// cardwin-session-scenarios.spec.mjs — 卡窗白屏修复批（2026-10-22）场景钉。
//
// 作者实测：控制台卡片「独立窗口打开」⇒ link.mystand.space 独立窗整页空白。
// 根因 = 打开链落 `/index.html?card-window=1`，而网关静态路由只把 `/` 派给
// index（WebSocketRoutes `GET -> Root`）；`/index.html` 落根文件白名单
// （sw.js/favicon 族）之外 ⇒ **404 + Content-Length: 0 空体** —— 独立窗渲染的
// 就是这个空 404 体（标题栏 favicon + 域名，内容纯白）。会话不是本次白屏的
// 挡板：同源开窗自带 cookie 会话；死会话形态由 ws.js onclose 自愈链收口。
//
// 本 spec 用**网关忠实静态服**（`/`→index；`/index.html`→404 空体 —— 与
// WebSocketRoutes 逐位同形）钉作者令两场景 + 直连形态：
//   S1 有会话开窗直达内容 —— 主窗点「独立」⇒ 独立窗落 `/?card-window=1…`，
//      自带会话（同源 cookie 形态）直渲真容；全程零 `/index.html` 请求
//      （打开链改口钉死）；引导态面随真容即撤，无登录面。
//   S2 无会话开窗出登录引导（relay 双平面形态）—— WS 握手被拒（测试服
//      upgrade 403，真实拒绝语义）+ portal/status 拒 + login-config 在位
//      ⇒ 页面被引去 `/api/auth/login?return_to=…` 登录腿，且 return_to
//      原样回带 card-window 参数（登录回来直达同一卡窗）。
//   S3 无会话（直连 LAN 形态，无 relay 登录腿）—— WS 拒 + portal/login-config
//      皆不在位 ⇒ 不白屏双面：卡窗引导态面落「登录过期」错误卡（快失败 rides
//      nebflow-ws-rejected + 2s 宽限）+ 账号登录模态（#nebflow-login-modal）。
//
// 运行：node tests/cardwin-session-scenarios.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8162);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const ALPHA = '/tmp/cw-fixture/alpha.md';
const FILE_CONTENTS = new Map([
  [ALPHA, { content: '# alpha\n\nalpha mock body v1\n', itemType: 'markdown' }],
]);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
// 网关根文件白名单（WebSocketRoutes `GET -> Root / fileName`）——其余根路径一律 404。
const ROOT_ALLOW = new Set(['style.css', 'app.js', 'sw.js', 'favicon-32.png', 'favicon-16.png',
  'favicon.svg', 'favicon.ico', 'favicon-180.png', 'favicon-192.png', 'favicon-512.png']);
const ALLOW_PREFIXES = ['/js/', '/css/', '/vendor/', '/brand/', '/assets/'];

/** 网关忠实静态服：`/` 是唯一 index 入口；`/index.html` 404 空体（与
 *  WebSocketRoutes 逐位同形 —— 旧批 spec 静态服 `/`→index 的掩盖面在此反转）；
 *  WS upgrade 一律 403 拒（真实握手拒绝语义，喂无会话形态）。 */
const hits = [];
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  hits.push(url.pathname + url.search);
  let p = url.pathname;
  if (p === '/') {
    // 真网关形态：`/` = brand 注入 index（注入与本 spec 无关，原文即够）。
    try {
      const b = readFileSync(join(WEB_DIR, 'index.html'));
      res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
      res.end(b);
    } catch { res.writeHead(404); res.end(); }
    return;
  }
  const serve = (rel) => {
    const file = join(WEB_DIR, rel);
    if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return true; }
    try {
      const b = readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[rel.slice(rel.lastIndexOf('.'))] || 'application/octet-stream' });
      res.end(b);
    } catch { res.writeHead(404); res.end(); }
    return true;
  };
  if (ALLOW_PREFIXES.some((q) => p.startsWith(q))) { serve(p); return; }
  if (ROOT_ALLOW.has(p.slice(1))) { serve(p); return; }
  // —— 关键钉面：/index.html（含任何 query）= 404 + Content-Length: 0 ——
  res.writeHead(404, { 'Content-Length': '0' });
  res.end();
});
// WS upgrade 一律拒 —— 浏览器侧 ws.onclose 在 opened=false（真握手拒绝）。
server.on('upgrade', (_req, socket) => {
  try { socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); } catch { /* already gone */ }
  socket.destroy();
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

async function newCtx() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  return ctx;
}

/** 有会话形态的 API/WS mock（同 canvas-card-window.spec 口径；pop.readFile 应答真容）。 */
async function mockLiveSession(ctx) {
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
}

