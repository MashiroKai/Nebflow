#!/usr/bin/env node
// mobile-todo-fullbleed-shots.mjs — 手机端「待办 / 定时任务」页铺满布局 + Canvas 同款
// 右下悬浮加号批（todo-fullbleed，作者令 2026-11-05 17:33）的**读数 + 截图**统一腿。
//
// 用法：
//   node tests/mobile-todo-fullbleed-shots.mjs [outDir]
//   TODOFULL_WEB_ROOT=/tmp/xxx SHOT_TAG=before node tests/mobile-todo-fullbleed-shots.mjs <outDir>
//     · TODOFULL_WEB_ROOT 指到另一份 web 树 = 同一脚本跑「改前腿」（判定基线）；
//     · SHOT_TAG 只进报告头部，不影响读数。
//
// 为什么是同一脚本跑两腿：改前/改后的读数必须同判据同取点，否则两张表不可比。
//
// 数据面 = routeWebSocket 帧夹具（configData / sessionList / scheduledTaskList /
// historyPage）+ todoStore.__seed 夹具模式；静态面 = page.route 从磁盘注真 web 树。
// 零后端、零 sbt、零宿主实例（不碰 :6138）。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TODOFULL_WEB_ROOT
  ? normalize(resolve(process.env.TODOFULL_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const TAG = process.env.SHOT_TAG || 'after';
const OUT = process.argv[2] || `/tmp/todo-fullbleed-shots/${TAG}`;
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todofull-session';
const T0 = Date.now();

/* ─────────────── 数据夹具 ─────────────── */

function todoItems() {
  const now = Date.now();
  return [
    { id: 'fd-1', tagId: null, name: '修登录态过期', detail: '', due: now + 3600_000, priority: 3, createdAt: now - 9 * 3600_000 },
    { id: 'fd-2', tagId: null, name: '缴房租', detail: '本月最后一笔', due: now + 2 * 3600_000, priority: 2, createdAt: now - 8 * 3600_000 },
    { id: 'fd-3', tagId: null, name: '读《设计中的设计》', detail: '', due: now + 3 * 3600_000, priority: 1, createdAt: now - 7 * 3600_000 },
    { id: 'fd-4', tagId: null, name: '记一个交互点子', detail: '', due: null, priority: 0, createdAt: now - 6 * 3600_000 },
  ];
}

function schedTasks() {
  return [
    { id: 'st-1', content: '每小时巡检构建', triggerAt: T0 + 3_600_000, repeat: 'hourly', enabled: true, triggered: false, lastTriggered: T0 - 3_600_000 },
    { id: 'st-2', content: '每天晨报摘要', triggerAt: T0 + 86_400_000, repeat: 'daily', enabled: true, triggered: false },
    { id: 'st-3', content: '每周复盘提醒', triggerAt: T0 + 7 * 86_400_000, repeat: 'weekly', enabled: true, triggered: false },
    { id: 'st-4', content: '单次：回看第 3 节', triggerAt: T0 + 1_800_000, enabled: true, triggered: false },
  ];
}

/* ─────────────── 页内读数探针（一次性注入，全部真 DOM 几何） ─────────────── */

const PROBE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    const f = (v) => +v.toFixed(2);
    return { x: f(b.x), y: f(b.y), w: f(b.width), h: f(b.height), right: f(b.right), bottom: f(b.bottom),
             cx: f(b.x + b.width / 2), cy: f(b.y + b.height / 2) }; };
  const desc = (el) => {
    if (!el) return 'null';
    const cls = typeof el.className === 'string' && el.className.trim()
      ? '.' + el.className.trim().split(/\\s+/).filter(Boolean).join('.') : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls;
  };
  const shown = (el) => { if (!el) return false; const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    const b = el.getBoundingClientRect(); return b.width > 0.5 && b.height > 0.5; };

  const page = document.querySelector('.mobile-page.live');
  const pageBody = page ? page.querySelector('.mobile-page-body') : null;
  const dock = document.getElementById('mobile-dock');
  const title = page ? page.querySelector('.mobile-page-title') : null;
  const vw = innerWidth, vh = innerHeight;

  // 列表容器候选（两页各取自己的滚动面；取「在场且最高」的那一个）
  const listCands = [
    '.tfl-list', '.tfl-list-wrap', '.reminder-panel-body', '#reminder-panel .reminder-panel-body',
  ].map((s) => page && page.querySelector(s)).filter(Boolean);
  let list = null;
  for (const c of listCands) { if (!list || c.getBoundingClientRect().height > list.getBoundingClientRect().height) list = c; }
  const listWrap = page ? page.querySelector('.tfl-list-wrap') : null;

  const fab = page ? page.querySelector('.tfl-fab-add, .reminder-fab-add, .mf-page-fab') : null;
  const fabRect = R(fab);
  let fabHit = null;
  if (fab) { const cs = getComputedStyle(fab); fabHit = { w: cs.width, h: cs.height }; }
  // 热区实测：命中盒 = 元素盒（可能由 ::after 外扩）—— 取 elementFromPoint 反查中心与边角
  let hitProbe = null;
  if (fabRect) {
    const inside = (dx, dy) => {
      const el = document.elementFromPoint(fabRect.cx + dx, fabRect.cy + dy);
      return !!(el && (el === fab || fab.contains(el) || el.closest('.tfl-fab-add, .reminder-fab-add, .mf-page-fab')));
    };
    // 从中心向外二分找命中边界（向上/下/左/右各一条）
    const edge = (axis, sign) => {
      let inside0 = 0, out = 80;
      for (let i = 0; i < 40; i++) {
        const mid = (inside0 + out) / 2;
        const ok = axis === 'x' ? inside(sign * mid, 0) : inside(0, sign * mid);
        if (ok) inside0 = mid; else out = mid;
      }
      return +inside0.toFixed(1);
    };
    hitProbe = {
      left: edge('x', -1), right: edge('x', 1), up: edge('y', -1), down: edge('y', 1),
    };
  }

  // 与底部 Tab 栏（#mobile-dock）的交叠检测
  let dockOverlap = null;
  if (fabRect && dock) {
    const d = dock.getBoundingClientRect();
    const ix = Math.max(0, Math.min(fabRect.right, d.right) - Math.max(fabRect.x, d.left));
    const iy = Math.max(0, Math.min(fabRect.bottom, d.bottom) - Math.max(fabRect.y, d.top));
    dockOverlap = { w: +ix.toFixed(2), h: +iy.toFixed(2), area: +(ix * iy).toFixed(2) };
  }

  const cs = fab ? getComputedStyle(fab) : null;
  const fabStyle = cs ? {
    classList: typeof fab.className === 'string' ? fab.className : '',
    borderRadius: cs.borderRadius, background: cs.backgroundColor, backgroundImage: cs.backgroundImage,
    boxShadow: cs.boxShadow, position: cs.position, width: cs.width, height: cs.height,
    backdropFilter: cs.backdropFilter || cs.webkitBackdropFilter, right: cs.right, bottom: cs.bottom, zIndex: cs.zIndex,
  } : null;

  const listRect = R(list), wrapRect = R(listWrap), pageRect = R(page), bodyRect = R(pageBody), titleRect = R(title);

  // 新建抽屉（两页共用 .mf-drawer 一族）
  const drawer = page ? page.querySelector('.mf-drawer') : null;
  const drawerBox = drawer ? drawer.querySelector('.mf-drawer-box') : null;
  const drawerBody = drawer ? drawer.querySelector('.mf-drawer-body') : null;
  const drawerInfo = drawer ? {
    desc: desc(drawer),
    hidden: drawer.hasAttribute('hidden') && drawer.hidden,
    box: R(drawerBox),
    body: R(drawerBody),
    bodyScrollH: drawerBody ? drawerBody.scrollHeight : null,
    bodyClientH: drawerBody ? drawerBody.clientHeight : null,
    // 抽屉底缘到页面底缘（=0 ⇒ 抽屉贴住 Tab 栏上缘，不溢出不悬空）
    boxBottomToPageBottom: drawerBox && pageRect ? +(pageRect.bottom - drawerBox.getBoundingClientRect().bottom).toFixed(2) : null,
    // 抽屉内最后一颗控件是否完整可见（内容底 ≤ 抽屉底）
    lastChildBottom: (() => {
      if (!drawerBody) return null;
      const kids = [...drawerBody.querySelectorAll('button,input,div')].filter(shown);
      if (!kids.length) return null;
      return +Math.max(...kids.map((k) => k.getBoundingClientRect().bottom)).toFixed(2);
    })(),
  } : null;

  // ── 铺满判据 ──
  const pageCS = page ? getComputedStyle(page) : null;
  const bodyCS = pageBody ? getComputedStyle(pageBody) : null;
  const listCS = list ? getComputedStyle(list) : null;
  const fill = (pageRect && listRect) ? {
    leftGap: +(listRect.x - pageRect.x).toFixed(2),
    rightGap: +(pageRect.right - listRect.right).toFixed(2),
    topGapToPage: +(listRect.y - pageRect.y).toFixed(2),
    bottomGapToPage: +(pageRect.bottom - listRect.bottom).toFixed(2),
    // 下空白带 = 列表底缘到「页面可用底缘」的距离；0 ⇒ 无白板
    blankBelow: +(pageRect.bottom - listRect.bottom).toFixed(2),
    widthRatio: +(listRect.w / pageRect.w).toFixed(4),
    heightRatio: +(listRect.h / pageRect.h).toFixed(4),
  } : null;
  const titleTo = (titleRect && listRect) ? +(listRect.y - titleRect.bottom).toFixed(2) : null;

  return {
    viewport: { w: vw, h: vh },
    page: { desc: desc(page), rect: pageRect, paddingBottom: pageCS && pageCS.paddingBottom, overflow: pageCS && pageCS.overflow },
    pageBody: { desc: desc(pageBody), rect: bodyRect, padding: bodyCS && bodyCS.padding,
                scrollH: pageBody ? pageBody.scrollHeight : null, clientH: pageBody ? pageBody.clientHeight : null },
    title: { desc: desc(title), rect: titleRect },
    list: { desc: desc(list), rect: listRect, padding: listCS && listCS.padding,
            scrollH: list ? list.scrollHeight : null, clientH: list ? list.clientHeight : null },
    listWrap: { desc: desc(listWrap), rect: wrapRect },
    fill, titleToListTop: titleTo,
    dock: { rect: R(dock) },
    fab: { desc: desc(fab), rect: fabRect, style: fabStyle, hitBoxProbe: hitProbe, styleBox: fabHit },
    fabInsets: fabRect ? {
      rightFromViewport: +(vw - fabRect.right).toFixed(2),
      bottomFromViewport: +(vh - fabRect.bottom).toFixed(2),
      rightFromPage: pageRect ? +(pageRect.right - fabRect.right).toFixed(2) : null,
      bottomFromPage: pageRect ? +(pageRect.bottom - fabRect.bottom).toFixed(2) : null,
      diameter: fabRect.w,
    } : null,
    dockOverlap,
    drawer: drawerInfo,
    texts: {
      pageLive: page ? page.dataset.page : null,
      visibleText: page ? (page.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 300) : '',
    },
    // 白板扫描：页面内「大块纯底空白」——找无内容且高度 > 40 的空 flex 子件
    empties: (() => {
      const out = [];
      if (!page) return out;
      for (const el of page.querySelectorAll('*')) {
        if (!shown(el)) continue;
        if (el.children.length) continue;
        const txt = (el.textContent || '').trim();
        if (txt) continue;
        const b = el.getBoundingClientRect();
        if (b.height > 40 && b.width > 40) out.push({ el: desc(el), rect: R(el) });
      }
      return out.slice(0, 12);
    })(),
  };
})()`;

/* ─────────────── 启动 ─────────────── */

const browser = await chromium.launch();
const errors = [];

async function boot({ colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

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
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    let tasks = schedTasks();
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    const sendTasks = () => ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') sendTasks();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    void tasks;
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { ctx, page };
}

/* ─────────────── 两页导航 ─────────────── */

async function openTasksPage(page) {
  await page.locator("[data-chrome='tasks']").click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(700); // 面板建板 + 弹簧落地
}

async function openReminderPage(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForTimeout(350);
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="reminder"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="reminder"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(700);
}

const reports = {};
async function shot(page, name) {
  await page.screenshot({ path: join(OUT, `${name}.png`) });
}
async function probe(page, name) {
  const r = await page.evaluate(PROBE);
  reports[name] = r;
  return r;
}

/* ═══════════ 场景 1：待办页（列表 / 空态 / 新建流程） ═══════════ */

{
  const { ctx, page } = await boot();
  // 夹具注入（先种后开页：face 建板时即读 store）
  await page.evaluate((items) => {
    return import('/js/todoStore.js').then((m) => { m.todoStore.__seed({ itemList: items }); });
  }, todoItems());
  await openTasksPage(page);
  await shot(page, '01-tasks-list');
  await probe(page, 'tasks-list');

  // 新建流程：点右下加号 → 弹出 → 填标题 → 保存 → 列表更新
  const fabSel = '.mobile-page[data-page="tasks"] .tfl-fab-add, .mobile-page[data-page="tasks"] .mf-page-fab';
  const fabCount = await page.locator(fabSel).count();
  reports['tasks-fab-count'] = fabCount;
  if (fabCount > 0) {
    await page.locator(fabSel).first().click();
    await page.waitForTimeout(600);
    await shot(page, '02-tasks-create-open');
    await probe(page, 'tasks-create-open');
    const input = page.locator('.mobile-page[data-page="tasks"] .tfl-create-row .tfl-row-title-input').first();
    if (await input.count()) {
      await input.fill('新建一条待办');
      await page.waitForTimeout(200);
      await shot(page, '03-tasks-create-typed');
      // 保存 = 点 footer 的「完成」或创建行非文本区；用 Enter（既有 saveCreate 路径）
      await input.press('Enter');
      await page.waitForTimeout(700);
      await shot(page, '04-tasks-create-saved');
      await probe(page, 'tasks-create-saved');
    }
  }

  // 空态：清空夹具
  await page.evaluate(() => import('/js/todoStore.js').then((m) => m.todoStore.__seed({ itemList: [] })));
  await page.waitForTimeout(600);
  await shot(page, '05-tasks-empty');
  await probe(page, 'tasks-empty');

  await ctx.close();
}

/* ═══════════ 场景 2：定时任务页（列表 / 空态 / 新建流程） ═══════════ */

{
  const { ctx, page } = await boot();
  await openReminderPage(page);
  await shot(page, '06-reminder-list');
  await probe(page, 'reminder-list');

  const fabSel = '.mobile-page[data-page="reminder"] .reminder-fab-add, .mobile-page[data-page="reminder"] .mf-page-fab';
  const fabCount = await page.locator(fabSel).count();
  reports['reminder-fab-count'] = fabCount;
  if (fabCount > 0) {
    await page.locator(fabSel).first().click();
    await page.waitForTimeout(600);
    await shot(page, '07-reminder-create-open');
    await probe(page, 'reminder-create-open');
  }
  await ctx.close();
}

/* ═══════════ 场景 3：暗色（口径核对，非判据） ═══════════ */

{
  const { ctx, page } = await boot({ colorScheme: 'dark' });
  await page.evaluate((items) => import('/js/todoStore.js').then((m) => { m.todoStore.__seed({ itemList: items }); }), todoItems());
  await openTasksPage(page);
  await shot(page, '08-tasks-list-dark');
  await ctx.close();
}

await browser.close();

const head = `# mobile-todo-fullbleed 读数（tag=${TAG}）\nWEB=${WEB}\nOUT=${OUT}\n`;
writeFileSync(join(OUT, 'probe.json'), JSON.stringify(reports, null, 2) + '\n');
writeFileSync(join(OUT, 'probe.md'), head + '\n```json\n' + JSON.stringify(reports, null, 2) + '\n```\n');
console.log(head);
console.log(JSON.stringify(reports, null, 2));
if (errors.length) { console.log('\nPAGE ERRORS:'); errors.forEach((e) => console.log(' - ' + e)); }
console.log('\nshots saved to ' + OUT);
