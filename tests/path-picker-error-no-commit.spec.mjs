// path-picker-error-no-commit.spec.mjs — 路径直输「错误态不可提交」行为判据
// （picker-trunc 批 ① 返工轮 r3 · 2026-09-22 · 判词位 picker-verify-r2 C1 FAIL 的补臂）
//
// ── 为什么需要本件 ────────────────────────────────────────────────────────
// 判词位（`.nebflow/reports/20260922_214340_picker-verify-r2__picker-trunc.md` §三）
// 判 C1 FAIL：`handleBrowseResult` 在函数顶部**无条件**把 `pathPickerCurrentPath`
// 写成帧里的 `path`，而错误帧的 `path` 正是那个打不开的路径；错误分支只显示错误行
// 就提前 return，**没有回滚**。`pathPickerCurrentPath` 又是 Select 按钮的提交值
// （回调模式 / `setFolderProjectRoot`）⇒ 用户看着面包屑还停在原目录，交出去的却是
// 打错的路径（所见 ≠ 所存）。
//
// 本件把该交互钉成**二值断言**：喂回服务端真实形态的错误帧后，点「选择此目录」
// **不得**把该失败路径交出去 —— 回调模式（Explorer 选择器）与 `setFolderProjectRoot`
// （目录模式）两条提交管道都断。
//
// ── 判据形态（只驱动产品自身的面，零 mock 产品逻辑）────────────────────────
// · 真实 `index.html` + 真实 `/js/sidebar.js`（产品模块，非重写）
// · 入站：**真实 ws 分发入口** `state.ws.onmessage`（main.js 的
//   `onMessage('browseResult') → handleBrowseResult` 挂在其上）—— 非直调 handler
// · 出站：`WebSocket.prototype.send` 原方法打桩（真实 `sendWs` 路径：`state.ws` 仍是
//   真 socket、`readyState` 真为 OPEN）—— 非替换 `state.ws` 对象
// · 用户动作面：真实 `#path-picker-goto-input` 打字 + Enter、真实 `#path-picker-select` 点击
// 说明：本 harness 不走 gateway（无 ws 服务端），故 **只读不改** `WebSocket.prototype`
// 的两处（`send` 记录出站帧、`readyState` 恒 OPEN）—— 这是同族
// `tests/workspace-picker.spec.mjs`（捕获 state.ws.onmessage）的更弱介入：产品的
// `state.ws`、连接对象、消息分发链**原样**。
//
// ── Run（自带静态服务器，无需起 gateway；默认端口 18995）──
//   node tests/path-picker-error-no-commit.spec.mjs
//   PORT=18996 node tests/path-picker-error-no-commit.spec.mjs
//
// 退出码：0 = 全绿；1 = 断言失败；3 = 环境/致命错误。

import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 18995);
const BAD = '/nonexistent/nb-pv3-xyz'; // 从不存在的目录（用户打错的路径）
const GOOD = '/tmp/nb-pv3-fixture'; // 用户真正所在的目录

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

const results = [];
function check(name, ok, extra = '') {
  results.push({ name, pass: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  return !!ok;
}

/** 静态文件服务器：只服务 web 根（与 gateway `jsRoutes` 同一棵树；API 面 404 是预期）。 */
function startStatic() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
    const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');
    const file = join(WEB, rel === '/' ? 'index.html' : rel);
    if (!file.startsWith(WEB) || !existsSync(file)) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(readFileSync(file));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(PORT, '127.0.0.1', () => resolve(server));
  });
}

const { chromium } = await import('playwright');

