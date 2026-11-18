(ns open.atlas.runtime.obs.subject-test
  "The subject index is what survives rotation, so its failure modes matter more
   than its happy path: an unknown subject, an unwritable anchor and a repeated
   event must all be boring."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.obs.impl.subject :as subject]))

(defn- tmp-root []
  (str (java.nio.file.Files/createTempDirectory
        "obs-subject-" (into-array java.nio.file.attribute.FileAttribute []))))

(def ^:private issue
  {:v 1 :event :token/issue :at "2026-08-31T10:00:00Z" :request-id "r1"
   :outcome :ok :token-id "tok1" :graph "orders"
   :actor {:service {:instance-id "i1"}
           :on-behalf-of {:issuer "admin" :subject "alice"}
           :asserted-by :verified}})

(deftest one-event-is-filed-under-every-subject-it-names
  (let [root (tmp-root)]
    (is (= 3 (subject/append! root issue)) "token, graph and principal")
    (is (= [issue] (subject/for-subject root :token "tok1")))
    (is (= [issue] (subject/for-subject root :graph "orders")))
    (is (= [issue] (subject/for-principal root {:issuer "admin" :subject "alice"})))))

(deftest the-same-event-twice-is-one-record
  (let [root (tmp-root)]
    (subject/append! root issue)
    (subject/append! root issue)
    (is (= 1 (count (subject/for-subject root :token "tok1")))
        "a retried caller must not double-count what happened once")))

(deftest events-come-back-in-a-stable-total-order
  (let [root (tmp-root)
        later (assoc issue :event :token/revoke :at "2026-08-31T11:00:00Z" :request-id "r2")]
    (subject/append! root later)
    (subject/append! root issue)
    (is (= ["r1" "r2"] (mapv :request-id (subject/for-subject root :token "tok1"))))))

(deftest an-unknown-subject-reads-empty-and-scans-nothing-else
  (let [root (tmp-root)]
    (subject/append! root issue)
    (testing "a subject with no events"
      (is (= [] (subject/for-subject root :token "nope"))))
    (testing "an id that is not a legal path segment refuses rather than sanitising"
      (is (= [] (subject/for-subject root :token "../../etc"))))
    (testing "no anchor at all"
      ;; This is the regression that caught us: falling back to the process's
      ;; own directory made the reader return this repo's deps.edn as an audit
      ;; record. Absent means empty, never «whatever is lying around here».
      (is (= [] (subject/for-subject nil :token "tok1"))))))

(deftest an-event-with-no-subject-is-stream-only
  (let [root (tmp-root)]
    (is (= 0 (subject/append! root {:v 1 :event :auth/failed :request-id "r9"})))
    (is (not (.exists (io/file root "audit-index")))
        "nothing filed means no index directory at all")))

(deftest a-write-failure-never-reaches-the-caller
  (let [root (tmp-root)
        blocker (io/file root "audit-index")]
    ;; a FILE where the index directory must go — every write below fails
    (io/make-parents blocker)
    (spit blocker "not a directory")
    (is (= 0 (subject/append! root issue)) "reported as written-nothing, not thrown")))

(deftest a-qualified-name-is-two-segments-not-an-illegal-one
  ;; «sales/app» is the graph identity D3.10 fixed; a path segment may not hold
  ;; a «/». Splitting keeps the name byte-for-byte instead of rewriting it —
  ;; a sanitised name would be a second identity for the same graph (D4.8).
  (let [root (tmp-root)
        e (assoc issue :event :graph/control :graph "sales/app" :token-id nil)]
    (subject/append! root e)
    (is (= [e] (subject/for-subject root :graph "sales/app")))
    (is (= [] (subject/for-subject root :graph "sales"))
        "the workspace half alone is not the graph")))

(deftest the-index-keeps-the-whole-event-including-the-timeline
  ;; owner 裁定 2026-08-31：盘上是完整的，内存环只是最近一批的缓存。
  ;; 时间线是名称与耗时、不含值，所以它和别的字段一样落盘——
  ;; 重启之后详情面板照样打得开。
  (let [root (tmp-root)
        with-trace (assoc issue :resolver-trace {:resolvers [{:resolver "x" :ms 3}]})]
    (subject/append! root with-trace)
    (is (= {:resolvers [{:resolver "x" :ms 3}]}
           (:resolver-trace (first (subject/for-subject root :token "tok1")))))))
