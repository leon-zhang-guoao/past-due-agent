(ns open.atlas.studio.ui.theme
  "One palette, shared by CSS and canvas.

   The graph is drawn to a canvas, so it cannot read CSS variables. Keeping the
   values here — and mirroring them in studio.css — means a colour has one
   definition and one meaning, instead of drifting between the two renderers.

   The rule the palette follows: chrome is achromatic, colour is reserved for
   data. Amber is the operator's own colour (selection, focus, the primary
   action) and appears nowhere else.")

(def palettes
  "Two grounds, one design. The canvas cannot read CSS variables, so the same two
   sets live here and in studio.css; changing a colour means changing both."
  {:dark  {:ground   "#12100e"
           :panel    "#191613"
           :raised   "#201c18"
           :rule     "#2c2721"
           :rule-2   "#3b342c"
           :text     "#ede5d8"
           :muted    "#a99e8a"
           :faint    "#8f8471"
           :accent   "#e3a33c"
           :accent-d "#8a6220"}
   ;; Not the dark theme inverted: paper wants more contrast between ground and
   ;; panel, darker rules to stay visible, and a deeper accent — amber that reads
   ;; on black is washed out on cream.
   :light {:ground   "#eae5d9"
           :panel    "#faf7ef"
           :raised   "#f0ebdd"
           :rule     "#cfc7b4"
           :rule-2   "#b3a891"
           :text     "#1c1814"
           :muted    "#574e43"
           :faint    "#665e4f"
           :accent   "#9c5a06"
           :accent-d "#e0c08a"}})

(def ^:private storage-key "studio.theme")

(defn stored
  "The operator's saved choice, or their system preference. A theme is the only
   thing this app keeps in browser storage — no headers, no queries, no results."
  []
  (or (some-> (try
                (when (and (exists? js/localStorage)
                           (some? (.-getItem js/localStorage)))
                  (.getItem js/localStorage storage-key))
                (catch :default _ nil))
              keyword)
      (if (and (exists? js/matchMedia)
               (.-matches (js/matchMedia "(prefers-color-scheme: light)")))
        :light
        :dark)))

(defonce ^{:doc "The active palette key. Read by the graph renderers, which rebuild
  when it changes.

  Seeded from `stored` rather than defaulting to :dark. `apply!` runs in an
  effect, *after* the first render — and the editor builds its syntax highlighting
  during render, so a workbench opened on the light theme drew its EQL in the dark
  palette's colours: pale blue keywords on paper, which is the one surface that
  cannot afford to be washed out."} current (atom (stored)))

(defn set-current!
  "Set the active palette key, synchronously.

   Separate from `apply!` because of effect ordering: React runs a child's
   effects before its parent's, so an editor reconfiguring its syntax colours in
   an effect read the palette the parent had not set yet — every theme switch
   painted the previous theme's colours. The key is set during the parent's
   render instead, so it is already true by the time any child looks."
  [t]
  (reset! current t)
  t)

(defn dark? [] (= :dark @current))

(defn ink
  "The active ground's ink. A function, not a map, because the theme can change
   while the page is open."
  ([] (get palettes @current))
  ([k] (get-in palettes [@current k])))

(def ^:private status-by-theme
  {:dark  {:success "#6fbf6a" :empty-output "#d8a13c" :error "#e2706b" :not-run "#645d54"}
   :light {:success "#2f7d3a" :empty-output "#9a6c10" :error "#b3352f" :not-run "#9a9080"}})

(defn status
  "Run outcomes. Green/amber/red because that is what they mean everywhere else,
   re-tuned per ground rather than copied across it."
  ([] (get status-by-theme @current))
  ([k] (get-in status-by-theme [@current k])))

(def ^:private plan-by-theme
  {:dark  {:resolver "#8e857a" :and "#b79be8" :or "#63c8ce"
           :root "#ede5d8" :focus "#e3a33c" :next "#ede5d8" :branch "#e8974a"}
   :light {:resolver "#8a8072" :and "#8d63c9" :or "#1d8f97"
           :root "#241f18" :focus "#a86a0c" :next "#241f18" :branch "#c56a12"}})

(defn plan
  "Plan node kinds and edge kinds, following Pathom Viz's vocabulary."
  ([] (get plan-by-theme @current))
  ([k] (get-in plan-by-theme [@current k])))

(def ^:private namespaces-by-theme
  "Ten hues for entity namespaces, in d3's schemeCategory10 order. The dark set is
   lifted (the originals are chosen against white and go muddy on #12100e); the
   light set is close to the originals."
  {:dark  ["#7fb2e5" "#e8974a" "#7bc96f" "#e2706b" "#b79be8"
           "#c79a6b" "#e896c4" "#9a9186" "#cfc44e" "#63c8ce"]
   :light ["#1f77b4" "#d1690a" "#2b8a2b" "#c62d2d" "#7a52b8"
           "#8c564b" "#c9539b" "#6b6355" "#9a9420" "#1d8f97"]})

(defn namespaces [] (get namespaces-by-theme @current))

(defn namespace-color
  "A stable hue per namespace: sorted ordinal assignment, so the same model paints
   the same way on every run."
  [all-namespaces ns']
  (let [ordered (vec (sort (distinct (remove nil? all-namespaces))))
        idx     (.indexOf ordered ns')
        hues    (namespaces)]
    (if (neg? idx)
      (ink :muted)
      (nth hues (mod idx (count hues))))))

(def ^:private syntax-by-theme
  "The editor is the largest coloured surface on either ground, so it gets its own
   scheme rather than the dark one re-used. Light is a technical-annotation
   palette — indigo, forest, sienna — at paper contrast; dark is the cooler set
   that survives on near-black."
  {:dark  {:keyword "#e8a93f" :atom "#8cbcea" :string "#86d17a" :number "#d0a173"
           :comment "#6b6355" :bracket "#968b78" :name "#f0e8d9"}
   ;; Darkened hard for paper. The first set was picked against a swatch and read
   ;; as a wash on screen: an attribute is nearly every glyph in this editor, so
   ;; its colour is the page's body text and has to carry body-text contrast, not
   ;; annotation contrast. Every value here clears 7:1 on the editor's ground.
   ;; Darkened again, and this time the weight goes with it. An attribute is
   ;; nearly every glyph on this surface, so it is not an accent — it is the body
   ;; text of the page, and body text at 12.5px monospace needs stem weight as
   ;; much as it needs contrast. Colour alone was never going to fix a pale
   ;; keyword; 500 does most of the work.
   :light {:keyword "#6b3800" :atom "#0a2f56" :string "#12441a" :number "#5c2905"
           :comment "#7d7565" :bracket "#453d2e" :name "#12100b"}})

(defn syntax
  ([] (get syntax-by-theme @current))
  ([k] (get-in syntax-by-theme [@current k])))

(defn apply!
  "Set the active palette, for both the CSS variables and the canvas."
  [theme]
  (reset! current theme)
  (set! (.. js/document -documentElement -dataset -theme) (name theme))
  (try (.setItem js/localStorage storage-key (name theme)) (catch :default _ nil))
  theme)

(defn darken
  "Mix a hex colour toward the ground by `amount` (0-1)."
  [color amount]
  (let [channel (fn [i]
                  (let [v (js/parseInt (subs color (inc (* i 2)) (+ 3 (* i 2))) 16)]
                    (.padStart (.toString (js/Math.round (* v (- 1 amount))) 16) 2 "0")))]
    (str "#" (channel 0) (channel 1) (channel 2))))
