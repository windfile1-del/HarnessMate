#!/usr/bin/env python3
"""Host-side R0 ELF, dependency, API-23 import, and vendor-lock verification.

This inspects existing build outputs. It does not rebuild, package, install, or
launch anything on Android.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "third_party/runtime"
LIBRARIES = {
    "libandroidshmem.so",
    "libproot.so",
    "libprootloader.so",
    "libtalloc.so",
    "libvegaspawn.so",
}
ABI = {
    "arm64-v8a": {
        "triple": "aarch64-linux-android",
        "machine": "AArch64",
        "entry": 0x2000000000,
    },
    "x86_64": {
        "triple": "x86_64-linux-android",
        "machine": "Advanced Micro Devices X86-64",
        "entry": 0x600000000000,
    },
}
EXPECTED_NEEDED = {
    "libproot.so": {"libtalloc.so", "libandroidshmem.so", "libdl.so", "libc.so"},
    "libprootloader.so": set(),
    "libtalloc.so": {"libdl.so", "libc.so"},
    "libandroidshmem.so": {"liblog.so", "libdl.so", "libc.so"},
    "libvegaspawn.so": {"libdl.so", "libc.so"},
}
COMMON_BIONIC_STUBS = {"libc.so", "libdl.so", "liblog.so", "libm.so", "libandroid.so"}
PAGE_SIZE = 16384


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_blob_sha1(data):
    header = b"blob " + str(len(data)).encode("ascii") + b"\0"
    return hashlib.sha1(header + data).hexdigest()


def read_json(path):
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("Cannot read JSON " + str(path) + ": " + str(exc)) from exc


def checked(argv):
    result = subprocess.run(
        [str(item) for item in argv],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode:
        raise ValueError(
            "Command failed (" + str(result.returncode) + "): "
            + " ".join(map(str, argv)) + "\n" + result.stderr
        )
    return result.stdout


def validate_vendor_lock():
    lock_path = RUNTIME / "vendor-sha256.json"
    lock = read_json(lock_path)
    require(lock.get("schema") == 1, "Unsupported vendor lock schema")
    require(lock.get("algorithm") == "sha256", "Vendor lock algorithm must be sha256")

    source_lock = read_json(RUNTIME / "source-lock.json")
    upstream = {}
    for entry in source_lock.get("files", []):
        destination = entry.get("destination", "")
        prefix = "third_party/runtime/"
        if destination.startswith(prefix):
            relative = destination[len(prefix):]
            if relative.startswith("vendor/"):
                upstream[relative] = entry.get("git_blob")

    vendor_root = RUNTIME / "vendor"
    actual_paths = set()
    for path in vendor_root.rglob("*"):
        if path.is_symlink():
            raise ValueError("Symlink in vendored tree: " + str(path))
        if path.is_file():
            actual_paths.add(path.relative_to(RUNTIME).as_posix())
    entries = lock.get("files")
    require(isinstance(entries, list), "Vendor lock files must be a list")
    locked_paths = set()
    for entry in entries:
        relative = entry.get("path", "")
        posix = PurePosixPath(relative)
        require(
            relative.startswith("vendor/")
            and "\\" not in relative
            and not posix.is_absolute()
            and ".." not in posix.parts,
            "Unsafe vendor lock path: " + str(relative),
        )
        require(relative not in locked_paths, "Duplicate vendor lock path: " + relative)
        locked_paths.add(relative)
        require(relative in upstream, "No source-lock provenance for " + relative)
        require(
            entry.get("upstreamGitBlob") == upstream[relative],
            "Vendor lock upstream Git blob differs from source-lock.json: " + relative,
        )
        path = RUNTIME / Path(*posix.parts)
        require(path.is_file() and not path.is_symlink(), "Missing regular vendor file: " + relative)
        data = path.read_bytes()
        require(len(data) == entry.get("bytes"), "Vendor file size mismatch: " + relative)
        require(hashlib.sha256(data).hexdigest() == entry.get("sha256"),
                "Vendor SHA-256 mismatch: " + relative)
        require(entry.get("upstreamNormalization") == "one-final-lf-added-locally",
                "Unexpected local/upstream byte adjustment: " + relative)
        require(data.endswith(b"\n") and git_blob_sha1(data[:-1]) == upstream[relative],
                "Vendor bytes do not match the upstream Git blob plus one final LF: " + relative)
    require(
        locked_paths == actual_paths == set(upstream),
        "Vendor lock, source-lock, and vendored file sets differ",
    )
    return {
        "fileCount": len(entries),
        "files": [
            {"path": item["path"], "bytes": item["bytes"], "sha256": item["sha256"]}
            for item in entries
        ],
        "sourceLock": str((RUNTIME / "source-lock.json").resolve()),
        "localBytesAreHashed": True,
    }


def host_prebuilt(ndk):
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    prebuilt = ndk / "toolchains/llvm/prebuilt" / host
    suffix = ".exe" if os.name == "nt" else ""
    readelf = prebuilt / "bin" / ("llvm-readelf" + suffix)
    require(readelf.is_file(), "NDK llvm-readelf not found: " + str(readelf))
    return prebuilt, readelf


def parse_symbols(text):
    undefined = []
    defined = set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 8 or not re.fullmatch(r"\d+:", fields[0]):
            continue
        index = fields[6]
        name = fields[7]
        if not name:
            continue
        base = name.split("@", 1)[0]
        if index == "UND":
            undefined.append({"name": base, "binding": fields[4], "raw": name})
        else:
            defined.add(base)
    return undefined, defined


def parse_elf_report(text, abi, name):
    require(re.search(r"Class:\s+ELF64\b", text) is not None, name + " is not ELF64")
    require(re.search(r"Data:\s+2's complement,\s+little endian", text) is not None,
            name + " is not little-endian ELF")
    machine = re.search(r"Machine:\s+(.+)", text)
    require(machine is not None and machine.group(1).strip() == ABI[abi]["machine"],
            name + " has the wrong e_machine for " + abi)
    elf_type = re.search(r"Type:\s+(\w+)", text)
    entry = re.search(r"Entry point address:\s+(0x[0-9a-fA-F]+)", text)
    require(elf_type is not None and entry is not None, "Incomplete ELF header for " + name)
    result = {
        "type": elf_type.group(1),
        "entry": int(entry.group(1), 16),
        "loads": [],
        "needed": set(re.findall(r"Shared library:\s+\[([^\]]+)\]", text)),
        "soname": None,
        "interpreter": None,
        "hasDynamic": "Dynamic section at offset" in text,
        "pieFlag": re.search(r"\(FLAGS_1\).*?\bPIE\b", text) is not None,
    }
    soname = re.search(r"Library soname:\s+\[([^\]]+)\]", text)
    interp = re.search(r"Requesting program interpreter:\s+([^\]]+)", text)
    result["soname"] = soname.group(1) if soname else None
    result["interpreter"] = interp.group(1) if interp else None
    for line in text.splitlines():
        fields = line.split()
        if not fields or fields[0] != "LOAD":
            continue
        require(len(fields) >= 8, "Could not parse PT_LOAD: " + line)
        load = {
            "offset": int(fields[1], 16),
            "vaddr": int(fields[2], 16),
            "filesz": int(fields[4], 16),
            "memsz": int(fields[5], 16),
            "flags": fields[-2],
            "align": int(fields[-1], 16),
        }
        require(load["align"] >= PAGE_SIZE, name + " has PT_LOAD alignment below 16 KiB")
        require(
            load["offset"] % PAGE_SIZE == load["vaddr"] % PAGE_SIZE,
            name + " has incongruent PT_LOAD offset/vaddr at 16 KiB",
        )
        result["loads"].append(load)
    require(result["loads"], name + " has no PT_LOAD segments")
    for needed in result["needed"]:
        require(needed.endswith(".so"), name + " has an unexpected dependency name: " + needed)
    return result


def verify_abi(abi, native_root, ndk, prebuilt, readelf, ndk_properties):
    directory = native_root / abi
    require(directory.is_dir(), "Missing native ABI output: " + str(directory))
    build_report = read_json(directory / "build-result.json")
    require(build_report.get("schema") == 1, "Unsupported build-result schema for " + abi)
    require(build_report.get("abi") == abi, "build-result ABI mismatch for " + abi)
    require(build_report.get("minSdk") == 23, "Native build report is not API 23 for " + abi)
    source_lock = read_json(RUNTIME / "source-lock.json")
    require(build_report.get("proot") == source_lock.get("proot"),
            "PRoot build pin differs from source-lock.json")
    require(build_report.get("ndkRevision", "").strip() == ndk_properties.read_text("utf-8").strip(),
            "Recorded NDK source.properties differs from the selected NDK")

    built = {item.get("path"): item for item in build_report.get("binaries", [])}
    require(set(built) == LIBRARIES, "Build report does not contain the exact five-file set for " + abi)
    actual = {path.name for path in directory.glob("*.so")}
    require(actual == LIBRARIES, "Native output directory has a missing or extra .so for " + abi)

    triple_dir = prebuilt / "sysroot/usr/lib" / ABI[abi]["triple"] / "23"
    require(triple_dir.is_dir(), "NDK API-23 stub directory is missing: " + str(triple_dir))
    system_needed = set().union(*(EXPECTED_NEEDED[name] for name in LIBRARIES)) - LIBRARIES
    stub_paths = {
        name: triple_dir / name
        for name in sorted(system_needed | COMMON_BIONIC_STUBS)
        if (triple_dir / name).is_file()
    }
    missing_common = COMMON_BIONIC_STUBS - set(stub_paths)
    require(not missing_common, "NDK API-23 stubs lack common Bionic libraries: "
            + ", ".join(sorted(missing_common)))
    stub_exports = {}
    for soname, path in sorted(stub_paths.items()):
        _, exports = parse_symbols(checked([readelf, "--dyn-syms", "--wide", path]))
        stub_exports[soname] = exports

    parsed = {}
    for name in sorted(LIBRARIES):
        path = directory / name
        entry = built[name]
        require(path.stat().st_size == entry.get("bytes"), "Build report size mismatch: " + str(path))
        require(sha256(path) == entry.get("sha256"), "Build report SHA-256 mismatch: " + str(path))
        elf_text = checked([readelf, "-h", "-l", "-d", "-W", path])
        elf = parse_elf_report(elf_text, abi, name)
        dyn_symbols, exports = parse_symbols(checked([readelf, "--dyn-syms", "--wide", path]))
        require(elf["needed"] == EXPECTED_NEEDED[name],
                name + " has unexpected DT_NEEDED names: " + ", ".join(sorted(elf["needed"])))
        if name == "libproot.so":
            require(elf["type"] == "DYN" and elf["hasDynamic"] and elf["pieFlag"],
                    "libproot.so is not a dynamic PIE")
            require(elf["entry"] > 0 and any(
                "E" in load["flags"] and load["vaddr"] <= elf["entry"] < load["vaddr"] + load["memsz"]
                for load in elf["loads"]
            ), "libproot.so entry is outside executable PT_LOAD")
            require(elf["interpreter"] == "/system/bin/linker64",
                    "libproot.so has an unexpected Android interpreter")
        elif name == "libprootloader.so":
            require(elf["type"] == "EXEC", "libprootloader.so is not a fixed-address executable")
            require(elf["entry"] == ABI[abi]["entry"], "libprootloader.so entry point is unexpected")
            require(not elf["hasDynamic"] and not elf["interpreter"] and not elf["needed"],
                    "libprootloader.so is not static")
            require(any(
                "E" in load["flags"] and load["vaddr"] <= elf["entry"] < load["vaddr"] + load["memsz"]
                for load in elf["loads"]
            ), "libprootloader.so entry is outside executable PT_LOAD")
            require(not dyn_symbols and not exports,
                    "Static loader unexpectedly has dynamic symbol imports/exports")
        else:
            require(elf["type"] == "DYN" and elf["hasDynamic"],
                    name + " is not a dynamic shared library")
            require(elf["entry"] == 0 and not elf["interpreter"],
                    name + " has an executable entry/interpreter")
            require(elf["soname"] == name, name + " SONAME differs from its packaged filename")
        parsed[name] = {"elf": elf, "undefined": dyn_symbols, "exports": exports}

    bundled_exports = {name: parsed[name]["exports"] for name in LIBRARIES}
    symbol_audit = []
    for name in sorted(LIBRARIES - {"libprootloader.so"}):
        elf = parsed[name]["elf"]
        available_by_needed = {}
        for soname in elf["needed"]:
            if soname in bundled_exports:
                available_by_needed[soname] = bundled_exports[soname]
            else:
                require(soname in stub_exports,
                        name + " dependency is neither bundled nor an API-23 NDK stub: " + soname)
                available_by_needed[soname] = stub_exports[soname]
        unavailable = []
        weak_unresolved = []
        checked_count = 0
        for symbol in parsed[name]["undefined"]:
            providers = [soname for soname, exports in available_by_needed.items()
                         if symbol["name"] in exports]
            if providers:
                checked_count += 1
            elif symbol["binding"] == "WEAK":
                weak_unresolved.append(symbol["raw"])
            else:
                unavailable.append(symbol["raw"])
        require(not unavailable,
                name + " has imports absent from its direct shipped/API-23 dependencies: "
                + ", ".join(sorted(unavailable)))
        symbol_audit.append({
            "file": name,
            "strongImportsResolvedByApi23OrShippedLibraries": checked_count,
            "weakUnresolvedOptionalImports": sorted(weak_unresolved),
            "systemNeeded": sorted(elf["needed"] - LIBRARIES),
        })

    return {
        "status": "PASS",
        "abi": abi,
        "minSdk": 23,
        "ndkRevision": re.search(r"(?m)^Pkg\.Revision\s*=\s*(\S+)",
                                 ndk_properties.read_text("utf-8")).group(1),
        "ndkSourceProperties": str((ndk / "source.properties").resolve()),
        "api23StubDirectory": str(triple_dir.resolve()),
        "api23StubLibrariesRead": sorted(stub_paths),
        "libraries": {
            name: {
                "bytes": built[name]["bytes"],
                "sha256": built[name]["sha256"],
                "elfType": parsed[name]["elf"]["type"],
                "entry": "0x%x" % parsed[name]["elf"]["entry"],
                "needed": sorted(parsed[name]["elf"]["needed"]),
                "loadSegments": [
                    {"offset": "0x%x" % load["offset"],
                     "vaddr": "0x%x" % load["vaddr"],
                     "alignment": "0x%x" % load["align"]}
                    for load in parsed[name]["elf"]["loads"]
                ],
            }
            for name in sorted(LIBRARIES)
        },
        "api23ImportAudit": symbol_audit,
        "buildResult": str((directory / "build-result.json").resolve()),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path, required=True,
                        help="Directory containing arm64-v8a/ and x86_64/ build outputs")
    parser.add_argument("--ndk", type=Path, required=True,
                        help="Installed Android NDK whose API-23 sysroot stubs will be audited")
    parser.add_argument("--report", type=Path,
                        help="Optional path for the machine-readable host report")
    args = parser.parse_args()
    native_root = args.native_dir.resolve()
    ndk = args.ndk.resolve()
    ndk_properties = ndk / "source.properties"
    require(ndk_properties.is_file(), "NDK source.properties not found: " + str(ndk_properties))
    prebuilt, readelf = host_prebuilt(ndk)
    vendor = validate_vendor_lock()
    report = {
        "schema": 1,
        "status": "PASS",
        "summary": {
            "status": "PASS",
            "abisPassed": ["arm64-v8a", "x86_64"],
            "nativeLibrariesPerAbi": 5,
            "api23StrongImportAudit": "PASS",
            "androidLaunchTested": False,
        },
        "scope": "host ELF and API-23 NDK stub verification only",
        "androidLaunchTested": False,
        "vendorLock": vendor,
        "readelf": str(readelf.resolve()),
        "ndkRevisionSource": str(ndk_properties.resolve()),
        "abis": [
            verify_abi(abi, native_root, ndk, prebuilt, readelf, ndk_properties)
            for abi in ("arm64-v8a", "x86_64")
        ],
    }
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8", newline="\n")
    print(rendered, end="")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print("R0 native host verification FAILED: " + str(exc), file=sys.stderr)
        sys.exit(1)
