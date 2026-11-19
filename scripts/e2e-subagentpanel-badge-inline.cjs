#!/usr/bin/env node
// e2e-subagentpanel-badge-inline.cjs — Sub-Agents panel single-line row probe
// (2026-09-27 badge-inline batch: task badge joins the uptime line).
//
// Same harness shape as e2e-subagentpanel-liveonly.cjs (zero gateway / zero
// port / zero backend): page.route serves the REAL frontend tree from disk +
// MockWebSocket drives the production ws.js -> main.js dispatch path with
// production-form frames (activeAgents snapshot rows + live agentToolStart).
//
// The SAME fixture and the SAME assertions run against the unmodified tree
// (NEBFLOW_WEB_DIR=<pristine copy>) and the modified tree — red before /
// green after is ONE criterion, not two scripts. Screenshots land in
// EVIDENCE_DIR for the before/after visual comparison (same data source).
//
// Layout contract asserted (pure layout, zero functional change):
//   G1 one line per row: exactly one .bg-task-line and it carries
//      .bg-task-main; no .bg-task-meta / .bg-task-name-line in the panel
//   G2 name · badge · uptime share that line, in this order; uptime pinned
//      to the line's right edge
//   G3 row height: normal rows single-line and equal; the Bash tool-summary
//      row is exactly one text-line taller (acceptance: 1 line normal,
//      2 lines with ↳ summary)
//   G4 longest badge truncates (scrollWidth > clientWidth) with the FULL
//      text preserved in the title tooltip; row never overflows
//   G5 semantics untouched: 进行中 state word, Flow chip, project text,
//      status dot class, no-badge rows render no badge
//   G6 narrow viewport (420px): same single-line geometry, no horizontal
//      overflow, badge still visible
//
// Run (worktree root):
//   node scripts/e2e-subagentpanel-badge-inline.cjs
//   NEBFLOW_WEB_DIR=<dir>  frontend tree to serve (default: worktree web/)
//   EVIDENCE_DIR=<dir>     screenshot output directory (optional)
//   RUN_LABEL=<str>        run label written into screenshot filenames
const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize, resolve } = require('node:path');

const WEB = process.env.NEBFLOW_WEB_DIR
  ? resolve(process.env.NEBFLOW_WEB_DIR)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const EVIDENCE = process.env.EVIDENCE_DIR ? resolve(process.env.EVIDENCE_DIR) : null;
const LABEL = process.env.RUN_LABEL || 'run';
if (EVIDENCE) mkdirSync(EVIDENCE, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};

const ROOT_SID = 'e2e-badgeinline-root';
const MIN = 60 * 1000;
const NOW = Date.now();
// Fixture mirrors the author's evidence screenshot (2026-09-27 20:02): long
// badges that truncate, one row with a live Bash tool summary, one short
// badge, one row with no task attribution at all.
const ROWS = [
  { id: 'dispatcher-bbbb0002', agentName: 'general', task: 'pr48-review', kind: 'Flow', project: 'nebflow',
    taskId: 46, taskTitle: 'nebflow —【PR #48 审查派发与多路合并编排：闭环回执链与冲突消解】',
    status: 'Processing', startedAt: NOW - 40 * MIN },
  { id: 'delegate-oom-0003', agentName: 'general', task: 'oom-rootcause-diag', kind: 'Flow', project: 'nebflow',
    taskId: 47, taskTitle: 'nebflow —【OOM 根因诊断断链：swap 压力与编译类路径只读定位】',
    status: 'Processing', startedAt: NOW - 37 * MIN },
  { id: 'dispatcher-ffff0006', agentName: 'project-dispatcher', task: 'dispatcher/nebflow', kind: 'Flow', project: 'nebflow',
    taskId: 53, taskTitle: 'nebflow —【新批 · 消息队列合并编排与回执链路治理】',
    status: 'Processing', startedAt: NOW - 30 * MIN,
    toolLabel: 'Bash (cd "/Users/kaiyu/Claude code/Nebflow" && grep -rn "未挂载" --include="*.scala" src/main/scala | head -40)' },
  { id: 'delegate-short-0007', agentName: 'general', task: 'bashkit-analysis', kind: 'Flow', project: '',
    taskId: 52, taskTitle: 'general —【新批 · 外部项目分拣与登记】',
    status: 'Processing', startedAt: NOW - 10 * MIN },
  { id: 'delegate-nobadge-0008', agentName: 'general', task: 'secrets-env-desl', kind: 'Delegate', project: '',
    taskId: null, taskTitle: '',
    status: 'Processing', startedAt: NOW - 16 * MIN },
];
const LONG_BADGE_TEXT = '#46 · ' + ROWS[0].taskTitle;

