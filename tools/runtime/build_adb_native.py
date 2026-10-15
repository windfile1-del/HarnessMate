#!/usr/bin/env python3
"""Build own embedded ADB JNI from pinned source; no APK or runtime downloads.

Python 3.9+. Android NDK/CMake must already exist. All output defaults to workspace.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native/adb'
THIRD = ROOT / 'third_party/adb'
ABIS = ('arm64-v8a', 'x86_64')

def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda:f.read(1<<20),b''):h.update(block)
    return h.hexdigest()

def verify_sources():
    lock=json.loads((THIRD/'source-lock.json').read_text('utf-8'))
    expected=set()
    for item in lock['files']:
        relative=Path(item['path'])
        p=THIRD/relative
        if relative.is_absolute() or '..' in relative.parts or p.is_symlink():
            raise ValueError('Unsafe source path: '+str(p))
        expected.add(relative.as_posix())
    for base in ('reference','vendor'):
        if any(p.is_symlink() for p in (THIRD/base).rglob('*') if p.is_dir()):
            raise ValueError('Source directory symlinks forbidden')
    def verify(item):
        p=THIRD/item['path']
        if p.stat().st_size!=item['bytes'] or digest(p)!=item['sha256']:
            raise ValueError('Pinned source mismatch: '+str(p))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(verify,lock['files']))
    actual={p.relative_to(THIRD).as_posix() for base in ('reference','vendor') for p in (THIRD/base).rglob('*') if p.is_file()}
    if actual!=expected:raise ValueError('Pinned source inventory mismatch')
    return lock

def tool_path(value):
    value=str(value).replace('\\','/')
    # Older Windows CMake/NDK tools mishandle Unicode paths. Use an existing,
    # verified workspace junction if available; never create a global alias.
    alias=Path('D:/vega-work')
    if os.name=='nt' and alias.exists() and alias.resolve()==ROOT.parent.resolve():
        value=value.replace(ROOT.parent.as_posix(),alias.as_posix())
    return value

def checked(args,log=None,env=None):
    args=list(map(tool_path,args))
    if log:
        with log.open('a',encoding='utf-8') as f:
            f.write('\n$ '+' '.join(args)+'\n');f.flush();start=f.tell()
            result=subprocess.run(args,stdout=f,stderr=subprocess.STDOUT,env=env,cwd=tool_path(ROOT))
        with log.open('r',encoding='utf-8',errors='replace') as f:
            f.seek(start);output=f.read()
    else:
        result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,
                              encoding='utf-8',errors='replace',env=env,cwd=tool_path(ROOT))
        output=result.stdout
    if result.returncode:raise RuntimeError('exit '+str(result.returncode)+'\n'+output[-10000:])
    return output

def sdk_roots():
    candidates=[os.environ.get('ANDROID_SDK_ROOT'),os.environ.get('ANDROID_HOME'),
                str(ROOT.parent/'.toolchains/aapt2-api23/sdk')]
    return [Path(p) for p in candidates if p and Path(p).is_dir()]

def find_ndk():
    for key in ('ANDROID_NDK_HOME','ANDROID_NDK_ROOT'):
        if os.environ.get(key):return Path(os.environ[key])
    for sdk in sdk_roots():
        if (sdk/'ndk').is_dir():
            for p in sorted((sdk/'ndk').iterdir(),reverse=True):
                if (p/'build/cmake/android.toolchain.cmake').is_file():return p
    raise RuntimeError('NDK_NOT_FOUND: pass --ndk; no global installs performed')

def find_cmake():
    prepared=sorted((ROOT/'.build-adb/tools').glob('cmake-*/bin/cmake.exe'),reverse=True)
    if os.name=='nt' and prepared:return prepared[0]
    found=shutil.which('cmake')
    if found:return Path(found)
    for sdk in sdk_roots():
        if (sdk/'cmake').is_dir():
            for p in sorted((sdk/'cmake').iterdir(),reverse=True):
                c=p/'bin'/('cmake.exe' if os.name=='nt' else 'cmake')
                if c.is_file():return c
    raise RuntimeError('CMAKE_NOT_FOUND: pass --cmake')

def common(cmake,work,jobs,extra,target,env=None):
    work.mkdir(parents=True,exist_ok=True)
    log=work/'build.log'
    ninja=cmake.parent/('ninja.exe' if os.name=='nt' else 'ninja')
    if not ninja.is_file():
        n=shutil.which('ninja')
        if n:ninja=Path(n)
        else:
            for sdk in sdk_roots():
                candidates=list((sdk/'cmake').glob('*/bin/ninja.exe'))
                if candidates:ninja=candidates[-1];break
    flags=['-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(ninja),'-DCMAKE_BUILD_TYPE=Release']
    perl=shutil.which('perl')
    if not perl and os.name=='nt' and Path('C:/Program Files/Git/usr/bin/perl.exe').is_file():
        perl='C:/Program Files/Git/usr/bin/perl.exe'
    if perl:flags.append('-DPERL_EXECUTABLE='+perl)
    go=ROOT/'.build-adb/tools/go/bin/go.exe'
    if go.is_file():flags.append('-DGO_EXECUTABLE='+go.as_posix())
    env=dict(env or os.environ)
    # Build-time Go generators must not write caches/settings outside the workspace.
    for variable,dirname in [('GOCACHE','go-cache'),('GOPATH','go-path'),
                             ('GOMODCACHE','go-modcache'),('GOTMPDIR','go-tmp')]:
        folder=ROOT/'.build-adb'/dirname;folder.mkdir(parents=True,exist_ok=True)
        env[variable]=tool_path(folder)
    env.update({'GOENV':'off','GOTOOLCHAIN':'local','GOPROXY':'off','GOSUMDB':'off'})
    checked([cmake,'-S',SOURCE,'-B',work,*flags,*extra],log,env)
    checked([cmake,'--build',work,'--target',target,'--parallel',jobs],log,env)
    return log

def android(args,cmake,lock):
    ndk=(args.ndk or find_ndk()).resolve()
    host='windows-x86_64' if os.name=='nt' else 'darwin-x86_64' if os.sys.platform=='darwin' else 'linux-x86_64'
    ext='.exe' if os.name=='nt' else ''
    llvm=ndk/'toolchains/llvm/prebuilt'/host/'bin'
    for abi in args.abi or ABIS:
        work=args.work/abi
        common(cmake,work,args.jobs,['-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
              '-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-23','-DANDROID_STL=c++_static',
              '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'],'vegaadb')
        binary=work/'libvegaadb.so'
        checked([llvm/('llvm-strip'+ext),'--strip-unneeded',binary])
        elf=checked([llvm/('llvm-readelf'+ext),'-h','-l','-d','--dyn-syms','--wide',binary])
        loads=[int(m,16) for m in re.findall(r'^\s*LOAD\s+.*?\s(0x[0-9a-f]+)\s*$',elf,re.M)]
        if not loads or any(n<16384 for n in loads):raise RuntimeError('ELF_16KB_ALIGNMENT_FAILED')
        needed=re.findall(r'\(NEEDED\).*?\[([^\]]+)\]',elf)
        if set(needed)-{'libc.so','libm.so','libdl.so','libandroid.so'}:raise RuntimeError('Unexpected dynamic dependency: '+str(needed))
        if 'Java_dev_wind_agent_adb_NativeAdb_nativePair' not in elf:raise RuntimeError('JNI_EXPORT_MISSING')
        for method in ('nativeSignToken','nativePublicKey'):
            if 'Java_dev_wind_agent_adb_NativeAdb_'+method not in elf:raise RuntimeError('JNI_AUTH_EXPORT_MISSING')
        output=args.output/abi
        output.mkdir(parents=True,exist_ok=True)
        shutil.copy2(binary,output/'libvegaadb.so')
        report={'schema':1,'abi':abi,'minSdk':23,'pageAlignment':loads,'needed':needed,
                'sha256':digest(binary),'bytes':binary.stat().st_size,
                'ndk':(ndk/'source.properties').read_text('utf-8'),
                'boringsslCommit':lock['boringssl']['commit'],'sourceLockSha256':digest(THIRD/'source-lock.json'),
                'nativeSources':native_sources(),
                'buildScriptSha256':digest(Path(__file__)),
                'apk':'NOT_PACKAGED','device':'NOT_RUN','elf':elf}
        (output/'build-result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print(abi+': '+str(output/'libvegaadb.so')+' (16KB PASS)',flush=True)

def native_sources():
    return {p.name:digest(p) for p in sorted(SOURCE.iterdir()) if p.is_file()}

def host(args,cmake):
    source_hashes=native_sources()
    lock_hash=digest(THIRD/'source-lock.json')
    shims={p.relative_to(SOURCE).as_posix():digest(p) for p in sorted((SOURCE/'test_shims').rglob('*')) if p.is_file()}
    extra=[];env=os.environ.copy()
    if args.host_tools:
        tools=args.host_tools.resolve()
        env['PATH']=tool_path(tools)+os.pathsep+env['PATH']
        resources=sorted((tools.parent/'lib/clang').iterdir())[-1]
        host_flags='-no-canonical-prefixes -resource-dir='+tool_path(resources)
        extra=['-DCMAKE_C_COMPILER='+str(tools/'clang.exe'),'-DCMAKE_CXX_COMPILER='+str(tools/'clang++.exe'),
               '-DCMAKE_C_FLAGS='+host_flags,'-DCMAKE_CXX_FLAGS='+host_flags]
    work=args.work/'host'
    log=common(cmake,work,args.jobs,extra,'vegaadb_tests',env)
    result=checked([cmake.parent/('ctest.exe' if os.name=='nt' else 'ctest'),
                    '--test-dir',work,'--output-on-failure','--timeout','45'],log,env)
    print(result,flush=True)
    if native_sources()!=source_hashes or digest(THIRD/'source-lock.json')!=lock_hash:
        raise RuntimeError('Sources changed during host test; rerun')
    exe=work/('vegaadb_tests.exe' if os.name=='nt' else 'vegaadb_tests')
    report={'status':'PASS','output':result,'device':'NOT_RUN','nativeSources':source_hashes,
            'sourceLockSha256':lock_hash,'testShims':shims,'testExecutableSha256':digest(exe),
            'buildScriptSha256':digest(Path(__file__))}
    text=json.dumps(report,indent=2)+'\n'
    (work/'test-result.json').write_text(text,encoding='utf-8')
    (THIRD/'host-test-result.json').write_text(text,encoding='utf-8')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ndk',type=Path)
    p.add_argument('--cmake',type=Path)
    p.add_argument('--work',type=Path,default=ROOT/'.build-adb/build')
    p.add_argument('--output',type=Path,default=THIRD/'jniLibs')
    p.add_argument('--abi',choices=ABIS,action='append')
    p.add_argument('--host-test',action='store_true')
    p.add_argument('--host-tools',type=Path)
    p.add_argument('--jobs',type=int,default=min(os.cpu_count() or 4,8))
    args=p.parse_args();args.work=args.work.resolve();args.output=args.output.resolve()
    for target in (args.work,args.output):
        if not target.is_relative_to(ROOT.resolve()):raise ValueError('Outputs must stay inside workspace')
    print('Verifying vendored source lock...',flush=True)
    lock=verify_sources();cmake=(args.cmake or find_cmake()).resolve()
    print('Source lock PASS; configuring '+('host tests' if args.host_test else 'Android JNI'),flush=True)
    if args.host_test:host(args,cmake)
    else:android(args,cmake,lock)

if __name__=='__main__':main()
