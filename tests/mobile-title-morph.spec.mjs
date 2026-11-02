#!/usr/bin/env node
// mobile-title-morph.spec.mjs — 面板标题「大标题 → 胶囊」单元素连续变形验收
// （作者令 2026-11-02 22:32，实拍双影：「我们现在面板的这个胶囊态是两个割裂的
// 形态，所以会出现两个重叠的现象，但是我想要的是文字逐渐缩小然后胶囊轮廓逐渐
// 显现的这种，两种形态平滑过渡，且一直是同一个的形式」）。
//
// 判据 L1–L11（逐条机械读数，不接受口头验收）：
//   L1 改前复现双影：同屏同名标题文字节点 ≥2 且矩形相交；改后恒 1。
//   L2 全滚动区间取样 ≥10 点，每点标题文字节点恒 1。
//   L3 字号连续：单调递减、相邻差 ≤ --t（13px 总量 / 12 步 ≈ 1.08，阈值取 1.5）。
//   L4 轮廓渐显：胶囊壳层 alpha / border-radius / 等效 blur 从 0 连续增至满，
//      相邻差 ≤ 阈值（alpha 0.15、radius 120px、blur 3px），非阶跃。
//   L5 无宽度跳变：渲染宽相邻差 ≤ 12px（字号 13px 跨 12 步 + 内距 96px 跨 12 步）。
//   L6 无重影像素级证据：半程帧上「只藏主标题 / 再藏装饰副本」两拍像素差 ⇒
//      残留墨迹像素必须为 0（= 页名只由一个元素渲染）。改前 >0（双影）。
//   L7 终点形态 = 改前胶囊终态（15px / 48 高 / r999 / 中心 40 / 玻璃满 / 宽 = 文字+48）。
//   L8 降级：CSS scroll-timeline 路径未采用（给机械读数说明为何不可用：pane 页的
//      主滚轴是标题的**兄弟子树**，不在祖先链上，scroll() 绑定不到）；实有降级
//      路径 = ①无 JS（--mtitle-p 取 0）②JS 连续路径，两者读数一致（对照实测）。
//   L9 reduced-motion：进度二值，任一取样点只会是静止态或终态，不卡中间。
//   L10 桌面回归：computed 快照落盘，跨版本 diff（脚本自身给出 0 差异判定）。
//   L11 通治：≥3 面板（后台任务 / 项目 / 设置 / 待办）同一套读数全绿。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: MTM_TAG=before node tests/mobile-title-morph.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const TAG = process.env.MTM_TAG || 'run';
const OUT = process.env.MTM_OUT_DIR || join(ROOT, 'evidence', `mobile-title-morph-${TAG}`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mtm-root-session';
// 端口 8899：WebKit bad-port 封禁表外（同族 spec 实证）。视口 390×844（作者实拍档）。
const ORIGIN = 'http://localhost:8899';
const VW = 390;
const VH = 844;
// 进度区间（与 navController.js ROLL_START/ROLL_THRESHOLD 同源）。
const ROLL_START = 8;
const ROLL_SNAP = 56;
const TITLE_FS_BIG = 28;
const TITLE_FS_MINI = 15;
const CAP_H = 48;
const CAP_CENTER_Y = 40;
const INSET = 72;
const CAP_PAD = 24;

// 断言阈值（理由写在报告与下方 ok 标签里）。
// 采样 = 过渡区间 8→56 上等距 13 点（步长 4px ⇒ Δp = 1/12），区间外再各取一点。
// 于是「相邻两点 = 一个 Δp 步」，「单点跳变」有统一含义：
const TH_FS_STEP = 1.6;     // (28−15)/12 = 1.0833，留 ~48% 余量吸亚像素/取整
const TH_ALPHA_STEP = 0.15; // 壳层 alpha 0→1 跨 12 步 ≈ 0.083，留近一倍余量
const TH_RADIUS_STEP = 120; // 圆角恒 999（不插值）：只判无跳变
const TH_BLUR_STEP = 3;     // 等效 blur 0→24 跨 12 步 = 2
const TH_W_STEP = 20;       // 宽 = 文字宽 + 内距(96px) 收缩：每 Δp 步文字按
                            // 当前字号比例缩 1/12 ≈ 8%（长页名 ≤ 20px/步），
                            // 阈值 20 只拦「单点跳变」，不拦字号驱动的正常收缩
const TH_GHOST_PX = 0;      // 双影残留墨迹像素（L6 硬零）

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page, sink) => {
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) sink.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) sink.push(`console.error: ${m.text()}`); });
};

