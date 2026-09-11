// tmp-canvas-html-repro.mjs — 复现 Canvas 打开 4.9MB slidev 离线 index.html 的死循环
// 静态服务器 :8977 serve web/；routeWebSocket mock；pop.readFile 响应用磁盘真实文件。
// Run: node tests/tmp-canvas-html-repro.mjs
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const BASE = 'http://127.0.0.1:8977';
const FILE = process.env.HOME + '/.nebflow/projects/html-deck-studio/ai-fpga-deck/ppt/index.html';
const SID = 'sess-1';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
const consoleErrs = [];
let secErrCount = 0;
page.on('console', m => { if (m.type() === 'error') consoleErrs.push(m.text().slice(0, 300)); });
page.on('pageerror', e => {
  const s = String(e.message || e);
  if (s.includes('replaceState')) secErrCount++;
  consoleErrs.push('[pageerror] ' + s.slice(0, 300));
});
await page.route('**/api/**', r => r.fulfill({ json: {} }));
// C 批（票据腿）：该件无 nf-file 专属 mock，但 srcdoc 内的模块/素材仍要走
// 票据 URL。补一条最小 nf-file mock（先判票）+ 票据假票 mock（最后注册）。
await page.route('**/api/nf-file**', async r => {
  if (await ticketGuard(r)) return;
  const p = new URL(r.request().url()).searchParams.get('path') || '';
  try { return r.fulfill({ body: readFileSync(p), contentType: 'application/octet-stream' }); }
  catch { return r.fulfill({ status: 404 }); }
});
await installTicketMock(page);

let serverWs = null;
await page.routeWebSocket(/\/ws/, ws => {
  serverWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    if (m.type === 'readFile' || m.type === 'pop.readFile') {
      console.log('[ws]', m.type, m.path);
      const content = readFileSync(FILE, 'utf8');
      ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, fileName: 'index.html', itemType: 'html', content, size: content.length }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
});

await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
await sleep(600);

// 真实链路：fileContent 帧 → explorer handler → workspace-open-item → canvas 打开
const content = readFileSync(FILE, 'utf8');
serverWs.send(JSON.stringify({ type: 'fileContent', path: FILE, absPath: FILE, fileName: 'index.html', itemType: 'html', content, size: content.length }));
console.log('[probe] fileContent frame sent, observing 12s...');

// 冻结检测：每 2s 做一次限时 evaluate；若主线程卡死，evaluate 会超时
let alive = true;
for (let i = 0; i < 6; i++) {
  await sleep(2000);
  try {
    const t = await Promise.race([
      page.evaluate(() => performance.now()),
      new Promise((_, rej) => setTimeout(() => rej(new Error('EVAL-TIMEOUT')), 1500)),
    ]);
    const iframes = await Promise.race([
      page.evaluate(() => document.querySelectorAll('iframe[data-nf-canvas-html]').length),
      new Promise((_, rej) => setTimeout(() => rej(new Error('EVAL2-TIMEOUT')), 1500)),
    ]);
    console.log(`[t+${(i + 1) * 2}s] main-thread alive, perf=${Math.round(t)}, canvas-iframes=${iframes}, replaceState-errors=${secErrCount}`);
  } catch (e) {
    console.log(`[t+${(i + 1) * 2}s] MAIN THREAD FROZEN (${e.message})`);
    alive = false;
    break;
  }
}
console.log('[console errors]', consoleErrs.length ? consoleErrs.slice(0, 10) : 'none');
console.log(alive ? 'RESULT: no freeze' : 'RESULT: FROZEN (reproduced)');
await browser.close();
