(ns open.atlas.server.connector-wiring-test
  "The production basis answers with rows — 8.12 P1's exit condition.

   These two tests exist because of a failure mode that does not announce
   itself. A connector registers into the engine SPI by `defmethod`; off the
   classpath, nothing throws. Every resolver of that source type compiles to a
   warning, is excluded from the index, and the data plane then answers 200
   with an empty result. To an operator that reads as \"the graph has no data\"
   — which is why it was worth an entire section (8.12.3) and why it is
   guarded here rather than left to a smoke test someone runs by hand.

   They must be run on the production basis:

       clojure -M:prod:test-prod

   Run on the default basis they would still pass while proving nothing: `src`
   is on that classpath, and the inherited stack brings connectors of its own.
   The point is not that A connector is registered somewhere in the process —
   it is that the basis the image ships registers them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            ;; The composition root is the assembly under test, required for
            ;; its `defmethod` side effects. Note what is NOT in this list: the
            ;; connector namespaces. Requiring them here would register them
            ;; here, and the first test would then be asserting this file's
            ;; requires rather than the server's — it would stay green through
            ;; exactly the regression it exists to catch.
            [open.atlas.server.main]))

(deftest the-production-basis-registers-every-connector-the-composition-root-claims
  (testing "requiring open.atlas.server.main is the whole of the wiring"
    (doseq [source-type ["sql" "derived" "api"]]
      (is (some? (get-method spi/compile-source source-type))
          (str "source type " (pr-str source-type) " has no SPI implementation on "
               "this basis. Nothing throws when it is missing: resolvers of "
               "this type compile to a warning, are excluded, and /eql then "
               "answers 200 with no rows (8.12.3).")))))

;; --- A real query, over the real demo database ------------------------------
;; Registration is necessary and not sufficient — a registered connector that
;; cannot open the database is the same empty answer. So this reads the
;; committed demo db (fixtures/demo/demo-db/cis-demo.db) through the committed
;; demo model, and asserts a value that is in the seed.

(def ^:private jdbc-url
  ;; Shared-cache in-memory SQLite, built from the committed schema + seed the
  ;; same way modules/integration builds it: `demo-db/cis-demo.db` in the
  ;; fixture is a zero-byte placeholder, the two .sql files are the data.
  ;; Under `:memory:` every pooled connection would be a fresh empty database,
  ;; so the suite holds one keep-alive connection open for the shared cache.
  "jdbc:sqlite:file:atlas_server_prod_smoke?mode=memory&cache=shared")

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn- with-db [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)]
    (try
      (doseq [r ["demo-db/schema.sql" "demo-db/seed.sql"]
              s (statements (slurp (io/resource r)))]
        (jdbc/execute! keep-alive [s]))
      (f)
      (finally
        ;; Resolved rather than required, for the reason in the ns form above.
        ((requiring-resolve 'open.atlas.common.connector.sql/close-all-pools!))
        (.close keep-alive)))))

(use-fixtures :once with-db)

(def ^:private compiled
  (delay
   (let [{m :model fs :findings} (dialect/read-dialect (slurp (io/resource "model.edn")))]
     (assert (some? m) (str "fixtures/demo/model.edn must pass B0, got: " (pr-str fs)))
     (engine/compile-env (assoc-in m [:sources "cis-demo-db" :jdbc_url] jdbc-url)))))

;; The anchor is from fixtures/demo/demo-db/seed.sql, produced deterministically
;; by generate.py — the same row modules/integration reads.
(def ^:private cpo-with-two-msos 210000001)

(deftest the-production-basis-answers-a-real-query-with-real-rows
  (testing "dialect → engine → sql connector → sqlite, on :prod's classpath"
    (let [result (engine/query @compiled
                                 {:graph "demo/b2" :version :latest}
                                 {:entity {:cis.cpo.cpo_header/order_no cpo-with-two-msos}
                                  :query  [:cis.cpo.cpo_header/cpo_cust_no
                                           {:cis.cpo.cpo_header/msos
                                            [:cis.sales.mso/order_no]}]})
            data   (:engine/data result)]
        (is (= :ok (:engine/status result)) (pr-str result))
        (is (= 120001 (:cis.cpo.cpo_header/cpo_cust_no data)))
        (is (= [143100001 143100016]
               (sort (mapv :cis.sales.mso/order_no (:cis.cpo.cpo_header/msos data))))
            "a non-empty answer that had to cross the connector to exist"))))
