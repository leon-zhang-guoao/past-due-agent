(ns open.atlas.common.connector.api-kit.impl.fixture
  "The recorded fixture: its shape, its on-disk form, and the key a replayed
   request is matched by.

   Two invariants this ns exists to hold:

   - **No resolved URL is ever stored.** A resolved URL carries the host, the
     tenant prefix and every query value the call was made with. What the
     suite needs is the endpoint TEMPLATE plus the parameter names — that is
     configuration, which the model already writes down — and the placeholder
     values, which are the identities the suite asks for by name anyway.
   - **Deterministic ordering.** Maps are written sorted and exchanges are
     written in a total order, so re-recording an unchanged endpoint produces
     a byte-identical file and a real change shows up as a readable diff."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def fixture-version 1)

(def response-header-allow-list
  "Response headers a fixture keeps. Everything else is dropped: `Set-Cookie`
   and `WWW-Authenticate` carry credentials, `Date`/`Server`/`ETag` carry
   noise that would make every re-record a diff."
  ["Content-Type" "Link" "Retry-After"])

(defn header-kept?
  [h]
  (let [l (str/lower-case (str h))]
    (or (some #(= l (str/lower-case %)) response-header-allow-list)
        (str/starts-with? l "x-ratelimit-"))))

(defn whitelist-headers
  [headers]
  (into (sorted-map)
        (keep (fn [[k v]] (when (header-kept? k) [(str k) (str v)])))
        (or headers {})))

;; ---------------------------------------------------------------------------
;; Endpoint template ↔ request key
;; ---------------------------------------------------------------------------

(defn- decode [s]
  (try (java.net.URLDecoder/decode (str s) "UTF-8") (catch Exception _ (str s))))

(defn split-template
  "An api-v1 `:endpoint` → `{:path \"…\" :query {name template}}`. api-v1 lets
   the endpoint carry its own query string (the SE corpus pins the
   entitlement pairing there), so the parameter NAMES come from two places
   and are collected in one."
  [endpoint]
  (let [[p q] (str/split (str endpoint) #"\?" 2)]
    {:path  p
     :query (into (sorted-map)
                  (for [kv (remove str/blank? (str/split (or q "") #"&"))
                        :let [[k v] (str/split kv #"=" 2)]]
                    [k (or v "")]))}))

(defn param-names
  "Every query-parameter NAME this operation can send, sorted. Names are
   configuration; values are not, and none are read here."
  [operation]
  (vec (sort (distinct (concat (keys (:query (split-template (:endpoint operation))))
                               (map name (keys (:query_params operation))))))))

(defn body-param-names
  "Top-level `:json_body` key NAMES, sorted. A body's values are business
   data; its keys are the model's own spelling."
  [operation]
  (vec (sort (map name (keys (:json_body operation))))))

(defn request-key
  "The key a recorded exchange and a replayed request are compared by:
   method, path, and the decoded query map. Built from a RESOLVED url when
   replaying and from the same function when recording, so the two can never
   drift apart."
  [method url]
  (let [uri   (java.net.URI. (str url))
        q     (.getRawQuery uri)
        pairs (for [kv (remove str/blank? (str/split (or q "") #"&"))
                    :let [[k v] (str/split kv #"=" 2)]]
                [(decode k) (decode (or v ""))])]
    {:method (str/upper-case (name (or method "GET")))
     :path   (.getRawPath uri)
     :query  (into (sorted-map) pairs)}))

(defn origin
  "`scheme://host[:port]` of a resolved url — for the REPLAY's own bookkeeping
   only. It never enters a fixture: a recording carries the template, and the
   origin is the deployment's business (§107.6 cross-origin check)."
  [url]
  (let [u (java.net.URI. (str url))]
    (str (.getScheme u) "://" (.getHost u)
         (when (pos? (.getPort u)) (str ":" (.getPort u))))))

(defn exchange-order
  "Total order for the exchange vector — see the ns docstring."
  [e]
  [(str (get-in e [:match :method])) (str (get-in e [:match :path]))
   (pr-str (get-in e [:match :query])) (str (:status e))])

;; ---------------------------------------------------------------------------
;; On disk
;; ---------------------------------------------------------------------------

(defn fixture-file
  ^java.io.File [dir source-id operation-id]
  (io/file (io/file (str dir) (str source-id)) (str operation-id ".fixture.edn")))

(defn write!
  "Write a fixture map to `<dir>/<source>/<op-id>.fixture.edn`."
  [dir fixture]
  (let [f (fixture-file dir
                        (get-in fixture [:api-kit/provenance :source])
                        (get-in fixture [:api-kit/provenance :operation]))]
    (io/make-parents f)
    (spit f (with-out-str
              (binding [*print-namespace-maps* false
                        pp/*print-right-margin* 100]
                (pp/pprint fixture))))
    (.getPath f)))

(defn read-file [f]
  (assoc (edn/read-string (slurp f)) :api-kit/file (.getPath (io/file f))))

(defn fixture-files
  "Every `*.fixture.edn` under `dir`, sorted by path."
  [dir]
  (->> (file-seq (io/file (str dir)))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".fixture.edn")))
       (sort-by #(.getPath ^java.io.File %))
       vec))

(defn read-dir
  "Every fixture under `dir`, keyed by `[source operation]`."
  [dir]
  (into (sorted-map)
        (map (fn [f]
               (let [fx (read-file f)
                     p  (:api-kit/provenance fx)]
                 [[(:source p) (:operation p)] fx])))
        (fixture-files dir)))
