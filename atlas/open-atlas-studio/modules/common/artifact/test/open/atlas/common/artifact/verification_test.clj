(ns open.atlas.common.artifact.verification-test
  "装载校验必须被**显式声明**，不许沉默跳过（D4.3/D4.11）。

   D4.3/D4.11 说 server「在暂存、装载与激活前都必须重算比对」。三样校验里
   只有 content-hash 能自证（manifest 自带它）；artifact-digest 与引用闭包
   都需要外部输入，而「入参缺席就跳过」把那个**必须**悄悄挪到了调用方身上
   ——调用方那一侧没有任何测试守着。默认沉默地少做一道校验就是静默降级。

   本 ns 钉住新的契约：二选一、必须选，以及 `:verified` 如实回报。"
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.artifact :as artifact]))

(def ^:private prov
  {:workspace "core-team" :graph "g" :version 1
   :cut-at "2026-08-22T00:00:00Z" :cut-by "verification-test"})

(def ^:private sealed
  (delay
    (let [{:keys [content-edn content-hash findings]} (artifact/encode-content {} [])]
      (assert (empty? findings) (pr-str findings))
      (artifact/seal-graph content-edn content-hash prov {} {} {:coverage 1.0}))))

(defn- load* [opts] (artifact/read-artifact (:files @sealed) opts))
(defn- codes [r] (set (map :code (:findings r))))

(def ^:private full
  "两样都真的验：digest 用密封时算出的那个值，闭包用一个解析函数
   （这份制品没有引用，因此该函数不会被调用，但**声明**是真的）。"
  (delay {:expected-digest (:artifact-digest @sealed)
          :resolve-reference (constantly nil)}))

;; --- 未声明的跳过 → 拒绝 -----------------------------------------------------

