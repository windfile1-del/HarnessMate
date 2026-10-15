"""Offline first-party app_process payload build/verification; never assembles an APK."""
import hashlib, json, os, pathlib, shutil, subprocess, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'phone-bridge/src'
ASSETS = ROOT / 'assets/embedded/phone'
BUILD = ROOT / '.build-phone-bridge'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_files():
    return sorted(SOURCE.rglob('*.java')) + [ROOT / 'src/dev/wind/agent/phone/VisualPatchDigest.java']

def sources():
    return {str(p.relative_to(ROOT)).replace('\\', '/'): digest(p) for p in source_files()}

def verify():
    lock = json.loads((ASSETS / 'payload.json').read_text('utf-8'))
    assert lock['protocol'] == 2 and lock['sources'] == sources(), 'PHONE_BRIDGE_SOURCE_STALE: run tools/runtime/build_phone_bridge.py'
    assert lock['sha256'] == digest(ASSETS / 'vega-phone.jar'), 'PHONE_BRIDGE_PAYLOAD_MISMATCH'
    for path, sha in lock['inputs'].items():
        assert digest(ROOT / path) == sha, 'PHONE_BRIDGE_BUILD_INPUT_CHANGED: ' + path
    with zipfile.ZipFile(ASSETS / 'vega-phone.jar') as z:
        assert z.namelist() == ['classes.dex'], 'PHONE_BRIDGE_ARCHIVE_INVALID'
        assert z.read('classes.dex').startswith(b'dex\n'), 'PHONE_BRIDGE_DEX_INVALID'
    if '--apk' in sys.argv:
        apk = pathlib.Path(sys.argv[sys.argv.index('--apk') + 1])
        with zipfile.ZipFile(apk) as z:
            assert z.read('assets/embedded/phone/vega-phone.jar') == (ASSETS / 'vega-phone.jar').read_bytes(), 'PHONE_BRIDGE_APK_PAYLOAD_MISMATCH'
            assert z.read('assets/embedded/phone/payload.json') == (ASSETS / 'payload.json').read_bytes(), 'PHONE_BRIDGE_APK_MANIFEST_MISMATCH'
    print('PHONE_BRIDGE_SOURCE_AND_PAYLOAD_VERIFIED; device NOT_RUN; APK NOT_PACKAGED')

if '--verify' in sys.argv:
    verify()
else:
    BUILD.mkdir(exist_ok=True); ASSETS.mkdir(parents=True, exist_ok=True)
    classes = BUILD / 'classes'
    assert not classes.is_symlink() and classes.resolve().parent == BUILD.resolve(), 'PHONE_BRIDGE_BUILD_DIRECTORY_INVALID'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    sdk = ROOT / 'assets/embedded/android-35/android.jar'
    r8 = ROOT / 'third_party/embedded/r8/r8-8.2.33.jar'
    listing = BUILD / 'sources.args'
    listing.write_text('\n'.join('"' + p.as_posix() + '"' for p in source_files()), encoding='utf-8')
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(sdk), '-d', str(classes), '@' + str(listing)], check=True)
    jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(classes.rglob('*.class')): z.write(p, p.relative_to(classes).as_posix())
    subprocess.run(['java', '-cp', str(r8), 'com.android.tools.r8.D8', '--min-api', '23', '--lib', str(sdk), '--output', str(BUILD), str(jar)], check=True)
    payload = ASSETS / 'vega-phone.jar'
    with zipfile.ZipFile(payload, 'w', zipfile.ZIP_DEFLATED) as z:
        info = zipfile.ZipInfo('classes.dex', (2026, 10, 5, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, (BUILD / 'classes.dex').read_bytes())
    inputs = {p.relative_to(ROOT).as_posix(): digest(p) for p in [sdk, r8, pathlib.Path(__file__)]}
    (ASSETS / 'payload.json').write_bytes((json.dumps({'protocol': 2, 'entrypoint': 'dev.wind.phonebridge.Main', 'sha256': digest(payload), 'sources': sources(), 'inputs': inputs}, indent=2) + '\n').encode('utf-8'))
    verify()

if '--host-tests' in sys.argv:
    test_classes = BUILD / 'tests'; test_classes.mkdir(exist_ok=True)
    test_cp = str(BUILD / 'classes') + os.pathsep + str(ROOT / 'assets/embedded/android-35/android.jar')
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(BUILD / 'classes'),
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/ReceiptLedgerTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + str(BUILD / 'classes'), 'dev.wind.phonebridge.ReceiptLedgerTests', str(BUILD)], check=True)
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', test_cp,
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/VirtualReflectionTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + test_cp, 'dev.wind.phonebridge.VirtualReflectionTests'], check=True)
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(BUILD / 'classes'),
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/BridgeLifecycleTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + str(BUILD / 'classes'), 'dev.wind.phonebridge.BridgeLifecycleTests', str(BUILD)], check=True)
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(BUILD / 'classes'),
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/AccessibilityEventScopeTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + str(BUILD / 'classes'), 'dev.wind.phonebridge.AccessibilityEventScopeTests'], check=True)
    bytecode = subprocess.check_output(['javap', '-c', '-p', '-classpath', str(BUILD / 'classes'), 'dev.wind.phonebridge.Main'], text=True)
    assert 'AccessibilityEvent.getDisplayId:' not in bytecode and 'AccessibilityRecord.getDisplayId:' not in bytecode, 'UNGUARDED_EVENT_DISPLAY_API'
    print('BRIDGE_EVENT_BYTECODE_COMPATIBILITY_PASS; no direct event getDisplayId invocation')
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(BUILD / 'classes'),
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/LaunchCompatibilityTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + str(BUILD / 'classes'), 'dev.wind.phonebridge.LaunchCompatibilityTests'], check=True)
    subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', str(BUILD / 'classes'),
                    '-d', str(test_classes), str(ROOT / 'phone-bridge/tests/WindowRoutingTests.java')], check=True)
    subprocess.run(['java', '-cp', str(test_classes) + os.pathsep + str(BUILD / 'classes'), 'dev.wind.phonebridge.WindowRoutingTests'], check=True)
