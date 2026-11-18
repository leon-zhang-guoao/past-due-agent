(ns open.atlas.common.connector.api.probe-stream-test
  "The STREAMING probe (8.4.23 裁定十七 and its 补裁 of 2026-09-04).

   The立项 case is a catalog endpoint that answers with the whole delta feed
   and honours no page parameter: 裁定七's «read it all, refuse over the
   ceiling» closed the door on exactly those sources. The probe now samples the
   first N records (100 by default) and stops at whichever comes first — N
   records or the byte ceiling. So what is asserted here is not «it parsed
   something» but the physical claims the ruling makes:

   - with `data_path`, the bytes READ are a small fraction of the response, a
     hundred records come back, and the server sees the client hang up
     mid-body;
   - `sample_size` is obeyed, and the ceiling wins when it comes first;
   - without `data_path`, records are found inside the ceiling, and a first
     record that does not FIT is refused rather than truncated.

   These use a real socket (see `stub`), because a mock cannot witness a
   disconnect."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.api.stub :as stub]))

;; Bulkhead and breaker state is process-wide and keyed by source identity
;; (D6.5) -- it deliberately outlives a compiled env, so tests that trip a
;; breaker must not decide the next test's verdict.
(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(defn- source [port]
  {:type "api" :base_url (str "http://127.0.0.1:" port)})

;; A feed with far more records than the sample size, each small enough that a
;; whole sample fits well inside the ceiling — the case where the RECORD COUNT
;; is what stops the probe.
(def ^:private records (* 20 api/default-sample-size))
(def ^:private pad 512)

(deftest data-path-samples-the-default-and-disconnects-test
  (let [{:keys [port written broken? stop]} (stub/serve! (stub/products-feed-chunks records pad))]
    (try
      (let [r (api/probe (source port) {:path "/catalog" :data_path "$.products"})]
        (is (= 200 (:status r)))
        (is (true? (:truncated? r)))
        (is (= "$.products" (:records-path r)))
        (is (= api/default-sample-size (:records r)) "the default sample size (补裁 2026-09-04)")
        (is (= api/default-sample-size (count (:sample r))))
        (is (= {:id 0 :name "item-0" :blurb (apply str (repeat pad \x))}
               (first (:sample r)))
            "complete records, in the order they arrived")
        (is (= (dec api/default-sample-size) (:id (last (:sample r)))))
        (testing "the ceiling bounds the bytes READ, not the response"
          (is (< (:bytes-read r) (quot api/probe-max-bytes 4))
              (str "read " (:bytes-read r) " bytes"))
          (is (< (:bytes-read r) (quot (* records pad) 10))
              "a small fraction of the feed the endpoint would have sent"))
        (testing "and the connection is dropped there"
          ;; The server is still writing when the probe walks away; give the
          ;; write loop a moment to discover it.
          (loop [n 0]
            (when (and (not @broken?) (< n 100))
              (Thread/sleep 20)
              (recur (inc n))))
          (is (true? @broken?) "the stub saw the client disconnect early")
          (is (< @written (* records pad)) "the whole feed was never written")))
      (finally (stop)))))

(deftest sample-size-is-obeyed-test
  (let [{:keys [port stop]} (stub/serve! (stub/products-feed-chunks records pad))]
    (try
      (let [r (api/probe (source port) {:path "/catalog" :data_path "$.products"
                                        :sample_size 5})]
        (is (= 5 (:records r)))
        (is (= [0 1 2 3 4] (mapv :id (:sample r))))
        (is (< (:bytes-read r) (* 16 1024))
            "five records cost five records' worth of bytes"))
      (finally (stop)))))

(deftest the-ceiling-wins-when-it-comes-first-test
  ;; Records fat enough that a whole sample cannot fit under the ceiling, so
  ;; the probe stops on BYTES and hands back the complete records it did read
  ;; (补裁: 先到哪个停哪个). Sized from the constants: a quarter of the sample
  ;; already fills the ceiling.
  (let [big-pad (quot (* 4 api/probe-max-bytes) api/default-sample-size)
        {:keys [port stop]} (stub/serve! (stub/products-feed-chunks
                                          api/default-sample-size big-pad))]
    (try
      (let [r (api/probe (source port) {:path "/catalog" :data_path "$.products"})]
        (is (< 0 (:records r) api/default-sample-size)
            (str "read " (:records r) " records"))
        (is (= (:records r) (count (:sample r))))
        (is (<= (:bytes-read r) api/probe-max-bytes))
        (is (every? #(contains? % :blurb) (:sample r))
            "every record handed back is complete"))
      (finally (stop)))))

(deftest without-a-data-path-records-are-found-inside-the-ceiling-test
  (let [{:keys [port stop]} (stub/serve! (stub/products-feed-chunks records pad))]
    (try
      (let [r (api/probe (source port) {:path "/catalog"})]
        (is (= 200 (:status r)))
        (is (true? (:truncated? r)))
        (is (= "$.products" (:records-path r))
            "the first array of objects, reported so the caller can plan :data_path")
        (is (= api/default-sample-size (:records r)))
        (is (= 0 (:id (first (:sample r)))))
        (is (<= (:bytes-read r) api/probe-max-bytes)))
      (finally (stop)))))

(deftest a-first-record-larger-than-the-ceiling-is-refused-test
  ;; One record bigger than the whole ceiling: nothing complete can be read.
  (let [{:keys [port stop]} (stub/serve! (stub/products-feed-chunks
                                          4 (+ api/probe-max-bytes 1024)))]
    (try
      (let [r (api/probe (source port) {:path "/catalog"})]
        (is (true? (:over-limit? r)))
        (is (nil? (:sample r))
            "a record cut in half is not a record — refused, never truncated")
        (is (<= (:bytes-read r) api/probe-max-bytes)))
      (finally (stop)))))

(deftest a-small-response-is-read-whole-test
  (let [{:keys [port stop]}
        (stub/serve! ["{\"products\":[{\"id\":1},{\"id\":2}]}"])]
    (try
      (let [r (api/probe (source port) {:path "/catalog"})]
        (is (= {:products [{:id 1} {:id 2}]} (:sample r))
            "under the ceiling the sample is the whole body, as before 裁定十七")
        (is (nil? (:truncated? r)))
        (is (nil? (:records r)) "nothing located records, so no count is claimed")
        (is (= (:bytes r) (:bytes-read r))))
      (finally (stop)))))
