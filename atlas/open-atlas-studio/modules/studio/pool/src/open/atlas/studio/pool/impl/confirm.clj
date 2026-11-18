(ns open.atlas.studio.pool.impl.confirm
  "`confirm_candidates` —— **候选签字通道**（§7.3「实施期待钉的四处」第 2 条,
   B4.4）。

   ## 为什么是一条**单独的 action**，不是 `execute_action` 的一个 `:type`

   三条理由，任何一条单独都够：

   1. **确认记录不是方言对象**（common/candidates README 裁定 ②）。`execute_action`
      的投机校验把**整池的对象**拼回方言交 B0 判；确认记录进不了那份模型，也不该
      进——它是治理痕迹，不是模型内容。给它开一个 `:type` 等于让一个「拼不回方言」
      的东西走一条「一定要拼回方言」的路。
   2. **一次签字产出好几个对象**：签一条词条候选 = 一条词条注册项 + n 条绑定。
      `execute_action` 一次一个对象，拆成 n+1 次调用意味着**「签了一半的候选」
      在池里是可表达的状态**——而那正是「签字退化成一次多余的点击」的形态。
      一条候选的裁决要么整个成立，要么一个字节都没写。
   3. **provenance 要与产出同一笔事务写下**（裁定 ③）。若签字走通用写面，
      「这条绑定是签出来的」只能事后补记，而事后补记是会漏的。

   ## 与 `execute_action` **共用**的东西（不另起一套）

   - `expected_revision` **必带**，规则与 §7.3 逐字相同：目标此刻还不该存在,
     所以取 **0**。这里的目标是**这条候选的确认记录**——「这条候选还没被裁决过」
     也是一句关于 revision 的断言，调用方只记一套规则。
   - 投机校验：`action/speculative-findings`，同一份实现。
   - 写入形状：`action/object-tx`，同一份实现——签字派生的对象与作者直写的对象
     在池里长得一模一样，**区别只在 provenance**（裁定 ③），不在存储形状。

   ## 不可变

   确认记录**没有任何写面寻址得到**：`execute_action` 按 `model/known-type?` 收
   类型，`:confirmation` 不在那张表里；本 ns 自己遇到已有记录一律
   `:pool.candidate/already-confirmed`。改一条记录要先改代码——D1.8「把拒绝做成
   结构」。

   ## provenance 从记录本身推，不另存一个标记

   「这条绑定是签出来的吗」= 「有没有哪条确认记录的 `:produced` 点着它」。
   另存一个 `:origin` 标记会有第二个真相，而两个真相分家的那天没人知道；更实际的
   是 `execute_action` 的 update 会逐条 retract 旧字段，一个挂在对象上的标记迟早
   被某次无关的编辑抹掉，而抹掉是**静默**的。"
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [open.atlas.common.candidates :as cc]
            [open.atlas.studio.pool.impl.action :as action]
            [open.atlas.studio.pool.impl.audit :as audit]
            [open.atlas.studio.pool.impl.finding :as f]
            [open.atlas.studio.pool.impl.model :as model]
            [open.atlas.studio.pool.impl.schema :as schema]
            [open.atlas.studio.pool.impl.store :as store]))

(def entrypoints
  "**入口**词表。principles「存储与同步」点名要的那一项：记录里没有它，就答不出
   「这个 revision 里哪些改动来自 AI」。

   **8.4 P1b 起它不再是请求的一个键，而是可信出处的一部分**（`impl/audit.clj`
   裁定 ①）：一个自己声明入口的调用方，声明的是一件它无权断言的事——签字记录
   于是能被 agent 写成「这是人在 UI 里签的」。词表本体住 audit，两张台账
   （确认记录与变更审计）共用同一份，理由见那边。"
  audit/entrypoints)

(def decisions
  "两种裁决。**不签也是裁决**：`:decline` 一样留记录（否则「看过了、不签」与
   「从没看过」在池里完全不可区分，正是 S5.4 那把量不出差别的尺子）。"
  #{:sign :decline})

;; `tiers` 随访问档位整套退役（owner 裁定 2026-08-27）：属性不再有
;; `:sensitivity`，也就没有「签哪一档」这个动作。要更窄的可见范围就切更窄的图。

(def ^:private request-keys
  "**`:entrypoint` 不在这里**（8.4 P1b）：它随可信出处从另一个参数进来，
   所以 agent 递一个 `:entrypoint :ui` 得到的是 `:pool.candidate/malformed`
   ——伪造签字来源不是被过滤掉，而是寻址不到（audit 裁定 ①）。"
  #{:kind :candidate :decision :concept-id :definition :tier :attributes
    :note :expected_revision :workspace})

