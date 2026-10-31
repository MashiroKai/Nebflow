// shoot-ask-fix.mjs — ask mode 修复批 2026-10-31 证据截图（作者令「回报：五件逐条
// + 工具设计清单 + 截图」）。复用 canvas-ask-dock.spec.mjs 同款 harness（静态 serve
// web 树 + routeWebSocket mock），抓三张：
//   01-waiting-single-bubble.png — 发问等待期：typing 单件、零空 ai 泡（④修复前
//      现场 = 空泡叠 typing 双泡，见作者截屏 3）；
//   02-md-rendered.png — 流式答复 markdown 渲染成品（h2/列表/表格/代码块全套，
//      ①修复前现场 = 裸 markdown 字面量，见作者截屏 1）；
//   03-liquid-glass-v2-fade.png — 顶带渐隐（液体玻璃 v2 材质 + 列顶留白）。
// Run: node evidence/20261031_ask-fix-batch/shoot-ask-fix.mjs
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from '../../tests/nf-ticket-mock.mjs';

const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../../src/main/resources/web');
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
mkdirSync(HERE, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file); // 先读后写：404 不得在 writeHead 之后再抛
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let askWs = null;
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
});
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('PAGEERROR', String(e.message || e).slice(0, 200)));
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route(/\/api\/nf-file\?/, async (route) => {
  if (await ticketGuard(route)) return;
  const url = new URL(route.request().url());
  return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${url.searchParams.get('path') || ''}\n\nmock body\n` });
});
await installTicketMock(page);
await page.routeWebSocket(/\/ws/, (ws) => {
  askWs = ws;
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    if (m.type === 'askOpen') {
      const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
      ws.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || 'file', messages: [] }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 15000 });
await sleep(600);

// 开文件卡 → 开 ask dock
const absPath = '/tmp/ask-fix-evidence/液体玻璃 v2 笔记.md';
await page.evaluate(({ absPath }) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + absPath, itemType: 'markdown', title: '液体玻璃 v2 笔记.md', content: '# 笔记\n', absPath, pinned: true },
  }));
}, { absPath });
await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000 });
await sleep(400);
await page.click('.ask-fab');
await sleep(400);

// ① 发问 → 等待期截图（单泡面）
await page.fill('.ask-dock input', '这份文件的 v2 改了什么？');
await page.press('.ask-dock input', 'Enter');
await sleep(250);
const waiting = await page.evaluate(() => ({
  ai: document.querySelectorAll('.ask-dock .ask-bub.ai').length,
  typing: document.querySelectorAll('.ask-dock .ask-bub.typing').length,
  user: document.querySelectorAll('.ask-dock .ask-bub.user').length,
}));
console.log('WAITING', JSON.stringify(waiting));
const dock = page.locator('.ask-dock');
await dock.screenshot({ path: join(HERE, '01-waiting-single-bubble.png') });

// ② 流式 markdown 答复（含标题/列表/表格/代码块——修复前呈裸 md 字面量）
const sid = sidOf(absPath);
const MD = [
  '## v1 → v2 改了什么\n\n',
  '- **顶缘渐隐带**：液体玻璃 v2 材质\n',
  '- 发送键：墨玻璃药丸\n\n',
  '| 面 | v1 | v2 |\n|---|---|---|\n| 顶栏 | 实体栏 | 渐隐带 |\n| 按钮 | 实色 | 折射玻璃 |\n\n',
  '```css\n#g2-edge-fade {\n  backdrop-filter: blur(18px) saturate(1.5);\n}\n```',
].join('');
wsSend: {
  for (let i = 0; i < MD.length; i += 24) {
    askWs.send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: MD.slice(i, i + 24) }));
    await sleep(40);
  }
  askWs.send(JSON.stringify({ type: 'done', sessionId: sid }));
}
await sleep(400);
const rendered = await page.evaluate(() => {
  const b = [...document.querySelectorAll('.ask-dock .ask-bub.ai')].pop();
  return {
    h2: !!b.querySelector('h2'), ul: !!b.querySelector('ul li'), table: !!b.querySelector('table td'),
    pre: !!b.querySelector('pre code'), raw: b.textContent.includes('|---|'),
  };
});
console.log('RENDERED', JSON.stringify(rendered));
await dock.screenshot({ path: join(HERE, '02-md-rendered.png') });

// ③ 渐隐带材质读数 + 多轮对话顶带截图
const fade = await page.evaluate(() => {
  const vcs = getComputedStyle(document.querySelector('.ask-colwrap .ask-veil'));
  const wcs = getComputedStyle(document.querySelector('.ask-colwrap'));
  return { filter: vcs.backdropFilter || vcs.webkitBackdropFilter, padTop: wcs.paddingTop, bg: vcs.backgroundImage.slice(0, 60) };
});
console.log('FADE', JSON.stringify(fade));
for (let i = 0; i < 3; i++) {
  await page.fill('.ask-dock input', `补充问题 ${i + 1}：折射强度参数？`);
  await page.press('.ask-dock input', 'Enter');
  await sleep(150);
  askWs.send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: `回答 ${i + 1}：feTurbulence 低频次噪声 + 克制 scale（§7 降级路径同述）。` }));
  askWs.send(JSON.stringify({ type: 'done', sessionId: sid }));
  await sleep(250);
}
await dock.screenshot({ path: join(HERE, '03-liquid-glass-v2-fade.png') });

await browser.close();
server.close();
console.log('DONE →', HERE);
