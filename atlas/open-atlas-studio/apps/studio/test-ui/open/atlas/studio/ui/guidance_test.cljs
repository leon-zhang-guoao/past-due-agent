(ns open.atlas.studio.ui.guidance-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            ["react-dom/server" :as server]
            [uix.core :refer [$]]
            [open.atlas.studio.ui.guidance :as guidance]))

(deftest recovery-distinguishes-unknown-writes-access-and-conflicts
  (is (= "Publication result not confirmed"
         (:title (guidance/error-guidance {:publication-uncertain? true :code :timeout :status 504}))))
  (is (= "Sign-in required" (:title (guidance/error-guidance {:status 401}))))
  (is (= "Your account cannot perform this action"
         (:title (guidance/error-guidance {:status 403}))))
  (is (= "The saved model has changed"
         (:title (guidance/error-guidance
                  {:findings [{:code :semantic.draft/revision-conflict}]}))))
  (is (= "This action conflicts with the current state"
         (:title (guidance/error-guidance {:status 409 :code :graph-already-exists}))))
  (is (str/includes? (:next (guidance/error-guidance {:code :timeout :method "POST"}))
                     "may have completed"))
  (is (not (str/includes? (:next (guidance/error-guidance {:code :timeout :method "GET"}))
                          "may have completed")))
  (is (= "Review the items below"
         (:title (guidance/error-guidance {:findings [{:code :invalid-config}]})))))

(deftest binding-summary-counts-incompatible-and-unknown-as-needing-attention
  (is (= {:total 0 :needs-attention 0 :configured 0} (guidance/source-summary [])))
  (is (= {:total 5 :needs-attention 3 :configured 2}
         (guidance/source-summary [{:status :ready} {:status :not-required}
                                   {:status :unbound} {:status :incompatible} {}]))))

(deftest workflow-never-promises-source-health-or-published-draft-parity
  (let [model (guidance/graph-guidance {:tab :semantic :can-edit? true :serving 7})
        sources (guidance/graph-guidance {:tab :sources :can-edit? true})]
    ;; The tabs run Sources → Model → Publish (owner, 2026-10-01), so each
    ;; banner points at the NEXT one.
    (is (= :release (:action model)))
    (is (str/includes? (:message model) "does not change the serving version"))
    (is (= :semantic (:action sources)))
    (is (str/includes? (:message sources) "what this model reads")))
  (doseq [tab [:semantic :explorer :sources :release]]
    (is (nil? (:action (guidance/graph-guidance {:tab tab :archived? true :can-edit? true}))))
    (is (not (contains? #{:sources :release}
                        (:action (guidance/graph-guidance {:tab tab :can-edit? false})))))))

(deftest rendered-guide-exposes-context-and-only-permitted-navigation
  (let [render #(server/renderToStaticMarkup ($ guidance/graph-guide %))
        editor (render {:tab :semantic :can-edit? true :on-navigate identity})
        archived (render {:tab :release :can-edit? true :archived? true})
        viewer (render {:tab :semantic :can-edit? false :on-navigate identity})]
    (is (str/includes? editor "Graph workflow guidance"))
    (is (str/includes? editor "Next: Publish"))
    (is (str/includes? editor "data-settings-navigation"))
    (is (str/includes? archived "This Graph is archived"))
    (is (not (str/includes? archived "<button")))
    (is (str/includes? viewer "Explore model"))
    (is (not (str/includes? viewer "Next: Publish")))))
