// tasklist-nodes.spec.mjs — 任务列表面板 Flow Map 节点条目验收（2026-09-02 作者裁定：
// 节点作为条目显示在任务列表中，纯前端；v2 迭代同步：绿色小圈 spinner + 简约化 +
// team 区块解耦（.task-node-* 独立命名空间））。
//
// 2026-09-05 作者裁定更新（taskpanel-audit 批）：状态词映射改为
// wiring/pending→待处理、running→进行中、blocked→阻塞、
// completed→已完成、failed→失败、cancelled→已取消（blocked——琥珀实心点）。
//
// 2026-10-01 随改随对（chainview-ui-impl-r2 批 · 前端面任务书 §二.2.5）：本 spec
// 相对**当前主图/面板语义**重新对齐，逐处如下（三处均为**先于本批**的代码漂移，
// 本批只是把测试对到既成事实，🔴 不回改产品代码、不重建已删除的机制）：
//   · T1 held 断言退役：`hold/held/release` 机制已由 `5e6170710` 整体移除
//     （`nodeData.js` 的 `NODE_STATUS` / `NODE_STATUS_CLS` 已无 held）⇒ 主代码里
//     `.task-node-box-held` 与「待放行」已无生产路径。原断言复活它 = 造第二权威
//     ⇒ 改为**回归哨兵**（断言 held fixture **不**渲染 held 专属类）。
//   · T1/T8/T9 主图同源过滤断言退役：整链归档过滤的前端镜像
//     （`deriveArchivedIds` / `clusterBatches`）已由 `da979d7cd`+`3cfbf6ea3` 删除，
//     可见性判据唯一存在于后端（`FlowMapStore` sweep ⇒ `nodeRemoved` 帧）
//     ⇒ 归档节点在前端缓存里**照常可见**；「全终态链隐藏」不再是前端行为。
//     T8/T9 改写为**后端权威语义**：`nodeRemoved`（整链出库的那一帧）移除条目、
//     同批活跃成员（无该帧）保留 —— 与 T3 同族的可见性单源证据。
//   · `assertFullyTerminalChainHidden` 反向断言（T8 原「③ 零渲染」）随判据删除，
//     替换为「③ 无 nodeRemoved ⇒ 仍在缓存 ⇒ 仍渲染」的正向断言。
//
// 链视图（chainview 批）新增锚点见 T10..T14：折叠链行 28px / 成员行 22px /
// 徽标五态 / 链控三钮 + 取消二次确认 / `chainState` 帧刷新 / 未分组组置底。
//
// 2026-09-05 10:54 作者裁定（同支叠加批）：任务面板 = 纯 Flow Map 节点视图，
// 旧任务区整体退役（推翻 67e69bf1「旧路径保留」取舍）。逐处：
//   T1 统计 = 仅节点数；progress 区块与旧任务行零存在断言
//   T7 重写：mock 注入旧 taskListUpdate/teamTaskListUpdate 事件 → 零渲染变化
//     （无旧任务行/progress 区/空态，节点视图与统计不受影响）
//   renderPanel 改道：旧 taskListUpdate 帧驱动首渲的路径已删，改为直接调
//     renderTaskList（与 sidebar.js 会话切换同一入口）
//
// 2026-09-05 12:59 作者裁定（同支叠加批）：任务列表主图同源过滤——整链已归档
// （批内全终态）节点不显示，判据与主图同一 clusterBatches 单点（flowMapArchive
// .deriveArchivedIds）。逐处：
//   T2/T3/T5 fixture 适配：原单节点完成后即「单节点全终态链」→ 新判据下整链
//     隐藏，原地徽章/移除/防回滚语义无从断言——各补同批 running 伴节点保持
//     链未齐（测试意图不变，断言不动）
//   T8 新增（核心混合态）：①活跃节点 + ②未齐终态链（同批 completed+running）
//     + ③全终态链（>120s 分链边界独立成批）→ 断言渲染集合=①+② 全部成员、
//     ③零渲染、统计=①+② 计数
//   T9 新增：全终态单链=列表空（面板收起兜底）；活跃成员并入后整链恢复
//     （含已完成成员）——「主图空=列表空」语义一致
//
// 其余覆盖：
//   T2 状态更新：nodeUpdated/nodeCompleted 原地反映（绿圈 → 终态色点）
//   T3 移除：nodeRemoved 条目消失
//   T4 交互：点击条目 → projects 标签页就地打开该项目 Flow Map 并高亮该节点
//   T5 快照对齐 + 防回滚：快照在途期间的 WS 增量不被旧快照滚回
//   T6 未知状态优雅降级
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
// 2026-09-05 裁定：held（hold 闸门挂起）与 blocked 升为正式徽章
const N_HELD = nodeJson({ id: 'n-held', name: 'hold-gate', agent: 'Frontend', status: 'held', completedAt: T0 - 30_000 });
const N_BLOCKED = nodeJson({ id: 'n-blocked', name: 'await-dispatch', agent: 'Backend', status: 'blocked' });

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

