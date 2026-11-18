(ns open.atlas.runtime.token.impl.ledger
  "The instance-local, single-graph token ledger.

   Each record contains one `:graph-id`. Studio's embedded
   runtime and a standalone Server both use this file layout and behaviour; the
   instances do not share the directory, digest pepper, or token values."
  (:require [clojure.string :as str]
            [open.atlas.common.token :as t]
            [open.atlas.runtime.obs :as obs]
            [open.atlas.runtime.store :as rs]
            [open.atlas.runtime.token.impl.secret :as secret])
  (:import [java.io File]))

(def ^:private subsystem "tokens")
(defn- tokens-dir ^File [store] (rs/subsystem-dir (:instance store) subsystem))
(defn- records-dir ^File [store] (File. (tokens-dir store) "records"))
(defn- record-file ^File [store token-id]
  (File. (records-dir store) (str (rs/safe-segment! token-id "token id") ".edn")))
;; One ledger, not two (owner ruling 2026-08-31, 8.10.4). This module used to
;; keep its own EDN audit under tokens/audit/<token-id>/; it now submits events
;; to runtime/obs like everything else, and reads them back through the subject
;; index. `obs-dir` is the instance's own obs subsystem — passed explicitly,
;; because obs's process-wide anchor cannot be right for a process holding two
;; instances.
(defn- obs-dir ^File [store] (rs/subsystem-dir (:instance store) "obs"))

