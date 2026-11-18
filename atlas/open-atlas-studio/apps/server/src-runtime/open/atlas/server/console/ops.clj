(ns open.atlas.server.console.ops
  "The console's runtime-facing operations: what is on the shelf, importing a
   graph, issuing and revoking a graph token, enabling and disabling a graph.

   **Migrated, not rewritten** (8.12 P3). This is the code that served the
   inherited Server GUI as `ent.dx.admin.ops.graphs`, which was made a thin
   adapter over this one so the two entrances ran one implementation rather
   than two that drift. That GUI was retired in 8.16 and this is now the only
   entrance. What changed at migration time was only what tied it to the GUI:
   the process-wide context atom became an explicit argument, and the
   browser's user map became the caller's actor principal.

   Nothing here writes the artifact shelf or the serving pointer directly.
   Import executes the canonical signed offer → stage → load → activate, which
   is the same protocol Studio's deploy uses and the same one `/control`
   accepts — so an illegal import is refused with the same `:code` whichever
   entrance asked (D3.16, and 8.12 P3's exit condition)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.store :as store]
            [open.atlas.runtime.credentials :as credentials]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.console.sources :as sources]
            [open.atlas.server.console.catalog :as catalog]
            [open.atlas.server.console.groups :as groups])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]
           [java.util UUID]))

;; A context is `{:runtime … :instance-id … :service-key-id … :actor-key-id …
;; :actor-secret …}`: one started runtime plus the two signing identities. It
;; is passed in rather than held here — a process-wide atom made this namespace
;; impossible to exercise twice in one JVM, and it is the assembly root's job
;; to know which runtime this is.

