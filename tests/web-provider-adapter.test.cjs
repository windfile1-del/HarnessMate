const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
// Independent DOM double for the bundled adapter, not a claim of browser acceptance.
function fixture(options={}){
 let clock=10000, clicks=0; const nodes=[];
 class Element {
  constructor(tag='DIV',text=''){this.tagName=tag;this.nodeType=1;this.innerText=text;this.textContent=text;this.childNodes=text?[{nodeType:3,nodeValue:text}]:[];this.hidden=false;this.isConnected=true;this.disabled=false;this.attrs={};this.parentElement=null;this.files=[];nodes.push(this);}
  getClientRects(){return [1];} getAttribute(k){return this.attrs[k]??null;} contains(){return false;} closest(){return null;}
  querySelectorAll(){return [];} querySelector(){return null;} focus(){} dispatchEvent(){} click(){clicks++;}
  compareDocumentPosition(n){return nodes.indexOf(n)>nodes.indexOf(this)?4:2;}
 }
 class Textarea extends Element {constructor(){super('TEXTAREA');this._value='';}get value(){return this._value;}set value(v){this._value=v;}}
 const input=new Textarea(),send=new Element('BUTTON'),stop=new Element('BUTTON'),regen=new Element('BUTTON'),file=new Element('INPUT');file.type='file';stop.hidden=true;regen.hidden=true;
 const users=[],answers=[],attachments=[],alerts=[]; const map={'#prompt':[input],'#send':[send],'#stop':[stop],'#regenerate':[regen],'#files':[file],'.fixture-user':users,'.fixture-answer':answers,'.fixture-file':attachments,'[role="alert"], [data-testid="rate-limit-banner"]':alerts};
 const doc={documentElement:{},body:{innerText:''},contains:n=>nodes.includes(n),querySelector:()=>null,querySelectorAll:s=>map[s]??(s.startsWith('[role="alert"]')?alerts:[])};
 if(options.adapter === 'deepseek' || options.adapter === 'glm' || options.adapter === 'gemini') {
  map['textarea']=[input];
  if(options.adapter === 'gemini') map['rich-textarea [contenteditable="true"]']=[input];
 }
 if(options.noFile) {map['#files']=[];} else map['input[type="file"]']=[file];
 if(options.rich){input.tagName='DIV';input.attrs.contenteditable='true';input._value=undefined;map['#prompt-textarea']=[input];map['button[data-testid="send-button"]']=[send];}
 let edits=0;
 if(options.editing) doc.execCommand=(command,ui,text)=>{assert.equal(command,'insertText');edits++;input.textContent=text;input.innerText=text;return true;};
 const location={protocol:'https:',hostname:'web-provider.vega.invalid',href:'https://web-provider.vega.invalid/fixture?chat=a'};
 const ctx={window:{},document:doc,location,Node:{DOCUMENT_POSITION_FOLLOWING:4},Date:{now:()=>clock},MutationObserver:class{observe(){}disconnect(){}},getComputedStyle:()=>({display:'block',visibility:'visible'}),HTMLTextAreaElement:Textarea,HTMLInputElement:Textarea,Event:class{}};
 vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'../assets/webproviders/adapter.js'),'utf8').replace('__HARNESS_KEY__','"testAdapter"').replace('__HARNESS_CONFIG__',JSON.stringify({adapter:options.adapter||'fixture',hosts:[location.hostname],version:'test'})),ctx);
 const command=(name,args={})=>JSON.parse(JSON.stringify(ctx.window.testAdapter.command(name,args)));
 function user(text){const n=new Element('DIV',text);users.push(n);return n;}
 function answer(text){const n=new Element('ARTICLE',text);answers.push(n);return n;}
 return {command,input,send,stop,regen,file,users,answers,alerts,user,answer,location,map,doc,Element,tick:n=>clock+=n,clicks:()=>clicks,edits:()=>edits};
}
test('prepare does not click; commit clicks exactly once',()=>{const f=fixture();assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).prepared,true);assert.equal(f.input.value,'q');assert.equal(f.clicks(),0);assert.equal(f.command('commit',{requestId:'a'}).submitted,true);assert.equal(f.clicks(),1);assert.equal(f.command('commit',{requestId:'a'}).error,'DUPLICATE_SUBMISSION_BLOCKED');assert.equal(f.clicks(),1);});
test('manual draft is preserved',()=>{const f=fixture();f.input.value='my draft';assert.equal(f.command('begin',{requestId:'a',text:'replace',operation:'SEND'}).error,'COMPOSER_HAS_DRAFT');assert.equal(f.input.value,'my draft');});
test('changed composer cannot be submitted',()=>{const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.input.value='changed';assert.equal(f.command('commit',{requestId:'a'}).error,'INPUT_CHANGED_BEFORE_SEND');assert.equal(f.clicks(),0);});
test('old identical user question does not confirm a new send',()=>{const f=fixture();f.user('q');f.answer('old');f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.command('commit',{requestId:'a'});assert.equal(f.command('probe').userConfirmed,false);assert.equal(f.command('probe').answer,'');f.user('q');const a=f.answer('new');f.stop.hidden=false;assert.equal(f.command('probe').answer,'new');f.stop.hidden=true;f.tick(1801);assert.equal(f.command('probe').done,true);a.childNodes=[{nodeType:3,nodeValue:'replacement'}];assert.equal(f.command('probe').answer,'replacement');assert.equal(f.command('probe').done,false);f.tick(1801);assert.equal(f.command('probe').done,true);});
test('latest unrelated question cannot confirm a historical match',()=>{const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.user('q');f.user('different');f.answer('answer');assert.equal(f.command('probe').userConfirmed,false);});
test('stable text alone does not prove completion',()=>{const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.user('q');f.answer('text');f.command('probe');f.tick(5000);assert.equal(f.command('probe').done,false);});
test('generation failure is attributed to current response',()=>{const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.user('q');f.answer('failed').attrs['data-failed']='true';assert.equal(f.command('probe').generationFailed,true);});
test('regenerate and stop use explicit controls',()=>{const f=fixture();f.user('q');f.answer('previous');f.regen.hidden=false;assert.equal(f.command('begin',{requestId:'r',text:'q',operation:'REGENERATE'}).submitted,true);assert.equal(f.clicks(),1);f.stop.hidden=false;assert.equal(f.command('stop').requested,true);assert.equal(f.clicks(),2);});
test('regenerate cannot immediately reuse the old response',()=>{const f=fixture();f.user('q');f.answer('previous');f.regen.hidden=false;f.command('begin',{requestId:'r',text:'q',operation:'REGENERATE'});f.tick(3000);const snapshot=f.command('probe');assert.equal(snapshot.answer,'');assert.equal(snapshot.done,false);});
test('all expected attachments must be present',()=>{const f=fixture();f.command('upload',{names:['a.png','b.pdf']});f.file.files=[{name:'a.png'}];assert.equal(f.command('probe').uploadReady,false);f.file.files.push({name:'b.pdf'});assert.equal(f.command('probe').uploadReady,true);});
test('upload readiness and upload action',()=>{const f=fixture();assert.equal(f.command('probe').uploadReady,false);assert.equal(f.command('upload').requested,true);f.file.files=[{name:'a.png'}];assert.equal(f.command('probe').uploadReady,true);});
test('origin change denies operations',()=>{const f=fixture();f.location.hostname='evil.example';assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).error,'UNTRUSTED_PAGE_ORIGIN');assert.equal(f.clicks(),0);});
test('unknown commands fail; recovery never submits',()=>{const f=fixture();assert.equal(f.command('callTool').error,'UNKNOWN_ADAPTER_COMMAND');f.user('q');f.answer('completed');f.command('recover',{requestId:'a',text:'q'});assert.equal(f.clicks(),0);assert.equal(f.command('probe').userConfirmed,true);});
test('ChatGPT rich composer uses editor insertion then one explicit send',()=>{const f=fixture({adapter:'chatgpt',rich:true,editing:true});assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).prepared,true);assert.equal(f.edits(),1);assert.equal(f.command('commit',{requestId:'a'}).submitted,true);assert.equal(f.clicks(),1);});
test('rich composer retains fallback when editing command is unavailable',()=>{const f=fixture({adapter:'chatgpt',rich:true});assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).prepared,true);assert.equal(f.input.textContent,'q');assert.equal(f.command('commit',{requestId:'a'}).submitted,true);});

