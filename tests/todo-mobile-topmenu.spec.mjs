#!/usr/bin/env node
// RETIRED: this test targets the removed .tfl-* mobile face. Its active-route
// replacement is tests/todo-create-bottom.spec.mjs; that test currently needs
// updating for the current create sheet and must not be counted as passing.
// todo-mobile-topmenu.spec.mjs — 手机端待办页「顶部⋯两级菜单 + 新建从顶部」实施项
// ① / ② 的自足验收（todo-mobile-revamp 批 · 2026-11-06）。
//
// 运行：node tests/todo-mobile-topmenu.spec.mjs
//   CHATFACE_WEB_ROOT=<另一份 web 树> 可指向改前树跑「验红」腿（默认本仓真树）。
//
// 零后端法（与 tests/_todo-face-harness.mjs / reports-evidence/…/capture-before.mjs 同源口径）：
//   · 静态面 = page.route 从磁盘注入本仓 web 树（零本地端口、零 sbt、不碰 :6138）；
//   · 数据面 = routeWebSocket 帧桩 + todoStore.__seed 夹具（纯本地，不发写帧）；
//   · 视口   = 375×812 / deviceScaleFactor 2 / hasTouch / isMobile（body.mobile-layer 激活）；
//   · 入口   = 真链 chatChrome [data-chrome='tasks'] → navController pushPage('tasks')。
//
// 判据（逐条对应任务书验收）：
//   A ① 零位移（三组读数）：新建开合前后 — 列表首条 rect 位移 ≤0.5px、dock 位置不变、
//      document.scrollingElement 与 .tfl-list 的 scrollTop/scrollHeight 逐一不变。
//      ★ create-bottom-restore 批（作者裁定 2026-11-06 09:44）：新建承载由本批原先的
//      「页顶」改回**页底**，故 A0（菜单首行仍是新建入口）保留、A5（顶部落位）改写为
//      「页面壳内无 .mf-drawer-top」「表单落在页底」，选择器去掉 .mf-drawer-top 段。
//      底腿的独立自证 = tests/todo-create-bottom.spec.mjs（本文件只守菜单与零位移）。
//   B ② 顶部唯一入口：.tfl-topmenu-btn 在场且 48 视觉 / ≥48 命中盒；
//      .tfl-filter-toggle / .tfl-collapse-btn / .tfl-chips-row / .tfl-prio-row
//      在手机页 DOM 里**零命中**（旧入口真退役，不并存）。
//   C ③ 一级菜单打开：毛玻璃面板（.nf-glass）、行 ≥4、危险项红字（--color-danger）、
//      过渡串 = js/motion/liquidMorph.js 单源（280ms / cubic-bezier(0.4,0,0.2,1) /
//      只含 transform+opacity）。
//   D ③ 二级面板叠显：点「标签」⇒ .tfl-menu-sub 可见 + 一级面板仍可见且变暗
//      （.is-back，opacity < 1 且两块面板矩形相交）。
//   E ③ 选中打勾回写：点某标签 ⇒ store.activeTagId 回写 + 整菜单收起 +
//      重开后该行 ✓ 命中；重要度同理（多选位写回）。
//   F ④ 点外部收起：幕布点击 ⇒ 菜单收起。
//   G 表单标题框蓝边退役：标题输入聚焦后 outline 宽度 0 / style none、边界无色。
//   H 桌面腿零改动：1440×900 下旧控件仍在（.tfl-filter-toggle 命中）、无 ⋯ 钮。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// 判据 C 的期望值**不从 CSS 抄数**：直接取液态形变单源模块的导出（改一处两处同步）。
import { DURATION, EASING } from '../src/main/resources/web/js/motion/liquidMorph.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.CHATFACE_WEB_ROOT
  ? normalize(process.env.CHATFACE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'reports-evidence', 'todo-mobile-revamp__20261106', 'impl-top-menu');
mkdirSync(OUT, { recursive: true });

