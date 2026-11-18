(ns open.atlas.common.connector.spi.impl.redact
  "不泄漏截断——**委托给 dialect 的公共接口，本模块不持第二份实现**。

   finding-v1 §5 说这条「在 B1『连接器故障的 ex-data 不含语句/URL/行值/凭证』
   处复用**同一个**截断函数」。曾经这里复制过一份五行的等价实现，理由是
   dialect 的那份住在 impl.* 私有 ns 里、公共接口没导出——顾虑成立，
   但结论错了：正解是把它摆到 dialect 的接口上（已做），不是各写一份。
   两份逐字节相同的实现今天没事，改一处忘另一处的那天就是泄漏。"
  (:require [open.atlas.common.dialect :as dialect]))

(defn redact
  "把任意值降为**类型描述**：字符串给长度、集合给规模、标量原样。
   任何可能是语句/URL/行值/凭证的东西都是字符串或集合，因此都被降级。"
  [v]
  (dialect/redact v))
