(ns open.atlas.common.connector.api.impl.conn
  "Process-wide keep-alive HTTP connection pools, three tiers
   (tls > insecure > default). Migrated from open-atlas ent.dx.http.conn.

   Without a :connection-manager, clj-http builds a throwaway connection
   manager per request, so every external call pays a full TCP + TLS
   handshake. Three tiers:
     - `manager`          — default; verifies server certs against the JVM trust store.
     - `insecure-manager` — trusts ALL server certs (curl -k), for :ssl_insecure? sources.
     - `tls-manager`      — mutual TLS: presents a CLIENT certificate; one pool per
                            distinct :tls config, cached.

   TLS validation errors are produced as data here and turned into findings
   by impl.validate — this ns never logs and never leaks resolved secrets."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.conn-mgr :as conn-mgr]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.spi :as spi]))

(defonce ^{:doc "Shared reusable (pooling) connection manager. Thread-safe."}
  manager
  (delay
    (conn-mgr/make-reusable-conn-manager
     {:timeout           30    ;; sec an idle conn is kept alive in the pool
      :threads           50    ;; total max connections
      :default-per-route 20})))

(defonce ^{:doc "Pooling manager that TRUSTS ALL TLS certs (verification off) —
   the HTTPS analogue of curl -k. Never the default; a source must opt in via
   :ssl_insecure?. clj-http ignores a request-level :insecure? when a
   :connection-manager is supplied, so the trust-all socket factory must live
   in the manager itself."}
  insecure-manager
  (delay
    (conn-mgr/make-reusable-conn-manager
     {:timeout 30 :threads 50 :default-per-route 20 :insecure? true})))

(defn- base-dir
  "Anchor for RELATIVE cert-store paths — never the process cwd. The order
   ($OPEN_ATLAS_CONFIG_PATH → $OPEN_ATLAS_HOME → ~/.open-atlas) is written once,
   on `spi/workspace-base-dir` (doc 104 §8.17.7 H item 1)."
  []
  (spi/workspace-base-dir))

(defn- workspace-file ^java.io.File [path]
  (let [f (io/file path)]
    (if (.isAbsolute f) f (io/file (base-dir) path))))

(defn resolve-tls-config
  "Resolve a raw `:tls` source-config map into the shape clj-http's
   make-reusable-conn-manager expects: ${VAR} passwords resolved, relative
   store paths anchored under the workspace base, defaults applied
   (:keystore_type PKCS12, :truststore_type JKS). nil when `tls` is nil/blank.
   Never touches the network; safe at validate time."
  [tls]
  (when (seq tls)
    (let [{:keys [keystore keystore_pass keystore_type
                  truststore truststore_pass truststore_type]} tls]
      (cond-> {}
        keystore   (assoc :keystore      (workspace-file keystore)
                          :keystore-type (or (not-empty (str keystore_type)) "PKCS12")
                          :keystore-pass (interp/resolve-value keystore_pass))
        truststore (assoc :trust-store      (workspace-file truststore)
                          :trust-store-type (or (not-empty (str truststore_type)) "JKS")
                          :trust-store-pass (interp/resolve-value truststore_pass))))))

(defn tls-config-problems
  "Structural problems with a `:tls` block that only surface once real paths
   and secrets are resolved — reported at validate time, not first request.
   Returns [{:path [...] :message \"...\"} ...] (empty when clean); the
   messages name keys and RESOLVED FILE PATHS (configuration), never secret
   values. impl.validate turns these into findings."
  [tls]
  (if-not (seq tls)
    []
    (let [resolved (resolve-tls-config tls)
          ks (:keystore resolved)
          ts (:trust-store resolved)]
      (cond-> []
        (not ks)
        (conj {:path [:source :tls :keystore]
               :message ":tls requires a :keystore (the client certificate + key)."})

        (and ks (not (.exists ^java.io.File ks)))
        (conj {:path [:source :tls :keystore]
               :message (str ":tls :keystore file not found: "
                             (.getPath ^java.io.File ks))})

        (and ks (str/blank? (str (:keystore-pass resolved))))
        (conj {:path [:source :tls :keystore_pass]
               :message (str ":tls :keystore_pass did not resolve to a value"
                             (when-let [raw (:keystore_pass tls)]
                               (str " (from " (pr-str raw) ")")) ".")})

        (and ts (not (.exists ^java.io.File ts)))
        (conj {:path [:source :tls :truststore]
               :message (str ":tls :truststore file not found: "
                             (.getPath ^java.io.File ts))})))))

(defonce ^{:private true
           :doc "resolved :tls config → pooling manager. One pool per distinct
   client-cert config (rotating a cert = a new config = a new pool). Keyed by
   the resolved config VALUE, not its 32-bit hash — a collision would hand one
   source another source's client-certificate pool."}
  tls-managers
  (atom {}))

(defn tls-manager
  "The mutual-TLS pooling connection manager for a source's raw `:tls`
   config, built lazily and cached by the resolved config. nil when
   `tls` is nil/blank. Throws when the keystore cannot be loaded — caught
   up front by validate-source (tls-config-problems)."
  [tls]
  (when (seq tls)
    (let [resolved (resolve-tls-config tls)]
      (or (get @tls-managers resolved)
          (get (swap! tls-managers
                      (fn [m]
                        (if (contains? m resolved)
                          m
                          (assoc m resolved
                                 (conn-mgr/make-reusable-conn-manager
                                  (merge {:timeout 30 :threads 50
                                          :default-per-route 20}
                                         resolved))))))
               resolved)))))

(defn oneshot-manager
  "A THROWAWAY (non-pooling) connection manager, for a request whose
   connection must be DESTROYED rather than returned to a pool.

   The streaming probe is that request (8.4.23 裁定十七): it stops at the
   first record and walks away, and a pooled connection is drained to the end
   before it can be reused — which would read the whole feed after all, the
   one thing the ruling exists to prevent. Shut it down (`shutdown-manager`)
   after the call; the tls / insecure tiers are the same as `select-manager`'s."
  [tls insecure?]
  (conn-mgr/make-regular-conn-manager
   (cond-> {}
     (seq tls)  (merge (resolve-tls-config tls))
     insecure?  (assoc :insecure? true))))

(defn shutdown-manager
  "Close every socket a `oneshot-manager` holds."
  [mgr]
  (conn-mgr/shutdown-manager mgr))

(defn select-manager
  "Pick the connection manager for a source: tls > insecure > default.
   :tls and :ssl_insecure? are mutually exclusive (a validation error, api-v1
   connection schema); should a conflicting config slip through, tls wins —
   fail toward the authenticated, verifying pool, never toward curl -k."
  [tls insecure?]
  (or (tls-manager tls)
      (if insecure? @insecure-manager @manager)))
