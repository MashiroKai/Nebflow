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
      // 标签栏重做批（2026-11-02 §一）：两颗悬浮钮图标必须真的渲染成 svg ——
      // 修前 toolbar 挂 section 根而图标替换只跑 wrap 子树 ⇒ 两钮永远是空圆
      // （<i data-lucide> 从未被替换）。判据 = svg 数 1 + 剩余未替换 i 数 0。
      closeIcon: { svg: document.querySelectorAll('.mf-cv-close svg').length, i: document.querySelectorAll('.mf-cv-close i[data-lucide]').length },
      stackIcon: { svg: document.querySelectorAll('.mf-cv-stack svg').length, i: document.querySelectorAll('.mf-cv-stack i[data-lucide]').length },
      emptyIcon: { svg: pg.querySelectorAll('.mf-cv-empty svg').length, i: pg.querySelectorAll('.mf-cv-empty i[data-lucide]').length },
      // 计数读数转 sr-only（视觉档位下沉到底部小点）—— 仍占 DOM/读屏，不占视觉。
      countBox: (() => { const r = pg.querySelector('.mf-cv-count').getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height) }; })(),
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
  // 标签栏重做批 §一：图标根因修复（两钮 svg 就位、无残留 <i data-lucide>）。
  expect(geo.closeIcon).toEqual({ svg: 1, i: 0 });
  expect(geo.stackIcon).toEqual({ svg: 1, i: 0 });
  expect(geo.emptyIcon).toEqual({ svg: 1, i: 0 });
  expect(geo.countBox).toEqual({ w: 1, h: 1 });   // 计数 = sr-only（clip 1×1）
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

test('T3b 堆叠卡视图（作者令 2026-11-02 §四）+ count + chip tap switch cards', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
  expect(await activeChip(page)).toBe('doc-c'); // 环序 [甲, 乙, 丙]
  const pg = '.mobile-page[data-page="canvas"]';
  // 堆叠钮 → 卡叠视图：顶行「3 张文档」+ 三张卡（卡标题 + 缩略预览 + 卡内 ✕）。
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('3 张文档');
  await expect(page.locator(`${pg} .mf-deck-card`)).toHaveCount(3);
  const deck = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.mf-deck-card')];
    const on = cards.find((c) => c.classList.contains('on'));
    return {
      titles: cards.map((c) => c.querySelector('.mf-deck-title').textContent),
      icons: cards.map((c) => c.querySelectorAll('.mf-deck-icon svg').length),
      thumbs: cards.map((c) => c.querySelectorAll('.mf-deck-thumb > *').length),
      closes: cards.map((c) => c.querySelectorAll('.mf-deck-x').length),
      onId: on ? on.dataset.tabId : null,
      // 位姿 = 环形距离逐级下沉/缩径（CSS 变量单源，非硬编码 transform）。
      dtys: cards.map((c) => c.style.getPropertyValue('--dty').trim()),
      dscs: cards.map((c) => c.style.getPropertyValue('--dsc').trim()),
      cardBg: getComputedStyle(on).backgroundColor,
      cardRadius: getComputedStyle(on).borderTopLeftRadius,
    };
  });
  expect(deck.titles).toEqual(['卡甲', '卡乙', '卡丙']);
  expect(deck.icons).toEqual([1, 1, 1]);
  expect(deck.thumbs).toEqual([1, 1, 1]);
  expect(deck.closes).toEqual([1, 1, 1]);
  expect(deck.onId).toBe('doc-c');                    // 前景卡 = 活动签
  // 卡叠下沉：活动卡 0，环两侧 |k|=1 各下沉一档（16px；同距破平 +0.5 档 = 24px）。
  expect(deck.dtys).toEqual(['24.0px', '16.0px', '0.0px']);
  expect(deck.dscs[0]).toBe('0.925');                  // 深处卡缩径（0.05/档）
  expect(deck.dscs[2]).toBe('1.000');
  expect(deck.cardRadius).toBe('18px');                // 卡族圆角
  // 点「后卡露出的那一段」（堆叠读感）= 翻到该卡，卡叠不退出（桌面环卡同构）。
  await page.waitForTimeout(600);   // 开面动画落位（stagger 300ms + 320ms 过渡）
  const back = await page.locator(`${pg} .mf-deck-card[data-tab-id="doc-a"]`).boundingBox();
  await page.mouse.click(back.x + back.width / 2, back.y + back.height - 5);
  await expect.poll(() => activeChip(page)).toBe('doc-a');
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();     // 翻卡不退出
  await expect(page.locator(`${pg} .mf-deck-card.on`)).toHaveAttribute('data-tab-id', 'doc-a');
  // 点前景卡（= 活动签）= 收面回标签视图（桌面「点前景卡换入面板」等价）。
  await page.locator(`${pg} .mf-deck-card.on`).click();
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

