#!/bin/bash
set -eu

LABEL="com.real-estate.data-probe"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
    launchctl bootout "$DOMAIN/$LABEL"
fi
rm -f "$PLIST"
echo "해제했다: $LABEL"
