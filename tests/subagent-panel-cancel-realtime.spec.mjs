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

/** 打开 Sub-Agents 面板（indicator 点击 → 下拉展开）。真面 = #bg-dropdown
 * （面板合并批换代口径；#bgagent-dropdown 为恒 hidden 幽灵面）。 */
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
}

test.describe('Sub-Agents 面板取消/终止实时刷新', () => {
  // Flow 会话可见性批（2026-11-01）口径更新：C1 过渡过滤退役——dispatcher-*/node-*
  // 行与 Delegate/SubTask 同一陈列标准（顶层直显 + 分发器/节点 kind 徽标 + 计数
  // 照计）。本套用例的被测行为（取消 → 后端补发 agentDone → 桶内条目实时移除，
  // 无刷新）不变；渲染断言随新口径翻转（行在列 → 终态删除后离场）。
  test('dispatcher-* 会话：顶层直显（分发器徽标·计数 1）；取消 agentDone 桶内终态清理 + 行离场（无刷新）', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    const SID = 'dispatcher-ab12cd34';
    // 无刷新探针：整轮断言期间页面不得导航/reload。
    await page.evaluate(() => { window.__noReloadProbe = Symbol('alive'); });

    await inject(page, agentStartFrame(SID));
    // Flow 会话可见性批（2026-11-01）：行顶层直显 + kind 徽标「分发器」；计数 1
    // （同桶同口径——badge 与列表永不打架的既定纪律）。
    const row = page.locator(`.bg-task-row[data-node-session-id="${SID}"]`);
    await openPanel(page);
    await expect(row).toHaveCount(1);
    await expect(row.locator('.bg-task-kind')).toHaveText('分发器');
    await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('1');
    // 数据侧在册（快照/管理面语义不变）
    const bucketHas = await page.evaluate(async (sid) => {
      const s = (await import('/js/state.js')).default;
      const bucket = s.sessionBgAgents[Object.keys(s.sessionBgAgents)[0]] || {};
      return Boolean(bucket[sid]);
    }, SID);
    expect(bucketHas).toBe(true);

    // ── 被测行为：取消 → 后端补发 agentDone 帧 → 桶内条目实时移除（终态管线回归）──
    await inject(page, cancelDoneFrame(SID));
    const bucketEmpty = await page.evaluate(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return !s.sessionBgAgents[sid] || Object.keys(s.sessionBgAgents[sid]).length === 0;
    }, ROOT_SID);
    expect(bucketEmpty).toBe(true);
    // 行实时离场；徽标随计数归零隐藏（终态清理与计数不打架）
    await expect(page.locator(`.bg-task-row[data-node-session-id="${SID}"]`)).toHaveCount(0);
    await expect(page.locator('#bgagent-indicator.hidden')).toHaveCount(1);
    // 无刷新：探针仍活、URL 未变
    expect(await page.evaluate(() => Boolean(window.__noReloadProbe))).toBe(true);
    expect(page.url()).toContain('localhost:1');
    expect(pageErrors).toEqual([]);
  });

  test('node-* 会话：顶层直显（节点徽标）；取消 agentDone 同契约桶内清理 + 行离场', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    const SID = 'node-9988aabb';
    await inject(page, agentStartFrame(SID, { agentType: 'researcher', name: 'researcher', taskDescription: '跑实验' }));
    await openPanel(page);
    const row = page.locator(`.bg-task-row[data-node-session-id="${SID}"]`);
    await expect(row).toHaveCount(1);
    await expect(row.locator('.bg-task-kind')).toHaveText('节点');
    await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('1');

    await inject(page, cancelDoneFrame(SID));
    await expect(row).toHaveCount(0, { timeout: 2000 });
    await expect(page.locator('#bgagent-indicator.hidden')).toHaveCount(1);
    expect(pageErrors).toEqual([]);
  });

  test('回归：取消不影响既有实时场景——delegate 行实时出行与工具标签 / 终态清理；dispatcher 行同面直显且不受连坐', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);

    // ① 新会话实时出行（面板新增实时，先于取消修复既有）：注入 agentStart 后
    //    打开面板，行立即可见（状态实时入库；面板渲染于展开时对齐状态）。
    //    dispatcher 行 = 同面直显（Flow 会话可见性批 2026-11-01，C1 退役）。
    const DISPATCHER = 'dispatcher-live1111';
    const DELEGATE = 'delegate-Explorer-9f8e7d6c';
    await inject(page, agentStartFrame(DISPATCHER));
    await inject(page, agentStartFrame(DELEGATE, {
      agentType: 'Explorer', name: 'Explorer', taskDescription: '搜代码',
      agentId: DELEGATE, nodeSessionId: DELEGATE,
    }));
    // ② Processing 状态实时：agentToolStart → 行内工具标签（事件先于开面板
    //    注入——开面板时的渲染读的是实时状态，非陈旧快照）。标签腿挂 delegate
    //    行（工具标签语义由可见行承载——dispatcher 行同样可见，各行独立）。
    await inject(page, {
      type: 'agentToolStart', agentId: DELEGATE, label: 'NodeEdit',
      sessionId: ROOT_SID, rootSessionId: ROOT_SID, nodeSessionId: DELEGATE,
    });
    await openPanel(page);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"]`)).toHaveCount(1);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"] .bg-task-kind`)).toHaveText('分发器');
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DELEGATE}"]`)).toHaveCount(1);
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DELEGATE}"] .bgagent-tool`)).toContainText('NodeEdit');

    // ③ delegate 会话终态（既有 agentDone 路径回归）：2s 定时器移除行。
    await inject(page, cancelDoneFrame(DELEGATE));
    const delegateRow = page.locator(`.bg-task-row[data-node-session-id="${DELEGATE}"]`);
    await expect(delegateRow).toHaveCount(0, { timeout: 4000 });

    // dispatcher 条目不受 delegate 清理影响（同桶多行各自独立——行保持在列，
    // 数据侧条目语义不变）
    await expect(page.locator(`.bg-task-row[data-node-session-id="${DISPATCHER}"]`)).toHaveCount(1);
    const bucketHasDispatcher = await page.evaluate(async (sid) => {
      const s = (await import('/js/state.js')).default;
      const bucket = s.sessionBgAgents[Object.keys(s.sessionBgAgents)[0]] || {};
      return Boolean(bucket[sid]);
    }, DISPATCHER);
    expect(bucketHasDispatcher).toBe(true);
    expect(pageErrors).toEqual([]);
  });
});
