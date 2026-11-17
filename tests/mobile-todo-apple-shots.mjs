#!/usr/bin/env node
// tests/mobile-todo-apple-shots.mjs — self-contained smoke + dual-theme shots
// for the mobile todo pages (Apple Reminders information architecture).
//
// Standalone node script (boots its own chromium, prints its own PASS/FAIL;
// does not import the @playwright/test runner surface, so playwright.config.mjs
// testIgnore excludes it from the CI collection set).
//
// Coverage:
//   (1) pages push: all three pages pushPage and go .live (back pops to the parent)
//   (2) cards carry data: readings == the todoStore count projection
//       (today/overdue/all/completed)
//   (3) list rows clickable: My Lists row -> list detail page (title = tag
//       name, row count = open count)
//   (4) detail page renders both the empty and the non-empty state
//       (__seed all completed -> centered "all done")
//   (5) create-state save persists (allItems() grows after createItem)
//   (6) toolbar appears with exactly 3 icons (date/flag/tag)
//   (7) checking = todoStore.completeItem (counts move, row leaves)
//   (8) zero console/page errors
//
// Usage: node tests/mobile-todo-apple-shots.mjs [--keep]

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.MTA_OUT_DIR || join(HERE, '..', 'evidence', `${TS}_mobile-todo-apple`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mobile-todo-apple-root';
const ORIGIN = 'http://localhost:8898'; // 8899 = panel-title spec; ports kept disjoint
const VW = 375;
const VH = 812;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures += 1;
};

