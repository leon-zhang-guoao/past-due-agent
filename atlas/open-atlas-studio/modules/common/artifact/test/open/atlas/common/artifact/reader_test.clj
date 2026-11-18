(ns open.atlas.common.artifact.reader-test
  "artifact reader（D4.6）与三种身份的比对（D4.3/D4.4/D4.11）。

   本 ns 全部无 IO——装载语义与文件系统的脾气各自可测（后者见 store-test）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]))

(def ^:private b1-model
  (:model (dialect/read-dialect (slurp (io/resource "graph.edn")))))

(defn- sealed
  ([] (sealed b1-model []))
  ([model refs]
   (let [{:keys [content-edn content-hash findings]} (artifact/encode-content model refs)]
     (assert (empty? findings) (pr-str findings))
     (artifact/seal-graph content-edn content-hash
                          {:workspace "core-team" :graph "b1" :version 1
                           :cut-at "2026-08-22T00:00:00Z" :cut-by "test"}
                          {} {} {:attribute-count 12}))))

(defn- codes [findings] (set (map :code findings)))

(defn- load-with
  "按用例需要拼装校验声明后装载。

   reader 不再接受沉默跳过：artifact-digest 与引用闭包各自要么给出验的依据，
   要么显式声明「我知道我没在验」（D4.3/D4.11）。这些用例大多在验别的东西，
   所以没点名的那一样一律显式声明为不验——**测试自己也不许静默降级**。"
  ([texts] (load-with texts {}))
  ([texts extra]
   (artifact/read-artifact
    texts
    (cond-> extra
      (not (contains? extra :expected-digest))   (assoc :digest :unverified)
      (not (contains? extra :resolve-reference)) (assoc :closure :unverified)))))

;; --- 装载成功路径 ------------------------------------------------------------

