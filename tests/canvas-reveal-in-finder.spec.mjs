#!/usr/bin/env node
// tests/canvas-reveal-in-finder.spec.mjs — 桌面端 canvas 页签右键「在访达 / 资源管理器 /
// 文件管理器中打开」门禁（canvas-reveal-in-finder 批 2026-11-19）。
//
// 作者令逐字（19:39）：「电脑端的canvas标签页右键，多一个选项，在访达/文件浏览器打开。」
//
// ── 为什么要有这个闸（每条断言的牙口在哪）──────────────────────────────────
// 这一面全是「看起来在、其实没做对」的肥沃土壤：文案写死在前端（平台猜错也绿）、
// 菜单项在非文件页签也长出来（右键 diff 也能点）、文件已被删还照样可点（点了静默
// 失败）、为了弹菜单把 document 级 contextmenu 一并吃掉（浏览器默认菜单没了）。
// 所以本闸的读数全部打在**真右键路径 + 真请求 body + 真 DOM 态**上：
//
//   R1 文案随**引擎**平台：三个平台档（finder / explorer / file-manager）各起一档，
//      菜单项文案逐字等于该档文案。前端若改成猜 navigator.platform，三档会同时
//      变成同一句 ⇒ 红。
//   R2 文件页签出现 + 动作真的发出去：真右键 → 点项 ⇒ **恰好一条**不带 dryRun 的
//      POST /api/reveal-path，body.path = 该页签 absPath（含空格的路径逐字不变），
//      成功后菜单收起。
//   R3 非文件页签零命中：diff 虚拟页签（type 'filechanges'）、URL 页签、面板页签
//      三档右键都不得出现该项。
//   R4 文件已不存在 ⇒ 置灰 + 可见原因：disabled / aria-disabled / title / 副行四个
//      读数 + 点击只出提示、**不发动作请求**（不静默、也不假装成功）。
//   R5 引擎说不支持（supported:false）⇒ 零命中：本项不是前端自己长出来的。
//   R6 不污染浏览器默认右键菜单：页签上 contextmenu 被 defaultPrevented，
//      页面空白处不被拦截且不弹本菜单。
//   R7 手机层零改动：mobile-layer 下即使派发 contextmenu，也不出现该项。
//
// ── 装置 ─────────────────────────────────────────────────────────────────
// 零宿主接触：本地静态服务器（默认端口 18482）供仓内 web 树 + 本机 WS mock；
// 唯一后端接触面 = `page.route('**/api/reveal-path')` 的 mock，其**判据由真实文件
// 系统回答**（existsSync），所以「文件在 / 已不存在」两档不是自说自话。
// 结构性不可能碰到宿主 6138（不建任何到它的连接）。
//
// 用法：
//   node tests/canvas-reveal-in-finder.spec.mjs                 # 门禁（默认仓内 web 树）
//   node tests/canvas-reveal-in-finder.spec.mjs --web=<dir>     # 跑在另一棵检出上
//   node tests/canvas-reveal-in-finder.spec.mjs --shots=<dir>   # 另存右键盘面截图
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 装置错误。

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const argOf = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const WEB_DIR = resolve(argOf('web', process.env.NEBFLOW_WEB_ROOT || join(ROOT, 'src/main/resources/web')));
const PORT = Number(argOf('port', 18482));
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = argOf('shots', join(ROOT, 'evidence', 'canvas-reveal-in-finder'));
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 夹具：两个真实文件 + 一个「已删除」的路径 ─────────────────────────────
// 名字里带空格与括号：argv 单 token 的安全性在这里被**逐字**验证（body.path 与
// 该字符串逐字节相等，不被任何编码/分词改写）。
const FIX = '/tmp/nf-reveal-gate';
mkdirSync(FIX, { recursive: true });
const ALIVE = join(FIX, '把 报告 (终版).md');
const GONE = join(FIX, '已经 删掉的稿.md');
writeFileSync(ALIVE, '# 活着的文件\n\n用于「在访达中显示」门禁。\n');
const exists = (p) => existsSync(p);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon',
};
if (!existsSync(join(WEB_DIR, 'index.html'))) {
  console.error(`装置错误：--web 指向的树里没有 index.html（${WEB_DIR}）`);
  process.exit(2);
}
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  let buf;
  try { buf = readFileSync(file); } catch { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
  res.end(buf);
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}

const pwRes = loadPlaywright();
const pw = pwRes.mod.playwright || pwRes.mod;
const { chromium } = pw;
const browser = await chromium.launch();

/**
 * 起一档桌面视口。`capability` = 引擎侧能力面应答（GET /api/reveal-path）；
 * `api.calls` 记录 POST 面（dryRun 与实际动作分开记）。
 */
