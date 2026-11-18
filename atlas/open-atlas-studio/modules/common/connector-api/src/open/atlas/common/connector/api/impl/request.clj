(ns open.atlas.common.connector.api.impl.request
  "HTTP execution and failure classification for the api connector.

   Failure semantics changed from the migrated open-atlas plugin, which
   returned nil on any non-2xx/exception and deposited an observability
   stat. The platform SPI defines nil as 'this resolver cannot supply these
   attributes' — NOT an error — so here every non-2xx, timeout, and
   connection exception goes through `spi/fail!` with a category, and the
   deposit-stat mechanism is not migrated. Observability is the host-bound
   `spi/trace-statement!` channel instead.

   Leak discipline: no logging anywhere; fail! details are configuration
   phrases (a status code, a category) — never a URL, never a response
   body, never an exception message."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [open.atlas.common.connector.api.impl.conn :as conn]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.api.impl.oauth :as oauth]
            [open.atlas.common.connector.api.impl.resilience :as resilience]
            [open.atlas.common.connector.spi :as spi])
  (:import [java.net ConnectException SocketTimeoutException UnknownHostException]
           [org.apache.http.conn ConnectTimeoutException]))

(def default-timeout-ms
  "Per-request timeout default (principles: timeouts are enforced and have
   a default). Note what the value bounds today: clj-http's :socket-timeout is
   per-read INACTIVITY, not a whole-response deadline. §107.3 re-reads
   `:timeout_ms` as the ATTEMPT total; that re-reading is not compatible with
   published api-v1 sources and lands with api-v2 in PR C."
  (:timeout_ms spi/platform-default-policy))

(def connector-default-policy
  "The api connector's level of the four-level merge (D6.2). It sets only what
   is specific to HTTP; everything else comes from the platform default."
  {:timeout_ms default-timeout-ms})

(defn effective-policy
  "The policy in force for one call.

   `source-config` carries `:resilience` -- for a Semantic 1.4 graph that block
   has already been merged and clamped by the runtime (slot policy, binding
   override, deployment capacity, tighten-only); for a plain dialect model it is
   what the source itself declares. A binding-level `:policy` that reached the
   operation block is applied on top.

   Every key is present in the result, so no call site re-derives a default."
  ([source-config] (effective-policy source-config nil))
  ([source-config config]
   (assoc (spi/effective-policy [connector-default-policy
                                 (:resilience source-config)
                                 (:policy config)]
                                nil)
          ;; **Which contract reads `:timeout_ms`** (api-v2, doc 107 §107.2).
          ;; Not a declarable key: the runtime sets it from the graph's declared
          ;; `:source/policy-v1`, because re-reading a published `:timeout_ms
          ;; 120000` from "two minutes of silence" to "two minutes in total" is
          ;; a different promise about the same number, and only the modeller
          ;; who wrote it can say which one he meant. A graph that has not opted
          ;; in keeps the api-v1 meaning exactly.
          :attempt-total-timeout?
          (boolean (or (:semantic/attempt-total-timeout? source-config)
                       (:semantic/attempt-total-timeout? config))))))

(def fingerprint-of
  "The source identity a bulkhead and a breaker are keyed on (D6.5)."
  resilience/fingerprint)

(defn timeout-ms
  "`:resilience {:timeout_ms n}` first (D6.2), then a top-level
   :timeout_ms, else the default — both shapes accepted, as in the
   migrated plugin, so a source setting only the top-level key does not
   silently run on the default."
  [source-config]
  (or (get-in source-config [:resilience :timeout_ms])
      (:timeout_ms source-config)
      default-timeout-ms))

