(ns open.atlas.studio.ui.mcp-connections
  (:require [clojure.string :as str]
            [open.atlas.studio.ui.clipboard :as clipboard]
            [uix.core :refer [defui $ use-state]]))

(defn endpoint-urls
  "Builder shares the browser origin; the embedded data listener uses its configured port and scheme."
  ([origin data-port] (endpoint-urls origin data-port "http"))
  ([origin data-port data-scheme]
  {:builder (str origin "/mcp")
   :data (when data-port
           (let [url (js/URL. origin)]
             (set! (.-protocol url) (str (or data-scheme "http") ":"))
             (set! (.-port url) (str data-port))
             (set! (.-pathname url) "/mcp")
             (.-href url)))}))

(defn graph-endpoint-url
  ([origin data-port graph-id] (graph-endpoint-url origin data-port "http" graph-id))
  ([origin data-port data-scheme graph-id]
   (when (and data-port (not (str/blank? graph-id)))
     (str (:data (endpoint-urls origin data-port data-scheme)) "/" graph-id))))

(defui endpoint-row
  "A connection address and its Copy button. `compact?` is the one-line form used
   on a graph's Publish tab: label, address, button — the description and the
   credential kind move into the tooltip, where they are read once and not again."
  [{:keys [title url description credential compact?]}]
  (let [[status set-status!] (use-state nil)
        copy! #(-> (clipboard/copy-text! url)
                   (.then (fn [_] (set-status! "URL copied")))
                   (.catch (fn [_] (set-status! "Select the URL and copy it manually."))))]
    (if compact?
      ($ :div.mcp-line {:title (str description " Credential: " credential ".")}
         ($ :span.mcp-line-label title)
         (if url
           ($ :<>
              ($ :input {:read-only true :value url :aria-label (str title " URL")
                         :on-focus #(.select (.-target %))})
              ($ :button.btn {:type "button" :on-click copy!} (if status "Copied" "Copy")))
           ($ :span.hint "address unavailable — reload the workspace")))
      ($ :section.mcp-endpoint
         ($ :div.mcp-endpoint-heading ($ :h3 title) ($ :span.mcp-credential credential))
         ($ :p description)
         (if url
           ($ :div.mcp-url-row
              ($ :input {:read-only true :value url :aria-label (str title " URL")
                         :on-focus #(.select (.-target %))})
              ($ :button.btn {:type "button" :on-click copy!} "Copy URL"))
           ($ :p.hint "Connection address is unavailable. Reload the workspace to try again."))
         ($ :p.mcp-copy-status {:role "status" :aria-live "polite"} (or status ""))))))

(defui graph-connection [{:keys [graph-id data-port data-scheme serving archived?]}]
  ($ :section.graph-mcp-connection {:aria-label "Graph MCP connection"
                                    :title (str "Graph UUID " graph-id ". The UUID identifies this graph independently of its name. These addresses use this host and Studio's data port; behind a reverse proxy, replace the base address and keep /mcp/<uuid>.")}
     ($ endpoint-row
        {:title "MCP" :compact? true
         :url (graph-endpoint-url (.-origin js/location) data-port data-scheme graph-id)
         :credential "Graph token"
         :description "Connect with HTTP transport and a token for this graph: Authorization: Bearer <token>."})
     ($ endpoint-row
        {:title "MCP · Docker" :compact? true
         :url (graph-endpoint-url "http://host.docker.internal" data-port data-scheme graph-id)
         :credential "same graph token"
         :description "For a client running in Docker on the Studio host. If the data port is published on a different host port, use that port."})
     (when (or archived? (nil? serving))
       ($ :p.hint (if archived?
                    "Restore and publish this graph before connecting."
                    "Publish this graph before connecting.")))))
