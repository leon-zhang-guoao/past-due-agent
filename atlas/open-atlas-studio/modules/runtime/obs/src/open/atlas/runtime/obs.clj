(ns open.atlas.runtime.obs
  "One instance's observability mechanics: rolling JSON-lines files, bounded
   in-memory rings, the async shipper, request correlation, in-process metrics,
   the Prometheus exposition and OTLP span export.

   These live in `runtime` and not in `common` because every one of them owns
   LOCAL state — files and their rolled generations, rings, daemon threads, an
   instance directory (D1.13). `common` is the factory kernel: pure decisions,
   no disk.

   This is the module's only public namespace (CLAUDE.md); `impl.*` is private.
   Names are prefixed by the mechanism they belong to (`ring-`, `ship-`,
   `metrics-`, `otlp-`) because a flat surface without them would collide —
   `ring/make` and `metrics/snapshot` were distinct only by their namespace.

   Audit records carry names, counts, timings and statuses without entity
   field values (audit-event-v1 §5). Separately enabled query diagnostics may
   include EQL input values; credentials and query results are excluded."
  (:require [open.atlas.runtime.obs.impl.audit :as audit]
            [open.atlas.runtime.obs.impl.jsonl :as jsonl]
            [open.atlas.runtime.obs.impl.log :as log]
            [open.atlas.runtime.obs.impl.metrics :as metrics]
            [open.atlas.runtime.obs.impl.otlp :as otlp]
            [open.atlas.runtime.obs.impl.prometheus :as prometheus]
            [open.atlas.runtime.obs.impl.ratelimit :as ratelimit]
            [open.atlas.runtime.obs.impl.ring :as ring]
            [open.atlas.runtime.obs.impl.ship :as ship]
            [open.atlas.runtime.obs.impl.subject :as subject]
            [open.atlas.runtime.obs.impl.trace :as trace]
            [open.atlas.runtime.obs.impl.webhook :as webhook]))

;; ── rolling JSON-lines files ────────────────────────────────────────────────
;; append-line! SIGNALS success rather than throwing: a caller on the audit path
;; has to be able to surface a persistent write loss (audit-event-v1 §6).
(def jsonl-append-line!        jsonl/append-line!)
(def jsonl-default-generations jsonl/default-generations)

;; ── bounded ring of recent events ───────────────────────────────────────────
(def ring-make   ring/make)
(def ring-push!  ring/push!)
(def ring-recent ring/recent)
(def ring-clear! ring/clear!)

;; ── async shipper (at-most-once, drop on overflow) ──────────────────────────
(def ship-make-shipper ship/make-shipper)
(def ship-enqueue!     ship/enqueue!)
(def ship-drain-now!   ship/drain-now!)
(def ship-flush-all!   ship/flush-all!)

