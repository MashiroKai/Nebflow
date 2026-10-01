// tasklist-chainctl-optimistic.spec.mjs — chain-row topology order + four-state motion
// trigger + freeze-style optimistic chain control (chain-tasklist-anim batch, impl node).
//
// Scope (implementation node's own acceptance surface; design card landing point §10.9):
//   O1  topology: member rows are ordered by the topological three-key sort (depth ↑,
//       createdAt ↑, id ↑); the chain entry is no longer painted as a dispatcher when its
//       name carries no dispatch word-root (C2); the dotmap draws a link only on a real
//       edge (C1) — the fixture is built so the time order and the topology order differ.
//   O2  active position: the collapsed row states `{n} working` for ≥2 concurrent runners,
//       and never invents a within-chain order.
//   O3  four-state motion: the check motion plays on the transition frame **exactly once**
//       and never on first paint of an already-terminal row (§4.3 anti-replay).
//   O4  overflow notice: >100 members render the first 100 rows plus the notice row.
//   O5  optimistic chain control: the click sets the in-flight state in the same frame
//       (<100ms budget), a `chainState` frame clears it, and a 404 rolls back + toasts.
//   O6  availability matrix + reduced motion.
//
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-chainctl-optimistic.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.TL_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'tl-root-session';
const T0 = Date.now();

const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: T0, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});

async function bootApp(page, reducedMotion) {
  if (reducedMotion) await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });

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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
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
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
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
}

/** Inject one frame through the real ws.js dispatch path (equivalent to a backend broadcast). */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function renderPanel(page) {
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForTimeout(100);
}

/** Inject a chain fixture: members first (nodeCreated), then the snapshot carrying the
 *  `chains` side table (the authoritative chain title / members / state). */
async function injectChainFixture(page, project, fx, extra = []) {
  const all = [...fx.nodes, ...extra];
  for (const n of all) {
    await inject(page, { type: 'nodeCreated', project, nodeId: n.id, node: n });
  }
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: project, workspace: `/w/${project}`, agentFile: project, description: '', createdAt: new Date().toISOString() }] },
  }));
  await page.route(`**/api/projects/${project}/flow-map`, (r) => r.fulfill({
    json: { nodes: all, worktrees: [], chains: fx.chains, meta: { project, updatedAt: Date.now() } },
  }));
  await page.evaluate(async () => {
    const { refreshNodeSnapshot } = await import('/js/taskList.js');
    await refreshNodeSnapshot();
  });
  await page.waitForTimeout(150);
}

/** O1 fixture — deliberately makes the time order differ from the topology order.
 *  Edges: a1 → a2 → a4 and a1 → a3 (so a1 depth 0, a2/a3 depth 1, a4 depth 2).
 *  createdAt ascends a4 < a3 < a2 < a1, i.e. the reverse of the topology. */
function topoFixture(t0) {
  return {
    chains: [{ id: 'c-topo', title: 'topo-chain', entries: ['a1'], ends: ['a4'], memberIds: ['a1', 'a2', 'a3', 'a4'] }],
    nodes: [
      nodeJson({ id: 'a1', name: 'tasklist-anim-impl', agent: 'g', status: 'completed', createdAt: t0 + 4_000, completedAt: t0 + 4_500, ttlLeftSec: 300, chainId: 'c-topo', in: [], out: [{ to: 'a2', port: 'pass', kind: 'chain' }, { to: 'a3', port: 'pass', kind: 'chain' }] }),
      nodeJson({ id: 'a2', name: 'verify-two', agent: 'g', status: 'completed', createdAt: t0 + 3_000, completedAt: t0 + 3_500, ttlLeftSec: 300, chainId: 'c-topo', role: 'verifier', in: ['a1'], out: [{ to: 'a4', port: 'pass', kind: 'chain' }] }),
      nodeJson({ id: 'a3', name: 'verify-three', agent: 'g', status: 'failed', createdAt: t0 + 2_000, completedAt: t0 + 2_500, ttlLeftSec: 300, chainId: 'c-topo', role: 'verifier', in: ['a1'] }),
      nodeJson({ id: 'a4', name: 'sink-four', agent: 'g', status: 'completed', createdAt: t0 + 1_000, completedAt: t0 + 1_500, ttlLeftSec: 300, chainId: 'c-topo', merge: true, in: ['a2'] }),
    ],
  };
}

