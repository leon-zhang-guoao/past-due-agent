(ns open.atlas.studio.ui.api-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [async deftest is]]
            [open.atlas.studio.ui.api :as api]))

(deftest removal-requests-name-the-thing-and-carry-no-value
  ;; owner 2026-10-04: Source and variable removal are plan -> execute pairs;
  ;; the browser sends a name only.
  (async done
    (let [original-fetch js/fetch
          seen (atom [])
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [url options]
              (swap! seen conj [url (.-body options)])
              (js/Promise.resolve
               #js {:status 200 :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise.resolve (pr-str {:removable? true})))})))
      (api/source-removal-plan! "atlas-lens"
        {:on-success
         (fn [_]
           (api/remove-variable! "ATLAS_LENS_USER"
             {:on-success
              (fn [_]
                (is (re-find #"/api/studio/sources/removal/plan$" (ffirst @seen)))
                (is (= {:source "atlas-lens"} (reader/read-string (second (first @seen)))))
                (is (re-find #"/api/studio/credentials/remove$" (first (second @seen))))
                (is (= {:variable "ATLAS_LENS_USER"} (reader/read-string (second (second @seen)))))
                (finish))
              :on-error (fn [e] (is false (pr-str e)) (finish))}))
         :on-error (fn [e] (is false (pr-str e)) (finish))}))))

(deftest query-errors-retain-diagnostics-for-the-result-pane
  (async done
    (let [original-fetch js/fetch
          diagnostics {:sources [{:resolver-id "broken" :source-ref "flowx-pg"
                                  :causes [{:message "column missing" :sql-state "42703"}]}]}
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [& _]
              (js/Promise.resolve
               #js {:status 400 :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise.resolve
                                  (pr-str {:error {:code :engine.execution/failed
                                                   :message "source execution failed"
                                                   :diagnostics diagnostics}})))})))
      (api/execute! {:query [:test/value]}
                    {:on-success (fn [_] (is false "Expected a query failure") (finish))
                     :on-error (fn [error]
                                 (is (= diagnostics (:diagnostics error)))
                                 (finish))}))))

(deftest stalled-response-body-times-out-without-replaying-a-write
  (async done
    (let [original-fetch js/fetch
          signal (atom nil)
          finish-body (atom nil)
          calls (atom 0)
          successes (atom 0)
          errors (atom 0)
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [_ options]
              (swap! calls inc)
              (reset! signal (.-signal options))
              (js/Promise.resolve
               #js {:status 200 :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise. (fn [resolve _] (reset! finish-body resolve))))})))
      (api/publish-graph!
       "demo/orders"
       {:timeout-ms 20
        :on-success (fn [_] (swap! successes inc))
        :on-error
        (fn [error]
          (swap! errors inc)
          (is (= :timeout (:code error)))
          (is (true? (.-aborted @signal)))
          (is (= 1 @calls) "A timed-out publish must not be replayed")
          ;; Even a transport that ignores abort cannot report late success.
          (@finish-body "{:version 2 :serving 2}")
          (js/setTimeout
           (fn []
             (is (zero? @successes))
             (is (= 1 @errors))
             (finish)) 20))}))))

(deftest a-read-retry-aborts-the-old-request-and-uses-a-fresh-signal
  (async done
    (let [original-fetch js/fetch
          signals (atom [])
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [_ options]
              (swap! signals conj (.-signal options))
              (if (= 1 (count @signals))
                (js/Promise. (fn [_ _]))
                (js/Promise.resolve
                 #js {:status 200 :headers #js {:get (fn [_] nil)}
                      :text (fn [] (js/Promise.resolve "{:users []}"))}))))
      (api/studio-users!
       {:timeout-ms 20 :max-retries 1
        :on-success
        (fn [result]
          (is (= {:users []} result))
          (is (= 2 (count @signals)))
          (is (true? (.-aborted (first @signals))))
          (js/setTimeout
           (fn []
             (is (false? (.-aborted (second @signals))) "Completed reads clear their deadline")
             (finish)) 30))
        :on-error (fn [error] (is false (str error)) (finish))}))))

(deftest failed-writes-are-not-replayed-but-reads-can-recover
  (async done
    (let [original-fetch js/fetch
          calls (atom [])
          finish (fn [] (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [_ options]
              (swap! calls conj (.-method options))
              (js/Promise.resolve
               #js {:status 503
                    :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise.resolve
                                  "{:error {:code :unavailable :message \"Try later\"}}"))})))
      (api/create-studio-user!
       {:name "Release Test" :email "release@example.test" :password "test-password-only"}
       {:on-success (fn [_] (is false "The failed write must report an error") (finish))
        :on-error
        (fn [error]
          (is (= ["POST"] @calls) "A 503 does not prove the write was not committed")
          (is (= :unavailable (:code error)))
          (is (string? (:request-id error)))
          (is (= 1 (:attempt error)))
          (reset! calls [])
          (set! js/fetch
                (fn [_ options]
                  (swap! calls conj (.-method options))
                  (if (= 1 (count @calls))
                    (js/Promise.reject (js/Error. "Temporary network failure"))
                    (js/Promise.resolve
                     #js {:status 200
                          :headers #js {:get (fn [_] nil)}
                          :text (fn [] (js/Promise.resolve "{:users []}"))}))))
          (api/studio-users!
           {:on-success (fn [result]
                          (is (= ["GET" "GET"] @calls))
                          (is (= {:users []} result))
                          (finish))
            :on-error (fn [_] (is false "The read should recover on retry") (finish))}))}))))

(deftest duplicate-findings-do-not-become-a-successful-preview-or-copy
  (async done
    (let [original-fetch js/fetch
          finding {:code :semantic.duplicate/stale-plan :severity :error :path [:plan-token]}
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [_ _]
              (js/Promise.resolve
               #js {:status 200 :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise.resolve (pr-str {:findings [finding]})))})))
      (api/duplicate-graph-plan!
       {:graph "demo/source" :name "copy" :namespace "copy"}
       {:on-success (fn [_] (is false "A refusal cannot be rendered as a plan") (finish))
        :on-error (fn [result]
                    (is (= [finding] (:findings result)))
                    (api/apply-duplicate-graph!
                     {:graph "demo/source" :plan-token "old"}
                     {:on-success (fn [_] (is false "A refusal cannot be reported as a created Graph") (finish))
                      :on-error (fn [result] (is (= [finding] (:findings result))) (finish))}))}))))

(deftest forbidden-errors-identify-the-request-and-do-not-retry
  (async done
    (let [original-fetch js/fetch
          calls (atom 0)
          finish #(do (set! js/fetch original-fetch) (done))]
      (set! js/fetch
            (fn [_ _]
              (swap! calls inc)
              (js/Promise.resolve
               #js {:status 403 :headers #js {:get (fn [_] nil)}
                    :text (fn [] (js/Promise.resolve "{:error {:code :workspace-owner-required}}"))})))
      (api/archive-graph!
       {:graph "demo/orders"}
       {:on-success (fn [_] (is false "A forbidden archive must fail") (finish))
        :on-error (fn [error]
                    (is (= 1 @calls))
                    (is (= 403 (:status error)))
                    (is (= "POST" (:method error)))
                    (is (= "/api/studio/graphs/archive" (:path error)))
                    (is (= :workspace-owner-required (:code error)))
                    (finish))}))))
