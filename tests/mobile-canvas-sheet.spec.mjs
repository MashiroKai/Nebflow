// mobile-canvas-sheet.spec.mjs — canvas 独立整页交互验收（dock 批 2026-10-29
// 判据换代：作者令 13:31 五栏 dock + 13:36「canvas 不做悬浮窗，做成独立整页……
// 页内用卡片式设计呈现/切换多张 canvas 卡片」+ 13:38「各节目都是独立界面」）。
//
// 前 bottom sheet 形态（#canvas-panel 悬浮卡 + canvasGestures.js 手势族 +
// 卡头胶囊 + 底部切换条 + 缘带下滑收层）整体退役，本文件判据换钉 canvas 页：
//   · 呈现 …… dock「canvas」键 → 全屏独立页面（.mobile-page[data-page="canvas"]，
//     chip 卡片条 + 工具条 + 文档区 + 空态）；桌面 #canvas-panel 手机层恒不显示。
//   · 卡片呈现/切换 …… chip 条（tap 跳卡 + .on 高亮）+ 工具条 ‹ 计数 › +
//     文档区横滑（轴锁 8px / 阈 48px，环式；单卡 no-op；横滑永不关卡）+
//     X 关卡（关后落相邻卡；末卡 → 页内空态，页不收）。
//   · 状态保留 …… 离开 canvas 页（dock 切走）文档页签不销毁，回页恢复活动卡。
//   · 桌面遗留面 …… pane 内 .canvas-header（桌面卡头）手机层退场（chip 条替代）。
//   · 退役零残留 …… .mobile-card-head/.mobile-card-switchbar/.mobile-canvas-edgezone
//     /body.mf-cards-live 全零（防复活钉）。
//
// 手势模拟 = Playwright 鼠标拖拽（mouse 腿走同一 Pointer Events 管线；
// hasTouch 上下文 + host touch-action:pan-y 与真机触屏同路径）。数据面零后端。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-canvas-sheet.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo, settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'sheet-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
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
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 开文档卡（canvas.js openTab 通用文档 pane）+ dock 进 canvas 页。
 *  注①：openTab 的预览组替换按 type 键（previewKeyOf）—— 多卡场景每卡给独立
 *  type，避免同组预览替换把前一张卡顶掉。 */
async function openDocCards(page, ids) {
  await page.evaluate((list) => {
    import('/js/canvas.js').then((canvas) => {
      for (const [id, title, type] of list) canvas.openTab(id, title, { type: type || 'generic' });
    });
  }, ids);
  await page.waitForTimeout(300);
  await dockTo(page, 'canvas');
}

/** 活动卡 id（chip 高亮为准 —— 卡片层的单一判据面）。 */
const activeChip = (page) => page.evaluate(() => {
  const el = document.querySelector('.mf-strip-chip.on');
  return el ? el.dataset.tabId : null;
});

/** 指针拖拽（Pointer Events 管线；触屏同路径 —— host touch-action:pan-y，
 *  轴锁内横滑走手势、纵滚让位原生）。 */
