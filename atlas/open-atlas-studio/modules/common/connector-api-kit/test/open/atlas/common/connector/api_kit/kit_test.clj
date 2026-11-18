(ns open.atlas.common.connector.api-kit.kit-test
  "The kit's own unit tests: the redaction rules, the request matcher, the
   failure overlays and the generator's naming.

   The SE suite proves the kit works end to end; these prove the three places
   where it would fail SILENTLY — a redaction rule that stopped firing, a
   matcher that answered the wrong exchange, and a generator that quietly
   stopped generating a capability."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api-kit :as api-kit]
            [open.atlas.common.connector.api-kit.impl.fixture :as fx]))

;; ---------------------------------------------------------------------------
;; Redaction
;; ---------------------------------------------------------------------------

(def long-text (apply str (repeat 200 \x)))

(def rules
  (api-kit/redaction-rules
   {:field-mapping {:demo.p/id "$.identifiers.id"
                    :demo.p/short "$.descriptions.0.short"
                    :demo.p/rows {:_from "$.rows[*]" :demo.r/name "$.name"}}
    :secret-paths ["$.identifiers.id"]
    :keep-paths ["$.status"]}))

(deftest field-mapping-paths-are-kept-by-default
  (let [r (api-kit/redaction-rules {:field-mapping {:demo.p/short "$.descriptions.0.short"}})
        out (api-kit/redact r {:descriptions [{:short long-text :long long-text}]})]
    (is (= long-text (get-in out [:descriptions 0 :short]))
        "a mapped path keeps its value — the suite asserts on it")
    (is (= "<len:200>" (get-in out [:descriptions 0 :long]))
        "an unmapped long string keeps only its length")))

(deftest a-dotted-index-in-a-mapping-keeps-every-element
  (let [r (api-kit/redaction-rules {:field-mapping {:demo.p/short "$.descriptions.0.short"}})
        out (api-kit/redact r {:descriptions [{:short long-text} {:short long-text}]})]
    (is (= [long-text long-text] (mapv :short (:descriptions out)))
        "recording element 0 must not redact element 1")))

(deftest a-from-branch-keeps-its-leaves
  (let [out (api-kit/redact rules {:rows [{:name long-text :other long-text}]})]
    (is (= long-text (get-in out [:rows 0 :name])))
    (is (= "<len:200>" (get-in out [:rows 0 :other])))))

(deftest a-declared-secret-outranks-a-keep
  (is (= "<secret>" (get-in (api-kit/redact rules {:identifiers {:id "ABC"}})
                            [:identifiers :id]))
      "a `:secret-path` wins even though the field_mapping named it"))

(deftest secret-shaped-strings-are-masked-wherever-they-sit
  (testing "including under a kept path — a leaked token is not a business value"
    (is (= "<email>" (:status (api-kit/redact rules {:status "ops@example.com"}))))
    (is (= "<token>" (:status (api-kit/redact rules {:status "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"}))))
    (is (= "<token>" (:status (api-kit/redact rules {:status "Bearer abcdef0123456789"}))))
    (is (= "<token>" (:status (api-kit/redact rules {:status "${SE_CLIENT_SECRET}"})))
        "an unresolved ${VAR} in a body means the connector shipped it upstream"))
  (testing "ordinary prose is not a secret"
    (is (= "Plain mounting plate H800xW600mm"
           (:status (api-kit/redact rules {:status "Plain mounting plate H800xW600mm"}))))))

(deftest the-rules-hash-moves-when-the-rules-do
  (is (not= (api-kit/redaction-rules-hash rules)
            (api-kit/redaction-rules-hash
             (api-kit/redaction-rules {:field-mapping {} :max-string 8})))
      "a fixture recorded under looser rules must not look like the same fixture"))

(deftest redaction-check-reports-paths-not-values
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "api-kit-check-" (System/nanoTime))))]
    (api-kit/write-fixture!
     dir {:api-kit/provenance {:source "s" :operation "o"}
          :api-kit/exchanges [{:match {:method "GET" :path "/x" :query {}}
                               :status 200
                               :body {:contact "ops@example.com"}}]})
    (let [hits (api-kit/redaction-check dir)]
      (is (= 1 (count hits)))
      (is (= :email (:rule (first hits))))
      (is (= ["contact"] (:path (first hits))))
      (is (not (str/includes? (pr-str hits) "example.com"))
          "the report names the path, never the value"))))

