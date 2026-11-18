(ns open.atlas.studio.ui.trace
  "The run as a timeline.

   Every invocation carries an offset from the run's start and its own duration,
   so the same numbers that colour the plan graph also lay out as bars. What this
   shows that the graph cannot: what ran in parallel, where the time actually
   went, and — because a nested query runs one graph per row — how many times the
   same resolver was asked the same kind of question."
  (:require [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]))

(defn- ticks
  "Four axis marks, on numbers a person would choose."
  [total]
  (let [step (/ total 4)]
    (mapv #(* % step) (range 0 5))))

(defn- rows
  "Every invocation at every depth, or — for an Explain, which has no timeline —
   the root plan's steps."
  [plan]
  (or (seq (:timeline plan))
      (filterv :start-ms (:steps plan))))

(defui timeline [{:keys [plan]}]
  (let [items (rows plan)
        total (or (:total-ms plan) 1)]
    (if (empty? items)
      ($ :p.empty-note "Run a query to see where its time went.")
      ($ :div.trace
         ($ :div.trace-axis
            (for [t (ticks total)]
              ($ :span {:key t :style {:left (str (* 100 (/ t total)) "%")}}
                 (str (/ (js/Math.round (* 10 t)) 10) " ms"))))
         (for [[i {:keys [step resolver status start-ms elapsed-ms depth path]}]
               (map-indexed vector items)]
           ($ :div.trace-row {:key i}
              ;; Depth is the indent: a resolver that ran inside a join reads as
              ;; running inside it. The path is on the title, because at three
              ;; joins deep it is longer than the pane.
              ($ :span.label {:title (str resolver
                                          (when (seq path) (str "  ·  " (str/join " → " path))))
                              :style (when (pos? (or depth 0))
                                       {:padding-left (str (* 10 depth) "px")})}
                 (or resolver (str "step " step)))
              ($ :div.track
                 ($ :div {:class (str "trace-bar " (name (or status :not-run)))
                          :style {:left  (str (* 100 (/ start-ms total)) "%")
                                  :width (str (max 0.6 (* 100 (/ (or elapsed-ms 0) total))) "%")}}
                    ($ :span (str (or elapsed-ms 0) " ms")))))))
      )))
