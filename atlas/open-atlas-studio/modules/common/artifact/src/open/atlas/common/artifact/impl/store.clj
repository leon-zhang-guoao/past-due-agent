(ns open.atlas.common.artifact.impl.store
  "落盘边界与制品架（D4.8/D4.2）。

   ```text
   <artifact-root>/
   └── <workspace>/<graph>/<version>/
       ├── content.edn
       ├── manifest.edn
       └── report.edn
   ```

   纪律逐条：`<artifact-root>` 限制在配置的根内；**拒绝**路径穿越（`..`）、
   符号链接逃逸、非法名称与大小写冲突路径；写入必须**原子**——部分写入的
   版本不得被装载或部署；已存在的版本目录是**拒绝**而不是合并进去。

   制品内的任何路径都相对于制品根解析，**绝不相对于进程 `cwd`**：本 ns 的
   每个公开函数都收一个显式 root，没有任何一处调用 `(System/getProperty
   \"user.dir\")` 或依赖相对路径的默认解析。这条的验收方式是：同一制品在不同
   机器不同目录下装载，行为一致。

   **原子写的落法**：先写进同一文件系统内的临时目录，逐份文件落盘并 fsync，
   再对**目录**做一次 `ATOMIC_MOVE`。移动一个目录是单次原子操作，因此
   「三份文件同时出现」——中途崩溃留下的是临时目录，而不是一个只有
   `content.edn` 的版本目录。"
  (:require [clojure.java.io :as io]
            [open.atlas.common.artifact.impl.canonical :as c]
            [open.atlas.common.artifact.impl.finding :as f])
  (:import [java.io File]
           [java.nio.file Files Path Paths StandardCopyOption LinkOption
            AtomicMoveNotSupportedException]
           [java.nio.file.attribute FileAttribute]
           [java.nio.charset StandardCharsets]))

