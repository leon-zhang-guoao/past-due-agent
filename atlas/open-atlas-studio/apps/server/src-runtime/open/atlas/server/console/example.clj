(ns open.atlas.server.console.example
  "The bundled example: a DVD rental store (8.14).

   A downloaded Server has an empty shelf, and an empty shelf answers every
   question with nothing. This makes the first import a button rather than a
   scavenger hunt: the package carries a model and its SQLite database, the
   console offers them, and importing runs the SAME signed
   offer → stage → load → activate everything else does.

   Two things it deliberately does NOT do:

   - **It does not import itself.** Putting a graph on the shelf is an act with
     an actor and an audit line; doing it silently at startup would mean the
     first thing an operator sees is a machine that has already decided what it
     serves.
   - **It does not rewrite what it publishes.** The model declares
     `jdbc:sqlite:data/open-atlas-demo.db`, a relative path that resolves nowhere in
     particular. So the database is materialised under the instance home ONCE,
     and the absolute path is set before the artifact is sealed — the artifact
     is then true about where its data is, rather than being adjusted at read
     time by something downstream (D4.10-a: a sealed artifact is a fact)."
  (:require [clojure.java.io :as io]
            [open.atlas.common.artifact :as artifact]
            ;; Required for its SPI registration, not for a var: the `:package`
            ;; block's `:files` comes from `spi/declared-files`, and the "sql"
            ;; method that knows a sqlite path hides in `:jdbc_url` is put on
            ;; the dispatch by loading this ns (the host decides which
            ;; connectors exist by deciding which ones it requires).
            [open.atlas.common.connector.sql]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.store :as store])
  (:import [java.io File]))

(def graph-id "demo/dvd")
(def ^:private database-name "open-atlas-demo.db")
(def ^:private model-resource "demo/model.edn")
(def ^:private database-resource "data/open-atlas-demo.db")

;; The fixture is the fallback for a SOURCE run: `clojure -M:prod:drivers:runtime`
;; has neither resource on its classpath (they are put there by `uber`), and a
;; developer running the Server from a checkout should meet the same example a
;; downloader does — otherwise the thing being demonstrated is only ever tested
;; in its packaged form.
(def ^:private fixture-model "../../fixtures/dvd/model.edn")
(def ^:private fixture-db-dir "../../fixtures/dvd/dvd-db")

(defn- model-text []
  (if-let [resource (io/resource model-resource)]
    (slurp resource)
    (let [file (io/file fixture-model)]
      (when (.isFile file) (slurp file)))))