const results = [];
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond]);
  console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? `  — ${extra}` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const snapshotRow = (r) => ({
  sessionId: r.id, agentId: r.id, agentName: r.agentName, rootSessionId: ROOT_SID,
  kind: r.kind, task: r.task, status: r.status, startedAt: r.startedAt,
  retryCount: 0, project: r.project, taskId: r.taskId || '', taskTitle: r.taskTitle || '',
});

async function newPanelPage(browser, { colorScheme = 'light', viewport = { width: 1440, height: 900 } } = {}) {
  const p = await browser.newPage({ viewport, colorScheme });
  const errors = [];
  p.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  // /api/ 404s are the mock's own answers (same filter as the liveonly probe):
  // only errors from real app resources count.
  p.on('console', (m) => {
    if (m.type() === 'error') {
      const url = m.location()?.url || '';
      if (url.includes('/api/')) return;
      errors.push(`console.error: ${m.text()} @ ${url}`);
    }
  });
  await p.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send() {} close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });
  await p.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let pth = decodeURIComponent(url.pathname);
    if (pth === '/') pth = '/index.html';
    if (pth.startsWith('/api/')) return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    const file = normalize(join(WEB, pth));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await p.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await p.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await p.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await p.waitForTimeout(250);
  const df = (frame) => p.evaluate((f) => window.__wsMock.onmessage({ data: JSON.stringify(f) }), frame);
  await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await p.waitForTimeout(400);
  return { p, df, errors };
}

async function openPanel(p) {
  return p.evaluate(async () => {
    const ind = document.getElementById('bgagent-indicator');
    const d = document.getElementById('bgagent-dropdown');
    if (!ind || !d) return false;
    if (!d.classList.contains('hidden')) { ind.click(); await new Promise((r) => setTimeout(r, 120)); }
    for (let i = 0; i < 3 && d.classList.contains('hidden'); i++) { ind.click(); await new Promise((r) => setTimeout(r, 150)); }
    return !d.classList.contains('hidden');
  });
}

/** One geometric read per row — line membership, order, truncation, height. */
async function readRows(p) {
  const opened = await openPanel(p);
  const r = await p.evaluate(() => {
    const dd = document.getElementById('bgagent-dropdown');
    const line = (el) => el.getBoundingClientRect();
    const rows = [...document.querySelectorAll('#bgagent-dropdown .bg-task-row')].map((row) => {
      const q = (s) => row.querySelector(s);
      const lineEl = row.querySelector('.bg-task-line');
      const name = q('.bg-task-name');
      const badge = q('.bg-task-badge');
      const uptime = q('.bg-task-uptime');
      const state = q('.bg-task-state');
      const kind = q('.bg-task-kind');
      const project = q('.bg-task-project');
      const tool = q('.bgagent-tool');
      const rect = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { left: +b.left.toFixed(1), right: +b.right.toFixed(1), top: +b.top.toFixed(1), width: +b.width.toFixed(1), height: +b.height.toFixed(1) };
      };
      return {
        key: row.getAttribute('data-bg-key') || '',
        lineCount: row.querySelectorAll('.bg-task-line').length,
        hasMainClass: !!(lineEl && lineEl.classList.contains('bg-task-main')),
        legacyMeta: !!q('.bg-task-meta'),
        legacyNameLine: !!q('.bg-task-name-line'),
        rowH: row.offsetHeight,
        rowScrollW: row.scrollWidth, rowClientW: row.clientWidth,
        lineRect: rect(lineEl),
        stateText: state ? state.textContent.trim() : '',
        stateRect: rect(state),
        kindText: kind ? kind.textContent.trim() : '',
        projectText: project ? project.textContent.trim() : '',
        dotClass: q('.bg-task-status') ? q('.bg-task-status').className : '',
        nameRect: rect(name),
        badgeRect: rect(badge),
        badgeScrollW: badge ? badge.scrollWidth : 0,
        badgeClientW: badge ? badge.clientWidth : 0,
        badgeTitle: badge ? badge.getAttribute('title') : null,
        uptimeRect: rect(uptime),
        uptimeText: uptime ? uptime.textContent.trim() : '',
        toolText: tool ? tool.textContent.trim() : '',
        toolRect: rect(tool),
      };
    });
    return {
      ddW: dd ? dd.clientWidth : 0,
      ddScrollW: dd ? dd.scrollWidth : 0,
      ddClientW: dd ? dd.clientWidth : 0,
      headerText: dd ? (dd.querySelector('.bg-dropdown-header')?.textContent || '').trim() : '',
      rows,
    };
  });
  r.opened = opened;
  return r;
}

