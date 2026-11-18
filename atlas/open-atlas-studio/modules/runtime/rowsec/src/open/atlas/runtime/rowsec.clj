(ns open.atlas.runtime.rowsec
  "S5.7 的行级安全门禁，逐 instance。跨模块只许 require 本 ns。

   ## 判据：声明且核对通过即放行

   [D5.14](../../../../../../docs/design/50-security.md#d514-行级安全门禁改为声明与接线核对)
   （owner 2026-09-03）**取代了 D5.6 的门禁默认**。D5.6 写「数据面没有执法者」时
   指的是没有**按声明**执法的机器；但把调用者属性写进 resolver `:input` 的那条
   路径一直是执法的——输入缺失时规划器判 unreachable，输入存在时
   `resolve-bindings` 把它绑成具名占位符进 `WHERE`。真正缺的是**一处核对**，
   E1 把它补在了切版本体检里（`:rowsec.wiring/*`）。

   于是本门禁的判据从

     「闭包里有 `:row-security` 声明 → 拒」

   改成

     「闭包里有声明**且不在 `:row-security-verified` 里** → 拒」。

   失败方向一个字没变，只是判定点前移了：从「发牌时拦一整张图」移到「切版本时
   拦一个接错线的 resolver」。前者拦得晚且粗；这里剩下的是**兜底**——一张 E1
   之前切出来的制品不带 `:row-security-verified`，它的声明没有被任何人核对过,
   所以照样拦（`artifact/health-facts` 把缺席读成空清单，关闭方向）。

   ## 豁免机制已退役

   `:rows-already-isolated` / `:not-multi-tenant` 两条理由与它们绑的五元组随
   D5.14 一起退役。豁免是「我看过这一版这份结论，接受它」——而 E1 之后没有「这份
   结论」可接受了：接线核对不过的图**切不出版本**，切得出的图不需要豁免。修的
   办法只剩一个，也正是该做的那个：**把 resolver 的接线改对**。

   `reasons`、`exempt!`、`exemption`、`exemptions`、`exemption-key` 与
   `:rowsec.exemption/*` 整个域一并删除——留一个会拒绝的桩，下一个人还得先读它才知道
   它不做事。**已落盘的豁免记录不删**：它是审计证据，回答「当初是谁、为什么点的头」,
   只是不再有任何一处读它。

   ## 三条入口都盖住

   门禁在**激活**与**发牌**两处各查一次，于是 D5.6 点名的三条入口全在里面：
   第一次发牌、已有 latest 跟随部署、直接钉一个危险版本。**已有 latest token 不
   构成绕过**——激活前检查失败，指针保持原值。

   ## 查的是整个闭包

   被引用图里一个声明了 `:row-security` 的 resolver，经合并同样进这份 env 的
   index（R3.2）。只看根 graph 等于漏掉一半。闭包事实由装配方注入。")

(def finding-codes
  #{:rowsec.gate/blocked          ; 闭包里有声明而未通过接线核对的 resolver（D5.14）
    :rowsec.gate/unknown-closure  ; 闭包事实取不到（成员不在架子上／有环）
    :rowsec.capabilities/missing})

(defn- error [code path message & {:keys [expected got]}]
  (cond-> {:code code :severity :error :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got got)))

(defn finding? [x]
  (boolean (and (map? x) (contains? finding-codes (:code x))
                (contains? #{:error :warning :info} (:severity x))
                (vector? (:path x)) (string? (:message x)))))

(defrecord Rowsec [instance closure-facts])

(defn open!
  "`(open! instance {:closure-facts …})` → `{:store s :findings []}`。

   `:closure-facts` `(fn [graph version] → [{:graph :version :content-hash
   :row-security-declared :row-security-verified :finding-set-hash}…] | nil)`。
   缺它就**拒绝启动**：
   一个查不到闭包的门禁只会一律放行或一律拒绝，两种都不是门禁。"
  [instance {:keys [closure-facts]}]
  (if-not (ifn? closure-facts)
    {:findings [(error :rowsec.capabilities/missing [:capabilities]
                       (str "the gate needs this instance's closure facts; without them it "
                            "would either pass everything or refuse everything, and neither "
                            "of those is a gate"))]}
    {:store (->Rowsec instance closure-facts) :findings []}))

;; ---------------------------------------------------------------------------
;; 判
;; ---------------------------------------------------------------------------

(defn check
  "`(check store graph version)` → `[finding…]`（空 = 放行）。

   **D5.14 的判据**：闭包里每个成员，`:row-security-declared` 减去
   `:row-security-verified` 若非空即拒——即「声明了、但接线核对没通过」。全部
   核对通过的成员放行；没有声明的成员本来就不在考虑范围内。

   为什么是差集而不是一个布尔：拒绝要**点得出是哪几个 resolver**。作者拿到
   「这张图不行」修不了任何东西，拿到 `[\"resolver:o_by_id\"]` 才知道去看哪一条。

   E1 之前切出的制品不带 `:row-security-verified`，`artifact/health-facts` 把
   缺席读成空清单——于是它的每一条声明都算未核对，照样拦。**这是关闭方向**：
   那些声明确实没有被任何人核对过，重切一次版本就好。

   激活与发牌两处各调一次。**闭包取不到就是拒绝**，不是放行——一个答不出
   「闭包里有什么」的门禁，放行等于在不知情的前提下承担风险。"
  [{:keys [closure-facts]} graph version]
  (if-let [members (closure-facts graph version)]
    (vec (for [m members
               :let [unverified (vec (sort (remove (set (:row-security-verified m))
                                                   (:row-security-declared m))))]
               :when (seq unverified)]
           (error :rowsec.gate/blocked [:closure]
                  (str "this graph's load closure declares :row-security on resolvers whose "
                       "wiring was not verified at cut time (D5.14), so activating it or "
                       "issuing a token for it would hand out every tenant's rows; fix the "
                       "declaration to name an :input the statement actually binds, then cut "
                       "the version again — exemptions retired with that ruling")
                  :expected []
                  :got {:graph (:graph m) :version (:version m)
                        :resolvers unverified})))
    [(error :rowsec.gate/unknown-closure [:closure]
            (str "this instance cannot enumerate the load closure of that version, so the gate "
                 "cannot tell whether anything in it declares :row-security; a gate that cannot "
                 "answer refuses rather than assumes")
            :got {:graph graph :version version})]))
