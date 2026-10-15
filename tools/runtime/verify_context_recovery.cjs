/* Offline source + host checks. Does not build/install an APK or contact a handset. */
const fs=require('node:fs'), path=require('node:path'), cp=require('node:child_process'), crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'); process.chdir(root);
const scratch=path.join(root,'.build-context-recovery'); fs.mkdirSync(scratch,{recursive:true});
const walk=d=>fs.existsSync(d)?fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name)]):[];
const java=process.env.VEPRO_HOST_JAVA || 'java', javac=process.env.VEPRO_HOST_JAVAC || 'javac';
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
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
const snapshot=()=>Object.fromEntries(sources.map(f=>[path.relative(root,f).replaceAll('\\','/'),hash(f)]));
const initial=snapshot(), manifest=path.join(scratch,'source-hashes.json');
const classes=path.join(scratch,'production'), json=path.join(scratch,'json'), tests=path.join(scratch,'tests');
for(const d of [classes,json,tests]) fs.mkdirSync(d,{recursive:true});
const base=[sdk,stdlib,...jars,...web].join(path.delimiter);
function compile(input,out,label,friend) {
    const rsp=path.join(scratch,label+'.args'); fs.writeFileSync(rsp,input.map(f=>'"'+path.resolve(f).replaceAll('\\','/')+'"').join('\n'));
    const args=['-Xmx3g','-Dfile.encoding=UTF-8','-cp',upstream.join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xbackend-threads=4','-classpath',friend?[classes,base].join(path.delimiter):base,'-d',out];
    if(friend) args.push('-Xfriend-paths='+classes); run(java,[...args,'@'+rsp],label);
}
if(process.argv.includes('--tests-only')) {
    if(!fs.existsSync(manifest)||JSON.stringify(JSON.parse(fs.readFileSync(manifest,'utf8')))!==JSON.stringify(initial)) throw Error('STALE_PRODUCTION_CLASSES');
} else {
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
const suites=['ContextRecoveryTests','ContextHarnessTests'];
compile(suites.map(s=>'tests/dev/wind/agent/'+s+'.kt'),tests,'host-test-compile',true);
const runtime=[json,tests,classes,sdk,stdlib,...jars,...web].join(path.delimiter), results={};
for(const s of suites) { const log=run(java,['-Xmx2g','-Dfile.encoding=UTF-8','-cp',runtime,'dev.wind.agent.'+s,s === 'ContextHarnessTests' ? '--core-only' : scratch],s); results[s]=log.trim().split(/\r?\n/).at(-1); console.log(results[s]); }
if(JSON.stringify(initial)!==JSON.stringify(snapshot())) throw Error('SOURCE_CHANGED_DURING_TESTS');
fs.writeFileSync(path.join(scratch,'verification-summary.json'),JSON.stringify({status:'SOURCE_AND_HOST_VERIFIED',productionInputs:sources.length,suites:results,device:'DEVICE_NOT_RUN',provider:'LOCAL_GEMINI_FIXTURE_ONLY',apk:'NOT_PACKAGED'},null,2)+'\n');
