const {test}=require('node:test'); const assert=require('node:assert/strict'); const fs=require('node:fs'); const vm=require('node:vm'); const path=require('node:path');
const file=path.resolve(__dirname,'../../main/assets/device/seed-android.js');
function setup(timers={setTimeout,clearTimeout}){let sent=[],events={};let w={seed:{helper:42},seedDeviceTransport:{postMessage(s){sent.push(JSON.parse(s));}},addEventListener(n,f){events[n]=f;}};w.window=w;vm.runInNewContext(fs.existsSync(file)?fs.readFileSync(file,'utf8'):'', {window:w,setTimeout:timers.setTimeout,clearTimeout:timers.clearTimeout,TextEncoder,queueMicrotask});return {w,sent,events};}
test('SDK exists and preserves seed helpers',()=>{const {w}=setup();assert.equal(typeof w.seed.android?.call,'function');assert.equal(w.seed.helper,42);});
test('correlates versioned replies',async()=>{const {w,sent}=setup();const p=w.seed.android.call({method:'sensor.list',params:{}});assert.equal(sent[0].v,1);w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:true,result:{sensors:[]}})});assert.deepEqual(JSON.parse(JSON.stringify(await p)),{sensors:[]});});
test('busy and disposal reject',async()=>{const {w,events}=setup();const p=w.seed.android.call({method:'camera.capture',params:{}});await assert.rejects(w.seed.android.call({method:'sensor.list',params:{}}),{code:'BUSY'});events.pagehide();await assert.rejects(p,{code:'CANCELLED'});});
test('future methods forward without SDK schema edits',async(t)=>{const {w,sent,events}=setup();t.after(()=>events.pagehide());const p=w.seed.android.call({method:'device.future',params:{feature:{enabled:true},values:[1,2]}});p.catch(()=>{});assert.equal(sent.length,1);assert.deepEqual(sent[0],{v:1,id:sent[0].id,method:'device.future',params:{feature:{enabled:true},values:[1,2]}});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:true,result:{future:true}})});assert.deepEqual(JSON.parse(JSON.stringify(await p)),{future:true});});
test('unknown methods forward and reject the native UNKNOWN_METHOD reply',async(t)=>{const {w,sent,events}=setup();t.after(()=>events.pagehide());const p=w.seed.android.call({method:'sensor.subscribe',params:{type:1}});p.catch(()=>{});assert.equal(sent.length,1);w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:false,error:{code:'UNKNOWN_METHOD',message:'Capability is not available'}})});await assert.rejects(p,{code:'UNKNOWN_METHOD'});});
test('invalid sensor parameters forward and reject the native INVALID_REQUEST reply',async(t)=>{const {w,sent,events}=setup();t.after(()=>events.pagehide());const p=w.seed.android.call({method:'sensor.read',params:{type:1.5,unexpected:true}});p.catch(()=>{});assert.equal(sent.length,1);assert.deepEqual(sent[0].params,{type:1.5,unexpected:true});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:false,error:{code:'INVALID_REQUEST',message:'Invalid device capability request'}})});await assert.rejects(p,{code:'INVALID_REQUEST'});});
test('malformed generic envelopes reject locally',async(t)=>{const {w,sent,events}=setup({setTimeout(f){setImmediate(f);return 1;},clearTimeout(){}});t.after(()=>events.pagehide());for(const request of [null,[],{}, {method:'',params:{}},{method:' '.repeat(3),params:{}},{method:'m'.repeat(129),params:{}},{method:1,params:{}},{method:'sensor.list',params:null},{method:'sensor.list',params:[]},{method:'sensor.list',params:'bad'},{method:'sensor.list',params:{},extra:true},Object.create({method:'sensor.list',params:{}})]) await assert.rejects(w.seed.android.call(request),{code:'INVALID_REQUEST'});assert.equal(sent.length,0);});
test('UTF8 wire limit and unserializable parameters reject locally',async()=>{const {w,sent}=setup();const circular={};circular.self=circular;for(const params of [{value:'é'.repeat(4096)},circular,{value:1n}]) await assert.rejects(w.seed.android.call({method:'device.future',params}),{code:'INVALID_REQUEST'});assert.equal(sent.length,0);});
test('timeout rejects and releases the operation slot',async()=>{let fire;const {w,sent}=setup({setTimeout(f,ms){assert.equal(ms,125000);fire=f;return 1;},clearTimeout(){}});const p=w.seed.android.call({method:'sensor.list',params:{}});fire();await assert.rejects(p,{code:'TIMEOUT'});const next=w.seed.android.call({method:'sensor.list',params:{}});assert.notEqual(sent[0].id,sent[1].id);w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[1].id,ok:true,result:{}})});await next;});
test('wrong IDs and versions never settle a promise',async()=>{const {w,sent}=setup();const p=w.seed.android.call({method:'sensor.list',params:{}});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:'unknown',ok:true,result:{}})});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:2,id:sent[0].id,ok:true,result:{}})});await assert.rejects(w.seed.android.call({method:'sensor.list',params:{}}),{code:'BUSY'});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:true,result:{}})});await p;});
test('restored cached page resumes calls without reusing old request IDs',async(t)=>{
  const {w,sent,events}=setup(); t.after(()=>events.pagehide());
  const old=w.seed.android.call({method:'sensor.list',params:{}});
  events.pagehide(); await assert.rejects(old,{code:'CANCELLED'});
  await assert.rejects(w.seed.android.call({method:'sensor.list',params:{}}),{code:'CANCELLED'});
  assert.equal(typeof events.pageshow,'function'); events.pageshow({persisted:true});
  const current=w.seed.android.call({method:'sensor.list',params:{}}); current.catch(()=>{});
  assert.notEqual(sent[0].id,sent[1].id);
  w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:true,result:{stale:true}})});
  await assert.rejects(w.seed.android.call({method:'sensor.list',params:{}}),{code:'BUSY'});
  w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[1].id,ok:true,result:{sensors:[]}})});
  assert.deepEqual(JSON.parse(JSON.stringify(await current)),{sensors:[]});
});
test('structured error and malformed messages',async()=>{const {w,sent}=setup();const p=w.seed.android.call({method:'sensor.list',params:{}});w.seedDeviceTransport.onmessage({data:new ArrayBuffer(4)});w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,id:sent[0].id,ok:false,error:{code:'PERMISSION_DENIED',message:'No'}})});await assert.rejects(p,{code:'PERMISSION_DENIED'});});

