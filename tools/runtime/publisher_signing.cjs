// JCA-compatible RSA-SHA256 signatures. Private key is never an APK/release asset.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const workspace=path.resolve(__dirname,'../..');
const argv=process.argv.slice(2),command=argv.shift();
const option=name=>{const i=argv.indexOf('--'+name);if(i<0)throw Error('Missing --'+name);return path.resolve(argv[i+1]);};
const value=name=>{const i=argv.indexOf('--'+name);if(i<0)throw Error('Missing --'+name);return argv[i+1];};
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function identity(key){const publicKey=key.type==='public'?key:crypto.createPublicKey(key),der=publicKey.export({format:'der',type:'spki'});return {key_id:'harnessmate-rsa-'+sha(der).slice(0,16),x509_base64:der.toString('base64'),revoked:false};}
if(command==='init') {
 const keyPath=option('key'),roots=option('roots');
 const rel=path.relative(workspace,keyPath);
 if(rel.startsWith('..')||path.isAbsolute(rel)||!rel.split(path.sep)[0].startsWith('.build-'))throw Error('Private key initialization must stay in ignored workspace .build-* storage');
 fs.mkdirSync(path.dirname(keyPath),{recursive:true});
 if(!fs.existsSync(keyPath)) {
  const keys=crypto.generateKeyPairSync('rsa',{modulusLength:3072});
  fs.writeFileSync(keyPath,keys.privateKey.export({format:'pem',type:'pkcs8'}),{flag:'wx',mode:0o600});
 }
 const publicRecord=identity(fs.readFileSync(keyPath));
 if(fs.existsSync(roots)) {
  const existing=JSON.parse(fs.readFileSync(roots));
  if(!existing.keys.some(k=>k.key_id===publicRecord.key_id&&!k.revoked))throw Error('Existing root does not authorize signing key; explicit rotation required');
 } else fs.writeFileSync(roots,JSON.stringify({schema:1,keys:[publicRecord]},null,2)+'\n',{flag:'wx'});
 console.log('PUBLISHER_ROOT_READY '+publicRecord.key_id);
} else if(command==='sign') {
 const key=fs.readFileSync(option('key')),manifest=fs.readFileSync(option('manifest')),metadata=JSON.parse(manifest);
 const statement={schema:1,family:value('family'),release:value('release'),manifest_sha256:sha(manifest),payload_sha256:metadata.sha256,
  compressed_bytes:metadata.compressedBytes,not_before:0,not_after:4102444800000};
 const signed=Buffer.from(JSON.stringify(statement));
 const envelope={schema:1,algorithm:'RSA_SHA256',key_id:identity(key).key_id,signed:signed.toString('base64'),signature:crypto.sign('sha256',signed,key).toString('base64')};
 fs.writeFileSync(option('output'),JSON.stringify(envelope)+'\n');console.log('PUBLISHER_STATEMENT_SIGNED '+envelope.key_id);
} else if(command==='verify') {
 const e=JSON.parse(fs.readFileSync(option('envelope'))),roots=JSON.parse(fs.readFileSync(option('roots'))),manifest=fs.readFileSync(option('manifest'));
 const key=roots.keys.find(k=>k.key_id===e.key_id&&!k.revoked);if(!key||e.schema!==1||e.algorithm!=='RSA_SHA256')throw Error('Untrusted publisher');
 const publicKey=crypto.createPublicKey({key:Buffer.from(key.x509_base64,'base64'),format:'der',type:'spki'});
 if(publicKey.asymmetricKeyDetails.modulusLength<3072||identity(publicKey).key_id!==e.key_id)throw Error('Invalid publisher key');
 const signed=Buffer.from(e.signed,'base64');if(!crypto.verify('sha256',signed,publicKey,Buffer.from(e.signature,'base64')))throw Error('Signature invalid');
 const s=JSON.parse(signed),m=JSON.parse(manifest);
 if(s.schema!==1||s.family!==value('family')||s.release!==value('release')||s.manifest_sha256!==sha(manifest)||s.payload_sha256!==m.sha256||s.compressed_bytes!==m.compressedBytes||Date.now()<s.not_before||Date.now()>=s.not_after)throw Error('Publisher binding mismatch');
 console.log('PUBLISHER_VERIFIED '+e.key_id);
} else throw Error('Expected init/sign/verify');