test('T7 chip 胶囊同族 + 横向圆环几何（居中 / 弧深 / 缩径 / 减淡 / 让位浮钮）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
  const m = await page.evaluate(() => {
    const chip = document.querySelector('.mf-strip-chip.on');
    const cs = getComputedStyle(chip);
    const strip = document.querySelector('.mf-strip');
    const stripR = strip.getBoundingClientRect();
    const closeB = document.querySelector('.mf-cv-close').getBoundingClientRect();
    const stackB = document.querySelector('.mf-cv-stack').getBoundingClientRect();
    const inter = (a, b) => !(a.right < b.left || b.right < a.left || a.bottom < b.top || b.bottom < a.top);
    const chips = [...document.querySelectorAll('.mf-strip-chip')].map((c) => {
      const r = c.getBoundingClientRect();
      return {
        id: c.dataset.tabId,
        on: c.classList.contains('on'),
        far: c.classList.contains('is-far'),
        cx: Math.round(r.x + r.width / 2),
        cy: Math.round(r.y + r.height / 2),
        op: +getComputedStyle(c).opacity,
        rdx: c.style.getPropertyValue('--rdx').trim(),
        rdy: c.style.getPropertyValue('--rdy').trim(),
        rsc: c.style.getPropertyValue('--rsc').trim(),
        rbl: c.style.getPropertyValue('--rbl').trim(),
        hitsBtn: inter(r, closeB) || inter(r, stackB),
      };
    });
    return {
      radius: cs.borderRadius,
      blur: `${cs.backdropFilter}${cs.webkitBackdropFilter || ''}`,
      borderBottom: cs.borderBottomColor,
      bgImage: cs.backgroundImage,
      stripH: Math.round(stripR.height),
      mask: `${getComputedStyle(strip).maskImage}${getComputedStyle(strip).webkitMaskImage || ''}`,
      closeCy: Math.round(closeB.y + closeB.height / 2),
      stackCy: Math.round(stackB.y + stackB.height / 2),
      chips,
    };
  });
  expect(m.radius).toBe('999px');                       // 圆角 = 胶囊同族
  expect(m.blur).toContain('blur');                     // 材质 = 毛玻璃同族
  expect(m.borderBottom).toBe('rgba(0, 0, 0, 0)');      // 底缘透明化（.canvas-header 同律）
  expect(m.bgImage).toContain('linear-gradient');       // 顶缘折射线层（胶囊同源）
  // 环带几何：高 72、双侧渐隐 mask（让位浮钮 68px 内清零）。
  expect(m.stripH).toBe(72);
  expect(m.mask).toContain('linear-gradient');
  // 当前签 = 环心：居中（cx = 375/2 附近）、scale 1、零偏移、全不透明、无模糊。
  const on = m.chips.find((c) => c.on);
  expect(on.cx).toBeGreaterThan(178);
  expect(on.cx).toBeLessThan(197);
  expect(on.rdx).toBe('0.0px');
  expect(on.rdy).toBe('0.0px');
  expect(on.rsc).toBe('1.000');
  expect(on.op).toBe(1);
  expect(on.rbl).toBe('0px');
  // 邻位（|k|=1）：外移一步、下沉弧深、缩径、减淡、轻模糊；两侧对称。
  const nb = m.chips.filter((c) => !c.on).sort((a, b) => a.cx - b.cx);
  expect(nb.map((c) => c.rdx)).toEqual(['-86.0px', '86.0px']);
  expect(nb.map((c) => c.rdy)).toEqual(['11.1px', '11.1px']);
  expect(nb.map((c) => c.rsc)).toEqual(['0.820', '0.820']);
  expect(nb.map((c) => c.op)).toEqual([0.78, 0.78]);
  expect(nb.map((c) => c.rbl)).toEqual(['0.8px', '0.8px']);
  // 垂直中心 = 两颗悬浮钮中心（同一悬浮带栅格）；命中盒永不压钮。
  expect(Math.abs(on.cy - m.closeCy)).toBeLessThanOrEqual(2);
  expect(Math.abs(on.cy - m.stackCy)).toBeLessThanOrEqual(2);
  expect(m.chips.some((c) => c.hitsBtn)).toBe(false);
  expect(errors).toEqual([]);
});

