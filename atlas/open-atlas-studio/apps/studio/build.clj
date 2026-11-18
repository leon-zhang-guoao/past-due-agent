(ns build
  "Production bundle for the first Studio Builder slice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/open-atlas-studio.jar")

(defn- basis
  "Dependency basis for the build. `aliases` lets a release swap pinned jars
   (e.g. :clickhouse-legacy in deps.edn) without editing :deps — the uberjar
   bakes drivers in, so basis creation is the only seam where a swap can act:
   `clojure -T:build uber :aliases '[:clickhouse-legacy]'`."
  [aliases]
  (b/create-basis (cond-> {:project "deps.edn"}
                    (seq aliases) (assoc :aliases (mapv keyword aliases)))))

(def ^:private demo-databases
  "Fixture directory → the database file name the models declare.

   The databases are BUILT here, from the committed schema.sql + seed.sql, and
   are not committed themselves. Before this, `apps/server/data/cis-demo.db`
   was copied into the jar — a file `apps/server/.gitignore` excludes, so a
   clean clone produced a Studio whose first start died on \"Bundled CIS demo
   database is missing\". A generated artifact belongs to the build, not to the
   repository (8.14)."
  {"../../fixtures/dvd/dvd-db"   "open-atlas-demo.db"
   "../../fixtures/demo/demo-db" "cis-demo.db"})

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn demo-db
  "Build each demo database into `target/data/`."
  [_]
  (doseq [[dir file] demo-databases]
    (let [target (io/file "target/data" file)]
      (b/delete {:path (.getPath target)})
      (io/make-parents target)
      (Class/forName "org.sqlite.JDBC")
      (with-open [conn (java.sql.DriverManager/getConnection
                        (str "jdbc:sqlite:" (.getPath target)))
                  stmt (.createStatement conn)]
        (doseq [source ["schema.sql" "seed.sql"]
                sql (statements (slurp (io/file dir source)))]
          (.execute stmt sql)))
      (println (str "Demo database built: " (.getPath target)))))
  nil)

(defn clean [_]
  (b/delete {:path "target"}))

(def drivers-dir "target/drivers")

(defn drivers
  "Lay the JDBC drivers out BESIDE the jar (8.13), as apps/server does.

   They are in the `:drivers` alias rather than `:deps`, so the jar does not
   carry them and an operator can swap one without this repository being
   rebuilt. Discovery is unchanged — `META-INF/services/java.sql.Driver` — so
   the directory on the classpath is the whole mechanism. The package ships
   all of them, so an unpacked Studio connects out of the box.

   SQLite is not among them: `common/search` keeps its index in a SQLite
   sidecar, so that driver travels inside the jar with the module that needs
   it. See the note in deps.edn."
  [{:keys [aliases]}]
  (let [with*   (b/create-basis {:project "deps.edn"
                                 :aliases (into [:drivers] (mapv keyword aliases))})
        without (b/create-basis {:project "deps.edn"})
        extra   (apply dissoc (:libs with*) (keys (:libs without)))
        jars    (mapcat :paths (vals extra))]
    (b/delete {:path drivers-dir})
    (doseq [^String jar jars]
      (b/copy-file {:src jar
                    :target (str drivers-dir "/" (.getName (java.io.File. jar)))}))
    (println (str "Drivers laid out beside the jar: " (count jars) " in " drivers-dir))
    (doseq [^String jar (sort jars)]
      (println (str "  " (.getName (java.io.File. jar)))))))

(defn uber [{:keys [aliases]}]
  (clean nil)
  ;; This used to copy `../../../open-atlas-studio/{src,resources}` first and
  ;; overlay this app on top — the migration's scaffolding, from when the
  ;; mature Studio backend still lived in a sibling checkout. Removed
  ;; 2026-08-31 after a survey found nothing left behind it: of the 170
  ;; namespaces reachable from `builder-server`, ZERO resolve only in that
  ;; checkout (they are all in `apps/studio/src` or `modules/**` now), and the
  ;; sibling's `resources/public` holds no file this app does not have.
  ;;
  ;; It was not merely redundant. It made this jar unbuildable from a clean
  ;; clone — the path leaves the repository — and it packed ~10.5k lines of
  ;; superseded code into the artifact, unreachable but shipped, which is the
  ;; same "second stack inside the artifact" that 8.12 removed from the Server.
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (when-let [ui-dir (System/getenv "OPEN_ATLAS_STUDIO_PACKAGE_UI_DIR")]
    ;; Replace the shared development output completely. Besides preventing a
    ;; watcher race, this keeps dev-only cljs-runtime modules out of the jar.
    (b/delete {:path (str class-dir "/public/js")})
    (b/copy-dir {:src-dirs [ui-dir]
                 :target-dir (str class-dir "/public/js")}))
  ;; Offline Help is part of the Studio deliverable. An installed Studio must
  ;; explain the Contract and import/deploy workflow without a source checkout.
  (b/copy-dir {:src-dirs ["../../docs/help"] :target-dir (str class-dir "/help")})
  (b/copy-file {:src "../../docs/contracts/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md"
                :target (str class-dir "/help/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md")})
  ;; SQLite cannot query inside a jar. `platform-runtime` materialises this
  ;; bundled standard demo under the Studio home on first production startup.
  (demo-db nil)
  (b/copy-dir {:src-dirs ["target/data"] :target-dir (str class-dir "/data")})
  ;; The seed model, by the name `builder-server` looks for on the classpath. A
  ;; Studio with an empty pool is seeded from it once, and a downloaded jar is
  ;; started where no checkout exists — without this it dies on first start.
  ;; The DVD store is the packaged seed (owner ruling 2026-09-01): it is the
  ;; example a person meets first, and it explains itself — renting a disc,
  ;; returning it, paying for it. `fixtures/demo` (cis) stays what it has
  ;; always been, thefixture six modules' tests run through every day.
  (b/copy-file {:src "../../fixtures/dvd/model.edn"
                :target (str class-dir "/demo/model.edn")})
  ;; Its sibling seed graph list (doc 104 §8.17.6, OQ-STUDIO-SEED): the graphs a
  ;; fresh Studio registers come from the model's own file, so the packaged copy
  ;; has to travel with the packaged model.
  (b/copy-file {:src "../../fixtures/dvd/graphs.edn"
                :target (str class-dir "/demo/graphs.edn")})
  (let [bs (basis aliases)]
    (b/compile-clj {:basis bs
                    :ns-compile '[open.atlas.studio.builder-server]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis bs
             :main 'open.atlas.studio.builder-server}))
  (println (str "Studio Builder uberjar built: " uber-file)))
