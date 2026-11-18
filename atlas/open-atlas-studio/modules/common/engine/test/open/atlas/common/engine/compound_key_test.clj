(ns open.atlas.common.engine.compound-key-test
  "doc 106 §106.14 item 14 (owner 2026-09-07): several `:identity? true`
   attributes in one namespace ARE the key — a compound identity, no new
   dialect key.

   The ruling's own proof sample, K8s-shaped: `k8s/namespace` is recognised by
   `name`; `k8s/pod` by `namespace` and `name` TOGETHER, and its `namespace`
   is at the same time the foreign key into the namespace thing. The four
   sentences it has to answer are a root collection, a by-compound-key read, a
   belongs-to into the parent, and the parent's has-many back.

   Static sources, so what is measured is the engine alone — no connector, no
   database (the same reason `static-test` uses them). The sqlite and api
   realisations of the same shape live with their connectors."
  (:require [clojure.test :refer [deftest testing is]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def ^:private namespaces
  [{:k8s.namespace/name "default" :k8s.namespace/phase "Active"}
   {:k8s.namespace/name "kube-system" :k8s.namespace/phase "Active"}])

;; The pod rows are FLAT, as every connector answers them, and `namespace`
;; does double duty: part of the pod's own key AND the value the belongs-to
;; branch reads the parent by.
(def ^:private pods
  [{:k8s.pod/namespace "default" :k8s.pod/name "web-1" :k8s.pod/phase "Running"
    :k8s.namespace/name "default"}
   {:k8s.pod/namespace "default" :k8s.pod/name "web-2" :k8s.pod/phase "Pending"
    :k8s.namespace/name "default"}
   ;; Same local name in a DIFFERENT namespace — the row a single key could
   ;; not tell apart, which is the whole reason the key is compound.
   {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1" :k8s.pod/phase "Running"
    :k8s.namespace/name "kube-system"}])

(def ^:private base
  {:namespaces {"k8s.namespace" {:label "Namespace"}
                "k8s.pod"       {:label "Pod"}}
   :attributes
   {:k8s.namespace/name  {:type :string :identity? true :format :id :label "名字"}
    :k8s.namespace/phase {:type :string :format :text :label "状态"}
    :k8s.namespace/pods  {:cardinality :one-to-many :target "k8s.pod" :label "该命名空间的 pod"}
    ;; BOTH are `:identity? true` — that is the compound key, and no other
    ;; key says so (§106.14 item 14).
    :k8s.pod/namespace   {:type :string :identity? true :format :id :label "所属命名空间"}
    :k8s.pod/name        {:type :string :identity? true :format :id :label "名字"}
    :k8s.pod/phase       {:type :string :format :text :label "状态"}
    :k8s.pod/all_pods    {:cardinality :one-to-many :target "k8s.pod" :label "所有 pod"}
    :k8s.pod/ns          {:cardinality :one-to-one :target "k8s.namespace" :label "所属命名空间"}}})

(def ^:private namespace-by-name
  {:id "k8s_namespace_by_name" :label "按名字取命名空间"
   :input [:k8s.namespace/name] :output [:k8s.namespace/phase]
   :result_type "single"
   :source {:type "static" :match-on :k8s.namespace/name :data namespaces}})

(def ^:private pod-by-key
  {:id "k8s_pod_by_namespace_and_name" :label "按命名空间与名字取 pod"
   ;; The `:input` is the WHOLE key.
   :input [:k8s.pod/namespace :k8s.pod/name]
   :output [:k8s.pod/phase {:k8s.pod/ns [:k8s.namespace/name]}]
   :result_type "single"
   ;; A VECTOR `:match-on`: every part must agree.
   :source {:type "static" :match-on [:k8s.pod/namespace :k8s.pod/name] :data pods}})

(def ^:private all-pods
  {:id "k8s_pod_all_pods" :label "所有 pod" :curated? true
   :input []
   ;; A compound-key thing's root collection hands back the WHOLE key, or
   ;; nothing can be read from the rows it answers (the cut refuses it).
   :output [{:k8s.pod/all_pods [:k8s.pod/namespace :k8s.pod/name :k8s.pod/phase]}]
   :result_type "list" :limit {:default 50 :max 200}
   :source {:type "static" :data pods}})

(def ^:private pods-of-namespace
  {:id "k8s_namespace_pods" :label "命名空间的 pod"
   :input [:k8s.namespace/name]
   :output [{:k8s.namespace/pods [:k8s.pod/namespace :k8s.pod/name :k8s.pod/phase]}]
   :result_type "list" :limit {:default 50 :max 200}
   :source {:type "static" :match-on :k8s.namespace/name :data pods}})

(def ^:private resolvers [namespace-by-name pod-by-key all-pods pods-of-namespace])

(defn- read-model [rs]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str (assoc base :resolvers rs)))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(defn- q [compiled request] (engine/query compiled nil request))

;; ---------------------------------------------------------------------------

(deftest a-compound-key-model-compiles
  (let [c (engine/compile-env (read-model resolvers))]
    (is (empty? (:engine/findings c))
        "two identity attributes and a vector :match-on are a legal model")))

(deftest the-root-collection-hands-back-the-whole-key
  (let [c (engine/compile-env (read-model resolvers))
        r (q c {:entity {} :query [{:k8s.pod/all_pods [:k8s.pod/namespace :k8s.pod/name]}]})]
    (is (= :ok (:engine/status r)))
    (is (= [{:k8s.pod/namespace "default" :k8s.pod/name "web-1"}
            {:k8s.pod/namespace "default" :k8s.pod/name "web-2"}
            {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1"}]
           (get-in r [:engine/data :k8s.pod/all_pods])))))

(deftest one-part-of-the-key-does-not-name-a-pod
  ;; The measurement the whole ruling turns on: `web-1` exists twice, and only
  ;; both parts together pick one of them.
  (let [c (engine/compile-env (read-model resolvers))]
    (testing "default/web-1"
      (let [r (q c {:entity {:k8s.pod/namespace "default" :k8s.pod/name "web-1"}
                    :query [:k8s.pod/phase]})]
        (is (= :ok (:engine/status r)))
        (is (= {:k8s.pod/phase "Running"} (:engine/data r)))))
    (testing "default/web-2 — same namespace, other name"
      (let [r (q c {:entity {:k8s.pod/namespace "default" :k8s.pod/name "web-2"}
                    :query [:k8s.pod/phase]})]
        (is (= {:k8s.pod/phase "Pending"} (:engine/data r)))))
    (testing "a pair that names no row answers nothing, not the first near miss"
      (let [r (q c {:entity {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-2"}
                    :query [:k8s.pod/phase]})]
        (is (nil? (get-in r [:engine/data :k8s.pod/phase])))))))

(deftest the-belongs-to-branch-chains-into-the-parent
  ;; The fk IS part of the key (the ruling's named edge case), and the branch
  ;; reads the parent by it in one query.
  (let [c (engine/compile-env (read-model resolvers))
        r (q c {:entity {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1"}
                :query [:k8s.pod/phase {:k8s.pod/ns [:k8s.namespace/phase]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:k8s.pod/phase "Running"
            :k8s.pod/ns {:k8s.namespace/phase "Active"}}
           (:engine/data r)))))

(deftest the-parent-has-many-pods
  (let [c (engine/compile-env (read-model resolvers))
        r (q c {:entity {:k8s.namespace/name "default"}
                :query [{:k8s.namespace/pods [:k8s.pod/name :k8s.pod/phase]}]})]
    (is (= :ok (:engine/status r)))
    (is (= [{:k8s.pod/name "web-1" :k8s.pod/phase "Running"}
            {:k8s.pod/name "web-2" :k8s.pod/phase "Pending"}]
           (get-in r [:engine/data :k8s.namespace/pods])))))

(deftest a-match-on-part-that-is-not-an-input-is-refused
  ;; Each part is held to static-v1's own test, not just the first.
  (let [bad (assoc-in pod-by-key [:source :match-on] [:k8s.pod/namespace :k8s.pod/phase])
        c   (engine/compile-env (read-model [bad]))
        fs  (:engine/findings c)]
    (is (some #(= :engine.compile/static-match-on-not-input (:code %)) fs))
    (is (some #(re-find #"k8s\.pod/phase" (str (:message %))) fs)
        "the refusal names the part that is not in :input")))

(deftest the-need-view-reads-two-things-with-vector-identities
  (let [v (dialect/need-view (read-model resolvers))]
    (is (= ["k8s.namespace" "k8s.pod"] (mapv :name (:things v))))
    (is (= {"k8s.namespace" ["k8s.namespace/name"]
            "k8s.pod" ["k8s.pod/namespace" "k8s.pod/name"]}
           (into {} (for [t (:things v)] [(:name t) (:identity t)])))
        (str "an identity is a vector, and a compound one carries both parts, in "
             "the by-key resolver's `:input` order (doc 106 §106.14 item 18)"))
    (is (every? :identity-declared? (:things v)))
    (is (empty? (:findings v)) "a compound key is not a defect")
    (is (empty? (:fragments v)))))
