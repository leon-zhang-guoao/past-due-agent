(ns open.atlas.studio.workspace-token-test
  "The workspace-token ledger (8.4 P1b ruling ②) on its own.

   `builder-mcp-test` covers what the ledger means at the endpoint; this covers
   the ledger itself — what it stores, what it never stores, and what it does
   when asked about a key it does not know."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.studio.workspace-token :as wt]))

(def ^:dynamic *home* nil)
(def ^:private pepper "test-pepper")

(defn- with-home [f]
  (let [home (str (System/getProperty "java.io.tmpdir") "/oa-wtl-" (random-uuid))]
    (try (binding [*home* home] (f))
         (finally (doseq [x (reverse (file-seq (java.io.File. home)))] (.delete ^java.io.File x))))))

(use-fixtures :each with-home)

(def ^:private t0 "2026-08-28T00:00:00Z")
(def ^:private t-expiry "2026-09-28T00:00:00Z")

;; `label` has a default because it is REQUIRED (8.4.9 ③ / 8.4.11) — every test
;; that is not about naming would otherwise have to repeat one, and the refusal
;; when it is missing is its own test below.
(defn- issue! [& {:keys [scope workspace label description issued-at expires-at]
                  :or {scope #{:read} workspace "demo" label "oma-agent-1"
                       issued-at t0 expires-at t-expiry}}]
  (wt/issue! *home* pepper {:workspace workspace :scope scope
                            :issued-by {:issuer "local" :subject "u1"}
                            :issued-at issued-at
                            :expires-at expires-at
                            :label label
                            :description description}))

(defn- verify [token now] (wt/verify *home* pepper token now))

(deftest the-secret-appears-exactly-once
  "S5.2/S5.9: it is in the issue response and afterwards unrecoverable from
   anything this instance keeps. A caller who loses it issues another and
   revokes this one — that is the intended cost, not a gap."
  (let [{:keys [token record]} (issue!)]
    (is (str/starts-with? token wt/token-prefix))
    (is (not (contains? record :digest)))
    (testing "the ledger's own view carries no digest and no secret"
      (let [rs (wt/records *home*)]
        (is (= 1 (count rs)))
        (is (not (contains? (first rs) :digest)))
        (is (not (str/includes? (pr-str rs) token)))))
    (testing "but it does carry who issued it and when — that is the whole point"
      (let [r (first (wt/records *home*))]
        (is (= {:issuer "local" :subject "u1"} (:issued-by r)))
        (is (= t0 (:issued-at r)))
        (is (= t-expiry (:expires-at r)))
        (is (nil? (:revoked-at r)))))))

(deftest verify-answers-only-yes-or-nothing
  (let [{:keys [token record]} (issue! :scope #{:read :write})]
    (testing "a good token resolves to its record, scope and all"
      (let [v (verify token t0)]
        (is (= (:token-id record) (:token-id v)))
        (is (= #{:read :write} (:scope v)))
        (is (= "demo" (:workspace v)))
        (is (not (contains? v :digest)))))
    (testing "every failure is the same nil: revoked, unknown and wrong-prefix
              are not told apart, because 「那把钥匙曾经存在」 is a fact the
              caller has no other way to learn"
      (is (nil? (verify "oat_v1.data-plane" t0)))
      (is (nil? (verify (str wt/token-prefix "made-up") t0)))
      (is (nil? (verify nil t0)))
      (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T01:00:00Z"})
      (is (nil? (verify token t0))))))

(deftest revoking-is-idempotent-and-scoped
  (let [{:keys [record]} (issue!)]
    (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T01:00:00Z"})
    (testing "revoking again is not an error — the caller's intent is already
              true, and failing would send them looking for a second key"
      (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T02:00:00Z"})
      (is (= "2026-08-28T01:00:00Z" (:revoked-at (first (wt/records *home*))))
          "and the FIRST revocation time stands: that is when it stopped working"))
    (testing "an id this ledger never issued is refused, not silently accepted"
      (is (thrown? clojure.lang.ExceptionInfo
                   (wt/revoke! *home* {:token-id "wt-nope" :revoked-at "x"}))))))

(deftest scope-is-read-or-read-write-and-nothing-else
  (testing "there is no write-only scope: a caller that may overwrite must be
            able to read what it is about to overwrite"
    (is (thrown? clojure.lang.ExceptionInfo (issue! :scope #{:write})))
    (is (thrown? clojure.lang.ExceptionInfo (issue! :scope #{})))
    (is (thrown? clojure.lang.ExceptionInfo (issue! :scope #{:read :admin}))))
  (testing "and the refusal names the two that exist (0.2 acceptance defect 10)"
    (let [d (try (issue! :scope :read) nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :workspace-token-scope-invalid (:code d)))
      (is (= [#{:read} #{:read :write}] (:expected d))))))

(deftest two-tokens-are-two-tokens
  "Each issue mints fresh material; nothing about one key can be derived from
   another, and revoking one leaves the rest working."
  (let [a (issue!)
        b (issue! :scope #{:read :write})]
    (is (not= (:token a) (:token b)))
    (is (not= (:token-id (:record a)) (:token-id (:record b))))
    (wt/revoke! *home* {:token-id (:token-id (:record a)) :revoked-at "2026-08-28T01:00:00Z"})
    (is (nil? (verify (:token a) t0)))
    (is (some? (verify (:token b) t0)))))

;; ---------------------------------------------------------------------------
;; Expiry
;; ---------------------------------------------------------------------------

(deftest a-key-dies-on-its-own
  "The point of the field: a token that authorises model writes stops working
   without anyone remembering to revoke it."
  (let [{:keys [token]} (issue! :expires-at "2026-08-29T00:00:00Z")]
    (is (some? (verify token "2026-08-28T23:59:59Z")))
    (testing "at exactly the expiry instant it is already over — an expiry that
              still worked on its own timestamp is an off-by-one nobody tests"
      (is (nil? (verify token "2026-08-29T00:00:00Z"))))
    (is (nil? (verify token "2026-08-29T00:00:01Z")))))

(deftest every-dead-key-verifies-to-exactly-nil
  "Expired, revoked, never-issued and wrong-door: four different reasons, one
   indistinguishable answer. «That key existed once» is a fact about this
   instance a caller has no other way to learn."
  (let [expired (:token (issue! :expires-at "2026-08-29T00:00:00Z"))
        live    (issue! :expires-at "2027-01-01T00:00:00Z")
        now     "2026-08-30T00:00:00Z"]
    (wt/revoke! *home* {:token-id (:token-id (:record live)) :revoked-at now})
    (let [answers [(verify expired now)
                   (verify (:token live) now)
                   (verify (str wt/token-prefix "never-issued") now)
                   (verify "oat_v1.wrong-door" now)]]
      (is (= [nil nil nil nil] answers)
          "four different reasons, one indistinguishable answer"))))

(deftest the-ledger-tells-the-three-apart-because-a-reader-may-know
  "verify says nothing; the governance view says which. Someone reading the
   panel has already authenticated, and «did we retire this or did it run
   out?» is the question that says whether the process is working."
  (let [live    (issue! :expires-at "2027-01-01T00:00:00Z")
        expired (issue! :expires-at "2026-08-29T00:00:00Z")
        retired (issue! :expires-at "2027-01-01T00:00:00Z")
        now     "2026-08-30T00:00:00Z"]
    (wt/revoke! *home* {:token-id (:token-id (:record retired)) :revoked-at now})
    (let [by-id (into {} (map (juxt :token-id identity) (wt/records *home* now)))]
      (is (= :active  (:status (by-id (:token-id (:record live))))))
      (is (= :expired (:status (by-id (:token-id (:record expired))))))
      (is (= :revoked (:status (by-id (:token-id (:record retired))))
             ) "revocation wins over expiry: it is the deliberate act")
      (testing "and the status view still carries no digest and no secret"
        (is (not (str/includes? (pr-str (wt/records *home* now)) (:token live))))
        (is (every? #(not (contains? % :digest)) (wt/records *home* now)))))))

(deftest a-revoked-key-that-also-ran-out-reads-as-revoked
  (let [{:keys [record]} (issue! :expires-at "2026-08-29T00:00:00Z")]
    (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-28T01:00:00Z"})
    (is (= :revoked (wt/status (first (wt/records *home*)) "2027-01-01T00:00:00Z")))))

(deftest expiry-is-required-and-must-be-an-instant
  "No default lifetime in this ns: a lifetime invented by the code is a policy
   decision hidden in an implementation, and the caller that omitted it would
   never learn it had accepted one."
  (is (thrown? clojure.lang.ExceptionInfo (issue! :expires-at nil)))
  (is (thrown? clojure.lang.ExceptionInfo (issue! :expires-at "next tuesday")))
  (is (thrown? clojure.lang.ExceptionInfo (issue! :expires-at 90)))
  (testing "and it may not precede issue: a key born dead is a mistake, not a
            configuration"
    (is (thrown? clojure.lang.ExceptionInfo
                 (issue! :issued-at t0 :expires-at "2026-08-27T00:00:00Z")))))

(deftest a-record-written-before-expiry-existed-is-not-eternal
  "Forward compatibility the safe way round. Treating a missing `:expires-at`
   as «no expiry» would leave untouched exactly the keys this change exists to
   kill."
  (let [{:keys [token]} (issue!)]
    ;; Rewrite the ledger the way an older build left it.
    (let [path (str *home* "/workspace-tokens.edn")
          stored (clojure.edn/read-string (slurp path))]
      (spit path (pr-str (mapv #(dissoc % :expires-at) stored))))
    (is (= :expired (:status (first (wt/records *home* t0)))))
    (is (nil? (verify token t0)))))

;; ---------------------------------------------------------------------------
;; Concurrency: the ledger is one file two writers rewrite whole
;; ---------------------------------------------------------------------------

(deftest a-revocation-is-never-lost-to-a-concurrent-issue
  "The reason `issue!` and `revoke!` serialise. Unserialised, the losing
   read-modify-write lands the vector the winner had already superseded — and
   when the loser is the revocation, a key a human retired goes on working
   while its owner has been told it is dead. That is a security event, not a
   lost update, and nothing downstream would report it."
  (let [{:keys [token record]} (issue! :label "seeded")
        seeded-id (:token-id record)
        revoked-at "2026-08-28T01:00:00Z"
        n-issues 12
        n-revokes 4
        tasks (concat (for [i (range n-issues)]
                        #(issue! :label (str "racer-" i)))
                      (repeat n-revokes
                              #(wt/revoke! *home* {:token-id seeded-id
                                                   :revoked-at revoked-at})))
        ;; Started as one batch so they actually overlap; a deadline rather
        ;; than an open `deref` so a regression hangs the test for two seconds
        ;; instead of forever.
        futures (mapv #(future (%)) (shuffle tasks))]
    (doseq [f futures]
      (is (not= ::timeout (deref f 2000 ::timeout)) "the writers made progress"))
    (let [rs (wt/records *home*)
          seeded (first (filter #(= seeded-id (:token-id %)) rs))]
      (is (= revoked-at (:revoked-at seeded))
          "the revocation survived every concurrent issue")
      (is (= (inc n-issues) (count rs))
          "and no issue was overwritten either — one record per successful call")
      (is (= (inc n-issues) (count (set (map :token-id rs)))))
      (is (nil? (verify token "2026-08-28T02:00:00Z"))
          "and the revoked key is refused at the door"))))

(deftest a-leftover-temp-file-is-not-a-ledger
  "A crash between the store's temp write and its atomic move leaves a
   `.tmp-` sibling behind. It is not the ledger and must not be read as one —
   nor may its presence stop the next write from landing."
  (let [{:keys [token]} (issue! :label "before-the-crash")
        leftover (java.io.File. (str *home* "/.tmp-workspace-tokens.edn"))]
    (spit leftover "[{:token-id \"wt-half-written\"")
    (is (= 1 (count (wt/records *home*))) "the leftover is not a record")
    (is (some? (verify token t0)))
    (issue! :label "after-the-crash")
    (is (= 2 (count (wt/records *home*))))
    (is (= #{"before-the-crash" "after-the-crash"}
           (set (map :label (wt/records *home*)))))))

;; ---------------------------------------------------------------------------
;; The two human fields (8.4.9 ③ / 8.4.11)
;; ---------------------------------------------------------------------------

(defn- code-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest a-key-must-be-named
  "8.4.9 ③ gives one agent one key and records which agent in the label. A blank
   written into the ledger would satisfy the schema and defeat the ruling: the
   pool's audit keeps only `token-id`, so an unnamed key is a change nobody can
   attribute."
  (is (= :workspace-token-label-invalid (code-of #(issue! :label nil))))
  (is (= :workspace-token-label-invalid (code-of #(issue! :label ""))))
  (is (= :workspace-token-label-invalid (code-of #(issue! :label "   "))))
  (is (= :workspace-token-label-invalid (code-of #(issue! :label :not-text))))
  (testing "and a refused issue writes nothing — no half-key on the shelf"
    (is (empty? (wt/records *home*)))))

(deftest a-name-is-trimmed-and-bounded-and-over-long-is-refused
  "Refused rather than truncated: a silently shortened name no longer matches
   the agent it was recording, and the reader cannot tell that happened."
  (is (= "oma-agent-7" (:label (:record (issue! :label "  oma-agent-7  ")))))
  (let [at-limit (apply str (repeat wt/max-label-chars \a))]
    (is (= at-limit (:label (:record (issue! :label at-limit))))))
  (let [over (apply str (repeat (inc wt/max-label-chars) \a))]
    (is (= :workspace-token-label-invalid (code-of #(issue! :label over))))
    (testing "the refusal reports the length, never the value — a diagnostic
              that echoes what was typed is a mirror (finding-v1 §5)"
      (let [data (try (issue! :label over) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= (inc wt/max-label-chars) (:got data)))
        (is (= wt/max-label-chars (:expected data)))
        (is (not (str/includes? (pr-str data) over)))))))

(deftest a-description-is-optional-and-absent-means-absent
  "Blank collapses to absent rather than to \"\": a key present but empty makes
   the reader work out whether somebody cleared it or the writer was buggy."
  (is (not (contains? (:record (issue!)) :description)))
  (is (not (contains? (:record (issue! :description "")) :description)))
  (is (not (contains? (:record (issue! :description "  ")) :description)))
  (testing "and the ledger view reads back the same way"
    (is (every? #(not (contains? % :description)) (wt/records *home*)))))

(deftest a-description-is-stored-trimmed-and-bounded
  (let [{:keys [record]} (issue! :description "  the model-maintenance agent for the sales pool  ")]
    (is (= "the model-maintenance agent for the sales pool" (:description record))))
  (is (= :workspace-token-description-invalid (code-of #(issue! :description 42))))
  (let [over (apply str (repeat (inc wt/max-description-chars) \a))]
    (is (= :workspace-token-description-invalid (code-of #(issue! :description over))))))

(deftest the-two-fields-are-clues-and-nothing-reads-them
  "Verification, revocation and the audit chain all run off `token-id`. Naming a
   key differently must not change what it opens."
  (let [{:keys [token record]} (issue! :label "agent-a" :description "why it exists"
                                       :scope #{:read :write})]
    (is (= #{:read :write} (:scope (verify token t0))))
    (is (= (:token-id record) (:token-id (verify token t0))))
    (testing "revocation is by id, not by name"
      (wt/revoke! *home* {:token-id (:token-id record) :revoked-at "2026-08-29T00:00:00Z"})
      (is (nil? (verify token "2026-08-29T00:00:00Z"))))))
