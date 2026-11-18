(ns open.atlas.common.connector.api.semantic-binding-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [open.atlas.common.connector.api :as api]
            [open.atlas.common.connector.spi :as spi]))

;; Bulkhead and breaker state is process-wide and keyed by source identity
;; (D6.5) -- it deliberately outlives a compiled env, so tests that trip a
;; breaker must not decide the next test's verdict.
(use-fixtures :each (fn [t] (api/reset-resilience-state!) (t)))

(def binding
  {:binding/effect :read
   :binding/implementation {:method "GET" :endpoint "/items/{item/id}"
                            :field_mapping {:item/id "$.id" :item/children {:_from "$.children[*]" :child/id "$.id"}}}
   :binding/input-map {:item/id "item/id"}
   :binding/output-map {:item/id "item/id" :item/children "item/children" :child/id "child/id"}})

(deftest nested-mapping-is-preserved-and-slot-mismatch-rejected
  (is (empty? (:findings (api/validate-binding binding))))
  (is (= (get-in binding [:binding/implementation :field_mapping]) (:field_mapping (api/binding-operation binding))))
  (doseq [b [(assoc-in binding [:binding/output-map :child/id] "wrong")
             (assoc-in binding [:binding/implementation :method] "DELETE")
             (assoc-in binding [:binding/implementation :headers] {"Authorization" "secret"})]]
    (is (seq (:findings (api/validate-binding b))))))

(deftest post-read-maps-multiple-entities-in-one-request
  (let [b (-> binding
              (assoc-in [:binding/implementation :method] "POST")
              (assoc-in [:binding/implementation :endpoint] "/items/search")
              (assoc-in [:binding/implementation :json_body] {:id "{item/id}"})
              (assoc-in [:binding/implementation :field_mapping :item/expenses]
                        {:_from "$.expenses[*]" :expense/id "$.id"})
              (assoc-in [:binding/output-map :item/expenses] "item/expenses")
              (assoc-in [:binding/output-map :expense/id] "expense/id"))
        calls (atom [])
        source (assoc (api/binding-operation b) :base_url "https://fixture.invalid")
        resolver {:id "items-post" :input [:item/id] :result_type "single"}]
    (is (empty? (:findings (api/validate-binding b))))
    (with-redefs [http/post (fn [url opts]
                             (swap! calls conj [url opts])
                             {:status 200 :body "{\"id\":1,\"children\":[{\"id\":7}],\"expenses\":[{\"id\":8}]}"})]
      (is (= {:item/id 1 :item/children [{:child/id 7}] :item/expenses [{:expense/id 8}]}
             ((spi/compile-source source nil resolver) {:item/id 1} {}))))
    (is (= 1 (count @calls)))
    (is (= "https://fixture.invalid/items/search" (ffirst @calls)))
    (is (= {"id" 1} (json/parse-string (:body (second (first @calls))))))
    (doseq [bad [(assoc b :binding/effect :upsert)
                 (assoc-in b [:binding/implementation :method] "PUT")
                 (assoc-in b [:binding/implementation :method] "PATCH")
                 (assoc-in b [:binding/implementation :method] "DELETE")
                 (assoc-in b [:binding/implementation :method] "GET")
                 (assoc-in b [:binding/implementation :json_body] "invalid")]]
      (is (seq (:findings (api/validate-binding bad)))))
    (with-redefs [http/post (fn [& _] (throw (AssertionError. "Unresolved body must not reach HTTP")))]
      (is (thrown? clojure.lang.ExceptionInfo
                   ((spi/compile-source (assoc source :json_body {:id "{unknown/id}"}) nil resolver)
                    {:item/id 1} {}))))))

(deftest semantic-api-stream-budget-rejects-whole-response
  (let [source (assoc (api/binding-operation binding) :base_url "https://fixture.invalid")
        resolver {:id "items" :input [:item/id] :result_type "single"
                  :semantic/collection {:bounds {:max-response-bytes 16 :timeout-ms 1000}}}
        closed (atom false)
        stream (proxy [java.io.ByteArrayInputStream] [(.getBytes "{\"id\":1,\"padding\":\"too long\"}" "UTF-8")]
                 (close [] (reset! closed true) (proxy-super close)))]
    (with-redefs [http/get (fn [_ opts] (is (= :stream (:as opts))) {:status 200 :body stream})]
      (is (thrown? clojure.lang.ExceptionInfo ((spi/compile-source source nil resolver) {:item/id 1} {})))
      (is @closed))))