const SID = 'todo-topmenu-spec-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* ───────────── 夹具（两枚标签 / 三行；首条 = 零位移读数对象）───────────── */
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

/* ───────────── 启动（零后端）───────────── */
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

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
}

async function seed(page) {
  await page.evaluate(async ({ sid, tags, its }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: its });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, its: items() });
}

const RECTS = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; };
  const page = document.querySelector('.mobile-page[data-page="tasks"]');
  const list = page && page.querySelector('.tfl-list');
  const se = document.scrollingElement;
  return {
    firstRow: R(page && page.querySelector('.tfl-row')),
    dock: R(document.querySelector('#mobile-dock')),
    page: R(page),
    list: list ? { top: list.scrollTop, sh: list.scrollHeight, ch: list.clientHeight } : null,
    doc: se ? { top: se.scrollTop, sh: se.scrollHeight } : null,
    winY: window.scrollY || 0,
  };
})()`;

const rectOf = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const b = el.getBoundingClientRect();
  return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
           right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) };
})()`;

const visible = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return false;
  const cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden') return false;
  const b = el.getBoundingClientRect();
  return b.width > 0.5 && b.height > 0.5;
})()`;

const near = (a, b, tol = 0.5) => a != null && b != null && Math.abs(a - b) <= tol;
/** 蓝主导判据（G1 专用）：css 颜色串里蓝通道显著压过红/绿 ⇒ 蓝（旧 sapphire 焦点
 *  环必命中；中性 ink color(srgb 0.106 0.118 0.149)/26% ≈ rgb(27,30,38) 不命中）。
 *  支持 rgb()/rgba()（0-255）与 color(srgb …)（0-1 浮点）两族序列化；其余 ⇒ 非蓝。 */
const isBlueish = (cssColor) => {
  if (!cssColor || /transparent|none/.test(cssColor)) return false;
  let r; let g; let b;
  const srgb = cssColor.match(/color\(\s*srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)/);
  if (srgb) { r = srgb[1] * 255; g = srgb[2] * 255; b = srgb[3] * 255; }
  else {
    const m = cssColor.match(/rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/);
    if (!m) return false;
    [r, g, b] = [Number(m[1]), Number(m[2]), Number(m[3])];
  }
  return b > r + 60 && b > g + 40;
};

/* 可点元素读数（与 reports-evidence/…/shots-before/capture-before.mjs 同一口径：
   元素盒 ∪ 吃指针的绝对定位伪元素外扩；供 scripts/check-mobile-tap.mjs --readings
   跑「改后腿」—— 默认读数仍是改前快照，本 spec 不覆盖它）。 */
const COLLECT = `(() => {
  const root = document.querySelector('.mobile-page[data-page="tasks"]');
  if (!root) return { count: 0, items: [] };
  const shown = (el) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    const b = el.getBoundingClientRect();
    return b.width > 0.5 && b.height > 0.5;
  };
  const pseudoHit = (el, rect) => {
    const res = [];
    for (const p of ['::after', '::before']) {
      const cs = getComputedStyle(el, p);
      if (!cs) continue;
      if (!cs.content || cs.content === 'none' || cs.content === 'normal') continue;
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (cs.pointerEvents === 'none') continue;
      if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
      const px = (v) => (typeof v === 'string' && v.endsWith('px') ? parseFloat(v) : v === 'auto' ? 0 : null);
      const L = px(cs.left), R = px(cs.right), T = px(cs.top), B = px(cs.bottom);
      if (L === null || R === null || T === null || B === null) { res.push({ w: null, h: null }); continue; }
      res.push({ w: +(rect.width - L - R).toFixed(2), h: +(rect.height - T - B).toFixed(2) });
    }
    return res;
  };
  const SEL = 'button, a, [role="button"], input, select, textarea, '
    + '[tabindex]:not([tabindex="-1"]), .tfl-row, .tfl-fab-add, .tfl-topmenu-btn, .tfl-menu-row';
  const seen = new Set();
  const out = [];
  for (const el of root.querySelectorAll(SEL)) {
    if (seen.has(el)) continue;
    seen.add(el);
    if (!shown(el)) continue;
    const b = el.getBoundingClientRect();
    const rect = { width: +b.width.toFixed(2), height: +b.height.toFixed(2) };
    let hitW = rect.width, hitH = rect.height;
    for (const q of pseudoHit(el, rect)) {
      if (q.w === null) { hitW = null; hitH = null; break; }
      hitW = Math.max(hitW, q.w); hitH = Math.max(hitH, q.h);
    }
    const id = el.id ? '#' + el.id : '';
    const cls = typeof el.className === 'string' && el.className.trim()
      ? '.' + el.className.trim().split(/\\s+/).join('.') : '';
    out.push({
      selector: (el.tagName.toLowerCase() + id + cls),
      w: rect.width, h: rect.height,
      hitW: hitW === null ? null : +hitW.toFixed(2),
      hitH: hitH === null ? null : +hitH.toFixed(2),
    });
  }
  return { count: out.length, items: out };
})()`;

/* ───────────── 主流程 ───────────── */
const browser = await chromium.launch();
const errors = [];
const media = { DURATION, EASING };

try {
  /* ══════ 手机腿 375×812 ══════ */
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));

  await wire(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  await seed(page);
  await page.waitForTimeout(200);

  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(800);
  await page.waitForSelector(".mobile-page[data-page='tasks'] .tfl-row", { timeout: 8000 });

  /* ── B ② 顶部唯一入口 + 旧控制退役 ─────────────────────────────────── */
  const btnRect = await page.evaluate(rectOf(".mobile-page[data-page='tasks'] .tfl-topmenu-btn"));
  const btnVisible = await page.evaluate(visible(".mobile-page[data-page='tasks'] .tfl-topmenu-btn"));
  ok('B1 ⋯ 钮存在且可见', btnVisible && btnRect != null, JSON.stringify(btnRect));
  ok('B2 ⋯ 钮 = 48×48 视觉档（--ds-hit-tap-min）', near(btnRect?.w, 48) && near(btnRect?.h, 48),
    `w=${btnRect?.w} h=${btnRect?.h}`);
  ok('B3 ⋯ 钮命中盒 ≥ 48（--ds-hit-box-min）', (btnRect?.w ?? 0) >= 48 && (btnRect?.h ?? 0) >= 48,
    `w=${btnRect?.w} h=${btnRect?.h}`);
  const oldSel = ['.tfl-filter-toggle', '.tfl-collapse-btn', '.tfl-chips-row', '.tfl-prio-row'];
  const oldCounts = {};
  for (const s of oldSel) {
    oldCounts[s] = await page.locator(`.mobile-page[data-page='tasks'] ${s}`).count();
  }
  ok('B4 旧顶部控制零命中（旧入口真退役，不并存）',
    Object.values(oldCounts).every((n) => n === 0), JSON.stringify(oldCounts));

  /* ── A ① 新建从顶部、零内容位移 ─────────────────────────────────────── */
  const before = await page.evaluate(RECTS);
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu:not([hidden]) .tfl-menu-panel.is-open", { timeout: 5000 });
  await page.waitForTimeout(350);

  // 菜单首行 = 新建待办（页顶入口）
  const firstMenuRow = page.locator(".tfl-menu-panel .tfl-menu-row").first();
  const firstRowText = (await firstMenuRow.innerText()).trim();
  ok('A0 一级菜单首行 = 新建待办（入口仍在菜单里，承载已按撤回批回页底）', firstRowText.includes('新建待办'), firstRowText);
  await firstMenuRow.click();
  await page.waitForSelector(".mobile-page[data-page='tasks'] .mf-drawer:not([hidden])", { timeout: 5000 });
  await page.waitForTimeout(700); // 400ms 大位移腿 + 聚焦延时 60ms
  const after = await page.evaluate(RECTS);

  ok('A1 首条元素零位移（≤0.5px）',
    near(before.firstRow?.x, after.firstRow?.x) && near(before.firstRow?.y, after.firstRow?.y)
    && near(before.firstRow?.w, after.firstRow?.w) && near(before.firstRow?.h, after.firstRow?.h),
    `before=${JSON.stringify(before.firstRow)} after=${JSON.stringify(after.firstRow)}`);
  ok('A2 dock 位置不变（#mobile-dock = 底栏；[data-chrome] 顶缘键同页）',
    near(before.dock?.x, after.dock?.x) && near(before.dock?.y, after.dock?.y)
    && near(before.dock?.w, after.dock?.w) && near(before.dock?.h, after.dock?.h),
    `${JSON.stringify(before.dock)} → ${JSON.stringify(after.dock)}`);
  ok('A3 列表滚区 scrollTop/scrollHeight/clientHeight 不变',
    before.list && after.list && before.list.top === after.list.top
    && before.list.sh === after.list.sh && before.list.ch === after.list.ch,
    `${JSON.stringify(before.list)} → ${JSON.stringify(after.list)}`);
  ok('A4 文档滚轴 scrollTop/scrollHeight 不变（无 body 级滚动跳动）',
    before.doc && after.doc && before.doc.top === after.doc.top && before.doc.sh === after.doc.sh
    && before.winY === after.winY,
    `${JSON.stringify(before.doc)} → ${JSON.stringify(after.doc)} / winY ${before.winY}→${after.winY}`);

  const sheetTop = await page.evaluate(rectOf(".mobile-page[data-page='tasks'] .mf-drawer .mf-drawer-box"));
  const pageTop = await page.evaluate(rectOf(".mobile-page[data-page='tasks']"));
  ok('A5 表单落在页面底缘（底承载；.mf-drawer-top 修饰类零残留）',
    sheetTop != null && pageTop != null && pageTop.bottom - sheetTop.bottom <= 8
    && (await page.locator('.mf-drawer-top').count()) === 0,
    `box.bottom=${sheetTop?.bottom} page.bottom=${pageTop?.bottom} top-style=${await page.locator('.mf-drawer-top').count()}`);

  /* ── G 标题框蓝边退役 ────────────────────────────────────────────────── */
  const titleSel = ".mobile-page[data-page='tasks'] .mf-drawer .tfl-row-title-input";
  await page.locator(titleSel).click();
  await page.waitForTimeout(250);
  const focusState = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      isActive: document.activeElement === el,
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor,
      borderColor: cs.borderColor, borderWidth: cs.borderWidth,
    };
  }, titleSel);
  const noBlue = focusState
    && (focusState.outlineStyle === 'none' || parseFloat(focusState.outlineWidth) === 0)
    && !isBlueish(focusState.outlineColor) && !isBlueish(focusState.borderColor);
  // ★ G1 判据改写（input-surface-unify 批语义重写，2026-11-06）：旧断言要求
  //   borderColor transparent —— 那是 918846c0f 批在 todo 面上覆盖出来的旧形态。
  //   main 的 inputSurface.css（inputFocus.css 已整文件退役）统一焦点语言 =
  //   指针零环 + 边界「中性 ink 26%」（非蓝）—— create-bottom-restore 批撤掉
  //   .mf-drawer-top 修饰类后，标题件回到 inputSurface 裸件表/标准档，聚焦边界
  //   = 中性 ink（非蓝）是**现行 sanctioned 形态**。判据回到语义本身：「无蓝」
  //   = outline 不画 + outline/border 都非蓝主导（蓝主导判据 b>r+60 && b>g+40，
  //   中性 ink rgb(27,30,38)/26% 不命中；旧 sapphire 蓝必命中）。
  ok('G1 标题框聚焦无蓝边（outline 退役 + outline/border 均非蓝主导）', !!focusState?.isActive && noBlue,
    JSON.stringify(focusState));

  /* 关闭表单（取消键 = 单一写口之一） */
  await page.locator(".mobile-page[data-page='tasks'] .mf-drawer .mf-drawer-cancel-btn").click();
  await page.waitForTimeout(600);
  const closed = await page.evaluate(visible(".mobile-page[data-page='tasks'] .mf-drawer"));
  ok('A6 取消后表单收起', closed === false);

  /* ── C ③ 一级菜单（毛玻璃 / 行 / 危险项 / 动效单源）─────────────────── */
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(350);

  const panelInfo = await page.evaluate(() => {
    const p = document.querySelector('.tfl-menu-panel');
    if (!p) return null;
    const cs = getComputedStyle(p);
    const b = p.getBoundingClientRect();
    return {
      glass: p.classList.contains('nf-glass'),
      rows: p.querySelectorAll('.tfl-menu-row').length,
      radius: cs.borderTopLeftRadius,
      filter: cs.backdropFilter || cs.webkitBackdropFilter,
      w: +b.width.toFixed(2), h: +b.height.toFixed(2),
      transitionProperty: cs.transitionProperty,
      transitionDuration: cs.transitionDuration,
      transitionTiming: cs.transitionTimingFunction,
    };
  });
  ok('C1 一级 = 毛玻璃面板（.nf-glass 单源）',
    !!panelInfo?.glass && /blur\(/.test(panelInfo.filter || ''), JSON.stringify(panelInfo));
  ok('C2 一级行数 ≥ 4（新建/标签/重要度/新建标签/删除标签）', (panelInfo?.rows ?? 0) >= 4,
    `rows=${panelInfo?.rows}`);
  const transitionOk = (() => {
    if (!panelInfo) return false;
    const props = panelInfo.transitionProperty.split(',').map((s) => s.trim());
    // 期望串按**属性条数**展开（cubic-bezier 里自带逗号 ⇒ 不能按 , 切时序串）
    const wantDur = props.map(() => `${DURATION.base / 1000}s`).join(', ');
    const wantEase = props.map(() => EASING).join(', ');
    return props.every((p) => p === 'transform' || p === 'opacity')
      && panelInfo.transitionDuration === wantDur
      && panelInfo.transitionTiming === wantEase;
  })();
  ok('C3 过渡串 = 液态形变单源（base 档 / 唯一曲线 / 白名单 transform·opacity）',
    transitionOk, JSON.stringify({
      props: panelInfo?.transitionProperty, dur: panelInfo?.transitionDuration, ease: panelInfo?.transitionTiming,
      expect: { base: DURATION.base, ease: EASING },
    }));

  const dangerColor = await page.evaluate(() => {
    const row = document.querySelector('.tfl-menu-row.is-danger');
    if (!row) return null;
    const probe = document.createElement('span');
    probe.style.color = getComputedStyle(document.documentElement).getPropertyValue('--color-danger').trim();
    document.body.appendChild(probe);
    const want = getComputedStyle(probe).color;
    probe.remove();
    return { got: getComputedStyle(row).color, want };
  });
  ok('C4 危险项红字 = 既有 --color-danger 令牌', dangerColor && dangerColor.got === dangerColor.want,
    JSON.stringify(dangerColor));

  const sepCount = await page.evaluate(() => document.querySelectorAll('.tfl-menu-row.is-sep').length);
  ok('C5 组间分隔线在场（≥1 行带 is-sep，伪元素 1px）', sepCount >= 1, `sep=${sepCount}`);

  /* ── D ③ 二级面板叠显 ───────────────────────────────────────────────── */
  const tagRow = page.locator(".tfl-menu-panel .tfl-menu-row", { hasText: '标签' }).first();
  await tagRow.click();
  await page.waitForSelector(".tfl-menu-sub.is-open", { timeout: 5000 });
  await sleep(350);
  const stacked = await page.evaluate(() => {
    const p = document.querySelector('.tfl-menu-panel');
    const s = document.querySelector('.tfl-menu-sub');
    const pr = p.getBoundingClientRect();
    const sr = s.getBoundingClientRect();
    const overlap = !(sr.right < pr.left || sr.left > pr.right || sr.bottom < pr.top || sr.top > pr.bottom);
    return {
      panelVisible: p.offsetWidth > 0 && p.offsetHeight > 0,
      panelBack: p.classList.contains('is-back'),
      panelOpacity: +getComputedStyle(p).opacity,
      subVisible: s.offsetWidth > 0 && s.offsetHeight > 0,
      subGlass: s.classList.contains('nf-glass'),
      overlap,
      subHead: s.querySelector('.tfl-menu-sub-head') ? s.querySelector('.tfl-menu-sub-head').innerText.replace(/\s+/g, ' ').trim() : '',
    };
  });
  ok('D1 二级面板叠出且与一级面板矩形相交（不是替换/跳页）',
    stacked.subVisible && stacked.overlap && stacked.subGlass, JSON.stringify(stacked));
  ok('D2 一级面板仍可见并变暗后退（.is-back，opacity < 1）',
    stacked.panelVisible && stacked.panelBack && stacked.panelOpacity < 0.9, JSON.stringify(stacked));

  /* ── E ③ 选中打勾回写 + 收起 ───────────────────────────────────────── */
  const subRows = await page.locator('.tfl-menu-sub .tfl-menu-row').count();
  ok('D3 二级标签面板含「全部 + 每枚标签」', subRows >= 1 + TAGS.length, `rows=${subRows}`);

  await page.locator('.tfl-menu-sub .tfl-menu-row', { hasText: '工作' }).first().click();
  await sleep(450);
  const writeBack = await page.evaluate(async () => {
    const store = (await import('/js/todoStore.js')).todoStore;
    return { activeTagId: store.activeTagId };
  });
  const menuHidden = await page.evaluate(() => {
    const root = document.querySelector('.tfl-menu');
    return !root || root.hidden || root.style.pointerEvents === 'none';
  });
  ok('E1 选中标签即回写 store（activeTagId）＋ 整菜单收起',
    writeBack.activeTagId === 'tag-work' && menuHidden, JSON.stringify({ writeBack, menuHidden }));

  // 重开 ⇒ 该行带 ✓
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(300);
  const viewSubLabel = (await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '标签' }).first().innerText()).replace(/\s+/g, ' ').trim();
  ok('E2 一级「标签」次行显示当前视图名', viewSubLabel.includes('工作'), viewSubLabel);
  await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '标签' }).first().click();
  await page.waitForSelector(".tfl-menu-sub.is-open", { timeout: 5000 });
  await sleep(300);
  const checked = await page.evaluate(() => {
    const on = [...document.querySelectorAll('.tfl-menu-sub .tfl-menu-row[aria-checked="true"]')]
      .map((r) => r.innerText.replace(/\s+/g, ' ').trim());
    const all = [...document.querySelectorAll('.tfl-menu-sub .tfl-menu-row')]
      .map((r) => ({ t: r.innerText.replace(/\s+/g, ' ').trim(), on: r.getAttribute('aria-checked'), check: !!r.querySelector('.tfl-menu-check svg') }));
    return { on, all };
  });
  ok('E3 二级选中态打勾（✓ 命中在选中行，未选中行无 ✓）',
    checked.on.some((t) => t.includes('工作'))
    && checked.all.every((r) => (r.on === 'true') === r.check),
    JSON.stringify(checked.all));

  /* 重要度二级：多选位写回。
     二级面板叠在锚行下沿 ⇒ 它盖住了一级面板下方的行（图3 同形）——先收菜单再重开。 */
  await page.mouse.click(24, 700);
  await sleep(450);
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(300);
  await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '重要度' }).first().click();
  await page.waitForSelector(".tfl-menu-sub.is-open", { timeout: 5000 });
  await sleep(300);
  await page.locator('.tfl-menu-sub .tfl-menu-row', { hasText: '高' }).first().click();
  await sleep(450);
  const prioState = await page.evaluate(async () => {
    const store = (await import('/js/todoStore.js')).todoStore;
    return [...store.selectedPriorities];
  });
  ok('E4 重要度二级选中即回写（togglePriority）', prioState.includes(3), JSON.stringify(prioState));

  /* ── F ④ 点外部收起 ────────────────────────────────────────────────── */
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(300);
  await page.mouse.click(24, 700); // 面板之外（左下角）
  await sleep(450);
  const outsideClosed = await page.evaluate(() => {
    const root = document.querySelector('.tfl-menu');
    const p = document.querySelector('.tfl-menu-panel');
    return { hidden: !root || root.hidden, open: p ? p.classList.contains('is-open') : false };
  });
  ok('F1 点面板外收起', outsideClosed.hidden === true && outsideClosed.open === false, JSON.stringify(outsideClosed));

  /* 改后读数（可点元素命中盒；供 check-mobile-tap --readings 复算） */
  const tapPage = await page.evaluate(COLLECT);
  writeFileSync(join(OUT, 'readings-after-page.json'), JSON.stringify(tapPage, null, 2));

  /* 截图（证据面） */
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(400);
  await page.screenshot({ path: join(OUT, 'menu-level1.png') });
  await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '标签' }).first().click();
  await page.waitForSelector(".tfl-menu-sub.is-open", { timeout: 5000 });
  await sleep(400);
  await page.screenshot({ path: join(OUT, 'menu-level2.png') });
  await page.mouse.click(24, 700);
  await sleep(400);
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForSelector(".tfl-menu-panel.is-open", { timeout: 5000 });
  await sleep(250);
  await page.locator('.tfl-menu-panel .tfl-menu-row').first().click();
  await page.waitForSelector(".mobile-page[data-page='tasks'] .mf-drawer:not([hidden])", { timeout: 5000 });
  await sleep(700);
  await page.screenshot({ path: join(OUT, 'create-from-top.png') });
  const geo = await page.evaluate(RECTS);
  const tapping = await page.evaluate(COLLECT);
  writeFileSync(join(OUT, 'readings-after-sheet.json'), JSON.stringify(tapping, null, 2));
  writeFileSync(join(OUT, 'spec-readings.json'), JSON.stringify({ media, before, after, geo, oldCounts, panelInfo, stacked, checked, focusState, tapPage, tapping }, null, 2));

  await ctx.close();

  /* ══════ 桌面腿 1440×900（零改动对照）══════ */
  const dctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const dpage = await dctx.newPage();
  await wire(dpage);
  await dpage.goto('http://localhost:1/');
  await dpage.waitForFunction(() => !!document.querySelector('#task-list'), null, { timeout: 20000 });
  await dpage.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await dpage.waitForTimeout(400);
  await seed(dpage);
  await dpage.waitForSelector('#task-list.has-tasks', { timeout: 8000 });
  await dpage.waitForTimeout(300);
  const desktop = {
    filterToggle: await dpage.locator('#task-list .tfl-filter-toggle').count(),
    collapseBtn: await dpage.locator('#task-list .tfl-collapse-btn').count(),
    topMenuBtn: await dpage.locator('.tfl-topmenu-btn').count(),
    drawerTop: await dpage.locator('.mf-drawer-top').count(),
  };
  ok('H1 桌面腿旧控件仍在（.tfl-filter-toggle / .tfl-collapse-btn 命中）',
    desktop.filterToggle >= 1 && desktop.collapseBtn >= 1, JSON.stringify(desktop));
  ok('H2 桌面腿零新增（无 ⋯ 钮 / 无顶部表单修饰类）',
    desktop.topMenuBtn === 0 && desktop.drawerTop === 0, JSON.stringify(desktop));
  await dctx.close();
} catch (e) {
  fail++;
  console.log(`FAIL  执行异常 — ${e.message}`);
  lines.push(`FAIL  执行异常 — ${e.message}`);
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
process.exit(fail === 0 ? 0 : 1);