(defn- now []
  (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn- expires-at []
  (str (.plusSeconds (.truncatedTo (Instant/now) ChronoUnit/SECONDS)
                     cp/max-assertion-seconds)))

(defn- request-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn finding-error [findings]
  (let [details (mapcat (fn [finding]
                          ;; The artifact reader wraps dialect failures in one
                          ;; artifact finding and preserves the exact dialect
                          ;; findings under :got. Return those details directly
                          ;; so the caller can show the precise offending paths.
                          (if (and (vector? (:got finding))
                                   (every? map? (:got finding)))
                            (:got finding)
                            [finding]))
                        findings)]
    {:status :error
     :error (or (:message (first findings)) "Graph operation was rejected")
     :findings (vec details)}))

;; --- what is on the shelf ----------------------------------------------------

(defn- graph-summary [{:keys [runtime]} graph-id versions records]
  (let [serving  (graph/serving-version (:graph runtime) graph-id)
        texts    (when-let [v (or serving (last (sort versions)))] (graph/shelf-texts (:graph runtime) graph-id v))
        manifest (try (some-> (get texts "manifest.edn") edn/read-string)
                      (catch Throwable _ nil))
        report   (try (some-> (get texts "report.edn") edn/read-string)
                      (catch Throwable _ nil))
        content  (try (some-> (get texts "content.edn") edn/read-string)
                      (catch Throwable _ nil))
        document (:document content)
        graph-tokens (filter #(= graph-id (:graph-id %)) records)]
    {:graph-id graph-id
     :state (graph/lifecycle-state (:graph runtime) graph-id)
     :versions (vec (sort > versions))
     :serving-version serving
     :enabled? (instance/graph-enabled? runtime graph-id)
     :token-count (count graph-tokens)
     :active-token-count (count (filter #(token/active? % (now)) graph-tokens))
     :tokens (vec (sort-by :created-at #(compare %2 %1)
                           (map #(select-keys % [:token-id :graph-id :status :version :created-at :expires-at])
                                graph-tokens)))
     :overview {:workspace (or (get-in manifest [:deployment :workspace]) (get-in manifest [:provenance :workspace]))
                :name (or (get-in document [:graph :graph/label]) (get-in manifest [:provenance :graph]))
                :cut-at (get-in manifest [:provenance :cut-at])
                :cut-by (get-in manifest [:provenance :cut-by])
                :content-hash (:content-hash manifest)
                :healthy? (if document (= :contract-validated (:validation report)) (get-in manifest [:health :structural-ok?]))
                :finding-count (count (get-in manifest [:health :findings]))
                :object-count (when-not document (count (:objects content)))
                :reference-count (count (:references content))
                :quality (:quality report)}}))

(defn list-graphs
  "Every graph on this instance's shelf, with the tokens issued against it.
   Token **records** only — a record is an id, a status and a coordinate; the
   secret was returned once at issuance and is not stored (S5.9)."
  [{:keys [runtime] :as context}]
  (let [shelf   (graph/shelf-versions (:graph runtime))
        by-id   (reduce (fn [m {:keys [graph version]}]
                          (update m graph (fnil conj []) version))
                        (into {} (for [[qn record] (graph/lifecycle-records (:graph runtime))
                                       :when (= :archived (:state record))] [qn []])) shelf)
        records (vec (token/all-records (:token runtime)))]
    {:status :ok
     :graphs (vec (map (fn [[graph-id versions]]
                         (graph-summary context graph-id versions records))
                       (sort-by key by-id)))}))

;; --- import ------------------------------------------------------------------

(defn- signed-envelope
  [{:keys [instance-id actor-key-id actor-secret]} actor deployment-id op payload]
  (cp/build-envelope
   {:op op :payload payload :deployment-id deployment-id
    :request-id (request-id (name op)) :audience instance-id
    :actor-principal actor :actor-key-id actor-key-id :actor-secret actor-secret
    :now (now) :expires-at (expires-at)}))

(defn- run-command
  [{:keys [runtime service-key-id] :as context} actor deployment-id op payload]
  (graph/handle! (:graph runtime) (:reads runtime)
                 (signed-envelope context actor deployment-id op payload)
                 {:instance-id (:instance-id context)
                  :service-principal {:issuer "service-key" :subject service-key-id}
                  :now (now)}))

(defn- artifact-metadata [files]
  (let [digest (artifact/artifact-digest files)
        read*  (artifact/read-artifact files {:expected-digest digest
                                              :closure :unverified})
        errors (vec (filter #(= :error (:severity %)) (:findings read*)))]
    ;; Reader findings also include intentional, non-blocking carried-key notes
    ;; (for example :cache and :expression). Only errors reject an import.
    (if (seq errors)
      {:findings errors}
      (let [{:keys [workspace graph version]} (or (:deployment read*) (get-in read* [:manifest :provenance]))]
        (if (and (string? workspace) (seq workspace)
                 (string? graph) (seq graph) (int? version) (pos? version))
          {:graph-id (str workspace "/" graph)
           :version version
           :content-hash (:content-hash read*)
           :artifact-digest digest :document (:document read*)}
          {:findings [{:code :server.upload/missing-coordinate :severity :error
                       :path [:manifest :provenance]
                       :message "manifest provenance must contain workspace, graph, and a positive version"}]})))))

(def ^:private max-bundle-bytes
  "Uncompressed ceiling on one delivery bundle.

   A zip says how big it is only after you have decompressed it, so a reader
   that trusts the archive decides how much memory this process spends. 64 MiB
   is far above any real artifact (three EDN texts) and far below anything that
   hurts."
  (* 64 1024 1024))

(defn bundle-files
  "A delivery bundle's bytes → the three artifact texts (D4.13).

   **Matched by basename, and the path is ignored** — D4.13 makes the directory
   names a convenience and explicitly not authority, so this must not read
   coordinates out of them. That has a direct consequence: two entries with the
   same basename are **unresolvable**, not a case to pick a winner in, and a
   bundle carrying more than one artifact trips exactly that. Since D3.17 a
   closure is always one artifact, so such a bundle is malformed today rather
   than merely unsupported — but it is refused BY NAME either way, instead of
   one of the two being loaded silently."
  [^bytes bytes]
  (let [wanted (set artifact/artifact-files)]
    (loop [in (java.util.zip.ZipInputStream. (java.io.ByteArrayInputStream. bytes))
           found {}
           total 0]
      (if-let [entry (.getNextEntry in)]
        (let [nm (last (str/split (.getName entry) #"/"))]
          (cond
            (.isDirectory entry) (recur in found total)
            (not (contains? wanted nm)) (recur in found total)
            (contains? found nm)
            {:findings [{:code :server.bundle/ambiguous-entry :severity :error
                         :path [:bundle nm]
                         :message (str "the bundle carries more than one " nm
                                       "; a bundle's directory names are not authority (D4.13),"
                                       " so there is no way to choose between them")}]}
            :else
            (let [out (java.io.ByteArrayOutputStream.)
                  buf (byte-array 65536)]
              (loop [n (.read in buf)
                     written 0]
                (cond
                  (neg? n) nil
                  (> (+ total written n) max-bundle-bytes) :too-big
                  :else (do (.write out buf 0 n)
                            (recur (.read in buf) (+ written n)))))
              (if (> (+ total (.size out)) max-bundle-bytes)
                {:findings [{:code :server.bundle/too-large :severity :error :path [:bundle]
                             :message (str "the bundle expands past " max-bundle-bytes
                                           " bytes and was not read further")}]}
                (recur in
                       (assoc found nm (String. (.toByteArray out) "UTF-8"))
                       (+ total (.size out)))))))
        (cond
          (:findings found) found
          (= wanted (set (keys found))) {:files found}

          ;; Nothing recognised at all reads differently from a bundle that is
          ;; short one file: arbitrary bytes make `ZipInputStream` yield no
          ;; entries rather than throw, so «missing all three» is what a file
          ;; that is not a bundle looks like from here. Saying "missing
          ;; content.edn, manifest.edn, report.edn" to somebody who picked the
          ;; wrong file sends them looking for the wrong problem.
          (empty? found)
          {:findings [{:code :server.bundle/invalid :severity :error :path [:bundle]
                       :message (str "no artifact files were found in this file — a delivery "
                                     "bundle is a zip containing "
                                     (str/join ", " artifact/artifact-files))}]}

          :else
          {:findings [{:code :server.bundle/incomplete :severity :error :path [:bundle]
                       :message (str "a bundle must contain "
                                     (str/join ", " artifact/artifact-files)
                                     "; missing: "
                                     (str/join ", " (sort (remove (set (keys found)) wanted))))}]})))))

(defn decode-bundle
  "Base64 → the three texts, or findings. The bundle arrives base64-encoded
   because the console speaks EDN and a zip is bytes; the inflation is paid
   once, on a file somebody picked by hand."
  [bundle]
  (if-not (string? bundle)
    {:findings [{:code :server.bundle/invalid :severity :error :path [:bundle]
                 :message "bundle must be base64 text"}]}
    (let [decoded (try (.decode (java.util.Base64/getDecoder) ^String bundle)
                       (catch IllegalArgumentException _ nil))]
      (if (nil? decoded)
        {:findings [{:code :server.bundle/invalid :severity :error :path [:bundle]
                     :message "bundle is not valid base64"}]}
        (try (bundle-files decoded)
             (catch java.util.zip.ZipException _
               {:findings [{:code :server.bundle/invalid :severity :error :path [:bundle]
                            :message "bundle is not a readable zip archive"}]}))))))

;; --- install plan (doc 104 §8.17.7 C) ----------------------------------------

(defn- requirement-base-dir
  "Anchor for a package's RELATIVE `:requires :files` paths — the same function
   the connectors resolve their own relative paths with, so \"the plan says the
   file is here\" and \"the connector opens that file\" cannot disagree
   (doc 104 §8.17.7 H item 1, owner ruling 2026-09-06)."
  [getenv]
  (spi/workspace-base-dir getenv))

(defn- var-present?
  "Names only, never values (doc 104 §8.17.7 A): this asks the environment
   whether the name is set and the answer that leaves here is a boolean."
  [getenv name]
  (boolean (not-empty (getenv name))))

;; --- findings ----------------------------------------------------------------
;; finding-v1 §6: every code this namespace can raise, in one list a reader can
;; look a refusal up in. `:credential/missing` is deliberately NOT in the
;; `:package/*` family — it is the same fact Studio reports through
;; `open.atlas.studio.credentials`, and doc 106 §106.14-20 (owner 2026-09-07)
;; unified the two on that code; `:package/*` keeps only codes about the
;; package's own structure. The code the import plan used to raise for a
;; missing variable is retired — not registered, not raised, and a test scans
;; the sources to keep it that way.

(def finding-codes
  #{:source.registry/revision-mismatch
    :semantic.runtime/source-binding-unavailable
    :semantic.runtime/source-binding-conflict
    :package/absent
    :package/incompatible
    :package/malformed
    :package/undeclared
    :package/unknown
    :package/unknown-connector
    :package/missing-file
    :credential/missing})

(defn- file-present? [base path]
  (let [f (java.io.File. ^String path)
        f (if (.isAbsolute f) f (java.io.File. ^String base ^String path))]
    (.exists f)))

(defn- compat-findings
  "The package's `:compat` against what this instance implements.

   An unknown source type is reported apart from an incompatible one: \"I do
   not implement this connector\" and \"I implement an older contract\" send an
   operator to two different places."
  [compat]
  (let [dialect-declared (:dialect compat)]
    (vec
     (concat
      (when (and (some? dialect-declared) (not= dialect-declared dialect/format-version))
        [{:code :package/incompatible :severity :error :path [:package :compat :dialect]
          :message (str "this package was cut against dialect " (pr-str dialect-declared)
                        "; this instance implements " (pr-str dialect/format-version))
          :expected dialect/format-version :got dialect-declared}])
      (for [[type declared] (sort-by key (:connectors compat))
            :let [implemented (spi/contract-version type)]
            :when (not= declared implemented)]
        (if (nil? implemented)
          {:code :package/unknown-connector :severity :error
           :path [:package :compat :connectors type]
           :message (str "this package uses a \"" type "\" source and this instance "
                         "has no connector registered for that type")
           :got declared}
          {:code :package/incompatible :severity :error
           :path [:package :compat :connectors type]
           :message (str "this package was cut against the \"" type "\" connector contract "
                         (pr-str declared) "; this instance implements "
                         (pr-str implemented))
           :expected implemented :got declared}))))))

(defn plan-import
  "What installing this bundle would need, before anything is put on the shelf
   (doc 104 §8.17.7 C).

   Pure apart from two existence questions — is this variable set in the
   process environment, is this file on this filesystem — and it answers both
   with a boolean. **Never a value**: a plan that echoed `NIFI_CLIENT_STORE_PASS`
   would put a secret into a console response and, from there, into a log.

   A bundle cut before the `:package` block existed is reported
   `:package/undeclared` at WARNING severity (H item 3) — but its variables are
   no longer unchecked: they are read from the bundle's own source entries
   (doc 106 §106.14-12 / OQ-IMPORT-VARS), so «plan says which variables are
   missing» holds for every bundle rather than only for re-cut ones.

   Server and Studio share the instance credential store and resolution order.
   When a home is supplied, stored variables take precedence over the process
   environment. Neither planning nor publication returns credential values.

   `opts` may carry `:getenv` — a `(fn [name] value-or-nil)` standing in for
   the process environment, which is also what the file anchor is read from.
   It exists so this can be exercised over a known environment; production
   passes nothing and reads the real one."
  ([input] (plan-import input {}))
  ([{:keys [files bundle source-bindings]} {:keys [getenv home runtime]}]
  (let [getenv   (or getenv (when home (credentials/lookup-fn home)) #(System/getenv %))
        base     (requirement-base-dir getenv)
        unpacked (when bundle (decode-bundle bundle))
        files    (if bundle
                   (:files unpacked)
                   (into {} (for [[k v] files] [(name k) v])))]
    (cond
      (seq (:findings unpacked)) {:would-import? false :findings (vec (:findings unpacked))}

      (not= (set artifact/artifact-files) (set (keys files)))
      {:would-import? false
       :findings [{:code :server.bundle/incomplete :severity :error :path [:bundle]
                   :message (str "an import is a delivery bundle (.zip), or exactly "
                                 (str/join ", " artifact/artifact-files))}]}

      :else
      (let [digest (artifact/artifact-digest files)
            read*  (artifact/read-artifact files {:expected-digest digest
                                                  :closure :unverified})
            errors (vec (filter #(= :error (:severity %)) (:findings read*)))]
        (if (seq errors)
          {:would-import? false :findings errors}
          (let [{:keys [workspace graph version]} (or (:deployment read*) (get-in read* [:manifest :provenance]))
                source-plan (when (:document read*)
                              (sources/prepare {:home home} (:document read*) source-bindings))
                package  (:package read*)
                ;; The declared list, UNIONED with what the bundle's own source
                ;; entries ask for (doc 106 §106.14-12 / OQ-IMPORT-VARS): a
                ;; bundle cut before the `:package` block declares nothing, and
                ;; "nothing could be checked" is a worse answer than reading the
                ;; entries — the same walk `spi/package-requirements` does when
                ;; a version is cut, so plan and cut cannot disagree about what
                ;; a variable is.
                vars     (vec (sort (distinct
                                     (concat (get-in package [:requires :vars])
                                             (:vars (spi/package-requirements
                                                     (:model read*)))))))
                paths    (get-in package [:requires :files])
                var-rows (mapv (fn [n] {:name n :present? (var-present? getenv n)}) vars)
                file-rows (mapv (fn [p] {:path p :present? (file-present? base p)}) paths)
                compat*  (if package (compat-findings (:compat package)) [])
                findings (vec
                          (concat
                           (when (and runtime (= :archived (graph/lifecycle-state (:graph runtime) (str workspace "/" graph))))
                             [{:code :graph-archived :severity :error :path [:graph]
                               :message "Restore the archived Graph before importing or publishing."}])
                           (if (or package (:document read*))
                             []
                             ;; H item 3, and the severity is the whole ruling:
                             ;; it is said out loud and it does not block.
                             [{:code :package/undeclared :severity :warning :path [:manifest :package]
                               :message (str "this bundle was cut before packages declared what "
                                             "they need; its variables were read from the source "
                                             "entries themselves and its files could not be "
                                             "checked at all")}])
                           compat*
                           (:findings source-plan)
                           (for [{:keys [name present?]} var-rows
                                 :when (not present?)]
                             ;; `:credential/missing`, the SAME code Studio
                             ;; raises for "this source's `${VAR}` has no
                             ;; value" (doc 106 §106.14-20, owner 2026-09-07).
                             ;; One fact, one code: the two faces used to
                             ;; differ only in which side asked, so every
                             ;; manual had to say it twice. `:path` names the
                             ;; place inside the document under judgement and
                             ;; `:expected` names the variable — never a value.
                             {:code :credential/missing :severity :error
                              :path [:package :requires :vars] :name name
                              :expected name
                              :message (str "this package needs the credential variable "
                                            name ", which is not set in this instance store or environment")})
                           (for [{:keys [path present?]} file-rows
                                 :when (not present?)]
                             {:code :package/missing-file :severity :error
                              :path [:package :requires :files] :file path
                              :message (str "this package needs the file " path
                                            ", which was not found under " base)})))]
            {:package (:name package)
             :graph-id (when (and (string? workspace) (string? graph))
                         (str workspace "/" graph))
             :version version
             :source-revision (:revision source-plan)
             :sources (:sources source-plan)
             :compat {:ok? (empty? compat*) :findings compat*}
             :requires {:vars var-rows :files file-rows}
             :would-import? (not (some #(= :error (:severity %)) findings))
             :findings findings})))))))

(defn- import-graph*
  "A delivery bundle, or the three loose artifact texts → offer → stage → load
   → activate, signed as `actor`.

   **`plan-import` runs first and can refuse** (doc 104 §8.17.7 C): a package
   whose variables or files are missing is not put on the shelf, because the
   failure it is heading for happens at the FIRST QUERY — the import answers
   `:ok` and the graph then dies with `:engine.execution/failed`, which is a
   diagnosis nobody can trace back to the install. The plan travels with the
   success response too: a bundle can install and still carry warnings
   (`:package/undeclared`), and the console shows them."
  [{:keys [runtime] :as context} actor {:keys [files bundle] :as input}]
  ;; Two ways in, one path onward. A bundle (D4.13) is unpacked to the very
  ;; same three texts the loose-file form supplies, and everything after this
  ;; line cannot tell which arrived. That is the point: the zip replaces the
  ;; TRANSPORT, not the commands.
  (let [unpacked (when bundle (decode-bundle bundle))
        files    (if bundle
                   (:files unpacked)
                   (into {} (for [[k v] files] [(name k) v])))
        plan     (plan-import input context)]
    (cond
      (seq (:findings unpacked)) (finding-error (:findings unpacked))

      (and (some? (:expected-source-revision input))
           (not= (:expected-source-revision input) (:source-revision plan)))
      (finding-error [{:code :source.registry/revision-mismatch :severity :error :path [:sources]
                       :message "Sources changed after review. Check readiness again before publishing."}])

      (and (:expected-graph-id input) (not= (:expected-graph-id input) (:graph-id plan)))
      (finding-error [{:code :graph-import-target-mismatch :severity :error :path [:graph-id]
                       :message "This bundle belongs to a different Graph. Import it from the Graph list instead."}])

      (and (contains? input :group-id) (not (groups/valid-group? runtime (:group-id input))))
      (finding-error [{:code :graph-group-unknown :severity :error :path [:group-id]
                       :message "The selected Group no longer exists. Refresh the Graph list."}])

      (not (:would-import? plan))
      (assoc (finding-error (:findings plan)) :plan plan)

      (not= (set artifact/artifact-files) (set (keys files)))
      {:status :error
       :error "Select a delivery bundle (.zip), or exactly content.edn, manifest.edn, and report.edn"}

      :else
      (let [{:keys [graph-id version content-hash artifact-digest findings document]}
            (artifact-metadata files)]
        (if (seq findings)
          (finding-error findings)
          (let [_ (when document
                    (let [prepared (sources/prepare context document (:source-bindings input))]
                      (sources/install! context (:entries prepared) (:origins prepared))))
                deployment-id (request-id "server-upload")
                member {:graph graph-id :version version :content-hash content-hash
                        :artifact-digest artifact-digest}
                deployed (cp/run-deployment!
                           []
                           {:offer (fn [_] (run-command context actor deployment-id :offer
                                                        {:graph graph-id :version version :closure [member]}))
                            :stage (fn [acc]
                                     (if (seq (get-in acc [:by-step :offer :missing]))
                                       (run-command context actor deployment-id :stage
                                                    {:artifact-digest artifact-digest :files files})
                                       {:result {:staged artifact-digest} :findings []}))
                            :load (fn [_] (run-command context actor deployment-id :load
                                                       {:graph graph-id :version version}))
                            :activate (fn [_]
                                        (let [current (graph/serving-version (:graph runtime) graph-id)]
                                          (if (= current version)
                                            {:result {:version version} :findings []}
                                            (run-command context actor deployment-id :activate
                                                         {:graph graph-id :version version
                                                          :expected-current current}))))}
                            {:activate? (not= false (:activate? input))})]
            (if (seq (:findings deployed))
              (finding-error (:findings deployed))
              (let [_ (graph/set-lifecycle-state! (:graph runtime) graph-id :active {:actor actor :now (now)})
                    enabled (when (not= false (:activate? input))
                              (instance/set-graph-enabled! runtime graph-id true {:actor actor :now (now)}))]
                (if (seq (:findings enabled))
                  (finding-error (:findings enabled))
                  (do
                    (when (contains? input :group-id)
                      (let [result (groups/assign! context actor {:graph-ids [graph-id] :group-id (:group-id input)})]
                        (when (= :error (:status result)) (throw (ex-info (:error result) {:code :graph-group-write-failed})))))
                  {:status :ok :graph-id graph-id :version version
                   :serving-version (graph/serving-version (:graph runtime) graph-id)
                   :steps (:steps deployed) :plan plan}))))))))))

(defn import-graph [context actor input]
  (try
    (store/with-lock (get-in context [:runtime :instance]) :graph-maintenance
      (sources/with-registry-lock #(import-graph* context actor input)))
    (catch clojure.lang.ExceptionInfo e
      (if-let [code (:code (ex-data e))]
        (finding-error [{:code code :severity :error :path [:sources]
                         :message "Production Source bindings could not be installed. Check the selected Sources and release."}])
        (throw e)))))

;; --- tokens ------------------------------------------------------------------

(defn issue-token
  "Issue a graph token following this graph's serving pointer.

   **The secret is in this return value and nowhere else.** The ledger keeps a
   keyed digest (S5.9), so there is no second chance to read it — which is why
   the console shows it once and says so."
  [{:keys [runtime]} actor {:keys [graph-id expires-at asserts-user?]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (if (= :archived (graph/lifecycle-state (:graph runtime) graph-id))
      (finding-error [{:code :graph-archived :severity :error :path [:graph-id]
                       :message "Restore the Graph before creating an access token."}])
  (let [r (token/issue! (:token runtime)
                        {:graph-id graph-id :version :latest
                         :actor actor
                         :request-id (request-id "issue-token")
                         :now (now) :expires-at expires-at
                         ;; D1.9b: only the Workbench service token is issued with this;
                         ;; the ledger refuses anything but true/false.
                         :asserts-user? asserts-user?})]
    (if (seq (:findings r))
      (finding-error (:findings r))
      {:status :ok :graph-id graph-id :token-id (:token-id r)
       :token (:secret r) :secret (:secret r) :record (:record r)})))))

(defn revoke-token
  [{:keys [runtime]} actor {:keys [token-id]}]
  (let [r (token/revoke-token! (:token runtime)
                               {:token-id token-id :actor actor
                                :request-id (request-id "revoke-token")
                                :now (now)})]
    (if (seq (:findings r))
      (finding-error (:findings r))
      ;; Read the record back rather than echoing the call's own return: what
      ;; the console shows should be what the ledger now says, not what this
      ;; process believes it just wrote.
      {:status :ok :token-id token-id
       :record (token/record (:token runtime) token-id)})))

(defn set-enabled
  [{:keys [runtime]} actor {:keys [graph-id enabled?]}]
  (let [r (instance/set-graph-enabled! runtime graph-id (boolean enabled?)
                                       {:actor actor :now (now)})]
    (if (seq (:findings r))
      (finding-error (:findings r))
      {:status :ok :graph-id graph-id :enabled? (:enabled? r)})))

(defn- lifecycle-error [e]
  (finding-error [{:code (or (:code (ex-data e)) :graph-operation-failed)
                   :severity :error :path [:graph] :message (.getMessage e)}]))

(defn set-state
  [{:keys [runtime]} actor {:keys [graph-id]} state]
  (try
    (store/with-lock (:instance runtime) :graph-maintenance
      (if-not (or (some #(= graph-id (:graph %)) (graph/shelf-versions (:graph runtime)))
                  (= :archived (graph/lifecycle-state (:graph runtime) graph-id)))
        (finding-error [{:code :graph-unknown :severity :error :path [:graph-id]
                         :message "No such Graph on this instance."}])
        (assoc (graph/set-lifecycle-state! (:graph runtime) graph-id state {:actor actor :now (now)})
               :status :ok)))
    (catch clojure.lang.ExceptionInfo e (lifecycle-error e))))

(defn delete-graph
  [{:keys [runtime]} actor {:keys [graph-id confirm-name]}]
  (try
    (store/with-lock (:instance runtime) :graph-maintenance
      (cond
        (not= graph-id confirm-name)
        (finding-error [{:code :graph-confirmation-required :severity :error :path [:confirm-name]
                         :message "Type the Graph name to confirm permanent deletion."}])
        (not= :archived (graph/lifecycle-state (:graph runtime) graph-id))
        (finding-error [{:code :graph-archive-required :severity :error :path [:graph-id]
                         :message "Archive the Graph before deleting it."}])
        :else
        (let [cleanup (instance/prepare-graph-deletion! runtime
                       {:graph graph-id :graph-id (get-in (graph/lifecycle-records (:graph runtime)) [graph-id :graph-uuid])
                        :actor actor :now (now)})]
          (let [result (groups/forget-graph! runtime actor graph-id)]
            (when (= :error (:status result)) (throw (ex-info (:error result) {:code :graph-group-write-failed}))))
          (graph/set-lifecycle-state! (:graph runtime) graph-id :deleted {:actor actor :now (now)})
          {:status :ok :graph-id graph-id :runtime-cleanup cleanup})))
    (catch clojure.lang.ExceptionInfo e (lifecycle-error e))))

(defn publish-graph
  [{:keys [runtime] :as context} actor {:keys [graph-id version expected-current]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (cond
      (not (and (string? graph-id) (= 2 (count (str/split graph-id #"/" -1)))
                (every? artifact/portable-segment? (str/split graph-id #"/" -1))
                (pos-int? version)))
      (finding-error [{:code :graph-publication-invalid :severity :error :path [:graph-id]
                       :message "Publication requires a Graph coordinate and a positive version."}])
      (not= expected-current (graph/serving-version (:graph runtime) graph-id))
      (finding-error [{:code :deploy.activate/stale-expectation :severity :error
                       :path [:expected-current] :message "Serving version changed. Refresh before publishing."}])
      ;; Publication follows the same preflight and signed deployment sequence
      ;; as Studio. Activation belongs to that deployment's authenticated plan.
      :else
      (import-graph context actor {:files (graph/shelf-texts (:graph runtime) graph-id version)}))))

(defn review-publication [{:keys [runtime] :as context} options]
  (let [id (get options "graph-id") version (parse-long (str (get options "version")))]
    (if-not (some #(and (= id (:graph %)) (= version (:version %)))
                  (graph/shelf-versions (:graph runtime)))
      (finding-error [{:code :graph-version-unknown :severity :error :path [:version]
                       :message "This version is no longer available. Refresh the version list."}])
      (let [current (graph/serving-version (:graph runtime) id)
            plan (plan-import {:files (graph/shelf-texts (:graph runtime) id version)} context)]
        {:status :ok :graph-id id :version version :expected-current current
         :current (when current (catalog/version-info runtime id current))
         :target (catalog/version-info runtime id version)
         :plan plan :would-publish? (:would-import? plan)}))))
