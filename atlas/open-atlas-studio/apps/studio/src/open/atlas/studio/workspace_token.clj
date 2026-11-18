(ns open.atlas.studio.workspace-token
  "**Workspace authorisation tokens** — the Builder MCP endpoint's credential
   ([8.4](../../../../../../docs/design/84-builder-mcp.md) ruling ②).

   ## Why this is a second ledger and not a second use of the first

   This instance already has a token ledger: `runtime/token`, the data plane's.
   That one answers *「这把钥匙可以读哪张图的哪个版本」* and its holder talks to
   `/eql` and the data-plane `/mcp`. This one answers *「这把钥匙可以在哪个
   workspace 里改模型」* and its holder talks to the Builder.

   [D3.12](../../../../../../docs/design/30-workspace-graph.md) already split
   those two doors into separate listeners with separate credentials, for the
   reason that one entrance accepting both kinds of key would make a leaked
   query token into a deploy permission. Two doors with one ledger would put
   that back: every widening of a data-plane grant would silently be a
   widening of write authority too, and nobody reviewing the first change
   would be looking at the second.

   So: separate ledger, separate pepper (S5.9 — the token pepper authenticates
   data-plane tokens and must not also authorise model writes), separate
   prefix. **Presenting one at the other's door is a structured refusal**, and
   the prefix is what makes that refusal cheap and legible rather than a
   confusing signature failure.

   ## What is stored, and what is not

   The record: `token-id`, a keyed **digest**, the workspace, the scope, who
   issued it and when, **when it expires**, whether it is revoked, and the two
   fields a human writes — `:label` and `:description` (see below). The
   secret itself appears exactly once — in the response to the call that issued it — and is
   afterwards unrecoverable from anything this instance keeps (S5.2/S5.9), the
   same rule the data-plane ledger follows.

   The digest is keyed (HMAC), not a bare hash: an unkeyed digest of a
   high-entropy secret is not brute-forceable in practice, but a keyed one
   additionally makes the ledger useless to anyone who copies the file without
   also copying this instance's secrets.

   ## Expiry and revocation are two fields because they are two facts

   `:expires-at` is set at issue and never changes; `:revoked-at` is set by a
   human deciding this key must stop working. A ledger with only the second
   one has no natural death: a key that authorises model writes outlives the
   engagement it was issued for, and the only thing standing between it and
   forever is somebody remembering. Collapsing them into one field would also
   lose the governance answer that matters most — «did we retire this, or did
   it simply run out?» is the difference between a process that works and one
   nobody is running.

   **`verify` treats them identically**: both are nil, indistinguishable from
   an unknown digest and from a data-plane token presented at this door. The
   split is for the *ledger view*, which is read by someone who has already
   authenticated; it is never visible to the presenter of a key.

   Expiry is required at issue. There is no default in this ns on purpose: a
   lifetime invented by the code is a policy decision hidden in an
   implementation, and the caller that omitted it would never learn it had
   accepted one.

   ## Scope is `#{:read}` or `#{:read :write}`, and it is the capability set

   It goes straight to `toolkit/registry` as the capability set, which is what
   makes «no write key ⇒ the write tools are ABSENT» true by construction
   rather than by a filter someone maintains (`common/toolkit` ruling ②).
   There is no write-only scope: a caller that may write must be able to read
   what it is about to overwrite.

   ## `:label` and `:description` are two fields because they answer two questions

   `:label` — the UI calls it **name** — is **required**. 8.4 ruling 8.4.9 ③
   gives one OMA agent one key and records which agent in the label, because
   the pool's mutation audit keeps only `token-id`: a key two agents share
   cannot answer which of them made a change. A key with no name is precisely
   the state that ruling exists to prevent, so an issue without one is refused
   rather than written with a blank.

   `:description` is optional free text answering «why does this key exist»
   (8.4.11). It exists because one field asked to be both a short audit clue
   and a purpose ended up worded «used by» — a person's name slot for a
   credential that usually belongs to no person.

   Both are bounded, and over-long is **refused rather than truncated**: a
   silently shortened name no longer matches the agent it was recording, and
   the reader has no way to tell that happened. Neither field is read by
   anything — verification, revocation and the audit chain all run off
   `token-id`. They are clues for the human reading the ledger, and this ns
   stores them without interpreting them.

   ## Why the two writers take a lock and the readers do not

   `issue!` and `revoke!` are both read-modify-write over the whole ledger. Run
   concurrently unserialised, the later `write-ledger!` lands the vector the
   earlier one had already superseded — and when the loser is a revocation,
   **a key a human retired goes on working**. That is not a lost update, it is
   a security event, and nothing downstream would ever report it: the caller
   was told the revocation succeeded.

   So both wrap their entire read-modify-write in a monitor keyed by ledger
   path. Per path rather than one global object, so two homes (two workspaces,
   or two tests) do not serialise each other for no reason. An in-process
   monitor is sufficient here and only here: the Builder is a single process
   (v1 single-writer ruling), so there is no second JVM holding this file.

   Readers stay lock-free. `write-ledger!` lands the new file with an atomic
   move, so a reader sees the whole old ledger or the whole new one — never a
   half-written vector."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.crypto :as crypto]
            [open.atlas.runtime.store :as store]))

