(ns open.atlas.common.control-protocol.impl.finding
  "本模块的 finding 构造与已发布 `:code` 注册表（finding-v1 §3 的域续接纪律：
   各模块登记自己的域）。域是 `control.*`。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布的 `:code`。一经发布不改名；产出未注册 code 是实现 bug。"
  #{;; 信封结构
    :control.envelope/malformed          ; 不是 map、缺必填键、键面之外的键
    :control.envelope/unsupported-version ; :control/version 不是 1
    :control.envelope/unknown-op          ; :control/op 不在封闭词表里
    :control.envelope/payload-mismatch    ; payload 与该 op 的形状对不上
    :control.envelope/not-canonical       ; 值写不成规范 EDN（签名输入因此不确定）
    ;; 签名与时效
    :control.signature/missing            ; 该带签名的位置没有签名
    :control.signature/malformed          ; 签名串形状不对（算法前缀、hex 长度）
    :control.signature/unknown-key        ; key-id 不在验签方的钥匙串里
    :control.signature/mismatch           ; 算出来的与带来的不等
    :control.signature/expired            ; 已过 :control/expires-at
    :control.signature/not-yet-valid      ; :control/issued-at 在未来（时钟漂移超容差）
    :control.signature/audience-mismatch}) ; 签名断言指向的不是这台 instance

(defn make
  [code severity path message & {:keys [expected got refs]}]
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got (dialect/redact got))
    (seq refs)       (assoc :refs (vec refs))))

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn valid?
  "x 是否为合法 finding（finding-v1 §2 封闭键面 + 本域 code）。"
  [x]
  (boolean
   (and (map? x)
        (contains? codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn sort-findings
  "确定性顺序（finding-v1 §6）：按 `[path code message]` 的打印形。"
  [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))
