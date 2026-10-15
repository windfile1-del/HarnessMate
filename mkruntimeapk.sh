#!/usr/bin/env bash
# Dependency-free build for the signed Vega self-contained runtime companion.
# The host and companion use the same keystore variables in CI, so Android's
# signature-level BIND_RUNTIME permission is enforceable after installation.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVAC_BIN="$(command -v javac || true)"
  [[ -n "$JAVAC_BIN" ]] || { echo "javac not found; set JAVA_HOME" >&2; exit 2; }
  JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVAC_BIN")")/.." && pwd)"
fi

BT=""
for candidate in \
  "${ANDROID_SDK_ROOT:-}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.1}" \
  "$HOME/android-sdk/build-tools/35.0.1" \
  /usr/lib/android-sdk/build-tools/debian; do
  if [[ -x "$candidate/aapt2" ]]; then BT="$candidate"; break; fi
done

ANDROID_JAR="${VEPRO_ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
    "$ROOT/sdk/platform-35/android-35/android.jar" \
    "$ROOT/sdk/android.jar"; do
    if [[ -e "$candidate" ]]; then ANDROID_JAR="$candidate"; break; fi
  done
fi

BUILD_DIR="${VEPRO_RUNTIME_BUILD_DIR:-$ROOT/.build-runtime-companion}"
VNAME="${1:-0.2.0-alpha}"
VCODE="${2:-1}"
OUT_ARG="${3:-VegaRuntimeCompanion-v$VNAME.apk}"
if [[ "$OUT_ARG" = /* ]]; then OUT="$OUT_ARG"; else OUT="$ROOT/$OUT_ARG"; fi

: "${VEPRO_KEYSTORE_PATH:?Set VEPRO_KEYSTORE_PATH to the release .jks file}"
: "${VEPRO_KEYSTORE_PASSWORD:?Set VEPRO_KEYSTORE_PASSWORD securely in the environment}"
VEPRO_KEY_PASSWORD="${VEPRO_KEY_PASSWORD:-$VEPRO_KEYSTORE_PASSWORD}"
VEPRO_KEY_ALIAS="${VEPRO_KEY_ALIAS:-vepro}"

[[ -n "$BT" ]] || { echo "aapt2 not found; set ANDROID_SDK_ROOT" >&2; exit 2; }
for required in "$JAVA_HOME/bin/javac" "$ANDROID_JAR" "$VEPRO_KEYSTORE_PATH"; do
  [[ -e "$required" ]] || { echo "Missing build dependency: $required" >&2; exit 2; }
done
[[ -x "$BT/d8" && -x "$BT/zipalign" && -x "$BT/apksigner" ]] || {
  echo "build-tools must include d8, zipalign and apksigner" >&2
  exit 2
}

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/out"

printf '== compile companion resources ==\n'
"$BT/aapt2" compile --dir "$ROOT/runtime-companion/res" -o "$BUILD_DIR/out/res.zip"

printf '== link companion resources ==\n'
"$BT/aapt2" link -o "$BUILD_DIR/out/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/runtime-companion/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 23 --target-sdk-version 35 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "$BUILD_DIR/out/res.zip"

printf '== compile companion Java ==\n'
find "$ROOT/runtime-companion/src" -name '*.java' | sort > "$BUILD_DIR/out/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 \
  -cp "$ANDROID_JAR:$BUILD_DIR/gen" -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/out/sources.txt" \
  "$BUILD_DIR/gen/dev/wind/agent/runtime/R.java"

printf '== create companion dex ==\n'
find "$BUILD_DIR/classes" -name '*.class' | sort > "$BUILD_DIR/out/classes.txt"
"$BT/d8" --release --min-api 23 --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" @"$BUILD_DIR/out/classes.txt"

printf '== package, align and sign companion ==\n'
cp "$BUILD_DIR/out/base.apk" "$BUILD_DIR/out/unsigned.apk"
python3 - "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/dex" <<'PY'
import sys
import zipfile
from pathlib import Path
apk, dex_dir = sys.argv[1], Path(sys.argv[2])
dex_files = sorted(dex_dir.glob("classes*.dex"), key=lambda path: (len(path.name), path.name))
if not dex_files:
    raise SystemExit("no companion dex output produced")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as archive:
    for dex in dex_files:
        archive.write(dex, dex.name)
print("packaged: " + ", ".join(path.name for path in dex_files))
PY
"$BT/zipalign" -p -f 4 "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/out/aligned.apk"

SIGN_ARGS=(
  --ks "$VEPRO_KEYSTORE_PATH"
  --ks-key-alias "$VEPRO_KEY_ALIAS"
  --ks-pass "env:VEPRO_KEYSTORE_PASSWORD"
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
  --min-sdk-version 23
  --out "$OUT" "$BUILD_DIR/out/aligned.apk"
)
if [[ "$VEPRO_KEY_PASSWORD" != "$VEPRO_KEYSTORE_PASSWORD" ]]; then
  SIGN_ARGS=(
    --ks "$VEPRO_KEYSTORE_PATH"
    --ks-key-alias "$VEPRO_KEY_ALIAS"
    --ks-pass "env:VEPRO_KEYSTORE_PASSWORD"
    --key-pass "env:VEPRO_KEY_PASSWORD"
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
    --min-sdk-version 23
    --out "$OUT" "$BUILD_DIR/out/aligned.apk"
  )
fi
"$BT/apksigner" sign "${SIGN_ARGS[@]}"
"$BT/apksigner" verify --min-sdk-version 23 --verbose --print-certs "$OUT"
sha256sum "$OUT"
printf 'Built: %s\n' "$OUT"
