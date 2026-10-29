# Changelog

Every update gets a new version number. It's shown in the POS (header, account menu and sign-in page) and on the admin dashboard, and is set in [`web/public/shared/version.js`](web/public/shared/version.js). Each version is also tagged on GitHub (`v2.0.0`, …).

Numbering is `major.minor.patch`: **major** when the data or the way staff work changes a lot, **minor** for new features, **patch** for fixes and small changes.

## 5.5.1 — 2026-10-29: UI check against common UX rules (Jakob, Fitts, Hick and accessibility)

Look and layout only; no data or money changes.
- **Readable buttons**: the blue main button was a bit too light for white text (4.4:1). It's a little darker now (4.9:1). In dark mode main buttons use dark text, so every store color is readable.
- **Bigger touch targets on phones and tablets**: small buttons, the ⋯ menu, the dialog ✕, the + / − steppers and the (?) icons are all at least 44 px to tap.
- **No zoom-in when typing on phones**: every input on phones uses 16 px text (iPhones zoom into smaller text).
- **Dashboard tabs on phones**: one row you can swipe sideways, like the Inventory tabs, instead of a 3 × 2 block. A soft fade on the right shows there are more tabs.
- **Daily summary on phones**: ‹ date › stays on one row; the date picker sits below at full width.
- **Same word everywhere**: the phone bottom bar says "Inventory", like the side bar and the page title (it said "Stock").
- **Team page on phones**: each staff row shows "Status" and "Dashboard" labels, and the dashboard checkbox is bigger.
- **Screen readers**: every page has a main heading; empty table headings say "Actions"; the "By size" table can be scrolled with the keyboard; the cart side panel is no longer marked as a separate landmark.
- Sign-up page: "Sign in" is a proper button.

## 5.5.0 — 2026-10-28: safer for paying stores (pilot readiness)

**Who can change what** (the server checks; the buttons are hidden for staff who can't)
- Staff without dashboard access can still sell, take payments, add and edit customers, buy tanks, send and receive refills, and pay suppliers.
- They can **void or edit only their own sales, and only in the first 30 minutes** (the same rule as Undo). Before, any staff member could void or edit any receipt of any day, which would let money from a sale disappear without the owner noticing.
- **Prices and products** (add, edit, delete), **stock counts and write-offs**, **editing or deleting a supplier** and **deleting a customer** are now for the owner and staff with dashboard access only. Give a trusted staff member dashboard access on the Team page if they need these.

**A sale or payment is never saved twice**
- Each new sale and payment carries its own number. If the signal drops after the server saved it and the cashier taps again, the server answers with the first receipt instead of saving a second one (also when tapped many times at once).

**A crash can't leave half a sale**
- A change that writes several files (a sale: the receipt and its items) first keeps a copy of each file it changes in the store's `.journal` folder. If the server stops half-way (crash, power cut, full disk), the next start puts those files back, so nothing is half-saved. A write that fails puts them back at once.

**Backups**
- Each store's folder is copied while its records are not being written, so a backup never holds a sale without its items.
- Fix: two backups in the same second (start-up and "Back up now") failed with "Directory not empty". The second one is now named `…-2`.

**Other**
- `/healthz` answers `{"ok":true}` when the server and its database work, for a free uptime monitor. It shows no store data.
- Sign-in: wrong passwords from one connection no longer lock the account for everyone; the per-account limit is higher (24 in 15 minutes) and the per-connection limit stays at 8.
- New test: `PilotCheck` (permissions, sent twice, two stores kept apart by id, crash and failed-write recovery, backups taken during sales, health link, sign-in limits).

## 5.4.0 — 2026-10-25: money check, and a (?) beside every important figure

**Fix: raising (or cutting) a salary changed what was owed for pay days already past**
- "Still owed" compared every pay day since the employee was added with today's salary. After a raise from ₱5,000 to ₱6,000, pay days already paid in full showed ₱1,000 owed each, and **Pay salary** would let that ₱1,000 be paid again (a cut hid salary really owed).
- A new salary now counts for pay days from the day it's entered (or the day the edit is dated) on. Pay days before keep the salary they had (saved in a new `rates` column in Employees.csv). Each pay day in **History** shows its own salary.
- **Edit employee** asks, when the salary changes: "Pay days from today on (a raise or a cut)" or "Every pay day (the salary was entered wrong)".
- Salaries changed before this version have no earlier salary saved, so their past pay days still use today's salary. Check **Salaries** once after updating.

**Fix: centavo rounding**
- Amounts are rounded to the centavo on their decimal value, halves away from zero. Before, ₱1.005 could round down to ₱1.00 (a percent discount, e.g. 0.1% of ₱1,005, came out ₱0.01 short). Saved records are not changed.

**(?) How is this worked out?**
- A small **?** sits beside every figure that can explain itself. Tap it (or press Enter on it) to see what the figure is, how it's worked out, the amounts that add up to it (= Result) and the records behind it. The server works it out with the same rules as the figure, and the tests check the two match.
- New: a customer's **balance** and **what all customers owe** (Customers), **what is owed to a supplier** and to all suppliers (Inventory → Suppliers, admin dashboard), **stock** per product and **tanks in all** (Inventory), **salary still owed** per employee and in all (Salaries), and on the daily summary **Expenses**, **Salaries** and **Cash in the drawer**.
- Customer, supplier and stock explanations are open to all staff (they already see those figures); the rest need dashboard access, as before. Each business only ever sees its own records.
- The daily tile "Cash from customers" is now "Money from customers": it always included GCash.

**Other**
- New test: `MoneyAuditCheck` (147 checks): a small store worked out by hand (discounts, swap fees, pay later, payments, a void and an edit, tanks owed and returned, a refill trip, staff and owner money, a purchase on credit, expenses), every figure checked against the hand-worked amount and across the daily summary, reports, dashboard and explanations; salary changes; rounding; two businesses kept apart. `ExplainCheck` now also recomputes every balance from the records.

## 5.3.0 — 2026-10-25: each store's own look, and a setup guide for new stores (phase 4)

**Your store's look** (account menu → **Team & store details** → Your store's look; store owner only)
- **Logo**: a PNG or JPG. It's made smaller on the phone or computer before it's sent (at most 320 px), and the server only takes a real PNG or JPEG of at most 200 KB (checked from the file itself). It's kept in the store's own folder, so it's in the backups, and only people signed in to that store can see it.
- **Color**: blue (standard), green, teal, purple, orange or rose. It changes the buttons, tabs and highlights in the POS, Team and Billing pages, in light and dark mode. Each phone remembers it, so pages don't flash blue first.
- **Receipt message**: up to 120 characters printed at the bottom of every receipt (for example a thank-you and a delivery number).
- The POS sidebar (and the top bar on phones) shows the store's logo and name instead of "LPG POS".
- Receipts, on screen and printed, now start with the logo, the store name, address and mobile number (from Store details). Before, they said "LPG POS".
- Staff see the look but can't change it. Changes are in the store's activity log and the platform audit log.

