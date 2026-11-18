(ns open.atlas.server.console
  "The operator console's HTTP surface (8.12 P3): graphs, import, graph tokens,
   and this instance's own records.

   ## Separate authentication on the shared Server listener

   Console routes authenticate an operator session. The composition root routes
   signed deployments and graph-token queries to their own handlers first.

   The console does not borrow the service key either: it signs its own
   commands as the logged-in operator (`open.atlas.server.console.ops`), so the
   audit trail says which person asked, not merely that the console did.

   ## Every route is closed until someone is signed in

   `wrap-session` refuses before any handler runs, and the two open routes are
   named explicitly rather than by pattern — a prefix rule (`/api/console/auth`
   is public) is one careless route name away from opening something else.
   There is no 'no operators configured yet' bypass; see
   `open.atlas.server.identity`.

   ## The page itself is public; everything it calls is not

   `index.html`, the stylesheet and the compiled script are served without a
   session — they have to be, because the sign-in screen is part of them. They
   are three static files with no instance state in them: what is worth
   protecting is behind `/api/console`, and that is closed.

   ## EDN, like the other two planes

   Canonical EDN out (D2.12), EDN in. One serialisation across the three
   interfaces means one set of normalisation rules to reason about."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :as compojure]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.console.example :as example]
            [open.atlas.server.console.ops :as ops]
            [open.atlas.server.console.catalog :as catalog]
            [open.atlas.server.console.groups :as groups]
            [open.atlas.server.console.sources :as sources]
            [open.atlas.server.console.packages :as packages]
            [open.atlas.server.identity :as identity]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params])
  (:import [java.io ByteArrayOutputStream InputStream]))

(def session-cookie-name "open_atlas_server_session")
(def ^:private oidc-state-cookie-name "open_atlas_server_oidc_state")

(def max-body-bytes
  "An import carries three EDN texts or a base64 bundle, so this ceiling is the
   bundle ceiling plus base64's third. Refused, never truncated: a truncated
   bundle is a valid-looking zip that fails somewhere further in, and the
   operator is then told about the wrong problem."
  (* 96 1024 1024))

;; --- static assets -----------------------------------------------------------

(def ^:private assets
  "Served by exact name, not by walking a path the caller supplies. A
   `(io/resource (str \"console/\" path))` would be one `..` away from serving
   whatever else is on the classpath — including the identity database's
   directory if it ever landed there."
  {"/"            ["index.html"   "text/html; charset=utf-8"]
   "/index.html"  ["index.html"   "text/html; charset=utf-8"]
   "/console.css" ["console.css"  "text/css; charset=utf-8"]
   "/js/main.js"  ["js/main.js"   "application/javascript; charset=utf-8"]})

(defn- asset-response [[resource content-type]]
  (when-let [url (io/resource resource)]
    {:status 200
     :headers {"content-type" content-type
               ;; No caching: a redeployed image must not be shadowed by a
               ;; browser holding last release's script against this release's
               ;; API.
               "cache-control" "no-store"}
     :body (slurp url)}))

(defn- edn-response [status body]
  {:status  status
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body    (dialect/canonical-str body)})

(defn- read-capped [^InputStream in cap]
  (if (nil? in)
    {:text ""}
    (let [buf (byte-array 8192)
          out (ByteArrayOutputStream.)]
      (loop []
        (let [n (.read in buf)]
          (cond
            (neg? n) {:text (.toString out "UTF-8")}
            (> (+ (.size out) n) cap) {:over cap}
            :else (do (.write out buf 0 n) (recur))))))))

(defn- body-params [request]
  (let [{:keys [text over]} (read-capped (:body request) max-body-bytes)]
    (cond
      over {:error (edn-response 413 {:status :error
                                      :error "the request body exceeds the cap"
                                      :expected over})}
      (str/blank? text) {:params {}}
      :else (let [parsed (try (edn/read-string text) (catch Throwable _ ::unreadable))]
              (if (or (= ::unreadable parsed) (not (map? parsed)))
                {:error (edn-response 400 {:status :error
                                           :error "the body is not readable as an EDN map"})}
                {:params parsed})))))

