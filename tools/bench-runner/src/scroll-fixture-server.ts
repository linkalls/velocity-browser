import {resolve} from 'node:path';
const port=Number(Bun.argv[2]??8181);
if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid port');
Bun.serve({hostname:'127.0.0.1',port,fetch(request){
 const scene=new URL(request.url).pathname;
 if(scene!=='/static'&&scene!=='/dynamic')return new Response('Fixture not found',{status:404});
 return new Response(Bun.file(resolve(import.meta.dir,`../fixtures/scroll-${scene.slice(1)}.html`)),{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'}});
}});
console.log(`Local static/dynamic fixtures listening on port ${port}`);
