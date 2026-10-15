#!/usr/bin/env python3
"""P6.2 source contracts for the signed self-contained companion.

The JVM test drives RuntimeProcessSupervisor itself. These checks cover the
Android-only boundary that the no-SDK runner cannot instantiate.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMP = ROOT / "runtime-companion"
MANIFEST = (COMP / "AndroidManifest.xml").read_text(encoding="utf-8")
HOST_MANIFEST = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
SERVICE = (COMP / "src/dev/wind/agent/runtime/VegaRuntimeService.java").read_text(encoding="utf-8")
WORKSPACE = (COMP / "src/dev/wind/agent/runtime/RuntimeWorkspace.java").read_text(encoding="utf-8")
SUPERVISOR = (COMP / "src/dev/wind/agent/runtime/RuntimeProcessSupervisor.java").read_text(encoding="utf-8")
BUILD = (ROOT / "mkruntimeapk.sh").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
ENDPOINT = (ROOT / "src/dev/wind/agent/runtime/RuntimeEndpoint.kt").read_text(encoding="utf-8")
TOOLS = (ROOT / "src/dev/wind/agent/runtime/RuntimeTool.kt").read_text(encoding="utf-8")
BUILD_BACKEND = (ROOT / "src/dev/wind/agent/runtime/EmbeddedBuildBackend.kt").read_text(encoding="utf-8")

def require(value, message):
    if not value:
        raise AssertionError(message)

require('package="dev.wind.agent.runtime"' in MANIFEST, "companion package drifted")
require('android:protectionLevel="signature"' in MANIFEST,
        "binding permission is not signature-level")
require('android:permission="dev.wind.agent.runtime.BIND_RUNTIME"' in MANIFEST,
        "service permission gate missing")
require('android:exported="true"' in MANIFEST, "host cannot bind an exported companion")
for forbidden in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
                  "MANAGE_EXTERNAL_STORAGE", "OPEN_DOCUMENT_TREE",
                  "com.termux.permission.RUN_COMMAND"):
    require(forbidden not in MANIFEST, f"companion requests forbidden permission/API: {forbidden}")

for marker in ("onTransact", "enforceInterface", "TRANSACTION_PROBE",
               "TRANSACTION_OPEN_WORKSPACE", "TRANSACTION_RUN_COMMAND",
               "TRANSACTION_CANCEL", "TRANSACTION_RECONCILE", "TRANSACTION_STOP",
               "TRANSACTION_START_COMMAND", "TRANSACTION_AWAIT_COMMAND",
               # P6.3 Closure-2: the streamed mirror data plane. A host only
               # starts a P3 execution after the probe announces the capability,
               # so these transactions and that announcement travel together.
               "TRANSACTION_OPEN_MIRROR_READ", "TRANSACTION_SYNC_BEGIN",
               "TRANSACTION_SYNC_PLAN_PAGE", "TRANSACTION_SYNC_FILE",
               "TRANSACTION_SYNC_COMMIT", "TRANSACTION_SYNC_ABORT",
               "mirrorStreamProtocolVersion", "MIRROR_STREAM_PROTOCOL_VERSION",
               "ParcelFileDescriptor", "writeToParcel", "readBounded"):
    require(marker in SERVICE, f"Binder protocol marker missing: {marker}")
# The host writer end of the same plane lives in the Kotlin client.
CLIENT = (ROOT / "src/dev/wind/agent/runtime/CompanionRuntimeClient.kt").read_text(encoding="utf-8")
for marker in ("createReliablePipe", "closeWithError", "MIRROR_SOURCE_FAILED",
               "MIRROR_CANCELLED", "TRANSACTION_SYNC_BEGIN", "TRANSACTION_OPEN_MIRROR_READ"):
    require(marker in CLIENT, f"host mirror data-plane marker missing: {marker}")
require("Base64" not in CLIENT, "host client reintroduced a Base64 transfer")
for marker in ("runtime-root", "runtimeInstanceId", "RUNTIME_BINDING_GENERATION_CONFLICT",
               "runtime_binding.json", "project", "home", "tmp", "cache", "imports",
               "PHYSICAL_PATH", "RUNTIME_PATH_ESCAPE", "RUNTIME_BINDING_WRITE_FAILED",
               "PROTOCOL_VERSION = 2"):
    require(marker in WORKSPACE, f"workspace invariant missing: {marker}")
# The companion must not carry a second ignore policy, and its enumerations must
# refuse to run without the host's.
require("IGNORED_SEGMENTS" not in WORKSPACE and "IGNORED_SUFFIXES" not in WORKSPACE,
        "companion still defines a private ignore list")
require("MIRROR_IGNORE_POLICY_MISSING" in WORKSPACE and
        "MIRROR_IGNORE_POLICY_INVALID" in WORKSPACE,
        "companion enumerations do not fail closed without the host policy")
# P3 parity: whole-file streaming hash, scan-change detection, symlink refusal.
require("MIRROR_CHANGED_DURING_SCAN" in WORKSPACE and "MANIFEST_PAGE_BYTES" in WORKSPACE,
        "companion manifest scan is not bounded and change-detecting")
require("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE" in WORKSPACE,
        "companion does not refuse links and special files")
SYNC = (COMP / "src/dev/wind/agent/runtime/RuntimeMirrorSyncSession.java").read_text(encoding="utf-8")
for marker in ("STALE_REMOTE_BEFORE_SYNC", "STALE_REMOTE_BEFORE_COMMIT",
               "RUNTIME_SYNC_PHASE_INVALID", "RUNTIME_SYNC_HASH_MISMATCH",
               "RUNTIME_SYNC_SIZE_MISMATCH", "MAX_FILES", "MAX_TOTAL_BYTES"):
    require(marker in WORKSPACE + SYNC, f"sync transaction invariant missing: {marker}")

# The companion also floors at the same API level as the host: it is now a real
# production runtime, so its minSdk is a shipped contract rather than a detail.
RUNTIME_MANIFEST = (COMP / "AndroidManifest.xml").read_text(encoding="utf-8")
require('android:minSdkVersion="23"' in RUNTIME_MANIFEST,
        "companion minSdk drifted from the API floor")
require("--min-api 23" in BUILD, "companion APK build no longer targets the API floor")
for marker in ("ProcessBuilder", "MAX_TIMEOUT_MS", "destroyForcibly",
               "cleanupConfirmed", "VEGA_RUNTIME_", "VEGA_INTERNAL_",
               "finished.await", "BindingScope", "trackedDescendants"):
    require(marker in SUPERVISOR, f"supervisor invariant missing: {marker}")
for marker in ("aapt2", "javac", "d8", "zipalign", "apksigner",
               "--min-sdk-version 23", "--min-api 23"):
    require(marker in BUILD, f"companion build invariant missing: {marker}")
for marker in ("VegaRuntimeCompanion-v0.2.0-alpha.apk",
               "verify_apk_signer_match.py"):
    require(marker in WORKFLOW, f"host/companion signing gate missing: {marker}")

for marker in ('android:name=".runtime.VegaRuntimeService"',
               'android:process=":runtime"'):
    require(marker in HOST_MANIFEST, f"embedded runtime manifest marker missing: {marker}")
for marker in ("interface RuntimeEndpoint", "ExternalCompanionEndpoint",
               "EmbeddedRuntimeEndpoint", "TRANSACTION_MIRROR_CALL"):
    require(marker in ENDPOINT, f"endpoint abstraction marker missing: {marker}")
for marker in ("TRANSACTION_OPEN_MIRROR_READ", "ParcelFileDescriptor.CREATOR",
               "AutoCloseInputStream", "expectedSize", "maxBytes"):
    require(marker in ENDPOINT, f"endpoint PFD stream marker missing: {marker}")
require("download_file" not in ENDPOINT and "android.util.Base64" not in ENDPOINT,
        "endpoint retained the broken JSON/Base64 file-stream fallback")
for marker in ("enum class RuntimeToolId", "JAVA_COMPILE", "KOTLIN_COMPILE",
               "RuntimeToolResolver", "IN_PROCESS"):
    require(marker in TOOLS, f"logical tool SPI marker missing: {marker}")
for marker in ("EmbeddedJavaCompileSpike", "EcjJavaCompiler", "EMBEDDED_DEX_UNAVAILABLE"):
    require(marker in BUILD_BACKEND, f"embedded build spike marker missing: {marker}")
require("javax.tools" not in BUILD_BACKEND,
        "embedded compiler retained a desktop javac fallback")

print("runtime_companion_tests: OK")
