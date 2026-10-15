#!/usr/bin/env python3
"""API-23 PRoot recipe migrated from DSHA (MIT); see third_party/runtime.

Builds all dependencies from source, for ARM64 and the x86_64 acceptance ABI.
Nothing is installed on a device. Output is an APK jniLibs directory.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VENDOR = ROOT / 'third_party/runtime/vendor'
ABI = {'arm64-v8a': ('aarch64-linux-android', '0x2000000000'),
       'x86_64': ('x86_64-linux-android', '0x600000000000')}


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def fetch(path, url, expected):
    if not path.exists():
        part = path.with_suffix(path.suffix + '.part')
        failure = None
        for attempt in range(3):
            # Retry a truncated/cache-corrupt transfer with a distinct request;
            # the pinned checksum remains mandatory on every attempt.
            request_url = url if attempt == 0 else url + ('&' if '?' in url else '?') + 'vega-r0-retry=' + str(attempt)
            try:
                with urllib.request.urlopen(request_url, timeout=60) as src, part.open('wb') as dst:
                    length = src.headers.get('Content-Length')
                    received = 0
                    while block := src.read(1 << 20):
                        dst.write(block)
                        received += len(block)
                if length is not None and received != int(length):
                    raise ValueError('Truncated source download: ' + str(part))
                if digest(part) != expected:
                    raise ValueError('Source checksum mismatch: ' + str(part))
                part.replace(path)
                break
            except (OSError, ValueError) as error:
                failure = error
                if attempt == 2:
                    raise failure
    if digest(path) != expected:
        raise ValueError('Source checksum mismatch: ' + str(path))


def checked(argv):
    result = subprocess.run(list(map(str, argv)), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError(result.stderr + '\n' + result.stdout)
    return result.stdout


def build(ndk, output, work, abi):
    vendor_lock = json.loads((ROOT / 'third_party/runtime/vendor-sha256.json').read_text('utf-8'))
    expected_paths = set()
    for item in vendor_lock['files']:
        path = ROOT / 'third_party/runtime' / item['path']
        if not path.resolve().is_relative_to(VENDOR.resolve()) or path.is_symlink():
            raise ValueError('Unsafe vendored source lock path')
        expected_paths.add(path.resolve())
        if path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise ValueError('Vendored source checksum mismatch: ' + str(path))
    if expected_paths != {path.resolve() for path in VENDOR.rglob('*') if path.is_file()}:
        raise ValueError('Vendored source lock is incomplete')
    lock = json.loads((ROOT / 'third_party/runtime/source-lock.json').read_text('utf-8'))
    pin = lock['proot']
    archive = work.parent / 'proot-5.1.107.92.zip'
    fetch(archive, pin['url'], pin['sha256'])
    # Extract only regular source entries; never trust archive traversal or links.
    with zipfile.ZipFile(archive) as source_zip:
        for entry in source_zip.infolist():
            if entry.is_dir():
                continue
            parts = Path(entry.filename).parts
            if entry.filename.startswith('/') or '..' in parts or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unsafe source ZIP entry: ' + entry.filename)
            target = work / entry.filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source_zip.read(entry))
    source = work / 'proot-5.1.107.92/src'
    canon = source / 'path/canon.c'
    text = canon.read_text('utf-8')
    expected = 'assert(status != 1 || sscanf(guest_path, "/proc/%*d/fd/%d", &status) == 1);'
    if text.count(expected) != 1:
        raise ValueError('DSHA Android named-FD patch no longer matches')
    canon.write_text(text.replace(expected, '/* DSHA: Android named fd paths do not satisfy this assertion. */'), 'utf-8')
    (work / 'build.h').write_text('#define HAVE_PROCESS_VM 1\n#define HAVE_SECCOMP_FILTER 1\n', 'utf-8')
    (work / 'android23.h').write_text('''#include <ifaddrs.h>
#include "shm.h"
int vega_getifaddrs(struct ifaddrs **result);
void vega_freeifaddrs(struct ifaddrs *result);
#define getifaddrs vega_getifaddrs
#define freeifaddrs vega_freeifaddrs
''', 'utf-8')
    (work / 'android23.c').write_text('''#include <ifaddrs.h>
#include <dlfcn.h>
#include <errno.h>
int vega_getifaddrs(struct ifaddrs **result) {
    int (*fn)(struct ifaddrs **) = dlsym(RTLD_DEFAULT, "getifaddrs");
    if (fn) return fn(result);
    *result = 0; errno = ENOSYS; return -1;
}
void vega_freeifaddrs(struct ifaddrs *result) {
    void (*fn)(struct ifaddrs *) = dlsym(RTLD_DEFAULT, "freeifaddrs");
    if (fn) fn(result);
}
''', 'utf-8')
    host_name = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    host = ndk / 'toolchains/llvm/prebuilt' / host_name
    ext = '.exe' if os.name == 'nt' else ''
    clang = host / ('bin/clang' + ext)
    readelf = host / ('bin/llvm-readelf' + ext)
    strip = host / ('bin/llvm-strip' + ext)
    triple, loader_address = ABI[abi]
    output.mkdir(parents=True, exist_ok=True)
    flags = [clang, '--target=' + triple + '23', '-O2', '-g0', '-fPIC', '-D_GNU_SOURCE',
             '-D_FILE_OFFSET_BITS=64', '-DARG_MAX=131072', '-DVERSION="5.1.107.92-vega-r0"',
             '-DPROOT_UNBUNDLE_LOADER="/unused"', '-DWITH_LIBANDROID_SHMEM',
             '-I' + str(work), '-I' + str(source), '-I' + str(VENDOR / 'talloc'),
             '-I' + str(VENDOR / 'libandroid-shmem'), '-Wno-implicit-function-declaration']
    link = ['-Wl,-z,noexecstack,-z,max-page-size=16384']
    checked(flags + ['-shared', '-DTALLOC_BUILD_VERSION_MAJOR=2', '-DTALLOC_BUILD_VERSION_MINOR=4',
                     '-DTALLOC_BUILD_VERSION_RELEASE=3', '-D__STDC_WANT_LIB_EXT1__=1',
                     VENDOR / 'talloc/talloc.c', '-Wl,-soname,libtalloc.so', *link,
                     '-o', output / 'libtalloc.so'])
    shmem = (VENDOR / 'libandroid-shmem/shmem.c').read_text('utf-8')
    old = 'sprintf(symlink_path, ASHV_KEY_SYMLINK_PATH, key);'
    if shmem.count(old) != 1:
        raise ValueError('Shared-memory temp patch no longer matches')
    shmem = shmem.replace(old, '''const char *tmp = getenv("PROOT_TMP_DIR");
        if (!tmp || snprintf(symlink_path, sizeof(symlink_path), "%s/ashv_key_%d", tmp, key) >= sizeof(symlink_path)) {
            pthread_mutex_unlock(&mutex); errno = EINVAL; return -1;
        }''')
    (work / 'shmem.c').write_text(shmem, 'utf-8')
    checked(flags + ['-shared', '-include', 'fcntl.h', '-include', 'sys/ioctl.h', work / 'shmem.c',
                     '-llog', '-Wl,-soname,libandroidshmem.so', *link,
                     '-o', output / 'libandroidshmem.so'])
    loader = output / 'libprootloader.so'
    checked(flags + ['-ffreestanding', '-nostdlib', '-static',
                     '-Wl,--build-id=none,-Ttext=' + loader_address, *link,
                     source / 'loader/loader.c', source / 'loader/assembly.S', '-o', loader])
    symbols = checked([readelf, '-s', '--wide', loader])
    addresses = {}
    for name in ['_start', 'pokedata_workaround']:
        match = re.search(r'\s\d+:\s+([0-9a-f]+).*\s' + name + r'\s*$', symbols, re.M)
        if match:
            addresses[name] = int(match.group(1), 16)
        elif abi == 'arm64-v8a':
            raise ValueError('Missing ARM64 loader symbol: ' + name)
        else:
            addresses[name] = 0  # x86_64 has no pokedata workaround (arch.h).
    (work / 'loader-info.c').write_text('#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround = %d;\n' %
                                      (addresses['pokedata_workaround'] - addresses['_start']), 'utf-8')
    object_names = re.findall(r'([\w/-]+\.o)', (source / 'GNUmakefile').read_text('utf-8')
                             .split('OBJECTS +=', 1)[1].split('define define_from_arch', 1)[0])
    def compile_one(item):
        obj = work / item.replace('/', '_')
        checked(flags + ['-include', work / 'android23.h', '-c', source / item.replace('.o', '.c'), '-o', obj])
        return obj
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        objects = list(pool.map(compile_one, object_names))
    for extra in ['android23', 'loader-info']:
        obj = work / (extra + '.o')
        checked(flags + ['-c', work / (extra + '.c'), '-o', obj])
        objects.append(obj)
    checked(flags + ['-pie', *link, *objects, '-L' + str(output), '-ltalloc', '-landroidshmem',
                     '-ldl', '-o', output / 'libproot.so'])
    checked(flags + ['-shared', ROOT / 'third_party/runtime/native/vega_spawn.c', *link,
                     '-Wl,-soname,libvegaspawn.so', '-o', output / 'libvegaspawn.so'])
    binaries = []
    for binary in sorted(output.glob('*.so')):
        checked([strip, '--strip-unneeded', binary])
        binaries.append({'path': binary.name, 'bytes': binary.stat().st_size, 'sha256': digest(binary),
                         'elf': checked([readelf, '-h', '-l', '-d', binary])})
    report = {'schema': 1, 'abi': abi, 'minSdk': 23, 'nativeApi': 3,
              'nativeSourceSha256': digest(ROOT / 'third_party/runtime/native/vega_spawn.c'),
              'proot': pin, 'binaries': binaries,
              'ndkRevision': (ndk / 'source.properties').read_text('utf-8'),
              'deviceAcceptance': 'NOT_RUN'}
    (output / 'build-result.json').write_text(json.dumps(report, indent=2), 'utf-8')
    print(abi + ': ' + ', '.join(b['path'] for b in binaries), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--abi', choices=ABI, action='append')
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    for abi in args.abi or ABI:
        work = args.work / abi
        work.mkdir(parents=True, exist_ok=True)
        build(args.ndk.resolve(), args.output.resolve() / abi, work.resolve(), abi)


if __name__ == '__main__':
    main()
