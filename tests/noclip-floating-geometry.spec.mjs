#!/usr/bin/env node
// No-clip floating gate — runtime geometry leg (author order 2026-11-18 22:01).
//
// Boots the REAL `src/main/resources/web` tree at the phone form factor
// (375x812, hasTouch) and, for a NAMED inventory of floating layers / cards,
// measures the four edges against the viewport and against every ancestor that
// clips (computed `overflow` != visible, `clip-path` != none, `contain` with
// paint/strict/content). Same vocabulary as the static leg; the shared named
// ledger (`scripts/noclip-floating-baseline.json`, section `runtime`) makes a
// current violation a known entry and a NEW one red.
//
// Standalone script (own browser, own PASS/FAIL, no @playwright/test surface) —
// the repo convention for geometry harnesses. Boot recipe follows
// `tests/mobile-todo-apple-shots.mjs` (route-from-disk + WS fixture, zero ports
// of a live instance). Run: `node tests/noclip-floating-geometry.spec.mjs`.
//
// Exit: 0 = green, 1 = red (violation outside the ledger; also CI-escalated
// SKIP), 2 = SKIP / environment (no playwright or no browser).
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const ROOT = join(HERE, '..');
const WEB = process.env.NOCLIP_WEB_ROOT ? resolve(process.env.NOCLIP_WEB_ROOT) : join(ROOT, 'src', 'main', 'resources', 'web');
const LEDGER = process.env.NOCLIP_LEDGER ? resolve(process.env.NOCLIP_LEDGER) : join(ROOT, 'scripts', 'noclip-floating-baseline.json');
const OUT = process.env.NOCLIP_SHOTS_DIR || '/Users/kaiyu/stand-evidence/noclip-floating-20261118/runtime';
const SID = 'noclip-runtime-root';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.webm': 'video/webm', '.mp4': 'video/mp4' };

const isCI = () => Boolean(process.env.CI || process.env.GITLAB_CI || process.env.GITHUB_ACTIONS);
const skip = (msg) => {
  if (isCI()) { console.error(`noclip-floating (runtime): SKIP: ${msg} — counted as FAIL under CI`); process.exit(1); }
  console.error(`noclip-floating (runtime): SKIP: ${msg} (exit 2 = environment, not red)`);
  process.exit(2);
};

let mod;
try {
  ({ mod } = await import('../scripts/lib/visual-harness.mjs').then((m) => ({ mod: m.loadPlaywright().mod })));
} catch (error) {
  skip(`playwright not resolvable (${String(error.message).split('\n')[0]})`);
}

// ── the in-page probe: four edges + every clipping ancestor ────────────────
const PROBE = (sel) => {
  const el = document.querySelector(sel);
  if (!el) return { sel, missing: true };
  const r = el.getBoundingClientRect();
  const vw = window.innerWidth, vh = window.innerHeight;
  const cs = getComputedStyle(el);
  const rules = [];
  if (r.top < -0.5) rules.push('viewport-top-clip');
  if (r.bottom > vh + 0.5) rules.push('viewport-bottom-clip');
  if (r.left < -0.5) rules.push('viewport-left-clip');
  if (r.right > vw + 0.5) rules.push('viewport-right-clip');
  // self-clip: the box caps its own content without a scrollable axis.
  const oy = cs.overflowY, ox = cs.overflowX;
  if (/hidden|clip/.test(oy) && el.scrollHeight > el.clientHeight + 1) rules.push('self-vertical-clip');
  if (/hidden|clip/.test(ox) && el.scrollWidth > el.clientWidth + 1) rules.push('self-horizontal-clip');
  const clippers = [];
  for (let n = el.parentElement; n; n = n.parentElement) {
    const c = getComputedStyle(n);
    const ov = !(c.overflow === 'visible' && c.overflowX === 'visible' && c.overflowY === 'visible');
    const cp = c.clipPath && c.clipPath !== 'none';
    const ct = /paint|strict|content/.test(c.contain || '');
    if (!(ov || cp || ct)) continue;
    const b = n.getBoundingClientRect();
    const cutTop = r.top < b.top - 0.5, cutBottom = r.bottom > b.bottom + 0.5;
    clippers.push({ who: n.id || String(n.className || '').slice(0, 48), overflow: c.overflow, clipPath: c.clipPath, contain: c.contain, cutTop, cutBottom });
    if (cutTop) rules.push(cp ? 'ancestor-clip-path-top' : ct ? 'ancestor-contain-top' : 'ancestor-overflow-top');
    if (cutBottom) rules.push(cp ? 'ancestor-clip-path-bottom' : ct ? 'ancestor-contain-bottom' : 'ancestor-overflow-bottom');
  }
  return {
    sel, rect: { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
    viewport: { vw, vh }, textOverflow: cs.textOverflow, overflow: cs.overflow,
    position: cs.position, zIndex: cs.zIndex, clippers, rules: [...new Set(rules)],
  };
};

async function boot(browser, { viewport, mobile, scheme = 'light' }) {
  const ctx = await browser.newContext({ viewport, colorScheme: scheme, deviceScaleFactor: 2, hasTouch: mobile, isMobile: mobile, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message)));
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
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); sess();
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sess();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] })); });
  });
  await page.goto('http://localhost:8921/');
  await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws && s.ws.readyState === 1; }, SID, { timeout: 20000 });
  if (mobile) await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(500);
  return { ctx, page, errors };
}

