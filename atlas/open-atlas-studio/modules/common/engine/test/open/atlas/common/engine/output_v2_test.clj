(ns open.atlas.common.engine.output-v2-test
  "Semantic contract 2.0 §6 (doc 109 §109.8 item 3, owner 2026-09-18) at the
   engine seam: a union `:output` lowers to Pathom's union spelling and each
   record is discriminated by exactly one branch identity (zero or several is
   withheld and reported, never guessed); a recursion node is a declared depth
   ceiling the gate clamps a request to -- the one permitted «truncation»,
   reported as `:recursion/depth-applied` -- while an undeclared recursion is
   refused and a cycle stops the walk with an info issue. Measured against the
   pathom3 2025.01.16-alpha facts in the task book (§0)."
  (:require [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def ^:private tickets
  [{:t.ticket/id 1 :t.ticket/subject {:t.device/id 10 :t.device/name "router"}}
   {:t.ticket/id 2 :t.ticket/subject {:t.person/id 20 :t.person/email "a@b"}}
   ;; Both identities: no branch is «the» record. Withheld, reported.
   {:t.ticket/id 3 :t.ticket/subject {:t.device/id 11 :t.device/name "sw" :t.person/id 21 :t.person/email "x@y"}}
   ;; Neither identity: the same.
   {:t.ticket/id 4 :t.ticket/subject {:t.device/name "orphan"}}
   ;; The api shape: a branch scope maps every branch, so the other branch's
   ;; identity arrives present but nil. That is one identity, not two.
   {:t.ticket/id 5 :t.ticket/subject {:t.device/id 12 :t.device/name "ap" :t.person/id nil :t.person/email nil}}])

(def ^:private groups
  {"root" {:t.group/id "root" :t.group/name "Root"}
   "a" {:t.group/id "a" :t.group/name "A"} "a1" {:t.group/id "a1" :t.group/name "A1"}
   "a1x" {:t.group/id "a1x" :t.group/name "A1X"} "b" {:t.group/id "b" :t.group/name "B"}
   "c" {:t.group/id "c" :t.group/name "C"} "d" {:t.group/id "d" :t.group/name "D"}})
(def ^:private children
  {"root" ["a" "b"] "a" ["a1"] "a1" ["a1x"] "a1x" [] "b" [] "c" ["d"] "d" ["c"]})

(def ^:private calls (atom []))

;; A test-only source type on the REAL SPI dispatch (same precedent as
;; `fake-connector` and `priority-v2-test`): the engine walks the path it
;; walks for sql.
(defmethod spi/compile-source "test-tree" [_source-config _connection {:keys [id]}]
  (fn [input _eql-params]
    (swap! calls conj [id (:t.group/id input)])
    (case id
      "group_by_id" (get groups (:t.group/id input))
      "group_children" (mapv groups (get children (:t.group/id input))))))

(def ^:private sql-source {:type "sql" :query "SELECT 1"})

(def ^:private model-text
  (pr-str
   {:namespaces {"t.ticket" {:label "Ticket"} "t.device" {:label "Device"}
                 "t.person" {:label "Person"} "t.group" {:label "Group"}}
    :attributes {:t.ticket/id {:type :integer :identity? true :format :id :label "ID"}
                 ;; 2.0 §6.5 lowering of `:attribute/targets`.
                 :t.ticket/subject {:targets #{"t.device" "t.person"} :label "Subject"}
                 :t.device/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.device/name {:type :string :format :text :label "Name"}
                 :t.person/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.person/email {:type :string :format :text :label "Email"}
                 :t.group/id {:type :string :identity? true :format :id :label "ID"}
                 :t.group/name {:type :string :format :text :label "Name"}
                 :t.group/children {:cardinality :one-to-many :target "t.group" :label "Children"}}
    :sources {"src" {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}}
    :resolvers [{:id "ticket_by_id" :input [:t.ticket/id]
                 :output [:t.ticket/id {:t.ticket/subject {:t.device/id [:t.device/id :t.device/name]
                                                           :t.person/id [:t.person/id :t.person/email]}}]
                 :result_type "single"
                 :source {:type "static" :match-on :t.ticket/id :data tickets}}
                {:id "group_by_id" :input [:t.group/id] :output [:t.group/id :t.group/name]
                 :result_type "single" :source sql-source :source-ref "src"}
                ;; The recursion node sits on the resolver that answers the
                ;; direct children (2.0 §6.3); depth ceiling 3.
                {:id "group_children" :input [:t.group/id]
                 :output [{:t.group/children [:t.group/id :t.group/name {:t.group/children 3}]}]
                 :result_type "list" :limit {:default 50 :max 50}
                 :source sql-source :source-ref "src"}]}))

(def ^:private compiled
  (delay
    (let [{:keys [model findings]} (dialect/read-dialect model-text)
          _ (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
          model (-> model
                    (update :sources update-vals #(assoc % :type "test-tree"))
                    (update :resolvers (fn [rs] (mapv #(if (= "static" (get-in % [:source :type])) %
                                                           (assoc-in % [:source :type] "test-tree")) rs))))
          result (engine/compile-complete model)]
      (assert (:compiled result) (pr-str (:findings result)))
      (:compiled result))))

(defn- pco-output [id]
  (-> @compiled :engine/bundle :env ::pci/index-resolvers
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config ::pco/output))

(defn- ask [entity query]
  (reset! calls [])
  (engine/query @compiled {:graph "t" :version 1} {:entity entity :query query}))

(defn- issues-of [answer code] (filter #(= code (:code %)) (:engine/issues answer)))

(def ^:private union-query
  [{:t.ticket/subject {:t.device/id [:t.device/name] :t.person/id [:t.person/email]}}])

;; ---------------------------------------------------------------------------

(deftest the-dialect-reader-accepts-the-2-0-nodes-and-the-engine-writes-them-to-pathom
  (let [{:keys [model findings]} (dialect/read-dialect model-text)]
    (is (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    (is (= #{"t.device" "t.person"} (get-in model [:attributes :t.ticket/subject :targets])))
    (is (= ["t.device" "t.person"] (get (dialect/join-attributes model) :t.ticket/subject))
        "a union join reaches several entities; the join table says which"))
  (testing "::pco/output carries the union with identity keys and the recursion with its depth"
    (is (= [:t.ticket/id {:t.ticket/subject {:t.device/id [:t.device/id :t.device/name]
                                             :t.person/id [:t.person/id :t.person/email]}}]
           (pco-output "ticket_by_id")))
    (is (= [{:t.group/children [:t.group/id :t.group/name {:t.group/children 3}]}]
           (pco-output "group_children"))))
  (testing "the declared ceiling is on the bundle for the gate"
    (is (= {:t.group/children 3} (get-in @compiled [:engine/bundle :recursion-decls]))))
  (testing "the catalog lists the union join with its targets"
    (is (= ["t.device" "t.person"] (get-in @compiled [:engine/bundle :catalog :joins :t.ticket/subject])))))

(deftest a-union-record-answers-the-branch-whose-identity-it-carries
  (let [device (ask {:t.ticket/id 1} union-query)
        person (ask {:t.ticket/id 2} union-query)]
    (is (= :ok (:engine/status device)) (pr-str device))
    (is (= {:t.device/name "router"} (get-in device [:engine/data :t.ticket/subject])))
    (is (nil? (:engine/issues device)))
    (is (not (:engine/partial? device)))
    (is (= {:t.person/email "a@b"} (get-in person [:engine/data :t.ticket/subject]))))
  (testing "a present-but-nil identity of the other branch is not a second branch"
    (let [a (ask {:t.ticket/id 5} union-query)]
      (is (= {:t.device/name "ap"} (get-in a [:engine/data :t.ticket/subject])) (pr-str a))
      (is (nil? (:engine/issues a))))))

(deftest a-union-record-matching-zero-or-several-branches-is-withheld-and-reported
  (doseq [[id why] [[3 "both identities present"] [4 "no identity present"]]]
    (let [a (ask {:t.ticket/id id} union-query)]
      (is (= :ok (:engine/status a)) (pr-str a))
      (is (nil? (get-in a [:engine/data :t.ticket/subject])) why)
      (is (:engine/partial? a) "a withheld value IS partiality (2.0 §6.4)")
      (let [[f :as fs] (issues-of a :engine.execution/union-ambiguous)]
        (is (= 1 (count fs)) (pr-str (:engine/issues a)))
        (is (= :error (:severity f)))
        (is (= [:t.ticket/subject] (:path f)))
        (is (= "ticket_by_id" (:resolver-id f)))
        (is (not (re-find #"router|\"sw\"|orphan|x@y|\b11\b|\b21\b" (pr-str f))) "no value leaves the engine (finding-v1)")))))

(deftest a-recursive-request-within-the-ceiling-walks-the-tree-and-reports-no-clamp
  (let [a (ask {:t.group/id "root"} [:t.group/name {:t.group/children 3}])]
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= {:t.group/name "Root"
            :t.group/children [{:t.group/name "A"
                                :t.group/children [{:t.group/name "A1"
                                                    :t.group/children [{:t.group/name "A1X" :t.group/children []}]}]}
                               {:t.group/name "B" :t.group/children []}]}
           (:engine/data a)))
    (is (not (contains? a :recursion/depth-applied)))
    (is (nil? (:engine/issues a)))
    (is (= 5 (count (filter #(= "group_children" (first %)) @calls))) "one children call per group reached")))

(deftest a-request-above-the-ceiling-or-unbounded-is-clamped-and-the-clamp-is-reported
  (doseq [[requested] [[5] ['...]]]
    (let [a (ask {:t.group/id "root"} [:t.group/name {:t.group/children requested}])]
      (is (= :ok (:engine/status a)) (pr-str a))
      (is (= [{:path [] :attribute :t.group/children :requested requested :applied 3}]
             (:recursion/depth-applied a))
          (pr-str requested))
      (is (= "A1X" (get-in a [:engine/data :t.group/children 0 :t.group/children 0 :t.group/children 0 :t.group/name]))
          "the walk goes exactly to the declared depth")))
  (testing "a depth below the ceiling is honoured as asked, without a report"
    (let [a (ask {:t.group/id "root"} [:t.group/name {:t.group/children 1}])]
      (is (= [{:t.group/name "A" :t.group/children [{:t.group/name "A1"}]} {:t.group/name "B" :t.group/children []}]
             (get-in a [:engine/data :t.group/children]))
          "the boundary children keep what their row already carried and are not descended into")
      (is (not (contains? a :recursion/depth-applied)))))
  (testing "a plain nested sub-query on the same join is 1.x behaviour, byte for byte"
    (let [a (ask {:t.group/id "root"} [:t.group/name {:t.group/children [:t.group/name]}])]
      (is (= [{:t.group/name "A"} {:t.group/name "B"}] (get-in a [:engine/data :t.group/children])))
      (is (not (contains? a :recursion/depth-applied)))
      (is (nil? (:engine/issues a))))))

(deftest a-recursion-form-on-an-undeclared-join-is-refused
  (doseq [form [2 '...]]
    (let [a (ask {:t.ticket/id 1} [{:t.ticket/subject form}])]
      (is (= :rejected (:engine/status a)) (pr-str a))
      (is (= :engine.request/recursion-not-declared (:code a)))
      (is (= [:query :t.ticket/subject] (:path a)))
      (is (empty? @calls) "nothing was planned or read"))))

(deftest a-cycle-stops-the-walk-before-the-ceiling-and-says-so
  (let [a (ask {:t.group/id "c"} [:t.group/name {:t.group/children 3}])]
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= {:t.group/name "C"
            :t.group/children [{:t.group/name "D"
                                ;; C again: the record is kept, its recursion is cut.
                                :t.group/children [{:t.group/name "C" :t.group/children []}]}]}
           (:engine/data a)))
    (let [[f :as fs] (issues-of a :engine.execution/recursion-cycle)]
      (is (= 1 (count fs)) (pr-str (:engine/issues a)))
      (is (= :info (:severity f)))
      (is (= [:t.group/children 0 :t.group/children 0 :t.group/children] (:path f))))
    (is (not (:engine/partial? a)) "a stopped cycle is information, not a missing value")))
