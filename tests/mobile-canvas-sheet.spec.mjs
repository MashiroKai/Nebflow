// mobile-canvas-sheet.spec.mjs — 画板 bottom sheet 交互验收（作者令 2026-10-23 ⑦ →
// 2026-10-27「手机端 Canvas 交互优化」令改写：横滑语义统一 = 切卡）。
//
// 形态与手势（canvasGestures.js + navController.js + mobileLayer.css 同批）：
//   · 呈现 …… 手机画板 = 从下方弹出的卡片（bottom sheet）：底缘锚定（safe 区上
//     6px）、顶缘留聊天头一线、入场自下滑入（mhSheetIn）、r20。
//   · 底缘带 …… 下滑跟手 → 释放越阈（≥120px 或快甩）= 收起整叠（closeCanvas，
//     保留状态不销毁 —— 页签数据模型保留，可再开）；未越阈回弹。
//   · 卡头胶囊（顶缘）…… 下滑 = 收起整叠；横滑 = 切上一/下一张（2026-10-27 令：
//     横滑语义统一 = 切卡，取代⑦令「左右滑关卡」与「上滑换卡」；关卡收敛到
//     卡头显性 X）；长按引用钮照 UI 批⑨保留。
//   · 内容区横滑 …… 左滑 = 下一张 / 右滑 = 上一张（轴锁 10px、阈 56px 或快甩；
//     真横滚器让位原生；纵滚零劫持 touch-action:pan-y）。
//   · 底部快捷切换条 …… [‹] [页点(tap 跳卡)] [›] ｜ [收起]；‹ ›/页点/收起三通路
//     全可点；单卡时 ‹ › 禁用、页点单枚。
//   · 关卡 …… 只走卡头 X（44px 命中盒；邻卡落位）；横滑永不关卡。
//   · 热区 …… 卡头 X / 切换条四钮命中盒 ≥44px（视觉圆 30px padding-box 解耦）。
//   · 桌面遗留面 …… .canvas-title / #canvas-pop-btn 手机层隐藏（双标题/双钮退役）。
//
// 手势模拟 = Playwright 指针事件拖拽（mouse 腿走同一 Pointer Events 管线；
// hasTouch 上下文 + 缘带 touch-action:none 与真机触屏同路径）。数据面零后端。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-canvas-sheet.spec.mjs

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

/** 开一张文档卡（canvas.js openTab 通用文档 pane → 卡片层亮起）。
 *  注①：openTab 的预览组替换按 type 键（previewKeyOf）—— 多卡场景每卡给独立
 *  type，避免同组预览替换把前一张卡顶掉。
 *  注②：layoutController 的启动自动展开抑制（watchBootAutoOpen）会把「首触前」
 *  打开的画板收掉 —— 真实路径 = 用户点键（touchLayer 先置位），故此处同缝先
 *  touchLayer 再开卡。 */
async function openDocCards(page, ids) {
  await page.evaluate((list) => {
    Promise.all([
      import('/js/canvas.js'),
      import('/js/mobile/navController.js'),
    ]).then(([canvas, nav]) => {
      nav.touchLayer();
      for (const [id, title, type] of list) canvas.openTab(id, title, { type: type || 'generic' });
    });
  }, ids);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  await page.waitForTimeout(500); // mhSheetIn 入场终态
}

/** 指针拖拽（Pointer Events 管线；触屏同路径 —— 缘带 touch-action:none）。 */
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

test('T1 sheet presentation: bottom-anchored card, slide-up entry, radius 20, switchbar ready', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const geo = await page.evaluate(() => {
    const el = document.getElementById('canvas-panel');
    const r = el.getBoundingClientRect();
    const bar = el.querySelector('.mobile-card-switchbar');
    const barR = bar ? bar.getBoundingClientRect() : null;
    const close = el.querySelector('.mobile-card-close');
    const closeR = close ? close.getBoundingClientRect() : null;
    const edge = el.querySelector('.mobile-canvas-edgezone');
    return {
      bottomGap: Math.round(window.innerHeight - r.bottom),
      top: Math.round(r.top),
      width: Math.round(r.width),
      radius: getComputedStyle(el).borderRadius,
      anim: getComputedStyle(el).animationName,
      sideZones: document.querySelectorAll('#canvas-panel .mobile-canvas-edgezone-side').length, // 2026-10-27 令退役
      bottomZone: !!edge,
      bar: !!bar,
      barBtns: bar ? bar.querySelectorAll('.sw-btn').length : 0,
      barDots: bar ? bar.querySelectorAll('.sw-dot').length : 0,
      barDisabled: bar ? bar.querySelector('.sw-prev').disabled : null,
      barBottomGap: barR ? Math.round(window.innerHeight - barR.bottom) : null,
      closeBox: closeR ? { w: Math.round(closeR.width), h: Math.round(closeR.height) } : null,
      desktopTitleHidden: (() => {
        const t = el.querySelector('.canvas-header .canvas-title');
        return !t || getComputedStyle(t).display === 'none';
      })(),
    };
  });
  expect(geo.bottomGap).toBe(6); // 底缘锚定（safe 区上 6px；harness 无 inset → 6）
  expect(geo.top).toBe(76);      // 顶缘留聊天头一线
  expect(geo.width).toBe(375 - 16);
  expect(geo.radius).toBe('20px');
  expect(geo.anim).toBe('mhSheetIn');
  expect(geo.sideZones).toBe(0);         // 隐形侧缘带退役（横滑语义统一 = 切卡）
  expect(geo.bottomZone).toBe(true);
  expect(geo.bar).toBe(true);            // 底部快捷切换条常驻
  expect(geo.barBtns).toBe(3);           // ‹ › ｜ 收起
  expect(geo.barDots).toBe(1);           // 单卡单页点
  expect(geo.barDisabled).toBe(true);    // 单卡 ‹ › 禁用
  expect(geo.barBottomGap).toBeGreaterThanOrEqual(40); // 坐落底缘带之上
  expect(geo.closeBox.w).toBe(44);       // 卡头 X 命中盒 44×44
  expect(geo.closeBox.h).toBe(44);
  expect(geo.desktopTitleHidden).toBe(true); // 双标题退役
  expect(errors).toEqual([]);
});

