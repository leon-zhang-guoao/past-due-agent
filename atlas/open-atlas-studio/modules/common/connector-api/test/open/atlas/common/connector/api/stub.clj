(ns open.atlas.common.connector.api.stub
  "A local HTTP stub for the streaming probe (8.4.23 裁定十七).

   The other api tests mock `clj-http.client` and never open a socket. This
   one MUST open one: the ruling's claim is that the probe stops reading and
   DISCONNECTS at the first record, and only a real server can witness that —
   it counts the bytes it managed to write and records the broken pipe.

   A hand-rolled `ServerSocket` rather than a web framework: the module's deps
   carry no server, and the stub owes exactly one response."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayInputStream InputStream OutputStream]
           [java.net ServerSocket Socket]))

(defn stream
  "A response body as clj-http hands it back under `:as :stream`."
  ^InputStream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn products-feed-chunks
  "A `{\"products\":[…]}` feed as a seq of strings, `n` records of `pad`
   filler each — far larger than the probe ceiling, and streamed so the server
   discovers the disconnect partway through."
  [n pad]
  (concat ["{\"meta\":{\"generated\":\"2026-09-04\"},\"products\":["]
          (for [i (range n)]
            (str (when (pos? i) ",")
                 "{\"id\":" i ",\"name\":\"item-" i "\",\"blurb\":\""
                 (apply str (repeat pad \x)) "\"}"))
          ["]}"]))

(defn serve!
  "Start a one-shot HTTP server answering every request with `chunks`.

   → `{:port n :written (atom 0) :broken? (atom false) :stop (fn [])}`. The
   two atoms are the assertion the ruling asks for: a probe that stops early
   leaves `@written` far below the whole body and `@broken?` true."
  [chunks]
  (let [server  (ServerSocket. 0)
        written (atom 0)
        broken? (atom false)
        thread
        (doto (Thread.
               (fn []
                 (try
                   (while (not (.isClosed server))
                     (let [^Socket sock (.accept server)]
                       (try
                         (let [in  (.getInputStream sock)
                               ^OutputStream out (.getOutputStream sock)
                               rdr (java.io.BufferedReader.
                                    (java.io.InputStreamReader. in "UTF-8"))]
                           ;; Consume the request head; we answer the same way
                           ;; whatever it says.
                           (loop [] (let [l (.readLine rdr)]
                                      (when (and l (not (str/blank? l))) (recur))))
                           (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                       "Content-Type: application/json\r\n"
                                                       "Transfer-Encoding: chunked\r\n"
                                                       "Connection: close\r\n\r\n")
                                                  "UTF-8"))
                           (.flush out)
                           (doseq [^String c chunks]
                             (let [bs (.getBytes c "UTF-8")]
                               (.write out (.getBytes (str (Integer/toHexString (alength bs))
                                                           "\r\n")
                                                      "UTF-8"))
                               (.write out bs)
                               (.write out (.getBytes "\r\n" "UTF-8"))
                               (.flush out)
                               (swap! written + (alength bs))))
                           (.write out (.getBytes "0\r\n\r\n" "UTF-8"))
                           (.flush out))
                         (catch Exception _
                           ;; The client hung up mid-body — the ruling's exit
                           ;; condition, not a failure.
                           (reset! broken? true))
                         (finally (try (.close sock) (catch Exception _ nil))))))
                   (catch Exception _ nil))))
          (.setDaemon true)
          (.start))]
    {:port    (.getLocalPort server)
     :written written
     :broken? broken?
     :stop    (fn [] (.close server) (.interrupt thread))}))

