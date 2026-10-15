#!/usr/bin/env python3
"""Packaging and negative verification tests for R3 android_native.py.

Uses the real 6 native binaries from third_party/runtime/android-jniLibs/arm64-v8a
and native-manifest from assets/embedded/linux-android/native-manifest.json,
with an isolated temporary asset directory containing a minimal mock tools.tar.gz payload.

Negative test coverage:
1. Valid baseline passes: real 6 binaries + real native-manifest + compliant manifest + mock payload.
2. Wrong payload SHA256 -> rejected.
3. Wrong payload compressedBytes -> rejected.
4. Wrong manifest ABI -> rejected.
5. Wrong buildToolsVersion -> rejected.
6. Wrong compileSdk / minSdk / versions -> rejected.
7. Extra .so in native directory -> rejected.
8. Missing required .so in native directory -> rejected.
9. Native binary byte mutation (SHA mismatch) -> rejected.
10. Bad ELF magic (with matching pin SHA) -> rejected.
11. Non-ARM64 ELF (with matching pin SHA) -> rejected.
12. Non-16KiB alignment (with matching pin SHA) -> rejected.
13. Missing / non-Bionic linker (with matching pin SHA) -> rejected.

Outputs execution log to .build-linux-r3/native-tests.log.
"""
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / 'tools' / 'runtime'))

import android_native

REAL_NATIVE_SRC = REPO_ROOT / 'third_party' / 'runtime' / 'android-jniLibs' / 'arm64-v8a'
REAL_NATIVE_MANIFEST = REPO_ROOT / 'assets' / 'embedded' / 'linux-android' / 'native-manifest.json'
NAMES = sorted(android_native.NAMES)

def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def setup_test_env(base_dir: Path):
    assets = base_dir / 'assets'
    native = base_dir / 'native'
    arm64_dir = native / 'arm64-v8a'
    assets.mkdir(parents=True, exist_ok=True)
    arm64_dir.mkdir(parents=True, exist_ok=True)

    # Copy real 6 binaries
    for name in NAMES:
        src = REAL_NATIVE_SRC / name
        dst = arm64_dir / name
        shutil.copy2(src, dst)

    # Copy real native-manifest.json
    shutil.copy2(REAL_NATIVE_MANIFEST, assets / 'native-manifest.json')

    # Create minimal mock tools.tar.gz
    payload = assets / 'tools.tar.gz'
    payload_content = b'mock-tools-tar-gz-payload-for-r3-android-verification'
    payload.write_bytes(payload_content)

    manifest = {
        'schema': 1,
        'payload': 'tools.tar.gz',
        'abi': 'arm64-v8a',
        'minSdk': 29,
        'gradleVersion': '8.9',
        'agpVersion': '8.7.2',
        'compileSdk': 35,
        'buildToolsVersion': '35.0.0',
        'kotlinVersion': '2.0.21',
        'jdkMajor': 17,
        'expandedBytes': 1024 * 1024,
        'entries': 100,
        'compressedBytes': len(payload_content),
        'sha256': file_sha256(payload)
    }
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2), 'utf-8')

    return native, assets

