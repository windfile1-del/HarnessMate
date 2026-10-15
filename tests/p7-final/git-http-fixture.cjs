const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),cp=require('node:child_process');
const root=path.resolve(process.argv[2]);
const scratch=path.resolve(__dirname,'../../.build-p7-final');
if(!root.startsWith(scratch+path.sep))throw Error('Fixture must stay in task scratch');
fs.mkdirSync(root,{recursive:true});
const git=process.env.HARNESSMATE_HOST_GIT || 'C:/Program Files/Git/cmd/git.exe';
const backend=process.env.HARNESSMATE_GIT_HTTP_BACKEND || 'C:/Program Files/Git/mingw64/libexec/git-core/git-http-backend.exe';
const seed=path.join(root,'seed'),repo=path.join(root,'repo.git');fs.mkdirSync(seed,{recursive:true});
function g(args,cwd=seed){const r=cp.spawnSync(git,args,{cwd,encoding:'utf8',env:{...process.env,GIT_AUTHOR_NAME:'Fixture',GIT_AUTHOR_EMAIL:'fixture@example.invalid',GIT_COMMITTER_NAME:'Fixture',GIT_COMMITTER_EMAIL:'fixture@example.invalid'}});if(r.status!==0)throw Error(r.stderr);return r.stdout;}
g(['init']);g(['symbolic-ref','HEAD','refs/heads/main']);fs.writeFileSync(path.join(seed,'.gitattributes'),'* text eol=lf\n');fs.writeFileSync(path.join(seed,'README.md'),'initial\n');fs.writeFileSync(path.join(seed,'poetry.lock'),'fixture dependency lock\n');g(['add','.']);g(['commit','-m','initial']);g(['clone','--bare',seed,repo],root);g(['--git-dir',repo,'config','http.receivepack','true']);g(['remote','add','origin',repo]);
let authorized=0,rejected=0;
const expected='Basic '+Buffer.from('fixture-user:fixture-token-not-a-real-secret').toString('base64');
const server=http.createServer((req,res)=>{
 if(req.url==='/control/stats'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({authorized,rejected,credentialLogged:false}));return;}
 if(req.url==='/control/stop'){fs.writeFileSync(path.join(root,'http-summary.json'),JSON.stringify({authorized,rejected,credentialLogged:false}));res.end('ok');server.close(()=>process.exit(0));return;}
 if(req.url==='/control/advance'){g(['fetch','origin']);g(['reset','--hard','origin/main']);fs.writeFileSync(path.join(seed,'README.md'),'server update\n');g(['add','.']);g(['commit','-m','server update']);g(['push','origin','main']);res.end('ok');return;}
 if(req.headers.authorization!==expected){rejected++;res.writeHead(401,{'WWW-Authenticate':'Basic realm="fixture"'});res.end('unauthorized');return;}
 authorized++;const chunks=[];req.on('data',b=>chunks.push(b));req.on('end',()=>{
  const url=new URL(req.url,'http://127.0.0.1');const body=Buffer.concat(chunks);
  const child=cp.spawn(backend,[],{env:{...process.env,GIT_PROJECT_ROOT:root,GIT_HTTP_EXPORT_ALL:'1',REMOTE_USER:'fixture-user',REQUEST_METHOD:req.method,PATH_INFO:url.pathname,QUERY_STRING:url.search.slice(1),CONTENT_TYPE:req.headers['content-type']||'',CONTENT_LENGTH:String(body.length),HTTP_CONTENT_ENCODING:req.headers['content-encoding']||'',HTTP_GIT_PROTOCOL:req.headers['git-protocol']||''}});
  const output=[];child.stdout.on('data',b=>output.push(b));child.stderr.on('data',b=>process.stderr.write(b));child.stdin.end(body);child.on('close',code=>{
   if(code!==0){res.writeHead(500);res.end('fixture backend error');return;}
   const bytes=Buffer.concat(output);let sep=bytes.indexOf('\r\n\r\n'),skip=4;if(sep<0){sep=bytes.indexOf('\n\n');skip=2;}
   if(sep<0){res.writeHead(500);res.end('invalid CGI');return;}
   let status=200;const headers={};for(const line of bytes.subarray(0,sep).toString().split(/\r?\n/)){const i=line.indexOf(':');if(i<0)continue;const key=line.slice(0,i),value=line.slice(i+1).trim();if(key.toLowerCase()==='status')status=parseInt(value);else headers[key]=value;}
   res.writeHead(status,headers);res.end(bytes.subarray(sep+skip));
  });
 });
});
server.listen(0,'127.0.0.1',()=>{fs.writeFileSync(path.join(root,'port.json'),JSON.stringify({port:server.address().port}));console.log('GIT_HTTP_FIXTURE_READY');});
function stop(){fs.writeFileSync(path.join(root,'http-summary.json'),JSON.stringify({authorized,rejected,credentialLogged:false}));server.close(()=>process.exit(0));}
process.on('SIGTERM',stop);process.on('SIGINT',stop);
