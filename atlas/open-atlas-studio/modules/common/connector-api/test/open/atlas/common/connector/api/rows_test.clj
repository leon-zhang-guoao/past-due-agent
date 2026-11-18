(ns open.atlas.common.connector.api.rows-test
  "Response → rows: :data_path location, column-zip, per-item mapping,
   batch grouping and chunking. Connectors hand over ROWS — no {join-parent
   rows} wrapping happens here (that shaping belongs to the engine)."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.impl.rows :as rows]))

(deftest data-path-locates-rows-uniformly
  (let [resp {:envelope {:products [{:sku "A"} {:sku "B"}]}}]
    (is (= [{:p/sku "A"} {:p/sku "B"}]
           (rows/response->rows resp {:data-path "$.envelope.products"
                                      :field-mapping {:p/sku "sku"}})))
    (testing "no :data_path → the response itself is the data"
      (is (= [{:p/sku "A"}]
             (rows/response->rows [{:sku "A"}]
                                  {:field-mapping {:p/sku "sku"}}))))))

(deftest column-zip-for-list-paths
  (let [resp {:data {:items [{:id 1 :n "a"} {:id 2 :n "b"}]}}]
    (is (= [{:x/id 1 :x/n "a"} {:x/id 2 :x/n "b"}]
           (rows/response->rows resp {:field-mapping {:x/id "$.data.items[*].id"
                                                      :x/n  "$.data.items[*].n"}})))))

(deftest single-honors-result-type-over-a-list-answer
  (testing "a list-shaped located value under result_type single → first row"
    (is (= {:p/sku "A"}
           (rows/response->single [{:sku "A"} {:sku "B"}]
                                  {:field-mapping {:p/sku "sku"}}))))
  (is (nil? (rows/response->single nil {:field-mapping {:p/sku "sku"}})))
  (is (nil? (rows/response->single [] {:field-mapping {:p/sku "sku"}}))))

(deftest stamp-input-on-list-rows
  (is (= [{:c/no 7 :p/sku "A"}]
         (rows/stamp-input-on-list-rows [{:p/sku "A"}] {:c/no 7} [:c/no])))
  (testing "the row's own value wins on conflict"
    (is (= [{:c/no 9}]
           (rows/stamp-input-on-list-rows [{:c/no 9}] {:c/no 7} [:c/no])))))

(deftest group-api-items-aligns-to-inputs
  (let [items  [{:sku "A" :d 1} {:sku "B" :d 2} {:sku "A" :d 3}]
        inputs [{:p/sku "B"} {:p/sku "A"} {:p/sku "C"}]
        batch  {:parent-key :p/sku :child-fk "sku"}]
    (is (= [[{:sku "B" :d 2}]
            [{:sku "A" :d 1} {:sku "A" :d 3}]
            []]
           (rows/group-api-items items inputs batch))))
  (testing "dotted :child-fk reaches a nested echoed id"
    (is (= [[{:ids {:pid "A"}}]]
           (rows/group-api-items [{:ids {:pid "A"}}] [{:p/sku "A"}]
                                 {:parent-key :p/sku :child-fk "ids.pid"})))))

(deftest chunked-ids
  (let [inputs (mapv (fn [i] {:p/sku i}) [1 2 2 3 4 nil 5])]
    (is (= [[1 2 3 4 5]]
           (rows/chunked-ids inputs {:parent-key :p/sku})))
    (is (= [[1 2] [3 4] [5]]
           (rows/chunked-ids inputs {:parent-key :p/sku :chunk-size 2})))
    (is (= [] (rows/chunked-ids [] {:parent-key :p/sku})))))

(deftest interpolate-batch-body
  (testing "an exact placeholder leaf becomes the ids ARRAY"
    (is (= {:skus [1 2] :q "in:1,2"}
           (rows/interpolate-batch-body {:skus "{sku_nos}" :q "in:{sku_nos}"}
                                        "sku_nos" [1 2] "1,2"))))
  (is (= 5 (rows/interpolate-batch-body 5 "x" [1] "1"))))

(deftest batch-field-mapping-rewrites-zero-index
  (is (= {:a "$.items[*].id"}
         (rows/batch-field-mapping {:a "$.items[0].id"})))
  (testing "a branch-scoped value is carried through untouched"
    (is (= {:a "$.items[*].id" :b {:c "$.p"}}
           (rows/batch-field-mapping {:a "$.items[0].id" :b {:c "$.p"}})))))

(deftest a-branch-scoped-field-mapping-shapes-the-branch-itself
  "doc 106 §106.14 item 6: a `:field_mapping` value that is a MAP is that
   branch's own scope — the only way a self relation can carry two values
   under one attribute kw."
  (let [fm {:nifi.pg/id "$.id"
            :nifi.pg/parent {:nifi.pg/id "$.component.parentGroupId"}}]
    (is (= [{:nifi.pg/id "g2" :nifi.pg/parent {:nifi.pg/id "g1"}}]
           (rows/response->rows [{:id "g2" :component {:parentGroupId "g1"}}]
                                {:field-mapping fm})))
    (testing "a root group's branch is nil, not a half-filled map"
      (is (= [{:nifi.pg/id "g1" :nifi.pg/parent nil}]
             (rows/response->rows [{:id "g1" :component {}}]
                                  {:field-mapping fm}))))))

;; ---------------------------------------------------------------------------
;; In-record collections (doc 106 §106.14 item 15, owner 2026-09-07)
;; ---------------------------------------------------------------------------

(deftest sibling-collections-at-the-top-level
  (testing "two collections out of ONE call, each naming its own array"
    (let [resp {:servers [{:id "s1"} {:id "s2"}] :users [{:login "ann"}]}
          fm   {:dc/servers {:_from "$.servers[*]" :srv/id "$.id"}
                :dc/users   {:_from "$.users[*]"   :usr/login "$.login"}}]
      (is (= {:dc/servers [{:srv/id "s1"} {:srv/id "s2"}]
              :dc/users   [{:usr/login "ann"}]}
             (rows/response->single resp {:field-mapping fm}))))))

(deftest an-array-inside-a-record
  (let [fm {:host/name "$.name"
            :host/disks {:_from "$.disks[*]" :disk/dev "$.dev" :disk/size "$.size"}}]
    (testing "the record's own leaves stay flat; the branch is already assembled"
      (is (= {:host/name "h1"
              :host/disks [{:disk/dev "sda" :disk/size 10}
                           {:disk/dev "sdb" :disk/size 20}]}
             (rows/response->single {:name "h1"
                                     :disks [{:dev "sda" :size 10}
                                             {:dev "sdb" :size 20}]}
                                    {:field-mapping fm}))))
    (testing "an EMPTY array is an empty collection, not nil"
      (is (= {:host/name "h1" :host/disks []}
             (rows/response->single {:name "h1" :disks []} {:field-mapping fm}))))
    (testing "an ABSENT array is a nil branch"
      (is (= {:host/name "h1" :host/disks nil}
             (rows/response->single {:name "h1"} {:field-mapping fm}))))))

(deftest a-record-plus-its-collection
  (testing "/process-groups/{id}: the group's fields and its processors, one call"
    (let [resp {:id "g1"
                :component {:name "root"}
                :processors [{:component {:id "p1" :name "GenerateFlowFile"}}]}
          fm {:nifi.pg/id "$.id"
              :nifi.pg/name "$.component.name"
              :nifi.pg/processors {:_from "$.processors[*]"
                                   :nifi.proc/id "$.component.id"
                                   :nifi.proc/name "$.component.name"}}]
      (is (= {:nifi.pg/id "g1" :nifi.pg/name "root"
              :nifi.pg/processors [{:nifi.proc/id "p1"
                                    :nifi.proc/name "GenerateFlowFile"}]}
             (rows/response->single resp {:field-mapping fm})))
      (testing "in-record-collection? is what makes the read take the single view"
        (is (rows/in-record-collection? fm))
        (is (not (rows/in-record-collection? {:nifi.pg/id "$.id"})))))))

(deftest nested-two-levels-deep
  (testing "a branch under a branch recurses; a nested array path works too"
    (let [fm {:dc/racks {:_from "$.data.racks[*]"
                         :rack/id "$.id"
                         :rack/hosts {:_from "$.hosts[*]"
                                      :host/name "$.name"}}}]
      (is (= {:dc/racks [{:rack/id "r1"
                          :rack/hosts [{:host/name "h1"} {:host/name "h2"}]}
                         {:rack/id "r2" :rack/hosts []}]}
             (rows/response->single
              {:data {:racks [{:id "r1" :hosts [{:name "h1"} {:name "h2"}]}
                              {:id "r2" :hosts []}]}}
              {:field-mapping fm}))))))

(deftest a-scalar-array-stays-an-attribute-value
  (testing "a leaf path with [*] is a value, not a branch"
    (is (= {:repo/topics ["clj" "edn"]}
           (rows/response->single {:topics ["clj" "edn"]}
                                  {:field-mapping {:repo/topics "$.topics[*]"}})))
    (testing "and per item, inside a collection branch"
      (is (= {:dc/hosts [{:host/tags ["a" "b"]}]}
             (rows/response->single
              {:hosts [{:tags ["a" "b"]}]}
              {:field-mapping {:dc/hosts {:_from "$.hosts[*]"
                                          :host/tags "$.tags[*]"}}}))))))
