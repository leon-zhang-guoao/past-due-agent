(ns open.atlas.server.http-stack.deps-security-test
  "解析出来的 Jetty 必须在 CVE 修复线之上。

   **这条测试才是从参考实现真正该借的东西**——不是那几个版本号。
   `ring-jetty-adapter` 传递地钉死内嵌 Jetty，而 ring 1.14.2 自己钉的是 12.0.21,
   低于两个 HIGH 的修复线：CVE-2026-2332（jetty-http，修在 12.0.33）与
   CVE-2026-10050（jetty-security / ee9-security，修在 12.0.36），外加
   CVE-2024-6763（MEDIUM，修在 12.0.12）。deps.edn 因此覆盖了适配器的四个直接
   Jetty 依赖。

   数字会过期，纪律不会：这条断言读的是**运行时真正加载的那个 Jetty**，
   树一旦回退（有人升 ring 却忘了同步覆盖、或覆盖被删掉）就当场红。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private cve-fix-floor
  "三条修复线取最大：max(12.0.12, 12.0.33, 12.0.36)。"
  [12 0 36])

(defn- parse-version
  "\"12.0.38\"（或带尾缀的 \"12.0.38.v…\"）→ [12 0 38]。尾缀忽略。"
  [s]
  (mapv parse-long (take 3 (str/split (str s) #"[.\-]"))))

(deftest jetty-is-past-the-cve-fix-floor
  (testing "内嵌 Jetty >= 12.0.36"
    (let [reported org.eclipse.jetty.util.Jetty/VERSION
          parsed   (parse-version reported)]
      (is (every? some? parsed) (str "解析不出版本：" reported))
      (is (>= (compare parsed cve-fix-floor) 0)
          (str "解析到的 Jetty " reported " 低于 CVE 修复线 "
               (str/join "." cve-fix-floor)
               "——多半是 ring 升级了而 deps.edn 里那四个覆盖没跟上")))))
