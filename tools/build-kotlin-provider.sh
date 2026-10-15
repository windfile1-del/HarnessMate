#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/third_party/embedded/kotlin"
UPSTREAM="$VENDOR/upstream/kotlin-compiler-embeddable-1.9.20.jar"
OVERLAY_SRC="$VENDOR/android-overlay/src/org/jetbrains/kotlin/com/intellij/util/containers"
PATCHER_SRC="$ROOT/tools/kotlin-provider/KotlinCompilerAndroidPatcher.java"
OUTPUT="$VENDOR/kotlin-compiler-embeddable-1.9.20-android2.jar"

for command_name in java javac; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing JDK command: $command_name (JDK 17 required)" >&2
    exit 2
  }
done

JAVAC_VERSION="$(javac -version 2>&1)"
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
[[ "$JAVAC_VERSION" == javac\ 17.* && "$JAVA_VERSION" == *17.* ]] || {
  echo "Kotlin provider builder requires JDK 17; found '$JAVAC_VERSION' / '$JAVA_VERSION'" >&2
  exit 2
}

[[ -f "$UPSTREAM" && -f "$PATCHER_SRC" && \
   -f "$OVERLAY_SRC/ConcurrentIntObjectHashMap.java" && \
   -f "$OVERLAY_SRC/ConcurrentLongObjectHashMap.java" ]] || {
  echo "Pinned Kotlin compiler or Android overlay source is missing" >&2
  exit 2
}

TEMP_BASE="${TMPDIR:-/tmp}"
if command -v cygpath >/dev/null 2>&1; then
  TEMP_BASE="$(cygpath -u "$TEMP_BASE")"
fi
TEMP_BASE="$(cd "$TEMP_BASE" && pwd -P)"
TEMP_ROOT="$(mktemp -d "$TEMP_BASE/vega-kotlin-provider.XXXXXXXX")"
[[ -n "$TEMP_ROOT" && -d "$TEMP_ROOT" ]] || {
  echo "Could not create isolated Kotlin provider build directory" >&2
  exit 2
}
cleanup() {
  case "$TEMP_ROOT" in
    "$TEMP_BASE"/vega-kotlin-provider.*) rm -rf -- "$TEMP_ROOT" ;;
    *) echo "Refusing to remove unexpected temporary directory: $TEMP_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT
OVERLAY_CLASSES="$TEMP_ROOT/overlay-classes"
PATCHER_CLASSES="$TEMP_ROOT/patcher-classes"
mkdir -p "$OVERLAY_CLASSES" "$PATCHER_CLASSES"

javac --release 8 -classpath "$UPSTREAM" -d "$OVERLAY_CLASSES" \
  "$OVERLAY_SRC/ConcurrentIntObjectHashMap.java" \
  "$OVERLAY_SRC/ConcurrentLongObjectHashMap.java"
javac -classpath "$UPSTREAM" -d "$PATCHER_CLASSES" "$PATCHER_SRC"
java -Xmx1g -classpath "$PATCHER_CLASSES:$UPSTREAM" KotlinCompilerAndroidPatcher \
  "$UPSTREAM" "$OVERLAY_CLASSES" "$OUTPUT"