(def ^:private ledger-file "workspace-tokens.edn")

(def token-prefix
  "The visible half of «wrong key at the wrong door». A data-plane token starts
   `oat_v1.`; this one starts `oaw_v1.`. Neither door has to attempt a
   signature check to tell the caller which key they brought."
  "oaw_v1.")

(def scopes
  "`#{:read}` or `#{:read :write}` — nothing else. See the ns docstring on why
   write-only is not offered."
  #{#{:read} #{:read :write}})

(def max-label-chars
  "The bound on `:label`. A name, not a sentence: 120 clears an OMA agent id and
   any client name anybody would recognise at a glance, and refuses the case
   where the purpose was typed into the name box because there was nowhere else
   to put it — which is what `:description` is now for."
  120)

(def max-description-chars
  "The bound on `:description`. A paragraph's worth. It is a bound rather than
   no bound because this ledger is a plaintext file on disk and the field takes
   whatever is pasted into it."
  1000)

(defn- required-name
  "The trimmed `:label`, or a refusal. See the ns docstring: 8.4.9 ③ wants every
   key to carry which client holds it, and a blank written into the ledger
   satisfies the schema while defeating the ruling."
  [label]
  (let [trimmed (when (string? label) (str/trim label))]
    (cond
      (not (seq trimmed))
      (throw (ex-info "A workspace token needs a name — the client or agent that will hold it"
                      {:code :workspace-token-label-invalid}))

      ;; The length, never the value: a refusal that echoes what was typed
      ;; turns a diagnostic into a mirror (finding-v1 §5).
      (> (count trimmed) max-label-chars)
      (throw (ex-info (str "A workspace token name must be at most " max-label-chars
                           " characters")
                      {:code :workspace-token-label-invalid
                       :expected max-label-chars :got (count trimmed)}))

      :else trimmed)))

(defn- optional-description
  "The trimmed `:description`, `nil` when absent or blank.

   Blank collapses to absent rather than to `\"\"`: a reader who sees the key
   present but empty has to work out whether somebody cleared it or the writer
   was buggy, and there is no version of that question worth asking."
  [description]
  (when (some? description)
    (when-not (string? description)
      (throw (ex-info "A workspace token description must be text"
                      {:code :workspace-token-description-invalid})))
    (let [trimmed (str/trim description)]
      (when (> (count trimmed) max-description-chars)
        (throw (ex-info (str "A workspace token description must be at most "
                             max-description-chars " characters")
                        {:code :workspace-token-description-invalid
                         :expected max-description-chars :got (count trimmed)})))
      (when (seq trimmed) trimmed))))

(defn- ledger-path [home] (str home "/" ledger-file))

(def ^:private ledger-monitors
  "Ledger path → monitor. See the ns docstring: keyed by path so two homes do
   not serialise each other."
  (atom {}))

(defn- ledger-monitor [home]
  (let [f (io/file (ledger-path home))
        ;; The canonical path, so `/a/./b` and `/a/b` are one key rather than
        ;; two monitors over one file — which would serialise nothing.
        key* (try (.getCanonicalPath f) (catch Exception _ (.getAbsolutePath f)))]
    (or (get @ledger-monitors key*)
        (get (swap! ledger-monitors update key* #(or % (Object.))) key*))))

;; Lock-free: the writers land whole files atomically, so a reader gets the
;; whole old ledger or the whole new one, never a partial one.
(defn- read-ledger [home]
  (let [f (io/file (ledger-path home))]
    (if (.isFile f) (edn/read-string (slurp f)) [])))

(defn- write-ledger! [home records]
  (let [f (io/file (ledger-path home))
        records (vec records)]
    ;; The instance's ONE atomic landing (D4.12): `runtime/store` owns
    ;; temp→fsync→move so the refusal on a filesystem that cannot move
    ;; atomically is judged in one place. A second implementation here would
    ;; make it two, and only one of them would be the one anybody reviews.
    (store/write-edn! f records)
    ;; Owner-only, exactly as `secrets.edn`: this file is the authority for
    ;; every model write this Builder will accept over MCP. Applied after the
    ;; move rather than to the temp file, which lives inside `runtime/store`
    ;; and is not this ns's to touch; the residual window is microseconds
    ;; inside a studio home that is itself private.
    (try (.setReadable f false false) (.setReadable f true true)
         (.setWritable f false false) (.setWritable f true true)
         (catch Exception _ nil))
    records))

(defn- digest [pepper secret] (crypto/hmac-sha256 pepper secret))

;; ---------------------------------------------------------------------------
;; Instants
;;
;; Parsed rather than string-compared. Both instants this ns handles are
;; produced by the same host function today, so lexicographic order would
;; happen to work — and would break silently the day one of them carries a
;; sub-second field or an offset other than `Z`. This ns reads NO clock: the
;; caller passes «now» in, which is what keeps it a pure ns the tests can pin.
;; ---------------------------------------------------------------------------

(defn- parse-instant ^java.time.Instant [x]
  (when (string? x)
    (try (java.time.Instant/parse x) (catch Exception _ nil))))

(defn- instant? [x] (some? (parse-instant x)))

(defn- before? [a b]
  (.isBefore (parse-instant a) (parse-instant b)))

(defn- new-secret []
  (let [b (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) b)
    (str token-prefix
         (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b))))

(defn status
  "`:revoked` | `:expired` | `:active` for one record, as of `now-iso`.

   Revocation wins over expiry when both are true: it is the deliberate act,
   and «we retired this key» is the answer a governance reader needs — that it
   would also have run out by now is incidental.

   This is a **ledger-view** distinction only. `verify` makes none of it: to
   the presenter of a key, expired and revoked and never-existed are one
   answer."
  [record now-iso]
  (cond
    (:revoked-at record) :revoked
    ;; `expires-at` missing ⇒ a record written before this field existed. It
    ;; is reported as expired rather than active, and `verify` refuses it, for
    ;; the reason expiry was added at all: an eternal model-write key is the
    ;; thing this change removes, and treating the old ones as eternal would
    ;; leave the very keys that motivated it untouched.
    (not (instant? (:expires-at record))) :expired
    (not (instant? now-iso))
    (throw (ex-info "status needs an ISO-8601 instant for «now»"
                    {:code :workspace-token-now-invalid :got now-iso}))
    ;; Active strictly BEFORE the instant it expires at. At exactly that
    ;; instant it is over — an expiry that still worked on its own timestamp
    ;; is an off-by-one nobody would think to test for.
    (before? now-iso (:expires-at record)) :active
    :else :expired))

(defn records
  "The governance view: records only, **never a digest and never a secret**
   (S5.1). What a panel or an audit may see.

   With `now-iso`, each record additionally carries `:status` (see `status`) —
   the panel must be able to say WHICH of the three a dead key is, or 「撤销」
   and 「过期」 look like one undifferentiated 'not working'."
  ([home]
   (->> (read-ledger home)
        (map #(dissoc % :digest))
        (sort-by :issued-at)
        vec))
  ([home now-iso]
   (mapv #(assoc % :status (status % now-iso)) (records home))))

(defn issue!
  "Mint a token for one workspace. → `{:token secret :record {…}}`.

   `:expires-at` is **required** (ISO-8601 instant) — this ns invents no
   default lifetime.

   `:label` is **required** and `:description` optional; both are bounded and
   refused rather than truncated (see the ns docstring).

   The secret is in this return value and **nowhere afterwards** — not in the
   ledger, not in a log, not in any later read. A caller that loses it issues
   another one and revokes this."
  [home pepper {:keys [workspace scope issued-by issued-at expires-at label description]}]
  ;; Named, not just rejected (finding-v1; 0.2 acceptance defect 10): the
  ;; caller cannot look the answer up in this file.
  (when-not (contains? scopes scope)
    (throw (ex-info "scope must be #{:read} or #{:read :write}"
                    {:code :workspace-token-scope-invalid :got scope
                     :expected [#{:read} #{:read :write}]})))
  ;; Required, not defaulted — see the ns docstring. A caller that forgot is
  ;; told so; a caller that is silently given a lifetime never finds out.
  (when-not (instant? expires-at)
    (throw (ex-info "expires-at must be an ISO-8601 instant"
                    {:code :workspace-token-expiry-invalid :got expires-at})))
  (when-not (instant? issued-at)
    (throw (ex-info "issued-at must be an ISO-8601 instant"
                    {:code :workspace-token-issued-at-invalid :got issued-at})))
  (when (before? expires-at issued-at)
    (throw (ex-info "expires-at must not precede issued-at"
                    {:code :workspace-token-expiry-invalid :got expires-at})))
  (let [name* (required-name label)
        description* (optional-description description)
        secret (new-secret)
        ;; Validation and minting sit outside the monitor on purpose: they read
        ;; nothing from the ledger, and a refusal must not wait on a writer.
        record (cond-> {:token-id (str "wt-" (java.util.UUID/randomUUID))
                        :digest (digest pepper secret)
                        :workspace workspace
                        :scope scope
                        :issued-by issued-by
                        :issued-at issued-at
                        :expires-at expires-at
                        :revoked-at nil
                        :label name*}
                 description* (assoc :description description*))]
    ;; Read and write as one step: an issue that read a ledger another writer
    ;; has already replaced would land it back, silently undoing that write.
    (locking (ledger-monitor home)
      (write-ledger! home (conj (vec (read-ledger home)) record)))
    {:token secret :record (dissoc record :digest)}))

(defn revoke!
  "Revoke by id. Idempotent — revoking an already-revoked token is not an
   error, because the caller's intent («this key must not work») is already
   true and failing would invite them to go looking for a second one."
  [home {:keys [token-id revoked-at]}]
  ;; The whole read-modify-write, not just the write: a revocation that loses
  ;; the race leaves a key its owner was told is dead still opening the door
  ;; (ns docstring — this is why the lock exists).
  (locking (ledger-monitor home)
    (let [records* (read-ledger home)]
      (when-not (some #(= token-id (:token-id %)) records*)
        (throw (ex-info "No such workspace token"
                        {:code :workspace-token-unknown :token-id token-id})))
      (write-ledger! home
                     (mapv (fn [r]
                             (if (and (= token-id (:token-id r)) (nil? (:revoked-at r)))
                               (assoc r :revoked-at revoked-at)
                               r))
                           records*))))
  {:token-id token-id :revoked-at revoked-at})

(defn verify
  "Credential → the record it authenticates, or nil.

   nil covers every failure — wrong prefix, unknown digest, revoked,
   **expired** — and deliberately does not distinguish them to the caller:
   telling an attacker «that token existed but is revoked» (or «…but ran out
   last Tuesday») is a fact about this instance they have no way to learn
   otherwise. Expiry therefore joins the others INSIDE the same filter rather
   than becoming a second, separately-shaped refusal.

   `now-iso` is passed in, never read from a clock here: this ns is pure, and
   a test that cannot move time cannot check that a key stops working.

   The comparison is constant-time (`crypto/constant-time=`): a byte-by-byte
   early exit leaks the digest one character at a time to anyone who can time
   the endpoint."
  [home pepper credential now-iso]
  (when (and (string? credential)
             (clojure.string/starts-with? credential token-prefix))
    (let [d (digest pepper credential)]
      (->> (read-ledger home)
           (filter #(and (= :active (status % now-iso))
                         (crypto/constant-time= d (:digest %))))
           first
           (#(some-> % (dissoc :digest)))))))
