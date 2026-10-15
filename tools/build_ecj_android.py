#!/usr/bin/env python3
"""Rebuild the pinned ECJ Android adaptation without modifying the upstream jar."""
import argparse
import hashlib
import pathlib
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / "third_party/embedded/ecj/ecj-3.46.100.jar"
SOURCE = ROOT / "third_party/embedded/ecj/android-src/org/eclipse/jdt/internal/compiler/batch/FileSystem.java"
OUTPUT = ROOT / "third_party/embedded/ecj/ecj-3.46.100-android1.jar"
BASE_SHA256 = "74c93172475fee0cd4e6eefb44233516dd40525642da35d009146d97ea598229"
SOURCE_SHA256 = "8ec0ce423606206e8d32cb8b89b0080bfa65fc41367ce3770d946f4040ebd3e5"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    for path, expected in ((BASE, BASE_SHA256), (SOURCE, SOURCE_SHA256)):
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Unreviewed adaptation input: {path}")
    compiler = subprocess.check_output([args.javac, "-version"], stderr=subprocess.STDOUT).decode().strip()
    if not compiler.startswith("javac 17."):
        raise SystemExit("The adaptation must be built with javac 17")
    with tempfile.TemporaryDirectory(prefix="ecj-adapt-") as temp:
        classes = pathlib.Path(temp) / "classes"
        subprocess.run([args.javac, "-encoding", "UTF-8", "--release", "17", "-g:none",
                        "-cp", str(BASE), "-d", str(classes), str(SOURCE)], check=True)
        with zipfile.ZipFile(BASE) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()
                       if not name.endswith("/") and not
                       (name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA")))}
        replacements = list(classes.rglob("*.class"))
        if not replacements:
            raise SystemExit("No adapted classes produced")
        # Remove old compiler-generated nested classes before installing the
        # complete javac output for this one reviewed upstream source file.
        prefix = "org/eclipse/jdt/internal/compiler/batch/FileSystem"
        for name in list(entries):
            if name.endswith(".class") and (name == prefix + ".class" or name.startswith(prefix + "$")):
                del entries[name]
        for path in replacements:
            entries[path.relative_to(classes).as_posix()] = path.read_bytes()
        entries["META-INF/vega-android-adaptation.properties"] = (
            f"base.sha256={BASE_SHA256}\nsource.sha256={SOURCE_SHA256}\ncompiler={compiler}\n"
            "adaptation=optional-java.compiler-capability-detection\n").encode()
        with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data, compresslevel=9)
    print(f"{hashlib.sha256(OUTPUT.read_bytes()).hexdigest()}  {OUTPUT.relative_to(ROOT).as_posix()}")


if __name__ == "__main__":
    main()
