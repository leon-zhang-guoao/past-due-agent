(ns open.atlas.studio.pool.speculative-diff-test
  "**投机校验做差集**（B5a.3；`modules/studio/graph/README.md` 裁定 ⑤）。

   B4.1 的投机校验把整池拼回方言交 B0，**任何一处已有的脏都会让无关的写被拒**,
   而报出来的是「你的模型不合法」。裁定 ⑤ 的修法是差集：写前写后各判一次，
   只有这次写引入的新 error 才拒绝；已有的脏当 `:warning` 随行报出。

   这份测试的两条主断言，逐字对着裁定 ⑤ 的第 3、4 步：

   1. **已经脏的池里，一次完全无关的合法写必须通过**，且响应里带着那条已有的脏
      作为 `:warning`；
   2. **真的会引入新错的写必须拒绝**，且 finding 里**只有新引入的那条**。

   装料是 demo 模型（与 `pool_test` 同一份，读 `fixtures/demo/model.edn`），
   再往里塞一条**引用了未登记属性**的 resolver 把池弄脏——那正是「导入带进来的、
   规则改判之后的存量」在现实里的样子。"
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool.pool-test :as pt]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:private ghost
  "池里**没有登记**的属性。它出现在 resolver 的 `:output` 里就违反 D2.2 的存在性
   闭包——B0 会报，而这条脏**不是**下面任何一次写造成的。"
  :cis.ghost/nope)

(def ^:private dirty-resolver
  {:pool/type :resolver
   :pool/id   "already_dirty"
   :pool/revision 1
   ;; D3.22: the dirt belongs to a workspace like everything else — speculative
   ;; validation is scoped to the writing workspace, so dirt nobody owns is
   ;; dirt nobody's write is judged against.
   :pool/workspace "demo"
   :pool/entry {:id "already_dirty"
                :input [:cis.master.vendor/vend_no]
                :output [ghost]
                :result_type "single"
                :source {:type "sql" :query "SELECT 1"}}})

(def ^:private live-attr
  "demo 模型里真实存在的属性——下面那次「完全无关的合法写」打的就是它。"
  :cis.master.vendor/vend_name)

(def ^:dynamic *conn* nil)
(def ^:dynamic *cfg* nil)

