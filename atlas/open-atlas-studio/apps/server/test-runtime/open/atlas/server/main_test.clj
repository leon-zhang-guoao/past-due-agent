(ns open.atlas.server.main-test
  "Startup contract of the standalone Server's composition root. Runs on the
   production basis: `clojure -M:prod:test-prod`."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.server.main :as main]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.server.http :as control]
            [open.atlas.server.data :as data]
            [open.atlas.server.console :as console]
            [open.atlas.server.identity :as identity]
            [ring.adapter.jetty :as jetty]))

(def valid-env
  {"OPEN_ATLAS_SERVER_HOME" "/tmp/open-atlas-test"
   "OPEN_ATLAS_SERVER_INSTANCE" "server-test"
   "OPEN_ATLAS_SERVER_TOKEN_PEPPER" "token-pepper"
   "OPEN_ATLAS_SERVER_SERVICE_KEY_ID" "studio"
   "OPEN_ATLAS_SERVER_SERVICE_SECRET" "service-secret"
   "OPEN_ATLAS_SERVER_ACTOR_KEY_ID" "studio-actor"
   "OPEN_ATLAS_SERVER_ACTOR_SECRET" "actor-secret"})

(deftest standalone-server-requires-every-separate-secret
  (let [r (main/config (dissoc valid-env "OPEN_ATLAS_SERVER_ACTOR_SECRET"))]
    (is (= [:server.config/missing] (mapv :code (:findings r))))
    (is (= ["OPEN_ATLAS_SERVER_ACTOR_SECRET"]
           (get-in r [:findings 0 :missing])))))

(deftest standalone-server-uses-one-port
  (is (= 3009 (:port (main/config valid-env))))
  (is (= 3010 (:port (main/config (assoc valid-env "OPEN_ATLAS_SERVER_PORT" "3010")))))
  (is (= 3011 (:port (main/config (assoc valid-env "OPEN_ATLAS_SERVER_CONSOLE_PORT" "3011")))))
  (is (= 3010 (:port (main/config (assoc valid-env "OPEN_ATLAS_SERVER_PORT" "3010"
                                                "OPEN_ATLAS_SERVER_CONSOLE_PORT" "3011")))))
  (is (= 3009 (:port (main/config (assoc valid-env "OPEN_ATLAS_SERVER_CONTROL_PORT" "8443"
                                                "OPEN_ATLAS_SERVER_DATA_PORT" "8444"))))))

(deftest control-validation-failure-never-starts-a-listener
  (let [started? (atom false)
        finding {:code :deploy.authz/service-not-permitted}]
    (with-redefs [control/startup-findings (constantly [finding])
                  jetty/run-jetty (fn [& _] (reset! started? true))]
      (is (= {:findings [finding]} (main/start! (main/config valid-env)))))
    (is (false? @started?))))

(deftest unified-routing-selects-authentication-by-path
  (with-redefs [control/routes (fn [_] (constantly :control))
                data/routes (fn [_] (constantly :data))
                console/routes (fn [_] (constantly :console))]
    (let [handler (main/routes {} {} {})]
      (doseq [path ["/eql" "/mcp/00000000-0000-0000-0000-000000000001" "/health" "/metrics"]]
        (is (= :data (handler {:uri path}))))
      (doseq [uri ["/mcp" "/mcp/" "/mcp/sse" "/mcp/messages" "/mcp/not-a-uuid"]]
        (is (= 404 (:status (handler {:uri uri :request-method :post})))))
      (is (= :control (handler {:uri "/control"})))
      (doseq [path ["/" "/app" "/api/console/graphs" "/api/console/tokens/issue" "/control-other"]]
        (is (= :console (handler {:uri path})))))))

