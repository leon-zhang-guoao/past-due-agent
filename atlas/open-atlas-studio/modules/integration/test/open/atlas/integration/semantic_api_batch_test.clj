(ns open.atlas.integration.semantic-api-batch-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.engine :as engine]))

(deftest semantic-batch-compiles-and-queries-in-chunks-of-25
  (let [v {:entities {"item" {:entity/label "Item" :entity/identity [:item/id]
                              :entity/attributes [:item/id :item/name :item/all]}}
           :attributes {:item/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                        :item/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                        :item/all {:attribute/label "Items" :attribute/type :ref :attribute/target "item"
                                   :attribute/cardinality :many :attribute/nullable? false}}}
        batch {:parent-key :item/id :child-fk "id" :placeholder "ids" :chunk-size 25}
        ;; Contract 2.0 §4.2: the definition says `:batch? true` and the
        ;; selected binding carries the physical block (single version since
        ;; 2026-09-19, task book PR1).
        defs {:resolvers {"detail" {:label "Detail" :effect :read :result/cardinality :one
                                    :input [:item/id] :output [:item/name] :batch? true}}
              :operations {}
              :sources {"api" {:source/kind "api" :source/effects #{:read}}}
              :bindings {"detail-api" {:binding/for [:resolver "detail"] :binding/effect :read
                                       :binding/source "api" :binding/input-map {:item/id "item/id"}
                                       :binding/output-map {:item/name "item/name"}
                                       :binding/implementation {:method "GET" :endpoint "/items/{ids}"
                                                                :data_path "$.items" :batch batch
                                                                :field_mapping {:item/name "$.name"}}}}
              :selection {[:resolver "detail"] "detail-api"}}
        candidate (compiler/compile-candidate v defs {"api" (spi/semantic-adapter "api")}
                                               {"api" {:base_url "https://fixture.invalid"}})
        calls (atom [])]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (is (some? (:compiled candidate)))
    (when (:compiled candidate)
      (with-redefs [http/get (fn [url _]
                              (let [ids (mapv parse-long
                                              (str/split (java.net.URLDecoder/decode
                                                          (last (str/split url #"/")) "UTF-8") #","))]
                                (swap! calls conj ids)
                                {:status 200 :body (json/generate-string
                                                   {:items (mapv (fn [id] {:id id :name (str "Item " id)})
                                                                 (reverse ids))})}))]
        (let [answer (engine/query (:compiled candidate) {:graph "test" :version 1}
                                   {:entity {:item/all (mapv #(hash-map :item/id %) (range 1 27))}
                                    :query [{:item/all [:item/id :item/name]}]})]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= (mapv #(hash-map :item/id % :item/name (str "Item " %)) (range 1 27))
                 (get-in answer [:engine/data :item/all])))
          (is (= [25 1] (mapv count @calls)))
          (is (= (set (range 1 27)) (set (mapcat identity @calls)))))))))
