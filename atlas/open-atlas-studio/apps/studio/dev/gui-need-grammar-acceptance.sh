#!/usr/bin/env bash
# doc 106 §106.16 的验收：**用 GUI 从空图建 NiFi 图，不开 MCP**。
#
# 「用 GUI」在这里的意思是**走浏览器走的那些路由**——`/api/auth/login` 拿会话
# cookie，然后每一句话都 POST 到 `/api/studio/intent`，正是向导与对话框按下
# 「plan」「写进图」时发的那一个请求。不驱动浏览器：要证的是**每一步落在同一个
# `plan-add-*` 规划器上**（D1.8），而那条判据在 HTTP 这一层就是可判的——这条路由
# 除了 `tools/call` 什么也不做，浏览器里没有第二份规划逻辑可以绕过它。
#
# 前置：
#   * 一台可达的 NiFi（默认 https://localhost:9443），客户端证书两份 p12；
#   * 一个 scratch Studio home 与 scratch `OPEN_ATLAS_CONFIG_PATH`（证书按相对
#     路径 `studio/…` 从后者往下找，见 connector-api 的 `base-dir`）；
#   * 该 home 里已 bootstrap 过一个 admin。
#
# 用法：
#   OPEN_ATLAS_STUDIO_HOME=/tmp/x/home OPEN_ATLAS_CONFIG_PATH=/tmp/x/config \
#     scripts/run-studio.sh          # 另一个终端，端口 9651/9652
#   STUDIO_URL=http://127.0.0.1:9651 STUDIO_EMAIL=… STUDIO_PASSWORD=… \
#   NIFI_HOST=localhost NIFI_CLIENT_STORE_PASS=… \
#     apps/studio/dev/gui-need-grammar-acceptance.sh
#
# 退出码 0 = 每一句都写进去了，且 `inspect_graph view=things` 零 error finding。
set -uo pipefail

URL="${STUDIO_URL:-http://127.0.0.1:9651}"
EMAIL="${STUDIO_EMAIL:?set STUDIO_EMAIL}"
PASSWORD="${STUDIO_PASSWORD:?set STUDIO_PASSWORD}"
NIFI_HOST="${NIFI_HOST:-localhost}"
NAME="${GRAPH_NAME:-nifi-gui}"
WORKSPACE="${WORKSPACE:-demo}"
GRAPH="$WORKSPACE/$NAME"
SOURCE="${SOURCE:-nifi-gui}"
JAR="$(mktemp -t oa-gui-cookies)"
trap 'rm -f "$JAR"' EXIT

pass=0; fail=0
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

post() {  # path, edn body on stdin
  curl -s -b "$JAR" -X POST "$URL$1" -H 'Content-Type: application/edn' --data-binary @-
}

# One GUI request: `$1` the tool, EDN args on stdin — verbatim what the form
# assembles, nothing rewritten on the way.
intent() {
  local tool="$1" args; args="$(cat)"
  printf '{:tool "%s" :args %s}' "$tool" "$args" | post /api/studio/intent
}

# A sentence: plan first (what every form does), read the plan's :summary, then
# apply. A plan carrying an error finding is NOT applied — that is the whole
# difference between the confirm box and a write button.
sentence() {
  local label="$1" tool="$2" args="$3" out
  say "$label  ($tool)"
  out="$(printf '%s' "$args" | intent "$tool")"
  if printf '%s' "$out" | grep -q ':severity :error'; then
    printf '\033[31mREFUSED (plan)\033[0m\n%s\n' "$out"; fail=$((fail+1)); return 1
  fi
  printf 'plan :summary → %s\n' \
    "$(printf '%s' "$out" | sed -n 's/.*:summary \(\[[^]]*\]\).*/\1/p' | head -c 500)"
  out="$(printf '%s' "${args%\}} :apply true}" | intent "$tool")"
  if printf '%s' "$out" | grep -q ':severity :error'; then
    printf '\033[31mREFUSED (apply)\033[0m\n%s\n' "$out"; fail=$((fail+1)); return 1
  fi
  printf '\033[32mapplied\033[0m\n'
  pass=$((pass+1))
}

say "sign in as a person (the browser's own door — no workspace token anywhere)"
printf '{:email "%s" :password "%s"}' "$EMAIL" "$PASSWORD" \
  | curl -s -c "$JAR" -X POST "$URL/api/auth/login" \
      -H 'Content-Type: application/edn' --data-binary @- | head -c 90; echo

say "the credentials page: the keystore password, by variable name (§106.14-12)"
printf '{:values {"NIFI_CLIENT_STORE_PASS" "%s"}}' "${NIFI_CLIENT_STORE_PASS:?set it}" \
  | post /api/studio/credentials | head -c 200; echo

say "declare the source — the Sources page's own route, not an intent"
# `${VAR}` is written with a single-quoted heredoc so the shell leaves it alone:
# a `\$` escape is not EDN, and the reader refuses it.
sed "s/@HOST@/$NIFI_HOST/; s|@SOURCE@|$SOURCE|" <<'EDN' | post /api/studio/assets/action | head -c 300; echo
{:type :source :id "@SOURCE@" :action :create :expected_revision 0
 :entry {:type "api"
         :label "NiFi (GUI acceptance)"
         :base_url "https://{nifi.instance/host}:9443/nifi-api"
         :health_endpoint "/flow/about"
         :tls {:keystore "studio/nifi-client.p12"
               :keystore_pass "${NIFI_CLIENT_STORE_PASS}"
               :keystore_type "PKCS12"
               :truststore "studio/nifi-truststore.p12"
               :truststore_pass "${NIFI_CLIENT_STORE_PASS}"
               :truststore_type "PKCS12"}}}
