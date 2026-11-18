---
title: "可导入的图制品包"
description: "三个 zip 制品：DVD demo、CIS demo、本地 NiFi。每个的图 id 与版本、需要的 ${VAR} 与文件、导入命令，以及已实测的可移植性缺口。"
---

# 可导入的图制品包

这里的每个 zip 就是 Studio `导出制品` 那一条路吐出来的东西（`GET /api/studio/graphs/export`）：
`<workspace>/<graph>/<version>/{content.edn,manifest.edn,report.edn}`，条目时间戳固定，
所以同一个版本导两次字节相同。**目录名只是方便，不是权威**——接收方从 `manifest.edn`
的 provenance 读坐标（D4.13）。

放在仓库里是为了两件事：Server 可以在**没有 Studio 在场**的情况下拿到一张图；
发布包里带着它们（[8.16.2](../../docs/design/103-package-plan-and-install.md)），
解压就有东西可导。

## 三个包

| 文件 | 发布名 | 图 id | 版本 | 来自 | 需要什么才跑得起来 |
|---|---|---|---|---|---|
| `open-atlas-dvd.zip` | `open-atlas/dvd` | `demo/dvd` | 1 | `fixtures/dvd/model.edn` 播种的一台干净 Studio，**未改一字**地 cut | SQLite 文件 `data/open-atlas-demo.db` |
| `open-atlas-cis.zip` | `open-atlas/cis` | `demo/cis` | 1 | `fixtures/demo/model.edn` 播种的一台干净 Studio | SQLite 文件 `data/cis-demo.db` |
| `open-atlas-nifi-local.zip` | `open-atlas/nifi-local` | `open-atlas/nifi-local` | 15 | 一台 scratch Studio 对着 NiFi 2.11.0，用需求语法在**空池**里逐句说出并切出的第 15 版；在第 14 版之上，**身份可以包含被带进这一行的属性**（§106.14 第 18 条）——每一个「按实例才唯一」的组件都以 `[host id]` 认，而且**读回来也是** `[host id]` | 一台可达的 NiFi + 客户端证书 + 一个 `${VAR}` |

## `:package` 块（doc 104 §8.17.7 A，2026-09-06）

三个包的 manifest 现在都带 `:package`：**发布名**与「这台机器上必须已经有什么」，
**只有名字，从不带值**。装之前拿它比对本机，缺则拒绝（§8.17.7 C 的两步安装）。

```clojure
;; open-atlas-dvd.zip
:package {:compat {:connectors {"sql" "v1"} :dialect "v1"}
          :name "open-atlas/dvd"
          :requires {:files ["data/open-atlas-demo.db"] :vars []}}
;; open-atlas-cis.zip
:package {:compat {:connectors {"sql" "v1"} :dialect "v1"}
          :name "open-atlas/cis"
          :requires {:files ["data/cis-demo.db"] :vars []}}
;; open-atlas-nifi-local.zip
:package {:compat {:connectors {"api" "v1"} :dialect "v1"}
          :name "open-atlas/nifi-local"
          :requires {:files ["studio/nifi-client.p12" "studio/nifi-truststore.p12"]
                     :vars ["NIFI_CLIENT_STORE_PASS"]}}
```

**发布名与图 id 是两件事**（§8.17.7 D）：`:package :name` 是这张图**出厂叫什么**，
由图登记上的 `:package-name` 决定（demo 两张来自 `fixtures/*/graphs.edn`，是建模内容）；
图 id 仍然取自 content 的 provenance。**第 8 版起 nifi 那张的图 id 不再是一个人的邮箱**（第 9 版同）：
重建是在一个名叫 `open-atlas` 的 workspace 上从空图做的，所以发布名与图 id 终于是同一个
`open-atlas/nifi-local`（缺口 2 因此销项，§8.17.7 D 末段那条特例不再需要）。

**三个包都是重新 cut 的**（同一条 Studio cut 路，`cut-at` 因此是新的）。

三个都实测导入过一台从源码起的 Server 并各查了一次（见文末「实测」）。

## 导入

```bash
BASE=http://127.0.0.1:3009

# 登录控制台（第一个操作员用 /api/console/auth/register 建）
curl -s -X POST $BASE/api/console/auth/sign-in -c cookies.txt \
     -H 'content-type: application/edn' \
     --data '{:username "ops" :password "…"}'

# 导入：bundle 是 zip 的 base64
python3 -c "import base64;print('{:bundle \"'+base64.b64encode(open('open-atlas-dvd.zip','rb').read()).decode()+'\"}')" \
  > /tmp/import.edn
curl -s -X POST $BASE/api/console/graphs/import -b cookies.txt \
     -H 'content-type: application/edn' --data-binary @/tmp/import.edn
# → {:graph-id "demo/dvd" :status :ok :version 1}
```

导入成功即置 enabled；随后 `POST /api/console/tokens/issue`（body `{:graph-id …}`）
签一张 graph token 就能查 `/eql`。完整链路见[建图与 token 授权](../../docs/guides/build-graph-and-tokens.md)。

**界面上**：Server 控制台（默认 `http://127.0.0.1:3009/`）的 Graphs 页有「导入」，
选这里的 zip 即可，走的是同一条 offer → stage → load → activate。
**Studio 侧目前没有导入**——它是[8.16.1 第 5 条](../../docs/design/103-package-plan-and-install.md)
明确待裁的事项，别去找那个按钮。

## `open-atlas-nifi-local.zip` 需要的变量与文件

content.edn 里只有一个 source，它是全部的外部依赖：

```clojure
"NiFi 本地实例 (2.11.0)"
{:type            "api"
 :base_url        "https://{nifi.instance/host}:9443/nifi-api"
 :health_endpoint "/flow/about"
 :tls {:keystore        "studio/nifi-client.p12"     :keystore_type   "PKCS12"
       :keystore_pass   "${NIFI_CLIENT_STORE_PASS}"
       :truststore      "studio/nifi-truststore.p12" :truststore_type "PKCS12"
       :truststore_pass "${NIFI_CLIENT_STORE_PASS}"}}
```

- **`${VAR}`：只有 `NIFI_CLIENT_STORE_PASS` 一个**，keystore 与 truststore 共用它。
  在 **Server** 上它必须在**进程环境里**（`<home>/.env`，或编排器导出）——Server 没有
  凭证库。在 **Studio** 上它可以填进凭证库（doc 106 §106.14 第 12 条，2026-09-07 裁定：
  凭证库按变量名存值，**库优先、进程环境其次**，改库即生效不必重启）。
  导入前先跑 `POST /api/console/graphs/import/plan`：它按包里的 source 条目逐个报
  「需要哪些变量、本机有没有」，缺就以 `:package/missing-var` 拒绝而不上架。
- **两个文件**：`nifi-client.p12`（客户端证书 + 私钥）与 `nifi-truststore.p12`
  （信任 NiFi 那张自签证书）。
- **相对路径锚在哪**：`studio/…` 这种相对路径由连接器锚到
  `$OPEN_ATLAS_CONFIG_PATH` → `$OPEN_ATLAS_HOME` → `~/.open-atlas`
  （规则只有一处实现：`connector-spi` 的 `workspace-base-dir`，连接器与安装计划共用）。
  所以这两个文件要放在 `<锚点>/studio/` 下，三者都没设时即 `~/.open-atlas/studio/`。
