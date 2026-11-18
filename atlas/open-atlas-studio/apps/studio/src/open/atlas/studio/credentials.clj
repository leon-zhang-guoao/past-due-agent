(ns open.atlas.studio.credentials
  "Compatibility API for Studio. Credential storage and resolution are shared
   with standalone Server in runtime/env; each app passes its own home."
  (:require [open.atlas.runtime.credentials :as shared]))

(def credential-keys shared/credential-keys)
(def variable-name shared/variable-name)
(def reference shared/reference)
(def read-store shared/read-store)
(def put-vars! shared/put-vars!)
(def put! shared/put!)
(def forget-vars! shared/forget-vars!)
(def forget! shared/forget!)
(def holds shared/holds)
(def held-variables shared/held-variables)
(def prepare-variable-change shared/prepare-variable-change)
(def rename-variable-reference shared/rename-variable-reference)
(def unresolved-findings shared/unresolved-findings)

(defn process-env [n] (shared/process-env n))

(defn lookup-fn
  ([home] (shared/lookup-fn home process-env))
  ([home env-lookup] (shared/lookup-fn home env-lookup)))

(defn resolve-config
  ([home source-id entry] (shared/resolve-config home source-id entry process-env))
  ([home source-id entry env-lookup] (shared/resolve-config home source-id entry env-lookup)))

(defn missing-variables
  ([home entry] (shared/missing-variables home entry process-env))
  ([home entry env-lookup] (shared/missing-variables home entry env-lookup)))

(defn missing-findings
  ([home source-id entry] (shared/missing-findings home source-id entry process-env))
  ([home source-id entry env-lookup] (shared/missing-findings home source-id entry env-lookup)))

(defn resolve-config!
  ([home source-id entry] (shared/resolve-config! home source-id entry process-env))
  ([home source-id entry env-lookup] (shared/resolve-config! home source-id entry env-lookup)))

(defn variable-status
  ([home entry] (shared/variable-status home entry process-env))
  ([home entry env-lookup] (shared/variable-status home entry env-lookup)))

(def check-resolved-connection shared/check-resolved-connection)
