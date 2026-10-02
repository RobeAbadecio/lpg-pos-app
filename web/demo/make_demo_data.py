#!/usr/bin/env python3
"""Generate FAKE data for the LPG POS demo (never point this at ~/POSSystemData).

    python3 make_demo_data.py DATA_DIR            write ~4 months of fake receipts, customers, stock, staff
    python3 make_demo_data.py --sessions PORT     sign the demo staff in, so "Signed in now" has people

Staff logins for the demo are written to demo-logins.txt next to this script.
"""
import base64
import datetime as dt
import hashlib
import os
import random
import secrets
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LOGINS = os.path.join(HERE, "demo-logins.txt")
TIME = "%Y-%m-%d %H:%M:%S"

STAFF = {  # username: (device user agent, fake IP from a documentation range)
    "ana": ("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1", "203.0.113.24"),
    "ben": ("Mozilla/5.0 (Linux; Android 15; SM-A556E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36", "198.51.100.7"),
    "carla": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Safari/537.36", "192.0.2.61"),
}

# brand, weight kg, refill price, swap fee (extra when the empty is another brand), popularity, reorder level,
# tanks kept in circulation, refill cost (what the refiller charges per tank), new tank cost
PRODUCTS = [
    ("Petron Gasul", 11.0, 1045.00, 50.00, 30, 15, 45, 880.00, 2400.00),
    ("Solane", 11.0, 1090.00, 50.00, 24, 12, 40, 925.00, 2480.00),
    ("Phoenix SuperLPG", 11.0, 1020.00, 50.00, 14, 8, 25, 860.00, 2300.00),
    ("Fiesta Gas", 11.0, 1010.00, 50.00, 10, 6, 20, 850.00, 2250.00),
    ("Petron Gasul", 22.0, 2090.00, 80.00, 6, 3, 10, 1780.00, 4200.00),
    ("Solane", 2.7, 365.00, 20.00, 9, 5, 18, 295.00, 950.00),
    ("Petron Gasul", 2.7, 355.00, 20.00, 5, 4, 12, 285.00, 920.00),
    ("Petron Gasul", 50.0, 4650.00, 150.00, 2, 1, 4, 4000.00, 8600.00),
]
DASHBOARD_USERS = {"ana"}  # staff the admin lets see the summary dashboard (ana is the demo's auto sign-in)
# name, contact (fake 555 numbers), brands, note
SUPPLIERS = [
    ("Petron depot", "0917 555 0142", ["Petron Gasul"], "Picks up Mon & Thu, back in 2 days"),
    ("Solane dealer", "0918 555 0199", ["Solane"], "Picks up Mon & Thu, back in 2 days"),
    ("Phoenix refiller", "0927 555 0110", ["Phoenix SuperLPG"], "Drop-off only"),
    ("Fiesta Gas depot", "0945 555 0177", ["Fiesta Gas"], ""),
]
# Empties not sent for the last N days, so the demo shows low and sold-out stock.
REFILL_PAUSED = {5: 6, 7: 35}  # product index -> days

FIRST = ["Juan", "Maria", "Jose", "Ana", "Pedro", "Liza", "Ramon", "Teresita", "Carlo", "Rosalie", "Mark", "Jenny",
         "Arnel", "Lorna", "Rodel", "Grace", "Dennis", "Marites", "Jomar", "Cristina", "Noel", "Aileen", "Rey", "Joy",
         "Elmer", "Shiela", "Romeo", "Divina", "Ariel", "Nenita", "Jun", "Kristine", "Edwin", "Maricel", "Allan", "Lovely",
         "Bong", "Analyn", "Ronald", "Precious", "Felix", "Imelda", "Gerald", "Cora", "Wilfredo", "Hazel", "Danilo", "Rica"]
LAST = ["Dela Cruz", "Santos", "Reyes", "Garcia", "Bautista", "Mendoza", "Ramos", "Aquino", "Villanueva", "Castillo",
        "Flores", "Gonzales", "Torres", "Navarro", "Domingo", "Mercado", "Salazar", "Pascual", "Soriano", "Manalo",
        "Cruz", "Lopez", "Rivera", "Fernandez", "Aguilar", "Valdez", "Morales", "Ocampo", "Del Rosario", "Panganiban"]