/** 让面板渲染管线跑起来：直接调 renderTaskList（与 sidebar.js 会话切换同一
 *  入口；旧 taskListUpdate 帧驱动路径已随旧任务区退役删除）。空 content 面板
 *  是 hidden 的（.has-tasks 才展开）——后续断言自行等待行出现。 */
async function renderPanel(page) {
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForTimeout(100);
}

test('T1 节点条目渲染：七状态徽章/状态词/时间/project·agent 标注/项目分组/统计计节点', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 两个项目的节点事件（真实 ws 分发路径）
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-fix', node: { ...N_FIX } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-held', node: { ...N_HELD } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-doc', node: { ...N_DOC } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-pend', node: { ...N_PEND } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-blocked', node: { ...N_BLOCKED } });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // 分区与项目分组头（节点区块自有类，不依赖 team 分组类）
  // 2026-09-06 显示优化批：「Flow Map」分区标题整体移除（作者 00:35 裁定）——
  // 断言其持续缺席作回归哨兵；flowmap.title 键保留（标签页域共用，不在此面板）。
  await expect(panel.locator('.task-section-nodes .task-node-section-title')).toHaveCount(0);
  await expect(panel.locator('.task-node-group-header', { hasText: 'alpha' })).toHaveCount(1);
  await expect(panel.locator('.task-node-group-header', { hasText: 'beta' })).toHaveCount(1);

  // 头部统计 = 仅节点数（2026-09-05 10:54 裁定：纯节点视图，旧任务计数退役）
  await expect(panel.locator('.task-stats')).toHaveText('7 任务');
  // 旧任务区零存在：无 progress 区块、无旧任务行、无空态
  await expect(panel.locator('.task-section-progress')).toHaveCount(0);
  await expect(panel.locator('.task-item')).toHaveCount(0);
  await expect(panel.locator('.task-empty')).toHaveCount(0);

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
  // 2026-09-05 裁定：running → 进行中（复用 task.inProgressShort）
  await expect(runRow.locator('.task-node-word')).toHaveText('进行中');
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

  // held（2026-10-01 随改随对）：hold/held 机制已随 `5e6170710` 整体退役 ⇒ 主代码
  // 里 `.task-node-box-held` 与「待放行」已无生产路径。本断言为**回归哨兵**：held
  // fixture 不得再命中那套已删除的类/文案，未知状态一律走中性降级（与 T6 同族）。
  // 🔴 修复方向不得是「复活 held 类」——那等于给已删机制造第二权威。
  const heldRow = panel.locator('.task-node[data-node-key="alpha:n-held"]');
  await expect(heldRow).toHaveCount(1); // 行本身仍渲染（未知状态不隐藏节点）
  await expect(heldRow.locator('.task-node-box-held')).toHaveCount(0);
  await expect(heldRow.locator('.task-node-dot-neutral')).toHaveCount(1);
  await expect(heldRow.locator('.task-node-word')).toHaveCount(0); // NODE_WORD_KEY 无 held ⇒ 无状态词

  // wiring：待处理（2026-09-05 裁定，原「等待」）
  const docRow = panel.locator('.task-node[data-node-key="beta:n-doc"]');
  await expect(docRow.locator('.task-node-box')).toHaveCount(1);
  await expect(docRow.locator('.task-node-word')).toHaveText('待处理');

  // pending：待处理（2026-09-05 裁定，原「排队中」）
  const pendRow = panel.locator('.task-node[data-node-key="beta:n-pend"]');
  await expect(pendRow.locator('.task-node-box')).toHaveCount(1);
  await expect(pendRow.locator('.task-node-word')).toHaveText('待处理');

  // blocked（2026-09-05 裁定）：琥珀实心点 + 阻塞
  const blockedRow = panel.locator('.task-node[data-node-key="beta:n-blocked"]');
  await expect(blockedRow.locator('.task-node-dot-blocked')).toHaveCount(1);
  await expect(blockedRow.locator('.task-node-word')).toHaveText('阻塞');

  expect(pageErrors).toEqual([]);
});

