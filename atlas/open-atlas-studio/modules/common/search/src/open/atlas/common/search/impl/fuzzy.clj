(ns open.atlas.common.search.impl.fuzzy
  "Per-word candidate validation for global discovery; shares canonical folding."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [open.atlas.common.search.impl.gram :as gram]
            [open.atlas.common.search.impl.match :as match]))

(def ^:dynamic *min-word-coverage* 0.45)

(defn words [abbrev ns-str s]
  (->> (str/split (match/fold abbrev ns-str (or s "")) #"[^\p{IsAlphabetic}\p{IsDigit}]+")
       (remove str/blank?) distinct vec))

(defn- cjk? [w] (boolean (re-find #"[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]" w)))
(defn fragments [w] (disj (set (gram/terms nil nil w)) w))
(defn prepare-doc [abbrev d]
  (let [ns-str (if (= :entity (:doc/type d)) (:doc/id d) (namespace (keyword (:doc/id d))))
        ws (distinct (mapcat #(words abbrev ns-str %) (conj (vec (:doc/names d)) (:doc/raw-description d))))]
    (assoc d :doc/words (set ws))))

(defn- deletions [w]
  (conj (set (for [i (range (count w))] (str (subs w 0 i) (subs w (inc i))))) w))

(defn prepare-corpus [ds]
  (let [wf (frequencies (mapcat :doc/words ds))
        fs (into {} (map (fn [w] [w (fragments w)]) (keys wf)))
        posting (fn [features]
                  (reduce-kv (fn [acc w xs]
                               (reduce #(update %1 %2 (fnil conj #{}) w) acc xs)) {} features))]
    {:word-fragments fs
     :fragment-words (posting fs)
     ;; Symmetric deletes generate candidates, then one-edit? validates them.
     :deletion-words (posting (into {} (for [w (keys wf) :when (>= (count w) 3)] [w (deletions w)])))
     :fragment-df (frequencies (mapcat (fn [d] (set (mapcat fs (:doc/words d)))) ds))
     :document-count (count ds)}))

(defn whole-accepts? [d query-words]
  (every? (fn [w] (or (contains? (:doc/words d) w)
                      (and (cjk? w) (some #(str/includes? % w) (:doc/words d))))) query-words))

(defn- one-edit? [a b]
  (let [na (count a) nb (count b)]
    (cond
      (= a b) true
      (= na nb) (let [bad (vec (keep #(when (not= (nth a %) (nth b %)) %) (range na)))]
                  (or (= 1 (count bad))
                      (and (= 2 (count bad))
                           (let [[i j] bad]
                             (and (= j (inc i)) (= (nth a i) (nth b j)) (= (nth a j) (nth b i)))))))
      (= 1 (abs (- na nb))) (let [[short long] (if (< na nb) [a b] [b a])
                                  i (or (first (filter #(not= (nth short %) (nth long %)) (range (count short)))) (count short))]
                              (= (subs short i) (subs long (inc i))))
      :else false)))

(defn- idf [corpus fragment]
  (+ 1.0 (Math/log (/ (+ 1.0 (:document-count corpus))
                      (+ 1.0 (get (:fragment-df corpus) fragment 0))))))

(defn- candidates [corpus w]
  (let [vocab (:word-fragments corpus)]
    ;; A known whole word anchors that query position; don't fuzz it into other concepts.
    (if (contains? vocab w) #{w}
      (let [qf (fragments w)
            weight (into {} (map (fn [f] [f (idf corpus f)]) qf))
            total (reduce + 0.0 (vals weight))
            by-gram (when (> (count w) 3) (mapcat #(get (:fragment-words corpus) %) qf))
            by-edit (when (>= (count w) 4) (mapcat #(get (:deletion-words corpus) %) (deletions w)))
            by-prefix (when (>= (count w) 2) (filter #(str/starts-with? % w) (keys vocab)))]
        (into #{}
          (filter (fn [v]
                    (or (and (>= (count w) 2) (str/starts-with? v w))
                        (and (>= (count w) 4) (one-edit? w v))
                        (and (> (count w) 3) (pos? total)
                             (let [shared (set/intersection qf (get vocab v))]
                               (and (>= (count shared) 2)
                                    (>= (/ (reduce + 0.0 (map weight shared)) total) *min-word-coverage*)))))))
          (set (concat by-gram by-edit by-prefix)))))))

(defn- quote-token [s] (str "\"" (str/replace s "\"" "\"\"") "\""))
(defn query-plan [abbrev q corpus]
  (let [groups (mapv #(candidates corpus %) (words abbrev nil q))]
    {:groups groups
     :expression (when (and (seq groups) (every? seq groups))
                   (str/join " AND " (map #(str "(" (str/join " OR " (map quote-token (sort %))) ")") groups)))}))
(defn accepts? [doc plan]
  (every? #(some (:doc/words doc) %) (:groups plan)))
