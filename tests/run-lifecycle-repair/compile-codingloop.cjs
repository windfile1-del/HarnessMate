const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const scratch = path.join(root, '.build-run-lifecycle-repair');
const walk = (p) => fs.existsSync(p) ? fs.readdirSync(p, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(path.join(p, e.name)) : [path.join(p, e.name)]) : [];
const upstream = walk(path.join(root, 'third_party/embedded/kotlin/upstream')).filter((p) => p.endsWith('.jar') && !p.endsWith('-sources.jar'));
const embedded = walk(path.join(root, 'third_party/embedded')).filter((p) => p.endsWith('.jar') && !p.includes('kotlin') && !p.includes('desugar') && !p.endsWith('-sources.jar'));
const webkit = fs.readFileSync(path.join(root, 'third_party/webkit/runtime-jars.txt'), 'utf8').trim().split(/\r?\n/).map((p) => path.resolve(root, p));

const finalClasses = path.join(root, '.build-run-lifecycle/final-classes');
const prodCheck = path.join(root, '.build-p7-tools/production-check');
const prodClasses = path.join(prodCheck, 'classes');
const prodJava = path.join(prodCheck, 'java');
const hasFinal = fs.existsSync(finalClasses) && fs.readdirSync(finalClasses).length > 0;

const compileCp = [
    path.join(scratch, 'codingloop-classes'),
    path.join(scratch, 'json'),
    path.join(scratch, 'host-android'),
    ...(hasFinal ? [finalClasses] : []),
    prodJava,
    prodClasses,
    path.join(root, 'assets/embedded/android-35/android.jar'),
    ...upstream,
    ...embedded,
    ...webkit
].join(path.delimiter);

const friendPaths = [
    ...(hasFinal ? [finalClasses] : []),
    prodClasses
].join(',');

const r = cp.spawnSync('D:/VSCode/bin/java.exe', [
    '-Xmx2g',
    '-Dfile.encoding=UTF-8',
    '-cp', upstream.join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib',
    '-no-reflect',
    '-nowarn',
    '-jvm-target', '17',
    '-Xfriend-paths=' + friendPaths,
    '-classpath', compileCp,
    '-d', path.join(scratch, 'codingloop-classes'),
    path.join(root, 'tests/dev/wind/agent/CodingLoopTests.kt')
], { encoding: 'utf8' });

fs.writeFileSync(path.join(scratch, 'codingloop-compile.log'), 'STATUS: ' + r.status + '\nERR:\n' + r.stderr + '\nOUT:\n' + r.stdout, 'utf8');
console.log('COMPILE STATUS:', r.status);
