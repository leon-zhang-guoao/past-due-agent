(ns open.atlas.server.console.sources
  "Production connection registry. Published releases receive immutable scoped
   bindings through the same runtime installer and resolver used by Studio."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.source-bindings :as bindings]
            [open.atlas.runtime.credentials :as credentials]
            [open.atlas.runtime.store :as store]
            [open.atlas.server.console.groups :as groups]))

(defonce ^:private write-lock (Object.))
(defn- path [home] (io/file home "sources.edn"))

(defn registry [home]
  (let [file (when home (path home))]
    (if-not (and file (.isFile file)) {:revision 0 :sources {}}
      (do
        (when (> (.length file) 1048576)
          (throw (ex-info "Source registry exceeds 1 MiB" {})))
        (with-open [r (java.io.PushbackReader. (io/reader file))]
          (let [v (edn/read {:eof nil} r)]
            (when-not (and (map? v) (integer? (:revision v)) (map? (:sources v))
                           (= ::end (edn/read {:eof ::end} r)))
              (throw (ex-info "Invalid Source registry" {})))
            v))))))

(defn list-sources [{:keys [home runtime]}]
  (let [{:keys [revision sources archived]} (registry home)
        group-data (when runtime (groups/registry runtime))
        membership (:source-members group-data)
        counts (frequencies (map #(get membership %) (keys sources)))]
    {:status :ok :revision revision
     :groups (mapv (fn [[id g]] (assoc g :id id :source-count (get counts id 0)))
                   (sort-by (comp str/lower-case :name val) (:groups group-data)))
     :ungrouped-count (get counts nil 0)
     :variables (credentials/held-variables home)
     :sources (mapv (fn [[id config]] {:id id :config config :archived? (contains? archived id) :group-id (get membership id) :variables (credentials/variable-status home config)}) (sort-by key sources))}))

(defn- secret-literals? [config]
  (let [found (atom false)]
    (walk/postwalk
     (fn [v]
       (when (map? v)
         (doseq [[k value] v]
           (when (and (contains? #{:jdbc_url :base_url "jdbc_url" "base_url"} k)
                      (string? value)
                      (re-find #"(?i)(password|passwd|pwd)=|://[^/@]+:[^/@]+@" value))
             (reset! found true))
           (when (and (re-find #"(?i)password|secret|token|authorization|api[_-]?key|^:?(value|credential)$" (str k))
                      (string? value) (not (str/blank? value))
                      (not (re-matches #"\$\{[A-Za-z_][A-Za-z0-9_]*\}" value)))
             (reset! found true)))) v) config)
    @found))

(defn validation [config]
  (cond
    (not (map? config)) [{:code :connector.source/invalid :severity :error :path [:config]
                         :message "Source configuration must be a map."}]
    (secret-literals? config) [{:code :credential/reference-required :severity :error :path [:config]
                                :message "Use ${ENV_VAR} references for passwords, tokens and secrets."}]
    (not (contains? #{"sql" "api" "nifi"} (:type config)))
    [{:code :connector.source/unsupported-type :severity :error :path [:config :type]
      :message "Choose sql, api or nifi."}]
    :else (:findings (spi/validate-source (merge config (:probe (spi/connection-shape (:type config))))))))

(declare test-source)

(defn- usage [document]
  (reduce (fn [m [id b]]
            (update m (:binding/source b) (fnil conj [])
                    {:binding-id id :target-id (second (:binding/for b)) :target-kind (first (:binding/for b))
                     :selected? (= id (get-in document [:execution :selection (:binding/for b)]))}))
          {} (sort-by key (get-in document [:execution :bindings]))))

(defn- save-config! [{:keys [home] :as context} {:keys [id config expected-revision verify?]}]
  (locking write-lock
    (let [current (registry home)
          findings (validation config)]
      (cond
        (not (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}" id)))
        {:status :error :error "Source ID must contain letters, numbers, dots, underscores or hyphens."}
        (contains? (:archived current) id)
        {:status :error :error "Restore this Source before editing it."}
        (not= expected-revision (:revision current))
        {:status :error :error "Sources changed. Refresh and retry."}
        (some #(= :error (:severity %)) findings)
        {:status :error :error "Source configuration was rejected." :findings (vec findings)}
        :else
        (let [health (test-source context {:id id :config config})]
          (if (and (not= false verify?) (= :error (:status health)))
            health
            (let [next (-> current (update :revision inc) (assoc-in [:sources id] config))
              file (path home)]
          (when (> (count (.getBytes (pr-str next) "UTF-8")) 1048576)
            (throw (ex-info "Source registry exceeds 1 MiB" {})))
          (io/make-parents file)
          (let [tmp (java.nio.file.Files/createTempFile (.toPath (.getParentFile file)) ".sources-" ".edn"
                                                       (make-array java.nio.file.attribute.FileAttribute 0))]
            (try
              (spit (.toFile tmp) (pr-str next))
              (java.nio.file.Files/move tmp (.toPath file)
                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                       java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
              (finally (java.nio.file.Files/deleteIfExists tmp))))
          {:status :ok :revision (:revision next) :connected? (= :ok (:status health))})))))))

(defn save-source! [{:keys [runtime home] :as context} input]
  (if-not (contains? input :group-id)
    (save-config! context input)
    (store/with-lock (:instance runtime) :graph-maintenance
      (if-not (groups/valid-group? runtime (:group-id input))
        {:status :error :error "The destination Group no longer exists."}
        (if (seq (store/writable-findings (:instance runtime)))
          {:status :error :error "This instance is not writable."}
          (let [result (save-config! context input)]
            (if (= :ok (:status result))
              (let [assigned (groups/assign-sources! runtime (:actor context) [(:id input)] (:group-id input)
                                                      (set (keys (:sources (registry home)))))]
                (if (= :ok (:status assigned)) result assigned))
              result)))))))

(defn assign-group! [{:keys [runtime home]} actor {:keys [source-ids group-id]}]
  (groups/assign-sources! runtime actor source-ids group-id (set (keys (:sources (registry home))))))

(defn test-source [{:keys [home]} {:keys [id config]}]
  (let [findings (validation config)]
    (if (some #(= :error (:severity %)) findings)
      {:status :error :error "Source configuration was rejected." :findings findings}
      (let [health (credentials/check-resolved-connection id (credentials/resolve-config home id config))]
        (if (= :ok (:status health))
          {:status :ok :message "Connection succeeded."}
          {:status :error :error "Connection failed. Check the connection and environment variables."
           :category (:error/category health) :missing-vars (:missing-vars health)})))))

(defn requirements [document]
  (mapv (fn [[id source]] {:slot-id id :graph-id (get-in document [:graph :graph/id]) :source source})
        (sort-by key (remove #(contains? #{"static" "derived"} (:source/kind (val %)))
                            (get-in document [:execution :sources])))))

(defn prepare
  "Read-only preflight, using the shared Source kind and slot resolver. No
   credential values are returned in the plan consumed by the browser."
  [{:keys [home]} document selection]
  (let [requirements (requirements document) usage-by-slot (usage document)
        {:keys [revision sources archived]} (registry home)
        sources (apply dissoc sources archived)
        resolved (when home (bindings/file-resolver (str (io/file home "source-bindings.edn")) (fn [_ x] x)))
        workspace (get-in document [:workspace :workspace/id])
        graph (get-in document [:graph :graph/id])
        release (get-in document [:publication :release/version])
        rows (mapv
              (fn [{:keys [slot-id source] :as requirement}]
                (let [source-id (get selection slot-id)
                      options (vec (for [[id entry] (sort-by key sources)
                                         :when (= (:type entry) (:source/kind source))]
                                     {:id id :label (or (:label entry) id)}))
                      existing (try (resolved slot-id (assoc source :semantic/workspace workspace
                                                       :semantic/graph graph :semantic/release release))
                                    (catch Exception _ nil))
                      entry (if source-id
                              (try (get (bindings/resolve-package-bindings
                                         {:sources sources :bindings {[graph slot-id] source-id}} [requirement])
                                        [graph slot-id]) (catch Exception _ nil))
                              existing)
                      vars (when entry (:vars (spi/package-requirements {:sources {slot-id entry}})))
                      missing (filterv #(str/blank? ((credentials/lookup-fn home) %)) vars)
                      conflict? (and existing entry (not= existing entry))]
                  {:slot-id slot-id :kind (:source/kind source) :selected-source source-id :options options
                   :binding-status (if entry :bound :missing) :uses (get usage-by-slot slot-id [])
                   :entry entry :key [workspace graph release slot-id]
                   :findings (vec (concat
                     (when-not entry [{:code :semantic.runtime/source-binding-unavailable :severity :error
                                       :path [:sources slot-id] :message "Choose a compatible production Source."}])
                     (when conflict? [{:code :semantic.runtime/source-binding-conflict :severity :error
                                        :path [:sources slot-id] :message "This release already has a different Source. Publish a new release to change it."}])
                     (for [v missing] {:code :credential/missing :severity :error :path [:sources slot-id]
                                       :expected v :message (str "Missing environment variable " v)})))})) requirements)]
    {:revision revision
     :origins (into {} (keep #(when (and (:entry %) (:selected-source %)) [(:key %) {:source-id (:selected-source %)}]) rows))
     :entries (into {} (keep #(when (:entry %) [(:key %) (:entry %)]) rows))
     :sources (into (mapv #(dissoc % :entry :key :findings) rows)
                    (for [[id source] (sort-by key (get-in document [:execution :sources]))
                          :when (contains? #{"static" "derived"} (:source/kind source))]
                      {:slot-id id :kind (:source/kind source) :binding-status :not-required :uses (get usage-by-slot id [])}))
     :findings (vec (mapcat :findings rows))}))

(defn- origins [home]
  (or (store/read-edn (io/file home "source-binding-origins.edn")) {}))

(defn install!
  ([context entries] (install! context entries {}))
  ([{:keys [home]} entries selected]
   (locking write-lock
     (bindings/install-missing! (str (io/file home "source-bindings.edn")) entries)
     ;; Provenance only: the runtime continues to resolve immutable snapshots.
     ;; Keep the first recorded selection when two registered Sources are equal.
     (when (seq selected)
       (store/write-edn! (io/file home "source-binding-origins.edn") (merge selected (origins home)))))))

(defn- binding-row [home registered recorded resolver workspace graph-id release id source used-by]
  (let [required? (not (contains? #{"static" "derived"} (:source/kind source)))
        snapshot (when required?
                   (try (resolver id (assoc source :semantic/workspace workspace :semantic/graph graph-id :semantic/release release))
                        (catch Exception _ nil)))
        original (get-in recorded [[workspace graph-id release id] :source-id])
        matches (when (and snapshot (nil? original)) (sort (keep (fn [[k v]] (when (= snapshot v) k)) registered)))
        source-id (or original (when (= 1 (count matches)) (first matches)))
        current (get registered source-id)]
    {:slot-id id :label (or (:source/label source) id) :kind (:source/kind source)
     :binding-status (cond (not required?) :not-required snapshot :bound :else :missing)
     :source-id source-id :source-label (or (:label snapshot) source-id)
     :origin (cond original :recorded source-id :configuration-match :else :unknown)
     :registry-status (cond (not required?) :not-required
                            (and original (nil? current)) :unavailable
                            current (if (= snapshot current) :current :changed)
                            (> (count matches) 1) :ambiguous
                            :else :untracked)
     :registered? (boolean current)
     :variables (when snapshot (mapv #(select-keys % [:name :present?]) (credentials/variable-status home snapshot)))
     :used-by (vec (sort (get used-by id)))}))

(defn graph-sources
  "Describe one retained release without exposing connection entries or secrets.
   Older bindings without provenance are matched only when configuration is unique."
  [{:keys [home runtime]} options]
  (let [id (get options "graph-id")
        versions (sort > (map :version (filter #(= id (:graph %)) (graph/shelf-versions (:graph runtime)))))
        serving (when (seq versions) (graph/serving-version (:graph runtime) id))
        latest (first versions)
        version (if-let [v (get options "version")]
                  (try (Long/parseLong v) (catch Exception _ nil)) (or serving latest))]
    (if-not (some #{version} versions)
      {:status :error :error "Choose an existing Graph version to inspect its Sources."}
      (let [texts (graph/shelf-texts (:graph runtime) id version)
            read* (artifact/read-artifact texts {:expected-digest (artifact/artifact-digest texts) :closure :unverified})
            errors (filterv #(= :error (:severity %)) (:findings read*))
            doc (:document read*)]
        (if (seq errors)
          {:status :error :error "This release could not be inspected." :findings errors}
          {:status :ok :graph-id id :version version :serving-version serving :latest-version latest
           :sources (if doc
                      (let [used-by (reduce (fn [m [_ b]] (update m (:binding/source b) (fnil conj #{})
                                                                (str (name (first (:binding/for b))) " / " (second (:binding/for b)))))
                                            {} (get-in doc [:execution :bindings]))
                            registered (:sources (registry home)) recorded (origins home)
                            resolver (bindings/file-resolver (str (io/file home "source-bindings.edn")) (fn [_ x] x))]
                        (mapv (fn [[slot source]]
                                (assoc (binding-row home registered recorded resolver
                                             (get-in doc [:workspace :workspace/id]) (get-in doc [:graph :graph/id])
                                             (get-in doc [:publication :release/version]) slot source used-by) :uses (get (usage doc) slot [])))
                              (sort-by key (get-in doc [:execution :sources]))))
                      (mapv (fn [[slot source]] {:slot-id slot :label (or (:label source) slot) :kind (:type source)
                                                :binding-status :embedded :registry-status :untracked :used-by []})
                            (sort-by key (get-in read* [:model :sources]))))})))))

(defn- saved-bindings [home]
  (or (store/read-edn (io/file home "source-bindings.edn")) {}))

(defn list-variables [{:keys [home]}]
  (let [held (set (credentials/held-variables home))
        references (concat
                     (for [[id config] (:sources (registry home))
                           row (credentials/variable-status home config)] (assoc row :source-id id))
                     (for [[scope config] (saved-bindings home)
                           row (credentials/variable-status home config)]
                       (assoc row :source-id (str "Release " (str/join "/" scope)))))
        by-name (group-by :name references)]
    {:status :ok
     :variables (mapv (fn [name]
                        {:name name :stored? (contains? held name)
                         :present? (boolean (or (contains? held name) (some :present? (get by-name name))))
                         :used-by (vec (sort (set (map :source-id (get by-name name)))))})
                      (sort (into held (keys by-name))))}))

(defn- embedded-variable-users [{:keys [runtime]} name]
  (when runtime
    (vec (for [{:keys [graph version]} (graph/shelf-versions (:graph runtime))
               :let [texts (graph/shelf-texts (:graph runtime) graph version)
                     read* (artifact/read-artifact texts {:expected-digest (artifact/artifact-digest texts) :closure :unverified})]
               [id config] (get-in read* [:model :sources])
               :when (some #(= name (:name %)) (spi/config-variables config))]
           (str graph " v" version " / " id)))))

(defn save-variable! [{:keys [home] :as context} params]
  (locking write-lock
    (try
      (let [prepared (credentials/prepare-variable-change home params)]
        (when (and (:renamed? prepared) (seq (embedded-variable-users context (:previous-name prepared))))
          (throw (ex-info "This variable is embedded in a legacy release. Replace that release with a Source-slot artifact before renaming."
                          {:code :credential/embedded-reference})))
        (credentials/put-vars! home (:values prepared))
        (when (:renamed? prepared)
          (let [current (registry home)
                updated (update current :sources
                                (fn [entries] (into {} (map (fn [[id config]]
                                  [id (credentials/rename-variable-reference config (:previous-name prepared) (:name prepared))]) entries))))]
            (when (not= current updated)
              (store/write-edn! (path home) (update updated :revision inc))))
          ;; Rename operator-owned references before removing the old value.
          ;; Release artifact bytes and the connection settings remain unchanged.
          (let [current (saved-bindings home)
                updated (credentials/rename-variable-reference current (:previous-name prepared) (:name prepared))]
            (when (not= current updated)
              (store/write-edn! (io/file home "source-bindings.edn") updated)))
          (credentials/forget-vars! home [(:previous-name prepared)]))
        {:status :ok :renamed? (:renamed? prepared) :names (credentials/held-variables home)})
      (catch clojure.lang.ExceptionInfo e
        {:status :error :error (.getMessage e)
         :findings [{:code (:code (ex-data e)) :severity :error :path [:variable]
                     :message (.getMessage e)}]}))))

(defn remove-variable! [{:keys [home] :as context} {:keys [name]}]
  (locking write-lock
    (let [row (some #(when (= name (:name %)) %) (:variables (list-variables context)))]
      (cond
        (nil? row) {:status :error :error "Variable no longer exists. Refresh and retry."}
        (seq (embedded-variable-users context name)) {:status :error :error "Variable is embedded in a saved legacy release. Replace that release before removing it."}
        (seq (:used-by row)) {:status :error :error "Variable is still referenced. Remove or replace these references first."
                             :findings [{:code :credential/in-use :severity :error :path [:variable]
                                         :message (str "Used by: " (str/join ", " (:used-by row)))}]}
        (not (:stored? row)) {:status :error :error "This value is provided by the Server environment. Remove it from the environment configuration."}
        :else (do (credentials/forget-vars! home [name]) {:status :ok})))))

(defn source-lifecycle! [{:keys [home runtime actor]} {:keys [id action expected-revision]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (locking write-lock
      (let [current (registry home)
            config (get-in current [:sources id])
            versions (set (map (juxt :graph (comp str :version)) (graph/shelf-versions (:graph runtime))))
            recorded (origins home)
            users (when (= action :delete)
                    (vec (for [[scope snapshot] (saved-bindings home)
                               :let [graph-id (str (first scope) "/" (second scope))]
                               :when (and (if (= 4 (count scope))
                                            (contains? versions [graph-id (nth scope 2)])
                                            (some #(= graph-id (first %)) versions))
                                          (or (= id (get-in recorded [scope :source-id]))
                                              (and (nil? (get recorded scope)) (= config snapshot))))]
                           (str/join "/" scope))))]
        (cond
          (seq (store/writable-findings (:instance runtime))) {:status :error :error "This instance is not writable."}
          (nil? config) {:status :error :error "Source no longer exists. Refresh and retry."}
          (not= expected-revision (:revision current)) {:status :error :error "Sources changed. Refresh and retry."}
          (not (contains? #{:archive :restore :delete} action)) {:status :error :error "Choose Archive, Restore or Delete."}
          (seq users) {:status :error :error "This Source is referenced by retained Graph versions. Archive it instead."
                       :findings (mapv (fn [usage] {:code :source/in-use :severity :error :path [:source id] :message usage}) users)}
          :else
          (let [next (-> (case action
                           :archive (update current :archived (fnil conj #{}) id)
                           :restore (update current :archived disj id)
                           :delete (-> current (update :sources dissoc id) (update :archived disj id)))
                         (update :revision inc))]
            (when (= action :delete)
              (groups/assign-sources! runtime actor [id] nil (set (keys (:sources current)))))
            (store/write-edn! (path home) next)
            {:status :ok :revision (:revision next)}))))))

(defn with-registry-lock [f]
  (locking write-lock (f)))
