#!/usr/bin/env bash
# Puts the TV back as the snapshot found it: re-enables every package that is off now but was on at the
# snapshot, and restores the Home-button, screensaver and accessibility settings. Packages that were already
# off before the snapshot stay off. Asks before uninstalling Nullofy.
#
# Usage: debloat/restore.sh [--uninstall-launcher | --keep-launcher]
#        (the flags answer the Nullofy question in advance, for an AI assistant that asked you in the chat)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
connect
need_snapshot
load_packages
echo "Restoring $MODEL to the snapshot of $(sed -n 's/^date=//p' "$STATE/snapshot.txt")"

# 1. packages
n=0
for p in $(comm -23 "$STATE/.off" "$STATE/snapshot-disabled.txt"); do
  if sh1 pm enable "$p" | grep -q "enabled"; then echo "  enabled     $p"; log "enabled $p"; n=$((n+1)); else echo "  FAILED      $p"; fi
done
echo "  $n packages turned back on."

# 2. Nullofy
if installed tv.nullofy.launcher; then
  case "${1:-}" in
    --uninstall-launcher) U=1 ;;
    --keep-launcher) U=0 ;;
    *) if ask "Uninstall Nullofy too?"; then U=1; else U=0; fi ;;
  esac
  if [ $U = 1 ]; then
    a uninstall tv.nullofy.launcher >/dev/null && { echo "  Nullofy uninstalled"; log "uninstalled tv.nullofy.launcher"; }
  fi
fi

# 3. settings as they were ("null" = not set then)
while IFS='=' read -r k v; do
  case "$k" in secure.*) ;; *) continue ;; esac
  k="${k#secure.}"
  if [ "$v" = null ] || [ -z "$v" ]; then sh1 settings delete secure "$k" >/dev/null; else sh1 settings put secure "$k" "$v"; fi
  echo "  $k = $v"
done < "$STATE/snapshot.txt"
log "settings restored from snapshot"
echo "  Home now opens: $(sh1 cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo
echo "Done. Wait a minute before restarting the TV. The snapshot is kept, so you can debloat again later."