(defn serve-headers-only!
  "Start a server that sends response HEADERS and then **never sends a body**,
   holding the connection open until the client goes away.

   The conformance stub for D6.3 / doc 107 §107.7. It is the case clj-http's
   `:socket-timeout` was never going to catch on its own the way api-v1 defined
   it, and the case the whole «attempt total» re-reading exists for: the server
   is not idle in any way the transport can see -- it simply has more to say
   later, forever.

   -> `{:port n :accepted (atom 0) :stop (fn [])}`."
  []
  (let [server   (ServerSocket. 0)
        accepted (atom 0)
        held     (atom [])
        thread
        (doto (Thread.
               (fn []
                 (try
                   (while (not (.isClosed server))
                     (let [^Socket sock (.accept server)]
                       (swap! accepted inc)
                       (swap! held conj sock)
                       (try
                         (let [rdr (java.io.BufferedReader.
                                    (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
                               ^OutputStream out (.getOutputStream sock)]
                           (loop [] (let [l (.readLine rdr)]
                                      (when (and l (not (str/blank? l))) (recur))))
                           ;; Headers promise a body of a known length, then
                           ;; nothing arrives. A well-behaved client waits.
                           (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                       "Content-Type: application/json\r\n"
                                                       "Content-Length: 4096\r\n\r\n")
                                                  "UTF-8"))
                           (.flush out))
                         (catch Exception _ nil))))
                   (catch Exception _ nil))))
          (.setDaemon true)
          (.start))]
    {:port     (.getLocalPort server)
     :accepted accepted
     :stop     (fn []
                 (doseq [^Socket s @held] (try (.close s) (catch Exception _ nil)))
                 (.close server)
                 (.interrupt thread))}))

(defn serve-trickle!
  "A server that sends headers and then one byte every `gap-ms`, forever.

   The «10 分钟涓流» of the SE `sources.edn` comment: never idle long enough to
   trip a socket timeout, never finished either. Only an ABSOLUTE attempt
   timeout ends it."
  [gap-ms]
  (let [server (ServerSocket. 0)
        held   (atom [])
        thread
        (doto (Thread.
               (fn []
                 (try
                   (while (not (.isClosed server))
                     (let [^Socket sock (.accept server)]
                       (swap! held conj sock)
                       (doto (Thread.
                              (fn []
                                (try
                                  (let [rdr (java.io.BufferedReader.
                                             (java.io.InputStreamReader.
                                              (.getInputStream sock) "UTF-8"))
                                        ^OutputStream out (.getOutputStream sock)]
                                    (loop [] (let [l (.readLine rdr)]
                                               (when (and l (not (str/blank? l))) (recur))))
                                    (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                                "Content-Type: application/json\r\n"
                                                                "Content-Length: 1048576\r\n\r\n")
                                                           "UTF-8"))
                                    (.flush out)
                                    (loop []
                                      (.write out (int \x))
                                      (.flush out)
                                      (Thread/sleep (long gap-ms))
                                      (recur)))
                                  (catch Exception _ nil))))
                         (.setDaemon true)
                         (.start))))
                   (catch Exception _ nil))))
          (.setDaemon true)
          (.start))]
    {:port (.getLocalPort server)
     :stop (fn []
             (doseq [^Socket s @held] (try (.close s) (catch Exception _ nil)))
             (.close server)
             (.interrupt thread))}))

(defn serve-pages!
  "A paging feed. `respond` takes the request's query string and answers
   `{:status n :headers {..} :body \"..\"}`.

   Records every request line so a test can assert HOW MANY pages went out and
   with what position -- which is the substance of most of §107.6's conformance:
   `:limit 150` with `:page-size 100` must cost exactly two requests, not three."
  [respond]
  (let [server   (ServerSocket. 0)
        requests (atom [])
        thread
        (doto (Thread.
               (fn []
                 (try
                   (while (not (.isClosed server))
                     (let [^Socket sock (.accept server)]
                       (try
                         (let [rdr (java.io.BufferedReader.
                                    (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
                               ^OutputStream out (.getOutputStream sock)
                               line (.readLine rdr)]
                           (loop [] (let [l (.readLine rdr)]
                                      (when (and l (not (str/blank? l))) (recur))))
                           (swap! requests conj line)
                           (let [{:keys [status headers body]} (respond (str line))
                                 bs (.getBytes (str body) "UTF-8")]
                             (.write out (.getBytes
                                          (str "HTTP/1.1 " status " X\r\n"
                                               "Content-Type: application/json\r\n"
                                               (apply str (for [[k v] headers]
                                                            (str k ": " v "\r\n")))
                                               "Content-Length: " (alength bs) "\r\n\r\n")
                                          "UTF-8"))
                             (.write out bs)
                             (.flush out)))
                         (catch Exception _ nil)
                         (finally (try (.close sock) (catch Exception _ nil))))))
                   (catch Exception _ nil))))
          (.setDaemon true)
          (.start))]
    {:port     (.getLocalPort server)
     :requests requests
     :stop     (fn [] (.close server) (.interrupt thread))}))