- **`:base_url` 的主机是一个属性**（第 12 版起）：`{nifi.instance/host}` 在 authority 段，
  查询时由 `:nifi.instance/host` 绑定（api-v1「`:base_url` 的占位符」，owner 2026-09-07
  裁定 doc 106 §106.14 第 11 条）。**一个 source、一张图，查询时说哪一台**——实例清单
  是模型里那份手写花名册（localhost / nifi-01.corp.local / nifi-02.corp.local）。
  该 source **不能整体体检**（健康检查以 `:connector.source/templated-health` 结构化
  拒绝），要体检给一个样本主机。第 11 版及以前 `:base_url` 写死 `localhost:9443`。
- 证书怎么**签发**：见你们的 PKI 流程；本仓库不提供签发步骤。
  十七条 resolver 怎么建，见[接入 NiFi](../../docs/guides/connect-nifi.md)。

## 已知的可移植性缺口

这三条都是**实测**出来的，不是推断：

1. ~~**两个 sql 包指着相对路径，而导入端按进程工作目录解析它。**~~ **已修**
   （owner 2026-09-06 裁定，doc 104 §8.17.7 B/H 第 1 条）。
   `demo/dvd` 的 source 仍是 `jdbc:sqlite:data/open-atlas-demo.db`、`demo/cis` 仍是
   `jdbc:sqlite:data/cis-demo.db`——**包不改写，包靠锚点**：相对的 sqlite 路径现在
   与 api 的证书路径同锚（`$OPEN_ATLAS_CONFIG_PATH` → `$OPEN_ATLAS_HOME` →
   `~/.open-atlas`），不再随 Server 从哪个目录启动而变。
   所以库文件放到 `<锚点>/data/` 下即可；缺文件也不再要等到第一次查询才知道——
   安装计划会以 `:package/missing-file` 点名拒绝（§8.17.7 C）。
   「导入时设定变量 / 路径」仍是 8.16.1 第 5 条待裁的事。
   （「导入示例」那条路照旧在密封前把 `:jdbc_url` 换成绝对路径——那是 demo 的
   既有例外 8.14.3，**通用导入仍然不做任何重写**，因为 bundle 是密封件。）

2. ~~**NiFi 包的 workspace 段是一个人的邮箱。**~~ **第 8 版已修。**
   第 7 版之前它是 `jie.gao@tdsynnex.com/nifi-local`，而且不只是目录名——
   `manifest.edn` 的 provenance 里也是这个 workspace，导入后图 id 就叫那个。

   **2026-09-06 E5 重建时找到的路**：`personal-workspace-name` 确实只会把邮箱小写
   （`apps/studio/.../identity.clj`），HTTP 面也确实没有建 workspace 的路由——但
   **`POST /api/auth/register` 收一个 `workspace` 参数**（`bootstrap-admin!`），
   所以在一台**尚未 bootstrap 的** Studio 上注册第一个操作员时可以直接把 workspace
   叫成 `open-atlas`。第 8 版就是这样切的：发布名与图 id 现在都是
   `open-atlas/nifi-local`。代价是这条路只有 bootstrap 那一次可走——一台已经初始化
   的 Studio 仍然没有建 workspace 的办法，8.17.2 第 4 条还是要裁。
   （第 9 / 10 版走的是同一条路，图 id 仍是 `open-atlas/nifi-local`。）

3. ~~**`fixtures/dvd/model.edn` 原样切不出版本。**~~ **已修**（owner 2026-09-06 裁定，
   [doc 104 §8.17.6](../../docs/design/104-graph-packages-and-market.md) OQ-DVD-CUT）。
   曾经一台按它播种的干净 Studio 上 `cut-check` 给 20 条 error：17 条
   `:cut.attribute/unregistered-use`（`dvd.store.*`、`film_actor.*` 等没有 resolver 产出）
   与 3 条 `:cut.resolver/unsigned-named-query`（无锚 list 缺 `:curated? true`，D2.13），
   于是本包只能在 scratch 池里改三处之后才切得出来——那份说明已经过期。
   fixture 现在**补了 resolver**（`dvd_all_stores` / `dvd_store_by_id` / `dvd_store_staff` /
   `dvd_staff_by_id` / `dvd_film_cast_credits`，另有四条既有 resolver 带上外键列），
   属性与 7 条 link 一个没删。**本包就是从修好的 fixture 经 Studio 正常 cut 出来的**：
   `cut-check` 回 `{:would-cut? true :health-findings [] :findings []}`，
   密封路（「导入示例」）与 cut 路现在得到同一张图。

4. ~~**实例的四个第二来源仍然是片段。**~~ **第 12 版已归位**（owner 2026-09-07 裁定
   doc 106 §106.14 第 11 条的 A2 补充「键出现在 URL 模板里视为已绑定」）：`/flow/about`、
   `/system-diagnostics`、`/flow/status`、`/flow/process-groups/root` 四条各以一句
   `add_attribute … from=… sample={"nifi.instance/host" "localhost"}` 说成**实例自己的
   属性**，四个 `nifi.instance.*` 片段因此消失，读回来只剩历史点那一个 `:series` 片段。
   下面是第 9–11 版的原文，留作那三版的事实。

   **第 10 / 11 版的旧状况：** 目标形状（doc 106 §106.14 第 2 条）
   要 `/flow/about`、`/system-diagnostics`、`/flow/status`、`/flow/process-groups/root`
   四条都变成**实例这个东西自己的属性**。第 9 / 10 版都按任务书先真试了一次 `add_attribute`
   带 `from`，**两次都被连接器拒，逐字相同**——第二来源的 resolver `:input` 是实例的身份属性 `host`，
   而 `/flow/about` 的记录里没有叫 `host` 的字段：

   ```clojure
   {:code :tools.intent/unknown-column :severity :error :path [:input]
    :message "this relation carries no typeable column by that name"
    :got ["host"]
    :expected ["buildTag" "contentViewerUrl" "timezone" "title" "uri" "version"]}
   ```

   所以这四条退回根集合片段（`nifi.instance.about` / `.jvm` / `.flow` / `.root`），
   实例本身仍只有花名册给的 `host` / `name` / `env`。**这不是工具缺口，是身份选得对不对
   的问题**：v8 用 `/flow/about` 真有的 `uri` 当身份，那一条因此归了位（另外三条一样退片段）；
   v9 / v10 按任务书用手写花名册的 `host` 认实例，`/flow/about` 就跟着退了。真正的归位要等
   OQ-BASE-URL-TEMPLATE 裁定——`:base_url` 里写 `{nifi.instance/host}` 之后，
   `host` 才既是身份又真的出现在请求里，四条第二来源才都说得通。

## 实测（2026-09-06）

一台从源码起的 Server（scratch home，控制/数据/控制台端口 8543/8544/3109），
三个包依次导入后各查一次：

