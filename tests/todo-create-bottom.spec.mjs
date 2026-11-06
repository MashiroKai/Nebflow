#!/usr/bin/env node
// todo-create-bottom.spec.mjs — 「新建恢复页底弹出」自证（create-bottom-restore 批 ·
// 作者裁定 2026-11-06 09:44，撤回批，覆盖任务书「新建从顶部」那条）。
//
// 作者原话（逐字）：「然后撤回一下 不管是定时任务还是待办 手机端的创建 还是从底部
// 弹出吧 方便一点」。
//
// 运行：node tests/todo-create-bottom.spec.mjs
//   CHATFACE_WEB_ROOT=<另一份 web 树> 可指向改前树（或上一批 918846c0f 的树）跑验红腿。
//
// 零后端法（与 tests/todo-mobile-topmenu.spec.mjs / tests/mobile-p3-reminder.spec.mjs 同源）：
//   · 静态面 = page.route 从磁盘注入本仓 web 树（零端口、零 sbt、不碰 :6138）；
//   · 数据面 = routeWebSocket 帧桩：待办走 todoStore.__seed 夹具，定时任务走
//     listScheduledTasks → scheduledTaskList 帧；
//   · 视口   = 375×812 / deviceScaleFactor 2 / hasTouch / isMobile（body.mobile-layer 激活）。
//
// 判据（逐条对应任务书五步 + 自证要求）：
//   T 待办页（真链 chatChrome [data-chrome='tasks'] → navController pushPage）
//     A1 点新建入口（右下加号）⇒ 抽屉贴页底（盒底 = 页壳底 ±0.5）
//     A2 三组读数（开前 / 开时 / 关后）：列表首条 rect 位移 ≤0.5px、dock 不变、
//        .tfl-list scrollTop/scrollHeight/clientHeight 不变、document.scrollingElement
//        scrollTop/scrollHeight + window.scrollY 恒定
//     A3 抽屉不撑高页面：开关前后页壳/页体/list-inner 的 scrollHeight 逐值不变
//     A4 动效单源：开合过渡串 = liquidMorph.morphTransition(transform+opacity, slow)
//        原文（400ms / 唯一曲线）；开时采样 transform 中间帧 = matrix 平移（位移只吃
//        transform，不是 top/height）；收尾 transform = none + opacity 1
//     A5 单实现：DOM 里恰好一个 .mf-drawer、无 .mf-drawer-top、无第二套新建表单源
//        （.tfl-list 内无 .tfl-create-row）
//     A6 ⋯ 仍在（.tfl-topmenu-btn 命中 1）且旧顶部控制仍然零命中
//     A7 关闭路径两条都在：遮罩点击 / 「取消」（后者实测）
//   G 标题框蓝边复核：焦点后 outlineWidth 0 / outlineStyle none / borderColor 透明
//     （指针点入 + 键盘 Tab 两腿），亮暗两主题各一次
//   S 定时任务页（工作台 → 定时任务）：同类断言 —— 抽屉贴页底、.mf-drawer-top 零命中、
//     开合过渡串同为液态形变 slow 档、开合零位移

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// 期望值不从 CSS/JS 抄数：直接取液态形变单源模块的导出（改一处两处同步）。
import { DURATION, EASING, morphTransition } from '../src/main/resources/web/js/motion/liquidMorph.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.CHATFACE_WEB_ROOT
  ? normalize(process.env.CHATFACE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'reports-evidence', 'todo-mobile-revamp__20261106', 'impl-create-bottom');
mkdirSync(OUT, { recursive: true });