async function boot({ width = 1440, height = 900, mobile = false, colorScheme = 'dark',
  locale = 'zh-CN', capability = { supported: true, platform: 'macos', target: 'finder' } } = {}) {
  const ctx = await browser.newContext({
    viewport: { width, height }, colorScheme, locale, hasTouch: !!mobile,
  });
  await ctx.addInitScript((loc) => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(k + '_token', 't');
      localStorage.setItem(k + '_locale', loc);
      localStorage.setItem(k + '_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    }
    localStorage.setItem('stand_sw', '0');
  }, locale);
  const api = { calls: [], get: 0 };
  // 兜底先注册（Playwright 的 route 按**注册逆序**匹配：后注册者优先），再由下面
  // 的 /api/reveal-path 具体面覆盖它。
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  // 能力面：引擎侧平台（本闸的 R1/R5 读数口）
  await ctx.route('**/api/reveal-path', async (route) => {
    const req = route.request();
    if (req.method() === 'GET') {
      api.get++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(capability) });
    }
    let body = {};
    try { body = JSON.parse(req.postData() || '{}'); } catch { body = {}; }
    api.calls.push({ path: body.path, dryRun: body.dryRun === true });
    if (!body.path || !exists(body.path)) {
      return route.fulfill({
        status: 404, contentType: 'application/json',
        body: JSON.stringify({ ok: false, code: 'not-found', error: `no regular file at ${body.path}` }),
      });
    }
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        ok: true, platform: capability.platform || 'macos', target: capability.target || 'finder',
        path: body.path, command: ['/usr/bin/open', '-R', body.path],
        ...(body.dryRun ? { exists: true, regularFile: true, dryRun: true } : { revealed: true }),
      }),
    });
  });
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }],
      activeId: 's1', folders: [],
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  const page = await ctx.newPage();
  const jserr = [];
  page.on('pageerror', (e) => jserr.push(String(e.message || e).slice(0, 200)));
  await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 20000 });
  await sleep(900);
  return { ctx, page, api, jserr };
}

/** 打开一个真实文件的页签（供内容，不走 WS 读盘：本闸只关心菜单）。 */
async function openFileTab(page, absPath) {
  await page.evaluate(({ path }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop(),
        absPath: path, content: '# 夹具\n', pinned: true,
      },
    }));
  }, { path: absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 15000, state: 'attached' });
  await sleep(400);
}

/** 右键「桌面端页签面」= 顶部标题胶囊（canvas.js:initCardHeadGestures 的 hdr）。 */
async function rightClickTabFace(page) {
  await page.locator('#canvas-panel .canvas-header').click({ button: 'right', timeout: 8000 });
  await sleep(260);
}

/** 菜单读数（本闸唯一读数口）。 */
const MENU_PROBE = `(() => {
  const m = document.querySelector('.canvas-tab-context-menu');
  if (!m) return { open: false };
  const buttons = [...m.querySelectorAll('button')];
  const reveal = buttons.find((b) => b.dataset && b.dataset.reveal === '1');
  return {
    open: true,
    labels: buttons.map((b) => (b.textContent || '').replace(/\\s+/g, ' ').trim()),
    count: buttons.length,
    reveal: reveal ? {
      label: (reveal.childNodes[0] && reveal.childNodes[0].nodeValue || '').trim(),
      text: (reveal.textContent || '').replace(/\\s+/g, ' ').trim(),
      state: reveal.dataset.revealState || '',
      code: reveal.dataset.revealCode || '',
      disabledClass: reveal.classList.contains('disabled'),
      ariaDisabled: reveal.getAttribute('aria-disabled'),
      title: reveal.title || '',
      reason: (reveal.querySelector('.ctm-reason') || {}).textContent || '',
      display: getComputedStyle(reveal).display,
      visibility: getComputedStyle(reveal).visibility,
    } : null,
  };
})()`;

const TOAST_PROBE = `(() => {
  const nodes = [...document.querySelectorAll('.nebflow-toast, [class*="toast"]')]
    .filter((n) => n.getClientRects().length > 0);
  return { n: nodes.length, texts: nodes.map((n) => (n.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 120)) };
})()`;

const menuOf = (page) => page.evaluate(MENU_PROBE);
const toastOf = (page) => page.evaluate(TOAST_PROBE);

/** 等该项定稿（探测回来）：ok / missing。 */
const settle = (page) => page.waitForFunction(() => {
  const b = document.querySelector('.canvas-tab-context-menu button[data-reveal]');
  return !!b && (b.dataset.revealState === 'ok' || b.dataset.revealState === 'missing');
}, null, { timeout: 8000 }).then(() => true).catch(() => false);

