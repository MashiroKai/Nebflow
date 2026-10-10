// tasklist-flowmap-rows.spec.mjs — task panel = flat flowmap node rows over the
// delegate plane (the classic 9.19 flowmap task-list interaction model restored
// on delegate data). Acceptance coverage:
//   T1 flat rows: delegate roots + star children + flow-step chain nodes all
//      render as flat rows (NO inline expand tree — zero chevron / detail
//      container), member rows carry the chain badge (same-color dot + short
//      chain title), rows grouped by project with group headers
//   T2 click-through navigation: root row click → L1 chain view; flow-step row
//      click → L1 with expandFlow + anchored node card
//   T3 chain badge click → L1 whole chain (no flow anchor); stopPropagation
//      pinned (the row's own anchor-bearing click must not also fire)
//   T4 display-only rows: external executor row (no role=button, click opens
//      nothing, open-native exit kept) + ready-to-merge row (queue rank chip,
//      no click-through)
//   T5 grouping + ordering: project group headers, most-recent-activity first
//      inside a group, headerless group for project-less rows; header stats =
//      visible row count (members included)
//
// Data plane = stub read-port fixtures (same harness family as
// delegate-chain-stub.spec.mjs); zero backend, zero sbt.
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-flowmap-rows.spec.mjs

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

const ROOT_SID = 'tfr-root-session';
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

/** Load stub fixtures and re-render the panel (harness seam, same family as the
 *  delegate-chain-stub spec). */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'tfr-root-session');
  }, fx);
  await page.waitForTimeout(100);
}

// ── Fixtures ─────────────────────────────────────────────────────────────
// Internal delegate with two star children and a two-step AgentFlow chain.
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
    { id: 's1', kind: 'flow-step', label: '步骤一', description: '第一步描述', status: 'running', deps: [], executor: '' },
    { id: 's2', kind: 'flow-step', label: '步骤二', description: '', status: 'pending', deps: ['s1'], executor: '' },
  ],
  worktree: { branch: 'delegate/task-41', worktree: '/wt/41', cwd: '/wt/41', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'abc123', plugins: [], allocation: '' },
  verdict: null, mergeQueue: [], frames: [],
};
// External executor row (project-less) — display-only + open-native exit.
const rowB = {
  taskNo: '42', delegateId: 'delegate-zcode-b42', title: '外部任务乙',
  executor: 'zcode', status: 'running', external: true, project: '',
  createdAt: T0 - 240_000,
};
// Ready-to-merge row in another project — queue rank chip, display-only.
const rowC = {
  taskNo: '43', delegateId: 'delegate-claude-c43', title: 'docs 任务',
  executor: 'claude-code', status: 'ready-to-merge', external: false, project: 'docs',
  createdAt: T0 - 360_000,
  mergeQueuePos: { position: 1, total: 2, readyAt: T0 - 50_000, branch: 'delegate/task-43', baseBranch: 'main' },
};

test('T1 flat rows: roots + children + flow-steps flat, grouped by project, badges on member rows, zero tree machinery', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [rowA, rowB, rowC],
    chains: [chainA],
    children: { 'delegate-nebflow-a41': childrenA },
  });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // Zero tree machinery: no chevron, no inline detail container, no fold rows.
  await expect(panel.locator('.task-tree-chev')).toHaveCount(0);
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  await expect(panel.locator('.task-kids-list, .task-flow-row, .task-flow-nodes')).toHaveCount(0);
  await expect(panel.locator('.task-kids-badge, .task-flow-badge')).toHaveCount(0);

  // Flat rows: 3 roots + 2 children + 2 flow-steps = 7 visible rows.
  await expect(panel.locator('.task-node')).toHaveCount(7);
  await expect(panel.locator('.task-delegate')).toHaveCount(3);
  await expect(panel.locator('.task-member')).toHaveCount(4);

  // Project groups: 'Nebflow' + 'docs' headers, plus one headerless group (rowB).
  const headers = panel.locator('.task-node-group-header');
  await expect(headers).toHaveCount(2);
  await expect(headers.nth(0)).toHaveText('Nebflow');
  await expect(headers.nth(1)).toHaveText('docs');
  await expect(panel.locator('.task-node-group')).toHaveCount(3);

  // Root row anatomy: glyph + executor mark + #N + title + status word + time.
  const rA = panel.locator('.task-delegate[data-task-no="41"]');
  await expect(rA.locator('.task-node-orbit')).toHaveCount(1);
  await expect(rA.locator('.executor-icon-nebflow')).toHaveCount(1);
  await expect(rA.locator('.task-delegate-no')).toHaveText('#41');
  await expect(rA.locator('.task-node-label')).toHaveText('链甲任务');
  await expect(rA.locator('.task-node-word')).toHaveText('进行中');
  await expect(rA.locator('.task-node-time')).toHaveCount(1);
  // The root row carries NO chain badge (the row itself is the chain identity).
  await expect(rA.locator('.task-node-chain')).toHaveCount(0);
  await expect(rA).toHaveAttribute('role', 'button');

  // Member rows: chain badge with the owning chain's short title; the icon slot
  // is a fixed-width placeholder (text column stays aligned with root rows).
  const kidRow = panel.locator('.task-member[data-node-key="41‖subtask-a1"]');
  await expect(kidRow).toHaveCount(1);
  await expect(kidRow.locator('.task-node-label')).toHaveText('scout');
  await expect(kidRow.locator('.task-node-chain')).toHaveCount(1);
  await expect(kidRow.locator('.task-node-chain-name')).toHaveText('链甲任务');
  await expect(kidRow.locator('.task-delegate-icon-empty')).toHaveCount(1);
  await expect(kidRow.locator('.task-node-meta')).toHaveText('侦察仓库');
  await expect(kidRow.locator('.task-node-word')).toHaveText('进行中');
  await expect(kidRow.locator('.task-node-time')).toHaveCount(1);

  // Completed child: terminal glyph + word; meta falls back to the prompt text.
  const doneKid = panel.locator('.task-member[data-node-key="41‖subagent-a2"]');
  await expect(doneKid.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(doneKid.locator('.task-node-word')).toHaveText('已完成');
  await expect(doneKid.locator('.task-node-meta')).toHaveText('审计全部改动');

  // Flow-step rows: glyph per node status; description meta when present;
  // NO time slot (the chain projection carries no timestamps — no fabrication).
  const s1 = panel.locator('.task-member[data-node-key="41‖flow‖s1"]');
  await expect(s1.locator('.task-node-label')).toHaveText('步骤一');
  await expect(s1.locator('.task-node-orbit')).toHaveCount(1);
  await expect(s1.locator('.task-node-chain-name')).toHaveText('链甲任务');
  await expect(s1.locator('.task-node-meta')).toHaveText('第一步描述');
  await expect(s1.locator('.task-node-time')).toHaveCount(0);
  const s2 = panel.locator('.task-member[data-node-key="41‖flow‖s2"]');
  await expect(s2.locator('.task-node-box')).toHaveCount(1);
  await expect(s2.locator('.task-node-word')).toHaveText('待处理');

  expect(pageErrors).toEqual([]);
});

