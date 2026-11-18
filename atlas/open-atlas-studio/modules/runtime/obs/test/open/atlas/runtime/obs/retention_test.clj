(ns open.atlas.runtime.obs.retention-test
  (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
            [open.atlas.runtime.obs.impl.retention :as r]
            [open.atlas.runtime.obs.impl.jsonl :as jsonl]))
(defn home [] (str (java.nio.file.Files/createTempDirectory "retention-" (make-array java.nio.file.attribute.FileAttribute 0))))
(deftest rotation-and-retained-reader
  (let [h (home) f (io/file h "logs/app.jsonl") now (System/currentTimeMillis)
        cfg {:days 30 :file-bytes 5 :total-bytes 100000}]
    (io/make-parents f)
    (swap! r/roots assoc h cfg)
    (try
      (jsonl/append-line! f 1 "first line")
      (jsonl/append-line! f 1 "second line")
      (is (= ["first line" "second line"] (r/reduce-lines f conj [])))
      (.setLastModified f (- now 86400000))
      (r/maintain! h cfg now)
      (is (zero? (.length f)))
      (is (= 3 (count (r/retained-files f))))
      (finally (swap! r/roots dissoc h)))))
(deftest retention-age-cap-and-business-isolation
  (let [h (home) now (System/currentTimeMillis) cfg {:days 30 :file-bytes 1000 :total-bytes 25}
        old (io/file h "logs/app.jsonl.older.gz") newer (io/file h "logs/app.jsonl.newer.gz")
        active (io/file h "logs/app.jsonl") business (io/file h "artifacts/keep.edn")
        index (io/file h "obs/audit-index/graph/a/old.edn")]
    (doseq [f [old newer active business index]] (io/make-parents f) (spit f "12345678901234567890"))
    (doseq [f [old index business]] (.setLastModified f (- now (* 31 86400000))))
    (r/maintain! h cfg now)
    (is (not (.exists old))) (is (not (.exists index)))
    (is (not (.exists newer)) "capacity removes newest eligible only after older files")
    (is (.exists active)) (is (.exists business))))
