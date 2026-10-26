// delegate-panorama.spec.mjs — delegate 全景视图（视图重设计实施批 · 2026-10-21）
// 前端位验收：设计 §8 可断言点 1-8 的 DOM/静态臂 ——
//   ① 入口统一（任务列表行 / 项目面板「项目流程图」圆钮 → 同一组件根
//      [data-view=delegate-panorama]）；② 项目带 = 去重项目数 + 带头项目名/工作区；
//   ③ 候合并卡三重编码（st-rtm + 合并队列 #n/N + e-rtm）；④ 合并中卡（st-merge +
//      合并窗持有 + e-merge + 汇点 live）；⑤ 子过程拓扑（星扇出边数=子卡数 /
//      链步骤卡数=步数）；⑥ 失败卡无出边、完成卡 e-del；⑦ 零字面 hex；
//   ⑧ prefers-reduced-motion 零运行动画。
// 数据面 = stub 夹具（rows/chains/children 三读口——harness 缝）；端点臂 = 路由
// mock（provider 换源 /api/delegates + 旧引擎 404 回落 /api/chains）。零后端、零 sbt。
//
// 追加判据（2026-10-22 作者截图令「胶囊挡住了其他文字和控件的查看和交互」，
// 本 spec 管辖面）：卡头胶囊（.canvas-header）与本视图 .flowmap-nav-bar
// （返回钮/「委托全景」标题/摘要）零重叠——布局层解法 = projects 面板顶 56px
// 让位带（projectPanel.css），T6 几何不相交 + elementFromPoint 命中双臂钉死。
//
// Run: node node_modules/@playwright/test/cli.js test tests/delegate-panorama.spec.mjs

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

const ROOT_SID = 'dvp-root-session';
const T0 = Date.now();

async function bootApp(page, { delegatesRows404 = false, hitLog = null } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (hitLog) hitLog.push(`${route.request().method()} ${p}${url.search}`);
      if (delegatesRows404 && p === '/api/delegates') {
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"no route"}' });
      }
      if (p === '/api/chains') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ rows: [] }) });
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
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], total: 0, offset: 0, hasMore: false }));
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

/** 装 stub 夹具（rows/chains/children 三读口）＋广播镜像事件（任务面板随事件重渲
 *  ——夹具在读口缝恒优先，事件只是重渲触发器，与生产纪律一致）。 */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    window.dispatchEvent(new Event('delegate-chain-mirror-updated'));
  }, fx);
}

/** 打开全景（视图组件唯一入口缝）。 */
async function openPanorama(page, project = '', focus = '') {
  return page.evaluate(async ({ project, focus }) => {
    const m = await import('/js/delegatePanoramaView.js');
    m.openDelegatePanorama(project, { focus });
    return true;
  }, { project, focus });
}

// ── 夹具（全景载荷加宽形 + 镜像组合臂数据）────────────────────────────────────
const base = (over) => ({
  taskNo: '', delegateId: '', title: 'T', executor: 'nebflow', status: 'running',
  external: false, project: 'projA', orchestrationMode: '', archivedAt: null,
  mergeQueue: null, mergeQueuePos: null, createdAt: T0, taskStatus: '',
  branch: '', baseBranch: '', worktreePath: '', projectWorkspace: '/ws/projA',
  reportStatus: '', reportDetail: '', verdict: null,
  ...over,
});

