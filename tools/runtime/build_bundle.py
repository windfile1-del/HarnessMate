#!/usr/bin/env python3
"""Build an offline R0 rootfs without root/Termux/WSL or host symlink privileges.

Stream-repack official Ubuntu Base and a complete Temurin JDK into a trusted
TAR payload. No apt execution and no downloaded compiler port are involved.
Pins are a feasibility fixture, not a production security-update policy.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
from build_native import ROOT, fetch, digest

PINS = {
    'arm64-v8a': ('arm64', 'aarch64',
                 'f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52',
                 '423416447885d9e45f96dd9e0b2c1367da5e1b0353e187cfdf9388c9820ac147'),
    'x86_64': ('amd64', 'x64',
               '60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426',
               '166774efcf0f722f2ee18eba0039de2d685b350ee14d7b69e6f83437dafd2af1')
}
PROBE = '''#!/bin/sh
set -eu
export HOME=/root PATH=/opt/jdk/bin:/usr/bin:/bin TMPDIR=/tmp
echo R0_SHELL_OK
/bin/uname -m
/opt/jdk/bin/java -Xmx256m -version
mkdir -p /tmp/vega-r0-classes
/opt/jdk/bin/javac -d /tmp/vega-r0-classes /opt/vega-r0/Child.java
/opt/jdk/bin/java -Xmx384m -cp '/opt/kotlin/lib/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jdk-home /opt/jdk -classpath /opt/kotlin/lib/kotlin-stdlib-1.9.20.jar:/tmp/vega-r0-classes -jvm-target 17 -d /tmp/vega-r0-classes /opt/vega-r0/Main.kt
/opt/jdk/bin/java -Xmx256m -cp /tmp/vega-r0-classes:/opt/kotlin/lib/kotlin-stdlib-1.9.20.jar MainKt
echo R0_COMPILE_AND_CHILD_OK
'''
CHILD = '''public final class Child {
    public static String message() { return "R0_JAVA_REFERENCE_OK"; }
}
'''
KOTLIN = '''fun main() {
    println(Child.message())
    val child = ProcessBuilder("/bin/sh", "-c", "printf R0_CHILD_STDOUT; printf R0_CHILD_STDERR >&2; exit 7").start()
    val out = child.inputStream.bufferedReader().readText()
    val err = child.errorStream.bufferedReader().readText()
    check(child.waitFor() == 7 && out == "R0_CHILD_STDOUT" && err == "R0_CHILD_STDERR")
    println(out)
    System.err.println(err)
    println("R0_KOTLIN_EXECUTED_OK")
}
'''


def safe(name):
    while name.startswith('./'):
        name = name[2:]
    if not name or name == '.':
        return ''
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name:
        raise ValueError('Unsafe source member: ' + name)
    return str(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=PINS, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    ubuntu_arch, jdk_arch, ubuntu_sha, jdk_sha = PINS[args.abi]
    ubuntu_name = 'ubuntu-base-20.04.5-base-' + ubuntu_arch + '.tar.gz'
    jdk_name = 'OpenJDK17U-jdk_' + jdk_arch + '_linux_hotspot_17.0.16_8.tar.gz'
    ubuntu_url = 'https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/' + ubuntu_name
    jdk_url = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/' + jdk_name
    for name, url, sha in [(ubuntu_name, ubuntu_url, ubuntu_sha), (jdk_name, jdk_url, jdk_sha)]:
        print('Fetching/verifying ' + name, flush=True)
        fetch(args.cache / name, url, sha)
    dest = args.assets / 'embedded/linux-r0' / args.abi
    dest.mkdir(parents=True, exist_ok=True)
    output = dest / 'rootfs.tar.gz'
    expanded = 0
    entries = 0
    compiler_inputs = []
    with output.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=3) as compressed:
        with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.PAX_FORMAT) as result:
            def add(member, stream=None):
                nonlocal expanded, entries
                member.uid = member.gid = member.mtime = 0
                member.uname = member.gname = ''
                member.mode = member.mode & 0o777
                member.pax_headers = {}
                result.addfile(member, stream)
                expanded += member.size
                entries += 1
            for archive, prefix, strip in [(ubuntu_name, '', False), (jdk_name, 'opt/jdk/', True)]:
                with tarfile.open(args.cache / archive, 'r:gz') as source:
                    for item in source:
                        name = safe(item.name)
                        if strip:
                            name = name.partition('/')[2]
                        if not name:
                            continue
                        if not (item.isfile() or item.isdir() or item.issym() or item.islnk()):
                            if name.startswith('dev/'):
                                continue  # /dev is supplied by an explicit host bind.
                            raise ValueError('Unsupported upstream TAR member: ' + name)
                        member = copy.copy(item)
                        member.name = prefix + name
                        if item.islnk():
                            target = safe(item.linkname)
                            if strip:
                                target = target.partition('/')[2]
                            member.linkname = prefix + target
                        add(member, source.extractfile(item) if item.isfile() else None)
            for dirname in ['opt/kotlin', 'opt/kotlin/lib', 'opt/vega-r0', 'tmp', 'root', 'workspace']:
                member = tarfile.TarInfo(dirname)
                member.type = tarfile.DIRTYPE
                member.mode = 0o700 if dirname in ['root', 'tmp'] else 0o755
                add(member)
            sums = {}
            for line in (ROOT / 'third_party/embedded/SHA256SUMS').read_text('utf-8').splitlines():
                if line.strip():
                    sha, path = line.split(maxsplit=1)
                    sums[path.lstrip('*')] = sha
            for jar in sorted((ROOT / 'third_party/embedded/kotlin/upstream').glob('*.jar')):
                if jar.name.endswith('-sources.jar'):
                    continue
                path = jar.relative_to(ROOT).as_posix()
                actual = digest(jar)
                if sums.get(path) != actual:
                    raise ValueError('Unpinned JVM compiler input: ' + path)
                compiler_inputs.append({'path': path, 'sha256': actual})
                member = tarfile.TarInfo('opt/kotlin/lib/' + jar.name)
                member.mode = 0o644
                member.size = jar.stat().st_size
                with jar.open('rb') as source:
                    add(member, source)
            for name, text in [('probe.sh', PROBE), ('Child.java', CHILD), ('Main.kt', KOTLIN)]:
                data = text.encode()
                member = tarfile.TarInfo('opt/vega-r0/' + name)
                member.mode = 0o755 if name.endswith('.sh') else 0o644
                member.size = len(data)
                add(member, io.BytesIO(data))
    manifest = {'schema': 1, 'abi': args.abi, 'minSdk': 23, 'jdkMajor': 17, 'fixtureOnly': True,
                'payload': 'rootfs.tar.gz', 'sha256': digest(output), 'compressedBytes': output.stat().st_size,
                'expandedBytes': expanded, 'entries': entries,
                'inputs': [{'url': ubuntu_url, 'sha256': ubuntu_sha}, {'url': jdk_url, 'sha256': jdk_sha}],
                'compilerInputs': compiler_inputs, 'networkIsolation': 'POLICY_ONLY'}
    (dest / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', 'utf-8')
    print(json.dumps({'output': str(dest), 'expandedBytes': expanded, 'entries': entries}), flush=True)


if __name__ == '__main__':
    main()
