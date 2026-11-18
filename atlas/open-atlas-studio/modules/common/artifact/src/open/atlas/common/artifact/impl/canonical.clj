(ns open.atlas.common.artifact.impl.canonical
  "规范形文本与三种摘要的唯一产出点（D4.3）。

   ## 为什么复用 dialect 的 writer 而不自己写一份

   D4.3 的序列化规则与 dialect-v1 §4 **同源**：map 键字典序、set 字典序、
   UTF-8 无 BOM、`\\n`、keyword 全限定不缩写、整数不带类型后缀。写第二份
   实现就等于让「同源」变成一句需要人去维护的口头承诺——两份逐字节相同的
   writer 今天没事，改一处忘另一处的那天，同一个模型在 studio 与 server
   算出两个 content-hash，而谁都不知道错在哪。`open.atlas.common.dialect/
   write-dialect` 是公共接口，直接用它。

   顺带的好处：`content.edn` 里 `:objects` 那一段与手写方言文件的规范形
   **逐字节相同**，D4.6「模型内容的解析规则只有一份实现」在文本层也成立。

   ## print var 全部钉死（principles「revision 是内容的纯函数」）

   writer 的叶子值走 `pr-str`，而 `pr-str` 受一组动态 var 影响。**产文本处**
   是这里，所以钉死在这里：

   | var | 不钉死会怎样 |
   |---|---|
   | `*print-namespace-maps*` | map 被写成 `#:ns{}`，同一内容两个线程两个 hash（D4.3 点名的那次教训） |
   | `*print-length*` | 集合被截断成 `...`，hash 覆盖不到全部内容 |
   | `*print-level*` | 深层结构被写成 `#`，同上 |
   | `*print-readably*` | 字符串不转义，换行/引号直接进文本，读回来是另一个值 |
   | `*print-dup*` | 数字带类型后缀（`1M`/`1N`），违反「整数不带类型后缀」 |
   | `*print-meta*` | 元数据混进文本，而元数据不是内容 |

   这些 var 是**线程绑定**的：REPL、测试 runner、future、agent 各自可能带着
   不同的值。不 binding 就等于让 hash 依赖调用者的线程状态——那正是「纯函数」
   要排除的东西。

   浮点用最短往返表示（Clojure `pr-str` 的既有行为，与 locale 无关）；整数是
   `Long`，`pr-str` 不加后缀。二者都不需要额外处理，但**需要测试钉住**——
   见黄金向量里的数值边界那一条。"
  (:require [open.atlas.common.dialect :as dialect])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def canonical-str
  "任意规范化 EDN 值 → 规范形文本（尾随单个 `\\n`）。

   同一内容在任何线程、任何新进程、任何机器产出同一份文本——这是 D4.3
   「hash 是内容的纯函数」的全部依据，也是它的**测试义务**。

   **实现在 `dialect/canonical-str`**，这里只是再导出：控制面的签名输入
   （D3.16）要的是同一份规范化，而各钉一遍 print var 就是各有一份「什么叫
   同一份内容」的定义。见那边的 ns 文档。"
  dialect/canonical-str)

(defn utf8-bytes
  "规范 UTF-8 字节：无 BOM（`String/getBytes` 从不产 BOM），`\\n` 由 writer 保证。"
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- hex
  "小写 hex。**不用 `Integer/toHexString` 直接拼**：它会把 0x0a 写成 `\"a\"`，
   丢掉前导零的摘要与另一份内容的摘要可以相撞。"
  [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (when (< b 0x10) (.append sb \0))
        (.append sb (Integer/toString b 16))))
    (.toString sb)))

(defn sha256-hex
  "字节 → 64 位小写 hex，不带算法前缀（D4.3 的 `file-hash`）。
   `MessageDigest` 实例**不共享**：它有内部状态，共享即线程不安全。"
  [^bytes bs]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bs)))

(defn prefixed
  "`sha256:` + 小写 hex。摘要算法定为 SHA-256（D4.3）——`sha256sum` 就能
   验一个制品，不需要平台自己的工具。前缀是算法标识，不是装饰：将来换算法
   时，旧值仍然自述它是用什么算的。"
  [hex-str]
  (str "sha256:" hex-str))

(def hash-pattern
  "`sha256:` + 恰好 64 位小写 hex。大写 hex 不是合法拼法（规范形唯一，D2.12
   的同一条纪律）——否则同一个摘要有两种写法，而它自己要进 content-hash。"
  #"^sha256:[0-9a-f]{64}$")

(defn hash-str? [s]
  (boolean (and (string? s) (re-matches hash-pattern s))))

(defn content-hash-of-text
  "`content.edn` 的规范化 UTF-8 字节 → content-hash（D4.3）。

   **哈希整份文件字节，不把字段分段拼接**：分段拼接要额外定义分隔符、字段
   顺序与组合方式，每一处都是歧义来源。文件字节是唯一的、已经被规范形钉死的
   东西——`:objects` 与 `:references` 因此自然全部进入身份。"
  [^String content-edn]
  (prefixed (sha256-hex (utf8-bytes content-edn))))

(def digest-file-order
  "artifact-digest 的固定文件顺序（D4.3）。顺序是算法的一部分，不是巧合。"
  ["content.edn" "manifest.edn" "report.edn"])

(def ^:private nul (str (char 0)))

(defn artifact-digest
  "三份文件的规范文本 → artifact-digest（D4.3）。

   逐份拼接 ASCII `filename`、NUL、`file-hash`（64 位小写 hex，不带前缀）、
   LF，对拼接字节算根 SHA-256，编码为 `sha256:` + 小写 hex。**NUL 分隔**让
   文件名与 hex 不可能互相渗透（文件名里不可能有 NUL），拼接因此无歧义——
   这是「不分段拼接」那条的唯一例外，而它拼的是三个定长摘要与三个固定文件名，
   不是内容本身。

   digest **不写回三份文件**（否则自引用），由切版本后不可变的制品登记记录
   保存，并随经过认证的部署控制信封传输（D4.11）。"
  [texts]
  {:pre [(= (set digest-file-order) (set (keys texts)))]}
  (let [lines (for [fname digest-file-order]
                (str fname nul (sha256-hex (utf8-bytes (get texts fname))) "\n"))]
    (prefixed (sha256-hex (utf8-bytes (apply str lines))))))

(defn file-hashes
  "三份文件各自的 `file-hash`——供 digest 不符时点名**是哪一份**变了
   （D4.6：装载失败必须点名哪个文件）。"
  [texts]
  (into (sorted-map) (for [[f t] texts] [f (sha256-hex (utf8-bytes t))])))

(defn canonical-str-or-error
  "`canonical-str` 的不抛版：`[文本 nil]` 或 `[nil 消息]`。

   dialect 的 writer 对不在方言值域内的类型与被书写的缺省值抛异常
   （它不是第二道校验门）。制品的 manifest/report 收的是**调用方合成的**
   发布上下文，一个不小心塞进去的 `java.util.Date` 不该让 `seal_graph`
   炸栈——它该变成一条点名文件与位置的 finding（D4.6）。"
  [v]
  (try
    [(canonical-str v) nil]
    (catch Throwable t
      [nil (or (.getMessage t) (str (class t)))])))
