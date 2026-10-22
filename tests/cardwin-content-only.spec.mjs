// cardwin-content-only.spec.mjs — 独立窗口内容物收口批（2026-10-22 作者令）验收钉。
//
// 作者令（逐字）：「现在独立窗口打开，打开的是整个stand，而不是一个具体的文档，
// 仅允许文档、文件等这些非功能性面板的可独立打开吧」
//
// 收口语义（本 spec 钉死）：
//   C1 内容物入口正常打开 —— 文件卡「独立」⇒ 同源独立窗 `/?card-window=1…`，
//      **首帧即卡面**（index.html 内联经典脚本 pre-paint 落 html.card-window-boot，
//      壳隐藏先于模块图 —— 主窗闪现「整个 stand」在任何网络/缓存条件下不可能），
//      真容渲染、引导面随撤、全功能 UI 面全部 display:none。
//   P1 功能面板无独立窗入口 —— 面板页签（plugins）：卡头 pop 钮隐藏、环卡 pop 钮
//      不建、右键菜单「在独立窗口打开」= 禁用态项 + 提示（title + 点击 toast），
//      点击不弹任何窗；弹出链终拒（popoutCardWindow 内容物门）兜底同一文案。
//   P2 别名不得作为独立窗 URL —— 旧深链 `/index.html?card-window=1…`（网关别名
//      200 形态）落地即被内联门归一到 canonical `/?card-window=1…`（pathname='/'，
//      query 原样）——别名永远不是独立窗的 URL，只是跳板。
//   P3 无 path 卡窗深链 = 非内容物目标 —— `/?card-window=1`（不带 path）即刻
//      失败卡（专用文案），壳保持隐藏，绝不落全功能 UI、不等 12s 超时。
//
// 静态服形态：**现行网关契约**（白屏批后的路由表）——`/` 与 `/index.html` 都是
// brand 前的原始 index（200）；JS/CSS 白名单前缀。与 cardwin-session-scenarios
// 的 404 金丝钉刻意不同：那边钉「打开链零 /index.html 请求」，这边钉「别名
// 形态下深链仍收敛到卡窗且 URL 归一」。
//
// 运行：node tests/cardwin-content-only.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8168);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const ALPHA = '/tmp/cw-content-only/alpha.md';
const FILE_CONTENTS = new Map([
  [ALPHA, { content: '# alpha\n\n内容物收口样本正文 v1\n', itemType: 'markdown' }],
]);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
// 现行网关契约：根文件白名单 + 前缀白名单；`/` 与 `/index.html` 都是 index（别名 200）。
const ROOT_ALLOW = new Set(['style.css', 'app.js', 'sw.js', 'favicon-32.png', 'favicon-16.png',
  'favicon.svg', 'favicon.ico', 'favicon-180.png', 'favicon-192.png', 'favicon-512.png']);
const ALLOW_PREFIXES = ['/js/', '/css/', '/vendor/', '/brand/', '/assets/'];

const hits = [];
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  hits.push(url.pathname + url.search);
  const p = url.pathname;
  const serveIndex = () => {
    try {
      const b = readFileSync(join(WEB_DIR, 'index.html'));
      res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
      res.end(b);
    } catch { res.writeHead(404); res.end(); }
  };
  if (p === '/' || p === '/index.html') { serveIndex(); return; }   // 别名 200（现行契约）
  const serve = (rel) => {
    const file = join(WEB_DIR, rel);
    if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
    try {
      const b = readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[rel.slice(rel.lastIndexOf('.'))] || 'application/octet-stream' });
      res.end(b);
    } catch { res.writeHead(404); res.end(); }
  };
  if (ALLOW_PREFIXES.some((q) => p.startsWith(q))) { serve(p); return; }
  if (ROOT_ALLOW.has(p.slice(1))) { serve(p); return; }
  res.writeHead(404, { 'Content-Length': '0' });
  res.end();
});
server.on('upgrade', (_req, socket) => {
  try { socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); } catch { /* gone */ }
  socket.destroy();
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

async function newCtx(colorScheme = 'dark') {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  return ctx;
}

/** 有会话形态的 API/WS mock（同 cardwin-session-scenarios S1 口径）。 */
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

async function openFileCard(page) {
  const rec = FILE_CONTENTS.get(ALPHA);
  await page.evaluate(({ absPath, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title: 'alpha.md', content, absPath, pinned: true },
    }));
  }, { absPath: ALPHA, content: rec.content, itemType: rec.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ALPHA}"]`, { timeout: 10000, state: 'attached' });
  await sleep(250);
}

