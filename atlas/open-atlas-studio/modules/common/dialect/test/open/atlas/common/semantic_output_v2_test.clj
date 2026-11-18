(ns open.atlas.common.semantic-output-v2-test
  "Semantic contract 2.0 §6 (doc 109 §109.8 item 3, owner 2026-09-18): union
   and recursive output at the dialect layer -- the vocabulary's multi-target
   ref, the two `:output` node forms and their rules, the checker carriers, the
   capability derivation, and record validation by matched branch. Plus the
   three §3.1 amendments of the same date (task book 补充任务 B)."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic :as semantic]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-execution :as execution]
            [open.atlas.common.semantic-record :as record]))

(def ^:private vocabulary
  {:entities {"ticket" {:entity/label "Ticket" :entity/identity [:ticket/id]
                        :entity/attributes [:ticket/id :ticket/subject :ticket/all]}
              "device" {:entity/label "Device" :entity/identity [:device/id]
                        :entity/attributes [:device/id :device/name :device/owner]}
              "person" {:entity/label "Person" :entity/identity [:person/id]
                        :entity/attributes [:person/id :person/email]}
              "group" {:entity/label "Group" :entity/identity [:group/id]
                       :entity/attributes [:group/id :group/name :group/children]}
              "pair" {:entity/label "Pair" :entity/identity [:pair/a :pair/b]
                      :entity/attributes [:pair/a :pair/b]}}
   :attributes {:ticket/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :ticket/subject {:attribute/label "Subject" :attribute/type :ref :attribute/nullable? false
                                 :attribute/targets #{"device" "person"}}
                :ticket/all {:attribute/label "All" :attribute/type :ref :attribute/nullable? false
                             :attribute/target "ticket" :attribute/cardinality :many}
                :device/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :device/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :device/owner {:attribute/label "Owner" :attribute/type :ref :attribute/nullable? true
                               :attribute/target "person"}
                :person/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :person/email {:attribute/label "Email" :attribute/type :string :attribute/nullable? false}
                :group/id {:attribute/label "ID" :attribute/type :string :attribute/nullable? false}
                :group/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :group/children {:attribute/label "Children" :attribute/type :ref :attribute/nullable? false
                                 :attribute/target "group" :attribute/cardinality :many}
                :pair/a {:attribute/label "A" :attribute/type :string :attribute/nullable? false}
                :pair/b {:attribute/label "B" :attribute/type :string :attribute/nullable? false}}})

(def ^:private union-output
  [:ticket/id {:ticket/subject {"device" [:device/id :device/name]
                                "person" [:person/id :person/email]}}])

(def ^:private execution
  {:resolvers {"ticket-by-id" {:label "Ticket" :effect :read :result/cardinality :one
                               :input [:ticket/id] :output union-output}
               "group-children" {:label "Children" :effect :read :result/cardinality :many
                                 :input [:group/id]
                                 :output [{:group/children [:group/id :group/name {:group/children 3}]}]
                                 :pagination {:default 10 :max 100}
                                 :sort {:allowed [:group/name] :default [:group/name :asc]}}
               ;; No recursion node here: `group-children` produces the key, and
               ;; a second producer would be a §4.1 priority tie (a recursion
               ;; node sits on the resolver that answers the direct children).
               "group-by-id" {:label "Group" :effect :read :result/cardinality :one
                              :input [:group/id] :output [:group/name]}}
   :operations {}
   :sources {"s" {:source/kind "test" :source/effects #{:read}}}
   :bindings {"ticket-b" {:binding/for [:resolver "ticket-by-id"] :binding/source "s" :binding/effect :read
                          :binding/implementation {:fixture :ticket}
                          :binding/input-map {:ticket/id "id"}
                          :binding/output-map {:ticket/id "id" :ticket/subject "subject"
                                               :device/id "device_id" :device/name "device_name"
                                               :person/id "person_id" :person/email "email"}}
              "children-b" {:binding/for [:resolver "group-children"] :binding/source "s" :binding/effect :read
                            :binding/implementation {:fixture :children}
                            :binding/input-map {:group/id "id"}
                            :binding/output-map {:group/children "children" :group/id "id" :group/name "name"}}
              "group-b" {:binding/for [:resolver "group-by-id"] :binding/source "s" :binding/effect :read
                         :binding/implementation {:fixture :group}
                         :binding/input-map {:group/id "id"}
                         :binding/output-map {:group/name "name"}}}
   :selection {[:resolver "ticket-by-id"] "ticket-b"
               [:resolver "group-children"] "children-b"
               [:resolver "group-by-id"] "group-b"}})

(def ^:private seen (atom []))
(def ^:private checkers {"test" (fn [b] (swap! seen conj b) {:findings []})})
(def ^:private v2 {:contract version/semantic-v2})

(defn- validate-v2
  ([e] (validate-v2 vocabulary e))
  ([v e] (execution/validate-execution v e checkers v2)))
(defn- codes [r] (set (map :code (:findings r))))
(defn- finding-at? [r code path] (some #(and (= code (:code %)) (= path (:path %))) (:findings r)))
(def ^:private at-ticket [[:resolver "ticket-by-id"] :output 1])

;; ---------------------------------------------------------------------------
;; §6.1 vocabulary
;; ---------------------------------------------------------------------------

(deftest a-multi-target-ref-is-a-2-0-vocabulary-form
  (is (empty? (:findings (semantic/validate-vocabulary vocabulary v2))))
  (testing "read the same without a descriptor; refused under a retired one"
    (is (empty? (:findings (semantic/validate-vocabulary vocabulary))))
    (is (= [version/version-retired]
           (:findings (semantic/validate-vocabulary vocabulary {:contract {:contract/family "atlas-semantic" :contract/version "1.4.0"}})))))
  (testing ":target and :targets are exclusive, both ways"
    (is (contains? (codes (semantic/validate-vocabulary
                           (assoc-in vocabulary [:attributes :ticket/subject :attribute/target] "device") v2))
                   :semantic/relationship))
    (is (contains? (codes (semantic/validate-vocabulary
                           (update-in vocabulary [:attributes :ticket/subject] dissoc :attribute/targets) v2))
                   :semantic/relationship)))
  (testing "fewer than two targets is a schema error, an unknown target a relationship error"
    (is (contains? (codes (semantic/validate-vocabulary
                           (assoc-in vocabulary [:attributes :ticket/subject :attribute/targets] #{"device"}) v2))
                   :semantic/schema))
    (is (contains? (codes (semantic/validate-vocabulary
                           (assoc-in vocabulary [:attributes :ticket/subject :attribute/targets] #{"device" "ghost"}) v2))
                   :semantic/relationship)))
  (testing "every target's identity must be exactly one attribute"
    (let [r (semantic/validate-vocabulary
             (assoc-in vocabulary [:attributes :ticket/subject :attribute/targets] #{"device" "pair"}) v2)]
      (is (finding-at? r :semantic/union-identity [:attributes :ticket/subject :attribute/targets "pair"])
          (pr-str (:findings r))))))

;; ---------------------------------------------------------------------------
;; §6.2 output nodes
;; ---------------------------------------------------------------------------

(deftest union-and-recursion-nodes-are-accepted-under-2-0-and-refused-under-a-retired-descriptor
  (let [r (validate-v2 execution)]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (= execution (:execution r))))
  (testing "the same fragments under a 1.4 descriptor are refused as retired, not read as 2.0"
    ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19).
    (let [retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"}]
      (is (= [version/version-retired]
             (:findings (execution/validate-execution vocabulary execution checkers {:contract retired-1-4}))))))
  (testing "a bare fragment with no descriptor is read as 2.0"
    (is (empty? (:findings (execution/validate-execution vocabulary execution checkers {}))))))

(deftest a-union-must-open-exactly-the-declared-branches-with-their-identities
  (let [at-output [:resolvers "ticket-by-id" :output]]
    (testing "a branch missing"
      (is (finding-at? (validate-v2 (assoc-in execution at-output
                                              [:ticket/id {:ticket/subject {"device" [:device/id :device/name]}}]))
                       :execution/union-branches at-ticket)))
    (testing "a branch too many"
      (is (finding-at? (validate-v2 (assoc-in execution (conj at-output 1 :ticket/subject "group") [:group/id]))
                       :execution/union-branches at-ticket)))
    (testing "a branch without its Entity's identity"
      (is (finding-at? (validate-v2 (assoc-in execution (conj at-output 1 :ticket/subject "device") [:device/name]))
                       :execution/union-identity-required (conj at-ticket :ticket/subject "device"))))
    (testing "a branch attribute owned by another Entity"
      (is (finding-at? (validate-v2 (assoc-in execution (conj at-output 1 :ticket/subject "device")
                                              [:device/id :person/email]))
                       :execution/output-owner (conj at-ticket :ticket/subject "device" 1))))
    (testing "a multi-target ref written as a plain join, and a single-target ref written as a union"
      (is (finding-at? (validate-v2 (assoc-in execution at-output [:ticket/id {:ticket/subject [:device/id]}]))
                       :execution/output-shape at-ticket))
      (is (finding-at? (validate-v2 (assoc-in execution [:resolvers "group-children" :output 0 :group/children 2]
                                              {:group/children {"group" [:group/id]}}))
                       :execution/output-shape [[:resolver "group-children"] :output 0 :group/children 2])))
    (testing "the output-map covers every branch's attributes"
      (is (finding-at? (validate-v2 (update-in execution [:bindings "ticket-b" :binding/output-map] dissoc :person/email))
                       :execution/output-map [:bindings "ticket-b" :binding/output-map])))))

(deftest a-recursion-node-is-a-self-reference-with-a-bounded-depth
  (let [at-children [:resolvers "group-children" :output 0 :group/children 2]]
    (testing "the ref must reach its own Entity"
      (is (finding-at? (validate-v2 (assoc-in execution [:resolvers "ticket-by-id" :output 1 :ticket/subject "device"]
                                              [:device/id {:device/owner 2}]))
                       :execution/recursion-target (conj at-ticket :ticket/subject "device" 1))))
    (testing "depth 0 and depth 17 are refused, 1 and 16 accepted"
      (doseq [d [0 17 -1]]
        (is (finding-at? (validate-v2 (assoc-in execution at-children {:group/children d}))
                         :execution/recursion-depth [[:resolver "group-children"] :output 0 :group/children 2])
            (str "depth " d)))
      (doseq [d [1 16]]
        (is (empty? (:findings (validate-v2 (assoc-in execution at-children {:group/children d})))) (str "depth " d))))
    (testing "a recursion node at the top level of a :one definition is legal when that resolver is the producer"
      (let [e (-> execution
                  (assoc-in [:resolvers "group-by-id" :output] [:group/name {:group/children 2}])
                  (assoc-in [:resolvers "group-by-id" :priority] 10)
                  (assoc-in [:bindings "group-b" :binding/output-map :group/children] "children"))]
        (is (empty? (:findings (validate-v2 e))) (pr-str (:findings (validate-v2 e))))
        (is (contains? (codes (validate-v2 (update-in e [:resolvers "group-by-id"] dissoc :priority)))
                       :execution/priority-tie)
            "two producers of the children key are the §4.1 overlap and need ranking")))
    (testing "the single root join of a :many definition is neither a union nor a recursion"
      (is (finding-at? (validate-v2 (assoc-in execution [:resolvers "group-children" :output] [{:group/children 3}]))
                       :execution/output-shape [[:resolver "group-children"] :output 0])))
    (testing "the recursion key counts as an output attribute; its level adds nothing else"
      (is (= [:group/name :group/children] (vec (execution/output-attrs [:group/name {:group/children 2}]))))
      (is (= #{:group/children} (execution/output-recursion (get-in execution [:resolvers "group-children" :output]))))
      (is (= {:group/children 3} (execution/recursion-depths (get-in execution [:resolvers "group-children" :output]))))
      (is (= #{:ticket/subject} (execution/output-union union-output))))))

(deftest checker-carriers-name-the-union-and-recursion-keys-only-when-present
  (reset! seen [])
  (validate-v2 execution)
  (let [by-fixture (into {} (map (juxt #(get-in % [:binding/implementation :fixture]) identity)) @seen)]
    (is (= #{:ticket/subject} (get-in by-fixture [:ticket :binding/output-union])))
    (is (not (contains? (get by-fixture :ticket) :binding/output-recursion)))
    (is (= #{:group/children} (get-in by-fixture [:children :binding/output-recursion])))
    (is (not (contains? (get by-fixture :children) :binding/output-union)))
    (is (not (contains? (get by-fixture :group) :binding/output-recursion)))))

(deftest a-connector-refusal-of-an-output-form-is-surfaced-under-the-binding
  (let [checker (fn [b]
                  {:findings (vec (for [a (:binding/output-union b)]
                                    {:code :connector.binding/union-output-unsupported :severity :error
                                     :path [:binding/output-map a]}))})
        r (execution/validate-execution vocabulary execution {"test" checker} v2)]
    (is (finding-at? r :connector.binding/union-output-unsupported [:bindings "ticket-b" :binding/output-map :ticket/subject])
        (pr-str (:findings r)))))

;; ---------------------------------------------------------------------------
;; capabilities
;; ---------------------------------------------------------------------------

(deftest the-two-capabilities-derive-from-the-forms-that-need-them
  (let [caps (fn [v e] (contract/required-capabilities {:contract version/semantic-v2 :vocabulary v :execution e}))
        no-union-vocab (-> vocabulary
                           (assoc-in [:attributes :ticket/subject :attribute/target] "device")
                           (update-in [:attributes :ticket/subject] dissoc :attribute/targets))]
    (is (contains? (caps vocabulary execution) :execution/output-union-v2))
    (is (contains? (caps vocabulary execution) :execution/output-recursion-v2))
    (testing "a multi-target ref alone derives the union capability"
      (is (contains? (caps vocabulary (assoc execution :resolvers {})) :execution/output-union-v2)))
    (testing "neither form, neither capability"
      (let [plain (caps no-union-vocab (update execution :resolvers dissoc "group-children" "group-by-id"
                                               "ticket-by-id"))]
        (is (not (contains? plain :execution/output-union-v2)))
        (is (not (contains? plain :execution/output-recursion-v2)))))))

;; ---------------------------------------------------------------------------
;; §6.4 record validation by matched branch
;; ---------------------------------------------------------------------------

(deftest a-union-record-is-validated-against-the-branch-it-carries
  (let [ok-device {:ticket/id 1 :ticket/subject {:device/id 7 :device/name "router"}}
        ok-person {:ticket/id 2 :ticket/subject {:person/id 9 :person/email "a@b"}}]
    (is (= ok-device (:record (record/validate-record vocabulary "ticket" ok-device))))
    (is (= ok-person (:record (record/validate-projection vocabulary "ticket" ok-person))))
    (testing "a wrong type inside the matched branch is found at the branch path"
      (is (= [:ticket/subject :device/name]
             (-> (record/validate-projection vocabulary "ticket" {:ticket/subject {:device/id 7 :device/name 3}})
                 :findings first :path))))
    (testing "zero or two identities is ambiguous, never guessed"
      (doseq [v [{:device/name "no id"} {:device/id 7 :person/id 9}]]
        (let [r (record/validate-projection vocabulary "ticket" {:ticket/subject v})]
          (is (= :record/union-ambiguous (-> r :findings first :code)) (pr-str r))
          (is (= [:ticket/subject] (-> r :findings first :path)))
          (is (nil? (:record r))))))
    (testing "a 1.x vocabulary validates exactly as before"
      (is (= :record/type (-> (record/validate-projection (dissoc vocabulary :x) "device" {:device/id "7"})
                              :findings first :code))))))

;; ---------------------------------------------------------------------------
;; 补充任务 B: contract 2.0 §3.1 amendments (owner 2026-09-18)
;; ---------------------------------------------------------------------------

(def ^:private params-execution
  (-> execution
      (assoc-in [:resolvers "ticket-by-id" :params] {:scope {:type :string :allowed ["a" "b"] :default "a"}})
      (assoc-in [:bindings "ticket-b" :binding/parameter-map] {:scope "scope"})))

(deftest allowed-is-for-enumerable-types-and-default-must-be-a-member
  (let [at (fn [k] [[:resolver "ticket-by-id"] :params :scope k])]
    (is (empty? (:findings (validate-v2 params-execution))) (pr-str (:findings (validate-v2 params-execution))))
    (testing ":allowed on :decimal, :date and :datetime is refused at :allowed"
      (doseq [[t vs] [[:decimal [1.5]] [:date ["2026-09-18"]] [:datetime ["2026-09-18T10:00:00Z"]]]]
        (is (finding-at? (validate-v2 (assoc-in params-execution [:resolvers "ticket-by-id" :params :scope]
                                                {:type t :allowed vs}))
                         :execution/parameter-value (at :allowed))
            (str t))))
    (testing ":allowed on :string, :integer and :boolean is fine"
      (doseq [[t vs] [[:string ["a"]] [:integer [1 2]] [:boolean [true]]]]
        (is (empty? (:findings (validate-v2 (assoc-in params-execution [:resolvers "ticket-by-id" :params :scope]
                                                      {:type t :allowed vs}))))
            (str t))))
    (testing "a :default outside :allowed is refused at :default"
      (is (finding-at? (validate-v2 (assoc-in params-execution [:resolvers "ticket-by-id" :params :scope :default] "z"))
                       :execution/parameter-value (at :default))))))

(deftest an-api-resolver-cannot-be-both-batched-and-parameterised
  (let [e (-> params-execution
              (assoc-in [:sources "s" :source/kind] "api")
              (assoc-in [:resolvers "ticket-by-id" :batch?] true)
              (assoc-in [:bindings "ticket-b" :binding/implementation :batch] {:parent-key :ticket/id}))
        r (execution/validate-execution vocabulary e {"api" (fn [_] {:findings []})} v2)]
    (is (finding-at? r :execution/batch-params-unsupported [[:resolver "ticket-by-id"] :params]) (pr-str (:findings r)))
    (testing "the same shape on a non-api source is not refused for this reason"
      (let [r2 (execution/validate-execution vocabulary (assoc-in e [:sources "s" :source/kind] "test") checkers v2)]
        (is (not (contains? (codes r2) :execution/batch-params-unsupported)) (pr-str (:findings r2)))))))
