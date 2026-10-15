# C2 Kotlin API 23 Compatibility Candidate Harness

This directory contains a non-production, host-JVM evidence tool. It does not activate a Kotlin provider, add a provider descriptor, or make KOTLIN_COMPILE available; that capability remains unavailable.

## Run

Requires an already-installed local Python 3, JDK (java and javac on PATH), and the pinned local artifacts listed below. The runner uses only those local files and Python's standard library. It never downloads artifacts or accesses the network.

Run twice with fresh output names to reproduce the candidate check:

    python tools/c2-kotlin-api23-candidate/run_candidate.py --run-name run-1
    python tools/c2-kotlin-api23-candidate/run_candidate.py --run-name run-2

Each run refuses to reuse an existing output directory and writes only under .build-c2-kotlin-candidate/<run-name>/. The result.json records input and candidate source/output hashes, command exit codes, class majors, smoke pass fields, and explicit evidence limits. Command logs are kept in that run's logs/ directory.

The runner checks the seven Kotlin/compiler dependency JAR SHA-256 values before invoking any compiler command. They must already exist under .build-tools/kotlin-spike/. It also verifies assets/embedded/android-35/android.jar is exactly 27,092,450 bytes with SHA-256 4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b.

## Candidate procedure

The runner compiles the copied Java 8 ConcurrentIntObjectHashMap and ConcurrentLongObjectHashMap wrappers against the pinned compiler JAR. The ASM patcher extracts the original PluginManagerCore.class in memory, validates its historical input SHA-256, requires exactly one HAS_LOADED_CLASS_METHOD_TYPE initializer pattern, replaces only GETSTATIC Boolean.TYPE + LDC String.class + INVOKESTATIC MethodType.methodType with ACONST_NULL, retains the existing PUTSTATIC, verifies the historical overlay SHA-256, and checks that other method instruction, branch, frame, max stack/local, and exception-table fingerprints are unchanged.

The Kotlin compiler classloader order is fixed: candidate overlay root, test-only javax.swing.Icon stub root, then the seven pinned JARs in deterministic alphabetical filename order. Its deny-list is the scratch experiment's exact list: java.lang.invoke.MethodHandle*, exact java.lang.invoke.MethodType, java.awt.*, javax.swing.* except javax.swing.Icon, javax.tools.*, javax.lang.model.*, com.sun.source.*, and com.sun.tools.*.

The Hello smoke compiles Hello.kt with K2JVMCompiler, pinned stdlib, and the verified Android 35 jar on its compile classpath. The mixed smoke compiles JavaPeer with javac --release 8, KotlinPeer through the gated K2JVMCompiler, then JavaConsumer with javac --release 8. The runner requires Hello.class, JavaPeer.class, KotlinPeer.class, and JavaConsumer.class to have class-file major version 52.

## Evidence limits

android-35/android.jar supplies compile symbols only; it is not an API 35 runtime. A successful result is HOST_JVM_SMOKE_ONLY and does not establish API 23 Android ART compatibility. This harness does not run D8/L8, package an APK, use adb, or test an emulator/device. In particular, success does not change KOTLIN_COMPILE availability or production activation, and does not resolve the separate .kotlin_module collision work.