| 图 | 查询 | 结果 |
|---|---|---|
| `demo/dvd` v1 | `{:request {:entity {:dvd.catalog.film/film_id 7} :query [:dvd.catalog.film/title]}}` | `"Crimson Lantern"` |
| `demo/cis` v1 | `{:request {:entity {} :query [{:cis.master.customer/all_customers [:cis.master.customer/cust_no :cis.master.customer/cust_name]}]}}` | 首条 `120001 / "Northwind Traders"` |
| `jie.gao@tdsynnex.com/nifi-local` v6 | `{:request {:entity {} :query [{:nifi.system/all_about [:nifi.system/title :nifi.system/version :nifi.system/timezone]}]}}` | NiFi 真回 200：`"NiFi" / "2.11.0" / "MYT"` |
| `jie.gao@tdsynnex.com/nifi-local` v7 | `{:request {:entity {} :query [{:nifi.instance.about/all_about [:nifi.instance.about/title …]}]}}` | NiFi 真回 200：`"NiFi" / "2.11.0" / "MYT"` |
| `open-atlas/nifi-local` **v8** | `{:request {:entity {:nifi.instance/uri "https://localhost:9443/nifi-api"} :query [:nifi.instance/title :nifi.instance/version :nifi.instance/timezone]}}` | NiFi 真回 200：`"NiFi" / "2.11.0" / "MYT"`，`:env/version 8` |
| `open-atlas/nifi-local` **v8** | `{:request {:entity {} :query [{:nifi.flow.connection/all_connections [:nifi.flow.connection/name :nifi.flow.connection/queued_count :nifi.flow.connection/percent_use_count]}]}}` | 真回全流程的 connection 队列，首条 `"success" / 22400 / 2` |

v6 / v7 两行留着，因为它们记的是当时的事实：**三行问的是同一台 NiFi 的同三个字段，答案一致**——
包换了形状，事实没换。v8 那一版这三个字段是**实例这个东西自己的属性**（`nifi.instance/*`），
因为 v8 的实例用 `/flow/about` 的 `uri` 认；v9 的实例改用一份手写花名册按 `host` 认
（need-grammar-v1 §3.1），`/flow/about` 因此退回 `nifi.instance.about` 片段——原因见下面
「已知的可移植性缺口」第 4 条。

## 实测（第 9 版，2026-09-07）

第 9 版切在一台 scratch Studio 上（`OPEN_ATLAS_CONFIG_PATH` 指向一份只放两个 p12 与
`NIFI_CLIENT_STORE_PASS` 的 scratch home，Builder/数据面 9641/9642），对着同一台
NiFi 2.11.0。下面四条查的是**已发布的第 9 版**（graph token 走数据面 `/eql`，不是草稿），
答案里的 `:env/version 9` 就是版本证据：

| 查询 | 结果 |
|---|---|
| `{:request {:entity {:nifi.instance/host "localhost"} :query [:nifi.instance/name :nifi.instance/env]}}` | `"local" / "dev"`（手写花名册，`:env/version 9`） |
| `{:request {:entity {} :query [{:nifi.instance.about/all_about [:nifi.instance.about/title :nifi.instance.about/version :nifi.instance.about/timezone]}]}}` | NiFi 真回 200：`"NiFi" / "2.11.0" / "MYT"` |
| `{:request {:entity {} :query [{(:nifi.flow.connection/all_connections {:limit 1}) [… :queued_count :percent_use_count]}]}}` | 首条 `"success" / 22400 / 2`，与 v8 同一台机器同一个答案 |
| `{:request {:entity {} :query [{(:nifi.flow.bulletin/all_bulletin {:limit 1}) [:level :source_name {:nifi.flow.bulletin/group [:nifi.flow.group/name]}]}]}}` | `"ERROR" / "EvaluateJsonPath" /` 所属组 `"CIS"`——bulletin 与它的组之间那条「属于」join 真的走得通 |

草稿面另外真跑过五条验收查询（任务书退出条件），其中三条是 v8 走不通的：一个组的
processors 同时给出 `scheduling_period`（`/processors/{id}`）与 `tasks_duration_nanos`
（`/flow/processors/{id}/status`）——**三个来源合在一个东西上**；一个 processor 反过来
拿到它的组的 `name`；一条 connection 的 `history` 最近若干个点。

## 实测（第 10 版，2026-09-07）

第 10 版是**同一个池里的从零重说**：先 `remove_objects` 清掉第 9 版的 17 条 resolver、
全部属性与 11 个 namespace（连六条词条一并删，好让 `define_concept` 能连定义一起说），
再把十二句话逐句说一遍。切出来的第 10 版与第 9 版的形状差两处，都是加出来的：

- **`nifi.flow.group/parent`**——组「属于」它的父组。第 9 版少这一条，理由记在 v9 的
  need_test 注释里（自关系的两个 `nifi.flow.group/id` 在一张平的 `:field_mapping` 里
  互相覆盖）；§106.14 第 6 条给自关系的分支单独的映射作用域之后，一句
  `add_relation kind one parent=nifi.flow.group child=nifi.flow.group via=parent_group_id name=parent`
  就说得出来。
- **`nifi.instance/all_instances`**——花名册的根集合，于是「所有实例」问得出来，
  不用先知道 host。

下面六条是**草稿面**（`execute_eql :draft true`，Builder 9641）在切第 10 版之前真跑过的，
六条全部回真数据：

| 查询 | 结果 |
|---|---|
| `{:entity {} :query [{:nifi.instance/all_instances [:host :name :env]}]}` | 花名册三台：`localhost/local/dev`、`nifi-01.corp.local/nifi-01/prod`、`nifi-02.corp.local/nifi-02/prod` |
| `{:entity {:nifi.flow.group/id "6c73f715…"} :query [:name {:nifi.flow.group/processors [:name :scheduling_period :tasks_duration_nanos]}]}` | 组 `"CIS"` 的五个 processor，`ControlRate` 给出 `"0 sec"`（`/processors/{id}`）与 `94747457`（`/flow/processors/{id}/status`）——**三个来源合在一个东西上** |
| `{:entity {:nifi.flow.processor/id "6c74e55e…"} :query [:name {:nifi.flow.processor/group [:nifi.flow.group/name]}]}` | `"ControlRate"` → 它的组 `"CIS"`（B2 反向，§106.14 第 5 条的 join 由 processor 自己的 resolver 产出） |
| `{:entity {:nifi.flow.group/id "6c73f715…"} :query [:name {:nifi.flow.group/parent [:nifi.flow.group/name]}]}` | `"CIS"` → 父组 `"NiFi Flow"`（自关系，第 9 版没有这一条） |
| `{:entity {:nifi.flow.connection/id "6cb8a31b…"} :query [:name {(:nifi.flow.connection/history {:limit 10}) [:timestamp :queued_count]}]}` | 连接 `"success"` 的历史点真回，`queued_count` 稳定在 60 |
| `{:entity {} :query [{(:nifi.flow.bulletin/all_bulletin {:limit 5}) [:level :source_name :timestamp_display {:nifi.flow.bulletin/group [:nifi.flow.group/name]}]}]}` | 五条 `ERROR` / `EvaluateJsonPath`，所属组 `"CIS"` |

`publish_graph` 体检回 `{:would-cut? true :health-findings [] :findings []}`，
`inspect_graph view=things` 回 6 个东西（身份全部 declared）、5 个片段
（`nifi.flow.queue_history` 与四个 `nifi.instance.*`）、6 条词条、**0 条 finding**。

