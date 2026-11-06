#!/usr/bin/env node
// capture-before.mjs — 「改前」基线实拍腿（todo-mobile-revamp 批 · 2026-11-06）。
//
// 零后端法（与 tests/_todo-face-harness.mjs 同源口径）：
//   · 静态面 = page.route 从磁盘注入**本 worktree 真 web 树**（src/main/resources/web）；
//   · 数据面 = routeWebSocket 帧桩（configData / sessionList / 空 historyPage）+ todoStore.__seed 夹具；
//   · 视口   = 375×812、deviceScaleFactor 2、hasTouch、isMobile（matchMedia 命中 ⇒ body.mobile-layer）；
//   · 入口   = chatChrome [data-chrome='tasks'] → navController pushPage('tasks') → js/mobile/taskPanel.js；
//   · 零 sbt / 零宿主管家实例 / 不碰 :6138。
//
// 用法：node reports-evidence/todo-mobile-revamp__20261106/shots-before/capture-before.mjs
// 产出：同目录 tasks-01-page.png / tasks-02-drawer.png / tasks-03-top-controls.png / readings-before.json
//
// 🔴 诚实申报：渲染链是真链（renderTodoTaskList → TodoFace → 面板/抽屉），数据面是夹具。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', '..', '..', 'src', 'main', 'resources', 'web');
const OUT = HERE;
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'todo-mobile-before-session';

/* ───────────── 页内读数探针：可点元素 rect + ::after/::before 外扩 ───────────── */

const PROBE = (scopeSel) => `(() => {
  const root = document.querySelector(${JSON.stringify(scopeSel)});
  if (!root) return { error: 'no-root' };

  const uniqSel = (el) => {
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === 1) {
      if (cur.id) { parts.unshift('#' + cur.id); break; }
      let part = cur.tagName.toLowerCase();
      const cls = [...cur.classList].slice(0, 3);
      if (cls.length) part += '.' + cls.join('.');
      const parent = cur.parentElement;
      if (parent) {
        const sameTag = [...parent.children].filter((c) => c.tagName === cur.tagName);
        if (sameTag.length > 1) part += ':nth-of-type(' + (sameTag.indexOf(cur) + 1) + ')';
      }
      parts.unshift(part);
      const p = parts.join(' > ');
      try { if (document.querySelectorAll(p).length === 1) break; } catch (e) {}
      cur = parent;
    }
    return parts.join(' > ');
  };

  const shown = (el) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    const b = el.getBoundingClientRect();
    return b.width > 0.5 && b.height > 0.5;
  };

  // 伪元素命中外扩：只认「定位 absolute/fixed + 有内容 + 吃指针」的伪元素
  // （pointer-events:none 的伪元素不吃点击 ⇒ 不算命中外扩）。
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
      if (L === null || R === null || T === null || B === null) { res.push({ p, w: null, h: null }); continue; }
      res.push({ p, w: +(rect.width - L - R).toFixed(2), h: +(rect.height - T - B).toFixed(2) });
    }
    return res;
  };

  const SEL = 'button, a, [role="button"], input, select, textarea, '
    + '[tabindex]:not([tabindex="-1"]), .tfl-row, .tfl-chip, .tfl-chip-trash, .tfl-chip-add, '
    + '.tfl-chip-arrow, .tfl-fab-add, .tfl-icon-btn, .mf-drawer-cancel-btn, .mf-drawer-done-btn, .tfl-undo-btn';

  const panels = [root, document.querySelector('#mobile-task-panel'), document.querySelector('#todo-island')]
    .filter(Boolean);
  const seen = new Set();
  const out = [];
  for (const host of panels) {
    for (const el of host.querySelectorAll(SEL)) {
      if (seen.has(el)) continue;
      seen.add(el);
      if (!shown(el)) continue;
      const b = el.getBoundingClientRect();
      const rect = { width: +b.width.toFixed(2), height: +b.height.toFixed(2) };
      const ps = pseudoHit(el, rect);
      let hitW = rect.width, hitH = rect.height;
      for (const q of ps) {
        if (q.w === null) { hitW = null; hitH = null; break; }
        hitW = Math.max(hitW, q.w); hitH = Math.max(hitH, q.h);
      }
      out.push({
        selector: uniqSel(el),
        w: rect.width,
        h: rect.height,
        hitW: hitW === null ? null : +hitW.toFixed(2),
        hitH: hitH === null ? null : +hitH.toFixed(2),
      });
    }
  }
  return { count: out.length, items: out };
})()`;

