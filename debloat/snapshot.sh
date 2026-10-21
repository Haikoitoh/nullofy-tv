#!/usr/bin/env bash
# Saves the TV's state before the first change: which packages are already off, the Home app, the screensaver
# and accessibility settings. Changes nothing on the TV. restore.sh puts this state back.
# The first snapshot is kept: running it again later does not overwrite it (pass --again to replace it).
#
# Usage: debloat/snapshot.sh [--again]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
connect
if [ -f "$STATE/snapshot.txt" ] && [ "${1:-}" != "--again" ]; then
  echo "Snapshot already taken on $(sed -n 's/^date=//p' "$STATE/snapshot.txt"), kept: $STATE/snapshot.txt"
  exit 0
fi
load_packages
{
  echo "date=$(date '+%F %T')"
  echo "model=$MODEL"
  for k in enabled_accessibility_services accessibility_enabled screensaver_enabled screensaver_components \
           screensaver_default_component screensaver_activate_on_sleep; do
    echo "secure.$k=$(sh1 settings get secure "$k")"
  done
  echo "home=$(sh1 cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
  echo "home_role=$(sh1 cmd role get-role-holders android.app.role.HOME 2>/dev/null || true)"
} > "$STATE/snapshot.txt"
cp "$STATE/.off" "$STATE/snapshot-disabled.txt"
cp "$STATE/.all" "$STATE/snapshot-packages.txt"
echo "Snapshot saved for $MODEL:"
echo "  $STATE/snapshot.txt"
echo "  $(wc -l < "$STATE/snapshot-disabled.txt" | tr -d ' ') packages were already off; $(wc -l < "$STATE/snapshot-packages.txt" | tr -d ' ') installed."
