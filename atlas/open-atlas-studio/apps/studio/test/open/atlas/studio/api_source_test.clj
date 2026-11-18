(ns open.atlas.studio.api-source-test
  "**The api source's modelling path** (8.4.22 裁定十六).

   Four exit conditions, in the ruling's own order:

   1. a WRITE key probes — the client declares no capability at all, because a
      standard MCP client does not — and a READ-ONLY key is refused before any
      request leaves the process (the stub counts its hits, so «refused» is
      measured and not asserted);
   2. `data_path` plus a nested `{name, path}` output produce a resolver the
      engine actually runs: `execute_eql` on the draft answers with the nested
      value in it;
   3. the Builder's own route writes the SAME entry, byte for byte, as the MCP
      call — one planner, two doors;
   4. the probe's audit record carries counts, a path and a status, and no
      part of the response body.

   The upstream is a local `com.sun.net.httpserver` stub rather than a fixture:
   what is under test is a real HTTP round trip through connector-api, and a
   canned sample would test the planner twice instead."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.mcp-transport :as wire]
            [open.atlas.common.connector.api :as connector-api]
            [open.atlas.common.connector.api.test-env :as test-env]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.studio.auth :as auth]
            [open.atlas.studio.builder-mcp :as builder-mcp]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.credentials :as credentials]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.workspace-token :as wt]
            [taoensso.timbre :as timbre])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; The upstream
;; ---------------------------------------------------------------------------

(def ^:private body
  "One record, one nested object: the smallest shape that needs BOTH halves of
   裁定十六 ② — a data path to find the records and a JSONPath to reach a value
   no top-level key carries."
  (json/generate-string {:data {:items [{:id 1 :name "a" :address {:city "X"}}]}}))

(def ^:dynamic *hits* nil)
(def ^:dynamic *base-url* nil)

(def ^:private catalog-records 500)
(def ^:private catalog-pad 1024)

(defn- catalog-chunks
  "A `{\"products\":[…]}` feed of 500 records — a delta-feed endpoint that takes
   no page parameter, which is the case 8.4.23 裁定十七 was ruled for.

   `note` is present in 30% of the records and NULL in a third of those: the
   optional-and-nullable field that a sample of one could never have shown
   (补裁 2026-09-04)."
  []
  (concat ["{\"products\":["]
          (for [i (range catalog-records)]
            (str (when (pos? i) ",")
                 "{\"id\":" i ",\"name\":\"item-" i "\""
                 (when (< (mod i 10) 3)
                   (if (zero? (mod i 10)) ",\"note\":null" (str ",\"note\":\"n" i "\"")))
                 ",\"blurb\":\"" (apply str (repeat catalog-pad \x)) "\"}"))
          ["]}"]))

(def ^:dynamic *catalog-written* nil)

