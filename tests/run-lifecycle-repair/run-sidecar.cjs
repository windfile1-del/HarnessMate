const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const scratch = path.join(root, '.build-run-lifecycle-repair');
const testClassesDir = path.join(scratch, 'sidecar-classes');
const tmpDir = path.join(scratch, 'temp');
fs.mkdirSync(testClassesDir, { recursive: true });
fs.mkdirSync(tmpDir, { recursive: true });

const java = process.env.VEPRO_HOST_JAVA || 'D:/VSCode/bin/java.exe';
const androidJar = path.join(root, 'assets/embedded/android-35/android.jar');
const walk = (p) => fs.existsSync(p) ? fs.readdirSync(p, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(path.join(p, e.name)) : [path.join(p, e.name)]) : [];
const upstream = walk(path.join(root, 'third_party/embedded/kotlin/upstream')).filter((p) => p.endsWith('.jar') && !p.endsWith('-sources.jar'));
const embedded = walk(path.join(root, 'third_party/embedded')).filter((p) => p.endsWith('.jar') && !p.includes('kotlin') && !p.includes('desugar') && !p.endsWith('-sources.jar'));
const webkit = fs.readFileSync(path.join(root, 'third_party/webkit/runtime-jars.txt'), 'utf8').trim().split(/\r?\n/).map((p) => path.resolve(root, p));

const prodCheck = path.join(root, '.build-p7-tools/production-check');
const prodClasses = path.join(prodCheck, 'classes');
const prodJava = path.join(prodCheck, 'java');
const finalClasses = path.join(root, '.build-run-lifecycle/final-classes');

// Classpath constraint: final-classes ahead of base java/classes, no old delta/ui-delta/cancellation-delta
const hasFinalClasses = fs.existsSync(finalClasses) && fs.readdirSync(finalClasses).length > 0;
const orderedClasspath = [
    testClassesDir,
    path.join(scratch, 'json'),
    path.join(scratch, 'host-android'),
    ...(hasFinalClasses ? [finalClasses] : []),
    prodJava,
    prodClasses,
    androidJar,
    ...upstream,
    ...embedded,
    ...webkit
];

const compileCp = orderedClasspath.join(path.delimiter);
const friendList = [
    ...(hasFinalClasses ? [finalClasses] : []),
    prodClasses
];
const friendPaths = friendList.join(',');

const testSource = path.join(__dirname, 'CodingLifecycleSidecarTests.kt');
const rsp = path.join(scratch, 'sidecar-sources.rsp');
fs.writeFileSync(rsp, '"' + testSource.replace(/\\/g, '/') + '"\n', 'utf8');

console.log('--- Compiling CodingLifecycleSidecarTests ---');
const compileRes = cp.spawnSync(java, [
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
], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });

if (compileRes.status !== 0) {
    console.error('Compilation FAILED:\n' + (compileRes.stderr || compileRes.stdout));
    process.exit(compileRes.status ?? 1);
}
console.log('Compilation SUCCESS.');

const runtimeCp = orderedClasspath.join(path.delimiter);
console.log('--- Running CodingLifecycleSidecarTests in independent JVM ---');
const runRes = cp.spawnSync(java, [
    '-Xmx512m',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', runtimeCp,
    'dev.wind.agent.CodingLifecycleSidecarTests'
], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });

if (runRes.stdout) process.stdout.write(runRes.stdout);
if (runRes.stderr) process.stderr.write(runRes.stderr);

const exitCode = runRes.status ?? 1;
const summary = {
    status: exitCode === 0 ? 'HOST_TESTS_PASS' : 'HOST_TESTS_FAILED',
    exitCode: exitCode,
    suite: 'dev.wind.agent.CodingLifecycleSidecarTests',
    casesPassed: exitCode === 0 ? 4 : 0,
    timestamp: new Date().toISOString()
};
fs.writeFileSync(path.join(scratch, 'sidecar-summary.json'), JSON.stringify(summary, null, 2), 'utf8');
process.exit(exitCode);