const SID = 'todo-create-bottom-spec-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* ───────────── 夹具 ───────────── */
const TAGS = [
  { id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' },
  { id: 'tag-life', name: '生活', color: 'rgb(16,185,129)' },
];
function items() {
  const now = Date.now();
  return [
    { id: 'f-1', tagId: 'tag-work', title: '修登录态过期', notes: '', due: now + 3600_000, priority: 3, createdAt: now - 9 * 3600_000 },
    { id: 'f-2', tagId: 'tag-life', title: '缴房租', notes: '', due: now + 2 * 3600_000, priority: 2, createdAt: now - 8 * 3600_000 },
    { id: 'f-3', tagId: 'tag-work', title: '读设计中的设计', notes: '', due: null, priority: 1, createdAt: now - 7 * 3600_000 },
  ];
}
/** 定时任务夹具（与 mobile-p3-reminder 同形：4 行在列）。 */
function scheduledTasks() {
  const now = Date.now();
  return [
    { id: 't1', content: '每小时巡检构建', triggerAt: now + 3_600_000, repeat: 'hourly', enabled: true, triggered: false },
    { id: 't2', content: '每天晨报摘要', triggerAt: now + 86_400_000, repeat: 'daily', enabled: true, triggered: false },
    { id: 't3', content: '每周复盘提醒', triggerAt: now + 7 * 86_400_000, repeat: 'weekly', enabled: true, triggered: false },
    { id: 't4', content: '单次：回看第 3 节', triggerAt: now + 1_800_000, enabled: true, triggered: false },
  ];
}

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const near = (a, b, tol = 0.5) => a != null && b != null && Math.abs(a - b) <= tol;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/* ───────────── 零后端 wire ───────────── */
async function wire(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
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
  const tasks = scheduledTasks();
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    const sendTasks = () => ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'listScheduledTasks') sendTasks();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
}

async function boot(page) {
  await wire(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

/* ───────────── 读数脚本 ───────────── */
/* 三组读数：首条 rect / dock / 列表滚区 / 文档滚轴 / 各层 scrollHeight（撑高判据）。 */
const READ = (pageSel, listSel, rowSel) => `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
             top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2) }; };
  const page = document.querySelector(${JSON.stringify(pageSel)});
  const list = page && page.querySelector(${JSON.stringify(listSel)});
  const se = document.scrollingElement;
  const body = page && page.querySelector('.mobile-page-body');
  const inner = page && page.querySelector('.tfl-list-inner, .reminder-panel-body');
  const dock = document.querySelector('#mobile-dock');
  return {
    firstRow: R(page && page.querySelector(${JSON.stringify(rowSel)})),
    page: R(page),
    dock: R(dock),
    list: list ? { top: list.scrollTop, sh: list.scrollHeight, ch: list.clientHeight, st: R(list) } : null,
    doc: se ? { top: se.scrollTop, sh: se.scrollHeight, ch: se.clientHeight } : null,
    winY: window.scrollY || 0,
    heights: {
      body: body ? body.scrollHeight : null,
      bodyCh: body ? body.clientHeight : null,
      inner: inner ? inner.scrollHeight : null,
    },
  };
})()`;

const RECT_OF = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const b = el.getBoundingClientRect();
  return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
           top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2) };
})()`;

const VISIBLE = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return false;
  const cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden') return false;
  const b = el.getBoundingClientRect();
  return b.width > 0.5 && b.height > 0.5;
})()`;