async function runScenario(tag, browser, { colorScheme, viewport, shotName }) {
  const { p, df, errors } = await newPanelPage(browser, { colorScheme, viewport });
  await df({ type: 'activeAgents', agents: ROWS.map(snapshotRow) });
  // The Bash-summary row: live agentToolStart sets currentTool on the snapshot
  // entry (snapshot frames never carry currentTool — production form).
  const toolRow = ROWS.find((r) => r.toolLabel);
  if (toolRow) {
    await df({ type: 'agentToolStart', agentId: toolRow.id, label: toolRow.toolLabel,
      rootSessionId: ROOT_SID, sessionId: ROOT_SID, nodeSessionId: toolRow.id });
  }
  await sleep(400);
  const r = await readRows(p);
  const rows = r.rows;
  const byId = (id) => rows.find((x) => x.key === id);
  const longRow = byId('dispatcher-bbbb0002');
  const toolRowR = byId('dispatcher-ffff0006');
  const noBadgeRow = byId('delegate-nobadge-0008');
  const normalRows = rows.filter((x) => x.key !== 'dispatcher-ffff0006');

  ok(`[${tag}] panel opened with 5 rows`, r.opened && rows.length === 5, `rows=${rows.length} header=${r.headerText}`);

  // G1 one line per row
  ok(`[${tag}] G1 every row has exactly one .bg-task-line carrying .bg-task-main`,
    rows.every((x) => x.lineCount === 1 && x.hasMainClass),
    rows.map((x) => `${x.key}:${x.lineCount}/${x.hasMainClass ? 'main' : 'MISSING'}`).join(' '));
  ok(`[${tag}] G1 no legacy .bg-task-meta / .bg-task-name-line in the panel`,
    rows.every((x) => !x.legacyMeta && !x.legacyNameLine));

  // G2 name · badge · uptime on the same line, in order, uptime pinned right.
  // "Same line" = vertical box OVERLAP, not equal tops: the line is
  // baseline-aligned (panel spec §2.2), so 10px chip boxes legitimately sit
  // lower than the 13px name box.
  const g2rows = rows.filter((x) => x.badgeRect && x.uptimeRect);
  const overlapY = (a, b) => a && b && a.top < b.top + b.height && b.top < a.top + a.height;
  ok(`[${tag}] G2 name/badge/uptime share one line element`,
    g2rows.every((x) => x.nameRect && x.badgeRect && x.uptimeRect &&
      overlapY(x.nameRect, x.badgeRect) && overlapY(x.badgeRect, x.uptimeRect)),
    g2rows.map((x) => x.key).join(' '));
  ok(`[${tag}] G2 order name < badge < uptime`,
    g2rows.every((x) => x.nameRect.left < x.badgeRect.left && x.badgeRect.left < x.uptimeRect.left));
  ok(`[${tag}] G2 uptime right edge pinned to line right edge (±2px)`,
    g2rows.every((x) => Math.abs(x.uptimeRect.right - x.lineRect.right) <= 2),
    g2rows.map((x) => `Δ${(x.uptimeRect.right - x.lineRect.right).toFixed(1)}`).join(' '));

  // G3 heights: normal rows single-line + equal; tool row one text-line taller
  const normalH = normalRows.map((x) => x.rowH);
  ok(`[${tag}] G3 normal rows single-line (≤40px) and equal height (±2px)`,
    normalH.every((h) => h <= 40) && Math.max(...normalH) - Math.min(...normalH) <= 2,
    `heights=${normalH.join(',')}`);
  ok(`[${tag}] G3 tool-summary row is one text-line taller (Δ ∈ [10,26])`,
    toolRowR && toolRowR.rowH - normalH[0] >= 10 && toolRowR.rowH - normalH[0] <= 26,
    `toolRowH=${toolRowR && toolRowR.rowH} normalH=${normalH[0]} tool="${(toolRowR && toolRowR.toolText).slice(0, 40)}…"`);

  // G4 longest badge truncates + tooltip keeps full text + row never overflows
  ok(`[${tag}] G4 longest badge truncates (scrollWidth > clientWidth)`,
    longRow && longRow.badgeScrollW > longRow.badgeClientW + 1,
    `scroll=${longRow && longRow.badgeScrollW} client=${longRow && longRow.badgeClientW}`);
  ok(`[${tag}] G4 truncated badge tooltip carries the full text`,
    longRow && longRow.badgeTitle && longRow.badgeTitle.includes(LONG_BADGE_TEXT),
    `title="${longRow && (longRow.badgeTitle || '').slice(0, 60)}…"`);
  ok(`[${tag}] G4 no horizontal overflow on any row`,
    rows.every((x) => x.rowScrollW <= x.rowClientW + 1),
    rows.map((x) => `${x.key}:${x.rowScrollW}/${x.rowClientW}`).join(' '));

  // G5 semantics untouched
  ok(`[${tag}] G5 state word / Flow chip / dot class preserved`,
    rows.every((x) => x.stateText === '进行中') &&
    ['dispatcher-bbbb0002', 'delegate-oom-0003', 'dispatcher-ffff0006', 'delegate-short-0007']
      .every((id) => byId(id) && byId(id).kindText === 'Flow') &&
    rows.every((x) => x.dotClass.includes('bg-status-active')));
  ok(`[${tag}] G5 project text preserved where the fixture sets it`,
    ['dispatcher-bbbb0002', 'delegate-oom-0003', 'dispatcher-ffff0006'].every((id) => byId(id).projectText === 'nebflow'));
  ok(`[${tag}] G5 no-attribution row renders no badge`,
    noBadgeRow && !noBadgeRow.badgeRect);

  // G6 narrow viewport: single line holds, no overflow, badge visible.
  // Normal rows keep the 1-line form (≤40px); the Bash-summary row keeps its
  // 2-line form (≤60px) — same acceptance shape as G3.
  if (viewport.width <= 500) {
    const narrowNormal = normalRows.map((x) => x.rowH);
    ok(`[${tag}] G6 narrow: line geometry unchanged (≤24px lines, normal rows ≤40px)`,
      rows.every((x) => x.lineRect.height <= 24) && narrowNormal.every((h) => h <= 40),
      rows.map((x) => `${x.key}:${x.lineRect.height}/${x.rowH}`).join(' '));
    ok(`[${tag}] G6 narrow: no dropdown overflow, badge still visible`,
      r.ddScrollW <= r.ddClientW + 1 && longRow.badgeClientW > 0,
      `dd=${r.ddScrollW}/${r.ddClientW} badgeW=${longRow.badgeClientW}`);
  }

  ok(`[${tag}] zero real console/page errors`, errors.length === 0, errors.slice(0, 2).join(' ; '));

  if (EVIDENCE) {
    try {
      await p.screenshot({ path: join(EVIDENCE, `shot_${shotName}_${LABEL}.png`), fullPage: false });
      const dd = await p.$('#bgagent-dropdown');
      if (dd) await dd.screenshot({ path: join(EVIDENCE, `shot_${shotName}_${LABEL}_panel.png`) });
    } catch (e) { console.log(`screenshot failed (${tag}): ${e.message}`); }
  }
  await p.close();
}

(async () => {
  console.log(`subagent-panel badge-inline probe — label=${LABEL} web=${WEB}`);
  const browser = await chromium.launch();
  try {
    await runScenario('light/wide', browser, { colorScheme: 'light', viewport: { width: 1440, height: 900 }, shotName: 'light_wide' });
    await runScenario('dark/wide', browser, { colorScheme: 'dark', viewport: { width: 1440, height: 900 }, shotName: 'dark_wide' });
    await runScenario('light/narrow420', browser, { colorScheme: 'light', viewport: { width: 420, height: 900 }, shotName: 'light_narrow420' });
  } finally {
    await browser.close();
  }
  const failed = results.filter(([, c]) => !c);
  console.log(`\n[${LABEL}] ${results.length - failed.length}/${results.length} assertions passed`);
  if (failed.length) {
    console.log('FAILED assertions:');
    for (const [n] of failed) console.log(`  - ${n}`);
  }
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
