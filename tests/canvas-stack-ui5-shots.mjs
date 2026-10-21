// canvas-stack-ui5-shots.mjs — UI 批（作者令 2026-10-21 五件）前后对照截图脚本。
//
// 与批内实现解耦（改动前后各跑一遍，输出目录用 SHOTS_DIR 区分）：
//   · 桌面 1600×1000：常态 / 叠卡开启 / 擦洗中间帧 / 深处卡（快照真实感）
//     / 前缘特写（边缘融合）
//   · 430×932 / 375×812：画布常态对照（窄窗 overlay / 移动层形态不回归）
// 运行：node tests/canvas-stack-ui5-shots.mjs
// 可选 env：SHOTS_DIR（输出目录，默认 evidence/<ts>_canvas-stack-ui5-{before,after}/shots）
//
// 用法（前后对照）：
//   SHOTS_DIR=evidence/..._before/shots node tests/canvas-stack-ui5-shots.mjs   # 改动前
//   SHOTS_DIR=evidence/..._after/shots  node tests/canvas-stack-ui5-shots.mjs   # 改动后

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8153);
const BASE = `http://127.0.0.1:${PORT}`;
const TS = process.env.SHOTS_TS || new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '').replace(/(\d{8})(\d{6}).*/, '$1_$2');
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, `../evidence/${TS}_canvas-stack-ui5/shots`);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const nfFileRoute = () => async (route) => {
  if (await ticketGuard(route)) return;
  const url = new URL(route.request().url());
  const p = url.searchParams.get('path') || '';
  if (/\.svg$/i.test(p)) {
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300">' +
      '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
      '<stop offset="0" stop-color="#5b8def"/><stop offset="1" stop-color="#9b6def"/></linearGradient></defs>' +
      '<rect width="480" height="300" fill="url(#g)"/>' +
      '<circle cx="120" cy="90" r="40" fill="#ffd66b"/>' +
      '<path d="M0 300 L160 150 L280 240 L380 170 L480 300 Z" fill="#2e4a7a"/></svg>';
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: svg });
  }
  return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
};

const MD = (name) => `# ${name}\n\n这段正文用于叠卡快照真实感对照：真实渲染面克隆进卡面缩略，不再是骨架灰条。\n\n## 小节一\n\n- 列表项 A：连续擦洗丝滑可回滚\n- 列表项 B：近卡放大 / 远卡缩小 + 视差位移\n- 列表项 C：边缘阴影柔化羽化，与背景融合过渡\n\n## 小节二\n\n正文段落。堆叠层次靠明暗差而非描边感；滚轮 / 上下拖动换卡的机制保持不变。\n\n> 引用行：堆叠态顶部帮助条整行移除，一个字不留。\n\n\`\`\`js\nconst deck = { scrub: 'continuous', reversible: true };\n\`\`\`\n`;

const HTML_DOC = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,sans-serif;padding:28px;color:#182035}
h1{font-size:24px;margin:0 0 12px} .tag{display:inline-block;padding:3px 10px;border-radius:99px;background:#e8f0ff;color:#3b6fd4;font-size:12px;margin-right:6px}
.card{border:1px solid #dfe6f2;border-radius:12px;padding:14px 16px;margin:12px 0;box-shadow:0 6px 18px rgba(30,50,90,.06)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
</style></head><body><h1>便携剂量仪方案对比</h1>
<div><span class="tag">零基础版</span><span class="tag">对照稿</span></div>
<div class="grid"><div class="card"><b>方案 A</b><p>成本低，部署快，适合小队巡检。</p></div>
<div class="card"><b>方案 B</b><p>精度高，需外接电源，适合固定哨位。</p></div></div>
<p>本页用于 html 卡快照真实感对照（srcdoc 同源 iframe）。</p></body></html>`;

const MD_FILES = ['知识图谱.html', '便携剂量仪方案对比_零基础版.md', '巡检路线优化.md', '任务看板.md', '剂量仪标定手册.md'];

async function bootPage(browser, { viewport = { width: 1600, height: 1000 }, colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => { errors.push(String(e.message || e).slice(0, 200)); console.log('PAGEERROR:', String(e.message || e).slice(0, 200)); });
  page.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE:', m.text().slice(0, 200)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  // 桌面壳等 #activity-bar；≤768px 移动层接管（body.mobile-layer，#activity-bar 隐藏属正常形态）
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 20000 });
  await sleep(800);
  return { ctx, page, errors };
}

async function openItem(page, { itemType, title, content, absPath, ext }) {
  await page.evaluate(({ itemType, title, content, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { itemType, title, content, absPath });
  // 移动层（body.mobile-layer）画布走卡片层形态，桌面 pane 恒 hidden——只等桌面态
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (!mobile) {
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000 });
  }
}

async function panelBox(page) {
  return page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    return { x: p.offsetLeft, y: p.offsetTop, w: p.offsetWidth, h: p.offsetHeight };
  });
}

async function shotDeckSequence(page, tag) {
  const pr = await panelBox(page);
  const clip = { x: Math.max(0, pr.x - 10), y: Math.max(0, pr.y - 10), width: pr.w + 20, height: pr.h + 20 };
  // 常态
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-closed.png`), clip });
  // 开叠卡
  const hasFab = await page.evaluate(() => {
    const f = document.getElementById('deck-fab');
    return !!f && getComputedStyle(f).display !== 'none';
  });
  if (!hasFab) {
    await page.screenshot({ path: join(SHOTS_DIR, `${tag}-2-mobile-no-deck.png`), clip });
    return;
  }
  await page.mouse.move(200, 500);
  await page.click('#deck-fab');
  await sleep(2800);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-3-deck-open.png`), clip });
  // 擦洗中间帧（连续 wheel 到一半就截图）
  await page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    for (let i = 0; i < 6; i++) {
      p.dispatchEvent(new WheelEvent('wheel', { deltaY: 20, bubbles: true, cancelable: true }));
    }
  });
  await sleep(120);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-4-scrub-mid.png`), clip });
  await sleep(2600);
  // 换到第二张后：深处卡 + 前缘特写
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-5-deck-front2.png`), clip });
  // 前缘特写（顶部露边带）
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-6-edge-closeup.png`), clip: { x: clip.x, y: clip.y, width: clip.width, height: Math.min(300, clip.height) } });
  // 收回
  await page.keyboard.press('Escape');
  await sleep(700);
}

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();

