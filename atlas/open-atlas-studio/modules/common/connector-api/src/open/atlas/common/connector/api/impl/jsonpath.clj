(ns open.atlas.common.connector.api.impl.jsonpath
  "JSONPath extraction over parsed JSON (keywordized maps / vectors).

   Migrated from the open-atlas api plugin. Supported forms (api-v1
   ':field_mapping'): `$` (the root itself), `$.a.b`, `$[*]`, `$[0]`, `$.a[*].b`, nested `[*]`
   levels (flattening to rows), and all-digit segments indexing sequential
   values by position (for upstreams answering array rows with the column
   names carried separately).

   Extraction never throws and never logs: a path that does not resolve is
   'this attribute cannot be supplied' (nil / empty), not an error — the
   SPI's single/nil contract. Structural failures of the CONFIG are the
   validator's job, not the extractor's."
  (:require [clojure.string :as str]))

(defn- step-in
  "Walk ONE dot-separated JSONPath segment into `cur`. An all-digits segment
   indexes a sequential value BY POSITION; anything else is a map-key lookup."
  [cur part]
  (when (some? cur)
    (if (re-matches #"\d+" part)
      (when (sequential? cur) (nth (vec cur) (Integer/parseInt part) nil))
      (get cur (keyword part)))))

(defn get-in-path
  "Walk a dot-separated field path (already stripped of any array marker)
   into `data` with `step-in` semantics."
  [data field-path]
  (reduce step-in data (str/split (str field-path) #"\.")))

(defn- get-nested-value [data path]
  (try
    (let [path  (if (str/starts-with? path "$.") (subs path 2) path)
          parts (str/split path #"\.")]
      (reduce
       (fn [cur part]
         (when-not (nil? cur)
           (if (str/includes? part "[")
             (let [[key idx-str] (str/split part #"[\[\]]")
                   cur (if (seq key) (step-in cur key) cur)]
               (cond
                 (or (nil? idx-str) (empty? idx-str)) cur
                 (= idx-str "*") (if (sequential? cur) cur [cur])
                 :else (nth cur (Integer/parseInt idx-str) nil)))
             (step-in cur part))))
       data parts))
    (catch Exception _ nil)))

(declare extract)

(defn- extract-column
  "Zip ONE column out of `array-val` for the field path that followed a `[*]`.
   A FURTHER `[*]` re-enters each element recursively and concatenates the
   per-parent columns, so a two-level array yields ROWS (one per grandchild)
   rather than a column of arrays. Single-level paths keep mapv (not keep):
   missing fields become nil so the column stays index-aligned with the
   array, which the batch column-zip relies on."
  [array-val field-part]
  (if (str/includes? field-part "[*]")
    (into [] (mapcat #(or (extract % (str "$" (if (str/starts-with? field-part ".")
                                                field-part
                                                (str "." field-part))))
                          []))
          array-val)
    (let [field-path (str/replace field-part #"^\." "")]
      (mapv #(get-in-path % field-path) array-val))))

(defn extract
  "Extract `jsonpath` from `data`. nil on any parse/walk failure."
  [data jsonpath]
  (try
    (cond
      ;; `$` is the ROOT — the same root every other form in api-v1 starts
      ;; from (`$.a.b`, `$[*]`, `$[0]`), with nothing walked after it. Without
      ;; this branch `$` fell through to a map-key lookup of `:$` and answered
      ;; nil, so a `:data_path "$"` probed clean and queried empty (doc 106
      ;; §106.12 item 7.4, measured on the v9 rebuild 2026-09-07).
      (= "$" (str/trim (str jsonpath)))
      data

      (str/starts-with? jsonpath "$[*]")
      (let [field-part (subs jsonpath 4)
            array-val  (if (sequential? data) data [])]
        (if (seq field-part)
          (extract-column array-val field-part)
          (vec array-val)))

      (re-find #"^\$\[(\d+)\]" jsonpath)
      (let [[_ idx-str] (re-find #"^\$\[(\d+)\](.*)" jsonpath)
            idx  (Integer/parseInt idx-str)
            item (nth (if (sequential? data) data []) idx nil)
            field-part (second (re-find #"^\$\[\d+\](.*)" jsonpath))]
        (if (seq field-part)
          (get-in-path item (str/replace field-part #"^\." ""))
          item))

      (str/includes? jsonpath "[*]")
      ;; limit 2: keep any FURTHER "[*]" in the remainder instead of dropping
      ;; it, so "$.items[*].itemPartials[*].qty" reaches the second level.
      (let [[array-path field-part] (str/split jsonpath #"\[\*\]" 2)
            array-val (get-nested-value data array-path)]
        (if (sequential? array-val)
          (if (seq field-part)
            (extract-column array-val field-part)
            (vec array-val))
          []))

      :else
      (get-nested-value data jsonpath))
    (catch Exception _ nil)))
