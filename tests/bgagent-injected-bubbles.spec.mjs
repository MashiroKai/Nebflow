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
//   ② the close path marks the view dirty, so a reopen heals a detached stream
//      anchor from the per-session buffers.
//
// Rework round 1 (verifier rejection: the unconditional re-pull alone was a
// regression). `handleBgAgentHistory`'s first-frame branch wiped the container
// unconditionally, and for the dispatcher/node family the assistant text and
// tool rows are NEVER persisted — this container is their only copy — so every
// open destroyed them, and it also orphaned `view.stream.currentAiBubble`
// (chat.js then reuses the detached node and drops the render on its
// `isConnected` guard). The branch now reconciles: it keeps the wipe shape
// only when the payload itself carries rows the panel renders from history
// (ai/tool/agent — the families the backend persists live), and otherwise
// renders the frame's injected rows in place while every other row stays.
//
// Assertion shape (T1/T2 halves are load-bearing, none of them vacuous):
//   · the `getHistory` frame for the panel's session is actually SENT, and
//   · the rows it answers with are actually RENDERED into the panel container,
//     with the bubble label taken VERBATIM from the engine-persisted `header`;
//   · T3/T4: the rows the frame CANNOT re-provide (in-flight assistant text,
//     closed-round tool rows) survive the merge — the round-1 regression.
// A test that only checked "no bubbles" would read identically whether the
// request was never sent or the response never rendered — hence both halves.
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
      // Rendered-and-attached assistant text rows, joined across the whole
      // container. A row whose bubble was orphaned by a container reset has no
      // text node inside the container, so its contribution is empty; joining
      // (rather than reading the first row) also sees text that arrived as a
      // LATER round, which is a separate row by design.
      aiText: [...c.querySelectorAll('.row.ai .bubble.ai')].map((b) => b.textContent).join('\n'),
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

test('T2 close → reopen with an in-flight stream: the stream keeps working and nothing is lost', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const historyRequests = await bootApp(page);

  await feedLiveWhileClosed(page);

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  // Close while the round is STILL open, then reopen. The carried-over live
  // rows must survive the reopen cycle (no duplicate, no loss) and the open
  // stream must keep taking deltas.
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
  // actually attached to the reopened container. The poll targets the DELTA
  // itself — polling for "some ai text exists" would be satisfied instantly by
  // the text carried over from the previous cycle and would assert nothing.
  await inject(page, { type: 'agentTextDelta', ...base(), delta: 'second round live tail.' });
  await expect
    .poll(async () => {
      const p = await probe(page);
      return p.open ? p.open.aiText : '';
    }, { timeout: 8000 })
    .toContain('second round live tail.');

  const afterDelta = (await probe(page)).open;
  expect(afterDelta.aiText, 'live tail after reopen lands in a rendered row')
    .toContain('second round live tail.');
  expect(afterDelta.injected, 'restored bubbles survive the live delta').toBe(ROWS.length);

  // One fetch per open — unconditional re-pull, still bounded.
  expect(historyRequests.filter((s) => s === DISP_SID).length,
    'exactly one getHistory per open').toBe(2);

  expect(pageErrors).toEqual([]);
});

// ── Rework round 1: the container must not be destroyed on open ──────────
// Verifier rejection (round 1) proved that ① alone introduced a regression:
// the first-frame branch wiped the container, and for the dispatcher/node
// family the assistant text and tool rows never reach .ui.json — the container
// is their ONLY copy. T3/T4 pin the survival half of the merge; the mutation
// readings below show each one fails on the round-1 code and passes now.

test('T3 first open while the turn is in flight: already-streamed assistant text survives', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // Frame shape measured on the previous tree: the stream is OPEN at open
  // time, so the live text is the exact content the wipe used to destroy.
  await feedLiveWhileClosed(page);
  const beforeOpen = (await probe(page)).hidden;
  expect(beforeOpen.aiRows, 'live assistant row exists before the panel opens').toBeGreaterThan(0);
  expect(beforeOpen.aiText, 'and it carries the pre-open text').toContain('Dispatching the batch now.');

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  const open = (await probe(page)).open;
  // Both channels are present at once: the injected rows from history AND the
  // live rows the frame cannot re-provide.
  expect(open.aiRows, 'the in-flight assistant row survives the history merge')
    .toBeGreaterThan(0);
  expect(open.aiText, 'with the text streamed BEFORE the open, still attached')
    .toContain('Dispatching the batch now.');
  expect(open.injected, 'and the history channel is merged in, not replaced').toBe(ROWS.length);

  // The open turn keeps taking deltas after the merge (the round-1 defect also
  // froze this: the stream bubble had been orphaned by the wipe).
  await inject(page, { type: 'agentTextDelta', ...base(), delta: ' CONTINUATION-AFTER-OPEN.' });
  await expect
    .poll(async () => (await probe(page)).open?.aiText ?? '', { timeout: 8000 })
    .toContain('CONTINUATION-AFTER-OPEN.');

  expect(pageErrors).toEqual([]);
});

// ── The other side of the discriminator ─────────────────────────────────
// The merge rule is chosen from the FRAME's content, so the complementary case
// needs its own pin: when the payload DOES carry rows the panel renders from
// history (the families whose engines persist live through a recording wsSend
// — delegate/subtask/dag), the established wipe + rebuild shape is kept. That
// is what removes the stale rows a closed round left behind, and it is what
// makes the rule a genuine discriminator rather than an unconditional keep.

