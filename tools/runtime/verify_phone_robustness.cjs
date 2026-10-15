// Source and host verification only. No APK, emulator, device or external model calls.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const root = path.resolve(__dirname, '../..'); process.chdir(root);
const scratch = path.join(root, '.build-phone-robustness');
for (const name of ['production', 'tests', 'json', 'tmp', 'data']) fs.mkdirSync(path.join(scratch, name), { recursive: true });
const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
const upstream = walk('third_party/embedded/kotlin/upstream').filter(f => f.endsWith('.jar') && !f.endsWith('-sources.jar'));
const embedded = walk('third_party/embedded').filter(f => f.endsWith('.jar') && !f.includes('kotlin') && !f.includes('desugar') && !f.endsWith('-sources.jar'));
const web = fs.readFileSync('third_party/webkit/runtime-jars.txt', 'utf8').trim().split(/\r?\n/);
const java = path.resolve('.build-web-access/java');
if (!fs.existsSync(java)) throw Error('Missing host Java classes: .build-web-access/java');
const sdk = path.resolve('assets/embedded/android-35/android.jar');
const compiler = upstream.map(f => path.resolve(f)).join(path.delimiter);
const libraries = [...web, ...upstream, ...embedded].map(f => path.resolve(f));
function run(exe, args, name, allowFailure = false) {
    const log = path.join(scratch, name + '.log'), fd = fs.openSync(log, 'w');
    const r = cp.spawnSync(exe, args, { stdio: ['ignore', fd, fd], env: { ...process.env, TEMP: path.join(scratch, 'tmp'), TMP: path.join(scratch, 'tmp') } });
    fs.closeSync(fd);
    if (r.status !== 0) { console.error(fs.readFileSync(log, 'utf8').slice(-16000)); if (!allowFailure) throw Error(name + ' failed: ' + r.status); }
    console.log(name + (r.status === 0 ? ' PASS' : ' FAIL')); return r.status;
}
run('javac', ['-encoding', 'UTF-8', '-d', path.join(scratch, 'json'), ...walk('tools/json-src').filter(f => f.endsWith('.java'))], 'json-compile');
// Compile against Android's actual JSON signatures; use behavioral JSON only at host runtime.
const base = [sdk, java, ...libraries].join(path.delimiter);
function compile(sources, output, classpath, name, friend = '') {
    const list = path.join(scratch, name + '.args');
    fs.writeFileSync(list, sources.map(f => '"' + path.resolve(f).replaceAll('\\', '/') + '"').join('\n'));
    const args = ['-Xmx3g', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + path.join(scratch, 'tmp'), '-cp', compiler,
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17',
        '-classpath', classpath, '-d', path.join(scratch, output)];
    if (friend) args.push('-Xfriend-paths=' + friend);
    run('java', [...args, '@' + list], name);
}
const production = walk('src').filter(f => /\.(kt|java)$/.test(f));
const sourceHashes = () => Object.fromEntries(production.map(f => [f, require('node:crypto').createHash('sha256').update(fs.readFileSync(f)).digest('hex')]));
const initialSourceHashes = sourceHashes();
const manifest = path.join(scratch, 'production-source-hashes.json');
if (process.argv.includes('--tests-only') && (!fs.existsSync(manifest) || JSON.stringify(JSON.parse(fs.readFileSync(manifest, 'utf8'))) !== JSON.stringify(initialSourceHashes))) {
    throw Error('STALE_PRODUCTION_CLASSES: compile current source before --tests-only');
}
if (!process.argv.includes('--tests-only')) compile(production, 'production', base, 'production-compile');
run('javac', ['-encoding', 'UTF-8', '-proc:none', '-classpath', [path.join(scratch, 'production'), base].join(path.delimiter),
    '-d', path.join(scratch, 'production'), ...production.filter(f => f.endsWith('.java'))], 'production-java-compile');
if (JSON.stringify(sourceHashes()) !== JSON.stringify(initialSourceHashes)) throw Error('PRODUCTION_SOURCE_CHANGED_DURING_COMPILE');
if (!process.argv.includes('--tests-only')) fs.writeFileSync(manifest, JSON.stringify(initialSourceHashes));
const integration = process.argv.includes('--permission-integration-only');
const adbOnly = process.argv.includes('--adb-only');
const suites = integration ? ['MirrorWritebackRegressionTests', 'CapabilitySandboxTests'] : adbOnly ? ['AdbTransportTests', 'AdbToolTests'] : process.argv.includes('--permissions-only') ? ['AgentPermissionTests'] : process.argv.includes('--phone-only') ? ['PhoneHarnessTests'] : ['AgentPermissionTests', 'PhoneHarnessTests', 'ContextHarnessTests', 'AgentLoopTests', 'CoreRegressionTests', 'AdbTransportTests', 'AdbToolTests'];
if (process.argv.includes('--compile-only')) { console.log('FINAL_PRODUCTION_HOST_COMPILE_PASS: ' + production.length + ' sources; no APK/device'); process.exit(0); }
if (!integration && !process.argv.includes('--permissions-only')) suites.push('PhoneBackendTests', 'PhoneWorkflowTests');
const helperSuites = integration ? ['CompanionMirrorIntegrationTests', 'MultiRootIntegrationTests', 'VerificationGateRegressionTests', 'CodingLoopTests'] : [];
compile([...new Set([...suites, ...helperSuites])].map(s => 'tests/dev/wind/agent/' + s + '.kt'), 'tests', [path.join(scratch, 'production'), base].join(path.delimiter), 'tests-compile', path.join(scratch, 'production'));
const runtime = [path.join(scratch, 'json'), path.resolve('.build-linux-r1/testing/offline/stubs-classes'),
    path.join(scratch, 'production'), java, path.join(scratch, 'tests'), ...libraries].join(path.delimiter);
