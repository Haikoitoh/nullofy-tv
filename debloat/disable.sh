#!/usr/bin/env bash
# Disables packages from a tiered list, asking first. Never uninstalls; restore.sh undoes it.
#   Safe        shown as one group, one yes for the group.
#   Unused/Ask  one question per package.
# Packages on keep.txt are always refused. A snapshot (snapshot.sh) must exist first.
#
# Usage: debloat/disable.sh LIST [--tier safe|unused|ask] [--dry-run]
#        debloat/disable.sh LIST --packages a.b.c,d.e.f    exactly these, no questions: for an AI assistant
#                                                          that has already asked you in the chat
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
LIST="${1:-}"
[ -f "$LIST" ] || { echo "Usage: debloat/disable.sh debloat/lists/<your-tv>.txt [--tier T] [--packages a,b] [--dry-run]" >&2; exit 1; }
shift
ONLY=""; TIERS="safe unused ask"; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tier) TIERS="$2"; shift 2 ;;
    --packages) ONLY="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
done
connect
need_snapshot
load_packages
entries "$LIST" > "$STATE/.list"
DONE=0

off() {   # off PKG: disables one package, or says why not
  if protected "$1"; then echo "  refused     $1 (keep.txt)"; return; fi
  if ! installed "$1"; then echo "  not on TV   $1"; return; fi
  if is_off "$1"; then echo "  already off $1"; return; fi
  if [ $DRY = 1 ]; then echo "  would disable $1"; return; fi
  if sh1 pm disable-user --user 0 "$1" | grep -q "disabled"; then
    echo "  disabled    $1"; log "disabled $1"; DONE=$((DONE+1))
    case "$(sh1 settings get secure screensaver_components)" in
      "$1"/*) echo "              that was the screensaver: the screen just dims when idle until another is set (launcher/install.sh sets Nullofy's)" ;;
    esac
  else
    echo "  FAILED      $1 (the TV refused it)"
  fi
}

if [ -n "$ONLY" ]; then
  for p in ${ONLY//,/ }; do
    if ! valid_pkg "$p"; then echo "  refused     $p (not a package name)"; continue; fi
    cut -f2 "$STATE/.list" | grep -qx "$p" || echo "  note: $p is not in $LIST (add it with a note once you know it is safe)"
    off "$p"
  done
else
  for tier in $TIERS; do
    todo=(); notes=()
    while IFS=$'\t' read -r t p note; do
      { [ "$t" = "$tier" ] && installed "$p" && ! is_off "$p" && ! protected "$p"; } || continue
      todo+=("$p"); notes+=("$note")
    done < "$STATE/.list"
    [ ${#todo[@]} -gt 0 ] || continue
    if [ "$tier" = safe ]; then
      printf '\nSafe (ads, promos, tracking):\n'
      for i in "${!todo[@]}"; do printf '  %-48s %s\n' "${todo[$i]}" "${notes[$i]}"; done
      if [ $DRY = 1 ] || ask "Disable these ${#todo[@]}?"; then for p in "${todo[@]}"; do off "$p"; done; fi
    else
      if [ "$tier" = unused ]; then printf '\nSafe if unused (one question each):\n'; else printf '\nAsk (your choice; untested ones may break something):\n'; fi
      for i in "${!todo[@]}"; do
        if [ $DRY = 1 ]; then off "${todo[$i]}"; elif ask "  ${todo[$i]}: ${notes[$i]}. Disable?"; then off "${todo[$i]}"; fi
      done
    fi
  done
fi
echo
if [ $DRY = 1 ]; then echo "Dry run: nothing changed."; exit 0; fi
echo "$DONE disabled now. Undo: debloat/restore.sh. Wait a minute before restarting the TV, or the change can be lost."