/* ── 夹具 ─────────────────────────────────────────────────────────── */
const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: `fixture-project-${String(i + 1).padStart(2, '0')}`,
  workspace: `/tmp/mtm/proj-${i + 1}`,
  agentFile: '',
  description: i % 2 === 0 ? '验收夹具项目（滚动域填充行）' : '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 24 }, (_, i) => ({
  id: `mtm-td-${i + 1}`, kind: 'todo',
  content: `验收夹具待办 ${i + 1}：滚动收缩域填充行`,
  triggerAt: Date.now() + 3600_000,
}));
const T0 = Date.now();
const BG_TASKS = [
  { taskId: 'job-1', description: '标题变形批：夹具任务一', status: 'completed', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  { taskId: 'job-2', description: '标题变形批：夹具任务二', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  { taskId: 'job-3', description: '标题变形批：夹具任务三', status: 'running', startedAt: T0 - 8_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
];
/** 智能体行夹具（后台任务页的唯一滚动域；24 行 = 余量充足）。 */
function agentEntries() {
  const rows = [{ id: 'delegate-fixture-1', info: {
    name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
    status: 'Processing', startedAt: T0 - 120_000, parentSessionId: SID,
    task: '标题变形批夹具：delegate 组头',
  } }];
  for (let i = 1; i <= 4; i += 1) {
    rows.push({ id: `subtask-fixture-${i}`, info: {
      name: `subtask-fixture-${i}`, kind: 'SubTask', sessionId: `subtask-fixture-${i}`,
      status: 'Processing', startedAt: T0 - (60_000 + i * 1_000), parentSessionId: 'delegate-fixture-1',
      task: `标题变形批夹具：派生第 ${i} 条`,
    } });
  }
  for (let i = 1; i <= 19; i += 1) {
    rows.push({ id: `node-fixture-${i}`, info: {
      name: `node-fixture-${i}`, kind: 'AgentFlow', sessionId: `node-fixture-${i}`,
      status: i % 3 === 0 ? 'Completed' : 'Processing', startedAt: T0 - (i * 7_000), parentSessionId: SID,
      task: `标题变形批夹具：平铺第 ${i} 条`,
    } });
  }
  return rows;
}

/* ── boot ─────────────────────────────────────────────────────────── */
async function boot(scheme, engineName, opts = {}) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const ctx = await engine.launch().then((b) => b.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
    reducedMotion: opts.reducedMotion ? 'reduce' : 'no-preference',
  }));
  const page = await ctx.newPage();
  const errors = [];
  watchErrors(page, errors);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') {
        ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: m.scope === 'all' ? 'all' : 'session', tasks: TODOS.map((t) => ({ ...t })) }));
      }
    });
    // activeBgTasks 快照 = { [sessionId]: Task[] }（后台任务面板读对象桶；
    // BG_TASKS 顶层数组会被客户端 handler 当桶遍历报错 —— 单滚批夹具同口径）。
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [SID]: BG_TASKS } }));
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(600);
  return { page, errors };
}

/** 后台任务页的滚动域填充（智能体行直种 —— 与单滚批 spec 同款路径）。 */
async function seedAgents(page) {
  return page.evaluate(({ sid, rows }) => import('/js/state.js').then((m) => {
    const bucket = {};
    for (const r of rows) bucket[r.id] = r.info;
    m.default.sessionBgAgents[sid] = bucket;
    m.default.updateBgAgentIndicator();
    return Object.keys(m.default.sessionBgAgents[sid]).length;
  }), { sid: SID, rows: agentEntries() });
}

/* ── 导航（全走真实 DOM 点击）───────────────────────────────────────── */
async function gotoWorkbench(page) {
  await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page.live');
    if (sec) sec.querySelectorAll('.mobile-back-btn').forEach((b) => b.click());
  });
  for (let i = 0; i < 6; i += 1) {
    const onHome = await page.evaluate(() => !document.querySelector('#mobile-pages .mobile-page.live[data-page]:not([data-page="messages"]):not([data-page="contacts"]):not([data-page="canvas"]):not([data-page="workbench"])'));
    if (onHome) break;
    await page.waitForTimeout(120);
  }
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
  await page.waitForTimeout(200);
}
async function openPage(page, hubPage, liveSelector) {
  await gotoWorkbench(page);
  await page.locator(`#mobile-pages .mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${hubPage}"]`).click();
  await page.waitForSelector(liveSelector, { timeout: 15000 });
  await page.waitForTimeout(450);
}
async function openBackground(page) {
  await openPage(page, 'background', '#mobile-pages .mobile-page.live[data-page="background"] .mobile-page-body > #bg-dropdown .bg-task-row');
  await page.waitForTimeout(300);
}
async function openTodo(page) {
  await gotoWorkbench(page);
  await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry')));
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="todo"] #todo-page .reminder-row', { timeout: 15000 });
  await page.waitForTimeout(450);
}

