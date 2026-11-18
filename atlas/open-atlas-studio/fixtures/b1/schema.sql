-- B1 竖切的 source 侧种子（SQLite，内存库）。
-- 数据刻意小而有结构：vendor 3 条（含一条 discontinued），part 5 条分属
-- 两个 vendor 且有一个 vendor 无 part——batch 对齐「没有行的父项得 []/nil，
-- 永不丢位」需要这个空位才测得出来。

CREATE TABLE vendors (
  vend_no       INTEGER PRIMARY KEY,
  vend_name     TEXT    NOT NULL,
  discontinued  TEXT    NOT NULL,
  contact_email TEXT,
  entry_by      TEXT,
  entry_date    TEXT
);

CREATE TABLE parts (
  sku_no     INTEGER PRIMARY KEY,
  part_no    TEXT    NOT NULL,
  short_desc TEXT,
  ave_cost   NUMERIC,
  prod_code  TEXT,
  vend_no    INTEGER REFERENCES vendors(vend_no)
);

INSERT INTO vendors (vend_no, vend_name, discontinued, contact_email, entry_by, entry_date) VALUES
  (1001, 'Acme Industrial',   'N', 'ap@acme.example',    'jgao',  '2024-03-11'),
  (1002, 'Beta Components',   'N', 'sales@beta.example', 'lchen', '2024-07-02'),
  (1003, 'Gamma Supply (old)','Y', NULL,                 'jgao',  '2023-01-19');

-- vendor 1003 刻意没有 part：batch 与 navigation 的空位用例。
INSERT INTO parts (sku_no, part_no, short_desc, ave_cost, prod_code, vend_no) VALUES
  (5001, 'ACM-100', 'Bearing 10mm',      12.50,  'R', 1001),
  (5002, 'ACM-220', 'Bearing 22mm',      18.75,  'R', 1001),
  (5003, 'ACM-900', 'Gearbox assembly', 340.00,  'A', 1001),
  (5004, 'BET-001', 'Cable loom',        47.25,  'A', 1002),
  (5005, 'BET-002', 'Connector kit',      9.90,  'R', 1002);
