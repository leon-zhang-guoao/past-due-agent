(ns open.atlas.common.connector.api.bound-attributes-test
  "E1 / D5.14: `spi/bound-attributes` on the api connector.

   The api connector spells an input placeholder with the **qualified** attr
   (`{vpo/order_no}`) and an eql-param by its simple name. That difference is
   the whole reason this seam answers in attributes rather than in binding
   names — a caller comparing local strings would have judged every api
   operation unbound."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api]
            [open.atlas.common.connector.spi :as spi]))

(defn- bound [src] (spi/bound-attributes (assoc src :type "api")))

(deftest an-endpoint-placeholder-binds-its-attribute
  (is (= #{:vpo/tenant_id}
         (bound {:input [:vpo/tenant_id]
                 :endpoint "/orders/{vpo/tenant_id}"}))))

(deftest query-params-and-json-body-count-too
  (testing "三处模板位各绑各的，合起来就是这次操作真正带出去的 :input"
    (is (= #{:vpo/tenant_id :vpo/region :vpo/id}
           (bound {:input [:vpo/tenant_id :vpo/region :vpo/id]
                   :endpoint "/orders/{vpo/id}"
                   :query_params {"tenant" "{vpo/tenant_id}"}
                   :json_body {"filter" {"region" "{vpo/region}"}}})))))

(deftest an-eql-param-placeholder-does-not-count
  (testing "eql-param 按**简单名**插值，与限定属性不同名——与 `:input` 求交
            自然把它排除。D5.6 共识 2：行级安全不绑调用方提供的值"
    (is (= #{} (bound {:input [:vpo/tenant_id]
                       :eql-params {:tenant_id {}}
                       :endpoint "/orders?tenant={tenant_id}"})))))

(deftest an-optional-placeholder-does-not-count
  (testing "`{?name}` 允许在发送前**静默掉出**（owner 裁定 2026-08-28）。一个
            可能凭空消失的过滤器不是行级保证——朝关闭"
    (is (= #{} (bound {:input [:vpo/tenant_id]
                       :query_params {"tenant" "{?vpo/tenant_id}"}})))))

(deftest a-placeholder-for-something-not-an-input-does-not-count
  (testing "模板里写着一个不在 `:input` 里的属性：运行期它没有值可填，请求会
            带着未解析的模板被拒。算成「绑上了」等于让核对在这类模型上放行"
    (is (= #{} (bound {:input [:vpo/id]
                       :endpoint "/orders/{vpo/tenant_id}"})))))

(deftest headers-are-not-walked
  (testing "`:headers` 的模板从环境配置解析（`substitute-vals`），不来自调用方
            的 entity——那里的占位符永远不携带行级值"
    (is (= #{} (bound {:input [:vpo/tenant_id]
                       :endpoint "/orders"
                       :headers {"X-Tenant" "{vpo/tenant_id}"}})))))

;; ---------------------------------------------------------------------------
;; :base_url (owner 2026-09-07, doc 106 §106.14 item 11)
;; ---------------------------------------------------------------------------

(deftest a-base-url-host-placeholder-binds-its-attribute
  (testing "one model over many like-for-like instances: the host is as
            row-level a narrowing as a path segment, so E1 must see it"
    (is (= #{:nifi.instance/host}
           (bound {:input [:nifi.instance/host]
                   :base_url "https://{nifi.instance/host}:9443/nifi-api"
                   :endpoint "/flow/about"})))
    (testing "and both spellings bind the same attribute"
      (is (= #{:nifi.instance/host}
             (bound {:input [:nifi.instance/host]
                     :base_url "https://{+nifi.instance/host}:9443/nifi-api"
                     :endpoint "/flow/about"}))))))
