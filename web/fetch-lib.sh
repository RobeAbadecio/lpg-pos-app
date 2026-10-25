#!/bin/bash
# Downloads the one library the server needs (SQLite driver) into web/lib and checks it against a pinned SHA-256.
# start.sh runs this automatically when the file is missing.
set -euo pipefail
cd "$(dirname "$0")"
JAR=lib/sqlite-jdbc-3.46.1.3.jar
SHA256=4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa
URL=https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar

sum() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if [ -f "$JAR" ] && [ "$(sum "$JAR")" = "$SHA256" ]; then exit 0; fi
mkdir -p lib
curl -fsSL --retry 4 -o "$JAR.part" "$URL"
if [ "$(sum "$JAR.part")" != "$SHA256" ]; then
  rm -f "$JAR.part"
  echo "Downloaded SQLite library has the wrong checksum; not using it." >&2
  exit 1
fi
mv "$JAR.part" "$JAR"
echo "SQLite library ready: $JAR"
