(ns open.atlas.studio.identity.bootstrap
  "One-time, headless Studio administrator bootstrap.

   The password is read from standard input and is never accepted as an argv
   value, keeping it out of shell history and process listings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.identity :as identity]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version :as version]))

(defn- studio-home []
  (or (System/getenv "OPEN_ATLAS_STUDIO_HOME")
      (str (io/file (System/getProperty "user.home") ".open-atlas" "studio"))))

(defn- parse-args [args]
  (loop [remaining args result {}]
    (if (empty? remaining)
      result
      (let [[flag value & more] remaining]
        (if (or (nil? value) (not (str/starts-with? flag "--")))
          {:error (str "Invalid arguments near " flag)}
          (recur more (assoc result (keyword (subs flag 2)) value)))))))

(defn bootstrap! [{:keys [email name workspace password]}]
  (let [cfg (pool/config {:backend :file :path (str (io/file (studio-home) "pool"))})
        conn (-> (pool/open-pool! cfg) graph/install! version/install! identity/install!)]
    (try
      (identity/bootstrap-admin! conn {:email email :name name
                                       :workspace (or workspace "demo")
                                       :password password})
      (finally (pool/close-pool! conn)))))

(defn -main [& args]
  (let [parsed (parse-args args)
        password (some-> (read-line) str)
        result (if-let [message (:error parsed)]
                 {:error {:code :invalid-arguments :message message}}
                 (bootstrap! (assoc parsed :password password)))]
    (if-let [error (:error result)]
      (do (binding [*out* *err*] (println "ERROR:" (:message error)))
          (System/exit 1))
      (do (println (str "Created founding admin " (get-in result [:user :email])
                        " with owner access to workspace " (:workspace result) "."))
          (System/exit 0)))))