/** 卡窗真容等待 + 引导面撤除等待（400ms 轮询，同白屏批口径）。 */
async function waitCardContent(popup, needle) {
  let txt = '';
  for (let i = 0; i < 30 && !txt.includes(needle); i++) {
    await sleep(200);
    txt = await popup.evaluate(() => {
      const pane = document.querySelector('.canvas-tab-pane');
      return pane ? pane.textContent : '';
    });
  }
  let bootGone = false;
  for (let i = 0; i < 25 && !bootGone; i++) {
    await sleep(200);
    bootGone = await popup.evaluate(() => !document.getElementById('nf-cardwin-boot'));
  }
  return { txt, bootGone };
}

/* ════════ C1 内容物入口正常打开 + 首帧即卡面 ════════ */
{
  const ctx = await newCtx();
  await mockLiveSession(ctx);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);
  await openFileCard(page);

  const popState = await page.evaluate(() => {
    const b = document.getElementById('canvas-pop-btn');
    return b ? { hidden: b.hidden, title: b.title } : null;
  });
  ok('C1-0 文件卡入口在挂：卡头 pop 钮可见 + 「独立窗口打开」形',
    !!popState && popState.hidden === false && popState.title === '独立窗口打开', JSON.stringify(popState));

  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  const popErrors = [];
  popup.on('pageerror', (e) => popErrors.push(String(e.message || e).slice(0, 200)));

  const u = new URL(popup.url());
  ok('C1-1 打开链 = `/?card-window=1…`（canonical `/`，别名不在链上）',
    u.pathname === '/' && u.searchParams.get('card-window') === '1' && u.searchParams.get('path') === ALPHA,
    popup.url());

  // 首帧门：html.card-window-boot 由内联经典脚本 pre-paint 落类（先于模块图），
  // boot 完成后仍在挂（与 body.card-window 恒同真，幂等保留）。
  await popup.waitForSelector('body.card-window', { timeout: 15000 });
  const frameGate = await popup.evaluate(() => ({
    htmlBoot: document.documentElement.classList.contains('card-window-boot'),
    bodyMode: document.body.classList.contains('card-window'),
  }));
  ok('C1-2 首帧即卡面：html.card-window-boot 在挂（pre-paint 门）+ body.card-window 运行态',
    frameGate.htmlBoot && frameGate.bodyMode, JSON.stringify(frameGate));

  const { txt, bootGone } = await waitCardContent(popup, '内容物收口样本正文 v1');
  ok('C1-3 卡窗真容：readFile 通路渲染内容物（非骨架/非空）', txt.includes('内容物收口样本正文 v1'), txt.slice(0, 60));
  ok('C1-4 引导态面随真容即撤（防白屏链保留）', bootGone);

  const shell = await popup.evaluate(() => {
    const disp = (sel) => {
      const el = document.querySelector(sel);
      return el ? getComputedStyle(el).display : '(missing)';
    };
    return {
      activity: disp('#activity-bar'), sidebar: disp('#sidebar'), header: disp('#header'),
      chat: disp('#chat'), input: disp('#input-area'),
    };
  });
  ok('C1-5 全功能 UI 面全部不在挂（display:none × 5）',
    Object.values(shell).every((d) => d === 'none'), JSON.stringify(shell));
  ok('C1-6 卡窗零 JS 错误', popErrors.length === 0, popErrors.slice(0, 2).join(' | '));
  ok('C1-7 主窗零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ P1 功能面板无独立窗入口（隐藏 + 禁用态提示） ════════ */
{
  const ctx = await newCtx();
  await mockLiveSession(ctx);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);

  // 应用自己的入口打开面板页签（plugins —— 任务列表族的全功能 UI 面）
  await page.evaluate(async () => { (await import('/js/plugins.js')).openPlugins(); });
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="plugins"]', { timeout: 10000, state: 'attached' });
  await sleep(400);

  const panelEntry = await page.evaluate(() => {
    const b = document.getElementById('canvas-pop-btn');
    return { popBtn: b ? { hidden: b.hidden, display: getComputedStyle(b).display } : null };
  });
  ok('P1-1 面板页签：卡头 pop 钮隐藏（hidden + display:none）',
    !!panelEntry.popBtn && panelEntry.popBtn.hidden === true && panelEntry.popBtn.display === 'none',
    JSON.stringify(panelEntry));

  // 环卡（deck 开）：面板环卡不建 pop 钮
  await page.click('#deck-fab');
  await page.waitForSelector('#deck-layer .deck-card', { timeout: 8000, state: 'attached' });
  await sleep(300);
  const ring = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('#deck-layer .deck-card')];
    return cards.map((c) => ({
      tab: c.getAttribute('data-tab'),
      popBtn: !!c.querySelector('.deck-card-pop'),
    }));
  });
  ok('P1-2 面板环卡不建独立钮（.deck-card-pop 缺席；文件环卡有钮的语义不回归）',
    ring.length > 0 && ring.every((c) => !c.popBtn), JSON.stringify(ring));
  await page.click('#deck-fab');   // 收环，避免遮挡后续右键
  await sleep(300);

  // 胶囊右键菜单：禁用态项 + 提示 + 点击 toast + 零弹窗
  const pagesBefore = ctx.pages().length;
  await page.click('.canvas-header', { button: 'right' });
  await page.waitForSelector('.canvas-tab-context-menu', { timeout: 5000 });
  const menu = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('.canvas-tab-context-menu button')];
    const pop = btns.find((b) => (b.textContent || '').includes('独立窗口打开') || (b.textContent || '').includes('合并回主窗'));
    if (!pop) return null;
    return {
      text: pop.textContent, disabled: pop.classList.contains('disabled'),
      ariaDisabled: pop.getAttribute('aria-disabled'), title: pop.title,
    };
  });
  ok('P1-3 面板页签右键菜单：「在独立窗口打开」= 禁用态项（class disabled + aria-disabled）',
    !!menu && menu.disabled === true && menu.ariaDisabled === 'true', JSON.stringify(menu));
  ok('P1-4 禁用态带提示文案（title = 内容物收口提示）',
    !!menu && (menu.title || '').includes('仅文档') && (menu.title || '').includes('功能面板'),
    menu ? menu.title : '(no item)');

  // aria-disabled 钮 Playwright click 拒点（enabled 门）——本项是「可点但只给提示」
  // 的软禁用面，dispatchEvent 即真实 DOM click 通路。
  await page.dispatchEvent('.canvas-tab-context-menu button.disabled', 'click');
  await sleep(400);
  const toastTxt = await page.evaluate(() => {
    const m = document.querySelector('.nebflow-toast-msg');
    return m ? m.textContent : '';
  });
  const pagesAfter = ctx.pages().length;
  ok('P1-5 点击禁用项 = 提示 toast + 零弹窗（无独立窗产生）',
    toastTxt.includes('仅文档') && pagesAfter === pagesBefore,
    `toast="${toastTxt.slice(0, 40)}" pages ${pagesBefore}→${pagesAfter}`);

  ok('P1-6 主窗零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ P2 别名不得作为独立窗 URL：/index.html 深链归一到 `/` ════════ */
{
  const ctx = await newCtx();
  await mockLiveSession(ctx);
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md');
  await page.waitForSelector('body.card-window', { timeout: 15000 });
  const u = new URL(page.url());
  ok('P2-1 别名深链归一：最终 URL pathname = `/`（别名只是跳板，query 原样保留）',
    u.pathname === '/' && u.searchParams.get('card-window') === '1' && u.searchParams.get('path') === ALPHA,
    page.url());
  ok('P2-2 归一后首帧门照常在挂（html.card-window-boot）',
    await page.evaluate(() => document.documentElement.classList.contains('card-window-boot')));
  const { txt } = await waitCardContent(page, '内容物收口样本正文 v1');
  ok('P2-3 别名跳板落点 = 内容物真容（卡窗链可用性不回归）', txt.includes('内容物收口样本正文 v1'), txt.slice(0, 60));
  await ctx.close();
}

/* ════════ P3 无 path 卡窗深链 = 非内容物目标：即刻失败卡，绝不落全功能 UI ════════ */
{
  for (const scheme of ['dark', 'light']) {
    const ctx = await newCtx(scheme);
    await mockLiveSession(ctx);
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
    await page.goto(BASE + '/?card-window=1');   // 无 path：功能性目标不可独立窗口化
    await page.waitForSelector('body.card-window', { timeout: 15000 });
    // 即刻失败（不等 12s 超时）：失败卡 + 专用文案 + 壳隐藏
    let failed = false;
    for (let i = 0; i < 20 && !failed; i++) {
      await sleep(200);
      failed = await page.evaluate(() => {
        const el = document.getElementById('nf-cardwin-boot');
        return !!el && el.getAttribute('data-state') === 'failed';
      });
    }
    const noPath = await page.evaluate(() => {
      const el = document.getElementById('nf-cardwin-boot');
      const shellHidden = getComputedStyle(document.querySelector('#activity-bar')).display === 'none';
      return {
        state: el ? el.getAttribute('data-state') : '(missing)',
        hint: el ? (el.querySelector('.nf-cw-hint')?.textContent || '') : '',
        retry: el ? getComputedStyle(el.querySelector('.nf-cw-retry')).display : '',
        shellHidden,
      };
    });
    ok(`P3-1[${scheme}] 无 path 深链：即刻失败卡（<3s，非 12s 超时）+ 壳隐藏`,
      failed && noPath.state === 'failed' && noPath.shellHidden, JSON.stringify(noPath));
    ok(`P3-2[${scheme}] 失败文案 = 内容物收口专用提示（非一般失败/登录形态）`,
      noPath.hint.includes('未携带内容路径'), noPath.hint);
    ok(`P3-3[${scheme}] 零 JS 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }
}

await browser.close();
server.close();
console.log(`\n${pass} PASS / ${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
