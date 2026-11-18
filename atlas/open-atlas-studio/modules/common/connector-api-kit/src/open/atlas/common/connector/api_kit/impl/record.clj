(ns open.atlas.common.connector.api-kit.impl.record
  "The recorder: real calls through the connector's NORMAL path, captured and
   redacted into a replayable fixture.

   Why it drives the connector rather than clj-http directly — a recorder that
   built its own requests would record the requests IT thinks the contract
   makes, and the fixture would then agree with the recorder instead of with
   the connector. Every `${VAR}`, every placeholder, every batch chunk here is
   produced by `impl.run` exactly as a query would produce it; the kit only
   watches the wire.

   What never leaves this ns: the resolved URL, the request headers (they
   carry the Authorization the connector just built), the response headers
   outside the allow-list, and any body string the rules did not keep."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [open.atlas.common.connector.api-kit.impl.fixture :as fx]
            [open.atlas.common.connector.api-kit.impl.redact :as redact]
            [open.atlas.common.connector.spi :as spi]))

(defn- parse-body
  "The response body as data. A body that is not JSON is recorded as a
   length marker: this kit records JSON APIs, and keeping an opaque blob
   would be keeping unredactable content."
  [body]
  (let [s (cond (nil? body)    ""
                (string? body) body
                :else          (slurp (clojure.java.io/reader body)))]
    (try (json/parse-string s true)
         (catch Exception _ (str "<len:" (count s) ">")))))

(defn- token-endpoint? [source-config url]
  (when-let [t (get-in source-config [:auth :token_url])]
    (str/starts-with? (str url) (str/replace (str t) #"\?.*$" ""))))

(defn- materialise
  "A response whose body can be read twice: the connector may have asked for
   `:as :stream` (the probe path), and a recorder that drained that stream
   would hand the connector an empty body."
  [resp]
  (if (string? (:body resp))
    [resp (:body resp)]
    (let [bs (if (nil? (:body resp))
               (byte-array 0)
               (.readAllBytes ^java.io.InputStream (:body resp)))]
      [(assoc resp :body (java.io.ByteArrayInputStream. bs))
       (String. ^bytes bs "UTF-8")])))

(defn- capture!
  "Record one exchange, unless it is the token endpoint (its request carries
   the client credentials and its response IS a credential)."
  [{:keys [source-config operation rules exchanges]} method url _opts resp]
  (let [[resp text] (materialise resp)]
   (when-not (token-endpoint? source-config url)
    (let [body (parse-body text)]
      (swap! exchanges conj
             {:method            (str/upper-case (name method))
              :endpoint          (:path (fx/split-template (:endpoint operation)))
              :query-param-names (fx/param-names operation)
              :body-param-names  (fx/body-param-names operation)
              :match             (fx/request-key method url)
              :status            (:status resp)
              :headers           (fx/whitelist-headers (:headers resp))
              :body              (redact/redact rules body)})))
    resp))

(defn- compiled
  "The connector function this operation is read through — batch or not, as
   the operation block itself says (api-v1: `:batch` implies it, there is no
   second switch)."
  [source-config resolver-config]
  (if (:batch source-config)
    (spi/compile-batch-source source-config nil resolver-config)
    (spi/compile-source source-config nil resolver-config)))

(defn record!
  "Execute `:samples` against the real endpoints and write one fixture file.

   Options:
     :source-id :operation-id   names in the provenance header and the path
     :source-config             source entry MERGED with the operation block
     :resolver-config           the dialect resolver entry (`:input` …)
     :samples                   [{:inputs [..] :eql-params {}}] — a batch
                                operation takes the whole `:inputs` vector,
                                a plain read takes its first map
     :keep-paths :secret-paths :max-string   redaction options
     :out-dir                   fixture root (`<out>/<source>/<op>.fixture.edn`)
     :transport                 optional (fn [method url opts] → response),
                                for recording against something other than the
                                live client; the DEFAULT is the live client

   Returns `{:file … :exchanges n :rules … :failures [..]}`. A sample that
   the upstream refused is recorded as the exchange that happened and its
   failure category is reported — a recorder that swallowed the refusal would
   produce a fixture missing exactly the cases the suite most needs."
  [{:keys [source-id operation-id source-config resolver-config samples out-dir
           keep-paths secret-paths max-string transport]
    :or   {samples [{}] out-dir "fixtures"}}]
  (let [rules     (redact/rules {:keep-paths    keep-paths
                                 :secret-paths  secret-paths
                                 :max-string    max-string
                                 :field-mapping (:field_mapping source-config)})
        exchanges (atom [])
        ctx       {:source-config source-config :operation source-config
                   :rules rules :exchanges exchanges}
        live-get  http/get
        live-post http/post
        send!     (fn [method url opts]
                    (let [resp (if transport
                                 (transport method url opts)
                                 (case method
                                   :get  (live-get url opts)
                                   :post (live-post url opts)))]
                      (capture! ctx method url opts resp)))
        failures  (atom [])]
    (with-redefs [http/get  (fn [url opts] (send! :get url opts))
                  http/post (fn [url opts] (send! :post url opts))]
      (let [f (compiled source-config resolver-config)]
        (doseq [{:keys [inputs eql-params]} samples]
          (try
            (if (:batch source-config)
              (f (vec inputs) (or eql-params {}))
              (f (or (first inputs) {}) (or eql-params {})))
            (catch clojure.lang.ExceptionInfo e
              (swap! failures conj {:category (or (spi/error-category e) :source)}))))))
    (let [fixture {:api-kit/version    fx/fixture-version
                   :api-kit/provenance (into (sorted-map)
                                             {:recorded-at       (str (java.time.Instant/now))
                                              :contract          "api-v1"
                                              :connector-version (spi/contract-version (:type source-config))
                                              :source            (str source-id)
                                              :operation         (str operation-id)
                                              :redaction-rules   (redact/rules-hash rules)})
                   ;; The samples travel WITH the fixture: the generated suite
                   ;; has to know which identities this recording can answer
                   ;; for, and a suite that guessed them from the recorded URLs
                   ;; would guess wrong the first time a key is compound.
                   :api-kit/samples    (vec samples)
                   :api-kit/rules      (into (sorted-map) rules)
                   :api-kit/exchanges  (->> @exchanges distinct (sort-by fx/exchange-order) vec)}]
      {:file      (fx/write! out-dir fixture)
       :exchanges (count (:api-kit/exchanges fixture))
       :rules     rules
       :failures  @failures})))
