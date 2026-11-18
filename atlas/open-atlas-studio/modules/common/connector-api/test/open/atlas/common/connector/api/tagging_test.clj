(ns open.atlas.common.connector.api.tagging-test
  "E2b：api 连接器的自报家门。

   E2 只做了 sql 那一侧（连接级 application name + 语句注释里的 request-id），
   api 连接器一次都没读过 `spi/*request-trace*`——被我们调的那些 API，运维在自己
   的访问日志里认不出这是谁。本文件钉住补上的那两层。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.impl.request :as request]
            [open.atlas.common.connector.spi :as spi]))

(defn- tag [src & [headers]] (request/tagging-headers src headers))

;; --- 第一层：User-Agent，无条件 ---------------------------------------------

(deftest user-agent-is-always-sent
  (testing "`ApplicationName` 的 HTTP 对应物：任何 API 都收、任何访问日志都记,
            对方运维不用做任何事就能认出我们的流量。**没有哪个 HTTP 端点会因为
            一个 UA 而失败**，所以这一层不需要「如果 API 支持」"
    (is (= {"User-Agent" "open-atlas"} (tag {})))))

(deftest the-instance-id-rides-along-when-the-host-bound-one
  (testing "同一个源被几台 instance 调着时，对方运维才分得清是哪一台"
    (binding [spi/*instance-id* "rt-7"]
      (is (= {"User-Agent" "open-atlas-rt-7"} (tag {}))))))

(deftest an-instance-id-cannot-smuggle-anything-into-the-header
  (testing "只留 `[A-Za-z0-9-]`——CRLF 与冒号被吃掉，于是「它注不进第二个
            header」是代码事实而不是一句约定。连字符在白名单里，所以剩下的是
            一坨无害的字符，不是一行 header"
    (binding [spi/*instance-id* "a\r\nX-Evil: 1"]
      (is (= {"User-Agent" "open-atlas-aX-Evil1"} (tag {}))))))

(deftest the-modeller-s-own-user-agent-wins
  (testing "建模者自己写了 UA 就以他的为准——自报家门绝不覆盖一次调用真正需要的
            东西（同 `:headers` 覆盖打标的合并顺序）"
    (is (= {} (tag {} {"User-Agent" "acme-client/2"})))
    (is (= {} (tag {} {"user-agent" "acme-client/2"}))
        "HTTP header 名大小写不敏感，判定也得是")))

;; --- 第二层：关联 id，按声明 -------------------------------------------------

(deftest correlation-ids-are-sent-only-where-declared
  (testing "往第三方请求里塞建模者没写过的 header，是在改一次他授权之外的调用：
            有的 API 白名单 header，有的把未知 header 计入签名。所以这一层要
            建模者签一次字——这就是「如果 API 支持」在这一层的意思"
    (binding [spi/*request-trace* {:request-id "req-1" :graph-id "g-9"}]
      (testing "没声明就只发 UA"
        (is (= {"User-Agent" "open-atlas"} (tag {}))))
      (testing "声明了就发，用**它给的 header 名**"
        (is (= {"User-Agent" "open-atlas"
                "X-Request-Id" "req-1"
                "X-Graph-Id" "g-9"}
               (tag {:trace_headers {:request-id "X-Request-Id"
                                     :graph-id "X-Graph-Id"}}))))
      (testing "只声明一个就只发一个"
        (is (= {"User-Agent" "open-atlas" "X-Request-Id" "req-1"}
               (tag {:trace_headers {:request-id "X-Request-Id"}})))))))

(deftest without-a-request-trace-nothing-is-invented
  (testing "单测、CLI、health check 都没有 trace——不发，不编一个。体检因此在对方
            日志里显示成「Open Atlas 在探活」，而不是某次业务调用"
    (is (= {"User-Agent" "open-atlas"}
           (tag {:trace_headers {:request-id "X-Request-Id" :graph-id "X-Graph-Id"}})))))

(deftest a-trace-value-cannot-smuggle-anything-either
  (binding [spi/*request-trace* {:request-id "a\r\nX-Evil: 1" :graph-id nil}]
    (is (= {"User-Agent" "open-atlas" "X-Request-Id" "aX-Evil1"}
           (tag {:trace_headers {:request-id "X-Request-Id"
                                 :graph-id "X-Graph-Id"}}))
        "graph-id 没有值就整条不发，而不是发一个空 header")))
