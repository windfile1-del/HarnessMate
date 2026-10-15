#!/usr/bin/env python3
"""Tests for tools/runtime/build_extension_pack.py.

Verifies:
- Entry order: exactly first 'manifest.json' then 'tools.tar.gz'.
- Compression: ZIP_STORED (uncompressed) for both entries.
- Byte fidelity: exact raw manifest and payload bytes without rewrite.
- Family validation: 'linux-jvm', 'linux-android', and 'linux-lsp' accepted.
- Validation: rejects size and SHA256 mismatches.
- Missing files: refuses missing manifest or payload.
- Safety: does not replace existing output file on validation failure.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
import zipfile

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "runtime"))

import build_extension_pack


def create_tiny_tar_gz(filename: str = "test.txt", content: bytes = b"runtime-payload") -> bytes:
    """Produce a deterministic, tiny valid .tar.gz payload in memory."""
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=buf, mode="wb", mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w|", format=tarfile.USTAR_FORMAT) as tar:
            info = tarfile.TarInfo(name=filename)
            info.size = len(content)
            info.mtime = 0
            info.mode = 0o644
            tar.addfile(info, io.BytesIO(content))
    return buf.getvalue()


class BuildExtensionPackTests(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="vega-pack-test-")
        self.work_dir = Path(self.temp_dir)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _create_sample_payload(self, content: bytes | None = None) -> tuple[Path, bytes, str, int]:
        if content is None:
            content = create_tiny_tar_gz("hello.txt", b"Extension pack content 2026")
        payload_file = self.work_dir / "tools.tar.gz"
        payload_file.write_bytes(content)
        sha256 = hashlib.sha256(content).hexdigest()
        size = len(content)
        return payload_file, content, sha256, size

    def _create_sample_manifest(
        self,
        sha256: str,
        size: int,
        schema: int = 1,
        payload_name: str = "tools.tar.gz",
        extra_fields: dict | None = None,
    ) -> tuple[Path, bytes]:
        data = {
            "schema": schema,
            "payload": payload_name,
            "sha256": sha256,
            "compressedBytes": size,
            "expandedBytes": 1024,
            "entries": 1,
        }
        if extra_fields:
            data.update(extra_fields)
        manifest_bytes = (json.dumps(data, indent=2) + "\n").encode("utf-8")
        manifest_file = self.work_dir / "manifest.json"
        manifest_file.write_bytes(manifest_bytes)
        return manifest_file, manifest_bytes

    def test_build_pack_success_order_compression_and_contents(self):
        for family in ("linux-jvm", "linux-android", "linux-lsp"):
            with self.subTest(family=family):
                payload_file, payload_bytes, sha256, size = self._create_sample_payload()
                manifest_file, manifest_bytes = self._create_sample_manifest(sha256, size)
                output_file = self.work_dir / f"{family}.hm-pack"

                ret = build_extension_pack.build_pack(
                    family=family,
                    manifest_path=manifest_file,
                    payload_path=payload_file,
                    output_path=output_file,
                )
                self.assertEqual(ret, output_file)
                self.assertTrue(output_file.is_file())

                with zipfile.ZipFile(output_file, "r") as zf:
                    # 1. Verify exact order: first manifest.json, then tools.tar.gz
                    namelist = zf.namelist()
                    self.assertEqual(namelist, ["manifest.json", "tools.tar.gz"])

                    infolist = zf.infolist()
                    self.assertEqual(len(infolist), 2)
                    self.assertEqual(infolist[0].filename, "manifest.json")
                    self.assertEqual(infolist[1].filename, "tools.tar.gz")

                    # 2. Verify compression: ZIP_STORED (uncompressed)
                    self.assertEqual(infolist[0].compress_type, zipfile.ZIP_STORED)
                    self.assertEqual(infolist[1].compress_type, zipfile.ZIP_STORED)

                    # 3. Verify exact byte contents without rewrite
                    self.assertEqual(zf.read("manifest.json"), manifest_bytes)
                    self.assertEqual(zf.read("tools.tar.gz"), payload_bytes)

    def test_reject_unsupported_family(self):
        payload_file, _, sha256, size = self._create_sample_payload()
        manifest_file, _ = self._create_sample_manifest(sha256, size)
        output_file = self.work_dir / "out.hm-pack"

        for bad_family in ("windows", "linux-x86_64", "android", "", "jvm", "linux_jvm", "lsp", "linux_lsp"):
            with self.subTest(bad_family=bad_family):
                with self.assertRaises(ValueError) as ctx:
                    build_extension_pack.build_pack(
                        family=bad_family,
                        manifest_path=manifest_file,
                        payload_path=payload_file,
                        output_path=output_file,
                    )
                self.assertIn("family", str(ctx.exception).lower())
                self.assertFalse(output_file.exists())

    def test_refuse_missing_manifest(self):
        payload_file, _, _, _ = self._create_sample_payload()
        missing_manifest = self.work_dir / "nonexistent-manifest.json"
        output_file = self.work_dir / "out.hm-pack"

        with self.assertRaises(FileNotFoundError):
            build_extension_pack.build_pack(
                family="linux-jvm",
                manifest_path=missing_manifest,
                payload_path=payload_file,
                output_path=output_file,
            )
        self.assertFalse(output_file.exists())

    def test_refuse_missing_payload(self):
        manifest_file, _ = self._create_sample_manifest("0" * 64, 100)
        missing_payload = self.work_dir / "nonexistent-tools.tar.gz"
        output_file = self.work_dir / "out.hm-pack"

        with self.assertRaises(FileNotFoundError):
            build_extension_pack.build_pack(
                family="linux-jvm",
                manifest_path=manifest_file,
                payload_path=missing_payload,
                output_path=output_file,
            )
        self.assertFalse(output_file.exists())

    def test_reject_wrong_size(self):
        payload_file, _, sha256, size = self._create_sample_payload()
        manifest_file, _ = self._create_sample_manifest(sha256, size + 42)
        output_file = self.work_dir / "out.hm-pack"

        with self.assertRaises(ValueError) as ctx:
            build_extension_pack.build_pack(
                family="linux-jvm",
                manifest_path=manifest_file,
                payload_path=payload_file,
                output_path=output_file,
            )
        self.assertIn("size", str(ctx.exception).lower())
        self.assertFalse(output_file.exists())

    def test_reject_wrong_hash(self):
        payload_file, _, sha256, size = self._create_sample_payload()
        corrupted_sha = "f" * 64 if sha256 != "f" * 64 else "0" * 64
        manifest_file, _ = self._create_sample_manifest(corrupted_sha, size)
        output_file = self.work_dir / "out.hm-pack"

        with self.assertRaises(ValueError) as ctx:
            build_extension_pack.build_pack(
                family="linux-jvm",
                manifest_path=manifest_file,
                payload_path=payload_file,
                output_path=output_file,
            )
        self.assertIn("checksum", str(ctx.exception).lower())
        self.assertFalse(output_file.exists())

    def test_reject_invalid_manifest_schema_or_payload_name(self):
        payload_file, _, sha256, size = self._create_sample_payload()
        output_file = self.work_dir / "out.hm-pack"

        # Schema not 1
        m1, _ = self._create_sample_manifest(sha256, size, schema=2)
        with self.assertRaises(ValueError) as ctx:
            build_extension_pack.build_pack("linux-jvm", m1, payload_file, output_file)
        self.assertIn("manifest", str(ctx.exception).lower())

        # Payload not tools.tar.gz
        m2, _ = self._create_sample_manifest(sha256, size, payload_name="other.tar.gz")
        with self.assertRaises(ValueError) as ctx:
            build_extension_pack.build_pack("linux-jvm", m2, payload_file, output_file)
        self.assertIn("manifest", str(ctx.exception).lower())

        # Invalid JSON
        bad_json = self.work_dir / "bad.json"
        bad_json.write_text("{not valid json", encoding="utf-8")
        with self.assertRaises(ValueError):
            build_extension_pack.build_pack("linux-jvm", bad_json, payload_file, output_file)

    def test_dont_replace_existing_output_on_validation_failure(self):
        output_file = self.work_dir / "existing.hm-pack"
        sentinel_bytes = b"ORIGINAL_EXISTING_PACK_DO_NOT_OVERWRITE_2026"

        payload_file, _, sha256, size = self._create_sample_payload()
        valid_manifest, _ = self._create_sample_manifest(sha256, size)

        scenarios = [
            ("invalid family", lambda: build_extension_pack.build_pack("invalid-family", valid_manifest, payload_file, output_file)),
            ("missing manifest", lambda: build_extension_pack.build_pack("linux-jvm", self.work_dir / "missing.json", payload_file, output_file)),
            ("missing payload", lambda: build_extension_pack.build_pack("linux-jvm", valid_manifest, self.work_dir / "missing.tar.gz", output_file)),
            ("wrong hash", lambda: build_extension_pack.build_pack("linux-jvm", self._create_sample_manifest("0" * 64, size)[0], payload_file, output_file)),
            ("wrong size", lambda: build_extension_pack.build_pack("linux-jvm", self._create_sample_manifest(sha256, size + 100)[0], payload_file, output_file)),
            ("schema != 1", lambda: build_extension_pack.build_pack("linux-jvm", self._create_sample_manifest(sha256, size, schema=99)[0], payload_file, output_file)),
        ]

        for desc, action in scenarios:
            with self.subTest(scenario=desc):
                # Ensure existing output file is populated with sentinel bytes
                output_file.write_bytes(sentinel_bytes)

                with self.assertRaises((ValueError, FileNotFoundError)):
                    action()

                # Verify output file still exists and content is untouched
                self.assertTrue(output_file.is_file(), f"Output file disappeared during {desc}")
                self.assertEqual(
                    output_file.read_bytes(),
                    sentinel_bytes,
                    f"Existing output file was overwritten or modified during {desc}"
                )

                # Verify no partial files were left in the output directory
                partials = list(self.work_dir.glob("*.partial-*"))
                self.assertEqual(partials, [], f"Leftover partial files found after {desc}: {partials}")


if __name__ == "__main__":
    unittest.main()
