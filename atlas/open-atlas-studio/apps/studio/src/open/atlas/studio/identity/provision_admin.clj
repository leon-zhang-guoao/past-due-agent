(ns open.atlas.studio.identity.provision-admin
  "Offline provisioning of an additional Studio administrator.

   This is deliberately separate from founding bootstrap: it uses the normal
   managed-user transaction and remains available after setup has closed. The
   password is read from stdin and is never accepted as an argv value."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]))

(defn- studio-home []
  (or (System/getenv "OPEN_ATLAS_STUDIO_HOME")
      (str (io/file (or (System/getenv "OPEN_ATLAS_HOME")
                        (str (io/file (System/getProperty "user.home") ".open-atlas")))
                    "studio"))))

(defn- parse-args [args]
  (loop [remaining args result {}]
    (if (empty? remaining)
      result
      (let [[flag value & more] remaining]
        (if (or (nil? value) (not (str/starts-with? flag "--")))
          {:error (str "Invalid arguments near " flag)}
          (recur more (assoc result (keyword (subs flag 2)) value)))))))

(defn with-studio-identity [f]
  (let [cfg (pool/config {:backend :file :path (str (io/file (studio-home) "pool"))})
        conn (-> (pool/open-pool! cfg) graph/install! version/install! identity/install!)]
    (try
      (f conn)
      (finally (pool/close-pool! conn)))))

(defn check! [email]
  (with-studio-identity
    (fn [conn]
      (cond
        (identity/needs-setup? (pool/db conn))
        {:error {:code :setup-required
                 :message "Studio has no founding administrator; run bootstrap-admin first."}}

        (identity/user-by-email (pool/db conn) email)
        {:error {:code :email-taken
                 :message "A Studio user with this email already exists."}}

        :else {:status :ok}))))

(defn provision! [{:keys [email name password]}]
  (with-studio-identity
    (fn [conn]
      (if (identity/needs-setup? (pool/db conn))
        {:error {:code :setup-required
                 :message "Studio has no founding administrator; run bootstrap-admin first."}}
        (identity/create-local-user! conn {:email email :name name
                                           :password password :role :admin})))))

(defn promote! [email]
  (with-studio-identity #(identity/promote-admin! % email)))

(defn reset-password! [email new-password]
  (with-studio-identity
    (fn [conn]
      (if-let [user (identity/user-by-email (pool/db conn) email)]
        (identity/reset-password! conn (:id user) new-password)
        {:error {:code :user-not-found
                 :message "No Studio user with this email exists."}}))))

(defn -main [& args]
  (let [parsed (parse-args args)
        mode (:mode parsed)
        result (cond
                 (:error parsed) {:error {:code :invalid-arguments :message (:error parsed)}}
                 (= mode "check") (check! (:email parsed))
                 (= mode "create") (provision! (assoc parsed :password (read-line)))
                 (= mode "promote") (promote! (:email parsed))
                 (= mode "reset-password") (reset-password! (:email parsed) (read-line))
                 :else {:error {:code :invalid-arguments
                                :message "Mode must be check, create, promote, or reset-password."}})]
    (if-let [error (:error result)]
      (do (binding [*out* *err*] (println "ERROR:" (:message error)))
          (System/exit 1))
      (do (println (case mode
                     "check" "Studio preflight passed."
                     "promote" (str "Studio administrator role verified for "
                                    (get-in result [:user :email]) ".")
                     "reset-password" (str "Reset Studio password for "
                                           (get-in result [:user :email]) ".")
                     (str "Created Studio administrator " (get-in result [:user :email])
                          " with owner access to workspace " (:workspace result) ".")))
          (System/exit 0)))))
