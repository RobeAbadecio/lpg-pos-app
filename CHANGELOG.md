# Changelog

Every update gets a new version number. It's shown in the POS (header, account menu and sign-in page) and on the admin dashboard, and is set in [`web/public/shared/version.js`](web/public/shared/version.js). Each version is also tagged on GitHub (`v2.0.0`, …).

Numbering is `major.minor.patch`: **major** when the data or the way staff work changes a lot, **minor** for new features, **patch** for fixes and small changes.

## 4.1.0 — 2026-10-24

**Staff salaries** (Dashboard → **Salaries**, for staff with dashboard access; view-only on the admin page's Money tab)
- Add each employee with their **salary** and **pay schedule**: weekly (pick the day), twice a month (two days) or monthly (one day).
- A table shows **Salary, Schedule, Next payday, Advanced** (unpaid advances) and **Balance** (salary − advances: what to hand over on pay day).
- **Give advance** for an employee. **Clear advance**: they pay it back in cash, or it's taken off their salary with **Pay salary**, which shows what to hand over. **History** lists every salary, advance and pay-back.
- Salaries and advances are **no longer counted as expenses**. Money and the Daily summary show them separately as **Salaries**; cash on hand still counts them, and an advance paid back is cash in. The expense form no longer offers salary or advance.
- Salary and advance records from before 4.1 are kept as they are. Names on them show in the table as "Not set up yet": tap **Set salary** to add their salary and schedule.

**Generic and other-brand empties**
- "Empty in" on a sale, and "Empty tank brought back" on Collect tank, have two new choices: **Generic (no brand)** and **Other brand** (type the brand). The swap fee applies, and staff can change it.
- These empties go into **no brand's stock** (they can't be sent to a brand's refiller). Inventory → Stock on hand has a new **Generic & other-brand empties** card, by size and brand.

**Data**
- New file `Employees.csv`. New columns `emptyKind` and `emptyBrand` in `Transactions.csv` and `TankReturns.csv`. New expense category `repaid` (an advance paid back).
- The server code changed, so the update restarts the POS and the **staff link changes**.

## 4.0.0 — 2026-10-24

A new look for the POS and the admin page (Figma "04 · Redesign (UI/UX)"). Screens and buttons only: no data or server changes, and every feature works as before.

**Finding your way**
- The top bar is now a **sidebar** on the left: New sale, Receipts, Customers, Products, Inventory (with the refill alert) and Dashboard. Your name and Sign out are at the bottom. Tablets get a narrow icon sidebar; phones keep the bottom tabs.
- Keyboard: **F1–F6** open the sections, **Ctrl+Enter** processes the sale.
- Each screen has a big title, a one-line summary and its main button at the top right.

**Lists**
- One table style everywhere. Status shows as a dot and a word: green paid / in stock, amber owed / tanks out / low, red unpaid / out of stock.
- Customers, Receipts, Products and Suppliers show one main button per row (e.g. Collect payment, Record payment, Edit, Pay). Everything else, including Delete and Void, is in the **⋯** menu. Delete still asks first.
- Filter chips with counts: Customers (All / Owes money / Has tanks out) and Receipts (All / Unpaid / Paid). Search still works with them.

**New sale**
- Tank-size chips over the tanks. Tanks with none left show a red "Out of stock".
- Quick cash buttons (Exact and round amounts) and a **Change** box.
- A bigger **Process sale · ₱total** button.

**Inventory**
- Split into tabs: Stock on hand, Tanks out, At refiller, Suppliers, Staff money and Stock history, with counts. The refill alert stays on top and gets a "Send empties now" button.

**Dialogs**
- Title with a ✕ on top; Cancel and the main button at the bottom right; Void on the far left in red.

## 3.9.0 — 2026-10-08

The production version that ran from early October and moved to the Ubuntu server on 17 October. Versions 3.3 to 3.8 went live one by one but were never pushed separately, so this release holds all of them together.

**Dashboard reports**
- The POS Dashboard has **Daily summary**, **Sales**, **Suppliers**, **Money** and **Starting balances**. Sales, Suppliers and Money can be shown by day, week, month or quarter.
- Tap a number to see **how it was worked out**.
- **Money** shows cash on hand, money in and out, and expenses: delivery gasoline, salaries, salary advances and other costs. A salary takes off the employee's unpaid advances.

**Starting balances**
- Enter what customers owed, tanks they still had, stock on hand and supplier balances on the start date.

**Fixing mistakes**
- **Undo** and **Edit** on sales, payments, refills, stock changes, tank returns, supplier payments and paybacks. Staff can change what they entered in the last 30 minutes; staff with dashboard access can change anything. Something that later records depend on can't be undone, and the message says why.
- A help page (account menu → **Fixing mistakes**) says where to fix each kind of record.
- Staff with dashboard access can enter a record for an earlier day, and move a receipt to another day.

**Stock and suppliers**
- **Record refill**: empties go out and come back filled in one step.
- Supplier payments can also come from the **owner's personal money**, paid by cash, check or online.
- **Customers' own tanks** kept at the store, separate from the store's stock. They can be used as the empties on a sale.

**Other**
- A red bar shows when the POS server can't be reached, so nobody types a sale that won't save.

## 3.2.0 — 2026-10-02

**Edit records (admin dashboard)**
- A new **Edit records** card on the admin dashboard fixes mistakes in any record: receipts, sale lines, customers, products, stock changes, refiller trips, supplier payments, tank returns and more.
- Plain column names, and customers, products and suppliers show by name. Newest first, search by name, and click a heading to sort.
- Click a row to edit it. Customers, products and suppliers are picked from lists, dates use a date picker, and yes/no fields are Yes/No. A box shows exactly what you changed before you save.
- Every change needs a reason and saves a full backup first (`POSSystemData/admin-backups/`). **Undo** reverses your last change.
- Changes that would break things are refused: negative stock, more tanks returned than owed, payments above a bill, links to missing records. Only on the server Mac, never on the staff link.

**Supplier history (POS)**
- Inventory → Suppliers → **History** shows everything done with that supplier: refill trips (sent, back, still out), new tanks bought, and payments (from sales or a staff member's own money), with billed, paid and still owed. Filter by date and type.
- Only staff with dashboard access see it.

**Tanks with customers**
- The tank-size table has a **With customers** column (tanks customers still owe). The "All sizes" row is gone.

## 3.1.0 — 2026-10-02

**Daily summary**
- The Dashboard opens on **Daily summary**: one day's sales, cash from customers, money paid to suppliers, cash left from sales, estimated profit, tanks sold, tanks in and out, money details and that day's receipts.
- **‹ ›**, a date picker and a **Previous days** table (the last 14 days) open earlier days. **Trends** is the range view as before.
- The admin dashboard has the same daily summary.

**Tanks instead of kg**
- The kg of LPG is gone. Each tank size shows its **total tanks** (with load + empty + at the refiller), with an all-sizes total.

**Who paid the supplier**
- Paying a refiller or supplier (on receiving, Pay refiller, Pay, Buy new tanks) records **from sales** and **staff's own money** separately, plus whose money it was, with the total paid.
- A **Staff money** card shows what the store owes each staff member, with **Pay back**.
- The dashboards split "Paid to suppliers" by source and show what's **owed to staff**.

**Data**
- New columns `source` and `paidBy` in `SupplierPayments.csv`; older payments count as paid from sales.
- New file `StaffPaybacks.csv`.

## 3.0.1 — 2026-10-02

- Admin dashboard: dashboard access for each staff account is now a clear on/off **switch**. Before, it was a button that looked like plain text. If saving fails, the switch flips back and says why.
- A page left open from before an update (a staff phone, the admin tab) shows **"LPG POS x.y.z is ready"** with **Reload** and **Later**. It never reloads by itself, so a half-typed sale isn't lost.
- Connection problems now say "Can't reach the POS server. Check the internet connection and try again." instead of a technical browser message.

## 3.0.0 — 2026-10-01

Built from the store's second round of feedback.

**Selling**
- No more "new tank" sales. When a customer brings no empty, choose **None**: they pay the normal price and owe the tank.
- **Swap fee:** when the empty is another brand, the product's swap fee (set in Products) is added per tank. Staff can change it on the sale.
- The empty must be the same size as the tank bought.
- Picking a different customer starts an empty receipt.
- The customer list shows each customer's last purchase and how many tanks they owe.

**Tanks not returned**
- A list of customers who still owe tanks, which tanks, and since when (Inventory, and both dashboards).
- **Collect tank** records an empty brought back. Another brand is accepted, with a remark.

**Suppliers**
- Buying new tanks can be paid in part, with the rest owed to the brand's supplier.
- Each supplier shows what's still owed (refill trips and new tanks), with a **Pay** button that settles the oldest bills first.

**Dashboard for chosen staff**
- On the admin dashboard, each staff account has a **Dashboard: on/off** switch.
- Staff with it on get a Dashboard tab in the POS: sales and profit, tanks by size, tanks not returned, unpaid balances and supplier balances.

**Stock by size**
- Totals per tank size (with load, empty, at the refiller) and the kg of LPG in the tanks with load.

**Easier to use**
- Small buttons are bigger on phones.
- Tab labels are the same on phones and computers, and the top bar fits tablets.

**Data**
- New file `TankReturns.csv`.
- New columns: `owesTank` and `swapFee` on receipt items, `swapFee` on products, `dashboard` on staff accounts.
- Older sales without an empty stay as new-tank sales and aren't counted as owed.

## 2.0.1 — 2026-10-01

- Each supplier refills only its own brand. The supplier form takes one brand, picked from your products' brands, and each brand has one supplier.
- Sending empties to a supplier that doesn't refill that brand is refused.
- A supplier's brand can't change while its tanks are still at the refiller.

## 2.0.0 — 2026-10-01

Built from the store's list of changes.

**Selling**
- Several LPG tanks on one receipt, each with its own quantity and price.
- Sell a tank without an empty in return (new-tank price).
- Customers can bring an empty of a different brand.
- Each product has a refill price and a new-tank price, and staff can change the price per item. Each receipt can take an optional discount in ₱ or %.
- Pay in full, pay part now, or pay later. Customers show their balance, and staff can collect payments later.
- A remark per item for the tank's condition (for example "dented"). It prints on the receipt and can be searched.

**Inventory**
- Stock is shown as tanks **with load** and **empty** tanks.
- Refiller trips: empties sent out show as "at refiller" until they come back. Trips can come back in parts, and tanks returned unfilled count as empties again.
- Suppliers per brand, with the inventory grouped by supplier.
- Paying the refiller: each product has a refill cost and a new-tank cost. Each trip shows its bill, what was paid and what's still owed, and the refiller can be paid now or later. New-tank purchases record their cost.

**Admin dashboard**
- New: cash collected, money paid to suppliers, and estimated gross profit. Profit only counts products with a refill cost.
- Unpaid customer balances, tanks at the refiller, and what's owed to refillers.
- The version number is shown.

**Under the hood**
- Data is compressed, and the POS only downloads it again when something changed. This saves mobile data.
- New data files: `Receipts.csv`, `Payments.csv`, `Suppliers.csv`, `Refills.csv` and `SupplierPayments.csv`. Sales recorded before this version show as one-item, paid receipts numbered "S" plus the old sale number.

## 1.1.0 — 2026-10-01

- Inventory: full and empty cylinders on hand, deliveries, stock counts, write-offs and low-stock alerts. A sale can't take more than is in stock.
- The dashboard shows stock levels, sales per day and the estimated days of stock left.

## 1.0.0 — 2026-10-01

- Web version of the desktop POS. Staff sign in on their phones or laptops. The admin dashboard runs on the host Mac.
- A public HTTPS link (Cloudflare quick tunnel), and the server starts in the background at login.
- A demo with fake data.
