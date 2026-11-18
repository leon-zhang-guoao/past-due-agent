(ns open.atlas.server.identity
  "Operator accounts for the standalone Server's console (8.12 P2).

   ## Why this is not Studio's identity

   The Builder keeps its local identities in the Datahike pool, alongside
   workspace membership, so that the founding admin and its owner membership
   land in one transaction. A standalone Server has no pool — the pool is the
   Builder's, and `apps/server` may not depend on `modules/studio/*` (D1.8
   makes that a classpath fact, not a review habit). So this is a separate,
   deliberately small store: **one SQLite file under the instance root**, owner
   ruling 2026-08-31.

   It is deliberately NOT the inherited stack's workspace db
   (`ent.dx.db.workspace` and its 21 migrations): reusing that would have made
   retiring the legacy stack depend on first extracting a login from it. That
   stack, and that database, were retired in 8.16 — this store outlived it,
   which is the whole point of having kept them separate.

   ## What is stored, and what is not

   Accounts are stored. Sessions are **in-memory**, so a restart signs everyone
   out — a property rather than a gap: a session is a browser's claim to a
   still-running process, and the process that issued it is gone.

   The principal is an immutable UUID subject (D5.10); the username is display
   and login only, and never participates in authorization matching. Renaming
   an operator must not silently re-point an audit trail.

   ## The console is closed until an admin exists

   `bootstrapped?` is false on a fresh instance, and `authenticate` refuses.
   There is no 'no accounts yet, so let anyone in' branch — D5.7 has no back
   door for dev either, and the moment such a branch exists it is reachable by
   deleting a file."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [open.atlas.common.crypto :as crypto]
            [open.atlas.runtime.store :as store])
  (:import [java.io File]
           [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def ^:private issuer
  "The issuer half of every principal minted here (D5.10). Local accounts on
   one instance; the subject half is a UUID, so two instances never collide."
  "open-atlas-local")

(def ^:private session-seconds (* 12 60 60))
(def ^:private ^SecureRandom rng (SecureRandom.))
(defonce ^:private sessions (atom {}))

;; --- the database ------------------------------------------------------------

(defn- identity-dir ^File [instance] (store/subsystem-dir instance "identity"))

(defn db-file
  "`<instance root>/identity/identity.db`."
  ^File [instance]
  (File. (identity-dir instance) "identity.db"))

(defn- datasource [instance]
  (let [^File f (db-file instance)]
    (.mkdirs (.getParentFile f))
    (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})))

(defn- create-table-sql
  "The operator table. `password_hash` is NULLABLE (doc [8.16.5]
   (../../../../../../docs/design/101-sso-oidc.md)): an operator the
   administrator pre-registered for single sign-on has no password at all, and
   the alternative — an empty-string sentinel, which is what SQLite's inability
   to drop NOT NULL invites — is worse than the migration below. A sentinel is
   a value `verify-password` would be handed, and the day someone reads it as
   \"the hash of the empty password\" the account is open. A real NULL cannot be
   misread."
  [if-absent?]
  (str "CREATE TABLE " (when if-absent? "IF NOT EXISTS ") "operator (
      id            TEXT PRIMARY KEY,
      username      TEXT NOT NULL UNIQUE,
      display_name  TEXT,
      password_hash TEXT,
      disabled      INTEGER NOT NULL DEFAULT 0,
      created_at    TEXT NOT NULL,
      updated_at    TEXT NOT NULL,
      -- The principal half of an SSO link, (issuer, subject) — immutable
      -- (D5.10). NULL until a link is made; `sso_email` is the address an
      -- administrator pre-registered, matched exactly once (doc 8.16.4 rule 4.3).
      sso_issuer    TEXT,
      sso_subject   TEXT,
      sso_email     TEXT)"))