;; --- session -----------------------------------------------------------------

(defn- request-cookie [request name*]
  (some->> (get-in request [:headers "cookie"])
           (#(str/split % #";\s*"))
           (map #(str/split % #"=" 2))
           (filter #(= name* (first %)))
           first
           second))

(defn- session-cookie [token max-age]
  ;; No `Secure` here: this listener is loopback by default and a Secure cookie
  ;; over plain http is simply dropped, which would look like "login does not
  ;; work". Terminating TLS in front of it is a deployment decision, and the
  ;; day it is made this attribute follows it.
  (str session-cookie-name "=" token
       "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" max-age))

(def ^:private unauthenticated
  (edn-response 401 {:status :error
                     :error "sign in to use the console"
                     :findings [{:code :identity.auth/required :severity :error
                                 :path [:session]
                                 :message "this request carries no valid console session"}]}))

;; --- handlers ----------------------------------------------------------------

(defn- sign-in! [{:keys [identity-db]} request]
  (let [{:keys [params error]} (body-params request)]
    (or error
        (let [r (identity/authenticate identity-db (select-keys params [:username :password]))]
          (if (seq (:findings r))
            ;; 401 for both causes. A different status for "not bootstrapped"
            ;; would tell an unauthenticated caller something about the
            ;; instance's state; the body already says it for the operator who
            ;; is standing at the console.
            (edn-response 401 {:status :error
                               :error (:message (first (:findings r)))
                               :findings (:findings r)})
            (assoc (edn-response 200 {:status :ok :operator (:operator r)})
                   :headers
                   {"content-type" "application/edn; charset=utf-8"
                    "cache-control" "no-store"
                    "set-cookie" (session-cookie (get-in r [:session :token])
                                                 (* 12 60 60))}))))))

(defn- sign-out! [request]
  (identity/sign-out! (request-cookie request session-cookie-name))
  (assoc (edn-response 200 {:status :ok})
         :headers {"content-type" "application/edn; charset=utf-8"
                   "cache-control" "no-store"
                   "set-cookie" (str session-cookie-name
                                     "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0")}))

(defn- register! [{:keys [identity-db] :as deps} request]
  ;; The FIRST operator, and only ever the first: `create-operator!` is called
  ;; on an instance that has none, and once one exists this answers 409. There
  ;; is no second door — every later account is created by an operator who is
  ;; already signed in.
  ;;
  ;; Open on every listener (owner ruling 2026-09-01). The consequence is worth
  ;; stating rather than hiding: on an instance reachable from the network,
  ;; whoever arrives first between start-up and registration becomes the
  ;; operator. `main` says so on startup when the console is not on loopback,
  ;; and the creation is recorded.
  (if (identity/bootstrapped? identity-db)
    (edn-response 409 {:status :error
                       :error "this instance already has an operator"
                       :findings [{:code :identity.account/exists :severity :error
                                   :path [:instance]
                                   :message (str "the first operator has been created; further "
                                                 "accounts are created by a signed-in operator")}]})
    (let [{:keys [params error]} (body-params request)]
      (or error
          (let [r (identity/create-operator!
                   identity-db (select-keys params [:username :password :display-name]))]
            (if (seq (:findings r))
              (edn-response 400 {:status :error
                                 :error (:message (first (:findings r)))
                                 :findings (:findings r)})
              ;; Signed in immediately: asking someone to type the password they
              ;; just chose proves nothing and loses the one moment where the
              ;; console can say "you are in".
              (let [session (identity/authenticate identity-db (select-keys params [:username :password]))]
                (assoc (edn-response 201 {:status :ok :operator (:operator r)})
                       :headers
                       {"content-type" "application/edn; charset=utf-8"
                        "cache-control" "no-store"
                        "set-cookie" (session-cookie (get-in session [:session :token])
                                                     (* 12 60 60))}))))))))

(defn- auth-state [{:keys [identity-db oidc]} request]
  (let [operator (identity/session-operator (request-cookie request session-cookie-name))]
    (edn-response 200 (cond-> {:status :ok
                               :authenticated? (some? operator)
                               ;; The sign-in form needs to know whether this
                               ;; instance has an operator at all, so it can say
                               ;; "run the bootstrap command" instead of
                               ;; "wrong password" forever.
                               :bootstrapped? (identity/bootstrapped? identity-db)
                               ;; So the Graphs page can offer the example
                               ;; rather than describing one that is not there.
                               :example-available? (example/available?)
                               ;; `{:id :name}` when single sign-on is
                               ;; configured, nil otherwise (doc 8.16.5). The
                               ;; sign-in screen draws its button from this and
                               ;; from nothing else, so a page cannot offer a
                               ;; provider this server does not have.
                               :oidc (when oidc (oidc/provider-info oidc))}
                        operator (assoc :operator operator)))))

(def query-details obs/query-details)

(defn- obs-activity [{:keys [runtime home]} params]
  (let [n (max 1 (min (or (some-> (get params "limit") parse-long) 200) 500))
        dir (str (store/subsystem-dir (:instance runtime) "obs"))
        kind (get params "kind")
        id (get params "id")
        events (vec (take n (if (and kind id)
                             (reverse (obs/audit-for-subject dir (keyword kind) id))
                             (obs/audit-recent))))
        details (query-details home events)]
    {:status :ok
     :events (mapv (fn [raw-event]
                     (let [event (reduce (fn [e key]
                                           (if (string? (get e key)) (update e key keyword) e))
                                         raw-event [:event :outcome :entry :reason])]
                     (if (= :access/query (:event event))
                       (assoc event :queries (vec (get details (:request-id event))))
                       event))) events)}))

(defn- obs-logs [{:keys [home]} params]
  (let [n (min (or (some-> (get params "limit") parse-long) 200) 1000)
        f (java.io.File. (java.io.File. ^String home "logs") "app.jsonl")
        level (get params "level")]
    (if-not (.isFile f)
      {:status :ok :lines [] :present? false :path (str f)}
      (with-open [r (io/reader f)]
        (let [lines (into [] (comp (map #(try (json/parse-string % true)
                                              (catch Throwable _ nil)))
                                   (filter map?)
                                   (filter #(or (str/blank? (str level))
                                                (= level (:level %)))))
                          (line-seq r))]
          {:status :ok :lines (vec (take-last n lines)) :present? true :path (str f)})))))

(defn- obs-metrics [{:keys [data-port]}]
  {:status :ok
   :uptime-ms (obs/metrics-uptime-ms)
   :counters (obs/metrics-counter-snapshot)
   :rejections (obs/metrics-rejections)
   :latency (obs/metrics-latency-stats)
   ;; The endpoint is on the shared Server listener, beside /health. The address is said
   ;; by the server because a page that guesses a port links somewhere else the
   ;; moment the configuration changes.
   :prometheus-url (str "http://127.0.0.1:" data-port "/metrics")})


;; --- single sign-on (doc 8.16, apps/server's console face) -------------------
;;
;; Two routes and no more. Everything the protocol needs — PKCE, the one-time
;; state transaction, the id_token checks, the claim mapping, the linking
;; decision — is `open.atlas.common.oidc`, shared verbatim with studio. What is
;; NOT shared is what this file does with the answer: operators live in this
;; app's SQLite store, and the console does not create accounts on sign-in.

(def ^:private oidc-status
  "Doc §8.16.6's status per code, in one table so that \"the three apps answer
   the same status for the same code\" is checkable rather than remembered.

   `:identity.sso/link-conflict` is not in §8.16.6 — it is this store's refusal
   to overwrite an existing link — and answers 403 because it is the same class
   of thing as `identity-not-linked`: an administrator's job, not a login's."
  {:auth/oidc-disabled             404
   :auth/invalid-oidc-response     401
   :auth/expired-oidc-transaction  401
   :auth/invalid-oidc-claims       401
   :auth/oidc-domain-not-allowed   403
   :auth/identity-not-linked       403
   :auth/identity-not-provisioned  403
   :auth/invalid-credentials       401
   :auth/unavailable-idp           502
   :identity.sso/link-conflict     403})

(def ^:private oidc-disabled-finding
  {:code :auth/oidc-disabled :severity :error :path [:auth :oidc]
   :message (str "single sign-on is not configured on this server; set "
                 "OIDC_DISCOVERY_URL, OIDC_CLIENT_ID and OIDC_CLIENT_SECRET")})

(defn- oidc-refusal
  "Findings → the §8.16.6 status with a finding-v1 body, and one audit line.

   Nothing here can carry an address, a claim, the authorization code or a
   token: the findings have no slot for a value (finding-v1 §5), and the event
   names only the principal, which is exactly what audit-event-v1 §5 permits
   (doc 8.16.4 rule 7)."
  [findings principal]
  (obs/log-event! :auth.login.oidc.rejected
                  (cond-> {:status :error :codes (mapv :code findings)}
                    principal (assoc :actor (:subject principal)
                                     :issuer (:issuer principal))))
  (edn-response (or (some oidc-status (map :code findings)) 401)
                {:status :error
                 :error (:message (first findings))
                 :findings (vec findings)}))

(defn- oidc-account
  "The account shape `oidc/resolve-identity` reads. It carries the operator's
   own keys too, so the action the decision names does not have to look the row
   up a second time."
  [operator]
  (when operator
    (assoc operator
           :disabled (boolean (:disabled? operator))
           :linked?  (boolean (:sso-linked? operator))
           ;; Every account this lookup finds was found by its `sso_email`,
           ;; which only an operator sets — so it is pre-registered by
           ;; construction, and may bind even with a password (§8.16.9 item 3).
           :pre-registered? (some? (:sso-email operator)))))

(defn- oidc-signed-in
  "Mint the ordinary console session and send the browser where it was going.
   Same cookie, same 12 hours, same `sign-out` — SSO is one more way to obtain
   a session, not a second kind of session (doc 8.16.4 rule 6)."
  [account principal return-to]
  (let [operator (dissoc account :disabled :linked?)
        session  (identity/create-session! operator)]
    (obs/log-event! :auth.login.oidc
                    {:status :ok
                     :actor (:subject principal)
                     :issuer (:issuer principal)
                     :operator-id (:id operator)})
    {:status 302
     :headers {"location" (or return-to "/")
               "cache-control" "no-store"
               "set-cookie" (session-cookie (get-in session [:session :token])
                                            (* 12 60 60))}
     :body ""}))

(defn- oidc-begin [{:keys [oidc]} request]
  (if-not oidc
    (oidc-refusal [oidc-disabled-finding] nil)
    (let [{:keys [url state findings]} (oidc/begin oidc (get (:query-params request) "return_to"))]
      (if (seq findings)
        (oidc-refusal findings nil)
        {:status 302 :headers {"location" url "cache-control" "no-store"
                               "set-cookie" (str oidc-state-cookie-name "=" state
                                                 "; HttpOnly; SameSite=Lax; Path=/api/console/auth/oidc; Max-Age=600"
                                                 (when (str/starts-with? (get-in oidc [:config :redirect-uri]) "https://")
                                                   "; Secure"))} :body ""}))))

(defn- oidc-callback [{:keys [oidc identity-db]} request]
  (if-not oidc
    (oidc-refusal [oidc-disabled-finding] nil)
    (let [{:keys [identity return-to findings]}
          (oidc/complete oidc (:query-params request)
                         (request-cookie request oidc-state-cookie-name))]
      (if (seq findings)
        ;; No principal yet: the id_token did not survive verification, so
        ;; there is nothing here that has been established about who asked.
        (oidc-refusal findings nil)
        (let [principal {:issuer (:issuer identity) :subject (:subject identity)}
              resolved  (oidc/resolve-identity
                         {:allowed-email-domains (get-in oidc [:config :allowed-email-domains])
                          ;; The console is an operations face: who may enter is
                          ;; pre-registered by a signed-in operator, never minted
                          ;; by arriving (doc 8.16.5, §8.16.4 rule 4.5).
                          :auto-provision? false}
                         {:by-principal (fn [issuer subject]
                                          (oidc-account (identity/operator-by-principal
                                                         identity-db issuer subject)))
                          :by-email     (fn [email]
                                          (oidc-account (identity/operator-by-sso-email
                                                         identity-db email)))}
                         identity)]
          (case (:action resolved)
            :login (oidc-signed-in (:account resolved) principal return-to)
            :bind  (let [r (identity/bind-sso! identity-db (:id (:account resolved))
                                               (:issuer principal) (:subject principal))]
                     (if (seq (:findings r))
                       (oidc-refusal (:findings r) principal)
                       (oidc-signed-in (:operator r) principal return-to)))
            (oidc-refusal (:findings resolved) principal)))))))

;; --- operators ---------------------------------------------------------------
;;
;; There are no roles here yet: every operator is equal, so any signed-in one
;; may create an account, reset any password and disable any account but its
;; own. Two refusals keep that from locking the instance out of itself —
;; disabling yourself, and disabling the last enabled account — because the
;; console has no second door: `register!` answers 409 forever once one
;; operator exists, and `bootstrap-admin` needs the instance lease, which the
;; running server holds.

(defn- account-error [findings]
  (edn-response 400 {:status :error
                     :error (:message (first findings))
                     :findings (vec findings)}))

(defn- list-operators
  "Every account, with `:self?` on the caller's own row so the page can hide
   the action it is not allowed to take. Matched on `:id` — the immutable
   subject, never the username (D5.10)."
  [{:keys [identity-db]} operator]
  {:status :ok
   :operators (mapv (fn [row]
                      (cond-> row
                        (= (:id row) (:id operator)) (assoc :self? true)))
                    (identity/operators identity-db))})

(defn- create-operator! [{:keys [identity-db]} operator params]
  (let [r (identity/create-operator!
           ;; `:sso-email` pre-registers an account for single sign-on; with
           ;; it and no password the operator has no password at all (doc
           ;; 8.16.5). The store decides whether that pair is enough.
           identity-db (select-keys params [:username :password :display-name :sso-email]))]
    (if (seq (:findings r))
      (account-error (:findings r))
      (do
        ;; Recorded the way every other console mutation is: a structured event
        ;; in `app.jsonl`, naming the actor by principal. The password is not a
        ;; field here and never becomes one.
        (obs/log-event! :console/operator-created
                        {:status :ok
                         :actor (:subject (:principal operator))
                         :operator-id (get-in r [:operator :id])})
        (edn-response 201 {:status :ok :operator (:operator r)})))))

(defn- set-operator-password! [{:keys [identity-db]} operator params]
  (let [r (identity/set-password! identity-db (select-keys params [:username :password]))]
    (if (seq (:findings r))
      (account-error (:findings r))
      (do (obs/log-event! :console/operator-password-set
                          {:status :ok
                           :actor (:subject (:principal operator))
                           :operator-id (get-in r [:operator :id])})
          (edn-response 200 {:status :ok :operator (:operator r)})))))

(defn- set-operator-sso-email!
  "Doc 101 §8.16.9 item 3: link an existing operator by pre-registering the
   address its identity provider will assert. The event names the actor and
   the target by id; the address is a value and is not logged (rule 7)."
  [{:keys [identity-db]} operator params]
  (let [r (identity/set-sso-email! identity-db (select-keys params [:username :sso-email]))]
    (if (seq (:findings r))
      (account-error (:findings r))
      (do (obs/log-event! (if (get-in r [:operator :sso-email])
                            :console/operator-sso-preregistered
                            :console/operator-sso-preregistration-cleared)
                          {:status :ok
                           :actor (:subject (:principal operator))
                           :operator-id (get-in r [:operator :id])})
          (edn-response 200 {:status :ok :operator (:operator r)})))))

(defn- set-operator-disabled! [{:keys [identity-db]} operator {:keys [username disabled?]}]
  (let [disabled? (boolean disabled?)
        accounts  (identity/operators identity-db)
        target    (first (filter #(= (str/lower-case (str (:username %)))
                                     (str/lower-case (str username)))
                                 accounts))
        enabled-after (count (remove #(or (:disabled? %)
                                          (and disabled? (= (:id %) (:id target))))
                                     accounts))]
    (cond
      (and disabled? target (= (:id target) (:id operator)))
      (account-error [{:code :identity.account/self-disable :severity :error
                       :path [:username]
                       :message (str "an operator cannot disable its own account; ask another "
                                     "operator to do it")}])

      (and disabled? target (zero? enabled-after))
      (account-error [{:code :identity.account/last-operator :severity :error
                       :path [:username]
                       :message (str "this is the last enabled operator; disabling it would "
                                     "close the console with no way back in")}])

      :else
      (let [r (identity/set-disabled! identity-db username disabled?)]
        (if (seq (:findings r))
          (account-error (:findings r))
          (do (obs/log-event! :console/operator-disabled-set
                              {:status :ok
                               :actor (:subject (:principal operator))
                               :operator-id (get-in r [:operator :id])
                               :disabled? (get-in r [:operator :disabled?])})
              (edn-response 200 {:status :ok :operator (:operator r)})))))))

