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
// 中签两端零虚化渐隐批（2026-11-02 11:23）：T13① 的像素腿判据（边缘斜坡带宽度）。
import { labelEdgeRamp } from './_label-edge-pixels.mjs';

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

test('T3b 堆叠卡视图（作者令 2026-11-02 §四；21:47 §三 iOS app switcher 形态）+ count + chip tap switch cards', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
  expect(await activeChip(page)).toBe('doc-c'); // 环序 [甲, 乙, 丙]
  const pg = '.mobile-page[data-page="canvas"]';
  // 堆叠钮 → 卡叠视图：顶行「3 张文档」+ 三张卡（卡标题 + 缩略预览 + 卡内 ✕）。
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('3 张文档');
  await expect(page.locator(`${pg} .mf-deck-card`)).toHaveCount(3);
  await page.waitForTimeout(450);          // 开面动画落位（stagger 150ms + 320ms 过渡）
  const deck = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.mf-deck-card')];
    const on = cards.find((c) => c.classList.contains('on'));
    const stage = document.querySelector('.mf-deck').getBoundingClientRect();
    return {
      titles: cards.map((c) => c.querySelector('.mf-deck-title').textContent),
      icons: cards.map((c) => c.querySelectorAll('.mf-deck-icon svg').length),
      thumbs: cards.map((c) => c.querySelectorAll('.mf-deck-thumb > *').length),
      closes: cards.map((c) => c.querySelectorAll('.mf-deck-x').length),
      onId: on ? on.dataset.tabId : null,
      // 位姿 = 环形距离逐级**横移**/缩径/压暗/模糊（iOS app switcher 横列，CSS
      // 变量单源，非硬编码 transform）。旧「纵向卡叠」的 --dty 下沉已退役。
      dtys: cards.map((c) => c.style.getPropertyValue('--dty').trim()),
      dtysRaw: cards.map((c) => c.style.getPropertyValue('--dtx').trim()),
      dscs: cards.map((c) => c.style.getPropertyValue('--dsc').trim()),
      dop: cards.map((c) => +c.style.getPropertyValue('--dop')),
      ddark: cards.map((c) => +c.style.getPropertyValue('--ddark')),
      dblur: cards.map((c) => c.style.getPropertyValue('--dblur').trim()),
      // 中央卡实际渲染盒 vs 邻卡：中央最大、左右邻卡被舞台裁切（只露一部分）。
      boxes: cards.map((c) => {
        const r = c.getBoundingClientRect();
        return { id: c.dataset.tabId, x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height) };
      }),
      stageW: Math.round(stage.width),
      cardBg: getComputedStyle(on).backgroundColor,
      cardRadius: getComputedStyle(on).borderTopLeftRadius,
      // 背景重度模糊（iOS app switcher 的桌面被糊开档）。
      viewBlur: getComputedStyle(document.querySelector('.mf-stack-view')).backdropFilter
        || getComputedStyle(document.querySelector('.mf-stack-view')).webkitBackdropFilter,
    };
  });
  expect(deck.titles).toEqual(['卡甲', '卡乙', '卡丙']);
  expect(deck.icons).toEqual([1, 1, 1]);
  expect(deck.thumbs).toEqual([1, 1, 1]);
  expect(deck.closes).toEqual([1, 1, 1]);
  expect(deck.onId).toBe('doc-c');                    // 前景卡 = 活动签
  // 横列：无纵向位移；两侧卡一左一右外移一步（环序甲乙丙、活动丙 ⇒ 乙在左、
  // 甲环绕到右）；中央零偏移。
  expect(deck.dtys).toEqual(['0.0px', '0.0px', '0.0px']);
  expect(deck.dtysRaw[2]).toBe('0.0px');
  expect([deck.dtysRaw[0].startsWith('-'), deck.dtysRaw[1].startsWith('-')].sort()).toEqual([false, true]);
  // 越靠边越暗越糊：den 单调（甲=乙 < 中央），opacity 同理，blur 反之。
  expect(deck.dscs[2]).toBe('1.000');
  expect(+deck.dscs[0]).toBeLessThan(1);
  expect(deck.dop[2]).toBe(1);
  expect(deck.dop[0]).toBeLessThan(1);
  expect(deck.ddark[2]).toBe(1);
  expect(deck.ddark[0]).toBeLessThan(1);
  expect(deck.dblur[2]).toBe('0.00px');
  expect(parseFloat(deck.dblur[0])).toBeGreaterThan(0);
  // 中央卡最大最清晰；邻卡渲染盒被舞台左右裁切（只露一部分 = app switcher 读感）。
  const center = deck.boxes.find((b) => b.id === 'doc-c');
  const sides = deck.boxes.filter((b) => b.id !== 'doc-c');
  for (const s of sides) expect(s.h).toBeLessThan(center.h);
  expect(sides.some((s) => s.x < 0 || s.x + s.w > deck.stageW)).toBe(true);
  expect(deck.cardRadius).toBe('20px');                // 卡族圆角（iOS 卡面档）
  expect(deck.viewBlur).toContain('blur(26px)');       // 背景重度模糊
  // 点「邻卡露出的那一段」（app switcher 读感）= 换入中央，卡叠不退出。
  // 取 |k|=1 的邻卡（本环序活动=丙 ⇒ 乙在左邻；甲已绕到 |k|=2 = .is-far 零命中）。
  // 邻卡中心在屏幕外（app switcher 的必然：只露一截）⇒ 点**露出的那条边**。
  await page.waitForTimeout(400);   // 开面动画落位 + 避开 350ms click 抑制窗
  const back = await page.locator(`${pg} .mf-deck-card[data-tab-id="doc-b"]`).boundingBox();
  const bx = back.x < 0 ? 8 : Math.min(back.x + back.width - 8, 375 - 8);
  await page.mouse.click(bx, back.y + back.height / 2);
  await expect.poll(() => activeChip(page)).toBe('doc-b');
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();     // 换卡不退出
  await expect(page.locator(`${pg} .mf-deck-card.on`)).toHaveAttribute('data-tab-id', 'doc-b');
  // 点中央卡（= 活动签）= 收面回阅读（iOS app switcher 同构）。
  await page.locator(`${pg} .mf-deck-card.on`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
  expect(await activeChip(page)).toBe('doc-b');
  await expect(page.locator(`${pg} .mf-cv-count`)).toHaveText('2/3');
  // chip tap → 跳卡甲（1/3，环签上的可点入口）。
  await page.locator(`${pg} .mf-strip-chip[data-tab-id="doc-a"]`).click();
  expect(await activeChip(page)).toBe('doc-a');
  await expect(page.locator(`${pg} .mf-cv-count`)).toHaveText('1/3');
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

test('T7 chip 胶囊同族 + 横向圆环几何（居中 / 缩径 / 连续减淡 / 让位浮钮）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
  const m = await page.evaluate(() => {
    const chip = document.querySelector('.mf-strip-chip.on');
    const cs = getComputedStyle(chip);
    // 渐隐热区修复批（::after 迁移）：玻璃材质四件套（玻璃底/描边/折射线/
    // backdrop-filter）已从宿主迁到 ::after 视觉层 —— 读取面同步迁，否则读数
    // 恒为 none（判据被架空成永真）。
    const after = getComputedStyle(chip, '::after');
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
      blur: `${after.backdropFilter}${after.webkitBackdropFilter || ''}`,
      borderBottom: after.borderBottomColor,
      bgImage: after.backgroundImage,
      // 宿主本体零视觉底（迁移的机械判据之一：背景/描边/玻璃在宿主上必须为空）。
      hostBg: cs.backgroundImage,
      hostBorder: cs.borderTopWidth,
      hostBlur: cs.backdropFilter || cs.webkitBackdropFilter || 'none',
      stripH: Math.round(stripR.height),
      mask: `${getComputedStyle(strip).maskImage}${getComputedStyle(strip).webkitMaskImage || ''}`,
      closeCy: Math.round(closeB.y + closeB.height / 2),
      stackCy: Math.round(stackB.y + stackB.height / 2),
      chips,
    };
  });
  expect(m.radius).toBe('999px');                       // 圆角 = 胶囊同族
  expect(m.blur).toContain('blur');                     // 材质 = 毛玻璃同族（::after 视觉层）
  expect(m.borderBottom).toBe('rgba(0, 0, 0, 0)');      // 底缘透明化（.canvas-header 同律）
  expect(m.bgImage).toContain('linear-gradient');       // 顶缘折射线层（胶囊同源）
  // 宿主本体零视觉底：玻璃材质只在 ::after 承载（热区修复批的机械前提 —— 宿主
  // 一旦带 mask/视觉底，渐隐区的 hit-test 就会跟着被裁，两端就点不到了）。
  expect(m.hostBg).toBe('none');
  expect(m.hostBorder).toBe('0px');
  expect(m.hostBlur).toBe('none');
  // 环带几何：高 72、双侧渐隐 mask（让位浮钮 68px 内清零）。
  expect(m.stripH).toBe(72);
  expect(m.mask).toContain('linear-gradient');
  // 当前签 = 环心：居中（cx = 375/2 附近）、零偏移、全不透明、无模糊。
  const on = m.chips.find((c) => c.on);
  expect(on.cx).toBeGreaterThan(178);
  expect(on.cx).toBeLessThan(197);
  expect(on.rdx).toBe('0.0px');
  // 对齐修正批（2026-11-02 09:00）：纵向弧深整条退役 —— `--rdy` 变量不再写，
  // transform 里也没有 dy 项，签心恒 y=40（几何断言在 T13 里机械钉死）。
  expect(on.rdy).toBe('');
  expect(on.rsc).toBe('1.140');
  expect(on.op).toBe(1);
  expect(on.rbl).toBe('0.00px');
  // 邻位（|k|=1）：外移一步、缩径、连续减淡、连续轻模糊；两侧对称；**不下沉**。
  const nb = m.chips.filter((c) => !c.on).sort((a, b) => a.cx - b.cx);
  expect(nb.map((c) => c.rdx)).toEqual(['-95.0px', '95.0px']);
  expect(nb.map((c) => c.rsc)).toEqual(['0.800', '0.800']);
  expect(nb.map((c) => c.op)).toEqual([0.66, 0.66]);
  expect(nb.map((c) => c.rbl)).toEqual(['0.70px', '0.70px']);
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
    rsc: c.style.getPropertyValue('--rsc').trim(), rbl: c.style.getPropertyValue('--rbl').trim(),
  })));
  // 环序 [1,2,3,4,5]，活动 = 5 ⇒ k = [1,2,-2,-1,0]（最近环绕）。
  expect(st.map((c) => c.id)).toEqual(['r1', 'r2', 'r3', 'r4', 'r5']);
  expect(st.map((c) => c.rdx)).toEqual(['95.0px', '190.0px', '-190.0px', '-95.0px', '0.0px']);
  expect(st.map((c) => c.far)).toEqual([false, true, true, false, false]);
  expect(st[1].op).toBe(0);
  expect(st[1].pe).toBe('none');
  expect(st[1].tabIndex).toBe(-1);
  // 曲线在环外触底（scale 触底、blur 封顶）而不归零 —— 换签轨迹连续。
  expect(st[1].rsc).toBe('0.600');
  expect(st[1].rbl).toBe('2.60px');
  expect(st[4].on).toBe(true);
  expect(errors).toEqual([]);
});

