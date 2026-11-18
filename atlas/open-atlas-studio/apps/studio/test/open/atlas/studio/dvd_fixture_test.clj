(ns open.atlas.studio.dvd-fixture-test
  "**The guard for OQ-DVD-CUT** (doc 104 §8.17.6, owner ruling 2026-09-06).

   The DVD store is the seed a person meets first: `fixtures/dvd/model.edn` is
   what a fresh Studio imports, and `fixtures/dvd/graphs.edn` beside it names
   the graph it registers. Both properties below were verified by hand when the
   fixture was repaired (commit 2641418) and by nothing else afterwards, which
   is exactly the shape of a regression nobody notices until first run:

   1. the fixture reads under the latest dialect with no `:error` finding and is
      already in canonical form (D2.12 — one form, so `write ∘ read` is the
      identity on a canonical file);
   2. a pool seeded from it the way `builder-server/bootstrap!` seeds one
      answers `cut-check` for `demo/dvd` with `:would-cut? true` and nothing to
      report — the resolvers and the `:curated? true` rulings the ruling added
      are what make that true, so dropping one must turn this red.

   The cut path here is the Builder's own service, not `version/cuttable`
   directly: `cut-check` is what the button and the agent both call. It projects
   and health-checks a projected model, so no live SQLite file is needed — the
   demo database is a query-time concern, not a cut-time one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.app :as app]
            [open.atlas.studio.builder-server]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:private fixture-dir
  "`clojure -M:test` runs from `apps/studio`; the aggregate runner runs from the
   repository root. Both find the fixture, and neither silently tests nothing."
  (->> ["../../fixtures/dvd" "fixtures/dvd"]
       (map io/file)
       (filter #(.isFile (io/file % "model.edn")))
       first))

(defn- fixture-file [name]
  (assert fixture-dir "fixtures/dvd not found from this working directory")
  (io/file fixture-dir name))

(def ^:dynamic *state* nil)
(def ^:dynamic *owner* nil)

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))

(defn- rm-r [path]
  (doseq [f (reverse (file-seq (java.io.File. ^String path)))] (.delete ^java.io.File f)))

(defn- check! [r] (assert (empty? (:findings r)) (pr-str (:findings r))) r)

