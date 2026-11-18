(ns open.atlas.studio.graph.impl.rename
  "**Renaming a workspace**: one transaction over every place this Studio's
   store persists a workspace name, then the shelf directories that carry the
   name as a path segment (E4 task sheet §1b.3, owner ruling 2026-09-05).

   The platform had no such action before. `rename-graph!` exists, and D3.10
   deliberately refuses to rename a *graph* — its qualified name lives in other
   workspaces' reference lists, in running tokens and in deployment pointers,
   two of which are immutable by definition. A **workspace** rename is a
   narrower thing: with D3.17 (a workspace is a closed world) nobody outside
   this Studio can be holding a reference to it, so the whole of «everywhere
   the name is written down» is reachable from one connection plus a handful of
   directories under one home.

   ## What it rewrites, and what it deliberately does not

   | site | rewritten? | why |
   |---|---|---|
   | `:graph.workspace/name` | yes | the registration itself |
   | `:graph.workspace/default-graph` | yes | a qualified name, first segment |
   | `:graph.graph/workspace` / `:graph.graph/qualified-name` | yes | D3.10's two segments |
   | `:graph.grant/grantee` / `/graph` / `/key` | yes | grantee is a workspace name; graph is a qualified name |
   | `:pool.object/workspace` | yes | D3.22's stored ownership column |
   | `:studio.version/graph` / `/id` | yes | the version registry addresses graphs by qualified name |
   | `:identity.user/default-workspace` | yes | where a user lands at login |
   | `:pool.mutation/*` (the change ledger) | **no** | history: the write really did happen under the old name |
   | `:graph.claim/*` / `:graph.trespass/*` / `:graph.unclaimed/*` | **no** | D3.22 deleted these registers; leftover datoms in an old pool are read by nothing |
   | `manifest.edn` inside a cut version | **no** | see «The manifests stay» below |

   The three attribute families this ns writes that belong to other modules —
   `:pool.object/*`, `:studio.version/*`, `:identity.user/*` — are read and
   written as raw datoms rather than through those modules' interfaces. It is
   one store (`studio-state` has a single `:conn`), the sheet requires **one
   transaction**, and inverting the dependency direction of three modules for a
   one-shot command would be the larger debt. `pool/impl/migrate` set the same
   precedent in the other direction when its one-shot D3.22 pass read the
   retired `:graph.claim/*` datoms (D1.8).

   ## The manifests stay

   `artifact-digest` covers `content.edn`, `manifest.edn` and `report.edn`
   together (artifact-v1 §7.3), and the digest of every cut version is stored
   in the immutable version registry (`:studio.version/artifact-digest`) and
   checked again before staging, loading and activating. Rewriting
   `manifest.edn`'s `:workspace` would therefore invalidate every artifact this
   Studio has ever cut — the files would no longer be the group that was
   sealed. So the version directories are **moved, not edited**, and a
   `renamed-from.edn` record is written beside the moved tree saying what the
   `:workspace` field inside those manifests refers to. A stale field with a
   note beside it is honest; a rewritten one that fails its own digest check is
   not.

   ## The name must still be a portable path segment

   artifact-v1 §8 confines a shelf path segment to `[A-Za-z0-9][A-Za-z0-9._@-]*`
   (the owner admitted `@` on 2026-09-05, so an email-named workspace is a legal
   shelf directory). A name outside that whitelist cannot have a version cut for
   it, and its existing shelf, once moved, is a path `common/artifact` will
   refuse to read. This ns **warns** rather than
   refuses: it is not the place to overrule a name the owner chose, but a
   silent move into an unreadable path would be exactly the silent degradation
   CLAUDE.md forbids. The warning names the code the shelf will refuse with."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.studio.graph.impl.finding :as f]
            [open.atlas.studio.graph.impl.store :as store]
            [open.atlas.studio.pool :as pool])
  (:import [java.io File]))

(def ^:const ruling
  "The ruling the audit records name."
  "D3.22")

(defn- name-ok?
  "The same rule `registry/name-ok?` applies: only `/` is forbidden, because it
   is the separator of the qualified graph name (D3.10)."
  [s]
  (and (string? s) (seq s) (not (str/includes? s "/"))))

(defn- requalify
  "`\"old/graph\"` → `\"new/graph\"`, or nil when the qualified name is not
   under `old`. Splits on the FIRST `/` — a workspace name may not contain one,
   a graph name may not either, and D3.10 says exactly two segments."
  [old new qn]
  (when (string? qn)
    (let [i (.indexOf ^String qn "/")]
      (when (and (pos? i) (= old (subs qn 0 i)))
        (str new (subs qn i))))))

