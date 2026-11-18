(ns open.atlas.studio.ui.source-list-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            ["react-dom/server" :as server]
            [uix.core :refer [$]]
            [open.atlas.studio.ui.vlist :as vlist]
            [open.atlas.studio.ui.source-summary :as source-summary]
            [open.atlas.studio.ui.semantic :as semantic]))

(deftest contract-only-sources-are-not-described-as-unused
  (let [source {:id "myhr-postgres" :label "myHR published views (read only)"
                :used-by [] :draft-usage {:resolvers 40 :operations 0 :graphs 1}}]
    (is (= "myHR published views (read only)" (source-summary/label source)))
    (is (source-summary/draft-used? source))
    (is (= "40 resolvers · 1 Graph draft" (source-summary/usage-text source))))
  (is (= "2 resolvers · 1 operation · 1 Graph draft"
         (source-summary/usage-text {:used-by ["legacy"]
                                     :draft-usage {:resolvers 1 :operations 1 :graphs 1}}))))

(deftest thousands-of-source-rows-render-only-a-window
  (let [html (server/renderToStaticMarkup
              ($ vlist/vlist {:items (mapv #(hash-map :id %) (range 2000))
                              :key-fn :id :row-size 78
                              :viewport-style {:height 420 :overflow-y "auto"}
                              :render-row (fn [item] ($ :span (str "source-row-" (:id item))))}))]
    (is (= 12 (count (re-seq #"source-row-" html))))
    (is (str/includes? html "height:156000px"))
    (is (str/includes? html "overflow-y:auto"))
    (is (not (str/includes? html "source-row-1999")))))

(deftest import-page-does-not-mount-a-full-file-editor
  (let [html (server/renderToStaticMarkup ($ semantic/semantic-editor {:graph "w/g" :can-edit? true}))]
    (is (str/includes? html "Resolver Sources"))
    (is (not (str/includes? html "Model (EDN)")))
    (is (not (str/includes? html "semantic-document-editor")))
    (is (str/includes? html "Needs attention"))))