const TRANSITION_OF = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const cs = getComputedStyle(el);
  return { props: cs.transitionProperty, dur: cs.transitionDuration, ease: cs.transitionTimingFunction,
           transform: cs.transform, opacity: cs.opacity };
})()`;

/* 期望串 = 单源模块生成的原文（不从 CSS 抄数）。 */
const WANT = morphTransition(['transform', 'opacity'], { duration: 'slow' });
const WANT_CSS = (() => {
  // getComputedStyle 口径：毫秒不带单位换算（400ms → "0.4s"）、曲线原样、逗号后带空格。
  const dur = `${DURATION.slow / 1000}s`;
  return { dur: `${dur}, ${dur}`, ease: `${EASING}, ${EASING}` };
})();

function transitionIsLiquid(t) {
  if (!t) return false;
  const props = t.props.split(',').map((s) => s.trim());
  return props.length === 2 && props.every((p) => p === 'transform' || p === 'opacity')
    && t.dur === WANT_CSS.dur && t.ease === WANT_CSS.ease;
}

/* 过渡中段的 transform 读数（开合动画「位移只吃 transform」的实证：取中间的
   matrix 平移 y ∈ (0, boxH)，且几何（w/h）不变 ⇒ 没有触发布局）。 */
/* ── 动画样本采集器（页内安装，先装后点）──────────────────────────────────
   rAF 每帧读抽屉盒的 computed transform 与几何；只留「纯平移矩阵 ty>0」的帧，
   并以**落位终态**（ty=0 的稳定帧）为基准校验截面（x / 宽）不变、y = 终态y − ty。
   —— 采样必须由页内 rAF 循环做：若从外面发 evaluate，采样本身占满 rAF 槽，
   400ms 的开合腿会被整段跳过（2026-11-06 实测：frames=0）。 */
const INSTALL_SAMPLER = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  window.__cbSamples = { frames: [], settledY: null, running: true };
  const tick = () => {
    if (!window.__cbSamples.running) return;
    if (!el) { requestAnimationFrame(tick); return; }
    const tf = getComputedStyle(el).transform;
    const m = /^matrix\\(1, 0, 0, 1, 0, (-?[0-9.]+)\\)$/.exec(tf);
    const b = el.getBoundingClientRect();
    if (m) {
      const ty = parseFloat(m[1]);
      if (Math.abs(ty) < 0.3) window.__cbSamples.settledY = +b.y.toFixed(2);
      else window.__cbSamples.frames.push({ ty: +ty.toFixed(2), x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2) });
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  return true;
})()`;

/* ───────────── 主流程 ───────────── */
const browser = await chromium.launch();
const errors = [];
const readings = { media: { DURATION, EASING, want: WANT } };

