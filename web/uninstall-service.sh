#!/bin/bash
# Stop the background LPG POS server and remove auto-start. Your data in ~/POSSystemData is kept.
set -euo pipefail
LABEL="com.lpgpos.webserver"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
echo "Stopped and removed auto-start."
