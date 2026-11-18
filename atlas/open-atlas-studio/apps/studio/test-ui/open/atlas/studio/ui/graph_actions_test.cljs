(ns open.atlas.studio.ui.graph-actions-test
  (:require [cljs.test :refer [deftest is testing]]
            [open.atlas.studio.ui.api :as api]
            [open.atlas.studio.ui.graph-actions :as actions]))

(deftest a-lost-publish-response-is-verified-without-replaying-the-write
  (let [calls (atom [])
        result (atom nil)]
    (with-redefs [api/publish-graph! (fn [_ opts]
                                     (swap! calls conj :publish)
                                     ((:on-error opts) {:code :timeout :request-id "request-1"}))
                  api/graph-cut-check! (fn [_ opts]
                                         (swap! calls conj :check)
                                         (is (= 0 (:max-retries opts)))
                                         ((:on-success opts)
                                          {:config-revision 4
                                           :release-status {:state :current :serving-version 9}}))]
      (actions/publish-graph! "demo/orders" 4
                             {:on-checking #(swap! calls conj :checking)
                              :on-success #(reset! result %)
                              :on-error #(is false (str %))}))
    (is (= [:publish :checking :check] @calls))
    (is (= {:graph "demo/orders" :version 9 :serving 9 :reconciled? true} @result))))

(deftest publication-cannot-be-confirmed-by-another-draft-or-an-unreadable-status
  (doseq [check [{:config-revision 5 :release-status {:state :current :serving-version 9}}
                {:config-revision 4 :release-status {:state :changed :serving-version 8}}
                {:config-revision 4 :release-status {:state :current}}
                {:findings [{:code :unavailable}]}
                :network-failure]]
    (let [error (atom nil)
          writes (atom 0)]
      (with-redefs [api/publish-graph! (fn [_ opts]
                                       (swap! writes inc)
                                       ((:on-error opts) {:code :network :request-id "request-1"}))
                    api/graph-cut-check! (fn [_ opts]
                                           (if (= check :network-failure)
                                             ((:on-error opts) {:code :timeout})
                                             ((:on-success opts) check)))]
        (actions/publish-graph! "demo/orders" 4
                               {:on-success #(is false (str "Unproven success: " %))
                                :on-error #(reset! error %)}))
      (is (= 1 @writes))
      (is (:publication-uncertain? @error))
      (is (= "request-1" (:request-id @error)))
      (when (= check :network-failure)
        (is (= :timeout (get-in @error [:status-check-error :code])))))))

(deftest publication-refusals-are-not-reinterpreted-as-transport-failures
  (let [refusal {:status 403 :code :workspace-owner-required}
        observed (atom nil)]
    (with-redefs [api/publish-graph! (fn [_ opts] ((:on-error opts) refusal))
                  api/graph-cut-check! (fn [& _] (is false "No recovery for a permission refusal"))]
      (actions/publish-graph! "demo/orders" 4
                             {:on-success #(is false (str %))
                              :on-error #(reset! observed %)}))
    (is (= refusal @observed))))

(deftest deletion-completes-without-a-graph-list-refresh
  (doseq [remaining [[] [{:graph/qualified-name "victor-li/other" :graph/state :active}]]]
    (testing (str "Remaining graphs: " remaining)
      (let [deleted {:graph/qualified-name "victor-li/victor-test" :graph/state :archived}
            graphs (atom (into [deleted] remaining))
            busy? (atom true)
            context (atom 0)
            callbacks (atom nil)
            refresh-token (atom nil)]
        (with-redefs [api/delete-graph! (fn [_ opts] (reset! callbacks opts))]
          (actions/delete-graph!
           {:graph (:graph/qualified-name deleted)}
           {:set-graphs! #(swap! graphs %)
            :clear-context! #(swap! context inc)
            :on-success #(reset! busy? false)
            :on-error #(is false (str %))
            :refresh! #(reset! refresh-token @context)}))
        (is @busy?)
        (is (= (into [deleted] remaining) @graphs))
        ((:on-success @callbacks) {:findings []})
        ;; React's selection effect changes context after the callback, making
        ;; the refresh stale. The confirmed deletion must already be visible.
        (swap! context inc)
        (is (not= @refresh-token @context))
        (is (= remaining @graphs))
        (is (false? @busy?))))))

(deftest deletion-refusals-preserve-the-graph-and-release-the-form
  (doseq [response [:on-error :on-success]]
    (let [failure {:findings [{:code :graph-archive-required}]}
          error (atom nil)
          busy? (atom true)]
      (with-redefs [api/delete-graph! (fn [_ opts] ((response opts) failure))]
        (actions/delete-graph!
         {:graph "victor-li/victor-test"}
         {:set-graphs! (fn [_] (is false "A refused graph must remain"))
          :clear-context! #(is false "Keep the current context on refusal")
          :on-success #(is false "Do not close a refused deletion")
          :on-error #(do (reset! error %) (reset! busy? false))
          :refresh! #(is false "No refresh after refusal")}))
      (is (= failure @error))
      (is (false? @busy?)))))

(deftest archive-and-restore-survive-a-failed-list-refresh
  (doseq [state [:archived :active]]
    (let [row {:graph/qualified-name "demo/订单" :graph/state :active :graph/can-manage? true}
          graphs (atom [row])
          calls (atom [])
          callback (atom nil)
          fake-api (fn [_ opts] (reset! callback opts))]
      (with-redefs [api/archive-graph! fake-api api/restore-graph! fake-api]
        (actions/set-graph-state!
         {:graph "demo/订单"} state
         {:set-graphs! #(swap! graphs %)
          :on-success #(swap! calls conj :done)
          :on-error #(is false (str %))
          :refresh! #(swap! calls conj :refresh)}))
      ((:on-success @callback) {:graph (assoc row :graph/state state) :findings []})
      (is (= state (:graph/state (first @graphs))))
      (is (:graph/can-manage? (first @graphs)))
      (is (= [:done :refresh] @calls)))))

(deftest refused-archive-keeps-local-state
  (doseq [response [:on-error :on-success]]
    (let [failure {:findings [{:code :workspace-owner-required}]}
          error (atom nil)]
      (with-redefs [api/archive-graph! (fn [_ opts] ((response opts) failure))]
        (actions/set-graph-state!
         {:graph "demo/orders"} :archived
         {:set-graphs! (fn [_] (is false "No local archive on refusal"))
          :on-success #(is false "No success on refusal")
          :on-error #(reset! error %)
          :refresh! #(is false "No refresh on refusal")}))
      (is (= failure @error)))))

(deftest execution-never-falls-back-to-an-archived-graph
  (let [archived {:graph/qualified-name "demo/old" :graph/state :archived}
        live {:graph/qualified-name "demo/live" :graph/state :active}
        landing {:graph/qualified-name "demo/landing" :graph/state :active}]
    (is (= "" (actions/active-root [archived] "demo/old" "demo/old")))
    (is (= "demo/live" (actions/active-root [archived live] "demo/old" "demo/old")))
    (is (= "demo/landing" (actions/active-root [archived live landing] "demo/old" "demo/landing")))
    (is (= "demo/live" (actions/active-root [live landing] "demo/live" "demo/landing")))
    (is (= "" (actions/active-root [] "demo/deleted" nil)))))
