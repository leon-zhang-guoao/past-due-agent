(ns open.atlas.common.engine.impl.limits
  "EQL 四道边界（principles「执行与连接」、D1.6）：请求文本 8 KB、深度 8 层、
   执行 30 秒、序列化结果 256 KB。**超限即拒绝而非截断**——截断的结果与完整的
   结果无法区分，调用方看到的是更少的行和零个理由，agent 会对被静默切掉的业务
   数据展开推理。拒绝点名是哪道界限与实际值。

   每道界限「在能查得最便宜的那一点检查」，四个检查点见 `open.atlas.common.engine`
   的 docstring。四个值可配置，默认即上述四个。"
  (:require [clojure.edn :as edn]
            [open.atlas.common.connector.spi :as spi]))

(def defaults
  "四道边界的默认值。可经 compile-env 的 `:limits` 覆盖，但不能取消——
   取消一道界限是让它在生产上第一次生效，与 D1.6「只在 prod 生效的执法点」同病。"
  {:request-bytes  8192      ; 8 KB 请求文本
   :depth          8         ; 8 层嵌套
   :execution-ms   30000     ; 30 秒执行
   :result-bytes   262144})  ; 256 KB 序列化结果

(defn resolve-limits
  "用户覆盖 + 默认值。未知键不接受——四道就是四道，多一道意味着有一道没人执法。"
  [overrides]
  (when-let [unknown (seq (remove (set (keys defaults)) (keys overrides)))]
    (throw (ex-info "unknown limit keys" {:unknown (vec unknown)
                                          :known (vec (keys defaults))})))
  (merge defaults overrides))

(defn- utf8-bytes ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn over-request-bytes
  "第一道：请求文本。最便宜的检查点是**读之前**——parse 一段超限文本本身就是代价。"
  [^String text limits]
  (let [n (utf8-bytes text)]
    (when (> n (:request-bytes limits))
      {:limit :request-bytes :max (:request-bytes limits) :actual n})))

(defn- refuse-tag [tag _value]
  (throw (ex-info "reader tags are not accepted in EQL requests" {:tag tag})))

(def ^:private no-tags
  "**无 tag handler**（principles）。`clojure.edn` 自带 `#inst` / `#uuid`
   两个内置 reader，光传 `{}` 挡不住它们——内置 tag 也是 tag，读进来就是一次
   隐式转换。所以两件事一起做：把内置 tag 覆盖成抛出，再用 `:default` 兜住
   其余全部 tag。"
  {:readers {'inst refuse-tag 'uuid refuse-tag}
   :default refuse-tag})

(defn read-request
  "EDN 用 clojure.edn 无 tag handler 读（principles）。任何 reader tag 直接抛，
   不注册 default-data-reader——默认 handler 就是「不做强制转换」的反面。"
  [^String text]
  (edn/read-string no-tags text))

(defn eql-depth
  "EQL 语法树的嵌套深度：平坦查询是 1 层，每进一个 join 加一层。
   第二道界限在 **parse 之后、规划之前**检查——树已经在手上，走一遍是常数级，
   而规划一棵 40 层的树不是。"
  [query]
  (letfn [(step [node d]
            (cond
              (map? node)    (if (seq node)
                               (apply max (map #(step % (inc d)) (vals node)))
                               d)
              (vector? node) (if (seq node)
                               (apply max (map #(step % d) node))
                               d)
              :else          d))]
    (step query 1)))

(defn over-depth [query limits]
  (let [d (eql-depth query)]
    (when (> d (:depth limits))
      {:limit :depth :max (:depth limits) :actual d})))

(defn over-result-bytes
  "第四道：序列化结果。检查点在**档位过滤之后**——离开平台的是过滤后的那份，
   拿过滤前的大小算界限会为调用方永远看不到的字节而拒绝。"
  [serialized limits]
  (let [n (utf8-bytes serialized)]
    (when (> n (:result-bytes limits))
      {:limit :result-bytes :max (:result-bytes limits) :actual n})))

(defn with-deadline
  "第三道：执行时限。检查点只能是执行本身——包住 pathom 的那一次调用。
   超时即拒绝，绝不返回「跑到一半的」结果：半份结果与完整结果同样无法区分。
   返回 `[:ok v]` / `[:timeout actual-ms]` / `[:error throwable]`。

   **超时时主动 abort 在途的 source 调用**（D6.3, doc 107 §107.7）。
   `future-cancel` 只停止**等待**：它 interrupt 那个线程，而 Apache HttpClient
   的阻塞 socket 读不理会 interrupt，于是连接会一直占着池直到上游愿意回话——
   600 秒预算下一个分块可以独自跑到 120 秒空闲，这正是 §107.1 记的那条不一致。
   所以这里除了取消等待，还要经 SPI 的 abort 钩子伸进连接器拥有的那层传输。"
  [limits f]
  (let [ms  (:execution-ms limits)
        fut (future (try [:ok (f)] (catch Throwable t [:error t])))
        r   (deref fut ms ::timeout)]
    (if (= ::timeout r)
      (do (spi/abort-all!)
          (future-cancel fut)
          [:timeout {:limit :execution-ms :max ms :actual ms}])
      r)))
