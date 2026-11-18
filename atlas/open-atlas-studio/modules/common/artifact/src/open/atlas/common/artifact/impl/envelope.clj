(ns open.atlas.common.artifact.impl.envelope
  "`content.edn` v1 信封（D4.2）与引用清单（D4.4）。

   信封**顶层封闭，只允许三个键**：

   ```clojure
   {:artifact/format-version 1
    :objects    {...}      ; 公有方言的规范形根 map，原样嵌入
    :references [...]}     ; D4.4 的引用清单
   ```

   没有平级的 `policy` 槽，**不为未知的未来需求留空槽**——空槽会被后人随手
   塞东西，而它已经进了 hash（D4.2 被否方案）。

   `:objects` **完整复用方言**（D2.12 的规范形），本 ns 不定义第二套模型形状，
   也不校验模型内容：那是方言 reader 的唯一职责（D4.6）。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.artifact.impl.canonical :as c]
            [open.atlas.common.artifact.impl.finding :as f]))

(def ^:const format-version
  "v1 的装载方只支持版本 1（D4.7），其余一律结构化拒绝并说明差在哪。"
  1)

(def envelope-keys
  "顶层键面，封闭（D4.2）。未知键一律拒绝，不静默忽略（D4.6）。"
  #{:artifact/format-version :objects :references})

(def reference-keys
  "引用条目的键面，封闭（D4.4）。"
  #{:workspace :graph :version :content-hash})

(def ^:private required-reference-keys #{:workspace :graph :version :content-hash})

;; --- 引用清单 ---------------------------------------------------------------

(defn- check-reference
  "单条引用的形状（D4.4）：限定名两段都写（D3.10）；版本必须是具体数字
   （R3.1 永不指 latest）；`:content-hash` 让装载方能验证拿到的确实是当初
   解析的那一份。"
  [idx r]
  (let [path [:references idx]
        bad  (fn [msg & {:as opts}]
               [(apply f/error :artifact.reference/invalid-shape path msg
                       (mapcat identity (merge {:refs ["D4.4"] :files ["content.edn"]} opts)))])]
    (cond
      (not (map? r)) (bad "reference must be a map")

      (seq (remove reference-keys (keys r)))
      [(f/error :artifact.reference/invalid-shape path
                (str "unknown key(s) in reference: "
                     (pr-str (vec (sort-by str (remove reference-keys (keys r))))))
                :expected (vec (sort-by str reference-keys))
                :refs ["D4.4" "D4.6"] :files ["content.edn"])]

      (seq (remove #(contains? r %) required-reference-keys))
      [(f/error :artifact.reference/invalid-shape path
                (str "missing required key(s) in reference: "
                     (pr-str (vec (sort-by str (remove #(contains? r %)
                                                       required-reference-keys)))))
                :expected (vec (sort-by str required-reference-keys))
                :refs ["D4.4"] :files ["content.edn"])]

      (f/blank-str? (:workspace r)) (bad "reference :workspace must be a non-empty string")
      (f/blank-str? (:graph r))     (bad "reference :graph must be a non-empty string")

      (not (and (integer? (:version r)) (pos? (:version r))))
      (bad "reference :version must be a positive integer; R3.1 never points at latest"
           :got (:version r) :refs ["D4.4" "R3.1"])

      (not (c/hash-str? (:content-hash r)))
      (bad "reference :content-hash must be \"sha256:\" + 64 lowercase hex digits"
           :got (f/redact (:content-hash r)) :refs ["D4.3" "D4.4"])

      :else [])))

(defn- check-duplicates
  "同限定名与版本重复出现即拒绝，**不能靠「后者覆盖前者」**（D4.2）。

   **裁量点**：同一限定名的**两个不同版本**同时出现，D4.2 的字面读法不禁止，
   本实现照字面放行——钻石冲突的裁定属于闭包解析，留在 B5（D4.10-a 明说
   「钻石冲突、scope 解析与真实闭包留到 B5」）。"
  [refs]
  (->> (map-indexed vector refs)
       (filter (comp map? second))
       (group-by (fn [[_ r]] ((juxt :workspace :graph :version) r)))
       (filter (fn [[_ v]] (> (count v) 1)))
       (sort-by (comp pr-str first))
       (mapcat (fn [[_ occurrences]]
                 (for [[idx r] (rest occurrences)]
                   (f/error :artifact.reference/duplicate [:references idx]
                            (str "duplicate reference " (f/qualified-name r)
                                 "; the later entry does not override the earlier one")
                            :got (f/qualified-name r)
                            :refs ["D4.2"] :files ["content.edn"]))))))

(defn sort-references
  "`:references` 语义上是集合、编码上是 vector：写入前按
   `(workspace, graph, version)` 字典序排序（D4.2）。因此菜谱里的引用顺序
   不进入内容语义，也不会无故改变 content-hash。"
  [refs]
  (vec (sort-by (juxt :workspace :graph :version) refs)))

(defn check-references [refs]
  (if-not (vector? refs)
    [(f/error :artifact.envelope/invalid-shape [:references]
              "`:references` must be a vector (semantically a set, encoded as a vector)"
              :refs ["D4.2"] :files ["content.edn"])]
    (let [shape (vec (mapcat #(apply check-reference %) (map-indexed vector refs)))]
      (if (seq shape) shape (vec (check-duplicates refs))))))

;; --- encode -----------------------------------------------------------------

(defn check-format-version
  "格式版本协商（D4.7）。非 1 → 结构化拒绝并说明差在哪。"
  [v path file]
  (cond
    (= v format-version) []
    (not (integer? v))
    [(f/error :artifact.compat/unsupported-format-version path
              ":artifact/format-version must be an integer"
              :got (f/redact v) :expected format-version
              :refs ["D4.7"] :files [file])]
    :else
    [(f/error :artifact.compat/unsupported-format-version path
              (str "artifact format-version " v
                   " is not supported; this loader supports version "
                   format-version " only")
              :got v :expected format-version
              :refs ["D4.7" "OQ-ARTIFACT-COMPAT"] :files [file])]))

(defn build
  "规范化模型 + 引用清单 + 格式版本 → {:envelope|nil :findings}。
   错误与产物互斥（finding-v1 §2）——存在 `:error` 时不产带病信封。"
  [model references fmt]
  (let [findings (vec (concat
                       (check-format-version fmt [:artifact/format-version] "content.edn")
                       (when-not (map? model)
                         [(f/error :artifact.envelope/invalid-shape [:objects]
                                   "`:objects` must be the dialect canonical root map"
                                   :got (f/redact model)
                                   :refs ["D4.2"] :files ["content.edn"])])
                       (check-references references)))]
    {:envelope (when-not (f/errors? findings)
                 {:artifact/format-version fmt
                  :objects                 model
                  :references              (sort-references references)})
     :findings (f/sort-findings findings)}))

;; --- decode -----------------------------------------------------------------

(defn parse
  "文本 → {:data|nil :findings}。`clojure.edn` 无 reader eval，天然安全；
   **不装 tag handler**：制品里出现 reader tag 即是它不该有的东西。"
  [^String s]
  (try
    {:data (edn/read-string {:eof nil} s) :findings []}
    (catch Exception e
      {:data nil
       :findings [(f/error :artifact.envelope/parse-error []
                           (str "EDN parse failed: " (.getMessage e))
                           :files ["content.edn"])]})))

(defn check-shape
  "已 parse 的信封 → findings。顶层封闭：未知键一律拒绝（D4.6）。

   格式版本先于其余检查：装载方需要在解析 `content.edn` 之前就知道自己认不
   认识这个格式，而解析本身依赖格式版本（D4.7）。"
  [data]
  (cond
    (not (map? data))
    [(f/error :artifact.envelope/invalid-shape []
              "content.edn must be a map at the top level"
              :got (f/redact data) :refs ["D4.2"] :files ["content.edn"])]

    (not (contains? data :artifact/format-version))
    [(f/error :artifact.envelope/missing-key [:artifact/format-version]
              "content.edn must declare :artifact/format-version"
              :refs ["D4.7"] :files ["content.edn"])]

    (seq (check-format-version (:artifact/format-version data)
                               [:artifact/format-version] "content.edn"))
    (check-format-version (:artifact/format-version data)
                          [:artifact/format-version] "content.edn")

    :else
    (let [unknown (remove envelope-keys (keys data))
          missing (remove #(contains? data %) [:objects :references])]
      (vec (concat
            (when (seq unknown)
              [(f/error :artifact.envelope/unknown-key []
                        (str "unknown top-level key(s) in content.edn: "
                             (pr-str (vec (sort-by str unknown)))
                             "; a key the loader cannot read means either a version "
                             "mismatch or a tampered artifact, and neither may continue")
                        :expected (vec (sort-by str envelope-keys))
                        :refs ["D4.2" "D4.6"] :files ["content.edn"])])
            (for [k missing]
              (f/error :artifact.envelope/missing-key [k]
                       (str "content.edn must carry " k)
                       :refs ["D4.2"] :files ["content.edn"]))
            (when (and (empty? missing) (not (map? (:objects data))))
              [(f/error :artifact.envelope/invalid-shape [:objects]
                        "`:objects` must be the dialect canonical root map"
                        :got (f/redact (:objects data))
                        :refs ["D4.2"] :files ["content.edn"])])
            (when (empty? missing)
              (check-references (:references data))))))))
