(ns open.atlas.common.connector.api-kit.impl.redact
  "Redaction rules for recorded api fixtures.

   A fixture is a SHAREABLE artefact — it goes into a source repo next to the
   graph and is read by anyone who runs the suite. What a real endpoint
   answered is not. So a recorded body keeps only what the conformance suite
   has to assert on (the paths the model actually maps) and replaces the rest
   with markers that preserve SHAPE and length without preserving content.

   Three rules, applied in this order, and the order is the point:

   1. `:secret-paths` — declared by the modeller, always replaced, even if the
      path is also in the keep list. A declared secret outranks a keep.
   2. shape-preserving masks — e-mail / token-looking strings are masked
      WHEREVER they appear, including under a kept path. `redaction-check`
      re-applies exactly these detectors to a recorded directory, so a rule
      that did not fire here is a rule that fails the check there.
   3. length ceiling — a string longer than `:max-string` becomes `<len:n>`
      unless its path is kept.

   Nothing here logs and nothing here returns a rejected value: a marker says
   what KIND of thing was removed and how long it was, never what it said
   (finding-v1 §5)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; JSONPath → token path
;; ---------------------------------------------------------------------------

(defn path-tokens
  "A `:field_mapping` JSONPath as the token vector this ns walks with.

   `$.a.b` → [\"a\" \"b\"], `$.a[*].b` → [\"a\" \"*\" \"b\"], and a numeric
   step (`$.etim.0.class`, the dotted-index spelling api-v1's jsonpath
   accepts) is `\"*\"` too: a fixture recorded from element 0 must still keep
   element 3 of the same array, or the second sample silently loses its
   fields. `$` and `$[*]` are the empty path — «the whole record», the
   `:detail_raw` escape hatch."
  [jsonpath]
  (when (string? jsonpath)
    (->> (-> jsonpath
             (str/replace #"^\$" "")
             (str/replace #"\[\*\]" ".*")
             (str/replace #"\[(\d+)\]" ".*")
             (str/split #"\."))
         (remove str/blank?)
         (mapv #(if (re-matches #"\d+" %) "*" %)))))

(defn field-mapping-keep-paths
  "Every leaf JSONPath in a `:field_mapping`, including the ones inside a
   branch scope and a `:_from` collection (api-v1 §«分支作用域» / «记录内集合»).

   These are kept by DEFAULT, because they are exactly the values the
   generated suite asserts on: redacting them would make a fixture that can
   only prove the connector returns nothing."
  [field-mapping]
  (letfn [(walk [m prefix]
            (mapcat (fn [[k v]]
                      (cond
                        (string? v)
                        (if (= :_from k)
                          ;; `:_from` locates the ARRAY; its elements' leaves
                          ;; are relative to it, so it becomes the prefix.
                          nil
                          [(into prefix (path-tokens v))])

                        (map? v)
                        (let [from (some-> (:_from v) path-tokens)]
                          (walk (dissoc v :_from) (into prefix (or from []))))

                        :else nil))
                    m))]
    (vec (distinct (walk (or field-mapping {}) [])))))

(defn- prefix-match?
  "Is `keep` a token-wise prefix of `path`? `\"*\"` in either matches anything."
  [keep path]
  (and (<= (count keep) (count path))
       (every? true?
               (map (fn [k p] (or (= k "*") (= p "*") (= k p)))
                    keep (take (count keep) path)))))

(defn- kept? [keep-paths path]
  (boolean (some #(prefix-match? % path) keep-paths)))

(defn- exact-match? [ps path]
  (boolean (some #(and (= (count %) (count path))
                       (every? true? (map (fn [a b] (or (= a "*") (= a b))) % path)))
                 ps)))

;; ---------------------------------------------------------------------------
;; Shape-preserving masks (rule 2) — also the redaction-check detectors
;; ---------------------------------------------------------------------------

(def detectors
  "`[rule-key predicate marker]`, in priority order.

   ONE list, used both when recording and when checking, because two lists
   would differ on the day it matters: a recorder that masks what the checker
   does not look for passes a check it never earned, and the reverse fails a
   fixture nobody can fix."
  [[:email
    #(re-find #"(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}" %)
    "<email>"]
   [:jwt
    #(re-find #"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}" %)
    "<token>"]
   [:bearer
    #(re-find #"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}" %)
    "<token>"]
   [:api-key-prefix
    #(re-find #"\b(sk-|ghp_|gho_|github_pat_|xox[baprs]-|AKIA)[A-Za-z0-9_-]{10,}" %)
    "<token>"]
   [:private-key
    #(re-find #"-----BEGIN [A-Z ]*PRIVATE KEY-----" %)
    "<token>"]
   [:unresolved-var
    ;; A `${VAR}` that reached a recorded body means the connector shipped the
    ;; literal upstream — the exact defect test-env exists to catch.
    #(re-find #"\$\{[A-Za-z_][A-Za-z0-9_]*\}" %)
    "<token>"]
   [:high-entropy
    ;; A long unbroken base64/hex run with no spaces: session ids, signatures,
    ;; opaque cursors. Deliberately NOT applied to strings carrying spaces or
    ;; punctuation — a product description is not a secret.
    #(re-find #"\b(?=[A-Za-z0-9+/=_-]{40,}\b)(?=[^\s]*[0-9])(?=[^\s]*[A-Za-z])[A-Za-z0-9+/=_-]{40,}\b" %)
    "<token>"]])

(defn detect
  "The first detector rule that fires on `s`, or nil. Answers the rule KEY;
   the caller never gets the matched text back."
  [s]
  (when (string? s)
    (some (fn [[k pred _]] (when (pred s) k)) detectors)))

(defn- marker-for [rule]
  (some (fn [[k _ m]] (when (= k rule) m)) detectors))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def default-max-string
  "Strings longer than this lose their content unless their path is kept.
   64 keeps identifiers, codes, statuses and short labels legible while a
   marketing paragraph becomes `<len:412>`."
  64)

(defn rules
  "Normalise the caller's redaction options into the rule map the recorder
   stores (and hashes) with every fixture."
  [{:keys [keep-paths secret-paths max-string field-mapping]}]
  {:max-string   (or max-string default-max-string)
   :keep-paths   (vec (sort (concat (map path-tokens keep-paths)
                                    (field-mapping-keep-paths field-mapping))))
   :secret-paths (vec (sort (map path-tokens secret-paths)))
   :detectors    (mapv first detectors)})

(defn rules-hash
  "A stable digest of the rule map — the provenance header carries it so a
   fixture recorded under looser rules is visible as a DIFFERENT fixture and
   not as an innocent re-record."
  [rs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (pr-str (into (sorted-map) rs)) "UTF-8"))]
    (str "sha256:" (subs (apply str (map #(format "%02x" %) bs)) 0 16))))

(defn redact-string
  "One string under `path`. Returns the string, a mask, or a length marker."
  [rs path ^String s]
  (cond
    (exact-match? (:secret-paths rs) path) "<secret>"
    (detect s)                             (marker-for (detect s))
    (kept? (:keep-paths rs) path)          s
    (> (count s) (:max-string rs))         (str "<len:" (count s) ">")
    :else                                  s))

(defn redact
  "Walk a parsed JSON body applying the rules. Map keys are configuration
   (they are the upstream's field NAMES, which the model already writes down)
   and are never redacted; values are."
  ([rs body] (redact rs body []))
  ([rs body path]
   (cond
     (map? body)
     (into (sorted-map)
           (map (fn [[k v]] [k (redact rs v (conj path (name k)))]))
           body)

     (sequential? body)
     (mapv #(redact rs % (conj path "*")) body)

     (string? body) (redact-string rs path body)
     :else          body)))

(defn scan
  "Every detector hit in `body`, as `[{:path [..] :rule :email}]`. Values are
   never carried out — the path and the rule are enough to go and fix the
   recording, and a report that quoted the secret would be a second copy of
   it."
  ([body] (scan body []))
  ([body path]
   (cond
     (map? body)        (mapcat (fn [[k v]] (scan v (conj path (name k)))) body)
     (sequential? body) (mapcat #(scan % (conj path "*")) body)
     (string? body)     (when-let [r (detect body)] [{:path path :rule r}])
     :else              nil)))