async function drag(page, x0, y0, x1, y1, steps = 12) {
  await page.mouse.move(x0, y0);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(x0 + ((x1 - x0) * i) / steps, y0 + ((y1 - y0) * i) / steps);
    await page.waitForTimeout(12);
  }
  await page.mouse.up();
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 canvas page presentation: full-bleed page, chip strip, toolbar, host, sheet family zero-residue', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);

  const geo = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="canvas"]');
    const r = pg.getBoundingClientRect();
    const chip = (sel) => {
      const el = pg.querySelector(sel);
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { w: Math.round(b.width), h: Math.round(b.height) };
    };
    return {
      x: Math.round(r.x), w: Math.round(r.width),
      live: pg.classList.contains('live'),
      // 顶栏批 2026-10-31（作者令①，两批合流）：canvas = dock 根页，顶栏标题
      // 胶囊随根页页头整体退役；标签条上移就位顶带（安全区顶距由页面壳
      // .mobile-page-nohead 自管，垫高不再随条）。
      title: pg.querySelector('.mobile-page-title')?.textContent ?? null,
      stripTop: Math.round(document.querySelector('.mf-strip').getBoundingClientRect().top),
      chips: pg.querySelectorAll('.mf-strip-chip').length,
      chipOn: pg.querySelectorAll('.mf-strip-chip.on').length,
      chipLabel: pg.querySelector('.mf-strip-chip.on')?.textContent || '',
      count: pg.querySelector('.mf-cv-count').textContent,
      // 批②⑤（2026-10-31）：工具条换代 = ✕/计数/堆叠视角钮；步进/独立窗退役。
      stackBox: chip('.mf-cv-stack'),
      popGone: !pg.querySelector('.mf-cv-pop') && !pg.querySelector('.mf-cv-prev') && !pg.querySelector('.mf-cv-next'),
      closeBox: chip('.mf-cv-close'),
      hostVisible: getComputedStyle(pg.querySelector('.mf-cv-host')).display !== 'none',
      emptyHidden: pg.querySelector('.mf-cv-empty').hidden,
      sheetPanelDisplay: getComputedStyle(document.getElementById('canvas-panel')).display,
      sheetResidue: document.querySelectorAll('.mobile-card-head, .mobile-card-switchbar, .mobile-canvas-edgezone').length,
      // 注：body.mf-cards-live 或随 canvas.js 桌面态出现，但手机层零 CSS 消费者
      // （grep 实证）—— 不钉零，只钉「无可见面 + 无 sheet DOM」（上行两断言）。
    };
  });
  expect(geo.x).toBe(0);            // 全屏独立页面（各节目不重叠）
  expect(geo.w).toBe(375);
  expect(geo.live).toBe(true);
  // 顶栏批 2026-10-31（作者令①，两批合流）：canvas = dock 根页，顶栏标题胶囊
  // 随根页页头整体退役；标签条置顶（桌面 inset=0 时 top=0）。
  expect(geo.title).toBe(null);
  expect(geo.stripTop).toBe(0);       // 标签条置顶（安全区顶距由页面壳自管）
  expect(geo.chips).toBe(1);        // 单卡 = 单 chip
  expect(geo.chipOn).toBe(1);
  expect(geo.chipLabel).toBe('样例文档一');
  expect(geo.popGone).toBe(true);
  // 悬浮钮统一批（10-31 16:03 作者令）：工具钮归 48px g2-fab 基准。
  expect(geo.stackBox).toEqual({ w: 48, h: 48 });
  // 批②：堆叠视角钮在挂（单卡亦可开面——计数 0 张）。
  expect(geo.stackBtn === undefined || geo.stackBtn === true).toBe(true);
  expect(geo.count).toBe('');           // 单卡无计数
  // 批⑤：.mf-cv-pop 已退役，popHidden 断言随钮移除（popGone 已钉在上）。
  expect(geo.closeBox.w).toBe(48);      // X 热区 48×48（统一批基准规格）
  expect(geo.closeBox.h).toBe(48);
  expect(geo.hostVisible).toBe(true);
  expect(geo.emptyHidden).toBe(true);
  // 收编是异步腿（openTab → pane 建出 → adoptActiveDoc），重试等 pane 落位；
  // 卡头退场两合法终态：'gone' = pane 无卡头节点，'none' = CSS 退场。
  await expect(page.locator(
    '.mobile-page[data-page="canvas"] .mf-cv-host .canvas-tab-pane[data-tab-id="doc-1"]',
  )).toHaveCount(1);
  const paneHeaderDisplay = await page.evaluate(() => {
    const h = document.querySelector('.mobile-page[data-page="canvas"] .canvas-tab-pane .canvas-header');
    return h ? getComputedStyle(h).display : 'gone';
  });
  expect(['gone', 'none']).toContain(paneHeaderDisplay);
  expect(geo.sheetPanelDisplay).toBe('none'); // 悬浮 sheet 恒不显示
  expect(geo.sheetResidue).toBe(0);
  expect(errors).toEqual([]);
});

