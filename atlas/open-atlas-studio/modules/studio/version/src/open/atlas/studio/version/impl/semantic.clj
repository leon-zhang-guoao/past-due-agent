(ns open.atlas.studio.version.impl.semantic
  "Seal a saved semantic draft into the existing immutable version shelf."
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.semantic-artifact :as semantic]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version.impl.store :as store]))

(defn cut! [conn {:keys [graph expected-revision artifact-root cut-at cut-by]}]
  (locking conn
    (let [db (pool/db conn) g (graph/graph db graph)
          draft (when g (pool/semantic-draft db (:graph/workspace g) (:graph/id g)))
          fail (fn [code] {:findings [{:code code :severity :error :path []}]})]
      (cond
        (not (and g (string? artifact-root) (seq artifact-root)
                  (string? cut-at) (seq cut-at) (string? cut-by) (seq cut-by)
                  (pos-int? expected-revision)))
        (fail :semantic.cut/request)
        (not= expected-revision (:revision draft)) (fail :semantic.draft/revision-conflict)
        :else
        (let [n (store/next-number db graph)
              document (assoc-in (:document draft) [:publication :release/version] (str n))
              sources (get-in document [:execution :sources])
              checkers (into {} (for [kind (set (map :source/kind (vals sources)))]
                                  [kind (or (:validate (spi/semantic-adapter kind)) (constantly false))]))
              delivery (contract/validate-deliverable document checkers)
              support (contract/check-support document semantic/runtime-capabilities)
              coords {:workspace (:graph/workspace g) :graph (:graph/name g) :version n}
              encoded (when (and (empty? (:findings delivery)) (empty? (:findings support))) (semantic/encode-candidate document {} checkers coords))]
          (cond
            (seq (:findings delivery)) delivery
            (seq (:findings support)) support
            (seq (:findings encoded)) encoded
            :else
            (let [written (artifact/write-artifact! artifact-root coords (:files encoded))]
              (if (seq (:findings written)) written
                (do (store/record! conn {:graph graph :number n :content-hash (:content-hash encoded)
                                        :artifact-digest (:artifact-digest encoded) :cut-at cut-at :cut-by cut-by})
                    (assoc (select-keys encoded [:content-hash :artifact-digest :findings])
                           :graph graph :version n :draft-revision expected-revision))))))))))
