(ns open.atlas.common.connector.api-kit.impl.replay
  "The replay stub: a fixture directory answering `clj-http.client/get` and
   `/post`.

   It installs itself through the SAME `with-redefs` seam the connector's own
   tests use, deliberately: a stub that intercepted lower (a fake connection
   manager, a local socket) would also replace the code path under test, and
   a stub that intercepted higher would not exercise `impl.request` at all —
   including the 401 retry, the timeout classification and the trace receipt,
   which are three of the things the generated suite is for.

   An unmatched request FAILS. It does not fall through to the network and it
   does not answer an empty body: both turn «the fixture is stale» into «the
   connector returned nothing», which is the wrong bug to go looking for. The
   refusal names the endpoint template and the parameter names it could not
   match, and never a value (finding-v1 §5)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [open.atlas.common.connector.api-kit.impl.fixture :as fx]))

;; ---------------------------------------------------------------------------
;; Overlays — the failures a recording cannot contain
;; ---------------------------------------------------------------------------
;;
;; A fixture records what the upstream DID answer. 401/429/503, a timeout and
;; a paging chain are things it mostly did not, and provoking them against a
;; live partner endpoint is not something a test suite may do. So they are
;; declared as overlays on top of a recorded exchange rather than recorded:
;; the request still goes through the connector, only the answer is dressed.

(defn- selects?
  "Does this overlay apply to this request? `:when` is
   `{:placeholder \"NSYMM86\"}` (the value appears in the decoded query or
   path — that is what «a status override for a given placeholder value»
   means) or `{:param \"date-of-reference\"}` or `{:nth 1}` (the n-th match,
   for a chain)."
  [overlay rk n]
  (let [{:keys [placeholder param nth]} (:when overlay)
        text (str (:path rk) "?" (str/join "&" (map (fn [[k v]] (str k "=" v)) (:query rk))))]
    (and (or (nil? placeholder) (str/includes? text (str placeholder)))
         (or (nil? param) (contains? (:query rk) (str param)))
         (or (nil? nth) (= nth n)))))

(defn- trickling-stream
  "Headers, then a partial body, then silence — a `SocketTimeoutException` on
   the read that never completes. This is the only honest way to test the
   timeout branch: a stub that threw immediately would test the exception
   handler, not the socket timeout that produces it."
  [^String prefix delay-ms]
  (let [bs   (.getBytes prefix "UTF-8")
        pos  (atom 0)
        stop (fn [] (Thread/sleep (long delay-ms))
               (throw (java.net.SocketTimeoutException. "Read timed out")))]
    ;; All three `read` arities, deliberately: `proxy` routes every overload of
    ;; a name to one fn, so declaring only `read()` makes `read(byte[],int,int)`
    ;; — the one a reader actually calls — an ArityException, and the connector
    ;; would classify THAT instead of the timeout under test.
    (proxy [java.io.InputStream] []
      (read
        ([] (let [i @pos]
              (if (< i (alength bs))
                (do (swap! pos inc) (bit-and (aget bs i) 0xff))
                (stop))))
        ([buf] (let [^bytes b buf] (.read ^java.io.InputStream this b 0 (alength b))))
        ([buf off len]
         (let [^bytes b buf
               i @pos
               n (min (int len) (- (alength bs) i))]
           (if (pos? n)
             (do (System/arraycopy bs i b (int off) n) (swap! pos + n) n)
             (stop)))))
      (close [] nil))))

(defn- apply-overlay
  [resp {:keys [status headers link retry-after trickle body]}]
  (cond-> resp
    status      (assoc :status status)
    headers     (update :headers merge headers)
    retry-after (update :headers assoc "Retry-After" (str retry-after))
    link        (update :headers assoc "Link" link)
    body        (assoc :body (json/generate-string body))
    trickle     (assoc :body (trickling-stream (or (:prefix trickle) "{\"products\":[")
                                               (or (:delay-ms trickle) 50)))))

;; ---------------------------------------------------------------------------
;; The stub
;; ---------------------------------------------------------------------------

