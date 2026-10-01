#!/bin/bash
# Temporary demo of the LPG POS with FAKE data. Your real data (~/POSSystemData) and the
# real server (ports 8080/8090) are not touched.
#
#   ./demo.sh     regenerate fake data and run the demo:
#                 admin dashboard  http://localhost:8190
#                 staff POS        http://localhost:8180   (no sign-in: opens as demo staff "ana")
#
# Stop it with Ctrl+C. To remove the demo completely, delete this demo folder.
set -euo pipefail
cd "$(dirname "$0")"

# Already running? Don't regenerate its data underneath it.
if lsof -nP -iTCP:8190 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "The demo is already running: admin dashboard http://localhost:8190 · POS http://localhost:8180"
  exit 0
fi

python3 make_demo_data.py data

# Compile into the demo's own folder so the real server's build/ is never rewritten while it runs.
rm -rf build && mkdir -p build
javac -d build ../src/*.java

# Once the server is up, sign two demo staff in so "Signed in now" isn't empty.
( for _ in $(seq 1 30); do
    curl -s -o /dev/null http://localhost:8190/ && { python3 make_demo_data.py --sessions 8180; break; }
    sleep 1
  done ) &

LPG_PORT=8180 LPG_ADMIN_PORT=8190 LPG_DATA_DIR="$PWD/data" LPG_AUTO_LOGIN=ana \
  exec java -Dlpg.web="$(cd .. && pwd)" -cp build WebServer