EDN

say "an EMPTY graph (the workspace is the session's, never an argument)"
printf '{:name "%s" :members {}}' "$NAME" | post /api/studio/graphs/create | head -c 200; echo

# ── the sentences ──────────────────────────────────────────────────────────

# A1 · 手写清单 (§3.1)：哪几台实例，是模型的一部分，没有别处可取。
sentence "A1 · 有一种东西叫 instance，用 host 认，来自一份手写清单" \
  add_thing \
  "{:graph \"$GRAPH\" :name \"nifi.instance\" :identity [\"host\"]
    :rows [{\"host\" \"$NIFI_HOST\"}]}"

# A1 · 复合身份 (§3.6 + 第 18 条)：[host id] —— host 是被连接带进这一行的，
# 不是记录里的字段。按键端点是模板，`sample` 给每个占位一个真值探一次。
sentence "A1 · 有一种东西叫 group，用 host 和 id 一起认，来自 /process-groups/{id}" \
  add_thing \
  "{:graph \"$GRAPH\" :name \"nifi.flow.group\"
    :identity [\"nifi.instance/host\" \"id\"]
    :source \"$SOURCE\" :from \"/process-groups/{id}\"
    :sample {\"nifi.instance/host\" \"$NIFI_HOST\" \"id\" \"root\"}
    :data_path \"$\"
    :attributes [{\"name\" \"id\" \"path\" \"\$.component.id\"}
                 {\"name\" \"name\" \"path\" \"\$.component.name\"}]}"

# B1 · 端点那一支：子集合由父键模板化的端点取，无 `:batch`。
#
# **这一句依赖那台 NiFi 的流里真有 processor。** 一个空的 root group 答
# `{"processors":[]}`，于是没有一个字段推得出类型，`attributes` 里点名的三个都以
# `:tools.intent/unknown-column` 拒——这是 D2.14「没有证据就不断言类型」，不是缺陷。
# 要跑通这一句，先在 NiFi 的 root group 里放一个 processor。
sentence "B1 · 每个 group 有很多 processor（来自 group 下的端点）" \
  add_relation \
  "{:graph \"$GRAPH\" :parent \"nifi.flow.group\" :child \"nifi.flow.processor\"
    :kind :many :source \"$SOURCE\"
    :from \"/process-groups/{id}/processors\"
    :sample {\"nifi.instance/host\" \"$NIFI_HOST\" \"id\" \"root\"}
    :attributes [{\"name\" \"id\" \"path\" \"\$.id\"}
                 {\"name\" \"name\" \"path\" \"\$.component.name\"}
                 {\"name\" \"state\" \"path\" \"\$.component.state\"}]}"

# A2 · 第二来源 (§3.3 + §3.5)：说在 group 之后，因为在此之前这张图还没有
# 点名任何源，`decide-source` 于是退到 workspace 的全部源上而无从选择——
# 而 `add_attribute` 的键集里没有 `source` 可以点名（见报告）。键在 base_url 模板里，记录不必回显它；
# `add_attribute` 的键集是封闭的，没有 `source` —— 用哪个源由 `from` 与图的
# 成员决定，与 `add_thing` 同则。
sentence "A2 · instance 还来自 /flow/about（第二来源，键已由 URL 绑定）" \
  add_attribute \
  "{:graph \"$GRAPH\" :thing \"nifi.instance\" :name \"version\"
    :from \"/flow/about\" :column \"\$.about.version\"
    :sample {\"nifi.instance/host\" \"$NIFI_HOST\"}}"

# C1/C2 · 概念，与它绑到哪个属性上。绑定永不按名生成 (D2.26)。
sentence "C1/C2 · 有个概念叫「运行状态」，processor 的 state 就是它" \
  define_concept \
  "{:graph \"$GRAPH\" :concept \"nifi.flow/run_status\"
    :definition {:label \"运行状态\" :description \"A component's run status.\"
                 :synonyms [\"run status\"]}
    :attributes [\"nifi.flow.processor/state\"]}"

# A4 · 数字口径。`:contract` 由工具写入，不由调用方给。
sentence "A4 · group 的数字口径 processor_count" \
  add_metric \
  "{:graph \"$GRAPH\" :thing \"nifi.flow.group\" :name \"processor_count\"
    :expression \"count(*)\" :label \"processor 数\"}"

# ── 读回来 ─────────────────────────────────────────────────────────────────
say "读回来 · inspect_graph view=things（与数据面 list_entities 同一份判据）"
things="$(printf '{:graph "%s" :view :things}' "$GRAPH" | intent inspect_graph)"
printf '%s\n' "$things" | head -c 5000; echo
errors="$(printf '%s' "$things" | grep -o ':severity :error' | wc -l | tr -d ' ')"

printf '\n\033[1m== 结论\033[0m\nsentences applied: %s   refused: %s   things-view error findings: %s\n' \
  "$pass" "$fail" "$errors"
[ "$fail" -eq 0 ] && [ "$errors" -eq 0 ]