test('O1 topology: member rows follow (depth↑, createdAt↑, id↑); entry is no longer a false dispatcher; dotmap links only real edges', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;
  await injectChainFixture(page, 'alpha', topoFixture(t0));

  const panel = page.locator('#task-list');
  const row = panel.locator('.task-chain[data-chain-key="alpha:c-topo"]');
  await expect(row).toHaveCount(1);
  await row.click();
  await expect(panel.locator('.task-chain-member')).toHaveCount(4);

  // Row order = topological order: a1 (d0) → a3 (d1, createdAt +2s) → a2 (d1, +3s)
  // → a4 (d2). Never the reverse-chronological order the panel sorts its node list by
  // (which would be a4, a2, a3, a1).
  const order = await panel.locator('.task-chain-member').evaluateAll((els) => els.map((e) => e.dataset.nodeId));
  expect(order).toEqual(['a1', 'a3', 'a2', 'a4']);

  // Depth → indent (--nest) and hierarchical sequence labels, in topological order.
  const nests = await panel.locator('.task-chain-member').evaluateAll((els) => els.map((e) => e.style.getPropertyValue('--nest').trim()));
  expect(nests).toEqual(['0', '1', '1', '2']);
  const seqs = await panel.locator('.task-chain-member .task-chain-depth').allTextContents();
  expect(seqs).toEqual(['1', '1.1', '1.2', '1.2.1']);

  // C2: a1 is the chain entry (empty in / deps) but its name carries no dispatch word-root
  // ⇒ it must fall through to impl, not be painted dispatcher.
  await expect(panel.locator('.task-chain-member[data-node-id="a1"] .task-chain-dot')).toHaveClass(/t-impl/);
  await expect(panel.locator('.task-chain-member[data-node-id="a1"] .task-chain-dot')).not.toHaveClass(/t-dispatcher/);

  // C1: links only where a real edge joins two adjacent dots. a1–a2 ✓, a2–a4 ✓,
  // a1–a3 is a real edge but those dots are not adjacent in the sorted order ⇒ 2 links.
  await expect(row.locator('.task-chain-map-dot')).toHaveCount(4);
  await expect(row.locator('.task-chain-map-link')).toHaveCount(2);

  // A true dispatcher seat (entry + dispatch word-root) still gets the dispatcher colour.
  const t1 = Date.now() - 60_000;
  await injectChainFixture(page, 'beta', {
    chains: [{ id: 'c-disp', title: 'disp-chain', entries: ['b1'], ends: ['b2'], memberIds: ['b1', 'b2'] }],
    nodes: [
      nodeJson({ id: 'b1', name: 'dispatch-intake', agent: 'g', status: 'running', createdAt: t1, startedAt: t1, chainId: 'c-disp', in: [] }),
      nodeJson({ id: 'b2', name: 'impl-after', agent: 'g', status: 'pending', createdAt: t1 + 1_000, chainId: 'c-disp', in: ['b1'], role: 'task' }),
    ],
  });
  const brow = panel.locator('.task-chain[data-chain-key="beta:c-disp"]');
  await brow.click();
  await expect(panel.locator('.task-chain-member[data-node-id="b1"] .task-chain-dot')).toHaveClass(/t-dispatcher/);

  expect(pageErrors).toEqual([]);
});

