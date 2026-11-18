(ns open.atlas.common.connector.api.impl.stream
  "Streaming JSON sampling for the probe (8.4.23 裁定十七).

   Why this ns exists: a catalog endpoint answers with the whole feed and
   takes no page parameter, so 裁定七's «read the body, refuse over the
   ceiling» closed the door on exactly the sources the probe was for. The
   probe wants ONE record; this reads the response as a stream and stops at
   the first complete one, so the ceiling bounds the bytes READ rather than
   the size of the upstream answer.

   Jackson's streaming parser does the walking (裁定十七: no hand-written
   scanner) and cheshire's own `parse*` materialises the one record, so a
   sampled record is the same Clojure value the whole-body path would have
   produced for it.

   The ceiling still REFUSES rather than truncates: what is handed back is a
   COMPLETE record or nothing at all — a half-read record would infer a half
   field list, and nothing downstream could tell that apart from the
   endpoint's real shape (裁定七, unchanged)."
  (:require [cheshire.factory :as factory]
            [cheshire.parse :as parse]
            [clojure.string :as str])
  (:import [com.fasterxml.jackson.core JsonParser JsonToken JsonStreamContext]
           [java.io ByteArrayOutputStream InputStream]))

;; ---------------------------------------------------------------------------
;; The capped, counting, tee-ing stream
;; ---------------------------------------------------------------------------

(defn capped-stream
  "Wrap `in` so that at most `max-bytes` are ever read out of it. Every byte
   read is counted in `counter` and copied into `sink`.

   Returning -1 at the cap is what makes the ceiling a fact about bytes read:
   the parser above sees end-of-input and stops asking for more, and the
   connection is closed by the caller right after."
  ^InputStream [^InputStream in max-bytes counter ^ByteArrayOutputStream sink]
  
  (let [max-bytes (long max-bytes)]
   (proxy [InputStream] []
    (read
      ([]
       (if (>= (long @counter) max-bytes)
         -1
         (let [b (.read in)]
           (if (neg? b)
             -1
             (do (swap! counter inc) (.write sink b) b)))))
      ([buf]
       (.read ^InputStream this ^bytes buf 0 (alength ^bytes buf)))
      ([buf off len]
       (let [remaining (- max-bytes (long @counter))]
         (if (<= remaining 0)
           -1
           (let [n (.read in ^bytes buf (int off) (int (min (long len) remaining)))]
             (if (neg? n)
               -1
               (do (swap! counter + n)
                   (.write sink ^bytes buf (int off) (int n))
                   n)))))))
    (close [] (.close in)))))

;; ---------------------------------------------------------------------------
;; Walking to the records
;; ---------------------------------------------------------------------------

