(ns open.atlas.common.semantic-protocol
  "Wire contract validation. Authentication and business transactions are owned
   by the host; shape validation never establishes identity or permissions."
  (:require [malli.core :as m]
            [clojure.walk :as walk]))

(def id [:string {:min 1}])
(def attribute [:fn qualified-keyword?])
(def path [:vector [:or attribute nat-int?]])
(defn- query-tree? [query depth]
  (and (<= depth 64) (vector? query)
       (every? (fn [node]
                 (or (qualified-keyword? node)
                     (and (seq? node) (= 2 (count node)) (qualified-keyword? (first node))
                          (map? (second node)) (every? keyword? (keys (second node))))
                     (and (map? node) (= 1 (count node))
                          (let [[k v] (first node)]
                            (and (or (qualified-keyword? k)
                                     (and (vector? k) (= 2 (count k)) (qualified-keyword? (first k)))
                                     (and (seq? k) (= 2 (count k)) (qualified-keyword? (first k))
                                          (map? (second k)) (every? keyword? (keys (second k)))))
                                 (query-tree? v (inc depth))))))) query)))
(def query-request-schema
  [:map {:closed true} [:graph/id id] [:request/id id]
   [:entity {:optional true} [:map-of attribute :any]]
   [:query [:fn #(query-tree? % 0)]]
   [:lenient? {:optional true} :boolean]
   [:cursors {:optional true} [:map-of path id]]])
(def issue-schema
  [:map {:closed true} [:path path]
   [:status [:enum :denied :not-found :source-error :invalid :conflict :unknown]]
   [:message id] [:retryable? {:optional true} :boolean]])
(def page-schema
  [:map {:closed true} [:limit pos-int?] [:has-more? :boolean]
   [:next-cursor [:maybe id]]])
(def query-response-schema
  [:map {:closed true}
   [:query/status [:enum :ok :partial :rejected]]
   [:data [:map-of attribute :any]]
   [:issues [:vector issue-schema]]
   [:pagination [:map-of path page-schema]]])
(def operation-request-schema
  [:map {:closed true}
   [:operation/id id] [:request/id id] [:idempotency/key id]
   [:input [:map-of attribute :any]]
   [:expected/revision {:optional true} [:or nat-int? id]]
   [:confirmation {:optional true}
    [:map {:closed true} [:draft/id id] [:draft/revision [:or nat-int? id]]]]])
(def operation-response-schema
  [:map {:closed true}
   [:operation/id id] [:request/id id]
   [:operation/status [:enum :committed :rejected :conflict :unknown]]
   [:receipt/id {:optional true} id]
   [:result {:optional true} [:map-of attribute :any]]
   [:issues [:vector issue-schema]]])
(def audit-schema
  [:map {:closed true}
   [:event/id id] [:request/id id] [:operation/id id]
   [:graph/id id] [:record/id id] [:record/revision [:or nat-int? id]]
   [:at [:fn #(and (string? %) (try (java.time.Instant/parse %) true (catch Exception _ false)))]]
   [:user [:map {:closed true} [:issuer id] [:subject id]]]
   [:agent/id id]
   [:change [:map {:closed true} [:fields [:vector attribute]] [:revision/ref id]]]
   [:provenance [:map {:closed true}
                 [:ai-generated? :boolean]
                 [:confirmation/status [:enum :not-required :pending :confirmed]]
                 [:evidence/refs [:vector id]]]]])

(def grant-schema
  [:map {:closed true} [:graph/id id]
   [:principal [:map {:closed true} [:kind [:enum :user :group]] [:issuer id] [:subject id]]]
   [:read? :boolean] [:operations [:set id]]])

(defn validate-grant
  "Validate a live grant record, not an authorization decision. Grants never
   enter a semantic Artifact and must be evaluated against verified principals."
  [grant]
  (if-let [explanation (m/explain grant-schema grant)]
    {:findings (mapv (fn [e] {:code :protocol/schema :severity :error :path (:in e)}) (:errors explanation))}
    {:grant grant :findings []}))

(defn- fail [code path] {:code code :severity :error :path path})
(defn- shapes [schema value]
  (mapv #(fail :protocol/schema (:in %)) (:errors (m/explain schema value))))

(defn validate-query-response [response]
  (let [base (shapes query-response-schema response)]
    (if (seq base) {:findings base}
      (let [status (:query/status response)
            issues (:issues response)
            fs (concat
                (when (or (and (= :ok status) (seq issues))
                          (and (#{:partial :rejected} status) (empty? issues))
                          (and (= :rejected status) (seq (:data response))))
                  [(fail :protocol/query-status [:query/status])])
                (for [[i issue] (map-indexed vector issues)
                      :when (and (= :denied (:status issue))
                                 (or (not= "not permitted" (:message issue)) (:retryable? issue)))]
                  (fail :protocol/denial [:issues i]))
                (when (not= (count issues) (count (distinct (map :path issues))))
                  [(fail :protocol/duplicate-issue [:issues])])
                (for [[i issue] (map-indexed vector issues)
                      :let [sentinel (Object.)]
                      :when (not (identical? sentinel (get-in (:data response) (:path issue) sentinel)))]
                  (fail :protocol/issue-data-conflict [:issues i]))
                (for [[p page] (:pagination response)
                      :when (or (not= (:has-more? page) (some? (:next-cursor page)))
                                (not (vector? (get-in (:data response) p)))
                                (> (count (get-in (:data response) p)) (:limit page)))]
                  (fail :protocol/page [:pagination p])))]
        (if (seq fs) {:findings (vec fs)} {:response response :findings []})))))

(defn validate-operation-request [request]
  (let [fs (shapes operation-request-schema request)]
    (if (seq fs) {:findings fs} {:request request :findings []})))

(defn validate-query-request [request]
  (let [fs (shapes query-request-schema request)]
    (if (seq fs) {:findings fs} {:request request :findings []})))

(defn validate-operation-response [response]
  (let [base (shapes operation-response-schema response)]
    (if (seq base) {:findings base}
      (let [committed? (= :committed (:operation/status response))
            invalid? (if committed?
                       (or (not (:receipt/id response)) (seq (:issues response)))
                       (or (contains? response :result) (contains? response :receipt/id)
                           (empty? (:issues response))))]
        (if invalid? {:findings [(fail :protocol/write-status [:operation/status])]}
            {:response response :findings []})))))

(defn validate-audit-event [event]
  (let [fs (shapes audit-schema event)]
    (if (seq fs) {:findings fs} {:event event :findings []})))


(defn wire-value
  "Encode internal Java time scalars as ISO strings at transport boundaries.
   Internal validation still sees Instant/LocalDate; no JVM object tags escape."
  [value]
  (walk/postwalk (fn [v] (if (or (instance? java.time.Instant v) (instance? java.time.LocalDate v)) (str v) v)) value))