test('O2 active position: the collapsed row states {n} working for concurrent runners and never invents an order', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  // Two concurrent runners (the card's headline case: 29.3% of real chains).
  await injectChainFixture(page, 'alpha', {
    chains: [
      { id: 'c-two', title: 'two-running', entries: ['r1'], ends: ['r2'], memberIds: ['r1', 'r2'] },
      { id: 'c-one', title: 'one-running', entries: ['s1'], ends: ['s1'], memberIds: ['s1'] },
    ],
    nodes: [
      nodeJson({ id: 'r1', name: 'impl-a', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-two', in: [] }),
      nodeJson({ id: 'r2', name: 'impl-b', agent: 'g', status: 'running', createdAt: t0 + 1_000, startedAt: t0 + 1_000, chainId: 'c-two', in: [] }),
      nodeJson({ id: 's1', name: 'solo-run', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-one' }),
    ],
  });

  const panel = page.locator('#task-list');
  // Concurrent case: the count is stated (the card's §3.2 "all annotated" branch).
  const two = panel.locator('.task-chain[data-chain-key="alpha:c-two"]');
  await expect(two.locator('.task-chain-meta')).toHaveText('0/2 · 2 位进行中 · 5m');
  // Unique-running case: no count appended (identified by its own row / the is-running dot).
  const one = panel.locator('.task-chain[data-chain-key="alpha:c-one"]');
  await expect(one.locator('.task-chain-meta')).toHaveText('0/1 · 5m');

  // No front-end invented order: the concurrent runners are laid out by topology only
  // (both depth 0 ⇒ createdAt ascends), deterministically and stably.
  await two.click();
  const order = await panel.locator('.task-chain-block:has(.task-chain[data-chain-key="alpha:c-two"]) .task-chain-member')
    .evaluateAll((els) => els.map((e) => e.dataset.nodeId));
  expect(order).toEqual(['r1', 'r2']);

  expect(pageErrors).toEqual([]);
});

test('O3 four-state motion: the check plays once on the transition frame and never on first paint of an already-terminal row', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  // Chain of three: a terminal member already on screen, a running member, a pending one.
  await injectChainFixture(page, 'alpha', {
    chains: [{ id: 'c-mo', title: 'motion-chain', entries: ['m1'], ends: ['m3'], memberIds: ['m1', 'm2', 'm3'] }],
    nodes: [
      nodeJson({ id: 'm1', name: 'impl-one', agent: 'g', status: 'completed', createdAt: t0, completedAt: t0 + 500, ttlLeftSec: 300, chainId: 'c-mo', in: [] }),
      nodeJson({ id: 'm2', name: 'impl-two', agent: 'g', status: 'running', createdAt: t0 + 1_000, startedAt: t0 + 1_000, chainId: 'c-mo', in: ['m1'] }),
      nodeJson({ id: 'm3', name: 'impl-three', agent: 'g', status: 'pending', createdAt: t0 + 2_000, chainId: 'c-mo', in: ['m2'] }),
    ],
  });
  const panel = page.locator('#task-list');
  await panel.locator('.task-chain[data-chain-key="alpha:c-mo"]').click();
  await expect(panel.locator('.task-chain-member')).toHaveCount(3);

  // First paint of an already-terminal row ⇒ silent (the anti-replay branch, §4.3).
  const doneMark = panel.locator('.task-chain-member[data-node-id="m1"] .task-chain-mark');
  await expect(doneMark).toHaveCount(1);
  await expect(doneMark).toHaveClass(/task-chain-mark-static/);
  expect(await doneMark.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');

  // Three distinct visuals for the three states under test.
  await expect(panel.locator('.task-chain-member[data-node-id="m2"] .task-node-spin')).toHaveCount(1); // running = spinner
  await expect(panel.locator('.task-chain-member[data-node-id="m3"] .task-chain-mark')).toHaveCount(0); // pending = no mark

  // A frame that does not change the status must not restart anything (the panel is fully
  // rebuilt per frame; without the diff the motion would replay every frame).
  await inject(page, { type: 'chainState', project: 'alpha', chainId: 'c-mo', status: 'active', pausedAt: null, cancelledAt: null });
  await expect(doneMark).toHaveClass(/task-chain-mark-static/);
  expect(await doneMark.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');

  // A real transition (running → completed) plays the draw exactly once.
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'm2',
    node: nodeJson({ id: 'm2', name: 'impl-two', agent: 'g', status: 'completed', createdAt: t0 + 1_000, completedAt: Date.now(), ttlLeftSec: 300, chainId: 'c-mo', in: ['m1'] }),
  });
  const m2Mark = panel.locator('.task-chain-member[data-node-id="m2"] .task-chain-mark-done');
  await expect(m2Mark).toHaveCount(1);
  await expect(m2Mark).not.toHaveClass(/task-chain-mark-static/);
  expect(await m2Mark.evaluate((el) => getComputedStyle(el).animationName)).toBe('task-chain-check-pop');
  // …and the very next repaint settles it (the transition frame is behind us).
  await inject(page, { type: 'chainState', project: 'alpha', chainId: 'c-mo', status: 'active', pausedAt: null, cancelledAt: null });
  await expect(panel.locator('.task-chain-member[data-node-id="m2"] .task-chain-mark-done')).toHaveClass(/task-chain-mark-static/);

  expect(pageErrors).toEqual([]);
});

test('O4 overflow notice: over 100 members render the first 100 rows plus the notice row', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 60 * 60_000;

  const nodes = [];
  const memberIds = [];
  for (let i = 0; i < 105; i++) {
    memberIds.push(`w${i}`);
    nodes.push(nodeJson({ id: `w${i}`, name: `work-${i}`, agent: 'g', status: 'pending', createdAt: t0 + i * 100, chainId: 'c-big' }));
  }
  await injectChainFixture(page, 'alpha', {
    chains: [{ id: 'c-big', title: 'big-chain', entries: ['w0'], ends: ['w104'], memberIds }],
    nodes,
  });

  const panel = page.locator('#task-list');
  const row = panel.locator('.task-chain[data-chain-key="alpha:c-big"]');
  await row.click();
  await expect(panel.locator('.task-chain-member')).toHaveCount(100);
  const more = panel.locator('.task-chain-more');
  await expect(more).toHaveCount(1);
  await expect(more).toHaveText('还有 5 位未显示');
  // The first 100 rows are the first 100 in topological order (all depth 0 ⇒ createdAt asc).
  await expect(panel.locator('.task-chain-member').first()).toHaveAttribute('data-node-id', 'w0');
  await expect(panel.locator('.task-chain-member').nth(99)).toHaveAttribute('data-node-id', 'w99');

  expect(pageErrors).toEqual([]);
});

test('O5 optimistic chain control: same-frame pausing state (<100ms), frame clears it, 404 rolls back + toasts', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  await injectChainFixture(page, 'alpha', {
    chains: [{ id: 'c-ctl', title: 'ctl-chain', entries: ['k1'], ends: ['k2'], memberIds: ['k1', 'k2'] }],
    nodes: [
      nodeJson({ id: 'k1', name: 'ctl-impl', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-ctl', in: [] }),
      nodeJson({ id: 'k2', name: 'ctl-verify', agent: 'g', status: 'pending', createdAt: t0 + 1_000, chainId: 'c-ctl', in: ['k1'], role: 'verifier' }),
    ],
  });

  const panel = page.locator('#task-list');
  const row = panel.locator('.task-chain[data-chain-key="alpha:c-ctl"]');
  const pauseBtn = row.locator('.task-chain-btn-pause');
  const cancelBtn = row.locator('.task-chain-btn-cancel');
  await expect(row.locator('.task-chain-badge')).toHaveText('运行');
  await expect(pauseBtn).toBeEnabled();

  // Slow chain-control endpoint: 900ms round-trip, well past the <100ms feedback budget.
  await page.route('**/api/projects/*/chains/*/*', async (r) => {
    await new Promise((res) => setTimeout(res, 900));
    return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ chainId: 'c-ctl', status: 'paused', pausedAt: Date.now(), cancelledAt: null }) });
  });

  // A-1 measurement, taken **inside the page** (a Playwright round-trip would dominate the
  // number): stamp a baseline, click, then poll until the optimistic state is in the DOM.
  const measured = await page.evaluate(async () => {
    const el = document.querySelector('.task-chain[data-chain-key="alpha:c-ctl"]');
    const t0 = performance.now();
    el.querySelector('.task-chain-btn-pause').click();
    for (let i = 0; i < 200; i++) {
      const now = document.querySelector('.task-chain[data-chain-key="alpha:c-ctl"]');
      const badge = now ? now.querySelector('.task-chain-badge') : null;
      if (now && now.getAttribute('data-chain-pending') === 'pause') {
        return {
          ms: performance.now() - t0,
          pending: now.getAttribute('data-chain-pending'),
          busy: now.getAttribute('aria-busy'),
          text: badge ? badge.textContent : '',
          pauseDisabled: now.querySelector('.task-chain-btn-pause').disabled,
          cancelDisabled: now.querySelector('.task-chain-btn-cancel').disabled,
        };
      }
      await new Promise((res) => requestAnimationFrame(res));
    }
    return null;
  });
  expect(measured).not.toBeNull();
  expect(measured.pending).toBe('pause');
  expect(measured.busy).toBe('true');
  expect(measured.text).toBe('暂停中');
  expect(measured.pauseDisabled).toBe(true);
  expect(measured.cancelDisabled).toBe(true);
  expect(measured.ms).toBeLessThan(100);

  await expect(row).toHaveAttribute('data-chain-pending', 'pause');
  await expect(row).toHaveAttribute('aria-busy', 'true');
  await expect(row.locator('.task-chain-badge')).toHaveText('暂停中');
  await expect(pauseBtn).toBeDisabled(); // both buttons locked in flight (§10.7(a))
  await expect(cancelBtn).toBeDisabled();

  // A `chainState` frame is the authority: it clears the in-flight state and applies the
  // engine state (here the 900ms response lands first and cleared it already; the frame
  // then sets pause and stays idempotent).
  await inject(page, { type: 'chainState', project: 'alpha', chainId: 'c-ctl', status: 'paused', pausedAt: Date.now(), cancelledAt: null });
  await expect(row).not.toHaveAttribute('data-chain-pending');
  await expect(row.locator('.task-chain-badge')).toHaveText('暂停');
  await expect(pauseBtn).toBeEnabled();
  await expect(pauseBtn).toHaveAttribute('title', '继续该链');

  expect(pageErrors).toEqual([]);
});

