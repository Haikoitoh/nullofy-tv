# Nullofy TV: assistant prompt

Paste everything below the line into an AI coding assistant that can run shell commands (Claude Code, Codex,
Gemini CLI), started in this repository's folder. Fill in the two lines at the top first.

---

My TV: **<brand and model, for example TCL 55C655 Google TV>**
Its IP address: **<for example 192.168.1.50>**
adb on this computer: **<leave as `adb` if it is on PATH, or the full path, for example C:/platform-tools/adb.exe>**

Set up Nullofy TV on my television from this repository: take a snapshot, debloat it in tiers with me deciding,
then build and install the Nullofy launcher so the remote's Home button opens it. Use only this repository's
scripts and plain `adb`. Work on the real TV, one step at a time, and check each step before the next.

## Rules (always)

1. **I decide.** The Safe tier may be done after I say yes to it as a group. Every package in Safe if unused and
   Ask needs my yes for that package, in this chat. Never pass a package to `--packages` that I have not approved.
2. **Never** uninstall a system app, never touch `debloat/keep.txt` packages, never open factory, service or test
   menus, never change settings the scripts don't change, never factory-reset. Don't disable the TV's own home
   screen or setup wizard.
3. **Snapshot first.** `debloat/snapshot.sh` runs before any change. Never run it with `--again` unless I ask.
4. **Check, don't assume.** After each change, confirm it (`"$ADB" shell pm list packages -d`, the top activity
   with `"$ADB" shell dumpsys activity activities | grep topResumedActivity`, or a screenshot saved outside the
   repository: `"$ADB" exec-out screencap -p > "${TMPDIR:-/tmp}/tv.png"`, then look at it). Injected key presses do not prove a remote button
   works: ask me to press the real button and tell you what happened.
5. **Short batches.** Send at most a few key presses at a time, and check the top activity before each batch:
   blind keys can change TV settings.
6. **Stop and tell me** if `adb` says `unauthorized` (I must accept the prompt on the TV), if a script prints
   FAILED, or if anything looks different from what you expected. Don't retry in a loop.
7. **Unknown packages.** For a package in no list, say what you know about it and how sure you are (and where
   that came from). It is "Ask, untested" unless this repository's lists say otherwise.
8. Don't put my IP address, serial number or account names in files you commit or in anything public, and
   don't repeat my TV's serial number (it is in the snapshot folder's name) in your messages.
9. Every command runs in bash (Git Bash on Windows). Shell state may not carry over between your commands, so
   start each one with the exports from step 1.

## Steps

**0. Check the setup.** Confirm adb works (`"$ADB" version`, with `ADB` from step 1) and the repository is
complete (`debloat/`, `launcher/`). For the build in step 8, check that `ANDROID_HOME` points to an SDK with
`build-tools;34.0.0` and `platforms;android-34`, and that Java is 17 or newer (`"$JAVA_HOME/bin/java" -version`,
or `java -version` when JAVA_HOME is not set). If something is missing, tell me what to install and stop.

**1. Connect.** `export TV=<ip>:5555 ADB=<adb from the top, or adb>` (the scripts read both), then
`"$ADB" connect $TV` and `"$ADB" devices`. If it says unauthorized, ask me to
accept "Allow USB debugging?" on the TV with Always allow, and wait for me. Then read the model:
`"$ADB" shell getprop ro.product.manufacturer`, `ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`.

**2. Snapshot.** Run `debloat/snapshot.sh`. Tell me where it saved it.

**3. Ask me first:**
- Which apps do I use (streaming, live TV, HDMI devices)?
- Do I cast from my phone (Chromecast, AirPlay, the TV maker's app)? Do I use voice search? Does anyone use
  TalkBack or other accessibility features?
- Do I want Nullofy's screensaver?

**4. Pick the list.** Look in `debloat/lists/` for my brand and Android version. Run
`debloat/scan.sh <list>` (or with no list when none fits) and show me the result grouped by tier, in plain words.

**5. Safe tier.** Show me the Safe packages that are still on, one line each. If a note names something I said I
use in step 3, take that package out and ask about it on its own. Ask once: "Disable these N?" On yes,
run `debloat/disable.sh <list> --packages <the comma-separated list>`. Then check them with
`"$ADB" shell pm list packages -d`, press Home and take a screenshot, and ask me to try live TV / an HDMI input and
the remote's Home and Input buttons.

**6. Safe if unused, then Ask.** For each package: name it, say what stops working without it (from the list's
note and my answers in step 3), and ask yes or no. Collect the yeses, then run one
`debloat/disable.sh <list> --packages ...` and check as in step 5. Mark untested ones clearly. Don't suggest ones
that conflict with what I said I use. If you disable the TV's own screensaver here, say that the screen will just
dim when idle until step 8 sets Nullofy's (or until I re-enable it).

**7. Unknown packages.** Show me the scan's "Not in any list" part, grouped (TV maker's apps, Google apps, other).
Apps I installed myself are listed apart by the scan and are left alone unless I bring them up.
For any I want gone, explain it first, then the same yes-per-package rule.

**8. Launcher.** Run `launcher/build.sh` (skip it only if `launcher/out/nullofy.apk` is newer than every file in
`launcher/` outside `launcher/out/`). Ask me before the Home-button service, explaining that it is an accessibility service that
only watches the Home key. Then run `launcher/install.sh --yes-home --screensaver yes` (or `no`, as I said); it ends by pressing Home once
to show Nullofy.
Check: `"$ADB" shell dumpsys accessibility | grep -i nullofy` shows it bound; a screenshot shows Nullofy. Then ask
me to press Home on the real remote from inside another app (for example Settings) and tell you what opened.

**9. Wrap up.** Tell me: what was disabled (by tier), what I kept, how to undo it (`debloat/restore.sh`), and
to wait a minute before restarting the TV. Offer to restart it and check everything is still off and Home
still opens Nullofy afterwards (after a restart the TV may ask to allow debugging again).

## If something goes wrong

| Symptom | Fix |
|---|---|
| An app I use broke | `"$ADB" shell pm enable <package>` and tell me; if unsure which, `debloat/restore.sh --keep-launcher` |
| Home opens the stock home | Run `launcher/install.sh --yes-home` again; ask me to open Nullofy once from the apps list |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Another Nullofy build is installed: ask me, then `"$ADB" uninstall tv.nullofy.launcher` and install again |
| Black screen on Home | Should not happen (the kit never disables the stock home). Run `debloat/restore.sh` and tell me |
| Anything else | Stop, show me the output, and suggest `debloat/restore.sh` |
