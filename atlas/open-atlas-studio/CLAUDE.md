# open-atlas-studio — 本库约定

通用约定（绝不做的事、拒绝结构化、决策编号）以 open-atlas-docs 库的 `CONVENTIONS.md` 为准。本库差异：

- 代码、注释、标识符英文；README、`docs/**` 中文。值本身要求中文的（demo 模型 label、测试查询串）除外。
- 模块边界 = classpath 事实：各模块 `deps.edn` 用 `:local/root`；跨模块只 require 接口 ns（`.clj-kondo/config.edn`
  的 `:discouraged-namespace`）。新增模块同时加进根 `deps.edn` 的 `:dev` 与 `:test`（含 `-d` 行）。
- 测试：根目录 `clojure -M:test`；Studio 应用 `cd apps/studio && clojure -M:test`。
- 不改 `fixtures/**` 让测试变绿。
- 契约与设计日志住 open-atlas-docs；注释按编号引用（`D2.25`、`data-plane-api-v1`），不按路径。
  `docs/contracts/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md` 只是打进 jar 的副本。
- 发布：更新 `release.json`、写 `docs/releases/<版本>.zh-CN.md` 并提交，再跑 `./release.sh`（标签 `studio-<版本>`）。
  不在本库复制 open-atlas-release 的任何文件（含 `load-env.sh`）；升级工具只改 `tools.lock`。
