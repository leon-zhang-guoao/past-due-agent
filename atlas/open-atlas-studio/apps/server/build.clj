(ns build
  "Build tasks for apps/server.

   `uber` builds the **standalone Server** — `open.atlas.server.main` on the
   `:prod` basis, which replaces `:paths`. There used to be a second artifact,
   `uber-legacy`, for the inherited `ent.dx.*` stack; that stack was retired in
   8.16 and the task went with it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/open-atlas-server.jar")

(defn- basis
  "Dependency basis for the build. `aliases` lets a release swap pinned jars
   (e.g. :clickhouse-legacy in deps.edn) without editing :deps — the uberjar
   bakes drivers in, so basis creation is the only seam where a swap can act:
   `clojure -T:build uber :aliases '[:clickhouse-legacy]'`."
  [aliases]
  (b/create-basis {:project "deps.edn"
                   :aliases (mapv keyword aliases)}))

(def ^:private demo-fixture "../../fixtures/dvd/dvd-db")

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn demo-db
  "Build the DVD demo database from the committed schema.sql + seed.sql (8.14).

   Generated, therefore not in the repository — and built here rather than by a
   script, so a clean clone produces a package whose bundled example works. The
   Server materialises it under the instance home on first start; SQLite has to
   open a real path, not a jar URL."
  [_]
  (let [target (io/file "target/data/open-atlas-demo.db")]
    (b/delete {:path (.getPath target)})
    (io/make-parents target)
    (Class/forName "org.sqlite.JDBC")
    (with-open [conn (java.sql.DriverManager/getConnection
                      (str "jdbc:sqlite:" (.getPath target)))
                stmt (.createStatement conn)]
      (doseq [source ["schema.sql" "seed.sql"]
              sql (statements (slurp (io/file demo-fixture source)))]
        (.execute stmt sql)))
    (println (str "Demo database built: " (.getPath target))))
  nil)

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "The standalone Server (8.12 P5).

   `resources-console` carries the console's page. It is BUILD OUTPUT for the
   most part (`npm run console:release` writes `resources-console/js`), so the
   image builds it in an earlier stage; building the jar without it produces a
   server whose console answers 500 saying exactly that, rather than a blank
   page."
  [{:keys [aliases]}]
  (clean nil)
  ;; `drivers` runs after, into the same target/: the jar and the directory
  ;; beside it are one deliverable.
  (demo-db nil)
  (b/copy-dir {:src-dirs ["src-runtime" "resources-console"]
               :target-dir class-dir})
  ;; The bundled example: the DVD model and its database. The console offers to
  ;; import it; nothing is put on the shelf without somebody asking (8.14).
  (b/copy-dir {:src-dirs ["target/data"] :target-dir (str class-dir "/data")})
  (b/copy-file {:src "../../fixtures/dvd/model.edn"
                :target (str class-dir "/demo/model.edn")})
  ;; Its sibling seed graph list (doc 104 §8.17.6, OQ-STUDIO-SEED): the graphs a
  ;; fresh Studio registers come from the model's own file, so the packaged copy
  ;; has to travel with the packaged model.
  (b/copy-file {:src "../../fixtures/dvd/graphs.edn"
                :target (str class-dir "/demo/graphs.edn")})
  (let [bs (basis (into [:prod] aliases))]
    (b/compile-clj {:basis bs
                    :ns-compile '[open.atlas.server.main
                                  open.atlas.server.identity.bootstrap]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis bs
             :main 'open.atlas.server.main}))
  (println (str "Standalone Server uberjar built: " uber-file)))

(def drivers-dir "target/drivers")

(defn drivers
  "Lay the JDBC drivers out BESIDE the jar (8.13).

   They are not in the jar: `deps.edn`'s `:drivers` alias holds them, this task
   copies that alias's extra jars into `target/drivers`, and the launcher puts
   the directory on the classpath. Discovery does not change — a driver is
   found through `META-INF/services/java.sql.Driver` either way, and nothing in
   this codebase names a driver class.

   What changes is who decides. An operator can drop in the version their
   server actually needs, or delete the ones they do not run, without this
   repository being rebuilt. The package still ships all of them, so the
   default remains: unpack, start, connect."
  [_]
  (let [with*    (b/create-basis {:project "deps.edn" :aliases [:prod :drivers]})
        without  (b/create-basis {:project "deps.edn" :aliases [:prod]})
        extra    (apply dissoc (:libs with*) (keys (:libs without)))
        jars     (mapcat :paths (vals extra))]
    (b/delete {:path drivers-dir})
    (doseq [^String jar jars]
      (b/copy-file {:src jar
                    :target (str drivers-dir "/" (.getName (java.io.File. jar)))}))
    (println (str "Drivers laid out beside the jar: " (count jars) " in " drivers-dir))
    (doseq [^String jar (sort jars)]
      (println (str "  " (.getName (java.io.File. jar)))))))

