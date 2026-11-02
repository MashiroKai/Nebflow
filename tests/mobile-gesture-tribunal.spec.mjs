// mobile-gesture-tribunal.spec.mjs — 手机端手势三件批验收（作者令 2026-11-01 14:09）。
//
// 三件：
//   ① 图谱拖动去显式拉手 —— 手机端知识图谱触摸即拖/平移（touch-action 收归指针
//      管线，pointercancel 病根拔除），双指捏合缩放（同一 damped zoomTarget 管线）；
//      **无任何 pan-mode/拉手式显式开关**（防复活钉）。
//   ② 手势仲裁（内容操作优先、误切率趋零）—— 内容手势面（.canvas-image-viewport
//      图片拖动/调尺寸、.wiki-canvas 图谱、.fm-viewport 流程图、
//      [data-nf-content-gesture] 逃逸口）起手的指针序列归内容操作：切卡手势零
//      接管、零 pointer capture 抢占；双指 = pinch 即解除已武装切卡；普通内容区
//      横滑切卡保留（回归钉）。
//   ③ 次级面板边缘返回 —— 一切详情页（stack.length > 1）左缘 20px 右滑 = iOS
//      同款跟手返回（跟手/阻尼/过阈 pop/不过阈弹簧回位）；dock 根页与聊天根
//      一级面不认领；按钮面与纵滑零劫持。
//
// 双引擎：默认 Chromium + WebKit 各跑一轮（ENGINE=chromium|webkit 可单跑）。
// 触摸序列 = Chromium CDP Input.dispatchTouchEvent（真触屏管线，touch-action
// 生效）；WebKit 腿走 mouse 拖（同一 Pointer Events 管线，mobile-canvas-sheet
// 批既有口径）+ 计算样式/静态钉。视口 390×844，hasTouch。
//
// 运行：node tests/mobile-gesture-tribunal.spec.mjs              # 双引擎
//      ENGINE=webkit node tests/mobile-gesture-tribunal.spec.mjs # 仅 WebKit
// 可选 env：PORT=8332、SHOTS_DIR=<dir>、HEADLESS=0

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8332);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');
const SID = 'tribunal-root-session';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 静态服务（真 web/ 树） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  // /api/* 夹具兜底：WebKit 偶发 page.route 拦截缺口（fetch 泄漏到网络层直接
  // 打到本服务）—— 服务端同形态应答，泄漏与否都零 404（零 console 的前提）。
  if (p.startsWith('/api/')) {
    const json = p === '/api/sessions'
      ? { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }
      : p === '/api/wiki/graph' ? graphFixture() : {};
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(json));
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    if (process.env.DEBUG_404) console.error(`[404] ${req.url}`);
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 夹具 ─────────────── */
function graphFixture() {
  return {
    projects: [{ name: 'nebflow 主仓' }, { name: '远端巡检' }],
    nodes: [
      { title: '架构总览', rel: 'docs/架构总览.md', project: 'nebflow 主仓', kind: 'doc', status: 'done' },
      { title: '巡检手册', rel: 'docs/巡检手册.md', project: '远端巡检', kind: 'doc', status: '' },
      { title: '待写草稿', rel: 'docs/待写草稿.md', project: 'nebflow 主仓', kind: 'doc', status: '', stub: true },
    ],
    edges: [
      { from: '架构总览', to: '巡检手册' },
      { from: '架构总览', to: '待写草稿' },
    ],
  };
}
const IMG_DATA = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

