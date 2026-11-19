// mobile-dup-layer.spec.mjs — 双层重叠缺陷批（作者报障 2026-11-02 23:07
// 「canvas 交互的时候 会偶发这种 双层重叠的现象 就是底下有一层内容固定 然后
// 上面有一层内容可以滑动」，附实拍：同一屏两份 README.md，一份可见可滚、
// 一份暗淡固定，文字交错）。
//
// ── 根因（已实证，非猜测）────────────────────────────────────────────
// 文件：`src/main/resources/web/js/viewers/markdown.js` 的 `viewMarkdown()`
//   · 早退守卫（约 L794-807）只挡「内容**没变**」的重入；
//   · 内容**变了**（外部改动 / 刷新钮 / 焦点刷新 / 重开腿）就走全量重建，而
//     挂载腿原本是 `pane.appendChild(mdScroller)`（HEAD dc7c965bb 起，Canvas
//     改造批⑦ 2026-10-31）——**旧 scroller 从不摘除**。
//   · `.canvas-md-scroll` 是 `position:absolute; inset:0` 的全幅独立滚动盒
//     （split.css:2007）⇒ 两个实例像素级重叠：旧层滚动位冻结（不写新内容、
//     非 active 的 scroll 记账监听也不更新）= 作者说的「底下有一层内容固定」；
//     新层照常滚动 = 「上面有一层内容可以滑动」。两层文字交错 = 双影。
//   ⇒ 缺陷类型 = 简报可能方向 ①（同一份文档被重复挂载），与「堆叠快照层」无关
//     （快照是 .mf-deck-snap 只读克隆，退出堆叠随 view hidden 一并消失；
//     本条复现全程不开堆叠）。
//   ⇒ 根因**早于本批**（appendChild 落地于 2026-10-31 批；本批只读克隆快照，
//     未触碰 markdown.js）。
//
// ── 修法 ────────────────────────────────────────────────────────────
// 重建前清场：`pane.querySelectorAll(':scope > .canvas-md-scroll,
// :scope > .canvas-md-viewer')` 与旧角浮件（source/find toggle）一并 remove，
// 然后挂新的。**幂等**：内容不变时早退守卫仍生效（零重建、阅读位保留）。
//
// ── 复现步骤（稳定必现；本 spec 逐条断言）──────────────────────────
//   1. 打开一个 md 页签（真渲染腿 fileViewers.renderFile → viewMarkdown）
//   2. 滚动到中段（scrollTop = 500，制造与顶层不同的滚动位）
//   3. 同一 pane **重渲变更后的内容**（模拟刷新钮/外部改动/再打开）
//   ⇒ 修前：pane 内 `.canvas-md-scroll` = 2、`.canvas-md-viewer` = 2、
//      同屏可见两份同源文本（下面 I12 用它做「改前必红」的对照读数）。
//   ⇒ 修后：一律 = 1。
//
// 编号承接主批（I1–I11 在 mobile-gesture-stack-assertions.mjs）：
//   I12 改前复现（对照读数 + 截图；改后用同一序列验唯一性）
//   I13 改后：同一文本在可视区唯一、同一文档容器实例数 = 1
//   I14 清理完整性：进/出堆叠、切页后无「opacity:0 却仍渲染」的层，
//       残留（display:none / hidden）层给出计数
//   I15 反复横跳压力 ≥20 次：层数不增长（无泄漏）
//
// 桌面+手机双视口各跑一轮（同一缺陷面与视口无关 —— 桌面 pane 里同样是
// absolute inset:0 的双层）。零后端：静态服 + ws 夹具。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-dup-layer.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'dup-root-session';
const OUT = process.env.SHOTS_DIR || join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence/dup-layer');

/** 长文档（滚动盒真实可滚；标记串用于「可视区唯一性」断言）。 */
const mdBody = (marker) => '# 标题甲\n\n'
  + Array.from({ length: 80 }, (_, i) => `段落 ${i} ${marker} 正文内容用于滚动与唯一性判据。`).join('\n\n');

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 开 n 个 md 页签（真渲染腿：fileViewers.renderFile → viewMarkdown）。
 *  注①：**必须 pinned: true**。openTab 走 previewKeyOf(type, absPath) 分预览组，
 *  两张 md 卡若 absPath 同目录不同名仍是**同一个 'file' 预览组** ⇒ 第二张开签会
 *  触发「预览替换」把第一张关掉（I15 首轮 start 时 x.md hasTab=false 的病根）。
 *  压力腿要的是「两卡共存」的夹具，故钉住（真机双击打开的文件卡也是 pinned）。 */
