import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { installTicketMock } from './nf-ticket-mock.mjs';
const BASE = 'http://127.0.0.1:8977';
const FILE = process.env.HOME + '/.nebflow/projects/html-deck-studio/ai-fpga-deck/ppt/index.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(([tok, file]) => {
  localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
  const p = { v: 2, tabs: [{ id: 'file:' + file, title: 'index.html', type: 'file', absPath: file, pinned: false, closable: true }], activeTabId: 'file:' + file };
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify(p));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify(p));
}, ['t', FILE]);
const page = await ctx.newPage();
await page.route('**/api/**', r => r.fulfill({ json: {} }));
// C 批（票据腿）：同 tmp-recursion-proof.mjs —— 假票 mock 必须后注册。
const mint = await installTicketMock(page);
let readFileCount = 0;
await page.routeWebSocket(/\/ws/, ws => {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    if (m.type === 'readFile' || m.type === 'pop.readFile') {
      readFileCount++;
      const content = readFileSync(FILE, 'utf8');
      ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, fileName: 'index.html', itemType: 'html', content, size: content.length }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
const depthOf = async () => {
  // 递归数 iframe 嵌套深度
  async function d(frame) {
    let kids;
    try { kids = await Promise.race([frame.evaluate(() => document.querySelectorAll('iframe').length), new Promise((_, rej) => setTimeout(() => rej(new Error('busy')), 1200))]); }
    catch { return -1; } // busy/cross-origin
    if (!kids) return 0;
    let max = 0;
    for (const c of frame.childFrames()) { const cd = await d(c); if (cd > max) max = cd; }
    return 1 + max;
  }
  return d(page.mainFrame());
};
for (let i = 0; i < 10; i++) {
  await sleep(3000);
  const t0 = Date.now();
  const depth = await depthOf();
  console.log(`[t+${(i + 1) * 3}s] iframe-depth=${depth} (${Date.now() - t0}ms) frames=${page.frames().length} readFile=${readFileCount}`);
}
await browser.close();
