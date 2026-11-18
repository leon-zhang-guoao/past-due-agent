(ns open.atlas.common.control-protocol.impl.envelope
  "控制信封的形状（D3.16）。**封闭键面**：多一个键就是拒绝，不是忽略。

   为什么封闭：信封是**签名输入**。若未知键被忽略，攻击者可以在签名覆盖不到的
   地方塞东西；若未知键被保留却不进签名，两侧对「签的是什么」的理解就分了家。
   封闭之后这两种情形都不存在——签名覆盖整个信封，而整个信封只有这些键。

   ## 信封里**没有** service 是谁

   service 身份来自**已认证的通道**（请求认证，见 impl.signature），由壳验完之后
   作为单独的参数交给命令核心。**自称的身份不是身份**：把它放进信封，等于让请求
   体自己声明它是谁签的。

   ## 五个 op

   `:offer` → `:stage` → `:load` → `:activate` / `:rollback`（D3.12 的控制面动词 +
   D4.11 的「先送清单再要缺失」）。`:load` 就是 R3.3 说的**装载**——依赖检查落在
   它上面，不落在激活上。"
  (:require [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol.impl.finding :as f])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

(def max-assertion-seconds
  "一份控制信封的**最长有效期**。两侧共用：studio 不该签一份 server 会拒的断言。

   为什么必须有上限：没有它，「短时断言」可以签几年——而 actor 断言就是那把
   代替 owner 说话的钥匙，它的有效期越长，一次泄漏的窗口就越大。300 秒够一次
   部署往返，不够拿去存着。"
  300)

(def protocol-version
  "`:control/version`。v1 的 reader 只接受 1（同 D4.7 对制品格式版本的纪律：
   不认识的版本是拒绝，不是尽力而为）。"
  1)

(def ops
  "封闭词表。词表外的 op 是拒绝——一个不认识的动词被静默忽略，调用方会以为
   自己部署过了。"
  #{:offer :stage :load :activate :rollback})

(def envelope-keys
  "信封的封闭键面。"
  #{:control/version :control/op :control/request-id :control/deployment-id
    :control/audience :control/issued-at :control/expires-at
    :control/actor :control/payload})

(def actor-keys
  "actor 断言的封闭键面。`:actor/principal` 是 D5.10 的不可变二元组
   `(issuer, subject)`——**邮箱与显示名只能作属性，永远不作主键**：人会改名、
   换邮箱、离职后邮箱被回收给新人，而审计必须在这些之后仍指向同一个人。"
  #{:actor/principal :actor/key-id :actor/signature})

(def principal-keys #{:issuer :subject})

(def payload-keys
  "每个 op 的 payload 封闭键面。"
  {:offer    #{:graph :version :closure}
   :stage    #{:artifact-digest :files}
   :load     #{:graph :version}
   :activate #{:graph :version :expected-current}
   :rollback #{:graph :to-version :expected-current}})

(def closure-member-keys
  "`:offer` 清单里一条成员。**带 `:artifact-digest`**：server 要靠它在
   `:load`/`:activate` 时重新校验，而制品文件里没有可信的自报值（D4.3 刻意不把
   digest 写回三份文件）。"
  #{:graph :version :content-hash :artifact-digest})

;; ---------------------------------------------------------------------------
;; 基本形状
;; ---------------------------------------------------------------------------

(defn- non-blank-string? [x] (and (string? x) (not (str/blank? x))))

(defn- digest-string?
  "`\"sha256:\" + 64 位小写 hex`（D4.3 的形状）。"
  [x]
  (boolean (and (string? x) (re-matches #"sha256:[0-9a-f]{64}" x))))

(defn- version-number?
  "版本是**正整数**。菜谱写 `\"v3\"`，信封里一律整数——两处混着写就会有两个
   「同一个版本」的拼法（同 D4.4 对制品信封的要求）。"
  [x]
  (and (int? x) (pos? x)))

(defn parse-instant
  "RFC3339 UTC、秒精度、`Z` 结尾 → `Instant`；不是那个形状或**不是真实日期**
   → nil。

   正则只管拼法，`2026-13-45T99:99:99Z` 是能过正则的。所以形状之后还要真解析
   一次——一个解析不出来的时间戳，比较起来会给出一个看似成立的答案。"
  [x]
  (when (and (string? x) (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" x))
    (try (Instant/parse x) (catch DateTimeParseException _ nil))))

(defn- timestamp?
  "**只认一种拼法**：时间进签名输入，两种等价拼法就是两串不同的字节。"
  [x]
  (some? (parse-instant x)))

(defn- closed-key-finding [path present allowed what]
  (let [extra (vec (sort (map str (remove allowed present))))]
    (when (seq extra)
      (f/error :control.envelope/malformed path
               (str what " carries key(s) outside the closed key set")
               :expected (vec (sort (map str allowed)))
               :got extra))))

(defn- missing-key-finding [path present required what]
  (let [missing (vec (sort (map str (remove (set present) required))))]
    (when (seq missing)
      (f/error :control.envelope/malformed path
               (str what " is missing required key(s)")
               :expected missing))))

;; ---------------------------------------------------------------------------
;; 校验
;; ---------------------------------------------------------------------------

(defn- check-actor [actor]
  (let [p [:control/actor]]
    (cond
      (not (map? actor))
      [(f/error :control.envelope/malformed p
                "the actor assertion must be a map"
                :got actor)]

      :else
      (let [ks (keys actor)
            {:actor/keys [principal key-id signature]} actor]
        (vec
         (keep
          identity
          [(closed-key-finding p ks actor-keys "the actor assertion")
           (missing-key-finding p ks actor-keys "the actor assertion")
           (when (and (some? principal)
                      (not (and (map? principal)
                                (= principal-keys (set (keys principal)))
                                (non-blank-string? (:issuer principal))
                                (non-blank-string? (:subject principal)))))
             (f/error :control.envelope/malformed (conj p :actor/principal)
                      (str "the actor principal is the immutable pair "
                           "{:issuer … :subject …} (D5.10); an email or display name "
                           "is an attribute, never the key — people rename, change "
                           "address, and a departed address gets reassigned, while the "
                           "audit trail must still point at the same person")
                      :expected [:issuer :subject]
                      :got principal
                      :refs ["D5.10"]))
           (when (and (some? key-id) (not (non-blank-string? key-id)))
             (f/error :control.envelope/malformed (conj p :actor/key-id)
                      "the actor key-id must be a non-empty string (keys rotate)"
                      :got key-id))
           (when (and (some? signature) (not (non-blank-string? signature)))
             (f/error :control.signature/malformed (conj p :actor/signature)
                      "the actor signature must be a non-empty string"
                      :got signature))]))))))

(defn- check-closure-member [i m]
  (let [p [:control/payload :closure i]]
    (if-not (map? m)
      [(f/error :control.envelope/payload-mismatch p
                "a closure member must be a map" :got m)]
      (vec
       (keep
        identity
        [(closed-key-finding p (keys m) closure-member-keys "a closure member")
         (missing-key-finding p (keys m) closure-member-keys "a closure member")
         (when (and (some? (:graph m)) (not (non-blank-string? (:graph m))))
           (f/error :control.envelope/payload-mismatch (conj p :graph)
                    "the qualified graph name must be a non-empty string" :got (:graph m)))
         (when (and (some? (:version m)) (not (version-number? (:version m))))
           (f/error :control.envelope/payload-mismatch (conj p :version)
                    "a version is a positive integer" :got (:version m)))
         (when (and (some? (:content-hash m)) (not (digest-string? (:content-hash m))))
           (f/error :control.envelope/payload-mismatch (conj p :content-hash)
                    "a content-hash is \"sha256:\" + 64 lowercase hex (D4.3)"
                    :got (:content-hash m)))
         (when (and (some? (:artifact-digest m)) (not (digest-string? (:artifact-digest m))))
           (f/error :control.envelope/payload-mismatch (conj p :artifact-digest)
                    "an artifact-digest is \"sha256:\" + 64 lowercase hex (D4.3)"
                    :got (:artifact-digest m)))])))))

(defn- check-payload [op payload]
  (let [p [:control/payload]]
    (cond
      (not (map? payload))
      [(f/error :control.envelope/payload-mismatch p "the payload must be a map" :got payload)]

      (nil? (payload-keys op)) []          ; op 本身非法，另有 finding

      :else
      (let [allowed (payload-keys op)
            ks      (keys payload)
            ;; `:expected-current` 允许 nil——**nil 是「我认为这是第一次激活」
            ;; 这句断言**，不是「不检查」。所以它在必填之列，只是值可以是 nil。
            required allowed]
        (vec
         (concat
          (keep identity
                [(closed-key-finding p ks allowed (str "the " (name op) " payload"))
                 (missing-key-finding p ks required (str "the " (name op) " payload"))
                 (when (and (contains? payload :graph)
                            (not (non-blank-string? (:graph payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :graph)
                            "the qualified graph name must be a non-empty string"
                            :got (:graph payload)))
                 (when (and (contains? payload :version)
                            (not (version-number? (:version payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :version)
                            "a version is a positive integer" :got (:version payload)))
                 (when (and (contains? payload :to-version)
                            (not (version-number? (:to-version payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :to-version)
                            "a version is a positive integer" :got (:to-version payload)))
                 (when (and (contains? payload :expected-current)
                            (some? (:expected-current payload))
                            (not (version-number? (:expected-current payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :expected-current)
                            (str "the expected current version is a positive integer, or nil "
                                 "meaning \"I believe this graph has never been activated\" "
                                 "— nil is an assertion, not \"skip the check\"")
                            :got (:expected-current payload)))
                 (when (and (contains? payload :artifact-digest)
                            (not (digest-string? (:artifact-digest payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :artifact-digest)
                            "an artifact-digest is \"sha256:\" + 64 lowercase hex (D4.3)"
                            :got (:artifact-digest payload)))
                 (when (and (contains? payload :files)
                            (not (and (map? (:files payload))
                                      ;; **键集合严格等于**那三份，不是「都是字符串就行」:
                                      ;; 这是 wire shape，缺一份或多一份该在 common 就拒绝,
                                      ;; 不该等到 server 层。文件名与顺序由 artifact-v1 定死
                                      ;; （digest 正是按那个顺序算的，D4.3）。
                                      (= (set artifact/artifact-files)
                                         (set (keys (:files payload))))
                                      (every? string? (vals (:files payload))))))
                   (f/error :control.envelope/payload-mismatch (conj p :files)
                            (str "files must carry exactly the three artifact files as "
                                 "{filename → text}")
                            :expected (vec artifact/artifact-files)
                            :got (vec (sort (map str (keys (:files payload)))))))
                 (when (and (contains? payload :closure)
                            (not (vector? (:closure payload))))
                   (f/error :control.envelope/payload-mismatch (conj p :closure)
                            "the closure is a vector of members, and it INCLUDES the root
                             version itself — the server does one set difference and never
                             has to guess whether the root counts"
                            :got (:closure payload)))])
          (when (vector? (:closure payload))
            (mapcat (fn [[i m]] (check-closure-member i m))
                    (map-indexed vector (:closure payload))))
          ;; 坐标重复会被下游的 `member-index` 之类**静默折叠**：两条不同的
          ;; digest 落到同一个键上，赢的那条取决于遍历顺序。拒绝，不去重。
          (when (vector? (:closure payload))
            (let [coords (map (juxt :graph :version) (:closure payload))
                  dupes  (vec (sort-by pr-str (keep (fn [[k n]] (when (> n 1) k))
                                                    (frequencies coords))))]
              (concat
               (when (seq dupes)
                 [(f/error :control.envelope/payload-mismatch [:control/payload :closure]
                           "the closure lists the same (graph, version) more than once"
                           :got dupes)])
               ;; 清单**含根自己**（server 只做一次集合差）——所以根必须在里面,
               ;; 且只有一次。不在就是清单没说全，多于一次上面已经拒了。
               (when (and (= :offer op)
                          (not= 1 (count (filter #(= [(:graph payload) (:version payload)] %)
                                                 coords))))
                 [(f/error :control.envelope/payload-mismatch [:control/payload :closure]
                           (str "the closure must contain the root version exactly once — it "
                                "includes the root so the server does one set difference and "
                                "never has to guess whether the root counts")
                           :expected [(:graph payload) (:version payload)])]))))))))))

(defn validate
  "控制信封 → `[finding…]`（空 = 形状合法）。

   **只判形状，不判权限、不判时效、不验签**——那三样各有各的输入（钥匙串、
   时钟、instance 身份），住在验签与命令那一层。这里回答的是「这是不是一份
   长得对的信封」。"
  [env]
  (if-not (map? env)
    [(f/error :control.envelope/malformed [] "the control envelope must be a map" :got env)]
    (let [ks (keys env)
          {:control/keys [version op payload actor audience
                          request-id deployment-id issued-at expires-at]} env]
      (f/sort-findings
       (vec
        (concat
         (keep identity
               [(closed-key-finding [] ks envelope-keys "the control envelope")
                (missing-key-finding [] ks envelope-keys "the control envelope")
                (when (and (contains? env :control/version) (not= protocol-version version))
                  (f/error :control.envelope/unsupported-version [:control/version]
                           "this reader only accepts control protocol version 1"
                           :expected protocol-version :got version))
                (when (and (contains? env :control/op) (not (contains? ops op)))
                  (f/error :control.envelope/unknown-op [:control/op]
                           "unknown control operation"
                           :expected (vec (sort ops)) :got op))
                (when (and (contains? env :control/audience) (not (non-blank-string? audience)))
                  (f/error :control.envelope/malformed [:control/audience]
                           (str "the audience is this instance's id; without it a signed "
                                "envelope replays onto another instance")
                           :got audience))
                (when (and (contains? env :control/request-id) (not (non-blank-string? request-id)))
                  (f/error :control.envelope/malformed [:control/request-id]
                           "the request id is the idempotency key; it must be a non-empty string"
                           :got request-id))
                (when (and (contains? env :control/deployment-id)
                           (not (non-blank-string? deployment-id)))
                  (f/error :control.envelope/malformed [:control/deployment-id]
                           (str "the deployment id spans the commands of one deployment "
                                "(offer → stage → load → activate); the request id is the "
                                "idempotency key of a single command")
                           :got deployment-id))
                (when (and (contains? env :control/issued-at) (not (timestamp? issued-at)))
                  (f/error :control.envelope/malformed [:control/issued-at]
                           "RFC3339 UTC with second precision, e.g. 2026-08-24T09:00:00Z"
                           :got issued-at))
                (when (and (contains? env :control/expires-at) (not (timestamp? expires-at)))
                  (f/error :control.envelope/malformed [:control/expires-at]
                           "RFC3339 UTC with second precision, e.g. 2026-08-24T09:05:00Z"
                           :got expires-at))])
         (when (contains? env :control/actor) (check-actor actor))
         (when (and (contains? env :control/payload) (contains? ops op))
           (check-payload op payload))))))))
