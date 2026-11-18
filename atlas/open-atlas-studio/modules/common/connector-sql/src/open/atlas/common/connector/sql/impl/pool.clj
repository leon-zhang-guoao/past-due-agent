(ns open.atlas.common.connector.sql.impl.pool
  "HikariCP 连接池。

   池按**连接键的指纹**缓存，不按 source id：凭证轮换后指纹变，新指纹开新池，
   旧池不会被继续端出去（参考实现在这里踩过「轮换后密码不生效」）。
   指纹是一个 `hash`，是数字——注册表里因此没有任何凭证明文。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.sql.impl.envsub :as envsub]
            [open.atlas.common.connector.sql.impl.paths :as paths]
            [open.atlas.common.connector.spi :as spi])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defonce ^:private pools (atom {}))
(defonce ^:private lock (Object.))

(defn connection-config
  "把 source-config 的连接键做 `${VAR}` 替换。
   → `{:jdbc-url … :username … :password … :max-pool-size n :min-idle n
       :missing [未解析的变量名 …] :anchored-path 绝对路径或 nil}`
   `:anchored-path` 只在 sqlite 相对路径被锚定时有值（doc 104 §8.17.7 B）。"
  ([source-config] (connection-config source-config envsub/env-lookup))
  ([{:keys [jdbc_url username password max_pool_size min_idle]} lookup]
   (let [u (envsub/substitute jdbc_url lookup)
         ;; doc 104 §8.17.7 B: a relative sqlite path is anchored under
         ;; $OPEN_ATLAS_CONFIG_PATH here — the one place the JDBC URL is
         ;; finalised, AFTER ${VAR} substitution — so describe-schema,
         ;; health-check and query all see the same file.
         anchored (paths/anchor-sqlite-url (:value u))
         n (envsub/substitute username lookup)
         p (envsub/substitute password lookup)
         max-pool (or max_pool_size 10)]
     {:jdbc-url      (:url anchored)
      :anchored-path (:anchored anchored)
      :username      (:value n)
      :password      (:value p)
      :max-pool-size max-pool
      :min-idle      (min (or min_idle 2) max-pool)
      :missing       (vec (concat (:missing u) (:missing n) (:missing p)))})))

(def application-name
  "本进程对外自称的名字 —— **SPI 导出的那一个**，不另写。

   它曾经住在这里，但它是**平台**的身份不是 sql 的概念：api 连接器要在
   `User-Agent` 里说同一个名字，而对方运维对账靠的正是这个名字。两份实现的
   那天，同一台机器会在 PG 里叫一个名字、在 HTTP 日志里叫另一个
   （CLAUDE.md：`redact` 与档位词表都出过这种事）。绑 instance id 请 `binding`
   `spi/*instance-id*`。"
  spi/application-name)

(defn app-name-plan
  "E2：这条 JDBC URL 该怎么打标。

   → `{:properties {属性名 值}}`，或 driver 不认识时
   `{:findings [{:code :connector.source/untagged-driver …}]}`。

   **不认识就不猜**：把一个 driver 不认的属性塞进 HikariConfig，它在建池时
   直接抛——那会把「没打上标」变成「连不上库」，代价与收益完全不成比例。
   报一条 `:info`，连接照开。"
  [jdbc-url]
  (let [u    (str jdbc-url)
        name (application-name)]
    (cond
      (str/starts-with? u "jdbc:postgresql:")
      {:properties {"ApplicationName" name}}

      (str/starts-with? u "jdbc:sqlserver:")
      {:properties {"applicationName" name}}

      (or (str/starts-with? u "jdbc:mysql:")
          (str/starts-with? u "jdbc:mariadb:"))
      {:properties {"connectionAttributes" (str "program_name:" name)}}

      :else
      {:findings [{:code :connector.source/untagged-driver
                   :severity :info
                   :path [:source :jdbc_url]
                   :message (str "this driver has no known application-name property; "
                                 "connections from Open Atlas are not tagged at the "
                                 "connection level (statements still carry the "
                                 "request-id comment)")
                   :refs ["E2"]}]})))

(defn- tag-application-name! [^HikariConfig cfg jdbc-url]
  (doseq [[k v] (:properties (app-name-plan jdbc-url))]
    (.addDataSourceProperty cfg k v))
  cfg)

(defn- open-pool ^HikariDataSource [{:keys [jdbc-url username password max-pool-size min-idle]} fp]
  (HikariDataSource.
   (doto (HikariConfig.)
     (.setJdbcUrl jdbc-url)
     (cond-> username (.setUsername username))
     (cond-> password (.setPassword password))
     (.setMaximumPoolSize max-pool-size)
     (.setMinimumIdle min-idle)
     ;; 取连接本身也要有界；它与语句超时是两回事（Hikari 的 connectionTimeout
     ;; 只管拿到连接，管不了拿到之后跑多久）。
     (.setConnectionTimeout 5000)
     ;; E2：连接级打标。driver 不认识就一个属性都不加（见 app-name-plan）。
     (tag-application-name! jdbc-url)
     (.setPoolName (str "atlas-sql-" fp)))))

(defn datasource
  "取（或开）连接池。同一份连接配置只开一个池。"
  [conn-config]
  (let [fp (hash [(select-keys conn-config [:jdbc-url :username :password
                                            :max-pool-size :min-idle])
                  ;; application name 也进指纹：宿主换了 instance-id，旧池上的
                  ;; 连接仍自称旧名字，继续端出去就是在源库日志里指错机器。
                  (application-name)])]
    (or (get @pools fp)
        (locking lock
          (or (get @pools fp)
              (let [ds (open-pool conn-config fp)]
                (swap! pools assoc fp ds)
                ds))))))

(defn close-all!
  "关掉全部池并忘记它们（进程退出、热重载、测试收尾）。返回关掉的个数。"
  []
  (let [m (locking lock (let [m @pools] (reset! pools {}) m))]
    (doseq [[_ ^HikariDataSource ds] m]
      (try (.close ds) (catch Exception _ nil)))
    (count m)))
