const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../..');
process.chdir(root);

function walk(dir) {
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e =>
        e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]
    );
}

const scratch = path.join(root, '.build-web-access');
const focusedClasses = path.join(scratch, 'focused-classes');
fs.mkdirSync(focusedClasses, { recursive: true });
const tmpDir = path.join(scratch, 'tmp');
fs.mkdirSync(tmpDir, { recursive: true });

const finalClasses = path.join(scratch, 'final');
const mainClasses = path.join(scratch, 'main');
const javaClasses = path.join(scratch, 'java');

// Android behavioral stubs (avoids Stub! runtime exceptions on Context)
const stubsClasses = path.resolve('.build-linux-r1/testing/offline/stubs-classes');

// JSON classes
const jsonClasses = fs.existsSync(path.join(scratch, 'json'))
    ? path.join(scratch, 'json')
    : path.join(root, '.build-web-providers-1791004187375', 'json');

const upstream = walk('third_party/embedded/kotlin/upstream')
    .filter(f => f.endsWith('.jar') && !f.endsWith('-sources.jar'));
const web = fs.readFileSync('third_party/webkit/runtime-jars.txt', 'utf8')
    .trim().split(/\r?\n/);
const embedded = walk('third_party/embedded')
    .filter(f => f.endsWith('.jar') && !f.includes('kotlin') && !f.includes('desugar') && !f.endsWith('-sources.jar'));

const compileClasspath = [
    focusedClasses,
    finalClasses,
    mainClasses,
    javaClasses,
    jsonClasses,
    stubsClasses,
    'assets/embedded/android-35/android.jar',
    ...web,
    ...upstream,
    ...embedded
].map(f => path.resolve(f)).join(path.delimiter);

const sources = [
    'tests/dev/wind/agent/SearchProviderNetworkTests.kt',
    'tests/dev/wind/agent/WebAuthorizedFetchTests.kt',
    'tests/web-access-repair/MockHttpConnection.kt',
    'tests/web-access-repair/WebHttpTransportTests.kt',
    'tests/web-access-repair/WebCapabilityRegressionTests.kt',
    'tests/web-access-repair/SearchProviderParsingAndApiTests.kt',
    'tests/web-access-repair/SearchPrefsAndManagerTests.kt',
    'tests/web-access-repair/WebRenderFetchedPageTests.kt'
].map(f => path.resolve(f));

const argsFile = path.join(scratch, 'focused-sources.args');
fs.writeFileSync(argsFile, sources.map(f => '"' + f.replaceAll('\\', '/') + '"').join('\n'), 'utf8');

const compilerCp = upstream.map(f => path.resolve(f)).join(path.delimiter);

console.log('Compiling focused web transport sources & test suites...');
const compileLog = path.join(scratch, 'focused-compile.log');
const logFd = fs.openSync(compileLog, 'w');

const friendPaths = [path.resolve(finalClasses), path.resolve(mainClasses)].join(',');

const compileResult = cp.spawnSync('java', [
    '-Xmx2g',
    '-Dfile.encoding=UTF-8',
    '-Djava.io.tmpdir=' + tmpDir,
    '-cp', compilerCp,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib',
    '-no-reflect',
    '-jvm-target', '17',
    '-classpath', compileClasspath,
    '-Xfriend-paths=' + friendPaths,
    '-d', focusedClasses,
    '@' + argsFile
], { stdio: ['ignore', logFd, logFd] });
fs.closeSync(logFd);

if (compileResult.status !== 0) {
    console.error('Compilation failed with exit code ' + compileResult.status);
    const out = fs.readFileSync(compileLog, 'utf8');
    console.error(out.slice(-8000));
    process.exit(compileResult.status ?? 1);
}
console.log('Focused compilation SUCCESS.\n');

const testSuites = [
    'dev.wind.agent.testing.WebHttpTransportTests',
    'dev.wind.agent.testing.WebCapabilityRegressionTests',
    'dev.wind.agent.testing.SearchProviderParsingAndApiTests',
    'dev.wind.agent.testing.SearchPrefsAndManagerTests',
    'dev.wind.agent.testing.WebRenderFetchedPageTests',
    'dev.wind.agent.SearchProviderNetworkTests',
    'dev.wind.agent.WebAuthorizedFetchTests'
];

// Runtime classpath: focused classes ahead of final/main/java classes, ahead of JSON + behavioral stubs, ahead of android.jar
const runtimeCp = [
    focusedClasses,
    finalClasses,
    mainClasses,
    javaClasses,
    jsonClasses,
    stubsClasses,
    compileClasspath
].join(path.delimiter);

const hostLogPath = path.join(scratch, 'host-tests.log');
const hostLogStream = fs.createWriteStream(hostLogPath, { flags: 'w', encoding: 'utf8' });
function logOut(msg) {
    process.stdout.write(msg);
    hostLogStream.write(msg);
}

let allPassed = true;
for (const suite of testSuites) {
    logOut('=== Running ' + suite + ' ===\n');
    const res = cp.spawnSync('java', [
        '-Dfile.encoding=UTF-8',
        '-Djava.io.tmpdir=' + tmpDir,
        '-cp', runtimeCp,
        suite
    ], { encoding: 'utf8' });
    if (res.stdout) logOut(res.stdout);
    if (res.stderr) logOut(res.stderr);
    if (res.status !== 0) {
        logOut('FAILED: ' + suite + ' (exit code ' + res.status + ')\n');
        allPassed = false;
    } else {
        logOut('PASSED: ' + suite + '\n\n');
    }
}
hostLogStream.end();

if (!allPassed) {
    console.error('Some test suites failed.');
    process.exit(1);
}
console.log('ALL FOCUSED WEB ACCESS & REGRESSION TESTS PASSED.');
