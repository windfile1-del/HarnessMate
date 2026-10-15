const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..');process.chdir(root);
const scratch=path.join(root,'.build-p7-final/host-tests');fs.mkdirSync(scratch,{recursive:true});
const gitOnly=process.argv.includes('--git-only');
const production=path.join(root,'.build-p7-tools/production-check');
const delta=path.join(root,'.build-p7-final/transport');
const manifest=JSON.parse(fs.readFileSync(path.join(delta,'summary.json'),'utf8'));
function sourceCheck(){for(const [p,h] of Object.entries(manifest.sourceHashes))if(crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex')!==h)throw Error('STALE_PRODUCTION '+p);}
sourceCheck();
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const java=process.env.VEPRO_HOST_JAVA||'D:/VSCode/bin/java.exe',javac=process.env.VEPRO_HOST_JAVAC||'D:/VSCode/bin/javac.exe';
const android=path.join(root,'assets/embedded/android-35/android.jar');
const upstream=walk(path.join(root,'third_party/embedded/kotlin/upstream')).filter(p=>p.endsWith('.jar')&&!p.endsWith('-sources.jar'));
const libs=[...upstream,...walk(path.join(root,'third_party/embedded')).filter(p=>p.endsWith('.jar')&&!p.includes('kotlin')&&!p.includes('desugar')&&!p.endsWith('-sources.jar')),
 ...fs.readFileSync('third_party/webkit/runtime-jars.txt','utf8').trim().split(/\r?\n/).map(p=>path.resolve(p))];
for(const n of ['classes','json','host-android','temp'])fs.mkdirSync(path.join(scratch,n),{recursive:true});
function run(name,exe,args){const log=path.join(scratch,name+'.log'),fd=fs.openSync(log,'w');const r=cp.spawnSync(exe,args,{stdio:['ignore',fd,fd],timeout:240000});fs.closeSync(fd);
 const output=fs.readFileSync(log,'utf8');if(r.error||r.status!==0){process.stderr.write(output);throw r.error||Error(name+' failed '+r.status);}console.log(name+' PASS');return output;}
if(!gitOnly)run('json-compile',javac,['-encoding','UTF-8','-d',path.join(scratch,'json'),...walk('tools/json-src').filter(p=>p.endsWith('.java'))]);
if(!gitOnly)run('host-stubs',javac,['-encoding','UTF-8','-cp',android,'-d',path.join(scratch,'host-android'),...walk(path.join(__dirname,'host-android')).filter(p=>p.endsWith('.java'))]);
const classPointer=path.join(scratch,'test-classes-path.txt');
const classes=gitOnly?fs.readFileSync(classPointer,'utf8'):path.join(scratch,'classes-'+Date.now());
if(!gitOnly){fs.mkdirSync(classes,{recursive:true});fs.writeFileSync(classPointer,classes);}
const prod=[path.join(production,'java'),path.join(delta,'classes'),path.join(production,'classes')];
const compileCp=[android,...prod,...libs].join(path.delimiter);
const sources=[...walk(__dirname).filter(p=>p.endsWith('.kt')),path.join(root,'tests/p7-vpn-downloads/VpnCompatibleDownloadsTests.kt'),
 path.join(root,'tests/dev/wind/agent/SecureArtifactDownloaderTests.kt'),path.join(root,'tests/p7-project-packages/ProjectPackageTests.kt'),
 path.join(root,'tests/p7-skills-tools/RuntimeToolCatalogTests.kt')];
const rsp=path.join(scratch,'sources.rsp');fs.writeFileSync(rsp,sources.map(p=>'"'+p.replaceAll('\\','/')+'"').join('\n'));
if(!gitOnly)run('tests-compile',java,['-Xmx1g','-Dfile.encoding=UTF-8','-cp',upstream.join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
 '-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+path.join(production,'classes')+','+path.join(delta,'classes'),'-classpath',compileCp,'-d',classes,'@'+rsp]);
const diagnostics=[];
if(process.argv.includes('--fixture-diagnostics')){
 const output=path.join(scratch,'fake-credential-diagnostics'),source=path.join(scratch,'RemoteGit-fixture.kt');fs.mkdirSync(output,{recursive:true});
 fs.writeFileSync(source,fs.readFileSync('src/dev/wind/agent/runtime/vcs/RemoteGit.kt','utf8').replace('// JGit/server errors may contain URLs or echoed headers.','e.printStackTrace() // FAKE-CREDENTIAL HOST FIXTURE ONLY\n            // JGit/server errors may contain URLs or echoed headers.'));
 run('fake-fixture-diagnostic-compile',java,['-Xmx1g','-cp',upstream.join(path.delimiter),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+path.join(production,'classes'),'-classpath',compileCp,'-d',output,source]);diagnostics.push(output);
}
const runtime=[...diagnostics,classes,path.join(scratch,'json'),path.join(scratch,'host-android'),...prod,android,...libs].join(path.delimiter);
const common=['-Xmx1g','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+path.join(scratch,'temp'),'-cp',runtime];
const results={};
for(const [suite,args] of [
 ['dev.wind.agent.SecretPublisherTests',[]],['dev.wind.agent.SecretBoundaryTests',[path.join(scratch,'concurrency-'+Date.now())]],
 ['dev.wind.agent.artifact.VpnCompatibleDownloadsTests',[]],['dev.wind.agent.SecureArtifactDownloaderTests',[]],
 ['dev.wind.agent.packages.ProjectPackageTests',[]],['dev.wind.agent.runtime.linux.RuntimeToolCatalogTests',[]],
 ['dev.wind.agent.SignedDistributionTests',[root,path.join(scratch,'signed-distribution-'+Date.now())]]
])if(!gitOnly)results[suite]=run(suite,java,[...common,suite,...args]).trim();
async function main(){
 const fixture=path.join(scratch,'git-http-'+Date.now()),fd=fs.openSync(path.join(scratch,'git-http-fixture.log'),'w');
 const server=cp.spawn(process.execPath,[path.join(__dirname,'git-http-fixture.cjs'),fixture],{stdio:['ignore',fd,fd]});
 let port;
 try{
  const info=path.join(fixture,'port.json');
  for(let i=0;i<150&&!fs.existsSync(info);i++){if(server.exitCode!==null)throw Error('Git HTTP fixture exited');await new Promise(r=>setTimeout(r,100));}
  port=JSON.parse(fs.readFileSync(info)).port;
  results['dev.wind.agent.runtime.vcs.RemoteGitIntegrationTests']=run('remote-git-integration',java,[...common,'dev.wind.agent.runtime.vcs.RemoteGitIntegrationTests',path.join(scratch,'git-client-'+Date.now()),String(port)]).trim();
  const stats=await(await fetch('http://127.0.0.1:'+port+'/control/stats')).json();
  if(stats.authorized<=0||stats.rejected<=0||stats.credentialLogged!==false)throw Error('Missing authentication challenge evidence');
  results.gitHttp=stats;
 }finally{if(port)try{await fetch('http://127.0.0.1:'+port+'/control/stop');}catch{}server.kill();fs.closeSync(fd);}
 sourceCheck();
 fs.writeFileSync(path.join(scratch,gitOnly?'git-summary.json':'summary.json'),JSON.stringify({status:diagnostics.length?'FAKE_CREDENTIAL_DIAGNOSTIC_ONLY':'SOURCE_AND_HOST_VERIFIED',sources:Object.keys(manifest.sourceHashes).length,results,
  apk:'NOT_PACKAGED',device:'DEVICE_NOT_RUN',git:'Real JGit + local git-http-backend through HTTP test seam; HTTPS/TLS/OEM/Keystore not device acceptance'},null,2));
 for(const [s,r]of Object.entries(results))console.log(s+': '+(typeof r==='string'?r.split(/\r?\n/).at(-1):JSON.stringify(r)));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