test('T2 状态更新原地反映：running → completed 绿圈与色点切换', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  // 主图同源过滤（2026-09-05 12:59 裁定）fixture 适配：n-run 完成后若为单节点
  // 链即「全终态归档」会隐藏——补同批 running 伴节点保持链未齐，原地徽章
  // 切换语义照常断言（createdAt 同为 T0 → 同批聚簇）。
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-peer', node: { ...N_RUN, id: 'n-peer', name: 'peer-task' } });
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
  // 主图同源过滤 fixture 适配（同 T2）：completed 单节点链会整链隐藏，补同批
  // running 伴节点让 n-rev 可见，移除语义照常断言。
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-peer', node: { ...N_RUN, id: 'n-peer', name: 'peer-task' } });
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

  // 慢快照桩（500ms）：内容是「n1 running」的旧权威快照 + 同批 running 伴节点
  // n2（主图同源过滤 fixture 适配：n1 完成后单节点链会整链隐藏，n2 保持链未齐）
  const STALE = { nodes: [{ ...N_RUN, id: 'n1', name: 'scan-repo' }, { ...N_RUN, id: 'n2', name: 'peer-run' }], worktrees: [], meta: { project: 'alpha', updatedAt: T0 } };
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
  // 2026-09-05 裁定后 blocked/held 已是已知状态（正式徽章）——降级路径改用
  // 尚不存在的假想状态 'draft'：前端不认识它时必须中性降级而非崩/空白
  await inject(page, {
    type: 'nodeCreated', project: 'alpha', nodeId: 'n-draft',
    node: nodeJson({ id: 'n-draft', name: 'future-node', agent: 'Backend', status: 'draft' }),
  });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-draft"]');
  await expect(row).toHaveCount(1);
  await expect(row.locator('.task-node-label')).toHaveText('future-node');
  await expect(row.locator('.task-node-dot-neutral')).toHaveCount(1); // 中性默认样式
  await expect(row.locator('.task-node-word')).toHaveCount(0); // 未知状态不出状态词
  await expect(row.locator('.task-node-spin')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T7 旧任务事件零渲染：taskListUpdate/teamTaskListUpdate 注入后面板不变（2026-09-05 10:54 裁定纯节点视图）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });

  const panel = page.locator('#task-list');
  // 基线：2 节点、统计=节点数
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  // 注入旧任务事件（真实 ws 分发路径）：1 条 session 域 + 1 条 team 域。
  // handler 已删 + ws 路由表项已出——帧必须零渲染影响。
  await inject(page, {
    type: 'taskListUpdate', sessionId: ROOT_SID,
    tasks: [{
      id: 'legacy-1', subject: 'legacy session task', description: '', status: 'in_progress',
      taskKind: 'agent', createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }, {
      id: 'legacy-2', subject: 'legacy pending task', description: '', status: 'pending',
      taskKind: 'agent', createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }],
  });
  await inject(page, {
    type: 'teamTaskListUpdate', team: 'some-team',
    tasks: [{
      id: 'legacy-team-1', subject: 'legacy team task', description: '', status: 'in_progress',
      taskKind: 'agent', teamId: 'some-team', assignee: 'Backend',
      createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }],
  });
  await page.waitForTimeout(150);

  // 零渲染变化断言：旧任务行/progress 区块/空态均不存在；
  // 节点行与统计（仅节点口径）保持原样。
  await expect(panel.locator('.task-item')).toHaveCount(0);
  await expect(panel.locator('.task-section-progress')).toHaveCount(0);
  await expect(panel.locator('.task-empty')).toHaveCount(0);
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  expect(pageErrors).toEqual([]);
});

