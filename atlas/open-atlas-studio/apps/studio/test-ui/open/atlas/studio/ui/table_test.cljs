(ns open.atlas.studio.ui.table-test
  "Column sorting on the Graphs table.

   The failure this pins is not a wrong order — it is `compare` throwing. Two
   rows whose column value came back as different types (a number and a nil, a
   string and a nil) blow up mid-sort, and a table that throws while being
   sorted reads as a table that broke. Every column therefore answers with one
   type, including for rows that have nothing to say."
  (:require [cljs.test :refer [deftest is testing]]
            [open.atlas.studio.ui.table :as table]))

(def ^:private sort-value table/graph-sort-value)

(defn- ordered [col dir gs]
  (let [v #(sort-value col %)]
    (mapv :graph/qualified-name
          (sort (if (= :desc dir) #(compare (v %2) (v %1)) #(compare (v %1) (v %2)))
                gs))))

(def ^:private graphs
  [{:graph/qualified-name "demo/b" :graph/state :active
    :graph/serving 2 :graph/held {:resolvers 5} :graph/live-keys 1 :graph/grants 1
    :graph/last-cut-at "2026-08-30T10:00:00Z"}
   ;; Never published, nothing held, nobody reading: every column's empty case
   ;; in one row.
   {:graph/qualified-name "demo/a" :graph/state :archived}
   {:graph/qualified-name "demo/c" :graph/state :active
    :graph/serving 10 :graph/held {:resolvers 5} :graph/live-keys 0 :graph/grants 4
    :graph/last-cut-at "2026-08-29T10:00:00Z"}])

(deftest every-column-sorts-without-throwing-on-a-row-that-has-nothing
  (doseq [col [:name :state :serving :ns :attr :res :link :holds :consumers :published]]
    (testing (str col)
      (is (= 3 (count (ordered col :asc graphs))))
      (is (= 3 (count (ordered col :desc graphs)))))))

(deftest not-serving-sorts-below-every-served-version
  "`nil` as 0 would file a graph that serves nothing beside one serving v0, and
   the question this column is scanned for is «what is live»."
  (is (= ["demo/a" "demo/b" "demo/c"] (ordered :serving :asc graphs)))
  (is (= ["demo/c" "demo/b" "demo/a"] (ordered :serving :desc graphs))))

(deftest serving-sorts-numerically-not-as-text
  "v10 after v2, which a string compare gets backwards."
  (is (= [-1 2 10] (mapv #(sort-value :serving %) (sort-by #(sort-value :serving %) graphs)))))

(deftest holds-sorts-by-the-total-the-cell-actually-shows
  "The register shows one `holds` cell where there were four columns. Sorting a
   visible sum by one of its parts is the kind of quiet mismatch nobody reports
   and everybody misreads."
  (is (= 5 (sort-value :holds (first graphs))))
  (is (= 0 (sort-value :holds (second graphs))))
  (testing "every part counts, not just resolvers"
    (is (= 15 (sort-value :holds (assoc (first graphs) :graph/held
                                        {:namespaces 2 :attributes 8 :resolvers 4 :links 1}))))))

(deftest consumers-is-keys-plus-grants
  (is (= 0 (sort-value :consumers (second graphs))))
  (is (= 2 (sort-value :consumers (first graphs))))
  (is (= 4 (sort-value :consumers (nth graphs 2))))
  (testing "so the busiest graph sorts to the top of a descending scan"
    (is (= ["demo/c" "demo/b" "demo/a"] (ordered :consumers :desc graphs)))))

(deftest never-published-sorts-to-one-end-rather-than-throwing
  (is (= ["demo/a" "demo/c" "demo/b"] (ordered :published :asc graphs))))

;; ---------------------------------------------------------------------------
;; The console rail's one line per graph
;; ---------------------------------------------------------------------------

(defn- status [g] (table/graph-status g))

(def ^:private healthy
  {:graph/qualified-name "demo/cis" :graph/state :active
   :graph/held {:resolvers 62} :graph/serving 5 :graph/cut-versions 5
   :graph/live-keys 2 :graph/grants 0})

(deftest a-serving-graph-says-what-is-live-and-who-reads-it
  (let [s (status healthy)]
    (is (= :ok (:tone s)))
    (is (false? (:attention? s)))
    (is (= "v5 serving · 2 readers" (:caption s))))
  (testing "singular when there is one"
    (is (= "v5 serving · 1 reader"
           (:caption (status (assoc healthy :graph/live-keys 1))))))
  (testing "and nothing about readers when there are none"
    (is (= "v5 serving"
           (:caption (status (assoc healthy :graph/live-keys 0 :graph/grants 0)))))))

(deftest an-archived-graph-is-not-flagged-for-attention
  "It serves nothing, and that is the point of archiving it. Flagging it would
   train the reader to ignore the dot, which is the only way this device fails."
  (let [s (status (assoc healthy :graph/state :archived :graph/serving nil))]
    (is (= :neutral (:tone s)))
    (is (false? (:attention? s)))
    (is (= "archived" (:caption s))))
  (testing "even with an empty recipe — retirement outranks every other question"
    (is (= "archived"
           (:caption (status (assoc healthy :graph/state :archived
                                    :graph/held {:resolvers 0})))))))

(deftest the-three-states-that-want-a-decision
  (testing "Semantic Contracts can serve without any legacy recipe members"
    (let [s (status (assoc healthy :graph/held {:resolvers 0}))]
      (is (= :ok (:tone s)))
      (is (false? (:attention? s)))
      (is (= "v5 serving · 2 readers" (:caption s)))))
  (testing "never published is a different sentence from no longer serving"
    (let [s (status (assoc healthy :graph/serving nil :graph/cut-versions 0))]
      (is (true? (:attention? s)))
      (is (= "never published" (:caption s)))))
  (testing "cut but not serving: somebody decided something and did not finish"
    (let [s (status (assoc healthy :graph/serving nil :graph/cut-versions 3))]
      (is (= :warn (:tone s)))
      (is (true? (:attention? s)))
      (is (= "3 versions cut · nothing serving" (:caption s))))
    (is (= "1 version cut · nothing serving"
           (:caption (status (assoc healthy :graph/serving nil :graph/cut-versions 1)))))))

(deftest a-graph-with-nothing-recorded-does-not-crash-the-rail
  "The ordinary state of a fresh workspace, not an edge."
  (let [s (status {:graph/qualified-name "demo/new" :graph/state :active})]
    (is (= :neutral (:tone s)))
    (is (= "never published" (:caption s)))))

(deftest the-rail-puts-what-needs-a-decision-first
  "A dot three screens down is not an answer to «which of these needs me»."
  (let [ordered (table/rail-order
                 [healthy
                  (assoc healthy :graph/qualified-name "demo/zed" :graph/serving nil :graph/cut-versions 3)
                  (assoc healthy :graph/qualified-name "demo/arc" :graph/state :archived)
                  (assoc healthy :graph/qualified-name "demo/new"
                         :graph/serving nil :graph/cut-versions 0)])]
    (is (= ["demo/new" "demo/zed" "demo/arc" "demo/cis"]
           (mapv :graph/qualified-name ordered))
        "attention first (alphabetical within), then the rest (alphabetical)")))

(deftest the-rail-keeps-retired-graphs-in-their-own-list
  "Archived graphs are not choices, so they do not sort in among the live ones."
  (let [{:keys [live archived]}
        (table/rail-split
         [healthy
          (assoc healthy :graph/qualified-name "demo/zed" :graph/serving nil :graph/cut-versions 3)
          (assoc healthy :graph/qualified-name "demo/arc" :graph/state :archived)
          (assoc healthy :graph/qualified-name "demo/old" :graph/state :archived)])]
    (is (= ["demo/zed" "demo/cis"] (mapv :graph/qualified-name live))
        "the live list is the rail order, with nothing retired in it")
    (is (= ["demo/arc" "demo/old"] (mapv :graph/qualified-name archived))
        "the retired list is by name — attention does not apply to it")
    (is (true? (table/archived? (assoc healthy :graph/state :archived))))
    (is (false? (table/archived? healthy)))))


(deftest renamed-graphs-are-displayed-and-found-by-the-new-name
  (let [g {:graph/name "old" :graph/qualified-name "demo/old" :graph/display-name "Sales Insights"}]
    (is (= "Sales Insights" (table/display-name g)))
    (is (= "Sales Insights" (table/graph-sort-value :name g)))
    (is (table/matches-filter? "sales" g))
    (is (table/matches-filter? "demo/old" g))
    (is (not (table/matches-filter? "missing" g)))
    (is (= "old" (table/display-name (dissoc g :graph/display-name))))))
