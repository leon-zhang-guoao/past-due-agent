(ns open.atlas.common.search.impl.gram
  "文本 → 喂给 FTS5 的词流（README 裁定 ⑤）。

   ## 为什么自己切

   FTS5 内置的 `unicode61` 不切 CJK：一整句中文对它是**一个**词，于是
   「客户编号」搜不到「客户号」，也搜不到任何把它当子串的东西。切 CJK 的正路是
   装一个自定义 tokenizer，但那要按平台编译 `.so`——本仓库的测试要在任何一台
   机器上跑得起来，所以**不引 C 扩展**。

   做法：索引期与查询期**都由我们**把文本切成 2/3-gram，以空格分隔喂给 FTS5,
   让它把每个 n-gram 当普通词。两种 gram 都要（principles「存储与同步」）：短 CJK
   短语插一个字就让三元组交集清空，长标识符的信号又在三元组里。

   ## 英文**也**出 gram，折叠排在 gram 之前

   principles 那一段说的是**字符** 2/3-gram，并把 `HP compnay` → `HP company ltd.`
   的拼写容错明确算作它买来的能力：**搜索框被要求的是容错不是语义**。所以 Latin
   段落出「整词 + 它的 gram」，不是只出整词。

   （B4.3 第一版按 common/search README 裁定 ⑤ 只出整词，代价是英文错字不再容错。
   实施时把这条差异报了上来，owner 复核后判 principles 为准——README 那一条已改。
   记在这里是因为「只按词索引」看起来更省事，下一个人可能会想改回去。）

   折叠排在 gram **之前**也是那一段的原话：`sso/order-no`、`sso/order_no`、
   `sso/orderNo` 折叠后是同一个字符串，**于是产生逐字节相同的 gram 流——不是碰巧
   排对**。"
  (:require [clojure.string :as str]
            [open.atlas.common.search.impl.match :as match]))

(defn- cjk?
  "这个字符要不要走 n-gram 路线。

   按码位区间判而不是 `Character$UnicodeBlock`：区间在源码里看得见改得动,
   而 UnicodeBlock 的枚举名把「到底包不包括假名」藏进了 JDK 版本里。
   收 CJK 部首补充 → 谚文（含中日韩统一表意文字、假名、谚文），外加兼容表意文字;
   **CJK 标点（`0x3000`–`0x303F`，「」、，。）不算**——它是分隔符，
   跟着进 gram 会造出跨句的假词。"
  [^Character c]
  (let [n (int c)]
    (and (not (<= 0x3000 n 0x303F))
         (or (<= 0x2E80 n 0xD7FF)
             (<= 0xF900 n 0xFAFF)))))

(defn- cjk-runs
  "字符串里的连续 CJK 段。非 CJK 一律当分隔——「客户编号(cust_no)」切出的段是
   `客户编号`，括号里的东西归 Latin 那条路。"
  [s]
  (->> (partition-by cjk? (seq (str s)))
       (filter (comp cjk? first))
       (map str/join)
       (remove str/blank?)))

(defn- n-grams
  "一段 CJK 文本 → 2-gram 与 3-gram。长度不足 n 时整段自己作为一个词进去
   ——否则单字的属性名（「量」）在索引里根本不存在，而**不存在与排序靠后是
   两件不同的事**（CLAUDE.md：缺失要如实说，不静默降级）。"
  [s]
  (let [n (count s)]
    (if (< n 2)
      [s]
      (concat (map #(subs s % (+ % 2)) (range 0 (- n 1)))
              (when (>= n 3) (map #(subs s % (+ % 3)) (range 0 (- n 2))))))))

(defn cjk-terms
  "文本里所有 CJK 段的 2/3-gram（不含 Latin 词）。"
  [s]
  (mapcat n-grams (cjk-runs s)))

(defn latin-terms
  "文本里的 Latin/数字词元 → **整词 + 它的 2/3-gram**，折叠与展开在 gram **之前**。

   **为什么 Latin 也出 gram**（principles「存储与同步」）：搜索框被要求的是**容错
   不是语义**——`HP compnay` 要找得到 `HP company ltd.`。只按整词索引，一个错字就
   一条都不命中，而建模的人手打字段名时打错是常态。

   折叠排在 gram 之前也是那一条的原话：`sso/order-no`、`sso/order_no`、`sso/orderNo`
   折叠后是同一个字符串，**于是产生逐字节相同的 gram 流——不是碰巧排对**。

   整词**也留着**：精确名要能进「scope 内精确名」那一档（README 裁定 ② 的类型间
   固定顺序），只有 gram 的话精确匹配与拼错匹配在索引里无从区分。"
  [abbrev ns-str s]
  (let [tbl   (match/table-for abbrev ns-str)
        words (->> (str/split (str s) #"[^\p{IsAlphabetic}\p{IsDigit}]+" )
                   (remove str/blank?)
                   (remove #(some cjk? (seq %)))
                   (mapcat #(match/expand tbl (match/tokens %))))]
    (concat words (mapcat n-grams words))))

(defn terms
  "一段文本 → 喂 FTS5 的词序列（CJK gram + Latin 整词与 gram）。"
  [abbrev ns-str s]
  (concat (cjk-terms s) (latin-terms abbrev ns-str s)))

(defn text
  "多段文本 → 一个用空格分隔的字符串，即 FTS5 那一列的值。
   索引期与查询期走**同一个函数**——两边切法不同就等于索引里没有那些词。"
  [abbrev ns-str strs]
  (str/join " " (mapcat #(terms abbrev ns-str %) (remove nil? strs))))

(defn match-expression
  "查询词 → FTS5 `MATCH` 表达式。每个词加引号（gram 里可能出现 FTS5 的语法字符）,
   词之间是 `OR`：**召回宽、排序在类型内**（README 裁定 ②）。
   查询切不出任何词时给 nil，调用方据此拒绝而不是发一个空 MATCH。"
  [abbrev ns-str q]
  (let [ts (distinct (terms abbrev ns-str q))]
    (when (seq ts)
      (str/join " OR " (map #(str \" (str/replace % "\"" "\"\"") \") ts)))))

(defn precise-expression
  "Require every folded whole Latin word or CJK gram, before typo fallback.
   Uses the existing index/token normalization; never broadens an over-limit result."
  [abbrev q]
  (let [folded (match/fold abbrev nil q)
        words (remove str/blank? (str/split folded #"[^\p{IsAlphabetic}\p{IsDigit}]+"))
        ts (distinct (mapcat (fn [w] (if (some cjk? w) (cjk-terms w) [w])) words))]
    (when (seq ts)
      (str/join " AND " (map #(str \" (str/replace % "\"" "\"\"") \" ) ts)))))
