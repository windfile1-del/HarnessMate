/* Exact production compilation and stage host regressions. Emulator acceptance is recorded separately. */
const fs=require('node:fs'), path=require('node:path'), cp=require('node:child_process'), crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'); process.chdir(root);
const scratch=path.join(root,'.build-mate-memory/host'); fs.mkdirSync(scratch,{recursive:true});
const walk=d=>fs.existsSync(d)?fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name)]):[];
const java=process.env.VEPRO_HOST_JAVA || 'java', javac=process.env.VEPRO_HOST_JAVAC || 'javac';
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const supportInput=[...walk('tools/json-src'),...walk('tests/run-lifecycle-repair/host-stubs'),'tools/runtime/verify_mate_memory.cjs'].filter(f=>/\.(java|cjs)$/.test(f));
const supportHashes=Object.fromEntries(supportInput.map(f=>[f,hash(f)]));
const sdk=path.resolve('assets/embedded/android-35/android.jar');
const upstream=walk('third_party/embedded/kotlin/upstream').filter(f=>f.endsWith('.jar')&&!f.endsWith('-sources.jar')).map(f=>path.resolve(f));
const jars=walk('third_party/embedded').filter(f=>f.endsWith('.jar')&&!f.includes('kotlin')&&!f.includes('desugar')&&!f.endsWith('-sources.jar')).map(f=>path.resolve(f));
const web=fs.readFileSync('third_party/webkit/runtime-jars.txt','utf8').trim().split(/\r?\n/).map(f=>path.resolve(f));
const stdlib=path.resolve('third_party/embedded/kotlin/upstream/kotlin-stdlib-1.9.20.jar');
function run(exe,args,label) {
    const log=path.join(scratch,label+'.log'), fd=fs.openSync(log,'w');
    const r=cp.spawnSync(exe,args,{stdio:['ignore',fd,fd]}); fs.closeSync(fd);
    if(r.status!==0) { console.error(fs.readFileSync(log,'utf8').slice(-16000)); throw Error(label+' failed: '+r.status); }
    console.log(label+' PASS'); return fs.readFileSync(log,'utf8');
}
const generated=path.join(scratch,'gen/dev/wind/agent/R.java'); fs.mkdirSync(path.dirname(generated),{recursive:true});
const groups={}; for(const m of fs.readFileSync('res/values/public.xml','utf8').matchAll(/<public type="([^"]+)" name="([^"]+)" id="([^"]+)"/g)) (groups[m[1]]??=[]).push([m[2],m[3]]);
fs.writeFileSync(generated,'package dev.wind.agent; public final class R {\n'+Object.entries(groups).map(([type,entries])=>'public static final class '+type+' {\n'+entries.map(([n,id])=>'public static final int '+n+'='+id+';').join('\n')+'\n}').join('\n')+'\n}\n');
const sources=[...walk('src'),...walk('runtime-companion/src'),generated].filter(f=>/\.(kt|java)$/.test(f)).sort();
const extraSources=()=>["AndroidManifest.xml",...walk("assets/skills"),...walk("assets/browser"),...walk("phone-bridge/src").filter(f=>f.endsWith('.java'))].sort();
const snapshot=()=>Object.fromEntries([...walk("src"),...walk("runtime-companion/src"),generated].filter(f=>/\.(kt|java)$/.test(f)).sort().concat(extraSources()).map(f=>[path.relative(root,f).replaceAll('\\','/'),hash(f)]));
const initial=snapshot(), manifest=path.join(scratch,'source-hashes.json');
const summaryFile=path.join(scratch,'verification-summary.json');
if(fs.existsSync(summaryFile))fs.unlinkSync(summaryFile);
const classes=path.join(scratch,'production'), json=path.join(scratch,'json'), tests=path.join(scratch,'tests');
for(const d of [classes,json,tests]) fs.mkdirSync(d,{recursive:true});
const stubs=path.join(scratch,"host-stubs"); fs.mkdirSync(stubs,{recursive:true});
const base=[sdk,stdlib,...jars,...web].join(path.delimiter);
function compile(input,out,label,friend) {
    const rsp=path.join(scratch,label+'.args'); fs.writeFileSync(rsp,input.map(f=>'"'+path.resolve(f).replaceAll('\\','/')+'"').join('\n'));
    const args=['-Xmx3g','-Dfile.encoding=UTF-8','-cp',upstream.join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xbackend-threads=4','-classpath',friend?[classes,stubs,base].join(path.delimiter):base,'-d',out];
    if(friend) args.push('-Xfriend-paths='+classes); run(java,[...args,'@'+rsp],label);
}
if(process.argv.includes('--tests-only')) {
    if(!fs.existsSync(manifest)||JSON.stringify(JSON.parse(fs.readFileSync(manifest,'utf8')))!==JSON.stringify(initial)) throw Error('STALE_PRODUCTION_CLASSES');
} else {
    for(const d of [classes]) { if(!d.startsWith(scratch+path.sep))throw Error('INVALID_BUILD_PATH');fs.rmSync(d,{recursive:true,force:true});fs.mkdirSync(d,{recursive:true}); }
    console.log('Compiling '+sources.length+' production inputs against actual Android SDK and WebKit jars');
    compile(sources,classes,'production-kotlin');
    const javaSources=sources.filter(f=>f.endsWith('.java'));
    const rsp=path.join(scratch,'java.args'); fs.writeFileSync(rsp,javaSources.map(f=>'"'+path.resolve(f).replaceAll('\\','/')+'"').join('\n'));
    run(javac,['-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-proc:none','-classpath',[classes,base].join(path.delimiter),'-d',classes,'@'+rsp],'production-java');
    if(JSON.stringify(initial)!==JSON.stringify(snapshot())) throw Error('SOURCE_CHANGED_DURING_COMPILE');
    fs.writeFileSync(manifest,JSON.stringify(initial));
}
if(process.argv.includes('--compile-only')) { console.log('FULL_PRODUCTION_COMPILE_PASS; DEVICE_NOT_RUN; APK_NOT_PACKAGED'); process.exit(0); }
run(javac,['-encoding','UTF-8','-d',json,...walk('tools/json-src').filter(f=>f.endsWith('.java'))],'host-json');
run(javac,['-encoding','UTF-8','-classpath',sdk,'-d',stubs,...walk('tests/run-lifecycle-repair/host-stubs').filter(f=>f.endsWith('.java'))],'host-android-fixtures');
const suites=[['dev.wind.agent.MateProfileTests','tests/dev/wind/agent/MateProfileTests.kt'],['dev.wind.agent.SessionFeatureTests','tests/dev/wind/agent/SessionFeatureTests.kt'],['dev.wind.agent.skills.SkillStoreTests','tests/p7-skills-tools/SkillStoreTests.kt'],['dev.wind.agent.ChatStoreIntegrationTests','tests/run-lifecycle-repair/ChatStoreIntegrationTests.kt'],['dev.wind.agent.ContextRecoveryTests','tests/dev/wind/agent/ContextRecoveryTests.kt'],['dev.wind.agent.ContextHarnessTests','tests/dev/wind/agent/ContextHarnessTests.kt']];
suites.unshift(['dev.wind.agent.MemoryRulesTests','tests/dev/wind/agent/MemoryRulesTests.kt']);
suites.unshift(['dev.wind.agent.MemoryExtractorTests','tests/dev/wind/agent/MemoryExtractorTests.kt']);
suites.unshift(['dev.wind.agent.MateTaskStoreTests','tests/dev/wind/agent/MateTaskStoreTests.kt']);
suites.push(['dev.wind.agent.AgentPermissionTests','tests/dev/wind/agent/AgentPermissionTests.kt']);
for(const suite of ['ChatGuardianTests','ChatGuardianVisualTests','ChatGuardianAndroid12Tests'])suites.push(['dev.wind.agent.'+suite,'tests/dev/wind/agent/'+suite+'.kt']);
const testInput=suites.map(s=>s[1]), testHashes={...supportHashes,...Object.fromEntries(testInput.map(f=>[f,hash(f)]))};
compile(testInput,tests,'host-test-compile',true);
const runtime=[json,stubs,tests,classes,sdk,stdlib,...jars,...web].join(path.delimiter),results={};
for(const [s] of suites) { const log=run(java,['-Xmx2g','-Dfile.encoding=UTF-8','-cp',runtime,s,s.endsWith('ContextHarnessTests')?'--core-only':scratch],s); results[s]=log.trim().split(/\r?\n/).at(-1); console.log(results[s]); }
if(JSON.stringify(initial)!==JSON.stringify(snapshot())) throw Error('SOURCE_CHANGED_DURING_TESTS');
if(Object.entries(testHashes).some(([f,h])=>hash(f)!==h)) throw Error('TESTS_CHANGED_DURING_VERIFICATION');
fs.writeFileSync(summaryFile,JSON.stringify({status:'SOURCE_AND_HOST_VERIFIED',sourceManifestHash:hash(manifest),testInputHashes:testHashes,productionInputs:sources.length,suites:results,device:'DEVICE_NOT_RUN',provider:'HOST_DOCUMENTS_AND_LOCAL_GEMINI_FIXTURES_ONLY',apk:'NOT_PACKAGED'},null,2)+'\n');

