(ns open.atlas.runtime.store.impl.finding
  "This module's finding constructor and published `:code` registry
   (finding-v1 §3). The domain is `instance.*`.

   **Why not `deploy.*`.** These codes were born in `server/control` when the
   lease existed only for deployments. The lease now covers every subsystem on
   the instance — a token issuance refused because another process holds the
   root would have answered `:deploy.lease/unavailable`, which reads as though
   a deployment were involved. The domain names what the code is about, and
   what it is about is the *instance*, not deploying."
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  #{;; 租约：单写进程（owner 裁定 2026-08-24）
    :instance.lease/unavailable      ; 启动时取不到 root 独占锁——**拒绝启动**
    :instance.lease/lost             ; 写之前发现锁已失效，或进程中毒——立即停写
    ;; 盘本身
    :instance.store/filesystem-unfit ; 不支持可靠文件锁与原子 rename（D4.12）
    :instance.store/illegal-segment}) ; 路径段不过白名单——**拒绝而不是净化**（D4.8）

(defn make
  [code severity path message & {:keys [expected got refs]}]
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got (dialect/redact got))
    (seq refs)       (assoc :refs (vec refs))))

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn valid? [x]
  (boolean
   (and (map? x)
        (contains? codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))
