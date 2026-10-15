const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../..');
process.chdir(root);

const scratch = path.join(root, '.build-run-lifecycle-repair');
const testClassesDir = path.join(scratch, 'codingloop-classes');
const jsonClassesDir = path.join(scratch, 'json');
const hostAndroidClassesDir = path.join(scratch, 'host-android');
const tmpDir = path.join(scratch, 'temp');
fs.mkdirSync(testClassesDir, { recursive: true });
fs.mkdirSync(tmpDir, { recursive: true });

const java = process.env.VEPRO_HOST_JAVA || 'D:/VSCode/bin/java.exe';
const javac = process.env.VEPRO_HOST_JAVAC || 'D:/VSCode/bin/javac.exe';
const androidJar = path.join(root, 'assets/embedded/android-35/android.jar');

const walk = (p) => {
    if (!fs.existsSync(p)) return [];
    return fs.readdirSync(p, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(path.join(p, e.name)) : [path.join(p, e.name)]
    );
};

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

const testSource = path.join(root, 'tests/dev/wind/agent/CodingLoopTests.kt');
const rsp = path.join(scratch, 'codingloop-sources.rsp');
fs.writeFileSync(rsp, '"' + testSource.replace(/\\/g, '/') + '"\n', 'utf8');

console.log('--- Compiling CodingLoopTests ---');
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
console.log('--- Running CodingLoopTests in independent JVM ---');
const runRes = cp.spawnSync(java, [
    '-Xmx512m',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', runtimeCp,
    'dev.wind.agent.CodingLoopTests'
], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });

if (runRes.stdout) process.stdout.write(runRes.stdout);
if (runRes.stderr) process.stderr.write(runRes.stderr);

const exitCode = runRes.status ?? 1;
if (exitCode !== 0) {
    console.error('FAILED CodingLoopTests (exit ' + exitCode + ')');
    process.exit(exitCode);
}
console.log('SUCCESS: CodingLoopTests suite PASSED.');