(deftest a-sealed-artifact-loads-back-into-the-same-model
  (testing "encode → seal → 装载，交回的模型与输入的规范化模型逐字相同"
    (let [r (load-with (:files (sealed)))]
      (is (empty? (filter #(= :error (:severity %)) (:findings r))))
      (is (= b1-model (:model r)))
      (is (= (:content-hash (artifact/encode-content b1-model [])) (:content-hash r))))))

(deftest the-model-comes-from-the-one-dialect-reader
  (testing "artifact reader 不解析模型内容：`:objects` 交给同一个方言 reader，
            因此制品与手写方言文件不可能被解释成两个模型（D4.6/D1.3）"
    (let [from-artifact (:model (load-with (:files (sealed))))
          from-file     (:model (dialect/read-dialect (slurp (io/resource "graph.edn"))))]
      (is (= from-file from-artifact)))))

(deftest loading-reports-the-digest-it-computed
  (let [s (sealed)]
    (is (= (:artifact-digest s) (:artifact-digest (load-with (:files s)))))))

;; --- 部分写入（D4.8）---------------------------------------------------------

(deftest a-partially-written-version-must-not-load
  (testing "三份文件缺一即拒绝——D4.3 的整体完整性覆盖的就是这一组，
            缺一份时它根本无从计算"
    (doseq [missing artifact/artifact-files]
      (let [r (load-with (dissoc (:files (sealed)) missing))]
        (is (nil? (:model r)) (str "缺 " missing " 时不得交出模型"))
        (is (contains? (codes (:findings r)) :artifact.integrity/missing-file))
        (is (= [missing] (:files (first (:findings r)))) "拒绝要点名是哪个文件")))))

(deftest a-content-only-directory-is-the-canonical-partial-write
  (testing "只写下了 content.edn 的那一刻——原子写要防的正是它"
    (let [r (load-with (select-keys (:files (sealed)) ["content.edn"]))]
      (is (nil? (:model r)))
      (is (= #{"manifest.edn" "report.edn"} (set (mapcat :files (:findings r))))))))

;; --- content-hash 比对（D4.3）------------------------------------------------

(deftest content-hash-mismatch-refuses-the-load
  (testing "装载方先读 content.edn 重算 content-hash，再与 manifest 记的值比对"
    (let [s (sealed)
          ;; 手改 content.edn：加一个空格就够——它进 hash
          bad (update (:files s) "content.edn" #(str % "\n"))
          r   (load-with bad)]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/content-hash-mismatch))
      (testing "manifest 与 report 的 content-hash 回显都参与判定，因此三份文件
                都被点名——改一份 content.edn 会同时打破两处自述"
        (is (= #{"content.edn" "manifest.edn" "report.edn"}
               (set (mapcat :files (:findings r)))))
        (is (= #{"manifest.edn" "report.edn"}
               (set (for [f (:findings r)
                          :when (= :artifact.integrity/content-hash-mismatch (:code f))
                          file  (:files f)
                          :when (not= file "content.edn")]
                      file)))
            "两处回显各报各的，不合并成一条含糊的拒绝")))))

(deftest manifest-does-not-self-reference
  (testing "manifest.edn 里记着 content-hash，因此它自己不可能进 content-hash（D4.3）"
    (let [s (sealed)
          m (edn/read-string (:manifest-edn s))]
      (is (= (:content-hash m)
             (artifact/content-hash-of-text (get (:files s) "content.edn"))))
      (is (not (clojure.string/includes? (get (:files s) "content.edn")
                                         (:content-hash m)))
          "content.edn 里不得出现它自己的 hash"))))

(deftest publication-context-does-not-change-the-content-hash
  (testing "切版本时间、操作者、差异报告与质量指标都不进内容身份（D4.3）：
            同一份对象在周二切和周四切内容一模一样"
    (let [{:keys [content-edn content-hash]} (artifact/encode-content b1-model [])
          seal-with (fn [when* who]
                      (artifact/seal-graph
                       content-edn content-hash
                       {:workspace "core-team" :graph "b1" :version 1
                        :cut-at when* :cut-by who}
                       {:findings [] :carried-only-keys [:a/b]}
                       {:previous-version 7 :added-attributes [:a/c]}
                       {:coverage 0.5}))
          tue (seal-with "2026-08-18T09:00:00Z" "alice")
          thu (seal-with "2026-08-20T17:30:00Z" "bob")]
      (is (= (get (:files tue) "content.edn") (get (:files thu) "content.edn")))
      (is (= content-hash
             (artifact/content-hash-of-text (get (:files tue) "content.edn"))
             (artifact/content-hash-of-text (get (:files thu) "content.edn"))))
      (testing "但整体完整性必须区分它们——否则发布上下文可被无声替换"
        (is (not= (:artifact-digest tue) (:artifact-digest thu)))))))

;; --- artifact-digest（D4.3/D4.11）--------------------------------------------

(deftest expected-digest-mismatch-refuses-the-load
  (testing "server 在暂存、装载与激活前都必须重算比对（D4.11）"
    (let [s (sealed)
          r (load-with (:files s)
                                    {:expected-digest (artifact/content-hash-of-text "other")})]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/artifact-digest-mismatch)))))

(deftest digest-mismatch-names-the-per-file-hashes
  (testing "digest 不符时要能点名是哪一份变了（D4.6）"
    (let [s (sealed)
          bad (update (:files s) "report.edn" #(str % "; tampered\n"))
          f (first (filter #(= :artifact.integrity/artifact-digest-mismatch (:code %))
                           (:findings (load-with
                                       bad {:expected-digest (:artifact-digest s)}))))]
      (is (some? f))
      (is (= (set artifact/artifact-files) (set (keys (:got f)))))
      (is (= (get (:got f) "content.edn")
             (subs (artifact/content-hash-of-text (get (:files s) "content.edn")) 7))
          "没变的文件的 file-hash 仍然对得上——差异因此可定位")
      (is (not= (get (:got f) "report.edn")
                (subs (artifact/content-hash-of-text (get (:files s) "report.edn")) 7))))))

(deftest the-digest-is-never-written-back-into-the-artifact
  (testing "digest 不写回三份文件（D4.3）——否则自引用；也让「自报值」这条路
            根本不存在，接收端只能从已认证控制信封取期望值（D4.11）"
    (let [s (sealed)
          digest (:artifact-digest s)]
      (doseq [[fname text] (:files s)]
        (is (not (clojure.string/includes? text digest))
            (str fname " 里出现了 artifact-digest"))))))

(deftest a-tampered-manifest-cannot-pass-as-the-sealed-one
  (testing "manifest/report 不进 content-hash，但不得被无声替换"
    (let [s (sealed)
          bad (assoc (:files s) "manifest.edn"
                     (artifact/canonical-str
                      (assoc-in (edn/read-string (:manifest-edn s))
                                [:provenance :cut-by] "mallory")))
          r (load-with bad {:expected-digest (:artifact-digest s)})]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/artifact-digest-mismatch)))))

;; --- 引用闭包（D4.1/D4.4）----------------------------------------------------

(def ^:private upstream
  (artifact/encode-content {:namespaces {"core.upstream" {:kind :master :label "Upstream"}}}
                           []))

(def ^:private ref-entry
  {:workspace "core-team" :graph "core" :version 3
   :content-hash (:content-hash upstream)})

(defn- resolver-for [text]
  (fn [r] (when (= ((juxt :workspace :graph :version) r) ["core-team" "core" 3]) text)))

(deftest a-reference-whose-hash-matches-loads
  (let [s (sealed b1-model [ref-entry])
        r (load-with (:files s)
                                  {:resolve-reference (resolver-for (:content-edn upstream))})]
    (is (= b1-model (:model r)))
    (is (= [ref-entry] (:references r)))))

(deftest a-reference-whose-hash-does-not-match-refuses-the-load
  (testing "`:content-hash` 让装载方能验证拿到的确实是当初解析的那一份——
            **对不上即拒绝装载，不做「大概是同一个」的推测**（D4.4）"
    (let [s (sealed b1-model [ref-entry])
          other (:content-edn (artifact/encode-content
                               {:namespaces {"core.upstream" {:kind :master
                                                              :label "Changed"}}} []))
          r (load-with (:files s) {:resolve-reference (resolver-for other)})]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.reference/content-hash-mismatch))
      (is (some #(re-find #"core-team/core@3" (:message %)) (:findings r))
          "拒绝要点名是哪个引用"))))

(deftest a-missing-closure-member-refuses-the-load
  (testing "部分送达的闭包若能被激活，server 会用一张缺了引用的图去应答查询（D4.11）"
    (let [s (sealed b1-model [ref-entry])
          r (load-with (:files s) {:resolve-reference (constantly nil)})]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.reference/missing)))))

(deftest without-a-resolver-only-the-shape-is-checked
  (testing "离线校验一份孤立制品是合法用法：不解析闭包时只验形状"
    (let [s (sealed b1-model [ref-entry])
          r (load-with (:files s))]
      (is (= b1-model (:model r))))))

;; --- 模型内容的拒绝 ----------------------------------------------------------

(deftest a-model-the-dialect-rejects-refuses-the-load
  (testing "`:objects` 过不了方言 reader → 不产带病模型，方言的 findings 随行"
    (let [text (artifact/canonical-str
                {:artifact/format-version 1
                 :objects {:attributes {:bad/attr {:type :nonesuch-type}}}
                 :references []})
          h (artifact/content-hash-of-text text)
          s (artifact/seal-graph text h {:workspace "w" :graph "g" :version 1
                                         :cut-at "t" :cut-by "t"} {} {} {:coverage 1.0})
          r (load-with (:files s))]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.model/rejected))
      (is (seq (:got (first (filter #(= :artifact.model/rejected (:code %))
                                    (:findings r)))))
          "方言的 findings 要随行，否则作者看不到差在哪"))))

