(ns open.atlas.studio.builder-server
  "The first visible Studio Builder slice.

   It intentionally reads the reviewed latest-dialect model on every graph
   request. That gives the developer launcher useful model hot reload today,
   while the Builder's pool/version/publish services are connected behind this
   read surface in the next slice."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            ;; Enterprise SSO's protocol half (doc 8.16): one implementation for
            ;; studio, server and — by contract — workbench.
            [open.atlas.common.oidc :as oidc]
            ;; Required for its side effect: the SQL connector registers itself
            ;; into the engine SPI with `defmethod`. Loading it is what makes
            ;; sql resolvers compile in the embedded runtime.
            [open.atlas.common.connector.sql]
            ;; Same, for "derived" (doc 89 Phase 2): the legacy Studio compiler
            ;; had no derived executor and fenced those resolvers off; the
            ;; engine path runs them like any other source type once this is
            ;; on the classpath and required.
            [open.atlas.common.connector.derived]
            ;; Same again for "api". D2.15 without this require: an api source
            ;; still loads but is only *carried* (`:executable? false`). Required,
            ;; it is validated against api-v1 by `spi/validate-source` at write
            ;; time and executes in the Lab. It deliberately brings no
            ;; `describe-schema` — see `:describable?` below.
            [open.atlas.common.connector.api :as connector-api]
            ;; The seam schema introspection goes through; the connector above
            ;; is what registers an implementation of it for "sql".
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.studio.tools :as tools]
            [open.atlas.runtime.graph :as rgraph]
            [open.atlas.server.data :as data]
            [open.atlas.server.mcp :as mcp]
            [ring.core.protocols]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.env :as renv]
            [open.atlas.runtime.token :as rtoken]
            [open.atlas.common.token :as ctoken]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.engine :as engine]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.graph :as studio-graph]
            [clojure.set]
            [open.atlas.studio.model.output-shape :as shape]
            [open.atlas.studio.auth :as auth :refer [*request-user*]]
            [open.atlas.studio.builder-mcp :as builder-mcp]
            [open.atlas.studio.semantic-service :as semantic]
            [open.atlas.studio.semantic-package-service :as semantic-package]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.studio.graph-service :as gsvc]
            [open.atlas.studio.saved-query-service :as sqsvc]
            [open.atlas.studio.recent-queries :as recent]
            [open.atlas.studio.publish-service :as psvc]
            [open.atlas.studio.workspace-token :as wtoken]
            [open.atlas.studio.credentials :as credentials]
            [open.atlas.studio.http :as http :refer [reply! reply-bytes! reply-edn!
                                                     request-edn]]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool-maintenance :as pool-maintenance]
            [open.atlas.studio.version :as version]
            [open.atlas.studio.pathom.explain :as explain]
            [open.atlas.studio.platform-runtime :as runtime]
            [open.atlas.studio.pathom.index-graph :as index-graph])
  (:gen-class)
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer
            HttpsConfigurator HttpsServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.security KeyStore]
           [java.util.concurrent Executors]
           [javax.net.ssl KeyManagerFactory SSLContext]))

(defn- env [k] (System/getenv k))
(declare data-plane-scheme)

(def ^:private bundled-model-resource
  "The seed model as packaged. `build.clj` copies fixtures/dvd/model.edn to
   this name, for the same reason it bundles the demo database: a Studio jar is
   downloaded and started somewhere that is not a checkout."
  "demo/model.edn")

(def ^:private bundled-graphs-resource
  "The seed GRAPHS as packaged, the sibling of `bundled-model-resource`.
   `build.clj` copies fixtures/<x>/graphs.edn to this name for the same reason
   it copies the model (doc 104 §8.17.6, OQ-STUDIO-SEED)."
  "demo/graphs.edn")

(defn model-path []
  (or (env "OPEN_ATLAS_MODEL_PATH")
      ;; Relative to the process's working directory, so this answers only when
      ;; Studio is started from `apps/studio` in a checkout. Packaged, the
      ;; classpath resource below is what answers — see `fixture-model`.
      (str (.getCanonicalPath (io/file "../..")) "/fixtures/dvd/model.edn")))

(defn- graphs-path
  "The seed graph list: `graphs.edn` beside whatever model this Studio seeds
   from (doc 104 §8.17.6, OQ-STUDIO-SEED). Not a dialect key — dialect-v1 §2's
   top level is closed, so declaring graphs inside model.edn would be a
   contract change; a sibling file is not."
  []
  (str (io/file (.getParentFile (.getAbsoluteFile (io/file (model-path))))
                "graphs.edn")))

(defn- graph-name []
  ;; The DVD store is the packaged seed (8.14), so the graph this Studio names
  ;; by default is that one. `OPEN_ATLAS_STUDIO_GRAPH` still wins, and a Studio
  ;; whose pool was seeded from something else should say so.
  (or (env "OPEN_ATLAS_STUDIO_GRAPH") "demo/dvd"))

(defn- instance-id
  "This embedded runtime's identity. It is part of a token's audience, so two
   Studio homes on one machine must not share it."
  []
  (or (env "OPEN_ATLAS_STUDIO_INSTANCE") "studio-embedded"))

(defn- now-iso []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))


;; ---------------------------------------------------------------------------
;; Model changes leave a trail in the UNIFIED ledger too (E3, audit-event-v1
;; §3 `:pool/mutation`)
;;
;; The pool has always recorded who changed what (`pool/mutations`), and P1 has
;; always recorded who queried what — with no key in common. So «which change
;; put this field in the graph, and who approved it» ran out of road at
;; «version 3 was activated by A». `:mutation-id` is that shared key: the
;; pointer, not the log. The ledger stays in the pool (D4.5).
;;
;; Emitted HERE, at the assembly layer, for a reason the classpath enforces:
;; `studio/pool` depends on `common` only and cannot require `runtime/obs`
;; (D1.8). And one wrapper rather than a callback the pool invokes — a hook
;; would let a second caller believe it was recorded when nobody wired it up.
;; ---------------------------------------------------------------------------

(def ^:private pool-entry
  "Which door a pool provenance entrance is, in audit-event-v1 §2.1.1's closed
   vocabulary. `:ui` and `:http` are both the Builder's HTTP listener; `:mcp`
   is the agent door D3.12 keeps apart from it. A write with no provenance is
   an internal path (bootstrap, migration) and says so rather than borrowing a
   door nobody came through."
  {:ui :builder-http :http :builder-http :mcp :builder-mcp})

(declare refresh-environments!)
;; 8.4.24 裁定十八's service function, defined with the rest of the source
;; path far below; `mcp-deps` above it hands the MCP door the very same one.
(declare declare-source!)

