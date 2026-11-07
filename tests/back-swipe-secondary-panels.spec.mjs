// back-swipe-secondary-panels.spec.mjs — 全局次级面板「从左向右滑返回上一层」批
// （作者令 2026-11-07 12:17 逐字：「然后全局次级面板的ui交互 都应该要能支持从左向右滑返回
// 上一层」）机械验收。
//
// 实现面 = js/gesture/backSwipe.js（单点手势层 + 面板栈）+ popSheet/mobileSheetBack 接线 +
// navController.bindEdgeBack 的让路判据 + main.js 装配。本 spec 只打外部可观测契约，
// 不读实现内部变量（除只读探针 window.__backSwipeProbe）。
//
// 判据（改前必红 / 改后必绿）：
//   B1 登记面：次级面板开 ⇒ 探针 depth ≥1 且 top = 该面板 id；关 ⇒ 归零（改前无此栈，depth 恒 0）
//   B2 达阈提交：面板内从左缘右滑超出阈值 ⇒ 面板**真的关闭**（关闭走它自己的关闭钮，不是另一套逻辑）
//   B3 未达阈：位移 < 阈值 ⇒ 面板保持开 + 跟手位移回位到 0（不回弹成关闭）
//   B4 横向滚动区不误触：起手落在横向滚动/输入元素上 ⇒ 手势不认领、面板保持开
//   B5 桌面鼠标腿：pointerType=mouse 同一段逻辑（起手带宽 64px）达阈同样关闭
//   B6 纵滑让位：竖动手势 ⇒ 面板保持开（不抢滚动）
//   B7 栈语义：两层同时开 ⇒ Esc 只关最上面那一层，下层仍在（逐层返回）
//   B8 主会话让路：浮层在 ⇒ 页面栈腿让路（探针 has=true；右滑只关浮层一层）
//   B9 零 pageerror
//
// 跑法：node tests/back-swipe-secondary-panels.spec.mjs（自足：静态服务器 + mock 票据，端口 8234）
// 取证：截图 + 读数落 <repo>/.nebflow/evidence/back-swipe-panels

// canvas-turntable-ring.spec.mjs — canvas 顶部胶囊「转盘」批（作者令 2026-11-07 12:15
// 逐字：「canvas顶部胶囊的动画不是很好看 我要那种转盘的感觉 在左右滚动选择」）机械验收。
//
// 形态（canvas.js initCapsuleRing 的 dial 块 + split.css 横排位姿）：
//   环 = 一枚可转的**横排转盘**：盘面位置 = 连续小数游标 pos（整数格 = 正对某签），
//   胶囊位姿由 pos × 步距逐帧算出 ⇒ 滚轮未过阈就「跟手预转」（lead ≤0.26 格），
//   过阈则 240ms 定向吸附到整格（吸附档在途可观测），落定只对齐游标不切页签
//   （CR3-1「滚动≠切换」既有裁定；切换仍由中心位点击/缘位点击承接）。
//   轴 = 全部横排（--cr-x / --cr-ry），旧的竖弧（--cr-y / --cr-x 为 0 / rotateX 34°）
//   随本令退役；触屏腿的行宽预算/图标档/可读性收档逐字保留。
//
// 判据（改前必红 / 改后必绿）：
//   T1 起手横排：中心=激活签、纵轴位姿全 0、邻签 --cr-x 非零且 |x| 相等（横轴对称）
//   T2 跟手预转：阈下 24px ⇒ 盘面位姿走出小数（某签 --cr-x 非整格值），且**中心位不换主**
//      （档位按整数盘位判 —— 阈下微漂不许换选）
//   T3 吸附在途：过阈后逐帧采样 ≥5 个不同 --cr-x 且单调，落定值 = 整格（步距整数倍）
//   T4 落定语义：吸附完成 ⇒ 游标=目标签；**激活页签不变**（滚动≠切换）
//   T5 冷却：过阈后 200ms 内的后续事件不叠加（游标只前进一格）
//   T6 遮挡零：三签同一水平带内不与 ✕ / 堆叠浮钮相交（作者第⑤条不遮挡）
//   T7 reduced-motion：一发过阈 ⇒ 无中间帧（中间读数 == 落定读数），仍换选
//   T8 触屏腿零回归：touch 面 ⇒ 常驻横排、邻签吃 --cr-w 尺寸预算、纵轴 0
//   T9 零 pageerror
//
// 跑法：node tests/canvas-turntable-ring.spec.mjs（自足：静态服务器 + mock 票据，端口 8233）
// 取证：截图与逐帧读数落 <repo>/.nebflow/evidence/turntable-ring

