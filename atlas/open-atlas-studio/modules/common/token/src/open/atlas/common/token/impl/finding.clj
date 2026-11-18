(ns open.atlas.common.token.impl.finding
  "This module's finding constructor and its published `:code` registry
   (finding-v1 §3:每个模块登记自己的域). The domain is `token.*`.

   The registry covers codes produced on **both** sides: the pure decisions here
   and the ledger decisions in `runtime/token`. One domain, one registry — a
   caller routing on `:code` must not have to know which module answered."
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "Published `:code`s. Once published they are not renamed; producing an
   unregistered code is an implementation bug."
  #{;; --- 外部格式（S5.9）---
    :token.format/malformed        ; 不是 oat_v1.<token-id>.<secret>
    :token.format/weak-secret      ; secret 短于 256 bit
    ;; --- grants 形状（S5.1）---
    :token.grants/empty            ; grants 必须非空
    :token.grants/duplicate-graph  ; 同一张 graph 出现两个 grant
    :token.grants/invalid          ; grant 不是 map / 键面之外的键 / 缺必填
    :token.grants/invalid-version  ; :version 不是 :latest 也不是正整数
    ;; --- 台账记录形状 ---
    :token.record/malformed        ; 记录键面不对、status 不在词表里
    ;; --- 签发（runtime 产出）---
    :token.pepper/unavailable      ; 本机 pepper 缺失或读不出——**拒绝启动**
    :token.issue/graph-not-loaded  ; 钉版本 grant 指向本机没装载的版本
    :token.issue/no-serving-version ; latest grant 指向本机没有 serving 指针的图
    :token.issue/graph-disabled    ; graph 被 instance 管理员停用
    ;; --- 授权判定 ---
    :token.authz/unknown           ; 本机台账里没有这个 token-id（runtime 产出）
    :token.authz/secret-mismatch   ; digest 对不上（runtime 产出）
    :token.authz/revoked           ; 该 token 在本机已吊销
    :token.authz/expired           ; 已过 :expires-at
    :token.authz/graph-disabled    ; graph 已停用，已有 token 也不能继续查询
    :token.authz/user-assertion-forbidden})  ; D1.9b: :on-behalf-of on a token issued without :asserts-user?

(def max-diagnostic-chars
  "The length bound on a caller-supplied string in `:got`.

   200 is chosen to clear the identifiers that must survive intact: a
   `sha256:…` digest is 71 characters, a token id is at most 128 by the format's
   own pattern, and a qualified graph name is well under 100 in practice.
   Anything longer is not an identifier — it is content the caller chose, and
   echoing it back without bound turns a diagnostic channel into a mirror."
  200)

(defn diag
  "Bound a caller-supplied value for `:got` (finding-v1 §5, owner ruling
   2026-08-25 on how to read it).

   §5 is a **classification**, not a blanket truncation:

   | class | treatment |
   |---|---|
   | token secrets, credentials, statement text, URLs, row values | never present |
   | operation-block content | truncated to a type description |
   | graph names, versions, tiers, digests, request ids | kept intact — they are the diagnosis |
   | any other caller-supplied string | kept, but length-bounded |

   So a graph name comes back readable and a full digest comes back whole,
   while a caller who sends a five-kilobyte \"graph name\" gets its type and
   length instead of an echo. Collections are bounded element-wise, because a
   list of names is still a list of identifiers.

   The first row is not handled here at all: values in that class reach
   `secret-safe-error`, which has no slot for them."
  [v]
  (cond
    (string? v) (if (<= (count v) max-diagnostic-chars)
                  v
                  (str "string(" (count v) " chars)"))
    (map? v)    (into {} (map (fn [[k x]] [k (diag x)])) v)
    (coll? v)   (into (empty v) (map diag) v)
    :else       v))

(defn make
  "Build a finding. `:got` goes through `diag`: identifiers survive intact,
   unbounded caller strings do not, and token secrets never get here at all —
   they go through `secret-safe-error`, which has no `:got` slot.

   This is finding-v1 §5 read as what it says — a list of what may never appear
   — rather than as \"truncate everything\". Truncating everything costs the
   diagnosis: a duplicated graph name or an unknown tier keyword is an
   identifier the caller already holds, and reporting it as a bare item count
   tells nobody anything."
  [code severity path message & {:keys [expected got refs] :as opts}]
  (assert (every? #{:expected :got :refs} (keys opts))
          (str "finding opts outside the closed key set: " (pr-str (keys opts))))
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got (diag got))
    (seq refs)       (assoc :refs (vec refs))))

(def redact
  "finding-v1 §5's truncation, for a `:got` that carries **operation-block
   content** rather than an identifier — the second row of `diag`'s table.
   Applied at the call site, because only the call site knows which class the
   value belongs to."
  dialect/redact)

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn secret-safe-error
  "A refusal about the token **value**, which by construction carries no `:got`.

   S5.2 says the secret never appears in logs, errors, traces, audits, DTOs or
   exports. Relying on `redact` for that is relying on a judgment call made
   correctly every time; this constructor simply has nowhere to put it."
  [code path message]
  {:code code :severity :error :path path :message message})

(defn valid?
  "Is x a well-formed finding (finding-v1 §2 closed key set + this domain)?"
  [x]
  (boolean
   (and (map? x)
        (contains? codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn sort-findings
  "Deterministic order (finding-v1 §6): by the printed form of
   `[path code message]`."
  [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))
