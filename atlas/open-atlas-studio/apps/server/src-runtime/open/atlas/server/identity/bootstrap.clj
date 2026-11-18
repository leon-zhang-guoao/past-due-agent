(ns open.atlas.server.identity.bootstrap
  "One-time, headless operator bootstrap for a standalone Server instance.

   The password is read from **standard input** and is never accepted as an
   argv value: argv is visible in shell history and in every process listing on
   the machine, so a password passed that way is disclosed to everyone with a
   login before it has hashed anything.

       OPEN_ATLAS_SERVER_HOME=/srv/atlas OPEN_ATLAS_SERVER_INSTANCE=prod-1 \\
         clojure -M:prod:bootstrap-admin --username admin

   It takes the instance lease while it runs, which is why it is a separate
   command rather than a flag on the server: two writers on one instance root
   is the one thing the lease exists to prevent."
  (:require [clojure.string :as str]
            [open.atlas.runtime.store :as store]
            [open.atlas.server.identity :as identity])
  (:gen-class))

(defn- parse-args [args]
  (loop [remaining args result {}]
    (if (empty? remaining)
      result
      (let [[flag value & more] remaining]
        (if (or (nil? value) (not (str/starts-with? (str flag) "--")))
          {:error (str "unexpected argument: " flag)}
          (recur more (assoc result (keyword (subs flag 2)) value)))))))

(defn- read-password []
  ;; `System/console` is nil under a pipe or a CI runner, so this reads the
  ;; line either way rather than refusing to run without a TTY.
  (if-let [console (System/console)]
    (some-> (.readPassword console "Password: " (object-array 0)) String.)
    (read-line)))

(defn- die! [message]
  (binding [*out* *err*] (println message))
  (System/exit 2))

(defn -main [& args]
  (let [{:keys [username display-name error]} (parse-args args)
        home        (System/getenv "OPEN_ATLAS_SERVER_HOME")
        instance-id (System/getenv "OPEN_ATLAS_SERVER_INSTANCE")]
    (cond
      error (die! error)
      (str/blank? (str username)) (die! "--username is required")
      (str/blank? (str home)) (die! "OPEN_ATLAS_SERVER_HOME is required")
      (str/blank? (str instance-id)) (die! "OPEN_ATLAS_SERVER_INSTANCE is required")
      :else
      (let [{:keys [instance findings]} (store/open! {:instance-id instance-id :root home})]
        (if (seq findings)
          (die! (str "cannot take this instance: " (:message (first findings))))
          (try
            (let [db (identity/open! instance)
                  password (read-password)
                  {:keys [operator findings]}
                  (identity/create-operator! db {:username username
                                                 :password password
                                                 :display-name display-name})]
              (if (seq findings)
                ;; The finding names the field and the expectation; it does not
                ;; echo what was typed.
                (die! (str "refused: " (str/join "; " (map :message findings))))
                (println (str "operator created: " (:username operator)
                              " (" (get-in operator [:principal :subject]) ")"))))
            (finally (store/close! instance))))))))