(deftest listener-start-failure-releases-runtime
  (let [stopped? (atom false)]
    (with-redefs [instance/start! (constantly {:instance {} :findings []})
                  instance/recover! (constantly nil)
                  instance/stop! (fn [_] (reset! stopped? true))
                  identity/open! (constantly nil)
                  jetty/run-jetty (fn [& _] (throw (ex-info "port occupied" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"port occupied"
                           (main/start! (main/config valid-env)))))
    (is @stopped?)))

(deftest a-single-listener-serves-all-endpoints
  (let [root (str (java.nio.file.Files/createTempDirectory
                  "server-unified-" (make-array java.nio.file.attribute.FileAttribute 0)))
        started (main/start! (assoc (main/config valid-env) :home root :port 0))
        port (.getLocalPort (first (.getConnectors (:server started))))
        client (java.net.http.HttpClient/newHttpClient)
        request! (fn [method path headers]
                   (let [builder (java.net.http.HttpRequest/newBuilder
                                  (java.net.URI/create (str "http://127.0.0.1:" port path)))]
                     (doseq [[k v] headers] (.header builder k v))
                     (.method builder method (java.net.http.HttpRequest$BodyPublishers/noBody))
                     (.send client (.build builder) (java.net.http.HttpResponse$BodyHandlers/ofString))))]
    (try
      (is (= 1 (count (.getConnectors (:server started)))))
      (is (= 200 (.statusCode (request! "GET" "/health" {}))))
      (is (= 200 (.statusCode (request! "GET" "/api/console/auth/state" {}))))
      (doseq [path ["/control" "/eql" "/mcp/00000000-0000-0000-0000-000000000001" "/api/console/tokens/issue"]]
        (is (= 401 (.statusCode (request! "POST" path {}))) path))
      (doseq [path ["/control" "/api/console/tokens/issue"]]
        (is (= 401 (.statusCode (request! "POST" path {"authorization" "Bearer query-token"}))) path))
      (finally (main/stop! started)))))

(deftest the-listeners-are-on-loopback-unless-someone-says-otherwise
  (testing "a default that is safe, and a widening that has to be asked for"
    (is (= "127.0.0.1" (:bind (main/config valid-env))))
    (is (= "0.0.0.0" (:bind (main/config (assoc valid-env "OPEN_ATLAS_SERVER_BIND"
                                                "0.0.0.0")))))))

;; --- single sign-on (doc 8.16.3, read on this composition root's env map) ----

(deftest an-environment-without-oidc-is-the-server-that-was-there-before
  (testing "§8.16.7: with no OIDC_* set, nothing about startup changes"
    (let [r (main/config valid-env)]
      (is (empty? (:findings r)))
      (is (nil? (:oidc-config r))
          "nil is 'single sign-on is off', and off is not a finding"))))

(deftest the-three-keys-without-a-redirect-uri-refuse-to-start
  (testing "§8.16.3: a guessed redirect URI is rejected in someone else's logs"
    (let [r (main/config (merge valid-env
                                {"OIDC_DISCOVERY_URL" "https://idp.example/.well-known/openid-configuration"
                                 "OIDC_CLIENT_ID" "console-client"
                                 "OIDC_CLIENT_SECRET" "console-secret"}))]
      (is (= [:oidc.config/missing] (mapv :code (:findings r))))
      (is (nil? (:oidc-config r)))
      (testing "and the refusal names no secret"
        (is (not (clojure.string/includes? (pr-str (:findings r)) "console-secret")))))))

(deftest a-complete-oidc-configuration-is-carried-to-the-console
  (let [r (main/config (merge valid-env
                              {"OIDC_DISCOVERY_URL" "https://idp.example/.well-known/openid-configuration"
                               "OIDC_CLIENT_ID" "console-client"
                               "OIDC_CLIENT_SECRET" "console-secret"
                               "OIDC_REDIRECT_URI" "https://console.example/api/console/auth/oidc/callback"
                               "OIDC_PROVIDER_NAME" "Corp SSO"}))]
    (is (empty? (:findings r)))
    (is (= "Corp SSO" (get-in r [:oidc-config :provider-name])))
    (is (= "console-client" (get-in r [:oidc-config :client-id])))))

(deftest a-missing-secret-is-still-reported-first-when-both-refusals-apply
  (testing "the older, larger problem leads — but neither is dropped"
    (let [r (main/config (merge (dissoc valid-env "OPEN_ATLAS_SERVER_ACTOR_SECRET")
                                {"OIDC_DISCOVERY_URL" "https://idp.example/x"
                                 "OIDC_CLIENT_ID" "console-client"
                                 "OIDC_CLIENT_SECRET" "console-secret"}))]
      (is (= [:server.config/missing :oidc.config/missing] (mapv :code (:findings r)))))))

(deftest a-keystore-turns-the-listener-into-https
  (is (nil? (:tls (main/config valid-env))))
  (is (= {:keystore "/s.p12" :password "pw"}
         (:tls (main/config (assoc valid-env "OPEN_ATLAS_SERVER_TLS_KEYSTORE" "/s.p12"
                                   "OPEN_ATLAS_SERVER_TLS_PASSWORD" "pw")))))
  (is (= [:server.listener/tls-password-missing]
         (map :code (:findings (main/config (assoc valid-env "OPEN_ATLAS_SERVER_TLS_KEYSTORE" "/s.p12")))))))
