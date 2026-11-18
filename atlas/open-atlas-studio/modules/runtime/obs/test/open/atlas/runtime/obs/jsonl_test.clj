(ns open.atlas.runtime.obs.jsonl-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.runtime.obs.impl.jsonl :as jsonl]))

(defn- temp-path []
  (str (.toString (java.nio.file.Files/createTempDirectory
                   "jsonl-test" (make-array java.nio.file.attribute.FileAttribute 0)))
       "/out.jsonl"))

(deftest append-creates-and-appends
  (let [p (temp-path)]
    (jsonl/append-line! p (* 10 1024 1024) "{\"a\":1}")
    (jsonl/append-line! p (* 10 1024 1024) "{\"a\":2}")
    (is (= ["{\"a\":1}" "{\"a\":2}"] (str/split-lines (slurp p))))))

(deftest rolls-when-over-max
  (let [p (temp-path)]
    (jsonl/append-line! p 5 "first-line-well-over-5-bytes")  ; file now > 5 bytes
    (jsonl/append-line! p 5 "second")
    (is (.exists (io/file (str p ".1"))) "rolled file exists")
    (is (= "second" (str/trim (slurp p))))))

(deftest keeps-n-generations-not-just-one
  ;; a single always-overwritten .1 loses all but the last roll of history; the
  ;; ring must retain `default-generations` (3) prior files, dropping only the oldest.
  (let [p (temp-path)]
    (doseq [s ["one" "two" "three" "four" "five"]]
      (jsonl/append-line! p 1 s))                 ; max-bytes 1 → every append rolls
    (is (= "five" (str/trim (slurp p))) "live file is the newest line")
    (is (.exists (io/file (str p ".1"))))
    (is (.exists (io/file (str p ".2"))))
    (is (.exists (io/file (str p ".3"))))
    (is (not (.exists (io/file (str p ".4")))) "oldest generation dropped, ring bounded")
    (testing "newest-first ordering across the generation ring"
      (is (= "four"  (str/trim (slurp (io/file (str p ".1"))))))
      (is (= "three" (str/trim (slurp (io/file (str p ".2"))))))
      (is (= "two"   (str/trim (slurp (io/file (str p ".3")))))))))

(deftest custom-generation-count-is-honored
  (let [p (temp-path)]
    (doseq [s ["a" "b" "c" "d"]]
      (jsonl/append-line! p 1 s 1))               ; keep only ONE rolled generation
    (is (= "d" (str/trim (slurp p))))
    (is (.exists (io/file (str p ".1"))))
    (is (not (.exists (io/file (str p ".2")))) "generations=1 keeps a single rolled file")))

(deftest fail-open-on-bad-path
  ;; a path under a file (not a dir) — must not throw, and must SIGNAL the failure
  ;; (false) so an audit-path caller can surface the otherwise-silent loss.
  (is (false? (jsonl/append-line! "/dev/null/nope/x.jsonl" 1024 "x"))))

(deftest returns-true-on-success
  (let [p (temp-path)]
    (is (true? (jsonl/append-line! p (* 10 1024 1024) "{\"a\":1}")))))
