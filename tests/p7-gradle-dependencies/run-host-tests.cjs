const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..'); process.chdir(root);
const scratch = path.join(root, '.build-p7-gradle/host-tests'); fs.mkdirSync(scratch, {recursive:true});
const base = path.join(root, '.build-p7-tools/production-check');
const manifest = JSON.parse(fs.readFileSync(path.join(base, 'summary.json'), 'utf8'));
function verifySources() { for (const [p,h] of Object.entries(manifest.sourceHashes)) {
  if (crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex') !== h) throw Error('STALE_SOURCE '+p);
} }
verifySources();
const walk = p => fs.readdirSync(p,{withFileTypes:true}).flatMap(e => e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const upstream = walk(path.join(root,'third_party/embedded/kotlin/upstream')).filter(p=>p.endsWith('.jar')&&!p.endsWith('-sources.jar'));
const libs = [...upstream,...walk(path.join(root,'third_party/embedded')).filter(p=>p.endsWith('.jar')&&!p.includes('kotlin')&&!p.includes('desugar')&&!p.endsWith('-sources.jar')),
 ...fs.readFileSync('third_party/webkit/runtime-jars.txt','utf8').trim().split(/\r?\n/).map(p=>path.resolve(p))];
const android = path.join(root,'assets/embedded/android-35/android.jar');
const json = path.join(root,'.build-p7-tools/host-tests/json');
fs.mkdirSync(json,{recursive:true});
const javac=process.env.VEPRO_HOST_JAVAC || 'D:/VSCode/bin/javac.exe';
const jsonCompile=cp.spawnSync(javac,['-encoding','UTF-8','-d',json,...walk(path.join(root,'tools/json-src')).filter(p=>p.endsWith('.java'))],{encoding:'utf8'});
if(jsonCompile.status!==0)throw Error(jsonCompile.stderr);
const classes = path.join(scratch,'classes'); fs.mkdirSync(classes,{recursive:true});
const stubs = path.join(scratch,'host-android'); fs.mkdirSync(stubs,{recursive:true});
const java = process.env.VEPRO_HOST_JAVA || 'D:/VSCode/bin/java.exe';
function run(name,args,cwd=root,success=true) {
 const log = path.join(scratch,name+'.log'), fd=fs.openSync(log,'w');
 const r=cp.spawnSync(java,args,{cwd,stdio:['ignore',fd,fd],timeout:180000});fs.closeSync(fd);
 const output=fs.readFileSync(log,'utf8');
 if(r.error || (success ? r.status!==0 : r.status===0)) {process.stderr.write(output);throw r.error || Error(name+' unexpected exit '+r.status);}
 console.log(name+' PASS');return output;
}
const prod = [path.join(base,'classes'),path.join(base,'java')];
const stubSources=walk(path.join(__dirname,'host-android')).filter(p=>p.endsWith('.java'));
const stubCompile=cp.spawnSync(javac,['-encoding','UTF-8','-cp',android,'-d',stubs,...stubSources],{encoding:'utf8'});
if(stubCompile.status!==0)throw Error(stubCompile.stderr);
run('tests-compile',['-Xmx1g','-Dfile.encoding=UTF-8','-cp',upstream.join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
 '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',[android,...prod,json,...libs].join(path.delimiter),'-d',classes,
 path.join(__dirname,'GradleDependencyTests.kt'),path.join(__dirname,'MirrorSupportFilesTests.kt'),
 path.join(root,'tests/dev/wind/agent/CompanionMirrorIntegrationTests.kt'),path.join(root,'tests/dev/wind/agent/MultiRootIntegrationTests.kt'),
 path.join(root,'tests/p7-project-packages/ProjectPackageTests.kt')]);
const runtime=[classes,json,stubs,...prod,android,...libs].join(path.delimiter);
const fixture=path.join(scratch,'fixture-'+Date.now());
const planner=run('planner',['-Dfile.encoding=UTF-8','-cp',runtime,'dev.wind.agent.packages.GradleDependencyTests',fixture]);
const legacy=run('npm-pip-regression',['-Dfile.encoding=UTF-8','-cp',runtime,'dev.wind.agent.packages.ProjectPackageTests']);
const mirror=run('mirror-support-files',['-Dfile.encoding=UTF-8','-cp',runtime,'dev.wind.agent.MirrorSupportFilesTests',path.join(scratch,'mirror-'+Date.now())]);
const plans=JSON.parse(fs.readFileSync(path.join(fixture,'plans.json'),'utf8'));
const launcher=process.env.HARNESSMATE_HOST_GRADLE_LAUNCHER || 'C:/Users/windfile/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib/gradle-launcher-8.5.jar';
if(!fs.existsSync(launcher))throw Error('Set HARNESSMATE_HOST_GRADLE_LAUNCHER to an installed host Gradle launcher jar');
const temp=path.join(scratch,'temp');fs.mkdirSync(temp,{recursive:true});
function gradle(name,success=true,label=name) {return run('gradle-'+label,['-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+temp,'-cp',launcher,'org.gradle.launcher.GradleMain',...plans[name]],plans.project,success);}
function result(output){const line=output.split(/\r?\n/).find(l=>l.startsWith('HARNESS_MATE_DEPENDENCIES='));if(!line)throw Error('Missing structured result');return JSON.parse(line.split('=').slice(1).join('='));}
let checks=0;
function check(value,reason){checks++;if(!value)throw Error(reason);}
const listed=result(gradle('list')); check(listed.projects.some(p=>p.module===':'&&p.configurations.includes('runtimeClasspath')),'Resolvable configuration discovery');
function verifyArtifacts(r){check(r.state==='RESOLVED'&&r.trusted_publisher===false,'Resolution does not imply publisher trust');
 const artifacts=r.configurations.flatMap(c=>c.artifacts);check(artifacts.length===2,'AAR and transitive JAR must materialize');
 for(const [file,hash] of Object.entries(plans.expected))check(artifacts.some(a=>a.file===file&&a.sha256===hash&&a.bytes>0),'Actual artifact bytes '+file);
}
verifyArtifacts(result(gradle('resolve')));
verifyArtifacts(result(gradle('lock')));
const lock=fs.readFileSync(path.join(plans.project,'gradle.lockfile'),'utf8');
check(lock.includes('fixture.android:android-ui:1.0=runtimeClasspath')&&lock.includes('fixture.android:helper:1.0=runtimeClasspath'),'Official Gradle lock records transitive dependency');
verifyArtifacts(result(gradle('check')));
check(gradle('bad-config',false).includes('GRADLE_CONFIGURATION_NOT_RESOLVABLE'),'Invalid configuration fails explicitly');
const build=path.join(plans.project,'build.gradle'),saved=fs.readFileSync(build,'utf8');
try { fs.writeFileSync(build,saved.replace('android-ui:1.0','missing:9.0')); const failure=gradle('check',false,'offline-missing');
 check(/Could not resolve|Could not find|dependency lock state/.test(failure),'Unavailable offline dependencies fail, not success');
} finally {fs.writeFileSync(build,saved);}
verifySources();
fs.writeFileSync(path.join(scratch,'summary.json'),JSON.stringify({status:'SOURCE_AND_HOST_VERIFIED',sources:Object.keys(manifest.sourceHashes).length,
 planner:planner.trim(),legacy:legacy.trim(),mirror:mirror.trim(),actualGradleChecks:checks,fixture,apk:'NOT_PACKAGED',device:'DEVICE_NOT_RUN',
 scope:'Local Maven fixture, production init script, real host Gradle; not AGP resource merge or Android device acceptance'},null,2));
console.log(planner.trim());console.log(legacy.trim());console.log('Actual Gradle integration PASS ('+checks+' assertions)');
