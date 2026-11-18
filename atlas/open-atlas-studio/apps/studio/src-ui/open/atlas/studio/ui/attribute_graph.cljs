(ns open.atlas.studio.ui.attribute-graph
  "The index graph, ported from Pathom Viz.

   A direct rewrite of `d3-attribute-graph.js` and the node/link construction
   around it in `index_explorer.cljs`, from the Pathom Viz standalone (MIT,
   Copyright (c) Wilker Lúcio da Rocha Silva). Everything that made our version
   different has been dropped: the forces, the radii, the palette, the highlight
   colours, the drag and the absence of labels are Pathom Viz's.

   The shape of it:

   - nodes are attributes; a link means 'some resolver takes A and produces B',
     and the resolvers ride on the link rather than being nodes themselves;
   - the layout is a **live** `forceSimulation` — there is no layout step. The
     tick handler paints; a drag pins the grabbed node and re-heats the whole
     simulation, so neighbours trail after it on weak springs and settle when it
     is let go;
   - nothing is labelled. One text line in the corner names whatever the pointer
     is on: the attribute for a node, the resolvers for a link. Four hundred
     labels are a smear; the corner line is always legible."
  (:require ["d3-drag" :as drag]
            ["d3-force" :as force]
            ["d3-selection" :as sel]
            ["d3-zoom" :as zoom]
            [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]))

(def scheme-category-10
  "d3.schemeCategory10, the palette Pathom Viz colours namespaces with."
  ["#1f77b4" "#ff7f0e" "#2ca02c" "#d62728" "#9467bd"
   "#8c564b" "#e377c2" "#7f7f7f" "#bcbd22" "#17becf"])

(defn- ordinal-scale
  "d3.scaleOrdinal: a colour per distinct value, assigned in order of first use."
  []
  (let [seen (atom {})]
    (fn [k]
      (when k
        (or (@seen k)
            (let [c (nth scheme-category-10 (mod (count @seen) (count scheme-category-10)))]
              (swap! seen assoc k c)
              c))))))

