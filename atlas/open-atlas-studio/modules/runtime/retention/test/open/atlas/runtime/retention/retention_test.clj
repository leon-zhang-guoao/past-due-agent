(ns open.atlas.runtime.retention.retention-test
  "R3.5 的守门测试。四样保留各测一遍，外加一条**最要紧的**：

   少注入一样输入不会安静地少保留一点，而是**拒绝启动**——因为「多报了可以删」
   这种错误，只有在东西已经删掉之后才看得见。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.retention :as ret]))

(def ^:private now "2026-08-25T10:00:00Z")

(defn- store
  "一台假 instance：架上四版，a/root@2 引用 b/dep@1。其余按需覆盖。"
  [& {:keys [shelf refs serving pinned held]
      :or {shelf   #{{:graph "a/root" :version 1} {:graph "a/root" :version 2}
                     {:graph "b/dep" :version 1}  {:graph "b/dep" :version 2}}
           refs    {["a/root" 2] [{:graph "b/dep" :version 1}]}
           serving {}
           pinned  #{}
           held    #{}}}]
  (:store (ret/open! {:shelf-versions     (fn [] shelf)
                      :references-of      (fn [g v] (get refs [g v] []))
                      :serving-version    (fn [g] (get serving g))
                      :pinned-coordinates (fn [_] pinned)
                      :env-held           (fn [] held)})))

(deftest a-missing-input-refuses-to-start
  (testing "少一样输入 → 多报「可以删」，而那种错误只有在东西没了之后才看得见"
    (doseq [k [:shelf-versions :references-of :serving-version
               :pinned-coordinates :env-held]]
      (let [caps (dissoc {:shelf-versions (fn []) :references-of (fn [_ _])
                          :serving-version (fn [_]) :pinned-coordinates (fn [_])
                          :env-held (fn [])}
                         k)]
        (is (= [:retention.capabilities/missing]
               (mapv :code (:findings (ret/open! caps))))
            (str "缺 " k))))
    (is (some? (store)))))

(deftest an-unreferenced-unserved-unpinned-version-is-reclaimable
  (let [s (store)]
    (is (= #{{:graph "a/root" :version 1} {:graph "a/root" :version 2}
             {:graph "b/dep" :version 2}}
           (ret/reclaimable s now)))
    (is (= #{{:graph "b/dep" :version 1}} (ret/retained s now))
        "只有被 a/root@2 引用的那一版被保留着")))

(deftest a-retained-artifact-holds-what-it-references-even-if-nothing-is-deployed
  (testing "R3.5 的「**保留中**而不是**已部署**」那一句：一份已切未部署的制品
            同样在引用清单里钉着别人；只按已部署计数会把它引用的版本回收掉,
            那份制品从此装不起来也部署不了——而它本该是可复现的审计对象"
    (let [s (store :serving {})]
      (is (contains? (ret/retained s now) {:graph "b/dep" :version 1}))
      (is (= #{:referenced} (ret/explain s now {:graph "b/dep" :version 1}))))))

(deftest the-serving-pointer-holds-its-version
  (let [s (store :serving {"a/root" 2})]
    (is (contains? (ret/retained s now) {:graph "a/root" :version 2}))
    (is (= #{:served} (ret/explain s now {:graph "a/root" :version 2})))
    (is (not (contains? (ret/retained s now) {:graph "a/root" :version 1}))
        "指针只保留它指着的那一版")))

(deftest an-active-pinned-grant-holds-its-version
  (let [s (store :pinned #{{:graph "a/root" :version 1}})]
    (is (= #{:pinned} (ret/explain s now {:graph "a/root" :version 1})))
    (testing "grant 摘掉/吊销/过期之后**下一次推导**自然不再保留——推导，不累加"
      (is (empty? (ret/explain (store :pinned #{}) now {:graph "a/root" :version 1}))))))

(deftest a-materialised-env-holds-its-version
  (testing "缓存里握着的那一版不能在它脚下被删掉"
    (let [s (store :held #{["a/root" 1]})]
      (is (= #{:materialised} (ret/explain s now {:graph "a/root" :version 1}))))))

(deftest holders-say-who-is-holding-not-just-that-something-is
  (testing "运维要答的下一个问题永远是「被谁保留着」——只回答「不能删」会让人
            去翻代码才知道该动哪一头"
    (let [s (store :serving {"b/dep" 1} :pinned #{{:graph "b/dep" :version 1}}
                   :held #{["b/dep" 1]})]
      (is (= #{:referenced :served :pinned :materialised}
             (ret/explain s now {:graph "b/dep" :version 1}))
          "四样同时保留着它，四样都说出来")
      (is (empty? (ret/explain s now {:graph "a/root" :version 1}))))))

(deftest a-version-not-on-the-shelf-is-never-reported-as-reclaimable
  (testing "`reclaimable` 是架上的减去被保留的——凭空多出一个坐标会让调用方
            去删一个不存在的东西，而那种「成功」掩盖的是判据算错了"
    (let [s (store :shelf #{{:graph "a/root" :version 1}})]
      (is (= #{{:graph "a/root" :version 1}} (ret/reclaimable s now)))
      (is (not (contains? (ret/reclaimable s now) {:graph "b/dep" :version 1}))))))
