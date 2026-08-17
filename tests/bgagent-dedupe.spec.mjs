// bgagent-dedupe.spec.mjs — bg-agent dropdown cross-keyspace dedupe (Teams
// 双条目 ghost regression, 2026-08-17).
//
// Root cause: sessionBgAgents has two keyspaces for the same agent —
// snapshot-restored rows key on the bare sessionId (getActiveAgents pins
// agentId == sessionId), live agentStart rows key on the actor-path agentId
// (mail-*). A page loaded while a team agent is busy gets the snapshot row;
// the agent's next live turn then added a second row → the dropdown showed
// e.g. "running Frontend" + "running Frontend · nebflow-project/Frontend".
// Fix: the agentStart handler drops the snapshot-keyed twin via
// nodeSessionId (team- prefix stripped).
//
// Self-contained: serves src/main/resources/web via route interception and
// mocks the WS boot handshake — no backend, no sbt.
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bgagent-dedupe.spec.mjs

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

const ROOT_SID = 'e2e-root-session';
const TEAM_SID = 'aac1c33a-c2d1-4b13-9ecd-3d5c4dc37f88';

/** Minimal boot: configData (onboarding terminal) + sessionList with an active root session. */
async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  // Static files from the repo working tree.
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
  // Wait until the WS handler registry is live: the primary view must be
  // bound to the root session before indicator updates can find it.
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** Drive an inbound WS event through the real handler chain. */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

function bgEntries(page) {
  return page.evaluate(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    const bucket = s.sessionBgAgents[rootSid] || {};
    return Object.entries(bucket).map(([key, v]) => ({ key, name: v.name, task: v.task, done: v.done }));
  }, ROOT_SID);
}

test('snapshot row + live agentStart for same team agent render once (ghost-dup regression)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 1. Snapshot restore path: page loads while the team agent is busy →
  //    getActiveAgents response keys the row on the bare sessionId, task=''.
  await inject(page, {
    type: 'activeAgents',
    agents: [{
      sessionId: TEAM_SID, agentId: TEAM_SID,
      agentName: 'Frontend', rootSessionId: ROOT_SID, kind: 'Team',
    }],
  });
  let entries = await bgEntries(page);
  expect(entries).toHaveLength(1);
  expect(entries[0].key).toBe(TEAM_SID);
  expect(entries[0].task).toBe('');

  // 2. The agent's next live turn emits agentStart keyed on the actor path
  //    (mail-*), carrying the qualified sessionName as taskDescription.
  //    Pre-fix this produced a second row; post-fix the snapshot twin is dropped.
  await inject(page, {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: 'mail-aac1c33a', name: 'Frontend', agentType: 'team',
    taskDescription: 'nebflow-project/Frontend',
    nodeSessionId: `team-${TEAM_SID}`,
  });
  entries = await bgEntries(page);
  expect(entries).toHaveLength(1);
  expect(entries[0].key).toBe('mail-aac1c33a');
  expect(entries[0].task).toBe('nebflow-project/Frontend');

  // 3. The dropdown renders exactly one row for the agent.
  const indicator = page.locator('#bgagent-indicator');
  await expect(indicator).toBeVisible();
  await indicator.click();
  const rows = page.locator('#bgagent-dropdown .bg-task-row');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('running Frontend · nebflow-project/Frontend');

  // 4. Turn end clears the row (W1-d dual-key cleanup still intact).
  await inject(page, {
    type: 'agentDone',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: 'mail-aac1c33a',
    nodeSessionId: `team-${TEAM_SID}`,
  });
  await page.waitForTimeout(2300); // rows are removed 2s after done
  entries = await bgEntries(page);
  expect(entries).toHaveLength(0);

  expect(pageErrors).toEqual([]);
});

test('agentStart without snapshot twin still works (no regression for live-only flow)', async ({ page }) => {
  await bootApp(page);
  await inject(page, {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: 'delegate-abc123', name: 'Explorer', agentType: 'delegate',
    taskDescription: 'survey codebase',
    nodeSessionId: 'delegate-abc123',
  });
  const entries = await bgEntries(page);
  expect(entries).toHaveLength(1);
  expect(entries[0].key).toBe('delegate-abc123');
  expect(entries[0].task).toBe('survey codebase');
});
