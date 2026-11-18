(ns open.atlas.studio.test-runner
  (:require [clojure.test :as test]
            [open.atlas.studio.api-source-test]
            [open.atlas.studio.artifact-cleanup-test]
            [open.atlas.studio.semantic-service-test]
            [open.atlas.studio.semantic-import-capabilities-test]
            [open.atlas.studio.semantic-package-service-test]
            [open.atlas.studio.builder-mcp-test]
            [open.atlas.studio.builder-server-test]
            [open.atlas.studio.child-collection-test]
            [open.atlas.studio.credentials-test]
            [open.atlas.studio.draft-parity-test]
            [open.atlas.studio.dvd-fixture-test]
            [open.atlas.studio.publish-mcp-test]
            [open.atlas.studio.repair-test]
            [open.atlas.studio.self-test-token-test]
            [open.atlas.studio.workspace-token-test]
            [open.atlas.studio.identity-test]
            [open.atlas.studio.oidc-test]
            [open.atlas.studio.platform-runtime-test]
            [open.atlas.studio.pool-maintenance-test]
            [open.atlas.studio.model.output-shape-test]
            [open.atlas.studio.ui-test-suite-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'open.atlas.studio.artifact-cleanup-test
                                       'open.atlas.studio.semantic-service-test
                                       'open.atlas.studio.semantic-import-capabilities-test
                                       'open.atlas.studio.semantic-package-service-test
                                       'open.atlas.studio.api-source-test
                                       'open.atlas.studio.builder-mcp-test
                                       'open.atlas.studio.builder-server-test
                                       'open.atlas.studio.child-collection-test
                                       'open.atlas.studio.credentials-test
                                       'open.atlas.studio.dvd-fixture-test
                                       'open.atlas.studio.draft-parity-test
                                       'open.atlas.studio.publish-mcp-test
                                       'open.atlas.studio.repair-test
                                       'open.atlas.studio.self-test-token-test
                                       'open.atlas.studio.workspace-token-test
                                       'open.atlas.studio.platform-runtime-test
                                       'open.atlas.studio.pool-maintenance-test
                                       'open.atlas.studio.oidc-test
                                       'open.atlas.studio.identity-test
                                       'open.atlas.studio.model.output-shape-test
                                       ;; Last: it shells out to node, and a
                                       ;; failure here is about the OTHER suite.
                                       'open.atlas.studio.ui-test-suite-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