/* ─────────────── 应用引导（手机层 390×844 hasTouch） ─────────────── */
async function boot(browser) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(9000);
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`); });
  await page.route('**/api/**', (r) => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    if (p === '/api/sessions') {
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p === '/api/wiki/graph') return r.fulfill({ json: graphFixture() });
    return r.fulfill({ json: {} });
  });
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      // 图谱工作台行随 wikiGraphEnabled 门控 —— 夹具携 flag（graphseal 通道）。
      type: 'configData', config: '{"features":{"wikiGraph":true}}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(400);
  return { ctx, page, errors };
}

/* ─────────────── 通用腿 ─────────────── */
async function dockTo(page, id) {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await sleep(250);
}
/** 工作台行 → 详情页（真实 UI 路径；graph 行随 flag 门控）。 */
async function openHubPage(page, face) {
  await dockTo(page, 'workbench');
  await page.locator(`.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${face}"]`).click();
  await page.waitForFunction((pid) => {
    const el = document.querySelector(`.mobile-page[data-page="${pid}"]`);
    return !!el && el.classList.contains('live');
  }, face);
  await sleep(250);
}
/** 图谱页就绪（pane 收编 + 画布非零高）。 */
async function waitGraphReady(page) {
  await page.waitForSelector('.mobile-page[data-page="graph"] .wiki-graph-pane .wiki-canvas', { timeout: 9000 });
  await page.waitForFunction(() => {
    const c = document.querySelector('.mobile-page[data-page="graph"] .wiki-canvas');
    return !!c && c.getBoundingClientRect().height > 300;
  });
  // 力导向布局静息（停帧后拖动读数只反映平移）。
  await page.waitForFunction(() => {
    const p = (/** @type {any} */ (window)).__wikiGraphProbe?.();
    return p && p.alpha < 0.05;
  }, undefined, { timeout: 9000 });
}
/** 开图片文档卡 + 一张普通卡，dock 进 canvas 页（探针腿复用 probe2 的真实链）。 */
async function openImageCards(page) {
  await page.evaluate(async (img) => {
    const canvas = await import('/js/canvas.js');
    canvas.openTab('tri-img', 'probe.png', { type: 'tri-img-type', absPath: '/tmp/probe.png' });
    canvas.openTab('tri-doc', 'probe.txt', { type: 'tri-doc-type' });
    canvas.setActiveTab('tri-img');
    const pane = canvas.getTabPane('tri-img');
    const fv = await import('/js/fileViewers.js');
    await fv.renderFile(pane, { itemType: 'image', objectUrl: img, fileName: 'probe.png', size: 100 });
  }, IMG_DATA);
  await dockTo(page, 'canvas');
  // 收编腿：dock 进 canvas 页后 syncCanvasPage 才把活动卡 pane re-parent 进
  // mf-cv-host（手机层 #canvas-panel 恒隐 —— 收编前 img 读 hidden 是常态）。
  await page.waitForSelector('.mf-cv-host .canvas-tab-pane[data-tab-id="tri-img"] .canvas-image-viewport img', { timeout: 9000 });
  await sleep(300);
}
const tapHandToggle = (page) => page.locator('.canvas-pan-toggle').first().click();
const activeChipId = (page) => page.evaluate(() => document.querySelector('.mf-strip-chip.on')?.dataset.tabId || null);
const navTop = (page) => page.evaluate(() => (/** @type {any} */ (window)).__nfMobileNav?.top || null);
const clearEvents = (page) => page.evaluate(() => { (/** @type {any} */ (window)).__nfMobileLayerEvents.length = 0; });
const readEvents = (page) => page.evaluate(() => (/** @type {any} */ (window)).__nfMobileLayerEvents.map((e) => e.kind));

/** 引擎判定（ENGINES 数组元素级判定；'chromium+webkit' 拆分后的单元素）。 */
const isTouchEngine = (e) => e === 'chromium';