(deftest bounded-response-retains-nested-data
  (let [source (assoc (api/binding-operation binding) :base_url "https://fixture.invalid")
        resolver {:id "items" :input [:item/id] :result_type "single"
                  :semantic/collection {:bounds {:max-response-bytes 1024 :timeout-ms 1000}}}]
    (with-redefs [http/get (fn [_ _] {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes "{\"id\":1,\"children\":[{\"id\":7}]}" "UTF-8"))})]
      (is (= {:item/id 1 :item/children [{:child/id 7}]}
             ((spi/compile-source source nil resolver) {:item/id 1} {}))))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §2.2 (doc 109 §109.8 items 1-2, owner 2026-09-18):
;; api takes an optional root only as a `:query_params` placeholder -- absent,
;; the parameter is omitted -- and refuses it in `:endpoint` / `:json_body`;
;; a nested root is refused outright. nifi refuses both.
;; ---------------------------------------------------------------------------

(def ^:private optional-binding
  {:binding/effect :read
   :binding/implementation {:method "GET" :endpoint "/items"
                            :query_params {"id" "{item/id}" "email" "{customer/email}"}
                            :field_mapping {:item/id "$.id"}}
   :binding/input-map {:item/id "item/id" :customer/email "customer/email"}
   :binding/output-map {:item/id "item/id"}
   ;; What the execution layer adds for a 2.0 definition.
   :binding/input-optional #{:customer/email}})

(defn- codes-and-paths [b]
  (set (map (juxt :code :path) (:findings (api/validate-binding b)))))

(deftest an-absent-optional-query-param-is-omitted-from-the-request
  (is (empty? (:findings (api/validate-binding optional-binding))) (pr-str (api/validate-binding optional-binding)))
  (let [urls (atom [])
        source (assoc (api/binding-operation optional-binding) :base_url "https://fixture.invalid")
        resolver {:id "items-opt" :input [:item/id :customer/email] :input-optional #{:customer/email}
                  :result_type "single"}
        read! (spi/compile-source source nil resolver)]
    (with-redefs [http/get (fn [url _]
                            (swap! urls conj url)
                            {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes "{\"id\":1}" "UTF-8"))})]
      (is (= {:item/id 1} (read! {:item/id 1} {})))
      (is (= {:item/id 1} (read! {:item/id 1 :customer/email "a@b"} {}))))
    (let [[absent present] @urls]
      (is (= "https://fixture.invalid/items?id=1" absent) "the whole parameter is omitted, not sent empty")
      (is (.contains ^String present "id=1"))
      (is (.contains ^String present "email=a%40b")))))

(deftest an-optional-root-may-not-sit-in-the-endpoint-or-the-body
  (testing ":endpoint, in any of the three placeholder spellings"
    (doseq [endpoint ["/items/{customer/email}" "/items/{+customer/email}" "/items/{?customer/email}"]]
      (is (contains? (codes-and-paths (-> optional-binding
                                          (assoc-in [:binding/implementation :endpoint] endpoint)
                                          (update-in [:binding/implementation :query_params] dissoc "email")))
                     [:connector.binding/optional-placement [:binding/input-map :customer/email]])
          endpoint)))
  (testing ":json_body"
    (is (contains? (codes-and-paths (-> optional-binding
                                        (assoc-in [:binding/implementation :method] "POST")
                                        (assoc-in [:binding/implementation :json_body] {:email "{customer/email}"})
                                        (update-in [:binding/implementation :query_params] dissoc "email")))
                   [:connector.binding/optional-placement [:binding/input-map :customer/email]])))
  (testing "a required root in the endpoint is untouched by this rule"
    (is (not (some #(= :connector.binding/optional-placement (first %))
                   (codes-and-paths (dissoc binding :binding/input-optional)))))))

(deftest a-nested-root-is-refused-by-api
  (is (contains? (codes-and-paths (-> binding
                                      (assoc-in [:binding/input-map :item/children] "item/children")
                                      (assoc :binding/input-nested {:item/children [:child/id]})))
                 [:connector.binding/nested-input-unsupported [:binding/input-map :item/children]])))

