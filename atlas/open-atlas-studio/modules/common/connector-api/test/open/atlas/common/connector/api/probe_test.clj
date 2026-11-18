(ns open.atlas.common.connector.api.probe-test
  "The probe entry (8.4.14 裁定七): one real call, one sample back.

   What must hold, and why each one is here rather than assumed:
   a status code is an ANSWER (a plan has to be able to report «that endpoint
   said 404»); a body over the ceiling is REFUSED and never carried out of the
   function, because fields inferred from half a body are not the endpoint's
   fields; and the query parameters go on the URL exactly once, in a stable
   order, so two probes of the same request are the same request.

   HTTP is mocked — no sockets, same discipline as `failure_test`. The mock
   answers with a STREAM because 8.4.23 裁定十七 made the probe read one
   (`:as :stream`); the RESULTS asserted here are the pre-裁定十七 ones,
   which is the ruling's «小响应行为与今天相同»."
  (:require [clj-http.client :as http]
            [open.atlas.common.connector.api.stub :as stub]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.api :as api]))

;; Bulkhead and breaker state is process-wide and keyed by source identity
;; (D6.5) -- it deliberately outlives a compiled env, so tests that trip a
;; breaker must not decide the next test's verdict.
(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(def source
  {:type "api" :base_url "http://upstream.test"})

(deftest probe-returns-a-sample-and-its-size-test
  (let [seen (atom nil)]
    (with-redefs [http/get (fn [url opts]
                             (reset! seen {:url url :opts opts})
                             {:status 200 :body (stub/stream "{\"rows\":[{\"id\":1,\"name\":\"acme\"}]}")})]
      (let [r (api/probe source {:path "/invoices" :params {:page 1 :size 2}})]
        (is (= 200 (:status r)))
        (is (= {:rows [{:id 1 :name "acme"}]} (:sample r)))
        (is (= (alength (.getBytes "{\"rows\":[{\"id\":1,\"name\":\"acme\"}]}" "UTF-8"))
               (:bytes r)))
        (testing "one call, one page — the parameters are sent, never followed"
          (is (str/starts-with? (:url @seen) "http://upstream.test/invoices?"))
          (is (str/includes? (:url @seen) "page=1"))
          (is (str/includes? (:url @seen) "size=2")))))))

(deftest probe-reports-a-non-2xx-as-an-answer-test
  (with-redefs [http/get (fn [_ _] {:status 404 :body (stub/stream "not found")})]
    (let [r (api/probe source {:path "/nope"})]
      (is (= 404 (:status r)))
      (is (nil? (:sample r)) "a refusal carries no body")
      (is (pos? (:bytes r))))))

(deftest probe-refuses-a-body-over-the-ceiling-test
  ;; One string value larger than the whole ceiling — sized FROM the constant,
  ;; which the owner has already moved once (256 KiB → 8 MiB, 2026-09-04).
  (let [big (str "{\"a\":\"" (apply str (repeat (+ api/probe-max-bytes 1024) \x)) "\"}")]
    (with-redefs [http/get (fn [_ _] {:status 200 :body (stub/stream big)})]
      (let [r (api/probe source {:path "/big"})]
        (is (true? (:over-limit? r)))
        (is (nil? (:sample r))
            "over the ceiling nothing is carried out — refuse, never truncate")
        ;; 8.4.23 裁定十七: past the ceiling what is known is how much was
        ;; READ, not how big the answer was — so that is what is asserted.
        (is (= api/probe-max-bytes (:bytes-read r)))))))

(deftest probe-does-not-carry-the-body-into-a-trace-test
  (let [traced (atom [])]
    (with-redefs [http/get (fn [_ _] {:status 200 :body (stub/stream "{\"secret\":\"do-not-log\"}")})
                  open.atlas.common.connector.spi/trace-statement!
                  (fn [s] (swap! traced conj s))]
      (api/probe source {:path "/x"})
      (is (not (str/includes? (pr-str @traced) "do-not-log"))
          "the trace channel is configuration only (finding-v1 §5)"))))
