#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE_ROOT = Path(__file__).resolve().parent
ARTIFACT_ROOT = ROOT / ".build-tools" / "kotlin-spike"
OUTPUT_ROOT = ROOT / ".build-c2-kotlin-candidate"
ANDROID_JAR = ROOT / "assets" / "embedded" / "android-35" / "android.jar"
ANDROID_SHA256 = "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b"
ANDROID_SIZE = 27092450
PLUGIN_MANAGER_INPUT_SHA256 = "9057c880e414a03b97c1d87d147621e611f36886c9f319848150af30c1908d05"
PLUGIN_MANAGER_OUTPUT_SHA256 = "604dba89ce417fd705eebf93350c9bff50c15281d4f542665fe1c41059d439bf"

PINNED_JARS = [
    ("annotations", "annotations-13.0.jar", "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478"),
    ("compiler", "kotlin-compiler-embeddable-1.9.20.jar", "a25024fe5da8440de01af045c4fcb954a22f078738ec02616085f0cfc57b2702"),
    ("daemon", "kotlin-daemon-embeddable-1.9.20.jar", "a939cb5d6ee2a758c9285bd9f3286824beabe12d9a4b5f49f784d0bca329dea5"),
    ("reflect", "kotlin-reflect-1.6.10.jar", "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203"),
    ("script-runtime", "kotlin-script-runtime-1.9.20.jar", "a26a6256a76f766ab8bacdb409b3f8c940d999712a8e88864252b678d66bab9e"),
    ("stdlib", "kotlin-stdlib-1.9.20.jar", "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c"),
    ("trove4j", "trove4j-1.0.20200330.jar", "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d"),
]

DENY_LIST = [
    "java.lang.invoke.MethodHandle*",
    "java.lang.invoke.MethodType",
    "java.awt.*",
    "javax.swing.* except javax.swing.Icon",
    "javax.tools.*",
    "javax.lang.model.*",
    "com.sun.source.*",
    "com.sun.tools.*",
]


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def relative(path):
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def source_hashes():
    files = sorted(
        path for path in CANDIDATE_ROOT.rglob("*")
        if path.is_file() and path.suffix.lower() in {".java", ".kt", ".py", ".md"}
    )
    return {relative(path): sha256_file(path) for path in files}


def validate_inputs():
    checks = {}
    for name, filename, expected in PINNED_JARS:
        path = ARTIFACT_ROOT / filename
        actual = sha256_file(path) if path.is_file() else None
        checks[name] = {
            "path": relative(path),
            "expected_sha256": expected,
            "actual_sha256": actual,
            "matches": actual == expected,
        }
    android_actual = sha256_file(ANDROID_JAR) if ANDROID_JAR.is_file() else None
    android_size = ANDROID_JAR.stat().st_size if ANDROID_JAR.is_file() else None
    checks["android-35"] = {
        "path": relative(ANDROID_JAR),
        "expected_size": ANDROID_SIZE,
        "actual_size": android_size,
        "size_matches": android_size == ANDROID_SIZE,
        "expected_sha256": ANDROID_SHA256,
        "actual_sha256": android_actual,
        "hash_matches": android_actual == ANDROID_SHA256,
        "matches": android_size == ANDROID_SIZE and android_actual == ANDROID_SHA256,
    }
    return checks


def class_major(path):
    data = path.read_bytes()
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    return int.from_bytes(data[6:8], "big")


def collect_class_evidence(output_dir):
    hashes = {}
    majors = {}
    for path in sorted(output_dir.rglob("*.class")):
        key = path.relative_to(output_dir).as_posix()
        hashes[key] = sha256_file(path)
        majors[key] = class_major(path)
    return hashes, majors


def write_json(result, output_dir):
    result_path = output_dir / "result.json"
    result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_command(label, command, output_dir, environment):
    logs = output_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    try:
        completed = subprocess.run(
            [str(part) for part in command],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            check=False,
        )
        exit_code = completed.returncode
        stdout = completed.stdout
        stderr = completed.stderr
    except subprocess.TimeoutExpired as exception:
        exit_code = 124
        stdout = exception.stdout or ""
        stderr = exception.stderr or ""
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", errors="replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        stderr += "\ncommand timed out after 300 seconds\n"
    except OSError as exception:
        exit_code = 127
        stdout = ""
        stderr = str(exception) + "\n"
    (logs / (label + ".stdout.txt")).write_text(stdout, encoding="utf-8")
    (logs / (label + ".stderr.txt")).write_text(stderr, encoding="utf-8")
    return exit_code, stdout


