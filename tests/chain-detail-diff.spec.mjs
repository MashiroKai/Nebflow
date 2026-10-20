// chain-detail-diff.spec.mjs — the delegate-chain node detail's file-diff
// section fetches under the chain's REAL root project name (USTC audit ⑫B fix,
// report §B: the engine-face detail used to pass the scope key
// `delegate-chain:<key>` as the project name ⇒ GET
// /api/projects/delegate-chain%3A…/flow-map/nodes/<id>/file-changes 404'd by
// construction ⇒ diffView's "missing = silently skip" contract left the Changes
// section silently absent).
//
// Acceptance coverage:
//   T1 real-name chain (row.project = 'Nebflow'): clicking a completed
//      flow-step node opens the classic .fm-detail card and the Changes section
//      mounts via GET /api/projects/Nebflow/... (real name, 200) — NOT the
//      scope key. Section anatomy: head with file count and +/- totals, file
//      rows with change badge / path / counts, expandable unified diff body
//      with hunk headers and +/- colored rows.
//   T2 no-name chain (row.project = ''): the detail card still opens, the
//      Changes section is EXPLICITLY skipped — zero /file-changes network
//      calls (the scope-key URL must not be requested either; the silent-404
//      path is dead).
//   T3 classic project face unchanged: a per-project flow map's completed node
//      still fetches under its own project name (diffProjectFor identity leg).
//
// Backend face = route interception mirroring ProjectsRoutes (real project ⇒
// 200 report payload; anything else ⇒ 404 — exactly the production contract
// that used to expose the bug). Data plane = stub read-port fixtures (same
// harness family as tasklist-chain-blocks.spec.mjs); zero backend, zero sbt.
// Run: node node_modules/@playwright/test/cli.js test tests/chain-detail-diff.spec.mjs

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

const ROOT_SID = 'cdd-root-session';
const T0 = Date.now();

// One modified file with a real unified diff body (hunk header + ctx/add/del).
const DIFF_TEXT = [
  '@@ -3,3 +3,4 @@ context around the change',
  ' unchanged line',
  '-removed line',
  '+added line',
  '+added line two',
  ' tail context',
].join('\n');

const REPORT = {
  nodeId: 's1',
  generatedAt: T0,
  root: '/w',
  files: [{
    path: 'src/main/resources/web/js/flowMapArchive.js',
    changeType: 'modified',
    added: 2,
    removed: 1,
    source: 'git',
    diffText: DIFF_TEXT,
  }],
};

// Classic per-project engine snapshot (T3 identity leg — the pre-existing
// behavior the fix must not disturb).
const CLASSIC_FM = {
  nodes: [
    { id: 's1', name: '步骤一', status: 'completed', description: '', in: [], deps: [], out: [] },
  ],
  chains: [],
  meta: { project: 'Nebflow', updatedAt: T0 },
};

/** Requests to any /file-changes endpoint, in order (URL strings). */
function trackFileChangesRequests(page) {
  const urls = [];
  page.on('request', (req) => {
    if (req.url().includes('/file-changes')) urls.push(req.url());
  });
  return urls;
}

async function bootApp(page, { projectReportOk }) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      // Mirror the production contract: the per-node file-changes REST face is
      // addressed by REAL project name (ProjectsRoutes) — a scope key like
      // `delegate-chain:<key>` resolves to no project ⇒ 404 (what used to make
      // the section silently vanish); a real name ⇒ 200 with the report.
      const m = p.match(/^\/api\/projects\/([^/]+)\/flow-map\/nodes\/([^/]+)\/file-changes$/);
      if (m) {
        const project = decodeURIComponent(m[1]);
        if (projectReportOk && project === 'Nebflow') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(REPORT) });
        }
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"no such project"}' });
      }
      const fm = p.match(/^\/api\/projects\/[^/]+\/flow-map$/);
      if (fm) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CLASSIC_FM) });
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

/** Load stub fixtures. */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

/** Open the L1 delegate chain view the way every real entry does
 *  (openDelegateChainTask with the taskList rowMeta — project included). */
async function openL1(page, key, meta) {
  await page.evaluate(([k, m]) => {
    return import('/js/delegateChainView.js').then(({ openDelegateChainTask }) => openDelegateChainTask(k, m));
  }, [key, meta]);
  await page.waitForFunction(
    () => !!document.querySelector('.canvas-tab-pane[data-delegate-mode="task"] .delegate-flowmap-host .fm-node'),
    null, { timeout: 10000 },
  );
}

async function clickNode(page, nodeId) {
  await page.locator(`.canvas-tab-pane[data-delegate-mode="task"] .fm-node[data-node-id="${nodeId}"]`).click();
  await expect(page.locator('.fm-detail')).toHaveClass(/open/);
}

// ── Fixtures ─────────────────────────────────────────────────────────────
const rowA = {
  taskNo: '41', delegateId: 'delegate-nebflow-a41', title: '链甲任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  createdAt: T0 - 60_000,
};
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
// No-name chain: legacy/unmounted row (project '' = 无项目态).
const rowB = {
  taskNo: '42', delegateId: 'delegate-nebflow-b42', title: '无挂载任务',
  executor: 'nebflow', status: 'running', external: false, project: '',
  createdAt: T0 - 120_000,
};
const chainB = {
  ...chainA,
  taskId: '42', delegateId: 'delegate-nebflow-b42',
  nodes: [
    { id: 'delegate-nebflow-b42', kind: 'delegate', label: '#42', description: '', status: 'running', deps: [], executor: '' },
    { id: 't1', kind: 'flow-step', label: '节点甲', description: '', status: 'completed', deps: [], executor: '' },
  ],
};

