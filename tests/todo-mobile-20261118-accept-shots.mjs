#!/usr/bin/env node
// tests/todo-mobile-20261118-accept-shots.mjs — acceptance harness for the
// 2026-11-18 mobile-todo batch (author brief: 8 interface items).
//
// Runs the SAME probes against the baseline worktree and the feature worktree
// so every reading is an honest before/after pair; emits readings-<variant>.json
// plus screenshots and (in gif scenarios) a webm to be turned into a GIF.
//
// Usage:
//   node tests/todo-mobile-20261118-accept-shots.mjs --variant=feat|base --scenario=read
//   node tests/todo-mobile-20261118-accept-shots.mjs --variant=feat --scenario=gif-sheet
//           [--out=<dir>]
// Scenarios: read | gif-sheet | gif-date | gif-tag | gif-reply
//
// Standalone node script (boots its own chromium; not part of the CI collection
// set — playwright.config.mjs testIgnore excludes non-spec helpers).

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const argv = process.argv.slice(2);
const arg = (k, d) => (argv.find((a) => a.startsWith(`--${k}=`)) || `--${k}=${d}`).split('=').slice(1).join('=');
const VARIANT = arg('variant', 'feat');
const SCENARIO = arg('scenario', 'read');
const OUT = arg('out', join(HERE, '..', 'evidence', `todo-mobile-20261118-${VARIANT}`));
const SHOTS = join(OUT, 'shots');
const VIDEO = join(OUT, 'video');
mkdirSync(SHOTS, { recursive: true });
mkdirSync(VIDEO, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mobile-todo-accept-root';
const ORIGIN = 'http://localhost:8898';
const VW = 375;
const VH = 812;

const readings = { variant: VARIANT, scenario: SCENARIO, viewport: { w: VW, h: VH }, steps: [] };
const note = (name, value) => {
  readings.steps.push({ name, value });
  console.log(`· ${name}: ${typeof value === 'string' ? value : JSON.stringify(value)}`);
};

async function boot({ record = false } = {}) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: 'light',
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
    ...(record ? { recordVideo: { dir: VIDEO, size: { width: VW, height: VH } } } : {}),
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|ResizeObserver loop/i.test(m.text())) errors.push(`console.error: ${m.text()}`); });
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
  await page.waitForTimeout(400);
  return { browser, page, errors };
}

const LONG_TITLE = '写一份特别长的周报标题用来验证详情页标题能不能完整显示不要被截断也不要出现省略号';
const LONG_BODY = Array.from({ length: 12 }, (_, i) => `第${i + 1}行：正文也要能完整显示，长正文不应被容器高度截断，也不应出现内部滚动条把内容藏起来。`).join('\n');

const seed = (page) => page.evaluate(async (payload) => {
  const m = await import('/js/todoStore.js');
  const d = new Date(); d.setHours(23, 0, 0, 0);
  m.todoStore.__seed({
    tagList: [
      { id: 'tw', name: '工作', color: 'rgb(0,122,255)' },
      { id: 'tl', name: '生活', color: 'rgb(77,217,102)' },
      { id: 'ts', name: '学习', color: 'rgb(255,149,0)' },
    ],
    itemList: [
      { id: 'long-1', tagId: 'tw', title: payload.longTitle, notes: payload.longBody, due: null, priority: 0, completed: false },
      { id: 'short-1', tagId: 'tl', title: '买菜', notes: '两斤西红柿', due: null, priority: 0, completed: false },
    ],
  });
}, { longTitle: LONG_TITLE, longBody: LONG_BODY });

