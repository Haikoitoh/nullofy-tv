#!/usr/bin/env bash
# Read-only: shows what a list would change on this TV, tier by tier, and which installed packages no list
# knows yet (for you, or your AI assistant, to look up and decide). Changes nothing.
#
# Usage: debloat/scan.sh [list]      (default: every list in debloat/lists, as one catalogue)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
connect
load_packages
if [ $# -gt 0 ]; then
  entries "$1" > "$STATE/.cat"
else
  : > "$STATE/.cat"
  for l in "$HERE"/lists/*.txt; do entries "$l" >> "$STATE/.cat"; done
fi
echo "TV: $MODEL"
for tier in safe unused ask; do
  case $tier in
    safe) printf '\n== Safe: ads, promos, tracking (nothing you use stops working)\n' ;;
    unused) printf '\n== Safe if unused: only if you do not use the feature named\n' ;;
    ask) printf '\n== Ask: your choice, or untested on this model\n' ;;
  esac
  n=0
  while IFS=$'\t' read -r t p note; do
    { [ "$t" = "$tier" ] && installed "$p"; } || continue
    if protected "$p"; then printf '  %-48s on keep.txt, never touched\n' "$p"
    elif is_off "$p"; then printf '  %-48s already off\n' "$p"
    else printf '  %-48s %s\n' "$p" "$note"; n=$((n+1)); fi
  done < "$STATE/.cat"
  [ $n -gt 0 ] || echo "  (nothing left to do)"
done
# installed, still on, in no list and not protected: vendor apps worth a look (core Android parts are left out)
cut -f2 "$STATE/.cat" | sort -u > "$STATE/.known"
# apps the owner installed are listed apart and left alone
sh1 pm list packages -3 | sed 's/^package://' | sort > "$STATE/.user"
comm -23 "$STATE/.all" "$STATE/.off" | comm -23 - "$STATE/.known" | comm -23 - "$STATE/.user" | while read -r p; do
  protected "$p" && continue
  case "$p" in com.android.*|android.*|*overlay*|*.rro*|*rro) continue ;; esac
  echo "$p"
done > "$STATE/uncatalogued.txt"
printf '\n== Not in any list: %s preinstalled packages (core Android parts left out)\n' "$(wc -l < "$STATE/uncatalogued.txt" | tr -d ' ')"
sed 's/^/  /' "$STATE/uncatalogued.txt"
comm -23 "$STATE/.user" "$STATE/.off" | comm -23 - "$STATE/.known" | while read -r p; do
  protected "$p" || echo "$p"
done > "$STATE/.mine"
printf '\n== Installed by you, in no list (left alone): %s apps\n' "$(wc -l < "$STATE/.mine" | tr -d ' ')"
sed 's/^/  /' "$STATE/.mine"
echo
echo "The preinstalled ones are saved in uncatalogued.txt in this TV's state folder. Look them up before disabling any."