(defn- extract-ns
  "Pathom Viz's `extractNs`: an attribute's namespace, or nothing for an input
   combination — a combination belongs to no namespace, so it takes the `multi`
   styling instead of a colour."
  [attribute]
  (when-not (str/starts-with? attribute "#")
    (let [parts (str/split attribute #"/")]
      (when (> (count parts) 1) (first parts)))))

(defn- node-radius
  "Pathom Viz's `node-radius`: what the simulation collides with. Not the drawn
   radius — the circle and its ring carry weight and reach on two separate
   channels, and this is the space the pair of them occupies."
  [weight reach]
  (js/Math.round (+ (js/Math.sqrt (+ (or weight 1) 2))
                    (js/Math.sqrt (+ (or reach 1) 1)))))

(defn build-data
  "`compute-nodes-links`, over our index-graph DTO."
  [{:keys [nodes edges]} focus]
  (let [present (into #{} (map :attribute) nodes)]
    {:nodes (clj->js
             (mapv (fn [{:keys [attribute weight reach combination? external?]}]
                     {:attribute attribute
                      :multiNode (boolean combination?)
                      :external (boolean external?)
                      :mainNode  (= attribute focus)
                      :weight    weight
                      :reach     reach
                      :radius    (node-radius weight reach)})
                   nodes))
     :links (clj->js
             (into []
                   (comp (filter (fn [{:keys [from to]}] (and (present from) (present to))))
                         (map (fn [{:keys [from to resolvers nested?]}]
                                {:source    from
                                 :target    to
                                 :weight    (count resolvers)
                                 ;; Pathom Viz keys its link index by this exact
                                 ;; string, so hovering one link lights every link
                                 ;; the same resolvers make.
                                 :resolvers (str/join "\n" resolvers)
                                 :deep      (boolean nested?)})))
                   edges))}))

(defn- link-path
  "The tick handler's path: a straight line trimmed at both ends by the node
   radius, so the arrowhead lands on the circle rather than under it."
  [^js d]
  (let [^js s  (.-source d)
        ^js t  (.-target d)
        gamma  (js/Math.atan2 (- (.-y t) (.-y s)) (- (.-x t) (.-x s)))
        tx     (- (.-x t) (* (js/Math.cos gamma) (.-radius t)))
        ty     (- (.-y t) (* (js/Math.sin gamma) (.-radius t)))
        sgamma (js/Math.atan2 (- (.-y s) (.-y t)) (- (.-x s) (.-x t)))
        sx     (- (.-x s) (* (js/Math.cos sgamma) (.-radius s)))
        sy     (- (.-y s) (* (js/Math.sin sgamma) (.-radius s)))]
    (str "M" sx "," sy "L" tx "," ty)))

(defn- node-at
  "The node under a point, in the container's own coordinates — the same frame
   `d`'s `x`/`y` and a drag event's `x`/`y` are already in, so the zoom
   transform needs no undoing. The radius is floored so a one-resolver node is
   still a target a pointer can actually hit."
  [nodes x y]
  (first (filter (fn [^js n]
                   (let [dx (- (.-x n) x)
                         dy (- (.-y n) y)
                         r  (max 12 (.-radius n))]
                     (<= (+ (* dx dx) (* dy dy)) (* r r))))
                 (array-seq nodes))))

(defn render!
  "`render(element, data)`. Returns a dispose thunk.

   `connecting?` is a thunk read at gesture start, not a parameter: connect
   mode must not be a dependency of the render effect, because rebuilding the
   SVG to toggle a mode throws away the arrangement the reader has — and the
   element the drag is holding."
  [^js svg-el data width height {:keys [on-select on-click-edge on-connect connecting?]}]
  (let [^js svg (sel/select svg-el)
        nodes    (:nodes data)
        links    (:links data)
        color    (ordinal-scale)]
    (set! (.-innerHTML svg-el) "")
    (let [container (.append svg "g")
          ^js zoom-b (.on (zoom/zoom) "zoom"
                          (fn [^js e] (.attr container "transform" (.-transform e))))]
      (.attr svg "width" width)
      (.attr svg "height" height)
      (.call svg zoom-b)

      ;; build the arrows.
      (-> svg
          (.append "svg:defs")
          (.selectAll "marker")
          (.data #js ["arrow-provides" "arrow-reaches"])
          (.join "svg:marker")
          (.attr "id" (fn [d] d))
          (.attr "viewBox" "0 -5 10 10")
          (.attr "refX" 15)
          (.attr "refY" 0)
          (.attr "markerWidth" 6)
          (.attr "markerHeight" 6)
          (.attr "markerUnits" "userSpaceOnUse")
          (.attr "orient" "auto")
          (.attr "class" (fn [d] (str "ag-" d)))
          (.append "svg:path")
          (.attr "d" "M0,-5L10,0L0,5"))

      (let [^js simulation
            (-> (force/forceSimulation nodes)
                (.force "link" (-> (force/forceLink links)
                                   (.id (fn [^js d] (.-attribute d)))
                                   (.distance 90)
                                   (.strength 0.1)))
                (.force "charge" (.strength (force/forceManyBody) -60))
                (.force "collision" (.radius (force/forceCollide)
                                             (fn [^js d] (* (.-radius d) 1.6))))
                (.force "center" (force/forceCenter (/ width 2) (/ height 2))))

            links-index (atom {})
            following   (atom nil)
            label       (-> svg (.append "text") (.attr "x" 10) (.attr "y" 30) (.text ""))

            ;; Selecting an attribute may briefly follow the live simulation so
            ;; the node is actually framed. A user-initiated zoom or pan must
            ;; win immediately, however: otherwise each simulation tick writes
            ;; the old locate transform back over the gesture and the canvas
            ;; feels as if it lost focus. Keep the selection highlight, cancel
            ;; only the automatic viewport follow.
            _           (.on zoom-b "start.user-input"
                             (fn [_] (reset! following nil)))

            highlight-edge!
            (fn [resolvers on?]
              (doseq [^js line (get @links-index resolvers)]
                (.classed line "ag-link-focus" on?))
              (.text label (if on? (str/replace resolvers #"\n" ", ") "")))

            ^js link
            (-> container
                (.append "g")
                (.selectAll "line")
                (.data links)
                (.join "svg:path")
                (.attr "class" "ag-link")
                (.attr "marker-end" "url(#arrow-provides)")
                (.classed "ag-link-deep" (fn [^js d] (.-deep d)))
                (.each (fn [^js d]
                         (this-as this
                           (let [line (sel/select this)]
                             (set! (.-ownerLine d) line)
                             (swap! links-index update (.-resolvers d) (fnil conj []) line)))))
                (.on "click" (fn [_ ^js d] (when on-click-edge (on-click-edge (.-resolvers d)))))
                (.on "mouseenter" (fn [_ ^js d] (highlight-edge! (.-resolvers d) true)))
                (.on "mouseleave" (fn [_ ^js d] (highlight-edge! (.-resolvers d) false))))

            highlight!
            (fn [^js d on?]
              (.classed (.-nodeElement d) "ag-node-highlight" on?)
              (doseq [^js l (array-seq (.-lineTargets d))]
                (.classed (.-ownerLine l) "ag-link-target" on?))
              (doseq [^js l (array-seq (.-lineSources d))]
                (.classed (.-ownerLine l) "ag-link-source" on?))
              (.text label (if on? (.-attribute d) "")))

            ;; The drag, verbatim: pin the node under the pointer, re-heat the
            ;; simulation while it is held, and release it back into the forces so
            ;; it settles rather than staying where it was dropped.
            ;; `(zero? (.-active e))`, not `(when-not (.-active e) ...)`. The
            ;; original reads `if (!d3.event.active)`, and in JavaScript 0 is
            ;; falsy — in ClojureScript it is not, so the direct translation
            ;; silently never re-heats the simulation. That looked like "the drag
            ;; works once or twice and then stops": while the graph was still
            ;; cooling from mount the ticks kept coming and the pin was painted,
            ;; and the moment alpha crossed alphaMin the timer stopped for good.
            ;;
            ;; In connect mode the same gesture means something else entirely:
            ;; it draws a proposed hop rather than moving a node. The
            ;; simulation is deliberately left alone — re-heating it would move
            ;; the target out from under the pointer mid-gesture.
            rubber (atom nil)

            node-drag
            (-> (drag/drag)
                (.on "start" (fn [^js e ^js d]
                               (if (connecting?)
                                 (reset! rubber
                                         (-> container
                                             (.append "line")
                                             (.attr "class" "ag-rubber")
                                             (.attr "x1" (.-x d)) (.attr "y1" (.-y d))
                                             (.attr "x2" (.-x d)) (.attr "y2" (.-y d))))
                                 (do (when (zero? (.-active e))
                                       (.restart (.alphaTarget simulation 0.3)))
                                     (set! (.-fx d) (.-x d))
                                     (set! (.-fy d) (.-y d))))))
                (.on "drag" (fn [^js e ^js d]
                              (if-let [^js r @rubber]
                                (-> r (.attr "x2" (.-x e)) (.attr "y2" (.-y e)))
                                (do (set! (.-fx d) (.-x e))
                                    (set! (.-fy d) (.-y e))))))
                (.on "end" (fn [^js e ^js d]
                             (if-let [^js r @rubber]
                               (let [^js hit (node-at nodes (.-x e) (.-y e))]
                                 (.remove r)
                                 (reset! rubber nil)
                                 ;; A hop needs two distinct ends. Dropping on
                                 ;; empty space or back on the source is how a
                                 ;; gesture is abandoned, so it is silent.
                                 (when (and hit on-connect
                                            (not= (.-attribute hit) (.-attribute d)))
                                   (on-connect (.-attribute d) (.-attribute hit))))
                               (do (when (zero? (.-active e))
                                     (.alphaTarget simulation 0))
                                   (set! (.-fx d) nil)
                                   (set! (.-fy d) nil))))))

            ^js node
            (-> container
                (.append "g")
                (.selectAll "circle")
                (.data nodes)
                (.join "circle")
                (.attr "class" "ag-node")
                (.classed "ag-node-multi" (fn [^js d] (.-multiNode d)))
                (.classed "ag-node-external" (fn [^js d] (.-external d)))
                (.style "fill" (fn [^js d] (when (.-external d) "#fff")))
                (.style "stroke-dasharray" (fn [^js d] (when (.-external d) "3 2")))
                (.classed "ag-node-main" (fn [^js d] (.-mainNode d)))
                (.attr "stroke-width" (fn [^js d] (+ (js/Math.sqrt (or (.-reach d) 1)) 1)))
                (.attr "r" (fn [^js d] (+ (js/Math.sqrt (or (.-weight d) 1)) 2)))
                (.attr "stroke" (fn [^js d]
                                  (when-not (.-multiNode d)
                                    (color (extract-ns (.-attribute d))))))
                (.each (fn [^js d]
                         (this-as this
                           (set! (.-nodeElement d) (sel/select this))
                           (set! (.-lineTargets d)
                                 (.filter links (fn [^js l]
                                                  (= (.-attribute (.-source l)) (.-attribute d)))))
                           (set! (.-lineSources d)
                                 (.filter links (fn [^js l]
                                                  (= (.-attribute (.-target l)) (.-attribute d))))))))
                (.on "click" (fn [_ ^js d] (when on-select (on-select (.-attribute d)))))
                (.on "mouseenter" (fn [_ ^js d] (highlight! d true)))
                (.on "mouseleave" (fn [_ ^js d] (highlight! d false)))
                (.call node-drag))]

        (.on simulation "tick"
             (fn []
               (when-let [{:keys [attr until locate]} @following]
                 (if (< (.now js/Date) until)
                   (locate attr)
                   (reset! following nil)))
               (.attr link "d" link-path)
               (-> node
                   (.attr "cx" (fn [^js d] (.-x d)))
                   (.attr "cy" (fn [^js d] (.-y d))))))

        {:dispose #(.stop simulation)
         :fit!
         (fn []
           (reset! following nil)
           (let [all (array-seq nodes)
                 xs (map #(.-x %) all)
                 ys (map #(.-y %) all)
                 w (.-clientWidth svg-el)
                 h (.-clientHeight svg-el)
                 bw (max 1 (- (apply max xs) (apply min xs)))
                 bh (max 1 (- (apply max ys) (apply min ys)))
                 k (max 0.05 (min 1.4 (/ (max 1 (- w 100)) bw) (/ (max 1 (- h 100)) bh)))
                 cx (/ (+ (apply max xs) (apply min xs)) 2)
                 cy (/ (+ (apply max ys) (apply min ys)) 2)]
             (.call svg (.-transform zoom-b)
                    (-> zoom/zoomIdentity
                        (.translate (- (/ w 2) (* k cx)) (- (/ h 2) (* k cy)))
                        (.scale k)))))
         ;; Marking a node is no use when it is one of four hundred and off
         ;; screen: the viewport travels to it. The zoom transform is set rather
         ;; than the nodes moved, so a locate does not disturb the simulation —
         ;; and the scale is left alone unless the picture is zoomed out far
         ;; enough that a single node would be invisible.
         :locate!
         (fn locate! [attr]
           (when-let [^js d (first (filter (fn [^js n] (= (.-attribute n) attr))
                                           (array-seq nodes)))]
             ;; Framed with its neighbours, not centred alone: a peripheral
             ;; attribute centred by itself fills the pane with the empty space
             ;; around it, and what the reader came to see is what it connects
             ;; to. The pane is measured now rather than at build time — a
             ;; divider may have moved since.
             (let [w    (.-clientWidth svg-el)
                   h    (.-clientHeight svg-el)
                   near (into [d] (comp (filter (fn [^js l]
                                                  (or (= (.-source l) d) (= (.-target l) d))))
                                        (map (fn [^js l] (if (= (.-source l) d)
                                                           (.-target l) (.-source l)))))
                              (array-seq links))
                   xs   (map (fn [^js n] (.-x n)) near)
                   ys   (map (fn [^js n] (.-y n)) near)
                   pad  70
                   bw   (max 1 (- (apply max xs) (apply min xs)))
                   bh   (max 1 (- (apply max ys) (apply min ys)))
                   k    (max 0.05 (min 1.4 (/ (max 1 (- w pad pad)) bw) (/ (max 1 (- h pad pad)) bh)))
                   cx   (/ (+ (apply max xs) (apply min xs)) 2)
                   cy   (/ (+ (apply max ys) (apply min ys)) 2)]
               (.call svg (.-transform zoom-b)
                      (-> zoom/zoomIdentity
                          (.translate (- (/ w 2) (* k cx)) (- (/ h 2) (* k cy)))
                          (.scale k))))))

         ;; Centring a node in a live simulation is not a single act. The graph
         ;; keeps moving while it settles, and a locate computed one frame is
         ;; wrong the next — which is how a framed node ended up in a corner. The
         ;; viewport re-frames on every tick until the motion stops, and then
         ;; hands control back so a pan is not fought.
         :follow!
         (fn [attr locate!]
           (reset! following {:attr attr :until (+ (.now js/Date) 2500) :locate locate!}))

         ;; Cytoscape kept its viewport in step with its container for us; an SVG
         ;; does not, so a resized pane left the drawing at its old size and the
         ;; simulation pulling towards the old centre.
         :resize!
         (fn [w h]
           (.attr svg "width" w)
           (.attr svg "height" h)
           ;; Re-centre the force but do not re-heat: waking the whole graph
           ;; because a divider moved throws away the arrangement the reader has,
           ;; and it drifts a node that was just located out from under them.
           (.force simulation "center" (force/forceCenter (/ w 2) (/ h 2))))
         ;; Marking the selection is a class, not a rebuild. It used to be a
         ;; dependency of the render effect, which tore the SVG down on every
         ;; click — including the click that ends a drag. d3-drag never saw that
         ;; gesture end, so `event.active` stayed above zero, and from then on
         ;; `if (!event.active) simulation.restart()` never fired: the pin
         ;; followed the pointer and nothing ticked, so the graph looked frozen
         ;; after one or two drags.
         :set-main! (fn [attr]
                      (.classed node "ag-node-main"
                                (fn [^js d] (= (.-attribute d) attr))))}))))

(defui attribute-graph
  "The Pathom Viz index graph. `focus` marks the main node; `theme` is a
   dependency only because a repaint has to follow a palette change."
  [{:keys [graph focus on-select on-hover-edge theme connect? on-connect fit-request]}]
  (let [box     (uix/use-ref nil)
        svg     (uix/use-ref nil)
        api     (uix/use-ref nil)
        handler (uix/use-ref nil)]
    (reset! handler {:on-select on-select :on-hover-edge on-hover-edge
                     :connect? connect? :on-connect on-connect})
    (uix/use-effect
     (fn []
       (when (and @svg @box (seq (:nodes graph)))
         (let [rect (.getBoundingClientRect @box)
               r    (render! @svg
                             (build-data graph nil)
                             (.-width rect)
                             (.-height rect)
                             {:on-select     (fn [a] (when-let [f (:on-select @handler)] (f a)))
                              :on-click-edge (fn [r]
                                               (when-let [f (:on-hover-edge @handler)]
                                                 (f (str/replace r #"\n" ", "))))
                              :connecting?   (fn [] (boolean (:connect? @handler)))
                              :on-connect    (fn [from to]
                                               (when-let [f (:on-connect @handler)]
                                                 (f from to)))})]
           (reset! api r)
           ;; Only stop the simulation. Clearing the SVG here would touch a ref
           ;; React has already nulled on unmount — and that threw, taking the
           ;; whole app down on the way to the Lab. `render!` clears before it
           ;; draws, which is the only place the clearing is needed.
           #(do ((:dispose r)) (reset! api nil)))))
     ;; Deliberately not `focus`: a selection marks a node, it does not rebuild
     ;; the picture. Rebuilding also destroys the element a drag is holding.
     ;; Keyed by the graph's value, not its identity (owner, 2026-10-04): the
     ;; parent re-derives an equal graph on every status poll, and each new
     ;; object restarted the simulation, so the picture never settled.
     [(hash graph) theme])

    (uix/use-effect
     (fn []
       (when (and @box (exists? js/ResizeObserver))
         (let [ro (js/ResizeObserver.
                   (fn [_]
                     (when-let [{:keys [resize!]} @api]
                       (let [r (.getBoundingClientRect @box)]
                         (resize! (.-width r) (.-height r))))))]
           (.observe ro @box)
           #(.disconnect ro))))
     [(hash graph)])

    (uix/use-effect
     (fn []
       (when-let [{:keys [set-main! locate! follow!]} @api]
         (set-main! focus)
         (when focus
           (locate! focus)
           (follow! focus locate!)))
       js/undefined)
     [focus (hash graph)])

    (uix/use-effect
     (fn []
       (when (pos? (or fit-request 0))
         (when-let [fit! (:fit! @api)] (fit!)))
       js/undefined)
     [fit-request])

    (if (empty? (:nodes graph))
      ($ :p.empty-note "Select an attribute to see what surrounds it.")
      ($ :div.attr-graph {:ref box :class (when connect? "connecting")}
         ($ :svg {:ref svg :role "img"
                  :aria-label (str "Index graph: " (count (:nodes graph)) " attributes")})))))
