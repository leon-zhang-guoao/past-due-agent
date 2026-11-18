(ns open.atlas.studio.tools.repair-test
  "**8.4.21 裁定十五 ①** — one inference, one shape.

   The end-to-end half (the same `:repair` in `inspect_graph`, `publish_graph`
   and `execute_eql`, sent as it stands and then the rows come back) lives in
   `apps/studio`'s `repair_test`, because it needs a database that really
   opens. What is unit-testable here is the inference itself: which link is
   found, what the prefilled call says, and — the point of the ruling — that a
   defect the machine CANNOT repair carries no `:repair` at all."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.studio.tools.impl.repair :as repair]))

(def ^:private model
  {:namespaces {"cis.users" {} "cis.posts" {}}
   :attributes {:cis.users/Id {:type :integer :identity? true}
                :cis.posts/Id {:type :integer :identity? true}
                :cis.posts/Title {:type :string}
                :cis.posts/OwnerUserId {:type :integer}}
   :links [{:from :cis.users/Id :to :cis.posts/OwnerUserId :direction :bi}]
   :resolvers [{:id "cis_posts_flat_list"
                :input [] :result_type "list"
                :output [:cis.posts/Id :cis.posts/Title]
                :source {:type "sql" :query "SELECT Id, Title FROM posts"}}]})

(def ^:private linkless (dissoc model :links))

(defn- ctx [m & {:as extra}] (merge {:model m :graph "demo/app"} extra))

(def ^:private anchorless
  {:code :engine.compile/list-without-anchor :severity :warning
   :path [:resolvers "cis_posts_flat_list"]
   :message "resolver cis_posts_flat_list returns a list but hangs under no entity's collection."
   :refs ["D2.8"]})

(def ^:private unreachable
  {:code :engine.request/unreachable :severity :error :path [:query]
   :message "these attributes cannot be reached from where this query starts."
   :attributes [:cis.posts/Title]})

(deftest anchorless-list-carries-the-child-collection-that-fixes-it
  (let [f (repair/repair-for anchorless (ctx model))
        r (:repair f)]
    (is (= "add_relation" (:tool r)))
    (is (= {:graph "demo/app" :parent "cis.users" :child "cis.posts"
            :kind "many" :via "OwnerUserId" :name "posts"}
           (:args r))
        "the child table comes from the resolver's own FROM clause and the key name defaults")
    (testing "and the resolver that compiles to nothing goes with it"
      (is (= [{:tool "remove_objects"
               :args {:objects ["cis_posts_flat_list"] :graph "demo/app"}}]
             (:then r))))
    (testing "the message gains the link it found — the engine could not name it"
      (is (re-find #"cis\.users/Id" (:message f)))
      (is (= (:code anchorless) (:code f)))
      (is (= (:path anchorless) (:path f)))
      (is (= (:refs anchorless) (:refs f))))))

(deftest no-link-means-no-repair-rather-than-a-guessed-one
  (let [f (repair/repair-for anchorless (ctx linkless))]
    (is (nil? (:repair f)))
    (is (= anchorless f) "the finding travels unchanged")))

(deftest unreachable-with-a-link-is-the-same-child-collection
  (let [r (:repair (repair/repair-for unreachable
                                      (ctx model :entity-namespaces #{"cis.users"})))]
    (is (= "add_relation" (:tool r)))
    (is (= "cis.users" (get-in r [:args :parent])))
    (is (= "OwnerUserId" (get-in r [:args :via])))))

(deftest unreachable-without-a-link-is-an-add-link-template-a-person-fills
  (let [f (repair/repair-for unreachable (ctx linkless :entity-namespaces #{"cis.users"}))
        r (:repair f)]
    (is (= "add_link" (:tool r)) (pr-str r))
    (is (= {:graph "demo/app" :from "" :to ""} (:args r))
        "the two ends are empty on purpose — which columns relate is a person's answer")
    (is (string? (:note r)))
    (is (re-find #"person" (:message f)))))

(deftest mixed-output-becomes-the-call-that-sends-the-delta
  (let [f {:code :scope/mixed-output :severity :error :path [:resolver "r"]
           :message "this object produces attributes this graph's recipe does not name."
           :expected {:recipe-delta {:add {:attributes [:cis.users/posts]}}}}
        r (:repair (repair/repair-for f (ctx model)))]
    (is (= {:tool "change_graph_members"
            :args {:add ["cis.users/posts"] :graph "demo/app"}}
           r))))

(deftest a-finding-nobody-can-repair-is-returned-untouched
  (let [f {:code :engine.execution/failed :severity :error :path [:query] :message "x"}]
    (is (= f (repair/repair-for f (ctx model))))))

(deftest without-a-model-nothing-is-inferred
  (is (= anchorless (repair/repair-for anchorless {:graph "demo/app"}))))

;; ---------------------------------------------------------------------------
;; The two read-back defects (doc 106 §106.5, P8a)
;;
;; `dialect/need-view` states them and knows no tool names — `common` naming a
;; Builder tool would be the dependency D1.8 draws the line against — so the
;; call that fixes each is inferred HERE, where every other repair is.
;; ---------------------------------------------------------------------------

(def ^:private need-model
  {:namespaces {"q.queue" {} "q.history" {}}
   :attributes {:q.queue/id {:type :string}
                :q.history/all_history {:target "q.history" :cardinality :one-to-many}
                :q.history/at {:type :datetime}}
   :resolvers [{:id "queue_by_id" :input [:q.queue/id] :result_type "single"
                :source-ref "s" :output [:q.queue/id]}
               {:id "queue_history" :input [:q.queue/id] :result_type "list"
                :source-ref "s" :output [{:q.history/all_history [:q.history/at]}]}]})

(deftest an-undeclared-identity-carries-the-one-call-that-declares-it
  (let [f (repair/repair-for
           {:code :dialect.need/identity-undeclared :severity :warning
            :path [:attributes :q.queue/id]
            :message "this reads as the identity of q.queue"
            :expected "mark :identity? true"}
           (ctx need-model))]
    (is (= {:tool "update_attribute"
            :args {:attribute "q.queue/id" :identity true :graph "demo/app"}}
           (:repair f)))))

(deftest a-misplaced-relation-carries-the-move-to-the-parent
  (let [f (repair/repair-for
           {:code :dialect.need/join-misplaced :severity :warning
            :path [:attributes :q.history/all_history]
            :message "this relation hangs on q.history"
            :expected ["q.queue"]}
           (ctx need-model))
        r (:repair f)]
    (is (= "update_resolver" (:tool r)))
    (is (= "queue_history" (get-in r [:args :resolver])))
    (testing "the branch KEY moves to the parent and its children travel unchanged"
      (is (= {"q.queue/all_history" ["q.history/at"]} (get-in r [:args :output]))))
    (testing "the misplaced attribute is removed afterwards, and the note says
              what a person still has to create"
      (is (= [{:tool "remove_objects"
               :args {:objects ["q.history/all_history"] :graph "demo/app"}}]
             (:then r)))
      (is (re-find #"q\.queue/all_history" (:note r))))))

(deftest attach-keeps-the-shape-a-vector
  (is (= [] (repair/attach nil (ctx model))))
  (is (vector? (repair/attach [anchorless] (ctx model)))))

;; ---------------------------------------------------------------------------
;; «You said it twice» at the cut — 0.2 acceptance defect 13, doc 84 §8.4.25
;; ---------------------------------------------------------------------------

(def ^:private twice-model
  "One thing said twice: the second resolver took the first one's id plus
   `_from_<relation>` and reads exactly the same statement from the same
   source. Every attribute of the thing is then refused by the cut's
   `multiple-producers`, and nothing in the refusal says why."
  {:attributes {:cis.posts/Id {:type :integer :identity? true}
                :cis.posts/Title {:type :string}}
   :resolvers [{:id "cis_posts_by_Id" :input [:cis.posts/Id] :result_type "single"
                :output [:cis.posts/Id :cis.posts/Title]
                :source-ref "db"
                :source {:type "sql" :query "SELECT Id, Title FROM posts WHERE Id IN (:Ids)"}}
               {:id "cis_posts_by_Id_from_posts" :input [:cis.posts/Id] :result_type "single"
                :output [:cis.posts/Id :cis.posts/Title]
                :source-ref "db"
                :source {:type "sql" :query "SELECT Id, Title FROM posts WHERE Id IN (:Ids)"}}]})

(defn- producers-finding [ids]
  {:code :cut.structure/multiple-producers :severity :error
   :path [:attributes :cis.posts/Title]
   :message "two \"single\" resolvers produce this attribute from the same input anchor; the planner cannot choose"
   :got :cis.posts/Title :expected ids :refs ["D2.11" "D2.8"]})

(deftest multiple-producers-off-identical-sources-carries-the-removal
  (let [f (repair/repair-for (producers-finding ["cis_posts_by_Id" "cis_posts_by_Id_from_posts"])
                             (ctx twice-model))]
    (is (= "remove_objects" (:tool (:repair f))))
    (is (= {:objects ["cis_posts_by_Id_from_posts"] :graph "demo/app"} (:args (:repair f)))
        "the NEWER of the two — the one whose id extends the other's")
    (is (re-find #"said twice" (:message f)))))

(deftest multiple-producers-off-different-sources-carries-no-repair
  (testing "two deliberate producers are a modelling decision, not a repeat"
    (let [m (assoc-in twice-model [:resolvers 1 :source-ref] "other-db")
          f (repair/repair-for
             (producers-finding ["cis_posts_by_Id" "cis_posts_by_Id_from_posts"])
             (ctx m))]
      (is (nil? (:repair f)))))
  (testing "and two ids that are not one-derived-from-the-other say nothing"
    (let [m (assoc-in twice-model [:resolvers 1 :id] "cis_posts_second")
          f (repair/repair-for (producers-finding ["cis_posts_by_Id" "cis_posts_second"])
                               (ctx m))]
      (is (nil? (:repair f))))))

(deftest already-said-carries-the-update-call
  (let [f (repair/repair-for
           {:code :tools.intent/already-said :severity :error :path [:resolver]
            :message "this sentence was already said, and it wrote resolver cis_posts_by_Id"
            :got "cis_posts_by_Id" :expected ["output"]}
           (ctx twice-model))]
    (is (= "update_resolver" (:tool (:repair f))))
    (is (= {:resolver "cis_posts_by_Id" :graph "demo/app"} (:args (:repair f))))
    (is (re-find #"add_attribute" (:note (:repair f))))))
