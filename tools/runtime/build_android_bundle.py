#!/usr/bin/env python3
"""Build R3 assets from locked official inputs and Mobile Harness ARM64 tools.

--refresh-lock records deliberately prepared inputs; default checks every byte.
Requires zstandard only on the development machine, never on Android.
"""
import argparse
import gzip
import io
import json
import tarfile
import zipfile
from pathlib import Path
from build_jvm_bundle import digest, download

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.build-linux-r3'
LOCK = ROOT / 'third_party/runtime/android-tools-lock.json'
TOOLS = {'aapt2': 'libvegaaapt2.so', 'aidl': 'libvegaaidl.so', 'zipalign': 'libvegazipalign.so',
         'aapt': 'libvegaaapt.so', 'dexdump': 'libvegadexdump.so', 'split-select': 'libvegasplit-select.so'}
UPSTREAM = dict(url='https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4/pocketdev-android-arm64-2026.09.1.tar.zst',
                sha256='01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6', bytes=569652007)
GRADLE = dict(url='https://services.gradle.org/distributions/gradle-8.9-bin.zip',
              sha256='d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab')
PLATFORM = dict(url='https://dl.google.com/android/repository/platform-35_r02.zip',
                sha256='0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0', bytes=64273788)

def safe(name):
    if name.startswith('/') or '\\' in name or ':' in name or any(p in ('..', '') for p in name.split('/')):
        raise ValueError('Unsafe input path: ' + name)
    return name

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh-lock', action='store_true')
    args = parser.parse_args()
    inputs = WORK / 'inputs'
    inputs.mkdir(parents=True, exist_ok=True)
    for name, pin in [('mobile-android.tar.zst', UPSTREAM), ('gradle-8.9-bin.zip', GRADLE), ('platform-35_r02.zip', PLATFORM)]:
        download(pin['url'], inputs / name, pin['sha256'])
        if 'bytes' in pin and (inputs / name).stat().st_size != pin['bytes']:
            raise ValueError('Pinned input size mismatch')
    old = None if args.refresh_lock else json.loads(LOCK.read_text('utf-8'))
    if old:
        for entry in old['maven']:
            target = WORK / 'maven' / entry['path']
            download(entry['url'], target, entry['sha256'])
            if target.stat().st_size != entry['bytes']:
                raise ValueError('Maven input size mismatch')
    files = {}
    maven = []
    selected = [WORK / 'maven' / entry['path'] for entry in old['maven']] if old else sorted((WORK / 'maven').rglob('*'))
    for path in selected:
        if not path.is_file() or path.suffix not in ('.jar', '.pom', '.module', '.aar'):
            continue
        key = safe(path.relative_to(WORK / 'maven').as_posix())
        data = path.read_bytes()
        base = 'https://dl.google.com/dl/android/maven2/' if key.startswith(('com/android/', 'androidx/', 'com/google/testing/')) else 'https://repo.maven.apache.org/maven2/'
        files['maven/' + key] = data
        maven.append(dict(path=key, url=base + key, bytes=len(data), sha256=digest(data)))
    native = {}
    import zstandard
    with (inputs / 'mobile-android.tar.zst').open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as stream:
        with tarfile.open(fileobj=stream, mode='r|') as archive:
            for item in archive:
                key = item.name.removeprefix('./')
                prefix = 'root/android-sdk/build-tools/35.0.0/'
                if not item.isfile():
                    continue
                if key.startswith(prefix):
                    name = safe(key[len(prefix):])
                    if name in TOOLS:
                        native[TOOLS[name]] = archive.extractfile(item).read()
                    elif name in ('NOTICE.txt', 'package.xml', 'source.properties', 'runtime.properties', 'core-lambda-stubs.jar', 'apksigner', 'd8') or name.startswith('lib/'):
                        files['sdk/build-tools/35.0.0/' + name] = archive.extractfile(item).read()
                elif key.startswith('root/android-sdk/licenses/'):
                    files['sdk/licenses/' + safe(key.split('licenses/', 1)[1])] = archive.extractfile(item).read()
    if set(native) != set(TOOLS.values()):
        raise ValueError('Incomplete upstream ARM64 build tool set')
    pins = dict(schema=1, abi='arm64-v8a', minSdk=29, upstream=UPSTREAM,
                binaries=[dict(path=key, sha256=digest(data), bytes=len(data)) for key, data in sorted(native.items())])
    for tool, name in TOOLS.items():
        files['sdk/build-tools/35.0.0/' + tool] = ('#!/bin/sh\nexec /opt/vega-native/' + name + ' "$@"\n').encode()
    # SDK35 requirements from sdklib 31.7.2 plus the real D8/signing entrypoints.
    required = (*TOOLS, 'core-lambda-stubs.jar', 'd8', 'apksigner', 'lib/d8.jar', 'lib/apksigner.jar')
    for name in required:
        if not files.get('sdk/build-tools/35.0.0/' + name):
            raise ValueError('Incomplete SDK35 build tools: ' + name)
    with zipfile.ZipFile(inputs / 'platform-35_r02.zip') as archive:
        for item in archive.infolist():
            if not item.is_dir():
                name = safe(item.filename)
                if not name.startswith('android-35/'):
                    raise ValueError('Unexpected SDK platform entry')
                files['sdk/platforms/' + name] = archive.read(item)
    with zipfile.ZipFile(inputs / 'gradle-8.9-bin.zip') as archive:
        for item in archive.infolist():
            if not item.is_dir():
                name = safe(item.filename)
                if not name.startswith('gradle-8.9/'):
                    raise ValueError('Unexpected Gradle input entry')
                files[name] = archive.read(item)
    lock = dict(schema=1, abi='arm64-v8a', minSdk=29, gradleVersion='8.9', agpVersion='8.7.2', kotlinVersion='2.0.21',
                buildToolsVersion='35.0.0', compileSdk=35, distribution=GRADLE, platform=PLATFORM,
                upstream=UPSTREAM, native=pins['binaries'], maven=maven,
                sourceReference='https://github.com/techjarves/Mobile-Harness/blob/f0ba6730b1522ce3225cf84089305d6d1e7df905/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt')
    if args.refresh_lock:
        LOCK.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    elif old != lock:
        raise ValueError('Android inputs differ from lock')
    script = (ROOT / 'tools/runtime/vega-offline.gradle').read_text('utf-8').replace('/opt/vega-jvm/maven', '/opt/vega-android/maven').replace('Vega R2', 'Vega R3').replace('R2_', 'R3_')
    script += "\n// The launcher supplies -Pandroid.aapt2FromMavenOverride before AGP snapshots properties.\n// SDK aapt2 is a PRoot file bind to the PackageManager executable (no filesDir exec).\n"
    files['vega-offline.gradle'] = script.encode()
    files['android-tools-lock.json'] = LOCK.read_bytes()
    fixture = ROOT / 'tests/fixtures/r3-android-mixed'
    for path in sorted(fixture.rglob('*')):
        if path.is_file() and not any(part in ('build', '.gradle', 'local.properties') for part in path.relative_to(fixture).parts):
            files['probe-project/' + safe(path.relative_to(fixture).as_posix())] = path.read_bytes()
    # Uses unmodified AGP for AAPT2, Java/Kotlin, D8/R8, package and debug sign.
    java = '/opt/jdk/bin/java -Xmx768m --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.prefs/java.util.prefs=ALL-UNNAMED --add-opens=java.base/java.nio.charset=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.internal.instrumentation.agent=false -classpath /opt/vega-android/gradle-8.9/lib/gradle-launcher-8.9.jar org.gradle.launcher.GradleMain'
    args = ' --offline --no-daemon --no-watch-fs --max-workers=1 --console=plain -Pandroid.aapt2FromMavenOverride=/opt/vega-android/sdk/build-tools/35.0.0/aapt2 -Pkotlin.compiler.execution.strategy=in-process -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false -Porg.gradle.java.installations.paths=/opt/jdk --init-script /opt/vega-android/vega-offline.gradle'
    files['bin/gradle'] = ('#!/bin/sh\nset -eu\nexport ANDROID_HOME=/opt/vega-android/sdk ANDROID_SDK_ROOT=/opt/vega-android/sdk\nexec ' + java + ' "$@"' + args + '\n').encode()
    files['probe.sh'] = ('''set -eu
trial=$(mktemp -d /workspace/.vega-android-probe.XXXXXX)
trap 'rm -rf "$trial"' EXIT
cp -R /opt/vega-android/probe-project/. "$trial/"
export GRADLE_USER_HOME="$trial/gradle-home"
export ANDROID_HOME=/opt/vega-android/sdk ANDROID_SDK_ROOT=/opt/vega-android/sdk
cd "$trial"
/opt/vega-android/bin/gradle :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :library:testDebugUnitTest
for variant in debug release; do
    apk="$trial/app/build/outputs/apk/$variant/app-$variant.apk"
    test -s "$apk"
    /bin/sh /opt/vega-android/sdk/build-tools/35.0.0/apksigner verify --verbose --min-sdk-version 29 "$apk"
    /opt/vega-native/libvegazipalign.so -c -P 16 4 "$apk"
    /opt/vega-native/libvegaaapt2.so dump badging "$apk"
done
test -s "$trial/app/build/test-results/testDebugUnitTest/TEST-com.example.mixed.app.AppUnitTest.xml"
test -s "$trial/library/build/test-results/testDebugUnitTest/TEST-com.example.mixed.library.LibraryUnitTest.xml"
printf 'VEGA_ANDROID_BUILD_READY\\n'
''').encode()
    files['LICENSES/Mobile-Harness-MIT.txt'] = (ROOT / 'third_party/runtime/licenses/Mobile-Harness-MIT.txt').read_bytes()
    catalog = ROOT / 'assets/embedded/linux-android'
    catalog.mkdir(parents=True, exist_ok=True)
    output = ROOT / 'extension-packs/inputs/linux-android'
    output.mkdir(parents=True, exist_ok=True)
    native_root = ROOT / 'third_party/runtime/android-jniLibs/arm64-v8a'
    native_root.mkdir(parents=True, exist_ok=True)
    for name, data in native.items():
        (native_root / name).write_bytes(data)
    (catalog / 'native-manifest.json').write_text(json.dumps(pins, indent=2) + '\n', encoding='utf-8')
    expanded = sum(map(len, files.values()))
    if expanded > 1024 * 1024 * 1024 or len(files) > 20000:
        raise ValueError('Android asset budget exceeded')
    stage = output / 'tools.tar.gz.partial'
    with stage.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=6) as gz:
        with tarfile.open(fileobj=gz, mode='w|', format=tarfile.USTAR_FORMAT) as archive:
            for key, data in sorted(files.items()):
                member = tarfile.TarInfo(key)
                member.size = len(data)
                member.mode = 0o755 if key.startswith('bin/') or key.startswith('sdk/build-tools/35.0.0/') and key.split('/')[-1] in (*TOOLS, 'apksigner', 'd8') else 0o644
                archive.addfile(member, io.BytesIO(data))
    stage.replace(output / 'tools.tar.gz')
    payload = (output / 'tools.tar.gz').read_bytes()
    manifest = dict(schema=1, payload='tools.tar.gz', sha256=digest(payload), compressedBytes=len(payload), expandedBytes=expanded,
                    entries=len(files), gradleVersion='8.9', kotlinVersion='2.0.21', agpVersion='8.7.2', compileSdk=35,
                    buildToolsVersion='35.0.0', jdkMajor=17, abi='arm64-v8a', minSdk=29, lockSha256=digest(LOCK.read_bytes()))
    (catalog / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    from build_extension_pack import build_pack
    build_pack('linux-android', catalog / 'manifest.json', output / 'tools.tar.gz', ROOT / 'extension-packs/harnessmate-android-build.hm-pack')
    # Inspection/host builds use exactly the same expanded inputs.
    for key, data in files.items():
        target = WORK / 'overlay' / key
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(json.dumps(manifest), flush=True)

if __name__ == '__main__':
    main()