**这一版的 zip 不是从 `/api/studio/graphs/export` 下的**：那条路由走 `session-caller`
（浏览器会话），而这台 scratch Studio 的注册已关、会话在重启时丢了，工作区 token 到不了
它——**MCP 面没有导出工具**。zip 是按 `builder_server.clj` 的 `bundle-bytes`
（固定 1980-01-01 时间戳、content/manifest/report 定序、Java 的 data-descriptor 形状）
在本地重建的，并先用同一段代码重建了第 9 版、与仓库里那份 v9 zip **逐字节比对通过**
之后才用来生成 v10。

## 实测（第 11 版，2026-09-07）

第 11 版是**同一台 scratch Studio、同一个池里的又一次从零重说**：先一次
`remove_objects`（154 个对象：18 条 resolver、119 个属性、11 个 namespace、6 条词条）
清空第 10 版，再把同样的十二句说一遍。**模型形状与第 10 版逐字相同**——
`need_test` 的读回断言一个字没改就过——变的是**工具面**（doc 106 §106.14 第 8、9 条，
84 §8.4.25 的「编码与身份拼法」），所以这一版量的是**说同一件事要花多少次调用**：

| | 第 10 版 | 第 11 版 |
|---|---|---|
| tools/call 总数 | 130 | 146（其中 39 次是一处环境错，见下） |
| apply | 66 | 56 |
| 拒绝 | 9 | 10（含刻意重跑的「待核 1」一次） |
| `update_resolver` | 2 | **0** |
| 说出来的意图（plan+apply 算一次） | — | 58 |

第 11 版的 146 次里有 **39 次是同一处环境错**：scratch Studio 起来时进程环境里没有
`NIFI_CLIENT_STORE_PASS`，于是每一句读 NiFi 的话都以
`the tool failed: Get Key failed: Cannot read the array length because "password" is null`
失败（这条正是 CLAUDE.md 的 OQ-API-CREDENTIALS：api 凭证只能进进程环境，且必须重启）。
补上变量重启后**十九句一次过**。去掉这一处，工具面的净成本是 **107 次调用 / 58 个意图**，
其中真正的建模拒绝**两条**：

- `cpu_duration_nanos` / `gc_duration_nanos` 的 `:tools.intent/type-undecidable`
  ——`processingPerformanceStatus.*` 在取样里是 null，补 `type: "integer"` 即过。
- 「待核」第 1 条（实例的第二来源）**照任务书又试了一次**，拒绝与第 9 / 10 版逐字相同，
  只有 `:path` 从 `[:input]` 变成 `[:thing]`（8.8 的参数名表把规划器的位置换成了调用方
  真填过的参数）：

  ```clojure
  {:code :tools.intent/unknown-column :severity :error :path [:thing]
   :message "this relation carries no typeable column by that name"
   :got ["host"]
   :expected ["buildTag" "contentViewerUrl" "timezone" "title" "uri" "version"]}
  ```

  所以四个 `nifi.instance.*` 片段**第 11 版仍未归位**，理由不变（见下面「已知的可移植性
  缺口」第 4 条），仍压在 OQ-BASE-URL-TEMPLATE 上。

三处 v10 记为缺口的地方这一版**一次说完，零 `update_resolver`**：

- **8.1 / 8.2**：processor 的第二来源九句 `add_attribute from=/flow/processors/{id}/status`
  全部并进**一条** resolver `nifi_flow_processor_by_id_from_processor`，`:output` 只有那
  九个新属性、**不再产出身份键**。v10 要一条 `update_resolver` 收尾，v11 不要。
- **8.3**：背压的三个过滤参数（`min_queued` / `min_percent_count` / `min_percent_bytes`）
  作为 `add_thing` 的 `filters` 与集合那一句一起说出去，直达 `:eql-params`。
  v10 是事后一条 `update_resolver`。
- **8.5**：十条 `add_relation` 全部一次成，`:summary` 直接写出方向
  （`Add relation attribute nifi.flow.processor/group on nifi.flow.processor — has one nifi.flow.group`）。
- **8.7**：zip 是 `GET /api/studio/graphs/export?graph=…&version=11` 带 **workspace token**
  下的（HTTP 200，7795 字节，三份文件齐）。v10 只能在本地按 `bundle-bytes` 重拼。

还剩一处摩擦，记下来不修：**重说一句 `add_thing` 不会给已存在的属性补上身份标记**。
花名册那一句第一次说漏了 `identity`，补说一遍之后 `nifi.instance` 从片段变回东西，但
`nifi.instance/host` 上的 `:identity? true` 没跟着写，`inspect_graph` 以
`dialect.need/identity-undeclared`（warning，带 `:repair`）点名，照它跑一次
`update_attribute` 才归零。

切版本前六条草稿查询全部回真数据（与第 10 版同六条、同三个 id，答案一致）：花名册三台、
`CIS` 组五个 processor 的 `scheduling_period` + `tasks_duration_nanos`、processor 反查组、
组的父组 `NiFi Flow`、connection 的历史点（`queued_count` 稳定在 60）、五条 `ERROR`
bulletin 及其所属组。`publish_graph` 体检回
`{:would-cut? true :health-findings [] :findings []}`，`inspect_graph view=things` 回
**6 个东西**（身份全部 declared）、**5 个片段**（`nifi.flow.queue_history` 与四个
`nifi.instance.*`）、**6 条词条**、**0 条 finding**。

> **形状上唯一没对上的一条**：§106.14 第 2 条把目标写成「**7 个东西**」（含「历史点」），
> 而验收同一句又把 `nifi.flow.queue_history` 列在**片段**里。历史点按任务书「无身份，
> `identity` 省略即『所有的 X』」建，因此它读回来必然是片段而不是东西——两句话要求相反。
> 第 10、11 两版都落在 **6 个东西 + 5 个片段**。**未自行改契约**：给历史点造一个身份
> 是建模裁定，留给 owner。


**v8 的导入是两步的**（doc 104 §8.17.7 C）：`import` 回的 plan 先核了
`studio/nifi-client.p12`、`studio/nifi-truststore.p12` 与 `NIFI_CLIENT_STORE_PASS`
三项 `:present? true`，才 `{:status :ok :version 8}` 上架。

DVD 那一条是在把 `open-atlas-demo.db` 放进 Server 工作目录的 `data/` 之后才通的——
缺口 1 就是这么量出来的（那次实测在锚点落地之前；现在该放的是 `<锚点>/data/`）。

**重切之后又量了一遍（同日，OQ-DVD-CUT 修完）。** 新的 `open-atlas-dvd.zip` 两条路都过：

- **密封路**：`POST /api/console/graphs/example` → `{:graph-id "demo/dvd" :status :ok :version 1}`，
  查 film 7 得 `"Crimson Lantern"`。
- **导入路**：把本目录这个 zip 走通用导入 → 同样 `{:graph-id "demo/dvd" :status :ok :version 1}`，
  film 7 同样是 `"Crimson Lantern"`；新补的 `dvd_all_stores` 也真回了两家门店
  （`Central Branch / Rotterdam`、`Riverside Branch / Utrecht`）。

## 实测（第 15 版，2026-09-07）

第 15 版是 [doc 106 §106.14 第 18 条](../../docs/design/106-need-grammar-to-dialect.md)
（**身份可以包含被带进该行的属性**）在这张图上的第一个样本，也是第 14 版记下的
§106.12 11.3——「说得出」与「读得回」在 `[host id]` 上不是同一件事——的销项。

