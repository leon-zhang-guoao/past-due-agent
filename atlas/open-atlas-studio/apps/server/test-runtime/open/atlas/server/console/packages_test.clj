(ns open.atlas.server.console.packages-test
  "The two-step install: what a package says it needs, and what this machine
   has (doc 104 §8.17.7 C and E).

   Every artifact here is SEALED by `artifact/seal-graph`, not hand-written:
   the plan reads the `:package` block back through the artifact reader, so a
   fixture that skipped the sealer would be testing a shape the sealer cannot
   produce."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.server.console.ops :as ops]
            [open.atlas.server.console.packages :as packages])
  (:import [java.io File]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- sealed
  "A minimal real artifact, with `package` sealed in when given."
  ([] (sealed nil))
  ([package]
   (let [encoded (artifact/encode-content {:namespaces {"demo" {:kind :master}}} [])
         args [(:content-edn encoded) (:content-hash encoded)
               {:workspace "test" :graph "packaged" :version 3
                :cut-at "2026-09-06T00:00:00Z" :cut-by "packages-test"}
               {:findings [] :carried-only-keys [] :row-security-declared []}
               {:previous-version nil :added-attributes [] :removed-attributes []
                :modified-objects []}
               {:attribute-count 0}]
         result (if package
                  (apply artifact/seal-graph (conj args package))
                  (apply artifact/seal-graph args))]
     (assert (empty? (filter #(= :error (:severity %)) (:findings result)))
             (pr-str (:findings result)))
     (:files result))))

(defn- temp-dir ^File []
  (io/file (str (java.nio.file.Files/createTempDirectory
                 "packages-test-" (into-array java.nio.file.attribute.FileAttribute [])))))

(defn- zip!
  "Write the three texts into `<dir>/<name>`, the way an exported bundle is
   laid out (D4.13: the directory names are a convenience, not authority)."
  [^File dir name files]
  (let [f (File. dir ^String name)]
    (with-open [out (ZipOutputStream. (io/output-stream f))]
      (doseq [[nm text] files]
        (.putNextEntry out (ZipEntry. (str "test/packaged/3/" nm)))
        (.write out (.getBytes ^String text "UTF-8"))
        (.closeEntry out)))
    f))

(defn- as-bundle [files]
  (let [dir (temp-dir)
        f (zip! dir "one.zip" files)
        bytes (java.nio.file.Files/readAllBytes (.toPath f))]
    (.encodeToString (java.util.Base64/getEncoder) bytes)))

(defn- codes [plan] (set (map :code (:findings plan))))

(def ^:private compat-ok
  {:dialect dialect/format-version :connectors {}})

;; --- the plan ----------------------------------------------------------------

(deftest a-bundle-with-no-package-block-warns-and-still-installs
  ;; doc 104 §8.17.7 H item 3 (owner ruling 2026-09-06). The three bundles in
  ;; fixtures/bundles are exactly this case until they are re-cut.
  (let [plan (ops/plan-import {:files (sealed)} {:getenv (constantly nil)})]
    (is (= "test/packaged" (:graph-id plan)))
    (is (= 3 (:version plan)))
    (is (nil? (:package plan)))
    (is (:would-import? plan) "an undeclared package is a warning, not a refusal")
    (is (= #{:package/undeclared} (codes plan)))
    (is (= [:warning] (map :severity (:findings plan))))))

(deftest everything-present-plans-clean
  (let [dir (temp-dir)
        _ (spit (File. dir "client.p12") "not really a keystore")
        plan (ops/plan-import
              {:files (sealed {:name "open-atlas/packaged"
                               :requires {:vars ["A_TOKEN"] :files ["client.p12"]}
                               :compat compat-ok})}
              {:getenv {"A_TOKEN" "value" "OPEN_ATLAS_CONFIG_PATH" (.getPath dir)}})]
    (is (= "open-atlas/packaged" (:package plan)))
    (is (:would-import? plan))
    (is (empty? (:findings plan)))
    (is (= {:ok? true :findings []} (:compat plan)))
    (is (= {:vars [{:name "A_TOKEN" :present? true}]
            :files [{:path "client.p12" :present? true}]}
           (:requires plan)))))

(deftest a-missing-variable-is-named-and-its-value-is-not
  (let [plan (ops/plan-import
              {:files (sealed {:name "open-atlas/packaged"
                               :requires {:vars ["A_TOKEN"]}
                               :compat compat-ok})}
              {:getenv (constantly nil)})]
    (is (not (:would-import? plan)))
    ;; doc 106 §106.14-20 (owner 2026-09-07): the SAME code Studio raises for
    ;; the same fact, with the variable named in `:expected` and no value
    ;; anywhere in the finding.
    (is (= #{:credential/missing} (codes plan)))
    (is (= "A_TOKEN" (:name (first (:findings plan)))))
    (is (= "A_TOKEN" (:expected (first (:findings plan)))))
    (is (= [:package :requires :vars] (:path (first (:findings plan)))))
    (is (= [{:name "A_TOKEN" :present? false}] (get-in plan [:requires :vars])))))

(deftest every-code-the-console-raises-is-registered-and-missing-var-is-retired
  ;; doc 106 §106.14-20: the old code was retired outright, not kept as an
  ;; alias — a second code for one fact is exactly what the ruling removed.
  ;; The name is assembled rather than written, so this test's own source does
  ;; not become the hit the scan below reports.
  (let [retired (keyword "package" "missing-var")
        needle  (str retired)]
    (is (contains? ops/finding-codes :credential/missing))
    (is (not (contains? ops/finding-codes retired)))
    (let [hits (for [root ["src-runtime" "src-ui" "test-runtime"]
                     ^File f (file-seq (io/file root))
                     :when (and (.isFile f)
                                (re-find #"\.clj[cs]?$" (.getName f))
                                (str/includes? (slurp f) needle))]
                 (.getPath f))]
      (is (empty? hits)
          (str "no code path may still speak " needle ": " (pr-str hits))))))

(deftest a-missing-file-is-reported-against-the-anchor
  ;; H item 1: $OPEN_ATLAS_CONFIG_PATH, else $OPEN_ATLAS_HOME, else ~/.open-atlas
  ;; — the same order the connectors resolve, so the plan and the connector
  ;; cannot disagree about where a relative path points.
  (let [dir (temp-dir)
        plan (ops/plan-import
              {:files (sealed {:name "open-atlas/packaged"
                               :requires {:files ["missing.p12"]}
                               :compat compat-ok})}
              {:getenv {"OPEN_ATLAS_HOME" (.getPath dir)}})]
    (is (not (:would-import? plan)))
    (is (= #{:package/missing-file} (codes plan)))
    (is (= "missing.p12" (:file (first (:findings plan)))))
    (is (re-find (re-pattern (java.util.regex.Pattern/quote (.getPath dir)))
                 (:message (first (:findings plan))))
        "the refusal says WHERE it looked")))

(deftest an-incompatible-dialect-is-refused
  (let [plan (ops/plan-import
              {:files (sealed {:name "open-atlas/packaged"
                               :compat {:dialect "v0" :connectors {}}})}
              {:getenv (constantly nil)})]
    (is (not (:would-import? plan)))
    (is (false? (get-in plan [:compat :ok?])))
    (is (= #{:package/incompatible} (codes plan)))))

(deftest an-unregistered-connector-reads-apart-from-an-old-contract
  (let [plan (ops/plan-import
              {:files (sealed {:name "open-atlas/packaged"
                               :compat {:dialect dialect/format-version
                                        :connectors {"quantum" "v1"}}})}
              {:getenv (constantly nil)})]
    (is (not (:would-import? plan)))
    (is (= #{:package/unknown-connector} (codes plan)))))

(deftest a-bundle-arrives-the-same-way-loose-files-do
  (let [plan (ops/plan-import {:bundle (as-bundle (sealed))} {:getenv (constantly nil)})]
    (is (= "test/packaged" (:graph-id plan)))
    (is (:would-import? plan))))

;; --- the directory -----------------------------------------------------------

(deftest the-package-list-carries-each-row-s-plan
  (let [dir (temp-dir)]
    (zip! dir "one.zip" (sealed {:name "open-atlas/packaged"
                                 :requires {:vars ["NOT_SET_ANYWHERE_XYZ"]}
                                 :compat compat-ok}))
    (spit (File. dir "index.edn")
          (pr-str [{:package "open-atlas/packaged" :file "one.zip"
                    :graph-id "test/packaged" :version 3 :summary "a test package"}
                   {:package "open-atlas/gone" :file "gone.zip"
                    :graph-id "test/gone" :version 1 :summary "not on disk"}]))
    (let [{:keys [packages]} (packages/list-packages dir (constantly nil))
          [present absent] packages]
      (is (= 2 (count packages)))
      (is (= "a test package" (:summary present)))
      (is (= "test/packaged" (:graph-id present)))
      (is (not (:would-import? present)) "the row's plan decides, not the index")
      (is (= #{:credential/missing} (set (map :code (:findings present)))))
      (is (not (:would-import? absent)))
      (is (= #{:package/absent} (set (map :code (:findings absent))))))))

(deftest a-path-that-escapes-the-directory-is-not-a-package
  (let [dir (temp-dir)]
    (zip! dir "one.zip" (sealed))
    (spit (File. dir "index.edn")
          (pr-str [{:package nil :file "one.zip" :graph-id "test/packaged"
                    :version 3 :summary "a test package"}]))
    (testing "matched against the index's own names, so nothing a caller writes
              reaches the filesystem as a path"
      (doseq [escape ["../index.edn" "/etc/passwd" "one.zip/../one.zip" nil 7]]
        (is (nil? (packages/entry-for dir escape)) (pr-str escape))
        (let [r (packages/install! {} "actor" dir {:file escape} nil)]
          (is (= :error (:status r)))
          (is (= [:package/unknown] (map :code (:findings r)))))))
    (is (some? (packages/entry-for dir "one.zip")))))

(deftest bundles-dir-is-found-beside-the-package-not-in-the-working-directory
  ;; 0.2 acceptance defect 4 / install.md §7: every candidate used to be
  ;; relative to `cwd`, so a Server started from anywhere else answered with
  ;; some other `bundles/` and its own packages were invisible.
  (let [root (temp-dir)
        pkg-bundles (io/file root "bundles")
        elsewhere (temp-dir)]
    (.mkdirs pkg-bundles)
    (testing "OPEN_ATLAS_BUNDLES_DIR still wins outright"
      (is (= (.getPath elsewhere)
             (.getPath (packages/bundles-dir
                        {"OPEN_ATLAS_BUNDLES_DIR" (.getPath elsewhere)
                         "OPEN_ATLAS_PACKAGE_ROOT" (.getPath root)})))))
    (testing "the package root the launcher exports is preferred over the cwd"
      (is (= (.getCanonicalPath pkg-bundles)
             (.getCanonicalPath (packages/bundles-dir
                                 {"OPEN_ATLAS_PACKAGE_ROOT" (.getPath root)})))))
    (testing "a package root with no bundles/ falls through rather than inventing one"
      (let [found (packages/bundles-dir {"OPEN_ATLAS_PACKAGE_ROOT" (.getPath elsewhere)})]
        (is (not= (.getCanonicalPath (io/file elsewhere "bundles"))
                  (.getCanonicalPath found)))))))

(deftest package-catalogue-uses-the-same-credential-store-as-publication
  (let [dir (temp-dir)
        home (.getPath (temp-dir))
        files (sealed {:name "store-package" :requires {:vars ["PROD_CATALOGUE_TOKEN"]}
                       :compat compat-ok})]
    (zip! dir "stored.zip" files)
    (spit (File. dir "index.edn") (pr-str [{:file "stored.zip"}]))
    (open.atlas.runtime.credentials/put-vars! home {"PROD_CATALOGUE_TOKEN" "local-test-secret"})
    (let [listed (packages/list-packages dir (constantly nil) {:home home})]
      (is (true? (get-in listed [:packages 0 :would-import?])))
      (is (not (str/includes? (pr-str listed) "local-test-secret"))))))