test('text-only DeepSeek prepares and submits without any upload control',()=>{
 const f=fixture({adapter:'deepseek',noFile:true});f.map['button[type="submit"]']=[f.send];
 assert.equal(f.command('probe').capabilities.upload,false);
 assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).prepared,true);
 assert.equal(f.command('commit',{requestId:'a'}).submitted,true);assert.equal(f.clicks(),1);
});
test('DeepSeek unlabelled send glyph selects only the known arrow, ignores other icons',()=>{
 const f=fixture({adapter:'deepseek',noFile:true});const attach=new f.Element('DIV');
 const path=new f.Element('PATH');path.attrs.d='M8.3125 0.981587 other path V15.0431H7 end';
 f.send.querySelectorAll=s=>s==='svg path'?[path]:[];
 f.map['button,[role="button"],.ds-icon-button']=[attach,f.send];
 assert.equal(f.command('begin',{requestId:'a',text:'q',operation:'SEND'}).prepared,true);
 assert.equal(f.command('commit',{requestId:'a'}).submitted,true);assert.equal(f.clicks(),1);
});
test('disabled DeepSeek arrow cannot send or mark a request as clicked',()=>{
 const f=fixture({adapter:'deepseek',noFile:true});f.map['button[type="submit"]']=[f.send];f.send.className='ds-icon-button ds-icon-button--disabled';
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});
 assert.equal(f.command('commit',{requestId:'a'}).error,'SEND_CONTROL_NOT_FOUND');assert.equal(f.clicks(),0);
 f.send.className='ds-icon-button';assert.equal(f.command('commit',{requestId:'a'}).submitted,true);
});
test('GLM recognises composer send-button rather than requiring a submit type',()=>{
 const f=fixture({adapter:'glm',noFile:true});f.map['div.input-wrap > div.enter']=[f.send];f.send.tagName='DIV';
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});
 assert.equal(f.command('commit',{requestId:'a'}).submitted,true);
});
test('Gemini Chinese send label is supported',()=>{
 const f=fixture({adapter:'gemini',noFile:true});f.map['button[aria-label="发送消息"]']=[f.send];
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});
 assert.equal(f.command('commit',{requestId:'a'}).submitted,true);
});
test('hidden upload input is usable while disabled input is rejected',()=>{
 const f=fixture({adapter:'glm'});f.file.hidden=true;
 assert.equal(f.command('upload',{names:['screen.jpg']}).requested,true);
 f.file.disabled=true;assert.equal(f.command('upload',{names:['screen.jpg']}).error,'UPLOAD_CONTROL_NOT_FOUND');
 assert.equal(f.clicks(),1);
});
test('Gemini upload menu is opened once, then dynamic file input is clicked once',()=>{
 const f=fixture({adapter:'gemini',noFile:true});const menu=new f.Element('BUTTON');f.map['button.upload-menu-button']=[menu];
 assert.equal(f.command('probe').capabilities.upload,true);
 assert.equal(f.command('upload',{names:['screen.jpg']}).menuOpened,true);
 assert.equal(f.command('upload',{names:['screen.jpg'],continuation:true}).error,'UPLOAD_CONTROL_NOT_FOUND');assert.equal(f.clicks(),1);
 f.map['input[type="file"]']=[f.file];f.file.hidden=true;
 assert.equal(f.command('upload',{names:['screen.jpg'],continuation:true}).requested,true);assert.equal(f.clicks(),2);
});
test('portalled upload menu item can trigger a chooser without a pre-existing input',()=>{
 const f=fixture({adapter:'gemini',noFile:true});const item=new f.Element('BUTTON','Upload files');
 f.map['[role="menuitem"],.mat-mdc-menu-item']=[item];
 assert.equal(f.command('upload',{names:['screen.jpg'],continuation:true}).requested,true);assert.equal(f.clicks(),1);
});
test('multiple unrelated file inputs are not guessed',()=>{
 const f=fixture({adapter:'deepseek'}),other=new f.Element('INPUT');other.type='file';
 f.map['input[type="file"]']=[f.file,other];
 assert.equal(f.command('upload',{names:['screen.jpg']}).error,'UPLOAD_CONTROL_NOT_FOUND');assert.equal(f.clicks(),0);
});
test('nested editor finds sibling toolbar but cannot click a send control elsewhere',()=>{
 const f=fixture({adapter:'glm',noFile:true});const wrapper=new f.Element(),composer=new f.Element();
 f.input.parentElement=wrapper;wrapper.parentElement=composer;composer.parentElement=f.doc.body;
 composer.querySelectorAll=s=>s==='button,[role="button"],.ds-icon-button'?[f.send]:[];
 composer.contains=n=>n===f.send||n===f.input;f.map['button.send-button']=[new f.Element('BUTTON'),f.send];
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});assert.equal(f.command('commit',{requestId:'a'}).submitted,true);assert.equal(f.clicks(),1);
});
test('DeepSeek native user bubble confirms and streams the answer',()=>{
 const f=fixture({adapter:'deepseek'});f.map['.fbb737a4']=f.users;f.map['.ds-markdown']=f.answers;f.map['button[type="submit"]']=[f.send];
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.command('commit',{requestId:'a'});
 f.user('q');f.answer('reply');assert.equal(f.command('probe').userConfirmed,true);assert.equal(f.command('probe').answer,'reply');
});
test('Gemini extracts query text and response content without surrounding UI labels',()=>{
 const f=fixture({adapter:'gemini'});f.map['user-query-content .query-text']=f.users;f.map['model-response message-content']=f.answers;
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.user('q');const reply=f.answer('real answer');
 const copy=new f.Element('COPY-BUTTON','Copy');copy.childNodes=[{nodeType:3,nodeValue:'Copy'}];reply.childNodes.push(copy);
 assert.equal(f.command('probe').answer,'real answer');
 reply.querySelectorAll=s=>s==='copy-button,copy-response-button'?[copy]:[];
 f.tick(1801);assert.equal(f.command('probe').done,true);
});
test('manual-send observation retains baseline, streams fresh reply and never clicks',()=>{
 const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});
 assert.equal(f.command('observeManualSend',{requestId:'a'}).observing,true);
 f.user('q');f.answer('manual reply');f.stop.hidden=false;
 assert.equal(f.command('probe').answer,'manual reply');f.stop.hidden=true;f.tick(1801);assert.equal(f.command('probe').done,true);
 assert.equal(f.command('commit',{requestId:'a'}).error,'DUPLICATE_SUBMISSION_BLOCKED');assert.equal(f.clicks(),0);
});
test('unknown user class can confirm only a new exact bubble outside the assistant answer',()=>{
 const f=fixture({adapter:'glm'}),bubbles=[];f.map['main div,main p,[role="main"] div,[role="main"] p']=bubbles;f.map['.markdown-body']=f.answers;
 const old=new f.Element('DIV','q');bubbles.push(old);
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});
 assert.equal(f.command('probe').userConfirmed,false);
 const user=new f.Element('DIV','q');bubbles.push(user);f.answer('fresh reply');
 assert.equal(f.command('probe').userConfirmed,true);assert.equal(f.command('probe').answer,'fresh reply');
});
test('assistant quoting the question is never a fallback user confirmation',()=>{
 const f=fixture({adapter:'glm'});f.map['.markdown-body']=f.answers;
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});const reply=f.answer('q');
 f.map['main div,main p,[role="main"] div,[role="main"] p']=[reply];
 assert.equal(f.command('probe').userConfirmed,false);assert.equal(f.command('probe').answer,'');
});
test('missing editor during generation does not stop response observation',()=>{
 const f=fixture();f.command('begin',{requestId:'a',text:'q',operation:'SEND'});f.user('q');f.answer('reply');f.input.hidden=true;
 assert.equal(f.command('probe').status,'READY');assert.equal(f.command('probe').answer,'reply');
});
test('rich editor draft paragraphs cannot confirm a user message',()=>{
 const f=fixture({adapter:'gemini',rich:true,editing:true}),draft=new f.Element('P','q');
 f.input.contains=n=>n===draft;f.map['main div,main p,[role="main"] div,[role="main"] p']=[draft];
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});assert.equal(f.command('probe').userConfirmed,false);
});
test('DeepSeek primary circular send control respects disabled class',()=>{
 const f=fixture({adapter:'deepseek',noFile:true});f.send.tagName='DIV';f.send.className='ds-button ds-button--disabled';
 f.map['div.ds-button--primary.ds-button--filled.ds-button--circle[role="button"]']=[f.send];
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});assert.equal(f.command('probe').capabilities.send,false);
 f.send.className='ds-button';assert.equal(f.command('commit',{requestId:'a'}).submitted,true);
});
test('fallback cannot override a newer identified unrelated question',()=>{
 const f=fixture({adapter:'glm'});f.map['[class*="user-message"]']=f.users;f.map['.markdown-body']=f.answers;
 f.command('begin',{requestId:'a',text:'q',operation:'SEND'});const matched=f.user('q');f.user('different');f.answer('different reply');
 f.map['main div,main p,[role="main"] div,[role="main"] p']=[matched];
 assert.equal(f.command('probe').userConfirmed,false);assert.equal(f.command('probe').answer,'');
});
