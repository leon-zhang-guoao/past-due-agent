(ns open.atlas.common.contract
  "Pure contract identification. Since 2026-09-19 (owner ruling, task book
   «contract-upgrade-and-retirement» PR1) the platform reads exactly one
   document version, Semantic contract 2.0. Identification is not model
   validation or a claim of runtime support."
  (:require [malli.core :as m]))

;; Contract 2.0 (doc 109 §109.8, owner 2026-09-18) -- the only readable
;; descriptor. The legacy reader and the 1.0-1.4 readers were retired on
;; 2026-09-19; a graph written against them is rebuilt with the
;; open-atlas-graph-builder skill, not migrated by the platform.
(def semantic-v2 {:contract/family "atlas-semantic" :contract/version "2.0.0"})

(def descriptor-schema
  [:map {:closed true}
   [:contract/family [:string {:min 1}]]
   [:contract/version [:string {:min 1}]]])

(def version-retired
  "The one finding every retired reader answers with. Shared so the contract
   layer, the artifact codec and the runtime refuse an old document with the
   same code and the same pointer to the upgrade path."
  {:code :contract/version-retired :severity :error :path [:contract]
   :message "only contract 2.0.0 is readable; rebuild the graph with the open-atlas-graph-builder skill"})

(defn retired?
  "True for a descriptor the platform once read and no longer does: a missing
   one (the legacy dialect document), the legacy family, or an atlas-semantic
   version other than 2.0.0. A descriptor of some other family is not retired,
   it was never ours (`:contract/unsupported`)."
  [descriptor]
  (or (nil? descriptor)
      (and (map? descriptor)
           (let [family (:contract/family descriptor)]
             (or (= "atlas-legacy" family)
                 (and (= "atlas-semantic" family)
                      (not= (:contract/version semantic-v2) (:contract/version descriptor))))))))

(defn- reject [code path]
  {:findings [{:code code :severity :error :path path}]})

(defn identify
  "Identify parsed EDN without modifying it. A document carries a :contract
   descriptor; a missing one is the retired legacy form, and partial reserved
   markers cannot downgrade a document to it. Never echoes values."
  [document]
  (cond
    (not (map? document)) (reject :contract/document-invalid [])
    (some #(contains? document %) [:contract/family :contract/version])
    (reject :contract/misplaced-descriptor [:contract])

    (not (contains? document :contract))
    {:findings [version-retired]}

    (not (m/validate descriptor-schema (:contract document)))
    (reject :contract/descriptor-invalid [:contract])

    :else {:contract (:contract document) :findings []}))