(defn- sql-statements [text]
  (->> (clojure.string/split-lines text)
       (remove #(clojure.string/starts-with? (clojure.string/triml %) "--"))
       (clojure.string/join "\n")
       (#(clojure.string/split % #";"))
       (map clojure.string/trim)
       (remove clojure.string/blank?)))

(defn- build-from-fixture!
  "Build the example database from the committed schema.sql + seed.sql."
  [^File target]
  (let [schema (io/file fixture-db-dir "schema.sql")
        seed   (io/file fixture-db-dir "seed.sql")]
    (when (and (.isFile schema) (.isFile seed))
      (io/make-parents target)
      (with-open [conn (java.sql.DriverManager/getConnection
                        (str "jdbc:sqlite:" (.getPath target)))
                  stmt (.createStatement conn)]
        (doseq [file [schema seed]
                sql  (sql-statements (slurp file))]
          (.execute stmt sql)))
      target)))

(defn database-file
  "`<instance root>/demo/open-atlas-demo.db`."
  ^File [instance]
  (File. (store/subsystem-dir instance "demo") database-name))

(defn available?
  "Is an example packaged with this build? A source checkout that never ran
   `clojure -T:build demo-db` has the model but not the database."
  []
  (boolean (and (model-text)
                (or (io/resource database-resource)
                    (.isFile (io/file fixture-db-dir "seed.sql"))))))

(defn materialise!
  "Copy the packaged database under the instance home, once.

   **Never overwritten**: between restarts that file is the operator's own copy
   of the example, and they may well have edited it — that is what an example
   is for."
  [instance]
  (let [target (database-file instance)]
    (when-not (.isFile target)
      (if-let [resource (io/resource database-resource)]
        (do (io/make-parents target)
            (with-open [in (io/input-stream resource)
                        out (io/output-stream target)]
              (io/copy in out)))
        (build-from-fixture! target)))
    (when (.isFile target) target)))

(defn- unknown-connector-findings
  "Sources whose `:type` has no connector registered in THIS build.

   doc 104 §8.17.7 A keeps `spi/package-compat` pure — an unregistered type is
   listed with a nil version, since the installing side has to see it. Sealing
   that nil is another matter: `declared-files` would fall back to `[]` and the
   manifest would under-declare what the package needs, silently. CLAUDE.md:
   超限、冲突、缺失都拒绝而非截断/猜测. The refusal is therefore the caller's,
   and it lands before `seal-graph`, not after.

   Here the cause would be this ns's connector `:require` list going stale —
   the same defect Studio's cut path refuses on, with the same code."
  [model]
  (vec (for [[id cfg] (sort-by (comp str key) (:sources model))
             :let [t (:type cfg)]
             :when (nil? (spi/contract-version t))]
         {:code :package/unknown-connector :severity :error
          :path [:sources (str id) :type]
          :message (str "source " (pr-str (str id)) " has type " (pr-str t)
                        ", and this build has no connector registered for it; "
                        "sealing would write a package that under-declares what it needs")
          :got t})))

(defn artifact-files
  "Seal the packaged model against this instance's copy of the database.
   → `{:files {…}}` or `{:findings […]}`."
  [instance now]
  (if-not (available?)
    {:findings [{:code :server.example/absent :severity :error :path [:example]
                 :message (str "this build carries no bundled example; it is produced by "
                               "`clojure -T:build demo-db` and packaged by `uber`")}]}
    (let [database (materialise! instance)
          {:keys [model findings]} (dialect/read-dialect (model-text))
          unknown  (when model (unknown-connector-findings model))]
      (cond
        (nil? model) {:findings (vec findings)}
        (nil? database) {:findings [{:code :server.example/absent :severity :error
                                     :path [:example] :message
                                     "the example database could not be materialised"}]}
        ;; Refuse before sealing (doc 104 §8.17.7 A + CLAUDE.md's
        ;; no-silent-degradation rule); see `unknown-connector-findings`.
        (seq unknown) {:findings unknown}

        :else
        (let [model    (assoc-in model [:sources "dvd-demo-db" :jdbc_url]
                                 (str "jdbc:sqlite:" (.getCanonicalPath database)))
              encoded  (artifact/encode-content model [])
              sealed   (artifact/seal-graph
                        (:content-edn encoded) (:content-hash encoded)
                        {:workspace "demo" :graph "dvd" :version 1
                         :cut-at now :cut-by "bundled-example"}
                        {:findings [] :carried-only-keys [] :row-security-declared []}
                        {:previous-version nil :added-attributes [] :removed-attributes []
                         :modified-objects []}
                        {:attribute-count (count (:attributes model))}
                        ;; doc 104 §8.17.7 A. The SAME block shape and the same
                        ;; computing functions as Studio's cut path, so a graph
                        ;; sealed here and one cut there describe their
                        ;; requirements identically (the §8.17.6 lesson).
                        ;;
                        ;; Sealed over the model whose `:jdbc_url` was already
                        ;; made absolute above, which is the bundled demo's
                        ;; documented exception (8.14.3): a package anchors, a
                        ;; demo rewrites.
                        {:name     "open-atlas/dvd"
                         :requires (spi/package-requirements model)
                         :compat   (spi/package-compat model)})
              errors   (vec (filter #(= :error (:severity %)) (:findings sealed)))]
          (if (seq errors) {:findings errors} {:files (:files sealed)}))))))
