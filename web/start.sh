#!/bin/bash
# Start the LPG POS web server on this Mac.
#
#   ./start.sh            POS on port 8080 (this Mac + same Wi-Fi), admin dashboard on http://localhost:8090
#   ./start.sh --tunnel   same, and also turn on the public internet link (needs: brew install cloudflared)
#
# Data lives in ~/POSSystemData, the same folder the desktop (Swing) app uses.
set -euo pipefail
cd "$(dirname "$0")"

if ! java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
  echo "Java 21 or newer is required." >&2
  exit 1
fi

# Recompile when any source file is newer than the last build.
if [ ! -f build/.stamp ] || [ -n "$(find src -name '*.java' -newer build/.stamp)" ]; then
  echo "Compiling…"
  rm -rf build && mkdir -p build
  javac -d build src/*.java
  touch build/.stamp
fi

# caffeinate keeps the Mac from idle-sleeping while the server runs.
exec caffeinate -i java -Dlpg.web="$PWD" -cp build WebServer "$@"