test('T2 bottom-edge drag down collapses the sheet; state kept (reopen restores docs)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const box = await page.locator('#canvas-panel').boundingBox();
  // 底缘带内起步（sheet 底缘上方 10px），下滑 170px（> 120 阈）→ 收起。
  await drag(page, box.x + box.width / 2, box.y + box.height - 10, box.x + box.width / 2, box.y + box.height + 160);
  await expect(page.locator('body')).not.toHaveClass(/mf-cards-live/);
  // 保留状态不销毁：页签数据模型仍在（closeCanvas 语义）。
  const kept = await page.evaluate(async () => {
    const m = await import('/js/canvas.js');
    return m.hasTab('doc-1') && !m.isCanvasOpen();
  });
  expect(kept).toBe(true);

  // 再开（画板 bar 键同路径 toggleCards）→ 卡片层复现，文档还在。
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.toggleCards();
  });
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('样例文档一');
  expect(errors).toEqual([]);
});

test('T2b header pill (top edge) drag down collapses; small drag springs back', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const head = await page.locator('#canvas-panel .canvas-header').boundingBox();
  // 顶缘（卡头胶囊）下滑 80px（> 48 阈）→ 收起整叠。
  await drag(page, head.x + head.width / 2, head.y + head.height / 2, head.x + head.width / 2, head.y + head.height / 2 + 80);
  await expect(page.locator('body')).not.toHaveClass(/mf-cards-live/);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-1'))).toBe(true);

  // 未越阈回弹腿：重开后小幅下滑 24px（< 48 阈）→ 层不收。
  await page.evaluate(async () => { (await import('/js/mobile/navController.js')).toggleCards(); });
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  await page.waitForTimeout(500);
  const head2 = await page.locator('#canvas-panel .canvas-header').boundingBox();
  await drag(page, head2.x + head2.width / 2, head2.y + head2.height / 2, head2.x + head2.width / 2, head2.y + head2.height / 2 + 24);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  expect(errors).toEqual([]);
});

test('T2c switchbar collapse button closes the sheet (explicit entry, state kept)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  await page.locator('#canvas-panel .mobile-card-switchbar .sw-collapse').click();
  await expect(page.locator('body')).not.toHaveClass(/mf-cards-live/);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-1'))).toBe(true);
  // 再开复现。
  await page.evaluate(async () => { (await import('/js/mobile/navController.js')).toggleCards(); });
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  expect(errors).toEqual([]);
});

test('T3 content horizontal swipe switches cards (left=next, right=prev); swipe never closes', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'sheet-a'], ['doc-b', '卡乙', 'sheet-b']]);
  // 初态 = 后开的卡乙（openTab 激活新卡）；环序 = docTabs() 卡片层序 [甲, 乙]。
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡乙');
  const box = await page.locator('#canvas-panel').boundingBox();
  const midY = box.y + box.height / 2;
  // 内容区左滑 90px（> 56 阈）→ 下一张（乙 → 环绕回甲）。
  await drag(page, box.x + box.width - 40, midY, box.x + box.width - 130, midY);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  // 关卡退役断言：横滑后两卡都还在（横滑永不关卡）。
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-a'))).toBe(true);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-b'))).toBe(true);
  // 页点同步：第 1 点点亮（甲=环首）。
  const dots = await page.evaluate(() => {
    const els = [...document.querySelectorAll('#canvas-panel .sw-dot')];
    return { n: els.length, onIdx: els.findIndex((el) => el.classList.contains('on')) };
  });
  expect(dots.n).toBe(2);
  expect(dots.onIdx).toBe(0);
  // 内容区右滑 90px → 上一张（甲 → 环绕回乙）。
  await drag(page, box.x + 40, midY, box.x + 130, midY);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡乙');
  expect(errors).toEqual([]);
});

