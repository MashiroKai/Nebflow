// subagent-panel-cancel-realtime.spec.mjs — Sub-Agents 面板取消/终止场景实时刷新验收。
//
// 缺口：node-/dispatcher- 会话被取消（AgentControl cancel / 面板 cancelAgent /
// TaskStuckWatcher giveUp / 观察桥 Failed+Cancelled）后，后端此前不发任何面板
// 可理解的事件——面板行由 agentStart 创建、只被 agentDone / 会话级 done 清理，
// 取消后 Processing 幽灵行滞留到浏览器刷新。修复：后端在取消链路补发 agentDone
// 同构帧（agentId=nodeSessionId=会话 id，rootSessionId 归桶键由路由包装注入），
// 前端复用既有终态管线（ws.js 转换为会话级 done → 立即删行 + 归属视图徽标/面板
// 即时刷新）。
//
// 注入帧 = 修复后后端的真实出帧形状（与活体 agentStart/agentDone 契约同构：
// protocol.scala toJson + NodeRunner.routeSubagentWsSend 注入三键），走真实
// ws.js 分发路径（s.ws.onmessage）注入——拦截器（bgAgentPopup → 弹窗视图）、
// 转换（agentDone → 会话级 done）、面板状态机全真。
//
// Run: node node_modules/@playwright/test/cli.js test tests/subagent-panel-cancel-realtime.spec.mjs

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

const ROOT_SID = 'panel-root-session';
const T0 = Date.now();

// 后端 agentStart 真实形状（subagent 分支）：{type, agentId, name, agentType,
// taskDescription?} + routeSubagentWsSend 注入 rootSessionId/sessionId/nodeSessionId。
function agentStartFrame(sessionId, over = {}) {
  return {
    type: 'agentStart',
    agentId: sessionId,
    name: 'Manager',
    agentType: 'project-dispatcher',
    taskDescription: '建一个调研节点',
    sessionId: ROOT_SID,
    rootSessionId: ROOT_SID,
    nodeSessionId: sessionId,
    ...over,
  };
}

// 修复后取消链路的真实出帧形状：NodeRunner.emitSubagentPanelDone 构造
// {type:'agentDone', agentId} 后经 routeSubagentWsSend 注入三键。
function cancelDoneFrame(sessionId) {
  return {
    type: 'agentDone',
    agentId: sessionId,
    sessionId: ROOT_SID,
    rootSessionId: ROOT_SID,
    nodeSessionId: sessionId,
  };
}

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
      } else if (msg.type === 'getActiveAgents') {
        // 快照基线恒空：行只来自实时事件注入（本 spec 不测快照恢复）。
        ws.send(JSON.stringify({ type: 'activeAgents', agents: [] }));
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

/** 走真实 ws.js 分发路径注入一帧（与后端广播等价）。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 打开 Sub-Agents 面板（indicator 点击 → dropdown 展开）。 */
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bgagent-dropdown:not(.hidden)', { timeout: 8000 });
}

test.describe('Sub-Agents 面板取消/终止实时刷新', () => {
  test('dispatcher-* 会话取消：注入 agentStart 出行 → 注入取消 agentDone 帧 → 行 2s 内移除（无刷新）', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    const SID = 'dispatcher-ab12cd34';
    // 无刷新探针：整轮断言期间页面不得导航/reload。
    await page.evaluate(() => { window.__noReloadProbe = Symbol('alive'); });

    await inject(page, agentStartFrame(SID));
    await openPanel(page);
    const row = page.locator(`.bg-task-row[data-node-session-id="${SID}"]`);
    await expect(row).toHaveCount(1);
    await expect(row.locator('.bg-task-kind')).toHaveText('Flow');
    await expect(row.locator('.bg-task-status.bg-status-active')).toHaveCount(1);
    // 徽标计数 = 1
    await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('1');

    // ── 被测行为：取消 → 后端补发 agentDone 帧 → 行实时移除 ──────────
    await inject(page, cancelDoneFrame(SID));

    // 2s 内行消失（会话级 done 分支同帧删行 + 归属视图渲染刷新）
    await expect(row).toHaveCount(0, { timeout: 2000 });
    // 面板转空态（非陈旧列表）
    await expect(page.locator('#bgagent-dropdown .bg-dropdown-empty')).toHaveCount(1);
    // 徽标隐藏（计数归零）
    await expect(page.locator('#bgagent-indicator.hidden')).toHaveCount(1);
    // 状态机已清（后续 activeAgents 快照/事件不复活幽灵行）
    const bucketEmpty = await page.evaluate(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return !s.sessionBgAgents[sid] || Object.keys(s.sessionBgAgents[sid]).length === 0;
    }, ROOT_SID);
    expect(bucketEmpty).toBe(true);
    // 无刷新：探针仍活、URL 未变
    expect(await page.evaluate(() => Boolean(window.__noReloadProbe))).toBe(true);
    expect(page.url()).toContain('localhost:1');
    expect(pageErrors).toEqual([]);
  });

  test('node-* 会话取消：同契约 2s 内移除', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    const SID = 'node-9988aabb';
    await inject(page, agentStartFrame(SID, { agentType: 'researcher', name: 'researcher', taskDescription: '跑实验' }));
    await openPanel(page);
    const row = page.locator(`.bg-task-row[data-node-session-id="${SID}"]`);
    await expect(row).toHaveCount(1);

    await inject(page, cancelDoneFrame(SID));
    await expect(row).toHaveCount(0, { timeout: 2000 });
    await expect(page.locator('#bgagent-indicator.hidden')).toHaveCount(1);
    expect(pageErrors).toEqual([]);
  });

  test('回归：取消不影响既有实时场景——新会话出行 / Processing 工具标签 / delegate 终态清理', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    // ① 新会话实时出行（面板新增实时，先于取消修复既有）：注入 agentStart 后
    //    打开面板，行立即可见（状态实时入库；面板渲染于展开时对齐状态）。
    const DISPATCHER = 'dispatcher-live1111';
    const DELEGATE = 'delegate-Explorer-9f8e7d6c';
    await inject(page, agentStartFrame(DISPATCHER));
    await inject(page, agentStartFrame(DELEGATE, {
      agentType: 'Explorer', name: 'Explorer', taskDescription: '搜代码',
      agentId: DELEGATE, nodeSessionId: DELEGATE,
    }));
    // ② Processing 状态实时：agentToolStart → 行内工具标签（事件先于开面板
    //    注入——开面板时的渲染读的是实时状态，非陈旧快照）。
    await inject(page, {
      type: 'agentToolStart', agentId: DISPATCHER, label: 'NodeEdit',
      sessionId: ROOT_SID, rootSessionId: ROOT_SID, nodeSessionId: DISPATCHER,
    });
    await openPanel(page);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"]`)).toHaveCount(1);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DELEGATE}"]`)).toHaveCount(1);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"] .bgagent-tool`)).toContainText('NodeEdit');

    // ③ delegate 会话终态（既有 agentDone 路径回归）：2s 定时器移除行。
    await inject(page, cancelDoneFrame(DELEGATE));
    const delegateRow = page.locator(`.bg-task-row[data-node-session-id="${DELEGATE}"]`);
    await expect(delegateRow).toHaveCount(0, { timeout: 4000 });

    // dispatcher 行不受 delegate 清理影响（同桶多行各自独立）
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"]`)).toHaveCount(1);
    expect(pageErrors).toEqual([]);
  });
});
