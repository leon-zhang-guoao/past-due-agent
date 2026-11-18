(ns open.atlas.server.console.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.server.console.catalog :as catalog]
            [open.atlas.server.console.groups :as groups]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.token :as token]))

(defn- id [n] (format "prod/graph-%03d" n))
(defn- scale-fixture [f]
  (let [versions (for [g (range 100) v (range 1 121)] {:graph (id g) :version v})
        tokens (vec (for [g (range 100) t (range 60)]
                      {:token-id (format "token-%03d-%03d" g t) :graph-id (id g) :status (if (< t 50) :active :revoked)
                       :created-at "2026-09-26T00:00:00Z" :version :latest}))
        metadata-reads (atom [])]
    (with-redefs [graph/shelf-versions (constantly versions)
                  graph/lifecycle-records (constantly {})
                  graph/serving-version (constantly 119)
                  instance/graph-enabled? (constantly true)
                  token/all-records (constantly tokens)
                  graph/shelf-texts (fn [& _] (throw (ex-info "Listing must not read artifact content" {})))
                  graph/shelf-metadata (fn [_ graph version] (swap! metadata-reads conj [graph version]) {:manifest {:provenance {:cut-by "test"}}})
                  groups/registry (constantly {:groups (into {} (for [g (range 10)] [(str g) {:name (str "Group " g)}]))
                                               :members (into {} (for [g (range 100)] [(id g) (str (mod g 10))]))})]
      (f metadata-reads))))

(deftest large-catalog-is-bounded-and-does-not-read-contents
  (scale-fixture
   (fn [reads]
     (let [r (catalog/graphs-page {:runtime {}} {})]
       (is (= 100 (:total r))) (is (= 20 (count (:items r))))
       (is (= 5 (:pages r))) (is (= 6000 (get-in r [:counts :tokens])))
       (is (= 5000 (get-in r [:counts :active-tokens])))
       (is (= 10 (count (:groups r))))
       (is (every? #(= 10 (:graph-count %)) (:groups r)))
       (is (every? #(and (not (contains? % :tokens)) (not (contains? % :versions))) (:items r)))
       (is (< (count (pr-str r)) 15000))
       (is (empty? @reads)))
     (let [a (catalog/graphs-page {:runtime {}} {"page" "1"})
           b (catalog/graphs-page {:runtime {}} {"page" "2"})]
       (is (= (id 20) (:graph-id (first (:items b)))))
       (is (= 40 (count (set (map :graph-id (concat (:items a) (:items b)))))))))))

(deftest filters-groups-clamping-and-lazy-histories
  (scale-fixture
   (fn [reads]
     (is (= 10 (:total (catalog/graphs-page {:runtime {}} {"group" "3"}))))
     (is (= 0 (:total (catalog/graphs-page {:runtime {}} {"group" "ungrouped"}))))
     (is (= [(id 73)] (map :graph-id (:items (catalog/graphs-page {:runtime {}} {"q" "GRAPH-073"})))))
     (let [r (catalog/versions-page {:runtime {}} {"graph-id" (id 0) "page" "2"})]
       (is (= 120 (:total r))) (is (= 100 (:version (first (:items r)))))
       (is (= 20 (count @reads))))
     (let [r (catalog/versions-page {:runtime {}} {"graph-id" (id 0) "page" "999" "size" "999999"})]
       (is (= 50 (:size r))) (is (= 3 (:page r))) (is (= 20 (count (:items r)))))
     (let [r (catalog/tokens-page {:runtime {}} {"graph-id" (id 4) "state" "active" "page" "3"})]
       (is (= 50 (:total r))) (is (= 10 (count (:items r))))
       (is (every? #(= (id 4) (:graph-id %)) (:items r)))))))
