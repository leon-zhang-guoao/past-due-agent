(ns open.atlas.studio.pool.batch-test
  "`execute-actions`: all of them, or none.

   The behaviour under test is not \"a loop that stops early\" — it is that a
   refused batch leaves the pool byte-identical to how it found it, and that a
   committed batch could not have been written as separate calls (a join
   attribute pointing at a namespace that does not exist yet would be refused on
   its own)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)

(defn with-empty-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "batch-pool"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)]
      (binding [*conn* conn] (f))
      (pool/close-pool! conn)
      (pool/delete-pool! cfg))))

(use-fixtures :each with-empty-pool)

(defn- create [type id entry]
  {:workspace "demo" :action :create :type type :id id :entry entry :expected_revision 0})

(def ^:private one-table
  "What a generated table looks like: a namespace, its scalar attributes, the
   join attribute that reaches the collection (D2.29), and the two resolvers
   that produce them. Ordered so everything an action names already exists."
  [(create :namespace "cis.demo.vendors" {})
   (create :attribute :cis.demo.vendors/vend_no {:type :integer :identity? true})
   (create :attribute :cis.demo.vendors/vend_name {:type :string})
   (create :attribute :cis.demo.vendors/all_vendors
           {:target "cis.demo.vendors" :cardinality :one-to-many})
   (create :source "demo-db" {:type "sql" :jdbc_url "${DEMO_JDBC_URL}"})
   (create :resolver "vendors_by_vend_no"
           {:id "vendors_by_vend_no"
            :input [:cis.demo.vendors/vend_no]
            :output [:cis.demo.vendors/vend_no :cis.demo.vendors/vend_name]
            :result_type "single"
            :source {:type "sql" :query "SELECT vend_no, vend_name FROM vendors WHERE vend_no = :vend_no"}
            :source-ref "demo-db"})
   (create :resolver "all_vendors_of_vendors"
           {:id "all_vendors_of_vendors"
            :curated? true
            :input []
            :limit {:default 50 :max 500}
            :output [{:cis.demo.vendors/all_vendors
                      [:cis.demo.vendors/vend_no :cis.demo.vendors/vend_name]}]
            :result_type "list"
            :source {:type "sql" :query "SELECT vend_no, vend_name FROM vendors"}
            :source-ref "demo-db"})])

(defn- object-count [] (count (pool/objects (pool/db *conn*))))

;; ---------------------------------------------------------------------------

