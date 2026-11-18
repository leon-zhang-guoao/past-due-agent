(ns open.atlas.runtime.obs.audit-test
  (:refer-clojure :exclude [*file*])
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.runtime.obs.impl.audit :as audit]
            [open.atlas.runtime.obs.impl.metrics :as metrics]
            [open.atlas.runtime.obs.impl.ring :as ring]
            [open.atlas.runtime.obs.impl.subject :as subject]
            [open.atlas.runtime.obs.impl.trace :as trace]))

(def ^:dynamic *file* nil)

(use-fixtures :each
  (fn [t]
    (let [f (java.io.File/createTempFile "access" ".jsonl")]
      (.delete f)
      (audit/configure! {:recent 3 :path (.getPath f) :max-bytes (* 1 1024 1024)})
      (audit/clear!)
      (binding [*file* (.getPath f)] (t))
      (.delete f))))

(deftest query-records-event-shape
  (audit/query! {:source "mcp"
                 :user-context {:user/id "jie" :user/roles #{"admin"}}
                 :eql-shape {:entity-keys [:vpo/order_no] :query-keys [:vendor/vend_name]}
                 :eql {:entity {} :query [:vpo/order_no]}
                 :attrs-filtered [:vendor/cost]
                 :status :ok :latency-ms 12})
  (let [e (first (audit/recent))]
    (is (= :access/query (:event e)))
    (is (= 1 (:v e)) "every event says which contract it is")
    (is (= {:subject "jie"} (get-in e [:actor :on-behalf-of])))
    (is (= :ok (:outcome e)))
    (is (= ["vpo" "vendor"] (:entities e)))
    (is (= ["vendor/cost"] (:attrs-filtered e)))
    (is (string? (:at e)))
    (testing "the names-only eql-shape is the logged summary"
      (is (= {:entity-keys [:vpo/order_no] :query-keys [:vendor/vend_name]}
             (:eql-shape e))))
    (testing "the raw :eql is NOT emitted — external tools get a values-free contract"
      (is (nil? (:eql e))))
    (testing "no returned data values are present"
      (is (nil? (:data e)))
      (is (nil? (:result e))))))

(deftest query-records-how-they-authenticated-and-who-it-was-for
  ;; P1-2 (docs/mcp-identity-passthrough.zh.md): the audit line alone must answer
  ;; "how did the caller authenticate" and "whose rows was this run for".
  ;; audit-event-v1 §4 keeps BOTH: with delegation the subject is the person the
  ;; work is for, and whoever pressed the button is :delegated-by.
  (audit/query! {:source "mcp"
                 :user-context {:user/id "gateway-svc" :user/roles #{"service"}
                                :auth/type :oidc :auth/on-behalf-of "alice@corp.com"}
                 :eql-shape {} :status :ok :latency-ms 1})
  (let [e (first (audit/recent))]
    (is (= {:subject "alice@corp.com"} (get-in e [:actor :on-behalf-of]))
        "the work was for alice")
    (is (= {:subject "gateway-svc" :issuer "oidc"} (get-in e [:actor :delegated-by]))
        "and the gateway is who ran it, kept rather than overwritten"))
  (testing "no delegation → the caller IS the subject, and no third slot appears"
    (audit/query! {:source "mcp" :user-context {:user/id "jie"}
                   :eql-shape {} :status :ok :latency-ms 1})
    (let [e (first (audit/recent))]
      (is (= {:subject "jie"} (get-in e [:actor :on-behalf-of])))
      (is (not (contains? (:actor e) :delegated-by)))
      (is (nil? (get-in e [:actor :on-behalf-of :issuer]))
          "an unknown auth type leaves the key out rather than asserting nil"))))

(deftest mutation-records-the-same-principal-chain
  (audit/mutation! {:source "mcp"
                    :user-context {:user/id "gateway-svc" :auth/type :jwt
                                   :auth/on-behalf-of "bob@corp.com"}
                    :mutation 'update_vpo_header :status :ok :latency-ms 1})
  (let [e (first (audit/recent))]
    (is (= :access/mutation (:event e)))
    (is (= {:subject "bob@corp.com"} (get-in e [:actor :on-behalf-of])))
    (is (= {:subject "gateway-svc" :issuer "jwt"} (get-in e [:actor :delegated-by])))))

(deftest recent-bounded-file-keeps-all
  (dotimes [i 5]
    (audit/query! {:source "mcp" :user-context {:user/id (str i)}
                   :eql-shape {} :status :ok :latency-ms 1}))
  (is (= 3 (count (audit/recent))))
  (is (= 5 (count (line-seq (io/reader *file*))))))

(deftest record-stamps-request-id-from-trace
  (binding [trace/*request-id* "rid-123"]
    (audit/record! {:event :query :status :ok}))
  (is (= "rid-123" (:request-id (first (audit/recent))))))

(deftest entity-namespaces-handles-nil
  (is (= [] (audit/entity-namespaces nil))))

(deftest prime-from-file-reloads-history-into-the-ring
  ;; 5.1 follow-up: a restart's empty ring is primed from the jsonl tail.
  (audit/query! {:source "mcp" :user-context {:user/id "a"} :eql-shape {} :status :ok :latency-ms 1})
  (audit/query! {:source "mcp" :user-context {:user/id "b"} :eql-shape {} :status :ok :latency-ms 1})
  (testing "simulate a restart: the ring is empty but the file persists"
    (audit/clear!)
    (is (empty? (audit/recent)))
    (audit/prime-from-file!)
    (is (every? :obs/restored (audit/recent))
        "读回来的盖章：读的人分得清「没有时间线」是本来没有还是留不下")
    (let [subjects (map #(get-in % [:actor :on-behalf-of :subject]) (audit/recent))]
      (is (= 2 (count subjects)) "both persisted events are reloaded")
      (is (= #{"a" "b"} (set subjects)))
      (is (= "b" (get-in (first (audit/recent)) [:actor :on-behalf-of :subject]))
          "newest first preserved")))
  (testing "priming respects the ring cap (3), keeping the most recent"
    (dotimes [i 5] (audit/query! {:source "mcp" :user-context {:user/id (str "u" i)}
                                  :eql-shape {} :status :ok :latency-ms 1}))
    (audit/clear!)
    (audit/prime-from-file!)
    (is (= 3 (count (audit/recent))) "the ring cap bounds the reload")))

(deftest append-failure-is-surfaced-not-silently-swallowed
  ;; A full disk / permission loss used to vanish (append-line! swallowed it and
  ;; the ring still looked healthy). Point the JSONL at an unwritable path: the
  ;; request path stays fail-open (no throw), but the loss is now COUNTED.
  (audit/configure! {:recent 3 :path "/dev/null/nope/access.jsonl" :max-bytes 1024})
  (is (zero? (audit/write-failures)) "counter reset on (re)configure")
  (audit/query! {:source "mcp" :user-context {:user/id "x"}
                 :eql-shape {} :status :ok :latency-ms 1})
  (is (= 1 (audit/write-failures)) "the first failed append is counted")
  (audit/query! {:source "mcp" :user-context {:user/id "y"}
                 :eql-shape {} :status :ok :latency-ms 1})
  (is (= 2 (audit/write-failures)) "subsequent failures keep counting")
  (testing "the in-memory ring stays healthy despite the persistence loss"
    (is (= 2 (count (audit/recent))))))

(deftest the-file-is-the-complete-record-and-the-ring-is-only-the-recent-ones
  ;; owner 裁定 2026-08-31：后台全部落盘，内存里只留最近一批方便翻。
  ;; **没有任何字段是内存独有的**——一条记录在盘上是否完整，
  ;; 不该取决于进程还活着没有。
  (let [trace {:resolvers [{:resolver 'demo_sso_by_id :ms 3 :start 0 :ok? true}]
               :resolver-count 1 :span-ms 3 :slowest 'demo_sso_by_id}]
    (audit/query! {:source "playground"
                   :user-context {:user/id "jie"}
                   :eql-shape {:entity-keys [:cis.sales.sso/order_no] :query-keys []}
                   :status :ok :latency-ms 5
                   :resolver-trace trace})
    (testing "内存环里有它（Activity 的默认来源）"
      (is (= (-> trace
                 (assoc :slowest "demo_sso_by_id")
                 (assoc-in [:resolvers 0 :resolver] "demo_sso_by_id"))
             (:resolver-trace (first (audit/recent))))))
    (testing "盘上也有——时间线是名称与耗时、不含值，所以它照样落盘"
      (let [line (first (line-seq (io/reader *file*)))]
        (is (str/includes? line "resolver-trace"))
        (is (str/includes? line "demo_sso_by_id") "resolver 的名字是名称，不是值")))
    (testing "值仍然一个都不进"
      (let [line (first (line-seq (io/reader *file*)))]
        (is (not (str/includes? line "order_no\":")) "键名进了 eql-shape，实体的值没有")))))

(deftest qualified-generated-resolvers-remain-readable-in-live-and-durable-audit
  (let [resolver (symbol "open.atlas.common.engine.generated" "trdc.resolver/support_matrix_details")
        event {:event :access/query :outcome :ok :graph "test/graph"
               :request-id "qualified-resolver"
               :resolver-trace {:slowest resolver
                                :resolvers [{:resolver resolver :ms 3 :ok? true}]}}
        root (.toFile (java.nio.file.Files/createTempDirectory
                       "audit-edn-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (audit/record! (str root) event)
      (let [live (first (audit/recent))
            persisted (first (subject/for-subject (str root) :graph "test/graph"))]
        (is (= (str resolver) (get-in live [:resolver-trace :slowest])))
        (is (= (str resolver) (get-in live [:resolver-trace :resolvers 0 :resolver])))
        (is (= :ok (:outcome live)))
        (is (= live persisted))
        (is (= {:events [live]} (edn/read-string (pr-str {:events [live]})))))
      (testing "records already in the ring are normalized at read time too"
        (with-redefs [ring/recent (fn [& _] [event])]
          (doseq [events [(audit/recent) (audit/recent 1)]]
            (is (= (str resolver) (get-in events [0 :resolver-trace :slowest])))
            (is (= events (edn/read-string (pr-str events)))))))
      (finally
        (doseq [f (reverse (file-seq root))] (.delete f))))))

;; Audit's choke points must also bump the Prometheus counters.
(deftest rejections-flow-from-audit
  (metrics/reset-all!)
  (audit/rate-limited! {:source :api :user-context {:user/id "u"}})
  (audit/auth-failed!  {:source :mcp})
  (is (= {:rate-limited 1 :unauthorized 1} (metrics/rejections))))
