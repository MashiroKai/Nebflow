// gitviz-seal.spec.mjs — 「Git 可视化面板整面板封存」判据（hide-workspace-sections 批）
//
// 作者令（2026-11-21 12:03 console 逐字）：「我要的是git可视化面板完全先封存
// 项目面板也不设置入口」。
//
// 判据（每条都能被负控打红 —— 见文末「负控」）：
//   G1 产品面零入口：项目卡上**零** `.project-gitviz-btn` / `[data-gitviz-project]`
//      节点（卡其余部分照常：卡名 / 工作区路径 / AGENTS.md / 运行中 agent /
//      描述 / 取消加载钮）。
//   G2 封存后**不可达**：从卡上点不到面板（无钮可点）；且没有 `/git-graph`
//      请求发出（封存 = 不上入口，不是白拉一次数据）。
//   G3 恢复点在场且面板完好（封存 ≠ 删代码）：`GITVIZ_SEALED === true`、
//      `gitvizSealed()` 为 true，`openGitViz()` 仍导出；手动调一次仍能渲染出
//      面板本体（`贡献热力图` 区块在场、`.gitviz[data-state="ready"]`）。
//   G4 语义正交：本封存不动项目面板「▶ 工作区」开关（`PANEL_HIDE_WORKSPACE_SECTIONS`
//      仍 true、`panelWorkspaceSectionsHidden()` 仍 true），也不动 agent 侧
//      取数/交办腿（`gitviz_graph` / `gitviz_handoff` 属后端工具面，不在前端断言面）。
//
// 负控（N1..N2，跑法见文件尾注释）：
//   N1 把 GITVIZ_SEALED 改 false ⇒ G1 必须红（卡头钮回来）。
//   N2 把 projectCardHtml 里的 `${gitvizSealed() ? '' : ...}` 条件拆成恒渲染
//      ⇒ G1 同样必须红。

import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);

const PROJECTS = {
  projects: [
    { name: 'BP', workspace: '/Users/kaiyu/local/STAND/BP', description: 'Kai 的商业计划书（BP）打磨…', agentFile: true, createdAt: 1 },
    { name: 'CZT', workspace: '/Users/kaiyu/.stand/projects/CZT', description: 'CZT（CdZnTe）探测器研究…', agentFile: true, createdAt: 2 },
  ],
};

/** 最小可行 git-graph 载荷（字段逐字对齐 `GitGraph.build` 的 JSON 契约，
 *  只求渲染路径不抛错：G3 的「面板完好」探针要真跑一遍 paint）。 */
const sha = (n) => `${String(n).repeat(4)}${'0'.repeat(36)}`;
const GIT_GRAPH = {
  state: 'ok',
  project: 'BP',
  root: '/Users/kaiyu/local/STAND/BP',
  generatedAt: 1,
  serverMs: 1,
  head: { ref: 'feat/base', sha: sha('a'), short: sha('a').slice(0, 7) },
  lanes: 2,
  refsTotal: 2,
  refsShown: 2,
  commitsTotal: 3,
  commitsLimit: 20,
  refs: [
    { name: 'feat/base', kind: 'local', lane: 0, tip: sha('a'), tipShort: sha('a').slice(0, 7), ts: 1, isHead: true, ahead: 0, behind: 0, mergeBase: sha('a'), inGraph: true },
    { name: 'feat/other', kind: 'local', lane: 1, tip: sha('b'), tipShort: sha('b').slice(0, 7), ts: 1, isHead: false, ahead: 1, behind: 0, mergeBase: sha('c'), inGraph: true },
  ],
  commits: [
    { sha: sha('a'), short: sha('a').slice(0, 7), row: 0, lane: 0, ts: 3, date: '2026-11-21', author: 'kai', subject: 'feat: head commit', parents: [sha('c')], refs: ['feat/base'], isTip: true },
    { sha: sha('b'), short: sha('b').slice(0, 7), row: 1, lane: 1, ts: 2, date: '2026-11-20', author: 'kai', subject: 'feat: other tip', parents: [sha('c')], refs: ['feat/other'], isTip: true },
    { sha: sha('c'), short: sha('c').slice(0, 7), row: 2, lane: 0, ts: 1, date: '2026-11-19', author: 'kai', subject: 'feat: base commit', parents: [], refs: [], isTip: false },
  ],
  edges: [
    { from: sha('a'), to: sha('c'), fromLane: 0, toLane: 0, fromRow: 0, toRow: 2 },
    { from: sha('b'), to: sha('c'), fromLane: 1, toLane: 0, fromRow: 1, toRow: 2 },
  ],
  heatmap: {
    days: Array.from({ length: 14 }, (_, i) => ({ date: `2026-11-${String(i + 1).padStart(2, '0')}`, count: i % 3 })),
    total: 14,
    max: 2,
    from: '2026-11-01',
    to: '2026-11-14',
  },
  digest: 'test-digest',
};

