# Editing records (admin dashboard)

Open the admin dashboard on the server Mac (http://localhost:8090) and scroll to **Edit records**. It never appears on the staff link or the public tunnel.

1. Pick what to fix under **Records** (Receipts, Sale lines, Customers, Products, Stock changes, Supplier payments…). The list loads by itself, newest first, with a short description of that table.
2. Find the record with **Search**. You can search by names too, not just IDs. Click a column heading to sort by it.
3. Click the row (or **Edit**). Customers, products and suppliers are picked from lists; dates use a date picker; yes/no fields are Yes/No. A box shows exactly what you changed (old → new).
4. Say **why** you're making the change, then **Save changes**. **Delete** shows the whole record before removing it. **Add record** fills in the next ID and the current time.
5. After saving, **Undo** reverses your last change. It is checked like any other change, so it is refused if someone changed records since.

Every save first stores a full copy of all business tables plus your reason under `POSSystemData/admin-backups/<id>/`, and is written to the activity log. The server refuses changes that would break things: negative stock, more tanks returned than owed, payments above a bill, links to records that don't exist. Fix linked records together (for example the sale line and its receipt). If records changed since you opened the table, reload first. Staff passwords are not in this editor; use the staff account controls.
