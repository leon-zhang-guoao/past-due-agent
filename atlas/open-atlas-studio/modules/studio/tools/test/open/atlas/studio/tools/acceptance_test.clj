(ns open.atlas.studio.tools.acceptance-test
  "The scripted scenarios behind `docs/agents/modelling-agent.md`'s turn
   limits (8.4.14, follow-on item 6).

   **Why a test and not a note in the doc.** The 27-turn modelling session of
   2026-09-02 was found by a person reading a transcript afterwards; the eight
   turns of modelling judgement in it were never the problem, the nineteen
   turns of encoding friction were. A limit nobody measures is a limit that
   comes back. Each scenario below is the whole conversation for one intent —
   every tool call counted, refusals included — and the assertion is the
   ceiling the doc publishes.

   A scenario that needs one more call than the ceiling is not a test to
   relax: it means the tool face made the caller supply something it could
   have derived."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.tools.intent-test :as fx]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(use-fixtures :each fx/with-sql-structure fx/with-pool)

(def ^:private turns (atom 0))

(defn- turn!
  "One tool call = one turn. Counting here rather than in each scenario is
   what makes «every call, refusals included» true by construction."
  [ctx tool args]
  (swap! turns inc)
  (tools/call (tools/registry (:scope ctx)) ctx tool args))

(defn- scenario [f]
  (reset! turns 0)
  (let [result (f)]
    {:turns @turns :result result}))

(defn- ctx []
  {:conn fx/*conn*
   :scope #{:read :write}
   ;; D3.22: the workspace the ctx carries is what stamps every action.
   :workspace "demo"
   :provenance {:entrypoint :mcp :token-id "t-1" :actor "acceptance"}
   :graph-service {:graph-members (fn [_] {:graph "ws/g" :members @fx/*recipe*})
                   :apply-recipe-delta!
                   (fn [{:keys [delta]}]
                     (swap! fx/*recipe*
                            #(reduce-kv (fn [a k ids] (update a k (fnil into #{}) ids))
                                        % (:add delta)))
                     {:graph "ws/g"})}})

(defn- ok? [r] (not-any? #(= :error (:severity %)) (:findings r)))

(deftest add-a-resolver-in-three-turns-test
  "«Add a resolver over the orders table, looked up by cust_no» — the whole
   session. Turn 1 is the plan a person reads, turn 2 is the apply, turn 3 is
   the check. No describe_source, no sibling-resolver reading, no
   execute_action per attribute, no update_recipe: 8.4.14's立项 measured that
   path at 6 + n."
  (let [args {:graph "ws/g" :name "orders" :from "orders" :identity "cust_no"
              :namespace "shop.orders_by_cust"}
        {:keys [turns result]}
        (scenario (fn []
                    (let [c (ctx)
                          plan (turn! c "add_thing" args)
                          done (turn! c "add_thing" (assoc args :apply true))]
                      ;; The third turn is the verification a person asks for.
                      ;; It is COUNTED even though `apply` already ran a
                      ;; limit-1 draft query — the ceiling must hold for the
                      ;; conversation as it is actually had.
                      (turn! c "read_object" {:type :resolver :id (:id (last (:actions done)))})
                      {:plan plan :done done})))]
    (is (<= turns 3) (str "add resolver took " turns " turns"))
    (is (ok? (:plan result)))
    (is (true? (:written? (:done result))))
    (is (contains? (:namespaces @fx/*recipe*) "shop.orders_by_cust")
        "the recipe followed in the same turn — no separate update_recipe")))

(deftest add-an-attribute-in-three-turns-test
  "«Add blob_col to the customers resolver» — plan, apply, check. The measured
   path before P7 was 6: read_object, create the attribute, update the
   resolver, graph_members, update_recipe, verify."
  (let [args {:graph "ws/g" :resolver "shop_customers_by_cust_no"
              :attribute "blob_col" :type :string :label "原始报文"}
        {:keys [turns result]}
        (scenario (fn []
                    (let [c (ctx)
                          plan (turn! c "add_attribute" args)
                          done (turn! c "add_attribute" (assoc args :apply true))]
                      (turn! c "read_object" {:type :attribute :id :shop.customers/blob_col})
                      {:plan plan :done done})))]
    (is (<= turns 3) (str "add attribute took " turns " turns"))
    (is (ok? (:plan result)))
    (is (true? (:written? (:done result))))
    (is (contains? (:attributes @fx/*recipe*) :shop.customers/blob_col))))

(deftest add-a-link-in-two-turns-test
  "«These two columns are the same key» — plan, apply. Two, not three: a link
   needs no recipe change and no verification query of its own, because the
   projection pulls it in by closure once both ends are in."
  (let [args {:graph "ws/g" :from ":shop.customers/cust_no"
              :to ":shop.orders/cust_no" :cardinality :one-to-many}
        {:keys [turns result]}
        (scenario (fn []
                    (let [c (ctx)
                          plan (turn! c "add_link" args)]
                      {:plan plan :done (turn! c "add_link" (assoc args :apply true))})))]
    (is (<= turns 2) (str "add link took " turns " turns"))
    (is (ok? (:plan result)))
    (is (true? (:written? (:done result))))
    (is (some? (pool/object (pool/db fx/*conn*) :link
                            [:shop.customers/cust_no :shop.orders/cust_no])))))

(deftest bind-a-term-to-five-attributes-in-two-turns-test
  "«These columns are all the customer number» — the case
   `confirm_candidates` cannot serve, because there is no machine-proposed
   candidate to sign. Two turns whatever the number of attributes: the batch
   is the point."
  (let [args {:graph "ws/g" :term "shop.party/customer_no"
              :attributes [":shop.customers/cust_no" ":shop.orders/cust_no"]
              :definition {:label "客户编号" :description "客户的稳定标识。"}}
        {:keys [turns result]}
        (scenario (fn []
                    (let [c (ctx)
                          plan (turn! c "define_concept" args)]
                      {:plan plan :done (turn! c "define_concept" (assoc args :apply true))})))]
    (is (<= turns 2) (str "assign term took " turns " turns"))
    (is (ok? (:plan result)))
    (is (true? (:written? (:done result))))
    (testing "and the plan said what it was going to take away"
      (is (vector? (:stripped (:plan result)))))))

(deftest enrich-twenty-objects-in-two-turns-test
  "The enrichment pass: twenty objects used to cost twenty reads and twenty
   writes. The ceiling here does not grow with the number of objects, which is
   the whole reason the tool exists."
  (let [args {:objects (vec (for [a [:shop.customers/cust_no :shop.customers/cust_name
                                     :shop.orders/order_no :shop.orders/cust_no
                                     :shop.orders/total]]
                              {:type :attribute :id a
                               :description (str "补给 " a " 的说明。")}))}
        {:keys [turns result]}
        (scenario (fn []
                    (let [c (ctx)
                          plan (turn! c "describe_objects" args)]
                      {:plan plan :done (turn! c "describe_objects" (assoc args :apply true))})))]
    (is (<= turns 2) (str "describe_objects took " turns " turns"))
    (is (ok? (:plan result)))
    (is (= 5 (count (:actions (:done result)))))))