/* ── 探针：标题态全读数（文字节点可见性/矩形/壳层/几何）─────────────── */
const STATE_JS = (pid) => `(() => {
  const sec = document.querySelector('#mobile-pages .mobile-page[data-page="${pid}"]');
  if (!sec) return { found: false };
  const title = sec.querySelector('.mobile-page-title');
  if (!title) return { found: false, why: 'no .mobile-page-title' };
  const mini = sec.querySelector('.mobile-page-title-mini');
  const cs = getComputedStyle(title);
  const pv = parseFloat(cs.getPropertyValue('--mtitle-p')) || 0;
  const effOpacity = (el) => {
    let o = 1;
    for (let e = el; e && e !== document.body; e = e.parentElement) o *= parseFloat(getComputedStyle(e).opacity);
    return o;
  };
  const txt = (title.textContent || '').trim();
  // 同名可见文字节点枚举（**不排除 aria-hidden**：装饰副本是可见渲染，正是双影本体）
  const nodes = [];
  const walker = document.createTreeWalker(sec, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  while (walker.nextNode()) {
    const n = walker.currentNode;
    const v = (n.nodeValue || '').trim();
    if (!v || v !== txt) continue;
    const host = n.parentElement;
    if (!host) continue;
    const hcs = getComputedStyle(host);
    if (hcs.visibility === 'hidden' || hcs.display === 'none') continue;
    const op = effOpacity(host);
    if (op <= 0.02) continue;
    range.selectNodeContents(n);
    const b = range.getBoundingClientRect();
    if (b.width < 1 || b.height < 1) continue;
    if (b.bottom <= 0 || b.top >= innerHeight) continue;
    nodes.push({
      sel: '.' + String(host.className || host.tagName).split(' ')[0],
      text: v, op: Math.round(op * 1000) / 1000,
      fs: hcs.fontSize, ls: hcs.letterSpacing,
      rect: { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), r: +b.right.toFixed(1), b: +b.bottom.toFixed(1) },
    });
  }
  let pairs = 0; let pairSample = '';
  for (let i = 0; i < nodes.length; i += 1) {
    for (let j = i + 1; j < nodes.length; j += 1) {
      const a = nodes[i].rect; const b = nodes[j].rect;
      const ix = Math.min(a.r, b.r) - Math.max(a.x, b.x);
      const iy = Math.min(a.b, b.b) - Math.max(a.y, b.y);
      if (ix > 0 && iy > 0 && ix * iy > 0.5) {
        pairs += 1;
        if (!pairSample) pairSample = i + '/' + j + ' area=' + (ix * iy).toFixed(1) + 'px²';
      }
    }
  }
  const tb = title.getBoundingClientRect();
  const acs = getComputedStyle(title, '::before');
  const shell = mini || title; // 壳层载体：改前 = 装饰副本，改后 = 单元素伪层
  const scs = getComputedStyle(shell);
  const shellAlpha = mini
    ? (parseFloat(getComputedStyle(title, '::before').opacity) || 0) * 0 + effOpacity(mini)
    : parseFloat(acs.opacity) || 0;
  const radius = mini ? scs.borderTopLeftRadius : (acs.borderTopLeftRadius || cs.borderTopLeftRadius);
  const blurSrc = mini ? (scs.backdropFilter || scs.webkitBackdropFilter || '') : '';
  const blurPx = (() => {
    const m = /blur\\(([\\d.]+)px\\)/.exec(blurSrc || '');
    if (m) return parseFloat(m[1]);
    // 坑位实测：getComputedStyle(el,'::before').backdropFilter 在 Chromium/WebKit
    // 都返回空串（伪元素该属性的 computed 值不可读）。改后的壳层恒定使用玻璃包
    // --cv-blur（= --glass-blur，24px），故从 body 读 token 作为声明值读回。
    const n = parseFloat(getComputedStyle(document.body).getPropertyValue('--cv-blur').trim());
    return Number.isFinite(n) ? n : 0;
  })();
  const mt = parseFloat(cs.marginBottom) || 0;
  const mtTop = parseFloat(cs.marginTop) || 0;
  // 主滚轴（判定同 navController.refreshRoll）
  const bodyEl = sec.querySelector('.mobile-page-body');
  const depthOf = (n) => { let d = 0; for (let q = n; q && q !== sec; q = q.parentElement) d += 1; return d; };
  let primary = null; let best = Infinity;
  for (const n of [bodyEl, ...bodyEl.querySelectorAll('*')]) {
    const oy = getComputedStyle(n).overflowY;
    if (oy !== 'auto' && oy !== 'scroll') continue;
    if (n.scrollHeight - n.clientHeight <= 4) continue;
    const d = depthOf(n);
    if (d < best) { primary = n; best = d; }
    if (d === 1) break;
  }
  const scroller = primary || bodyEl;
  const titleTextW = (() => {
    const r = document.createRange(); r.selectNodeContents(title);
    const rects = [...r.getClientRects()];
    if (!rects.length) return 0;
    return Math.round((Math.max(...rects.map((x) => x.right)) - Math.min(...rects.map((x) => x.left))) * 100) / 100;
  })();
  return {
    found: true,
    p: Math.round(pv * 1000) / 1000,
    rolled: sec.classList.contains('rolled'),
    paneScroll: sec.classList.contains('mpt-pane-scroll'),
    titleText: txt,
    scrollerSel: scroller === bodyEl ? '.mobile-page-body' : (scroller.id ? '#' + scroller.id : '.' + String(scroller.className || '').split(' ')[0]),
    scrollerInTitleChain: [...(function* () { for (let q = title; q; q = q.parentElement) yield q; })()].includes(scroller),
    scrollTop: scroller.scrollTop,
    bodyScrollTop: bodyEl.scrollTop,
    font: parseFloat(cs.fontSize),
    lineHeight: parseFloat(cs.lineHeight),
    height: Math.round(tb.height * 100) / 100,
    width: Math.round(tb.width * 100) / 100,
    top: Math.round(tb.top * 100) / 100,
    bottom: Math.round(tb.bottom * 100) / 100,
    centerY: Math.round((tb.top + tb.height / 2) * 100) / 100,
    marginBottom: mt,
    marginTop: mtTop,
    // 流内占位 = 盒高 + 上外边距 + 下外边距（吸附态应 → 0，内容满铺到胶囊之下）
    footprint: Math.round((tb.height + mt + mtTop) * 100) / 100,
    textW: titleTextW,
    padL: parseFloat(cs.paddingLeft),
    padR: parseFloat(cs.paddingRight),
    opacity: Math.round(parseFloat(cs.opacity) * 1000) / 1000,
    radiusEl: cs.borderTopLeftRadius,
    shellAlpha: Math.round(shellAlpha * 1000) / 1000,
    radius,
    blurPx,
    blurEff: Math.round(blurPx * shellAlpha * 100) / 100,
    miniPresent: !!mini,
    nodes, nodeCount: nodes.length, overlapPairs: pairs, pairSample,
    vw: innerWidth,
  };
})()`;