STREETS = ["Mabini St", "Rizal Ave", "Bonifacio St", "Luna St", "Burgos St", "Del Pilar St", "Quezon Ave", "Sampaloc St",
           "Magsaysay Blvd", "Aguinaldo St", "Jacinto St", "Recto Ave", "Roxas St", "Osmeña St", "Laurel St"]
BARANGAYS = ["Brgy. San Roque", "Brgy. Poblacion", "Brgy. Santo Niño", "Brgy. San Isidro", "Brgy. Malanday",
             "Brgy. Bagong Silang", "Brgy. San Jose", "Brgy. Maligaya"]
BUSINESSES = ["Carinderia", "Bakery", "Lugawan", "Eatery", "Sari-sari Store", "Panciteria"]


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def pbkdf2(password, salt):  # matches Auth.java: PBKDF2-HMAC-SHA256, 210k rounds, 32 bytes
    return b64(hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 210_000, 32))


def money(v):
    return f"{v:.2f}"


def kg(w):
    return f"{w:.1f}" if w == int(w) else str(w)


def csv_field(s):
    s = "" if s is None else str(s)
    return '"' + s.replace('"', '""') + '"' if any(c in s for c in ',"\n') else s


def write_csv(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(",".join(csv_field(x) for x in r) + "\n")


def generate(data_dir):
    rng = random.Random(2026)
    os.makedirs(data_dir, exist_ok=True)
    for old in os.listdir(data_dir):
        if old.endswith(".csv") or old.endswith(".log"):
            os.remove(os.path.join(data_dir, old))
    now = dt.datetime.now().replace(microsecond=0)
    today = now.date()
    label = [f"{p[0]} {kg(p[1])} kg" for p in PRODUCTS]

    # ---- customers: a few businesses that buy a lot, many households that buy now and then
    customers, weights, is_business = [], [], []
    used = set()
    for i in range(1, 61):
        while True:
            name = (rng.choice(FIRST), rng.choice(LAST))
            if name not in used:
                used.add(name)
                break
        business = i <= 8
        street = f"{rng.randint(3, 220)} {rng.choice(STREETS)}"
        address = f"{rng.choice(BUSINESSES)}, {street}" if business else f"{street}, {rng.choice(BARANGAYS)}"
        phone = f"09{rng.choice(['17', '18', '19', '20', '27', '28', '45', '55', '56', '66', '76', '95', '98'])}{rng.randint(1000000, 9999999)}"
        customers.append([str(i), name[0], name[1], phone, address])
        weights.append(rng.uniform(6, 12) if business else rng.paretovariate(1.6))
        is_business.append(business)
    new_customers = {57: 6, 58: 4, 59: 2, 60: 0}  # customer id -> days ago they were added
    name = {c[0]: f"{c[1]} {c[2]}" for c in customers}

    products = [[str(i + 1), p[0], money(p[2]), kg(p[1]), str(p[5]), "0.00", money(p[7]), money(p[8]), money(p[3])] for i, p in enumerate(PRODUCTS)]
    suppliers = [[str(i + 1), s[0], s[1], "; ".join(s[2]), s[3]] for i, s in enumerate(SUPPLIERS)]
    supplier_of = {i: next(n for n, s in enumerate(SUPPLIERS) if p[0] in s[2]) for i, p in enumerate(PRODUCTS)}

    def refill_cost(i, day):
        # The refiller's price moves with the same monthly adjustments.
        months_back = (today.year - day.year) * 12 + today.month - day.month
        step = {0: 0, 1: 30, 2: 50}.get(months_back, 70)
        return round(max(1.0, PRODUCTS[i][7] - step * PRODUCTS[i][1] / 11.0), 2)

    supplier_payments = []  # [time, supplierIdx, tripId or purchase move, kind, amount, staff, note]
    later_payments = []  # (due datetime, supplierIdx, tripId or purchase move, kind, amount)
    tank_returns = []  # [time, customerId, productIdx, qty, staff, remark]
    paybacks = []  # [time, staff paid back, amount, recorded by, note]
    returns_due = []  # (date, customerId, productIdx owed, qty)

    def refill_price(i, day):
        # Monthly LPG price adjustments: earlier months were cheaper.
        months_back = (today.year - day.year) * 12 + today.month - day.month
        step = {0: 0, 1: 35, 2: 60}.get(months_back, 85)
        return round(max(1.0, PRODUCTS[i][2] - step * PRODUCTS[i][1] / 11.0), 2)

    def on_shift(t):
        return "carla" if t.weekday() >= 5 else ("ana" if t.hour < 13 else "ben")

    # ---- stock buckets per product: with load, empty (good), damaged, at refiller
    loaded = [p[6] for p in PRODUCTS]
    empty = [p[6] // 3 for p in PRODUCTS]
    damaged = [0] * len(PRODUCTS)
    at_refiller = [0] * len(PRODUCTS)
    moves, refills, trips_due = [], [], []  # moves: [time, idx, type, dl, de, dd, dr, staff, note, refillId]
    start = today - dt.timedelta(days=120)
    opening = dt.datetime.combine(start, dt.time(6, 30))
    for i in range(len(PRODUCTS)):
        moves.append([opening, i, "count", loaded[i], empty[i], 0, 0, "ana", "Opening stock", ""])

    receipts, lines, payments, credit = [], [], [], []
    rid = lid = 1
    for d in range(121):
        day = start + dt.timedelta(days=d)
        days_ago = (today - day).days

        # Morning: trips sent two days ago come back with load; now and then one tank is rejected.
        for trip in [t for t in trips_due if t["due"] <= day]:
            when = dt.datetime.combine(day, dt.time(7, rng.randint(0, 30)))
            if when > now:
                continue
            trips_due.remove(trip)
            bill = 0.0
            for i, n in trip["items"].items():
                bad = sum(1 for _ in range(n) if rng.random() < 0.015)
                good = n - bad
                at_refiller[i] -= n
                loaded[i] += good
                empty[i] += bad  # back unfilled: still an empty tank
                note = f"DR #{rng.randint(10000, 99999)}"
                if good:
                    each = refill_cost(i, day)
                    bill += good * each
                    moves.append([when, i, "refill-receive", good, 0, 0, -good, on_shift(when), note, trip["id"], each])
                if bad:
                    moves.append([when, i, "refill-reject", 0, bad, 0, -bad, on_shift(when), "Returned unfilled: loose valve", trip["id"]])
            # Usually paid in full on the spot; now and then half now and the rest a few days later.
            bill = round(bill, 2)
            if bill > 0:
                s_idx = supplier_of[next(iter(trip["items"]))]
                if rng.random() < 0.15:
                    # Not enough cash from sales: whoever is on shift puts in their own money, paid back in a few days.
                    who = on_shift(when)
                    own = float(min(bill, rng.choice([500, 1000, 1500, 2000])))  # what one person can spare
                    if bill - own > 0.005:
                        supplier_payments.append([when, s_idx, trip["id"], "refill", round(bill - own, 2), who, "Cash"])
                    supplier_payments.append([when, s_idx, trip["id"], "refill", own, who, "Own money", "staff", who])
                    back = when + dt.timedelta(days=rng.randint(1, 5), hours=rng.randint(1, 6))
                    if back <= now and rng.random() < 0.85:
                        paybacks.append([back, who, own, "ana", "Cash from the drawer"])
                elif rng.random() < 0.9:
                    supplier_payments.append([when, s_idx, trip["id"], "refill", bill, on_shift(when), "Cash"])
                else:
                    half = round(bill / 2, 2)
                    supplier_payments.append([when, s_idx, trip["id"], "refill", half, on_shift(when), "Cash, rest next week"])
                    later_payments.append((when + dt.timedelta(days=rng.randint(3, 8)), s_idx, trip["id"], "refill", round(bill - half, 2)))

        # Customers who took a tank without an empty bring one back, sometimes another brand.
        for due in [x for x in returns_due if x[0] <= day]:
            returns_due.remove(due)
            when = dt.datetime.combine(day, dt.time(rng.randint(8, 18), rng.randint(0, 59)))
            if when > now:
                continue
            _, cust, owed_i, qty = due
            other = [i for i, p in enumerate(PRODUCTS) if p[1] == PRODUCTS[owed_i][1] and p[0] != PRODUCTS[owed_i][0]]
            back = rng.choice(other) if other and rng.random() < 0.2 else owed_i
            remark = f"Brought {PRODUCTS[back][0]} instead of {PRODUCTS[owed_i][0]}" if back != owed_i else rng.choice(["", "", "", "Dented"])
            empty[back] += qty
            tank_returns.append([when, cust, back, qty, on_shift(when), remark])

        # Mon & Thu: send good empties to each refiller.
        if day.weekday() in (0, 3):
            when = dt.datetime.combine(day, dt.time(6, 45 + rng.randint(0, 10)))
            if when <= now:
                for s_idx in range(len(SUPPLIERS)):
                    items = {i: empty[i] for i in supplier_of if supplier_of[i] == s_idx and empty[i] > 0
                             and days_ago >= REFILL_PAUSED.get(i, 0)}
                    if not items:
                        continue
                    trip_id = str(len(refills) + 1)
                    refills.append([trip_id, when.strftime(TIME), str(s_idx + 1), on_shift(when), ""])
                    for i, n in items.items():
                        empty[i] -= n
                        at_refiller[i] += n
                        moves.append([when, i, "refill-send", 0, -n, 0, n, on_shift(when), "", trip_id])
                    trips_due.append({"id": trip_id, "due": day + dt.timedelta(days=2), "items": items})

        # Mondays: buy new tanks to replace those still with customers or lost to brand swaps.
        # Usually paid on the spot; sometimes part now and the rest within two weeks.
        if day.weekday() == 0:
            when = dt.datetime.combine(day, dt.time(8, 15))
            if when <= now:
                for i, p in enumerate(PRODUCTS):
                    circulating = loaded[i] + empty[i] + damaged[i] + at_refiller[i]
                    gap = int(p[6] * 1.25) - circulating
                    if gap > 0 and days_ago >= REFILL_PAUSED.get(i, 0):
                        loaded[i] += gap
                        invoice = f"{SUPPLIERS[supplier_of[i]][0]} invoice #{rng.randint(1000, 9999)}"
                        move = [when, i, "purchase", gap, 0, 0, 0, "ana", invoice, "", p[8]]
                        moves.append(move)
                        bill = round(gap * p[8], 2)
                        if rng.random() < 0.75:
                            supplier_payments.append([when, supplier_of[i], move, "purchase", bill, "ana", invoice])
                        else:
                            half = round(bill / 2, 2)
                            supplier_payments.append([when, supplier_of[i], move, "purchase", half, "ana", invoice + ", rest later"])
                            later_payments.append((when + dt.timedelta(days=rng.randint(5, 14)), supplier_of[i], move, "purchase", round(bill - half, 2)))

        # Receipts through the day.
        growth = 0.85 + 0.3 * d / 120
        factor = {0: 1.15, 1: 1.0, 2: 0.95, 3: 1.0, 4: 1.1, 5: 1.3, 6: 0.55}[day.weekday()]
        n = max(2, int(rng.gauss(11 * growth * factor, 2.8)))
        hours = list(range(7, 20))
        times = sorted(dt.datetime.combine(day, dt.time(rng.choices(hours, [3, 6, 7, 6, 5, 3, 3, 4, 5, 6, 6, 4, 2])[0],
                                                        rng.randint(0, 59), rng.randint(0, 59))) for _ in range(n))
        for t in times:
            if t > now:
                break
            eligible = [i for i in range(60) if new_customers.get(i + 1, 10 ** 6) >= days_ago]
            ci = rng.choices(eligible, [weights[i] for i in eligible])[0]
            biz = is_business[ci]
            n_lines = rng.choices([1, 2, 3], [60, 30, 10] if biz else [80, 17, 3])[0]
            items = []
            for _ in range(n_lines):
                pi = rng.choices(range(len(PRODUCTS)), [p[4] for p in PRODUCTS])[0]
                if biz and rng.random() < 0.35:
                    pi = rng.choice([0, 1, 4])
                taken = sum(x[1] for x in items if x[0] == pi)
                if loaded[pi] - taken <= 0:
                    same = [i for i, p in enumerate(PRODUCTS) if p[1] == PRODUCTS[pi][1] and loaded[i] - sum(x[1] for x in items if x[0] == i) > 0]
                    if not same:
                        continue
                    pi = rng.choice(same)
                    taken = sum(x[1] for x in items if x[0] == pi)
                qty = min(loaded[pi] - taken, rng.choices([1, 2, 3], [65, 25, 10] if biz else [93, 6, 1])[0])
                roll = rng.random()
                fee = 0.0
                if roll < 0.06:
                    empty_of = None  # no empty in: the customer owes the tank
                elif roll < 0.14:
                    swaps = [i for i, p in enumerate(PRODUCTS) if p[1] == PRODUCTS[pi][1] and p[0] != PRODUCTS[pi][0]]
                    empty_of = rng.choice(swaps) if swaps else pi  # brought another brand's empty
                    fee = PRODUCTS[pi][3] if empty_of != pi else 0.0
                else:
                    empty_of = pi
                # Now and then staff note the condition of the tank.
                remark = rng.choice(["Dented", "Rusty base", "Loose valve", "No seal cap", "Faded paint"]) if rng.random() < 0.04 else ""
                price = refill_price(pi, day)
                if biz and rng.random() < 0.3:
                    price -= rng.choice([20, 25, 30])  # regular ("suki") price
                items.append((pi, qty, round(price, 2), empty_of, remark, fee))
            if not items:
                continue
            staff = on_shift(t)
            subtotal = sum(q * (p + f) for _, q, p, _, _, f in items)
            discount = 0.0
            if rng.random() < 0.07:
                discount = rng.choice([20.0, 50.0, 100.0, round(subtotal * 0.05, 2)])
            total = round(subtotal - discount, 2)
            pay_roll = rng.random()
            if pay_roll < (0.30 if biz else 0.05):
                paid = 0.0
            elif pay_roll < (0.40 if biz else 0.10):
                paid = float(int(total / 2 / 100) * 100) or round(total / 2, 2)
            else:
                paid = total
            receipt_id = str(rid)
            rid += 1
            receipts.append([receipt_id, t.strftime(TIME), customers[ci][0], staff, money(discount), money(paid), ""])
            for pi, qty, price, empty_of, remark, fee in items:
                loaded[pi] -= qty
                if empty_of is not None:
                    empty[empty_of] += qty
                elif rng.random() < 0.8:  # most bring the tank back within three weeks
                    returns_due.append((day + dt.timedelta(days=rng.randint(1, 21)), customers[ci][0], pi, qty))
                lines.append([str(lid), customers[ci][0], str(pi + 1), t.strftime(TIME), str(qty), money(price), staff,
                              "0" if empty_of is None else "1", receipt_id,
                              "" if empty_of is None else str(empty_of + 1), remark,
                              "1" if empty_of is None else "", money(fee) if fee else ""])
                lid += 1
            if paid < total:
                credit.append((receipt_id, customers[ci][0], t, round(total - paid, 2)))

    # ---- pay-later balances: most are settled within two weeks, recent ones are still open
    pid = 1
    for receipt_id, cust, t, owed in credit:
        if rng.random() < 0.12:
            continue  # still unpaid
        pay_at = t + dt.timedelta(days=rng.randint(3, 14), hours=rng.randint(0, 6))
        if pay_at > now:
            continue
        parts = [owed] if owed < 1500 or rng.random() < 0.6 else [round(owed / 2, 2), round(owed - round(owed / 2, 2), 2)]
        for k, amount in enumerate(parts):
            when = pay_at + dt.timedelta(days=4 * k)
            if when > now:
                break
            payments.append([str(pid), when.strftime(TIME), receipt_id, cust, money(amount), on_shift(when),
                             rng.choice(["Cash", "GCash", "Cash", ""])])
            pid += 1
    payments.sort(key=lambda p: p[1])

    write_csv(os.path.join(data_dir, "Customers.csv"), customers)
    write_csv(os.path.join(data_dir, "LPGs.csv"), products)
    write_csv(os.path.join(data_dir, "Suppliers.csv"), suppliers)
    write_csv(os.path.join(data_dir, "Receipts.csv"), receipts)
    write_csv(os.path.join(data_dir, "Transactions.csv"), lines)
    write_csv(os.path.join(data_dir, "Payments.csv"), payments)
    write_csv(os.path.join(data_dir, "Refills.csv"), refills)
    moves.sort(key=lambda m: m[0])
    write_csv(os.path.join(data_dir, "StockMovements.csv"),
              [[str(n + 1), m[0].strftime(TIME), str(m[1] + 1), m[2], str(m[3]), str(m[4]), m[7], m[8], str(m[5]), str(m[6]), m[9],
                money(m[10] if len(m) > 10 else 0)] for n, m in enumerate(moves)])
    # A purchase's payments carry its bill key: "P" + the purchase's movement id.
    move_id = {id(m): n + 1 for n, m in enumerate(moves)}
    bill_key = lambda ref: ref if isinstance(ref, str) else f"P{move_id[id(ref)]}"
    for due, s_idx, ref, kind, amount in later_payments:
        if due <= now:
            supplier_payments.append([due, s_idx, ref, kind, amount, on_shift(due), "Balance"])
    supplier_payments.sort(key=lambda x: x[0])
    write_csv(os.path.join(data_dir, "SupplierPayments.csv"),
              [[str(n + 1), x[0].strftime(TIME), str(x[1] + 1), bill_key(x[2]), x[3], money(x[4]), x[5], x[6],
                x[7] if len(x) > 7 else "sales", x[8] if len(x) > 8 else ""] for n, x in enumerate(supplier_payments)])
    paybacks.sort(key=lambda x: x[0])
    write_csv(os.path.join(data_dir, "StaffPaybacks.csv"),
              [[str(n + 1), x[0].strftime(TIME), x[1], money(x[2]), x[3], x[4]] for n, x in enumerate(paybacks)])
    tank_returns.sort(key=lambda x: x[0])
    write_csv(os.path.join(data_dir, "TankReturns.csv"),
              [[str(n + 1), x[0].strftime(TIME), x[1], str(x[2] + 1), str(x[3]), x[4], x[5]] for n, x in enumerate(tank_returns)])

    # ---- staff accounts (one shared demo password, written to demo-logins.txt)
    password = "demo-" + secrets.token_hex(3)
    users = []
    for i, u in enumerate(STAFF):
        salt = secrets.token_bytes(16)
        users.append([u, b64(salt), pbkdf2(password, salt), (now - dt.timedelta(days=125 - i * 3)).strftime(TIME),
                      "1" if u in DASHBOARD_USERS else ""])
    write_csv(os.path.join(data_dir, "WebUsers.csv"), users)
    with open(LOGINS, "w") as f:
        f.write("LPG POS demo logins (fake data, demo server only)\n")
        f.write("POS: http://localhost:8180 (opens without sign-in as ana)   Admin dashboard: http://localhost:8190\n")
        f.write("These logins are only needed to try signing in as ben or carla via /api/login.\n\n")
        for u in STAFF:
            f.write(f"username: {u}   password: {password}\n")

    # ---- activity log for the last few days, oldest first (the server reads the tail)
    log = []
    cutoff = now - dt.timedelta(days=3)
    signed_in = set()
    by_receipt = {}
    for ln in lines:
        by_receipt.setdefault(ln[8], []).append(ln)
    for r in receipts:
        when = dt.datetime.strptime(r[1], TIME)
        if when < cutoff:
            continue
        staff, ip = r[3], STAFF[r[3]][1]
        if (staff, when.date()) not in signed_in:
            signed_in.add((staff, when.date()))
            log.append((when - dt.timedelta(minutes=2), staff, ip, "Signed in (Internet)"))
        parts = []
        for ln in by_receipt[r[0]]:
            tail = " (no empty, owes the tank)" if ln[9] == "" else "" if ln[9] == ln[2] \
                else f" (empty {label[int(ln[9]) - 1]}" + (f", swap fee ₱{ln[12]} each)" if ln[12] else ")")
            parts.append(f"{ln[4]} × {label[int(ln[2]) - 1]}{tail}" + (f" [{ln[10]}]" if ln[10] else ""))
        total = sum(int(ln[4]) * (float(ln[5]) + float(ln[12] or 0)) for ln in by_receipt[r[0]]) - float(r[4])
        paid = float(r[5])
        pay = ", ₱%s paid" % money(total) if paid >= total - 0.005 else ", ₱%s to pay later" % money(total) if paid == 0 \
            else ", ₱%s (paid ₱%s, ₱%s to pay later)" % (money(total), money(paid), money(total - paid))
        log.append((when, staff, ip, f"Receipt #{r[0]} for {name[r[2]]}: {', '.join(parts)}{pay}"))
    for p in payments:
        when = dt.datetime.strptime(p[1], TIME)
        if when >= cutoff:
            log.append((when, p[5], STAFF[p[5]][1], f"Payment ₱{p[4]} from {name[p[3]]} for receipt #{p[2]}"))
    for x in paybacks:
        if x[0] >= cutoff:
            log.append((x[0], x[3], STAFF[x[3]][1], f"Paid back ₱{money(x[2])} to {x[1]} (money they put in for suppliers)"))
    for x in tank_returns:
        if x[0] >= cutoff:
            log.append((x[0], x[4], STAFF[x[4]][1], f"{name[x[1]]} returned {x[3]} empty {label[x[2]]}" + (f" [{x[5]}]" if x[5] else "")))
    for m in moves:
        if m[0] < cutoff or m[2] not in ("refill-send", "refill-receive", "purchase"):
            continue
        each = m[10] if len(m) > 10 else 0
        what = {"refill-send": f"Sent {m[6]} × {label[m[1]]} to {SUPPLIERS[supplier_of[m[1]]][0]} (trip #{m[9]})",
                "refill-receive": f"Received {m[3]} × {label[m[1]]} from {SUPPLIERS[supplier_of[m[1]]][0]} (trip #{m[9]}) · ₱{money(each)} each",
                "purchase": f"Bought {m[3]} new {label[m[1]]} tanks with load for ₱{money(m[3] * each)}"}[m[2]]
        log.append((m[0], m[7], STAFF[m[7]][1], what))
    log.sort(key=lambda e: e[0])
    with open(os.path.join(data_dir, "WebActivity.log"), "w", encoding="utf-8") as f:
        for when, actor, ip, msg in log:
            f.write(f"{when.strftime(TIME)}\t{actor}\t{ip}\t{msg}\n")

    print(f"Fake data written to {data_dir}: {len(customers)} customers, {len(products)} products, {len(receipts)} receipts "
          f"({len(lines)} items), {len(payments)} payments, {len(refills)} refill trips, {len(supplier_payments)} supplier payments, "
          f"{len(tank_returns)} tank returns, {len(paybacks)} staff paybacks, {len(moves)} stock movements. Logins: {LOGINS}")
    print("Stock now (with load/empty/at refiller): " + ", ".join(
        f"{label[i]} {loaded[i]}/{empty[i]}/{at_refiller[i]}" for i in range(len(PRODUCTS))))


def sign_in_sessions(port):
    """Sign the demo staff in through the demo server so the dashboard shows live sessions."""
    password = None
    with open(LOGINS) as f:
        for line in f:
            if line.startswith("username:"):
                password = line.split("password:")[1].strip()
    for user in ("ana", "ben"):
        ua, ip = STAFF[user]
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/login",
            data=urllib.parse.urlencode({"username": user, "password": password}).encode(),
            headers={"X-LPG": "1", "User-Agent": ua, "CF-Connecting-IP": ip}, method="POST")
        urllib.request.urlopen(req, timeout=10).read()
    print("Demo staff signed in.")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--sessions":
        sign_in_sessions(int(sys.argv[2]))
    elif len(sys.argv) == 2:
        target = os.path.abspath(sys.argv[1])
        if os.path.abspath(os.path.expanduser("~/POSSystemData")) == target:
            sys.exit("Refusing to write fake data into the real data folder.")
        generate(target)
    else:
        sys.exit(__doc__)