const push = async (page, id) => {
  await page.evaluate(async (pid) => { const nav = await import('/js/mobile/navController.js'); nav.pushPage(pid); }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(260);
};
const popToRoot = (page) => page.evaluate(async () => {
  const nav = await import('/js/mobile/navController.js');
  try { nav.popToRoot(); } catch { /* already at root */ }
  await new Promise((r) => setTimeout(r, 120));
});

/** A point on the home page whose hit-test lands on plain background (no pill / menu / row / card). */
const blankPoint = (page) => page.evaluate(() => {
  const bad = '.ta-pill,.ta-menu,.ta-searchbar,.ta-row-new,.ta-row,.ta-card,.ta-fab,.ta-item,.mobile-topbar,#mobile-dock,button,input,textarea,a,[role=button]';
  for (let y = 90; y < window.innerHeight - 120; y += 6) {
    for (let x = 12; x < window.innerWidth - 12; x += 6) {
      const el = document.elementFromPoint(x, y);
      if (!el || el.closest(bad)) continue;
      const page = el.closest('.mobile-page');
      if (!page || !page.classList.contains('live')) continue;
      return { x, y, hit: el.className || el.tagName };
    }
  }
  return null;
});

const framesStart = (page) => page.evaluate(() => {
  window.__frames = [];
  let last = performance.now();
  const tick = (t) => { window.__frames.push(t - last); last = t; window.__raf = requestAnimationFrame(tick); };
  window.__raf = requestAnimationFrame(tick);
});
const framesStop = (page) => page.evaluate(() => {
  cancelAnimationFrame(window.__raf);
  const f = window.__frames.slice(1);
  const sorted = [...f].sort((a, b) => a - b);
  const q = (p) => sorted.length ? Math.round(sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))] * 100) / 100 : null;
  return {
    frames: f.length,
    p50ms: q(0.5),
    p95ms: q(0.95),
    maxMs: sorted.length ? Math.round(sorted[sorted.length - 1] * 100) / 100 : null,
    over24ms: f.filter((x) => x > 24).length,
    totalMs: Math.round(f.reduce((a, b) => a + b, 0)),
  };
});