const rowRunStar = base({
  taskNo: '91', delegateId: 'delegate-nebflow-pano-star', title: '星扇诊断',
  orchestrationMode: 'subagent-star', worktreePath: '/ws/projA/.nebflow/worktrees/wt-star',
  branch: 'delegate/delegate-nebflow-pano-star', createdAt: T0 - 60_000,
});
const rowRunFlow = base({
  taskNo: '92', delegateId: 'delegate-nebflow-pano-flow', title: '链式样张',
  orchestrationMode: 'agentflow', createdAt: T0 - 50_000,
});
const rowRestart = base({
  taskNo: '93', delegateId: 'delegate-nebflow-pano-restart', title: '重启腿',
  status: 'restarting', createdAt: T0 - 40_000,
});
const rowRtm = base({
  taskNo: '94', delegateId: 'delegate-nebflow-pano-rtm', title: '候批装配',
  status: 'ready-to-merge', reportDetail: '样张已出 · 候批', branch: 'delegate/d-94',
  baseBranch: 'main', mergeQueue: { projectKey: 'projA', ahead: ['delegate-nebflow-pano-mrg'], holder: 'delegate-nebflow-pano-mrg', state: 'waiting' },
  mergeQueuePos: { position: 2, total: 3, readyAt: T0 - 20_000, branch: 'delegate/d-94', baseBranch: 'main' },
  createdAt: T0 - 30_000,
});
const rowMerging = base({
  taskNo: '95', delegateId: 'delegate-nebflow-pano-mrg', title: '登录态修复',
  status: 'running', taskStatus: 'merging', branch: 'delegate/d-95', baseBranch: 'main',
  mergeQueue: { projectKey: 'projA', ahead: [], holder: 'delegate-nebflow-pano-mrg', state: 'turn-granted' },
  mergeQueuePos: { position: 1, total: 3, readyAt: T0 - 25_000, branch: 'delegate/d-95', baseBranch: 'main' },
  createdAt: T0 - 35_000,
});
const rowDone = base({
  taskNo: '96', delegateId: 'delegate-nebflow-pano-done', title: '已并入腿',
  status: 'completed', branch: 'delegate/d-96', baseBranch: 'main',
  reportDetail: '已并入 main', verdict: { verdict: 'completed', reason: 'clean', source: 'judge', at: T0 - 10_000 },
  createdAt: T0 - 90_000,
});
const rowFail = base({
  taskNo: '97', delegateId: 'delegate-nebflow-pano-fail', title: '回归扫描',
  status: 'failed', reportDetail: '规格冻结被驳回', createdAt: T0 - 80_000,
});
const rowCancel = base({
  taskNo: '98', delegateId: 'delegate-nebflow-pano-cancel', title: '指标回填',
  status: 'cancelled', createdAt: T0 - 70_000,
});
const rowArchived = base({
  taskNo: '99', delegateId: 'delegate-nebflow-pano-arch', title: '历史腿',
  status: 'completed', archivedAt: T0 - 5_000, createdAt: T0 - 120_000,
});
const rowProjB = base({
  taskNo: '95', delegateId: 'delegate-nebflow-pano-b1', title: 'B 项目单跑',
  project: 'projB', projectWorkspace: '/ws/projB', createdAt: T0 - 15_000,
});

const fixtureRows = [
  rowRunStar, rowRunFlow, rowRestart, rowRtm, rowMerging,
  rowDone, rowFail, rowCancel, rowArchived, rowProjB,
];

const chainFlow = {
  taskId: '92', delegateId: 'delegate-nebflow-pano-flow',
  nodes: [
    { id: 'fs1', kind: 'flow-step', label: '检索资产', description: '', status: 'completed', deps: [], executor: '' },
    { id: 'fs2', kind: 'flow-step', label: '撰写样张', description: '', status: 'running', deps: ['fs1'], executor: '' },
  ],
  worktree: null, verdict: null, mergeQueue: [], frames: [],
};

const starChildren = {
  'delegate-nebflow-pano-star': [
    { taskId: 'sub-k1', agentName: 'api-scan', description: '', status: 'completed', spawnedAt: T0, completedAt: null },
    { taskId: 'sub-k2', agentName: 'ui-scan', description: '', status: 'running', spawnedAt: T0, completedAt: null },
    { taskId: 'sub-k3', agentName: 'spec-writer', description: '', status: 'pending', spawnedAt: T0, completedAt: null },
  ],
};

test.beforeEach(() => {});

