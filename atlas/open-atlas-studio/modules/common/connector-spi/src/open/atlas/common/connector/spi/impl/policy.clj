(ns open.atlas.common.connector.spi.impl.policy
  "Effective resilience policy: the four-level merge of D6.2 and the
   deployment-side tighten-only clamp of doc 107 §107.3 (owner 2026-09-18).

   **One implementation for every connector.** D1.8's «second semantics» is
   exactly what two copies of this arithmetic would produce: the same graph
   would get two answers to «may this be retried» depending on whether it
   reached an HTTP endpoint or a database. api and sql therefore share this ns
   rather than each deciding.

   Two halves, because D6.2 splits the ownership:

   - the **semantic** half (`:max_retries` `:retry_on`) is knowledge about the
     data source, so the modeller declares it and it rides with the artifact;
   - the **capacity** half (`:timeout_ms` `:backoff_ms` `:jitter` `:bulkhead`
     `:circuit_breaker`) is an operations judgement, so it lives on the
     deployment's Source Registry entry and never enters the artifact.

   The deployment may only TIGHTEN the semantic half. A deployment that raises
   `:max_retries` or adds a `:retry_on` category is overriding a modelling
   decision it cannot see the reason for -- `loosenings` names those so the
   version-switch check can refuse (`:deployment.policy/loosened`), and `merge`
   clamps regardless so a slipped-through config cannot widen anything at run
   time."
  (:require [open.atlas.common.dialect :as dialect]))

(def platform-default
  "The platform floor. Numbers are CANDIDATE values pending
   OQ-RESILIENCE-DEFAULTS (doc 107 §107.2); the key face is ruled, the
   numbers are not. `:max_retries 0` is deliberate: retry is opt-in, because a
   retry the modeller did not ask for turns one upstream call into several."
  {:timeout_ms      5000
   :max_retries     0
   :retry_on        #{}
   :backoff_ms      [200 2000 2.0]
   :jitter          0.2
   :bulkhead        {:concurrency 8 :max_wait_ms 100}
   :circuit_breaker {:failure_ratio [20 50] :reset_ms 10000}})

(def semantic-keys
  "The half a graph carries (D6.2). Anything else in a slot/binding `:policy`
   is a capacity key in the wrong place."
  dialect/policy-keys)

(def capacity-keys
  "The half the deployment owns; never written into an artifact."
  #{:timeout_ms :backoff_ms :jitter :bulkhead :circuit_breaker})

(defn- deep-merge-level [a b]
  (reduce-kv (fn [out k v]
               (if (and (map? v) (map? (get out k)))
                 (assoc out k (merge (get out k) v))
                 (assoc out k v)))
             (or a {}) (or b {})))

(defn- clamp-semantic
  "Apply the tighten-only rule: a later level may lower `:max_retries` and may
   remove `:retry_on` categories, never the reverse."
  [base override]
  (cond-> base
    (contains? override :max_retries)
    (assoc :max_retries (min (or (:max_retries base) 0) (:max_retries override)))

    (contains? override :retry_on)
    (assoc :retry_on (into #{} (filter (set (or (:retry_on base) #{})))
                           (:retry_on override)))))

(defn merge-policy
  "The four-level merge, then the clamp.

   `levels` run outermost-default first: platform default, connector-type
   default, slot policy, binding override. Each later level may widen the
   semantic half -- the modeller is the one allowed to say «this source may be
   retried twice». `capacity` is the deployment entry's `:resilience`, applied
   LAST and only able to tighten (§107.3).

   Returns one effective policy with every key present, so no caller has to
   re-derive a default."
  [levels capacity]
  (let [model    (reduce deep-merge-level platform-default (remove nil? levels))
        ;; A `:retry_on` naming a non-transient category never becomes
        ;; effective even if a write-side check were bypassed (D6.4).
        model    (update model :retry_on #(into #{} (filter dialect/transient-failure-categories) (or % #{})))
        capacity (or capacity {})
        merged   (deep-merge-level model (select-keys capacity capacity-keys))
        merged   (clamp-semantic merged (select-keys capacity semantic-keys))
        cap      (get-in merged [:bulkhead :concurrency])]
    (cond-> merged
      ;; §107.3: chunk concurrency is a semantic declaration, but it cannot
      ;; exceed the permits operations granted this source.
      (and cap (get-in merged [:batch :concurrency]))
      (update-in [:batch :concurrency] min cap))))

(defn loosenings
  "Every place the deployment's `:resilience` tries to WIDEN the graph's
   declared policy, as `{:key … :declared … :deployment …}` -- names and
   numbers, no values (finding-v1 §5). Empty when the deployment only
   tightens. The version-switch check turns each into a
   `:deployment.policy/loosened` finding."
  [model capacity]
  (let [model (deep-merge-level platform-default (or model {}))
        {:keys [max_retries retry_on] :as capacity} (or capacity {})
        extra (when retry_on (into #{} (remove (set (or (:retry_on model) #{}))) retry_on))
        cap   (get-in capacity [:bulkhead :concurrency])
        want  (get-in model [:batch :concurrency])]
    (cond-> []
      (and (some? max_retries) (> max_retries (or (:max_retries model) 0)))
      (conj {:key :max_retries :declared (:max_retries model) :deployment max_retries})

      (seq extra)
      (conj {:key :retry_on :declared (:retry_on model) :deployment (set extra)})

      (and cap want (> want cap))
      (conj {:key [:batch :concurrency] :declared want :deployment cap}))))

(defn retry?
  "May a failure of `category` be retried under `policy` on attempt `n`
   (1-based, so the first call is 1)? D6.4's five preconditions reduce to
   these here: the category is transient AND named by `:retry_on`, and the
   budget is not spent. `:circuit_open`, `:bulkhead_rejected` and
   `:unauthorized` can never qualify -- the first two are OUR refusal (retrying
   is what the breaker exists to stop) and the third is deterministic."
  [policy category n]
  (boolean (and (contains? dialect/transient-failure-categories category)
                (contains? (set (:retry_on policy)) category)
                (< n (inc (or (:max_retries policy) 0))))))

(defn backoff-ms
  "Wait before attempt `n+1` (1-based `n`): exponential from `[first max
   factor]`, multiplied by a jitter factor in [1-j, 1+j] and capped at max.
   `upstream` (a parsed `Retry-After`) wins when present -- it is the source
   telling us when it will answer, which beats our guess."
  ([policy n] (backoff-ms policy n nil))
  ([policy n upstream]
   (let [[first-ms max-ms factor] (or (:backoff_ms policy) (:backoff_ms platform-default))
         j     (double (or (:jitter policy) 0))
         base  (min (double max-ms) (* (double first-ms) (Math/pow (double factor) (dec (long n)))))
         spread (if (pos? j) (+ (- 1.0 j) (* (rand) 2 j)) 1.0)]
     (long (max 0 (if (some? upstream) upstream (min (double max-ms) (* base spread))))))))
