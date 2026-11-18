---
title: "connector-api-kit —— api 来源接入套件"
description: "录制可重放的脱敏 fixture、按模型自动生成 conformance 套件、CLI（record / verify / redaction-check）；30 分钟接一个新 API。"
---

# connector-api-kit

一个新的 api 来源，怎么拿到和 Schneider 那套一样的回归保护——**不靠人再手写一份
测试**。

- **录制器** `record!`：让连接器走它平时的路径真调一次上游，把请求形状与**脱敏后的**
  响应体写成一份确定性排序的 EDN fixture；
- **重放桩** `with-replay` / `replay-responder`：用 fixture 顶替 `clj-http.client/get`
  与 `/post`（与连接器自己的测试同一个 `with-redefs` 接缝），带**失败叠加**
  （状态覆盖、`Retry-After`、`Link`、涓流体、链式游标）；
- **生成器** `conformance-tests` / `defconformance`：从 operation 块或 Semantic 1.4
  文档的 api binding 推出一套 `clojure.test`；
- **CLI**：`record` / `verify` / `redaction-check`。

跨模块只 require `open.atlas.common.connector.api-kit`；`.impl.*` 私有。依赖只有
connector-api / connector-spi / dialect——**故意的**：一个只装着一张图的来源仓库要跑得动
这套测试（D1.8）。

## 30 分钟接一个新 API

### 1. 写一个 plan 文件（5 分钟）

```clojure
;; fixtures/record-plan.edn —— 录制是要真调上游的，所以它该是一份可 review 的文件，
;; 不是一行 shell 历史。
{:source-id    "acme-api"
 :operation-id "acme-order-detail"
 :source-config {:type "api"
                 :base_url "https://api.acme.example"
                 :auth {:type "api_key" :value "${ACME_KEY}"}
                 :method "GET"
                 :endpoint "/v1/orders/{acme.order/id}"
                 :data_path "$"
                 :field_mapping {:acme.order/id "$.id"
                                 :acme.order/status "$.status"
                                 :acme.order/total "$.amounts.total"}}
 :resolver-config {:id "acme_order_detail"
                   :input [:acme.order/id]
                   :result_type "single"}
 :samples [{:inputs [{:acme.order/id "A-1001"}]}
           {:inputs [{:acme.order/id "A-1002"}]}]
 :secret-paths ["$.customer.email"]
 :out-dir "fixtures"}

;; 上游怎么表达失败，由 source 说（doc 107 §107.5）——写进 :source-config 的
;; :status-map，生成器据此决定生成哪几条 deftest：
;;   :status-map {404 :not-found 422 :input-rejected}
```

`${ACME_KEY}` 由**连接器自己**从进程环境解析（api-v1「运行期由连接器自己替换」）；
kit 既不读它也不打印它。

### 2. 录（1 分钟）

```bash
ACME_KEY=... clojure -M:kit record fixtures/record-plan.edn
# → fixtures/acme-api/acme-order-detail.fixture.edn （2 exchange）
```

### 3. 查（10 秒，**每次录完都跑**）

```bash
clojure -M:kit redaction-check fixtures
# api-kit redaction-check: clean          ← 0 退出
# 有命中时只打印 文件 + 路径 + 规则名，绝不打印值；退出码 1
```

### 4. 生成套件（3 行）

```clojure
(ns acme.conformance-test
  (:require [clojure.test]
            [open.atlas.common.connector.api-kit :as api-kit]))

(def source-def {:id "acme-order-detail" :source-id "acme-api"
                 :source-config …   ; 与 plan 里同一份
                 :resolver-config … })

(api-kit/defconformance acme-order-detail source-def "fixtures")
```

跑 `clojure -M:test`。Semantic 1.4 文档更省事——`semantic-source-defs` 把每个 api read
binding 直接变成一个 source-def，见 `test/.../se_conformance_test.clj`：四行换来 38 条
生成的 `deftest`。

或者不写测试文件，直接：

```bash
clojure -M:kit verify 路径/contract-1.4.edn fixtures
```

## fixture 长什么样

```clojure
{:api-kit/version 1
 :api-kit/provenance {:connector-version "v1" :contract "api-v1"
                      :operation "acme-order-detail" :recorded-at "2026-09-18T…"
                      :redaction-rules "sha256:…" :source "acme-api"}
 :api-kit/samples [{:inputs [{:acme.order/id "A-1001"}]}]
 :api-kit/rules {…}                      ; 这次录制用的规则，原样写下
 :api-kit/exchanges
 [{:method "GET"
   :endpoint "/v1/orders/{acme.order/id}"   ; 模板，**不是**解析后的 URL
   :query-param-names [...]                 ; 名字，不是值
   :match {:method "GET" :path "/v1/orders/A-1001" :query {}}
   :status 200
   :headers {"Content-Type" "application/json"}   ; 白名单外的一律丢
   :body {…已脱敏…}}]}
```

