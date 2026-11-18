(ns open.atlas.common.artifact.store-test
  "落盘边界（D4.8）与 server 的最小制品装载形态（B3 交付）。

   这是本模块唯一碰文件系统的 ns——装载语义在 reader-test，与文件系统的
   脾气分开可测。"
  {:clj-kondo/ignore [:discouraged-namespace]}   ; module-internal, see .clj-kondo/config.edn
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            ;; The one impl require in this ns, and only for D4.12: the refusal
            ;; branch fires on a cross-filesystem move, which a unit test cannot
            ;; arrange for real, so `atomic-move!` is rebound to raise it.
            [open.atlas.common.artifact.impl.store :as impl-store]
            [open.atlas.common.dialect :as dialect])
  (:import [java.nio.file AtomicMoveNotSupportedException Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def ^:private model
  (:model (dialect/read-dialect (slurp (io/resource "graph.edn")))))

(def ^:private prov-base
  {:cut-at "2026-08-22T00:00:00Z" :cut-by "test"})

(defn- sealed
  ([coords] (sealed coords model []))
  ([coords m refs]
   (let [{:keys [content-edn content-hash findings]} (artifact/encode-content m refs)]
     (assert (empty? findings) (pr-str findings))
     (artifact/seal-graph content-edn content-hash (merge prov-base coords)
                          {} {} {:coverage 1.0}))))

(defn- tmp-root ^java.io.File []
  (.toFile (Files/createTempDirectory "atlas-b3-shelf-" (into-array FileAttribute []))))

(defn- rm-rf [^java.io.File f]
  (doseq [^java.io.File x (reverse (file-seq f))] (.delete x)))

(defmacro with-root [[sym] & body]
  `(let [~sym (tmp-root)]
     (try ~@body (finally (rm-rf ~sym)))))

(defn- codes [findings] (set (map :code findings)))

(def ^:private coords {:workspace "core-team" :graph "b1" :version 3})

;; --- 布局与往返（D4.2）------------------------------------------------------

(deftest one-version-one-directory-three-files
  (with-root [root]
    (let [s (sealed coords)
          w (artifact/write-artifact! root coords (:files s))]
      (is (empty? (:findings w)))
      (is (= #{"content.edn" "manifest.edn" "report.edn"}
             (set (.list (io/file root "core-team" "b1" "3")))))
      (testing "落盘的字节与算过 digest 的字节逐字相同"
        (doseq [[fname text] (:files s)]
          (is (= text (slurp (io/file root "core-team" "b1" "3" fname)
                             :encoding "UTF-8"))))))))

(deftest a-written-artifact-loads-back-into-the-same-model
  (testing "server 的最小制品装载形态：能装载并交出模型（B3 交付）"
    (with-root [root]
      (artifact/write-artifact! root coords (:files (sealed coords)))
      (let [r (artifact/load-artifact root coords {:digest :unverified})]
        (is (empty? (filter #(= :error (:severity %)) (:findings r))))
        (is (= model (:model r)))))))

(deftest loading-verifies-the-digest-from-the-control-envelope
  (with-root [root]
    (let [s (sealed coords)]
      (artifact/write-artifact! root coords (:files s))
      (is (= model (:model (artifact/load-artifact
                            root coords {:expected-digest (:artifact-digest s)}))))
      (is (nil? (:model (artifact/load-artifact
                         root coords {:expected-digest (artifact/content-hash-of-text "x")})))))))

(deftest paths-resolve-against-the-root-not-the-process-cwd
  (testing "同一制品在不同机器不同目录下装载，行为一致（D4.8）——
            用两个不同的根装同一份制品，模型与 hash 逐字相同"
    (with-root [a]
      (with-root [b]
        (let [s (sealed coords)]
          (artifact/write-artifact! a coords (:files s))
          (artifact/write-artifact! b coords (:files s))
          (is (= (:model (artifact/load-artifact a coords {:digest :unverified}))
                 (:model (artifact/load-artifact b coords {:digest :unverified}))))
          (is (= (:content-hash (artifact/load-artifact a coords {:digest :unverified}))
                 (:content-hash (artifact/load-artifact b coords {:digest :unverified}))
                 (artifact/content-hash-of-text (get (:files s) "content.edn")))))))))

;; --- 部分写入不可被装载（D4.8）----------------------------------------------

(deftest a-partially-written-version-directory-does-not-load
  (testing "手工造出「只有 content.edn」的版本目录——原子写要防的正是它"
    (with-root [root]
      (let [s (sealed coords)
            dir (io/file root "core-team" "b1" "3")]
        (.mkdirs dir)
        (spit (io/file dir "content.edn") (get (:files s) "content.edn"))
        (let [r (artifact/load-artifact root coords {:digest :unverified})]
          (is (nil? (:model r)))
          (is (contains? (codes (:findings r)) :artifact.integrity/missing-file)))))))

(deftest the-write-is-atomic-no-intermediate-state-is-a-loadable-version
  (testing "写入过程中留下的是临时目录而不是半份版本目录：
            写完之后 graph 目录下只有一个版本目录，没有残留"
    (with-root [root]
      (artifact/write-artifact! root coords (:files (sealed coords)))
      (is (= ["3"] (vec (sort (.list (io/file root "core-team" "b1")))))
          "没有 .staging-* 残留，也没有第二个半成品目录"))))

(deftest a-failed-write-leaves-no-loadable-version
  (testing "写入抛错时不得留下可装载的版本目录"
    (with-root [root]
      (let [s (sealed coords)
            broken (assoc (:files s) "report.edn" nil)]
        (is (thrown? Throwable (artifact/write-artifact! root coords broken)))
        (is (nil? (:model (artifact/load-artifact root coords {:digest :unverified}))))))))

;; --- 已存在的版本是拒绝而不是合并（D4.8/D4.4）--------------------------------

(deftest an-existing-version-directory-is-refused-not-merged-into
  (testing "版本不可变：一次「合并」等于让同一个 (workspace, graph, version)
            在两个时刻指向两份内容，而全平台的引用同一性都建立在它不会发生之上"
    (with-root [root]
      (let [s1 (sealed coords)
            s2 (sealed coords {:namespaces {"other" {:kind :master :label "Other"}}} [])]
        (is (empty? (:findings (artifact/write-artifact! root coords (:files s1)))))
        (let [again (artifact/write-artifact! root coords (:files s2))]
          (is (contains? (codes (:findings again)) :artifact.store/version-exists))
          (is (= (get (:files s1) "content.edn")
                 (slurp (io/file root "core-team" "b1" "3" "content.edn")
                        :encoding "UTF-8"))
              "原有内容一字未动"))))))

;; --- 路径安全（D4.8）--------------------------------------------------------

(deftest path-traversal-is-refused
  (with-root [root]
    (doseq [c [{:workspace ".." :graph "b1" :version 1}
               {:workspace "core-team" :graph "../../etc" :version 1}
               {:workspace "core-team/../.." :graph "b1" :version 1}
               {:workspace "core-team" :graph "b1/nested" :version 1}]]
      (is (contains? (codes (:findings (artifact/write-artifact!
                                        root c (:files (sealed coords)))))
                     :artifact.store/path-escape)
          (str "路径穿越必须被拒绝：" (pr-str c)))
      (is (nil? (:model (artifact/load-artifact root c {:digest :unverified})))))))

(deftest illegal-names-are-refused-not-sanitised
  (with-root [root]
    (doseq [c [{:workspace "" :graph "b1" :version 1}
               {:workspace "core team" :graph "b1" :version 1}
               {:workspace ".hidden" :graph "b1" :version 1}
               {:workspace "核心" :graph "b1" :version 1}
               {:workspace "core-team" :graph "b1" :version "latest"}
               {:workspace "core-team" :graph "b1" :version 0}]]
      (is (contains? (codes (:findings (artifact/write-artifact!
                                        root c (:files (sealed coords)))))
                     :artifact.store/path-escape)
          (str "非法名称必须被拒绝而不是清洗：" (pr-str c))))))

(deftest an-email-named-workspace-is-a-legal-segment
  (testing "owner 裁定 2026-09-05：白名单加入 `@`（artifact-v1 §8）——白名单挡的是
            路径穿越，`@` 穿不出去，而 D3.22 之后个人 workspace 以邮箱命名"
    (with-root [root]
      (let [c {:workspace "jie.gao@tdsynnex.com" :graph "b1" :version 1}
            r (artifact/write-artifact! root c (:files (sealed coords)))]
        (is (empty? (:findings r)))
        (is (.isFile (io/file root "jie.gao@tdsynnex.com" "b1" "1" "content.edn"))))
      (testing "而穿越与分隔符仍然被拒"
        (doseq [c [{:workspace ".." :graph "b1" :version 1}
                   {:workspace "a/b" :graph "b1" :version 1}]]
          (is (contains? (codes (:findings (artifact/write-artifact!
                                            root c (:files (sealed coords)))))
                         :artifact.store/path-escape)
              (pr-str c)))))))

(deftest a-symlink-that-escapes-the-root-is-refused
  (with-root [outside]
    (with-root [root]
      (let [link (.toPath (io/file root "escapee"))]
        (Files/createSymbolicLink link (.toPath outside) (into-array FileAttribute []))
        (let [c {:workspace "escapee" :graph "b1" :version 1}
              r (artifact/write-artifact! root c (:files (sealed coords)))]
          (is (contains? (codes (:findings r)) :artifact.store/path-escape)
              "符号链接逃逸必须被拒绝"))))))

(deftest a-case-colliding-sibling-is-refused
  (testing "在大小写不敏感的文件系统上它们是同一个目录——拒绝而不是让两个
            限定名悄悄合并成一个"
    (with-root [root]
      (artifact/write-artifact! root {:workspace "Core-Team" :graph "b1" :version 1}
                                (:files (sealed coords)))
      (let [r (artifact/write-artifact! root {:workspace "core-team" :graph "b1" :version 1}
                                        (:files (sealed coords)))]
        ;; 大小写不敏感的 FS 上第一次写就占住了目录，第二次落到 version-exists；
        ;; 大小写敏感的 FS 上落到 case 冲突。两条都是拒绝，都不产生第二个版本。
        (is (seq (:findings r)))
        (is (contains? #{:artifact.store/path-escape :artifact.store/version-exists}
                       (:code (first (:findings r)))))))))

;; --- 引用闭包默认在同一个制品架上解析（D4.1）--------------------------------

(deftest the-load-closure-resolves-against-the-same-shelf
  (testing "「装载闭包」不是一种新的文件格式，只是「装这张图需要哪几个目录」"
    (with-root [root]
      (let [up-coords {:workspace "core-team" :graph "core" :version 3}
            up-model  {:namespaces {"core.upstream" {:kind :master :label "Upstream"}}}
            up        (artifact/encode-content up-model [])
            ref       {:workspace "core-team" :graph "core" :version 3
                       :content-hash (:content-hash up)}
            downstream (sealed coords model [ref])]
        (artifact/write-artifact! root up-coords (:files (sealed up-coords up-model [])))
        (artifact/write-artifact! root coords (:files downstream))
        (testing "闭包齐全 → 装载成功"
          (is (= model (:model (artifact/load-artifact root coords {:digest :unverified})))))
        (testing "上游被人动过 → 引用 hash 对不上，拒绝装载"
          (spit (io/file root "core-team" "core" "3" "content.edn")
                (:content-edn (artifact/encode-content
                               {:namespaces {"core.upstream" {:kind :master
                                                              :label "Changed"}}} [])))
          (let [r (artifact/load-artifact root coords {:digest :unverified})]
            (is (nil? (:model r)))
            (is (contains? (codes (:findings r))
                           :artifact.reference/content-hash-mismatch))))))))

(deftest a-reference-not-on-the-shelf-refuses-the-load
  (with-root [root]
    (let [ref {:workspace "core-team" :graph "core" :version 3
               :content-hash (:content-hash (artifact/encode-content {} []))}]
      (artifact/write-artifact! root coords (:files (sealed coords model [ref])))
      (let [r (artifact/load-artifact root coords {:digest :unverified})]
        (is (nil? (:model r)))
        (is (contains? (codes (:findings r)) :artifact.reference/missing))))))

(deftest an-absent-version-is-a-structured-rejection
  (with-root [root]
    (let [r (artifact/load-artifact root {:workspace "nope" :graph "nope" :version 1}
                                     {:digest :unverified})]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/missing-file)))))

;; --- D4.12: atomic move unavailable → refuse, never downgrade ----------------

(deftest an-unavailable-atomic-move-is-refused-never-downgraded
  (testing "D4.12: when staging and the shelf are on different volumes, **refuse**
            rather than fall back to a plain move.

            Asserting only \"it produced that code\" is not enough: an
            implementation that reports the move as unavailable *and* copies the
            files across anyway would satisfy it — and that is precisely the
            shape D4.12 exists to prevent, a machine that quietly has no
            atomicity any more with nobody the wiser. So this also asserts that
            **the landing directory does not exist at all**, that **loading it is
            a structured rejection**, and that **no staging leftovers remain**.

            (Checked by mutation: making the branch fall back to a non-atomic
            move turns the last three red while the code assertion stays green.)"
    (with-root [root]
      (let [{:keys [files]} (sealed coords)
            r (with-redefs [impl-store/atomic-move!
                            (fn [_ _] (throw (AtomicMoveNotSupportedException.
                                              "staging" "shelf" "test-injected")))]
                (artifact/write-artifact! root coords files))
            dir (io/file root "core-team" "b1" "3")]
        (is (= #{:artifact.store/atomic-move-unavailable} (codes (:findings r))))
        (is (nil? (:dir r)) "error and product are mutually exclusive (finding-v1 §2)")
        (is (not (.exists dir))
            "**not one byte landed** — a fallback to a plain move would leave a
             complete version directory sitting right here")
        (is (seq (:findings (artifact/load-artifact root coords {:digest :unverified})))
            "loading it is a structured rejection: the shelf has no such version")
        (is (empty? (filter #(str/starts-with? (.getName ^java.io.File %) ".staging-")
                            (or (seq (.listFiles (io/file root "core-team" "b1"))) [])))
            "the staging directory is gone — a refusal must not leave half-written
             debris behind")))))
