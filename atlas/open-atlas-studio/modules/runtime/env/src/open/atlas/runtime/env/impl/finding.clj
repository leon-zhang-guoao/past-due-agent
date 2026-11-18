(ns open.atlas.runtime.env.impl.finding
  "This module's finding constructor and published `:code` registry
   (finding-v1 §3). The domain is `env.*`.

   Fallthrough codes are passed through unchanged, as everywhere else: half of
   what can go wrong materialising a closure is `artifact.*` (a digest that does
   not match, a reference not on the shelf, a reader refusal) and the other half
   is `dialect.*` (two graphs claiming one attribute key). Re-wrapping those in
   `env.*` would make a caller routing on `:code` miss both halves."
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]))

(def codes
  #{:env.version/unresolved     ; :latest，但本机该图没有 serving 指针
    :env.artifact/absent        ; 闭包成员不在本机制品架上
    :env.artifact/unreceipted   ; 架上有目录但没有 receipt——**不算已装载**
    :env.closure/cycle          ; (graph, 版本) 上本该是 DAG
    :env.capabilities/missing}) ; 装配方没给齐读能力——**拒绝启动**

(def all-codes
  (into codes (concat artifact/finding-codes dialect/finding-codes)))

(defn make
  [code severity path message & {:keys [expected got refs]}]
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got got)
    (seq refs)       (assoc :refs (vec refs))))

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn valid? [x]
  (boolean
   (and (map? x)
        (contains? all-codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn sort-findings [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))
