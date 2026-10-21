# Shared by the debloat scripts and launcher/install.sh. Source it; do not run it.
#
# Environment:
#   TV=192.168.1.50:5555   the TV's address (network debugging); leave unset when one device is plugged in
#   ADB=/path/to/adb       adb to use (default: adb on PATH)
#   NULLOFY_STATE=dir      where snapshots and logs go (default: ~/.nullofy-tv)

HERE="${HERE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
ROOT="$(cd "$HERE/.." && pwd)"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /sdcard-style paths as they are
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || { echo "adb not found. Install Android platform-tools or set ADB=." >&2; exit 1; }

# adb for the chosen TV
a() { if [ -n "${TV:-}" ]; then "$ADB" -s "$TV" "$@"; else "$ADB" "$@"; fi; }
# one line from the TV's shell, without the carriage return adb adds on some systems
sh1() { a shell "$@" < /dev/null | tr -d '\r'; }

# connect: checks there is exactly one authorised TV; sets SERIAL and STATE (this TV's folder)
connect() {
  if [ -n "${TV:-}" ]; then "$ADB" connect "$TV" >/dev/null 2>&1 || true; fi
  local st; st="$(a get-state 2>&1 || true)"
  case "$st" in
    device) ;;
    *unauthorized*) echo "The TV has not allowed this computer yet: accept \"Allow USB debugging?\" on the TV (tick Always allow), then run this again." >&2; exit 1 ;;
    *more\ than\ one*) echo "More than one device is connected: set TV=<ip>:5555." >&2; exit 1 ;;
    *) echo "No TV found. Turn on network debugging on the TV and set TV=<ip>:5555 (see README)." >&2; exit 1 ;;
  esac
  SERIAL="$(sh1 getprop ro.serialno)"; [ -n "$SERIAL" ] || SERIAL="$(sh1 settings get secure android_id)"
  STATE="${NULLOFY_STATE:-$HOME/.nullofy-tv}/$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9_-' '_')"
  mkdir -p "$STATE"
  MODEL="$(sh1 getprop ro.product.manufacturer) $(sh1 getprop ro.product.model), Android $(sh1 getprop ro.build.version.release) (SDK $(sh1 getprop ro.build.version.sdk))"
}

# valid_pkg NAME: true for an Android package name (two or more dot-separated parts of letters, digits and _,
# each starting with a letter or _). Names go into commands run on the TV, so nothing else is let through.
valid_pkg() { [[ "$1" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]; }

# entries LIST: prints "tier<TAB>package<TAB>note" for every package in a tiered list.
# Format: a "[safe]", "[unused]" or "[ask]" line starts a tier; then one package per line, an optional note after
# it, "#" starts a comment. Strips a BOM and CRLF (Notepad). Refuses the whole list if an entry is not a bare
# package name, because the name goes into a shell command on the TV.
entries() {
  LC_ALL=C awk -v bom="$(printf '\357\273\277')" '
    NR == 1 && index($0, bom) == 1 { $0 = substr($0, 4) }   # a list saved by Notepad
    { sub(/\r$/, ""); sub(/[ \t]*#.*$/, "") }
    /^[ \t]*$/ { next }
    /^\[[a-z]+\][ \t]*$/ { gsub(/[][ \t]/, ""); tier=$0; next }
    { p=$1; $1=""; sub(/^[ \t]+/, ""); print (tier==""?"ask":tier) "\t" p "\t" $0 }
  ' "$1" > "$STATE/.entries"
  local bad="" p
  for p in $(cut -f2 "$STATE/.entries"); do valid_pkg "$p" || bad="$bad  $p"$'\n'; done
  if [ -n "$bad" ]; then { echo "refusing $1: not a package name:"; printf '%s' "$bad"; } >&2; exit 1; fi
  bad="$(cut -f1 "$STATE/.entries" | grep -vxE 'safe|unused|ask' | sort -u || true)"
  if [ -n "$bad" ]; then echo "refusing $1: unknown tier: $bad (use [safe], [unused] or [ask])" >&2; exit 1; fi
  cat "$STATE/.entries"
}

# protected PKG: true when PKG is on keep.txt (a trailing * is a prefix match)
protected() {
  local k
  for k in $(grep -vE '^\s*(#|$)' "$ROOT/debloat/keep.txt" | sed -E 's/\s+#.*$//' | awk '{print $1}' | tr -d '\r'); do
    case "$k" in *\*) [ "${1#${k%\*}}" != "$1" ] && return 0 ;; *) [ "$1" = "$k" ] && return 0 ;; esac
  done
  return 1
}

# ask QUESTION: y/N on the terminal (never assumes yes). With no terminal (a pipe, an AI assistant's shell) the
# answer is no: an assistant asks in the chat and passes the answers as flags instead.
ask() {
  local r=""
  if ! { true < /dev/tty; } 2>/dev/null; then echo "$1 [y/N] no (no terminal to ask on; see the script's flags)"; return 1; fi
  read -r -p "$1 [y/N] " r < /dev/tty || true
  [ "$r" = y ] || [ "$r" = Y ] || [ "$r" = yes ]
}

# the TV's package lists, read once
load_packages() {
  sh1 pm list packages | sed 's/^package://' | sort > "$STATE/.all"
  sh1 pm list packages -d | sed 's/^package://' | sort > "$STATE/.off"
}
installed() { grep -qx "$1" "$STATE/.all"; }
is_off() { grep -qx "$1" "$STATE/.off"; }

# need_snapshot: every change needs the snapshot taken before the first one
need_snapshot() {
  [ -f "$STATE/snapshot.txt" ] || { echo "No snapshot for this TV yet: run debloat/snapshot.sh first (it changes nothing)." >&2; exit 1; }
}

# log WHAT: one line in this TV's change log (restore.sh reads it)
log() { printf '%s\t%s\n' "$(date '+%F %T')" "$1" >> "$STATE/changes.log"; }
