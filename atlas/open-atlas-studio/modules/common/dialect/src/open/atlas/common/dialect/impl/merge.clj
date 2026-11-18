(ns open.atlas.common.dialect.impl.merge
  "分拆文件纯并集（实现顺序第 6 步）。
   冲突面（dialect-v1 §4）：同 id resolver/mutation、同键 source/namespace/
   attribute/unit/metric/term、同端点 link → :dialect.merge/duplicate-entry
   （:error，:files 点名两个文件）。不继承参考实现「后文件静默覆盖」。
   合并产物整体重过 invariants（注册表引用可跨文件成立）。"
  (:require [open.atlas.common.dialect.impl.finding :as finding]
            [open.atlas.common.dialect.impl.invariants :as invariants]))

(def ^:private map-keys
  "map 形注册表：键相同即冲突（dialect-v1 §4）。"
  [:attributes :metrics :namespaces :sources :terms :units])

(def ^:private id-keys
  "向量形条目：`:id` 相同即冲突。"
  [:resolvers :mutations])

(defn- link-endpoint
  "link 的冲突身份：`:bi`（缺省，不书写）两端按字典序规范化后比较（D2.3/dialect-v1 §3）。"
  [l]
  (let [dir (get l :direction :bi)
        ends [(:from l) (:to l)]]
    (if (= :bi dir)
      [:bi (vec (sort-by pr-str ends))]
      [dir (vec ends)])))

(defn- dup-finding [path what ident file-a file-b]
  (finding/make :dialect.merge/duplicate-entry :error path
                (str "duplicate " what " " (pr-str ident)
                     " declared in two files; author-side split files merge as a pure union")
                :got ident
                :refs ["dialect-v1 §4" "dialect-v1 §7"]
                :files [file-a file-b]))

(defn- merge-map-key
  "把一个 map 形顶层键并起来：{:acc 合并结果 :owner {键 → 文件} :findings […]}。"
  [state k {:keys [file model]}]
  (reduce-kv
   (fn [st entry-key entry-val]
     (if-let [prev (get-in st [:owner k entry-key])]
       (update st :findings conj
               (dup-finding [k entry-key] (name k) entry-key prev file))
       (-> st
           (assoc-in [:acc k entry-key] entry-val)
           (assoc-in [:owner k entry-key] file))))
   state
   (get model k)))

(defn- merge-id-key
  "resolvers / mutations：按 :id 并集，重复 id 点名两个文件。"
  [state k {:keys [file model]}]
  (reduce
   (fn [st entry]
     (let [id (:id entry)]
       (if-let [prev (get-in st [:owner k id])]
         (update st :findings conj
                 (dup-finding [k] (str (name k) " :id") id prev file))
         (-> st
             (update-in [:acc k] (fnil conj []) entry)
             (assoc-in [:owner k id] file)))))
   state
   (get model k)))

(defn- merge-links
  "links：按规范化端点并集（`:bi` 两端字典序），同端点点名两个文件。"
  [state {:keys [file model]}]
  (reduce
   (fn [st l]
     (let [ident (link-endpoint l)]
       (if-let [prev (get-in st [:owner :links ident])]
         (update st :findings conj
                 (dup-finding [:links] "link endpoint pair" ident prev file))
         (-> st
             (update-in [:acc :links] (fnil conj []) l)
             (assoc-in [:owner :links ident] file)))))
   state
   (:links model)))

(defn- closure-conflict-finding [path what ident a b]
  (finding/make :dialect.merge/closure-conflict :error path
                (str "two members of one reference closure carry different definitions of the same "
                     what " " (pr-str ident) "; they were sealed from the shared pool at moments "
                     "when that object differed, and one env cannot serve both")
                :got ident
                :refs ["R3.2" "D1.2" "dialect-v1 §4"]
                :files [a b]))