(deftest nifi-refuses-both-forms
  (let [nifi {:binding/effect :read
              :binding/implementation {:operation :event :field_mapping {:nifi.event/id "$.id"}}
              :binding/input-map {:nifi.event/id "event-id"}
              :binding/output-map {:nifi.event/id "nifi.event/id"}}
        codes (fn [b] (set (map (juxt :code :path) (:findings (api/validate-nifi-binding b)))))]
    (is (contains? (codes (assoc nifi :binding/input-optional #{:nifi.event/id}))
                   [:connector.binding/optional-unsupported [:binding/input-map :nifi.event/id]]))
    (let [nested (codes (-> nifi
                            (assoc-in [:binding/input-map :nifi.event/children] "node-id")
                            (assoc :binding/input-nested {:nifi.event/children [:nifi.child/id]})))]
      (is (contains? nested [:connector.binding/nested-input-unsupported [:binding/input-map :nifi.event/children]]))
      (is (= 1 (count (filter #(= :connector.binding/nested-input-unsupported (first %)) nested)))
          "judged once by nifi, not again by the api rules it delegates to"))))

(deftest context-is-explicit-and-cannot-overwrite-api-evidence
  (let [b (-> binding
              (assoc-in [:binding/implementation :context] {:item/source {:value "local-source"}})
              (assoc-in [:binding/output-map :item/source] "item/source"))]
    (is (empty? (:findings (api/validate-binding b))))
    (is (seq (:findings (api/validate-binding (assoc-in b [:binding/implementation :context :item/id] {:value 2})))))
    (is (seq (:findings (api/validate-binding (assoc-in b [:binding/implementation :context :item/source] {:input :unknown/id})))))
    (let [resolver {:id "items" :input [:item/id] :result_type "single"
                    :semantic/output-context {:item/source {:value "local-source"}}}
          source (assoc (api/binding-operation b) :base_url "https://fixture.invalid")]
      (with-redefs [http/get (fn [_ _] {:status 200 :body "{\"id\":1,\"children\":[]}"})]
        (is (= "local-source" (:item/source ((spi/compile-source source nil resolver) {:item/id 1} {}))))))))

(def batch-binding
  {:binding/effect :read
   :binding/input-map {:item/id "item/id"}
   :binding/output-map {:item/name "item/name"}
   :binding/implementation
   {:method "GET" :endpoint "/items/{ids}" :data_path "$.items" :field_mapping {:item/name "$.name"}
    :batch {:parent-key :item/id :child-fk "id" :placeholder "ids" :chunk-size 25}}})

(deftest semantic-batch-validates-and-preserves-existing-api-contract
  (is (empty? (:findings (api/validate-binding batch-binding))))
  (is (= (get-in batch-binding [:binding/implementation :batch])
         (:batch (api/binding-operation batch-binding))))
  (doseq [bad [nil false true "invalid" {:parent-key :item/id}
               {:parent-key :item/id :child-fk "id" :placeholder "missing"}
               {:parent-key :item/id :child-fk "id" :placeholder "ids" :chunk-size 0}
               {:parent-key [:item/id :item/tenant] :child-fk "id" :placeholder "ids"}
               {:parent-key :item/id :child-fk "id" :placeholder "ids" :body-mode "root-array"}]]
    (is (seq (:findings (api/validate-binding
                        (assoc-in batch-binding [:binding/implementation :batch] bad))))
        (pr-str bad))))

(deftest semantic-post-batch-aligns-missing-and-duplicate-inputs
  (let [b (-> batch-binding
              (assoc-in [:binding/implementation :method] "POST")
              (assoc-in [:binding/implementation :endpoint] "/items/search")
              (assoc-in [:binding/implementation :json_body] {:ids "{ids}"}))
        calls (atom [])]
    (is (empty? (:findings (api/validate-binding b))))
    (with-redefs [http/post (fn [_ opts]
                             (swap! calls conj (json/parse-string (:body opts)))
                             {:status 200 :body "{\"items\":[{\"id\":2,\"name\":\"two\"},{\"id\":1,\"name\":\"one\"}]}"})]
      (is (= [{:item/name "one"} nil {:item/name "two"} {:item/name "one"}]
             ((spi/compile-batch-source
               (assoc (api/binding-operation b) :base_url "https://fixture.invalid") nil
               {:id "batch" :input [:item/id] :result_type "single"})
              [{:item/id 1} {:item/id 3} {:item/id 2} {:item/id 1}] {}))))
    (is (= [{"ids" [1 3 2]}] @calls))))

(deftest batch-context-and-transforms-apply-per-parent
  (doseq [result-type ["single" "list"]]
    (with-redefs [http/get (fn [& _]
                            {:status 200 :body "{\"items\":[{\"id\":1,\"amount\":7.5}]}"})]
      (let [f (spi/compile-batch-source
               {:type "api" :base_url "https://fixture.invalid" :method "GET" :endpoint "/items/{ids}"
                :data_path "$.items" :field_mapping {:item/amount "$.amount"}
                :batch {:parent-key :item/id :child-fk "id" :placeholder "ids"}}
               nil {:id "batch" :input [:item/id] :result_type result-type
                    :semantic/output-context {:item/parent {:input :item/id} :item/source {:value "fixture"}}
                    :semantic/output-transforms {:item/amount :number-to-decimal}})
            r (f [{:item/id 1} {:item/id 2}] {})
            row (if (= "single" result-type) (first r) (ffirst r))]
        (is (= {:item/amount 7.5M :item/parent 1 :item/source "fixture"} row))
        (is (instance? java.math.BigDecimal (:item/amount row)))
        (is (= (if (= "single" result-type) nil []) (second r)))))))

;; ---------------------------------------------------------------------------
;; Owner rulings 2026-09-18: per-clause findings (B), slot names + required
;; :field_mapping (#3), write bindings (#4).
;; ---------------------------------------------------------------------------

(defn- codes-and-paths [b]
  (set (map (juxt :code :path) (:findings (api/validate-binding b)))))

(deftest each-clause-reports-its-own-code-and-path
  (is (contains? (codes-and-paths (assoc binding :binding/effect :upsert))
                 [:connector.binding/effect [:binding/effect]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :headers] {"Authorization" "secret"}))
                 [:connector.binding/unknown-key [:binding/implementation :headers]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :method] "DELETE"))
                 [:connector.binding/method [:binding/implementation :method]]))
  (is (contains? (codes-and-paths (update binding :binding/implementation dissoc :field_mapping))
                 [:connector.binding/missing-key [:binding/implementation :field_mapping]])
      "ruling #3: output-map values are slot names, so :field_mapping is required")
  (is (contains? (codes-and-paths (assoc-in binding [:binding/output-map :child/id] "wrong"))
                 [:connector.binding/slot-mismatch [:binding/output-map :child/id]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/input-map :item/id] "id"))
                 [:connector.binding/slot-mismatch [:binding/input-map :item/id]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/output-map :item/extra] "item/extra"))
                 [:connector.binding/output-map-unmapped [:binding/output-map :item/extra]]))
  (is (contains? (codes-and-paths (update binding :binding/output-map dissoc :child/id))
                 [:connector.binding/output-map-missing [:binding/output-map :child/id]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :json_body] {:id "{item/id}"}))
                 [:connector.binding/json-body-method [:binding/implementation :json_body]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :transforms] {:item/other :iso-to-instant}))
                 [:connector.binding/transform-target [:binding/implementation :transforms :item/other]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :transforms] {:item/id :upper-case}))
                 [:connector.binding/transform-kind [:binding/implementation :transforms :item/id]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :context] {:item/id {:value 2}}))
                 [:connector.binding/context-target [:binding/implementation :context :item/id]]))
  (is (contains? (codes-and-paths (-> binding
                                      (assoc-in [:binding/implementation :context] {:item/source {:input :unknown/id}})
                                      (assoc-in [:binding/output-map :item/source] "item/source")))
                 [:connector.binding/context-spec [:binding/implementation :context :item/source]]))
  (testing "all clause findings are collected"
    (is (= 3 (count (:findings (api/validate-binding
                                (-> binding
                                    (assoc-in [:binding/implementation :headers] {})
                                    (assoc-in [:binding/implementation :method] "DELETE")
                                    (assoc-in [:binding/output-map :child/id] "wrong")))))))))

