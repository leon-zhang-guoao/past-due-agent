(ns open.atlas.runtime.obs.impl.ring
  "Bounded in-memory ring of recent events for realtime GUI review. Thread-safe
   via an atom; `recent` returns newest-first.")

(defn make
  "Create a ring holder with capacity `cap` (a positive integer)."
  [cap]
  {:pre [(pos-int? cap)]}
  {:cap cap :items (atom [])})

(defn push!
  "Append `item`, dropping the oldest beyond :cap. Returns nil."
  [{:keys [cap items]} item]
  (swap! items (fn [v]
                 (let [v' (conj v item)
                       n  (count v')]
                   (if (> n cap) (subvec v' (- n cap)) v'))))
  nil)

(defn recent
  "Up to `n` most-recent items, newest first (default: all)."
  ([ring] (recent ring (:cap ring)))
  ([{:keys [items]} n] (->> @items rseq (take n) vec)))

(defn clear!
  "Drop all items from the ring. Returns nil."
  [{:keys [items]}]
  (reset! items [])
  nil)
