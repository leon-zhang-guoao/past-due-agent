(ns open.atlas.studio.config.normalize
  "Attribute keyword -> `\"ns/name\"` text, shared by `pathom/explain.clj` and
   `pathom/index-graph.clj` — the plan/blueprint DTOs the Builder still hand-
   rolls from the dialect model directly (doc 89 Phase 3: everything else
   this namespace held — `attr->keyword`, `executable`/`supported`/
   `normalize` — served only the retired legacy config loader and was
   deleted with it).")

(defn keyword->attr [kw]
  (str (namespace kw) "/" (name kw)))