test('T3b header pill horizontal swipe switches card (close-on-swipe retired)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'sheet-a'], ['doc-b', '卡乙', 'sheet-b']]);
  const head = await page.locator('#canvas-panel .canvas-header').boundingBox();
  // 卡头横滑 90px（> 48 阈）→ 切下一张（2026-10-27 令；旧语义 = 关卡，退役）。
  await drag(page, head.x + head.width / 2, head.y + head.height / 2, head.x + head.width / 2 - 90, head.y + head.height / 2);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-a'))).toBe(true);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-b'))).toBe(true);
  expect(errors).toEqual([]);
});

test('T3c switchbar prev/next buttons and dot tap switch cards', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'sheet-a'], ['doc-b', '卡乙', 'sheet-b']]);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡乙');
  // 「下一张 ›」→ 环绕回卡甲。
  await page.locator('#canvas-panel .mobile-card-switchbar .sw-next').click();
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  // 「‹ 上一张」→ 回卡乙。
  await page.locator('#canvas-panel .mobile-card-switchbar .sw-prev').click();
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡乙');
  // 页点 tap → 跳第 1 张（卡甲）。
  await page.locator('#canvas-panel .sw-dot').nth(0).click();
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  expect(errors).toEqual([]);
});

test('T3d X button closes the active card (explicit entry; neighbor settles; last card → layer settles)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-a', '卡甲', 'sheet-a'], ['doc-b', '卡乙', 'sheet-b']]);
  // X 关激活卡（卡乙）→ 落邻卡卡甲。
  await page.locator('#canvas-panel .mobile-card-close').click();
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-b')), { timeout: 4000 }).toBe(true);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  // 末卡关后收层。
  await page.locator('#canvas-panel .mobile-card-close').click();
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-a')), { timeout: 4000 }).toBe(true);
  await expect(page.locator('body')).not.toHaveClass(/mf-cards-live/);
  expect(errors).toEqual([]);
});

test('T4 content area vertical scroll untouched; horizontal swipe on single card is a no-op (internal-gesture priority)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  // 灌高内容 → 滚动发生在 pane 内部滚动器（真实文档卡构造：#canvas-content /
  // .canvas-tab-pane 恒 overflow:hidden，滚的是 pane 内滚），sheet 不收不起。
  await page.evaluate(() => {
    const pane = document.querySelector('#canvas-content .canvas-tab-pane[data-tab-id="doc-1"]');
    pane.innerHTML = '<div class="t4-scroll" style="height:100%;overflow-y:auto"><div style="height:2400px">scroll-body</div></div>';
  });
  const before = await page.evaluate(() => document.querySelector('#canvas-content .t4-scroll').scrollTop);
  await page.mouse.move(187, 400);
  await page.mouse.wheel(0, 300);
  await page.waitForTimeout(200);
  const after = await page.evaluate(() => document.querySelector('#canvas-content .t4-scroll').scrollTop);
  expect(after).toBeGreaterThan(before);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  // 单卡横滑 = no-op（无可切邻卡；也绝不关卡）。
  const box = await page.locator('#canvas-panel').boundingBox();
  await drag(page, 100, 400, 260, 400);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-1'))).toBe(true);
  expect(errors).toEqual([]);
});

test('T5 hit zones: header X / pop and switchbar buttons ≥44px (visual circle stays compact)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const boxes = await page.evaluate(() => {
    const box = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height) };
    };
    return {
      close: box('#canvas-panel .mobile-card-close'),
      pop: box('#canvas-panel .mobile-card-pop'),
      prev: box('#canvas-panel .sw-prev'),
      next: box('#canvas-panel .sw-next'),
      collapse: box('#canvas-panel .sw-collapse'),
      dotsRow: box('#canvas-panel .sw-dots'),
      // 视觉圆 = padding-box（44 - 2×7 透明边 = 30px）：热区/视觉解耦的物证。
      closeClip: getComputedStyle(document.querySelector('#canvas-panel .mobile-card-close')).backgroundClip,
    };
  });
  for (const [k, b] of Object.entries(boxes)) {
    if (k === 'closeClip' || k === 'pop') continue; // pop 仅文件卡可见（generic 卡隐藏）
    if (!b) throw new Error(`missing element for ${k}`);
    expect(b.w, k).toBeGreaterThanOrEqual(44);
    expect(b.h, k).toBeGreaterThanOrEqual(44);
  }
  // pop 可见时（文件卡）同为 44 盒 —— 用文件卡腿单独验。
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    c.openTab('doc-file', '说明.md', { type: 'file' });
  });
  await page.waitForTimeout(400);
  const popBox = await page.evaluate(() => {
    const el = document.querySelector('#canvas-panel .mobile-card-pop');
    if (!el || el.hidden) return null;
    const r = el.getBoundingClientRect();
    return { w: Math.round(r.width), h: Math.round(r.height) };
  });
  if (popBox) {
    expect(popBox.w).toBeGreaterThanOrEqual(44);
    expect(popBox.h).toBeGreaterThanOrEqual(44);
  }
  expect(boxes.closeClip).toBe('padding-box');
  expect(errors).toEqual([]);
});
