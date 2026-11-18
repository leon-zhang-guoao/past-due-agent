(ns open.atlas.common.semantic-graph-package
  "Portable Semantic Contract 2.0 package. A package embeds the root Graph and
   every transitive child as distinct immutable snapshots. It never contains a
   deployment Source instance, endpoint credential, token or password."
  (:require [clojure.edn :as edn]
            [open.atlas.common.artifact.impl.canonical :as canonical]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-contract :as contract]))

(def package-family "atlas-semantic-graph")
(def package-version 1)
(def max-package-bytes (* 16 1024 1024))

(defn- finding [code path]
  {:findings [{:code code :severity :error :path path}]})

(defn locator [document]
  [(get-in document [:graph :graph/id])
   (get-in document [:publication :release/version])])

(defn- encoded-document [document checkers]
  (let [written (contract/write-document document checkers)]
    (when (:text written)
      {:graph/id (first (locator document))
       :release/version (second (locator document))
       :content/hash (contract/content-hash (:text written))
       :document document})))

(defn- build-compatible
  "Build a complete offline package. `documents` includes the root and every
   transitive dependency. Exact dependency locators and hashes are rechecked;
   cycles, omissions and duplicate locators fail closed."
  [root documents checkers]
  (let [encoded (mapv #(encoded-document % checkers) documents)
        asset-ids (fn [document]
                    (concat (for [id (keys (get-in document [:vocabulary :entities]))] [:entity id])
                            (for [id (keys (get-in document [:vocabulary :attributes]))] [:attribute id])
                            (for [id (keys (get-in document [:vocabulary :terms]))] [:term id])
                            (for [id (keys (get-in document [:execution :resolvers]))] [:resolver id])
                            (for [id (keys (get-in document [:execution :operations]))] [:operation id])))
        all-assets (mapcat asset-ids documents)
        terms (frequencies (mapcat #(keys (get-in % [:vocabulary :terms])) documents))
        referenced-terms (keep :attribute/term
                               (mapcat #(vals (get-in % [:vocabulary :attributes])) documents))]
    (cond
      ;; Task book PR1 (owner 2026-09-19): a package sealed under a retired
      ;; contract is refused by name, before the encoder hides the reason.
      (some #(version/retired? (:contract %)) documents)
      {:findings [(assoc version/version-retired :path [:graphs])]}
      (or (nil? root) (some nil? encoded)) (finding :semantic.package/document [])
      ;; Only 2.0 has a package form (contract 2.0 §1).
      (not-every? #(= version/semantic-v2 (get-in % [:document :contract])) encoded)
      (finding :semantic.package/contract-version [:graphs])
      (not= (count encoded) (count (distinct (map (juxt :graph/id :release/version) encoded))))
      (finding :semantic.package/duplicate [:graphs])
      (not= (count all-assets) (count (distinct all-assets)))
      (finding :semantic.package/asset-conflict [:graphs])
      (some #(not= 1 (get terms % 0)) referenced-terms)
      (finding :semantic.package/term-missing [:graphs])
      :else
      (let [snapshot (into {} (map (fn [{:keys [graph/id release/version document]}]
                                      [[id version] document])) encoded)
            root-locator (locator root)
            root-entry (some #(when (= root-locator [(:graph/id %) (:release/version %)]) %) encoded)
            closure (contract/verify-closure root snapshot checkers)]
        (cond
          (nil? root-entry) (finding :semantic.package/root-missing [:root])
          (seq (:findings closure)) (select-keys closure [:findings])
          :else
          (let [package {:package/family package-family
                         :package/version package-version
                         :root (select-keys root-entry [:graph/id :release/version :content/hash])
                         :graphs (vec (sort-by (juxt :graph/id :release/version) encoded))}
                text (canonical/canonical-str package)]
            (if (> (alength (.getBytes ^String text "UTF-8")) max-package-bytes)
              (finding :semantic.package/size [])
              {:package package :text text :package-digest (canonical/content-hash-of-text text)
               :snapshot snapshot :root root :findings []})))))))

(defn read-package
  "Read and verify one package form. The caller supplies the transported digest;
   no registry or author server is consulted."
  [text expected-digest checkers]
  (try
    (if (or (not (string? text))
            (> (alength (.getBytes ^String text "UTF-8")) max-package-bytes))
      (finding :semantic.package/size [])
      (let [reader (java.io.PushbackReader. (java.io.StringReader. text))
            eof (Object.)
            opts {:eof eof :readers {} :default (fn [_ _] (throw (ex-info "tag" {})))}
            package (edn/read opts reader)
            tail (edn/read opts reader)]
        (cond
          (not (identical? eof tail)) (finding :semantic.package/trailing-form [])
          (not= expected-digest (canonical/content-hash-of-text text))
          (finding :semantic.package/digest [])
          (not= #{:package/family :package/version :root :graphs} (set (keys package)))
          (finding :semantic.package/schema [])
          (or (not= package-family (:package/family package))
              (not= package-version (:package/version package))
              (not (vector? (:graphs package))))
          (finding :semantic.package/schema [])
          :else
          (let [docs (mapv :document (:graphs package))
                root-ref (:root package)
                root (some (fn [d]
                             (when (= [(:graph/id root-ref) (:release/version root-ref)] (locator d)) d)) docs)
                rebuilt (build-compatible root docs checkers)]
            (if (seq (:findings rebuilt)) rebuilt
              (if (or (not= (:package rebuilt) package)
                      (not= (:content/hash root-ref)
                            (get-in rebuilt [:package :root :content/hash])))
                (finding :semantic.package/integrity [])
                (assoc rebuilt :package-digest expected-digest)))))))
    (catch Exception _ (finding :semantic.package/unreadable []))))

(defn build
  "Strict new-delivery entry point. Every embedded Graph carries its own guide;
   read-package remains compatible with existing immutable packages."
  [root documents checkers]
  (let [findings (vec (mapcat (fn [document]
                               (map #(assoc % :graph/id (get-in document [:graph :graph/id]))
                                    (:findings (contract/validate-deliverable document checkers))))
                             documents))]
    (if (seq findings) {:findings findings} (build-compatible root documents checkers))))

(def build-deliverable build)