(deftest findings-carry-the-full-shape-and-echo-no-configuration
  (let [b (-> binding
              (assoc-in [:binding/implementation :endpoint] "/items/{item/id}/secret-segment/{unbound/id}")
              (assoc-in [:binding/implementation :query_params] {"token" "private-secret"}))
        result (api/validate-binding b)]
    (is (seq (:findings result)))
    (is (every? #(and (:code %) (:severity %) (:path %) (:message %)) (:findings result)))
    (is (not-any? #(contains? % :got) (:findings result)))
    (is (not (.contains (pr-str result) "private-secret")))
    (is (not (.contains (pr-str result) "secret-segment")))))

(def write-binding
  {:binding/effect :write
   :binding/implementation {:method "POST" :endpoint "/items"
                            :json_body {:title "{item/title}"}
                            :field_mapping {:item/id "$.id"}}
   :binding/input-map {:item/title "item/title"}
   :binding/output-map {:item/id "item/id"}})

(deftest a-post-write-binding-validates-lowers-and-executes
  (is (empty? (:findings (api/validate-binding write-binding))))
  (is (= {:findings []} ((:validate (spi/semantic-adapter "api")) write-binding)))
  (let [operation (api/binding-operation write-binding)
        calls (atom [])]
    (is (= {:type "api" :method "POST" :endpoint "/items" :json_body {:title "{item/title}"}
            :field_mapping {:item/id "$.id"}}
           operation))
    (with-redefs [http/post (fn [url opts]
                             (swap! calls conj [url (json/parse-string (:body opts))])
                             {:status 200 :body "{\"id\":42}"})]
      (let [write! (spi/compile-mutation (assoc operation :base_url "https://fixture.invalid") nil
                                         {:id "create-item" :result_type "single"})]
        (is (= {:item/id 42} (write! {:item/title "hello"})))))
    (is (= [["https://fixture.invalid/items" {"title" "hello"}]] @calls)))
  (testing "an output-map that is empty is a :none operation; otherwise it names what :field_mapping produces"
    (is (empty? (:findings (api/validate-binding (assoc write-binding :binding/output-map {})))))
    (is (contains? (codes-and-paths (assoc-in write-binding [:binding/output-map :item/name] "item/name"))
                   [:connector.binding/output-map-unmapped [:binding/output-map :item/name]])))
  (testing "PUT, PATCH and DELETE are write methods; :json_body goes with any of them"
    (doseq [m ["PUT" "PATCH" "DELETE"]]
      (is (empty? (:findings (api/validate-binding (assoc-in write-binding [:binding/implementation :method] m)))) m))))

(deftest write-bindings-refuse-read-methods-and-read-machinery
  (is (contains? (codes-and-paths (assoc-in write-binding [:binding/implementation :method] "GET"))
                 [:connector.binding/method [:binding/implementation :method]]))
  (is (contains? (codes-and-paths (assoc-in write-binding [:binding/implementation :batch]
                                            {:parent-key :item/title :child-fk "title" :placeholder "titles"}))
                 [:connector.binding/batch-effect [:binding/implementation :batch]]))
  (doseq [k [:result-root :transforms :context]]
    (is (contains? (codes-and-paths (assoc-in write-binding [:binding/implementation k] {}))
                   [:connector.binding/unknown-key [:binding/implementation k]]) k))
  (is (contains? (codes-and-paths (update write-binding :binding/implementation dissoc :field_mapping))
                 [:connector.binding/missing-key [:binding/implementation :field_mapping]]))
  (is (contains? (codes-and-paths (assoc-in write-binding [:binding/implementation :json_body] "raw"))
                 [:connector.binding/shape [:binding/implementation :json_body]])))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §6.3 (doc 109 §109.8 item 3, owner 2026-09-18): api
;; carries a union when its :field_mapping entry is a branch scope; a leaf path
;; is refused; nifi refuses both output forms. Plus §3.1's api parameter-slot
;; rule (task book 补充任务 B).
;; ---------------------------------------------------------------------------

(def ^:private union-binding
  {:binding/effect :read
   :binding/implementation {:method "GET" :endpoint "/tickets/{ticket/id}"
                            :field_mapping {:ticket/id "$.id"
                                            :ticket/subject {:device/id "$.subject.deviceId" :device/name "$.subject.deviceName"
                                                             :person/id "$.subject.personId" :person/email "$.subject.email"}}}
   :binding/input-map {:ticket/id "ticket/id"}
   :binding/output-map {:ticket/id "ticket/id" :ticket/subject "ticket/subject"
                        :device/id "device/id" :device/name "device/name"
                        :person/id "person/id" :person/email "person/email"}
   :binding/output-union #{:ticket/subject}})

(deftest a-union-output-needs-a-branch-scope-and-answers-nested-records
  (is (empty? (:findings (api/validate-binding union-binding))) (pr-str (:findings (api/validate-binding union-binding))))
  (testing "a leaf path cannot carry a branch identity"
    (let [r (api/validate-binding (assoc-in union-binding [:binding/implementation :field_mapping :ticket/subject] "$.subject"))]
      (is (some #(= [:connector.binding/union-identity-mapping [:binding/output-map :ticket/subject]] ((juxt :code :path) %))
                (:findings r))
          (pr-str (:findings r)))))
  (testing "the stubbed source answers each record's union value as a nested record the engine can discriminate"
    (let [source (assoc (api/binding-operation union-binding) :base_url "https://fixture.invalid")
          read! (spi/compile-source source nil {:id "ticket" :input [:ticket/id] :result_type "single"
                                                :output [:ticket/id {:ticket/subject {:device/id [:device/id :device/name]
                                                                                      :person/id [:person/id :person/email]}}]})
          bodies {1 "{\"id\":1,\"subject\":{\"deviceId\":10,\"deviceName\":\"router\"}}"
                  2 "{\"id\":2,\"subject\":{\"personId\":20,\"email\":\"a@b\"}}"
                  3 "{\"id\":3,\"subject\":{\"deviceId\":11,\"deviceName\":\"sw\",\"personId\":21,\"email\":\"x@y\"}}"}]
      (with-redefs [http/get (fn [url _] {:status 200 :body (get bodies (Long/parseLong (re-find #"\d+$" url)))})]
        ;; The branch scope maps every branch, so the other branch's
        ;; attributes arrive present but nil; the engine discriminates on the
        ;; identity that has a value (2.0 §6.4) and drops the nil ones.
        (is (= {:ticket/id 1 :ticket/subject {:device/id 10 :device/name "router" :person/id nil :person/email nil}}
               (read! {:ticket/id 1} {})))
        (is (= {:ticket/id 2 :ticket/subject {:device/id nil :device/name nil :person/id 20 :person/email "a@b"}}
               (read! {:ticket/id 2} {})))
        (is (= {:device/id 11 :device/name "sw" :person/id 21 :person/email "x@y"}
               (:ticket/subject (read! {:ticket/id 3} {})))
            "the connector hands the ambiguous record through; discrimination is the engine's (2.0 §6.4)")))))

(deftest a-recursive-output-needs-nothing-of-api
  (let [b (-> binding (assoc :binding/output-recursion #{:item/children}))]
    (is (empty? (:findings (api/validate-binding b))))))

(deftest an-api-parameter-map-value-must-equal-the-parameter-name
  (let [b (assoc binding :binding/parameter-map {:keyword "keyword" :since "since"})]
    (is (empty? (:findings (api/validate-binding b))))
    (let [r (api/validate-binding (assoc b :binding/parameter-map {:keyword "q" :since "since"}))]
      (is (= [[:connector.binding/parameter-slot [:binding/parameter-map :keyword]]]
             (mapv (juxt :code :path) (:findings r)))
          (pr-str (:findings r))))))

(deftest nifi-refuses-both-output-forms-and-keeps-its-own-parameter-names
  (let [nifi {:binding/effect :read
              :binding/implementation {:operation :event
                                       :field_mapping {:nifi.event/id "$.provenanceEvent.eventId"
                                                       :nifi.event/type "$.provenanceEvent.eventType"}}
              :binding/input-map {:nifi.event/id "event-id"}
              :binding/output-map {:nifi.event/id "nifi.event/id" :nifi.event/type "nifi.event/type"}}
        codes-paths (fn [b] (set (map (juxt :code :path) (:findings (api/validate-nifi-binding b)))))]
    (is (empty? (:findings (api/validate-nifi-binding nifi))) (pr-str (:findings (api/validate-nifi-binding nifi))))
    (is (contains? (codes-paths (assoc nifi :binding/output-union #{:nifi.event/type}))
                   [:connector.binding/union-output-unsupported [:binding/output-map :nifi.event/type]]))
    (is (contains? (codes-paths (assoc nifi :binding/output-recursion #{:nifi.event/type}))
                   [:connector.binding/recursion-output-unsupported [:binding/output-map :nifi.event/type]]))
    (testing "a nifi parameter-map spells nifi parameter names, not api slots (2.0 §3.1 exempts nifi)"
      (is (empty? (:findings (api/validate-nifi-binding (assoc nifi :binding/parameter-map {:node "node-id"}))))))))