async function readScenario(page) {
  await seed(page);
  await push(page, 'tasks');
  await page.screenshot({ path: join(SHOTS, 'read-01-home.png') });

  // ---- (③) create sheet geometry: does the dock bite into the sheet? ----
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.ta-create-overlay .ta-create-sheet', { timeout: 10000 });
  // live-measured entry motion (what the browser actually runs, not what the CSS text says)
  note('create-sheet-animations', await page.evaluate(() => [...document.querySelectorAll('.ta-create-sheet')].flatMap((n) => n.getAnimations().map((a) => ({ name: a.animationName || a.transitionProperty, dur: a.effect.getTiming().duration, ease: a.effect.getTiming().easing, state: a.playState })))));
  await page.waitForTimeout(260);
  note('create-sheet-geom', await page.evaluate(() => {
    const sheet = document.querySelector('.ta-create-sheet');
    const dock = document.getElementById('mobile-dock');
    const s = sheet.getBoundingClientRect();
    const d = dock.getBoundingClientRect();
    const cs = getComputedStyle(sheet);
    return {
      sheet: { top: Math.round(s.top), bottom: Math.round(s.bottom), h: Math.round(s.height) },
      dock: { top: Math.round(d.top), bottom: Math.round(d.bottom) },
      overlapPx: Math.round(Math.max(0, s.bottom - d.top)),
      sheetPaddingBottom: cs.paddingBottom,
      sheetMaxHeight: cs.maxHeight,
      viewportH: window.innerHeight,
    };
  }));
  await page.screenshot({ path: join(SHOTS, 'read-02-create-sheet.png') });

  // blank tap above the sheet closes it (overlay hit-test)
  const overlayBlank = await page.evaluate(() => {
    const o = document.querySelector('.ta-create-overlay');
    const s = document.querySelector('.ta-create-sheet');
    const ob = o.getBoundingClientRect();
    const sb = s.getBoundingClientRect();
    const x = Math.round(ob.left + ob.width / 2);
    const y = Math.round(ob.top + Math.max(8, (sb.top - ob.top) / 2));
    const el = document.elementFromPoint(x, y);
    return { x, y, hit: el ? (el.className || el.tagName) : null, isOverlay: el === o };
  });
  note('create-sheet-overlay-blank-point', overlayBlank);
  if (overlayBlank.isOverlay) {
    await page.mouse.click(overlayBlank.x, overlayBlank.y);
    await page.waitForTimeout(300);
    note('create-sheet-closed-by-blank-tap', await page.locator('.ta-create-overlay').count() === 0);
  } else {
    note('create-sheet-closed-by-blank-tap', 'n/a (no overlay blank point)');
  }
  if (await page.locator('.ta-create-overlay').count()) {
    await page.locator('.ta-create-cancel').click();
    await page.waitForTimeout(200);
  }

  // ---- (②) home panels: does a blank tap close them? ----
  const bp = await blankPoint(page);
  note('home-blank-point', bp);
  for (const [label, pillIdx, probe] of [
    ['more-menu', 2, '.ta-menu'],
    ['searchbar', 0, '.ta-searchbar'],
    ['new-list-row', 1, '.ta-row-new'],
  ]) {
    // btn index: [search, new-list, more] (see the pill assembly in todoAppleFace)
    await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(pillIdx).click();
    if (label === 'more-menu') {
      note('more-menu-animations', await page.evaluate(() => [...document.querySelectorAll('.ta-menu')].flatMap((n) => n.getAnimations().map((a) => ({ name: a.animationName || a.transitionProperty, dur: a.effect.getTiming().duration, ease: a.effect.getTiming().easing, state: a.playState })))));
    }
    await page.waitForTimeout(180);
    const opened = await page.evaluate((sel) => {
      const n = document.querySelector(sel);
      return n ? { hidden: n.hidden, display: getComputedStyle(n).display } : null;
    }, probe);
    if (bp) {
      await page.mouse.click(bp.x, bp.y);
      await page.waitForTimeout(260);
    }
    const closed = await page.evaluate((sel) => {
      const n = document.querySelector(sel);
      return n ? { hidden: n.hidden, display: getComputedStyle(n).display } : null;
    }, probe);
    note(`blank-tap-${label}`, { opened, afterBlankTap: closed, closes: !!closed && (closed.hidden === true || closed.display === 'none') });
    // unconditional cleanup (baseline does not close on blank tap)
    const stillOpen = await page.evaluate((sel) => { const n = document.querySelector(sel); return !!n && !n.hidden && getComputedStyle(n).display !== 'none'; }, probe);
    if (stillOpen) {
      await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(pillIdx).click();
      await page.waitForTimeout(180);
    }
  }

  // ---- (⑦) detail page: title + body fully displayed? ----
  await page.locator('.ta-row.ta-row-tag', { hasText: '工作' }).first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.locator(".ta-item[data-item-id='long-1'] .ta-item-body").click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
  await page.waitForTimeout(320);
  const detail = await page.evaluate(() => {
    const page_ = document.querySelector('.mobile-page[data-page="todo-apple-item"]');
    const measure = (n) => {
      if (!n) return null;
      const cs = getComputedStyle(n);
      const box = n.getBoundingClientRect();
      return {
        cls: n.className,
        h: Math.round(box.height),
        clientH: n.clientHeight,
        scrollH: n.scrollHeight,
        clipped: n.scrollHeight > n.clientHeight + 1,
        overflowY: cs.overflowY,
        maxHeight: cs.maxHeight,
        whiteSpace: cs.whiteSpace,
        ellipsis: cs.textOverflow,
        lines: Math.round(n.scrollHeight / parseFloat(cs.lineHeight || '20')),
        valueLen: typeof n.value === 'string' ? n.value.length : null,
        valueTail: typeof n.value === 'string' ? n.value.slice(-14) : null,
      };
    };
    const notesEl = page_.querySelector('.ta-notes');
    return {
      title: measure(page_.querySelector('.ta-title-input')),
      notes: measure(notesEl),
      notesHidden: notesEl ? notesEl.hidden : null,
      bodyScroll: { scrollH: page_.querySelector('.ta-body')?.scrollHeight, clientH: page_.querySelector('.ta-body')?.clientHeight },
    };
  });
  note('detail-long-text', detail);
  await page.screenshot({ path: join(SHOTS, 'read-03-detail-long.png') });

  // ---- (⑧) reply composer: template signature + autosize ----
  const reply = await page.evaluate(() => {
    const wrap = document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply');
    if (!wrap) return null;
    const ta = wrap.querySelector('textarea');
    const btns = [...wrap.querySelectorAll('button')].map((b) => ({ cls: b.className, w: Math.round(b.getBoundingClientRect().width), h: Math.round(b.getBoundingClientRect().height) }));
    return {
      wrapCls: wrap.className,
      children: [...wrap.children].map((c) => c.className),
      inputCls: ta ? ta.className : null,
      glass: !!ta && ta.classList.contains('nf-glass'),
      glassHost: !!wrap.querySelector('.nf-glass'),
      textarea: !!ta,
      buttons: btns,
      h: Math.round((ta || wrap).getBoundingClientRect().height),
    };
  });
  note('reply-composer-signature', reply);
  const growth = await page.evaluate(() => {
    const ta = document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply textarea');
    if (!ta) return null;
    const before = Math.round(ta.getBoundingClientRect().height);
    ta.value = '第一行\n第二行\n第三行\n第四行';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    return new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => {
      const after = Math.round(ta.getBoundingClientRect().height);
      r({ before, after, grew: after > before, clipped: ta.scrollHeight > ta.clientHeight + 1 });
    })));
  });
  note('reply-autosize', growth);
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-reply').scrollIntoViewIfNeeded();
  await page.waitForTimeout(260);
  await page.screenshot({ path: join(SHOTS, 'read-04-reply.png') });
  note('reply-box-box', await page.evaluate(() => {
    const w = document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply').getBoundingClientRect();
    const d = document.getElementById('mobile-dock').getBoundingClientRect();
    return { top: Math.round(w.top), bottom: Math.round(w.bottom), h: Math.round(w.height), dockTop: Math.round(d.top), inViewport: w.top >= 0 && w.bottom <= window.innerHeight };
  }));

  // ---- (④⑤⑥) toolbar keys: what does each open? ----
  const dateKey = await page.evaluate(async () => {
    const page_ = document.querySelector('.mobile-page[data-page="todo-apple-item"]');
    const keys = [...page_.querySelectorAll('.ta-tool .ta-tool-btn, .ta-tool button')];
    const before = [...page_.querySelectorAll('.ta-chips .ta-chip')].map((c) => c.textContent);
    keys[0]?.click();
    await new Promise((r) => setTimeout(r, 300));
    const panel = document.querySelector('.tw-panel');
    return {
      keys: keys.map((k) => k.getAttribute('data-icon') || k.className),
      pickerOpened: !!panel && panel.classList.contains('in'),
      pickerSheet: !!panel && panel.classList.contains('sheet'),
      bodyPointVisible: !!document.querySelector('.tw-body-point') && getComputedStyle(document.querySelector('.tw-body-point')).display !== 'none',
      chipsBefore: before,
    };
  });
  note('date-key', dateKey);
  if (dateKey.pickerOpened) await page.screenshot({ path: join(SHOTS, 'read-05-date-picker.png') });
  // close the picker: Esc (timeWheel outsideCloser/Escape) then fall back to its confirm
  await page.keyboard.press('Escape');
  await page.waitForTimeout(260);
  if (await page.evaluate(() => !!document.querySelector('.tw-panel.in'))) {
    await page.evaluate(() => document.querySelector('.tw-panel.in .tw-ok')?.click());
    await page.waitForTimeout(260);
  }
  note('date-key-chips-after', await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="todo-apple-item"] .ta-chips .ta-chip')].map((c) => c.textContent)));

  const tagKey = await page.evaluate(async () => {
    const page_ = document.querySelector('.mobile-page[data-page="todo-apple-item"]');
    const keys = [...page_.querySelectorAll('.ta-tool .ta-tool-btn, .ta-tool button')];
    keys[2]?.click();
    await new Promise((r) => setTimeout(r, 300));
    const menu = document.querySelector('.fm-context-menu');
    return {
      menuOpened: !!menu,
      rows: menu ? [...menu.querySelectorAll('button')].map((b) => ({ label: b.textContent, cls: b.className })) : [],
    };
  });
  note('tag-key', tagKey);
  if (tagKey.menuOpened) await page.screenshot({ path: join(SHOTS, 'read-06-tag-menu.png') });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);

  // ---- (①) frame pacing: sheet open + list push ----
  await popToRoot(page);
  await push(page, 'tasks');
  await framesStart(page);
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.ta-create-overlay .ta-create-sheet', { timeout: 8000 });
  await page.waitForTimeout(420);
  note('frames-create-sheet-open', await framesStop(page));
  await page.locator('.ta-create-cancel').click();
  await page.waitForTimeout(300);

  await push(page, 'todo-apple-list');
  await page.waitForTimeout(200);
  await popToRoot(page);
  await push(page, 'tasks');
  await framesStart(page);
  await page.locator('.ta-row.ta-row-tag').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 8000 });
  await page.waitForTimeout(420);
  note('frames-list-push', await framesStop(page));

  // ---- (⑧) canvas ask regression: the dock composer still builds from the template ----
  const ask = await page.evaluate(async () => {
    const doc = await import('/js/askDock.js');
    return { exports: Object.keys(doc) };
  });
  note('askdock-exports', ask.exports);

  await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch {} });
  await page.waitForTimeout(200);
}