test('slow asynchronous callback holds ACK; duplicates and callback failures clean up',async(t)=>{
 const {w,sent,events}=setup();t.after(()=>events.pagehide());let release,calls=0;
 const p=w.seed.android.subscribe({method:'sensor.subscribe',params:{type:1}},()=>{calls++;return new Promise(r=>release=r);});
 const id=sent[0].id;reply(w,{id,ok:true,result:{subscriptionId:'slow',type:1,rateHz:30}});const h=await p;
 const sample={id,type:'stream',subscriptionId:'slow',event:'sample',sequence:1,sample:{type:1,values:[1],timestampNs:1,accuracy:3}};
 reply(w,sample);reply(w,sample);assert.equal(calls,1);assert.equal(sent.length,1);
 release();await new Promise(setImmediate);assert.equal(sent[1].type,'stream_ack');assert.equal(sent[1].sequence,1);
 reply(w,sample);assert.equal(calls,1);
 const stop=h.stop();reply(w,{id:sent.at(-1).id,ok:true,result:{stopped:true}});await stop;
 const q=w.seed.android.subscribe({method:'sensor.subscribe',params:{type:1}},()=>{throw Error('oops');});
 const rid=sent.at(-1).id;reply(w,{id:rid,ok:true,result:{subscriptionId:'throw',type:1,rateHz:30}});const bad=await q;
 reply(w,{...sample,id:rid,subscriptionId:'throw'});assert.equal((await bad.closed).code,'INTERNAL_ERROR');assert.equal(sent.at(-1).method,'sensor.unsubscribe');
 reply(w,{id:sent.at(-1).id,ok:true,result:{stopped:true}});
});
test('pagehide terminates slow stream without late ACK and cached return does not resubscribe',async(t)=>{
 const {w,sent,events}=setup();t.after(()=>events.pagehide());let release;
 await assert.rejects(w.seed.android.subscribe({method:'sensor.subscribe',params:{}},null),{code:'INVALID_REQUEST'});
 const p=w.seed.android.subscribe({method:'sensor.subscribe',params:{type:1}},()=>new Promise(r=>release=r));
 const id=sent[0].id;reply(w,{id,ok:true,result:{subscriptionId:'slow',type:1,rateHz:30}});const h=await p;
 reply(w,{id,type:'stream',subscriptionId:'slow',event:'sample',sequence:1,sample:{type:1,values:[1],timestampNs:1,accuracy:1}});
 events.pagehide();assert.equal((await h.closed).code,'CANCELLED');release();await new Promise(setImmediate);
 assert.equal(sent.filter(r=>r.type==='stream_ack').length,0);const count=sent.length;events.pageshow({persisted:true});assert.equal(sent.length,count);
});
function reply(w,r){w.seedDeviceTransport.onmessage({data:JSON.stringify({v:1,...r})});}
test('stream buffers latest early sample after await and stop bypasses busy',async(t)=>{
 const {w,sent,events}=setup();t.after(()=>events.pagehide());let handle,seen=[];
 const p=w.seed.android.subscribe({method:'sensor.subscribe',params:{type:1}},s=>{assert.ok(handle);seen.push(s.values[0]);});
 const id=sent[0].id,subscriptionId='native-1';
 for(const n of [1,2]) reply(w,{id,type:'stream',subscriptionId,event:'sample',sequence:n,sample:{type:1,values:[n],timestampNs:n,accuracy:3}});
 reply(w,{id,ok:true,result:{subscriptionId,type:1,rateHz:30}});handle=await p;await new Promise(setImmediate);assert.deepEqual(seen,[2]);
 const gps=w.seed.android.call({method:'location.current',params:{}});gps.catch(()=>{});
 const stop=handle.stop();const control=sent.at(-1);assert.equal(control.method,'sensor.unsubscribe');assert.equal(handle.stop(),stop);
 reply(w,{id:control.id,ok:true,result:{stopped:true}});await stop;await handle.closed;events.pagehide();
});
test('terminal event closes and cached page does not resurrect stream',async()=>{
 const {w,sent,events}=setup();let seen=0;
 const p=w.seed.android.subscribe({method:'sensor.subscribe',params:{type:1}},()=>seen++);
 const id=sent[0].id;reply(w,{id,ok:true,result:{subscriptionId:'n',type:1,rateHz:30}});const h=await p;
 reply(w,{id,type:'stream',subscriptionId:'wrong',event:'closed',error:{code:'CANCELLED',message:'background'}});
 reply(w,{id,type:'stream',subscriptionId:'n',event:'closed',error:{code:'CANCELLED',message:'background'}});
 assert.equal((await h.closed).code,'CANCELLED');events.pagehide();events.pageshow({persisted:true});
 reply(w,{id,type:'stream',subscriptionId:'n',event:'sample',sample:{type:1,values:[1],timestampNs:1,accuracy:1}});assert.equal(seen,0);
});