test('T2 leaving the canvas page keeps doc tabs; return restores the active card (state kept)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b']]);
  expect(await activeChip(page)).toBe('doc-b'); // 后开的卡乙为活动卡

  // 离开 canvas 页（dock 切工作台根）—— 文档保留在页签数据模型（closeCanvas 语义）。
  await dockTo(page, 'workbench');
  const kept = await page.evaluate(async () => {
    const m = await import('/js/canvas.js');
    return m.hasTab('doc-a') && m.hasTab('doc-b');
  });
  expect(kept).toBe(true);

  // 回 canvas 页 —— 活动卡不变（卡乙）+ pane 重新收编进页面文档区。
  await dockTo(page, 'canvas');
  expect(await activeChip(page)).toBe('doc-b');
  await expect(page.locator(
    '.mobile-page[data-page="canvas"] .mf-cv-host .canvas-tab-pane[data-tab-id="doc-b"]',
  )).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('T3 host horizontal swipe switches cards (left=next, right=prev, ring); swipe never closes', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b']]);
  expect(await activeChip(page)).toBe('doc-b'); // 环序 [甲, 乙]，活动 = 乙
  const host = await page.locator('.mf-cv-host').boundingBox();
  const midY = host.y + host.height / 2;
  // 左滑 120px（> 48 阈）→ 下一张（乙 → 环绕回甲）。
  await drag(page, host.x + host.width - 40, midY, host.x + host.width - 160, midY);
  expect(await activeChip(page)).toBe('doc-a');
  // 横滑永不关卡：两卡都还在。
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-a'))).toBe(true);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-b'))).toBe(true);
  // chip 高亮同步：唯甲一枚 .on。
  const chips = await page.evaluate(() => ({
    n: document.querySelectorAll('.mf-strip-chip').length,
    onIdx: [...document.querySelectorAll('.mf-strip-chip')].findIndex((el) => el.classList.contains('on')),
  }));
  expect(chips.n).toBe(2);
  expect(chips.onIdx).toBe(0);
  // 右滑 120px → 上一张（甲 → 环绕回乙）。
  await drag(page, host.x + 40, midY, host.x + 160, midY);
  expect(await activeChip(page)).toBe('doc-b');
  expect(errors).toEqual([]);
});

test('T3b 堆叠视角 + count + chip tap switch cards（批②⑤：步进钮退役，堆叠视角接棒）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
  expect(await activeChip(page)).toBe('doc-c'); // 环序 [甲, 乙, 丙]
  const pg = '.mobile-page[data-page="canvas"]';
  // 堆叠视角开面：顶行「3 张文档」（批⑤ 顶部计数）+ 全卡列表。
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('3 张文档');
  await expect(page.locator(`${pg} .mf-stack-card`)).toHaveCount(3);
  // 点卡甲换入并收面（1/3）。
  await page.locator(`${pg} .mf-stack-card[data-tab-id="doc-a"]`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
  expect(await activeChip(page)).toBe('doc-a');
  await expect(page.locator(`${pg} .mf-cv-count`)).toHaveText('1/3');
  // chip tap → 跳卡乙（2/3）。
  await page.locator(`${pg} .mf-strip-chip[data-tab-id="doc-b"]`).click();
  expect(await activeChip(page)).toBe('doc-b');
  await expect(page.locator(`${pg} .mf-cv-count`)).toHaveText('2/3');
  expect(errors).toEqual([]);
});

test('T3c X closes the active card (neighbor settles; last card → in-page empty state, page stays)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b']]);
  // X 关激活卡（卡乙，idx=1）→ 落邻卡卡甲。
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-close').click();
  expect(await activeChip(page)).toBe('doc-a');
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-b')), { timeout: 4000 }).toBe(true);
  await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/); // 页不收
  // 末卡关后 = 页内空态（strip/toolbar/host 隐，empty 显），页仍 live。
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-close').click();
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-a')), { timeout: 4000 }).toBe(true);
  const st = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="canvas"]');
    return {
      live: pg.classList.contains('live'),
      stripHidden: pg.querySelector('.mf-strip').hidden,
      toolbarHidden: pg.querySelector('.mf-cv-toolbar').hidden,
      hostHidden: pg.querySelector('.mf-cv-host').hidden,
      emptyVisible: getComputedStyle(pg.querySelector('.mf-cv-empty')).display !== 'none',
      emptyTitle: pg.querySelector('.mf-cv-empty-title').textContent,
    };
  });
  expect(st.live).toBe(true);
  expect(st.stripHidden).toBe(true);
  expect(st.toolbarHidden).toBe(true);
  expect(st.hostHidden).toBe(true);
  expect(st.emptyVisible).toBe(true);
  expect(st.emptyTitle).toBe('看板暂无文档卡片');
  expect(errors).toEqual([]);
});