(defn- merge-closure-map-key
  "闭包合并的 map 形键：**同键同值折叠，同键异值冲突**。

   与作者端的 `merge-map-key` 不同，而区别是有理由的：作者端「同一个模型拆成
   多文件」里出现同键，那是笔误——声明一次就够了。闭包里出现同键则是常态：
   全公司共享**一个**语义池（D1.2），两张图各自的投影带着同一个池对象过来,
   逐字节相同。按作者端的严格性判，几乎每一个真实闭包都合不起来。

   **异值仍然拒绝**：那意味着两版是在池里那个对象不同的两个时刻密封的,
   一份 env 服务不了两种定义——猜哪一个才是灾难（D4.3 同一条精神）。"
  [state k {:keys [file model]}]
  (reduce-kv
   (fn [st entry-key entry-val]
     (let [prev-file (get-in st [:owner k entry-key])
           prev-val  (get-in st [:acc k entry-key])]
       (cond
         (nil? prev-file)
         (-> st
             (assoc-in [:acc k entry-key] entry-val)
             (assoc-in [:owner k entry-key] file))

         (= prev-val entry-val) st

         :else
         (update st :findings conj
                 (closure-conflict-finding [k entry-key] (name k) entry-key prev-file file)))))
   state
   (get model k)))

(defn- merge-closure-id-key
  "resolvers / mutations：同 `:id` 同内容折叠，异内容冲突（同上）。"
  [state k {:keys [file model]}]
  (reduce
   (fn [st entry]
     (let [id (:id entry)
           prev-file (get-in st [:owner k id])
           prev-val  (get-in st [:seen k id])]
       (cond
         (nil? prev-file)
         (-> st
             (update-in [:acc k] (fnil conj []) entry)
             (assoc-in [:owner k id] file)
             (assoc-in [:seen k id] entry))

         (= prev-val entry) st

         :else
         (update st :findings conj
                 (closure-conflict-finding [k] (str (name k) " :id") id prev-file file)))))
   state
   (get model k)))

(defn- merge-closure-links [state {:keys [file model]}]
  (reduce
   (fn [st l]
     (let [ident (link-endpoint l)
           prev-file (get-in st [:owner :links ident])
           prev-val  (get-in st [:seen :links ident])]
       (cond
         (nil? prev-file)
         (-> st
             (update-in [:acc :links] (fnil conj []) l)
             (assoc-in [:owner :links ident] file)
             (assoc-in [:seen :links ident] l))
         (= prev-val l) st
         :else (update st :findings conj
                       (closure-conflict-finding [:links] "link endpoint pair" ident
                                                 prev-file file)))))
   state
   (:links model)))

(defn merge-closure
  "引用闭包的成员模型 → 一份合并模型（R3.2）。同键同值折叠，同键异值拒绝。"
  [named-models]
  (let [{:keys [acc findings]}
        (reduce
         (fn [st nm]
           (let [st (reduce #(merge-closure-map-key %1 %2 nm) st map-keys)
                 st (reduce #(merge-closure-id-key %1 %2 nm) st id-keys)]
             (merge-closure-links st nm)))
         {:acc {} :owner {} :seen {} :findings []}
         named-models)
        merged (into {} (remove (comp nil? val)) acc)
        inv    (when-not (finding/errors? findings) (invariants/check merged))
        all    (finding/sort-findings (concat findings inv))]
    {:model    (when-not (finding/errors? all) merged)
     :findings (vec all)}))

(defn merge-dialects
  "[{:file 名 :model 内部模型}…] → {:model|nil :findings}。
   纯并集；无冲突时对合并产物整体重跑 invariants/check——注册表「先注册后使用」
   可以跨文件成立，逐文件校验会误报（dialect-v1 §4）。"
  [named-models]
  (let [{:keys [acc findings]}
        (reduce
         (fn [st nm]
           (let [st (reduce #(merge-map-key %1 %2 nm) st map-keys)
                 st (reduce #(merge-id-key %1 %2 nm) st id-keys)]
             (merge-links st nm)))
         {:acc {} :owner {} :findings []}
         named-models)
        merged   (into {} (remove (comp nil? val)) acc)
        inv      (when-not (finding/errors? findings)
                   (invariants/check merged))
        all      (finding/sort-findings (concat findings inv))]
    {:model    (when-not (finding/errors? all) merged)
     :findings (vec all)}))
