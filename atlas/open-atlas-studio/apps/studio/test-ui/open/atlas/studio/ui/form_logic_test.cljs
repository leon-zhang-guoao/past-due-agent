(ns open.atlas.studio.ui.form-logic-test
  "Each deftest below pins a decision on the entry editor's write path — most
   of them a shipped bug. They run in plain node (`npm run test:ui`) — no
   browser, no component tree.

   The field-widget tests are gone with the widgets (owner ruling 2026-09-05:
   creating and editing any pool object is EDN-only). What the editor still
   decides — how an id box is read, what a save sends, what a seed starts from,
   and what the declare-attributes gate accepts — is pinned below."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.studio.ui.form-logic :as fl]))

(deftest a-removed-key-becomes-an-explicit-nil-on-merge
  (testing "the third layer: attribute update is :merge, so 'key gone from the
            form' must be said as `key nil` or the deletion is swallowed —
            save OK, revision +1, value unchanged"
    (is (= {:type :date :listed? nil}
           (fl/patch-entry {:create? false :update-kind :merge
                            :original {:type :date :listed? false}
                            :value    {:type :date}}))))
  (testing "keys present in the edited entry ride through untouched"
    (is (= {:type :string :label "L"}
           (fl/patch-entry {:create? false :update-kind :merge
                            :original {:type :string :label "old"}
                            :value    {:type :string :label "L"}}))))
  (testing "a create carries no nils — there is nothing to delete"
    (is (= {:type :date}
           (fl/patch-entry {:create? true :update-kind :merge
                            :original nil :value {:type :date}}))))
  (testing "a :replace update needs no completion — the whole entry is the message"
    (is (= {:id "r"}
           (fl/patch-entry {:create? false :update-kind :replace
                            :original {:id "r" :cache {:ttl-ms 1}}
                            :value    {:id "r"}})))))

(deftest a-resolvers-output-names-the-joins-it-needs
  "Creating a resolver whose join root is not a registered join attribute is
   refused — and until the form learned to offer the join, that refusal was a
   DEAD END: nothing in the Builder could declare one. So the form has to work
   out what is missing, and from what. (D2.29 moved the registration from a
   navigation onto an `:attributes` entry carrying `:target`; what the form has
   to work out did not change.)"
  (let [out [:a/scalar
             {:a/kids [:b/x :b/y]}
             {:a/others [:c/z]}]]
    (testing "join roots are the keys rows hang from, not the scalars"
      (is (= [:a/kids :a/others] (fl/join-roots out))))

    (testing "the target is READ from the members, not asked for — retyping it
              is how a path ends up pointing somewhere plausible and wrong"
      (is (= "b" (fl/inferred-target out :a/kids)))
      (is (= "c" (fl/inferred-target out :a/others))))

    (testing "only what the catalog lacks is offered"
      (is (= [{:join :a/others :target "c"}]
             (fl/missing-joins #{:a/kids} out)))
      (is (= [] (fl/missing-joins #{:a/kids :a/others} out))))

    (testing "an ambiguous output yields NO target — the form must ask

              Members spanning two entities cannot say where the path goes.
              Picking one would be a guess that reads as a fact."
      (is (nil? (fl/inferred-target [{:a/mixed [:b/x :c/y]}] :a/mixed)))
      (is (= [{:join :a/mixed :target nil}]
             (fl/missing-joins #{} [{:a/mixed [:b/x :c/y]}])))))

  (testing "a flat output needs no join at all"
    (is (= [] (fl/missing-joins #{} [:a/x :a/y])))))

(deftest a-derived-seed-names-the-inputs-the-expression-will-bind
  "derived-v1 binds `:input` by LOCAL NAME, so the seed has to spell the
   symbols the modeller is about to type. A skeleton full of placeholders is a
   shape to delete, not a start."
  (testing "the locals are the attributes' local names, in the picked order"
    (is (= ["entry_id" "u_version"]
           (fl/derived-locals [:cis.master.customer/entry_id
                               :cis.master.customer/u_version]))))
  (testing "anything that is not a qualified attribute contributes no symbol"
    (is (= [] (fl/derived-locals [nil "" :bare]))))

  (let [t (fl/derived-template [:cis.master.customer/entry_id
                                :cis.master.customer/u_version]
                               :cis.master.customer/audit_info)]
    (testing "the seeded expression compiles as written"
      (is (= "(str entry_id \" \" u_version)" (get-in t [:source :expr]))))
    (testing "no :source-ref — derived has no connection to name (derived-v1)"
      (is (not (contains? t :source-ref))))
    (testing "exactly one output, and `single`, because the connector refuses
              anything else at compile time"
      (is (= [:cis.master.customer/audit_info] (:output t)))
      (is (= "single" (:result_type t))))
    (testing "the source block is the closed pair the contract states"
      (is (= #{:type :expr} (set (keys (:source t)))))
      (is (= "derived" (get-in t [:source :type])))))

  (testing "with nothing picked the seed is still a legal expression, not a
            placeholder symbol the compiler would refuse"
    (let [t (fl/derived-template [] nil)]
      (is (= "(str )" (get-in t [:source :expr])))
      (is (= [] (:input t)))
      (is (= [] (:output t))))))

(deftest a-resolver-seed-carries-the-block-its-source-type-declares
  "The create form used to seed `{:type \"sql\" :query \"SELECT …\"}` whatever
   the source was. Against an api source that is not a head start: api-v1's
   operation block is closed, so `:query` is a key the writer refuses, and the
   first thing the modeller has to do is delete everything the form offered."
  (testing "an api source seeds the api-v1 operation block's own key face"
    (is (= {:type "api" :method "GET" :endpoint "/path/{ns/attr}" :field_mapping {}}
           (fl/source-template "api"))))

  (testing ":data_path is NOT seeded — canonical form does not write a default,
            and 'the body itself' is what a missing :data_path means"
    (is (not (contains? (fl/source-template "api") :data_path))))

  (testing "sql is unchanged, and so is anything this form does not know about:
            sql is what a source is unless something says otherwise, and a
            third invented shape would be worse than the wrong one"
    (is (= {:type "sql" :query "SELECT …"} (fl/source-template "sql")))
    (is (= {:type "sql" :query "SELECT …"} (fl/source-template nil)))
    (is (= {:type "sql" :query "SELECT …"} (fl/source-template "clickhouse"))))

  (let [t (fl/resolver-template "vendor-api" "api" nil nil)]
    (testing "the seed is otherwise the resolver shape it always was"
      (is (= "vendor-api" (:source-ref t)))
      (is (= "list" (:result_type t)))
      (is (= {:default 50 :max 500} (:limit t))))
    (testing "and the api block rides in :source"
      (is (= "api" (get-in t [:source :type]))))
    (testing "no hop means an unanchored list — D2.13's signature is seeded, so
              the rule is on screen while the resolver is written rather than at
              the cut that refuses it"
      (is (true? (:curated? t)))
      (is (true? (fl/curated-required? t)))))

  (testing "a dragged hop still becomes input → output as a `single`"
    (let [t (fl/resolver-template "db" "sql" "a/x" "b/y")]
      (is (= [:a/x] (:input t)))
      (is (= [:b/y] (:output t)))
      (is (= "single" (:result_type t)))
      (is (not (contains? t :curated?)) "an anchored hop signs for nothing"))))

;; ---------------------------------------------------------------------------
;; The connect card: one form state, two connectors
;; ---------------------------------------------------------------------------

(deftest a-sql-declaration-is-exactly-what-it-was
  ;; This card's sql path is shipped and in use. The api variant may not move
  ;; it by a key, which is what this test is for.
  (is (= {:id "sales-db" :type "sql" :label "Sales"
          :jdbc_url "jdbc:postgresql://h/db" :username "reader" :password "hunter2"}
         (fl/connect-request {:id "  sales-db " :type "sql" :label "Sales"
                              :jdbc_url "jdbc:postgresql://h/db"
                              :username "reader" :password "hunter2"})))
  (testing "a blank box is not a value — canonical form has no key for one"
    (is (= {:id "sales-db" :type "sql" :jdbc_url "${SALES_DB_URL}"}
           (fl/connect-request {:id "sales-db" :type "sql" :label ""
                                :jdbc_url "${SALES_DB_URL}"
                                :username "" :password "  "}))))
  (testing "and it is ready on the two keys it has always needed"
    (is (fl/connect-ready? {:id "x" :type "sql" :jdbc_url "${U}"}))
    (is (not (fl/connect-ready? {:id "x" :type "sql" :jdbc_url ""})))))

(deftest an-api-declaration-carries-only-api-keys
  ;; A `:jdbc_url` riding along would be a key api-v1 has never heard of,
  ;; refused for a reason the modeller did not choose.
  (let [form {:id "orders-api" :type "api" :label "Orders"
              :base_url "https://api.example.com" :health_endpoint "/v1/ping"
              :auth_type "none"
              ;; left behind by a type switch that did not clear them
              :jdbc_url "jdbc:postgresql://h/db" :username "reader"}]
    (is (= {:id "orders-api" :type "api" :label "Orders"
            :base_url "https://api.example.com" :health_endpoint "/v1/ping"}
           (fl/connect-request form))))
  (testing "switching type keeps the two keys that name the source, drops the rest"
    (is (= {:id "orders-api" :label "Orders" :type "api" :auth_type "none"}
           (fl/connect-switch-type {:id "orders-api" :label "Orders" :type "sql"
                                    :jdbc_url "jdbc:postgresql://h/db"
                                    :password "hunter2"}
                                   "api")))
    (is (= {:id "orders-api" :label "Orders" :type "sql"}
           (fl/connect-switch-type {:id "orders-api" :label "Orders" :type "api"
                                    :base_url "https://api.example.com"
                                    :auth_type "api_key" :value "${K}"}
                                   "sql"))))
  (testing "the SQL form can render immediately after API-specific keys are dropped"
    (let [sql-form (fl/connect-switch-type {:id "orders-api" :type "api"
                                            :base_url "https://api.example.com"}
                                           "sql")]
      (is (false? (fl/connect-text-present? sql-form :username)))
      (is (false? (fl/connect-text-present? sql-form :password)))
      (is (true? (fl/connect-text-present? (assoc sql-form :username " reader ")
                                           :username))))))

(deftest auth-none-writes-no-auth-key-at-all
  ;; `:auth {}` would be a discriminated block missing the `:type` api-v1
  ;; requires, and `:auth nil` is not canonical form. Absence is the answer.
  (let [request (fl/connect-request {:id "a" :type "api" :auth_type "none"
                                     :base_url "https://api.example.com"})]
    (is (not (contains? request :auth)))
    (is (nil? (fl/connect-auth-block {:auth_type "none"})))
    (is (nil? (fl/connect-auth-block {})))))

(deftest each-auth-variant-writes-its-own-key-face
  (testing "oauth2 — defaults shown by the controls are not written back"
    (is (= {:type "oauth2" :token_url "https://api.example.com/oauth/token"
            :client_id "${A_CLIENT_ID}" :client_secret "${A_CLIENT_SECRET}"}
           (:auth (fl/connect-request
                   {:id "a" :type "api" :base_url "https://api.example.com"
                    :auth_type "oauth2"
                    :token_url "https://api.example.com/oauth/token"
                    :client_id "${A_CLIENT_ID}" :client_secret "${A_CLIENT_SECRET}"
                    :grant_type "client_credentials" :client_auth "body"})))))
  (testing "and a value that is NOT the default is"
    (is (= {:type "oauth2" :token_url "https://t" :grant_type "password"
            :client_auth "basic"}
           (:auth (fl/connect-request
                   {:id "a" :type "api" :base_url "https://b" :auth_type "oauth2"
                    :token_url "https://t" :grant_type "password"
                    :client_auth "basic"})))))
  (testing "api_key"
    (is (= {:type "api_key" :value "${A_KEY}" :header_prefix "Bearer"}
           (:auth (fl/connect-request
                   {:id "a" :type "api" :base_url "https://b" :auth_type "api_key"
                    :value "${A_KEY}" :header_name "Authorization"
                    :header_prefix "Bearer"})))))
  (testing "no api_key variant key leaks into an oauth2 block, or the reverse"
    (let [both {:id "a" :type "api" :base_url "https://b"
                :token_url "https://t" :value "${A_KEY}"}]
      (is (= #{:type :token_url} (set (keys (fl/connect-auth-block
                                             (assoc both :auth_type "oauth2"))))))
      (is (= #{:type :value} (set (keys (fl/connect-auth-block
                                         (assoc both :auth_type "api_key")))))))))

(deftest a-variant-is-not-ready-without-the-key-that-variant-requires
  ;; The whole point of the discriminator: oauth2 without :token_url is
  ;; broken, api_key without it is normal.
  (is (fl/connect-ready? {:id "a" :type "api" :base_url "https://b" :auth_type "none"}))
  (is (not (fl/connect-ready? {:id "a" :type "api" :base_url "" :auth_type "none"})))
  (is (not (fl/connect-ready? {:id "a" :type "api" :base_url "https://b"
                               :auth_type "oauth2"})))
  (is (fl/connect-ready? {:id "a" :type "api" :base_url "https://b"
                          :auth_type "oauth2" :token_url "https://t"}))
  (is (not (fl/connect-ready? {:id "a" :type "api" :base_url "https://b"
                               :auth_type "api_key"})))
  (is (fl/connect-ready? {:id "a" :type "api" :base_url "https://b"
                          :auth_type "api_key" :value "${K}"})))

(deftest a-credential-key-is-recognised-only-as-a-whole-reference
  ;; api-v1: the starred keys take one `${VAR}`; an embedded reference is
  ;; refused there even though non-credential keys allow it.
  (is (fl/env-reference? "${SALES_API_KEY}"))
  (is (fl/env-reference? "  ${A_1}  "))
  (is (not (fl/env-reference? "svc-${ENV}-reader")))
  (is (not (fl/env-reference? "sekret")))
  (is (not (fl/env-reference? "${1BAD}")))
  (is (not (fl/env-reference? ""))))

;; ---------------------------------------------------------------------------
;; From-source wizard
;; ---------------------------------------------------------------------------

(deftest the-id-box-is-read-by-the-type-s-id-shape
  "Measured 2026-09-05: «+ attribute» sent the id as the raw string and the
   pool refused it with `:pool.action/invalid-id` — from every door."
  (testing "an attribute id is a qualified keyword, colon optional"
    (is (= {:id :nifi.instance/host} (fl/typed-id :keyword "nifi.instance/host")))
    (is (= {:id :nifi.instance/host} (fl/typed-id :keyword " :nifi.instance/host ")))
    (is (contains? (fl/typed-id :keyword "host") :error) "no entity → said, not sent"))
  (testing "a link id is two attribute keywords"
    (is (= {:id [:cis.a/x :cis.b/y]} (fl/typed-id :attr-pair "[:cis.a/x :cis.b/y]")))
    (is (contains? (fl/typed-id :attr-pair ":cis.a/x") :error)))
  (testing "string-identified types keep the trimmed text"
    (is (= {:id "nifi.instance"} (fl/typed-id :string " nifi.instance ")))
    (is (= {:id "USD"} (fl/typed-id nil "USD")))))

;; --- D2.13: signing a named query, and D2.14: declaring the output's leaves

(deftest an-unanchored-list-needs-a-signature
  (testing "D2.13's shape: \"list\" with no anchor in :input"
    (is (true? (fl/curated-required? {:result_type "list" :input []})))
    (is (true? (fl/curated-required? {:result_type "list"}))))
  (testing "an anchored list, and any single, sign for nothing"
    (is (false? (fl/curated-required? {:result_type "list" :input [:a/id]})))
    (is (false? (fl/curated-required? {:result_type "single" :input []})))))

(deftest output-leaves-are-the-scalars-not-the-branches
  (testing "bare keywords and every branch's members; the branch key is a join
            attribute and belongs to join-roots instead (D2.29)"
    (is (= [:a/id :a/name :b/x :b/y]
           (fl/output-leaves [:a/id :a/name {:a/kids [:b/x :b/y]}]))))
  (testing "a leaf named twice is one leaf"
    (is (= [:b/x] (fl/output-leaves [{:a/k [:b/x]} {:a/j [:b/x]}])))))

(deftest missing-output-attributes-are-the-undeclared-leaves
  (let [out [:a/id {:a/kids [:b/x :b/y]}]]
    (is (= [:a/id :b/y] (fl/missing-output-attributes #{:b/x} out)))
    (is (= [] (fl/missing-output-attributes #{:a/id :b/x :b/y} out)))))

(deftest a-static-source-says-what-type-a-column-is
  (let [src {:type "static" :data [{:a/id 1 :a/name "x" :a/ok true :a/rate 1.5}
                                   {:a/id 2 :a/name "y" :a/ok false :a/rate 2.0}]}]
    (is (= :integer (fl/inferred-attribute-type src :a/id)))
    (is (= :string (fl/inferred-attribute-type src :a/name)))
    (is (= :boolean (fl/inferred-attribute-type src :a/ok)))
    (is (= :decimal (fl/inferred-attribute-type src :a/rate))))
  (testing "a single map is one row"
    (is (= :string (fl/inferred-attribute-type {:type "static" :data {:a/n "x"}} :a/n))))
  (testing "rows that disagree, and a key no row has, are asked for"
    (is (nil? (fl/inferred-attribute-type
               {:type "static" :data [{:a/n "x"} {:a/n 1}]} :a/n)))
    (is (nil? (fl/inferred-attribute-type {:type "static" :data [{:a/n "x"}]} :a/other))))
  (testing "sql and api are never probed from a form"
    (is (nil? (fl/inferred-attribute-type {:type "sql" :query "SELECT 1"} :a/n)))
    (is (nil? (fl/inferred-attribute-type {:type "api" :method "GET"} :a/n)))))


(deftest the-declare-gate-opens-with-what-it-could-infer
  "The gate is an EDN block, not a table (owner ruling 2026-09-05). It opens
   pre-filled per missing leaf, `:type` read off a static source's own data and
   left nil where nothing can be inferred — nil is written on purpose, because
   an absent key and an unanswered one look the same in a map."
  (let [entry {:output [:a/name :a/id]
               :source {:type "static" :data [{:a/id 1 :a/name "x"}]}}]
    (is (= {:a/id {:type :integer} :a/name {:type :string}}
           (fl/missing-attributes-template #{} entry)))
    (testing "sorted, so two passes over one output read the same way"
      (is (= [:a/id :a/name] (keys (fl/missing-attributes-template #{} entry)))))
    (testing "a declared leaf is not asked for again"
      (is (= {:a/name {:type :string}}
             (fl/missing-attributes-template #{:a/id} entry)))))
  (testing "an sql resolver's leaves come back unanswered"
    (is (= {:a/id {:type nil}}
           (fl/missing-attributes-template
            #{} {:output [:a/id] :source {:type "sql" :query "SELECT 1"}})))))

(deftest the-declare-gate-refuses-an-entry-the-pool-would
  "An entry with neither `:type` (D2.14) nor `:target` (D2.29) is the one thing
   the pool is certain to refuse, so it is said here — before anything is sent."
  (testing "a typed leaf and a targeted join both pass, and travel verbatim"
    (is (= {:rows [{:attribute :a/id :entry {:type :integer :label "Id"}}
                   {:attribute :a/kids :entry {:target "b" :cardinality :one-to-many}}]}
           (fl/parse-attribute-declarations
            {:a/kids {:target "b" :cardinality :one-to-many}
             :a/id {:type :integer :label "Id"}}))))
  (testing "an unanswered type is refused, and the message names the attribute"
    (let [{:keys [error rows]} (fl/parse-attribute-declarations {:a/id {:type nil}})]
      (is (nil? rows))
      (is (re-find #":a/id" error))))
  (testing "a shape that is not a map of id → entry is said, not sent"
    (is (:error (fl/parse-attribute-declarations [:a/id])))
    (is (:error (fl/parse-attribute-declarations {})))
    (is (:error (fl/parse-attribute-declarations {"a/id" {:type :string}})))
    (is (:error (fl/parse-attribute-declarations {:a/id :string})))))

(deftest a-link-s-endpoints-come-from-its-id
  "The id is the pair (D2.3); typing it twice was the EDN-only editor's one
   regression (owner, 2026-09-05)."
  (is (= {:from :a/x :to :b/y :label "l"}
         (fl/complete-link-entry :link [:a/x :b/y] {:label "l"})))
  (is (= {:from :a/x :to :c/z}
         (fl/complete-link-entry :link [:a/x :b/y] {:from :a/x :to :c/z}))
      "an explicit pair is left for the pool to judge")
  (is (= {:label "l"} (fl/complete-link-entry :attribute :a/x {:label "l"}))))

(deftest a-create-seed-carries-the-keys-the-contract-requires
  "Measured 2026-09-06: the seed for every non-resolver type was label +
   description only, and an attribute saved from it is refused (D2.14)."
  (is (= :string (:type (fl/asset-template "attribute"))))
  (is (contains? (fl/asset-template "namespace") :kind))
  (is (= "list" (:result_type (fl/asset-template "resolver"))) "no hop: the curated named-query seed")
  (is (= "none" (:result_type (fl/asset-template "mutation"))))
  (is (= {:label "" :description ""} (fl/asset-template "unit"))))

;; ---------------------------------------------------------------------------
;; Request shapes
;; ---------------------------------------------------------------------------
;;
;; The seam these pin — what the UI builds and a route reads — had no test on
;; either side. The clj suite sends the right shape by hand, so it stayed green
;; while the screen sent a wrong one; the browser never runs an assertion. The
;; rule being pinned is the route's own: `removal-plan` does
;; `[(keyword type) (edn/read-string id)]`, so a type must be a string and an
;; id must be PRINTED EDN TEXT, exactly once.

(defn- as-route-reads
  "What `removal-plan` makes of a ref — `(keyword type)` and the id read back
   once. A ref is correct exactly when this is the identity the pool holds."
  [{:keys [type id]}]
  [(keyword type) (reader/read-string id)])

(deftest an-object-ref-survives-the-route-reading-it-back
  (testing "a keyword id — an attribute, the common case"
    (is (= {:type "attribute" :id ":cis.users/name"}
           (fl/object-ref :attribute :cis.users/name)))
    (is (= [:attribute :cis.users/name]
           (as-route-reads (fl/object-ref :attribute :cis.users/name)))))
  (testing "a STRING id — a source. Printed, it comes back a string; unprinted
            it came back the symbol `demo-db`, which the pool does not hold,
            and the removal reported itself done having written nothing"
    (is (= {:type "source" :id "\"demo-db\""} (fl/object-ref :source "demo-db")))
    (is (= [:source "demo-db"] (as-route-reads (fl/object-ref :source "demo-db"))))
    (is (not= [:source 'demo-db] (as-route-reads (fl/object-ref :source "demo-db")))))
  (testing "a link's endpoint pair — one field, three id shapes (D2.3)"
    (is (= [:link [:a/b :c/d]] (as-route-reads (fl/object-ref :link [:a/b :c/d])))))
  (testing "the type is a string on the wire; a keyword arrives as `:​:source`"
    (is (string? (:type (fl/object-ref :source "demo-db"))))
    (is (= "source" (:type (fl/object-ref "source" "demo-db"))))))

(deftest a-printed-ref-is-not-printed-a-second-time
  (testing "an object list carries ids already printed — sent back as they are"
    (is (= {:type "attribute" :id ":cis.users/name"}
           (fl/printed-object-ref "attribute" ":cis.users/name")))
    (is (= [:attribute :cis.users/name]
           (as-route-reads (fl/printed-object-ref "attribute" ":cis.users/name")))))
  (testing "printing an already-printed id is the same defect the other way:
            `:a/b` printed twice reads back as the STRING \":a/b\""
    (is (not= (fl/object-ref :attribute ":cis.users/name")
              (fl/printed-object-ref "attribute" ":cis.users/name"))))
  (testing "an id that was read before it got here is refused, not printed"
    (is (thrown? js/Error (fl/printed-object-ref "attribute" :cis.users/name)))
    (is (thrown? js/Error (fl/printed-object-ref "link" [:a/b :c/d])))))

(deftest a-removal-request-is-the-picked-set-body-the-route-takes
  (is (= {:objects [{:type "source" :id "\"demo-db\""}]}
         (fl/removal-request [(fl/object-ref :source "demo-db")])))
  (testing "several objects that share no namespace go in one request"
    (is (= 2 (count (:objects (fl/removal-request
                               [(fl/object-ref :attribute :a/x)
                                (fl/object-ref :attribute :b/y)]))))))
  (testing ":objects is a vector — the route reads it with `seq`, and a lazy
            sequence would still be one, but the body is EDN on the wire"
    (is (vector? (:objects (fl/removal-request
                            (map #(fl/object-ref :attribute %) [:a/x :b/y])))))))

(def ^:private namespace-key-face
  "What `GET /api/studio/schema/key-face?type=namespace` answers — derived
   server-side from the dialect's closed schema (doc 84 §8.4.25). Written out
   here because these tests are the browser half: what is under test is how a
   face is rendered, not how it is derived (that is `key_docs_test`)."
  {:type "namespace"
   :summary "what kind of thing this is and how people call it"
   :keys [{:key :description :required? false
           :meaning "one or two sentences for agents and people"
           :example ":description \"A person or company we sell to.\""}
          {:key :kind :required? false
           :enum [:bridge :event :master :reference :transaction]
           :meaning "what kind of entity this is"
           :example ":kind :master"}
          {:key :label :required? false
           :meaning "human name shown in catalogs"
           :example ":label \"Customer\""}
          {:key :notes :required? false
           :meaning "modelling notes, not shown to consumers"
           :example ":notes \"one row per party\""}
          {:key :synonyms :required? false
           :meaning "other names people use for it"
           :example ":synonyms [\"client\"]"}]})

(def ^:private attribute-key-face
  {:type "attribute"
   :summary "one column of an entity, or a branch to another entity"
   :keys [{:key :target :required? true :variants ["join"]
           :meaning "the entity a join branch reaches"
           :example ":target \"cis.order\""}
          {:key :label :required? false
           :meaning "human name shown in catalogs"
           :example ":label \"Total cost\""}
          {:key :type :required? true :variants ["scalar"]
           :enum [:boolean :date :datetime :decimal :integer :json :string]
           :meaning "the value this leaf carries"
           :example ":type :string"}]})

(defn- noted-text
  "What the editor's `seed-text` puts in the box for one entry: the type's note
   lines, then the entry as EDN. `app.cljs` builds it through
   `editor/format-edn` (CodeMirror, browser-only), so these tests compose the
   same two parts with `pr-str` — what is under test is the note lines and what
   survives a read back, neither of which pretty-printing touches."
  ([type entry] (noted-text type entry nil))
  ([type entry key-face]
   (let [notes (fl/key-face-notes type key-face)]
     (cond->> (pr-str entry)
       (seq notes) (str (str/join "\n" notes) "\n")))))

(deftest an-edited-entry-is-shown-with-the-same-notes-as-a-fresh-one
  (testing "an `add_thing` namespace is a literal `{}` — without the notes the
            box says nothing about :kind (owner ruling 2026-09-05: the box IS
            the entry, so a hint beside it is not a substitute)"
    (let [text (noted-text :namespace {})]
      (is (str/starts-with?
           text ";; :kind is one of :master :transaction :reference :event :bridge"))
      (is (str/ends-with? text "{}"))))
  (testing "an attribute from the write face carries only :type (D2.14), and
            both leaf and join lines are shown above it"
    (let [text (noted-text :attribute {:type :string})]
      (is (= 2 (count (filter #(str/starts-with? % ";;") (str/split-lines text)))))
      (is (str/includes? text ":target"))))
  (testing "the notes are per type, not per path: what create shows for a type
            is what edit shows for it"
    (doseq [t [:attribute :namespace :link :metric :mutation]]
      (is (seq (get fl/asset-seed-notes (name t)))))))

(deftest the-notes-are-comments-so-not-one-byte-of-them-reaches-the-pool
  (testing "reading the box back gives the entry unchanged — the red line"
    (doseq [entry [{} {:type :string} {:kind :master :label "Users"}]]
      (is (= entry (reader/read-string (noted-text :namespace entry))))
      (is (= entry (reader/read-string (noted-text :attribute entry))))))
  (testing "and no template default rides along: an absent key stays absent
            (D2.12 canonical form) — `:label \"\"` in the pool would fake
            'someone described this as empty'"
    (let [read-back (reader/read-string (noted-text :namespace {}))]
      (is (= {} read-back))
      (is (not (contains? read-back :label)))
      (is (not (contains? read-back :description)))))
  (testing "saving an untouched box sends an empty patch — nothing to write"
    (let [original {:type :string :label "Name"}
          value    (reader/read-string (noted-text :attribute original))]
      (is (= original value))
      (is (= original (fl/patch-entry {:create? false :update-kind :merge
                                       :original original :value value}))))))

(deftest the-notes-say-every-key-the-type-may-carry-with-a-literal-example
  (testing "a namespace `{}` used to show one line about :kind; the key face
            names every key with the bytes that write it (owner 2026-09-07,
            doc 84 §8.4.25)"
    (let [lines (fl/key-face-notes :namespace namespace-key-face)
          text  (str/join "\n" lines)]
      (is (str/starts-with? (first lines)
                            ";; namespace — what kind of thing this is"))
      (doseq [k [":label" ":description" ":kind" ":synonyms" ":notes"]]
        (is (some #(and (str/includes? % (str " " k " ")) (str/includes? % "e.g."))
                  lines)
            (str k " has a line with a literal example")))
      (testing "the vocabulary is listed rather than described"
        (is (str/includes? text ":master")))
      (testing "and the closing line says what an absent key means"
        (is (str/includes? (last lines) "optional unless marked required")))
      (testing "every line is an EDN comment — that is the whole red line"
        (is (every? #(str/starts-with? % ";;") lines)))))
  (testing "required keys and the variant that declares them are marked, since
            a scalar and a join are disjoint key sets (D2.14 / D2.29)"
    (let [text (str/join "\n" (fl/key-face-notes :attribute attribute-key-face))]
      (is (str/includes? text "required — [join] the entity a join branch reaches"))
      (is (str/includes? text "required — [scalar] the value this leaf carries"))))
  (testing "no key face (route unreachable, or an older server) → the short
            notes, so the box never opens on nothing"
    (is (= (vec (get fl/asset-seed-notes "namespace"))
           (fl/key-face-notes :namespace nil)))
    (is (= [] (fl/key-face-notes :unit nil)))))

(deftest the-derived-notes-are-comments-for-every-type-s-template
  (doseq [t ["attribute" "namespace" "resolver" "link" "metric" "mutation"
             "source" "term" "unit"]
          :let [template (fl/asset-template t)
                face (case t
                       "namespace" namespace-key-face
                       "attribute" attribute-key-face
                       nil)]]
    (is (= template (reader/read-string (noted-text (keyword t) template face)))
        (str t ": the notes are dropped by the reader, the entry is untouched"))))

(deftest editing-one-key-in-the-noted-box-patches-only-that-key
  (let [original {:type :string}
        ;; what an author leaves behind after typing a :label under the notes
        value    (reader/read-string (noted-text :attribute {:type :string :label "Name"}))
        patch    (fl/patch-entry {:create? false :update-kind :merge
                                  :original original :value value})]
    (is (= {:type :string :label "Name"} patch))
    (testing "the note lines are not keys, so they never appear as nils in the
              delete-detecting diff"
      (is (every? keyword? (keys patch)))
      (is (every? some? (vals patch))))))

(deftest duplicate-namespace-handles-graph-names-outside-asset-syntax
  (is (= "orders-sandbox" (fl/duplicate-namespace " orders-sandbox ")))
  (is (= "Sales.orders_v2" (fl/duplicate-namespace "Sales.orders_v2")))
  (is (= "graph-2026-orders" (fl/duplicate-namespace "2026 orders")))
  (is (= "" (fl/duplicate-namespace "  ")))
  (doseq [value ["订单" "a/b" "_orders" ".orders" "orders sandbox"]]
    (is (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" (fl/duplicate-namespace value)))))

(deftest studio-email-validation-accepts-normal-addresses
  (is (fl/valid-user-email? "jerry.duan@tdsynnex.com"))
  (is (fl/valid-user-email? " member+test@example.com "))
  (is (not (fl/valid-user-email? "jerry.duan.tdsynnex.com")))
  (is (not (fl/valid-user-email? "member@")))
  (is (not (fl/valid-user-email? "member name@example.com"))))
