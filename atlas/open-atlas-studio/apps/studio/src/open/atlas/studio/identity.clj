(ns open.atlas.studio.identity
  "Studio-owned local identities and short-lived browser sessions.

   Identity rows live in the same Datahike store as workspace membership. The
   founding admin and its owner membership are therefore written by one
   transaction. Local principals use an immutable UUID subject; email remains a
   mutable/display attribute and never participates in authorization matching."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [open.atlas.studio.graph.impl.schema :as graph-schema]
            [open.atlas.studio.graph.impl.store :as graph-store]
            [open.atlas.studio.identity.password :as password])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def ^:private issuer "open-atlas-local")
(def ^:private session-seconds (* 7 24 60 60))
(def ^:private ^SecureRandom rng (SecureRandom.))
(defonce ^:private sessions (atom {}))

(def schema
  [{:db/ident :identity.user/id :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/email :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/password-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; SSO link (doc [8.16](../../../../../../docs/design/101-sso-oidc.md) §8.16.5).
   ;; `sso-key` is the single unique attribute because Datahike has no composite
   ;; uniqueness: `"<issuer> <subject>"` makes «one account per identity provider
   ;; principal» (D5.10) a store-enforced fact rather than a check some caller
   ;; may forget. The two halves are kept beside it so a reader never has to
   ;; parse the key back apart.
   {:db/ident :identity.user/sso-key :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/sso-issuer :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/sso-subject :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; An administrator's pre-registration of an EXISTING account for SSO (doc
   ;; 101 §8.16.4 rule 4.4's remedy, §8.16.9 item 3) — the counterpart of the
   ;; server's `sso_email`. Unique so one address names one account; retracted
   ;; by `bind-sso!`, so it is consumed by the one sign-in it was set for.
   {:db/ident :identity.user/sso-email :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/system-role :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/default-workspace :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/disabled :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :identity.user/updated-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn install! [conn]
  (d/transact conn schema)
  conn)

(defn- entity->user [entity]
  (when entity
    {:id (:identity.user/id entity)
     :email (:identity.user/email entity)
     :name (:identity.user/name entity)
     ;; May be absent: an SSO account has no password at all (§8.16.4 rule 5).
     :password-hash (:identity.user/password-hash entity)
     :sso-issuer (:identity.user/sso-issuer entity)
     :sso-subject (:identity.user/sso-subject entity)
     :sso-email (:identity.user/sso-email entity)
     :system-role (:identity.user/system-role entity)
     :default-workspace (:identity.user/default-workspace entity)
     :disabled? (boolean (:identity.user/disabled entity))
     :created-at (:identity.user/created-at entity)
     :updated-at (:identity.user/updated-at entity)}))

(defn- pull-user [db attr value]
  (some-> (first (d/q '[:find [(pull ?e [*]) ...]
                         :in $ ?attribute ?value
                         :where [?e ?attribute ?value]]
                       db attr value))
          entity->user))

(defn user-by-email [db email]
  (pull-user db :identity.user/email (some-> email str/trim str/lower-case)))

(defn user-by-id [db id]
  (pull-user db :identity.user/id id))

(defn users [db]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :identity.user/id]] db)
       (map entity->user)
       (sort-by :email)
       vec))

(defn sso-key
  "The store's unique key for an identity provider principal (D5.10)."
  [sso-issuer sso-subject]
  (str sso-issuer " " sso-subject))

(defn public-user
  "The user as it leaves this ns. The hash never does — `:has-password?` is the
   only thing any caller needs to know about it, and `:sso-linked?` the only
   thing about the link."
  [user]
  (-> user
      (dissoc :password-hash :sso-issuer :sso-subject)
      (assoc :has-password? (some? (:password-hash user))
             :sso-linked? (some? (:sso-subject user))
             :principal {:issuer issuer :subject (:id user)})))

(defn needs-setup? [db] (empty? (users db)))

(def seed-workspace
  "The workspace a fresh Studio is seeded for — `builder_server`'s
   `bootstrap-workspace` reads this, so the seed and the founding account can
   never be told two different names.

   It is also `bootstrap-admin!`'s default. When the founding operator names a
   DIFFERENT one, that account is additionally made owner of this workspace:
   the seed's objects are stamped with it (D3.22) and its graphs registered
   under it (D3.19), and Studio has no route to create or join a workspace
   afterwards — so without the grant the bundled demo is on the machine with
   nobody able to see it (0.2 acceptance defect 5). Moving the objects instead
   would restamp every seeded object and rename every seeded graph, which is a
   migration, not a first start."
  "demo")

