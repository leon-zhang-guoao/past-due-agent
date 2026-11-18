(ns open.atlas.common.token.impl.grant
  "The single-graph token contract.

   A token authorises exactly one immutable graph identity and version:

   ```clojure
   {:token-id …
    :graph-id …
    :version :latest
    :status :active
    :created-at …
    :expires-at nil}
   ```

   There is deliberately no `:grants` collection. Requiring another graph is
   an explicit request for another token; that keeps the bearer, audit trail,
   rate-limit key, and data boundary aligned."
  (:require [open.atlas.common.token.impl.finding :as f])
  (:import [java.time Instant]))

(def authorization-keys #{:graph-id :version})
;; :asserts-user? — D1.9b: only a token issued with it may carry :on-behalf-of on /eql.
(def record-keys #{:token-id :graph-id :version :status :created-at :expires-at :asserts-user?})
(def statuses #{:active :revoked})

(def version-latest :latest)

(defn version-spec? [x]
  (or (= version-latest x) (and (integer? x) (pos? x))))

(defn- instant [s]
  (when (string? s) (try (Instant/parse s) (catch Exception _ nil))))

(defn validate-authorization
  "Validate the closed `:graph-id` / `:version` capability.
   The graph id is intentionally opaque here; the runtime asks its graph store
   whether that identity is loaded before it issues the token."
  [authorization]
  (f/sort-findings
   (cond
     (not (map? authorization))
     [(f/error :token.grants/invalid [] "a token authorization must be a map")]

     (seq (remove authorization-keys (keys authorization)))
     [(f/error :token.grants/invalid []
               "a token authorization carries exactly :graph-id and :version"
               :expected (vec (sort authorization-keys))
               :got (vec (sort (remove authorization-keys (keys authorization)))))]

     :else
     (cond-> []
       (not (and (string? (:graph-id authorization)) (seq (:graph-id authorization))))
       (conj (f/error :token.grants/invalid [:graph-id]
                      "a token authorization needs a non-blank graph id"))

       (not (version-spec? (:version authorization)))
       (conj (f/error :token.grants/invalid-version [:version]
                      "a version spec is :latest or a concrete positive version number"
                      :got (:version authorization)))))))

(defn validate-record [rec]
  (f/sort-findings
   (if-not (map? rec)
     [(f/error :token.record/malformed [] "a token record must be a map")]
     (cond-> (vec (validate-authorization (select-keys rec authorization-keys)))
       (seq (remove record-keys (keys rec)))
       (conj (f/error :token.record/malformed []
                      "the token record carries keys outside the closed set"
                      :expected (vec (sort record-keys))
                      :got (vec (sort (remove record-keys (keys rec))))))

       (not (and (string? (:token-id rec)) (seq (:token-id rec))))
       (conj (f/error :token.record/malformed [:token-id] "a token record needs an id"))

       (not (contains? statuses (:status rec)))
       (conj (f/error :token.record/malformed [:status]
                      "unknown token status" :expected (vec (sort statuses))
                      :got (:status rec)))

       (nil? (instant (:created-at rec)))
       (conj (f/error :token.record/malformed [:created-at]
                      "created-at must be a parseable instant"))

       (and (some? (:expires-at rec)) (nil? (instant (:expires-at rec))))
       (conj (f/error :token.record/malformed [:expires-at]
                      "expires-at is optional, but when present it must be a parseable instant"))

       (and (contains? rec :asserts-user?) (not (true? (:asserts-user? rec))))
       (conj (f/error :token.record/malformed [:asserts-user?]
                      "asserts-user? is either absent or true"
                      :got (:asserts-user? rec)))))))

(defn expired? [rec now]
  (let [exp (:expires-at rec)]
    (cond
      (nil? exp) false
      (nil? (instant exp)) true
      :else (let [n (instant now)] (or (nil? n) (not (.isBefore n (instant exp))))))))

(defn authorize
  "Validate a token record and return its one graph identity. The caller never
   supplies a graph selector: the bearer itself is the graph boundary."
  [rec {:keys [now]}]
  (let [fs (validate-record rec)]
    (cond
      (seq fs) {:findings fs}
      (not= :active (:status rec))
      {:findings [(f/error :token.authz/revoked [:token]
                           "this token is revoked on this instance" :got (:token-id rec))]}
      (expired? rec now)
      {:findings [(f/error :token.authz/expired [:token]
                           "this token has passed its expiry" :got (:token-id rec))]}
      ;; The capability rides beside the closed authorization, never inside it:
      ;; the graph boundary stays exactly :graph-id and :version.
      :else (cond-> {:authorization (select-keys rec authorization-keys)}
              (true? (:asserts-user? rec)) (assoc :asserts-user? true)))))
