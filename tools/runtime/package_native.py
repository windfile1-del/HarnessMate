#!/usr/bin/env python3
"""Add verified optional R0 native executables to an unsigned APK.

Absent inputs are a no-op only when no R0 bundle is selected. A selected
bundle requires matching verified native libraries, never an assets exec.
"""
import argparse
import json
from pathlib import Path
import zipfile
from build_native import digest
from android_native import verified_android

LIBRARIES = {'libproot.so', 'libprootloader.so', 'libtalloc.so', 'libandroidshmem.so', 'libvegaspawn.so'}
ABIS = {'arm64-v8a', 'x86_64'}


def verified_libraries(native):
    files = []
    for directory in sorted(native.iterdir()):
        if not directory.is_dir():
            continue
        if directory.name not in ABIS:
            raise ValueError('Unvalidated R0 native ABI: ' + directory.name)
        report = json.loads((directory / 'build-result.json').read_text('utf-8'))
        if report.get('abi') != directory.name or report.get('minSdk') != 23:
            raise ValueError('R0 native report ABI/API mismatch')
        pins = {item['path']: item for item in report['binaries']}
        if set(pins) != LIBRARIES or {p.name for p in directory.glob('*.so')} != LIBRARIES:
            raise ValueError('Incomplete/extra R0 native library set')
        for name in sorted(LIBRARIES):
            file = directory / name
            if file.stat().st_size != pins[name]['bytes'] or digest(file) != pins[name]['sha256']:
                raise ValueError('R0 native library checksum mismatch: ' + str(file))
            files.append((file, 'lib/' + directory.name + '/' + name))
    if not files:
        raise ValueError('Empty R0 native input directory')
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--native', required=True, type=Path)
    parser.add_argument('--assets', required=True, type=Path)
    parser.add_argument('--android-native', type=Path, default=Path(__file__).resolve().parents[2] / 'third_party/runtime/android-jniLibs')
    args = parser.parse_args()
    if not args.check_only and not args.apk:
        parser.error('--apk is required unless --check-only is selected')
    families = [(args.assets / 'embedded/linux-r0', True),
                (args.assets / 'embedded/linux-runtime', False)]
    selected = set()
    for bundles, _ in families:
        if bundles.is_dir():
            selected.update(p.name for p in bundles.iterdir() if p.is_dir())
    if not args.native.is_dir():
        if selected:
            raise ValueError('Linux assets selected without the APK native runtime')
        print('No Linux native input selected')
        return
    files = verified_libraries(args.native)
    jvm = args.assets / 'embedded/linux-jvm'
    if jvm.exists():
        manifest = json.loads((jvm / 'manifest.json').read_text('utf-8'))
        if (manifest.get('schema') != 1 or manifest.get('payload') != 'tools.tar.gz'
                or manifest.get('gradleVersion') != '8.5' or manifest.get('kotlinVersion') != '2.0.21'
                or manifest.get('jdkMajor') != 17 or not 0 < manifest.get('expandedBytes', 0) <= 512 * 1024 * 1024
                or not 0 < manifest.get('entries', 0) <= 10000):
            raise ValueError('Invalid JVM bundle manifest')
        payload = jvm / 'tools.tar.gz'
        if payload.exists():
            raise ValueError('JVM extension payload must not be bundled in APK assets')
    lsp = args.assets / 'embedded/linux-lsp'
    if lsp.exists():
        metadata = json.loads((lsp / 'manifest.json').read_text('utf-8'))
        if (metadata.get('schema') != 1 or metadata.get('payload') != 'tools.tar.gz'
                or metadata.get('jdkMajor') != 17 or not 0 < metadata.get('expandedBytes', 0) <= 1024 * 1024 * 1024
                or not 0 < metadata.get('entries', 0) <= 20000
                or set(metadata.get('languages', [])) != {'java', 'kotlin', 'python', 'typescript', 'javascript'}):
            raise ValueError('Invalid LSP extension catalog')
        if (lsp / 'tools.tar.gz').exists():
            raise ValueError('LSP extension payload must not be bundled in APK assets')
    available = {file.parent.name for file, _ in files}
    android = args.assets / 'embedded/linux-android'
    if android.exists():
        if 'arm64-v8a' not in available:
            raise ValueError('R3 Android asset requires the ARM64 Linux native runtime')
        if (android / 'tools.tar.gz').exists():
            raise ValueError('Android extension payload must not be bundled in APK assets')
        files.extend(verified_android(args.android_native, android, require_payload=False))
    if not selected.issubset(available) or not selected.issubset(ABIS):
        raise ValueError('Linux bundle ABI has no validated native runtime')
    for bundles, fixture in families:
        if not bundles.is_dir():
            continue
        for abi in sorted(p.name for p in bundles.iterdir() if p.is_dir()):
            manifest = json.loads((bundles / abi / 'manifest.json').read_text('utf-8'))
            if (manifest.get('abi') != abi or manifest.get('schema') != 1
                    or manifest.get('payload') != 'rootfs.tar.gz' or manifest.get('fixtureOnly') is not fixture):
                raise ValueError('Invalid Linux bundle manifest')
            if not fixture and (manifest.get('nativeApi') != 3 or manifest.get('minSdk') != 29):
                raise ValueError('Production Linux bundle requires guardian API3 / Android10+')
            if not fixture:
                native_report = json.loads((args.native / abi / 'build-result.json').read_text('utf-8'))
                source = Path(__file__).resolve().parents[2] / 'third_party/runtime/native/vega_spawn.c'
                if native_report.get('nativeApi') != 3 or native_report.get('nativeSourceSha256') != digest(source):
                    raise ValueError('Production native source/API does not match its build')
                if not 0 < manifest.get('expandedBytes', 0) <= 1024 * 1024 * 1024 or not 0 < manifest.get('entries', 0) <= 40000:
                    raise ValueError('Production rootfs quota exceeds installer budget')
            payload = bundles / abi / 'rootfs.tar.gz'
            if payload.stat().st_size != manifest['compressedBytes'] or digest(payload) != manifest['sha256']:
                raise ValueError('Linux bundle checksum mismatch')
    if args.check_only:
        print('Verified R0 inputs: ' + ', '.join(sorted(available)))
        return
    with zipfile.ZipFile(args.apk, 'a') as archive:
        existing = set(archive.namelist())
        if any(name in existing for _, name in files):
            raise ValueError('Refusing to replace existing native APK entries')
        for file, name in files:
            # extractNativeLibs=true: PackageManager supplies executable paths.
            archive.write(file, name, compress_type=zipfile.ZIP_DEFLATED)
    print('Packaged R0 native ABIs: ' + ', '.join(sorted(available)))


if __name__ == '__main__':
    main()
