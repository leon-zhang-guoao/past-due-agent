(ns open.atlas.studio.ui.vlist
  "A windowed list.

   Three thousand attributes is the working size of this model, and three
   thousand DOM rows is not. Only the rows inside the viewport exist; a spacer
   holds the scrollbar honest. Row height is fixed, which is what makes the
   arithmetic — and therefore the scrolling — exact."
  (:require [uix.core :as uix :refer [defui $]]))

(def row-height 26)
(def ^:private overscan 6)

(defui vlist
  "`items` is the whole collection; `render-row` gets one item and returns its
   contents. `key-fn` identifies a row, `selected` marks one."
  [{:keys [items render-row key-fn selected on-select on-context empty-note row-size viewport-style]}]
  (let [row-height (or row-size row-height)
        [scroll set-scroll!] (uix/use-state 0)
        [height set-height!] (uix/use-state 400)
        viewport (uix/use-ref nil)
        total    (count items)
        first-i  (max 0 (- (quot scroll row-height) overscan))
        last-i   (min total (+ (quot (+ scroll height) row-height) overscan 1))
        ;; Not named `window`: that shadows js/window, which this effect uses.
        visible  (subvec (vec items) (min first-i total) (max (min last-i total) (min first-i total)))]

    (uix/use-effect
     (fn []
       (when-let [el @viewport]
         (let [measure (fn []
                         ;; **Ignore a zero.** A pane measured before layout —
                         ;; or while hidden — reports 0, and a height of 0
                         ;; windows the list down to the overscan: seven rows
                         ;; and then blank space, however many items there are.
                         ;; Keeping the last good height is wrong by at most a
                         ;; screenful; taking the 0 is wrong by everything.
                         (let [h (.-clientHeight el)]
                           (when (pos? h) (set-height! h))))]
           (measure)
           (if (exists? js/ResizeObserver)
             (let [ro (js/ResizeObserver. measure)]
               (.observe ro el)
               #(.disconnect ro))
             (do (.addEventListener js/window "resize" measure)
                 #(.removeEventListener js/window "resize" measure))))))
     [])

    ;; **One node, always.** This used to swap between an empty note and the
    ;; scroll container, so an empty moment — which is every graph switch, the
    ;; catalog is cleared before the next one arrives — unmounted the element
    ;; the measuring effect had observed. The effect never runs again (its deps
    ;; are empty by design; it owns a subscription, not a value), so the list
    ;; came back with a stale height and rendered a handful of rows into a full
    ;; pane. Keeping the container mounted keeps the ref, and the observer,
    ;; alive across that gap.
    ($ :div.pane-body {:ref viewport :style viewport-style :on-scroll #(set-scroll! (.. % -target -scrollTop))}
       (if (zero? total)
         ($ :p.empty-note (or empty-note "Nothing matches."))
         ($ :div.vlist {:style {:height (* total row-height)}}
            (for [[offset item] (map-indexed vector visible)
                  :let [i   (+ first-i offset)
                        k   (key-fn item)
                        on? (= k selected)]]
              ($ :div {:key k
                       :class (str "vrow" (when on? " on"))
                       :style {:top (* i row-height) :height row-height}
                       :role (when on-select "button")
                       :tab-index (when on-select 0)
                       :aria-selected (when on-select on?)
                       :on-click (when on-select #(on-select item))
                       ;; Right-click acts on the row **under the cursor**, so
                       ;; it selects first: a menu that acted on whatever was
                       ;; selected a moment ago would act on the wrong thing
                       ;; exactly when the user was most sure it would not.
                       :on-context-menu (when on-context
                                          (fn [e]
                                            (.preventDefault e)
                                            (on-select item)
                                            (on-context {:item item
                                                         :x (.-clientX e)
                                                         :y (.-clientY e)})))
                       :on-key-down #(cond
                                       (and on-select (contains? #{"Enter" " "} (.-key %)))
                                       (do (.preventDefault %) (on-select item))
                                       ;; Keyboard route to the same menu —
                                       ;; a right-click-only action is one a
                                       ;; keyboard user simply does not have.
                                       (and on-context (= "ContextMenu" (.-key %)))
                                       (do (.preventDefault %)
                                           (on-select item)
                                           (let [r (.getBoundingClientRect (.-currentTarget %))]
                                             (on-context {:item item :x (.-left r)
                                                          :y (.-bottom r)}))))}
                 (render-row item))))))))
