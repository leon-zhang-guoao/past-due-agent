(ns open.atlas.studio.ui.things-view-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [open.atlas.studio.ui.app :as app]
            [uix.core :refer [$]]
            ["react-dom/server" :as react-server]))

(defn- render [need section]
  (react-server/renderToStaticMarkup
   ($ app/things-view {:need need
                       :graph "test/support"
                       :semantic? true
                       :semantic-revision 3
                       :initial-section section
                       :on-open-form (fn [_])
                       :on-open-definition (fn [])})))

(deftest concepts-and-validation-are-safe-render-branches
  (testing "zero-item branches remain visible instead of handing case labels to React"
    (let [need {:things [] :fragments [] :concepts [] :findings []}]
      (is (str/includes? (render need :concepts) "No Concepts match this search."))
      (is (str/includes? (render need :findings) "No validation issues match this search."))))
  (testing "finding messages are rendered as text even when a checker returns a keyword"
    (let [need {:things [] :fragments []
                :concepts [{:concept "support/case" :definition {:label "Case"}
                            :attributes []}]
                :findings [{:code :contract/problem :message :needs-source}]}]
      (is (str/includes? (render need :concepts) "support/case"))
      (is (str/includes? (render need :findings) ":needs-source")))))

(deftest source-removal-blockers-group-by-graph
  ;; owner 2026-10-04: an archived graph is named as such, and 200 objects read
  ;; as one group per graph rather than 200 rows.
  (let [plan {:blocking-graphs [{:graph "demo/cis" :state "archived" :objects 2}]
              :used-by {:objects [{:type "resolver" :id "a" :graphs ["demo/cis"]}
                                  {:type "attribute" :id "b" :graphs ["demo/cis"]}
                                  {:type "link" :id "c" :graphs []}]}}
        [g other] (app/source-removal-blocker-groups plan)]
    (is (= ["Delete or restore archived graph demo/cis first."]
           (app/source-removal-graph-lines plan)))
    (is (= "demo/cis (archived) — 2 objects" (:title g)))
    (is (= ["a" "b"] (map :id (:objects g))))
    (is (= ["c"] (map :id (:objects other))))))

(deftest source-removal-names-an-unreachable-workspace
  ;; owner 2026-10-04: demo/cis sat in a workspace the requester cannot enter.
  (is (= ["demo/cis is in workspace demo — you are not a member. As a Studio administrator you can delete that graph here."]
         (app/source-removal-graph-lines
          {:blocking-graphs [{:graph "demo/cis" :state "active" :workspace "demo"
                              :can-open? false :can-retire? true :objects 200}]})))
  (is (= ["demo/cis is in workspace demo — you are not a member. Ask its workspace owner or a Studio administrator to remove it."]
         (app/source-removal-graph-lines
          {:blocking-graphs [{:graph "demo/cis" :state "active" :workspace "demo"
                              :can-open? false :can-retire? false :objects 200}]}))))

(deftest source-removal-verdict-is-one-sentence
  ;; owner 2026-10-04: the removal panel opens with a verdict.
  (is (= "Can't remove cis-demo-db: 1 Graph draft still binds it."
         (app/source-removal-verdict {:source "cis-demo-db" :removable? false
                                      :used-by {:drafts ["demo/cis"]}})))
  (is (= "Remove NiFi and the 15 resolvers only it feeds? No Graph uses them."
         (app/source-removal-verdict {:source "NiFi" :removable? true
                                      :also-removed (repeat 15 {:type "resolver" :id "\"r\""})})))
  (is (= "Remove lens? Nothing uses it."
         (app/source-removal-verdict {:source "lens" :removable? true})))
  (is (str/includes? (app/source-removal-verdict
                      {:source "x" :removable? false
                       :used-by {:objects [{:type "resolver"} {:type "attribute"}]
                                 :serving [{:graph "g" :version "2"}]}})
                     "1 serving version runs on it; 2 objects a Graph still needs read it")))