(deftest neither-evidence-nor-declaration-is-refused
  (testing "两样都不给 → 结构化拒绝，点名这是一次未声明的跳过"
    (let [r (load* {})]
      (is (nil? (:model r)) "契约没谈拢就不该交出模型")
      (is (= #{:artifact.verification/undeclared-skip} (codes r)))
      (is (= 2 (count (:findings r))) "digest 与闭包各报一条，不合并成一句含糊的话")
      (is (= #{[:expected-digest] [:resolve-reference]}
             (set (map :path (:findings r))))
          "点名的是缺了哪一样的依据"))))

(deftest an-undeclared-digest-skip-is-refused-even-when-the-closure-is-declared
  (let [r (load* {:closure :unverified})]
    (is (nil? (:model r)))
    (is (= #{:artifact.verification/undeclared-skip} (codes r)))
    (is (= [[:expected-digest]] (map :path (:findings r))))))

(deftest an-undeclared-closure-skip-is-refused-even-when-the-digest-is-verified
  (let [r (load* {:expected-digest (:artifact-digest @sealed)})]
    (is (nil? (:model r)))
    (is (= [[:resolve-reference]] (map :path (:findings r))))))

(deftest the-declaration-is-checked-before-anything-else
  (testing "契约违规先于制品的毛病：一份三文件全缺、内容不可解析的输入，
            未声明时报的仍然只是未声明——在契约谈拢之前不对制品下结论"
    (let [r (artifact/read-artifact {} {})]
      (is (= #{:artifact.verification/undeclared-skip} (codes r)))
      (is (empty? (filter #(= :artifact.integrity/missing-file (:code %)) (:findings r)))))))

;; --- 自相矛盾的声明 → 拒绝 ---------------------------------------------------

(deftest declaring-both-evidence-and-skip-is-refused
  (testing "两个都给 → 拒绝：替调用方挑一个才是真正危险的事"
    (doseq [opts [{:expected-digest (:artifact-digest @sealed) :digest :unverified
                   :closure :unverified}
                  {:resolve-reference (constantly nil) :closure :unverified
                   :digest :unverified}]]
      (let [r (load* opts)]
        (is (nil? (:model r)))
        (is (contains? (codes r) :artifact.verification/contradictory-declaration)
            (pr-str opts))))))

(deftest the-skip-declaration-has-exactly-one-legal-spelling
  (testing "`:unverified` 是唯一取值——第二种拼法会让「有没有声明」本身
            变成需要解释的事（D2.12 的同一条纪律）"
    (doseq [v [true false nil :skip :none "unverified"]]
      (let [r (load* {:digest v :closure :unverified})]
        (is (nil? (:model r)) (str "非法声明值必须被拒绝：" (pr-str v)))
        (is (contains? (codes r) :artifact.verification/undeclared-skip))))))

;; --- :verified 如实回报 ------------------------------------------------------

(deftest verified-reports-both-verifications-when-both-were-done
  (let [r (load* @full)]
    (is (some? (:model r)) (pr-str (:findings r)))
    (is (= {:content-hash :verified :artifact-digest :verified :references :verified}
           (:verified r)))))

(deftest verified-tells-the-truth-when-a-check-was-declared-away
  (testing "显式声明跳过时 `:verified` 如实反映——让「没验」在日志里看得见，
            而不是消失"
    (is (= {:content-hash :verified :artifact-digest :unverified :references :verified}
           (:verified (load* {:digest :unverified
                              :resolve-reference (constantly nil)}))))
    (is (= {:content-hash :verified :artifact-digest :verified :references :unverified}
           (:verified (load* {:expected-digest (:artifact-digest @sealed)
                              :closure :unverified}))))
    (is (= {:content-hash :verified :artifact-digest :unverified :references :unverified}
           (:verified (load* {:digest :unverified :closure :unverified}))))))

(deftest content-hash-is-always-verified-and-has-no-off-switch
  (testing "manifest 自带 content-hash，可自证，因此**永远**校验：
            即便两样外部校验都被声明为不验，被改过的 content.edn 仍然装不进来"
    (is (= :verified (:content-hash (:verified (load* {:digest :unverified
                                                       :closure :unverified})))))
    (let [tampered (update (:files @sealed) "content.edn" str "\n")
          r (artifact/read-artifact tampered {:digest :unverified :closure :unverified})]
      (is (nil? (:model r)))
      (is (contains? (set (map :code (:findings r)))
                     :artifact.integrity/content-hash-mismatch)))
    (testing "而且没有任何 opts 键能关掉它"
      (doseq [k [:content-hash :verify-content-hash :skip-content-hash]]
        (let [r (artifact/read-artifact (update (:files @sealed) "content.edn" str "\n")
                                        {:digest :unverified :closure :unverified k :unverified})]
          (is (nil? (:model r)) (str k " 不该成为一个开关")))))))

(deftest a-rejected-load-carries-no-verification-guarantee
  (testing "`:verified` 只在装载成功时给出——被拒的装载没有模型可供这份保证依附"
    (is (nil? (:verified (load* {}))))
    (is (nil? (:verified (artifact/read-artifact (dissoc (:files @sealed) "report.edn")
                                                 {:digest :unverified :closure :unverified}))))))

;; --- load-artifact 的默认声明 ------------------------------------------------

(deftest the-shelf-default-does-not-collide-with-an-explicit-declaration
  (testing "`load-artifact` 默认把闭包解析接到制品架上（那本身就是一次声明），
            但调用方自己声明时默认必须让位，否则会撞成一次自相矛盾的声明"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "atlas-b3-verify-" (into-array java.nio.file.attribute.FileAttribute [])))
          coords {:workspace "core-team" :graph "g" :version 1}]
      (try
        (artifact/write-artifact! root coords (:files @sealed))
        (testing "默认闭包声明 + 显式 digest 声明"
          (let [r (artifact/load-artifact root coords {:digest :unverified})]
            (is (some? (:model r)) (pr-str (:findings r)))
            (is (= :verified (:references (:verified r))))))
        (testing "调用方显式声明不解析闭包 → 默认让位，不产生矛盾声明"
          (let [r (artifact/load-artifact root coords {:digest :unverified
                                                       :closure :unverified})]
            (is (some? (:model r)) (pr-str (:findings r)))
            (is (= :unverified (:references (:verified r))))))
        (testing "digest 没有默认：制品架上没有可信的自报值可用（D4.11）"
          (let [r (artifact/load-artifact root coords {})]
            (is (nil? (:model r)))
            (is (= #{:artifact.verification/undeclared-skip} (codes r)))
            (is (= [[:expected-digest]] (map :path (:findings r))))))
        (finally
          (doseq [f (reverse (file-seq root))] (.delete f)))))))

;; --- finding 契约 ------------------------------------------------------------

(deftest verification-findings-are-well-formed-and-registered
  (doseq [f (concat (:findings (load* {}))
                    (:findings (load* {:expected-digest (:artifact-digest @sealed)
                                       :digest :unverified :closure :unverified}))
                    (:findings (load* {:digest :nonsense :closure :unverified})))]
    (is (artifact/finding? f) (pr-str f))
    (is (contains? artifact/finding-codes (:code f)))
    (is (= "artifact.verification" (namespace (:code f))))))