test('T13 对齐修正批五条 + 中签两端零虚化渐隐批六条机械断言', async ({ page }) => {
  // ── 本批（作者令 2026-11-02 11:23「前置显示的那个标签，两端不要有虚化渐隐」）
  // 把上一批 T13 的 ④ 拆成两条并新增 ⑤：语义从「所有签都靠 mask 化开」改成
  // 「**只有邻签化开，中签两端硬边**」。六条断言与批文档 §3 读数表一一对应：
  //   ① 中签 label `mask-image` = none，且像素腿斜坡带 ≤5 设备像素（端点未被淡化）
  //   ② 两侧邻签 mask 仍生效（linear-gradient），且越靠边 opacity 更低 / blur 更大
  //   ③ 全组可见签渲染盒垂直中心极差 ≤1px（同水平线，沿用）
  //   ④ 全仓零省略号（text-overflow=clip + 文本/title 无 U+2026）
  //   ⑤ 超长页名（≥10 汉字）整字收束：不溢出签盒 / 不硬裁（收在字界）/ 无省略号 /
  //      展开腿能显示全名
  //   ⑥ 中签与邻签 12 项视觉属性逐项相等，**唯一差异项 = 有无两端渐隐**（mask）
  // 上一批的 ②（中签更大）与 ⑤（热区 ≥44 + 签心对钮线）两条照旧保留，编号不动。
  const errors = watchErrors(page);

  /** 清空页签 + 灌入 n 张卡（末张给超长标题）—— 覆盖 2/3/5/8 签四档。 */
  const seedTabs = async (n) => {
    await page.evaluate(async () => {
      const c = await import('/js/canvas.js');
      const nav = await import('/js/mobile/navController.js');
      for (const d of nav.docTabs()) c.closeTab(d.id);
    });
    await page.waitForTimeout(200);
    await page.evaluate((list) => import('/js/canvas.js').then((canvas) => {
      for (const [id, title, type] of list) canvas.openTab(id, title, { type });
    }), Array.from({ length: n }, (_, i) => [
      `t13-${i}`,
      i === n - 1 ? '7451 lugi 命名规约汇编（超长标题档·验零省略号）' : `签${i + 1}`,
      `t13-type-${i}`,
    ]));
    await dockTo(page, 'canvas');
    await page.waitForTimeout(520);   // 位姿过渡落定（320ms 曲线）
  };

  const probe = () => page.evaluate(() => {
    const chips = [...document.querySelectorAll('.mf-strip-chip')];
    const close = document.querySelector('.mf-cv-close').getBoundingClientRect();
    const stack = document.querySelector('.mf-cv-stack').getBoundingClientRect();
    const box = (el) => {
      const r = el.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height, cy: r.top + r.height / 2, cx: r.left + r.width / 2 };
    };
    return {
      strip: {
        h: document.querySelector('.mf-strip').getBoundingClientRect().height,
        mask: getComputedStyle(document.querySelector('.mf-strip')).maskImage,
        fade: document.querySelector('.mf-strip').classList.contains('fade'),
      },
      fabCy: { close: close.top + close.height / 2, stack: stack.top + stack.height / 2 },
      fabBox: { closeRight: close.right, stackLeft: stack.left },
      chips: chips.map((c) => {
        const cs = getComputedStyle(c);
        const label = c.querySelector('.mf-strip-label');
        const lcs = getComputedStyle(label);
        const before = getComputedStyle(c, '::before');
        const acs = getComputedStyle(c, '::after');   // 玻璃视觉层（::after 迁移批）
        const lr = label.getBoundingClientRect();
        return {
          id: c.dataset.tabId,
          on: c.classList.contains('on'),
          far: c.classList.contains('is-far'),
          expanded: c.classList.contains('is-expanded'),
          op: +cs.opacity,
          blur: cs.filter,
          rsc: +c.style.getPropertyValue('--rsc'),
          ...box(c),
          // ⑥ 视觉语言同源面：中签与两侧签必须逐项相等（只有尺寸/透明度/模糊/渐隐不同）
          // 渐隐热区修复批（::after 迁移）：材质/描边/折射线/阴影/玻璃全部下沉
          // ::after 视觉层 —— 读取面同步迁（宿主上这些恒为空，读宿主等于永真）。
          radius: cs.borderRadius,
          bgImage: acs.backgroundImage,
          bgColor: acs.backgroundColor,
          borderTop: acs.borderTopColor,
          borderBottom: acs.borderBottomColor,
          borderWidth: acs.borderTopWidth,
          shadow: acs.boxShadow,
          weight: cs.fontWeight,
          size: cs.fontSize,
          family: cs.fontFamily,
          color: cs.color,
          glass: acs.backdropFilter || acs.webkitBackdropFilter,
          // 宿主零视觉底 + 零 mask（热区修复的机械前提；两者任一回到宿主即复发）
          hostBg: cs.backgroundImage,
          hostBlur: cs.backdropFilter || cs.webkitBackdropFilter || 'none',
          hostMask: cs.maskImage || cs.webkitMaskImage || 'none',
          hostBorderW: cs.borderTopWidth,
          // 视觉层的方向渐隐（中签无、邻签有）
          afterMask: acs.maskImage || acs.webkitMaskImage || 'none',
          // ① / ② 渐隐面
          textOverflow: lcs.textOverflow,
          labelMask: lcs.maskImage || lcs.webkitMaskImage,
          overflow: lcs.overflow,
          labelW: lr.width,
          labelSW: label.scrollWidth,
          labelCssMaxW: label.style.maxWidth || '',
          labelRight: lr.right,
          text: label.textContent,
          title: c.title,
          // ⑤ 热区面（::before 的计算尺寸 = 渲染尺寸：伪元素自带 1/--rsc 反向
          // 缩放，与 chip 的 scale 相抵，净变换恒为单位阵 —— 见 CSS 命中盒注释）
          hitW: parseFloat(before.width),
          hitH: parseFloat(before.height),
        };
      }),
    };
  });

  /** 像素腿：截当前中签 label 框（含 2px 边距）并量两端斜坡带宽度（设备像素）。 */
  const centerRamp = async () => {
    const b = await page.evaluate(() => {
      const l = document.querySelector('.mf-strip-chip.on .mf-strip-label');
      const r = l.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height };
    });
    const buf = await page.screenshot({
      clip: { x: Math.max(0, b.x - 2), y: Math.max(0, b.y - 2), width: Math.min(b.w + 4, 370), height: b.h + 4 },
    });
    return labelEdgeRamp(buf);
  };

  const ratios = [];
  const ramps = [];
  for (const n of [2, 3, 5, 8]) {
    await seedTabs(n);
    const p = await probe();

    // ── ③ 全组露出纸签渲染盒垂直中心极差 ≤1px（作者令「不应该都是同一水平吗」）
    const shown = p.chips.filter((c) => c.op > 0.01);
    expect(shown.length).toBeGreaterThanOrEqual(Math.min(n, 3));
    const cys = shown.map((c) => c.cy);
    const spread = Math.max(...cys) - Math.min(...cys);
    expect(spread).toBeLessThanOrEqual(1);

    // ── ② 中签渲染宽/高严格大于次签（次签 = 可见非中签里最大的那枚）
    const on = p.chips.find((c) => c.on);
    const side = p.chips.filter((c) => !c.on && c.op > 0.01).sort((a, b) => b.w - a.w)[0];
    expect(on).toBeTruthy();
    expect(side).toBeTruthy();
    expect(on.w).toBeGreaterThan(side.w);
    expect(on.h).toBeGreaterThan(side.h);
    ratios.push({ n, onW: +on.w.toFixed(1), sideW: +side.w.toFixed(1), wRatio: +(on.w / side.w).toFixed(3), hRatio: +(on.h / side.h).toFixed(3) });

    // ── ⑥ 中签与两侧签的视觉语言逐项一致（只允许尺寸 / 透明度 / 模糊 / 渐隐不同）
    // 渐隐热区修复批特例：'glass'（backdrop-filter）单键豁免 —— blur 只在中签
    // 的 ::after（无 mask 层）；邻签 ::after 带 mask，headless 软渲染下同元素
    // mask × backdrop-filter 会把描边/玻璃面按未裁剪输出（mask 架空，最小复现
    // 见 mobileLayer.css ::after 注）。材质其余 11 键仍逐项相等。
    const VISUAL_KEYS = ['radius', 'bgImage', 'bgColor', 'borderTop', 'borderBottom', 'borderWidth',
      'shadow', 'weight', 'size', 'family', 'color'];
    for (const k of VISUAL_KEYS) {
      expect({ n, key: k, on: on[k] }).toEqual({ n, key: k, on: side[k] });
    }
    // glass 单键例外记账：中签 = blur 档、邻签 = none（分层而非缺材质）。
    expect({ n, onGlass: on.glass }).toEqual({ n, onGlass: expect.stringContaining('blur') });
    expect({ n, sideGlass: side.glass }).toEqual({ n, sideGlass: 'none' });
    // 12 项之外**唯一**的视觉差异 = 有无两端渐隐（本批作者令）；逐项记账成对拍死，
    // 防后续批悄悄多出第二个差异项（glass 已在上一行单独豁免并记账）。
    expect({ n, diff: ['radius', 'bgImage', 'bgColor', 'borderTop', 'borderBottom', 'borderWidth',
      'shadow', 'weight', 'size', 'family', 'color'].filter((k) => on[k] !== side[k]) })
      .toEqual({ n, diff: [] });
    expect(on.labelMask).not.toBe(side.labelMask);
    // 渐隐热区修复批（::after 迁移）三钉：宿主全组零视觉底 / 零 mask / 零描边，
    // 视觉层方向渐隐只落邻签（中签无）—— 任何一项回到宿主，渐隐区的 hit-test
    // 立即随行被裁（两端渐隐点不到的病根复发面）。
    for (const c of p.chips) {
      expect({ n, id: c.id, hostBg: c.hostBg }).toEqual({ n, id: c.id, hostBg: 'none' });
      expect({ n, id: c.id, hostBlur: c.hostBlur }).toEqual({ n, id: c.id, hostBlur: 'none' });
      expect({ n, id: c.id, hostMask: c.hostMask }).toEqual({ n, id: c.id, hostMask: 'none' });
      expect({ n, id: c.id, hostBorderW: c.hostBorderW }).toEqual({ n, id: c.id, hostBorderW: '0px' });
    }
    expect(on.afterMask).toBe('none');

    // ── ④ 任一纸签无省略号：text-overflow = clip + 文本/title 无 U+2026
    for (const c of p.chips) {
      expect(c.textOverflow).toBe('clip');
      expect(c.text.includes('\u2026')).toBe(false);
      expect(c.title.includes('\u2026')).toBe(false);
      expect(c.title).toBe(c.text);                        // 全名留在 title（读屏/悬停）
    }
    // 中签长标题确实溢出（否则 ①/⑤ 断言没验到东西）。单位对齐：scrollWidth 是
    // **布局宽**，labelW 是**渲染宽**（= 布局宽 × --rsc），故左侧乘回 rsc 比较。
    expect(on.labelSW).toBeGreaterThan(on.labelW / on.rsc);

    // ── ① 中签两端零虚化渐隐：计算样式腿（mask-image = none）+ 像素腿（斜坡带 ≤5）
    expect({ n, onMask: on.labelMask }).toEqual({ n, onMask: 'none' });
    const ramp = await centerRamp();
    ramps.push({ n, rampLeft: ramp.rampLeft, rampRight: ramp.rampRight, firstRatio: ramp.firstRatio, lastRatio: ramp.lastRatio, interiorMedian: +ramp.interiorMedian.toFixed(1) });
    // 斜坡带 ≤5 设备像素 = 只剩字形自身的抗锯齿边；若 mask 还在，实测 14–18。
    expect({ n, rampLeft: ramp.rampLeft }).toEqual({ n, rampLeft: expect.any(Number) });
    expect(ramp.rampLeft).toBeLessThanOrEqual(5);
    expect(ramp.rampRight).toBeLessThanOrEqual(5);
    // 端点列仍有墨（不是被洗成透明）
    expect(ramp.firstRatio).toBeGreaterThan(0.5);
    expect(ramp.lastRatio).toBeGreaterThan(0.5);

    // ── ② 两侧邻签渐隐与模糊原样保留（作者上一批「越到边缘越模糊」不撤）
    const nbs = p.chips.filter((c) => !c.on && c.op > 0.01);
    expect(nbs.length).toBeGreaterThanOrEqual(1);
    for (const c of nbs) {
      expect({ n, nb: c.id, mask: c.labelMask }).toEqual({
        n, nb: c.id, mask: expect.stringContaining('linear-gradient'),
      });
      // ::after 视觉层的方向渐隐（与 label 同向）：玻璃层在中签无 mask、邻签有。
      expect({ n, nb: c.id, afterMask: c.afterMask }).toEqual({
        n, nb: c.id, afterMask: expect.stringContaining('linear-gradient'),
      });
    }
    // 单调性：越靠边（离中签越远）opacity 更低、blur 更大 —— 沿用上一批断言。
    // 左右同环次（同一 |k|）读数必须**相等**（环对称）；不同环次必须严格单调。
    // （可见邻签通常只有 ±1 两枚，|k|≥2 已掉到 opacity 0，故按环次分组比较。）
    const byDist = [...nbs].map((c) => ({ ...c, dist: +Math.abs(c.cx - on.cx).toFixed(1) }));
    byDist.sort((a, b) => a.dist - b.dist);
    for (let i = 1; i < byDist.length; i++) {
      const prev = byDist[i - 1];
      const cur = byDist[i];
      if (cur.dist > prev.dist + 0.5) {
        expect(cur.op).toBeLessThan(prev.op);
        expect(parseFloat(cur.blur.replace(/[^\d.]/g, '')))
          .toBeGreaterThan(parseFloat(prev.blur.replace(/[^\d.]/g, '')));
      } else {
        // 同环次（对称位）：读数必须一致 —— 「越靠边越淡」的曲线本身不动。
        expect(cur.op).toBe(prev.op);
        expect(cur.blur).toBe(prev.blur);
      }
    }

    // ── ⑤ 热区 ≥44（含环位姿缩放后的实际尺寸）+ 签心 = 两悬浮钮中心线 + 不压钮
    for (const c of p.chips) {
      expect(c.hitW).toBeGreaterThanOrEqual(43.5);
      expect(c.hitH).toBeGreaterThanOrEqual(43.5);
    }
    expect(Math.abs(on.cy - p.fabCy.close)).toBeLessThanOrEqual(2);
    expect(Math.abs(on.cy - p.fabCy.stack)).toBeLessThanOrEqual(2);
    // 不压浮钮：可见签的渲染盒左右缘都在两颗 48px 浮钮之外（16…64 / 311…359）。
    const closeR = p.fabBox.closeRight;      // ✕ 钮右缘 = 64
    const stackL = 375 - 64;                 // 堆叠钮左缘 = 311
    for (const c of shown) {
      expect(c.x).toBeGreaterThanOrEqual(closeR);
      expect(c.x + c.w).toBeLessThanOrEqual(stackL);
    }

    // 环带几何本身：高 72 + 双侧 mask 保留（与邻签淡化曲线叠加，作者令「别打架」）
    expect(p.strip.h).toBe(72);
    expect(p.strip.fade).toBe(true);
    expect(p.strip.mask).toContain('linear-gradient');
  }

  // ── ⑤ 超长页名（≥10 汉字）实测：整字收束，不溢出 / 不硬裁 / 无省略号；
  //    展开腿能显示全名（作者令「超宽文字必须给出明确行为」——走既有展开腿）。
  await seedTabs(3);
  // 标题取 11 个汉字（≥10 满足「超长页名」判据）：常态可用宽 78px 只放得下 6 个字
  // ⇒ 必然收束；展开档可用宽 168.6px ⇒ 11 字（143px）**完整放得下** ⇒ 全名可见。
  // 更长的行（如 30 字）展开档也放不全，那时全名仍留在 title / DOM 文本（批文档 §5 申报）。
  const LONG = '命名规约汇编与落盘纪律';   // 11 汉字
  await page.evaluate(async (title) => {
    const c = await import('/js/canvas.js');
    const nav = await import('/js/mobile/navController.js');
    for (const d of nav.docTabs()) c.closeTab(d.id);
    c.openTab('t13-long', title, { type: 't13-long-type' });
    c.openTab('t13-a', '背景任务设计', { type: 't13-a-type' });
    c.openTab('t13-b', '单文件画像', { type: 't13-b-type' });
  }, LONG);
  await page.waitForTimeout(420);
  await page.locator('.mf-strip-chip[data-tab-id="t13-long"]').click();
  await page.waitForTimeout(520);
  const longP = await probe();
  const longOn = longP.chips.find((c) => c.on);
  expect(longOn.id).toBe('t13-long');
  expect(longOn.text).toBe(LONG);
  // 不溢出签盒：label 右缘在签盒内（左缘受 flex 居中约束，同样在内）
  expect(longOn.labelRight).toBeLessThanOrEqual(longOn.x + longOn.w - 1);
  // 不硬裁：有内联 max-width 收束值，且渲染宽恰好等于「该值 × 环位姿 scale」
  // （max-width 是布局宽，中签 --rsc=1.14 ⇒ 渲染 65 × 1.14 = 74.1；不是被裁窄）。
  expect(longOn.labelCssMaxW).not.toBe('');
  const cssW = parseFloat(longOn.labelCssMaxW);
  expect(Math.abs(longOn.labelW - cssW * longOn.rsc)).toBeLessThanOrEqual(0.6);
  // 收在字界：把 CSS 宽 × rsc 还原成「布局宽」后，逐字累积宽里存在一个 ≤ 它且
  // 下一档 > 它的断点（= 没有切半个字形）。
  const glyph = await page.evaluate((cssWpx) => {
    const chip = document.querySelector('.mf-strip-chip.on');
    const label = chip.querySelector('.mf-strip-label');
    const cs = getComputedStyle(label);
    const probeEl = document.createElement('span');
    probeEl.style.cssText = `position:absolute;left:-9999px;top:0;visibility:hidden;white-space:nowrap;font:${cs.font};letter-spacing:${cs.letterSpacing};word-spacing:${cs.wordSpacing};text-transform:${cs.textTransform}`;
    document.body.appendChild(probeEl);
    const graphemes = [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(label.textContent)].map((s) => s.segment);
    const widths = graphemes.map((_, i) => {
      probeEl.textContent = graphemes.slice(0, i + 1).join('');
      return +probeEl.getBoundingClientRect().width.toFixed(2);
    });
    probeEl.remove();
    const layoutW = cssWpx;                       // max-width 是布局宽（缩放前）
    let fit = 0;
    for (const w of widths) if (w <= layoutW + 0.02) fit++;
    return { n: graphemes.length, widths: widths.slice(0, 6), fit, nextW: widths[fit] ?? null, layoutW };
  }, cssW);
  expect(glyph.fit).toBeGreaterThanOrEqual(3);      // 至少收下 3 个整字（不是空签）
  expect(glyph.fit).toBeLessThan(glyph.n);          // 确实收了（全名放不下 ⇒ 才需要展开腿）
  if (glyph.nextW !== null) expect(glyph.nextW).toBeGreaterThan(cssW);  // 下一字放不下 ⇒ 收在字界
  // 无省略号
  expect(longOn.textOverflow).toBe('clip');
  expect(longOn.text.includes('\u2026')).toBe(false);
  expect(longOn.labelMask).toBe('none');
  // 展开腿：点活签 → 全名可见（label 绘制宽 ≥ 常态收束宽，不再被 max-width 收束）
  const narrowW = longOn.labelW;
  await page.locator('.mf-strip-chip.on').click();
  await page.waitForTimeout(460);
  const expP = await probe();
  const expOn = expP.chips.find((c) => c.on);
  expect(expOn.expanded).toBe(true);
  // 展开档同样走「整字收束」，但签本身宽了（渲染 187 ≥ 常态 112.9）⇒ 收束值放大，
  // 全名放得下时 label 绘制宽 = 文字自然宽（scrollWidth 是布局宽，换算回渲染宽）。
  expect(expOn.labelW).toBeGreaterThan(narrowW + 20);
  expect(expOn.labelW).toBeGreaterThanOrEqual(expOn.labelSW * expOn.rsc - 1);
  // 展开档全名一个不落（不裁、不加省略号）：要么根本没收束（内联 max-width 为空
  // = 全文放得下），要么收束值 ≥ 文字自然布局宽。两条互斥，取其一即可。
  const expCap = expOn.labelCssMaxW === '' ? Infinity : parseFloat(expOn.labelCssMaxW);
  expect(expCap).toBeGreaterThanOrEqual(expOn.labelSW - 0.5);
  expect(expOn.text).toBe(LONG);
  expect(expOn.labelMask).toBe('none');             // 展开档中签同样零渐隐
  await page.waitForTimeout(2700);                  // 2600ms 自动收
  const backP = await probe();
  expect(backP.chips.find((c) => c.on).expanded).toBe(false);

  // ── ⑤-b 极端长名（27 汉字，展开档也放不全）：仍守三条底线 —— 不溢出签盒、
  //    不切半个字（收在字界）、零省略号；全名保底留在 DOM 文本 + title。
  //    （「展开腿能显示全名」这一条以 11 汉字档为判据：那是展开档几何能容下的
  //     上限量级；更长的名字作者令未要求撑破浮钮钳制，批文档 §5 已申报。）
  const VERY_LONG = '7451 lugi 命名规约汇编与落盘纪律以及画板标签环的几何单源说明档';
  await page.evaluate(async (title) => {
    const c = await import('/js/canvas.js');
    c.openTab('t13-verylong', title, { type: 't13-verylong-type' });
  }, VERY_LONG);
  await page.waitForTimeout(320);
  await page.locator('.mf-strip-chip[data-tab-id="t13-verylong"]').click();
  await page.waitForTimeout(520);
  for (const stage of ['normal', 'expanded']) {
    const vp = await probe();
    const vOn = vp.chips.find((c) => c.on);
    expect(vOn.id).toBe('t13-verylong');
    expect(vOn.text).toBe(VERY_LONG);
    expect(vOn.text.includes('\u2026')).toBe(false);
    expect(vOn.textOverflow).toBe('clip');
    expect(vOn.labelMask).toBe('none');
    expect(vOn.labelRight).toBeLessThanOrEqual(vOn.x + vOn.w - 1);   // 不溢出签盒
    expect(vOn.labelCssMaxW).not.toBe('');                            // 有整字收束值
    expect(Math.abs(vOn.labelW - parseFloat(vOn.labelCssMaxW) * vOn.rsc)).toBeLessThanOrEqual(0.6);
    if (stage === 'normal') {
      await page.locator('.mf-strip-chip.on').click();
      await page.waitForTimeout(460);
    }
  }
  await page.waitForTimeout(2700);

  // 中签/次签尺寸比（报告要具体值）：2 签 127.7/43.4≈2.94×、3 签同、5/8 签同。
  console.log('T13 中签 vs 次签渲染尺寸比:', JSON.stringify(ratios));
  for (const r of ratios) expect(r.wRatio).toBeGreaterThan(1.8);
  // 中签两端斜坡带读数（报告要具体值）：应恒 ≤5 设备像素（mask=none 的像素证据）。
  console.log('T13 中签两端斜坡带（设备像素）:', JSON.stringify(ramps));
  console.log('T13 超长名整字收束:', JSON.stringify({ cssW, glyph, narrowW: +narrowW.toFixed(2), expW: +expOn.labelW.toFixed(2), sw: expOn.labelSW }));
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
  // 位置：内容区底部**浮层带**居中（2026-11-02 19:18 批：脱离页面流，与底部
  // 圆钮行共中线；「流内末行」几何断言随批退役 —— 不再要求在 host 盒之下）。
  const pos = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots').getBoundingClientRect();
    const dock = document.getElementById('mobile-dock').getBoundingClientRect();
    const host = document.querySelector('.mf-cv-host').getBoundingClientRect();
    return {
      cx: Math.round(d.x + d.width / 2),
      aboveDock: d.bottom <= dock.y + 0.5,
      inViewport: d.y > 0 && d.bottom < window.innerHeight,
      // 浮层证据：与 host 盒重叠（浮在文档区之上）且不超出 host 下缘太远
      // （仍在 canvas 页体内）。
      overlapsHost: d.bottom > host.top,
    };
  });
  expect(Math.abs(pos.cx - 187.5)).toBeLessThanOrEqual(2);   // 居中
  expect(pos.aboveDock).toBe(true);                          // 不压 dock
  expect(pos.inViewport).toBe(true);
  expect(pos.overlapsHost).toBe(true);                       // 浮层（文档区之上）
  expect(errors).toEqual([]);
});

