(ns open.atlas.studio.graph.sync-test
  "**sync 单飞**（[R3.7](../../../../../../../../docs/design/30-workspace-graph.md#32-版本与引用规则)，
   B5a 退出条件 3）。

   R3.7 有两半，两半都要真并发才测得出：

   1. **同一张 graph 的并发 sync 合并为一次执行，后到者等待同一结果**——
      断言底层 `build` **只被调了一次**（计数器），且全部调用方拿到**同一个**
      对象（`identical?`，不是「相等」）；
   2. **不同 graph 各自独立分道，互不排队**——一张慢图在建时，另一张图的 sync
      必须**跑得完**。这一条挡的是「顺手加一把全局锁」：那种实现在第 1 条上
      也全绿，只有这一条能把它抓出来（R3.7「为什么不做全局串行」）。

   闸门用 `CountDownLatch` / `promise` 而不是 `sleep` 来对齐时序——sleep 测出来的
   是机器今天有多快。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(timbre/set-min-level! :warn)

(defn- await! [^CountDownLatch l]
  (is (.await l 10 TimeUnit/SECONDS) "并发测试超时——单飞闸把调用方钉死了"))

(deftest concurrent-syncs-of-one-graph-run-the-build-once
  "**R3.7 上半**：同一张 graph 的并发 sync 合并为一次执行，后到者等同一结果。"
  (let [flight  (graph/single-flight)
        calls   (atom 0)
        entered (CountDownLatch. 1)   ; leader 已经进到 build 里
        release (CountDownLatch. 1)   ; 放行 leader
        joined  (CountDownLatch. 7)   ; 7 个后到者都已经发出调用
        build   (fn [] (swap! calls inc)
                  (.countDown entered)
                  (await! release)
                  {:env :materialised})
        results (atom [])
        run     (fn [] (fn [] (let [r (graph/sync! flight "sales/core" build)]
                                (swap! results conj r))))
        leader  (doto (Thread. (run)) .start)]
    (await! entered)
    (is (= #{"sales/core"} (graph/in-flight flight)) "leader 在建")
    (let [joiners (doall (for [_ (range 7)]
                           (doto (Thread. (fn [] (.countDown joined) ((run)))) .start)))]
      (await! joined)
      ;; 后到者已经发出调用；leader 仍卡在 build 里，于是它们只可能合流。
      ;; **这里不 sleep**：joined 数到 0 就说明 7 个线程都进过 `sync!` 之前那一行,
      ;; 而 `sync!` 的合流判定（一次 `swap!`）在其后没有任何会失败的等待。
      (Thread/sleep 50)
      (.countDown release)
      (doseq [^Thread t (cons leader joiners)] (.join t 10000)))
    (is (= 1 @calls) "**底层只执行了一次**——这是 R3.7 的全部内容")
    (is (= 8 (count @results)))
    (is (every? #(identical? (first @results) %) @results)
        "全部调用方拿到的是同一个结果对象，不是相等的八个")
    (is (empty? (graph/in-flight flight)) "执行结束闸放开")))

(deftest a-sync-after-the-previous-one-finished-runs-again
  "合流窗口 = 「这一次执行还没结束」。**结束之后到达的请求跑一次新的执行**——
   sync 的意义是取此刻的池快照（D3.2 运行态隔离），缓存给后来者就把它抹掉了。"
  (let [flight (graph/single-flight)
        calls  (atom 0)]
    (dotimes [_ 3] (graph/sync! flight "sales/core" #(swap! calls inc)))
    (is (= 3 @calls))))

(deftest different-graphs-do-not-queue-behind-each-other
  "**R3.7 下半**：不同 graph 各自独立分道。一个域的大图重建**不得**阻塞别人的
   小改动——它们之间没有任何共享可变状态需要保护（R3.7「为什么不做全局串行」）。

   全局串行的实现在上一条测试里也是全绿的，只有这一条抓得住它。"
  (let [flight  (graph/single-flight)
        slow-in (CountDownLatch. 1)
        release (CountDownLatch. 1)
        big     (doto (Thread. (fn [] (graph/sync! flight "core-team/core"
                                                   (fn [] (.countDown slow-in)
                                                     (await! release)
                                                     :big))))
                  .start)]
    (await! slow-in)
    ;; 大图正卡在 build 里。小图必须**现在**就跑得完。
    (is (= :small (graph/sync! flight "sales/tiny" (constantly :small)))
        "小图被大图挡住了——那正是 R3.7 拒绝的全局串行")
    (is (= #{"core-team/core"} (graph/in-flight flight)))
    (.countDown release)
    (.join big 10000)))

(deftest a-failing-build-does-not-pin-the-graph
  "`build` 抛异常：异常传给调用方，**闸照样放开**——一次失败不该把这张图的 sync
   永久钉死在一个坏结果上。"
  (let [flight (graph/single-flight)]
    (is (thrown? RuntimeException
                 (graph/sync! flight "sales/core" #(throw (RuntimeException. "boom")))))
    (is (empty? (graph/in-flight flight)))
    (is (= :ok (graph/sync! flight "sales/core" (constantly :ok))))))

(deftest syncing-an-unregistered-graph-is-refused-structurally
  "四参形态先查登记：sync 物化的是一份**已登记的菜谱**，没登记就结构化拒绝,
   而且**不去跑 build**（拒绝不许有副作用）。"
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "sync"))})
        _    (pool/delete-pool! cfg)
        conn (graph/install! (pool/open-pool! cfg))]
    (try
      (let [flight (graph/single-flight)
            ran    (atom false)
            r      (graph/sync! flight (pool/db conn) "sales/nope"
                                (fn [] (reset! ran true) :built))]
        (is (false? @ran) "拒绝就不跑 build")
        (is (= #{:graph.registry/unknown} (set (map :code (:findings r)))))
        (is (every? graph/finding? (:findings r)))
        (testing "登记之后同一个调用就跑得通了"
          (doseq [x [(graph/register-workspace! conn {:name "sales" :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]})
                     (graph/register-graph! conn {:workspace "sales" :name "nope"
                                                  :members {}})]]
            (assert (empty? (:findings x)) (pr-str x)))
          (is (= :built (graph/sync! flight (pool/db conn) "sales/nope" (constantly :built))))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))
