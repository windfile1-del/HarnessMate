// Real production classes; no replacement installers, APKs or device connections.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..'); process.chdir(root);
const scratch = path.join(root, '.build-p7-tools/host-tests');
fs.mkdirSync(scratch, { recursive: true });
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const java = process.env.VEPRO_HOST_JAVA || 'java', javac = process.env.VEPRO_HOST_JAVAC || 'javac';
const production = path.join(root, '.build-p7-tools/production-check');
const summaryFile = path.join(production, 'summary.json');
if (!fs.existsSync(summaryFile)) throw Error('Run tools/runtime/verify_p7_runtime_skills.py first');
const summary = JSON.parse(fs.readFileSync(summaryFile, 'utf8'));
for (const [file, sha] of Object.entries(summary.sourceHashes)) {
    if (crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex') !== sha) throw Error('STALE_PRODUCTION: ' + file);
}
const upstream = walk('third_party/embedded/kotlin/upstream').filter(f => f.endsWith('.jar') && !f.endsWith('-sources.jar'));
const libraries = [...upstream, ...walk('third_party/embedded').filter(f => f.endsWith('.jar') && !f.includes('kotlin') && !f.includes('desugar') && !f.endsWith('-sources.jar')),
    ...fs.readFileSync('third_party/webkit/runtime-jars.txt', 'utf8').trim().split(/\r?\n/)].map(f => path.resolve(f));
const compileClasspath = [path.join(production, 'classes'), path.join(production, 'java'), path.resolve('assets/embedded/android-35/android.jar'), ...libraries].join(path.delimiter);
function run(name, exe, args) {
    const log = path.join(scratch, name + '.log'), fd = fs.openSync(log, 'w');
    const r = cp.spawnSync(exe, args, { stdio: ['ignore', fd, fd] }); fs.closeSync(fd);
    const output = fs.readFileSync(log, 'utf8');
    if (r.status !== 0) { process.stderr.write(output); throw Error(name + ' failed: ' + r.status); }
    console.log(name + ' PASS'); return output;
}
for (const dir of ['json', 'tests']) fs.mkdirSync(path.join(scratch, dir), { recursive: true });
run('json-compile', javac, ['-encoding', 'UTF-8', '-d', path.join(scratch, 'json'), ...walk('tools/json-src').filter(f => f.endsWith('.java'))]);
const argsFile = path.join(scratch, 'tests.rsp');
fs.writeFileSync(argsFile, [...walk(__dirname).filter(f => f.endsWith('.kt')), path.join(root, 'tests/dev/wind/agent/CapabilitySandboxTests.kt')].map(f => '"' + f.replaceAll('\\', '/') + '"').join('\n'));
run('tests-compile', java, ['-Xmx1g', '-Dfile.encoding=UTF-8', '-cp', upstream.map(f => path.resolve(f)).join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', compileClasspath, '-d', path.join(scratch, 'tests'), '@' + argsFile]);
const results = {};
for (const suite of ['skills.SkillStoreTests', 'runtime.linux.RuntimeToolCatalogTests', 'runtime.linux.RuntimeToolAuthorityTests', 'CapabilitySandboxTests']) {
    const output = run(suite, java, ['-Dfile.encoding=UTF-8', '-cp', [path.join(scratch, 'json'), path.join(scratch, 'tests'), compileClasspath].join(path.delimiter), 'dev.wind.agent.' + suite]);
    results[suite] = output.trim(); console.log(output.trim());
}
fs.writeFileSync(path.join(scratch, 'summary.json'), JSON.stringify({ status: 'SOURCE_AND_HOST_VERIFIED', suites: results, device: 'DEVICE_NOT_RUN', apk: 'NOT_PACKAGED' }, null, 2));
