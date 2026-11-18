(ns open.atlas.common.artifact.golden-test
  "D4.3 的黄金向量与「hash 是内容的纯函数」那条测试义务。

   向量本体在 `fixtures/golden/`（见那里的 README）。本 ns 对每一份向量施加
   四件事：**复现**期望 hash、**任意一个字节变化 hash 必须不同**、
   **书写顺序不影响输出**、**线程状态不影响输出**。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]))

(def golden
  (edn/read-string (slurp (io/resource "golden.edn"))))

(defn- vector-text
  "黄金向量的**原始字节**，用 UTF-8 读回。不经任何归一——向量守的就是字节。"
  [name*]
  (slurp (io/resource (str "golden/" name* "/content.edn")) :encoding "UTF-8"))

(def vector-names (vec (sort (keys (:content-hash golden)))))

;; --- ① 复现 -----------------------------------------------------------------

(deftest content-hash-golden-vectors-reproduce
  (testing "每一份冻结的 content.edn 复算出期望的 content-hash（D4.3）"
    (is (seq vector-names) "向量索引不得为空——空索引让本 ns 全部空转")
    (doseq [n vector-names]
      (is (= (get-in golden [:content-hash n])
             (artifact/content-hash-of-text (vector-text n)))
          (str "content-hash 向量不复现：" n)))))

(deftest writer-edges-golden-text-reproduces
  (testing "writer 值域边界：同一个值写出逐字节相同的文本（浮点最短往返、
            整数无类型后缀、字符串转义、set 与 map 的字典序）"
    (let [value    (edn/read-string (slurp (io/resource "golden/writer-edges/value.edn")))
          expected (slurp (io/resource "golden/writer-edges/canonical.txt") :encoding "UTF-8")]
      (is (= expected (artifact/canonical-str value)))
      (is (= (:writer-edges golden) (artifact/content-hash-of-text expected))))))

(deftest artifact-digest-golden-vector-reproduces
  (testing "三文件 artifact-digest 向量复现（D4.3）"
    (let [texts (into {} (for [f artifact/artifact-files]
                           [f (slurp (io/resource (str "golden/b1-sealed/" f))
                                     :encoding "UTF-8")]))]
      (is (= (get-in golden [:b1-sealed :content-hash])
             (artifact/content-hash-of-text (get texts "content.edn"))))
      (is (= (get-in golden [:b1-sealed :artifact-digest])
             (artifact/artifact-digest texts))))))

(deftest digest-covers-all-three-files-not-just-content
  (testing "content.edn 不动、manifest 或 report 动一个字节 → content-hash 不变、
            artifact-digest 必须变。这正是两种身份分开的理由（D4.3）：
            发布上下文不进内容身份，但不得被无声替换。"
    (let [texts (into {} (for [f artifact/artifact-files]
                           [f (slurp (io/resource (str "golden/b1-sealed/" f))
                                     :encoding "UTF-8")]))
          base  (artifact/artifact-digest texts)]
      (doseq [f ["manifest.edn" "report.edn"]]
        (let [tampered (update texts f str " ")]
          (is (= (artifact/content-hash-of-text (get texts "content.edn"))
                 (artifact/content-hash-of-text (get tampered "content.edn")))
              (str f " 变了不该影响 content-hash"))
          (is (not= base (artifact/artifact-digest tampered))
              (str f " 变了必须让 artifact-digest 变")))))))

(deftest digest-is-order-sensitive-across-files
  (testing "三份文件的内容互换 → digest 必须变。
            文件顺序是算法的一部分：不带文件名与顺序的拼接会让 A/B 互换算出同一个值。"
    (let [texts (into {} (for [f artifact/artifact-files]
                           [f (slurp (io/resource (str "golden/b1-sealed/" f))
                                     :encoding "UTF-8")]))
          swapped (assoc texts
                         "manifest.edn" (get texts "report.edn")
                         "report.edn"   (get texts "manifest.edn"))]
      (is (not= (artifact/artifact-digest texts) (artifact/artifact-digest swapped))))))

;; --- ② 任意一个字节变化 hash 必须不同 ---------------------------------------

