(ns open.atlas.common.oidc.impl.http
  "The IdP-facing HTTP calls, over JDK `java.net.http` (doc §8.16.5).

   Three call sites only — discovery, token exchange, JWKS — and all three
   distinguish exactly two outcomes for the caller: *unreachable or not JSON*
   (`auth/unavailable-idp`, 502) versus *reached and answered* (whose body the
   caller judges). That split is §8.16.6's, and it is why the return here is
   `{:status … :body …}` or `{:error :unavailable}` rather than an exception:
   an exception would have to be classified again at every call site.

   The response body never leaves this namespace toward a finding — §8.16.4
   rule 2 forbids handing the IdP's raw response to the browser."
  (:require [cheshire.core :as json])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defn client
  "A client with a bounded connect timeout: an IdP that never answers must
   become a 502, not a request that hangs until the browser gives up."
  ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
      (.build)))

(defn urlencode ^String [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn form-encode ^String [params]
  (->> params
       (map (fn [[k v]] (str (urlencode (name k)) "=" (urlencode v))))
       (interpose "&")
       (apply str)))

(defn- send-request [^HttpClient c ^HttpRequest req]
  (try
    (let [resp (.send c req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})
    (catch Exception _ {:error :unavailable})))

(defn get-json
  "GET a JSON document. `{:json m}` / `{:error :unavailable}`.

   A non-2xx status and a body that does not parse as a JSON object collapse to
   the same `:unavailable`: for discovery and JWKS there is no useful second
   outcome — either we have the document or we do not."
  [^HttpClient c ^String url]
  (let [{:keys [status body error]}
        (send-request c (-> (HttpRequest/newBuilder (URI/create url))
                            (.timeout (Duration/ofSeconds 10))
                            (.header "accept" "application/json")
                            (.GET)
                            (.build)))]
    (if error
      {:error :unavailable}
      (if-not (<= 200 status 299)
        {:error :unavailable}
        (try
          (let [m (json/parse-string body)]
            (if (map? m) {:json m} {:error :unavailable}))
          (catch Exception _ {:error :unavailable}))))))

(defn post-form
  "POST an `application/x-www-form-urlencoded` body and parse a JSON reply.

   Unlike `get-json` this keeps the three outcomes apart, because the token
   endpoint has a meaningful failure that is not the IdP being down: a 400 with
   a JSON `error` body is the IdP saying the code is bad, which is
   `auth/invalid-oidc-response`, not `auth/unavailable-idp` (§8.16.6)."
  [^HttpClient c ^String url params]
  (let [{:keys [status body error]}
        (send-request c (-> (HttpRequest/newBuilder (URI/create url))
                            (.timeout (Duration/ofSeconds 10))
                            (.header "content-type" "application/x-www-form-urlencoded")
                            (.header "accept" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString (form-encode params)))
                            (.build)))]
    (if error
      {:error :unavailable}
      (let [m (try (json/parse-string body) (catch Exception _ nil))]
        (cond
          (not (map? m))         {:error :unavailable}   ; reached, but not JSON
          (<= 200 status 299)    {:json m}
          :else                  {:error :rejected})))))