test('T8 可见性单源在后端：全终态链无 nodeRemoved ⇒ 仍渲染；整链出库（nodeRemoved）⇒ 整链离去；统计同口径', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 2026-10-01 随改随对（原「主图同源过滤」语义退役说明见文件头）：
  // 前端已无链聚簇/归档判据（`deriveArchivedIds`/`clusterBatches` 随 `da979d7cd`
  // +`3cfbf6ea3` 删除）⇒ 前端可见性 = **后端活动区事实本身**。本用例把它做成
  // 可执行证据：同一批全终态成员，无 `nodeRemoved` 时全部渲染；随后补一帧
  // `nodeRemoved`（= 后端 sweep 整链出库时广播的那一帧）⇒ 整链条目随之离去。
  const t0 = Date.now() - 10 * 60_000;
  const terminal = [
    { id: 'g-done1', name: 'gamma-done1', agent: 'Backend', status: 'completed', createdAt: t0, completedAt: t0 + 3_500 },
    { id: 'g-done2', name: 'gamma-done2', agent: 'Docs', status: 'cancelled', createdAt: t0 + 1_000, completedAt: t0 + 1_500 },
  ];
  const alive = [
    { id: 'g-run', name: 'gamma-run', agent: 'Backend', status: 'running', createdAt: t0 + 2_000, startedAt: t0 + 2_000 },
  ];
  for (const n of [...terminal, ...alive]) {
    await inject(page, { type: 'nodeCreated', project: 'gamma', nodeId: n.id, node: nodeJson(n) });
  }

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // ① 全终态成员仍渲染（前端无「整链归档隐藏」判据——该判据只存在于后端）
  await expect(panel.locator('.task-node')).toHaveCount(3);
  await expect(panel.locator('.task-node[data-node-key="gamma:g-done1"] .task-node-word')).toHaveText('已完成');
  await expect(panel.locator('.task-node[data-node-key="gamma:g-done2"] .task-node-word')).toHaveText('已取消');
  await expect(panel.locator('.task-stats')).toHaveText('3 任务');

  // ② 后端 sweep 整链出库 → nodeRemoved ×2（真实 ws 分发路径）⇒ 两条目离去
  await inject(page, { type: 'nodeRemoved', project: 'gamma', nodeId: 'g-done1', node: {} });
  await inject(page, { type: 'nodeRemoved', project: 'gamma', nodeId: 'g-done2', node: {} });
  await expect(panel.locator('.task-node')).toHaveCount(1);
  await expect(panel.locator('.task-node[data-node-key="gamma:g-done1"]')).toHaveCount(0);
  await expect(panel.locator('.task-node[data-node-key="gamma:g-done2"]')).toHaveCount(0);

  // ③ 活跃成员未收 nodeRemoved ⇒ 保留；统计随缓存同口径
  await expect(panel.locator('.task-node[data-node-key="gamma:g-run"]')).toHaveCount(1);
  await expect(panel.locator('.task-stats')).toHaveText('1 任务');

  expect(pageErrors).toEqual([]);
});

