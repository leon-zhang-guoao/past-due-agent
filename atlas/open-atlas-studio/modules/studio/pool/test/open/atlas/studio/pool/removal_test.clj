(ns open.atlas.studio.pool.removal-test
  "Taking a namespace back out.

(defn- stamped
  "D3.22: a removal plan names objects; the workspace acting on them comes from
   the caller, so the test supplies it exactly as the app does."
  [actions]
  (mapv #(assoc % :workspace "demo") actions))


   Two things must hold, and neither is obvious from reading the plan: the
   order must be one the pool actually accepts (it refuses to delete anything
   still referred to), and the blast radius must be *complete* — an object left
   behind pointing at a deleted attribute is worse than a refusal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)

(defn with-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)]
      (binding [*conn* conn] (f))
      (pool/close-pool! conn)
      (pool/delete-pool! cfg))))

(use-fixtures :each with-pool)

(defn- create [type id entry]
  {:workspace "demo" :action :create :type type :id id :entry entry :expected_revision 0})

(defn- seed! [actions]
  (let [r (pool/execute-actions *conn* actions)]
    (assert (empty? (filter #(= :error (:severity %)) (:findings r))) (pr-str (:findings r)))
    r))

(def ^:private two-namespaces
  "`pg.dvd.film` and `pg.dvd.actor`, joined by a link and a join attribute,
   plus `other.thing` — a separate namespace whose resolver reaches **into**
   pg.dvd. That last one is the case worth building: it must go too, and it
   must be named as something that was not in the namespace."
  [(create :namespace "pg.dvd.film" {})
   (create :namespace "pg.dvd.actor" {})
   (create :namespace "other.thing" {})
   (create :attribute :pg.dvd.film/film_id {:type :integer :identity? true})
   (create :attribute :pg.dvd.film/title {:type :string})
   (create :attribute :pg.dvd.actor/actor_id {:type :integer :identity? true})
   (create :attribute :pg.dvd.actor/film_id {:type :integer})
   (create :attribute :other.thing/id {:type :integer :identity? true})
   ;; D2.29: the branch that reaches pg.dvd.actor is one join attribute — not a
   ;; navigation plus a collection attribute standing for the same edge.
   (create :attribute :pg.dvd.film/actors
           {:target "pg.dvd.actor" :cardinality :one-to-many})
   (create :link [:pg.dvd.film/film_id :pg.dvd.actor/film_id]
           {:from :pg.dvd.film/film_id :to :pg.dvd.actor/film_id
            :cardinality :one-to-many})
   (create :source "db" {:type "sql" :jdbc_url "${DB_URL}"})
   (create :resolver "pg_dvd_film_by_film_id"
           {:id "pg_dvd_film_by_film_id" :input [:pg.dvd.film/film_id]
            :output [:pg.dvd.film/film_id :pg.dvd.film/title]
            :result_type "single"
            :source {:type "sql" :query "SELECT film_id, title FROM film WHERE film_id = :film_id"}
            :source-ref "db"})
   (create :resolver "pg_dvd_actor_by_actor_id"
           {:id "pg_dvd_actor_by_actor_id" :input [:pg.dvd.actor/actor_id]
            :output [:pg.dvd.actor/actor_id :pg.dvd.actor/film_id]
            :result_type "single"
            :source {:type "sql" :query "SELECT actor_id, film_id FROM actor WHERE actor_id = :actor_id"}
            :source-ref "db"})
   ;; Lives in other.thing, reads pg.dvd.film — the cross-namespace casualty.
   (create :resolver "other_thing_by_id"
           {:id "other_thing_by_id" :input [:other.thing/id]
            :output [:other.thing/id :pg.dvd.film/title]
            :result_type "single"
            :source {:type "sql" :query "SELECT id, title FROM t WHERE id = :id"}
            :source-ref "db"})])

(defn- ids-of [type*]
  (set (map :pool/id (filter #(= type* (:pool/type %)) (pool/objects (pool/db *conn*))))))

;; ---------------------------------------------------------------------------

(deftest the-plan-is-an-order-the-pool-actually-accepts
  ;; The load-bearing one. The pool refuses to delete anything still referred
  ;; to, so a plan in the wrong order is not slightly wrong — it is refused
  ;; partway, and a caller working the order out by hand gets it wrong on the
  ;; first table that has a link.
  (seed! two-namespaces)
  (let [plan (pool/namespace-removal (pool/db *conn*) "pg.dvd")
        r    (pool/execute-actions *conn* (stamped (:actions plan)))]
    (is (empty? (filter #(= :error (:severity %)) (:findings r))) (pr-str (:findings r)))
    (is (= #{"other.thing"} (ids-of :namespace))
        "the pg.dvd namespaces are gone; the untouched one is not")
    (is (= #{:other.thing/id} (ids-of :attribute))
        "and neither is its attribute — only what reached into pg.dvd went")
    (is (empty? (ids-of :link)))
    (is (= #{"db"} (ids-of :source)) "a source is not part of a namespace")))

(deftest an-object-reaching-in-from-outside-goes-too-and-is-named
  ;; A resolver whose :output includes a deleted attribute is not a smaller
  ;; resolver afterwards, it is a broken one. It has to go — and because it was
  ;; not in the namespace, the caller has to be told before agreeing.
  (seed! two-namespaces)
  (let [plan (pool/namespace-removal (pool/db *conn*) "pg.dvd")]
    (is (contains? (set (:objects plan)) [:resolver "other_thing_by_id"]))
    (is (contains? (set (:reaching-in plan)) [:resolver "other_thing_by_id"]))
    (is (not (contains? (set (:reaching-in plan)) [:attribute :pg.dvd.film/title]))
        "what was in the namespace is not reported as reaching into it")
    (testing "and what did not touch it survives"
      (pool/execute-actions *conn* (stamped (:actions plan)))
      (is (contains? (ids-of :namespace) "other.thing"))
      (is (contains? (ids-of :attribute) :other.thing/id)))))

(deftest the-prefix-matches-on-segment-boundaries
  ;; `cis.dv` must not swallow `cis.dvd`, or removing one namespace quietly
  ;; takes a neighbour with it.
  (seed! [(create :namespace "pg.dvd" {})
          (create :namespace "pg.dvdx" {})
          (create :attribute :pg.dvd/a {:type :integer})
          (create :attribute :pg.dvdx/b {:type :integer})])
  (let [plan (pool/namespace-removal (pool/db *conn*) "pg.dvd")]
    (is (contains? (set (:objects plan)) [:attribute :pg.dvd/a]))
    (is (not (contains? (set (:objects plan)) [:attribute :pg.dvdx/b])))))

(deftest removing-one-namespace-under-a-prefix-leaves-its-siblings
  (seed! two-namespaces)
  (let [plan (pool/namespace-removal (pool/db *conn*) "pg.dvd.actor")]
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (contains? (ids-of :namespace) "pg.dvd.film") "the sibling stays")
    (is (contains? (ids-of :attribute) :pg.dvd.film/title))
    (is (not (contains? (ids-of :attribute) :pg.dvd.actor/actor_id)))
    (is (empty? (ids-of :link)) "the link ended in the removed namespace, so it went")))

(deftest a-prefix-that-holds-nothing-is-an-answer-not-a-failure
  (seed! two-namespaces)
  (let [plan (pool/namespace-removal (pool/db *conn*) "nothing.here")]
    (is (= [] (:objects plan)))
    (is (= [] (stamped (:actions plan))))))

(deftest the-plan-writes-nothing-by-itself
  (seed! two-namespaces)
  (let [before (count (pool/objects (pool/db *conn*)))]
    (pool/namespace-removal (pool/db *conn*) "pg.dvd")
    (is (= before (count (pool/objects (pool/db *conn*))))
        "it plans; the caller rules on the list and then executes it")))

;; ---------------------------------------------------------------------------
;; Removing one object — three rules, one per kind
;; ---------------------------------------------------------------------------

(defn- entry-of [type* id]
  (:pool/entry (pool/object (pool/db *conn*) type* id)))

(deftest an-attribute-anything-still-names-is-refused
  ;; Rewriting a resolver to stop mentioning an attribute — quietly, as a side
  ;; effect of deleting that attribute — edits an object whose author did not
  ;; ask for it. Deleting is the caller's to ask for; editing someone else's
  ;; resolver is theirs.
  (seed! two-namespaces)
  (let [plan (pool/object-removal (pool/db *conn*) :attribute :pg.dvd.film/title)]
    (is (empty? (stamped (:actions plan))) "nothing may be removed")
    (is (contains? (set (:blocked-by plan)) [:resolver "pg_dvd_film_by_film_id"]))
    (is (contains? (set (:blocked-by plan)) [:resolver "other_thing_by_id"])
        "including one in another namespace — it is named, not edited"))
  (is (some? (pool/object (pool/db *conn*) :attribute :pg.dvd.film/title))))

(deftest an-attribute-nothing-names-is-removed
  (seed! [(create :namespace "z.thing" {})
          (create :attribute :z.thing/spare {:type :string})])
  (let [plan (pool/object-removal (pool/db *conn*) :attribute :z.thing/spare)]
    (is (empty? (:blocked-by plan)))
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (nil? (pool/object (pool/db *conn*) :attribute :z.thing/spare)))))

(deftest a-link-blocks-an-attribute-just-as-a-resolver-does
  (seed! two-namespaces)
  (let [plan (pool/object-removal (pool/db *conn*) :attribute :pg.dvd.actor/film_id)]
    (is (contains? (set (:blocked-by plan))
                   [:link [:pg.dvd.film/film_id :pg.dvd.actor/film_id]]))
    (is (empty? (stamped (:actions plan))))))

(deftest removing-a-resolver-takes-the-attributes-nothing-else-produces
  ;; Not an extension of the delete — its completion. An attribute no :output
  ;; names is `:cut.attribute/unregistered-use`, which stops the graph being
  ;; cut; leaving it behind hands the modeller a model that will not seal.
  (seed! two-namespaces)
  (let [plan (pool/object-removal (pool/db *conn*) :resolver "pg_dvd_actor_by_actor_id")]
    (is (contains? (set (:cascade plan)) [:attribute :pg.dvd.actor/actor_id]))
    (let [r (pool/execute-actions *conn* (stamped (:actions plan)))]
      (is (empty? (filter #(= :error (:severity %)) (:findings r))) (pr-str (:findings r)))))
  (is (nil? (pool/object (pool/db *conn*) :resolver "pg_dvd_actor_by_actor_id")))
  (is (nil? (pool/object (pool/db *conn*) :attribute :pg.dvd.actor/actor_id))))

(deftest an-attribute-another-resolver-still-produces-survives
  (seed! (conj (vec two-namespaces)
               (create :resolver "pg_dvd_film_titles"
                       {:id "pg_dvd_film_titles" :input [:pg.dvd.film/film_id]
                        :output [:pg.dvd.film/title]
                        :result_type "single"
                        :source {:type "sql" :query "SELECT title FROM film WHERE film_id = :film_id"}
                        :source-ref "db"})))
  (let [plan (pool/object-removal (pool/db *conn*) :resolver "pg_dvd_film_titles")]
    (is (empty? (:cascade plan)) "title is still produced by the by-key resolver")
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (some? (pool/object (pool/db *conn*) :attribute :pg.dvd.film/title)))))

(deftest an-orphan-something-else-still-references-is-left-and-named
  ;; Taking it would break that reference, and following the reference further
  ;; is how one click deletes half a model.
  (seed! two-namespaces)
  (let [plan (pool/object-removal (pool/db *conn*) :resolver "pg_dvd_film_by_film_id")]
    ;; film_id is produced only here, but the link and the actor resolver's
    ;; batch still name it.
    (is (contains? (set (:orphaned plan)) [:attribute :pg.dvd.film/film_id]))
    (is (not (contains? (set (:cascade plan)) [:attribute :pg.dvd.film/film_id])))
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (some? (pool/object (pool/db *conn*) :attribute :pg.dvd.film/film_id))
        "left in place, reported — not silently taken")))

(deftest removing-a-link-has-no-dependants
  (seed! two-namespaces)
  (let [plan (pool/object-removal (pool/db *conn*)
                                  :link [:pg.dvd.film/film_id :pg.dvd.actor/film_id])]
    (is (= 1 (count (stamped (:actions plan)))))
    (is (empty? (:cascade plan)))
    (is (empty? (:blocked-by plan)))
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (empty? (ids-of :link)))))

(deftest a-join-root-is-produced-like-any-other-attribute
  ;; D2.29 puts the branch of an :output tree in the one attribute registry, so
  ;; the resolver that declares it produces it — and takes it when it goes.
  (seed! [(create :namespace "z.thing" {})
          (create :attribute :z.thing/id {:type :integer :identity? true})
          (create :attribute :z.thing/all_things
                  {:target "z.thing" :cardinality :one-to-many})
          (create :source "db3" {:type "sql" :jdbc_url "${U}"})
          (create :resolver "z_all"
                  {:id "z_all" :curated? true :input [] :limit {:default 50 :max 500}
                   :output [{:z.thing/all_things [:z.thing/id]}]
                   :result_type "list"
                   :source {:type "sql" :query "SELECT id FROM t"} :source-ref "db3"})])
  (let [plan (pool/object-removal (pool/db *conn*) :resolver "z_all")]
    (is (contains? (set (:cascade plan)) [:attribute :z.thing/all_things]))
    (pool/execute-actions *conn* (stamped (:actions plan)))
    (is (nil? (pool/object (pool/db *conn*) :attribute :z.thing/all_things)))))
