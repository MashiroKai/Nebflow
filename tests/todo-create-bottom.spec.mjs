#!/usr/bin/env node
// todo-create-bottom.spec.mjs — mobile create-entry self-check for the two
// create faces:
//   · todo side = Apple face (this branch): the bottom-right FAB pushes the
//     full-page item editor (todo-apple-item). The 2026-11-06 "create from
//     the bottom drawer" ruling is superseded on this side by the Apple-face
//     create flow — the drawer markup no longer exists on the todo side.
//   · scheduled-task side = unchanged bottom drawer (.reminder-fab-add →
//     .mf-drawer), asserted by the S legs.
//
// Run: node tests/todo-create-bottom.spec.mjs
//   CHATFACE_WEB_ROOT=<another web tree> points the static route at a
//   pre-change tree (red-leg verification).
//
// Zero-backend technique (same family as tests/todo-mobile-edit-real.spec.mjs):
//   · static: page.route serves the web tree from disk (no port, no sbt,
//     never touches :6138);
//   · data: routeWebSocket frame stubs — todos via todoStore.__seed fixtures,
//     scheduled tasks via listScheduledTasks → scheduledTaskList frames;
//   · viewport: 375×812 / deviceScaleFactor 2 / hasTouch / isMobile
//     (body.mobile-layer active).
//
// Old-assertion dispositions (old labels kept as prefixes so the receipts
// line up; no coverage deleted without a stated replacement):
//   A-leg (todo side, repointed to the Apple face)
//     A0    repointed — tap the bottom-right .ta-fab ⇒ item editor pushed,
//           empty create draft, save key present (the drawer form check
//           folded into this).
//     A1    repointed — create entry bottom-anchored: .ta-fab-wrap right =
//           page − var(--mpad-x), bottom = page − 16px; the editor fills the
//           shell (shell bottom = dock top, shell top = 0). Same "entry sits
//           at the page bottom" intent, new carrier.
//     A2a-d repointed — pushing a stacked page must not relayout the covered
//           home page: first row / dock / list card / document scroll all
//           unchanged while the editor is live.
//     A3    repointed — the editor does not grow the covered page (page body
//           and list inner scrollHeight unchanged).
//     A4    retired with the drawer: the stacked page swap is a visibility
//           toggle, no transition leg left to sample (mid-flight transform
//           frames gone); replaced by a final-geometry check (shell transform
//           none / opacity 1).
//     A5    repointed — single create implementation: exactly one .ta-fab,
//           zero drawer and zero legacy .tfl-* markup on the tasks page.
//     A6    repointed — home capsule trio (search / new list / more) replaces
//           the old top menu; old top controls all zero.
//     A7    repointed, split in two — back button = discard path (no store
//           write, home restored); save key = the real createItem write +
//           pop. Both close paths still proven.
//     A7b   repointed — the scrim retired with the drawer; the second entry
//           (the list-page create pill) opens the same editor instead
//           ("two entries, one editor").
//     G0-G3 repointed to the editor title input (.ta-title-input): reading
//           contract, pointer + keyboard legs, light and dark themes.
//   S-leg (scheduled-task side, unchanged): S0-S5 as before — drawer opens
//     from the fab, bottom-anchored on the page shell, single implementation,
//     liquid-morph slow transition, zero shift, closes back to place.

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// Expected strings come from the liquid-morph single-source module's exports
// (change one place, both sides stay in sync) — used by the S-leg drawer.
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

/* ───────────── fixtures ───────────── */
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
/** Scheduled-task fixtures (same shape as mobile-p3-reminder: 4 rows listed). */
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

/* ───────────── zero-backend wire ───────────── */
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

/* ───────────── reading scripts ───────────── */
/* Three readings: first-row rect / dock / list scroll region / document
   scroll axis / per-layer scrollHeight (page-growth criterion). */
