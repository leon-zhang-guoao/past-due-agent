(ns open.atlas.common.engine.compile-cache-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as connector]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.engine.fake-connector :as fake]))

(defn- one-resolver-model [source-type]
  (let [{:keys [model findings]}
        (dialect/read-dialect (slurp (io/resource "graph.edn")))
        resolver (first (remove #(get-in % [:source :batch]) (:resolvers model)))]
    (assert (empty? findings))
    (-> (fake/inline-model model source-type)
        (assoc :resolvers [(assoc-in resolver [:source :type] source-type)])
        (assoc :mutations []))))

(defn- install-compile-method! [source-type calls]
  (.addMethod ^clojure.lang.MultiFn connector/compile-source
              source-type
              (fn [_source _connection _resolver]
                (swap! calls inc)
                (fn [_input _params] nil))))

(deftest connector-registry-change-invalidates-a-compiled-model
  (let [source-type "test-dynamic-cache"
        model       (one-resolver-model source-type)
        calls       (atom 0)]
    (remove-method connector/compile-source source-type)
    (try
      (testing "an unavailable connector is represented in the first bundle"
        (is (some #(= :engine.compile/source-not-executable (:code %))
                  (:engine/findings (engine/compile-env model)))))
      (testing "registering it makes the same model compile without manual cache clearing"
        (install-compile-method! source-type calls)
        (is (empty? (:engine/findings (engine/compile-env model))))
        (is (= 1 @calls)))
      (finally
        (remove-method connector/compile-source source-type)))))

(deftest concurrent-cache-misses-share-one-compilation
  (let [source-type "test-single-flight"
        model       (one-resolver-model source-type)
        calls       (atom 0)]
    (remove-method connector/compile-source source-type)
    (try
      (install-compile-method! source-type calls)
      (let [start   (promise)
            tasks   (doall (repeatedly 12
                              #(future @start (engine/compile-env model))))]
        (deliver start true)
        (let [results (mapv deref tasks)
              envs    (mapv #(get-in % [:engine/bundle :env]) results)]
          (is (= 1 @calls) "the connector compiler runs once for one model/version")
          (is (every? #(identical? (first envs) %) (rest envs))
              "all callers receive the same compiled environment")))
      (finally
        (remove-method connector/compile-source source-type)))))
