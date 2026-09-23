// bgagent-injected-bubbles.spec.mjs — bg-agent panel must re-pull backend
// history on EVERY open, so the persisted injected rows ("blue bubbles") show.
//
// Defect (chain `bluebubble-diag`, diagnosis
// `~/.nebflow/docs/Nebflow/20260924_002502_bluebubble-diag__bluebubble-diag.md`):
//   `bgAgentPopup.js` gated the history fetch on
//   `entry.container.children.length === 0`. ws.js renders bg-agent live frames
//   into the HIDDEN container even while the popup is closed (the "Gating
//   EXEMPT" branch), so ONE live row made the container non-empty and the
//   history leg was then skipped forever. The dispatcher/node session family
//   keeps its injected rows ONLY in persisted history (its .ui.json carries no
//   ai/tool rows at all — those are live-only), so the panel showed exactly
//   "assistant text + tool blocks" and ZERO bubbles: the author's screenshot.
//
// Fix under test (author ruling 2026-09-24, options ① + ② — ③ forbidden):
//   ① the guard becomes the sister popup's unconditional shape
//      (`if (nodeSessionId)` — flowAgentPopup.js "always re-pull on open");
//   ② `closeStepPopup` marks the view `dirtyWhileHidden`, so the EXISTING
//      refresh branch in `openStepPopup` (resetStream + clear the hidden
//      container + re-seed the live tail) runs on every reopen.
//
// Assertion shape (both halves are load-bearing, neither is vacuous):
//   · the `getHistory` frame for the panel's session is actually SENT, and
//   · the rows it answers with are actually RENDERED into the panel container,
//     with the bubble label taken VERBATIM from the engine-persisted `header`.
// A test that only checked "no bubbles" would read identically whether the
// request was never sent or the response never rendered — hence both.
//
// Mutation evidence (run by the implementing node, logs in
// `.nebflow/evidence/20260924_bluebubble-fix-impl/`):
//   pre-fix tree        → T1/T2 RED  (history request absent, 0 bubbles)
//   post-fix tree       → T1/T2 GREEN
//   post-fix, ① reverted to the old guard → T1 RED again
//   post-fix, ② reverted (no dirty mark)  → T2 RED (stale live rows survive)
//
// Self-contained: serves src/main/resources/web via route interception and
// mocks the WS boot handshake — no backend, no sbt, never the 8080 host.
//
// Run: node node_modules/@playwright/test/cli.js test tests/bgagent-injected-bubbles.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'e2e-root-session';
// `dispatcher-` prefix = the session family the defect is about (isBgAgentId
// admits delegate-/subtask-/node-/dispatcher-; the dispatcher is the family
// whose injected rows exist only in persisted history).
const DISP_SID = 'dispatcher-b1ueb0b';
const DISP_NAME = 'project-dispatcher';

// The engine-persisted rows: verbatim `header` strings frozen from
// `~/.nebflow/sessions/dispatcher-c775afa5.ui.json` (8 injected rows, all
// `injected:true`, source distribution dispatch:1 / mail:4 / task:3). Row
// bodies are truncated in the repo copy (the engine's full bodies are up to
// 16 KB) — the assertion targets are the row COUNT, the row ORDER and the
// VERBATIM header strings, none of which the truncation touches.
const ROWS = JSON.parse(readFileSync(join(HERE, 'fixtures', 'bgagent-bluebubble', 'dispatcher-history-rows.json'), 'utf8'));
const HEADERS = ROWS.map((r) => r.header);

/** Frames shaped like NodeRunner.routeSubagentWsSend output (rootSessionId /
 *  sessionId / nodeSessionId injected by the routing wrapper). `sessionId` on
 *  node-/dispatcher- live frames is the sub-agent's OWN id. */
const base = () => ({
  agentId: DISP_SID,
  nodeSessionId: DISP_SID,
  rootSessionId: ROOT_SID,
  sessionId: DISP_SID,
  name: DISP_NAME,
  project: 'nebflow',
});