(defn- workspace-name? [value]
  (and (string? value) (not (str/blank? value)) (not (str/includes? value "/"))))

(defn admin?
  "A Studio control-plane administrator. This is deliberately unrelated to a
   workspace :owner role and never crosses into Server or Workbench."
  [user]
  (= :admin (:system-role user)))

(defn- valid-account-input?
  "`sso?` = this account signs in through the identity provider and therefore
   has no password (§8.16.4 rule 5). An administrator may still set one later,
   at which point both ways in work."
  ([email name password] (valid-account-input? email name password false))
  ([email name password sso?]
   (cond
     (or (str/blank? email) (not (str/includes? email "@")))
     {:code :invalid-email :message "A valid email address is required."}
     (str/blank? name)
     {:code :invalid-name :message "A display name is required."}
     (and (not sso?) (< (count (or password "")) 12))
     {:code :weak-password :message "Password must contain at least 12 characters."}
     :else nil)))

(defn personal-workspace-name
  "A new user's personal workspace name: **the stored (lowercased) email**
   (owner ruling 2026-09-05, E4 task sheet §1.3b).

   It used to be a slug of the address' local part with a `-2` suffix on a
   collision. A slug is a second name for a person, derived from the first and
   no longer traceable back to it — two `alice@`s at different companies became
   `alice` and `alice-2`, and nothing on screen said which was which. The email
   is already unique in the user table, so the retreat suffix has nothing left
   to guard against.

   Nothing in the name face objects: `registry/name-ok?` forbids only `/`, a
   qualified name `<workspace>/<graph>` splits on the first `/`, an address has
   none, and `@` / `.` are legal in the `artifacts/<workspace>/…` shelf path."
  [email]
  (str/lower-case (str email)))

(defn- workspace-name-taken
  "D3.22 / §1.3b: a personal workspace name is never retreated from. If one is
   already registered under this address — only a hand-made workspace can be —
   creation refuses and names it, rather than quietly owning someone else's
   workspace or inventing a second name for this person."
  [db workspace]
  (when (graph-store/find-workspace db workspace)
    {:code :workspace-name-taken
     :message (str "A workspace named " workspace " already exists; "
                   "rename it before creating this account.")}))

(defn- user-tx
  "`password` nil = no password attribute at all. Datahike would reject a nil
   value, and an SSO account must be distinguishable from one whose hash failed
   to be written."
  [id email name password role workspace now]
  (cond-> {:identity.user/id id
           :identity.user/email email
           :identity.user/name name
           :identity.user/system-role role
           :identity.user/default-workspace workspace
           :identity.user/disabled false
           :identity.user/created-at now
           :identity.user/updated-at now}
    (some? password) (assoc :identity.user/password-hash (password/hash-password password))))

(defn- personal-workspace-tx
  "A new user's own workspace, owned by that user.

   The owner membership carries the **local** principal `{:issuer
   \"open-atlas-local\" :subject id}` even for an SSO account (doc §8.16.5).
   Workspace membership already keys on that principal everywhere in this app,
   and D5.10's requirement is that the control-plane key be immutable — the
   local uuid is. The identity provider principal is recorded as the *link* the
   login walks in through, not as a second control-plane identity: two
   principals for one person would make «who owns this workspace» a question
   with two answers."
  [workspace principal email name]
  {:graph.workspace/name workspace
   :graph.workspace/members
   (graph-schema/encode-edn
    [{:principal principal :role :owner
      :attributes {:email email :name name}}])})

