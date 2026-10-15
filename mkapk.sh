#!/usr/bin/env bash
# Reproducible release build: aapt2 -> kotlinc -> javac(R) -> d8 -> zipalign -> apksigner
#
# Kotlin edition. The compiler and its compile classpath are pinned in-tree;
# trove may be replaced through VEPRO_KOTLIN_TROVE_JAR for LGPL relinking.
#
# Works out-of-the-box on-device (Termux/proot with the Debian android-sdk
# packages) as well as on a desktop with a full Android SDK:
#
#   JAVA_HOME            JDK 17+ home        (default: auto-detected from javac)
#   VEPRO_KOTLIN_TROVE_JAR compatible trove4j replacement (optional)
#   VEPRO_ANDROID_ASSETS_DIR alternate assets tree for isolated relink builds
#   ANDROID_SDK_ROOT     SDK root            (default: auto-detected)
#   VEPRO_ANDROID_JAR    path to android.jar (default: sdk/platforms or ./sdk/)
#   VEPRO_R8_JAR         path to r8.jar      (default: cmdline-tools/latest/lib/r8.jar, ./sdk/r8.jar, or $BT/lib/d8.jar)
#   VEPRO_KEYSTORE_PATH  release .jks        (required)
#   VEPRO_KEYSTORE_PASSWORD / VEPRO_KEY_PASSWORD / VEPRO_KEY_ALIAS=vepro
#
#   ./mkapk.sh [versionName] [versionCode] [out.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVAC_BIN="$(command -v javac || true)"
  [[ -n "$JAVAC_BIN" ]] || { echo "javac not found; set JAVA_HOME" >&2; exit 2; }
  JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVAC_BIN")")/.." && pwd)"
fi

# ---- Pinned Kotlin compiler -------------------------------------------------
KOTLIN_UPSTREAM="$ROOT/third_party/embedded/kotlin/upstream"
KOTLIN_COMPILER_CP=(
  "$ROOT/third_party/embedded/kotlin/upstream/kotlin-compiler-embeddable-1.9.20.jar"
  "$KOTLIN_UPSTREAM/kotlin-stdlib-1.9.20.jar"
  "$KOTLIN_UPSTREAM/kotlin-reflect-1.6.10.jar"
  "$KOTLIN_UPSTREAM/kotlin-script-runtime-1.9.20.jar"
  "$KOTLIN_UPSTREAM/kotlin-daemon-embeddable-1.9.20.jar"
  "$KOTLIN_UPSTREAM/annotations-13.0.jar"
  "${VEPRO_KOTLIN_TROVE_JAR:-$KOTLIN_UPSTREAM/trove4j-1.0.20200330.jar}"
)
for jar in "${KOTLIN_COMPILER_CP[@]}"; do
  [[ -f "$jar" ]] || { echo "Pinned Kotlin compiler input missing: $jar" >&2; exit 2; }
done
KOTLIN_COMPILER_CLASSPATH="$(IFS=:; echo "${KOTLIN_COMPILER_CP[*]}")"
KOTLIN_STDLIB="${VEPRO_KOTLIN_STDLIB:-$KOTLIN_UPSTREAM/kotlin-stdlib-1.9.20.jar}"
[[ -e "$KOTLIN_STDLIB" ]] || { echo "kotlin-stdlib.jar not found at $KOTLIN_STDLIB" >&2; exit 2; }
ASSETS_ROOT="${VEPRO_ANDROID_ASSETS_DIR:-$ROOT/assets}"
[[ -d "$ASSETS_ROOT" ]] || { echo "Assets directory not found: $ASSETS_ROOT" >&2; exit 2; }
for family in linux-jvm linux-android linux-lsp; do
  if [[ -f "$ASSETS_ROOT/embedded/$family/tools.tar.gz" ]]; then
    echo "Optional $family payload belongs in extension-packs, not APK assets" >&2
    exit 2
  fi
done

BT=""
for candidate in \
  "${ANDROID_SDK_ROOT:-}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.1}" \
  /usr/lib/android-sdk/build-tools/debian \
  "$HOME/android-sdk/build-tools/35.0.1"; do
  if [[ -x "$candidate/aapt2" ]]; then
    BT="$candidate"
    break
  fi
done

ANDROID_JAR="${VEPRO_ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
    "$ROOT/sdk/platform-35/android-35/android.jar" \
    "$ROOT/sdk/android.jar"; do
    if [[ -e "$candidate" ]]; then
      ANDROID_JAR="$candidate"
      break
    fi
  done