(defn- with-upstream [f]
  (let [hits (atom 0)
        written (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (swap! hits inc)
                        (if (= "/catalog" (.getPath (.getRequestURI exchange)))
                          ;; Streamed with no content length: the probe is meant
                          ;; to walk away partway through (8.4.23 裁定十七), and
                          ;; `written` measures how far the server got.
                          (do (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                              (.sendResponseHeaders exchange 200 0)
                              (try
                                (with-open [out (.getResponseBody exchange)]
                                  (doseq [^String c (catalog-chunks)]
                                    (let [bs (.getBytes c "UTF-8")]
                                      (.write out bs)
                                      (.flush out)
                                      (swap! written + (alength bs)))))
                                (catch java.io.IOException _ nil)))
                          (let [bytes (.getBytes ^String body "UTF-8")]
                            (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                            (.sendResponseHeaders exchange 200 (alength bytes))
                            (with-open [out (.getResponseBody exchange)] (.write out bytes)))))))
    (.start server)
    (try
      (binding [*hits* hits
                *catalog-written* written
                *base-url* (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
        (f))
      (finally (.stop server 0)))))

;; ---------------------------------------------------------------------------
;; The Studio
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)
(def ^:dynamic *home* nil)
(def ^:dynamic *owner* nil)
(def ^:dynamic *user* nil)
(def ^:private pepper "test-workspace-pepper")

(defn- with-studio [f]
  (let [cfg  (pool/config {:backend :mem :id (str (random-uuid))})
        conn (-> (pool/open-pool! cfg) graph/install! identity/install!)
        home (str (System/getProperty "java.io.tmpdir") "/oa-api-" (random-uuid))]
    (try
      (let [admin (identity/bootstrap-admin!
                   conn {:email "owner@example.com" :name "Owner"
                         :password "a-safe-password" :workspace "demo"})]
        (let [r (pool/execute-actions
                 conn
                 [{:workspace "demo" :action :create :type :namespace :id "cis.users"
                   :entry {:kind :master :label "Users"} :expected_revision 0}
                  {:workspace "demo" :action :create :type :source :id "users-api"
                   :entry {:type "api" :label "Users" :base_url *base-url*}
                   :expected_revision 0}]
                 {:entrypoint :ui :actor (:principal (:user admin))
                  :at "2026-08-28T00:00:00Z"})]
          (assert (empty? (:findings r)) (pr-str (:findings r))))
        (graph/register-graph! conn {:workspace "demo" :name "users"
                                     :created-at "2026-08-28T00:00:00Z"
                                     :members {:namespaces #{"cis.users"}}})
        (reset! (var-get #'open.atlas.studio.builder-server/studio-state) {:conn conn})
        (binding [*conn* conn *home* home
                  *owner* (:principal (:user admin))
                  *user* (:user admin)]
          (f)))
      (finally
        (reset! (var-get #'open.atlas.studio.builder-server/studio-state) nil)
        (pool/close-pool! conn)
        (pool/delete-pool! cfg)
        (doseq [f* (reverse (file-seq (java.io.File. home)))] (.delete ^java.io.File f*))))))

(use-fixtures :each with-upstream with-studio)

;; ---------------------------------------------------------------------------
;; The two doors
;; ---------------------------------------------------------------------------

(defn- deps []
  (builder-mcp/deps
   {:home *home* :pepper pepper :conn *conn*
    :graph-service (var-get #'open.atlas.studio.builder-server/graph-service)
    :draft-service (var-get #'open.atlas.studio.builder-server/draft-service)
    :draft-runtime (var-get #'open.atlas.studio.builder-server/draft-runtime)
    ;; Wired exactly as `mcp-deps` wires them (8.4.14 裁定七 / 8.4.22 裁定十六 ②).
    :probe-source (fn [cfg request] (connector-api/probe cfg request))
    :locate-rows connector-api/locate-rows
    :declarations (tools/declarations mcp/mountable-declarations)
    :registry #(tools/registry % mcp/mountable-declarations)
    :tools-list tools/tools-list
    :call (var-get #'open.atlas.studio.builder-server/audited-tool-call)
    :default-graph (fn [workspace] (graph/default-graph (pool/db *conn*) workspace))
    :resolve-source (fn [source-id entry]
                      (credentials/resolve-config *home* source-id entry))
    ;; 8.4.24 裁定十八, wired exactly as `mcp-deps` wires it: the tool calls
    ;; the host's one source-write path and validates nothing of its own.
    :declare-source (fn [caller]
                      (fn [request]
                        ((var-get #'open.atlas.studio.builder-server/declare-source!)
                         (assoc request :workspace (:workspace caller)))))
    :now (constantly "2026-08-28T00:00:00Z")}))

(defn- issue! [scope]
  (wt/issue! *home* pepper {:workspace "demo" :scope scope :issued-by *owner*
                            :issued-at "2026-08-28T00:00:00Z"
                            :expires-at "2026-09-28T00:00:00Z"
                            :label "api-source-test"}))

(defn- call! [token tool args]
  (let [payload (json/generate-string {:jsonrpc "2.0" :method "tools/call" :id 1
                                       :params {:name tool :arguments args}})
        r ((wire/routes (deps))
           {:request-method :post :uri "/mcp"
            :headers {"host" "127.0.0.1:9631"
                      "authorization" (str "Bearer " token)}
            :body (java.io.ByteArrayInputStream. (.getBytes ^String payload "UTF-8"))})
        ;; The transport hands a tool result over as JSON text
        ;; (`mcp-transport` impl/jsonrpc `tool-content`), so this reads it back
        ;; the way a client does — keywords where the writer wrote keywords,
        ;; strings where EDN types crossed the wire.
        ;; A refusal comes back as PRINTED EDN inside an `{:error …}` envelope
        ;; (`builder-mcp/refusal->error`), a success as JSON — so both spellings
        ;; are read, exactly as a client would have to.
        text (get-in (json/parse-string (:body r) true) [:result :content 0 :text])
        v (try (json/parse-string text true)
               (catch Exception _ (edn/read-string text)))]
    (or (:error v) v)))

(defn- over-the-wire
  "An EDN value as the MCP transport would deliver it. The two doors are
   compared THROUGH this, because one of them answers JSON and the other
   answers EDN in-process — what must match is the entry, not the encoding."
  [v]
  (json/parse-string (json/generate-string v) true))

(defn- as-editor [f]
  (binding [auth/*request-user* (assoc *user* :default-workspace "demo")] (f)))

(def ^:private resolver-args
  "«There is a thing called users, and it comes from /users» — 8.4.25 裁定十七's
   `add_thing`, the sentence this wizard step says."
  {:graph "demo/users" :name "users" :source "users-api"
   :namespace "cis.users" :from "/users"
   :data_path "$.data.items"
   :attributes ["id" "name" {:name "city" :path "$.address.city"}]})

(defn- resolver-entry [plan]
  (:entry (first (filter #(#{:resolver "resolver"} (:type %)) (:actions plan)))))

;; ---------------------------------------------------------------------------
;; 1. The gate is the write key, and a refusal costs no request
;; ---------------------------------------------------------------------------

(deftest a-write-key-probes-and-a-read-key-is-refused-before-the-request
  (testing "a read-only key: refused, and the upstream is never called"
    (let [{:keys [token]} (issue! #{:read})
          r (call! token "add_thing" resolver-args)]
      (is (= #{:tools.intent/probe-required} (set (map :code (:findings r))))
          (pr-str r))
      (is (zero? @*hits*) "a refusal must cost no HTTP request at all")))
  (testing "a write key declaring NO capability gets a plan (8.4.22 裁定十六 ①)"
    (let [{:keys [token]} (issue! #{:read :write})
          r (call! token "add_thing" resolver-args)]
      (is (empty? (filter #(= "error" (:severity %)) (:findings r))) (pr-str (:findings r)))
      (is (= 1 @*hits*) "one plan, one call")
      (is (some? (resolver-entry r))))))

;; ---------------------------------------------------------------------------
;; 2. data_path + a nested output produce a resolver the engine runs
;; ---------------------------------------------------------------------------

(deftest a-planned-api-resolver-answers-on-the-draft
  (let [{:keys [token]} (issue! #{:read :write})
        applied (call! token "add_thing" (assoc resolver-args :apply true))
        entry (resolver-entry applied)]
    (is (true? (:written? applied)) (pr-str (:findings applied)))
    (testing "the entry is an api operation block, mapped by JSONPath"
      (is (= "api" (get-in entry [:source :type])))
      (is (= "$.data.items" (get-in entry [:source :data_path])))
      (is (= "$.address.city" (get-in entry [:source :field_mapping :cis.users/city]))))
    (testing "and the draft answers with the nested value in the row"
      (let [q (call! token "execute_eql"
                     {:graph "demo/users"
                      :query "[{:cis.users/all_users [:cis.users/id :cis.users/name :cis.users/city]}]"})]
        (is (empty? (filter #(= "error" (:severity %)) (:findings q))) (pr-str (:findings q)))
        (is (str/includes? (pr-str (:result q)) "\"X\"")
            (str "the nested value reached the row: " (pr-str (:result q))))))))

;; ---------------------------------------------------------------------------
;; 3. One planner, two doors
;; ---------------------------------------------------------------------------

(deftest the-builder-route-plans-the-same-entry-as-the-mcp-call
  (let [{:keys [token]} (issue! #{:read :write})
        mcp-plan (call! token "add_thing" resolver-args)
        gui-plan (as-editor
                  #((var-get #'open.atlas.studio.builder-server/api-resolver!)
                    {:graph "demo/users" :relation "users" :source "users-api"
                     :path "/users" :data_path "$.data.items"
                     :namespace "cis.users"
                     :outputs ["id" "name" {:name "city" :path "$.address.city"}]}))]
    (is (= (resolver-entry mcp-plan) (over-the-wire (resolver-entry gui-plan)))
        "8.4.22 裁定十六 ③: the wizard writes through plan-add-resolver, not a second generator")))

;; ---------------------------------------------------------------------------
;; 4. The probe route's audit record, and what it must not carry
;; ---------------------------------------------------------------------------

(deftest the-probe-route-answers-the-tree-and-audits-without-the-body
  (let [r (as-editor
           #((var-get #'open.atlas.studio.builder-server/api-probe!)
             {:source "users-api" :path "/users"}))]
    (is (= 1 @*hits*) "one call, one page (8.4.14 裁定七)")
    (testing "step ① shows the response tree and pre-selects the records"
      (is (= "$.data.items" (:data-path r)))
      (is (= 1 (count (get-in r [:sample :data :items])))))
    (testing "the audit record is the MCP path's, and carries no response body"
      (let [a (:probe r)]
        (is (= "users-api" (:probe/source a)))
        (is (= "/users" (:probe/path a)))
        (is (= 200 (:probe/status a)))
        (is (= 3 (:probe/fields a)))
        (is (= "$.data.items" (:probe/data-path a)))
        (is (not (str/includes? (pr-str a) "address")))
        (is (not (str/includes? (pr-str a) "\"X\"")))))))

;; ---------------------------------------------------------------------------
;; 5. The streaming probe (8.4.23 裁定十七)
;; ---------------------------------------------------------------------------

(deftest a-feed-too-big-to-read-is-modelled-from-its-sample
  ;; The立项 case: a catalog endpoint answers with the whole feed and honours
  ;; no page parameter. Before 裁定十七 this was refused outright; now the probe
  ;; reads to the first record and the resolver built from it runs.
  (let [{:keys [token]} (issue! #{:read :write})
        ;; No `:outputs`: the sample's UNION is what gets modelled, which is
        ;; the point of sampling a hundred records (补裁 2026-09-04).
        args {:graph "demo/users" :name "catalog" :source "users-api"
              :namespace "cis.users" :from "/catalog"
              :data_path "$.products"}
        plan (call! token "add_thing" args)
        applied (call! token "add_thing" (assoc args :apply true))]
    (is (empty? (filter #(= "error" (:severity %)) (:findings plan))) (pr-str (:findings plan)))
    (is (true? (:written? applied)) (pr-str (:findings applied)))
    (testing "the audit says the read stopped early, how much it read and how many records"
      (let [a (:probe plan)]
        (is (true? (:probe/truncated? a)))
        ;; The whole feed is fewer records than the default sample, so the
        ;; array ends first and all 500 come back (补裁 2026-09-04).
        (is (= catalog-records (:probe/records a)))
        (is (<= (:probe/bytes-read a) connector-api/probe-max-bytes))
        (is (not (str/includes? (pr-str a) "item-0")) "no response content in the audit")))
    (testing "an OPTIONAL field only 30% of the records carried is in the model"
      (is (some #(and (= "note" (second %)) (= "type" (nth % 2))) (:fill plan))
          (pr-str (:fill plan)))
      (is (some #(= "cis.users/note" (str (:id %))) (:actions applied))
          "a sample of one record would have missed it entirely"))
    (testing "and the resolver answers on the draft"
      (let [q (call! token "execute_eql"
                     {:graph "demo/users"
                      :query "[{:cis.users/all_catalog [:cis.users/id :cis.users/name]}]"})]
        (is (empty? (filter #(= "error" (:severity %)) (:findings q))) (pr-str (:findings q)))
        (is (str/includes? (pr-str (:result q)) "item-0") (pr-str (:result q)))))))

(deftest the-probe-route-hands-the-wizard-the-records-it-read
  ;; Step ① of the wizard on the same endpoint. It gives no `data_path` — that
  ;; is what step ② asks — so it reads up to the ceiling (裁定十七's no-path
  ;; branch); this feed fits under the 8 MiB ceiling, so it is read whole and
  ;; the reply is the tree itself, exactly as before 裁定十七.
  (let [r (as-editor
           #((var-get #'open.atlas.studio.builder-server/api-probe!)
             {:source "users-api" :path "/catalog"}))
        records (get-in r [:sample :products])]
    (is (not (:truncated? r)) "read whole: nothing was cut off")
    (is (= "$.products" (:data-path r)) "the path the heuristic pre-selects")
    (is (= catalog-records (count records)))
    (is (= 0 (:id (first records))) "the records, in the order they arrived")
    (testing "the optional field is in what the field table counts over"
      (is (= 150 (count (filter #(contains? % :note) records))) "seen in 150 of 500")
      (is (= 50 (count (filter #(and (contains? % :note) (nil? (:note %))) records)))
          "and null in some of those"))
    (is (= (:bytes r) (:bytes-read r)) "read whole, so the answer's size is known")
    (is (not (str/includes? (pr-str (:probe r)) "item-0")))))

(deftest a-sample-size-smaller-than-the-feed-reads-exactly-that-many
  ;; 500 records upstream, 100 asked for: the probe stops at 100 (补裁
  ;; 2026-09-04 — `sample_size` bounds the records, the ceiling the bytes).
  (let [{:keys [token]} (issue! #{:read :write})
        plan (call! token "add_thing" {:name "catalog" :from "/catalog" :namespace "cis.users" :graph "demo/users" :source "users-api" :data_path "$.products" :sample_size 100})]
    (is (empty? (filter #(= "error" (:severity %)) (:findings plan))) (pr-str (:findings plan)))
    (is (= 100 (:probe/records (:probe plan))))
    (is (true? (:probe/truncated? (:probe plan))))))

;; ---------------------------------------------------------------------------
;; 6. declare_source (8.4.24 裁定十八)
;;
;; The ruling's exit conditions, in its own order. They live here rather than
;; in `builder_server_test` because every one of them turns on a REAL health
;; check against a real upstream: "prove the connection, then write" is the
;; whole shape of the tool, and a stubbed connector would test the plumbing
;; twice and the ruling not at all.
;; ---------------------------------------------------------------------------

(defn- codes*
  "The finding codes of a reply, as keywords.

   A success comes back as JSON and a refusal as printed EDN (see `call!`), so
   a code is a string on one path and a keyword on the other. Normalised here
   rather than per assertion: what a test is about is WHICH refusal, never
   which encoding carried it."
  [r]
  (into #{} (map #(keyword (str/replace (str (:code %)) #"^:" ""))) (:findings r)))

(defn- errors*
  [r]
  (filter #(#{"error" :error} (:severity %)) (:findings r)))

(defn- audit-events
  "Every audit event `f` produced, oldest first, anchored in a temp dir so this
   instance's trail does not land in the working directory. Same helper as
   `builder_mcp_test`'s, for the same reason."
  [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "studio-source-audit-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (obs/audit-configure! {:recent 50 :dir dir})
      (obs/audit-clear!)
      (f)
      (vec (reverse (obs/audit-recent)))
      (finally
        (obs/audit-configure! {:recent 50 :dir nil})
        (obs/audit-clear!)
        (doseq [f* (reverse (file-seq (java.io.File. dir)))] (.delete ^java.io.File f*))))))

(def ^:private api-token-var "DECLARED_API_TOKEN")

(defn- with-token-env [f]
  ;; Both seams, because doc 106 §106.14-12 gave Studio one of its own: the
  ;; credential store resolves every `${VAR}` before the connector is called,
  ;; so an environment faked only inside connector-api would leave Studio
  ;; refusing with `:credential/missing` before anything dialled.
  (with-redefs [credentials/process-env {api-token-var "s3cr3t-token-value"}]
    (test-env/with-env {api-token-var "s3cr3t-token-value"} (f))))

(defn- api-key-entry
  "The api_key variant of the 立项 case, `${VAR}` and all.

   `:ssl_insecure?` is here deliberately: it is one of the keys the switch to
   `:tls` must make GO AWAY, and a key that was never set could not show that."
  []
  {:type "api"
   :label "Declared users api"
   :base_url *base-url*
   :ssl_insecure? true
   :auth {:type "api_key"
          :value (str "${" api-token-var "}")
          :header_name "X-Api-Key"}})

(defn- pool-source [id]
  (get-in (pool/as-dialect (pool/db *conn*)) [:sources id]))

(defn- source-revision [id]
  (:pool/revision (pool/object (pool/db *conn*) :source id)))

(defn- empty-keystore!
  "A real PKCS12 file, so the `:tls` block passes the connector's own
   validation (`conn/tls-config-problems` resolves the path and the password
   at validate time). Empty because nothing here presents a client
   certificate: what is under test is which KEYS survive a declaration."
  [pass]
  (let [f (java.io.File. (str *home* "/client.p12"))
        ks (java.security.KeyStore/getInstance "PKCS12")]
    (.mkdirs (.getParentFile f))
    (.load ks nil (.toCharArray ^String pass))
    (with-open [out (java.io.FileOutputStream. f)]
      (.store ks out (.toCharArray ^String pass)))
    (.getAbsolutePath f)))

(deftest declare-source-creates-an-api-source-a-resolver-then-answers-through
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            entry (api-key-entry)
            plan (call! token "declare_source" {:id "declared-api" :entry (pr-str entry)})]
        (testing "the plan proves the connection and writes nothing"
          (is (empty? (errors* plan)) (pr-str plan))
          (is (false? (:written? plan)))
          (is (true? (:connected? plan)))
          (is (#{"ok" :ok} (get-in plan [:health :status])))
          (is (#{"create" :create} (get-in plan [:action :action]))
              "an omitted revision is a create")
          (is (nil? (pool-source "declared-api")) "a plan writes nothing")
          ;; doc 106 §106.14-12 item 5: the plan says which variables this
          ;; entry needs and which this instance can answer. 8.4.24 裁定十八
          ;; still stands — `declare_source` writes NO credential.
          (is (= [{:name api-token-var :path [":auth" ":value"]
                   :stored? false :in-env? true :present? true}]
                 (mapv #(into {} (map (fn [[k v]] [(keyword (name k)) v]) %))
                       (get-in plan [:requires :vars])))
              (pr-str (:requires plan)))
          (is (not (str/includes? (pr-str (:requires plan)) "s3cr3t-token-value"))
              "names, never values"))
        (let [applied (call! token "declare_source"
                             {:id "declared-api" :entry (pr-str entry) :apply true})]
          (is (true? (:written? applied)) (pr-str applied))
          (is (= 1 (:revision applied))))
        (testing "read_object hands the entry back byte for byte, ${VAR} and all"
          (let [r (call! token "read_object" {:type "source" :id "declared-api"})
                read-back (:pool/entry r)]
            (is (= (over-the-wire entry) read-back)
                (str "what was declared is what is registered: " (pr-str read-back)))
            (is (str/includes? (pr-str read-back) (str "${" api-token-var "}")))
            (is (not (str/includes? (pr-str r) "s3cr3t-token-value"))
                "the resolved value never crosses MCP (8.4.18 裁定十一)")))
        (testing "and a resolver over it answers on the draft"
          (let [r (call! token "add_thing" {:name "declared_users" :from "/users" :attributes ["id" "name"] :namespace "cis.users" :graph "demo/users" :source "declared-api" :data_path "$.data.items" :apply true})]
            (is (true? (:written? r)) (pr-str (:findings r))))
          (let [q (call! token "execute_eql"
                         {:graph "demo/users"
                          :query (str "[{:cis.users/all_declared_users "
                                      "[:cis.users/id :cis.users/name]}]")})]
            (is (empty? (filter #(= "error" (:severity %)) (:findings q))) (pr-str (:findings q)))
            (is (str/includes? (pr-str (:result q)) "\"a\"") (pr-str (:result q)))))))))

(deftest declaring-the-same-source-again-replaces-it-whole
  ;; **The直接反例 of 立项证据 2**: a merging write could never take `:auth` and
  ;; `:ssl_insecure?` off a source, so it could never move one onto
  ;; certificate authentication at all.
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            pass "keystore-pass"
            _ (call! token "declare_source"
                     {:id "declared-api" :entry (pr-str (api-key-entry)) :apply true})
            switched {:type "api"
                      :label "Declared users api"
                      :base_url *base-url*
                      :tls {:keystore (empty-keystore! pass) :keystore_pass pass}}
            r (call! token "declare_source"
                     {:id "declared-api" :entry (pr-str switched)
                      :expected_revision (source-revision "declared-api")
                      :apply true})]
        (is (true? (:written? r)) (pr-str r))
        (is (#{"ok" :ok} (get-in r [:health :status])))
        (let [read-back (:pool/entry (call! token "read_object"
                                            {:type "source" :id "declared-api"}))]
          (is (= (over-the-wire switched) read-back))
          (is (not (contains? read-back :auth)) "the api_key block is gone, not merged")
          (is (not (contains? read-back :ssl_insecure?))
              ":ssl_insecure? is gone — it is what makes :tls declarable at all"))))))

(deftest a-stale-expected-revision-is-refused-and-the-pool-does-not-move
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            _ (call! token "declare_source"
                     {:id "declared-api" :entry (pr-str (api-key-entry)) :apply true})
            current (source-revision "declared-api")
            r (call! token "declare_source"
                     {:id "declared-api"
                      :entry (pr-str (assoc (api-key-entry) :label "Renamed"))
                      :expected_revision (dec current)
                      :apply true})]
        (is (not (true? (:written? r))))
        (is (contains? (codes* r) :pool.conflict/revision-mismatch) (pr-str (:findings r)))
        (is (= current (source-revision "declared-api")) "nothing moved")
        (is (= "Declared users api" (:label (pool-source "declared-api"))))))))

(deftest a-literal-credential-is-refused-before-any-connection-is-opened
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            before @*hits*
            r (call! token "declare_source"
                     {:id "leaky-api" :apply true
                      :entry (pr-str (assoc-in (api-key-entry) [:auth :value] "literal-token"))})]
        (is (not (true? (:written? r))))
        (is (contains? (codes* r) :connector.source/literal-credential) (pr-str (:findings r)))
        (is (nil? (:health r)) "no health check was run on an entry we refuse to write")
        (is (= before @*hits*) "and no request left the process")
        (is (nil? (pool-source "leaky-api")))))))

(deftest verify-false-registers-an-unreachable-source-and-says-so
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            ;; A `${VAR}` nothing defines: the health check refuses before any
            ;; socket, and the plan names the VARIABLE, never a value.
            entry {:type "api" :label "Not yet" :base_url "${DECLARED_MISSING_HOST}"}
            refused (call! token "declare_source"
                           {:id "later-api" :entry (pr-str entry) :apply true})]
        (testing "verify defaults to true: nothing is written"
          (is (not (true? (:written? refused))))
          (is (#{"down" :down} (get-in refused [:health :status])))
          (is (= ["DECLARED_MISSING_HOST"] (get-in refused [:health :missing-vars])))
          ;; doc 106 §106.14-12 unified the code: an unanswered `${VAR}` is
          ;; `:credential/missing` wherever it is found, sql or api, so the
          ;; refusal reads the same on the card, in a plan and at install.
          (is (contains? (codes* refused) :credential/missing))
          (is (nil? (pool-source "later-api"))))
        (testing "verify=false registers it anyway, and the reply does not pretend"
          (let [r (call! token "declare_source"
                         {:id "later-api" :entry (pr-str entry)
                          :verify false :apply true})]
            (is (true? (:written? r)) (pr-str r))
            (is (false? (:connected? r)))
            (is (seq (errors* r))
                "the finding stays — nothing anywhere claims this was reached")
            (is (= (over-the-wire entry)
                   (:pool/entry (call! token "read_object"
                                       {:type "source" :id "later-api"}))))))))))

(deftest the-source-write-is-audited-with-its-health-and-no-resolved-value
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            events (audit-events
                    #(call! token "declare_source"
                            {:id "declared-api" :entry (pr-str (api-key-entry)) :apply true}))
            mutations (filter #(= :pool/mutation (:event %)) events)]
        (is (= 1 (count mutations)) (str "one write, one record: " (pr-str events)))
        (let [m (first mutations)]
          (is (= :builder-mcp (:entry m)))
          (is (= :ok (:health-status m)) "裁定十八: the health verdict rides with the write")
          (is (number? (:health-latency-ms m)))
          ;; The actor is whoever ISSUED the key, as it is for every other
          ;; write through this door — 「经谁授权」 has an answer with no person
          ;; at the keyboard.
          (is (= *owner* (get-in m [:actor :on-behalf-of])) (pr-str (:actor m))))
        (testing "and no resolved ${VAR} value is anywhere in the trail"
          (is (not (str/includes? (pr-str events) "s3cr3t-token-value"))))))))

(deftest a-host-templated-source-is-checked-one-instance-at-a-time
  "owner 2026-09-07, doc 106 §106.14 item 11: a `:base_url` whose host is a
   query-time attribute has no single upstream, so the whole-source check
   REFUSES; `sample` names the instance to ask."
  (with-token-env
    (fn []
      (let [{:keys [token]} (issue! #{:read :write})
            entry {:type "api" :label "Every instance"
                   :base_url "https://{nifi.instance/host}:9443/nifi-api"}
            whole (call! token "declare_source"
                         {:id "tpl-api" :entry (pr-str entry) :apply true})]
        (testing "as a whole: refused, and nothing is written"
          (is (not (true? (:written? whole))))
          (is (#{"refused" :refused} (get-in whole [:health :status])))
          (is (nil? (pool-source "tpl-api"))))
        (testing "with a sample: one instance is asked, and the answer is about that host"
          (let [one (call! token "declare_source"
                           {:id "tpl-api" :entry (pr-str entry)
                            :sample {"nifi.instance/host" "127.0.0.1"}
                            :verify false :apply true})]
            (is (not (#{"refused" :refused} (get-in one [:health :status])))
                (pr-str (:health one)))
            (testing "and what is WRITTEN is the entry, template and all"
              (is (= (over-the-wire entry)
                     (:pool/entry (call! token "read_object"
                                         {:type "source" :id "tpl-api"})))))))))))

(deftest edn-source-validation-and-connect
  (with-token-env
    (fn []
      (as-editor
       (fn []
         (let [declare! (var-get #'open.atlas.studio.builder-server/declare-source-request!)
               request {:id "edn-api" :entry (api-key-entry)}
               plan (declare! request)]
           (is (true? (:connected? plan)))
           (is (false? (:written? plan)))
           (is (nil? (pool-source "edn-api")))
           (let [saved (declare! (assoc request :apply? true))]
             (is (true? (:written? saved)))
             (is (= 1 (:revision saved))))
           (let [checked (declare! (assoc request :expected-revision 1))]
             (is (true? (:connected? checked)))
             (is (false? (:written? checked))))
           (let [failed (declare! {:id "edn-invalid" :apply? true :verify? false
                                  :entry (assoc (api-key-entry) :auth {:type "api_key" :value "literal-secret"})})]
             (is (false? (:written? failed)))
             (is (nil? (pool-source "edn-invalid"))))))))))
