#!/usr/bin/env bash
# Compiles and verifies the whole app with NO Android SDK and NO network.
#
# How it works:
#   1. tools/gen_stubs.py generates an android.jar-style stub source tree that
#      covers exactly the platform surface this app touches. Every body throws,
#      like the real android.jar — it is a compile-time contract. A handful of
#      stubs are deliberately *behavioural* (Base64, TextUtils, Path, RectF,
#      MimeTypeMap) so pure app logic can actually be executed.
#   2. tools/json-src is a small, correct org.json — on device this comes from
#      the platform, so it has to be supplied here.
#   3. tools/gen_r.py regenerates R.java from res/values/public.xml.
#   4. The Kotlin is compiled against (1)+(2)+(3).
#   5. If --java <dir> points at the ORIGINAL Java sources, they are compiled the
#      same way and tools/DiffHarness.java runs both builds side by side,
#      comparing tens of thousands of real return values.
#
# Step 5 is what validates the stubs themselves: the Java is known to compile
# against the real SDK, so if it also compiles here, the stubs are faithful.
#
#   tools/build-offline.sh                       # compile + type-check the port
#   tools/build-offline.sh --tests               # + run CoreRegressionTests
#   tools/build-offline.sh --java ../src         # + full differential test
#   tools/build-offline.sh --java ../src --tests --fuzz 2000
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${VEPRO_OFFLINE_DIR:-$ROOT/.build-offline}"
JAVA_SRC=""
FUZZ=600
RUN_TESTS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java) JAVA_SRC="$2"; shift 2 ;;
    --fuzz) FUZZ="$2"; shift 2 ;;
    --tests) RUN_TESTS=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v javac >/dev/null || { echo "javac not found (need a JDK 17+)" >&2; exit 2; }
eval "$("$ROOT/tools/find-kotlinc.sh")"

rm -rf "$OUT"
mkdir -p "$OUT"/{stubs-classes,json-classes,kt-classes,diff-classes}

step() { printf '\n== %s ==\n' "$1"; }
normalize_paths() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m -f "$1" > "$1.tmp" && mv "$1.tmp" "$1"
  fi
}
javac() {
  command javac -J-Dfile.encoding=UTF-8 -encoding UTF-8 "$@"
}

EMBEDDED_JARS=(
  "$ROOT/third_party/embedded/ocr/tesseract4android-4.9.0.jar"
  "$ROOT/third_party/embedded/jgit/org.eclipse.jgit-7.7.1.202607240634-r.jar"
  "$ROOT/third_party/embedded/jgit/JavaEWAH-1.2.3.jar"
  "$ROOT/third_party/embedded/jgit/slf4j-api-2.0.18.jar"
  "$ROOT/third_party/embedded/jgit/commons-codec-1.22.0.jar"
  "$ROOT/third_party/embedded/ecj/ecj-3.46.100-android1.jar"
  "$ROOT/third_party/embedded/zipflinger/zipflinger-8.13.2.jar"
  "$ROOT/third_party/embedded/stax/geronimo-stax-api_1.0_spec-1.0.1.jar"
  "$ROOT/third_party/embedded/kotlin/compat/kotlin-swing-icon-api-1.jar"
  "$ROOT/third_party/embedded/r8/r8-8.2.33.jar"
)
EMBEDDED_LIBRARY_JARS=(
  "$ROOT/third_party/embedded/zipflinger/annotations-31.13.2.jar"
)
command -v sha256sum >/dev/null || {
  echo "sha256sum not found (required for embedded provider integrity)" >&2
  exit 2
}
for embedded_jar in "${EMBEDDED_JARS[@]}"; do
  [[ -f "$embedded_jar" ]] || {
    echo "missing embedded provider JAR: $embedded_jar" >&2
    exit 1
  }
done
for embedded_library_jar in "${EMBEDDED_LIBRARY_JARS[@]}"; do
  [[ -f "$embedded_library_jar" ]] || {
    echo "missing embedded provider library JAR: $embedded_library_jar" >&2
    exit 1
  }
done
(cd "$ROOT" && sha256sum -c third_party/embedded/SHA256SUMS)
EMBEDDED_CP="$(IFS=:; echo "${EMBEDDED_JARS[*]}")"

step "android stubs"
python3 "$ROOT/tools/gen_stubs.py" "$OUT/stubs-src" >/dev/null
find "$OUT/stubs-src" -name '*.java' > "$OUT/stub-files.txt"
normalize_paths "$OUT/stub-files.txt"
javac -encoding UTF-8 -nowarn -d "$OUT/stubs-classes" @"$OUT/stub-files.txt"
echo "stub classes: $(find "$OUT/stubs-classes" -name '*.class' | wc -l)"
# Package them so the stubs can stand in for android.jar on a classpath.
(cd "$OUT/stubs-classes" && jar cf "$OUT/android-stubs.jar" .)