第 6 版自己的注释早就写着「组件 id 只在一台实例内唯一，跨实例的连接要 `[host id]`」；
第 14 版**说得出**这句话，第 15 版**读得回**它。

台账：scratch Studio（Builder / 数据面 9641 / 9642，独立 home，**没有 `.env`**——
`NIFI_CLIENT_STORE_PASS` 经 `POST /api/studio/credentials` 按变量名进凭证库，
§106.14 第 12 条），workspace `open-atlas`，`[:read :write]` 工作区 token，全部经
`POST /mcp`；导出走 **workspace token** 那条路（§106.12 第 8.7 条）。

| | 第 14 版 | 第 15 版 |
|---|---|---|
| tools/call 总数 | 74 | **74** |
| apply | 73 | **73**（`create_graph` 没有 apply 参数） |
| 拒绝 | 0 | **0** |
| `update_resolver` | 0 | **0** |
| 脚手架（说完再删的句子） | 0 | **0** |

形状与第 14 版逐项相同：`inspect_graph view=things` 回 **9 个东西**（身份全部 declared）、
**1 个片段**（`nifi.flow.queue_history`，`:series`）、**6 条词条**、**0 条 finding**；
`publish_graph` 体检回 `{:would-cut? true :health-findings [] :findings []}`。
变的只有身份那一栏：

| 东西 | 第 14 版读回的 `:identity` | 第 15 版 |
|---|---|---|
| `nifi.flow.group` / `processor` / `connection` / `label` / `group_status` | `[id]` | **`[nifi.instance/host, <ns>/id]`** |
| `nifi.instance` | `[host]` | 不变（手写花名册的键） |
| `nifi.flow.counter` | `[context, name]` | 不变（两个**自有**字段，第 14 条） |
| `nifi.flow.bulletin` | `[id]` | **仍是 `[id]`**，见下 |
| `nifi.flow.relationship` | `[name]` | **仍是 `[name]`**，见下 |

**两个例外，同一条理由。** 第 18 条按「这个东西的**按键 resolver** 把哪些带进来的属性
列进了 `:input`」放宽身份，而这两个东西**没有按键 resolver** 可放宽：`bulletin` 是
「所有的 X，用 k 认」——`/flow/bulletin-board` 是集合端点，没有 `/{id}` 那一段
（§106.12 第 7.7 条）；`relationship` 住在 processor 的记录里，NiFi 不提供单独取一条的
路径。它们的**行**确实带着 host（relationship 还带着 `nifi.flow.processor/id`——下面第 2
条验收查询就是问它），但没有任何 `:input` 列出它，也就没有任何地方声明它是键。
这不是缺陷，是第 18 条的判据边界，逐字记在这里。

九条验收查询（`execute_eql`，Builder 9641），**九条全部按预期**：

| 查询 | 结果 |
|---|---|
| 实例 `localhost` → `version` + `used_heap_bytes` + `{root_group [name]}` | `"2.11.0"` / `663213384` / 根组 `"NiFi Flow"` |
| **processor → 它的 relationships → 再链回 processor 自己的 id** | `GenerateFlowFile` 的 `success`（`auto_terminate` false），行里带着 `nifi.flow.processor/id`——第 17 条的证据 |
| **两层嵌套一次答完**：根组状态 → `child_status[*]` → `connection_status[*]` | `NiFiBenchMarkTest` 其下 `success` 队列 22400、另一条 0 |
| 一个组的 processors 同时给出 `scheduling_period` 与 `tasks_duration_nanos` | `NiFiBenchMarkTest` 三个 processor，两个来源合在一个东西上 |
| 自关系：processor → 它的组 → 组的父组 | `"DEMO"` → `"NiFi Flow"` |
| **复合键**：`all_counter` 的 `[context name]` | 八个计数器，`Lines Counted` 在两个 context 下各一条、值都是 10480 |
| 五条 bulletin 及其所属组 | 五条 `ERROR` / `EvaluateJsonPath`，所属组 `"CIS"` |
| **记录内集合带过滤参数**：`(:…/connection_status {:min_queued 1000})` | 参数被接受，照常回数据（NiFi 不认这个查询串，所以上游不收窄——`:eql-params` 在 api 源上是声明） |
| **同一个组件 id 换一台主机** | `localhost` 答 `"NiFiBenchMarkTest"`；`nifi-01.corp.local` 5006 ms 后以 `:connection` 失败，`GET /process-groups/{nifi.flow.group/id} [failed]`——**请求真的发去了另一台**，故障记在那一台上。「一个组件 id 在两台实例上是两个不同的东西」由此可验 |

**这一版量到的两处**（都在写面，都已修，各带回归测试）：

1. **A2 第二来源按半把键命名，于是没有跟已有的 lookup 合流。**
   `add_attribute from=⟨端点⟩` 用「这个东西的键」去命名它生成的 resolver，而那里取的是
   **自有**声明的一半（`[id]`），于是 id 算成 `nifi_flow_processor_by_id`——与真正的
   lookup `nifi_flow_processor_by_host_id` 不撞，`…_from_<端点>` 那一支就没走到，
   第二来源被写成了**第三条** resolver、只认半把键。改为按**整把**键
   （§106.14 第 14、18 条），两者重新撞上，合流照常。
2. **记录内集合的 `{name path}` 按名字比对，于是改名的字段被拒。**
   `{"name": "auto_terminate", "path": "$.autoTerminate"}` 撞
   `:tools.intent/unknown-column`——对元素的键面而言是真话，却答非所问：调用方从没说
   元素里有个叫 `auto_terminate` 的字段，它说的是**那个路径上的值，叫这个名字**
   （§106.12 第 7.2 条的裁定，这一支没照做）。现在**给了 path 就按 path 比对**，
   类型也从那条路径上的值定，不再留 nil（`modules/studio/tools` 的
   `an-in-record-field-may-be-renamed-by-its-path-test`）。

**仍在的边界**（不是这一版引入的，逐字重记）：

- **join 上的集合不能按参数收窄，`:limit` 也只是声明**（D2.8「`:eql-params` 只做锚上收窄」；
  `pagination` 块缺失）。`nifi.flow.connection/history` 二十几条一起读仍会撞 EQL 262144
  字节上限。
- **在模板化 source 上，一条查询要从子实体的键起头，就得把 `host` 一起写进 `:entity`**
  （`{:entity {:nifi.instance/host "localhost" :nifi.flow.processor/id "…"}}`）。第 15 版
  之后这不再是「多写一个键」而是**这个东西的键本来就是两个**——`list_entities` 的
  `:identity_keys` 与 `inspect_graph` 的 `:identity` 都这么说。

## 实测（第 14 版，2026-09-07）

第 14 版是 [doc 106 §106.14 第 17 条](../../docs/design/106-need-grammar-to-dialect.md)
（**父键随记录内集合进入子行**）在这张图上的第一个样本，也是第 13 版记下的两条
「说不出来」的销项。第 13 条让 **URL 绑定**的属性随 join 进入子实体；第 17 条把同一段
记账扩到**父 resolver 的身份属性**，只对 `:_from` 分支生效——记录内的行本来就是从父记录
里装出来的，父的身份在装配时已知。