**Setup guide** (New sale page, store owner only)
- "Get your store ready" lists the first steps, each with a button that goes there: make it yours (logo, color or receipt message), add LPG tanks, add suppliers, enter the starting stock, add staff, pay the first month (only when billing is on) and record the first sale.
- Each step ticks itself from the store's own records, with a progress bar. The guide goes away when every step is done, or when the owner taps **Hide**; **Show it again** is on the Team page.

**Behind the scenes**
- Database step 4: `brand_color`, `receipt_note`, `logo` and `guide_hidden` on businesses. Older versions ignore these columns, so going back to 5.2.1 keeps working.
- New calls: `PUT /api/business/brand`, `GET|POST|DELETE /api/business/logo`, `GET|POST /api/guide`; `/api/me` has `brand`.
- New check: `BrandingCheck` (55 checks).
## 5.2.2 — 2026-10-25: printed receipts show only the tanks bought

- On a swap, the printed receipt lists the tank the customer bought and the swap fee, not the empty they gave back. The empty and its brand still show on the receipt on screen, in Edit and in the records, and stock still counts it.
- A tank the customer owes, or a new tank with no empty, is still noted on paper.

## 5.2.1 — 2026-10-25: staff salaries owed per pay day

**Fix: the salary balance didn't change after paying a salary**
- The balance was worked out as salary − unpaid advances, so recording a salary never lowered it (and taking an advance off a salary made it go up). It is now **Still owed**: the salary not yet paid for each pay day that has come since the employee was put on the payroll.
- Each salary counts for one pay day. **Pay salary** asks which pay day it is for (the oldest one not fully paid is picked, then the next one), so early and late payments land on the right pay day. The pay day is saved with the salary (new `payday` column in Expenses; older salaries count for the nearest pay day and say "by date").
- A pay day can't be paid twice: a salary more than what's left for that pay day is refused, and so is a day that isn't one of the employee's pay days.
- Advances stay apart: an advance is not a salary payment; salary = handed over + advance taken off.

