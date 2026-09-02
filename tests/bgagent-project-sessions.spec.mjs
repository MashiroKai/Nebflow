// bgagent-project-sessions.spec.mjs — #28 subagent 面板可观测接线回归
// (dispatcher/node 会话在 Sub-Agents 面板可见, 与 Delegate/SubTask 同一标准)。
//
// 背景根因 (2026-09-02 Coder 取证):
//  1. 后端: 启动挂载的项目 engine.wsSendFn 为 no-op (GatewayMain mountAll 传
//     None + wsHub 在挂载后才创建) → 节点/分发器事件到不了前端。
//  2. 后端: 节点/分发器 agent 的 wsSend 未走路由包装 (DelegateTool.routeWsSend
//     同款) → 事件缺 rootSessionId (sessionBgAgents 归桶键)。
//  3. 前端: agentStart / activeAgents 显式过滤 node-* → 节点行永不显示;
//     isBgAgentId 不认 node-/dispatcher- → popup 管线不接。
//  4. 分发器: 完成不回注册表 → getActiveAgents 幽灵行。
//
// 本测试锁定修复后的前端契约 (后端事件形状 = NodeRunner.routeSubagentWsSend
// 包装后的真实 payload):
//  - agentStart (nodeSessionId=node-*/dispatcher-*, rootSessionId, sessionId)
//    → 行出现在 root 桶, name/task/kind=Flow, status Processing。
//  - agentToolStart → currentTool 更新 (工具调用过程可见)。
//  - session-level done (sessionId=node-*/dispatcher-*; 父级 None 的 agent 以
//    done 而非 agentDone 收尾) → 行移除。
//  - activeAgents 快照 (kind=Flow, node-*/dispatcher-*) → 刷新后行恢复。
//
// Run: node node_modules/@playwright/test/cli.js test tests/bgagent-project-sessions.spec.mjs

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

async function bootApp(page) {
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
    return Object.entries(bucket).map(([key, v]) => ({
      key, name: v.name, task: v.task, kind: v.kind, status: v.status,
      currentTool: v.currentTool, done: v.done,
    }));
  }, ROOT_SID);
}

function isBgAgentId(page, id) {
  return page.evaluate(async (sid) => {
    const u = await import('/js/utils.js');
    return u.isBgAgentId(sid);
  }, id);
}

test('node session: agentStart + agentToolStart rows visible; session-level done removes row', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 后端 wrapper (routeSubagentWsSend) 真实形状: agentStart 无 sessionId 字段,
  // wrapper 注入 rootSessionId + sessionId=rootSessionId; toJson 盖章 nodeSessionId。
  await inject(page, {
    type: 'agentStart',
    agentId: 'node-819',
    name: 'test-agent',
    agentType: 'standalone',
    taskDescription: '调研-幽灵行',
    nodeSessionId: 'node-819',
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });

  let entries = await bgEntries(page);
  expect(entries).toHaveLength(1);
  expect(entries[0].key).toBe('node-819');
  expect(entries[0].name).toBe('test-agent');
  expect(entries[0].task).toBe('调研-幽灵行');
  expect(entries[0].kind).toBe('Flow'); // bgAgentKindFromSession('node-*') → Flow
  expect(entries[0].status).toBe('Processing');
  expect(entries[0].done).toBe(false);

  // 工具调用过程: agentToolStart → currentTool 显示
  await inject(page, {
    type: 'agentToolStart',
    agentId: 'node-819',
    label: 'NodeList',
    nodeSessionId: 'node-819',
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });
  entries = await bgEntries(page);
  expect(entries[0].currentTool).toBe('NodeList');

  // 节点收尾: parentRef=None → 会话级 done (sessionId=node-*), 非 agentDone
  await inject(page, {
    type: 'done',
    sessionId: 'node-819',
    rootSessionId: ROOT_SID,
  });
  entries = await bgEntries(page);
  expect(entries).toHaveLength(0);

  expect(pageErrors).toEqual([]);
});