for (const suite of suites) {
    run('java', ['-Xmx2g', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + path.join(scratch, 'tmp'), '-cp', runtime,
        'dev.wind.agent.' + suite, path.join(scratch, 'data')], suite);
    console.log(fs.readFileSync(path.join(scratch, suite + '.log'), 'utf8').trim().split(/\r?\n/).slice(-1)[0]);
}
if (JSON.stringify(sourceHashes()) !== JSON.stringify(initialSourceHashes)) throw Error('PRODUCTION_SOURCE_CHANGED_DURING_TESTS');
if (integration || adbOnly || process.argv.includes('--host-only')) {
    fs.writeFileSync(path.join(scratch, integration ? 'permission-integration-summary.json' : adbOnly ? 'adb-host-summary.json' : 'permission-host-summary.json'), JSON.stringify({
        status: 'SOURCE_AND_HOST_VERIFIED', productionSources: production.length, productionJavaSources: production.filter(f => f.endsWith('.java')).length,
        sourceHashes: 'production-source-hashes.json', sourcesUnchangedDuringVerification: true,
        suites: Object.fromEntries(suites.map(s => [s, fs.readFileSync(path.join(scratch, s + '.log'), 'utf8').trim().split(/\r?\n/).at(-1)])),
        device: 'DEVICE_NOT_RUN', apk: 'NOT_PACKAGED', externalModel: 'NOT_RUN', wholeRepositorySourceRegressions: 'NOT_RUN'
    }, null, 2));
}
if (process.argv.includes('--phone-only')) { console.log('FOCUSED_PHONE_HOST_LOGIC_PASS; notification/IME/device behavior NOT_RUN'); process.exit(0); }
if (process.argv.includes('--permissions-only')) { console.log('FOCUSED_PERMISSION_HOST_LOGIC_PASS; Android/device behavior NOT_RUN'); process.exit(0); }
if (adbOnly) { console.log('FOCUSED_ADB_HOST_LOGIC_PASS; Android/device behavior NOT_RUN'); process.exit(0); }
if (process.argv.includes('--host-only')) { console.log('PRODUCTION_AND_HOST_SUITES_PASS; Android/device behavior NOT_RUN'); process.exit(0); }
if (integration) { console.log('PERMISSION_MIRROR_AND_BROKER_REGRESSIONS_PASS; Android/device behavior NOT_RUN'); process.exit(0); }
const backups = fs.readdirSync('scratch').filter(name => name.startsWith('backup-phone-robustness-')).sort();
const baseline = backups.length ? path.resolve('scratch', backups.at(-1), 'AgentEngine.kt') : '';
const python = process.env.VEPRO_HOST_PYTHON || 'py';
run(python, [...(process.env.VEPRO_HOST_PYTHON ? [] : ['-3']), 'tools/runtime/phone_ocr_payload.py'], 'ocr-payload-verification');
const sourceStatus = run(python, [...(process.env.VEPRO_HOST_PYTHON ? [] : ['-3']), 'tools/runtime/check_phone_source_regressions.py',
    path.join(scratch, 'source-regressions.json'), baseline], 'source-regressions', true);
const source = fs.existsSync(path.join(scratch, 'source-regressions.json')) ? JSON.parse(fs.readFileSync(path.join(scratch, 'source-regressions.json'), 'utf8')) : null;
fs.writeFileSync(path.join(scratch, 'verification-summary.json'), JSON.stringify({
    status: sourceStatus === 0 ? 'SOURCE_AND_HOST_VERIFIED' : 'HOST_COMPILE_AND_JVM_SUITES_PASS_SOURCE_REGRESSIONS_FAILED',
    productionSources: production.length, productionJavaSources: production.filter(f => f.endsWith('.java')).length,
    suites: Object.fromEntries(suites.map(s => [s, fs.readFileSync(path.join(scratch, s + '.log'), 'utf8').trim().split(/\r?\n/).at(-1)])),
    sourceRegressions: source, ocrPayload: { integrity: 'PASS', androidRecognition: 'NOT_RUN' },
    device: 'DEVICE_NOT_RUN', externalModel: 'NOT_RUN', apk: 'NOT_PACKAGED'
}, null, 2));
process.exitCode = sourceStatus === 0 ? 0 : 1;

