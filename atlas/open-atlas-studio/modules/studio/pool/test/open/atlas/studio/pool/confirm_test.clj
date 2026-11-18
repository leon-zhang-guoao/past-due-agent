(ns open.atlas.studio.pool.confirm-test
  "**B4.4 候选签字通道**（§7.3「实施期待钉的四处」第 2 条）。

   守四件事，各对应 common/candidates README 的一条裁定：

   1. 池里的候选**从池挖**（裁定 ①）——与对同一份模型直接挖出来的一模一样；
   2. 签下去**产出可用对象**，拼回方言仍过 B0；
   3. 确认记录**不可变**、**不在 `as-dialect` 里**，且导出往返仍逐字节相同（裁定 ②）；
   4. 签字派生的绑定与作者直写的绑定**分得开**（裁定 ③）。

   装料同 `pool-test`：demo 模型（README 裁定 ⑥）。玩具数据证不出候选——
   三个属性的模型里没有跨 entity 的重名。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.candidates :as cc]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.pool.pool-test :as pt]))

(use-fixtures :each pt/with-loaded-pool)

(defn- db [] (pool/db pt/*conn*))

(defn- rejected!
  "拒绝的形状：一个字节都没写、结构化、不含配置值（B4 退出条件 2 / finding-v1 §5）。"
  [res]
  (is (nil? (:confirmation res)) "拒绝不得返回确认记录")
  (let [fs (:findings res)]
    (is (seq fs) "拒绝必须有 finding，不许静默失败")
    (is (every? pool/finding? fs))
    (is (some #(= :error (:severity %)) fs))
    (let [s (binding [*print-namespace-maps* false] (pr-str fs))]
      (doseq [v ["jdbc:sqlite" "cis-demo.db" "data/cis-demo"]]
        (is (not (str/includes? s v)) (str "finding 泄漏了配置值 " (pr-str v)))))
    (set (map :code fs))))

(defn- a-term-candidate
  "池里一条**跨 entity 重名**的词条候选（ID 留空，等 owner 命名）。"
  [idx]
  (->> (keys idx)
       (filter #(= :term (first %)))
       (filter #(= :name-frequency (:source (get idx %))))
       (sort-by pr-str)
       first))

;; ---------------------------------------------------------------------------
;; 裁定 ①：候选从池挖
;; ---------------------------------------------------------------------------

(deftest candidates-come-from-the-pool
  (pt/stage-unsigned-named-query!)
  (let [d     (db)
        cands (pool/pool-candidates d)]
    (testing "挖法只有一条：池的清单是提议器产出的**子集**，差额恰是 `:settled`
              （两条挖法分叉的那天，owner 会以为自己看的是全部，S5.4）"
      (let [raw (cc/candidates (pool/as-dialect d))]
        ;; 不受过滤影响的两张原样相等
        (is (= (:curated raw) (:curated cands)))
        ;; 受过滤的两张：留下的 + 已定的 = 提议器产出的全部，**一条不多一条不少**
        (doseq [[k id-of] [[:terms :local-name]]]
          (is (= (into #{} (map id-of) (get raw k))
                 (into (into #{} (map id-of) (get cands k))
                       (map id-of) (get-in cands [:settled k])))
              (str k "：留下的与已定的加起来必须正好是提议器给的那些"))
          (is (empty? (clojure.set/intersection
                       (into #{} (map id-of) (get cands k))
                       (into #{} (map id-of) (get-in cands [:settled k]))))
              (str k "：一条候选不能既在清单里又在已定里")))))

    (testing "被 withheld 的**逐条列出来**，不静默丢
              （少报一条候选与「本来就没有」在清单上长得一样）"
      (let [settled (get-in cands [:settled :terms])]
        (is (seq settled) "demo 模型里 23 个词条都已经绑好，池里必然有一批候选是既成事实了")
        (doseq [c settled]
          (is (= :every-binding-already-carries-a-term (:reason c)))
          (is (seq (:attributes c))))
        (is (not-any? (into #{} (map :local-name) settled)
                      (map :local-name (:terms cands)))))
      (testing "已定的那些，池里确实每一条绑定都带着 `:term` —— 理由是可证伪的"
        (let [attrs (:attributes (pool/as-dialect d))]
          (doseq [c (get-in cands [:settled :terms])
                  a (:attributes c)]
            (is (some? (:term (get attrs a))) (str a " 被判已定，但它没绑词条"))))))
    (testing "两张清单都喂得动，且形状对得上直接挖 demo 模型那份（`:sensitive` 那张随档位退役）"
      (is (seq (:terms cands)))
      (is (seq (:curated cands)))
      (is (every? #(nil? (:term %))
                  (filter #(= :name-frequency (:source %)) (:terms cands)))
          "D2.26：按名字频次聚类的候选，ID 一律留空")
      (is (every? :id-required-from-owner
                  (filter #(= :name-frequency (:source %)) (:terms cands)))))

    ;; 这里原有一条「与 B2 那份候选清单对得上：只多不少」的对账，随 B2 语料与
    ;; 转换器一并删去（2026-08-24，模型落成 fixtures/demo/model.edn）：那是**迁移期的守卫**,
    ;; 参照物没了就无从对起。它证的「签过的簇落进 `:settled` 那一半」由上面
    ;; 「挖法只有一条」与「被 withheld 的逐条列出来」两条覆盖，不是靠它。
    ))

;; ---------------------------------------------------------------------------
;; 裁定 ①/② ：签下去产出可用对象
;; ---------------------------------------------------------------------------

(deftest signing-a-term-candidate-produces-usable-objects
  (let [idx  (pool/candidate-index (pool/pool-candidates (db)))
        [_ ck :as key*] (a-term-candidate idx)
        cand (get idx key*)
        tid  "test.party/signed_concept"
        res  (pool/confirm-candidates!
              pt/*conn*
              {:workspace "demo" :kind :term :candidate ck :decision :sign
               :concept-id tid
               :definition {:label "签出来的概念" :description "由候选签字通道产生。"}
               :expected_revision 0}
              {:entrypoint :mcp})]
    (is (empty? (:findings res)) (pr-str (:findings res)))
    (let [d (db)]
      (testing "词条注册项真的在池里"
        (is (= {:label "签出来的概念" :description "由候选签字通道产生。"}
               (:pool/entry (pool/object d :term tid)))))
      (testing "每条绑定都写上了 `:term`，且本地的词条携带字段按 D2.26 摘掉了"
        (doseq [a (:attributes cand)]
          (let [e (:pool/entry (pool/object d :attribute a))]
            (is (= tid (:term e)))
            (is (empty? (select-keys e cc/term-carried-keys))))))
      (testing "拼回方言仍过 B0（裁定 ②：池不写第二个 schema）"
        (let [{:keys [model findings]}
              (dialect/read-dialect (binding [*print-namespace-maps* false]
                                      (pr-str (pool/as-dialect d))))]
          (is (some? model))
          (is (empty? (filter #(= :error (:severity %)) findings))))))))

(deftest declining-is-a-ruling-too
  (let [idx (pool/candidate-index (pool/pool-candidates (db)))
        [_ ck] (a-term-candidate idx)
        before (pool/as-dialect (db))
        res  (pool/confirm-candidates!
              pt/*conn* {:workspace "demo" :kind :term :candidate ck :decision :decline
                         :note "同名不同义" :expected_revision 0}
              {:entrypoint :ui})]
    (is (empty? (:findings res)))
    (testing "模型一个字节没动，记录照留 —— 否则「看过了、不签」与「从没看过」
              在池里完全不可区分（S5.4）"
      (is (= before (pool/as-dialect (db))))
      (let [c (first (pool/confirmations (db)))]
        (is (= :decline (:confirmation/decision c)))
        (is (= [] (:confirmation/produced c)))
        (is (= "同名不同义" (:confirmation/note c)))))))

;; ---------------------------------------------------------------------------
;; 裁定 ②：确认记录
;; ---------------------------------------------------------------------------

(defn- sign-one!
  "签一条 `:curated` 候选（最省事的一种：一次只动一个对象）。返回候选键。"
  [& {:keys [entrypoint token-id] :or {entrypoint :http}}]
  (pt/stage-unsigned-named-query!)
  (let [idx (pool/candidate-index (pool/pool-candidates (db)))
        [_ ck] (->> (keys idx) (filter #(= :curated (first %))) (sort-by pr-str) first)
        res (pool/confirm-candidates!
             pt/*conn* {:workspace "demo" :kind :curated :candidate ck :decision :sign
                        :expected_revision 0}
             (cond-> {:entrypoint entrypoint} token-id (assoc :token-id token-id)))]
    (assert (empty? (:findings res)) (pr-str (:findings res)))
    ck))

(deftest the-confirmation-record-carries-what-it-must
  (let [ck (sign-one! :entrypoint :http)
        c  (first (filter #(= [:curated ck] (:confirmation/key %)) (pool/confirmations (db))))]
    (is (some? c))
    (is (= :curated (:confirmation/kind c)))
    (is (= :sign (:confirmation/decision c)))
    (testing "**入口**：principles「存储与同步」点名要的那一项"
      (is (= :http (:confirmation/entrypoint c))))
    (testing "产出的对象 + **前后 revision**"
      (is (= 1 (count (:confirmation/produced c))))
      (let [p (first (:confirmation/produced c))]
        (is (= :resolver (first (:object p))))
        (is (= (inc (:revision-before p)) (:revision-after p)))
        (is (= (:revision-after p) (:pool/revision (apply pool/object (db) (:object p)))))))
    (testing "签下去的效果真的在池里：`:curated? true`（D2.13）"
      (is (true? (:curated? (:pool/entry (pool/object (db) :resolver ck))))))))

(deftest the-confirmation-record-is-not-in-the-dialect
  (pt/stage-unsigned-named-query!)
  (let [before (pool/as-dialect (db))
        _      (pool/confirm-candidates!
                pt/*conn* {:workspace "demo" :kind :curated
                           :candidate (second (->> (keys (pool/candidate-index (pool/pool-candidates (db))))
                                                   (filter #(= :curated (first %))) (sort-by pr-str) first))
                           :decision :decline :expected_revision 0}
                          {:entrypoint :ui})
        after  (pool/as-dialect (db))]
    (testing "`:decline` 只写记录不写模型，于是 `as-dialect` 一个字节不变 ——
              记录混进模型会让同一个模型因为「谁签的」不同而产出不同的 content-hash,
              而制品的身份必须只由模型决定（D4.3）"
      (is (= before after))
      (is (= 1 (count (pool/confirmations (db)))) "记录确实写下去了，不是什么都没发生"))
    (testing "整份方言里查不到 `confirmation` 这个词"
      (is (not (str/includes? (binding [*print-namespace-maps* false] (pr-str after))
                              "confirmation"))))))

(deftest the-confirmation-record-is-immutable
  (let [ck (sign-one!)]
    (testing "没有任何写面**寻址得到**它：`execute_action` 按类型表收类型，
              `:confirmation` 不在那张表里（D1.8「把拒绝做成结构」）"
      (is (contains? (rejected! (pool/execute-action
                                 pt/*conn*
                                 {:workspace "demo" :action :update :type :confirmation :id "x"
                                  :entry {} :expected_revision 1}))
                     :pool.action/unknown-type))
      (is (contains? (rejected! (pool/execute-action
                                 pt/*conn*
                                 {:workspace "demo" :action :delete :type :confirmation :id "x"
                                  :expected_revision 1}))
                     :pool.action/unknown-type)))
    (testing "同一条候选不许再裁一次 —— 「改」在这条通道上的唯一形态就是再签一次"
      (is (contains? (rejected! (pool/confirm-candidates!
                                 pt/*conn* {:workspace "demo" :kind :curated :candidate ck :decision :decline
                                            :expected_revision 0}
                                 {:entrypoint :ui}))
                     :pool.candidate/already-confirmed)))
    (is (= 1 (count (pool/confirmations (db)))))))

(deftest export-roundtrip-is-still-byte-identical
  (sign-one!)
  (let [before (pool/export-pool (db))
        cfg2   (pool/config {:backend :mem :id (str (gensym "roundtrip"))})]
    (pool/delete-pool! cfg2)
    (let [conn2 (pool/open-pool! cfg2)]
      (try
        (let [r (pool/import-pool! conn2 (edn/read-string before))]
          (is (empty? (:findings r)) (pr-str (:findings r))))
        (testing "B4.1 退出条件 1 不许破：删掉 store、导入最后一次导出，
                  **逐字节**重建"
          (is (= before (pool/export-pool (pool/db conn2)))))
        (testing "治理痕迹跟着池走 —— 一份连一次重建都撑不过的记录，说它不可变是自欺"
          (is (= (pool/confirmations (db)) (pool/confirmations (pool/db conn2)))))
        (finally (pool/close-pool! conn2) (pool/delete-pool! cfg2))))))

;; ---------------------------------------------------------------------------
;; 裁定 ③：provenance 分得开
;; ---------------------------------------------------------------------------

(deftest signoff-derived-and-authored-bindings-are-told-apart
  (let [idx  (pool/candidate-index (pool/pool-candidates (db)))
        [_ ck :as key*] (a-term-candidate idx)
        cand (get idx key*)
        signed-attr (first (:attributes cand))
        tid  "test.party/signed_concept"]
    (pool/confirm-candidates!
     pt/*conn* {:workspace "demo" :kind :term :candidate ck :decision :sign :concept-id tid
                :definition {:label "签" :description "签字派生。"}
                :expected_revision 0}
     {:entrypoint :mcp})
    ;; 作者**直接**写一条词条绑定：这仍然合法（B2 当年的 `:authored-terms` 就是这条路）,
    ;; 区别只在 provenance —— 分不开，签字就退化成一次多余的点击。
    (let [d0 (db)
          authored-attr (->> (pool/objects d0)
                             (filter #(= :attribute (:pool/type %)))
                             (remove #(:term (:pool/entry %)))
                             (sort-by #(pr-str (:pool/id %)))
                             first)
          aid (:pool/id authored-attr)]
      (pool/execute-action pt/*conn*
                           {:workspace "demo" :action :update :type :term :id tid :entry {}
                            :expected_revision (:pool/revision (pool/object d0 :term tid))})
      (let [r (pool/execute-action pt/*conn*
                                   {:workspace "demo" :action :update :type :attribute :id aid
                                    :entry (into {:term tid} (for [k cc/term-carried-keys] [k nil]))
                                    :expected_revision (:pool/revision authored-attr)})]
        (is (empty? (:findings r)) (pr-str (:findings r))))
      (let [d (db)]
        (testing "两条绑定在方言里长得**一模一样**（都是一个 `:term` 键）"
          (is (= tid (:term (:pool/entry (pool/object d :attribute signed-attr)))))
          (is (= tid (:term (:pool/entry (pool/object d :attribute aid))))))
        (testing "而在池里**查得出区别**（裁定 ③）"
          (is (= :signoff  (pool/provenance d :attribute signed-attr)))
          (is (= :authored (pool/provenance d :attribute aid))))
        (testing "后来一次无关的 `execute_action` 不会把 provenance 抹掉 ——
                  它是从确认记录推出来的，不是挂在对象上的一个会被 retract 的标记"
          (pool/execute-action pt/*conn*
                               {:workspace "demo" :action :update :type :attribute :id signed-attr
                                :entry {:label "改个标签"}
                                :expected_revision (:pool/revision (pool/object d :attribute signed-attr))})
          (is (= :signoff (pool/provenance (db) :attribute signed-attr))))))))

;; ---------------------------------------------------------------------------
;; 拒绝：各一条，都过 finding-v1 且不含配置值
;; ---------------------------------------------------------------------------

(deftest rejections
  (testing "未知候选 —— 裁一件机器从没提议过的事，等于让 owner 签自己写的东西"
    (is (contains? (rejected! (pool/confirm-candidates!
                               pt/*conn* {:workspace "demo" :kind :term :candidate "no_such_local_name"
                                          :decision :sign :concept-id "x/y"
                                          :definition {:label "l" :description "d"}
                                          :expected_revision 0}
                               {:entrypoint :mcp}))
                   :pool.candidate/unknown)))
  (testing "`expected_revision` 冲突 —— 确认记录此刻还不该存在，所以只能是 0"
    (let [idx (pool/candidate-index (pool/pool-candidates (db)))
          [_ ck] (a-term-candidate idx)]
      (is (contains? (rejected! (pool/confirm-candidates!
                                 pt/*conn* {:workspace "demo" :kind :term :candidate ck :decision :decline
                                            :expected_revision 7}
                                 {:entrypoint :ui}))
                     :pool.conflict/revision-mismatch))))
  (testing "重复签同一条候选（见 `the-confirmation-record-is-immutable`）"
    (let [ck (sign-one!)]
      (is (contains? (rejected! (pool/confirm-candidates!
                                 pt/*conn* {:workspace "demo" :kind :curated :candidate ck :decision :sign
                                            :expected_revision 0}
                                 {:entrypoint :ui}))
                     :pool.candidate/already-confirmed))))
  (testing "签了却不给概念 ID —— D2.26：机器不许命名概念，沿用历史字段名是明令禁止的"
    (let [idx (pool/candidate-index (pool/pool-candidates (db)))
          [_ ck] (a-term-candidate idx)]
      (is (contains? (rejected! (pool/confirm-candidates!
                                 pt/*conn* {:workspace "demo" :kind :term :candidate ck :decision :sign
                                            :expected_revision 0}
                                 {:entrypoint :mcp}))
                     :pool.candidate/id-required))))
  (testing "**请求里递一个 `:entrypoint` —— 伪造签字来源不是被过滤掉，而是寻址不到**
            （8.4 P1b / audit 裁定 ①）。这条从前是「入口缺席即拒」，现在缺席是合法的
            （记 `:unattributed`），不合法的是调用方**自称**从哪个入口来：那是一件
            它无权断言的事，而一个能自称 `:ui` 的 agent 会让签字记录说谎"
    (is (contains? (rejected! (pool/confirm-candidates!
                               pt/*conn* {:workspace "demo" :kind :curated :candidate "x" :decision :decline
                                          :entrypoint :ui :expected_revision 0}))
                   :pool.candidate/malformed)))
  (testing "出处本身不合词表也是拒绝，且 `:unattributed` 不许由调用方点名 ——
            它是**缺席的形状**，声称它等于声称一件自己不知道的事"
    (is (contains? (rejected! (pool/confirm-candidates!
                               pt/*conn* {:workspace "demo" :kind :curated :candidate "x" :decision :decline
                                          :expected_revision 0}
                               {:entrypoint :unattributed}))
                   :pool.provenance/malformed)))
  (testing "全部 `pool.candidate.*` code 都登记在册（finding-v1 §6）"
    (is (every? pool/finding-codes
                [:pool.candidate/unknown :pool.candidate/already-confirmed
                 :pool.candidate/id-required :pool.candidate/malformed]))))

(deftest candidate-db-refuses-exactly-what-execute-refuses
  "candidate-db 的 docstring 承诺「与 execute-action 同一条判定路径」——方言
   差集闸门也算在内。它曾经只跑 prepare，于是放行了一份 execute 必拒的条目;
   拿到那个候选库的 preflight 在投影里撞上 writer 的断言（writer 不是校验门,
   对非法输入抛 AssertionError），HTTP 边界的 catch Exception 接不住，请求
   永远悬着——界面上就是那个不散的「saving…」。"
  (let [bad {:workspace "demo" :action :update :type :attribute
             :id (first (keys (:attributes (pool/as-dialect (db)))))
             ;; 词表外的 :format —— 真非法。原样本是 `:listed? true`，后来
             ;; owner 裁定（2026-08-27）书写缺省是**等价拼法**，它就不再能当
             ;; 「必拒」的样本用了。
             :entry {:type :string :format :nope}
             :expected_revision 1}
        cand (pool/candidate-db pt/*conn* bad)
        exec (pool/execute-action pt/*conn* bad)]
    (is (nil? (:db cand)) "闸门在预览这条路上也要关")
    (is (= (set (map :code (:findings cand)))
           (set (map :code (:findings exec))))
        "两条路给出同一份拒绝——一条不多，一条不少")))