(defn- rec-key [kind candidate]
  (binding [*print-namespace-maps* false] (pr-str [kind candidate])))

;; ---------------------------------------------------------------------------
;; 请求的形状
;; ---------------------------------------------------------------------------

(defn- check-shape
  [{:keys [kind candidate decision tier attributes definition note
           expected_revision workspace] :as req}]
  (cond-> []
    ;; D3.22, same rule `execute_action` applies and for the same reason: a
    ;; sign-off writes pool objects, and a pool object's owner is stored.
    (not (and (string? workspace) (seq workspace)))
    (conj (f/make :pool.candidate/malformed :error []
                  ":workspace is required (D3.22: a pool object's owner is stored)"
                  :expected "non-empty string" :got (f/redact workspace)
                  :refs ["D3.17" "D3.22"]))

    (seq (set/difference (set (keys req)) request-keys))
    (conj (f/make :pool.candidate/malformed :error []
                  "confirm_candidates key set is closed"
                  :expected (vec (sort (map str request-keys)))
                  :got (vec (sort (map str (set/difference (set (keys req)) request-keys))))))

    (not (contains? (set cc/kinds) kind))
    (conj (f/make :pool.candidate/malformed :error []
                  "kind must be one of the candidate kinds"
                  :expected (vec cc/kinds) :got kind :refs ["D2.26" "D2.13"]))

    (not (and (string? candidate) (seq candidate)))
    (conj (f/make :pool.candidate/malformed :error []
                  "candidate must be the non-empty key of one candidate"
                  :expected "string" :got (f/redact candidate)))

    (not (contains? decisions decision))
    (conj (f/make :pool.candidate/malformed :error []
                  "decision must be :sign or :decline; declining is a ruling too and is recorded"
                  :expected (vec (sort decisions)) :got decision))

    (not (integer? expected_revision))
    (conj (f/make :pool.candidate/malformed :error []
                  "expected_revision is required; the confirmation record must not exist yet, so it is 0"
                  :expected 0 :got (f/redact expected_revision) :refs ["§7.3" "D3.5"]))

    (not (or (nil? definition) (map? definition)))
    (conj (f/make :pool.candidate/malformed :error []
                  ":definition must be a map of dialect entry keys"
                  :got (f/redact definition)))

    (not (or (nil? attributes)
             (and (vector? attributes) (seq attributes) (every? qualified-keyword? attributes))))
    (conj (f/make :pool.candidate/malformed :error []
                  ":attributes, when given, must be a non-empty vector of fully qualified attribute keywords"
                  :got (f/redact attributes) :refs ["D2.17"]))

    (not (or (nil? note) (string? note)))
    (conj (f/make :pool.candidate/malformed :error [] ":note must be a string"
                  :got (f/redact note)))))

;; ---------------------------------------------------------------------------
;; 一条裁决 → 要写哪些对象
;; ---------------------------------------------------------------------------

(defn- bound-attributes
  "owner 可以只签一部分绑定——**签哪条绑哪条，绝不整簇自动绑**（同 B2 的
   `sign-terms`）。给了不在候选里的属性 = 点名点不中，拒绝而非静默忽略。"
  [cand asked]
  (let [all (vec (:attributes cand))]
    (if (nil? asked)
      {:attributes all}
      (let [extra (vec (sort-by str (remove (set all) asked)))]
        (if (seq extra)
          {:findings [(f/make :pool.candidate/unknown :error [:candidates]
                              (str (count extra) " of the attributes named by this ruling are not "
                                   "bindings of this candidate; a name that hits nothing is refused, "
                                   "not silently dropped")
                              :got (mapv str extra) :refs ["D2.26"])]}
          {:attributes (vec (filter (set asked) all))})))))