;; ---------------------------------------------------------------------------
;; The matcher
;; ---------------------------------------------------------------------------

(def op
  {:type "api" :base_url "https://fixture.invalid"
   :method "GET"
   :endpoint "/things?tenant=acme&id={demo.thing/id}"
   :data_path "$.items"
   :field_mapping {:demo.thing/id "$.id" :demo.thing/name "$.name"}})

(def fixture
  {:api-kit/provenance {:source "demo" :operation "things"}
   :api-kit/samples [{:inputs [{:demo.thing/id "T-1"}]}]
   :api-kit/exchanges
   [{:method "GET" :endpoint "/things"
     :query-param-names ["id" "tenant"]
     :match {:method "GET" :path "/things" :query {"tenant" "acme" "id" "T-1"}}
     :status 200 :headers {"Content-Type" "application/json"}
     :body {:items [{:id "T-1" :name "first"}]}}]})

(def fixtures {["demo" "things"] fixture})

(defn- call [url & [opts overlays]]
  ((api-kit/replay-responder {:fixtures fixtures :overlays (vec overlays)})
   :get url (or opts {})))

(deftest an-exact-request-is-answered-from-its-exchange
  (is (= 200 (:status (call "https://fixture.invalid/things?tenant=acme&id=T-1")))))

(deftest a-request-differing-only-in-a-placeholder-value-still-matches
  (testing "the isolation split sends id subsets no recording ever saw"
    (is (= 200 (:status (call "https://fixture.invalid/things?tenant=acme&id=T-9"))))))

(deftest an-unmatched-request-refuses-and-names-no-value
  (let [e (try (call "https://fixture.invalid/other?secret-value=hunter2") nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e) "an unmatched request must fail the test, never answer empty")
    (is (= :api-kit.replay/unmatched-request (:code (ex-data e))))
    (is (= "/other" (:path (ex-data e))))
    (is (= ["secret-value"] (:params (ex-data e))) "parameter NAMES are named")
    (is (not (str/includes? (pr-str (ex-data e)) "hunter2"))
        "the value is not in the refusal")))

;; ---------------------------------------------------------------------------
;; Overlays
;; ---------------------------------------------------------------------------

(deftest an-overlay-selects-by-placeholder-value
  (let [f (api-kit/replay-responder
           {:fixtures fixtures
            :overlays [{:when {:placeholder "T-9"} :status 503}]})]
    (is (= 200 (:status (f :get "https://fixture.invalid/things?tenant=acme&id=T-1" {}))))
    (is (= 503 (:status (f :get "https://fixture.invalid/things?tenant=acme&id=T-9" {}))))))

(deftest an-overlay-carries-retry-after-and-link
  (let [r (call "https://fixture.invalid/things?tenant=acme&id=T-1" {}
                [{:retry-after 7 :status 429
                  :link "<https://fixture.invalid/things?cursor=2>; rel=\"next\""}])]
    (is (= 429 (:status r)))
    (is (= "7" (get-in r [:headers "Retry-After"])))
    (is (str/includes? (get-in r [:headers "Link"]) "rel=\"next\""))))

(deftest a-trickled-body-times-out-on-the-read
  (is (thrown? java.net.SocketTimeoutException
               (call "https://fixture.invalid/things?tenant=acme&id=T-1" {}
                     [{:trickle {:prefix "{\"items\":[" :delay-ms 1}}])))
  (testing "a streaming caller is handed the stream and reads it itself"
    (let [r (call "https://fixture.invalid/things?tenant=acme&id=T-1" {:as :stream}
                  [{:trickle {:prefix "{\"items\":[" :delay-ms 1}}])]
      (is (instance? java.io.InputStream (:body r))))))

