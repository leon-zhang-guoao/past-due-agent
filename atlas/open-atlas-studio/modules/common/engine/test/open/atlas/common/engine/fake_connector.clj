(ns open.atlas.common.engine.fake-connector
  "测试用的假连接器：返回固定行，不碰数据库。真数据库端到端归 connector-sql
   那一路（fixtures/b1/schema.sql），engine 的测试只验证「从归一模型到 env、
   再到 D5.4 那道门」这一段。

   注册在**真 SPI** 的 multimethod 上（`open.atlas.common.connector.spi`），
   source type 用 `\"test-inline\"` 与 `\"test-slow\"`——与任何真连接器的 type
   都不撞，所以 defmethod 不改 connector-spi 下的任何文件，只是往它的分派表里
   登记两个测试专用的 type。engine 因此在测试里也走的是**同一条 SPI 路径**，
   没有为测试开的旁门。"
  (:require [open.atlas.common.connector.spi :as spi]))

(def vendors
  [{:cis.master.vendor/vend_no 1001 :cis.master.vendor/vend_name "Acme"
    :cis.master.vendor/discontinued "N" :cis.master.vendor/contact_email "a@acme.example"
    :cis.master.vendor/entry_by "gaojie" :cis.master.vendor/entry_date "2026-01-02"}
   {:cis.master.vendor/vend_no 1002 :cis.master.vendor/vend_name "Globex"
    :cis.master.vendor/discontinued "Y" :cis.master.vendor/contact_email "b@globex.example"
    :cis.master.vendor/entry_by "liuyang" :cis.master.vendor/entry_date "2026-01-03"}
   {:cis.master.vendor/vend_no 1003 :cis.master.vendor/vend_name "Initech"
    :cis.master.vendor/discontinued "N" :cis.master.vendor/contact_email "c@initech.example"
    :cis.master.vendor/entry_by "gaojie" :cis.master.vendor/entry_date "2026-01-04"}])

(def parts
  [{:cis.master.part/sku_no 1 :cis.master.part/part_no "P-1" :cis.master.part/short_desc "Bolt"
    :cis.master.part/ave_cost 1.5M :cis.master.part/prod_code "R" :cis.master.part/vend_no 1001}
   {:cis.master.part/sku_no 2 :cis.master.part/part_no "P-2" :cis.master.part/short_desc "Nut"
    :cis.master.part/ave_cost 0.5M :cis.master.part/prod_code "R" :cis.master.part/vend_no 1001}
   {:cis.master.part/sku_no 3 :cis.master.part/part_no "P-3" :cis.master.part/short_desc "Frame"
    :cis.master.part/ave_cost 20.0M :cis.master.part/prod_code "A" :cis.master.part/vend_no 1002}])

(defn- rows-for
  "`input-params` 是 pathom 给的输入 map，`eql-params` 是扁平的参数 map
   （含保留调用名 :limit/:order_by/…）——SPI 的调用约定。"
  [id input-params eql-params]
  (case id
    "demo_vendor_by_id"
    (first (filter #(= (:cis.master.vendor/vend_no %)
                       (:cis.master.vendor/vend_no input-params))
                   vendors))

    "demo_all_parts"
    (let [pc (:prod_code eql-params)]
      (cond->> parts
        pc              (filterv #(= pc (:cis.master.part/prod_code %)))
        (:limit eql-params) (into [] (take (:limit eql-params)))))

    "demo_parts_by_vendor"
    (filterv #(= (:cis.master.part/vend_no %)
                 (:cis.master.vendor/vend_no input-params))
             parts)))

(defmethod spi/compile-source "test-inline" [_source-config _connection resolver-config]
  (fn [input-params eql-params]
    (rows-for (:id resolver-config) input-params eql-params)))

