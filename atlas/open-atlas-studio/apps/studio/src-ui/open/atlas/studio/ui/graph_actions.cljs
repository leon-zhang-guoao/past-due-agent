(ns open.atlas.studio.ui.graph-actions
  (:require [open.atlas.studio.ui.api :as api]))

(defn- uncertain-write? [{:keys [code status]}]
  (or (contains? #{:timeout :network :connection :bad-response} code)
      (and (number? status) (>= status 500))))

(defn publish-graph!
  "After an ambiguous write response, read the saved draft's serving status.
   A read can establish the desired state, not which caller published it.
   Never replay the write or claim that an unchanged pointer proves failure."
  [graph revision {:keys [on-success on-error on-checking]}]
  (api/publish-graph!
   graph
   {:on-success on-success
    :on-error
    (fn [error]
      (if-not (uncertain-write? error)
        (on-error error)
        (do
          (when on-checking (on-checking))
          (api/graph-cut-check!
           graph
           {:max-retries 0
            :on-success
            (fn [check]
              (let [status (:release-status check)
                    version (:serving-version status)]
                (if (and (some? revision)
                         (= revision (:config-revision check))
                         (= :current (:state status))
                         (some? version))
                  (on-success {:graph graph :version version :serving version
                               :reconciled? true})
                  (on-error (assoc error :publication-uncertain? true
                                  :message "The publish response was lost. Publication is not confirmed. Review the serving version and refresh status before trying again.")))))
            :on-error
            (fn [check-error]
              (on-error (assoc error :publication-uncertain? true
                              :status-check-error check-error
                              :message "The publish response was lost and status could not be checked. Use Refresh status after the connection recovers; the server may still be publishing.")))}))))}))

(defn delete-graph!
  "Apply a confirmed deletion locally before refreshing server state. A context
   change can discard that refresh, so it must not be needed to finish deletion."
  [request {:keys [set-graphs! clear-context! on-success on-error refresh!]}]
  (api/delete-graph!
   request
   {:on-error on-error
    :on-success (fn [result]
                  (if (seq (:findings result))
                    (on-error result)
                    (do
                      (set-graphs! #(filterv (fn [graph]
                                              (not= (:graph request) (:graph/qualified-name graph))) %))
                      (clear-context!)
                      (on-success)
                      (refresh!))))}))

(defn active-root
  "Keep execution on a live graph; archived rows remain available in Graphs."
  [graphs root landing]
  (let [live (filterv #(= :active (:graph/state %)) graphs)
        names (set (map :graph/qualified-name live))]
    (or (when (contains? names root) root)
        (when (contains? names landing) landing)
        (:graph/qualified-name (first live))
        "")))

(defn set-graph-state!
  "Apply the confirmed archive/restore response before any fallible refresh."
  [request state {:keys [set-graphs! on-success on-error refresh!]}]
  ((if (= state :archived) api/archive-graph! api/restore-graph!)
   request
   {:on-error on-error
    :on-success (fn [result]
                  (if (seq (:findings result))
                    (on-error result)
                    (do
                      (set-graphs!
                       #(mapv (fn [graph]
                                (if (= (:graph request) (:graph/qualified-name graph))
                                  (merge graph (:graph result) {:graph/state state})
                                  graph)) %))
                      (on-success)
                      (refresh!))))}))
