# LPG POS

A point-of-sale system for an LPG (cooking gas) retail store. Staff record sales from their phones or laptops. The owner watches sales, stock and staff activity from an admin dashboard on the Mac that hosts it.

It started as a Java Swing desktop app (`src/`). The web version in `web/` uses the same CSV data, so both read the same files.

## Features

**Staff POS** (phone-friendly web app, sign-in required)
- Record sales: search a customer, tap a cylinder, set the quantity, and mark it as a refill swap or a new tank
- Sales history with search, edit and delete
- Customers and LPG products
- Inventory: full and empty cylinders on hand, deliveries, stock counts, write-offs, and low-stock alerts. A sale can't take more than is in stock.

**Admin dashboard** (only reachable on the host machine)
- Revenue, sales, cylinders sold, average sale and customers served, compared with the previous period
- Revenue by day or hour, by product and by staff member; top customers
- Stock levels with sales per day and estimated days of stock left
- Who's signed in right now (and from where), with the option to sign people out
- Staff accounts, the activity log, and the public-link switch

## How it works

- Plain Java 21: the JDK's built-in `HttpServer`, with no frameworks or build tool. The front end is vanilla HTML, CSS and JS.
- Two servers in one process:
  - **POS** on port `8080`, on all network interfaces, with staff sign-in
  - **Admin** on port `8090`, bound to `127.0.0.1` only, so it can't be reached from the network or through the tunnel
- Remote access goes through a [Cloudflare quick tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/) (`cloudflared`). It gives an HTTPS address without port forwarding. Only the POS port is exposed.
- Data is stored as CSV files in `~/POSSystemData` (or `LPG_DATA_DIR`). Stock isn't stored as a number: it's calculated from the stock movements plus the sales.

### Security
- Passwords are hashed with PBKDF2-SHA256 (210k iterations). Sessions use HttpOnly, SameSite=Strict cookies, with `Secure` when served over HTTPS.
- Sign-in is throttled per IP and per username.
- Every request that changes data needs a custom header (protection against cross-site request forgery).
- A strict Content-Security-Policy is set, and all user data is rendered as text.
- The admin server checks the `Host` header, which blocks DNS-rebinding attacks.

## Run it

Requires Java 21+ (macOS or Linux).

```bash
cd web
./start.sh            # POS http://localhost:8080 · admin http://localhost:8090
./start.sh --tunnel   # also open a public HTTPS link (needs: brew install cloudflared)
```

Open the admin dashboard and create a staff account. Staff then sign in to the POS.

**Run it in the background on macOS** (starts at login and restarts if it stops, with the public link on):

```bash
web/install-service.sh     # remove with web/uninstall-service.sh
```

| Variable | Default | |
|---|---|---|
| `LPG_PORT` | `8080` | POS port |
| `LPG_ADMIN_PORT` | `8090` | Admin port (localhost only) |
| `LPG_DATA_DIR` | `~/POSSystemData` | Where the CSV files live |

## Demo with fake data

```bash
web/demo/demo.sh   # POS http://localhost:8180 (no sign-in) · admin http://localhost:8190
```

This generates about 4 months of made-up sales, customers, staff and stock. It never touches the real data folder.

## Data files

| File | Columns |
|---|---|
| `Customers.csv` | id, firstName, lastName, contactNo, address |
| `LPGs.csv` | id, brand, price, weight[, reorderLevel] |
| `Transactions.csv` | id, customerId, lpgId, dateTime[, qty, unitPrice, staff, returnedEmpty] |
| `StockMovements.csv` | id, dateTime, lpgId, type, fullDelta, emptyDelta, staff, note |
| `WebUsers.csv` | username, salt, hash, created |

The columns in brackets are web-only. The desktop app ignores them. The CSV files in this repo's root are the original desktop app's sample data.

## Project layout

```
src/               original Swing desktop app
web/src/           Java web server (POS + admin APIs, storage, auth, stats, tunnel)
web/public/app/    staff POS front end
web/public/admin/  admin dashboard
web/demo/          fake-data demo
```
