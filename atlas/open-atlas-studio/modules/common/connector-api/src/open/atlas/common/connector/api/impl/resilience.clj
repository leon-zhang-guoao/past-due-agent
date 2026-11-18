(ns open.atlas.common.connector.api.impl.resilience
  "The HTTP-SPECIFIC half of the resilience model (doc 107 §107.4 / §107.7,
   owner ruling 2026-09-18): how an upstream answer is classified, how a
   `Retry-After` is read, and what «the same source» means for a URL.

   The general half -- bulkhead, circuit breaker, retry loop -- lives in the
   SPI (`spi/guarded-call!`) so that api and sql give the same answer to the
   same rule (D1.8). Two copies of a breaker is exactly the «second semantics»
   that ruling exists to prevent.

   **Leak discipline.** The fingerprint is built from the CONFIGURED connection
   (base url, auth kind, TLS store paths) and is hashed before it is used as a
   key; no credential value, response body or row value ever enters this ns.
   Nothing here logs."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi])
  (:import [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter DateTimeParseException]))

;; ---------------------------------------------------------------------------
;; Status -> category (doc 107 §107.4, platform default table)
;; ---------------------------------------------------------------------------

(defn platform-status-category
  "The PLATFORM's classification of a non-2xx status (doc 107 §107.4), before
   any source declaration. Shape-free: the by-key 404 default is applied by
   `status-category`, which knows the read shape."
  [status]
  (cond
    (#{401 403} status)     :unauthorized
    (= 408 status)          :timeout
    (= 429 status)          :rate_limited
    (#{502 503 504} status) :unavailable
    ;; Every other 4xx, 500, and a non-JSON non-2xx: the request or the source
    ;; is wrong, and neither is worth repeating.
    :else                   :source))

(defn status-category
  "How THIS read should read a non-2xx status (doc 107 §107.4 / §107.5).

   Three layers, in this order:

   1. **A protected status keeps the platform's answer.** Authorization, rate
      limiting and system failure are not a source's to re-describe -- the
      write side refuses such a mapping, and this is the second lock: a source
      must not be able to bisect its way past a 429 by calling it a per-row
      outcome.
   2. **The source's `:status-map`**, if it declares one for this status. This
      is the whole point of §107.5: the 2026-09-17 isolation ruling wrote one
      API's accidental choice of 400 into the platform, and the next API uses
      404, or 422, or none of them.
   3. **The platform default**, with one shape-dependent case: a 404 on a
      BY-KEY read is «no such row» (dialect-v1 §3: a missing row is an answer),
      while a 404 on a list or key-less read is an absent COLLECTION, which is
      the endpoint being wrong. An explicit `{404 :source}` overrides the
      former; the latter cannot be remapped at all."
  ([status] (status-category status nil nil))
  ([status status-map {:keys [by-key?]}]
   (cond
     (contains? spi/protected-statuses status) (platform-status-category status)
     (contains? status-map status)             (get status-map status)
     (and by-key? (= 404 status))              :not-found
     :else                                     (platform-status-category status))))

(defn effective-status-map
  "The `{status → category}` map in force for one operation.

   Three spellings collapse here, which is why it is ONE function that both the
   runtime and the write side call: the operation block's own `:status-map`,
   the slot/binding `:policy :status-map` (Semantic 1.4, merged by the runtime),
   and the superseded `:isolate-statuses` rewritten to its equivalent.

   §107.5: `:isolate-statuses #{400 404}` IS `{400 :input-rejected 404
   :input-rejected}`. The rewrite lives here, not in the validator, so the two
   spellings cannot diverge -- the runtime reads the same function the write
   side reports on."
  [source-config]
  (or (:status-map source-config)
      (get-in source-config [:policy :status-map])
      (when-let [declared (seq (get-in source-config [:batch :isolate-statuses]))]
        (into {} (map (fn [s] [s :input-rejected])) declared))))

(def ^:private http-date-formats
  [DateTimeFormatter/RFC_1123_DATE_TIME])

(defn retry-after-ms
  "Parse a `Retry-After` header into milliseconds: either delta-seconds or an
   HTTP-date (RFC 7231 §7.1.3). nil when absent or unparsable -- an unreadable
   hint is not a reason to refuse, it is a reason to fall back on backoff.

   The parsed number is the ONE upstream-derived value allowed to travel in
   ex-data (§107.4); a date in the past clamps to 0, never to a negative wait."
  [headers]
  (when-let [raw (some (fn [[k v]] (when (and k (= "retry-after" (str/lower-case (name k)))) v))
                       headers)]
    (let [s (str/trim (str raw))]
      (if (re-matches #"\d+" s)
        (* 1000 (Long/parseLong s))
        (some (fn [^DateTimeFormatter f]
                (try
                  (let [at (ZonedDateTime/parse s f)]
                    (max 0 (- (.toEpochMilli (.toInstant at)) (System/currentTimeMillis))))
                  (catch DateTimeParseException _ nil)
                  (catch Exception _ nil)))
              http-date-formats)))))

;; ---------------------------------------------------------------------------
;; Source identity
;; ---------------------------------------------------------------------------

(defn fingerprint
  "A stable, value-free key for «the same upstream, reached the same way».

   Deliberately NOT the whole source-config: credentials live there, and two
   resolvers on one connection must share one bulkhead and one breaker (D6.5).
   Deliberately not the resolver id either -- that would give every resolver
   its own breaker and protect nothing."
  [{:keys [base_url auth tls ssl_insecure?]}]
  (str (hash [(str base_url) (:type auth) (:token_url auth)
              (:keystore tls) (:truststore tls) (boolean ssl_insecure?)])))

(defn origin-fingerprint
  "Fallback identity for a call made without a source entry in hand (a health
   check, the NiFi probe): scheme + host + port ONLY.

   Never the whole URL. A URL carries the path and the query string, so keying
   on it would (a) put row values into the key and (b) give every row its own
   bulkhead and breaker -- which is the same as having neither."
  [url]
  (str (hash (try
               (let [u (java.net.URI. (str url))]
                 [(.getScheme u) (.getHost u) (.getPort u)])
               (catch Exception _ [(str url)])))))

;; ---------------------------------------------------------------------------
;; The general half, by delegation (one implementation, in the SPI)
;; ---------------------------------------------------------------------------

(def call! spi/guarded-call!)
(def reset-state! spi/reset-guards!)
