-- B2 demo 库的 source 侧（SQLite）。`cis-sqlite` chart 转换 + 补充后的产物打在它上面。
--
-- 数据**刻意小而有结构**（同 fixtures/b1/schema.sql 的路子）：够演示，不臃肿。
-- 参考实现那份库是 8.7 MB / 51,683 行 / 导出 12.5 MB SQL——**不进本仓库**。
--
-- 行数据在 seed.sql，由 generate.py 确定性生成（每次重跑逐字节相同）。
-- **数据全部是合成的**：金额、日期、名称像真的，但没有一条来自 owner 的业务数据。
--
-- ── 业务链 ──────────────────────────────────────────────────────────────────
--
--   CPO ─→ MSO ─┬─ 正常履约 ───────────────────→ SSO   (sso.int_ref → MSO)
--               └─ dropship → VPO ─(供应商直发)─→ SSO   (sso.int_ref → VPO)
--
--   cpo_header / cpo_detail   客户的采购订单（链条起点）
--   msos / mso_order_details  我们的销售合同，**全量订货行**
--   vpos / vpo_order_details  向供应商下的采购单；dropship 时供应商直发给客户
--   sso  / sso_order_detail   每次发货一张（履约进度）
--
-- ── int_ref_no / int_ref_type ───────────────────────────────────────────────
--
-- `int_ref_type` **不是建模概念**：方言里没有它的值域、也没有码值表。它就是调用
-- 时写死在 SQL 里的字面量，含义写在用到它的那条 resolver 旁边
-- （fixtures/b2/additions/cis-sqlite.additions.edn）。本库用到三处：
--
--   msos.int_ref_no  → cpo_header.order_no   int_ref_type = 4
--   vpos.int_ref_no  → msos.order_no         int_ref_type = 3   （dropship 采购单）
--   sso.int_ref_no   → msos.order_no         int_ref_type = 3   （仓库发货）
--                    → vpos.order_no         int_ref_type = 2   （dropship，供应商直发）
--
-- `sso.int_ref_no` 那一条是**多态**引用：指向什么由 int_ref_type 决定。它因此
-- **不能建成 link**——`:condition` 是纯文字槽、不进入执行语义（D2.3），条件根本
-- 不会被执行，指向 MSO 的 SSO 会被静默地错接到 VPO 上。两条导航都建成显式 join
-- resolver，`int_ref_type = ?` 写进 WHERE。理由与出处见 additions 文件的抬头。
--
-- ── 号段：四类单据各占一段，**互不重叠** ───────────────────────────────────
--
--   cpo_header.order_no  210,000,001 –            9 位（参考库无 cpo，另取一段）
--   msos.order_no        143,100,001 –            9 位（真实区间 142.4M – 146.0M）
--   sso.order_no         160,500,001 –            9 位（真实区间 128.2M – 168.4M）
--   vpos.order_no         32,700,101 –            8 位（真实区间  32.70M – 32.71M）
--   parts.sku_no           5,100,001 –            7 位（真实区间   4.0M –  9.6M）
--
-- 号段分开是数据自带的**一道自检**：号段重叠时，一条接错的 join 仍然「查得出
-- 东西」，bug 于是变成静默的；分开之后接错了就查不到，错误会自己现形。
--
-- ── 类型一致 ────────────────────────────────────────────────────────────────
--
-- **同一个业务键在所有表里都是同一个类型**，全部 INTEGER。参考库里
-- `parts.sku_no` 是 TEXT 而 `sso_order_detail.sku_no` 是 INTEGER——我们不复制那个
-- 毛病。connector-sql 的 `join-key` 归一**不因此放松**，它守的是「连上真实的、
-- 类型不一致的库」那种情况，而那种库确实存在。详见 README.md。

-- ── 主数据 ──────────────────────────────────────────────────────────────────

CREATE TABLE vendors (
  vend_no      INTEGER PRIMARY KEY,
  vend_name    TEXT    NOT NULL,
  vend_type    TEXT,
  primary_loc  INTEGER,
  discontinued TEXT    NOT NULL,
  entry_id       TEXT,
  entry_datetime TEXT,
  u_version      TEXT
);

CREATE TABLE customers (
  cust_no       INTEGER PRIMARY KEY,
  cust_name     TEXT    NOT NULL,
  cust_type     TEXT,
  sales_terr    TEXT,
  default_terms TEXT,
  tax_status    TEXT,
  entry_id       TEXT,
  entry_datetime TEXT,
  u_version      TEXT
);

