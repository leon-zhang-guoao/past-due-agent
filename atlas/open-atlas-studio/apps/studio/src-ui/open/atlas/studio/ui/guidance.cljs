(ns open.atlas.studio.ui.guidance
  (:require [clojure.string :as str]
            [uix.core :refer [defui $]]))

(defn error-guidance [error]
  (let [codes (set (keep :code (cons error (:findings error))))
        revision? (some #(str/includes? (str %) "revision-conflict") codes)]
    (cond
      (:publication-uncertain? error)
      {:title "Publication result not confirmed"
       :next "Use Refresh status in Publish to check the serving version. A lost response does not mean the operation failed."}
      (or (= 401 (:status error)) (some codes [:authentication-required :unauthorized :session-expired]))
      {:title "Sign-in required"
       :next "Keep a copy of any unsaved edits, sign in again, then refresh this page."}
      (or (= 403 (:status error)) (some codes [:forbidden :workspace-owner-required]))
      {:title "Your account cannot perform this action"
       :next "Ask a Workspace owner to check your access. Retrying the same action will not change your permissions."}
      revision?
      {:title "The saved model has changed"
       :next "Keep your edits, reload the latest saved revision, and compare the changes before saving again."}
      (= 409 (:status error))
      {:title "This action conflicts with the current state"
       :next "Refresh the relevant status and review the latest state before trying again."}
      (= 429 (:status error))
      {:title "Too many requests"
       :next "Wait briefly before trying again. Avoid starting several copies of the same action."}
      (some codes [:timeout :network :connection :upstream-timeout :upstream-connection])
      {:title "Studio could not complete the request"
       :next (if (and (:method error) (not= "GET" (:method error)))
               "Check the saved or serving state before retrying: the operation may have completed even though its response was lost."
               "Check the connection and refresh this view. If the problem continues, share the request reference below with support.")}
      (seq (:findings error))
      {:title "Review the items below"
       :next "Use each item's location and expected value to correct the model or configuration, then validate again."}
      :else
      {:title "Action could not be completed"
       :next "Review the message below. Keep any unsaved edits before refreshing; share the request reference if you need help."})))

(defn source-summary [rows]
  (let [needs (count (remove #(contains? #{:ready :not-required} (:status %)) rows))]
    {:total (count rows) :needs-attention needs :configured (- (count rows) needs)}))

(defn graph-guidance [{:keys [tab archived? can-edit? serving]}]
  (cond
    archived?
    {:title "This Graph is archived"
     :message "A Workspace owner can restore it from Graph actions (⋯). Its model and version history are retained."}
    (not can-edit?)
    {:title "Browse this Graph"
     :message "You can explore the model. Import, Source configuration and publication require editor access."
     :action (when (not= tab :explorer) :explorer) :label "Explore model"}
    ;; One line each, in the order the tabs are: connections → model → publish.
    (= tab :sources)
    {:title "1 · Connect"
     :message "Choose what this model reads."
     :action :semantic :label "Next: Model →"}
    (= tab :semantic)
    {:title "2 · Model"
     :message "Import a model and bind its resolvers. Saving a draft does not change the serving version."
     :action :release :label "Next: Publish →"}
    (= tab :explorer)
    {:title "Explore"
     :message "Read the saved draft's relationships."
     :action :semantic :label "Edit model"}
    (= tab :release)
    {:title (if serving "3 · Publish changes" "3 · Publish")
     :message "Check readiness, publish, then connect a client."}
    :else nil))

(defui graph-guide [{:keys [on-navigate] :as props}]
  (when-let [{:keys [title message action label]} (graph-guidance props)]
    ($ :aside.graph-guidance {:aria-label "Graph workflow guidance"}
       ($ :div ($ :strong title) ($ :p message))
       (when action
         ($ :button.btn {:type "button" :data-settings-navigation true
                         :on-click #(on-navigate action)} label)))))
