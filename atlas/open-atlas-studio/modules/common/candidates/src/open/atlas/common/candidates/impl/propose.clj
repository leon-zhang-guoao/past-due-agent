(ns open.atlas.common.candidates.impl.propose
  "**机器提议**：从一份方言模型里挖出四张候选清单，交 owner 处置。

   这份实现从 B2 的 `legacy-import.impl.candidates` **原样搬过来**（modules/common/
   candidates/README.md「为什么单独一个模块」）：搬迁那天有两个调用方——B2 的
   一次性转换器与 B4 的池——而两份实现分叉的那天，同一份模型在两条路上会得到不同
   的候选，偏偏候选是 owner 逐条过目的东西（S5.4），他会以为自己看的是全部。
   转换器已于 2026-08-24 退休（fixtures/demo/README.md），**今天只剩池一个调用方**;
   实现留在 common 是因为 D1.8 不许 common 依赖 studio，不是因为还有两个调用方。

   两张清单（组候选随 D2.25 组移除，档位候选随 owner 裁定 2026-08-27 一并删去）：
   1. 词条候选（D2.26）：审计族按概念命名给出候选 ID；其余跨 entity 重名的
      按频次聚类，**ID 留空由 owner 命名**（不沿用历史字段名是 D2.26 的明文）；
   2. `:curated?` 待签清单（D2.13）：无锚的 list resolver。

   D2.27 闸门：绑定候选的成员若 `:type` 或码值编码不一致，**不出候选**，
   改报 finding——猜编码的失败形态是静默错配。

   **只提议，不处置。** 签字文件的解析、合并与拒绝留在调用方（B4 是
   `confirm_candidates` action；B2 当年是 `signoff.edn` 与 `apply-signoff`）。"
  (:require [clojure.string :as str]
            [open.atlas.common.candidates.impl.finding :as f]))

(def audit-concepts
  "转换规范的审计族白名单（dialect-v1 §6「entry_*/update_*/delete_*/u_version/
   h_version…，白名单在转换规范」）。

   右侧是**概念名**，不是历史字段名：词条叫 created_by，不叫 entry_id
   ——「后者只是某张表碰巧的拼法」（D2.26）。**D2.26 管的是用词，不是分隔符**：
   不许沿用历史字段名指的是 `created_by` ≠ `entry_id` 这个**词**的差别，
   下划线不承载那个信号（2026-08-23 注册键统一 snake_case，见 dialect-v1 §2）。
   白名单是清单不是正则：`last_pay_date` 之流形如审计实为业务日期，
   正则会把它们卷进来。"
  (array-map
   "entry_id"        "common.audit/created_by"
   "entry_date"      "common.audit/created_on"
   "entry_datetime"  "common.audit/created_at"
   "update_id"       "common.audit/updated_by"
   "update_date"     "common.audit/updated_on"
   "update_datetime" "common.audit/updated_at"
   "delete_id"       "common.audit/deleted_by"
   "delete_date"     "common.audit/deleted_on"
   "delete_datetime" "common.audit/deleted_at"
   "u_version"       "common.audit/detail_row_version"
   "h_version"       "common.audit/header_row_version"))

(defn- local [a] (name a))

