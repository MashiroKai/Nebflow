// tasklist-nodes.spec.mjs — 任务列表面板 Flow Map 节点条目验收（2026-09-02 作者裁定：
// 节点作为条目显示在任务列表中，纯前端；v2 迭代同步：绿色小圈 spinner + 简约化 +
// team 区块解耦（.task-node-* 独立命名空间））。
//
// 覆盖：
//   T1 渲染：WS 节点事件驱动的条目（节点名 + 状态 glyph + 状态词 + 相对时间 +
//      agent 标注 + 项目分组头），五状态 glyph 映射正确；v2 视觉断言（running
//      品牌绿 12px 转动圆环、状态词 muted、行密度 13px/3px 对齐任务行、节点行
//      不携带任务域类）
//   T2 状态更新：nodeUpdated/nodeCompleted 原地反映（绿圈 → 终态色点）
//   T3 移除：nodeRemoved 条目消失
//   T4 交互：点击条目 → projects 标签页就地打开该项目 Flow Map 并高亮该节点
//   T5 快照对齐 + 防回滚：快照在途期间的 WS 增量不被旧快照滚回
//   T6 未知状态优雅降级：后续新状态（如 blocked）→ 中性点、无状态词、不崩
//
// 事件帧 = 后端契约帧 {type, project, nodeId, node}（20260901_project-node-contract
// §2），走真实 ws.js 分发路径注入（s.ws.onmessage），前端管线全真。
//
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-nodes.spec.mjs

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

const ROOT_SID = 'tl-root-session';
const T0 = Date.now();

const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: T0, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});
const N_RUN = nodeJson({ id: 'n-run', name: 'scan-repo', agent: 'Backend', status: 'running', startedAt: T0 });
const N_REV = nodeJson({ id: 'n-rev', name: 'code-review', agent: 'qa-frontend', status: 'completed', completedAt: T0 - 60_000, ttlLeftSec: 240 });
const N_DOC = nodeJson({ id: 'n-doc', name: 'write-docs', agent: 'Docs', status: 'wiring' });
const N_PEND = nodeJson({ id: 'n-pend', name: 'gen-report', agent: 'czt-writer', status: 'pending' });
const N_FIX = nodeJson({ id: 'n-fix', name: 'fix-imports', agent: 'Backend', status: 'failed', completedAt: T0 - 120_000, ttlLeftSec: 180 });

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

/** 让面板渲染管线跑起来：Nebula 根会话的一条任务帧（空任务即可触发首渲 + 懒加载）。
 *  空 content 面板是 hidden 的（.has-tasks 才展开）——后续断言自行等待行出现。 */
async function renderPanel(page, tasks = []) {
  await inject(page, { type: 'taskListUpdate', sessionId: ROOT_SID, tasks });
  await page.waitForTimeout(100);
}

test('T1 节点条目渲染：五状态徽章/状态词/时间/project·agent 标注/项目分组', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 两个项目的节点事件（真实 ws 分发路径）
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-fix', node: { ...N_FIX } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-doc', node: { ...N_DOC } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-pend', node: { ...N_PEND } });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // 分区与项目分组头（节点区块自有类，不依赖 team 分组类）
  await expect(panel.locator('.task-section-nodes .task-node-section-title')).toHaveText('Flow Map');
  await expect(panel.locator('.task-node-group-header', { hasText: 'alpha' })).toHaveCount(1);
  await expect(panel.locator('.task-node-group-header', { hasText: 'beta' })).toHaveCount(1);

  // running：品牌绿小圈（--color-primary #07C160，非任务行蓝色 sapphire）+ 12px
  const runRow = panel.locator('.task-node[data-node-key="alpha:n-run"]');
  await expect(runRow.locator('.task-node-label')).toHaveText('scan-repo');
  await expect(runRow.locator('.task-node-spin')).toHaveCount(1);
  const spinColor = await runRow.locator('.task-node-spin')
    .evaluate((el) => getComputedStyle(el).borderTopColor);
  expect(spinColor).toBe('rgb(7, 193, 96)'); // #07c160 品牌绿
  const spinSize = await runRow.locator('.task-node-spin')
    .evaluate((el) => ({ w: el.offsetWidth, h: el.offsetHeight }));
  expect(spinSize.w).toBe(12); // 缩小到 12px 节奏（= pending 小方框，宁小勿大）
  expect(spinSize.h).toBe(12); // offsetWidth/Height 不含 transform——转圈中的 AABB 会随角度变大
  await expect(runRow.locator('.task-node-word')).toHaveText('运行中');
  const wordColor = await runRow.locator('.task-node-word')
    .evaluate((el) => getComputedStyle(el).color);
  expect(wordColor).not.toBe('rgb(7, 193, 96)'); // 状态词 muted（颜色信号归 glyph）
  await expect(runRow.locator('.task-node-meta')).toHaveText('Backend'); // meta 只标 agent
  await expect(runRow.locator('.task-node-time')).not.toBeEmpty();
  // 行密度对齐任务行：13px 字号 + 3px 上下 padding；不携带任务域类（命名空间解耦）
  expect(await runRow.evaluate((el) => getComputedStyle(el).fontSize)).toBe('13px');
  expect(await runRow.evaluate((el) => getComputedStyle(el).paddingTop)).toBe('3px');
  await expect(runRow).not.toHaveClass(/task-item/);
  await expect(runRow).not.toHaveClass(/task-check/);

  // completed：绿色实心点 + 已完成
  const revRow = panel.locator('.task-node[data-node-key="alpha:n-rev"]');
  await expect(revRow.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(revRow.locator('.task-node-word')).toHaveText('已完成');

  // failed：红点
  const fixRow = panel.locator('.task-node[data-node-key="alpha:n-fix"]');
  await expect(fixRow.locator('.task-node-dot-failed')).toHaveCount(1);
  await expect(fixRow.locator('.task-node-word')).toHaveText('失败');

  // wiring：等待语义（静默小方框 + 等待词）
  const docRow = panel.locator('.task-node[data-node-key="beta:n-doc"]');
  await expect(docRow.locator('.task-node-box')).toHaveCount(1);
  await expect(docRow.locator('.task-node-word')).toHaveText('等待');

  // pending：排队中
  const pendRow = panel.locator('.task-node[data-node-key="beta:n-pend"]');
  await expect(pendRow.locator('.task-node-box')).toHaveCount(1);
  await expect(pendRow.locator('.task-node-word')).toHaveText('排队中');

  expect(pageErrors).toEqual([]);
});

