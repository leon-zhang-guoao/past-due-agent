-- DVD 出租店的 source 侧（SQLite）。**面向使用者的那个例子**——下载、启动、
-- 第一眼看到的就是它（8.14）。
--
-- 为什么另起一套而不是复用 cis：`fixtures/demo` 那份是**六个模块测试每天穿过
-- 的装料**，动它就是动测试的既成事实（CLAUDE.md 明令禁止为让测试变绿去改
-- fixtures）。而演示要的是「一眼看懂」——租碟、还碟、收钱，不需要先解释什么是
-- MSO。两者因此并存：cis 是测试的真相，dvd 是使用者的第一课。
--
-- 数据**全部是合成的**，由 generate.py 确定性生成（每次重跑逐字节相同）。
-- 形状取自业界熟知的 DVD 租赁样例库的结构，数据一行都不是从那里来的。
--
-- ── 业务链 ──────────────────────────────────────────────────────────────────
--
--   customer ─→ rental ─→ inventory ─→ film ─→ category
--                  │                     └──→ film_actor ─→ actor
--                  └─→ payment
--
--   film / category / actor / film_actor   片库：一部片、它的分类、它的演员
--   store / staff                          门店与店员
--   inventory                              一张实体碟：某部片在某个门店的一份拷贝
--   rental                                 一次借出（return_date 为空 = 还没还）
--   payment                                一次收款，挂在一次借出上
--
-- ── 两处刻意留下的形状 ──────────────────────────────────────────────────────
--
-- 1. `rental.return_date` 可为空，且**空是答案**：未归还不是缺失数据，是「这张
--    碟还在客户手里」。演示 outer join 与「空值即事实」时用得上。
-- 2. `film.rental_rate` 与 `payment.amount` 不必相等——续租、滞纳金、店员改价
--    都会让它们分开。把 payment 当成 rental_rate 的复制品会算错收入。

CREATE TABLE category (
  category_id   INTEGER PRIMARY KEY,
  name          TEXT    NOT NULL,
  last_update   TEXT    NOT NULL
);

CREATE TABLE film (
  film_id       INTEGER PRIMARY KEY,
  title         TEXT    NOT NULL,
  description   TEXT,
  release_year  INTEGER NOT NULL,
  category_id   INTEGER NOT NULL REFERENCES category(category_id),
  rental_rate   REAL    NOT NULL,   -- 挂牌日租金
  rental_duration INTEGER NOT NULL, -- 标准租期（天）
  length        INTEGER,            -- 片长（分钟）
  rating        TEXT    NOT NULL,   -- G / PG / PG-13 / R
  last_update   TEXT    NOT NULL
);

CREATE TABLE actor (
  actor_id      INTEGER PRIMARY KEY,
  first_name    TEXT    NOT NULL,
  last_name     TEXT    NOT NULL,
  last_update   TEXT    NOT NULL
);

CREATE TABLE film_actor (
  film_id       INTEGER NOT NULL REFERENCES film(film_id),
  actor_id      INTEGER NOT NULL REFERENCES actor(actor_id),
  billing_order INTEGER NOT NULL,  -- 1 = 主演
  PRIMARY KEY (film_id, actor_id)
);

CREATE TABLE store (
  store_id      INTEGER PRIMARY KEY,
  name          TEXT    NOT NULL,
  city          TEXT    NOT NULL,
  opened_on     TEXT    NOT NULL
);

CREATE TABLE staff (
  staff_id      INTEGER PRIMARY KEY,
  first_name    TEXT    NOT NULL,
  last_name     TEXT    NOT NULL,
  store_id      INTEGER NOT NULL REFERENCES store(store_id),
  active        INTEGER NOT NULL,
  hired_on      TEXT    NOT NULL
);

CREATE TABLE customer (
  customer_id   INTEGER PRIMARY KEY,
  first_name    TEXT    NOT NULL,
  last_name     TEXT    NOT NULL,
  email         TEXT    NOT NULL,
  store_id      INTEGER NOT NULL REFERENCES store(store_id),
  active        INTEGER NOT NULL,
  created_on    TEXT    NOT NULL
);

CREATE TABLE inventory (
  inventory_id  INTEGER PRIMARY KEY,
  film_id       INTEGER NOT NULL REFERENCES film(film_id),
  store_id      INTEGER NOT NULL REFERENCES store(store_id),
  acquired_on   TEXT    NOT NULL
);

CREATE TABLE rental (
  rental_id     INTEGER PRIMARY KEY,
  rental_date   TEXT    NOT NULL,
  inventory_id  INTEGER NOT NULL REFERENCES inventory(inventory_id),
  customer_id   INTEGER NOT NULL REFERENCES customer(customer_id),
  staff_id      INTEGER NOT NULL REFERENCES staff(staff_id),
  return_date   TEXT             -- NULL = 还在客户手里，见文件头
);

CREATE TABLE payment (
  payment_id    INTEGER PRIMARY KEY,
  rental_id     INTEGER NOT NULL REFERENCES rental(rental_id),
  customer_id   INTEGER NOT NULL REFERENCES customer(customer_id),
  staff_id      INTEGER NOT NULL REFERENCES staff(staff_id),
  amount        REAL    NOT NULL,
  payment_date  TEXT    NOT NULL
);

CREATE INDEX idx_film_category   ON film (category_id);
CREATE INDEX idx_inventory_film  ON inventory (film_id);
CREATE INDEX idx_rental_customer ON rental (customer_id);
CREATE INDEX idx_rental_inventory ON rental (inventory_id);
CREATE INDEX idx_payment_rental  ON payment (rental_id);
