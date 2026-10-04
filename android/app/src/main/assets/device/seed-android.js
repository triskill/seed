(function () {
  'use strict';
  var transport = window.seedDeviceTransport;
  if (!transport || typeof transport.postMessage !== 'function') return;
  var pending = new Map(), streams = new Map(), sequence = 0, disposed = false;
  function error(code, message) { var e = new Error(message); e.code = code; return e; }
  function object(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
  function validate(r) {
    if (!object(r)) return false;
    var keys = Reflect.ownKeys(r);
    return keys.length === 2 && keys.includes('method') && keys.includes('params') &&
      typeof r.method === 'string' && r.method.trim().length > 0 && r.method.length <= 128 && object(r.params);
  }
  function validError(e) { return object(e) && typeof e.code === 'string' && typeof e.message === 'string'; }
  function finish(s, reason) { if (s.done) return; s.done = true; streams.delete(s.requestId); s.resolveClosed(reason); }
  function cleanup(s) {
    if (!s.id) return Promise.resolve({stopped:false});
    if (!s.stopPromise) s.stopPromise = call({method:'sensor.unsubscribe',params:{subscriptionId:s.id}}).then(function(r) {
      finish(s,{code:'CANCELLED',message:'Stream stopped'}); return r;
    },function(e) { finish(s,{code:e.code,message:e.message}); throw e; });
    return s.stopPromise;
  }
  function deliver(s, r) {
    if (s.done || r.subscriptionId !== s.id) return;
    if (r.event === 'closed') { finish(s,r.error); return; }
    if (r.sequence <= (s.sequence || 0) || s.delivering) return;
    s.sequence = r.sequence; s.delivering = true;
    function failed() { s.delivering=false; finish(s,{code:'INTERNAL_ERROR',message:'Sample callback failed'}); cleanup(s).catch(function(){}); }
    try { Promise.resolve(s.callback(r.sample)).then(function() {
      s.delivering=false;
      if (!s.done && !s.stopPromise) {
        try { transport.postMessage(JSON.stringify({v:1,type:'stream_ack',subscriptionId:s.id,sequence:r.sequence})); } catch (_) { failed(); }
      }
    },failed); } catch (_) { failed(); }
  }
  transport.onmessage = function(event) {
    if (typeof event.data !== 'string') return;
    var r; try { r = JSON.parse(event.data); } catch (_) { return; }
    if (!object(r) || r.v !== 1 || typeof r.id !== 'string') return;
    if (r.type === 'stream') {
      var s = streams.get(r.id); if (!s || s.done || typeof r.subscriptionId !== 'string' || !r.subscriptionId.length || r.subscriptionId.length > 64) return;
      if (r.event === 'sample') {
        var v = r.sample;
        if (!Number.isSafeInteger(r.sequence) || r.sequence < 1 || !object(v) || !Number.isInteger(v.type) || v.type < 1 || !Array.isArray(v.values) || !v.values.length || !v.values.every(function(n){return n === null || (typeof n === 'number' && Number.isFinite(n));}) || !Number.isFinite(v.timestampNs) || v.timestampNs < 0 || !(v.accuracy === null || Number.isInteger(v.accuracy))) return;
      } else if (r.event !== 'closed' || !validError(r.error)) return;
      if (!s.id) { s.early = r; return; }
      deliver(s,r); return;
    }
    if (typeof r.ok !== 'boolean') return;
    var p = pending.get(r.id); if (!p) return;
    if (r.ok ? !object(r.result) : !validError(r.error)) return;
    pending.delete(r.id); clearTimeout(p.timer);
    if (r.ok) p.resolve(r.result); else p.reject(error(r.error.code,r.error.message));
  };
  function call(request, stream) {
    return new Promise(function(resolve,reject) {
      if (disposed) { reject(error('CANCELLED','Page disposed')); return; }
      if (!validate(request)) { reject(error('INVALID_REQUEST','Invalid device capability request')); return; }
      var control = request.method === 'sensor.unsubscribe';
      if (!control && Array.from(pending.values()).some(function(p){return !p.control;})) { reject(error('BUSY','Another device operation is active')); return; }
      var id = String(++sequence), wire;
      try { wire = JSON.stringify({v:1,id:id,method:request.method,params:request.params}); if (new TextEncoder().encode(wire).length > 8192) throw new Error(); }
      catch (_) { reject(error('INVALID_REQUEST','Invalid device capability request')); return; }
      if (stream) { stream.requestId = id; streams.set(id,stream); }
      var timer = setTimeout(function(){pending.delete(id);reject(error('TIMEOUT','Device operation timed out'));},125000);
      pending.set(id,{resolve:resolve,reject:reject,timer:timer,control:control});
      try { transport.postMessage(wire); } catch (_) { clearTimeout(timer);pending.delete(id);reject(error('UNAVAILABLE','Device transport unavailable')); }
    });
  }
  window.addEventListener('pagehide',function() {
    streams.forEach(function(s){cleanup(s).catch(function(){});finish(s,{code:'CANCELLED',message:'Page disposed'});});
    disposed = true;
    pending.forEach(function(p){clearTimeout(p.timer);p.reject(error('CANCELLED','Page disposed'));});pending.clear();
  });
  window.addEventListener('pageshow',function(e){if(e.persisted) disposed=false;});
  var seed = window.seed || (window.seed = {});
  seed.android = {call:function(request){return call(request);},subscribe:function(request,onSample) {
    if (typeof onSample !== 'function' || !validate(request) || request.method !== 'sensor.subscribe') return Promise.reject(error('INVALID_REQUEST','Invalid sensor subscription'));
    var s = {callback:onSample,done:false};
    var closed = new Promise(function(resolve){s.resolveClosed=resolve;});
    return new Promise(function(resolve,reject) { call(request,s).then(function(r) {
      if (typeof r.subscriptionId === 'string' && r.subscriptionId.length > 0 && r.subscriptionId.length <= 64) s.id=r.subscriptionId;
      if (typeof r.subscriptionId !== 'string' || !r.subscriptionId.length || r.subscriptionId.length > 64 || !Number.isInteger(r.type) || r.type < 1 || !Number.isInteger(r.rateHz) || r.rateHz < 1 || r.rateHz > 60) throw error('INVALID_REQUEST','Invalid subscription acknowledgement');
      s.id=r.subscriptionId;
      var h={id:s.id,closed:closed,stop:function(){return cleanup(s);}};
      resolve(h);
      // Resolve first, then allow await adoption (including cross-realm callers)
      // to assign the public handle before delivering the one buffered event.
      queueMicrotask(function(){queueMicrotask(function(){queueMicrotask(function(){if(s.early){var early=s.early;s.early=null;deliver(s,early);}});});});
    }).catch(function(e){finish(s,{code:e.code || 'INTERNAL_ERROR',message:e.message});cleanup(s).catch(function(){});reject(e);}); });
  }};
})();
