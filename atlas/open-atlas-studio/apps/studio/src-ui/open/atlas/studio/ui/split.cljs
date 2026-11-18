(ns open.atlas.studio.ui.split
  "Draggable dividers between panes — Pathom Viz's `ui/drag-resize`.

   The divider sits *between* two panes and controls the one before it, as a
   percentage of the container. Percent rather than pixels because the window is
   resized more often than the split is, and a pixel split silently becomes the
   wrong split on a smaller screen.

   Sizes live in the caller's state, so a layout keeps its proportions across
   re-renders and a pane can be given a sensible default without the divider
   having to know anything about it."
  (:require [uix.core :as uix :refer [defui $]]))

(def ^:private min-pct 8)
(def ^:private max-pct 92)

(defn- clamp [pct] (max min-pct (min max-pct pct)))

(defui gutter
  "A divider. `dir` is `:x` for a vertical line between columns, `:y` for a
   horizontal one between rows; `pct` is the current size of the pane before it
   and `on-pct` receives the new one.

   The drag is tracked on the window, so the pointer may leave the four-pixel
   line — which it will, every time — without the drag stopping."
  [{:keys [dir pct on-pct]}]
  (let [state (uix/use-ref nil)
        begin (uix/use-callback
               (fn [^js e]
                 (let [^js el  (.-currentTarget e)
                       ^js box (.. el -parentElement getBoundingClientRect)
                       total   (if (= :x dir) (.-width box) (.-height box))
                       start   (if (= :x dir) (.-clientX e) (.-clientY e))
                       move    (fn [^js ev]
                                 (let [now   (if (= :x dir) (.-clientX ev) (.-clientY ev))
                                       delta (- now start)]
                                   (on-pct (clamp (+ pct (* 100 (/ delta total)))))))
                       stop    (atom nil)]
                   (.preventDefault e)
                   (reset! stop (fn []
                                  (.removeEventListener js/window "mousemove" move)
                                  (.removeEventListener js/window "mouseup" @stop)
                                  (.remove (.-classList (.-body js/document))
                                           (if (= :x dir) "resizing-x" "resizing-y"))))
                   (.add (.-classList (.-body js/document))
                         (if (= :x dir) "resizing-x" "resizing-y"))
                   (.addEventListener js/window "mousemove" move)
                   (.addEventListener js/window "mouseup" @stop)
                   (reset! state @stop)))
               [dir pct on-pct])]
    (uix/use-effect (fn [] #(when-let [f @state] (f))) [])
    ($ :div {:class (str "gutter gutter-" (name dir))
             :role "separator"
             :aria-orientation (if (= :x dir) "vertical" "horizontal")
             :on-mouse-down begin})))
