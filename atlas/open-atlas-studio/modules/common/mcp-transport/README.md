---
title: "common/mcp-transport — MCP 的线"
description: "两个 MCP 端点共用的协议层：注入面的形状，与「不认识业务」这条可跑的闸门。"
---

# common/mcp-transport — MCP 的线

立项与阶段计划在 [8.4 Builder MCP](../../../docs/design/84-builder-mcp.md)（P1a）。
本文只记**本模块自己的**两条裁定，不复述那边说过的话。

协议层原先住在 `server/mcp` 的私有 impl 里。Builder 的 MCP 端点要的是同一份协议
加不同的判定，而 server 与 studio 不得互相依赖（[D1.8](../../../docs/design/10-platform-boundary.md#d18-五个-namespace-根依赖严格单向)），
共用的那一半只能住 common——与 [common/toolkit](../toolkit) 同一条理由。

## 裁定

### ① 注入面是**三个函数**，不是一个「认证配置」

`:authenticate` / `:tools-list` / `:call-tool`。三处都刻意是函数：

- `:authenticate` 拿整个 request、还回 `{:id :ctx}`。**`:id` 不许是凭证**——它会被
  SSE 会话表留住，比请求活得长，而活得比请求长的秘密就是一个泄漏点（S5.2）。
  凭证放 `:ctx`，`:ctx` 只在本次请求里流到工具那一侧。
- `:tools-list` 吃 `ctx`。这条是承重的：能力过滤必须与 dispatch 是**同一个**决定
  （toolkit 裁定 ②）。若这里只能接一个固定向量，宿主就得在 dispatch 侧再过滤一遍，
  两处哪天改了一处，「列出来了但调不了」就出现，且要等客户端真调了才看得见。
- `:call-tool` 的**返回 `{:error …}`** 是拒绝（转成 `isError`，agent 读了能改下一次
  调用），**抛异常**是故障。两者绝不合并：把故障讲成拒绝，会诱使 agent 一直重试一个
  它无能为力的东西。

`:unauthorized-message` 也注入。一句通用的 401 会教调用方去找**错的那种钥匙**——
两个端点认的钥匙本来就不是一回事。

### ② 「不认识业务」是一条**跑得出来的**闸门，不是一句承诺

`the-wire-knows-no-business` 扫本模块 src 的每一个字节，见到 graph、workspace、
dialect、resolver、entity、EQL 或 `open.atlas.{runtime,server,studio}.*` 就红。

**连注释和 docstring 一起扫**，这是有意的：写这个模块时，最初那版 ns 注释里为了解释
「为什么要通用」而顺手写了「graph token」「workspace token」，闸门当场就红了。可以给
注释开个例外——但例外清单会长大，而长大的过程每一步看起来都合理。留着严的那一版，
代价只是把注释改成指 8.4；收益是这个模块永远说不出业务的词。

判定注入不出去，这条测试就红；红了说明协议与判定又长回一块，而那正是下沉要防的事。

## 不在这里的东西

- **ring 适配器**：流式响应体的形状属于宿主的 HTTP 适配器，走 `:sse-writer` 注入；
- **限流的实现**：`:rate-limit?` 缺省就是不限流——一个**看得见的配置事实**，
  比一个静默的默认值诚实；
- **instructions 文本**：那是宿主对 agent 说的话，各端点各说各的。