;; --- finding 契约 ------------------------------------------------------------

(deftest every-finding-this-module-produces-is-well-formed-and-registered
  (testing "finding 自身也被校验，且全部 code 登记在本模块注册表（finding-v1 §6）"
    (let [s (sealed)
          samples (concat
                   (:findings (load-with (dissoc (:files s) "report.edn")))
                   (:findings (artifact/read-artifact
                               (update (:files s) "content.edn" str "\n")))
                   (:findings (artifact/read-artifact
                               (:files s) {:expected-digest (artifact/content-hash-of-text "x")}))
                   (:findings (artifact/encode-content {} [{:workspace "w"}]))
                   (:findings (artifact/encode-content {} [] 2))
                   (:findings (load-with {"content.edn" "{"
                                                       "manifest.edn" "{}"
                                                       "report.edn" "{}"})))]
      (is (seq samples))
      (doseq [f samples]
        (is (artifact/finding? f) (str "不合法的 finding: " (pr-str f)))
        (is (contains? artifact/finding-codes (:code f))
            (str "未注册的 code: " (:code f)))
        (is (= "artifact" (first (clojure.string/split (namespace (:code f)) #"\.")))
            (str "code 必须在 artifact.* 域: " (:code f)))))))

(deftest findings-are-deterministically-ordered
  (testing "同一输入两次校验产出的 findings 逐字节相同且顺序稳定（finding-v1 §6）"
    (let [bad {"content.edn" (artifact/canonical-str
                              {:artifact/format-version 1 :objects {}
                               :references [{:workspace "w"} {:graph "g"}]})
               "manifest.edn" "{}" "report.edn" "{}"}]
      (is (= (:findings (load-with bad))
             (:findings (load-with bad)))))))

;; --- report.edn 的回显真的参与判定（D4.7 的对称面）---------------------------

(defn- with-report [f]
  (let [s (sealed)]
    (assoc (:files s) "report.edn"
           (artifact/canonical-str (f (edn/read-string (:report-edn s)))))))

(deftest a-report-that-reports-on-another-content-hash-refuses-the-load
  (testing "三份文件是被**一起**密封的一组：report 的自述与另外两份对不上，
            说明制品被人拆开改过。

            这两个回显曾经在场却不参与任何判定——那比没有更糟：它**看起来像
            门禁**，读的人会以为有人在把关，而实际改动它只会改 artifact-digest。"
    (let [r (load-with (with-report #(assoc % :content-hash
                                            (artifact/content-hash-of-text "something else"))))]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/content-hash-mismatch))
      (is (= #{"content.edn" "report.edn"}
             (set (mapcat :files (:findings r))))
          "点名的是对不上的那两份，不含无辜的 manifest"))))

