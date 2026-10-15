#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
SOURCE_JAR="${1:-$ROOT/third_party/embedded/kotlin/upstream/trove4j-1.0.20200330-sources.jar}"
OUTPUT_JAR="${2:-}"
SOURCE_DIR="${3:-}"
EXPECTED_SOURCE_SHA256=2e0174e80b989eb5deafcfb77407c908a0abe1f67a80306f98aeca63246e5481
EXPECTED_SOURCE_SIZE=477097

[[ -n "$OUTPUT_JAR" ]] || { echo "usage: $0 [exact-sources.jar] <new-output.jar> [modified-source-directory]" >&2; exit 2; }
for tool in java javac jar sha256sum unzip find sort; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing relink tool: $tool" >&2; exit 2; }
done
[[ -f "$SOURCE_JAR" ]] || { echo "sources JAR not found: $SOURCE_JAR" >&2; exit 2; }
[[ "$(wc -c < "$SOURCE_JAR" | tr -d '[:space:]')" == "$EXPECTED_SOURCE_SIZE" ]] || {
  echo "TROVE_SOURCE_ARTIFACT_SIZE_MISMATCH" >&2; exit 2;
}
[[ "$(sha256sum "$SOURCE_JAR" | awk '{print $1}')" == "$EXPECTED_SOURCE_SHA256" ]] || {
  echo "TROVE_SOURCE_ARTIFACT_MISMATCH: expected exact trove4j 1.0.20200330 sources JAR" >&2; exit 2;
}
[[ "$(javac -version 2>&1)" == javac\ 17.* ]] || { echo "JDK 17 is required" >&2; exit 2; }

OUTPUT_JAR="$(realpath -m "$OUTPUT_JAR")"
[[ ! -e "$OUTPUT_JAR" ]] || { echo "refusing to overwrite output JAR: $OUTPUT_JAR" >&2; exit 2; }
[[ -d "$(dirname "$OUTPUT_JAR")" ]] || { echo "output directory must already exist" >&2; exit 2; }

TEMP_BASE="${TMPDIR:-/tmp}"
if command -v cygpath >/dev/null 2>&1; then TEMP_BASE="$(cygpath -u "$TEMP_BASE")"; fi
TEMP_BASE="$(cd "$TEMP_BASE" && pwd -P)"
TEMP_ROOT="$(mktemp -d "$TEMP_BASE/vega-trove-relink.XXXXXXXX")"
cleanup() {
  case "$TEMP_ROOT" in
    "$TEMP_BASE"/vega-trove-relink.*) rm -rf -- "$TEMP_ROOT" ;;
    *) echo "refusing to remove unexpected temporary path: $TEMP_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT

if [[ -n "$SOURCE_DIR" ]]; then
  SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd -P)"
else
  SOURCE_DIR="$TEMP_ROOT/source"
  mkdir "$SOURCE_DIR"
  unzip -q "$SOURCE_JAR" -d "$SOURCE_DIR"
fi
SOURCE_LIST="$SOURCE_DIR/.__trove_sources.args"
(cd "$SOURCE_DIR" && find . -type f -name '*.java' -print | LC_ALL=C sort > "$SOURCE_LIST")
[[ -s "$SOURCE_LIST" ]] || { echo "no Java sources found" >&2; exit 2; }

CLASSES="$TEMP_ROOT/classes"
LICENSE_ROOT="$TEMP_ROOT/license"
mkdir -p "$CLASSES" "$LICENSE_ROOT/META-INF"
(cd "$SOURCE_DIR" && javac --release 8 -encoding UTF-8 -d "$CLASSES" @.__trove_sources.args)
cp "$ROOT/assets/embedded/licenses/trove4j/LICENSE.txt" "$LICENSE_ROOT/META-INF/LICENSE.txt"
cp "$ROOT/assets/embedded/licenses/trove4j/README-license.txt" "$LICENSE_ROOT/META-INF/README-license.txt"
jar --create --file "$OUTPUT_JAR" --date=1980-01-01T00:00:02Z -C "$CLASSES" . \
  -C "$LICENSE_ROOT" META-INF/LICENSE.txt \
  -C "$LICENSE_ROOT" META-INF/README-license.txt
jar --list --file "$OUTPUT_JAR" | grep -Fx 'META-INF/LICENSE.txt' >/dev/null
jar --list --file "$OUTPUT_JAR" | grep -Fx 'META-INF/README-license.txt' >/dev/null

echo "TROVE_RELINK_SOURCE_SHA256=$EXPECTED_SOURCE_SHA256"
echo "TROVE_RELINK_SOURCE_COUNT=$(find "$SOURCE_DIR" -type f -name '*.java' | wc -l | tr -d '[:space:]')"
echo "TROVE_RELINK_OUTPUT=$OUTPUT_JAR"
echo "TROVE_RELINK_OUTPUT_SIZE=$(wc -c < "$OUTPUT_JAR" | tr -d '[:space:]')"
echo "TROVE_RELINK_OUTPUT_SHA256=$(sha256sum "$OUTPUT_JAR" | awk '{print $1}')"
echo "TROVE_RELINK_TARGET_CLASSFILE=Java 8 (javac --release 8)"
