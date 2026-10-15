#!/usr/bin/env python3
"""Compose pinned Mobile Harness ARM64 core/Python without executing guest tools.

Requires the host-only zstandard module. All guest libraries, package metadata,
and redistribution notices stay in the archive; no user homes are imported.
"""
import argparse
import copy
import gzip
import json
import tarfile
from pathlib import Path
import zstandard
from build_runtime_bundle import name_of, sha256

PIN = 'f0ba6730b1522ce3225cf84089305d6d1e7df905'
ROOT = 'https://github.com/techjarves/Mobile-Harness'
INPUTS = {
    'core': 'df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012',
    'python': '6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85',
}


def opened(path):
    return tarfile.open(fileobj=zstandard.ZstdDecompressor().stream_reader(path.open('rb')), mode='r|')


def keep(name):
    return name not in ('root', 'home', 'tmp', 'run') and not any(
        name.startswith(prefix) for prefix in ('root/', 'home/', 'tmp/', 'run/', 'var/log/',
                                             'var/cache/', 'etc/ssh/', 'opt/pocketdev/'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    paths = {key: args.inputs / (key + '.tar.zst') for key in INPUTS}
    inventories = {}
    for key, path in paths.items():
        if sha256(path) != INPUTS[key]:
            raise ValueError('Upstream pin mismatch: ' + key)
        with opened(path) as source:
            inventory = {}
            for item in source:
                name = name_of(item.name)
                if not name or not keep(name):
                    continue
                if not (item.isfile() or item.isdir() or item.issym() or item.islnk()):
                    if name.startswith('dev/'):
                        continue
                    raise ValueError('Unsupported upstream entry: ' + name)
                if name in inventory and not (item.isdir() and inventory[name].isdir()):
                    raise ValueError('Duplicate upstream path: ' + name)
                inventory[name] = item
            inventories[key] = inventory
        print(key, len(inventory), sum(m.size for m in inventory.values() if m.isfile()), flush=True)
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / 'mobile-tools.tar.gz'
    if archive.exists():
        raise FileExistsError(archive)
    partial = archive.with_suffix('.gz.partial')
    with partial.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=3) as gz:
        with tarfile.open(fileobj=gz, mode='w|', format=tarfile.PAX_FORMAT) as output:
            written = set()
            for key, path in paths.items():
                with opened(path) as source:
                    for original in source:
                        name = name_of(original.name)
                        if name not in inventories[key] or name in written:
                            continue
                        if key == 'core' and name in inventories['python']:
                            continue
                        item = copy.copy(original)
                        item.name = name
                        item.uid = item.gid = item.mtime = 0
                        item.uname = item.gname = ''
                        item.mode &= 0o777
                        item.pax_headers = {}
                        if item.islnk():
                            item.linkname = name_of(item.linkname)
                        output.addfile(item, source.extractfile(original) if item.isfile() else None)
                        written.add(name)
            # Upstream Node lives under /usr/local. A guest-relative alias keeps
            # the existing runtime executable contract and cleared PATH stable.
            if 'usr/bin/node' not in written:
                node = tarfile.TarInfo('usr/bin/node')
                node.type = tarfile.SYMTYPE
                node.linkname = '../local/lib/nodejs/bin/node'
                node.mode = 0o777
                output.addfile(node)
    partial.rename(archive)
    licenses = ['usr/local/lib/nodejs/LICENSE', 'usr/share/doc/git/copyright',
                'usr/lib/python3.8/LICENSE.txt']
    lock = dict(schema=1, abi='arm64-v8a', archive=archive.name, sha256=sha256(archive),
                source=ROOT + '/tree/' + PIN, licenseFiles=licenses,
                upstreamInputs=[dict(bundle=key, sha256=value) for key, value in INPUTS.items()])
    (args.output / 'overlay-lock.json').write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    print(archive, flush=True)


if __name__ == '__main__':
    main()
