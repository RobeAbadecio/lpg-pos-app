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