test('T11 三点手势区（21:47 §二：去长按门槛）：横滑逐签 + 轻触跳点 + 上滑进堆叠 + 下滑关标签（零确认）', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['l1', '拖一', 'l1'], ['l2', '拖二', 'l2'], ['l3', '拖三', 'l3'], ['l4', '拖四', 'l4'], ['l5', '拖五', 'l5']]);
  expect(await activeChip(page)).toBe('l5');
  const events = () => page.evaluate(() => (/** @type {any} */ (window)).__nfMobileLayerEvents.map((e) => e.kind));
  const box = await page.locator('.mf-dots').boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  // ① 零等待响应：**全程不按任何长按时长**，横拖第一帧即进 is-grip（放大态）。
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 10, cy);      // 过 6px 轴锁即锁横轴
  await page.mouse.move(cx - 30, cy);
  const gripped = await page.evaluate(() => ({
    grip: document.querySelector('.mf-dots').classList.contains('is-grip'),
    scale: getComputedStyle(document.querySelector('.mf-dots')).transform,
  }));
  expect(gripped.grip).toBe(true);                  // 无 260ms 门槛：立刻是「抓住了」
  expect(gripped.scale).not.toBe('none');
  // ② 轴锁内（< 6px）：零换卡、零 grip —— 点按抖动不误触。
  for (let i = 1; i <= 3; i++) await page.mouse.move(cx + (5 * i) / 3, cy);
  expect(await activeChip(page)).toBe('l5');
  // ③ 横滑 52px（> 26px 阈）→ **一次滑 = 一张，环式**（语义单源见
  // navController bindDots「不在拖动中逐签换」：拖动中只维持 is-grip 临界
  // 反馈，松手按行程方向提交 1 张 —— 26px/步连换 2 签是旧实现残留）。
  // 判据时序随实现：拖动中 active 不变（l5），**松手后**才提交 —— 环式下一张
  // = wrap 到 l1（环式是文档区横滑的既定语义，见文件头判据块）。
  for (let i = 1; i <= 8; i++) await page.mouse.move(cx - 30 - (22 * i) / 8, cy);
  expect(await activeChip(page)).toBe('l5');        // 拖动中不换卡（一次滑=一张）
  await page.mouse.up();
  await page.waitForTimeout(200);
  expect(await activeChip(page)).toBe('l1');        // 松手提交：l5 → 环式下一张 l1
  const after = await page.evaluate(() => {
    const d = document.querySelector('.mf-dots');
    const dots = [...d.querySelectorAll('.mf-dot')];
    return { grip: d.classList.contains('is-grip'), onIdx: dots.findIndex((x) => x.classList.contains('on')) };
  });
  expect(after.grip).toBe(false);                   // 松手解除放大态
  // ④ 轻触（零位移）= 跳该点段代表签（非手势可点等价入口）。5 签 > 3 点：
  // 三段归一映射（dotIndex）—— 点1→段首 idx0(l1)、点2→idx2(l3)、点3→idx4(l5)。
  // 两跳（点2 → l3、点1 → l1）都换签，判据不因「已在 l1」而落空。
  const dot2 = await page.locator('.mf-dot').nth(1).boundingBox();
  await page.mouse.click(dot2.x + dot2.width / 2, dot2.y + dot2.height / 2);
  await page.waitForTimeout(200);
  expect(await activeChip(page)).toBe('l3');        // 轻触点 2（段首 idx2）→ l3
  const firstDot = await page.locator('.mf-dot').first().boundingBox();
  await page.mouse.click(firstDot.x + firstDot.width / 2, firstDot.y + firstDot.height / 2);
  await page.waitForTimeout(200);
  expect(await activeChip(page)).toBe('l1');        // 轻触点 1（段首 idx0）→ l1
  // ⑤ 上滑 → 进堆叠视角（日志 dots-swipe-up + 面可见）。
  await page.evaluate(() => { (/** @type {any} */ (window)).__nfMobileLayerEvents.length = 0; });
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx, cy - 12);
  await page.mouse.move(cx, cy - 40);      // 过 22px 阈
  await page.mouse.up();
  await expect(page.locator('.mobile-page[data-page="canvas"] .mf-stack-view')).toBeVisible();
  expect(await events()).toContain('dots-swipe-up');
  // ⑥ 收面回阅读（上滑互逆腿 = 舞台纵上滑），再把「下滑关标签」测在三点区上。
  await page.locator('.mf-stack-x').click();
  await expect(page.locator('.mobile-page[data-page="canvas"] .mf-stack-view')).toBeHidden();
  expect(await activeChip(page)).toBe('l1');
  const before = await page.evaluate(() => ({
    chips: document.querySelectorAll('.mf-strip-chip').length,
    // 零确认判据的基线：手势前**可见的**对话/确认面计数（关签后必须不增）。
    dialogs: [...document.querySelectorAll('[role="dialog"], [class*="confirm"], [class*="dialog"]')]
      .filter((el) => el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0).length,
  }));
  await page.evaluate(() => { (/** @type {any} */ (window)).__nfMobileLayerEvents.length = 0; });
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx, cy + 12);
  await page.mouse.move(cx, cy + 40);      // 过 22px 阈
  await page.mouse.up();
  // 零确认：一下手势即关签（无对话/无二次确认面、无取消路径）。
  await expect.poll(() => page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length)).toBe(before.chips - 1);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('l1'))).toBe(false);
  // 关的是**标签页**不是文档本体：其余四张签与画板其余文档全在。
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('l2'))).toBe(true);
  expect(await events()).toContain('dots-swipe-down');
  // 无确认面新增（零确认路径不弹任何模态 —— 与手势前同数）。
  const afterDialogs = await page.evaluate(() => [...document.querySelectorAll('[role="dialog"], [class*="confirm"], [class*="dialog"]')]
    .filter((el) => el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0).length);
  expect(afterDialogs).toBe(before.dialogs);
  // ⑦ reduced-motion 降级：动效压零但手势语义全保留。
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const rm = await page.evaluate(() => {
    const dots = document.querySelector('.mf-dots');
    return {
      dotsTransition: getComputedStyle(dots).transitionDuration,
      dotTransition: getComputedStyle(dots.querySelector('.mf-dot')).transitionDuration,
    };
  });
  expect(rm.dotsTransition).toBe('0s');
  expect(rm.dotTransition).toBe('0s');
  const b2 = await page.locator('.mf-dots').boundingBox();
  const cx2 = b2.x + b2.width / 2;
  const cy2 = b2.y + b2.height / 2;
  const preRm = await activeChip(page);
  await page.mouse.move(cx2, cy2);
  await page.mouse.down();
  await page.mouse.move(cx2 - 40, cy2);
  await page.mouse.up();
  await page.waitForTimeout(120);
  expect(await activeChip(page)).not.toBe(preRm);   // 换签照旧
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  expect(errors).toEqual([]);
});

