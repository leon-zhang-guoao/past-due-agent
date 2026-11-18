(ns open.atlas.common.connector.api.paging-test
  "doc 107 §107.6 / §107.8: the connector drives the paging loop, every page is
   an attempt, and every bound REFUSES rather than truncating.

   A real server, because most of what is claimed here is about the REQUESTS:
   how many went out, carrying which position, and to which origin. A mock that
   handed back pages on demand would assert the shape of the answer while
   leaving the interesting half -- the traffic -- unobserved."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.stub :as stub]
            [open.atlas.common.connector.spi :as spi]))

(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(def ^:private base-resolver
  {:id "all_parts" :input [] :result_type "list" :output [:p/sku]})

;; `:semantic/collection` rides the RESOLVER -- that is where `compile-candidate`
;; puts it -- while `:base_url` and friends ride the source. `read-all` keeps the
;; halves together so each test names only the thing it is varying.
(defn- source [port]
  {:type "api" :base_url (str "http://127.0.0.1:" port)
   :method "GET" :endpoint "/parts"
   :field_mapping {:p/sku "sku"}
   :data_path "items"
   :resilience {:timeout_ms 2000}})

(defn- collection [paging & {:as bound-overrides}]
  {:ordering :source-defined :completeness :not-guaranteed
   :paging paging
   :bounds (merge {:max-items 10000 :max-response-bytes 4194304
                   :timeout-ms 30000 :max-pages 200}
                  bound-overrides)})

(defn- rows [n from]
  (mapv (fn [i] {:sku (str "S" (+ from i))}) (range n)))

(defn- query-of [request-line]
  (let [target (second (str/split (str request-line) #"\s+"))]
    (into {} (for [pair (str/split (or (second (str/split (str target) #"\?" 2)) "") #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when (seq k)]
               [k v]))))

(defn- read-all
  "Compile and run one collection read. `eql` carries `:limit` when a test
   exercises the caller's own cap; `source-over` varies the source half."
  ([port coll] (read-all port coll {} {}))
  ([port coll eql] (read-all port coll eql {}))
  ([port coll eql source-over]
   ((spi/compile-source (merge (source port) source-over) nil
                        (assoc base-resolver :semantic/collection coll))
    {} eql)))

(defn- caught [f] (try (f) ::no-throw (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; cursor
;; ---------------------------------------------------------------------------

(def ^:private cursor-paging
  {:strategy :cursor :param "cursor" :next-path "$.meta.next"
   :size-param "limit" :page-size 100})

(defn- cursor-feed
  "Three pages of 100, linked by `meta.next`."
  [& {:keys [snapshot pages] :or {pages 3}}]
  (fn [line]
    (let [page (Integer/parseInt (or (get (query-of line) "cursor") "1"))]
      {:status 200
       :body (json/generate-string
              (cond-> {:items (rows 100 (* 100 (dec page)))
                       :meta (cond-> {} (< page pages) (assoc :next (str (inc page))))}
                snapshot (assoc-in [:meta :snapshot_id] (snapshot page))))})))

(deftest a-limit-stops-early-and-says-so
  ;; §107.8: three pages of 100, `:limit 150`, `:page-size 100` -> exactly TWO
  ;; requests and 150 rows. The third page is never fetched: the caller asked
  ;; for 150, and a request whose rows would be discarded is one the source pays
  ;; for and nobody reads.
  (let [{:keys [port requests stop]} (stub/serve-pages! (cursor-feed))]
    (try
      (let [out (read-all port (collection cursor-paging) {:limit 150})]
        (is (= 150 (count out)))
        (is (= "S0" (:p/sku (first out))))
        (is (= "S149" (:p/sku (last out))))
        (is (= 2 (count @requests)) "exactly two pages, not three")
        (is (= "100" (get (query-of (first @requests)) "limit"))
            "the declared page size rides the request"))
      (finally (stop)))))

(deftest a-bound-refuses-and-answers-no-rows-at-all
  ;; D2.9 / §107.6: a bound REFUSES. Truncating would hand back 120 rows that
  ;; are indistinguishable from a complete answer of 120 rows.
  (let [{:keys [port stop]} (stub/serve-pages! (cursor-feed))]
    (try
      (let [e (caught #(read-all port (collection cursor-paging :max-items 120)))]
        (is (spi/failure? e) (pr-str e))
        (is (= :source (spi/error-category e)))
        (is (= {:limit :max-items :max 120 :actual 200} (:limit (ex-data e)))
            "names WHICH bound and by how much -- «too many» names nothing")
        (is (= #{:error/category :resolver-id :source-type :limit}
               (set (keys (ex-data e))))
            "and nothing else rides along: the key face stays closed"))
      (finally (stop)))))

(deftest max-pages-bounds-the-requests-not-only-the-rows
  ;; §107.6: empty pages count too, so the request ceiling is its own bound. A
  ;; sparse filter that answers empty-with-a-next forever is otherwise limited
  ;; only by how long the caller is willing to wait.
  (let [{:keys [port requests stop]} (stub/serve-pages! (cursor-feed))]
    (try
      (let [e (caught #(read-all port (collection cursor-paging :max-pages 2)))]
        (is (= :max-pages (:limit (:limit (ex-data e)))))
        (is (= 2 (count @requests)) "it refused instead of fetching a third"))
      (finally (stop)))))

(deftest an-empty-page-that-still-offers-a-next-is-not-exhaustion
  (let [{:keys [port requests stop]}
        (stub/serve-pages!
         (fn [line]
           (let [page (Integer/parseInt (or (get (query-of line) "cursor") "1"))]
             {:status 200
              :body (json/generate-string
                     {:items (if (= 2 page) [] (rows 2 (* 10 page)))
                      :meta (when (< page 3) {:next (str (inc page))})})})))]
    (try
      (is (= 4 (count (read-all port (collection cursor-paging))))
          "page 2 was empty and the walk continued")
      (is (= 3 (count @requests)))
      (finally (stop)))))

(deftest a-repeated-cursor-is-refused-rather-than-followed-forever
  (let [{:keys [port stop]}
        (stub/serve-pages! (fn [_] {:status 200
                                    :body (json/generate-string
                                           {:items (rows 1 0) :meta {:next "same"}})}))]
    (try
      (let [e (caught #(read-all port (collection cursor-paging)))]
        (is (spi/failure? e) (pr-str e))
        (is (str/includes? (ex-message e) "pager loops")
            "an upstream whose pager loops is named, not waited on"))
      (finally (stop)))))

(deftest a-snapshot-identity-must-hold-across-every-page
  ;; §107.6: declaring `:snapshot-path` promises the pages came from ONE
  ;; snapshot. Stitching two gives a collection that never existed at any
  ;; instant -- worse than refusing, because it looks fine.
  (let [with-snapshot (assoc cursor-paging :snapshot-path "$.meta.snapshot_id")]
    (testing "a stable identity is carried"
      (let [{:keys [port stop]} (stub/serve-pages! (cursor-feed :snapshot (constantly "snap-1")))]
        (try (is (= 300 (count (read-all port (collection with-snapshot)))))
             (finally (stop)))))
    (testing "an identity that CHANGES between pages is refused"
      (let [{:keys [port stop]} (stub/serve-pages! (cursor-feed :snapshot #(str "snap-" %)))]
        (try
          (let [e (caught #(read-all port (collection with-snapshot)))]
            (is (spi/failure? e) (pr-str e))
            (is (str/includes? (ex-message e) "snapshot identity")))
          (finally (stop)))))
    (testing "a declared identity that is absent is refused too"
      (let [{:keys [port stop]} (stub/serve-pages! (cursor-feed))]
        (try
          (let [e (caught #(read-all port (collection with-snapshot)))]
            (is (spi/failure? e) (pr-str e))
            (is (str/includes? (ex-message e) "snapshot identity")))
          (finally (stop)))))))

;; ---------------------------------------------------------------------------
;; offset / page
;; ---------------------------------------------------------------------------

(deftest offset-paging-walks-by-row-count
  (let [{:keys [port requests stop]}
        (stub/serve-pages!
         (fn [line]
           (let [off (Integer/parseInt (or (get (query-of line) "offset") "0"))]
             {:status 200 :body (json/generate-string
                                 {:items (if (< off 20) (rows 10 off) [])})})))]
    (try
      ;; An empty page ends an offset walk: the rows-so-far handle stops moving,
      ;; which is the same position twice and therefore exhaustion.
      (is (= 20 (count (read-all port (collection {:strategy :offset :param "offset"
                                                   :size-param "limit" :page-size 10})))))
      (is (= ["0" "10" "20"] (mapv #(get (query-of %) "offset") @requests))
          "each request carries the rows already in hand as its offset")
      (finally (stop)))))

(deftest page-paging-starts-at-one-and-counts-up
  (let [{:keys [port requests stop]}
        (stub/serve-pages!
         (fn [line]
           (let [p (Integer/parseInt (or (get (query-of line) "page") "1"))]
             {:status 200 :body (json/generate-string
                                 {:items (if (<= p 2) (rows 5 (* 5 (dec p))) [])})})))]
    (try
      (is (= 10 (count (read-all port (collection {:strategy :page :param "page"
                                                   :size-param "per_page" :page-size 5})))))
      (is (= ["1" "2" "3"] (mapv #(get (query-of %) "page") @requests))
          "page numbers start at 1 -- not 0, which would skip or duplicate a page")
      (finally (stop)))))

;; ---------------------------------------------------------------------------
;; Link header
;; ---------------------------------------------------------------------------

(deftest link-header-paging-follows-rel-next-within-the-same-origin
  (let [{:keys [port requests stop]}
        (stub/serve-pages!
         (fn [line]
           (if (get (query-of line) "after")
             {:status 200 :body (json/generate-string {:items (rows 2 2)})}
             {:status 200
              :headers {"Link" "</parts?after=2>; rel=\"next\""}
              :body (json/generate-string {:items (rows 2 0)})})))]
    (try
      (is (= 4 (count (read-all port (collection {:strategy :link-header})))))
      (is (= 2 (count @requests)))
      (is (str/includes? (second @requests) "after=2")
          "the relative Link target was resolved against the base url")
      (finally (stop)))))

(deftest a-cross-origin-link-next-is-refused-and-never-followed
  ;; §107.6: a Link header is upstream-controlled content. Following it blindly
  ;; would send this source's Authorization header to whatever host it named.
  (let [other (stub/serve-pages! (fn [_] {:status 200 :body "{\"items\":[]}"}))
        {:keys [port requests stop]}
        (stub/serve-pages!
         (fn [_] {:status 200
                  :headers {"Link" (str "<http://127.0.0.1:" (:port other)
                                        "/parts>; rel=\"next\"")}
                  :body (json/generate-string {:items (rows 2 0)})}))]
    (try
      (let [e (caught #(read-all port (collection {:strategy :link-header})))]
        (is (spi/failure? e) (pr-str e))
        (is (= :source (spi/error-category e)))
        (is (= 1 (count @requests)))
        (is (zero? (count @(:requests other)))
            "nothing was sent to the other origin -- credentials least of all"))
      (finally (stop) ((:stop other))))))

;; ---------------------------------------------------------------------------
;; Every page is an attempt (§107.7)
;; ---------------------------------------------------------------------------

(deftest a-429-on-page-two-retries-page-two-only
  (let [seen (atom 0)
        {:keys [port requests stop]}
        (stub/serve-pages!
         (fn [line]
           (let [page (Integer/parseInt (or (get (query-of line) "cursor") "1"))]
             (cond
               (= 1 page) {:status 200 :body (json/generate-string
                                              {:items (rows 2 0) :meta {:next "2"}})}
               (= 2 page) (if (= 1 (swap! seen inc))
                            {:status 429 :headers {"Retry-After" "0"} :body "{}"}
                            {:status 200 :body (json/generate-string {:items (rows 2 2)})})
               :else {:status 200 :body (json/generate-string {:items []})}))))]
    (try
      (let [out (spi/with-retry-sleep
                  (fn [_] nil)
                  (read-all port (collection cursor-paging) {}
                            {:resilience {:timeout_ms 2000 :max_retries 2
                                          :retry_on #{:rate_limited}
                                          :backoff_ms [1 2 1.0] :jitter 0}}))]
        (is (= 4 (count out)) "both pages' rows, once each")
        (is (= 3 (count @requests))
            "page 1, page 2 (429), page 2 again -- page 1 was not re-fetched"))
      (finally (stop)))))

;; ---------------------------------------------------------------------------
;; The receipts (§107.6: every page leaves one, and says which page it was)
;; ---------------------------------------------------------------------------

(deftest every-page-leaves-its-own-numbered-receipt
  ;; One summary receipt would report three upstream calls as one, and an audit
  ;; reader could not tell a one-page collection from a thirty-page one.
  (let [{:keys [port stop]} (stub/serve-pages! (cursor-feed))
        statements (atom [])]
    (try
      (binding [spi/*statement-trace* #(swap! statements conj %)]
        (is (= 300 (count (read-all port (collection cursor-paging))))))
      (let [summary (spi/statement-audit-summary @statements)]
        (is (= 3 (:count summary)))
        (is (= [1 2 3] (mapv :page (:by-resolver summary))))
        (is (not (str/includes? (pr-str summary) "S1"))
            "still names and counts only -- no row reaches the audit"))
      (finally (stop)))))
