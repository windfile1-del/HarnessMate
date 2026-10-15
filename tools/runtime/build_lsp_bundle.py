#!/usr/bin/env python3
"""Build the external R4 LSP pack from fixed upstream releases, never on a phone."""
import argparse
import base64
import gzip
import hashlib
import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from build_extension_pack import build_pack

ROOT = Path(__file__).resolve().parents[2]
INPUTS = {
    'kotlin': ('1.3.13', 'https://github.com/fwcd/kotlin-language-server/releases/download/1.3.13/server.zip'),
    'java': ('1.28.0', 'https://download.eclipse.org/jdtls/milestones/1.28.0/jdt-language-server-1.28.0-202309281329.tar.gz'),
    'python': ('1.1.390', 'https://registry.npmjs.org/pyright/-/pyright-1.1.390.tgz'),
    'typescript-server': ('4.3.3', 'https://registry.npmjs.org/typescript-language-server/-/typescript-language-server-4.3.3.tgz'),
    'typescript': ('5.6.3', 'https://registry.npmjs.org/typescript/-/typescript-5.6.3.tgz'),
}
NOTICES = {
    'kotlin-server-license': 'https://raw.githubusercontent.com/fwcd/kotlin-language-server/1.3.13/LICENSE.txt',
    'jdt-server-license': 'https://raw.githubusercontent.com/eclipse-jdtls/eclipse.jdt.ls/v1.28.0/LICENSE',
}

