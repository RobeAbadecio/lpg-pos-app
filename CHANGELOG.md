# Changelog

Every update gets a new version number. It's shown in the POS (header, account menu and sign-in page) and on the admin dashboard, and is set in [`web/public/shared/version.js`](web/public/shared/version.js). Each version is also tagged on GitHub (`v2.0.0`, …).

Numbering is `major.minor.patch`: **major** when the data or the way staff work changes a lot, **minor** for new features, **patch** for fixes and small changes.

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