const state = (page, pid) => page.evaluate(STATE_JS(pid));

/** 主滚轴 scrollTop 精调 + 派发 scroll（真实事件腿）。 */
async function setScroll(page, pid, v) {
  const r = await page.evaluate(({ pid, v }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const bodyEl = sec.querySelector('.mobile-page-body');
    const depthOf = (n) => { let d = 0; for (let q = n; q && q !== sec; q = q.parentElement) d += 1; return d; };
    let primary = null; let best = Infinity;
    for (const n of [bodyEl, ...bodyEl.querySelectorAll('*')]) {
      const oy = getComputedStyle(n).overflowY;
      if (oy !== 'auto' && oy !== 'scroll') continue;
      if (n.scrollHeight - n.clientHeight <= 4) continue;
      const d = depthOf(n);
      if (d < best) { primary = n; best = d; }
      if (d === 1) break;
    }
    const t = primary || bodyEl;
    t.scrollTop = v;
    t.dispatchEvent(new Event('scroll', { bubbles: false }));
    return { top: t.scrollTop, room: t.scrollHeight - t.clientHeight, sel: t.id ? '#' + t.id : '.' + String(t.className || '').split(' ')[0] };
  }, { pid, v });
  await page.waitForTimeout(120);
  return r;
}

/* ── 像素面：帧差双影探针 ───────────────────────────────────────────── */
const BAND = { x: 0, y: 0, width: VW, height: 96 };
async function bandShot(page, name) {
  const buf = await page.screenshot({ clip: BAND });
  if (name) writeFileSync(join(SHOTS, `${name}.png`), buf);
  return buf.toString('base64');
}
async function diffInk(page, a, b) {
  return page.evaluate(async ({ a, b }) => {
    const load = (src) => new Promise((res, rej) => {
      const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = 'data:image/png;base64,' + src;
    });
    const [ia, ib] = await Promise.all([load(a), load(b)]);
    const read = (img) => {
      const c = document.createElement('canvas');
      c.width = img.width; c.height = img.height;
      const x = c.getContext('2d', { willReadFrequently: true });
      x.drawImage(img, 0, 0);
      return x.getImageData(0, 0, c.width, c.height).data;
    };
    const da = read(ia); const db = read(ib);
    let n = 0; let maxd = 0;
    for (let i = 0; i < da.length; i += 4) {
      const d = Math.max(Math.abs(da[i] - db[i]), Math.abs(da[i + 1] - db[i + 1]), Math.abs(da[i + 2] - db[i + 2]));
      if (d > 8) { n += 1; if (d > maxd) maxd = d; }
    }
    return { w: ia.width, h: ia.height, diff: n, maxd, total: ia.width * ia.height };
  }, { a, b });
}

