#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""demo-db 的**确定性**种子生成器 —— 产出 seed.sql。

    python3 fixtures/demo/demo-db/generate.py

产物 `seed.sql` 与本脚本**一起进仓库**。为什么两个都留：脚本说明数据是**怎么
造出来的**（业务规则写在代码里，读得到），seed.sql 让跑测试的人不必装 Python
也不必信任生成器。两者不一致时以脚本为准——重跑一遍即可，**每次逐字节相同**。

「逐字节相同」是硬要求，所以这里**不用 `random` 模块**：标准库的 Mersenne
Twister 在不同 Python 版本间的行为不是契约。用一个写死的 LCG，序列由常数决定，
与运行环境无关。

数据是**合成的**。金额、日期、名称像真的，但没有一条来自 owner 的业务数据。
"""

import io
import os

# --- 确定性伪随机 -----------------------------------------------------------
# 自带 LCG（glibc 的常数）。不用标准库 `random`：那是「同一版本内确定」，
# 我们要的是「跨版本、跨机器逐字节确定」。


class Rng:
    def __init__(self, seed):
        self.s = seed & 0x7FFFFFFF

    def next(self):
        self.s = (1103515245 * self.s + 12345) & 0x7FFFFFFF
        return self.s

    def below(self, n):
        return self.next() % n

    def between(self, lo, hi):
        """闭区间 [lo, hi]。"""
        return lo + self.below(hi - lo + 1)

    def pick(self, xs):
        return xs[self.below(len(xs))]


# --- 号段（owner 钉死的量级；四段互不重叠）----------------------------------
# 号段分开是数据自带的一道**自检**：号段重叠时一条错误的 join 仍然「查得出东西」，
# bug 于是变成静默的。分开之后，接错了就查不到——错误会自己现形。
CUST_BASE = 120001      # 6 位
VEND_BASE = 700001      # 6 位
SKU_BASE = 5100001      # 7 位  参考库真实区间 4,001,821 – 9,610,851
VPO_BASE = 32700101     # 8 位  参考库真实区间 32,700,001 – 32,709,374
MSO_BASE = 143100001    # 9 位  参考库真实区间 142,423,568 – 145,972,043
SSO_BASE = 160500001    # 9 位  参考库真实区间 128,247,378 – 168,382,320
CPO_BASE = 210000001    # 9 位  参考库无 cpo，另取一段，高于 SSO 上限

N_CUST, N_VEND, N_PART, N_CPO, N_MSO, N_VPO, N_SSO = 20, 10, 30, 15, 20, 8, 30

# int_ref_type —— **写死的字面量，不是建模概念**（不给它码值表，方言里也没有
# 它的值域）。含义写在使用它的那条 SQL 旁边，见 additions 文件的 resolver 注释。
REF_VPO = 2   # int_ref_no 指向一张 VPO（dropship）—— owner 钦定，与语料注释一致
REF_MSO = 3   # int_ref_no 指向一张 MSO
REF_CPO = 4   # int_ref_no 指向一张 CPO

# --- 履约剧本（写死，不靠随机——三种状态各要有足够样本才演示得出来）---------
# 现有 demo 数据里「部分发货」**一条都没有**，那正是演示不出履约进度的原因。
UNSHIPPED = [0, 3, 7, 11, 15, 18]           # 6 张：0%
FULL = [1, 4, 8, 12, 16, 19]                # 6 张：100%
# 其余 8 张为部分发货（0 < x < 100%）
PARTIAL = [i for i in range(N_MSO) if i not in UNSHIPPED and i not in FULL]

DROPSHIP_ONLY = [4, 9, 14]                  # 只有供应商直发
MIXED = [2, 5, 10, 16, 17]                  # **同时**有仓库发货与 dropship
# 其余发货的 MSO 走仓库发货

CITIES = [
    ("Fremont", "CA"), ("Plano", "TX"), ("Naperville", "IL"), ("Cary", "NC"),
    ("Bellevue", "WA"), ("Alpharetta", "GA"), ("Warren", "MI"), ("Nashua", "NH"),
    ("Boulder", "CO"), ("Tempe", "AZ"),
]
CUST_NAMES = [
    "Northwind Traders", "Contoso Electronics", "Fabrikam Industrial", "Litware Systems",
    "Adventure Outfitters", "Proseware Medical", "Wingtip Components", "Tailspin Avionics",
    "Coho Distributing", "Lucerne Metalworks", "Alpine Ski Supply", "Blue Yonder Logistics",
    "Graphic Design Institute", "Humongous Insurance", "Trey Research Labs",
    "Woodgrove Manufacturing", "Fourth Coffee Roasters", "VanArsdel Marine",
    "Relecloud Networks", "Margie's Travel Goods",
]
VEND_NAMES = [
    "Kaizen Precision", "Meridian Castings", "Orion Fasteners", "Baltic Polymers",
    "Cascade Bearings", "Delta Circuit Works", "Helios Optics", "Ironwood Tooling",
    "Juniper Connectors", "Keystone Hydraulics",
]
PART_WORDS = [
    "Bearing", "Gearbox", "Coupler", "Sensor", "Harness", "Bracket", "Actuator",
    "Manifold", "Impeller", "Regulator", "Bushing", "Flange", "Spindle", "Gasket",
    "Solenoid",
]
PART_SIZES = ["10mm", "22mm", "40mm", "M8", "M12", "1/2in", "3/4in", "DN25"]
STAFF = ["jgao", "lchen", "mruiz", "aokafor", "spatel", "dnovak"]

# --- 审计/作业字段：**第二条独立的 LCG** ------------------------------------
# 本轮新加的审计、收货地址、仓库作业与放行控制字段，全部由 `arng` 产出，
# **不碰主 rng 的调用序列**。理由很具体：主 rng 是一条流，在中间插一次抽取会把
# 它之后的**每一个值**都推移一格——已提交的 seed.sql、集成测试里那几个锚点
# （`mso-partial-mixed` 的 56/135 之类）会集体失效，而那与本轮要演示的东西毫无
# 关系。两条流各自确定，合起来仍然逐字节可重现。
AUDIT_SEED = 20260824

# 扣单剧本（写死，不靠随机）——**放行控制只有造出「有单被扣住」才演示得出来**。
HELD_NOW = [3, 11]            # 仍被扣：approval N、hold_* 有值、credit_rel_date 空
RELEASED_AFTER_HOLD = [5, 12]  # 扣过又放行：三个字段都有值



def money(cents):
    """分 → 两位小数字符串。金额一律用整数分算，浮点的舍入误差会让『可重建且
    逐字节相同』这条要求变成一句空话。"""
    return "%d.%02d" % (cents // 100, cents % 100)


def day(n):
    """把序号摊成 2025 年里的一个日期（合成的，不是真实业务日期）。"""
    md = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    d = n % 365
    m = 0
    while d >= md[m]:
        d -= md[m]
        m += 1
    return "2025-%02d-%02d" % (m + 1, d + 1)


def q(s):
    return "'" + s.replace("'", "''") + "'"


def qday(n):
    """日期字面量。"""
    return q(day(n))


NULL = "NULL"


def main():
    rng = Rng(20260823)
    # 审计与作业字段走**自己的**流，见 AUDIT_SEED 那一段。
    arng = Rng(AUDIT_SEED)

    def audit3(base_day):
        """录入三件套：谁、何时、行版本号。`entry_datetime` **早于业务日期**
        —— 单子是先录进来，再发生后面的事（词条 `common.audit/created_at`
        的描述就是这么写的，数据得对得上）。"""
        return [q(arng.pick(STAFF)), qday(max(0, base_day - arng.between(3, 12))),
                q(str(arng.between(1, 4)))]

    def audit5(base_day):
        """审计五件套。`u_version` 从 1 起；**版本号是 1 就意味着从没被改过**,
        于是 `update_id` / `update_datetime` 为 NULL——空本身是答案，
        与词条 `common.audit/updated_at` 的描述对得上。"""
        who, when, _ = audit3(base_day)
        ver = arng.between(1, 4)
        if ver == 1:
            return [who, when, NULL, NULL, "'1'"]
        return [who, when, q(arng.pick(STAFF)),
                qday(base_day + arng.between(1, 6)), q(str(ver))]

    out = []

    def section(title):
        out.append("")
        out.append("-- " + "-" * 74)
        out.append("-- " + title)
        out.append("-- " + "-" * 74)

    def insert(table, cols, rows):
        out.append("INSERT INTO %s (%s) VALUES" % (table, ", ".join(cols)))
        for i, r in enumerate(rows):
            out.append("  (%s)%s" % (", ".join(r), ";" if i == len(rows) - 1 else ","))

    # ---- vendors ----------------------------------------------------------
    section("vendors —— 10 家供应商（dropship 时由他们直接发货给客户）")
    vends = [VEND_BASE + i for i in range(N_VEND)]
    insert("vendors", ["vend_no", "vend_name", "vend_type", "primary_loc", "discontinued",
                       "entry_id", "entry_datetime", "u_version"],
           [[str(vends[i]), q(VEND_NAMES[i]), q(rng.pick(["M", "D", "S"])),
             str(rng.between(1, 4)), q("Y" if i == N_VEND - 1 else "N")]
            + audit3(20 + i)
            for i in range(N_VEND)])

    # ---- customers --------------------------------------------------------
    section("customers —— 20 个客户（CPO 的下单方，也是 SSO 的收货方）")
    custs = [CUST_BASE + i for i in range(N_CUST)]
    cust_city = []
    rows = []
    for i in range(N_CUST):
        city, st = CITIES[i % len(CITIES)]
        cust_city.append((city, st))
        rows.append([str(custs[i]), q(CUST_NAMES[i]), q(rng.pick(["R", "D", "O"])),
                     q("T%d" % rng.between(1, 6)), q(rng.pick(["N30", "N45", "N60", "COD"])),
                     q(rng.pick(["TAXABLE", "EXEMPT"]))] + audit3(15 + i))
    insert("customers", ["cust_no", "cust_name", "cust_type", "sales_terr",
                         "default_terms", "tax_status",
                         "entry_id", "entry_datetime", "u_version"], rows)

    # ---- customer_credit / customer_bank ----------------------------------
    section("customer_credit —— 授信（credit_limit 是 :restricted，curr_bal 等是 :sensitive）")
    rows = []
    for i in range(N_CUST):
        lim = rng.between(20, 400) * 100000
        bal = rng.below(lim // 2)
        rows.append([str(custs[i]), q(rng.pick(["N30", "N45", "N60"])),
                     money(lim), money(bal), money(rng.below(bal + 1)),
                     money(rng.below(500000)), money(rng.below(200000)),
                     q(day(200 + i * 3)), q(day(250 + i * 2)),
                     q("N" if i % 7 else "Y"), q(day(i * 11))] + audit3(18 + i))
    insert("customer_credit",
           ["cust_no", "terms", "credit_limit", "curr_bal", "curr_pymts", "pending_amt",
            "past_due_amt", "past_due_date", "last_pay_date", "restricted", "sold_since",
            "entry_id", "entry_datetime", "u_version"], rows)

    section("customer_bank —— 结算银行（整族都是 :sensitive）")
    rows = []
    for i in range(0, N_CUST, 2):          # 一半客户有银行档，另一半留空位
        for line in (1, 2) if i % 6 == 0 else (1,):
            rows.append([str(custs[i]), str(line), q("W"),
                         q(rng.pick(["First Meridian Bank", "Cascade Federal",
                                     "Harborline Trust", "Summit National"])),
                         q("%08d" % rng.below(100000000)), q("%09d" % rng.below(1000000000)),
                         q(rng.pick(["CHK", "SAV"])),
                         q("%d %s St" % (rng.between(100, 9000), rng.pick(PART_WORDS))),
                         q("SMBXUS%02d" % rng.between(10, 99)),
                         q("US%02d0000%08d" % (rng.between(10, 99), rng.below(100000000))),
                         q("Y" if line == 1 else "N"), q(day(i * 7))])
    insert("customer_bank",
           ["cust_no", "line_no", "type", "bank_name", "bank_acct", "bank_aba", "acct_type",
            "bank_address", "swift_code", "iban_number", "bank_def", "date_opened"], rows)

    # ---- parts ------------------------------------------------------------
    section("parts —— 30 个物料。**sku_no 是 INTEGER**（见 README：参考库里它是 TEXT，"
            "同一业务键两张表两种类型，我们不复制那个毛病）")
    skus = [SKU_BASE + i * 13 for i in range(N_PART)]
    part_cost = []
    rows = []
    for i in range(N_PART):
        cost = rng.between(250, 48000)          # 分
        part_cost.append(cost)
        rows.append([str(skus[i]),
                     q("%s-%03d" % (VEND_NAMES[i % N_VEND][:3].upper(), 100 + i)),
                     q("%s %s" % (PART_WORDS[i % len(PART_WORDS)], PART_SIZES[i % len(PART_SIZES)])),
                     money(cost), q(rng.pick(["A", "R"])), str(vends[i % N_VEND])]
                    + audit3(25 + i))
    insert("parts", ["sku_no", "part_no", "short_desc", "ave_cost", "prod_code", "vend_no",
                     "entry_id", "entry_datetime", "u_version"], rows)

    # ---- CPO --------------------------------------------------------------
    section("cpo_header / cpo_detail —— 15 张客户采购订单，链条的起点")
    cpos = [CPO_BASE + i for i in range(N_CPO)]
    cpo_cust = [custs[(i * 3) % N_CUST] for i in range(N_CPO)]
    cpo_lines = []                              # [(sku, qty, unit_price_cents)]
    for i in range(N_CPO):
        n = rng.between(3, 8)
        ls = []
        used = set()
        for _ in range(n):
            k = rng.below(N_PART)
            while k in used:
                k = (k + 1) % N_PART
            used.add(k)
            qty = rng.between(2, 60)
            price = int(part_cost[k] * rng.between(125, 190) / 100)
            ls.append((skus[k], qty, price))
        cpo_lines.append(ls)

    rows = []
    for i in range(N_CPO):
        total = sum(qty * price for _, qty, price in cpo_lines[i])
        rows.append([str(cpos[i]), str(cpo_cust[i]), q(day(10 + i * 6)),
                     q("O" if i % 5 else "C"), q(rng.pick(["1", "2", "3"])),
                     money(total), q(rng.pick(STAFF)), q(day(10 + i * 6))])
    insert("cpo_header", ["order_no", "cpo_cust_no", "cpo_date", "cpo_status", "cpo_priority",
                          "po_total", "cpo_entry_id", "cpo_entry_datetime"], rows)

    rows = []
    for i in range(N_CPO):
        for seq, (sku, qty, price) in enumerate(cpo_lines[i], start=1):
            rows.append([str(cpos[i]), str(seq), str(sku), str(qty), money(price),
                         money(qty * price), q("O" if i % 5 else "C")])
    insert("cpo_detail", ["order_no", "cpo_line_seq", "cpo_sku_no", "cpo_line_qty",
                          "cpo_unit_price", "cpo_price", "cpo_line_status"], rows)

    # ---- MSO --------------------------------------------------------------
    # 每张 MSO 指回一张 CPO（int_ref_type = 4）。20 张 MSO 摊在 15 张 CPO 上，
    # 于是有 CPO 开出不止一张销售合同——CPO→MSO 是一对多，要演示得出来。
    section("msos / mso_order_details —— 20 张销售合同，int_ref_no 指回 CPO（int_ref_type = 4）")
    msos = [MSO_BASE + i for i in range(N_MSO)]
    mso_cpo = [cpos[i % N_CPO] for i in range(N_MSO)]
    mso_cust = [cpo_cust[i % N_CPO] for i in range(N_MSO)]
    mso_lines = []                              # [(sku, order_qty, unit_cost, unit_price)]
    for i in range(N_MSO):
        src = cpo_lines[i % N_CPO]
        ls = []
        for (sku, qty, price) in src[:rng.between(3, min(8, len(src)))]:
            k = (sku - SKU_BASE) // 13
            ls.append((sku, qty, part_cost[k], price))
        mso_lines.append(ls)

    # 履约计划：每条 MSO 行**总共**要发多少
    ship_plan = []
    for i in range(N_MSO):
        if i in UNSHIPPED:
            ship_plan.append([0] * len(mso_lines[i]))
        elif i in FULL:
            ship_plan.append([qty for (_, qty, _, _) in mso_lines[i]])
        else:                                   # 部分发货：每行发 1..qty-1
            plan = []
            for (_, qty, _, _) in mso_lines[i]:
                plan.append(max(1, qty * rng.between(35, 70) // 100) if qty > 1 else 0)
            if all(v == 0 for v in plan):       # 保证「部分」真的发出去了一点
                plan[0] = 1
            ship_plan.append(plan)

    rows = []
    for i in range(N_MSO):
        total = sum(qty * price for (_, qty, _, price) in mso_lines[i])
        city, st = cust_city[(mso_cust[i] - CUST_BASE)]
        issue = 40 + i * 5
        # ── 仓库作业时间线 ────────────────────────────────────────────────
        # 顺序恒为 issue < pick < qc < label < ship < bol，偏移写死不随机——
        # **时间线的顺序是这一族的语义**，让它随机等于让它偶尔说谎。
        # 走到哪一步由履约状态决定，没走到的是 NULL：
        #   未发货（6 张）  五个时点全空 —— 「这单还没发货」
        #   部分发货（8 张）开到 ship_date，bol_date 空 —— 提单还没开完
        #   全部发货（6 张）五个都有
        if i in UNSHIPPED:
            ops = [NULL] * 5
        elif i in FULL:
            ops = [qday(issue + 4), qday(issue + 6), qday(issue + 8),
                   qday(issue + 10), qday(issue + 12)]
        else:
            ops = [qday(issue + 4), qday(issue + 6), qday(issue + 8),
                   qday(issue + 10), NULL]
        # ── 放行控制 ──────────────────────────────────────────────────────
        # 两张仍被扣住（都落在未发货那一批里——被扣住的单子当然发不出去），
        # 两张扣过又放行。其余没被扣过，三个字段全空。
        if i in HELD_NOW:
            rel = [q("N"), q(arng.pick(STAFF)), qday(issue + 2), NULL]
        elif i in RELEASED_AFTER_HOLD:
            rel = [q("Y"), q(arng.pick(STAFF)), qday(issue + 1), qday(issue + 3)]
        else:
            rel = [q("Y"), NULL, NULL, NULL]
        rows.append([str(msos[i]), "1", q(CUST_NAMES[mso_cust[i] - CUST_BASE]), q(city),
                     money(total), money(int(total * 0.98)), q("T%d" % (1 + i % 6)),
                     str(mso_cust[i]), str(mso_cpo[i]), str(REF_CPO), q(day(issue))]
                    + audit5(issue)
                    + [q("%d %s Ave" % (100 + i * 17, PART_WORDS[i % len(PART_WORDS)])),
                       q(st), q("%05d" % (10000 + i * 733)), q("US")]
                    + ops + rel)
    insert("msos", ["order_no", "order_type", "ship_to_name", "ship_to_city", "total_order",
                    "sales_total", "sales_terr", "to_acct_no", "int_ref_no", "int_ref_type",
                    "issue_date",
                    "entry_id", "entry_datetime", "update_id", "update_datetime", "u_version",
                    "ship_to_addr", "ship_to_state", "ship_to_zip", "ship_to_country",
                    "pick_date", "qc_date", "label_date", "ship_date", "bol_date",
                    "approval", "hold_id", "hold_date", "credit_rel_date"], rows)

    rows = []
    for i in range(N_MSO):
        # `inv_type`：1 = 库存单（从我们的仓库发），98 = 直发单（供应商直发客户）。
        # 只有 DROPSHIP_ONLY 那三张整单直发，行上才写 98。
        #
        # **MIXED 那五张写 1，是有意的**：它们在**数量**上对半拆（见下面 emit_sso
        # 那一段），同一行既有仓库发的一半、也有供应商直发的一半。给这样一行写 98
        # 等于说「这一行从不经过我们的仓库」——那是假的。`inv_type` 记的是这一行的
        # 履约方式，它表达不了「半直发」，于是照实写它主要的那一种，而不是替这个
        # 码值发明一层它没有的语义。
        ds_lines = set(DROPSHIP_ONLY)
        for ln, (sku, qty, cost, price) in enumerate(mso_lines[i], start=1):
            shipped = ship_plan[i][ln - 1]
            inv_type = "98" if i in ds_lines else "1"
            rows.append([str(msos[i]), str(ln), "1", str(sku), inv_type, str(qty), str(shipped), "0",
                         money(cost), money(price), money(qty * price),
                         money(int(cost * 1.02)), money(int(cost * 1.05)),
                         q(rng.pick(STAFF)), q(rng.pick(STAFF)), q(day(40 + i * 5))])
    insert("mso_order_details",
           ["order_no", "line_no", "order_type", "sku_no", "inv_type", "order_qty", "ship_qty",
            "rec_qty", "unit_cost", "unit_price", "net_price", "sys_new_cost", "claim_new_cost",
            "release_id", "entry_id", "entry_datetime"], rows)

    # ---- VPO（dropship）---------------------------------------------------
    # dropship：我们向供应商下 VPO，供应商**直接发货给客户**。VPO 指回它履约的
    # 那张 MSO（int_ref_type = 3），收货方 to_acct_no 是**客户**不是我们的仓库。
    section("vpos / vpo_order_details —— 8 张 dropship 采购单，int_ref_no 指回 MSO（int_ref_type = 3）")
    ds_msos = sorted(DROPSHIP_ONLY + MIXED)     # 8 张 MSO 各配一张 VPO
    assert len(ds_msos) == N_VPO
    vpos = [VPO_BASE + i for i in range(N_VPO)]
    vpo_of_mso = {m: vpos[i] for i, m in enumerate(ds_msos)}
    vpo_vend = [vends[(i * 3) % N_VEND] for i in range(N_VPO)]

    rows = []
    for i, mi in enumerate(ds_msos):
        city, st = cust_city[(mso_cust[mi] - CUST_BASE)]
        cost = sum(qty * cost for (_, qty, cost, _) in mso_lines[mi])
        freight = rng.between(2500, 18000)
        # ── 收货时间线的后两个时点 ────────────────────────────────────────
        # 与 vpo_eta_detail 的 `actual_arrival` **对得上**：那边 i % 3 == 0 的
        # 三张还没到货，这里对应的 receiving_date / closed_date 就是 NULL。
        # 两处说的是同一件事，不许互相矛盾。收到 → 关单，顺序恒定。
        if i % 3:
            recv, closed = qday(68 + i * 7), qday(72 + i * 7)
        else:
            recv, closed = NULL, NULL
        detail_total = cost
        rows.append([str(vpos[i]), "2", money(cost), money(freight),
                     q(CUST_NAMES[mso_cust[mi] - CUST_BASE]), q(city), q(st),
                     q(day(50 + i * 7)), q(day(64 + i * 7)),
                     str(vpo_vend[i]), str(mso_cust[mi]), str(msos[mi]), str(REF_MSO)]
                    + audit5(50 + i * 7)
                    + [q("%d %s Ave" % (100 + mi * 17, PART_WORDS[mi % len(PART_WORDS)])),
                       q("%05d" % (10000 + mi * 733)), recv, closed,
                       # `total_order` = 明细价合计 + 运费（这张单**一共要付多少**),
                       # `detail_price_total` = 明细价合计。两者不是同一个数,
                       # 分开才看得出运费在哪一层。
                       money(cost + freight), money(detail_total)])
    insert("vpos", ["order_no", "order_type", "total_cost", "freight", "ship_to_name",
                    "ship_to_city", "ship_to_state", "issue_date", "expected_date",
                    "from_acct_no", "to_acct_no", "int_ref_no", "int_ref_type",
                    "entry_id", "entry_datetime", "update_id", "update_datetime", "u_version",
                    "ship_to_addr", "ship_to_zip", "receiving_date", "closed_date",
                    "total_order", "detail_price_total"], rows)

    rows = []
    for i, mi in enumerate(ds_msos):
        for ln, (sku, qty, cost, _) in enumerate(mso_lines[mi], start=1):
            rows.append([str(vpos[i]), str(ln), str(sku),
                         q("VP-%05d" % (sku % 100000)), str(qty),
                         str(ship_plan[mi][ln - 1]), money(cost)])
    insert("vpo_order_details", ["order_no", "line_no", "sku_no", "vend_part_no",
                                 "order_qty", "ship_qty", "unit_cost"], rows)

    section("vpo_eta_detail —— 到货跟踪（每张 VPO 的第一行）")
    rows = []
    for i, mi in enumerate(ds_msos):
        sku = mso_lines[mi][0][0]
        rows.append([str(vpos[i]), "1", str(sku), q(day(64 + i * 7)), q(day(66 + i * 7)),
                     q(day(67 + i * 7)) if i % 3 else "NULL",
                     q(rng.pick(["ON_TIME", "DELAYED", "ARRIVED"])),
                     str(rng.between(10, 40)), q("1Z%09d" % rng.below(1000000000)),
                     str(rng.below(6)), q(day(68 + i * 7))])
    insert("vpo_eta_detail",
           ["order_no", "line_no", "sku_no", "original_eta", "current_eta", "actual_arrival",
            "eta_status", "carrier_no", "tracking_no", "delay_days", "updated_date"], rows)

    # ---- SSO --------------------------------------------------------------
    # 每张 SSO 是**一次发货**。它 int_ref 指向什么，取决于货是从哪儿发的：
    #   仓库发货  → 指向 MSO（int_ref_type = 3）
    #   供应商直发 → 指向 VPO（int_ref_type = 2）
    # 这就是那条**多态**引用，也是「不能建成 link」的理由。
    section("sso / sso_order_detail —— 30 次发货。int_ref 是**多态**的："
            "仓库发货指向 MSO（3），dropship 指向 VPO（2）")

    warehouse_msos = [i for i in range(N_MSO)
                      if i not in UNSHIPPED and i not in DROPSHIP_ONLY]
    # 仓库发货的张数：MIXED 各 1 张，其余摊满剩下的名额
    per_mso = {i: 1 for i in MIXED}
    plain = [i for i in warehouse_msos if i not in MIXED]
    remaining = N_SSO - len(DROPSHIP_ONLY) - len(MIXED) - len(MIXED)
    for j, i in enumerate(plain):
        per_mso[i] = remaining // len(plain) + (1 if j < remaining % len(plain) else 0)

    sso_rows, det_rows = [], []
    nxt = [0]

    def emit_sso(mi, ref_no, ref_type, lines, drop):
        n = nxt[0]
        nxt[0] += 1
        no = SSO_BASE + n
        city, st = cust_city[(mso_cust[mi] - CUST_BASE)]
        total = sum(qty * price for (_, qty, _, price) in lines)
        cost = sum(qty * c for (_, qty, c, _) in lines)
        sso_rows.append(
            [str(no), "1", q("PO-%d" % mso_cpo[mi]), q(CUST_NAMES[mso_cust[mi] - CUST_BASE]),
             q(city), q(st), q(day(70 + n * 3)), q(day(68 + n * 3)), q(day(74 + n * 3)),
             str(mso_cust[mi]), str(mso_cust[mi]), money(total), money(cost),
             money(int(total * 0.99)), str(rng.between(5, 900)), q("Y" if drop else "N"),
             str(ref_no), str(ref_type)])
        for ln, (sku, qty, c, price) in enumerate(lines, start=1):
            det_rows.append([str(no), str(ln), str(sku), str(qty), str(qty),
                             money(c), money(price), money(qty * price)])
        return no

    # dropship：整批由供应商直发，SSO 指向 VPO
    for mi in sorted(DROPSHIP_ONLY + MIXED):
        lines = [(sku, ship_plan[mi][k], c, p)
                 for k, (sku, _, c, p) in enumerate(mso_lines[mi])
                 if ship_plan[mi][k] > 0]
        if mi in MIXED:
            # 混合模式：dropship 只承担一半，另一半从仓库发 —— 这一张 MSO
            # 于是**同时**挂着两条履约路径，真实业务里最容易出错的形态。
            lines = [(sku, max(1, qty // 2), c, p) for (sku, qty, c, p) in lines]
        if lines:
            emit_sso(mi, vpo_of_mso[mi], REF_VPO, lines, True)

    # 仓库发货：SSO 指向 MSO，一张 MSO 可以有多张
    for mi in sorted(per_mso):
        total_lines = [(sku, ship_plan[mi][k], c, p)
                       for k, (sku, _, c, p) in enumerate(mso_lines[mi])
                       if ship_plan[mi][k] > 0]
        if mi in MIXED:                          # 减掉 dropship 已经发掉的那部分
            total_lines = [(sku, qty - max(1, qty // 2), c, p)
                           for (sku, qty, c, p) in total_lines]
            total_lines = [t for t in total_lines if t[1] > 0]
        k = per_mso[mi]
        for s in range(k):                       # 把这些行摊成 k 次发货
            chunk = [(sku, qty // k + (1 if s < qty % k else 0), c, p)
                     for (sku, qty, c, p) in total_lines]
            chunk = [t for t in chunk if t[1] > 0]
            if chunk:
                emit_sso(mi, msos[mi], REF_MSO, chunk, False)

    assert nxt[0] == N_SSO, "SSO 张数必须正好 %d，实得 %d" % (N_SSO, nxt[0])
    insert("sso", ["order_no", "order_type", "ext_ref", "ship_to_name", "ship_to_city",
                   "ship_to_state", "ship_date", "issue_date", "invoice_date", "to_acct_no",
                   "cust_no", "total_order", "total_cost", "sales_total", "total_weight",
                   "drop_ship", "int_ref_no", "int_ref_type"], sso_rows)
    insert("sso_order_detail", ["order_no", "line_no", "sku_no", "order_qty", "ship_qty",
                                "unit_cost", "unit_price", "net_price"], det_rows)

    here = os.path.dirname(os.path.abspath(__file__))
    header = [
        "-- demo 库的种子数据 —— **由 fixtures/demo/demo-db/generate.py 生成，不要手改**。",
        "--",
        "--   python3 fixtures/demo/demo-db/generate.py",
        "--",
        "-- 每次重跑逐字节相同（生成器用写死的 LCG，不用标准库 random）。",
        "-- 数据**全部是合成的**：金额、日期、名称像真的，但没有一条来自 owner 的业务数据。",
        "-- 表与 entity 的对应、三种履约状态的分布见本目录 README.md。",
    ]
    body = "\n".join(header + out).rstrip() + "\n"
    io.open(os.path.join(here, "seed.sql"), "w", encoding="utf-8", newline="\n").write(body)
    print("wrote seed.sql (%d lines)" % body.count("\n"))


if __name__ == "__main__":
    main()