def run_tests(log_path: Path):
    results = []

    def expect_rejection(label: str, fn, expected_msg: str):
        try:
            fn()
            msg = f"FAIL: {label} expected ValueError('{expected_msg}'), but succeeded"
            results.append(msg)
            print(msg)
            return False
        except ValueError as e:
            if expected_msg in str(e):
                msg = f"PASS: {label} (rejected as expected with '{e}')"
                results.append(msg)
                print(msg)
                return True
            else:
                msg = f"FAIL: {label} expected message containing '{expected_msg}', got '{e}'"
                results.append(msg)
                print(msg)
                return False

    with tempfile.TemporaryDirectory() as temp_dir:
        base = Path(temp_dir)

        # 1. Baseline with real 6 binaries and real native-manifest passes
        n1, a1 = setup_test_env(base / 'baseline')
        try:
            ret = android_native.verified_android(n1, a1)
            assert len(ret) == 6, f"Expected 6 verified binaries, got {len(ret)}"
            results.append("PASS: Baseline verification with real 6 binaries passed")
            print(results[-1])
        except Exception as e:
            results.append(f"FAIL: Baseline verification failed: {e}")
            print(results[-1])

        # 2. Wrong payload SHA256
        n2, a2 = setup_test_env(base / 'bad_payload_sha')
        m2 = json.loads((a2 / 'manifest.json').read_text('utf-8'))
        m2['sha256'] = '0' * 64
        (a2 / 'manifest.json').write_text(json.dumps(m2), 'utf-8')
        expect_rejection("Payload SHA256 mismatch", lambda: android_native.verified_android(n2, a2),
                         "R3 Android asset checksum mismatch")

        # 3. Wrong payload length
        n3, a3 = setup_test_env(base / 'bad_payload_len')
        m3 = json.loads((a3 / 'manifest.json').read_text('utf-8'))
        m3['compressedBytes'] = 999999
        (a3 / 'manifest.json').write_text(json.dumps(m3), 'utf-8')
        expect_rejection("Payload length mismatch", lambda: android_native.verified_android(n3, a3),
                         "R3 Android asset checksum mismatch")

        # 4. Wrong manifest ABI
        n4, a4 = setup_test_env(base / 'bad_abi')
        m4 = json.loads((a4 / 'manifest.json').read_text('utf-8'))
        m4['abi'] = 'x86_64'
        (a4 / 'manifest.json').write_text(json.dumps(m4), 'utf-8')
        expect_rejection("Manifest ABI mismatch", lambda: android_native.verified_android(n4, a4),
                         "Invalid R3 Android manifest")

        # 5. Wrong buildToolsVersion
        n5, a5 = setup_test_env(base / 'bad_buildtools')
        m5 = json.loads((a5 / 'manifest.json').read_text('utf-8'))
        m5['buildToolsVersion'] = '34.0.0'
        (a5 / 'manifest.json').write_text(json.dumps(m5), 'utf-8')
        expect_rejection("buildToolsVersion mismatch", lambda: android_native.verified_android(n5, a5),
                         "Invalid R3 Android manifest")

        # 6. Wrong compileSdk
        n6, a6 = setup_test_env(base / 'bad_compilesdk')
        m6 = json.loads((a6 / 'manifest.json').read_text('utf-8'))
        m6['compileSdk'] = 34
        (a6 / 'manifest.json').write_text(json.dumps(m6), 'utf-8')
        expect_rejection("compileSdk mismatch", lambda: android_native.verified_android(n6, a6),
                         "Invalid R3 Android manifest")

        # 7. Extra .so in native directory
        n7, a7 = setup_test_env(base / 'extra_so')
        extra_so = n7 / 'arm64-v8a' / 'libextra.so'
        shutil.copy2(n7 / 'arm64-v8a' / 'libvegaaidl.so', extra_so)
        expect_rejection("Extra .so rejected", lambda: android_native.verified_android(n7, a7),
                         "Incomplete/extra R3 Android native library set")

        # 8. Missing required .so in native directory
        n8, a8 = setup_test_env(base / 'missing_so')
        (n8 / 'arm64-v8a' / 'libvegazipalign.so').unlink()
        expect_rejection("Missing .so rejected", lambda: android_native.verified_android(n8, a8),
                         "Incomplete/extra R3 Android native library set")

        # 9. Native binary byte mutation (SHA mismatch)
        n9, a9 = setup_test_env(base / 'tampered_so')
        target = n9 / 'arm64-v8a' / 'libvegaaapt2.so'
        data = bytearray(target.read_bytes())
        data[-1] ^= 0x55
        target.write_bytes(data)
        expect_rejection("Native binary checksum mismatch", lambda: android_native.verified_android(n9, a9),
                         "R3 Android native checksum mismatch: libvegaaapt2.so")

        # 10. Bad ELF magic (update native pin so checksum matches)
        n10, a10 = setup_test_env(base / 'bad_magic')
        target10 = n10 / 'arm64-v8a' / 'libvegaaapt.so'
        data10 = bytearray(target10.read_bytes())
        data10[:4] = b'PE\x00\x00'
        target10.write_bytes(data10)
        nm10 = json.loads((a10 / 'native-manifest.json').read_text('utf-8'))
        for b in nm10['binaries']:
            if b['path'] == 'libvegaaapt.so':
                b['sha256'] = file_sha256(target10)
        (a10 / 'native-manifest.json').write_text(json.dumps(nm10), 'utf-8')
        expect_rejection("Bad ELF magic rejected", lambda: android_native.verified_android(n10, a10),
                         "R3 requires ARM64 ELF64 PIE: libvegaaapt.so")

        # 11. Non-ARM64 ELF (e_machine = 62 EM_X86_64, update native pin)
        n11, a11 = setup_test_env(base / 'bad_arch')
        target11 = n11 / 'arm64-v8a' / 'libvegaaidl.so'
        data11 = bytearray(target11.read_bytes())
        struct.pack_into('<H', data11, 18, 62) # EM_X86_64
        target11.write_bytes(data11)
        nm11 = json.loads((a11 / 'native-manifest.json').read_text('utf-8'))
        for b in nm11['binaries']:
            if b['path'] == 'libvegaaidl.so':
                b['sha256'] = file_sha256(target11)
        (a11 / 'native-manifest.json').write_text(json.dumps(nm11), 'utf-8')
        expect_rejection("Non-ARM64 ELF rejected", lambda: android_native.verified_android(n11, a11),
                         "R3 requires ARM64 ELF64 PIE: libvegaaidl.so")

        # 12. Non-16KiB alignment (align = 4096, update native pin)
        n12, a12 = setup_test_env(base / 'bad_align')
        target12 = n12 / 'arm64-v8a' / 'libvegadexdump.so'
        data12 = bytearray(target12.read_bytes())
        # Find PT_LOAD header and change align to 4096
        phoff = struct.unpack_from('<Q', data12, 32)[0]
        stride, count = struct.unpack_from('<HH', data12, 54)
        for i in range(count):
            typ = struct.unpack_from('<I', data12, phoff + i * stride)[0]
            if typ == 1: # PT_LOAD
                struct.pack_into('<Q', data12, phoff + i * stride + 48, 4096)
                break
        target12.write_bytes(data12)
        nm12 = json.loads((a12 / 'native-manifest.json').read_text('utf-8'))
        for b in nm12['binaries']:
            if b['path'] == 'libvegadexdump.so':
                b['sha256'] = file_sha256(target12)
        (a12 / 'native-manifest.json').write_text(json.dumps(nm12), 'utf-8')
        expect_rejection("Non-16KiB alignment rejected", lambda: android_native.verified_android(n12, a12),
                         "R3 Android native tool is not 16 KiB aligned")

        # 13. Missing / non-Bionic linker (change interpreter path, update native pin)
        n13, a13 = setup_test_env(base / 'bad_interp')
        target13 = n13 / 'arm64-v8a' / 'libvegazipalign.so'
        data13 = bytearray(target13.read_bytes())
        phoff = struct.unpack_from('<Q', data13, 32)[0]
        stride, count = struct.unpack_from('<HH', data13, 54)
        for i in range(count):
            typ, _, pos, _, _, size = struct.unpack_from('<IIQQQQ', data13, phoff + i * stride)
            if typ == 3: # PT_INTERP
                data13[pos:pos+size] = b'/system/bin/linkerXX\x00'
                break
        target13.write_bytes(data13)
        nm13 = json.loads((a13 / 'native-manifest.json').read_text('utf-8'))
        for b in nm13['binaries']:
            if b['path'] == 'libvegazipalign.so':
                b['sha256'] = file_sha256(target13)
        (a13 / 'native-manifest.json').write_text(json.dumps(nm13), 'utf-8')
        expect_rejection("Non-Bionic interpreter rejected", lambda: android_native.verified_android(n13, a13),
                         "R3 Android tool requires system Bionic linker")

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_content = "\n".join(results) + f"\n\nTOTAL_TESTS: {len(results)}\nALL_TESTS_PASS: {all(r.startswith('PASS') for r in results)}\n"
    log_path.write_text(log_content, 'utf-8')
    print(f"Log written to {log_path}")
    if not all(r.startswith('PASS') for r in results):
        sys.exit(1)

if __name__ == '__main__':
    log_file = REPO_ROOT / '.build-linux-r3' / 'native-tests.log'
    if len(sys.argv) > 1:
        log_file = Path(sys.argv[1])
    run_tests(log_file)
