(ns open.atlas.studio.version.closure-test
  "**闭包解析**：R3.1（永不指 latest）、R3.4（钻石冲突拒绝，点名两条路径）、
   R3.8（沿路径取严，且 **R3.4 在前**）。

   本文件**不开池**：闭包解析是纯函数，输入是菜谱引用与已解析的钉版本引用。
   装料用 `artifact/encode-content` 真的编一遍 `content.edn`——手搓一个信封会
   让测试测的是我对信封的记忆，而不是 B3 的产物。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.version :as version]))

(defn- read! [m]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private tiny
  (read! {:attributes {:x.a/id {:type :integer :format :id :identity? true :label "A"}}
          :namespaces {"x.a" {:kind :master :label "A"}}}))

(defn- artifact-of
  "一份已解析引用：内容真的经 `encode-content` 走一遍，于是 content-hash 对得上。"
  [qn version refs]
  (let [{:keys [envelope content-hash findings]} (artifact/encode-content tiny (vec refs))]
    (assert (empty? findings) (pr-str findings))
    {:graph qn :version version :content envelope :content-hash content-hash
     :artifact-digest (str "sha256:" (apply str (repeat 64 "a")))}))

(defn- env-ref [ws nm v & {:keys [up-to]}]
  (cond-> {:workspace ws :graph nm :version v
           :content-hash (str "sha256:" (apply str (repeat 64 "b")))}
    up-to (assoc :up-to up-to)))

(defn- codes [r] (set (map :code (:findings r))))

;; ---------------------------------------------------------------------------
;; R3.1
;; ---------------------------------------------------------------------------

(deftest r3-1-a-reference-never-points-at-latest
  "R3.1：引用只钉版本。`latest`、空、非法版本一律拒绝，不猜。"
  (doseq [v ["latest" "" "v0" nil :latest]]
    (let [r (version/resolve-closure "s/core" [{:graph "b/b" :version v}] [])]
      (is (= #{:cut.closure/unpinned-version} (codes r)) (pr-str v))
      (is (nil? (:nodes r)) "错误与产物互斥")
      (is (every? version/finding? (:findings r)))))
  (testing "菜谱写 \"v3\"、制品信封写 3，是同一个身份"
    (is (= 3 (version/normalize-version "v3") (version/normalize-version 3)))))

(deftest r3-1-holds-inside-the-closure-too
  "R3.1 在**传递**引用上同样成立：被引用制品自己的引用清单里若出现非钉版本，
   照样拒绝——闭包里有一条边指不明，整个闭包就不是一个确定的东西。"
  ;; B3 的 encoder 已经挡了一道（`:artifact.reference/invalid-shape`），所以这里
  ;; 只能把一份**已经编好**的信封改坏——这正是它要防的形态：制品被人手改过。
  (let [c (artifact-of "c/c" "v1" [(env-ref "b" "b" 1)])
        c (assoc-in c [:content :references 0 :version] "latest")
        r (version/resolve-closure "s/core" [{:graph "c/c" :version "v1"}] [c])]
    (is (= #{:cut.closure/unpinned-version} (codes r)))))

;; ---------------------------------------------------------------------------
;; R3.4 —— 退出条件 2
;; ---------------------------------------------------------------------------

(deftest r3-4-diamond-conflict-is-refused-and-names-both-paths
  "**退出条件 2**：A→B@v1、A→C→B@v2 → 拒绝切，点名 graph、两个版本、**两条路径**。
   不做自动仲裁（R3.4 原话：层级浅，拒绝够用）。"
  (let [b1 (artifact-of "b/b" "v1" [])
        b2 (artifact-of "b/b" "v2" [])
        c1 (artifact-of "c/c" "v1" [(env-ref "b" "b" 2)])
        r  (version/resolve-closure "a/a"
                                    [{:graph "b/b" :version "v1"}
                                     {:graph "c/c" :version "v1"}]
                                    [b1 b2 c1])
        [f] (:findings r)]
    (is (= #{:cut.closure/version-conflict} (codes r)))
    (is (nil? (:nodes r)) "冲突时不产闭包——档位合并不得掩盖版本冲突")
    (is (= "b/b" (:got f)) "点名是哪个 graph")
    (is (= ["v1" "v2"] (mapv :version (:expected f))) "点名哪两个版本")
    (is (= ["a/a → b/b@v1" "a/a → c/c@v1 → b/b@v2"] (mapv :via (:expected f)))
        "点名经由哪两条路径——作者要靠它知道该改哪一条边")
    (is (every? version/finding? (:findings r)))
    (is (not-any? #(re-find #"sha256:" (pr-str %)) (:findings r))
        "拒绝不含配置值")))


;; ---------------------------------------------------------------------------
;; R3.8
;; ---------------------------------------------------------------------------



;; ---------------------------------------------------------------------------
;; 闭包本身
;; ---------------------------------------------------------------------------

(deftest closure-is-transitive-and-complete
  "闭包是**传递**的：菜谱只写了 c，b 经 c 进来。"
  (let [b1 (artifact-of "b/b" "v1" [])
        c1 (artifact-of "c/c" "v1" [(env-ref "b" "b" 1)])
        r  (version/resolve-closure "a/a" [{:graph "c/c" :version "v1"}] [b1 c1])]
    (is (= [["b/b" 1] ["c/c" 1]] (:order r)))))

(deftest a-version-in-the-closure-that-was-not-supplied-is-refused
  "传递闭包里的版本没被交进来 → 拒绝，点名是哪一个、经由哪条路径。
   **不静默地少装一段**：少掉的那一段会让下游对着一份缺了引用的图推理。"
  (let [c1 (artifact-of "c/c" "v1" [(env-ref "b" "b" 1)])
        r  (version/resolve-closure "a/a" [{:graph "c/c" :version "v1"}] [c1])
        [f] (:findings r)]
    (is (= #{:cut.closure/reference-missing} (codes r)))
    (is (= "b/b@v1" (:got f)))
    (is (= "a/a → c/c@v1 → b/b@v1" (:expected f)))))

(deftest a-cycle-is-refused-not-truncated
  "(graph, 版本) 上本该是 DAG（R3.3）。成环拒绝，不截断——截断会让闭包少一段
   而没人知道。"
  (let [c1 (artifact-of "c/c" "v1" [(env-ref "d" "d" 1)])
        d1 (artifact-of "d/d" "v1" [(env-ref "c" "c" 1)])
        r  (version/resolve-closure "a/a" [{:graph "c/c" :version "v1"}] [c1 d1])]
    (is (= #{:cut.closure/cycle} (codes r)))))

(deftest direct-references-are-the-recipe-edges-not-the-whole-closure
  "`content.edn` 的 `:references` 只写菜谱的**直接**引用（D4.2：被引用版本
   各自成目录、不内联；D4.4：三元组、钉版本、带 `:up-to`）。"
  (let [b1 (artifact-of "b/b" "v1" [])
        c1 (artifact-of "c/c" "v1" [(env-ref "b" "b" 1)])
        recipe [{:graph "c/c" :version "v1"}]
        cl (version/resolve-closure "a/a" recipe [b1 c1])
        refs (version/direct-references recipe [b1 c1] cl)]
    (is (= 1 (count refs)) "b 只经 c 到达，不在直接引用清单里")
    (is (= {:workspace "c" :graph "c" :version 1}
           (dissoc (first refs) :content-hash)))
    (is (= (:content-hash c1) (:content-hash (first refs)))
        "带上解析时那一份的 hash——装载方靠它验「拿到的确实是这一份」（D4.4）")))