// ═══ R1 + R2：macOS 档 —— 文案 = 引擎给的 finder 文案；点项 ⇒ 真动作请求 ═══
{
  const { ctx, page, api } = await boot({ capability: { supported: true, platform: 'macos', target: 'finder' } });
  await openFileTab(page, ALIVE);
  await rightClickTabFace(page);
  await settle(page);
  const m1 = await menuOf(page);
  await page.screenshot({ path: join(OUT, 'r1-menu-dark.png') });

  const finderLabelOk = !!m1.open && !!m1.reveal && m1.reveal.label === '在访达中显示'
    && m1.reveal.display !== 'none' && m1.reveal.visibility === 'visible';
  record('R1', 'macOS 档：菜单项文案 =「在访达中显示」（引擎给 finder ⇒ 绝非前端猜平台）', finderLabelOk,
    `open=${m1.open} labels=${JSON.stringify(m1.labels)} reveal=${JSON.stringify(m1.reveal && { label: m1.reveal.label, state: m1.reveal.state, display: m1.reveal.display })}`);

  // 点下去：恰好一条动作请求（不带 dryRun），body.path = 页签 absPath 逐字
  const before = api.calls.length;
  await page.locator('.canvas-tab-context-menu button[data-reveal]').click({ timeout: 5000 });
  await sleep(500);
  const after = api.calls.slice(before);
  const m1b = await menuOf(page);
  const live = after.filter((c) => !c.dryRun);
  record('R2', '点项 ⇒ 恰好一条 POST /api/reveal-path{path}（逐字 = 页签 absPath，含空格），成功后菜单收起',
    live.length === 1 && live[0].path === ALIVE && m1b.open === false,
    `newCalls=${JSON.stringify(after)} menuAfterOpen=${m1b.open} expectedPath=${JSON.stringify(ALIVE)}`);

  await ctx.close();
}

// ═══ R1b：另两档平台 —— 文案随引擎换（同一份代码，零平台猜测量） ═══
for (const [target, expected, id] of [
  ['explorer', '在资源管理器中显示', 'R1b-1'],
  ['file-manager', '在文件管理器中打开', 'R1b-2'],
]) {
  const { ctx, page } = await boot({ capability: { supported: true, platform: target === 'explorer' ? 'windows' : 'linux', target } });
  await openFileTab(page, ALIVE);
  await rightClickTabFace(page);
  await settle(page);
  const m = await menuOf(page);
  record(id, `target=${target} ⇒ 文案 =「${expected}」（三档平台文案全由引擎 target 选键）`,
    !!m.open && !!m.reveal && m.reveal.label === expected,
    `target=${target} labels=${JSON.stringify(m.labels)} revealLabel=${JSON.stringify(m.reveal && m.reveal.label)}`);
  await page.screenshot({ path: join(OUT, `${id}-menu-dark.png`) });
  await ctx.close();
}

// ═══ R3：非文件页签零命中（diff 虚拟页签 / URL 页签 / 面板页签） ═══
{
  const { ctx, page } = await boot();
  const legs = [
    ['R3-1', 'diff 阅读面（type filechanges，虚拟件无 absPath）', 'filechanges:v1'],
    ['R3-2', 'URL 页签（type url）', 'url:http://127.0.0.1/blank'],
    ['R3-3', '面板页签（type projects，无 absPath）', 'projects'],
  ];
  for (const [id, name, tabId] of legs) {
    await page.evaluate(async ({ id: tid }) => {
      const c = await import('/js/canvas.js');
      const type = tid.startsWith('filechanges') ? 'filechanges' : tid.startsWith('url:') ? 'url' : tid;
      c.openTab(tid, tid, { type, closable: true, pinned: true });
      c.setActiveTab(tid);
    }, { id: tabId });
    await sleep(350);
    await rightClickTabFace(page);
    await sleep(300);
    const m = await menuOf(page);
    record(id, `${name}：右键菜单里**没有**该项`,
      !!m.open && m.reveal === null && m.count >= 1,
      `tab=${tabId} open=${m.open} buttons=${m.count} reveal=${JSON.stringify(m.reveal)} labels=${JSON.stringify(m.labels)}`);
    await page.evaluate(async ({ id: tid }) => {
      const c = await import('/js/canvas.js');
      c.closeTab(tid);
    }, { id: tabId });
    await sleep(250);
  }
  await ctx.close();
}

