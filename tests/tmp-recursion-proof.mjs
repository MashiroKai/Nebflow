// 全链路递归实证：预置 canvas_tabs（含 deck 文件标签）→ 嵌套 app restore 必然重开
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
  // 预置：canvas 已打开 deck 文件标签（模拟用户点击后 persistTabs 的状态）
  const tabsPayload = { v: 2, tabs: [{ id: 'file:' + file, title: 'index.html', type: 'file', absPath: file, pinned: false, closable: true }], activeTabId: 'file:' + file };
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify(tabsPayload));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify(tabsPayload));
}, ['t', FILE]);
const page = await ctx.newPage();
await page.route('**/api/**', r => r.fulfill({ json: {} }));
// C 批（票据腿）：restore → viewHtml → resolveLocalFiles 会 POST /api/nf-ticket。
// 没有这条假票 mock，catch-all 会回 `{}` → 无票 URL → 401；该件以 frame 计数
// 为信号，静默失败的素材会改读数（mint 失败必须被隔离在 mock 之外）。
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
for (let i = 0; i < 8; i++) {
  await sleep(2500);
  const frames = page.frames();
  const nestedApp = frames.filter(f => f.url().includes('index.html')).length - 1;
  const srcdoc = frames.filter(f => f.url() === 'about:srcdoc').length;
  console.log(`[t+${(i + 1) * 2.5 | 0}s] frames=${frames.length} nested-app=${nestedApp} srcdoc=${srcdoc} readFile=${readFileCount}`);
  if (frames.length > 30) { console.log('>>> RECURSION CONFIRMED'); break; }
}
await browser.close();