(defn row [store token-id]
  (when (and (string? token-id) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" token-id))
    (rs/read-edn (record-file store token-id))))

(defn record [store token-id] (:record (row store token-id)))

(defn all-records [store]
  (for [^File f (file-seq (records-dir store))
        :when (and (.isFile f) (str/ends-with? (.getName f) ".edn"))
        :let [r (:record (rs/read-edn f))]
        :when (map? r)]
    r))

;; audit-event-v1 names the op :token/issue and :token/revoke; this ledger's
;; own vocabulary was :issue and :revoke-token. The contract wins on the wire,
;; and `audit` keeps answering in this module's terms — a projection, not a
;; second ledger.
(def ^:private op->event {:issue :token/issue :revoke-token :token/revoke})
(def ^:private event->op (into {} (map (fn [[k v]] [v k])) op->event))

(defn- legacy-view
  "One v1 event as this module's callers have always seen it."
  [e]
  {:token-id      (:token-id e)
   :op            (event->op (:event e))
   :actor         (get-in e [:actor :on-behalf-of])
   :request-id    (:request-id e)
   :at            (:at e)
   :outcome       (:outcome e)
   :authorization (:authorization e)})

(defn audit [store token-id]
  (mapv legacy-view (obs/audit-for-subject (obs-dir store) :token token-id)))

(defn- authorization-of [record]
  (select-keys record t/authorization-keys))

(defn- audit! [store token-id {:keys [op actor request-id at outcome authorization]}]
  ;; audit-event-v1 §4: the service principal is this instance's control plane;
  ;; the human who asked is :on-behalf-of. :asserted-by is :verified because the
  ;; instance validated this call itself — writing :verified where only an
  ;; assertion happened is the one thing 50-security forbids here.
  (obs/audit-record!
   (obs-dir store)
   {:v 1
    :event       (op->event op)
    :at          at
    :request-id  request-id
    :instance-id (rs/instance-id (:instance store))
    :entry       :internal
    :outcome     outcome
    :actor       {:service {:instance-id (rs/instance-id (:instance store))}
                  :on-behalf-of actor
                  :asserted-by  :verified}
    :token-id      token-id
    :authorization authorization}))

(defn- put-row! [store row*]
  (rs/write-edn! (record-file store (get-in row* [:record :token-id])) row*))

(defn pinned-coordinates [store now]
  (into #{}
        (for [r (all-records store)
              :when (and (= :active (:status r))
                         (not (t/expired? r now))
                         (integer? (:version r)))]
          {:graph-id (:graph-id r) :version (:version r)})))

(defn- availability-findings
  [{:keys [graph-loaded? graph-serving-version graph-enabled?]}
   {:keys [graph-id version]}]
  (cond
    (not (graph-enabled? graph-id))
    [(t/error :token.issue/graph-disabled [:graph-id]
              "this graph is disabled on this runtime instance"
              :got graph-id)]

    (integer? version)
    (when-not (graph-loaded? graph-id version)
      [(t/error :token.issue/graph-not-loaded [:version]
                "this instance has not loaded that exact graph version"
                :got {:graph-id graph-id :version version})])

    (= t/version-latest version)
    (when-not (graph-serving-version graph-id)
      [(t/error :token.issue/no-serving-version [:version]
                "this graph has no serving pointer on this instance"
                :got graph-id)])

    :else []))

(defn- gate-findings
  [{:keys [row-security-gate graph-loaded? graph-serving-version]}
   {:keys [graph-id version]}]
  (let [resolved-version (if (integer? version) version (graph-serving-version graph-id))]
    (if (and resolved-version (graph-loaded? graph-id resolved-version))
      (vec (row-security-gate graph-id resolved-version))
      [])))

(defn issue!
  [{:keys [instance pepper] :as store}
   {:keys [graph-id version actor request-id now expires-at asserts-user?]}]
  (let [token-id (secret/derive-token-id {:instance-id (rs/instance-id instance)
                                          :actor actor :request-id request-id})]
    (if-let [existing (record store token-id)]
      {:replayed true :token-id token-id :record existing}
      (let [authorization {:graph-id graph-id :version version}
            rec (cond-> (merge {:token-id token-id :status :active
                                :created-at now :expires-at expires-at}
                               authorization)
                  ;; Absent unless asked for (D1.9b): an ordinary token can never carry a user.
                  ;; false means "not asked"; anything else that is not true is kept so
                  ;; validate-record refuses it instead of guessing.
                  (and (some? asserts-user?) (not (false? asserts-user?))) (assoc :asserts-user? asserts-user?))
            findings (-> (vec (t/validate-record rec))
                         (into (availability-findings store authorization))
                         (into (gate-findings store authorization)))]
        (if (seq findings)
          {:findings (t/sort-findings findings)}
          (let [secret* (secret/generate-secret)
                digest (t/digest pepper {:instance-id (rs/instance-id instance)
                                         :token-id token-id :secret secret*})]
            (put-row! store {:record rec :digest digest :issued-by actor :issued-at now})
            (audit! store token-id {:op :issue :actor actor :request-id request-id
                                    :at now :outcome :ok :authorization authorization})
            {:token-id token-id :secret (t/format-token token-id secret*) :record rec}))))))

(defn verify
  [{:keys [instance pepper] :as store} presented {:keys [now]}]
  (let [parsed (t/parse-token presented)]
    (if (seq (:findings parsed))
      parsed
      (let [{:keys [token-id secret]} parsed
            row* (row store token-id)]
        (cond
          (nil? row*)
          {:findings [(t/error :token.authz/unknown [:token]
                               "this instance has issued no token with that id"
                               :got token-id)]}

          (not= :active (get-in row* [:record :status]))
          {:findings [(t/error :token.authz/revoked [:token]
                               "this token is revoked on this instance" :got token-id)]}

          (t/expired? (:record row*) now)
          {:findings [(t/error :token.authz/expired [:token]
                               "this token has passed its expiry" :got token-id)]}

          (not (t/digest-matches? pepper {:instance-id (rs/instance-id instance)
                                          :token-id token-id :secret secret}
                                  (:digest row*)))
          {:findings [(t/secret-safe-error :token.authz/secret-mismatch [:token]
                                           "the presented secret does not match this token")]}

          :else
          (let [r (t/authorize (:record row*) {:now now})]
            (if (:authorization r) (assoc r :token-id token-id) r)))))))

(defn revoke-token! [store {:keys [token-id actor request-id now]}]
  (if-let [row* (row store token-id)]
    (let [record* (:record row*)]
      (when (= :active (:status record*))
        (put-row! store (assoc-in row* [:record :status] :revoked)))
      (audit! store token-id {:op :revoke-token :actor actor :request-id request-id
                              :at now :outcome :ok :authorization (authorization-of record*)})
      {:token-id token-id :status :revoked})
    {:findings [(t/error :token.authz/unknown [:token-id]
                         "no such token on this instance" :got token-id)]}))