// ═══ R4：文件已不存在 ⇒ 置灰 + 可见原因；点击不静默、不发动作请求 ═══
{
  const { ctx, page, api } = await boot();
  await openFileTab(page, GONE);
  await rightClickTabFace(page);
  const settled = await settle(page);
  const m = await menuOf(page);
  await page.screenshot({ path: join(OUT, 'r4-missing-dark.png') });
  const greyOk = settled && !!m.reveal && m.reveal.state === 'missing'
    && m.reveal.disabledClass === true && m.reveal.ariaDisabled === 'true'
    && m.reveal.title.includes('文件已不存在') && m.reveal.reason.trim() === '文件已不存在'
    && m.reveal.code === 'not-found';
  record('R4', '文件已删除 ⇒ 置灰（disabled + aria-disabled）+ 可见原因（副行「文件已不存在」+ title）',
    greyOk,
    `settled=${settled} reveal=${JSON.stringify(m.reveal)}`);

  // 点置灰项：出提示、不发动作请求（不是静默失败，也不假装成功）
  const before = api.calls.length;
  await page.locator('.canvas-tab-context-menu button[data-reveal]').click({ timeout: 5000, force: true });
  await sleep(500);
  const after = api.calls.slice(before);
  const t = await toastOf(page);
  const stillOpen = await menuOf(page);
  record('R4b', '点置灰项 ⇒ 屏上明确提示同一原因、零动作请求、不静默关闭菜单',
    after.filter((c) => c.dryRun === false).length === 0
      && t.n > 0 && t.texts.some((x) => x.includes('文件已不存在'))
      && stillOpen.reveal && stillOpen.reveal.state === 'missing',
    `newCalls=${JSON.stringify(after)} toasts=${JSON.stringify(t.texts)} stillMissing=${stillOpen.reveal && stillOpen.reveal.state}`);
  await ctx.close();
}

// ═══ R5：引擎说不支持 ⇒ 零命中 ═══
{
  const { ctx, page, api } = await boot({ capability: { supported: false, platform: 'linux', target: null } });
  await openFileTab(page, ALIVE);
  await rightClickTabFace(page);
  await sleep(600);
  const m = await menuOf(page);
  record('R5', '引擎能力面 supported:false ⇒ 文件页签菜单里也**没有**该项（不是前端自己长出来的）',
    !!m.open && m.reveal === null && api.get >= 1,
    `open=${m.open} reveal=${JSON.stringify(m.reveal)} capabilityGETs=${api.get} labels=${JSON.stringify(m.labels)}`);
  await ctx.close();
}

// ═══ R6：不污染浏览器默认右键菜单 ═══
{
  const { ctx, page } = await boot();
  await openFileTab(page, ALIVE);
  const probes = await page.evaluate(() => {
    const fire = (el) => {
      const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 600, clientY: 40 });
      el.dispatchEvent(ev);
      return ev.defaultPrevented;
    };
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const onTab = fire(hdr);
    const menuAfterTab = !!document.querySelector('.canvas-tab-context-menu');
    // 先收起菜单，再在页面空白处（对话列）试一次：本批不得在全局吃掉右键
    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    const blank = fire(document.body);
    return { onTab, blank, menuAfterTab };
  });
  await sleep(300);
  record('R6', '页签上 contextmenu 被接管（defaultPrevented + 菜单真弹出）· 页面空白处不被拦截（false）⇒ 浏览器默认菜单照旧',
    probes.onTab === true && probes.blank === false && probes.menuAfterTab === true,
    `onTabPrevented=${probes.onTab} menuAfterTab=${probes.menuAfterTab} blankPrevented=${probes.blank}`);
  await ctx.close();
}

// ═══ R7：手机层零改动（窄屏没有右键；本项在 mobile-layer 下不挂） ═══
{
  const { ctx, page } = await boot({ width: 375, height: 812, mobile: true });
  await openFileTab(page, ALIVE);
  const probe = await page.evaluate(async ({ path }) => {
    const c = await import('/js/canvas.js');
    c.setActiveTab('file:' + path);
    // 手机层没有右键：这里主动派发一次 contextmenu（等价于 Android 长按），
    // 判据 = 「即使派发了也不出现该项」。
    const tabEl = document.querySelector('#canvas-tab-bar .canvas-tab')
      || document.querySelector('#canvas-panel .canvas-header');
    tabEl.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true, cancelable: true, clientX: 180, clientY: 60,
    }));
    await new Promise((r) => setTimeout(r, 400));
    const m = document.querySelector('.canvas-tab-context-menu');
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      menuOpen: !!m,
      reveal: !!(m && m.querySelector('button[data-reveal]')),
    };
  }, { path: ALIVE });
  record('R7', '手机层（375×812, mobile-layer）：派发 contextmenu 也不出现该项',
    probe.mobileLayer === true && probe.reveal === false,
    `mobileLayer=${probe.mobileLayer} menuOpen=${probe.menuOpen} reveal=${probe.reveal}`);
  await ctx.close();
}

// ═══ 收尾 ═══
await browser.close();
server.close();
writeFileSync(join(OUT, 'gate-readout.json'), JSON.stringify({ rows, pass, fail }, null, 2));
console.log(`\ncanvas-reveal-in-finder: ${pass}/${pass + fail} 通过${fail ? `（${fail} 红）` : ''}`);
process.exit(fail ? 1 : 0);