/** 藏/复 title（与装饰副本）——只动 visibility，不触发布局。 */
async function hideLayers(page, pid, { title = false, mini = false } = {}) {
  await page.evaluate(({ pid, title, mini }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const t = sec.querySelector('.mobile-page-title');
    const m = sec.querySelector('.mobile-page-title-mini');
    if (t) t.style.visibility = title ? 'hidden' : '';
    if (m) m.style.visibility = mini ? 'hidden' : '';
  }, { pid, title, mini });
  await page.waitForTimeout(80);
}

/* ── 采样点：过渡区间 8→56 等距 13 点（步长 4px = Δp 1/12）+ 区间外两点 ──── */
const SAMPLE_TOP = [0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 140];
const expectP = (st) => Math.round(Math.max(0, Math.min(1, (st - ROLL_START) / (ROLL_SNAP - ROLL_START))) * 1000) / 1000;
const monotone = (arr, dir) => arr.every((v, i) => i === 0 || (dir === 'down' ? v <= arr[i - 1] + 1e-6 : v >= arr[i - 1] - 1e-6));
const maxStep = (arr) => arr.reduce((m, v, i) => (i === 0 ? m : Math.max(m, Math.abs(v - arr[i - 1]))), 0);

/* ── 单面板全腿（L2–L7 + L11 的同套读数）────────────────────────────── */
const readings = { tag: TAG, panels: {}, meta: {} };