;; ── request correlation ─────────────────────────────────────────────────────
;; *request-id* is NOT re-exported as a var: aliasing a dynamic var with `def`
;; makes a SECOND var, and a caller's `binding` on the alias would leave the
;; reader inside impl looking at the original. Callers get a reader and the two
;; binding macros instead, which expand to a binding of the one real var.
(defn request-id
  "The current request id, or nil outside a correlated request."
  []
  trace/*request-id*)

(defmacro with-request-id
  "Run `body` correlated to `rid`."
  [rid & body]
  `(binding [trace/*request-id* ~rid] ~@body))

(defmacro with-request-id-from-env
  "Run a resolver `body` correlated to the :obs/request-id carried on `env`, so
   parallel Pathom threads inherit the id of the request that spawned them."
  [env & body]
  `(trace/with-request-id ~env ~@body))

(def gen-request-id              trace/gen-request-id)
(def wrap-request-id             trace/wrap-request-id)
(def extract-upstream-request-id trace/extract-upstream-request-id)
(def register-call-trace!        trace/register-call-trace!)
(def snapshot-call-trace         trace/snapshot-call-trace)
(def clear-call-trace!           trace/clear-call-trace!)
(def record-resolver-call!       trace/record-resolver-call!)
(def summarize-run-stats         trace/summarize-run-stats)
(def summarize-trace             trace/summarize-trace)
(def eql-shape                   trace/eql-shape)

;; ── in-process metrics (reset on restart, by design) ────────────────────────
(def metrics-reset-all!          metrics/reset-all!)
(def metrics-uptime-ms           metrics/uptime-ms)
(def metrics-record-query!       metrics/record-query!)
(def metrics-record-rejection!   metrics/record-rejection!)
(def metrics-record-agent-run!   metrics/record-agent-run!)
(def metrics-rejections          metrics/rejections)
(def metrics-agent-stats         metrics/agent-stats)
(def metrics-latency-stats       metrics/latency-stats)
(def metrics-counter-snapshot    metrics/counter-snapshot)
(def metrics-snapshot            metrics/snapshot)
(def metrics-recent-queries      metrics/recent-queries)
(def metrics-series              metrics/series)
(def metrics-query-by-request-id metrics/query-by-request-id)

;; ── Prometheus exposition ───────────────────────────────────────────────────
(def prometheus-render  prometheus/render)
(def prometheus-handler prometheus/handler)

;; ── OTLP trace export ───────────────────────────────────────────────────────
;; otlp-configure! takes a PROVIDER FN, not a config map: the endpoint and
;; headers are read per call so a reloaded config takes effect without a
;; restart — the behaviour the app's own config lookup had before this module
;; owned the code.
(def otlp-configure!  otlp/configure!)
(def otlp-endpoint    otlp/endpoint)
(def otlp-span        otlp/span)
(def otlp-envelope    otlp/envelope)
(def otlp-shipper     otlp/shipper)
(def otlp-send-batch! otlp/send-batch!)
(def otlp-offer-query! otlp/offer-query!)
(def otlp-offer-agent! otlp/offer-agent!)

;; ── per-principal rate limiting ─────────────────────────────────────────────
(def rate-configure! ratelimit/configure!)
(def rate-limit      ratelimit/limit)
(def rate-allow?     ratelimit/allow?)

;; ── structured logging (console + app.jsonl) ────────────────────────────────
(def log-configure! log/configure!)
(def log-event!     log/log-event!)

;; ── audit trail ─────────────────────────────────────────────────────────────
;; audit-configure! takes :dir — the anchor a relative :path resolves against.
;; The module does not read the app's config and does not open an instance:
;; whoever owns the instance root hands it over (8.10 P2).
(def audit-configure!        audit/configure!)
(def audit-record!           audit/record!)
(def audit-access-event      audit/access-event)
(def audit-recent            audit/recent)
(def audit-clear!            audit/clear!)
(def audit-write-failures    audit/write-failures)
(def audit-prime-from-file!  audit/prime-from-file!)
(def audit-query!            audit/query!)
(def audit-mutation!         audit/mutation!)
(def audit-tool!             audit/tool!)
(def audit-rate-limited!     audit/rate-limited!)
(def audit-auth-failed!      audit/auth-failed!)
(def audit-entity-namespaces audit/entity-namespaces)

;; ── audit webhook (SIEM / log pipeline) ─────────────────────────────────────
(def webhook-configure! webhook/configure!)
(def webhook-url        webhook/url)
(def webhook-offer!     webhook/offer!)

;; ── subject-keyed audit index ───────────────────────────────────────────────
;; The stream answers "what happened"; this answers "what happened to THIS
;; token / graph / person" and survives rotation. `dir` is passed per call, not
;; configured once: one process can hold more than one instance (tests always
;; do), and a global anchor would file one instance's events under another's.
(def audit-index-append!  subject/append!)
(def audit-for-subject    subject/for-subject)
(def audit-for-principal  subject/for-principal)
(def audit-principal-key  subject/principal-key)

(def query-details log/query-details)
(def ^:dynamic *query-source* nil)