CREATE TABLE customer_credit (
  cust_no       INTEGER PRIMARY KEY REFERENCES customers(cust_no),
  terms         TEXT,
  credit_limit  NUMERIC,   -- :restricted（signoff）
  curr_bal      NUMERIC,   -- :sensitive
  curr_pymts    NUMERIC,   -- :sensitive
  pending_amt   NUMERIC,   -- :sensitive
  past_due_amt  NUMERIC,   -- :sensitive
  past_due_date TEXT,      -- :sensitive
  last_pay_date TEXT,
  restricted    TEXT,
  sold_since    TEXT,
  entry_id       TEXT,
  entry_datetime TEXT,
  u_version      TEXT
);

CREATE TABLE customer_bank (
  cust_no      INTEGER NOT NULL REFERENCES customers(cust_no),
  line_no      INTEGER NOT NULL,
  type         TEXT,
  bank_name    TEXT,
  bank_acct    TEXT,      -- :sensitive（整族）
  bank_aba     TEXT,
  acct_type    TEXT,
  bank_address TEXT,
  swift_code   TEXT,
  iban_number  TEXT,
  bank_def     TEXT,
  date_opened  TEXT,
  PRIMARY KEY (cust_no, line_no)
);

-- sku_no 是 INTEGER —— 见抬头「类型一致」那一节。
CREATE TABLE parts (
  sku_no     INTEGER PRIMARY KEY,
  part_no    TEXT    NOT NULL,
  short_desc TEXT,
  ave_cost   NUMERIC,
  prod_code  TEXT,
  vend_no    INTEGER REFERENCES vendors(vend_no),
  entry_id       TEXT,
  entry_datetime TEXT,
  u_version      TEXT
);

-- ── CPO：客户的采购订单，链条的起点 ─────────────────────────────────────────
-- `order_no` 存的是**客户给的采购订单号**，`msos.int_ref_no` 指向它。

CREATE TABLE cpo_header (
  order_no           INTEGER PRIMARY KEY,
  cpo_cust_no        INTEGER REFERENCES customers(cust_no),
  cpo_date           TEXT,
  cpo_status         TEXT,
  cpo_priority       TEXT,
  po_total           NUMERIC,
  cpo_entry_id       TEXT,
  cpo_entry_datetime TEXT
);

CREATE TABLE cpo_detail (
  order_no        INTEGER NOT NULL REFERENCES cpo_header(order_no),
  cpo_line_seq    INTEGER NOT NULL,
  cpo_sku_no      INTEGER REFERENCES parts(sku_no),
  cpo_line_qty    INTEGER,
  cpo_unit_price  NUMERIC,
  cpo_price       NUMERIC,
  cpo_line_status TEXT,
  PRIMARY KEY (order_no, cpo_line_seq)
);

-- ── MSO：我们的销售合同。int_ref_no → cpo_header.order_no（int_ref_type = 4）──

CREATE TABLE msos (
  order_no     INTEGER PRIMARY KEY,
  order_type   INTEGER,
  ship_to_name TEXT,
  ship_to_city TEXT,
  total_order  NUMERIC,
  sales_total  NUMERIC,
  sales_terr   TEXT,
  to_acct_no   INTEGER REFERENCES customers(cust_no),
  int_ref_no   INTEGER,   -- → cpo_header.order_no
  int_ref_type INTEGER,   -- 4 = 指向 CPO
  issue_date   TEXT,
  entry_id        TEXT,
  entry_datetime  TEXT,
  update_id       TEXT,
  update_datetime TEXT,
  u_version       TEXT,
  ship_to_addr    TEXT,
  ship_to_state   TEXT,
  ship_to_zip     TEXT,
  ship_to_country TEXT,
  -- 仓库作业时间线。**未发生的步骤是 NULL，NULL 本身是演示素材**：
  -- 未发货的 6 张五个时点全空，部分发货的 8 张开到 ship_date、bol_date 空
  -- 全部发货的 6 张五个都有。顺序恒为 issue < pick < qc < label < ship < bol。
  pick_date       TEXT,
  qc_date         TEXT,
  label_date      TEXT,
  ship_date       TEXT,
  bol_date        TEXT,
  -- 放行控制：被扣住的单据 hold_id / hold_date 有值、credit_rel_date 为空
  -- approval = 'N'。放行过的两张三个字段都有值。
  approval        TEXT,
  hold_id         TEXT,
  hold_date       TEXT,
  credit_rel_date TEXT
);

