---
title: "open-atlas-studio"
description: "Open Atlas Studio（Builder + 内嵌 runtime）及其 Clojure 模块的仓库：布局、测试、构建镜像与发布。"
---

# open-atlas-studio

Studio（Builder 加一台内嵌的 runtime instance）与它依赖的全部 Clojure 模块。2026-10-07 按仓库拆分方案
（open-atlas-docs 库 `proposals/repo-split.md`）从单仓 `open-atlas-platform`（`platform-1.3.0`，提交 `d9f0753a`）
迁出，保留了这些路径的提交历史。

## 布局

| 路径 | 内容 |
|---|---|
| `apps/studio` | Studio 应用（Builder UI、HTTP、内嵌 runtime） |
| `apps/server` | 独立 Server，**不发布**（doc 110 §110.6），与 Studio 共用模块 |
| `modules/{common,runtime,studio,server}` | 四个 Clojure 根（D1.8）；边界靠各模块 `deps.edn` 的 `:local/root` 执法 |
| `modules/integration` | 装配点，只有 test |
| `fixtures/` | 测试与镜像用的 fixture（`b1`、`demo`、`dvd`…） |
| `docs/help` | 打进 jar 的帮助页 |
| `docs/contracts/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md` | 打进 jar 的契约正文，**副本**，事实源在 open-atlas-docs 库（见下） |
| `docs/guides/examples` | `apps/studio` 测试读取的示例 |
| `docs/releases/<版本>.zh-CN.md` | Studio 发布记录（1.3.0 见 `platform-1.3.0.zh-CN.md`） |
| `release.json` | Studio 版本与 provides/requires 契约区间 |
| `Dockerfile`、`.dockerignore` | 镜像（构建上下文是仓库根） |
| `release.sh`、`tools.lock` | 发布入口；`tools.lock` 固定 open-atlas-release 的版本 |

## 测试

```bash
clojure -M:test                       # 根目录全量聚合（modules/**）
cd apps/studio && clojure -M:test     # Studio 应用自己的测试
```

新增模块要同时加进根 `deps.edn` 的 `:dev` 与 `:test`（含 `-d` 行）。

## 构建镜像

镜像用 open-atlas-release（版本见 `tools.lock`）构建，它写 `build-info/studio/BUILD.json` 并把该版本的
`load-env.sh` 拷进 `build-info/`——本库不保存 `load-env.sh` 的副本：

```bash
git clone --branch release-tools-$(cat tools.lock) git@github.com:gaojie197710-tech/open-atlas-release.git /tmp/oar
/tmp/oar/scripts/release/build-images.sh studio --source . --platform linux/amd64
```

## 发布

`./release.sh`：读 `release.json` 的版本，要求 `docs/releases/<版本>.zh-CN.md` 已在当前提交里，打 `studio-<版本>`，
在干净 clone 里构建镜像与包并核对 `BUILD.json` 的 commit。1.3.0 是在旧单仓以 `platform-1.3.0` 发布的，
见 [platform-1.3.0](docs/releases/platform-1.3.0.zh-CN.md)；
该记录故意不叫 `1.3.0.zh-CN.md`，所以在 1.3.0 上跑 `./release.sh` 会被拒绝，不会补打 `studio-1.3.0`。

## 契约正文副本

`docs/contracts/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md` 会被 `apps/studio/build.clj` 打进 jar，作为运行时帮助。
release-tools 1.0.0 的 `build-images.sh` 没有「构建前取文件」的钩子，所以这里保留一份副本；
**事实源是 open-atlas-docs 库的同名文件**，改契约先改 docs 库，再把副本同步过来。