(deftest a-chain-answers-successive-calls-in-turn
  (let [f (api-kit/replay-responder
           {:fixtures fixtures
            :overlays [{:when {:nth 1} :link "<https://fixture.invalid/things?cursor=2>; rel=\"next\""}
                       {:when {:nth 2} :body {:items []}}]})
        url "https://fixture.invalid/things?tenant=acme&id=T-1"]
    (is (str/includes? (get-in (f :get url {}) [:headers "Link"]) "cursor=2"))
    (is (= {:items []} (json/parse-string (:body (f :get url {})) true))
        "the cursor page is the second answer to the same request")))

;; ---------------------------------------------------------------------------
;; Recorder
;; ---------------------------------------------------------------------------

(def resolver-config {:id "demo_thing" :input [:demo.thing/id] :result_type "single"})

(defn- record-into [dir]
  (api-kit/record!
   {:source-id "demo" :operation-id "things"
    :source-config op
    :resolver-config resolver-config
    :samples [{:inputs [{:demo.thing/id "T-1"}]}]
    :out-dir dir
    :transport (fn [_m _u _o]
                 {:status 200
                  :headers {"Content-Type" "application/json"
                            "Set-Cookie" "session=abc"
                            "X-RateLimit-Remaining" "99"
                            "Date" "Thu, 18 Sep 2026 00:00:00 GMT"}
                  :body (json/generate-string
                         {:items [{:id "T-1" :name "first" :blurb long-text}]})})}))

(deftest the-recorder-writes-a-deterministic-redacted-fixture
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "api-kit-rec-" (System/nanoTime))))
        r   (record-into dir)
        fxt (api-kit/read-fixture (:file r))
        ex  (first (:api-kit/exchanges fxt))]
    (is (= 1 (:exchanges r)))
    (testing "the endpoint TEMPLATE is stored, never a resolved URL"
      (is (= "/things" (:endpoint ex)))
      (is (= ["id" "tenant"] (:query-param-names ex)))
      (is (not (str/includes? (slurp (:file r)) "https://fixture.invalid"))))
    (testing "response headers are whitelisted"
      (is (= {"Content-Type" "application/json" "X-RateLimit-Remaining" "99"}
             (into {} (:headers ex))))
      (is (not (str/includes? (slurp (:file r)) "Set-Cookie"))))
    (testing "mapped fields survive, unmapped long strings do not"
      (is (= "first" (get-in ex [:body :items 0 :name])))
      (is (= "<len:200>" (get-in ex [:body :items 0 :blurb]))))
    (testing "the provenance header carries what a reviewer needs"
      (let [p (:api-kit/provenance fxt)]
        (is (= "api-v1" (:contract p)))
        (is (= "v1" (:connector-version p)))
        (is (str/starts-with? (:redaction-rules p) "sha256:"))
        (is (some? (:recorded-at p)))))
    (testing "the samples travel with the fixture"
      (is (= [{:inputs [{:demo.thing/id "T-1"}]}] (:api-kit/samples fxt))))
    (testing "re-recording changes nothing but the timestamp"
      (let [again (api-kit/read-fixture (:file (record-into dir)))]
        (is (= (dissoc fxt :api-kit/provenance :api-kit/file)
               (dissoc again :api-kit/provenance :api-kit/file)))))))

(deftest a-recorded-fixture-replays
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "api-kit-rt-" (System/nanoTime))))
        _   (record-into dir)
        sd  {:id "things" :source-id "demo" :source-config op
             :resolver-config resolver-config}
        cs  (api-kit/conformance-tests sd dir)]
    (is (= '[things-read things-by-key-not-found things-unmapped-status
             things-failure-unauthorized things-failure-rate-limited
             things-failure-unavailable things-failure-timeout
             things-failure-cancelled things-audit-receipt]
           (api-kit/test-names sd))
        "an operation-block source definition needs no Semantic document")
    (doseq [c cs] (testing (:label c) ((:run c))))))

;; ---------------------------------------------------------------------------
;; Fixture helpers
;; ---------------------------------------------------------------------------

