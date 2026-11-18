(ns open.atlas.studio.pool.relocation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [open.atlas.studio.pool.impl.store :as store]
            [taoensso.timbre :as timbre]))

(deftest repeated-file-store-relocation
  (timbre/set-min-level! :warn)
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "atlas-relocation" (make-array java.nio.file.attribute.FileAttribute 0)))
        dirs (mapv #(io/file root %) ["original" "first-copy" "second-copy"])
        cfg #(assoc-in (store/config {:backend :file :path (.getPath %)})
                       [:store :scope] (str "host-" (.getName %)))
        open-check! (fn [dir]
                      (let [conn (store/create! (cfg dir))]
                        (try
                          (is (= "preserved" (d/q '[:find ?v . :where [_ :relocation/value ?v]] @conn)))
                          (finally (store/release! conn)))))]
    (try
      (let [conn (store/create! (cfg (first dirs)))]
        (d/transact conn [{:db/ident :relocation/value :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}])
        (d/transact conn [{:relocation/value "preserved"}])
        (store/release! conn))
      (doseq [[from to] (partition 2 1 dirs)]
        (doseq [f (file-seq from)]
          (let [target (io/file to (str (.relativize (.toPath from) (.toPath f))))]
            (if (.isDirectory f) (.mkdirs target) (io/copy f target))))
        (open-check! to)
        (open-check! to))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/create! (assoc (cfg (last dirs)) :keep-history? true))))
      (finally
        (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))
