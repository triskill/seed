(function () {
  'use strict';
  var transport = window.seedDeviceTransport;
  if (!transport || typeof transport.postMessage !== 'function') return;
  var pending = new Map(), sequence = 0, disposed = false;
  function error(code, message) { var e = new Error(message); e.code = code; return e; }
  function object(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
  function validate(request) {
    if (!object(request)) return false;
    var keys = Reflect.ownKeys(request);
    // Transport validates only the envelope. Native owns capability names and schemas.
    return keys.length === 2 && keys.indexOf('method') !== -1 && keys.indexOf('params') !== -1 &&
      typeof request.method === 'string' && request.method.trim().length > 0 && request.method.length <= 128 && object(request.params);
  }
  transport.onmessage = function(event) {
    if (typeof event.data !== 'string') return;
    var r; try { r = JSON.parse(event.data); } catch (_) { return; }
    if (!object(r) || r.v !== 1 || typeof r.id !== 'string' || typeof r.ok !== 'boolean') return;
    var p = pending.get(r.id); if (!p) return;
    if (r.ok ? !object(r.result) : (!object(r.error) || typeof r.error.code !== 'string' || typeof r.error.message !== 'string')) return;
    pending.delete(r.id); clearTimeout(p.timer);
    if (r.ok) p.resolve(r.result); else p.reject(error(r.error.code, r.error.message));
  };
  window.addEventListener('pagehide', function() {
    disposed = true;
    pending.forEach(function(p){clearTimeout(p.timer);p.reject(error('CANCELLED','Page disposed'));}); pending.clear();
  });
  // A bfcache-restored document retains this SDK; document-start injection
  // does not run again. Old calls stay canceled and sequence IDs stay unique.
  window.addEventListener('pageshow', function(event) {
    if (event.persisted) disposed = false;
  });
  var seed = window.seed || (window.seed = {});
  seed.android = { call: function(request) {
    return new Promise(function(resolve,reject) {
      if (disposed) { reject(error('CANCELLED','Page disposed')); return; }
      if (!validate(request)) { reject(error('INVALID_REQUEST','Invalid device capability request')); return; }
      if (pending.size) { reject(error('BUSY','Another device operation is active')); return; }
      var id = String(++sequence), wire;
      try { wire = JSON.stringify({v:1,id:id,method:request.method,params:request.params}); if (new TextEncoder().encode(wire).length > 8192) throw new Error(); }
      catch (_) { reject(error('INVALID_REQUEST','Invalid device capability request')); return; }
      var timer = setTimeout(function(){pending.delete(id);reject(error('TIMEOUT','Device operation timed out'));},125000);
      pending.set(id,{resolve:resolve,reject:reject,timer:timer});
      try { transport.postMessage(wire); } catch (_) { clearTimeout(timer);pending.delete(id);reject(error('UNAVAILABLE','Device transport unavailable')); }
    });
  }};
})();
