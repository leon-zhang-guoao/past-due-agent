(ns open.atlas.server.main
  "Standalone Server: one listener, separate control, query and operator authentication."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.derived]
            [open.atlas.common.connector.sql]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.semantic-static]
            [open.atlas.runtime.source-bindings :as source-bindings]
            [open.atlas.runtime.credentials :as credentials]
            [ring.adapter.jetty :as jetty]
            [open.atlas.common.oidc :as oidc]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.server.console :as console]
            [open.atlas.server.console.example :as example]
            [open.atlas.server.data :as data]
            [open.atlas.server.identity :as identity]
            [open.atlas.server.mcp :as mcp]
            [open.atlas.server.http :as control]
            [ring.core.protocols])
  (:gen-class))

;; The three connector namespaces above are required for their side effect and
;; for nothing else: a connector registers itself into the engine SPI by
;; `defmethod`, so being absent from the classpath does not fail loudly — every
;; sql resolver compiles to a warning, is excluded, and the data plane then
;; answers 200 with no rows. That failure reads as "the graph is empty", which
;; is the most expensive kind of wrong answer to attribute (8.12.3).

(defn- present [value]
  (some-> value str str/trim not-empty))

(defn- utc-now []
  (str (.truncatedTo (java.time.Instant/now)
                      java.time.temporal.ChronoUnit/SECONDS)))