(defn- own-existing-workspace-tx
  "Make `principal` the owner of an **existing** workspace, keeping every member
   already on it.

   Unlike `bootstrap-admin!`, which drops the `{:issuer \"studio\" :subject
   \"local-builder\"}` seed member, this keeps it: on a local Studio that
   principal is how a passwordless session gets in at all, and a one-shot
   rename that silently locked the machine out of its own Builder would be a
   repair that breaks the thing it repaired (E4 §1b.3)."
  [ws principal email name]
  (let [members (->> (conj (vec (remove #(= principal (:principal %))
                                        (:workspace/members ws)))
                           {:principal principal :role :owner
                            :attributes {:email email :name name}})
                     (sort-by #(pr-str (:principal %)))
                     vec)]
    {:graph.workspace/name (:workspace/name ws)
     :graph.workspace/members (graph-schema/encode-edn members)}))

(defn create-local-user!
  "Studio-admin provisioning path. It creates a local Studio member and that
   user's own empty workspace in one transaction. It does not create a Server
   admin, issue a Server token, or grant any existing workspace membership.

   `:into-workspace` names an **existing** workspace to own instead of creating
   a personal one. The one caller is the workspace-rename tool (E4 §1b.3):
   after `demo` has been renamed to somebody's address, creating that person's
   personal workspace would collide with the very workspace they are meant to
   own. Absent — the normal path — nothing changes.

   `:role` overrides the `:member` system role for the same tool."
  [conn {:keys [email name password sso into-workspace role]}]
  (let [email (some-> email str/trim str/lower-case)
        name (some-> name str/trim)
        sso? (boolean sso)]
    (if-let [error (valid-account-input? email name password sso?)]
      {:error error}
      (locking conn
        (let [db (d/db conn)]
          (cond
            (not (contains? #{nil :member :admin} role))
            {:error {:code :invalid-system-role :message "Choose Admin or Member."}}
            (user-by-email db email)
            {:error {:code :email-taken :message "A Studio user with this email already exists."}}

            (and into-workspace (nil? (graph-store/find-workspace db into-workspace)))
            {:error {:code :no-such-workspace
                     :message (str "No workspace named " into-workspace " is registered.")}}

            (and (nil? into-workspace)
                 (workspace-name-taken db (personal-workspace-name email)))
            {:error (workspace-name-taken db (personal-workspace-name email))}

            :else
            (let [id (str (UUID/randomUUID))
                  principal {:issuer issuer :subject id}
                  workspace (or into-workspace (personal-workspace-name email))
                  now (str (Instant/now))
                  user (user-tx id email name (when-not sso? password)
                                (or role :member) workspace now)
                  workspace-tx (if into-workspace
                                 (own-existing-workspace-tx
                                  (graph-store/find-workspace db into-workspace)
                                  principal email name)
                                 (personal-workspace-tx workspace principal email name))]
              (d/transact conn [user workspace-tx])
              {:status :ok :user (public-user (entity->user user))
               :workspace workspace})))))))

(defn promote-admin!
  "Promote an existing local Studio account without changing its identity,
   password, disablement state, default workspace, or workspace memberships."
  [conn email]
  (locking conn
    (let [db (d/db conn)
          user (user-by-email db email)]
      (cond
        (nil? user)
        {:error {:code :user-not-found :message "No Studio user with this email exists."}}

        (admin? user)
        {:status :ok :changed? false :user (public-user user)}

        :else
        (let [now (str (Instant/now))]
          (d/transact conn [{:db/id [:identity.user/id (:id user)]
                             :identity.user/system-role :admin
                             :identity.user/updated-at now}])
          {:status :ok :changed? true
           :user (public-user (user-by-id (d/db conn) (:id user)))})))))

(defn bootstrap-admin!
  "Create the only founding admin and its default workspace membership.
   Refuses once any identity exists. User + membership are one Datahike tx."
  [conn {:keys [email name password workspace]}]
  (let [db (d/db conn)
        email (some-> email str/trim str/lower-case)
        name (some-> name str/trim)
        workspace (or (some-> workspace str/trim not-empty) seed-workspace)]
    (cond
      (not (needs-setup? db)) {:error {:code :bootstrap-closed
                                      :message "Studio is already initialized."}}
      (valid-account-input? email name password)
      {:error (valid-account-input? email name password)}
      (not (workspace-name? workspace))
      {:error {:code :invalid-workspace :message "Workspace must be a non-empty name without '/'."}}
      :else
      (locking conn
        (if-not (needs-setup? (d/db conn))
          {:error {:code :bootstrap-closed :message "Studio is already initialized."}}
          (let [id (str (UUID/randomUUID))
                principal {:issuer issuer :subject id}
                now (str (Instant/now))
                existing (graph-store/find-workspace (d/db conn) workspace)
                members (->> (conj (vec (remove #(= {:issuer "studio" :subject "local-builder"}
                                                     (:principal %))
                                                (:workspace/members existing)))
                                   {:principal principal :role :owner
                                    :attributes {:email email :name name}})
                             (sort-by #(pr-str (:principal %)))
                             vec)
                user-tx (user-tx id email name password :admin workspace now)
                workspace-tx {:graph.workspace/name workspace
                              :graph.workspace/members (graph-schema/encode-edn members)}
                ;; The seed's own workspace, when the founder named another
                ;; one. Its `local-builder` member is KEPT — as in
                ;; `own-existing-workspace-tx`, that principal is how a
                ;; passwordless local session reaches the Builder at all.
                seed-ws (when (not= workspace seed-workspace)
                          (graph-store/find-workspace (d/db conn) seed-workspace))
                seed-tx (when seed-ws
                          (own-existing-workspace-tx seed-ws principal email name))]
            (d/transact conn (cond-> [user-tx workspace-tx] seed-tx (conj seed-tx)))
            {:status :ok
             :user (public-user (entity->user user-tx))
             :workspace workspace}))))))

(defn user-by-principal
  "The account linked to an identity provider principal (§8.16.4 rule 4.1)."
  [db sso-issuer sso-subject]
  (pull-user db :identity.user/sso-key (sso-key sso-issuer sso-subject)))

(defn user-by-sso-email
  "The account an administrator pre-registered for this address, or nil."
  [db email]
  (some->> (some-> email str/trim str/lower-case not-empty)
           (pull-user db :identity.user/sso-email)))

(defn set-sso-email!
  "Pre-register an existing account for SSO, or clear it (blank `email`).

   Doc 101 §8.16.4 rule 4.4's remedy (§8.16.9 item 3), with Workbench's
   semantics: the next verified sign-in asserting this address binds its
   `(issuer, subject)` onto the account once — password or not — and the
   pre-registration is cleared. Moving an existing link is not this act
   (§8.16.8), so a linked account is refused."
  [conn user-id email]
  (locking conn
    (let [db (d/db conn)
          target (user-by-id db user-id)
          email (some-> email str str/trim str/lower-case not-empty)
          owner (when email (user-by-email db email))
          claimed (when email (user-by-sso-email db email))
          now (str (Instant/now))]
      (cond
        (nil? target)
        {:error {:code :user-not-found :message "User not found."}}

        (nil? email)
        (do (when (:sso-email target)
              (d/transact conn [[:db/retract [:identity.user/id user-id]
                                 :identity.user/sso-email (:sso-email target)]
                                {:identity.user/id user-id :identity.user/updated-at now}]))
            {:status :ok :user (public-user (user-by-id (d/db conn) user-id))})

        (:sso-subject target)
        {:error {:code :auth/identity-already-linked
                 :message "This account is already linked to an identity provider account."}}

        (not (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" email))
        {:error {:code :auth/invalid-email :message "A valid email address is required."}}

        (or (and owner (not= user-id (:id owner)))
            (and claimed (not= user-id (:id claimed))))
        {:error {:code :auth/email-in-use :message "Another Studio user already uses this email."}}

        :else
        (do (d/transact conn [{:identity.user/id user-id
                               :identity.user/sso-email email
                               :identity.user/updated-at now}])
            {:status :ok :user (public-user (user-by-id (d/db conn) user-id))})))))

(defn bind-sso!
  "Write `(issuer, subject)` onto an existing account (§8.16.4 rule 4.3).

   Refuses to move a link: a second principal on one account, or one principal
   on a second account, is an administrator's decision and not a login's."
  [conn user-id sso-issuer sso-subject]
  (locking conn
    (let [db (d/db conn)
          user (user-by-id db user-id)]
      (cond
        (nil? user)
        {:error {:code :no-such-user :message "No such Studio user."}}

        (:sso-subject user)
        {:error {:code :already-linked
                 :message "This Studio user is already linked to an identity provider account."}}

        (user-by-principal db sso-issuer sso-subject)
        {:error {:code :principal-taken
                 :message "Another Studio user is already linked to that identity provider account."}}

        :else
        (let [now (str (Instant/now))]
          (d/transact conn (cond-> [{:identity.user/id user-id
                                     :identity.user/sso-key (sso-key sso-issuer sso-subject)
                                     :identity.user/sso-issuer sso-issuer
                                     :identity.user/sso-subject sso-subject
                                     :identity.user/updated-at now}]
                             ;; The pre-registration is consumed by the bind it
                             ;; was set for (§8.16.9 item 3); from here on only
                             ;; `user-by-principal` reaches this account.
                             (:sso-email user)
                             (conj [:db/retract [:identity.user/id user-id]
                                    :identity.user/sso-email (:sso-email user)])))
          {:status :ok
           :user (public-user (user-by-id (d/db conn) user-id))})))))

(defn create-sso-user!
  "Auto-provisioning from a verified identity provider identity (§8.16.4 rule
   4.5; studio provisions, doc §8.16.5). Same transaction shape as
   `create-local-user!` — a member plus that member's own workspace — with the
   link written in the same transaction, so an account never exists for a
   moment without the principal that is the only way into it."
  [conn {:keys [email name] sso-issuer :issuer sso-subject :subject}]
  (let [email (some-> email str/trim str/lower-case)
        name (or (some-> name str/trim not-empty) email)]
    (if-let [error (valid-account-input? email name nil true)]
      {:error error}
      (locking conn
        (let [db (d/db conn)]
          (cond
            (user-by-email db email)
            {:error {:code :email-taken :message "A Studio user with this email already exists."}}

            (user-by-principal db sso-issuer sso-subject)
            {:error {:code :principal-taken
                     :message "A Studio user is already linked to that identity provider account."}}

            (workspace-name-taken db (personal-workspace-name email))
            {:error (workspace-name-taken db (personal-workspace-name email))}

            :else
            (let [id (str (UUID/randomUUID))
                  principal {:issuer issuer :subject id}
                  workspace (personal-workspace-name email)
                  now (str (Instant/now))
                  user (assoc (user-tx id email name nil :member workspace now)
                              :identity.user/sso-key (sso-key sso-issuer sso-subject)
                              :identity.user/sso-issuer sso-issuer
                              :identity.user/sso-subject sso-subject)]
              (d/transact conn [user (personal-workspace-tx workspace principal email name)])
              {:status :ok :user (public-user (entity->user user))
               :workspace workspace})))))))

(defn authenticate-password
  "nil for a wrong password, an unknown address, a disabled account **and** an
   account with no password at all (§8.16.4 rule 5) — one refusal, so the login
   face cannot tell the four apart and neither can whoever is knocking."
  [db email plaintext]
  (when-let [user (user-by-email db email)]
    (when (and (not (:disabled? user))
               (some? (:password-hash user))
               (password/verify-password plaintext (:password-hash user)))
      (public-user user))))

(defn- revoke-user-sessions! [user-id]
  (swap! sessions
         (fn [active]
           (into {} (remove (fn [[_ session]]
                              (= user-id (get-in session [:user :id])))
                            active))))
  nil)

(defn change-password!
  "Change the authenticated user's own local password.

   The current password is always verified against the latest stored hash; the
   caller cannot name another user.  Password-only and linked SSO accounts may
   use this path when they already have a local password.  A pure SSO account
   is refused instead of silently turning an identity-provider account into a
   local-password account.  Every browser session for the user is revoked on
   success, including the current one."
  [conn user current-password new-password]
  (let [user-id (:id user)]
    (locking conn
      (let [stored (user-by-id (d/db conn) user-id)]
        (cond
          (or (nil? stored) (:disabled? stored))
          {:error {:code :invalid-current-password
                   :message "The current password is incorrect."}}

          (nil? (:password-hash stored))
          {:error {:code :local-password-unavailable
                   :message "This account signs in with SSO and has no local password to change."}}

          (not (password/verify-password (or current-password "")
                                         (:password-hash stored)))
          {:error {:code :invalid-current-password
                   :message "The current password is incorrect."}}

          (< (count (or new-password "")) 12)
          {:error {:code :weak-password
                   :message "Password must contain at least 12 characters."}}

          :else
          (let [now (str (Instant/now))]
            (d/transact conn [{:db/id [:identity.user/id user-id]
                               :identity.user/password-hash (password/hash-password new-password)
                               :identity.user/updated-at now}])
            (revoke-user-sessions! user-id)
            {:status :ok
             :user (public-user (user-by-id (d/db conn) user-id))}))))))

(defn reset-password!
  "Administrator password reset for an existing local-password account.  It
   never creates a local password for a pure SSO account and revokes every
   active session for the target after replacing the hash."
  [conn user-id new-password]
  (locking conn
    (let [stored (user-by-id (d/db conn) user-id)]
      (cond
        (nil? stored)
        {:error {:code :no-such-user :message "No such Studio user."}}

        (:disabled? stored)
        {:error {:code :user-disabled :message "This Studio user has been removed."}}

        (nil? (:password-hash stored))
        {:error {:code :local-password-unavailable
                 :message "This SSO-only account has no local password to reset."}}

        (< (count (or new-password "")) 12)
        {:error {:code :weak-password
                 :message "Password must contain at least 12 characters."}}

        :else
        (let [now (str (Instant/now))]
          (d/transact conn [{:db/id [:identity.user/id user-id]
                             :identity.user/password-hash (password/hash-password new-password)
                             :identity.user/updated-at now}])
          (revoke-user-sessions! user-id)
          {:status :ok
           :user (public-user (user-by-id (d/db conn) user-id))})))))

(defn update-user!
  "Admin-managed profile updates preserve the immutable user principal and workspaces."
  [conn {:keys [user-id name email role]}]
  (locking conn
    (let [db (d/db conn)
          target (user-by-id db user-id)
          name (some-> name str/trim)
          email (some-> email str/trim str/lower-case)
          duplicate (user-by-email db email)
          enabled-admins (count (filter #(and (admin? %) (not (:disabled? %))) (users db)))]
      (cond
        (nil? target) {:error {:code :user-not-found :message "User not found."}}
        (:disabled? target) {:error {:code :user-disabled :message "This account has been removed."}}
        (not (#{:admin :member} role)) {:error {:code :invalid-system-role :message "Choose Admin or Member."}}
        (valid-account-input? email name nil true) {:error (valid-account-input? email name nil true)}
        (and duplicate (not= user-id (:id duplicate))) {:error {:code :email-taken :message "A Studio user with this email already exists."}}
        (and (admin? target) (= role :member) (<= enabled-admins 1))
        {:error {:code :last-admin-required :message "Keep at least one enabled Studio administrator."}}
        :else
        (do
          (d/transact conn [{:db/id [:identity.user/id user-id] :identity.user/name name
                            :identity.user/email email :identity.user/system-role role
                            :identity.user/updated-at (str (Instant/now))}])
          ;; Cached sessions must not retain an old role or profile.
          (swap! sessions (fn [entries]
                            (into {} (map (fn [[token session]]
                                           [token (if (= user-id (get-in session [:user :id]))
                                                    (assoc session :user (user-by-id (d/db conn) user-id)) session)])) entries)))
          {:status :ok :user (public-user (user-by-id (d/db conn) user-id))})))))

(defn remove-user!
  "Remove a user's Studio access without erasing attribution or model data.

   This is a soft removal: the identity and workspace membership records remain
   so historical ownership and audit principals stay intelligible, while the
   disabled account can no longer authenticate.  Administrators cannot remove
   themselves, and the last enabled system administrator is protected."
  [conn actor target-id]
  (locking conn
    (let [db (d/db conn)
          target (user-by-id db target-id)
          enabled-admins (count (filter #(and (admin? %) (not (:disabled? %)))
                                        (users db)))]
      (cond
        (nil? target)
        {:error {:code :no-such-user :message "No such Studio user."}}

        (= (:id actor) target-id)
        {:error {:code :cannot-remove-self
                 :message "You cannot remove your own Studio account."}}

        (:disabled? target)
        {:status :ok :changed? false :user (public-user target)}

        (and (admin? target) (<= enabled-admins 1))
        {:error {:code :last-admin-required
                 :message "The last enabled Studio administrator cannot be removed."}}

        :else
        (let [now (str (Instant/now))]
          (d/transact conn [{:db/id [:identity.user/id target-id]
                             :identity.user/disabled true
                             :identity.user/updated-at now}])
          (revoke-user-sessions! target-id)
          {:status :ok :changed? true
           :user (public-user (user-by-id (d/db conn) target-id))})))))

(defn restore-user!
  "Restore sign-in for a removed identity, retaining its role and memberships."
  [conn target-id]
  (locking conn
    (if-let [target (user-by-id (d/db conn) target-id)]
      (if-not (:disabled? target)
        {:status :ok :changed? false :user (public-user target)}
        (do
          (d/transact conn [{:db/id [:identity.user/id target-id]
                             :identity.user/disabled false
                             :identity.user/updated-at (str (Instant/now))}])
          {:status :ok :changed? true
           :user (public-user (user-by-id (d/db conn) target-id))}))
      {:error {:code :no-such-user :message "No such Studio user."}})))

(defn- session-token []
  (let [bytes (byte-array 32)]
    (.nextBytes rng bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn create-session! [user]
  (let [token (session-token)
        expires (+ (quot (System/currentTimeMillis) 1000) session-seconds)]
    (swap! sessions assoc token {:user user :expires expires})
    {:token token :expires expires :max-age session-seconds}))

(defn session-user [token]
  (when-let [{:keys [user expires]} (get @sessions token)]
    (if (> expires (quot (System/currentTimeMillis) 1000))
      user
      (do (swap! sessions dissoc token) nil))))

(defn destroy-session! [token]
  (swap! sessions dissoc token)
  nil)

(defn reset-sessions! [] (reset! sessions {}))
