(ns open.atlas.runtime.retention
  "R3.5 on one instance: which versions may be reclaimed, derived from what this
   machine currently holds.

   ## The rule, restated

   A version may be removed **iff**, on this instance:

   1. no **retained artifact** names it in a reference list;
   2. the serving pointer does not point at it;
   3. no **active, unexpired pinned grant** names it;
   4. nothing else in the local runtime is holding it — today that is the
      materialised env cache.

   \"Retained\" rather than \"deployed\" is load-bearing (R3.5's own wording): a
   version that is cut but not deployed still pins what it references, and
   counting only what is running would reclaim something an immutable,
   reproducible artifact depends on.

   ## Derived, never accumulated

   Every answer is recomputed from current state. A maintained counter would
   have to be decremented on `remove-grant`, on `revoke-token`, on expiry and
   on voiding an artifact — four paths, and a miss on any one of them is
   invisible until something that was still needed is gone.

   ## Why it is its own module

   The four inputs belong to four subsystems and **none of them should own the
   answer** (owner ruling 2026-08-25). In particular `runtime/token` must not
   reach back into the shelf: that would make the token ledger the owner of
   reclamation. Here the inputs arrive as injected reads, so this module depends
   on none of them and can be tested on its own."
  (:require [clojure.set :as set]))

(defrecord Retention [shelf-versions references-of serving-version
                      pinned-coordinates env-held])

(defn open!
  "`(open! {:shelf-versions … :references-of … :serving-version …
            :pinned-coordinates … :env-held …})` → `{:store s :findings []}`.

   - `:shelf-versions`     `(fn [] → #{{:graph … :version n}})`
   - `:references-of`      `(fn [graph version] → [{:graph … :version n}…])`
   - `:serving-version`    `(fn [graph] → n|nil)`
   - `:pinned-coordinates` `(fn [now] → #{{:graph … :version n}})`
   - `:env-held`           `(fn [] → #{[graph version]})`

   缺任何一个都**拒绝启动**：少一样输入，`reclaimable` 就会多报一些「可以删」,
   而那种错误只有在东西已经删掉之后才看得见。"
  [{:keys [shelf-versions references-of serving-version pinned-coordinates env-held]
    :as caps}]
  (let [missing (vec (for [[k v] caps :when (not (ifn? v))] k))]
    (if (or (seq missing)
            (not= 5 (count (select-keys caps [:shelf-versions :references-of :serving-version
                                              :pinned-coordinates :env-held]))))
      {:findings [{:code :retention.capabilities/missing :severity :error :path [:capabilities]
                   :message (str "reclamation needs all four holders on this instance; one "
                                 "missing input does not fail loudly, it silently reports "
                                 "more versions as reclaimable — and that error is only "
                                 "visible after something is already gone")
                   :expected [:shelf-versions :references-of :serving-version
                              :pinned-coordinates :env-held]}]}
      {:store (->Retention shelf-versions references-of serving-version
                           pinned-coordinates env-held)
       :findings []})))

(defn holders
  "谁在保留什么 → `{:referenced #{…} :served #{…} :pinned #{…} :materialised #{…}}`。

   分开给而不是直接给并集，是因为运维要答的下一个问题永远是**「被谁保留着」**;
   一个只回答「不能删」的函数，会让人去翻代码才知道该动哪一头。"
  [{:keys [shelf-versions references-of serving-version pinned-coordinates env-held]} now]
  (let [on-shelf (shelf-versions)]
    {:referenced   (into #{} (mapcat (fn [{:keys [graph version]}]
                                       (references-of graph version)))
                         on-shelf)
     :served       (into #{} (keep (fn [{:keys [graph]}]
                                     (when-let [v (serving-version graph)]
                                       {:graph graph :version v})))
                         on-shelf)
     :pinned       (set (pinned-coordinates now))
     :materialised (into #{} (map (fn [[g v]] {:graph g :version v})) (env-held))}))

(defn retained
  "本机**不可回收**的坐标集合 —— 四样保留的并集。"
  [store now]
  (let [h (holders store now)]
    (set/union (:referenced h) (:served h) (:pinned h) (:materialised h))))

(defn reclaimable
  "本机**可以回收**的坐标 —— 架上有、而没有任何一样保留着它的。

   注意这不是「删掉它们」：回收本身是另一个动作，且要经写闸与租约。本函数只
   回答「谁还指着它」，而 R3.5 说回收的判据必须与这个问题**完全对齐**。"
  [{:keys [shelf-versions] :as store} now]
  (set/difference (set (shelf-versions)) (retained store now)))

(defn explain
  "一个坐标为什么不能回收 → `#{:referenced :served :pinned :materialised}`（空 = 可回收）。"
  [store now coord]
  (let [h (holders store now)]
    (into #{} (keep (fn [k] (when (contains? (get h k) coord) k)))
          [:referenced :served :pinned :materialised])))

(def finding-codes #{:retention.capabilities/missing})
