(ns open.atlas.integration.control-plane-test
  "B5c-1 end to end: **real Jetty, real port, real disk**, from studio all the
   way to the server's pointer.

   Why this can only live at the assembly point: `studio/deploy`,
   `runtime/graph` and `server/http` being green each prove their own
   decisions. \"The envelope studio signs is one the server recognizes\", \"the
   shell hands the decision the key-id it verified, not the one the body
   claims\" and \"bytes off the shelf still match their digest after a round
   trip through HTTP\" are none of those — they live on the seams (D1.5). There
   is no dependency edge between studio and server (D1.8), so this is the only
   place where both sides of the seam are on the classpath at once.

   Against B5c-1's exit conditions, one by one:

   | exit condition | deftest |
   |---|---|
   | the dependency check lands on **load** (R3.3) | `the-dependency-check-refuses-at-load-not-at-activate` |
   | a refusal never takes down the running version (D3.15) | `a-refused-activation-leaves-the-running-version-alone` |
   | a partial delivery cannot be activated | above + `a-partial-delivery-cannot-be-activated` |
   | latest follows a rollback (R3.6) | `rollback-moves-the-pointer-back-with-the-target-s-own-plan` |
   | both principals are auditable (D3.12) | `both-principals-reach-the-persisted-event` |
   | CAS: someone else moved the pointer → refuse | `a-stale-expected-current-is-refused` |"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.runtime.graph :as control]
            [open.atlas.runtime.store :as rs]
            [open.atlas.server.http :as http]
            [open.atlas.studio.deploy :as deploy]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; Identities and keys — two of them, kept apart (D3.12)
;; ---------------------------------------------------------------------------

(def ^:private alice   {:issuer "corp-idp" :subject "00u-alice"})
(def ^:private mallory {:issuer "corp-idp" :subject "00u-mallory"})

(def ^:private actor-key-id   "studio-actor-2026-08")
(def ^:private actor-secret   "actor-key-not-the-data-plane-one")
(def ^:private service-key-id "studio-prod")
(def ^:private service-secret "service-key-not-the-data-plane-one")
(def ^:private instance-id    "prod-instance-a")

;; ---------------------------------------------------------------------------
;; The studio side: one pool, two graphs, one shelf
;; ---------------------------------------------------------------------------

(def ^:private model
  "Two namespaces, one graph each: `c.*` → core-team/core, `s.*` → sales/app.
   A single pool holds both; projection takes what each graph's selectors say."
  {:attributes {:c.k/id   {:type :integer :format :id :identity? true :label "K"}
                :c.k/name {:type :string :format :text :label "K name"}
                :s.a/id   {:type :integer :format :id :identity? true :label "A"}
                :s.a/name {:type :string :format :text :label "A name"}}
   :namespaces {"c.k" {:kind :master :label "K"}
                "s.a" {:kind :master :label "A"}}
   :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
   :resolvers  [{:id "k_by_id" :input [:c.k/id] :output [:c.k/id :c.k/name]
                 :result_type "single" :source {:type "sql" :query "SELECT 1"}
                 :source-ref "db"}
                {:id "a_by_id" :input [:s.a/id] :output [:s.a/id :s.a/name]
                 :result_type "single" :source {:type "sql" :query "SELECT 1"}
                 :source-ref "db"}]})

(defn- check! [r]
  (assert (empty? (:findings r)) (pr-str (:findings r)))
  r)

(defn- tmp-root [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- rm-r [f]
  (let [f (io/file f)] (when (.isDirectory f) (run! rm-r (.listFiles f))) (.delete f)))

(defn- open-studio!
  "Pool + both graph registrations + the reference grant. → `[cfg conn]`."
  []
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "b5c1"))})
        _    (pool/delete-pool! cfg)
        conn (version/install! (graph/install! (pool/open-pool! cfg)))
        objs (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") (pool/dialect->objects model))]
    (check! (pool/import-pool! conn {:pool/version 1 :pool/objects objs}))
    (check! (graph/register-workspace!
             conn {:name "core-team" :members [{:principal alice :role :owner}]}))
    (check! (graph/register-workspace!
             conn {:name "sales" :members [{:principal alice   :role :owner}
                                           {:principal mallory :role :editor}]}))
    ;; 菜谱是对象清单（D3.19），跨 graph 引用已退役（D3.17）——两张图各装
    ;; 自己那一半，控制面的用例问的是「制品怎么走到指针」，与装什么无关。
    (let [members-under (fn [prefix]
                          (let [m (pool/as-dialect (pool/db conn))
                                ns-names (into #{} (filter #(clojure.string/starts-with? % prefix))
                                               (keys (:namespaces m)))]
                            {:namespaces ns-names
                             :attributes (into #{} (filter #(contains? ns-names (namespace %)))
                                               (keys (:attributes m)))
                             :resolvers (set (map :id (filter (fn [r] (some #(contains? ns-names (namespace %))
                                                                            (filter keyword? (:input r))))
                                                              (:resolvers m))))}))]
      (check! (graph/register-graph! conn {:workspace "core-team" :name "core"
                                           :members (members-under "c.")}))
      (check! (graph/register-graph! conn {:workspace "sales" :name "app"
                                           :members (members-under "s.")})))
    [cfg conn]))

