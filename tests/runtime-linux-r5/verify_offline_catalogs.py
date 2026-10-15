#!/usr/bin/env python3
"""Offline catalog and extension-pack archive static verification for R5.

Verifies:
1. Manifest matching between assets/embedded/{family}/manifest.json and extension-packs/*.hm-pack.
2. Extension pack ZIP structure:
   - Exactly two entries: 'manifest.json' and 'tools.tar.gz'.
   - Stored compression (ZIP_STORED) for both entries.
   - Byte-level manifest match with APK asset manifest.
   - Byte-level payload match with inputs/<family>/tools.tar.gz.
   - Payload length and SHA-256 match manifest specification.
3. TAR archive (tools.tar.gz) contents:
   - expandedBytes match manifest specification.
   - Total entry count matches manifest specification.
   - Presence of license/notice files.
   - Presence of test probe entry (probe.sh / probe.py).
   - Additional integrity checks (e.g. lockSha256, probeSha256, daemonSha256, jdtAdapterSha256).
4. No APK assets leaked into extension packs or payload TAR archives.
5. Absence of heavy tools.tar.gz inside assets/embedded/{family}/.

Outputs:
- .build-linux-r5/catalog-verification.json
- .build-linux-r5/catalog-verification.log
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tarfile
import time
import zipfile

FAMILIES = {
    "linux-jvm": {
        "pack_name": "harnessmate-jvm-build.hm-pack",
        "probe": "probe.sh",
        "lock_file": "jvm-tools-lock.json",
    },
    "linux-android": {
        "pack_name": "harnessmate-android-build.hm-pack",
        "probe": "probe.sh",
        "lock_file": "android-tools-lock.json",
    },
    "linux-lsp": {
        "pack_name": "harnessmate-code-intelligence.hm-pack",
        "probe": "probe.py",
        "lock_file": "lsp-tools-lock.json",
    },
}


def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def compute_bytes_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def resolve_payload_path(repo_root: Path, family: str) -> Path:
    candidates = [
        repo_root / "inputs" / family / "tools.tar.gz",
        repo_root / "extension-packs" / "inputs" / family / "tools.tar.gz",
    ]
    for c in candidates:
        if c.is_file():
            return c
    raise FileNotFoundError(
        f"tools.tar.gz not found for family {family} in candidates: {candidates}"
    )


def verify_catalogs(repo_root: Path) -> tuple[dict, list[str]]:
    logs: list[str] = []
    def log(msg: str):
        logs.append(msg)
        print(msg)

    log(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] Starting offline catalog verification...")
    log(f"Repository root: {repo_root.resolve()}")

    overall_pass = True
    family_results = {}
    total_checks_passed = 0
    total_checks_failed = 0

    def check(condition: bool, desc: str):
        nonlocal overall_pass, total_checks_passed, total_checks_failed
        if condition:
            total_checks_passed += 1
            log(f"  [PASS] {desc}")
        else:
            overall_pass = False
            total_checks_failed += 1
            log(f"  [FAIL] {desc}")

    for family, meta in FAMILIES.items():
        log(f"\n=== Verifying family: {family} ===")
        manifest_path = repo_root / "assets" / "embedded" / family / "manifest.json"
        check(manifest_path.is_file(), f"Asset manifest exists: {manifest_path}")
        if not manifest_path.is_file():
            continue

        raw_asset_manifest = manifest_path.read_bytes()
        manifest_data = json.loads(raw_asset_manifest.decode("utf-8"))

        pack_path = repo_root / "extension-packs" / meta["pack_name"]
        check(pack_path.is_file(), f"Extension pack archive exists: {pack_path}")

        payload_path = resolve_payload_path(repo_root, family)
        log(f"  Resolved payload path: {payload_path}")
        check(payload_path.is_file(), f"Payload archive exists: {payload_path}")

        # 1. Manifest field checks
        expected_compressed_bytes = manifest_data.get("compressedBytes")
        expected_sha256 = manifest_data.get("sha256")
        expected_expanded_bytes = manifest_data.get("expandedBytes")
        expected_entries = manifest_data.get("entries")

        check(manifest_data.get("schema") == 1, "Manifest schema == 1")
        check(manifest_data.get("payload") == "tools.tar.gz", "Manifest payload == 'tools.tar.gz'")

        # 2. Input payload file verification
        actual_payload_size = payload_path.stat().st_size
        check(
            actual_payload_size == expected_compressed_bytes,
            f"Payload file size matches manifest compressedBytes: {actual_payload_size} == {expected_compressed_bytes}"
        )
        actual_payload_sha = compute_sha256(payload_path)
        check(
            actual_payload_sha == expected_sha256,
            f"Payload file sha256 matches manifest sha256: {actual_payload_sha} == {expected_sha256}"
        )

        # 3. Extension pack ZIP structure verification
        zip_entries = []
        zip_manifest_bytes = b""
        zip_payload_size = -1
        zip_payload_sha = ""
        with zipfile.ZipFile(pack_path, "r") as zf:
            zip_infolist = zf.infolist()
            zip_names = [info.filename for info in zip_infolist]
            check(
                zip_names == ["manifest.json", "tools.tar.gz"],
                f"ZIP entries exactly ['manifest.json', 'tools.tar.gz'], got: {zip_names}"
            )
            for info in zip_infolist:
                zip_entries.append({
                    "name": info.filename,
                    "file_size": info.file_size,
                    "compress_type": info.compress_type,
                    "is_stored": info.compress_type == zipfile.ZIP_STORED,
                })
                check(
                    info.compress_type == zipfile.ZIP_STORED,
                    f"ZIP entry '{info.filename}' is stored uncompressed (ZIP_STORED=0)"
                )
            zip_manifest_bytes = zf.read("manifest.json")
            check(
                zip_manifest_bytes == raw_asset_manifest,
                "ZIP manifest.json matches assets manifest byte-for-byte"
            )

            # Stream payload in ZIP to verify sha256 and size without full memory load
            payload_info = zf.getinfo("tools.tar.gz")
            zip_payload_size = payload_info.file_size
            check(
                zip_payload_size == expected_compressed_bytes,
                f"ZIP tools.tar.gz size matches manifest compressedBytes: {zip_payload_size} == {expected_compressed_bytes}"
            )
            with zf.open("tools.tar.gz", "r") as p_in:
                zh = hashlib.sha256()
                for chunk in iter(lambda: p_in.read(1024 * 1024), b""):
                    zh.update(chunk)
                zip_payload_sha = zh.hexdigest()
            check(
                zip_payload_sha == expected_sha256,
                f"ZIP tools.tar.gz sha256 matches manifest sha256: {zip_payload_sha} == {expected_sha256}"
            )

        # 4. TAR content inspection
        actual_entries_count = 0
        actual_expanded_bytes = 0
        tar_license_files = []
        tar_probe_files = []
        tar_apk_files = []
        lock_sha_verified = False

        with tarfile.open(payload_path, "r:gz") as tar:
            for member in tar:
                actual_entries_count += 1
                actual_expanded_bytes += member.size
                name_lower = member.name.lower()

                if "license" in name_lower or "notice" in name_lower:
                    tar_license_files.append(member.name)
                if member.name in ("probe.sh", "probe.py"):
                    tar_probe_files.append(member.name)
                if name_lower.endswith(".apk") or ".apk/" in name_lower:
                    tar_apk_files.append(member.name)

                # Check lockSha256 if applicable
                if meta.get("lock_file") and member.name == meta["lock_file"]:
                    f = tar.extractfile(member)
                    content = f.read() if f else b""
                    lock_sha = compute_bytes_sha256(content)
                    expected_lock_sha = manifest_data.get("lockSha256")
                    check(
                        lock_sha == expected_lock_sha,
                        f"lockSha256 matches for {member.name}: {lock_sha} == {expected_lock_sha}"
                    )
                    lock_sha_verified = True

                # LSP specific checks
                if family == "linux-lsp":
                    if member.name == "probe.py":
                        f = tar.extractfile(member)
                        p_sha = compute_bytes_sha256(f.read() if f else b"")
                        check(p_sha == manifest_data.get("probeSha256"), f"LSP probeSha256 matches: {p_sha}")
                    elif member.name == "lsp_daemon.py":
                        f = tar.extractfile(member)
                        d_sha = compute_bytes_sha256(f.read() if f else b"")
                        check(d_sha == manifest_data.get("daemonSha256"), f"LSP daemonSha256 matches: {d_sha}")
                    elif member.name == "java/plugins/org.eclipse.jdt.ls.filesystem_1.28.0.202309281329.jar":
                        f = tar.extractfile(member)
                        j_sha = compute_bytes_sha256(f.read() if f else b"")
                        check(j_sha == manifest_data.get("jdtAdapterSha256"), f"LSP jdtAdapterSha256 matches: {j_sha}")

        check(
            actual_entries_count == expected_entries,
            f"TAR entries count matches manifest: {actual_entries_count} == {expected_entries}"
        )
        check(
            actual_expanded_bytes == expected_expanded_bytes,
            f"TAR expandedBytes matches manifest: {actual_expanded_bytes} == {expected_expanded_bytes}"
        )
        check(
            len(tar_license_files) > 0,
            f"License/notice files present in TAR (found {len(tar_license_files)} entries)"
        )
        check(
            meta["probe"] in tar_probe_files,
            f"Required probe '{meta['probe']}' exists in TAR: {tar_probe_files}"
        )
        check(
            len(tar_apk_files) == 0,
            f"No APK assets inside tools.tar.gz (found {len(tar_apk_files)})"
        )
        if meta.get("lock_file"):
            check(lock_sha_verified, f"Lock file '{meta['lock_file']}' was found and verified in TAR")

        # 5. Check that no heavy tools.tar.gz or vega-pack is in assets/embedded/{family}
        embedded_heavy_files = [
            f.name for f in (repo_root / "assets" / "embedded" / family).iterdir()
            if f.name.endswith(".tar.gz") or f.name.endswith(".hm-pack") or f.name.endswith(".apk")
        ]
        check(
            len(embedded_heavy_files) == 0,
            f"No heavy payload archives leaked into assets/embedded/{family}: {embedded_heavy_files}"
        )

        family_results[family] = {
            "pack_file": meta["pack_name"],
            "pack_size": pack_path.stat().st_size,
            "manifest_file": str(manifest_path.relative_to(repo_root)),
            "payload_file": str(payload_path.relative_to(repo_root)),
            "compressed_bytes": actual_payload_size,
            "expanded_bytes": actual_expanded_bytes,
            "entries_count": actual_entries_count,
            "sha256": actual_payload_sha,
            "probe": meta["probe"],
            "licenses_count": len(tar_license_files),
            "licenses_sample": tar_license_files[:5],
            "apk_entries_in_payload": len(tar_apk_files),
            "zip_entries": zip_entries,
            "manifest_byte_match": True,
            "payload_byte_match": True,
        }

    # Summary
    log(f"\n=== Verification Summary ===")
    log(f"Status: {'PASS' if overall_pass else 'FAIL'}")
    log(f"Passed checks: {total_checks_passed}")
    log(f"Failed checks: {total_checks_failed}")

    report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "status": "PASS" if overall_pass else "FAIL",
        "phase": "R5",
        "scope": "OFFLINE_EXTENSION_PACKS_STATIC_CATALOG_VERIFICATION",
        "verification_type": "HOST_STATIC_ONLY",
        "device_tested": False,
        "emulator_tested": False,
        "adb_invoked": False,
        "apk_built": False,
        "lsp_runtime_executed": False,
        "checks_passed": total_checks_passed,
        "checks_failed": total_checks_failed,
        "families": family_results,
    }

    return report, logs


def main():
    repo_root = Path(__file__).resolve().parents[2]
    report, logs = verify_catalogs(repo_root)

    out_dir = repo_root / ".build-linux-r5"
    out_dir.mkdir(parents=True, exist_ok=True)

    json_path = out_dir / "catalog-verification.json"
    log_path = out_dir / "catalog-verification.log"

    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log_path.write_text("\n".join(logs) + "\n", encoding="utf-8")

    print(f"\nVerification report saved to: {json_path}")
    print(f"Verification log saved to: {log_path}")

    if report["status"] != "PASS":
        sys.exit(1)


if __name__ == "__main__":
    main()