**Last pay day** (Dashboard → Salaries)
- Each employee shows their last pay day with **Paid**, **Partially paid**, **Unpaid** (it's today), **Overdue** (it has passed) or **Unknown** (before they were added to the payroll, so no record says), what was paid for it and what's left, and money already paid toward the next pay day.
- **Pay salary** is the main button while salary is owed. **History** lists the recent pay days with the salaries that paid each one, above all records.

## 5.2.0 — 2026-10-25: MariBank emails check payments, phase 3

**Reading MariBank's emails** (platform admin → **MariBank emails**)
- The server reads MariBank's "money received" emails (MariBank Transfer Notification, from alerts@maribank.com.ph) in a Gmail box, every 2 minutes. It signs in with a Gmail **app password** and opens the inbox **read-only**: nothing is marked read, moved or deleted.
- An email is only used when Gmail says MariBank really sent it (MariBank's DKIM signature and DMARC passed). A fake email that only looks like MariBank's is listed as "Not trusted" and does nothing.
- Each email gives the time, amount, the payer's bank and the Reference No (the same number the payer's app shows). The same transfer is saved once.
- The app password is kept in a private file next to the data (`mail-password`, owner-only), never in the database, the backups or the page.

**Matching**
- When a store owner sends a reference that a MariBank email also shows, the payment gets a green "MariBank email" line in **Payments to check**. Without one, the queue says "No MariBank email with this reference yet". A different amount is pointed out.
- **Auto-approve** (off by default): approves a payment by itself when the email's reference matches and its amount is exactly what the invoice asks (every open invoice has its own centavos), paid on or after the invoice date. The audit log shows "bank email" as who approved it.
- An email nobody sent a reference for, whose amount is exactly one open invoice's, is suggested for that store: **Approve for <store>** pays it in one click. This is never automatic.
- An email that isn't a store fee can be marked **Not a fee**. Rejecting a payment frees its email again.

**Other**
- New test: `MailCheck` (75 checks, with a fake mailbox). Saved emails can be checked by hand with `java -cp build:lib/* BankMail file.eml`.

## 5.1.0 — 2026-10-25: monthly billing, phase 2

**Monthly fee per business** (platform admin → Billing settings)
- Price per month (₱10 to start) and grace days after the paid date (7). Billing is **off** until the admin turns it on, after uploading the MariBank QR code. Business 1 (the platform owner's own store) never pays; any store can be made free on its **Billing** button.
- **Pay first:** when billing is on, a business that has never paid (a new one once approved, and every existing one when billing is turned on) can sign in and look around, but can't save anything until its first payment is approved. Its first month starts on the day of approval. The admin can give free days instead (setting, 0 by default).
- Each business is paid up to a date. A week before that date it gets an invoice for the next month. Every open invoice has its own amount (the price plus a few centavos, for example ₱10.37) so payments are easy to tell apart in the MariBank history.
- After the paid date the POS keeps working for the grace days, with a warning bar. After that the store is **overdue**: everyone can still sign in, see their records and pay, but new records can't be saved until the payment is approved.

**Paying** (store owner → account menu → **Billing**)
- Shows the amount, the payment QR and short steps. The owner types the reference number from their receipt, the amount and date, and may add a screenshot (shrunk on the phone before sending; PNG or JPEG only, checked by content).
- A reference number can be used once across all businesses; an invoice can have one payment waiting at a time.

**Checking payments** (platform admin → **Payments to check**)
- Waiting payments first, with warnings (wrong amount, a reference sent before, paid before the invoice existed) and the receipt picture. Approve adds one month (from the day of approval if the store was locked); reject asks for a reason the owner sees.
- Approving is a single conditional database update, so a payment is applied once even if Approve is clicked many times.
- The admin can also set a "paid until" date directly, for cash payments.

**Other**
- Backups now include `platform-files/` (QR code and receipt pictures).
- New test: `BillingCheck` (80 checks).

## 5.0.0 — 2026-10-25: multi-business platform, phase 1

**Several businesses on one server**
- Each business has its own data folder (`tenants/<id>/`) with the same CSV files as before. Which business a request belongs to comes only from the signed-in session; a business id sent by the browser is ignored.
- On first start, the existing store's files are **copied** into business 1. The original files stay where they were, for rolling back.
- Accounts, businesses and the audit log live in a small SQLite database (`platform.db`). Usernames are unique across all businesses. The database itself allows only one active owner per business.

**Sign-up and approval**
- New businesses register at `/signup.html` and wait for the platform admin's approval. Staff ask to join with their store's 6-letter code; the store owner approves them.
- The store owner gets a **Team** page (account menu): approve or reject sign-ups, add staff, disable or enable them (a disabled person is signed out at once), reset passwords, dashboard access, store details.

**Platform admin** (localhost only, now with a password set on first visit)
- `/platform.html`: approve, reject, suspend, reactivate or deactivate businesses; their accounts (approve, disable, reset password, change owner); health (database check, disk space, last backup); audit log.
- The existing store dashboard shows the business picked there.

**Backups**
- At start-up and daily: a consistent copy of `platform.db` plus all business folders, in `platform-backups/` (14 kept). Restore with `java -cp "build:lib/*" Backups restore <backup> <data folder>`.

**Setup**
- One library added: the SQLite driver. `web/fetch-lib.sh` downloads it and checks a pinned SHA-256; `start.sh` runs it.

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
