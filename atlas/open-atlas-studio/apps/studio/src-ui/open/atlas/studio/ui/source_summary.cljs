(ns open.atlas.studio.ui.source-summary
  (:require [clojure.string :as str]))

(defn label [{:keys [id label]}]
  (if (str/blank? label) id label))

(defn draft-used? [source]
  (pos? (or (get-in source [:draft-usage :graphs]) 0)))

(defn usage-text [{:keys [used-by draft-usage]}]
  (let [resolvers (+ (count used-by) (or (:resolvers draft-usage) 0))
        operations (or (:operations draft-usage) 0)
        graphs (or (:graphs draft-usage) 0)
        amount (fn [n noun] (str n " " noun (when (not= 1 n) "s")))
        parts (cond-> []
                (pos? resolvers) (conj (amount resolvers "resolver"))
                (pos? operations) (conj (amount operations "operation"))
                (pos? graphs) (conj (amount graphs "Graph draft")))]
    (if (seq parts) (str/join " · " parts) "No model bindings")))