(deftest a-report-missing-its-echoes-refuses-the-load
  (doseq [k [:content-hash :artifact/format-version]]
    (let [r (load-with (with-report #(dissoc % k)))]
      (is (nil? (:model r)) (str "report 缺 " k " 时不得装载"))
      (is (contains? (codes (:findings r)) :artifact.envelope/missing-key))
      (is (= ["report.edn"] (:files (first (:findings r))))))))

(deftest a-report-echoing-another-format-version-refuses-the-load
  (testing "非 1 → 结构化拒绝；是 1 但与 content.edn 不一致 → 拒绝"
    (let [r (load-with (with-report #(assoc % :artifact/format-version 2)))]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.compat/unsupported-format-version)))))

(deftest the-report-echo-gate-catches-a-swapped-report-from-another-version
  (testing "把另一个版本的 report.edn 搬过来——digest 不验时，这道门是唯一
            拦得住它的东西"
    (let [other (sealed {:namespaces {"other" {:kind :master :label "Other"}}} [])
          mine  (sealed)
          bad   (assoc (:files mine) "report.edn" (:report-edn other))
          r     (load-with bad)]
      (is (nil? (:model r)))
      (is (contains? (codes (:findings r)) :artifact.integrity/content-hash-mismatch)))))

(deftest a-well-formed-report-still-does-not-drive-the-model
  (testing "回显参与**完整性**判定，但 report 的其余内容仍然不参与任何装载
            结果——`:diff` 与 `:quality` 改了不影响模型，只改 artifact-digest"
    (let [s (sealed)
          bumped (assoc (:files s) "report.edn"
                        (artifact/canonical-str
                         (assoc-in (edn/read-string (:report-edn s))
                                   [:quality :coverage] 0.123)))
          r (load-with bumped)]
      (is (= b1-model (:model r)) "质量指标不改变模型")
      (is (not= (:artifact-digest s) (artifact/artifact-digest bumped))
          "但它确实改变了整体完整性凭据"))))
