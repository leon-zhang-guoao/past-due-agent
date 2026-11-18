(ns open.atlas.common.dialect.impl.read
  "Reader + 归一（实现顺序第 3 步）。
   管线：parse → 归一 → 结构 schema → 跨条目不变量 → {:model|nil :findings}。
   容忍非规范输入（乱序键、#:ns{} 缩写）——归一即消除；
   但缺省值被书写、词表外值、未知键都是拒绝，不是归一。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.dialect.impl.finding :as finding]
            [open.atlas.common.dialect.impl.schema :as schema]
            [open.atlas.common.dialect.impl.vocab :as vocab]
            [open.atlas.common.dialect.impl.invariants :as invariants]))

(defn- parse
  "字符串 → {:data|nil :findings}。clojure.edn 无 reader eval，天然安全；
   #:ns{} 缩写在 edn/read-string 层已展开为全限定 kw（归一的一半白得）。"
  [s]
  (try
    {:data (edn/read-string s) :findings []}
    (catch Exception e
      {:data nil
       :findings [(finding/make :dialect.schema/parse-error :error []
                                (str "EDN parse failed: " (.getMessage e)))]})))

(defn- strip-default
  "键的值恰为缺省 → 键消失。只认**恰等**：`:listed? \"yes\"` 不是缺省的另一种
   拼法，是词表外的值，留给 schema 拒。"
  [m k]
  (if (and (map? m) (= (get m k ::absent) (get vocab/defaults k)))
    (dissoc m k)
    m))

(defn- normalize
  "归一：等价拼法收敛到规范形，读入即消除。

   **书写出来的缺省值是等价拼法，不是错误**（owner 裁定 2026-08-27，改判
   dialect-v1 §4 的 reader 半边）：`:listed? true` 与不写这个键说的是同一句话,
   收得宽、存得严——writer 仍然只产出规范形，往返不变量
   `(write (read s)) 逐字节 = (write 任何等价输入)` 因此不降反升（等价输入
   更多了）。剥除只发生在缺省键的**四个结构位置**，不做盲走：`:security` 与
   `:values` 里住着自由内容，盲走会把用户数据当缺省吃掉。

   仍然是拒绝、不是归一的：字符串形属性引用（D2.17 唯一拼法）、词表外的值、
   未知键。"
  [data]
  (if-not (map? data)
    data
    (cond-> data
      (map? (:attributes data))
      (update :attributes update-vals #(strip-default % :listed?))

      (vector? (:resolvers data))
      (update :resolvers (fn [rs] (mapv #(strip-default % :curated?) rs)))

      (vector? (:links data))
      (update :links (fn [ls] (mapv #(strip-default % :direction) ls)))

      (vector? (:mutations data))
      (update :mutations
              (fn [ms] (mapv (fn [m]
                               (if (map? (:params m))
                                 (update m :params update-vals
                                         #(strip-default % :required?))
                                 m))
                             ms))))))

(defn read-dialect [s]
  (let [{:keys [data findings]} (parse s)]
    (if (finding/errors? findings)
      {:model nil :findings (finding/sort-findings findings)}
      (let [data      (normalize data)
            f-schema  (schema/check data)
            f-inv     (when-not (finding/errors? f-schema)
                        (invariants/check data))
            all       (finding/sort-findings (concat findings f-schema f-inv))]
        {:model    (when-not (finding/errors? all) data)
         :findings all}))))
