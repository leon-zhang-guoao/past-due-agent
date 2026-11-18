---
title: "Studio 1.3.0"
description: "Studio 1.3.0 由拆分前的单仓以统一版本 platform-1.3.0 发布；本库从同一份代码起步，版本从 1.3.0 继续。"
---

# Studio 1.3.0（2026-10-07）

**本版本不是在本库发布的。** Studio 1.3.0 由拆分前的单仓 `open-atlas-platform` 以统一标签 `platform-1.3.0`（提交 `d9f0753a`，镜像
`open-atlas-studio:1.3.0`）与 Workbench、Dossier 一起发布，是单仓的最后一次统一发布。

- 相对 Studio 1.2.0 无代码变化（随平台版本重建）；服务管理令牌见 [Studio 1.2.0](1.2.0.zh-CN.md)。
- 2026-10-07 按仓库拆分方案（open-atlas-docs 库 `proposals/repo-split.md`）把 Studio 及其 Clojure 模块迁入本库
  `open-atlas-studio`，代码与 `platform-1.3.0` 相同，`release.json` 的版本因此仍是 `1.3.0`。
- 本库不为 1.3.0 打 `studio-1.3.0` 标签，也不重新发布；`platform-1.3.0` 的可复现构建留在旧单仓。
  本记录因此**故意不叫** `1.3.0.zh-CN.md`：`./release.sh` 以「当前版本的发布记录已提交」为发布前提，
  这里没有 `1.3.0.zh-CN.md`，它就会拒绝在 1.3.0 上打标签。
  下一次 Studio 有变化时升版本（如 1.3.1 / 1.4.0），用本库的 `./release.sh` 发布，标签 `studio-<版本>`。