const READ = (pageSel, listSel, rowSel) => `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
             top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2) }; };
  const page = document.querySelector(${JSON.stringify(pageSel)});
  const list = page && page.querySelector(${JSON.stringify(listSel)});
  const se = document.scrollingElement;
  const body = page && page.querySelector('.mobile-page-body');
  const inner = page && page.querySelector('.ta-rows, .reminder-panel-body');
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

/* Expected strings = the single-source module's own output (never re-typed
   from CSS). Only the S-leg drawer still carries a transition to compare. */
const WANT = morphTransition(['transform', 'opacity'], { duration: 'slow' });
const WANT_CSS = (() => {
  // getComputedStyle contract: unit-less ms normalize (400ms → "0.4s"),
  // easing verbatim, comma followed by a space.
  const dur = `${DURATION.slow / 1000}s`;
  return { dur: `${dur}, ${dur}`, ease: `${EASING}, ${EASING}` };
})();

function transitionIsLiquid(t) {
  if (!t) return false;
  const props = t.props.split(',').map((s) => s.trim());
  return props.length === 2 && props.every((p) => p === 'transform' || p === 'opacity')
    && t.dur === WANT_CSS.dur && t.ease === WANT_CSS.ease;
}

/* ───────────── main flow ───────────── */
const browser = await chromium.launch();
const errors = [];
const readings = { media: { DURATION, EASING, want: WANT } };

try {
  /* ══════ Todo side 375×812 · light theme ══════ */
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await boot(page);
  await page.evaluate(async ({ tags, its }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: its });
  }, { tags: TAGS, its: items() });
  await page.waitForTimeout(200);

  // Real chain: mobile chrome task button → navController page switch.
  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(800);
  await page.waitForSelector(".mobile-page[data-page='tasks'] .ta-row-all", { timeout: 8000 });

  const PAGE = ".mobile-page[data-page='tasks']";
  const ITEM = ".mobile-page[data-page='todo-apple-item']";
  const LIST_PG = ".mobile-page[data-page='todo-apple-list']";
  const LIST = '.ta-listcard';
  const ROW = '.ta-row-all';
  const FAB = `${PAGE} .ta-fab`;
  const TITLE = `${ITEM} .ta-title-input`;
  const waitLive = (sel) => page.waitForFunction(
    (s) => { const el = document.querySelector(s); return !!el && el.classList.contains('live'); },
    sel, { timeout: 8000 },
  );
  const storeCount = () => page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    return todoStore.visibleItems().length;
  });

  /* ── A5 / A6 static shape: single create entry + legacy remnants zero ── */
  const staticShape = {
    fab: await page.locator(FAB).count(),
    fabWrap: await page.locator(`${PAGE} .ta-fab-wrap`).count(),
    pillBtns: await page.locator(`${PAGE} .ta-pill .ta-pill-btn`).count(),
    drawers: await page.locator(`${PAGE} .mf-drawer`).count(),
    drawerTop: await page.locator('.mf-drawer-top').count(),
    legacy: {
      row: await page.locator(`${PAGE} .tfl-row`).count(),
      fabAdd: await page.locator(`${PAGE} .tfl-fab-add`).count(),
      createRow: await page.locator(`${PAGE} .tfl-create-row`).count(),
      topmenu: await page.locator(`${PAGE} .tfl-topmenu-btn`).count(),
      filter: await page.locator(`${PAGE} .tfl-filter-toggle`).count(),
      collapse: await page.locator(`${PAGE} .tfl-collapse-btn`).count(),
      chips: await page.locator(`${PAGE} .tfl-chips-row`).count(),
      prio: await page.locator(`${PAGE} .tfl-prio-row`).count(),
    },
  };
  ok('A5 single create implementation: exactly one bottom-right .ta-fab; drawer and legacy .tfl-* markup all zero on the tasks page',
    staticShape.fab === 1 && staticShape.fabWrap === 1 && staticShape.drawers === 0
    && staticShape.drawerTop === 0 && Object.values(staticShape.legacy).every((n) => n === 0),
    JSON.stringify(staticShape));
  ok('A6 home capsule trio present (search / new list / more) — the old top control row is fully gone',
    staticShape.pillBtns === 3, `pillBtns=${staticShape.pillBtns}`);

  const countBefore = await storeCount();

  /* ── Readings before the push ── */
  const before = await page.evaluate(READ(PAGE, LIST, ROW));

  /* A0: the bottom-right FAB is the single create entry; it pushes the
     full-page item editor (the drawer form is retired). */
  await page.locator(FAB).click();
  await waitLive(ITEM);
  await page.waitForSelector(TITLE, { timeout: 5000 });
  await page.waitForTimeout(150);
  const openState = await page.evaluate(VISIBLE(TITLE));
  const titleVal = await page.locator(TITLE).inputValue();
  const saveVisible = await page.evaluate(VISIBLE(`${ITEM} .ta-save`));
  ok('A0 tapping the bottom-right FAB pushes the item editor: empty create draft + save key present',
    openState === true && titleVal === '' && saveVisible === true,
    `visible=${openState} title='${titleVal}' save=${saveVisible}`);

  /* A1 bottom anchor: the FAB wrap sits at right = page − var(--mpad-x) /
     bottom = page − 16px inside the page shell; the editor page fills the
     shell down to the dock. Values read from CSS vars at runtime, never
     re-typed here. */
  const fabRect = await page.evaluate(RECT_OF(`${PAGE} .ta-fab-wrap`));
  const pageRect = await page.evaluate(RECT_OF(PAGE));
  const itemRect = await page.evaluate(RECT_OF(ITEM));
  const dockRect = await page.evaluate(RECT_OF('#mobile-dock'));
  const padX = await page.evaluate((sel) => {
    const pageEl = document.querySelector(sel);
    return parseFloat(getComputedStyle(pageEl).getPropertyValue('--mpad-x')) || 0;
  }, PAGE);
  const fabRight = fabRect ? fabRect.x + fabRect.w : null;
  const pageRight = pageRect ? pageRect.x + pageRect.w : null;
  ok('A1 create entry is bottom-anchored (FAB wrap right = page − var(--mpad-x), bottom = page − 16px) and the editor fills the shell (bottom = dock top, top = 0)',
    near(fabRight, pageRight - padX, 1) && near(fabRect?.bottom, pageRect?.bottom - 16, 1)
    && near(itemRect?.bottom, dockRect?.top, 1.5) && near(itemRect?.top, 0, 1),
    `fab=${JSON.stringify(fabRect)} padX=${padX} item=${JSON.stringify(itemRect)} dock=${JSON.stringify(dockRect)}`);

  /* A2/A3: pushing a stacked page must not relayout the covered home page. */
  const during = await page.evaluate(READ(PAGE, LIST, ROW));
  ok('A2a home first row zero shift (before → editor open, ≤0.5px)',
    near(before.firstRow?.x, during.firstRow?.x) && near(before.firstRow?.y, during.firstRow?.y)
    && near(before.firstRow?.w, during.firstRow?.w) && near(before.firstRow?.h, during.firstRow?.h),
    `before=${JSON.stringify(before.firstRow)} open=${JSON.stringify(during.firstRow)}`);

  ok('A2b dock (#mobile-dock) position unchanged',
    same(before.dock, during.dock), `${JSON.stringify(before.dock)} → ${JSON.stringify(during.dock)}`);

  ok('A2c list card scrollTop/scrollHeight/clientHeight unchanged',
    before.list && during.list && before.list.top === during.list.top
    && before.list.sh === during.list.sh && before.list.ch === during.list.ch,
    `${JSON.stringify(before.list)} → ${JSON.stringify(during.list)}`);

  ok('A2d document.scrollingElement scrollTop/scrollHeight constant + window.scrollY 0',
    before.doc && during.doc && before.doc.top === during.doc.top && before.doc.sh === during.doc.sh
    && before.winY === during.winY,
    `${JSON.stringify(before.doc)} → ${JSON.stringify(during.doc)} / winY ${before.winY}→${during.winY}`);

  ok('A3 the editor does not grow the covered page (page body and list inner scrollHeight unchanged)',
    same(before.heights, during.heights), `${JSON.stringify(before.heights)} → ${JSON.stringify(during.heights)}`);

  /* A4 (repointed): the stacked page swap is a visibility toggle — the editor
     presents at final geometry. The drawer-style transition legs (slow
     liquid-morph string, mid-flight transform sampling) are retired with the
     drawer; this final-geometry check is the replacement. */
  const trItem = await page.evaluate(TRANSITION_OF(ITEM));
  ok('A4 editor presents at final geometry: shell transform none / opacity 1 (page swap has no transition leg)',
    trItem && (trItem.transform === 'none' || trItem.transform === 'matrix(1, 0, 0, 1, 0, 0)') && trItem.opacity === '1',
    JSON.stringify({ tf: trItem?.transform, op: trItem?.opacity }));

  /* ── G focus-ring checks (pointer + keyboard; light theme) on the editor
     title input ── */
  const titleSel = TITLE;
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
  // Judging contract (not fooled by engine-normalized "exactly 0px" readings):
  //   · ring = author-drawn outline ⇒ `outlineStyle` may only be `none` (no
  //     outline) or `auto` (browser UA default, author wrote nothing); any
  //     solid/dashed/… means someone drew a ring = red.
  //   · border = the four longhand colors (`borderColor` is a first-edge
  //     alias; on a border-style:none input it reads the UA currentColor, not
  //     an author value ⇒ not usable as a criterion).
  const noRing = (s) => !!s && (s.outlineStyle === 'none' || s.outlineStyle === 'auto');
  const noVisibleBorder = (s) => !!s && s.borderWidths.every((w) => parseFloat(w) === 0 || /rgba\(0, 0, 0, 0\)|transparent/.test(s.borderColors[0]));
  const blueish = (s) => !!s && /rgb\((\d+), (\d+), (\d+)\)/.test(s.outlineColor || '')
    && (() => { const m = /rgb\((\d+), (\d+), (\d+)\)/.exec(s.outlineColor); return +m[3] > +m[1] + 40 && +m[3] > +m[2] + 20; })();
  ok('G0 reading contract: UA default focus ring is not blue (blue = author ring; outlineStyle may only be none/auto)',
    focusPointer ? !(focusPointer.outlineStyle === 'auto' && blueish(focusPointer)) : false,
    JSON.stringify({ style: focusPointer?.outlineStyle, color: focusPointer?.outlineColor }));
  ok('G1 title input has zero blue ring (pointer path: author outline none / four widths 0 / not blue)',
    noRing(focusPointer) && noVisibleBorder(focusPointer) && !blueish(focusPointer) && focusPointer.active,
    JSON.stringify(focusPointer));

  /* Screenshot (evidence): editor open, light theme. */
  await page.screenshot({ path: join(OUT, 'create-bottom-todo.png') });
  const geoShot = await page.evaluate(RECT_OF(ITEM));

  // Keyboard leg: blur first, then Tab in (focusModality flips to keyboard).
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await sleep(80);
  await page.keyboard.press('Tab');
  await sleep(120);
  const focusKeyboard = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    const cs = el ? getComputedStyle(el) : null;
    return cs ? {
      active: document.activeElement === el,
      modality: document.documentElement.getAttribute('data-nf-modality'),
      tag: document.activeElement ? document.activeElement.className : null,
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor,
      borderWidths: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth],
      borderColors: [cs.borderTopColor, cs.borderRightColor, cs.borderBottomColor, cs.borderLeftColor],
    } : null;
  }, titleSel);
  ok('G2 keyboard Tab leg: ring comes from the inputSurface token family (≤2px inset grey ring) or zero ring — never the old full blue circle',
    !!focusKeyboard && (
      (focusKeyboard.active && parseFloat(focusKeyboard.outlineWidth) <= 2)
      || (!focusKeyboard.active && noRing(focusKeyboard))
    ) && !(focusKeyboard?.active && blueish(focusKeyboard) && parseFloat(focusKeyboard.outlineWidth) > 2),
    JSON.stringify(focusKeyboard));

  /* ── A7 discard path: back button pops without a store write ── */
  await page.locator(`${ITEM} .mobile-back-btn`).click();
  await waitLive(PAGE);
  await page.waitForTimeout(400);
  const countAfterBack = await storeCount();
  const after = await page.evaluate(READ(PAGE, LIST, ROW));
  ok('A7 back button closes the editor without writing (create discarded; tasks page live again)',
    countAfterBack === countBefore, `store count ${countBefore} → ${countAfterBack}`);

  ok('A2e home first row back at its place (before → after close, ≤0.5px)',
    near(before.firstRow?.x, after.firstRow?.x) && near(before.firstRow?.y, after.firstRow?.y)
    && near(before.firstRow?.w, after.firstRow?.w) && near(before.firstRow?.h, after.firstRow?.h),
    `before=${JSON.stringify(before.firstRow)} closed=${JSON.stringify(after.firstRow)}`);

  ok('A2f dock / list card / document scroll all identical to before (values + heights)',
    same(before.dock, after.dock) && same(before.list, after.list) && same(before.doc, after.doc)
    && before.winY === after.winY && same(before.heights, after.heights),
    `dock ${JSON.stringify(after.dock)} list ${JSON.stringify(after.list)} doc ${JSON.stringify(after.doc)} h ${JSON.stringify(after.heights)}`);

  /* ── A7 save path: the save key is the real createItem write ── */
  await page.locator(FAB).click();
  await waitLive(ITEM);
  await page.waitForSelector(TITLE, { timeout: 5000 });
  await page.locator(TITLE).fill('底部创建回归');
  await page.locator(`${ITEM} .ta-save`).click();
  await waitLive(PAGE);
  await page.waitForTimeout(300);
  const saved = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const it = todoStore.visibleItems().find((x) => x.name === '底部创建回归');
    return it ? { name: it.name, priority: it.priority, completed: it.completed } : null;
  });
  const countAfterSave = await storeCount();
  ok('A7-save save key writes the draft through createItem and pops back',
    saved !== null && countAfterSave === countBefore + 1,
    `saved=${JSON.stringify(saved)} count ${countBefore} → ${countAfterSave}`);

  /* ── A7b (repointed) second entry: the list-page create pill reaches the
     same editor (single editor, two entries; the scrim is retired) ── */
  await page.locator(`${PAGE} ${ROW}`).click();
  await waitLive(LIST_PG);
  await page.waitForSelector(`${LIST_PG} .ta-item`, { timeout: 5000 });
  const pillCount = await page.locator(`${LIST_PG} .ta-pill-btn`).count();
  await page.locator(`${LIST_PG} .ta-pill-btn`).click();
  await waitLive(ITEM);
  await page.waitForSelector(TITLE, { timeout: 5000 });
  const secondEntry = await page.evaluate(VISIBLE(TITLE));
  ok('A7b the list-page create pill opens the same editor (single editor, two entries)',
    pillCount === 1 && secondEntry === true, `pill=${pillCount} editorVisible=${secondEntry}`);
  await page.locator(`${ITEM} .mobile-back-btn`).click();
  await waitLive(LIST_PG);
  await page.locator(`${LIST_PG} .mobile-back-btn`).click();
  await waitLive(PAGE);
  await page.waitForTimeout(300);

  readings.todo = {
    before, during, after, fabRect, pageRect, itemRect, dockRect, padX,
    focusPointer, focusKeyboard, staticShape, geoShot, saved,
    counts: { before: countBefore, afterBack: countAfterBack, afterSave: countAfterSave },
  };

  /* ── G3 dark theme recheck (both themes must read zero blue ring) ── */
  await page.emulateMedia({ colorScheme: 'dark' });
  await sleep(150);
  await page.locator(FAB).click();
  await waitLive(ITEM);
  await page.waitForSelector(TITLE, { timeout: 5000 });
  await sleep(200);
  await page.locator(TITLE).click();
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
  ok('G3 dark theme: title input blue ring likewise zero',
    noRing(focusDark) && noVisibleBorder(focusDark) && !blueish(focusDark), JSON.stringify(focusDark));
  readings.todo.focusDark = focusDark;
  await page.locator(`${ITEM} .mobile-back-btn`).click();
  await sleep(400);
  await page.emulateMedia({ colorScheme: 'light' });

  await ctx.close();

  /* ══════ Scheduled-task page 375×812 (same-family assertions) ══════ */
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
  // The drawer hangs on the page shell (.mobile-page) — same containing
  // block family as the todo side; only the plus button lives in the page body.
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
  ok('S0 scheduled page: plus opens the drawer (visible), hosted on the page shell',
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

  ok('S1 scheduled page: drawer hugs the page bottom (box bottom = shell bottom ≤0.5px) with the form in the drawer body',
    near(sBox?.bottom, sPage?.bottom) && sShape.body + sShape.inlineRows >= 1,
    `box=${JSON.stringify(sBox)} page=${JSON.stringify(sPage)} shape=${JSON.stringify(sShape)}`);
  ok('S2 scheduled page: .mf-drawer-top zero remnants (one shared bottom drawer)',
    sShape.drawerTop === 0 && sShape.drawers === 1, JSON.stringify(sShape));
  ok('S3 scheduled page: open/close transition string is the same liquid-morph slow preset (transform+opacity / single easing)',
    transitionIsLiquid(sTr), JSON.stringify({ got: sTr, want: WANT }));
  ok('S4 scheduled page: zero shift on open (first row rect / dock / list scroll region / document scroll axis / per-layer scrollHeight unchanged)',
    near(sBefore.firstRow?.x, sDuring.firstRow?.x) && near(sBefore.firstRow?.y, sDuring.firstRow?.y)
    && same(sBefore.dock, sDuring.dock) && same(sBefore.list, sDuring.list) && same(sBefore.doc, sDuring.doc)
    && same(sBefore.heights, sDuring.heights),
    `row ${JSON.stringify(sBefore.firstRow)}→${JSON.stringify(sDuring.firstRow)} / list ${JSON.stringify(sBefore.list)}→${JSON.stringify(sDuring.list)} / h ${JSON.stringify(sBefore.heights)}→${JSON.stringify(sDuring.heights)}`);

  await sp.screenshot({ path: join(OUT, 'create-bottom-scheduled.png') });
  await sp.locator(`${SDRAWER} .mf-drawer-cancel-btn`).click();
  await sp.waitForTimeout(700);
  const sAfter = await sp.evaluate(READ(SPAGE, SLIST, SROW));
  ok('S5 scheduled page: after close the first row / dock are back in place and the drawer is hidden',
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