test('O5b rollback: a 404 clears the optimistic state, restores the server state and toasts', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  await injectChainFixture(page, 'alpha', {
    chains: [{ id: 'c-404', title: 'refuse-chain', entries: ['q1'], ends: ['q2'], memberIds: ['q1', 'q2'] }],
    nodes: [
      nodeJson({ id: 'q1', name: 'refuse-impl', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-404', in: [] }),
      nodeJson({ id: 'q2', name: 'refuse-verify', agent: 'g', status: 'pending', createdAt: t0 + 1_000, chainId: 'c-404', in: ['q1'], role: 'verifier' }),
    ],
  });

  const panel = page.locator('#task-list');
  const row = panel.locator('.task-chain[data-chain-key="alpha:c-404"]');
  const pauseBtn = row.locator('.task-chain-btn-pause');

  // Engine refusal leg: 404 with an actionable code (freeze-style rollback requirement).
  await page.route('**/api/projects/*/chains/*/*', (r) => r.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ error: 'CHAIN_SINGLE_MEMBER: chain needs at least two members' }),
  }));

  await pauseBtn.click();
  // In-flight is observable immediately…
  await expect(row).toHaveAttribute('data-chain-pending', 'pause');
  // …then rolls back: no in-flight state, engine state intact, both buttons live again.
  await expect(row).not.toHaveAttribute('data-chain-pending', { timeout: 3000 });
  await expect(row.locator('.task-chain-badge')).toHaveText('运行');
  await expect(pauseBtn).toBeEnabled();
  await expect(row).toHaveAttribute('data-chain-state', 'running');
  // Notification bar (§10.7(b) rollback ⇒ showToast).
  const toast = page.locator('.nebflow-toast');
  await expect(toast).toHaveCount(1);
  await expect(toast).toContainText('CHAIN_SINGLE_MEMBER');

  expect(pageErrors).toEqual([]);
});

