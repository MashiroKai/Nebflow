#!/usr/bin/env node
// e2e-ctxthresh.cjs — ctxring 批（2026-09-17 恢复「环上拖动」，作者裁定 P1/P2/P3）UI 面判据。
//
// 形态（设计卡 `~/.nebflow/docs/Nebflow/20260916_170435_ctxring-restore-design__chain-n-b9a95e1a.md`）：
//   入口 = **环本身，唯一**（小面板整路径已删）；`pointerdown` 起手即按指针角度取值
//   （无位移闸）→ 拖动中只改把手 `transform = rotate(<pct×3.6> 18 18)` →
//   `pointerup` 一次性提交 WS `setCompactThreshold`。
//
// 打桩路线**完全复用** scripts/e2e-header-collision.cjs（头部注释逐字）：
//   `page.route` 从磁盘服务真实前端 + `MockWebSocket` 注入真实 `ws.js` 分发路径，
//   **零端口零进程、不碰 8080**、不碰宿主实例。
//
// 服务端帧由本脚本**逐字段镜像** `WebSocketRoutes.compactThresholdInfo`（`type /
// sessionId / ratio / contextWindow / effectiveThreshold / effectiveRatio /
// defaultThreshold / defaultRatio / minRatio / maxRatio`）与
// `compactThresholdError`（`type / sessionId / message`）——镜像点 = 两侧字段名的
// 唯一契约（权威实现见 Scala 侧同名方法）。
//
// 用法：
//   node scripts/e2e-ctxthresh.cjs             # after——当前磁盘树（硬断言，失败 exit 1）
//   node scripts/e2e-ctxthresh.cjs --baseline  # before——本批**改前**前端（main.js /
//                                              #   ctxthresh.js / chat.css / locales 全取
//                                              #   --baseline-ref 版本）⇒ 环拖动腿不存在 ⇒ 红
//   --baseline-ref <ref>                       # 改前引用，默认 main
//   --web-root <dir>                           # 前端根改为**冻结副本**（`git archive` 解开）
//
// 🔴 判据口径 = 作者终裁（2026-09-15 逐字）「不显示任何文字，只在拉动滑杆的时候，
//   显示%比」＋显式确认「**不显示任何字**」⇒ 全域零文字；锁定档「静默锁死」即终形。
//
// 断言（改前全红 / 改后全绿）：
//   U1  入口唯一：`#ctxthresh-panel` 不存在 + 面板标识全域零命中；环可交互
//   U2  零常驻文字：全域无「已超限 / Over limit」串、无面板节点
//   U3  拖动提交：恰一条 `setCompactThreshold{ratio=0.62}`；权威帧回显后环 tooltip/角度随之变
//   U3b 拖动中（跟手三层）：把手 `rotate(223.2 18 18)` + `<line>` 端点恒 18/1.5/18/5
//      + 环心读数 = 阈值 % + 角度导引线为 SVG 最后子节点 + 拖动期 `usageUpdate` 抢不回（S1）
//   U3c 松手后（回显后）读数交还用量 %
//   U4  恢复默认两腿：环聚焦后 Delete ⇒ `ratio:null`；双击环 ⇒ 末帧 `ratio:null`
//   U5  键盘：←/→ ±1% · Home = 动态下限 · End = 90%
//   U6  作用域：非主会话回显帧不入环；出站帧只载主会话 id
//   U7  钳制：pct 0 ⇒ ≥16%（开区间下限）；用量 40% 拖到 20% ⇒ 钳回 40%
//   U8  90% 硬顶（root 2026-09-15 逐字）：用量 ≥90% ⇒ 三腿（拖动/键盘/双击）零出站 + 零文案
//   U9  缺字段回显帧（旧数据）⇒ 降级不崩
//   U10 键集机械核：en/zh 对称 + 无未消费 `ctxthresh.*` 键
//   U11 a11y：`role="slider"` + `tabindex` + `aria-valuemin/max/now/valuetext`
//   U12 命中面：环本体 28×28 + 伪元素命中面 32×44（`::before inset:-8px -2px`）
//   U13 取消腿：`pointercancel` / `Escape` ⇒ 零出站 + 回弹权威值
//
// 产出：`--json <path>` 可落机读读数（供 impl/verify 引用）。