test('T9 空态由缓存事实决定：唯一节点出库后列表空/面板收起；同项目新成员到达即恢复', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 60_000;

  // 唯一节点（已终态）先渲染
  await inject(page, {
    type: 'nodeCreated', project: 'solo', nodeId: 's-done',
    node: nodeJson({ id: 's-done', name: 'solo-done', agent: 'Backend', status: 'completed', createdAt: t0, completedAt: t0 + 1_000 }),
  });
  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-node')).toHaveCount(1);

  // 后端整链出库（nodeRemoved）⇒ 缓存空 ⇒ 面板收起（.has-tasks 移除，
  // 既有空态兜底 = 无 .has-tasks 不展开），零节点行。
  await inject(page, { type: 'nodeRemoved', project: 'solo', nodeId: 's-done', node: {} });
  await expect(panel).not.toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-node')).toHaveCount(0);

  // 同项目新成员到达 ⇒ 面板恢复展开（缓存事实驱动，零派生）
  await inject(page, {
    type: 'nodeCreated', project: 'solo', nodeId: 's-run',
    node: nodeJson({ id: 's-run', name: 'solo-run', agent: 'Backend', status: 'running', createdAt: t0, startedAt: t0 }),
  });
  await expect(panel).toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-node')).toHaveCount(1);
  await expect(panel.locator('.task-node[data-node-key="solo:s-run"]')).toHaveCount(1);
  await expect(panel.locator('.task-stats')).toHaveText('1 任务');

  expect(pageErrors).toEqual([]);
});

// ══ 链视图（chainview 批 2026-10-01 · 前端面 §二.1/§二.2 新增锚点）════
// fixture：一条链 `c-a`（成员 4 位，覆盖 impl/verify/sink/dispatcher 四类）+ 一个
// 无链节点（落「未分组」组）；另一条链 `c-b` 覆盖 paused/cancelled 徽标两态。

/** 链夹具：`chains` 旁挂 + 节点 `chainId` 条件键（与后端 NodeList 载荷同构）。 */
function chainFixture(t0) {
  return {
    chains: [
      { id: 'c-a', title: 'alpha-chain', entries: ['c1'], ends: ['c2'], memberIds: ['c1', 'c2', 'c3', 'c4'] },
      { id: 'c-b', title: 'beta-chain', entries: ['d1'], ends: ['d1'], memberIds: ['d1'], status: 'paused' },
    ],
    nodes: [
      // 分发器位：in 空 + 无 deps；未起跑（空心点）
      nodeJson({ id: 'c1', name: 'dispatch', agent: 'general', status: 'pending', createdAt: t0, chainId: 'c-a', in: [], out: [{ to: 'c2', port: 'pass', kind: 'chain' }] }),
      // 实现位：running（实心 + 描边放大）
      nodeJson({ id: 'c2', name: 'implement-x', agent: 'general', status: 'running', createdAt: t0 + 1_000, startedAt: t0 + 1_000, chainId: 'c-a', role: 'task', in: ['c1'], deps: ['c1'] }),
      // 验证位：completed
      nodeJson({ id: 'c3', name: 'verify-x', agent: 'general', status: 'completed', createdAt: t0 + 2_000, completedAt: t0 + 2_500, ttlLeftSec: 300, chainId: 'c-a', role: 'verifier', in: ['c2'], deps: ['c2'], lastVerdict: 'pass' }),
      // 落地位：merge true + 同名 sink
      nodeJson({ id: 'c4', name: 'merge-sink', agent: 'general', status: 'pending', createdAt: t0 + 3_000, chainId: 'c-a', role: 'task', merge: true, in: ['c3'], deps: ['c3'] }),
      // 无链节点 → 「未分组」
      nodeJson({ id: 'x1', name: 'kernel-boot', agent: 'general', status: 'running', createdAt: t0 + 500, startedAt: t0 + 500 }),
      // 暂停链的唯一成员
      nodeJson({ id: 'd1', name: 'paused-work', agent: 'general', status: 'running', createdAt: t0 + 4_000, startedAt: t0 + 4_000, chainId: 'c-b' }),
    ],
  };
}