(defn- cut!
  "Cut one version onto the shelf. `:cut-at` / `:cut-by` are passed in
   explicitly — this module reads neither clock nor current user (D4.10-a)."
  [conn shelf qn & {:keys [resolved] :or {resolved []}}]
  (check! (version/cut-version! conn {:graph qn :resolved resolved
                                      :artifact-root shelf
                                      :cut-at "2026-08-25T09:00:00Z" :cut-by "alice"})))

(defn- resolved-from
  "A cut version → an entry the next `cut-version!` can take as `:resolved`.

   **The content is read back off the shelf**, not rebuilt here: a second copy
   built in the test would eventually diverge from the bytes on disk, and from
   that point on this test would be vouching for an artifact that exists
   nowhere."
  [qn cut]
  {:graph qn :version (:version cut)
   :content (edn/read-string (slurp (io/file (:dir cut) "content.edn")))
   :content-hash (:content-hash cut)
   :artifact-digest (:artifact-digest cut)})

;; ---------------------------------------------------------------------------
;; The server side: the lease, then a real Jetty
;; ---------------------------------------------------------------------------

(defn- now-utc []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- plus-seconds [^String ts n]
  (str (.plusSeconds (java.time.Instant/parse ts) n)))

(defn- server-deps [store]
  {:store store
   :reads (merge (control/store-reads store)
                 {:actor-key (fn [k] (when (= actor-key-id k) actor-secret))
                  ;; The ACL: this instance knows one service key, and it may run
                  ;; all five ops. The issued-token ledger is B5c-2; in v1 the ACL
                  ;; is exactly this — a predicate the assembler injects.
                  :service-allows? (fn [principal _op]
                                     (= principal {:issuer "service-key"
                                                   :subject service-key-id}))
                  ;; S5.7 门禁显式注入：缺席即拒绝激活（fail-closed）。这条端到端
                  ;; 测的是部署生命周期，门禁本身的测试在 runtime/rowsec。
                  :row-security-gate (constantly [])})
   :instance-id instance-id
   :service-keys {service-key-id service-secret}
   :service-key (fn [k] (when (= service-key-id k) service-secret))
   :now now-utc
   ;; Port 0: the OS picks a free one and we read it back, so two runs of this
   ;; suite on one machine never collide over a hard-coded port.
   :port 0 :host "127.0.0.1"})

(defn- jetty-port [server]
  (.getLocalPort ^org.eclipse.jetty.server.ServerConnector
                 (aget (.getConnectors ^org.eclipse.jetty.server.Server server) 0)))