const TALL_ASK = [
  { question: '问题 1：这是一个较长的决策问题描述，用于把卡片撑到超过一屏，检验顶部是否被截断。', options: ['选项 1-A 说明较长', '选项 1-B', '选项 1-C', '选项 1-D'] },
  { question: '问题 2：这是一个较长的决策问题描述，用于把卡片撑到超过一屏，检验顶部是否被截断。', options: ['选项 2-A 说明较长', '选项 2-B', '选项 2-C', '选项 2-D'] },
  { question: '问题 3：这是一个较长的决策问题描述，用于把卡片撑到超过一屏，检验顶部是否被截断。', options: ['选项 3-A 说明较长', '选项 3-B', '选项 3-C', '选项 3-D'] },
];

const renderTallAsk = (items) => async (page) => {
  await page.evaluate(async (q) => {
    const { renderAskUser } = await import('/js/chat.js');
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const state = (await import('/js/state.js')).default;
    setActiveView(chatViews.primary);
    state.ws = { readyState: 1, send: () => {} };
    renderAskUser(q, 'noclip-runtime', 'NOCLIP');
  }, items);
  await page.waitForTimeout(400);
};

const fillChat = async (page, n = 40) => page.evaluate((count) => {
  const c = document.getElementById('chat');
  for (let i = 0; i < count; i++) {
    const d = document.createElement('div');
    d.className = 'row';
    d.innerHTML = `<div class="bubble">历史消息 ${i}</div>`;
    c.appendChild(d);
  }
}, n);

const SCENARIOS = [
  {
    id: 'mobile-chat-ask-card', mobile: true, viewport: { width: 375, height: 812 }, selectors: ['.option-box'],
    setup: renderTallAsk(TALL_ASK),
  },
  {
    id: 'mobile-chat-escalation-card', mobile: true, viewport: { width: 375, height: 812 }, selectors: ['.error-escalation-card'],
    setup: async (page) => {
      await fillChat(page);
      await page.evaluate(async () => {
        const { renderEscalationCard } = await import('/js/errorRecovery.js');
        renderEscalationCard({ sessionId: 'noclip-runtime-root', reason: 'llm-transient', retryCount: 3, level: 2, escalateAt: Date.now() + 300000 });
      });
      await page.waitForTimeout(400);
    },
  },
  {
    id: 'mobile-todo-decision-card', mobile: true, viewport: { width: 375, height: 812 }, selectors: ['.ta-decision-detail'],
    setup: async (page) => {
      const longQ = '这是一个很长的决策问题描述。'.repeat(8);
      const opts = Array.from({ length: 8 }, (_, i) => `选项 ${i + 1} —— 说明文字较长用于撑高卡片`);
      await page.evaluate(async ({ longQ, opts }) => {
        const m = await import('/js/todoStore.js');
        m.todoStore.__seed({ tagList: [{ id: 'tw', name: '工作', color: 'rgb(0,122,255)' }], itemList: [
          { id: 'd1', tagId: 'tw', title: '发布方案需要你定夺', due: null, priority: 2, completed: false,
            decision: { question: longQ, options: opts, multiSelect: false, allowOther: true } },
          { id: 'i2', tagId: 'tw', title: '普通待办', due: null, priority: 0, completed: false },
        ] });
        const nav = await import('/js/mobile/navController.js'); nav.pushPage('tasks');
      }, { longQ, opts });
      await page.waitForSelector('.mobile-page[data-page="tasks"].live', { timeout: 15000 });
      await page.waitForTimeout(250);
      await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
      await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
      await page.waitForTimeout(250);
      await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item-body').first().click();
      await page.waitForTimeout(350);
      // the user scrolls to reach the answer controls — the realistic worst case
      await page.evaluate(() => { const b = document.querySelector('.mobile-page[data-page="todo-apple-list"] .mobile-page-body'); b.scrollTop = b.scrollHeight; });
      await page.waitForTimeout(250);
    },
  },
  {
    id: 'mobile-chrome', mobile: true, viewport: { width: 375, height: 812 },
    selectors: ['#mobile-dock', '.mh-chrome-btn', '.mobile-back-btn', '#input-bar'],
    setup: async (page) => { await page.waitForTimeout(200); },
  },
  {
    id: 'desktop-chat-ask-card', mobile: false, viewport: { width: 1280, height: 800 }, selectors: ['.option-box'],
    setup: renderTallAsk(TALL_ASK),
  },
];