const { chromium } = require('playwright');
const { readFileSync, readdirSync, writeFileSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
const _wrIdx = process.argv.indexOf('--web-root');
const WEB_ROOT = _wrIdx >= 0 && process.argv[_wrIdx + 1] ? process.argv[_wrIdx + 1] : WEB;
// 「改前」的服务端文件来源 = **批基**（默认 main）。不用 HEAD：本批一旦在支上落地，
// HEAD 就已是「改后」树，baseline 会假绿（实测踩过）。
const _refIdx = process.argv.indexOf('--baseline-ref');
const BASELINE_COMMIT = _refIdx >= 0 && process.argv[_refIdx + 1] ? process.argv[_refIdx + 1] : 'main';
const _jsonIdx = process.argv.indexOf('--json');
const JSON_OUT = _jsonIdx >= 0 && process.argv[_jsonIdx + 1] ? process.argv[_jsonIdx + 1] : null;
// 本批改动的前端件——改前模式一律取批基版本（全取 ⇒ 「改前」是完整旧形态，非新旧混装）
const CHANGED = [
  'js/main.js', 'js/ctxthresh.js', 'css/chat.css',
  'js/locales/en.js', 'js/locales/zh-CN.js',
];
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'e2e-root';
const NODE_SID = 'node-e2e-sub';
const WINDOW = 1000000;          // preset 链现取窗口（1M）
const DEFAULT_TOKENS = 256000;   // 现值函数读数（>300k ⇒ 固定 256000）
const DEG_PER_PCT = 3.6;

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
  console.log(`[ctxthresh e2e] mode=${MODE} webRoot=${WEB_ROOT} baselineRef=${BASELINE_COMMIT}`);
  const browser = await chromium.launch();
  const failures = [];
  const results = [];
  const readings = {};
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
  const setFrames = async () => (await sent()).filter((m) => m.type === 'setCompactThreshold');

  // ── 读数面（全部 DOM 派生；新旧两形态同款可读）──
  const ringInfo = () => page.evaluate(() => {
    const host = document.getElementById('header-model-info');
    const wrap = host && host.querySelector('.ctx-ring-wrap');
    const svg = host && host.querySelector('.ctx-ring-svg');
    const line = host && host.querySelector('.ctx-ring-threshold');
    const pctEl = host && host.querySelector('.ctx-ring-pct');
    const guide = svg && svg.querySelector('.ctx-ring-guide');
    return {
      hostPresent: !!host, wrapPresent: !!wrap,
      transform: line ? line.getAttribute('transform') : null,
      geom: line ? ['x1', 'y1', 'x2', 'y2'].map((a) => line.getAttribute(a)).join('/') : null,
      readout: pctEl ? (pctEl.textContent || '').trim() : null,
      readoutPx: pctEl ? getComputedStyle(pctEl).fontSize : null,
      readoutColor: pctEl ? getComputedStyle(pctEl).color : null,
      guide: !!guide,
      guideIsLast: svg ? (svg.lastElementChild === guide) : null,
      svgChildren: svg ? svg.children.length : null,
      draggingClass: host ? host.classList.contains('ctx-ring-dragging') : null,
      tooltip: wrap ? (wrap.getAttribute('title') || '') : '',
      aria: host ? {
        role: host.getAttribute('role'),
        tabindex: host.getAttribute('tabindex'),
        min: host.getAttribute('aria-valuemin'),
        max: host.getAttribute('aria-valuemax'),
        now: host.getAttribute('aria-valuenow'),
        text: host.getAttribute('aria-valuetext'),
        disabled: host.getAttribute('aria-disabled'),
      } : null,
    };
  });
  const ringBox = () => page.evaluate(() => {
    const wrap = document.querySelector('#header-model-info .ctx-ring-wrap');
    if (!wrap) return null;
    const r = wrap.getBoundingClientRect();
    return { left: r.left, top: r.top, right: r.right, bottom: r.bottom, w: r.width, h: r.height, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
  });
  // 面板标识残留面：`ctxthresh-*` id / class（面板容器·滑杆·数字框·按钮·读数位·注入样式）
  // + `ctx-bar-*` 孤儿类。本模块自己的注入样式 id 已改 `ctxring-css`（环形态），
  // 故「面板标识零命中」= 真正的零残留读数。
  const panelResidue = () => page.evaluate(() => {
    const ids = [...document.querySelectorAll('*[id]')].map((e) => e.id).filter((i) => /^ctxthresh-|^ctx-bar/.test(i));
    const cls = [...document.querySelectorAll('*[class]')].flatMap((e) => [...e.classList].filter((c) => /ctxthresh|ctx-bar/.test(c)));
    return { ids, cls: [...new Set(cls)], headStyle: !!document.getElementById('ctxring-css') };
  });
  const bodyText = () => page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').trim());
  const focusRing = () => page.evaluate(() => { const h = document.getElementById('header-model-info'); if (!h) return null; h.focus(); return document.activeElement === h; });
  /** 手势：起手在环上（12 点方向 11.5px）、终点 = 目标百分点角度 × dist px。 */
  const gesture = async (targetPct, dist = 110, opts = {}) => {
    const b = await ringBox();
    const rad = (targetPct * DEG_PER_PCT) * Math.PI / 180;
    const sx = b.cx, sy = b.cy - 11.5;
    const ex = b.cx + dist * Math.sin(rad), ey = b.cy - dist * Math.cos(rad);
    await page.mouse.move(sx, sy);
    await page.mouse.down();
    await page.mouse.move(ex, ey, { steps: opts.steps || 10 });
    if (opts.mid) await opts.mid();
    await page.mouse.up();
    await page.waitForTimeout(120);
    return { sx, sy, ex, ey, b };
  };

  // ── boot ──
  await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(300);
  await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 12000, contextWindow: WINDOW, model: 'qa-model' });
  await df(infoFrame(ROOT_SID, null));
  await page.waitForTimeout(250);

  const boot = await ringInfo();
  readings.boot = boot;
  const ringOk = boot.wrapPresent === true;

  // ── U1 入口唯一 + 面板零残留 ──
  const res0 = await panelResidue();
  check('U1 入口唯一：面板标识 (#ctxthresh-panel / .ctxthresh-*) 全域零命中',
    res0.ids.length === 0 && res0.cls.length === 0, `ids=${JSON.stringify(res0.ids)} cls=${JSON.stringify(res0.cls)}`);
  check('U1b 环在场（唯一入口前置）', ringOk, `wrapPresent=${boot.wrapPresent}`);

  if (!ringOk) {
    for (const id of ['U2 零常驻文字：全域无「已超限 / Over limit」串',
      'U3 拖动提交：恰一条 setCompactThreshold{ratio=0.62}', 'U3b 拖动中跟手三层',
      'U3c 松手后读数交还用量 %', 'U4a Delete ⇒ ratio:null', 'U4b 双击 ⇒ 末帧 ratio:null',
      'U5 键盘 ←/→/Home/End', 'U6 作用域', 'U7 钳制', 'U8 90% 硬顶', 'U9 缺字段',
      'U11 a11y', 'U12 命中面', 'U13 取消腿']) check(id, false, 'ring missing（改前形态无环上拖动腿）');
  } else {
    // ── U2 零常驻文字 ──
    const t0 = await bodyText();
    check('U2 零常驻文字：全域无「已超限 / Over limit」串',
      !/已超限|over limit/i.test(t0), `body="${t0.slice(0, 120)}"`);

    // ── U3/U3b/U3c 拖动：起手在环上、终点离环心 110px、目标 62% ──
    await clearSent();
    let mid = null;
    const g = await gesture(62, 110, { mid: async () => {
      mid = await ringInfo();
      // 🔴 S1 预览钩子：拖动中插入一条权威帧 (usageUpdate)，把手不得被抢回
      await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 12000, contextWindow: WINDOW, model: 'qa-model' });
      await page.waitForTimeout(80);
      mid.afterAuthoritativeFrame = await ringInfo();
    } });
    const frames = await setFrames();
    readings.gesture = { ...g, mid, frames };
    check('U3 拖动提交：恰一条 setCompactThreshold{ratio=0.62}',
      frames.length === 1 && frames[0].ratio === 0.62 && frames[0].sessionId === ROOT_SID,
      `frames=${JSON.stringify(frames)}`);
    const tf = parseFloat(String(mid && mid.transform || '').replace(/[^0-9.]/g, ''));
    check('U3b1 拖动中把手跟手：rotate(223.2 18 18)（容差 ±0.01）',
      mid && Math.abs(tf - 223.2) <= 0.01, `transform="${mid && mid.transform}" → ${tf} (期望 223.2)`);
    check('U3b2 不脱环：<line> 端点全程恒 18/1.5/18/5',
      mid && mid.geom === '18/1.5/18/5', `geom="${mid && mid.geom}"`);
    check('U3b3 拖动中环心读数 = 阈值 %（62）+ 字号 12px',
      mid && mid.readout === '62' && mid.readoutPx === '12px', `readout="${mid && mid.readout}" size=${mid && mid.readoutPx}`);
    check('U3b4 角度导引线在场且为 SVG 最后子节点（保 circle:nth-child(2) 用量弧定位）',
      mid && mid.guide === true && mid.guideIsLast === true && mid.svgChildren === 4,
      `guide=${mid && mid.guide} last=${mid && mid.guideIsLast} children=${mid && mid.svgChildren}`);
    check('U3b5 S1 预览钩子：拖动中插入 usageUpdate 权威帧 ⇒ 把手/读数不被抢回',
      mid && mid.afterAuthoritativeFrame && mid.afterAuthoritativeFrame.transform === mid.transform &&
      mid.afterAuthoritativeFrame.readout === mid.readout,
      `before="${mid && mid.transform}/${mid && mid.readout}" after="${mid && mid.afterAuthoritativeFrame && mid.afterAuthoritativeFrame.transform}/${mid && mid.afterAuthoritativeFrame && mid.afterAuthoritativeFrame.readout}"`);
    // 权威帧回显 ⇒ 环 tooltip 与刻线角度随之变（读数唯一权威面）
    await df(infoFrame(ROOT_SID, 0.62));
    await page.waitForTimeout(200);
    const after = await ringInfo();
    readings.afterEcho = after;
    check('U3b6 权威帧回显后环 tooltip `threshold 62%` + 刻线角度 223.2（会话级热生效，无重载/重连/刷新）',
      after.tooltip.includes('threshold 62%') && Math.abs(parseFloat(String(after.transform).replace(/[^0-9.]/g, '')) - 223.2) <= 0.01,
      `tooltip="${after.tooltip}" transform="${after.transform}"`);
    check('U3c 松手后（回显后）读数交还用量 %（=1）',
      after.readout === '1', `readout="${after.readout}"`);

    // ── U4 恢复默认两腿 ──
    const focused = await focusRing();
    await clearSent();
    await page.keyboard.press('Delete');
    await page.waitForTimeout(150);
    const f4a = (await setFrames()).pop();
    check('U4a 恢复默认（环聚焦后 Delete）⇒ 出站 ratio:null',
      focused === true && !!f4a && f4a.ratio === null && f4a.sessionId === ROOT_SID, `frame=${JSON.stringify(f4a)}`);
    await df(infoFrame(ROOT_SID, null));
    await page.waitForTimeout(150);
    await clearSent();
    const bb = await ringBox();
    await page.mouse.dblclick(bb.cx, bb.cy - 11.5);
    await page.waitForTimeout(220);
    const dbl = await setFrames();
    readings.dblclickFrames = dbl;
    check('U4b 恢复默认（双击环）⇒ 末帧 ratio:null（P2「起手即写值」⇒ 双击含两次中间提交，已入报告待裁）',
      dbl.length >= 1 && dbl[dbl.length - 1].ratio === null && dbl[dbl.length - 1].sessionId === ROOT_SID,
      `frames=${JSON.stringify(dbl)}`);
    await df(infoFrame(ROOT_SID, null));
    await page.waitForTimeout(150);

    // ── U5 键盘：←/→ ±1% · Home = 动态下限 · End = 90% ──
    await page.evaluate(() => document.getElementById('header-model-info').focus());
    const press = async (k) => { await clearSent(); await page.keyboard.press(k); await page.waitForTimeout(140); const f = (await setFrames()).pop(); if (f) await df(infoFrame(ROOT_SID, f.ratio)); await page.waitForTimeout(90); return f; };
    const kRight = await press('ArrowRight');
    const kRight2 = await press('ArrowRight');
    const kLeft = await press('ArrowLeft');
    const kHome = await press('Home');
    const kEnd = await press('End');
    readings.keyboard = { ArrowRight: kRight, ArrowRight2: kRight2, ArrowLeft: kLeft, Home: kHome, End: kEnd };
    check('U5 键盘 ←/→ ±1%（基准 = 权威值 26% ⇒ 27 / 28 / 27）',
      kRight && Math.round(kRight.ratio * 100) === 27 && kRight2 && Math.round(kRight2.ratio * 100) === 28 &&
      kLeft && Math.round(kLeft.ratio * 100) === 27,
      `→${kRight && kRight.ratio} →${kRight2 && kRight2.ratio} ←${kLeft && kLeft.ratio}`);
    check('U5b 键盘 Home = 动态下限（用量 1.2% ⇒ 16%）/ End = 90% 硬顶',
      kHome && Math.round(kHome.ratio * 100) === 16 && kEnd && Math.round(kEnd.ratio * 100) === 90,
      `Home=${kHome && kHome.ratio} End=${kEnd && kEnd.ratio}`);

    // ── U6 作用域：非主会话帧不入环；出站帧只载主会话 id ──
    const before6 = await ringInfo();
    await df(infoFrame(NODE_SID, 0.80, 500000));
    await df({ type: 'usageUpdate', sessionId: NODE_SID, inputTokens: 400000, contextWindow: 500000, compactThreshold: 0.512 });
    await page.waitForTimeout(200);
    const after6 = await ringInfo();
    check('U6a 非主会话回显帧不入 Nebula 窗口的环',
      after6.tooltip === before6.tooltip && after6.transform === before6.transform && after6.readout === before6.readout,
      `before="${before6.tooltip}/${before6.readout}" after="${after6.tooltip}/${after6.readout}"`);
    const sids = new Set((await page.evaluate(() => window.__wsSent || []))
      .filter((m) => m.type === 'setCompactThreshold').map((m) => m.sessionId));
    check('U6b 出站面作用域：setCompactThreshold 只载主会话 id',
      sids.size <= 1 && (sids.size === 0 || sids.has(ROOT_SID)), `sids=${JSON.stringify([...sids])}`);

    // ── U7 钳制 ──
    await df(infoFrame(ROOT_SID, null));
    await clearSent();
    await gesture(0, 40);                    // 12 点方向 ⇒ pct 0 ⇒ 钳到 16%
    const f7a = (await setFrames()).pop();
    check('U7a 钳制：pct 0 ⇒ 钳到 16%（> 15% 开区间下限）',
      !!f7a && Math.abs(f7a.ratio - 0.16) < 1e-9, `ratio=${f7a && f7a.ratio}`);
    await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 400000, contextWindow: WINDOW, model: 'qa-model' });
    await page.waitForTimeout(200);
    await clearSent();
    await gesture(20, 60);                   // 用量 40% ⇒ 拖到 20% 必须钳回 40%
    const f7b = (await setFrames()).pop();
    check('U7b 钳制：动态下限（用量 40% ⇒ 拖到 20% 钳回 40%）',
      !!f7b && Math.abs(f7b.ratio - 0.40) < 1e-9, `ratio=${f7b && f7b.ratio}`);

    // ── U8 90% 硬顶 ──
    await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 950000, contextWindow: WINDOW, model: 'qa-model' });
    await page.waitForTimeout(150);
    await clearSent();
    await gesture(40, 90);                   // 拖动腿
    await page.evaluate(() => document.getElementById('header-model-info').focus());
    await page.keyboard.press('ArrowRight'); // 键盘腿
    await page.keyboard.press('Delete');     // 复位腿（键）
    const bb8 = await ringBox();
    await page.mouse.dblclick(bb8.cx, bb8.cy - 11.5);   // 复位腿（双击）
    await page.waitForTimeout(250);
    const f8 = await setFrames();
    const t8 = await bodyText();
    const r8 = await ringInfo();
    readings.locked = { frames: f8, aria: r8.aria };
    check('U8a 90% 硬顶：用量 95% ⇒ 三腿（拖动/键盘/双击）零出站',
      f8.length === 0, `frames=${JSON.stringify(f8)}`);
    check('U8b 90% 硬顶：零文案（全域无「已超限 / Over limit」串）',
      !/已超限|over limit/i.test(t8), `body="${t8.slice(0, 120)}"`);
    check('U8c 90% 硬顶：环 aria-disabled=true（锁定态只以不可拖表达）',
      r8.aria && r8.aria.disabled === 'true', `aria=${JSON.stringify(r8.aria)}`);
    check('U8d 出站值永不 > 90%：全帧 ratio ≤ 0.90',
      (await page.evaluate(() => window.__wsSent || []))
        .filter((m) => m.type === 'setCompactThreshold' && typeof m.ratio === 'number' && m.ratio > 0.90).length === 0,
      'over=[]');

    // ── U9 缺字段回显帧（旧数据）⇒ 降级不崩 ──
    await df({ type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 12000, contextWindow: WINDOW, model: 'qa-model' });
    await df({ type: 'compactThresholdInfo', sessionId: ROOT_SID });
    await page.waitForTimeout(200);
    const r9 = await ringInfo();
    const err9 = await page.evaluate(() => window.__shotErrors || []);
    check('U9 缺字段回显帧 ⇒ 降级不崩（环仍在 + 零 JS 错误）',
      r9.wrapPresent === true && err9.length === 0, `wrap=${r9.wrapPresent} errors=${JSON.stringify(err9)}`);

    // ── U11 a11y：面板 aria 面整体迁到环 ──
    await df(infoFrame(ROOT_SID, null));
    await page.waitForTimeout(150);
    const r11 = await ringInfo();
    readings.a11y = r11.aria;
    check('U11 a11y：role=slider + tabindex + aria-valuemin/max/now/valuetext（面板 aria 面已整体迁到环）',
      r11.aria && r11.aria.role === 'slider' && r11.aria.tabindex === '0' &&
      r11.aria.min === '16' && r11.aria.max === '90' && r11.aria.now === '26' && r11.aria.text === '26%',
      `aria=${JSON.stringify(r11.aria)}`);

    // ── U12 命中面：环本体 28×28 + 伪元素命中面 32×44 ──
    const hit = await page.evaluate(() => {
      const wrap = document.querySelector('#header-model-info .ctx-ring-wrap');
      const r = wrap.getBoundingClientRect();
      const inside = (x, y) => { const el = document.elementFromPoint(x, y); return !!el && (el === wrap || wrap.contains(el)); };
      const midY = r.top + r.height / 2, midX = r.left + r.width / 2;
      return {
        bodyW: +r.width.toFixed(2), bodyH: +r.height.toFixed(2),
        hit: {
          left: inside(r.left - 1, midY), right: inside(r.right + 1, midY),
          top: inside(midX, r.top - 7), bottom: inside(midX, r.bottom + 7),
        },
        miss: {
          left: inside(r.left - 3, midY), right: inside(r.right + 3, midY),
          top: inside(midX, r.top - 9), bottom: inside(midX, r.bottom + 9),
        },
      };
    });
    readings.hitArea = hit;
    check('U12 命中面：环本体 28×28 + 伪元素命中面 32×44（`::before inset:-8px -2px`）',
      hit.bodyW === 28 && hit.bodyH === 28 &&
      hit.hit.left && hit.hit.right && hit.hit.top && hit.hit.bottom &&
      !hit.miss.left && !hit.miss.right && !hit.miss.top && !hit.miss.bottom,
      JSON.stringify(hit));

    // ── U13 取消腿：pointercancel / Escape ⇒ 零出站 + 回弹权威值 ──
    await df(infoFrame(ROOT_SID, 0.62));
    await page.waitForTimeout(150);
    const auth = await ringInfo();
    // (a) pointercancel
    const b13 = await ringBox();
    await clearSent();
    await page.mouse.move(b13.cx, b13.cy - 11.5);
    await page.mouse.down();
    await page.mouse.move(b13.cx + 90, b13.cy + 40, { steps: 6 });
    const duringPc = await ringInfo();
    await page.evaluate(() => {
      const h = document.getElementById('header-model-info');
      h.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerId: 1 }));
    });
    await page.waitForTimeout(150);
    await page.mouse.up();
    await page.waitForTimeout(150);
    const afterPc = await ringInfo();
    const fPc = await setFrames();
    check('U13a 取消腿 pointercancel ⇒ 零出站 + 回弹权威值（拖动中的角度 ≠ 权威角度）',
      fPc.length === 0 && duringPc.transform !== auth.transform && afterPc.transform === auth.transform && afterPc.guide === false,
      `during="${duringPc.transform}" auth="${auth.transform}" after="${afterPc.transform}" frames=${JSON.stringify(fPc)}`);
    // (b) Escape
    await clearSent();
    await page.mouse.move(b13.cx, b13.cy - 11.5);
    await page.mouse.down();
    await page.mouse.move(b13.cx + 70, b13.cy + 60, { steps: 6 });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(120);
    const afterEsc = await ringInfo();
    await page.mouse.up();
    await page.waitForTimeout(150);
    const fEsc = await setFrames();
    check('U13b 取消腿 Escape ⇒ 零出站 + 回弹权威值',
      fEsc.length === 0 && afterEsc.transform === auth.transform && afterEsc.guide === false,
      `after="${afterEsc.transform}" auth="${auth.transform}" frames=${JSON.stringify(fEsc)}`);
  }

  // ── U10 键集机械核：en/zh 对称 + 无未消费 `ctxthresh.*` 键 ──
  {
    const src = MODE === 'before' ? baselineBlob : null;
    const readLocale = (rel) => src ? src['/' + rel].toString('utf8') : readFileSync(join(WEB_ROOT, rel), 'utf8');
    const allKeys = (rel) => [...readLocale(rel).matchAll(/^\s*'([A-Za-z0-9_.]+)'\s*:/gm)].map((m) => m[1]);
    const enAll = allKeys('js/locales/en.js'), zhAll = allKeys('js/locales/zh-CN.js');
    const onlyEn = enAll.filter((k) => !zhAll.includes(k)), onlyZh = zhAll.filter((k) => !enAll.includes(k));
    const ctxKeys = enAll.filter((k) => k.startsWith('ctxthresh.'));
    const consumed = new Set();
    const walk = (dir) => {
      for (const ent of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, ent.name);
        if (ent.isDirectory()) { if (ent.name !== 'locales') walk(p); continue; }
        if (!ent.name.endsWith('.js')) continue;
        const rel = 'js/' + p.split(join(WEB_ROOT, 'js') + '/')[1];
        const txt = (MODE === 'before' && baselineBlob['/' + rel]) ? baselineBlob['/' + rel].toString('utf8') : readFileSync(p, 'utf8');
        for (const m of txt.matchAll(/t\(\s*'ctxthresh\.([A-Za-z0-9_]+)'/g)) consumed.add('ctxthresh.' + m[1]);
      }
    };
    walk(join(WEB_ROOT, 'js'));
    const unconsumed = ctxKeys.filter((k) => !consumed.has(k));
    readings.keySet = { en: enAll.length, zh: zhAll.length, ctxKeys, unconsumed, onlyEn, onlyZh };
    check('U10 键集机械核：en/zh 对称 + 无未消费 `ctxthresh.*` 键（面板悬空键已双侧同删）',
      onlyEn.length === 0 && onlyZh.length === 0 && unconsumed.length === 0,
      `en=${enAll.length} zh=${zhAll.length} ctxthresh=${JSON.stringify(ctxKeys)} unconsumed=${JSON.stringify(unconsumed)} only-en=${JSON.stringify(onlyEn)} only-zh=${JSON.stringify(onlyZh)}`);
  }

  const pageErrors = await page.evaluate(() => window.__shotErrors || []);
  check('页面零 JS 错误', pageErrors.length === 0, JSON.stringify(pageErrors));

  await browser.close();

  if (JSON_OUT) {
    writeFileSync(JSON_OUT, JSON.stringify({ mode: MODE, webRoot: WEB_ROOT, baselineRef: BASELINE_COMMIT, results, readings }, null, 2));
    console.log(`[ctxthresh e2e] readings → ${JSON_OUT}`);
  }

  const pass = results.filter((r) => r.ok).length;
  console.log(`\n[${MODE}] ctxthresh UI 判据: ${pass}/${results.length} pass, ${failures.length} fail`);
  if (MODE === 'before') {
    console.log(`[before] 预期红：${failures.length} 项红（改前无环上拖动腿 ⇒ 全红）`);
    process.exit(0);
  }
  if (failures.length) {
    console.log('FAILURES:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  console.log('e2e-ctxthresh OK (exit 0)');
})();
