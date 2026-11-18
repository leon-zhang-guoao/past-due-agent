(ns open.atlas.common.artifact.conformance-test
  "**编码 conformance**（D4.10 第一层）在模型层的那一半。

   D4.10 的两层，**都必须等价**，两层都是测试套件里的一等公民：

   | 层 | 对照 | 守住什么 | 阶段 |
   |---|---|---|---|
   | **编码 conformance** | 同一规范化模型**直接编译**的 env ⟷ 经 `encode_content → content.edn → artifact reader` 后编译的 env | 序列化与反序列化不改变模型 | **B3（本层）** |
   | **真实边界 conformance** | 经 **Datahike 池投影**编译的 env ⟷ 经**正式切版本制品**装载的 env | studio 与 server 装的确实是同一张图 | B5 |

   第一层在 B3 落成两处，因为它有两个可以分别失败的接缝：

   - **模型层**（本 ns）：`模型 → encode → seal → 装载 → 模型` 是恒等。无 IO、
     无 engine，因此可以对**每一份** fixture 与黄金向量逐一施加。
   - **env 层**（`modules/integration/artifact_encoding_conformance_test.clj`）：
     两边编译出的 env 对同一批 EQL 给出逐字相同的结果。它需要 engine，而
     artifact 模块**不依赖 engine**（制品是模型内容契约，与执行无关），
     所以它住在装配模块。

   **为什么两处都要**：模型相等是 env 等价的必要条件，不是充分条件——编译器
   完全可能对一个 `=` 相等但内部共享结构不同的模型给出不同的 env。反过来，
   env 层用的是一份 fixture，覆盖不到模型层这里逐一施加的十来种形状。少任何
   一处，都会留下一段没被测试穿过的接缝（D1.5 的同一条理由）。

   **第二层不在 B3 的能力范围内**：它必须等池与切版本都在（B5）。这不是可以
   靠「上线前记得手工比一次」补上的东西——D4.10 已经明确否掉了那条路。B5 的
   `cut-version` 复用的是本模块的 `encode-content` 与 `seal-graph`（D4.10-a），
   因此第二层新增的对照面只有池投影那一侧。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]))

(def ^:private fragment-fixtures
  "方言自己的规范形黄金 fixture。它们是**片段**（单个 resolver / 单个 namespace
   块），不是方言根 map——dialect 的 write-test 也是这样用它们的。片段用来测
   writer 的等价性，整模型用来测 encode → 装载的恒等性。"
  ["resolver-single" "resolver-batch" "resolver-curated" "namespace-attributes"])

(defn- read-model [resource-name]
  (let [{:keys [model findings]} (dialect/read-dialect (slurp (io/resource resource-name)))]
    (assert model (str resource-name " must read clean: " (pr-str findings)))
    model))

(def ^:private models
  "取样面：B1 的真 fixture，加上黄金向量里的 `:objects`。两处来源各自独立
   演进，同时退化的可能性远低于单一来源。"
  (into {"fixtures/b1/graph.edn" (read-model "graph.edn")}
        (for [n ["minimal" "seven-kinds" "edges" "one-reference" "upstream-core"]]
          [(str "golden/" n)
           (:objects (edn/read-string
                      (slurp (io/resource (str "golden/" n "/content.edn"))
                             :encoding "UTF-8")))])))

(defn- round-trip
  "模型 → encode_content → seal_graph → artifact reader → 模型。
   **走的是公共接口的全程**，不是内部捷径：B3 与 B5 共用这条路径（D4.10-a）。"
  [model]
  (let [{:keys [content-edn content-hash findings]} (artifact/encode-content model [])
        _ (assert (empty? findings) (pr-str findings))
        sealed (artifact/seal-graph content-edn content-hash
                                    {:workspace "w" :graph "g" :version 1
                                     :cut-at "2026-08-22T00:00:00Z" :cut-by "conformance"}
                                    {} {} {:coverage 1.0})
        _ (assert (empty? (:findings sealed)) (pr-str (:findings sealed)))]
    (artifact/read-artifact (:files sealed) {:expected-digest (:artifact-digest sealed)
                                            :closure :unverified})))

