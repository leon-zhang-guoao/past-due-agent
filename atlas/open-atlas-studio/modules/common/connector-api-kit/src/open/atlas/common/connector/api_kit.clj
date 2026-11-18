(ns open.atlas.common.connector.api-kit
  "api connector onboarding kit — the public interface.

   The problem it exists for: the Schneider corpus has a regression suite
   because someone wrote one by hand, once, against a real integration. The
   hundredth API will not get that. This kit makes the same safety
   MECHANICAL — record the real endpoints once, redact what comes back, and
   generate the suite from the model itself.

   Three pieces and a CLI:

     `record!`             real calls through the connector's own path →
                           a redacted, deterministic fixture file
     `with-replay`         a fixture directory answering clj-http, with
                           overlays for the failures nobody may provoke live
     `conformance-tests`   a source definition + a fixture → a clojure.test
                           suite, one `deftest` per capability
     `-main`               record / verify / redaction-check

   Cross-module callers require THIS ns only (`.impl.*` is private), and this
   ns depends on the api connector, the SPI and the dialect — nothing from
   runtime / studio / server, so a source repo that holds only a graph can
   run the suite (D1.8).

   A 30-minute onboarding is in this module's README."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [open.atlas.common.connector.api-kit.impl.conformance :as conf]
            [open.atlas.common.connector.api-kit.impl.fixture :as fx]
            [open.atlas.common.connector.api-kit.impl.record :as rec]
            [open.atlas.common.connector.api-kit.impl.redact :as redact]
            [open.atlas.common.connector.api-kit.impl.replay :as replay])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Recording
;; ---------------------------------------------------------------------------

(defn record!
  "Record one operation's real calls into `<out-dir>/<source>/<op>.fixture.edn`.
   See `impl.record/record!` for the option map."
  [opts]
  (rec/record! opts))

(def default-max-string redact/default-max-string)

(defn redaction-rules
  "The normalised rule map a recording will apply — inspect it before you
   record, and pin its hash in review."
  [opts]
  (redact/rules opts))

(defn redaction-rules-hash [rules] (redact/rules-hash rules))

(defn redact
  "Apply redaction rules to a parsed JSON body. Exported because a source
   repo sometimes hand-writes a synthetic fixture body and must run it
   through the SAME rules the recorder would."
  [rules body]
  (redact/redact rules body))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn read-fixture [f] (fx/read-file f))

(defn read-fixture-dir
  "Every fixture under `dir`, keyed by `[source operation]`."
  [dir]
  (fx/read-dir dir))

(defn write-fixture! [dir fixture] (fx/write! dir fixture))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(defn replay-responder
  "`(fn [method url opts] → response)` answering from `{:fixtures … :overlays …
   :calls atom}`."
  [opts]
  (replay/responder opts))

(defmacro with-replay
  "Run `body` with `clj-http.client/get` / `/post` answered from fixtures."
  [opts & body]
  `(replay/with-replay ~opts ~@body))

;; ---------------------------------------------------------------------------
;; Conformance
;; ---------------------------------------------------------------------------

(defn semantic-source-defs
  "Every api read binding of a Semantic 1.4 document, as source definitions."
  ([document] (semantic-source-defs document {}))
  ([document connections] (conf/semantic-source-defs document connections)))

(defn capabilities [source-def] (conf/capabilities source-def))

(defn test-names
  "The `deftest` symbols `defconformance` would define for this source
   definition — the generator's own contract, assertable in a unit test."
  [source-def]
  (mapv #(conf/test-name (conf/operation-source-def source-def) %)
        (conf/capabilities source-def)))

(defn conformance-tests
  "The generated suite as DATA: `[{:capability … :test/name … :label … :run …}]`.

   `fixture-dir-or-fixture` is either a fixture map (already read) or a
   directory, in which case the fixture recorded for this source definition's
   `[source id]` is the one used."
  [source-def fixture-dir-or-fixture]
  (let [fixture (if (map? fixture-dir-or-fixture)
                  fixture-dir-or-fixture
                  (get (fx/read-dir fixture-dir-or-fixture)
                       [(:source-id source-def) (:id source-def)]))]
    (when-not fixture
      (throw (ex-info "api-kit: no fixture recorded for this source definition"
                      {:code   :api-kit.conformance/missing-fixture
                       :source (:source-id source-def)
                       :operation (:id source-def)})))
    (conf/conformance-tests source-def fixture)))

