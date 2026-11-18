(ns open.atlas.common.engine.semantic-compiler-test
  "`compile-candidate` used to wrap its whole body in one catch that mapped
   every exception -- three distinct authoring errors and any genuine crash --
   to `:semantic.compile/adapter-rejected` with an empty path and no message.
   Nothing in the repo drove this function directly, which is how that
   survived until a Studio user met the bare code on the Release tab
   (2026-09-15). Each refusal below has exactly one defect in its fixture, so
   the assertion does not depend on the order the compiler happens to walk
   the model in."
  (:require [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-static :as semantic-static]))

(def ^:private vocabulary
  {:entities {"t.acct" {:entity/label "Account"
                        :entity/identity [:t.acct/id]
                        :entity/attributes [:t.acct/id :t.acct/name]}}
   :attributes {:t.acct/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :t.acct/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}}})

(defn- execution [kind implementation]
  {:resolvers {"acct_by_id" {:label "Account by id" :effect :read :input [:t.acct/id]
                             :result/cardinality :one :output [:t.acct/name]}}
   :operations {}
   :sources {"src" {:source/kind kind :source/effects #{:read}}}
   :bindings {"b1" {:binding/for [:resolver "acct_by_id"] :binding/source "src" :binding/effect :read
                    :binding/implementation implementation
                    :binding/input-map {:t.acct/id "t.acct/id"}
                    :binding/output-map {:t.acct/name "t.acct/name"}}}
   :selection {[:resolver "acct_by_id"] "b1"}})

(def ^:private static-impl {:data [{:t.acct/id 1 :t.acct/name "Acme"}] :match-on :t.acct/id})
(def ^:private static-adapters {"static" (spi/semantic-adapter "static")})

;; A permissive stand-in for a connector: validation always passes and lowering
;; yields a shape `read-dialect` accepts for "sql". The defect under test lives
;; upstream of lowering in every case that uses it.
(def ^:private permissive {:validate (constantly true) :lower (fn [_] {:type "sql" :query "SELECT 1"})})
(def ^:private sql-connection {"src" {:jdbc_url "jdbc:sqlite::memory:"}})

(defn- only-finding [result]
  (is (= 1 (count (:findings result))) (pr-str (:findings result)))
  (first (:findings result)))

(deftest a-valid-static-candidate-compiles
  (let [r (compiler/compile-candidate vocabulary (execution "static" static-impl) static-adapters {})]
    (is (some? (:compiled r)) (pr-str (:findings r)))))

(deftest a-source-without-a-deployment-connection-is-named
  ;; The E2E_Trace case: the contract is fine, this Studio simply has no
  ;; connection for one of its sources. The refusal has to point at it.
  (let [f (only-finding (compiler/compile-candidate vocabulary (execution "sql" {:query "SELECT 1"})
                                                    {"sql" permissive} {}))]
    (is (= :semantic.compile/missing-connection (:code f)))
    (is (= [:sources "src"] (:path f)))
    (is (re-find #"no connection configured" (:message f)))))

(deftest a-source-kind-with-no-installed-connector-is-named
  ;; `spi/semantic-adapter` answers nil for an unknown kind and callers pass
  ;; that nil in; this used to surface as a NullPointerException in disguise.
  (let [f (only-finding (compiler/compile-candidate vocabulary (execution "sql" {:query "SELECT 1"})
                                                    {"sql" {:validate (constantly true)}} sql-connection))]
    (is (= :semantic.compile/adapter-missing (:code f)))
    (is (= [:sources "src" :source/kind] (:path f)))))

(deftest a-result-root-on-a-single-result-resolver-is-named
  (let [f (only-finding (compiler/compile-candidate vocabulary
                                                    (execution "sql" {:query "SELECT 1" :result-root :t.acct/rows})
                                                    {"sql" permissive} sql-connection))]
    (is (= :semantic.compile/result-root-mismatch (:code f)))
    (is (= [:bindings "b1" :binding/implementation :result-root] (:path f)))))

(deftest an-attribute-contradicting-its-term-is-named
  (let [vocabulary (-> vocabulary
                       ;; The term agrees with the attribute on :label on purpose: the
                       ;; compiler checks [:label :description :synonyms ...] in order and
                       ;; names the FIRST disagreement, so a fixture must have only one.
                       (assoc :terms {"acct-name" {:term/label "Name" :term/description "The account's name"
                                                    :term/synonyms ["nm"]}})
                       (assoc-in [:attributes :t.acct/name :attribute/term] "acct-name")
                       (assoc-in [:attributes :t.acct/name :attribute/synonyms] ["other"]))
        f (only-finding (compiler/compile-candidate vocabulary (execution "static" static-impl) static-adapters {}))]
    (is (= :semantic.compile/term-override (:code f)))
    (is (= [:attributes :t.acct/name :synonyms] (:path f)))))

(deftest a-genuine-crash-keeps-the-old-code-but-says-what-it-is-and-leaks-nothing
  (let [boom (fn [_] (throw (IllegalStateException. "jdbc:mysql://user:s3cret@db/prod")))
        f (only-finding (compiler/compile-candidate vocabulary (execution "sql" {:query "SELECT 1"})
                                                    {"sql" (assoc permissive :lower boom)} sql-connection))]
    (testing "the code stays, for callers that match on it"
      (is (= :semantic.compile/adapter-rejected (:code f))))
    (testing "but it now says it is a platform defect and names only the exception class"
      (is (re-find #"platform defect" (:message f)))
      (is (re-find #"IllegalStateException" (:message f)))
      (is (not (re-find #"s3cret|jdbc:" (:message f))) "an exception message may carry credentials; it must not reach the user"))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18)
;; ---------------------------------------------------------------------------

(def ^:private v2 {:contract version/semantic-v2})
(def ^:private retired-1-4 {:contract {:contract/family "atlas-semantic" :contract/version "1.4.0"}})

(defn- with-optional-name [execution]
  (-> execution
      (assoc-in [:resolvers "acct_by_id" :input] [:t.acct/id {:attribute :t.acct/name :optional? true}])
      (assoc-in [:bindings "b1" :binding/input-map] {:t.acct/id "t.acct/id" :t.acct/name "t.acct/name"})))

(deftest the-2-0-input-forms-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; 1.x schema is retired. No descriptor means «read as 2.0»; a 1.4 descriptor
  ;; is refused by name, never read flat.
  (let [compile (fn [options] (compiler/compile-candidate vocabulary (with-optional-name (execution "static" static-impl))
                                                          static-adapters {} options))]
    (is (= (:findings (compile v2)) (:findings (compile {}))))
    (let [r (compile retired-1-4)]
      (is (= [version/version-retired] (:findings r)))
      (is (nil? (:compiled r))))))

(deftest graph-contract-refuses-optional-input-even-with-permissive-adapter
  (doseq [adapters [static-adapters {"static" {:validate (constantly true) :lower semantic-static/binding-operation}}]]
    (let [r (compiler/compile-candidate vocabulary (with-optional-name (execution "static" static-impl)) adapters {} v2)]
      (is (some #(= :execution/optional-input-retired (:code %)) (:findings r)))
      (is (nil? (:compiled r)))))
  (let [b {:binding/effect :read :binding/implementation static-impl
           :binding/input-map {:t.acct/id "t.acct/id" :t.acct/lines "t.acct/lines"}
           :binding/output-map {:t.acct/name "t.acct/name"}
           :binding/input-nested {:t.acct/lines [:t.line/qty]}}]
    (is (some #(= :connector.binding/nested-input-unsupported (:code %)) (:findings (semantic-static/validate-binding b))))))

(deftest a-2-0-document-with-1x-inputs-lowers-exactly-as-before
  (let [r (compiler/compile-candidate vocabulary (execution "static" static-impl) static-adapters {} v2)]
    (is (some? (:compiled r)) (pr-str (:findings r)))))

;; Semantic contract 2.0 §3.1 (doc 109 §109.8 item 5, owner 2026-09-18): `:params`
;; ---------------------------------------------------------------------------

(def ^:private params
  {:keyword {:type :string :required? true :description "Full-text term"}
   :scope {:type :string :allowed ["standalone" "cluster"] :default "standalone"}})

(defn- with-params [execution]
  (-> execution
      (assoc-in [:resolvers "acct_by_id" :params] params)
      (assoc-in [:resolvers "acct_by_id" :filters] {:name {:attribute :t.acct/name :operator :eq}})
      (assoc-in [:bindings "b1" :binding/parameter-map] {:keyword "q" :scope "scope" :name "name"})))

(defn- param-decls [candidate]
  (get-in candidate [:compiled :engine/bundle :param-decls :t.acct/name :eql-params]))

(deftest free-params-lower-into-the-same-eql-params-map-as-filters
  (let [r (compiler/compile-candidate vocabulary (with-params (execution "static" static-impl)) static-adapters {} v2)]
    (is (some? (:compiled r)) (pr-str (:findings r)))
    (testing "a param entry carries the declaration keys and nothing bound to an attribute or operator"
      (is (= {:type :string :required? true :description "Full-text term"} (get (param-decls r) :keyword)))
      (is (= {:type :string :allowed ["standalone" "cluster"] :default "standalone"} (get (param-decls r) :scope))))
    (testing "the 1.4 filter entry next to it is byte-for-byte what 1.4 lowered"
      (is (= {:type :string :op :eq} (get (param-decls r) :name))))
    (testing "the gate refuses what the declaration does not admit, before any source is reached"
      (let [q (fn [ps] (engine/query (:compiled r) {:graph "t" :version 1}
                                     {:entity {:t.acct/id 1} :query [(list :t.acct/name ps)]}))]
        (is (= "Acme" (get-in (q {:keyword "acme"}) [:engine/data :t.acct/name])) (pr-str (q {:keyword "acme"})))
        (is (= :engine.request/missing-param (:code (q {}))))
        (is (= :engine.request/unknown-param (:code (q {:keyword "acme" :whatever 1}))))
        (is (= :engine.request/param-value-not-allowed (:code (q {:keyword "acme" :scope "mesh"}))))))))

(deftest params-are-read-without-a-descriptor-and-refused-under-a-retired-one
  (let [compile (fn [options] (compiler/compile-candidate vocabulary (with-params (execution "static" static-impl)) static-adapters {} options))]
    (is (= (:findings (compile v2)) (:findings (compile {}))))
    (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
    (let [r (compile retired-1-4)]
      (is (= [version/version-retired] (:findings r)))
      (is (nil? (:compiled r))))))

;; Semantic contract 2.0 §4 (doc 109 §109.8 item 4, owner 2026-09-18): `:priority`
;; ---------------------------------------------------------------------------

(defn- with-cached-candidate
  "A second static producer of `:t.acct/name` ranked above the first."
  [execution priority]
  (-> execution
      (assoc-in [:resolvers "acct_by_id_cache"]
                (cond-> {:label "Account by id (cache)" :effect :read :input [:t.acct/id]
                         :result/cardinality :one :output [:t.acct/name]}
                  priority (assoc :priority priority)))
      (assoc-in [:bindings "b2"] {:binding/for [:resolver "acct_by_id_cache"] :binding/source "src" :binding/effect :read
                                  :binding/implementation {:data [{:t.acct/id 1 :t.acct/name "Acme (cached)"}] :match-on :t.acct/id}
                                  :binding/input-map {:t.acct/id "t.acct/id"}
                                  :binding/output-map {:t.acct/name "t.acct/name"}})
      (assoc-in [:selection [:resolver "acct_by_id_cache"]] "b2")))

(defn- pco-priority [candidate id]
  (-> candidate :compiled :engine/bundle :env ::pci/index-resolvers
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config ::pco/priority))

(deftest priority-lowers-into-the-model-and-into-pco-priority
  (let [r (compiler/compile-candidate vocabulary (with-cached-candidate (execution "static" static-impl) 10)
                                      static-adapters {} v2)]
    (is (some? (:compiled r)) (pr-str (:findings r)))
    (is (= 10 (pco-priority r "acct_by_id_cache")))
    (is (nil? (pco-priority r "acct_by_id")) "absent stays absent -- no default is written")
    (testing "the planner answers from the preferred candidate"
      (let [a (engine/query (:compiled r) {:graph "t" :version 1} {:entity {:t.acct/id 1} :query [:t.acct/name]})]
        (is (= "Acme (cached)" (get-in a [:engine/data :t.acct/name])) (pr-str a))
        (is (nil? (:engine/issues a)) "no fallback happened, so nothing is reported")))
    (testing "the preferred candidate's declarations are the ones the gate reads"
      (is (= "acct_by_id_cache" (get-in r [:compiled :engine/bundle :param-decls :t.acct/name :resolver-id]))))))

(deftest a-tie-is-refused-before-lowering
  (let [r (compiler/compile-candidate vocabulary (with-cached-candidate (execution "static" static-impl) nil)
                                      static-adapters {} v2)]
    (is (nil? (:compiled r)))
    (is (= #{[:execution/priority-tie [[:resolver "acct_by_id"] :priority]]
             [:execution/priority-tie [[:resolver "acct_by_id_cache"] :priority]]}
           (set (map (juxt :code :path) (:findings r)))) (pr-str (:findings r)))))

(deftest priority-is-read-without-a-descriptor-and-refused-under-a-retired-one
  (let [compile (fn [options] (compiler/compile-candidate vocabulary (with-cached-candidate (execution "static" static-impl) 10)
                                                          static-adapters {} options))]
    (is (= (:findings (compile v2)) (:findings (compile {}))))
    (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
    (let [r (compile retired-1-4)]
      (is (= [version/version-retired] (:findings r)))
      (is (nil? (:compiled r))))))