/** 注入夹具：先 nodeCreated 全部成员，再让快照带 `chains` 旁挂落地（链标题/状态权威）。
 *  同时钉住 `/api/projects`（快照对账会按项目清单收敛缓存——清单空会把缓存整体清空，
 *  既有用例同样显式桩住该端点）。`extra` 供「快照未含但缓存已有」的场景使用。 */
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

test('T10 折叠链行：链内成员收拢为一行、结构 = ▸名称+徽标+点图+n/N+三钮、节点行不再直挂组下', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;
  await injectChainFixture(page, 'alpha', chainFixture(t0));

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  const chainRow = panel.locator('.task-chain[data-chain-key="alpha:c-a"]');
  await expect(chainRow).toHaveCount(1);
  await expect(chainRow.locator('.task-chain-name')).toHaveText('alpha-chain');
  await expect(chainRow.locator('.task-chain-badge')).toHaveText('运行'); // c-a 有 running 成员
  await expect(chainRow.locator('.task-chain-meta')).toHaveText('1/4 · 5m'); // 终态 1（verify）/4
  await expect(chainRow.locator('.task-chain-btn')).toHaveCount(2); // ⏸/▶ 同钮双态 + ✕
  await expect(chainRow.locator('.task-chain-map-dot')).toHaveCount(4); // 点图 = 4 位成员
  await expect(chainRow.locator('.task-chain-map-link')).toHaveCount(3); // 相邻连线

  // 折叠态：成员行零渲染；成员节点**不**直挂项目分组下（已收进链块）
  await expect(chainRow).toHaveAttribute('aria-expanded', 'false');
  await expect(panel.locator('.task-chain-member')).toHaveCount(0);
  await expect(panel.locator('.task-node[data-node-key="alpha:c2"]')).toHaveCount(0);

  // 无链节点落「未分组」组，**置底**（在链块之后）
  await expect(panel.locator('.task-node-ungrouped')).toHaveCount(1);
  await expect(panel.locator('.task-node-ungrouped .task-node[data-node-key="alpha:x1"]')).toHaveCount(1);
  const order = await panel.locator('.task-node-group > *').evaluateAll((els) => els.map((e) => e.className));
  const chainIdx = order.findIndex((c) => c.includes('task-chain-block'));
  const ungIdx = order.findIndex((c) => c.includes('task-node-ungrouped'));
  expect(chainIdx).toBeGreaterThanOrEqual(0);
  expect(ungIdx).toBeGreaterThan(chainIdx); // 置底

  expect(pageErrors).toEqual([]);
});