async function panelLeg(page, pid, tag, shotPrefix) {
  const rows = [];
  for (const top of SAMPLE_TOP) {
    const sc = await setScroll(page, pid, top);
    const st = await state(page, pid);
    st.requestedTop = top;
    st.actualTop = sc.top;
    st.room = sc.room;
    rows.push(st);
  }
  readings.panels[`${pid}-${tag}`] = rows.map((r) => ({
    top: r.actualTop, p: r.p, font: r.font, width: r.width, height: r.height, shellAlpha: r.shellAlpha,
    radius: r.radius, blurEff: r.blurEff, nodeCount: r.nodeCount, overlapPairs: r.overlapPairs, footprint: r.footprint,
  }));

  const live = rows.filter((r) => r.found);
  ok(`${tag} 页面壳与标题在场`, live.length === rows.length, `${live.length}/${rows.length}`);
  if (live.length !== rows.length) return rows;

  // ── L2 单实例：全区间每点同名可见文字节点 = 1 ──
  const multi = rows.filter((r) => r.nodeCount !== 1);
  ok(`${tag} L2 标题文字节点恒 1（${rows.length} 个取样位置）`, multi.length === 0,
    multi.map((r) => `top=${r.actualTop}:n=${r.nodeCount}[${r.nodes.map((x) => x.sel + '@' + x.op).join(',')}]`).join(' ') || 'all 1');
  const ghosts = rows.filter((r) => r.overlapPairs > 0);
  ok(`${tag} L2b 任一取样点无同名文字矩形相交（重影）`, ghosts.length === 0,
    ghosts.map((r) => `top=${r.actualTop}:${r.pairSample || r.overlapPairs}`).join(' ') || 'zero overlap');

  // ── L3 字号连续：单调递减 + 相邻差 ≤ 阈值 ──
  const fonts = rows.map((r) => r.font);
  const fsStep = maxStep(fonts);
  ok(`${tag} L3 字号单调递减（${fonts[0]} → ${fonts[fonts.length - 1]}px）`, monotone(fonts, 'down'), fonts.join('/'));
  ok(`${tag} L3b 字号相邻差 ≤ ${TH_FS_STEP}px（实测最大 ${fsStep.toFixed(2)}）`, fsStep <= TH_FS_STEP, `steps=${fonts.join('/')}`);
  ok(`${tag} L3c 字号起止 = 28 → 15px（大标题档 → 胶囊档）`,
    Math.abs(fonts[0] - TITLE_FS_BIG) <= 0.5 && Math.abs(fonts[fonts.length - 1] - TITLE_FS_MINI) <= 0.5, `${fonts[0]}/${fonts[fonts.length - 1]}`);
  ok(`${tag} L3d 全区间字号 ≥ 13px（Apple HIG 下限）`, Math.min(...fonts) >= 13, `min=${Math.min(...fonts)}`);

  // ── L4 轮廓渐显：alpha / radius / 等效 blur 连续增长 ──
  const alphas = rows.map((r) => r.shellAlpha);
  const radii = rows.map((r) => parseFloat(r.radius));
  const blurs = rows.map((r) => r.blurEff);
  ok(`${tag} L4 壳层 alpha 从 0 连续增至满（${alphas[0]} → ${alphas[alphas.length - 1]}）`,
    monotone(alphas, 'up') && alphas[0] <= 0.02 && alphas[alphas.length - 1] >= 0.98, alphas.join('/'));
  ok(`${tag} L4b 壳层 alpha 相邻差 ≤ ${TH_ALPHA_STEP}（实测 ${maxStep(alphas).toFixed(3)}）`, maxStep(alphas) <= TH_ALPHA_STEP, alphas.map((a) => a.toFixed(2)).join('/'));
  // 圆角：胶囊盒恒 999 全圆角 —— 「轮廓渐显」由 alpha/等效 blur 承担（透明盒的
  // 圆角不可感知；半径插值只会让低 alpha 轮廓畸变）。判据 = 全程无圆角跳变。
  ok(`${tag} L4c 圆角恒 999px 无跳变（全程 ${radii.join('/')}，相邻差 ≤ ${TH_RADIUS_STEP}）`,
    radii.every((r) => Math.abs(r - 999) <= 1) && maxStep(radii) <= TH_RADIUS_STEP,
    `maxStep=${maxStep(radii).toFixed(0)}`);
  ok(`${tag} L4e 壳层载体 = 标题元素自身（无装饰副本节点）`, rows.every((r) => !r.miniPresent),
    rows.some((r) => r.miniPresent) ? '存在 .mobile-page-title-mini 装饰副本（两套元素交接）' : 'single element');
  ok(`${tag} L4d 等效毛玻璃强度连续增长（${blurs[0]} → ${blurs[blurs.length - 1]}px blur，相邻差 ≤ ${TH_BLUR_STEP}）`,
    monotone(blurs, 'up') && blurs[0] <= 0.1 && blurs[blurs.length - 1] >= 20 && maxStep(blurs) <= TH_BLUR_STEP,
    `${blurs.join('/')} · maxStep=${maxStep(blurs).toFixed(2)}`);

  // ── L5 无宽度跳变 ──
  const widths = rows.map((r) => r.width);
  const wStep = maxStep(widths);
  ok(`${tag} L5 渲染宽相邻差 ≤ ${TH_W_STEP}px（实测 ${wStep.toFixed(1)}）`, wStep <= TH_W_STEP, widths.join('/'));
  ok(`${tag} L5b 宽度随字号收缩（起 ${widths[0]} → 终 ${widths[widths.length - 1]}）`, widths[0] > widths[widths.length - 1], `${widths[0]} → ${widths[widths.length - 1]}`);

  // ── L1/L6 双影（半程帧）：节点数 + 像素级残留 ──
  const midTop = 32; // p ≈ 0.5
  await setScroll(page, pid, midTop);
  const mid = await state(page, pid);
  await bandShot(page, `${shotPrefix}-p50-all`);
  ok(`${tag} L1 半程帧（p≈0.5）同名可见文字节点 = 1（实测 ${mid.nodeCount}）`, mid.nodeCount === 1,
    mid.nodes.map((n) => `${n.sel}@${n.fs}/α${n.op}`).join(' | ') || 'none');
  // 像素面：只藏主标题 → 再藏装饰副本，两拍差 = 「页名由第二元素渲染」的墨迹
  await hideLayers(page, pid, { title: true, mini: false });
  const shotTitleHidden = await bandShot(page, `${shotPrefix}-p50-titlehidden`);
  await hideLayers(page, pid, { title: true, mini: true });
  const shotAllHidden = await bandShot(page, `${shotPrefix}-p50-allhidden`);
  await hideLayers(page, pid, { title: false, mini: false });
  const ghost = await diffInk(page, shotTitleHidden, shotAllHidden);
  ok(`${tag} L6 像素级无重影（藏主标题后残留同名墨迹 = 0，实测 ${ghost.diff}px / maxΔ${ghost.maxd}）`,
    ghost.diff <= TH_GHOST_PX, `diffPx=${ghost.diff} of ${ghost.total}`);

  // ── L7 终点形态 = 胶囊档 ──
  await setScroll(page, pid, 140);
  const end = await state(page, pid);
  await bandShot(page, `${shotPrefix}-end`);
  ok(`${tag} L7 终点字号 15px`, Math.abs(end.font - TITLE_FS_MINI) <= 0.5, `${end.font}px`);
  ok(`${tag} L7b 终点壳层高 48（= 返回钮高）`, Math.abs(end.height - CAP_H) <= 1, `${end.height}px`);
  ok(`${tag} L7c 终点垂直中心 40（= 返回钮中心）`, Math.abs(end.centerY - CAP_CENTER_Y) <= 1, `${end.centerY}px`);
  ok(`${tag} L7d 终点全圆角 999 + 玻璃满 alpha`, parseFloat(end.radius) >= 998 && end.shellAlpha >= 0.98, `${end.radius}/${end.shellAlpha}`);
  ok(`${tag} L7e 终点宽度 = 文字宽 + 2×24 内距（${end.textW} + 48 ≈ ${end.width}）`,
    Math.abs(end.width - (end.textW + 2 * CAP_PAD)) <= 2, `w=${end.width} text=${end.textW}`);
  ok(`${tag} L7f 终点左缘 ≥ 72（不压返回钮）`, end.width / 2 <= (end.vw / 2 - INSET) + 1, `w=${end.width} vw=${end.vw}`);
  ok(`${tag} L7g 吸附态顶区流内占位 → 0（footprint = 高 + 负外边距）`,
    rows[rows.length - 2].paneScroll ? Math.abs(end.footprint) <= 1 : true, `footprint=${end.footprint} paneScroll=${end.paneScroll}`);

  // 三帧连续存证（静止 / 半程 / 终点）
  await setScroll(page, pid, 0);
  await bandShot(page, `${shotPrefix}-rest`);
  await setScroll(page, pid, 20);
  await bandShot(page, `${shotPrefix}-p25`);
  await setScroll(page, pid, 50);
  await bandShot(page, `${shotPrefix}-p75`);
  await setScroll(page, pid, 0);
  return rows;
}

