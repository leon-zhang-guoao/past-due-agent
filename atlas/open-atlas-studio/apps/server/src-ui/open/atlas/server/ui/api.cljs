(ns open.atlas.server.ui.api
  "The one place the console UI talks to its Server.

   Same-origin only, and the browser supplies no principal: the session cookie
   is the whole of the caller's identity, and it is `HttpOnly` — this code
   cannot read it, which is the point.

   The console session transport stays app-specific. Graph access components
   and clipboard behavior are shared with Studio through common/ui."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [uix.core :as uix]))

(defn- parse [text]
  (try
    (reader/read-string text)
    (catch :default e
      {::unreadable (str "the server sent unreadable EDN: " (.-message e))})))

(defn request!
  "`{:method :path :body :on-ok :on-error}`. `on-error` receives a map with
   `:message` and, when the server sent them, `:findings` — the console shows
   the structured refusal rather than inventing a sentence for it."
  [{:keys [method path body on-ok on-error]}]
  (-> (js/fetch path
                (clj->js (cond-> {:method (or method "GET")
                                  :credentials "same-origin"
                                  :headers {"Accept" "application/edn"}}
                           body (assoc :body (pr-str body)
                                       :headers {"Accept" "application/edn"
                                                 "Content-Type" "application/edn"}))))
      (.then (fn [response]
               (.then (.text response)
                      (fn [text] #js [(.-status response) text]))))
      (.then (fn [pair]
               (let [status (aget pair 0)
                     value  (parse (aget pair 1))]
                 (cond
                   ;; A 401 is not an error to shout about — it is the console
                   ;; discovering it is signed out, which the app turns back
                   ;; into the sign-in screen.
                   (= 401 status)
                   (do
                     (when-not (str/starts-with? path "/api/console/auth/")
                       (.dispatchEvent js/window (js/Event. "atlas-session-expired")))
                     (when on-error (on-error {:unauthenticated? true
                                              :message "Your session has ended. Please sign in again."})))

                   (::unreadable value)
                   (when on-error (on-error {:message (::unreadable value)}))

                   ;; Every endpoint answers an EDN map. Anything else must
                   ;; surface as a failure rather than flow onward as a map
                   ;; whose every key is nil.
                   (not (map? value))
                   (when on-error (on-error {:message "the server sent a non-map response"}))

                   (or (>= status 400) (= :error (:status value)))
                   (when on-error (on-error {:message (or (:error value) "the request was refused")
                                             :findings (:findings value)}))

                   :else (when on-ok (on-ok value))))))
      (.catch (fn [e] (when on-error (on-error {:message (str "the server is unreachable: "
                                                              (.-message e))}))))))

(defn use-read
  "A late response must never replace the selected Graph, search or page.
   Superseded payloads also stop rendering before the next effect starts."
  [path revision]
  (let [[response set-response!] (uix/use-state nil)
        request-key [path revision]]
    (uix/use-effect
     (fn []
       (let [live? (atom true)]
         (when path
           (request! {:path path
                          :on-ok #(when @live? (set-response! {:key [path revision] :data %}))
                          :on-error #(when @live? (set-response! {:key [path revision] :error %}))}))
         #(reset! live? false)))
     [path revision])
    (if (= request-key (:key response)) response {:loading? (some? path) :previous-data (:data response)})))