(defmethod spi/compile-batch-source "test-inline"
  [source-config _connection resolver-config]
  ;; 与 inputs **等长同序**，没有行的父项拿 nil（`"single"`）或 `[]`（`"list"`）
  ;; ——丢一位会把后面每个结果静默移到错误的父项上（principles「执行与连接」）。
  ;;
  ;; 按 batch 块的 `:parent-key` 泛化，而不是写死某一条 resolver：fixture 里
  ;; 两条 batch resolver 的父键不同（part 按 sku_no、vendor 按 vend_no）。
  ;; `"list"` 侧照样过 `spi/window-bucket`——假连接器若自己绕开切窗，
  ;; per-parent 窗口就又变成没人测的那一段了。
  (let [pk    (get-in source-config [:batch :parent-key])
        list? (= "list" (:result_type resolver-config))
        ;; 子行认父的键：part 自己的身份键，或它的 vend_no 外键。
        fk-of #(if (= pk :cis.master.vendor/vend_no)
                 (:cis.master.part/vend_no %)
                 (:cis.master.part/sku_no %))]
    (fn [inputs eql-params]
      (let [win (let [{:keys [default max]} (:limit resolver-config)
                      n (or (:limit eql-params) default)]
                  (when n {:limit (if (and max (> (long n) (long max))) max n)
                           :offset (:offset eql-params)}))]
        (mapv (fn [input]
                (let [bucket (filterv #(= (fk-of %) (get input pk)) parts)]
                  (if list?
                    (spi/window-bucket bucket win)
                    (first bucket))))
              (vec inputs))))))

;; The fake stands in for fixtures/b1's `"sql"` sources, and sql-v1 declares
;; `offset ✓` — so it declares the same, and the engine's gate hands `:offset`
;; down for it exactly as it does for a real sql source. The batch path really
;; does window with it (`spi/window-bucket` below).
(defmethod spi/supports-offset? "test-inline" [_] true)

(defmethod spi/compile-source "test-unconnectable"
  ;; A connector that cannot open its source at all. It fails the way
  ;; `connector-sql`'s `datasource!` fails on an unresolved placeholder:
  ;; category `:connection`, and the variable NAME in the message — a name is
  ;; configuration in the dialect, not a credential value (`spi/fail!`'s
  ;; contract keeps statements, URLs and row values out).
  [_source-config _connection resolver-config]
  (fn [_input-params _eql-params]
    (spi/fail! :connection {:resolver-id (:id resolver-config)
                            :source-type "test-unconnectable"}
               "unresolved ${DB_PASSWORD}")))

(defmethod spi/compile-source "test-slow" [_source-config _connection _resolver-config]
  (fn [_input-params _eql-params] (Thread/sleep 5000) nil))

(def ^:dynamic *bound-hit*
  "Which declared bound `test-bounded` should refuse with, as
   `{:limit kw :max n :actual n}` (doc 107 §107.6)."
  {:limit :max-items :max 120 :actual 200})

(defmethod spi/compile-source "test-bounded"
  ;; A connector whose collection read hits a declared bound -- what the api
  ;; connector's paging loop does when `:max-items` or `:max-pages` is reached.
  ;; The engine must answer this with `:engine.limit/exceeded`, the SAME shape
  ;; the four EQL boundaries use, rather than as an execution failure: to the
  ;; caller it is one question, "was I given everything I asked for?".
  [_source-config _connection resolver-config]
  (fn [_input-params _eql-params]
    (spi/fail! :source {:resolver-id (:id resolver-config)
                        :source-type "test-bounded"}
               (str "collection exceeded " (:limit *bound-hit*))
               {:limit *bound-hit*})))

(def writes
  "What the fake connector's mutations did, in order. A test asserts against
   this rather than a database, because what engine owns is 'the params
   reached the connector and its answer was shaped' — the write itself is
   connector-sql's, and it has its own real-SQLite test."
  (atom []))

(defmethod spi/compile-mutation "test-inline"
  [_source-config _connection mutation-config]
  (fn [params]
    (swap! writes conj {:id (:id mutation-config) :params params})
    (case (:result_type mutation-config)
      "none" nil
      "list" [{:cis.master.vendor/vend_no (:cis.master.vendor/vend_no params)
               :cis.master.vendor/vend_name "written"}]
      {:cis.master.vendor/vend_no (:cis.master.vendor/vend_no params)
       :cis.master.vendor/vend_name "written"})))

(defmethod spi/compile-mutation "test-unwritable"
  [_source-config _connection _mutation-config]
  (throw (ex-info "this source cannot be written" {})))

(defn inline-model
  "把 fixture 模型的 source type 换成假连接器的 type。fixtures/ 不许改，
   所以换在内存里；resolver 的 `:source` 内联块也一并换（编译时两者合并）。"
  ([model] (inline-model model "test-inline"))
  ([model t]
   (-> model
       (update :sources update-vals #(assoc % :type t))
       (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] t) rs))))))