(defn config
  "Build standalone-server configuration from a string-keyed environment map.
   Required secrets are intentionally separate: actor signing, service requests,
   and graph-token verification never share key material."
  [environment]
  (let [get* #(present (get environment %))
        required ["OPEN_ATLAS_SERVER_HOME"
                  "OPEN_ATLAS_SERVER_INSTANCE"
                  "OPEN_ATLAS_SERVER_TOKEN_PEPPER"
                  "OPEN_ATLAS_SERVER_SERVICE_KEY_ID"
                  "OPEN_ATLAS_SERVER_SERVICE_SECRET"
                  "OPEN_ATLAS_SERVER_ACTOR_KEY_ID"
                  "OPEN_ATLAS_SERVER_ACTOR_SECRET"]
        missing (vec (remove #(get* %) required))
        ;; Single sign-on (doc 8.16.5): nil = not enabled and that is not an
        ;; error, findings = the three keys are set and OIDC_REDIRECT_URI is
        ;; not, which refuses startup rather than guessing a redirect the IdP
        ;; will reject somewhere we cannot see.
        oidc-result (oidc/config environment)
        findings (cond-> (vec (:findings oidc-result))
                   (and (get* "OPEN_ATLAS_SERVER_TLS_KEYSTORE")
                        (not (get* "OPEN_ATLAS_SERVER_TLS_PASSWORD")))
                   (conj {:code :server.listener/tls-password-missing :severity :error
                          :path [:env "OPEN_ATLAS_SERVER_TLS_PASSWORD"]
                          :message "a TLS keystore is configured but its password is not"
                          :expected "the keystore's password"})
                   (seq missing)
                   (conj {:code :server.config/missing :severity :error
                          :path [:environment]
                          :message "standalone Server requires explicit runtime and signing configuration"
                          :missing missing}))]
    (if (seq findings)
      ;; The server-config refusal comes first when both apply: the missing
      ;; secrets are the older, larger problem.
      {:findings (vec (reverse findings))}
      {:home           (get* "OPEN_ATLAS_SERVER_HOME")
       :instance-id    (get* "OPEN_ATLAS_SERVER_INSTANCE")
       :token-pepper   (get* "OPEN_ATLAS_SERVER_TOKEN_PEPPER")
       :service-key-id (get* "OPEN_ATLAS_SERVER_SERVICE_KEY_ID")
       :service-secret (get* "OPEN_ATLAS_SERVER_SERVICE_SECRET")
       :actor-key-id   (get* "OPEN_ATLAS_SERVER_ACTOR_KEY_ID")
       :actor-secret   (get* "OPEN_ATLAS_SERVER_ACTOR_SECRET")
       :port (Long/parseLong (or (get* "OPEN_ATLAS_SERVER_PORT") (get* "OPEN_ATLAS_SERVER_CONSOLE_PORT") "3009"))
       ;; Loopback unless someone says otherwise, and saying otherwise is a
       ;; deliberate act with a warning attached (see `-main`). A container
       ;; must set this — inside one, 127.0.0.1 means the ports are reachable
       ;; from nothing at all — which is why the image sets it explicitly
       ;; rather than the default being widened for everybody's convenience.
       :bind           (or (get* "OPEN_ATLAS_SERVER_BIND") "127.0.0.1")
       ;; HTTPS on the one listener (control, data, MCP, console) when a
       ;; PKCS12 keystore is named; its password is then required (finding above).
       :tls            (when-let [ks (get* "OPEN_ATLAS_SERVER_TLS_KEYSTORE")]
                         {:keystore ks :password (get* "OPEN_ATLAS_SERVER_TLS_PASSWORD")})
       ;; nil when SSO is off; `start!` opens a handle from it and hands that
       ;; to the console.
       :oidc-config    (:config oidc-result)
       :findings       []})))

(defn- data-plane-routes
  "`/eql` and `/mcp` on ONE listener: same graph token, same `instance/answer`,
   different wire language. Splitting them across ports would make \"the data
   plane has exactly one kind of key\" (D5.7) a sentence with an exception in
   it — and it would give the standalone Server a shape Studio does not have,
   which D1.13 forbids for precisely this reason."
  [shell]
  (mcp/routes
   (assoc shell
          :on-auth-failure #(obs/log-event! :mcp-auth-failed (assoc % :status :denied))
          ;; Streaming belongs to the HTTP adapter, not to MCP: the shell hands
          ;; back a writer fn and this is where it becomes a Ring body.
          :sse-writer
          (fn [write!]
            (reify ring.core.protocols/StreamableResponseBody
              (write-body-to-stream [_ _response out]
                (write! out)))))))

(defn routes [control-deps data-deps console-deps]
  (let [control-handler (control/routes control-deps)
        data-handler (data/routes (assoc data-deps :extra-routes (data-plane-routes data-deps)))
        console-handler (console/routes console-deps)]
    (fn [request]
      (let [uri (:uri request)]
        (cond
          (and (or (= uri "/mcp") (str/starts-with? uri "/mcp/"))
               (not (re-matches #"/mcp/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?" uri)))
          {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Use /mcp/<graph-uuid> with a token for that Graph."}
          (= uri "/control") (control-handler request)
          (or (contains? #{"/eql" "/mcp" "/health" "/metrics" "/meta/contracts"} uri)
              (str/starts-with? uri "/mcp/"))
          (let [started (System/nanoTime)
                response (data-handler request)]
            (when (str/starts-with? uri "/mcp/")
              (obs/log-event! :mcp-request
                              {:method (some-> (:request-method request) name)
                               :path uri :http-status (:status response)
                               :request-id (get-in response [:headers "X-Request-Id"])
                               :duration-ms (/ (- (System/nanoTime) started) 1000000.0)}))
            response)
          :else (console-handler request))))))

(defn start!
  "Start the runtime and one HTTP listener. Validate deployment credentials before opening resources."
  [{:keys [home instance-id token-pepper service-key-id service-secret
           actor-key-id actor-secret port bind oidc-config tls]
    :or {bind "127.0.0.1" port 3009}}]
  (let [control-deps {:instance-id instance-id :service-keys {service-key-id service-secret}
                      :service-key #(get {service-key-id service-secret} %) :now utc-now}
        findings (control/startup-findings control-deps)]
    (if (seq findings)
      {:findings findings}
      (do
        (obs/log-configure! {:retention-home home :log-file (str (io/file home "logs" "app.jsonl"))})
        (let [runtime (instance/start!
                       {:instance-id instance-id :root home :token-pepper token-pepper
                        :resolve-source (source-bindings/file-resolver
                                         (str (io/file home "source-bindings.edn"))
                                         (fn [id entry] (credentials/resolve-config home id entry)))
                        :actor-key #(when (= actor-key-id %) actor-secret)
                        :service-allows? (fn [principal _]
                                           (= principal {:issuer "service-key" :subject service-key-id}))})]
          (if (seq (:findings runtime)) runtime
            (let [oidc-handle (atom nil)]
              (try
                (instance/recover! runtime)
                (when oidc-config (reset! oidc-handle (oidc/open oidc-config)))
                (let [console-deps {:runtime runtime :identity-db (identity/open! (:instance runtime))
                                    :instance-id instance-id :service-key-id service-key-id
                                    :actor-key-id actor-key-id :actor-secret actor-secret
                                    :oidc @oidc-handle :home home :now utc-now :data-port port}
                      handler (routes (assoc control-deps :store (:graph runtime) :reads (:reads runtime))
                                      {:instance (assoc runtime :query-log? true) :now utc-now} console-deps)
                      server (jetty/run-jetty handler (data/jetty-options {:port port :host bind :tls tls}))]
                  {:runtime runtime :server server :oidc @oidc-handle :findings []})
                (catch Throwable t
                  (when @oidc-handle (oidc/close! @oidc-handle))
                  (instance/stop! runtime)
                  (throw t))))))))))

(defn stop! [{:keys [runtime server oidc]}]
  (when server (.stop server))
  (when oidc (oidc/close! oidc))
  (when runtime (instance/stop! runtime)))

(defn -main [& _]
  (let [{:keys [findings] :as cfg} (config (System/getenv))]
    (if (seq findings)
      (do (binding [*out* *err*]
            (println "Server refused to start:")
            (doseq [finding findings]
              (println " " (:message finding))
              ;; **Name what is missing** (finding-v1 «点名位置»; 0.2 acceptance
              ;; defect 3). The finding has carried `:missing` all along; the
              ;; console printed only `:message`, so the operator was told that
              ;; seven items are required and never which of them this host
              ;; lacks — which is the whole of what they needed.
              (doseq [item (:missing finding)]
                (println "    missing:" item))
              (when-let [expected (:expected finding)]
                (if (sequential? expected)
                  (doseq [item expected] (println "    expected:" item))
                  (println "    expected:" expected)))))
          (System/exit 2))
      (let [{:keys [findings] :as started} (start! cfg)]
        (if (seq findings)
          (do (binding [*out* *err*]
                (println "Server refused to start:" (pr-str findings)))
              (System/exit 2))
          (do
            (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! started)))
            ;; First start under this home: put the packaged example's database
            ;; where the console can seal an artifact against it (8.14). The
            ;; import itself stays a deliberate act — see console.example.
            (example/materialise! (:instance (:runtime started)))
            (let [host (str (if (:tls cfg) "https" "http") "://" (:bind cfg))
                  bind (:bind cfg)]
              (println (str "Open Atlas Server control plane: " host ":"
                            (:port cfg) "/control"))
              (println (str "Open Atlas Server data plane:    " host ":"
                            (:port cfg) "/eql"))
              (println (str "Open Atlas Server data MCP:      " host ":"
                            (:port cfg) "/mcp/<graph-uuid>   (graph token)"))
              (println (str "Open Atlas Server console:       " host ":"
                            (:port cfg) "/   (operator session)"))
              (when-not (identity/bootstrapped? (identity/open! (:instance (:runtime started))))
                (println (str "  → no operator yet: open the console and register the first one"
                              (when-not (contains? #{"127.0.0.1" "localhost" "::1"} bind)
                                (str "\n  ⚠ and this console is NOT on loopback, so whoever reaches "
                                     "it first can claim that account"))))) 
              (when-not (contains? #{"127.0.0.1" "localhost" "::1"} bind)
                ;; Said out loud because it is the one line that distinguishes
                ;; this run from every other: all three listeners are reachable
                ;; from off this machine, and between them and a stranger there
                ;; is only a key, a token and a password.
                (println (str "  ⚠ these listeners are NOT on loopback (bind " bind
                              ") — the control plane, the data plane and the "
                              "console are all reachable from the network"))))
            @(promise)))))))
