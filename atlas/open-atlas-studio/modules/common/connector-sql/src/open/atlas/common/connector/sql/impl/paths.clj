(ns open.atlas.common.connector.sql.impl.paths
  "Anchor RELATIVE sqlite file paths in a `jdbc:sqlite:` URL.

   doc 104 §8.17.7 B (owner ruling 2026-09-06): a package describes a class of
   system, so file paths inside a source config must be portable. A relative
   `jdbc:sqlite:<path>` used to resolve against the JVM working directory, so a
   bundle imported fine and its first query failed (§8.17.4 item 1). The anchor
   is now the same one connector-api's `workspace-file` uses:
   $OPEN_ATLAS_CONFIG_PATH, else $OPEN_ATLAS_HOME, else ~/.open-atlas.

   Untouched: absolute paths, `:memory:`, and URI-style specs (`file:...`,
   `:resource:...`) — rewriting those would change their meaning, and a URI
   already says where it points."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]))

(def ^:private sqlite-prefix "jdbc:sqlite:")

(defn base-dir
  "Anchor for relative file paths — `spi/workspace-base-dir`, which is the one
   place the $OPEN_ATLAS_CONFIG_PATH → $OPEN_ATLAS_HOME → ~/.open-atlas order
   is written (doc 104 §8.17.7 H item 1)."
  ([] (spi/workspace-base-dir))
  ([getenv] (spi/workspace-base-dir getenv)))

(defn sqlite-spec
  "The spec written after `jdbc:sqlite:`, or nil when the URL is not a sqlite
   one. **The one parser** of this URL form in this connector: `declared-files`
   used to carry a second copy, and two copies of \"what counts as a sqlite
   path\" drift (D1.8)."
  [url]
  (when (and (string? url)
             (str/starts-with? (str/lower-case url) sqlite-prefix))
    (subs url (count sqlite-prefix))))

(defn declared-file
  "The file path inside a `jdbc:sqlite:<path>` URL, or nil.

   Answered **as written** (relative stays relative, `${VAR}` unresolved) —
   see `spi/declared-files`. `:memory:` and the `file::memory:` forms name no
   file on disk, and an empty path is sqlite's own private temporary database;
   none of the three is something an installer can check for."
  [url]
  (when-let [spec (sqlite-spec url)]
    (let [path (-> spec
                   ;; sqlite's JDBC options ride a `?` query string; they are
                   ;; not part of the file name.
                   (str/split #"\?" 2)
                   first
                   str/trim)]
      (when-not (or (str/blank? path)
                    (str/includes? path ":memory:")
                    (str/starts-with? path ":resource:"))
        path))))

(defn- uri-style?
  "A spec the sqlite driver reads as a URI or a special target rather than a
   plain filesystem path."
  [spec]
  (or (str/starts-with? spec ":")            ; :memory:, :resource:...
      (str/starts-with? spec "file:")
      (str/starts-with? spec "jdbc:")
      (str/includes? spec "::")))

(defn anchor-sqlite-url
  "Rewrite a `jdbc:sqlite:<relative path>` URL so the path resolves under
   `base-dir`. Any other URL is returned unchanged.
   → `{:url <string> :anchored <absolute path or nil>}`; `:anchored` is the
   resolved path when a rewrite happened, so callers can surface it instead of
   silently changing a URL the user cannot see."
  [url]
  (if-let [spec (sqlite-spec url)]
    (if (or (str/blank? spec)
            (uri-style? spec)
            (.isAbsolute (io/file spec)))
      {:url url :anchored nil}
      (let [abs (.getPath (io/file (base-dir) spec))]
        {:url (str sqlite-prefix abs) :anchored abs}))
    {:url url :anchored nil}))
