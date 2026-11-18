(ns open.atlas.server.ui.app
  "The standalone Server's operator console (8.12 P4).

   Four things and no more: sign in, see what this instance is serving, import
   a graph, and issue or revoke the tokens that read it — plus this instance's
   own records. It is deliberately not a Builder: there is no pool here, so
   there is nothing to model.

   ## What the UI is not allowed to decide

   Every refusal on screen is the server's finding, rendered — never a sentence
   this code made up from a status code. When the console says why an import
   was refused, an operator can search that `:code` and find the rule; a
   paraphrase would send them looking for a rule that does not exist.

   The same rule governs the numbers. Every figure in the rail and the state
   strip is either returned by the server or counted from what the server
   returned. Nothing here is estimated, and a figure this instance cannot
   answer is absent rather than approximated — an operator who cannot tell a
   measured zero from an unavailable one will read the wrong one as the other."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [open.atlas.common.ui.access :as access]
            [open.atlas.common.ui.query-details :as query-details]
            [open.atlas.server.ui.variables :as variables]
            [open.atlas.common.ui.source-bindings :as source-bindings]
            [open.atlas.common.ui.pagination :refer [pager]]
            [open.atlas.server.ui.api :as api]
            [open.atlas.server.ui.components :as components]
            [open.atlas.server.ui.graph-browser :as graph-browser]
            [open.atlas.server.ui.source-maintenance :as source-maintenance]
            [uix.core :as uix :refer [defui $]]
            [uix.dom]))

;; --- shared bits -------------------------------------------------------------

(def findings-list components/findings-list)
(def notice components/notice)

(defn- ago [iso] (or iso "—"))