/* ─────────────── 触摸序列（Chromium CDP 真触屏；WebKit mouse 腿） ─────────────── */
// 单 CDP session 纪律（bisect 实证）：拖（A）→ pinch（B）→ 拖（A）的跨 session
// 触摸序列会让 session A 的注入死掉（pointerdown 零派发）；全程共用一个
// session 则全序列稳定 —— touch 序列一律走调用方传入的同一个 client。
async function makeDrag(page, ctx, engine, client) {
  if (engine === 'chromium') {
    /** CDP 真触屏拖（touchStart→touchMove×n→touchEnd；pointerType=touch）。
     *  触点显式 id=10：无 id 触点在「先无 id 拖 → 带 id 双指 pinch」序列后会被
     *  CDP 触摸引擎当陈旧点吞掉（pointerdown 不派发 = 手势假死），id 一致化
     *  后全序列稳定（bisect 实证）。 */
    return async (x0, y0, x1, y1, steps = 10, holdMs = 16) => {
      await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x0, y: y0, id: 10 }] });
      for (let i = 1; i <= steps; i++) {
        await client.send('Input.dispatchTouchEvent', {
          type: 'touchMove',
          touchPoints: [{ x: x0 + ((x1 - x0) * i) / steps, y: y0 + ((y1 - y0) * i) / steps, id: 10 }],
        });
        await sleep(holdMs);
      }
      await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await sleep(120);
    };
  }
  /** WebKit：mouse 腿（同一 Pointer Events 管线；mobile-canvas-sheet 批口径）。 */
  return async (x0, y0, x1, y1, steps = 10, holdMs = 16) => {
    await page.mouse.move(x0, y0);
    await page.mouse.down();
    for (let i = 1; i <= steps; i++) {
      await page.mouse.move(x0 + ((x1 - x0) * i) / steps, y0 + ((y1 - y0) * i) / steps);
      await sleep(holdMs);
    }
    await page.mouse.up();
    await sleep(120);
  };
}

/** CDP 双指捏合（chromium 专属；dist0→dist1，中点 cx,cy）。 */
async function touchPinch(page, client, cx, cy, dist0, dist1, steps = 10) {
  const pt = (d) => [
    { x: cx - d / 2, y: cy, id: 1 },
    { x: cx + d / 2, y: cy, id: 2 },
  ];
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: pt(dist0) });
  for (let i = 1; i <= steps; i++) {
    await client.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: pt(dist0 + ((dist1 - dist0) * i) / steps),
    });
    await sleep(16);
  }
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await sleep(200);
}

/** 装点取消监听（pointercancel 计数 = 浏览器手势接管信号，修前必 >0）。 */
const armCancelProbe = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return false;
  (/** @type {any} */ (window)).__triCancels = 0;
  el.addEventListener('pointercancel', () => { (/** @type {any} */ (window)).__triCancels++; });
  return true;
}, sel);
const readCancels = (page) => page.evaluate(() => (/** @type {any} */ (window)).__triCancels || 0);

/** 图谱画布上远离一切节点与 panebar 的起手点（世界→屏幕换算与 draw() 同源）。
 *  移动页 panebar（vault 切换/搜索/chips/stats，pointer-events:auto 子件）叠在
 *  画布顶带 —— 顶带扫描的 elementFromPoint 与真实输入管线在叠层上会不一致
 *  （实测：扫描读 canvas、真触摸命中 .wg-chip），**从底部带向上扫**（panebar
 *  永不覆盖）+ 节点距离 >56px + elementFromPoint 双门。 */
async function findPanPoint(page) {
  return page.evaluate(() => {
    const p = (/** @type {any} */ (window)).__wikiGraphProbe?.();
    const c = document.querySelector('.mobile-page[data-page="graph"] .wiki-canvas');
    if (!p || !c) return null;
    const r = c.getBoundingClientRect();
    const toScreen = (wx, wy) => ({
      x: (wx - p.W / 2) * p.scale + p.W / 2 + p.vx,
      y: (wy - p.H / 2) * p.scale + p.H / 2 + p.vy,
    });
    const pts = p.nodes.map((n) => toScreen(n.x, n.y));
    for (let gy = r.height - 40; gy > 200; gy -= 24) {
      for (let gx = 40; gx < r.width - 60; gx += 24) {
        const hit = document.elementFromPoint(r.x + gx, r.y + gy);
        if (hit !== c) continue;
        if (pts.every((q) => Math.hypot(q.x - gx, q.y - gy) > 56)) {
          return { x: r.x + gx, y: r.y + gy };
        }
      }
    }
    return { x: r.x + 40, y: r.y + r.height - 40 };
  });
}

/** 左缘带内非交互起手 y（避开按钮/输入面 —— 控件面起手归控件，仲裁契约）。 */
async function findEdgeStartY(page) {
  return page.evaluate(() => {
    for (let y = 260; y <= 760; y += 16) {
      const el = document.elementFromPoint(6, y);
      if (el && !el.closest('button, input, textarea, select, a')) return y;
    }
    return 400;
  });
}

