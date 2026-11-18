(ns open.atlas.common.connector.spi.statement-audit-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.connector.spi :as spi]))

(deftest diagnostic-fields-are-closed-and-value-free
  (is (= {:count 2 :rows 3
          :by-resolver [{:resolver-id "details" :rows 3 :ok? false :http-status 400 :error/category :source}
                        {:resolver-id "details"}]}
         (spi/statement-audit-summary
           [{:resolver-id "details" :rows 3 :ok? false :http-status 400 :error/category :source
             :sql "private URL" :values ["private id"] :body "private body"}
            {:resolver-id "details" :ok? "private" :http-status "private" :error/category :private
             :row-outcomes [{:path [[:p/id "private"]]}]}]))))

(deftest attempt-and-category-are-carried-and-type-checked
  ;; doc 107 §107.8: an audit reader must be able to tell a clean call from one
  ;; that answered on its third try, and say in which category the earlier ones
  ;; failed. Same discipline as the 2026-09-17 fields -- a counter and a closed
  ;; category only; anything else is dropped rather than carried.
  (is (= {:count 1 :rows 0
          :by-resolver [{:resolver-id "parts" :ok? true :attempt 3 :category :rate_limited}]}
         (spi/statement-audit-summary
           [{:resolver-id "parts" :ok? true :attempt 3 :category :rate_limited
             :retry-after-ms 2000 :url "https://upstream/parts"}])))
  (is (= {:count 1 :rows 0 :by-resolver [{:resolver-id "parts"}]}
         (spi/statement-audit-summary
           [{:resolver-id "parts" :attempt "many" :category :not-a-category}]))
      "a non-integer attempt and an unruled category are dropped, not coerced"))