(defn- single-byte-mutations
  "在文本的若干个采样位置各改一个字符。全量逐字节遍历对大向量太慢而收益为零——
   采样覆盖首、尾与均匀分布的中间位置，足以暴露「有被忽略的位」。"
  [^String s]
  (let [n (count s)
        idxs (distinct (concat [0 (dec n)]
                               (map #(int (* (/ % 40.0) n)) (range 40))))]
    (for [i idxs
          :when (and (>= i 0) (< i n))
          :let [c (.charAt s i)
                ;; 换一个**不会改变 EDN 语义为同一份内容**的字符即可——
                ;; 这条测的是 hash 覆盖了全部字节，不是 EDN 的合法性。
                c' (if (= c \a) \b \a)]
          :when (not= c c')]
      (str (subs s 0 i) c' (subs s (inc i))))))

(deftest any-single-byte-change-changes-the-hash
  (testing "任意一个字节变化 → hash 必须不同；没有被忽略的位（D4.3）"
    (doseq [n vector-names
            :let [text (vector-text n)
                  base (artifact/content-hash-of-text text)]]
      (doseq [mutated (single-byte-mutations text)]
        (is (not= base (artifact/content-hash-of-text mutated))
            (str n "：改一个字节后 hash 未变"))))))

(deftest a-changed-reference-hash-changes-the-referrer
  (testing "引用参与 hash：被引用版本的 hash 变了，引用方的 hash 也变——
            这让「我依赖的东西被人动过」成为可检测事件（D4.3/D4.4）"
    (let [a (vector-text "one-reference")
          b (vector-text "one-reference-upstream-changed")]
      (is (not= a b))
      (is (not= (artifact/content-hash-of-text a) (artifact/content-hash-of-text b)))
      (testing "两份向量的差异确实只在引用的 :content-hash 上，不在 :objects"
        (let [ea (edn/read-string a) eb (edn/read-string b)]
          (is (= (:objects ea) (:objects eb)))
          (is (= (mapv #(dissoc % :content-hash) (:references ea))
                 (mapv #(dissoc % :content-hash) (:references eb)))))))))

;; --- ③ 书写顺序不进入身份 ---------------------------------------------------

(defn- shuffle-deep
  "把每张 map 重建为一个书写顺序不同的等价 map，把每个 set 重建为不同插入序。
   `array-map` 保留插入序，因此这确实产生一个「书写顺序不同」的内存值。"
  [v]
  (cond
    (map? v)    (into (array-map) (shuffle (mapv (fn [[k x]] [k (shuffle-deep x)]) v)))
    (set? v)    (into #{} (shuffle (mapv shuffle-deep v)))
    (vector? v) (mapv shuffle-deep v)          ; 向量保持书写顺序——它是有序的
    :else       v))

(deftest writing-order-does-not-change-the-text-or-the-hash
  (testing "两个语义相同、书写顺序不同的内存值：writer 输出与 hash 必须相同
            ——规范形真的在生效（D2.12/D4.3）"
    (doseq [n vector-names
            :let [text (vector-text n)
                  envelope (edn/read-string text)]]
      (dotimes [_ 5]
        (is (= text (artifact/canonical-str (shuffle-deep envelope)))
            (str n "：打乱书写顺序后文本不同"))))))

(deftest references-order-in-the-recipe-does-not-change-the-hash
  (testing "`:references` 语义上是集合：写入前排序，因此菜谱里的引用顺序
            不进入内容语义，也不会无故改变 content-hash（D4.2）"
    (let [refs [{:workspace "z-team" :graph "z" :version 1
                 :content-hash (get-in golden [:content-hash "minimal"])}
                {:workspace "a-team" :graph "a" :version 2
                 :content-hash (get-in golden [:content-hash "minimal"])}
                {:workspace "a-team" :graph "a" :version 1
                 :content-hash (get-in golden [:content-hash "minimal"])}]
          h (fn [rs] (:content-hash (artifact/encode-content {} rs)))]
      (is (= (h refs) (h (vec (reverse refs))) (h (vec (shuffle refs))))))))

;; --- ④ 线程状态不进入身份 ---------------------------------------------------

(def ^:private hostile-bindings
  "曾经出过事的那一组：`*print-namespace-maps*` 没钉时，两个线程为同一内容
   算出不同 hash（D4.3）。这里把每一个会改变被哈希文本的 var 都拨到敌对值。"
  {#'*print-namespace-maps* true
   #'*print-length*         3
   #'*print-level*          2
   #'*print-readably*       nil
   #'*print-meta*           true})

(deftest hash-is-independent-of-caller-thread-print-vars
  (testing "调用方带着敌对的 print var 绑定 → 文本与 hash 逐字节不变"
    (doseq [n vector-names
            :let [text (vector-text n)
                  envelope (edn/read-string text)]]
      (with-bindings hostile-bindings
        (is (= text (artifact/canonical-str envelope))
            (str n "：调用线程的 print var 泄漏进了被哈希的文本"))))))

(deftest hash-is-identical-across-threads
  (testing "同一内容在多个线程算出同一个 hash。
            future 会传递动态绑定，因此敌对绑定确实跟着进了工作线程——
            这正是当年那次事故的形状。"
    (let [envelope (edn/read-string (vector-text "seven-kinds"))
          expected (get-in golden [:content-hash "seven-kinds"])
          results  (with-bindings hostile-bindings
                     (mapv deref
                           (doall (for [_ (range 8)]
                                    (future (artifact/content-hash-of-text
                                             (artifact/canonical-str envelope)))))))]
      (is (= [expected] (distinct results))))))

(deftest hash-is-identical-on-a-raw-thread-with-no-binding-conveyance
  (testing "裸线程（没有绑定传递，看到的是各 var 的**根**值）也算出同一个 hash"
    (let [envelope (edn/read-string (vector-text "edges"))
          result   (atom nil)
          t (Thread. #(reset! result (artifact/content-hash-of-text
                                      (artifact/canonical-str envelope))))]
      (.start t) (.join t)
      (is (= (get-in golden [:content-hash "edges"]) @result)))))

;; --- ⑤ 向量与真模型的连接 ---------------------------------------------------

(deftest seven-kinds-vector-really-carries-all-seven-concepts
  (testing "「七类 objects 全部出现」是这份向量的**全部意义**——
            它不该因为一次无心的编辑退化成一份少了 metric 的向量"
    (let [objects (:objects (edn/read-string (vector-text "seven-kinds")))]
      (doseq [k [:sources :namespaces :attributes :resolvers :mutations :links :metrics
                 :units :terms]]
        (is (seq (get objects k)) (str "seven-kinds 向量缺少 " k))))))

(deftest every-vector-is-a-model-the-dialect-accepts
  (testing "向量不是随手编的 EDN：每一份的 `:objects` 都过 B0 方言 reader 零 error。
            否则黄金向量守住的是一个平台根本不接受的形状。"
    (doseq [n vector-names
            :let [objects (:objects (edn/read-string (vector-text n)))
                  {:keys [model findings]} (dialect/read-dialect
                                            (artifact/canonical-str objects))]]
      (is (some? model)
          (str n "：方言 reader 拒绝了向量的 :objects —— "
               (pr-str (filterv #(= :error (:severity %)) findings)))))))

(deftest the-frozen-sealed-artifact-still-loads
  (testing "黄金向量不只是一串 hash：`b1-sealed/` 那三份冻结的文件必须仍然
            **装得进来**。装载规则一旦收紧（例如 report 的回显开始参与判定），
            冻结的向量若不再合法，说明收紧的那一步改变了 v1 的兼容边界——
            那是必须被看见的事，不该只有 hash 测试全绿。"
    (let [texts (into {} (for [f artifact/artifact-files]
                           [f (slurp (io/resource (str "golden/b1-sealed/" f))
                                     :encoding "UTF-8")]))
          r (artifact/read-artifact
             texts {:expected-digest (get-in golden [:b1-sealed :artifact-digest])
                    :closure :unverified})]
      (is (some? (:model r))
          (str "冻结的制品装不进来了：" (pr-str (:findings r))))
      (is (= {:content-hash :verified :artifact-digest :verified :references :unverified}
             (:verified r)))
      (is (= (:model (dialect/read-dialect (slurp (io/resource "graph.edn"))))
             (:model r))
          "它密封的就是 fixtures/b1/graph.edn，交回来的必须还是它"))))
