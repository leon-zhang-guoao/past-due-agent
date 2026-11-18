(ns open.atlas.common.dialect.impl.finding
  "Finding v1（docs/contracts/finding-v1.md）：构造、校验、排序、不泄漏截断。
   实现顺序第 1 步——所有后续步骤共用的输出契约。"
  (:require [malli.core :as m]))

(def codes
  "已发布 :code 注册表。产出未注册 code 是实现 bug（测试兜底）。
   随实现推进逐条登记；一经发布不改名。"
  #{;; dialect.schema —— 结构/封闭/词表
    :dialect.schema/parse-error
    :dialect.schema/unknown-key
    :dialect.schema/vocab-violation
    :dialect.schema/default-written          ; 缺省值被书写出来（如 :curated? false）
    :dialect.schema/missing-key              ; 契约声明的必填键缺席
    :dialect.schema/invalid-shape            ; 形状不符（非未知键/词表/缺省的其余结构违规）
    ;; dialect.invariant —— 跨条目不变量
    :dialect.invariant/result-type-returns-mismatch   ; D2.19
    :dialect.invariant/eql-param-unresolvable         ; D2.20
    :dialect.invariant/replaced-by-invalid            ; D2.22
    :dialect.invariant/replacement-cycle              ; D2.22 替换环（属性/词条同则）
    :dialect.invariant/eql-param-anchor-invalid       ; D2.20 :attribute/:type 互斥且必居其一
    :dialect.invariant/reserved-param-name            ; D2.20 保留调用名被声明为参数名
    ;; D2.29 改名：`navigation-target-mismatch` 的检查对象从 navigation 变成了
    ;; join 属性，code 跟着改——`:navigation` 这个词在 v1 里已经没有指称对象了。
    :dialect.invariant/join-target-mismatch           ; D2.16/D2.29 :target 与子属性 namespace 不一致
    :dialect.invariant/join-attribute-invalid         ; D2.29 语法位置与条目形状不一致
    ;; D2.29 后续裁定（owner 2026-09-02）：join `:target` 指向未登记 namespace。
    ;; 合并前这条由 pool 的父 namespace 检查兜着，合并后失去出处，补回方言层。
    :dialect.invariant/join-target-unregistered       ; D2.29 :target 不是已登记 namespace
    :dialect.invariant/term-violation                 ; D2.26
    :dialect.invariant/type-compat-violation          ; D2.27 兼容矩阵
    :dialect.invariant/unregistered-reference         ; 先注册后使用（units/terms）
    ;; `:dialect.invariant/name-collision` 随 D2.29 退役：一张注册表之后，
    ;; 同 namespace 内的一个名字只对应一个条目，没有第二个东西可撞。
    :dialect.invariant/list-limit-missing             ; D2.8 list resolver 必须声明 :limit
    ;; dialect.merge
    :dialect.merge/duplicate-entry
    ;; 闭包合并（R3.2）：同键**异值**。同键同值在闭包里是常态（共享池的同一个
    ;; 对象经两张图带过来），折叠而不报——见 `dialect/merge-closure`。
    :dialect.merge/closure-conflict
    ;; dialect.carried
    :dialect.carried/key-present
    ;; dialect.need —— 反向翻译（doc 106 §106.5，P8a）。既不是结构违规也不是
    ;; 不变量：模型合法，只是**读回用户语言时读不干净**。两条都是 :warning,
    ;; 因为它们点名的是缺一句声明，不是拒绝一个模型。
    :dialect.need/identity-undeclared        ; §106.5 形状证据：身份看得出来但没人标
    :dialect.need/join-misplaced})           ; §106.5 位置规则：join 挂在子 namespace 上

(def Finding
  (m/schema
   [:map {:closed true}
    [:code     [:fn (comp some? codes)]]
    [:severity [:enum :error :warning :info]]
    [:path     vector?]
    [:message  :string]
    [:expected {:optional true} :any]
    [:got      {:optional true} :any]
    [:refs     {:optional true} [:vector :string]]
    [:files    {:optional true} [:vector :string]]]))

(defn valid? [x] (m/validate Finding x))

(defn make
  "构造并断言。opts 只收 :expected :got :refs :files。"
  [code severity path message & {:as opts}]
  (let [f (merge {:code code :severity severity :path path :message message}
                 opts)]
    (assert (valid? f) (str "invalid finding: " (pr-str f)))
    f))

(defn redact
  "不泄漏截断（finding-v1 §5）：操作块内容降为类型描述。
   B1 连接器 ex-data 复用同一函数。"
  [v]
  (cond
    (string? v) (str "string(" (count v) " chars)")
    (map? v)    (str "map(" (count v) " keys)")
    (coll? v)   (str "coll(" (count v) " items)")
    :else       v))

(defn sort-findings
  "确定性顺序：按 :path 字典序、同 path 按 :code（finding-v1 §6）。"
  [findings]
  (sort-by (juxt (comp pr-str :path) :code) findings))

(defn errors? [findings]
  (boolean (some #(= :error (:severity %)) findings)))