const TOP_PROBE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
             right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) }; };
  const page = document.querySelector('.mobile-page[data-page="tasks"]');
  const titleEl = page && page.querySelector('.mobile-page-title');
  return {
    page: { desc: page ? page.className : null, rect: R(page) },
    pageHead: R(page && page.querySelector('.mobile-page-head')),
    title: { text: titleEl ? titleEl.textContent.trim() : '', rect: R(titleEl) },
    header: R(page && page.querySelector('.tfl-header')),
    filterWrap: R(page && page.querySelector('.tfl-filter-wrap')),
    chipsRow: R(page && page.querySelector('.tfl-chips-row')),
    prioRow: R(page && page.querySelector('.tfl-prio-row')),
    fab: R(page && page.querySelector('.tfl-fab-add')),
    drawer: R(page && page.querySelector('.mf-drawer')),
    drawerBox: R(page && page.querySelector('.mf-drawer-box')),
    viewport: { w: innerWidth, h: innerHeight },
    text: page ? (page.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 400) : null,
  };
})()`;

/* ───────────── 启动 ───────────── */

const browser = await chromium.launch();
const errors = [];
const log = {};

const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
});
const page = await ctx.newPage();
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
const failed = [];
page.on('response', (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`); });

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

await page.goto('http://localhost:1/');
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await page.waitForTimeout(400);

// 夹具：空待办 + 空标签（作者图1 = 空列表态）
await page.evaluate(async (sid) => {
  const store = await import('/js/todoStore.js');
  store.todoStore.__seed({ tagList: [], itemList: [] });
  const face = await import('/js/todoFace.js');
  if (typeof face.renderTodoTaskList === 'function') face.renderTodoTaskList(sid);
}, SID);
await page.waitForTimeout(300);

// 入口：左上「任务」圆钮 → pushPage('tasks')
log.chromeEntryCount = await page.locator("[data-chrome='tasks']").count();
await page.locator("[data-chrome='tasks']").first().click();
await page.waitForFunction(() => {
  const el = document.querySelector('.mobile-page[data-page="tasks"]');
  return el && el.classList.contains('live');
}, null, { timeout: 10000 });
await page.waitForTimeout(800); // 面板建板 + 落位

// 筛选栏展开（chips 行 = 全部 + 垃圾桶；prio 行 = 无/低/中/高）
log.filterToggleCount = await page.locator(".mobile-page[data-page='tasks'] .tfl-filter-toggle").count();
await page.locator(".mobile-page[data-page='tasks'] .tfl-filter-toggle").first().click();
await page.waitForSelector(".mobile-page[data-page='tasks'] .tfl-chip-all", { state: 'visible', timeout: 8000 });
await page.waitForTimeout(500);

log.top = await page.evaluate(TOP_PROBE);

/* ───────── ① 空列表态整页 ───────── */
await page.screenshot({ path: join(OUT, 'tasks-01-page.png') });
const read1 = await page.evaluate(PROBE(".mobile-page[data-page='tasks']"));
log.read1Count = read1.count;

