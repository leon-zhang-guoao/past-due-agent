(ns open.atlas.studio.ui.graph
  "The two graphs, on one palette (ui.theme).

   The index graph follows Pathom Viz's model — attributes are the nodes, a
   resolver hop is an edge — with its three channels: node size is how many
   resolvers produce the attribute, ring thickness is how far it reaches, colour
   is its namespace. The plan graph follows Pathom Viz's plan vocabulary: AND/OR
   branch nodes, sequencing edges against branch edges, and after a Run each node
   coloured by what happened."
  (:require ["cytoscape" :as cytoscape]
            ["cytoscape-dagre" :as cytoscape-dagre]
            [clojure.string :as str]
            [open.atlas.studio.ui.theme :as theme]
            [uix.core :as uix :refer [defui $]]))

(defonce ^:private registered
  (do (cytoscape/use cytoscape-dagre)
      true))

(defn- observe-resize!
  "Keep Cytoscape's viewport in step with its container.

   Cytoscape sizes its canvas once, at construction. When the surrounding layout
   changes the element resizes but the viewport does not, so the drawing appears
   scaled and hit-testing lands somewhere other than what is drawn."
  [^js cy element]
  (if (exists? js/ResizeObserver)
    (let [observer (js/ResizeObserver. (fn [_] (.resize cy)))]
      (.observe observer element)
      #(.disconnect observer))
    (let [on-resize (fn [_] (.resize cy))]
      (.addEventListener js/window "resize" on-resize)
      #(.removeEventListener js/window "resize" on-resize))))

(defn- mount-cytoscape
  "One effect body for both graphs: build, wire, observe, tear down."
  [{:keys [container elements style layout wire! max-zoom]}]
  (let [cy (cytoscape (clj->js (cond-> {:container container
                                        :elements  elements
                                        :style     style
                                        :layout    layout
                                        :minZoom   0.1
                                        :wheelSensitivity 0.2}
                                 max-zoom (assoc :maxZoom max-zoom))))]
    (wire! cy)
    {:cy cy :stop (observe-resize! cy container)}))

;; ---------------------------------------------------------------------------
;; Plan graph
;; ---------------------------------------------------------------------------

(def ^:private plan-palette
  "Two tunings of one vocabulary (ADR-029).

   Neither ground is white, so neither uses Pathom Viz's values as they stand:
   `#00cc00` at full saturation is a lamp on near-black and glare on warm paper,
   and 11px white on top of it is the least legible pairing in the interface
   either way.

   Both sets therefore keep the *hue* of each meaning and move the other two
   axes, in opposite directions. Dark: a deep fill that sits into the ground, a
   border of the same hue at working lightness, a near-white label tinted by it.
   Light: a pale tint that sits on the paper, a border of the same hue at working
   darkness, a deep label of it. The reader still reads green as ok and purple as
   AND on both.

   The hues come from `ui.theme`, which the legend beside the graph already
   draws, so a meaning has one colour rather than two."
  {:light {:resolver ["#e6e0d2" "#8a8072" "#2f2b24"]
           :and      ["#e8dff7" "#8d63c9" "#3e2c5e"]
           :or       ["#d8eff1" "#1d8f97" "#124147"]
           :success  ["#dcebd6" "#2f7d3a" "#1f4a26"]
           :empty    ["#f3e7c8" "#9a6c10" "#4e3708"]
           :error    ["#f5ddd9" "#b3352f" "#5d1c18"]
           :line     "#241f18"
           :branch   "#c56a12"
           :label-bg "#faf7ef"}
   :dark  {:resolver ["#332e25" "#6f6757" "#e6ddcc"]
           :and      ["#3c2c4c" "#a986c6" "#efe4f7"]
           :or       ["#123f47" "#3fa9bb" "#daf0f4"]
           :success  ["#1d4720" "#4f9c52" "#d9efd6"]
           :empty    ["#4c4415" "#a89331" "#f0e8cd"]
           :error    ["#54211f" "#bf5f5b" "#f6dcda"]
           :line     "#cfc3ad"
           :branch   "#e08b33"
           :label-bg "#17140f"}})

(defn- plan-style
  "Pathom Viz's plan vocabulary, from `pathom3/viz/plan.cljs` (MIT, Copyright (c)
   Wilker Lucio da Rocha Silva): purple AND, cyan OR, grey resolver, green/yellow/
   red outcomes, sequencing edges against branch edges, and a root border. Toned
   per ground by `plan-palette` (ADR-029); the focus ring is amber rather than
   Pathom Viz's red, because amber is the operator's own mark everywhere in this
   interface and a red ring beside red error nodes read as a second error."
  []
  (let [p       (get plan-palette (if (theme/dark?) :dark :light))
        kind    (fn [k] (get p k))
        fill    (fn [k] (first (kind k)))
        stroke  (fn [k] (second (kind k)))
        text    (fn [k] (nth (kind k) 2))
        node-of (fn [k]
                  {:background-color (fill k)
                   :border-color     (stroke k)
                   :border-width     1.5
                   :color            (text k)})]
    (clj->js
     [{:selector "node"
       :style (merge {:label "data(label)"
                      ;; 11px white on a saturated fill was the worst type in the
                      ;; interface. Half a point larger, a heavier weight, and a
                      ;; label colour that belongs to its own node.
                      :font-size 11.5
                      :font-family "Plex Mono, ui-monospace, monospace"
                      :font-weight 600
                      :text-valign "center"
                      :text-halign "center"
                      :width "label"
                      :height 28
                      :padding "10px"
                      :shape "round-rectangle"}
                     (node-of :resolver))}
      {:selector "node.node-and" :style (node-of :and)}
      {:selector "node.node-or"  :style (node-of :or)}
      {:selector "node.node-resolver" :style (node-of :resolver)}
      {:selector "node.node-success"      :style (node-of :success)}
      {:selector "node.node-empty-output" :style (node-of :empty)}
      {:selector "node.node-error"        :style (node-of :error)}
      {:selector "node.root"  :style {:border-width 3 :border-color (:line p)}}
      {:selector "node.focus" :style {:border-width 3 :border-color (theme/plan :focus)}}
      {:selector "edge"
       :style {:curve-style "bezier"
               :width 2
               :arrow-scale 0.8
               :target-arrow-shape "triangle"}}
      {:selector "edge.branch"
       :style {:line-color (:branch p) :target-arrow-color (:branch p)}}
      {:selector "edge.next"
       :style {:line-color (:line p) :target-arrow-color (:line p)}}
      ;; Not Pathom Viz's: the graft into a level that ran once per row.
      {:selector "edge.nested"
       :style {:line-style "dashed"
               :label "data(label)"
               :font-size 10
               :font-family "Plex Mono, ui-monospace, monospace"
               :color (theme/ink :muted)
               :text-background-color (:label-bg p)
               :text-background-opacity 0.92
               :text-background-padding 3
               :text-rotation "autorotate"
               :line-color (:line p) :target-arrow-color (:line p)}}])))

(def ^:private plan-layout
  ;; Pathom Viz: `{:name "dagre" :rankDir "LR"}`, nothing else.
  (clj->js {:name "dagre" :rankDir "LR" :fit true :padding 24}))

(defn- plan-elements [{:keys [nodes edges]}]
  (clj->js
   (concat
    (for [{:keys [id kind label root? status]} nodes]
      {:group "nodes"
       :data  {:id (str id) :label label}
       :classes (cond-> (str "node-" (name (or kind :resolver)))
                  (and status (not= :not-run status)) (str " node-" (name status))
                  root? (str " root"))})
    (for [{:keys [from to kind label]} edges]
      {:group "edges"
       :data  {:id (str from "->" to) :source (str from) :target (str to)
               :label (or label "")}
       :classes (name kind)}))))

(defui plan-graph [{:keys [graph focus on-select theme]}]
  (let [container (uix/use-ref nil)
        cy-ref    (uix/use-ref nil)
        handlers  (uix/use-ref nil)]
    (reset! handlers {:on-select on-select})
    (uix/use-effect
     (fn []
       (when (and @container (seq (:nodes graph)))
         (let [{:keys [cy stop]}
               (mount-cytoscape
                {:container @container
                 :elements  (plan-elements graph)
                 :style     (plan-style)
                 :layout    plan-layout
                 ;; fit would otherwise blow a one-node plan up to fill the pane.
                 :max-zoom  1.15
                 :wire!     (fn [^js cy]
                              (.on cy "tap" "node"
                                   (fn [e] (when-let [f (:on-select @handlers)]
                                             (f (.id (.-target e)))))))})]
           (reset! cy-ref cy)
           #(do (stop) (.destroy cy) (reset! cy-ref nil)))))
     ;; `theme` is a dependency because the canvas cannot re-read CSS variables:
     ;; a palette change is a rebuild.
     [graph theme])

    (uix/use-effect
     (fn []
       (when-let [cy @cy-ref]
         (.removeClass (.nodes cy) "focus")
         (when focus
           (let [^js node (.nodes cy (str "[id=\"" focus "\"]"))]
             (when (pos? (.size node))
               (.addClass node "focus")
               (.animate cy #js {:center #js {:eles node}}
                         #js {:duration 260 :easing "ease-out"}))))))
     [focus graph])

    (if (empty? (:nodes graph))
      ($ :p.empty-note "No plan yet — Explain or Run a query.")
      ($ :div.cy {:ref container :role "img"
                  :aria-label (str "Query plan: " (count (:nodes graph)) " steps")}))))

(defui plan-legend
  "The same swatches the graph is painted with, for whichever ground it is on."
  [{:keys [ran? theme]}]
  (let [p (get plan-palette (if (theme/dark?) :dark :light))
        sw (fn [k] {:background (first (get p k)) :border (str "1.5px solid " (second (get p k)))})]
    ($ :div.legend
       (if ran?
         (for [[k label] [[:success "ok"] [:empty "empty"] [:error "error"]]]
           ($ :span {:key label} ($ :i {:style (sw k)}) label))
         (for [[k label] [[:resolver "resolver"] [:and "and"] [:or "or"]]]
           ($ :span {:key label} ($ :i {:style (sw k)}) label)))
       ($ :span ($ :i.line {:style {:border-top-color (:line p)}}) "next")
       ($ :span ($ :i.branch {:style {:border-top-color (:branch p)}}) "branch"))))

(defui plan-table [{:keys [steps]}]
  ($ :table
     ($ :thead ($ :tr ($ :th "#") ($ :th "Step") ($ :th "Input") ($ :th "Output")
                   ($ :th "Next") ($ :th "Outcome")))
     ($ :tbody
        ;; A nested run repeats step numbers at every level, so the row key is the
        ;; qualified id when there is one.
        (for [s steps]
          ($ :tr {:key (or (:id s) (:step s))}
             ($ :td (:step s))
             ($ :td.mono (or (:resolver s) (str (name (or (:kind s) :step)) " branch")))
             ($ :td.mono (str/join ", " (:input s)))
             ($ :td.mono (str/join ", " (:output s)))
             ($ :td (or (:next s) "—"))
             ($ :td (if-let [status (:status s)]
                      ($ :span {:class (str "status-pill " (name status))} (name status))
                      "—")))))))

;; ---------------------------------------------------------------------------
;; Index graph
;; ---------------------------------------------------------------------------

(defn combination-id
  "The graph node for a set of inputs, in the server's spelling
   (`pathom.index-graph/combination-id`). A resolver that needs two attributes
   together sits on the combination, not on either member."
  [input]
  (if (> (count input) 1)
    (str "#{" (str/join " " (sort input)) "}")
    (first input)))

(defn combination? [id] (str/starts-with? (str id) "#{"))

(defn combination-members [id]
  (-> id (subs 2 (dec (count id))) (str/split #" ")))

;; ---------------------------------------------------------------------------
;; The worksheet — a dense, scannable table of whatever kind is selected
;; ---------------------------------------------------------------------------
;;
;; Defining resolvers, terms and links is patient work, and the question a
;; modeller keeps asking is not "what does this one say" but "which of these
;; two hundred is underspecified". A list of truncated names answers neither.
;;
;; So: one row per object, the facts that decide quality laid out in columns,
;; and a defects column that says what is missing **without opening anything**.
;; Sort by it and the work sorts itself. Clicking a row still opens the
;; dossier, so nothing is lost — but most of the scanning no longer needs it.

(defn- attribute-defects
  "Two classes, kept apart on purpose.

   `:blocks` is what a cut will actually **refuse**: a registry entry nothing
   produces is D2.2 drift, and `:type` is required by the schema. `:thin` is
   documentation — real quality, and the thing this model is consumed through,
   but it does not stop a version.

   Collapsing the two would be the easy thing and the wrong one: a signal that
   marks optional fields as faults stops being read at all."
  [a]
  {:blocks (cond-> []
             (nil? (:type a))       (conj "type")
             (empty? (:producers a)) (conj "no producer"))
   :thin   (cond-> []
             ;; A bound term supplies these, and D2.26 forbids the attribute
             ;;from writing them too — so their absence here is correct.
             (and (nil? (:term a)) (str/blank? (:label a)))       (conj "label")
             (and (nil? (:term a)) (str/blank? (:description a))) (conj "descr"))})

(defn- resolver-defects
  [r]
  {:blocks (cond-> []
             (and (= "list" (:result_type r)) (nil? (:limit r))) (conj "no limit")
             ;; `:source-ref` is optional in the contract, and a "derived" or
             ;; "static" resolver computes from its inputs — it has no
             ;; connector to name. Flagging those marked every derived resolver
             ;; in the demo as broken, which is how a quality signal earns
             ;; being ignored. Only a connector-backed resolver needs one.
             (and (#{"sql" "api"} (:source-type r))
                  (str/blank? (:source-ref r)))                  (conj "no source"))
   :thin   (cond-> []
             (str/blank? (:description r)) (conj "descr"))})

(defn- term-defects [t]
  {:blocks []
   :thin (cond-> []
           (str/blank? (:description t))    (conj "descr")
           (zero? (or (:bound-count t) 0))  (conj "unused"))})

(defn- link-defects [l]
  ;; `:cardinality` and `:direction` are optional, and `:bi` is the unwritten
  ;; default (D2.3) — marking their absence would flag every well-formed link
  ;; in the model.
  {:blocks [] :thin (cond-> [] (str/blank? (:description l)) (conj "descr"))})

(defn- source-defects [s]
  {:blocks [] :thin (cond-> [] (empty? (:used-by s)) (conj "unread"))})

(defui qualified
  "A qualified name set so the part that identifies it survives.

   `text-overflow` clips the tail, which is precisely the wrong end: every row
   in a group shares its namespace and differs only in the leaf. So the
   namespace is a separate, dimmed span allowed to shrink and clip, and the
   leaf is never truncated — the same answer the rail already reached."
  [{:keys [value]}]
  (let [s (str value)
        i (str/last-index-of s "/")]
    (if i
      ($ :span.qual
         ($ :span.qual-ns (subs s 0 (inc i)))
         ($ :span.qual-leaf (subs s (inc i))))
      ($ :span.qual-leaf s))))

(defn- cell-text [v]
  (cond (nil? v) "" (keyword? v) (name v) :else (str v)))

(defn- columns-for
  "Column set per kind. Each column is `[key label width align render]`, and the
   render is given the row so a column can be derived rather than stored."
  [kind]
  (case kind
    :resolvers
    [[:id      "resolver"  :wide  :left  #(:id %)]
     [:result_type "type"  :xs    :left  #(:result_type %)]
     [:source-type "conn"  :xs    :left  #(:source-type %)]
     [:source-ref  "source" :mid  :left  #(:source-ref %)]
     [:inputs   "in"       :num   :right #(count (:input %))]
     [:outputs  "out"      :num   :right #(:output-count %)]
     [:params   "params"   :num   :right #(count (:eql-params %))]
     [:defects  "wants"    :flags :left  resolver-defects]]

    :terms
    [[:id      "term"      :wide  :left  #(:id %)]
     [:label   "label"     :mid   :left  #(:label %)]
     [:format  "format"    :xs    :left  #(:format %)]
     [:values  "values"    :num   :right #(count (:values %))]
     [:synonyms "syn"      :num   :right #(count (:synonyms %))]
     [:bound   "bound"     :num   :right #(:bound-count %)]
     [:defects "wants"     :flags :left  term-defects]]

    :links
    [[:from    "from"      :wide  :left  #(:from %)]
     [:to      "to"        :wide  :left  #(:to %)]
     [:direction "dir"     :xs    :left  #(:direction %)]
     [:cardinality "card"  :mid   :left  #(:cardinality %)]
     [:defects "wants"     :flags :left  link-defects]]

    :sources
    [[:id      "source"    :wide  :left  #(:id %)]
     [:type    "type"      :xs    :left  #(:type %)]
     [:label   "label"     :mid   :left  #(:label %)]
     [:used    "read by"   :num   :right #(count (:used-by %))]
     [:creds   "cred keys" :num   :right #(count (:credential-keys %))]
     [:defects "wants"     :flags :left  source-defects]]

    ;; attributes
    [[:attribute "attribute" :wide :left  #(:attribute %)]
     [:type    "type"        :xs   :left  #(:type %)]
     [:format  "format"      :xs   :left  #(:format %)]
     [:unit    "unit"        :xs   :left  #(:unit %)]
     [:term    "term"        :mid  :left  #(:term %)]
     [:producers "prod"      :num  :right #(count (:producers %))]
     [:consumers "cons"      :num  :right #(count (:consumers %))]
     [:defects "wants"       :flags :left attribute-defects]]))

(defn- row-key [kind r]
  (case kind
    :resolvers (:id r) :terms (:id r) :sources (:id r)
    :links (str (:from r) "->" (:to r))
    (:attribute r)))

(defui worksheet
  "One kind, every row, every column that bears on whether it is finished."
  [{:keys [kind rows on-select]}]
  (let [[sort-col set-sort-col!] (uix/use-state nil)
        [asc? set-asc!]          (uix/use-state true)
        cols    (columns-for kind)
        render  (into {} (for [[k _ _ _ f] cols] [k f]))
        ;; Blocking counts for more than thin when sorting by `wants`, so one
        ;; click brings what would actually refuse to the top and the merely
        ;; under-documented after it.
        weigh   (fn [d] (+ (* 10 (count (:blocks d))) (count (:thin d))))
        sorted  (if-let [f (get render sort-col)]
                  (sort-by (fn [r] (let [v (f r)]
                                     (cond (number? v) v
                                           (map? v)    (weigh v)
                                           (coll? v)   (count v)
                                           :else       (str/lower-case (cell-text v)))))
                           (if asc? compare #(compare %2 %1))
                           rows)
                  rows)
        defects (map (get render :defects) rows)
        blocking (count (filter #(seq (:blocks %)) defects))
        thin     (count (filter #(seq (:thin %)) defects))]
    ($ :div.worksheet
       ($ :div.worksheet-summary
          ($ :span ($ :b (str (count rows))) (str " " (name kind)))
          ;; Two figures, because they mean different things: one stops a
          ;; version, the other is documentation debt.
          (when (pos? blocking)
            ($ :span.worksheet-blocking ($ :b (str blocking)) " will refuse"))
          (when (pos? thin)
            ($ :span.worksheet-thin ($ :b (str thin)) " undocumented"))
          ($ :span.worksheet-hint "click a heading to sort · click a row to open it"))
       ($ :table.worksheet-table
          ($ :thead
             ($ :tr
                (for [[k label width _ _] cols]
                  ($ :th {:key (name k)
                          :class (str (name width)
                                      (when (= k sort-col) (if asc? " sorted-asc" " sorted-desc")))
                          :on-click #(if (= k sort-col)
                                       (set-asc! (not asc?))
                                       (do (set-sort-col! k) (set-asc! true)))}
                     label))))
          ($ :tbody
             (for [r sorted]
               ($ :tr {:key (row-key kind r)
                       :on-click #(when on-select (on-select r))}
                  (for [[k _ width align f] cols]
                    (let [v (f r)]
                      ($ :td {:key (name k)
                              :class (str (name width) " " (name align))}
                         (cond
                           (and (= :wide width) (string? v) (str/includes? v "/"))
                           ($ qualified {:value v})

                           (= :flags width)
                           (if (or (seq (:blocks v)) (seq (:thin v)))
                             ($ :span.wants
                                (for [[i w] (map-indexed vector (:blocks v))]
                                  ($ :i.blocks {:key (str "b" i)} w))
                                (for [[i w] (map-indexed vector (:thin v))]
                                  ($ :i.thin {:key (str "t" i)} w)))
                             ($ :span.complete "·"))

                           :else (cell-text v)))))))))
       (when (empty? rows)
         ($ :p.empty-note "Nothing of this kind in scope.")))))


;; ---------------------------------------------------------------------------
;; Relation tables — the worksheet's economy, applied inside a dossier
;; ---------------------------------------------------------------------------
;;
;; A ledger row said "demo_part_by_id  5 out" and left the rest to a click.
;; But the question being asked of a producer list is a comparison — which of
;; these do I want, does one return a list, does one need an anchor I do not
;; have — and a comparison made one click at a time is not a comparison.
;;
;; Same columns, same rules, same density as the worksheet, so a reader who
;; has learned one has learned both.

(defui relations-table
  "Resolvers, as a table you can compare rather than a list you must open."
  [{:keys [title rows resolver-index empty-note on-select]}]
  ($ :div.relations
     ($ :h3.relations-head title
        (when (seq rows) ($ :span.count (str "  " (count rows)))))
     (if (empty? rows)
       ($ :p.none empty-note)
       ($ :table.relations-table
          ($ :thead
             ($ :tr ($ :th "resolver") ($ :th "type") ($ :th.num "in")
                ($ :th.num "out") ($ :th "conn")))
          ($ :tbody
             (for [rid rows]
               (let [r (get resolver-index rid)]
                 ($ :tr {:key rid :on-click #(when on-select (on-select rid r))}
                    ($ :td.wide ($ :span.qual-leaf rid))
                    ($ :td (or (:result_type r) ""))
                    ($ :td.num (str (or (:input-count r) "")))
                    ($ :td.num (str (or (:output-count r) "")))
                    ($ :td (or (:source-type r) "")))))))) ))

(defui attributes-table
  "Attributes with the two facts that decide whether you may have them: their
   type, and their tier.

   The tier belongs here rather than one click away. Reviewing what a resolver
   hands back is a security question as much as a modelling one, and reading a
   `:restricted` output off a list of bare names requires opening every row —
   which is exactly how one gets missed."
  [{:keys [title attrs attribute-index empty-note on-select]}]
  ($ :div.relations
     ($ :h3.relations-head title
        (when (seq attrs) ($ :span.count (str "  " (count attrs)))))
     (if (empty? attrs)
       ($ :p.none empty-note)
       ($ :table.relations-table
          ($ :thead ($ :tr ($ :th "attribute") ($ :th "type")))
          ($ :tbody
             (for [a attrs]
               (let [d (get attribute-index a)]
                 ($ :tr {:key a :on-click #(when on-select (on-select a))}
                    ($ :td.wide ($ qualified {:value a}))
                    ($ :td (if-let [t (:type d)] (name t) ""))))))))))

(defui index-table
  "The blueprint's table view. Edges when nothing else is asked for; otherwise
   the worksheet for the kind the rail is showing."
  [{:keys [graph catalog kind on-select]}]
  (if (or (nil? kind) (= :edges kind))
    ($ :table
       ($ :thead ($ :tr ($ :th "From") ($ :th "To") ($ :th "Via") ($ :th "Nested")))
       ($ :tbody
          (for [e (:edges graph)]
            ($ :tr {:key (str (:from e) "->" (:to e))}
               ($ :td.mono (:from e))
               ($ :td.mono (:to e))
               ($ :td.mono (str/join ", " (:resolvers e)))
               ($ :td (if (:nested? e) "join" "—"))))))
    ($ worksheet
       {:kind kind
        :on-select on-select
        :rows (case kind
                :resolvers (:resolvers catalog)
                :terms     (mapv (fn [t]
                                   (assoc t :bound-count
                                          (count (get (:term-bindings catalog) (:id t) []))))
                                 (:terms catalog))
                :links     (:links catalog)
                :sources   (:sources catalog)
                (:attributes catalog))})))