/** 阻尼缩放收敛等待（rAF 25% 追帧；|scale−zoomTarget|<eps 才算停）。T1f 的
 *  「拖动不改缩放」断言必须在收敛后快照 —— 否则 pinch 的阻尼尾帧漂移会被
 *  误记成单指拖改了 scale（实测竞态：200ms 尾帧 Δscale 可 >0.05）。 */
async function waitGraphSettled(page, eps = 0.004, timeoutMs = 3000) {
  const t0 = Date.now();
  for (;;) {
    const st = await page.evaluate(() => {
      const p = (/** @type {any} */ (window)).__wikiGraphProbe?.();
      return p ? Math.abs(p.scale - p.zoomTarget) : -1;
    });
    if (st < 0 || st < eps || Date.now() - t0 > timeoutMs) return;
    await sleep(60);
  }
}

/* ─────────────── 结果收集 ─────────────── */
const report = [];
let passTotal = 0, failTotal = 0;
function ok(engine, name, cond, detail = '') {
  const line = `  [${engine}] ${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`;
  report.push(line);
  if (cond) passTotal++; else failTotal++;
}
function info(engine, line) { report.push(`        [${engine}] ${line}`); }
const shot = async (page, engine, name) => {
  if (!SHOTS_DIR) return;
  await page.screenshot({ path: join(SHOTS_DIR, `${engine}-${name}.png`) });
};

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
for (const ENGINE of ENGINES) {
  const browser = await (ENGINE === 'webkit' ? webkit : chromium).launch({ headless: process.env.HEADLESS !== '0' });
  try {
    const { ctx, page, errors } = await boot(browser);
    // 触摸腿统一 CDP session（chromium；单 session 纪律见 makeDrag 头注）。
    const touchClient = isTouchEngine(ENGINE) ? await ctx.newCDPSession(page) : null;
    const drag = await makeDrag(page, ctx, ENGINE, touchClient);
    const isTouch = isTouchEngine(ENGINE);

    // ════ T1 图谱触摸即拖（无拉手钮）════
    await openHubPage(page, 'graph');
    await waitGraphReady(page);
    ok(ENGINE, 'T1a 图谱画布 touch-action:none（指针管线收归）',
      await page.evaluate(() => getComputedStyle(document.querySelector('.mobile-page[data-page="graph"] .wiki-canvas')).touchAction) === 'none');
    // 防复活钉：页面内零 pan-mode/拉手式开关（wg-chips 过滤片除外）。
    const panBtns = await page.evaluate(() => {
      const pane = document.querySelector('.mobile-page[data-page="graph"] .wiki-graph-pane');
      return pane ? [...pane.querySelectorAll('button')]
        .filter((b) => /pan|hand|拖动|拉手|抓手|平移/i.test(`${b.className} ${b.title} ${b.getAttribute('aria-label') || ''}`))
        .length : -1;
    });
    ok(ENGINE, 'T1b 图谱页无任何 pan-mode/拉手钮（显式开关不存在=不存在态钉死）', panBtns === 0, `命中 ${panBtns}`);
    const probeBefore = await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.());
    await armCancelProbe(page, '.mobile-page[data-page="graph"] .wiki-canvas');
    const box = await page.locator('.mobile-page[data-page="graph"] .wiki-canvas').boundingBox();
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await drag(cx - 30, cy, cx + 120, cy, 12);   // 单指横拖 150px
    const probeAfter = await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.());
    const vx = probeAfter ? probeAfter.vx - probeBefore.vx : 0;
    ok(ENGINE, `T1c 图谱触摸拖动 = 平移生效（vx Δ=${Math.round(vx)}，触摸即拖无模式切换）`,
      Math.abs(vx) > 60, isTouch ? 'CDP 真触屏序列' : 'mouse 腿（同一 Pointer Events 管线）');
    if (isTouch) ok(ENGINE, 'T1d 触屏拖动零 pointercancel（浏览器手势接管病根拔除）', (await readCancels(page)) === 0,
      `cancels=${await readCancels(page)}`);
    await shot(page, ENGINE, 't1-graph-drag');

    // ════ T1e 图谱双指捏合缩放（chromium 真触屏腿）════
    if (isTouch) {
      const s0 = (await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.())).scale;
      await touchPinch(page, touchClient, cx, cy, 120, 260);   // 外捏放大（同一 touchClient）
      await waitGraphSettled(page);   // 阻尼追帧收敛后再快照（T1f 竞态修除）
      const s1 = (await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.())).scale;
      ok(ENGINE, 'T1e 图谱双指捏合 = 缩放生效（同一 damped zoomTarget 管线）', s1 > s0 * 1.35, `scale ${s0.toFixed(2)}→${s1.toFixed(2)}`);
      // 收指后单指拖仍平移（手势语义零串线）—— 起手点远离节点（pinch 后节点
      // 铺满中带，中心起手会拖节点而非平移）。
      const panPt = await findPanPoint(page);
      const vxBefore = (await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.())).vx;
      await drag(panPt.x, panPt.y, panPt.x + 90, panPt.y, 8);
      const s2 = (await page.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.()));
      ok(ENGINE, 'T1f pinch 收指后单指拖仍平移（手势语义零串线）',
        Math.abs(s2.vx - vxBefore) > 30 && Math.abs(s2.scale - s1) < 0.05,
        `panPt=(${panPt.x},${panPt.y}) vx Δ=${Math.round(s2.vx - vxBefore)} scale ${s1.toFixed(2)}→${s2.scale.toFixed(2)}`);
    }

    // ════ T2 内容操作仲裁（图片卡拖动/双指调尺寸 vs 切卡）════
    await openImageCards(page);
    await tapHandToggle(page);   // 抓手开（冷启动批契约保留；内容操作=拖图）
    await sleep(150);
    const vp = await page.locator('.mf-cv-host .canvas-image-viewport').boundingBox();
    const vx0 = vp.x + vp.width / 2, vy0 = vp.y + vp.height / 2;
    const imgBefore = await page.evaluate(() => document.querySelector('.mf-cv-host .canvas-image-viewport img')?.style.transform || '');
    await clearEvents(page);
    await drag(vx0 - 20, vy0, vx0 + 130, vy0, 12);   // 图片面横拖（修前必切卡）
    const chipAfterImgDrag = await activeChipId(page);
    const ev1 = await readEvents(page);
    const imgAfter = await page.evaluate(() => document.querySelector('.mf-cv-host .canvas-image-viewport img')?.style.transform || '');
    const panningStuck = await page.evaluate(() => document.querySelector('.mf-cv-host .canvas-image-viewport')?.classList.contains('panning') || false);
    ok(ENGINE, 'T2a 图片面横拖不切卡（内容操作优先；修前此处 card-switch 误发）',
      chipAfterImgDrag === 'tri-img' && !ev1.includes('card-switch'), `chip=${chipAfterImgDrag} events=${JSON.stringify(ev1)}`);
    ok(ENGINE, 'T2b 图片随拖平移（拖图全链生效，capture 抢占病根拔除）', imgAfter !== imgBefore,
      `tx ${imgBefore.slice(0, 24)}… → ${imgAfter.slice(0, 24)}…`);
    ok(ENGINE, 'T2c 松手后 .panning 态零滞留（capture 收尾泄漏修除）', !panningStuck);

    // 双指 = 调尺寸手势：切卡解除（chromium 真双指；webkit 单指腿已覆盖主路径）
    if (isTouch) {
      await clearEvents(page);
      await touchPinch(page, touchClient, vx0, vy0, 140, 60);   // 内捏 = 调小（同一 touchClient）
      const ev2 = await readEvents(page);
      ok(ENGINE, 'T2d 双指调尺寸零切卡（pinch 即解除切卡手势）', !ev2.includes('card-switch'), `events=${JSON.stringify(ev2)}`);
    }

    // ════ T2e 流程图/内容手势面仲裁（.fm-viewport 面起手零切卡）════
    // 单槽收编：先点 chip 切到 tri-doc（pane 入 host），再注入内容手势面桩。
    await page.locator('.mf-strip-chip[data-tab-id="tri-doc"]').click();
    await page.waitForSelector('.mf-cv-host .canvas-tab-pane[data-tab-id="tri-doc"]', { timeout: 9000 });
    await page.evaluate(() => {
      const pane = document.querySelector('.mf-cv-host .canvas-tab-pane[data-tab-id="tri-doc"]');
      const fm = document.createElement('div');
      fm.className = 'fm-viewport';
      fm.style.cssText = 'position:absolute;inset:0;';
      pane.appendChild(fm);
    });
    const paneBox = await page.locator('.mf-cv-host .canvas-tab-pane[data-tab-id="tri-doc"]').boundingBox();
    await clearEvents(page);
    await drag(paneBox.x + 120, paneBox.y + paneBox.height / 2, paneBox.x + 240, paneBox.y + paneBox.height / 2, 10);
    const ev3 = await readEvents(page);
    ok(ENGINE, 'T2e .fm-viewport（流程图）面起手横拖零切卡', !ev3.includes('card-switch'), `events=${JSON.stringify(ev3)}`);
    // 清理桩面
    await page.evaluate(() => document.querySelector('.mf-cv-host .fm-viewport')?.remove());

    // ════ T2f 非交互内容区横滑切卡保留（回归钉：仲裁没杀功能）════
    await clearEvents(page);
    await drag(paneBox.x + 260, paneBox.y + paneBox.height / 2, paneBox.x + 60, paneBox.y + paneBox.height / 2, 10);
    const ev4 = await readEvents(page);
    const chipAfterSwitch = await activeChipId(page);
    ok(ENGINE, 'T2f 普通内容区横滑仍切卡（切卡手势保留回归钉）',
      ev4.includes('card-switch') && chipAfterSwitch === 'tri-img', `chip=${chipAfterSwitch} events=${JSON.stringify(ev4)}`);

    // ════ T3 次级面板边缘返回 ════
    // 用量页腿（hub 行，与图谱无关的普通详情页；起手 y 避开控件面）
    await openHubPage(page, 'usage');
    await sleep(300);
    ok(ENGINE, 'T3a 用量详情页已推入（stack>1）', (await navTop(page)) === 'usage', `top=${await navTop(page)}`);
    const startY1 = await findEdgeStartY(page);
    await clearEvents(page);
    await drag(8, startY1, 150, startY1, 10);   // 左缘 8px 起右滑 142px
    ok(ENGINE, 'T3b 边缘右滑 = 真实返回（pop 回工作台根）', (await navTop(page)) === 'workbench', `top=${await navTop(page)}`);
    ok(ENGINE, 'T3c 返回语义与「<」同源（edge-back 事件在流水）', (await readEvents(page)).includes('edge-back'));

    // 图谱页腿（作者令点名的面板）
    await openHubPage(page, 'graph');
    await waitGraphReady(page);
    const startY2 = await findEdgeStartY(page);
    await drag(8, startY2, 160, startY2, 10);
    ok(ENGINE, 'T3d 图谱页边缘右滑 = 真实返回', (await navTop(page)) === 'workbench', `top=${await navTop(page)}`);

    // 弹簧回位（不过阈不 pop）
    await openHubPage(page, 'settings');
    await sleep(300);
    await drag(8, 400, 34, 400, 6);   // 26px < 80px 阈
    await sleep(320);
    ok(ENGINE, 'T3e 带内短距右滑不过阈 = 不返回 + 页面回位（弹簧语义）', (await navTop(page)) === 'settings');
    const tfCleared = await page.evaluate(() => {
      const el = document.querySelector('.mobile-page[data-page="settings"]');
      return !el.style.transform;
    });
    ok(ENGINE, 'T3f 弹簧回位后页面壳零残留 transform', tfCleared);

    // 纵滑零劫持（带内纵向 = 原生滚动/内容）
    await clearEvents(page);
    await drag(8, 300, 10, 440, 8);   // dy 主导
    ok(ENGINE, 'T3g 带内纵滑零返回（让位原生滚动/内容操作）', (await navTop(page)) === 'settings' && !(await readEvents(page)).includes('edge-back-claim'));

    // 按钮面零劫持（返回钮就在带旁：left 16 起 48px 面与 20px 带重叠）
    const backBtn = page.locator('.mobile-page[data-page="settings"] .mobile-back-btn');
    const bb = await backBtn.boundingBox();
    await drag(bb.x + 10, bb.y + bb.height / 2, bb.x + 130, bb.y + bb.height / 2, 8);
    ok(ENGINE, 'T3h 带内按钮面起手零劫持（返回钮点击语义原样）', (await navTop(page)) === 'settings');

    // dock 根页边界（一级面不认领）
    await openHubPage(page, 'graph');
    const startY3 = await findEdgeStartY(page);
    await drag(8, startY3, 160, startY3, 10);   // 先弹回 workbench
    await dockTo(page, 'canvas');
    await clearEvents(page);
    await drag(8, 420, 160, 420, 10);
    ok(ENGINE, 'T3i dock 根页（看板）不认领边缘返回（一级面边界钉）',
      (await navTop(page)) === 'canvas' && !(await readEvents(page)).includes('edge-back'), `top=${await navTop(page)}`);

    // ════ T4 手机层多面板横滑与切卡共存回归（canvas 页边缘右滑不误触发）════
    // （canvas 页 stack=1 无边缘返回；横滑仍切卡 —— 两手势同一页互不误触）
    // 单槽收编：先切到普通卡（tri-doc），拖起手才落在非内容手势面。
    await page.locator('.mf-strip-chip[data-tab-id="tri-doc"]').click();
    await page.waitForSelector('.mf-cv-host .canvas-tab-pane[data-tab-id="tri-doc"]', { timeout: 9000 });
    await clearEvents(page);
    await drag(30, 500, 200, 500, 10);   // 非带内横滑
    const ev5 = await readEvents(page);
    const chipT4 = await activeChipId(page);
    ok(ENGINE, 'T4 看板页非带内横滑仍走切卡（与边缘手势零互扰）',
      ev5.includes('card-switch') && chipT4 === 'tri-img', `chip=${chipT4} events=${JSON.stringify(ev5)}`);

    // ════ T5 console/page 零错误 ════
    ok(ENGINE, 'T5 手机层全程 console/pageerror 零错误', errors.length === 0,
      errors.length ? errors.slice(0, 3).join(' | ') : 'clean');
    await shot(page, ENGINE, 't5-final');

    // ════ T6 桌面 1280 回归（独立 context；CAD 手感/图片抓手契约不受批影响）════
    const dctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const dpage = await dctx.newPage();
    const derrors = [];
    dpage.on('pageerror', (e) => derrors.push(`pageerror: ${String(e.message || e).slice(0, 160)}`));
    dpage.on('console', (m) => { if (m.type() === 'error') derrors.push(`console: ${m.text().slice(0, 160)}`); });
    await dpage.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN');
    });
    await dpage.route('**/api/**', (r) => {
      const p = decodeURIComponent(new URL(r.request().url()).pathname);
      if (p === '/api/sessions') return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
      if (p === '/api/wiki/graph') return r.fulfill({ json: graphFixture() });
      return r.fulfill({ json: {} });
    });
    await dpage.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
    await dpage.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"wikiGraph":true}}', configured: true, onboarding: 'done' }));
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      sendConfig();
      sendSessions();
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getConfig') sendConfig();
        else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
        else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      });
    });
    await dpage.goto(BASE + '/index.html');
    await dpage.waitForSelector('#activity-bar', { timeout: 15000 });
    await sleep(600);
    const desktopNotMobile = await dpage.evaluate(() => !document.body.classList.contains('mobile-layer'));
    ok(ENGINE, 'T6a 桌面 1280 = 桌面壳（body.mobile-layer 不在）', desktopNotMobile);
    // 桌面图谱：CAD 手感保留（鼠标拖=平移 / 滚轮=缩放；touch-action 不影响鼠标）
    await dpage.evaluate(async () => {
      const m = await import('/js/wikiGraph.js');
      await m.openWikiGraphTab();
    });
    await dpage.waitForSelector('.wiki-graph-pane.active .wiki-canvas', { timeout: 9000 });
    await dpage.waitForFunction(() => {
      const p = (/** @type {any} */ (window)).__wikiGraphProbe?.();
      return p && p.alpha < 0.05;
    }, undefined, { timeout: 9000 });
    const dbox = await dpage.locator('.wiki-graph-pane.active .wiki-canvas').boundingBox();
    const dcx = dbox.x + dbox.width / 2, dcy = dbox.y + dbox.height / 2;
    const dBefore = await dpage.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.());
    await dpage.mouse.move(dcx, dcy);
    await dpage.mouse.down();
    for (let i = 1; i <= 8; i++) { await dpage.mouse.move(dcx + i * 20, dcy); await sleep(14); }
    await dpage.mouse.up();
    const dAfter = await dpage.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.());
    ok(ENGINE, 'T6b 桌面图谱鼠标拖平移不受批影响（CAD 管线原样）',
      Math.abs(dAfter.vx - dBefore.vx) > 60, `vx Δ=${Math.round(dAfter.vx - dBefore.vx)}`);
    await dpage.mouse.wheel(0, -240);
    await sleep(400);
    const dZoom = await dpage.evaluate(() => (/** @type {any} */ (window)).__wikiGraphProbe?.());
    ok(ENGINE, 'T6c 桌面图谱滚轮缩放不受批影响', dZoom.scale > dAfter.scale, `scale ${dAfter.scale.toFixed(2)}→${dZoom.scale.toFixed(2)}`);
    // 桌面图片查看器：抓手门契约原样（默认非拖拽）
    await dpage.evaluate(async (img) => {
      const canvas = await import('/js/canvas.js');
      canvas.openTab('dt-img', 'probe.png', { type: 'dt-img-type', absPath: '/tmp/probe.png' });
      const pane = canvas.getTabPane('dt-img');
      const fv = await import('/js/fileViewers.js');
      await fv.renderFile(pane, { itemType: 'image', objectUrl: img, fileName: 'probe.png', size: 100 });
    }, IMG_DATA);
    await dpage.waitForSelector('.canvas-tab-pane[data-tab-id="dt-img"] .canvas-image-viewport img', { timeout: 9000 });
    const handOnDesktop = await dpage.locator('.canvas-tab-pane[data-tab-id="dt-img"] .canvas-pan-toggle').count();
    const ib = await dpage.locator('.canvas-tab-pane[data-tab-id="dt-img"] .canvas-image-viewport').boundingBox();
    const imgTxBefore = await dpage.evaluate(() => document.querySelector('.canvas-tab-pane[data-tab-id="dt-img"] .canvas-image-viewport img')?.style.transform);
    await dpage.mouse.move(ib.x + ib.width / 2, ib.y + ib.height / 2);
    await dpage.mouse.down();
    for (let i = 1; i <= 6; i++) { await dpage.mouse.move(ib.x + ib.width / 2 + i * 18, ib.y + ib.height / 2); await sleep(12); }
    await dpage.mouse.up();
    const imgTxAfter = await dpage.evaluate(() => document.querySelector('.canvas-tab-pane[data-tab-id="dt-img"] .canvas-image-viewport img')?.style.transform);
    ok(ENGINE, 'T6d 桌面图片查看器抓手钮在场 + 默认非拖拽契约原样（冷启动批不动）',
      handOnDesktop === 1 && imgTxBefore === imgTxAfter);
    ok(ENGINE, 'T6e 桌面 1280 全程 console/pageerror 零错误', derrors.length === 0,
      derrors.length ? derrors.slice(0, 3).join(' | ') : 'clean');

    await ctx.close();
    await dctx.close();
  } finally {
    await browser.close();
  }
}

webServer.close();
console.log('\n===== 手机端手势三件批 · 双引擎验收 =====');
console.log(report.join('\n'));
console.log(`\n总计：${passTotal} PASS / ${failTotal} FAIL`);
process.exit(failTotal ? 1 : 0);

/** 看板文档卡数（chip 条）。 */
async function docCount(page) {
  return page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length);
}
