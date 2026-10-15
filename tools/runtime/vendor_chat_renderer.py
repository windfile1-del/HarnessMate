#!/usr/bin/env python3
"""Vendor pinned browser distributions and licenses; never installs npm packages or runs scripts."""
import base64, hashlib, io, json, tarfile, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / 'third_party/chat-renderer/lock.json'
def main():
    lock = json.loads(LOCK.read_text('utf-8'))
    out = ROOT / 'assets/chat-render/vendor'
    out.mkdir(parents=True, exist_ok=True)
    records = {}
    for item in lock['packages']:
        data = urllib.request.urlopen(item['tarball'], timeout=60).read()
        assert 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode() == item['integrity'], item['name']
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            selected = dict(item['files'])
            if item['name'] == 'katex':
                selected.update({m.name:'fonts/' + m.name.rsplit('/',1)[1] for m in archive if m.isfile() and m.name.startswith('package/dist/fonts/')})
            for member, target in selected.items():
                assert '..' not in Path(target).parts and not Path(target).is_absolute()
                entry = archive.getmember(member)
                assert entry.isfile()
                content = archive.extractfile(entry).read()
                path = out / target; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
                records[str(path.relative_to(ROOT))] = hashlib.sha256(content).hexdigest()
        print('VENDORED', item['name'], item['version'], flush=True)
    (LOCK.parent/'SHA256SUMS').write_text(''.join(h + '  ' + p + '\n' for p,h in sorted(records.items())), encoding='utf-8')
if __name__ == '__main__': main()