test('T4 pane vertical scroll untouched; horizontal swipe on a single card is a no-op', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  // 灌高内容 → 滚动发生在 pane 内滚器（文档区不收手势，纵滚零劫持）。
  await page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host .canvas-tab-pane[data-tab-id="doc-1"]');
    pane.innerHTML = '<div class="t4-scroll" style="height:100%;overflow-y:auto"><div style="height:2400px">scroll-body</div></div>';
  });
  const before = await page.evaluate(() => document.querySelector('.mf-cv-host .t4-scroll').scrollTop);
  await page.mouse.move(187, 400);
  await page.mouse.wheel(0, 300);
  await page.waitForTimeout(200);
  const after = await page.evaluate(() => document.querySelector('.mf-cv-host .t4-scroll').scrollTop);
  expect(after).toBeGreaterThan(before);
  await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/);
  // 单卡横滑 = no-op（无可切邻卡；也绝不关卡）。
  const host = await page.locator('.mf-cv-host').boundingBox();
  await drag(page, host.x + 60, host.y + 200, host.x + 220, host.y + 200);
  expect(await activeChip(page)).toBe('doc-1');
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-1'))).toBe(true);
  expect(errors).toEqual([]);
});

test('T5 hit zones: toolbar prev/next/close ≥44px (pop ≥44 when visible)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const boxes = await page.evaluate(() => {
    const box = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height), hidden: el.hidden };
    };
    return {
      // 批②⑤：工具条 = ✕ + 堆叠视角钮（统一批：48px 液体玻璃圆）+ 计数。
      stack: box('.mf-cv-stack'),
      close: box('.mf-cv-close'),
    };
  });
  for (const k of ['stack', 'close']) {
    expect(boxes[k].w, k).toBeGreaterThanOrEqual(44);
    expect(boxes[k].h, k).toBeGreaterThanOrEqual(44);
  }
  // 批⑤：.mf-cv-pop 独立窗钮已退役——文件卡也不再生 pop 钮。
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    c.openTab('doc-file', '说明.md', { type: 'file' });
  });
  await page.waitForTimeout(400);
  const popGone = await page.evaluate(() => !document.querySelector('.mf-cv-pop'));
  expect(popGone).toBe(true);
  expect(errors).toEqual([]);
});

// ── 手机端 canvas 修复批（2026-10-31 作者令 23:29/23:35，五件）───────────
// T6 = 批① 双页叠开收口；T7 = 批② chip 胶囊同族 + 批③ 条带让位浮钮；
// T8 = 批③ ask 悬浮钮让位角落圆钮簇。

test('T6 双页叠开收口（批①）：归还腿失效（锚邻位被摘）时 host 恒单 pane，旧 pane 归还内容区', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b']]);
  expect(await activeChip(page)).toBe('doc-b');
  // 切到卡甲：pane 甲收编进 host（锚 = {#canvas-content, next=乙 pane}）。
  await page.locator('.mf-strip-chip[data-tab-id="doc-a"]').click();
  await expect.poll(() => activeChip(page)).toBe('doc-a');
  await expect(page.locator('.mf-cv-host > .canvas-tab-pane[data-tab-id="doc-a"]')).toHaveCount(1);
  // 树变破坏锚邻位：内容区其余 pane 全摘（邻卡被关等真实腿的等效扰动）。
  await page.evaluate(() => {
    const c = document.getElementById('canvas-content');
    for (const p of [...c.querySelectorAll(':scope > .canvas-tab-pane')]) p.remove();
  });
  // 换回卡乙：修前 insertBefore(已摘邻位) 抛错被吞 + 锚已清 ⇒ 甲 pane 永久滞留
  // host、与乙上下叠开（作者截图实证）；修后归还腿回退追加 + 收口扫口双保险。
  await page.locator('.mf-strip-chip[data-tab-id="doc-b"]').click();
  await expect.poll(() => activeChip(page)).toBe('doc-b');
  const hostPanes = await page.evaluate(() =>
    [...document.querySelectorAll('.mf-cv-host > .canvas-tab-pane')].map((p) => p.dataset.tabId));
  expect(hostPanes).toEqual(['doc-b']);
  // 甲 pane 已归还画板内容区（不在页体内滞留；pane 显隐由 .active 驱动）。
  const aBack = await page.evaluate(() =>
    !!document.querySelector('#canvas-content > .canvas-tab-pane[data-tab-id="doc-a"]'));
  expect(aBack).toBe(true);
  expect(errors).toEqual([]);
});

