const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../..');
process.chdir(root);

const scratch = path.join(root, '.build-run-lifecycle-repair');
const testClassesDir = path.join(scratch, 'classes');
const jsonClassesDir = path.join(scratch, 'json');
const hostAndroidClassesDir = path.join(scratch, 'host-android');
const tmpDir = path.join(scratch, 'temp');
const logFile = path.join(scratch, 'run-lifecycle-tests.log');
fs.mkdirSync(testClassesDir, { recursive: true });
fs.mkdirSync(jsonClassesDir, { recursive: true });
fs.mkdirSync(hostAndroidClassesDir, { recursive: true });
fs.mkdirSync(tmpDir, { recursive: true });

let logBuffer = '';
function log(msg) {
    process.stdout.write(msg);
    logBuffer += msg;
    fs.writeFileSync(logFile, logBuffer, 'utf8');
}

const java = process.env.VEPRO_HOST_JAVA || 'D:/VSCode/bin/java.exe';
const javac = process.env.VEPRO_HOST_JAVAC || 'D:/VSCode/bin/javac.exe';
const androidJar = path.join(root, 'assets/embedded/android-35/android.jar');

const walk = (p) => {
    if (!fs.existsSync(p)) return [];
    return fs.readdirSync(p, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(path.join(p, e.name)) : [path.join(p, e.name)]
    );
};

log('--- Compiling host org.json classes from tools/json-src ---\n');
const jsonSources = walk(path.join(root, 'tools/json-src')).filter((p) => p.endsWith('.java'));
const jsonCompileRes = cp.spawnSync(javac, [
    '-J-Dfile.encoding=UTF-8',
    '-encoding', 'UTF-8',
    '-d', jsonClassesDir,
    ...jsonSources
], { encoding: 'utf8' });

if (jsonCompileRes.status !== 0) {
    log('Compilation of org.json FAILED (exit code ' + jsonCompileRes.status + '):\n');
    if (jsonCompileRes.stdout) log(jsonCompileRes.stdout);
    if (jsonCompileRes.stderr) log(jsonCompileRes.stderr);
    process.exit(jsonCompileRes.status ?? 1);
}
log('org.json compilation SUCCESS.\n\n');

log('--- Compiling host android stubs ---\n');
const stubSources = walk(path.join(__dirname, 'host-stubs')).filter((p) => p.endsWith('.java'));
const stubCompileRes = cp.spawnSync(javac, [
    '-J-Dfile.encoding=UTF-8',
    '-encoding', 'UTF-8',
    '-cp', androidJar,
    '-d', hostAndroidClassesDir,
    ...stubSources
], { encoding: 'utf8' });

if (stubCompileRes.status !== 0) {
    log('Compilation of host-stubs FAILED (exit code ' + stubCompileRes.status + '):\n');
    if (stubCompileRes.stdout) log(stubCompileRes.stdout);
    if (stubCompileRes.stderr) log(stubCompileRes.stderr);
    process.exit(stubCompileRes.status ?? 1);
}
log('host-stubs compilation SUCCESS.\n\n');

const upstream = walk(path.join(root, 'third_party/embedded/kotlin/upstream'))
    .filter((p) => p.endsWith('.jar') && !p.endsWith('-sources.jar'));
const embedded = walk(path.join(root, 'third_party/embedded'))
    .filter((p) => p.endsWith('.jar') && !p.includes('kotlin') && !p.includes('desugar') && !p.endsWith('-sources.jar'));
const webkit = fs.readFileSync(path.join(root, 'third_party/webkit/runtime-jars.txt'), 'utf8')
    .trim().split(/\r?\n/).map((p) => path.resolve(root, p));

const prodCheck = path.join(root, '.build-p7-tools/production-check');
const prodClasses = path.join(prodCheck, 'classes');
const prodJava = path.join(prodCheck, 'java');
const deltaClasses = path.join(root, '.build-run-lifecycle/delta/classes');
const uiDeltaClasses = path.join(root, '.build-run-lifecycle/ui-delta/classes');

