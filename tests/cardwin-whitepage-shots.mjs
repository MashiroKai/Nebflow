// zz-cardwin-shots.mjs — 卡窗白屏修复批（2026-10-22）before/after 证据截图。
// before = 旧打开链 `/index.html?card-window=1` 在网关忠实路由下的空 404 体
//          （作者白窗的本地逐位复现）；
// after  = 新打开链 `/?card-window=1` 弹出即真容（有会话直达）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = resolve(HERE, '../evidence/20261022_cardwin-whitepage/shots');
mkdirSync(OUT, { recursive: true });
const PORT = Number(process.env.PORT || 8166);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ALPHA = '/tmp/cw-fixture/alpha.md';

const MIME = { '.html':'text/html; charset=utf-8','.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.woff2':'font/woff2' };
const ROOT_ALLOW = new Set(['style.css','app.js','sw.js','favicon-32.png','favicon-16.png','favicon.svg','favicon.ico','favicon-180.png','favicon-192.png','favicon-512.png']);
const server = createServer((req,res)=>{
  const u = new URL(req.url, BASE); let p = u.pathname;
  if (p === '/') { try { res.writeHead(200,{'Content-Type':MIME['.html']}); res.end(readFileSync(join(WEB_DIR,'index.html'))); } catch { res.writeHead(404); res.end(); } return; }
  const serve = (rel) => { const f = join(WEB_DIR, rel); try { const b = readFileSync(f); res.writeHead(200,{'Content-Type':MIME[rel.slice(rel.lastIndexOf('.'))]||'application/octet-stream'}); res.end(b); } catch { res.writeHead(404); res.end(); } };
  if (['/js/','/css/','/vendor/','/brand/'].some(q=>p.startsWith(q))) { serve(p); return; }
  if (ROOT_ALLOW.has(p.slice(1))) { serve(p); return; }
  res.writeHead(404, {'Content-Length':'0'}); res.end();
});
server.on('upgrade', (_q, socket) => { try { socket.write('HTTP/1.1 403 Forbidden\r\n\r\n'); } catch {} socket.destroy(); });
await new Promise(r=>server.listen(PORT,'127.0.0.1',r));
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 780, height: 680 }, colorScheme: 'light', deviceScaleFactor: 2 });
await ctx.addInitScript(()=>{ localStorage.setItem('nebflow_locale','zh-CN'); localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({v:2,tabs:[]})); });
await ctx.route('**/api/**', r=>r.fulfill({status:404,json:{}}));
await installTicketMock(ctx);
await ctx.routeWebSocket(/\/ws/, (ws)=>{
  ws.send(JSON.stringify({type:'configData',configured:true,onboarding:'done',models:[],defaults:{}}));
  ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'sess-1',agentName:'Nebula',title:'T',updatedAt:Date.now()}],activeId:'sess-1',folders:[]}));
  ws.onMessage((raw)=>{ let m;try{m=JSON.parse(raw);}catch{return;}
    if(m.type==='getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));
    else if(m.type==='pop.readFile') ws.send(JSON.stringify({type:'fileContent',path:m.path,absPath:m.path,content:'# 项目周报\n\n修复后的独立窗真容：**加粗**、`code`、列表。\n\n1. 第一\n2. 第二\n',itemType:'markdown',fileName:'alpha.md',size:80}));
  });
});

// BEFORE：旧打开链（404 空体 = 白窗）
const before = await ctx.newPage();
await before.goto(BASE + '/index.html?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md', { waitUntil: 'load' }).catch(()=>{});
await sleep(600);
await before.screenshot({ path: join(OUT, 'before-old-url-404-white.png') });
console.log('before status:', await before.evaluate(() => document.body ? document.body.innerText.length : -1), 'title:', await before.title());

// AFTER：新打开链（真容直达）
const after = await ctx.newPage();
await after.goto(BASE + '/?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md');
await after.waitForSelector('body.card-window', { timeout: 15000, state: 'attached' });
let txt = '';
for (let i = 0; i < 40 && !txt.includes('修复后的独立窗真容'); i++) { await sleep(200); txt = await after.evaluate(() => document.querySelector('.canvas-tab-pane')?.textContent || ''); }
await sleep(800);
await after.screenshot({ path: join(OUT, 'after-new-url-content.png') });
console.log('after content ok:', txt.includes('修复后的独立窗真容'));
await browser.close(); server.close();
