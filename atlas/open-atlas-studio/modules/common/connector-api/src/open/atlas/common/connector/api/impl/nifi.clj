(ns open.atlas.common.connector.api.impl.nifi
  "NiFi 2.11 read-query jobs. Fixed endpoints; never replay/content/flow mutation."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [open.atlas.common.connector.api.impl.request :as request]
            [open.atlas.common.connector.api.impl.rows :as rows]
            [open.atlas.common.connector.api.impl.run :as run]
            [open.atlas.common.connector.api.impl.values :as values]
            [open.atlas.common.connector.spi :as spi]))

(def max-bytes (* 4 1024 1024))
(def event-fields [:id :eventId :flowFileUuid :clusterNodeId :eventType :eventTime :eventTimestamp
                   :componentId :componentName :componentType :groupId :details :relationship
                   :parentUuids :childUuids :fileSizeBytes :eventDuration :lineageDuration])
(def node-fields [:id :type :flowFileUuid :clusterNodeIdentifier :millis :timestamp :eventType
                  :componentType :parentUuids :childUuids])
(def link-fields [:sourceId :targetId :flowFileUuid :millis :timestamp])
(defn- fail! [code & [data]] (throw (ex-info "NiFi evidence query failed" (merge {:code code} data))))
(defn- segment [s]
  (when-not (and (some? s) (re-matches #"[A-Za-z0-9_-]{1,128}" (str s))) (fail! :nifi/identifier))
  (str s))

(defn request-json!
  "Bounded same-source transport. Returns status and parsed body; errors omit body."
  [source {:keys [method path body node timeout-ms max-response-bytes]}]
  (let [cap (min max-bytes (or max-response-bytes max-bytes))
        url (str (str/replace (:base_url source) #"/$" "") path
                 (when node (str "?clusterNodeId=" (segment node))))
        r (request/execute!
           {:method method :url url :json-body body :headers (:headers source)
            :auth (:auth source) :tls (:tls source) :insecure? (:ssl_insecure? source)
            :timeout (or timeout-ms 5000) :raw? true :max-bytes cap
            :ctx {:source-type "nifi" :resolver-id "evidence"} :endpoint-template path
            :stream-sample (fn [^java.io.InputStream input]
                             (let [bytes (.readNBytes input (inc cap))]
                               (when (> (alength bytes) cap) (fail! :nifi/response-limit))
                               {:sample (when (pos? (alength bytes))
                                          (json/parse-string (String. bytes "UTF-8") true))}))})]
    {:status (:status r) :body (:sample r)}))

(defn query!
  "op :provenance/:lineage/:event/:search-options. Injectable transport/clock for
   fixtures. Query jobs are cleaned in finally; cleanup failure is reported."
  ([source op params] (query! source op params {}))
  ([source op params {:keys [request! now sleep! deadline-ms response-bytes]
                     :or {request! request-json! now #(System/currentTimeMillis)
                          sleep! #(Thread/sleep %) deadline-ms 15000 response-bytes max-bytes}}]
   (when-not (#{:provenance :lineage :event :search-options} op) (fail! :nifi/operation))
   (when (seq (remove #{:uuid :event-id :node-id :search-field :search-value :start-date :end-date :max-results :direction} (keys params)))
     (fail! :nifi/parameters))
   (let [end (+ (now) (min 30000 (max 1 deadline-ms)))
         node (when-not (= "standalone" (:node-id params)) (:node-id params))
         _ (when node (segment node))
         warnings (atom [])
         call! (fn [method path body cleanup?]
                 (let [remaining (if cleanup? 1500 (- end (now)))]
                   (when (<= remaining 0) (fail! :nifi/query-timeout))
                   (let [r (request! source {:method method :path path :body body :node node
                                             :timeout-ms (min 5000 remaining) :max-response-bytes response-bytes})]
                     (when-not (<= 200 (:status r 0) 299)
                       (fail! :nifi/http {:status (:status r)}))
                     (:body r))))
         options! #(get-in (call! "GET" "/provenance/search-options" nil false) [:provenanceOptions :searchableFields])]
     (cond
       (= op :search-options) {:fields (options!)}
       (= op :event)
       (do (when-not (and (integer? (:event-id params)) (<= 0 (:event-id params))) (fail! :nifi/event-id))
           (let [event (:provenanceEvent (call! "GET" (str "/provenance-events/" (:event-id params)) nil false))]
             (when-not (and (map? event) (= (:event-id params) (:eventId event)))
               (fail! :nifi/invalid-response))
             {:event (select-keys event event-fields)}))
       :else
       (let [provenance? (= op :provenance)
             key (if provenance? :provenance :lineage)
             path (if provenance? "/provenance" "/provenance/lineage")
             request (if provenance?
                       (let [field (if (:uuid params) "FlowFileUUID" (:search-field params))
                             value (or (:uuid params) (:search-value params))
                             allowed (set (map :id (options!)))
                             maximum (or (:max-results params) 100)]
                         (when-not (and (allowed field) (string? value) (<= 1 (count value) 256)
                                        (integer? maximum) (<= 1 maximum 1000)) (fail! :nifi/search))
                         (cond-> {:searchTerms {field {:value value :inverse false}} :maxResults maximum :incrementalResults false :summarize false}
                           (:start-date params) (assoc :startDate (:start-date params))
                           (:end-date params) (assoc :endDate (:end-date params))
                           node (assoc :clusterNodeId node)))
                       (let [direction (or (:direction params) "FLOWFILE")]
                         (when-not (#{"FLOWFILE" "PARENTS" "CHILDREN"} direction) (fail! :nifi/direction))
                         (when-not (or (and (= "FLOWFILE" direction) (string? (:uuid params))
                                            (re-matches #"[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}" (:uuid params)))
                                       (and (integer? (:event-id params)) (<= 0 (:event-id params)))) (fail! :nifi/lineage-input))
                         (cond-> {:lineageRequestType direction}
                           (= "FLOWFILE" direction) (assoc :uuid (:uuid params))
                           (:event-id params) (assoc :eventId (:event-id params))
                           node (assoc :clusterNodeId node))))
             submitted (get (call! "POST" path {key {:request request}} false) key)
             id (:id submitted)
             job-path (when id (str path "/" (segment id)))
             result (try
                      (loop [job submitted]
                        (when-not (map? job) (fail! :nifi/invalid-response))
                        (if (:finished job)
                          (let [results (:results job)]
                            (when-not (map? results) (fail! :nifi/invalid-response))
                            results)
                          (do (when-not job-path (fail! :nifi/missing-job-id))
                              (when (>= (now) end) (fail! :nifi/query-timeout))
                              (sleep! (min 250 (max 1 (- end (now)))))
                              (recur (get (call! "GET" job-path nil false) key)))))
                      (finally
                        (when job-path
                          (try (call! "DELETE" job-path nil true)
                               (catch Exception _ (swap! warnings conj :cleanup-failed))))))]
         (if provenance?
           {:query-id id :events (mapv #(select-keys % event-fields) (:provenanceEvents result))
            :oldest-event (:oldestEvent result) :total-count (:totalCount result)
            :limit (:maxResults request) :limit-reached? (>= (count (:provenanceEvents result)) (:maxResults request))
            :errors (vec (:errors result)) :warnings @warnings}
           {:query-id id :nodes (mapv #(select-keys % node-fields) (:nodes result))
            :links (mapv #(select-keys % link-fields) (:links result))
            :errors (vec (:errors result)) :warnings @warnings}))))))

(def parameter-names
  #{"uuid" "event-id" "node-id" "search-field" "search-value" "start-date" "end-date" "max-results" "direction"})

(defn binding-operation [binding]
  (let [implementation (:binding/implementation binding)]
    (cond-> (assoc (select-keys implementation [:operation :defaults :data_path :field_mapping])
                   :type "nifi"
                   :parameter_mapping (into {} (map (fn [[a p]] [(keyword p) a]))
                                            (:binding/input-map binding)))
      (seq (:binding/parameter-map binding))
      (assoc :eql_parameter_mapping
             (into {} (map (fn [[p target]] [(keyword target) p]))
                   (:binding/parameter-map binding))))))

(defn compile-read [source config]
  (fn [input eql-params]
    (let [params (merge (:defaults source)
                        (into {} (map (fn [[p a]] [p (get input a)])) (:parameter_mapping source))
                        (into {} (map (fn [[p source-param]] [p (get eql-params source-param)]))
                              (:eql_parameter_mapping source)))
          params (cond-> params (and (= :provenance (:operation source)) (:limit eql-params))
                   (assoc :max-results (:limit eql-params)))
          bounds (get-in config [:semantic/collection :bounds])
          result (query! source (:operation source) params
                         {:deadline-ms (or (:timeout-ms bounds) 15000)
                          :response-bytes (or (:max-response-bytes bounds) max-bytes)})
          _ (when (seq (:errors result))
              (spi/fail! :source {:source-type "nifi" :resolver-id (:id config)} "NiFi reported incomplete query evidence"))
          _ (when (seq (:warnings result))
              (spi/fail! :source {:source-type "nifi" :resolver-id (:id config)} "NiFi query cleanup did not complete"))
          result (cond-> result
                   (:events result) (update :events #(mapv (fn [event] (assoc event :nodeScope (or (:clusterNodeId event) (:node-id params) "standalone"))) %))
                   (:event result) (update :event #(assoc % :nodeScope (or (:clusterNodeId %) (:node-id params) "standalone"))))
          query-id (:query-id result)
          nodes (mapv #(assoc % :queryId query-id) (:nodes result))
          by-id (into {} (map (juxt :id identity)) nodes)
          result (cond-> result
                   (:nodes result) (assoc :nodes nodes)
                   (:links result) (assoc :links (mapv (fn [i link]
                                                       (assoc link :id (str i) :queryId query-id
                                                              :sourceNode (get by-id (:sourceId link))
                                                              :targetNode (get by-id (:targetId link)))) (range) (:links result))))
          opts {:data-path (:data_path source) :field-mapping (:field_mapping source)}
          mapped (if (= "list" (:result_type config)) (rows/response->rows result opts) (rows/response->single result opts))
          context (into {} (map (fn [[a spec]] [a (if (contains? spec :input) (get input (:input spec)) (:value spec))])) (:semantic/output-context config))]
      ((values/coercer (:semantic/output-types config))
       (run/transform-values
        (cond (nil? mapped) nil
              (vector? mapped) (mapv #(merge % context) mapped)
              :else (merge mapped context))
        (:semantic/output-transforms config) {:source-type "nifi" :resolver-id (:id config)})))))