test('dispatcher session: visible like node; done (dispatcher-*) removes row', async ({ page }) => {
  await bootApp(page);

  await inject(page, {
    type: 'agentStart',
    agentId: 'dispatcher-3f2a',
    name: 'project-dispatcher',
    agentType: 'standalone',
    taskDescription: 'dispatcher/czt-project',
    nodeSessionId: 'dispatcher-3f2a',
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });

  let entries = await bgEntries(page);
  expect(entries).toHaveLength(1);
  expect(entries[0].key).toBe('dispatcher-3f2a');
  expect(entries[0].kind).toBe('Flow');
  expect(entries[0].status).toBe('Processing');

  await inject(page, {
    type: 'agentToolStart',
    agentId: 'dispatcher-3f2a',
    label: 'NodeEdit',
    nodeSessionId: 'dispatcher-3f2a',
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });
  entries = await bgEntries(page);
  expect(entries[0].currentTool).toBe('NodeEdit');

  await inject(page, {
    type: 'done',
    sessionId: 'dispatcher-3f2a',
    rootSessionId: ROOT_SID,
  });
  entries = await bgEntries(page);
  expect(entries).toHaveLength(0);
});

test('activeAgents snapshot restores node-/dispatcher- rows (source filter removed)', async ({ page }) => {
  await bootApp(page);

  await inject(page, {
    type: 'activeAgents',
    agents: [
      {
        sessionId: 'node-7a1', agentId: 'node-7a1',
        agentName: 'test-agent', rootSessionId: ROOT_SID, kind: 'Flow',
        task: '仿真跑批', status: 'Processing', startedAt: Date.now() - 5000, retryCount: 0,
      },
      {
        sessionId: 'dispatcher-9c4', agentId: 'dispatcher-9c4',
        agentName: 'project-dispatcher', rootSessionId: ROOT_SID, kind: 'Flow',
        task: 'dispatcher/demo', status: 'Processing', startedAt: Date.now() - 1000, retryCount: 0,
      },
    ],
  });

  const entries = await bgEntries(page);
  expect(entries).toHaveLength(2);
  const node = entries.find(e => e.key === 'node-7a1');
  const disp = entries.find(e => e.key === 'dispatcher-9c4');
  expect(node).toBeTruthy();
  expect(node.name).toBe('test-agent');
  expect(node.kind).toBe('Flow');
  expect(node.status).toBe('Processing');
  expect(disp).toBeTruthy();
  expect(disp.task).toBe('dispatcher/demo');
});

test('isBgAgentId admits node-/dispatcher- (bg-agent popup pipeline gate)', async ({ page }) => {
  await bootApp(page);
  expect(await isBgAgentId(page, 'node-819')).toBe(true);
  expect(await isBgAgentId(page, 'dispatcher-3f2a')).toBe(true);
  // 既有前缀不受影响
  expect(await isBgAgentId(page, 'delegate-coder-abc')).toBe(true);
  expect(await isBgAgentId(page, 'subtask-123')).toBe(true);
  expect(await isBgAgentId(page, 'team-sess')).toBe(false);
  expect(await isBgAgentId(page, 'dag-xyz')).toBe(false);
  expect(await isBgAgentId(page, 'sess-1')).toBe(false);
});

test('node/dispatcher rows render in the dropdown (name · task + Flow kind chip)', async ({ page }) => {
  await bootApp(page);

  await inject(page, {
    type: 'agentStart',
    agentId: 'node-42',
    name: 'test-agent',
    agentType: 'standalone',
    taskDescription: '调研',
    nodeSessionId: 'node-42',
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });

  // 打开下拉 (primary view 的指示器)
  await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    const v = s.getActiveView && s.getActiveView();
    if (v && v.dom && v.dom.bgagentIndicatorEl) v.dom.bgagentIndicatorEl.click();
  });
  const rows = page.locator('#bgagent-dropdown .bg-task-row');
  await expect(rows).toHaveCount(1);
  // 名称行: name · task; kind 徽章: Flow
  await expect(rows.first()).toContainText('test-agent');
  await expect(rows.first()).toContainText('调研');
  await expect(rows.first()).toContainText('Flow');
});