(deftest a-whole-table-commits-as-one-unit
  (let [r (pool/execute-actions *conn* one-table)]
    (is (empty? (filter #(= :error (:severity %)) (:findings r)))
        (pr-str (:findings r)))
    (is (= (count one-table) (count (:revisions r))))
    (is (every? #(= 1 %) (:revisions r)) "every object in the batch is new")
    (is (= (count one-table) (object-count)))))

(deftest an-action-may-depend-on-what-the-same-batch-creates
  ;; The attributes the collection resolver produces do not exist when the batch
  ;; starts. Alone, that action is refused by the dialect gate; inside a batch
  ;; that also registers them, it is not — which is the whole reason a batch
  ;; exists rather than a loop.
  (let [alone (pool/execute-action *conn* (last one-table))]
    (is (seq (filter #(= :error (:severity %)) (:findings alone)))
        "on its own, a resolver producing unregistered attributes is refused")
    (is (zero? (object-count))))
  (let [r (pool/execute-actions *conn* one-table)]
    (is (empty? (filter #(= :error (:severity %)) (:findings r))))
    (is (some? (pool/object (pool/db *conn*) :attribute :cis.demo.vendors/all_vendors)))
    (is (some? (pool/object (pool/db *conn*) :resolver "all_vendors_of_vendors")))))

(deftest a-refused-batch-writes-nothing
  (let [broken (conj (vec one-table)
                     ;; A resolver reading an attribute nothing registers: the
                     ;; dialect rejects the pool this batch would produce.
                     (create :resolver "orphan"
                             {:id "orphan"
                              :input [:cis.demo.nowhere/missing]
                              :output [:cis.demo.vendors/vend_name]
                              :result_type "single"
                              :source {:type "sql" :query "SELECT vend_name FROM vendors WHERE x = :x"}
                              :source-ref "demo-db"}))
        r (pool/execute-actions *conn* broken)]
    (is (seq (filter #(= :error (:severity %)) (:findings r))))
    (is (zero? (object-count))
        "not one of the seven valid actions ahead of the bad one was written")))

(deftest a-refusal-names-which-action-it-is-about
  (let [r (pool/execute-actions
           *conn* [(create :namespace "cis.demo.vendors" {})
                   (create :attribute :cis.demo.vendors/vend_no {:type :integer})
                   ;; expected_revision 3 on a create — the object must not exist
                   {:workspace "demo" :action :create :type :attribute :id :cis.demo.vendors/other
                    :entry {:type :string} :expected_revision 3}])
        err (first (filter #(= :error (:severity %)) (:findings r)))]
    (is (= [:batch 2] (take 2 (:path err)))
        "without the index, a refusal on the third of twenty similar writes is
         a puzzle rather than a message")
    (is (zero? (object-count)))))

(deftest an-action-that-collides-with-what-is-already-there-refuses-the-batch
  (pool/execute-actions *conn* one-table)
  (let [before (object-count)
        r      (pool/execute-actions *conn* one-table)]
    (is (seq (filter #(= :error (:severity %)) (:findings r))))
    (is (= before (object-count)) "a re-run is refused whole, not merged")))

(deftest order-is-the-callers-and-is-not-silently-fixed
  ;; Each action is validated against the pool as the earlier ones left it, so
  ;; an update before the create it depends on is refused by name rather than
  ;; reordered — this ns does not know which of two possible orders the caller
  ;; meant. (The dialect gate, by contrast, runs once over the batch's end
  ;; state, so an order that only reshuffles mutually consistent objects is
  ;; accepted: the pool the batch would leave behind is the same one.)
  (let [r (pool/execute-actions
           *conn* [(create :namespace "cis.demo.vendors" {})
                   {:workspace "demo" :action :update :type :attribute :id :cis.demo.vendors/vend_no
                    :entry {:label "Vendor no"} :expected_revision 1}
                   (create :attribute :cis.demo.vendors/vend_no {:type :integer})])
        err (first (filter #(= :error (:severity %)) (:findings r)))]
    (is (= :pool.action/unknown-entity (:code err)))
    (is (= [:batch 1] (take 2 (:path err))))
    (is (zero? (object-count)))))

(deftest an-empty-batch-is-refused-rather-than-reported-as-success
  ;; Almost always a caller bug: a selection that filtered to nothing, or a
  ;; plan whose proposals were all blocked.
  (let [r (pool/execute-actions *conn* [])]
    (is (seq (filter #(= :error (:severity %)) (:findings r))))
    (is (nil? (:revisions r))))
  (testing "and so is something that is not a sequence of actions"
    (is (seq (:findings (pool/execute-actions *conn* {:workspace "demo" :action :create}))))))

(deftest the-batch-goes-through-the-same-validation-as-a-single-action
  ;; Not a second write face: a batch of one must answer exactly as
  ;; execute-action does for the same action.
  (let [a (create :namespace "cis.demo.vendors" {})
        r (pool/execute-actions *conn* [a])]
    (is (= [1] (:revisions r)))
    (is (= 1 (:pool/revision (pool/object (pool/db *conn*) :namespace "cis.demo.vendors"))))))

(deftest findings-carry-no-entry-content
  ;; A source entry has a :jdbc_url sitting in it (B4 exit condition 2).
  (let [r (pool/execute-actions
           *conn* [(create :source "demo-db" {:type "sql" :jdbc_url "jdbc:postgresql://host/db?password=hunter2"})
                   {:workspace "demo" :action :create :type :source :id "demo-db"
                    :entry {:type "sql"} :expected_revision 9}])]
    (is (not (clojure.string/includes? (pr-str (:findings r)) "hunter2")))))
