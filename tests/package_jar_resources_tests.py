#!/usr/bin/env python3
"""Focused tests for tools/PackageJarResources.java."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def _jdk_tools(home: Path) -> tuple[Path, Path] | None:
    bin_dir = home / "bin"
    suffixes = (".exe", "") if os.name == "nt" else ("", ".exe")
    for suffix in suffixes:
        javac = bin_dir / f"javac{suffix}"
        java = bin_dir / f"java{suffix}"
        if javac.is_file() and java.is_file():
            return javac, java
    return None


def _same_jdk(javac: Path, java: Path) -> bool:
    try:
        javac_home = javac.resolve(strict=True).parent.parent
        java_home = java.resolve(strict=True).parent.parent
    except OSError:
        return False
    return os.path.normcase(str(javac_home)) == os.path.normcase(str(java_home))


def _supports_java_17(javac: Path) -> bool:
    try:
        result = subprocess.run(
            [str(javac), "-version"],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
    except OSError:
        return False
    output = result.stdout + "\n" + result.stderr
    match = re.search(r"\bjavac\s+([0-9]+)(?:\.([0-9]+))?", output)
    if result.returncode != 0 or not match:
        return False
    major = int(match.group(1))
    if major == 1:
        major = int(match.group(2) or "0")
    return major >= 17


def _find_jdk_tools() -> tuple[Path, Path]:
    attempts: list[str] = []
    configured_home = os.environ.get("JAVA_HOME")
    if configured_home:
        home = Path(configured_home)
        tools = _jdk_tools(home)
        if tools and _same_jdk(*tools) and _supports_java_17(tools[0]):
            return tools
        attempts.append(f"JAVA_HOME does not contain a usable JDK 17+: {home}")

    javac_from_path = shutil.which("javac")
    java_from_path = shutil.which("java")
    if javac_from_path and java_from_path:
        tools = Path(javac_from_path), Path(java_from_path)
        if _same_jdk(*tools) and _supports_java_17(tools[0]):
            return tools
        attempts.append("PATH java and javac are not from the same JDK 17+ installation")
    else:
        attempts.append("PATH does not contain both java and javac")

    raise AssertionError("Could not find a matching JDK 17+; set JAVA_HOME or update PATH. "
                         + "; ".join(attempts))


JAVAC, JAVA = _find_jdk_tools()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            archive.writestr(name, entries[name])


def run_tool(classes: Path, apk: Path, *jars: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(JAVA), "-cp", str(classes), "PackageJarResources", str(apk), *(str(j) for j in jars)],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )


def read_entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def main() -> None:
    require(JAVAC.is_file(), f"JDK 17 javac not found: {JAVAC}")
    require(JAVA.is_file(), f"JDK 17 java not found: {JAVA}")

    with tempfile.TemporaryDirectory(prefix="vega-package-jar-resources-") as temp_name:
        temp = Path(temp_name)
        classes = temp / "classes"
        classes.mkdir()
        compile_result = subprocess.run(
            [str(JAVAC), "-encoding", "UTF-8", "-source", "17", "-target", "17",
             "-d", str(classes), str(ROOT / "tools" / "PackageJarResources.java")],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        require(compile_result.returncode == 0,
                f"javac failed:\n{compile_result.stdout}\n{compile_result.stderr}")

        base_entries = {
            "AndroidManifest.xml": b"<manifest package='test'/>",
            "classes.dex": b"dex\n035\0synthetic",
            "assets/keep.txt": b"keep",
        }
        base_one = temp / "base-one.apk"
        base_two = temp / "base-two.apk"
        write_zip(base_one, base_entries)
        write_zip(base_two, base_entries)

        jar_one = temp / "jar-one.jar"
        write_zip(
            jar_one,
            {
                "com/example/Skipped.class": b"class-bytes",
                "same.properties": b"same=true\n",
                "conflict.properties": b"from-one\n",
                "kotlin/test.kotlin_builtins": b"builtins",
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
                "META-INF/INDEX.LIST": b"index",
                "META-INF/one.SF": b"signature",
                "META-INF/services/com.example.Tool": (
                    b"# comment\ncom.example.Shared\ncom.example.A\n"
                ),
                "META-INF/vega-android-adaptation.properties": b"adapted=true\n",
                "META-INF/LICENSE.txt": b"license from one\n",
                "META-INF/NOTICE.txt": b"shared notice\n",
                "about.html": b"<html>ECJ provider information</html>\n",
            },
        )
        jar_two = temp / "jar-two.jar"
        write_zip(
            jar_two,
            {
                "com/example/Other.class": b"other-class-bytes",
                "same.properties": b"same=true\n",
                "conflict.properties": b"from-one\n",
                "META-INF/services/com.example.Tool": (
                    b"com.example.B\ncom.example.Shared # duplicate\n"
                ),
                "META-INF/other.RSA": b"signature",
                "META-INF/LICENSE.txt": b"license from two\n",
                "META-INF/NOTICE.txt": b"shared notice\n",
                "about.html": b"<html>JGit provider information</html>\n",
            },
        )

        success = run_tool(classes, base_one, jar_two, jar_one)
        require(success.returncode == 0,
                f"successful packaging failed:\n{success.stdout}\n{success.stderr}")
        packaged = read_entries(base_one)
        require(packaged["AndroidManifest.xml"] == base_entries["AndroidManifest.xml"],
                "existing APK entry changed")
        require(packaged["classes.dex"] == base_entries["classes.dex"],
                "existing DEX changed")
        require(packaged["same.properties"] == b"same=true\n",
                "identical duplicate resource was not retained")
        require(packaged["kotlin/test.kotlin_builtins"] == b"builtins",
                "kotlin builtins resource was not packaged at APK root")
        require(packaged["META-INF/vega-android-adaptation.properties"] == b"adapted=true\n",
                "ECJ adaptation provenance was not packaged")
        require(packaged["META-INF/services/com.example.Tool"] == (
            b"com.example.A\ncom.example.B\ncom.example.Shared\n"
        ), "service providers were not merged and deduplicated deterministically")
        require("com/example/Skipped.class" not in packaged and "com/example/Other.class" not in packaged,
                "class entry leaked into the APK resource set")
        for filtered in ("META-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/one.SF", "META-INF/other.RSA"):
            require(filtered not in packaged, f"filtered signature/manifest entry leaked: {filtered}")
        require("META-INF/NOTICE.txt" in packaged,
                "identical NOTICE duplicate should retain the original path")
        license_paths = sorted(
            name for name in packaged if name.startswith("META-INF/vega-resources/")
            and name.endswith("/META-INF/LICENSE.txt")
        )
        require(len(license_paths) == 2,
                f"conflicting licenses were not kept by source: {license_paths}")
        about_paths = sorted(
            name for name in packaged if name.startswith("META-INF/vega-resources/")
            and name.endswith("/about.html")
        )
        require(about_paths == [
            "META-INF/vega-resources/jar000-jar-one.jar/about.html",
            "META-INF/vega-resources/jar001-jar-two.jar/about.html",
        ], f"conflicting root about.html resources were not kept by source: {about_paths}")
        require("about.html" not in packaged,
                "ambiguous root about.html was exposed without a source namespace")
        require(packaged[about_paths[0]] == b"<html>ECJ provider information</html>\n"
                and packaged[about_paths[1]] == b"<html>JGit provider information</html>\n",
                "namespaced about.html resource bytes changed")
        require(all(not name.startswith("assets/") for name in packaged if name.endswith(".properties")),
                "provider resources were unexpectedly nested under assets")

        # Sorted entry names and fixed timestamps make equivalent input builds
        # byte-identical, independent of the order in which jars were passed.
        deterministic = run_tool(classes, base_two, jar_one, jar_two)
        require(deterministic.returncode == 0,
                f"second deterministic packaging failed:\n{deterministic.stdout}\n{deterministic.stderr}")
        require(digest(base_one) == digest(base_two),
                "equivalent resource packaging was not deterministic")

        def failure_case(name: str, bad_jar: Path) -> None:
            target = temp / name
            write_zip(target, base_entries)
            before = target.read_bytes()
            result = run_tool(classes, target, bad_jar)
            require(result.returncode != 0, f"invalid input unexpectedly succeeded: {bad_jar}")
            require(target.read_bytes() == before,
                    f"failed packaging modified the original APK: {bad_jar}\n{result.stderr}")
            require(not list(temp.glob(target.name + ".resources-*.tmp")),
                    f"temporary output was left after failure: {bad_jar}")

        conflict = temp / "conflict.jar"
        write_zip(conflict, {"conflict.properties": b"different\n"})
        # A normal conflict needs a first candidate from the APK or another jar.
        conflict_target = temp / "conflict-target.apk"
        write_zip(conflict_target, {**base_entries, "conflict.properties": b"base\n"})
        before_conflict = conflict_target.read_bytes()
        conflict_result = run_tool(classes, conflict_target, conflict)
        require(conflict_result.returncode != 0, "ordinary resource conflict unexpectedly succeeded")
        require(conflict_target.read_bytes() == before_conflict,
                "ordinary conflict damaged the original APK")
        require(not list(temp.glob(conflict_target.name + ".resources-*.tmp")),
                "temporary output was left after ordinary resource conflict")

        signed_target = temp / "signed-target.apk"
        write_zip(signed_target, {
            **base_entries,
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
            "META-INF/VEGA.SF": b"Signature-Version: 1.0\n",
        })
        before_signed = signed_target.read_bytes()
        signed_result = run_tool(classes, signed_target, jar_one)
        require(signed_result.returncode != 0,
                "signed target APK unexpectedly entered the resource rewrite path")
        require(signed_target.read_bytes() == before_signed,
                "signed target APK changed after the helper rejected it")
        require(not list(temp.glob(signed_target.name + ".resources-*.tmp")),
                "temporary output was left after rejecting signed target APK")

        v2_signed_target = temp / "v2-signed-target.apk"
        write_zip(v2_signed_target, base_entries)
        raw = v2_signed_target.read_bytes()
        eocd = raw.rfind(b"PK\x05\x06")
        require(eocd >= 0, "synthetic APK has no end-of-central-directory record")
        central_directory_offset = struct.unpack_from("<I", raw, eocd + 16)[0]
        require(central_directory_offset != 0xFFFFFFFF,
                "synthetic APK unexpectedly requires ZIP64 signing-block setup")
        signer_pair = struct.pack("<Q", 5) + struct.pack("<I", 0x7109871A) + b"x"
        signing_block_size = len(signer_pair) + 24
        signing_block = (
            struct.pack("<Q", signing_block_size)
            + signer_pair
            + struct.pack("<Q", signing_block_size)
            + b"APK Sig Block 42"
        )
        signed = bytearray(
            raw[:central_directory_offset] + signing_block + raw[central_directory_offset:]
        )
        signed_eocd = eocd + len(signing_block)
        struct.pack_into("<I", signed, signed_eocd + 16,
                         central_directory_offset + len(signing_block))
        v2_signed_target.write_bytes(signed)
        with zipfile.ZipFile(v2_signed_target) as archive:
            require(archive.testzip() is None, "synthetic APK signing block broke ZIP readability")
        before_v2_signed = v2_signed_target.read_bytes()
        v2_signed_result = run_tool(classes, v2_signed_target, jar_one)
        require(v2_signed_result.returncode != 0,
                "APK Signature Scheme v2/v3 block unexpectedly entered the rewrite path")
        require(v2_signed_target.read_bytes() == before_v2_signed,
                "v2/v3-signed target APK changed after the helper rejected it")
        require(not list(temp.glob(v2_signed_target.name + ".resources-*.tmp")),
                "temporary output was left after rejecting v2/v3-signed target APK")

        invalid_parent = temp / "invalid-parent.jar"
        write_zip(invalid_parent, {"../evil.properties": b"evil"})
        failure_case("invalid-parent.apk", invalid_parent)

        invalid_absolute = temp / "invalid-absolute.jar"
        write_zip(invalid_absolute, {"/evil.properties": b"evil"})
        failure_case("invalid-absolute.apk", invalid_absolute)

        invalid_backslash = temp / "invalid-backslash.jar"
        # Python's Windows zipfile writer normalizes backslashes to slashes.
        # Patch the equal-length ZIP name bytes so the Java reader sees the
        # hostile separator exactly as it would arrive from an external jar.
        write_zip(invalid_backslash, {"dir/evil.properties": b"evil"})
        raw = invalid_backslash.read_bytes()
        require(b"dir/evil.properties" in raw, "test ZIP did not contain the expected path")
        invalid_backslash.write_bytes(raw.replace(b"dir/evil.properties", b"dir\\evil.properties"))
        failure_case("invalid-backslash.apk", invalid_backslash)

    print("package_jar_resources_tests: OK")


if __name__ == "__main__":
    main()