(defn- with-params
  "Run `f` with the parsed body, or answer the parse refusal."
  [request f]
  (let [{:keys [params error]} (body-params request)]
    (or error (edn-response 200 (f params)))))

(defn- with-body
  "Like `with-params`, for a handler that decides its own status."
  [request f]
  (let [{:keys [params error]} (body-params request)]
    (or error (f params))))

(defn- bundles-dir
  "The assembly root may name the package directory (`:bundles-dir`); otherwise
   it is discovered the way a downloaded Server discovers it. Explicit beats
   ambient for the same reason the runtime is an argument here rather than an
   atom — a process-wide answer cannot be exercised twice in one JVM."
  ^java.io.File [deps]
  (or (some-> (:bundles-dir deps) io/file) (packages/bundles-dir)))

(defn- source-change! [deps operator event operation params]
  (let [result (operation deps params)]
    (when (= :ok (:status result))
      (obs/log-event! event (cond-> {:status :ok :actor (:subject (:principal operator))}
                             (:id params) (assoc :source (:id params))
                             (:name params) (assoc :variable (:name params)))))
    result))

(defn- authorized-routes [{:keys [runtime] :as deps} operator]
  (let [actor (:principal operator)]
    (compojure/routes
     (compojure/GET "/api/console/variables" _ (edn-response 200 (sources/list-variables deps)))
     (compojure/POST "/api/console/variables/remove" request
       (with-params request #(source-change! deps operator :console/credential-variable-removed sources/remove-variable! %)))
     (compojure/POST "/api/console/variables" request
       (with-params request #(source-change! deps operator :console/credential-variable-saved sources/save-variable! %)))
     (compojure/GET "/api/console/sources" _ (edn-response 200 (sources/list-sources deps)))
     (compojure/POST "/api/console/sources/lifecycle" request
       (with-params request #(source-change! (assoc deps :actor actor) operator :console/source-lifecycle sources/source-lifecycle! %)))
     (compojure/POST "/api/console/sources" request
       (with-params request #(source-change! (assoc deps :actor actor) operator :console/source-saved sources/save-source! %)))
     (compojure/POST "/api/console/sources/variable" request
       (with-params request #(source-change! deps operator :console/credential-variable-saved sources/save-variable! %)))
     (compojure/POST "/api/console/sources/test" request
       (with-params request #(sources/test-source deps %)))
     (compojure/POST "/api/console/groups/save" request
       (with-params request #(groups/save-group! deps actor %)))
     (compojure/POST "/api/console/groups/delete" request
       (with-params request #(groups/remove-group! deps actor %)))
     (compojure/POST "/api/console/groups/sources" request
       (with-params request #(sources/assign-group! deps actor %)))
     (compojure/POST "/api/console/groups/assign" request
       (with-params request #(groups/assign! deps actor %)))
     (compojure/GET "/api/console/graphs/page" request
       (edn-response 200 (catalog/graphs-page deps (:query-params request))))
     (compojure/GET "/api/console/graphs/detail" request
       (edn-response 200 (catalog/graph-detail deps (:query-params request))))
     (compojure/GET "/api/console/graphs/sources" request
       (edn-response 200 (sources/graph-sources deps (:query-params request))))
     (compojure/GET "/api/console/graphs/versions" request
       (edn-response 200 (catalog/versions-page deps (:query-params request))))
     (compojure/GET "/api/console/graphs/review" request
       (edn-response 200 (ops/review-publication deps (:query-params request))))
     (compojure/GET "/api/console/tokens/page" request
       (edn-response 200 (catalog/tokens-page deps (:query-params request))))
     (compojure/GET  "/api/console/graphs" _ (edn-response 200 (ops/list-graphs deps)))
     (compojure/POST "/api/console/graphs/import" request
       (with-params request #(ops/import-graph deps actor %)))
     ;; The first half of the two-step install (doc 104 §8.17.7 C). Same auth
     ;; as the import it precedes: it reads a bundle somebody uploaded and
     ;; reports what this machine is missing, which is not a fact for an
     ;; unauthenticated caller to collect.
     (compojure/POST "/api/console/graphs/import/plan" request
       (with-params request #(assoc (ops/plan-import % deps) :status :ok)))
     ;; The local package directory (doc 104 §8.17.7 E).
     (compojure/GET  "/api/console/packages" _
       (edn-response 200 (packages/list-packages
                          (bundles-dir deps)
                          #(graph/serving-version (:graph runtime) %) deps)))
     (compojure/POST "/api/console/packages/install" request
       (with-params request
         #(packages/install! deps actor (bundles-dir deps) %
                             ((:now deps (constantly nil))))))
     ;; One button, the ordinary path: the packaged model is sealed against
     ;; this instance's copy of the database and then imported like anything
     ;; else — same four signed commands, same audit, same refusals.
     (compojure/POST "/api/console/graphs/example" _
       (let [{:keys [files findings]} (example/artifact-files (:instance runtime)
                                                              ((:now deps (constantly nil))))]
         (if (seq findings)
           (edn-response 400 (ops/finding-error findings))
           (edn-response 200 (ops/import-graph deps actor {:files files})))))
     (compojure/POST "/api/console/graphs/archive" request
       (with-params request #(ops/set-state deps actor % :archived)))
     (compojure/POST "/api/console/graphs/restore" request
       (with-params request #(ops/set-state deps actor % :active)))
     (compojure/POST "/api/console/graphs/delete" request
       (with-params request #(ops/delete-graph deps actor %)))
     (compojure/POST "/api/console/graphs/publish" request
       (with-params request #(ops/publish-graph deps actor %)))
     (compojure/POST "/api/console/graphs/enabled" request
       (with-params request #(ops/set-enabled deps actor %)))
     (compojure/GET  "/api/console/tokens" _
       (edn-response 200 {:status :ok
                          :tokens (vec (token/all-records (:token runtime)))}))
     (compojure/POST "/api/console/tokens/issue" request
       (with-params request #(ops/issue-token deps actor %)))
     (compojure/POST "/api/console/tokens/revoke" request
       (with-params request #(ops/revoke-token deps actor %)))
     (compojure/GET  "/api/console/operators" _
       (edn-response 200 (list-operators deps operator)))
     (compojure/POST "/api/console/operators" request
       (with-body request #(create-operator! deps operator %)))
     (compojure/POST "/api/console/operators/password" request
       (with-body request #(set-operator-password! deps operator %)))
     (compojure/POST "/api/console/operators/sso-email" request
       (with-body request #(set-operator-sso-email! deps operator %)))
     (compojure/POST "/api/console/operators/disabled" request
       (with-body request #(set-operator-disabled! deps operator %)))
     (compojure/GET  "/api/console/obs/activity" request
       (edn-response 200 (obs-activity deps (:query-params request))))
     (compojure/GET  "/api/console/obs/logs" request
       (edn-response 200 (obs-logs deps (:query-params request))))
     (compojure/GET  "/api/console/obs/metrics" _ (edn-response 200 (obs-metrics deps))))))

(def ^:private public-routes
  "Named one by one, deliberately. A prefix rule is one careless route name
   away from publishing something that was never meant to be public."
  #{"/api/console/auth/state" "/api/console/auth/sign-in" "/api/console/auth/sign-out"
    ;; Open by ruling, and closed by state: it answers 409 the moment an
    ;; operator exists (see `register!`).
    "/api/console/auth/register"
    ;; The two SSO routes are open by necessity: they are how a person who has
    ;; no session yet gets one. They are closed by configuration instead —
    ;; without OIDC_* they answer 404 `auth/oidc-disabled` (doc 8.16.6).
    "/api/console/auth/oidc" "/api/console/auth/oidc/callback"})

(defn- handler
  "The routing itself. Wrapped below — `routes` is what callers mount.

   `deps`: `{:runtime … :identity-db … :instance-id … :service-key-id …
             :actor-key-id … :actor-secret … :home … :data-port …
             :oidc …}` — `:oidc` is the handle from `open.atlas.common.oidc/open`,
   or nil when single sign-on is not configured (doc 8.16.5)."
  [deps]
  (fn [request]
    (let [uri (:uri request)]
      (cond
        (contains? assets uri)
        (or (asset-response (get assets uri))
            ;; A missing asset is cheaper to explain here than as a blank page
            ;; with a 404 in the network tab. BOTH causes are named on purpose:
            ;; the file can be present in `resources-console` and still be
            ;; absent from the classpath, and an operator told only about the
            ;; build will rerun a build that already succeeded.
            {:status 500 :headers {"content-type" "text/plain"}
             :body (str "console asset " (first (get assets uri))
                        " is not on the classpath. Either the build has not run"
                        " (`npm run console:release` writes resources-console/js),"
                        " or `resources-console` is not on this basis's :paths"
                        " (the production basis is `clojure -M:prod:drivers:runtime`).")})

        (contains? public-routes uri)
        (case uri
          "/api/console/auth/state"    (auth-state deps request)
          "/api/console/auth/sign-in"  (sign-in! deps request)
          "/api/console/auth/sign-out" (sign-out! request)
          "/api/console/auth/register" (register! deps request)
          "/api/console/auth/oidc"          (oidc-begin deps request)
          "/api/console/auth/oidc/callback" (oidc-callback deps request))

        :else
        (if-let [operator (identity/session-operator
                           (request-cookie request session-cookie-name))]
          (or ((authorized-routes deps operator) request)
              {:status 404 :headers {"content-type" "text/plain"} :body "not found"})
          ;; 401 for an unknown path too, while unauthenticated: answering 404
          ;; here would let anyone map which routes exist.
          unauthenticated)))))

(defn routes
  "Ring handler. Starts no server — it can be fed a request map directly."
  [deps]
  ;; `wrap-params` only to read the query string; the console's request bodies
  ;; are EDN and are read by `body-params`, never form-decoded.
  (params/wrap-params (handler deps)))

(defn start! [{:keys [host port] :as deps}]
  {:server (jetty/run-jetty (routes deps)
                            {:port (or port 3009) :host (or host "127.0.0.1") :join? false})})

(defn stop! [server] (.stop server))
