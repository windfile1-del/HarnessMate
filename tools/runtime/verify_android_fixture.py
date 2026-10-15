#!/usr/bin/env python3
"""Host-only R3 verification: Java/Kotlin, resources, D8/R8 and local JUnit.

Deliberately never invokes assemble/package/sign/install or emits an APK.
Windows AAPT2 is a host substitute; Android ARM64 execution is NOT_RUN.
"""
import argparse
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, default=ROOT / '.build-linux-r3/host-verification')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    # Preserve a caller's ASCII subst drive on Windows; resolving it would
    # reintroduce the Chinese checkout path rejected by AGP's host-only guard.
    work = args.work.absolute()
    fixture = work / 'fixture'
    if work.exists() and not args.resume:
        raise ValueError('Use a fresh host verification path')
    work.mkdir(parents=True, exist_ok=True)
    if not fixture.exists():
        shutil.copytree(ROOT / 'tests/fixtures/r3-android-mixed', fixture, ignore=shutil.ignore_patterns('build', '.gradle', 'local.properties'))
    sdk = work / 'sdk'
    if not sdk.exists():
        shutil.copytree(ROOT / '.build-linux-r3/host-build-tools/android-15', sdk / 'build-tools/35.0.0')
        shutil.copytree(ROOT / '.build-linux-r3/sdk-platform/android-35', sdk / 'platforms/android-35')
    # Use the final shipping Maven seed and adapted init script, in isolation.
    maven = work / 'maven'
    if not maven.exists():
        shutil.copytree(ROOT / '.build-linux-r3/overlay/maven', maven)
    elif args.resume:
        shutil.copytree(ROOT / '.build-linux-r3/maven', maven, dirs_exist_ok=True)
    gradle = work / 'gradle-8.9'
    if not gradle.exists():
        shutil.copytree(ROOT / '.build-linux-r3/overlay/gradle-8.9', gradle)
    init = work / 'vega-offline.gradle'
    shutil.copyfile(ROOT / '.build-linux-r3/overlay/vega-offline.gradle', init)
    (work / 'tmp').mkdir(exist_ok=True)
    env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
               GRADLE_USER_HOME=str(work / 'gradle-home'), TEMP=str(work / 'tmp'), TMP=str(work / 'tmp'))
    argv = ['D:/VSCode/bin/java.exe', '-Xmx1g', '-Dfile.encoding=UTF-8', '-Dorg.gradle.native=false',
            '-Dorg.gradle.vfs.watch=false', '-Dorg.gradle.jvmargs=-Xmx1g', '-Dorg.gradle.internal.instrumentation.agent=false',
            '-Djava.io.tmpdir=' + str(work / 'tmp'), '-Dvega.maven=' + maven.as_uri(),
            '-Dvega.aapt2=' + str(sdk / 'build-tools/35.0.0/aapt2.exe'),
            '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.lang=ALL-UNNAMED',
            '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED',
            '--add-opens=java.base/java.nio.charset=ALL-UNNAMED', '--add-opens=java.base/java.net=ALL-UNNAMED',
            '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
            '-classpath', str(gradle / 'lib/gradle-launcher-8.9.jar'), 'org.gradle.launcher.GradleMain',
            '-p', str(fixture), '--offline', '--no-daemon', '--no-watch-fs', '--max-workers=1', '--console=plain',
            '-Pkotlin.compiler.execution.strategy=in-process', '-Porg.gradle.java.installations.auto-detect=false',
            '-Pandroid.aapt2FromMavenOverride=' + str(sdk / 'build-tools/35.0.0/aapt2.exe'),
            '-Porg.gradle.java.installations.auto-download=false', '-Porg.gradle.java.installations.paths=D:/VSCode',
            '--init-script', str(init)]
    tasks = [':app:compileDebugJavaWithJavac', ':app:processDebugResources', ':app:mergeExtDexDebug',
             ':app:mergeProjectDexDebug', ':app:minifyReleaseWithR8', ':app:testDebugUnitTest', ':library:testDebugUnitTest']
    log = work / 'android-host.log'
    with log.open('wb') as out:
        result = subprocess.run(argv + tasks, stdout=out, stderr=subprocess.STDOUT, env=env, timeout=900)
    print('Gradle exit', result.returncode, 'log', str(log), flush=True)
    if result.returncode:
        print(log.read_text('utf-8', errors='replace')[-14000:], flush=True)
        raise SystemExit(result.returncode)
    tests = {}
    for module, suite in [('app', 'com.example.mixed.app.AppUnitTest'), ('library', 'com.example.mixed.library.LibraryUnitTest')]:
        report = fixture / module / 'build/test-results/testDebugUnitTest' / ('TEST-' + suite + '.xml')
        root = ET.parse(report).getroot()
        if root.get('failures') != '0' or root.get('errors') != '0' or int(root.get('tests', '0')) < 1:
            raise ValueError('Missing/failing actual JUnit workload')
        tests[module] = int(root.get('tests'))
    dex = [p for p in (fixture / 'app/build').rglob('classes*.dex') if p.is_file() and p.stat().st_size]
    if not dex or any(fixture.rglob('*.apk')):
        raise ValueError('Expected D8/R8 DEX and no APK packaging')
    summary = dict(host='PASS', handset='NOT_RUN', emulator='NOT_RUN', apkPackaging='NOT_RUN',
                   gradle='8.9', agp='8.7.2', kotlin='2.0.21', sdk=35, tasks=tasks, junit=tests,
                   dex=[str(p.relative_to(fixture)) for p in dex],
                   nativeNote='Windows AAPT2 substitutes for ARM64. Native executable acceptance remains on phone.')
    (work / 'verification-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary), flush=True)

if __name__ == '__main__':
    main()