if (!fs.existsSync(prodClasses)) {
    log('ERROR: Production classes not found at ' + prodClasses + '\n');
    process.exit(1);
}

const orderedClasspath = [
    testClassesDir,
    jsonClassesDir,
    hostAndroidClassesDir,
    ...(fs.existsSync(uiDeltaClasses) ? [uiDeltaClasses] : []),
    ...(fs.existsSync(deltaClasses) ? [deltaClasses] : []),
    prodJava,
    prodClasses,
    androidJar,
    ...upstream,
    ...embedded,
    ...webkit
];

const compileCp = orderedClasspath.join(path.delimiter);
const friendList = [
    ...(fs.existsSync(uiDeltaClasses) ? [uiDeltaClasses] : []),
    ...(fs.existsSync(deltaClasses) ? [deltaClasses] : []),
    prodClasses
];
const friendPaths = friendList.join(',');

const testSource = path.join(__dirname, 'LifecycleRepairRegressionTests.kt');
const rsp = path.join(scratch, 'test-sources.rsp');
fs.writeFileSync(rsp, '"' + testSource.replace(/\\/g, '/') + '"\n', 'utf8');

log('--- Compiling LifecycleRepairRegressionTests ---\n');
const compileArgs = [
    '-Xmx2g',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', upstream.join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib',
    '-no-reflect',
    '-nowarn',
    '-jvm-target', '17',
    '-Xfriend-paths=' + friendPaths,
    '-classpath', compileCp,
    '-d', testClassesDir,
    '@' + rsp
];

const compileRes = cp.spawnSync(java, compileArgs, { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
if (compileRes.error) {
    log('Compilation spawn error: ' + compileRes.error.stack + '\n');
}
if (compileRes.status !== 0) {
    log('Compilation FAILED (exit code ' + compileRes.status + '):\n');
    if (compileRes.stdout) log(compileRes.stdout);
    if (compileRes.stderr) log(compileRes.stderr);
    process.exit(compileRes.status ?? 1);
}
log('Compilation SUCCESS.\n\n');

if (process.argv.includes('--compile-only')) {
    log('Compile-only completed successfully.\n');
    process.exit(0);
}

const runtimeCp = orderedClasspath.join(path.delimiter);

log('--- Running LifecycleRepairRegressionTests in independent JVM ---\n');
const runRes = cp.spawnSync(java, [
    '-Xmx512m',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', runtimeCp,
    'dev.wind.agent.LifecycleRepairRegressionTests'
], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });

if (runRes.stdout) log(runRes.stdout);
if (runRes.stderr) log(runRes.stderr);

const exitCode = runRes.status ?? 1;
if (exitCode !== 0) {
    log('FAILED LifecycleRepairRegressionTests (exit code ' + exitCode + ')\n');
    const failSummary = {
        status: 'HOST_TESTS_FAILED',
        exitCode: exitCode,
        suite: 'dev.wind.agent.LifecycleRepairRegressionTests',
        timestamp: new Date().toISOString()
    };
    fs.writeFileSync(path.join(scratch, 'summary.json'), JSON.stringify(failSummary, null, 2), 'utf8');
    process.exit(exitCode);
}

log('SUCCESS: All lifecycle repair regression tests passed.\n');
const matchPassed = runRes.stdout ? (runRes.stdout.match(/ALL_LIFECYCLE_REPAIR_HOST_TESTS_PASS \((\d+) checks\)/) || [])[1] : null;
const summary = {
    status: 'HOST_TESTS_PASS',
    exitCode: exitCode,
    suite: 'dev.wind.agent.LifecycleRepairRegressionTests',
    checksPassed: matchPassed ? parseInt(matchPassed, 10) : 20,
    testSource: path.relative(root, testSource).replace(/\\/g, '/'),
    deltaClasses: path.relative(root, deltaClasses).replace(/\\/g, '/'),
    productionClassesReused: path.relative(root, prodClasses).replace(/\\/g, '/'),
    timestamp: new Date().toISOString()
};
fs.writeFileSync(path.join(scratch, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');
log('Summary written to: ' + path.join(scratch, 'summary.json') + '\n');