(defn path-segments
  "An api-v1 `:data_path` → the segments to walk: field names, `[*]` and
   `[<n>]` (the forms `impl.rows/locate-rows` accepts). `$` / nil = the
   response itself."
  [data-path]
  (->> (-> (str data-path)
           (str/replace #"^\$" "")
           (str/replace #"\[(\*|\d+)\]" ".[$1]"))
       (#(str/split % #"\."))
       (remove str/blank?)
       vec))

(defn- read-value
  "Materialise the value at the parser's CURRENT token with cheshire's own
   parser, so one sampled record equals what a whole-body parse would give."
  [^JsonParser p]
  (parse/parse* p keyword parse/*use-bigdecimals?* nil))

(defn- context-path
  "The JSONPath of the parser's current container, built from Jackson's own
   parsing contexts — a LOCATION, and the only thing reported about a
   response the probe did not read to the end."
  [^JsonParser p]
  (loop [^JsonStreamContext ctx (.getParsingContext p)
         names ()]
    (if (or (nil? ctx) (nil? (.getParent ctx)))
      (str "$" (str/join (map #(str "." %) names)))
      (recur (.getParent ctx)
             (let [n (.getCurrentName ctx)]
               (if (and n (not= n (first names))) (cons n names) names))))))

(defn- records-of-array
  "The parser sits on START_ARRAY: read up to `n` of its elements that are
   objects and return them as a vector.

   A record is a record only if it is COMPLETE: a parse cut short by the
   ceiling throws, and the records already collected are what the probe has —
   half a record is never one of them (补裁 2026-09-04, 裁定七 unchanged)."
  [^JsonParser p n]
  (let [acc (volatile! [])]
    (try
      (loop []
        (when (< (count @acc) (long n))
          (let [t (.nextToken p)]
            (when (= t JsonToken/START_OBJECT)
              (vswap! acc conj (read-value p))
              (recur)))))
      (catch Exception _ nil))
    @acc))

(defn- enter-field
  "The parser sits on START_OBJECT: advance to the VALUE of `field` (leaving
   the parser on that value's first token) or return false."
  [^JsonParser p field]
  (loop []
    (let [t (.nextToken p)]
      (cond
        (nil? t) false
        (= t JsonToken/END_OBJECT) false
        (= t JsonToken/FIELD_NAME)
        (if (= field (.getCurrentName p))
          (do (.nextToken p) true)
          (do (.nextToken p) (.skipChildren p) (recur)))
        :else (recur)))))

(defn- nth-element
  "The parser sits on START_ARRAY: advance to element `n` (leaving the parser
   on its first token) or return false."
  [^JsonParser p ^long n]
  (loop [i 0]
    (let [t (.nextToken p)]
      (cond
        (or (nil? t) (= t JsonToken/END_ARRAY)) false
        (= i n) true
        :else (do (.skipChildren p) (recur (inc i)))))))

(defn- records-at-path
  "Walk `segs` from the parser's current value token and return up to `n`
   records found there."
  [^JsonParser p segs n]
  (let [seg (first segs)]
    (cond
      (nil? seg)
      (condp = (.currentToken p)
        JsonToken/START_ARRAY  (records-of-array p n)
        ;; A `:data_path` naming one object is a one-record answer.
        JsonToken/START_OBJECT [(read-value p)]
        [])

      (= seg "[*]")
      (if (= JsonToken/START_ARRAY (.currentToken p))
        (if (seq (rest segs))
          (if (= JsonToken/START_OBJECT (.nextToken p))
            (records-at-path p (rest segs) n)
            [])
          (records-of-array p n))
        [])

      (re-matches #"\[\d+\]" seg)
      (if (and (= JsonToken/START_ARRAY (.currentToken p))
               (nth-element p (parse-long (re-find #"\d+" seg))))
        (records-at-path p (rest segs) n)
        [])

      :else
      (if (and (= JsonToken/START_OBJECT (.currentToken p))
               (enter-field p seg))
        (records-at-path p (rest segs) n)
        []))))

(defn- scan-for-records
  "No `:data_path`: the first array whose first element is an object, in
   document order, and up to `n` records out of it. Returns `[records path]`
   or nil.

   This is the same question `infer-data-path` asks of a whole sample; asked
   here because a body over the ceiling never becomes a whole sample."
  [^JsonParser p n]
  (loop []
    (let [t (.nextToken p)]
      (cond
        (nil? t) nil
        (= t JsonToken/START_ARRAY)
        (let [path (context-path p)]
          (if (= JsonToken/START_OBJECT (.nextToken p))
            [(into [(read-value p)] (records-of-array p (dec (long n)))) path]
            (recur)))
        :else (recur)))))

;; ---------------------------------------------------------------------------
;; The sampler the probe hands to impl.request
;; ---------------------------------------------------------------------------

(def default-sample-size
  "How many records a probe samples by default (补裁 2026-09-04).

   One record cannot show an OPTIONAL field, a null, or a small value set, so
   every inference drawn from one record overstates what the endpoint said.
   A thousand is the ruling's number (owner's second 补裁, 2026-09-04, which
   raised it from a hundred); the byte ceiling still wins whenever it comes
   first."
  1000)

(defn sample!
  "Read `in` as a JSON stream and hand back the probe's sample (裁定十七 and
   its 补裁).

   It stops at whichever comes first: `sample-size` records, or the byte
   ceiling. Two shapes, one for each thing the caller knows:

   - `data-path` given → walk to the records and read up to `sample-size` of
     them, then disconnect; a delta feed costs the bytes up to its thousandth
     record and not its size;
   - no `data-path` → read up to the ceiling. Under the ceiling the whole body
     is parsed and the sample is the whole body — today's behaviour, byte for
     byte. Over it, the first array-of-objects inside what was read gives the
     records, and its path is reported back.

   → `{:bytes-read n :sample <whole body> :bytes n}` (read whole, not
     truncated), `{:bytes-read n :sample [<record> …] :records n
     :records-path p :truncated? true}` (stopped early), or
     `{:bytes-read n :over-limit? true}` when not ONE complete record could be
     read. `:records` is the count that was read, and is absent for a whole
     body — nothing located records there, so nothing may claim how many there
     were."
  ([^InputStream in max-bytes data-path]
   (sample! in max-bytes data-path default-sample-size))
  ([^InputStream in max-bytes data-path sample-size]
   (let [n       (max 1 (long (or sample-size default-sample-size)))
         counter (atom 0)
         sink    (ByteArrayOutputStream.)
         capped  (capped-stream in max-bytes counter sink)
         segs    (when (seq (str data-path)) (path-segments data-path))
         finish  (fn [m] (assoc m :bytes-read (long @counter)))
         located (fn [records path]
                   (if (seq records)
                     (finish {:sample (vec records) :records (count records)
                              :records-path path :truncated? true})
                     (finish {:over-limit? true})))]
     (with-open [^JsonParser p (.createParser (factory/make-json-factory
                                               factory/default-factory-options)
                                              ^InputStream capped)]
       (if (some? data-path)
         ;; The caller said where the records are: read n of them and leave.
         (located (try (.nextToken p)
                       (records-at-path p segs n)
                       (catch Exception _ nil))
                  (str data-path))
         (let [whole (try (read-value (doto p (.nextToken)))
                          (catch Exception _ ::unparsable))]
           (cond
             ;; A parse that SUCCEEDED read a complete JSON document, so the
             ;; whole answer was under the ceiling: behave exactly as before
             ;; 裁定十七 — the sample is the whole body, and `:bytes` (the size
             ;; of the whole answer) is known and reported. It is reported ONLY
             ;; here: nothing else can honestly say how big an answer was that
             ;; the probe stopped reading.
             (not= whole ::unparsable)
             (finish {:sample whole :bytes (long @counter)})

             ;; Not JSON, and the ceiling was never reached — pre-裁定十七 that
             ;; was a nil sample, and it still is.
             (< (long @counter) max-bytes)
             (finish {:sample nil :bytes (long @counter)})

             :else
             ;; Cut off by the ceiling. Re-read the prefix that WAS read,
             ;; looking for records in it.
             (let [prefix (.toByteArray sink)]
               (with-open [^JsonParser p2 (.createParser
                                           (factory/make-json-factory
                                            factory/default-factory-options)
                                           ^bytes prefix)]
                 (let [[records path] (try (scan-for-records p2 n)
                                           (catch Exception _ nil))]
                   (located records path)))))))))))