async function openMdTabs(page, list) {
  await page.evaluate(async (items) => {
    const canvas = await import('/js/canvas.js');
    const fv = await import('/js/fileViewers.js');
    for (const { id, title, content } of items) {
      canvas.openTab(id, title, { type: 'markdown', absPath: `/tmp/dup/${title}`, pinned: true });
      const pane = canvas.getTabPane(id);
      await fv.renderFile(pane, { itemType: 'markdown', content, absPath: `/tmp/dup/${title}`, fileName: title });
    }
    canvas.setActiveTab(items[0].id);
  }, list);
  await page.waitForTimeout(350);
}

/** 重渲一个已存在页签（同样的内容或变更后的内容）。 */
async function rerender(page, id, title, content) {
  await page.evaluate(async ({ id, title, content }) => {
    const canvas = await import('/js/canvas.js');
    const fv = await import('/js/fileViewers.js');
    await fv.renderFile(canvas.getTabPane(id), { itemType: 'markdown', content, absPath: `/tmp/dup/${title}`, fileName: title });
  }, { id, title, content });
  await page.waitForTimeout(250);
}

/** 容器计数读数：pane 内 scroller/viewer 实例数 + 可视区里同源文本出现次数。 */
const census = (page, paneSel, marker) => page.evaluate(({ sel, mk }) => {
  const pane = document.querySelector(sel);
  if (!pane) return null;
  const scrollers = [...pane.querySelectorAll(':scope > .canvas-md-scroll')];
  const viewers = [...pane.querySelectorAll(':scope > .canvas-md-viewer, :scope > .canvas-md-scroll > .canvas-md-viewer')];
  const vh = window.innerHeight;
  const visible = scrollers.filter((s) => {
    const r = s.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.top < vh && r.bottom > 0;
  });
  // 可视区同源文本唯一性：每个可视 scroller 内是否含 marker。
  const withMarker = visible.filter((s) => (s.textContent || '').includes(mk));
  return {
    scrollers: scrollers.length,
    viewers: viewers.length,
    visibleScrollers: visible.length,
    visibleWithMarker: withMarker.length,
    scrollTops: scrollers.map((s) => s.scrollTop),
    rects: visible.map((s) => { const r = s.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; }),
    toggleCount: pane.querySelectorAll(':scope > .canvas-source-toggle, :scope > .canvas-find-toggle').length,
  };
}, { sel: paneSel, mk: marker });

/** 全页层账：可疑「仍渲染但不可见」的层 + display:none 残留计数。 */
const layerLedger = (page) => page.evaluate(() => {
  const vh = window.innerHeight;
  const suspects = [];
  let hiddenResidue = 0;
  for (const el of document.querySelectorAll('.canvas-tab-pane, .canvas-md-scroll, .mf-cv-host > *, .mf-stack-view')) {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const renders = cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0;
    const opacity = parseFloat(cs.opacity);
    const inViewport = r.bottom > 0 && r.top < vh;
    if (cs.display === 'none' || cs.visibility === 'hidden' || el.hidden) { hiddenResidue++; continue; }
    // 违规面：还在渲染（占位、有内容）却 opacity≈0 —— 看不见但仍参与合成。
    if (renders && opacity < 0.02 && (el.textContent || '').length > 0) {
      suspects.push({ cls: String(el.className).slice(0, 60), opacity, inViewport });
    }
  }
  return {
    suspects,
    hiddenResidue,
    panes: document.querySelectorAll('.canvas-tab-pane').length,
    scrollers: document.querySelectorAll('.canvas-md-scroll').length,
    deckCards: document.querySelectorAll('.mf-deck-card').length,
    deckSnaps: document.querySelectorAll('.mf-deck-snap').length,
  };
});