step "org.json"
find "$ROOT/tools/json-src" -name '*.java' > "$OUT/json-files.txt"
normalize_paths "$OUT/json-files.txt"
javac -encoding UTF-8 -nowarn -d "$OUT/json-classes" @"$OUT/json-files.txt"

step "R.java"
python3 "$ROOT/tools/gen_r.py" "$OUT/gen"
R_JAVA="$OUT/gen/dev/wind/agent/R.java"

SUPPORT="$OUT/stubs-classes:$OUT/json-classes:$EMBEDDED_CP"

step "compile Kotlin app"
# Kotlin AND the app's own Java. src holds exactly one .java file —
# BaseAgentService, which exists so the foreground service can declare both the
# API 34 and the API 35 `onTimeout` overload without an @Override that compiles
# against only one of them. Leaving it out is not a smaller build: kotlinc then
# fails on every `class X : BaseAgentService()` with "unresolved reference".
#
# kotlinc accepts .java on its source list and resolves it without emitting
# bytecode for it — the same way it is already handed R.java below.
find "$ROOT/src" "$ROOT/runtime-companion/src" \( -name '*.kt' -o -name '*.java' \) | sort > "$OUT/kt-files.txt"
normalize_paths "$OUT/kt-files.txt"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$SUPPORT" -d "$OUT/kt-classes" \
    @"$OUT/kt-files.txt" "$R_JAVA" 2>&1 | tee "$OUT/kotlinc.log" | grep -E ': error:' && {
  echo "KOTLIN COMPILE FAILED" >&2
  exit 1
} || true
if grep -qE ': error:' "$OUT/kotlinc.log"; then
  echo "KOTLIN COMPILE FAILED" >&2
  exit 1
fi
# kotlinc only *resolves* the Java it is handed, it never emits it — compile both
# the generated R.java and the app's own Java for real. kt-classes is on the
# classpath because BaseAgentService implements the interface the Kotlin
# AgentService declares.
find "$ROOT/src" "$ROOT/runtime-companion/src" -name '*.java' | sort > "$OUT/java-src-files.txt"
normalize_paths "$OUT/java-src-files.txt"
javac -encoding UTF-8 -nowarn -proc:none -cp "$SUPPORT:$OUT/kt-classes" -d "$OUT/kt-classes" \
    @"$OUT/java-src-files.txt" "$R_JAVA"
echo "kotlin errors: 0   classes: $(find "$OUT/kt-classes" -name '*.class' | wc -l)"

STDLIB="$(compgen -G "$KOTLIN_LIB/kotlin-stdlib*.jar" | sort | tail -1)"
# The companion's Java is part of the production data plane: the JVM tests drive
# the same scanner, sync session and supervisor the APK ships, so they are
# compiled into the test module rather than described by it.
COMPANION_SOURCES=()
for companion in RuntimeProcessSupervisor RuntimeWorkspace RuntimeMirrorSyncSession VegaRuntimeService; do
  candidate="$ROOT/runtime-companion/src/dev/wind/agent/runtime/$companion.java"
  [[ -f "$candidate" ]] && COMPANION_SOURCES+=("$candidate")
done

