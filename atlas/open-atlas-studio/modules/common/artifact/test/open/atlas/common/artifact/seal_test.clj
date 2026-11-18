(ns open.atlas.common.artifact.seal-test
  "`encode_content` 与 `seal_graph` 是两个纯函数（D4.10-a）；
   manifest/report 的职责分工与稳定排序（D4.9/D4.3）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]))

(def ^:private model
  (:model (dialect/read-dialect (slurp (io/resource "graph.edn")))))

(def ^:private prov
  ;; `:tier` 随档位退役（owner 裁定 2026-08-27）离开了 provenance 的封闭键面。
  {:workspace "core-team" :graph "b1" :version 3
   :cut-at "2026-08-22T00:00:00Z" :cut-by "jgao"})

(defn- seal
  "缺省 `:quality` 用一份最小的真度量——`:quality` 收「非空 `{keyword → any}`」，
   `{}` 不再是合法入参。**用 `contains?` 而不是 `or` 取值**：否则显式传 nil
   会被悄悄换成缺省，而那正是本 ns 要测的一类非法入参。"
  [& {:as opts}]
  (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
        pick (fn [k default] (if (contains? opts k) (get opts k) default))]
    (artifact/seal-graph content-edn content-hash prov
                         (pick :health {}) (pick :diff {})
                         (pick :quality {:coverage 1.0}))))

(defn- codes [findings] (set (map :code findings)))

;; --- 纯 ---------------------------------------------------------------------

(deftest both-functions-are-pure-same-inputs-same-bytes
  (testing "纯函数不得读取时钟、当前用户、环境变量或上一版本（D4.10-a）——
            同样的入参在不同时刻产出逐字节相同的三份文件"
    (let [a (seal) _ (Thread/sleep 5) b (seal)]
      (is (= (:files a) (:files b)))
      (is (= (:artifact-digest a) (:artifact-digest b))))))

(deftest neither-function-reads-ambient-state
  (testing "把时钟以外的环境也拨一遍：user.name 与 locale 都不该影响输出"
    (let [before (seal)
          prev-user (System/getProperty "user.name")
          prev-locale (java.util.Locale/getDefault)]
      (try
        (System/setProperty "user.name" "somebody-else")
        (java.util.Locale/setDefault (java.util.Locale. "tr" "TR"))  ; 著名的 i/İ locale
        (is (= (:files before) (:files (seal))))
        (is (= (:content-hash (artifact/encode-content model []))
               (artifact/content-hash-of-text (get (:files before) "content.edn"))))
        (finally
          (System/setProperty "user.name" prev-user)
          (java.util.Locale/setDefault prev-locale))))))

(deftest encode-and-seal-are-separable
  (testing "编码只吃内容，密封还吃发布上下文——两次密封同一份 content.edn
            产出不同的 manifest，而 content.edn 与 content-hash 一字不改"
    (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
          a (artifact/seal-graph content-edn content-hash prov {} {} {:coverage 1.0})
          b (artifact/seal-graph content-edn content-hash
                                 (assoc prov :cut-by "someone-else") {} {} {:coverage 1.0})]
      (is (= content-edn (get (:files a) "content.edn") (get (:files b) "content.edn")))
      (is (not= (:manifest-edn a) (:manifest-edn b)))
      (is (not= (:artifact-digest a) (:artifact-digest b))))))

;; --- 分工（D4.9）------------------------------------------------------------

