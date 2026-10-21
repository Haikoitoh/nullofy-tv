#!/usr/bin/env bash
# Build Nullofy without Gradle: aapt2 -> javac -> R8 -> zipalign -> apksigner.  Output: launcher/out/nullofy.apk
#
# Needs: an Android SDK with build-tools 34.0.0 and platforms;android-34 (ANDROID_HOME or ANDROID_SDK_ROOT),
# a JDK 17+ (JAVA_HOME, or java on PATH) and Python 3. Works in bash on Linux, macOS and Git Bash on Windows.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK (see README, Build it yourself)." >&2; exit 1; }
BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"
[ -d "$BT" ] && [ -f "$JAR" ] || { echo "Missing build-tools 34.0.0 or platforms;android-34 in $SDK" >&2; exit 1; }
if [ -n "${JAVA_HOME:-}" ]; then J="$JAVA_HOME/bin/"; else J=""; fi
PY=""   # the first that really runs (Windows has a python3 stub that only opens the Store)
for p in python3 python; do "$p" -c "import zipfile" >/dev/null 2>&1 && { PY="$p"; break; }; done
[ -n "$PY" ] || { echo "Python 3 is needed." >&2; exit 1; }

# Windows (Git Bash): the SDK tools are .exe / .bat and Java wants Windows paths
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) X=.exe; B=.bat; w() { cygpath -w "$1"; } ;;
  *) X=; B=; w() { printf '%s' "$1"; } ;;
esac

rm -rf out && mkdir -p out/res out/gen out/classes out/dex
"$BT/aapt2$X" compile --dir res -o out/res
"$BT/aapt2$X" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml -A assets --java out/gen \
  --proguard out/aapt-rules.txt --min-sdk-version 24 --target-sdk-version 34 out/res/*.flat
"${J}javac$X" -nowarn -encoding UTF-8 --release 11 -cp "$(w "$JAR")" -d out/classes $(find src out/gen -name '*.java')
# R8: shrink + optimise; aapt2's rules keep the manifest components
find out/classes -name '*.class' > out/classes.txt
if [ -n "$X" ]; then while read -r f; do cygpath -w "$f"; done < out/classes.txt > out/classes.win; mv out/classes.win out/classes.txt; fi
"${J}java$X" -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.R8 --release --min-api 24 --lib "$(w "$JAR")" \
  --pg-conf "$(w out/aapt-rules.txt)" --pg-conf proguard.txt --output "$(w out/dex)" @out/classes.txt
cp out/base.apk out/unsigned.apk
"$PY" -c "import zipfile;z=zipfile.ZipFile('out/unsigned.apk','a',zipfile.ZIP_DEFLATED);z.write('out/dex/classes.dex','classes.dex');z.close()"
"$BT/zipalign$X" -f 4 out/unsigned.apk out/aligned.apk

# Signing: your own key, made on the first build (launcher/nullofy.keystore + .pass, both git-ignored).
# Keep them: an update must be signed with the same key. The password goes through the environment only.
if [ -z "${NULLOFY_KS_PASS:-}" ]; then
  [ -f nullofy.keystore.pass ] || "$PY" -c "import secrets;print(secrets.token_urlsafe(24))" > nullofy.keystore.pass
  NULLOFY_KS_PASS="$(tr -d '\r\n' < nullofy.keystore.pass)"
fi
export NULLOFY_KS_PASS
[ -f nullofy.keystore ] || "${J}keytool$X" -genkeypair -keystore nullofy.keystore -storepass:env NULLOFY_KS_PASS \
  -keypass:env NULLOFY_KS_PASS -alias nullofy -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Nullofy" >/dev/null
# v2/v3 signatures only: every Android 7.0+ TV (minSdk 24) checks those
if [ -n "$B" ]; then
  cmd //c "$(w "$BT/apksigner$B")" sign --v1-signing-enabled false --ks nullofy.keystore --ks-pass env:NULLOFY_KS_PASS \
    --out "$(w out/nullofy.apk)" "$(w out/aligned.apk)"
else
  "$BT/apksigner" sign --v1-signing-enabled false --ks nullofy.keystore --ks-pass env:NULLOFY_KS_PASS \
    --out out/nullofy.apk out/aligned.apk
fi
ls -la out/nullofy.apk