(defmacro ^:private with-platform
  "Bring up the whole thing: studio pool + shelf + a control root under lease +
   a real Jetty. `sym` is bound to `{:conn :shelf :store :root :endpoint}`."
  [[sym] & body]
  `(let [[cfg# conn#] (open-studio!)
         shelf# (tmp-root "b5c1-shelf-")
         root#  (tmp-root "b5c1-control-")
         opened# (rs/open! {:instance-id instance-id :root root#})]
     (assert (empty? (:findings opened#)) (pr-str (:findings opened#)))
     (let [inst#  (:instance opened#)
           store# (control/open! inst#)
           _#     (control/recover! store#)
           started# (http/start! (server-deps store#))]
       (assert (empty? (:findings started#)) (pr-str (:findings started#)))
       (let [server# (:server started#)
             ~sym {:conn conn# :shelf shelf# :store store# :root root#
                   :endpoint (str "http://127.0.0.1:" (jetty-port server#) "/control")}]
         (try ~@body
              (finally (http/stop! server#)
                       (rs/close! inst#)
                       (pool/close-pool! conn#) (pool/delete-pool! cfg#)
                       (rm-r shelf#) (rm-r root#)))))))

(defn- deploy-conf
  "Everything one deployment needs. `:request-id-fn` is injected, so every
   step's idempotency key is predictable from the test."
  [{:keys [conn shelf endpoint]} & {:as over}]
  (let [now (now-utc)]
    (merge {:db (pool/db conn) :artifact-root shelf :endpoint endpoint
            :graph "sales/app" :actor alice
            :audience instance-id
            :actor-key-id actor-key-id :actor-secret actor-secret
            :service-key-id service-key-id :service-secret service-secret
            :now now :expires-at (plus-seconds now 120)
            :expected-current nil}
           over)))

(defn- ids
  "A `:request-id-fn`. Staging's step is `[:stage <digest>]` — one idempotency
   key per artifact — and everything else is an op keyword; `pr-str` handles
   both, and **the same step always yields the same id**, which is what makes a
   retry a replay instead of a conflict."
  [prefix]
  (fn [step] (str prefix "-" (pr-str step))))

(defn- cut-both!
  "Cut core@v1, then app@v1 referencing it. → both cuts."
  [{:keys [conn shelf]}]
  (let [core (cut! conn shelf "core-team/core")]
    {:core core
     :app  (cut! conn shelf "sales/app"
                 :resolved [(resolved-from "core-team/core" core)])}))

(defn- pointer-of [{:keys [store]} qn]
  ((:pointer (control/store-reads store)) qn))

(defn- codes [x] (into (sorted-set) (map :code) (:findings x)))

;; ---------------------------------------------------------------------------
;; (1) The whole trip: offer → stage → load → activate
;; ---------------------------------------------------------------------------

(deftest a-cut-version-travels-all-the-way-to-the-pointer
  (testing "bytes studio cut, an envelope studio signed, real HTTP, real disk —
            not one stand-in anywhere along the way"
    (with-platform [p]
      (let [{:keys [app]} (cut-both! p)
            out (deploy/deploy! (deploy-conf p :version (:version app)
                                             :deployment-id "dep-app-v1"
                                             :request-id-fn (ids "r1")))]
        (is (empty? (:findings out)) (pr-str (:findings out)))
        (is (= [:owner :plan :offer :stage :load :activate] (mapv :step (:steps out))))
        (is (= [["sales/app" 1]]
               (mapv (juxt :graph :version) (get-in out [:by-step :plan :closure])))
            "the closure is the root alone — a graph references nothing since
             D3.17 — and it is still traversed off the shelf, not recomputed
             from the recipe")
        (is (= 1 (get-in out [:by-step :stage :sent]))
            "first deployment: the one member is missing, so it gets shipped")
        (is (= {:from nil :to (:version app)} (get-in out [:by-step :activate])))
        (is (= (:version app) (:version (pointer-of p "sales/app"))))))))

(deftest a-second-deployment-only-ships-what-is-missing
  (testing "D4.11: offer agrees on the manifest first. core is already on the
            shelf, so the second version ships only its own bytes"
    (with-platform [p]
      (let [{:keys [conn shelf]} p
            core (cut! conn shelf "core-team/core")
            v1   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])
            v2   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])]
        (check! (deploy/deploy! (deploy-conf p :version (:version v1)
                                             :deployment-id "dep-app-v1"
                                             :request-id-fn (ids "r1"))))
        (let [out (deploy/deploy! (deploy-conf p :version (:version v2)
                                               :deployment-id "dep-app-v2"
                                               :expected-current (:version v1)
                                               :request-id-fn (ids "r2")))]
          (is (empty? (:findings out)) (pr-str (:findings out)))
          (is (= 1 (get-in out [:by-step :stage :sent]))
              "only the missing one — core@v1's bytes are not sent twice")
          (is (= (:version v2) (:version (pointer-of p "sales/app")))))))))

;; ---------------------------------------------------------------------------
;; (2) R3.3: the dependency check lands on **load**
;; ---------------------------------------------------------------------------

(deftest the-dependency-check-refuses-at-load-not-at-activate
  (testing "R3.3: with one closure member missing, **`:load` is what refuses** —
            not activate. A receipt is evidence that cannot be rewritten, so
            handing one out and only discovering the hole at activation time is
            already one step too late"
    (with-platform [p]
      (let [{:keys [app]} (cut-both! p)
            conf (deploy-conf p :version (:version app)
                              :deployment-id "dep-hole"
                              :request-id-fn (ids "hole"))]
        ;; Offer runs (the plan lands on disk), but **stage is skipped**: not one
        ;; byte was ever sent.
        (check! (deploy/offer! conf (:closure (deploy/plan! conf))))
        (let [r (deploy/load! conf)]
          (is (contains? (codes r) :deploy.closure/member-absent) (pr-str (codes r))))
        ;; The companion `:deploy.closure/plan-mismatch` had a subject only while
        ;; a closure could have several members and be traversed *partially*.
        ;; Since D3.17 retired references a closure is the root alone, so
        ;; "reachable disagrees with the plan" and "the one member is absent"
        ;; are the same fact — and `member-absent` is the one that names it.
        (is (nil? (pointer-of p "sales/app"))
            "after the refusal the pointer is as absent as it ever was — a
             refusal is not \"write it down and call it not-counted\"")))))

;; ---------------------------------------------------------------------------
;; (3) D3.15: refuse the new activation, leave the running one alone
;; ---------------------------------------------------------------------------

(deftest a-refused-activation-leaves-the-running-version-alone
  (testing "D3.15: a refusal blocks the **new** activation and does not touch a
            byte of the version already running — taking something down
            automatically violates \"when to go live is the owner's promise\"
            even harder than putting something up automatically does"
    (with-platform [p]
      (let [{:keys [conn shelf]} p
            core (cut! conn shelf "core-team/core")
            v1   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])
            v2   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])]
        (check! (deploy/deploy! (deploy-conf p :version (:version v1)
                                             :deployment-id "dep-app-v1"
                                             :request-id-fn (ids "r1"))))
        ;; v2: offer only, no bytes, straight to activate.
        (let [conf (deploy-conf p :version (:version v2)
                                :deployment-id "dep-app-v2"
                                :expected-current (:version v1)
                                :request-id-fn (ids "r2"))]
          (check! (deploy/offer! conf (:closure (deploy/plan! conf))))
          (let [r (deploy/activate! conf)]
            ;; **Name the reason for the refusal.** Asserting only "it refused"
            ;; would also pass on a bad signature or a mistyped audience, and
            ;; neither of those has anything to do with D3.15.
            (is (contains? (codes r) :deploy.closure/member-absent) (pr-str (codes r)))
            (is (= (:version v1) (:version (pointer-of p "sales/app")))
                "**v1 is still running** — a failed v2 activation must not take it down")))))))

(deftest a-partial-delivery-cannot-be-activated
  (testing "ship the root but not its dependency and activation still refuses:
            what activation verifies is the **whole closure**.

            Being precise about what this covers: the protocol has no state in
            which the shelf holds half a closure — `:load` either lands the
            whole thing or hands out no receipt at all. So the one reachable
            shape of \"partial delivery\" is this one: plan authenticated, some
            bytes shipped, `:load` never successful. That shape is what is
            asserted here, not \"a half-empty shelf cannot be activated\" —
            that shelf cannot be built."
    (with-platform [p]
      (let [{:keys [conn shelf]} p
            core (cut! conn shelf "core-team/core")
            app  (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])
            conf (deploy-conf p :version (:version app)
                              :deployment-id "dep-partial"
                              :request-id-fn (ids "part"))]
        (let [closure   (:closure (deploy/plan! conf))
              root-only (filter #(= "sales/app" (:graph %)) closure)]
          (check! (deploy/offer! conf closure))
          (is (= 1 (count root-only)))
          (check! (deploy/stage! conf root-only)))
        (let [r (deploy/activate! conf)]
          (is (contains? (codes r) :deploy.closure/member-absent) (pr-str (codes r)))
          (is (nil? (pointer-of p "sales/app"))))))))

;; ---------------------------------------------------------------------------
;; (4) CAS and rollback (R3.6)
;; ---------------------------------------------------------------------------

(deftest a-stale-expected-current-is-refused
  (with-platform [p]
    (let [{:keys [app]} (cut-both! p)]
      (check! (deploy/deploy! (deploy-conf p :version (:version app)
                                           :deployment-id "dep-app-v1"
                                           :request-id-fn (ids "r1"))))
      ;; Same version again, but claiming the pointer is still nil. It is not.
      (let [r (deploy/activate! (deploy-conf p :version (:version app)
                                             :deployment-id "dep-app-v1"
                                             :expected-current nil
                                             :request-id-fn (ids "r-stale")))]
        (is (contains? (codes r) :deploy.activate/stale-expectation) (pr-str (codes r)))))))

(deftest rollback-moves-the-pointer-back-with-the-target-s-own-plan
  (testing "R3.6: latest **is** the deployment pointer, so after a rollback
            latest is the version rolled back to. The evidence has to be
            **that** version's own plan — rolling back on the current version's
            plan is using A's evidence to vouch for B"
    (with-platform [p]
      (let [{:keys [conn shelf]} p
            core (cut! conn shelf "core-team/core")
            v1   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])
            v2   (cut! conn shelf "sales/app" :resolved [(resolved-from "core-team/core" core)])]
        (check! (deploy/deploy! (deploy-conf p :version (:version v1)
                                             :deployment-id "dep-app-v1"
                                             :request-id-fn (ids "r1"))))
        (check! (deploy/deploy! (deploy-conf p :version (:version v2)
                                             :deployment-id "dep-app-v2"
                                             :expected-current (:version v1)
                                             :request-id-fn (ids "r2"))))
        (testing "roll back to v1 carrying v2's deployment id — refused"
          (let [r (deploy/rollback! (deploy-conf p :to-version (:version v1)
                                                 :deployment-id "dep-app-v2"
                                                 :expected-current (:version v2)
                                                 :request-id-fn (ids "bad-rb")))]
            (is (contains? (codes r) :deploy.plan/immutable) (pr-str (codes r)))
            (is (= (:version v2) (:version (pointer-of p "sales/app"))))))
        (testing "roll back carrying v1's own deployment id — accepted, and the
                  pointer follows"
          (let [r (deploy/rollback! (deploy-conf p :to-version (:version v1)
                                                 :deployment-id "dep-app-v1"
                                                 :expected-current (:version v2)
                                                 :request-id-fn (ids "rb")))]
            (is (empty? (:findings r)) (pr-str (:findings r)))
            (is (= {:from (:version v2) :to (:version v1)} (:result r)))
            (is (= (:version v1) (:version (pointer-of p "sales/app"))))))))))

;; ---------------------------------------------------------------------------
;; (5) Both principals are auditable (D3.12)
;; ---------------------------------------------------------------------------

(deftest both-principals-reach-the-persisted-event
  (testing "an audit has to answer both \"who authorized this\" and \"which
            controlled service made the call\". **The actor comes from a signed
            assertion, the service from the key-id the shell verified** — the
            envelope has no \"who I am\" field at all, because a claimed
            identity is not an identity"
    (with-platform [p]
      (let [{:keys [app]} (cut-both! p)]
        (check! (deploy/deploy! (deploy-conf p :version (:version app)
                                             :deployment-id "dep-app-v1"
                                             :request-id-fn (ids "r1"))))
        (let [ptr (pointer-of p "sales/app")
              ev  ((:event (control/store-reads (:store p))) "sales/app" (:head-event-id ptr))]
          (is (some? ev) "the head event the pointer names is on disk")
          (is (= alice (:actor-principal ev)) "actor: the pair studio asserted")
          (is (= {:issuer "service-key" :subject service-key-id} (:service-principal ev))
              "service: derived by the shell from a **verified** key-id, not from
               anything the body claimed")
          (is (= instance-id (:instance-id ev)))
          (is (= "dep-app-v1" (:deployment-id ev))))))))

(deftest an-editor-is-refused-before-a-single-byte-leaves-studio
  (testing "the first link of the authorization chain is in studio (D3.12): the
            server does not copy the member table, so the owner check can only
            happen here — and it has to happen **before** anything is sent"
    (with-platform [p]
      (let [{:keys [app]} (cut-both! p)
            out (deploy/deploy! (deploy-conf p :version (:version app)
                                             :actor mallory
                                             :deployment-id "dep-mallory"
                                             :request-id-fn (ids "m")))]
        (is (= [:deploy.authz/not-owner] (mapv :code (:findings out))))
        (is (empty? (:steps out)))
        (is (nil? ((:deployment (control/store-reads (:store p))) "dep-mallory"))
            "the server has no plan either — not one byte went out")))))
