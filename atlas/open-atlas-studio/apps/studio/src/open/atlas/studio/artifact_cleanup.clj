(ns open.atlas.studio.artifact-cleanup
  "Compatibility entry point; Studio and Server use the shared cleanup engine."
  (:require [open.atlas.runtime.artifact-cleanup :as shared]))
(def plan! shared/plan!)
(def apply! shared/apply!)
(def delete-path! shared/delete-path!)
