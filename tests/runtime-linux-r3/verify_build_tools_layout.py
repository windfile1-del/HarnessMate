"""Inspect the release archive using AGP 8.7.2's real sdklib; no APK build/device."""
import io, json, os, subprocess, tarfile, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.build-linux-r3/build-tools-layout-test'
OUT.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(ROOT / 'extension-packs/harnessmate-android-build.hm-pack') as pack:
    with pack.open('tools.tar.gz') as payload, tarfile.open(fileobj=payload, mode='r|gz') as archive:
        for member in archive:
            prefix = 'sdk/build-tools/35.0.0/'
            if not member.isfile() or not member.name.startswith(prefix): continue
            relative = Path(member.name[len(prefix):])
            target = OUT / 'sdk' / relative
            if not target.resolve().is_relative_to((OUT / 'sdk').resolve()): raise ValueError('Unsafe archive')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())
            if relative.name == 'split-select':
                assert b'/opt/vega-native/libvegasplit-select.so' in target.read_bytes()
pins=json.loads((ROOT/'assets/embedded/linux-android/native-manifest.json').read_text())
assert 'libvegasplit-select.so' in {item['path'] for item in pins['binaries']}
cp=os.pathsep.join(str(p.relative_to(ROOT)) for p in (ROOT/'.build-linux-r3/maven').rglob('*.jar'))
def invoke(binary, args):
    rsp=OUT/(binary+'.args')
    args=[a.relative_to(ROOT) if isinstance(a,Path) and a.is_absolute() else a for a in args]
    rsp.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
    encoding = '-J-Dfile.encoding=UTF-8' if binary == 'javac' else '-Dfile.encoding=UTF-8'
    subprocess.run([str(Path('D:/VSCode/bin')/(binary+'.exe')),encoding,'@'+str(rsp.relative_to(ROOT))],check=True,cwd=ROOT)
invoke('javac',['-cp',cp,'-d',OUT,ROOT/'tests/runtime-linux-r3/BuildToolsLayoutTests.java'])
invoke('java',['-Dos.name=Linux','-cp',str(OUT.relative_to(ROOT))+os.pathsep+cp,'BuildToolsLayoutTests',OUT/'sdk'])
