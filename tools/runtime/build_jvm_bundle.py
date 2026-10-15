#!/usr/bin/env python3
"""Build the separate offline R2 JVM asset from pinned Gradle and Maven inputs.

Preparation may download official inputs on the development machine. Android
never downloads a distribution. --refresh-lock is explicit input discovery;
normal builds require every input to match the checked-in manifest.
"""
import argparse
import gzip
import hashlib
import io
import json
import tarfile
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE_SHA = '9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026'
GRADLE_URL = 'https://services.gradle.org/distributions/gradle-8.5-bin.zip'
MAVEN = 'https://repo.maven.apache.org/maven2/'
EXTRA = [
    'org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/2.0.21/org.jetbrains.kotlin.jvm.gradle.plugin-2.0.21.pom',
    'junit/junit/4.13.2/junit-4.13.2.pom', 'junit/junit/4.13.2/junit-4.13.2.jar',
    'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.pom', 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'org/hamcrest/hamcrest-parent/1.3/hamcrest-parent-1.3.pom',
    'org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.pom',
    'org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar',
    'com/google/code/gson/gson/2.8.9/gson-2.8.9.pom',
    'com/google/code/gson/gson/2.8.9/gson-2.8.9.jar',
    'com/google/code/gson/gson-parent/2.8.9/gson-parent-2.8.9.pom',
    'org/sonatype/oss/oss-parent/7/oss-parent-7.pom',
]
for artifact, version in [('kotlin-build-tools-impl', '2.0.21'), ('kotlin-compiler-runner', '2.0.21'),
                          ('kotlin-scripting-compiler-embeddable', '2.0.21'), ('kotlin-scripting-compiler-impl-embeddable', '2.0.21'),
                          ('kotlin-scripting-common', '2.0.21'), ('kotlin-scripting-jvm', '2.0.21'),
                          ('kotlin-script-runtime', '2.0.21'),
                          ('kotlin-stdlib', '2.0.21'), ('kotlin-stdlib-jdk7', '2.0.21'), ('kotlin-stdlib-jdk8', '2.0.21'),
                          ('kotlin-stdlib', '1.6.21'), ('kotlin-stdlib-common', '1.6.21'),
                          ('kotlin-stdlib-jdk7', '1.6.21'), ('kotlin-stdlib-jdk8', '1.6.21')]:
    EXTRA.extend('org/jetbrains/kotlin/' + artifact + '/' + version + '/' + artifact + '-' + version + '.' + suffix
                 for suffix in ('pom', 'jar'))