/* ───────── ③ 顶部控制区特写（先于抽屉拍：抽屉会盖住控制区） ───────── */
{
  const t = log.top;
  const xs = [t.header, t.filterWrap].filter(Boolean);
  const x0 = Math.max(0, Math.floor(Math.min(...xs.map((r) => r.x)) - 8));
  const y0 = Math.max(0, Math.floor(Math.min(...xs.map((r) => r.y)) - 8));
  const x1 = Math.min(t.viewport.w, Math.ceil(Math.max(...xs.map((r) => r.right)) + 8));
  const y1 = Math.min(t.viewport.h, Math.ceil(Math.max(...xs.map((r) => r.bottom)) + 8));
  log.clip = { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
  await page.screenshot({ path: join(OUT, 'tasks-03-top-controls.png'), clip: log.clip });
}

/* ───────── ② 新建抽屉（标题框聚焦蓝边帧） ───────── */
await page.locator(".mobile-page[data-page='tasks'] .tfl-fab-add").first().click();
await page.waitForSelector(".mobile-page[data-page='tasks'] .mf-drawer:not([hidden])", { timeout: 8000 });
await page.waitForTimeout(500);
log.drawerInputCount = await page.locator(".mobile-page[data-page='tasks'] .mf-drawer input").count();
const titleInput = page.locator(".mobile-page[data-page='tasks'] .mf-drawer input").first();
await titleInput.click();
await page.waitForTimeout(400);
log.focused = await page.evaluate(() => {
  const a = document.activeElement;
  if (!a) return null;
  const cs = getComputedStyle(a);
  return { tag: a.tagName.toLowerCase(), cls: a.className, outline: cs.outline, boxShadow: cs.boxShadow,
           borderColor: cs.borderColor, borderWidth: cs.borderWidth };
});
await page.screenshot({ path: join(OUT, 'tasks-02-drawer.png') });
log.topDrawer = await page.evaluate(TOP_PROBE);
// 抽屉表单几何（供「命中盒 / 行高」口径核对：输入框自身盒 vs 外层行盒）
log.drawerGeom = await page.evaluate(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; };
  const body = document.querySelector(".mobile-page[data-page='tasks'] .mf-drawer .mf-drawer-body");
  if (!body) return null;
  const rows = [...body.querySelectorAll('.tfl-create-row, .tfl-row, .tfl-row-title, .tfl-row-notes, input, textarea, button')]
    .map((el) => {
      const cs = getComputedStyle(el);
      return { el: el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.split(/\s+/).join('.') : ''),
               rect: R(el), boxSizing: cs.boxSizing, height: cs.height, lineHeight: cs.lineHeight, padding: cs.padding };
    });
  return { body: R(body), rows };
});
// 焦点环真身（蓝边落在哪个元素上：outline 非 none 且宽 > 0 的全量清单）
log.focusRings = await page.evaluate(() => {
  const R = (el) => { const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; };
  const desc = (el) => el.tagName.toLowerCase()
    + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).join('.') : '');
  const out = [];
  for (const el of document.querySelectorAll('.mobile-page[data-page="tasks"] *')) {
    const cs = getComputedStyle(el);
    if (cs.outlineStyle && cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) {
      out.push({ el: desc(el), rect: R(el), outline: `${cs.outlineWidth} ${cs.outlineStyle} ${cs.outlineColor} offset ${cs.outlineOffset}`,
                 borderColor: cs.borderColor, borderWidth: cs.borderWidth });
    }
  }
  return out;
});
log.hitAtDrawerTitle = await page.evaluate(() => [585, 600, 612].map((y) => {
  const el = document.elementFromPoint(200, y);
  return { y, el: el ? el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).join('.') : '') : null };
}));
// 关键目标元素 rect 全表（README 的「目标元素 rect」列直接取此处）
log.keyRects = await page.evaluate(() => {
  const R = (sel, idx = 0) => {
    const els = document.querySelectorAll(sel);
    const el = els[idx];
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) };
  };
  const P = ".mobile-page[data-page='tasks'] ";
  return {
    backBtn: R(P + '.mobile-back-btn'),
    pageTitle: R(P + '.mobile-page-title'),
    header: R(P + '.tfl-header'),
    headerCount: R(P + '.tfl-header-count'),
    filterToggle: R(P + '.tfl-filter-toggle'),
    filterWrap: R(P + '.tfl-filter-wrap'),
    chipsRow: R(P + '.tfl-chips-row'),
    allChip: R(P + '.tfl-chip-all'),
    chipAdd: R(P + '.tfl-chip-add'),
    trash: R(P + '.tfl-chip-trash'),
    prio1: R(P + '.tfl-chip-prio', 0),
    prio2: R(P + '.tfl-chip-prio', 1),
    prio3: R(P + '.tfl-chip-prio', 2),
    prio4: R(P + '.tfl-chip-prio', 3),
    listScroll: R(P + '.tfl-list'),
    emptyText: R(P + '.tfl-empty, ' + P + '.tfl-list-empty'),
    fab: R(P + '.tfl-fab-add'),
    drawer: R(P + '.mf-drawer'),
    drawerBox: R(P + '.mf-drawer-box'),
    sheetTitle: R(P + '.mf-drawer-title'),
    cancelBtn: R(P + '.mf-drawer-cancel-btn'),
    doneBtn: R(P + '.mf-drawer-done-btn'),
    titleInput: R(P + '.mf-drawer input.tfl-row-title-input'),
    notesInput: R(P + '.mf-drawer input.tfl-edit-notes'),
    drawerPrio1: R(P + '.mf-drawer .tfl-edit-prio-btn', 0),
    drawerPrio4: R(P + '.mf-drawer .tfl-edit-prio-btn', 3),
    drawerToggle: R(P + '.mf-drawer .nb-toggle'),
    dock: R('#mobile-dock'),
  };
});
const read2 = await page.evaluate(PROBE(".mobile-page[data-page='tasks']"));

await ctx.close();
await browser.close();

/* ───────── readings-before.json ───────── */
// 主表 = ① 空列表态（筛选展开、抽屉闭合）；抽屉态另量一腿并追加（选择器路径含 .mf-drawer 自证来源）。
const items = [...read1.items];
const known = new Set(items.map((i) => i.selector));
for (const it of read2.items) if (!known.has(it.selector)) items.push(it);
writeFileSync(join(OUT, 'readings-before.json'), JSON.stringify(items, null, 2) + '\n');
writeFileSync(join(OUT, '_probe-log.json'), JSON.stringify({ log, errors, failed }, null, 2) + '\n');

console.log(JSON.stringify({ log, errors, failed, readings: { page: read1.count, drawer: read2.count, total: items.length } }, null, 2));