def adapt_jdt(tree, work, javac):
    """Reuse JDT's own metadata filesystem with existing-file copy-on-write."""
    source_root = ROOT / 'third_party/runtime/jdt-metadata-cow'
    plugins = tree / 'java/plugins'
    original = next(plugins.glob('org.eclipse.jdt.ls.filesystem_*.jar'))
    sources = sorted((source_root / 'src').rglob('*.java'))
    with tempfile.TemporaryDirectory(prefix='jdt-adapter-', dir=work) as tmp:
        output = Path(tmp)
        subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-classpath',
            os.pathsep.join(str(p) for p in plugins.glob('*.jar')), '-d', str(output)]
            + [str(p) for p in sources], check=True)
        patched = output / 'filesystem.jar'
        classes = {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*.class')}
        with zipfile.ZipFile(original) as upstream, zipfile.ZipFile(patched, 'w', zipfile.ZIP_DEFLATED) as target:
            for entry in upstream.infolist():
                if entry.filename in classes or entry.filename.upper().endswith(('.SF', '.RSA', '.DSA')):
                    continue
                target.writestr(entry, upstream.read(entry))
            for name, data in sorted(classes.items()):
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(entry, data)
        shutil.copyfile(patched, original)
    # EPL source and exact untouched upstream file travel with the modified bundle.
    shutil.copytree(source_root, tree / 'adaptations/jdt-metadata-cow', dirs_exist_ok=True)
    return hashlib.sha256(original.read_bytes()).hexdigest()

def extract(data, target, is_zip):
    target.mkdir(parents=True, exist_ok=True)
    archive = zipfile.ZipFile(io.BytesIO(data)) if is_zip else tarfile.open(fileobj=io.BytesIO(data), mode='r:gz')
    with archive:
        for item in archive.infolist() if is_zip else archive.getmembers():
            name = item.filename if is_zip else item.name
            parts = Path(name).parts
            if name.startswith('/') or '..' in parts or '\\' in name:
                raise ValueError('Unsafe upstream archive path')
            if not is_zip and not (item.isfile() or item.isdir()):
                raise ValueError('Unsupported upstream archive entry')
            dest = target / name
            if item.is_dir() if is_zip else item.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with (archive.open(item) if is_zip else archive.extractfile(item)) as source, dest.open('wb') as output:
                    shutil.copyfileobj(source, output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, default=ROOT / '.build-linux-r4' / 'bundle')
    parser.add_argument('--refresh-lock', action='store_true')
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    work = args.work.resolve()
    if work == ROOT or (work.is_relative_to(ROOT) and not work.relative_to(ROOT).parts[0].startswith('.build-')):
        raise ValueError('Bundle work must be an isolated build directory')
    work.mkdir(parents=True, exist_ok=True)
    lock_path = ROOT / 'third_party/runtime/lsp-tools-lock.json'
    pinned_document = {} if args.refresh_lock else json.loads(lock_path.read_text('utf-8'))
    pinned = pinned_document.get('inputs', {})
    lock = {}
    tree = work / 'tools'
    if tree.exists():
        if not tree.is_relative_to(work):
            raise ValueError('Unsafe work directory')
        shutil.rmtree(tree)
    tree.mkdir()
    for family, (version, url) in INPUTS.items():
        cache = work / (family + ('.zip' if family == 'kotlin' else '.tgz'))
        if not cache.exists():
            print('Downloading', family, version, flush=True)
            with urllib.request.urlopen(url, timeout=90) as source, cache.open('wb') as output:
                shutil.copyfileobj(source, output)
        data = cache.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        entry = {'version': version, 'url': url, 'bytes': len(data), 'sha256': sha}
        if args.refresh_lock:
            if family == 'java':
                with urllib.request.urlopen(url + '.sha256', timeout=30) as source:
                    expected = source.read().decode().split()[0]
                if sha != expected:
                    raise ValueError('JDT upstream checksum mismatch')
            elif family != 'kotlin':
                package = {'python': 'pyright', 'typescript-server': 'typescript-language-server', 'typescript': 'typescript'}[family]
                with urllib.request.urlopen('https://registry.npmjs.org/' + package + '/' + version, timeout=30) as source:
                    metadata = json.load(source)
                integrity = metadata['dist']['integrity']
                if integrity != 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode():
                    raise ValueError('NPM upstream integrity mismatch')
                entry['integrity'] = integrity
        elif any(entry[k] != pinned[family][k] for k in entry):
            raise ValueError('Pinned LSP input mismatch: ' + family)
        else:
            entry = pinned[family]
        lock[family] = entry
        extract(data, tree / family, family == 'kotlin')
    notices = {}
    (tree / 'LICENSES').mkdir()
    for name, url in NOTICES.items():
        cache = work / (name + '.txt')
        if not cache.exists():
            with urllib.request.urlopen(url, timeout=30) as response:
                cache.write_bytes(response.read())
        data = cache.read_bytes()
        entry = {'url': url, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        if not args.refresh_lock and entry != pinned_document.get('notices', {}).get(name):
            raise ValueError('Pinned license mismatch: ' + name)
        notices[name] = entry
        (tree / 'LICENSES' / (name + '.txt')).write_bytes(data)
    adapter_hash = adapt_jdt(tree, work, args.javac)
    # npm packages are complete; sibling node_modules resolves the shared TypeScript SDK.
    node_modules = tree / 'node_modules'
    node_modules.mkdir()
    (tree / 'python/package').rename(node_modules / 'pyright')
    (tree / 'typescript-server/package').rename(node_modules / 'typescript-language-server')
    (tree / 'typescript/package').rename(node_modules / 'typescript')
    for name in ('python', 'typescript-server', 'typescript'):
        (tree / name).rmdir()
    (tree / 'bin').mkdir()
    launchers = {
        'pyright-langserver': 'exec node /opt/vega-lsp/node_modules/pyright/langserver.index.js "$@"',
        'typescript-language-server': 'exec node /opt/vega-lsp/node_modules/typescript-language-server/lib/cli.mjs "$@"',
        'kotlin-language-server': 'exec java -cp "/opt/vega-lsp/kotlin/server/lib/*" org.javacs.kt.MainKt "$@"',
        'jdtls': 'case "$(uname -m)" in aarch64|arm64) cfg=config_linux_arm ;; *) cfg=config_linux ;; esac\n'
                 'exec java -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4 '
                 '-Djava.import.generatesMetadataFilesAtProjectRoot=false '
                 '-Declipse.product=org.eclipse.jdt.ls.core.product -Dlog.level=WARNING -Xmx512m '
                 '--add-modules=ALL-SYSTEM --add-opens java.base/java.util=ALL-UNNAMED '
                 '--add-opens java.base/java.lang=ALL-UNNAMED '
                 '-jar /opt/vega-lsp/java/plugins/org.eclipse.equinox.launcher_*.jar '
                 '-configuration "/opt/vega-lsp/java/$cfg" -data "${TMPDIR:-/tmp}/vega-jdt-$$"',
    }
    for name, body in launchers.items():
        (tree / 'bin' / name).write_text('#!/bin/sh\n' + body + '\n', encoding='utf-8')
    # Reuse the exact daemon and actual-language acceptance workload for install readiness.
    for source, name in [(ROOT / 'assets/code_intel_lsp_daemon.py', 'lsp_daemon.py'),
                         (ROOT / 'tools/runtime/lsp_probe.py', 'probe.py')]:
        shutil.copyfile(source, tree / name)
    lock_bytes = (json.dumps({'schema': 1, 'inputs': lock, 'notices': notices}, indent=2) + '\n').encode()
    lock_path.write_bytes(lock_bytes)
    (tree / 'lsp-tools-lock.json').write_bytes(lock_bytes)
    files = sorted(tree.rglob('*'))
    entries = expanded = 0
    payload = ROOT / 'extension-packs/inputs/linux-lsp/tools.tar.gz'
    payload.parent.mkdir(parents=True, exist_ok=True)
    with payload.open('wb') as out, gzip.GzipFile(fileobj=out, mode='wb', mtime=0, filename='') as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for path in files:
            rel = path.relative_to(tree).as_posix()
            info = tarfile.TarInfo(rel)
            info.uid = info.gid = info.mtime = 0
            if path.is_dir():
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                archive.addfile(info)
            else:
                info.size = path.stat().st_size
                info.mode = 0o755 if rel.startswith('bin/') else 0o644
                with path.open('rb') as source:
                    archive.addfile(info, source)
                expanded += info.size
            entries += 1
    manifest = {'schema': 1, 'payload': 'tools.tar.gz', 'sha256': hashlib.sha256(payload.read_bytes()).hexdigest(),
                'compressedBytes': payload.stat().st_size, 'expandedBytes': expanded, 'entries': entries,
                'lockSha256': hashlib.sha256(lock_bytes).hexdigest(), 'jdkMajor': 17,
                'languages': ['java', 'kotlin', 'python', 'typescript', 'javascript'],
                'daemonSha256': hashlib.sha256((tree / 'lsp_daemon.py').read_bytes()).hexdigest(),
                'probeSha256': hashlib.sha256((tree / 'probe.py').read_bytes()).hexdigest()}
    manifest['jdtAdapterSha256'] = adapter_hash
    catalog = ROOT / 'assets/embedded/linux-lsp/manifest.json'
    catalog.parent.mkdir(parents=True, exist_ok=True)
    catalog.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    pack = build_pack('linux-lsp', catalog, payload, ROOT / 'extension-packs/harnessmate-code-intelligence.hm-pack')
    print(pack, payload.stat().st_size, expanded, entries, flush=True)

if __name__ == '__main__':
    main()
