// 回归：普通 HTML（无 router）经 fileContent 链路正常渲染 + 无 pane 替换
import { chromium } from 'playwright';
import { installTicketMock } from './nf-ticket-mock.mjs';
const BASE = 'http://127.0.0.1:8977';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const NORMAL = '<!DOCTYPE html><html><head><title>t</title></head><body><h1 id="x">Hello Canvas</h1><p>normal html</p></body></html>';
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => { localStorage.setItem('nebflow_token','t'); localStorage.setItem('neblink_token','t'); });
const page = await ctx.newPage();
page.on('pageerror', e => console.log('[pageerror]', String(e.message||e).slice(0,120)));
await page.route('**/api/**', r => r.fulfill({ json: {} }));
// C 批（票据腿）Dg4：普通 HTML 无本地引用 ⇒ 候选集为空 ⇒ 零 mint 请求。
// 这里装的是一个「计数 + 假票」mock：请求数必须是 0，而不是「有请求也能过」。
const mint = await installTicketMock(page);
await page.routeWebSocket(/\/ws/, ws => {
  ws.onMessage(raw => { let m; try{m=JSON.parse(raw)}catch{return;} if(m.type==='getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0})); });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
await sleep(500);
// 直接在主页面里验门控不误伤：nfEmbedded 不应存在
const flagMain = await page.evaluate(() => document.documentElement.dataset.nfEmbedded || 'unset');
// 正常 HTML 文件打开
await page.evaluate((content) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:/tmp/normal.html', itemType: 'html', title: 'normal.html', content, absPath: '/tmp/normal.html', pinned: false },
  }));
}, NORMAL);
await sleep(1500);
const r = await page.evaluate(() => {
  const ifr = document.querySelector('iframe[data-nf-canvas-html]');
  const paneText = ifr?.closest('.canvas-tab-pane')?.textContent || '';
  return { iframe: !!ifr, noticeShown: paneText.includes('Rendering was stopped') };
});
let inner = null;
try { inner = await page.frames().find(f => f.url() === 'about:srcdoc')?.evaluate(() => document.getElementById('x')?.textContent); } catch {}
// Dg4（§4.3 F1）：无本地引用的 HTML 不得发起任何 /api/nf-ticket 请求。
const mintCount = mint.count();
console.log(JSON.stringify({ flagMain, ...r, innerH1: inner, mintRequests: mintCount }));
if (mintCount !== 0) {
  console.log(`FAIL  Dg4 zero mint requests for a local-reference-free HTML — got ${mintCount}`);
  process.exitCode = 1;
} else {
  console.log('PASS  Dg4 zero mint requests for a local-reference-free HTML');
}
await browser.close();