test('T1 real-name chain: completed node detail mounts the Changes section via the REAL project name (not the scope key)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const fcUrls = trackFileChangesRequests(page);

  await bootApp(page, { projectReportOk: true });
  await setFixture(page, { rows: [rowA], chains: [chainA], children: {} });
  await openL1(page, '41', {
    taskNo: '41', delegateId: 'delegate-nebflow-a41', title: '链甲任务',
    status: 'running', executor: 'nebflow', project: 'Nebflow',
  });

  // Completed flow-step node → detail card → file-changes REST.
  await clickNode(page, 's1');
  const detail = page.locator('.fm-detail');
  await expect(detail.locator('.fm-detail-title')).toHaveText('步骤一');

  // The section mounts (lazily fetched) — under the REAL project name.
  const section = detail.locator('.nf-filechanges');
  await expect(section).toHaveCount(1);
  await expect(section.locator('.nf-filechanges-head')).toContainText('Changes · 1 file');
  await expect(section.locator('.nf-fc-path')).toHaveText('src/main/resources/web/js/flowMapArchive.js');
  await expect(section.locator('.nf-fc-badge-modified')).toHaveText('modified');
  await expect(section.locator('.nf-fc-added').first()).toHaveText('+2');
  await expect(section.locator('.nf-fc-removed').first()).toHaveText('−1');

  // Expand the file row → unified diff body: hunk header + +/- colored rows.
  await section.locator('summary.nf-fc-row').click();
  const addColor = await section.locator('.nf-diff-row.nf-diff-add').first()
    .evaluate((el) => getComputedStyle(el).color);
  const delColor = await section.locator('.nf-diff-row.nf-diff-del').first()
    .evaluate((el) => getComputedStyle(el).color);
  await expect(section.locator('.nf-diff-row.nf-diff-hunk')).toHaveCount(1);
  await expect(section.locator('.nf-diff-row.nf-diff-hunk')).toContainText('@@ -3,3 +3,4 @@');
  // +/- 着色：add 绿（g 通道占优）、del 红（r 通道占优）——按 RGB 通道判，不吃死色值。
  const rgb = (s) => (s.match(/\d+/g) || []).slice(0, 3).map(Number);
  const [ar, ag, ab] = rgb(addColor);
  const [dr, dg] = rgb(delColor);
  expect(ag).toBeGreaterThan(ar);
  expect(ag).toBeGreaterThan(ab);
  expect(dr).toBeGreaterThan(dg);

  // REST evidence: exactly one file-changes call, addressed by the real
  // project name; the scope-key URL is never requested.
  expect(fcUrls.length).toBe(1);
  expect(decodeURIComponent(fcUrls[0])).toContain('/api/projects/Nebflow/flow-map/nodes/s1/file-changes');
  expect(fcUrls[0]).not.toContain(encodeURIComponent('delegate-chain:'));

  expect(pageErrors).toEqual([]);
});

test('T2 no-name chain (project = ""): detail still opens, diff section EXPLICITLY skipped — zero file-changes network calls', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const fcUrls = trackFileChangesRequests(page);

  await bootApp(page, { projectReportOk: true });
  await setFixture(page, { rows: [rowB], chains: [chainB], children: {} });
  await openL1(page, '42', {
    taskNo: '42', delegateId: 'delegate-nebflow-b42', title: '无挂载任务',
    status: 'running', executor: 'nebflow', project: '',
  });

  await clickNode(page, 't1');
  const detail = page.locator('.fm-detail');
  await expect(detail.locator('.fm-detail-title')).toHaveText('节点甲');
  // Detail face still renders (result section present)…
  await expect(detail.locator('.fm-detail-body')).toContainText('已完成');
  // …and the Changes section is explicitly skipped: not mounted, and NO
  // /file-changes request left the page (the old scope-key 404 path is dead).
  await expect(detail.locator('.nf-filechanges')).toHaveCount(0);
  await page.waitForTimeout(400); // would-be lazy fetch window
  expect(fcUrls).toEqual([]);

  expect(pageErrors).toEqual([]);
});

test('T3 classic per-project flow map unchanged: completed node fetches under its own project name', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const fcUrls = trackFileChangesRequests(page);

  await bootApp(page, { projectReportOk: true });
  // Classic scope: drive the real render pipeline (default REST fetch seam —
  // the flow-map GET is served by the route above).
  await page.evaluate(async () => {
    const { renderFlowMapInto } = await import('/js/flowMapTab.js');
    const host = document.createElement('div');
    host.className = 'flowmap-view-body';
    host.style.cssText = 'position:relative;width:1100px;height:600px';
    document.body.appendChild(host);
    renderFlowMapInto(host, 'Nebflow');
  });
  await page.waitForFunction(
    () => !!document.querySelector('.flowmap-view-body .fm-node[data-node-id="s1"]'),
    null, { timeout: 10000 },
  );

  await page.locator('.flowmap-view-body .fm-node[data-node-id="s1"]').click();
  await expect(page.locator('.fm-detail')).toHaveClass(/open/);
  await expect(page.locator('.fm-detail .nf-filechanges')).toHaveCount(1);
  expect(fcUrls.length).toBe(1);
  expect(decodeURIComponent(fcUrls[0])).toContain('/api/projects/Nebflow/flow-map/nodes/s1/file-changes');

  expect(pageErrors).toEqual([]);
});