(defn- clock
  "The time-of-day half of an ISO stamp, for the one place that is read at a
   glance. The whole stamp stays in `title` — shortening a value on screen is
   allowed; losing it is not."
  [iso]
  (if-let [t (second (str/split (str iso) #"T"))]
    (first (str/split t #"[.Z]"))
    (or iso "—")))

(defn- fmt-version
  "A token's coordinate is either a pinned number or `:latest` — the serving
   pointer's own name for whatever is live (R3.6). Prefixing a `v` onto the
   keyword produced `v:latest`, which reads as a version that does not exist."
  [v]
  (if (number? v) (str "v" v) (str v)))

(defn- fmt-int
  "Thin space every three digits. Counters are read at a glance and compared
   against the last look; unseparated six-digit runs are what make that fail."
  [n]
  (if (number? n)
    (->> (str (long n)) reverse (partition-all 3) (map #(apply str (reverse %)))
         reverse (str/join " "))
    "—"))

(defn- fmt-uptime
  "Coarsest two units that are non-zero. A freshly restarted process reading
   `0h 0m` is the one moment uptime matters most, and that is exactly when the
   two-fixed-unit form says nothing."
  [ms]
  (if (number? ms)
    (let [s (quot ms 1000) d (quot s 86400)
          h (quot (rem s 86400) 3600) m (quot (rem s 3600) 60)]
      (cond (pos? d) (str d "d " h "h")
            (pos? h) (str h "h " m "m")
            (pos? m) (str m "m " (rem s 60) "s")
            :else    (str s "s")))
    "—"))

(def card components/card)

;; --- sign in -----------------------------------------------------------------

(defui sign-in [{:keys [bootstrapped? oidc on-signed-in]}]
  (let [[username set-username!] (uix/use-state "")
        [password set-password!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)
        ;; One form, two meanings, decided by the server: with no operator yet
        ;; this REGISTERS the first one, and afterwards it signs in. The server
        ;; answers 409 to a second registration, so the two cannot be confused
        ;; by a stale page.
        path (if bootstrapped? "/api/console/auth/sign-in" "/api/console/auth/register")
        submit (fn [e]
                 (.preventDefault e)
                 (set-error! nil)
                 (api/request! {:method "POST" :path path
                                :body {:username username :password password}
                                :on-ok #(on-signed-in (:operator %))
                                :on-error set-error!}))]
    ($ :div.signin
       ($ :form.card {:on-submit submit}
          ($ :h1 "Open Atlas Server")
          (when-not bootstrapped?
            ($ :div.empty
               ($ :p "This instance has no admin yet. The account you create here is the first one — after that, this form signs in.")))
          ($ :label "Username"
             ($ :input {:value username :auto-focus true
                        :on-change #(set-username! (.. % -target -value))}))
          ($ :label "Password"
             ($ :input {:type "password" :value password
                        :on-change #(set-password! (.. % -target -value))}))
          ($ :button.primary {:type "submit"}
             (if bootstrapped? "Sign in" "Create the first admin"))
          ($ notice {:error error})
          ;; A plain link, not a fetch: the route answers 302 to the identity
          ;; provider, and following redirects across origins is the browser's
          ;; job. Drawn only when the server said it has a provider, so this
          ;; page can never offer a sign-in this server cannot perform.
          (when (and bootstrapped? oidc)
            ($ :<>
               ($ :div.or "or")
               ($ :a.button.quiet {:href "/api/console/auth/oidc"}
                  (str "Continue with " (or (:name oidc) "Single Sign-On")))))
          (when-not bootstrapped?
            ($ :p.muted
               "Prefer the command line? "
               ($ :code.code "bin/bootstrap-admin.sh --username admin")
               " reads the password from stdin."))))))

;; --- graphs ------------------------------------------------------------------

(defui import-panel [{:keys [on-imported expected-graph-id group-id]}]
  (let [[busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        [payload set-payload!] (uix/use-state nil)
        [plan set-plan!] (uix/use-state nil)
        [selection set-selection!] (uix/use-state {})
        check (fn [payload selection]
                (set-busy! true) (set-error! nil)
                (api/request! {:method "POST" :path "/api/console/graphs/import/plan"
                               :body (assoc payload :source-bindings selection)
                               :on-ok (fn [r] (set-busy! false) (set-plan! r))
                               :on-error (fn [e] (set-busy! false) (set-plan! nil) (set-error! e))}))
        publish (fn []
                  (set-busy! true) (set-error! nil)
                  (api/request! {:method "POST" :path "/api/console/graphs/import"
                                 :body (cond-> (assoc payload :activate? false :source-bindings selection :expected-source-revision (:source-revision plan))
                                         expected-graph-id (assoc :expected-graph-id expected-graph-id)
                                         group-id (assoc :group-id group-id))
                                 :on-ok (fn [r] (set-busy! false) (set-payload! nil) (set-plan! nil) (on-imported r))
                                 :on-error (fn [e] (set-busy! false) (set-error! e))}))
        read-file (fn [file]
                    (when file
                      (set-plan! nil) (set-payload! nil) (set-selection! {}) (set-error! nil)
                      (set-busy! true)
                      (let [reader (js/FileReader.)]
                        (set! (.-onerror reader) #(do (set-busy! false) (set-error! {:message "Could not read the file."})))
                        (set! (.-onload reader)
                              (fn [] (let [payload {:bundle (second (str/split (.-result reader) #","))}]
                                       (set-payload! payload) (check payload {}))))
                        (.readAsDataURL reader file))))]
    ($ card {:title "Import Graph" :hint "Review a Studio delivery bundle and configure production Sources"}
       ($ :label {:class (str "drop" (when busy? " busy"))
                  :on-drag-over #(.preventDefault %)
                  :on-drop (fn [e] (.preventDefault e)
                             (when-not busy? (read-file (aget (.. e -dataTransfer -files) 0))))}
          ($ :input {:type "file" :accept ".zip" :disabled busy?
                     :on-change #(read-file (aget (.. % -target -files) 0))})
          ($ :div.big (if busy? "Working…" "Choose a Studio release bundle (.zip)")))
       (when plan
         ($ :div
            ($ :p (str "Graph: " (:graph-id plan) " · Version: " (:version plan)))
            (when (seq (:sources plan))
              ($ :section
                 ($ :h3 "Assign resolver Sources")
                 ($ :p.muted "Choose from the shared Server connections. Resolvers sharing a Source slot use the same connection; a connection can serve many Graphs.")
                 ($ source-bindings/resolver-bindings
                    {:sources (:sources plan) :selection selection :disabled? busy?
                     :on-change (fn [slot value]
                                  (let [next (if (seq value) (assoc selection slot value) (dissoc selection slot))]
                                    (set-selection! next) (check payload next)))})
                 ($ :p.muted "All required bindings and credentials must be ready before importing and publishing. Maintain connections in Sources; return here and Check readiness.")))
            ($ findings-list {:findings (:findings plan)})
            ($ :button.quiet {:disabled busy? :on-click #(check payload selection)} "Check readiness")
            (when (and expected-graph-id (not= expected-graph-id (:graph-id plan)))
              ($ :p.notice "This bundle belongs to another Graph. Choose a release for " expected-graph-id "."))
            ($ :button {:disabled (or busy? (not (:would-import? plan))
                                     (and expected-graph-id (not= expected-graph-id (:graph-id plan)))) :on-click publish}
               (if expected-graph-id "Import new version" "Import Graph"))
            ($ :p.muted "Import creates a production version. Review and Publish the Graph to start serving it.")))
       ($ notice {:error error}))))

(defui package-row
  "One catalogue row. Everything shown here — the version, what is missing,
   whether it may be installed — is the server's own plan for THIS bundle on
   THIS machine, rendered. The button is disabled by `:would-import?` and by
   nothing this code worked out for itself."
  [{:keys [package on-installed group-id]}]
  (let [[busy? set-busy!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)
        installed (:installed-version package)
        install (fn []
                  (set-busy! true)
                  (api/request! {:method "POST" :path "/api/console/packages/install"
                                 :body (cond-> {:file (:file package) :activate? false}
                                         group-id (assoc :group-id group-id))
                                 :on-ok (fn [r] (set-busy! false) (set-error! nil)
                                          (on-installed r))
                                 :on-error (fn [e] (set-busy! false) (set-error! e))}))]
    ($ :article.graph
       ($ :header
          ($ :span.graph-id (or (:package package) (:graph-id package)))
          ($ :span.badge (str "v" (:version package)))
          (when installed ($ :span.badge.on (str "Installed v" installed)))
          ($ :div.actions
             ($ :button.primary
                {:disabled (or busy? (not (:would-import? package)))
                 :on-click install}
                (cond busy? "Importing…"
                      installed "Import again"
                      :else "Import Graph"))))
       ($ :div.cols
          ($ :div
             ($ :p.muted (:summary package))
             ;; The plan's findings, not a sentence about them: an operator who
             ;; can read `:credential/missing NIFI_CLIENT_STORE_PASS` (doc 106
             ;; §106.14-20) knows exactly what to set, and a paraphrase would
             ;; not say which name.
             ($ findings-list {:findings (:findings package)})
             ($ notice {:error error}))))))

(defui import-tools [{:keys [on-imported expected-graph-id group-id]}]
  (let [[packages set-packages!] (uix/use-state nil)
        [show-packages? set-show-packages!] (uix/use-state false)
        [error set-error!] (uix/use-state nil)]
    (uix/use-effect
     (fn []
       (when show-packages?
         (api/request! {:path "/api/console/packages" :on-ok #(set-packages! (:packages %)) :on-error set-error!}))
       js/undefined) [show-packages?])
    ($ :div.import-tools
       ($ import-panel {:on-imported on-imported :expected-graph-id expected-graph-id :group-id group-id})
       (when-not expected-graph-id
         ($ :<>
            ($ :button.quiet {:on-click #(set-show-packages! (not show-packages?))}
               (if show-packages? "Hide bundled packages" "Browse bundled packages"))
            (when show-packages?
              ($ :<>
                 ($ notice {:error error})
                 (if (nil? packages) ($ :p "Loading packages…")
                   ($ card {:title "Bundled packages"}
                      (for [package packages]
                        ($ package-row {:key (:file package) :package package :group-id group-id
                                        :on-installed on-imported})))))))))))

;; --- operators ---------------------------------------------------------------

(defui new-operator-form [{:keys [reload]}]
  (let [[username set-username!] (uix/use-state "")
        [display set-display!] (uix/use-state "")
        [password set-password!] (uix/use-state "")
        [sso-email set-sso-email!] (uix/use-state "")
        [error set-error!] (uix/use-state nil)
        ;; A password, an SSO email, or both (doc 8.16.5). Checked here so the
        ;; form does not post a request whose only outcome is a refusal — and
        ;; checked again by the server, which is where the rule actually lives
        ;; (`:identity.account/credentials-required`).
        credentials? (or (not (str/blank? password)) (not (str/blank? sso-email)))
        submit (fn [e]
                 (.preventDefault e)
                 (set-error! nil)
                 (api/request! {:method "POST" :path "/api/console/operators"
                                :body (cond-> {:username username :display-name display}
                                        (not (str/blank? password))  (assoc :password password)
                                        (not (str/blank? sso-email)) (assoc :sso-email sso-email))
                                :on-ok (fn [_]
                                         (set-username! "") (set-display! "")
                                         (set-password! "") (set-sso-email! "") (reload))
                                :on-error set-error!}))]
    ($ card
       {:title "New admin"
        :hint "a password, an SSO email, or both — there are no roles yet"}
       ;; A grid of labelled fields; autocomplete is off because this form creates
       ;; SOMEONE ELSE's account, and the browser filling in your own saved password
       ;; and email here would create it with your credentials.
       ($ :form.operator-form {:on-submit submit :auto-complete "off"}
          ($ :label "Username"
             ($ :input {:value username :auto-complete "off"
                        :on-change #(set-username! (.. % -target -value))}))
          ($ :label "Display name"
             ($ :input {:value display :auto-complete "off"
                        :on-change #(set-display! (.. % -target -value))}))
          ($ :label "Password"
             ($ :input {:type "password" :value password :auto-complete "new-password"
                        :placeholder "optional"
                        :on-change #(set-password! (.. % -target -value))}))
          ($ :label "SSO email"
             ($ :input {:type "email" :value sso-email :placeholder "optional" :auto-complete "off"
                        :on-change #(set-sso-email! (.. % -target -value))}))
          ($ :p.muted
             "Leave the password empty to pre-register an account that can only sign in through single sign-on. The link is made the first time that address signs in.")
          ($ :button.primary {:type "submit" :disabled (not credentials?)}
             "Create admin"))
       ($ notice {:error error}))))

(defui operators-page [{:keys [operators reload]}]
  (let [[error set-error!] (uix/use-state nil)
        toggle (fn [op]
                 (api/request! {:method "POST" :path "/api/console/operators/disabled"
                                :body {:username (:username op)
                                       :disabled? (not (:disabled? op))}
                                :on-ok (fn [_] (reload)) :on-error set-error!}))
        reset-password
        (fn [op]
          ;; Prompted rather than typed into a field on the row: a password box
          ;; sitting open on every row is one stray click away from being
          ;; submitted for the wrong account.
          (when-let [pw (js/prompt (str "New password for " (:username op)))]
            (when-not (str/blank? pw)
              (api/request! {:method "POST" :path "/api/console/operators/password"
                             :body {:username (:username op) :password pw}
                             :on-ok (fn [_] (reload)) :on-error set-error!}))))
        ;; Doc 101 §8.16.9 item 3: link an existing operator by pre-registering
        ;; the address its identity provider will assert. Empty clears it.
        set-sso-email
        (fn [op]
          (let [v (js/prompt (str "SSO email for " (:username op)
                                  " (the address the identity provider asserts; empty clears it)")
                             (or (:sso-email op) ""))]
            (when (some? v)
              (api/request! {:method "POST" :path "/api/console/operators/sso-email"
                             :body {:username (:username op) :sso-email (str/trim v)}
                             :on-ok (fn [_] (reload)) :on-error set-error!}))))]
    ($ :<>
       ($ card
          {:title "Admins"
           :hint (str (count (remove :disabled? operators)) " enabled · "
                      (count (filter :disabled? operators)) " disabled")}
          ($ notice {:error error})
          ($ :table.table
             ($ :thead ($ :tr ($ :th "admin") ($ :th "display name") ($ :th "state")
                           ($ :th "sso") ($ :th "created") ($ :th.act "")))
             ($ :tbody
                (for [op operators]
                  ($ :tr {:key (:id op) :class (when (:disabled? op) "revoked")}
                     ($ :td (:username op)
                        (when (:self? op) ($ :span.badge "you")))
                     ($ :td (or (:display-name op) "—"))
                     ($ :td ($ :span {:class (str "badge" (if (:disabled? op) " bad" " on"))}
                               (if (:disabled? op) "disabled" "enabled")))
                     ;; Three states worth telling apart: linked, waiting for
                     ;; its first sign-in, and password-only.
                     ($ :td (cond
                              (:sso-linked? op)
                              ($ :span.sso-state.on "Linked"
                                 (when (:sso-email op) ($ :span.muted (str " · " (:sso-email op)))))
                              (:sso-email op)
                              ($ :span.sso-state "Pending first sign-in"
                                 ($ :span.muted (str " · " (:sso-email op))))
                              :else ($ :span.muted "—")))
                     ($ :td (ago (:created-at op)))
                     ;; Fixed slots: an action that does not apply leaves its slot empty, so
                     ;; every row's buttons stay in the same columns.
                     ($ :td.act
                        ($ :div.act-slots
                           ($ :button.quiet {:on-click #(reset-password op)} "Reset password")
                           ;; A linked operator is not pre-registered again: the
                           ;; server refuses it (`:auth/identity-already-linked`).
                           (if (:sso-linked? op)
                             ($ :span)
                             ($ :button.quiet {:on-click #(set-sso-email op)} "SSO email"))
                           ;; No disable button on your own row: the server refuses
                           ;; it (`:identity.account/self-disable`), and offering a
                           ;; button whose only outcome is a refusal is a lie.
                           (if (:self? op)
                             ($ :span)
                             ($ :button.quiet {:on-click #(toggle op)}
                                (if (:disabled? op) "Enable" "Disable"))))))))))
       ($ new-operator-form {:reload reload}))))

;; --- records -----------------------------------------------------------------

(defui meters
  "The counters the server actually keeps. They are per SOURCE (`:api`, `:mcp`,
   `:admin`) and not per graph, because that is how `record-query!` counts them;
   a per-graph split here would be an invention."
  [{:keys [metrics]}]
  (let [counters (:counters metrics)
        latency (:latency metrics)
        queries (reduce + 0 (map (comp #(or % 0) :queries val) counters))
        errors  (reduce + 0 (map (comp #(or % 0) :errors val) counters))
        p95     (some->> (vals latency) (keep :p95) seq (apply max))
        rej     (reduce + 0 (vals (or (:rejections metrics) {})))]
    ($ :div.meters
       ($ :div.meter
          ($ :div.k "Queries") ($ :div.v (fmt-int queries))
          ($ :div.sub "since restart, all sources"))
       ($ :div.meter
          ($ :div.k "Errors") ($ :div {:class (str "v" (when (pos? errors) " warn"))} (fmt-int errors)))
       ($ :div.meter
          ($ :div.k "Rejected") ($ :div {:class (str "v" (when (pos? rej) " warn"))} (fmt-int rej))
          ($ :div.sub "rate-limited + unauthorized"))
       ($ :div.meter
          ($ :div.k "p95 latency") ($ :div.v (if p95 (str p95 " ms") "—"))
          ($ :div.sub "worst source, last 500"))
       ($ :div.meter
          ($ :div.k "Uptime") ($ :div.v (fmt-uptime (:uptime-ms metrics)))))))

(defn- log-line
  "One `app.jsonl` line, in the shape the WRITER produces (obs/impl/log.clj):
   `:ts` and `:level`, then either `:msg` for a plain log call or `:event` plus
   that event's own keys. There is no `:message` and no `:timestamp` — reading
   for those printed a column of bare levels, which is what this replaces.

   The event's remaining keys are shown rather than dropped: by policy they are
   names, counts, timings and statuses, never entity field values, and they are
   the half of the line an operator is actually reading."
  [l]
  (let [{:keys [ts level msg event]} l
        extras (->> (dissoc l :ts :level :msg :event)
                    (remove (comp nil? val))
                    (sort-by key)
                    (map (fn [[k v]] (str (name k) "=" v)))
                    (str/join " "))]
    (str/join " " (remove str/blank?
                          [(str ts)
                           (str/upper-case (str (or level "?")))
                           (or msg (some-> event name) "")
                           extras]))))

(defui log-view [{:keys [logs]}]
  ($ :pre.log
     (for [[i l] (map-indexed vector logs)]
       ;; Level decides the colour, and only warn/error get one — a log where
       ;; every line is coloured says nothing about which line matters.
       ($ :div {:key i
                :class (case (str (:level l))
                         ("warn" "error" "fatal") "bad"
                         nil)}
          (log-line l)))))

(defui activity-details [{:keys [event]}]
  ($ :div.activity-details
     ($ :dl.facts
        (for [[label value] [["Request ID" (:request-id event)]
                             ["Token ID" (:token-id event)]
                             ["Graph" (:graph event)]
                             ["Version" (:graph-version event)]
                             ["Entry" (:entry event)]
                             ["Duration" (when (:latency-ms event) (str (:latency-ms event) " ms"))]]
              :when (some? value)]
          ($ :<> {:key label} ($ :dt label) ($ :dd ($ :code (str value))))))
     (if (seq (:queries event))
       ($ query-details/query-details {:queries (:queries event)})
       (when (= :access/query (:event event))
         ($ :p.muted "Query text is unavailable for this record; it may predate query logging or its log file may have rotated.")))))

(defui activity-row [{:keys [event]}]
  (let [[expanded? set-expanded!] (uix/use-state false)]
    ($ :<>
       ($ :tr
          ($ :td ($ :code.code (ago (:at event))))
          ($ :td ($ :code.code (str (:event event))))
          ($ :td ($ :span {:class (str "badge" (if (= :ok (:outcome event)) " on" " bad"))}
                     (name (or (:outcome event) :unknown))))
          ($ :td (str (or (:graph event) (:token-id event) "—")))
          ($ :td ($ :button.quiet {:aria-expanded expanded? :on-click #(set-expanded! (not expanded?))}
                     (if expanded? "Hide details" "View details"))))
       (when expanded?
         ($ :tr ($ :td {:col-span 5} ($ activity-details {:event event})))))))

(defui records-page [{:keys [activity logs metrics]}]
  ($ :<>
     ($ :div.card
        ($ :header ($ :h2 "Metrics")
           ($ :span.hint "in-process counters · they reset on restart, by design")
           ($ :div.right ($ :code.code.muted (str (:prometheus-url metrics)))))
        ($ meters {:metrics metrics}))
     ($ card
        {:title "Activity" :hint "queries and management actions · expand a record to inspect it"}
        (if (empty? activity)
          ($ :div.empty ($ :p "Nothing recorded yet."))
          ($ :table.table
             ($ :thead ($ :tr ($ :th "at") ($ :th "event") ($ :th "outcome") ($ :th "subject") ($ :th "details")))
             ($ :tbody
                (for [[i e] (map-indexed vector activity)]
                  ($ activity-row {:key (str (:request-id e) "/" (:at e) "/" i) :event e}))))))
     ($ card
        {:title "Log" :hint "app.jsonl · tail"}
        (if (empty? logs)
          ($ :div.empty ($ :p "app.jsonl is empty or absent."))
          ($ log-view {:logs logs})))))

;; --- rail and strip -----------------------------------------------------------

(def ^:private tabs [[:graphs "Groups & Graphs"] [:sources "Sources"] [:variables "Variables"] [:operators "Admins"]
                     [:records "Records"]])

(defui profile-card
  "Who is signed in, how they sign in, and the way out (owner, 2026-10-03): a card that pops up from the name at
   the rail's foot, as the other consoles' profiles do."
  [{:keys [operator me on-sign-out on-close]}]
  (let [box (uix/use-ref nil)]
    (uix/use-effect
     (fn []
       (let [away (fn [e] (when (and @box (not (.contains @box (.-target e)))) (on-close)))
             esc (fn [e] (when (= "Escape" (.-key e)) (on-close)))]
         (js/document.addEventListener "mousedown" away)
         (js/document.addEventListener "keydown" esc)
         #(do (js/document.removeEventListener "mousedown" away)
              (js/document.removeEventListener "keydown" esc))))
     [on-close])
    ($ :div.profile-pop {:ref box :role "dialog" :aria-label "Profile"}
       ($ :div.profile-head
          ($ :span.profile-avatar (str/upper-case (subs (or (:username operator) "?") 0 1)))
          ($ :div
             ($ :div.profile-name (or (:display-name me) (:username operator)))
             ($ :div.muted (:username operator))))
       ($ :dl.profile-facts
          ($ :dt "Single sign-on")
          ($ :dd (cond
                   (:sso-linked? me) ($ :span.sso-state.on "Linked"
                                        (when (:sso-email me) ($ :span.muted (str " · " (:sso-email me)))))
                   (:sso-email me) ($ :span "Pending first sign-in"
                                      ($ :span.muted (str " · " (:sso-email me))))
                   :else ($ :span.muted "Not set up — password only")))
          ($ :dt "Role")
          ($ :dd "Admin"))
       ($ :div.profile-actions
          ($ :button {:on-click on-sign-out} "Sign out")))))

(defui rail [{:keys [tab operator me metrics counts on-tab on-sign-out]}]
  ($ :aside.rail
     ($ :div.plate
        ($ :div.mark ($ :span.glyph) ($ :span.name "Open Atlas Server"))
        ;; Deliberately short: uptime is the only instance fact the API answers.
        ;; Host and build would each need a new field, and a plate that guessed
        ;; either would be worse than a plate that omits them.
        ($ :dl
           ($ :dt "up") ($ :dd (fmt-uptime (:uptime-ms metrics)))))
     ($ :nav
        (for [[t label] tabs]
          ($ :button {:key (name t)
                      :class (when (= t tab) "active")
                      :on-click #(on-tab t)}
             label
             ($ :span.count (str (get counts t))))))
     ($ :div.spacer)
     ($ :div.foot
        ;; The name opens the profile card; sign-out lives in it (owner, 2026-10-03).
        (let [[open? set-open!] (uix/use-state false)]
          ($ :div.who
             (when open?
               ($ profile-card {:operator operator :me me :on-sign-out on-sign-out
                                :on-close #(set-open! false)}))
             ($ :button.who-name {:title (:username operator) :aria-haspopup "dialog" :aria-expanded open?
                                  :on-click #(set-open! not)}
                ($ :span.profile-avatar.small (str/upper-case (subs (or (:username operator) "?") 0 1)))
                ($ :span.who-label (:username operator))))))))

(defui state-strip
  "The standing answer to «is it up, and what is live». Every figure is counted
   from the same payloads the pages below render — the strip cannot disagree
   with them because it is not a second query."
  [{:keys [counts activity]}]
  (let [serving (:serving counts 0)
        held (- (:graphs counts 0) serving)
        active (:active-tokens counts 0)
        last-write (first activity)]
    ($ :div.strip
       ($ :div.stat
          ($ :div.k "Serving")
          ($ :div {:class (str "v" (when (pos? serving) " on"))} (str serving))
          ($ :div.sub (str "of " (:graphs counts 0) " graphs"
                           (when (pos? held) (str " · " held " held")))))
       ($ :div.stat
          ($ :div.k "Active tokens")
          ($ :div.v (str active))
          ($ :div.sub (str (:tokens counts 0) " on the ledger")))
       ($ :div.stat
          ($ :div.k "Last write")
          ($ :div.v {:title (ago (:at last-write))}
             (if last-write (clock (:at last-write)) "—"))
          ($ :div.sub (if last-write
                        (str (:event last-write) " · " (or (:graph last-write)
                                                           (:token-id last-write) "—"))
                        "nothing recorded yet")))
       ($ :div.grow))))

;; --- the app -----------------------------------------------------------------

(defui console []
  (let [[state set-state!] (uix/use-state {:tab :graphs :loading? true})
        ;; `use-callback` with no dependencies, deliberately: `set-state!` is
        ;; stable, so these are too, and the mount effect below can name them
        ;; without re-running on every render — which with a fetch inside is
        ;; not a lint nit but a request loop.
        set-k! (uix/use-callback (fn [k v] (set-state! #(assoc % k v))) [])
        set-counts! (uix/use-callback (fn [counts] (set-k! :counts counts)) [set-k!])
        open-source! (uix/use-callback
                       (fn [source-id graph-id]
                         (set-state! #(assoc % :tab :sources :sources-open? true
                                               :source-navigation {:source-id source-id :graph-id graph-id
                                                                   :sequence (inc (get-in % [:source-navigation :sequence] 0))}))) [])
        load-operators (uix/use-callback
                        (fn []
                         (api/request! {:path "/api/console/operators"
                                        :on-ok #(set-k! :operators (:operators %))
                                        :on-error identity}))
                        [set-k!])
        load-records (uix/use-callback
                      (fn []
                       (api/request! {:path "/api/console/obs/activity"
                                      :on-ok #(set-k! :activity (:events %)) :on-error identity})
                       (api/request! {:path "/api/console/obs/metrics"
                                      :on-ok #(set-k! :metrics %) :on-error identity})
                       (api/request! {:path "/api/console/obs/logs"
                                      :on-ok #(set-k! :logs (:lines %)) :on-error identity}))
                      [set-k!])
        reload (uix/use-callback (fn [] (set-state! #(update % :revision (fnil inc 0)))
                                       (load-operators) (load-records))
                                [load-operators load-records])]


    (uix/use-effect
     (fn []
       (let [expired (fn [_] (set-state! #(-> % (assoc :operator nil :session-expired? true) (dissoc :issued))))]
         (.addEventListener js/window "atlas-session-expired" expired)
         #(.removeEventListener js/window "atlas-session-expired" expired))) [])

    (uix/use-effect
     (fn []
       (api/request! {:path "/api/console/auth/state"
                      :on-ok (fn [r]
                               (set-state! #(assoc % :loading? false
                                                   :bootstrapped? (:bootstrapped? r)
                                                   :oidc (:oidc r)
                                                   :operator (:operator r)))
                               (when (:operator r) (reload)))
                      :on-error (fn [_] (set-k! :loading? false))}))
     [set-k! reload])

    (cond
      (:loading? state) ($ :div.signin ($ :div.card "…"))

      (nil? (:operator state))
      ($ :<>
         (when (:session-expired? state)
           ($ :p.session-notice {:role "status"} "Your session has ended. Please sign in again."))
         ($ sign-in {:bootstrapped? (:bootstrapped? state)
                  :oidc (:oidc state)
                  :on-signed-in (fn [op] (set-state! #(assoc % :operator op :session-expired? false)) (reload))}))

      :else
      ($ :div.app
         ($ rail {:tab (:tab state)
                  :operator (:operator state)
                  ;; The signed-in admin's own row, for the profile card's SSO line.
                  :me (some #(when (:self? %) %) (:operators state))
                  :metrics (:metrics state)
                  :counts {:graphs (get-in state [:counts :graphs] 0)
                           :tokens (get-in state [:counts :active-tokens] 0)
                           :operators (count (:operators state))
                           :records (count (:activity state))}
                   :on-tab (fn [tab]
                            (set-state! (fn [s] (cond-> (assoc s :tab tab)
                                                 (= :sources tab) (assoc :sources-open? true)
                                                 (= :variables tab) (assoc :variables-open? true)
                                                 (= :graphs tab)
                                                 (assoc :catalog-navigation {:section tab :sequence (inc (get-in s [:catalog-navigation :sequence] 0))}))))
                            (reload))
                  :on-sign-out #(api/request!
                                 {:method "POST" :path "/api/console/auth/sign-out"
                                  :on-ok (fn [_] (set-k! :operator nil))
                                  :on-error (fn [_] (set-k! :operator nil))})})

         ($ :div.deck
            ($ state-strip {:counts (:counts state)
                            :activity (:activity state)})

            ($ :main
               ($ :div.page-stack {:hidden (not= :graphs (:tab state))}
                  ($ graph-browser/graphs-page {:revision (:revision state) :reload reload :on-counts set-counts!
                                               :navigation (:catalog-navigation state) :on-open-source open-source!
                                               :render-import (fn [props] ($ import-tools props))
                                               :issued (:issued state) :on-dismiss-issued #(set-k! :issued nil)
                                               :on-issued #(set-k! :issued %)}))
               (when (:sources-open? state)
                 ($ :div.page-stack {:hidden (not= :sources (:tab state))}
                    ($ source-maintenance/sources-page {:revision (:revision state) :on-changed reload
                                                        :navigation (:source-navigation state)
                                                        :on-variables #(set-state! (fn [s] (assoc s :tab :variables :variables-open? true)))
                                                        :on-back #(set-state! (fn [s] (assoc s :tab :graphs :catalog-navigation
                                                               {:graph-id (get-in s [:source-navigation :graph-id])
                                                                :sequence (inc (get-in s [:catalog-navigation :sequence] 0))})))})))
               (when (:variables-open? state)
                 ($ :div {:hidden (not= :variables (:tab state))}
                    ($ variables/variables-page {:revision (:revision state) :on-model-change reload})))
               (case (:tab state)
                 :graphs nil
                 :sources nil
                 :variables nil
                 :operators ($ operators-page {:operators (:operators state) :reload reload})
                 :records ($ records-page {:activity (:activity state)
                                           :logs (:logs state)
                                           :metrics (:metrics state)}))))))))

(defonce ^:private root (atom nil))

(defn init []
  (when-not @root
    (reset! root (uix.dom/create-root (js/document.getElementById "app"))))
  (uix.dom/render-root ($ console) @root))
