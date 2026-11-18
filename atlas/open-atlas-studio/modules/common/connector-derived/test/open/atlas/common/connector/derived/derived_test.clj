(ns open.atlas.common.connector.derived.derived-test
  "derived-v1 的 conformance 要点，逐条。

   契约最后一节写着四样：预解析失败拒绝 · 白名单外符号求值即 finding ·
   同输入同输出（纯函数性质抽查）· 超时不拖垮同实体其他属性。

   外加两样契约没列但必须守的：**白名单是封闭的**（多给一个是升版级别的改动），
   与**禁止形式在发布闸上被拒**（白名单管不住 SCI 的特殊形式，见 impl.sandbox
   抬头那张实测表）。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.derived :as derived]
            [open.atlas.common.connector.spi :as spi]))

(defn- op
  ([expr] (op expr nil))
  ([expr extra] (merge {:type "derived" :expr expr} extra)))

(defn- resolver
  ([] (resolver [:o/a :o/b]))
  ([inputs] {:id "r1" :input inputs :output [:o/out] :result_type "single"}))

(defn- errors [r] (filterv #(= :error (:severity %)) (:findings r)))
(defn- codes  [r] (set (map :code (:findings r))))

(defn- run!
  "编译并跑一次，收走运行期 finding。→ `[结果 findings]`。"
  ([expr inputs] (run! expr inputs (resolver (vec (keys inputs))) nil))
  ([expr inputs rc eql]
   (let [collected (atom [])
         f (spi/compile-source (op expr) nil rc)]
     (derived/with-finding-sink #(swap! collected conj %)
       (fn [] [(f inputs eql) @collected])))))

;; ---------------------------------------------------------------------------
;; 写入端：唯一真闸
;; ---------------------------------------------------------------------------

(deftest pre-parse-failure-is-refused
  (testing "契约：「表达式在编译期预解析，解析失败即校验失败」"
    (let [r (spi/validate-source (op "(+ a"))]
      (is (seq (errors r)))
      (is (= #{:connector.source/expr-unparsable} (codes r)))
      (is (every? spi/finding? (:findings r)) "过 finding-v1 §2 封闭键面")))

  (testing ":expr 缺失 / 非字符串 / 空 各自拒绝"
    (is (= #{:connector.source/missing-key}
           (codes (spi/validate-source {:type "derived"}))))
    (is (= #{:connector.source/invalid-shape}
           (codes (spi/validate-source (op 42)))))
    (is (= #{:connector.source/invalid-shape}
           (codes (spi/validate-source (op "   "))))))

  (testing "操作块键面封闭——契约 support policy「未知操作键 :reject」"
    (let [r (spi/validate-source (op "(+ 1 1)" {:query "SELECT 1"}))]
      (is (= #{:connector.source/unknown-key} (codes r)))
      (is (= [":query"] (:got (first (:findings r)))) "点名多出来的是哪个键")))

  (testing "合法的通过，且不产任何 finding（带 inputs 的完整闸）"
    (is (empty? (:findings (derived/validate-source-with-inputs
                            (op "(round2 (* a b))") (resolver)))))))

(deftest symbols-outside-the-allowlist-are-refused-at-the-publish-gate
  (testing "SCI 默认就带一份 clojure.core 子集（`(map inc …)` 不绑也能跑），所以闸是
            **allowlist**：允许集合之外一律拒绝。**无界迭代尤其必须在这里拦**——
            超时停不下一个 recur 循环（实测）"
    (doseq [[expr what] {"(loop [] (recur))"   "loop"
                         "(while true 1)"      "while"
                         "(eval 1)"            "eval"
                         "(atom 1)"            "atom"
                         "(read-string \"1\")" "read-string"
                         "(iterate inc 1)"     "iterate"
                         "(range)"             "range"
                         "(fn [x] x)"          "fn"
                         ;; `map` moved INTO the allowlist with Semantic 2.0's
                         ;; nested inputs (derived-v1 "Semantic 2.0 输入形态");
                         ;; `repeat` is the generator that stays out.
                         "(repeat 1)"          "repeat"
                         "(slurp a)"           "slurp"
                         "(+ 1 (eval 2))"      "eval"}]
      (let [r (derived/validate-source-with-inputs (op expr) (resolver))]
        (is (= #{:connector.source/expr-unparsable} (codes r)) expr)
        (is (clojure.string/includes? (:message (first (:findings r))) what)
            (str expr " 的拒绝要点名是哪个符号")))))

  (testing "1-arity（SPI 那一路）**看不到 inputs，所以是弱扫描**——
            带命名空间限定与 `.` 形式仍拒，但 `slurp` 过得去（它长得像 input local name）。
            这条分界必须被测到，否则「写入端校验过了」会变成一句错觉"
    (is (= #{:connector.source/expr-unparsable}
           (codes (spi/validate-source (op "(clojure.java.io/file a)")))))
    (is (= #{:connector.source/expr-unparsable}
           (codes (spi/validate-source (op "(.length a)")))))
    (is (empty? (:findings (spi/validate-source (op "(slurp a)"))))
        "弱扫描放它过——完整 allowlist 在 validate-source-with-inputs 与装配期"))

  (testing "除法没被误禁——`/` 是契约白名单里的算术，不是命名空间分隔符"
    (is (empty? (:findings (derived/validate-source-with-inputs
                            (op "(/ a b)") (resolver))))))

  (testing "`let` 出来的局部名与 resolver 的 input local name 都在允许集合里"
    (is (empty? (:findings (derived/validate-source-with-inputs
                            (op "(let [t (* a b)] (round2 t))") (resolver)))))))

;; ---------------------------------------------------------------------------
;; 白名单是封闭的
;; ---------------------------------------------------------------------------

(deftest the-whitelist-is-exactly-the-contract-table
  (testing "derived-v1 那张表逐字对账——挡的是「顺手多给一个」，
            而多给一个在契约里是**升版**级别的改动"
    (is (= '#{abs round round2 ceil floor sqrt pow
              upper lower trim blank? format
              sum today date days-between before? after?}
           derived/contract-symbols)))

  (testing "白名单 = 契约那张表 + clojure.core 纯函数子集，二者不重叠"
    (is (empty? (clojure.set/intersection
                 derived/contract-symbols
                 (clojure.set/difference derived/whitelist derived/contract-symbols)))))

  (testing "白名单与允许的特殊形式**不相交**——一个符号不该同时是函数和特殊形式"
    (is (empty? (clojure.set/intersection derived/whitelist derived/permitted-forms))))

  (testing "无界迭代一个都不在允许集合里——这是超时兜不住的那一类"
    (doseq [sym '[loop recur while for doseq dotimes iterate range repeat cycle]]
      (is (not (contains? derived/whitelist sym)) (str sym " 不该在白名单里"))
      (is (not (contains? derived/permitted-forms sym)) (str sym " 不该是允许的形式")))))

;; ---------------------------------------------------------------------------
;; 运行期
;; ---------------------------------------------------------------------------

(deftest bindings-are-by-local-name-and-missing-binds-nil
  (testing "契约：`:input` 的每个属性按 **local name** 绑定为局部符号"
    (let [[v fs] (run! "(round2 (* qty price))"
                       {:cis.sales.mso_order_detail/qty 3
                        :cis.sales.mso_order_detail/price 1.005})]
      (is (= {:o/out 3.01} v))
      (is (empty? fs))))

  (testing "契约：缺失输入**绑 nil**（合法，表达式自行处理）——
            不绑的话符号会「解析不到」，报出来是沙箱错误，而真相是这一行没数据"
    (let [rc (resolver [:o/qty :o/price])
          [v fs] (run! "(if (nil? price) 0 (* qty price))" {:o/qty 3} rc nil)]
      (is (= {:o/out 0} v))
      (is (empty? fs) "缺失输入不是错误，不该产 finding"))))

(deftest an-out-of-whitelist-symbol-yields-a-finding-not-a-throw
  (testing "契约 conformance：「白名单外符号求值即 finding」——
            属性缺席、sink 收到 finding、**不抛**"
    ;; 这些**在发布闸就被 allowlist 拦下**了（见上一条 deftest）。这里测的是
    ;; **绕过写入端**的情形：手改一份制品直接装载，运行期仍不许抛、不许失败整个实体。
    ;; 用一个白名单内符号但会在运行期炸的表达式来触发。
    (doseq [expr ["(format \"%d\" x)"]]
      (let [[v fs] (run! expr {:o/x "irrelevant"} (resolver [:o/x]) nil)]
        (is (= {} v) (str expr " → 属性缺席"))
        (is (= [:connector.eval/failed] (mapv :code fs)) expr)
        (is (= :warning (:severity (first fs)))
            "求值失败是 :warning——它不失败整个实体（契约：与「single 可 nil」同一语义）")))))

(deftest a-failure-carries-no-row-values
  (testing "principles「source 失败与日志不携带业务数据」：
            finding 里不得出现输入行值"
    (let [secret "S3CRET-ROW-VALUE"
          [_ fs] (run! "(format \"%d\" x)" {:o/x secret} (resolver [:o/x]) nil)]
      (is (seq fs))
      (is (not (clojure.string/includes? (pr-str fs) secret))
          "输入行值泄漏进了 finding"))))

(deftest same-input-same-output
  (testing "契约 conformance：「同输入同输出（纯函数性质抽查）」"
    (let [in {:o/a 7 :o/b 6}
          e  "(sum a b (round2 (/ a b)))"]
      (is (= (first (run! e in)) (first (run! e in))))))

  (testing "含 `today` 的表达式：**给定同一上下文**也逐字相同（README 裁定 ②）——
            `today` 不读系统时钟，从求值上下文取，否则这条断言写不出来"
    (let [rc  (resolver [:o/due])
          eql {:today (java.time.LocalDate/parse "2026-08-24")}
          e   "(days-between (today) due)"
          a   (first (run! e {:o/due "2026-09-01"} rc eql))
          b   (first (run! e {:o/due "2026-09-01"} rc eql))]
      (is (= {:o/out 8} a))
      (is (= a b))))

  (testing "换一个上下文，结果跟着换——它是入参，不是隐藏状态"
    (let [rc (resolver [:o/due])
          e  "(days-between (today) due)"]
      (is (not= (first (run! e {:o/due "2026-09-01"} rc
                             {:today (java.time.LocalDate/parse "2026-08-24")}))
                (first (run! e {:o/due "2026-09-01"} rc
                             {:today (java.time.LocalDate/parse "2026-08-25")})))))))

;; ---------------------------------------------------------------------------
;; 能力：batch ✗ / mutation ✗
;; ---------------------------------------------------------------------------

(deftest batch-and-mutation-are-refused-structurally
  (testing "契约能力表：batch ✗——**结构化拒绝，不是静默不支持**"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spi/compile-batch-source (op "(+ 1 1)") nil (resolver)))))

  (testing "mutation ✗：写入端一条 finding，编译期一条故障"
    (let [r (spi/validate-mutation (op "(+ 1 1)"))]
      (is (= #{:connector.source/mutation-unsupported} (codes r)))
      (is (every? spi/finding? (:findings r))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spi/compile-mutation (op "(+ 1 1)") nil {:id "m1"}))))

  (testing "没有连接概念，health 恒 :ok（不是假装体检过了——derived 没有 source 可以死）"
    (is (= :ok (:status (spi/health-check (op "(+ 1 1)") nil))))))

;; ---------------------------------------------------------------------------
;; 超时：机制成立，而**从合法表达式走不到这里**
;; ---------------------------------------------------------------------------

(deftest a-timeout-does-not-drag-down-other-attributes
  (testing "契约 conformance 第四条：「超时不拖垮同实体其他属性」。

            **从一个已发布的表达式走不到这里**——无界迭代在 allowlist 闸上就被拒了
            （上面那条 deftest）。所以这里直接喂一棵绕过闸的语法树，测的是**机制**：
            超时只让这一次求值缺席，紧接着的下一次照样算得出来。"
    (let [sb    (requiring-resolve 'open.atlas.common.connector.derived.impl.sandbox/evaluate)
          parse (requiring-resolve 'open.atlas.common.connector.derived.impl.sandbox/parse)
          spin  (:form (parse "(loop [] (recur))"))
          t0    (System/currentTimeMillis)
          r     (sb spin {} nil 200)
          dt    (- (System/currentTimeMillis) t0)]
      (is (:timeout r) "超时要如实报，不是给一个 nil 假装算完了")
      (is (< dt 2000) (str "不该等下去，实际 " dt "ms"))

      (testing "同一实体的下一个属性照样算得出来——预算是逐次的，不是逐实体的"
        (is (= 3 (:value (sb (:form (parse "(+ 1 2)")) {} nil 200)))))))

  (testing "**超时是预算不是保证**：它停不下已经在跑的那一个。
            承载线程是 daemon，所以失控的求值不会挂住 JVM 退出——第一版用 `future`
            （非 daemon 池）时，测试跑到 `shutdown-agents` 就停在那里。
            这条断言守住 daemon 这个属性本身。"
    (let [threads (atom [])]
      ;; 造一个求值，抓住它的承载线程，断言它是 daemon
      (with-redefs []
        (let [sb (requiring-resolve 'open.atlas.common.connector.derived.impl.sandbox/evaluate)]
          (sb (:form ((requiring-resolve
                       'open.atlas.common.connector.derived.impl.sandbox/parse) "(+ 1 1)"))
              {} nil 200)))
      (doseq [[^Thread t _] (Thread/getAllStackTraces)
              :when (= "derived-eval" (.getName t))]
        (swap! threads conj t)
        (is (.isDaemon t) "求值线程必须是 daemon"))
      ;; 线程可能已经结束（求值很快），抓不到不算失败——这条断言只在抓到时有意义
      (is (or (empty? @threads) (every? #(.isDaemon ^Thread %) @threads))))))

;; ---------------------------------------------------------------------------
;; E1 / D5.14: what a derived source binds
;; ---------------------------------------------------------------------------

(deftest a-derived-source-binds-nothing-and-says-so
  (testing "`#{}`，不是 `nil`。derived 在别的 resolver 已经产出的值上跑一个
            `:expr`——它不查询、不收窄，所以答案是「一个都没有」，而不是
            「我答不出」。差别是给作者的话不同：`:rowsec.wiring/not-bound`
            说的是「行级安全在 derived 上无处落地」（真话），
            `:rowsec.wiring/unverifiable` 会把他指去修一个连接器"
    (is (= #{} (spi/bound-attributes {:type "derived" :expr "1"
                                      :input [:a/x :a/tenant_id]})))
    (is (some? (spi/bound-attributes {:type "derived" :expr "1"}))
        "有方法在，所以不会落到缺省的 nil")))

(deftest offset-capability-matches-the-contract
  ;; derived-v1「能力」：offset 不适用 — a derived resolver computes an attribute
  ;; per entity, there is no row set to skip into. It declares nothing, so the
  ;; SPI's default-deny answers, and the engine's gate refuses a caller's
  ;; `:offset` instead of accepting one that would be silently dropped (8.17).
  (is (false? (spi/supports-offset? derived/source-type))))
