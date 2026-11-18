(ns open.atlas.integration.demo-fixture-test
  "demo fixture 自身的守门测试：`fixtures/demo/` 那两份东西不许悄悄分家。

   为什么在装配点：这里是唯一同时看得见**模型**与**库**的地方（其余模块只用
   模型），而 fixture 的两半一致性正是要跨这两者判。

   ── 守什么 ────────────────────────────────────────────────────────────────

   1. `model.edn` 过 B0。它是 committed 的文件，**不再有任何一步会替它验**
      ——转换器退休之后，没验就是没验（`fixtures/demo/README.md`）。
   曾经还有第二件：`tiers.md` 的属性数与模型对得上。那张表是 owner 的档位
   裁定，而访问档位已于 2026-08-27 整套退役
   （[D5.11](../../../../../../docs/design/50-security.md)），表与这条执法
   一并删除——**不是规则被绕过，是它没有对象了**。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]))

(def ^:private model
  (delay (edn/read-string (slurp (io/resource "model.edn")))))

(deftest committed-model-passes-b0
  (testing "`fixtures/demo/model.edn` 自己过 B0——committed 的东西更该验，
            因为不再有任何一步会替它验（dialect-v1 §7）"
    (let [{m :model fs :findings} (dialect/read-dialect (slurp (io/resource "model.edn")))]
      (is (some? m) (str "model.edn 未过 B0: "
                         (pr-str (mapv (juxt :code :path)
                                       (filter #(= :error (:severity %)) fs)))))
      (is (empty? (filter #(= :error (:severity %)) fs))))))


