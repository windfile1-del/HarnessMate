"""Sign existing pinned extensions without rebuilding tools, exposing keys or packaging an APK."""
import argparse, json, os, re, subprocess, uuid, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
FAMILIES={'linux-jvm':'harnessmate-jvm-build.hm-pack','linux-android':'harnessmate-android-build.hm-pack','linux-lsp':'harnessmate-code-intelligence.hm-pack'}
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key',type=Path,default=ROOT/'.build-p7-publisher/keys/publisher-private.pem')
    parser.add_argument('--node',default='node')
    parser.add_argument('--family',action='append',choices=FAMILIES)
    args=parser.parse_args()
    release=re.search(r'const val RELEASE = "([a-zA-Z0-9.-]+)"',(ROOT/'src/dev/wind/agent/runtime/linux/RuntimeToolCatalog.kt').read_text('utf-8')).group(1)
    script=ROOT/'tools/runtime/publisher_signing.cjs';roots=ROOT/'assets/publisher-trust.json'
    subprocess.run([args.node,str(script),'init','--key',str(args.key.resolve()),'--roots',str(roots)],check=True)
    scratch=ROOT/'.build-p7-publisher';scratch.mkdir(exist_ok=True)
    for family,name in FAMILIES.items():
        if args.family and family not in args.family: continue
        manifest=ROOT/'assets/embedded'/family/'manifest.json';envelope=scratch/(family+'.publisher.json')
        subprocess.run([args.node,str(script),'sign','--key',str(args.key.resolve()),'--manifest',str(manifest),'--family',family,'--release',release,'--output',str(envelope)],check=True)
        subprocess.run([args.node,str(script),'verify','--roots',str(roots),'--manifest',str(manifest),'--family',family,'--release',release,'--envelope',str(envelope)],check=True)
        pack=ROOT/'extension-packs'/name;stage=pack.with_suffix('.hm-pack.partial-'+uuid.uuid4().hex)
        try:
            with zipfile.ZipFile(pack) as source,zipfile.ZipFile(stage,'w',compression=zipfile.ZIP_STORED,allowZip64=True) as target:
                if source.read('manifest.json')!=manifest.read_bytes():raise ValueError('Pack manifest differs from pinned catalog')
                for entry in ('manifest.json','tools.tar.gz'):
                    info=source.getinfo(entry)
                    if info.compress_type!=zipfile.ZIP_STORED:raise ValueError('Unexpected compression')
                    # Preserve sizes/CRC through streaming, never hold large payloads in RAM.
                    with source.open(entry) as stream,target.open(info,'w') as out:
                        while data:=stream.read(1024*1024):out.write(data)
                target.writestr('publisher.json',envelope.read_bytes(),compress_type=zipfile.ZIP_STORED)
            os.replace(stage,pack)
        finally:stage.unlink(missing_ok=True)
        print('SIGNED_TOOL_PACK',name,flush=True)
    (scratch/'signing-summary.json').write_text(json.dumps({'state':'SIGNED','release':release,'public_root':str(roots),'private_key':str(args.key.resolve()),'packs':[name for family,name in FAMILIES.items() if not args.family or family in args.family],'apk':'NOT_PACKAGED','device':'DEVICE_NOT_RUN'},indent=2),'utf-8')
if __name__=='__main__':main()