台账：scratch Studio（Builder / 数据面 9641 / 9642，独立 home，**没有 `.env`**——
`NIFI_CLIENT_STORE_PASS` 经 `POST /api/studio/credentials` 按变量名进凭证库，
§106.14 第 12 条），workspace `open-atlas`，`[:read :write]` 工作区 token，全部经
`POST /mcp`；导出走 **workspace token** 那条路（§106.12 第 8.7 条），不再借浏览器会话。

| | 第 13 版 | 第 14 版（重放一遍的净值） |
|---|---|---|
| tools/call 总数 | 72 | **74** |
| apply | 72 | **73**（`create_graph` 没有 apply 参数） |
| 拒绝 | 0 | **0** |
| `update_resolver` | 0 | **0** |
| 脚手架（说完再删的句子） | 1 | **0** |

形状：`inspect_graph view=things` 回 **9 个东西**（身份全部 declared）、**1 个片段**
（`nifi.flow.queue_history`，`:series`）、**6 条词条**、**0 条 finding**；
`publish_graph` 体检回 `{:would-cut? true :health-findings [] :findings []}`。
与第 13 版比，变的是三处：

- **`nifi.flow.relationship` 从 `:series` 片段变成一个东西。** 一句
  `add_relation parent=processor child=nifi.flow.relationship from="$.relationships[*]" sample=…`
  说完，再一句 `update_attribute … identity:true`——**没有脚手架 `add_thing`，也没有
  `remove_objects`**（第 13 版为了让子东西先有字段，说了一句字面路径的 `add_thing`
  再拆掉；第 10.4 条修好之后，`sample` 就是父的键，父的那条记录读一次，数组的元素
  即子的行）。它的 `:identity` 读回来是一个**向量** `["nifi.flow.relationship/name"]`,
  而「哪一个 processor 的」由第 17 条随行带进来。
- **`nifi.flow.group/group` 没有了。** 第 13 版把「组属于父组」说了两遍——`group/parent`
  和子集合顺带生成的 `group/group`，一条边两个名字。第 14 版只说一遍。
- **`[host id]` 说得出了。** `nifi.flow.group_status` 的 `add_thing identity`
  收 `["nifi.instance/host", "id"]`：URL 绑定的那一半不注册成属性（它是另一个东西的键），
  但进了按键 resolver 的 `:input`，于是 `nifi_flow_group_status_by_nifi.instance/host_id`
  是「这台实例上的这个组的状态」。**读回来的 `:identity` 仍只有 `[id]`**——第 14 条的
  身份是**同一个 namespace 内**全部 `:identity? true` 的属性，`host` 住在别的 namespace；
  跨 namespace 的复合身份今天由 resolver 的 `:input` 承载，不由 `:identity?` 承载。
  这一点值得 owner 知道：**「说得出」与「读得回」在这一形上还不是同一件事**。

九条验收查询（`execute_eql`，Builder 9641），**九条全部回数据**：

| 查询 | 结果 |
|---|---|
| 实例 `localhost` → `version` + `used_heap_bytes` + `{root_group [name]}` | `"2.11.0"` / `247956176` / 根组 `"NiFi Flow"` |
| **processor → 它的 relationships → 再链回 processor 自己的名字** | `EvaluateJsonPath` 的 `failure`（`auto_terminate` true）/ `matched` / `unmatched`，**每一行都带着 `nifi.flow.processor/id`**，`nifi.flow.processor/name` 因此从子行里问得出来——**这就是第 17 条的证据**，第 13 版这一枝只有 `name` 与 `auto_terminate` |
| **两层嵌套一次答完**：根组状态 → `child_status[*]` → `connection_status[*]` | `NiFiBenchMarkTest` 排队 22400，其下 `success` 队列 22400、另一条 0 |
| 同一条记录的另一枝：`processor_status[*]` | `ControlRate` / `AttributesToJSON` / `EvaluateJsonPath` `Running`，`RouteOnAttribute` `Invalid`，`GenerateFlowFile` `Stopped` |
| **记录内集合带过滤参数**：`(:…/connection_status {:min_queued 1000})` | 参数被接受（第 13 版这一支静默丢弃、查询时才 `unknown-param`），照常回数据；**NiFi 不认这个查询串，所以上游不收窄**——`:eql-params` 在 api 源上是声明 |
| 一个组的 processors 同时给出 `scheduling_period` 与 `tasks_duration_nanos` | `CIS` 组五个 processor，`ControlRate` 回 `"0 sec"` / `103847876`——三个来源合在一个东西上 |
| 自关系：processor → 它的组 → 组的父组 | `"EvaluateJsonPath"` → `"CIS"` → `"NiFi Flow"` |
| **复合键**：`all_counter` 的 `[context name]` | 八个计数器，`Lines Counted` 在两个 context 下各一条、值都是 10480 |
| 五条 bulletin 及其所属组 | 五条 `ERROR` / `EvaluateJsonPath`，所属组 `"CIS"` |

**这一版量到的两处**（都在引擎，都已修，各带回归测试）：

1. **记录内的子行会被父的键盖掉，当子行与父同 namespace。** 第 17 条把父的身份写进
   每个子行，而 `child_status` 的子行**也是** group_status——写在后面就把每个子组的 `id`
   换成了根组的，于是「每个子组自己的 connection」全部答成根组的（实测：五个子组一律 `[]`）。
   判据改为**子行已经答了的键不被覆盖**（与 §106.14 第 6 条分支作用域同一句话：这一层
   补全行，不改写行）。`engine` 的 `a-child-keeps-its-own-key-when-the-branch-is-its-own-namespace`。
2. **`add_relation from="$…"` 的探针要按父的 `data_path` 定位数组。** `:_from` 是**记录内**
   的路径（`$.relationships[*]`），而探针拿到的是整个响应，父的 `data_path`（`$.component`）
   要接在前面，否则 `$.relationships` 在响应根下找不到。存进模型的仍是记录相对的原文。
   `modules/studio/tools` 的 `the-probe-locates-the-array-under-the-parents-data-path-test`。

**仍在的边界**（不是这一版引入的，逐字重记）：

- **join 上的集合不能按参数收窄，`:limit` 也只是声明**（D2.8「`:eql-params` 只做锚上收窄」；
  `pagination` 块缺失）。`nifi.flow.connection/history` 二十几条一起读仍会撞 EQL 262144
  字节上限。
- **在模板化 source 上，一条查询要从子实体的键起头，就得把 `host` 一起写进 `:entity`**
  （`{:entity {:nifi.instance/host "localhost" :nifi.flow.processor/id "…"}}`）——`:input`
  真的需要两个值。九条验收查询因此都带着 host。

## 实测（第 13 版，2026-09-07）

第 13 版是**嵌套集合**（[doc 106 §106.14 第 15 条](../../docs/design/106-need-grammar-to-dialect.md)）
与**复合键**（同 §106.14 第 14 条）两条裁定在这张图上的第一个样本。第 12 版把
「所有的 connection」说成一条 `/flow/process-groups/root/status?recursive=true` 的
根集合，靠一条**七段长的 `data_path`** 从三层嵌套里把 connection 捞平；第 13 版改成
**记录加它自己的集合**：那份响应是一条**组状态记录**，`add_relation from="$…"` 三句
把它自己的 `processGroupStatusSnapshots[*]`、`connectionStatusSnapshots[*]`、
`processorStatusSnapshots[*]` 说成它的三枝，一次调用答两层。

