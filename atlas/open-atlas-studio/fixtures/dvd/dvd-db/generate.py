#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dvd-db 的**确定性**种子生成器 —— 产出 seed.sql。

    python3 fixtures/dvd/dvd-db/generate.py

与 fixtures/demo/demo-db/generate.py 同一套纪律，理由也一样：

- 产物 `seed.sql` 与本脚本**一起进仓库**。脚本说明数据是怎么造出来的（业务规则
  写在代码里，读得到），seed.sql 让人不必装 Python、也不必信任生成器。两者不一致
  时以脚本为准——重跑一遍即可。
- 「逐字节相同」是硬要求，所以**不用 `random` 模块**：标准库的 Mersenne Twister
  在不同 Python 版本间的行为不是契约。用一个写死的 LCG。
- 数据是**合成的**。片名、人名、金额像真的，但没有一条来自任何真实数据集——
  形状借自业界熟知的 DVD 租赁样例库，数据一行都不是从那里来的。

规模刻意小而有结构：够演示每一条链（客户→借出→碟→片→分类、借出→收款、
片→演员），不臃肿。
"""

import io
import os


class Rng:
    """自带 LCG（glibc 的常数）。跨版本、跨机器逐字节确定。"""

    def __init__(self, seed):
        self.s = seed & 0x7FFFFFFF

    def next(self):
        self.s = (1103515245 * self.s + 12345) & 0x7FFFFFFF
        return self.s

    def below(self, n):
        return self.next() % n

    def pick(self, items):
        return items[self.below(len(items))]


def q(value):
    """SQL 字面量。None → NULL，字符串转义单引号。"""
    if value is None:
        return "NULL"
    if isinstance(value, (int,)):
        return str(value)
    if isinstance(value, float):
        return "%.2f" % value
    return "'" + str(value).replace("'", "''") + "'"


def rows(table, columns, records):
    out = ["-- %s (%d)" % (table, len(records))]
    for record in records:
        out.append(
            "INSERT INTO %s (%s) VALUES (%s);"
            % (table, ", ".join(columns), ", ".join(q(v) for v in record))
        )
    out.append("")
    return out


# --- 词表：合成，但读起来像真的 ---------------------------------------------

CATEGORIES = ["Action", "Comedy", "Documentary", "Drama", "Family",
              "Horror", "Sci-Fi", "Classics"]

TITLE_HEAD = ["Autumn", "Crimson", "Silent", "Northern", "Golden", "Broken",
              "Electric", "Midnight", "Paper", "Iron", "Velvet", "Distant"]
TITLE_TAIL = ["Harbour", "Engine", "Kingdom", "Circuit", "Orchard", "Signal",
              "Compass", "Lantern", "Voyage", "Meridian", "Anthem", "Archive"]

FIRST_NAMES = ["Ada", "Bruno", "Clara", "Dmitri", "Elena", "Farid", "Greta",
               "Hugo", "Ines", "Jonas", "Kaori", "Liam", "Mira", "Noor",
               "Otto", "Petra", "Quinn", "Rosa", "Samir", "Tessa"]
LAST_NAMES = ["Aalto", "Bergman", "Costa", "Duarte", "Ekwueme", "Fontaine",
              "Grimaldi", "Haas", "Ibarra", "Jansen", "Keller", "Lindqvist",
              "Moreau", "Nakamura", "Okafor", "Prieto", "Rossi", "Strand",
              "Tanaka", "Vargas"]

RATINGS = ["G", "PG", "PG-13", "R"]
STORES = [(1, "Central Branch", "Rotterdam", "2019-03-04"),
          (2, "Riverside Branch", "Utrecht", "2021-06-17")]

STAMP = "2026-01-05T09:00:00Z"


def day(index):
    """2025-01-01 起的第 index 天，YYYY-MM-DD。自己算，不用 datetime——
    闰年规则在这个窗口里只有 2028，窗口内用不到。"""
    month_lengths = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    year, remaining = 2025, index
    while True:
        year_length = 365
        if remaining < year_length:
            break
        remaining -= year_length
        year += 1
    month = 0
    while remaining >= month_lengths[month]:
        remaining -= month_lengths[month]
        month += 1
    return "%04d-%02d-%02d" % (year, month + 1, remaining + 1)


def main():
    rng = Rng(20260901)
    lines = [
        "-- 由 generate.py 确定性生成，**不要手改**（改了下一次重跑就没了）。",
        "-- 数据全部是合成的：没有一条来自任何真实数据集。",
        "",
    ]

    # 分类
    categories = [(i + 1, name, STAMP) for i, name in enumerate(CATEGORIES)]
    lines += rows("category", ["category_id", "name", "last_update"], categories)

    # 片库：48 部，标题由两张词表组合，组合唯一
    films = []
    film_id = 1
    for head in TITLE_HEAD:
        for tail_index in range(4):
            tail = TITLE_TAIL[(TITLE_HEAD.index(head) + tail_index * 3) % len(TITLE_TAIL)]
            category_id = 1 + rng.below(len(CATEGORIES))
            # 日租金与租期相关：便宜的片租得久，新片贵而租期短。
            rate = rng.pick([0.99, 1.99, 2.99, 3.99, 4.99])
            duration = 7 if rate <= 1.99 else (5 if rate <= 3.99 else 3)
            films.append((
                film_id,
                "%s %s" % (head, tail),
                "A %s picture about %s and its consequences."
                % (CATEGORIES[category_id - 1].lower(), tail.lower()),
                2015 + rng.below(11),
                category_id,
                rate,
                duration,
                80 + rng.below(70),
                rng.pick(RATINGS),
                STAMP,
            ))
            film_id += 1
    lines += rows("film",
                  ["film_id", "title", "description", "release_year", "category_id",
                   "rental_rate", "rental_duration", "length", "rating", "last_update"],
                  films)

    # 演员与出演（每部片 2–4 位，billing_order 从 1 起）
    actors = []
    for i in range(24):
        actors.append((i + 1, FIRST_NAMES[i % len(FIRST_NAMES)],
                       LAST_NAMES[(i * 7) % len(LAST_NAMES)], STAMP))
    lines += rows("actor", ["actor_id", "first_name", "last_name", "last_update"], actors)

    film_actor = []
    for film in films:
        count = 2 + rng.below(3)
        chosen = []
        while len(chosen) < count:
            actor_id = 1 + rng.below(len(actors))
            if actor_id not in chosen:
                chosen.append(actor_id)
        for order, actor_id in enumerate(chosen):
            film_actor.append((film[0], actor_id, order + 1))
    lines += rows("film_actor", ["film_id", "actor_id", "billing_order"], film_actor)

    # 门店与店员
    lines += rows("store", ["store_id", "name", "city", "opened_on"], STORES)

    staff = []
    for i in range(4):
        staff.append((i + 1, FIRST_NAMES[(i * 5) % len(FIRST_NAMES)],
                      LAST_NAMES[(i * 3) % len(LAST_NAMES)],
                      1 + (i % 2), 1 if i != 3 else 0, day(rng.below(200))))
    lines += rows("staff",
                  ["staff_id", "first_name", "last_name", "store_id", "active", "hired_on"],
                  staff)

    # 客户：40 位，两家门店，少数已停用
    customers = []
    for i in range(40):
        first = FIRST_NAMES[(i * 3) % len(FIRST_NAMES)]
        last = LAST_NAMES[(i * 11) % len(LAST_NAMES)]
        customers.append((
            100 + i,
            first,
            last,
            "%s.%s@example.test" % (first.lower(), last.lower()),
            1 + (i % 2),
            0 if i % 17 == 0 else 1,
            day(rng.below(120)),
        ))
    lines += rows("customer",
                  ["customer_id", "first_name", "last_name", "email", "store_id",
                   "active", "created_on"],
                  customers)

    # 库存：每部片 1–3 张碟，分布在两家门店
    inventory = []
    inventory_id = 1
    for film in films:
        for _ in range(1 + rng.below(3)):
            inventory.append((inventory_id, film[0], 1 + rng.below(2), day(rng.below(90))))
            inventory_id += 1
    lines += rows("inventory", ["inventory_id", "film_id", "store_id", "acquired_on"], inventory)

    # 借出与收款
    #
    # 三种结局，刻意都造出来：已归还、逾期归还、**未归还**（return_date 为空）。
    # 未归还那一支是「空即答案」的演示材料，不是缺数据。
    rentals = []
    payments = []
    rental_id = 1
    payment_id = 1
    for i in range(220):
        item = inventory[rng.below(len(inventory))]
        film = films[item[1] - 1]
        customer = customers[rng.below(len(customers))]
        clerk = staff[rng.below(len(staff))]
        start = 120 + rng.below(220)
        outcome = rng.below(10)
        if outcome < 6:                      # 按期归还
            returned = day(start + film[6])
            amount = film[5] * film[6]
        elif outcome < 8:                    # 逾期：多收滞纳金
            late = 1 + rng.below(5)
            returned = day(start + film[6] + late)
            amount = film[5] * film[6] + late * 1.50
        else:                                # 还没还
            returned = None
            amount = film[5] * film[6]
        rentals.append((rental_id, day(start), item[0], customer[0], clerk[0], returned))
        payments.append((payment_id, rental_id, customer[0], clerk[0],
                         round(amount, 2), day(start)))
        rental_id += 1
        payment_id += 1
    lines += rows("rental",
                  ["rental_id", "rental_date", "inventory_id", "customer_id",
                   "staff_id", "return_date"],
                  rentals)
    lines += rows("payment",
                  ["payment_id", "rental_id", "customer_id", "staff_id",
                   "amount", "payment_date"],
                  payments)

    here = os.path.dirname(os.path.abspath(__file__))
    body = "\n".join(lines)
    io.open(os.path.join(here, "seed.sql"), "w", encoding="utf-8", newline="\n").write(body)
    outstanding = len([r for r in rentals if r[5] is None])
    print("wrote seed.sql (%d lines): %d films, %d customers, %d rentals (%d outstanding)"
          % (body.count("\n"), len(films), len(customers), len(rentals), outstanding))


if __name__ == "__main__":
    main()
