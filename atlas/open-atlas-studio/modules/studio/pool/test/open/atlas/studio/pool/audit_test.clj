(ns open.atlas.studio.pool.audit-test
  "**变更审计**（8.4 P1b，`impl/audit.clj`）。

   守的是四件事，各对应 audit 的一条裁定：

   1. **伪造身份寻址不到**（裁定 ①）——出处是另一个参数，写进 action 里得到的是
      键面拒绝，不是「被忽略」也不是「被过滤」；
   2. **没有出处的写照样留记录**（裁定 ②）——记 `:unattributed`，因为
      「这次改动没有出处」与「这次改动不存在」在台账里必须分得开；
   3. **审计不进模型**（裁定 ③）——`as-dialect` 一个字节不变，三条查询互不相见;
   4. **审计与对象同一次事务**——被拒的写不留审计，成功的写必留审计，没有第三种。

   装料同 `pool-test`：demo 模型。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool.pool-test :as pt]))

(use-fixtures :each pt/with-loaded-pool)

(defn- db [] (pool/db pt/*conn*))

(defn- an-attribute []
  (->> (pool/objects (db))
       (filter #(= :attribute (:pool/type %)))
       (sort-by #(pr-str (:pool/id %)))
       first))

(defn- mutations-for [id]
  (filter #(= [:attribute id] (:mutation/object %)) (pool/mutations (db))))

;; ---------------------------------------------------------------------------
;; 裁定 ①：伪造身份寻址不到
;; ---------------------------------------------------------------------------

(deftest forging-an-identity-is-unaddressable-not-filtered
  "**这条是整段 P1b 的承重规则。** 一个能自称 `:entrypoint :ui` 的 agent 会让
   台账说谎，而说谎的台账比没有台账更糟：它会被当成证据读。

   安全不能活在 handler 开头的一句 if 里——那是一行代码，谁都能挪走；只能是
   **参数递不进来**。这里证的就是「递不进来」。"
  (let [a (an-attribute)]
    (doseq [k [:entrypoint :token-id :actor]]
      (let [r (pool/execute-action
               pt/*conn* (assoc {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                 :entry {:label "改"}
                                 :expected_revision (:pool/revision a)}
                                k :forged))]
        (is (= #{:pool.action/malformed} (set (map :code (:findings r))))
            (str k " 写进 action 里必须是键面拒绝"))
        (is (nil? (:revision r)) "而且一个字节都没写")))))

(deftest a-caller-may-not-name-the-absence
  "`:unattributed` 是**缺席的形状**，只有池自己写得出（裁定 ②）。调用方点名它，
   等于声称一件它无从知道的事——而那正是这套记法要防的。"
  (let [a (an-attribute)
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                :entry {:label "改"}
                                :expected_revision (:pool/revision a)}
                               {:entrypoint :unattributed})]
    (is (= #{:pool.provenance/malformed} (set (map :code (:findings r)))))
    (is (nil? (:revision r)))))

(deftest provenance-itself-is-checked
  (let [a (an-attribute)
        write (fn [prov] (pool/execute-action
                          pt/*conn* {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                     :entry {:label "改"}
                                     :expected_revision (:pool/revision a)}
                          prov))]
    (testing "键面封闭：一个拼错的键静默丢掉，就是一条永远不会被写下的出处"
      (is (= #{:pool.provenance/malformed}
             (set (map :code (:findings (write {:entrypoint :mcp :tokenid "t"})))))))
    (testing "入口必须在词表里"
      (is (= #{:pool.provenance/malformed}
             (set (map :code (:findings (write {:entrypoint :smtp})))))))
    (testing "token-id 空串不是「没给」"
      (is (= #{:pool.provenance/malformed}
             (set (map :code (:findings (write {:entrypoint :mcp :token-id ""})))))))
    (testing "拒绝一律 finding-v1，且不含配置值"
      (let [fs (:findings (write {:entrypoint :smtp}))]
        (is (every? pool/finding? fs))
        (is (every? #(= :error (:severity %)) fs))))))

;; ---------------------------------------------------------------------------
;; 裁定 ②：写下的是什么
;; ---------------------------------------------------------------------------

(deftest every-write-leaves-a-record-with-its-entrance
  (let [a (an-attribute)
        id (:pool/id a)
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id id
                                :entry {:label "经 MCP 改的"}
                                :expected_revision (:pool/revision a)}
                               {:entrypoint :mcp :token-id "wt-7"
                                :actor "agent@example" :at "2026-08-28T10:00:00Z"})]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (let [[m :as ms] (mutations-for id)]
      (is (= 1 (count ms)) "一次写一条记录")
      (is (= :mcp (:mutation/entrypoint m)))
      (is (= "wt-7" (:mutation/token-id m)) "签发人可由控制面台账按它查回")
      (is (= "agent@example" (:mutation/actor m)))
      (is (= "2026-08-28T10:00:00Z" (:mutation/at m)))
      (is (= :update (:mutation/action m)))
      (testing "前后 revision 都在——「改成什么」之外，审计还要答得出「从什么改的」"
        (is (= (:pool/revision a) (:mutation/revision-before m)))
        (is (= (:revision r) (:mutation/revision-after m)))))))

(deftest an-unattributed-write-is-recorded-not-skipped
  "不写记录会让「这次改动没有出处」与「这次改动不存在」在台账里**不可区分**,
   而台账被查阅的场合恰好就是有人在问这两者的区别（S5.4 那把量不出差别的尺子）。"
  (let [a (an-attribute)
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                :entry {:label "没说从哪来"}
                                :expected_revision (:pool/revision a)})]
    (is (empty? (:findings r)))
    (let [[m] (mutations-for (:pool/id a))]
      (is (= :unattributed (:mutation/entrypoint m)))
      (is (nil? (:mutation/token-id m)) "没有的东西不编一个出来"))))

(deftest create-and-delete-say-what-they-are
  (let [ns-id "test.audit"
        _ (pool/execute-action pt/*conn* {:workspace "demo" :action :create :type :namespace :id ns-id
                                          :entry {:label "审计装料"} :expected_revision 0}
                               {:entrypoint :ui :actor "someone@example"})
        created (first (filter #(= [:namespace ns-id] (:mutation/object %)) (pool/mutations (db))))]
    (testing ":create 的 before 是 0 —— 「还不存在」用 expected_revision 的同一套说法"
      (is (= :create (:mutation/action created)))
      (is (= 0 (:mutation/revision-before created)))
      (is (= 1 (:mutation/revision-after created))))
    (let [rev (:pool/revision (pool/object (db) :namespace ns-id))
          _ (pool/execute-action pt/*conn* {:workspace "demo" :action :delete :type :namespace :id ns-id
                                            :expected_revision rev}
                                 {:entrypoint :ui :actor "someone@example"})
          deleted (->> (pool/mutations (db))
                       (filter #(and (= [:namespace ns-id] (:mutation/object %))
                                     (= :delete (:mutation/action %))))
                       first)]
      (testing ":delete 没有 after —— 0 已经表示「不存在过」，被删的对象存在过"
        (is (some? deleted))
        (is (= rev (:mutation/revision-before deleted)))
        (is (nil? (:mutation/revision-after deleted))))
      (testing "对象没了，它的审计还在：不可变的记录撑得过被审计对象的死"
        (is (nil? (pool/object (db) :namespace ns-id)))))))

(deftest a-batch-records-every-action-in-order
  (let [before (count (pool/mutations (db)))
        r (pool/execute-actions
           pt/*conn*
           [{:workspace "demo" :action :create :type :namespace :id "test.batch" :entry {:label "批"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :test.batch/a
             :entry {:type :string :label "甲"} :expected_revision 0}]
           {:entrypoint :mcp :token-id "wt-9"})]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (let [ms (drop before (pool/mutations (db)))]
      (is (= 2 (count ms)) "一个 action 一条，不是一次运行一条")
      (is (every? #(= :mcp (:mutation/entrypoint %)) ms))
      (is (every? #(= "wt-9" (:mutation/token-id %)) ms)))))

;; ---------------------------------------------------------------------------
;; 裁定 ③ 与原子性
;; ---------------------------------------------------------------------------

(deftest the-audit-does-not-reach-the-model
  "混进模型的代价是具体的：同一份模型会因为「谁改的」不同而产出不同的
   content-hash，而制品的身份必须只由模型决定（D4.3）。"
  (let [a (an-attribute)
        before (pool/as-dialect (db))
        _ (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                :entry (:pool/entry a)
                                :expected_revision (:pool/revision a)}
                               {:entrypoint :mcp :token-id "wt-1"})
        after (pool/as-dialect (db))]
    (testing "一次「改成原样」的写：模型逐字节不变，审计却多了一条"
      (is (= before after))
      (is (seq (mutations-for (:pool/id a)))))
    (testing "整份方言里查不到 mutation / token-id 的任何痕迹"
      (let [s (binding [*print-namespace-maps* false] (pr-str after))]
        (is (not (str/includes? s "mutation")))
        (is (not (str/includes? s "wt-1")))))
    (testing "三条查询互不相见：审计不在对象里，也不在确认记录里"
      (is (not-any? #(= :mutation (:pool/type %)) (pool/objects (db))))
      (is (empty? (pool/confirmations (db)))))))

(deftest a-refused-write-leaves-no-record
  "审计与对象在**同一次** transact 里，所以没有哪种顺序能让一个落地而另一个没有。
   一条挂在从未发生的写上的审计，会让台账凭空多出一次改动。"
  (let [a (an-attribute)
        before (count (pool/mutations (db)))]
    (testing "revision 冲突"
      (pool/execute-action pt/*conn*
                           {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                            :entry {:label "改"} :expected_revision 9999}
                           {:entrypoint :mcp})
      (is (= before (count (pool/mutations (db))))))
    (testing "方言校验不过"
      (pool/execute-action pt/*conn*
                           {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                            :entry {:type :string :format :nope}
                            :expected_revision (:pool/revision a)}
                           {:entrypoint :mcp})
      (is (= before (count (pool/mutations (db))))))
    (testing "批量里有一条不合法 —— 整批不写，审计也整批不写"
      (pool/execute-actions
       pt/*conn*
       [{:workspace "demo" :action :create :type :namespace :id "test.ok" :entry {:label "行"}
         :expected_revision 0}
        {:workspace "demo" :action :create :type :namespace :id "test.ok" :entry {:label "撞车"}
         :expected_revision 0}]
       {:entrypoint :mcp})
      (is (= before (count (pool/mutations (db))))))))

;; ---------------------------------------------------------------------------
;; 导出往返
;; ---------------------------------------------------------------------------

(deftest the-audit-survives-a-rebuild
  "一条「重建一次就消失」的审计记录，在它唯一被需要的场合恰好是空的。
   退出条件 1 的「删掉 store、从最后一次导出建回来」必须把治理痕迹带回来。"
  (let [a (an-attribute)]
    (pool/execute-action pt/*conn*
                         {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                          :entry {:label "改过"}
                          :expected_revision (:pool/revision a)}
                         {:entrypoint :mcp :token-id "wt-3" :at "2026-08-28T11:00:00Z"})
    (let [exported (pool/export-pool (db))
          before (pool/mutations (db))
          cfg (pool/config {:backend :mem :id (str "audit-roundtrip-" (hash exported))})
          conn2 (pool/open-pool! cfg)]
      (try
        (let [r (pool/import-pool! conn2 (read-string exported))]
          (is (empty? (:findings r)) (pr-str (:findings r)))
          (testing "记录逐字段回来了"
            (is (= before (pool/mutations (pool/db conn2)))))
          (testing "再导出一次仍逐字节相同"
            (is (= exported (pool/export-pool (pool/db conn2))))))
        (finally (pool/close-pool! conn2) (pool/delete-pool! cfg))))))

(deftest the-confirmation-record-carries-the-same-identity
  "两张台账要能**连起来**回答「哪些改动来自 AI、经谁授权」，所以入口的词表是
   同一份，身份的字段名也是同一套。"
  (pt/stage-unsigned-named-query!)
  (let [idx (pool/candidate-index (pool/pool-candidates (db)))
        [_ ck] (->> (keys idx) (filter #(= :curated (first %))) (sort-by pr-str) first)
        r (pool/confirm-candidates! pt/*conn*
                                    {:workspace "demo" :kind :curated :candidate ck :decision :sign
                                     :expected_revision 0}
                                    {:entrypoint :mcp :token-id "wt-5"
                                     :actor "agent@example" :at "2026-08-28T12:00:00Z"})
        c (first (pool/confirmations (db)))]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (= :mcp (:confirmation/entrypoint c)))
    (is (= "wt-5" (:confirmation/token-id c)))
    (is (= "agent@example" (:confirmation/actor c)))
    (is (= "2026-08-28T12:00:00Z" (:confirmation/at c)))
    (testing "两张台账用同一份入口词表，不是两份各写一遍"
      (is (contains? pool/entrypoints (:confirmation/entrypoint c))))))

;; ---------------------------------------------------------------------------
;; E3：写面回传台账记录的 id
;;
;; 装配层要为每条台账记录发一条统一审计事件（`:pool/mutation`），两本账因此
;; 需要一个**共享的键**。没有它，装配层只能按时间去猜哪条事件对应哪条记录——
;; 而并发写下，猜错的那次没有任何迹象。
;; ---------------------------------------------------------------------------

(deftest a-write-hands-back-the-id-of-the-record-it-wrote
  (let [a (an-attribute)
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                :entry {:label "改"}
                                :expected_revision (:pool/revision a)}
                               {:entrypoint :mcp :token-id "wt-e3"})
        [m] (:mutations r)]
    (is (string? (:mutation-id r)))
    (is (= (:mutation-id r) (:mutation-id m)) "同一条记录，两处说法一致")
    (testing "回传的摘要与台账里那一行逐字段相同"
      (let [row (first (filter #(= (:mutation-id r) (:mutation/id %))
                               (pool/mutations (db))))]
        (is (some? row) "id 找得到那一行——这就是两本账的共享键")
        (is (= [:attribute (:pool/id a)] (:mutation/object row) (:object m)))
        (is (= :update (:mutation/action row) (:action m)))
        (is (= :mcp (:mutation/entrypoint row) (:entrypoint m)))
        (is (= (:pool/revision a) (:mutation/revision-before row) (:revision-before m)))
        (is (= (:revision r) (:mutation/revision-after row) (:revision-after m)))))))

(deftest a-delete-hands-back-a-summary-with-no-after
  (let [_ (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :create :type :namespace :id "test.e3del"
                                :entry {:label "删"} :expected_revision 0})
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :delete :type :namespace :id "test.e3del"
                                :expected_revision 1})
        [m] (:mutations r)]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (= :delete (:action m)))
    (is (not (contains? m :revision-after))
        "0 已经表示「不存在过」，被删的对象存在过——键缺席，不是 0")
    (is (= :unattributed (:entrypoint m)) "没有出处的写照样有记录（裁定 ②）")))

(deftest a-batch-hands-back-one-summary-per-touched-object
  (let [r (pool/execute-actions
           pt/*conn*
           [{:workspace "demo" :action :create :type :namespace :id "test.e3" :entry {:label "批"}
             :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :test.e3/a
             :entry {:type :string :label "甲"} :expected_revision 0}
            {:workspace "demo" :action :create :type :attribute :id :test.e3/b
             :entry {:type :string :label "乙"} :expected_revision 0}]
           {:entrypoint :ui})
        ms (:mutations r)]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (= 3 (count ms)) "触及 3 个对象 = 3 条记录，不是一次调用一条")
    (is (= 3 (count (distinct (map :mutation-id ms)))) "id 互不相同")
    (is (= [[:namespace "test.e3"] [:attribute :test.e3/a] [:attribute :test.e3/b]]
           (map :object ms))
        "顺序即 action 的顺序")
    (let [ids (set (map :mutation/id (pool/mutations (db))))]
      (is (every? ids (map :mutation-id ms)) "三条 id 在台账里都找得到"))))

(deftest a-refused-write-hands-back-no-summary
  (let [a (an-attribute)
        r (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id (:pool/id a)
                                :entry {:label "改"} :expected_revision 9999}
                               {:entrypoint :ui})]
    (is (nil? (:mutation-id r)))
    (is (empty? (:mutations r))
        "没写就没有记录可指——装配层据此不发事件，而不是发一条空的")))