/** History row kinds the panel renders in their own right — the shape a
 *  delegate/subtask/dag session's .ui.json actually has (measured on the host:
 *  delegate-design-engineer-f80ad567 = ai 40 / tool 100). */
const RENDERABLE_ROWS = [
  { type: 'user', injected: true, source: 'delegate', eventType: 'result', sender: 'Explorer', header: 'DELEGATE · NEBFLOW · EXPLORER · RESULT', timestamp: 1790176939643, text: 'PAIR injected body', attachments: [] },
  { type: 'ai', text: 'PERSISTED-ASSISTANT-TEXT', timestamp: 1790180000000 },
  { type: 'tool', label: 'Bash\n(cd /tmp && ls)', summary: 'list dir', content: 'total 0', isError: false, input: '{"command":"ls"}' },
];

test('T5 payload that carries renderable rows: the container is rebuilt from history (stale live rows dropped)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, { historyRows: RENDERABLE_ROWS });

  // Live frames that the persisted payload supersedes (same session, rendered
  // into the hidden container while the panel was closed).
  await inject(page, { type: 'agentStart', ...base(), taskDescription: 'delegate/nebflow' });
  await feedLiveWhileClosed(page);
  const beforeOpen = (await probe(page)).hidden;
  expect(beforeOpen.aiRows, 'a live row exists before the open').toBeGreaterThan(0);
  expect(beforeOpen.injected, 'and no live injected row was rendered').toBe(0);

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(1);

  const open = (await probe(page)).open;
  // The payload reproduces the container, so the container is history's, not a
  // merge of the two: exactly the frame's own rows, in the frame's own shape.
  expect(open.injected, 'the persisted injected row is rendered').toBe(1);
  expect(open.aiRows, 'exactly the frame\'s one assistant row (the live duplicate is gone)').toBe(1);
  expect(open.toolRows, 'and the frame\'s tool row').toBe(1);
  expect(open.aiText, 'the assistant text comes from the payload, not the live tail')
    .toContain('PERSISTED-ASSISTANT-TEXT');
  expect(open.aiText, 'the superseded live text is not concatenated in')
    .not.toContain('Dispatching the batch now.');
  expect(open.headers, 'header verbatim from the persisted field')
    .toEqual(['DELEGATE · NEBFLOW · EXPLORER · RESULT']);

  expect(pageErrors).toEqual([]);
});

test('T6 reopen: the previous cycle\'s stream anchor is not continued (and the tail is restored, not lost)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await feedLiveWhileClosed(page);
  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  await closePanel(page);
  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  // (a) the tail is RESTORED, not lost — the reopen re-seeds from the
  // per-session buffers, which accumulate for every session regardless of the
  // popup's state.
  const reopened = (await probe(page)).open;
  expect(reopened.aiText, 'the in-flight tail survives the reopen').toContain('Dispatching the batch now.');
  expect(reopened.aiRows, 'exactly one assistant row — the restored tail is one bubble').toBe(1);

  // (b) the previous cycle's text is NOT re-emitted: the delta below is the
  // only new text, so the row must not grow the earlier copy a second time.
  await inject(page, { type: 'agentTextDelta', ...base(), delta: 'ONLY-NEW-TAIL.' });
  await expect
    .poll(async () => (await probe(page)).open?.aiText ?? '', { timeout: 8000 })
    .toContain('ONLY-NEW-TAIL.');

  const afterDelta = (await probe(page)).open;
  const occurrences = afterDelta.aiText.split('Dispatching the batch now.').length - 1;
  expect(occurrences, 'the pre-close text appears exactly once — no cross-cycle re-emission')
    .toBe(1);
  expect(afterDelta.aiText, 'and the new tail rides the restored bubble')
    .toContain('ONLY-NEW-TAIL.');

  expect(pageErrors).toEqual([]);
});

test('T4 first open with a CLOSED round and a tool row: both survive the history merge', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // Text → toolStart/toolEnd → (round closed) → the panel opens. This is the
  // shape whose tool rows are unrecoverable for the dispatcher/node family:
  // the frame holds injected user rows only, so a wipe loses them for good.
  await feedLiveWhileClosed(page, { closeRound: true });
  const beforeOpen = (await probe(page)).hidden;
  expect(beforeOpen.toolRows, 'a tool row was rendered before the open').toBeGreaterThan(0);
  expect(beforeOpen.aiRows, 'and the round that preceded it too').toBeGreaterThan(0);

  await openPanel(page);
  await expect
    .poll(async () => (await probe(page)).open?.injected ?? -1, { timeout: 8000 })
    .toBe(ROWS.length);

  const open = (await probe(page)).open;
  expect(open.toolRows, 'closed-round tool rows survive the history merge')
    .toBe(beforeOpen.toolRows);
  expect(open.aiRows, 'and so does the closed round\'s assistant row').toBeGreaterThan(0);
  expect(open.aiText, 'carrying the closed round\'s text').toContain('Dispatching the batch now.');
  expect(open.injected, 'history channel merged in alongside').toBe(ROWS.length);

  // A round that closes AFTER the merge still lands as a new row (the merge
  // must not leave a half-wired stream state behind).
  await inject(page, { type: 'agentTextDelta', ...base(), delta: 'NEXT-ROUND-TEXT.' });
  await expect
    .poll(async () => (await probe(page)).open?.aiText ?? '', { timeout: 8000 })
    .toContain('NEXT-ROUND-TEXT.');
  expect((await probe(page)).open.toolRows, 'tool rows still intact after a later round')
    .toBe(beforeOpen.toolRows);

  expect(pageErrors).toEqual([]);
});
