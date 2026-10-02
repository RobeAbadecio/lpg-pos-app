# Admin database correction checks

From the repository root, compile into an isolated temporary directory:

```sh
javac -d /private/tmp/lpg-admin-check-build web/src/*.java web/tests/*.java
java -cp /private/tmp/lpg-admin-check-build DatabaseEditorCheck
java -cp /private/tmp/lpg-admin-check-build AdminDatabaseApiCheck
node web/tests/DatabaseUiCheck.js
```

These checks use generated fake records and a mocked HTTP exchange. They do not connect to the running POS or alter real business data. The UI check exercises rendering and request payloads using a small DOM stub; it does not verify visual layout in a browser.