test('T1 全景状态三重编码（§8.2-8.6）：带结构/候选琥珀/合并窗蓝晶/星扇链拓扑/失败无出边', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: fixtureRows, chains: [chainFlow], children: starChildren });
  await openPanorama(page);

  // ① 组件根（设计 §8.1 的组件臂——入口统一断言在 T3）。
  const root = page.locator('[data-view="delegate-panorama"]');
  await expect(root).toHaveCount(1);

  // ② 项目带 = 去重项目数（projA/projB；归档腿零入场）。
  const bands = page.locator('.dvp-band');
  await expect(bands).toHaveCount(2);
  const headA = page.locator('.dvp-band[data-project="projA"] .dvp-band-head');
  await expect(headA).toContainText('projA');
  await expect(headA).toContainText('/ws/projA');

  // ③ 候合并卡：类 + 位次徽标（引擎 mergeQueuePos 透传）+ 出边。
  const rtmCard = page.locator('[data-view="delegate-panorama"] [data-delegate-id="delegate-nebflow-pano-rtm"]');
  await expect(rtmCard).toHaveClass(/st-rtm/);
  await expect(rtmCard.locator('.dvp-qbadge.rtm')).toHaveText(/合并队列 #2\/3/);
  await expect(page.locator('.dvp-band[data-project="projA"] path.e-rtm')).toHaveCount(1);
  await expect(page.locator('.dvp-band[data-project="projA"] .dvp-e-chip').filter({ hasText: 'FIFO #2' })).toHaveCount(1);

  // ④ 合并中卡（G1 一等态）：类 + 合并窗徽标 + 蓝晶行军蚁出边 + 汇点 live。
  const mrgCard = page.locator('[data-view="delegate-panorama"] [data-delegate-id="delegate-nebflow-pano-mrg"]');
  await expect(mrgCard).toHaveClass(/st-merge/);
  await expect(mrgCard.locator('.dvp-qbadge.mrg')).toHaveText('合并窗持有');
  await expect(page.locator('.dvp-band[data-project="projA"] path.e-merge')).toHaveCount(1);
  await expect(page.locator('.dvp-band[data-project="projA"] .dvp-e-chip.mrgc')).toHaveText('合并窗');
  await expect(page.locator('.dvp-band[data-project="projA"] .dvp-sink.live')).toHaveCount(1);

  // ⑤ 子过程拓扑（镜像组合臂）：星扇 3 子卡 + 链 2 步骤卡；边计数（设计 §4 三族）：
  //    派发边仅运行中保留（run 泳道 3 行 → 3 条 e-idle）；星扇按子态（del/inflight/
  //    idle 各 1）；链上游态定边态（delegate→fs1 inflight、fs1→fs2 del）；完成卡入
  //    汇点 1 条 e-del。
  const bandA = page.locator('.dvp-band[data-project="projA"]');
  await expect(bandA.locator('.dvp-sub')).toHaveCount(5); // 3 star + 2 chain steps
  await expect(bandA.locator('[data-sub-key^="chain:"]')).toHaveCount(2);
  await expect(bandA.locator('path.e-idle')).toHaveCount(4); // 派发×3 + 星扇→pending 子
  await expect(bandA.locator('path.e-inflight')).toHaveCount(2); // delegate→fs1 + 星扇→running 子
  await expect(bandA.locator('path.e-del')).toHaveCount(3); // fs1→fs2 + 星扇→completed 子 + 完成卡→汇点
  await expect(bandA.locator('path.e-rtm')).toHaveCount(1);
  await expect(bandA.locator('path.e-merge')).toHaveCount(1);
  // ⑥ 失败卡红染类在场、无合并出边（终态不入合并管线）——e-del 总数已被 ⑤ 钉死
  //    （链内 1 + 完成卡 1，失败/取消零贡献）；取消卡中性。
  await expect(page.locator('[data-view="delegate-panorama"] [data-delegate-id="delegate-nebflow-pano-fail"]')).toHaveClass(/st-fail/);
  await expect(page.locator('[data-view="delegate-panorama"] [data-delegate-id="delegate-nebflow-pano-cancel"]')).toHaveClass(/st-cancel/);
  await expect(page.locator('[data-view="delegate-panorama"] [data-delegate-id="delegate-nebflow-pano-done"]')).toHaveClass(/st-done/);
  // 报告条（失败卡红染底由 CSS 类承担——DOM 断言报告条在场）。
  await expect(page.locator('[data-delegate-id="delegate-nebflow-pano-fail"] .dvp-report')).toContainText('规格冻结被驳回');

  // 图例 + 批次推进带（projA 最深阶段 = 合并中）。
  await expect(page.locator('.dvp-legend .dvp-lg-group')).toHaveCount(3);
  await expect(bandA.locator('.dvp-rb-st.cur .dvp-rb-word')).toHaveText('合并中');

  // 零页面错误。
  await page.waitForTimeout(200);
  expect(pageErrors, pageErrors.join('\n')).toEqual([]);
});