/** Start a fresh app page with the WS handshake mocked and every getHistory
 *  frame recorded. Returns the live `historyRequests` array (mutated in place
 *  by the route handler). */
async function bootApp(page, { historyRows = ROWS } = {}) {
  const historyRequests = [];
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getActiveAgents') ws.send(JSON.stringify({ type: 'activeAgents', agents: [] }));
      else if (msg.type === 'getHistory') {
        historyRequests.push(msg.sessionId);
        const msgs = msg.sessionId === DISP_SID ? historyRows : [];
        // Backend contract (SessionStore.getHistoryPage): the tail page when
        // no beforeIndex is given — offset 0, hasMore only when more exists.
        ws.send(JSON.stringify({
          type: 'historyPage',
          sessionId: msg.sessionId,
          messages: msgs,
          total: msgs.length,
          offset: 0,
          hasMore: false,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return historyRequests;
}

/** Drive an inbound frame through the REAL ws.js dispatch chain. */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

function openPanel(page, sid = DISP_SID) {
  return page.evaluate(async ([s, n]) => {
    const m = await import('/js/bgAgentPopup.js');
    m.openStepPopup(s, n, n);
  }, [sid, DISP_NAME]);
}

function closePanel(page) {
  return page.evaluate(async () => {
    const m = await import('/js/bgAgentPopup.js');
    m.closeStepPopup();
  });
}

/** Readings for the OPEN popup's chat container + the hidden container. */
function probe(page) {
  return page.evaluate(async () => {
    const open = document.querySelector('.flow-agent-overlay .flow-agent-chat');
    const hidden = document.querySelector('.flow-agent-hidden .flow-agent-chat');
    const read = (c) => (c ? {
      children: c.children.length,
      injected: c.querySelectorAll('.bubble.injected').length,
      // The header is the label's LEADING text node; a `delivery=immediate` row
      // appends a 「即时」 badge element after it (chat.js#appendDeliveryBadge).
      // Reading the text node (not textContent) keeps the verbatim comparison
      // free of the badge while still asserting the engine string byte-for-byte.
      headers: [...c.querySelectorAll('.bubble.injected .ask-label.injected-source-label')]
        .map((el) => (el.firstChild && el.firstChild.nodeType === Node.TEXT_NODE ? el.firstChild.nodeValue : '')),
      deliveryBadges: [...c.querySelectorAll('.bubble.injected .ask-label .delivery-badge')]
        .map((el) => el.textContent),
      aiRows: c.querySelectorAll('.row.ai').length,
      toolRows: c.querySelectorAll('.row.tool').length,
      // Rendered-and-attached assistant text rows. A row whose bubble was
      // orphaned by a container reset has no text node inside the container.
      aiText: (c.querySelector('.row.ai .bubble.ai') || {}).textContent || '',
    } : null);
    return { open: read(open), hidden: read(hidden) };
  });
}

/** The dispatcher is mid-turn while the popup is CLOSED: its live frames land
 *  in the hidden container (ws.js bg-agent branch is gating-EXEMPT) — this is
 *  what makes `container.children.length === 0` false and used to suppress the
 *  history leg. Leaves the stream OPEN (assistant text only, no tool call: a
 *  toolStart would close the round via main.js's toolStart flush, and the
 *  reopen defect this pins is specifically about a stream that is still open
 *  when the popup is closed). */
async function feedLiveWhileClosed(page, { closeRound = false } = {}) {
  await inject(page, { type: 'agentStart', ...base(), taskDescription: 'dispatcher/nebflow' });
  await page.waitForTimeout(150);
  await inject(page, { type: 'agentThinking', ...base(), delta: 'planning the batch…' });
  await inject(page, { type: 'agentTextDelta', ...base(), delta: 'Dispatching the batch now.' });
  if (closeRound) {
    await inject(page, { type: 'agentToolStart', ...base(), label: 'NodeEdit(create)' });
    await inject(page, { type: 'agentToolEnd', ...base(), label: 'NodeEdit(create)', summary: 'ok', content: 'created', isError: false });
  }
  await page.waitForTimeout(500);
}

test('T1 first open with a non-empty container: history IS re-pulled and the injected rows render', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const historyRequests = await bootApp(page);

  // Round is still in flight at open time (toolStart closes a round), which is
  // the author's symptom shape: a dispatcher mid-turn renders enough live DOM
  // that `container.children.length === 0` was false.
  await feedLiveWhileClosed(page);

  // Positive control (live leg): the frames above were accepted and rendered —
  // without this, "no request" and "no rendering at all" would read alike.
  const hidden = (await probe(page)).hidden;
  expect(hidden, 'live frames must land in the hidden container while closed').not.toBeNull();
  expect(hidden.aiRows, 'live assistant text rendered into the hidden container').toBeGreaterThan(0);
  expect(hidden.children, 'container is non-empty ⇒ the old guard would have suppressed the fetch')
    .toBeGreaterThan(0);

  const before = historyRequests.filter((s) => s === DISP_SID).length;
  expect(before, 'no history request for the panel session before it is opened').toBe(0);

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  // ① the request was actually SENT for this session …
  expect(historyRequests.filter((s) => s === DISP_SID).length,
    'getHistory for the panel session must be sent on open').toBe(1);

  // … and ② the response was actually RENDERED, label byte-identical to the
  // engine-persisted header (the frontend must not re-concatenate a second time).
  const open = (await probe(page)).open;
  expect(open.injected, 'one blue bubble per persisted injected row').toBe(ROWS.length);
  expect(open.headers, 'bubble labels verbatim from the persisted header field').toEqual(HEADERS);
  // Cross-check the fixture itself is the four-segment engine form and that the
  // `delivery=immediate` rows carry the badge (the source of the label suffix).
  expect(HEADERS[0]).toBe('DISPATCH · NEBFLOW');
  expect(HEADERS[0].split(' · ')).toHaveLength(2);
  const immediateRows = ROWS.filter((r) => r.delivery === 'immediate').length;
  expect(open.deliveryBadges, 'immediate rows carry the delivery badge').toHaveLength(immediateRows);

  expect(pageErrors).toEqual([]);
});

test('T2 close → reopen with an in-flight stream: the stale container is reset, not reused', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const historyRequests = await bootApp(page);

  await feedLiveWhileClosed(page);

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  // Close while the round is STILL open, then reopen. Reusing the previous
  // cycle's container/stream state here is the defect ② fixes: the live tail
  // that arrives after the reopen is appended to a stream bubble that the
  // history restore has already detached, so it renders nowhere.
  await closePanel(page);
  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  const reopened = (await probe(page)).open;
  expect(reopened.injected, 'same row count after reopen — no duplicates, no loss').toBe(ROWS.length);
  expect(reopened.headers, 'labels still verbatim after reopen').toEqual(HEADERS);
  expect(reopened.deliveryBadges, 'delivery badges intact after reopen')
    .toHaveLength(ROWS.filter((r) => r.delivery === 'immediate').length);

  // The load-bearing half of ②: a fresh live delta must land in a row that is
  // actually attached to the reopened container.
  await inject(page, { type: 'agentTextDelta', ...base(), delta: 'second round live tail.' });
  await expect
    .poll(async () => {
      const p = await probe(page);
      return p.open ? p.open.aiText.length : -1;
    }, { timeout: 8000 })
    .toBeGreaterThan(0);

  const afterDelta = (await probe(page)).open;
  expect(afterDelta.aiText, 'live tail after reopen lands in a rendered row')
    .toContain('second round live tail.');
  expect(afterDelta.injected, 'restored bubbles survive the live delta').toBe(ROWS.length);

  // One fetch per open — unconditional re-pull, still bounded.
  expect(historyRequests.filter((s) => s === DISP_SID).length,
    'exactly one getHistory per open').toBe(2);

  expect(pageErrors).toEqual([]);
});