if [[ $RUN_TESTS == 1 ]]; then
  step "CoreRegressionTests against the generated stubs"
  # src + tests compile as ONE module so `internal` members stay visible.
  mkdir -p "$OUT/test-classes"
  # Same .java inclusion as the app step above — this module holds its own copy of
  # the app sources, so BaseAgentService has to be in it or nothing that extends
  # it resolves.
  find "$ROOT/src" "$ROOT/tests" \( -name '*.kt' -o -name '*.java' \) | sort > "$OUT/test-files.txt"
  for companion in "${COMPANION_SOURCES[@]:-}"; do
    [[ -n "$companion" ]] || continue
    if command -v cygpath >/dev/null 2>&1; then
      printf '%s\n' "$(cygpath -m "$companion")" >> "$OUT/test-files.txt"
    else
      printf '%s\n' "$companion" >> "$OUT/test-files.txt"
    fi
  done
  normalize_paths "$OUT/test-files.txt"
  "$KOTLINC" -nowarn -jvm-target 17 -cp "$SUPPORT" -d "$OUT/test-classes" \
      @"$OUT/test-files.txt" "$R_JAVA" 2>&1 | tee "$OUT/kotlinc-tests.log" || true
  if grep -qE ': error:' "$OUT/kotlinc-tests.log"; then
    echo "TEST COMPILE FAILED" >&2
    exit 1
  fi
  javac -encoding UTF-8 -nowarn -proc:none -cp "$SUPPORT:$OUT/test-classes" -d "$OUT/test-classes" \
      @"$OUT/java-src-files.txt" "$R_JAVA" "$ROOT/tools/PackageJarResources.java"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.CoreRegressionTests

  # The agent loop itself, end to end, over real sockets. Separate runner because it
  # needs the behavioural stubs above — a real android.jar throws on every call.
  step "AgentLoopTests against the generated stubs"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.AgentLoopTests

  step "VegaSafeSecurityTests (security & sandbox suite)"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.VegaSafeSecurityTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.MirrorFoundationTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.MirrorReadBoundaryTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.MirrorBridgeTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.ProjectPathTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.MultiRootIntegrationTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.CapabilitySandboxTests
  env JAVA_TOOL_OPTIONS= java -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.PersistentWorkspaceTests
  # P4. Runs the real coding runtime over a scripted host, so the completion
  # gate and the verification stamp are exercised rather than described.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.CodingLoopTests
  # P5. Code intelligence suite
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.CodeIntelligenceTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.RuntimeContractTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.JGitGitServiceTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.BuildCoreTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.ClasspathResolverTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.EcjJavaCompilerTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.D8DexCompilerTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.L8RuntimeDexCompilerTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.Aapt2ResourceCompilerTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  # P6-AP1. Synthetic ZIP/DEX tests for unsigned, unaligned APK assembly.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.ApkAssemblerTests
  # P6-AP2A. APK alignment contract, binary verifier, and optional explicit host zipalign proof.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.ApkAlignerTests
  # P6-BE5-A. Opt-in transactional AP1 -> AP2 package task integration.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.EmbeddedApkPackagingIntegrationTests
  # P6-AP2B1. Explicit in-process Zipflinger proof and optional D8/L8 minApi 23 gate.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.ZipflingerAlignmentProviderTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  # P6-AP2B2. Default test/App-style classloader and build-definition parity.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.PackagedZipflingerAlignmentTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.EmbeddedProviderPackagingTests "$ROOT"
  # P6-AP2B3A. Instrumentation isolation, target-loader source contract, runner guards.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.Ap2b3HarnessTests "$ROOT"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.PackagedAndroidPlatformTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  # P6-SC1A. Packaged R8 default-loader D8/L8 and app-private desugar input gate.
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.PackagedR8ToolchainTests \
      "$ROOT"
  # Prove the Android path does not borrow javax.tools/java.compiler from JDK.
  env JAVA_TOOL_OPTIONS= java --limit-modules java.base,java.logging,java.xml \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.PackagedAndroidPlatformTests \
      "$ROOT/assets/embedded/android-35/android.jar"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.RuntimeEndpointTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.RuntimeBindingTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.RuntimeCompanionTests
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" dev.wind.agent.CompanionMirrorIntegrationTests
  python3 "$ROOT/tests/mirror_helper_tests.py"
  python3 "$ROOT/tests/code_intel_helper_tests.py"
  python3 "$ROOT/tests/runtime_companion_tests.py"
fi

if [[ -z "$JAVA_SRC" ]]; then
  printf '\nOK — the Kotlin port compiles clean with no SDK.\n'
  printf 'Pass --java <original-java-src-dir> to also run the differential test.\n'
  exit 0
fi

[[ -d "$JAVA_SRC" ]] || { echo "--java: not a directory: $JAVA_SRC" >&2; exit 2; }

step "compile original Java (validates the stubs)"
mkdir -p "$OUT/java-classes"
find "$JAVA_SRC" -name '*.java' | sort > "$OUT/java-files.txt"
javac -nowarn -proc:none -cp "$SUPPORT" -d "$OUT/java-classes" \
    @"$OUT/java-files.txt" "$R_JAVA"
echo "java errors: 0   classes: $(find "$OUT/java-classes" -name '*.class' | wc -l)"

step "differential test: original Java vs ported Kotlin"
javac -nowarn -d "$OUT/diff-classes" "$ROOT/tools/DiffHarness.java"
env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 -Ddiff.fuzz="$FUZZ" -cp "$OUT/diff-classes" DiffHarness \
    "$OUT/java-classes" "$OUT/kt-classes" \
    "$OUT/stubs-classes" "$OUT/json-classes" "$STDLIB"