test('T11 展开/折叠：点行 → 成员行 22px、色点按类型着色、再点收回', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;
  await injectChainFixture(page, 'alpha', chainFixture(t0));

  const panel = page.locator('#task-list');
  const chainRow = panel.locator('.task-chain[data-chain-key="alpha:c-a"]');
  await chainRow.click();
  await expect(chainRow).toHaveAttribute('aria-expanded', 'true');
  await expect(panel.locator('.task-chain-member')).toHaveCount(4);

  // 展开节点行 22px + 色点 = 类型色（CSS 变量单源，见证据脚本）
  const member = panel.locator('.task-chain-member[data-node-key="alpha:c2"]');
  const box = await member.evaluate((el) => ({ h: el.getBoundingClientRect().height }));
  expect(box.h).toBe(22);
  await expect(member.locator('.task-chain-member-name')).toHaveText('implement-x');
  await expect(member.locator('.task-chain-member-word')).toHaveText('进行中');

  // 类型着色：dispatcher/impl/verify/sink 四点各取其类（值断言由单源脚本负责）
  await expect(panel.locator('.task-chain-member[data-node-key="alpha:c1"] .task-chain-dot')).toHaveClass(/t-dispatcher/);
  await expect(panel.locator('.task-chain-member[data-node-key="alpha:c2"] .task-chain-dot')).toHaveClass(/t-impl/);
  await expect(panel.locator('.task-chain-member[data-node-key="alpha:c3"] .task-chain-dot')).toHaveClass(/t-verify/);
  await expect(panel.locator('.task-chain-member[data-node-key="alpha:c4"] .task-chain-dot')).toHaveClass(/t-sink/);

  // 折叠记忆：再点收回，零成员行
  await chainRow.click();
  await expect(chainRow).toHaveAttribute('aria-expanded', 'false');
  await expect(panel.locator('.task-chain-member')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T12 徽标五态：运行/暂停/完成/失败/取消（链态 + 成员聚合并行）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;

  // 五条链，各命中一种徽标态（判据 = 链 status 三态优先，其余看成员聚合）
  const chains = [
    { id: 's-run', title: 's-run', memberIds: ['r1'], status: 'active' },
    { id: 's-pause', title: 's-pause', memberIds: ['p1'], status: 'paused' },
    { id: 's-done', title: 's-done', memberIds: ['d1'], status: 'active' },
    { id: 's-fail', title: 's-fail', memberIds: ['f1'], status: 'active' },
    { id: 's-cancel', title: 's-cancel', memberIds: ['k1'], status: 'cancelled' },
  ];
  const nodes = [
    nodeJson({ id: 'r1', name: 'r', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 's-run' }),
    nodeJson({ id: 'p1', name: 'p', agent: 'g', status: 'running', createdAt: t0, startedAt: t0, chainId: 's-pause' }),
    nodeJson({ id: 'd1', name: 'd', agent: 'g', status: 'completed', createdAt: t0, completedAt: t0 + 900, ttlLeftSec: 300, chainId: 's-done' }),
    nodeJson({ id: 'f1', name: 'f', agent: 'g', status: 'failed', createdAt: t0, completedAt: t0 + 900, ttlLeftSec: 300, chainId: 's-fail' }),
    nodeJson({ id: 'k1', name: 'k', agent: 'g', status: 'cancelled', createdAt: t0, completedAt: t0 + 900, ttlLeftSec: 300, chainId: 's-cancel' }),
  ];
  await injectChainFixture(page, 'map', { chains, nodes });

  const panel = page.locator('#task-list');
  const expectBadge = async (cid, cls, word) => {
    const row = panel.locator(`.task-chain[data-chain-key="map:${cid}"]`);
    await expect(row.locator('.task-chain-badge')).toHaveClass(new RegExp(`s-${cls}`));
    await expect(row.locator('.task-chain-badge')).toHaveText(word);
    await expect(row).toHaveAttribute('data-chain-state', cls);
  };
  await expectBadge('s-run', 'running', '运行');
  await expectBadge('s-pause', 'paused', '暂停');
  await expectBadge('s-done', 'completed', '完成');
  await expectBadge('s-fail', 'failed', '失败');
  await expectBadge('s-cancel', 'cancelled', '取消');

  expect(pageErrors).toEqual([]);
});