(deftest manifest-carries-machine-facts-report-carries-human-facts
  (let [s (seal :health {:carried-only-keys [:a/b] :row-security-declared ["r1"]}
                :diff {:previous-version 2 :added-attributes [:a/c]}
                :quality {:coverage 0.75})
        m (edn/read-string (:manifest-edn s))
        r (edn/read-string (:report-edn s))]
    (testing "manifest 只带机器装载或治理要判定的事实"
      (is (= #{:artifact/format-version :content-hash :provenance :health} (set (keys m))))
      (is (= [:a/b] (get-in m [:health :carried-only-keys])))
      (is (= ["r1"] (get-in m [:health :row-security-declared])))
      (is (true? (get-in m [:health :structural-ok?]))))
    (testing "report 带面向人的质量指标与差异；装载不依赖它"
      (is (= #{:artifact/format-version :content-hash :diff :quality} (set (keys r))))
      (is (= 2 (get-in r [:diff :previous-version])))
      (is (= 0.75 (get-in r [:quality :coverage]))))
    (testing "同一个指标只允许一个权威位置：详细度量不出现在 manifest"
      (is (not (str/includes? (:manifest-edn s) "coverage"))))
    ;; 曾经还有一条「graph `:tier` 只影响治理，所以在 manifest 而不在
    ;; content.edn（D4.2）」。档位退役之后（owner 裁定 2026-08-27）两处都没有
    ;; 它——这一条测的分工仍在，只是不再有一个档位可拿来演示。
    (testing "content.edn 里没有任何治理字段"
      (is (not (str/includes? (get (:files s) "content.edn") ":tier"))))))

(deftest health-lists-are-always-written-even-when-empty
  (testing "空清单是「查过，没有」，缺席是「不知道查没查过」——而 server 无法
            重算这些结论（D4.1）"
    (let [m (edn/read-string (:manifest-edn (seal)))]
      ;; `:restricted-from-references` 随档位与引用一起退役（D3.17 + owner 裁定
      ;; 2026-08-27），不再是体检的一项结论。
      ;; `:row-security-verified` 是 E1 / D5.14 加的第五项：空清单在这里尤其
      ;; 要写出来——门禁读「声明减已核对」，缺席会被读成「没有任何一条过」
      ;; 或「全都过了」，两种读法都危险。
      (is (= #{:structural-ok? :findings :carried-only-keys
               :row-security-declared :row-security-verified}
             (set (keys (:health m))))))))

(deftest structural-ok-is-derived-from-the-findings
  (is (false? (get-in (edn/read-string
                       (:manifest-edn (seal :health {:findings [{:severity :error}]})))
                      [:health :structural-ok?])))
  (is (true? (get-in (edn/read-string
                      (:manifest-edn (seal :health {:findings [{:severity :warning}]})))
                     [:health :structural-ok?]))))

;; --- 稳定排序（D4.3）--------------------------------------------------------

(deftest unordered-lists-are-sorted-before-they-reach-the-digest
  (testing "manifest/report 中语义无序的清单必须先按稳定对象键排序，
            不能把 map 遍历或体检发现顺序带入 digest（D4.3）"
    (let [keys* [:z/z :a/a :m/m :b/b]
          a (seal :health {:carried-only-keys keys*}
                  :diff {:added-attributes keys* :modified-objects ["z" "a"]})
          b (seal :health {:carried-only-keys (vec (reverse keys*))}
                  :diff {:added-attributes (vec (shuffle keys*))
                         :modified-objects ["a" "z"]})]
      (is (= (:files a) (:files b)))
      (is (= (:artifact-digest a) (:artifact-digest b))))))

(deftest health-findings-are-sorted-deterministically
  (let [fs [{:code :b/x :severity :warning :path [:b] :message "b"}
            {:code :a/x :severity :warning :path [:a] :message "a"}
            {:code :c/x :severity :warning :path [:a] :message "c"}]
        a (seal :health {:findings fs})
        b (seal :health {:findings (vec (reverse fs))})]
    (is (= (:manifest-edn a) (:manifest-edn b)))
    (is (= [[:a] [:a] [:b]]
           (mapv :path (get-in (edn/read-string (:manifest-edn a)) [:health :findings]))))))

(deftest duplicate-entries-in-a-list-do-not-move-the-digest
  (testing "同一个键出现两次不该让 digest 变——它是集合语义"
    (is (= (:artifact-digest (seal :health {:carried-only-keys [:a/b]}))
           (:artifact-digest (seal :health {:carried-only-keys [:a/b :a/b :a/b]}))))))

;; --- 结构化拒绝 --------------------------------------------------------------

(deftest seal-rejects-an-incomplete-provenance
  (doseq [k [:workspace :graph :version :cut-at :cut-by]]
    (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
          r (artifact/seal-graph content-edn content-hash (dissoc prov k) {} {} {:coverage 1.0})]
      (is (nil? (:artifact-digest r)) (str "缺 " k " 不得产出制品"))
      (is (contains? (codes (:findings r)) :artifact.envelope/missing-key)))))

(deftest seal-rejects-unknown-provenance-and-health-keys
  (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])]
    (is (contains? (codes (:findings (artifact/seal-graph
                                      content-edn content-hash
                                      (assoc prov :hostname "laptop") {} {} {:coverage 1.0})))
                   :artifact.envelope/unknown-key)
        "本机事实不进制品——凡是因机器而异的都不进（D4.5）")
    (is (contains? (codes (:findings (artifact/seal-graph
                                      content-edn content-hash prov
                                      {:executable? true} {} {:coverage 1.0})))
                   :artifact.envelope/unknown-key)
        "`:executable?` 反映的是这台机器装了哪些插件（D4.5）")))

(deftest seal-rejects-a-malformed-content-hash
  (let [{:keys [content-edn]} (artifact/encode-content model [])]
    (is (contains? (codes (:findings (artifact/seal-graph content-edn "deadbeef"
                                                          prov {} {} {:coverage 1.0})))
                   :artifact.integrity/content-hash-mismatch))))