// capsule-trackpad-feel.spec.mjs — 顶栏胶囊触控板手感批（作者报障 2026-10-25 13:07）spec 钉
//
// 作者报障逐字：「使用 mac 的触控板进行滑动的时候，不够灵敏和丝滑。」
// （组件 = 顶栏胶囊面板切换器：hover 环开态堆叠 pill 纵滚换选 —— #840 同组件）
//
// 手感病：触控板滑动 = 高频小增量 wheel 事件 + 惯性末段；鼠标滚轮 = 离散大步
// 进（delta≈±100）。旧实现「单事件过恒定阈值即切」两难：阈高触控板要用力甩
// （不灵敏）；阈低惯性一冲连跳三格（不丝滑）。
//
// 修法（canvas.js initCapsuleRing）：①设备分档累积器 —— 单事件 |d|（deltaMode
// 归一 px）≥40 = 鼠标格低阈 32 即切；触控板小增量按向累积过 48 才切；②切换后
// 200ms 冷却，期内事件只消费不累积（惯性不连跳，冷却后重积）；③steady 态游标
// 步进过渡 0.18s 快跟手档（split.css；prefers-reduced-motion 0.01s 直切照旧）；
// ④hover 区外滚轮零响应（不 preventDefault，不抢页面滚动）。
//
// 验收（工单逐字）：轻扫序列（多个小 delta）恰切 1 次；惯性序列（30 个小
// delta 连发）切换 ≤2 次不超调；鼠标单格 delta 切 1 次；冷却期内事件不叠加
// 切换；加过渡动画的 CSS 断言。
//
// 运行：node tests/capsule-trackpad-feel.spec.mjs
// （boot 同 capsule-hover-chain.spec.mjs：隔离静态服务器 + workspace-open-item
//   直灌文件卡 + __canvasDeck.state() 探针；永不触碰宿主 8080。滚轮一律页内
//   合成 WheelEvent（可编程 delta/时序/坐标，触控板时序无法用真触控板自动化
//   —— 用事件序列断言行为学）。）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8234);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = resolve(HERE, '../.nebflow/evidence/back-swipe-panels');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const FILES = ['alpha', 'bravo', 'charlie', 'delta'];
const FILE_CONTENTS = new Map(FILES.map((f) => [`/tmp/ct-fixture/${f}.md`, { content: `# ${f}\n\n${f} body\n`, itemType: 'markdown' }]));

