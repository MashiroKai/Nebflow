// tasklist-chain-blocks.spec.mjs — task panel = chain blocks over the delegate
// plane (the classic flowmap-era chain-structure list) + the L1 map face
// (the flowmap engine re-driven on delegate data). Acceptance coverage:
//   T1 collapsed face: one block per live delegate, header row anatomy (twisty,
//      glyph, executor mark, #N, title, dotmap+meta jump zone, word, time),
//      members hidden by default, leaf placeholder alignment, project groups,
//      stats = total unit count
//   T2 fold interaction: header click expands (aria-expanded, member rows in
//      topological order with sequence labels + kind dots + status words),
//      re-collapse; fold state survives a re-render
//   T3 member row click → L1 map anchored (camera focus + flash), map structure
//      (nodes/edges), no chain-cancel on delegate scopes, node click = detail
//      card, root-card chevron folds the chain into the aggregate card
//   T4 display-only rows: external (open-native exit, no click-through) and
//      ready-to-merge (rank chip, members hidden, no fold)
//   T5 transition motion: a member status change across renders plays the
//      self-drawing check + word roll-in; first-paint terminal rows stay static
//   T6 camera: wheel zooms the world transform, double-click refits
//
// Data plane = stub read-port fixtures (same harness family as
// delegate-chain-stub.spec.mjs); zero backend, zero sbt.
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-chain-blocks.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'tcb-root-session';
const T0 = Date.now();

async function bootApp(page) {
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
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
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
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
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

/** Load stub fixtures and re-render the panel (harness seam). */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'tcb-root-session');
  }, fx);
  await page.waitForTimeout(100);
}

/** Expand a chain block and wait out the entry/pop animations. */
async function expandChain(page, taskNo) {
  await page.locator(`.task-chain-head[data-task-no="${taskNo}"]`).click();
  await page.waitForTimeout(200);
  await page.waitForFunction(
    () => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 },
  ).catch(() => {});
}

// ── Fixtures ─────────────────────────────────────────────────────────────
const rowA = {
  taskNo: '41', delegateId: 'delegate-nebflow-a41', title: '链甲任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  createdAt: T0 - 60_000,
};
const childrenA = [
  { taskId: 'subtask-a1', agentName: 'scout', description: '侦察仓库', status: 'running', source: 'subtask', spawnedAt: T0 - 30_000 },
  { taskId: 'subagent-a2', agentName: 'audit', description: '', prompt: '审计全部改动', status: 'completed', source: 'subagent', spawnedAt: T0 - 50_000, completedAt: T0 - 10_000 },
];
const chainA = {
  taskId: '41', delegateId: 'delegate-nebflow-a41',
  nodes: [
    { id: 'delegate-nebflow-a41', kind: 'delegate', label: '#41', description: '', status: 'running', deps: [], executor: '' },
    { id: 's1', kind: 'flow-step', label: '步骤一', description: '第一步描述', status: 'completed', deps: [], executor: '' },
    { id: 's2', kind: 'flow-step', label: '步骤二', description: '', status: 'running', deps: ['s1'], executor: '' },
  ],
  worktree: { branch: 'delegate/task-41', worktree: '/wt/41', cwd: '/wt/41', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'abc123', plugins: [], allocation: '' },
  verdict: null, mergeQueue: [], frames: [],
};
const rowB = {
  taskNo: '42', delegateId: 'delegate-zcode-b42', title: '外部任务乙',
  executor: 'zcode', status: 'running', external: true, project: '',
  createdAt: T0 - 240_000,
};
const rowC = {
  taskNo: '43', delegateId: 'delegate-claude-c43', title: 'docs 任务',
  executor: 'claude-code', status: 'ready-to-merge', external: false, project: 'docs',
  createdAt: T0 - 360_000,
  mergeQueuePos: { position: 1, total: 2, readyAt: T0 - 50_000, branch: 'delegate/task-43', baseBranch: 'main' },
};

