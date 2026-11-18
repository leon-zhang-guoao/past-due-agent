(ns open.atlas.common.connector.api.impl.paging
  "Connector-driven paging for collection reads (doc 107 §107.6, owner ruling
   2026-09-18).

   **The connector drives the loop, not the caller.** A caller that had to ask
   for page 2 itself would need to know the strategy, and then the bounds would
   be advice rather than enforcement. Here they are enforcement: every bound
   REFUSES rather than truncating (D2.9), because a short answer and a complete
   one are indistinguishable to whoever reads them, and an agent will happily
   reason about the rows that silently went missing.

   **Every page is an attempt.** It goes out through `execute!` like any other
   request, so it gets its own deadline check, its own place in the bulkhead and
   breaker, its own retry under the effective policy, and its own statement
   receipt (§107.7). A 429 on page 2 retries page 2 -- not the whole collection,
   which would re-fetch everything already in hand and hit the same limit again.

   **What this ns will not do**: return part of a collection. Every exit is
   either the whole requested range or a refusal naming the bound that stopped
   it."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]))

(defn strategy
  "The declared strategy, `:none` when the block does not page."
  [collection]
  (let [p (:paging collection)]
    (if (map? p) (or (:strategy p) :none) :none)))

(defn pages?
  "Does this collection read drive a paging loop at all?"
  [collection]
  (not= :none (strategy collection)))

(defn- limit-exceeded!
  "Refuse, naming WHICH bound stopped us (D2.9 / §107.6).

   The bound's name is the whole value of this refusal: «too many» tells a
   modeller nothing, while «:max-pages, 200» tells him which declaration to
   revisit. Counts and limits only -- never a row, a cursor or a URL."
  [ctx bound max actual]
  ;; The bound rides `fail!`'s `:limit` (doc 107 §107.6): the engine turns it
  ;; into `:engine.limit/exceeded`, the SAME code and fields the four EQL
  ;; boundaries answer with. One rule, one shape -- a collection bound and a
  ;; request-size bound are the same refusal to the caller, and giving them two
  ;; shapes would make «was this truncated?» a question about which subsystem
  ;; answered. The name and the two numbers also ride the message, for a reader
  ;; who only ever sees the exception.
  (spi/fail! :source ctx
             (str "collection exceeded " bound " (limit " max ", reached " actual ")")
             {:limit {:limit bound :max max :actual actual}}))

;; ---------------------------------------------------------------------------
;; Reading the next page's handle out of a response
;; ---------------------------------------------------------------------------

