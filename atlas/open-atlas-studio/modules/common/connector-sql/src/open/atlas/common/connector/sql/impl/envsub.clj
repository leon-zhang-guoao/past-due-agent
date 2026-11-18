(ns open.atlas.common.connector.sql.impl.envsub
  "`${VAR}` 替换与字面凭证检查。

   sql-v1「连接 schema」：`:jdbc_url` 必填、凭证键，「可含凭证成分，只收
   `${VAR}` 或无凭证 URL」；`:username`/`:password`「只收 `${VAR}`」。
   「凭证键不可被 resolver 内联 `:source` 覆盖（D2.15）；**字面值在写入端拒绝**。」

   写入端拒绝 = validate-source 拒绝。运行期只做替换：这时再拒绝已经晚了，
   而且方言里存的本来就该是占位符（fixtures/b1 README：「凭证与连接串永不写
   字面值」）。"
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]))

;; One pattern for the whole platform, on the seam (doc 104 §8.17.7 A): the
;; package block scans EVERY connector's config for the same `${VAR}`, and a
;; second copy here is a copy to forget when one of them is tightened.
(def var-pattern spi/var-pattern)

(defn only-placeholder?
  "整个值恰好是一个 `${VAR}`——`:username`/`:password` 的唯一合法形状。"
  [s]
  (boolean (and (string? s) (re-matches var-pattern s))))

(def var-names
  "The `${VAR}` names in a string. Same reading as the package face uses."
  spi/var-names)

(defn- strip-vars [s] (str/replace (str s) var-pattern ""))

(def ^:private credential-in-url
  "URL 里的凭证成分：`//user:pass@host` 的 userinfo，或查询串上的
   user/username/password/pwd。只在**去掉 `${VAR}` 之后的字面部分**上查——
   `jdbc:x://${U}:${P}@h/db` 的凭证在环境变量里，那是合法形状。"
  [#"//[^/@\s]+:[^/@\s]+@" #"(?i)[?&;](user|username|password|pwd)=[^&;\\s]"])

(defn url-has-literal-credential?
  [url]
  (let [literal (strip-vars url)]
    (boolean (some #(re-find % literal) credential-in-url))))

(defn substitute
  "把 `${VAR}` 换成 `lookup` 给的值。
   → `{:value 替换后 :missing [未解析的变量名 …]}`。
   未解析**不**留字面占位符：留着会让一个明明没配好的连接串被当成主机名拨出去，
   失败点漂到驱动里，故障类别也就跟着错。调用方看见 `:missing` 就报
   `:connection`。"
  [s lookup]
  (if-not (string? s)
    {:value s :missing []}
    (let [missing (volatile! [])
          v (str/replace s var-pattern
                         (fn [[_ n]]
                           (or (lookup n)
                               (do (vswap! missing conj n) ""))))]
      {:value v :missing @missing})))

(defn env-lookup
  "缺省来源：宿主绑定的 `spi/*config-lookup*`——未绑定即进程环境变量。
   Studio 把它绑成「凭证库优先、进程环境其次」（doc 106 §106.14-12），
   两个连接器因此对同一个 `${VAR}` 给同一个答案。"
  [n]
  (spi/*config-lookup* n))
