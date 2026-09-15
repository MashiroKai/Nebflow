#!/usr/bin/env node
// e2e-ctxthresh.cjs — ctxthresh 批（2026-09-15 方案 A）UI 面判据 U1–U6 + 钳制判据。
//
// 设计来源 = 设计件 §8.2「UI 面（改前红 → 改后绿）」逐条表 + 验收 ⑥ 钳制判据。
// 打桩路线**完全复用** scripts/e2e-header-collision.cjs（头部注释逐字）：
//   `page.route` 从磁盘服务真实前端 + `MockWebSocket` 注入真实 `ws.js` 分发路径，
//   **零端口零进程、不碰 8080**、不碰宿主实例。
//
// 服务端帧由本脚本**逐字段镜像** `WebSocketRoutes.compactThresholdInfo`（`type /
// sessionId / ratio / contextWindow / effectiveThreshold / effectiveRatio /
// defaultThreshold / defaultRatio / minRatio / maxRatio`）与
// `compactThresholdError`（`type / sessionId / message`）——镜像点 = 两侧字段名的
// 唯一契约（本脚本头部即契约记录；权威实现见 Scala 侧同名方法）。
//
// 用法：
//   node scripts/e2e-ctxthresh.cjs             # after——当前磁盘树（硬断言，失败 exit 1）
//   node scripts/e2e-ctxthresh.cjs --baseline  # before——js/main.js 以 git show <批基> 应答
//                                              #   且新模块不被引用（面板不存在）
//   --baseline-ref <ref>                       # 批基引用，默认 main（本批基 = main tip）
//   --web-root <dir>                           # 前端根改为**冻结副本**（`git archive` 解开）⇒ 同一断言集
//                                              #   可在「改前树 / 改后树」各跑一遍 = 改前红 / 改后绿
//
// 🔴 判据口径 = 作者终裁（2026-09-15 逐字）「不显示任何文字，只在拉动滑杆的时候，显示%比」
//   ＋显式确认「不显示任何字」⇒ 本断言集**以「零文字 + 滑杆锁死」为绿判据**。
//   本脚本此前以「『已超限』文案在场」为绿判据的断言（旧 U2/U5/U8a/U8a2/U8d2/U8e/U8h/U8c）属
//   **被终裁取代的前提**，按 provisional / 存档处理，不再作为绿判据。
//
// 断言（改前全红 / 改后全绿）：
//   U1 入口在场：click('#header-model-info') → #ctxthresh-panel 可见
//   U2 零常驻读数（终裁 ③）：静止态 abs / pct / 数字框全空（改前 = 256k / 25.6% / 26 ⇒ 红）
//   U3 设值生效：设 r=0.5 → 发帧 → 环 tooltip `threshold 50%` 与阈值线角度随之变（读数唯一权威面）
//   U3b 小滑杆（旧 hover 拖杆形态）：拖动中浮出 % 读数 + 松手提交 + 环随之变（终裁「只在拖动时显示%比」）
//   U4 恢复默认：点「恢复默认」→ 出站帧 ratio=null（面板仍零读数）
//   U5 零常驻文字（终裁 ②）：标题 / 作用域句 / 提示句 / 默认值注记全空（两态各一条）
//   U6 非 Nebula 面零影响：非主会话回显帧不改变环/面板；出站帧只载主会话 id
//   U7 钳制：v ≤ 15% 禁选（拖到最左 ⇒ 出站 ≥16%）；v < 当前用量 ⇒ 钳回（用量 40% ⇒ ≥40%）
//   U8 90% 硬顶边缘态（root 2026-09-15 逐字 + 终裁）：usage ≥ 90% ⇒ 无可选区间 ⇒ **只锁死 / 置灰**
//      + **零文字**（全域无「已超限 / Over limit」串；拦截腿静默）+ 保持默认值（不静默改值）
//      + 三腿零出站（拖动 / 保存 / 复位）+ 出站值永不 > 90%；usage < 90% 侧 `[max(15%, 用量), 90%]` 不回归
//   U9 缺字段回显帧（旧数据）⇒ 降级展示、不崩（面板仍在 + 零文字）
//   U10 键集机械核：locales en/zh 对称、无 `ctxthresh.overLimit` 键、无未消费 `ctxthresh.*` 键


