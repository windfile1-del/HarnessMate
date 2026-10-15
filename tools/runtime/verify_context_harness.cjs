// Source and host verification only. No APK, emulator, device or external model calls.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const root = path.resolve(__dirname, '../..'); process.chdir(root);
const scratch = path.join(root, '.build-context-harness');
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
function run(exe, args, name) {
    const log = path.join(scratch, name + '.log'), fd = fs.openSync(log, 'w');
    const r = cp.spawnSync(exe, args, { stdio: ['ignore', fd, fd], env: { ...process.env, TEMP: path.join(scratch, 'tmp'), TMP: path.join(scratch, 'tmp') } });
    fs.closeSync(fd);
    if (r.status !== 0) { console.error(fs.readFileSync(log, 'utf8').slice(-16000)); throw Error(name + ' failed: ' + r.status); }
    console.log(name + ' PASS');
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
if (!process.argv.includes('--tests-only')) compile(production, 'production', base, 'production-compile');
const suites = ['ContextHarnessTests', 'AgentLoopTests', 'ModelProviderTests', 'WebProviderCoreTests', 'CoreRegressionTests'];
compile(suites.map(s => 'tests/dev/wind/agent/' + s + '.kt'), 'tests', [path.join(scratch, 'production'), base].join(path.delimiter), 'tests-compile', path.join(scratch, 'production'));
const runtime = [path.join(scratch, 'json'), path.resolve('.build-linux-r1/testing/offline/stubs-classes'), java,
    path.join(scratch, 'production'), path.join(scratch, 'tests'), ...libraries].join(path.delimiter);
for (const suite of suites) {
    run('java', ['-Xmx2g', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + path.join(scratch, 'tmp'), '-cp', runtime,
        'dev.wind.agent.' + suite, path.join(scratch, 'data')], suite);
    console.log(fs.readFileSync(path.join(scratch, suite + '.log'), 'utf8').trim().split(/\r?\n/).slice(-1)[0]);
}
fs.writeFileSync(path.join(scratch, 'verification-summary.json'), JSON.stringify({ status: 'SOURCE_AND_HOST_VERIFIED', productionSources: production.length,
    suites, device: 'DEVICE_NOT_RUN', externalModel: 'NOT_RUN', apk: 'NOT_PACKAGED' }, null, 2));
