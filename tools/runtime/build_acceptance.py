#!/usr/bin/env python3
"""Build signed Linux R0/R1 fixture acceptance APKs; this is not a full app build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import zipfile
sys.dont_write_bytecode = True
from package_native import verified_libraries


ROOT = Path(__file__).resolve().parents[2]
ANDROID_JAR = ROOT / 'assets/embedded/android-35/android.jar'
PRODUCTION_SOURCES = ROOT / 'src/dev/wind/agent/runtime/linux'
INSTRUMENTATION_ROOT = ROOT / 'device-tests/linux-r0'
BUILD_TOOLS_VERSION = '34.0.0'
ABI_LIBRARIES = {
    'arm64-v8a': ('libandroidshmem.so', 'libproot.so', 'libprootloader.so', 'libtalloc.so', 'libvegaspawn.so'),
    'x86_64': ('libandroidshmem.so', 'libproot.so', 'libprootloader.so', 'libtalloc.so', 'libvegaspawn.so'),
}


def rooted(path):
    path = Path(path)
    return path.resolve() if path.is_absolute() else (ROOT / path).resolve()


def checked(argv, label, env=None):
    result = subprocess.run([str(arg) for arg in argv], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                            errors='replace', env=env)
    if result.returncode:
        raise RuntimeError('{} exited {}:\n{}'.format(label, result.returncode, result.stdout.strip()))
    return result.stdout


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    temporary.replace(path)


def require_file(path, label):
    if not path.is_file() or path.is_symlink():
        raise FileNotFoundError('{} is missing or not a regular file: {}'.format(label, path))
    return path.resolve()


def find_r8(sdk, build_tools):
    candidates = [
        ROOT / 'third_party/embedded/r8/r8-8.2.33.jar',
        sdk / 'cmdline-tools/latest/lib/r8.jar',
        build_tools / 'lib/d8.jar',
    ]
    for candidate in candidates:
        if candidate.is_file() and not candidate.is_symlink():
            return candidate.resolve()
    raise FileNotFoundError('Local R8/D8 jar not found in third_party/embedded/r8 or Android SDK build tools')


def source_tree(source_root, output, android_jar, javac):
    sources = sorted(source_root.glob('*.java'))
    if not sources or not any(path.name == 'LinuxR0Probe.java' for path in sources):
        raise FileNotFoundError('Production LinuxR0Probe.java and Linux runtime sources are required')
    output.mkdir(parents=True)
    command = [javac, '-J-Duser.language=en', '-J-Duser.country=US', '-J-Dfile.encoding=UTF-8',
               '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-proc:none',
               '-bootclasspath', android_jar, '-d', output] + sources
    checked(command, 'javac production Linux R0 sources')
    classes = sorted(output.rglob('*.class'))
    if not classes:
        raise RuntimeError('javac produced no production classes')
    return classes


def dex_classes(java, r8_jar, android_jar, classes, output):
    output.mkdir(parents=True)
    command = [java, '-Duser.language=en', '-Duser.country=US', '-Dfile.encoding=UTF-8',
               '-cp', r8_jar, 'com.android.tools.r8.D8', '--min-api', '23',
               '--lib', android_jar, '--output', output] + classes
    checked(command, 'D8 min-api 23')
    dex = output / 'classes.dex'
    return require_file(dex, 'D8 classes.dex')


def matching_inputs(native_root, assets_root, requested_abis):
    asset_base = assets_root / 'embedded/linux-r0'
    if not native_root.is_dir():
        raise FileNotFoundError('Native input directory is missing: ' + str(native_root))
    native_by_abi = {}
    for path, _ in verified_libraries(native_root):
        native_by_abi.setdefault(path.parent.name, {})[path.name] = path.resolve()
    asset_abis = {path.name for path in asset_base.iterdir() if path.is_dir()} if asset_base.is_dir() else set()
    invalid_assets = asset_abis - set(ABI_LIBRARIES)
    if invalid_assets:
        raise ValueError('Unsupported R0 asset ABI directory: ' + ', '.join(sorted(invalid_assets)))
    abis = requested_abis or sorted(set(native_by_abi).intersection(asset_abis))
    selected = []
    for abi in abis:
        asset_dir = asset_base / abi
        if abi not in native_by_abi or not asset_dir.is_dir():
            if requested_abis:
                raise FileNotFoundError('Requested ABI lacks matching native and rootfs inputs: ' + abi)
            continue
        native_files = native_by_abi[abi]
        missing = [name for name in ABI_LIBRARIES[abi] if name not in native_files]
        if missing:
            raise FileNotFoundError('{} native input is missing: {}'.format(abi, ', '.join(missing)))
        manifest_path = require_file(asset_dir / 'manifest.json', abi + ' rootfs manifest')
        archive_path = require_file(asset_dir / 'rootfs.tar.gz', abi + ' rootfs archive')
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        if (manifest.get('abi') != abi or manifest.get('minSdk') != 23 or
                manifest.get('fixtureOnly') is not True or manifest.get('payload') != 'rootfs.tar.gz'):
            raise ValueError('R0 rootfs manifest does not match fixture ABI/minSdk contract: ' + str(manifest_path))
        if (manifest.get('sha256') != sha256(archive_path) or
                manifest.get('compressedBytes') != archive_path.stat().st_size):
            raise ValueError('R0 rootfs archive does not match its manifest: ' + str(archive_path))
        selected.append({'abi': abi, 'libraries': native_files, 'manifest': manifest_path, 'archive': archive_path})
    if not selected:
        raise FileNotFoundError('No supported ABI has both native/*.so inputs and matching embedded/linux-r0 assets')
    return selected


def assemble(base_apk, dex, abi_inputs, output, target):
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(base_apk, 'r') as source, zipfile.ZipFile(output, 'w') as apk:
        names = set()
        for item in source.infolist():
            names.add(item.filename)
            with source.open(item, 'r') as input_stream, apk.open(item, 'w') as output_stream:
                shutil.copyfileobj(input_stream, output_stream, 1 << 20)

        def add(name, path, compression):
            if name in names:
                raise ValueError('Duplicate APK entry: ' + name)
            names.add(name)
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = compression
            info.external_attr = 0o100644 << 16
            with path.open('rb') as input_stream, apk.open(info, 'w') as output_stream:
                shutil.copyfileobj(input_stream, output_stream, 1 << 20)

        add('classes.dex', dex, zipfile.ZIP_DEFLATED)
        if target:
            for item in abi_inputs:
                abi = item['abi']
                for name, path in sorted(item['libraries'].items()):
                    add('lib/{}/{}'.format(abi, name), path, zipfile.ZIP_STORED)
                prefix = 'assets/embedded/linux-r0/{}/'.format(abi)
                add(prefix + 'manifest.json', item['manifest'], zipfile.ZIP_DEFLATED)
                add(prefix + 'rootfs.tar.gz', item['archive'], zipfile.ZIP_STORED)


def link_manifest(aapt2, android_jar, manifest, output):
    checked([aapt2, 'link', '-o', output, '-I', android_jar, '--manifest', manifest,
             '--min-sdk-version', '23', '--target-sdk-version', '35',
             '--version-code', '1', '--version-name', 'R0-fixture'], 'aapt2 manifest link')


def sign_apk(java, apksigner_jar, zipalign, aligned, signed, keystore, alias, password, env_name):
    checked([zipalign, '-f', '-p', '4', aligned, signed.with_suffix('.aligned.apk')], 'zipalign')
    aligned_apk = signed.with_suffix('.aligned.apk')
    checked([zipalign, '-c', '-v', '4', aligned_apk], 'zipalign verification')
    signing_env = os.environ.copy()
    signing_env[env_name] = password
    try:
        checked([java, '-Duser.language=en', '-Duser.country=US', '-Dfile.encoding=UTF-8',
                 '-jar', apksigner_jar, 'sign',
                 '--ks', keystore, '--ks-key-alias', alias,
                 '--ks-pass', 'env:' + env_name, '--key-pass', 'env:' + env_name,
                 '--out', signed, aligned_apk], 'apksigner sign', signing_env)
        checked([java, '-Duser.language=en', '-Duser.country=US', '-Dfile.encoding=UTF-8',
                 '-jar', apksigner_jar, 'verify', '--verbose', signed], 'apksigner verification')
    finally:
        signing_env.pop(env_name, None)
        aligned_apk.unlink(missing_ok=True)


def build(args):
    output = rooted(args.output)
    if output.exists() or output.is_symlink():
        raise FileExistsError('Refusing to reuse output directory: ' + str(output))
    if not output.parent.is_dir():
        raise FileNotFoundError('OutputDirectory parent must already exist: ' + str(output.parent))
    output.mkdir()

    report = {
        'phase': args.phase,
        'schema': 1,
        'status': 'BUILD_FAILED',
        'deviceAcceptance': 'NOT_RUN',
        'fixtureScope': 'Linux ' + args.phase + ' native adapter acceptance fixture; not a full Vega Safe app or Binder/service recovery acceptance',
        'targetPackage': 'dev.wind.agent',
        'instrumentationPackage': 'dev.wind.agent.linuxr0',
        'minSdk': 23,
        'targetSdk': 35,
        'outputDirectory': str(output),
        'includedAbis': [],
        'error': None,
    }
    keystore = output / '.r0-acceptance.p12'
    env_name = 'VEGA_R0_KEYPASS_' + secrets.token_hex(12).upper()
    try:
        sdk = rooted(args.sdk)
        java_home = rooted(args.java_home)
        native_root = rooted(args.native)
        assets_root = rooted(args.assets)
        build_tools = sdk / 'build-tools' / BUILD_TOOLS_VERSION
        java = require_file(java_home / 'bin/java.exe', 'java')
        javac = require_file(java_home / 'bin/javac.exe', 'javac')
        keytool = require_file(java_home / 'bin/keytool.exe', 'keytool')
        aapt2 = require_file(build_tools / 'aapt2.exe', 'aapt2 build-tools 34')
        zipalign = require_file(build_tools / 'zipalign.exe', 'zipalign build-tools 34')
        apksigner_jar = require_file(build_tools / 'lib/apksigner.jar', 'apksigner build-tools 34')
        android_jar = require_file(ANDROID_JAR, 'embedded Android 35 android.jar')
        r8_jar = require_file(find_r8(sdk, build_tools), 'local R8/D8 jar')
        target_manifest = require_file(INSTRUMENTATION_ROOT / 'target-AndroidManifest.xml', 'target fixture manifest')
        test_manifest = require_file(INSTRUMENTATION_ROOT / 'AndroidManifest.xml', 'instrumentation manifest')
        runner_source = require_file(INSTRUMENTATION_ROOT / 'src/dev/wind/agent/linuxr0/LinuxR0AcceptanceInstrumentation.java', 'instrumentation source')
        probe_source = require_file(PRODUCTION_SOURCES / 'LinuxR0Probe.java', 'production LinuxR0Probe source')
        abi_inputs = matching_inputs(native_root, assets_root, args.abi)
        report['includedAbis'] = [item['abi'] for item in abi_inputs]
        report['nativeLibraries'] = {
            item['abi']: [
                {'path': name, 'bytes': path.stat().st_size, 'sha256': sha256(path)}
                for name, path in sorted(item['libraries'].items())
            ] for item in abi_inputs
        }
        report['tools'] = {'sdk': str(sdk), 'javaHome': str(java_home), 'r8Jar': str(r8_jar),
                           'aapt2': str(aapt2), 'buildTools': BUILD_TOOLS_VERSION}

        target_classes = output / 'target-classes'
        # R1 adds only the native process/supervisor probe; service/Binder tests
        # remain a separate acceptance gate rather than an implied result.
        target_classes.mkdir(parents=True)
        r0_sources = [PRODUCTION_SOURCES / name for name in (
            'LinuxNativeSpawn.java', 'TrustedRootfsExtractor.java', 'LinuxR0Installer.java',
            'LinuxRuntimeLauncher.java', 'LinuxToolchainProbe.java', 'LinuxR0Probe.java')]
        if args.phase == 'R1':
            r0_sources += [PRODUCTION_SOURCES / 'LinuxManagedProcess.java',
                           PRODUCTION_SOURCES / 'LinuxProcessFactory.java',
                           ROOT / 'runtime-companion/src/dev/wind/agent/runtime/RuntimeProcessSupervisor.java',
                           ROOT / 'device-tests/linux-r1/src/dev/wind/agent/runtime/linux/LinuxR1Probe.java']
        report['compiledSources'] = [{'path': str(path), 'sha256': sha256(path)} for path in r0_sources]
        checked([javac, '-source', '8', '-target', '8', '-encoding', 'UTF-8',
                 '-proc:none', '-bootclasspath', android_jar, '-d', target_classes, *r0_sources],
                'javac R0 production fixture')
        target_dex = dex_classes(java, r8_jar, android_jar,
                                 sorted(target_classes.rglob('*.class')), output / 'target-dex')
        target_base = output / 'target-base.apk'
        link_manifest(aapt2, android_jar, target_manifest, target_base)
        target_unsigned = output / 'target-unsigned.apk'
        target_apk = output / 'target-acceptance.apk'
        assemble(target_base, target_dex, abi_inputs, target_unsigned, True)

        runner_classes = output / 'instrumentation-classes'
        runner_classes.mkdir()
        checked([javac, '-J-Duser.language=en', '-J-Duser.country=US', '-J-Dfile.encoding=UTF-8',
                 '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-proc:none',
                 '-bootclasspath', android_jar, '-d', runner_classes, runner_source],
                'javac R0 instrumentation')
        runner_dex = dex_classes(java, r8_jar, android_jar,
                                 sorted(runner_classes.rglob('*.class')), output / 'instrumentation-dex')
        test_base = output / 'instrumentation-base.apk'
        link_manifest(aapt2, android_jar, test_manifest, test_base)
        test_unsigned = output / 'instrumentation-unsigned.apk'
        test_apk = output / 'instrumentation-acceptance.apk'
        assemble(test_base, runner_dex, abi_inputs, test_unsigned, False)

        password = secrets.token_urlsafe(36)
        alias = 'r0acceptance'
        key_env = os.environ.copy()
        key_env[env_name] = password
        try:
            checked([keytool, '-J-Duser.language=en', '-J-Duser.country=US', '-J-Dfile.encoding=UTF-8',
                     '-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', keystore,
                     '-storepass:env', env_name, '-keypass:env', env_name, '-alias', alias,
                     '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1',
                     '-dname', 'CN=Vega Safe Linux R0 Acceptance Fixture, OU=Local Test, O=Vega Safe, C=US'],
                    'generate short-lived acceptance key', key_env)
        finally:
            key_env.pop(env_name, None)
        sign_apk(java, apksigner_jar, zipalign, target_unsigned, target_apk,
                 keystore, alias, password, env_name)
        sign_apk(java, apksigner_jar, zipalign, test_unsigned, test_apk,
                 keystore, alias, password, env_name)

        report.update({
            'status': 'BUILT_NOT_DEVICE_PASS',
            'targetApk': str(target_apk),
            'targetApkSha256': sha256(target_apk),
            'instrumentationApk': str(test_apk),
            'instrumentationApkSha256': sha256(test_apk),
            'instrumentationRunner': 'dev.wind.agent.linuxr0.LinuxR0AcceptanceInstrumentation',
            'productionProbeSource': str(probe_source),
            'instrumentationSource': str(runner_source),
            'signature': 'verified with a fresh one-day local acceptance key; signing key removed',
        })
    except Exception as error:
        report['error'] = '{}: {}'.format(type(error).__name__, error)
    finally:
        try:
            keystore.unlink(missing_ok=True)
        except OSError as error:
            report['signingKeyCleanupError'] = str(error)
            if report['status'] == 'BUILT_NOT_DEVICE_PASS':
                report['status'] = 'BUILD_FAILED'
                report['error'] = 'Could not remove short-lived acceptance key: ' + str(error)
        write_json(output / 'build-result.json', report)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['status'] == 'BUILT_NOT_DEVICE_PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=['R0', 'R1'], default='R0')
    parser.add_argument('--sdk', type=Path, default=Path(r'D:\HTML\android-sdk'))
    parser.add_argument('--java-home', type=Path, default=Path(r'D:\VSCode'))
    parser.add_argument('--native', type=Path, default=Path('.build-linux-r0/native'))
    parser.add_argument('--assets', type=Path, default=Path('.build-linux-r0/assets'))
    parser.add_argument('--abi', action='append', choices=sorted(ABI_LIBRARIES),
                        help='include only this ABI; repeat to select more than one')
    parser.add_argument('--output', type=Path, required=True, help='must name a fresh directory; it is never reused')
    return build(parser.parse_args())


if __name__ == '__main__':
    sys.exit(main())
