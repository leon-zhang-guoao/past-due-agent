(ns open.atlas.studio.ui.clipboard-test
  (:require [cljs.test :refer [async deftest is]]
            [open.atlas.studio.ui.clipboard :as clipboard]))

(defn- fake-document [copied?]
  (let [events (atom [])
        field #js {:style #js {} :focus #(swap! events conj :focus)
                   :select #(swap! events conj :select)
                   :setSelectionRange (fn [start end] (swap! events conj [start end]))}
        body #js {}]
    ;; Define after body exists rather than capture a not-yet-bound local.
    (set! (.-appendChild body) (fn [f] (set! (.-parentNode f) body) (swap! events conj :append)))
    (set! (.-removeChild body) (fn [f] (set! (.-parentNode f) nil) (swap! events conj :remove)))
    {:field field :events events
     :document #js {:body body :createElement (fn [_] field)
                    :activeElement #js {:focus #(swap! events conj :restore-focus)}
                    :execCommand (fn [command]
                                   (swap! events conj [command (.-value field)]) copied?)}}))

(deftest copies-on-http-and-on-native-api-rejection
  (async done
    (let [run-case (fn [navigator]
                     (let [{:keys [document field events]} (fake-document true)
                           result (clipboard/copy-text! "test-token" navigator document)]
                       (-> result
                           (.then (fn [_]
                                    (is (some #{["copy" "test-token"]} @events))
                                    (is (= "" (.-value field)))
                                    (is (nil? (.-parentNode field)))
                                    (is (= :restore-focus (last @events))))))))]
      (-> (run-case #js {})
          (.then #(run-case #js {:clipboard #js {:writeText (fn [_] (js/Promise.reject (js/Error. "denied")))}}))
          (.then #(run-case #js {:clipboard #js {:writeText (fn [_] (throw (js/Error. "denied")))}}))
          (.then #(done))
          (.catch (fn [e] (is false (str e)) (done)))))))

(deftest reports-failure-without-leaving-a-secret-in-the-dom
  (async done
    (let [{:keys [document field]} (fake-document false)]
      (-> (clipboard/copy-text! "test-token" #js {} document)
          (.then (fn [_] (is false "A refused copy must not report success") (done)))
          (.catch (fn [_]
                    (is (= "" (.-value field)))
                    (is (nil? (.-parentNode field)))
                    (done)))))))

(deftest native-copy-does-not-create-a-temporary-field
  (async done
    (let [copied (atom nil)]
      (-> (clipboard/copy-text! "test-token"
                               #js {:clipboard #js {:writeText (fn [s] (reset! copied s) (js/Promise.resolve nil))}}
                               #js {:createElement (fn [_] (is false "No fallback on native success"))})
          (.then (fn [_] (is (= "test-token" @copied)) (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))