test('T1 collapsed face: blocks + header anatomy + dotmap/meta + leaf placeholder + groups + stats', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [rowA, rowB, rowC], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA } });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // Blocks: 3 header rows; members hidden by default (collapsed face).
  await expect(panel.locator('.task-chain-head')).toHaveCount(3);
  await expect(panel.locator('.task-chain-member')).toHaveCount(0);

  // Header anatomy on the membered chain: twisty + glyph + icon + #N + title +
  // dotmap (root + 2 children + 2 flow-steps = 5 dots) + meta + word + time.
  const headA = panel.locator('.task-chain-head[data-task-no="41"]');
  await expect(headA.locator('.task-chain-twisty')).toHaveCount(1);
  await expect(headA).toHaveAttribute('aria-expanded', 'false');
  await expect(headA.locator('.task-node-orbit')).toHaveCount(1);
  await expect(headA.locator('.executor-icon-nebflow')).toHaveCount(1);
  await expect(headA.locator('.task-delegate-no')).toHaveText('#41');
  await expect(headA.locator('.task-node-label')).toHaveText('链甲任务');
  // The chain-level jump zone = the progress meta text (minimal face — the
  // dotmap thumbnail was dropped as noise). role=button + hint title + click
  // through to L1 is pinned in T6; here: presence + the n/N reading.
  await expect(headA.locator('.task-chain-jump')).toHaveCount(1);
  await expect(headA.locator('.task-chain-jump')).toHaveAttribute('role', 'button');
  await expect(panel.locator('.task-chain-map')).toHaveCount(0); // dotmap retired
  await expect(headA.locator('.task-chain-meta')).toHaveText(/^[12]\/5/); // audit+s1 done ⇒ 2/5
  await expect(headA.locator('.task-node-word')).toHaveText('进行中');
  await expect(headA.locator('.task-node-time')).toHaveCount(1);

  // Leaf roots carry the invisible twisty placeholder (text column aligned).
  const headB = panel.locator('.task-chain-head[data-task-no="42"]');
  await expect(headB.locator('.task-chain-twisty-leaf')).toHaveCount(1);
  await expect(headB.locator('.task-chain-jump')).toHaveCount(0);

  // Groups: 'Nebflow' + 'docs' headers + the headerless group (rowB).
  const headers = panel.locator('.task-node-group-header');
  await expect(headers).toHaveCount(2);
  await expect(headers.nth(0)).toHaveText('Nebflow');
  await expect(headers.nth(1)).toHaveText('docs');
  await expect(panel.locator('.task-node-group')).toHaveCount(3);

  // Stats = total unit count: chain A 5 units + external root 1 + queued root 1.
  await expect(panel.locator('.task-stats')).toHaveText('7 任务');

  expect(pageErrors).toEqual([]);
});

test('T2 fold: header click expands into topological member rows, re-collapses, state survives re-render', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA } });
  await expandChain(page, '41');

  const headA = page.locator('.task-chain-head[data-task-no="41"]');
  await expect(headA).toHaveAttribute('aria-expanded', 'true');

  // Topological order (depth↑, ts↑, id↑): s1 (entry flow) → audit → scout
  // (children, spawn-time order after s1's… ) — assert via the sequence labels
  // and names pairwise. Depths: root=0; s1/audit/scout=1; s2=2 (deps s1).
  const members = page.locator('.task-chain-member');
  await expect(members).toHaveCount(4);
  await expect(members.nth(0).locator('.task-chain-member-name')).toHaveText('步骤一');
  await expect(members.nth(0).locator('.task-chain-depth')).toHaveText('1.1');
  await expect(members.nth(1).locator('.task-chain-member-name')).toHaveText('audit');
  await expect(members.nth(2).locator('.task-chain-member-name')).toHaveText('scout');
  await expect(members.nth(3).locator('.task-chain-member-name')).toHaveText('步骤二');
  await expect(members.nth(3).locator('.task-chain-depth')).toHaveText('1.3.1');
  // Kind dots: flow-step = t-design, children = t-impl.
  await expect(members.nth(0).locator('.task-chain-dot.t-design')).toHaveCount(1);
  await expect(members.nth(1).locator('.task-chain-dot.t-impl')).toHaveCount(1);
  // Words + first-paint-static marks (completed members render the static check).
  await expect(members.nth(0).locator('.task-chain-member-word')).toHaveText('已完成');
  await expect(members.nth(0).locator('.task-chain-mark-static')).toHaveCount(1);
  await expect(members.nth(2).locator('.task-chain-member-word')).toHaveText('进行中');

  // Fold state survives a re-render (render-layer memory).
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await expect(page.locator('.task-chain-head[data-task-no="41"]')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('.task-chain-member')).toHaveCount(4);

  // Re-collapse.
  await page.locator('.task-chain-head[data-task-no="41"]').click();
  await expect(page.locator('.task-chain-member')).toHaveCount(0);
  await expect(page.locator('.task-chain-head[data-task-no="41"]')).toHaveAttribute('aria-expanded', 'false');

  expect(pageErrors).toEqual([]);
});