(defn- binding-compatible
  "D2.27 兼容矩阵闸门：同一候选的全部绑定必须 `:type` 一致；带码值的还要
   **码值键集**一致（编码不同 → v1 拒绝共用词条）。返回 nil 表示可出候选，
   否则返回不兼容的事实描述。"
  [attrs members]
  (let [types (into (sorted-set) (map #(get-in attrs [% :type]) members))
        codes (into #{} (map #(some-> (get-in attrs [% :values]) keys set) members))
        codes (disj codes nil)]
    (cond
      (> (count types) 1) {:kind :type :got (vec types)}
      (> (count codes) 1) {:kind :values :got (mapv #(vec (sort-by str %)) (sort-by pr-str codes))}
      :else nil)))

;; ---------------------------------------------------------------------------
;; 1. 词条候选
;; ---------------------------------------------------------------------------

(defn term-candidates
  "D2.26：两个来源。
   - `:audit-whitelist` —— 白名单命中，候选 ID 由概念表给出；
   - `:name-frequency`  —— 跨 entity 重名按频次聚类，**候选 ID 留空**：
     概念名是业务判断，机器给不出，沿用历史字段名又是 D2.26 明令禁止的。
   两者都只在 D2.27 兼容时才出候选。"
  [model]
  (let [attrs (:attributes model)
        by-local (group-by local (keys attrs))
        rows (for [[ln ks] (sort-by key by-local)
                   :let [members (vec (sort-by str ks))
                         entities (into (sorted-set) (map namespace ks))
                         audit? (contains? audit-concepts ln)]
                   :when (and (or audit? (> (count entities) 1))
                              (> (count members) 1))]
               {:local-name ln
                :term (get audit-concepts ln)
                :source (if audit? :audit-whitelist :name-frequency)
                :frequency (count members)
                :entities (vec entities)
                :attributes members
                :incompatible (binding-compatible attrs members)})
        good (vec (for [r rows :when (nil? (:incompatible r))]
                    (cond-> (dissoc r :incompatible)
                      (nil? (:term r))
                      (assoc :term nil
                             :id-required-from-owner true
                             :note "concept ID must be named by the owner; reusing the historical field name is forbidden (D2.26)"))))
        bad  (vec (for [r rows :when (:incompatible r)] r))]
    {:candidates good
     :findings (vec (for [r bad]
                      (f/make :candidate/type-mismatch :warning
                              [:terms (:local-name r)]
                              (str "no term candidate is emitted for local name " (pr-str (:local-name r))
                                   ": its bindings disagree on "
                                   (name (:kind (:incompatible r)))
                                   " — the D2.27 compatibility matrix forbids sharing a term across "
                                   "differing types or encodings, and guessing produces silent mismatches")
                              :got (:got (:incompatible r))
                              :refs ["D2.26" "D2.27" "OQ-TERM-VALUE-MAPPING"])))}))

;; ---------------------------------------------------------------------------
;; 3. :curated? 待签清单（D2.13）
;; ---------------------------------------------------------------------------

(defn curated-candidates
  "D2.13：无锚的 list resolver。锚 = `:input` 非空。签字不可机械推定——
   「无 input 的 list resolver 有两种，机器分不开」。"
  [model]
  (let [rows (vec (for [rr (:resolvers model)
                        :when (and (= "list" (:result_type rr))
                                   (empty? (:input rr))
                                   (not (:curated? rr)))]
                    {:resolver (:id rr)
                     :label (:label rr)
                     :signoff-key (:id rr)}))]
    {:candidates rows
     :findings (vec (for [r rows]
                      (f/make :candidate/curated-signoff-needed :warning
                              [:resolvers (:resolver r) :curated?]
                              (str "list resolver " (pr-str (:resolver r))
                                   " has no anchor; :curated? true must be signed by the owner and is not "
                                   "inferred from the absence of :input")
                              :refs ["D2.13" "dialect-v1 §6"])))}))

;; ---------------------------------------------------------------------------
;; （原第 4 节「:sensitive 待确认清单」随访问档位整套退役 —— owner 裁定
;;   2026-08-27。属性不再有 :sensitivity，也就没有「疑似需要授权」这个提议：
;;   要更窄的可见范围就切更窄的图。）
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; 汇总
;; ---------------------------------------------------------------------------

(defn candidates
  "→ {:terms … :curated … :findings …}。
   **不改 model**——这是本 ns 的全部意义。"
  [model]
  (let [t (term-candidates model) c (curated-candidates model)]
    {:terms        (:candidates t)
     :curated      (:candidates c)
     :findings     (f/sort-findings (concat (:findings t) (:findings c)))}))