/** `/git-graph` 命中记录 —— G2 用（封存态必须为零请求）。 */
const graphRequests = [];

try {
  const session = await createSession(browser, {
    apiStubs: {
      '/api/projects': { body: PROJECTS },
      '/api/projects/BP/git-graph': { body: GIT_GRAPH },
      '/api/projects/CZT/git-graph': { body: { ...GIT_GRAPH, project: 'CZT' } },
      '/api/projects/BP/flow-map': { body: { nodes: [], worktrees: [], meta: { project: 'BP', updatedAt: 1 } } },
      '/api/projects/CZT/flow-map': { body: { nodes: [], worktrees: [], meta: { project: 'CZT', updatedAt: 1 } } },
    },
  });
  session.page.on('request', (r) => {
    if (/\/api\/projects\/[^/]+\/git-graph/.test(r.url())) graphRequests.push(r.url());
  });

  await session.boot({ sessionId: 'gitviz-seal-root', messages: [] });

  await session.page.evaluate(async () => {
    const mod = await import('/js/projectTab.js');
    mod.openProjectsTab();
  });
  await session.page.waitForSelector('.team-card.project-card', { timeout: 15000 });
  await settle(session.page, 400);

  const cards = await session.page.locator('.team-card.project-card').count();
  assert(cards >= 2, `project cards rendered (got ${cards})`);

  // ── G1：产品面零入口，卡其余部分照常 ─────────────────────────────────────
  const gitvizBtns = await session.page.locator('.project-card .project-gitviz-btn').count();
  assert.equal(gitvizBtns, 0, 'G1: no .project-gitviz-btn on any project card (panel sealed)');
  const gitvizHooks = await session.page.locator('.project-card [data-gitviz-project]').count();
  assert.equal(gitvizHooks, 0, 'G1: no [data-gitviz-project] hook on any card (click leg unreachable)');
  // 卡头没有「Git 可视化」可访问名残留
  const ariaHit = await session.page.evaluate(() => [...document.querySelectorAll('.project-card [title], .project-card [aria-label]')]
    .filter((n) => /Git 可视化/.test(`${n.getAttribute('title') || ''} ${n.getAttribute('aria-label') || ''}`)).length);
  assert.equal(ariaHit, 0, 'G1: no 「Git 可视化」 title/aria-label remains inside cards');

  // 卡其余部分不误伤（封存范围只到 gitviz 入口钮）
  const first = session.page.locator('.team-card.project-card').first();
  assert(await first.locator('.project-card-name').count() === 1, 'card name still rendered');
  assert(await first.locator('.project-unmount-btn').count() === 1, 'G1: 取消加载 button untouched');
  const fieldLabels = await first.locator('.project-field-label').allTextContents();
  assert(fieldLabels.includes('工作区'), 'workspace path row intact');
  assert(fieldLabels.includes('AGENTS.md'), 'AGENTS.md row intact');
  assert(fieldLabels.includes('运行中 agent'), 'running-agent row intact');
  assert(fieldLabels.includes('描述'), 'description row intact');

  // ── G2：不可达 ⇒ 零 /git-graph 请求 ──────────────────────────────────────
  assert.equal(graphRequests.length, 0,
    `G2: no /git-graph fetch while sealed (saw: ${graphRequests.join(', ')})`);

  // 封存下 pane 也没有任何 gitviz 视图体（没有入口就不会有残壳）
  const paneSkins = await session.page.evaluate(() => ({
    views: document.querySelectorAll('.canvas-tab-pane[data-type="projects"] .gitviz').length,
    viewKind: document.querySelector('.canvas-tab-pane[data-type="projects"]')?.dataset.projectsView || '',
  }));
  assert.equal(paneSkins.views, 0, 'G2: no .gitviz view body mounted');
  assert.equal(paneSkins.viewKind, 'list', 'G2: projects pane stays in list view (no git-viz view kind)');

  // ── G3：恢复点在场 + 面板本体完好（手动挂一次仍能渲染）──────────────────
  const flags = await session.page.evaluate(async () => {
    const m = await import('/js/featureFlags.js');
    return { constant: m.GITVIZ_SEALED, fn: m.gitvizSealed(), hasOpen: typeof m.gitvizSealed };
  });
  assert.equal(flags.constant, true, 'G3: GITVIZ_SEALED is true (the revert point)');
  assert.equal(flags.fn, true, 'G3: gitvizSealed() reads the same switch');

  const probe = await session.page.evaluate(async (payload) => {
    const view = await import('/js/gitVizView.js');
    const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
    await view.openGitViz(pane, 'BP', { data: payload });
    const root = pane.querySelector('.gitviz');
    const titles = [...pane.querySelectorAll('.gitviz-heat-title, .delegate-chain-title')]
      .map((n) => (n.textContent || '').trim());
    return {
      exportsOpen: typeof view.openGitViz,
      state: root?.dataset.state || '',
      titles,
      sections: [...pane.querySelectorAll('.gitviz-heat, .gitviz-main, .gitviz-tree, .gitviz-ctl')]
        .map((n) => n.className),
    };
  }, GIT_GRAPH);
  assert.equal(probe.exportsOpen, 'function', 'G3: openGitViz still exported (code not deleted)');
  assert.equal(probe.state, 'ready', 'G3: panel still renders when called directly (feature intact)');
  assert(probe.titles.includes('贡献热力图'), `G3: heatmap block still rendered (titles: ${probe.titles.join(' / ')})`);
  assert(probe.titles.some((t) => t.includes('Git 可视化')), 'G3: panel nav title still rendered');

  // ── G4：与项目面板「工作区」开关正交（各自独立，禁混用）──────────────────
  const wsFlags = await session.page.evaluate(async () => {
    const m = await import('/js/featureFlags.js');
    return { constant: m.PANEL_HIDE_WORKSPACE_SECTIONS, fn: m.panelWorkspaceSectionsHidden() };
  });
  assert.equal(wsFlags.constant, true, 'G4: PANEL_HIDE_WORKSPACE_SECTIONS untouched by this seal');
  assert.equal(wsFlags.fn, true, 'G4: panelWorkspaceSectionsHidden() untouched');
  const wsBlocks = await session.page.locator('.project-card .project-workspaces').count();
  assert.equal(wsBlocks, 0, 'G4: workspace fold block still hidden (both switches coexist)');

  console.log('PASS gitviz-seal: panel entry sealed (zero buttons, zero fetches), '
    + 'cards intact, panel + openGitViz restorable, workspace switch orthogonal');

  // 证据图（封存后入口所在页 = 项目面板，卡头不再有 git 钮）
  const outDir = process.env.HIDE_WS_EVIDENCE_DIR
    || '/Users/kaiyu/Claude code/Stand/.stand/tmp/hide-workspace-sections/evidence';
  mkdirSync(outDir, { recursive: true });
  // 重置回列表视图（G3 探针把 pane 切到了 git-viz），再拍封存态
  const backToList = async () => {
    await session.page.evaluate(async () => {
      const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
      if (pane) { pane.dataset.projectsView = 'list'; delete pane.dataset.gitVizProject; pane.innerHTML = ''; }
      const mod = await import('/js/projectTab.js');
      mod.openProjectsTab();
    });
    // 🔴 不用 waitForSelector（默认 visible）：手机档下桌面 pane 被 mobile 层藏起，
    // 卡片在 DOM 里但不可见——判据只要求「卡片已挂」故按 count 等。
    await session.page.waitForFunction(
      () => document.querySelectorAll('.team-card.project-card').length >= 2,
      null, { timeout: 15000 });
    await settle(session.page, 400);
  };
  await backToList();
  await session.page.screenshot({ path: `${outDir}/gitviz-sealed-card-header.png`, fullPage: false });
  await session.page.setViewportSize({ width: 375, height: 812 });
  await settle(session.page, 300);
  // 手机档走**手机层**入口（桌面 pane 在 mobile-layer 下不可见；项目页 = navController
  // 的 'projects' 页，内部仍走同一个 openProjectsTab ⇒ 同一卡片模板、同一封存判据）。
  await session.page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.navigateTo('projects');
  });
  await session.page.waitForFunction(
    () => document.querySelectorAll('.mobile-layer-visible .team-card.project-card, .team-card.project-card').length >= 2,
    null, { timeout: 15000 });
  await settle(session.page, 600);
  await session.page.screenshot({ path: `${outDir}/gitviz-sealed-card-header-mobile.png`, fullPage: false });

  await session.close();
} finally {
  await browser.close();
}

// 负控跑法（两条都必须让 G1 变红，否则判据对该缺陷不敏感）：
//   N1: sed -i '' 's/^export const GITVIZ_SEALED = true;/export const GITVIZ_SEALED = false;/' \
//         src/main/resources/web/js/featureFlags.js && node tests/gitviz-seal.spec.mjs
//   N2: 在 projectTab.js 把 `${gitvizSealed() ? '' : `...`}` 改回恒渲染的 `<button ...>` 一行
//         && node tests/gitviz-seal.spec.mjs
// 复位后本判据复绿。
