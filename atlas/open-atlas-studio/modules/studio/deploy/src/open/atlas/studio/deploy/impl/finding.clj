(ns open.atlas.studio.deploy.impl.finding
  "域是 `deploy.*`（studio 侧的那几个）。**server 回来的 finding 原样透传**——
   它们是 `deploy.*`/`control.*`/`artifact.*`，重贴一层皮会让下游按 code 分流时
   漏掉一半（同 studio/version 的 `finding?`）。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]))

(def codes
  #{:deploy.authz/not-owner        ; actor 不是该 workspace 的 owner（D3.12/D5.7）
    :deploy.graph/unknown          ; 本 studio 里没有这张 graph
    :deploy.plan/unknown-version   ; 闭包里的某一版在架子上或版本登记里缺席
    :deploy.transport/failed       ; 送不出去（连不上、非 2xx 且非结构化拒绝、应答读不了）
    :deploy.transport/unexpected}) ; 应答形状不是协议约定的那两种

(def all-codes
  (into codes (concat artifact/finding-codes cp/finding-codes dialect/finding-codes
                      ;; server 回来的 deploy.* —— 它那一份注册表在 server 模块,
                      ;; studio 不依赖 server（D1.8），所以这里按域前缀放行。
                      )))

(defn make [code severity path message & {:keys [expected got refs]}]
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got (dialect/redact got))
    (seq refs)       (assoc :refs (vec refs))))

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn valid?
  "本模块自己产出的 finding 是否合法。**server 回来的不走这里**——它们由对面的
   注册表负责，这一侧只透传。"
  [x]
  (boolean
   (and (map? x)
        (or (contains? all-codes (:code x))
            ;; server 侧的 deploy.* —— 见 all-codes 的注释。
            (= "deploy" (some-> (:code x) namespace (clojure.string/split #"\.") first)))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x)))))

(defn sort-findings [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))