try {
  /* ══════ 待办页 375×812 · 亮主题 ══════ */
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await boot(page);
  await page.evaluate(async ({ sid, tags, its }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: its });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, its: items() });
  await page.waitForTimeout(200);

  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(800);
  await page.waitForSelector(".mobile-page[data-page='tasks'] .tfl-row", { timeout: 8000 });

  const PAGE = ".mobile-page[data-page='tasks']";
  const LIST = '.tfl-list';
  const ROW = '.tfl-row';
  const DRAWER = `${PAGE} .mf-drawer`;
  const BOX = `${DRAWER} .mf-drawer-box`;

  /* ── A5 / A6 静态形态：单实现 + ⋯ 仍在 ── */
  const staticShape = {
    drawers: await page.locator(DRAWER).count(),
    drawerTop: await page.locator('.mf-drawer-top').count(),
    formInList: await page.locator(`${PAGE} .tfl-list .tfl-create-row`).count(),
    topBtn: await page.locator(`${PAGE} .tfl-topmenu-btn`).count(),
    oldCtrl: {
      filter: await page.locator(`${PAGE} .tfl-filter-toggle`).count(),
      collapse: await page.locator(`${PAGE} .tfl-collapse-btn`).count(),
      chips: await page.locator(`${PAGE} .tfl-chips-row`).count(),
      prio: await page.locator(`${PAGE} .tfl-prio-row`).count(),
    },
    fab: await page.locator(`${PAGE} .tfl-fab-add`).count(),
  };
  ok('A5 单实现：恰好一个 .mf-drawer / .mf-drawer-top 零残留 / 列表流内无第二套创建行',
    staticShape.drawers === 1 && staticShape.drawerTop === 0 && staticShape.formInList === 0,
    JSON.stringify(staticShape));
  ok('A6 ⋯ 钮仍在（顶部菜单未受影响）且旧顶部控制仍零命中',
    staticShape.topBtn === 1 && Object.values(staticShape.oldCtrl).every((n) => n === 0),
    JSON.stringify({ topBtn: staticShape.topBtn, oldCtrl: staticShape.oldCtrl }));

  /* ── 开前读数 ── */
  const before = await page.evaluate(READ(PAGE, LIST, ROW));

  /* 点新建入口（右下加号 = 手机页面唯一新建入口）；先装采集器再点。 */
  await page.evaluate(INSTALL_SAMPLER(`${PAGE} .mf-drawer-box`));
  await page.locator(`${PAGE} .tfl-fab-add`).click();
  await page.waitForSelector(`${DRAWER}:not([hidden])`, { timeout: 5000 });
  await page.waitForTimeout(700); // 400ms slow 档 + 60ms 聚焦延时
  // 开合动画中段采样（位移只吃 transform）
  const midFrames = await page.evaluate(() => { window.__cbSamples.running = false; return window.__cbSamples; });

  const openState = await page.evaluate(VISIBLE(DRAWER));
  const during = await page.evaluate(READ(PAGE, LIST, ROW));
  const boxOpen = await page.evaluate(RECT_OF(BOX));
  const pageRect = await page.evaluate(RECT_OF(PAGE));
  const trOpen = await page.evaluate(TRANSITION_OF(BOX));

  ok('A0 点右下加号 ⇒ 底部抽屉开（表单在抽屉体内）',
    openState === true
    && (await page.locator(`${DRAWER} .mf-drawer-body .tfl-create-row`).count()) === 1,
    `visible=${openState}`);

  ok('A1 抽屉贴页底（盒底 = 页壳底 ≤0.5px；盒顶在页壳内）',
    near(boxOpen?.bottom, pageRect?.bottom) && (boxOpen?.top ?? 0) > (pageRect?.top ?? 0),
    `box=${JSON.stringify(boxOpen)} page=${JSON.stringify(pageRect)}`);

  ok('A2a 列表首条零位移（开前 → 开时，≤0.5px）',
    near(before.firstRow?.x, during.firstRow?.x) && near(before.firstRow?.y, during.firstRow?.y)
    && near(before.firstRow?.w, during.firstRow?.w) && near(before.firstRow?.h, during.firstRow?.h),
    `before=${JSON.stringify(before.firstRow)} open=${JSON.stringify(during.firstRow)}`);

  ok('A2b dock（#mobile-dock）位置不变',
    same(before.dock, during.dock), `${JSON.stringify(before.dock)} → ${JSON.stringify(during.dock)}`);

  ok('A2c 列表 scrollTop/scrollHeight/clientHeight 不变',
    before.list && during.list && before.list.top === during.list.top
    && before.list.sh === during.list.sh && before.list.ch === during.list.ch,
    `${JSON.stringify(before.list)} → ${JSON.stringify(during.list)}`);

  ok('A2d document.scrollingElement scrollTop/scrollHeight 恒定 + window.scrollY 0',
    before.doc && during.doc && before.doc.top === during.doc.top && before.doc.sh === during.doc.sh
    && before.winY === during.winY,
    `${JSON.stringify(before.doc)} → ${JSON.stringify(during.doc)} / winY ${before.winY}→${during.winY}`);

  ok('A3 抽屉不撑高页面（页体与列表内层 scrollHeight 逐值不变）',
    same(before.heights, during.heights), `${JSON.stringify(before.heights)} → ${JSON.stringify(during.heights)}`);

  ok('A4a 开合过渡串 = 液态形变单源（morphTransition(transform+opacity, slow) 原文）',
    transitionIsLiquid(trOpen) && WANT.includes(`${DURATION.slow}ms`) && WANT.includes(EASING),
    JSON.stringify({ got: trOpen, want: WANT }));

  const allFrames = (midFrames && midFrames.frames) || [];
  const settledY = midFrames ? midFrames.settledY : null;
  // 只取「落位终态之后」的平移段：首个 ty<0.3 的稳定帧之后（此前 = 内容刚挂进 DOM
  // 的一次性布局落位，非位移腿）。
  let anchorIdx = allFrames.findIndex((f) => f.ty < 0.3);
  if (anchorIdx < 0) anchorIdx = allFrames.findIndex((f) => f.ty < 2);
  const midMoved = anchorIdx >= 0 ? allFrames.slice(anchorIdx) : [];
  // 位移由 transform 承担：该段盒**截面**（x / 宽）逐帧 = 终态，且 y 与 ty 咬合
  // （y = 终态 y − ty）⇒ 位移不走 top/height/margin（那些会触发布局）。
  const shapeStable = midMoved.length >= 3 && settledY != null
    && midMoved.every((f) => near(f.x, boxOpen?.x, 0.6) && near(f.w, boxOpen?.w, 0.6)
      && (f.ty < 2 || near(f.y, settledY - f.ty, 1.5)));
  ok('A4b 开合中位移只吃 transform（中间帧 = 纯平移矩阵 ty>0，盒 x/宽 = 终态，y = 终态y − ty）',
    shapeStable,
    `frames=${allFrames.length} (settled-leg ${midMoved.length}) settledY=${settledY} sections=${[...new Set(midMoved.map((f) => `${f.x}/${f.w}`))].join(' ')} ty=${midMoved.map((f) => f.ty).join('→')} yErr=${Math.max(0, ...midMoved.filter((f) => f.ty >= 2).map((f) => Math.abs(f.y - (settledY - f.ty)))).toFixed(2)}`);

  ok('A4c 落位终态：transform=none / opacity=1',
    trOpen && (trOpen.transform === 'none' || trOpen.transform === 'matrix(1, 0, 0, 1, 0, 0)')
    && trOpen.opacity === '1',
    JSON.stringify({ tf: trOpen?.transform, op: trOpen?.opacity }));

  /* ── G 蓝边复核（指针点入 + 键盘 Tab；亮主题）── */
  const titleSel = `${DRAWER} .tfl-row-title-input`;
  await page.locator(titleSel).click();
  await page.waitForTimeout(200);
  const focusPointer = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      active: document.activeElement === el,
      modality: document.documentElement.getAttribute('data-nf-modality'),
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor,
      borderWidths: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth],
      borderColors: [cs.borderTopColor, cs.borderRightColor, cs.borderBottomColor, cs.borderLeftColor],
      boxShadow: cs.boxShadow,
    };
  }, titleSel);
  // 判据口径（不靠「恰好是 0px」这种易被引擎归一化骗过去的读数）：
  //   · 蓝边 = 作者样式画出的 outline ⇒ `outlineStyle` 只能是 `none`（无）或 `auto`
  //     （浏览器 UA 默认态、作者未写任何 outline）；出现 solid/dashed/… = 有人画环 = 红。
  //   · 边界 = 四条长写边色（`borderColor` 是**首边别名**，在 border-style:none 的
  //     输入件上读到的是 UA 的 currentColor，不是作者值 ⇒ 不作判据）。
  const noRing = (s) => !!s && (s.outlineStyle === 'none' || s.outlineStyle === 'auto');
  const noVisibleBorder = (s) => !!s && s.borderWidths.every((w) => parseFloat(w) === 0 || /rgba\(0, 0, 0, 0\)|transparent/.test(s.borderColors[0]));
  const blueish = (s) => !!s && /rgb\((\d+), (\d+), (\d+)\)/.test(s.outlineColor || '')
    && (() => { const m = /rgb\((\d+), (\d+), (\d+)\)/.exec(s.outlineColor); return +m[3] > +m[1] + 40 && +m[3] > +m[2] + 20; })();
  ok('G0 读数口径校验：UA 默认焦点环 ≠ 蓝（蓝 = 作者环；本面 outlineStyle 只能 none/auto）',
    focusPointer ? !(focusPointer.outlineStyle === 'auto' && blueish(focusPointer)) : false,
    JSON.stringify({ style: focusPointer?.outlineStyle, color: focusPointer?.outlineColor }));
  ok('G1 标题框蓝边 0 条（指针点入：作者 outline = none / 四边宽 0 / 非蓝）',
    noRing(focusPointer) && noVisibleBorder(focusPointer) && !blueish(focusPointer) && focusPointer.active,
    JSON.stringify(focusPointer));

  // 键盘腿：先 blur，再用 Tab 进入（focusModality 把模态切到 keyboard）
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await sleep(80);
  await page.keyboard.press('Tab');
  await sleep(120);
  const focusKeyboard = await page.evaluate(() => {
    const el = document.querySelector('.mf-drawer .tfl-row-title-input');
    const cs = el ? getComputedStyle(el) : null;
    return cs ? {
      active: document.activeElement === el,
      modality: document.documentElement.getAttribute('data-nf-modality'),
      tag: document.activeElement ? document.activeElement.className : null,
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor,
      borderWidths: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth],
      borderColors: [cs.borderTopColor, cs.borderRightColor, cs.borderBottomColor, cs.borderLeftColor],
    } : null;
  });
  ok('G2 键盘 Tab 腿：环来自 inputSurface 令牌体系（≤2px 内缩压灰环）或零环 —— 不是旧「整圈蓝框」',
    !!focusKeyboard && (
      (focusKeyboard.active && parseFloat(focusKeyboard.outlineWidth) <= 2)
      || (!focusKeyboard.active && noRing(focusKeyboard))
    ) && !(focusKeyboard?.active && blueish(focusKeyboard) && parseFloat(focusKeyboard.outlineWidth) > 2),
    JSON.stringify(focusKeyboard));

  /* ── A7 关闭（「取消」键）→ 关后读数 ── */
  const trBeforeClose = await page.evaluate(TRANSITION_OF(BOX));
  await page.locator(`${DRAWER} .mf-drawer-cancel-btn`).click();
  await page.waitForTimeout(700);
  const after = await page.evaluate(READ(PAGE, LIST, ROW));
  const closed = await page.evaluate(VISIBLE(DRAWER));
  ok('A7 「取消」键 ⇒ 抽屉收起（hidden）', closed === false, `visible=${closed}`);

  ok('A2e 列表首条关后仍在原位（开前 → 关后，≤0.5px）',
    near(before.firstRow?.x, after.firstRow?.x) && near(before.firstRow?.y, after.firstRow?.y)
    && near(before.firstRow?.w, after.firstRow?.w) && near(before.firstRow?.h, after.firstRow?.h),
    `before=${JSON.stringify(before.firstRow)} closed=${JSON.stringify(after.firstRow)}`);

  ok('A2f dock / 列表滚区 / 文档滚轴关后与开前逐值相同',
    same(before.dock, after.dock) && same(before.list, after.list) && same(before.doc, after.doc)
    && before.winY === after.winY && same(before.heights, after.heights),
    `dock ${JSON.stringify(after.dock)} list ${JSON.stringify(after.list)} doc ${JSON.stringify(after.doc)} h ${JSON.stringify(after.heights)}`);

  /* ── A7' 遮罩关闭（第二条关闭路径）── */
  await page.locator(`${PAGE} .tfl-fab-add`).click();
  await page.waitForSelector(`${DRAWER}:not([hidden])`, { timeout: 5000 });
  await sleep(600);
  await page.locator(`${DRAWER} .mf-drawer-scrim`).click({ position: { x: 8, y: 8 } });
  await sleep(700);
  ok('A7b 遮罩点击 ⇒ 抽屉收起（两条关闭路径都在）',
    (await page.evaluate(VISIBLE(DRAWER))) === false);

  /* 截图（证据面）：打开态 */
  await page.locator(`${PAGE} .tfl-fab-add`).click();
  await page.waitForSelector(`${DRAWER}:not([hidden])`, { timeout: 5000 });
  await sleep(700);
  await page.screenshot({ path: join(OUT, 'create-bottom-todo.png') });
  const geoShot = await page.evaluate(RECT_OF(BOX));
  await page.locator(`${DRAWER} .mf-drawer-cancel-btn`).click();
  await sleep(600);

  readings.todo = {
    before, during, after, boxOpen, pageRect, trOpen: trBeforeClose, midFrames,
    focusPointer, focusKeyboard, staticShape, geoShot,
  };

  /* ── 暗主题复核 G1（亮暗两档蓝边都必须是 0）── */
  await page.emulateMedia({ colorScheme: 'dark' });
  await sleep(150);
  await page.locator(`${PAGE} .tfl-fab-add`).click();
  await page.waitForSelector(`${DRAWER}:not([hidden])`, { timeout: 5000 });
  await sleep(600);
  await page.locator(titleSel).click();
  await sleep(200);
  const focusDark = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor,
      borderWidths: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth],
      borderColors: [cs.borderTopColor, cs.borderRightColor, cs.borderBottomColor, cs.borderLeftColor],
    };
  }, titleSel);
  ok('G3 暗主题：标题框蓝边同为 0 条',
    noRing(focusDark) && noVisibleBorder(focusDark) && !blueish(focusDark), JSON.stringify(focusDark));
  readings.todo.focusDark = focusDark;
  await page.locator(`${DRAWER} .mf-drawer-cancel-btn`).click();
  await sleep(600);
  await page.emulateMedia({ colorScheme: 'light' });

  await ctx.close();

  /* ══════ 定时任务页 375×812（同类断言）══════ */
  const sctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const sp = await sctx.newPage();
  sp.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await boot(sp);
  await sp.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await sp.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="workbench"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await sp.waitForTimeout(300);
  await sp.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="reminder"]').click();
  await sp.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="reminder"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await sp.waitForTimeout(600);

  const SPAGE = ".mobile-page[data-page='reminder']";
  // 抽屉挂在页面壳（.mobile-page）上 —— 与待办页同包含块；页面体内只剩加号。
  const SDRAWER = '.mf-drawer';
  const SBOX = `${SDRAWER} .mf-drawer-box`;
  const SLIST = '.reminder-panel-body';
  const SROW = '.reminder-row';
  await sp.waitForSelector(`${SPAGE} ${SROW}`, { timeout: 8000 });

  const sBefore = await sp.evaluate(READ(SPAGE, SLIST, SROW));
  await sp.locator(`${SPAGE} .reminder-fab-add`).click();
  await sp.waitForTimeout(900);
  const sOpen = await sp.evaluate(VISIBLE('.mf-drawer'));
  const sDiag = await sp.evaluate(() => ({
    drawers: document.querySelectorAll('.mf-drawer').length,
    hosts: [...document.querySelectorAll('.mf-drawer')].map((d) => ({
      hidden: !!d.hidden,
      parentId: d.parentElement ? d.parentElement.id || d.parentElement.className : null,
      isPageShell: !!(d.parentElement && d.parentElement.classList.contains('mobile-page')),
    })),
  }));
  ok('S0 定时任务页点加号 ⇒ 抽屉开（可见），且挂在页面壳上（与待办页同一包含块）',
    sOpen === true && sDiag.drawers === 1 && sDiag.hosts.every((h) => h.isPageShell),
    JSON.stringify(sDiag));
  const sDuring = await sp.evaluate(READ(SPAGE, SLIST, SROW));
  const sBox = await sp.evaluate(RECT_OF(SBOX));
  const sPage = await sp.evaluate(RECT_OF(SPAGE));
  const sTr = await sp.evaluate(TRANSITION_OF(SBOX));
  const sShape = {
    drawers: await sp.locator(SDRAWER).count(),
    drawerTop: await sp.locator('.mf-drawer-top').count(),
    body: await sp.locator(`${SDRAWER} .mf-drawer-body .reminder-inline-input, ${SDRAWER} .mf-drawer-body #reminder-inline-input`).count(),
    inlineRows: await sp.locator(`${SDRAWER} .mf-drawer-body .reminder-inline-create, ${SDRAWER} .mf-drawer-body input, ${SDRAWER} .mf-drawer-body textarea`).count(),
    hidden: await sp.locator(SDRAWER).evaluate((el) => el.hidden),
  };

  ok('S1 定时任务页：新建抽屉贴页底（盒底 = 页壳底 ≤0.5px）且表单在抽屉体内',
    near(sBox?.bottom, sPage?.bottom) && sShape.body + sShape.inlineRows >= 1,
    `box=${JSON.stringify(sBox)} page=${JSON.stringify(sPage)} shape=${JSON.stringify(sShape)}`);
  ok('S2 定时任务页：.mf-drawer-top 零残留（与待办页同一套底抽屉）',
    sShape.drawerTop === 0 && sShape.drawers === 1, JSON.stringify(sShape));
  ok('S3 定时任务页：开合过渡串同为液态形变 slow 档（transform+opacity / 唯一曲线）',
    transitionIsLiquid(sTr), JSON.stringify({ got: sTr, want: WANT }));
  ok('S4 定时任务页：开合零位移（首行 rect / dock / 列表滚区 / 文档滚轴 / 各层 scrollHeight 不变）',
    near(sBefore.firstRow?.x, sDuring.firstRow?.x) && near(sBefore.firstRow?.y, sDuring.firstRow?.y)
    && same(sBefore.dock, sDuring.dock) && same(sBefore.list, sDuring.list) && same(sBefore.doc, sDuring.doc)
    && same(sBefore.heights, sDuring.heights),
    `row ${JSON.stringify(sBefore.firstRow)}→${JSON.stringify(sDuring.firstRow)} / list ${JSON.stringify(sBefore.list)}→${JSON.stringify(sDuring.list)} / h ${JSON.stringify(sBefore.heights)}→${JSON.stringify(sDuring.heights)}`);

  await sp.screenshot({ path: join(OUT, 'create-bottom-scheduled.png') });
  await sp.locator(`${SDRAWER} .mf-drawer-cancel-btn`).click();
  await sp.waitForTimeout(700);
  const sAfter = await sp.evaluate(READ(SPAGE, SLIST, SROW));
  ok('S5 定时任务页：关后首行 / dock 仍原位、抽屉 hidden',
    near(sBefore.firstRow?.y, sAfter.firstRow?.y) && same(sBefore.dock, sAfter.dock)
    && (await sp.evaluate(VISIBLE(SDRAWER))) === false,
    `row ${JSON.stringify(sAfter.firstRow)} dock ${JSON.stringify(sAfter.dock)}`);
  readings.scheduled = { before: sBefore, during: sDuring, after: sAfter, box: sBox, page: sPage, tr: sTr, shape: sShape };

  await sctx.close();
} catch (e) {
  fail++;
  const line = `FAIL  执行异常 — ${e.message}`;
  console.log(line);
  lines.push(line);
} finally {
  await browser.close();
}

if (errors.length) {
  console.log('pageerror：');
  for (const e of errors) console.log('  ' + e);
}

const summary = `${pass} passed, ${fail} failed`;
console.log(`\n${fail === 0 ? 'PASS' : 'FAIL'}  ${summary}`);
lines.push(`${fail === 0 ? 'PASS' : 'FAIL'}  ${summary}`);
writeFileSync(join(OUT, 'spec-output.txt'), lines.join('\n') + '\n');
writeFileSync(join(OUT, 'spec-readings.json'), JSON.stringify(readings, null, 2));
process.exit(fail === 0 ? 0 : 1);
