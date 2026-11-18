(ns open.atlas.common.dialect.impl.vocab
  "受控词表常量（dialect-v1 §3）。实现顺序第 1 步（与 finding 同批）。
   词表外的值在写入端拒绝；词表增补是契约变更，不是代码变更。")

(def attribute-types  #{:string :integer :decimal :boolean :date :datetime :json})
(def formats          #{:id :code :currency :percent :number :date :datetime :text})
(def result-types     #{"single" "list"})
(def mutation-result-types #{"none" "single" "list"})
(def ops              #{:eq :in :lt :lte :gt :gte :like})
(def link-directions  #{:bi :one-way})                     ; 缺省 :bi 不书写
(def cardinalities    #{:one-to-one :one-to-many})
(def reserved-call-names #{:limit :offset :order_by :sort_dir})

(def namespace-kinds  #{:master :transaction :reference :event :bridge})  ; D2.7 词表
(def metric-grains    #{:day :week :month :quarter :year})                ; D2.7/D2.21，缺省缺席

(def format->types
  "D2.27 :format × :type 合法组合。"
  {:id       #{:string :integer}
   :code     #{:string :integer :boolean}
   :currency #{:decimal}
   :percent  #{:decimal}
   :number   #{:integer :decimal}
   :date     #{:date}
   :datetime #{:datetime}
   :text     #{:string}})

(def values-capable-types
  "仅这些类型可声明 :values 或绑定带 :values 的词条（D2.27）。"
  #{:string :integer :boolean})

(def defaults
  "缺省值表：writer 不书写、reader 见到书写即拒绝（唯一规范形）。"
  {:curated?    false
   :direction   :bi
   :required?   false
   ;; D2.28：缺省列出。写 `:listed? true` 就是把缺省值写出来，规范形下即拒绝——
   ;; 于是「不列出」永远是一次**显式**声明，而「列出」不占任何字节。
   :listed?     true})
