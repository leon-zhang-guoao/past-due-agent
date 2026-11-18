(ns open.atlas.runtime.env.semantic-input-types-test
  "Preview checks entity inputs before querying; the published data plane
   passes them to the engine unchanged. A caller can only spell a date as ISO
   text (EDN and JSON share nothing else), so the check reads the input in its
   declared class, as `:params` already do (contract 2.0 §3.1), and the engine
   still receives the caller's value. A non-ISO spelling is still refused."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]
            [open.atlas.common.contract :as version]
            [open.atlas.runtime.env :as env]))

(def ^:private document
  {:contract version/semantic-v2
   :workspace {:workspace/id "portable"}
   :graph {:graph/id "days" :graph/label "Days" :graph/exposure :restricted
           :graph/entities ["day"] :graph/resolvers ["day"] :graph/operations []
           :graph/terms [] :graph/dependencies []}
   :vocabulary {:entities {"day" {:entity/label "Day" :entity/identity [:day/on]
                                  :entity/attributes [:day/on :day/note :day/amount]}}
                :attributes {:day/on {:attribute/label "Date" :attribute/type :date :attribute/nullable? false}
                             :day/note {:attribute/label "Note" :attribute/type :string :attribute/nullable? true}
                             :day/amount {:attribute/label "Amount" :attribute/type :decimal :attribute/nullable? true}}
                :terms {} :aliases [] :references [] :equivalences []}
   :execution {:resolvers {"day" {:label "Day" :effect :read :input [:day/on]
                                  :output [:day/note] :result/cardinality :one}}
               :operations {}
               :sources {"db" {:source/kind "sql" :source/effects #{:read}}}
               :bindings {"day-sql" {:binding/for [:resolver "day"] :binding/source "db" :binding/effect :read
                                     :binding/implementation {:query "SELECT note FROM days WHERE on_date = :d"}
                                     :binding/input-map {:day/on "d"}
                                     :binding/output-map {:day/note "note"}}}
               :selection {[:resolver "day"] "day-sql"}}
   :ownership {:entities {"day" "days"} :resolvers {"day" "days"} :operations {} :terms {}}
   :publication {:release/version "1" :source/revision 1
                 :required-capabilities #{:semantic/core-v1 :graph/single-owner-v1 :deployment/source-slot-v1
                                          :artifact/embedded-closure-v1 :connector/sql}}})

(deftest a-date-input-may-be-iso-text
  (let [url (str "jdbc:sqlite:file:" (random-uuid) "?mode=memory&cache=shared")]
    (with-open [db (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! db ["CREATE TABLE days (on_date TEXT PRIMARY KEY, note TEXT)"])
      (jdbc/execute! db ["INSERT INTO days VALUES ('2026-09-16', 'kept as text in the source')"])
      (let [candidate (env/for-semantic-document document {:adapters {"sql" (spi/semantic-adapter "sql")}
                                                           :connections {"db" {:type "sql" :jdbc_url url}}})
            ask (fn [entity] (env/query-semantic-candidate candidate {:entity entity :query [:day/note]}))]
        (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
        (let [r (ask {:day/on "2026-09-16"})]
          (is (empty? (:findings r)) (pr-str r))
          (is (= "kept as text in the source" (get-in r [:response :engine/data :day/note]))
              "the engine received the caller's text, so the source matched it"))
        (is (= [:record/type] (mapv :code (:findings (ask {:day/on "16/09/2026"}))))
            "a non-ISO spelling is still refused")
        (is (= [:record/type] (mapv :code (:findings (ask {:day/on "2026-09-16" :day/amount "12.5"}))))
            "text is not a decimal: only numbers convert")))))