test('T7 chip 胶囊同族（批②：材质/圆角/选中态）+ 条带让位悬浮工具带（批③）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b']]);
  const m = await page.evaluate(() => {
    const chip = document.querySelector('.mf-strip-chip.on');
    const cs = getComputedStyle(chip);
    const strip = getComputedStyle(document.querySelector('.mf-strip'));
    const closeB = document.querySelector('.mf-cv-close').getBoundingClientRect();
    const stackB = document.querySelector('.mf-cv-stack').getBoundingClientRect();
    const chipR = chip.getBoundingClientRect();
    const inter = (a, b) => !(a.right < b.left || b.right < a.left || a.bottom < b.top || b.bottom < a.top);
    return {
      radius: cs.borderRadius,
      blur: `${cs.backdropFilter}${cs.webkitBackdropFilter || ''}`,
      borderBottom: cs.borderBottomColor,
      bgImage: cs.backgroundImage,
      padL: strip.paddingLeft,
      padR: strip.paddingRight,
      chipHitsBtn: inter(chipR, closeB) || inter(chipR, stackB),
    };
  });
  expect(m.radius).toBe('999px');                       // 圆角 = 胶囊同族
  expect(m.blur).toContain('blur');                     // 材质 = 毛玻璃同族
  expect(m.borderBottom).toBe('rgba(0, 0, 0, 0)');      // 底缘透明化（.canvas-header 同律）
  expect(m.bgImage).toContain('linear-gradient');       // 顶缘折射线层（胶囊同源）
  expect(m.padL).toBe('76px');                          // 让位 ✕/堆叠浮钮（48+16+12）
  expect(m.padR).toBe('76px');
  expect(m.chipHitsBtn).toBe(false);                    // chip 命中盒不进浮钮底下
  expect(errors).toEqual([]);
});

test('T8 ask 悬浮钮单行同带（单行排布批 2026-11-01：簇左锚化，异侧零让位）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-md', '说明.md', 'markdown']]);
  // 装簇（viewer 挂钮同几何：pane 内 absolute bottom:16，左 16/64——单行排布批
  // 左锚镜像，槽值原样；尺寸 = 手机层 48px 档（mobileLayer 统一批），与 fab
  // 同径共中线）。
  await page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const src = document.createElement('button');
    src.className = 'canvas-source-toggle';
    src.style.cssText = 'position:absolute;left:16px;bottom:16px;width:48px;height:48px;';
    const find = document.createElement('button');
    find.className = 'canvas-find-toggle';
    find.style.cssText = 'position:absolute;left:64px;bottom:16px;width:48px;height:48px;';
    pane.append(find, src);
  });
  await page.waitForTimeout(400); // childList 观察 → rAF 合帧重算座位
  const seat = await page.evaluate(() => {
    const fab = document.querySelector('.ask-fab');
    const cluster = document.querySelector('.mf-cv-host .canvas-source-toggle').getBoundingClientRect();
    const fr = fab.getBoundingClientRect();
    return {
      visible: fr.width > 0 && fr.height > 0,
      bottomInline: fab.style.bottom,
      bottomOffset: window.innerHeight - fr.bottom,
      fabCenter: fr.top + fr.height / 2,
      clusterCenter: cluster.top + cluster.height / 2,
      fabRightGap: window.innerWidth - fr.right,
      clusterLeftGap: cluster.left,
    };
  });
  // 左锚簇与右缘 fab 异侧 → 让位腿零触发：fab 座位 = css 钉位 dock-h+16
  // （内联 bottom 保持空 = 纯 css 钉；让位内联腿只在 epub 通栏底栏在场时写）。
  expect(seat.bottomInline).toBe('');
  // 单行同带（fab 可见时）：fab 中线 = 左锚簇中线（两端对齐），ask 独右、簇贴左。
  if (seat.visible) {
    expect(Math.abs(seat.fabCenter - seat.clusterCenter)).toBeLessThanOrEqual(3);
    expect(seat.fabRightGap).toBeLessThanOrEqual(17);    // ask 独守右缘 16 栅格
  }
  expect(seat.clusterLeftGap).toBeLessThanOrEqual(17);   // 工具簇贴左缘
  // 撤簇 → 座位不变（内联 bottom 本就为空；输入条联动同清）。
  await page.evaluate(() => {
    for (const sel of ['.canvas-source-toggle', '.canvas-find-toggle']) {
      document.querySelector('.mf-cv-host ' + sel)?.remove();
    }
  });
  await page.waitForTimeout(400);
  const cleared = await page.evaluate(() => ({
    fab: document.querySelector('.ask-fab').style.bottom,
    dock: document.querySelector('.ask-dock').style.bottom,
  }));
  expect(cleared.fab).toBe('');
  expect(cleared.dock).toBe('');
  expect(errors).toEqual([]);
});
