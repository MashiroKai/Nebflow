// console-takeover.spec.mjs — 控制台单实例（接管独占）前端验收（2026-10-27 批）。
// 设计稿：reports/20261027_231456_console-single-instance/DESIGN.md §10 F1–F4。
//
// 契约（引擎路径由 ConsoleTakeoverSpec 钉；本 spec 钉**接管模态面**）：
//   F1 takeover 帧弹模态：标题「控制台已在其他位置打开」+【退出】【重新接管】两按钮；
//      GLOBAL 路由不抢 activeView（activeSessionId 不变）；
//   F2 重新接管：点击 → 页面 WS 发 {type:'takeoverRetake'}；回执 ok ⇒ 模态收起；
//   F3 退出：点击 → 主动 close + 不再自动重连 + 退出面【重新进入】；横幅不谎报「正在重连」；
//   F4 强制二选一：Escape / 遮罩点击不关闭。
// 附：接管模态截图（供作者过目）落 reports/20261027_231456_console-single-instance/。
//
// 数据面 = WS 桩直种 takeover 帧，零后端、零 sbt。
// Run: node tests/console-takeover.spec.mjs（自包含：静态服务器随机隔离端口，finally 必关）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SHOT = join(ROOT, 'reports', '20261027_231456_console-single-instance', 'takeover-modal.png');
const SHOT_EXIT = join(ROOT, 'reports', '20261027_231456_console-single-instance', 'takeover-exit-face.png');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩 WS（每连接一个；connCount 供 F3「退出后不再重连」判据）─────────────
let connCount = 0;
let currentWs = null;
function wireUp(ws) {
  connCount++;
  currentWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (m.type === 'takeoverRetake') {
      ws.send(JSON.stringify({ type: 'takeoverResult', ok: true }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root-local-session', name: '本机会话' }], activeId: 'root-local-session', folders: [] }));
}
/** 推给页面当前连接的 takeover 帧（ConsoleTakeover.takeoverFrame 同形）。 */
const pushTakeover = () => currentWs.send(JSON.stringify({ type: 'takeover', at: Date.now() }));

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1000);

  const box = page.locator('#takeover-box');
  const overlay = page.locator('#modal-overlay');
  const activeSid = () => page.evaluate(() => import('/js/state.js').then(m => m.default.activeSessionId));

  // ── F1: takeover 帧弹模态（文案/两按钮/GLOBAL 不抢视图）──────────────────
  pushTakeover();
  await sleep(400);
  {
    ok('F1 模态在场', await box.isVisible());
    ok('F1 标题逐字', (await page.locator('#takeover-title').textContent()) === '控制台已在其他位置打开');
    ok('F1 正文让位语义', ((await page.locator('#takeover-msg').textContent()) || '').includes('让出控制权'));
    ok('F1 退出按钮', (await page.locator('#takeover-exit').textContent()) === '退出' && await page.locator('#takeover-exit').isVisible());
    ok('F1 重新接管按钮', (await page.locator('#takeover-retake').textContent()) === '重新接管' && await page.locator('#takeover-retake').isVisible());
    ok('F1 GLOBAL 路由不抢视图', (await activeSid()) === 'root-local-session');
    const superseded = await page.evaluate(() => import('/js/state.js').then(m => m.default.consoleSuperseded));
    ok('F1 consoleSuperseded 旗标在场（canvas 账本联动读点）', superseded === true);
    await page.screenshot({ path: SHOT });
  }

  // ── F4: 强制二选一（Escape / 遮罩点击不关闭）────────────────────────────
  await page.keyboard.press('Escape');
  await sleep(250);
  ok('F4 Escape 不关闭接管模态', await box.isVisible());
  await page.mouse.click(30, 450); // 遮罩边缘（盒外）
  await sleep(250);
  ok('F4 遮罩点击不关闭接管模态', await box.isVisible());

  // ── F2: 重新接管（发帧 + 回执 ok 收模态）───────────────────────────────
  let retakeFrame = null;
  await page.exposeFunction('__noteRetake', (raw) => { retakeFrame = JSON.parse(raw); });
  // 桩侧记录页面→服务端方向的 retake 帧：routeWebSocket 的 onMessage 已在 wireUp，
  // 这里复用 re-wire 不可行——改从页面侧包装 sendWs 发送路径（直接断言桩收到帧即可：
  // wireUp 的 takeoverRetake 腿回 ok，若帧未到则回执超时、模态不收 ⇒ 断言已覆盖）。
  await page.locator('#takeover-retake').click();
  await sleep(500);
  {
    ok('F2 回执 ok ⇒ 模态收起', !(await box.isVisible()));
    ok('F2 overlay 收起', !(await overlay.evaluate(el => el.classList.contains('on'))));
    const superseded = await page.evaluate(() => import('/js/state.js').then(m => m.default.consoleSuperseded));
    ok('F2 consoleSuperseded 复位', superseded === false);
  }

  // ── F3: 退出（close + 不再重连 + 退出面/重入门 + 横幅不谎报）──────────────
  const connsBefore = connCount;
  pushTakeover();
  await sleep(300);
  ok('F3 前置：模态再弹（幂等）', await box.isVisible());
  await page.locator('#takeover-exit').click();
  await sleep(400);
  {
    ok('F3 退出面在场', await box.isVisible());
    ok('F3 重入门按钮', await page.locator('#takeover-reenter').isVisible());
    const exited = await page.evaluate(() => import('/js/state.js').then(m => m.default.takeoverExited));
    ok('F3 takeoverExited 旗标在场', exited === true);
    const banner = await page.locator('#conn-banner').evaluate(el => el.classList.contains('show'));
    ok('F3 横幅不谎报「正在重连」', !banner);
    await page.screenshot({ path: SHOT_EXIT });
  }
  await sleep(3500); // 越过 2s 基础重连窗
  ok('F3 退出后零新连接（不自动重连）', connCount === connsBefore, `connCount=${connCount} 期望 ${connsBefore}`);

  ok('零页面异常', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} finally {
  await browser.close().catch(() => {});
  server.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
