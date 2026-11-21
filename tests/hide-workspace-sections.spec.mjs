// hide-workspace-sections.spec.mjs — 「项目面板工作区入口不设置」判据
// （hide-workspace-sections 批）
//
// 作者令（2026-11-21）：「git 可视化面板和项目面板的工作区都**先隐藏**（「先」=暂时
// 隐藏，功能保留可恢复，不是删除）」；同日 12:03 澄清逐字：「我要的是git可视化面板
// 完全先封存 项目面板也不设置入口」——两项各归一个独立开关，本 spec 只管**项目面板**：
//   · 本 spec（PANEL_HIDE_WORKSPACE_SECTIONS）= 卡内「▶ 工作区」折叠区块不设置入口；
//   · Git 可视化面板的**整面板封存**（GITVIZ_SEALED）见 `tests/gitviz-seal.spec.mjs`。
// 两个开关语义正交、禁混用（两 spec 各自断言对方开关的值不变）。
//
// 判据（每条都能被负控打红 —— 见文末「负控」）：
//   C1 项目卡：开关 ON（出厂值 true）⇒ 卡上**零** `.project-workspaces` 折叠区块，
//      且 DOM 里没有 `工作区` 摘要入口；卡的其余部分（名称/工作区路径字段/
//      AGENTS.md/运行中 agent/描述）照常渲染。
//   C2 恢复点在场且可回：模块仍导出 `mountProjectWorkspaces`（组件未删），
//      `PANEL_HIDE_WORKSPACE_SECTIONS === true`、`panelWorkspaceSectionsHidden()` 为 true；
//      手动调 `mountProjectWorkspaces(card,...)` 仍能挂出折叠区块（组件功能完好）。
//   C3 零数据面变化：卡即使**隐藏**区块也不发 `/api/projects/<p>/workspaces` 请求
//      （隐藏 = 不上入口，不是白拉一次数据）；端点与模块保留可恢复。
//
// 负控（N1..N2，跑法见文件尾注释）：
//   N1 把 PANEL_HIDE_WORKSPACE_SECTIONS 改 false ⇒ C1 必须红（区块回来）。
//   N2 把 projectTab.js 的 `if (!panelWorkspaceSectionsHidden())` 条件拆掉
//      （直调 mountProjectWorkspaces）⇒ C1 同样必须红。

import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);

const PROJECTS = {
  projects: [
    { name: 'BP', workspace: '/Users/kaiyu/local/STAND/BP', description: 'Kai 的商业计划书（BP）打磨…', agentFile: true, createdAt: 1 },
    { name: 'CZT', workspace: '/Users/kaiyu/.stand/projects/CZT', description: 'CZT（CdZnTe）探测器研究…', agentFile: true, createdAt: 2 },
  ],
};

/** 记录 `/workspaces` 的命中次数 —— C3 用（隐藏态必须为零请求）。 */
const workspaceRequests = [];