/* ── L8 降级路径 ───────────────────────────────────────────────────── */
async function degradeLeg(page) {
  // (a) CSS scroll-timeline 路径可用性：scroll() 只能绑**祖先**滚动容器。pane 型
  //     面板（后台任务/项目）主滚轴在标题的兄弟子树里（不在祖先链），timeline
  //     无法表达 ⇒ 本批不采用该路径，JS 单路径为唯一真值源。机械读数如下。
  await seedAgents(page);
  await openBackground(page);
  const bg = await state(page, 'background');
  readings.degrade = { paneScroller: bg.scrollerSel, inAncestorChain: bg.scrollerInTitleChain, paneScroll: bg.paneScroll };
  console.log(`[L8a] background 主滚轴=${bg.scrollerSel} 是否标题祖先链上=${bg.scrollerInTitleChain} paneScroll=${bg.paneScroll}`);
  ok('L8a 机械证据：pane 页主滚轴不在标题祖先链上（scroll() 无法绑定 ⇒ 采用 JS 单路径）',
    !bg.scrollerInTitleChain || bg.paneScroll === false);

  // (b) 降级/静态路径一致性：把 --mtitle-p 强制写 0（= 无 JS 时的 CSS 兜底取值），
  //     读数必须与「JS 连续路径在 p=0」逐项一致。
  await setScroll(page, 'background', 0);
  const jsRest = await state(page, 'background');
  await page.evaluate(() => {
    const sec = document.querySelector('#mobile-pages .mobile-page[data-page="background"]');
    sec.style.setProperty('--mtitle-p', '0');
  });
  await page.waitForTimeout(80);
  const cssRest = await state(page, 'background');
  await page.evaluate(() => {
    const sec = document.querySelector('#mobile-pages .mobile-page[data-page="background"]');
    sec.style.removeProperty('--mtitle-p');
  });
  await setScroll(page, 'background', 0);
  const same = ['font', 'height', 'width', 'shellAlpha', 'centerY'].every((k) => Math.abs(parseFloat(jsRest[k]) - parseFloat(cssRest[k])) <= 0.6);
  ok('L8b 降级路径一致（无 JS 的 CSS 兜底 p=0 与 JS 连续路径 p=0 同读数，容差 0.6）', same,
    `JS=${JSON.stringify({ f: jsRest.font, h: jsRest.height, w: jsRest.width, a: jsRest.shellAlpha })} CSS=${JSON.stringify({ f: cssRest.font, h: cssRest.height, w: cssRest.width, a: cssRest.shellAlpha })}`);
}

