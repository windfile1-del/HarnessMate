#!/usr/bin/env python3
"""Repack pinned Linux inputs into the production R1 asset format, without installing packages.

The overlay is an upstream-built complete tool package with its libraries and
licenses, not hand-written replacements for Git/Python/Node. This tool never
downloads inputs and never promotes a fixture-only archive by changing a flag.
"""
import argparse
import copy
import gzip
import hashlib
import json
import re
import posixpath
import tarfile
from pathlib import Path, PurePosixPath


def sha256(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            value.update(chunk)
    return value.hexdigest()


def name_of(value):
    while value.startswith('./'):
        value = value[2:]
    if value in ('', '.'):
        return ''
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or '\\' in value:
        raise ValueError('Unsafe archive path: ' + value)
    return path.as_posix()


def members(path):
    names = {}
    with tarfile.open(path, 'r:*') as archive:
        for member in archive:
            name = name_of(member.name)
            if not name:
                continue
            if name in names and not (member.isdir() and names[name].isdir()):
                raise ValueError('Duplicate input path: ' + name)
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError('Unsupported archive entry: ' + name)
            if member.islnk():
                name_of(member.linkname)
            names[name] = member
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=Path, help='pinned R0 ABI asset directory')
    parser.add_argument('--overlay-lock', required=True, type=Path,
                        help='schema1: abi, archive, sha256, source, licenseFiles')
    parser.add_argument('--version', required=True)
    parser.add_argument('--assets', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,63}', args.version):
        raise ValueError('Invalid runtime version')
    base_manifest = json.loads((args.base / 'manifest.json').read_text('utf-8'))
    lock = json.loads(args.overlay_lock.read_text('utf-8'))
    abi = base_manifest['abi']
    if abi not in ('arm64-v8a', 'x86_64') or lock.get('schema') != 1 or lock.get('abi') != abi:
        raise ValueError('Overlay ABI/schema mismatch')
    if base_manifest.get('fixtureOnly') is not True or base_manifest.get('payload') != 'rootfs.tar.gz':
        raise ValueError('Expected pinned R0 base')
    base = args.base / 'rootfs.tar.gz'
    overlay = (args.overlay_lock.parent / lock['archive']).resolve()
    if not re.fullmatch(r'[0-9a-f]{64}', lock.get('sha256', '')):
        raise ValueError('Overlay requires an exact SHA256')
    if not isinstance(lock.get('source'), str) or not lock['source'].startswith('https://'):
        raise ValueError('Overlay requires upstream source provenance')
    if sha256(base) != base_manifest['sha256'] or base.stat().st_size != base_manifest['compressedBytes']:
        raise ValueError('Base pin mismatch')
    if overlay.is_symlink() or not overlay.is_file() or sha256(overlay) != lock['sha256']:
        raise ValueError('Overlay pin mismatch')
    base_entries, overlay_entries = members(base), members(overlay)
    licenses = lock.get('licenseFiles', [])
    if not isinstance(licenses, list) or not licenses:
        raise ValueError('Overlay license file list is required')
    for license_path in licenses:
        member = overlay_entries.get(name_of(license_path))
        if member is None or not member.isfile() or member.size == 0:
            raise ValueError('Missing overlay license: ' + str(license_path))
    merged = {**base_entries, **overlay_entries}
    def resolve_guest(path, seen=None):
        seen = set() if seen is None else seen
        if path in seen or len(seen) > 40:
            raise ValueError('Cyclic runtime executable link: ' + path)
        seen.add(path)
        parts = path.split('/')
        for count in range(1, len(parts) + 1):
            prefix = '/'.join(parts[:count])
            member = merged.get(prefix)
            if member is not None and (member.issym() or member.islnk()):
                target = member.linkname
                if member.issym() and not target.startswith('/'):
                    target = posixpath.join(posixpath.dirname(prefix), target)
                target = posixpath.normpath(target.lstrip('/'))
                if target == '..' or target.startswith('../'):
                    raise ValueError('Escaping runtime executable link')
                remaining = '/'.join(parts[count:])
                return resolve_guest(posixpath.join(target, remaining) if remaining else target, seen)
        return merged.get(path)
    for executable in ('bin/sh', 'usr/bin/env', 'usr/bin/git', 'usr/bin/python3',
                       'usr/bin/node', 'opt/jdk/bin/java', 'opt/jdk/bin/javac'):
        member = resolve_guest(executable)
        if member is None or not member.isfile():
            raise ValueError('Missing real runtime executable: ' + executable)
    destination = args.assets / 'embedded/linux-runtime' / abi
    if destination.exists():
        raise FileExistsError('Refusing to replace a runtime asset version')
    destination.mkdir(parents=True)
    stage = destination / 'rootfs.tar.gz.partial'
    expanded, entries = 0, 0
    with stage.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=3) as gz:
        with tarfile.open(fileobj=gz, mode='w|', format=tarfile.PAX_FORMAT) as output:
            written = set()
            for archive_path, is_overlay in ((base, False), (overlay, True)):
                with tarfile.open(archive_path, 'r|*') as source:
                    for original in source:
                        name = name_of(original.name)
                        if any(part.startswith('._') for part in PurePosixPath(name).parts) or name.startswith('.pocket-'):
                            continue
                        if name == 'opt/vega-r0' or name.startswith('opt/vega-r0/'):
                            continue
                        if not name or name in written or (not is_overlay and name in overlay_entries):
                            continue
                        member = copy.copy(original)
                        member.name = name
                        member.uid = member.gid = member.mtime = 0
                        member.uname = member.gname = ''
                        member.mode &= 0o777
                        member.pax_headers = {}
                        if member.islnk():
                            member.linkname = name_of(member.linkname)
                        expanded += member.size if member.isfile() else 0
                        entries += 1
                        if expanded > 1024 * 1024 * 1024 or entries > 40000:
                            raise ValueError('Runtime exceeds installer budget')
                        output.addfile(member, source.extractfile(original) if original.isfile() else None)
                        written.add(name)
    if stage.stat().st_size > 512 * 1024 * 1024:
        raise ValueError('Runtime compressed budget exceeded')
    archive = destination / 'rootfs.tar.gz'
    stage.rename(archive)
    manifest = dict(base_manifest)
    manifest.update(fixtureOnly=False, nativeApi=3, minSdk=29, runtimeVersion=args.version,
                    sha256=sha256(archive), compressedBytes=archive.stat().st_size,
                    expandedBytes=expanded, entries=entries,
                    inputs=base_manifest.get('inputs', []) + [{'url': lock['source'], 'sha256': lock['sha256']}],
                    toolOverlay={'source': lock['source'], 'sha256': lock['sha256'], 'licenseFiles': licenses,
                                 'upstreamInputs': lock.get('upstreamInputs', [])})
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(destination)


if __name__ == '__main__':
    main()