(defn classify
  "Exception → :error/category. The exception is inspected HERE and dropped
   — its message can carry the URL and host names (same discipline as the
   sql connector's impl.exec/classify)."
  [^Exception e]
  (cond
    (instance? SocketTimeoutException e)  :timeout
    (instance? ConnectTimeoutException e) :timeout
    (instance? ConnectException e)        :connection
    (instance? UnknownHostException e)    :connection
    :else
    (let [msg (str/lower-case (or (.getMessage e) ""))]
      (cond
        (re-find #"timeout|timed out" msg)                        :timeout
        (re-find #"connect|connection|ssl|handshake|refused" msg) :connection
        :else                                                     :source))))

(defn auth-headers
  "Header map for the source's :auth block, dispatched on the :type
   discriminator (api-v1 :auth table). oauth2 fetches (or reuses) a token;
   api_key resolves the static value. nil auth → no headers. Returns
   {:headers {..} :token t-or-nil} so the caller can drive the 401 retry."
  [auth]
  (cond
    (nil? auth) {:headers nil :token nil}
    (= "api_key" (:type auth)) {:headers (oauth/api-key-header auth) :token nil}
    ;; Contract makes :type mandatory; oauth2 is also accepted here without
    ;; it for robustness at runtime — validate rejects the missing key.
    :else (let [token (oauth/access-token auth)]
            {:headers (oauth/bearer-header auth token) :token token})))

(defn tagging-headers
  "**E2b：这次 HTTP 调用带的自报家门。**

   两层，因为「对方支不支持」在 HTTP 上对这两件事的答案不一样：

   - **`User-Agent` 无条件发**（建模者自己在 `:headers` 里写了就以他的为准）。
     它是 `ApplicationName` 的 HTTP 对应物：任何 API 都收、任何访问日志都记,
     对方运维不用做任何事就能认出我们的流量。**这就是「如果 API 支持」在这一层
     恒为真的原因**——没有哪个 HTTP 端点会因为一个 UA 而失败。
   - **关联 id 按 `:trace_headers` 声明发。** 往第三方请求里塞建模者没写过的
     header 是在改一次他授权之外的调用：有的 API 白名单 header，有的把未知
     header 计入签名。所以这一层要建模者签一次字，代价是一行配置。

   值取自 `spi/*request-trace*`（`{:request-id … :graph-id …}`，D3.18 的 graph
   uuid 就在里面）——engine 在每次 source 调用的叶子上绑它。没有 trace（单测、
   CLI、health check）就不发，不编一个。

   只留 `[A-Za-z0-9-]`：这些值要进 HTTP header，过滤让「它注不进第二个 header」
   成为代码事实，而不是一句约定（同 `spi/application-name`）。"
  [{:keys [trace_headers]} declared-headers]
  (let [own-ua? (boolean (some #(= "user-agent" (str/lower-case (name %)))
                               (keys (or declared-headers {}))))
        san     (fn [v] (some-> v str (str/replace #"[^A-Za-z0-9-]" "") not-empty))
        t       spi/*request-trace*]
    (cond-> {}
      (not own-ua?)
      (assoc "User-Agent" (spi/application-name))

      (and (:request-id trace_headers) (san (:request-id t)))
      (assoc (:request-id trace_headers) (san (:request-id t)))

      (and (:graph-id trace_headers) (san (:graph-id t)))
      (assoc (:graph-id trace_headers) (san (:graph-id t))))))

(defn- drain-bytes
  "Count (and discard) at most `max-bytes` of a response we will not parse —
   a non-2xx on the streaming probe path. The bytes are counted and dropped:
   an error body is upstream content like any other (finding-v1 §5)."
  [^java.io.InputStream in max-bytes]
  (let [cap (long (or max-bytes 0))
        buf (byte-array 8192)]
    (loop [n 0]
      (if (>= n cap)
        n
        (let [r (.read in buf 0 (int (min 8192 (- cap n))))]
          (if (neg? r) n (recur (+ n r))))))))

(defn- release-body!
  "Let go of a response body we are NOT going to finish reading.

   **Never on the caller's thread.** Closing an Apache response stream means
   «I am done, keep the connection», and to keep it Apache first DRAINS what is
   left -- so closing a body that is never coming blocks until the socket
   timeout, on the very path that exists because we already decided to stop
   waiting. (Measured: it hung the whole test suite.) `abortConnection` would
   discard it outright, but clj-http hands back a proxy wrapping the
   `EofSensorInputStream`, so that method is not reachable from here without
   reflecting into `java.base`.

   So the close is handed to a daemon thread and the caller walks away. The
   pool slot comes back when the drain finishes or the socket timeout fires --
   bounded by the same attempt budget, because that is what `:socket-timeout`
   was set to. D6.3 asks that a timed-out attempt not GO ON holding a slot; it
   does not ask the caller to wait while it lets go."
  [body]
  (doto (Thread. ^Runnable (fn [] (try (.close ^java.io.Closeable body)
                                       (catch Throwable _ nil)))
                 "atlas-api-release")
    (.setDaemon true)
    (.start))
  nil)

(defn- read-body-by
  "Read a response body, giving up at the ABSOLUTE instant `by` (D6.3).

   This is where `:timeout_ms` stops being an IDLE timeout. clj-http's
   `:socket-timeout` bounds one read with no data; a source that trickles a byte
   every four seconds under a five-second socket timeout never trips it, and
   the SE `sources.edn` comment about a «10 分钟涓流» is that hole written down.
   The loop below checks the clock between reads, so the body costs at most the
   attempt's whole budget however the bytes are spaced.

   A non-stream body (every mocked response, and the probe paths) is returned
   untouched: there is nothing left to wait for."
  [body by abort!]
  (if-not (instance? java.io.InputStream body)
    body
    (let [out (java.io.ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop []
        (if (and by (<= (- (long by) (System/currentTimeMillis)) 0))
          (do (abort!)
              (throw (java.net.SocketTimeoutException. "attempt deadline exceeded")))
          (let [n (.read ^java.io.InputStream body buf)]
            (if (neg? n)
              (String. (.toByteArray out) "UTF-8")
              (do (.write out buf 0 n) (recur)))))))))

(defn- within
  "Run `f` on another thread and give up waiting at the absolute instant `by`,
   running `abort!` and throwing a timeout instead.

   Why a thread rather than trusting the per-phase timeouts: connect, waiting
   for the first byte and reading the body have SEPARATE clj-http timeouts, and
   §107.7 says explicitly that two independent timeouts must not be added up
   and called a total. The caller's clock is the only place the total exists.

   The worker may stay blocked briefly after we stop waiting -- Apache's
   blocking read ignores an interrupt -- but each of its phases is itself capped
   at the same budget, so it unwinds and releases its connection rather than
   holding a pool slot until the source feels like answering (D6.3)."
  [by abort! f]
  (if-not by
    (f)
    (let [result (java.util.concurrent.ArrayBlockingQueue. 1)
          ;; **A daemon thread of our own, not `future`.** Clojure's future pool
          ;; is non-daemon, so a worker still blocked on a socket read keeps the
          ;; whole JVM from exiting -- the same "holding on to something nobody
          ;; is waiting for any more" this code exists to prevent, one level up.
          ;; A process that will not shut down because one source went quiet is
          ;; not an acceptable way to enforce a timeout.
          worker (doto (Thread.
                        ^Runnable (fn []
                                    (.offer result
                                            (try [:ok (f)]
                                                 (catch Throwable t [:error t]))))
                        "atlas-api-attempt")
                   (.setDaemon true)
                   (.start))
          remaining (max 0 (- (long by) (System/currentTimeMillis)))
          r (.poll result remaining java.util.concurrent.TimeUnit/MILLISECONDS)]
      (if (nil? r)
        (do (abort!)
            (.interrupt worker)
            (throw (java.net.SocketTimeoutException. "attempt deadline exceeded")))
        (if (= :error (first r)) (throw (second r)) (second r))))))

(defn- attempt!
  "ONE attempt: execute one HTTP request and return the parsed JSON body
   (keywordized;
   an unparsable body is returned as the raw string). `json-body` non-nil
   sends a JSON-encoded body for POST/PUT/PATCH.

   `by-key?` marks a single-record read anchored on `:input` attributes: for
   those, and only those, a 404 is the answer «no such row» and returns
   `::not-found` instead of failing (see the branch below).

   Non-2xx → spi/fail! :source; timeout/connection exceptions → their
   category; token-endpoint failures → :connection. A 401 carrying an OAuth
   token invalidates + retries ONCE with a fresh token, unless the token is
   too fresh to have expired (see oauth/fresh-token?); the second answer is
   final (api-v1 conformance).

   `ctx` is {:resolver-id .. :source-type ..}; `endpoint-template` is the
   uninterpolated :endpoint — configuration, safe for the trace channel."
  [{:keys [method url headers timeout json-body tls insecure? auth
           ctx endpoint-template tagging raw? max-bytes stream-sample
           by-key? status-map isolatable? attempt total-timeout? with-headers?
           fail-non-2xx? page]
    :or {attempt 1}}]
  (let [t0        (System/nanoTime)
        ;; ${VAR} substitution is the CONNECTOR's job, not the host's — the
        ;; same division connector-sql draws in impl.pool. The url carries
        ;; :base_url (which api-v1 allows to be a ${VAR}) and header values
        ;; carry credential components the contract says are ${VAR}-only.
        sub-url   (interp/substitute url)
        sub-hdrs  (interp/substitute-vals headers)
        missing   (distinct (concat (:missing sub-url) (:missing sub-hdrs)
                                    (oauth/missing-vars auth)))
        _         (when (seq missing)
                    ;; Names, never values (health reports the same way). A
                    ;; request built from a blanked-out var would reach the
                    ;; wrong host or authenticate as nobody, and the failure
                    ;; would surface far from the config that caused it.
                    (spi/fail! :connection ctx
                               (str "unresolved configuration variable(s): "
                                    (str/join ", " missing))))
        url       (:value sub-url)
        headers   (:value sub-hdrs)
        method-up (str/upper-case (name (or method "GET")))
        last-status (atom nil)
        trace!    (fn [ok? & [category]]
                    (spi/trace-statement!
                     (cond-> {:ok? ok?
                      :sql         (str method-up " " endpoint-template
                                        (when-not ok? " [failed]"))
                      :values      []
                      :elapsed-ms  (quot (- (System/nanoTime) t0) 1000000)
                      :resolver-id (:resolver-id ctx)
                      :source-type (:source-type ctx)
                      ;; §107.8: which try this was, so an audit reader can tell
                      ;; a clean call from one that answered on its third.
                      :attempt     attempt}
                       page (assoc :page page)
                       @last-status (assoc :http-status @last-status)
                       category (assoc :error/category category :category category))))
        body-opts (when (and json-body (#{"POST" "PUT" "PATCH"} method-up))
                    {:body         (json/generate-string json-body)
                     :content-type :json})
        ;; 8.4.23 裁定十七: the streaming probe abandons the body mid-flight,
        ;; so it gets a connection of its own to destroy — see
        ;; `conn/oneshot-manager` for why a pooled one is the wrong tool.
        oneshot-mgr (when stream-sample (conn/oneshot-manager tls insecure?))
        ;; D6.3 / §107.7: ONE absolute instant covering connect + first byte +
        ;; body read. `timeout` was already narrowed to min(:timeout_ms,
        ;; remaining budget) by the caller.
        ;;
        ;; **Only under api-v2.** Under api-v1 `:timeout_ms` means what it
        ;; always meant -- clj-http's IDLE timeout -- and nothing here bounds
        ;; the total; the narrowing above still applies, because that is D6.3's
        ;; "an attempt may not outlive the query", which is not a v2 change.
        attempt-by  (when (and timeout total-timeout?)
                      (+ (System/currentTimeMillis) (long timeout)))
        ;; Whatever we are holding open, so both this attempt's own timer and
        ;; the ENGINE's query deadline can let go of it (`spi/abort-all!`).
        in-flight   (atom nil)
        abort!      (fn []
                      (when-let [c @in-flight]
                        (reset! in-flight nil)
                        (try (c) (catch Throwable _ nil))))
        unregister  (spi/register-abort! abort!)
        do-request
        (fn [hdrs]
          (let [opts (cond-> {:socket-timeout timeout
                              :conn-timeout   timeout
                              ;; Bound the wait to LEASE a pooled connection
                              ;; too — clj-http defaults this to INFINITE, so
                              ;; a saturated per-route pool would pin the
                              ;; calling thread forever.
                              :connection-request-timeout timeout
                              :throw-exceptions false
                              :connection-manager (or oneshot-mgr
                                                     (conn/select-manager tls insecure?))}
                       (seq hdrs) (assoc :headers hdrs)
                       ;; The STREAMING probe (8.4.23 裁定十七): the body is
                       ;; walked as it arrives and the connection is dropped at
                       ;; the first complete record, so it must not be slurped
                       ;; into a string first.
                       ;; The normal path streams too (D6.3): a body clj-http has
                       ;; already slurped cannot be given up on, and a handle we
                       ;; hold is the only thing an abort can act on. A mocked
                       ;; response hands back a String and `read-body-by` passes
                       ;; it through untouched.
                       (not raw?) (assoc :as :stream)
                       stream-sample (assoc :as :stream)
                       body-opts  (merge body-opts))]
            (case method-up
              "GET"    (http/get url opts)
              "POST"   (http/post url opts)
              "PUT"    (http/put url opts)
              "PATCH"  (http/patch url opts)
              "DELETE" (http/delete url opts)
              (spi/fail! :source ctx (str "unsupported HTTP :method " method-up)))))]
    (try
      (let [{ahdrs :headers token :token}
            (try (auth-headers auth)
                 (catch clojure.lang.ExceptionInfo e
                   (if (spi/failure? e)
                     (throw e)
                     ;; Token endpoint failed — the source's IdP is part of
                     ;; its connectivity.
                     (spi/fail! :connection ctx "OAuth2 token acquisition failed"))))
            ;; E2b：打标最先铺底，建模者的 `:headers` 与 auth 覆盖它——
            ;; 自报家门绝不覆盖一次调用真正需要的东西。
            resp (within attempt-by abort! #(do-request (merge tagging headers ahdrs)))
            resp (if (and token (= 401 (:status resp)))
                   ;; A token issued seconds ago cannot have expired: this
                   ;; 401 is the upstream refusing THIS request, not a stale
                   ;; token — do not discard a good token other resolvers use.
                   (if (oauth/fresh-token? auth token)
                     resp
                     (do (oauth/invalidate! auth token)
                         (let [token2 (oauth/access-token auth)]
                           ;; The OAuth replay shares the attempt's clock: it is
                           ;; one more request against the SAME budget, not a
                           ;; fresh one (§107.5).
                           (within attempt-by abort!
                                   #(do-request (merge tagging headers
                                                       (oauth/bearer-header auth token2)))))))
                   resp)
            _ (reset! last-status (:status resp))
            ;; From here the body is what is still open. Closing it is what an
            ;; abort DOES on a pooled connection: Apache discards a connection
            ;; whose entity was not consumed, which is exactly the "give the
            ;; pool slot back" D6.3 asks for.
            _ (when (instance? java.io.InputStream (:body resp))
                (reset! in-flight #(release-body! (:body resp))))
            resp (if (or raw? stream-sample)
                   resp
                   (let [read (read-body-by (:body resp) attempt-by abort!)]
                     ;; Fully consumed: close it the ORDINARY way so the pool
                     ;; keeps the connection, and stop offering it to `abort!`.
                     (when (instance? java.io.InputStream (:body resp))
                       (reset! in-flight nil)
                       (try (.close ^java.io.InputStream (:body resp)) (catch Throwable _ nil)))
                     (assoc resp :body read)))]
        (if (and raw? stream-sample)
          ;; **The streaming probe path** (8.4.23 裁定十七). The ceiling bounds
          ;; the bytes READ, not the size of the upstream answer: the sampler
          ;; below stops at the first complete record and this closes the
          ;; response, which drops the connection mid-body. A non-2xx is still
          ;; an ANSWER (裁定七), and still carries no body out of here.
          (let [ok?  (<= 200 (:status resp) 299)
                ^java.io.InputStream body (:body resp)
                ;; Same division as the non-streaming raw branch: a PROBE reads
                ;; a non-2xx as an answer, a bounded COLLECTION read does not --
                ;; and its failures must be raised inside the attempt so the
                ;; guard can retry them (§107.6).
                fail? (and (not ok?) fail-non-2xx?
                           (not (and by-key? (= 404 (:status resp)))))
                category (when fail? (resilience/status-category
                                      (:status resp) status-map {:by-key? by-key?}))]
            (trace! (and ok? (not fail?)) (or category (when-not ok? :source)))
            (when fail?
              (try (conn/shutdown-manager oneshot-mgr) (catch Exception _ nil))
              (try (.close body) (catch Exception _ nil))
              (spi/fail! category ctx (str "HTTP " (:status resp))
                         (when (= :rate_limited category)
                           (when-let [w (resilience/retry-after-ms (:headers resp))]
                             {:retry-after-ms w}))))
            (try
              (if ok?
                (assoc (stream-sample body) :status (:status resp)
                       ;; Paging reads `Link: rel=next` from here (§107.6); the
                       ;; sampler only ever sees the byte stream.
                       :headers (:headers resp))
                (let [n (drain-bytes body max-bytes)]
                  ;; A non-2xx body is not parsed, so what was read IS what is
                  ;; known about its size.
                  {:status (:status resp) :bytes n :bytes-read n}))
              (finally
                ;; Shut the manager down FIRST: closing the body would drain
                ;; what is left of it to make the connection reusable, and
                ;; there is no connection to keep here.
                (try (conn/shutdown-manager oneshot-mgr) (catch Exception _ nil))
                (try (.close body) (catch Exception _ nil)))))
          (if raw?
          ;; **The probe path** (8.4.14 裁定七). It differs from a resolver
          ;; call in exactly two ways, and both are why it cannot reuse the
          ;; branch below: a non-2xx is an ANSWER here (the plan must be able
          ;; to say «that endpoint replied 404»), and the body has a size
          ;; ceiling that must REFUSE rather than truncate — so the bytes are
          ;; counted before anything is parsed, and an oversized body is never
          ;; carried out of this function at all (CLAUDE.md「超限拒绝」).
          (let [body  (str (:body resp))
                bytes* (alength (.getBytes body "UTF-8"))
                ok?   (<= 200 (:status resp) 299)
                ;; **A bounded COLLECTION read is not a probe.** The probe path
                ;; treats a non-2xx as an answer (8.4.14 裁定七: the plan must be
                ;; able to say «that endpoint replied 404»). A collection read
                ;; borrows the same byte-ceiling machinery but is an ordinary
                ;; read, so its failures must be classified HERE, inside the
                ;; attempt -- otherwise the guard never sees them and a page's
                ;; 429 is never retried (§107.6).
                fail? (and (not ok?) fail-non-2xx?
                           (not (and by-key? (= 404 (:status resp)))))
                category (when fail? (resilience/status-category
                                      (:status resp) status-map {:by-key? by-key?}))]
            (trace! (and ok? (not fail?)) (or category (when-not ok? :source)))
            (when fail?
              (spi/fail! category ctx (str "HTTP " (:status resp))
                         (when (= :rate_limited category)
                           (when-let [w (resilience/retry-after-ms (:headers resp))]
                             {:retry-after-ms w}))))
            (cond
              (not ok?)      {:status (:status resp) :bytes bytes*}
              (and max-bytes (> bytes* max-bytes))
              {:status (:status resp) :bytes bytes* :over-limit? true}
              :else
              {:status (:status resp) :bytes bytes*
               :sample (try (json/parse-string body true)
                            (catch Exception _ nil))}))
          (if (<= 200 (:status resp) 299)
          (let [parsed (try (json/parse-string (:body resp) true)
                            (catch Exception _ (:body resp)))]
            (trace! true)
            ;; Paging needs the response HEADERS as well as its body (a
            ;; `Link: rel=next` lives there), and nothing else does -- so the
            ;; envelope is opt-in rather than the default shape every existing
            ;; caller would have to unwrap.
            (if with-headers? {:body parsed :headers (:headers resp)} parsed))
          ;; **doc 107 §107.4 / §107.5: the source decides the CATEGORY.**
            ;; Before this every non-2xx was `:source`, so a 429 and a 500 were
            ;; the same answer and D6.4's "transient only" had nothing to test;
            ;; and which statuses could be isolated was two numbers hardcoded
            ;; from one API. Protected statuses still keep the platform's
            ;; answer -- see `resilience/status-category`.
            (let [category (resilience/status-category (:status resp) status-map
                                                       {:by-key? by-key?})
                  wait     (when (= :rate_limited category)
                             (resilience/retry-after-ms (:headers resp)))]
              (cond
                ;; A missing row is an ANSWER on a by-key read, whatever status
                ;; the upstream spells it with (dialect-v1 §3). The engine turns
                ;; the nil into an absent attribute plus `:engine/not-found`.
                (and by-key? (= :not-found category))
                (do (trace! true) ::not-found)

                ;; On a batch chunk, a per-ROW category is not the chunk's
                ;; failure: it is a claim about whichever input in it is at
                ;; fault, so the caller bisects to find out which.
                (and isolatable? (#{:not-found :input-rejected} category))
                (do (trace! false category)
                    (spi/batch-item-failure ctx (:status resp) category))

                :else
                (do (trace! false category)
                    (spi/fail! category ctx (str "HTTP " (:status resp))
                               (when wait {:retry-after-ms wait})))))))))
      (catch clojure.lang.ExceptionInfo e
        (if (spi/failure? e)
          (throw e)
          (do (trace! false (classify e))
              (spi/fail! (classify e) ctx))))
      (catch Exception e
        (trace! false (classify e))
        (spi/fail! (classify e) ctx))
      (finally
        ;; **Close whatever is still open, on EVERY exit.** Not only when the
        ;; attempt's own timer fired: a `SocketTimeoutException` thrown by the
        ;; read itself also leaves a half-read entity, and Apache keeps that
        ;; connection LEASED until somebody lets go of it. D6.3 asks for the
        ;; slot back, and "we timed out" is precisely the case where it would
        ;; otherwise be held longest. Idempotent -- `abort!` clears the handle.
        (abort!)
        ;; The attempt is over either way: stop offering the engine's deadline
        ;; a handle to something already finished.
        (unregister)
        ;; Idempotent: the streaming branch already shut it down on the happy
        ;; path; this is the leak-free exit for a request that never got a
        ;; body (transport failure, unsupported method).
        (when oneshot-mgr
          (try (conn/shutdown-manager oneshot-mgr) (catch Exception _ nil)))))))

(defn execute!
  "Execute one HTTP request under the source's effective resilience policy.

   Every request the connector makes goes through here, so the protections are
   not something a call site can forget:

   - the **breaker** for this source identity (D6.5) refuses without sending
     anything while it is open (`:circuit_open`);
   - a **bulkhead permit** bounds how many calls this source carries at once;
     none within `:max_wait_ms` is `:bulkhead_rejected`;
   - a failure in the effective `:retry_on` is retried at most `:max_retries`
     times with exponential backoff + jitter, honouring an upstream
     `Retry-After` (D6.4). Reads are idempotent by default; `:idempotent?
     false` (mutations) never retries.

   `:policy` may be omitted -- a caller with no policy in hand gets the
   platform default, which retries nothing. That is the safe default: a retry
   nobody asked for turns one upstream call into several."
  [{:keys [policy fingerprint ctx idempotent?] :as request}]
  (let [policy (or policy spi/platform-default-policy)
        ;; The caller passes the source's fingerprint; a call made without a
        ;; source entry (health check, probe) falls back to the ORIGIN. Never
        ;; the request URL -- see `resilience/origin-fingerprint`.
        fp     (or fingerprint (resilience/origin-fingerprint (:url request)))
        budget (or (:timeout request) (:timeout_ms policy))]
    (resilience/call!
     {:fingerprint fp :policy policy :ctx ctx
      :idempotent? (if (some? idempotent?) idempotent? true)
      ;; D6.3: the guard refuses to start an attempt once this has passed, and
      ;; will not sleep a backoff past it either.
      :deadline-at (spi/deadline-at)}
     (fn [n]
       (attempt! (assoc request :attempt n
                        ;; **Narrowed per attempt, not once per call** (D6.3):
                        ;; a retry three seconds in gets what is left, not
                        ;; another full `:timeout_ms`. This applies to every
                        ;; graph -- it is the query's budget, not v2 semantics.
                        :timeout (spi/attempt-timeout-ms budget)
                        :total-timeout? (:attempt-total-timeout? policy)))))))

(defn execute-bounded!
  "A semantic snapshot reads at most cap+1 bytes, rejects excess and closes the
   stream. It never returns a truncated JSON document or silently drops rows."
  [request {:keys [max-response-bytes timeout-ms]}]
  (let [r (execute! (assoc request :raw? true :max-bytes max-response-bytes
                          ;; Classified inside the attempt so the guard can
                          ;; retry it (§107.6: a page's 429 retries that page).
                          :fail-non-2xx? true
                          :timeout (min (or (:timeout request) default-timeout-ms) timeout-ms)
                          :stream-sample
                          (fn [^java.io.InputStream input]
                            (let [bytes (.readNBytes input (inc max-response-bytes))]
                              (when (> (alength bytes) max-response-bytes)
                                (spi/fail! :source (:ctx request) "Source collection response exceeded its byte budget"))
                              {:sample (when (pos? (alength bytes))
                                         (json/parse-string (String. bytes "UTF-8") true))}))))]
    (cond
      (<= 200 (:status r) 299)
      ;; A paged read asked for the envelope: it needs the headers as well as
      ;; the sampled body, and the byte ceiling still applies to every page.
      (if (:with-headers? request)
        {:body (:sample r) :headers (:headers r)}
        (:sample r))
      (and (= 404 (:status r)) (:by-key? request)) ::not-found
      ;; §107.4: a bounded collection read gets the same classification as any
      ;; other read -- a throttled snapshot is `:rate_limited`, not `:source`.
      :else (spi/fail! (resilience/status-category (:status r) (:status-map request)
                                                   {:by-key? (:by-key? request)})
                       (:ctx request) "Source collection request failed"))))
