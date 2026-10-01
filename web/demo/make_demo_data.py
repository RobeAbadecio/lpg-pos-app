#!/usr/bin/env python3
"""Generate FAKE data for the LPG POS demo (never point this at ~/POSSystemData).

    python3 make_demo_data.py DATA_DIR            write ~4 months of fake sales, customers, staff
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

# brand, weight kg, current price, popularity, reorder level, full cylinders kept after a delivery
PRODUCTS = [
    ("Petron Gasul", 11.0, 1045.00, 30, 15, 45),
    ("Solane", 11.0, 1090.00, 24, 12, 40),
    ("Phoenix SuperLPG", 11.0, 1020.00, 14, 8, 25),
    ("Fiesta Gas", 11.0, 1010.00, 10, 6, 20),
    ("Petron Gasul", 22.0, 2090.00, 6, 3, 10),
    ("Solane", 2.7, 365.00, 9, 5, 18),
    ("Petron Gasul", 2.7, 355.00, 5, 4, 12),
    ("Petron Gasul", 50.0, 4650.00, 2, 1, 4),
]
SUPPLIER = {"Petron Gasul": "Petron depot", "Solane": "Solane dealer", "Phoenix SuperLPG": "Phoenix refiller",
            "Fiesta Gas": "Fiesta Gas depot"}
# Deliveries paused for the last N days, so the demo shows low and sold-out stock.
DELIVERY_PAUSED = {3: 9, 5: 5, 7: 30}  # product index -> days

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
    s = str(s)
    return '"' + s.replace('"', '""') + '"' if any(c in s for c in ',"\n') else s


def write_csv(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(",".join(csv_field(x) for x in r) + "\n")


def generate(data_dir):
    rng = random.Random(2026)
    os.makedirs(data_dir, exist_ok=True)
    now = dt.datetime.now().replace(microsecond=0)
    today = now.date()

    # Customers: a few businesses that buy a lot, many households that buy now and then.
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
    # The last few customers were "added" in the past days and buy only recently.
    new_customers = {57: 6, 58: 4, 59: 2, 60: 0}  # customer id -> days ago they were added

    products = [[str(i + 1), b, money(p), kg(w), str(r)] for i, (b, w, p, _, r, _) in enumerate(PRODUCTS)]
    popularity = [p[3] for p in PRODUCTS]

    def unit_price(pidx, day):
        # Monthly LPG price adjustments: September and August were cheaper.
        price = PRODUCTS[pidx][2]
        months_back = (today.year - day.year) * 12 + today.month - day.month
        step = {0: 0, 1: 35, 2: 60}.get(months_back, 85)
        return max(1.0, price - step * PRODUCTS[pidx][1] / 11.0)

    def pick_hour():
        hours = list(range(7, 20))
        w = [3, 6, 7, 6, 5, 3, 3, 4, 5, 6, 6, 4, 2]
        return rng.choices(hours, w)[0]

    txns = []
    moves = []
    start = today - dt.timedelta(days=120)
    tid = 1
    full = [cap for *_, cap in PRODUCTS]
    empty = [cap // 2 for *_, cap in PRODUCTS]
    opening = dt.datetime.combine(start, dt.time(6, 45))
    for i in range(len(PRODUCTS)):
        moves.append([opening, i, "count", full[i], empty[i], "ana", "Opening stock"])

    def on_shift(t):
        return "carla" if t.weekday() >= 5 else ("ana" if t.hour < 13 else "ben")

    for d in range(121):
        day = start + dt.timedelta(days=d)
        days_ago = (today - day).days
        # Morning deliveries on Mon/Wed/Fri: top up anything running low, hand back the empties.
        if day.weekday() in (0, 2, 4):
            when = dt.datetime.combine(day, dt.time(7, rng.randint(5, 40)))
            for i, (brand, w, _, _, reorder, cap) in enumerate(PRODUCTS):
                if days_ago < DELIVERY_PAUSED.get(i, 0) or when > now or full[i] > reorder * 1.6:
                    continue
                got = cap - full[i]
                back = min(empty[i], got)
                full[i] += got
                empty[i] -= back
                moves.append([when, i, "delivery", got, -back, on_shift(when),
                              f"{SUPPLIER.get(brand, 'Supplier')} DR #{rng.randint(10000, 99999)}"])
        # A recount every few weeks finds the odd missing or leaking cylinder.
        if day.weekday() == 5 and d % 21 == 5:
            when = dt.datetime.combine(day, dt.time(18, 30))
            if when <= now:
                i = rng.randrange(4)
                if full[i] > 0:
                    full[i] -= 1
                    kind, note = rng.choice([("damaged", "Leaking valve, set aside for supplier"), ("count", "")])
                    moves.append([when, i, kind, -1, 1 if kind == "damaged" else 0, "carla", note])
        growth = 0.85 + 0.3 * d / 120
        dow = day.weekday()
        factor = {0: 1.15, 1: 1.0, 2: 0.95, 3: 1.0, 4: 1.1, 5: 1.3, 6: 0.55}[dow]
        n = max(2, int(rng.gauss(14 * growth * factor, 3.2)))
        times = []  # today keeps only the sales that have already happened
        for _ in range(n):
            h = pick_hour()
            t = dt.datetime.combine(day, dt.time(h, rng.randint(0, 59), rng.randint(0, 59)))
            if t <= now:
                times.append(t)
        for t in sorted(times):
            days_ago = (today - day).days
            eligible = [i for i in range(60) if new_customers.get(i + 1, 10**6) >= days_ago]
            ci = rng.choices(eligible, [weights[i] for i in eligible])[0]
            pi = rng.choices(range(len(PRODUCTS)), popularity)[0]
            if is_business[ci] and rng.random() < 0.35:
                pi = rng.choice([0, 1, 4])  # businesses favour 11 kg and 22 kg
            if full[pi] <= 0:
                # Sold out: the customer takes another brand of the same size, or leaves.
                same = [i for i, p in enumerate(PRODUCTS) if p[1] == PRODUCTS[pi][1] and full[i] > 0]
                if not same:
                    continue
                pi = rng.choice(same)
            qty = min(full[pi], rng.choices([1, 2, 3], [70, 22, 8] if is_business[ci] else [92, 7, 1])[0])
            swap = rng.random() < (0.95 if is_business[ci] else 0.85)
            full[pi] -= qty
            if swap:
                empty[pi] += qty
            txns.append([str(tid), customers[ci][0], str(pi + 1), t.strftime(TIME), str(qty), money(unit_price(pi, day)),
                         on_shift(t), "1" if swap else "0"])
            tid += 1

    write_csv(os.path.join(data_dir, "Customers.csv"), customers)
    write_csv(os.path.join(data_dir, "LPGs.csv"), products)
    write_csv(os.path.join(data_dir, "Transactions.csv"), txns)
    moves.sort(key=lambda m: m[0])
    write_csv(os.path.join(data_dir, "StockMovements.csv"),
              [[str(n + 1), m[0].strftime(TIME), str(m[1] + 1), m[2], str(m[3]), str(m[4]), m[5], m[6]] for n, m in enumerate(moves)])

    # Staff accounts (one shared demo password, written to demo-logins.txt).
    password = "demo-" + secrets.token_hex(3)
    users = []
    for i, u in enumerate(STAFF):
        salt = secrets.token_bytes(16)
        created = (now - dt.timedelta(days=125 - i * 3)).strftime(TIME)
        users.append([u, b64(salt), pbkdf2(password, salt), created])
    write_csv(os.path.join(data_dir, "WebUsers.csv"), users)
    with open(LOGINS, "w") as f:
        f.write("LPG POS demo logins (fake data, demo server only)\n")
        f.write("POS: http://localhost:8180 (opens without sign-in as ana)   Admin dashboard: http://localhost:8190\n")
        f.write("These logins are only needed to try signing in as ben or carla via /api/login.\n\n")
        for u in STAFF:
            f.write(f"username: {u}   password: {password}\n")

    # Activity log for the last few days, oldest first (the server reads the tail).
    label = {str(i + 1): f"{p[0]} {kg(p[1])} kg" for i, p in enumerate(PRODUCTS)}
    name = {c[0]: f"{c[1]} {c[2]}" for c in customers}
    log = []
    cutoff = now - dt.timedelta(days=3)
    signed_in = set()
    for t in txns:
        when = dt.datetime.strptime(t[3], TIME)
        if when < cutoff:
            continue
        staff, ip = t[6], STAFF[t[6]][1]
        key = (staff, when.date())
        if key not in signed_in:
            signed_in.add(key)
            if rng.random() < 0.3:
                log.append((when - dt.timedelta(minutes=3), staff, ip, "Failed sign-in (Internet)"))
            log.append((when - dt.timedelta(minutes=2), staff, ip, "Signed in (Internet)"))
        cid = int(t[1])
        if cid in new_customers and when.date() == today - dt.timedelta(days=new_customers[cid]) \
                and not any(m.startswith(f"Added customer #{cid} ") for _, _, _, m in log):
            log.append((when - dt.timedelta(minutes=1), staff, ip, f"Added customer #{cid} {name[t[1]]}"))
        amount = int(t[4]) * float(t[5])
        tank = "" if t[7] == "1" else " (new tank)"
        log.append((when, staff, ip, f"Sale #{t[0]}: {t[4]} × {label[t[2]]}{tank} to {name[t[1]]} (₱{money(amount)})"))
        if rng.random() < 0.03:
            log.append((when + dt.timedelta(minutes=4), staff, ip, f"Edited sale #{t[0]}"))
    for when, i, kind, f, e, staff, note in moves:
        if when < cutoff:
            continue
        ip = STAFF[staff][1]
        if kind == "delivery":
            log.append((when, staff, ip, f"Received {f} × {label[str(i + 1)]}" + (f", returned {-e} empties" if e else "") + f" ({note})"))
        else:
            log.append((when, staff, ip, ("Wrote off damaged stock" if kind == "damaged" else "Stock count") + f" for {label[str(i + 1)]} ({note or 'recount'})"))
    first_of_month = dt.datetime.combine(today.replace(day=1), dt.time(7, 5))
    if first_of_month >= cutoff:
        for i, (b, w, p, *_) in enumerate(PRODUCTS[:4]):
            log.append((first_of_month + dt.timedelta(minutes=i), "ana", STAFF["ana"][1], f"Edited product #{i + 1} (price {money(p)})"))
    log.sort(key=lambda e: e[0])
    with open(os.path.join(data_dir, "WebActivity.log"), "w", encoding="utf-8") as f:
        for when, actor, ip, msg in log:
            f.write(f"{when.strftime(TIME)}\t{actor}\t{ip}\t{msg}\n")

    print(f"Fake data written to {data_dir}: {len(customers)} customers, {len(products)} products, "
          f"{len(txns)} sales, {len(moves)} stock movements, {len(users)} staff. Logins: {LOGINS}")
    print("Stock now (full/empty): " + ", ".join(f"{label[str(i + 1)]} {full[i]}/{empty[i]}" for i in range(len(PRODUCTS))))


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