(def ^:private publish-service
  (var-get #'open.atlas.studio.builder-server/publish-service))
(def ^:private studio-state
  (var-get #'open.atlas.studio.builder-server/studio-state))

(defn- seed-pool!
  "The same three steps `bootstrap!` takes: import the model's objects stamped
   with the seed workspace (D3.22), then register each graph `graphs.edn`
   declares, its members expanded from the selectors at registration time
   (D3.19)."
  [conn model-text]
  (let [model (:model (dialect/read-dialect model-text))]
    (check! (pool/import-pool!
             conn {:pool/version 1
                   :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                       (pool/dialect->objects model))}))
    (doseq [{:keys [name selectors package-name]}
            (edn/read-string (slurp (fixture-file "graphs.edn")))]
      (check! (graph/register-graph!
               conn (cond-> {:workspace "demo" :name name
                             :created-at "2026-09-06T00:00:00Z"
                             :members (graph/expand-selectors (pool/as-dialect (pool/db conn))
                                                              (vec selectors))}
                       ;; doc 104 §8.17.7 D — the seed's release name is
                       ;; modelling content and rides in `graphs.edn`.
                       package-name (assoc :package-name package-name)))))))

(defn- with-studio [f]
  (let [runtime-root (tmp "dvd-rt-")
        artifact-root (tmp "dvd-art-")
        started (app/start! {:pool* {:backend :mem :id (str (gensym "dvd"))}
                             :instance-id "studio-embedded"
                             :runtime-root runtime-root
                             :artifact-root artifact-root
                             :token-pepper "studio-pepper"
                             :actor-key (constantly nil)})
        conn (:conn started)]
    (assert (empty? (:findings started)) (pr-str (:findings started)))
    (try
      (identity/install! conn)
      (let [owner (:user (identity/bootstrap-admin!
                          conn {:email "owner@example.com" :name "Owner"
                                :password "a-safe-password" :workspace "demo"}))]
        (reset! studio-state started)
        (binding [*state* started *owner* (:principal owner)]
          (f)))
      (finally
        (reset! studio-state nil)
        (app/stop! started)
        (rm-r runtime-root) (rm-r artifact-root)))))

(use-fixtures :each with-studio)

(defn- cut! [graph]
  (open.atlas.studio.version/cut-version!
   (:conn *state*)
   {:graph graph :resolved []
    :artifact-root (:artifact-root *state*)
    :cut-at "2026-09-06T00:00:00Z" :cut-by "dvd-fixture-test"}))

(defn- manifest-of
  "The `manifest.edn` a cut produced, read back as data."
  [cut]
  (edn/read-string (get (:files cut) "manifest.edn")))

(defn- cut-check [graph]
  ((:cut-check (publish-service {:workspace "demo" :principal *owner*})) {:graph graph}))

(deftest the-packaged-dvd-fixture-reads-and-is-already-canonical
  (let [text (slurp (fixture-file "model.edn"))
        {:keys [model findings]} (dialect/read-dialect text)]
    (is (empty? (filter #(= :error (:severity %)) findings))
        (pr-str (vec findings)))
    (is (some? model))
    (testing "and the file on disk is the canonical form (D2.12)"
      (is (= text (dialect/write-dialect model))))))

(deftest a-studio-seeded-from-the-dvd-fixture-can-cut-its-graph
  (seed-pool! (:conn *state*) (slurp (fixture-file "model.edn")))
  (let [r (cut-check "demo/dvd")]
    (is (true? (:would-cut? r)) (pr-str (:health-findings r)))
    (is (empty? (:health-findings r)))
    (is (empty? (:findings r)))))

(deftest dropping-a-curated-ruling-makes-the-seed-uncuttable
  "The other half of the guard: OQ-DVD-CUT was repaired partly by recording
   `:curated? true` on the unanchored lists (D2.13), so a test that only ever
   sees the good fixture cannot tell whether it is checking anything. The
   model text is edited in memory — the fixture on disk is the model's home and
   is never written by a test."
  (let [text (slurp (fixture-file "model.edn"))
        broken (str/replace-first text ":curated? true" ":curated? false")]
    (is (not= text broken) "the fixture must still carry a curated ruling to drop")
    (seed-pool! (:conn *state*) broken)
    (let [r (cut-check "demo/dvd")]
      (is (false? (:would-cut? r)))
      (is (seq (:health-findings r))))))

;; ---------------------------------------------------------------------------
;; The package block (doc 104 §8.17.7 A / D)
;; ---------------------------------------------------------------------------

(deftest a-cut-manifest-carries-the-package-block
  "Sealed at CUT time, not at export: export re-reads what the manifest already
   says, so a requirement that is not written here can never appear downstream."
  (seed-pool! (:conn *state*) (slurp (fixture-file "model.edn")))
  (let [cut (cut! "demo/dvd")
        pkg (:package (manifest-of cut))]
    (is (empty? (:findings cut)) (pr-str (:findings cut)))
    (is (= "open-atlas/dvd" (:name pkg)))
    (testing "requirements come from the model's own sources, via the connector"
      (is (= ["data/open-atlas-demo.db"] (:files (:requires pkg))))
      (is (= [] (:vars (:requires pkg)))))
    (testing "and the contract versions it was cut against"
      (is (= {"sql" "v1"} (:connectors (:compat pkg))))
      (is (some? (:dialect (:compat pkg)))))))

(deftest a-personal-workspace-with-no-release-name-gets-the-default
  "doc 104 §8.17.7 D, owner ruling 2026-10-01 (reversing 2026-09-06 H item 2).
   The `<workspace>/<graph>` fallback would put a person in a package name when
   the workspace is an email address, so a personal workspace falls back to
   `open-atlas/<graph>` instead of refusing: publishing needs no input, the
   person never appears, and the name is visible and replaceable."
  (seed-pool! (:conn *state*) (slurp (fixture-file "model.edn")))
  (check! (graph/register-workspace!
           (:conn *state*)
           {:name "jie.gao@example.com"
            :members [{:principal *owner* :role :owner}]}))
  (check! (graph/register-graph! (:conn *state*)
                                 {:workspace "jie.gao@example.com" :name "personal"
                                  :created-at "2026-09-06T00:00:00Z"
                                  ;; Not an empty recipe: since doc 106 §106.12
                                  ;; 13.3 that is its own refusal
                                  ;; (`:cut.structure/empty-recipe`), and this
                                  ;; test is about the package name.
                                  :members (graph/expand-selectors
                                            (pool/as-dialect (pool/db (:conn *state*)))
                                            ["dvd.*"])}))
  (testing "the rule itself: default, flagged as one, and never the email"
    (let [g (graph/graph (pool/db (:conn *state*)) "jie.gao@example.com/personal")
          r (graph/package-name-for g)]
      (is (= {:name "open-atlas/personal" :default? true} r))
      (is (not (str/includes? (:name r) "@")))))
  (testing "a cut seals under the default"
    (let [r (cut! "jie.gao@example.com/personal")]
      (is (empty? (:findings r)) (pr-str (:findings r)))
      (is (= "open-atlas/personal" (:name (:package (manifest-of r)))))))
  (testing "setting a release name replaces it"
    (check! (graph/set-package-name! (:conn *state*) "jie.gao@example.com/personal"
                                     "acme/personal-dvd"))
    (is (= {:name "acme/personal-dvd"}
           (graph/package-name-for
            (graph/graph (pool/db (:conn *state*)) "jie.gao@example.com/personal")))))
  (testing "a team workspace keeps `<workspace>/<graph>`"
    (is (= {:name "demo/dvd" :default? true}
           (graph/package-name-for {:graph/workspace "demo" :graph/name "dvd"
                                    :graph/qualified-name "demo/dvd"}))))
  (testing "only a graph name with nothing usable is refused"
    (is (= [:package/name-required]
           (mapv :code (:findings (graph/package-name-for
                                   {:graph/workspace "a@b.c" :graph/name "..."
                                    :graph/qualified-name "a@b.c/..."})))))))

(deftest a-malformed-release-name-is-refused
  "The character rules are a graph id's: two segments, no second separator. A
   name that parses into three segments downstream is an identity that is no
   longer unique (D3.10, same reason)."
  (seed-pool! (:conn *state*) (slurp (fixture-file "model.edn")))
  (doseq [bad ["open-atlas" "open atlas/dvd" "open-atlas/dvd/extra" "" "/dvd"]]
    (let [r (graph/set-package-name! (:conn *state*) "demo/dvd" bad)]
      (is (= [:graph.registry/malformed] (mapv :code (:findings r)))
          (str "accepted a malformed release name: " (pr-str bad)))))
  (testing "and a well-formed one is stored and read back"
    (check! (graph/set-package-name! (:conn *state*) "demo/dvd" "open-atlas/dvd-2"))
    (is (= "open-atlas/dvd-2"
           (:graph/package-name (graph/graph (pool/db (:conn *state*)) "demo/dvd"))))
    (testing "clearing it removes the key rather than blanking it"
      (check! (graph/set-package-name! (:conn *state*) "demo/dvd" nil))
      (is (not (contains? (graph/graph (pool/db (:conn *state*)) "demo/dvd")
                          :graph/package-name))))))
