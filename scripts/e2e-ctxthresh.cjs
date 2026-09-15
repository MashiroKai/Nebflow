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
//
// 断言（改前全红 / 改后全绿）：
//   U1 入口在场：click('#header-model-info') → #ctxthresh-panel 可见
//   U2 值回显：面板显示真实生效值 256k (25.6%)（1M 窗默认，= 现值函数）
//   U3 设值生效：设 r=0.5 → 发帧 → 环 tooltip `threshold 50%` 与阈值线角度随之变
//   U3b 小滑杆（旧 hover 拖杆形态）：拖动滑杆 → 发帧 → 环随之变
//   U4 恢复默认：点「恢复默认」→ 值回 256k (25.6%)
//   U5 作用域文案在场：「仅本 Nebula 窗口（其他 agent / 节点不受影响）」
//   U6 非 Nebula 面零影响：非主会话回显帧不改变环/面板；出站帧只载主会话 id
//   U7 钳制：v ≤ 15% 禁选（拖到最左 ⇒ 出站 ≥16%）；v < 当前用量 ⇒ 钳回（用量 40% ⇒ ≥40%）

const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
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
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
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
    'U2 值回显：面板显示真实生效值 256k (25.6%)',
    'U5 作用域文案在场',
    'U3a 发帧：setCompactThreshold{ratio=0.5}',
    'U3 设值生效：环 tooltip `threshold 50%` 与阈值线角度随之变',
    'U3c 面板同步：生效比例回显 50.0%',
    'U3b 小滑杆（旧 hover 拖杆形态）：拖动实时回显 + 松手提交 + 环随之变',
    'U6a 非主会话帧不入 Nebula 窗口：环/面板值不变',
    'U7a 钳制：15% 为开区间下限（拖到最左 ⇒ 出站 > 15%）',
    'U7b 钳制：v < 当前用量 ⇒ 钳回 ≥ 当前用量（用量 40%，设 5% ⇒ ≥40%）',
    'U4a 恢复默认：出站帧 ratio=null',
    'U4 恢复默认：值回 256k (25.6%)（环 tooltip 同步回 26%）',
    'U6b 出站面作用域：setCompactThreshold 只载主会话 id',
    '浮层硬约束：#ctxthresh-panel 是 #header 的兄弟节点（同在 #main 内）',
    '零新增 header 叶子控件',
  ];
  if (!u1) {
    // 改前（baseline）：面板不存在 ⇒ 其余判据逐条记红（这正是「改前红」的读数）
    for (const id of PANEL_STEPS) check(id, false, 'panel missing (#ctxthresh-panel 不存在)');
  } else {
  // ── U2 值回显：真实生效值 256k (25.6%) ──
  const abs = await panelText('ctxthresh-abs');
  const pct = await panelText('ctxthresh-pct');
  check('U2 值回显：面板显示真实生效值 256k (25.6%)',
    abs === '256k' && pct === '25.6%', `abs="${abs}" pct="${pct}"`);

  // ── U5 作用域文案在场 ──
  const scope = await panelText('ctxthresh-scope');
  check('U5 作用域文案在场', scope.includes('仅本 Nebula 窗口') && scope.includes('其他 agent / 节点不受影响'), `scope="${scope}"`);

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
  check('U3c 面板同步：生效比例回显 50.0%', pctAfter === '50.0%', `pct="${pctAfter}"`);

  // ── U3b 小滑杆（旧 hover 拖杆形态）：拖动 ⇒ 发帧 ⇒ 环随之变 ──
  const trackBox = await page.evaluate(() => {
    const t = document.getElementById('ctxthresh-track');
    if (!t) return null;
    const r = t.getBoundingClientRect();
    return { x: r.left, y: r.top + r.height / 2, w: r.width };
  });
  let u3b = false, u3bDetail = 'track missing';
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
    await page.mouse.up();
    await page.waitForTimeout(200);
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
  const abs4 = await panelText('ctxthresh-abs');
  const pct4 = await panelText('ctxthresh-pct');
  const tip4 = await ringTooltip();
  check('U4 恢复默认：值回 256k (25.6%)（环 tooltip 同步回 26%）',
    abs4 === '256k' && pct4 === '25.6%' && tip4.includes('threshold 26%'), `abs="${abs4}" pct="${pct4}" tip="${tip4}"`);

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
  } // end if (u1) —— 面板判据块

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
