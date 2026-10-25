# Admin database correction checks

From the repository root, compile into an isolated temporary directory:

```sh
javac -d /private/tmp/lpg-admin-check-build web/src/*.java web/tests/*.java
java -cp /private/tmp/lpg-admin-check-build DatabaseEditorCheck
java -cp /private/tmp/lpg-admin-check-build AdminDatabaseApiCheck
java -cp /private/tmp/lpg-admin-check-build RevenuePeriodsCheck
java -cp /private/tmp/lpg-admin-check-build SupplierSummaryCheck
node web/tests/DatabaseUiCheck.js
```

These checks use generated fake records and a mocked HTTP exchange. They do not connect to the running POS or alter real business data. The UI check exercises rendering and request payloads using a small DOM stub; it does not verify visual layout in a browser.

## Multi-business platform check

Needs the SQLite library (`web/fetch-lib.sh` downloads it). Runs both servers on free ports with a throw-away data folder:

```sh
web/fetch-lib.sh
javac -d /tmp/lpg-check -cp "web/lib/*" web/src/*.java web/tests/*.java
java -cp "/tmp/lpg-check:web/lib/*" PlatformCheck
```

It covers: copying the single-store data into business 1, platform admin password, business sign-up and approval,
staff joining with the store code, isolation between businesses, owner-only Team actions, disabling and suspending
(sessions end at once), one active owner per business, simultaneous sign-ups, the audit log, and backup + restore.

## Billing check

Same setup as the platform check. Moves the calendar to test due dates, grace days and the overdue lock:

```sh
java -cp "/tmp/lpg-check:web/lib/*" BillingCheck
```

It covers: billing off and on, free days, invoices with their own amounts, sending a payment (reference, amount, date,
picture checked by content), one use per reference across businesses, approving exactly once under many clicks,
rejecting, grace and lock (records still readable), free stores, paid-until for cash, the QR code, and backups with pictures.

## MariBank email check

Same setup. Runs a fake mailbox (IMAP) with made-up MariBank emails:

```sh
java -cp "/tmp/lpg-check:web/lib/*" MailCheck
```

It covers: reading the email (quoted-printable and base64), trusting only emails Gmail says MariBank signed (fake
senders, failed or look-alike signatures, a faked header below Gmail's), read-only mailbox commands, saving each
transfer once, matching by reference and exact amount, suggest-only and auto-approve, amount-only suggestions and
the admin's one-click approve, wrong amounts, rejected payments, "not a fee", and the app password kept out of the
database, the page and the backups.