(defn- plan-writes
  "一条**签**下去的裁决 → `[{:type :id :entry} …]`（entry 已是最终值）。
   拒绝走 `:findings`。`db` 只用来读现值：resolver 是整体替换类型（§7.3），
   补一个 `:curated? true` 得先把现值取回来。"
  [db kind cand {:keys [concept-id definition tier attributes]}]
  (case kind
    ;; ── 词条（D2.26）：概念 ID **必须由 owner 给**，机器不许命名概念 ──────
    :term
    (let [tid (or concept-id (:term cand))]
      (cond
        (not (and (string? tid) (seq tid)))
        {:findings [(f/make :pool.candidate/id-required :error [:terms (:local-name cand)]
                            (str "signing a term candidate requires a concept id from the owner; "
                                 "the machine may not name the concept and reusing the historical "
                                 "field name is forbidden")
                            :expected "\"<vocabulary>/<name>\"" :refs ["D2.26"])]}

        :else
        (let [{:keys [attributes findings]} (bound-attributes cand attributes)
              existing (store/find-object db (model/object-key :term tid))]
          (cond
            (seq findings) {:findings findings}

            (and (nil? existing) (not (map? definition)))
            {:findings [(f/make :pool.candidate/malformed :error [:terms tid]
                                (str "term " (pr-str tid) " is not registered yet, so this ruling "
                                     "must carry its :definition; a binding pointing at a definition "
                                     "nobody wrote is exactly what D2.26 forbids")
                                :expected [:definition] :refs ["D2.26"])]}

            :else
            {:writes (into (if definition [{:type :term :id tid :entry definition}] [])
                           ;; D2.26：绑定之后本地不许再写词条携带字段——显式 `nil`
                           ;; 就是 merge patch 的删键（§7.3）。**词表在
                           ;; common/candidates**，B2 摘的是同一批键。
                           (for [a attributes]
                             {:type :attribute :id a
                              :entry (into {:term tid}
                                           (for [k cc/term-carried-keys] [k nil]))}))}))))

    ;; ── :curated?（D2.13）：resolver 是整体替换类型，补键要带上现值 ──────
    :curated
    (let [rid (:resolver cand)
          cur (store/find-object db (model/object-key :resolver rid))]
      (if (nil? cur)
        {:findings [(f/make :pool.candidate/unknown :error [:resolvers rid]
                            "the resolver this candidate names is no longer in the pool"
                            :refs ["D2.13"])]}
        {:writes [{:type :resolver :id rid
                   :entry (assoc (:pool/entry (schema/entity->object cur))
                                 :curated? true)}]}))

))

;; ---------------------------------------------------------------------------
;; 执行
;; ---------------------------------------------------------------------------

(defn- record-tx [kind candidate decision prov note produced]
  (cond-> {:db/id                       "confirmation"
           :pool.confirmation/key        (rec-key kind candidate)
           :pool.confirmation/kind       kind
           :pool.confirmation/candidate  candidate
           :pool.confirmation/decision   decision
           ;; 出处来自可信参数，不是请求里的键（audit 裁定 ①）。不给出处的签字
           ;; 记 `:unattributed`——比不记诚实（audit 裁定 ②）。
           :pool.confirmation/entrypoint (or (:entrypoint prov) :unattributed)
           :pool.confirmation/produced   (model/encode-edn produced)}
    note              (assoc :pool.confirmation/note note)
    (:token-id prov)  (assoc :pool.confirmation/token-id (:token-id prov))
    (:actor prov)     (assoc :pool.confirmation/actor (model/encode-edn (:actor prov)))
    (:at prov)        (assoc :pool.confirmation/at (:at prov))))