台账：scratch Studio（Builder / 数据面 9641 / 9642，独立 home，**没有 `.env`**——
`NIFI_CLIENT_STORE_PASS` 经 `POST /api/studio/credentials` 按变量名进凭证库，
`declare_source` 的 `:requires` 因此回 `stored? true / in-env? false`，§106.14 第 12 条），
workspace `open-atlas`，`[:read :write]` 工作区 token，全部经 `POST /mcp`。

| | 第 12 版 | 第 13 版（重放一遍的净值） |
|---|---|---|
| tools/call 总数 | 67 | **72** |
| apply | 67 | **72** |
| 拒绝 | 0 | **0** |
| `update_resolver` | 0 | **0** |
| 意图（一句一次） | 67 | 72 |

形状：`inspect_graph view=things` 回 **8 个东西**（身份全部 declared）、**2 个片段**
（都是 `:series`）、**6 条词条**、**0 条 finding**；`publish_graph` 体检回
`{:would-cut? true :health-findings [] :findings []}`。与第 12 版比多两个东西：

- **`nifi.flow.group_status`**——递归状态那条**记录**本身，按 `id` 认，来自
  `/flow/process-groups/{id}/status?recursive=true`。三枝记录内集合都挂在它这条
  按键 resolver 的操作块上，`:_from` 指着数组、叶子路径在元素之内、**都没有 `:limit`**
  （基数是宿主记录的，D2.8 对这一形豁免）。
- **`nifi.flow.counter`**——`/counters` 的计数器，身份是 **`[context name]` 两个字段
  一起**：`Lines Counted` 在每个 CountText 下都出现一次，单看 `name` 认不出是哪一个。
  `inspect_graph` 的 `:identity` 因此是一个**向量**，不是片段。

新增的第二个 `:series` 片段是 **`nifi.flow.relationship`**：processor 的
`component.relationships[*]` 在 processor 自己的记录里，有 `name` 没有 id——
按 §106.14 第 10 条读回来是 `:series`，挂在 `nifi.flow.processor/relationships` 下，
**不产生 finding**。

十条草稿查询（`execute_eql`，Builder 9641），**九条真回数据**：

| 查询 | 结果 |
|---|---|
| 实例 `localhost` → `version` + `used_heap_bytes` + `{root_group [name]}` | `"2.11.0"` / `332827048` / 根组 `"NiFi Flow"` |
| **两层嵌套一次答完**：根组状态 → `child_status[*]` → `connection_status[*]` | 一次 `GET /flow/process-groups/{id}/status?recursive=true`，回 `NiFiBenchMarkTest` 排队 22400，其下 `success` 队列 22400、另一条 0——**这就是第 15 条的证据** |
| 同一条记录的另一枝：`child_status[*]` → `processor_status[*]` | `CompressContent` / `GenerateFlowFile` `Stopped`，`DuplicateFlowFile` `Running` |
| 一个组的 processors 同时给出 `scheduling_period` 与 `tasks_duration_nanos` | `CIS` 组五个 processor，`ControlRate` 回 `"0 sec"` / `85354792`——三个来源合在一个东西上 |
| processor → 它的组的 `name`，组 → 父组的 `name`（自关系） | `"ControlRate"` → `"CIS"`；`"CIS"` → `"NiFi Flow"` |
| **记录内的 `:series`**：processor 的 `relationships` | `EvaluateJsonPath` 回 `failure`（`auto_terminate` true）、`matched`、`unmatched`——**零次额外请求**，行就在 processor 那条记录里 |
| **复合键**：`all_counter` 的 `[context name]` | 八个计数器，`Lines Counted` 在 `CountText (6c7528a2…)` 与 `All CountText's` 两个 context 下各一条、值都是 10480——**同名不同物，正是复合键要认的那件事** |
| 过滤：`(:nifi.flow.group/connections {:min_queued 1000})` | 参数被接受（不再是 `unknown-param`），查询照常回数据；**NiFi 不认这个查询串，所以上游不收窄**——`:eql-params` 在 api 源上是声明，narrowing 由上游负责 |
| 五条 bulletin 及其所属组 | 五条 `ERROR` / `EvaluateJsonPath`，所属组 `"CIS"` |
| connection 的 `history` | **被 EQL 结果上限拒**，见下面第 3 条 |

**这一版量到的三处**（都在写入端或边界上，不在引擎）：

1. **`add_relation` 的 `filters` 在「记录内」那一支被静默丢弃。** 同一个 `filters`
   参数在 `add_thing` 与 `add_relation` 的**端点**那一支都写进 `:eql-params`
   （plan 的 action 里看得见），在 `from="$…"` 那一支**既不写、也不报**——plan 回
   `ok`、`:findings` 空、`:fill` 空，直到查询时才以
   `:engine.request/unknown-param`「undeclared EQL parameter」现形。§106.12 第 8.3 条
   的 `filters` 落地漏了第 15 条新开的这一支。**本版因此把三个背压过滤参数说在
   `nifi.flow.group/connections`（端点那一支）上**，它们在那里确实进了模型。

2. **`add_attribute` 的 `column` 写成 JSONPath 时推不出类型。** `column: "buildTag"`
   （裸字段名）正常推出 `:string`；`column: "$.processingPerformanceStatus.cpuDuration"`
   写进 `:field_mapping` 是对的，但**探针拿它去比对采样字段名**，比不上，于是一律
   `:tools.intent/type-undecidable`「the source structure does not give this column a
   dialect type; state it」。嵌套字段因此必须同时给 `type`。同一件事在 `add_thing`
   的 `attributes` 里**不发生**——那里的 `{"name":…, "path":…}` 条目推得出类型。
   两个工具对「一条路径」的处理不一致。

3. **一个 join 上的集合没有办法收窄，`:limit` 也只是声明。** `nifi.flow.connection/history`
   每条 connection 1440 个采样点，二十几条 connection 一起读是 623 KB～1.34 MB，
   超过 EQL 的 262144 字节上限被整句拒（`:engine.limit/exceeded`，**拒绝不截断**）。
   收窄不了的原因有两条：`:eql-params only narrows on the anchor`——挂在 join 上的
   集合不是锚，`_limit` 也不例外；而 `:limit {:default 50 :max 500}` 要靠 source 的
   `pagination` 块才真的分页，这个 source 没有（plan 的 `:fill` 里写着
   `[:source "pagination" "_limit / _offset (api-v1 eql-param :field)"]`）。
   这不是第 13 版引入的——第 12 版同样如此，只是那一版的验收查询按单条 connection
   的键起头。**在模板化 source 上按子实体的键起头是不可达的**（`:input` 还要 host），
   所以这条路今天走不通。

**两条「说不出来」的句子，逐字记下不修**（都是第 15 条落地后新露出来的）：

