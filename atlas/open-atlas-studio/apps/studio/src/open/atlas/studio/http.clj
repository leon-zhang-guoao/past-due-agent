(ns open.atlas.studio.http
  "HTTP plumbing over com.sun.net.httpserver — request reading and reply
   writing, nothing else.  Extracted from builder-server so the transport
   idioms live in one place while the handlers keep their domain shape.
   No state, no domain knowledge: everything here takes the exchange and
   plain data."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.nio.charset StandardCharsets]))

(defn reply!
  ([exchange status body content-type]
   (reply! exchange status body content-type {}))
  ([^HttpExchange exchange status body content-type headers]
   (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)
         response-headers (.getResponseHeaders exchange)]
     (.set response-headers "Content-Type" (str content-type "; charset=utf-8"))
     (.set response-headers "Cache-Control" "no-store")
     (doseq [[name value] headers]
       (.set response-headers (str name) (str value)))
     (.sendResponseHeaders exchange status (alength bytes))
     (with-open [out (.getResponseBody exchange)] (.write out bytes)))))

(defn reply-bytes!
  ([exchange status bytes content-type] (reply-bytes! exchange status bytes content-type {}))
  ([^HttpExchange exchange status ^bytes bytes content-type headers]
   (doto (.getResponseHeaders exchange)
     (.set "Content-Type" content-type)
     (.set "Cache-Control" "no-store"))
   ;; Extra headers, same shape `reply!` takes — a download needs
   ;; `Content-Disposition`, and building it into every caller's content-type
   ;; string was the alternative.
   (let [response-headers (.getResponseHeaders exchange)]
     (doseq [[name value] headers]
       (.set response-headers (str name) (str value))))
   (.sendResponseHeaders exchange status (alength bytes))
   (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn reply-edn!
  ([exchange status body] (reply-edn! exchange status body {}))
  ([exchange status body headers]
   (reply! exchange status (pr-str body) "application/edn" headers)))

(defn request-edn [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)]
    (edn/read-string (slurp in))))

(defn request-cookie [^HttpExchange exchange cookie-name]
  (when-let [header (.getFirst (.getRequestHeaders exchange) "Cookie")]
    (some (fn [part]
            (let [[name value] (str/split (str/trim part) #"=" 2)]
              (when (= cookie-name name) value)))
          (str/split header #";"))))
