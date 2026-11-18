(ns open.atlas.runtime.rowsec.rowsec-test
  "S5.7 门禁的守门测试。要钉住的是 D5.14 那句改判（owner 2026-09-03）：

   > 门禁判据由「有 `:row-security` 声明即禁止」改为**「声明且接线核对通过即
   > 放行；核对不过则拒绝切版本」**。

   所以这里的每一条都成对：接线核对过的图**放行**，没核对过的**照样拦**。
   只测其中一半的门禁测不出翻向——旧的测试全是后一半，全绿的同时判据可以是
   「一律拒绝」。

   豁免那一节整节删除：`:rows-already-isolated` / `:not-multi-tenant`、它们绑的五元组、
   以及整套台账 API 随 D5.14 退役。留下的是一条「确实删干净了」的用例。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.rowsec :as rowsec]
            [open.atlas.runtime.store :as rs]))

(defn- tmp []
  (str (java.nio.file.Files/createTempDirectory
        "rt-rowsec-" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)] (when (.isDirectory f) (run! rm-r (.listFiles f))) (.delete f)))

(defn- member
  "闭包成员的门禁事实。`:row-security-verified` 缺省为空——**这正是 E1 之前
   切出来的制品的样子**（`artifact/health-facts` 把缺席读成空清单），所以
   默认的 member 一旦有声明就该被拦。"
  [& {:as over}]
  (merge {:graph "a/root" :version 1 :content-hash "sha256:c1"
          :finding-set-hash "sha256:f1"
          :row-security-declared [] :row-security-verified []}
         over))

(defn- wired
  "接线核对通过的成员：声明的每一条都在 `:row-security-verified` 里。"
  [ids & {:as over}]
  (merge (member :row-security-declared ids :row-security-verified ids) over))

(defmacro with-gate
  "`facts` 是 `{[graph version] [成员…]}`；查不到就是闭包取不到。"
  [[sym inst-sym & {:keys [facts instance-id]}] & body]
  `(let [root# (tmp)
         ~inst-sym (:instance (rs/open! {:instance-id (or ~instance-id "instance-a")
                                         :root root#}))
         opened# (rowsec/open! ~inst-sym
                               {:closure-facts (fn [g# v#] (get ~facts [g# v#]))})
         ~sym (:store opened#)]
     (assert (some? ~sym) (pr-str (:findings opened#)))
     (try ~@body (finally (rs/close! ~inst-sym) (rm-r root#)))))

(defn- codes [fs] (into (sorted-set) (map :code) fs))

;; ---------------------------------------------------------------------------
;; 判据：声明且核对通过即放行（D5.14）
;; ---------------------------------------------------------------------------

(deftest a-clean-closure-passes
  (with-gate [g i :facts {["a/root" 1] [(member)]}]
    (is (empty? (rowsec/check g "a/root" 1)))))

(deftest a-verified-declaration-passes
  (testing "**门禁翻向的那一半**（D5.14）：声明在场、接线核对通过 → 放行。
            E1 之前这张图是拦死的，而它的 resolver 一直是执法的"
    (with-gate [g i :facts {["a/root" 1] [(wired ["resolver:orders_by_tenant"])]}]
      (is (empty? (rowsec/check g "a/root" 1))))))

(deftest an-unverified-declaration-is-blocked
  (testing "声明在场、接线核对**没**通过 → 拦。失败方向一个字没变"
    (with-gate [g i :facts {["a/root" 1]
                            [(member :row-security-declared ["resolver:orders_by_tenant"])]}]
      (let [fs (rowsec/check g "a/root" 1)]
        (is (= #{:rowsec.gate/blocked} (codes fs)))
        (is (every? rowsec/finding? fs))
        (is (= ["resolver:orders_by_tenant"] (:resolvers (:got (first fs))))
            "点名是**未核对的那几条**——拿到「这张图不行」修不了任何东西")))))

(deftest a-partially-verified-closure-names-only-the-unverified
  (testing "差集而不是布尔：一张图上两条声明、一条过一条没过时，拒绝里出现的
            必须只有没过的那条"
    (with-gate [g i :facts {["a/root" 1]
                            [(member :row-security-declared ["resolver:a" "resolver:b"]
                                     :row-security-verified ["resolver:a"])]}]
      (let [fs (rowsec/check g "a/root" 1)]
        (is (= #{:rowsec.gate/blocked} (codes fs)))
        (is (= ["resolver:b"] (:resolvers (:got (first fs)))))))))

(deftest a-pre-e1-artifact-is-blocked-not-grandfathered
  (testing "E1 之前切出的制品不带 `:row-security-verified`，`health-facts` 把缺席
            读成空清单 → 每一条声明都算未核对，照样拦。**关闭方向**：那些声明
            确实没有被任何人核对过，重切一次版本就好"
    (with-gate [g i :facts {["a/root" 1]
                            [(dissoc (member :row-security-declared ["resolver:r"])
                                     :row-security-verified)]}]
      (is (= #{:rowsec.gate/blocked} (codes (rowsec/check g "a/root" 1)))))))

(deftest the-gate-checks-the-whole-closure-not-just-the-root
  (testing "S5.7：被引用图里那个声明了 `:row-security` 的 resolver，经合并同样
            进这份 env 的 index（R3.2）。只看根等于漏掉一半入口"
    (with-gate [g i :facts {["a/root" 1] [(wired ["resolver:root_ok"])
                                          (member :graph "b/dep" :version 3
                                                  :content-hash "sha256:c2"
                                                  :row-security-declared ["resolver:dep_rows"])]}]
      (let [fs (rowsec/check g "a/root" 1)]
        (is (= #{:rowsec.gate/blocked} (codes fs)))
        (is (= "b/dep" (:graph (:got (first fs)))) "被拦的是被引用的那张图")))))

(deftest a-closure-that-cannot-be-enumerated-is-refused-not-passed
  (testing "一个答不出「闭包里有什么」的门禁，放行等于在不知情的前提下承担风险"
    (with-gate [g i :facts {}]
      (is (= #{:rowsec.gate/unknown-closure} (codes (rowsec/check g "a/root" 1)))))))

(deftest a-gate-without-closure-facts-refuses-to-start
  (let [root (tmp)
        i    (:instance (rs/open! {:instance-id "i" :root root}))]
    (try
      (is (= [:rowsec.capabilities/missing] (mapv :code (:findings (rowsec/open! i {})))))
      (finally (rs/close! i) (rm-r root)))))

;; ---------------------------------------------------------------------------
;; 豁免：已退役（D5.14）
;; ---------------------------------------------------------------------------

(deftest the-exemption-mechanism-is-gone-not-stubbed
  (testing "`reasons` / `exempt!` / `exemption` / `exemptions` / `exemption-key` 与
            `:rowsec.exemption/*` 整个域随 D5.14 退役。留一个会拒绝的桩，下一个人
            还得先读它才知道它不做事——而这条路已经没有「不做事」以外的语义了：
            接线核对不过的图切不出版本，切得出的图不需要豁免。

            **已落盘的豁免记录不删**（审计证据），只是不再有任何一处读它。"
    (doseq [n '[reasons exempt! exemption exemptions exemption-key]]
      (is (nil? (ns-resolve 'open.atlas.runtime.rowsec n)) (str n " 应当已删除")))
    (is (not-any? #(= "rowsec.exemption" (namespace %)) rowsec/finding-codes))))