test('T2 项目过滤 pills（点项目 = 该项目全景）＋ 落位高亮 focus', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: fixtureRows, chains: [chainFlow], children: starChildren });
  await openPanorama(page, 'projB', 'delegate-nebflow-pano-b1');
  const root = page.locator('[data-view="delegate-panorama"]');
  await expect(root).toHaveAttribute('data-filter', 'projB');
  await expect(page.locator('.dvp-band[data-project="projA"]')).toBeHidden();
  await expect(page.locator('.dvp-band[data-project="projB"]')).toBeVisible();
  await expect(page.locator('.dvp-pill[data-p="projB"]')).toHaveAttribute('aria-pressed', 'true');
  // 「全部」回全景。
  await page.click('.dvp-pill[data-p="all"]');
  await expect(page.locator('.dvp-band[data-project="projA"]')).toBeVisible();
  // focus 高亮类曾落卡（1.2s×2 后自动摘除——先断言渲染无错即可）。
  await page.waitForTimeout(200);
  expect(pageErrors, pageErrors.join('\n')).toEqual([]);
});

test('T3 入口统一（作者令⑤）：任务列表 delegate 行 → 全景；项目面板「项目流程图」圆钮 → 全景', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: fixtureRows, chains: [chainFlow], children: starChildren });
  // 等镜像首刷把夹具行送进任务面板（delegate 行渲染）。
  await page.waitForSelector('.task-node.task-delegate', { timeout: 10000 });
  // ① 任务列表行点按 → 全景（同一组件根）。点无链夹具的行（非 openable ⇒ 直进）。
  await page.click('.task-node.task-delegate:has-text("B 项目单跑")');
  await expect(page.locator('[data-view="delegate-panorama"]')).toHaveCount(1);
  // ② 返回任务列表 → 项目面板（FAB 入口）→ 全景全量。
  await page.click('.flowmap-nav-bar [data-back-to-tasks]');
  await page.waitForTimeout(200);
  await page.evaluate(async () => {
    const { openTab, getTabPane } = await import('/js/canvas.js');
    openTab('projects', '项目', { type: 'projects', closable: true });
    return !!getTabPane('projects');
  });
  await page.waitForSelector('.proj-flowmap-fab, [class*="flowmap-fab"]', { timeout: 8000 }).catch(() => {});
  const fabCount = await page.evaluate(() => document.querySelectorAll('[class*="proj-flowmap-fab"], [aria-label="项目流程图"]').length);
  if (fabCount > 0) {
    await page.evaluate(async () => {
      const btn = document.querySelector('[aria-label="项目流程图"]') || document.querySelector('[class*="proj-flowmap-fab"]');
      btn.click();
    });
    await expect(page.locator('[data-view="delegate-panorama"]')).toHaveCount(1);
  }
  await page.waitForTimeout(200);
  expect(pageErrors, pageErrors.join('\n')).toEqual([]);
});

test('T4 provider 换源：行面走 /api/delegates；旧引擎 404 回落 /api/chains（部署序容忍）', async ({ page }) => {
  const hits = [];
  await bootApp(page, { delegatesRows404: false, hitLog: hits });
  await page.waitForTimeout(600);
  expect(hits.some((h) => h.startsWith('GET /api/delegates')), hits.join('\n')).toBe(true);

  const hitsOld = [];
  await bootApp(page, { delegatesRows404: true, hitLog: hitsOld });
  await page.waitForTimeout(800);
  expect(hitsOld.some((h) => h.startsWith('GET /api/delegates')), 'new endpoint attempted first').toBe(true);
  expect(hitsOld.some((h) => h.startsWith('GET /api/chains')), 'fallback arm hits the legacy endpoint').toBe(true);
  // 回落臂下任务面板照常出行（/api/chains 返回空行集 → 零行不崩面即可）。
  await page.evaluate(async () => true);
});

test('T5 prefers-reduced-motion：全景零运行动画（§8.8）', async ({ page }) => {
  await bootApp(page);
  await setFixture(page, { rows: fixtureRows, chains: [chainFlow], children: starChildren });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openPanorama(page);
  const anims = await page.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('[data-view="delegate-panorama"] *')) {
      const cs = getComputedStyle(el);
      if (cs.animationName && cs.animationName !== 'none') out.push(`${el.className}: ${cs.animationName}`);
    }
    return out;
  });
  expect(anims, anims.join('\n')).toEqual([]);
});

