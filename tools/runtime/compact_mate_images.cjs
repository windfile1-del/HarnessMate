/* Only restored API31 system/vendor images. Zero ranges are byte-verified before deallocation. */
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'),imageRoot=fs.realpathSync(path.join(root,'.build-virtual-display/android31-image/x86_64'));
if(!imageRoot.startsWith(root+path.sep))throw Error('IMAGE_ROOT_OUTSIDE_WORKSPACE');
if(!fs.readFileSync(path.join(imageRoot,'source.properties'),'utf8').includes('AndroidVersion.ApiLevel=31'))throw Error('WRONG_IMAGE_API');
const adb='D:/HTML/android-sdk/platform-tools/adb.exe';
const devices=cp.spawnSync(adb,['-P','5060','devices'],{encoding:'utf8'});
if(devices.status!==0||devices.stdout.includes('emulator-5560'))throw Error('STOP_OWNED_AVD_BEFORE_COMPACTION');
const result=[],block=process.argv.includes('--fine')?64*1024:4*1024*1024,zero=Buffer.alloc(block);
function scan(file,ranges){const fd=fs.openSync(file,'r'),buffer=Buffer.alloc(block),digest=crypto.createHash('sha256');let offset=0,start=null;try{while(true){const n=fs.readSync(fd,buffer,0,block,null);if(!n)break;const part=buffer.subarray(0,n);digest.update(part);if(ranges&&part.equals(zero.subarray(0,n))){if(start===null)start=offset;}else if(start!==null){ranges.push([start,offset-start]);start=null;}offset+=n;}if(start!==null)ranges.push([start,offset-start]);}finally{fs.closeSync(fd);}return {size:offset,sha256:digest.digest('hex')};}
function util(args){const r=cp.spawnSync('C:/Windows/System32/fsutil.exe',args,{encoding:'utf8'});if(r.status!==0)throw Error('fsutil: '+r.stdout+' '+r.stderr);}
for(const name of ['system.img','vendor.img']){
    const file=path.join(imageRoot,name),ranges=[],before=scan(file,ranges);
    util(['sparse','setflag',file]);
    for(const [offset,length] of ranges){if(offset<0||length<=0||offset+length>before.size)throw Error('ZERO_RANGE_OUTSIDE_IMAGE');util(['sparse','setrange',file,String(offset),String(length)]);}
    const after=scan(file);
    if(before.size!==after.size||before.sha256!==after.sha256)throw Error('IMAGE_BYTES_CHANGED');
    result.push({file,...after,zeroBytes:ranges.reduce((n,r)=>n+r[1],0),ranges:ranges.length,status:'BYTES_UNCHANGED'});
    fs.writeFileSync(path.join(root,'.build-mate-memory/sparse-image-result.json'),JSON.stringify(result,null,2));
    console.log(name+': identical SHA256, zero ranges='+ranges.length+', zero bytes='+result.at(-1).zeroBytes);
}
