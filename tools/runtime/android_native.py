"""Validate the separate upstream ARM64 Android build tools before packaging."""
import json
import struct
from pathlib import Path
from build_native import digest

NAMES = {'libvegaaapt2.so', 'libvegaaidl.so', 'libvegazipalign.so', 'libvegaaapt.so', 'libvegadexdump.so', 'libvegasplit-select.so'}

def verified_android(native, assets, require_payload=True):
    manifest = json.loads((assets / 'manifest.json').read_text('utf-8'))
    if (manifest.get('schema') != 1 or manifest.get('payload') != 'tools.tar.gz'
            or manifest.get('abi') != 'arm64-v8a' or manifest.get('minSdk') != 29
            or manifest.get('gradleVersion') != '8.9' or manifest.get('agpVersion') != '8.7.2'
            or manifest.get('compileSdk') != 35 or manifest.get('buildToolsVersion') != '35.0.0'
            or manifest.get('kotlinVersion') != '2.0.21' or manifest.get('jdkMajor') != 17
            or not 0 < manifest.get('expandedBytes', 0) <= 1024 * 1024 * 1024
            or not 0 < manifest.get('entries', 0) <= 20000):
        raise ValueError('Invalid R3 Android manifest')
    payload = assets / 'tools.tar.gz'
    if require_payload and (payload.stat().st_size != manifest['compressedBytes'] or digest(payload) != manifest['sha256']):
        raise ValueError('R3 Android asset checksum mismatch')
    report = json.loads((assets / 'native-manifest.json').read_text('utf-8'))
    if report.get('schema') != 1 or report.get('abi') != 'arm64-v8a' or report.get('minSdk') != 29:
        raise ValueError('Invalid R3 Android native manifest')
    pins = {item['path']: item for item in report['binaries']}
    directory = native / 'arm64-v8a'
    if len(report['binaries']) != len(NAMES) or set(pins) != NAMES or {p.name for p in directory.glob('*.so')} != NAMES:
        raise ValueError('Incomplete/extra R3 Android native library set')
    results = []
    for name in sorted(NAMES):
        file = directory / name
        if file.stat().st_size != pins[name]['bytes'] or digest(file) != pins[name]['sha256']:
            raise ValueError('R3 Android native checksum mismatch: ' + name)
        data = file.read_bytes()
        if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', data, 16) != (3, 183):
            raise ValueError('R3 requires ARM64 ELF64 PIE: ' + name)
        offset = struct.unpack_from('<Q', data, 32)[0]
        stride, count = struct.unpack_from('<HH', data, 54)
        if stride < 56 or count > 64 or offset + stride * count > len(data):
            raise ValueError('Invalid ELF program headers')
        interpreter = False
        loads = 0
        for index in range(count):
            typ, flags, pos, vaddr, _, size, _, align = struct.unpack_from('<IIQQQQQQ', data, offset + index * stride)
            if typ == 1:
                loads += 1
                if align < 16384 or (vaddr - pos) % 16384:
                    raise ValueError('R3 Android native tool is not 16 KiB aligned')
            if typ == 3:
                interpreter = data[pos:pos + size] == b'/system/bin/linker64\0'
        if not interpreter or not loads:
            raise ValueError('R3 Android tool requires system Bionic linker')
        results.append((file, 'lib/arm64-v8a/' + name))
    return results