-- MSO 的**全量订货行**。`ship_qty` 是这条行项**累计**发出去的量，跨全部 SSO
-- （无论从我们仓库发的还是供应商直发的）。履约进度 = ship_qty / order_qty。
CREATE TABLE mso_order_details (
  order_no       INTEGER NOT NULL REFERENCES msos(order_no),
  line_no        INTEGER NOT NULL,
  order_type     INTEGER,
  sku_no         INTEGER REFERENCES parts(sku_no),
  inv_type       INTEGER,
  order_qty      INTEGER,   -- 合同订货量
  ship_qty       INTEGER,   -- 累计已发（≤ order_qty）
  rec_qty        INTEGER,
  unit_cost      NUMERIC,   -- :restricted（signoff）
  unit_price     NUMERIC,
  net_price      NUMERIC,
  sys_new_cost   NUMERIC,   -- :restricted（signoff）
  claim_new_cost NUMERIC,   -- :restricted（signoff）
  release_id     TEXT,
  entry_id       TEXT,
  entry_datetime TEXT,
  PRIMARY KEY (order_no, line_no)
);

-- ── VPO：向供应商下的采购单。dropship 时供应商**直接发货给客户** ────────────
-- 所以 `to_acct_no` 是客户账号，不是我们的仓库。int_ref_no → msos.order_no。

CREATE TABLE vpos (
  order_no      INTEGER PRIMARY KEY,
  order_type    INTEGER,
  total_cost    NUMERIC,
  freight       NUMERIC,
  ship_to_name  TEXT,
  ship_to_city  TEXT,
  ship_to_state TEXT,
  issue_date    TEXT,
  expected_date TEXT,
  from_acct_no  INTEGER REFERENCES vendors(vend_no),
  to_acct_no    INTEGER REFERENCES customers(cust_no),
  int_ref_no    INTEGER,   -- → msos.order_no
  int_ref_type  INTEGER,   -- 3 = 指向 MSO
  entry_id        TEXT,
  entry_datetime  TEXT,
  update_id       TEXT,
  update_datetime TEXT,
  u_version       TEXT,
  ship_to_addr       TEXT,
  ship_to_zip        TEXT,
  -- 收货时间线的后两个时点：货实际到了、单子关掉。还没到货的 VPO 两者皆空。
  receiving_date     TEXT,
  closed_date        TEXT,
  total_order        NUMERIC,
  detail_price_total NUMERIC
);

CREATE TABLE vpo_order_details (
  order_no     INTEGER NOT NULL REFERENCES vpos(order_no),
  line_no      INTEGER NOT NULL,
  sku_no       INTEGER REFERENCES parts(sku_no),
  vend_part_no TEXT,
  order_qty    INTEGER,
  ship_qty     INTEGER,
  unit_cost    NUMERIC,
  PRIMARY KEY (order_no, line_no)
);

CREATE TABLE vpo_eta_detail (
  order_no       INTEGER NOT NULL REFERENCES vpos(order_no),
  line_no        INTEGER NOT NULL,
  sku_no         INTEGER REFERENCES parts(sku_no),
  original_eta   TEXT,
  current_eta    TEXT,
  actual_arrival TEXT,
  eta_status     TEXT,
  carrier_no     INTEGER,
  tracking_no    TEXT,
  delay_days     INTEGER,
  updated_date   TEXT,
  PRIMARY KEY (order_no, line_no)
);

-- ── SSO：每次发货一张。**int_ref_no 是多态的**，见抬头 ───────────────────────

CREATE TABLE sso (
  order_no      INTEGER PRIMARY KEY,
  order_type    INTEGER,
  ext_ref       TEXT,
  ship_to_name  TEXT,
  ship_to_city  TEXT,
  ship_to_state TEXT,
  ship_date     TEXT,
  issue_date    TEXT,
  invoice_date  TEXT,
  to_acct_no    INTEGER REFERENCES customers(cust_no),
  cust_no       INTEGER REFERENCES customers(cust_no),
  total_order   NUMERIC,
  total_cost    NUMERIC,
  sales_total   NUMERIC,
  total_weight  INTEGER,
  drop_ship     TEXT,
  -- 多态：int_ref_type = 3 时指向 msos.order_no（仓库发货）；
  --       int_ref_type = 2 时指向 vpos.order_no（dropship，供应商直发）。
  -- 因此**没有 FOREIGN KEY**：目标表由 int_ref_type 决定，SQL 的外键表达不了。
  -- 这正是它在方言里也表达不成 link 的同一个理由（D2.3）。
  int_ref_no    INTEGER,
  int_ref_type  INTEGER
);

CREATE TABLE sso_order_detail (
  order_no   INTEGER NOT NULL REFERENCES sso(order_no),
  line_no    INTEGER NOT NULL,
  sku_no     INTEGER REFERENCES parts(sku_no),
  order_qty  INTEGER,
  ship_qty   INTEGER,
  unit_cost  NUMERIC,   -- :sensitive
  unit_price NUMERIC,   -- :sensitive
  net_price  NUMERIC,   -- :sensitive
  PRIMARY KEY (order_no, line_no)
);