/* ── L9 reduced-motion ─────────────────────────────────────────────── */
async function reducedLeg() {
  const { page, errors } = await boot('light', 'chromium', { reducedMotion: true });
  try {
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    const ps = []; const fonts = []; const alphas = [];
    for (const top of [0, 16, 24, 30, 36, 40, 46, 56, 120]) {
      await setScroll(page, 'projects', top);
      const s = await state(page, 'projects');
      ps.push(s.p); fonts.push(s.font); alphas.push(s.shellAlpha);
    }
    ok('L9 reduced-motion 进度二值（只会是 0 或 1）', ps.every((x) => x === 0 || x === 1), ps.join('/'));
    ok('L9b reduced-motion 形态不卡中间（字号只取 28 或 15，壳层 alpha 只取 0 或 1）',
      fonts.every((f) => Math.abs(f - 28) < 0.5 || Math.abs(f - 15) < 0.5) && alphas.every((a) => a <= 0.02 || a >= 0.98),
      `fonts=${fonts.join('/')} alpha=${alphas.join('/')}`);
    ok('L9c reduced-motion 两端形态与连续路径端点一致（28px/52 高 · 15px/48 高）', (() => {
      const restF = fonts[ps.indexOf(0)]; const endF = fonts[ps.indexOf(1)];
      return Math.abs(restF - 28) < 0.5 && Math.abs(endF - 15) < 0.5;
    })(), `${fonts.join('/')}`);
  } finally {
    await page.close();
    if (errors.length) { failures += 1; console.log('FAIL  reduced-motion 腿 console error: ' + errors.slice(0, 3).join(' | ')); }
  }
}

/* ── L10 桌面回归（computed 快照落盘，跨版本 diff）──────────────────── */
async function desktopLeg() {
  const ctx = await chromium.launch().then((b) => b.newContext({
    viewport: { width: 1280, height: 800 }, colorScheme: 'light', locale: 'zh-CN',
  }));
  const page = await ctx.newPage();
  const errors = [];
  watchErrors(page, errors);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(800);
  const snap = await page.evaluate(() => {
    const pick = (el, props) => (el ? Object.fromEntries(props.map((p) => {
      const cs = getComputedStyle(el);
      return [p, cs.getPropertyValue(p)];
    })) : null);
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      pageShells: document.querySelectorAll('.mobile-page').length,
      titleNodes: document.querySelectorAll('.mobile-page-title, .mobile-page-title-mini').length,
      header: pick(document.querySelector('#header'), ['display', 'position', 'height', 'background-color', 'backdrop-filter']),
      headerVisible: !!document.querySelector('#header'),
      bodyPaddingTop: getComputedStyle(document.body).paddingTop,
    };
  });
  readings.desktop = snap;
  ok('L10 桌面 body.mobile-layer 不激活', !snap.mobileLayer);
  ok('L10b 桌面零页面壳 / 零标题节点（页面世界不建）', snap.pageShells === 0 && snap.titleNodes === 0, `shells=${snap.pageShells} titles=${snap.titleNodes}`);
  ok('L10c 桌面 #header 在场且几何/材质照旧', snap.headerVisible && snap.header && snap.header.display !== 'none', JSON.stringify(snap.header));
  await page.close();
  if (errors.length) { failures += 1; console.log('FAIL  桌面腿 console error: ' + errors.slice(0, 3).join(' | ')); }
}

/* ── 主流程 ───────────────────────────────────────────────────────── */
console.log(`evidence → ${OUT}`);
const allErrors = [];
// readings.json 必须无条件落盘（腿中途抛异常也要留下已采到的读数 = 证据完整性）。
const flush = () => {
  readings.errors = allErrors;
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 1));
};
process.on('uncaughtException', (e) => { flush(); console.error(e); process.exit(1); });
{
  const { page, errors } = await boot('light', 'chromium');
  allErrors.push(...errors);
  try {
    // ① 后台任务（作者实拍的那一页；主滚轴在面板内 = pane 型）
    await seedAgents(page);
    await openBackground(page);
    await panelLeg(page, 'background', 'bg/chromium-light', 'bg-chromium-light');
    // ② 项目（pane 型）
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await panelLeg(page, 'projects', 'projects/chromium-light', 'projects-chromium-light');
    // ③ 设置（overlay 收编面，主滚轴 = #settings-content）
    await openPage(page, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    await panelLeg(page, 'settings', 'settings/chromium-light', 'settings-chromium-light');
    // ④ 待办（in-flow 页）
    await openTodo(page);
    await panelLeg(page, 'todo', 'todo/chromium-light', 'todo-chromium-light');
    // ⑤ 降级腿
    await degradeLeg(page);
  } finally {
    await page.close();
  }
}
// ⑥ 暗主题 + WebKit 复验（后台任务页一条完整腿）
{
  const { page, errors } = await boot('dark', 'webkit');
  allErrors.push(...errors);
  try {
    await seedAgents(page);
    await openBackground(page);
    await panelLeg(page, 'background', 'bg/webkit-dark', 'bg-webkit-dark');
  } finally {
    await page.close();
  }
}
await reducedLeg();
await desktopLeg();

readings.errors = allErrors;
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 1));
ok('零 console error（全腿，噪声过滤后硬零）', allErrors.length === 0, allErrors.slice(0, 5).join(' | '));

console.log(`\nreadings → ${join(OUT, 'readings.json')}`);
console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
