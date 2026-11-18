(ns open.atlas.common.connector.api.compound-key-test
  "doc 106 §106.14 item 14 (owner 2026-09-07): a compound key over api.

   Two placeholders in one template — `/namespaces/{k8s.pod/namespace}/pods/
   {k8s.pod/name}` — are interpolated from the two `:input` attributes, both
   are seen by `bound-input-attributes` (so E1 knows the read is narrowed by
   both), and a batch's `:parent-key` / `:child-fk` accept vectors and align
   on a ROW VALUE.

   A real socket, like conformance-test, so what reaches the wire is what is
   asserted rather than what a mock was told."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [open.atlas.common.connector.api.impl.interp :as interp]
            [open.atlas.common.connector.spi :as spi])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- start-stub [handler]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (proxy [HttpHandler] []
       (handle [^HttpExchange ex]
         (let [req {:path (.getRawPath (.getRequestURI ex))
                    :query (.getQuery (.getRequestURI ex))}
               _   (swap! requests conj req)
               {:keys [status body] :or {status 200 body "{}"}} (handler req)
               bytes (.getBytes ^String body "UTF-8")]
           (.add (.getResponseHeaders ex) "Content-Type" "application/json")
           (.sendResponseHeaders ex status (alength bytes))
           (with-open [os (.getResponseBody ex)]
             (.write os bytes))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :requests requests}))

(defmacro ^:private with-stub [[binding handler] & body]
  `(let [stub# (start-stub ~handler)
         ~binding stub#]
     (try ~@body
          (finally (.stop ^HttpServer (:server stub#) 0)))))

(def ^:private pod-resolver
  {:id "k8s_pod_by_namespace_and_name"
   :input [:k8s.pod/namespace :k8s.pod/name]
   :output [:k8s.pod/phase]
   :result_type "single"})

(defn- pod-source [port]
  {:type "api" :base_url (str "http://127.0.0.1:" port)
   :method "GET"
   :endpoint "/namespaces/{k8s.pod/namespace}/pods/{k8s.pod/name}"
   :field_mapping {:k8s.pod/phase "$.status.phase"}})

;; ---------------------------------------------------------------------------

(deftest every-placeholder-of-a-compound-key-reaches-the-url
  (with-stub [{:keys [port requests]}
              (fn [_] {:status 200 :body "{\"status\":{\"phase\":\"Running\"}}"})]
    (let [read (spi/compile-source (pod-source port) nil pod-resolver)
          r    (read {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1"} nil)]
      (is (= "Running" (:k8s.pod/phase r)))
      (is (= "/namespaces/kube-system/pods/web-1" (:path (first @requests)))
          "both parts of the key are in the path, each in its own segment"))))

(deftest a-templated-key-value-is-still-one-segment-per-part
  ;; The `{attr}` boundary (item 8) is unchanged by a compound key: each part
  ;; encodes on its own.
  (with-stub [{:keys [port requests]}
              (fn [_] {:status 200 :body "{\"status\":{\"phase\":\"Running\"}}"})]
    (let [read (spi/compile-source (pod-source port) nil pod-resolver)]
      (read {:k8s.pod/namespace "kube system" :k8s.pod/name "web/1"} nil)
      (is (= "/namespaces/kube%20system/pods/web%2F1" (:path (first @requests)))))))

(deftest both-parts-count-as-bound-inputs
  ;; E1 / D5.14 asks «does this operation really narrow by this attribute».
  ;; With a compound key the answer has to be «by both», or a row-security
  ;; declaration on the second part would look unwired.
  (is (= #{:k8s.pod/namespace :k8s.pod/name}
         (interp/bound-input-attributes
          (merge (pod-source 1) {:input [:k8s.pod/namespace :k8s.pod/name]})))))

(deftest a-row-value-batch-aligns-every-parent
  ;; Shape 1 of api batching: one request answers many, and the rows carry the
  ;; echoed key. With a compound key the bucket is the PAIR — grouping on
  ;; either half alone would hand `kube-system/web-1`'s row to
  ;; `default/web-1`.
  (with-stub [{:keys [port]}
              (fn [_]
                {:status 200
                 :body (str "{\"items\":["
                            "{\"ns\":\"default\",\"nm\":\"web-1\",\"phase\":\"Running\"},"
                            "{\"ns\":\"kube-system\",\"nm\":\"web-1\",\"phase\":\"Pending\"}"
                            "]}")})]
    (let [source {:type "api" :base_url (str "http://127.0.0.1:" port)
                  :method "GET" :endpoint "/pods?keys={keys}"
                  :data_path "$.items"
                  :field_mapping {:k8s.pod/namespace "$.ns" :k8s.pod/name "$.nm"
                                  :k8s.pod/phase "$.phase"}
                  :batch {:parent-key [:k8s.pod/namespace :k8s.pod/name]
                          :child-fk ["ns" "nm"]
                          :placeholder "keys"}}
          read (spi/compile-batch-source source nil pod-resolver)
          out  (read [{:k8s.pod/namespace "default" :k8s.pod/name "web-1"}
                      {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1"}
                      {:k8s.pod/namespace "default" :k8s.pod/name "web-2"}]
                     nil)]
      (is (= 3 (count out)) "one slot per parent, always")
      (is (= ["Running" "Pending" nil] (mapv :k8s.pod/phase out))
          "each slot holds the row for ITS pair, not for a matching half"))))

(deftest an-unpaired-batch-declaration-is-refused
  (let [fs (:findings (spi/validate-source
            {:type "api" :base_url "http://127.0.0.1:1" :method "GET"
             :endpoint "/pods?keys={keys}"
             :batch {:parent-key [:k8s.pod/namespace :k8s.pod/name]
                     :child-fk ["ns"]
                     :placeholder "keys"}
             :id "r" :input [:k8s.pod/namespace :k8s.pod/name]
             :output [:k8s.pod/phase] :result_type "single"}))]
    (is (some #(and (= :connector.source/invalid-shape (:code %))
                    (str/includes? (str (:message %)) "same number"))
              fs)
        "half a key groups on half a bucket, and every parent then reads as childless")))
