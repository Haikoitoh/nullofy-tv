#!/usr/bin/env bash
# Installs Nullofy on the TV and makes the Home button open it. Takes the snapshot first (if there is none),
# so debloat/restore.sh can put everything back.
#
#   1. installs launcher/out/nullofy.apk (run launcher/build.sh first)
#   2. names Nullofy the Home app (Android TV honours this; Google TV needs step 3)
#   3. turns on Nullofy's Home-button service (an accessibility service that only watches the Home key
#      and the stock home screen; it reads nothing else)
#   4. optionally sets Nullofy's screensaver
#
# Usage: launcher/install.sh [--yes-home] [--screensaver yes|no]
#        (the flags answer the questions in advance, for an AI assistant that asked you in the chat)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../debloat" && pwd)"
. "$HERE/lib.sh"
APK="$ROOT/launcher/out/nullofy.apk"
PKG=tv.nullofy.launcher
SERVICE=$PKG/$PKG.HomeService
DREAM=$PKG/$PKG.NullofyDream
YES_HOME=0; SS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes-home) YES_HOME=1; shift ;;
    --screensaver) SS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
done
[ -f "$APK" ] || { echo "No APK yet: run launcher/build.sh first." >&2; exit 1; }
connect
[ -f "$STATE/snapshot.txt" ] || bash "$HERE/snapshot.sh"
SDK="$(sh1 getprop ro.build.version.sdk)"
[ "$SDK" -ge 24 ] || { echo "This TV runs SDK $SDK; Nullofy needs Android 7.0 (SDK 24) or newer." >&2; exit 1; }

echo "Installing Nullofy on $MODEL"
LOCAL_APK="$APK"   # Windows adb.exe needs a Windows path (lib.sh turns Git Bash's own conversion off)
command -v cygpath >/dev/null 2>&1 && LOCAL_APK="$(cygpath -w "$APK")"
out="$(a install -r "$LOCAL_APK" 2>&1 || true)"
case "$out" in
  *Success*) echo "  installed" ; log "installed $PKG" ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) echo "  A Nullofy signed with another key is installed. Uninstall it first: adb uninstall $PKG" >&2; exit 1 ;;
  *) echo "$out" >&2; exit 1 ;;
esac
# TCL (and some other brands) block background starts of new apps unless AUTO_START is allowed; without it
# the Home service and the screensaver never bind
sh1 appops set $PKG AUTO_START allow >/dev/null 2>&1 || true
sh1 am start -n $PKG/.HomeActivity >/dev/null
sleep 2
# Home app (role). Google TV ignores it, which is why the service below exists.
sh1 cmd role add-role-holder android.app.role.HOME $PKG >/dev/null 2>&1 || true
sh1 cmd package set-home-activity $PKG/.HomeActivity >/dev/null 2>&1 || true

if [ $YES_HOME = 1 ] || ask "Turn on Nullofy's Home-button service (needed on Google TV)?"; then
  cur="$(sh1 settings get secure enabled_accessibility_services)"
  case "$cur" in null|"") new="$SERVICE" ;; *"$SERVICE"*) new="$cur" ;; *) new="$cur:$SERVICE" ;; esac
  # set it fresh: some firmware only binds a service that newly appears in the setting
  sh1 settings delete secure enabled_accessibility_services >/dev/null
  sleep 1
  sh1 settings put secure enabled_accessibility_services "$new"
  sh1 settings put secure accessibility_enabled 1
  log "home service on"
  sleep 3
  if sh1 dumpsys accessibility | grep -q "$PKG"; then echo "  Home-button service on"; else echo "  Home-button service set but not bound yet: open Nullofy once from the apps list, then press Home."; fi
fi

case "$SS" in yes|no) ;; *) if ask "Use Nullofy's screensaver (bouncing clock, or Art)?"; then SS=yes; else SS=no; fi ;; esac
if [ "$SS" = yes ]; then
  sh1 settings put secure screensaver_components $DREAM
  sh1 settings put secure screensaver_default_component $DREAM
  sh1 settings put secure screensaver_enabled 1
  log "screensaver set"
  echo "  screensaver set (it starts after the TV's own idle time; change it in Nullofy Settings)"
fi
sh1 input keyevent KEYCODE_HOME
echo
echo "Done. Press Home on the remote: Nullofy should open. Undo everything: debloat/restore.sh"