test('T2 状态更新原地反映：running → completed 绿圈与色点切换', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-run"]');
  await expect(row.locator('.task-node-spin')).toHaveCount(1);

  // 节点完成（nodeCompleted 全量 payload）
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n-run',
    node: { ...N_RUN, status: 'completed', result: 'repo scanned', completedAt: Date.now(), ttlLeftSec: 300 },
  });
  await expect(row.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(row.locator('.task-node-spin')).toHaveCount(0);
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  expect(pageErrors).toEqual([]);
});

test('T3 nodeRemoved 移除条目', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });
  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-rev"]');
  await expect(row).toHaveCount(1);

  await inject(page, { type: 'nodeRemoved', project: 'alpha', nodeId: 'n-rev', node: {} });
  await expect(row).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T4 点击条目 → 就地打开该项目 Flow Map 并高亮节点', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({
    json: { nodes: [{ ...N_RUN }, { ...N_REV }], worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } },
  }));
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-run"]');
  await expect(row).toHaveCount(1);
  await row.click();

  // 同标签页就地视图 + 节点卡片渲染 + 定位高亮（fm-node-flash）
  const body = page.locator('.canvas-tab-pane.active .flowmap-view-body');
  await expect(body).toHaveCount(1);
  const nodeCard = body.locator('.fm-node[data-node-id="n-run"]');
  await expect(nodeCard).toHaveCount(1);
  await expect(nodeCard).toHaveClass(/fm-node-flash/, { timeout: 3000 });

  expect(pageErrors).toEqual([]);
});

test('T5 快照在途期间的 WS 增量不被旧快照回滚', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 慢快照桩（500ms）：内容是「n1 running」的旧权威快照
  const STALE = { nodes: [{ ...N_RUN, id: 'n1', name: 'scan-repo' }], worktrees: [], meta: { project: 'alpha', updatedAt: T0 } };
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', async (r) => {
    await new Promise((res) => setTimeout(res, 500));
    return r.fulfill({ json: STALE });
  });

  // 触发快照对齐（重连 → onReconnect → refreshNodeSnapshot），快照在途……
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.forceReconnect();
  });
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 10000 });
  await page.waitForFunction(() => document.querySelector('#task-list .task-node[data-node-key="alpha:n1"]'), null, { timeout: 5000 });

  // ……期间 nodeCompleted 到达：缓存已更新为 completed
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n1',
    node: { ...N_RUN, id: 'n1', status: 'completed', completedAt: Date.now(), ttlLeftSec: 300 },
  });
  const row = page.locator('#task-list .task-node[data-node-key="alpha:n1"]');
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  // 慢快照（running）此时才落地：防回滚守卫生效，面板保持 completed
  await page.waitForTimeout(800);
  await expect(row.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  expect(pageErrors).toEqual([]);
});

test('T6 未知节点状态优雅降级：中性点、无状态词、不崩', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  // 后续会有 blocked 等新状态——前端现在还不知道它，必须中性降级而非崩/空白
  await inject(page, {
    type: 'nodeCreated', project: 'alpha', nodeId: 'n-blocked',
    node: nodeJson({ id: 'n-blocked', name: 'await-gate', agent: 'Backend', status: 'blocked' }),
  });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-blocked"]');
  await expect(row).toHaveCount(1);
  await expect(row.locator('.task-node-label')).toHaveText('await-gate');
  await expect(row.locator('.task-node-dot-neutral')).toHaveCount(1); // 中性默认样式
  await expect(row.locator('.task-node-word')).toHaveCount(0); // 未知状态不出状态词
  await expect(row.locator('.task-node-spin')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});
