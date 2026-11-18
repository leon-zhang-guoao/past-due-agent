(ns open.atlas.studio.graph.impl.selector
  "namespace selector 的规范形与**分段边界匹配**——
   [scope projection v1 §1](../../../../../../../../docs/contracts/scope-projection-v1.md)
   的那一段规则，**这是它唯一的实现**。

   §1 说：selector 只有两种规范形，精确 namespace 名或以 `.*` 结尾的前缀；前缀按
   `.` 分段边界匹配（`sales.*` 匹配 `sales.order`，不匹配 `salesforce.order`；裸
   `sales` 只做精确匹配）。§7 又把同一套规则加到词表域前缀上（`common.audit/*`,
   按 `.` 与 `/` 分段边界）。

   **为什么两处共用一份实现**：B5a.1 的地盘登记查重与 B5a.2 的投影第 1、7 步问的是
   同一个问题——「这个名字落在这个 selector 里吗」。写成两份，某天有人在投影里补一个
   边界情形而忘了登记查重，于是**登记说不重叠、投影说都归你**，两个 workspace 同时
   合法地圈走同一个 namespace。CLAUDE.md 那条「`redact` 与档位词表出现过两份」
   说的就是这种事。

   本 ns 是**纯函数**，不碰池、不碰时间——§1「实现不得读取 latest、当前时间或池快照
   之外的状态」从这一层就成立。"
  (:require [clojure.string :as str]))

(def ^:private segment-separators
  "分段边界字符。`.` 来自 §1，`/` 来自 §7 的词表域（`common.audit/g_x`）。
   两者同表，是因为 §7 明说词表域前缀「规则同 namespace selector」。"
  #{\. \/})

(defn prefix-form?
  "selector 是不是前缀形（以 `.*` 或 `/*` 结尾）。"
  [s]
  (and (string? s)
       (> (count s) 2)
       (str/ends-with? s "*")
       (contains? segment-separators (nth s (- (count s) 2)))))

(defn canonical?
  "**只有两种规范形**（§1）。裸名里不许出现 `*`——`sal*es` 那种写法既不是精确名也不是
   前缀，静默当成精确名去匹配会让作者以为自己圈到了一批东西，而实际零匹配。"
  [s]
  (and (string? s)
       (seq s)
       (if (str/includes? s "*")
         (prefix-form? s)
         (not (contains? segment-separators (last s))))))

(defn- prefix-of
  "前缀形 selector → 它要求的字面前缀（**含分段分隔符**）。
   `sales.*` → `\"sales.\"`；`common.audit/*` → `\"common.audit/\"`。
   分隔符留在前缀里，就是「按分段边界匹配」这句话的全部实现——
   `salesforce.order` 不以 `sales.` 开头，于是自然不匹配。"
  [s]
  (subs s 0 (dec (count s))))

(defn matches?
  "selector 是否选中名字 `nm`。精确形要求相等；前缀形要求以 `<前缀><分隔符>` 开头
   且**还有下文**（`sales.*` 不选中 `sales` 自己——那是一个独立的 namespace,
   要它就精确写出来）。"
  [selector nm]
  (boolean
   (and (string? nm)
        (if (prefix-form? selector)
          (let [p (prefix-of selector)]
            (and (str/starts-with? nm p) (> (count nm) (count p))))
          (= selector nm)))))

(defn overlap?
  "两个 selector 是否**可能**选中同一个名字——地盘查重的判据（D3.3「登记查重」）。

   三种组合：两个精确名相等；精确名落在另一个前缀里；两个前缀中一个是另一个的
   前缀（`sales.*` 与 `sales.order.*`）。

   **重叠即冲突，不做「先到先得、后到只拿差集」**：差集意味着一个 workspace 的地盘
   要靠读另一个 workspace 的登记才能算出来，而它会随对方的登记变化——地盘从此不是
   一句能写在纸上的话。D3.3 要的是登记查重，不是登记求差。"
  [a b]
  (cond
    (and (prefix-form? a) (prefix-form? b))
    (let [pa (prefix-of a) pb (prefix-of b)]
      (or (str/starts-with? pa pb) (str/starts-with? pb pa)))

    (prefix-form? a) (matches? a b)
    (prefix-form? b) (matches? b a)
    :else            (= a b)))

(defn covers?
  "selector `wider` 是否**完全覆盖** selector `s`。

   与 `overlap?` 的区别是承重的：`sales.order`（精确）与 `sales.*` 是**重叠**的,
   但 `sales.*` 会选中 `sales.shipment`，而后者不在前者里。「会不会撞车」与
   「是不是全在里面」是两问，用错一个就等于让前缀 selector 悄悄多选。

   （原文说的是 namespace 地盘与菜谱 selector 的关系；地盘制度随 D3.17 / D3.22
   退役，判定本身是纯粹的 selector 代数，留着。）"
  [wider s]
  (boolean
   (and (canonical? wider) (canonical? s)
        (if (prefix-form? s)
          (and (prefix-form? wider) (str/starts-with? (prefix-of s) (prefix-of wider)))
          (matches? wider s)))))

(defn namespace-of
  "对象 id 所在的 namespace / 词表域，取不出给 nil。

   - 全限定属性 kw（D2.17）→ 它的 namespace 段；
   - 注册表 ID 字符串带域（`common.audit/g_x`）→ 域；不带域的裸 ID → **nil**,
     它不属于任何 namespace，于是既不可能越界也不是「无人认领」（见 impl.mirror）。"
  [x]
  (cond
    (qualified-keyword? x) (namespace x)
    (string? x)            (when-let [i (str/index-of x "/")] (subs x 0 i))
    :else                  nil))