/* ════════ S1 有会话：开窗直达内容（零 /index.html 请求）════════ */
{
  const ctx = await newCtx();
  await mockLiveSession(ctx);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(400);
  // 主窗开一张文件卡（真容直灌，同既有 spec 口径）
  const rec = FILE_CONTENTS.get(ALPHA);
  await page.evaluate(({ absPath, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title: 'alpha.md', content, absPath, pinned: true },
    }));
  }, { absPath: ALPHA, content: rec.content, itemType: rec.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ALPHA}"]`, { timeout: 10000, state: 'attached' });

  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  await popup.waitForSelector('body.card-window', { timeout: 15000 });

  ok('S1-1 打开链改口：独立窗落 `/?card-window=1…`（不再 /index.html）',
    popup.url().startsWith(BASE + '/?') && popup.url().includes('card-window=1') &&
    new URL(popup.url()).pathname === '/',
    popup.url());
  ok('S1-2 网关忠实服下零 /index.html 请求（旧链在本地即 404 空体）',
    hits.every((h) => !h.startsWith('/index.html')),
    hits.filter((h) => h.startsWith('/index.html')).slice(0, 3).join(','));

  // 真容渲染（popup 自己的 readFile 通路）
  let cardTxt = '';
  for (let i = 0; i < 30 && !cardTxt.includes('alpha mock body v1'); i++) {
    await sleep(200);
    cardTxt = await popup.evaluate((p) => {
      const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
      return pane ? pane.textContent : '';
    }, ALPHA);
  }
  ok('S1-3 有会话直达内容：卡窗 readFile 真容渲染（非骨架/非空）',
    cardTxt.includes('alpha mock body v1'), cardTxt.slice(0, 60));
  // 引导态面撤除走 400ms 轮询 —— 真容在后 ⇒ 面随撤；轮询等齐再断言。
  let bootGone = false;
  for (let i = 0; i < 25 && !bootGone; i++) {
    await sleep(200);
    bootGone = await popup.evaluate(() => !document.getElementById('nf-cardwin-boot'));
  }
  const faces = await popup.evaluate(() => ({
    boot: !!document.getElementById('nf-cardwin-boot'),
    login: !!document.getElementById('nebflow-login-modal'),
  }));
  ok('S1-4 引导态面随真容即撤 + 无登录面（直达语义）', bootGone && !faces.login, JSON.stringify(faces));
  ok('S1-5 卡窗零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ S2 无会话（relay 形态）：出登录引导，return_to 原样回带 ════════ */
{
  const ctx = await newCtx();
  // 无 WS mock ⇒ 真 upgrade 403（握手拒绝语义）。API：portal 拒 + 登录腿在位。
  // Playwright 路由后注册者优先 ⇒ catch-all 必须最先注册。
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route('**/api/nf-authcheck', (r) => r.fulfill({ status: 403, json: {} }));
  await ctx.route('**/api/portal/status', (r) => r.fulfill({ status: 401, json: {} }));
  await ctx.route('**/api/auth/login-config', (r) => r.fulfill({ status: 200, json: { configured: true } }));
  await ctx.route(/\/api\/auth\/login\?/, (r) => {
    r.fulfill({ status: 200, contentType: 'text/html', body: '<!DOCTYPE html><html><body>STAND LOGIN PAGE</body></html>' });
  });
  const page = await ctx.newPage();
  const cardUrl = BASE + '/?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md';
  // 登录导航与 goto 竞速 —— waitForURL 先武装再 goto；goto 用 commit 即返
  // （等 load 会占住首个文档的加载权，remint 的 location.href 会被延宕/取消）。
  const navP = page.waitForURL(/\/api\/auth\/login\?/, { timeout: 20000 }).then(() => true).catch(() => false);
  await page.goto(cardUrl, { waitUntil: 'commit' });
  const navigated = await navP;
  if (navigated) {
    const u = new URL(page.url());
    const back = decodeURIComponent(u.searchParams.get('return_to') || '');
    ok('S2-1 无会话（relay 形态）出登录引导：引去 /api/auth/login', true, page.url());
    ok('S2-2 return_to 回带 card-window 参数（登录回来直达同一卡窗）',
      back.startsWith('/?') && back.includes('card-window=1') && back.includes('path=' + ALPHA), back);
  } else {
    ok('S2-1 无会话（relay 形态）出登录引导：引去 /api/auth/login', false, page.url());
    ok('S2-2 return_to 回带 card-window 参数（登录回来直达同一卡窗）', false, page.url());
  }
  await ctx.close();
}

/* ════════ S3 无会话（直连 LAN 形态）：不白屏双面 = 错误卡 + 登录模态 ════════ */
{
  const ctx = await newCtx();
  // 无 WS mock + portal/login-config 皆 404 ⇒ remint 无腿，落账号登录模态。
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  const cardUrl = BASE + '/?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md';
  await page.goto(cardUrl);
  await page.waitForSelector('body.card-window', { timeout: 15000, state: 'attached' });

  // 快失败（nebflow-ws-rejected + 2s 宽限）⇒ 引导态面落「登录过期」错误卡
  let failedState = false;
  try {
    await page.waitForSelector('#nf-cardwin-boot[data-state="failed"]', { timeout: 15000 });
    failedState = true;
  } catch { failedState = false; }
  const face = failedState ? await page.evaluate(() => ({
    hint: document.querySelector('#nf-cardwin-boot .nf-cw-hint')?.textContent || '',
    retry: !!document.querySelector('#nf-cardwin-boot .nf-cw-retry'),
    visible: (() => { const el = document.getElementById('nf-cardwin-boot'); if (!el) return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; })(),
  })) : null;
  ok('S3-1 引导态面落错误卡（不白屏；快失败 rides nebflow-ws-rejected）', failedState && !!face?.visible,
    JSON.stringify(face));
  ok('S3-2 错误卡带登录过期文案 + [重试]（reload 重走 remint/登录链）',
    !!face && face.retry && /登录状态可能已过期|会话/.test(face.hint), face?.hint || '');
  // 账号登录模态（showTicketEntry → activateAccountEntry → showLoginModal）
  let loginShown = false;
  try {
    await page.waitForSelector('#nebflow-login-modal', { timeout: 12000, state: 'attached' });
    loginShown = true;
  } catch { loginShown = false; }
  ok('S3-3 账号登录模态在挂（登录引导非白屏）', loginShown);
  ok('S3-4 页面未被引离（直连形态就地出引导，无导航循环）',
    new URL(page.url()).pathname === '/' && new URL(page.url()).searchParams.get('card-window') === '1',
    page.url());
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\n${pass} pass, ${fail} fail`);
process.exit(fail ? 1 : 0);
