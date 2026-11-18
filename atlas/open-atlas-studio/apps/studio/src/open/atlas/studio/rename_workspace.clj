(ns open.atlas.studio.rename-workspace
  "One-shot, headless **workspace rename** for a local Studio home
   (E4 task sheet §1b.3, owner ruling 2026-09-05).

   ```
   cd apps/studio
   clojure -M:rename-workspace --from demo --to jie.gao@tdsynnex.com \\
                               --owner-email jie.gao@tdsynnex.com --owner-name \"Jie Gao\"
   ```

   `--plan` prints what would change and writes nothing. Anything else runs the
   whole thing: one Datahike transaction over every stored occurrence of the
   name, then the shelf directory moves, then — when `--owner-email` is given —
   a local account owning the renamed workspace.

   The **password is read from standard input**, never from argv, as in
   `identity.bootstrap`. An empty line means «create the account with no
   password»: on this machine the passwordless Builder session gets in through
   the `{:issuer \"studio\" :subject \"local-builder\"}` member, which the
   rename keeps.

   **Studio must be stopped.** The pool is a single-writer store, and this tool
   opens it for writing; it also runs the pool migrations on open, exactly as a
   Studio start would."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]))

(defn- studio-home []
  (or (System/getenv "OPEN_ATLAS_STUDIO_HOME")
      (str (io/file (System/getProperty "user.home") ".open-atlas" "studio"))))

(defn shelf-parents
  "Every directory under a Studio home that holds a `<workspace>` segment.

   Two roots, not one: the Builder's own shelf (`<home>/artifacts`, where
   `cut-version!` writes) and the embedded runtime instance's own layout
   (`<home>/runtime/{artifacts,receipts,events,pointers}` — see
   `runtime.graph.impl.store`'s layout table). Missing directories are skipped
   by the rename itself, so listing all of them costs nothing."
  [home]
  [(io/file home "artifacts")
   (io/file home "runtime" "artifacts")
   (io/file home "runtime" "receipts")
   (io/file home "runtime" "events")
   (io/file home "runtime" "pointers")])

(defn- parse-args [args]
  (loop [remaining args result {}]
    (if (empty? remaining)
      result
      (let [[flag & more] remaining]
        (cond
          (= "--plan" flag) (recur more (assoc result :plan true))
          (not (str/starts-with? (str flag) "--")) {:error (str "Invalid arguments near " flag)}
          (empty? more) {:error (str "Missing value for " flag)}
          :else (recur (rest more) (assoc result (keyword (subs flag 2)) (first more))))))))

(defn- open! [home]
  (-> (pool/open-pool! (pool/config {:backend :file :path (str (io/file home "pool"))}))
      graph/install! version/install! identity/install!))

(defn inventory
  "The before/after listing this tool prints: everywhere the name is visible."
  [conn home]
  (let [db (pool/db conn)]
    {:workspaces (mapv :workspace/name (graph/workspaces db))
     :graphs (mapv :graph/qualified-name (graph/graphs db))
     :versions (mapv :version/id (version/versions db))
     :objects-by-workspace (frequencies (map :pool/workspace (pool/objects db)))
     :users (mapv (juxt :email :default-workspace) (identity/users db))
     :members (into {} (for [w (graph/workspaces db)]
                         [(:workspace/name w)
                          (mapv (juxt :principal :role) (:workspace/members w))]))
     :shelves (into {} (for [^java.io.File p (shelf-parents home)
                             :when (.isDirectory p)]
                         [(.getPath p) (vec (sort (.list p)))]))}))

(defn retarget-files!
  "The two plain EDN files under a Studio home that name a workspace but are
   not in the store, rewritten in place. → what each one did.

   - **`workspace-tokens.edn`** — a workspace token's record carries the
     workspace it authorizes writes in. The stored `:digest` is
     `HMAC(pepper, secret)` and does not cover the record, so retargeting the
     name keeps every issued key working; leaving it would point live keys at
     a workspace that no longer exists — an MCP client that silently stops
     being able to write, with no refusal that says why.
   - **`session-preferences.edn`** — `:last-workspace` per principal, i.e.
     where the Builder reopens. Cosmetic, but a stale value sends the owner to
     a workspace that is gone.

   Not touched here, deliberately: `credentials.edn` and `.env` (keyed by
   source, not workspace), `history.edn` (query history — history), and the
   `logs/` and `runtime/obs/` trees (records of things that happened under the
   old name)."
  [home old new]
  (into {}
        (for [[f xf] [[(io/file home "workspace-tokens.edn")
                       (fn [records]
                         (mapv #(if (= old (:workspace %)) (assoc % :workspace new) %)
                               records))]
                      [(io/file home "session-preferences.edn")
                       (fn [prefs]
                         (update prefs :last-workspace
                                 #(into {} (for [[k v] %] [k (if (= old v) new v)]))))]]
              :when (.isFile ^java.io.File f)]
          [(.getName ^java.io.File f)
           (let [before (edn/read-string (slurp f))
                 after (xf before)]
             (when (not= before after) (spit f (pr-str after)))
             (if (= before after) :unchanged :rewritten))])))

(defn ensure-owner!
  "Make `email` the owner of the renamed workspace.

   Two cases, because the owner's own store already held an account for the
   address the workspace is being renamed to (measured on a copy of the real
   pool, 2026-09-05 — the task sheet assumed there was none):

   - **the account exists** → it is only promoted to `:owner` of this
     workspace. Its default workspace already moved with the rename, which
     rewrites `:identity.user/default-workspace`. No password is touched: this
     tool never overwrites an existing credential.
   - **it does not** → a local account is created owning this workspace
     instead of a personal one (`:into-workspace`). A blank password creates
     the account **without** one, to be set by the owner later.

   Every other member of the workspace is kept either way — including the
   `{:issuer \"studio\" :subject \"local-builder\"}` seed member where one
   exists, which is how a passwordless local Builder session gets in."
  [conn workspace email name password]
  (let [db (pool/db conn)]
    (if-let [user (identity/user-by-email db email)]
      (assoc (graph/add-workspace-member!
              conn workspace {:principal (:principal (identity/public-user user))
                              :role :owner
                              :attributes {:email email :name (or name (:name user))}})
             :account :existing)
      (assoc (identity/create-local-user!
              conn {:email email :name (or name email) :password password
                    :sso (str/blank? password) :role :admin :into-workspace workspace})
             :account :created))))

(defn run-rename!
  "→ `{:status :ok :before … :after … :result …}` or `{:error {…}}`."
  [{:keys [from to plan owner-email owner-name password]}]
  (let [home (studio-home)]
    (if (or (str/blank? from) (str/blank? to))
      {:error {:code :invalid-arguments :message "--from and --to are both required."}}
      (let [conn (open! home)]
        (try
          (let [before (inventory conn home)]
            (cond
              plan
              {:status :ok :before before
               :result (graph/rename-workspace-plan (pool/db conn) from to
                                                    (shelf-parents home))}

              :else
              (let [result (graph/rename-workspace! conn from to (shelf-parents home))]
                (if (some #(= :error (:severity %)) (:findings result))
                  {:error {:code :rename-refused
                           :message "Rename refused; nothing was written."}
                   :result result}
                  (let [account (when-not (str/blank? owner-email)
                                  (ensure-owner! conn to owner-email owner-name password))
                        files (retarget-files! home from to)]
                    {:status :ok :before before :result result :account account
                     :files files :after (inventory conn home)})))))
          (finally (pool/close-pool! conn)))))))

(defn -main [& args]
  (let [parsed (parse-args args)
        password (when-not (:plan parsed) (some-> (read-line) str str/trim))
        result (if-let [message (:error parsed)]
                 {:error {:code :invalid-arguments :message message}}
                 (run-rename! (assoc parsed :password password)))]
    (if-let [error (:error result)]
      (do (binding [*out* *err*]
            (println "ERROR:" (:message error))
            (doseq [f (:findings (:result result))] (println " -" (pr-str f))))
          (System/exit 1))
      (do (println "BEFORE:") (pprint/pprint (:before result))
          (println "RESULT:") (pprint/pprint (:result result))
          (when (:account result) (println "ACCOUNT:") (pprint/pprint (:account result)))
          (when (:files result) (println "FILES:") (pprint/pprint (:files result)))
          (when (:after result) (println "AFTER:") (pprint/pprint (:after result)))
          (System/exit 0)))))