EXTRA.append('org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.module')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def download(url, target, expected=None):
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        stage = target.with_suffix(target.suffix + '.partial')
        with urllib.request.urlopen(url, timeout=60) as source, stage.open('wb') as output:
            while chunk := source.read(1 << 20):
                output.write(chunk)
        if expected and digest(stage.read_bytes()) != expected:
            raise ValueError('Download checksum mismatch: ' + url)
        stage.replace(target)
    if expected and digest(target.read_bytes()) != expected:
        raise ValueError('Input checksum mismatch: ' + str(target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, help='Discovery only: Gradle modules-2/files-2.1')
    parser.add_argument('--work', type=Path, default=ROOT / '.build-linux-r2/inputs')
    parser.add_argument('--refresh-lock', action='store_true')
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    distribution = args.work / 'gradle-8.5-bin.zip'
    download(GRADLE_URL, distribution, GRADLE_SHA)
    lock_path = ROOT / 'third_party/runtime/jvm-tools-lock.json'
    inputs = {}
    if not args.refresh_lock:
        for item in json.loads(lock_path.read_text('utf-8'))['maven']:
            path = args.work / 'maven' / item['path']
            download(item['url'], path, item['sha256'])
            data = path.read_bytes()
            if len(data) != item['bytes']:
                raise ValueError('Maven input size mismatch')
            inputs[item['path']] = data
    elif args.cache is None:
        raise ValueError('--refresh-lock requires --cache')
    # Export dependency files into portable Maven layout, not a machine Gradle cache.
    for group in (('org.jetbrains.kotlin', 'org.jetbrains.kotlinx', 'org.jetbrains.intellij.deps', 'org.jetbrains') if args.refresh_lock else ()):
        for path in sorted((args.cache / group).glob('*/*/*/*')):
            if path.is_file() and path.suffix in ('.jar', '.pom', '.module'):
                artifact, version = path.relative_to(args.cache / group).parts[:2]
                # This slice ships the selected plugin plus its runtime libraries only.
                if group == 'org.jetbrains.kotlin' and version not in ('2.0.21', '1.6.10'):
                    continue
                key = '/'.join([group.replace('.', '/'), artifact, version, path.name])
                data = path.read_bytes()
                if key in inputs and inputs[key] != data:
                    raise ValueError('Conflicting Maven input: ' + key)
                inputs[key] = data
    for key in (EXTRA if args.refresh_lock else ()):
        path = args.work / 'maven' / key
        download(MAVEN + key, path)
        inputs[key] = path.read_bytes()
    if args.refresh_lock:
        # A host cache may contain a JAR without its POM. A fresh local Maven
        # repository needs the actual model; artifact-only fallback loses dependencies.
        for key in list(inputs):
            parts = key.split('/')
            pom = '/'.join(parts[:-1] + [parts[-3] + '-' + parts[-2] + '.pom'])
            if pom not in inputs:
                path = args.work / 'maven' / pom
                download(MAVEN + pom, path)
                inputs[pom] = path.read_bytes()
        # Fill exact compile/runtime POM dependencies missing from the host cache.
        # Gradle still resolves variants/conflicts; this only collects pinned inputs.
        queue = [key for key in inputs if key.endswith('.pom')]
        visited = set()
        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
        while queue:
            key = queue.pop()
            if key in visited:
                continue
            visited.add(key)
            model = ET.fromstring(inputs[key])
            def value(element, name, default=''):
                return element.findtext('m:' + name, default, namespace).strip()
            refs = []
            parent = model.find('m:parent', namespace)
            if parent is not None:
                refs.append((parent, True))
            for dependency in model.findall('m:dependencies/m:dependency', namespace):
                if value(dependency, 'scope', 'compile') not in ('compile', 'runtime') or value(dependency, 'optional') == 'true':
                    continue
                refs.append((dependency, value(dependency, 'type', 'jar') == 'pom'))
            for dependency in model.findall('m:dependencyManagement/m:dependencies/m:dependency', namespace):
                if value(dependency, 'scope') == 'import':
                    refs.append((dependency, True))
            for ref, pom_only in refs:
                group, artifact, version = (value(ref, part) for part in ('groupId', 'artifactId', 'version'))
                if not version or '${' in version or version.startswith('[') or version.startswith('('):
                    continue  # Inherited/version-managed edges are covered by Gradle cold-cache acceptance.
                prefix = group.replace('.', '/') + '/' + artifact + '/' + version + '/'
                pom = prefix + artifact + '-' + version + '.pom'
                if pom not in inputs:
                    path = args.work / 'maven' / pom
                    download(MAVEN + pom, path)
                    inputs[pom] = path.read_bytes()
                queue.append(pom)
                if not pom_only and not any(k.startswith(prefix) and k.endswith('.jar') for k in inputs):
                    jar = prefix + artifact + '-' + version + '.jar'
                    path = args.work / 'maven' / jar
                    download(MAVEN + jar, path)
                    inputs[jar] = path.read_bytes()
    expected = [{'path': key, 'sha256': digest(data), 'bytes': len(data), 'url': MAVEN + key}
                for key, data in sorted(inputs.items())]
    lock = dict(schema=1, gradleVersion='8.5', kotlinVersion='2.0.21', junitVersion='4.13.2',
                distribution=dict(url=GRADLE_URL, sha256=GRADLE_SHA), maven=expected,
                sourceReference='https://github.com/techjarves/Mobile-Harness/blob/f0ba6730b1522ce3225cf84089305d6d1e7df905/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt')
    if args.refresh_lock:
        lock_path.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    elif json.loads(lock_path.read_text('utf-8')) != lock:
        raise ValueError('JVM inputs differ from lock; no asset was built')
    # Portable, inspectable seed used by host regressions and final bundle alike.
    for key, data in inputs.items():
        destination = args.work.parent / 'extracted/maven' / key
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    files = {'maven/' + key: data for key, data in inputs.items()}
    with zipfile.ZipFile(distribution) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            if not info.filename.startswith('gradle-8.5/') or '..' in Path(info.filename).parts:
                raise ValueError('Unsafe Gradle distribution entry')
            files[info.filename] = archive.read(info)
    files['vega-offline.gradle'] = (ROOT / 'tools/runtime/vega-offline.gradle').read_bytes()
    files['jvm-tools-lock.json'] = lock_path.read_bytes()
    fixture = ROOT / 'tests/fixtures/r2-jvm-mixed'
    for path in sorted(fixture.rglob('*')):
        if path.is_file() and not any(part in ('build', '.gradle') for part in path.relative_to(fixture).parts):
            files['probe-project/' + path.relative_to(fixture).as_posix()] = path.read_bytes()
    files['probe.sh'] = b'''set -eu
trial=$(mktemp -d /workspace/.vega-jvm-probe.XXXXXX)
trap 'rm -rf "$trial"' EXIT
cp -R /opt/vega-jvm/probe-project/. "$trial/"
export GRADLE_USER_HOME="$trial/gradle-home"
/opt/jdk/bin/java -Xmx512m --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.prefs/java.util.prefs=ALL-UNNAMED --add-opens=java.base/java.nio.charset=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.internal.instrumentation.agent=false -classpath /opt/vega-jvm/gradle-8.5/lib/gradle-launcher-8.5.jar org.gradle.launcher.GradleMain -p "$trial" --offline --no-daemon --no-watch-fs --max-workers=1 --console=plain -Pkotlin.compiler.execution.strategy=in-process -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false -Porg.gradle.java.installations.paths=/opt/jdk --init-script /opt/vega-jvm/vega-offline.gradle build
test -s "$trial/build/libs/r2-jvm-mixed-1.0.0.jar"
test -s "$trial/build/test-results/test/TEST-dev.wind.fixture.r2.JavaMutualReferenceTest.xml"
test -s "$trial/build/test-results/test/TEST-dev.wind.fixture.r2.KotlinMutualReferenceTest.xml"
printf 'VEGA_JVM_BUILD_READY\\n'
'''
    catalog = ROOT / 'assets/embedded/linux-jvm'
    catalog.mkdir(parents=True, exist_ok=True)
    output = ROOT / 'extension-packs/inputs/linux-jvm'
    output.mkdir(parents=True, exist_ok=True)
    stage = output / 'tools.tar.gz.partial'
    expanded = sum(map(len, files.values()))
    if expanded > 512 * 1024 * 1024 or len(files) > 10000:
        raise ValueError('JVM bundle budget exceeded')
    with stage.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=6) as gz:
        with tarfile.open(fileobj=gz, mode='w|', format=tarfile.USTAR_FORMAT) as archive:
            for key, data in sorted(files.items()):
                member = tarfile.TarInfo(key)
                member.size = len(data)
                member.mode = 0o644
                archive.addfile(member, io.BytesIO(data))
    stage.replace(output / 'tools.tar.gz')
    payload = (output / 'tools.tar.gz').read_bytes()
    manifest = dict(schema=1, payload='tools.tar.gz', sha256=digest(payload), compressedBytes=len(payload),
                    expandedBytes=expanded, entries=len(files), gradleVersion='8.5', kotlinVersion='2.0.21',
                    jdkMajor=17, lockSha256=digest(lock_path.read_bytes()))
    (catalog / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    from build_extension_pack import build_pack
    build_pack('linux-jvm', catalog / 'manifest.json', output / 'tools.tar.gz', ROOT / 'extension-packs/harnessmate-jvm-build.hm-pack')
    print(json.dumps(manifest))


if __name__ == '__main__':
    main()
