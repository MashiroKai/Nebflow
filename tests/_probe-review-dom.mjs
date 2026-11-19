import { chromium } from 'playwright';
import { readFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const HSRC = resolve(HERE, 'fixtures/mobile-diff-baseline/harness.html');
const PORT = 8399, BASE = `http://127.0.0.1:${PORT}`;
const M = { '.html':'text/html','.js':'text/javascript','.css':'text/css' };
const srv = createServer((q,s)=>{const u=new URL(q.url,BASE);const p=decodeURIComponent(u.pathname);
 if(p==='/__harness.html'){s.writeHead(200,{'Content-Type':M['.html']});s.end(readFileSync(HSRC));return;}
 const f=join(WEB,p); if(!f.startsWith(WEB)||!existsSync(f)){s.writeHead(404);s.end();return;}
 s.writeHead(200,{'Content-Type':M[extname(f)]||'application/octet-stream'});s.end(readFileSync(f));});
await new Promise(r=>srv.listen(PORT,'127.0.0.1',r));
const b = await chromium.launch();
const ctx = await b.newContext({viewport:{width:375,height:812},isMobile:true,hasTouch:true});
const page = await ctx.newPage();
await page.goto(BASE+'/__harness.html');
await page.waitForFunction('window.__ready===true');
await page.evaluate(()=>window.__showPage('review'));
await page.waitForTimeout(100);
const dump = await page.evaluate(()=>{
  const root=document.querySelector('#page-review .cm-content');
  const classes=new Set();
  root.querySelectorAll('*').forEach(e=>e.classList.forEach(c=>classes.add(c)));
  const chunkDom = [...document.querySelectorAll('#page-review .cm-deletedChunk')].map(e=>({txt:e.textContent.slice(0,60), html:e.innerHTML.slice(0,200)}));
  return { classes:[...classes].sort(), chunkCount: chunkDom.length, chunkDom, lines:[...root.querySelectorAll('.cm-line')].map(e=>({cls:e.className, t:e.textContent.slice(0,40)})).slice(0,8), deletedLines:[...document.querySelectorAll('#page-review .cm-deletedLine')].map(e=>e.textContent.slice(0,40)) };
});
console.log(JSON.stringify(dump,null,1));
await b.close(); srv.close();