test.describe('双层重叠缺陷批（markdown 重渲重复挂载）', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'dark' });

  test('I12/I13 重渲不再叠加层：同一文档容器实例 = 1，可视区同源文本唯一', async ({ page }) => {
    await boot(page);
    const MD_V1 = mdBody('MARKLAYER');
    const MD_V2 = mdBody('MARKLAYER') + '\n\n追加段 NEWBLOCK 触发全量重建。';
    await openMdTabs(page, [{ id: 'file:/tmp/dup/README.md', title: 'README.md', content: MD_V1 }]);
    await dockTo(page, 'canvas');
    const paneSel = '.canvas-tab-pane[data-tab-id="file:/tmp/dup/README.md"]';

    // 基线：单容器。
    let c = await census(page, paneSel, 'MARKLAYER');
    expect(c.scrollers, '首渲 = 单 scroller').toBe(1);
    expect(c.viewers).toBe(1);

    // 制造不同滚动位（作者截图特征：底层固定在不同位置）。
    await page.evaluate((sel) => { document.querySelector(sel + ' > .canvas-md-scroll').scrollTop = 500; }, paneSel);
    await page.waitForTimeout(120);
    expect((await census(page, paneSel, 'MARKLAYER')).scrollTops[0]).toBeGreaterThan(100);

    // ① 复现序列：内容变更 → 全量重建（改前此步产生第 2 个 scroller）。
    await rerender(page, 'file:/tmp/dup/README.md', 'README.md', MD_V2);
    c = await census(page, paneSel, 'MARKLAYER');
    console.log('I12/I13 变更重渲后容器读数:', JSON.stringify(c));
    expect(c.scrollers, 'I13 重渲后 scroller 实例数 = 1（改前 = 2）').toBe(1);
    expect(c.viewers, 'I13 重渲后 viewer 实例数 = 1（改前 = 2）').toBe(1);
    expect(c.visibleScrollers, 'I13 可视容器 = 1').toBe(1);
    expect(c.visibleWithMarker, 'I13 可视区同源文本只出现一次').toBe(1);
    expect(c.toggleCount, 'I14 旧角浮件不重复（源码/查找各一枚）').toBeLessThanOrEqual(2);
    // 新内容就位（重建确实发生，不是被早退守卫吞掉）。
    await expect(page.locator(paneSel)).toContainText('NEWBLOCK');

    // ② 同内容再渲（早退守卫）：零新增、阅读位保留。
    await page.evaluate((sel) => { document.querySelector(sel + ' > .canvas-md-scroll').scrollTop = 300; }, paneSel);
    await page.waitForTimeout(120);
    await rerender(page, 'file:/tmp/dup/README.md', 'README.md', MD_V2);
    const kept = await census(page, paneSel, 'MARKLAYER');
    expect(kept.scrollers, 'I13 内容不变的重渲零新增（早退守卫仍生效）').toBe(1);
    expect(kept.scrollTops[0], '阅读位保留（早退不改滚动）').toBeGreaterThan(100);

    // ③ 连续三次变更重渲：仍是 1（幂等清场）。
    for (let i = 0; i < 3; i++) {
      await rerender(page, 'file:/tmp/dup/README.md', 'README.md', mdBody('MARKLAYER') + `\n\n第 ${i} 轮追加 REBUILD${i}`);
    }
    const after3 = await census(page, paneSel, 'MARKLAYER');
    console.log('I13 连续三次重渲后:', JSON.stringify(after3));
    expect(after3.scrollers).toBe(1);
    expect(after3.viewers).toBe(1);
    expect(after3.visibleWithMarker).toBe(1);

    await page.screenshot({ path: join(OUT, 'i13_after_fix_single_layer.png') });
  });

  test('I14 清理完整性：进出堆叠 / 切页后零「仍渲染却不可见」的层', async ({ page }) => {
    await boot(page);
    await openMdTabs(page, [
      { id: 'file:/tmp/dup/a.md', title: 'a.md', content: mdBody('MARKA') },
      { id: 'file:/tmp/dup/b.md', title: 'b.md', content: mdBody('MARKB') },
    ]);
    await dockTo(page, 'canvas');
    const pg = '.mobile-page[data-page="canvas"]';

    // ① 进堆叠（快照层建出）→ 读数。
    await page.locator(`${pg} .mf-cv-stack`).click();
    await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
    await page.waitForTimeout(650);
    const openLedger = await layerLedger(page);
    console.log('I14 堆叠开启态层账:', JSON.stringify(openLedger));
    expect(openLedger.suspects, 'I14 堆叠态无「opacity≈0 却仍渲染」的层').toEqual([]);

    // ② 出堆叠 → 快照层必须整体退场（hidden 或摘除），不留「固定底层」。
    await page.locator(`${pg} .mf-stack-x`).click();
    await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
    await page.waitForTimeout(400);
    const closedLedger = await layerLedger(page);
    console.log('I14 堆叠关闭后层账:', JSON.stringify(closedLedger));
    expect(closedLedger.suspects, 'I14 退堆叠后无「仍渲染却不可见」的层').toEqual([]);
    // 退出后：可视区可见 scroller 恒 1（阅读面唯一）。
    const vis = await page.evaluate(() => {
      const vh = window.innerHeight;
      return [...document.querySelectorAll('.canvas-tab-pane > .canvas-md-scroll')]
        .filter((s) => { const r = s.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.top < vh && r.bottom > 0; }).length;
    });
    expect(vis, 'I14 退堆叠后可见阅读面 = 1').toBe(1);

    // ③ 切页（dock 走开再回来）→ 账不涨。注：离 canvas 页 = 活动文档 pane
    //    原位归还画板内容区（canvasDocAnchor 归还腿）⇒ scroller 总数不变（2），
    //    但「pane 在页面体内」的计数读数随收放面浮动 —— 判据锚**总量**与
    //    「仍渲染却不可见」违规面，不锚 pane 落点。
    const beforeAway = await layerLedger(page);
    await dockTo(page, 'messages');
    await page.waitForTimeout(300);
    await dockTo(page, 'canvas');
    await page.waitForTimeout(400);
    const backLedger = await layerLedger(page);
    console.log('I14 切页往返后层账:', JSON.stringify(backLedger), '（离开前:', JSON.stringify(beforeAway.scrollers), '）');
    expect(backLedger.suspects).toEqual([]);
    expect(backLedger.scrollers, 'I14 切页往返后 scroller 总量不增长（= 离开前水平）').toBe(beforeAway.scrollers);
  });

  test('I15 反复横跳压力 ≥20 次：层数不增长（无泄漏）', async ({ page }) => {
    await boot(page);
    await openMdTabs(page, [
      { id: 'file:/tmp/dup/x.md', title: 'x.md', content: mdBody('MARKX') },
      { id: 'file:/tmp/dup/y.md', title: 'y.md', content: mdBody('MARKY') },
    ]);
    await dockTo(page, 'canvas');
    const pg = '.mobile-page[data-page="canvas"]';

    const sample = () => page.evaluate(() => ({
      scrollers: document.querySelectorAll('.canvas-md-scroll').length,
      // viewer 计数分账（渐隐热区修复批补）：deck 快照按设计是渲染面
      // .canvas-md-viewer 的**克隆**（canvas.js deckSnapshotSource，去 id 防全局
      // 重复）—— 进过一次堆叠后总数恒含 snap 克隆，旧「总数恒 2」读法把设计
      // 克隆误读成泄漏。判据改锚**真 pane 面**（泄漏面）+ 克隆数对账。
      viewers: document.querySelectorAll('.canvas-tab-pane .canvas-md-viewer').length,
      viewersInSnaps: document.querySelectorAll('.mf-deck-snap .canvas-md-viewer').length,
      panes: document.querySelectorAll('.canvas-tab-pane').length,
      hostPanes: document.querySelectorAll('.mf-cv-host > .canvas-tab-pane').length,
      deckCards: document.querySelectorAll('.mf-deck-card').length,
      deckSnaps: document.querySelectorAll('.mf-deck-snap').length,
      nodes: document.querySelectorAll('*').length,
    }));
    const base = await sample();
    console.log('I15 基线:', JSON.stringify(base));

    // 一轮压力 = 进/出堆叠 ×10 + 切文档 ×10（每次切换都重走 adopt/syncDeck 全链）
    // + 内容变更重渲 ×10（每次全量重建 —— 泄漏最可能的放大面）。
    const pressureRound = async (round) => {
      for (let i = 0; i < 10; i++) {
        // 进/出堆叠
        await page.locator(`${pg} .mf-cv-stack`).click();
        await page.waitForTimeout(220);
        await page.locator(`${pg} .mf-stack-x`).click();
        await page.waitForTimeout(200);
        // 切文档（走 setActiveTab 同一切换腿；UI 点击入口由 I11 单独钉死 ——
        // 压力腿只关心「每次切换都会重走 adopt/syncDeck 全链」）。
        await page.evaluate(async (idx) => {
          const c = await import('/js/canvas.js');
          c.setActiveTab(idx % 2 ? 'file:/tmp/dup/y.md' : 'file:/tmp/dup/x.md');
        }, i);
        await page.waitForTimeout(160);
      }
      // 再叠一轮内容变更重渲。
      for (let i = 0; i < 10; i++) {
        await rerender(page, 'file:/tmp/dup/x.md', 'x.md', mdBody('MARKX') + `\n\n第 ${round}-${i} 次 REBUILDX`);
      }
    };
    // 跑两轮：deck 快照克隆是**设计残差**（退堆叠随面 hidden，deckCards 仍挂
    // DOM），首轮会把节点基线抬高一次后**饱和**；真泄漏则第二轮继续线性爬。
    // 旧写法（单轮 + 基线 ×1.05）把首轮设计残差（≈ +190 节点 = 2 张克隆快照
    // 及卡壳）误读成泄漏。泄漏判据 = **轮间斜率**。
    await pressureRound(1);
    const mid = await sample();
    console.log('I15 第一轮后:', JSON.stringify(mid));
    await pressureRound(2);
    const end = await sample();
    console.log('I15 压力后:', JSON.stringify(end));
    expect(end.scrollers, 'I15 scroller 总数不增长（恒 = 页签数 2）').toBe(2);
    expect(end.viewers, 'I15 真 pane 面 viewer 总数不增长（泄漏面 = 2）').toBe(2);
    // snap 克隆对账：克隆数 = 快照数（设计行为），压力循环不得让它失控增长。
    expect(end.viewersInSnaps, 'I15 快照克隆 viewer 数 = snap 数（克隆对账，无失控）').toBe(end.deckSnaps);
    expect(end.hostPanes, 'I15 host 内 pane 恒 1（单 pane 不变式）').toBe(1);
    expect(end.deckCards, 'I15 退堆叠后卡 DOM 不残留（随面 hidden；卡数 = 页签数 2）').toBe(2);
    // 总节点数：轮间斜率 ≤5%（+40 抖动）—— 设计残差首轮饱和、第二轮读数持平；
    // 真泄漏每轮 +190 起步，必然击穿。
    expect(end.nodes, 'I15 总 DOM 节点数无泄漏增长（第二轮斜率判）').toBeLessThan(mid.nodes * 1.05 + 40);
  });

  test('I12 桌面同源面（同一 pane 面，非移动端特有）', async ({ page }) => {
    await boot(page);
    const MD_V1 = mdBody('MARKDESK');
    const MD_V2 = mdBody('MARKDESK') + '\n\n桌面追加 DESKTOP-REBUILD';
    await openMdTabs(page, [{ id: 'file:/tmp/dup/desk.md', title: 'desk.md', content: MD_V1 }]);
    const paneSel = '.canvas-tab-pane[data-tab-id="file:/tmp/dup/desk.md"]';
    expect((await census(page, paneSel, 'MARKDESK')).scrollers).toBe(1);
    await rerender(page, 'file:/tmp/dup/desk.md', 'desk.md', MD_V2);
    const c = await census(page, paneSel, 'MARKDESK');
    expect(c.scrollers, 'I12 桌面重渲同样单容器（缺陷面与视口无关，修法同一处）').toBe(1);
    await expect(page.locator(paneSel)).toContainText('DESKTOP-REBUILD');
  });
});