(defn run-conformance!
  "Run the generated suite for one source definition under `clojure.test`,
   grouping cases by capability. → the `clojure.test` summary."
  [source-def fixture-dir]
  (let [cs (conformance-tests source-def fixture-dir)]
    (test/with-test-out
      (doseq [[cap group] (group-by :capability cs)]
        (test/testing (str (:id source-def) " / " (name cap))
          (doseq [c group] (test/testing (:label c) ((:run c)))))))
    cs))

(defmacro defconformance
  "Define one `deftest` per capability for `source-def-expr` against
   `fixture-dir-expr`.

     (api-kit/defconformance se-detail
       (first (api-kit/semantic-source-defs document))
       \"fixtures\")

   The names are computed at macro-expansion (the source definition is a
   value, not a query), so the suite reads like hand-written tests in the
   runner's output and a capability that disappeared from a model is a
   `deftest` that disappeared from the file."
  [prefix source-def-expr fixture-dir-expr]
  (let [sd   (eval source-def-expr)
        caps (conf/capabilities sd)]
    `(do
       ~@(for [cap caps]
           `(test/deftest ~(symbol (str (name prefix) "-" (name cap)))
              (let [sd# ~source-def-expr
                    fx# (conformance-tests sd# ~fixture-dir-expr)]
                (doseq [c# fx# :when (= ~cap (:capability c#))]
                  (test/testing (:label c#) ((:run c#))))))))))

;; ---------------------------------------------------------------------------
;; redaction-check
;; ---------------------------------------------------------------------------

(defn redaction-check
  "Scan a fixture directory for anything still shaped like a secret, an
   e-mail or a token. → `[{:file … :path [..] :rule :email}]`, values never
   carried. An empty vector is the only acceptable result."
  [dir]
  (vec
   (for [f     (fx/fixture-files dir)
         :let  [fixture (fx/read-file f)]
         e     (:api-kit/exchanges fixture)
         hit   (concat (redact/scan (:body e))
                       (redact/scan (into {} (:headers e))))]
     (assoc hit :file (.getPath ^java.io.File f)))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- read-edn-file [p] (edn/read-string (slurp (io/file p))))

(defn- verify
  "Run the generated suite for every api read binding of a Semantic document
   against a fixture directory."
  [document-path fixture-dir]
  (let [doc  (read-edn-file document-path)
        defs (conf/semantic-source-defs doc {})
        res  (atom {:pass 0 :fail 0 :error 0})]
    (doseq [sd defs]
      (let [summary (test/with-test-out
                      (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                        (doseq [c (conformance-tests sd fixture-dir)]
                          (test/testing (str (:id sd) " / " (name (:capability c))
                                             " — " (:label c))
                            (try ((:run c))
                                 (catch Throwable t
                                   (test/do-report {:type :error :message (str t)
                                                    :expected nil :actual t})))))
                        @test/*report-counters*))]
        (swap! res #(merge-with + % (select-keys summary [:pass :fail :error])))))
    (println (str "api-kit verify: " (count defs) " source definition(s), "
                  (:pass @res) " assertions passed, "
                  (:fail @res) " failed, " (:error @res) " errored"))
    (if (zero? (+ (:fail @res) (:error @res))) 0 1)))

(defn -main
  "record | verify | redaction-check.

     clojure -M:kit verify <semantic-document.edn> <fixture-dir>
     clojure -M:kit redaction-check <fixture-dir>
     clojure -M:kit record <plan.edn>

   `record` takes a plan file: an EDN map of the `record!` option map, because
   a recording names a live endpoint and its samples, and that belongs in a
   reviewable file rather than in shell history."
  [& args]
  (let [[cmd & more] args
        code
        (case cmd
          "verify" (apply verify (take 2 more))

          "redaction-check"
          (let [hits (redaction-check (first more))]
            (if (seq hits)
              (do (println (str "api-kit redaction-check: " (count hits)
                                " suspicious value(s); values are not printed"))
                  (doseq [h (sort-by (juxt :file (comp pr-str :path)) hits)]
                    (println (str "  " (:file h) "  " (str/join "." (:path h))
                                  "  " (name (:rule h)))))
                  1)
              (do (println "api-kit redaction-check: clean") 0)))

          "record"
          (let [plan (read-edn-file (first more))
                r    (record! plan)]
            (println (str "api-kit record: " (:file r) " (" (:exchanges r) " exchange(s))"))
            (doseq [f (:failures r)] (println (str "  refused: " (:category f))))
            0)

          (do (println (:doc (meta #'-main))) 2))]
    (System/exit code)))