;; ---------------------------------------------------------------------------
;; The plan
;; ---------------------------------------------------------------------------

(defn- graph-changes [db old new]
  (for [g (store/all-graphs db)
        :when (= old (:graph/workspace g))
        :let [qn (:graph/qualified-name g)]]
    {:from qn
     :to   (requalify old new qn)
     :tx   {:db/id [:graph.graph/qualified-name qn]
            :graph.graph/qualified-name (requalify old new qn)
            :graph.graph/workspace new}}))

(defn- grant-changes [db old new]
  (for [g (store/all-grants db)
        :let [grantee (:grant/grantee g)
              graph   (:grant/graph g)
              ;; The grantee is a workspace name or `:public` (D3.14); only the
              ;; former can be this workspace.
              grantee* (if (= old grantee) new grantee)
              graph*   (or (requalify old new graph) graph)]
        :when (or (not= grantee grantee*) (not= graph graph*))]
    {:from [grantee graph] :to [grantee* graph*]
     :tx [[:db/retractEntity [:graph.grant/key (store/grant-key grantee graph)]]
          (store/grant-tx {:grant/grantee grantee* :grant/graph graph*
                           :grant/ceiling (:grant/ceiling g)})]}))

(defn- raw-value-changes
  "Entities whose `attr` holds **exactly** `old`, rewritten to `new`, one tx map
   each. Matching on the value rather than rewriting every row is the whole
   point: `:pool.object/workspace` is on every object in the Studio, and only
   this workspace's are being renamed."
  [db attr old new]
  (vec (for [[e] (d/q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr old)]
         {:db/id e attr new})))