test('T12 堆叠面 iOS app switcher 手势：横滑换中央卡 / 下滑关标签（零确认）/ 上滑收面 + 卡内 ✕ + Esc', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['p1', '页一', 'p1'], ['p2', '页二', 'p2'], ['p3', '页三', 'p3']]);
  expect(await activeChip(page)).toBe('p3');
  const pg = '.mobile-page[data-page="canvas"]';
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  const stage = await page.locator('.mf-deck').boundingBox();
  const scx = stage.x + stage.width / 2;
  const scy = stage.y + stage.height / 2;
  // ① 横滑（左拖 120px > 56px 阈）= 换中央卡（3 → 环绕回 1）；卡叠不退出。
  await drag(page, scx, scy, scx - 120, scy);
  await page.waitForTimeout(360);
  expect(await activeChip(page)).toBe('p1');
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  // ② 横滑右拖 → 上一张（1 → 环绕回 3）。
  await drag(page, scx, scy, scx + 120, scy);
  await page.waitForTimeout(360);
  expect(await activeChip(page)).toBe('p3');
  // ③ 上滑（> 48px）= 收面回阅读（与阅读面「上滑进堆叠」互逆成环）。
  await drag(page, scx, scy, scx, scy - 110);
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
  expect(await activeChip(page)).toBe('p3');          // 收面不动签
  // ④ 再开面 → 下滑（> 48px）= 关当前标签（零确认，作者 21:54 裁定）。
  //   等 400ms：松手后的 350ms click 抑制窗要整个过去，否则下一次拖的
  //   pointerdown 会被吞（拖后松手吞 click 的同窗也吞下一记手势起手）。
  await page.waitForTimeout(400);
  await page.locator(`${pg} .mf-cv-stack`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  await drag(page, scx, scy, scx, scy + 110);
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('p3'))).toBe(true);
  await expect(page.locator(`${pg} .mf-deck-card`)).toHaveCount(2);
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('2 张文档');
  // 关的是标签页不是文档本体：其余两张卡与其文档全在，面仍开着。
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeVisible();
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('p1'))).toBe(true);
  // ⑤ 卡内 ✕ 关卡（前景卡 = 活动签）：卡数递减。
  await page.locator(`${pg} .mf-deck-card.on .mf-deck-x`).click();
  await expect(page.locator(`${pg} .mf-deck-card`)).toHaveCount(1);
  await expect(page.locator(`${pg} .mf-stack-count`)).toHaveText('1 张文档');
  // ⑥ ✕ 退出回标签视图（环带复现，卡叠隐藏）。
  await page.locator(`${pg} .mf-stack-x`).click();
  await expect(page.locator(`${pg} .mf-stack-view`)).toBeHidden();
  await expect(page.locator(`${pg} .mf-strip`)).toBeVisible();
  // ⑦ Esc 同样收面（可点/可键等价入口）。
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