(def ^:private indexes
  ;; Each idempotent. Usernames are compared case-insensitively at login, so
  ;; uniqueness has to be case-insensitive too — otherwise `Admin` and `admin`
  ;; are two rows that one login can match either of. The same holds for
  ;; `sso_email`, which arrives lowercased from the identity provider's claim.
  ;; SQLite treats NULLs as distinct in a unique index, so the many rows with
  ;; no link and no SSO address do not collide.
  ["CREATE UNIQUE INDEX IF NOT EXISTS operator_username_ci
      ON operator (LOWER(username))"
   "CREATE UNIQUE INDEX IF NOT EXISTS operator_sso_principal
      ON operator (sso_issuer, sso_subject)"
   "CREATE UNIQUE INDEX IF NOT EXISTS operator_sso_email_ci
      ON operator (LOWER(sso_email))"])

(defn- columns
  "`PRAGMA table_info` as `{column-name row}`; empty when the table is absent."
  [ds]
  (into {} (map (juxt :name identity))
        (jdbc/execute! ds ["PRAGMA table_info(operator)"]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- migrate!
  "Bring a database written before 8.16 up to the shape above. Idempotent by
   construction: each step is guarded by the very fact it establishes, so a
   second open! does nothing.

   SQLite cannot drop a NOT NULL, so the rename/copy migration is the only way
   to a real NULL — see `create-table-sql` for why a sentinel was refused. The
   old table's indexes travel with the rename, so they are dropped by name
   before the new table claims those names back."
  [ds]
  (let [cols (columns ds)]
    (when (and (seq cols) (= 1 (:notnull (get cols "password_hash"))))
      (jdbc/with-transaction [tx ds]
        (jdbc/execute! tx ["ALTER TABLE operator RENAME TO operator_pre_sso"])
        (jdbc/execute! tx ["DROP INDEX IF EXISTS operator_username_ci"])
        (jdbc/execute! tx [(create-table-sql false)])
        (jdbc/execute! tx ["INSERT INTO operator
                            (id, username, display_name, password_hash, disabled,
                             created_at, updated_at)
                            SELECT id, username, display_name, password_hash, disabled,
                                   created_at, updated_at
                            FROM operator_pre_sso"])
        (jdbc/execute! tx ["DROP TABLE operator_pre_sso"])))
    ;; A table that was already rebuilt still predates the three columns if it
    ;; was written between the two changes; add whichever are absent.
    (let [cols (columns ds)]
      (doseq [c ["sso_issuer" "sso_subject" "sso_email"]
              :when (and (seq cols) (not (contains? cols c)))]
        (jdbc/execute! ds [(str "ALTER TABLE operator ADD COLUMN " c " TEXT")])))))

(defn open!
  "Open (creating if absent) this instance's identity database.
   → `{:ds …}` — hand it to every fn below."
  [instance]
  (let [ds (datasource instance)]
    (jdbc/execute! ds [(create-table-sql true)])
    (migrate! ds)
    (doseq [statement indexes] (jdbc/execute! ds [statement]))
    {:ds ds :instance instance}))

(defn- now-iso [] (str (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- row->operator
  "The shape callers see. **Never carries `password_hash`** — a hash that is
   only ever read by `verify` cannot be logged, echoed into a finding, or
   serialised into an API response by someone who assumed the map was safe."
  [row]
  (when row
    {:id           (:operator/id row)
     :username     (:operator/username row)
     :display-name (:operator/display_name row)
     :disabled?    (= 1 (:operator/disabled row))
     :created-at   (:operator/created_at row)
     :updated-at   (:operator/updated_at row)
     ;; The SSO facts a caller may see (doc 8.16.5). `(issuer, subject)` is a
     ;; principal, not a secret — the console shows it so an administrator can
     ;; tell one link from another — but the password hash is still absent, and
     ;; `:has-password?` says only whether one exists.
     :sso-email     (:operator/sso_email row)
     :sso-issuer    (:operator/sso_issuer row)
     :sso-subject   (:operator/sso_subject row)
     :sso-linked?   (some? (:operator/sso_subject row))
     :has-password? (some? (:operator/password_hash row))
     :principal    {:issuer issuer :subject (:operator/id row)}}))

(defn- normalize-email
  "Addresses are compared and stored lowercased and trimmed: the identifying
   claim arrives lowercased from `open.atlas.common.oidc`, and an administrator
   typing `Person@Example.COM` into the console must pre-register the same
   account the provider will assert."
  [email]
  (some-> email str str/trim str/lower-case not-empty))

(defn- find-row [{:keys [ds]} username]
  (first (jdbc/execute! ds ["SELECT * FROM operator WHERE LOWER(username) = LOWER(?)"
                            (str username)]
                        {:builder-fn rs/as-maps})))

(defn- find-row-by-id [{:keys [ds]} id]
  (first (jdbc/execute! ds ["SELECT * FROM operator WHERE id = ?" (str id)]
                        {:builder-fn rs/as-maps})))

(defn- find-row-by-sso-email [{:keys [ds]} email]
  (when-let [e (normalize-email email)]
    (first (jdbc/execute! ds ["SELECT * FROM operator WHERE LOWER(sso_email) = ?" e]
                          {:builder-fn rs/as-maps}))))

(defn operator-by-principal
  "The account linked to `(issuer, subject)`, or nil (doc 8.16.4 rule 4.1).
   This — not the email — is what an SSO login matches on from the second time
   onwards: an address can be reassigned to a new person and a principal cannot
   (D5.10)."
  [{:keys [ds] :as _db} issuer subject]
  (when-not (or (str/blank? (str issuer)) (str/blank? (str subject)))
    (row->operator
     (first (jdbc/execute! ds ["SELECT * FROM operator WHERE sso_issuer = ? AND sso_subject = ?"
                               (str issuer) (str subject)]
                           {:builder-fn rs/as-maps})))))

(defn operator-by-sso-email
  "The account an administrator pre-registered for this address, or nil
   (doc 8.16.4 rule 4.3 — the one and only match by email)."
  [db email]
  (row->operator (find-row-by-sso-email db email)))

(defn operators
  "Every account, without hashes, username order."
  [{:keys [ds]}]
  (mapv row->operator
        (jdbc/execute! ds ["SELECT * FROM operator ORDER BY LOWER(username)"]
                       {:builder-fn rs/as-maps})))

(defn bootstrapped?
  "Does this instance have any operator at all? The console is closed until it
   does — see the ns docstring."
  [{:keys [ds]}]
  (pos? (long (:c (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM operator"]
                                     {:builder-fn rs/as-unqualified-lower-maps})))))

;; --- findings ----------------------------------------------------------------
;; finding-v1: name the place, state the expectation, leak no values. In
;; particular a refusal never echoes the username back for an unknown account
;; and never distinguishes 'no such account' from 'wrong password' — see
;; `authenticate`.

(def finding-codes
  #{:identity.account/exists
    :identity.account/invalid
    ;; An account needs a way in: a password, an SSO email, or both (doc
    ;; 8.16.5). Distinct from `/invalid` because the caller's repair is
    ;; different — not "fix this field" but "supply one of two".
    :identity.account/credentials-required
    ;; A link that would put two accounts on one identity provider account, or
    ;; a second provider account on one operator. The unique index would refuse
    ;; too; a named finding says which of the two it was.
    :identity.sso/link-conflict
    ;; `set-sso-email!`'s refusals. The same codes Workbench and Studio answer
    ;; for the same act (doc 101 §8.16.6), so an administrator reading one
    ;; app's refusal can look it up in any of the three.
    :auth/invalid-email
    :auth/email-in-use
    :auth/identity-already-linked
    ;; Raised by the console, not here: they are rules about WHO is asking,
    ;; and this store does not know that. Registered in the one list so a
    ;; reader looking a code up finds it (see `console/operators` routes).
    :identity.account/self-disable
    :identity.account/last-operator
    :identity.auth/rejected
    :identity.auth/not-bootstrapped})

(defn- error [code path message]
  {:code code :severity :error :path path :message message})

(def ^:private min-password-length 12)

(def ^:private username-pattern #"[A-Za-z0-9][A-Za-z0-9._@-]{1,63}")

;; Deliberately loose: this is a pre-registration, and the address that decides
;; a login is the identity provider's claim, not this one. What it rules out is
;; the input that could never match a claim at all.
(def ^:private sso-email-pattern #"[^@\s]+@[^@\s.]+(\.[^@\s.]+)+")

(defn- account-findings
  "Validation shared by `create-operator!` and `set-password!`.

   `:require-password?` is the latter's case: there the password is the whole
   point, so its absence is `:identity.account/invalid` on `[:password]` rather
   than the create-time \"a password, an SSO email, or both\" refusal."
  [{:keys [username password sso-email require-password?]}]
  (let [password? (not (str/blank? (str password)))
        sso?      (not (str/blank? (str sso-email)))]
    (cond-> []
      (not (re-matches username-pattern (str username)))
      (conj (error :identity.account/invalid [:username]
                   (str "a username is 2-64 characters of letters, digits and "
                        "'.', '_', '-', '@', starting with a letter or digit")))

      (and (or password? require-password?)
           (< (count (str password)) min-password-length))
      (conj (error :identity.account/invalid [:password]
                   (str "a password is at least " min-password-length " characters")))

      (and sso? (not (re-matches sso-email-pattern (normalize-email sso-email))))
      (conj (error :identity.account/invalid [:sso-email]
                   "an SSO email is one address, as the identity provider will assert it"))

      (and (not require-password?) (not password?) (not sso?))
      (conj (error :identity.account/credentials-required [:credentials]
                   (str "an operator needs a password, an SSO email that an "
                        "identity provider account will assert, or both"))))))

(defn create-operator!
  "Create an account. → `{:operator …}` or `{:findings […]}`.

   A password, an `:sso-email`, or both (doc 8.16.5). With only an SSO email
   the row's `password_hash` is a real NULL and password login is impossible
   for it; the account becomes reachable when that address is asserted by the
   identity provider, at which point `bind-sso!` writes the principal on.

   The plaintext password is used here and nowhere else: what reaches the
   database, the return value and every log line is the hash."
  [{:keys [ds] :as db} {:keys [username password display-name sso-email]}]
  (let [email (normalize-email sso-email)]
    (if-let [fs (seq (account-findings {:username username :password password
                                        :sso-email sso-email}))]
      {:findings (vec fs)}
      (cond
        (find-row db username)
        {:findings [(error :identity.account/exists [:username]
                           "an operator with this username already exists")]}

        (and email (find-row-by-sso-email db email))
        {:findings [(error :identity.account/exists [:sso-email]
                           "an operator with this SSO email already exists")]}

        :else
        (let [id  (str (UUID/randomUUID))
              now (now-iso)]
          (jdbc/execute! ds ["INSERT INTO operator
                              (id, username, display_name, password_hash, disabled,
                               created_at, updated_at, sso_email)
                              VALUES (?, ?, ?, ?, 0, ?, ?, ?)"
                             id (str username) display-name
                             (when-not (str/blank? (str password))
                               (crypto/hash-password (str password)))
                             now now email])
          {:operator (row->operator (find-row db username)) :findings []})))))

(defn bind-sso!
  "Write `(issuer, subject)` onto an operator — doc 8.16.4 rule 4.3, the one
   time in an account's life that an SSO identity is matched by email. From
   then on only `operator-by-principal` applies.

   Refused rather than overwritten when the operator already carries a link, or
   when the principal belongs to someone else. The unique index would refuse
   either way; a named finding says which."
  [{:keys [ds] :as db} operator-id issuer subject]
  (let [row    (find-row-by-id db operator-id)
        holder (operator-by-principal db issuer subject)]
    (cond
      (nil? row)
      {:findings [(error :identity.account/invalid [:operator-id] "no such operator")]}

      (some? (:operator/sso_subject row))
      {:findings [(error :identity.sso/link-conflict [:sso]
                         (str "this operator is already linked to an identity "
                              "provider account"))]}

      (and holder (not= (:id holder) (str operator-id)))
      {:findings [(error :identity.sso/link-conflict [:sso]
                         (str "another operator is already linked to this identity "
                              "provider account"))]}

      :else
      ;; The pre-registration is consumed here (doc 101 §8.16.9 item 3): from
      ;; now on only `operator-by-principal` matches this account, so a later
      ;; holder of the same address cannot reach it by email.
      (do (jdbc/execute! ds ["UPDATE operator SET sso_issuer = ?, sso_subject = ?,
                              sso_email = NULL, updated_at = ? WHERE id = ?"
                             (str issuer) (str subject) (now-iso) (str operator-id)])
          {:operator (row->operator (find-row-by-id db operator-id)) :findings []}))))

(defn set-sso-email!
  "Pre-register an EXISTING operator for single sign-on, or clear it (blank
   `:sso-email`). → `{:operator …}` or `{:findings […]}`.

   Doc 101 §8.16.4 rule 4.4's remedy (§8.16.9 item 3): the next verified
   sign-in asserting this address binds its `(issuer, subject)` onto the
   account once — password or not — and `bind-sso!` clears the address.
   Moving an existing link is not this act (§8.16.8), so a linked operator is
   refused. Clearing is refused where it would leave the account with no way
   in: an unlinked operator without a password."
  [{:keys [ds] :as db} {:keys [username sso-email]}]
  (let [row   (find-row db username)
        email (normalize-email sso-email)
        other (some-> email (as-> e (find-row-by-sso-email db e)))]
    (cond
      (nil? row)
      {:findings [(error :identity.account/invalid [:username] "no such operator")]}

      (some? (:operator/sso_subject row))
      {:findings [(error :auth/identity-already-linked [:sso-email]
                         (str "an operator not yet linked to an identity provider "
                              "account; this one already is"))]}

      (and (nil? email) (nil? (:operator/password_hash row)))
      {:findings [(error :identity.account/credentials-required [:sso-email]
                         (str "an operator without a password keeps its SSO email; "
                              "set a password before clearing it"))]}

      (and email (not (re-matches sso-email-pattern email)))
      {:findings [(error :auth/invalid-email [:sso-email]
                         "an SSO email is one address, as the identity provider will assert it")]}

      (and other (not= (:operator/id other) (:operator/id row)))
      {:findings [(error :auth/email-in-use [:sso-email]
                         "an SSO email no other operator is pre-registered with")]}

      :else
      (do (jdbc/execute! ds ["UPDATE operator SET sso_email = ?, updated_at = ? WHERE id = ?"
                             email (now-iso) (:operator/id row)])
          {:operator (row->operator (find-row-by-id db (:operator/id row))) :findings []}))))

(defn set-password!
  "Set (or add) a password. Allowed on an SSO operator: giving one an
   administrator-chosen password is an administrator's act, and afterwards both
   ways in work (doc 8.16.4 rule 5)."
  [{:keys [ds] :as db} {:keys [username password]}]
  (if-let [fs (seq (account-findings {:username username :password password
                                      :require-password? true}))]
    {:findings (vec fs)}
    (if-let [row (find-row db username)]
      (do (jdbc/execute! ds ["UPDATE operator SET password_hash = ?, updated_at = ? WHERE id = ?"
                             (crypto/hash-password (str password)) (now-iso)
                             (:operator/id row)])
          {:operator (row->operator (find-row db username)) :findings []})
      {:findings [(error :identity.account/invalid [:username]
                         "no such operator")]})))

(defn set-disabled!
  [{:keys [ds] :as db} username disabled?]
  (if-let [row (find-row db username)]
    (do (jdbc/execute! ds ["UPDATE operator SET disabled = ?, updated_at = ? WHERE id = ?"
                           (if disabled? 1 0) (now-iso) (:operator/id row)])
        ;; Sign out what the account already holds. Leaving live sessions
        ;; behind would make "disabled" mean "cannot log in again", which is
        ;; not what an operator disabling an account is asking for.
        (swap! sessions #(into {} (remove (fn [[_ s]] (= (:id (:operator s)) (:operator/id row)))) %))
        {:operator (row->operator (find-row db username)) :findings []})
    {:findings [(error :identity.account/invalid [:username] "no such operator")]}))

;; --- sessions ----------------------------------------------------------------

(defn- session-token []
  (let [bs (byte-array 32)]
    (.nextBytes rng bs)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(defn create-session!
  "Mint a session for an operator that has ALREADY been authenticated — by
   password here, or by a verified id_token in the console's OIDC callback.

   One shape for both, deliberately (doc 8.16.4 rule 6): single sign-on is one
   more way to obtain a session, not a second kind of session with its own
   lifetime, cookie or expiry rule."
  [operator]
  (let [token   (session-token)
        expires (str (.plusSeconds (Instant/now) session-seconds))]
    (swap! sessions assoc token {:operator operator :expires-at expires})
    {:session {:token token :expires-at expires} :operator operator :findings []}))

(defn authenticate
  "username + password → `{:session {:token … :expires-at …} :operator …}`
   or `{:findings […]}`.

   **One refusal for five causes** — no accounts yet aside, an unknown
   username, a wrong password, a disabled account, and an account that has no
   password at all (an SSO pre-registration, doc 8.16.4 rule 5) all answer
   `:identity.auth/rejected` with the same message. Distinguishing them turns
   the login form into an account enumerator.

   The wrong password still costs a full PBKDF2 verification for a *known*
   account. For an unknown one it does not, which is a timing difference this
   does not pretend to erase; the console is a loopback-by-default operator
   surface, and a constant-time login would be a claim worth making only
   alongside rate limiting, which is 8.12 P3's business."
  [db {:keys [username password]}]
  (let [rejected {:findings [(error :identity.auth/rejected [:credentials]
                                    "the username or password is not correct")]}]
    (cond
      (not (bootstrapped? db))
      {:findings [(error :identity.auth/not-bootstrapped [:instance]
                         (str "this instance has no operator account yet, so the "
                              "console is closed; bootstrap one with "
                              "open.atlas.server.identity.bootstrap"))]}

      :else
      (let [row (find-row db username)]
        (if (and row
                 (not= 1 (:operator/disabled row))
                 ;; A NULL hash is an SSO-only account. It gets the SAME
                 ;; refusal: "this account exists but has no password" is a
                 ;; fact a login form must not confirm.
                 (some? (:operator/password_hash row))
                 (crypto/verify-password (str password) (:operator/password_hash row)))
          (create-session! (row->operator row))
          rejected)))))

(defn session-operator
  "A session token → the operator, or nil. Expiry is checked here rather than
   swept on a timer: a token that has outlived its window must not work in the
   window between the sweep and the request."
  [token]
  (when-let [{:keys [operator expires-at]} (and (string? token) (get @sessions token))]
    (if (pos? (compare expires-at (str (Instant/now))))
      operator
      (do (swap! sessions dissoc token) nil))))

(defn sign-out! [token] (swap! sessions dissoc token) nil)

(defn sign-out-all!
  "Every session on this process. Used by tests and by a restart's clean slate."
  []
  (reset! sessions {}))
