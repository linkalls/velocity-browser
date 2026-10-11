// Local fixture only. Supply a certificate trusted by the test browser; never bypass TLS checks.
const [certPath,keyPath,portText='8443'] = Bun.argv.slice(2);
if (!certPath || !keyPath || !/^\d+$/.test(portText)) throw Error('usage: https-fixture.ts cert.pem key.pem [port]');
const port = Number(portText); if (port < 1024 || port > 65535) throw Error('invalid port');
let adRequests = 0, controlRequests = 0, transferredBytes = 0;
const body = `<!doctype html><meta charset="utf-8"><title>Velocity blocking fixture</title>
<h1>HTTPS blocker fixture</h1><p id="result">RUNNING</p>
<form><label>保持する入力<input aria-label="保持する入力"></label></form>
<div class="ad-slot">CSS広告枠</div><div class="ad-slot"><form><input placeholder="非表示禁止"></form></div>
<script>
(async()=>{
 let ad=false,control=false;
 try { const r=await fetch('https://ads.velocity.test:${port}/ad'); ad=r.ok; } catch {}
 try { const r=await fetch('/control'); control=r.ok; } catch {}
 document.querySelector('#result').textContent='AD_'+(ad?'ALLOWED':'FAILED')+' CONTROL_'+(control?'OK':'FAILED');
})();</script>`;
Bun.serve({hostname:'0.0.0.0',port,tls:{cert:Bun.file(certPath),key:Bun.file(keyPath)},fetch(request) {
  const u = new URL(request.url);
  if (u.pathname === '/counts') return Response.json({adRequests,controlRequests,transferredBytes});
  if (u.pathname === '/reset' && request.method === 'POST') { adRequests=0;controlRequests=0;transferredBytes=0;return new Response('RESET'); }
  let content: string;
  if (u.pathname === '/ad') { adRequests++; content='AD_OK'; }
  else if (u.pathname === '/control') { controlRequests++; content='CONTROL_OK'; }
  else if (u.pathname === '/') content=body;
  else return new Response('not found',{status:404});
  transferredBytes+=new TextEncoder().encode(content).length;
  return new Response(content,{headers:{'Content-Type':u.pathname==='/'?'text/html; charset=utf-8':'text/plain','Cache-Control':'no-store','Access-Control-Allow-Origin':`https://site.velocity.test:${port}`}});
}});
console.log(`HTTPS fixture listening on ${port}; configure site.velocity.test and ads.velocity.test DNS and trusted test TLS`);
