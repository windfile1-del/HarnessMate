'use strict';
// Local HTTP fixtures only. Never contacts the user's proxy or any real model.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http');
const {LiveBudget,POLICY}=require('../../tools/runtime/mate_live_budget.cjs');
const {createGuard}=require('../../tools/runtime/mate_live_proxy.cjs');
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'harnessmate-budget-fixture-'));
const body={model:POLICY.model,messages:[{role:'user',content:'本地测试'}]};
let checks=0;const ok=(condition)=> {assert.ok(condition);checks++;};
const rejects=(fn,pattern)=> {assert.throws(fn,pattern);checks++;};
const budget=(name)=>new LiveBudget(path.join(directory,name+'.json'));
const listen=server=>new Promise(resolve=>server.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+server.address().port)));
const close=server=>new Promise(resolve=> {server.close(resolve);server.closeAllConnections();});
(async()=> {
    const b=budget('calls');
    rejects(()=>b.reserve({...body,model:'gpt-expensive'}),/ONLY_APPROVED/);
    rejects(()=>b.reserve({...body,n:2}),/MULTIPLE_GENERATIONS/);
    rejects(()=>b.reserve({...body,fallback_models:['expensive']}),/UNSUPPORTED_GENERATION_OPTION/);
    for(const effort of ['low','medium','high',null,0]) rejects(()=>b.reserve({...body,reasoning_effort:effort}),/ONLY_MINIMAL_TEST_REASONING_ALLOWED/);
    ok(b.snapshot().requests.length===0);
    rejects(()=>b.reserve({...body,messages:[{role:'user',content:[{type:'image_url',image_url:{url:'x'}}]}]}),/MULTIMODAL/);
    rejects(()=>b.reserve({...body,max_tokens:0}),/INVALID_OUTPUT/);
    rejects(()=>b.reserve({...body,max_tokens:5,max_completion_tokens:5}),/AMBIGUOUS/);
    const first=b.reserve({...body,max_tokens:POLICY.perRequestOutput+1000,reasoning_effort:'minimal'});
    ok(JSON.parse(first.encoded).reasoning_effort==='minimal');
    ok(JSON.parse(first.encoded).max_tokens===POLICY.perRequestOutput);
    ok(first.inputReserved>=Buffer.byteLength(first.encoded)+1024);
    b.finish(first.id,'FAILED');
    for(let i=0;i<POLICY.maxRequests-1;i++)new LiveBudget(b.file).reserve({...body,max_tokens:400});
    const before=b.snapshot();
    rejects(()=>b.reserve(body),/REQUEST_BUDGET/);
    ok(b.snapshot().requests.length===POLICY.maxRequests && b.snapshot().outputReserved===POLICY.perRequestOutput+(POLICY.maxRequests-1)*400);
    ok(b.snapshot().inputReserved===before.inputReserved);
    rejects(()=>b.finish(first.id,'COMPLETED'),/ALREADY_FINAL/);
    const big=budget('input');
    big.reserve({...body,messages:[{role:'user',content:'x'.repeat(POLICY.maxInputReserved-5000)}]});
    rejects(()=>big.reserve({...body,messages:[{role:'user',content:'x'.repeat(6000)}]}),/INPUT_BUDGET/);
    ok(big.snapshot().requests.length===1);
    const outputBudget=budget('output');const outputCalls=Math.floor(POLICY.maxOutputReserved/POLICY.perRequestOutput);for(let i=0;i<outputCalls;i++)outputBudget.reserve(body);
    rejects(()=>outputBudget.reserve(body),/OUTPUT_BUDGET/);ok(outputBudget.snapshot().requests.length===outputCalls);
    const broken=budget('broken');broken.snapshot();fs.writeFileSync(broken.file,'broken');
    rejects(()=>broken.reserve(body),/Unexpected token/);
    const locked=budget('locked');fs.mkdirSync(locked.lock);rejects(()=>locked.reserve(body),/BUDGET_BUSY/);fs.rmdirSync(locked.lock);
    let calls=0,stream=false,fail=false,exceed=false;
    const upstream=http.createServer(async(req,res)=> {
        calls++;const chunks=[];for await(const c of req)chunks.push(c);
        const request=JSON.parse(Buffer.concat(chunks));assert.equal(request.model,POLICY.model);assert.ok(request.max_tokens<=POLICY.perRequestOutput);
        if(calls===1) assert.equal(request.reasoning_effort,'minimal');
        if(fail) {res.writeHead(503);return res.end('fixture failure');}
        const usage={prompt_tokens:12,completion_tokens:exceed?POLICY.perRequestOutput+1:1,total_tokens:exceed?POLICY.perRequestOutput+13:13};
        if(stream) {
            assert.equal(request.stream_options.include_usage,true);
            res.writeHead(200,{'content-type':'text/event-stream'});
            res.write('data: {"choices":[]}\n\n');
            res.write('data: '+JSON.stringify({usage})+'\n');return res.end('\ndata: [DONE]\n\n');
        }
        res.setHeader('content-type','application/json');res.end(JSON.stringify({choices:[],usage}));
    });
    const fixtureUrl=await listen(upstream),net=budget('net');
    const guard=createGuard({budget:net,upstream:fixtureUrl+'/v1/chat/completions'}),url=await listen(guard);
    try {
        const models=await (await fetch(url+'/v1/models')).json();ok(models.data.length===1 && models.data[0].id===POLICY.model && calls===0);
        const send=(data)=>fetch(url+'/v1/chat/completions',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(data)});
        ok((await send({...body,model:'claude-expensive'})).status===429 && calls===0);
        ok((await send({...body,reasoning_effort:'high'})).status===429 && calls===0 && net.snapshot().requests.length===0);
        ok((await send({...body,reasoning_effort:'minimal'})).status===200);ok(net.snapshot().requests[0].status==='COMPLETED_WITH_REPORTED_USAGE');
        stream=true;const response=await send({...body,stream:true});ok((await response.text()).includes('[DONE]'));
        ok(net.snapshot().requests[1].reportedUsage.completion_tokens===1);
        fail=true;const failure=await send(body);await failure.text();ok(failure.status===502 && calls===3);
        ok(net.snapshot().requests[2].status==='UPSTREAM_HTTP_503' && net.snapshot().outputReserved===3*POLICY.perRequestOutput);
        fail=false;stream=false;exceed=true;const oversized=await send(body);await oversized.text();
        ok(net.snapshot().halted===true);ok((await send(body)).status===429 && calls===4);
        ok((await fetch(url+'/v1/responses',{method:'POST',body:'{}'})).status===403 && calls===4);
    } finally {await close(guard);await close(upstream);}
    console.log('PASS mate live budget: '+checks+' checks; real model calls=0');
})().catch(e=> {console.error(e);process.exitCode=1;}).finally(()=>fs.rmSync(directory,{recursive:true,force:true}));
