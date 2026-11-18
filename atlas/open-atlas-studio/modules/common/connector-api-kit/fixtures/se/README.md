---
title: "SE fixture（api-kit 录制形）"
description: "connector-api-kit 为 SE v3 Semantic 文档四条 api binding 录制的可重放 fixture：怎么来的、值是合成的还是实测的、怎么重录。"
---

# SE fixture（api-kit 录制形）

`se-catalog-api/*.fixture.edn` 是 `api-kit/record!` 为
`业务案例/se/history/v3/se-product-semantic-contract-1.4-v3.edn`（2026-09-30 起移入 history，现行为 `业务案例/se/graph/se-product.edn`） 的**四条 api read binding**
录下的 fixture，`se_conformance_test.clj` 用它跑生成的 44 条 `deftest`，**不开 socket**。

它与 `modules/common/connector-api/fixtures/se/` 那份不是一回事：
那份是**语料**（api-v1 形状的 `graph.edn` 与一份样本响应），这份是**录制产物**——
请求形状 + 脱敏后的响应体 + provenance 头。

## 值从哪来

| binding | 响应体 | 出处 |
|---|---|---|
| `se-detail-api` | `sample-detail.json` 原样 | 既有语料的样本响应（结构取自 2026-08-12 实测，值多为 `SAMPLE-` 合成，见那份 README） |
| `se-gtin-api` | 同上 | 同一份负载，按 GTIN 端点的映射读 |
| `se-index-api` | 三条 `identifiers.commercialReference` | **合成**，形状取自 `verify-v3.clj` 的 index 用例 |
| `se-delta-api` | 三条带 `delta` 的记录（NEW / UPDATE / DELETE） | **合成**，形状取自 `verify-v3.clj` 的 delta 用例 |

**没有一次真实上游调用**：录制走的是 `record!` 的 `:transport` 口子，喂的是上面这些
负载。所以这份 fixture 证明的是「连接器 + 生成器对着这个形状表现正确」，
**不是**「Schneider 今天这么答」。真要后者，得拿着凭证对着 `api.se.com` 重录一次。

## 怎么重录

```bash
# 合成重录（离线，本仓库用的就是这条）
clojure -M:dev <一个照着本 README 写 :transport 的脚本>

# 对着真实上游（需要 SE_CLIENT_ID / SE_CLIENT_SECRET，且会计入对方限流）
SE_CLIENT_ID=… SE_CLIENT_SECRET=… \
clojure -M:kit record <plan.edn>
clojure -M:kit redaction-check modules/common/connector-api-kit/fixtures/se
```

重录后 `:recorded-at` 会变，其余部分若也变了，那就是**真的变了**——去看 diff，
不要直接提交。

## 生成器从文档里读到了什么

fixture 只记**线上发生过什么**；生成哪几条用例由 SE 契约自己决定（doc 107）：

- `se-detail-api` 的 `:status-map {400 :input-rejected 404 :input-rejected}` →
  `row-outcome` / `batch-isolation` / `batch-isolation-budget` 三条；
- slot `se-catalog-api` 的 `:source/policy {:retry_on #{:rate_limited :unavailable}
  :max_retries 1}` → `failure-rate-limited` 断「恰好重试一次、等足 `Retry-After`」；
- 四个 resolver 的 `:collection :paging :none` → `paging` 断「`Link: rel=next` 永不跟进」；
- 文档声明了 `:source/policy-v1` → `:timeout_ms` 按 api-v2 读（attempt 总时限）。

## 脱敏

`:api-kit/rules` 就写在每份 fixture 里，`:redaction-rules` 是它的摘要。
`se-detail-api` 的 keep 清单里有一条 `["*"]`：那来自模型自己的
`:detail_raw "$[*]"`（整条记录的逃生口），所以长度上限对这份负载不生效——
形状探测（邮箱 / token / 未解析的 `${VAR}`）仍然逐字段生效，
`the-fixtures-are-clean` 每次跑都在验这一条。
