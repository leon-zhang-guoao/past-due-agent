(ns open.atlas.common.semantic-result
  "Pure result validation for development semantic candidates. No execution,
   authorization, mutation handling or partial-result synthesis."
  (:require [open.atlas.common.semantic-record :as record]))

(defn- ident-root? [k]
  (and (vector? k) (= 2 (count k)) (qualified-keyword? (first k))))

(defn validate-query-result
  "Validate attribute projections and EQL ident roots without changing their
   shape. Ident anchors are typed input values, not evidence of a found row.
   Findings use ordinal paths, never caller-supplied identity values."
  [vocabulary response]
  (if (not= :ok (:engine/status response))
    {:response response :findings []}
    (letfn [(check [data path depth]
              (if (or (> depth 64) (not (map? data))
                      (not-every? #(or (qualified-keyword? %) (ident-root? %)) (keys data)))
                [{:code :result/shape :severity :error :path path}]
                (concat
                  (mapcat
                    (fn [[eid entries]]
                      (map #(update % :path (partial into path))
                           (:findings (record/validate-projection vocabulary eid (into {} entries)))))
                    (sort-by key (group-by (comp namespace key)
                                          (filter (comp qualified-keyword? key) data))))
                  (mapcat
                    (fn [[i [[attr id] projection]]]
                      (let [p (conj path :idents i)]
                        (concat
                          (map #(update % :path (partial into p))
                               (:findings (record/validate-projection vocabulary (namespace attr) {attr id})))
                          (when (and (map? projection) (contains? projection attr)
                                     (not= id (get projection attr)))
                            [{:code :result/identity-mismatch :severity :error :path (conj p attr)}])
                          (check projection p (inc depth)))))
                    (map-indexed vector (filter (comp ident-root? key) data))))))]
      (let [findings (vec (check (:engine/data response) [] 0))]
        (if (seq findings) {:findings findings}
            {:response response :findings []})))))


(defn validate-execution-result
  "Mutation roots are symbols. Validate their values as record projections.
   A result error after execution cannot promise that nothing was committed."
  [vocabulary response]
  (let [data (:engine/data response)]
    (if (and (= :ok (:engine/status response)) (map? data)
             (seq data) (every? symbol? (keys data)))
      (let [findings (vec (mapcat (fn [[_ value]]
                                   (when-not (= {:mutation/ok true} value)
                                     (:findings (validate-query-result vocabulary
                                                  {:engine/status :ok :engine/data value})))) data))]
        (if (seq findings)
          {:response {:engine/status :error :operation/status :unknown
                      :engine/issues findings} :findings []}
          {:response response :findings []}))
      (validate-query-result vocabulary response))))
