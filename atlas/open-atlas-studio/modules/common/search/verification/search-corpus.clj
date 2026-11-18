;; From modules/common/search: clojure -M verification/search-corpus.clj /path/to/graph.edn
;; Uses a local vocabulary projection, not a deployed compiled Graph or Agent.
(require '[clojure.edn :as edn] '[clojure.pprint :as pp]
         '[open.atlas.common.search :as search])
(let [path (first *command-line-args*)
      _ (when-not path (throw (ex-info "Supply Graph EDN path" {})))
      content (slurp path)
      graph (edn/read-string content)
      bare (fn [entry] (into {} (map (fn [[k v]] [(keyword (name k)) v]) entry)))
      model {:attributes (update-vals (get-in graph [:vocabulary :attributes]) bare)
             :namespaces (update-vals (get-in graph [:vocabulary :entities]) bare)
             :terms (update-vals (get-in graph [:vocabulary :terms]) bare)}
      idx (search/build-index model 1)]
  (try
    (pp/pprint
      {:sha256 (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes content "UTF-8"))))
       :documents (count (:search/docs idx))
       :queries (mapv (fn [q]
                        (let [result (search/search idx q 1 {:max-hits 5000})]
                          {:query q :count (count (:hits result))
                           :ids (mapv :id (:hits result)) :findings (:findings result)}))
                      ["coverage rate" "coverge rate" "coverage raet" "covrage rate"
                       "coverage rtae" "coverage" "rate" "覆盖率"])})
    (finally (search/close-index! idx))))