async function gifScenario(page, name) {
  await seed(page);
  await push(page, 'tasks');
  if (name === 'gif-sheet') {
    await page.waitForTimeout(300);
    await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
    await page.waitForTimeout(1400);
    await page.screenshot({ path: join(SHOTS, 'gif-sheet-open.png') });
    const bp = await page.evaluate(() => {
      const o = document.querySelector('.ta-create-overlay');
      const s = document.querySelector('.ta-create-sheet');
      const ob = o.getBoundingClientRect(); const sb = s.getBoundingClientRect();
      return { x: Math.round(ob.left + ob.width / 2), y: Math.round(ob.top + (sb.top - ob.top) / 2) };
    });
    await page.mouse.click(bp.x, bp.y);
    await page.waitForTimeout(1200);
  } else if (name === 'gif-menu') {
    await page.waitForTimeout(300);
    await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(2).click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: join(SHOTS, 'gif-menu-open.png') });
    const bp = await page.evaluate(() => {
      const bad = '.ta-pill,.ta-menu,.ta-searchbar,.ta-row-new,.ta-row,.ta-card,.ta-fab,#mobile-dock,button,input,textarea,a,[role=button]';
      for (let y = 120; y < window.innerHeight - 160; y += 6) {
        for (let x = 12; x < window.innerWidth - 12; x += 6) {
          const el = document.elementFromPoint(x, y);
          if (!el || el.closest(bad)) continue;
          return { x, y };
        }
      }
      return { x: 12, y: 120 };
    });
    await page.mouse.click(bp.x, bp.y);
    await page.waitForTimeout(1200);
    await page.screenshot({ path: join(SHOTS, 'gif-menu-after-blank-tap.png') });
  } else if (name === 'gif-date' || name === 'gif-tag' || name === 'gif-reply') {
    await page.locator('.ta-row.ta-row-tag', { hasText: '工作' }).first().click();
    await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
    await page.waitForTimeout(240);
    await page.locator(".ta-item[data-item-id='long-1'] .ta-item-body").click();
    await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
    await page.waitForTimeout(320);
    if (name === 'gif-date') {
      await page.evaluate(() => { const p = document.querySelector('.mobile-page[data-page="todo-apple-item"]'); [...p.querySelectorAll('.ta-tool button')][0]?.click(); });
      await page.waitForTimeout(1600);
      await page.screenshot({ path: join(SHOTS, 'gif-date-picker-open.png') });
      await page.evaluate(() => document.querySelector('.tw-panel.in .tw-ok')?.click());
      await page.waitForTimeout(1100);
    } else if (name === 'gif-tag') {
      await page.evaluate(() => { const p = document.querySelector('.mobile-page[data-page="todo-apple-item"]'); [...p.querySelectorAll('.ta-tool button')][2]?.click(); });
      await page.waitForTimeout(1600);
      await page.screenshot({ path: join(SHOTS, 'gif-tag-menu-open.png') });
      await page.evaluate(() => { const b = [...document.querySelectorAll('.fm-context-menu button')]; b[1]?.click(); });
      await page.waitForTimeout(1100);
    } else {
      await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-reply textarea').click();
      await page.keyboard.type('第一行回复');
      await page.waitForTimeout(900);
      await page.keyboard.type('\n第二行回复，检查输入框随内容长高而不是内部滚动。');
      await page.waitForTimeout(900);
      await page.screenshot({ path: join(SHOTS, 'gif-reply-typed.png') });
      await page.waitForTimeout(1000);
    }
  }
  await page.waitForTimeout(300);
}

const { browser, page, errors } = await boot({ record: SCENARIO !== 'read' });
try {
  if (SCENARIO === 'read') await readScenario(page);
  else await gifScenario(page, SCENARIO);
} catch (e) {
  readings.error = String(e && e.message ? e.message : e);
  console.log(`!! ${readings.error}`);
}
readings.consoleErrors = errors;
await page.waitForTimeout(300);
const videoPath = SCENARIO !== 'read' ? await page.video()?.path() : null;
if (videoPath) readings.video = videoPath;
writeFileSync(join(OUT, `readings-${VARIANT}-${SCENARIO}.json`), JSON.stringify(readings, null, 2));
console.log(`\nreadings: ${join(OUT, `readings-${VARIANT}-${SCENARIO}.json`)}`);
if (videoPath) console.log(`video: ${videoPath}`);
await browser.close();
process.exit(readings.error ? 1 : 0);
