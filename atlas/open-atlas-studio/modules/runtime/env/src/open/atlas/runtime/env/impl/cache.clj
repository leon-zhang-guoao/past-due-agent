(ns open.atlas.runtime.env.impl.cache
  "Lazy materialisation, single-flight, cache (R3.5 «an env materialises the
   first time it is needed»).

   ## The key is `[qualified-name concrete-version]`, so a pointer move needs no invalidation

   A version is immutable (D3.6/D4.1). A deploy or a rollback changes **which
   version `:latest` resolves to**, not the content of any version — the moment
   the pointer moves, the next request computes a different key, and the old
   entry is neither wrong nor in the way. Only two reasons to drop it remain:
   reclaiming memory, and a version being collected.

   Only a design that keyed on `:latest` would need invalidation, and it would
   carry a window with it: the pointer has moved, the cache is not cleared yet,
   so for a while the old version still answers — the very thing R3.6 («a
   rollback takes effect atomically across all latest») exists to prevent.

   ## Idle expiry (8.4.20 裁定十四 ①)

   Correctness never needed expiry — the key is a concrete version — but memory
   does: a compiled env for a version nobody has queried in hours is pure
   retention. Each entry records when it was last used, and every access reaps
   the entries idle longer than the TTL. The clock is injectable so a test can
   age a cache without sleeping; the TTL is the SAME configured value the
   Builder's own cache uses (`env/cache-ttl-ms`), so the two doors cannot drift
   to two numbers.

   ## single-flight

   Concurrent hits on one key materialise once and the rest wait (R3.7's reason
   for sync: one compile is real work, and N concurrent requests each compiling
   is pure waste). It uses the instance's lock registry, key
   `[:env qualified-name version]` — one table shared with graph and token, so
   subsystems never serialise on each other through a bare string collision."
  (:require [open.atlas.runtime.store :as rs]))

(defn new-cache
  "`(new-cache)` / `(new-cache {:ttl-ms n :clock (fn [] millis)})`.

   The state is one atom holding `{:entries {k {:value v :last-used-ms t}}}`
   plus the two knobs, so `invalidate!` and `held` stay single-atom operations."
  ([] (new-cache nil))
  ([{:keys [ttl-ms clock]}]
   (atom {:entries {}
          :ttl-ms ttl-ms
          :clock (or clock #(System/currentTimeMillis))})))

(defn- reap
  "Drop the entries idle longer than the TTL. No TTL configured = no expiry."
  [{:keys [ttl-ms] :as state} now]
  (if (and (number? ttl-ms) (pos? ttl-ms))
    (update state :entries
            #(into {} (remove (fn [[_ e]] (>= (- now (:last-used-ms e)) ttl-ms))) %))
    state))

(defn- touch
  "Reap first, then mark `k` used at `now` if it is still there."
  [state k now]
  (let [state (reap state now)]
    (cond-> state
      (contains? (:entries state) k) (assoc-in [:entries k :last-used-ms] now))))

(defn get-or-materialize!
  "Hit returns; otherwise materialise inside this key's lock after a **second**
   lookup (double-check).

   The second lookup is not redundant: by the time the requests waiting on the
   lock wake up, the first one has already stored the result."
  [instance cache k build!]
  (let [now ((:clock @cache))
        state (swap! cache touch k now)]
    (or (get-in state [:entries k :value])
        (rs/with-lock instance (into [:env] k)
          (or (get-in @cache [:entries k :value])
              (let [v (build!)]
                (when (:env v)
                  (swap! cache assoc-in [:entries k]
                         {:value v :last-used-ms ((:clock @cache))}))
                v))))))

(defn invalidate!
  "Drop everything, or one key. **Not a correctness device** (see the ns
   comment); it is for reclaiming memory and for collection."
  ([cache] (swap! cache assoc :entries {}))
  ([cache k] (swap! cache update :entries dissoc k)))

(defn held
  "Which `[qualified-name version]` the cache is holding. Retention decisions
   need to know what env kept alive (R3.5). Expired-but-not-yet-reaped entries
   are not held: a reap runs first, so this answers about now, not about the
   last access."
  [cache]
  (let [state (swap! cache reap ((:clock @cache)))]
    (into #{} (keys (:entries state)))))
