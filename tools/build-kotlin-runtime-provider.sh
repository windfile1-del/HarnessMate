#!/usr/bin/env bash
set -euo pipefail

# Optional LGPL relink inputs. Defaults reproduce the pinned runtime asset set.
# VEPRO_KOTLIN_TROVE_JAR selects a compatible rebuilt Trove input; output paths
# are required to keep relink variants separate from the checked-in payload.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/tools/kotlin-provider/KotlinRuntimePayloadBuilder.java"

for command_name in java javac; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing JDK command: $command_name (JDK 17 required)" >&2
    exit 2
  }
done

JAVAC_VERSION="$(javac -version 2>&1)"
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
[[ "$JAVAC_VERSION" == javac\ 17.* && "$JAVA_VERSION" == *17.* ]] || {
  echo "Kotlin runtime provider builder requires JDK 17; found '$JAVAC_VERSION' / '$JAVA_VERSION'" >&2
  exit 2
}
[[ -f "$SOURCE" ]] || { echo "Kotlin runtime payload builder is missing" >&2; exit 2; }

TEMP_BASE="${TMPDIR:-/tmp}"
if command -v cygpath >/dev/null 2>&1; then TEMP_BASE="$(cygpath -u "$TEMP_BASE")"; fi
TEMP_BASE="$(cd "$TEMP_BASE" && pwd -P)"
TEMP_ROOT="$(mktemp -d "$TEMP_BASE/vega-kotlin-runtime-provider.XXXXXXXX")"
[[ -n "$TEMP_ROOT" && -d "$TEMP_ROOT" ]] || {
  echo "Could not create isolated Kotlin runtime provider build directory" >&2
  exit 2
}
cleanup() {
  case "$TEMP_ROOT" in
    "$TEMP_BASE"/vega-kotlin-runtime-provider.*) rm -rf -- "$TEMP_ROOT" ;;
    *) echo "Refusing to remove unexpected temporary directory: $TEMP_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT

mkdir "$TEMP_ROOT/classes"
mkdir "$TEMP_ROOT/stage"
javac -encoding UTF-8 -d "$TEMP_ROOT/classes" "$SOURCE"
BUILDER_ARGUMENTS=("$ROOT" "$TEMP_ROOT/stage")
if [[ -n "${VEPRO_KOTLIN_TROVE_JAR:-}" ]]; then
  BUILDER_ARGUMENTS+=(--trove-jar "$(realpath "$VEPRO_KOTLIN_TROVE_JAR")")
fi
if [[ -n "${VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT:-}" ]]; then
  BUILDER_ARGUMENTS+=(--output-root "$(realpath -m "$VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT")")
fi
if [[ -n "${VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT:-}" ]]; then
  BUILDER_ARGUMENTS+=(--pins-source-output "$(realpath -m "$VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT")")
fi
java -Xmx1g -Dfile.encoding=UTF-8 -cp "$TEMP_ROOT/classes" \
  KotlinRuntimePayloadBuilder "${BUILDER_ARGUMENTS[@]}"
