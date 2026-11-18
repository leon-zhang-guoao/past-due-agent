(ns open.atlas.runtime.graph.store-test
  "store 的守门测试：**真目录、真租约、真残留**。

   命令那一侧一个目录都不铺（判定是纯的）；这一侧反过来——它要证的正是「字节
   真的原子落下去了」「崩在中间留下的东西不被当作成功」。

   **租约不在这里测**：它属于整台 instance，测试在 `runtime/store`（③-1）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.runtime.graph :as control]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as rs]
            [open.atlas.runtime.store :as rs])
  (:import [java.io File]))

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "b5c1-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-r (.listFiles f)))
    (.delete f)))

(defmacro with-store
  "先开 **instance**（租约在那一层，③-1），再拿本子系统的句柄。"
  [[sym root-sym] & body]
  `(let [~root-sym (tmp-root)
         opened#   (rs/open! {:instance-id "test-instance" :root ~root-sym})
         inst#     (:instance opened#)]
     (assert (some? inst#) (pr-str (:findings opened#)))
     (let [~sym (control/open! inst#)]
       (try ~@body
            (finally (rs/close! inst#) (rm-r ~root-sym))))))

(defn- slurp-edn [& path]
  (let [f (apply io/file path)] (when (.isFile f) (edn/read-string (slurp f)))))

;; 租约的测试在 `runtime/store`：那把锁属于整台 instance，不属于本子系统（③-1）。

;; ---------------------------------------------------------------------------
;; 落盘：路径编码与原子性
;; ---------------------------------------------------------------------------

(deftest external-ids-never-become-raw-path-segments
  (testing "request-id / deployment-id 只被要求非空，可以含 `/` 或 `..`"
    (with-store [s root]
      (let [evil "../../../etc/passwd"]
        (control/apply-effects!
         s [{:effect/kind :write-request :request-id evil
             :record {:op :offer :result {:missing []}}}])
        (is (.isFile (io/file root "requests" (str (control/path-segment evil) ".edn")))
            "落在 hash 过的段名下")
        (is (= #{"control.lock" "requests"} (set (.list (io/file root))))
            "root 底下只多出 requests/——没有任何东西沿着 `..` 逃出去")
        (is (= evil (:request-id (slurp-edn root "requests"
                                            (str (control/path-segment evil) ".edn"))))
            "**原值存在文件内容里**——路径不可读，记录仍然可查")))))

(deftest a-graph-name-that-is-not-a-legal-segment-is-refused
  (testing "workspace/graph 照 D3.10 是人要 grep 的东西，所以原样用——
            但非法段名是**拒绝**，不是净化"
    (with-store [s root]
      (let [out (control/apply-effects!
                 s [{:effect/kind :set-pointer :graph "../evil/g" :pointer {:version 1}}])]
        (is (seq (:findings out)))
        (is (= [:instance.store/illegal-segment] (mapv :code (:findings out))))))))

(deftest staging-is-content-addressed-and-idempotent
  (with-store [s root]
    (let [hex   (apply str (repeat 64 "a"))
          files {"content.edn" "{}" "manifest.edn" "{}" "report.edn" "{}"}
          e     {:effect/kind :stage-artifact :digest-hex hex :digest (str "sha256:" hex)
                 :files files :evidence {:request-id "r1"}}]
      (is (empty? (:findings (control/apply-effects! s [e]))))
      (is (.isFile (io/file root "staging" hex "content.edn")))
      (testing "evidence 落在三文件**之外**——三文件的 digest 是身份，往里塞就改了身份"
        (is (.isFile (io/file root "staging" hex "stage-receipt.edn")))
        (is (= 4 (count (.list (io/file root "staging" hex))))))
      (testing "重传同一 digest 是幂等的"
        (is (empty? (:findings (control/apply-effects! s [e]))))))))

;; ---------------------------------------------------------------------------
;; 写失败即停写
;; ---------------------------------------------------------------------------

(deftest a-failed-effect-poisons-the-process
  (testing "带着半应用状态继续服务，比停下来更坏"
    (with-store [s root]
      (is (seq (:findings (control/apply-effects!
                           s [{:effect/kind :set-pointer :graph "../bad/g"
                               :pointer {:version 1}}]))))
      (testing "此后一切写直接拒绝，直到恢复跑过"
        (let [out (control/apply-effects!
                   s [{:effect/kind :write-request :request-id "r" :record {}}])]
          (is (seq (:findings out)))))
      (testing "恢复之后解毒"
        (control/recover! s)
        (is (empty? (:findings (control/apply-effects!
                                s [{:effect/kind :write-request :request-id "r"
                                    :record {:op :offer}}]))))))))

;; ---------------------------------------------------------------------------
;; 启动恢复
;; ---------------------------------------------------------------------------

(deftest recovery-sweeps-temp-debris
  (with-store [s root]
    (io/make-parents (io/file root "requests" ".tmp-x.edn"))
    (spit (io/file root "requests" ".tmp-x.edn") "{}")
    (is (pos? (:swept (control/recover! s))))
    (is (not (.exists (io/file root "requests" ".tmp-x.edn"))))))

(deftest stop-serving-clears-only-the-current-pointer
  (with-store [s _root]
    (control/apply-effects!
     s [{:effect/kind :set-pointer :graph "sales/app"
         :pointer {:version 8 :head-event-id nil}}])
    (is (= 8 (control/serving-version s "sales/app")))
    (is (seq (:findings (control/stop-serving! s "sales/app"
                                                {:expected-current 7
                                                 :stopped-at "2026-09-11T00:00:00Z"
                                                 :stopped-by {:issuer "test" :subject "owner"}}))))
    (is (= 8 (control/serving-version s "sales/app")) "a stale request changes nothing")
    (is (= {:graph "sales/app" :from 8 :serving nil :findings []}
           (control/stop-serving! s "sales/app"
                                  {:expected-current 8
                                   :stopped-at "2026-09-11T00:00:00Z"
                                   :stopped-by {:issuer "test" :subject "owner"}})))
    (is (nil? (control/serving-version s "sales/app")))))

(deftest recovery-backfills-the-idempotency-index-from-pointer-reachable-events
  (testing "事件已写、指针已切、`write-request` 前崩溃、重启后别的事件又推进了
            head——原请求重放时既没有 request 记录、也不再是当前 head，于是收到
            一次**假 CAS 冲突**。回填就是补这个缺口。"
    (with-store [s root]
      (let [ev1 {:event/id "req-1" :request-id "req-1" :op :activate
                 :envelope-hash "sha256:aa" :result {:from nil :to 7}
                 :previous-event-id nil}
            ev2 {:event/id "req-2" :request-id "req-2" :op :activate
                 :envelope-hash "sha256:bb" :result {:from 7 :to 8}
                 :previous-event-id "req-1"}]
        (control/apply-effects!
         s [{:effect/kind :append-event :graph "sales/app" :event-id "req-1" :event ev1}
            {:effect/kind :append-event :graph "sales/app" :event-id "req-2" :event ev2}
            {:effect/kind :set-pointer :graph "sales/app"
             :pointer {:version 8 :head-event-id "req-2"}}])
        (let [r (control/recover! s)]
          (is (= 2 (:backfilled r)) "链上两条都回填")
          (is (empty? (:quarantined r)))
          (let [back (slurp-edn root "requests" (str (control/path-segment "req-1") ".edn"))]
            (is (= {:from nil :to 7} (:result back))
                "回填的是事件里那份**原始结果**——重放才拿得回同一个答案")
            (is (= "req-1" (:backfilled-from-event back)))))))))

(deftest an-orphan-event-is-not-backfilled
  (testing "**只回填 pointer 可达的事件**：孤儿事件是未提交残留，回填它等于把
            没提交的东西认成提交过的"
    (with-store [s root]
      (control/apply-effects!
       s [{:effect/kind :append-event :graph "sales/app" :event-id "orphan"
           :event {:event/id "orphan" :request-id "orphan" :result {:from 1 :to 2}}}])
      (let [r (control/recover! s)]
        (is (zero? (:backfilled r)))
        (is (not (.isFile (io/file root "requests"
                                   (str (control/path-segment "orphan") ".edn")))))))))

(deftest a-pointer-whose-chain-does-not-close-quarantines-the-graph
  (with-store [s root]
    (control/apply-effects!
     s [{:effect/kind :set-pointer :graph "sales/app"
         :pointer {:version 8 :head-event-id "gone"}}])
    (let [r (control/recover! s)]
      (is (= ["sales/app"] (:quarantined r)))
      (is (= [:deploy.pointer/broken-chain] (mapv :code (:findings r)))))
    (testing "隔离期间对它的写直接拒绝——「现在指着的这一版怎么来的」答不出来"
      (let [out (control/handle! s {} {:control/op :activate
                                       :control/payload {:graph "sales/app"}} {})]
        (is (= [:deploy.pointer/broken-chain] (mapv :code (:findings out))))))
    (testing "拒绝也进审计流水（8.10 P6 裁定 a：引用而不复制）"
      ;; 权威记录仍是本模块的链式 event；这一条只是让「这个人这一天做了什么」
      ;; 有一个地方能问。S5.3：只记成功的流水回答不了「谁在敲哪扇门」。
      (let [filed (obs/audit-for-subject (rs/subsystem-dir (control/instance s) "obs")
                                         :graph "sales/app")]
        (is (seq filed) "被拒绝的 activate 留下了记录")
        (is (= :graph/control (:event (first filed))))
        (is (= :denied (:outcome (first filed))))
        (is (= :deploy.pointer/broken-chain (:reason (first filed))))))))

(deftest artifacts-without-receipts-are-reported-not-repaired
  (testing "它们本来就不算已装载（定义就是目录 + 匹配的 receipt），所以放着是
            安全的——**只是要被看见**。自动修复需要 plan 与重算 digest，那是
            `:load` 的活"
    (with-store [s root]
      (doseq [n ["content.edn" "manifest.edn" "report.edn"]]
        (io/make-parents (io/file root "artifacts" "sales" "app" "7" n))
        (spit (io/file root "artifacts" "sales" "app" "7" n) "{}"))
      (let [r (control/recover! s)]
        (is (= [{:workspace "sales" :graph "app" :version 7}] (:orphan-artifacts r)))
        (is (= [:warning] (mapv :severity (:findings r)))
            "是提醒不是错误：它没有伤害任何东西，只是没人看见就会烂掉")))))