(def segment-pattern
  "合法路径段：ASCII 字母数字起头，其后允许 `.` `_` `-` `@`。

   **为什么这么窄**：制品要能被复制到任何地方（D4.5），而「任何地方」包括
   大小写不敏感的文件系统、不接受某些字符的归档格式与不同 locale 的机器。
   `..`、`/`、`\\`、空格、控制字符与非 ASCII 一律不是合法段——限定名的取值
   域由 D3.10 决定，此处只是把它在文件系统上钉死。

   `@` 在 2026-09-05 由 owner 加入（artifact-v1 §8）：白名单的用途是挡住路径穿越
   （`..`、`/` 与各种分隔符），`@` 穿不出去、且在我们发布的每一种文件系统上都合法,
   而 D3.22 之后个人 workspace 以邮箱命名，它必须是合法段。"
  #"[A-Za-z0-9][A-Za-z0-9._@-]*")

(defn portable-segment?
  "artifact-v1 §8 的段名判定。**这是唯一一份**——警告与拒绝两侧都问它，
   否则「什么是合法段」会有两个答案（D1.8）。"
  [s]
  (boolean (and (string? s) (re-matches segment-pattern s))))

(defn- segment-error [seg label]
  (cond
    (f/blank-str? seg)
    (f/error :artifact.store/path-escape [label]
             (str label " must be a non-empty string") :got (f/redact seg)
             :refs ["D4.8" "D3.10"])
    (not (re-matches segment-pattern seg))
    (f/error :artifact.store/path-escape [label]
             (str label " is not a portable path segment; `..`, separators, spaces "
                  "and non-ASCII characters are rejected, not sanitised")
             :got seg :expected (str segment-pattern) :refs ["D4.8"])))

(defn check-coordinates
  "graph 版本身份 `(workspace, graph, version)`（D4.3）在文件系统上的合法性。"
  [{:keys [workspace graph version]}]
  (vec (remove nil?
               [(segment-error workspace "workspace")
                (segment-error graph "graph")
                (when-not (and (integer? version) (pos? version))
                  (f/error :artifact.store/path-escape [:version]
                           "version must be a positive integer; R3.1 never points at latest"
                           :got (f/redact version) :refs ["D4.8" "R3.1"]))])))

(defn- ^Path as-path [x]
  (if (instance? Path x) x (.toPath (io/file x))))

(defn- ^Path anchored
  "Anchor a path on the **real path of its deepest existing ancestor**, with the
   not-yet-existing segments appended unchanged.

   The baseline for an escape check can only be a real path — otherwise a
   symlink pointing elsewhere makes every prefix comparison succeed while reads
   and writes land outside the root. But requiring the whole path to exist does
   not work either: the leaf directory has not been created yet.

   Both sides (root and candidate) go through this, because **normalizing only
   one side manufactures escapes that are not there**: on macOS `/var` is a
   symlink to `/private/var`, so a normalized candidate never shares a prefix
   with an unnormalized root."
  [^Path p]
  (let [abs (.normalize (.toAbsolutePath p))
        deepest (loop [x abs]
                  (cond (nil? x) nil
                        (Files/exists x (into-array LinkOption [])) x
                        :else (recur (.getParent x))))]
    (if deepest
      (.resolve (.toRealPath deepest (into-array LinkOption [])) (.relativize deepest abs))
      abs)))

(defn- ^Path real-root
  "The root's real path, the baseline for escape checks. When the root does
   **not exist yet** (an empty shelf) this does not throw: nothing lives under
   it, so there is no symlink to escape through, and the `.isFile` checks at the
   leaf report the artifact as absent, which is the truth. Letting
   `NoSuchFileException` out would turn \"the shelf is still empty\" into a crash."
  [root]
  (anchored (as-path root)))

(defn- escapes?
  "Does the resolved path escape the root? Both sides go through `anchored`,
   which catches a mid-path symlink that points outside the root without
   requiring the leaf directory to exist."
  [^Path root ^Path candidate]
  (not (.startsWith (anchored candidate) (anchored root))))

(defn- case-collision
  "大小写冲突路径（D4.8）：同一父目录下已存在一个只在大小写上不同的兄弟。
   在大小写不敏感的文件系统上它们是同一个目录——**拒绝**而不是让两个限定名
   在一台机器上悄悄合并成一个。"
  [^File parent ^String name*]
  (when (.isDirectory parent)
    (first (for [^String existing (or (seq (.list parent)) [])
                 :when (and (not= existing name*)
                            (.equalsIgnoreCase existing name*))]
             existing))))

(defn version-dir
  "root + 坐标 → `{:dir File}` 或 `{:findings [...]}`。不创建任何目录。"
  [root {:keys [workspace graph version] :as coords}]
  (let [coord-errs (check-coordinates coords)]
    (if (seq coord-errs)
      {:findings (f/sort-findings coord-errs)}
      (let [rr   (real-root root)
            cand (.resolve rr (Paths/get workspace
                                         (into-array String [graph (str version)])))]
        (if (escapes? rr cand)
          {:findings [(f/error :artifact.store/path-escape []
                               (str "resolved artifact path escapes <artifact-root>: "
                                    workspace "/" graph "/" version)
                               :refs ["D4.8"])]}
          {:dir (.toFile cand)})))))

;; --- 写 ---------------------------------------------------------------------

(defn atomic-move!
  "The one atomic landing (D4.8). It sits behind its own var so a test can make
   it raise `AtomicMoveNotSupportedException` — D4.12's refusal branch is
   otherwise only reachable by putting the shelf's parent on a second
   filesystem, which no unit test can arrange, and an exit condition nothing
   can reach is an exit condition nothing is checking."
  [^Path from ^Path to]
  (Files/move from to (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn- fsync-file! [^File file ^String text]
  (with-open [out (java.io.FileOutputStream. file)]
    (.write out (c/utf8-bytes text))
    (.flush out)
    (.sync (.getFD out))))

(defn write-artifact!
  "三份文件原子落盘。`files` 是 `seal-graph` 的 `:files`——写下去的与算过
   digest 的是同一份文本，不给两者分叉的机会。

   已存在的版本目录是**拒绝**而不是合并进去（D4.8）：版本不可变，一次
   「合并」等于让同一个 `(workspace, graph, version)` 在两个时刻指向两份内容，
   而全平台的引用同一性都建立在它不会发生之上（D4.4）。"
  [root coords files]
  {:pre [(= (set c/digest-file-order) (set (keys files)))]}
  (let [{:keys [dir findings]} (version-dir root coords)]
    (cond
      (seq findings) {:findings findings}

      (.exists dir)
      {:findings [(f/error :artifact.store/version-exists []
                           (str "version directory already exists: "
                                (:workspace coords) "/" (:graph coords) "@"
                                (:version coords)
                                "; a cut version is immutable and is never merged into")
                           :refs ["D4.8" "D4.4"])]}

      :else
      (let [^File parent (.getParentFile dir)]
        (if-let [clash (case-collision parent (.getName dir))]
          {:findings [(f/error :artifact.store/path-escape []
                               (str "case-colliding sibling already exists: "
                                    clash " vs " (.getName dir))
                               :got (.getName dir) :expected clash :refs ["D4.8"])]}
          (do
            (.mkdirs parent)
            (let [staging (Files/createTempDirectory
                           (.toPath parent) ".staging-"
                           (into-array FileAttribute []))]
              (try
                (doseq [fname c/digest-file-order]
                  (fsync-file! (io/file (.toFile staging) fname) (get files fname)))
                (try
                  (atomic-move! staging (.toPath dir))
                  (catch AtomicMoveNotSupportedException _
                    ;; D4.12：**拒绝，不退回**。staging 建在目标的父目录里，
                    ;; 同一文件系统内 ATOMIC_MOVE 总是可用——走到这里说明父目录
                    ;; 是个挂载点之类的东西，两边跨了卷。此时唯二的选择是退回
                    ;; 普通 move（复制 + 删除，中途失败留下半份）或拒绝，而
                    ;; D4.8 说「写入必须原子，**无例外**」：半份制品被装载或
                    ;; 部署正是它要防的事。退回是**静默降级**——那台机器从此
                    ;; 没有原子性，而没有任何人会知道。
                    (throw (ex-info "atomic move unavailable"
                                    {::atomic-move-unavailable true}))))
                {:dir dir :findings []}
                (catch Throwable t
                  (doseq [^File leftover (reverse (file-seq (.toFile staging)))]
                    (.delete leftover))
                  (if (::atomic-move-unavailable (ex-data t))
                    {:findings [(f/error :artifact.store/atomic-move-unavailable []
                                         (str "the staging directory and the artifact shelf are not "
                                              "on the same filesystem, so the landing cannot be "
                                              "atomic; this is a configuration error, not a runtime "
                                              "fallback — a non-atomic landing would leave a partial "
                                              "version that must never be loaded or deployed")
                                         :refs ["D4.8" "D4.12"])]}
                    (throw t)))))))))))

;; --- 读 ---------------------------------------------------------------------

(defn read-texts
  "版本目录 → `{文件名 → 文本}`。缺席或不可读的文件在 map 里就是缺席——
   reader 会把它变成 `:artifact.integrity/missing-file`（D4.8）。
   不在这里判定「合法」：**装载语义只有一处**，就是 impl.reader。"
  [^File dir]
  (into {}
        (for [fname c/digest-file-order
              :let  [file (io/file dir fname)]
              :when (and (.isFile file) (.canRead file))]
          [fname (slurp file :encoding "UTF-8")])))

(defn read-shelf-texts
  "root + coordinates → `{filename → text}`. Absent, unreadable, or
   out-of-bounds coordinates yield an empty map, i.e. plain absence: this
   function reads bytes and judges nothing (loading semantics live in exactly
   one place, impl.reader), and the caller decides by whether all three files
   came back. A coordinate that escapes <artifact-root> (D4.8) resolves to no
   bytes at all, which is the direction that fails closed.

   It used to hand `[root coords]` straight to `read-texts`, which takes a
   directory — so every call threw on arity. Nothing caught it because both
   callers (`runtime/graph`'s `:shelf-artifact` read and `studio/deploy`'s
   plan) inject fake reads in their own unit tests; it took the assembly point
   to run the real one."
  [root coords]
  (let [{:keys [dir]} (version-dir root (select-keys coords [:workspace :graph :version]))]
    (if dir (read-texts dir) {})))

(defn shelf-reader
  "制品架上的 `content.edn` 文本解析函数，供 reader 校验引用闭包（D4.1/D4.4）。
   坐标非法或不存在 → nil，reader 报 `:artifact.reference/missing`。"
  [root]
  (fn [coords]
    (let [{:keys [dir]} (version-dir root (select-keys coords [:workspace :graph :version]))
          file (when dir (io/file dir "content.edn"))]
      (when (and file (.isFile file) (.canRead file))
        (slurp file :encoding "UTF-8")))))
