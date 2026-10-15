"""Read-only verification of a completed unsigned stage before align/sign resumption."""
import hashlib, json, pathlib, sys, zipfile
root=pathlib.Path(__file__).resolve().parents[2]
build=pathlib.Path(sys.argv[1]).resolve()
if not build.is_relative_to(root / '.build-mate-memory'): raise ValueError('Invalid owned build')
def digest(data): return hashlib.sha256(data).hexdigest()
for name,want in json.loads((root/'.build-mate-memory/host/source-hashes.json').read_text('utf-8')).items():
    if digest((root/name).read_bytes())!=want: raise ValueError('Stale source '+name)
production=root/'.build-mate-memory/host/production'
count=0
for original in production.rglob('*.class'):
    rel=original.relative_to(production)
    if rel.parent.as_posix()=='dev/wind/agent' and (rel.name=='R.class' or rel.name.startswith('R$')): continue
    if (build/'classes'/rel).read_bytes()!=original.read_bytes(): raise ValueError('Stale compiled class '+str(rel))
    count+=1
with zipfile.ZipFile(build/'out/unsigned.apk') as apk:
    names=apk.namelist()
    for entry in names:
        if (entry.startswith('lib/') or entry.endswith('.dex')) and names.count(entry)!=1: raise ValueError('Duplicate executable entry '+entry)
    for dex in (build/'dex').glob('*.dex'):
        if apk.read(dex.name)!=dex.read_bytes(): raise ValueError('Stale D8 output '+dex.name)
    if apk.read('assets/embedded/phone/vega-phone.jar')!=(root/'assets/embedded/phone/vega-phone.jar').read_bytes(): raise ValueError('Stale Bridge payload')
print('STAGED_CLASSES_DEX_BRIDGE_VERIFIED',count)