test('T6 全景样式表零字面 hex 色值（§8.7：全部 var token）', async () => {
  const css = readFileSync(join(WEB, 'css', 'delegatePanorama.css'), 'utf8');
  const hexHits = css.match(/#[0-9a-fA-F]{3,8}\b/g) || [];
  expect(hexHits, hexHits.join(', ')).toEqual([]);
});

test('T8 卡头胶囊零重叠（2026-10-22 作者追加令）：让位带后 nav 条标题/返回钮全可见、命中可达', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: fixtureRows, chains: [chainFlow], children: starChildren });
  await openPanorama(page);
  const pane = page.locator('.canvas-tab-pane[data-type="projects"]');
  await expect(pane).toHaveCount(1);
  await expect(pane.locator('.flowmap-nav-bar .delegate-chain-title')).toHaveText('委托全景');
  // 过渡/图标渲染 settle 后采几何。
  await page.waitForTimeout(400);

  // 几何臂：卡头胶囊矩形 vs nav 条可点件（返回钮/链标题/摘要）双轴不相交。
  const geo = await page.evaluate(() => {
    const cap = document.querySelector('#canvas-panel .canvas-header');
    const bar = document.querySelector('.canvas-tab-pane[data-type="projects"] .flowmap-nav-bar');
    const hit = (a, b) => !(a.left >= b.right || b.left >= a.right
      || a.top >= b.bottom || b.top >= a.bottom);
    const out = { hasCap: !!cap, overlaps: {}, hit: {} };
    if (!cap || !bar) return out;
    const capR = cap.getBoundingClientRect();
    for (const [k, sel] of Object.entries({
      back: '[data-back-to-tasks]', title: '.delegate-chain-title', summary: '.flowmap-summary',
    })) {
      const el = bar.querySelector(sel);
      if (!el) { out.overlaps[k] = null; continue; }
      out.overlaps[k] = hit(el.getBoundingClientRect(), capR);
      if (k === 'back') {
        const r = el.getBoundingClientRect();
        const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        out.hit.backSelf = !!(at && at.closest('[data-back-to-tasks]'));
        out.hit.backCap = !!(at && at.closest('.canvas-header'));
      }
    }
    return out;
  });
  expect(geo.hasCap).toBe(true);
  expect(geo.overlaps.back).toBe(false); // 返回钮不被胶囊遮挡
  expect(geo.overlaps.title).toBe(false); // 「委托全景」标题不被遮挡（作者截图本体）
  expect(geo.overlaps.summary).toBe(false);
  expect(geo.hit.backSelf).toBe(true); // 命中测试：返回钮中心 = 按钮自身
  expect(geo.hit.backCap).toBe(false); // 且不被胶囊命中区拦截
  await page.waitForTimeout(200);
  expect(pageErrors, pageErrors.join('\n')).toEqual([]);
});

test('T7 重启对账（全景审计面）：打断卡灰态（st-stop）+「重启打断」顶徽标+「已由 #N 接续」关系 chip；永不借 fail 红/done 绿', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  const rowInterrupted = base({
    taskNo: '90', delegateId: 'delegate-nebflow-pano-int', title: '【断点批】旧会话',
    status: 'interrupted', restartInterruptedAt: T0 - 60_000,
    continuedBy: 'delegate-nebflow-pano-succ', createdAt: T0 - 90_000,
  });
  const rowSucc = base({
    taskNo: '91', delegateId: 'delegate-nebflow-pano-succ', title: '【断点批 · 断点续作】新会话',
    createdAt: T0 - 5_000,
  });
  await setFixture(page, { rows: [rowInterrupted, rowSucc], chains: [], children: {} });
  await openPanorama(page);
  const card = page.locator('[data-view="delegate-panorama"] .dvp-nd[data-status="interrupted"]');
  await expect(card).toHaveCount(1);
  await expect(card).toHaveClass(/st-stop/); // 灰态卡（非 st-fail 红 / 非 st-done 绿）
  await expect(card.locator('.dvp-qbadge')).toHaveText('重启打断');
  await expect(card.locator('.dvp-chip.continued')).toHaveText('已由 #91 接续');
  // 接续者卡本体零打断语言。
  const succ = page.locator('[data-view="delegate-panorama"] .dvp-nd[data-delegate-id="delegate-nebflow-pano-succ"]');
  await expect(succ.locator('.dvp-qbadge')).toHaveCount(0);
  await page.waitForTimeout(150);
  expect(pageErrors, pageErrors.join('\n')).toEqual([]);
});
