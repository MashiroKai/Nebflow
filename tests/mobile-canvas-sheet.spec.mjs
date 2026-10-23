// mobile-canvas-sheet.spec.mjs — 画板 bottom sheet 交互验收（作者令 2026-10-23 ⑦）。
//
// 形态与手势（canvasGestures.js + mobileLayer.css 同批）：
//   · 呈现 …… 手机画板 = 从下方弹出的卡片（bottom sheet）：底缘锚定（safe 区上
//     6px）、顶缘留聊天头一线、入场自下滑入（mhSheetIn）、r20。
//   · 底缘带 …… 下滑跟手 → 释放越阈（≥120px 或快甩）= 收起整叠（closeCanvas，
//     保留状态不销毁 —— 页签数据模型保留，可再开）；未越阈回弹。
//   · 卡头胶囊（顶缘）…… 下滑 = 收起整叠（⑦ 令「顶部边缘下滑 = 收起」，取代
//     批⑨纵滑换卡的下半向；上滑换卡/左右关卡/长按引用钮照 UI 批⑨保留）。
//   · 左右缘带 …… 向内横滑 ≥64px = 关闭该文档卡（navController.closeCard 既有
//     邻卡落位）。
//   · 内部手势优先 …… 内容区零劫持：滚动/缩放原生（touch-action:pan-y），缘带
//     才触发收合/关闭。
//
// 手势模拟 = Playwright 指针事件拖拽（mouse 腿走同一 Pointer Events 管线；
// hasTouch 上下文 + 底缘带 touch-action:none 与真机触屏同路径）。数据面零后端。
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

test('T1 sheet presentation: bottom-anchored card, slide-up entry, radius 20', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const geo = await page.evaluate(() => {
    const el = document.getElementById('canvas-panel');
    const r = el.getBoundingClientRect();
    return {
      bottomGap: Math.round(window.innerHeight - r.bottom),
      top: Math.round(r.top),
      width: Math.round(r.width),
      radius: getComputedStyle(el).borderRadius,
      anim: getComputedStyle(el).animationName,
      sideZones: document.querySelectorAll('#canvas-panel .mobile-canvas-edgezone-side').length,
      bottomZone: !!document.querySelector('#canvas-panel .mobile-canvas-edgezone'),
    };
  });
  expect(geo.bottomGap).toBe(6); // 底缘锚定（safe 区上 6px；harness 无 inset → 6）
  expect(geo.top).toBe(76);      // 顶缘留聊天头一线
  expect(geo.width).toBe(375 - 16);
  expect(geo.radius).toBe('20px');
  expect(geo.anim).toBe('mhSheetIn');
  expect(geo.sideZones).toBe(2);
  expect(geo.bottomZone).toBe(true);
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

test('T3 side-edge swipe inward closes the card (last card → layer settles)', async ({ page }) => {
  const errors = watchErrors(page);
  await openDocCards(page, [['doc-1', '样例文档一']]);
  const box = await page.locator('#canvas-panel').boundingBox();
  // 右缘带内起步（右缘内 10px、缘带纵段），向内左滑 90px（> 64 阈）→ 关卡。
  await drag(page, box.x + box.width - 10, box.y + 300, box.x + box.width - 100, box.y + 300);
  await expect(page.locator('body')).not.toHaveClass(/mf-cards-live/);
  // closeTab 异步收尾 → 轮询等页签模型清算（同腿二）。
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-1')), { timeout: 4000 }).toBe(true);

  // 两张卡：左缘内滑关卡 —— 手势关的是**激活卡**（后开的卡乙），
  // closeCard 邻卡落位既有语义 → 落到卡甲。
  await openDocCards(page, [['doc-a', '卡甲', 'sheet-a'], ['doc-b', '卡乙', 'sheet-b']]);
  const box2 = await page.locator('#canvas-panel').boundingBox();
  await drag(page, box2.x + 10, box2.y + 300, box2.x + 110, box2.y + 300);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  await expect(page.locator('#canvas-panel .mobile-card-title')).toHaveText('卡甲');
  // closeTab 的映射清算是异步收尾（transitionend/200ms）→ 轮询等 gone。
  await expect.poll(async () => page.evaluate(async () => !(await import('/js/canvas.js')).hasTab('doc-b')), { timeout: 4000 }).toBe(true);
  expect(errors).toEqual([]);
});

test('T4 content area untouched: internal scroll works, sheet stays (internal-gesture priority)', async ({ page }) => {
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
  // 内容区横向拖动也不触发关卡（缘带才触发 —— 内滑只认左右缘带起步）。
  await drag(page, 100, 400, 260, 400);
  await expect(page.locator('body')).toHaveClass(/mf-cards-live/);
  expect(await page.evaluate(async () => (await import('/js/canvas.js')).hasTab('doc-1'))).toBe(true);
  expect(errors).toEqual([]);
});