test('O6 availability matrix + reduced motion: cancelled locks both buttons; reduced motion leaves zero animation', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  await injectChainFixture(page, 'alpha', {
    chains: [
      { id: 'c-pass', title: 'paused-chain', entries: ['z1'], ends: ['z1'], memberIds: ['z1'], status: 'paused' },
      { id: 'c-cancel', title: 'cancelled-chain', entries: ['z2'], ends: ['z2'], memberIds: ['z2'], status: 'cancelled' },
    ],
    nodes: [
      nodeJson({ id: 'z1', name: 'paused-work', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-pass' }),
      nodeJson({ id: 'z2', name: 'cancelled-work', agent: 'g', status: 'cancelled', createdAt: t0 + 500, completedAt: t0 + 900, ttlLeftSec: 300, chainId: 'c-cancel' }),
    ],
  });

  const panel = page.locator('#task-list');
  // paused: the cancel leg stays available (cancelling a paused chain is correct semantics).
  const paused = panel.locator('.task-chain[data-chain-key="alpha:c-pass"]');
  await expect(paused.locator('.task-chain-btn-pause')).toBeEnabled();
  await expect(paused.locator('.task-chain-btn-cancel')).toBeEnabled();
  // cancelled: both locked.
  const cancelled = panel.locator('.task-chain[data-chain-key="alpha:c-cancel"]');
  await expect(cancelled.locator('.task-chain-btn-pause')).toBeDisabled();
  await expect(cancelled.locator('.task-chain-btn-cancel')).toBeDisabled();

  expect(pageErrors).toEqual([]);
});

test('O6b reduced motion: marks land in their static final state with zero animation', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, true);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  await injectChainFixture(page, 'alpha', {
    chains: [{ id: 'c-rm', title: 'rm-chain', entries: ['y1'], ends: ['y3'], memberIds: ['y1', 'y2', 'y3'] }],
    nodes: [
      nodeJson({ id: 'y1', name: 'rm-impl', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 'c-rm', in: [] }),
      nodeJson({ id: 'y2', name: 'rm-verify', agent: 'g', status: 'completed', createdAt: t0 + 1_000, completedAt: t0 + 1_500, ttlLeftSec: 300, chainId: 'c-rm', in: ['y1'], role: 'verifier' }),
      nodeJson({ id: 'y3', name: 'rm-sink', agent: 'g', status: 'failed', createdAt: t0 + 2_000, completedAt: t0 + 2_500, ttlLeftSec: 300, chainId: 'c-rm', merge: true, in: ['y2'] }),
    ],
  });

  const panel = page.locator('#task-list');
  await panel.locator('.task-chain[data-chain-key="alpha:c-rm"]').click();
  await expect(panel.locator('.task-chain-member')).toHaveCount(3);

  // Spinner, check and cross all present but frozen.
  const spin = panel.locator('.task-chain-member[data-node-id="y1"] .task-node-spin');
  await expect(spin).toHaveCount(1);
  expect(await spin.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');
  const done = panel.locator('.task-chain-member[data-node-id="y2"] .task-chain-mark-done');
  await expect(done).toHaveCount(1);
  expect(await done.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');
  expect(await done.locator('path').evaluate((el) => getComputedStyle(el).strokeDashoffset)).toBe('0px');
  const failed = panel.locator('.task-chain-member[data-node-id="y3"] .task-chain-mark-failed');
  await expect(failed).toHaveCount(1);
  expect(await failed.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');

  expect(pageErrors).toEqual([]);
});