def compiler_command(java, gate_root, overlay_root, icon_root, jar_paths, source, destination, java_peer_root, temp_root):
    classpath_entries = [ARTIFACT_ROOT / "kotlin-stdlib-1.9.20.jar", ANDROID_JAR]
    if java_peer_root is not None:
        classpath_entries.append(java_peer_root)
    kotlin_arguments = [
        "-no-stdlib",
        "-no-reflect",
        "-classpath",
        os.pathsep.join(str(path) for path in classpath_entries),
        "-jvm-target",
        "1.8",
        str(source),
        "-d",
        str(destination),
    ]
    return [
        java,
        "-Djava.io.tmpdir=" + str(temp_root),
        "-cp",
        str(gate_root),
        "AndroidApiGate",
        str(overlay_root),
        str(icon_root),
        *[str(path) for path in jar_paths],
        "--",
        *kotlin_arguments,
    ]


def main():
    parser = argparse.ArgumentParser(description="Run the non-production C2 Kotlin host-JVM candidate smoke harness.")
    parser.add_argument("--run-name", required=True, help="fresh output directory name under .build-c2-kotlin-candidate")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", args.run_name) or args.run_name in {".", ".."}:
        parser.error("--run-name must be one safe directory name")

    output_dir = OUTPUT_ROOT / args.run_name
    if output_dir.exists():
        parser.error("output directory already exists; choose a fresh --run-name")

    checks = validate_inputs()
    result = {
        "input_hashes": checks,
        "candidate_source_hashes": source_hashes(),
        "candidate_output_hashes": {},
        "plugin_manager_core_hashes": {
            "input_sha256": None,
            "expected_input_sha256": PLUGIN_MANAGER_INPUT_SHA256,
            "output_sha256": None,
            "expected_output_sha256": PLUGIN_MANAGER_OUTPUT_SHA256,
        },
        "command_exit_codes": {},
        "class_majors": {},
        "hello_pass": False,
        "mixed_pass": False,
        "evidence_level": "HOST_JVM_SMOKE_ONLY",
        "production_activated": False,
        "api23_art_verified": False,
        "d8_l8_verified": False,
        "deny_list": DENY_LIST,
        "gate_classpath_order": [
            "candidate overlay root",
            "Icon stub root",
            *[name + " pinned jar" for name, _, _ in PINNED_JARS],
        ],
        "error": None,
    }

    output_dir.mkdir(parents=True, exist_ok=False)
    if not all(check["matches"] for check in checks.values()):
        result["error"] = "local pinned artifact validation failed; compiler commands were not run"
        write_json(result, output_dir)
        print("Pinned local artifact verification failed; see " + relative(output_dir / "result.json"), file=sys.stderr)
        return 2

    java = shutil.which("java")
    javac = shutil.which("javac")
    if java is None or javac is None:
        result["error"] = "local java and javac executables must both be available on PATH"
        write_json(result, output_dir)
        print(result["error"], file=sys.stderr)
        return 2

    compiler_jar = ARTIFACT_ROOT / "kotlin-compiler-embeddable-1.9.20.jar"
    jar_paths = [ARTIFACT_ROOT / filename for _, filename, _ in PINNED_JARS]
    output_paths = {
        "overlay": output_dir / "candidate-overlay",
        "icon": output_dir / "icon-stub",
        "patcher": output_dir / "patcher-classes",
        "gate": output_dir / "gate-classes",
        "hello": output_dir / "hello",
        "mixed_java": output_dir / "mixed" / "java-peer",
        "mixed_kotlin": output_dir / "mixed" / "kotlin-peer",
        "mixed_consumer": output_dir / "mixed" / "java-consumer",
        "temp": output_dir / "tmp",
    }
    for path in output_paths.values():
        path.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["TEMP"] = str(output_paths["temp"])
    environment["TMP"] = str(output_paths["temp"])
    environment["TMPDIR"] = str(output_paths["temp"])
    result["runtime_tools"] = {"java": java, "javac": javac}

    def run(label, command):
        code, stdout = run_command(label, command, output_dir, environment)
        result["command_exit_codes"][label] = code
        return code, stdout

    try:
        patcher_source = CANDIDATE_ROOT / "src" / "PatchPluginManagerCore.java"
        code, _ = run("compile_patcher", [javac, "-cp", compiler_jar, "-d", output_paths["patcher"], patcher_source])
        if code != 0:
            raise RuntimeError("patcher compilation failed")

        patcher_classpath = os.pathsep.join([str(output_paths["patcher"]), str(compiler_jar)])
        code, patch_output = run("patch_plugin_manager_core", [
            java, "-cp", patcher_classpath, "PatchPluginManagerCore", compiler_jar, output_paths["overlay"]
        ])
        if code != 0:
            raise RuntimeError("PluginManagerCore patching or structural verification failed")
        for line in patch_output.splitlines():
            if line.startswith("source SHA-256="):
                result["plugin_manager_core_hashes"]["input_sha256"] = line.split("=", 1)[1].strip().lower()
            elif line.startswith("overlay SHA-256="):
                result["plugin_manager_core_hashes"]["output_sha256"] = line.split("=", 1)[1].strip().lower()

        wrapper_sources = [
            CANDIDATE_ROOT / "src" / "ConcurrentIntObjectHashMap.java",
            CANDIDATE_ROOT / "src" / "ConcurrentLongObjectHashMap.java",
        ]
        code, _ = run("compile_map_wrappers", [
            javac, "--release", "8", "-cp", compiler_jar, "-d", output_paths["overlay"], *wrapper_sources
        ])
        if code != 0:
            raise RuntimeError("Java 8 map-wrapper compilation failed")

        icon_source = CANDIDATE_ROOT / "src" / "javax" / "swing" / "Icon.java"
        code, _ = run("compile_icon_stub", [javac, "--release", "8", "-d", output_paths["icon"], icon_source])
        if code != 0:
            raise RuntimeError("test-only Icon stub compilation failed")

        gate_source = CANDIDATE_ROOT / "src" / "AndroidApiGate.java"
        code, _ = run("compile_android_api_gate", [javac, "-d", output_paths["gate"], gate_source])
        if code != 0:
            raise RuntimeError("Android API gate compilation failed")

        hello_source = CANDIDATE_ROOT / "smoke" / "Hello.kt"
        (output_paths["hello"] / "META-INF").mkdir(parents=True, exist_ok=True)
        code, _ = run("compile_hello", compiler_command(
            java, output_paths["gate"], output_paths["overlay"], output_paths["icon"], jar_paths,
            hello_source, output_paths["hello"], None, output_paths["temp"]
        ))
        hello_class = output_paths["hello"] / "Hello.class"
        result["hello_pass"] = code == 0 and hello_class.is_file() and class_major(hello_class) == 52

        java_peer_source = CANDIDATE_ROOT / "smoke" / "mixed" / "JavaPeer.java"
        kotlin_peer_source = CANDIDATE_ROOT / "smoke" / "mixed" / "KotlinPeer.kt"
        java_consumer_source = CANDIDATE_ROOT / "smoke" / "mixed" / "JavaConsumer.java"
        code_java_peer, _ = run("compile_java_peer", [
            javac, "--release", "8", "-d", output_paths["mixed_java"], java_peer_source
        ])
        code_kotlin_peer = 127
        code_java_consumer = 127
        if code_java_peer == 0:
            code_kotlin_peer, _ = run("compile_kotlin_peer", compiler_command(
                java, output_paths["gate"], output_paths["overlay"], output_paths["icon"], jar_paths,
                kotlin_peer_source, output_paths["mixed_kotlin"], output_paths["mixed_java"], output_paths["temp"]
            ))
        else:
            result["command_exit_codes"]["compile_kotlin_peer"] = None
        if code_kotlin_peer == 0:
            mixed_classpath = os.pathsep.join([
                str(output_paths["mixed_kotlin"]),
                str(output_paths["mixed_java"]),
                str(ARTIFACT_ROOT / "kotlin-stdlib-1.9.20.jar"),
            ])
            code_java_consumer, _ = run("compile_java_consumer", [
                javac, "--release", "8", "-cp", mixed_classpath,
                "-d", output_paths["mixed_consumer"], java_consumer_source
            ])
        else:
            result["command_exit_codes"]["compile_java_consumer"] = None

        mixed_classes = [
            output_paths["mixed_java"] / "JavaPeer.class",
            output_paths["mixed_kotlin"] / "KotlinPeer.class",
            output_paths["mixed_consumer"] / "JavaConsumer.class",
        ]
        result["mixed_pass"] = (
            code_java_peer == 0
            and code_kotlin_peer == 0
            and code_java_consumer == 0
            and all(path.is_file() and class_major(path) == 52 for path in mixed_classes)
        )
        output_hashes, majors = collect_class_evidence(output_dir)
        result["candidate_output_hashes"] = output_hashes
        result["class_majors"] = majors

        if result["plugin_manager_core_hashes"]["input_sha256"] != PLUGIN_MANAGER_INPUT_SHA256:
            raise RuntimeError("PluginManagerCore input hash evidence did not match the pinned history")
        if result["plugin_manager_core_hashes"]["output_sha256"] != PLUGIN_MANAGER_OUTPUT_SHA256:
            raise RuntimeError("PluginManagerCore output hash evidence did not match the pinned history")
        if not result["hello_pass"]:
            raise RuntimeError("Hello smoke failed or Hello.class is not major 52")
        if not result["mixed_pass"]:
            raise RuntimeError("mixed Java/Kotlin smoke failed or a class is not major 52")
    except RuntimeError as exception:
        result["error"] = str(exception)
        output_hashes, majors = collect_class_evidence(output_dir)
        result["candidate_output_hashes"] = output_hashes
        result["class_majors"] = majors

    write_json(result, output_dir)
    print("result=" + relative(output_dir / "result.json"))
    print("hello_pass=" + str(result["hello_pass"]).lower())
    print("mixed_pass=" + str(result["mixed_pass"]).lower())
    return 0 if result["hello_pass"] and result["mixed_pass"] and result["error"] is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
