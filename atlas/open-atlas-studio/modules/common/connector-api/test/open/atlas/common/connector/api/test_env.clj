(ns open.atlas.common.connector.api.test-env
  "One place that binds the connector's `${VAR}` lookup for tests.

   There are THREE entry points — `resolve-value` (whole-value credential
   keys), `substitute` (embedded vars in config), `missing-vars` (the
   refusal check) — and a test that binds only some of them silently falls
   through to the real environment for the rest. That is not hypothetical:
   binding only `resolve-value` is what let the Schneider fixture pass while
   the connector was still shipping `${SE_CLIENT_ID}` upstream as a literal.
   Hence one macro, used everywhere, rather than a copy per test ns."
  (:require [open.atlas.common.connector.api.impl.interp :as interp]))

(defmacro with-env
  "Run `body` with every `${VAR}` lookup served from `env` ({name → value})
   instead of the process environment."
  [env & body]
  `(let [env#  ~env
         rv#   interp/resolve-value
         sub#  interp/substitute
         miss# interp/missing-vars]
     (with-redefs [interp/resolve-value (fn [v# & _#] (rv# v# env#))
                   interp/substitute    (fn [s# & _#] (sub# s# env#))
                   interp/missing-vars  (fn [vs# & _#] (miss# vs# env#))]
       ~@body)))
