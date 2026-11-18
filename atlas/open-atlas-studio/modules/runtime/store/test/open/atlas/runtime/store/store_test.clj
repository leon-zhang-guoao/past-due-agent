(ns open.atlas.runtime.store.store-test
  "共享底座的守门测试：**真目录、真租约、真残留**。

   这些断言此前散在 `runtime/graph` 的 store 测试里，随租约一起搬过来——
   它们要证的是 instance 级的保证，而不是 graph 子系统的。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.store :as rs])
  (:import [java.io File]))

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "rt-store-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defmacro with-instance [[sym root-sym] & body]
  `(let [~root-sym (tmp-root)
         opened#   (rs/open! {:instance-id "test-instance" :root ~root-sym})
         ~sym      (:instance opened#)]
     (assert (some? ~sym) (pr-str (:findings opened#)))
     (try ~@body (finally (rs/close! ~sym) (rm-r ~root-sym)))))

;; ---------------------------------------------------------------------------
;; 租约：每台 instance 一个写进程
;; ---------------------------------------------------------------------------

(deftest a-second-writer-cannot-take-the-lease
  (testing "取不到就**拒绝启动**——不降级成「大家小心别同时写」"
    (with-instance [i root]
      (is (rs/lease-held? i))
      (let [second* (rs/open! {:instance-id "test-instance" :root root})]
        (is (nil? (:instance second*)))
        (is (= [:instance.lease/unavailable] (mapv :code (:findings second*))))
        (is (every? rs/finding? (:findings second*)))))))

(deftest releasing-the-lease-lets-the-next-process-in
  (let [root (tmp-root)]
    (try
      (let [a (rs/open! {:instance-id "i" :root root})]
        (is (some? (:instance a)))
        (rs/close! (:instance a)))
      ;; 进程退出/崩溃后 OS 释放——这正是不用普通 lock 文件的理由（那种会留下
      ;; stale 状态，要人去判断「这个锁还算数吗」）。
      (let [b (rs/open! {:instance-id "i" :root root})]
        (is (some? (:instance b)) "上一个释放之后，下一个取得到")
        (rs/close! (:instance b)))
      (finally (rm-r root)))))

(deftest the-lease-is-one-per-instance-not-one-per-subsystem
  (testing "③-1 的整条理由：同一个 JVM 里各开一次 `control.lock`，第二次
            **不会**像跨进程那样干净地被拒——它要么抛 OverlappingFileLock、
            要么让调用方以为自己独立持有一把。所以租约只开一次、传给每个子系统"
    (with-instance [i root]
      (let [again (rs/open! {:instance-id "test-instance" :root root})]
        (is (nil? (:instance again))
            "同进程内第二次开也拿不到——但靠的是异常被兜住，不是文件锁的进程语义")
        (is (rs/lease-held? i) "第一把仍然有效，没有被第二次尝试弄坏")))))

;; ---------------------------------------------------------------------------
;; 写闸：租约 + poisoned，全 instance 一份
;; ---------------------------------------------------------------------------

(deftest writes-stop-when-the-lease-is-gone
  (let [root (tmp-root)
        i    (:instance (rs/open! {:instance-id "i" :root root}))]
    (try
      (is (empty? (rs/writable-findings i)))
      (rs/close! i)
      (is (= [:instance.lease/lost] (mapv :code (rs/writable-findings i))))
      (finally (rm-r root)))))

(deftest a-poisoned-instance-refuses-every-subsystem-not-just-the-one-that-failed
  (testing "poisoned 是**整台 instance** 的：token 台账一边照写、graph 一边
            已知半应用，是事后谁也讲不清的状态"
    (with-instance [i _]
      (is (empty? (rs/writable-findings i)))
      (rs/poison! i {:effect :set-pointer :message "disk full"})
      (is (= [:instance.lease/lost] (mapv :code (rs/writable-findings i))))
      (is (= {:effect :set-pointer :message "disk full"} (rs/poisoned i)))
      (rs/clear-poison! i)
      (is (empty? (rs/writable-findings i))))))

;; ---------------------------------------------------------------------------
;; 原子落盘
;; ---------------------------------------------------------------------------

(deftest an-edn-write-lands-atomically-and-leaves-no-temp
  (with-instance [i root]
    (let [f (File. (rs/subsystem-dir i "tokens") "row.edn")]
      (rs/write-edn! f {:b 2 :a 1})
      (is (= {:a 1 :b 2} (rs/read-edn f)))
      (is (= "{:a 1 :b 2}\n" (slurp f))
          "落盘形是规范 EDN：键有序、同内容任何时候逐字节相同（写入时给的是 {:b 2 :a 1}）")
      (is (empty? (filter #(str/starts-with? (.getName ^File %) ".tmp-")
                          (file-seq (io/file root))))
          "temp 不留"))))

(deftest a-directory-write-is-atomic-and-idempotent
  (with-instance [i _]
    (let [d (File. (rs/subsystem-dir i "staging") "abc")]
      (rs/write-dir! d {"a.txt" "A" "b.txt" "B"})
      (is (= "A" (slurp (File. d "a.txt"))))
      (testing "已存在即幂等成功——内容寻址，同 digest 同内容"
        (rs/write-dir! d {"a.txt" "DIFFERENT"})
        (is (= "A" (slurp (File. d "a.txt"))) "不覆盖")))))

(deftest reading-a-corrupted-row-is-absence-not-an-exception
  (testing "每个调用方本来就要处理「不存在」，让它再处理一次异常是白加一条路径"
    (with-instance [i _]
      (let [f (File. (rs/subsystem-dir i "tokens") "bad.edn")]
        (io/make-parents f)
        (spit f "{:unbalanced")
        (is (nil? (rs/read-edn f)))))
    (is (nil? (rs/read-edn (io/file "/nonexistent/nope.edn"))))))

(deftest temps-from-a-crash-are-swept
  (with-instance [i root]
    (let [t (File. (io/file root) ".tmp-leftover")]
      (io/make-parents (File. t "x"))
      (spit (File. t "x") "debris")
      (is (= 1 (rs/sweep-temps! root)))
      (is (not (.exists t))))))

;; ---------------------------------------------------------------------------
;; 段名白名单：拒绝而不是净化
;; ---------------------------------------------------------------------------

(deftest an-illegal-path-segment-is-refused-not-sanitised
  (testing "净化出来的名字是同一个东西的第二个身份，而 D3.10 说限定名**就是**身份"
    (doseq [bad ["../escape" "a/b" "" ".hidden" nil]]
      (let [t (try (rs/safe-segment! bad "workspace") ::no-throw (catch Throwable x x))]
        (is (not= ::no-throw t) (pr-str bad))
        (is (rs/finding? (rs/finding-of t)) (pr-str bad))
        (is (= :instance.store/illegal-segment (:code (rs/finding-of t))))))
    (is (= "sales" (rs/safe-segment! "sales" "workspace"))))
  (testing "owner 裁定 2026-09-05：`@` 进白名单——邮箱名的个人 workspace（D3.22）
            必须是合法段，而 `..` 与分隔符仍然被拒"
    (is (= "jie.gao@tdsynnex.com" (rs/safe-segment! "jie.gao@tdsynnex.com" "workspace")))
    (doseq [bad [".." "a/b"]]
      (is (thrown? Throwable (rs/safe-segment! bad "workspace")) (pr-str bad)))))

;; ---------------------------------------------------------------------------
;; 子系统锁：键带命名空间
;; ---------------------------------------------------------------------------

(deftest subsystem-lock-keys-do-not-collide
  (testing "graph 与 token 共用一张锁注册表。裸字符串作键的话，`\"tok-1\"` 与一张
            碰巧同名的 graph 会互相串行——一个查不出来的性能问题，而不是一个错误"
    (with-instance [i _]
      (is (identical? (rs/monitor i [:graph "sales/app"]) (rs/monitor i [:graph "sales/app"])))
      (is (not (identical? (rs/monitor i [:graph "x"]) (rs/monitor i [:token "x"])))))))

(deftest with-lock-serialises-on-one-key
  (with-instance [i _]
    (let [hits (atom [])]
      (rs/with-lock i [:token "t"] (swap! hits conj :in))
      (is (= [:in] @hits)))))

(deftest a-subsystem-dir-is-under-the-instance-root
  (with-instance [i root]
    (is (= (.getCanonicalPath (io/file root "tokens"))
           (.getCanonicalPath (rs/subsystem-dir i "tokens"))))
    (is (= "test-instance" (rs/instance-id i)))))
