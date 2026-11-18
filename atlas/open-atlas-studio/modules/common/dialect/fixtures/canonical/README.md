---
title: "dialect conformance fixtures"
description: "B0 黄金向量：canonical/ 下每个 .edn 是规范形，往返必须逐字节相同。"
---

约定（实现顺序第 7 步逐条填充，种子取自 dialect-v1 §5）：

- `canonical/*.edn` —— 规范形文件：`(write (read f)) == f` 逐字节；
- `variant/<名>/*.edn` —— 同名 canonical 的等价变体（乱序键、`#:ns{}`）：
  format 后必须与 canonical 同字节同 hash；
- `reject/*.edn` + 同名 `.findings.edn` —— 拒绝用例与期望 findings
  （conformance 矩阵 §7 每条至少一个用例）。

布局规则已落定（dialect-v1 §4「布局」，实现在 `impl/write.clj`）：集合起始列 +
单行宽度 ≤ 100 列即单行，否则 map 一键一行、向量/set 在元素边界换行，续行一律
对齐到开括号后一列；叶子值永不折行。canonical/ 下四个种子取自 dialect-v1 §5，
已按该规则重排。规则再变即规范形变更，须先改 dialect-v1 再重排本目录。
