#!/usr/bin/env bash
# =============================================================================
# Nothing update fix — reproducible build script (git-bash / MSYS on Windows)
#
# Pipeline: gen-icons → aapt2 compile/link → javac → d8 → add dex → zipalign
#           → keytool keystore (once) → apksigner → verification → sha256
#
# Output: release/NothingUpdateFix.apk  (signed, v2/v3)
# Run from anywhere:  bash build.sh
# =============================================================================
set -euo pipefail

# ---- paths (native Windows tools need C:/ style) ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cygpath -m "$SCRIPT_DIR" 2>/dev/null || echo "$SCRIPT_DIR")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-C:/Users/25451/AppData/Local/Android/Sdk}}"
BT="$SDK/build-tools/37.0.0"
ANDROID_JAR="$SDK/platforms/android-37.0/android.jar"   # Android 17 (API 37)

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
DEXDUMP="$BT/dexdump.exe"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"

PY="${PYTHON:-C:/Users/25451/AppData/Local/Programs/Python/Python312/python}"

XPOSED_JAR="$ROOT/vendor/XposedBridgeApi-82.jar"
KS="$ROOT/keystore/release.jks"
KS_ALIAS="nothingupdatefix"
KS_PASS="nothingupdatefix"

APP_SRC="$ROOT/app/src/main"
OUT="$ROOT/out"
RELEASE="$ROOT/release"

for f in "$ANDROID_JAR" "$AAPT2" "$ZIPALIGN" "$DEXDUMP" "$D8_JAR" "$APKSIGNER_JAR" \
         "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL"; do
  [ -e "$f" ] || { echo "MISSING: $f" >&2; exit 1; }
done

# ---- 0. vendored Xposed API 82 (compile-only; never packaged into dex) ----
mkdir -p "$ROOT/vendor"
if [ ! -s "$XPOSED_JAR" ]; then
  echo "== downloading XposedBridgeApi-82.jar"
  curl -fsSL -m 60 -o "$XPOSED_JAR" \
    "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar" \
    || curl -fsSL -m 60 -x http://127.0.0.1:7897 -o "$XPOSED_JAR" \
    "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar"
fi
echo "== Xposed API jar: $(ls -la "$XPOSED_JAR" | awk '{print $5}') bytes"

# ---- 1. icons (deterministic PIL generation) ----
if [ ! -f "$APP_SRC/res/mipmap-xxhdpi/ic_launcher.png" ]; then
  echo "== generating launcher icons"
  "$PY" "$ROOT/scripts/gen_icons.py"
fi

# ---- 2. clean ----
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$RELEASE"

# ---- 3. aapt2 compile + link (resources + assets + manifest) ----
echo "== aapt2 compile"
"$AAPT2" compile --dir "$APP_SRC/res" -o "$OUT/res.zip"

echo "== aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" \
  --manifest "$APP_SRC/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 37 \
  --version-code 1 --version-name 1.0 \
  -A "$APP_SRC/assets" \
  "$OUT/res.zip"

# ---- 4. javac (module sources only, against android.jar + Xposed API) ----
echo "== javac"
( cd "$ROOT" && find app/src/main/java -name '*.java' | sed 's/^/"/; s/$/"/' > out/sources.txt \
  && "$JAVAC" -encoding UTF-8 --release 17 \
       -classpath "$ANDROID_JAR;$XPOSED_JAR" \
       -d "$OUT/classes" \
       @out/sources.txt )

# ---- 5. d8 (module classes ONLY — Xposed API / stubs must NOT enter dex) ----
echo "== d8"
"$JAR" cf "$OUT/module-classes.jar" -C "$OUT/classes" .
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  "$OUT/module-classes.jar"

# ---- 6. inject classes.dex into the APK ----
echo "== add classes.dex"
"$PY" - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED)
z.write(dex, "classes.dex")
z.close()
print("classes.dex added")
PYEOF

# ---- 7. zipalign ----
echo "== zipalign"
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

# ---- 8. keystore (generated once, kept for reproducible rebuilds) ----
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  echo "== generating keystore"
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=NothingUpdateFix, OU=OpenSource, O=rtzhang, C=CN"
fi

# ---- 9. sign ----
echo "== apksigner"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$RELEASE/NothingUpdateFix.apk" \
  "$OUT/aligned.apk"
"$JAVA" -jar "$APKSIGNER_JAR" verify --verbose "$RELEASE/NothingUpdateFix.apk"

# ---- 10. verification: badging + dexdump + sha256 ----
echo "== aapt2 dump badging"
"$AAPT2" dump badging "$RELEASE/NothingUpdateFix.apk" > "$OUT/badging.txt" || true
grep -E "package:|application-label|application-icon|sdkVersion|targetSdkVersion|launchable-activity" \
  "$OUT/badging.txt" || true

echo "== dexdump verification"
"$PY" - "$RELEASE/NothingUpdateFix.apk" "$OUT/dex_check" <<'PYEOF'
import sys, zipfile, os
apk, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
z = zipfile.ZipFile(apk)
z.extract("classes.dex", dest)
print("dex size:", os.path.getsize(os.path.join(dest, "classes.dex")))
PYEOF
"$DEXDUMP" "$OUT/dex_check/classes.dex" > "$OUT/dexdump.txt"
grep "Class descriptor" "$OUT/dexdump.txt" | sed "s/.*'\(.*\)'.*/\1/" > "$OUT/dex_classes.txt"
for cls in MainHook ChangelogActivity MainActivity StatusProvider ChangelogData ChangelogView; do
  grep -q "^Lio/github/rtzhang/nothingupdatefix/$cls;$" "$OUT/dex_classes.txt" \
    && echo "  OK  class defined in dex: $cls" \
    || { echo "  FAIL missing class: $cls"; exit 1; }
done
if grep -q "^Lde/robv/android/xposed" "$OUT/dex_classes.txt"; then
  echo "  FAIL Xposed API / stub classes defined in dex:"; \
  grep "^Lde/robv" "$OUT/dex_classes.txt"; exit 1
else
  echo "  OK  no Xposed API / stub class definitions in dex (references only, as expected)"
fi

echo "== sha256"
SHA=$(sha256sum "$RELEASE/NothingUpdateFix.apk" | awk '{print $1}')
echo "  APK : $RELEASE/NothingUpdateFix.apk"
echo "  SHA256: $SHA"
echo "$SHA  NothingUpdateFix.apk" > "$RELEASE/NothingUpdateFix.apk.sha256"

echo "== build complete"
