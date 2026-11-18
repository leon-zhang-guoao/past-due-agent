(ns open.atlas.integration.module-boundary-test
  "模块边界的执法**自己也要被执法**。

   CLAUDE.md 说「跨模块只许 require 接口 ns（`.clj-kondo/config.edn` 的
   `:discouraged-namespace` 执法）」。B4.2 复核时发现那份清单**整份是失效的**：
   条目写的是 `open.atlas.dialect.impl.*`，而实际 ns 从来都是
   `open.atlas.common.dialect.impl.*`——七条规则一条都没匹配上，另外 44 个
   impl ns 从未登记。lint 每次都干净，因为它在检查一批不存在的名字。

   这是本仓库反复栽的同一个坑（`:audit-groups` 写成不存在的键、fixtures 掉出
   classpath、`-d` 前缀被吃掉）：**「写了却没生效」不会报错，只会安静地什么都不做。**

   于是这份清单不再手工维护——磁盘是事实来源，本测试逐条对账。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond (nil? d) (throw (ex-info "repo root not found" {}))
          (.isDirectory (io/file d "modules")) d
          :else (recur (.getParentFile d)))))

(defn- impl-namespaces-on-disk
  "`modules/**/src/**/impl/*.clj` 里 `(ns …)` 声明的 ns 名。
   **读声明而不是从路径推**：路径要做下划线↔连字符的还原，而那正是当初
   `legacy_import` / `legacy-import` 那类错的温床。"
  [root]
  (into (sorted-set)
        (for [f (file-seq (io/file root "modules"))
              :when (and (.isFile ^java.io.File f)
                         (str/ends-with? (.getName ^java.io.File f) ".clj")
                         (str/includes? (.getPath ^java.io.File f) "/src/")
                         (str/includes? (.getPath ^java.io.File f) "/impl/"))
              :let  [m (re-find #"\(ns\s+([a-zA-Z0-9._-]+)" (slurp f))]
              :when m]
          (symbol (second m)))))

(defn- module-test-dirs
  "磁盘上每一个有 `test/` 的模块目录，形如 \"modules/common/dialect/test\"。"
  [root]
  (into (sorted-set)
        (for [f (file-seq (io/file root "modules"))
              :when (and (.isDirectory ^java.io.File f)
                         (= "test" (.getName ^java.io.File f))
                         (seq (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")
                                      (file-seq f))))]
          (str/replace (.getPath ^java.io.File f) (str (.getPath ^java.io.File root) "/") ""))))

(deftest aggregate-suite-runs-every-module
  (testing "根 deps.edn 的 `:test` **真的把每个模块都跑了**

           这条不是形式主义：同一个错我在这份文件上犯过**三次**——用字符串替换往
           `:main-opts` 里加目录时把 `-d` 前缀吃掉，test-runner 于是把它当成一个
           无名位置参数**默默吃掉**，那个模块的测试从此不跑，而聚合**照样全绿**。
           第一次是 fixtures 掉出 classpath，第二次 pool 的 8 个测试消失，
           第三次是 search 这一行多写了一份没有前缀的。

           「全绿」从来不等于「都跑了」——所以现在由这条测试对账，不靠盯断言数。"
    (let [root  (repo-root)
          deps  (edn/read-string (slurp (io/file root "deps.edn")))
          opts  (vec (get-in deps [:aliases :test :main-opts]))
          dirs  (into (sorted-set)
                      (keep (fn [[a b]] (when (= "-d" a) b))
                            (partition 2 1 opts)))
          orphan (vec (for [i (range (count opts))
                            :let [x (nth opts i)]
                            :when (and (string? x)
                                       (str/includes? x "/")
                                       (not= "-d" (when (pos? i) (nth opts (dec i)))))]
                        x))]
      (is (empty? orphan)
          (str "`:main-opts` 里有不带 `-d` 前缀的路径，test-runner 会静默忽略它："
               (pr-str orphan)))
      (is (empty? (into (sorted-set) (remove dirs) (module-test-dirs root)))
          "有模块的 test/ 目录没被 `-d` 列进聚合")
      (is (empty? (into (sorted-set) (remove (module-test-dirs root)) dirs))
          "`-d` 列了一个不存在或没有测试的目录"))))

(defn- module-dir-of
  "modules/<root>/<module> for a file somewhere under it, as a repo-relative
   string; nil for a file outside modules/."
  [root ^java.io.File f]
  (let [rel (str/replace (.getPath f) (str (.getPath ^java.io.File root) "/") "")
        segs (str/split rel #"/")]
    (when (and (= "modules" (first segs)) (<= 3 (count segs)))
      (str/join "/" (take 3 segs)))))

(defn- impl-ns->module-dir
  "impl ns → the module directory that owns it. Same scan as
   `impl-namespaces-on-disk`, keeping where each one was found."
  [root]
  (into {}
        (for [f (file-seq (io/file root "modules"))
              :when (and (.isFile ^java.io.File f)
                         (str/ends-with? (.getName ^java.io.File f) ".clj")
                         (str/includes? (.getPath ^java.io.File f) "/src/")
                         (str/includes? (.getPath ^java.io.File f) "/impl/"))
              :let  [m (re-find #"\(ns\s+([a-zA-Z0-9._-]+)" (slurp f))]
              :when m]
          [(second m) (module-dir-of root f)])))

(defn- clj-files [root]
  (for [f (file-seq (io/file root "modules"))
        :when (and (.isFile ^java.io.File f)
                   (str/ends-with? (.getName ^java.io.File f) ".clj"))]
    f))

(deftest no-file-reaches-into-another-module-s-impl-through-a-var-ref
  (testing "`#'other.module.impl.thing/private-fn` **is invisible to clj-kondo**.

           The boundary rule is enforced by `:discouraged-namespace`, and that
           linter only ever sees `:require` forms. A fully-qualified var ref
           needs no require at all — and the `#'` reader macro additionally
           walks straight past `defn-`. So the one construct that breaks the
           boundary hardest is the one construct the enforcement cannot see.

           This is the same shape as everything else in this file: the rule was
           written, it reads as enforced, and for one whole class of violation
           it silently does nothing. Five test files were piercing
           `studio.pool.impl.model/dialect->objects` this way, from three
           different modules, while lint stayed clean — and the reason was a
           real gap in the public surface (a pool could be exported to a model
           but not filled from one), which is now `pool/dialect->objects`.

           Module-internal `#'` into one's own impl stays legal: that is what a
           module's own tests are for, and it is what `.clj-kondo/config.edn`
           says in its own comment."
    (let [root  (repo-root)
          owner (impl-ns->module-dir root)
          bad   (vec (for [^java.io.File f (clj-files root)
                           :let [here (module-dir-of root f)
                                 text (slurp f)]
                           [_ refd] (re-seq #"#'([a-zA-Z0-9._-]+\.impl\.[a-zA-Z0-9._-]+)/" text)
                           :let [refd (first (re-find #"^(.*\.impl\.[a-zA-Z0-9_-]+)" refd))
                                 there (get owner refd)]
                           :when (and there (not= here there))]
                       {:file (str/replace (.getPath f) (str (.getPath ^java.io.File root) "/") "")
                        :reaches-into refd :owned-by there}))]
      (is (empty? bad)
          (str "a var ref pierces another module's impl — require that module's "
               "interface ns instead, and if what you need is not on it, that "
               "missing door is the actual defect: " (pr-str bad))))))

(defn- module-deps-files [root]
  (for [f (file-seq (io/file root "modules"))
        :when (and (.isFile ^java.io.File f) (= "deps.edn" (.getName ^java.io.File f)))]
    f))

(deftest every-module-deps-edn-stands-on-its-own
  (testing "每个模块的 deps.edn 都是**它自己的**，`:local/root` 从它所在目录解析得开

           这条也是被真事故逼出来的：B4.3 那次我在 `modules/integration` 目录下跑了
           一句 `cp /tmp/deps.bak deps.edn`，**把根 deps.edn 的内容整份写进了
           `modules/integration/deps.edn`**，并且提交了。根聚合照常全绿（它读的是根
           那一份），只有 `cd modules/integration && clojure -M:test` 会炸——而那正是
           CLAUDE.md 写着的两种跑法之一。

           模块边界是 **classpath 事实**（D1.8）。一个模块的 deps.edn 被换掉，
           那条事实就不再成立，而聚合测试看不见。"
    (let [root (repo-root)]
      (doseq [^java.io.File f (module-deps-files root)]
        (let [dir  (.getParentFile f)
              deps (edn/read-string (slurp f))
              roots (for [[_ v] (:deps deps)
                          :when (:local/root v)]
                      (:local/root v))]
          (is (contains? deps :paths)
              (str f " 没有 `:paths` —— 根 deps.edn 才是纯聚合，模块必须有自己的源码路径"))
          (doseq [r roots]
            (is (.isDirectory (io/file dir r))
                (str f " 的 :local/root " (pr-str r)
                     " 从模块目录解析不出来（相对根写的？那是被根 deps.edn 覆盖过的样子）"))))))))

(def ^:private allowed-roots
  "D1.8's dependency lattice, as of the fifth root (owner ruling 2026-08-25).
   Key = the root a module lives under; value = the roots it may depend on."
  {"common"  #{"common"}
   "runtime" #{"common" "runtime"}
   "studio"  #{"common" "runtime" "studio"}
   "server"  #{"common" "runtime" "server"}
   ;; The assembly point exists to put both apps on one classpath (D1.5); it is
   ;; test-only and has no src, so it cannot be depended on by anything.
   "integration" #{"common" "runtime" "studio" "server"}})

(deftest module-dependencies-obey-the-root-lattice
  (testing "D1.8 says the dependency direction is **verifiable at build time**,
            and CLAUDE.md repeats it — but until now nothing verified it. What
            makes `common` independent is only the *absence* of a `:local/root`
            line in ten deps.edn files, and an absence is exactly the kind of
            thing that stops being true without anyone noticing.

            The lattice matters more now that there are five roots: `runtime`
            exists so studio and server can share one runtime **without
            depending on each other** (D1.13). One `:local/root` from runtime
            back up into studio would quietly undo that, and every test would
            still pass."
    (let [root (repo-root)
          bad  (vec (for [^java.io.File f (module-deps-files root)
                          :let [dir  (module-dir-of root f)
                                segs (when dir (str/split dir #"/"))
                                here (when (= 3 (count segs)) (second segs))
                                ;; modules/integration has no third segment
                                here (or here (last (str/split (str/replace (.getPath (.getParentFile f))
                                                                            (str (.getPath ^java.io.File root) "/") "")
                                                               #"/")))
                                ok   (get allowed-roots here)]
                          :when ok
                          [_ v] (:deps (edn/read-string (slurp f)))
                          :let [lr (:local/root v)]
                          :when lr
                          :let [target (->> (str/split (str (.getCanonicalPath
                                                             (io/file (.getParentFile f) lr)))
                                                       #"/")
                                            (drop-while #(not= "modules" %))
                                            second)]
                          :when (and target (not (contains? ok target)))]
                      {:module dir :depends-on-root target :allowed ok}))]
      (is (empty? bad)
          (str "a module depends across a root the lattice forbids: " (pr-str bad))))))

(defn- src-namespaces
  "ns → 拥有它的模块目录。扫的是 `modules/**/src/**/*.clj` 里的 `(ns …)` 声明。"
  [root]
  (into {}
        (for [^java.io.File f (clj-files root)
              :when (str/includes? (.getPath f) "/src/")
              :let  [m (re-find #"\(ns\s+([a-zA-Z0-9._-]+)" (slurp f))]
              :when m]
          [(second m) (module-dir-of root f)])))

(defn- required-namespaces
  "一个 .clj 文件的 `(ns …)` 形里 require 的全部 ns。

   **读 ns 形，不 grep 全文**：全文 grep 会把 docstring 里提到的模块名也算成
   依赖，而这份文件的注释里到处都在写别的模块叫什么。"
  [^java.io.File f]
  (let [form (try (read-string (slurp f)) (catch Throwable _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (into #{}
            (for [clause form
                  :when (and (seq? clause) (#{:require :use} (first clause)))
                  spec  (rest clause)
                  :let  [n (cond (symbol? spec) spec
                                 (vector? spec) (first spec)
                                 :else nil)]
                  :when (symbol? n)]
              (str n))))))

(deftest every-namespace-a-module-requires-is-a-module-it-declares
  (testing "格子测试只查**已声明的**依赖会不会越根；它查不出反过来那一半——
            **直接 require 了却没在自己的 deps.edn 里声明**。那种依赖今天能编译,
            因为某个上游顺手把它传递了过来；上游哪天不再需要它，这里就在一次
            毫不相关的改动里断掉，而断的地方与改的地方隔着两个模块。

            这是本仓库反复栽的同一个形状：**能用不等于说清楚了**。`-d` 前缀被
            吃掉、discouraged 清单匹配不到任何东西、`#'` 穿透 lint 看不见——
            都是「写了却没生效」或「生效了却没写」，而两者都不报错。"
    (let [root  (repo-root)
          owner (src-namespaces root)
          bad   (vec (for [^java.io.File f (clj-files root)
                           :let [here (module-dir-of root f)
                                 test? (str/includes? (.getPath f) "/test/")
                                 deps-file (io/file root here "deps.edn")]
                           :when (.isFile deps-file)
                           :let [deps (edn/read-string (slurp deps-file))
                                 declared
                                 (into #{here}
                                       (for [[_ v] (merge (:deps deps)
                                                          (when test?
                                                            (get-in deps [:aliases :test :extra-deps])))
                                             :let [lr (:local/root v)]
                                             :when lr]
                                         (->> (str/split (str (.getCanonicalPath
                                                               (io/file (.getParentFile deps-file) lr)))
                                                         #"/")
                                              (drop-while #(not= "modules" %))
                                              (take 3)
                                              (str/join "/"))))]
                           required (or (required-namespaces f) #{})
                           :let [target (get owner required)]
                           :when (and target (not (contains? declared target)))]
                       {:file (str/replace (.getPath f) (str (.getPath ^java.io.File root) "/") "")
                        :requires required :owned-by target :declared declared}))]
      (testing "前提自检：确实扫到了 ns 与 require，不是空对空地相等
                （这份文件里每一条测试都栽在过「看起来在查、其实什么都没查」）"
        (is (< 40 (count owner)))
        (is (< 20 (count (filter seq (map required-namespaces (clj-files root)))))))
      (is (empty? bad)
          (str "a module requires a namespace it does not declare a dependency on — it "
               "compiles today only because something upstream happens to pull it in: "
               (pr-str (mapv #(select-keys % [:file :requires :owned-by]) bad)))))))

(deftest discouraged-namespace-list-matches-the-disk
  (let [root      (repo-root)
        cfg       (edn/read-string (slurp (io/file root ".clj-kondo/config.edn")))
        declared  (into (sorted-set) (keys (get-in cfg [:linters :discouraged-namespace])))
        on-disk   (impl-namespaces-on-disk root)]
    (testing "每一个 impl ns 都登记了 —— 漏一个，那个模块的私有边界就没人管"
      (is (empty? (into (sorted-set) (remove declared) on-disk))))
    (testing "没有登记不存在的 ns —— 一条规则若匹配不到任何东西，它就是失效的
              （lint 照样干净，而那正是它当初骗过所有人的方式）"
      (is (empty? (into (sorted-set) (remove on-disk) declared))))
    (testing "前提自检：确实扫到了东西，不是空对空地相等"
      (is (< 40 (count on-disk))))
    (testing "每条规则的 message 指向自己模块的接口 ns"
      (doseq [[n {:keys [message]}] (get-in cfg [:linters :discouraged-namespace])]
        (is (str/includes? (str message) (first (str/split (str n) #"\.impl\.")))
            (str n " 的提示指向了别的模块的接口 ns"))))))