(defn with-dirty-pool
  "demo 模型 + **一条已经脏的 resolver**。

   脏是直接写进 Datahike 的，**绕过 action 与 `import-pool!`**：那两条路都有投机
   校验守着，进不去。绕过是有意的——现实里的脏本来就不是从写面进来的，它是
   **规则改判之后的存量**（B0 今天判 D2.2 的存在性闭包，昨天不判）与手工修复留下的
   东西。要测「已经脏的池」，就得能造出一个已经脏的池。"
  [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "specdiff"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)
          objs (pool/dialect->objects @pt/demo-model)
          r    (pool/import-pool!
                conn {:pool/version 1
                      :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") objs)})]
      (assert (empty? (:findings r)) (pr-str (:findings r)))
      (d/transact conn [(#'open.atlas.studio.pool.impl.schema/object->tx
                         dirty-resolver 1)])
      (binding [*conn* conn *cfg* cfg]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-dirty-pool)

(defn- warnings [res] (filterv #(= :warning (:severity %)) (:findings res)))
(defn- errors   [res] (filterv #(= :error   (:severity %)) (:findings res)))

(deftest the-pool-really-is-dirty
  "前提自证：不先证明这个池真的脏，下面两条测试证的就是别的东西。"
  (let [{:keys [findings]} (pool/execute-action
                            *conn* {:workspace "demo" :action :update :type :attribute
                                    :id live-attr
                                    :entry {:description "touch"}
                                    :expected_revision 1})]
    (is (seq (filter #(= :pool.dialect/pre-existing (:code %)) findings))
        "池里本来就有 dialect error，否则这份 fixture 没在测差集")))

(deftest unrelated-write-passes-and-carries-the-existing-dirt-as-warning
  "**裁定 ⑤ 第 4 步**：一次与那条脏 resolver 完全无关的合法写 → **必须通过**,
   且响应里带着那条已有的脏作为 `:warning`。

   B4.1 的行为是：这次写被拒，理由是「你的模型不合法」，而位置指向别人的对象。"
  (let [res (pool/execute-action
             *conn* {:workspace "demo" :action :update :type :attribute
                     :id live-attr
                     :entry {:description "A completely unrelated edit."}
                     :expected_revision 1})]
    (is (= 2 (:revision res)) "无关的合法写必须写进去")
    (is (empty? (errors res)) (pr-str (errors res)))
    (let [ws (warnings res)]
      (is (seq ws) "已有的脏必须随行报出，不许静默吞掉")
      (is (every? pool/finding? ws) "warning 也过 finding-v1 §2")
      (is (= 1 (count (filter #(= :pool.dialect/pre-existing (:code %)) ws)))
          "一条摘要行")
      (is (str/includes? (pr-str ws) (str ghost))
          "随行的明细点名那条已有的脏在哪"))
    (is (= "A completely unrelated edit."
           (:description (:pool/entry (pool/object (pool/db *conn*) :attribute live-attr)))))))

(deftest a-write-that-really-introduces-an-error-is-still-rejected
  "**裁定 ⑤ 第 3 步**：一次**真的引入新错**的写 → 必须拒绝，且 finding 里
   **只有新引入的那条**——已有的那条脏不许混进拒绝理由里。"
  (let [res (pool/execute-action
             *conn* {:workspace "demo" :action :create :type :resolver :id "brand_new_broken"
                     :entry {:id "brand_new_broken"
                             :input [:cis.master.vendor/vend_no]
                             :output [:cis.phantom/absent]
                             :result_type "single"
                             :source {:type "sql" :query "SELECT 2"}}
                     :expected_revision 0})]
    (is (nil? (:revision res)) "被拒的 action 一个字节都没写")
    (is (nil? (pool/object (pool/db *conn*) :resolver "brand_new_broken")))
    (let [fs (:findings res)
          detail (remove #(= :pool.dialect/rejected (:code %)) fs)]
      (is (every? pool/finding? fs))
      (is (= 1 (count (filter #(= :pool.dialect/rejected (:code %)) fs)))
          "一条摘要行")
      (is (seq detail))
      (is (str/includes? (pr-str detail) "cis.phantom/absent")
          "拒绝点名的是这次写引入的那个属性")
      (is (not (str/includes? (pr-str detail) (str ghost)))
          "**已有的脏不许出现在拒绝里**——那正是裁定 ⑤ 要消灭的误伤"))))

(deftest the-same-existing-error-is-recognised-as-the-same-one-across-two-runs
  "差集的键（`[:code :path :message]`，理由见 `impl.action/finding-key`）
   必须能把**同一条已有 error** 在两次运行里认成同一条——否则每次写都会把整池
   已有的脏当成「你引入的」，差集等于没做。"
  (let [db     (pool/db *conn*)
        errs   (#'open.atlas.studio.pool.impl.action/dialect-errors db)
        again  (#'open.atlas.studio.pool.impl.action/dialect-errors db)
        keyfn  #'open.atlas.studio.pool.impl.action/finding-key
        difff  #'open.atlas.studio.pool.impl.action/diff-findings]
    (is (seq errs))
    (is (= (map keyfn errs) (map keyfn again))
        "同一快照两次运行，每条 error 的键逐字相同")
    (is (empty? (difff errs again))
        "两次运行之间没有一条被认成「新引入的」")
    (testing "多重集语义：同一个键出现两次时，多出来的那一条仍算新引入"
      (let [one (first errs)]
        (is (= 1 (count (difff [one] [one one]))))
        (is (empty? (difff [one one] [one])))))))
