(ns open.atlas.runtime.obs.impl.audit
  "Access-event log. Every data access emits ONE structured event to a standard
   JSON-lines file (logs/access.jsonl, for external analysis) AND a bounded
   in-memory ring (the realtime GUI's only source).

   POLICY (AGENTS.md §5): names/counts/timings/statuses only — never the returned
   data values. The query is logged as :eql-shape (key NAMES only), never the raw
   :eql vector."
  (:require [open.atlas.runtime.obs.impl.retention :as retention]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [open.atlas.runtime.obs.impl.jsonl :as jsonl]
            [open.atlas.runtime.obs.impl.metrics :as metrics]
            [open.atlas.runtime.obs.impl.ring :as ring]
            [open.atlas.runtime.obs.impl.subject :as subject]
            [open.atlas.runtime.obs.impl.trace :as trace]
            [open.atlas.runtime.obs.impl.webhook :as webhook]))

;; :dir is the anchor a RELATIVE :path resolves against — the instance (or
;; workspace) root, handed in by whoever owns it. This module does not read the
;; app's config and does not open an instance: two apps embedding it must not
;; each grow their own copy of "where does this install keep its files".
;; :instance-id is stamped on every event (audit-event-v1 §2.1): with two
;; deployment forms running the same code, «which machine» is not recoverable
;; from the record itself unless it is written down.
(defonce ^:private cfg (atom {:path "logs/access.jsonl" :dir nil :instance-id nil
                              :max-bytes (* 10 1024 1024)
                              :generations jsonl/default-generations}))
(defonce ^:private ring* (atom (ring/make 500)))

;; The in-memory ring is fail-open BY DESIGN (a file-write failure must not empty
;; the Activity view — see record!), but that also means a full disk / permission
;; error silently drops every persisted access record while the GUI looks healthy.
;; These make the loss observable: a running count for the admin/obs snapshot, plus
;; a one-shot warn so the operator log gets exactly one line (not one per request).
(defonce ^:private write-failures* (atom 0))
(defonce ^:private write-failure-warned* (atom false))

(defn write-failures
  "Count of audit-JSONL append failures since boot (or last configure!). The
   request path stays fail-open, so this counter is the ONLY operator signal that
   the persisted access trail is being lost — surface it in the admin/obs
   snapshot. See record!."
  []
  @write-failures*)

(defn- note-write-failure!
  "Record one lost persisted access event: bump the counter and, on the FIRST
   failure only, emit a single warn (later failures are counted, not re-logged)."
  []
  (swap! write-failures* inc)
  (when (compare-and-set! write-failure-warned* false true)
    (log/warn "audit: access-event persistence FAILED (disk full / permissions?) —"
              "the in-memory Activity ring is still live, but the JSONL audit trail is"
              "losing records. Later failures are counted in (audit/write-failures),"
              "not re-logged.")))

(defn configure!
  "opts: {:recent ring-cap :path file :dir anchor-for-a-relative-path
          :instance-id this-deployment :max-bytes n
          :generations rolled-file-count}."
  [{:keys [recent path dir instance-id max-bytes generations]}]
  (when recent (reset! ring* (ring/make recent))) ; replaces the ring; a concurrent push! may land in the old (discarded) ring
  (when path (swap! cfg assoc :path path))
  (when dir (swap! cfg assoc :dir (str dir)))
  (when instance-id (swap! cfg assoc :instance-id (str instance-id)))
  (when max-bytes (swap! cfg assoc :max-bytes max-bytes))
  (when generations (swap! cfg assoc :generations generations))
  ;; a (re)configure resets the loss signal — a fresh path may well be writable
  (reset! write-failures* 0)
  (reset! write-failure-warned* false)
  nil)

(defn- ns-of [x] (when (qualified-keyword? x) (namespace x)))

(defn- attr-str [x]
  (if (keyword? x)
    (subs (str x) 1)          ; :vendor/cost → "vendor/cost"
    (str x)))

(defn entity-namespaces
  "Distinct entity-namespace strings from an eql-shape's keys."
  [{:keys [entity-keys query-keys]}]
  (->> (concat entity-keys query-keys) (keep ns-of) distinct vec))

(defn- log-path*
  "Resolve the JSONL path. A relative default (logs/access.jsonl) is anchored to
   the configured :dir — the packaged app's process cwd is a read-only system dir,
   so a relative path silently fails there (and, since the file write fed the
   in-memory ring, used to take the realtime GUI down with it; see record!).
   With no :dir configured the path stays relative to cwd, which is what a test
   or a REPL wants."
  [dir]
  (let [p (:path @cfg)]
    (if (.isAbsolute (io/file p))
      p
      (str (if dir (io/file dir p) (io/file p))))))

