(ns open.atlas.studio.ui.api
  "The one place the Studio UI talks to the Platform Server.

   Same-origin requests only.  Identity, workspace visibility, and the active
   workspace come from the server; the browser supplies neither a principal nor
   an authorization scope."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

(def ^:private default-timeout-ms 10000)

(def ^:private max-retry-count 2)

(def ^:private retryable-status-codes #{429 502 503 504})

(defn- error-retryable?
  [{:keys [code status] :as _error}]
  (or (boolean (:retryable _error))
      (contains? #{:connection :timeout :upstream-connection :upstream-timeout} code)
      (retryable-status-codes status)
      (contains? #{:network :network-timeout} code)))

(defn- normalize-error [error]
  (let [raw     (cond
                  (string? error) {:message (str/trim error)}
                  (map? error) error
                  :else {:message (str error)})
        status  (:status raw)
        code    (or (:code raw)
                    (when status (keyword (str "http-" status)))
                    :request-failed)
        message (or (and (string? (:message raw)) (str/trim (:message raw)))
                    (and (string? (:problem raw)) (str/trim (:problem raw)))
                    "Request failed.")]
    (cond-> (merge (select-keys raw [:request-id :method :path :attempt :remaining-retries
                                   :retry-after-ms :next-retry-ms])
                  {:code code :status status :message message})
      (seq (:findings raw)) (assoc :findings (:findings raw))
      (map? (:diagnostics raw)) (assoc :diagnostics (:diagnostics raw))
      (contains? raw :expected) (assoc :expected (:expected raw))
      (error-retryable? raw) (assoc :retryable true)
      :always (assoc :details (select-keys raw [:error :diagnostics :status :code :findings :expected])))))

(defn- parse-timeout-ms [value]
  (if (number? value)
    (max 1 (long value))
    default-timeout-ms))

(defn- new-request-id []
  (let [fallback (str "ui-" (.now js/Date) "-" (rand-int 1000000))]
    (if (and (exists? js/crypto) (.-randomUUID js/crypto))
      (.randomUUID js/crypto)
      fallback)))

(defn- parse-retry-after [raw]
  (let [value (when (some? raw) (str/trim (str raw)))]
    (when (seq value)
      (let [seconds (js/parseInt value 10)]
        (when (js/isFinite seconds)
          (* 1000 (long seconds)))))))

(defn- retry-delay-ms [attempt]
  (+ 200 (* attempt 400) (rand-int 75)))

(defn- with-timeout [ms fetch-fn]
  (js/Promise.
   (fn [resolve reject]
     (let [controller (js/AbortController.)
           timer (js/setTimeout (fn []
                                  ;; Settle with the timeout before abort can
                                  ;; reject fetch with a generic AbortError.
                                  (reject (js/Error. "Request timeout"))
                                  (.abort controller)) ms)]
       (-> (js/Promise.resolve nil)
           (.then #(fetch-fn (.-signal controller)))
           (.then (fn [value]
                    (js/clearTimeout timer)
                    (resolve value)))
           (.catch (fn [error]
                    (js/clearTimeout timer)
                    (reject error))))))))

(defn- parse [text]
  (try
    (reader/read-string text)
    (catch :default e
      {:error {:code :bad-response
               :message (str "Server sent unreadable EDN: " (.-message e))}})))

(defn- request!
  [{:keys [method path body on-success on-error timeout-ms max-retries]
    :or {method "GET" timeout-ms default-timeout-ms max-retries max-retry-count}}]
  (let [timeout-ms (parse-timeout-ms timeout-ms)
        ;; A timed-out write may have committed. Only reads can be replayed
        ;; automatically without an endpoint-specific idempotency contract.
        retries-left (if (= method "GET") (max 0 (long max-retries)) 0)
        request-id (new-request-id)]
    (letfn [(dispatch [remaining]
              (let [attempt (max 0 (- retries-left remaining))
                    headers (cond-> {"Accept" "application/edn"
                                    "X-Request-Id" request-id}
                              body (assoc "Content-Type" "application/edn"))
                    options (cond-> {:method method
                                    :credentials "same-origin"
                                    :headers headers}
                              body (assoc :body (pr-str body)))]
              (-> (with-timeout
                    timeout-ms
                    (fn [signal]
                      (-> (js/fetch path (clj->js (assoc options :signal signal)))
                          (.then (fn [response]
                                   (.then (.text response)
                                          (fn [text]
                                            (let [retry-after (parse-retry-after (.get (.-headers response)
                                                                                      "Retry-After"))]
                                              #js [(.-status response) retry-after text]))))))))
                  (.then (fn [pair]
                           (let [status (aget pair 0)
                                 retry-after-ms (aget pair 1)
                                 value (parse (aget pair 2))
                                 error (cond
                                         (and (map? value) (:error value)) (:error value)
                                         (and (map? value) (>= status 400)) value
                                         (not (map? value)) {:status status
                                                            :code (keyword (str "http-" status))
                                                            :message (str "Unexpected server response (HTTP " status ")")
                                                            :retry-after-ms retry-after-ms}
                                         :else nil)
                                 next-delay (max (retry-delay-ms remaining)
                                                (or retry-after-ms 0))]
                             (if error
                               (let [normalized (normalize-error
                                                 (assoc (cond-> error
                                                          retry-after-ms (assoc :retry-after-ms retry-after-ms))
                                                        :status status
                                                        :method method :path path
                                                        :attempt (inc attempt)
                                                        :remaining-retries remaining
                                                        :request-id request-id
                                                        :next-retry-ms next-delay))]
                                 (if (and (pos? remaining)
                                          (error-retryable? normalized))
                                   (-> (js/Promise.
                                        (fn [resolve]
                                          (js/setTimeout #(resolve true) next-delay)))
                                       (.then #(dispatch (dec remaining))))
                                   (when on-error (on-error normalized))))
                               (when on-success (on-success value))))))
                  (.catch (fn [e]
                            (let [message (or (.-message e) "Request failed")
                                  code (if (= message "Request timeout")
                                         :timeout
                                         :network)
                                  next-delay (retry-delay-ms remaining)
                                  error {:code code
                                         :message message
                                         :retryable (contains? #{:network :timeout} code)
                                         :status (when (= code :timeout) 504)
                                         :request-id request-id
                                         :method method :path path
                                         :attempt (inc attempt)
                                         :remaining-retries remaining
                                         :next-retry-ms next-delay}
                                  normalized (normalize-error error)]
                              (if (and (pos? remaining)
                                       (error-retryable? normalized))
                                (-> (js/Promise.
                                     (fn [resolve]
                                       (js/setTimeout #(resolve true)
                                                     next-delay)))
                                    (.then #(dispatch (dec remaining))))
                                (when on-error (on-error normalized)))))))))]
              (dispatch retries-left))))

(defn index! [q root opts]
  (request! (assoc opts
                   :path (str "/api/pathom/index"
                              "?root=" (js/encodeURIComponent root)
                              (when-not (empty? q) (str "&q=" (js/encodeURIComponent q)))))))

(defn graphs! [opts] (request! (assoc opts :path "/api/studio/graphs?include-archived=true")))

(defn schema! [opts] (request! (assoc opts :path "/api/studio/schema")))

(defn key-face!
  "What one object type may carry, derived from the dialect's closed schemas
   (doc 84 §8.4.25). A read; the editor caches it per type."
  [type opts]
  (request! (assoc opts :path (str "/api/studio/schema/key-face?type="
                                   (js/encodeURIComponent (name type))))))

(defn publish-graph!
  "Cut the current recipe and publish it into the embedded runtime — the same
   offer/stage/load/activate a remote Server gets."
  [graph opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/publish"
                   :body {:graph graph})))

(defn graph-versions!
  "Every version cut for this graph, newest first, plus where the serving
   pointer is and what the runtime holds. A read.

   Editor-only, like the publish it belongs to: the service guards both with one
   function, so this refuses for exactly the same reason publishing would."
  [graph opts]
  (request! (assoc opts :path (str "/api/studio/graphs/versions?graph="
                                   (js/encodeURIComponent graph)))))

(defn graph-cut-check!
  "Would this graph cut right now, and which checks refuse it if not.

   `preflight!` is the neighbouring question and needs an ACTION — it answers
   «would this write break the next cut». This one answers «is this graph
   publishable at all», which previously could only be learned by pressing
   publish and reading the refusal.

   The expensive read of the three: it projects and health-checks. Asked for one
   open graph, never for a list."
  [graph opts]
  (request! (assoc opts :path (str "/api/studio/graphs/cut-check?graph="
                                   (js/encodeURIComponent graph)))))

(defn rollback-graph!
  "Point the graph back at a version cut earlier.

   The evidence presented to the runtime is that version's OWN deployment plan
   (R3.6) — rolling back re-presents what was already accepted rather than
   inventing a new deployment. A version that was never cut, or one already
   being served, is refused by name."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/rollback"
                   :body req)))

(defn reclaim-graph-version!
  "Remove one published version's artifact from this instance (R3.5). The
   version record stays, marked reclaimed; refused by name while the version
   is serving, pinned by an active token, or referenced by another artifact."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/versions/reclaim"
                   :body req)))

(defn reclaim-unused-graph-versions!
  "Remove every reclaimable version except the newest `:keep` (R3.5)."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/versions/reclaim-unused"
                   :body req)))

(defn preflight!
  "Would this action leave the graph un-cuttable, and is that this action's
   doing? Writes nothing."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/preflight" :body req)))

(defn self-test-tokens! [opts] (request! (assoc opts :path "/api/studio/tokens")))

(defn issue-self-test-token!
  "The response carries the secret **once**; it exists nowhere afterwards."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/tokens/issue" :body req)))

(defn revoke-self-test-token! [token-id opts]
  (request! (assoc opts :method "POST" :path "/api/studio/tokens/revoke"
                   :body {:token-id token-id})))

;; --- Workspace tokens: the Builder MCP endpoint's key (8.4 P1b) -------------
;;
;; A DIFFERENT ledger from the self-test tokens above, on a different path
;; prefix, for a different door. Mixing the two in one panel would invite
;; pasting one where the other belongs — and the whole reason D3.12 split the
;; doors is that those two authorities must not be interchangeable.

(defn workspace-tokens! [opts]
  (request! (assoc opts :path "/api/studio/workspace-tokens")))

(defn issue-workspace-token!
  "The response carries the secret **once**; it exists nowhere afterwards.

   `req` is `{:scope […] :expires-in-days n :label \"…\" :description \"…\"}`.
   `:label` is required and `:description` optional; both are bounded by the
   ledger, which refuses rather than truncates — the bounds ride back on the
   list response's `:limits`, so this side keeps no copy of them (8.4.11 ②).

   The lifetime is a duration and not an instant on purpose: the server stamps
   the expiry off the same clock it will later compare against, so this
   browser's clock cannot widen it."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/workspace-tokens/issue" :body req)))

(defn revoke-workspace-token! [token-id opts]
  (request! (assoc opts :method "POST" :path "/api/studio/workspace-tokens/revoke"
                   :body {:token-id token-id})))

(defn asset-object!
  "One pool object with the revision an in-place edit must carry back.
   `id` travels as printed EDN because ids are typed per asset type
   (attributes are qualified keywords, registry entries strings)."
  [type id opts]
  (request! (assoc opts :path (str "/api/studio/assets/object?type=" (name type)
                                   "&id=" (js/encodeURIComponent (pr-str id))))))
(defn write-asset! [action opts]
  (request! (assoc opts :method "POST" :path "/api/studio/assets/action" :body action)))

(defn set-landing-graph!
  "Set — or clear, with `:graph nil` — which graph this workspace opens on.
   Owner-only, refused server-side. `req` is `{:graph qn-or-nil}`."
  [req opts]
  (request! (assoc opts :method "POST"
                   :path "/api/studio/workspace/default-graph" :body req)))

(defn set-default-query!
  "Set — or clear, by sending nil — the query Query Studio opens a graph with.

   `req` is `{:graph qn :default-query {:query <vector> :entity <map>}}`.
   Clearing is `:default-query nil`: «this graph has no default» is a state a
   modeller must be able to return to, and it is not an empty default."
  [req opts]
  (request! (assoc opts :method "POST"
                   :path "/api/studio/graphs/default-query" :body req)))

(defn set-package-name!
  "Set — or clear, by sending nil — the RELEASE name a graph's cut versions are
   sealed under (doc 104 §8.17.7 D).

   `req` is `{:graph qn :package-name \"open-atlas/dvd\"}`. Cleared is a real
   state, not a blank: on a personal workspace it is what makes the next cut
   refuse rather than publish an email address as the package name."
  [req opts]
  (request! (assoc opts :method "POST"
                   :path "/api/studio/graphs/package-name" :body req)))

(defn variables! [opts]
  (request! (assoc opts :path "/api/studio/credentials")))

(defn save-variable! [request opts]
  (request! (assoc opts :method "POST" :path "/api/studio/credentials/save" :body request)))

(defn source-declarations!
  "What a source's resolvers DECLARE they read from it — endpoint, row path and
   attribute→path mapping per resolver.

   For source types with no introspectable schema (api), where `source-schema!`
   is refused and rightly so. This reads the model only: it opens no connection
   to the upstream and writes nothing.

   `req` is `{:source id}`."
  [req opts]
  (request! (assoc opts :method "GET"
                   :path (str "/api/studio/sources/declarations?source="
                              (js/encodeURIComponent (:source req))))))

(defn source-schema!
  "What a declared source exposes: relations, columns, keys, and connector
   capabilities. Reads the source and writes nothing.

   The request is a source id plus optional schema, names, and keys? narrowing.
   With keys? false, large catalogs can be listed without per-relation key reads."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/schema" :body req)))

(defn sources!
  "Every declared source, without needing a graph selected — the Sources page's
   register. Which keys are set is said; what they are set to never leaves the
   server."
  [opts]
  (request! (assoc opts :path "/api/studio/sources")))

(defn connect-source!
  "One step from a JDBC URL to a list of tables: validate the connection keys,
   prove the database answers, register the source, and return its relations
   with a fresh selection seed. Order matters and is the point — a source that
   registered but cannot be reached would sit in the pool looking finished, so
   a connection that does not answer registers **nothing** and comes back
   `:connected? false` with the findings that say why.

   `req` is `{:id :type :prefix}` plus the sql connection keys
   (`:jdbc_url :username :password :label :description`). Credentials should be
   `${VAR}` references; a literal one is refused by the connector's own
   write-side rules before anything is written."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/connect" :body req)))

(defn workspace-objects!
  "Every pool object this workspace may remove, by kind — read from the POOL,
   not through a graph. Takes no graph on purpose: the Workspace screen exists
   to show what no recipe selects, and asking a recipe would hide exactly that.
   It is also what keeps opening the screen from moving the Graph Editor's
   current graph."
  [opts]
  (request! (assoc opts :method "GET" :path "/api/studio/workspace/objects")))

(defn removal-plan!
  "What removing this object — or this namespace — would do. Writes nothing.
   `req` is `{:type :id}` (id as printed EDN) or `{:namespace \"pg.dvd\"}`."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/removal/plan" :body req)))

(defn remove-objects!
  "Carry out the removal. Re-planned server-side from the same request, so what
   is removed is what the plan said and not a list the browser assembled."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/removal" :body req)))

;; owner 2026-10-04: removing a declared Source and a stored credential
;; variable, plan -> execute. The server re-plans on execute and refuses while
;; anything uses either; values never travel.
(defn source-removal-plan! [source opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/removal/plan" :body {:source source})))

(defn remove-source! [source opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/remove" :body {:source source})))

;; owner 2026-10-04: a Studio administrator archives + deletes a graph in any
;; workspace; `confirm` repeats the qualified name.
(defn admin-retire-graph! [graph opts]
  (request! (assoc opts :method "POST" :path "/api/studio/admin/graphs/retire"
                   :body {:graph graph :confirm graph})))

(defn variable-removal-plan! [variable opts]
  (request! (assoc opts :method "POST" :path "/api/studio/credentials/removal/plan" :body {:variable variable})))

(defn remove-variable! [variable opts]
  (request! (assoc opts :method "POST" :path "/api/studio/credentials/remove" :body {:variable variable})))

(defn api-probe!
  "**Step ① of the api wizard** (8.4.22 裁定十六 ③): one real call to a
   registered api source, and the response tree it answered with.

   `req` is `{:source :path :method :params :sample_size}`. The probe reads the
   first `:sample_size` records (default 1000, 补裁 2026-09-04) or up to the
   connector's byte ceiling, whichever comes first, and refuses rather than
   truncating when not one complete record fits; the
   reply carries the tree, the pre-selected `:data-path` and the audit record
   the MCP path returns — which names the path and the parameter NAMES and
   never the body."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/api/probe" :body req)))

(defn api-resolver!
  "**Step ④ of the api wizard**: plan (or, with `:apply true`, write) ONE
   resolver over an api source — through the very `add_resolver` the Builder's
   MCP endpoint mounts, so what the wizard writes is what an agent would have
   written (8.4.22 裁定十六 ③).

   `req` is `{:graph :relation :source :path :data_path :outputs :namespace
   :params :apply}`, where an `outputs` entry is a column name or
   `{:name … :path …}` for a value nested inside one record."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/api/resolver" :body req)))

(defn intent!
  "**One of the twelve need-grammar sentences** (doc 106 §106.16), planned or
   applied through the SAME tool the Builder MCP endpoint dispatches.

   `tool` is `\"add_thing\"` / `\"add_relation\"` / `\"add_attribute\"` /
   `\"define_concept\"` / `\"add_metric\"` / `\"inspect_graph\"` …; `args` is that
   tool's own declared parameters, verbatim.

   **The browser decides nothing about the model.** Every namespace, resolver
   shape, `:batch`, `:field_mapping` and recipe delta is `plan-add-*`'s answer —
   the defence doc 106 §106.16 names against two planners (D1.8). Anything this
   namespace computed on its own would be a second modelling implementation with
   no test holding it to the first.

   Plan by default; pass `:apply true` inside `args` once a person has read the
   plan's `:summary`."
  [tool args opts]
  (request! (assoc opts :method "POST" :path "/api/studio/intent"
                   :body {:tool tool :args args})))

(defn create-graph! [graph opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/create" :body graph)))

(defn update-graph-recipe! [recipe opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/recipe" :body recipe)))

(defn recipe-candidates!
  "Everything in this workspace's pool, each row saying whether this graph's
   recipe names it. The tick-list behind the Graph Editor's recipe panel."
  [graph opts]
  (request! (assoc opts :path (str "/api/studio/graphs/candidates?graph="
                                   (js/encodeURIComponent graph)))))

(defn set-graph-members!
  "Tick or untick recipe members — `{:graph :kind :ids [...] :in?}` for one
   kind, or `{:graph :edits [{:kind :ids :in?} ...]}` for several at once.

   Several kinds MUST ride in one request. The server rewrites the whole recipe
   from what it read, so concurrent calls each write back a value that predates
   the others and the last one wins — which is how ticking an entity used to
   land only one of its three kinds.

   The server rewrites the whole recipe from the edited set (a recipe is a
   value; a delta would need a second shape saying what a delta means), but it
   takes a LIST, because the unit a modeller acts in is an entity, not an
   attribute: adding one means a namespace plus its attributes plus its
   resolvers, and one at a time that is twenty round trips."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/members" :body req)))

(defn rename-graph!
  "Rename the display label without changing the UUID or deployment coordinates."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/rename" :body req)))

(defn duplicate-graph!
  "Same recipe, a new graph. The **list** is copied, not the objects — the pool
   holds one of each and two graphs point at the same ones (D3.19)."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/duplicate" :body req)))

(defn- duplicate-response-options [{:keys [on-success on-error] :as opts}]
  ;; Semantic refusals carry findings even when the HTTP request succeeded.
  (assoc opts :on-success
         (fn [result]
           (if (seq (:findings result))
             (when on-error (on-error result))
             (when on-success (on-success result))))))

(defn duplicate-graph-plan! [req opts]
  (request! (assoc (duplicate-response-options opts)
                  :method "POST" :path "/api/studio/graphs/duplicate/plan" :body req)))

(defn apply-duplicate-graph! [req opts]
  (request! (assoc (duplicate-response-options opts)
                  :method "POST" :path "/api/studio/graphs/duplicate/apply"
                  :max-retries 0 :timeout-ms 60000 :body req)))

(defn merge-graphs!
  "Several graphs into a new one: the union of their recipes. Since references
   retired (D3.17) this is the only shape \"combine A and B\" has, and the right
   one — same pool, same objects, union and dedupe."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/merge" :body req)))

(defn delete-graph!
  "Permanently delete an archived Graph and its owned draft/history and version
   records. Owned local artifacts are removed; shared legacy pool objects and audit evidence are retained."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/delete" :body req)))

(defn archive-graph! [graph opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/archive" :body graph)))

(defn restore-graph! [graph opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/restore" :body graph)))

(defn studio-context! [opts]
  (request! (assoc opts :path "/api/studio/context")))

(defn auth-state! [opts]
  (request! (assoc opts :path "/api/auth/state")))

(defn register! [credentials opts]
  (request! (assoc opts :method "POST" :path "/api/auth/register" :body credentials)))

(defn login! [credentials opts]
  (request! (assoc opts :method "POST" :path "/api/auth/login" :body credentials)))

(defn logout! [opts]
  (request! (assoc opts :method "POST" :path "/api/auth/logout" :body {})))

(defn change-password! [passwords opts]
  (request! (assoc opts :method "POST" :path "/api/auth/change-password"
                   :body passwords)))

(defn studio-users! [opts]
  (request! (assoc opts :path "/api/studio/users")))

(defn create-studio-user! [user opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users" :body user)))

(defn update-studio-user! [user opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users/update" :body user)))

(defn set-studio-user-sso-email!
  "Doc 101 §8.16.9 item 3: pre-register an existing account for SSO; a blank
   `email` clears it."
  [user-id email opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users/sso-email"
                   :body {:user-id user-id :email email})))

(defn reset-studio-user-password! [user-id new-password opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users/reset-password"
                   :body {:user-id user-id :new-password new-password})))

(defn restore-studio-user! [user-id opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users/restore"
                   :body {:user-id user-id})))

(defn remove-studio-user! [user-id opts]
  (request! (assoc opts :method "POST" :path "/api/studio/users/remove"
                   :body {:user-id user-id})))

(defn add-workspace-member! [workspace email role opts]
  (request! (assoc opts :method "POST" :path "/api/studio/workspaces/members"
                   :body {:workspace workspace :email email :role role})))

(defn remove-workspace-member! [workspace email opts]
  (request! (assoc opts :method "POST" :path "/api/studio/workspaces/members/remove"
                   :body {:workspace workspace :email email})))

;; ── Observability (8.10.6) ──────────────────────────────────────────────────
;; The server decides what this caller may see; these just ask.

(defn obs-activity!
  "Recent audit events, or one subject's history when `kind`/`id` are given."
  ([opts] (obs-activity! nil nil opts))
  ([kind id opts]
   (request! (assoc opts :path (if (and kind id)
                                 (str "/api/studio/obs/activity?kind=" (js/encodeURIComponent (name kind))
                                      "&id=" (js/encodeURIComponent id))
                                 "/api/studio/obs/activity")))))

(defn obs-logs! [{:keys [level q] :as opts}]
  (request! (assoc (dissoc opts :level :q)
                   :path (str "/api/studio/obs/logs"
                              "?limit=300"
                              (when (seq level) (str "&level=" (js/encodeURIComponent level)))
                              (when (seq q) (str "&q=" (js/encodeURIComponent q)))))))

(defn obs-metrics! [opts]
  (request! (assoc opts :path "/api/studio/obs/metrics")))

(defn select-workspace! [workspace opts]
  (request! (assoc opts
                   :method "POST"
                   :path "/api/studio/context/workspace"
                   :body {:workspace workspace})))

(defn index-graph! [{:keys [root q focus depth]} opts]
  (let [params (cond-> []
                 (seq root)  (conj (str "root=" (js/encodeURIComponent root)))
                 (seq q)     (conj (str "q=" (js/encodeURIComponent q)))
                 (seq focus) (conj (str "focus=" (js/encodeURIComponent focus)))
                 depth       (conj (str "depth=" depth)))]
    (request! (assoc opts :path (str "/api/pathom/index-graph"
                                     (when (seq params) (str "?" (clojure.string/join "&" params))))))))

(defn explain! [request opts] (request! (assoc opts :method "POST" :path "/api/pathom/explain" :body request)))
(defn execute! [request opts] (request! (assoc opts :method "POST" :path "/api/pathom" :body request)))
(defn history! [opts] (request! (assoc opts :path "/api/pathom/history")))
(defn forget-history! [id opts] (request! (assoc opts :method "DELETE" :path (str "/api/pathom/history/" id))))
(defn status! [opts] (request! (assoc opts :path "/api/config/status")))
(defn events! [cursor opts] (request! (assoc opts :path (str "/api/config/events?since=" (or cursor 0)))))
(defn model-status! [opts] (request! (assoc opts :path "/api/model/status")))
(defn sync! [opts] (request! (assoc opts :method "POST" :path "/api/model/sync")))

(defn declare-source!
  "Validate EDN and check its connection; only apply? true registers it."
  [req opts]
  (request! (assoc opts :method "POST" :path "/api/studio/sources/declare" :body req)))

(defn recent-queries!
  "The data plane's recent requests for one graph — all, failed, pinned — and
   the live capture rules (proposal studio-saved-queries §4). With an id, the
   one request a debug link names."
  ([graph opts] (recent-queries! graph nil opts))
  ([graph id opts]
   (request! (assoc opts :path (str "/api/studio/graphs/recent-queries?graph="
                                    (js/encodeURIComponent graph)
                                    (when id (str "&id=" (js/encodeURIComponent id))))))))

(defn recent-query-action!
  "Pin / unpin a recorded request, or add / remove a capture rule:
   `{:action :pin|:unpin|:add-rule|:remove-rule :graph … …}`."
  [body opts]
  (request! (assoc opts :method "POST" :path "/api/studio/graphs/recent-queries/action" :body body)))