(deftest seal-turns-an-unwritable-context-into-a-finding-not-a-stack-trace
  (testing "调用方合成的发布上下文里塞进一个不在规范形值域内的值 → 结构化拒绝"
    (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
          r (artifact/seal-graph content-edn content-hash prov {} {}
                                 {:when (java.util.Date.)})]
      (is (nil? (:artifact-digest r)))
      (is (contains? (codes (:findings r)) :artifact.envelope/invalid-shape)))))

;; --- 三份文件与 digest 同源 ---------------------------------------------------

(deftest the-files-map-is-what-the-digest-was-computed-over
  (testing "写下去的与算过 digest 的是同一份文本，不给两者分叉的机会"
    (let [s (seal)]
      (is (= (set artifact/artifact-files) (set (keys (:files s)))))
      (is (= (:manifest-edn s) (get (:files s) "manifest.edn")))
      (is (= (:report-edn s) (get (:files s) "report.edn")))
      (is (= (:artifact-digest s) (artifact/artifact-digest (:files s)))))))

;; --- :structural-ok? 只有一个权威位置（D4.9）---------------------------------

(deftest structural-ok-is-never-an-input
  (testing "调用方传 `:structural-ok?` → 结构化拒绝，点名它不是入参。

            **不静默忽略**：静默忽略会让调用方以为自己的设置生效了，而它其实
            被丢掉——那是另一种形式的静默降级。"
    (doseq [v [true false]]
      (let [r (seal :health {:structural-ok? v})]
        (is (nil? (:artifact-digest r)) (str ":structural-ok? " v " 不得产出制品"))
        (is (= #{:artifact.envelope/derived-key-supplied} (codes (:findings r))))
        (is (= [:health :structural-ok?] (:path (first (:findings r))))
            "点名的是哪一个键")))))

(deftest a-supplied-structural-ok-produces-exactly-one-finding
  (testing "同一个事实不报两遍：它不该同时挨一条 unknown-key 与一条
            derived-key-supplied，否则读的人得自己判断哪条才是真正的毛病"
    (is (= 1 (count (:findings (seal :health {:structural-ok? true})))))))

(deftest the-contradiction-that-used-to-be-sealable-is-now-impossible
  (testing "曾经能密封出「`:structural-ok? true` 却带着 error findings」的
            manifest——那正是 D4.9 禁止的第二个权威位置，而且是现役实例"
    (let [with-errors {:findings [{:code :x/y :severity :error :path [] :message "boom"}]}]
      (is (seq (:findings (seal :health (assoc with-errors :structural-ok? true))))
          "想同时声明 true 与 error findings：拒绝")
      (is (false? (get-in (edn/read-string (:manifest-edn (seal :health with-errors)))
                          [:health :structural-ok?]))
          "不声明时结论只能从 findings 来，且只能是 false"))))

;; --- :quality 的形状（dialect-v1 §3 的既有先例）------------------------------

(deftest quality-is-a-non-empty-keyword-keyed-map
  (testing "键面未定形的块收「非空 `{keyword → any}`」——与 dialect-v1 §3
            carried 汇总里 `:row-security`（键面归 D5.6）与 `:resilience`
            （键面归 D6.2）同一个处理"
    (testing "值随意，B5 的真实度量不会被挡住"
      (doseq [q [{:coverage 0.5}
                 {:tier-distribution {:public 9 :restricted 2}}
                 {:a 1 :b "two" :c [:d] :e {:f #{:g}}}]]
        (is (empty? (:findings (seal :quality q))) (pr-str q))))
    (testing "空 map 被拒：与「没算」不可区分，而它是部署前 owner 要看的东西"
      (is (contains? (codes (:findings (seal :quality {})))
                     :artifact.envelope/invalid-shape)))
    (testing "非 keyword 键被拒"
      (doseq [q [{"coverage" 1} {1 :a} {:ok 1 "bad" 2}]]
        (is (contains? (codes (:findings (seal :quality q)))
                       :artifact.envelope/invalid-shape)
            (pr-str q))))
    (testing "非 map 被拒"
      (doseq [q [nil [] "x" 1]]
        (is (contains? (codes (:findings (seal :quality q)))
                       :artifact.envelope/invalid-shape)
            (pr-str q))))))

;; --- health findings 的排序键（finding-v1 §6）--------------------------------

(deftest health-findings-sort-exactly-as-finding-v1-says
  (testing "按 `:path` 字典序、同 path 按 `:code`——与 finding-v1 §6 **逐字一致**。
            这里曾经多带一段 `:message`；那是收紧不是冲突，但两处文字不一致
            本身就是将来分叉的种子，要收紧就去改 finding-v1。"
    (let [fs [{:code :b/x :severity :warning :path [:b] :message "m"}
              {:code :a/x :severity :warning :path [:a] :message "m"}
              {:code :c/x :severity :warning :path [:a] :message "m"}]
          sorted (get-in (edn/read-string (:manifest-edn (seal :health {:findings fs})))
                         [:health :findings])]
      (is (= [[[:a] :a/x] [[:a] :c/x] [[:b] :b/x]]
             (mapv (juxt :path :code) sorted))))))

(deftest same-path-same-code-findings-keep-caller-order
  (testing "代价明说：排序键与 finding-v1 对齐后，同 path 同 code 的并列项由
            `sort-by` 的稳定性保持**入参顺序**——这一段的确定性因此归调用方
            （B5 的体检需产出稳定顺序）。本用例钉住这个事实，免得它日后被
            当成一个意外发现。"
    (let [mk (fn [msg] {:code :a/x :severity :warning :path [:a] :message msg})
          msgs (fn [h] (mapv :message (get-in (edn/read-string (:manifest-edn h))
                                              [:health :findings])))]
      (is (= ["first" "second"] (msgs (seal :health {:findings [(mk "first") (mk "second")]}))))
      (is (= ["second" "first"] (msgs (seal :health {:findings [(mk "second") (mk "first")]}))))
      (is (not= (:artifact-digest (seal :health {:findings [(mk "first") (mk "second")]}))
                (:artifact-digest (seal :health {:findings [(mk "second") (mk "first")]})))
          "两种入参顺序确实产出两个 digest——这是上面那句话的代价，不是 bug"))))

;; --- `:object-revisions`（E3，§4.1） -----------------------------------------
;;
;; 指针进 provenance，日志不进（D4.5，owner 2026-09-03）。这一组守的是它
;; **不改内容身份**，以及老制品不因它而装不起来。

(deftest the-same-content-cut-from-different-revisions-is-the-same-content
  "artifact-v1 §5「发布上下文不改内容身份」：同一份 content 带两套
   `:object-revisions` 密封，content-hash 必须相同，artifact-digest 必须不同
   ——前者是「这是什么」，后者是「这三份文件有没有被换过」（D4.3）。"
  (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
        seal* (fn [revs] (artifact/seal-graph content-edn content-hash
                                              (assoc prov :object-revisions revs)
                                              {} {} {:coverage 1.0}))
        a (seal* {[:resolver "r"] 7})
        b (seal* {[:resolver "r"] 8})]
    (is (empty? (:findings a)) (pr-str (:findings a)))
    (is (empty? (:findings b)) (pr-str (:findings b)))
    (is (= content-hash
           (:content-hash (edn/read-string (get (:files a) "manifest.edn")))
           (:content-hash (edn/read-string (get (:files b) "manifest.edn")))))
    (is (= (get (:files a) "content.edn") (get (:files b) "content.edn"))
        "content.edn 逐字节相同——revision 一个字节都没进去")
    (is (not= (:artifact-digest a) (:artifact-digest b))
        "但 manifest 变了，digest 覆盖三份文件")))

(deftest an-artifact-without-the-key-seals-and-reads-as-before
  (let [r (seal)]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (not (contains? (:provenance (edn/read-string (get (:files r) "manifest.edn")))
                        :object-revisions))
        "缺省不书写：老制品一个都没有它，把它写成空 map 会是第二种「没有」")))

(deftest object-revisions-refuses-a-shape-it-cannot-be-read-back-from
  (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
        seal* (fn [revs] (artifact/seal-graph content-edn content-hash
                                              (assoc prov :object-revisions revs)
                                              {} {} {:coverage 1.0}))]
    (testing "不是 map"
      (is (contains? (codes (:findings (seal* [[:resolver "r"] 7])))
                     :artifact.envelope/invalid-shape)))
    (testing "键不是 [类型 id]"
      (is (contains? (codes (:findings (seal* {"resolver/r" 7})))
                     :artifact.envelope/invalid-shape)))
    (testing "值不是正整数——0 在池里意思是「不存在过」"
      (doseq [v [0 -1 "8" 8.0]]
        (is (contains? (codes (:findings (seal* {[:resolver "r"] v})))
                       :artifact.envelope/invalid-shape)
            (str "revision " (pr-str v) " 必须被拒"))))
    (testing "拒绝就是拒绝：一个字节的制品都没有"
      (is (nil? (:artifact-digest (seal* {[:resolver "r"] 0})))))))

(deftest a-links-pair-id-is-a-legal-key
  ;; link 的 id 本身就是一对属性键（D2.3）。若这里只认字符串与 keyword，
  ;; 一张带 link 的图会在切版本时被自己的 provenance 拒掉。
  (let [{:keys [content-edn content-hash]} (artifact/encode-content model [])
        r (artifact/seal-graph content-edn content-hash
                               (assoc prov :object-revisions
                                      {[:link [:a/x :b/y]] 2
                                       [:attribute :a/x] 1
                                       [:namespace "a"] 1})
                               {} {} {:coverage 1.0})]
    (is (empty? (:findings r)) (pr-str (:findings r)))))
