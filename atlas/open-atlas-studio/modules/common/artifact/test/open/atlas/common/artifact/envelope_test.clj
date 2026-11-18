(ns open.atlas.common.artifact.envelope-test
  "`content.edn` v1 信封（D4.2）、格式版本协商（D4.7）与引用清单（D4.4）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]))

(def ^:private a-hash
  (:content-hash (artifact/encode-content {} [])))

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

(defn- reject-of
  "只用公开接口做一次装载并取回 findings：把一份手改过的 `content.edn`
   与一份合法的 manifest/report 配起来。sealer 只在 content-hash 上把关，
   所以这里先密封一份合法的，再替换 content.edn 与 manifest 里的 hash。"
  [content-map]
  (let [text (artifact/canonical-str content-map)
        h    (artifact/content-hash-of-text text)
        base (artifact/seal-graph text h
                                  {:workspace "w" :graph "g" :version 1
                                   :cut-at "2026-01-01T00:00:00Z" :cut-by "t"}
                                  {} {} {:coverage 1.0})]
    (:findings (load-with (:files base)))))

;; --- 顶层封闭 ---------------------------------------------------------------

(deftest top-level-is-closed-unknown-keys-are-rejected
  (testing "未知键一律拒绝，不静默忽略（D4.6）：读不懂的键要么版本不兼容，
            要么制品被改过，两种都不该继续"
    (let [f (reject-of {:artifact/format-version 1 :objects {} :references []
                        :policy {:whatever true}})]
      (is (contains? (codes f) :artifact.envelope/unknown-key))
      (is (some #(re-find #":policy" (:message %)) f) "拒绝要点名是哪个键"))))

(deftest there-is-no-sibling-policy-slot
  (testing "没有平级的 `policy` 槽（D4.2）——不为未知的未来需求留空槽"
    (is (= #{:artifact/format-version :objects :references}
           (set (keys (edn/read-string
                       (:content-edn (artifact/encode-content {} [])))))))))

(deftest objects-and-references-are-both-required
  (doseq [missing [:objects :references]]
    (let [f (reject-of (dissoc {:artifact/format-version 1 :objects {} :references []}
                               missing))]
      (is (contains? (codes f) :artifact.envelope/missing-key)
          (str missing " 缺席必须被点名")))))

(deftest unparseable-content-is-a-structured-rejection-not-an-exception
  (testing "解析失败也走 finding，不抛异常——装载失败必须点名文件（D4.6）"
    (let [f (:findings (load-with
                        {"content.edn" "{:artifact/format-version 1 :objects"
                         "manifest.edn" "{}" "report.edn" "{}"}))]
      (is (contains? (codes f) :artifact.envelope/parse-error))
      (is (= ["content.edn"] (:files (first (filter #(= :artifact.envelope/parse-error
                                                       (:code %)) f))))))))

;; --- 格式版本（D4.7）--------------------------------------------------------

(deftest format-version-other-than-one-is-structurally-rejected
  (testing "v1 的装载方只支持版本 1，其余一律结构化拒绝并说明差在哪"
    (doseq [v [2 0 -1 99]]
      (let [f (reject-of {:artifact/format-version v :objects {} :references []})]
        (is (contains? (codes f) :artifact.compat/unsupported-format-version)
             (str "format-version " v " 必须被拒绝"))
        (let [finding (first (filter #(= :artifact.compat/unsupported-format-version
                                         (:code %)) f))]
          (is (= 1 (:expected finding)) "拒绝要说清期望的是哪个版本")
          (is (= v (:got finding)) "拒绝要说清读到的是哪个版本"))))))

(deftest non-integer-format-version-is-rejected-without-leaking-the-value
  (let [f (reject-of {:artifact/format-version "1" :objects {} :references []})
        finding (first (filter #(= :artifact.compat/unsupported-format-version (:code %)) f))]
    (is (some? finding))
    (is (= "string(1 chars)" (:got finding)) "非法值降为类型描述（finding-v1 §5）")))

(deftest encode-refuses-to-produce-a-non-v1-envelope
  (testing "encode 端同样拒绝——错误与产物互斥，不产带病制品（finding-v1 §2）"
    (let [r (artifact/encode-content {} [] 2)]
      (is (nil? (:content-edn r)))
      (is (nil? (:content-hash r)))
      (is (contains? (codes (:findings r)) :artifact.compat/unsupported-format-version)))))

(deftest manifest-echo-must-agree-with-content
  (testing "两处不一致即拒绝装载：那说明制品被人手改过（D4.7）"
    (let [text (artifact/canonical-str {:artifact/format-version 1 :objects {} :references []})
          h    (artifact/content-hash-of-text text)
          base (artifact/seal-graph text h {:workspace "w" :graph "g" :version 1
                                            :cut-at "t" :cut-by "t"} {} {} {:coverage 1.0})
          ;; 手改 manifest 的回显（并保持 manifest 仍是合法 EDN）
          bad  (assoc (:files base) "manifest.edn"
                      (artifact/canonical-str
                       (assoc (edn/read-string (:manifest-edn base))
                              :artifact/format-version 2)))
          f    (:findings (load-with bad))]
      ;; 回显本身就是不支持的版本，先在 manifest 上被点名；无论落到哪一条，
      ;; 都必须是 compat 域的拒绝且点名 manifest.edn。
      (is (seq (filter #(and (= "artifact.compat" (namespace (:code %)))
                             (some #{"manifest.edn"} (:files %)))
                       f))))))

(deftest manifest-must-echo-the-format-version
  (let [text (artifact/canonical-str {:artifact/format-version 1 :objects {} :references []})
        h    (artifact/content-hash-of-text text)
        base (artifact/seal-graph text h {:workspace "w" :graph "g" :version 1
                                          :cut-at "t" :cut-by "t"} {} {} {:coverage 1.0})
        bad  (assoc (:files base) "manifest.edn"
                    (artifact/canonical-str
                     (dissoc (edn/read-string (:manifest-edn base))
                             :artifact/format-version)))]
    (is (contains? (codes (:findings (load-with bad)))
                   :artifact.envelope/missing-key))))

;; --- 引用清单（D4.4）--------------------------------------------------------

(defn- enc-refs [refs] (artifact/encode-content {} refs))

(deftest a-reference-is-a-triple-with-a-hash
  (let [r (enc-refs [{:workspace "core-team" :graph "core" :version 3
                      :content-hash a-hash}])]
    (is (empty? (:findings r)))
    (is (= [{:workspace "core-team" :graph "core" :version 3
             :content-hash a-hash}]
           (:references (:envelope r))))))

(deftest reference-version-must-be-a-concrete-number
  (testing "R3.1 永不指 latest"
    (doseq [v [:latest "3" 0 -1 nil]]
      (is (contains? (codes (:findings (enc-refs [{:workspace "w" :graph "g" :version v
                                                   :content-hash a-hash}])))
                     :artifact.reference/invalid-shape)
          (str "version " (pr-str v) " 必须被拒绝")))))

(deftest reference-requires-both-halves-of-the-qualified-name
  (testing "限定名两段都写（D3.10）"
    (doseq [r [{:graph "g" :version 1 :content-hash a-hash}
               {:workspace "w" :version 1 :content-hash a-hash}
               {:workspace "" :graph "g" :version 1 :content-hash a-hash}]]
      (is (contains? (codes (:findings (enc-refs [r]))) :artifact.reference/invalid-shape)))))

(deftest reference-hash-must-be-lowercase-sha256
  (doseq [h [(clojure.string/upper-case a-hash)
             (subs a-hash 7)
             "sha256:deadbeef"
             "md5:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"]]
    (is (contains? (codes (:findings (enc-refs [{:workspace "w" :graph "g" :version 1
                                                 :content-hash h}])))
                   :artifact.reference/invalid-shape)
        (str "非法 hash 拼法必须被拒绝：" h))))


(deftest reference-keys-are-closed
  (is (contains? (codes (:findings (enc-refs [{:workspace "w" :graph "g" :version 1
                                               :content-hash a-hash :tier :public}])))
                 :artifact.reference/invalid-shape)))

(deftest duplicate-reference-is-rejected-not-overridden
  (testing "同一限定名与版本重复出现即拒绝，不能靠「后者覆盖前者」（D4.2）"
    (let [r {:workspace "core-team" :graph "core" :version 3 :content-hash a-hash}
          f (:findings (enc-refs [r (assoc r :content-hash a-hash)]))]
      (is (contains? (codes f) :artifact.reference/duplicate))
      (is (some #(re-find #"core-team/core@3" (:message %)) f)))))

(deftest two-versions-of-the-same-graph-are-not-a-duplicate
  (testing "裁量点：同一限定名的两个不同版本，D4.2 的字面读法不禁止；
            钻石冲突的裁定属于闭包解析，留在 B5（D4.10-a）"
    (let [r {:workspace "core-team" :graph "core" :content-hash a-hash}]
      (is (empty? (:findings (enc-refs [(assoc r :version 3) (assoc r :version 4)])))))))