(defn index
  "`{request-key → [exchange …]}` for a fixture directory. Several exchanges
   under one key is a CHAIN (a cursor loop records the same request twice);
   they are answered in recorded order and the last one repeats."
  [fixtures]
  (reduce (fn [m fx]
            (reduce (fn [m e] (update m (:match e) (fnil conj []) e))
                    m (:api-kit/exchanges fx)))
          {} (vals fixtures)))

(defn shape
  "The weaker key: method, path and the sorted parameter NAMES. A recorded
   exchange answers a request that differs only in its placeholder VALUES —
   which is what makes the isolation split (whose sub-requests carry id
   subsets no recording ever saw) replayable at all."
  [rk]
  [(:method rk) (:path rk) (vec (sort (keys (:query rk))))])

(def ^:private by-shape
  (memoize (fn [idx]
             (reduce-kv (fn [m k v] (update m (shape k) (fnil into []) v)) {} idx))))

(defn unmatched
  "The structured refusal for a request no exchange answers. Names the
   template and the parameter names; never a value."
  [rk idx]
  {:code    :api-kit.replay/unmatched-request
   :method  (:method rk)
   :path    (:path rk)
   :params  (vec (sort (keys (:query rk))))
   :known   (vec (sort (distinct (map (fn [k] [(:method k) (:path k)
                                               (vec (sort (keys (:query k))))])
                                      (keys idx)))))})

(defn responder
  "→ `(fn [method url opts] → clj-http response)`. Overlays are bound when the
   stub is built, because an overlay describes the SCENARIO a test runs, not
   one call.

   An overlay that carries its own `:body` may answer a request the fixture
   never recorded. That is the paging walk: page two's query carries a position
   no recording ever saw, and the scenario — not the recording — is what says
   what page two contains. Every other unmatched request still refuses."
  [{:keys [fixtures overlays calls]}]
  (let [idx    (index fixtures)
        seen   (atom 0)]
    (fn [method url opts]
      (let [rk     (fx/request-key method url)
            n      (swap! seen inc)
            ov     (first (filter #(selects? % rk n) overlays))
            _      (when calls
                     ;; The ORIGIN is recorded here and nowhere else: a fixture
                     ;; must not carry one, but «did anything go to the other
                     ;; host» is exactly the cross-origin claim (§107.6).
                     (swap! calls conj (assoc (dissoc rk :query)
                                              :origin (fx/origin url))))
            chain  (or (get idx rk) (get (by-shape idx) (shape rk)))
            _      (when-not (or chain (:body ov))
                     (throw (ex-info "api-kit replay: no recorded exchange matches this request"
                                     (unmatched rk idx))))
            e      (when chain (nth chain (min (dec n) (dec (count chain)))))
            base   {:status  (or (:status e) 200)
                    :headers (into {} (:headers e))
                    :body    (json/generate-string (:body e))}
            resp   (if ov (apply-overlay base ov) base)]
        ;; clj-http reads the whole body before returning unless the caller
        ;; asked for `:as :stream`. A trickled body therefore surfaces its read
        ;; timeout HERE for an ordinary read, and is handed over intact to the
        ;; streaming probe — the same two shapes a real socket produces.
        (cond
          (and (instance? java.io.InputStream (:body resp)) (not= :stream (:as opts)))
          (assoc resp :body (slurp (:body resp)))

          ;; A source collection read (`execute-bounded!`) asks for `:as
          ;; :stream` and reads the bytes itself, so it must be handed a
          ;; stream — a String body there is not «a smaller answer», it is a
          ;; ClassCastException wearing a :source failure's clothes.
          (and (string? (:body resp)) (= :stream (:as opts)))
          (assoc resp :body (java.io.ByteArrayInputStream.
                             (.getBytes ^String (:body resp) "UTF-8")))

          :else resp)))))

(defn install-fn
  "The pair of functions `with-redefs` needs."
  [opts]
  (let [f (responder opts)]
    {:get  (fn [url o] (f :get url o))
     :post (fn [url o] (f :post url o))}))

(defmacro with-replay
  "Run `body` with `clj-http.client/get` and `/post` answered from a fixture
   directory. `opts` is `{:fixtures {…} :overlays [...]}`."
  [opts & body]
  `(let [fns# (install-fn ~opts)]
     (with-redefs [http/get  (:get fns#)
                   http/post (:post fns#)]
       ~@body)))