/* ── 桌面（暗色为主，作者截图同环境）── */
{
  const { ctx, page, errors } = await bootPage(browser, { colorScheme: 'dark' });
  await openItem(page, { itemType: 'html', title: '知识图谱.html', content: HTML_DOC, absPath: '/tmp/ui5-fixture/知识图谱.html' });
  await sleep(800);
  await openItem(page, { itemType: 'markdown', title: '便携剂量仪方案对比_零基础版.md', content: MD('便携剂量仪方案对比_零基础版.md'), absPath: '/tmp/ui5-fixture/便携剂量仪方案对比_零基础版.md' });
  await openItem(page, { itemType: 'image', title: '巡检路线示意.svg', content: '', absPath: '/tmp/ui5-fixture/巡检路线示意.svg' });
  await sleep(600);
  await openItem(page, { itemType: 'markdown', title: '任务看板.md', content: MD('任务看板.md'), absPath: '/tmp/ui5-fixture/任务看板.md' });
  await openItem(page, { itemType: 'markdown', title: '剂量仪标定手册.md', content: MD('剂量仪标定手册.md'), absPath: '/tmp/ui5-fixture/剂量仪标定手册.md' });
  await sleep(900);
  await shotDeckSequence(page, 'desktop-dark');
  console.log('desktop-dark errors:', errors.length ? errors : 'none');
  await ctx.close();
}
/* ── 桌面浅色（边缘融合对照更敏感）── */
{
  const { ctx, page, errors } = await bootPage(browser, { colorScheme: 'light' });
  await openItem(page, { itemType: 'markdown', title: '便携剂量仪方案对比_零基础版.md', content: MD('便携剂量仪方案对比_零基础版.md'), absPath: '/tmp/ui5-fixture/便携剂量仪方案对比_零基础版.md' });
  await openItem(page, { itemType: 'markdown', title: '巡检路线优化.md', content: MD('巡检路线优化.md'), absPath: '/tmp/ui5-fixture/巡检路线优化.md' });
  await openItem(page, { itemType: 'markdown', title: '任务看板.md', content: MD('任务看板.md'), absPath: '/tmp/ui5-fixture/任务看板.md' });
  await sleep(700);
  await shotDeckSequence(page, 'desktop-light');
  console.log('desktop-light errors:', errors.length ? errors : 'none');
  await ctx.close();
}
/* ── 430 / 375 窄窗对照（移动层形态：叠卡退役，画布卡片层照常）── */
for (const [tag, vp] of [['w430', { width: 430, height: 932 }], ['w375', { width: 375, height: 812 }]]) {
  const { ctx, page, errors } = await bootPage(browser, { viewport: vp, colorScheme: 'dark' });
  await openItem(page, { itemType: 'markdown', title: '便携剂量仪方案对比_零基础版.md', content: MD('便携剂量仪方案对比_零基础版.md'), absPath: '/tmp/ui5-fixture/便携剂量仪方案对比_零基础版.md' });
  await openItem(page, { itemType: 'markdown', title: '任务看板.md', content: MD('任务看板.md'), absPath: '/tmp/ui5-fixture/任务看板.md' });
  await sleep(900);
  // 移动层：点右列「画板」键开文档卡片层（deck fab 在移动层退役 = display:none）
  await page.evaluate(() => {
    const key = document.querySelector('.mh-dd[data-dd="right"] [data-face="canvas"]');
    if (key) key.click();
  });
  await sleep(1200);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-0-viewport.png`) });
  const deckHidden = await page.evaluate(() => {
    const f = document.getElementById('deck-fab');
    return !f || getComputedStyle(f).display === 'none';
  });
  console.log(tag, 'deck-fab hidden(移动层退役):', deckHidden, 'errors:', errors.length ? errors : 'none');
  await ctx.close();
}

await browser.close();
server.close();
console.log('shots →', SHOTS_DIR);
