// console-takeover.spec.mjs — 控制台单实例（接管独占）前端验收（2026-10-27 批；
// 四修批 2026-10-29 作者令；接管必达批 2026-11-01 作者令①②）。
// 设计稿：reports/20261027_231456_console-single-instance/DESIGN.md §10（本批修订：
// F1 卡面同族重做——按钮语换代 g2 药丸族（次级白玻璃/主动作墨玻璃药丸）+ 卡面
// 错误行；F2 接管改 HTTP 腿（POST /api/takeover/retake，回执随响应必达，不再走
// ws 帧）；F2b 失败面错误码分类（拒绝/网络/超时各自有名，落卡面错误行 + toast，
// 不静默）；F3 退出改官网个人主页、F5 内容断喂前端兜底不变）。
//
// 契约（引擎路径由 ConsoleTakeoverSpec 钉；本 spec 钉**接管模态面**）：
//   F1  takeover 帧弹模态：标题「控制台已在其他位置打开」+【退出】【重新接管】两按钮；
//       GLOBAL 路由不抢 activeView；overlay 带 takeover-blur（毛玻璃）；
//       按钮 = g2 药丸族（次级白玻璃药丸 / 主动作墨玻璃药丸，radius 999）；
//   F2  重新接管：点击 → loading 态（disabled + 接管中…）→ HTTP 回执 ok ⇒ reload
//       重新进入（WS 重连 = 全量重同步）；
//   F2b 失败腿：回执 !ok ⇒ 分类文案落卡面错误行（主机拒绝）+ toast + 恢复可点；
//       网络事故腿（连接失败）⇒「无法连接主机」错误行——明确报错不静默；
//       恢复 ok 后再点可成功；
//   F3  退出：直达官网个人主页（brand 契约 profileUrl，from=client 提示随行）；
//   F4  强制二选一：Escape / 遮罩点击不关闭；
//   F5  内容断喂前端兜底：superseded 态到达的内容帧一律不渲染（HTTP 回执可达
//       即其可达面证明）。
//
// 数据面 = WS 桩直种 takeover 帧 + HTTP 接管端点路由桩，零后端、零 sbt。
// Run: node tests/console-takeover.spec.mjs（自包含：静态服务器随机隔离端口，finally 必关）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
// 卡片家族批（2026-11-01 作者 16:05 令）起截图随本 spec 落本批证据目录
// （原接管批目录 evidence/20261101_103300_takeover-entry__* 定格为历史，不复写）。
const SHOTS = join(ROOT, 'evidence', '20261101_cards-family__delegate-nebflow-67f23d42', 'after');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitUntil(fn, ms = 6000, label = 'condition') {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await fn()) return true;
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${label}`);
}
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩 WS（每连接一个；connCount 供 F2「回执 ok ⇒ reload 重连」判据）────────────
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
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root-local-session', name: '本机会话' }], activeId: 'root-local-session', folders: [] }));
}
/** 推给页面当前连接的 takeover 帧（ConsoleTakeover.takeoverFrame 同形）。 */
const pushTakeover = () => currentWs.send(JSON.stringify({ type: 'takeover', at: Date.now() }));
const pushFrame = (obj) => currentWs.send(JSON.stringify(obj));

// ── 接管 HTTP 端点桩（接管必达批：前端消费 POST /api/takeover/retake）──────────
// mode: 'ok' ⇒ {ok:true}；'refused' ⇒ {ok:false,error:'refused'}（慢 400ms 供
// loading 态观察）；'abort' ⇒ 连接失败（fetch TypeError 腿）。
const retakeStub = { mode: 'ok' };

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
await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch();
const INIT = () => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_input_history', '[]');
};
async function openConsole(ctx) {
  await ctx.addInitScript(INIT);
  const page = await ctx.newPage();
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  // 接管端点桩（后注册优先于上方通配；ConsoleTakeoverRoutes 契约同形）。
  await page.route('**/api/takeover/retake', async r => {
    if (retakeStub.mode === 'abort') return r.abort();
    if (retakeStub.mode === 'refused') {
      await sleep(400); // loading 态可观察窗
      return r.fulfill({ json: { ok: false, error: 'refused' } });
    }
    return r.fulfill({ json: { ok: true, kicked: 1 } });
  });
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1000);
  return page;
}

try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await openConsole(ctx);
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  const box = page.locator('#takeover-box');
  const overlay = page.locator('#modal-overlay');
  const activeSid = () => page.evaluate(() => import('/js/state.js').then(m => m.default.activeSessionId));

  // ── F5 正对照（必须先于 takeover）：同款内容帧在未顶出态确实被分发 ────────────
  // 断言 = textDelta 处理器自身的副作用（state.sessionTexts 累积）——比 DOM 文本
  // 更强：入口闸若在，处理器根本不会被调用（appendAiText 在处理器内）。
  const textsOf = () => page.evaluate(() => import('/js/state.js').then(m => m.default.sessionTexts));
  pushFrame({ type: 'textDelta', sessionId: 'root-local-session', delta: 'CONTROL_MARKER' });
  await sleep(400);
  const controlTexts = await textsOf();
  ok('F5 正对照：superseded 前内容帧正常分发渲染', JSON.stringify(controlTexts).includes('CONTROL_MARKER'), JSON.stringify(controlTexts).slice(0, 120));

  // ── F1: takeover 帧弹模态（文案/两按钮/GLOBAL 不抢视图/毛玻璃/g2 药丸按钮语）─
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
    ok('F1 consoleSuperseded 旗标在场', superseded === true);
    ok('F1 毛玻璃：overlay takeover-blur 类在场', await overlay.evaluate(el => el.classList.contains('takeover-blur')));
    const blur = await overlay.evaluate(el => getComputedStyle(el).backdropFilter || getComputedStyle(el).webkitBackdropFilter);
    ok('F1 毛玻璃：backdrop-filter blur(24px) 同源 --cv-blur', /blur\(24px\)/.test(blur || ''), blur || '');
    // 卡片家族批（作者 16:05 令）：面板 = .nfc 实色家族卡（官网 auth 域 DNA 逐值：
    // 白底 #fff / 边 #e5e7eb / radius 18 / 宽 384 / 阴影两档）+ 官方字标双档。
    const card = await box.evaluate(el => {
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, radius: s.borderRadius, width: s.width, shadow: s.boxShadow, border: `${s.borderWidth} ${s.borderColor}` };
    });
    ok('F1 家族卡材质（bg #fff 实色，玻璃退役）', card.bg === 'rgb(255, 255, 255)', card.bg);
    ok('F1 家族卡几何（radius 18 + width 384）', card.radius === '18px' && card.width === '384px', JSON.stringify(card));
    ok('F1 家族卡边（1px #e5e7eb）', card.border === '1px rgb(229, 231, 235)', card.border);
    ok('F1 家族卡影（官方两档外影，无 inset 玻璃高光）',
      card.shadow.includes('rgba(17, 24, 39, 0.06)') && !card.shadow.includes('inset'), card.shadow.slice(0, 120));
    const lockup = page.locator('#takeover-box .nfc-lockup-light');
    ok('F1 家族字标在场（官方 lockup 图，alt=STAND）',
      await lockup.isVisible() && (await lockup.getAttribute('alt')) === 'STAND');
    const titleSt = await page.locator('#takeover-title').evaluate(el => {
      const s = getComputedStyle(el);
      return { size: s.fontSize, weight: s.fontWeight, color: s.color };
    });
    ok('F1 家族标题档（21px/600 #111827）',
      titleSt.size === '21px' && titleSt.weight === '600' && titleSt.color === 'rgb(17, 24, 39)', JSON.stringify(titleSt));
    // 按钮语 = g2 药丸族（接管/入场同族批作者令②）：药丸形态（radius 999）+
    // 非透明填充（次级白玻璃 / 主动作墨玻璃各自 token 底），旧「无底无框 chip」退役。
    for (const [id, expectClass] of [['takeover-exit', 'g2-pill'], ['takeover-retake', 'g2-pill-primary']]) {
      const btn = page.locator('#' + id);
      const st = await btn.evaluate(el => ({
        cls: el.className,
        radius: getComputedStyle(el).borderRadius,
        bg: getComputedStyle(el).backgroundColor,
      }));
      ok(`F1 药丸形态（#${id} radius 999px + 类 ${expectClass}）`,
        st.cls.includes(expectClass) && st.radius === '999px', JSON.stringify(st));
      ok(`F1 药丸有底（#${id} 非透明填充，chip 退役）`,
        st.bg !== 'rgba(0, 0, 0, 0)' && st.bg !== 'transparent', st.bg);
    }
    // 主次异材：墨玻璃药丸底色 ≠ 白玻璃药丸底色（同族按钮语的两档）。
    const [exitBg, retakeBg] = await Promise.all([
      page.locator('#takeover-exit').evaluate(el => getComputedStyle(el).backgroundColor),
      page.locator('#takeover-retake').evaluate(el => getComputedStyle(el).backgroundColor),
    ]);
    ok('F1 主次异材（重新接管墨玻璃 ≠ 退出白玻璃）', exitBg !== retakeBg, `${exitBg} vs ${retakeBg}`);
    await page.screenshot({ path: join(SHOTS, 'takeover-light.png') });
  }

  // ── F5: 内容断喂前端兜底（superseded 态内容帧一律不渲染）───────────────────
  {
    // 被顶出态：新内容帧不得再渲染（服务端 gateFrame 断喂之外的旧引擎/直发帧兜底）。
    pushFrame({ type: 'textDelta', sessionId: 'root-local-session', delta: 'SECRET_MARKER' });
    await sleep(400);
    const leaked = JSON.stringify(await textsOf()).includes('SECRET_MARKER');
    const leakedDom = await page.evaluate(() => document.body.innerText.includes('SECRET_MARKER'));
    ok('F5 superseded 态新内容帧不分发不渲染', !leaked && !leakedDom);
    ok('F5 模态仍在场（内容闸不误收接管面）', await box.isVisible());
  }

  // ── F4: 强制二选一（Escape / 遮罩点击不关闭）────────────────────────────
  await page.keyboard.press('Escape');
  await sleep(250);
  ok('F4 Escape 不关闭接管模态', await box.isVisible());
  await page.mouse.click(30, 450); // 遮罩边缘（盒外）
  await sleep(250);
  ok('F4 遮罩点击不关闭接管模态', await box.isVisible());

  // ── F2: 重新接管（HTTP 腿：loading 态 + 回执 ok ⇒ reload 重新进入）──────────
  {
    const connsBefore = connCount;
    await page.locator('#takeover-retake').click();
    await sleep(150);
    // loading 态是瞬态（桩立即回 ok）；能观察到则断言，观察不到不硬等——
    // 失败腿的 loading 断言在 F2b 稳定钉。
    await waitUntil(() => connCount > connsBefore, 8000, 'retake ok ⇒ reload（新 WS 连接）');
    ok('F2 HTTP 回执 ok ⇒ reload 重新进入（WS 重连）', connCount > connsBefore, `connCount=${connCount} > ${connsBefore}`);
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(600);
    ok('F2 模态收起', !(await box.isVisible()));
    ok('F2 overlay 毛玻璃摘除', !(await overlay.evaluate(el => el.classList.contains('takeover-blur'))));
    const superseded = await page.evaluate(() => import('/js/state.js').then(m => m.default.consoleSuperseded));
    ok('F2 consoleSuperseded 复位', superseded === false);
    await page.screenshot({ path: join(SHOTS, 'retake-success-reentered.png') });
  }

  // ── F2b: 失败腿（回执 !ok / 网络事故）⇒ 分类文案落卡面错误行 + toast + 恢复可点
  //    （接管必达批作者令①：明确报错不许静默；恢复 ok 后再点成功）───────────────
  {
    pushTakeover();
    await sleep(300);
    ok('F2b 前置：模态再弹（幂等）', await box.isVisible());
    // 腿1：回执 !ok（error=refused，慢 400ms 供 loading 态稳定观察）。
    retakeStub.mode = 'refused';
    const retakeBtn = page.locator('#takeover-retake');
    await retakeBtn.click();
    await sleep(150);
    ok('F2b loading：按钮 disabled', await retakeBtn.evaluate(el => el.disabled));
    ok('F2b loading：文案 接管中…', (await retakeBtn.textContent()) === '接管中…');
    await waitUntil(() => retakeBtn.evaluate(el => !el.disabled), 4000, 'F2b refused 回执后恢复可点');
    ok('F2b 失败恢复可点', !(await retakeBtn.evaluate(el => el.disabled)));
    ok('F2b 失败文案复位', (await retakeBtn.textContent()) === '重新接管');
    const errText = await page.locator('#takeover-error').textContent().catch(() => '');
    ok('F2b 分类错误行在场（主机拒绝）', errText === '主机拒绝了本次接管', errText || '(absent)');
    const errVisible = await page.locator('#takeover-error').isVisible();
    ok('F2b 错误行可见（不静默）', errVisible);
    const toast = await page.locator('.nebflow-toast').last().textContent().catch(() => '');
    ok('F2b 失败 toast 在场', (toast || '').includes('主机拒绝'), toast || '');
    ok('F2b 模态仍在场（失败不收面）', await box.isVisible());
    await page.screenshot({ path: join(SHOTS, 'retake-fail-refused.png') });
    // 腿2：网络事故（连接失败）⇒「无法连接主机」——fetch TypeError 分类腿。
    retakeStub.mode = 'abort';
    await retakeBtn.click();
    await waitUntil(() => page.locator('#takeover-error').evaluate(el => !el.hidden && el.textContent.includes('无法连接主机')), 4000, 'F2b abort 腿错误行');
    const errText2 = await page.locator('#takeover-error').textContent();
    ok('F2b 网络事故分类文案（无法连接主机）', (errText2 || '').includes('无法连接主机'), errText2 || '');
    ok('F2b 网络事故后仍恢复可点', !(await retakeBtn.evaluate(el => el.disabled)));
    // 腿3：恢复 ok ⇒ 再点成功（reload）。
    retakeStub.mode = 'ok';
    const connsBefore = connCount;
    await retakeBtn.click();
    await waitUntil(() => connCount > connsBefore, 8000, 'F2b 恢复 ok 后再点 ⇒ reload');
    ok('F2b 恢复后再点成功（reload）', connCount > connsBefore);
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  }

  // ── F1-dark: 暗档毛玻璃 + 极简卡截图 ─────────────────────────────────────
  {
    const darkCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    const dark = await openConsole(darkCtx);
    pushTakeover();
    await sleep(400);
    ok('F1-dark 模态在场', await dark.locator('#takeover-box').isVisible());
    ok('F1-dark 毛玻璃类在场', await dark.locator('#modal-overlay').evaluate(el => el.classList.contains('takeover-blur')));
    // 卡片家族批：暗档面板 = 官网 auth 域 dark 值（#161b22 面 / #2d333b 边 /
    // 官方 --auth-panel-shadow 逐值）+ 暗字标（白墨 lockup）切换。
    const darkCard = await dark.locator('#takeover-box').evaluate(el => {
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, border: `${s.borderWidth} ${s.borderColor}`, shadow: s.boxShadow };
    });
    ok('F1-dark 家族卡暗档（bg #161b22 + 边 #2d333b）',
      darkCard.bg === 'rgb(22, 27, 34)' && darkCard.border === '1px rgb(45, 51, 59)', JSON.stringify(darkCard));
    ok('F1-dark 家族影暗档（官方 panel-shadow 两档）',
      darkCard.shadow.includes('rgba(0, 0, 0, 0.5)') && darkCard.shadow.includes('rgba(0, 0, 0, 0.35)'), darkCard.shadow.slice(0, 120));
    ok('F1-dark 暗字标切换（dark lockup 可见/亮档隐）',
      await dark.locator('#takeover-box .nfc-lockup-dark').isVisible()
      && !(await dark.locator('#takeover-box .nfc-lockup-light').isVisible()));
    await dark.screenshot({ path: join(SHOTS, 'takeover-dark.png') });
    await darkCtx.close();
  }

  // ── F3: 退出 ⇒ 直达官网个人主页（profileUrl + from=client）─────────────────
  {
    const exitCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await exitCtx.addInitScript(() => {
      // brand 契约注入（StaticRoutes.brandScriptTag 同形；profileUrl 指向桩域，
      // 路由侧 fulfill ⇒ 不触网）。brand.js 在模块装载时冻结 __BRAND__，故必须
      // 走 initScript（页后再改 window.__BRAND__ 无效）。
      window.__BRAND__ = {
        productName: 'stand', lowerName: 'stand', domain: 'mystand.space', name: 'stand',
        profileUrl: 'https://profile.test/landing',
      };
    });
    const exitPage = await openConsole(exitCtx);
    await exitPage.route('https://profile.test/**', r =>
      r.fulfill({ status: 200, contentType: 'text/html', body: '<html><body>profile landing</body></html>' }));
    pushTakeover();
    await sleep(400);
    ok('F3 前置：模态在场', await exitPage.locator('#takeover-box').isVisible());
    await exitPage.locator('#takeover-exit').click();
    await exitPage.waitForURL(u => u.href.startsWith('https://profile.test/landing'), { waitUntil: 'commit', timeout: 8000 });
    ok('F3 退出直达官网个人主页（profileUrl）', exitPage.url().startsWith('https://profile.test/landing'), exitPage.url());
    ok('F3 from=client 提示随行（跨仓契约）', exitPage.url().includes('from=client'), exitPage.url());
    await exitCtx.close();
  }

  ok('零页面异常', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} finally {
  await browser.close().catch(() => {});
  server.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
