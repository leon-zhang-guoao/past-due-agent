(ns open.atlas.studio.ui.mcp-connections-test
  (:require [cljs.test :refer-macros [deftest is]]
            [open.atlas.studio.ui.mcp-connections :as mcp]))

(deftest listener-addresses-use-configured-port
  (is (= {:builder "https://studio.example/mcp" :data "http://studio.example:12345/mcp"}
         (mcp/endpoint-urls "https://studio.example" 12345)))
  (is (= "http://[::1]:9632/mcp" (:data (mcp/endpoint-urls "http://[::1]:9631" 9632))))
  (is (nil? (:data (mcp/endpoint-urls "http://localhost:9631" nil)))))

(deftest graph-addresses-use-the-uuid-and-data-listener
  (is (= "http://studio.example:12345/mcp/12345678-1234-4234-8234-123456789abc"
         (mcp/graph-endpoint-url "https://studio.example" 12345 "12345678-1234-4234-8234-123456789abc")))
  (is (= "http://[::1]:9632/mcp/12345678-1234-4234-8234-123456789abc"
         (mcp/graph-endpoint-url "http://[::1]:9631" 9632 "12345678-1234-4234-8234-123456789abc")))
  (is (nil? (mcp/graph-endpoint-url "http://localhost:9631" nil "12345678-1234-4234-8234-123456789abc")))
  (is (nil? (mcp/graph-endpoint-url "http://localhost:9631" 9632 nil))))

(deftest an-https-data-plane-gets-an-https-url
  (is (= "https://studio.example:9632/mcp"
         (:data (mcp/endpoint-urls "https://studio.example" 9632 "https"))))
  (is (= "https://studio.example:9632/mcp/12345678-1234-4234-8234-123456789abc"
         (mcp/graph-endpoint-url "https://studio.example" 9632 "https"
                                 "12345678-1234-4234-8234-123456789abc"))))
