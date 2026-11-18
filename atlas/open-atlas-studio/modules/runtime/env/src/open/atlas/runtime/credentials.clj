(ns open.atlas.runtime.credentials
  "This instance's connection credentials, kept out of the model.

   ## The two lifetimes that were being confused

   A source's credentials belong to **two different moments** with two
   different owners:

   - *Authoring.* A modeller in Studio needs to reach a database now, to read
     its schema and try a resolver. They are an ordinary user of this Builder.
     They cannot write a file into the VM's environment and restart the
     process — only an administrator can.
   - *Production.* The graph is cut, exported, and deployed to a Server, where
     **that** administrator configures the real credentials for the real
     database. They are not the same secret, and often not the same database.

   Requiring `${VAR}` everywhere served the second moment and made the first
   impossible: the tool became unusable by the people it is for. Allowing a
   literal in the source entry would serve the first and poison the second —
   the pool would hold plaintext, `as-dialect` would show it, the content hash
   would cover it, and the artifact would carry it to the Server.

   ## So the value never goes near the model

   The source entry keeps `${VAR}` **names**, which is exactly the fact a
   production administrator needs: \"this source reads ${SALES_DB_PASSWORD}\".
   The *values* live here, in this instance's home, keyed by source id, and are
   substituted when a connection is opened.

   Nothing about that is a rule someone has to remember. The pool never sees a
   value, so export, `cut-version!` and the artifact cannot carry one — not
   because they strip it, but because it was never there.

   ## Keyed by variable name (doc 106 §106.14-12, owner 2026-09-07)

   The store's keys ARE the `${VAR}` names — not a per-connector list of
   credential keys. The old shape (`{source-id {:password …}}`) could only
   name sql's three top-level keys, so an api source's nested `[:auth :value]`
   fell through to `System/getenv` and could not be filled in from the UI at
   all (OQ-API-CREDENTIALS). One store, one lookup, every connector.

   ## Resolution order: store first, process environment second

   Reversed by the same ruling. A short-lived token is renewed by writing the
   store — this file is re-read from disk on every call, so a renewal script
   takes effect without restarting Studio — and a stale value left in
   `<home>/.env` must not shadow the fresh one. `.env` is now the INITIAL
   value, not the authority. Studio and Server use this same resolver with separate instance homes.

   Stored as plaintext at 0600 in the instance home, alongside `secrets.edn`,
   which already holds this instance's token pepper and actor signing key on
   the same terms. Anyone who can read this file can already read those."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [open.atlas.common.connector.spi :as spi]))


(def ^:private store-file "credentials.edn")

(def credential-keys
  "The sql connection keys the «Connect a database» card offers a text box for.
   `:jdbc_url` is included because a URL can carry credentials in its userinfo.

   This is no longer the store's key face (doc 106 §106.14-12) — the store is
   keyed by variable name and holds an api source's nested keys just as well.
   It is only the CARD's shape: which boxes that form draws, and which variable
   name each box's value is filed under."
  [:jdbc_url :username :password])

(defn variable-name
  "The environment variable a production administrator will configure for this
   source's key: `sales-db` + `:password` -> `SALES_DB_PASSWORD`.

   Derived rather than asked for, because a modeller typing a password should
   not also have to invent a shell identifier — but it is *shown* to them, so
   what the exported model asks of production is never a surprise."
  [source-id k]
  (str (-> (str source-id)
           str/upper-case
           (str/replace #"[^A-Z0-9]+" "_")
           (str/replace #"^_+|_+$" ""))
       "_"
       (case k :jdbc_url "URL" :username "USER" :password "PASSWORD"
             (str/upper-case (name k)))))

(defn reference
  "The `${VAR}` reference that goes **into the model** for this key."
  [source-id k]
  (str "${" (variable-name source-id k) "}"))

;; ---------------------------------------------------------------------------
;; The store: variable name -> value
;; ---------------------------------------------------------------------------

(defn- store-path [home] (io/file home store-file))

(defn- migrate
  "The pre-§106.14-12 shape — `{source-id {credential-key value}}` — read as
   `{variable-name value}`.

   **What happens to a literal.** Nothing was ever filed under a literal: the
   card stored a typed value only after `credentials/reference` had put
   `${SOURCE_KEY}` into the entry in its place, so every stored value already
   had exactly one variable name and `variable-name` synthesizes the same one
   here. A source whose entry carried a literal has no store row to migrate.

   Migration is on READ and is not written back: a store rewritten at startup
   is a store that changed under a running renewal script."
  [store]
  (reduce-kv (fn [m k v]
               (if (map? v)
                 (reduce-kv (fn [m* ck val*] (assoc m* (variable-name k ck) val*)) m v)
                 (assoc m (str k) v)))
             {}
             store))

(defn read-store
  "`{\"SALES_DB_PASSWORD\" value}`. Missing or unreadable reads as empty — a
   corrupt credentials file must not stop Studio from starting, it must only
   stop those connections from resolving.

   Re-read from disk on every call, deliberately and with no memoize: renewing
   a short-lived token is writing this file, and a cached read would make that
   take effect only after a restart (§106.14-12)."
  [home]
  (let [f (store-path home)]
    (if (.isFile f)
      (try (let [v (edn/read-string (slurp f))] (if (map? v) (migrate v) {}))
           (catch Exception _ {}))
      {})))

(defn- write-store! [home store]
  (let [f (store-path home)]
    (io/make-parents f)
    ;; A temporary file is owner-only from creation; replacement is atomic so
    ;; concurrent connection reads cannot observe a partially written store.
    (let [tmp (java.nio.file.Files/createTempFile
               (.toPath (.getParentFile (.getAbsoluteFile f))) ".credentials-" ".edn"
               (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile tmp) (with-out-str (pprint/pprint store)))
        (java.nio.file.Files/move tmp (.toPath f)
          (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        (finally (java.nio.file.Files/deleteIfExists tmp))))
    store))

(defn put-vars!
  "Record values by variable name — `{\"NIFI_TOKEN\" \"…\"}`. A blank value
   removes the entry rather than storing it empty: \"no password\" and \"a
   password that is the empty string\" are different answers, and only one of
   them is ever meant. -> the names now held, sorted."
  [home values]
  (let [store* (reduce-kv (fn [m n v]
                            (let [n (str (if (keyword? n) (name n) n))]
                              (if (and (string? v) (seq (str/trim v)))
                                (assoc m n (str/trim v))
                                (dissoc m n))))
                          (read-store home) values)]
    (write-store! home store*)
    (vec (sort (keys store*)))))

(defn put!
  "The card's form: values typed against one source's credential KEYS, filed
   under the variable names that source's entry references."
  [home source-id values]
  (put-vars! home (into {} (for [k credential-keys
                                 :when (contains? values k)]
                             [(variable-name source-id k) (get values k)])))
  (vec (for [k credential-keys
             :let [v (get values k)]
             :when (and (string? v) (seq (str/trim v)))]
         k)))

(defn forget-vars!
  "Drop values by variable name."
  [home names]
  (write-store! home (apply dissoc (read-store home) (map str names))))

(defn forget!
  "Drop the credentials a source's card filed — used when a source is deleted,
   so the store does not outlive the thing it was for.

   Only the names this source's own card derives are dropped. A variable a
   modeller wrote by hand into two sources' entries is not one source's to
   delete; those are removed by name from the credentials page."
  [home source-id]
  (forget-vars! home (map #(variable-name source-id %) credential-keys)))

(defn holds
  "Which of a source's card credential keys this instance holds a value for.
   Names only; this is what a read-only surface may say about it."
  [home source-id]
  (let [store (read-store home)]
    (vec (sort (for [k credential-keys
                     :when (contains? store (variable-name source-id k))]
                 (name k))))))

(defn held-variables
  "Every variable name this instance holds a value for. Names, never values."
  [home]
  (vec (sort (keys (read-store home)))))

;; ---------------------------------------------------------------------------
;; Resolution: store first, process environment second (§106.14-12)
;; ---------------------------------------------------------------------------

(defn process-env
  "The process environment as a `(fn [var-name] value-or-nil)`.

   A named function rather than an inline lookup for one reason:
   Studio now resolves `${VAR}` for EVERY connector before dialling, so a test
   that fakes an environment has to fake it here as well as inside the
   connector — and it can only do that if there is one name to rebind."
  [n]
  (System/getenv n))

(defn lookup-fn
  "The one resolution order, as a `(fn [var-name] value-or-nil)`: this store
   first, the process environment second."
  ([home] (lookup-fn home process-env))
  ([home env-lookup]
   (let [store (read-store home)]
     (fn [n] (or (get store n) (env-lookup n))))))

(defn prepare-variable-change
  "Validate a manually named variable without writing. A blank value on edit
   keeps the current value; renaming retains the old variable for other users."
  [home {:keys [name previous-name value]}]
  (let [valid-name? #(and (string? %) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
        lookup (lookup-fn home)
        supplied? (and (string? value) (not (str/blank? value)))
        renamed? (and previous-name (not= previous-name name))]
    (when-not (and (valid-name? name)
                   (or (nil? previous-name) (valid-name? previous-name))
                   (or (nil? value) (string? value)))
      (throw (ex-info "Variable names must start with a letter or underscore and contain only letters, digits and underscores."
                      {:code :credential/invalid-name})))
    (when (and (or (nil? previous-name) renamed?) (some? (lookup name)))
      (throw (ex-info "That variable already exists. Edit it or choose another name."
                      {:code :credential/name-in-use})))
    (let [new-value (if supplied? value (when previous-name (lookup previous-name)))]
      (when (and (or (nil? previous-name) renamed?) (str/blank? new-value))
        (throw (ex-info "Enter a value for this variable."
                        {:code :credential/value-required})))
      {:name name :previous-name previous-name :renamed? (boolean renamed?)
       :values (if (or supplied? renamed?) {name new-value} {})})))

(defn rename-variable-reference [entry old-name new-name]
  (walk/postwalk #(if (string? %)
                    (str/replace % (str "${" old-name "}") (str "${" new-name "}"))
                    %) entry))

(defn resolve-config
  "A source entry as declared -> the entry a connection can actually be opened
   with, for **this** instance.

   Every `${VAR}` at any depth is substituted — api-v1's nested `[:auth :value]`
   included — from this store first and the process environment second. A
   variable neither can answer is LEFT as `${VAR}`: the reference is the
   actionable fact, and callers refuse with `missing-findings` before dialling
   rather than handing a connector a nil that surfaces as an NPE.

   `source-id` no longer keys the store (§106.14-12); it stays in the signature
   because every call site names the source it is resolving and the refusals
   below quote it."
  ([home source-id entry] (resolve-config home source-id entry process-env))
  ([home _source-id entry env-lookup]
   (spi/substitute-config entry (lookup-fn home env-lookup))))

(defn missing-variables
  "The `${VAR}` references in this entry that neither the store nor the process
   environment can answer, as `[{:name … :path […]} …]`."
  ([home entry] (missing-variables home entry process-env))
  ([home entry env-lookup]
   (let [lookup (lookup-fn home env-lookup)]
     (vec (remove #(some? (lookup (:name %))) (spi/config-variables entry))))))

(defn unresolved-findings
  "`:credential/missing`, one per `${VAR}` still written in an entry that has
   already been through `resolve-config` (finding-v1): the name and the config
   path, never the value.

   Takes the RESOLVED entry rather than asking the store a second time, so a
   caller that supplied some values itself — the connect card's freshly typed
   secrets — is judged on what it is actually about to dial with."
  [source-id resolved]
  (vec (for [{:keys [name path]} (spi/config-variables resolved)]
         {:code :credential/missing
          :severity :error
          :path (into [:source (str source-id)] path)
          :message (str "this source needs the variable " name
                        ", which is neither in this instance's credential store "
                        "nor set in its environment")
          :expected name})))

(defn missing-findings
  "`:credential/missing`, one per unanswered variable (finding-v1): the name
   and the config path, never the value."
  ([home source-id entry] (missing-findings home source-id entry process-env))
  ([home source-id entry env-lookup]
   (vec (for [{:keys [name path]} (missing-variables home entry env-lookup)]
          {:code :credential/missing
           :severity :error
           :path (into [:source (str source-id)] path)
           :message (str "this source needs the variable " name
                         ", which is neither in this instance's credential store "
                         "nor set in its environment")
           :expected name}))))

(defn resolve-config!
  "`resolve-config`, refusing rather than dialling with a hole in it.

   Throws `ex-info` carrying `:findings` when any `${VAR}` is unanswered. This
   is the point §106.14-12 moves the failure to: the api connector used to
   reach its TLS keystore with a nil password and die with «Cannot read the
   array length because \"password\" is null», a message that names neither the
   variable nor the source."
  ([home source-id entry] (resolve-config! home source-id entry process-env))
  ([home source-id entry env-lookup]
   (let [missing (missing-findings home source-id entry env-lookup)]
     (when (seq missing)
       (throw (ex-info (str "Missing credential variable(s) for source " source-id)
                       {:code :credential/missing
                        :source source-id
                        :findings missing})))
     (resolve-config home source-id entry env-lookup))))

(defn variable-status
  "One row per `${VAR}` a source entry references: the name, where in the entry
   it is written, and whether this instance can answer it — and if so, from the
   store or from the environment. Names only, never values.

   This is the credentials page's whole data face, and it is the same row for a
   sql source and an api one (§106.14-12: sql 与 api 同一张表)."
  ([home entry] (variable-status home entry process-env))
  ([home entry env-lookup]
   (let [store (read-store home)]
     (vec (for [{:keys [name path]} (spi/config-variables entry)]
            {:name name
             :path (mapv str path)
             :stored? (contains? store name)
             :in-env? (some? (env-lookup name))
             :present? (boolean (or (contains? store name) (env-lookup name)))})))))

(defn check-resolved-connection
  "Both apps refuse missing credential references before invoking the connector
   health check. The response contains variable names, never their values."
  [source-id resolved]
  (let [gaps (unresolved-findings source-id resolved)]
    (if (seq gaps)
      {:status :down :error/category :credential :missing-vars (mapv :expected gaps)}
      (spi/health-check resolved nil))))
