// Host-only source verification. No APK, dex, device or emulator is produced.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..');process.chdir(root);
const scratch=path.join(root,'.build-web-providers-'+Date.now());fs.mkdirSync(scratch,{recursive:true});
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);}
for(const line of fs.readFileSync('third_party/webkit/SHA256SUMS','utf8').trim().split(/\r?\n/)){
 const [,digest,file]=line.match(/^(\S+)\s+\*?(.+)$/);if(crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')!==digest)throw Error('Checksum mismatch '+file);
}
const groups={};for(const m of fs.readFileSync('res/values/public.xml','utf8').matchAll(/<public type="([^"]+)" name="([^"]+)" id="([^"]+)"/g))(groups[m[1]]??=[]).push([m[2],m[3]]);
const r=path.join(scratch,'R.java');fs.writeFileSync(r,'package dev.wind.agent; public final class R {'+Object.entries(groups).map(([k,v])=>'public static final class '+k+' {'+v.map(([n,id])=>'public static final int '+n+'='+id+';').join('')+'}').join('')+'}');
const upstream=walk('third_party/embedded/kotlin/upstream').filter(f=>f.endsWith('.jar')&&!f.endsWith('-sources.jar'));
const web=fs.readFileSync('third_party/webkit/runtime-jars.txt','utf8').trim().split(/\r?\n/);
const embedded=walk('third_party/embedded').filter(f=>f.endsWith('.jar')&&!f.includes('kotlin')&&!f.includes('desugar')&&!f.endsWith('-sources.jar'));
const classpath=['assets/embedded/android-35/android.jar',...web,...upstream,...embedded].map(f=>path.resolve(f)).join(path.delimiter);
const sources=[...walk('src'),...walk('runtime-companion/src'),r,'tests/dev/wind/agent/WebProviderCoreTests.kt','tests/dev/wind/agent/ModelProviderTests.kt'].filter(f=>/\.(kt|java)$/.test(f));
const args=path.join(scratch,'sources.args');fs.writeFileSync(args,sources.map(f=>'"'+path.resolve(f).replaceAll('\\','/')+'"').join('\n'));
fs.writeFileSync(path.join(scratch,'classpath.txt'),classpath);
const log=fs.openSync(path.join(scratch,'compile.log'),'w');
const result=cp.spawnSync('java',['-Xmx3g','-cp',upstream.map(f=>path.resolve(f)).join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',classpath,'-d',path.join(scratch,'classes'),'@'+args],{stdio:['ignore',log,log]});fs.closeSync(log);
console.log('Host source compile:',result.status,'Evidence:',scratch);
if(result.status!==0){console.log(fs.readFileSync(path.join(scratch,'compile.log'),'utf8').slice(-16000));process.exit(result.status??1);}
const classes=path.join(scratch,'classes');
function run(executable,args){if(executable==='javac')args.unshift('-J-Dfile.encoding=UTF-8');const result=cp.spawnSync(executable,args,{encoding:'utf8'});if(result.stdout)console.log(result.stdout);if(result.stderr)console.log(result.stderr);if(result.status!==0)throw Error(executable+' failed: '+result.status);}
const javaSources=sources.filter(f=>f.endsWith('.java'));
const javaArgs=path.join(scratch,'java.args');fs.writeFileSync(javaArgs,javaSources.map(f=>'"'+path.resolve(f).replaceAll('\\','/')+'"').join('\n'));
run('javac',['-encoding','UTF-8','-source','17','-target','17','-classpath',classes+path.delimiter+classpath,'-d',classes,'@'+javaArgs]);
const jsonClasses=path.join(scratch,'json');fs.mkdirSync(jsonClasses);
run('javac',['-encoding','UTF-8','-d',jsonClasses,...walk('tools/json-src').filter(f=>f.endsWith('.java'))]);
run('java',['-cp',[jsonClasses,classes,classpath].join(path.delimiter),'dev.wind.agent.WebProviderCoreTests']);
run('java',['-cp',[jsonClasses,classes,classpath].join(path.delimiter),'dev.wind.agent.ModelProviderTests']);
run('node',['--test','tests/web-provider-adapter.test.cjs']);
console.log('PASS: Kotlin, Java, Web Provider core assertions and DOM regression tests. No APK or emulator.');
