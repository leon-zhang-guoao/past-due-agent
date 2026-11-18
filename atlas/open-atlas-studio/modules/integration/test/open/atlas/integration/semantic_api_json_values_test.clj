(ns open.atlas.integration.semantic-api-json-values-test
  "A :json attribute read from an api source holds what the upstream sent. The
   connector parses bodies with keyword keys, and the Contract 2.0 result check
   accepts a :json object only with string keys, so every raw payload or
   document list was refused (SE Product Catalog, 2026-09-30). The api
   connector now hands :json values back with their original key text on the
   2.0 path, by the declared output types (D2.20). Legacy dialect resolvers
   never carry the types and keep their keyword keys."
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
  {:entities {"item" {:entity/label "Item" :entity/identity [:item/id]
                      :entity/attributes [:item/id :item/name :item/raw :item/docs :item/tags :item/all]}
              "part" {:entity/label "Part" :entity/identity [:part/id]
                      :entity/attributes [:part/id :part/raw]}}
   :attributes {:item/id {:attribute/label "ID" :attribute/type :string :attribute/nullable? false}
                :item/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? true}
                :item/raw {:attribute/label "Raw" :attribute/type :json :attribute/nullable? true}
                :item/docs {:attribute/label "Docs" :attribute/type :json :attribute/nullable? true}
                :item/tags {:attribute/label "Tags" :attribute/type :json :attribute/nullable? true}
                :part/id {:attribute/label "Part ID" :attribute/type :string :attribute/nullable? false}
                :part/raw {:attribute/label "Part raw" :attribute/type :json :attribute/nullable? true}
                :item/all {:attribute/label "All" :attribute/type :ref :attribute/nullable? true
                           :attribute/target "item" :attribute/cardinality :many}}})

(def ^:private field-mapping
  {:item/name "$.name" :item/raw "$.raw" :item/docs "$.docs" :item/tags "$.tags"})

(def ^:private output-map
  {:item/name "item/name" :item/raw "item/raw" :item/docs "item/docs" :item/tags "item/tags"})

