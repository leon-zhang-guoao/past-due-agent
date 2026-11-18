(ns open.atlas.studio.ui-test-suite-test
  "The cljs suite, run from the clj one.

   `npm run test:ui` compiles `test-ui/**` to node and runs it. It is the ONLY
   place the seam between the UI and the routes is asserted: `clojure -M:test`
   builds its request bodies by hand, so it stays green while the screen sends
   a shape no route can read — which is how a removal came to report itself
   done having written nothing (2026-09-07, `form-logic`'s Request shapes).

   It is invoked from here rather than left to a person or to a CI file this
   repository does not have, because a defence nobody runs is not a defence:
   before this, `test:ui` was referenced by no script, no workflow and no
   README in the whole tree.

   A missing toolchain is a FAILURE, not a skip. Skipping quietly is the same
   silent degradation the suite exists to catch — the run would go green
   having asserted nothing, and the next person would trust it."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- studio-dir
  "This app's directory, whether the suite was started from here or from the
   repository root."
  []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (first (filter #(.isFile (io/file % "package.json"))
                   [cwd (io/file cwd "apps" "studio")]))))

(deftest the-cljs-suite-passes
  (let [dir (studio-dir)]
    (is (some? dir)
        "apps/studio was not found from the working directory, so test:ui could not be run")
    (when dir
      (testing "node and its packages are installed — a missing toolchain is a failure"
        (is (.isDirectory (io/file dir "node_modules"))
            "node_modules is missing: run `npm install` in apps/studio"))
      (when (.isDirectory (io/file dir "node_modules"))
        (let [{:keys [exit out err]} (shell/with-sh-dir (str dir)
                                       (shell/sh "npm" "run" "test:ui"))
              report (str/trim (str out \newline err))]
          (is (zero? exit) (str "npm run test:ui failed:\n" report))
          ;; A suite that compiled and ran but asserted nothing exits 0 too.
          (is (re-find #"Ran \d+ tests containing \d+ assertions" report)
              (str "test:ui did not report a run:\n" report)))))))

(deftest the-builder-ui-compiles
  ;; `test:ui` compiles only `test-ui/**` and what it requires, so a form that
  ;; ClojureScript rejects in `app.cljs` (2026-09-07: a `defonce` carrying a
  ;; docstring) leaves the clj suite green while `run-studio.sh` cannot produce
  ;; `resources/public/js/main.js`. The browser build itself is the seam; compile
  ;; it here so a broken screen fails `clojure -M:test`, not the next start.
  ;; `release`, not `compile`: both write `resources/public/js`, and a dev build
  ;; left there would be what the next start serves.
  (let [dir (studio-dir)]
    (when (and dir (.isDirectory (io/file dir "node_modules")))
      (let [{:keys [exit out err]} (shell/with-sh-dir (str dir)
                                     (shell/sh "npx" "shadow-cljs" "--force-spawn" "release" "builder"))
            report (str/trim (str out \newline err))]
        (is (zero? exit) (str "shadow-cljs release builder failed:\n" report))
        (is (re-find #"Build completed" report)
            (str "the builder build did not report completion:\n" report))))))