fi

# Screen sharing declares API-34 foreground-service flags. Use a modern aapt2
# and platform 34+ for linking as well as compilation; API 31 is insufficient.
AAPT2_JAR="${VEPRO_AAPT2_JAR:-}"
if [[ -z "$AAPT2_JAR" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
    "${ANDROID_SDK_ROOT:-}/platforms/android-34/android.jar" \
    "$ANDROID_JAR"; do
    if [[ -e "$candidate" ]]; then
      AAPT2_JAR="$candidate"
      break
    fi
  done
fi

BUILD_DIR="${VEPRO_BUILD_DIR:-$ROOT/.build-release}"
VNAME="${1:-1}"
VCODE="${2:-1}"
OUT_ARG="${3:-Vega-v${VNAME}.apk}"

if [[ "$OUT_ARG" = /* ]]; then
  OUT="$OUT_ARG"
else
  OUT="$ROOT/$OUT_ARG"
fi

: "${VEPRO_KEYSTORE_PATH:?Set VEPRO_KEYSTORE_PATH to the release .jks file}"
: "${VEPRO_KEYSTORE_PASSWORD:?Set VEPRO_KEYSTORE_PASSWORD securely in the environment}"
VEPRO_KEY_PASSWORD="${VEPRO_KEY_PASSWORD:-$VEPRO_KEYSTORE_PASSWORD}"
VEPRO_KEY_ALIAS="${VEPRO_KEY_ALIAS:-vepro}"

# ---- Embedded runtime/provider and desugar artifacts -----------------------
PROVIDER_JARS=(
  "$ROOT/third_party/embedded/jgit/org.eclipse.jgit-7.7.1.202607240634-r.jar"
  "$ROOT/third_party/embedded/jgit/JavaEWAH-1.2.3.jar"
  "$ROOT/third_party/embedded/jgit/slf4j-api-2.0.18.jar"
  "$ROOT/third_party/embedded/jgit/commons-codec-1.22.0.jar"
  "$ROOT/third_party/embedded/ecj/ecj-3.46.100-android1.jar"
  "$ROOT/third_party/embedded/zipflinger/zipflinger-8.13.2.jar"
  "$ROOT/third_party/embedded/stax/geronimo-stax-api_1.0_spec-1.0.1.jar"
  "$ROOT/third_party/embedded/kotlin/compat/kotlin-swing-icon-api-1.jar"
)
PACKAGED_TOOLCHAIN_JARS=(
  "$ROOT/third_party/embedded/r8/r8-8.2.33.jar"
)
PACKAGED_TOOLCHAIN_RESOURCE_JARS=(
  "$ROOT/third_party/embedded/r8/r8-8.2.33.jar"
)
while IFS= read -r web_jar; do
  web_jar="${web_jar%$'\r'}"
  [[ -z "$web_jar" ]] || PROVIDER_JARS+=("$ROOT/$web_jar")
done < "$ROOT/third_party/webkit/runtime-jars.txt"
PROVIDER_JARS+=("$ROOT/third_party/embedded/ocr/tesseract4android-4.9.0.jar")
KOTLIN_PROVIDER_JARS=(
  "$ROOT/third_party/embedded/kotlin/kotlin-compiler-embeddable-1.9.20-android3.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/kotlin-stdlib-1.9.20.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/kotlin-reflect-1.6.10.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/kotlin-script-runtime-1.9.20.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/kotlin-daemon-embeddable-1.9.20.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/trove4j-1.0.20200330.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/annotations-13.0.jar"
  "$ROOT/third_party/embedded/kotlin/upstream/kotlinx-coroutines-core-jvm-1.5.0.jar"
)
PROVIDER_LIBRARY_JARS=(
  "$ROOT/third_party/embedded/zipflinger/annotations-31.13.2.jar"
)

DESUGAR_JDK_LIBS="$ROOT/third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar"
DESUGAR_CONFIG_JAR="$ROOT/third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
EMBEDDED_SHA256SUMS="$ROOT/third_party/embedded/SHA256SUMS"

for required in "${PROVIDER_JARS[@]}" "${PACKAGED_TOOLCHAIN_JARS[@]}" "${PACKAGED_TOOLCHAIN_RESOURCE_JARS[@]}" "${KOTLIN_PROVIDER_JARS[@]}" "${PROVIDER_LIBRARY_JARS[@]}" "$DESUGAR_JDK_LIBS" "$DESUGAR_CONFIG_JAR" "$EMBEDDED_SHA256SUMS"; do
  if [[ ! -e "$required" ]]; then
    echo "Missing required embedded artifact: $required" >&2
    exit 2
  fi
done

command -v sha256sum >/dev/null 2>&1 || {
  echo "sha256sum not found (required for embedded provider integrity)" >&2
  exit 2
}
(cd "$ROOT" && sha256sum -c "third_party/webkit/SHA256SUMS" && sha256sum -c "third_party/embedded/SHA256SUMS") || {
  echo "Checksum verification failed for embedded artifacts" >&2
  exit 2
}

R8_JAR=""
if [[ -n "${VEPRO_R8_JAR:-}" && -e "$VEPRO_R8_JAR" ]]; then
  R8_JAR="$VEPRO_R8_JAR"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -e "$ANDROID_SDK_ROOT/cmdline-tools/latest/lib/r8.jar" ]]; then
  R8_JAR="$ANDROID_SDK_ROOT/cmdline-tools/latest/lib/r8.jar"
elif [[ -e "$ROOT/sdk/r8.jar" ]]; then
  R8_JAR="$ROOT/sdk/r8.jar"
elif [[ -n "$BT" && -e "$BT/lib/d8.jar" ]]; then
  R8_JAR="$BT/lib/d8.jar"
fi

for required in "$JAVA_HOME/bin/javac" "$ANDROID_JAR" "$VEPRO_KEYSTORE_PATH"; do
  if [[ ! -e "$required" ]]; then
    echo "Missing build dependency: $required" >&2
    exit 2
  fi
done
[[ -n "$BT" ]] || { echo "aapt2 not found; set ANDROID_SDK_ROOT" >&2; exit 2; }
[[ -n "$R8_JAR" ]] || { echo "R8 jar not found; set VEPRO_R8_JAR, install cmdline-tools with lib/r8.jar, place r8.jar in sdk/, or install build-tools with lib/d8.jar" >&2; exit 2; }

D8=("$JAVA_HOME/bin/java" "-cp" "$R8_JAR" "com.android.tools.r8.D8")
L8=("$JAVA_HOME/bin/java" "-cp" "$R8_JAR" "com.android.tools.r8.L8")

RAW_D8_VERSION="$("${D8[@]}" --version 2>&1 || true)"
python3 - "$R8_JAR" "$RAW_D8_VERSION" <<'PY'
import sys, re

r8_jar = sys.argv[1]
raw = sys.argv[2]

m = re.search(r'(?:D8|R8)\s+([0-9]+(?:\.[0-9]+)+)', raw)
if not m:
    print(f"Error: Unable to parse D8/R8 semantic version from '{raw}' (selected: {r8_jar})", file=sys.stderr)
    sys.exit(2)

v_str = m.group(1)
parts = [int(x) for x in v_str.split('.')]
while len(parts) < 3:
    parts.append(0)
parts = tuple(parts[:3])

min_version = (8, 2, 33)
if parts < min_version:
    min_str = '.'.join(str(x) for x in min_version)
    print(f"Error: D8/R8 version {v_str} in {r8_jar} is below required minimum {min_str}", file=sys.stderr)
    sys.exit(2)
PY

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/out" "$BUILD_DIR/out/providers" "$BUILD_DIR/out/toolchain"

printf '== extract desugar config ==\n'
python3 - "$DESUGAR_CONFIG_JAR" "$BUILD_DIR/out/desugar.json" <<'PY'
import json, sys, zipfile

config_jar, out_json = sys.argv[1], sys.argv[2]
entry_name = 'META-INF/desugar/d8/desugar.json'

with zipfile.ZipFile(config_jar) as z:
    try:
        raw = z.read(entry_name)
    except KeyError:
        raise SystemExit(f'missing {entry_name} in {config_jar}')
    data = json.loads(raw.decode('utf-8'))

if data.get('identifier') != 'com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1':
    raise SystemExit(f"invalid identifier: {data.get('identifier')}")
if data.get('configuration_format_version') != 200:
    raise SystemExit(f"invalid configuration_format_version: {data.get('configuration_format_version')}")
if data.get('synthesized_library_classes_package_prefix') != 'j$/':
    raise SystemExit(f"invalid synthesized_library_classes_package_prefix: {data.get('synthesized_library_classes_package_prefix')}")

with open(out_json, 'wb') as f:
    f.write(raw)
print('extracted and validated desugar config')
PY

printf '== compile resources ==\n'
"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD_DIR/out/res.zip"

WEB_RESOURCE_ZIPS=()
for library in core-1.1.0 webkit-1.15.0 annotation-experimental-1.3.0; do
  "$BT/aapt2" compile --dir "$ROOT/third_party/webkit/$library/res" -o "$BUILD_DIR/out/$library-res.zip"
  WEB_RESOURCE_ZIPS+=("$BUILD_DIR/out/$library-res.zip")
done
printf '== link resources ==\n'
"$BT/aapt2" link -o "$BUILD_DIR/out/base.apk" \
  -I "$AAPT2_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ASSETS_ROOT" \
  -0 ttf -0 png \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 23 --target-sdk-version 35 \
  --version-code "$VCODE" --version-name "$VNAME" \
  --extra-packages androidx.core:androidx.webkit --auto-add-overlay \
  "${WEB_RESOURCE_ZIPS[@]}" "$BUILD_DIR/out/res.zip"

printf '== compile Kotlin ==\n'
# kotlinc is given BOTH the Kotlin sources and the generated R.java. It does not
# emit bytecode for .java inputs — they exist only so `R.mipmap.…` resolves.
python3 - "$ROOT/src" "$ROOT/runtime-companion/src" "$BUILD_DIR/gen" "$BUILD_DIR/out/sources.txt" <<'PY'
from pathlib import Path
import sys
kotlin_root = Path(sys.argv[1])
companion_root = Path(sys.argv[2])
gen_root = Path(sys.argv[3])
out = Path(sys.argv[4])
files = sorted(p.as_posix() for p in kotlin_root.rglob('*.kt'))
files += sorted(p.as_posix() for p in kotlin_root.rglob('*.java'))
files += sorted(p.as_posix() for p in companion_root.rglob('*.java'))
files += sorted(p.as_posix() for p in gen_root.rglob('*.java'))
out.write_text('\n'.join(files) + '\n', encoding='utf-8')
PY
# shellcheck disable=SC2046
PROVIDER_CP="$(IFS=:; echo "${PROVIDER_JARS[*]}")"
mapfile -t KOTLIN_SOURCES < "$BUILD_DIR/out/sources.txt"
"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$ANDROID_JAR:$PROVIDER_CP" \
  -jvm-target 17 \
  -no-stdlib \
  -no-reflect \
  -nowarn \
  -d "$BUILD_DIR/classes" \
  "${KOTLIN_SOURCES[@]}"

printf '== compile generated R.java ==\n'
python3 - "$ROOT/src" "$ROOT/runtime-companion/src" "$BUILD_DIR/gen" "$BUILD_DIR/out/java-sources.txt" <<'PY'
from pathlib import Path
import sys
src_root = Path(sys.argv[1])
companion_root = Path(sys.argv[2])
gen_root = Path(sys.argv[3])
java_files = sorted(p.as_posix() for p in src_root.rglob('*.java'))
java_files += sorted(p.as_posix() for p in companion_root.rglob('*.java'))
java_files += sorted(p.as_posix() for p in gen_root.rglob('*.java'))
Path(sys.argv[4]).write_text('\n'.join(java_files) + '\n', encoding='utf-8')
PY
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 \
  -cp "$ANDROID_JAR:$BUILD_DIR/classes" -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/out/java-sources.txt"

printf '== stage kotlin-stdlib ==\n'
# d8 in some build-tools releases chokes on module-info.class, so dex a copy of
# the stdlib with the module descriptor and signatures removed.
cp "$KOTLIN_STDLIB" "$BUILD_DIR/out/kotlin-stdlib.jar"
python3 - "$BUILD_DIR/out/kotlin-stdlib.jar" "$BUILD_DIR/out/kotlin-stdlib-dex.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename
        if name == 'module-info.class' or name.endswith('/module-info.class'):
            continue
        if name.startswith('META-INF/') and name.endswith(('.SF', '.RSA', '.DSA')):
            continue
        zout.writestr(item, zin.read(name))
print('staged stdlib without module-info')
PY

printf '== stage provider jars ==\n'
SANITIZED_PROVIDER_JARS=()
for jar in "${PROVIDER_JARS[@]}"; do
  jar_name="$(basename "$jar")"
  sanitized="$BUILD_DIR/out/providers/$jar_name"
  SANITIZED_PROVIDER_JARS+=("$sanitized")
done

python3 - "$BUILD_DIR/out/providers" "${PROVIDER_JARS[@]}" <<'PY'
import sys, zipfile
from pathlib import Path

out_dir = Path(sys.argv[1])
jars = [Path(p) for p in sys.argv[2:]]

for src in jars:
    dst = out_dir / src.name
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name == 'module-info.class' or name.endswith('/module-info.class'):
                continue
            if name.startswith('META-INF/') and name.endswith(('.SF', '.RSA', '.DSA')):
                continue
            zout.writestr(item, zin.read(name))
    print(f'staged sanitized provider: {src.name}')
PY

SANITIZED_TOOLCHAIN_JARS=()
for jar in "${PACKAGED_TOOLCHAIN_JARS[@]}"; do
  jar_name="$(basename "$jar")"
  sanitized="$BUILD_DIR/out/toolchain/$jar_name"
  SANITIZED_TOOLCHAIN_JARS+=("$sanitized")
done

python3 - "$BUILD_DIR/out/toolchain" "${PACKAGED_TOOLCHAIN_JARS[@]}" <<'PY'
import sys, zipfile
from pathlib import Path

out_dir = Path(sys.argv[1])
jars = [Path(p) for p in sys.argv[2:]]

for src in jars:
    dst = out_dir / src.name
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name == 'module-info.class' or name.endswith('/module-info.class'):
                continue
            if name.startswith('META-INF/') and name.endswith(('.SF', '.RSA', '.DSA')):
                continue
            zout.writestr(item, zin.read(name))
    print(f'staged sanitized toolchain: {src.name}')
PY

printf '== create release dex ==\n'
python3 - "$BUILD_DIR/classes" "$BUILD_DIR/out/classes.txt" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
Path(sys.argv[2]).write_text('\n'.join(sorted(path.as_posix() for path in root.rglob('*.class'))) + '\n')
PY
# --min-api MUST match aapt2's --min-sdk-version, apksigner's --min-sdk-version,
# AndroidManifest's <uses-sdk> and build.gradle.kts's minSdk. They disagreed
# (24/24/23/24/24) and a signer that promised Android 6 while d8 desugared for
# Android 7 is precisely how API-24 calls reached a device that has no such method.
D8_LIBRARY_ARGS=(--lib "$ANDROID_JAR")
for library in "${PROVIDER_LIBRARY_JARS[@]}"; do
  D8_LIBRARY_ARGS+=(--lib "$library")
done
"${D8[@]}" --release --min-api 23 "${D8_LIBRARY_ARGS[@]}" \
  --desugared-lib "$BUILD_DIR/out/desugar.json" \
  --desugared-lib-pg-conf-output "$BUILD_DIR/out/l8.pg" \
  --output "$BUILD_DIR/dex" \
  "$BUILD_DIR/out/kotlin-stdlib-dex.jar" \
  "${SANITIZED_PROVIDER_JARS[@]}" \
  "${SANITIZED_TOOLCHAIN_JARS[@]}" \
  @"$BUILD_DIR/out/classes.txt"

[[ -s "$BUILD_DIR/out/l8.pg" ]] || {
  echo "Expected non-empty l8.pg output from D8" >&2
  exit 2
}

printf '== create desugared library dex with L8 ==\n'
"${L8[@]}" --release --min-api 23 --lib "$ANDROID_JAR" \
  --desugared-lib "$BUILD_DIR/out/desugar.json" \
  --pg-conf "$BUILD_DIR/out/l8.pg" \
  --output "$BUILD_DIR/out/l8.zip" \
  "$DESUGAR_JDK_LIBS" \
  "$DESUGAR_CONFIG_JAR"

python3 - "$BUILD_DIR/out/l8.zip" <<'PY'
import sys, zipfile
l8_zip_path = sys.argv[1]
with zipfile.ZipFile(l8_zip_path) as z:
    dex_entries = [name for name in z.namelist() if name.startswith('classes') and name.endswith('.dex')]
    if 'classes.dex' not in dex_entries:
        raise SystemExit(f'{l8_zip_path} does not contain classes.dex (found: {dex_entries})')
print('L8 produced valid dex output: ' + ', '.join(dex_entries))
PY

printf '== package and align ==\n'
cp "$BUILD_DIR/out/base.apk" "$BUILD_DIR/out/unsigned.apk"
python3 - "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/dex" "$BUILD_DIR/out/l8.zip" <<'PY'
import sys, zipfile
from pathlib import Path

apk_path = sys.argv[1]
dex_dir = Path(sys.argv[2])
l8_zip_path = sys.argv[3]

def dex_index(name: str) -> int:
    if name == 'classes.dex':
        return 1
    if name.startswith('classes') and name.endswith('.dex'):
        num_str = name[len('classes'):-len('.dex')]
        if num_str.isdigit():
            return int(num_str)
    raise ValueError(f'Invalid dex filename: {name}')

app_dex_files = sorted(dex_dir.glob('classes*.dex'), key=lambda p: (len(p.name), p.name))
if not app_dex_files:
    raise SystemExit('no D8 app dex output produced')

with zipfile.ZipFile(l8_zip_path) as l8_zip:
    l8_dex_names = sorted(
        [n for n in l8_zip.namelist() if n.startswith('classes') and n.endswith('.dex')],
        key=lambda n: (len(n), n)
    )
    if not l8_dex_names:
        raise SystemExit('no L8 dex output produced')

    max_app_idx = max(dex_index(p.name) for p in app_dex_files)

    packaged_names = set()
    l8_mappings = []

    with zipfile.ZipFile(apk_path, 'a', zipfile.ZIP_DEFLATED) as archive:
        for dex in app_dex_files:
            if dex.name in packaged_names:
                raise SystemExit(f'duplicate output dex name: {dex.name}')
            packaged_names.add(dex.name)
            archive.write(dex, dex.name)

        next_idx = max_app_idx + 1
        for orig_name in l8_dex_names:
            target_name = f'classes{next_idx}.dex'
            if target_name in packaged_names:
                raise SystemExit(f'duplicate output dex name: {target_name}')
            packaged_names.add(target_name)
            l8_mappings.append((orig_name, target_name))
            archive.writestr(target_name, l8_zip.read(orig_name))
            next_idx += 1

print('Packaged app dex: ' + ', '.join(p.name for p in app_dex_files))
print('Packaged L8 dex: ' + ', '.join(f'{orig} -> {target}' for orig, target in l8_mappings))
PY
"$JAVA_HOME/bin/java" "$ROOT/tools/PackageJarResources.java" \
  "$BUILD_DIR/out/unsigned.apk" "$KOTLIN_STDLIB" "${PROVIDER_JARS[@]}" \
  "${PACKAGED_TOOLCHAIN_RESOURCE_JARS[@]}"
"${VEPRO_PYTHON:-python3}" "$ROOT/tools/runtime/phone_ocr_payload.py" --root "$ROOT" --apk "$BUILD_DIR/out/unsigned.apk"
"${VEPRO_PYTHON:-python3}" "$ROOT/tools/runtime/build_phone_bridge.py" --verify --apk "$BUILD_DIR/out/unsigned.apk"
"${VEPRO_PYTHON:-python3}" "$ROOT/tools/runtime/adb_payload.py" --root "$ROOT" --apk "$BUILD_DIR/out/unsigned.apk"
if [[ -n "${VEPRO_LINUX_NATIVE_DIR:-}" || -d "$ASSETS_ROOT/embedded/linux-r0" || -d "$ASSETS_ROOT/embedded/linux-runtime" ]]; then
  "${VEPRO_PYTHON:-python3}" "$ROOT/tools/runtime/package_native.py" \
    --apk "$BUILD_DIR/out/unsigned.apk" \
    --native "${VEPRO_LINUX_NATIVE_DIR:-$ROOT/third_party/runtime/jniLibs}" \
    --android-native "${VEPRO_ANDROID_NATIVE_DIR:-$ROOT/third_party/runtime/android-jniLibs}" \
    --assets "$ASSETS_ROOT"
fi
"$BT/zipalign" -p -f 4 "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/out/aligned.apk"

printf '== sign release ==\n'
SIGN_ARGS=(
  --ks "$VEPRO_KEYSTORE_PATH"
  --ks-key-alias "$VEPRO_KEY_ALIAS"
  --ks-pass "env:VEPRO_KEYSTORE_PASSWORD"
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
  --min-sdk-version 23
  --out "$OUT" "$BUILD_DIR/out/aligned.apk"
)
# A separate key password is only forwarded when it actually differs (some
# apksigner builds mishandle an env:-indirected --key-pass).
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

printf '== verify ==\n'
"$BT/apksigner" verify --min-sdk-version 23 --verbose --print-certs "$OUT"
sha256sum "$OUT"
printf 'Built: %s\n' "$OUT"