(defn- audit-pool-mutations!
  "One `:pool/mutation` event per audit record the write left behind, and
   `result` back unchanged so this wraps a call in place.

   **One action, one event** (§8): a batch touching N objects is N changes and
   therefore N events — the pool already hands back one summary each, so this
   never has to decide what «one action» means.

   A refused write left no record and gets one `:denied` event naming the
   reason: S5.3 wants the knocking audited, and a trail of successes alone
   cannot answer «has anyone been trying». `prov`'s actor is `:on-behalf-of`
   (§4) — the service half is this instance, which is what actually performed
   the write.

   Subject keys are names and numbers only (§5): the entry — a `:label`, a SQL
   text, a source's `:jdbc_url` — is nowhere in here."
  ([prov result] (audit-pool-mutations! prov nil result))
  ([prov subject result]
    (let [entry   (get pool-entry (:entrypoint prov) :internal)
          actor   (cond-> {:service {:instance-id (instance-id)}
                           :asserted-by :verified}
                    (:actor prov) (assoc :on-behalf-of (:actor prov)))
          denied  (first (filter #(= :error (:severity %)) (:findings result)))
          base    {:v 1
                   :event :pool/mutation
                   :at (or (:at prov) (now-iso))
                   :request-id (obs/request-id)
                   :instance-id (instance-id)
                   :entry entry
                   :actor actor}]
      (if denied
        ;; `subject` is what the caller was trying to change — known for a single
        ;; action, absent for a batch, where the refusal is about the batch and
        ;; naming one of its actions would be a guess.
        (obs/audit-record! (merge base subject {:outcome :denied :reason (:code denied)}))
        (doseq [m (:mutations result)]
          (obs/audit-record!
           (cond-> (assoc base
                          :outcome :ok
                          :object (:object m)
                          :action (:action m)
                          :mutation-id (:mutation-id m)
                          :entrypoint (:entrypoint m)
                          :revision-before (:revision-before m))
             (contains? m :revision-after) (assoc :revision-after (:revision-after m))
             ;; 8.4.24 裁定十八: a source write carries the health check's
             ;; verdict, because «it was registered» and «it answered» are two
             ;; different facts about one write. Status and latency ONLY — a
             ;; resolved `${VAR}` is exactly what §5 keeps out of here.
             (:health-status subject)
             (merge (select-keys subject [:health-status :health-latency-ms]))))))
      ;; 8.4.20 裁定十四 ②: the pool changed, so some graph's projection may
      ;; have changed with it. This is the ONE seam every pool write passes
      ;; through — the MCP intent tools' apply (`audited-tool-call`), the HTTP
      ;; pool routes, `confirm-candidates!`, the removal executor and
      ;; bootstrap — which is why the sweep hangs here rather than on five
      ;; call sites, one of which would eventually be added without it.
      (refresh-environments!)
      result)))

(defn- studio-home []
  (or (env "OPEN_ATLAS_STUDIO_HOME")
      (str (System/getProperty "user.home") "/.open-atlas/studio")))

(def ^:private secrets-file "secrets.edn")

(defn- instance-secrets
  "This instance's own secret material, generated once into the Studio home.

   D5.7 forbids a fixed default that only exists in dev: a pepper baked into
   the source would make every Studio instance share one, and S5.8's rule that
   a token issued by A necessarily fails on B would quietly stop being true. So these
   are generated per instance on first boot and kept — losing them invalidates
   this instance's tokens, which is the correct consequence, not a bug.

   The token pepper and the Builder's actor-signing key are **separate keys**
   (S5.9): the actor key authorises deployments, the pepper authenticates
   tokens, and one leaking must not become the other."
  []
  (let [file (io/file (studio-home) secrets-file)
        rng (java.security.SecureRandom.)
        secret! (fn [] (let [b (byte-array 32)]
                         (.nextBytes rng b)
                         (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b)))]
    (if (.isFile file)
      ;; An existing home predates the workspace-token pepper. Generate the
      ;; missing key and keep every other one **unchanged**: regenerating the
      ;; file wholesale would invalidate this instance's live tokens and
      ;; deployments, which is a far worse answer to "one key is missing".
      (let [stored (edn/read-string (slurp file))]
        (if (:workspace-token-pepper stored)
          stored
          (let [upgraded (assoc stored :workspace-token-pepper (secret!))]
            (spit file (pr-str upgraded))
            upgraded)))
      (let [generated {:token-pepper (secret!)
                       ;; S5.9 again, a third time: the Builder MCP endpoint's
                       ;; ledger gets its OWN pepper. The token pepper
                       ;; authenticates data-plane readers and must not also
                       ;; authorise model writes — one leaking must not become
                       ;; the other.
                       :workspace-token-pepper (secret!)
                       :actor-key-id "studio-builder-actor"
                       :actor-secret (secret!)}]
        (io/make-parents file)
        (spit file (pr-str generated))
        ;; Owner-only: this file is the whole authority for this instance's
        ;; tokens and deployments.
        (try (.setReadable file false false) (.setReadable file true true)
             (.setWritable file false false) (.setWritable file true true)
             (catch Exception _ nil))
        generated))))

(def ^:private bootstrap-workspace
  "The workspace a fresh Studio is seeded for.

   Named once because FOUR things must agree on it: the workspace registration
   below, the objects the seed import lands, the D3.22 stamp pass that runs
   after both — and the founding account, which is made an owner here so the
   seed is reachable when that account chose another workspace to work in
   (`identity/seed-workspace`, 0.2 acceptance defect 5)."
  identity/seed-workspace)

(defonce ^:private studio-state (atom nil))
(defonce ^:private query-history (atom []))
(defonce ^:private last-sync (atom nil))

(declare read-model current-workspace! require-editable-graph!
         require-current-workspace-editor! writable-here?
         mcp-deps graph-service publish-service draft-service draft-runtime
         session-caller)

(defn- fixture-model-text
  "The seed model's text: the configured or checkout-relative file if there is
   one, else the copy packaged in the jar.

   Refusing here — by name, saying what to set — is the point. Before this, a
   jar started outside a checkout died with a FileNotFoundException naming a
   `fixtures/dvd/model.edn` under whatever directory the operator happened to
   be in, which reads as a corrupt install rather than as a missing setting."
  []
  (let [file (io/file (model-path))]
    (cond
      (.isFile file) (slurp file)
      (io/resource bundled-model-resource) (slurp (io/resource bundled-model-resource))
      :else
      (throw (ex-info (str "No seed model: set OPEN_ATLAS_MODEL_PATH to a "
                           "latest-dialect model.edn. A Studio with an empty "
                           "pool is seeded once from it.")
                      {:looked-at (str file)
                       :bundled bundled-model-resource})))))

(defn- model-source
  "Where a seed would be read from, said the way `fixture-model-text` resolves
   it. The startup banner used to print `model-path` unconditionally, which
   named a checkout path even when the packaged copy was what got used."
  []
  (let [file (io/file (model-path))]
    (cond
      (.isFile file) (str file)
      (io/resource bundled-model-resource) (str "bundled " bundled-model-resource)
      :else "no seed model configured")))

(defn- fixture-graphs
  "The seed graphs, read the way `fixture-model-text` reads the model: the file
   beside the configured model if there is one, else the copy packaged in the
   jar (doc 104 §8.17.6, OQ-STUDIO-SEED).

   Absent is not an error — a model with no `graphs.edn` seeds a pool and no
   graphs, which is a legible state. What it must never do again is seed
   SOMEBODY ELSE's graphs: bootstrap used to register `demo/{cis,master,sales,
   purchase}` with `cis.*` selectors whatever `OPEN_ATLAS_MODEL_PATH` pointed
   at, so a DVD Studio came up with four empty graphs and no `demo/dvd`."
  []
  (let [file (io/file (graphs-path))
        text (cond
               (.isFile file) (slurp file)
               (io/resource bundled-graphs-resource)
               (slurp (io/resource bundled-graphs-resource)))
        entries (some-> text edn/read-string)]
    (when (seq entries)
      (when-not (and (sequential? entries)
                     (every? #(and (map? %) (string? (:name %))
                                   (sequential? (:selectors %)))
                             entries))
        (throw (ex-info (str "Seed graph list is malformed: expected a vector of "
                             "{:name \"…\" :selectors [\"…\"]}")
                        {:looked-at (str file)})))
      (vec entries))))

(defn- fixture-model []
  (let [{:keys [model findings]} (dialect/read-dialect (fixture-model-text))]
    (or model
        (throw (ex-info "Latest dialect model was rejected" {:findings findings})))))

(defn- whole-pool-model
  "Every object in this Studio's pool as a dialect model, **across workspaces**.

   For the places where that is genuinely the question: bootstrap (no request,
   no workspace yet) and the reconcile sweep below. Everything a request reaches
   goes through `read-model`, which is scoped (D3.22)."
  []
  ;; The fixture seeds an empty Studio only.  Afterwards the pool is the live
  ;; authored model, so an owner/editor write is immediately visible to the
  ;; Blueprint and runtime cache without rewriting a source fixture on disk.
  (if-let [conn (:conn @studio-state)]
    (pool/as-dialect (pool/db conn))
    (fixture-model)))

(defn- read-model
  "The model **the current workspace owns** (D3.22).

   A workspace is a closed world (D3.17), so the model a request reasons about
   is its own workspace's objects and nothing else — one workspace's dirt
   cannot make another's write illegal, and no screen can show an object its
   viewer may not touch. Ownership is read off the stored
   `:pool.object/workspace` column, not derived from a name."
  []
  (if-let [conn (:conn @studio-state)]
    (pool/as-dialect (pool/db conn) (current-workspace!))
    (fixture-model)))

(defn- model-revision []
  (let [bytes (.getBytes (pr-str (read-model)) StandardCharsets/UTF_8)
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (str "sha256:" (format "%064x" (java.math.BigInteger. 1 digest)))))

(defn- history-path [] (str (studio-home) "/history.edn"))

(defn- preferences-path [] (str (studio-home) "/session-preferences.edn"))

(defn- read-preferences []
  (let [file (io/file (preferences-path))]
    (if (.isFile file)
      (try
        (let [value (edn/read-string (slurp file))]
          (if (map? value) value {}))
        (catch Exception _ {}))
      {})))

(defn- write-preferences! [value]
  (io/make-parents (preferences-path))
  (spit (preferences-path) (pr-str value)))

(defn- principal-key [{:keys [issuer subject]}] [issuer subject])

(defn- last-workspace [principal]
  (get-in (read-preferences) [:last-workspace (principal-key principal)]))

(defn- remember-workspace! [principal workspace]
  (let [preferences (read-preferences)]
    (write-preferences! (assoc-in preferences [:last-workspace (principal-key principal)] workspace))
    workspace))

(defn- load-history! []
  (let [file (io/file (history-path))]
    (reset! query-history
            (if (.isFile file)
              (try (vec (edn/read-string (slurp file))) (catch Exception _ []))
              []))))

(defn- save-history! []
  (io/make-parents (history-path))
  (spit (history-path) (pr-str @query-history)))

(defn- record-history! [request status elapsed-ms error]
  (let [entry (cond-> {:id (str (java.util.UUID/randomUUID))
                       :at (str (java.time.Instant/now))
                       :workspace (current-workspace!)
                       :principal (auth/request-principal)
                       :graph (:graph request)
                       :query (pr-str (:query request))
                       :entity (when (contains? request :entity) (pr-str (:entity request)))
                       :status status
                       :elapsed-ms elapsed-ms
                       :revision (get-in request [:environment :revision])}
                error (assoc :error error))]
    (swap! query-history #(->> (conj % entry) (take-last 100) vec))
    (save-history!)
    entry))

(defn- current-history []
  (let [workspace (current-workspace!)
        principal (auth/request-principal)]
    (->> @query-history
         (filter #(and (= workspace (:workspace %))
                       (= principal (:principal %))))
         reverse
         vec)))

(defn- forget-current-history! [id]
  (let [entry (some #(when (= id (:id %)) %) @query-history)]
    (when-not entry
      (throw (ex-info "Query history entry was not found" {:code :history-not-found :id id})))
    (when-not (some #(= id (:id %)) (current-history))
      (throw (ex-info "Query history belongs to another workspace or user"
                      {:code :history-forbidden :id id})))
    (swap! query-history #(vec (remove (fn [item] (= id (:id item))) %)))
    (save-history!)
    {:status :forgotten :id id}))

(defn- status-data []
  (runtime/cache-status))

(defn- model-status []
  {:revision (model-revision)
   ;; A request derives its effective graph revision from this current model;
   ;; there is no longer one stale, process-global server revision to compare.
   :server-revision (model-revision)
   :in-sync? true
   :last-sync @last-sync})

(defn- sync-model! []
  (try
    (let [_ (read-model)
          retired (runtime/clear!)
          response {:status :synced
                    :revision (model-revision)
                    :retired-environments retired}]
      (reset! last-sync response)
      response)
    (catch Exception e
      (let [response {:status :failed
                      :attempted-revision (model-revision)
                      :errors [{:code :sync-failed :message (.getMessage e)}]}]
        (reset! last-sync response)
        response))))

(defn- ensure-demo-database!
  "Kept as a no-op seam, deliberately.

   It used to copy `apps/server/data/cis-demo.db` — a gitignored file — into
   `./data/`, relative to the working directory. Two problems: a clean clone
   has no such file, and a packaged Studio wrote a `data/` directory into
   whatever directory it was started from. The materialisation now lives in
   `platform-runtime`, which puts each demo database under THIS Studio's home
   and rewrites the source coordinate for this instance only (8.14).

   The call site stays because bootstrap order is worth reading in one place;
   the work moved, the sequence did not."
  []
  nil)

(defn- missing-join-attributes
  "Join attributes the fixture declares that this pool produces but never
   registered.

   A pool is seeded from the committed demo model ONCE, while it is empty;
   after that the pool is the live authored model and the fixture is not read
   again. So a pool created before the collection ruling (owner ruling
   2026-08-26, fixture commit ba7004d) holds the `all_*` resolvers — whose
   `:output` join root names the collection — but not the attribute
   registrations those roots refer to. Since D2.29 a join root is an ordinary
   `:attributes` entry carrying `:target` (the old `:collection? true`
   registrations are joins onto their own namespace). The symptom is maddening
   precisely because nothing errors: the Blueprint draws the collection (its
   nodes derive from resolvers), while the ATTRS list shows nothing (it reads
   the registry), and both are faithfully reporting a pool that disagrees with
   itself.

   The guard is deliberately double: the attribute must be missing, AND some
   resolver in this pool must actually produce it as a join root. The second
   half is what keeps this from being \"bootstrap silently writes the fixture
   into a live pool\": an attribute the modeller deleted along with its
   resolver stays deleted."
  [model fixture]
  (let [produced (into #{}
                       (keep (fn [r]
                               (let [o (first (:output r))]
                                 (when (map? o) (first (keys o))))))
                       (:resolvers model))]
    (into (sorted-map)
          (for [[k v] (:attributes fixture)
                :when (and (dialect/join-attribute? v)
                           (not (contains? (:attributes model) k))
                           (contains? produced k))]
            [k v]))))

(defn- reconcile-collection-attributes!
  "Register what `missing-join-attributes` found, as one unit.
   Returns the count registered; 0 on a healthy pool, so this is idempotent."
  [conn]
  (let [missing (missing-join-attributes (pool/as-dialect (pool/db conn))
                                         (fixture-model))]
    (when (seq missing)
      (let [r (audit-pool-mutations!
               nil
               (pool/execute-actions
                conn (vec (for [[k v] missing]
                            {:action :create :type :attribute :id k :entry v
                             ;; D3.22: bootstrap has no request and therefore no
                             ;; session workspace; the objects it repairs are
                             ;; the seed model's, which belong to the workspace
                             ;; this Studio is seeded for.
                             :workspace bootstrap-workspace
                             :expected_revision 0}))))]
        (when (some #(= :error (:severity %)) (:findings r))
          (throw (ex-info "Studio bootstrap failed at collection-reconcile"
                          {:step :collection-reconcile :findings (:findings r)})))
        (obs/log-event! :studio/collection-attributes-registered
                        {:count (count missing)
                         :attributes (vec (keys missing))
                         :status :ok})))
    (count missing)))

(defn- oidc-handle
  "Enterprise SSO from the environment (doc 8.16 §8.16.3), or nil.

   `oidc/config` answers nil when the three enabling variables are not all set
   — SSO is simply off, which is not an error and changes nothing else about
   this Studio. Findings mean the configuration is half-written (the three keys
   present, `OIDC_REDIRECT_URI` absent); that throws, because this app has no
   other startup refusal path at this depth and a login face that silently is
   not the one the operator configured is exactly what §8.16.6 refuses to
   allow."
  []
  (when-let [result (oidc/config (System/getenv))]
    (if-let [fs (seq (:findings result))]
      (throw (ex-info "Single sign-on configuration was rejected" {:findings (vec fs)}))
      (oidc/open (:config result)))))

(defn- bootstrap! []
  (letfn [(accept! [step result]
            (when (seq (:findings result))
              (throw (ex-info (str "Studio bootstrap failed at " (name step))
                              {:step step :findings (:findings result)})))
            result)]
  (let [_ (io/make-parents (str (studio-home) "/.keep"))
        _ (ensure-demo-database!)
        secrets (instance-secrets)
        ;; studio = Builder + an embedded runtime instance, and studio/app is
        ;; the **only** assembly path (D1.13). This app must not wire the
        ;; runtime subsystems itself: two wirings would eventually differ on
        ;; some capability, which is exactly what the ruling forbids.
        started (app/start!
                 {:pool* {:backend :file :path (str (studio-home) "/pool")}
                  :instance-id (instance-id)
                  :runtime-root (str (studio-home) "/runtime")
                  ;; Two shelves, not one: the Builder cuts onto its own, and
                  ;; publishing sends the bytes **into** the runtime's — the
                  ;; same offer/stage/load/activate a remote Server gets.
                  :artifact-root (str (studio-home) "/artifacts")
                  :token-pepper (:token-pepper secrets)
                  ;; **The same connection facts on both paths.** The Builder's
                  ;; own query path resolves a source through
                  ;; `credentials/resolve-config`; handing the runtime the same
                  ;; function is what keeps `/eql` and `/mcp` from being the one
                  ;; place where a declared `${VAR}` never gets a value. They
                  ;; used to differ, and the symptom was a graph that answered
                  ;; in the UI and refused with "source execution failed" to an
                  ;; agent — two shapes of one runtime disagreeing, which D1.13
                  ;; exists to forbid.
                  ;; 8.4.18 裁定十一 ④: literally the same function the draft
                  ;; path compiles with (`platform-runtime/with-credentials`),
                  ;; so a `${VAR}`, a demo database's path, and a missing
                  ;; credential behave identically on both.
                  :resolve-source runtime/resolve-source
                  :actor-key (fn [k] (when (= (:actor-key-id secrets) k)
                                       (:actor-secret secrets)))})
        _ (accept! :runtime started)
        cfg (:pool-config started)
        conn (identity/install! (:conn started))]
    (studio-graph/ensure-graph-identities! conn)
    (when (empty? (pool/objects (pool/db conn)))
      (let [objects (mapv #(assoc % :pool/revision 1
                                  ;; D3.22: the seed's objects belong to the
                                  ;; workspace this Studio is seeded for. Stamped
                                  ;; here rather than left to the migration's
                                  ;; stamp pass, which runs at `open-pool!` —
                                  ;; i.e. BEFORE this import.
                                  :pool/workspace bootstrap-workspace)
                          (pool/dialect->objects (fixture-model)))
            result (pool/import-pool! conn {:pool/version 1 :pool/objects objects})]
        (accept! :import result)))
    ;; After the (possible) seed, not inside it: the pools this repairs are
    ;; precisely the ones that were seeded long ago and will not enter the
    ;; branch above.
    (reconcile-collection-attributes! conn)
    (when-not (some #(= bootstrap-workspace (:workspace/name %))
                    (studio-graph/workspaces (pool/db conn)))
      (accept! :workspace
               (studio-graph/register-workspace!
                conn {:name bootstrap-workspace
                      :members [{:principal {:issuer "studio" :subject "local-builder"}
                                 :role :owner}]})))
    ;; The bootstrap used to seed a `cis.*` namespace claim for the demo
    ;; workspace. Claims are gone (D3.17 retired the territory rules, D3.22
    ;; removed the table): the seeded objects carry `:pool/workspace` and that
    ;; is the whole of their ownership.
    (letfn [(members-for [selectors]
              ;; A recipe is an object list (D3.19). Bootstrap expands the
              ;; declared selectors **once, here** — the same thing the Graph
              ;; Editor's "add all of cis.master.*" button does at click time,
              ;; through the same one implementation. Objects added to the pool
              ;; later do not join by themselves; that is the cost D3.19 accepts
              ;; and the editor makes visible rather than hides.
              (studio-graph/expand-selectors (pool/as-dialect (pool/db conn))
                                             (vec selectors)))
            (ensure-graph! [name selectors package-name]
              (let [qn (str bootstrap-workspace "/" name)
                    recipe {:members (members-for selectors)}]
                (if-let [graph (studio-graph/graph (pool/db conn) qn)]
                  ;; Bootstrap must not resurrect or mutate an archived graph.
                  (if (= :archived (:graph/state graph))
                    {:graph graph :findings []}
                    (do
                      ;; Backfill only: a graph seeded before doc 104 §8.17.7 D
                      ;; has no release name and could not be cut for a
                      ;; personal workspace. A name someone already chose is
                      ;; never overwritten from the seed.
                      (when (and package-name (nil? (:graph/package-name graph)))
                        (accept! :graph (studio-graph/set-package-name! conn qn package-name)))
                      (accept! :graph (studio-graph/update-recipe! conn qn recipe))))
                  (accept! :graph (studio-graph/register-graph!
                                   conn (merge {:workspace bootstrap-workspace :name name
                                                :created-at (str (java.time.Instant/now))}
                                               ;; doc 104 §8.17.7 D: the seed's
                                               ;; release name is modelling
                                               ;; content and travels in the
                                               ;; model's own `graphs.edn`.
                                               (when package-name
                                                 {:package-name package-name})
                                               recipe))))))]
      ;; doc 104 §8.17.6 (OQ-STUDIO-SEED): the seed graphs come from the model's
      ;; own `graphs.edn`, so `OPEN_ATLAS_MODEL_PATH` decides which graphs a
      ;; fresh Studio starts with — not a list compiled into this file.
      (doseq [{:keys [name selectors package-name]} (fixture-graphs)]
        (ensure-graph! name selectors package-name)))
    (load-history!)
    (merge started {:cfg cfg :conn conn :secrets secrets :oidc (oidc-handle)}))))

(defn- attribute-keyword [value]
  (cond (keyword? value) value
        (string? value) (let [[ns' name'] (str/split value #"/" 2)]
                          (when (and (seq ns') (seq name')) (keyword ns' name')))))

(defn- graph-resolver [{:keys [id input output] :as resolver}]
  (let [flat (->> output (filter keyword?) vec)
        join (some (fn [entry]
                     (when (map? entry)
                       (let [[parent children] (first entry)]
                         {:key parent :children (vec children)}))) output)]
    (merge resolver {:id (str id)
                     :input (vec (keep attribute-keyword input))
                     :output-attrs (into flat (:children join))
                     :join join})))

(defn- owned-attribute?
  "Is this attribute in the recipe? The recipe is an object list (D3.19), so
   ownership is a set lookup — no prefix matching, and no way for an object to
   be \"nearly\" in a graph."
  [members attribute]
  (when-let [attribute (attribute-keyword attribute)]
    (contains? (set (:attributes members)) attribute)))

(defn- own-output [members output]
  (reduce
   (fn [kept entry]
     (cond
       (owned-attribute? members entry) (conj kept entry)
       (map? entry)
       (let [[parent children] (first entry)
             children' (vec (filter #(owned-attribute? members %) children))]
         (if (or (owned-attribute? members parent) (seq children'))
           (conj kept {parent children'})
           kept))
       :else kept))
   [] output))

(defn- own-resolvers [members resolvers]
  (keep (fn [resolver]
          (let [output (own-output members (:output resolver))]
            (when (seq output)
              (graph-resolver (assoc resolver :output output)))))
        resolvers))

(defn- display-model-for-root
  "The graph's own model, for one CALLER.

   **The caller is an argument, not the session** (8.4.13). It used to read
   `(session-caller)` here, which throws `:authentication-required` when there
   is no browser cookie — so every draft tool on the Builder MCP door
   (`evaluate_draft_eql` included, 8.4.12) refused with an auth error that had
   nothing to do with the agent's workspace token, and read as an environment
   problem rather than a wiring one. This is the same rule `graph-service`
   already states: «the graph service takes it as an argument rather than
   reading the session, which is the whole reason an agent holding a workspace
   token can reach the same operations at all». This function was the one that
   did not get it.

   The single-arity form keeps reading the session, so the Builder's own HTTP
   routes are unchanged."
  ([root] (display-model-for-root root (session-caller)))
  ([root caller]
   (let [{:keys [conn]} @studio-state
        db (pool/db conn)
        definition (gsvc/require-graph! db caller root)
        _ (when (= :archived (:graph/state definition))
            (throw (ex-info "Archived graphs cannot be selected for execution"
                            {:code :graph-archived :graph root})))
        members (:graph/members definition)
        model (read-model)
        attributes (->> (keys (:attributes model))
                        (filter #(owned-attribute? members %))
                        vec)]
    ;; Builder renders ownership. A resolver belongs to its output namespace;
    ;; inputs remain visible as external dependency nodes, without making their
    ;; attributes or resolvers owned by this graph.
    {:definition definition
     :attributes attributes
     :resolvers (vec (own-resolvers members (:resolvers model)))})))

(def workspace-scope
  "The graph selector's value for \"the whole workspace\".

   A qualified name always contains a slash (D3.10), so this cannot collide
   with one."
  "*")

(defn- workspace-model
  "The same shape `display-model-for-root` returns, scoped to **everything the
   workspace owns** instead of one graph's recipe.

   Since D3.22 that is a lookup rather than an expansion: the objects a
   workspace owns are the objects stamped with its name, so this reads them
   directly. It used to expand the workspace's namespace claims, which is the
   turf table D3.17 retired.

   It exists because a graph's selectors can leave objects unreachable: an
   attribute no recipe selects is invisible in every Explorer, and an
   attribute nobody can see is one nobody can fix."
  []
  (let [db (pool/db (:conn @studio-state))
        workspace (current-workspace!)
        model (read-model)
        ;; A recipe's eight groups (scope-projection-v1 §1), filled from the
        ;; objects this workspace owns. `:source` has no group — sources come
        ;; back through the projection's closure (§2.4–§2.8), and computing
        ;; them here would be a second closure.
        members (reduce (fn [m {:pool/keys [type id]}]
                          (if-let [k (get {:namespace :namespaces :attribute :attributes
                                           :resolver :resolvers :mutation :mutations
                                           :link :links :term :terms :unit :units
                                           :metric :metrics}
                                          type)]
                            (update m k (fnil conj #{}) id)
                            m))
                        {}
                        (pool/objects db workspace))]
    {:definition {:graph/qualified-name workspace-scope
                  :graph/members members}
     :attributes (vec (filter #(owned-attribute? members %) (keys (:attributes model))))
     :resolvers (vec (own-resolvers members (:resolvers model)))}))

(defn- display-model-for
  "Whichever scope the caller selected: one graph's recipe, or the workspace."
  [root]
  (if (= workspace-scope root)
    (workspace-model)
    (display-model-for-root root)))

(defn- projection-digest
  "The cache revision for one projection: a digest of its own canonical text.

   Its own function because two callers need the SAME number — the compile
   (`draft-model-for-root`) and the post-write sweep that asks whether a
   graph still projects to what its cached environment was compiled from
   (8.4.20 裁定十四 ②). Two digests of one projection would be a second
   answer to «did this graph change»."
  [projected]
  (str "sha256:"
       (format "%064x"
               (java.math.BigInteger.
                1
                (.digest (java.security.MessageDigest/getInstance "SHA-256")
                         (.getBytes ^String (str (:edn projected)) StandardCharsets/UTF_8))))))

(defn refresh-environments!
  "Drop every cached environment whose graph no longer projects to the digest
   it was compiled from (8.4.20 裁定十四 ②).

   **The projection decides, not a hand-written rule about who is affected.**
   A pool object is usually named by several recipes, so «which graphs did this
   write touch» is exactly the question the scope projection already answers;
   writing a second answer here is how the two would come to disagree. So the
   host re-projects each graph that HAS a cached entry and compares digests —
   the same digest the entry is keyed by.

   A graph that no longer projects (deleted, archived, or refused by the cut)
   is invalidated too: nothing that cannot be projected may keep answering."
  []
  (when-let [conn (:conn @studio-state)]
    (let [db (pool/db conn)]
      (doseq [[graph-id revisions] (runtime/cached-graph-revisions)]
        (let [definition (studio-graph/graph-by-id db graph-id)
              digest (when definition
                       (let [projected (studio-graph/project
                                        db (:graph/qualified-name definition))]
                         (when (empty? (:findings projected))
                           (projection-digest projected))))]
          (when-not (= revisions #{digest})
            (runtime/environment-invalidate! graph-id)))))))

(defn- draft-model-for-root
  "The graph's model as an **unsealed cut** (8.4.18 裁定十一 ③).

   The Builder used to assemble this itself — own the recipe's attributes,
   pull their resolvers' dependencies in, filter the links, take the whole
   `:sources` map. That assembly was a SECOND answer to «what is in this
   graph», and the cut's answer is `studio/graph`'s scope projection. The two
   were not the same, which is how a query could run on the draft and be
   refused at the cut with `:scope/mixed-output` and nothing in between able
   to say why.

   So this runs `project` — the same call `studio/version` makes to cut a
   version, over the pool as it stands — and the projection's findings ARE the
   refusal. «The draft runs» and «the cut runs» are one judgement now; a graph
   that cannot be cut is refused here with the finding `publish_graph`'s check
   would give, rather than answering rows it cannot ship.

   The revision is a digest of the projection's own canonical text, so the
   compiled environment is keyed by exactly what was compiled."
  ([root] (draft-model-for-root root (session-caller)))
  ([root caller]
   (let [db (pool/db (:conn @studio-state))
         definition (gsvc/require-graph! db caller root)
         _ (when (= :archived (:graph/state definition))
             (throw (ex-info "Archived graphs cannot be selected for execution"
                             {:code :graph-archived :graph root})))
         projected (studio-graph/project db root)]
     (when (seq (:findings projected))
       ;; The cut's refusal, verbatim. `:error {:findings …}` is the shape
       ;; every Builder face already unwraps (`studio/tools`' services), so a
       ;; caller gets finding-v1 rather than a message about a message.
       (throw (ex-info "This graph cannot be projected, so it cannot be queried or cut"
                       {:code :graph-not-projectable :graph root
                        :error {:findings (vec (:findings projected))}})))
     {:scope {:workspace (:graph/workspace definition)
              :graph (:graph/id definition)
              :graph-name root
              :revision (projection-digest projected)}
      :model (:model projected)})))

(defn- request-root
  "Resolve the selected graph once at the Builder boundary.  External callers
   should use `:graph-id`; `:graph` stays accepted for the current Studio UI and
   readable automation scripts.  Supplying both is allowed only when they name
   the same graph."
  [{:keys [graph graph-id]}]
  (let [db (pool/db (:conn @studio-state))
        by-id (when (and (string? graph-id) (seq graph-id))
                (studio-graph/graph-by-id db graph-id))
        by-name (when (and (string? graph) (seq graph))
                  (studio-graph/graph db graph))]
    (when (and graph-id (nil? by-id))
      (throw (ex-info "Unknown Studio graph UUID" {:code :graph-unknown :graph-id graph-id})))
    (when (and graph (nil? by-name))
      (throw (ex-info "Unknown Studio graph" {:code :graph-unknown :graph graph})))
    (when (and by-id by-name (not= (:graph/id by-id) (:graph/id by-name)))
      (throw (ex-info "The graph UUID and graph name select different graphs"
                      {:code :graph-context-mismatch :graph graph :graph-id graph-id})))
    (let [definition (or by-id by-name)]
      (when-not definition
        (throw (ex-info "A graph must be selected before Explain or Run"
                        {:code :graph-context-required})))
      (:graph/qualified-name definition))))

(defn- environment-for-request
  "The compiled environment for one request — one graph's draft, projected and
   compiled (8.4.18 裁定十一 ③). `caller` is explicit on the agent's path for
   the reason `display-model-for-root` gives: the Builder MCP door has a
   workspace token and no browser session."
  ([request] (environment-for-request request (session-caller)))
  ([request caller]
   (let [root (request-root request)
         {:keys [scope model]} (draft-model-for-root root caller)]
     (runtime/environment! scope model))))

(defn- selected-graph!
  "The graph this request names, checked by the SAME guard the MCP endpoint
   uses (8.4 P4).

   It used to carry its own visibility check that refused with
   `:graph-forbidden` — which tells a caller in another workspace that the
   graph exists. The service's answer is `:graph-unknown`, and one answer that
   leaks nothing beats two answers that disagree."
  [request]
  (gsvc/require-graph! (pool/db (:conn @studio-state)) (session-caller)
                       (request-root request)))

(defn- accessible-workspaces [db principal]
  (->> (studio-graph/workspaces db)
       (keep (fn [{:workspace/keys [name] :as workspace}]
               (when-let [role (studio-graph/workspace-role db name principal)]
                 (cond-> (assoc workspace :workspace/role role)
                   ;; RESOLVED, not the stored string (D3.21): the setting names
                   ;; a qualified name, so a deleted, renamed or archived graph
                   ;; leaves a pointer behind. `default-graph` answers nil for
                   ;; those, and the browser must not be handed a landing target
                   ;; it cannot land on.
                   true (assoc :workspace/landing-graph
                               (studio-graph/default-graph db name))))))
       vec))

(def ^:private build-info
  "What this running Studio is: BUILD.json written by the release tools at
   the package root. Absent in a dev checkout, where the footer says `dev`."
  (delay
    (try
      (let [f (io/file (or (System/getenv "OPEN_ATLAS_PACKAGE_ROOT") ".") "BUILD.json")]
        (when (.isFile f)
          (let [b (json/parse-string (slurp f) true)]
            {:version (:version b) :commit (:commit b) :built-at (:built_at b)})))
      (catch Exception _ nil))))

(defn- studio-context []
  (let [db         (pool/db (:conn @studio-state))
        principal  (auth/request-principal)
        workspaces (accessible-workspaces db principal)
        names      (set (map :workspace/name workspaces))
        last       (last-workspace principal)
        preferred  (:default-workspace *request-user*)
        current    (cond
                     (contains? names last) last
                     (contains? names preferred) preferred
                     :else (:workspace/name (first workspaces)))]
    {:authentication {:authenticated? true :mode :local}
     ;; The data plane's port is the server's fact, not the browser's guess:
     ;; it is configurable, and a UI that printed a default would send people
     ;; to a listener that is not there.
     :data-plane {:port (Long/parseLong (or (env "OPEN_ATLAS_STUDIO_DATA_PORT") "9632"))
                  :scheme (data-plane-scheme)
                  :instance-id (instance-id)}
     :build @build-info
     :user (select-keys *request-user* [:id :email :name :system-role
                                       :has-password? :sso-linked?])
     :principal principal
     :workspaces workspaces
     ;; The local identity supplies the personal/default workspace. The first
     ;; accessible workspace is the safe fallback after a removed preference.
     :default-workspace (if (contains? names preferred)
                          preferred
                          (:workspace/name (first workspaces)))
     :current-workspace current}))

(defn- select-workspace! [{:keys [workspace]}]
  (let [principal (auth/request-principal)
        db        (pool/db (:conn @studio-state))]
    (when-not (studio-graph/workspace-member? db workspace principal)
      (throw (ex-info "You do not have access to this workspace"
                      {:code :workspace-forbidden :workspace workspace})))
    (assoc (studio-context) :current-workspace (remember-workspace! principal workspace))))

(def ^:dynamic ^:private *caller-workspace*
  "Bound by `as-caller` while a Builder MCP tool runs a session handler: the
   workspace TOKEN's workspace, which then is the current workspace."
  nil)

(def ^:dynamic ^:private *caller-provenance*
  "Bound by `as-caller`: the MCP door's provenance, so a handler's
   `ui-provenance` files the write under the token and not under `:ui`."
  nil)

(defn- current-workspace! []
  "The persisted Studio context is the authorization boundary for every
   workspace-owned asset.  A client may select only an accessible workspace,
   but it may never supply an alternative workspace on an asset request."
  (or *caller-workspace*
      (:current-workspace (studio-context))
      (throw (ex-info "No Studio workspace is available for this user"
                      {:code :workspace-context-required}))))

(defn- resolver-output-attributes [output]
  (mapcat #(cond
             (keyword? %) [%]
             (map? %) (let [[parent children] (first %)] (cons parent children))
             :else [])
          (or output [])))

(def ^:private credential-keys
  "Source keys whose **value** stays off read-only surfaces.

   dialect-v1 §3 says a source's connector keys include its credential keys,
   and `dialect.impl.schema` says credential keys take only `${VAR}`. That
   second rule is a docstring — no invariant or test enforces it — so a literal
   password can be sitting in one of these today. Until the rule is enforced,
   the lists and the dossier show that the key is set, not what it is set to;
   the editor still shows it, because that is where someone is deliberately
   looking at it."
  #{:password :client_secret :value :jdbc_url :keystore})

(defn- object-workspace
  "Which workspace owns this pool object — **read off the object** (D3.22).

   It used to be derived: a namespace claim for most types, the `:from` end for
   a link, the single owner of the outputs for a resolver, the referencing
   resolvers for a source. D3.17 retired the claim table those rules read, and
   what was left refused every name no surviving claim happened to cover. The
   derivation now runs exactly once, in `pool`'s D3.22 migration, and what it
   decided is stored.

   nil means the pool holds no such object."
  [db type id]
  (:pool/workspace (pool/object db type id)))

(defn- require-current-workspace-editor! []
  (let [db (pool/db (:conn @studio-state))
        workspace (current-workspace!)
        role (studio-graph/workspace-role db workspace (auth/request-principal))]
    (when-not (contains? #{:owner :editor} role)
      (throw (ex-info "An owner or editor of the current workspace is required"
                      {:code :workspace-editor-required :workspace workspace})))
    workspace))

(defn- require-current-workspace-owner! []
  (gsvc/require-workspace-owner! (pool/db (:conn @studio-state)) (session-caller)))

(def ^:private field-vocabulary
  "Which controlled vocabulary governs a field, where one does.  The names are
   dialect/vocabulary's keys — the reader rejects by that same table, so the
   form cannot offer a value the writer would refuse."
  {[:attribute :type]         :attribute-types
   [:attribute :format]       :formats
   [:term :format]            :formats
   [:namespace :kind]         :namespace-kinds
   ;; D2.29: `:cardinality` is a join attribute's key now — the vocabulary is
   ;; the same one, it moved with the object it describes.
   [:attribute :cardinality]  :cardinalities
   [:link :direction]         :link-directions
   [:link :cardinality]       :cardinalities
   [:metric :grain]           :metric-grains
   [:resolver :result_type]   :result-types
   [:mutation :result_type]   :mutation-result-types})

(def ^:private field-order
  "The order a form presents fields in — identity and meaning first, then the
   machinery.  Fields absent here follow, sorted, so a new dialect key shows up
   in the form instead of disappearing from it."
  ;; D2.29: `:target` and `:cardinality` sit right after `:type`, because they
  ;; are the alternative to it — the form's first question about an attribute
  ;; is which of the two kinds it is, and the two answers should be adjacent.
  {:attribute [:label :description :type :target :cardinality :format :unit
               :identity? :synonyms :values :term :term-context :listed?
               :deprecated? :replaced-by]
   :resolver  [:label :description :input :output :result_type :source-ref
               :source :limit :order :eql-params :cache :curated?
               :row-security]})

(defn- form-descriptor [t]
  (let [fields (pool/type-fields t)
        order  (get field-order t [])
        ranked (concat order (sort (remove (set order) (keys fields))))]
    {:type t
     :id-kind (pool/type-id-kind t)
     ;; The editor edits a whole entry; :update takes a patch. It needs to know
     ;; which dialect the pool speaks for this type (see pool/type-update).
     :update (pool/type-update t)
     :open-keys? (some? (pool/open-entry-keys t))
     :fields (vec (for [k ranked
                        :let [kind (get fields k)]
                        :when kind]
                    (cond-> {:field k :kind kind}
                      (field-vocabulary [t k])
                      (assoc :options (vec (sort-by str (get dialect/vocabulary
                                                             (field-vocabulary [t k]))))))))}))

(defn- schema-data []
  {:dto-version 1
   ;; The defaults matter to a form: writing a default back is not canonical
   ;; form, so the form must leave those fields empty rather than pre-filling
   ;; them (D2.28 / dialect-v1 canonical form).
   :defaults (:defaults dialect/vocabulary)
   ;; Which connectors this instance actually has. Only the server can answer
   ;; it — the same reason `:describable?` is the server's answer and not a
   ;; list in the browser (D2.15): the types are whatever registered
   ;; themselves on *this* classpath, so a face holding its own copy would
   ;; offer a type this build cannot compile.
   :source-types (vec (sort (map name (spi/registered-types))))
   :types (into {} (for [t pool/object-types] [t (form-descriptor t)]))})

(defn- key-face-data
  "What one object type may carry, for the EDN editor's note lines.

   Derived from the dialect's closed schemas (owner ruling 2026-09-07, doc 84
   §8.4.25) — this handler holds no key list of its own, and neither does the
   browser: a second statement of the closed key set is the drift D1.8 forbids.
   An unknown type is refused by name rather than answered with an empty face."
  [params]
  (let [type (get params "type")]
    (or (dialect/key-face type)
        (throw (ex-info "Unknown model asset type"
                        {:code :asset-type-unknown :type type})))))

(def ^:private asset-types
  "The pool's nine object types — the only values the asset-object endpoint
   accepts, so a request never turns an arbitrary string into a keyword lookup.
   `:navigation` was the tenth until D2.29 folded it into `:attribute`."
  #{:attribute :link :metric :mutation :namespace :resolver
    :source :term :unit})

(defn- asset-object-data
  "One pool object with its revision, for the in-place asset editor.  The
   revision fetched here is the `:expected_revision` the subsequent write must
   carry, so the edit form and the conflict check share one origin."
  [params]
  (let [db (pool/db (:conn @studio-state))
        workspace (current-workspace!)
        type (keyword (get params "type"))
        id (edn/read-string (get params "id"))]
    (when-not (contains? asset-types type)
      (throw (ex-info "Unknown model asset type"
                      {:code :asset-type-unknown :type (get params "type")})))
    (let [object (pool/object db type id)]
      (when-not object
        (throw (ex-info "No such model asset"
                        {:code :asset-not-found :type type :id id})))
      ;; Same discipline as the write path (`writable-here?`): the editor's
      ;; fetch must accept whatever a save would accept, or the form shows a
      ;; refusal for an edit the pool would take (D3.22).
      (when-not (writable-here? db workspace {:type type :id id})
        (throw (ex-info "This model asset belongs to a different workspace"
                        {:code :asset-outside-current-workspace
                         :workspace workspace
                         :asset-workspace (object-workspace db type id)
                         :type type :id id})))
      {:object object
       :editable? (contains? #{:owner :editor}
                             (studio-graph/workspace-role db workspace
                                                          (auth/request-principal)))})))

(defn- runtime-parts []
  (or (:runtime @studio-state)
      (throw (ex-info "The embedded runtime is not running"
                      {:code :runtime-unavailable}))))

(defn- publish-graph!
  "Cut the current recipe into a version on the Builder's own shelf, then
   publish it into the embedded runtime.

   Both halves are the shipped ones: `studio/version` cuts (health checks and
   all), and `studio/app` sends it through the very offer/stage/load/activate
   a remote Server would get. Nothing here re-implements either — the point of
   the round trip is that what you self-test is what production will do."
  [{:keys [graph]}]
  ;; The orchestration is `publish-service` (8.4 P3) — the SAME function the
  ;; MCP endpoint calls, so an agent's publish and a person's are one path.
  ((:publish! (publish-service (session-caller))) {:graph graph}))

(defn- graph-versions-data
  "What has been cut for this graph, and where the pointer is. A read."
  [params]
  ((:versions (publish-service (session-caller))) {:graph (get params "graph")}))

(defn- recent-queries-data
  "The data plane's recent requests for one graph: all, failed, pinned, and
   the live capture rules (proposal studio-saved-queries §4.2). With `id`, the
   one request a debug link names."
  [params]
  (let [svc (graph-service (session-caller))
        graph (get params "graph")]
    (if-let [id (get params "id")]
      {:entry ((:recent-query svc) {:graph graph :id id})}
      ((:recent-queries svc) {:graph graph}))))

(defn- recent-query-action!
  "Pin / unpin a recorded request, or add / remove a capture rule."
  [request]
  (let [svc (graph-service (session-caller))]
    (case (:action request)
      :pin ((:pin-recent-query! svc) request)
      :unpin ((:unpin-recent-query! svc) request)
      :add-rule ((:add-capture-rule! svc) request)
      :remove-rule ((:remove-capture-rule! svc) request)
      (throw (ex-info "Unknown recent-query action" {:code :recent-query-action-unknown})))))

(def ^:private bundle-entry-epoch-ms
  "The timestamp every entry in an exported bundle carries.

   Fixed so that exporting the same version twice produces the same bytes — a
   bundle is a thing people checksum and diff, and «the same version gave me a
   different file» is a question nobody should have to answer. 1980-01-01 rather
   than 0 because the zip format's DOS timestamps cannot represent anything
   earlier, and a tool that clamps it silently would put the reproducibility
   back at the mercy of whichever tool opened it."
  315532800000)

(defn- bundle-bytes
  "The three files as a zip laid out like the shelf (D4.13):
   `<workspace>/<graph>/<version>/<file>`.

   The path is a convenience and explicitly **not** authority — the receiving
   side reads coordinates from `manifest.edn`'s provenance and verifies content
   by digest, so renaming a directory in here changes nothing about what the
   bundle is. It is written this way because unzipping it onto an artifact root
   should just work, and because a closure of more than one artifact (not
   possible today under D3.17, but the concept has not gone anywhere) is simply
   more directories."
  [{:keys [workspace name version files]}]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (java.util.zip.ZipOutputStream. out)]
      ;; Fixed order, like every other byte-stable output in this codebase: two
      ;; exports of one version diff to nothing.
      (doseq [f artifact/artifact-files]
        (let [entry (java.util.zip.ZipEntry.
                     (str workspace "/" name "/" version "/" f))]
          (.setTime entry bundle-entry-epoch-ms)
          (.putNextEntry zip entry)
          (.write zip (.getBytes ^String (get files f) java.nio.charset.StandardCharsets/UTF_8))
          (.closeEntry zip))))
    (.toByteArray out)))

(defn- bundle-filename
  "`<workspace>-<graph>-v<n>.zip`. The qualified name's slash cannot go in a
   filename, and a downloads folder is a flat place — the workspace stays in the
   name so two workspaces' `app` graphs do not both arrive as `app-v3.zip`."
  [{:keys [workspace name version]}]
  (str (str/replace (str workspace) #"[^A-Za-z0-9_.-]" "_") "-"
       (str/replace (str name) #"[^A-Za-z0-9_.-]" "_") "-v" version ".zip"))

(defn- export-graph-bundle!
  "Hand back one version's bundle.

   `caller` is the SESSION's for a person at the browser and the WORKSPACE
   TOKEN's for an agent (doc 106 §106.12 item 8.7): the export was reachable
   only through a session cookie, so a modelling agent could not fetch the
   artifact it had just cut, and rebuild sessions hand-assembled the zip from
   `bundle-bytes` instead. It stays an HTTP route rather than becoming an MCP
   tool — 8.4.16's «常用的留、非常用的回 GUI» — because exporting is a TRANSPORT
   act and not one of the twelve sentences, and a name on the tool list is a
   name an agent can pick by mistake. What was wrong was never the door, only
   which credential it accepted."
  [^HttpExchange exchange params caller]
  (let [params* (into {} params)
        bundle ((:export (publish-service caller))
                {:graph (get params* "graph") :version (get params* "version")})]
    (reply-bytes! exchange 200 (bundle-bytes bundle) "application/zip"
                  {"Content-Disposition"
                   (str "attachment; filename=\"" (bundle-filename bundle) "\"")})))

(defn- graph-cut-check-data
  "Would this graph cut right now. A read, and the expensive one — it projects
   and health-checks, so it is asked about the graph a person has open, never
   about a list."
  [params]
  ((:cut-check (publish-service (session-caller))) {:graph (get params "graph")}))

(defn- rollback-graph!
  "Point the graph back at a version cut earlier — the SAME service function
   the `rollback_graph` MCP tool calls, so «who may roll back» and «which
   evidence is presented» are decided once (8.4 P3)."
  [{:keys [graph to-version]}]
  ((:rollback! (publish-service (session-caller)))
   {:graph graph :to-version to-version}))

(defn- reclaim-graph-version!
  "Remove one published version's artifact on this instance (R3.5)."
  [{:keys [graph version]}]
  ((:reclaim-version! (publish-service (session-caller)))
   {:graph graph :version version}))

(defn- reclaim-unused-graph-versions!
  "Remove every reclaimable version except the newest `keep` (R3.5)."
  [{:keys [graph keep]}]
  ((:reclaim-unused! (publish-service (session-caller)))
   {:graph graph :keep keep}))

(defn- issue-graph-token*
  "Issue a token for this Studio's **embedded** runtime.

   Format, signature check and grant rules are the Server's — the same
   `runtime/token` ledger, because there is only one runtime (D1.13). What is
   local is the authority: this ledger is the only place the token exists, so
   a token issued here necessarily fails on any other instance (S5.8).

   The secret appears in this response and **nowhere afterwards** (S5.2/S5.9):
   the ledger keeps a keyed digest, never the value.

   `caller` is explicit (owner ruling 2026-10-11, revising P3 ruling ③): the
   Builder page passes the session's, the Builder MCP a workspace token's, so
   an agent can issue every kind of graph token — Workbench service tokens
   included — under the same editor bar and into the same ledger and audit."
  [caller {:keys [graph expires-at asserts-user?]}]
  (let [{:keys [conn]} @studio-state
        definition (gsvc/require-graph! (pool/db conn) caller graph)
        _ (when-not (studio-graph/graph-editable? (pool/db conn) graph (:principal caller))
            (throw (ex-info "Only an owner or editor may issue a self-test token for this graph"
                            {:code :graph-editor-required :graph graph})))
        now (str (.truncatedTo (java.time.Instant/now)
                               java.time.temporal.ChronoUnit/SECONDS))
        result (rtoken/issue! (:token (runtime-parts))
                              ;; The public single-graph shape (a token
                              ;; authorises exactly one graph). `:grants` is a
                              ;; migration seam the ledger normalises away and
                              ;; never stores — writing through it left this
                              ;; app speaking a shape nothing reads back.
                              (cond-> {:graph-id graph :version :latest
                                       :actor (:principal caller)
                                       :request-id (str "selftest-" (:graph/id definition) "-"
                                                        (java.util.UUID/randomUUID))
                                       :now now}
                                expires-at (assoc :expires-at expires-at)
                                ;; D1.9b: a Workbench service token may name the user it reads for;
                                ;; the ledger refuses anything but true/false.
                                (some? asserts-user?) (assoc :asserts-user? asserts-user?)))]
    (when (seq (:findings result))
      (throw (ex-info "Issuing a self-test token was rejected" {:error result})))
    result))

;; `preview-tokens` / `preview-token!` / `preview-query!` lived here: a
;; server-minted token and a run of the PUBLISHED version through
;; `instance/answer`, so a modeller could ask "what will a consumer get?".
;; Retired (D1.14): the answer is the same one a real UAT instance gives —
;; same runtime by D1.13 — and that instance additionally covers the control
;; protocol, real credentials and a real token lifecycle, none of which this
;; shortcut could reach. The self-test token panel below stays: it issues a
;; REAL token for the real data-plane door (D5.7), which is what this was
;; imitating.

;; ---------------------------------------------------------------------------
;; The control plane's two doors leave a trail (audit-event-v1 §3)
;;
;; D3.12 keeps :builder-http (people, session cookie) and :builder-mcp (agents,
;; workspace token) apart, and S5.3 wants every refusal at either recorded:
;; «who is knocking on which door» is the data source for deciding whether
;; someone should have been let in. Both doors used to answer 401 and record
;; nothing at all, so «has anyone been trying?» had no answer anywhere.
;;
;; Emission is fail-open by construction — `obs/audit-record!` never throws to
;; its caller and counts its own write failures (`obs/audit-write-failures`).
;; So no handler below guards it, and none of them invents a second failure
;; mode for a lost audit line.
;; ---------------------------------------------------------------------------

(defn- audit-auth-failure!
  "One refused authentication at `entry`, with the machine-readable `reason`.

   No subject key, on purpose: audit-event-v1 §2.2 gives `:auth/*` none, and a
   credential that failed verification yields no principal this instance has
   established. An event with no subject is stream-only (obs `subject/subjects`)
   — that retention gap is known and is not paid for by filing an attempted,
   unverified name in the subject index.

   `:asserted-by :verified` because the check that failed happened HERE: this
   instance ran it and rejected the caller, which is exactly what 50-security
   requires the value to mean."
  [entry reason]
  (obs/audit-record!
   {:v 1
    :event       :auth/failed
    :at          (now-iso)
    :request-id  (obs/request-id)
    :instance-id (instance-id)
    :entry       entry
    :actor       {:service     {:instance-id (instance-id)}
                  :asserted-by :verified}
    :outcome     :denied
    :reason      reason}))

(defn- audit-oidc-login!
  "One accepted single sign-on (doc 8.16 §8.16.4 rule 7).

   `:on-behalf-of` is the **identity provider** principal `(issuer, sub)`, not
   the local one: what this event records is which external identity was
   verified, and D5.10's point is that this pair is the immutable key. The
   email, the claims, the code and the tokens are values and appear in no key
   (audit-event-v1 §5)."
  [entry principal]
  (obs/audit-record!
   {:v 1
    :event       :auth/login-oidc
    :at          (now-iso)
    :request-id  (obs/request-id)
    :instance-id (instance-id)
    :entry       entry
    :actor       {:service      {:instance-id (instance-id)}
                  :on-behalf-of principal
                  :asserted-by  :verified}
    :outcome     :ok}))

(defn- audit-workspace-token!
  "A workspace token's issue / revoke, in the shape the data-plane ledger
   already writes them (`runtime/token` impl/ledger `audit!`): same events,
   same actor chain. Two token ledgers writing one event shape is the point of
   the 2026-08-31 merge ruling — the entry differs because this key is minted
   at the Builder's door, not internally.

   Which workspace, which scope and how long it lives ride in `:authorization`:
   audit-event-v1 §2.2 allows the `:token/*` family `:token-id`, `:graph`,
   `:graph-version` and `:authorization` — the grant SUMMARY — and no free
   `:workspace` key. The secret and the digest appear in neither (§5)."
  [event {:keys [token-id workspace scope expires-at]}]
  (obs/audit-record!
   {:v 1
    :event       event
    :at          (now-iso)
    :request-id  (obs/request-id)
    :instance-id (instance-id)
    :entry       :builder-http
    ;; §4: the service is this instance's control plane; the operator who
    ;; asked for the key is :on-behalf-of. A key minted by nobody is not a
    ;; thing this door can produce — every caller here is session-authenticated.
    :actor       {:service      {:instance-id (instance-id)}
                  :on-behalf-of (auth/request-principal)
                  :asserted-by  :verified}
    :outcome     :ok
    :token-id    token-id
    ;; Sorted rather than as-stored: §8 wants two runs of one action to differ
    ;; only in :at / :request-id, and a set's print order is not a promise.
    :authorization {:workspace workspace
                    :scope (vec (sort scope))
                    :expires-at expires-at}}))

(defn- graph-tokens*
  "The ledger's governance view for this workspace's graphs: records only —
   never a digest, never a secret (S5.1). `caller` explicit, as for issuing."
  [caller]
  (let [{:keys [conn]} @studio-state
        db (pool/db conn)
        workspace (:workspace caller)
        mine (->> (studio-graph/visible-graphs db (:principal caller))
                  (filter #(= workspace (:graph/workspace %)))
                  (map :graph/qualified-name)
                  set)
        parts (runtime-parts)]
    {:instance-id (instance-id)
     ;; Which of the visible graphs this caller may act on. Visibility belongs
     ;; to the workspace and editing does not, so a member sees keys they may
     ;; not retire — and a control that can only refuse is worse than none.
     ;; This is a display input; the refusal itself lives at the handler.
     :editable (vec (sort (filter #(studio-graph/graph-editable?
                                    db % (:principal caller))
                                  mine)))
     ;; **`:graph-id`, not `:grants`.** A token authorises exactly one graph and
     ;; the ledger records it under `:graph-id` — `normalize-issue-request` is
     ;; explicit that it "never persists nor returns a `:grants` field". Reading
     ;; the old key made this filter drop every record, so the panel said "no
     ;; token has been issued for this graph" while the ledger held live,
     ;; unrevoked ones. A governance view that under-reports is worse than none:
     ;; it invites issuing another token to replace one that was never gone.
     ;; `:expired?` rides alongside each record because the ledger's own
     ;; `:status` is two-valued (`:active` / `:revoked`) — expiry is a separate
     ;; fact, and a view showing only the status calls a key that ran out last
     ;; week live. Answered with `common/token`'s own `expired?` rather than by
     ;; the browser comparing instants: one implementation of «has this run
     ;; out», and it is the one the door uses.
     :tokens (let [now (now-iso)]
               (->> (rtoken/all-records (:token parts))
                    (filter (fn [r] (contains? mine (:graph-id r))))
                    ;; Newest first (owner 2026-10-10): the key just issued is the one looked for;
                    ;; history goes below. Revoking the top rows by mistake was the cost of oldest-first.
                    (sort-by :created-at #(compare %2 %1))
                    (mapv #(assoc % :expired? (ctoken/expired? % now)))))}))

;; ---------------------------------------------------------------------------
;; Workspace authorisation tokens — the Builder MCP endpoint's credential
;; (8.4 P1b). A DIFFERENT ledger from the self-test tokens above: those
;; authorise reading one graph on the data plane, these authorise writing the
;; model in one workspace. D3.12 keeps the doors apart; one ledger for both
;; would put back exactly what that split prevents.
;; ---------------------------------------------------------------------------

(def workspace-token-lifetimes
  "The lifetimes the panel offers, in days. There is no default: the issuer
   picks one every time, because a default lifetime is a policy the person
   signing the key never sees and therefore never weighs."
  [1 7 30 90])

(defn- workspace-tokens-data []
  (let [now (now-iso)]
    {:workspace (current-workspace!)
     :lifetimes workspace-token-lifetimes
     ;; Served, not repeated in the browser: two copies of a limit is two
     ;; limits the day one of them moves, and the one that moved is the one
     ;; the person typing never sees (CLAUDE.md「摆到接口上，不要各写一份」).
     :limits {:label wtoken/max-label-chars
              :description wtoken/max-description-chars}
     ;; `:status` per record — `:active` / `:expired` / `:revoked`. A panel
     ;; that showed only «revoked or not» would call an expired key live.
     :tokens (->> (wtoken/records (studio-home) now)
                  (filter #(= (current-workspace!) (:workspace %)))
                  ;; Newest first, as the graph tokens (owner 2026-10-10).
                  (sort-by :issued-at #(compare %2 %1))
                  vec)}))

(defn- issue-workspace-token!
  "Mint a workspace token. Owner or editor only — the same bar as writing the
   model by hand, because that is exactly what this key delegates.

   The secret is in THIS response and nowhere else afterwards (S5.2).

   `label` (the UI's **name**) is required and `description` optional — both
   bounded by `workspace-token`, which refuses rather than truncates (8.4.11).

   `expires-in-days` is required — a key that authorises model writes gets a
   natural death, not a reminder to revoke it someday."
  [{:keys [scope label description expires-in-days]}]
  (let [workspace (require-current-workspace-editor!)
        ;; **A mistyped `scope` is a refusal, not a stack trace** (0.2
        ;; acceptance defect 10). `:read` where `[:read]` was meant used to
        ;; reach `map` and die with "Don't know how to create ISeq from:
        ;; clojure.lang.Keyword" — true, and about the wrong subject. The two
        ;; scopes there are get named (finding-v1 «点名位置»).
        scope* (when (or (nil? scope) (sequential? scope) (set? scope))
                 (set (map keyword (or scope [:read]))))
        days (when (number? expires-in-days) (long expires-in-days))]
    (when-not (and scope* (contains? wtoken/scopes scope*))
      (throw (ex-info "scope must be a list: [\"read\"] or [\"read\" \"write\"]"
                      {:code :workspace-token-scope-invalid
                       :got scope
                       :expected [["read"] ["read" "write"]]})))
    ;; Required, and a DURATION rather than an absolute instant: the instant
    ;; is computed from the same clock `verify` will later compare against, so
    ;; a skewed or hand-edited browser clock cannot mint a key that outlives
    ;; what the issuer chose. The upper bound is here and not in the ledger
    ;; because it is this Builder's policy, not a property of the record.
    (when-not (and days (pos? days) (<= days 365))
      (throw (ex-info "expires-in-days must be a whole number of days, 1–365"
                      {:code :workspace-token-expiry-invalid :got expires-in-days})))
    (let [issued-at (now-iso)
          result
          (wtoken/issue! (studio-home)
                         (:workspace-token-pepper (:secrets @studio-state))
                         {:workspace workspace
                          :scope scope*
                          :issued-by (auth/request-principal)
                          :issued-at issued-at
                          :expires-at (str (.plus ^java.time.Instant (java.time.Instant/parse issued-at)
                                                  days java.time.temporal.ChronoUnit/DAYS))
                          ;; Passed through unchecked on purpose: the ledger
                          ;; bounds and refuses both fields, and a second check
                          ;; here would be a second place for the limits to
                          ;; disagree.
                          :label label
                          :description description})]
      ;; From the RECORD, never the return value as a whole: `issue!` hands
      ;; back the secret, and it is in that response and nowhere else (S5.2).
      (audit-workspace-token! :token/issue (:record result))
      result)))

(defn- revoke-workspace-token!
  "Revoke one. Scoped to the current workspace: a token belonging to another
   workspace is refused as unknown rather than as forbidden — whether it exists
   is not this caller's business."
  [{:keys [token-id]}]
  (let [workspace (require-current-workspace-editor!)
        record (->> (wtoken/records (studio-home))
                    (filter #(and (= token-id (:token-id %)) (= workspace (:workspace %))))
                    first)]
    (when-not record
      (throw (ex-info "No such workspace token"
                      {:code :workspace-token-unknown :token-id token-id})))
    (let [result (wtoken/revoke! (studio-home)
                                 {:token-id token-id :revoked-at (now-iso)})]
      ;; The grant summary is the record's, read before the revocation: what a
      ;; key was allowed to do is the fact that makes «this was withdrawn»
      ;; readable later, and `revoke!` answers only with the id and the instant.
      (audit-workspace-token! :token/revoke record)
      result)))

(defn- audited-tool-call
  "`tools/call`, with the pool writes it performed filed in the unified ledger
   (E3).

   The agent door's writes go through `studio/tools`, which — like the pool
   itself — may not require `runtime/obs` (D1.8). So the emission sits on the
   `:call` seam this app already injects: every write tool, present and
   future, is covered by construction, and no tool has to remember to record
   itself.

   `:mutations` is on every pool-write return and on no other return, so
   present-and-empty is a refused write and absent is a read — the one key
   this has to look at."
  [reg ctx tool args]
  (let [r (tools/call reg ctx tool args)]
    (when (contains? r :mutations)
      (audit-pool-mutations! (:provenance ctx) r))
    r))

(defn- mcp-deps
  "The Builder MCP endpoint's transport deps. Built per request because the
   pool connection and secrets live in the server's state atom."
  []
  (builder-mcp/deps
   {:home (studio-home)
    :pepper (:workspace-token-pepper (:secrets @studio-state))
    :conn (:conn @studio-state)
    :graph-service graph-service
    :publish-service publish-service
    :draft-service draft-service
    ;; 8.4.18 裁定十一 ①: the environment seam the seven mounted data-plane
    ;; tools resolve through. Injected here because `studio/tools` may not
    ;; require `server.mcp` (D1.8) and this app is the assembly point.
    :draft-runtime draft-runtime
    ;; 8.4.14 裁定七: the api probe, injected because `studio/tools` declares
    ;; the SPI seam and not any one connector — this app is where «which
    ;; connectors are loaded» is decided, and it already requires this one.
    :probe-source (fn [source-config request] (connector-api/probe source-config request))
    ;; 8.4.22 裁定十六 ②: the connector's own `:data_path` locator, so the
    ;; fields a plan infers are read from exactly where the query path will
    ;; read its rows. Injected for the same reason the probe is.
    :locate-rows connector-api/locate-rows
    ;; The very function the runtime resolves a query's sources with (8.4.18
    ;; 裁定十一 ④ / 裁定十二 ①), so `describe_source`, `add_resolver`'s plan and
    ;; the api probe see the same credentials `/eql` does — Studio credential
    ;; store first, process env second (doc 106 §106.14 item 12), decided once in
    ;; `credentials/resolve-config`.
    :resolve-source runtime/resolve-source
    ;; 8.4.24 裁定十八: the ONE guarded path a source write takes, bound to
    ;; this key's workspace exactly as the graph service is. `declare_source`
    ;; validates nothing and opens no connection of its own — it calls this,
    ;; which is the same function the «Connect a database» card calls.
    :declare-source (fn [caller]
                      (fn [request]
                        (declare-source! (assoc request :workspace (:workspace caller)))))
    ;; 8.4.17 裁定十: the fallback for an omitted `graph`. The SAME resolution
    ;; the browser's landing target uses (`accessible-workspaces`) — RESOLVED
    ;; and not the stored string (D3.21), so a pointer left behind by a
    ;; deleted, renamed or archived graph answers nil and the tool refuses
    ;; instead of naming a graph that is not there.
    :default-graph (fn [workspace]
                     (studio-graph/default-graph (pool/db (:conn @studio-state)) workspace))
    ;; 8.4.18 裁定十一: the seven data-plane declarations, mounted verbatim.
    ;; `modules/studio/tools` may not require `modules/server/mcp` (D1.8), so
    ;; the app — the assembly point — hands them over.
    :declarations (tools/declarations mcp/mountable-declarations)
    :registry #(tools/registry % mcp/mountable-declarations)
    :semantic-service (fn [caller] (semantic/service (:conn @studio-state) caller runtime/resolve-source))
    :semantic-package-service
    (fn [caller]
      (fn [action request provenance]
        (case action
          :plan (semantic-package/plan (:conn @studio-state) caller request semantic/checkers)
          :apply (semantic-package/apply! (:conn @studio-state) caller request
                                           semantic/checkers provenance))))
    :tools-list tools/tools-list
    :call audited-tool-call
    :now now-iso
    :on-auth-failure (partial audit-auth-failure! :builder-mcp)}))

(defn- revoke-graph-token*
  "Retire one graph key. Requires editor on the graph that key opens.

   The same bar as issuing one, because they are one authority: retiring a key
   changes what a consumer can read, and «may hand out access» and «may take it
   away» are not two different rights.

   This handler used to take a `token-id` and revoke it with **no check at
   all**, while `issue-self-test-token!` has always required editor on the
   graph. The asymmetry was easy to miss while the only revoke buttons lived
   inside a panel you had to already be inside; a workspace-wide list of every
   key (8.4.11 ①) is what made it worth closing.

   An unknown id and one this caller may not touch refuse **identically**:
   whether a key exists on this instance is not something a caller without
   rights on it should be able to learn by trying — the same reason D3.12's two
   doors give one answer to a key brought to the wrong one."
  [caller {:keys [token-id]}]
  (let [{:keys [conn]} @studio-state
        store (:token (runtime-parts))
        graph (:graph-id (rtoken/record store token-id))]
    (when-not (and graph
                   (= (:workspace caller) (some-> (studio-graph/graph (pool/db conn) graph) :graph/workspace))
                   (studio-graph/graph-editable? (pool/db conn) graph
                                                 (:principal caller)))
      (throw (ex-info "This instance has issued no such token"
                      {:code :token-unknown})))
    (let [result (rtoken/revoke-token! store
                                       {:token-id token-id
                                        :actor (:principal caller)
                                        :now (str (java.time.Instant/now))})]
      (when (seq (:findings result))
        (throw (ex-info "Revoking was rejected" {:error result})))
      result)))

;; The Builder page's entrances: the same three functions, the session as caller.
(defn- issue-self-test-token! [request] (issue-graph-token* (session-caller) request))
(defn- self-test-tokens-data [] (graph-tokens* (session-caller)))
(defn- revoke-self-test-token! [request] (revoke-graph-token* (session-caller) request))

(defn- preflight-asset!
  "Would this action leave the graph un-cuttable — and is that this action's
   doing? Runs the real cut checks against a candidate pool and returns the
   diff against the current one (`studio/version`'s preflight).

   It writes nothing. The author still gets to save: D2.2 is \"free to write,
   strict to seal\", so turning this into a block in the Builder would change
   that ruling rather than serve it."
  [{:keys [graph action]}]
  ((:preflight (publish-service (session-caller))) {:graph graph :action action}))

(defn- writable-here?
  "Whether this workspace may write this object (D3.22, owner ruling 2026-09-05).

   Two cases, and deliberately no third:

   - **The pool holds no such object.** Writing it is allowed, and the write
     stamps it with this workspace — that is how an object acquires an owner,
     and it is the only way one ever does.
   - **The pool holds it.** It belongs to the workspace stamped on it, and to
     nobody else.

   The `:source` special case is gone with the derivation that made it
   necessary. A source's owner used to come from the resolvers referencing it,
   which made registering one impossible by construction (a new source is
   referenced by nothing, so it had no owner, so the write was refused), and
   the exemption «unowned is fair game» was the patch. With ownership stored, a
   new source is simply a new object: case one. One rule, no exceptions —
   two exceptions would be the second semantics D1.8 forbids."
  [db workspace {:keys [type id]}]
  (let [owner (object-workspace db type id)]
    (or (nil? owner) (= workspace owner))))

(defn- session-caller
  "The caller, as the SESSION establishes it (8.4 P2).

   Both entrances build one of these; the graph service takes it as an argument
   rather than reading the session, which is the whole reason an agent holding
   a workspace token can reach the same operations at all."
  []
  {:workspace (current-workspace!) :principal (auth/request-principal)})

(defn- workspace-token-caller
  "The caller a Bearer workspace token establishes, or nil (item 8.7).

   `wtoken/verify` is the MCP door's own validator, CALLED here rather than
   copied: two implementations of «is this key good» is how one of them ends up
   still accepting a revoked one. Returns the shape `session-caller` returns,
   plus the key's `:scope` for the route to judge."
  [^HttpExchange exchange]
  (when-let [raw (let [h (str (.getFirst (.getRequestHeaders exchange) "Authorization"))]
                   (when (str/starts-with? (str/lower-case h) "bearer ")
                     (not-empty (str/trim (subs h 7)))))]
    ;; No pepper (an instance without workspace tokens) or a key that does
    ;; not even parse: no caller — the request then falls through to the
    ;; session check and is refused as unauthenticated, never as a 400.
    (when-let [record (when-let [pepper (:workspace-token-pepper (:secrets @studio-state))]
                        (try (wtoken/verify (studio-home) pepper raw (now-iso))
                             (catch Exception _ nil)))]
      {:workspace (:workspace record)
       :principal (:issued-by record)
       :scope (set (:scope record))})))

(defn- prepare-runtime-graph-deletion!
  "Stop local service and revoke every still-valid key for an archived Graph.
   Remove exclusively owned local artifact copies, retaining audit evidence.
   Inspect dependencies before any service or credential changes."
  [definition caller]
  (instance/prepare-graph-deletion!
   (:runtime @studio-state)
   {:graph (:graph/qualified-name definition) :graph-id (:graph/id definition)
    :artifact-roots [(:artifact-root @studio-state)]
    :actor (:principal caller) :now (now-iso)}))

(declare run-draft! draft-findings
         credentials-data* put-credentials* save-variable* variable-removal-plan* remove-variable*
         sources-data api-probe! source-removal-plan-request remove-source!
         rename-graph! duplicate-graph-plan apply-duplicate-graph! merge-graphs! archive-request!
         delete-graph! rollback-graph! graph-cut-check-data set-graph-default-query!
         set-graph-package-name! set-workspace-default-graph! reclaim-unused-graph-versions!
         workspace-tokens-data revoke-workspace-token! workspace-objects)

(defn- semantic-draft-revision
  "The graph's saved Semantic Contract draft revision, or nil for a graph on
   the legacy projection. The same decision `run-request-core!` makes: a saved
   Contract draft is that graph's authoring truth."
  [graph caller]
  (let [db (pool/db (:conn @studio-state))
        definition (gsvc/require-graph! db caller graph)
        revision (:revision (pool/semantic-draft db (:workspace caller) (:graph/id definition)))]
    (when (pos? (or revision 0)) revision)))

(defn- semantic-or-throw [r]
  (if (seq (:findings r))
    (throw (ex-info "The Semantic Contract draft could not be compiled"
                    {:code :saved-query-check-failed :error {:findings (vec (:findings r))}}))
    r))

(defn- draft-attributes
  "The attributes and joins the draft of `graph` declares — the catalog
   `list_attributes` renders, off the compile its next version gets."
  [graph caller]
  (let [cat (if (semantic-draft-revision graph caller)
              (:catalog (semantic-or-throw
                         ((semantic/service (:conn @studio-state) caller runtime/resolve-source)
                          :catalog {:graph graph} nil)))
              (:catalog (mcp/catalog-of (runtime/compiled-env
                                         (environment-for-request {:graph graph} caller)))))]
    (into (set (keys (:attributes cat))) (keys (:joins cat)))))

(defn- run-draft-query
  "One EQL request against the draft, by whichever path the graph authors on
   — Semantic Contract preview or legacy projection — as `{:findings …}` or
   the engine's answer."
  [graph caller {:keys [entity query]}]
  (if-let [revision (semantic-draft-revision graph caller)]
    (let [r ((semantic/service (:conn @studio-state) caller runtime/resolve-source)
             :query {:graph graph :expected_revision revision :query query :entity (or entity {})} nil)
          response (or (:response r) r)]
      (cond
        (seq (:findings r)) {:findings (vec (:findings r))}
        (= :rejected (:engine/status response)) {:findings [(dissoc response :engine/status)]}
        :else response))
    (draft-findings #(run-draft! {:graph graph :entity entity :query query} caller :builder-mcp))))

(defn- saved-query-stale
  "Saved queries naming attributes the draft no longer has → findings. Static;
   the publish check (proposal studio-saved-queries §3.2)."
  [caller]
  (fn [graph queries]
    (let [known (draft-attributes graph caller)]
      (vec (mapcat #(mcp/saved-query-stale-findings % known) queries)))))

(defn- saved-query-check
  "What a save runs before keeping a saved query: the static check, then —
   when examples cover every required parameter — a real run against the
   draft through `run-draft!`, the one draft query path. The run's refusal
   travels whole."
  [caller]
  (fn [{:keys [graph saved-query verify?]}]
    (let [stale (try (mcp/saved-query-stale-findings saved-query (draft-attributes graph caller))
                     (catch clojure.lang.ExceptionInfo e
                       (or (seq (get-in (ex-data e) [:error :findings]))
                           [{:code :studio.draft/refused :severity :error :path [:graph]
                             :message (ex-message e)}])))
          examples (mcp/saved-query-example-params saved-query)]
      (cond
        (seq stale) {:findings (vec stale)}
        (not verify?) {:verification {:ran false :reason "verify was false"}}
        (nil? examples) {:verification {:ran false
                                        :reason "a required parameter has no example, so the query could not be run; give examples to have it checked"}}
        :else
        (let [{:keys [request findings]} (mcp/bind-saved-query saved-query examples)]
          (if findings
            {:findings findings}
            (let [r (run-draft-query graph caller (if (map? request) request {:query request}))]
              (if (seq (:findings r))
                {:findings (vec (:findings r))}
                {:verification {:ran true :params examples :status "ok"}}))))))))

(defn- as-caller
  "Run a Builder page handler as `caller` — a workspace token's workspace and
   issuer — instead of the browser session (owner ruling 2026-10-11: AI
   manages Studio over the Builder MCP).

   The handler is the page's own, unchanged: its role checks read the issuer's
   role in the token's workspace, its writes are filed under `provenance` (the
   MCP door's). An issuer whose account is gone acts as nobody."
  [caller provenance f]
  (let [db (pool/db (:conn @studio-state))
        user (some-> (identity/user-by-id db (get-in caller [:principal :subject]))
                     identity/public-user)]
    (when-not (and user (not (:disabled? user)) (= (:principal caller) (:principal user)))
      (throw (ex-info "The key's issuer is no longer a Studio user"
                      {:code :authentication-required})))
    (binding [*request-user* user
              *caller-workspace* (:workspace caller)
              *caller-provenance* provenance]
      (f))))

(defn- caller-op
  "A page handler as a service op: `:provenance` rides in on the request from
   the tool's ctx (never from tool arguments) and is taken off before the
   handler sees it."
  [caller handler]
  (fn [{:keys [provenance] :as request}]
    (as-caller caller provenance #(handler (dissoc request :provenance)))))

(defn- graph-service
  "The graph operations bound to one caller. The Builder's own routes and the
   MCP endpoint each build this — same functions, same guards, different
   caller."
  [caller]
  (let [ctx {:conn (:conn @studio-state)
             :caller caller
             :now #(str (java.time.Instant/now))
             :prepare-delete! prepare-runtime-graph-deletion!
             ;; The embedded runtime caches a compiled env per graph. A recipe
             ;; change that did not invalidate it would leave the data plane
             ;; answering from the previous recipe — visible only as "my new
             ;; attribute is not there", long after the edit.
             :invalidate! (fn [graph-id]
                            (runtime/invalidate!
                             (if graph-id
                               #(= graph-id (get-in % [:scope :graph]))
                               (constantly true)))
                            ;; 8.4.20 裁定十四 ②: a recipe write can move
                            ;; ANOTHER graph's projection too (a shared
                            ;; namespace, a link's other end), so the
                            ;; same digest sweep the pool writes run also runs
                            ;; here — one rule for both kinds of write.
                            (refresh-environments!))}]
    {;; **Which resolvers the engine cannot compile** (8.4.19 裁定十三 ②).
     ;;
     ;; `inspect_graph`'s summary reads it. It lives on the host because only
     ;; the host compiles: `studio/tools` may not require `apps/studio`, and
     ;; the compile is the embedded runtime's — the same one a published
     ;; version gets (裁定十二 ①), so «this resolver is dropped» here means
     ;; dropped there.
     ;;
     ;; The engine's findings travel WHOLE. A count is added and nothing is
     ;; reworded: the publish check and `add_resolver`'s plan refuse with the
     ;; same codes, and three wordings for one defect is three vocabularies.
     :compile-status
     (fn [{:keys [graph]}]
       (let [compiled (runtime/compiled-env (environment-for-request {:graph graph} caller))
             findings (filterv #(= "engine.compile" (namespace (:code %)))
                               (:engine/findings compiled))]
         {:graph graph
          :uncompilable (mapv (fn [f]
                                {:resolver (second (:path f)) :finding f})
                              findings)
          :findings []}))
     :sweep-removed!   #(gsvc/sweep-removed! ctx %)
     :list-graphs      #(gsvc/list-graphs ctx %)
     :graph-members    #(gsvc/graph-members ctx %)
     :project-graph    #(gsvc/project-graph ctx %)
     :create-graph!    #(gsvc/create-graph! ctx %)
     :update-recipe!   #(gsvc/update-recipe! ctx %)
     ;; **The intent tools' recipe-delta executor** (8.4.14). Not a tool of its
     ;; own: the owner ruled against an `add_members` face that speaks in
     ;; object ids, so the delta is generated inside an intent tool and only
     ;; applied here. Both halves go through `gsvc` — `graph-members` decides
     ;; visibility, `update-recipe!` decides editability — so this adds a
     ;; caller-bound operation and no new authority.
     :apply-recipe-delta!
     (fn [{:keys [graph delta]}]
       (let [current (:members (gsvc/graph-members ctx {:graph graph}))
             merged  (studio-graph/apply-members-delta current delta)]
         (if (seq (:findings merged))
           merged
           (gsvc/update-recipe! ctx {:graph graph :members (:members merged)}))))
     :set-default-query! #(gsvc/set-default-query! ctx %)
     ;; Saved queries, the draft set (proposal studio-saved-queries §3.2).
     :list-saved-queries  #(sqsvc/list-saved-queries ctx %)
     :save-saved-query!   #(sqsvc/save-saved-query! (assoc ctx :check (saved-query-check caller)) %)
     :delete-saved-query! #(sqsvc/delete-saved-query! ctx %)
     ;; Graph tokens (owner ruling 2026-10-11): the same three functions the
     ;; Builder page's token panel calls, bound to this caller.
     :graph-tokens       (fn [{:keys [graph]}]
                           (cond-> (graph-tokens* caller)
                             graph (update :tokens (fn [ts] (filterv #(= graph (:graph-id %)) ts)))))
     :issue-graph-token! #(issue-graph-token* caller %)
     :revoke-graph-token! #(revoke-graph-token* caller %)
     ;; Credentials (owner ruling 2026-10-11): the credentials page's own
     ;; functions, bound to this caller. Names in, names out — a value is
     ;; written, never read back. `:provenance` is the MCP door's, from ctx.
     :credentials              (fn [_] (credentials-data* caller))
     :put-credentials!         #(put-credentials* caller %)
     :save-credential!         (fn [{:keys [provenance] :as r}]
                                 (save-variable* caller provenance (dissoc r :provenance)))
     :credential-removal-plan  #(variable-removal-plan* caller %)
     :remove-credential!       #(remove-variable* caller %)
     ;; Sources (owner ruling 2026-10-11): the Sources page's register, its
     ;; api probe and its removal — the page's own handlers run as this caller.
     ;; Connecting and editing are `declare_source`; reading the structure is
     ;; `describe_source`.
     :sources                  (caller-op caller sources-data)
     :probe-api-source         (caller-op caller api-probe!)
     :source-removal-plan      (caller-op caller source-removal-plan-request)
     :remove-source!           (caller-op caller remove-source!)
     ;; Graph lifecycle (owner ruling 2026-10-11, reversing 裁定九 for these):
     ;; the Graphs page's own handlers — duplicate and merge carry the
     ;; Semantic draft, which the bare `gsvc` functions above do not.
     :page-rename-graph!       (caller-op caller rename-graph!)
     :duplicate-graph-plan     (caller-op caller duplicate-graph-plan)
     :page-duplicate-graph!    (caller-op caller apply-duplicate-graph!)
     :page-merge-graphs!       (caller-op caller merge-graphs!)
     :archive-graph!           (caller-op caller #(archive-request! % :archived))
     :restore-graph!           (caller-op caller #(archive-request! % :active))
     :page-delete-graph!       (caller-op caller delete-graph!)
     :rollback-graph!          (caller-op caller rollback-graph!)
     :cut-check                (caller-op caller #(graph-cut-check-data {"graph" (:graph %)}))
     :page-set-default-query!  (caller-op caller set-graph-default-query!)
     :page-set-package-name!   (caller-op caller set-graph-package-name!)
     :page-set-default-graph!  (caller-op caller set-workspace-default-graph!)
     :reclaim-unused-versions! (caller-op caller reclaim-unused-graph-versions!)
     ;; Workspace tokens (owner ruling 2026-10-11): list and revoke only —
     ;; issuing stays a person's act, so a key cannot mint another key.
     :workspace-tokens         (caller-op caller (fn [_] (workspace-tokens-data)))
     :revoke-workspace-token!  (caller-op caller revoke-workspace-token!)
     ;; The Workspace screen's object list (owner 2026-10-11): every pool
     ;; object this workspace owns, including those no graph selects.
     :workspace-objects        (caller-op caller (fn [_] (workspace-objects)))
     ;; The data plane's recent requests (proposal studio-saved-queries §4).
     ;; Workspace-level visibility, the same guard as every graph read.
     :recent-queries       #(recent/recent (assoc ctx :now now-iso) %)
     :recent-query         #(recent/entry (assoc ctx :now now-iso) %)
     :pin-recent-query!    #(recent/pin! (assoc ctx :now now-iso) %)
     :unpin-recent-query!  #(recent/unpin! ctx %)
     :add-capture-rule!    #(recent/add-rule! (assoc ctx :now now-iso) %)
     :remove-capture-rule! #(recent/remove-rule! ctx %)
     :set-package-name!  #(gsvc/set-package-name! ctx %)
     :set-default-graph! #(gsvc/set-default-graph! ctx %)
     :rename-graph!    #(gsvc/rename-graph! ctx %)
     :delete-graph!    #(gsvc/delete-graph! ctx %)
     :set-graph-state! #(gsvc/set-graph-state! ctx %)
     :set-members!     #(gsvc/set-members! ctx %)
     :graph-access     #(gsvc/graph-access ctx %)
     :grant-graph-access!  #(gsvc/grant-graph-access! ctx %)
     :revoke-graph-access! #(gsvc/revoke-graph-access! ctx %)
     :duplicate-graph! #(gsvc/duplicate-graph! ctx %)
     :merge-graphs!    #(gsvc/merge-graphs! ctx %)}))

(defn- publish-service
  "The cut/publish/rollback operations bound to one caller — the same functions
   the MCP endpoint calls (8.4 P3)."
  [caller]
  (let [ctx {:conn (:conn @studio-state)
             :caller caller
             :state @studio-state
             :runtime (runtime-parts)
             :resolve-source (or (:resolve-source @studio-state) runtime/resolve-source)
             :source-bindings-path (or (:source-bindings-path @studio-state)
                                       (str (io/file (studio-home) "source-bindings.edn")))
             :saved-query-stale (saved-query-stale caller)
             :now #(str (.truncatedTo (java.time.Instant/now)
                                      java.time.temporal.ChronoUnit/SECONDS))}]
    {:preflight      #(psvc/preflight ctx %)
     :serving-status #(psvc/serving-status ctx %)
     ;; Reachable from the Builder only. The agent has `inspect_graph`'s
     ;; summary, which answers the same question in the shape it needs; a
     ;; second MCP tool over the same reads would be two names for one fact.
     :versions       #(psvc/versions ctx %)
     :cut-check      #(psvc/cut-check ctx %)
     :export         #(psvc/export ctx %)
     :publish!       #(psvc/publish! ctx %)
     :rollback!      #(psvc/rollback! ctx %)
     ;; GUI only, like the other governance actions: removing a version is
     ;; irreversible and a person confirms it.
     :reclaim-version! #(psvc/reclaim-version! ctx %)
     :reclaim-unused!  #(psvc/reclaim-unused! ctx %)}))

(defn- ui-provenance
  "Trusted provenance for a write arriving through the Builder's own UI
   (8.4 P1b). The entrance is `:ui` and the actor is the SESSION's user —
   neither is read from the request body, for the same reason the MCP endpoint
   does not read them from tool arguments: a caller that can name its own
   entrance can make the audit say anything.

   No `:token-id`: a browser session is not a token, and inventing an id for it
   would make the ledger's `token-id` column mean two different things."
  []
  (or *caller-provenance*
      {:entrypoint :ui
       :actor (auth/request-principal)
       :at (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS))}))

(defn- write-pool-asset!
  "One pool write, guarded by the workspace boundary and filed in the ledger.

   Split out of `write-workspace-asset!` by 8.4.24 裁定十八 so a caller that is
   NOT a browser session can use the same guard: the MCP door establishes its
   workspace and its provenance from a token, not from `auth/*request-user*`,
   and a second copy of «may this workspace write this object» is precisely
   the second semantics D1.8 forbids.

   `subject` rides along to the audit event (see `audit-pool-mutations!`).
   Refusals THROW here, because that is what this app's HTTP layer turns into
   a finding envelope; a caller that would rather read findings than catch
   (the MCP service below) checks with `pool/candidate-actions` first."
  [action {:keys [workspace provenance subject]}]
  (let [conn (:conn @studio-state)
        db (pool/db conn)
        owner (object-workspace db (:type action) (:id action))]
    (when-not (writable-here? db workspace action)
      (throw (ex-info "This model asset belongs to a different workspace"
                      {:code :asset-outside-current-workspace
                       :workspace workspace
                       :asset-workspace owner
                       :type (:type action)
                       :id (:id action)})))
    (let [result (audit-pool-mutations!
                  provenance
                  (merge {:object [(:type action) (:id action)] :action (:action action)}
                         subject)
                  ;; D3.22: the workspace is stamped onto the action HERE, from
                  ;; the caller's session or token — never from anything the
                  ;; caller sent. The pool refuses an action without it.
                  (pool/execute-action conn (assoc action :workspace workspace)
                                       provenance))]
      (when (some #(= :error (:severity %)) (:findings result))
        (throw (ex-info "Model asset update was rejected" {:error result})))
      (runtime/clear!)
      result)))

(defn- write-workspace-asset! [action]
  (write-pool-asset! action {:workspace (require-current-workspace-editor!)
                             :provenance (ui-provenance)}))

;; ---------------------------------------------------------------------------
;; The credentials page (doc 106 §106.14-12): one table over every `${VAR}` the
;; registered sources reference, sql and api alike
;; ---------------------------------------------------------------------------

(defn- require-caller-workspace-editor!
  "`require-current-workspace-editor!` for an explicit caller — the session's
   or a workspace token's (owner ruling 2026-10-11: credentials over MCP)."
  [{:keys [workspace principal]}]
  (let [role (studio-graph/workspace-role (pool/db (:conn @studio-state)) workspace principal)]
    (when-not (contains? #{:owner :editor} role)
      (throw (ex-info "An owner or editor of the current workspace is required"
                      {:code :workspace-editor-required :workspace workspace})))
    workspace))

(defn- credentials-data*
  "Every `${VAR}` name written in any registered source entry, with where it is
   written and whether this instance can answer it.

   Names, paths and booleans — never a value, on the same terms as the sources
   register: this page exists so that a modeller can SEE what is missing and
   fill it in, not so that a browser can read back what was filled in.

   `caller` explicit, so the Builder MCP's `list_credentials` reads exactly
   this (owner ruling 2026-10-11)."
  [caller]
  (let [home  (studio-home)
        model (if-let [conn (:conn @studio-state)]
                (pool/as-dialect (pool/db conn) (:workspace caller))
                (fixture-model))
        rows  (for [[id entry] (:sources model)
                    row (credentials/variable-status home entry)]
                (assoc row :source (str id)))]
    {:variables
     (->> (group-by :name rows)
          (map (fn [[n rs]]
                 {:name n
                  :present? (boolean (some :present? rs))
                  :stored? (boolean (some :stored? rs))
                  :in-env? (boolean (some :in-env? rs))
                  :used-by (vec (sort (distinct (map :source rs))))
                  :paths (vec (sort (distinct (map #(str/join "." (:path %)) rs))))}))
          (sort-by :name)
          vec)
     ;; A value this instance holds for a variable no registered source
     ;; references any more: it is named so it can be removed, because a
     ;; credential nobody asks for is one nobody remembers to rotate.
     :unreferenced
     (vec (sort (remove (set (map :name rows)) (credentials/held-variables home))))}))

(defn- credentials-data [_] (credentials-data* (session-caller)))

(defn- put-credentials*
  "Set or clear credential values by VARIABLE NAME — the one write face for
   both connectors (§106.14-12). A blank value clears the variable.

   `{:values {\"NIFI_TOKEN\" \"…\"}}` in, names held out. Renewing a token is
   this call; the store is re-read on every resolution, so nothing restarts."
  [caller {:keys [values]}]
  (require-caller-workspace-editor! caller)
  (when-not (map? values)
    (throw (ex-info "values must be a map of variable name to value"
                    {:code :credential/malformed})))
  (let [held (credentials/put-vars! (studio-home) values)]
    (runtime/clear!)
    {:held held}))

(defn- put-credentials! [request] (put-credentials* (session-caller) request))

(defn- save-variable*
  "Manually create, update or rename a variable. A rename updates only this
   workspace's Source references as one pool transaction. Values never enter
   the pool or audit trail; the old variable remains for other consumers."
  [caller provenance request]
  (let [workspace (require-caller-workspace-editor! caller)
        home (studio-home)
        {:keys [name previous-name renamed? values]}
        (credentials/prepare-variable-change home request)
        conn (:conn @studio-state)
        db (pool/db conn)
        actions (when renamed?
                  (vec (for [object (pool/objects db)
                             :when (and (= :source (:pool/type object))
                                        (= workspace (:pool/workspace object)))
                             :let [entry (:pool/entry object)
                                   updated (credentials/rename-variable-reference entry previous-name name)]
                             :when (not= entry updated)]
                         {:action :update :type :source :id (:pool/id object)
                          :workspace workspace :expected_revision (:pool/revision object)
                          :entry updated})))
        lookup (credentials/lookup-fn home)]
    (when (seq actions)
      (let [result (binding [spi/*config-lookup* #(or (get values %) (lookup %))]
                     (audit-pool-mutations! provenance
                       (pool/execute-actions conn actions provenance)))]
        (when (some #(= :error (:severity %)) (:findings result))
          (throw (ex-info "The variable was not renamed because a Source update was rejected."
                          {:error result})))))
    (when (seq values) (credentials/put-vars! home values))
    (runtime/clear!)
    {:saved? true :name name :renamed? renamed?
     :updated-sources (mapv :id actions)
     :old-variable-retained? renamed?}))

(defn- save-variable! [request] (save-variable* (session-caller) (ui-provenance) request))

;; ---------------------------------------------------------------------------
;; Build a default model from a source's schema
;; ---------------------------------------------------------------------------

(defn- semantic-source-usage [db workspace]
  ;; Contract bindings live outside the legacy pool. Count a target once per
  ;; Graph, even when alternative bindings reference the same Source.
  (->> (pool/semantic-drafts db)
       (filter #(= workspace (:workspace %)))
       (mapcat (fn [{:keys [graph document]}]
                 (for [[_ binding] (get-in document [:execution :bindings])
                       :let [[kind target] (:binding/for binding)
                             source (:binding/source binding)]
                       :when (and source target (#{:resolver :operation} kind))]
                   [source graph kind target])))
       distinct
       (group-by first)
       (reduce-kv (fn [result source uses]
                    (assoc result source
                           {:resolvers (count (filter #(= :resolver (nth % 2)) uses))
                            :operations (count (filter #(= :operation (nth % 2)) uses))
                            :graphs (count (set (map second uses)))}))
                  {})))

(defn- sources-data
  "Every declared source, for the Sources page's register.

   Deliberately **not** behind a selected graph: the whole point of that page
   is that a database can be connected before any graph knows about it — the
   catalog's graph-context requirement is the reason connecting a source ever
   felt buried. Same discipline as the catalog's list otherwise: which keys are
   set is said, what they are set to never leaves the server."
  [_]
  (let [model (read-model)
        db    (pool/db (:conn @studio-state))
        draft-usage (semantic-source-usage db (current-workspace!))]
    {:sources
     (->> (:sources model)
          (mapv (fn [[id d]]
                  (merge {:id id
                          :draft-usage (get draft-usage id)
                          :used-by (vec (sort (keep #(when (= id (:source-ref %)) (str (:id %)))
                                                    (:resolvers model))))
                          :workspace (object-workspace db :source id)}
                         (select-keys d [:type :label :description])
                         {:keys-set (vec (sort (map name (keys (dissoc d :type :label :description)))))
                          :credential-keys (vec (sort (map name (filter credential-keys (keys d)))))
                          ;; Whether "build a default model from this source"
                          ;; can succeed at all. Only the server knows which
                          ;; connectors are on this instance's classpath, so
                          ;; the browser must be told rather than guess from
                          ;; `:type` — and offering a button that can only fail
                          ;; is worse than not offering it (`spi/supports-describe?`).
                          :describable? (spi/supports-describe? (:type d))
                          ;; Which credentials this instance can actually supply,
                          ;; as opposed to which the model asks for. The gap
                          ;; between the two is why a source stops connecting
                          ;; after it is opened somewhere else.
                          :held-credentials (credentials/holds (studio-home) id)
                          ;; §106.14-12: the SAME row for a sql source and an
                          ;; api one — every `${VAR}` the entry writes at any
                          ;; depth, with present/missing. `:held-credentials`
                          ;; above answers only for the card's three sql keys
                          ;; and cannot see a nested `[:auth :value]`.
                          :variables (credentials/variable-status (studio-home) d)
                          ;; §106.14-11 / need-grammar-v1 §3.6: the attributes a
                          ;; query BINDS by picking an instance — the `{…}` in a
                          ;; templated `:base_url`. The Thing wizard offers them
                          ;; alongside the source's own fields when the modeller
                          ;; says what recognises one (item 18: `[host id]`).
                          ;;
                          ;; The CONNECTOR's own judgement, not a regex here:
                          ;; which positions may carry a placeholder is api-v1's
                          ;; rule, and a second reading of it in this app is the
                          ;; second semantics D1.8 forbids.
                          :bound-attributes (vec (sort (map str (spi/connection-bound-attributes d))))})))
          (sort-by :id)
          vec)}))

(def ^:private shown-operation-keys
  "The operation-block keys the declarations view SHOWS, with their values.

   Every one of them is model structure — where the rows are, which attribute
   comes from which path. What is deliberately absent is as load-bearing:
   `:headers`, `:auth`, `:json_body` and `:query_params` can carry `${VAR}`
   references and templated values, and this view has no use for them. The
   register above says «which keys are set, never what they are set to»; this
   is the same rule applied one level down, so the panel cannot become the one
   place a credential reference is rendered."
  [:method :endpoint :data_path :result_type])

(defn- declared-operation
  "One resolver's declared operation block, projected for display."
  [r]
  (let [src (:source r)
        batch (:batch src)]
    (cond-> {:id (str (:id r))
             :batch? (boolean (:batch? r))
             :output (vec (map str (:output r)))
             ;; Attribute → path, the whole point of this view: an api source
             ;; exposes no schema to READ, but what a resolver DECLARES it
             ;; produces is structure the modeller already wrote down.
             :field-mapping (into (sorted-map)
                                  (map (fn [[k v]] [(str k) (str v)]))
                                  (:field_mapping src))}
      (seq (select-keys src shown-operation-keys))
      (merge (into {} (map (fn [[k v]] [k (str v)]))
                   (select-keys src shown-operation-keys)))

      batch
      (assoc :batch (into {} (map (fn [[k v]] [k (str v)]))
                          (select-keys batch [:parent-key :child-fk :chunk-size])))

      :always
      (assoc :other-keys-set
             ;; Named, not shown — see `shown-operation-keys`.
             (vec (sort (map name (remove (set (conj shown-operation-keys
                                                     :field_mapping :batch :type))
                                          (keys src)))))))))

(defn- source-declarations-data
  "What an api-shaped source has to show when there is no schema to read.

   `describe-schema` is refused for these types and that refusal is right — an
   HTTP upstream exposes no introspectable relational structure. But «nothing
   to READ» is not «nothing to SHOW»: the resolvers that already read this
   source each declare an operation block, and its `:field_mapping` is the set
   of attributes this source produces. The panel counted those resolvers and
   then said there was nothing here.

   Declared, never introspected — so this view proposes nothing and writes
   nothing. Reading it does not open a connection."
  [params]
  (let [id    (get params "source")
        model (read-model)]
    (current-workspace!)
    {:source id
     :type (get-in model [:sources id :type])
     :resolvers (->> (:resolvers model)
                     (filter #(= id (:source-ref %)))
                     (mapv declared-operation)
                     (sort-by :id)
                     vec)}))

(defn- require-readable-source!
  "An editor of the current workspace may read the structure of a source **that
   the current workspace owns**.

   Before D3.22 a source's owner was derived from the resolvers referencing it,
   so a freshly registered source had none, and this gate had to let unowned
   sources through — which meant any editor of any workspace could read the
   table and column names of a database nobody had modelled yet. With ownership
   stored, a source acquires its owner from the write that created it, so there
   is no unowned window and no exemption: reading a schema spends that source's
   credentials, and only its workspace may spend them."
  [source-id]
  (let [conn      (:conn @studio-state)
        db        (pool/db conn)
        workspace (require-current-workspace-editor!)
        model     (read-model)
        ;; Existence is asked of the POOL, not of the caller's scoped model:
        ;; «this Studio has no such source» and «it is not yours» are different
        ;; answers, and collapsing them would tell a caller to go and create a
        ;; source that already exists somewhere else (D3.22 hides the object,
        ;; not the boundary).
        object    (pool/object db :source source-id)
        owner     (:pool/workspace object)]
    (when-not object
      (throw (ex-info "No such source" {:code :source-unknown :source source-id})))
    (when-not (= workspace owner)
      (throw (ex-info "This source belongs to a different workspace"
                      {:code :asset-outside-current-workspace
                       :source source-id
                       :workspace workspace
                       :asset-workspace owner})))
    {:conn conn :db db :workspace workspace :model model
     ;; Resolved for dialling, never for storing: the entry in the pool keeps
     ;; its `${VAR}` names, and this instance's own values are substituted only
     ;; to open the connection (see `open.atlas.studio.credentials`).
     ;; doc 106 §106.14-12: refuses by variable name rather than dialling with a
     ;; hole in the config.
     :source (credentials/resolve-config! (studio-home) source-id
                                          (:pool/entry object))}))

(defn- describe-source-schema!
  "The structure this source exposes. Identifiers and declared types only; the
   connector seam reads no rows."
  [{:keys [source schema names] :as request}]
  (let [source-config (:source (require-readable-source! source))]
    (spi/describe-schema
     source-config nil
     (cond-> {}
       (seq schema) (assoc :schema schema)
       (seq names)  (assoc :names names)
       (contains? request :keys?) (assoc :keys? (:keys? request))))))

(def ^:private connection-shape spi/connection-shape)

(defn- health-refusal
  "The finding a connection that did not answer gets, in the words the card
   has always used. One function because 8.4.24 裁定十八 has TWO doors reaching
   it — the card and `declare_source` — and «the database did not answer» must
   read identically whichever one asked."
  [shape health registered?]
  (let [missing (seq (:missing-vars health))]
    (if missing
      {:code :connector.source/missing-key :severity :error
       :path [:source (:connection-key shape)]
       :message (str "no value is available for "
                     (str/join ", " (map #(str "${" % "}") missing))
                     ": nothing was typed for it here and the environment "
                     "Studio was started from does not define it"
                     (if registered?
                       ". Registered anyway, unverified, because you asked."
                       ". Nothing was registered."))
       ;; Variable NAMES, never their values (8.4.18 裁定十一 / 裁定十八).
       :got (vec missing)}
      {:code :connector.source/unreachable :severity :error
       :path [:source (:connection-key shape)]
       :message (str "the database did not answer ("
                     (name (or (:error/category health) :connection))
                     "); "
                     (if registered?
                       "registered anyway, unverified, because you asked"
                       "nothing was registered"))})))

(defn- declare-source!
  "**The three guarded steps of a source write**, on either door (8.4.24
   裁定十八): validate against the connector's own contract, prove the
   connection, write the pool object — in that order and no other.

   Both the «Connect a database» card (`connect-source!` below, which adds the
   request shape and the schema read around this) and the MCP `declare_source`
   tool call THIS. 8.4.22 裁定十六 ③ — «the entry a person writes and the entry
   an agent writes are the same bytes» — read backwards: one guard path, or the
   two doors eventually differ on which check they run.

   Wholesale replacement, never a merge: `entry` is what the source becomes.
   That is the pool's own semantics for an entry, and it is the whole point of
   the ruling — api-v1's `:tls` and `:ssl_insecure?` are mutually exclusive, so
   a merging write could never move a source onto certificate authentication.

   `:expected-revision` nil declares a NEW source (`:create`, revision 0);
   given, it must equal the current one, and a stale value is refused by the
   pool's own `:pool.conflict/revision-mismatch` — read here through the dry
   path so a plan says it before an apply does.

   `:verify?` false is the card's `register-unverified?` under the tool's name:
   the health check still runs and is still reported, and the reply says
   `:connected? false` rather than pretending.

   `:secrets` is the card's alone — a typed sql password this instance keeps
   (api-v1 has nothing to keep, see `connect-source!`). It is merged into the
   resolved config for the health check and stored only if the write happens;
   it never reaches the entry, the plan or the audit."
  [{:keys [id entry expected-revision verify? apply? workspace provenance secrets sample]}]
  (let [home     (studio-home)
        shape    (connection-shape (some-> (:type entry) name))
        action   {:action (if (nil? expected-revision) :create :update)
                  :type :source :id id :entry entry
                  ;; D3.22: stamped from the caller's workspace (session or
                  ;; token), so the dry path below judges exactly what the
                  ;; write would.
                  :workspace workspace
                  ;; `:create`'s expected_revision is 0 — «this object should
                  ;; not exist yet» is also a statement about a revision, and
                  ;; the pool says both with one key.
                  :expected_revision (or expected-revision 0)}
        ;; The connector's own judgement, on the entry merged with the shell
        ;; that makes it look like something a resolver would run. A literal
        ;; credential is refused HERE — before any connection is opened, which
        ;; is the discipline 裁定十八 states for the write side.
        findings (:findings (spi/validate-source (merge entry (:probe shape))))
        ;; The pool's own dry path, for the same reason `intent/preflight`
        ;; runs it: a preview that cannot say no is not a preview. This is
        ;; where a stale `expected_revision` and a taken id are refused.
        conflict (when (empty? (filter #(= :error (:severity %)) findings))
                   (:findings (pool/candidate-actions (:conn @studio-state) [action])))
        refused  (vec (concat findings conflict))
        base     {:source id :action action :fill [] :written? false :connected? false
                  ;; §106.14-12: what this entry will need on this machine —
                  ;; every `${VAR}` it writes, with present/missing from the
                  ;; store or the environment. 8.4.24 裁定十八 is untouched:
                  ;; `declare_source` still writes NO credential, it only says
                  ;; which ones are missing so the refusal is not a surprise
                  ;; at the first query.
                  :requires {:vars (credentials/variable-status home entry)}}]
    (if (some #(= :error (:severity %)) refused)
      ;; Nothing was reached and nothing was written: the refusal is the whole
      ;; answer, and a health check on an entry we are not going to write
      ;; would be a request nobody asked for.
      (assoc base :findings refused)
      (let [;; Resolved ONLY to open the connection. This value dies with the
            ;; call; `entry` — `${VAR}` names and all — is what is written,
            ;; reported and audited (8.4.18 裁定十一).
            resolved (merge (credentials/resolve-config home id entry) secrets)
            ;; §106.14-12: what is STILL a `${VAR}` after resolution is a
            ;; variable this instance cannot answer. Health-checking with a
            ;; hole in the config produces an unexplained `:down` (or, for
            ;; api's TLS keystore, an NPE); naming the variable is the only
            ;; answer an operator can act on.
            gaps     (credentials/unresolved-findings id resolved)
            ;; **One instance of a host-templated source** (owner 2026-09-07,
            ;; doc 106 §106.14 item 11). A `:base_url` whose host is a
            ;; query-time attribute has no single upstream, so the connector
            ;; REFUSES to check it as a whole; `sample` names the one to check.
            ;; The binding lives for this call — what is written is the entry,
            ;; template and all.
            resolved (cond-> resolved
                       (and (seq sample) (= "api" (some-> (:type entry) name)))
                       (connector-api/bind-base-url sample))
            health   (credentials/check-resolved-connection id resolved)
            ok?      (= :ok (:status health))
            write?   (and apply? (or ok? (not verify?)))
            plan     (assoc base
                            :health health
                            :connected? ok?
                            :findings (cond-> (vec refused)
                                        (seq gaps)              (into gaps)
                                        (and (not ok?) (empty? gaps))
                                        (conj (health-refusal shape health write?))))]
        (if-not write?
          plan
          (let [_ (when (seq secrets) (credentials/put! home id secrets))
                subject (cond-> {:health-status (:status health)}
                          ;; 裁定十八's audit line: one mutation record, plus
                          ;; what the health check said. Names and numbers
                          ;; only — no resolved value goes anywhere near this.
                          (:latency-ms health) (assoc :health-latency-ms (:latency-ms health)))
                written (try
                          (write-pool-asset! action {:workspace workspace
                                                     :provenance provenance
                                                     :subject subject})
                          ;; The workspace boundary refuses by THROWING — this
                          ;; app's HTTP layer turns that into an envelope. A
                          ;; tool caller reads findings, so it becomes one here.
                          (catch clojure.lang.ExceptionInfo e
                            {:refused (or (seq (get-in (ex-data e) [:error :findings]))
                                          [{:code (or (:code (ex-data e)) :source/write-refused)
                                            :severity :error :path [:source :id]
                                            :message (ex-message e)}])}))]
            (if-let [refusal (:refused written)]
              (assoc plan :findings (into (vec (:findings plan)) refusal))
              ;; **No `:mutations` on the way out.** `audited-tool-call` files
              ;; an event for any tool result carrying that key, and this write
              ;; has already been filed with its health verdict — a second
              ;; record for one write would make the ledger count it twice.
              (assoc plan :written? true
                     :revision (:revision written)
                     :registered-unverified? (not ok?)))))))))

(defn- declare-source-request!
  "The EDN editor's validate/connect path. Scope and verification are server-owned."
  [request]
  (declare-source!
   (assoc (select-keys request [:id :entry :expected-revision :apply?])
          :apply? (true? (:apply? request))
          :verify? true
          :workspace (require-current-workspace-editor!)
          :provenance (ui-provenance))))

(defn- connect-source!
  "Register a database connection and immediately say what is behind it.

   The step this feature was missing. Registering a source and reading its
   schema were two unrelated places — one of them reachable only by switching
   an editor into raw EDN to type a JDBC URL — so \"connect a database and
   build a model from it\" was never one thing anybody could follow.

   The order matters and is the whole value: **validate, then prove the
   connection, then write**. A source that was registered and cannot be reached
   is worse than no source at all — it sits in the pool looking finished, and
   the failure surfaces later as a resolver that does not run. So a connection
   that does not answer is reported here and nothing is written.

   `:register-unverified? true` is the deliberate exception, and it is the
   caller's to ask for: a credential that will exist later, a database that is
   down this afternoon, a connection someone wants to declare now and fix
   after. It still runs the write-side validation — a literal credential is
   refused either way — and it still reports what the health check said. What
   it does not do is pretend: the reply carries `:connected? false` and the
   finding stays, so nothing anywhere claims this source has been reached.

   Which connection keys this path offers is the shared `spi/connection-shape`. That is
   not a second schema: the keys are passed through untouched and judged by the
   connector's own `validate-source` (D2.15), so a key this app has never heard
   of is refused by the contract that owns it, not by a copy of it here.

   Keeping a typed credential on this machine is a `sql` affair only — api-v1
   takes `${VAR}` and refuses a literal, so there is nothing to keep. See the
   `typed` binding."
  [{:keys [id type label description prefix register-unverified?] :as request}]
  (let [workspace (require-current-workspace-editor!)
        home      (studio-home)
        shape     (connection-shape type)
        ;; What the modeller typed. It goes to this instance's own store and
        ;; nowhere near the pool.
        ;;
        ;; **Only sql has anything to keep**, and that is the contract's
        ;; doing, not a shortcut here (owner ruling 2026-08-28,
        ;; contracts/connectors/api-v1.md `:auth`): api-v1's credential keys
        ;; take one `${VAR}` and a literal is refused at write time, so there
        ;; is no value to hold for the modeller. Nor could this store name
        ;; one — api's credentials are nested inside `:auth`
        ;; (`[:auth :client_secret]`) while `credential-keys` and
        ;; `credentials/reference` both address top-level keys only. Saying it
        ;; here rather than letting the collection come back empty by accident:
        ;; the empty result is a fact about the contract, and a later key added
        ;; to `credential-keys` must not silently start storing api values.
        typed     (if (:keeps-credentials? shape)
                    (into {} (for [k credentials/credential-keys
                                   :let [v (get request k)]
                                   :when (and (string? v) (seq (str/trim v)))]
                               [k (str/trim v)]))
                    {})
        ;; A value that is already a `${VAR}` reference is a *declaration*, not
        ;; a secret: it stays in the entry and is not stored here.
        reference? (fn [v] (boolean (re-matches #"\$\{[A-Za-z_][A-Za-z0-9_]*\}" (str v))))
        base      (cond-> {:type (or type "sql")}
                    (seq label)       (assoc :label label)
                    (seq description) (assoc :description description))
        ;; Connection keys that are not credential keys — api's whole face.
        ;; Passed through untouched: which keys are legitimate is the
        ;; connector's contract to say (D2.15), not this app's.
        declared  (into {} (for [k (:declared shape)
                                 :let [v (get request k)]
                                 :when (if (string? v) (seq (str/trim v)) (some? v))]
                             [k (if (string? v) (str/trim v) v)]))
        literal   (into (into base declared) typed)
        ;; **The connector decides which values are credentials, not this app.**
        ;; `:username` and `:password` always are; a `:jdbc_url` only is when it
        ;; carries userinfo or credential query parameters, and sql-v1 says a
        ;; credential-free URL is legitimate model content. Duplicating that
        ;; rule here would be a second opinion that drifts; asking for it is the
        ;; same question the write-side asks.
        flagged   (into #{}
                        (comp (filter #(= :connector.source/literal-credential (:code %)))
                              (keep #(last (:path %))))
                        (:findings (spi/validate-source (merge literal (:probe shape)))))
        secrets   (into {} (for [[k v] typed
                                 :when (and (not (reference? v)) (contains? flagged k))]
                             [k v]))
        ;; The entry that goes **into the model**: every credential it uses is a
        ;; `${VAR}` name. That is precisely the fact a production administrator
        ;; needs, and it is why nothing here can carry a secret into an export
        ;; or an artifact. Everything that is not a credential — a
        ;; credential-free URL above all — stays as written, so the model still
        ;; records which database it was built against.
        entry     (reduce (fn [m k]
                            (if (contains? secrets k)
                              (assoc m k (credentials/reference id k))
                              m))
                          literal
                          credentials/credential-keys)
        ;; 8.4.24 裁定十八: the three steps are `declare-source!`'s, not a
        ;; second copy here. What stays in this function is the CARD's shape —
        ;; which keys the form offers, which typed value this machine keeps —
        ;; and the schema read that made "connect a database and build a model
        ;; from it" one thing to follow.
        r (declare-source! {:id id :entry entry
                            ;; The card only ever creates: an existing source
                            ;; is edited in the EDN editor or declared through
                            ;; `declare_source`, both of which say which
                            ;; revision they are replacing.
                            :expected-revision nil
                            :verify? (not register-unverified?)
                            :apply? true
                            :workspace workspace
                            :provenance (ui-provenance)
                            :secrets secrets})]
    (cond
      (not (:written? r))
      (cond-> {:connected? false :findings (vec (:findings r))}
        (:health r) (assoc :health (:health r)))

      (not (:connected? r))
      {:connected? false
       :health (:health r)
       :findings (vec (:findings r))
       :registered-unverified? true
       :source id
       :revision (:revision r)}

      :else
      (let [;; Reading the schema is a *sql* thing, not a connect thing.
            ;; api brings no `describe-schema` on purpose (there is no
            ;; catalog behind an HTTP endpoint), and asking anyway would
            ;; hit the refusing default and turn a connection that
            ;; answered into an error. `:describable? false` is the same
            ;; answer the register already gives for this source, so the
            ;; wizard says "modelled by hand" instead of offering a button
            ;; that can only fail.
            resolved     (merge (credentials/resolve-config (studio-home) id entry) secrets)
            description* (when (spi/supports-describe? (:type entry))
                           (spi/describe-schema resolved nil {:keys? false}))]
        {:connected?  true
         :source      id
         :workspace   workspace
         :revision    (:revision r)
         :health      (:health r)
         ;; Names only — what production will have to configure, so the
         ;; modeller sees the ask before it becomes someone else's problem.
         :variables   (vec (for [k (keys secrets)] (credentials/variable-name id k)))
         :describable? (some? description*)
         :relations    (:relations description*)
         :capabilities (:capabilities description*)
         :keys-read?   (:keys-read? description*)
         :findings     (vec (concat (:findings r) (:findings description*)))}))))

(defn- api-probe!
  "**Step ① of the api wizard** (8.4.22 裁定十六 ③): one real call to a
   registered api source, and the response tree the modeller picks the records
   out of.

   Bounds and audit are the MCP path's, not a second set: the ceiling is the
   connector's own (`connector-api/probe-max-bytes`, 8.4.14 裁定七), the
   response is refused rather than truncated when it exceeds it, and the record
   this returns is built by `tools/probe-audit` — the very record the MCP plan
   carries, which names the path, the parameter NAMES, the status and the size,
   and never the body.

   The sample itself IS returned, because the wizard exists to show it to the
   person who called it; what 裁定七 forbids is the body reaching an audit
   record, a log line or a file, and none of those happen here."
  [{:keys [source path method params sample_size]}]
  (let [{:keys [source] :as ctx} (assoc (require-readable-source! source)
                                        :source-id source)
        _ (when (str/blank? (str path))
            (throw (ex-info "An api probe needs the path to call"
                            {:code :probe-path-missing})))
        result (connector-api/probe source (cond-> {:path path :params params}
                                             method (assoc :method method)
                                             ;; 补裁 2026-09-04: the wizard may ask
                                             ;; for fewer (or more) records; the
                                             ;; default lives in the connector.
                                             sample_size (assoc :sample_size sample_size)))
        sample (:sample result)
        ;; 8.4.23 裁定十七: a body over the ceiling is no longer a refusal — the
        ;; probe read up to the ceiling and says where it found the first
        ;; record. That path is the pre-selection; the heuristic answers for a
        ;; response that was read whole.
        data-path (or (:records-path result) (tools/infer-data-path sample))]
    (when (:over-limit? result)
      ;; Only reached when no COMPLETE record fit in what was read: refused,
      ;; never truncated (裁定七), with the way out — say where the records are
      ;; and the probe stops at the first one instead of reading to the ceiling.
      (throw (ex-info (str "No complete record was found in the first "
                           (quot connector-api/probe-max-bytes (* 1024 1024)) " MiB, which is all a probe "
                           "reads. Point step ② at the records array (data_path) so the probe can "
                           "stop at the first record, or call an endpoint that returns one item.")
                      {:code :probe-over-limit :bytes-read (:bytes-read result)
                       :limit connector-api/probe-max-bytes})))
    {:source (:source-id ctx)
     :path path
     :status (:status result)
     :bytes (:bytes result)
     :bytes-read (:bytes-read result)
     ;; How many records the field table's «seen in n/N» counts against.
     :records (:records result)
     ;; The wizard says «only the first N records were read» when this is set
     ;; (8.4.23 裁定十七) — the response tree it shows is a sample, not the
     ;; whole answer, and a person must not read it as the whole answer.
     :truncated? (boolean (:truncated? result))
     ;; The heuristic's answer, PRE-SELECTED and not applied: the wizard shows
     ;; the tree with this node highlighted and the modeller may pick another.
     :data-path data-path
     :sample sample
     :probe (tools/probe-audit {:source (:source-id ctx) :path path :params params
                                :data-path data-path :data-path-from :heuristic
                                :records (:records result)}
                               result
                               (count (let [rows (if (:truncated? result)
                                                   ;; Already located by the probe.
                                                   sample
                                                   (connector-api/locate-rows sample data-path))]
                                        ;; The union across the sample, which is
                                        ;; what the field table shows (补裁).
                                        (distinct (mapcat keys
                                                          (if (sequential? rows)
                                                            rows
                                                            [rows]))))))}))

(defn- intent-ctx
  "The intent-tool ctx a BROWSER session calls the need-grammar tools with.

   Assembled from the SESSION rather than a token, exactly as `session-caller`
   describes: same services, same planner, different door. 8.4.22 裁定十六 ③
   says the wizard writes through `plan-add-resolver` and not a second
   generator, and the cheapest way to mean it is to call the tool.

   Doc 106 §106.16 widened it from the api wizard's one tool to every sentence
   of need-grammar-v1: the defence named there is that the GUI carries ZERO
   modelling logic and reaches the same `plan-add-*` planners the MCP does
   (D1.8, 「第二套语义」). This ctx is that seam — one assembly, one set of
   guards, two doors."
  []
  (let [caller (session-caller)]
    {:conn (:conn @studio-state)
     ;; D3.22: the intent tools stamp every pool action from `(:workspace ctx)`
     ;; and from nowhere else. The MCP door sets it from the token's record;
     ;; this door sets it from the session, or the wizard's apply writes an
     ;; action with no owner.
     :workspace (:workspace caller)
     ;; The browser door is a workspace EDITOR door (`require-readable-source!`
     ;; above has already established that), which is the write key's
     ;; equivalent — and 裁定十六 ① is that writing implies probing.
     :scope #{:read :write}
     :graph-service (graph-service caller)
     :semantic-service
     (semantic/service (:conn @studio-state) caller runtime/resolve-source
       {:cut-artifact! (fn [{:keys [graph workspace graph-id expected-revision entries]}]
                         (locking (:conn @studio-state)
                           (when-not (= expected-revision (:revision (pool/semantic-draft (pool/db (:conn @studio-state)) workspace graph-id)))
                             (throw (ex-info "Draft changed" {})))
                           (source-bindings/install-missing!
                            (str (io/file (studio-home) "source-bindings.edn"))
                            (let [release (str (version/next-version
                                                (pool/db (:conn @studio-state)) graph))]
                              (into {} (map (fn [[id entry]]
                                              [[workspace graph-id release id] entry])) entries)))
                           (version/cut-semantic-version! (:conn @studio-state)
                             {:graph graph :expected-revision expected-revision
                              :artifact-root (str (studio-home) "/artifacts")
                              :cut-at (str (java.time.Instant/now)) :cut-by (pr-str (:principal caller))})))})
     :probe-source (fn [source-config request] (connector-api/probe source-config request))
     :locate-rows connector-api/locate-rows
     :resolve-source runtime/resolve-source
     ;; 8.4.17 裁定十: what a tool's omitted `graph` falls back to. RESOLVED,
     ;; like the MCP door's, so a pointer left behind by a deleted or renamed
     ;; graph refuses instead of naming a graph that is not there (D3.21).
     :default-graph (studio-graph/default-graph (pool/db (:conn @studio-state))
                                                (:workspace caller))
     ;; **`:ui`, and it has to be** — `pool/impl/audit` closes the entrance set
     ;; at `[:http :mcp :ui]` (8.4 P1b), so the `:builder` this said until
     ;; 2026-09-07 made the pool refuse EVERY apply through this ctx with
     ;; `:pool.provenance/malformed`. The api wizard's plan therefore worked and
     ;; its «add to the graph» never wrote — the refusal is structured and
     ;; correct, and it named a spelling nobody had a reason to look at. The
     ;; browser is the same entrance `ui-provenance` names, so it says so.
     ;; Found by the GUI acceptance run of doc 106 §106.16.
     :provenance {:entrypoint :ui
                  :actor (:principal caller)
                  :at (now-iso)}}))

(def ^:private need-grammar-tools
  "The tools `/api/studio/intent` dispatches, and the capability each needs.

   A WHITELIST, not a passthrough. The browser door carries the same authority
   as a write workspace token, so an open dispatcher would work — but this
   route exists to make doc 106 §106.16 true — 「Studio 的向导与 MCP 说同一套
   十二句」— and naming the sentences here is what keeps that claim readable.
   Everything outside the grammar keeps its own route; the data-plane tools are
   absent because this ctx carries no `:runtime` and they would refuse anyway —
   being refused for the wrong reason is not a guard.

   `:write` additionally requires the session to be a workspace editor, which is
   the browser's equivalent of the write key (8.4.22 裁定十六 ①)."
  {;; A. 东西与属性 (need-grammar-v1 A1–A4)
   "semantic_draft"   :write
   "add_thing"        :write
   "add_attribute"    :write
   "add_metric"       :write
   ;; B. 关系 (B1 / B2 / B4, and B3's link)
   "add_relation"     :write
   "add_link"         :write
   ;; C. 概念 (C1–C4)
   "define_concept"   :write
   ;; D. 治理 (D1 / D2)
   "describe_objects" :write
   ;; 反向翻译: the `things` view the graph page reads (§106.5)
   "inspect_graph"    :read
   ;; The evidence the wizards prefill from — the SAME read an agent makes, so
   ;; a field the GUI offers and a field an agent sees are one answer.
   "describe_source"  :read
   ;; Reading one object back is how a form pre-fills itself for an edit, and
   ;; how a finding's `:repair` names what it is about.
   "read_object"      :read
   ;; **The tools a server-generated `:repair` names** (`impl.repair`, 8.4.21
   ;; 裁定十五 ①). The `things` view offers each finding its repair as one
   ;; click, and a repair whose tool this route will not dispatch is a dead
   ;; button — worse than no button, because it says the fix is one click away
   ;; and then is not. They are outside the twelve sentences on purpose: a
   ;; person does not say them, a check does.
   "change_graph_members" :write
   "remove_objects"       :write
   "update_attribute"     :write
   "update_resolver"      :write})

(defn- intent-tool!
  "ONE route for the twelve sentences (doc 106 §106.16).

   The GUI's wizards and dialogs post `{:tool … :args …}` here and this hands
   both straight to `tools/call` — the very function the Builder MCP endpoint
   calls (through `audited-tool-call`, so the pool writes land in the same
   ledger). Nothing about a namespace, a resolver shape, a `:batch` or a
   `:field_mapping` is decided on this path or in the browser: `plan-add-*`
   decides all of it, once, for both doors.

   Plan by default. `apply` is the caller's, and the GUI only sets it after a
   person has read the plan's `:summary` — but this route does not enforce that
   ordering, because the planners are what guarantee plan and apply run the
   same code (`intent-test` asserts it byte for byte), and a second, weaker copy
   of that guarantee here would be exactly the second semantics D1.8 forbids."
  [{:keys [tool args]}]
  (if-let [capability (get need-grammar-tools tool)]
    (do
      (when (= :write capability)
        (require-current-workspace-editor!))
      (let [ctx (intent-ctx)]
        ;; EDN on this door, so no JSON↔EDN coercion: the browser and the
        ;; planner share a reader, and the coercion the MCP door needs has
        ;; nothing to do.
        (audited-tool-call (tools/registry (:scope ctx)) ctx tool (or args {}))))
    ;; RETURNED, not thrown: every other refusal a caller of this route can
    ;; read is finding-v1 shaped (the planners' own), and an exception here
    ;; would reach the browser as a diagnostics envelope with the findings
    ;; stripped — one route answering refusals in two shapes.
    {:findings [{:code :tools.intent/unknown-tool :severity :error
                 :path [:tool]
                 :message (str "the Builder dispatches only the need-grammar sentences "
                               "through this route")
                 :expected (vec (sort (keys need-grammar-tools)))
                 :got (pr-str tool)}]}))

(defn- api-resolver!
  "**Step ④ of the api wizard**: plan, or apply, ONE `add_thing` — the same
   tool the MCP endpoint mounts, so the entry the wizard writes is the entry an
   agent would have written (8.4.22 裁定十六 ③ / its third exit condition).

   8.4.25 裁定十七 renamed the tool the wizard drives: `add_resolver` became
   `add_thing`, and the wizard's `input` — the field the records are looked up
   BY — is that sentence's `identity`. The wizard offers ONE such field, which
   is what `add_thing` says; a compound key is still reachable from the pool's
   own routes."
  [{:keys [graph relation source path method params data_path sample_size outputs input
           namespace collection_name apply]}]
  (require-current-workspace-editor!)
  (let [ctx (intent-ctx)
        args (cond-> {:graph graph :name relation :source source :from path}
               (seq input) (assoc :identity (first input))
               method (assoc :method method)
               (seq params) (assoc :params params)
               (seq data_path) (assoc :data_path data_path)
               ;; 补裁 2026-09-04: the wizard's step ① probed with a sample
               ;; size, and step ④ must plan from the same sample.
               sample_size (assoc :sample_size sample_size)
               (seq outputs) (assoc :attributes (vec outputs))
               (seq (str namespace)) (assoc :namespace namespace)
               (seq (str collection_name)) (assoc :collection_name collection_name)
               apply (assoc :apply true))]
    (audited-tool-call (tools/registry (:scope ctx)) ctx "add_thing" args)))

(defn- read-object-id
  "An object id off the wire, which travels **printed** — `\":cis.users/name\"`,
   `\"[:a/b :c/d]\"`, `\"\\\"demo-pg\\\"\"` — because a keyword, a string and a
   link's endpoint pair share one field and only EDN text carries all three
   back unchanged.

   Refused by name when it is not text. A caller that sent the value already
   read used to reach `edn/read-string` and get a ClassCastException (a
   keyword) or, worse, a silent misread (a string becoming a symbol): the pool
   holds no such identity, the plan has no actions, and the removal reported
   itself done having written nothing."
  [id]
  (if (string? id)
    (edn/read-string id)
    (throw (ex-info "An object id must arrive as printed EDN text"
                    {:code :pool.action/unknown-entity
                     :findings [{:code :pool.action/unknown-entity :severity :error
                                 :path [:objects :id]
                                 :message "An object id must arrive as printed EDN text, e.g. \":cis.users/name\"."
                                 :got (pr-str id)}]}))))

(defn- removal-plan
  "What removing this object — or this namespace — would do. Writes nothing.

   Shown before it is agreed to, because both operations reach past the thing
   named: removing an attribute edits every resolver that mentions it, and
   removing a namespace takes with it everything that reaches into it. A
   confirmation dialog that says only \"are you sure\" is not a question anyone
   can answer."
  [{:keys [type id namespace objects]}]
  (require-current-workspace-editor!)
  (let [db (pool/db (:conn @studio-state))]
    (cond
      ;; A picked set, for the clearing a prefix cannot express. The client
      ;; names identities and never actions — see `remove-objects!`.
      (seq objects)
      (pool/objects-removal db (mapv (fn [{:keys [type id]}]
                                       [(keyword type) (read-object-id id)])
                                     objects))

      (seq namespace) (pool/namespace-removal db namespace)
      :else (pool/object-removal db (keyword type) (read-object-id id)))))

(defn- foreign-objects
  "Objects in a removal plan that belong to another workspace.

   **Writing and destroying are not symmetric.** Creating a new object under
   any name is additive and reversible, and D3.22 simply stamps it with the
   caller's workspace. Deleting is neither: what it takes is gone, and the
   workspace that authored it is not here to object. So a removal stops at the
   ownership boundary.

   Unowned objects are removable — nobody is being overruled. Since D3.22
   nothing new is unowned; only an object that predates the stamping migration
   could be."
  [db workspace object-keys]
  (vec (for [[t id] object-keys
             :let [owner (object-workspace db t id)]
             :when (and owner (not= workspace owner))]
         {:object [t id] :owner owner})))

(defn- workspace-objects
  "Every pool object this workspace may remove, grouped by kind.

   **Pool objects, not a graph's model.** The Workspace screen has no graph and
   must not acquire one: reading it through a recipe would hide exactly what it
   exists to show — the objects no recipe selects. So this reads the pool
   directly and takes no `graph` argument, which is also what keeps opening the
   screen from disturbing whichever graph the Graph Editor has open.

   Objects another workspace owns are left out rather than listed and refused
   later: `remove-objects!` stops at that boundary anyway (see
   `foreign-objects`), and offering a tick that cannot be honoured is a worse
   answer than not offering it. Unowned objects are in — nobody is overruled."
  []
  (let [workspace (require-current-workspace-editor!)
        db        (pool/db (:conn @studio-state))
        mine?     (fn [o] (= workspace (:pool/workspace o)))
        note      (fn [{:pool/keys [type id entry]}]
                    (case type
                      :namespace (str (:kind entry))
                      :attribute (or (some-> (:target entry) (str " →"))
                                     (str (:type entry)))
                      :resolver  (str (:result_type entry))
                      :mutation  (str (:result_type entry))
                      :metric    (str (:entity entry))
                      ;; The kind of connection, never a word of its config —
                      ;; a source entry carries `:jdbc_url` (B4 exit 2).
                      :source    (str (:type entry))
                      nil))]
    {:workspace workspace
     :objects (vec (for [o (pool/objects db)
                         :when (mine? o)]
                     {:type  (name (:pool/type o))
                      ;; Printed, because a keyword, a string and a link's
                      ;; `[from to]` pair are one field here and only EDN text
                      ;; carries all three back unchanged.
                      :id    (pr-str (:pool/id o))
                      :label (let [id (:pool/id o)]
                               (if (vector? id)
                                 (str/join " → " (map #(subs (str %) 1) id))
                                 (str id)))
                      :note  (note o)}))}))

(defn- remove-objects!
  "Carry out a removal plan, as one unit.

   Re-planned here rather than taking actions from the caller: a client that
   could hand over its own delete list would be `execute-actions` with a
   friendlier name, and the model could be taken apart in ways nothing checked."
  [{:keys [type id namespace objects] :as request}]
  (let [workspace (require-current-workspace-editor!)
        conn (:conn @studio-state)
        plan (removal-plan request)
        foreign (foreign-objects (pool/db conn) workspace (:objects plan))]
    (when (seq foreign)
      (throw (ex-info "This removal reaches objects another workspace owns"
                      {:code :asset-outside-current-workspace
                       :workspace workspace
                       :objects (mapv :object foreign)
                       :owners (vec (sort (distinct (map :owner foreign))))})))
    (if (empty? (:actions plan))
      (assoc plan :removed? false
             :findings [{:code :pool.action/unknown-entity :severity :error
                         :path [:removal]
                         :message "nothing to remove"}])
      (let [prov (ui-provenance)
            ;; D3.22: every action carries the workspace it acts on behalf of,
            ;; taken from the session — the same stamp `write-pool-asset!`
            ;; applies, so one write face means one.
            actions (mapv #(assoc % :workspace workspace) (:actions plan))
            r (audit-pool-mutations!
               prov (pool/execute-actions conn actions prov))]
        (when (some #(= :error (:severity %)) (:findings r))
          (throw (ex-info "Removal was rejected" {:error r})))
        ;; **Finish the job.** The objects are gone; every recipe that named
        ;; them now names a ghost, and the only place that surfaces is the next
        ;; cut — as `:scope/unknown-member`, a refusal that does not mention
        ;; the removal it came from. Reported, not silent: which graphs changed
        ;; is the question a modeller asks straight after "did it delete".
        (let [swept ((:sweep-removed! (graph-service (session-caller))) (:objects plan))]
          (runtime/clear!)
          (assoc plan :removed? true :workspace workspace
                 :swept (vec swept)
                 :findings (vec (:findings r))))))))

(defn- require-editable-graph! [definition]
  (gsvc/require-editable! (pool/db (:conn @studio-state)) (session-caller) definition))

(defn- create-graph!
  "A new graph. `:members` is the recipe — an object list (D3.19); `:selectors`
   is still accepted as a **convenience**, expanded against the pool right here
   the way the editor's batch-add button does. What is stored is always the
   expanded list: a selector never reaches the recipe."
  [request]
  ;; The operation itself is `graph-service` (8.4 P2) — the SAME function the
  ;; MCP endpoint calls. What is left here is the HTTP shape.
  ((:create-graph! (graph-service (session-caller)))
   ;; `:package-name` rides along (doc 104 §8.17.7 D) — optional, and refused
   ;; here by the registry's one test rather than a second one at this edge.
   (select-keys request [:name :members :selectors :package-name])))

(defn- semantic-package-plan! [request]
  (semantic-package/plan (:conn @studio-state) (session-caller) request semantic/checkers))

(defn- semantic-package-import! [request]
  (semantic-package/apply! (:conn @studio-state) (session-caller) request
                           semantic/checkers (ui-provenance)))

(defn- graph-serving-version
  "Draft names need not be portable artifact coordinates. Such drafts cannot
   have a runtime pointer; do not turn listing them into a filesystem error."
  [runtime-store qualified-name]
  (let [parts (when (string? qualified-name) (str/split qualified-name #"/" -1))]
    (when (and runtime-store (= 2 (count parts))
               (every? artifact/portable-segment? parts))
      (rgraph/serving-version runtime-store qualified-name))))

;; ---------------------------------------------------------------------------
;; Removing a declared Source, removing a credential variable (owner 2026-10-04)
;;
;; Two plan → execute pairs on the `removal-plan` / `remove-objects!` model:
;; the plan writes nothing and says who still uses the thing; execute re-plans
;; on the server and refuses while anything does (finding-v1). The one cascade
;; is the owner's narrow 2026-10-04 ruling: a Source takes with it the feeders
;; no graph names (`feeder-plan`) — never a workspace-wide orphan sweep.
;; ---------------------------------------------------------------------------

(defn- source-bindings-path [] (str (io/file (studio-home) "source-bindings.edn")))

(defn- graph-label [db graph-id]
  (or (:graph/qualified-name (studio-graph/graph-by-id db graph-id)) (str graph-id)))

(defn- vars-referenced-by-other-sources
  "Variable names any OTHER source entry in the pool references, across every
   workspace: the credential store is instance-wide, so another workspace's
   source asking for a name is a user of that value too."
  [db home source-id]
  (into #{} (for [o (pool/objects db)
                  :when (and (= :source (:pool/type o)) (not= source-id (:pool/id o)))
                  row (credentials/variable-status home (:pool/entry o))]
              (:name row))))

(defn- graph-namers
  "`[type id]` → the graphs whose recipe names that object, across every
   workspace and archived graphs included: a recipe anywhere is a use."
  [db]
  (reduce (fn [m g]
            (let [members (studio-graph/normalize-members (:graph/members g))]
              (reduce-kv (fn [m t kind]
                           (reduce #(update %1 [t %2] (fnil conj []) (:graph/qualified-name g))
                                   m (get members kind)))
                         m gsvc/pool-type->member-kind)))
          {} (studio-graph/graphs db)))

(defn- feeder-plan
  "The resolvers / operations that read ONLY this Source and that no graph names
   go with it (owner 2026-10-04, narrow and source-scoped — not the workspace
   sweep of OQ-ORPHAN-SWEEP). Every other user blocks.

   The cascade is the pool's own: `object-removal` per feeder, judged against
   the pool minus the batch, says which attributes only the feeders produce;
   such an attribute that nothing but links still names joins the batch, and
   `objects-removal` then derives closure and order. Anything that closure
   reaches beyond feeders / their attributes / links, or that a graph names,
   is a blocker — never silently taken.

   A pool resolver carries one `:source-ref`, so «reads another source too»
   cannot arise from the reference shapes today; the closure check is what
   would catch a future shape that reads two."
  [db workspace source-id cut-graphs]
  (let [users   (:blocked-by (pool/object-removal db :source source-id))
        named   (graph-namers db)
        owners  (into {} (map (juxt (juxt :pool/type :pool/id) :pool/workspace)) (pool/objects db))
        owner   (fn [k] (owners k))
        why     (fn [k] (cond
                          (seq (named k)) {:reason "named by a graph" :graphs (named k)}
                          (and (#{:resolver :mutation} (first k)) (seq cut-graphs))
                          {:reason "read by a cut version" :graphs cut-graphs}
                          (and (owner k) (not= workspace (owner k)))
                          {:reason "owned by another workspace" :graphs []}))
        feeders (filterv #(#{:resolver :mutation} (first %)) users)
        batch   (into #{[:source source-id]} feeders)
        ;; Judged in two batched calls (one pool read each), not one call per
        ;; object: ~80 feeders and ~200 attributes made the per-object form
        ;; take minutes on cis-demo-db (owner 2026-10-04).
        per     (pool/object-removals db feeders batch)
        attrs   (into #{} (comp (mapcat #(concat (:cascade %) (:orphaned %)))) per)
        ;; An attribute still named by something other than a link stays;
        ;; one only links name goes, and the closure takes those links.
        attrs   (let [ordered (vec attrs)
                      judged  (pool/object-removals db ordered (into batch attrs))]
                  (into #{} (keep (fn [[k r]]
                                    (when (every? #(= :link (first %)) (:blocked-by r)) k)))
                        (map vector ordered judged)))
        closure (pool/objects-removal db (vec (into batch attrs)))
        beyond  (remove #(or (batch %) (attrs %) (= :link (first %))) (:objects closure))
        blockers (vec (concat
                       (for [k (concat (remove #(#{:resolver :mutation} (first %)) users) beyond)]
                         {:object k :reason "uses this Source through another path" :graphs []})
                       (for [k (:objects closure) :let [w (why k)] :when w]
                         (assoc w :object k))))]
    {:blockers blockers
     :objects (vec (remove #{[:source source-id]} (:objects closure)))
     :actions (:actions closure)}))

(defn- wire-object [[t id]] {:type (name t) :id (pr-str id)})

(defn- source-removal-plan
  "What removing one declared Source would do, and who still uses it.

   Users, each of which blocks the removal: pool objects reading it that a
   graph still needs (see `feeder-plan`; the rest go with it), Semantic drafts whose
   execution bindings name it, and cut releases whose binding belongs to the
   version their graph is serving right now. Bindings of releases that are not
   serving are listed and removed with the declaration — activating such a
   release afterwards is refused by `source-bindings/resolve-entry`, which the
   plan says up front so nobody finds out at activation."
  [{:keys [source]}]
  (let [workspace (require-current-workspace-editor!)
        id (str source)
        db (pool/db (:conn @studio-state))
        home (studio-home)
        target (first (filter #(and (= :source (:pool/type %)) (= id (:pool/id %)))
                              (pool/objects db)))
        _ (when-not target
            (throw (ex-info "No such Source"
                            {:code :source/unknown
                             :error {:message (str "No declared Source \"" id "\".")
                                     :findings [{:code :source/unknown :severity :error
                                                 :path [:source] :message "No declared Source by this id."
                                                 :got id}]}})))
        foreign (foreign-objects db workspace [[:source id]])
        drafts (->> (pool/semantic-drafts db)
                    (filter #(= workspace (:workspace %)))
                    (filter (fn [{:keys [document]}]
                              (some #(= id (:binding/source (val %)))
                                    (get-in document [:execution :bindings]))))
                    (mapv #(graph-label db (:graph %))))
        slots (source-bindings/source-slots (source-bindings-path) workspace id)
        store (:graph (:runtime @studio-state))
        serving-slot? (fn [[_ graph-id release :as k]]
                        (and (= 4 (count k))
                             (let [qn (:graph/qualified-name (studio-graph/graph-by-id db graph-id))
                                   live (graph-serving-version store qn)]
                               (and live (= (str live) release)))))
        serving (vec (for [[_ graph-id release :as k] slots :when (serving-slot? k)]
                       {:graph (graph-label db graph-id) :version release}))
        cut-graphs (vec (distinct (for [[_ graph-id _ :as k] slots
                                        :when (and (= 4 (count k)) (not (serving-slot? k))
                                                   (studio-graph/graph-by-id db graph-id))]
                                    (graph-label db graph-id))))
        feed (feeder-plan db workspace id cut-graphs)
        blocked (mapv (fn [{:keys [object reason graphs]}]
                        (assoc (wire-object object) :reason reason :graphs graphs))
                      (:blockers feed))
        ;; Owner 2026-10-04: «named by demo/cis» read as a ghost because the
        ;; graph was archived, not deleted. Archived recipes still count as
        ;; uses (deliberate); the plan says which state each blocking graph is
        ;; in and how many blocked objects it accounts for, so the person knows
        ;; to delete or restore it.
        states (into {} (map (fn [g] [(:graph/qualified-name g) g])) (studio-graph/graphs db))
        principal (auth/request-principal)
        admin? (identity/admin? *request-user*)
        ;; Owner 2026-10-04: demo/cis sat in the pre-identity `demo` workspace,
        ;; whose only owner is the retired `studio/local-builder` principal, and
        ;; the panel offered an «open» nobody could follow. Each blocking graph
        ;; now says whose it is and what this requester can do about it.
        blocking-graphs (->> blocked
                             (mapcat (fn [b] (map #(vector % b) (distinct (:graphs b)))))
                             (group-by first)
                             (map (fn [[g rows]]
                                    (let [d (states g)
                                          ws (:graph/workspace d)]
                                      {:graph g
                                       :state (some-> (:graph/state d) name)
                                       :workspace ws
                                       :can-open? (boolean (and ws (studio-graph/workspace-member? db ws principal)))
                                       :can-retire? (boolean (and d admin?))
                                       :objects (count rows)})))
                             (sort-by (juxt #(- (:objects %)) :graph))
                             vec)
        others (vars-referenced-by-other-sources db home id)
        held (set (credentials/held-variables home))
        own-vars (into (set (map :name (credentials/variable-status home (:pool/entry target))))
                       (map #(credentials/variable-name id %) credentials/credential-keys))
        creds (vec (sort (filter #(and (held %) (not (others %))) own-vars)))
        findings (cond-> []
                   (seq foreign)
                   (conj {:code :asset-outside-current-workspace :severity :error :path [:source]
                          :message "This Source belongs to another workspace."
                          :expected workspace :got (:owner (first foreign))})
                   (seq blocked)
                   (conj {:code :source/in-use :severity :error :path [:source :used-by :objects]
                          :message "Pool objects a graph still needs read this Source; remove or rebind them first."
                          :got blocked :refs ["owner 2026-10-04" "D3.19"]})
                   (seq drafts)
                   (conj {:code :source/in-use :severity :error :path [:source :used-by :drafts]
                          :message "Graph drafts still bind this Source; rebind them first."
                          :got drafts :refs ["owner 2026-10-04"]})
                   (seq serving)
                   (conj {:code :source/in-use :severity :error :path [:source :used-by :serving]
                          :message "A serving graph version runs on this Source; activate another version first."
                          :got serving :refs ["owner 2026-10-04"]}))]
    {:source id
     :type (some-> (:type (:pool/entry target)) str)
     :removable? (empty? findings)
     :used-by {:objects blocked :drafts drafts :serving serving}
     :blocking-graphs blocking-graphs
     ;; Feeders no graph names and their pool cascade (owner 2026-10-04).
     :also-removed (mapv wire-object (:objects feed))
     ;; Released bindings that go with it (none serving). Keys only, never entries.
     :bindings (vec (for [[_ graph-id release :as k] slots :when (not (serving-slot? k))]
                      {:graph (graph-label db graph-id)
                       :version (when (= 4 (count k)) release)}))
     ;; Stored values only this Source referenced. Names only.
     :credentials creds
     :findings findings
     ::slots slots
     ::objects (:objects feed)
     ::actions (:actions feed)}))

(defn- public-plan [plan] (dissoc plan ::slots ::actions ::objects))

(defn- admin-retire-graph!
  "Owner 2026-10-04: a Studio administrator archives and deletes a graph in any
   workspace, from the Source removal panel — the sanctioned way out when the
   graph sits in a workspace no account can enter. Asks for the qualified name
   twice (`:confirm` must equal `:graph`) so a stale click cannot delete the
   wrong one. Audited either way as `:graph/admin-retire`."
  [{:keys [graph confirm]}]
  (let [principal (auth/request-principal)
        audit! (fn [outcome extra]
                 (obs/audit-record!
                  (merge {:v 1 :event :graph/admin-retire :at (now-iso)
                          :request-id (obs/request-id) :instance-id (instance-id)
                          :entry :builder-http
                          :actor {:service {:instance-id (instance-id)}
                                  :on-behalf-of principal :asserted-by :verified}
                          :outcome outcome :graph (str graph)}
                         extra)))]
    (try
      (auth/require-studio-admin!)
      (when-not (and (string? graph) (= graph confirm))
        (throw (ex-info "Confirm by repeating the graph's qualified name"
                        {:code :graph/confirm-mismatch :expected graph :got confirm})))
      (let [result (gsvc/admin-retire-graph!
                    {:conn (:conn @studio-state)
                     :prepare-delete! prepare-runtime-graph-deletion!
                     :invalidate! (fn [graph-id]
                                    (runtime/invalidate! #(= graph-id (get-in % [:scope :graph])))
                                    (refresh-environments!))}
                    {:graph graph :actor principal})]
        (audit! :ok {:workspace (:workspace result)})
        (select-keys result [:graph :workspace]))
      (catch clojure.lang.ExceptionInfo e
        (audit! :denied {:reason (or (:code (ex-data e)) :graph/refused)})
        (throw e)))))

(defn- source-removal-plan-request [request]
  (public-plan (source-removal-plan request)))

(defn- remove-source!
  "Remove one declared Source: its pool object, its deployment bindings and the
   credential values only it referenced, plus the feeders no graph names and
   their pool cascade, in the same pool batch — re-planned here, refused while
   anything else uses it (owner 2026-10-04). The pool write goes first because it is
   the step that can be refused (a stale revision); the two instance files
   follow only once the declaration is gone. Values are dropped by name and
   never read back. Audited as `:pool/mutation` through the pool ledger."
  [request]
  (let [workspace (require-current-workspace-editor!)
        plan (source-removal-plan request)]
    (when-not (:removable? plan)
      (throw (ex-info "This Source is still in use"
                      {:code (:code (first (:findings plan)))
                       :error {:message "This Source cannot be removed."
                               :findings (:findings plan)}})))
    (let [conn (:conn @studio-state)
          prov (ui-provenance)
          actions (mapv #(assoc % :workspace workspace) (::actions plan))
          r (audit-pool-mutations! prov (pool/execute-actions conn actions prov))]
      (when (some #(= :error (:severity %)) (:findings r))
        (throw (ex-info "Removal was rejected" {:error r})))
      (let [gone (source-bindings/remove-slots! (source-bindings-path) (::slots plan))
            removed (::objects plan)
            n (fn [t] (count (filter #(= t (first %)) removed)))]
        (when (seq (:credentials plan))
          (credentials/forget-vars! (studio-home) (:credentials plan)))
        (runtime/clear!)
        (assoc (public-plan plan) :removed? true
               :bindings-removed (count gone)
               :removed-counts {:resolvers (n :resolver) :operations (n :mutation)
                                :attributes (n :attribute) :links (n :link)
                                :credentials (count (:credentials plan))}
               :findings (vec (:findings r)))))))

(defn- variable-users
  "Every declared source, in any workspace, whose entry references `name`."
  [db home name]
  (vec (sort (distinct (for [o (pool/objects db)
                             :when (= :source (:pool/type o))
                             row (credentials/variable-status home (:pool/entry o))
                             :when (= name (:name row))]
                         (str (:pool/id o)))))))

(defn- variable-removal-plan*
  "Whether a stored credential value can be removed: refused while any source
   references the variable (owner 2026-10-04). Names and booleans only."
  [caller {:keys [variable]}]
  (require-caller-workspace-editor! caller)
  (let [name (str variable)
        home (studio-home)
        users (variable-users (pool/db (:conn @studio-state)) home name)
        held? (contains? (set (credentials/held-variables home)) name)
        findings (cond-> []
                   (not held?)
                   (conj {:code :credential/not-held :severity :error :path [:variable]
                          :message "This instance holds no stored value for this variable."
                          :got name})
                   (seq users)
                   (conj {:code :credential/in-use :severity :error :path [:variable :used-by]
                          :message "Sources still reference this variable; remove the reference first."
                          :got users :refs ["owner 2026-10-04"]}))]
    {:variable name :held? held? :used-by users
     :removable? (empty? findings) :findings findings}))

(defn- remove-variable*
  "Drop one stored credential value, re-planned and refused while referenced.
   The value is never read into the reply or any log."
  [caller request]
  (let [plan (variable-removal-plan* caller request)]
    (when-not (:removable? plan)
      (throw (ex-info "This variable cannot be removed"
                      {:code (:code (first (:findings plan)))
                       :error {:message "This variable cannot be removed."
                               :findings (:findings plan)}})))
    (credentials/forget-vars! (studio-home) [(:variable plan)])
    (runtime/clear!)
    (assoc plan :removed? true)))

;; The Builder page's entrances: the session as caller.
(defn- variable-removal-plan [request] (variable-removal-plan* (session-caller) request))
(defn- remove-variable! [request] (remove-variable* (session-caller) request))

(defn- held-counts
  "What a graph **holds**, not what its recipe **lists**.

   The two differ by design (D3.19 + scope-projection-v1): a recipe names
   namespaces and attributes; resolvers, links and metrics arrive by closure —
   a resolver joins when all of its outputs are in the graph (§2.4), a link
   when both of its endpoints are (§2.6). Counting the recipe's own `:resolvers`
   set therefore reports zero for every graph that never listed one by hand,
   which is nearly all of them.

   This is the closure's arithmetic, not the closure itself: cheap enough to run
   for every row of the register, and it answers the question the column asks."
  [model members]
  (let [attrs (set (:attributes members))
        ns-set (set (:namespaces members))
        out-attrs (fn [r] (filter keyword? (tree-seq coll? seq (:output r))))]
    {:namespaces (count ns-set)
     :attributes (count attrs)
     :resolvers (count (filter (fn [r]
                                 (let [o (filter attrs (out-attrs r))]
                                   (and (seq o)
                                        (every? attrs (filter #(contains? (:attributes model) %)
                                                              (out-attrs r))))))
                               (:resolvers model)))
     :links (count (filter #(and (contains? attrs (:from %)) (contains? attrs (:to %)))
                           (:links model)))}))

(defn- entity-groups
  "Per namespace: the member ids that make up that entity, and how many of them
   this recipe already names.

   A resolver belongs to an entity when it **produces** one of its attributes —
   that is the relation a modeller means by \"this entity's resolvers\". A
   resolver spanning two entities therefore appears under both, which is
   correct rather than a duplicate: adding either entity should bring the path
   that reaches it, and the recipe is a set, so naming it twice costs nothing.

   **All six groups an entity can own, not three.** Links, metrics and
   mutations used to be left out, so unticking an entity took its namespace,
   its attributes and its resolvers and left its links named — a recipe that
   still points at both ends of a path whose attributes it no longer holds, and
   a graph that refuses to cut (`:scope/unclosed-link`) for a reason the screen
   never showed. A link belongs to both its endpoints' entities, on the same
   grounds a resolver belongs to every entity it produces into."
  [model members]
  (let [attrs-of (fn [ns*] (filterv #(= ns* (namespace %)) (keys (:attributes model))))
        ;; `resolver-output-attributes` already exists in this ns — one reading
        ;; of an :output tree, not two.
        produces (fn [r] (set (resolver-output-attributes (:output r))))
        touches  (fn [m] (set (concat (keys (:params m))
                                      (resolver-output-attributes (:returns m)))))
        in?      (fn [kind id] (contains? (get members kind #{}) id))]
    (vec (for [[ns* def*] (sort-by key (:namespaces model))
               :let [as   (attrs-of ns*)
                     aset (set as)
                     rs   (vec (for [r (:resolvers model)
                                     :when (seq (clojure.set/intersection (produces r) aset))]
                                 (:id r)))
                     ls   (vec (for [l (:links model)
                                     :when (or (contains? aset (:from l))
                                               (contains? aset (:to l)))]
                                 [(:from l) (:to l)]))
                     ms   (vec (for [[id m] (sort-by key (:metrics model))
                                     :when (= ns* (str (:entity m)))]
                                 id))
                     mus  (vec (for [m (:mutations model)
                                     :when (seq (clojure.set/intersection (touches m) aset))]
                                 (:id m)))
                     ids  {:namespaces [ns*] :attributes as :resolvers rs
                           :links ls :metrics ms :mutations mus}
                     total (reduce + (map count (vals ids)))
                     have  (reduce + (for [[k v] ids] (count (filter #(in? k %) v))))]]
           {:entity ns*
            :label (or (:label def*) ns*)
            :members (into {} (for [[k v] ids] [k (mapv pr-str v)]))
            :count total
            :have have
            ;; Three states, not two: **partial** is the shape that actually
            ;; bites — a namespace whose attributes came in but whose resolvers
            ;; did not looks present and answers nothing.
            :state (cond (zero? have)    :out
                         (= have total)  :in
                         :else           :partial)}))))

(defn- recipe-candidates
  "Everything in this workspace's pool, each row saying whether **this graph's
   recipe names it**.

   This is the Graph Editor's tick-list (D3.19). Two things it is careful about:

   - `:in?` is a plain set lookup, not a guess. A recipe is a list, so \"is it in
     this graph\" has one answer and no prefix to reason about.
   - `:missing` counts what the pool holds and the recipe does not. That number
     is the whole cost D3.19 accepts — objects added later do not join by
     themselves — and the point of showing it is that the cost becomes a visible
     to-do rather than a silent divergence."
  [params]
  (let [root (get params "graph")
        definition (selected-graph! {:graph root})
        members (studio-graph/normalize-members (:graph/members definition))
        model (read-model)
        rows (fn [kind ids]
               (let [in (get members kind #{})]
                 (vec (for [id (sort-by pr-str ids)]
                        {:id (pr-str id) :in? (contains? in id)}))))
        groups {:namespaces  (rows :namespaces (keys (:namespaces model)))
                :attributes  (rows :attributes (keys (:attributes model)))
                :resolvers   (rows :resolvers (map :id (:resolvers model)))
                :mutations   (rows :mutations (map :id (:mutations model)))
                :links       (rows :links (map (juxt :from :to) (:links model)))
                :metrics     (rows :metrics (keys (:metrics model)))
                :terms       (rows :terms (keys (:terms model)))
                :units       (rows :units (keys (:units model)))}]
    {:graph root
     :groups groups
     :missing (reduce-kv (fn [m k rs] (assoc m k (count (remove :in? rs)))) {} groups)
     ;; **The unit a modeller thinks in.** A recipe is an object list (D3.19)
     ;; and that is right for the artifact, but nobody decides "these eighteen
     ;; attributes" — they decide "SSO". So the same pool is offered a second
     ;; way, grouped by entity: the namespace, the attributes under it, and
     ;; the resolvers that read or produce them.
     ;;
     ;; This is a VIEW, not a second recipe shape. Ticking a group posts the
     ;; same member ids the tick-list posts; the grouping only spares the
     ;; modeller from assembling them by hand across three tabs.
     :entities (entity-groups model members)}))

(defn- update-graph-members!
  "Tick or untick objects of ONE kind. `:ids` is a vector of printed ids; `:id`
   is still accepted for a single one.

   The whole recipe is rewritten from the edited set, because a recipe is a
   value — sending a delta would need a second shape saying what a delta means,
   and the two would drift.

   **Why a batch at all**, given each toggle already rewrites the whole value:
   because the unit a modeller acts in is not one object. Adding an entity that
   the pool grew later means a namespace, its eighteen attributes and its
   resolvers — one at a time that is twenty round trips across three tabs, and
   the recipe is momentarily half-formed after each one. Taking a list makes
   the whole addition ONE edit, which is also what it is conceptually.

   **`:edits` carries several KINDS in the same request** for the same reason
   one kind carries several ids: ticking an entity is a namespace, its
   attributes and its resolvers together. Sent as three requests they race —
   each reads the same recipe and writes it back whole, so two of the three are
   silently overwritten (fixed 2026-09-02; the Graph Editor's entity rows were
   dropping edits exactly this way)."
  [{:keys [graph kind id ids in? edits]}]
  ((:set-members! (graph-service (session-caller)))
   (if (seq edits)
     {:graph graph :edits (vec edits)}
     {:graph graph :kind kind :ids (vec (or (seq ids) [id])) :in? in?})))

(defn- rename-graph!
  "Change the Studio display name, preserving UUID and deployment coordinates."
  [{:keys [graph name]}]
  ((:rename-graph! (graph-service (session-caller))) {:graph graph :name name}))

(defn- duplicate-graph-plan
  "Read-only, owner-authorized preview. Apply rechecks the snapshot under the
   same connection lock used by semantic draft writes."
  [{:keys [graph name namespace] :as request}]
  (let [caller (session-caller)
        db (pool/db (:conn @studio-state))
        definition (gsvc/require-graph! db caller graph)
        _ (gsvc/require-workspace-owner! db caller)
        draft (pool/semantic-draft db (:workspace caller) (:graph/id definition))
        preview (when (pos? (or (:revision draft) 0))
                  (semantic/duplicate-document (:document draft) "duplicate-preview" namespace semantic/checkers))
        existing (apply clojure.set/union #{} (map (comp semantic/document-asset-ids :document)
                                                  (pool/semantic-drafts db)))
        conflicts (when (:document preview)
                    (clojure.set/intersection existing (semantic/document-asset-ids (:document preview))))]
    (cond
      (or (not (string? name)) (str/blank? name) (str/includes? name "/"))
      {:findings [{:code :semantic.duplicate/invalid-name :severity :error :path [:name]
                   :message "Graph name is required and must not contain /."}]}
      (studio-graph/graph db (str (:workspace caller) "/" name))
      {:findings [{:code :semantic.duplicate/name-conflict :severity :error :path [:name]
                   :message "A Graph already uses this name."}]}
      (seq (:findings preview)) preview
      (seq conflicts)
      {:findings [{:code :semantic.duplicate/namespace-conflict :severity :error :path [:namespace]
                   :message "The new namespace overlaps existing assets. Choose another namespace."
                   :conflicts (vec (sort-by pr-str conflicts))}]}
      :else (semantic/duplicate-plan definition draft request))))

(defn- duplicate-graph!
  "Duplicate a Graph registration and, when present, its Contract 1.4 draft.
   The new namespace rewrites only assets owned by this Graph. Dependencies,
   external Terms and logical Source Slots remain references; credentials and
   serving state are never copied. Legacy Graphs retain the old recipe-copy
   compatibility path."
  [{:keys [graph name namespace plan-token] :as request}]
  (locking (:conn @studio-state)
  (let [caller (session-caller)
        service (graph-service caller)
        db (pool/db (:conn @studio-state))
        source-definition (gsvc/require-graph! db caller graph)
        _ (gsvc/require-workspace-owner! db caller)
        source-draft (pool/semantic-draft db (:workspace caller) (:graph/id source-definition))
        preview (when (pos? (or (:revision source-draft) 0))
                  (semantic/duplicate-document (:document source-draft) "duplicate-preview"
                                               namespace semantic/checkers))
        existing-assets (apply clojure.set/union #{}
                               (for [{:keys [document]} (pool/semantic-drafts db)]
                                 (semantic/document-asset-ids document)))
        conflicts (when (:document preview)
                    (clojure.set/intersection existing-assets
                                              (semantic/document-asset-ids (:document preview))))]
    (cond
      (and plan-token (not= plan-token (:plan-token (semantic/duplicate-plan source-definition source-draft request))))
      {:findings [{:code :semantic.duplicate/stale-plan :severity :error :path [:plan-token]
                   :message "Source Graph changed. Preview the copy again before applying."}]}
      (and plan-token (studio-graph/graph db (str (:workspace caller) "/" name)))
      {:findings [{:code :semantic.duplicate/name-conflict :severity :error :path [:name]
                   :message "The target Graph name is now taken. Choose another name and preview again."}]}
      (seq (:findings preview)) preview
      (seq conflicts) {:findings [{:code :semantic.duplicate/namespace-conflict
                                   :severity :error :path [:namespace]
                                   :conflicts (vec (sort-by pr-str conflicts))}]}
      :else
      (let [created (if preview
                      ;; A Contract 1.4 Graph owns the assets in its Definition.
                      ;; Copying the legacy recipe as well would leave the new
                      ;; Graph with two disagreeing models: rewritten IDs in
                      ;; Definition and the source IDs in Entities.
                      ((:create-graph! service) {:name name :members {}})
                      ((:duplicate-graph! service) {:graph graph :name name}))
            target (:graph created)]
        (if-not preview
          (assoc created :semantic-copied? false)
          (let [copy (semantic/duplicate-document (:document source-draft) (:graph/id target)
                                                  namespace semantic/checkers)]
            (if (seq (:findings copy))
              (do ((:delete-graph! service) {:graph (:graph/qualified-name target)}) copy)
              (let [saved (pool/save-semantic-draft! (:conn @studio-state) (:workspace caller)
                                                     (:graph/id target) 0 (:document copy)
                                                     semantic/checkers (ui-provenance))]
                (assoc created :semantic-copied? true :semantic-revision (:revision saved)
                       :namespace namespace)))))))))
)

(defn- apply-duplicate-graph! [request]
  (if (str/blank? (:plan-token request))
    {:findings [{:code :semantic.duplicate/plan-required :severity :error :path [:plan-token]
                 :message "Preview the copy before applying."}]}
    (duplicate-graph! request)))

(defn- merge-graphs!
  "Create a new Graph from selected drafts, preserving the source Graphs."
  [{:keys [graphs name]}]
  (locking (:conn @studio-state)
    (let [conn (:conn @studio-state)
          caller (session-caller)
          service (graph-service caller)
          _ (gsvc/require-workspace-owner! (pool/db conn) caller)
          sources (mapv #(gsvc/require-graph! (pool/db conn) caller %) (distinct graphs))
          drafts (mapv #(pool/semantic-draft (pool/db conn) (:workspace caller) (:graph/id %)) sources)
          semantic? (some #(pos? (or (:revision %) 0)) drafts)]
      (cond
        (< (count sources) 2) {:findings [{:code :graph-merge-needs-two :message "Select at least two Graphs."}]}
        (some #(= :archived (:graph/state %)) sources)
        {:findings [{:code :graph-archived :message "Restore archived Graphs before merging."}]}
        (and semantic? (some #(not (pos? (or (:revision %) 0))) drafts))
        {:findings [{:code :semantic.merge/mixed-models :message "Choose Graphs with saved Models together. Legacy recipes cannot be mixed with Models."}]}
        (not semantic?) ((:merge-graphs! service) {:graphs graphs :name name})
        :else
        (let [prefix (str "merged." (subs (str (random-uuid)) 0 8))
              preview (semantic/merge-documents (mapv :document drafts) "merge-preview" name prefix)]
          (if (seq (:findings preview)) preview
            (let [created ((:create-graph! service) {:name name :members {}})
                  target (:graph created)
                  prepared (semantic/rebind-document-context (:document preview) (:workspace caller) (:graph/id target))
                  saved (pool/save-semantic-draft! conn (:workspace caller) (:graph/id target) 0 (:document prepared)
                                                    semantic/checkers (ui-provenance))]
              (assoc created :findings (:findings saved) :semantic-revision (:revision saved)))))))))

(defn- delete-graph!
  "Permanently delete an archived Graph, its Semantic draft/history, and cut-version records."
  [request]
  ((:delete-graph! (graph-service (session-caller)))
   {:graph (request-root request)}))

(defn- update-graph-recipe!
  "Two different acts, told apart by which key the caller sent.

   - `:members` **replaces** the recipe. That is what the Recipe panel's tick
     list means: it sends the edited set, whole.
   - `:add-selectors` **adds** — the prefixes are expanded against the pool and
     unioned into what the graph already holds.
   - `:remove-selectors` **takes out** — the same expansion, subtracted. It
     edits the list, never the pool: an object dropped from this recipe stays
     in the pool and in every other graph that names it (D3.19).

   They used to be one path, and it replaced: a button labelled *add by prefix*
   quietly wiped the recipe and left behind only what the prefix matched (or
   nothing at all, when it matched nothing). Naming the act in the request is
   what keeps \"add\" from meaning \"replace\" again."
  [request]
  ((:update-recipe! (graph-service (session-caller)))
   (cond-> {:graph (request-root request)}
     (contains? request :members) (assoc :members (:members request))
     (seq (:add-selectors request)) (assoc :add-selectors (:add-selectors request))
     (seq (:remove-selectors request)) (assoc :remove-selectors
                                              (:remove-selectors request)))))

(defn- set-graph-default-query!
  "Query Studio's opening query for one graph (owner ruling 2026-09-01).

   `:default-query` absent or nil CLEARS it — «this graph has no default» is a
   state a modeller has to be able to get back to, and it is not the same as a
   default that happens to be empty."
  [request]
  ((:set-default-query! (graph-service (session-caller)))
   {:graph (request-root request)
    :default-query (:default-query request)}))

(defn- set-graph-package-name!
  "The release name this graph's versions are sealed under (doc 104 §8.17.7 D).

   `:package-name` absent or nil CLEARS it — and cleared is a real state: the
   next cut then uses the default name (`graph/package-name-for`)."
  [request]
  ((:set-package-name! (graph-service (session-caller)))
   {:graph (request-root request)
    :package-name (:package-name request)}))

(defn- set-workspace-default-graph!
  "Which graph this workspace lands on (D3.21). `:graph` nil clears it."
  [request]
  ((:set-default-graph! (graph-service (session-caller)))
   {:graph (:graph request)}))

(defn- archive-request! [request state]
  ((:set-graph-state! (graph-service (session-caller)))
   {:graph (request-root request) :state state}))

;; ---------------------------------------------------------------------------
;; Per-graph access grants (8.4 P2). Three one-line routes over the SAME
;; service the MCP tools call — an illegal grant must refuse with the same code
;; and the same words at both doors, which is only true if there is one
;; function to refuse from.
;; ---------------------------------------------------------------------------

(defn- graph-access-data [params]
  ((:graph-access (graph-service (session-caller))) {:graph (get params "graph")}))

(defn- grant-graph-access! [{:keys [graph email role]}]
  ((:grant-graph-access! (graph-service (session-caller)))
   {:graph graph :email email :role role}))

(defn- revoke-graph-access! [{:keys [graph email]}]
  ((:revoke-graph-access! (graph-service (session-caller)))
   {:graph graph :email email}))

(defn- graph-data [opts]
  (let [root (or (:root opts) (graph-name))
        {:keys [definition attributes resolvers]} (display-model-for root)
        members (:graph/members definition)]
    (assoc (update (index-graph/build {:resolvers resolvers :attributes attributes}
                                      (dissoc opts :root))
                   :nodes
                   (fn [nodes]
                     (mapv #(assoc % :external? (and (not (:combination? %))
                                                       (not (owned-attribute? members (:attribute %)))) ) nodes)))
           :root root
           :definition (select-keys definition [:graph/members]))))

(defn- attr-text [attribute]
  (str (namespace attribute) "/" (name attribute)))

(defn- catalog-data [query root]
  (let [model (read-model)
        ;; A graph is an ownership boundary, not just a visual filter.  The
        ;; selected graph's catalog therefore has only its own properties and
        ;; resolver outputs; cross-graph inputs remain visible as dependencies
        ;; on the Blueprint, never as silently-owned catalog entries.
        ;; `:definition` comes along because the recipe's own selectors are
        ;; what decide which **registry** entries this graph contains — terms
        ;; are defined independently of the attributes that bind them, so they
        ;; cannot be derived from the attribute list the way links and sources
        ;; are.
        {:keys [attributes resolvers definition]}
        (if (seq root)
          (select-keys (display-model-for root) [:attributes :resolvers :definition])
          (throw (ex-info "A graph must be selected before opening the catalog"
                          {:code :graph-context-required})))
        members (:graph/members definition)
        attribute-definitions (select-keys (:attributes model) attributes)
        resolver-dtos (mapv graph-resolver resolvers)
        ;; Keep a backwards-safe fallback for any resolver join that has not
        ;; yet been registered as a collection attribute.  Explicit latest
        ;; dialect definitions below always win.
        ;; No synthesised pseudo-definitions. A collection's output shape is
        ;; not a property of the attribute — it belongs to the resolver that
        ;; declares it — and merging one in made the shape look like a fact of
        ;; the attribute while quietly losing to the real definition once
        ;; collections became registered attributes. Shapes travel separately,
        ;; keyed by producer, as `:collection-shapes` below.
        catalog-definitions attribute-definitions
        producers (reduce (fn [index {:keys [id output-attrs join]}]
                            (reduce #(update %1 %2 (fnil conj []) id) index
                                    (cond-> output-attrs join (conj (:key join)))))
                          {} resolver-dtos)
        consumers (reduce (fn [index {:keys [id input]}]
                            (reduce #(update %1 %2 (fnil conj []) id) index input))
                          {} resolver-dtos)
        catalog {:dto-version 1
                 :resolvers (mapv (fn [{:keys [id input output-attrs join] :as resolver}]
                                    {:id id :name (or (:label resolver) id)
                                     :description (:description resolver)
                                     ;; The create panel seeds its template from
                                     ;; the source-refs already in use here.
                                     :source-ref (:source-ref resolver)
                                     :connection nil :source-type (get-in resolver [:source :type])
                                     :batch? (boolean (get-in resolver [:source :batch]))
                                     :input (mapv attr-text input)
                                     :output (mapv attr-text output-attrs)
                                     :output-count (count output-attrs)
                                     ;; The output **as written**: a collection
                                     ;; with its members under it, not the
                                     ;; members flattened up beside it. The
                                     ;; reader and the editor then show the
                                     ;; same shape, which is the one the entry
                                     ;; actually has.
                                     :output-tree (mapv (fn [n]
                                                          (cond-> {:attribute (attr-text (:attribute n))}
                                                            (:members n)
                                                            (assoc :members (mapv (comp attr-text :attribute)
                                                                                  (:members n)))))
                                                        (shape/entry->tree resolver))
                                     :join (when join {:key (attr-text (:key join))
                                                       :children (mapv attr-text (:children join))})
                                     :eql-params (vec (keys (:eql-params resolver)))
                                     ;; The worksheet judges completeness from
                                     ;; these two: D2.8 refuses a "list" with
                                     ;; no :limit at seal time, so it is worth
                                     ;; seeing while there is still time.
                                     :result_type (:result_type resolver)
                                     :limit (:limit resolver)})
                                  resolver-dtos)
                 :attributes (mapv (fn [[attribute definition]]
                                     (merge {:attribute (attr-text attribute)
                                             :entity (namespace attribute)
                                             :name (name attribute)
                                             :producers (vec (sort (get producers attribute [])))
                                             :consumers (vec (sort (get consumers attribute [])))}
                                            ;; Everything the attribute actually
                                            ;; declares. `:children` is gone —
                                            ;; it was the pseudo-definition's
                                            ;; key and nothing sets it now.
                                            ;; D2.29: one list, two kinds of
                                            ;; entry. `:target`/`:cardinality`
                                            ;; are what a join declares in
                                            ;; place of `:type`, so the rail
                                            ;; and the dossier can tell them
                                            ;; apart without a second list.
                                            (select-keys definition
                                                         [:type :format :identity?
                                                          :target :cardinality
                                                          :label :description :synonyms :unit
                                                          :term :term-context :values
                                                          :deprecated? :listed? :replaced-by])))
                                   (sort-by (comp attr-text key) catalog-definitions))
                 ;; One entry per (collection, producer). Deliberately not
                 ;; unioned: two producers declaring different members is a
                 ;; conflict the reader must see, not something to merge away.
                 ;; The glossary is a registry with global ids (D2.26), not a
                 ;; per-graph list, so it is not filtered by this graph's
                 ;; selectors: an attribute here may legitimately bind a term
                 ;; defined elsewhere, and a picker that hid it would make that
                 ;; binding unwritable through the form.
                 ;; Links are declared between two attributes, so a link
                 ;; belongs to this graph when the graph owns **either** end:
                 ;; a link from a sales attribute to a master one is a fact
                 ;; sales needs to see, and hiding it because the far end is
                 ;; elsewhere would leave a join nobody can find.
                 :links (->> (:links model)
                             (filter (fn [l] (or (contains? (set attributes) (:from l))
                                                 (contains? (set attributes) (:to l)))))
                             (mapv (fn [l]
                                     (-> l
                                         (assoc :from (attr-text (:from l))
                                                :to (attr-text (:to l))
                                                ;; The pool addresses a link by
                                                ;; its endpoint pair (D2.3).
                                                :id [(attr-text (:from l))
                                                     (attr-text (:to l))])
                                         (update :direction #(or % :bi)))))
                             (sort-by (juxt :from :to))
                             vec)
                 ;; scope-projection-v1 §5: a graph carries the sources its
                 ;; own resolvers reference — a source does not arrive because
                 ;; a namespace prefix reached it.
                 ;; **Declared or used** — a source is an independently
                 ;; defined object, so being referenced is one way into a graph
                 ;; and not the only one. A recipe may also name a source
                 ;; outright, which is how one gets declared here before any
                 ;; resolver reads it.
                 ;;
                 ;; Naming it outright means naming it *exactly*: a source id
                 ;; is a bare string with no `.` or `/` in it (`cis-demo-db`),
                 ;; and a selector matches on those separators
                 ;; (scope-projection-v1 §1) — so `cis.*` does not reach
                 ;; `cis-demo-db` and no prefix ever will. That is a property of
                 ;; the id shape, not a gap in the matcher.
                 ;;
                 ;; `:declared?` says which way it got here, because "nothing
                 ;; reads this yet" is a fact a modeller acts on and an empty
                 ;; `:used-by` alone cannot distinguish it from a stale entry.
                 :sources (let [used (into (sorted-set) (keep :source-ref resolvers))
                                ;; A recipe may name a source outright — that is
                                ;; how one gets declared here before any resolver
                                ;; reads it. Under D3.19 that is a plain set
                                ;; lookup; the old prefix match could never have
                                ;; reached a bare id like `cis-demo-db` anyway.
                                ;;
                                ;; The workspace scope shows the whole register
                                ;; instead: it is every object this workspace
                                ;; owns (D3.22), so a source nothing references
                                ;; yet is still visible — that scope exists
                                ;; precisely so nothing sits invisible with
                                ;; nobody able to fix it (`workspace-model`).
                                ;; :declared? still tells the modeller nothing
                                ;; reads it yet.
                                declared (if (= workspace-scope root)
                                           (into (sorted-set) (keys (:sources model)))
                                           (into (sorted-set)
                                                 (filter (set (:sources members)))
                                                 (keys (:sources model))))]
                            (mapv (fn [id]
                                    (let [d (get (:sources model) id)]
                                      (merge {:id id
                                              :declared? (contains? declared id)
                                              :used-by (vec (sort (keep #(when (= id (:source-ref %))
                                                                          (str (:id %)))
                                                                        resolvers)))}
                                             (select-keys d [:type :label :description])
                                             ;; Which connector keys are set,
                                             ;; without their values.
                                             {:keys-set (vec (sort (map name (keys (dissoc d :type :label :description)))))
                                              :credential-keys (vec (sort (map name (filter credential-keys (keys d)))))
                                              ;; Same fact as the register's,
                                              ;; from the same seam: the
                                              ;; dossier's "build default
                                              ;; model" button reads it here.
                                              :describable? (spi/supports-describe? (:type d))})))
                                  (vec (into (sorted-set) (concat used declared)))))
                 ;; The three kinds the catalog never carried. A modeller
                 ;; who cannot see an object cannot fix it, and `query_model`
                 ;; already reports gaps on some of these — a gap naming an
                 ;; object no screen can open is a gap nobody can close.
                 ;;
                 ;; Metrics belong to a namespace and are scoped by it; units
                 ;; and mutations are registry-shaped, so they follow the same
                 ;; rule terms do — selected by the recipe, or reached because
                 ;; something in the graph uses them.
                 :metrics (let [in-graph? (set (:metrics members))
                                ;; A metric also arrives by closure: its
                                ;; `:entity` namespace being in the recipe is
                                ;; what the projection uses (§2.8), so the
                                ;; screen answers the same way.
                                ns-in? (set (:namespaces members))]
                            (vec (for [[id d] (sort-by key (:metrics model))
                                       :when (or (in-graph? id) (ns-in? (str (:entity d))))]
                                   (merge {:id id} (select-keys d [:label :description :entity
                                                                   :grain :dimensions])))))
                 :mutations (let [owned (set (map :id resolvers))]
                              (vec (for [[id d] (sort-by key (:mutations model))
                                         :let [returns (into #{} (filter keyword?)
                                                             (tree-seq coll? seq (:returns d)))]
                                         :when (some #(contains? (set attributes) %)
                                                     (concat (keys (:params d)) returns))]
                                     (merge {:id id} (select-keys d [:label :description
                                                                     :result_type :source-ref])))))
                 :units (let [listed (set (:units members))
                              used (into #{} (keep #(:unit (get (:attributes model) %))) attributes)]
                          (vec (for [[id d] (sort-by key (:units model))
                                     :when (or (contains? used id) (listed id))]
                                 (merge {:id id :used? (contains? used id)}
                                        (select-keys d [:label :description :symbol])))))
                 ;; Which attributes bind each term. Derived here and never
                 ;; filtered: a term's dossier asks "who uses me", and
                 ;; answering it from the search-narrowed attribute list would
                 ;; say "nobody" whenever a filter is active.
                 :term-bindings (reduce (fn [m [a d]]
                                          (cond-> m
                                            (:term d) (update (:term d) (fnil conj []) (attr-text a))))
                                        {} (select-keys (:attributes model) attributes))
                 ;; The resolvers a dossier needs to name, by id. Same reason.
                 :resolver-index (into {} (for [r resolver-dtos]
                                            [(str (:id r))
                                             {:id (str (:id r))
                                              :name (or (:label r) (str (:id r)))
                                              :source-type (get-in r [:source :type])
                                              :source-ref (:source-ref r)
                                              :result_type (:result_type r)
                                              :input-count (count (:input r))
                                              :output-count (count (:output-attrs r))}]))
                 ;; Type/term/format per attribute, unfiltered — a dossier
                 ;; listing what a resolver returns must not need a request
                 ;; per row.
                 :attribute-index (into {} (for [[a d] (select-keys (:attributes model) attributes)]
                                             [(attr-text a)
                                              ;; `:target` rather than `:type`
                                              ;; for a join (D2.29): a row with
                                              ;; neither would read as an
                                              ;; attribute nobody typed.
                                              (select-keys d [:type :term :format :target])]))
                 ;; A lookup table, never filtered. `:terms` below is the
                 ;; rail's list and the search narrows it; a dossier resolving
                 ;; an attribute's bound term against that list would find
                 ;; nothing whenever a filter is active and report the term as
                 ;; missing from the graph — which was a wrong inference, not a
                 ;; fact. What a list shows and what a reference resolves
                 ;; against are two different things.
                 :term-index (into {} (for [[id d] (:terms model)]
                                        [id (select-keys d [:label :description :format
                                                            :synonyms :values :deprecated?])]))
                 ;; **Selected by the recipe, not derived from attributes.**
                 ;;
                 ;; A term is an independently defined object: it must exist
                 ;; before anything can bind it, and `query_model` reports a
                 ;; term nothing binds as a gap to go and fix. Deriving the list
                 ;; from the attributes that happen to bind one made both
                 ;; impossible — a term could never be created here, and the
                 ;; zero-binding gap named an object no screen could open.
                 ;;
                 ;; So terms are scoped the way attributes are: by the graph's
                 ;; own selectors. A glossary domain is a prefix like any
                 ;; other (`common.audit/*`, scope-projection-v1 §1), so
                 ;; "which terms are in this graph" is a question the recipe
                 ;; already answers.
                 ;;
                 ;; Reference closure still governs the **artifact** (§7): a
                 ;; cut carries the terms its attributes bind. What a graph
                 ;; *contains* and what its artifact *ships* are two questions,
                 ;; and this screen asks the first.
                 :terms (let [listed (set (:terms members))
                              bound (into #{}
                                          (keep #(:term (get (:attributes model) %)))
                                          attributes)
                              in-graph? (fn [id] (or (contains? bound id) (listed id)))]
                          (mapv (fn [[id definition]]
                                  (merge {:id id :bound? (contains? bound id)}
                                         (select-keys definition
                                                      [:label :description :format
                                                       :synonyms :deprecated?])))
                                (filter #(in-graph? (key %))
                                        (sort-by key (:terms model)))))
                 ;; **The pickable terms** (8.4.14, owner 2026-09-03).
                 ;;
                 ;; One table was doing two jobs. `:term-index` above is the
                 ;; whole pool and is never filtered, because RESOLVING a bound
                 ;; term must work even when the term lives outside this graph;
                 ;; the attribute form's picker borrowed it for its OPTIONS,
                 ;; and so offered every term in the workspace. Binding one
                 ;; from outside the graph's visibility passes here and is
                 ;; refused at cut time, where the `:term` points at a term the
                 ;; artifact does not carry (D2.26 的可见域 = 本 graph + 钉版本
                 ;; 引用的传递闭包).
                 ;;
                 ;; So the options are scoped like the rail's list — and unlike
                 ;; it, NOT narrowed by the search box: a picker that shrank
                 ;; while someone searched the rail would hide the very term
                 ;; they were looking for.
                 ;;
                 ;; The pinned-reference half of the visibility rule is not
                 ;; computed here: nothing in this file reads a graph's pinned
                 ;; references, and inventing a second closure for them would
                 ;; be the drift this repo keeps paying for. What this offers
                 ;; is this graph's own terms, which is strictly narrower than
                 ;; the visibility rule — a picker that offers too few is a
                 ;; nuisance, one that offers too many is a cut that fails.
                 :term-options (let [listed (set (:terms members))
                                     bound (into #{}
                                                 (keep #(:term (get (:attributes model) %)))
                                                 attributes)]
                                 (mapv (fn [[id definition]]
                                         (merge {:id id}
                                                (select-keys definition
                                                             [:label :description :format
                                                              :synonyms :deprecated?])))
                                       (filter #(or (listed (key %)) (bound (key %)))
                                               (sort-by key (:terms model)))))
                 ;; No `:navigations` list any more (D2.29). A join root is an
                 ;; ordinary attribute carrying `:target`, so it is already in
                 ;; `:attributes` above — projected, owned and searched by the
                 ;; recipe exactly like every other attribute. The separate
                 ;; list existed because a join root the rail could not find is
                 ;; one nobody queries (demo-ch's `user_posts` sat published
                 ;; and invisible, 2026-08-31); one registry answers that
                 ;; without a second list to keep in step.
                 :collection-shapes (mapv (fn [s]
                                            {:collection (attr-text (:collection s))
                                             :members (mapv attr-text (:members s))
                                             :producer (:producer s)})
                                          (shape/collection-shapes
                                           {:resolvers resolvers}))}
        ;; **The registered namespaces, not the namespaces attributes happen to
        ;; mention.** Grouping the attribute list loses a namespace the moment
        ;; it has no attributes — which is exactly the state a namespace is in
        ;; right after it is created, and the state it is left in when its last
        ;; attribute is removed. Both are moments when someone needs to see it.
        catalog (let [listed-ns (set (:namespaces members))
                      by-entity (group-by :entity (:attributes catalog))
                      registered (filter (comp listed-ns key) (:namespaces model))
                      entity-of (fn [ns-str]
                                  (let [attrs (get by-entity ns-str [])
                                        d (get (:namespaces model) ns-str)]
                                    (merge {:entity ns-str
                                            :attribute-count (count attrs)
                                            :identity-keys (->> attrs (filter :identity?)
                                                                (mapv :attribute))}
                                           (select-keys d [:label :kind :description :synonyms]))))]
                  (assoc catalog :entities
                         (->> (into (sorted-set)
                                    (concat (keys by-entity) (map key registered)))
                              (mapv entity-of)
                              vec)))
        q (some-> query str/lower-case)]
    (if (str/blank? q)
      catalog
      ;; Match on what a thing **is**, not on what it is related to.
      ;;
      ;; This used to be `pr-str` over the whole entry, so searching a
      ;; collection's name matched every attribute carrying that resolver in
      ;; `:producers` — typing `all_customers` returned the collection **and
      ;; all of its members**, which reads as though the members were named
      ;; that. Relationships stay searchable where they are the question a
      ;; reader actually has: on the resolvers tab, "which resolver touches
      ;; this attribute" is the point.
      (let [match? (fn [fields x]
                     (str/includes? (str/lower-case (pr-str (select-keys x fields))) q))]
        (-> catalog
            ;; `:target` is part of what a join attribute **is** (D2.29), so it
            ;; is matched here rather than left to the retired navigation list.
            (update :attributes
                    #(filterv (partial match? [:attribute :name :entity :label
                                               :description :synonyms :term :target]) %))
            (update :resolvers
                    #(filterv (partial match? [:id :name :description :source-ref
                                               :input :output]) %))
            (update :entities #(filterv (partial match? [:entity :label]) %))
            (update :links #(filterv (partial match? [:from :to :description]) %))
            (update :sources #(filterv (partial match? [:id :type :label :description]) %))
            (update :terms
                    #(filterv (partial match? [:id :label :description :synonyms]) %))
            (update :metrics #(filterv (partial match? [:id :label :description :entity]) %))
            (update :mutations #(filterv (partial match? [:id :label :description]) %))
            (update :units #(filterv (partial match? [:id :label :description :symbol]) %)))))))

(defn- local-resource [name]
  ;; During development Clojure can otherwise resolve an old copy from
  ;; target/classes before Shadow's freshly emitted bundle in resources/.  The
  ;; filesystem copy is the hot-reload contract; packaged production falls
  ;; through to the classpath resource below.
  (let [file (io/file "resources" name)
        help (when (str/starts-with? name "help/")
               (io/file "../.." "docs" name))]
    (cond (.isFile file) file
          (and help (.isFile help)) help)))

(defn- resource-text [name]
  ;; Two places, and the order is the hot-reload contract: the filesystem copy
  ;; under `resources/` wins during development, the packaged classpath
  ;; resource answers in production.
  ;;
  ;; A third fell away on 2026-08-31: a relative path into the sibling
  ;; `open-atlas-studio` checkout, from before those assets moved in here. It
  ;; could not fire — every asset this serves is present locally — and it made
  ;; a packaged Studio's behaviour depend on what sat next to the repository.
  (slurp (or (local-resource name)
             (io/resource name)
             (throw (ex-info "Missing Studio resource" {:resource name})))))

(defn- resource-bytes [name]
  (with-open [in (io/input-stream (or (local-resource name)
                                       (io/resource name)
                                       (throw (ex-info "Missing Studio resource" {:resource name}))) )]
    (.readAllBytes in)))

(defn- studio-users-data []
  (let [db (pool/db (:conn @studio-state))
        workspace (current-workspace!)
        owner? (= :owner (studio-graph/workspace-role db workspace (auth/request-principal)))]
    ;; A Studio administrator manages accounts everywhere.  An owner needs the
    ;; same local user directory only while managing the workspace they have
    ;; actively selected, so they can add an existing teammate without making
    ;; that directory visible to ordinary members.
    (when-not (or (identity/admin? *request-user*) owner?)
      (throw (ex-info "A Studio administrator or current workspace owner is required"
                      {:code :studio-user-directory-forbidden
                       :workspace workspace})))
    {:users (mapv identity/public-user (identity/users db))}))

(defn- create-studio-user! [request]
  (auth/require-studio-admin!)
  (let [result (identity/create-local-user! (:conn @studio-state) request)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      result)))

(defn- update-studio-user! [request]
  (auth/require-studio-admin!)
  (let [result (identity/update-user! (:conn @studio-state) request)]
    (if-let [error (:error result)] (throw (ex-info (:message error) error)) result)))

(defn- reset-studio-user-password! [{:keys [user-id new-password]}]
  (auth/require-studio-admin!)
  (let [result (identity/reset-password! (:conn @studio-state) user-id new-password)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      result)))

(defn- set-studio-user-sso-email!
  "Doc 101 §8.16.9 item 3: an administrator links an existing account by
   pre-registering the address its identity provider will assert. The event
   names the actor and the target by id; the address is a value (rule 7)."
  [{:keys [user-id email]}]
  (auth/require-studio-admin!)
  (let [result (identity/set-sso-email! (:conn @studio-state) user-id email)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      (do (obs/log-event! (if (get-in result [:user :sso-email])
                            :studio/user-sso-preregistered
                            :studio/user-sso-preregistration-cleared)
                          {:status :ok
                           :actor (:subject (auth/request-principal))
                           :user-id user-id})
          result))))

(defn- restore-studio-user! [{:keys [user-id]}]
  (auth/require-studio-admin!)
  (let [result (identity/restore-user! (:conn @studio-state) user-id)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      result)))

(defn- remove-studio-user! [{:keys [user-id]}]
  (auth/require-studio-admin!)
  (let [result (identity/remove-user! (:conn @studio-state) *request-user* user-id)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      result)))

(defn- require-workspace-owner-for! [workspace]
  (let [db (pool/db (:conn @studio-state))
        principal (auth/request-principal)]
    (when-not (= workspace (current-workspace!))
      (throw (ex-info "Select the target workspace before changing its membership"
                      {:code :workspace-outside-current-context
                       :current-workspace (current-workspace!)
                       :workspace workspace})))
    (when-not (= :owner (studio-graph/workspace-role db workspace principal))
      (throw (ex-info "Only the workspace owner may change membership"
                      {:code :workspace-owner-required :workspace workspace})))
    principal))

;; ---------------------------------------------------------------------------
;; Workspace membership audit (8.10 P6)
;;
;; Until now, changing who can reach a workspace — and at what role — left no
;; record anywhere: the owner could promote, demote or withdraw access and
;; afterwards nothing said who did it or when. Graph lifecycle has the deploy
;; event chain and tokens have the ledger; membership had neither.
;;
;; Refusals are audited too, not just successes. S5.3 calls the refusal trail
;; «who is knocking on which door» and makes it the data source for deciding
;; whether someone should have been let in — a trail of only successes cannot
;; answer that.
;; ---------------------------------------------------------------------------

(defn- audit-membership!
  [event {:keys [workspace member role previous-role outcome reason]}]
  (obs/audit-record!
   (cond-> {:v 1
            :event      event
            :at         (now-iso)
            :request-id (obs/request-id)
            :instance-id (instance-id)
            :entry      :builder-http
            ;; audit-event-v1 §4: the service is this instance's control plane,
            ;; the human who asked is :on-behalf-of, and :asserted-by says which
            ;; of the two was actually checked here.
            :actor      {:service      {:instance-id (instance-id)}
                         :on-behalf-of (auth/request-principal)
                         :asserted-by  :verified}
            :outcome    outcome
            :workspace  workspace}
     member        (assoc :member member)
     role          (assoc :role role)
     previous-role (assoc :previous-role previous-role)
     reason        (assoc :reason reason))))

(defn- audited-membership-change
  "Run `f`, recording the outcome either way. A refusal carries the machine
   readable :reason the caller was refused with — never the values involved."
  [event subject f]
  (try
    (let [result (f)]
      (audit-membership! event (assoc subject :outcome :ok))
      result)
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (audit-membership! event (assoc subject
                                        :outcome :denied
                                        :reason (or (:code d)
                                                    (get-in d [:finding :code])
                                                    :workspace/refused))))
      (throw e))))

(defn- remove-studio-workspace-member!
  "Withdraw a member's access. The module refuses to remove the last owner —
   a workspace nobody can manage has no repair path — so that rule is not
   restated here; it is enforced where the membership actually lives."
  [{:keys [workspace email]}]
  (audited-membership-change
   :workspace/member-remove {:workspace workspace :member email}
   (fn []
  (let [conn (:conn @studio-state)
        db (pool/db conn)
        _ (require-workspace-owner-for! workspace)
        target (or (identity/user-by-email db email)
                   (throw (ex-info "No Studio user with that email"
                                   {:code :studio-user-not-found :email email})))
        result (studio-graph/remove-workspace-member!
                conn workspace (:principal (identity/public-user target)))]
    (when-let [finding (first (:findings result))]
      (throw (ex-info (:message finding) {:error {:findings (:findings result)}
                                          :finding finding})))
    {:status :removed :workspace workspace :email email}))))

(defn- add-studio-workspace-member! [{:keys [workspace email role]}]
  (let [conn (:conn @studio-state)
        db (pool/db conn)
        principal (auth/request-principal)
        target (identity/user-by-email db email)
        role (or role :member)
        ;; The UI's role picker posts through this same endpoint, so «add» and
        ;; «change this person's role» arrive identically. They are different
        ;; facts to anyone reading the trail later, so they are told apart here
        ;; — before the write, while the previous role is still knowable.
        previous-role (when target
                        (studio-graph/workspace-role
                         db workspace (:principal (identity/public-user target))))
        event (if previous-role :workspace/member-role-change :workspace/member-add)]
    (audited-membership-change
     event {:workspace workspace :member email :role role :previous-role previous-role}
     (fn []
    (when-not (= workspace (current-workspace!))
      (throw (ex-info "Select the target workspace before changing its membership"
                      {:code :workspace-outside-current-context
                       :current-workspace (current-workspace!)
                       :workspace workspace})))
    (when-not (= :owner (studio-graph/workspace-role db workspace principal))
      (throw (ex-info "Only the workspace owner may add Studio members"
                      {:code :workspace-owner-required :workspace workspace})))
    (when-not target
      (throw (ex-info "Create the local Studio user before adding them to a workspace"
                      {:code :studio-user-not-found})))
    (when-not (contains? #{:member :editor} role)
      (throw (ex-info "Workspace owner assignment is not available through invitations"
                      {:code :workspace-role-invalid :role role})))
    (let [result (studio-graph/add-workspace-member!
                  conn workspace {:principal (:principal (identity/public-user target))
                                  :role role
                                  :attributes {:email (:email target) :name (:name target)}})]
      (if-let [finding (first (:findings result))]
        (throw (ex-info (:message finding)
                        {:code (:code finding) :finding finding}))
        result))))))

;; ---------------------------------------------------------------------------
;; Observability (8.10.6)
;;
;; The plumbing landed in 8.10; these three reads are the door to it. Who may
;; see an entry is a SECURITY property, not a page option, so the filtering is
;; enforced here and the pages are only allowed to render what they are handed.
;; ---------------------------------------------------------------------------

(defn- event-workspace
  "Which workspace an audit event belongs to, or nil when it belongs to none.

   A qualified graph name is «workspace/graph» (D3.10), so the first segment IS
   the workspace. nil is not «unknown, show it anyway» — see obs-scope!."
  [{:keys [workspace graph] :as e}]
  (or workspace
      (some-> (or graph (get-in e [:authorization :graph-id]))
              str (str/split #"/") first not-empty)))

(defn- obs-scope!
  "→ {:admin? bool :workspaces #{…}}. Throws when the caller may see nothing.

   Rule (owner ruling 2026-08-31): a Studio admin sees everything; a workspace
   owner sees the events of the workspaces they own. Everyone else sees nothing
   — an audit trail names people, their entry points and what they were refused."
  []
  (let [db (pool/db (:conn @studio-state))
        principal (auth/request-principal)
        admin? (identity/admin? *request-user*)
        owned (into #{} (comp (filter #(= :owner (studio-graph/workspace-role db % principal)))
                              (map str))
                    (map :workspace (studio-graph/workspaces db)))]
    (when-not (or admin? (seq owned))
      (throw (ex-info "Observability is for Studio administrators and workspace owners"
                      {:code :observability-forbidden})))
    {:admin? admin? :workspaces owned}))

(defn- visible-events
  "Filter to what this caller may see.

   An event with NO workspace (an authentication failure, a read refused before
   the token resolved a graph) is admin-only on purpose: showing another
   tenant's refusals to a workspace owner is the leak, not the feature."
  [{:keys [admin? workspaces]} events]
  (if admin?
    (vec events)
    (filterv #(contains? workspaces (event-workspace %)) events)))

(def ^:private obs-keyword-fields
  "从流水读回来的事件是 JSON 解析的结果：`:outcome` 是 `\"ok\"` 而不是 `:ok`。
   这几个字段的**值本来就是限定 keyword**（audit-event-v1 §2/§3 的封闭词表）,
   所以读回时还原它们——不还原的话，重启后的那一批在页面上会**静静地**少掉
   状态徽章与事件分类，而数据其实一点没少。"
  [:event :outcome :reason :entry :op :role :previous-role])

(defn- rekeywordize
  "只还原上面那几个字段，且只在它们是字符串时。其余一律不动——猜哪个字符串
   「本来是 keyword」会把一个人的名字变成一个符号。"
  [e]
  (reduce (fn [m k]
            (let [v (get m k)]
              (if (string? v) (assoc m k (keyword v)) m)))
          e
          obs-keyword-fields))

(defn- obs-activity-data
  "Recent audit events, or one subject's history when asked by (kind, id).
   The subject lookup reads the durable index, so it survives rotation and a
   restart — that is the whole reason the index exists."
  [{:strs [kind id limit]}]
  (let [scope (obs-scope!)
        n (min (or (some-> limit parse-long) 200) 500)
        dir (str (store/subsystem-dir (:instance (:runtime @studio-state)) "obs"))
        events (map rekeywordize
                    (if (and kind id)
                      (reverse (obs/audit-for-subject dir (keyword kind) id))
                      (obs/audit-recent)))]
    {:events (let [visible (vec (take n (visible-events scope events)))
                   details (obs/query-details (studio-home) visible)]
               (mapv #(assoc % :queries (vec (filter (fn [q] (= (:graph %) (:graph q)))
                                                     (get details (:request-id %))))) visible))
     :scope (cond-> {:admin? (:admin? scope)}
              (not (:admin? scope)) (assoc :workspaces (vec (sort (:workspaces scope)))))}))

(defn- obs-logs-data
  "The tail of the structured log. Only a Studio admin: app.jsonl is the whole
   process's log — module lines, connector failures, other workspaces' errors —
   and it carries no workspace to filter on."
  [{:strs [limit level q]}]
  (auth/require-studio-admin!)
  (let [n (min (or (some-> limit parse-long) 200) 1000)
        f (io/file (studio-home) "logs" "app.jsonl")
        lines (if (.isFile f) (with-open [r (io/reader f)] (vec (line-seq r))) [])
        parsed (into []
                     (comp (map #(try (json/parse-string % true) (catch Throwable _ nil)))
                           (filter map?)
                           (filter #(or (str/blank? (str level)) (= level (:level %))))
                           (filter #(or (str/blank? (str q))
                                        (str/includes? (str/lower-case (pr-str %))
                                                       (str/lower-case q)))))
                     lines)]
    {:lines (vec (take-last n parsed))
     :present? (.isFile f)
     :path (str f)}))

(defn- obs-metrics-data
  "In-process counters. They reset on restart BY DESIGN (the process is the
   source), which is why the page says so rather than implying history."
  []
  (obs-scope!)
  {:uptime-ms (obs/metrics-uptime-ms)
   :counters (obs/metrics-counter-snapshot)
   :rejections (obs/metrics-rejections)
   :latency (obs/metrics-latency-stats)
   ;; 端点挂在**数据面**监听器上（默认只在 loopback），与 /health 并排。
   ;; 地址由服务端说——页面猜一个端口，改了配置就是一个指向别处的链接。
   :prometheus-url (str (data-plane-scheme) "://127.0.0.1:"
                        (or (env "OPEN_ATLAS_STUDIO_DATA_PORT") "9632")
                        "/metrics")})

(def ^:private missing-attributes-key
  :com.wsscode.pathom3.connect.runner/attributes-missing)
(def ^:private pathom-phase-key :com.wsscode.pathom3.error/phase)
(def ^:private pathom-cause-key :com.wsscode.pathom3.error/cause)

(defn- exception-details [^Throwable error]
  ;; The Result pane needs enough evidence to correct a model or request, but
  ;; must not echo a source config, credentials, SQL text, or returned entity
  ;; values.  The allow-list below is deliberately operational metadata only.
  (loop [current error details []]
    (if (or (nil? current) (>= (count details) 8))
      {:causes details}
      (let [data (ex-data current)
            missing (some->> (get data missing-attributes-key) keys (mapv str))
            detail (cond-> {:type (.getName (class current))
                            :message (or (.getMessage current) "No message")}
                     (seq missing) (assoc :missing-attributes missing)
                     (get data :error/category) (assoc :category (str (get data :error/category)))
                     (get data :resolver-id) (assoc :resolver-id (get data :resolver-id))
                     (get data :source-type) (assoc :source-type (get data :source-type))
                     (get data :sql-state) (assoc :sql-state (get data :sql-state))
                     (get data :timeout-ms) (assoc :timeout-ms (get data :timeout-ms))
                     (get data :placeholder) (assoc :placeholder (get data :placeholder))
                     (get data :code) (assoc :code (str (get data :code)))
                     (get data pathom-phase-key) (assoc :phase (str (get data pathom-phase-key)))
                     (get data pathom-cause-key) (assoc :cause (str (get data pathom-cause-key))))]
        (recur (.getCause current) (conj details detail))))))

;; Query Studio 的每一次运行也是一次数据读取。它走的是 Builder 的编排环境而不是
;; 数据面（`instance/answer`），所以数据面那处审计看不见它——「在 Query Studio 里
;; 查了什么」曾经一处都没记。门是 Builder 的监听器，事件仍是 `:access/query`：
;; 门回答「从哪儿进来」，事件回答「做了什么」。
(def ^:private statements-summary
  "回执的可留档摘要——**定义在 `connector-spi`**（回执的形状归它，那么
   「哪些可以留下」也归它）。这里曾经有一份自己的白名单；数据面也要记步骤之后
   就成了两份，而这种重复的代价是某一处悄悄多带了 `:sql`。"
  spi/statement-audit-summary)

(defn- eql-shape-of
  "请求问了哪些键——**只有键名**，不含任何值（audit-event-v1 §2.2 的 :eql-shape）。"
  [{:keys [entity query]}]
  {:entity-keys (vec (sort (filter qualified-keyword? (keys (or entity {})))))
   :query-keys (->> (tree-seq coll? seq query)
                    (filter qualified-keyword?)
                    distinct sort vec)})

(defn- audit-query-run!
  "One data read, recorded.

   **`principal` is an argument, not the session** (8.4.18 裁定十一 ⑤b). It
   used to call `auth/request-principal`, which THROWS when there is no
   browser cookie — and the whole body is wrapped in a `catch Throwable`, so
   on the MCP door every draft query silently wrote nothing. «Draft failures
   are not audited» was that line: the record was not missing by design, it
   was being thrown away. The caller knows who it is on both doors, so it
   says."
  [{:keys [graph outcome reason latency-ms entities trace eql-shape statements entry
           principal request]}]
  (obs/log-event! :query-executed
                  (merge obs/*query-source*
                         {:execution-target :draft :eql (pr-str request)
                          :principal principal :graph graph :entry (or entry :builder-http)
                          :outcome outcome :reason reason :latency-ms latency-ms}))
  ;; 指标与审计从**同一处**记：`/metrics` 上的数与流水里的条数必须能对上,
  ;; 对不上的那天没人知道该信哪一个。
  (try
    (obs/metrics-record-query! {:request-id (obs/request-id)
                                :source (or entry :builder-http)
                                :op :query
                                :status (if (= :ok outcome) :ok :error)
                                :latency-ms latency-ms})
    (catch Throwable _ nil))
  (try
    (obs/audit-record!
     (cond-> {:v 1
              :event :access/query
              :at (now-iso)
              :request-id (obs/request-id)
              :instance-id (instance-id)
              :entry (or entry :builder-http)
              :outcome outcome
              :actor {:service {:instance-id (instance-id)}
                      :on-behalf-of principal
                      :asserted-by :verified}
              :latency-ms latency-ms
              :entities entities}
       graph     (assoc :graph graph)
       reason    (assoc :reason reason)
       eql-shape (assoc :eql-shape eql-shape)
       ;; 语句层面的步骤是 **Builder 独有**的：数据面根本不绑那条回执通道
       ;; （`spi/*statement-trace*` 的 ns 文档：「不是关掉的日志，是不存在」）。
       ;; 所以它进 §2.3 的应用局部扩展，而不是平台键面。
       ;; `:statements` 是**平台级**的键（契约 §2.2）：Builder 与数据面都产出它。
       ;; 它一度住在 §2.3 的 `:app` 里，那是在「只有 Builder 有它」的时候。
       statements (assoc :statements statements)
       ;; 每-resolver 的名称与耗时。只进内存环，`record!` 会把它从流水里剥掉
       ;; （audit-event-v1 §2.2）——它是给详情面板看的，不是给外部采集器的。
       trace  (assoc :resolver-trace trace)))
    (catch Throwable _ nil)))

(defn- request-entities
  "这次请求碰到的实体命名空间——**只有名字**，不含键、不含值。"
  [request]
  (->> (tree-seq coll? seq (select-keys request [:entity :query]))
       (filter qualified-keyword?)
       (map namespace)
       distinct
       sort
       vec))

(defn- refusal-findings
  "One draft run's refusals as finding-v1, or nil.

   Engine hands back **two** shapes — `{:findings […]}` from a guard, and a
   FLAT map with `:engine/status :rejected` plus the finding's own keys. Only
   reading `:findings` reports the second as a success, which is the mistake
   `runtime/instance/refusals` documents on the data-plane side; this is the
   same reading for the draft path.

   `:error/category` survives (8.4.18 裁定十一 ⑤a): the engine puts the
   connector's declared category on the refusal, and the Builder's old draft
   face threw it away by keeping only `[:code :graph]`. Nothing is projected
   out here, so a connection-class failure now reaches the caller saying so."
  [r]
  (cond
    (seq (:findings r)) (vec (:findings r))
    ;; `::statements` / `::scope` are this ns's own bookkeeping, added to the
    ;; result AFTER engine returned it. On the refusal path the flat map IS the
    ;; finding, so leaving them on shipped Builder-internal keys — a scope
    ;; digest and a statement list — inside a finding the data plane delivers
    ;; without them. A refusal an agent learns here must be the refusal it
    ;; meets there (8.4.18 裁定十一's exit condition), so they come off.
    (= :rejected (:engine/status r)) [(dissoc r :engine/status ::statements ::scope)]
    :else nil))

(defn- run-draft!
  "**The one draft query path** (8.4.18 裁定十一 ③/④).

   Query Studio's `/api/pathom`, the Builder MCP's `execute_eql` (and the six
   other data-plane tools mounted beside it), and the intent tools' post-apply
   verification all arrive here. Before this ruling the browser had one path
   and the agent had another, and the day they disagreed nothing could say
   which of the two was wrong.

   The model is the scope projection — an unsealed cut — and the compile is
   the one `platform-runtime` performs for a published version, so a query
   that runs here is a query the next cut will run.

   Returns engine's own result map as DATA (`platform-runtime/run`), plus
   `::statements` and `::scope`. A refusal is not thrown: the MCP face has to
   deliver it as a finding, and a refusal that arrived as an exception has
   already lost its shape.

   **Audited here, and only here.** A draft query is a data read like any
   other (S5.3), and the old draft face wrote its own audit — so «who read
   this graph» had two half-answers. `entry` is the door: `:builder-mcp` for
   an agent, `:builder-http` for the browser."
  [{:keys [graph] :as request} caller entry]
  (let [started (System/nanoTime)
        elapsed #(quot (- (System/nanoTime) started) 1000000)
        ;; Lifted out of the try: the statements a FAILING run sent are
        ;; evidence too, and only this level sees both paths.
        seen (atom [])]
    (try
      (let [environment (environment-for-request request caller)
            ;; The statements this run sent (`spi/*statement-trace*`): the
            ;; modeller receives these rows, so the statement gives away
            ;; nothing the result did not — and «why is this list empty» has
            ;; no other answer. A deployed Server's data plane never binds
            ;; this channel, so it does not exist there at all.
            ;; Chained rather than replaced (8.4.18 裁定十二 ③): the MCP face
            ;; binds this channel too, to hand the modeller the statements
            ;; beside the rows. A binding that dropped the outer function would
            ;; make «who is listening» depend on who nested whom.
            outer spi/*statement-trace*
            r (binding [spi/*statement-trace* (fn [receipt]
                                                (swap! seen conj receipt)
                                                (when outer (outer receipt)))]
                (runtime/run environment request))
            fs (refusal-findings r)]
        (audit-query-run! {:request (select-keys request [:entity :query]) :graph (str graph)
                           :entry entry
                           :principal (:principal caller)
                           :outcome (if fs :denied :ok)
                           :reason (when fs (:code (first fs)))
                           :latency-ms (elapsed)
                           :entities (request-entities request)
                           :eql-shape (eql-shape-of request)
                           :statements (statements-summary @seen)
                           :trace (obs/summarize-run-stats (:engine/stats r))})
        (assoc r ::statements @seen ::scope (:scope environment)))
      (catch Exception e
        ;; Everything before execution — an unknown graph, an archived one, a
        ;; graph the projection refuses (`:scope/mixed-output` and friends).
        ;; It is audited for the same reason a failed query is: a read that
        ;; could not happen is evidence about what someone was doing.
        (let [code (or (:code (ex-data e)) :query-failed)]
          (audit-query-run! {:request (select-keys request [:entity :query]) :graph (str graph) :entry entry :outcome :error
                             :principal (:principal caller)
                             :reason code :latency-ms (elapsed)
                             :entities (request-entities request)
                             :eql-shape (eql-shape-of request)
                             :statements (statements-summary @seen)})
          (throw e))))))

(defn- draft-findings
  "`run-draft!`'s refusals, plus the ones its setup throws, as one
   `{:findings […]}` — the shape every MCP handler reads.

   The projection's refusal travels VERBATIM (`:error {:findings …}`), so an
   uncuttable graph is refused on `execute_eql` with the very finding
   `publish_graph`'s check gives."
  [thunk]
  (try
    (let [r (thunk)]
      (if-let [fs (refusal-findings r)] {:findings fs} r))
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        {:findings (or (seq (get-in d [:error :findings]))
                       [{:code :studio.draft/refused :severity :error :path [:graph]
                         :message (ex-message e)
                         :got (select-keys d [:code :graph])}])}))))

(defn- run-request-core!
  "Query Studio's `/api/pathom`. A saved Semantic Contract draft is the
   authoring truth for that graph and therefore wins over the legacy Pool
   projection. Graphs without one keep the legacy draft path. The decision is
   made here at the HTTP boundary, so a browser cannot race Contract discovery
   and accidentally execute the same text against a different catalog."
  [request]
  (let [started (System/nanoTime)
        elapsed #(quot (- (System/nanoTime) started) 1000000)]
    (try
      (let [root (request-root request)
            request (assoc request :graph root)
            caller (session-caller)
            db (pool/db (:conn @studio-state))
            definition (gsvc/require-graph! db caller root)
            semantic-draft (pool/semantic-draft db (:workspace caller) (:graph/id definition))]
        (if (pos? (or (:revision semantic-draft) 0))
          (let [result ((semantic/service (:conn @studio-state) caller runtime/resolve-source
                                          ;; Plan and Trace are drawn from the
                                          ;; engine's run-stats, as for a legacy
                                          ;; draft; without this a Contract run
                                          ;; showed both panes empty.
                                          {:query-stats? true})
                        :query {:graph root
                                :expected_revision (:revision semantic-draft)
                                :query (:query request)
                                :entity (or (:entity request) {})
                                :lenient? (boolean (:lenient? request))}
                        nil)
                ;; The service answers `{:response <engine response> :findings […]
                ;; :revision n}` (its query result is validated, and validation
                ;; wraps — semantic_service_test pins `[:response :engine/data]`).
                ;; This read the engine keys off the WRAPPER, so every Contract
                ;; graph's Query Studio run showed `nil` with no plan or trace
                ;; while the engine had answered (TRDC examples, 2026-10-01).
                ;; A refusal carries no `:response` and is read as it is.
                response (or (:response result) result)
                f (first (or (seq (:findings result))
                             (when (= :rejected (:engine/status response)) [response])))]
            (if f
              (throw (ex-info (or (:message f) "the Semantic Contract query was refused")
                              (assoc (dissoc f :message)
                                     :execution-target :semantic-contract
                                     :config-revision (:revision semantic-draft))))
              (do
                (audit-query-run! {:request (select-keys request [:entity :query]) :graph root :entry :builder-http
                                   :principal (:principal caller) :outcome :ok
                                   :latency-ms (elapsed) :entities (request-entities request)
                                   :eql-shape (eql-shape-of request)
                                   :trace (obs/summarize-run-stats (:engine/stats response))})
                (record-history! request :ok (elapsed) nil)
                (cond-> {:result (:engine/data response)
                 :plan (explain/plan-of-result (:engine/stats response))
                 :statements []
                 :meta {:config-revision (:revision semantic-draft)
                        :execution-target :semantic-contract
                        :graph root :elapsed-ms (elapsed)}}
                  (:engine/partial? response) (assoc :partial true :issues (:engine/issues response) :source-completeness :partial)
                  (:engine/row-outcomes response) (assoc :row-outcomes (:engine/row-outcomes response))))))
          (let [r (run-draft! request caller :builder-http)]
            (if-let [fs (refusal-findings r)]
              (let [f (first fs)]
                (throw (ex-info (or (:message f) "the query was refused")
                                (dissoc f :message))))
              (do
                (record-history! request :ok (elapsed) nil)
                (cond-> {:result (:engine/data r)
                 :plan (explain/plan-of-result (:engine/stats r))
                 :statements (::statements r)
                 :meta {:config-revision (get-in r [::scope :revision])
                        :execution-target :legacy-projection
                        :graph root :elapsed-ms (elapsed)}}
                  (:engine/partial? r) (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)
                  (:engine/row-outcomes r) (assoc :row-outcomes (:engine/row-outcomes r))))))))
      (catch Exception e
        (let [error {:code (or (:code (ex-data e)) :query-failed)
                     :message (.getMessage e)
                     :diagnostics (exception-details e)}]
          (record-history! request :error (elapsed) error)
          (throw (ex-info (.getMessage e) {:error error} e)))))))

(defn- run-request!
  "Attach SQL diagnostics only to the authenticated Builder preview response.
   The core records history before this wrapper adds driver details."
  [request]
  (let [diagnostics (atom [])]
    (binding [spi/*source-diagnostics* #(swap! diagnostics conj %)]
      (try
        (let [result (run-request-core! request)]
          (cond-> result
            (seq @diagnostics) (assoc :source-diagnostics @diagnostics)))
        (catch clojure.lang.ExceptionInfo e
          (if (seq @diagnostics)
            (throw (ex-info (.getMessage e)
                            (assoc-in (ex-data e) [:error :diagnostics :sources] @diagnostics)))
            (throw e)))))))

(defn- draft-runtime
  "**How the seven mounted data-plane tools obtain an environment**
   (8.4.18 裁定十一 ①).

   `modules/server/mcp` declares the tools and the seam; the data plane fills
   the seam with «the version this graph token pins», and this fills it with
   «the draft of the graph this call names». Same declarations, same handlers,
   same refusal shape — one thing differs, and this is it.

   `:pinned` answers the discovery and metric tools, `:answer` the executing
   ones. Both refuse with finding-v1, because that is what those handlers
   check for."
  [caller]
  {:pinned
   (fn [_ctx args]
     (draft-findings
      (fn []
        (let [environment (environment-for-request {:graph (:graph args)} caller)
              compiled (runtime/compiled-env environment)]
          (assoc (mcp/catalog-of compiled)
                 :result-owner [caller (get-in environment [:scope :workspace])]
                 ;; The draft has no version number. The projection's digest
                 ;; is what plays the part a version number plays: it is the
                 ;; search index's cache key, and it changes exactly when the
                 ;; draft changes (D7.10 wants a key that discards stale
                 ;; entries, not a number).
                 :version (get-in environment [:scope :revision])
                 :graph-id (get-in environment [:scope :graph-name])
                 :model (:engine/model compiled))))))
   :answer
   (fn [_ctx args request]
     (draft-findings
      (fn []
        ;; `read-eql` accepts both EQL shapes; the entity map is the request's
        ;; when it carries one.
        (let [{:keys [entity query]} (if (map? request) request {:query request})]
          ;; No `:env/version` on this path: a draft has no version, and
          ;; `draft-parity-test` pins that as the ONE key the two doors' answers
          ;; differ by. The projection digest — the key the environment is
          ;; cached under (8.4.20 裁定十四 ②) — is reported by `:pinned` below,
          ;; where it is a catalog fact rather than a version claim.
          (let [r (run-draft! {:graph (:graph args) :entity entity :query query}
                              caller :builder-mcp)]
            (cond-> r
              (= :ok (:engine/status r))
              (assoc :mcp/result-version (get-in r [::scope :revision]))))))))})

(defn- draft-service
  "The Builder-specific half of the draft face: the post-apply verification
   the intent tools run (8.4.14).

   `evaluate_draft_eql` is gone (裁定十一 ②) — an agent runs `execute_eql`
   with a `graph` — but the intent tools still verify their own write with a
   limit-1 query, and they do it through THIS, which is `run-draft!`: the same
   path, so a verification cannot pass on a draft the agent's own query would
   be refused on.

   Bound to one caller, like `graph-service` and `publish-service`, and for the
   same reason: an agent and a person get one set of guards rather than two
   that agree today."
  [caller]
  {:evaluate! (fn [{:keys [graph] :as request}]
                (let [r (run-draft! (assoc request :graph graph) caller :builder-mcp)]
                  (if-let [fs (refusal-findings r)]
                    {:findings fs}
                    (cond-> {:result (:engine/data r) :statements (::statements r)}
                      (:engine/partial? r) (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)
                      (:engine/row-outcomes r) (assoc :row-outcomes (:engine/row-outcomes r))))))})

(defn- explain-request! [request]
  (let [root (request-root request)
        request (assoc request :graph root)
        environment (environment-for-request request)
        ;; Same resolver DTO shape `catalog-data`/`graph-data` already build
        ;; from this graph's model (`graph-resolver`) — `pathom/explain.clj`
        ;; needs `:join {:key :children}`, not the dialect's raw `:output`
        ;; tree.
        resolvers (mapv graph-resolver (:resolvers (display-model-for root)))]
    (runtime/explain environment resolvers request)))

(defn- query-params [^HttpExchange exchange]
  (let [query (.getRawQuery (.getRequestURI exchange))]
    (into {}
          (for [part (str/split (or query "") #"&")
                :when (seq part)
                :let [[k v] (str/split part #"=" 2)]]
            [k (java.net.URLDecoder/decode (or v "") "UTF-8")] ))))

(defn- graph-options [params]
  (cond-> {}
    (seq (get params "root")) (assoc :root (get params "root"))
    (seq (get params "q")) (assoc :q (get params "q"))
    (seq (get params "focus")) (assoc :focus (get params "focus"))
    (seq (get params "depth")) (assoc :depth (Long/parseLong (get params "depth")))))

(defn- content-type [path]
  (cond (str/ends-with? path ".js") "text/javascript; charset=utf-8"
        (str/ends-with? path ".map") "application/json; charset=utf-8"
        (str/ends-with? path ".woff2") "font/woff2"
        :else "application/octet-stream"))

(defn- post!
  "A POST-only route: run `f` on the parsed EDN body, reply with `status`.

   The method guard used to be written out at each call site — 22 copies, and
   they had already drifted into two different 405 bodies (EDN for eighteen,
   `text/plain` for four).  A client that parses EDN cannot read the plain
   one, so the refusal for a wrong method was itself unreadable on exactly
   those four routes.  One guard, one shape."
  ([exchange method f] (post! exchange method 200 f))
  ([exchange method status f]
   (if (= "POST" method)
     (reply-edn! exchange status (f (request-edn exchange)))
     (reply-edn! exchange 405 {:error {:code :method-not-allowed}}))))

(defn- api-error->edn [ex]
  (let [d (ex-data ex)
        inner (:error d)
        code (or (:code d) (:code inner) :request-failed)
        status (or (:status d)
                   (if (#{:studio-admin-required :studio-user-directory-forbidden
                         :workspace-owner-required :workspace-forbidden :graph-forbidden
                         :graph-outside-current-workspace :workspace-outside-current-context
                         :history-forbidden :workspace-editor-required
                         :asset-outside-current-workspace :graph-editor-required}
                        code)
                     403
                     400))
        payload (cond-> {:code code
                        :status status
                        :message (or (:message inner)
                                     (:message d)
                                     (.getMessage ex)
                                     "Request failed")
                        :request-id (obs/request-id)}
                  (seq (:findings inner)) (assoc :findings (:findings inner))
                  (contains? d :expected) (assoc :expected (:expected d))
                  (contains? #{502 503 504 429 408} status) (assoc :retryable true)
                  (= code :upstream-timeout) (assoc :retryable true)
                  (= code :upstream-connection) (assoc :retryable true)
                  (= code :rate-limited) (assoc :retryable true)
                  (or (:diagnostics d) (:diagnostics inner))
                  (assoc :diagnostics (or (:diagnostics d) (:diagnostics inner))))]
    (cond-> payload
      (:error-category d) (assoc :error-category (:error-category d))
      (:exception-identity d) (assoc :exception-identity (:exception-identity d)))))

(defn- handle-routes! [^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))
        method (.getRequestMethod exchange)]
    (try
      (case path
        "/" (reply! exchange 200 (resource-text "public/index.html") "text/html")
        "/studio.css" (reply! exchange 200 (resource-text "public/studio.css") "text/css")
        "/platform.css" (reply! exchange 200 (resource-text "public/platform.css") "text/css")
        "/help" (reply! exchange 200 (resource-text "public/help.html") "text/html; charset=utf-8")
        "/api/pathom/index" (reply! exchange 200 (pr-str (catalog-data (get (query-params exchange) "q")
                                                                        (get (query-params exchange) "root"))) "application/edn")
        "/api/pathom/index-graph" (reply! exchange 200 (pr-str (graph-data (graph-options (query-params exchange)))) "application/edn")
        "/api/pathom/explain" (reply! exchange 200 (pr-str (explain-request! (request-edn exchange))) "application/edn")
        "/api/pathom" (reply! exchange 200 (pr-str (run-request! (request-edn exchange))) "application/edn")
        "/api/pathom/history" (reply! exchange 200
                                      (pr-str {:history (current-history)})
                                      "application/edn")
        "/api/config/status" (reply! exchange 200 (pr-str (status-data)) "application/edn")
        "/api/config/events" (reply! exchange 200 (pr-str {:cursor 0 :events []}) "application/edn")
        "/api/model/status" (reply! exchange 200 (pr-str (model-status)) "application/edn")
        "/api/model/sync" (reply! exchange 200 (pr-str (sync-model!)) "application/edn")
        "/api/studio/context" (reply! exchange 200 (pr-str (studio-context)) "application/edn")
        "/api/studio/context/workspace" (reply! exchange 200
                                                   (pr-str (select-workspace! (request-edn exchange)))
                                                   "application/edn")
        "/api/auth/change-password" (if (= "POST" method)
                                        (auth/handle-change-password! (:conn @studio-state) exchange)
                                        (reply-edn! exchange 405
                                                    {:error {:code :method-not-allowed}}))
        "/api/studio/users" (case method
                               "GET" (reply-edn! exchange 200 (studio-users-data))
                               "POST" (reply-edn! exchange 201
                                                   (create-studio-user! (request-edn exchange)))
                               (reply-edn! exchange 405 {:error {:code :method-not-allowed}}))
        "/api/studio/users/update" (post! exchange method update-studio-user!)
        "/api/studio/users/reset-password" (post! exchange method reset-studio-user-password!)
        "/api/studio/users/restore" (post! exchange method restore-studio-user!)
        "/api/studio/users/sso-email" (post! exchange method set-studio-user-sso-email!)
        "/api/studio/users/remove" (post! exchange method remove-studio-user!)
        "/api/studio/workspaces/members/remove" (post! exchange method remove-studio-workspace-member!)
        "/api/studio/workspaces/members" (post! exchange method add-studio-workspace-member!)
        "/api/studio/obs/activity" (reply-edn! exchange 200 (obs-activity-data (query-params exchange)))
        "/api/studio/obs/logs" (reply-edn! exchange 200 (obs-logs-data (query-params exchange)))
        "/api/studio/obs/metrics" (reply-edn! exchange 200 (obs-metrics-data))
        "/api/studio/schema/key-face" (reply-edn! exchange 200
                                                       (key-face-data (query-params exchange)))
        "/api/studio/schema" (reply-edn! exchange 200 (schema-data))
        "/api/studio/graphs/publish" (post! exchange method publish-graph!)
        "/api/studio/graphs/recent-queries" (reply-edn! exchange 200 (recent-queries-data (query-params exchange)))
        "/api/studio/graphs/recent-queries/action" (post! exchange method recent-query-action!)
        "/api/studio/graphs/versions" (reply-edn! exchange 200
                                                  (graph-versions-data (query-params exchange)))
        "/api/studio/graphs/rollback" (post! exchange method rollback-graph!)
        "/api/studio/graphs/versions/reclaim" (post! exchange method reclaim-graph-version!)
        "/api/studio/graphs/versions/reclaim-unused" (post! exchange method reclaim-unused-graph-versions!)
        "/api/studio/graphs/cut-check" (reply-edn! exchange 200
                                                   (graph-cut-check-data (query-params exchange)))
        ;; The one route that does not answer EDN. A refusal still does — the
        ;; generic handler below turns it into the usual `{:error …}` — so a
        ;; browser that followed this link to a version it may not read gets a
        ;; readable answer rather than a corrupt download.
        "/api/studio/graphs/export" (export-graph-bundle! exchange (query-params exchange)
                                                          (session-caller))
        "/api/studio/preflight" (post! exchange method preflight-asset!)
        "/api/studio/tokens" (reply-edn! exchange 200 (self-test-tokens-data))
        "/api/studio/tokens/issue" (post! exchange method 201 issue-self-test-token!)
        "/api/studio/tokens/revoke" (post! exchange method revoke-self-test-token!)
        ;; The workspace-token panel. Deliberately a DIFFERENT path prefix from
        ;; `/api/studio/tokens/*` above: the two ledgers must not read as two
        ;; views of one thing.
        "/api/studio/workspace-tokens" (reply-edn! exchange 200 (workspace-tokens-data))
        "/api/studio/workspace-tokens/issue" (post! exchange method 201 issue-workspace-token!)
        "/api/studio/workspace-tokens/revoke" (post! exchange method revoke-workspace-token!)
        "/api/studio/assets/object" (reply-edn! exchange 200
                                                (asset-object-data (query-params exchange)))
        "/api/studio/credentials" (case method
                                    "POST" (post! exchange method put-credentials!)
                                    (reply-edn! exchange 200 (credentials-data (query-params exchange))))
        "/api/studio/credentials/save" (post! exchange method save-variable!)
        "/api/studio/sources" (reply-edn! exchange 200 (sources-data (query-params exchange)))
        ;; GET, like the register above: it reads the model and opens no
        ;; connection. Nothing here writes.
        "/api/studio/sources/declarations"
        (reply-edn! exchange 200 (source-declarations-data (query-params exchange)))
        "/api/studio/workspace/objects" (reply-edn! exchange 200 (workspace-objects))
        "/api/studio/removal/plan" (post! exchange method removal-plan)
        "/api/studio/removal" (post! exchange method remove-objects!)
        ;; owner 2026-10-04: plan -> execute for a declared Source and a stored
        ;; credential variable, on the same terms as the object removal above.
        "/api/studio/sources/removal/plan" (post! exchange method source-removal-plan-request)
        "/api/studio/sources/remove" (post! exchange method remove-source!)
        "/api/studio/credentials/removal/plan" (post! exchange method variable-removal-plan)
        "/api/studio/credentials/remove" (post! exchange method remove-variable!)
        "/api/studio/sources/declare" (post! exchange method declare-source-request!)
        "/api/studio/sources/connect" (post! exchange method connect-source!)
        "/api/studio/sources/schema" (post! exchange method describe-source-schema!)
        ;; 8.4.22 裁定十六 ③: the api wizard. `probe` is the one real call
        ;; (step ①); `resolver` plans and applies through the MCP endpoint's
        ;; own `add_resolver` (step ④).
        "/api/studio/sources/api/probe" (post! exchange method api-probe!)
        "/api/studio/sources/api/resolver" (post! exchange method api-resolver!)
        ;; doc 106 §106.16: the twelve sentences, one route, the SAME planners
        ;; the MCP endpoint dispatches.
        "/api/studio/intent" (post! exchange method intent-tool!)
        "/api/studio/assets/action" (post! exchange method write-workspace-asset!)
        "/api/studio/graphs/create" (post! exchange method 201 create-graph!)
        "/api/studio/semantic-package/plan" (post! exchange method semantic-package-plan!)
        "/api/studio/semantic-package/import" (post! exchange method semantic-package-import!)
        "/api/studio/graphs/recipe" (post! exchange method update-graph-recipe!)
        "/api/studio/graphs/default-query" (post! exchange method set-graph-default-query!)
        "/api/studio/graphs/package-name" (post! exchange method set-graph-package-name!)
        "/api/studio/workspace/default-graph" (post! exchange method set-workspace-default-graph!)
        "/api/studio/graphs" (let [include-archived? (= "true" (get (query-params exchange) "include-archived"))
                                    workspace (current-workspace!)
                                    db (pool/db (:conn @studio-state))
                                    graphs (->> (studio-graph/visible-graphs db (auth/request-principal))
                                                (filter #(= workspace (:graph/workspace %))))]
                                (reply! exchange 200
                                        (pr-str {:graphs (cond->> graphs
                                                          (not include-archived?)
                                                          (filter #(= :active (:graph/state %)))
                                                          true (mapv (let [model (read-model)
                                                                           ;; Read off the state atom rather than
                                                                           ;; `runtime-parts`: that one throws when no
                                                                           ;; runtime is up, and a graph list is still a
                                                                           ;; true answer without one — it just cannot
                                                                           ;; say what is serving.
                                                                           store (:graph (:runtime @studio-state))
                                                                           ;; The key ledger is a directory scan, so it
                                                                           ;; is read ONCE and indexed — per row it was
                                                                           ;; one scan per graph, which is the shape
                                                                           ;; that turns a list into a slow list as
                                                                           ;; soon as a workspace has a few graphs.
                                                                           now (now-iso)
                                                                           live-keys
                                                                           (when-let [tp (:token (:runtime @studio-state))]
                                                                             (frequencies
                                                                              (for [r (rtoken/all-records tp)
                                                                                    :when (and (= :active (:status r))
                                                                                               (not (ctoken/expired? r now)))]
                                                                                (:graph-id r))))]
                                                                       #(assoc %
                                                                               :graph/held (held-counts model (studio-graph/normalize-members (:graph/members %)))
                                                                               ;; The two facts the list could never
                                                                               ;; show: which version is live, and
                                                                               ;; whether anything was ever cut. Publish
                                                                               ;; used to be a button that left no
                                                                               ;; trace on this page.
                                                                               :graph/serving (when store
                                                                                                (graph-serving-version
                                                                                                 store (:graph/qualified-name %)))
                                                                               :graph/cut-versions (count (version/versions
                                                                                                           db (:graph/qualified-name %)))
                                                                               ;; When a version was last cut. «created»
                                                                               ;; alone answers how old the recipe is,
                                                                               ;; never whether anybody has published it
                                                                               ;; since.
                                                                               :graph/last-cut-at (->> (version/versions
                                                                                                        db (:graph/qualified-name %))
                                                                                                       (map :version/cut-at)
                                                                                                       (remove nil?)
                                                                                                       sort
                                                                                                       last)
                                                                               ;; The blast radius of archiving or
                                                                               ;; deleting this graph: who reads it
                                                                               ;; (live keys) and who was lifted onto it
                                                                               ;; (explicit grants). Both were reachable
                                                                               ;; only by opening two different panels
                                                                               ;; on two different pages.
                                                                               :graph/live-keys (get live-keys
                                                                                                     (:graph/qualified-name %) 0)
                                                                               ;; The release name in force and whether it
                                                                               ;; is the default — the same answer the cut
                                                                               ;; will use, so the Publish tab shows it
                                                                               ;; rather than deriving its own.
                                                                               :graph/release-name (:name (studio-graph/package-name-for %))
                                                                               :graph/release-name-default? (nil? (:graph/package-name %))
                                                                               :graph/grants (count (:graph/access %))
                                                                               :graph/can-edit? (studio-graph/graph-editable? db (:graph/qualified-name %) (auth/request-principal))
                                                                               :graph/can-manage? (= :owner (studio-graph/workspace-role db workspace (auth/request-principal)))))))})
                                        "application/edn"))
        "/api/studio/graphs/candidates" (reply-edn! exchange 200
                                                     (recipe-candidates (query-params exchange)))
        "/api/studio/graphs/members" (reply-edn! exchange 200
                                                 (update-graph-members! (request-edn exchange)))
        "/api/studio/graphs/access" (if (= "POST" method)
                                      (reply-edn! exchange 200 (grant-graph-access! (request-edn exchange)))
                                      (reply-edn! exchange 200 (graph-access-data (query-params exchange))))
        "/api/studio/graphs/access/revoke" (post! exchange method revoke-graph-access!)
        "/api/studio/graphs/rename" (post! exchange method rename-graph!)
        "/api/studio/graphs/duplicate/plan" (post! exchange method duplicate-graph-plan)
        "/api/studio/graphs/duplicate/apply" (post! exchange method apply-duplicate-graph!)
        "/api/studio/graphs/duplicate" (post! exchange method duplicate-graph!)
        "/api/studio/graphs/merge" (post! exchange method merge-graphs!)
        "/api/studio/graphs/delete" (post! exchange method delete-graph!)
        "/api/studio/admin/graphs/retire" (post! exchange method admin-retire-graph!)
        "/api/studio/graphs/archive" (reply! exchange 200
                                                (pr-str (archive-request! (request-edn exchange) :archived))
                                                "application/edn")
        "/api/studio/graphs/restore" (reply! exchange 200
                                                (pr-str (archive-request! (request-edn exchange) :active))
                                                "application/edn")
        "/api/version" (reply! exchange 200
                              (str/trim (resource-text "VERSION")) "text/plain")
        (if (and (= "DELETE" method) (str/starts-with? path "/api/pathom/history/"))
          (let [id (subs path (count "/api/pathom/history/"))]
            (reply! exchange 200 (pr-str (forget-current-history! id)) "application/edn"))
          (if (str/starts-with? path "/help/")
            (reply! exchange 200 (resource-text (subs path 1)) "text/markdown; charset=utf-8")
            (if (or (str/starts-with? path "/js/")
                (str/starts-with? path "/fonts/"))
            (reply-bytes! exchange 200 (resource-bytes (str "public" path)) (content-type path))
            (reply! exchange 404 "Not found" "text/plain")))))
      (catch Exception e
        (let [payload (api-error->edn e)]
          (reply! exchange (or (:status payload) 400)
                  (pr-str {:error payload})
                  "application/edn"))))))

(defn- public-asset? [path]
  (or (= "/" path)
      (= "/studio.css" path)
      (= "/platform.css" path)
      (= "/api/version" path)
      (str/starts-with? path "/js/")
      (str/starts-with? path "/fonts/")))

(defn- audit-admin-token-call!
  "Every call made with the admin service token (§112.1 audit row). The actor
   is the service `service:admin-token` plus the first 8 hex of the matched
   digest — enough to tell rotated tokens apart, useless for guessing one —
   on behalf of the configured account."
  [digest-prefix principal method path outcome reason]
  (obs/audit-record!
   (cond-> {:v 1
            :event       :auth/admin-token
            :at          (now-iso)
            :request-id  (obs/request-id)
            :instance-id (instance-id)
            :entry       :builder-http
            :actor       (cond-> {:service     {:instance-id (instance-id)
                                                :id "service:admin-token"
                                                :token-digest-prefix digest-prefix}
                                  :asserted-by :verified}
                           principal (assoc :on-behalf-of principal))
            :outcome     outcome
            :method      method
            :path        path}
     reason (assoc :reason reason))))

(defn- serve-admin-token!
  "A Bearer that is not a workspace token, with the admin token configured.
   The credential is never logged or echoed; refusals say only which rule."
  [^HttpExchange exchange path method]
  (let [cfg (:admin-token @studio-state)
        digest (auth/admin-token-match cfg (auth/bearer-token exchange))]
    (if-not digest
      (do (audit-auth-failure! :builder-http :auth/admin-token-invalid)
          (reply-edn! exchange 401
                      {:error {:code :auth/admin-token-invalid
                               :message "The bearer token is not a valid admin service token."}}))
      (let [prefix (subs digest 0 8)
            {:keys [user error]} (auth/admin-token-user (pool/db (:conn @studio-state)) cfg)]
        (cond
          error
          (do (audit-admin-token-call! prefix nil method path :denied (:code error))
              (reply-edn! exchange 401 {:error error}))

          (not (auth/admin-token-route? path))
          (do (audit-admin-token-call! prefix (:principal user) method path
                                       :denied :auth/admin-token-route-forbidden)
              (reply-edn! exchange 403
                          {:error {:code :auth/admin-token-route-forbidden
                                   :message "The admin service token may not use this route."
                                   :expected "/api/studio/** except accounts, members, Studio administration and workspace-token issuing"}}))

          :else
          (binding [*request-user* user
                    auth/*admin-token* {:digest-prefix prefix}]
            (audit-admin-token-call! prefix (:principal user) method path :ok nil)
            (handle-routes! exchange)))))))

(defn admin-token-from-env!
  "Read and check §112.1 configuration against the account store. Returns nil
   (off) or the config; throws with findings when it is malformed or names an
   account that does not exist — a start-time refusal, because the store is
   open by the time this runs."
  [env conn]
  (when-let [cfg (auth/admin-token-config env)]
    (when-let [fs (seq (:findings cfg))]
      (throw (ex-info "Admin service token configuration was rejected" {:findings (vec fs)})))
    (when-let [error (:error (auth/admin-token-user (pool/db conn) cfg))]
      (throw (ex-info "Admin service token configuration was rejected" {:findings [error]})))
    cfg))

(defn- handle-request-inner! [^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))
        method (.getRequestMethod exchange)]
    (try
      (cond
        ;; These auth routes are the only ones served WITHOUT a bound user
        ;; — they are how one is obtained.  Everything else runs inside the
        ;; binding below or not at all.
        (and (= path "/api/auth/state") (= method "GET"))
        (auth/handle-auth-state! (:conn @studio-state) exchange (:oidc @studio-state))

        ;; The two SSO routes join the no-session allowlist for the same reason
        ;; the four above are in it: they are how a session is obtained (8.16.5).
        (and (= path "/api/auth/oidc") (= method "GET"))
        (auth/handle-oidc-begin! (:oidc @studio-state) exchange
                                 (partial audit-auth-failure! :builder-http))

        (and (= path "/api/auth/oidc/callback") (= method "GET"))
        (auth/handle-oidc-callback! (:conn @studio-state) (:oidc @studio-state) exchange
                                    (partial audit-auth-failure! :builder-http)
                                    (partial audit-oidc-login! :builder-http))

        (and (= path "/api/auth/register") (= method "POST"))
        (auth/handle-register! (:conn @studio-state) exchange)

        (and (= path "/api/auth/login") (= method "POST"))
        ;; The recorder is threaded in rather than reached for: `auth` holds no
        ;; state and knows no instance, and this is the instance.
        (auth/handle-login! (:conn @studio-state) exchange
                            (partial audit-auth-failure! :builder-http))

        (and (= path "/api/auth/logout") (= method "POST"))
        (auth/handle-logout! exchange)

        (public-asset? path)
        (handle-routes! exchange)

        ;; **The MCP endpoint authenticates itself** (8.4 P1b): it carries a
        ;; workspace token, not a session cookie. Running the cookie guard over
        ;; it would refuse every agent with a 401 that names the wrong
        ;; credential — and the transport must be the thing that answers, since
        ;; it owns the JSON-RPC error shape a client can read.
        (builder-mcp/handles? path)
        (builder-mcp/handle! (mcp-deps) exchange)

        ;; **The export accepts the key an agent already holds** (doc 106
        ;; §106.12 item 8.7). Verified by `workspace-token/verify` — the SAME
        ;; validator the MCP door runs, never a second one — and a `:read`
        ;; scope is enough, because an export reads a published version and
        ;; writes nothing. The caller is the TOKEN's, exactly as on the MCP
        ;; ctx, so no query parameter can move the read into another workspace.
        (and (= path "/api/studio/graphs/export") (= method "GET")
             (some? (workspace-token-caller exchange)))
        (let [caller (workspace-token-caller exchange)]
          (if (contains? (:scope caller) :read)
            (export-graph-bundle! exchange (query-params exchange)
                                  (select-keys caller [:workspace :principal]))
            (reply-edn! exchange 403
                        {:error {:code :workspace-token-scope-invalid
                                 :message "This key may not read this workspace."
                                 :expected "read"}})))

        ;; **The graph list accepts the key too** (owner 2026-10-11): an agent or
        ;; script holding a workspace token lists its workspace's graphs —
        ;; id, names, state and, with `with-guide=true`, each graph's purpose —
        ;; without a browser session. `:read` is enough; the workspace is the
        ;; TOKEN's, as on the MCP door. The same `gsvc/list-graphs` that
        ;; `list_graphs` calls answers, so the two cannot drift. EDN by
        ;; default, JSON when the client asks for it.
        (and (= path "/api/studio/graphs") (= method "GET")
             (some? (workspace-token-caller exchange)))
        (let [caller (workspace-token-caller exchange)
              params (query-params exchange)]
          (if (contains? (:scope caller) :read)
            (let [r ((:list-graphs (graph-service (select-keys caller [:workspace :principal])))
                     {:include-archived? (= "true" (get params "include-archived"))
                      :with-guide? (= "true" (get params "with-guide"))})]
              (if (str/includes? (str (.getFirst (.getRequestHeaders exchange) "Accept")) "application/json")
                (reply! exchange 200 (json/generate-string r) "application/json")
                (reply-edn! exchange 200 r)))
            (reply-edn! exchange 403
                        {:error {:code :workspace-token-scope-invalid
                                 :message "This key may not read this workspace."
                                 :expected "read"}})))

        ;; **The admin service token** (doc 112 §112.1). Only when configured
        ;; — unset, a Bearer is ignored and the request is refused exactly as
        ;; one with no session. An `oaw_v1.` key is never compared here, so a
        ;; workspace token on `/api/studio/**` behaves as it did before.
        (and (:admin-token @studio-state)
             (some-> (auth/bearer-token exchange) (-> auth/workspace-token-shaped? not)))
        (serve-admin-token! exchange path method)

        :else
        (if-let [user (auth/request-user exchange)]
          (binding [*request-user* user]
            (handle-routes! exchange))
          (reply-edn! exchange 401
                      {:error {:code :authentication-required
                               :message "Sign in to Open Atlas Studio."}})))
      ;; Throwable, not Exception: an AssertionError from a library invariant
      ;; (the dialect writer's, say) is an Error, and an unanswered exchange is
      ;; the one failure mode with no message at all — the client just shows
      ;; "saving…" until the end of time. Whatever escapes, the boundary replies.
      (catch Throwable e
        ;; **拒绝的理由必须过得了这道边界。** handler 抛出的 ex-data 里常常带着
        ;; 一整份结构化拒绝（`{:error {:findings [...]}}`），而这里原来只留下
        ;; code 与 message —— 于是界面上只剩「保存失败」四个字，真正说明白的
        ;; 那一条（比如「缺省值 true 不许写出来，去掉这个键」）被扔在服务端。
        ;; finding-v1 的全部意义就是让拒绝可被行动，丢掉它等于没有拒绝理由。
        (let [payload (api-error->edn e)]
          (reply-edn! exchange (or (:status payload) 400)
                      {:error payload}))))))

;; ---------------------------------------------------------------------------
(defn- handle-request! [^HttpExchange exchange]
  (binding [obs/*query-source* {:remote-addr (some-> exchange .getRemoteAddress .getAddress .getHostAddress)
                               :path (.getPath (.getRequestURI exchange))
                               :user-agent (.getFirst (.getRequestHeaders exchange) "User-Agent")}]
    (handle-request-inner! exchange)))

;; Where the Builder listens (8.4.9 ①)
;; ---------------------------------------------------------------------------

(def ^:private loopback-hosts
  "The addresses that mean «this machine only». `localhost` is in here because
   that is what people type; it resolves to a loopback address and binding it
   exposes nothing."
  #{"127.0.0.1" "::1" "localhost"})

(defn builder-binding
  "Decide how the Builder listener binds, from configuration alone.

   → `{:host h :port n :tls {:keystore … :password …}}` or `{:findings […]}`.

   Pure and separately testable on purpose: this is the one decision in the
   process that can turn a control-plane endpoint into something the internet
   can reach, and a decision made inline inside `-main` is a decision with no
   test and no name.

   The rules
   ([8.4.9](../../../../../../docs/design/84-builder-mcp.md#849-裁定二oma-作为-builder-mcp-客户端owner2026-08-29)):

   - **Default is loopback.** An OMA agent reaching this Builder is a thing
     somebody configures, never a thing that happens because they upgraded.
   - **Any binding may speak plain HTTP** (owner ruling 2026-08-29-b,
     overruling the original «off-loopback demands TLS»). The consequence is
     recorded where the ruling is: off-loopback cleartext lets anything on
     the path copy and replay the workspace token. TLS stays available —
     configure the keystore and this listener speaks HTTPS.
   - **A keystore with no password is still a refusal**, rather than an
     attempt with the empty one: «TLS did not start» must never be something
     you find out by reading a log.

   The data plane is configured separately by OPEN_ATLAS_STUDIO_DATA_HOST —
   D3.12's two doors are two decisions, and this one is only the Builder's."
  [{:keys [host port keystore password]}]
  (let [host (or (not-empty host) "127.0.0.1")]
    (cond
      ;; Owner ruling 2026-08-29-b (8.4.9 ①修订): plain HTTP is allowed on ANY
      ;; binding. The tls-required refusal that used to sit here is removed —
      ;; the owner accepts that off-loopback cleartext lets anything on the
      ;; path copy and replay the workspace token. TLS remains available:
      ;; configure the keystore and the listener speaks HTTPS. The one refusal
      ;; kept is half-configured TLS below — that one is about a listener
      ;; silently not being what its configuration says, not about policy.
      (and (seq keystore) (empty? password))
      {:findings [{:code :builder.listener/tls-password-missing :severity :error
                   :path [:env "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD"]
                   :message (str "a TLS keystore is configured but its password is not. "
                                 "Refusing rather than trying an empty one: a listener "
                                 "that fails to start its TLS is a fact that must be "
                                 "read here, not discovered in a log.")
                   :expected "the keystore's password"}]}

      :else
      (cond-> {:host host :port port}
        (seq keystore) (assoc :tls {:keystore keystore :password password})))))

(defn- ssl-context
  "An `SSLContext` holding the configured keystore. PKCS12 because that is the
   format every current tool writes by default."
  [{:keys [keystore password]}]
  (let [pw (.toCharArray ^String password)
        ks (doto (KeyStore/getInstance "PKCS12")
             (.load (io/input-stream (io/file keystore)) pw))
        kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks pw))]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) nil nil))))

(defn- handler
  "Every Builder request — the UI's and an agent's MCP call alike — runs
   correlated. Without this a Builder MCP failure could not be tied back to the
   call that caused it: the transport is JSON-RPC over one shared endpoint, so
   «which agent, which call» is not recoverable from the path alone.

   An upstream X-Request-Id is honoured so a caller that already has an id
   keeps it across the hop; the id is echoed back so the caller can quote it."
  [^HttpExchange exchange]
  (let [upstream (.getFirst (.getRequestHeaders exchange) "X-Request-Id")
        rid (or (not-empty (str upstream)) (obs/gen-request-id))]
    ;; set before the body runs — response headers are frozen at sendResponseHeaders
    (.set (.getResponseHeaders exchange) "X-Request-Id" rid)
    (obs/with-request-id rid
      (if (public-asset? (.getPath (.getRequestURI exchange)))
        ;; Static assets include /, used by the deployment health check. They
        ;; never access the pool and stay available during maintenance.
        (handle-request! exchange)
        (pool-maintenance/with-access
         (:pool-maintenance @studio-state)
         #(handle-request! exchange)
         #(reply-edn! exchange 503
                      {:error {:code :studio/maintenance
                               :message "Studio database maintenance is in progress or needs attention. Please retry shortly."}}
                      {"Retry-After" "5"}))))))

(defn- start-builder-listener!
  "The listener for `binding`, started but with no context attached yet."
  [{:keys [host port tls]}]
  (let [address (InetSocketAddress. ^String host (int port))]
    (if tls
      (doto (HttpsServer/create address 0)
        (.setHttpsConfigurator (HttpsConfigurator. (ssl-context tls))))
      (HttpServer/create address 0))))

(defn data-plane-binding
  "The data listener is independently configurable from the Builder listener.

   TLS: OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE / _PASSWORD, and when neither is set
   the Builder's keystore — one certificate is the common deployment, and a
   data plane left on HTTP beside an HTTPS Builder would carry graph tokens in
   cleartext without anyone having chosen that. Half-configured TLS is the
   same refusal as the Builder's."
  [environment]
  (let [get* #(not-empty (str/trim (get environment % "")))
        own? (or (get* "OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE")
                 (get* "OPEN_ATLAS_STUDIO_DATA_TLS_PASSWORD"))
        keystore (if own? (get* "OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE")
                     (get* "OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE"))
        password (if own? (get* "OPEN_ATLAS_STUDIO_DATA_TLS_PASSWORD")
                     (get* "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD"))]
    (if (and keystore (nil? password))
      {:findings [{:code :data.listener/tls-password-missing :severity :error
                   :path [:env (if own? "OPEN_ATLAS_STUDIO_DATA_TLS_PASSWORD"
                                   "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD")]
                   :message "a data-plane TLS keystore is configured but its password is not."
                   :expected "the keystore's password"}]}
      (cond-> {:host (or (get* "OPEN_ATLAS_STUDIO_DATA_HOST") "127.0.0.1")
               :port (Long/parseLong (or (get* "OPEN_ATLAS_STUDIO_DATA_PORT") "9632"))}
        keystore (assoc :tls {:keystore keystore :password password})))))

(defn- data-plane-scheme []
  (if (:tls (data-plane-binding (System/getenv))) "https" "http"))

(defn -main [& _]
  (let [maintenance-config (pool-maintenance/configuration (System/getenv))
        port (Long/parseLong (or (env "OPEN_ATLAS_STUDIO_PORT") "9631"))
        binding* (builder-binding
                  {:host (env "OPEN_ATLAS_STUDIO_BUILDER_HOST")
                   :port port
                   :keystore (env "OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE")
                   :password (env "OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD")})
        _ (when-let [fs (seq (:findings binding*))]
            ;; Before `bootstrap!`: refusing after the pool is open and the
            ;; runtime lease is taken would leave a half-started Studio behind
            ;; for a decision that needed no state to make.
            (doseq [f fs] (println (str "✗ " (name (:code f)) ": " (:message f))))
            (System/exit 2))
        data-binding (data-plane-binding (System/getenv))
        _ (when-let [fs (seq (:findings data-binding))]
            (doseq [f fs] (println (str "✗ " (name (:code f)) ": " (:message f))))
            (System/exit 2))
        state (bootstrap!)
        ;; §112.1: checked after `bootstrap!` because the named account lives in
        ;; the pool; refused before the listener opens, so a half-configured
        ;; token never answers a single request.
        admin-token (try (admin-token-from-env! (System/getenv) (:conn state))
                         (catch clojure.lang.ExceptionInfo e
                           (doseq [f (:findings (ex-data e))]
                             (println (str "✗ " (name (:code f)) ": " (:message f))))
                           (System/exit 2)))
        state (assoc state :admin-token admin-token)
        maintenance (pool-maintenance/controller
                     {:home (studio-home) :config maintenance-config
                      :get-connection #(:conn @studio-state)
                      :publish-connection! #(swap! studio-state assoc :conn %)})
        scheme (if (:tls binding*) "https" "http")
        host (:host binding*)
        server (start-builder-listener! binding*)]
    (reset! studio-state (assoc state :pool-maintenance maintenance))
    ;; §106.14-12: one resolution order for every `${VAR}`, on every path.
    ;; `resolve-config` already served the CONNECT path, but a connector's
    ;; write-side validation (`spi/validate-source`) resolves on its own and
    ;; was reading the process environment directly — so a credential held
    ;; only in this instance's store was refused at `declare_source`
    ;; («:tls :keystore_pass did not resolve to a value») and accepted at
    ;; query time. Bound at the root, not per request: the connectors resolve
    ;; on whatever thread the call lands on. Re-read per lookup, which is what
    ;; makes renewing a token take effect without a restart.
    (alter-var-root #'spi/*config-lookup*
                    (constantly (fn [n] ((credentials/lookup-fn (studio-home)) n))))
    ;; 8.10 P4. Two outputs for logs (console + app.jsonl) and an audit trail
    ;; anchored where audit-event-v1 §6 says it belongs: this instance's own obs
    ;; subsystem. studio HAS an instance, so unlike apps/server it can give the
    ;; contract's answer instead of the workspace-root approximation.
    (obs/log-configure! {:retention-home (studio-home) :log-file (str (io/file (studio-home) "logs" "app.jsonl"))})
    (obs/audit-configure!
     {:recent 200
      :dir (str (store/subsystem-dir (:instance (:runtime state)) "obs"))})
    ;; 环是内存，流水在盘上——**重启后把盘上的尾巴读回环里**，否则 Activity 页
    ;; 在一台跑了半年的 instance 上重启后是空的，看起来像「什么都没发生过」,
    ;; 而实际上什么都记着。轮转出去的那一代不回读（读不回来，也不假装）。
    (obs/audit-prime-from-file!)
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    ;; Release the runtime lease before closing the pool. Deliberately not
    ;; `app/stop!`: that deletes the pool, which is right for a test and
    ;; catastrophic for a Studio home.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (.stop server 0)
                                    (pool-maintenance/stop! maintenance)
                                    (when-let [rt (:runtime state)] (instance/stop! rt))
                                    (pool/close-pool! (:conn @studio-state))
                                    (reset! studio-state nil))))
    ;; The data plane is a **separate listener with separate credentials**
    ;; (D3.12): the Builder's key is a session cookie, the data plane's is a
    ;; graph token. One entrance would make a leaked query token a deploy
    ;; permission. The shell is `server/data` verbatim — studio does not
    ;; re-implement grant judgement (D1.13).
    (let [{data-port :port data-host :host data-tls :tls} data-binding
          data-scheme (if data-tls "https" "http")
          ;; `:now` is injected so both shells are testable; here it is simply
          ;; the wall clock.
          now* (fn [] (str (.truncatedTo (java.time.Instant/now)
                                         java.time.temporal.ChronoUnit/SECONDS)))
          shell {:instance (assoc (:runtime state) :query-log? true
                                  ;; Studio keeps the recent requests for debugging
                                  ;; (proposal studio-saved-queries §4); the
                                  ;; standalone Server sets no recorder.
                                  :on-query (recent/recorder #(:conn @studio-state)
                                                             (recent/ring-size (System/getenv))))
                 :now now*
                 ;; Saved queries, pinned per published version (data-plane-api
                 ;; 1.2). Read at call time, so a publish is seen at once.
                 :saved-queries (fn [graph-uuid version]
                                  (studio-graph/pinned-saved-queries
                                   (pool/db (:conn @studio-state)) graph-uuid version))}
          data (data/start!
                (assoc shell
                       :saved-query (mcp/saved-query-request shell)
                       :port data-port :host data-host :tls data-tls
                       ;; **MCP rides the data plane listener**, because it IS
                       ;; the data plane: same token, same `instance/answer`,
                       ;; different wire language. A separate port would make
                       ;; "the data plane has exactly one kind of key" (D5.7)
                       ;; a sentence with an exception in it.
                       :extra-routes
                       (mcp/routes
                        (assoc shell
                               ;; Streaming belongs to the HTTP adapter, not to
                               ;; MCP — the shell hands us a writer fn and this
                               ;; is where it becomes a Ring streamed body.
                               :sse-writer
                               (fn [write!]
                                 (reify ring.core.protocols/StreamableResponseBody
                                   (write-body-to-stream [_ _response out]
                                     (write! out))))))))]
      (swap! studio-state assoc :data-server (:server data))
      (println (str "Open Atlas Studio data plane: " data-scheme "://" data-host ":" data-port "/eql"))
      (println (str "Open Atlas Studio data MCP:   " data-scheme "://" data-host ":" data-port
                    "/mcp   (graph token)")))
    (pool-maintenance/start! maintenance)
    (println (str "Open Atlas Studio Builder: " scheme "://" host ":" port))
    (when-not (contains? loopback-hosts host)
      ;; Said out loud because it is the one line that distinguishes this run
      ;; from every other: the control plane is reachable from off this
      ;; machine, and the only thing between it and a stranger is a key.
      (println (str "  ⚠ the Builder listener is NOT on loopback — every workspace "
                    "token is now a credential that travels"))
      (when-not (:tls binding*)
        ;; The owner ruled plain HTTP legal here (8.4.9 ①修订); this line is
        ;; the ruling's recorded consequence, printed where it is true.
        (println (str "  ⚠ …and it travels in CLEARTEXT: anything on the path "
                      "can copy and replay it"))))
    ;; The modelling endpoint is announced next to the others because it is the
    ;; only one with no page to discover it from: a data-plane token has a panel
    ;; that names its URL, and this one is reached by an agent that was told
    ;; where to look. An address nobody prints is an address nobody uses.
    (println (str "Open Atlas Builder MCP:    " scheme "://" host ":" port
                  "/mcp   (workspace token — issue one in the Builder)"))
    (println (str "Graph " (graph-name) " ← " (model-source)))
    @(promise)))
