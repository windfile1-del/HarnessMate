#!/usr/bin/env python3
"""Full source compilation only; never assembles an APK or connects to Android."""
import argparse, hashlib, json, os, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java',default='java'); p.add_argument('--javac',default='javac')
    args=p.parse_args()
    scratch=ROOT/'.build-p7-tools/production-check'
    classes=scratch/'classes';classes.mkdir(parents=True,exist_ok=True)
    java_classes=scratch/'java';java_classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([sys.executable,str(ROOT/'tools/gen_r.py'),str(scratch/'gen')],check=True)
    upstream=[str(f) for f in (ROOT/'third_party/embedded/kotlin/upstream').glob('*.jar') if not f.name.endswith('-sources.jar')]
    libraries=[str(ROOT/'assets/embedded/android-35/android.jar'),*upstream]
    libraries += [str(f) for f in (ROOT/'third_party/embedded').rglob('*.jar') if 'kotlin' not in f.parts and 'desugar' not in f.parts and not f.name.endswith('-sources.jar')]
    libraries += [str(ROOT/f) for f in (ROOT/'third_party/webkit/runtime-jars.txt').read_text().splitlines() if f.strip()]
    sources=sorted(f for directory in ('src','runtime-companion/src') for f in (ROOT/directory).rglob('*') if f.suffix in ('.java','.kt'))
    hashes={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sources}
    def run(name,command):
        with (scratch/(name+'.log')).open('w',encoding='utf-8') as log:
            result=subprocess.run(command,stdout=log,stderr=log,cwd=ROOT)
        if result.returncode:
            print((scratch/(name+'.log')).read_text('utf-8')[-20000:]);raise SystemExit(result.returncode)
        print(name,'PASS',flush=True)
    def response(name,files):
        path=scratch/name;path.write_text('\n'.join('"'+f.as_posix()+'"' for f in files),encoding='utf-8');return '@'+str(path)
    java_sources=[f for f in sources if f.suffix=='.java']+list((scratch/'gen').rglob('*.java'))
    run('java', [args.javac,'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-proc:none','-source','17','-target','17','-cp',os.pathsep.join(libraries),'-d',str(java_classes),response('java.rsp',java_sources)])
    run('kotlin',[args.java,'-Xmx3g','-Dfile.encoding=UTF-8','-cp',os.pathsep.join(upstream),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-jvm-target','17','-classpath',os.pathsep.join([str(java_classes),*libraries]),'-d',str(classes),response('kotlin.rsp',[f for f in sources if f.suffix=='.kt'])])
    if any(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()!=h for f,h in hashes.items()):raise ValueError('SOURCE_CHANGED_DURING_COMPILE')
    (scratch/'summary.json').write_text(json.dumps({'status':'PRODUCTION_SOURCE_COMPILE_PASS','sources':len(sources),'sourceHashes':hashes,'apk':'NOT_PACKAGED','device':'DEVICE_NOT_RUN'},indent=2),encoding='utf-8')
    print('PRODUCTION_SOURCE_COMPILE_PASS',len(sources),flush=True)
if __name__=='__main__':main()