(defn confirm
  "一次裁决。返回 `{:confirmation 记录 :findings []}`（已提交）或
   `{:findings [错误…]}`（**一个字节都没写**，含确认记录本身）。

   `prov` 是可信出处（8.4 P1b）：由服务端构造，调用方递不进来。"
  ([conn req] (confirm conn req nil))
  ([conn {:keys [kind candidate decision note expected_revision] :as req} prov]
  (let [shape (if (map? req)
                (into (check-shape req) (audit/check prov))
                [(f/make :pool.candidate/malformed :error [] "request must be a map"
                         :got (f/redact req))])]
    (if (seq shape)
      {:findings (f/sort-findings shape)}
      ;; 读—判—写在一把锁里，同 `execute_action`：`d/with` 与 `d/transact` 之间
      ;; 若被别人插进来，判的就是一个已经过期的事实。
      (locking conn
        (let [db    (d/db conn)
              rk    (rec-key kind candidate)
              exist (store/find-confirmation db rk)
              cands (cc/keyed (cc/candidates (model/objects->dialect (store/all-objects db))))
              cand  (get cands (cc/candidate-key kind candidate))
              errs
              (cond
                exist
                [(f/make :pool.candidate/already-confirmed :error [:candidates kind]
                         (str "this candidate already has a confirmation record; the record is "
                              "immutable and there is no second ruling on the same candidate")
                         :got {:decision (:pool.confirmation/decision exist)}
                         :refs ["common/candidates README 裁定 ②"])]

                (not= 0 expected_revision)
                [(f/make :pool.conflict/revision-mismatch :error [:candidates kind]
                         "the confirmation record must not exist yet, so expected_revision is 0"
                         :expected 0 :got expected_revision :refs ["D3.5" "§7.3"])]

                (nil? cand)
                [(f/make :pool.candidate/unknown :error [:candidates kind]
                         (str "no such candidate in the pool's current candidate list; a ruling on "
                              "something the machine never proposed is refused rather than written "
                              "— it would be an owner signing his own words")
                         :refs ["common/candidates README"])])]
          (cond
            (seq errs) {:findings (f/sort-findings errs)}

            :else
            (let [{:keys [writes findings]}
                  (if (= :decline decision) {:writes []} (plan-writes db kind cand req))]
              (if (seq findings)
                {:findings (f/sort-findings (vec findings))}
                (let [;; 每个产出对象的**前后 revision**：不存在写 0，与 `:create`
                      ;; 的 `expected_revision` 取 0 同一套规则。
                      planned (vec (for [{:keys [type id entry]} writes
                                         :let [cur (store/find-object db (model/object-key type id))
                                               before (or (:pool.object/revision cur) 0)]]
                                     {:type type :id id
                                      :entry (action/new-entry
                                              type
                                              (when cur (:pool/entry
                                                         (schema/entity->object cur)))
                                              entry)
                                      :revision-before before
                                      :revision-after  (inc before)}))
                      produced (mapv (fn [{:keys [type id revision-before revision-after]}]
                                       {:object [type id]
                                        :revision-before revision-before
                                        :revision-after revision-after})
                                     planned)
                      tx (into (vec (mapcat (fn [{:keys [type id entry revision-after]}]
                                              (action/object-tx db type id entry revision-after
                                                                (:workspace req)))
                                            planned))
                               [(record-tx kind candidate decision prov note produced)])
                      {:keys [db-after]} (d/with db tx)
                      spec (action/speculative-findings db db-after kind candidate
                                                        (:workspace req))]
                  (if (seq (:errors spec))
                    {:findings (vec (:errors spec))}
                    (do (d/transact conn tx)
                        {:confirmation (cond-> {:confirmation/key [kind candidate]
                                                :confirmation/kind kind
                                                :confirmation/candidate candidate
                                                :confirmation/decision decision
                                                :confirmation/entrypoint
                                                (or (:entrypoint prov) :unattributed)
                                                :confirmation/produced produced}
                                         (:token-id prov)
                                         (assoc :confirmation/token-id (:token-id prov))
                                         (:actor prov)
                                         (assoc :confirmation/actor (:actor prov))
                                         (:at prov)
                                         (assoc :confirmation/at (:at prov)))
                         ;; 已存在的脏随行报出，但没挡住这次签字（裁定 ⑤ 第 4 步）。
                         :findings (vec (:warnings spec))}))))))))))))

;; ---------------------------------------------------------------------------
;; provenance（裁定 ③）
;; ---------------------------------------------------------------------------

(defn signoff-derived
  "签字**派生**的对象集合：`#{[类型 id] …}`。

   由确认记录的 `:produced` 推出来，不另存一个标记——两个真相分家的那天没人会
   知道，而挂在对象上的标记还会被后来一次无关的 `execute_action` update 静默
   retract 掉。"
  [db]
  (into #{} (for [c (store/all-confirmations db)
                  p (:confirmation/produced c)]
              (:object p))))

(defn provenance
  "一个对象是**签字派生**还是**作者直写**（裁定 ③）。

   为什么必须分得开：一条绑定是「机器提议、人核准」还是「人直接写的」，是两件
   不同的事，报告与审计都要答得出是哪一件。**把两者混成一样，签字就退化成一次
   多余的点击。**"
  [db t id]
  (if (contains? (signoff-derived db) [t id]) :signoff :authored))