const { chromium } = require('playwright');
const { readFileSync, readdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
// --web-root <dir>：前端根指向**冻结副本**（改前树 / 预演合并树），默认 = 当前磁盘树。
const _wrIdx = process.argv.indexOf('--web-root');
const WEB_ROOT = _wrIdx >= 0 && process.argv[_wrIdx + 1] ? process.argv[_wrIdx + 1] : WEB;
// 「改前」的服务端文件来源 = **批基**（默认 main；--baseline-ref 可覆盖）。不用 HEAD：
// 本批一旦在支上落地，HEAD 就已是「改后」树，baseline 会假绿（实测踩过）。
const _refIdx = process.argv.indexOf('--baseline-ref');
const BASELINE_COMMIT = _refIdx >= 0 && process.argv[_refIdx + 1] ? process.argv[_refIdx + 1] : 'main';
// baseline 模式：main.js 取批基版（无 ctxthresh import）；新模块无引用 ⇒ 面板不存在
const CHANGED = ['js/main.js'];
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'e2e-root';
const NODE_SID = 'node-e2e-sub';
const WINDOW = 1000000;          // 默认 preset 链现取窗口（1M）
const DEFAULT_TOKENS = 256000;   // 现值函数读数（>300k ⇒ 固定 256000）
const DEFAULT_RATIO = DEFAULT_TOKENS / WINDOW;
const NODE_DEFAULT_RATIO = 500000 > 300000 ? DEFAULT_TOKENS / 500000 : 0.8; // 子会话旁证窗口

const baselineBlob = {};
if (MODE === 'before') {
  for (const rel of CHANGED) {
    baselineBlob['/' + rel] = execFileSync(
      'git', ['show', `${BASELINE_COMMIT}:src/main/resources/web/${rel}`],
      { cwd: ROOT, maxBuffer: 30 * 1024 * 1024 });
  }
}

/** 镜像 WebSocketRoutes.compactThresholdInfo 的帧体（字段名 = 两侧唯一契约）。 */
function infoFrame(sessionId, ratio, window = WINDOW) {
  const effective = ratio == null ? (window > 300000 ? DEFAULT_TOKENS : Math.round(window * 0.8))
    : Math.round(window * ratio);
  const dflt = window > 300000 ? DEFAULT_TOKENS : Math.round(window * 0.8);
  return {
    type: 'compactThresholdInfo', sessionId, ratio: ratio == null ? null : ratio,
    contextWindow: window,
    effectiveThreshold: effective,
    effectiveRatio: effective / window,
    defaultThreshold: dflt,
    defaultRatio: dflt / window,
    minRatio: 0.16,
    maxRatio: 0.90,
  };
}

(async () => {
  console.log(`[ctxthresh e2e] mode=${MODE} webRoot=${WEB_ROOT}`);
  const browser = await chromium.launch();
  const failures = [];
  const results = [];
  const check = (id, ok, detail) => {
    results.push({ id, ok, detail });
    if (!ok) failures.push(`${id}: ${detail}`);
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${id}${detail ? ' — ' + detail : ''}`);
  };

  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'shot-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__shotErrors = [];
    window.addEventListener('error', (e) => window.__shotErrors.push(String(e.message)));
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) {
        this.url = u; this.readyState = MockWS.CONNECTING;
        window.__wsMock = this;
        window.__wsSent = [];
      }
      send(data) { try { window.__wsSent.push(JSON.parse(data)); } catch { /* ignore */ } }
      close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    if (MODE === 'before' && baselineBlob[p]) {
      return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: baselineBlob[p] });
    }
    const file = normalize(join(WEB_ROOT, p));
    if (!file.startsWith(normalize(WEB_ROOT))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(300);

  const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  const sent = () => page.evaluate(() => (window.__wsSent || []).slice());
  const clearSent = () => page.evaluate(() => { window.__wsSent.length = 0; });
  const panelVisible = () => page.evaluate(() => {
    const el = document.getElementById('ctxthresh-panel');
    if (!el) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.opacity !== '0' && el.getBoundingClientRect().width > 1;
  });
  const panelText = (id) => page.evaluate((i) => (document.getElementById(i)?.textContent || '').trim(), id);
  // 🔴 终裁口径读数面（机械）：常驻文字 4 件 + 常驻读数 3 件 + 面板/全域文案扫描
  const resident = () => page.evaluate(() => {
    const g = (id) => document.getElementById(id);
    const tx = (id) => (g(id)?.textContent || '').trim();
    const num = g('ctxthresh-number');
    return {
      title: (g('ctxthresh-panel')?.querySelector('.ctxthresh-title')?.textContent || '').trim(),
      scope: tx('ctxthresh-scope'), hint: tx('ctxthresh-hint'), note: tx('ctxthresh-default-note'),
      abs: tx('ctxthresh-abs'), pct: tx('ctxthresh-pct'), num: num ? num.value : null,
      panel: (g('ctxthresh-panel')?.innerText || '').replace(/\s+/g, ' ').trim(),
      bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').trim(),
    };
  });
  const zeroText = (r) => r.title === '' && r.scope === '' && r.hint === '' && r.note === '';
  const zeroReadout = (r) => r.abs === '' && r.pct === '' && r.num === '';
  const ringTooltip = () => page.evaluate(() =>
    document.querySelector('#header-model-info .ctx-ring-wrap')?.getAttribute('title') || '');
  const ringThresholdAngle = () => page.evaluate(() =>
    document.querySelector('#header-model-info .ctx-ring-threshold')?.getAttribute('transform') || '');

  // ── boot ──
  await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(300);
  await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 12000, contextWindow: WINDOW, model: 'qa-model' });
  await page.waitForTimeout(250);

  // ── U1 入口在场：点环 → 面板可见 ──
  const ringPresent = await page.evaluate(() => !!document.querySelector('#header-model-info .ctx-ring-wrap'));
  check('U1a 环在场（入口前置）', ringPresent, 'ring=' + ringPresent);
  if (ringPresent) {
    await page.click('#header-model-info');
    await page.waitForTimeout(250);
  }
  const u1 = await panelVisible();
  check('U1 入口在场：click(#header-model-info) → #ctxthresh-panel 可见', u1);
  // 面板打开时模块会发 getCompactThreshold ⇒ 用镜像帧应答（模拟服务端）
  const opened = await sent();
  const got = opened.filter((m) => m.type === 'getCompactThreshold');
  if (got.length) await df(infoFrame(ROOT_SID, null));
  await page.waitForTimeout(200);

  // ── U2..U7：面板存在才可执行（改前 = 面板不存在 ⇒ 全红，基线模式只报告不执行）──
  const PANEL_STEPS = [
    'U2 零常驻读数（终裁 ③）：静止态 abs / pct / 数字框全空',
    'U5 零常驻文字（终裁 ②）：标题 / 作用域句 / 提示句 / 默认值注记全空（usage<90% 态）',
    'U5b 全域零「已超限 / Over limit」串（zh 侧）',
    'U3a 发帧：setCompactThreshold{ratio=0.5}',
    'U3 设值生效：环 tooltip `threshold 50%` 与阈值线角度随之变',
    'U3c 静止态零读数（终裁 ③）：生效值只由环 tooltip 承载，面板不留百分比',
    'U3b 小滑杆（旧 hover 拖杆形态）：拖动实时回显 + 松手提交 + 环随之变',
    'U3b2 拖动时显示 %比（终裁 ③ 第三态）：thumb 标签可见 + pct 实时读数在场',
    'U3b3 松手后读数归隐（终裁 ③：零常驻）',
    'U6a 非主会话帧不入 Nebula 窗口：环/面板值不变',
    'U7a 钳制：15% 为开区间下限（拖到最左 ⇒ 出站 > 15%）',
    'U7b 钳制：v < 当前用量 ⇒ 钳回 ≥ 当前用量（用量 40%，设 5% ⇒ ≥40%）',
    'U4a 恢复默认：出站帧 ratio=null',
    'U4 恢复默认：环 tooltip 回 `threshold 26%`（面板仍零读数 —— 读数唯一权威面 = 环）',
    'U6b 出站面作用域：setCompactThreshold 只载主会话 id',
    '浮层硬约束：#ctxthresh-panel 是 #header 的兄弟节点（同在 #main 内）',
    '零新增 header 叶子控件',
  ];
  if (!u1) {
    // 改前（baseline）：面板不存在 ⇒ 其余判据逐条记红（这正是「改前红」的读数）
    for (const id of PANEL_STEPS) check(id, false, 'panel missing (#ctxthresh-panel 不存在)');
  } else {
  // ── U2 零常驻读数（终裁 ③）：静止态 token 数 / 比例 / 数字框一律空 ──
  const rIdle = await resident();
  const abs = rIdle.abs, pct = rIdle.pct;
  check('U2 零常驻读数（终裁 ③）：静止态 abs / pct / 数字框全空',
    zeroReadout(rIdle), `abs="${abs}" pct="${pct}" num="${rIdle.num}"`);

  // ── U5 零常驻文字（终裁 ②）：标题 / 作用域句 / 提示句 / 默认值注记全空 + 全域无超限串 ──
  check('U5 零常驻文字（终裁 ②）：标题 / 作用域句 / 提示句 / 默认值注记全空（usage<90% 态）',
    zeroText(rIdle), JSON.stringify({ title: rIdle.title, scope: rIdle.scope, hint: rIdle.hint, note: rIdle.note }));
  check('U5b 全域零「已超限 / Over limit」串（zh 侧）',
    !/已超限|over limit/i.test(rIdle.bodyText), `body="${rIdle.bodyText.slice(0, 100)}"`);

  // ── U3 设值生效：数字框 50 + 保存 ⇒ 出站帧 ratio=0.5 ⇒ 环随之变 ──
  await page.fill('#ctxthresh-number', '50');
  await page.click('#ctxthresh-save-btn');
  await page.waitForTimeout(200);
  const setFrames = (await sent()).filter((m) => m.type === 'setCompactThreshold');
  const u3frame = setFrames[setFrames.length - 1];
  check('U3a 发帧：setCompactThreshold{ratio=0.5}', !!u3frame && u3frame.ratio === 0.5 && u3frame.sessionId === ROOT_SID,
    `frame=${JSON.stringify(u3frame)}`);
  await df(infoFrame(ROOT_SID, u3frame ? u3frame.ratio : 0.5));
  await page.waitForTimeout(200);
  const tip = await ringTooltip();
  const angle = await ringThresholdAngle();
  check('U3 设值生效：环 tooltip `threshold 50%` 与阈值线角度随之变',
    tip.includes('threshold 50%') && angle === 'rotate(180 18 18)', `tooltip="${tip}" angle="${angle}"`);
  const pctAfter = await panelText('ctxthresh-pct');
  check('U3c 静止态零读数（终裁 ③）：生效值只由环 tooltip 承载，面板不留百分比',
    pctAfter === '', `pct="${pctAfter}"`);

  // ── U3b 小滑杆（旧 hover 拖杆形态）：拖动 ⇒ 发帧 ⇒ 环随之变 ──
  const trackBox = await page.evaluate(() => {
    const t = document.getElementById('ctxthresh-track');
    if (!t) return null;
    const r = t.getBoundingClientRect();
    return { x: r.left, y: r.top + r.height / 2, w: r.width };
  });
  let u3b = false, u3bDetail = 'track missing', dragDuring = null, dragAfter = null;
  if (trackBox && trackBox.w > 10) {
    await clearSent();
    // 旧形态：mousedown 起拖 → mousemove → mouseup 提交（dragRatio 期间实时回显）
    const fx = (r) => trackBox.x + ((r - 0.16) / (0.90 - 0.16)) * trackBox.w;
    await page.mouse.move(fx(0.30), trackBox.y);
    await page.mouse.down();
    await page.mouse.move(fx(0.55), trackBox.y, { steps: 6 });
    const dragLabel = await page.evaluate(() => {
      const l = document.getElementById('ctxthresh-thumb-label');
      return l ? { text: l.textContent, visible: l.classList.contains('visible') } : null;
    });
    const dragging = await page.evaluate(() => document.getElementById('ctxthresh-track')?.classList.contains('dragging'));
    dragDuring = await resident();          // 🔴 终裁 ③ 第三态：拖动中读数在场
    await page.mouse.up();
    await page.waitForTimeout(200);
    dragAfter = await resident();           // 🔴 终裁 ③：松手后读数归隐（零常驻）
    const dragFrames = (await sent()).filter((m) => m.type === 'setCompactThreshold');
    const dfr = dragFrames[dragFrames.length - 1];
    if (dfr) await df(infoFrame(ROOT_SID, dfr.ratio));
    await page.waitForTimeout(150);
    const tip2 = await ringTooltip();
    const expected = Math.round((dfr ? dfr.ratio : 0) * 100);
    u3b = !!dfr && dfr.ratio >= 0.45 && dfr.ratio <= 0.62 && dragging === true &&
      dragLabel && dragLabel.visible === true && tip2.includes(`threshold ${expected}%`);
    u3bDetail = `dragRatio=${dfr && dfr.ratio} draggingClass=${dragging} label=${JSON.stringify(dragLabel)} tip="${tip2}"`;
  }
  check('U3b 小滑杆（旧 hover 拖杆形态）：拖动实时回显 + 松手提交 + 环随之变', u3b, u3bDetail);
  check('U3b2 拖动时显示 %比（终裁 ③ 第三态）：thumb 标签可见 + pct 实时读数在场',
    !!dragDuring && dragDuring.pct !== '' && dragDuring.hint === '' && dragDuring.scope === '',
    dragDuring ? `pct="${dragDuring.pct}" abs="${dragDuring.abs}" hint="${dragDuring.hint}" scope="${dragDuring.scope}"` : 'no reading');
  check('U3b3 松手后读数归隐（终裁 ③：零常驻）',
    !!dragAfter && zeroReadout(dragAfter) && zeroText(dragAfter),
    dragAfter ? JSON.stringify({ abs: dragAfter.abs, pct: dragAfter.pct, num: dragAfter.num, hint: dragAfter.hint, scope: dragAfter.scope, note: dragAfter.note, title: dragAfter.title }) : 'no reading');

  // ── U6 非 Nebula 面零影响 ──
  // (a) 非主会话（node-*）回显帧不得改变环/面板（作用域第一道闸）
  const beforeTip = await ringTooltip();
  const beforePct = await panelText('ctxthresh-pct');
  await df(infoFrame(NODE_SID, 0.8, 500000));
  await df({ type: 'usageUpdate', sessionId: NODE_SID, inputTokens: 400000, contextWindow: 500000, compactThreshold: NODE_DEFAULT_RATIO });
  await page.waitForTimeout(200);
  const afterTip = await ringTooltip();
  const afterPct = await panelText('ctxthresh-pct');
  check('U6a 非主会话帧不入 Nebula 窗口：环/面板值不变',
    afterTip === beforeTip && afterPct === beforePct, `before="${beforeTip}"/"${beforePct}" after="${afterTip}"/"${afterPct}"`);

  // ── U7 钳制：拖到最左 ⇒ ≥16%；用量 40% 时设 5% ⇒ 钳回 ≥40% ──
  let u7a = false, u7aDetail = 'track missing';
  if (trackBox && trackBox.w > 10) {
    await clearSent();
    await page.mouse.move(trackBox.x + 2, trackBox.y);
    await page.mouse.down();
    await page.mouse.move(trackBox.x - 40, trackBox.y, { steps: 4 });
    await page.mouse.up();
    await page.waitForTimeout(200);
    const f = (await sent()).filter((m) => m.type === 'setCompactThreshold').pop();
    u7a = !!f && f.ratio > 0.15 && f.ratio >= 0.16;
    u7aDetail = `ratio=${f && f.ratio} (要求 > 15%)`;
  }
  check('U7a 钳制：15% 为开区间下限（拖到最左 ⇒ 出站 > 15%）', u7a, u7aDetail);

  // 用量 40% ⇒ 动态下限抬到 40%：设 5% 必须钳回 ≥ 40%
  await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 400000, contextWindow: WINDOW, model: 'qa-model' });
  await page.waitForTimeout(200);
  await clearSent();
  await page.fill('#ctxthresh-number', '5');
  await page.click('#ctxthresh-save-btn');
  await page.waitForTimeout(200);
  const f7 = (await sent()).filter((m) => m.type === 'setCompactThreshold').pop();
  check('U7b 钳制：v < 当前用量 ⇒ 钳回 ≥ 当前用量（用量 40%，设 5% ⇒ ≥40%）',
    !!f7 && f7.ratio >= 0.40 && f7.ratio <= 0.90, `ratio=${f7 && f7.ratio}`);

  // ── U4 恢复默认 ──
  await clearSent();
  await page.click('#ctxthresh-reset-btn');
  await page.waitForTimeout(200);
  const f4 = (await sent()).filter((m) => m.type === 'setCompactThreshold').pop();
  check('U4a 恢复默认：出站帧 ratio=null', !!f4 && f4.ratio === null && f4.sessionId === ROOT_SID, `frame=${JSON.stringify(f4)}`);
  await df(infoFrame(ROOT_SID, null));
  await page.waitForTimeout(200);
  const r4 = await resident();
  const abs4 = r4.abs, pct4 = r4.pct;
  const tip4 = await ringTooltip();
  check('U4 恢复默认：环 tooltip 回 `threshold 26%`（面板仍零读数 —— 读数唯一权威面 = 环）',
    abs4 === '' && pct4 === '' && tip4.includes('threshold 26%'), `abs="${abs4}" pct="${pct4}" tip="${tip4}"`);

  // ── U6b 出站帧只载主会话 id（非 root 会话永不被写）──
  const allSets = (await sent()).concat(await page.evaluate(() => window.__wsSent || []))
    .filter((m) => m.type === 'setCompactThreshold');
  const sids = new Set(allSets.map((m) => m.sessionId));
  check('U6b 出站面作用域：setCompactThreshold 只载主会话 id',
    sids.size <= 1 && (sids.size === 0 || sids.has(ROOT_SID)), `sids=${JSON.stringify([...sids])}`);

  // ── 面板浮层硬约束：必须是 #header 的兄弟节点（base.css:243-256）──
  const sib = await page.evaluate(() => {
    const p = document.getElementById('ctxthresh-panel');
    if (!p) return null;
    return { parentIsMain: p.parentElement?.id === 'main', prevId: p.previousElementSibling?.id || null };
  });
  check('浮层硬约束：#ctxthresh-panel 是 #header 的兄弟节点（同在 #main 内）',
    !!sib && sib.parentIsMain === true && sib.prevId === 'header', JSON.stringify(sib));

  // ── 零新增 header 叶子控件（CHAIN 不变 ⇒ 无碰撞新面）──
  const headerLeaves = await page.evaluate(() => {
    const known = ['sidebar-toggle', 'header-model-info', 'session-name', 'memory-btn', 'search-btn',
      'bypass-dropdown', 'bypass-toggle', 'voice-toggle-btn', 'reminder-btn', 'daemon-btn', 'bg-indicator',
      'pending-asks-indicator', 'bgagent-indicator', 'canvas-toggle-btn'];
    const ids = [...document.querySelectorAll('#header *[id]')].map((e) => e.id);
    return ids.filter((i) => !known.includes(i));
  });
  check('零新增 header 叶子控件', headerLeaves.length === 0, `unknown ids=${JSON.stringify(headerLeaves)}`);

  // ── U8 90% 硬顶边缘态（usage ≥ 90% ⇒ 无可选区间；root 2026-09-15 逐字）──
  const setUsage = async (tokens) => {
    await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: tokens, contextWindow: WINDOW, model: 'qa-model' });
    await page.waitForTimeout(120);
  };
  const reopenPanel = async () => {
    await page.click('#header-model-info');
    await page.click('#header-model-info');
    await page.waitForTimeout(150);
    if ((await sent()).some((m) => m.type === 'getCompactThreshold')) await df(infoFrame(ROOT_SID, null));
    await page.waitForTimeout(150);
  };
  const uiState = () => page.evaluate(() => {
    const tr = document.getElementById('ctxthresh-track');
    const th = document.getElementById('ctxthresh-thumb');
    const num = document.getElementById('ctxthresh-number');
    return {
      trackPE: tr ? getComputedStyle(tr).pointerEvents : null,
      thumbPE: th ? getComputedStyle(th).pointerEvents : null,
      trackAria: tr ? tr.getAttribute('aria-disabled') : null,
      thumbAria: th ? th.getAttribute('aria-disabled') : null,
      numDisabled: num ? num.disabled : null,
      numMin: num ? num.min : null,
      numMax: num ? num.max : null,
      saveDisabled: document.getElementById('ctxthresh-save-btn')?.disabled ?? null,
      resetDisabled: document.getElementById('ctxthresh-reset-btn')?.disabled ?? null,
    };
  });

  // (1) usage = 95%（> 90%）⇒ 锁定档（终裁：只锁死/置灰 + 零文字）
  await setUsage(950000);
  await reopenPanel();
  const r95 = await resident();
  const pctOverBefore = r95.pct;
  check('U8a 超限态零文字（终裁 ①）：usage 95% ⇒ 提示句/标题/作用域句/默认值注记全空',
    zeroText(r95), JSON.stringify({ title: r95.title, scope: r95.scope, hint: r95.hint, note: r95.note }));
  check('U8a2 超限档全域零文字（无「已超限 / Over limit」串、面板无区间句）',
    !/已超限|over limit/i.test(r95.bodyText) && !/\d+\s*%\s*[–-]\s*90\s*%/.test(r95.panel),
    `body="${r95.bodyText.slice(0, 100)}" panel="${r95.panel}"`);
  const s95 = await uiState();
  check('U8b 滑杆真禁用：track/thumb pointer-events:none + aria-disabled + 数字框/保存/复位 disabled',
    s95.trackPE === 'none' && s95.thumbPE === 'none' && s95.trackAria === 'true' && s95.thumbAria === 'true' &&
    s95.numDisabled === true && s95.saveDisabled === true && s95.resetDisabled === true, JSON.stringify(s95));

  // 三腿（拖动 / 保存 / 复位）在超限档必须零出站；复位腿与保存腿用程序化事件绕过 disabled 属性，
  // 以验证「handler 闸」本身（不只是 attribute）。
  await clearSent();
  await page.mouse.move(trackBox.x + 20, trackBox.y);
  await page.mouse.down();
  await page.mouse.move(trackBox.x + trackBox.w - 4, trackBox.y, { steps: 4 });
  await page.mouse.up();
  const gateEv = await page.evaluate(() => {
    const tr = document.getElementById('ctxthresh-track');
    tr.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: tr.getBoundingClientRect().left + 200 }));
    const dragging = tr.classList.contains('dragging');
    const num = document.getElementById('ctxthresh-number');
    num.disabled = false;
    num.value = '95';
    num.dispatchEvent(new Event('change', { bubbles: true }));
    num.value = '50';   // 反例腿：低于当前用量（95%）的值 —— 旧钳制链会把它抬成 95%（出站 > 90%）
    num.dispatchEvent(new Event('change', { bubbles: true }));
    const save = document.getElementById('ctxthresh-save-btn');
    save.disabled = false;
    save.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const reset = document.getElementById('ctxthresh-reset-btn');
    reset.disabled = false;
    reset.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return {
      dragging,
      msg: (document.getElementById('ctxthresh-msg')?.textContent || '').trim(),
      hint: (document.getElementById('ctxthresh-hint')?.textContent || '').trim(),
      panel: (document.getElementById('ctxthresh-panel')?.innerText || '').replace(/\s+/g, ' ').trim(),
    };
  });
  await page.waitForTimeout(250);
  const outFrames = (await sent()).filter((m) => m.type === 'setCompactThreshold');
  check('U8d 超限档三腿零出站：拖动/保存/复位 ⇒ 0 帧 setCompactThreshold',
    outFrames.length === 0 && gateEv.dragging === false, `frames=${JSON.stringify(outFrames)} dragging=${gateEv.dragging}`);
  check('U8d2 拦截腿静默（终裁 ①）：超限档 msg 位零文字 + 提示句位仍空',
    gateEv.msg === '' && gateEv.hint === '' && !/已超限|over limit/i.test(gateEv.panel),
    `msg="${gateEv.msg}" hint="${gateEv.hint}" panel="${gateEv.panel}"`);
  const r95b = await resident();
  const pctOverAfter = r95b.pct;
  check('U8c 保持默认值（不静默改值）+ 静止态零读数：pct 前后皆空',
    pctOverAfter === pctOverBefore && pctOverAfter === '', `before="${pctOverBefore}" after="${pctOverAfter}"`);

  // (2) usage = 90%（边界闭端 ≥）⇒ 同为锁定档
  await setUsage(900000);
  await reopenPanel();
  const r90 = await resident();
  const s90 = await uiState();
  check('U8e 边界：usage = 90% ⇒ 同为锁定档（`>=` 闭端）+ 零文字',
    s90.trackPE === 'none' && s90.numDisabled === true && zeroText(r90),
    `trackPE=${s90.trackPE} numDisabled=${s90.numDisabled} text=${JSON.stringify({ title: r90.title, scope: r90.scope, hint: r90.hint, note: r90.note })}`);

  // (3) usage = 89%（< 90%）⇒ 区间 [max(15%, 用量), 90%] 不回归（第三例）
  // 🔴 区间判据改由 `num.min/num.max + trackPE` 承担：终裁后提示句不再渲染 ⇒ 无「区间句」可读
  //    （原判据 `hint89.includes('89.0%')` 与终裁 ② 机械互斥，属被取代的前提）。
  await setUsage(890000);
  await reopenPanel();
  const s89 = await uiState();
  const r89 = await resident();
  check('U8f < 90% 侧区间不回归：usage 89% ⇒ 区间 [89%, 90%] 且滑杆未禁用（判据 = num.min/max）+ 零文字零读数',
    s89.numMin === '89' && s89.numMax === '90' && s89.numDisabled === false && s89.trackPE !== 'none' &&
    zeroText(r89) && zeroReadout(r89),
    `numMin=${s89.numMin} numMax=${s89.numMax} numDisabled=${s89.numDisabled} trackPE=${s89.trackPE} text=${JSON.stringify({ hint: r89.hint, scope: r89.scope, note: r89.note, title: r89.title })} readout=${JSON.stringify({ abs: r89.abs, pct: r89.pct, num: r89.num })}`);

  // (4) 全局：本会话全部出站帧 ratio ≤ 90%（含 usage > 90% 场景）
  const overFrames = (await page.evaluate(() => window.__wsSent || []))
    .filter((m) => m.type === 'setCompactThreshold' && typeof m.ratio === 'number' && m.ratio > 0.90);
  check('U8g 出站值永不 > 90%：全帧 ratio ≤ 0.90', overFrames.length === 0, `over=${JSON.stringify(overFrames)}`);

  // (5) 英文侧（同一渲染面、en 词典）：同为**零文字**
  await page.evaluate(async () => { (await import('/js/i18n.js')).setLocale('en'); });
  await setUsage(950000);
  await df(infoFrame(ROOT_SID, null));
  await page.waitForTimeout(200);
  const rEn = await resident();
  check('U8h en 侧同口径：超限态零文字（无 `Over limit` 串）',
    zeroText(rEn) && !/已超限|over limit/i.test(rEn.bodyText),
    JSON.stringify({ title: rEn.title, scope: rEn.scope, hint: rEn.hint, note: rEn.note, body: rEn.bodyText.slice(0, 80) }));

  // ── U9 缺字段回显帧（旧数据）⇒ 降级展示、不崩（面板在 + 零文字）──
  await df({ type: 'compactThresholdInfo', sessionId: ROOT_SID });
  await page.waitForTimeout(200);
  const degraded = await resident();
  const degradedPanel = await page.evaluate(() => !!document.getElementById('ctxthresh-panel'));
  check('U9 缺字段回显帧 ⇒ 降级展示不崩（面板仍在 + 零文字）',
    degradedPanel === true && degraded.hint === '' && degraded.abs === '' && degraded.pct === '',
    JSON.stringify({ panel: degradedPanel, abs: degraded.abs, pct: degraded.pct, hint: degraded.hint }));
  } // end if (u1) —— 面板判据块

  // ── U10 键集机械核（终裁 (b)）：en/zh 对称 + 无 `ctxthresh.overLimit` + 无未消费 `ctxthresh.*` ──
  {
    const allKeys = (rel) => {
      const txt = readFileSync(join(WEB_ROOT, rel), 'utf8');
      return [...txt.matchAll(/^\s*'([A-Za-z0-9_.]+)'\s*:/gm)].map((m) => m[1]);
    };
    const enAll = allKeys('js/locales/en.js'), zhAll = allKeys('js/locales/zh-CN.js');
    const onlyEn = enAll.filter((k) => !zhAll.includes(k)), onlyZh = zhAll.filter((k) => !enAll.includes(k));
    const ctxKeys = enAll.filter((k) => k.startsWith('ctxthresh.'));
    // 消费点扫描：前端 js 面（排除 locales 自身）里 `t('ctxthresh.X'` 的出现集合
    const consumed = new Set();
    const walk = (dir) => {
      for (const ent of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, ent.name);
        if (ent.isDirectory()) { if (ent.name !== 'locales') walk(p); continue; }
        if (!ent.name.endsWith('.js')) continue;
        for (const m of readFileSync(p, 'utf8').matchAll(/t\(\s*'ctxthresh\.([A-Za-z0-9_]+)'/g)) consumed.add('ctxthresh.' + m[1]);
      }
    };
    walk(join(WEB_ROOT, 'js'));
    const unconsumed = ctxKeys.filter((k) => !consumed.has(k));
    check('U10 键集机械核：en/zh 对称 + 无 overLimit 键 + 无未消费 ctxthresh.* 键',
      onlyEn.length === 0 && onlyZh.length === 0 && !ctxKeys.includes('ctxthresh.overLimit') &&
      !zhAll.includes('ctxthresh.overLimit') && unconsumed.length === 0,
      `en=${enAll.length} zh=${zhAll.length} only-en=${JSON.stringify(onlyEn)} only-zh=${JSON.stringify(onlyZh)} ctxthresh=${JSON.stringify(ctxKeys)} unconsumed=${JSON.stringify(unconsumed)}`);
  }

  const pageErrors = await page.evaluate(() => window.__shotErrors || []);
  check('页面零 JS 错误', pageErrors.length === 0, JSON.stringify(pageErrors));

  await browser.close();

  const pass = results.filter((r) => r.ok).length;
  console.log(`\n[${MODE}] ctxthresh UI 判据: ${pass}/${results.length} pass, ${failures.length} fail`);
  if (MODE === 'before') {
    // 改前预期：入口/面板全红（面板不存在）
    console.log(`[before] 预期红：${results.filter((r) => !r.ok).length} 项红（面板不存在 ⇒ U1–U7 红）`);
    process.exit(0);
  }
  if (failures.length) {
    console.log('FAILURES:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  console.log('e2e-ctxthresh OK (exit 0)');
})();
