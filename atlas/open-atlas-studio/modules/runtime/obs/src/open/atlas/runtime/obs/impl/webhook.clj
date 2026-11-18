(ns open.atlas.runtime.obs.impl.webhook
  "Audit event shipping to an external collector (SIEM / log pipeline).

   config.edn:
     :server {:audit-webhook-url \"https://siem.example.com/ingest\"
              :audit-webhook-headers {\"Authorization\" \"Bearer ${SIEM_TOKEN}\"}}

   Each POST body is JSON-lines (one audit event per line — the same
   values-free records the audit JSONL file gets), batched up to 50 events,
   at-most-once, fully fail-open (a dead collector never touches the request
   path; overflow drops)."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [open.atlas.runtime.obs.impl.ship :as ship]
))

;; Same seam as impl.otlp: the app hands over a PROVIDER FN returning
;; {:url :headers}, read per call so a reloaded config takes effect without a
;; restart. Unconfigured = disabled, which is why the default yields nil.
(defonce ^:private provider* (atom (constantly nil)))

(defn configure!
  "Install the config provider fn (nil resets to 'webhook disabled')."
  [f]
  (reset! provider* (or f (constantly nil)))
  nil)

(defn- cfg [] (or (@provider*) {}))

(defn url
  "The configured webhook URL (${VAR} already resolved by the app), or nil when
   disabled."
  []
  (not-empty (str (:url (cfg)))))

(defn- headers []
  (or (:headers (cfg)) {}))

(def shipper (ship/make-shipper "audit-webhook"))

(defn send-batch!
  "POST one batch of pre-serialized JSON lines. Var-passed so tests redef it."
  [lines]
  (when-let [u (url)]
    (http/post u {:body (str (str/join "\n" lines) "\n")
                  :headers (merge {"Content-Type" "application/x-ndjson"} (headers))
                  :socket-timeout 5000 :connection-timeout 5000
                  :throw-exceptions true})))

(defn offer!
  "Queue one serialized audit JSON line for delivery. No-op when unconfigured.
   Never throws (audit calls this on the live request path)."
  [json-line]
  (when (url)
    (ship/enqueue! shipper #'send-batch! json-line))
  nil)
