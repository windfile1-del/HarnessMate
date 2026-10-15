#!/usr/bin/env python3
"""Prepare a packages-only release; --publish uses an already authenticated GitHub CLI.

No APK build, source upload, credential prompt, token output, or private repository change.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = 'windfile1-del/harnessmate-tool-packs'
FAMILIES = {'linux-jvm': 'harnessmate-jvm-build.hm-pack', 'linux-android': 'harnessmate-android-build.hm-pack',
            'linux-lsp': 'harnessmate-code-intelligence.hm-pack'}

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for data in iter(lambda: source.read(1024 * 1024), b''): h.update(data)
    return h.hexdigest()

def prepare(destination, families=None):
    destination = destination.resolve()
    if not destination.is_relative_to(ROOT) or not destination.relative_to(ROOT).parts[0].startswith('.build-'):
        raise ValueError('Distribution staging must be inside a root .build-* directory')
    destination.mkdir(parents=True, exist_ok=True)
    source = (ROOT / 'src/dev/wind/agent/runtime/linux/RuntimeToolCatalog.kt').read_text('utf-8')
    release = re.search(r'const val RELEASE = "([a-zA-Z0-9.-]+)"', source).group(1)
    records = []
    for family, filename in FAMILIES.items():
        if families and family not in families: continue
        pack = ROOT / 'extension-packs' / filename
        pin = (ROOT / 'assets/embedded' / family / 'manifest.json').read_bytes()
        metadata = json.loads(pin)
        with zipfile.ZipFile(pack) as archive:
            if archive.namelist() != ['manifest.json', 'tools.tar.gz', 'publisher.json']:
                raise ValueError('Unexpected or duplicate pack contents: ' + filename)
            if archive.read('manifest.json') != pin:
                raise ValueError('Pack catalog bytes differ: ' + filename)
            envelope = destination / (family + '.publisher.json')
            if archive.getinfo('publisher.json').file_size > 32768:
                raise ValueError('Publisher envelope too large')
            envelope.write_bytes(archive.read('publisher.json'))
            subprocess.run(['node', str(ROOT / 'tools/runtime/publisher_signing.cjs'), 'verify',
                '--roots', str(ROOT / 'assets/publisher-trust.json'), '--manifest', str(ROOT / 'assets/embedded' / family / 'manifest.json'),
                '--family', family, '--release', release, '--envelope', str(envelope)], check=True, capture_output=True)
            payload_hash = hashlib.sha256()
            size = 0
            with archive.open('tools.tar.gz') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    size += len(chunk); payload_hash.update(chunk)
            if size != metadata['compressedBytes'] or payload_hash.hexdigest() != metadata['sha256']:
                raise ValueError('Pack payload integrity mismatch: ' + filename)
        target = destination / filename
        shutil.copyfile(pack, target)
        records.append({'family': family, 'filename': filename, 'bytes': target.stat().st_size,
                        'sha256': sha(target), 'payloadSha256': metadata['sha256'],
                        'url': f'https://github.com/{REPO}/releases/download/{release}/{filename}'})
        # Outer ZIP digest is separate from the signed tools.tar.gz payload pin.
        (ROOT / 'assets/embedded' / family / 'download.json').write_text(
            json.dumps(records[-1], indent=2) + '\n', encoding='utf-8')
    (destination / 'catalog.json').write_text(json.dumps({'schema': 1, 'repository': REPO,
        'release': release, 'trust': 'APK_PINNED_PUBLISHER_SIGNATURE_AND_PAYLOAD', 'packs': records}, indent=2)+'\n', encoding='utf-8')
    (destination / 'SHA256SUMS').write_text(''.join(f"{r['sha256']}  {r['filename']}\n" for r in records), encoding='utf-8')
    (destination / 'README.md').write_text('# Harness Mate tool packs\n\n'
        'Public extension packages only. Harness Mate App source remains private.\n\n'
        'Use the matching App version. The App checks its embedded RSA publisher public key, detached statement, pinned catalog and payload SHA-256 before activation; '
        'this downloaded catalog is informational and cannot grant trust.\n\n'
        'JVM: Gradle/Kotlin; Android: SDK/offline dependencies; code intelligence: Java/Kotlin/Python/TS/JS servers. '
        'Python, JDK, Git and Node remain in the base App. Upstream licenses/notices and modified JDT adapter sources '
        'are preserved inside the packs.\n\n' + '\n'.join('- ' + r['filename'] for r in records) + '\n', encoding='utf-8')
    return release, [destination / r['filename'] for r in records] + [destination / n for n in ('catalog.json', 'SHA256SUMS', 'README.md')]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.build-p7-tools/distribution')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--replace-unreleased', action='store_true', help='Replace the fixed packages-only release authorized as unreleased by its owner')
    parser.add_argument('--family', action='append', choices=FAMILIES, help='Partial preparation only; cannot publish')
    parser.add_argument('--gh', default='gh')
    args = parser.parse_args()
    if args.publish and args.family: raise ValueError('Publication requires all three families')
    release, files = prepare(args.output, args.family)
    print('PACKAGES_ONLY_PREPARED', release, flush=True)
    if not args.publish: return
    def gh(*params, check=True):
        return subprocess.run([args.gh, *params], check=check, capture_output=True, text=True, encoding='utf-8')
    owner = gh('api', 'user', '--jq', '.login').stdout.strip()
    if owner != REPO.split('/')[0]: raise ValueError('Authenticated GitHub owner differs from approved owner')
    existing = gh('repo', 'view', REPO, '--json', 'visibility', check=False)
    if existing.returncode:
        # Only a proved 404 may create the exact authorized repository.
        if '404' not in existing.stderr and 'Could not resolve to a Repository' not in existing.stderr:
            raise RuntimeError('Repository lookup failed; creation skipped')
        gh('repo', 'create', REPO, '--public', '--add-readme', '--description', 'Public Harness Mate extension tool packages; no App source')
    elif json.loads(existing.stdout)['visibility'] != 'PUBLIC':
        raise ValueError('Refusing to alter repository visibility')
    prior = gh('release', 'view', release, '--repo', REPO, '--json', 'isDraft', check=False)
    if prior.returncode == 0 and not json.loads(prior.stdout)['isDraft'] and not args.replace_unreleased:
        raise ValueError('Published release exists; refusing to overwrite versioned packages')
    if prior.returncode:
        gh('release', 'create', release, '--repo', REPO, '--draft', '--title', release,
           '--notes-file', str(args.output.resolve() / 'README.md'))
    remote = json.loads(gh('release', 'view', release, '--repo', REPO, '--json', 'assets').stdout)['assets']
    local = {f.name: f for f in files}
    for asset in remote:
        f = local.get(asset['name'])
        if f is None or not args.replace_unreleased and (asset['size'] != f.stat().st_size or asset.get('digest') != 'sha256:' + sha(f)):
            raise ValueError('Existing draft asset differs; refusing to overwrite')
    uploaded = {a['name']: a for a in remote}
    pending = [str(f) for f in files if f.name not in uploaded or args.replace_unreleased and
               (uploaded[f.name]['size'] != f.stat().st_size or uploaded[f.name].get('digest') != 'sha256:' + sha(f))]
    if pending: gh('release', 'upload', release, '--repo', REPO, *(['--clobber'] if args.replace_unreleased else []), *pending)
    remote = json.loads(gh('release', 'view', release, '--repo', REPO, '--json', 'assets').stdout)['assets']
    expected = {f.name: f.stat().st_size for f in files}
    if {a['name']: a['size'] for a in remote} != expected:
        raise ValueError('Draft asset inventory mismatch; publication skipped')
    for asset in remote:
        if asset.get('digest') != 'sha256:' + sha(local[asset['name']]):
            raise ValueError('Draft asset SHA-256 mismatch; publication skipped')
    gh('release', 'edit', release, '--repo', REPO, '--draft=false', '--notes-file', str(args.output.resolve() / 'README.md'))
    published = json.loads(gh('release', 'view', release, '--repo', REPO, '--json', 'isDraft,tagName,assets').stdout)
    if published['isDraft'] or published['tagName'] != release or any(
            a['url'] != f"https://github.com/{REPO}/releases/download/{release}/{a['name']}" for a in published['assets']):
        raise ValueError('Published release metadata/URLs differ from App catalog')
    print('PUBLIC_RELEASE_PUBLISHED', f'https://github.com/{REPO}/releases/tag/{release}')

if __name__ == '__main__': main()
