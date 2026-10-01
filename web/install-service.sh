#!/bin/bash
# Run the LPG POS server in the background whenever you log in to this Mac,
# with the public Cloudflare link switched on. Restarts automatically if it stops.
#
#   ./install-service.sh      install (or reinstall) and start it now
#   ./uninstall-service.sh    stop it and remove auto-start
#
# Server output goes to ~/POSSystemData/server.log
set -euo pipefail
cd "$(dirname "$0")"

LABEL="com.lpgpos.webserver"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DATA="$HOME/POSSystemData"
mkdir -p "$HOME/Library/LaunchAgents" "$DATA"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$PWD/start.sh</string>
    <string>--tunnel</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>StandardOutPath</key><string>$DATA/server.log</string>
  <key>StandardErrorPath</key><string>$DATA/server.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed. Admin dashboard: http://localhost:8090  (log: $DATA/server.log)"