(defn- path-get
  "Read a `$.a.b` / `a.b` path out of a parsed body. Deliberately tiny: this
   reads ONE scalar handle (a cursor, a snapshot id), not a row shape, and the
   full JSONPath machinery in `impl.jsonpath` would invite `:next-path` to
   become an expression language."
  [body path]
  (when (and body path)
    (reduce (fn [acc seg]
              (when (map? acc) (get acc (keyword seg))))
            body
            (remove str/blank? (str/split (str/replace (str path) #"^\$\.?" "") #"\.")))))

(def ^:private link-next
  ;; RFC 8288 `Link: <url>; rel="next"`, one link per match.
  #"<([^>]+)>\s*;[^,]*\brel\s*=\s*\"?next\"?")

(defn- header-value [headers k]
  (some (fn [[hk v]] (when (and hk (= k (str/lower-case (name hk)))) v)) headers))

(defn- same-origin?
  "Is `candidate` the same scheme, host and effective port as `base`?

   §107.6 requires this and requires it AFTER relative resolution: a `Link`
   header is upstream-controlled content, and following it blindly would send
   this source's Authorization header wherever the upstream pointed. Effective
   port, not literal: `https://h` and `https://h:443` are the same host, and
   treating them as different origins would refuse a perfectly ordinary feed."
  [base candidate]
  (try
    (let [b (java.net.URI. (str base))
          c (java.net.URI. (str candidate))
          port (fn [^java.net.URI u]
                 (if (neg? (.getPort u))
                   (case (str/lower-case (str (.getScheme u))) "https" 443 "http" 80 -1)
                   (.getPort u)))]
      (and (= (str/lower-case (str (.getScheme b))) (str/lower-case (str (.getScheme c))))
           (= (str/lower-case (str (.getHost b))) (str/lower-case (str (.getHost c))))
           (= (port b) (port c))))
    (catch Exception _ false)))

(defn- next-handle
  "The handle for the page after this one, or nil when the feed is exhausted.

   For `:link-header` the handle is an absolute URL (already origin-checked);
   for the others it is whatever `:next-path` / the arithmetic produced."
  [{:keys [paging base-url ctx page-index rows-so-far page-rows]} resp]
  ;; `resp` is the `{:body :headers}` envelope `execute!` returns for a paged
  ;; read -- the body alone could not carry a `Link` header.
  (case (:strategy paging)
    :cursor (path-get (:body resp) (:next-path paging))

    :link-header
    (when-let [raw (header-value (:headers resp) "link")]
      (when-let [[_ target] (re-find link-next raw)]
        (let [resolved (str (.resolve (java.net.URI. (str base-url)) (str target)))]
          (if (same-origin? base-url resolved)
            resolved
            ;; Refused, not skipped: quietly stopping here would answer a
            ;; partial collection, and quietly following it would forward this
            ;; source's credentials to a host the modeller never named.
            (spi/fail! :source ctx
                       "the Link rel=next target is on a different origin")))))

    ;; offset/page have no explicit "next": a page that came back EMPTY is the
    ;; end of the feed. (`:cursor` and `:link-header` are the opposite case --
    ;; there an empty page with a next is ordinary, see §107.6 -- which is why
    ;; the two families cannot share one exhaustion rule.)
    :offset (when (pos? (or page-rows 0)) (or rows-so-far 0))
    :page   (when (pos? (or page-rows 0)) (inc page-index))
    nil))

(defn- request-params
  "The query parameters carrying this page's position."
  [{:keys [paging]} handle]
  (let [{:keys [param size-param page-size strategy]} paging]
    (cond-> {}
      (and param (#{:offset :page} strategy) (some? handle)) (assoc param (str handle))
      (and size-param page-size)                             (assoc size-param (str page-size))
      (and param (= :cursor strategy) (some? handle))        (assoc param (str handle)))))

;; ---------------------------------------------------------------------------
;; The loop
;; ---------------------------------------------------------------------------

(defn drive!
  "Walk the pages and answer every row of the requested range, or refuse.

   `fetch` takes `{:params … :url … :page N}` and answers the parsed response
   (it is one `execute!` call, so one attempt with its own deadline, policy and
   receipt). `rows-of` turns a response into that page's rows.

   Returns `{:rows [...] :exhausted? bool :snapshot id-or-nil :pages n}`.
   `:exhausted? false` means «we stopped because the caller's `:limit` was
   reached», which is NOT a claim that the source has no more rows -- §107.6
   insists an early stop says so rather than letting a consumer infer the feed
   ended."
  [{:keys [collection base-url ctx limit] :as spec} fetch rows-of]
  (let [paging   (:paging collection)
        bounds   (:bounds collection)
        max-items (:max-items bounds)
        max-pages (:max-pages bounds)
        snapshot-path (:snapshot-path paging)
        strategy (:strategy paging)]
    (loop [handle    (case strategy :page 1 :offset 0 nil)
           page      1
           rows      []
           snapshot  ::unset
           seen      #{}]
      ;; Cursor reuse is how a paging loop becomes an infinite one, and the
      ;; upstream that does it is usually not lying on purpose -- it is a bug in
      ;; its own pager. Refusing names the source; looping forever names nothing
      ;; and burns the whole budget (§107.6).
      (when (and (some? handle) (#{:cursor :link-header} strategy) (contains? seen handle))
        (spi/fail! :source ctx "paging cursor repeated: the upstream pager loops"))
      (when (and max-pages (> page max-pages))
        (limit-exceeded! ctx :max-pages max-pages page))

      (let [spec (assoc spec :paging paging)
            resp (fetch {:params (request-params spec handle)
                         :url    (when (= :link-header strategy) handle)
                         :page   page})
            page-rows (vec (rows-of resp))
            id   (when snapshot-path (path-get (:body resp) snapshot-path))
            _    (when snapshot-path
                   ;; §107.6: declaring a snapshot means promising every page
                   ;; came from ONE. Stitching pages from two snapshots gives a
                   ;; result that never existed at any instant.
                   (when (or (nil? id) (and (not= snapshot ::unset) (not= id snapshot)))
                     (spi/fail! :source ctx
                                "collection snapshot identity is missing or changed between pages")))
            all  (into rows page-rows)
            next-h (next-handle (assoc spec :page-index page
                                       :rows-so-far (count all)
                                       :page-rows (count page-rows))
                                resp)]

        (cond
          ;; A bound is a refusal, never a trim: the caller asked for a range
          ;; and either gets it or is told which limit stopped it (D2.9).
          (and max-items (> (count all) max-items))
          (limit-exceeded! ctx :max-items max-items (count all))

          ;; The caller's own `:limit`: an early stop, and flagged as one.
          (and limit (>= (count all) limit))
          {:rows (vec (take limit all)) :exhausted? false :pages page
           :snapshot (when (not= snapshot ::unset) snapshot)}

          ;; An empty page that still offers a next is NOT exhaustion -- a
          ;; sparse filter answers exactly that, and stopping would truncate.
          (nil? next-h)
          {:rows all :exhausted? true :pages page
           :snapshot (if (= snapshot ::unset) id snapshot)}

          :else
          (recur next-h (inc page) all
                 (if (= snapshot ::unset) id snapshot)
                 (cond-> seen (some? handle) (conj handle))))))))