function loadLedger() {
  if (!existsSync(LEDGER)) return { static: [], runtime: [] };
  const data = JSON.parse(readFileSync(LEDGER, 'utf8'));
  return { static: data.static || [], runtime: data.runtime || [] };
}

async function main() {
  mkdirSync(OUT, { recursive: true });
  let browser;
  try {
    browser = await mod.chromium.launch({ headless: true });
  } catch (error) {
    skip(`no usable chromium (${String(error.message).split('\n')[0]})`);
  }

  const readings = [];
  const pageErrors = [];
  for (const sc of SCENARIOS) {
    const { ctx, page, errors } = await boot(browser, { viewport: sc.viewport, mobile: sc.mobile });
    try {
      await sc.setup(page);
      for (const sel of sc.selectors) {
        const reading = await page.evaluate(PROBE, sel);
        readings.push({ scenario: sc.id, ...reading });
      }
      await page.screenshot({ path: join(OUT, `${sc.id}.png`), animations: 'disabled' });
    } catch (error) {
      readings.push({ scenario: sc.id, sel: sc.selectors.join(','), error: String(error.message) });
    } finally {
      pageErrors.push(...errors.map((e) => `${sc.id}: ${e}`));
      await ctx.close();
    }
  }
  await browser.close();

  const ledger = loadLedger();
  const known = new Set(ledger.runtime.map((e) => e.key));
  const violations = [];
  for (const r of readings) {
    if (r.missing || r.error) continue;
    for (const rule of r.rules) violations.push({ key: `${r.scenario}::${r.sel}::${rule}`, scenario: r.scenario, selector: r.sel, rule, rect: r.rect, viewport: r.viewport, clippers: r.clippers });
  }
  const fresh = violations.filter((v) => !known.has(v.key));
  const improved = ledger.runtime.filter((e) => !violations.some((v) => v.key === e.key));

  const report = { generatedAtNote: 'runtime geometry readings', readings, violations, fresh, improved, ledgerSize: ledger.runtime.length, pageErrors };
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(report, null, 2));
  if (process.argv.includes('--json')) console.log(JSON.stringify(report, null, 2));

  if (pageErrors.length) console.error(`noclip-floating (runtime): page errors: ${pageErrors.slice(0, 3).join(' | ')}`);
  if (fresh.length) {
    console.error(`noclip-floating (runtime): ${fresh.length} violation(s) outside the named ledger (${violations.length} total, ledger ${ledger.runtime.length}):`);
    for (const v of fresh) console.error(`  ${v.key}  rect=${JSON.stringify(v.rect)} viewport=${JSON.stringify(v.viewport)}`);
    process.exit(1);
  }
  console.log(`noclip-floating (runtime): PASS — ${violations.length} known violation(s) all ledgered, ${readings.length} surface(s) measured${improved.length ? `, ${improved.length} ledger entry(ies) now gone` : ''}`);
  console.log(`  shots/readings: ${OUT}/readings.json`);
  process.exit(0);
}

await main();
