(ns open.atlas.runtime.obs.impl.trace
  "Request correlation (request-id middleware) and per-resolver trace
   extraction from Pathom3 run-stats. Extraction is fail-open: it returns
   nil on any unexpected shape and never throws."
  (:import [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(def ^:dynamic *request-id*
  "Bound by wrap-request-id for the duration of each HTTP request."
  nil)

(def ^:dynamic *call-trace*
  "Bound for the duration of a Pathom query on the request thread; parallel
   resolver threads use the request-id registry (call-trace-for) instead."
  nil)

(defonce ^:private ^ConcurrentHashMap call-traces-by-request-id
  (ConcurrentHashMap.))

(defn register-call-trace!
  "Register (or replace) the in-flight HTTP trace atom for a request-id."
  [request-id]
  (when request-id
    (let [a (atom [])]
      (.put call-traces-by-request-id request-id a)
      a)))

(defn call-trace-for [request-id]
  (when request-id (.get call-traces-by-request-id request-id)))

(defn snapshot-call-trace
  "Copy recorded HTTP calls for request-id (empty when unknown)."
  [request-id]
  (if-let [a (call-trace-for request-id)]
    (vec @a)
    (when *call-trace* (vec @*call-trace*))))

(defn clear-call-trace! [request-id]
  (when request-id (.remove call-traces-by-request-id request-id))
  nil)

;; 8 hex chars = 32 bits of entropy — fine for correlating a 100-entry
;; in-memory buffer; not globally unique.
(defn gen-request-id []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn wrap-request-id
  "Ring middleware: assigns a request-id (honoring an incoming X-Request-Id
   header when present), exposes it as :obs/request-id on the request, binds
   *request-id*, and adds an X-Request-Id response header."
  [handler]
  (fn [req]
    (let [rid (or (get-in req [:headers "x-request-id"])
                  (gen-request-id))]
      (binding [*request-id* rid]
        (when-let [resp (handler (assoc req :obs/request-id rid))]
          (assoc-in resp [:headers "X-Request-Id"] rid))))))

;; Fully-qualified Pathom keys (avoids requiring Pathom nses here)
(def ^:private run-stats-key  :com.wsscode.pathom3.connect.runner/run-stats)
(def ^:private nodes-key      :com.wsscode.pathom3.connect.planner/nodes)
(def ^:private node-stats-key :com.wsscode.pathom3.connect.runner/node-run-stats)
(def ^:private op-name-key    :com.wsscode.pathom3.connect.operation/op-name)
(def ^:private node-error-key :com.wsscode.pathom3.connect.runner/node-error)
(def ^:private rs-start-key   :com.wsscode.pathom3.connect.runner/resolver-run-start-ms)
(def ^:private rs-finish-key  :com.wsscode.pathom3.connect.runner/resolver-run-finish-ms)
(def ^:private nr-start-key   :com.wsscode.pathom3.connect.runner/node-run-start-ms)
(def ^:private nr-finish-key  :com.wsscode.pathom3.connect.runner/node-run-finish-ms)

(defn- maybe-deref [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn- eql-keywords
  "All keywords in an EQL fragment (join keys, attrs, param keys), depth-first."
  [x]
  (cond
    (keyword? x)    [x]
    (map? x)        (mapcat (fn [[k v]] (concat (eql-keywords k) (eql-keywords v))) x)
    (sequential? x) (mapcat eql-keywords x)
    :else           []))

(defn- collect-run-stats-maps
  "Gather every run-stats map attached to meta anywhere in a result tree —
   collection rows often carry stats the top-level map lacks (batch)."
  [x]
  (lazy-seq
    (cond
      (map? x) (let [own (when-let [m (get (meta x) run-stats-key)] [m])]
                 (concat own (mapcat collect-run-stats-maps (vals x))))
      (sequential? x) (mapcat collect-run-stats-maps x)
      :else [])))

(defn- merge-run-stats [stats-maps]
  (when (seq stats-maps)
    (let [nodes      (mapv #(get % nodes-key {}) stats-maps)
          node-stats (reduce into {} (map #(maybe-deref (get % node-stats-key {})) stats-maps))]
      {nodes-key (apply merge nodes)
       node-stats-key node-stats})))

(defn extract-upstream-request-id
  "Vendor requestId from a parsed JSON body (e.g. EC private API). Never throws."
  [body]
  (try
    (when (map? body)
      (or (:requestId body) (:request-id body)
          (get body "requestId") (get body "request-id")))
    (catch Exception _ nil)))

(defn record-resolver-call!
  "Append one upstream resolver/HTTP call to the in-flight trace. Uses the
   request-id registry so parallel Pathom resolver threads can record without
   inheriting dynamic vars. Fail-open."
  [{:keys [resolver ms ok? upstream-request-id endpoint]}]
  (try
    (when-let [trace-atom (or (when *request-id* (call-trace-for *request-id*))
                              *call-trace*)]
      (swap! trace-atom conj
             (cond-> {:resolver (str resolver)
                      :ms       (long ms)
                      :ok?      (boolean ok?)}
               upstream-request-id (assoc :upstream-request-id (str upstream-request-id))
               endpoint            (assoc :endpoint (str endpoint)))))
    (catch Exception _ nil))
  nil)

(defmacro with-request-id
  "Wrap a resolver body so parallel Pathom threads inherit :obs/request-id from env,
   letting record-resolver-call! find the right per-request trace atom."
  [env & body]
  `(binding [*request-id* (:obs/request-id ~env)]
     ~@body))

(defn- resolver-label [r]
  (cond
    (symbol? r)  (name r)
    (keyword? r) (name r)
    :else        (str r)))

(defn summarize-run-stats
  "Extract a compact per-resolver summary from a Pathom result's metadata:
     {:resolvers [{:resolver sym :ms long-or-nil :start long-or-nil :ok? bool} ...]
      :resolver-count n
      :span-ms long-or-nil   ; wall-clock of the whole resolver phase
      :slowest sym}
   :resolvers is in EXECUTION ORDER (by start time; untimed resolvers last), and
   each :start is an offset in ms from the first resolver's start — so a caller can
   render both the call SEQUENCE and a timeline (parallel resolvers overlap).
   :span-ms is max(finish) - min(start) across timed resolvers. :slowest is the
   resolver with the largest :ms. Walks the result tree for nested run-stats
   (collection rows often carry stats the top-level map lacks). Returns nil when
   stats are absent or unreadable. Never throws."
  [result]
  (try
    (let [stats (or (some-> result meta run-stats-key)
                    (merge-run-stats (vec (distinct (collect-run-stats-maps result)))))]
      (when (map? stats)
        (let [nodes      (get stats nodes-key {})
              node-stats (maybe-deref (get stats node-stats-key {}))
              raw
              (->> node-stats
                   (keep (fn [[node-id ns-map]]
                           (when-let [op (get-in nodes [node-id op-name-key])]
                             (let [start  (or (get ns-map rs-start-key) (get ns-map nr-start-key))
                                   finish (or (get ns-map rs-finish-key) (get ns-map nr-finish-key))]
                               {:resolver op
                                :raw-start start
                                :ms  (when (and start finish) (long (- finish start)))
                                :ok? (not (contains? ns-map node-error-key))}))))
                   vec)]
          (when (seq raw)
            (let [t0        (some->> (keep :raw-start raw) seq (apply min))
                  max-fin   (some->> (keep (fn [{:keys [raw-start ms]}]
                                             (when (and raw-start ms) (+ raw-start ms)))
                                           raw)
                                     seq (apply max))
                  span      (when (and t0 max-fin) (long (- max-fin t0)))
                  resolvers (->> raw
                                 ;; execution order: by start; untimed resolvers last
                                 (sort-by #(or (:raw-start %) Long/MAX_VALUE))
                                 (mapv (fn [{:keys [resolver raw-start ms ok?]}]
                                         (cond-> {:resolver resolver :ms ms :ok? ok?}
                                           (and raw-start t0) (assoc :start (long (- raw-start t0)))))))]
              {:resolvers      resolvers
               :resolver-count (count resolvers)
               :span-ms        span
               :slowest        (->> resolvers (filter :ms) (sort-by :ms >) first :resolver)})))))
    (catch Exception _ nil)))

(defn summarize-trace
  "The per-resolver summary for an Activity entry, merging the HTTP call trace
   (API plugin records each upstream call + its vendor requestId) with Pathom
   run-stats. When NO HTTP calls were recorded (in-process / SQL queries), returns
   the run-stats summary unchanged — keeping its execution-order timeline
   (:start/:span-ms). When HTTP calls exist (API / batch, where run-stats is often
   one node), they lead and surface :upstream-request-id; run-stats resolvers not
   already covered by name are folded in as supplemental rows. Pass http-calls
   explicitly to avoid relying on the dynamic var after process-eql returns."
  ([result] (summarize-trace result nil))
  ([result http-calls]
   (try
     (let [http   (vec (or http-calls (snapshot-call-trace *request-id*) []))
           pathom (summarize-run-stats result)]
       (if (empty? http)
         ;; in-process / SQL: the run-stats summary unchanged (keeps :start/:span-ms gantt)
         pathom
         ;; API / batch: MERGE per resolver name into tiers — run-stats gives the
         ;; resolver TOTAL (api + adapter), the HTTP call gives the upstream API
         ;; roundtrip (:api-ms) + vendor :upstream-request-id; adapter = total − api.
         (let [http-by (reduce (fn [m c] (assoc m (:resolver c) c)) {} http)
               merged  (mapv (fn [r]
                               (let [nm    (resolver-label (:resolver r))
                                     h     (get http-by nm)
                                     total (:ms r)
                                     api   (:ms h)]
                                 (cond-> {:resolver nm :ms total :ok? (and (get r :ok? true) (get h :ok? true))}
                                   (:start r)                     (assoc :start (:start r))
                                   api                            (assoc :api-ms api)
                                   (and total api (>= total api)) (assoc :adapter-ms (- total api))
                                   (:upstream-request-id h)       (assoc :upstream-request-id (:upstream-request-id h))
                                   (:endpoint h)                  (assoc :endpoint (:endpoint h)))))
                             (:resolvers pathom))
               seen    (set (map #(resolver-label (:resolver %)) (:resolvers pathom)))
               ;; HTTP calls with no matching run-stats node (batch — run-stats is
               ;; often one node for many upstream calls): surface them on their own.
               http-only (->> http
                              (remove #(seen (:resolver %)))
                              (mapv (fn [c] (cond-> {:resolver (:resolver c) :ms (:ms c)
                                                     :api-ms (:ms c) :ok? (get c :ok? true)}
                                              (:upstream-request-id c) (assoc :upstream-request-id (:upstream-request-id c))
                                              (:endpoint c)            (assoc :endpoint (:endpoint c))))))
               resolvers (vec (concat merged http-only))]
           (when (seq resolvers)
             {:resolvers      resolvers
              :resolver-count (count resolvers)
              :span-ms        (:span-ms pathom)
              :slowest        (->> resolvers (filter #(number? (:ms %))) (sort-by :ms >) first :resolver)}))))
     (catch Exception _ nil))))

(defn- query-key [item]
  (cond
    (keyword? item) item
    (map? item)     (let [k (first (keys item))]
                      (if (seq? k) (first k) k))
    (seq? item)     (query-key (first item))
    (vector? item)  (first item)   ; EQL ident [:attr val] → :attr
    :else           :unknown))

(defn eql-shape
  "Loggable shape of a query: entity KEY NAMES, top-level query keys, and the set
   of all attribute namespaces touched (top-level + nested joins) as
   :entity-namespaces. Never includes values — safe to log per AGENTS.md §5.
   Tolerates non-map entity and non-sequential eql."
  [entity eql]
  (let [entity-keys (vec (keys (if (map? entity) entity {})))
        query-keys  (mapv query-key (if (sequential? eql) eql []))
        all-kws     (eql-keywords (if (sequential? eql) eql []))]
    {:entity-keys       entity-keys
     :query-keys        query-keys
     :entity-namespaces (->> (concat entity-keys all-kws) (keep namespace) distinct sort vec)}))