(defn- log-path [] (log-path* (:dir @cfg)))

(defn- event-data
  "Audit resolver names are data, not executable symbols. Generated Pathom
   symbols can contain a qualified id in their name, which prints as invalid
   EDN. Normalize recursively for both live responses and durable records."
  [entry]
  (walk/postwalk #(if (symbol? %) (str %) %) entry))

(defn record!
  "Stamp :request-id + :ts when absent, push to the in-memory ring, then append a JSON
   line. The ring is the realtime GUI's source, so it goes FIRST and independently of
   the file — a file-write failure must not empty the Activity view. Fully fail-open —
   never throws to the caller (this is on the live request path).

   :resolver-trace (per-resolver names/timings — §5-compliant) rides along
   everywhere: ring, subject index AND the JSONL line. The ring is a convenience
   cache of the recent ones, not a decision about what survives — owner ruling
   2026-08-31: the file is the complete record. It costs a longer line and buys
   a detail panel that still opens after a restart.

   The 2-arity takes an explicit `dir` anchor. One process can hold more than
   one instance (every store test does), and an instance's audit belongs to
   THAT instance's directory — a single configured anchor would file one
   instance's events under another's."
  ([entry] (record! nil entry))
  ([dir entry]
  (let [e (cond-> (event-data entry)
            (nil? (:request-id entry))  (assoc :request-id trace/*request-id*)
            ;; :at, not :ts — audit-event-v1 §2.1 names it, and one clock field
            ;; with two names is how two readers disagree about which is real.
            (nil? (:at entry))          (assoc :at (str (java.time.Instant/now)))
            (nil? (:instance-id entry)) (assoc :instance-id (:instance-id @cfg)))]
    (try (ring/push! @ring* e) (catch Exception _ nil))
    ;; The ring rolls and the stream rolls; the subject index does not. An
    ;; event nobody can find six months from now was not really audited
    ;; (audit-event-v1 §6).
    ;; **盘上是完整的**（owner 裁定 2026-08-31）：内存环只是「最近一批、方便翻」
    ;; 的缓存，不是哪条记录活得下来的判据。时间线是名称与耗时，不含值,
    ;; 所以它和别的字段一样落盘——重启之后详情面板照样打得开。
    (try (subject/append! (or dir (:dir @cfg)) e) (catch Exception _ nil))
    (try (let [line (json/generate-string e)]
           (if (jsonl/append-line! (log-path* (or dir (:dir @cfg))) (:max-bytes @cfg) line (:generations @cfg))
             ;; same values-free line, optionally shipped to a SIEM/collector
             ;; (:server :audit-webhook-url) — async, fail-open, no-op when unset
             (webhook/offer! line)
             ;; the append swallowed a failure — surface it instead of losing it silently
             (note-write-failure!)))
         ;; serialization (or webhook offer) itself throwing is also a lost record
         (catch Exception _ (note-write-failure!))))
  nil))

(defn recent
  "Recent events, newest first (up to the ring cap)."
  ([] (mapv event-data (ring/recent @ring*)))
  ([n] (mapv event-data (ring/recent @ring* n))))

(defn clear! [] (ring/clear! @ring*) nil)

(defn prime-from-file!
  "Load up to `n` most-recent events from the audit JSONL back into the ring, so
   a freshly-started process shows the operations timeline from BEFORE it
   restarted instead of an empty ring. Reads the current file's tail
   and retained compressed generations. Fail-open: any trouble
   leaves the ring untouched. Events keep their persisted :ts; their values are
   the JSON-parsed forms (op names come back as strings, exactly what the JSON
   API re-emits anyway). Pushes oldest→newest so `recent` stays newest-first."
  ([] (prime-from-file! 200))
  ([n]
   (try
     (let [f (io/file (log-path))]
       (when (seq (retention/retained-files f))
         (let [tail (retention/reduce-lines f (fn [xs line] (if (str/blank? line) xs (vec (take-last n (conj xs line))))) [])]
           (doseq [line tail]
             ;; `:obs/restored` 是**读回来时盖的章**，不是被审计的事实——它带
             ;; 命名空间正是为了这一点：契约的键面（§2.1）管的是写下去的东西。
             ;; 有了它，读的人才分得清「这条没有时间线」是「它本来就没有」还是
             ;; 「时间线只在内存环、重启时留不下」——列两种可能等于没说。
             (try (ring/push! @ring* (assoc (json/parse-string line true)
                                            :obs/restored true))
                  (catch Exception _ nil))))))
     (catch Exception e
       (log/debug "audit/prime-from-file! skipped:" (.getMessage e))))
   nil))

;; ---- convenience recorders (build the event + record!) ----

(defn- actor-of
  "audit-event-v1 §4: the two-part principal chain, plus :delegated-by when a
   third party is involved. :service is never empty — an access with no service
   identity should not have reached an audit point. :asserted-by says which half
   was actually checked HERE: writing :verified where only an assertion happened
   is the one thing 50-security forbids."
  [user-context]
  (let [caller (when-let [id (:user/id user-context)]
                 ;; :issuer is HOW they authenticated. Absent means unknown, and
                 ;; an explicit nil issuer would read as «we checked and it was
                 ;; nothing» — so the key is left out instead.
                 (cond-> {:subject id}
                   (:auth/type user-context) (assoc :issuer (name (:auth/type user-context)))))
        obo    (:auth/on-behalf-of user-context)]
    (cond-> {:service {:app "atlas-server"}
             :asserted-by :verified}
      ;; With delegation the person the work is FOR is the subject; who pressed
      ;; the button is kept separately rather than overwritten — an audit that
      ;; answers only one of those answers the wrong question half the time.
      obo         (assoc :on-behalf-of {:subject obo} :delegated-by caller)
      (and caller (not obo)) (assoc :on-behalf-of caller))))

(defn- outcome-of
  "The old :status was one field doing two jobs. audit-event-v1 splits it:
   :outcome is what happened, :reason is why it was refused."
  [status]
  (case status
    (:ok nil)      [:ok nil]
    :error         [:error nil]
    :rate-limited  [:denied :rate-limit/exceeded]
    :unauthorized  [:denied :auth/failed]
    [:error nil]))

(defn access-event
  "The common half of every :access/* event. Public because an app that emits
   its own access-family event must not re-derive :outcome from :status — two
   translations of one status is two answers."
  [event {:keys [source user-context status latency-ms remote-addr error]}]
  (let [[outcome reason] (outcome-of status)]
    (cond-> {:v 1
             :event   event
             :entry   source
             :outcome outcome
             :actor   (actor-of user-context)}
      reason      (assoc :reason reason)
      latency-ms  (assoc :latency-ms latency-ms)
      remote-addr (assoc :remote-addr remote-addr)
      error       (assoc :error (str error)))))

(defn query!
  "Emit ONE access event. The logged query summary is the names-only `eql-shape`
   (entity/query KEY NAMES, never values nor the raw :eql vector) so external log
   tools get a stable, values-free hand-off contract (audit-event-v1 §5).

   :resolver-trace (optional) is the per-resolver names/timings summary
   (trace/summarize-run-stats) — names/counts/timings only, so §5-compliant. It
   rides along on the in-memory ring entry (the Activity detail panel reads it
   directly), but is stripped from the external JSONL line by `record!`."
  [{:keys [eql-shape attrs-filtered resolver-trace assistant] :as opts}]
  (record! (cond-> (assoc (access-event :access/query opts)
                          :entities (entity-namespaces eql-shape)
                          :attrs-filtered (mapv attr-str (or attrs-filtered [])))
             ;; which assistant ran this chat/board turn (id only) — powers usage counts
             assistant      (assoc :assistant assistant)
             eql-shape      (assoc :eql-shape eql-shape)
             resolver-trace (assoc :resolver-trace resolver-trace))))

(defn mutation!
  [{:keys [mutation attrs-filtered] :as opts}]
  (record! (cond-> (assoc (access-event :access/mutation opts)
                          :attrs-filtered (mapv attr-str (or attrs-filtered [])))
             mutation (assoc :mutation (str mutation)))))

(defn tool!
  "Emit ONE access event for a non-EQL data tool (analyze_result / execute_code —
   the spill readers). Logged: tool name, spill BASENAMES, status/latency — never
   the SQL/code text nor any returned cells (audit-event-v1 §5)."
  [{:keys [tool files] :as opts}]
  (record! (cond-> (assoc (access-event :access/tool opts) :tool tool)
             (seq files) (assoc :files (mapv str files)))))

(defn rate-limited!
  "A refusal at the door. Not its own event name: audit-event-v1 §3 keeps
   refusals as an :outcome so «what did this person try» and «what did they
   reach» answer in one query instead of two."
  [{:keys [source user-context remote-addr]}]
  (metrics/record-rejection! :rate-limited)
  (record! (access-event :access/request {:source source :user-context user-context
                                          :status :rate-limited :remote-addr remote-addr})))

(defn auth-failed!
  [{:keys [source remote-addr]}]
  (metrics/record-rejection! :unauthorized)
  (record! (access-event :access/request {:source source :status :unauthorized
                                          :remote-addr remote-addr})))