- **父键不进记录内的子行，所以记录内的东西不能有含父键的复合身份。** 任务原本要把
  processor 的 `relationship` 认成 `[processor_id name]`——那是它真正的身份，`success`
  在每个 processor 下都有一条。`add_thing … identity=["processor_id","name"]` 逐字回：

  ```clojure
  {:code :tools.intent/unknown-column :severity :error :path [:identity]
   :message "this relation carries no typeable column by that name"
   :got ["processor_id"]
   :expected ["autoTerminate" "description" "name" "retry"]}
  ```

  §106.14 第 13 条让**URL 绑定**的属性随 join 进入子实体，父的**身份键**不在其列，
  记录内的子行里也没有回显它的字段。于是 `relationship` 只能是无身份的 `:series`。
  这是复合键（第 14 条）与记录内集合（第 15 条）交界处的一个真空：**NiFi 上唯一
  自然的、跨实例的复合键 `[host id]` 同样说不出来**——`host` 是 URL 绑定的，不是行里
  的列，`identity` 只收「这个关系有的、推得出类型的列」。`/counters` 的
  `[context name]` 是这台 NiFi 上唯一一个**两个字段都在行里**的自然复合键，本版用的
  就是它。

- **记录内集合要求子东西先有字段，而子东西的数据只在父的按键端点里。**
  `add_relation from="$.relationships[*]"` 对尚未建模的子东西逐字回：

  ```clojure
  {:code :tools.intent/name-unresolved :severity :error :path [:child]
   :message "this thing has no fields registered yet, and an in-record collection
             has nothing to probe; say what the thing is first (add_thing)"
   :got "nifi.flow.relationship" :refs ["doc 106 §106.14 item 15"]}
  ```

  而 `add_thing … from="/processors/{id}" data_path="$.component.relationships"`
  **不带 `identity`** 时，`sample` 里的 `id` 不被用来填 `{id}`（`sample` 只填身份键
  与 URL 绑定属性），探针照字面请求 `/processors/{id}` 换来
  `:tools.intent/probe-refused :got "HTTP 404"`。本版因此多说一句**脚手架**：
  用字面路径 `/processors/<一个真 id>` 把字段读进池，说完真正那一句
  `add_relation from="$.relationships[*]"` 之后再 `remove_objects` 把脚手架拆掉
  （与第 12 版拆 `nifi_flow_bulletin_all_bulletin` 同一种做法）。
  **不自行改契约**：是让 `sample` 填任意占位符、还是让记录内集合自己带 `attributes`
  去建子东西，交 owner。

**另有一处措辞**（不是缺陷，记在这里省得下一轮再撞）：一个空图上 `add_thing` 不给
`source` 会以 `:tools.intent/source-undecidable`「this graph names 2 sources」拒，
列出的其实是**池里**的两个（这台 scratch Studio 播种了 `demo/dvd`，所以池里有
`dvd-demo-db`）。判定本身对，措辞把「池」说成了「图」——与 §106.12 第 2 条同一条。

## 实测（第 12 版，2026-09-07）

第 12 版是**第一次把 `:base_url` 的 authority 占位真的用起来**的一版：一个 source、
一张图，查询时说哪一台 NiFi。它也是 [doc 106 §106.14 第 13 条](../../docs/design/106-need-grammar-to-dialect.md)
（URL 绑定属性随 join 进入子实体）落地后的第一个样本——第 12 版的第一次尝试（同日）
量到「除实例自己的属性外整张图查不动」，正是那条裁定要修的东西，当时**未切版**。

台账：scratch Studio（Builder / 数据面 9641 / 9642，独立 home，**没有 `.env`**——
`NIFI_CLIENT_STORE_PASS` 经 `POST /api/studio/credentials` 按变量名进凭证库，
`declare_source` 的 `:requires` 因此回 `stored? true / in-env? false`，§106.14 第 12 条），
workspace `open-atlas`，`[:read :write]` 工作区 token，全部经 `POST /mcp`。

| | 第 11 版 | 第 12 版（重放一遍的净值） |
|---|---|---|
| tools/call 总数 | 146（含 39 次环境错） | **67** |
| apply | 56 | **67** |
| 拒绝 | 10 | **0** |
| `update_resolver` | 0 | **0** |
| 意图（一句一次） | 58 | 67 |

形状：`inspect_graph view=things` 回 **6 个东西**（身份全部 declared）、**1 个片段**
（`nifi.flow.queue_history`，`:series`）、**6 条词条**、**0 条 finding**；
`publish_graph` 体检回 `{:would-cut? true :health-findings [] :findings []}`。
与第 11 版比，四个 `nifi.instance.*` 片段没有了（缺口 4 销项），根集合从七个减到
**一个**（静态花名册）——api 的两个根集合在模板化 source 上无键可绑，改说成实例的
子集合 `nifi.instance/bulletins` 与 `nifi.instance/connections`（背压那三个过滤参数
随后者保留）。

切版本前七条草稿查询（`execute_eql`，Builder 9641）：

| 查询 | 结果 |
|---|---|
| 实例 `localhost` → `version` + `used_heap_bytes` + `{root_group [name]}` | `"2.11.0"` / `485185040` / 根组 `"NiFi Flow"`——**跨 join 的那一枝正是第 13 条的证据**：`nifi_flow_group_by_id` 的 `:input` 要 host，而 host 从来不在响应行里 |
| 一个组的 processors 同时给出 `scheduling_period` 与 `tasks_duration_nanos` | `CIS` 组五个 processor，`ControlRate` 回 `"0 sec"` / `76945193`——三个来源合在一个东西上 |
| processor → 它的组的 `name` | `"ControlRate"` → `"CIS"` |
| 组 → 父组的 `name` | `"CIS"` → `"NiFi Flow"`（自关系） |
| connection 的 `history` | `"success"` 的历史点，`queued_count` 稳定在 60 |
| 五条 bulletin 及其所属组 | 五条 `ERROR` / `EvaluateJsonPath`，所属组 `"CIS"` |
| **三台实例的根组名一次问完**（`all_instances`） | **整句被拒**：第一行 `localhost` 已答，第二行 `nifi-01.corp.local` 5 秒后 `:connection`，引擎以 `:engine.execution/failed` 拒绝**整个**查询并点名失败的行 `[:nifi.instance/all_instances 1]`——**没有部分结果**。花名册里那两台是虚构主机，所以这正是预期：轴是真的（换主机真的换了拨号目标），而一行失败即整句失败 |

**这一版量到的两处，记下不修**（都在写入端，不在引擎）：

1. **子集合在模板化 source 上不带 URL 绑定属性。** `add_relation kind many` 生成的
   resolver `:input` 只有父键，于是 `/process-groups/{id}/processors` 带着未解析的
   `{nifi.instance/host}` 发出去，以 `:mapping` 失败——§106.14 第 11 条说「走该 source 的
   每条 resolver 自动获得这个输入」，而子集合是**唯一**一处 `:input` 不由调用方点名的
   keyed 形状，恰好漏了。本轮随手修掉（`modules/studio/tools` 的
   `a-child-collection-on-a-templated-source-is-keyed-on-the-host-too-test`），第 12 版是
   修好之后重放出来的。
2. **模板化 source 上的根集合被写下而不是被拒。** need-grammar-v1 §3.5 说这种条目
   「由 api-v1 的写入端校验点名 `:base_url` 拒绝」；实际是写入成功，只有事后的验证查询
   以 `source execution failed` 报出来。第 12 版因此多说了一句
   `remove_objects` 把 `nifi_flow_bulletin_all_bulletin` 拆掉。**未自行改契约**：
   是补写入端校验还是改契约措辞，交 owner。