test('T3 member click → L1 map anchored; map structure; detail card on node click; chevron folds to chain card', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA } });
  await expandChain(page, '41');

  await page.locator('.task-chain-member[data-node-key="41:s2"]').click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '41');
  // Nav: identity slot keeps the chain title; the engine owns the counts summary.
  await expect(l1.locator('.delegate-chain-title')).toHaveText('#41 · 委托流程图');

  // Map structure: 5 node cards + 4 edges (root→scout, root→audit, root→s1, s1→s2).
  await expect(l1.locator('.delegate-flowmap-host .fm-node')).toHaveCount(5);
  await expect(l1.locator('.delegate-flowmap-host path.fm-edge')).toHaveCount(4);
  // Anchor: the clicked member flashes (camera focus + flash animation).
  await expect(l1.locator('.fm-node[data-node-id="s2"]')).toHaveCount(1);
  await expect(l1.locator('.fm-node[data-node-id="s2"].fm-node-flash')).toHaveCount(1);
  // Delegate scope carries no ChainLedger face: zero cancel buttons anywhere;
  // the root card carries the fold chevron; the per-project archive FAB is hidden.
  await expect(l1.locator('.fm-chain-cancel')).toHaveCount(0);
  await expect(l1.locator('.fm-chain-chev')).toHaveCount(1);
  const fabDisplay = await l1.locator('.fm-fab').evaluate((el) => getComputedStyle(el).display).catch(() => 'absent');
  expect(['none', 'absent']).toContain(fabDisplay);

  // Node click = the floating detail card (classic .fm-detail face).
  await l1.locator('.fm-node[data-node-id="s1"]').click();
  const detail = page.locator('.fm-detail');
  await expect(detail).toHaveClass(/open/);
  await expect(detail.locator('.fm-detail-title')).toHaveText('步骤一');
  await expect(detail.locator('.fm-detail-body')).toContainText('已完成');
  await page.keyboard.press('Escape');
  await expect(detail).toBeHidden();

  // Root-card chevron folds the chain into the aggregate chain card. The node
  // click above zoomed the camera onto s1 — refit first (double-click blank
  // world, the classic camera gesture) so the root card is in the viewport.
  const vpBox = await l1.locator('.delegate-flowmap-host .fm-viewport').boundingBox();
  await page.mouse.dblclick(vpBox.x + vpBox.width / 2, vpBox.y + vpBox.height - 12);
  await page.waitForTimeout(450); // camera fit animation
  await page.locator('.fm-chain-chev').click();
  await expect(l1.locator('.fm-node[data-chain-card="1"]')).toHaveCount(1);
  await expect(l1.locator('.fm-node[data-chain-card="1"] .fm-chain-cancel')).toHaveCount(0);
  // Chain card aggregates the five members; clicking it unfolds back.
  await expect(l1.locator('.fm-node[data-chain-card="1"]')).toContainText('5');
  await l1.locator('.fm-node[data-chain-card="1"]').click();
  await expect(l1.locator('.delegate-flowmap-host .fm-node')).toHaveCount(5);

  expect(pageErrors).toEqual([]);
});