(def ^:private definitions
  {:resolvers {"one" {:label "One" :effect :read :result/cardinality :one :input [:item/id]
                      :output [:item/name :item/raw :item/docs :item/tags]}
               "parts" {:label "Parts" :effect :read :result/cardinality :one :input [:part/id]
                        :output [:part/raw] :batch? true}
               "all" {:label "All" :effect :read :result/cardinality :many :input []
                      :collection {:ordering :source-defined :paging :none :completeness :not-guaranteed
                                   :bounds {:max-items 100 :max-response-bytes 1048576 :timeout-ms 5000}}
                      :output [{:item/all [:item/id :item/name :item/raw :item/docs :item/tags]}]}}
   :operations {}
   :sources {"api" {:source/kind "api" :source/effects #{:read}}}
   :bindings {"one-api" {:binding/for [:resolver "one"] :binding/source "api" :binding/effect :read
                         :binding/input-map {:item/id "item/id"}
                         :binding/output-map output-map
                         :binding/implementation {:method "GET" :endpoint "/items/{item/id}"
                                                  :data_path "$.item" :field_mapping field-mapping}}
              "parts-api" {:binding/for [:resolver "parts"] :binding/source "api" :binding/effect :read
                           :binding/input-map {:part/id "part/id"}
                           :binding/output-map {:part/raw "part/raw"}
                           :binding/implementation {:method "GET" :endpoint "/batch?ids={ids}"
                                                    :data_path "$.items"
                                                    :batch {:parent-key :part/id :child-fk "id"
                                                            :placeholder "ids" :chunk-size 25}
                                                    :field_mapping {:part/raw "$.raw"}}}
              "all-api" {:binding/for [:resolver "all"] :binding/source "api" :binding/effect :read
                         :binding/input-map {}
                         :binding/output-map (assoc output-map :item/all "item/all" :item/id "item/id")
                         :binding/implementation {:method "GET" :endpoint "/items" :data_path "$.items"
                                                  :result-root :item/all
                                                  :field_mapping (assoc field-mapping :item/id "$.id")}}}
   :selection {[:resolver "one"] "one-api" [:resolver "parts"] "parts-api" [:resolver "all"] "all-api"}})

(defn- item [id]
  ;; An object with a namespaced-looking key and a numeric key, an array of
  ;; objects, and an array of scalars -- the three shapes a raw payload mixes.
  {:id id :name (str "Item " id)
   :raw {:identifiers {:manufacturerPID id "ns/key" "slash" "123" "digits"} :score 1.5}
   :docs [{:type "datasheet" :sources ["https://example.invalid/a.pdf"]}]
   :tags ["a" "b"]})

(defn- route [url]
  (let [body (cond
               (str/includes? url "/batch?ids=")
               {:items (mapv item (str/split (java.net.URLDecoder/decode (last (str/split url #"ids=")) "UTF-8") #","))}
               (re-find #"/items/[^/?]+$" url) {:item (item (last (str/split url #"/")))}
               :else {:items [(item "A") (item "B")]})]
    (json/generate-string body)))

(defn- query [candidate entity eql]
  (with-redefs [http/get (fn [url opts]
                           (let [b (route url)]
                             {:status 200
                              :body (if (= :stream (:as opts)) (java.io.ByteArrayInputStream. (.getBytes b "UTF-8")) b)}))]
    (engine/query (:compiled candidate) {:graph "items" :version 1} {:entity entity :query eql})))

(def ^:private expected-raw
  {"identifiers" {"manufacturerPID" "A" "ns/key" "slash" "123" "digits"} "score" 1.5})

(deftest json-values-keep-their-key-text-and-pass-the-result-check
  (let [candidate (compiler/compile-candidate vocabulary definitions {"api" (spi/semantic-adapter "api")}
                                              {"api" {:base_url "https://fixture.invalid"}}
                                              {:contract version/semantic-v2})]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (when (:compiled candidate)
      (testing "a by-key read"
        (let [answer (query candidate {:item/id "A"} [:item/name :item/raw :item/docs :item/tags])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= expected-raw (get-in answer [:engine/data :item/raw])) "\"ns/key\" is not cut to \"key\"")
          (is (= [{"type" "datasheet" "sources" ["https://example.invalid/a.pdf"]}]
                 (get-in answer [:engine/data :item/docs])))
          (is (= ["a" "b"] (get-in answer [:engine/data :item/tags])) "arrays of scalars are unchanged")
          (is (empty? (:findings (result/validate-query-result vocabulary answer))))))
      (testing "records nested under a collection join root"
        (let [answer (query candidate {} [{:item/all [:item/id :item/raw :item/docs]}])
              rows (get-in answer [:engine/data :item/all])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= ["A" "B"] (mapv :item/id rows)) "record keys stay attribute keywords")
          (is (= expected-raw (:item/raw (first rows))))
          (is (empty? (:findings (result/validate-query-result vocabulary answer))))))
      (testing "a batched read"
        (let [answer (query candidate {} [{[:part/id "A"] [:part/raw]} {[:part/id "B"] [:part/raw]}])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= expected-raw (get-in answer [:engine/data [:part/id "A"] :part/raw])))
          (is (empty? (:findings (result/validate-query-result vocabulary answer)))))))))

(deftest a-legacy-api-resolver-keeps-its-keyword-keys
  ;; No `:semantic/output-types` on the resolver config: the dialect path.
  (let [read! (spi/compile-source {:type "api" :method "GET" :base_url "https://fixture.invalid"
                                   :endpoint "/items/{item/id}" :data_path "$.item"
                                   :field_mapping field-mapping}
                                  nil
                                  {:id "legacy" :result_type "single" :input [:item/id]
                                   :output [:item/name :item/raw]})]
    (with-redefs [http/get (fn [url _] {:status 200 :body (route url)})]
      (is (= {:identifiers {:manufacturerPID "A" :ns/key "slash" :123 "digits"} :score 1.5}
             (:item/raw (read! {:item/id "A"} {})))))))
