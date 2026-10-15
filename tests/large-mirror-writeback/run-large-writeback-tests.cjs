const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../..');
process.chdir(root);

const scratch = path.join(root, '.build-large-writeback');
const testsDir = path.join(scratch, 'tests');
const tmpDir = path.join(scratch, 'temp');
const logFile = path.join(scratch, 'unit-tests.log');
fs.mkdirSync(testsDir, { recursive: true });
fs.mkdirSync(tmpDir, { recursive: true });

const logStream = fs.createWriteStream(logFile, { flags: 'w', encoding: 'utf8' });
function log(msg) {
    process.stdout.write(msg);
    logStream.write(msg);
}

const supportCp = fs.readFileSync(path.join(scratch, 'support-cp.txt'), 'utf8').trim();
const productionDir = path.join(scratch, 'production');
const javaDir = path.join(scratch, 'java');
const stubsDir = path.resolve('.build-linux-r1/testing/offline/stubs-classes');
const jsonDir = path.resolve('.build-phone-robustness/json');

const compileCp = [
    testsDir,
    jsonDir,
    productionDir,
    javaDir,
    stubsDir,
    supportCp
].join(path.delimiter);

const sources = [
    path.join(root, 'tests/dev/wind/agent/MultiRootIntegrationTests.kt'),
    path.join(root, 'tests/dev/wind/agent/CompanionMirrorIntegrationTests.kt'),
    path.join(root, 'tests/dev/wind/agent/MirrorWritebackRegressionTests.kt'),
    path.join(root, 'tests/large-mirror-writeback/LargeMirrorWritebackTests.kt')
];

const argsFile = path.join(scratch, 'test-sources.args');
fs.writeFileSync(argsFile, sources.map(s => JSON.stringify(s.replace(/\\/g, '/'))).join('\n'), 'utf8');

const compilerCp = fs.existsSync(path.join(root, '.build-web-access/compiler-cp.txt'))
    ? fs.readFileSync(path.join(root, '.build-web-access/compiler-cp.txt'), 'utf8').trim()
    : path.join(root, 'third_party/embedded/kotlin/upstream/*');

log('--- Compiling test suites with K2JVMCompiler ---\n');
const compileRes = cp.spawnSync('java', [
    '-Xmx2g',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', compilerCp,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib',
    '-no-reflect',
    '-nowarn',
    '-jvm-target', '17',
    '-Xfriend-paths=' + productionDir,
    '-classpath', compileCp,
    '-d', testsDir,
    '@' + argsFile
], { encoding: 'utf8' });

if (compileRes.status !== 0) {
    log('Compilation FAILED (exit code ' + compileRes.status + '):\n');
    if (compileRes.stdout) log(compileRes.stdout);
    if (compileRes.stderr) log(compileRes.stderr);
    logStream.end();
    process.exit(compileRes.status ?? 1);
}
log('Compilation SUCCESS.\n\n');

const runtimeCp = [
    testsDir,
    jsonDir,
    productionDir,
    javaDir,
    stubsDir,
    supportCp
].join(path.delimiter);

const suites = [
    { name: 'dev.wind.agent.LargeMirrorWritebackTests', xmx: '-Xmx96m' },
    { name: 'dev.wind.agent.MirrorWritebackRegressionTests', xmx: '-Xmx96m' }
];

let allPassed = true;
for (const suite of suites) {
    log('=== Running ' + suite.name + ' with ' + suite.xmx + ' ===\n');
    const res = cp.spawnSync('java', [
        suite.xmx,
        '-Dfile.encoding=UTF-8',
        '-Djava.io.tmpdir=' + tmpDir,
        '-cp', runtimeCp,
        suite.name
    ], { encoding: 'utf8' });

    if (res.stdout) log(res.stdout);
    if (res.stderr) log(res.stderr);

    if (res.status !== 0) {
        log('FAILED: ' + suite.name + ' (exit code ' + res.status + ')\n\n');
        allPassed = false;
    } else {
        log('PASSED: ' + suite.name + '\n\n');
    }
}

if (!allPassed) {
    log('Some suites failed.\n');
    logStream.end();
    process.exit(1);
}
log('ALL TARGET TEST SUITES PASSED.\n');
logStream.end();