const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`); });
};

async function boot(scheme) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => { /* registration stays a silent no-op */ }),
      configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(500);
  return { browser, page };
}

const push = async (page, id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(260);
};
const popAll = async (page) => {
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    try { nav.popToRoot(); } catch { /* already at root */ }
  });
  await page.waitForTimeout(120);
};
const store = (page, fn, arg) => page.evaluate(async ({ src, a }) => {
  const m = await import('/js/todoStore.js');
  // eslint-disable-next-line no-new-func
  return new Function('todoStore', 'a', src)(m.todoStore, a);
}, { src: fn, a: arg });

// ── Main leg (light theme) ───────────────────────────────────────────────
async function mainLeg() {
  const { browser, page } = await boot('light');
  // Deterministic seed (fixture = two tags + three open items + one completed;
  // today/overdue/all are each independently assertable)
  await store(page, `
    const DAY = 86400000;
    const d = new Date(); d.setHours(23, 0, 0, 0);   // today 23:00 (not yet past, so today and overdue cards do not overlap)
    const today = d.getTime();
    todoStore.__seed({
      tagList: [
        { id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' },
        { id: 'tl', name: '生活', color: 'rgb(77, 217, 102)' },
      ],
      itemList: [
        { id: 'i1', tagId: 'tw', title: '写周报', due: today, priority: 3, completed: false },
        { id: 'i2', tagId: 'tw', title: '过期的事', due: today - 3 * DAY, priority: 0, completed: false },
        { id: 'i3', tagId: 'tl', title: '买菜', due: null, priority: 0, completed: false },
        { id: 'i4', tagId: 'tl', title: '已完成的旧事', due: null, priority: 1, completed: true },
      ],
    });
  `);

  // (1) home page push
  await push(page, 'tasks');
  ok('(1) tasks page live (pushPage works)', await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);
  ok('(1) shell back button present (48 g2-fab)', await page.locator('.mobile-page[data-page="tasks"] .mobile-back-btn').count() === 1);
  // The shell page title is statically hidden on this page (selector scoped to it)
  ok('(1) in-flow page title statically hidden on this page (display:none)',
    await page.locator('.mobile-page[data-page="tasks"] .mobile-page-title').evaluate((el) => getComputedStyle(el).display) === 'none');
  // The page title is a single node (no separate sticky-capsule node), so
  // hiding that one node covers both the in-flow and the sticky state.
  ok('(1) no separate sticky title node (single-node title: hidden in both states)',
    await page.locator('.mobile-page[data-page="tasks"] .mobile-page-title-mini').count() === 0);

  // (2) the four cards carry data
  const cards = await page.evaluate(() => {
    const out = {};
    for (const k of ['today', 'overdue', 'all', 'completed']) {
      const c = document.querySelector(`.mobile-page[data-page="tasks"] .ta-card-${k}`);
      out[k] = {
        n: c?.querySelector('.ta-card-n')?.textContent,
        l: c?.querySelector('.ta-card-l')?.textContent,
        bg: c ? getComputedStyle(c).backgroundColor : '',
      };
    }
    return out;
  });
  ok('(2) card readings (today 1 / overdue 1 / all 3 / completed 1)',
    cards.today.n === '1' && cards.overdue.n === '1' && cards.all.n === '3' && cards.completed.n === '1',
    JSON.stringify(cards));
  ok('(2) card labels render the zh-CN locale entries', cards.today.l === '今天' && cards.overdue.l === '逾期' && cards.all.l === '全部' && cards.completed.l === '已完成');
  ok('(2) four card backgrounds are four distinct colors', new Set([cards.today.bg, cards.overdue.bg, cards.all.bg, cards.completed.bg]).size === 4,
    [cards.today.bg, cards.overdue.bg, cards.all.bg, cards.completed.bg].join(' | '));

  // "My Lists" = todoStore.tags + the trailing "all todos" row
  const rows = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-rows .ta-row')]
    .map((r) => ({
      label: r.querySelector('.ta-row-label')?.textContent,
      count: r.querySelector('.ta-row-count')?.textContent,
      cls: r.className,
    })));
  ok('(2) list card rows = 2 tags + trailing "all todos" row (rows come from todoStore.tags)',
    rows.length === 3 && rows[0].label === '工作' && rows[0].count === '2'
    && rows[1].label === '生活' && rows[1].count === '1' && rows[2].label === '全部待办' && rows[2].count === '3',
    JSON.stringify(rows));

  // Top capsule with three icons (top right; left end stays clear)
  const pill = await page.evaluate(() => {
    const p = document.querySelector('.mobile-page[data-page="tasks"] .ta-pill');
    if (!p) return null;
    const r = p.getBoundingClientRect();
    return { btns: p.querySelectorAll('.ta-pill-btn').length, right: innerWidth - r.right, left: r.left, h: r.height };
  });
  ok('(2) top-right white capsule has three icons (left end clear of the back button)', !!pill && pill.btns === 3 && pill.left > 64 && pill.right < 20, JSON.stringify(pill));

  // FAB
  const fab = await page.evaluate(() => {
    const f = document.querySelector('.mobile-page[data-page="tasks"] .ta-fab');
    if (!f) return null;
    const r = f.getBoundingClientRect();
    const d = getComputedStyle(document.body).getPropertyValue('--band-d') || '48px';
    return { w: r.width, h: r.height, right: innerWidth - r.right, bg: getComputedStyle(f).backgroundColor, bandD: d.trim() };
  });
  // ui-unify-panels 批（2026-11-17）：带直径档 48 字面量退役 → --band-d
  // clamp(36px,10vw,44px)（375 视宽 = 37.5），FAB 与顶带圆钮同一把尺。
  ok('(2) bottom-right blue FAB (--band-d, = top band circle tier)', !!fab && Math.abs(fab.w - 37.5) < 1 && Math.abs(fab.h - 37.5) < 1, JSON.stringify(fab));

  await page.screenshot({ path: join(SHOTS, 'light-01-tasks.png') });

  // (3) list row clickable -> list detail page (title = tag name, row count = open count)
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(200);
  const detail = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-list"].live');
    const t = sec.querySelector('.ta-dtitle');
    return {
      title: t?.textContent,
      titleColor: t ? getComputedStyle(t).color : '',
      titleAlign: t ? getComputedStyle(t).textAlign : '',
      items: [...sec.querySelectorAll('.ta-item')].map((r) => ({
        title: r.querySelector('.ta-item-title')?.textContent,
        done: r.classList.contains('is-done'),
        prio: !!r.querySelector('.ta-prio'),
        checkW: r.querySelector('.ta-check')?.getBoundingClientRect().width,
        circleW: r.querySelector('.ta-check-circle')?.getBoundingClientRect().width,
      })),
      emptyHidden: sec.querySelector('.ta-empty')?.hidden,
    };
  });
  ok('(3) list detail title = tag name, left-aligned', detail.title === '工作' && detail.titleAlign === 'left', JSON.stringify({ t: detail.title, a: detail.titleAlign }));
  ok('(3) list detail title color = tag.color (data color)', /rgb\(0,\s*122,\s*255\)/.test(detail.titleColor), detail.titleColor);
  ok('(3) list rows render (2 open; priority mark only when priority > 0)',
    detail.items.length === 2 && detail.items[0].prio && !detail.items[1].prio, JSON.stringify(detail.items));
  ok('(3) check hit area 48 / visual 24 (>=44 / >=24 contract)',
    Math.abs(detail.items[0].checkW - 48) < 1 && Math.abs(detail.items[0].circleW - 24) < 1,
    `hit=${detail.items[0].checkW} vis=${detail.items[0].circleW}`);
  ok('(3) non-empty state: empty state hidden', detail.emptyHidden === true);
  await page.screenshot({ path: join(SHOTS, 'light-02-todo-apple-list.png') });

  // (7) checking = completeItem -> row leaves + counts move
  await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item .ta-check').first().click();
  await page.waitForTimeout(260);
  const afterComplete = await store(page, 'return { open: todoStore.openCount(), done: todoStore.completedCount() };');
  ok('(7) checking a row = todoStore.completeItem (open 3->2, completed 1->2)',
    afterComplete.open === 2 && afterComplete.done === 2, JSON.stringify(afterComplete));
  ok('(7) completed row leaves the list',
    await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item').count() === 1);

  // Back (pops one level to the home page)
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(300);
  ok('(1) back pops to the home page (stack semantics reused)',
    await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);

  // (4) empty state: complete every remaining open item -> opening the tag
  // list shows the centered "all done"
  await store(page, `for (const it of todoStore.allItems()) { if (!it.completed) todoStore.completeItem(it.id); }`);
  await page.waitForTimeout(220);
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(200);
  const empty = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-list"].live');
    const e = sec.querySelector('.ta-empty');
    const r = e.getBoundingClientRect();
    return {
      hidden: e.hidden, text: e.textContent,
      cx: Math.round(r.left + r.width / 2), cy: Math.round(r.top + r.height / 2),
      itemCount: sec.querySelectorAll('.ta-item').length,
      display: getComputedStyle(e).display,
    };
  });
  ok('(4) empty state renders ("all done" copy, no rows)', empty.hidden === false && empty.text === '全部完成' && empty.itemCount === 0, JSON.stringify(empty));
  ok('(4) empty state centered vertically and horizontally (within the page body)', Math.abs(empty.cx - VW / 2) < 24 && empty.cy > 200, JSON.stringify(empty));
  await page.screenshot({ path: join(SHOTS, 'light-03-todo-apple-empty.png') });
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(260);

  // (5)(6) create state: FAB -> item page; 3-key toolbar; type + save persists
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
  await page.waitForTimeout(300);
  const itemPage = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-item"].live');
    const tool = sec.querySelector('.ta-tool');
    const tr = tool ? tool.getBoundingClientRect() : null;
    const save = sec.querySelector('.ta-save');
    const sr = save ? save.getBoundingClientRect() : null;
    return {
      placeholder: sec.querySelector('.ta-title-input')?.placeholder,
      infoBtn: !!sec.querySelector('.ta-info'),
      toolBtns: tool ? tool.querySelectorAll('.ta-tool-btn').length : 0,
      toolIcons: tool ? [...tool.querySelectorAll('.ta-tool-btn [data-lucide]')].map((i) => i.getAttribute('data-lucide')) : [],
      toolBottom: tr ? Math.round(innerHeight - tr.bottom) : null,
      saveW: sr ? Math.round(sr.width) : null,
      saveBg: save ? getComputedStyle(save).backgroundColor : '',
      pillVisible: sec.querySelector('.ta-pill')?.hidden === false,
      pillBtns: [...(sec.querySelectorAll('.ta-pill .ta-pill-btn') || [])]
        .filter((b) => getComputedStyle(b).display !== 'none')
        .map((b) => b.getAttribute('aria-label')),
      focused: document.activeElement?.className || '',
    };
  });
  ok('(5) new row placeholder = zh-CN "new todo" entry + (i) info button', itemPage.placeholder === '新待办' && itemPage.infoBtn, JSON.stringify(itemPage));
  ok('(6) toolbar has exactly 3 icons (date/flag/tag)',
    itemPage.toolBtns === 3 && JSON.stringify(itemPage.toolIcons) === JSON.stringify(['calendar', 'flag', 'hash']),
    JSON.stringify(itemPage.toolIcons));
  ok('(6) toolbar hugs the bottom (no keyboard = resting position above the dock)', itemPage.toolBottom != null && itemPage.toolBottom < 120, `bottom=${itemPage.toolBottom}`);
  ok('(5) save = blue circular check (48 / solid sapphire fill)', itemPage.saveW === 48 && /91,\s*127,\s*191/.test(itemPage.saveBg.replace(/\s/g, ' ')), `${itemPage.saveW} ${itemPage.saveBg}`);
  ok('(5) create state caret in place (input auto-focused)', /ta-title-input/.test(itemPage.focused), itemPage.focused);
  ok('(5) create-state capsule holds only keys that can act (flag; no complete key without a target item)',
    itemPage.pillVisible === true && itemPage.pillBtns.length === 1 && itemPage.pillBtns[0] === '旗标',
    JSON.stringify(itemPage.pillBtns));
  await page.screenshot({ path: join(SHOTS, 'light-04-todo-apple-new.png') });

  // Toolbar interaction: date cycle + flag cycle (both write to the draft)
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-tool-btn').nth(0).click();
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-tool-btn').nth(1).click();
  await page.waitForTimeout(160);
  const chips = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="todo-apple-item"] .ta-chip')]
    .map((c) => c.textContent));
  ok('(6) toolbar writes take effect (date/flag/tag chips all appear)', chips.length === 3, JSON.stringify(chips));

  // Type a title + save -> createItem persists + pop
  const before = await store(page, 'return todoStore.allItems().length;');
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-title-input').fill('冒烟新建的待办');
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-save').click();
  await page.waitForTimeout(320);
  const after = await store(page, `const all = todoStore.allItems(); return { n: all.length, last: all[all.length - 1] && all[all.length - 1].title };`);
  ok('(5) create save persists (allItems grows + title correct)', after.n === before + 1 && after.last === '冒烟新建的待办', JSON.stringify({ before, after }));
  ok('(5) save pops back one level (home page live)',
    await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);

  // More menu (two real entries)
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(2).click();
  await page.waitForTimeout(160);
  const menu = await page.evaluate(() => {
    const m = document.querySelector('.mobile-page[data-page="tasks"] .ta-menu');
    return { hidden: m?.hidden, rows: [...(m?.querySelectorAll('.ta-menu-row') || [])].map((r) => r.querySelector('.ta-menu-l')?.textContent) };
  });
  ok('(2) more menu = two real entries (completed / all todos)', menu.hidden === false && menu.rows.length === 2, JSON.stringify(menu));
  await page.screenshot({ path: join(SHOTS, 'light-05-tasks-menu.png') });
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(2).click();

  // Search (local filtering is a real capability)
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(0).click();
  await page.waitForTimeout(160);
  const searchRow = await page.evaluate(() => {
    const c = document.querySelector('.mobile-page[data-page="tasks"] .ta-search-input');
    if (!c) return null;
    c.value = '冒烟';
    c.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  });
  await page.waitForTimeout(200);
  const hits = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-row-hit')]
    .map((r) => r.querySelector('.ta-row-label')?.textContent));
  ok('(2) search filters locally (real capability, not a dummy button)', searchRow === true && hits.length === 1 && hits[0] === '冒烟新建的待办', JSON.stringify(hits));

  // Dual-theme shots (dark theme must keep the cards distinct)
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    try { nav.popToRoot(); } catch { /* already at root */ }
  });
  await page.waitForTimeout(150);
  await page.emulateMedia({ colorScheme: 'dark' });
  await push(page, 'tasks');
  const darkCards = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-card')]
    .map((c) => getComputedStyle(c).backgroundColor));
  ok('(2) dark theme keeps four distinct card colors', new Set(darkCards).size === 4, darkCards.join(' | '));
  await page.screenshot({ path: join(SHOTS, 'dark-01-tasks.png') });
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.screenshot({ path: join(SHOTS, 'dark-02-todo-apple-list.png') });
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(200);
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.screenshot({ path: join(SHOTS, 'dark-03-todo-apple-new.png') });

  // Keyboard avoidance geometry (--ta-kb write channel; a real keyboard cannot
  // be simulated headless, so assert the token channel + the resting position)
  const kb = await page.evaluate(() => ({
    track: !!window.visualViewport,
    prop: getComputedStyle(document.documentElement).getPropertyValue('--ta-kb').trim(),
    cssBottom: getComputedStyle(document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-tool')).bottom,
  }));
  ok('(6) keyboard avoidance channel present (--ta-kb written from visualViewport, referenced by CSS bottom)',
    kb.prop !== '' && /--ta-kb|px/.test(kb.cssBottom), JSON.stringify(kb));

  ok('(8) zero console/page errors', consoleErrors.length === 0, consoleErrors.slice(0, 4).join(' || '));
  await browser.close();
}

await mainLeg();
console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}  —  shots: ${SHOTS}`);
process.exit(failures === 0 ? 0 : 1);
