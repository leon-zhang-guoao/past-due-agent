(ns open.atlas.common.connector.api.interp-test
  "Interpolation and parameter routing: endpoint URL encoding, json_body
   raw-type preservation, rejection walker for unresolved placeholders,
   query/body routing and pagination defaults."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.impl.interp :as interp]))

(deftest string-interpolation
  (is (= "/v/A9" (interp/interpolate-params "/v/{p/sku}" {"p/sku" "A9"})))
  (testing "unknown placeholders stay for the caller to reject"
    (is (= "/v/{p/sku}" (interp/interpolate-params "/v/{p/sku}" {})))
    (is (= ["p/sku"] (interp/unresolved-placeholders "/v/{p/sku}")))))

(deftest url-interpolation-encodes-values
  (is (= "/vendors/a%2Fb%20c" (interp/interpolate-url-params
                               "/vendors/{v/no}" {"v/no" "a/b c"}))))

(deftest body-interpolation-preserves-raw-types
  (let [body {:version "{x/version}" :note "v={x/version}" :n 1}
        out  (interp/interpolate-body body {"x/version" 7})]
    (testing "an exact single-placeholder leaf keeps the raw value type"
      (is (= 7 (:version out))))
    (testing "an embedded placeholder string-interpolates"
      (is (= "v=7" (:note out))))
    (is (= 1 (:n out)))))

(deftest body-unresolved-placeholders-walker
  (is (= [] (interp/body-unresolved-placeholders {:a 1 :b "x"})))
  (is (= ["lineNo"]
         (interp/body-unresolved-placeholders {:a {:b ["{lineNo}"]}})))
  (testing "optional {?name} placeholders are not violations"
    (is (= [] (interp/body-unresolved-placeholders {:a "{?lineNo}"})))))

(deftest optional-placeholders-substitute-or-drop
  (testing "with a value, {?name} substitutes like {name} (raw type kept)"
    (is (= {:line 7} (-> (interp/interpolate-body {:line "{?lineNo}"} {"lineNo" 7})
                         interp/drop-optional-entries))))
  (testing "without a value, the entry drops out of the body"
    (is (= {:sku "A"}
           (-> (interp/interpolate-body {:sku "{p/sku}" :line "{?lineNo}"}
                                        {"p/sku" "A"})
               interp/drop-optional-entries))))
  (testing "a plain unresolved {name} does NOT drop — it stays and is rejected"
    (is (= {:line "{lineNo}"}
           (interp/drop-optional-entries {:line "{lineNo}"})))))

(deftest param-routing
  (let [declared {:vendor {:field "vendorNo"}
                  :status {:field "state" :param_location "query"}}]
    (testing "body params: :field-declared, minus query-routed and pagination"
      (is (= {"vendorNo" 9}
             (interp/merge-eql-params-into-body
              {:vendor 9 :status "open" :limit 5} declared))))
    (testing "query params: only :param_location \"query\""
      (is (= {"state" "open"}
             (interp/declared-query-params {:vendor 9 :status "open"} declared))))))

(deftest pagination-defaults-and-overrides
  (is (= {"_limit" 10 "_offset" 20}
         (interp/pagination-query-params {:limit 10 :offset 20} {})))
  (is (= {"pageSize" 10}
         (interp/pagination-query-params {:limit 10}
                                         {:limit {:field "pageSize"}}))))

(deftest merge-into-body-is-map-only
  (is (= {:a 1 :b 2} (interp/merge-into-body {:a 1} {:b 2})))
  (testing "a root-array body has no place for named keys"
    (is (= [1 2] (interp/merge-into-body [1 2] {:b 2})))))

(deftest templated-query-params-drop-unresolved
  (is (= {"q" "sku:A9"}
         (interp/templated-query-params {"q" "sku:{p/sku}" "opt" "{p/x}"}
                                        {"p/sku" "A9"}))))

(deftest var-placeholder-discipline
  (is (interp/only-placeholder? "${API_KEY}"))
  (is (not (interp/only-placeholder? "Bearer ${API_KEY}")))
  (is (= ["MISSING_ONE"]
         (interp/missing-vars ["${MISSING_ONE}" "plain" "${SET_ONE}"]
                              {"SET_ONE" "x"}))))

;; ---------------------------------------------------------------------------
;; {+attr} reserved expansion (api-v1, owner ruling 2026-09-07,
;; doc 106 §106.14 item 8 / OQ-PATH-KEY-ENCODING)
;; ---------------------------------------------------------------------------

(deftest reserved-expansion-keeps-slashes
  (testing "{attr} encodes the whole value — one path segment"
    (is (= "/repos/apache%2Fnifi"
           (interp/interpolate-url-params "/repos/{r/full_name}" {"r/full_name" "apache/nifi"}))))
  (testing "{+attr} lets / through and still encodes everything else"
    (is (= "/repos/apache/nifi"
           (interp/interpolate-url-params "/repos/{+r/full_name}" {"r/full_name" "apache/nifi"})))
    (is (= "/repos/a%20b/c%3Fd"
           (interp/interpolate-url-params "/repos/{+r/full_name}" {"r/full_name" "a b/c?d"}))))
  (testing "both spellings bind the same attribute name"
    (is (= ["r/full_name"] (interp/unresolved-placeholders "/repos/{+r/full_name}")))
    (is (= #{:r/full_name}
           (interp/bound-input-attributes {:endpoint "/repos/{+r/full_name}"
                                           :input [:r/full_name]})))))

(deftest reserved-expansion-refuses-path-injection
  (testing "every forbidden pattern is named, and the value is not echoed"
    (doseq [[v reason] [[".." :dot-segment]
                        ["a/../b" :dot-segment]
                        ["./a" :dot-segment]
                        ["a?x=1" :query-or-fragment]
                        ["a#f" :query-or-fragment]
                        ["a\\b" :backslash]
                        ["/a" :empty-segment]
                        ["a/" :empty-segment]
                        ["a//b" :empty-segment]
                        ["a b" :whitespace]
                        [(str "a" (char 1) "b") :control]]]
      (is (= reason (:reason (interp/reserved-value-violation v)))
          (str "expected " reason " for " (pr-str v)))
      (is (= [reason]
             (mapv :reason (interp/reserved-value-findings "/x/{+k/id}" {"k/id" v}))))))
  (testing "a plain slashed value is exactly what {+attr} is for"
    (is (nil? (interp/reserved-value-violation "apache/nifi")))
    (is (= [] (interp/reserved-value-findings "/x/{+k/id}" {"k/id" "apache/nifi"}))))
  (testing "the same value under a plain {attr} is not a path at all, so not judged here"
    (is (= [] (interp/reserved-value-findings "/x/{k/id}" {"k/id" ".."})))))