test('T2 row click-through: root row → L1 chain view; flow-step row → L1 with expandFlow + anchored node', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA },
  });

  // Root row click → L1 per-task chain view.
  await page.locator('.task-delegate[data-task-no="41"]').click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '41');
  await expect(l1.locator('.flowmap-summary')).toHaveText('#41 · 委托流程图');
  // Root click carries no flow anchor → the flow-step layer stays collapsed.
  await expect(l1.locator('.delegate-flow-steps')).toBeHidden();

  // Close the pane, then flow-step row click → L1 anchored (layer expanded).
  await page.locator('[data-back-to-tasks]').click();
  await expect(l1).toHaveCount(0);
  await page.locator('.task-member[data-node-key="41‖flow‖s2"]').click();
  const l1b = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1b).toHaveCount(1);
  await expect(l1b.locator('.delegate-flow-chain-toggle')).toHaveAttribute('aria-expanded', 'true');
  await expect(l1b.locator('.delegate-flow-steps')).toBeVisible();
  await expect(l1b.locator('.delegate-flow-steps .fm-node[data-node-id="s1"]')).toHaveCount(1);
  await expect(l1b.locator('.delegate-flow-steps .fm-node[data-node-id="s2"]')).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});

test('T3 chain badge click → L1 whole chain (no flow anchor); stopPropagation pinned', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [rowA], chains: [chainA], children: { 'delegate-nebflow-a41': childrenA },
  });

  // Badge on a FLOW-step row: opens L1 without the row's expandFlow anchor —
  // if the click bubbled into the row handler, the steps layer would open.
  const s1badge = page.locator('.task-member[data-node-key="41‖flow‖s1"] .task-node-chain');
  await expect(s1badge).toHaveAttribute('role', 'button');
  await s1badge.click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '41');
  await expect(l1.locator('.delegate-flow-steps')).toBeHidden(); // whole-chain view, no anchor

  expect(pageErrors).toEqual([]);
});

test('T4 display-only rows: external row (no click-through, open-native exit kept) + ready-to-merge row (rank chip, no click-through)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [rowA, rowB, rowC], chains: [chainA] });

  const rB = page.locator('.task-delegate[data-task-no="42"]');
  await expect(rB).toHaveAttribute('data-external', '1');
  await expect(rB).not.toHaveAttribute('role', 'button');
  await expect(rB.locator('.task-delegate-btn')).toHaveCount(1); // open-native exit
  await rB.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);

  const rC = page.locator('.task-delegate[data-task-no="43"]');
  await expect(rC).toHaveAttribute('data-delegate-status', 'ready-to-merge');
  await expect(rC.locator('.task-node-word')).toHaveText('待合并');
  await expect(rC.locator('.task-delegate-rank')).toHaveText('#1');
  await expect(rC).not.toHaveAttribute('role', 'button');
  await rC.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T5 grouping + ordering + stats: recent-first inside the group; headerless group last; stats = visible row count', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [rowA, rowB, rowC],
    chains: [chainA],
    children: { 'delegate-nebflow-a41': childrenA },
  });

  const panel = page.locator('#task-list');
  // Group order = first appearance in the globally activity-sorted list:
  // 'Nebflow' (child at T0-10s) → headerless (rowB at T0-240s) → 'docs'
  // (rowC at T0-360s).
  const groups = panel.locator('.task-node-group');
  await expect(groups).toHaveCount(3);
  await expect(groups.nth(0).locator('.task-node-group-header')).toHaveText('Nebflow');
  await expect(groups.nth(1).locator('.task-node-group-header')).toHaveCount(0);
  await expect(groups.nth(1).locator('.task-delegate')).toHaveAttribute('data-task-no', '42');
  await expect(groups.nth(2).locator('.task-node-group-header')).toHaveText('docs');
  await expect(groups.nth(2).locator('.task-delegate')).toHaveAttribute('data-task-no', '43');

  // Inside 'Nebflow': completed child (T0-10s) → running child (T0-30s) →
  // root (T0-60s) → flow-steps (chain root's time, source order s1, s2).
  const labels = groups.nth(0).locator('.task-node .task-node-label');
  await expect(labels).toHaveText(['audit', 'scout', '链甲任务', '步骤一', '步骤二']);

  // Stats = visible row count (roots + members).
  await expect(panel.locator('.task-stats')).toHaveText('7 任务');

  expect(pageErrors).toEqual([]);
});