(deftest encoding-round-trip-is-the-identity-on-the-model
  (testing "序列化与反序列化不改变模型（D4.10 编码 conformance，模型层）"
    (is (seq models))
    (doseq [[name* model] models]
      (let [r (round-trip model)]
        (is (empty? (filter #(= :error (:severity %)) (:findings r)))
            (str name* " 装载失败：" (pr-str (:findings r))))
        (is (= model (:model r)) (str name* " 经 encode → seal → 装载后模型变了"))))))

(deftest a-second-round-trip-is-byte-for-byte-stable
  (testing "装载回来的模型再编码一次，`content.edn` 与 content-hash 逐字节不变
            ——规范形是**不动点**，不是「第一次归一，第二次又变一点」"
    (doseq [[name* model] models]
      (let [first-pass  (artifact/encode-content model [])
            reloaded    (:model (round-trip model))
            second-pass (artifact/encode-content reloaded [])]
        (is (= (:content-edn first-pass) (:content-edn second-pass)) name*)
        (is (= (:content-hash first-pass) (:content-hash second-pass)) name*)))))

(deftest the-embedded-objects-are-byte-identical-to-the-hand-written-dialect-file
  (testing "`content.edn` 里 `:objects` 那一段与手写方言文件的规范形同源
            ——制品与手写文件因此不可能被解释成两个模型（D4.6/D1.3）"
    (doseq [[name* model] models]
      (is (= (dialect/write-dialect model) (artifact/canonical-str model))
          (str name* "：制品的 writer 与方言的 writer 输出不同")))))

(deftest a-non-canonical-input-writes-to-the-canonical-bytes
  ;; 打乱键序、用 `#:ns{}` 缩写书写的等价输入，经制品的规范 writer 后与冻结的
  ;; 规范形 fixture **逐字节相同**，因此 hash 也相同（D2.12/D4.3 表里的
  ;; 「两个语义相同、书写顺序不同的输入」那一条）。用的是 dialect 自己的黄金
  ;; fixture：制品的身份规则与 dialect-v1 §4 同源，同源就该拿同一组向量验，
  ;; 而不是各自造一组。
  (testing "书写顺序与 #:ns{} 缩写都不进入身份"
    (doseq [n fragment-fixtures
            :let [canon   (slurp (io/resource (str "canonical/" n ".edn")) :encoding "UTF-8")
                  variant (slurp (io/resource (str "variant/" n "/shuffled-ns-abbrev.edn"))
                                 :encoding "UTF-8")]]
      (is (not= canon variant) (str n "：变体与规范形的字节本该不同"))
      (is (= canon (artifact/canonical-str (edn/read-string variant))) n)
      (is (= (artifact/content-hash-of-text canon)
             (artifact/content-hash-of-text (artifact/canonical-str
                                             (edn/read-string variant))))
          n))))

(deftest references-survive-the-round-trip-verbatim
  (testing "引用清单也要原样回来——它进 content-hash，装载方要靠它验闭包"
    (let [refs [{:workspace "core-team" :graph "core" :version 3
                 :content-hash (:content-hash (artifact/encode-content {} []))}
                {:workspace "a-team" :graph "a" :version 1
                 :content-hash (:content-hash (artifact/encode-content {} []))}]
          {:keys [content-edn content-hash]} (artifact/encode-content
                                              (get models "fixtures/b1/graph.edn") refs)
          sealed (artifact/seal-graph content-edn content-hash
                                      {:workspace "w" :graph "g" :version 1
                                       :cut-at "t" :cut-by "t"} {} {} {:coverage 1.0})
          r (artifact/read-artifact (:files sealed) {:digest  :unverified
                                                     :closure :unverified})]
      (is (= (vec (sort-by (juxt :workspace :graph :version) refs)) (:references r))))))