(defn plan
  "What a rename would do, read-only.

   → `{:findings [] :changes {…} :tx […] :shelf […] :objects n}`, or
   `{:findings [error…]}`. `:shelf` is the list of `<parent>/<old>` →
   `<parent>/<new>` directory moves the caller must perform **after** the
   transaction commits."
  [db old new shelf-parents]
  (let [existing (store/find-workspace db old)
        taken    (store/find-workspace db new)]
    (cond
      (not (name-ok? new))
      {:findings [(f/make :workspace.registry/malformed :error [:workspace :name]
                          (str "workspace name must be a non-empty string without '/' — "
                               "'/' is the separator of the qualified graph name")
                          :got new :refs ["D3.10"])]}

      (nil? existing)
      {:findings [(f/make :workspace.registry/unknown :error [:workspace old]
                          "no workspace is registered under this name"
                          :refs ["D3.3"])]}

      taken
      {:findings [(f/make :workspace.registry/name-taken :error [:workspace new]
                          "a workspace is already registered under the new name"
                          :refs ["D3.3"])]}

      :else
      (let [graphs   (vec (graph-changes db old new))
            grants   (vec (grant-changes db old new))
            objects  (raw-value-changes db :pool.object/workspace old new)
            versions (vec (for [{:keys [e graph id]}
                                (map (fn [[e g i]] {:e e :graph g :id i})
                                     (d/q '[:find ?e ?g ?i
                                            :where [?e :studio.version/graph ?g]
                                                   [?e :studio.version/id ?i]] db))
                                :let [g* (requalify old new graph)]
                                :when g*]
                            {:from id
                             ;; The registry id is `<qualified-name>@vN`; it is a
                             ;; unique identity, so it moves with the name it is
                             ;; built from or the next cut collides with it.
                             :to (str g* (subs id (count graph)))
                             :tx {:db/id e :studio.version/graph g*
                                  :studio.version/id (str g* (subs id (count graph)))}}))
            users    (raw-value-changes db :identity.user/default-workspace old new)
            default-graph (:workspace/default-graph existing)
            ws-tx    (cond-> {:db/id [:graph.workspace/name old]
                              :graph.workspace/name new}
                       (requalify old new default-graph)
                       (assoc :graph.workspace/default-graph
                              (requalify old new default-graph)))
            audit    (pool/system-mutation-tx
                      db ruling
                      (vec (for [o (pool/objects db old)]
                             ;; The workspace column changed and the dialect
                             ;; content did not — the same `:update` shape the
                             ;; D3.22 stamp pass records, for the same reason.
                             {:action :update :type (:pool/type o) :id (:pool/id o)
                              :revision-before (:pool/revision o)
                              :revision-after (:pool/revision o)})))]
        {:findings
         ;; One implementation of "is this a legal shelf segment" (D1.8): the
         ;; module that REFUSES on it also answers the question here, so a
         ;; warning and a refusal can never disagree.
         (vec (when-not (artifact/portable-segment? new)
                [(f/make :workspace.registry/rename-unportable :warning [:workspace :name]
                         (str "this name is not a portable path segment (artifact-v1 §8: "
                              "[A-Za-z0-9][A-Za-z0-9._@-]*), so the artifact shelf under it "
                              "will be refused with :artifact.store/path-escape — existing "
                              "cut versions become unreadable and no new version can be cut")
                         :got new :expected "[A-Za-z0-9][A-Za-z0-9._@-]*"
                         :refs ["D4.8" "D3.10"])]))
         :changes {:workspace {:from old :to new}
                   :default-graph (when (requalify old new default-graph)
                                    {:from default-graph
                                     :to (requalify old new default-graph)})
                   :graphs (mapv #(select-keys % [:from :to]) graphs)
                   :grants (mapv #(select-keys % [:from :to]) grants)
                   :versions (mapv #(select-keys % [:from :to]) versions)
                   :objects (count objects)
                   :users (count users)}
         :tx (into [ws-tx]
                   (concat (map :tx graphs)
                           (mapcat :tx grants)
                           objects users
                           (pool/semantic-workspace-rename-tx db old new)
                           (map :tx versions)
                           audit))
         :shelf (vec (for [^File parent shelf-parents
                           :let [from (io/file parent old)]
                           :when (.isDirectory from)]
                       {:from from :to (io/file parent new)}))}))))

;; ---------------------------------------------------------------------------
;; Running it
;; ---------------------------------------------------------------------------

(defn- renamed-from-record [from to old new]
  {:renamed-from old
   :renamed-to new
   :moved-from (.getPath ^File from)
   :ruling ruling
   ;; artifact-v1 §7.3: `artifact-digest` covers `manifest.edn`, and the cut
   ;; digest is recorded immutably in the version registry. So the `:workspace`
   ;; field inside every manifest under here still reads `old`, on purpose.
   :note (str "manifest.edn files under this tree still say :workspace \"" old
              "\"; they are NOT rewritten because artifact-digest covers them "
              "(artifact-v1 §7.3) and the recorded digest would no longer match")})

(defn- move-dir!
  "→ nil on success, a finding on failure. `Files/move` rather than
   `File.renameTo`, which reports failure as a bare false."
  [{:keys [^File from ^File to]}]
  (try
    (when (.exists to)
      (throw (ex-info "target exists" {})))
    (io/make-parents to)
    (java.nio.file.Files/move (.toPath from) (.toPath to)
                              (into-array java.nio.file.CopyOption []))
    nil
    (catch Exception e
      (f/make :workspace.registry/rename-shelf :error [:shelf (.getPath from)]
              (str "could not move the shelf directory: " (.getMessage e))
              :refs ["D4.8"]))))

(defn rename-workspace!
  "Rename `old` to `new`, everywhere this Studio writes the name down.

   `shelf-parents` are directories that hold a `<workspace>` subdirectory —
   `<home>/artifacts`, and the runtime root's `artifacts` / `receipts` /
   `events` / `pointers`.

   → `{:renamed? true :changes {…} :moved […] :findings [warning…]}`, or
   `{:renamed? false :findings [error…]}` with **nothing written**.

   **Idempotent**: renaming to a name that is already there, from a name that
   is not, is a no-op success — re-running after a partial disk failure is
   therefore safe.

   One `d/transact`, then the directory moves. A move that fails un-does the
   moves already made **and transacts the rename back**, so the store and the
   disk never disagree; that compensation is why the moves come last."
  [conn old new shelf-parents]
  (locking conn
    (let [db (d/db conn)]
      (if (and (nil? (store/find-workspace db old)) (store/find-workspace db new))
        {:renamed? false :already? true :changes {} :moved [] :findings []}
        (let [{:keys [findings changes tx shelf]} (plan db old new shelf-parents)]
          (if (some #(= :error (:severity %)) findings)
            {:renamed? false :findings (vec findings)}
            (do
              (d/transact conn tx)
              (loop [[m & more] shelf moved []]
                (if-not m
                  {:renamed? true :changes changes :moved moved :findings (vec findings)}
                  (if-let [bad (move-dir! m)]
                    (do
                      (doseq [{:keys [from to]} moved] (move-dir! {:from to :to from}))
                      (d/transact conn (:tx (plan (d/db conn) new old shelf-parents)))
                      {:renamed? false :findings [bad]})
                    (do (spit (io/file (:to m) "renamed-from.edn")
                              (pr-str (renamed-from-record (:from m) (:to m) old new)))
                        (recur more (conj moved {:from (.getPath ^File (:from m))
                                                 :to (.getPath ^File (:to m))})))))))))))))
