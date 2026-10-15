"""Verify/embed the built-in ADB JNI payload. No downloads, signing or device operations."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MACHINES = {'arm64-v8a': 183, 'x86_64': 62}


def inspect_elf(data, machine):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('ADB payload must be little endian ELF64')
    if struct.unpack_from('<HH', data, 16) != (3, machine):
        raise ValueError('ADB payload ABI/type mismatch')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    if entsize != 56 or count > 100 or phoff + count * entsize > len(data):
        raise ValueError('ADB ELF program headers invalid')
    loads = []
    dynamic = []
    for index in range(count):
        kind, flags, offset, vaddr, _, size, memory, alignment = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
        if offset + size > len(data):
            raise ValueError('ADB ELF segment truncated')
        if kind == 1:
            if alignment < 16384 or (vaddr - offset) % alignment:
                raise ValueError('ADB ELF does not support 16KiB pages')
            loads.append((vaddr, offset, size))
        if kind == 2:
            for pos in range(offset, offset + size, 16):
                tag, value = struct.unpack_from('<qQ', data, pos)
                if tag == 0:
                    break
                dynamic.append((tag, value))
    if not loads:
        raise ValueError('ADB ELF missing load segments')
    straddr = next((v for t, v in dynamic if t == 5), None)
    strsize = next((v for t, v in dynamic if t == 10), None)
    if straddr is None or strsize is None:
        raise ValueError('ADB ELF missing dynamic strings')
    start = next((off + straddr - va for va, off, size in loads if va <= straddr < va + size), None)
    if start is None or start + strsize > len(data):
        raise ValueError('ADB ELF invalid string table')
    strings = data[start:start + strsize]
    needed = []
    for tag, offset in dynamic:
        if tag == 1:
            if offset >= len(strings):
                raise ValueError('ADB ELF invalid dependency')
            name = strings[offset:].split(b'\x00', 1)[0].decode('ascii')
            if name not in {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so'}:
                raise ValueError('ADB external native dependency: ' + name)
            needed.append(name)
    return needed


def verify(root):
    source_lock = root / 'third_party/adb/source-lock.json'
    lock = json.loads(source_lock.read_text('utf-8'))
    third = (root / 'third_party/adb').resolve()
    notices = (third / 'NOTICE.txt').read_text('utf-8') + '\n' + (third / 'reference/NOTICE').read_text('utf-8')
    if (root / 'assets/embedded/licenses/adb-native-NOTICE.txt').read_text('utf-8') != notices:
        raise ValueError('ADB packaged license notices missing or stale')
    expected = set()
    for item in lock['files']:
        relative = Path(item['path'])
        source = third / relative
        if relative.is_absolute() or '..' in relative.parts or source.is_symlink():
            raise ValueError('ADB unsafe pinned source path')
        expected.add(relative.as_posix())
    for directory in ('reference', 'vendor'):
        if any(p.is_symlink() for p in (third / directory).rglob('*') if p.is_dir()):
            raise ValueError('ADB source directory symlinks forbidden')
    def check_source(item):
        source = third / item['path']
        data = source.read_bytes()
        if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise ValueError('ADB pinned source changed: ' + item['path'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(check_source, lock['files']))
    actual = {p.relative_to(third).as_posix() for directory in ('reference', 'vendor')
              for p in (third / directory).rglob('*') if p.is_file()}
    if actual != expected:
        raise ValueError('ADB pinned source inventory changed')
    base = root / 'third_party/adb/jniLibs'
    result = {}
    for abi, machine in MACHINES.items():
        path = base / abi / 'libvegaadb.so'
        data = path.read_bytes()
        needed = inspect_elf(data, machine)
        sha256 = hashlib.sha256(data).hexdigest()
        report = json.loads((path.parent / 'build-result.json').read_text('utf-8'))
        if report.get('abi') != abi or report.get('bytes') != len(data) or report.get('sha256') != sha256:
            raise ValueError('ADB native build manifest mismatch: ' + abi)
        if report.get('sourceLockSha256') != hashlib.sha256(source_lock.read_bytes()).hexdigest():
            raise ValueError('ADB source lock changed; rebuild native component')
        sources = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in sorted((root / 'native/adb').iterdir()) if p.is_file()}
        if report.get('nativeSources') != sources:
            raise ValueError('ADB native sources changed; rebuild native component')
        result[abi] = {'bytes': len(data), 'sha256': sha256, 'needed': needed}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify(args.root)
    if args.apk:
        with zipfile.ZipFile(args.apk, 'a', zipfile.ZIP_DEFLATED) as archive:
            names = set(archive.namelist())
            notice_name = 'assets/embedded/licenses/adb-native-NOTICE.txt'
            if archive.namelist().count(notice_name) != 1 or archive.read(notice_name) != (args.root / 'assets/embedded/licenses/adb-native-NOTICE.txt').read_bytes():
                raise ValueError('ADB packaged license notices missing or stale in APK')
            if any(f'lib/{abi}/libvegaadb.so' in names for abi in MACHINES):
                raise ValueError('Duplicate ADB JNI entry')
            for abi in MACHINES:
                name = f'lib/{abi}/libvegaadb.so'
                archive.write(args.root / 'third_party/adb/jniLibs' / abi / 'libvegaadb.so', name)
    report = json.dumps({'status': 'ADB_NATIVE_PAYLOAD_VERIFIED', 'abis': result,
                         'apk': 'NOT_PACKAGED' if not args.apk else 'JNI_EMBEDDED', 'device': 'NOT_RUN'}, indent=2)
    if args.report:
        args.report.resolve().relative_to(args.root.resolve())
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report + '\n', encoding='utf-8')
    print(report)


if __name__ == '__main__':
    main()
