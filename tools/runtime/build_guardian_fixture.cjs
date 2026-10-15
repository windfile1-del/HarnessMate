/* Offline, explicitly labelled QQ-package protocol fixture for the owned API31 AVD only. */
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..');process.chdir(root);
const out=path.join(root,'.build-mate-memory/p3/guardian-fixture');fs.mkdirSync(out,{recursive:true});
const scratch=path.join(out,'build-'+Date.now()),classes=path.join(scratch,'classes'),dex=path.join(scratch,'dex');
fs.mkdirSync(classes,{recursive:true});fs.mkdirSync(dex);
const java='D:/VSCode/bin/java.exe',jar='D:/VSCode/bin/jar.exe',bt='D:/HTML/android-sdk/build-tools/34.0.0',sdk=path.join(root,'assets/embedded/android-35/android.jar');
const inputs=['device-tests/guardian-fixture/MainActivity.java','device-tests/guardian-fixture/AndroidManifest.xml'];
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');const hashes=Object.fromEntries(inputs.map(f=>[f,hash(f)]));
function run(exe,args,label){const fd=fs.openSync(path.join(scratch,label+'.log'),'w');const r=cp.spawnSync(exe,args,{stdio:['ignore',fd,fd]});fs.closeSync(fd);if(r.status!==0)throw Error(label+' failed; inspect '+scratch);console.log(label+' PASS');}
run('D:/VSCode/bin/javac.exe',['--release','8','-cp',sdk,'-d',classes,path.join(root,inputs[0])],'java');
const inputJar=path.join(scratch,'fixture.jar');run(jar,['cf',inputJar,'-C',classes,'.'],'jar');
run(java,['-cp',path.join(bt,'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','30','--lib',sdk,'--output',dex,inputJar],'dex');
const unsigned=path.join(scratch,'unsigned.apk');run(path.join(bt,'aapt2.exe'),['link','-o',unsigned,'--manifest',path.join(root,inputs[1]),'-I',sdk,'--min-sdk-version','30','--target-sdk-version','31'],'resources');
run(jar,['uf',unsigned,'-C',dex,'classes.dex'],'apk-dex');const aligned=path.join(scratch,'aligned.apk');run(path.join(bt,'zipalign.exe'),['-f','4',unsigned,aligned],'align');
const apk=path.join(out,'HarnessMate-local-guardian-fixture.apk');run(java,['-jar',path.join(bt,'lib/apksigner.jar'),'sign','--ks','C:/Users/windfile/.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned],'sign');
run(java,['-jar',path.join(bt,'lib/apksigner.jar'),'verify','--verbose',apk],'signature');
if(JSON.stringify(hashes)!==JSON.stringify(Object.fromEntries(inputs.map(f=>[f,hash(f)]))))throw Error('FIXTURE_INPUT_CHANGED');
fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({scope:'OFFLINE_LOCAL_FIXTURE_NO_QQ_ACCOUNT_OR_NETWORK',package:'com.tencent.mobileqq',apk,sha256:hash(apk),inputs:hashes},null,2));console.log(apk);