(deftest the-template-splitter-collects-names-from-both-places
  (is (= ["id" "page" "tenant"]
         (fx/param-names (assoc op :query_params {:page "{demo.thing/page}"})))))

(deftest the-header-allow-list-is-a-list-not-a-guess
  (is (every? fx/header-kept? ["Content-Type" "content-type" "Link" "Retry-After"
                               "X-RateLimit-Limit" "x-ratelimit-remaining"]))
  (is (not-any? fx/header-kept? ["Set-Cookie" "WWW-Authenticate" "Authorization" "Date"])))

;; ---------------------------------------------------------------------------
;; Paging (doc 107 §107.6) — SE declares `:paging :none`, so the strategies
;; themselves are proven here, on synthetic sources of each shape.
;; ---------------------------------------------------------------------------

(def ^:private feed-op
  {:type "api" :base_url "https://fixture.invalid"
   :method "GET" :endpoint "/parts"
   :data_path "$.items"
   :field_mapping {:demo.part/sku "$.sku"}})

(defn- feed-resolver [paging]
  {:id "all_parts" :input [] :result_type "list"
   :semantic/collection {:ordering :source-defined :completeness :not-guaranteed
                         :paging paging
                         :bounds {:max-items 10000 :max-response-bytes 4194304
                                  :timeout-ms 30000 :max-pages 3}}})

(defn- feed-fixture!
  "Record a one-page fixture for a feed source, then hand back its source-def."
  [id paging]
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "api-kit-" id "-" (System/nanoTime))))
        sd  {:id id :source-id "demo" :source-config feed-op
             :resolver-config (feed-resolver paging)}]
    (api-kit/record!
     (assoc sd :operation-id id :out-dir dir
            :samples [{:inputs [{}]}]
            :transport (fn [_m _u opts]
                         (let [b (json/generate-string
                                  {:items [{:sku "S0"} {:sku "S1"}] :meta {}})]
                           {:status 200 :headers {"Content-Type" "application/json"}
                            :body (if (= :stream (:as opts))
                                    (java.io.ByteArrayInputStream. (.getBytes b "UTF-8"))
                                    b)}))))
    [sd dir]))

(defn- run-cases! [sd dir capability]
  (let [cs (filter #(= capability (:capability %)) (api-kit/conformance-tests sd dir))]
    (is (seq cs) (str "no case generated for " capability))
    (doseq [c cs] (testing (:label c) ((:run c))))))

(deftest cursor-paging-cases-are-generated-and-pass
  (let [[sd dir] (feed-fixture! "cursor-feed"
                                {:strategy :cursor :param "cursor"
                                 :next-path "$.meta.next"
                                 :size-param "limit" :page-size 2})]
    (is (= 3 (count (filter #(= :paging (:capability %))
                            (api-kit/conformance-tests sd dir))))
        "two pages + :limit, the :max-pages refusal, and the cursor-loop refusal")
    (run-cases! sd dir :paging)))

(deftest link-header-paging-cases-cover-the-cross-origin-refusal
  (let [[sd dir] (feed-fixture! "link-feed" {:strategy :link-header})]
    (is (some #(str/includes? (:label %) "cross-origin")
              (api-kit/conformance-tests sd dir))
        "a Link-header feed must get the cross-origin case")
    (run-cases! sd dir :paging)))

(deftest offset-and-page-strategies-end-on-an-empty-page
  (doseq [paging [{:strategy :offset :param "offset" :size-param "limit" :page-size 2}
                  {:strategy :page :param "page" :size-param "per_page" :page-size 2}]]
    (let [[sd dir] (feed-fixture! (str "feed-" (name (:strategy paging))) paging)]
      (run-cases! sd dir :paging))))

(deftest a-snapshot-path-adds-its-own-case
  (let [[sd dir] (feed-fixture! "snap-feed"
                                {:strategy :cursor :param "cursor"
                                 :next-path "$.meta.next" :size-param "limit"
                                 :page-size 2 :snapshot-path "$.meta.snapshot_id"})]
    (is (some #(str/includes? (:label %) "snapshot")
              (api-kit/conformance-tests sd dir)))
    (run-cases! sd dir :paging)))
