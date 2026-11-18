(ns open.atlas.studio.ui.table
  "The Graphs table's pure decisions, extracted from the component that renders
   them — the same reason [[open.atlas.studio.ui.form-logic]] exists: `app.cljs`
   reaches `localStorage` at load time through the theme, so nothing inside it
   can be reached by a plain `cljs.test` run under node.

   Sorting in particular earns the move. The failure it can produce is not a
   wrong order but an exception: `compare` throws across types, so one row whose
   column value came back `nil` while its neighbours' came back numbers takes
   the whole table down mid-render — and a table that breaks while being sorted
   reads as a table that broke."
  (:require [clojure.string :as str]))

(defn display-name [graph]
  (or (:graph/display-name graph) (:graph/name graph) (str (:graph/qualified-name graph))))

(defn graph-sort-value
  "How one column sorts.

   It lives next to the header that offers it so a column cannot end up sorting
   by a neighbour's value, and **every column answers with one type**, including
   for a row that has nothing to say: a graph that was never published, holds
   nothing and is read by nobody is the ordinary case on a fresh workspace, not
   an edge."
  [col g]
  (case col
    :name      (display-name g)
    :state     (name (:graph/state g))
    ;; Not-serving sorts BELOW every served version rather than beside v0: the
    ;; question this column is scanned for is «what is live», and nil-as-0 files
    ;; «nothing is» in the middle of the answer.
    :serving   (or (:graph/serving g) -1)
    :ns        (or (get-in g [:graph/held :namespaces]) 0)
    :attr      (or (get-in g [:graph/held :attributes]) 0)
    :res       (or (get-in g [:graph/held :resolvers]) 0)
    :link      (or (get-in g [:graph/held :links]) 0)
    ;; The register shows one `holds` cell where there were four columns, so it
    ;; needs one number to sort by. The total, not the resolver count: the cell
    ;; shows all four, and sorting a visible sum by one of its parts is the kind
    ;; of quiet mismatch nobody reports and everybody misreads.
    :holds     (reduce + (map #(or (get-in g [:graph/held %]) 0)
                              [:namespaces :attributes :resolvers :links]))
    :consumers (+ (or (:graph/live-keys g) 0) (or (:graph/grants g) 0))
    ;; "" rather than nil, so never-published sorts to one end instead of
    ;; throwing against a timestamp.
    :published (or (:graph/last-cut-at g) "")
    (str (:graph/qualified-name g))))

(defn graph-status
  "Published state is authoritative. Legacy recipe counts do not describe a
   Semantic Contract draft and must never label a serving graph as empty."
  [g]
  (let [serving (:graph/serving g)
        cut (or (:graph/cut-versions g) 0)
        readers (+ (or (:graph/live-keys g) 0) (or (:graph/grants g) 0))]
    (cond
      (= :archived (:graph/state g))
      {:tone :neutral :attention? false :caption "archived"}

      (some? serving)
      {:tone :ok :attention? false
       :caption (str "v" serving " serving"
                     (when (pos? readers)
                       (str " · " readers (if (= 1 readers) " reader" " readers"))))}

      (zero? cut)
      {:tone :neutral :attention? true :caption "never published"}

      :else
      {:tone :warn :attention? true
       :caption (str cut (if (= 1 cut) " version cut" " versions cut") " · nothing serving")})))

(defn rail-order
  "Attention first, then by name.

   Sorted rather than merely coloured because the rail is the only place the
   question «which of these needs me» gets asked, and a dot three screens down
   is not an answer. Stable within each group so the list does not reshuffle
   while somebody is reading it — the two keys are total."
  [graphs]
  (vec (sort-by (juxt #(if (:attention? (graph-status %)) 0 1)
                      display-name)
                graphs)))

(defn archived?
  "Whether a graph is retired. Archiving is a state, not a flavour of active:
   an archived graph cannot be published and is not a thing to pick when you go
   looking for something to work on, so every list that offers choices keeps it
   out of the choices."
  [g]
  (= :archived (:graph/state g)))

(defn rail-split
  "The console rail as two lists: the graphs in use, and the retired ones.

   One list mixed them and sorted archived graphs in among the live ones by
   name, so a retired graph sat between two working ones and had to be read
   before it could be dismissed. They are not the same kind of thing — nothing
   you do to a live graph applies to an archived one except restoring it — so
   they get two lists, and the retired one is closed until asked for."
  [graphs]
  {:live (rail-order (remove archived? graphs))
   :archived (vec (sort-by display-name
                           (filter archived? graphs)))})

(defn matches-filter?
  "Whether a graph survives the table's filter box. Name only, case-insensitive:
   the box sits over a column of qualified names and matching something the
   reader cannot see would make rows appear for no visible reason."
  [needle g]
  (or (str/blank? needle)
      (str/includes? (str/lower-case (str (display-name g) " " (:graph/qualified-name g)))
                     (str/lower-case (str/trim needle)))))