test('T4 display-only rows: external (open-native exit, no fold, no click-through) + ready-to-merge (rank chip, members hidden)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  // rowC carries a chain with members in the fixture — the queued row must
  // still render display-only (no twisty content, no dotmap, no expand).
  const chainC = { ...chainA, taskId: '43', delegateId: 'delegate-claude-c43' };
  await setFixture(page, { rows: [rowA, rowB, rowC], chains: [chainA, chainC], children: { 'delegate-nebflow-a41': childrenA } });

  const rB = page.locator('.task-chain-head[data-task-no="42"]');
  await expect(rB).toHaveAttribute('data-external', '1');
  await expect(rB).not.toHaveAttribute('role', 'button');
  await expect(rB.locator('.task-delegate-btn')).toHaveCount(1); // open-native exit
  await expect(rB.locator('.task-chain-twisty-leaf')).toHaveCount(1);
  await rB.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);

  const rC = page.locator('.task-chain-head[data-task-no="43"]');
  await expect(rC).toHaveAttribute('data-delegate-status', 'ready-to-merge');
  await expect(rC.locator('.task-node-word')).toHaveText('待合并');
  await expect(rC.locator('.task-delegate-rank')).toHaveText('#1');
  await expect(rC).not.toHaveAttribute('role', 'button');
  await expect(rC.locator('.task-chain-twisty-leaf')).toHaveCount(1);
  await expect(rC.locator('.task-chain-jump')).toHaveCount(0);
  await rC.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);
  await expect(page.locator('.task-chain-member')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T5 transition motion: running→completed across renders plays the check draw + word roll; first-paint terminal stays static', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  const kidsRunning = [
    { taskId: 'subtask-a1', agentName: 'scout', description: '', status: 'running', source: 'subtask', spawnedAt: T0 - 30_000 },
  ];
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': kidsRunning } });
  await expandChain(page, '41');
  const kidRow = page.locator('.task-chain-member[data-node-key="41:subtask-a1"]');
  await expect(kidRow.locator('.task-chain-member-word')).toHaveText('进行中');
  await expect(kidRow.locator('.task-chain-mark .task-node-orbit')).toHaveCount(1);

  // Transition frame: the child completes → re-render plays the draw (non-static)
  // and the status word rolls in.
  const kidsDone = [
    { ...kidsRunning[0], status: 'completed', completedAt: Date.now() },
  ];
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': kidsDone } });
  const kidDone = page.locator('.task-chain-member[data-node-key="41:subtask-a1"]');
  await expect(kidDone.locator('.task-chain-member-word')).toHaveText('已完成');
  await expect(kidDone.locator('.task-chain-mark-done')).toHaveCount(1);
  await expect(kidDone.locator('.task-chain-mark-done')).not.toHaveClass(/task-chain-mark-static/);
  await expect(kidDone.locator('.task-chain-member-word')).toHaveClass(/task-chain-roll-in/);

  // Same-state re-render: no replay (static branch).
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': kidsDone } });
  await expect(page.locator('.task-chain-member[data-node-key="41:subtask-a1"] .task-chain-mark-static')).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});

test('T6 camera: wheel zooms the world transform; double-click refits', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA } });
  await expandChain(page, '41');
  await page.locator('.task-chain-head[data-task-no="41"] .task-chain-jump').click();
  const l1 = page.locator('.canvas-tab-pane[data-delegate-mode="task"]');
  await expect(l1.locator('.delegate-flowmap-host .solar-canvas')).toHaveCount(1);

  const transformOf = () => l1.locator('.delegate-flowmap-host .solar-canvas')
    .evaluate((el) => el.style.transform || '');
  const before = await transformOf();
  const vp = l1.locator('.delegate-flowmap-host .fm-viewport');
  const box = await vp.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, -240); // zoom in
  await page.waitForTimeout(150);
  const after = await transformOf();
  expect(after).not.toBe(before);
  expect(after).toContain('scale(');
  const scaleOf = (s) => { const m = /scale\(([\d.]+)\)/.exec(s); return m ? parseFloat(m[1]) : 1; };
  expect(scaleOf(after)).toBeGreaterThan(scaleOf(before));
  // Double-click on the blank world → animated refit (scale back to fit ≤1).
  await page.mouse.dblclick(box.x + box.width / 2, box.y + box.height - 12);
  await page.waitForTimeout(450);
  const refit = await transformOf();
  expect(scaleOf(refit)).toBeLessThanOrEqual(1);

  expect(pageErrors).toEqual([]);
});
