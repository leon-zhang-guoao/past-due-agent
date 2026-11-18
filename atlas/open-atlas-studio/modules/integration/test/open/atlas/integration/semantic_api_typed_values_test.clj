(ns open.atlas.integration.semantic-api-typed-values-test
  "JSON has no decimal or date type, so an api source sends prices as numbers
   and dates as ISO text, and a request input carried back through `:context`
   is text too. The Contract 2.0 result check accepts only BigDecimal /
   LocalDate / Instant for :decimal / :date / :datetime, so every such value was
   refused unless the binding declared an explicit `:transforms` (SE catalog
   changes, 2026-09-30). The api connector now converts by the declared type
   with the same rules as sql (`spi/declared-value`, D2.20). Text that needs a
   guessed zone is still refused; legacy dialect resolvers are unchanged."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-result :as result]))

(def ^:private vocabulary
  {:entities {"order" {:entity/label "Order" :entity/identity [:order/id]
                       :entity/attributes [:order/id :order/amount :order/due :order/placed-at :order/local-at]}
              "window" {:entity/label "Window" :entity/identity [:window/since]
                        :entity/attributes [:window/since :window/changes]}
              "change" {:entity/label "Change" :entity/identity [:change/since :change/ref]
                        :entity/attributes [:change/since :change/ref]}}
   :attributes {:order/id {:attribute/label "ID" :attribute/type :string :attribute/nullable? false}
                :order/amount {:attribute/label "Amount" :attribute/type :decimal :attribute/nullable? true}
                :order/due {:attribute/label "Due" :attribute/type :date :attribute/nullable? true}
                :order/placed-at {:attribute/label "Placed" :attribute/type :datetime :attribute/nullable? true}
                :order/local-at {:attribute/label "Local" :attribute/type :datetime :attribute/nullable? true}
                :window/since {:attribute/label "Since" :attribute/type :date :attribute/nullable? false}
                :window/changes {:attribute/label "Changes" :attribute/type :ref :attribute/nullable? true
                                 :attribute/target "change" :attribute/cardinality :many}
                :change/since {:attribute/label "Since" :attribute/type :date :attribute/nullable? false}
                :change/ref {:attribute/label "Ref" :attribute/type :string :attribute/nullable? false}}})

(def ^:private definitions
  {:resolvers {"order" {:label "Order" :effect :read :result/cardinality :one :input [:order/id]
                        :output [:order/amount :order/due :order/placed-at :order/local-at]}
               "changes" {:label "Changes" :effect :read :result/cardinality :many :input [:window/since]
                          :collection {:ordering :source-defined :paging :none :completeness :not-guaranteed
                                       :bounds {:max-items 100 :max-response-bytes 1048576 :timeout-ms 5000}}
                          :output [{:window/changes [:change/since :change/ref]}]}}
   :operations {}
   :sources {"api" {:source/kind "api" :source/effects #{:read}}}
   :bindings {"order-api" {:binding/for [:resolver "order"] :binding/source "api" :binding/effect :read
                           :binding/input-map {:order/id "order/id"}
                           :binding/output-map {:order/amount "order/amount" :order/due "order/due"
                                                :order/placed-at "order/placed-at" :order/local-at "order/local-at"}
                           :binding/implementation {:method "GET" :endpoint "/orders/{order/id}" :data_path "$.order"
                                                    :field_mapping {:order/amount "$.amount" :order/due "$.due"
                                                                    :order/placed-at "$.placedAt" :order/local-at "$.localAt"}}}
              "changes-api" {:binding/for [:resolver "changes"] :binding/source "api" :binding/effect :read
                             :binding/input-map {:window/since "window/since"}
                             :binding/output-map {:window/changes "window/changes" :change/since "change/since"
                                                  :change/ref "change/ref"}
                             :binding/implementation {:method "GET" :endpoint "/changes?from={window/since}"
                                                      :data_path "$.changes" :result-root :window/changes
                                                      :field_mapping {:change/ref "$.ref"}
                                                      :context {:change/since {:input :window/since}}}}}
   :selection {[:resolver "order"] "order-api" [:resolver "changes"] "changes-api"}})

(def ^:private calls (atom []))

(defn- get* [url opts]
  (swap! calls conj (java.net.URLDecoder/decode url "UTF-8"))
  (let [b (json/generate-string
           (if (str/includes? url "/changes")
             {:changes [{:ref "A"} {:ref "B"}]}
             {:order {:amount 12.5 :due "2026-10-01" :placedAt "2026-09-29 08:00:00+00" :localAt "2026-09-29T08:00:00"}}))]
    {:status 200 :body (if (= :stream (:as opts)) (java.io.ByteArrayInputStream. (.getBytes b "UTF-8")) b)}))

(deftest api-values-arrive-in-their-declared-classes
  (let [candidate (compiler/compile-candidate vocabulary definitions {"api" (spi/semantic-adapter "api")}
                                              {"api" {:base_url "https://fixture.invalid"}}
                                              {:contract version/semantic-v2})
        q (fn [entity eql]
            (with-redefs [http/get get*]
              (engine/query (:compiled candidate) {:graph "orders" :version 1} {:entity entity :query eql})))]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (when (:compiled candidate)
      (testing "a JSON number, date text and offset text"
        (let [answer (q {:order/id "7"} [:order/amount :order/due :order/placed-at])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= 12.5M (get-in answer [:engine/data :order/amount])))
          (is (= (java.time.LocalDate/parse "2026-10-01") (get-in answer [:engine/data :order/due])))
          (is (= (java.time.Instant/parse "2026-09-29T08:00:00Z") (get-in answer [:engine/data :order/placed-at])))
          (is (empty? (:findings (result/validate-query-result vocabulary answer))))))
      (testing "a zoneless date-time is not given a zone: the result check still refuses it"
        (let [answer (q {:order/id "7"} [:order/local-at])]
          (is (= "2026-09-29T08:00:00" (get-in answer [:engine/data :order/local-at])))
          (is (= [[:order/local-at]] (map :path (:findings (result/validate-query-result vocabulary answer)))))))
      (testing "a date input sent as text reaches the URL as text and comes back through :context as a date"
        (reset! calls [])
        (let [answer (q {:window/since "2026-09-16"} [{:window/changes [:change/since :change/ref]}])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= [(java.time.LocalDate/parse "2026-09-16")] (distinct (map :change/since (get-in answer [:engine/data :window/changes])))))
          (is (str/includes? (first @calls) "from=2026-09-16"))
          (is (empty? (:findings (result/validate-query-result vocabulary answer)))))))))

(deftest a-legacy-api-resolver-keeps-its-json-values
  (let [read! (spi/compile-source {:type "api" :method "GET" :base_url "https://fixture.invalid"
                                   :endpoint "/orders/{order/id}" :data_path "$.order"
                                   :field_mapping {:order/amount "$.amount" :order/due "$.due"}}
                                  nil
                                  {:id "legacy" :result_type "single" :input [:order/id]
                                   :output [:order/amount :order/due]})]
    (with-redefs [http/get get*]
      (is (= {:order/amount 12.5 :order/due "2026-10-01"} (read! {:order/id "7"} {}))))))