try {
  const session = await createSession(browser, {
    apiStubs: {
      '/api/projects': { body: PROJECTS },
      '/api/projects/BP/flow-map': { body: { nodes: [], worktrees: [], meta: { project: 'BP', updatedAt: 1 } } },
      '/api/projects/CZT/flow-map': { body: { nodes: [], worktrees: [], meta: { project: 'CZT', updatedAt: 1 } } },
    },
  });
  session.page.on('request', (r) => {
    const u = r.url();
    if (/\/api\/projects\/[^/]+\/workspaces/.test(u)) workspaceRequests.push(u);
  });

  await session.boot({ sessionId: 'hide-ws-root', messages: [] });

  // ── 直接把项目列表画出来（产品路径：openProjectsTab → projectTab 取数渲染）──
  await session.page.evaluate(async () => {
    const mod = await import('/js/projectTab.js');
    window.__hideWs = mod;
    mod.openProjectsTab();
  });
  await session.page.waitForSelector('.team-card.project-card', { timeout: 15000 });
  await settle(session.page, 400);

  const cardCount = await session.page.locator('.team-card.project-card').count();
  assert(cardCount >= 2, `project cards rendered (got ${cardCount})`);

  // ── C1：零工作区块 + 卡其余部分正常 ────────────────────────────────────────
  const details = await session.page.locator('.project-card .project-workspaces').count();
  assert.equal(details, 0, 'C1: no .project-workspaces section on project cards (hidden)');

  const summaryHit = await session.page.evaluate(() => {
    const cards = [...document.querySelectorAll('.project-card')];
    return cards.filter((c) => {
      const summary = c.querySelector('summary');
      return summary && (summary.textContent || '').includes('工作区');
    }).length;
  });
  assert.equal(summaryHit, 0, 'C1: no 「工作区」 <summary> entry remains on any card');

  // 卡其余字段照常（隐藏范围只到工作区块，不误伤）
  const first = session.page.locator('.team-card.project-card').first();
  assert(await first.locator('.project-card-name').count() === 1, 'card name still rendered');
  const fieldLabels = await first.locator('.project-field-label').allTextContents();
  assert(fieldLabels.includes('工作区'), 'C1: the workspace PATH field row stays (only the fold block is hidden)');
  assert(fieldLabels.includes('AGENTS.md'), 'AGENTS.md row stays');
  assert(fieldLabels.includes('运行中 agent'), 'running-agent row stays');
  assert(fieldLabels.includes('描述'), 'description row stays');
  const pathValue = await first.locator('.project-field-value.mono').first().textContent();
  assert.equal(pathValue, '/Users/kaiyu/local/STAND/BP', 'workspace path value still rendered verbatim');

  // ── C2：恢复点在场（常量 true + 组件仍可挂）──────────────────────────────
  const flags = await session.page.evaluate(async () => {
    const m = await import('/js/featureFlags.js');
    return {
      constant: m.PANEL_HIDE_WORKSPACE_SECTIONS,
      fn: m.panelWorkspaceSectionsHidden(),
      exportsMount: typeof m.PANEL_HIDE_WORKSPACE_SECTIONS,
    };
  });
  assert.equal(flags.constant, true, 'C2: PANEL_HIDE_WORKSPACE_SECTIONS is true (the revert point)');
  assert.equal(flags.fn, true, 'C2: panelWorkspaceSectionsHidden() reads the same switch');

  // 组件未删：手动挂一次仍能产出折叠区块（功能完好，只是产品路径不再调用）
  const manualMount = await session.page.evaluate(async () => {
    const { mountProjectWorkspaces } = await import('/js/projectWorkspaces.js');
    const card = document.createElement('section');
    card.id = 'manual-mount-probe';
    card.style.cssText = 'position:fixed;top:0;left:0;width:400px;background:white;color:black';
    document.body.appendChild(card);
    mountProjectWorkspaces(card, 'BP');
    const details = card.querySelector('details.project-workspaces');
    return { has: !!details, summary: details?.querySelector('summary')?.textContent || '' };
  });
  assert.equal(manualMount.has, true, 'C2: mountProjectWorkspaces still creates the fold block when called');
  assert.equal(manualMount.summary, '工作区', 'C2: restored block keeps its original summary text');
  await session.page.evaluate(() => document.getElementById('manual-mount-probe')?.remove());

  // ── C3：隐藏态零 /workspaces 请求（不上入口 ⇒ 不白拉数据）────────────────
  assert.equal(workspaceRequests.length, 0,
    `C3: no /workspaces fetch while hidden (saw: ${workspaceRequests.join(', ')})`);

  // ── C4：与 gitviz 整面板封存开关正交（各自独立，禁混用）──────────────────
  // 本 spec 只管项目卡的工作区块；gitviz 面板封存（GITVIZ_SEALED）另有其 spec。
  // 这里只钉两件事：该开关仍是 true（未被本批顺手改），且它的入口钮确实不在卡上。
  const orthogonality = await session.page.evaluate(async () => {
    const m = await import('/js/featureFlags.js');
    return {
      gitvizSealed: m.GITVIZ_SEALED,
      fn: m.gitvizSealed(),
      buttons: document.querySelectorAll('.project-card .project-gitviz-btn').length,
    };
  });
  assert.equal(orthogonality.gitvizSealed, true, 'C4: GITVIZ_SEALED untouched by the workspace switch');
  assert.equal(orthogonality.buttons, 0, 'C4: no gitviz button on cards (both seals coexist)');

  console.log('PASS hide-workspace-sections: project card fold block hidden, card intact, '
    + 'switch + component restorable, zero workspace fetches');

  // 证据图（两面板改后截图之一：项目面板）
  const outDir = process.env.HIDE_WS_EVIDENCE_DIR
    || '/Users/kaiyu/Claude code/Stand/.stand/tmp/hide-workspace-sections/evidence';
  const { mkdirSync } = await import('node:fs');
  mkdirSync(outDir, { recursive: true });
  await session.page.screenshot({ path: `${outDir}/projects-panel-after.png`, fullPage: false });
  await session.page.setViewportSize({ width: 375, height: 812 });
  await settle(session.page, 300);
  // 手机档走**手机层**入口（桌面 pane 在 mobile-layer 下不可见；项目页 = navController
  // 的 'projects' 页，内部仍走同一个 openProjectsTab ⇒ 同一卡片模板、同一隐藏判据）。
  await session.page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.navigateTo('projects');
  });
  await session.page.waitForFunction(
    () => document.querySelectorAll('.team-card.project-card').length >= 2,
    null, { timeout: 15000 });
  await settle(session.page, 600);
  await session.page.screenshot({ path: `${outDir}/projects-panel-after-mobile.png`, fullPage: false });

  await session.close();
} finally {
  await browser.close();
}

// 负控跑法（两条都必须让 C1 变红，否则判据对该缺陷不敏感）：
//   N1: sed -i '' 's/^export const PANEL_HIDE_WORKSPACE_SECTIONS = true;/export const PANEL_HIDE_WORKSPACE_SECTIONS = false;/' \
//         src/main/resources/web/js/featureFlags.js && node tests/hide-workspace-sections.spec.mjs
//   N2: 在 projectTab.js:1300 把条件改成直调 `mountProjectWorkspaces(card, card.dataset.project);`
//         && node tests/hide-workspace-sections.spec.mjs
// 恢复原状后本判据复绿。