test('T13 链控三钮：暂停/继续同钮双态 + 取消过二次确认（确认框缺口 ⇒ 不发请求）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;
  await injectChainFixture(page, 'alpha', chainFixture(t0));

  const panel = page.locator('#task-list');
  const row = panel.locator('.task-chain[data-chain-key="alpha:c-a"]');
  const pauseBtn = row.locator('.task-chain-btn-pause');
  const cancelBtn = row.locator('.task-chain-btn-cancel');

  // 拦链控端点：记录动作，回 200（REST 响应体只用于错误分流，状态权威 = chainState 帧）
  const calls = [];
  await page.route('**/api/projects/*/chains/*/*', async (r) => {
    const u = new URL(r.request().url());
    calls.push({ method: r.request().method(), path: u.pathname });
    return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ chainId: 'c-a', status: 'paused', pausedAt: Date.now(), cancelledAt: null }) });
  });

  // active 态：钮标题 = 暂停该链；点它 → POST .../chains/c-a/pause
  await expect(pauseBtn).toHaveAttribute('title', '暂停该链');
  await pauseBtn.click();
  await page.waitForTimeout(200);
  expect(calls).toEqual([{ method: 'POST', path: '/api/projects/alpha/chains/c-a/pause' }]);

  // paused 态（经 chainState 帧刷新 —— 权威帧路径，非 REST 本地改写）
  await inject(page, { type: 'chainState', project: 'alpha', chainId: 'c-a', status: 'paused', pausedAt: Date.now(), cancelledAt: null });
  await expect(row.locator('.task-chain-badge')).toHaveText('暂停');
  await expect(pauseBtn).toHaveAttribute('title', '继续该链'); // 同钮双态
  await pauseBtn.click();
  await page.waitForTimeout(200);
  expect(calls[1]).toEqual({ method: 'POST', path: '/api/projects/alpha/chains/c-a/resume' });

  // 取消：必须过确认对话框。先装一个可控 __showConfirm —— 点 ✕ 只弹框、不发请求。
  await page.evaluate(() => {
    window.__confirmCalls = [];
    window.__showConfirm = (title, text, cb) => { window.__confirmCalls.push({ title, text }); window.__confirmCb = cb; };
  });
  await cancelBtn.click();
  await page.waitForTimeout(150);
  const confirmCalls = await page.evaluate(() => window.__confirmCalls);
  expect(confirmCalls.length).toBe(1);
  expect(confirmCalls[0].title).toBe('取消整条链');
  expect(confirmCalls[0].text).toContain('alpha-chain');
  expect(calls.length).toBe(2); // 未确认 ⇒ 零新请求

  // 确认回调触发 → 才发 cancel
  await page.evaluate(() => window.__confirmCb());
  await page.waitForTimeout(200);
  expect(calls[2]).toEqual({ method: 'POST', path: '/api/projects/alpha/chains/c-a/cancel' });

  // 确认框组件缺席 ⇒ **什么都不做**（绝不无确认直发不可逆腿）
  await page.evaluate(() => { delete window.__showConfirm; window.__confirmCalls = []; });
  const before = calls.length;
  await cancelBtn.click();
  await page.waitForTimeout(200);
  expect(calls.length).toBe(before);

  expect(pageErrors).toEqual([]);
});

test('T14 未分组组：默认展开可折叠、链标题未知的 chainId 回落此组（不臆造链名）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 5 * 60_000;
  // ghost：带 chainId 但快照 `chains` 里没有该 id（链标题未知）→ 不得臆造链名 ⇒ 落未分组
  const ghost = nodeJson({ id: 'ghost', name: 'ghost-node', agent: 'g', status: 'running', createdAt: t0 + 600, startedAt: t0 + 600, chainId: 'c-unknown' });
  await injectChainFixture(page, 'alpha', chainFixture(t0), [ghost]);

  const panel = page.locator('#task-list');
  const ung = panel.locator('.task-node-ungrouped');
  await expect(ung).toHaveCount(1);
  await expect(ung.locator('.task-node-ungrouped-header')).toHaveText('未分组');
  await expect(ung.locator('.task-node[data-node-key="alpha:ghost"]')).toHaveCount(1);
  await expect(ung.locator('.task-node[data-node-key="alpha:x1"]')).toHaveCount(1);
  // 🔴 不臆造：未知 chainId 不产生任何链行
  await expect(panel.locator('.task-chain[data-chain-id="c-unknown"]')).toHaveCount(0);

  // 默认展开 → 行可见；手动收起 + 跨重渲保持
  await expect(ung).not.toHaveClass(/is-collapsed/);
  await ung.locator('.task-node-ungrouped-header').click();
  await expect(ung).toHaveClass(/is-collapsed/);
  await expect(ung.locator('.task-node[data-node-key="alpha:x1"]')).toBeHidden();
  await inject(page, { type: 'chainState', project: 'alpha', chainId: 'c-a', status: 'active', pausedAt: null, cancelledAt: null });
  await expect(panel.locator('.task-node-ungrouped')).toHaveClass(/is-collapsed/); // 重渲后仍是用户收起态

  expect(pageErrors).toEqual([]);
});