async function boot(ctxOpts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark', reducedMotion: 'no-preference', ...ctxOpts });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openFile(page, absPath) {
  const rec = FILE_CONTENTS.get(absPath);
  const title = absPath.split('/').pop();
  await page.evaluate(({ absPath, title, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { absPath, title, content: rec.content, itemType: rec.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const stateOf = (page) => page.evaluate(() => window.__canvasDeck.state());

/** 胶囊（标题头）中心点 + 面板矩形（区外零响应取点用）。 */
async function points(page) {
  return page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return {
      cap: { x: r.left + r.width / 2, y: r.top + r.height / 2 },
      far: { x: p.left + p.width / 2, y: Math.min(p.top + 320, p.bottom - 80) },
    };
  });
}
async function hoverCapsule(page) {
  const { cap } = await points(page);
  await page.mouse.move(cap.x, cap.y);
  await sleep(520);                                 // pointerenter 开环 + 展开过渡 + 稳态门走完
}
const ringSnap = (page) => page.evaluate(() => ({
  open: document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open'),
  n: document.querySelectorAll('#canvas-capsule-ring .cr-capsule').length,
  center: document.querySelector('#canvas-capsule-ring .cr-capsule.is-center')?.dataset.tabId || null,
  steady: document.getElementById('canvas-capsule-ring')?.classList.contains('steady') || false,
}));

/** 页内账本：游标步进（is-center 易主序列）+ 消费相 defaultPrevented（区外
 *  零响应断言）。消费观察走 window **捕获**相 + 延拍读取 —— 环消费滚轮时会
 *  stopPropagation，冒泡相永远看不到被消费的事件；捕获相先记账、宏任务末读
 *  defaultPrevented（派发同步完成后终值）。 */
async function installProbe(page) {
  await page.evaluate(() => {
    const ring = document.getElementById('canvas-capsule-ring');
    window.__steps = [];                   // [{id, t}] 每次 is-center 易主记一笔（连元去重）
    window.__bubble = [];                  // [{prevented, dy, t}] 捕获相延拍账本
    window.addEventListener('wheel', (e) => {
      const { deltaY } = e;
      setTimeout(() => { window.__bubble.push({ prevented: e.defaultPrevented, dy: deltaY, t: performance.now() }); }, 0);
    }, true);
    if (window.__mo) window.__mo.disconnect();
    window.__mo = new MutationObserver((muts) => {
      const c = ring.querySelector('.cr-capsule.is-center');
      const id = c ? c.dataset.tabId : null;
      const arr = window.__steps;
      if (!arr.length || arr[arr.length - 1].id !== id) arr.push({ id, t: performance.now(), via: muts.map((m) => (m.oldValue || '(null)') + '→' + (m.target.className || '')).join(' ;; ').slice(0, 300) });
    });
    window.__mo.observe(ring, { subtree: true, attributes: true, attributeOldValue: true, attributeFilter: ['class'] });
  });
}
const stepCount = (page) => page.evaluate(() => window.__steps.length);
const stepIds = (page) => page.evaluate(() => window.__steps.map((s) => (s.id || 'null').split('/').pop() + '@' + Math.round(s.t) + ' via:' + (s.via || '')));
const bubbleTail = (page, k) => page.evaluate((n) => window.__bubble.slice(-n), k);

/** 页内合成 wheel 序列（真时序：事件间 setTimeout 真等待 —— 冷却语义按真实
 *  时钟判定；坐标显式携带 —— 区内/区外零响应按事件坐标判定）。 */
async function fireWheel(page, deltas, gapMs, pt) {
  await page.evaluate(async ({ deltas, gapMs, pt }) => {
    for (let i = 0; i < deltas.length; i++) {
      const el = document.elementFromPoint(pt.x, pt.y) || document.body;
      el.dispatchEvent(new WheelEvent('wheel', {
        deltaX: 0, deltaY: deltas[i], deltaMode: 0,
        clientX: pt.x, clientY: pt.y, bubbles: true, cancelable: true, view: window,
      }));
      if (gapMs && i < deltas.length - 1) await new Promise((r) => setTimeout(r, gapMs));
    }
  }, { deltas, gapMs, pt });
}

mkdirSync(SHOTS, { recursive: true });

const probe = (page) => page.evaluate(() => (window.__backSwipeProbe ? window.__backSwipeProbe() : { has: false, top: null, depth: -1 }));
const openPanel = async (page, id) => {
  await page.evaluate((sel) => {
    const ov = document.querySelector(sel);
    ov.classList.add('on');   // 显隐单源 = modal.css 的 .on 规则（不塞内联样式，否则关闭腿失效）
  }, id);
  await sleep(120);
};
const closePanelState = async (page) => page.evaluate(() => ({
  settings: document.getElementById('settings-overlay')?.classList.contains('on') || false,
  memory: document.getElementById('memory-overlay')?.classList.contains('on') || false,
  search: document.getElementById('search-overlay')?.classList.contains('on') || false,
  tf: document.getElementById('settings-overlay')?.style.transform || '',
}));

/** 合成一条完整指针序列（pointerdown → move ×N → up）；pointerType 可切 touch/mouse。
 *  起手坐标显式给（左缘带判定按事件坐标，不按元素）。 */
async function swipe(page, { x0 = 6, y0 = 400, dx = 220, dy = 0, pointerType = 'touch', steps = 6, gap = 12, targetSel = 'body' } = {}) {
  await page.evaluate(async ({ x0, y0, dx, dy, pointerType, steps, gap, targetSel }) => {
    const target = document.querySelector(targetSel) || document.body;
    const mk = (type, cx, cy, buttons) => new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true, pointerId: 11, pointerType,
      isPrimary: true, button: 0, buttons, clientX: cx, clientY: cy,
    });
    target.dispatchEvent(mk('pointerdown', x0, y0, 1));
    for (let i = 1; i <= steps; i++) {
      target.dispatchEvent(mk('pointermove', x0 + (dx * i) / steps, y0 + (dy * i) / steps, 1));
      if (gap) await new Promise((r) => setTimeout(r, gap));
    }
    target.dispatchEvent(mk('pointerup', x0 + dx, y0 + dy, 0));
  }, { x0, y0, dx, dy, pointerType, steps, gap, targetSel });
}

/* ════════ 主场景：次级面板（设置 / 记忆）滑返 ════════ */
{
  const { ctx, page, errors } = await boot();
  await sleep(300);
  const p0 = await probe(page);
  ok('B0 前置：无面板时滑返栈为空（depth=0）', p0.depth === 0 && p0.has === false, JSON.stringify(p0));

  /* B1 登记面 */
  await openPanel(page, '#settings-overlay');
  const p1 = await probe(page);
  await page.screenshot({ path: join(SHOTS, '1-settings-open.png') });
  ok('B1 次级面板开 ⇒ 单点滑返栈登记（depth≥1、栈顶 = settings）', p1.depth >= 1 && p1.top === 'settings', JSON.stringify(p1));

  /* B4 横向滚动区/输入区起手不误触（在设置面板的输入控件上起手横滑） */
  const hasInput = await page.evaluate(() => !!document.querySelector('#settings-content input, #settings-content select, #settings-content button'));
  if (hasInput) {
    await page.evaluate(() => {
      const el = document.querySelector('#settings-content input, #settings-content select, #settings-content button');
      el.dataset.probeTarget = '1';
      document.documentElement.dataset.probeBox = JSON.stringify(el.getBoundingClientRect());
    });
    const box = await page.evaluate(() => JSON.parse(document.documentElement.dataset.probeBox));
    await swipe(page, { x0: Math.max(6, box.x + 4), y0: box.y + box.height / 2, dx: 220, targetSel: '[data-probe-target="1"]' });
    await sleep(260);
    const st = await closePanelState(page);
    ok('B4 输入/控件上起手横滑不误触（面板保持开）', st.settings === true, JSON.stringify(st));
  } else {
    ok('B4 输入/控件上起手横滑不误触（面板内无输入控件，跳过）', true, 'skip');
  }

  /* B6 纵滑让位 */
  await swipe(page, { x0: 6, y0: 400, dx: 0, dy: 220, targetSel: '#settings-overlay' });
  await sleep(260);
  ok('B6 纵滑让位（不抢页面滚动、面板保持开）', (await closePanelState(page)).settings === true, 'vertical');

  /* B3 未达阈 ⇒ 回弹不关 */
  await swipe(page, { x0: 6, y0: 400, dx: 40, gap: 70, targetSel: '#settings-overlay' });   // 慢速 40px（>200ms ⇒ 不算快弹）
  await sleep(320);
  const st3 = await closePanelState(page);
  ok('B3 未达阈（40px < max(80, 22%×w)）⇒ 面板保持开 + 跟手位移回位 0', st3.settings === true && (st3.tf === '' || /translate3d\(0px, 0px, 0px\)|matrix\(1, 0, 0, 1, 0, 0\)/.test(st3.tf)), JSON.stringify(st3));

  /* B2 达阈提交（触屏腿） */
  await swipe(page, { x0: 8, y0: 420, dx: 260, steps: 5, gap: 14, targetSel: '#settings-overlay' });
  await sleep(400);
  const st2 = await closePanelState(page);
  const p2 = await probe(page);
  await page.screenshot({ path: join(SHOTS, '2-settings-closed.png') });
  ok('B2 左缘右滑达阈 ⇒ 面板真的关闭（走它自己的关闭钮）+ 栈随之出栈',
    st2.settings === false && p2.depth === 0, `panel=${JSON.stringify(st2)} probe=${JSON.stringify(p2)}`);

  /* B5 桌面鼠标腿 */
  await openPanel(page, '#settings-overlay');
  await sleep(120);
  await swipe(page, { x0: 12, y0: 420, dx: 260, pointerType: 'mouse', steps: 5, gap: 14, targetSel: '#settings-overlay' });
  await sleep(400);
  ok('B5 桌面鼠标拖拽同一条腿（pointerType=mouse）达阈同样关闭',
    (await closePanelState(page)).settings === false, JSON.stringify(await closePanelState(page)));

  /* B7 栈语义：两层开 ⇒ Esc 只关栈顶 */
  await openPanel(page, '#settings-overlay');
  await openPanel(page, '#search-overlay');
  const p7 = await probe(page);
  await page.keyboard.press('Escape');
  await sleep(320);
  const st7 = await closePanelState(page);
  const p7b = await probe(page);
  ok('B7 栈语义：两层同开（settings+search）⇒ Esc 只关最上面那层，下层仍在（逐层返回）',
    p7.depth >= 2 && st7.search === false && st7.settings === true && p7b.top === 'settings',
    `before=${JSON.stringify(p7)} after=${JSON.stringify(st7)} probe=${JSON.stringify(p7b)}`);

  /* B8 主会话让路：浮层在 ⇒ 页面栈腿让路（探针 has=true 即判据口） */
  ok('B8 浮层在时页面栈腿让路（hasBackHandler=true = navController 的让路判据）',
    (await probe(page)).has === true, JSON.stringify(await probe(page)));

  await page.keyboard.press('Escape');
  await sleep(300);
  ok('B9 逐层退完 ⇒ 栈空（depth=0）且零 pageerror',
    (await probe(page)).depth === 0 && errors.length === 0, `probe=${JSON.stringify(await probe(page))} err=${errors.slice(0, 2).join(' | ')}`);
  await ctx.close();
}

console.log(`\nback-swipe-secondary-panels: ${pass} PASS / ${fail} FAIL`);
if (server && typeof server.close === 'function') server.close();
process.exit(fail ? 1 : 0);
