(ns open.atlas.runtime.token
  "One runtime instance's token ledger.

   Studio's embedded runtime and a standalone Server call this same namespace.
   The implementation is shared; state is not. Every token names one graph id
   and is valid only on the instance that issued it."
  (:require [clojure.string :as str]
            [open.atlas.common.token :as t]
            [open.atlas.runtime.store :as rs]
            [open.atlas.runtime.token.impl.ledger :as ledger]))

(defrecord TokenStore [instance pepper graph-loaded? graph-serving-version
                       graph-enabled? row-security-gate])

(defn open!
  [instance {:keys [pepper graph-loaded? graph-serving-version graph-enabled?
                    row-security-gate]}]
  (let [findings (cond-> []
                   (not (and (string? pepper) (seq (str/trim pepper))))
                   (conj (t/error :token.pepper/unavailable [:pepper]
                                  "this instance has no token digest pepper"))

                   (not (and (ifn? graph-loaded?) (ifn? graph-serving-version)
                             (ifn? graph-enabled?) (ifn? row-security-gate)))
                   (conj (t/error :token.pepper/unavailable [:capabilities]
                                  "issuing a token requires graph availability, serving-version, enabled-state, and row-security capabilities")))]
    (if (seq findings)
      {:findings findings}
      {:store (->TokenStore instance pepper graph-loaded? graph-serving-version
                            graph-enabled? row-security-gate)
       :findings []})))

(defn- guarded [store token-id f]
  (if-let [findings (seq (rs/writable-findings (:instance store)))]
    {:findings (vec findings)}
    (rs/with-lock (:instance store) :graph-maintenance
      (rs/with-lock (:instance store) [:token token-id] (f)))))

(defn- normalize-issue-request
  "Temporary migration seam for callers not yet moved off `:grants`.

   It accepts exactly one legacy grant, immediately normalises it to the public
   single-graph shape, and rejects every other collection. The ledger therefore
   never persists nor returns a `:grants` field."
  [request]
  (if-not (contains? request :grants)
    {:request request}
    (let [grants (:grants request)]
      (if (and (sequential? grants) (= 1 (count grants)) (map? (first grants)))
        {:request (-> request
                      (dissoc :grants)
                      (merge (let [{:keys [graph version]} (first grants)]
                               {:graph-id graph :version version})))}
        {:findings [(t/error :token.grants/invalid [:grants]
                            "a token may authorise exactly one graph; issue another token for another graph")]}))))

(defn issue!
  "Issue a token for exactly one graph:

   ```clojure
   {:graph-id … :version :latest
    :actor … :request-id … :now … :expires-at nil
    :asserts-user? true}   ; optional, D1.9b: may carry :on-behalf-of on /eql
   ```

  The secret appears only in the initial successful response."
  [store {:keys [actor request-id] :as request}]
  (let [normalised (normalize-issue-request request)]
    (if (seq (:findings normalised))
      normalised
      (let [request (:request normalised)]
        (guarded store (str (:actor request) "/" (:request-id request))
                 #(ledger/issue! store request))))))

(defn verify
  "Verify a presented bearer and return the graph UUID/version it authorises."
  [store presented {:keys [now]}]
  (ledger/verify store presented {:now now}))

(defn revoke-token!
  "Revoke a token's only authorisation."
  [store {:keys [token-id] :as request}]
  (guarded store token-id #(ledger/revoke-token! store request)))

(def record ledger/record)
(def all-records ledger/all-records)
(def audit ledger/audit)
(def pinned-coordinates ledger/pinned-coordinates)
(def finding-codes t/finding-codes)
(defn finding? [x] (t/finding? x))

(defn active?
  "Whether a ledger record still authorizes access at this instant."
  [record now]
  (and (= :active (:status record)) (not (t/expired? record now))))