const server = await startStatic();
const browser = await chromium.launch();
let code = 0;
try {
  const BASE = `http://127.0.0.1:${PORT}`;
  const page = await browser.newPage();
  await page.goto(`${BASE}/index.html`);
  await page.waitForTimeout(1500);

  // ── 0 · DOM 就位（本件驱动的是产品真实的 picker 弹窗）──
  check('A0 picker DOM 就位（弹窗 + 直输框 + Select）',
    await page.evaluate(() => !!document.getElementById('path-picker-select')
      && !!document.getElementById('path-picker-goto-input')
      && !!document.getElementById('path-picker-breadcrumb')));

  // ── 1 · 只读改两处 WebSocket.prototype：记录出站帧 + 恒 OPEN；真实分发入口留作驱动面 ──
  const wired = await page.evaluate(async () => {
    const state = (await import('/js/state.js')).default;
    const proto = WebSocket.prototype;
    window.__sent = [];
    window.__picked = 'NOT-CALLED';
    window.__roots = [];
    const origSend = proto.send;
    proto.send = function (d) {
      try {
        const m = typeof d === 'string' ? JSON.parse(d) : d;
        if (m && m.type === 'setFolderProjectRoot') window.__roots.push(m);
        window.__sent.push(m);
      } catch { window.__sent.push(String(d)); }
      return true; // 真实 send 在无服务端时会抛/丢弃；此处只记不发
    };
    void origSend;
    Object.defineProperty(proto, 'readyState', { configurable: true, get: () => 1 }); // WebSocket.OPEN === 1
    // 真实入站分发入口（ws.js `onmessage` 闭包；main.js 的 onMessage('browseResult') 挂其上）
    window.__entry = state.ws && typeof state.ws.onmessage === 'function' ? state.ws.onmessage : null;
    return { hasEntry: !!window.__entry };
  });
  check('A1 真实 ws 分发入口已捕获（state.ws.onmessage 可达）', wired.hasEntry);

  const inject = (msg) => page.evaluate(
    (m) => window.__entry({ data: JSON.stringify(m) }), msg);

  // ── 2 · 真实导航到「用户所在的目录」（经真实分发入口喂回成功帧）──
  await page.evaluate(async (p) => {
    const sb = await import('/js/sidebar.js');
    sb.openPathPickerCallback(p, (sel) => { window.__picked = sel; });
  }, GOOD);
  await inject({ type: 'browseResult', path: GOOD, entries: [{ name: 'b1', path: `${GOOD}/b1` }], total: 1, truncated: false, query: '' });
  await page.waitForTimeout(150);
  const crumbGood = await page.locator('#path-picker-breadcrumb').innerText();
  check('B1 成功帧经真实入口到达 ⇒ 面包屑停在用户所在目录',
    crumbGood.includes('nb-pv3-fixture'), `crumb=${JSON.stringify(crumbGood)}`);

  // ── 3 · 真实用户动作：在直输框打字 + Enter（真发 browsePath 帧）──
  await page.fill('#path-picker-goto-input', BAD);
  await page.locator('#path-picker-goto-input').press('Enter');
  await page.waitForTimeout(200);
  const sent = await page.evaluate(() => window.__sent.filter((m) => m.type === 'browsePath').slice(-1)[0]);
  check('B2 直输 Enter ⇒ 真发 browsePath 帧（携带用户所打的路径）',
    !!sent && sent.path === BAD, `frame=${JSON.stringify(sent)}`);

  // ── 4 · 喂回服务端真实形态的错误帧（browseErrorFrame：空 entries + errorKind + error）──
  await inject({
    type: 'browseResult', path: BAD, entries: [], total: 0, truncated: false, query: '',
    errorKind: 'invalid-path', error: `No such directory: ${BAD}`,
  });
  await page.waitForTimeout(200);

  // 错误行可见（"禁静默"的可观察一半：本批既有行为，须保持不回归）
  const errVis = await page.locator('#path-picker-error').isVisible();
  const errTxt = await page.locator('#path-picker-error').innerText();
  check('C0 错误行内联可见且带服务端明文（保持不回归）',
    errVis && errTxt.includes(BAD), `visible=${errVis} text=${JSON.stringify(errTxt)}`);

  // 面包屑不动（本批既有行为，须保持不回归）
  const crumbNow = await page.locator('#path-picker-breadcrumb').innerText();
  check('C1 失败跳转不移动面包屑（用户仍看到原目录）',
    crumbNow === crumbGood, `good=${JSON.stringify(crumbGood)} after=${JSON.stringify(crumbNow)}`);

  // ── 5 · 核心判据：错误态点「选择此目录」，不得提交那个打不开的路径 ──
  // 5a · 回调模式（Explorer 文件夹选择器）—— 同样走真实入口，不直调 handler
  await page.locator('#path-picker-select').click();
  await page.waitForTimeout(250);
  const picked = await page.evaluate(() => window.__picked);
  check('D1a 错误态 Select（回调模式）不交出失败路径',
    picked !== BAD, `picked=${JSON.stringify(picked)}`);
  check('D1b 错误态 Select（回调模式）回落到用户真正所在的目录',
    picked === GOOD, `picked=${JSON.stringify(picked)} expected=${JSON.stringify(GOOD)}`);

  // 5b · 目录模式（sendWs setFolderProjectRoot）—— 同一条真实入口链，只换打开方式
  await page.evaluate(async () => {
    const sb = await import('/js/sidebar.js');
    sb.openPathPicker('folder-pv3', null); // folderId 非空 ⇒ 目录模式
    window.__sent.length = 0;
    window.__roots.length = 0;
  });
  await page.waitForTimeout(100);
  await inject({ type: 'browseResult', path: GOOD, entries: [], total: 0, truncated: false, query: '' });
  await page.waitForTimeout(100);
  await inject({
    type: 'browseResult', path: BAD, entries: [], total: 0, truncated: false, query: '',
    errorKind: 'invalid-path', error: `No such directory: ${BAD}`,
  });
  await page.waitForTimeout(150);
  await page.locator('#path-picker-select').click();
  await page.waitForTimeout(250);
  const roots = await page.evaluate(() => window.__roots);
  const committedRoot = roots.length ? roots[roots.length - 1].projectRoot : null;
  check('D2a 错误态 Select（目录模式）不交出失败路径',
    committedRoot !== BAD, `projectRoot=${JSON.stringify(committedRoot)}`);
  check('D2b 错误态 Select（目录模式）回落到用户真正所在的目录',
    committedRoot === GOOD, `projectRoot=${JSON.stringify(committedRoot)} expected=${JSON.stringify(GOOD)}`);

  // ── 6 · 反向臂：成功导航后 Select 仍交出该导航到的目录（修复不得把提交面焊死）──
  const OKDIR = '/tmp/nb-pv3-ok';
  await page.evaluate(async (d) => {
    const sb = await import('/js/sidebar.js');
    window.__picked = 'NOT-CALLED';
    sb.openPathPickerCallback(d, (sel) => { window.__picked = sel; });
  }, OKDIR);
  await inject({ type: 'browseResult', path: OKDIR, entries: [], total: 0, truncated: false, query: '' });
  await page.waitForTimeout(150);
  await page.locator('#path-picker-select').click();
  await page.waitForTimeout(200);
  const okPicked = await page.evaluate(() => window.__picked);
  check('E1 反向臂：成功导航后 Select 仍交出该目录（提交面未被焊死）',
    okPicked === OKDIR, `picked=${JSON.stringify(okPicked)}`);
} catch (e) {
  console.error('[spec] FATAL ' + (e && e.stack ? e.stack : String(e)));
  code = 3;
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
}

const fails = results.filter((r) => !r.pass);
console.log(`\n[spec] total=${results.length} pass=${results.length - fails.length} fail=${fails.length}`);
if (fails.length) console.log('[spec] FAILED: ' + fails.map((f) => f.name).join(' ; '));
if (!results.length) code = 3;
process.exit(code || (fails.length ? 1 : 0));