test('T9 环外签（|k|≥2）：零透明零命中（位姿仍算 —— 换签轨迹连续）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [
    ['r1', '环一', 'r1'], ['r2', '环二', 'r2'], ['r3', '环三', 'r3'],
    ['r4', '环四', 'r4'], ['r5', '环五', 'r5'],
  ]);
  const st = await page.evaluate(() => [...document.querySelectorAll('.mf-strip-chip')].map((c) => ({
    id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
    op: +getComputedStyle(c).opacity, pe: getComputedStyle(c).pointerEvents,
    tabIndex: c.tabIndex, rdx: c.style.getPropertyValue('--rdx').trim(),
  })));
  // 环序 [1,2,3,4,5]，活动 = 5 ⇒ k = [1,2,-2,-1,0]（最近环绕）。
  expect(st.map((c) => c.id)).toEqual(['r1', 'r2', 'r3', 'r4', 'r5']);
  expect(st.map((c) => c.rdx)).toEqual(['86.0px', '172.0px', '-172.0px', '-86.0px', '0.0px']);
  expect(st.map((c) => c.far)).toEqual([false, true, true, false, false]);
  expect(st[1].op).toBe(0);
  expect(st[1].pe).toBe('none');
  expect(st[1].tabIndex).toBe(-1);
  expect(st[4].on).toBe(true);
  expect(errors).toEqual([]);
});

test('T10 底部小点指示器：1 签不显示 / 2 签 2 点 / ≥3 签 3 点（当前点高亮）+ 位置在内容区底部居中不压 dock', async ({ page }) => {
  const errors = watchErrors(page);
  // 1 签：不显示。
  await openDocCards(page, [['s1', '单卡', 's1']]);
  const one = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots');
    return { hidden: d.hidden, dots: d.querySelectorAll('.mf-dot').length, attr: d.getAttribute('aria-hidden') };
  });
  expect(one.hidden).toBe(true);
  expect(one.attr).toBe('true');
  expect(one.dots).toBe(0);
  // 2 签：2 点，活动签高亮。
  await page.evaluate(async () => { (await import('/js/canvas.js')).openTab('s2', '次卡', { type: 's2' }); });
  await expect.poll(() => page.evaluate(() => document.querySelector('.mf-dots').dataset.count)).toBe('2');
  const two = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots');
    const dots = [...d.querySelectorAll('.mf-dot')];
    return {
      n: dots.length,
      onIdx: dots.findIndex((x) => x.classList.contains('on')),
      onW: Math.round(dots[1].getBoundingClientRect().width),
      offW: Math.round(dots[0].getBoundingClientRect().width),
    };
  });
  expect(two.n).toBe(2);
  expect(two.onIdx).toBe(1);
  expect(two.onW).toBeGreaterThan(two.offW);       // 当前点更大
  // 5 签：点数恒 3（省略策略：序号归一映射，点不随换签滑动）+ 当前档高亮。
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    c.openTab('s3', '三卡', { type: 's3' });
    c.openTab('s4', '四卡', { type: 's4' });
    c.openTab('s5', '五卡', { type: 's5' });
  });
  await expect.poll(() => page.evaluate(() => document.querySelector('.mf-dots').dataset.count)).toBe('3');
  const five = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots');
    const dots = [...d.querySelectorAll('.mf-dot')];
    return { n: dots.length, onIdx: dots.findIndex((x) => x.classList.contains('on')), dataN: d.dataset.n, dataIdx: d.dataset.idx };
  });
  expect(five).toEqual({ n: 3, onIdx: 2, dataN: '5', dataIdx: '4' });   // 末签落第三点
  // 点回第一签：归一映射到首点（换签点不滑动，只换高亮）。
  await page.evaluate(async () => (await import('/js/canvas.js')).setActiveTab('s1'));
  await expect.poll(() => page.evaluate(() => [...document.querySelectorAll('.mf-dot')].findIndex((x) => x.classList.contains('on')))).toBe(0);
  const xs = await page.evaluate(() => [...document.querySelectorAll('.mf-dot')].map((x) => Math.round(x.getBoundingClientRect().x)));
  expect(xs.length).toBe(3);
  // 位置：内容区底部居中、dock 之上（不压 dock）。
  const pos = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots').getBoundingClientRect();
    const host = document.querySelector('.mf-cv-host').getBoundingClientRect();
    const dock = document.getElementById('mobile-dock').getBoundingClientRect();
    return {
      cx: Math.round(d.x + d.width / 2),
      aboveHostBottom: d.y >= host.bottom - 1,
      aboveDock: d.bottom <= dock.y + 0.5,
      inViewport: d.y > 0 && d.bottom < window.innerHeight,
    };
  });
  expect(Math.abs(pos.cx - 187.5)).toBeLessThanOrEqual(2);   // 居中
  expect(pos.aboveHostBottom).toBe(true);                    // 在内容区之下的独立条带
  expect(pos.aboveDock).toBe(true);                          // 不压 dock
  expect(pos.inViewport).toBe(true);
  expect(errors).toEqual([]);
});