**排序是确定性的**：map 排序写出、exchange 按 `[method path query status]` 排。同一个
端点重录一次，除了 `:recorded-at` 之外逐字节不变——真正的变化才会在 diff 里看得见。

## 脱敏规则

按顺序，先命中先算：

| 顺序 | 规则 | 结果 |
|---|---|---|
| 1 | `:secret-paths` 声明的路径 | `<secret>`（**压过** keep 清单） |
| 2 | 形状探测：邮箱 / JWT / `Bearer …` / `sk-`·`ghp_`·`AKIA` 前缀 / PEM 私钥 / **未解析的 `${VAR}`** / 40 字以上无空白的高熵串 | `<email>` 或 `<token>`，**keep 路径下也照打** |
| 3 | 超过 `:max-string`（缺省 64）且路径不在 keep 清单 | `<len:123>` |

**keep 清单缺省 = `:field_mapping` 的全部叶子路径**（含分支作用域与 `:_from` 记录内集合）
——它们正是生成的套件要断言的值，脱掉就等于录了一份「证明连接器什么都取不到」的
fixture。`$.a.0.b` 这类点号下标按 `*` 记，**录的是第 0 个元素，第 3 个元素照样保得住**。

`:detail_raw "$[*]"` 这种整条记录的逃生口会让 keep 清单变成「整条留下」，此时只有第 1、2
条仍然生效——这是**有意的**：模型自己说了它要整条记录。

响应头白名单：`Content-Type`、`Link`、`Retry-After`、`X-RateLimit-*`。`Set-Cookie` 与
`WWW-Authenticate` 带凭证，`Date`/`Server`/`ETag` 只会让每次重录都变成一个 diff。

## 失败叠加（overlay）

录制录得到的是上游**真的答过**的东西。401/429/503、超时、翻页链多半不在其中，而且
对着伙伴的生产端点去**制造**这些故障不是测试套件该做的事。所以它们是叠加在已录
exchange 上的：

```clojure
{:overlays [{:when {:placeholder "A-1002"} :status 404}      ; 只废掉这一个身份
            {:status 429 :retry-after 2}
            {:trickle {:prefix "{\"items\":[" :delay-ms 50}} ; 头 + 半个体 + 静默
            {:when {:nth 1} :link "<…?cursor=2>; rel=\"next\""}
            {:when {:nth 2} :body {:items []}}]}             ; 链式：同一请求的第 2 个答案
```

`:when` 支持 `:placeholder`（值出现在请求里）、`:param`（这个参数名在）、`:nth`（第几次）。

## 匹配与拒绝

先按 **method + path + 完整 query（值也比）** 精确匹配；不中时退回
**method + path + 参数名集合**——batch 隔离二分出来的子请求带着谁都没录过的 id 子集，
这一层退让正是它们能重放的原因。

两层都不中 → **结构化拒绝**：点名 method / path / 参数名与已录的形状，**绝不回显值**。
不落网、不回空体——回空体会把「fixture 过期」伪装成「连接器什么都没返回」。

## 生成器：能力与断言

完整能力表见 [docs/guides/api-onboarding.md](../../../docs/guides/api-onboarding.md)。
要点：**生成器没有自己的意见**——哪个状态码是「这个输入不在」读 `:status-map`
（doc 107 §107.5），429 重不重试读四级合并后的 `:resilience`（§107.3），
集合怎么翻页读 `:collection :paging`（§107.6）。来源没声明的能力**不生成测试**。

`semantic-source-defs` 复刻 `runtime/env` 的 `apply-policies`：slot 的
`:source/policy` + binding 的 `:policy` 覆盖 + 部署侧 `:resilience` 容量半，合并用的是
SPI 自己的 `effective-policy`（合并规则不写第二遍）；图声明了 `:source/policy-v1` 时
把 `:semantic/attempt-total-timeout?` 置上，`:timeout_ms` 于是按
[api-v2](../../../docs/contracts/connectors/api-v2.md) 读。

## 本模块自己的测试

```bash
cd modules/common/connector-api-kit && clojure -M:test
```

`kit_test.clj` 管脱敏 / 匹配 / 叠加 / 录制确定性，另外拿四种合成的分页来源
（cursor / link-header / offset / page，外加一个带 `:snapshot-path` 的）跑分页那一组用例
——SE 声明的是 `:paging :none`，五种策略只能在这里证。
`se_conformance_test.clj` 拿真实的 SE v3 Semantic 文档（只读）和 `fixtures/se/` 跑生成的
**44 条 `deftest`**，**不开 socket**。