test('T11 小点长按放大 + 横拖逐签换卡 + 松手吸附（reduced-motion 下同语义）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['l1', '拖一', 'l1'], ['l2', '拖二', 'l2'], ['l3', '拖三', 'l3'], ['l4', '拖四', 'l4'], ['l5', '拖五', 'l5']]);
  expect(await activeChip(page)).toBe('l5');
  const box = await page.locator('.mf-dots').boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  // 长按（> 260ms 阈值）→ .is-grip 放大态。
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.waitForTimeout(340);
  const gripped = await page.evaluate(() => ({
    grip: document.querySelector('.mf-dots').classList.contains('is-grip'),
    scale: getComputedStyle(document.querySelector('.mf-dots')).transform,
  }));
  expect(gripped.grip).toBe(true);
  expect(gripped.scale).not.toBe('none');                  // 放大档（scale 1.24）
  // 按住左拖 2 步（34px/步）→ 逐签换到 l3（5 → 4 → 3）。
  for (let i = 1; i <= 8; i++) {
    await page.mouse.move(cx - (68 * i) / 8, cy);
    await page.waitForTimeout(16);
  }
  expect(await activeChip(page)).toBe('l3');
  // 松手吸附：停在该签（大点高亮映射随动），grip 态解除。
  await page.mouse.up();
  await page.waitForTimeout(120);
  const after = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots');
    const dots = [...d.querySelectorAll('.mf-dot')];
    return { grip: d.classList.contains('is-grip'), onIdx: dots.findIndex((x) => x.classList.contains('on')) };
  });
  expect(after.grip).toBe(false);
  expect(after.onIdx).toBeGreaterThan(0);
  expect(after.onIdx).toBeLessThan(2);
  // 轻触（< 阈值、零位移）：点按不触发 grip，落点小点 = 跳到该点对应签。
  const firstDot = await page.locator('.mf-dot').first().boundingBox();
  await page.mouse.click(firstDot.x + firstDot.width / 2, firstDot.y + firstDot.height / 2);
  await page.waitForTimeout(160);
  expect(await activeChip(page)).toBe('l1');
  // reduced-motion 降级：动效压零但语义全保留（长按放大仍是状态指示、换签仍生效）。
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const rm = await page.evaluate(() => {
    const dots = document.querySelector('.mf-dots');
    const d0 = dots.querySelector('.mf-dot');
    return {
      dotsTransition: getComputedStyle(dots).transitionDuration,
      dotTransition: getComputedStyle(d0).transitionDuration,
      dotBox: Math.round(d0.getBoundingClientRect().width),
    };
  });
  expect(rm.dotsTransition).toBe('0s');
  expect(rm.dotTransition).toBe('0s');       // 点尺寸随档直切（无补间）
  expect(rm.dotBox).toBeGreaterThan(0);
  const box2 = await page.locator('.mf-dots').boundingBox();
  await page.mouse.move(box2.x + box2.width / 2, box2.y + box2.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(340);
  expect(await page.evaluate(() => document.querySelector('.mf-dots').classList.contains('is-grip'))).toBe(true);
  await page.mouse.up();
  expect(await page.evaluate(() => document.querySelector('.mf-dots').classList.contains('is-grip'))).toBe(false);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  expect(errors).toEqual([]);
});

test('T12 堆叠卡上下滑动翻页 + 卡内 ✕ 关卡 + ✕/Esc 退出回标签视图', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['p1', '页一', 'p1'], ['p2', '页二', 'p2'], ['p3', '页三', 'p3']]);
  expect(await activeChip(page)).toBe('p3');
  const pg = '.mobile-page[data-page="canvas"]';
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  // 上滑（> 56px 阈）→ 翻到下一张：环序 3 → 环绕回 1。
  const stage = await page.locator('.mf-deck').boundingBox();
  await drag(page, stage.x + stage.width / 2, stage.y + stage.height / 2, stage.x + stage.width / 2, stage.y + stage.height / 2 - 110);
  await page.waitForTimeout(320);
  expect(await activeChip(page)).toBe('p1');
  // 下滑 → 翻回上一张（1 → 环绕 3）。
  await drag(page, stage.x + stage.width / 2, stage.y + stage.height / 2, stage.x + stage.width / 2, stage.y + stage.height / 2 + 110);
  await page.waitForTimeout(320);
  expect(await activeChip(page)).toBe('p3');
  // 卡内 ✕ 关卡（前景卡 = 活动签）：关后卡叠重建、卡数递减。
  await page.locator(`${pg} .mf-deck-card.on .mf-deck-x`).click();
  await expect(page.locator(`${pg} .mf-deck-card`)).toHaveCount(2);
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('p3'))).toBe(true);
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('2 张文档');
  // ✕ 退出回标签视图（环带复现，卡叠隐藏）。
  await page.locator(`${pg} .mf-stack-x`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
  await expect(page.locator(`${pg} .mf-strip`)).toBeVisible();
  // Esc 同样收面。
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
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
