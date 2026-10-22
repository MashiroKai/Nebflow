// canvas-stack.spec.mjs — Canvas 标签行 → 叠卡（v5 定稿落地）实施版验收
//
// 断言清单移植自 v5 demo 报告（reports/20261020_083800_canvas-card-stack-design-v5
// §四自验记录，58 断言）＋本批三精修各自断言：
//   精修① 动画再顺滑一档 —— 趋近系数 10/7→12/9、翻落曲线收幅 30/40/6/30→26/32/5/24、
//          morph 时长统一 0.28s 档（canvas.js DECK_* 常量单一来源）；
//   精修② 背景阴影浅一档 —— --card-shadow / .stacked 投影密度低于样张值（split.css token）；
//   精修③ × 退役 → 竖排浅色滑动提示 —— .cv-close 零命中、卡缘/面板缘竖排
//          writing-mode 小字（「滑动关闭」），划走关闭手势照 v5 保留。
// 作者补充令（竖拖换卡）断言：
//   A17 面板面竖拖换卡 —— Pointer Events 纵向拖动 dy 增量喂 scrub 引擎（跟手连续）、
//       松手惯性+贴靠换卡且不触发关闭；触屏（CDP touch → pointerType:'touch'）同通路；
//   A18 环卡面竖拖换卡 / 横滑关闭分工 —— 纵向主导换卡不关闭、横向主导甩出关闭
//       （原 v2⑥ 逻辑保持）；
//   A19 主导轴判定 —— dy 主导对角→锁 y 换卡、dx 主导对角→锁 x 划走关闭、
//       ≤8px 死区不锁轴（点击语义保持：点 canvas = 收回卡组）。
//
// uifix 批（作者令 2026-10-20 四件，叠卡视角重做）断言修订：
//   · wheelScrub 改为**触控板式连续小 delta 流**（单事件 <40px 基速增益）——
//     新 wheel 契约（③c）：叠卡面内才捕获；鼠标格（|dy|≥40）走档位增益 ≈一格近一卡；
//     光标须落在叠卡面上（面板/卡组层），面外双指滚动不被劫持（与页面滚动不打架）；
//   · 露边阶梯 22px → **27px**（③a 默认 v2 均衡档间距拉开；v1=22 收敛/v3=34 拉开）；
//   · 精修③断言（竖排提示）整体改写为 ③d/③e：提示文字零命中 + 卡面 × 钮存在且可关；
//   · 精修② F2-2 改锚 uifix 新 token（--stacked-shadow 前景档随 look 联动）。
//
// UI 批（作者令 2026-10-21 五件，堆叠视图）断言修订：
//   · ①帮助条整体退役：A2-9/A3-11/A13-4/A17-0b 改写为 deck-foot 零命中 +
//     文案扫描零命中（前景轮换身份改由 state().frontName 断言）；
//   · ②连点稳定 spec 钉 = A20：快速三连点每次计数（排队补发）、收场清重入锁、
//     泄漏修复后 fab 照常响应；
//   · ③真容快照 + 活性窗 = A22：md 族全克隆零 pending、可视卡 ±2 精渲染窗、
//     源滚动缩略镜像、源变更防抖重截；
//   · ④动效层 = A21 + A2-6/A2-7/A13-1 改锚缩放坡道（近大远小 + 视差 z）；
//     ④②deckProjectYComp 透视 y 补偿：投影顶钉回设计露边阶梯（补偿前投影把
//     27px 阶梯压扁到 ~4px、深处命中带 <1px 致 A9 红）——A2-5/A13-2 直接锚
//     设计值 27px；
//   · ⑤边缘融合 = F2-2/F2-3 改锚羽化 token（环境晕 + 大模糊 + 描边退役 + shade 渐变）。
//
// 与样张 __demo.state() 同口径的断言通道 = window.__canvasDeck.state()。
// 环境注（继承 v2–v5）：无头 chromium rAF 实测 ~36fps，指数趋近收敛时间按比例拉长，
// 自动化等待 ≥2.6s；真实浏览器 60fps 无此现象。
//
// 运行：node tests/canvas-stack.spec.mjs
// 可选 env：PORT（静态服务端口）、CANVAS_STACK_SHOTS_DIR（截图输出目录）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8147);
const BASE = `http://127.0.0.1:${PORT}`;
const TS = process.env.CANVAS_STACK_TS || new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '').replace(/(\d{8})(\d{6}).*/, '$1_$2');
const SHOTS_DIR = process.env.CANVAS_STACK_SHOTS_DIR ||
  resolve(HERE, `../evidence/${TS}_canvas-stack-impl/shots`);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

function nfFileRoute() {
  const ALLOWED = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico', 'mp4', 'webm', 'mp3', 'wav', 'woff', 'woff2', 'ttf', 'otf', 'pdf', 'docx', 'xlsx', 'pptx', 'epub', 'js', 'mjs', 'css', 'json', 'md', 'markdown', 'txt'];
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const ext = (p.split('.').pop() || '').toLowerCase();
    if (!ALLOWED.includes(ext)) return route.fulfill({ status: 400, contentType: 'text/plain', body: 'File type not allowed' });
    try {
      return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  };
}

// ── 9 页签 fixtures（同 v5 样张 9 卡规模；markdown 渲染面轻量）──
const FILES = [
  { name: 'alpha.md', dirty: false }, { name: 'bravo.md', dirty: false },
  { name: 'charlie.md', dirty: false }, { name: 'delta.md', dirty: false },
  { name: 'echo.md', dirty: false }, { name: 'foxtrot.md', dirty: false },
  { name: 'golf.md', dirty: false }, { name: 'hotel.md', dirty: false },
  { name: 'india.md', dirty: false },
];
const absOf = (name) => `/tmp/canvas-stack-fixture/${name}`;

async function bootPage(browser, { viewport = { width: 1600, height: 1000 }, colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openFile(page, name) {
  const absPath = absOf(name);
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nmock body\n`,
        absPath, pinned: true,
      },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000 });
}

/** 开满 9 页签并把 active 收敛回第一张（同 v5 样张 activeId=TABS[0]）。 */
async function openAllTabs(page) {
  for (const f of FILES) await openFile(page, f.name);
  await openFile(page, FILES[0].name);   // 已存在 → 仅切激活
  await sleep(400);
}

async function state(page) {
  return page.evaluate(() => window.__canvasDeck.state());
}
/** 等待擦洗/动画收敛（headless rAF ~36fps，v5 报告环境注：等待 ≥2.6s）。 */
async function settle(page, ms = 2700) { await sleep(ms); }
/** 轮询直到 cond(state) 为真（叠卡中途态采样）。 */
async function pollState(page, cond, ms = 2600) {
  const t0 = Date.now();
  let s = await state(page);
  while (Date.now() - t0 < ms) {
    s = await state(page);
    if (cond(s)) return s;
    await sleep(30);
  }
  return s;
}
/** 轮询一个原子快照（state + inline 位姿同时读，避免中途态采样竞态）。 */
async function pollSnap(page, ms = 2600) {
  const t0 = Date.now();
  let snap = null;
  while (Date.now() - t0 < ms) {
    snap = await page.evaluate(() => {
      const s = window.__canvasDeck.state();
      if (!(Math.abs(s.d0) > 0.15 && Math.abs(s.d0) < 0.85)) return null;
      const t = document.getElementById('canvas-panel').style.transform;
      const mY = /translate3d\(0(?:px)?,\s*(-?[\d.]+)px/.exec(t);
      const mRX = /rotateX\((-?[\d.]+)deg/.exec(t);
      return { ...s, inlineY: mY ? parseFloat(mY[1]) : null, inlineRX: mRX ? parseFloat(mRX[1]) : null };
    });
    if (snap) return snap;
    await sleep(25);
  }
  return null;
}
/** canvas 区基准盒 = 面板布局盒（offset*，与引擎 deckPanelRect 同源——叠卡态
 *  gBCR 是缩放投影盒，不能做几何基准）。 */
async function panelRect(page) {
  return page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    return {
      x: p.offsetLeft, y: p.offsetTop,
      width: p.offsetWidth, height: p.offsetHeight,
      right: p.offsetLeft + p.offsetWidth, bottom: p.offsetTop + p.offsetHeight,
    };
  });
}
async function cardRect(page, r) {
  return page.evaluate((r) => {
    const el = document.querySelector(`.deck-card[data-r="${r}"]`);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: b.x, y: b.y, width: b.width, height: b.height, op: parseFloat(el.style.opacity || '1') };
  }, r);
}
/** uifix ③c：触控板式滚轮擦洗（连续小 delta 流，单事件 <40px 基速增益，合计
 *  ≈dy/240 卡）。光标落在叠卡面上（面板/卡组层）——新契约只有面内才捕获擦洗，
 *  面外滚动不被劫持。基速同 v5：240px 合计 = 1.0 卡。
 *  熄滑尾流：流末静默 >110ms 会以末窗速度起滑行（③c 动量）——补三粒 **0delta**
 *  尾事件把「末窗/瞬时速度」稀释到武装地板之下，滑行不起（相位钉住整数）。
 *  ⚠ 页内合成 WheelEvent 派发（非 page.mouse.wheel）：后者经 CDP 原生通路在
 *  macOS 上伴随 ~1px/次的 scroll-latch 尾事件（240px 流实测超付 +10px，相位
 *  被推离整数 → 基姿/贴靠断言全红；合成派发 target 精确 = dy/240，探针实证）。 */
async function wheelScrub(page, dy) {
  await page.evaluate(() => {}); // flush
  const pr = await panelRect(page);
  await page.mouse.move(pr.x + pr.width / 2, pr.y + pr.height / 2);
  const steps = Math.max(1, Math.ceil(Math.abs(dy) / 24));
  const stepDy = dy / steps;
  const fire = (d) => page.evaluate(({ d }) => {
    const p = document.getElementById('canvas-panel');
    p.dispatchEvent(new WheelEvent('wheel', { deltaY: d, bubbles: true, cancelable: true }));
  }, { d });
  await fire(0);                       // 首粒 0delta：只落光标域（不进速度窗：dt 大）
  for (let i = 0; i < steps; i++) {
    await fire(stepDy);
    await sleep(8);
  }
  for (let i = 0; i < 3; i++) {        // 熄滑三粒（0delta：稀释末窗速度不动相位——
    await fire(0);                     //  0.5px 粒会把 target 顶过整数（A15 d₀=0 断红实证）
    await sleep(30);
  }
}

/** Pointer Events 拖划（鼠标通路，竖拖补充令断言驱动）：按下 → 分步位移 → 松手。
 *  段间 gap 让 EMA 速度收敛到人手量级（松手惯性项可复现：260px/40ms 间隔 ≈
 *  冲程 0.2~0.5 卡，贴靠落点确定）；hold=true 不松手（中途态采样后由调用方 up）。 */
async function pointerDrag(page, x, y, dx, dy, { steps = 10, gap = 40, hold = false } = {}) {
  await page.mouse.move(x, y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(x + (dx * i) / steps, y + (dy * i) / steps, { steps: 1 });
    if (gap) await sleep(gap);
  }
  if (!hold) await page.mouse.up();
}

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);                    // 增量输出：单组崩溃不丢已跑结果
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();

/* ════════ A1 常态（v5 A1 移植 + 精修③无叉）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const s = await state(page);
  ok('A1-1 卡组层空且未 live', s.M === 0 && !/live/.test(s.deckLayerClass), `class="${s.deckLayerClass}"`);
  ok('A1-2 canvas 根无 stacked', !/\bstacked\b/.test(s.panelClass), `class="${s.panelClass}"`);
  ok('A1-3 deck-layer 非 canvas 根子节点', s.deckIsPanelChild === false);
  ok('A1-4 浮钮非 canvas 根子节点（v5② 判据）', s.fabIsPanelChild === false);
  const pr = await panelRect(page);
  const fab = await page.evaluate(() => {
    const el = document.getElementById('deck-fab');
    const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height, op: getComputedStyle(el).opacity };
  });
  ok('A1-5 浮钮钉区右下 bottom16/right12/40px（移位批 2026-10-22：顶右 → 右下角）',
    approx(pr.bottom - (fab.y + fab.h), 16, 1.5) && approx(pr.right - (fab.x + fab.w), 12, 1.5) && fab.w === 40 && fab.h === 40,
    `db=${(pr.bottom - fab.y - fab.h).toFixed(1)} dr=${(pr.right - fab.x - fab.w).toFixed(1)} ${fab.w}x${fab.h}`);
  ok('A1-6 浮钮 rest 0.6', fab.op === '0.6', `op=${fab.op}`);
  const noX = await page.evaluate(() => ({
    cvClose: document.querySelectorAll('.cv-close').length,
    tabBarCloseVisible: (() => {
      const b = document.querySelector('.canvas-tab-close');
      if (!b) return false;
      const st = getComputedStyle(b);
      return st.display !== 'none' && st.visibility !== 'hidden' && b.offsetParent !== null;
    })(),
  }));
  ok('A1-7 常态无叉（.cv-close 零命中 + 标签条叉不可见，精修③）', noX.cvClose === 0 && !noX.tabBarCloseVisible,
    `cv-close=${noX.cvClose} tabBarCloseVisible=${noX.tabBarCloseVisible}`);
  ok('R0 主文档无 JS 运行时错误（A1）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A2 整区缩放入场（v5 A2 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const s = await state(page);
  const pr = await panelRect(page);
  // 缩放态 canvas 的可视前卡顶（布局盒 → 中心缩放 85% → +8px shift）
  const stackedFrontTop = pr.y + (pr.height - pr.height * 0.85) / 2 + 8;
  ok('A2-1 根 matrix(0.85,…,8)', /matrix\(0\.85,\s*0,\s*0,\s*0\.85,\s*0,\s*8\)/.test(s.rootTransform), s.rootTransform);
  ok('A2-2 环含全部页签（active 在环首，v5①）', s.M === s.tabCount && s.ring.length === s.tabCount && s.ring[0] === 'alpha.md',
    `M=${s.M} tabs=${s.tabCount} ring0=${s.ring[0]}`);
  ok('A2-3 初始前景位=当前 canvas 卡', s.frontIdx === 0 && s.frontName === 'alpha.md', `front=${s.frontName}`);
  ok('A2-4 canvas 环卡前景位隐没（op≈0）', s.selfCardHidden === true);
  const c1 = await cardRect(page, 1);
  /* UI 批 ④②：deckProjectYComp 透视 y 补偿后，投影顶 = 设计顶 —— 露边阶梯
     27px（peek1 档）在屏幕上真实存在（补偿前投影压扁到 ~4px，A9 命中带消失）。 */
  ok('A2-5 d=1 露边 27px（设计值；④②透视补偿后投影顶=设计顶，±4px）',
    approx(stackedFrontTop - c1.y, 27, 4), `peek=${(stackedFrontTop - c1.y).toFixed(1)}`);
  /* UI 批 ④：缩放坡道（近大远小）+ 视差 z。d=1 卡预期宽 ≈ 面板宽 × 0.85（卡基）
     × 0.945（DECK_DEPTH_SCALE_NEAR）× 0.978（透视 z=-26 投影）≈ ×0.783。 */
  const d1Expected = pr.width * 0.85 * (1 - 0.055) * (1150 / (1150 + 26));
  ok('A2-6 后卡 = 缩放坡道 d=1 位（0.85 × 近档缩小 × 透视投影，±3%）',
    approx(c1.width, d1Expected, d1Expected * 0.03) && c1.width < pr.width * 0.85 - 8,
    `${c1.width.toFixed(0)} vs ${d1Expected.toFixed(0)}（面板 ${(pr.width * 0.85).toFixed(0)}）`);
  const c3 = await cardRect(page, 3);
  const d3Expected = pr.width * 0.85 * (1 - 0.055 - 0.036) * (1150 / (1150 + 26 * 3));
  ok('A2-7 d3 卡更深一档（宽 < d1，且落在坡道公式 ±4%——近大远小连续）',
    c3.width < c1.width - 4 && approx(c3.width, d3Expected, d3Expected * 0.04),
    `d1=${c1.width.toFixed(0)} d3=${c3.width.toFixed(0)} exp=${d3Expected.toFixed(0)}`);
  ok('A2-8 canvas 前景时 .over 未抬', s.deckOver === false);
  /* UI 批 ①：两行帮助条（操作提示 + 前景计数）整体退役——deck-foot 元素零命中、
     面板/卡组层一个字不留。 */
  const helpGone = await page.evaluate(() => ({
    foot: !!document.getElementById('deck-foot'),
    hint: document.querySelectorAll('.deck-hint').length,
    count: document.querySelectorAll('.deck-count').length,
    anyText: [...document.querySelectorAll('#canvas-panel, #deck-layer')].some((el) =>
      el.textContent.includes('滚轮 / 上下拖动') || el.textContent.includes('下一张')),
  }));
  ok('A2-9 帮助条整行退役（deck-foot/hint/count 零命中 + 文案扫描零命中）',
    !helpGone.foot && helpGone.hint === 0 && helpGone.count === 0 && !helpGone.anyText,
    JSON.stringify(helpGone));
  ok('A2-10 断言通道 helpRetired=true', s.helpRetired === true);
  ok('R0 主文档无 JS 运行时错误（A2）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.screenshot({ path: join(SHOTS_DIR, 'light-deck-open.png'), clip: { x: pr.x - 8, y: pr.y - 8, width: pr.width + 16, height: pr.height + 16 } });
  await ctx.close();
}

/* ════════ A3 前景轮换（v5 A3 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 240);
  const mid = await pollSnap(page);
  ok('A3-0 中途态采样命中（d₀∈(0.15,0.85)）', !!mid, mid ? `d0=${mid.d0.toFixed(2)}` : 'miss');
  ok('A3-1 擦洗中途面板 translate3d 正 y（向下翻落）', !!mid && mid.inlineY !== null && mid.inlineY > 5,
    mid ? `dy=${mid.inlineY}` : 'no-snap');
  ok('A3-2 擦洗中途 rotateX 前倾（正角）', !!mid && mid.inlineRX !== null && mid.inlineRX > 2,
    mid ? `rx=${mid.inlineRX}` : 'no-snap');
  ok('A3-3 中途 alpha 交叉衰减（<1）', !!mid && mid.panelAlpha < 1, mid ? `alpha=${mid.panelAlpha}` : 'no-snap');
  ok('A3-4 中途 .over 抬层', !!mid && mid.deckOver === true);
  await settle(page);
  const s1 = await state(page);
  ok('A3-5 pos 收敛 1.0', approx(s1.pos, 1, 0.01), `pos=${s1.pos.toFixed(3)}`);
  ok('A3-6 前景位易主 bravo.md', s1.frontIdx === 1 && s1.frontName === 'bravo.md', `front=${s1.frontName}`);
  const panelHidden = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    return { pe: p.style.pointerEvents, tf: p.style.transform };
  });
  ok('A3-7 面板隐没（alpha 0 + pointer-events 关断，隐没面板不拦卡组点击）',
    s1.panelAlpha < 0.05 && panelHidden.pe === 'none', `alpha=${s1.panelAlpha} pe=${panelHidden.pe}`);
  ok('A3-8 环卡前景位 over 保持抬层（盖过隐没 canvas）', s1.deckOver === true, `over=${s1.deckOver}`);
  const fr = await cardRect(page, 1);
  const pr = await panelRect(page);
  const stackedTop = pr.y + (pr.height - pr.height * 0.85) / 2 + 8;
  const stackedLeft = pr.x + (pr.width - pr.width * 0.85) / 2;
  ok('A3-9 前卡落前景基姿（与缩放态 canvas 同位 ≤2px）',
    approx(fr.y, stackedTop, 2.5) && approx(fr.x, stackedLeft, 2.5) && fr.op === 1,
    `card=(${fr.x.toFixed(1)},${fr.y.toFixed(1)}) stacked=(${stackedLeft.toFixed(1)},${stackedTop.toFixed(1)})`);
  const frontCls = await page.evaluate(() => {
    const el = document.querySelector('.deck-card[data-r="1"]');
    return { front: el.classList.contains('front'), cursor: getComputedStyle(el).cursor, hint: !!el.querySelector('.card-swipe-hint') };
  });
  ok('A3-10 前卡 .front 可拖划', frontCls.front === true && frontCls.cursor === 'grab');
  /* UI 批 ①：计数行退役——轮换态身份改由 state().frontName 断言（A3-6 已钉）。 */
  const countGone = await page.evaluate(() => !document.getElementById('deck-foot'));
  ok('A3-11 计数行退役（轮换态仍可断言：frontName=bravo.md）', countGone && s1.frontName === 'bravo.md',
    `foot=${countGone} front=${s1.frontName}`);
  const fallen = await cardRect(page, 0);
  ok('A3-12 canvas 本尊翻落收进卡组（op 显著衰减）', fallen !== null && fallen.op < 0.7, `op=${fallen && fallen.op}`);
  await wheelScrub(page, -240);
  await settle(page);
  const s2 = await state(page);
  ok('A3-13 反向 −1 格可回滚（canvas 回前景）', approx(s2.pos, 0, 0.01) && s2.frontIdx === 0 && s2.frontName === 'alpha.md',
    `pos=${s2.pos.toFixed(3)} front=${s2.frontName}`);
  // 键盘同通路
  await page.keyboard.press('ArrowDown');
  await settle(page);
  const s3 = await state(page);
  ok('A3-14 键盘 ↑↓ 同通路（↓ → bravo 前景）', approx(s3.pos, 1, 0.01) && s3.frontName === 'bravo.md', `pos=${s3.pos.toFixed(3)}`);
  await page.keyboard.press('ArrowUp');
  await settle(page);
  const s4 = await state(page);
  ok('A3-15 键盘 ↑ 回滚', approx(s4.pos, 0, 0.01) && s4.frontName === 'alpha.md');
  const shotRect = await panelRect(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'light-scrub-mid.png'), clip: { x: shotRect.x - 8, y: shotRect.y - 8, width: shotRect.width + 16, height: shotRect.height + 16 } });
  ok('R0 主文档无 JS 运行时错误（A3）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A4 点深处卡 = 翻到该卡（v5 A4 移植，不换签）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 240);
  await settle(page);
  await page.evaluate(() => document.querySelector('.deck-card[data-r="3"]').click());
  await settle(page);
  const s = await state(page);
  ok('A4-1 点 d=3 卡 = 翻到该卡（pos→3）', approx(s.pos, 3, 0.01) && s.frontIdx === 3, `pos=${s.pos.toFixed(3)}`);
  ok('A4-2 不换签（v1 回归；active 仍 alpha.md）', s.activeId.endsWith('alpha.md'), `active=${s.activeId}`);
  ok('R0 主文档无 JS 运行时错误（A4）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A5 点前景卡 = 放大回满幅换入（v5 A5 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const nBase = await page.evaluate(() => window.__canvasDeck.state().tabCount);
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 240);        // bravo 到前景
  await settle(page);
  await page.evaluate(() => document.querySelector('.deck-card[data-r="1"]').click());
  await settle(page, 3200);
  const s = await state(page);
  ok('A5-1 activeId 切为该卡', s.activeId.endsWith('bravo.md'), `active=${s.activeId}`);
  ok('A5-2 根回满幅（stacked 解除）', !/\bstacked\b/.test(s.panelClass) && s.panelAlpha === 1, `class="${s.panelClass}"`);
  ok('A5-3 deck 收回清空', s.open === false && s.M === 0 && !/live/.test(s.deckLayerClass));
  ok('A5-4 页签数不变', s.tabCount === nBase, `n=${s.tabCount} base=${nBase}`);
  const title = await page.evaluate(() => document.getElementById('canvas-title-name').textContent);
  ok('A5-5 标题切换为 bravo.md', title === 'bravo.md', `title=${title}`);
  await page.click('#deck-fab');      // 重开
  await settle(page);
  const s2 = await state(page);
  ok('A5-6 重开后新 canvas 在前景位', s2.open && s2.frontIdx === 0 && s2.frontName === 'bravo.md', `front=${s2.frontName}`);
  ok('R0 主文档无 JS 运行时错误（A5）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A6 浮钮固定（v5 A6 移植 + v5②）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  // offset* 布局盒对 transform 免疫（hover scale 1.05 / 亚像素合成只影响视觉 rect）——
  // 「钉死固定位」断言读布局盒才是判据本体
  const fabBox = () => page.evaluate(() => { const el = document.getElementById('deck-fab'); return { x: el.offsetLeft, y: el.offsetTop, w: el.offsetWidth, h: el.offsetHeight }; });
  const r0 = await fabBox();
  await wheelScrub(page, 240);
  const mid = await pollState(page, (s) => Math.abs(s.d0) > 0.3);
  const rMid = await fabBox();
  await settle(page);
  const rEnd = await fabBox();
  const same = (a, b) => a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h;
  ok('A6-1 擦洗轮换中浮钮布局盒逐像素恒定（v5②；offset* 对 hover 缩放免疫）', same(r0, rMid) && same(r0, rEnd),
    `r0=(${r0.x},${r0.y}) rMid=(${rMid.x},${rMid.y}) rEnd=(${rEnd.x},${rEnd.y})`);
  const z = await page.evaluate(() => getComputedStyle(document.getElementById('deck-fab')).zIndex);
  ok('A6-2 浮钮 z=250 恒在卡组与面板之上', z === '250', `z=${z}`);
  await page.click('#deck-fab');      // 退出
  await settle(page);
  const s1 = await state(page);
  ok('A6-3 点浮钮退出（deck 关、根回满幅、内容不变）',
    s1.open === false && !/\bstacked\b/.test(s1.panelClass) && s1.activeId.endsWith('alpha.md'));
  await page.click('#deck-fab');      // 再进
  await settle(page);
  const s2 = await state(page);
  ok('A6-4 再点进入', s2.open === true && s2.M === s2.tabCount, `open=${s2.open} M=${s2.M}`);
  ok('R0 主文档无 JS 运行时错误（A6）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A7 划走 canvas 前景（v5 A7 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const n0 = (await state(page)).tabCount;   // 开批基准（含首跑默认「插件」页签时为 10）
  const pr = await panelRect(page);
  const cx = pr.x + pr.width / 2, cy = pr.y + pr.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) await page.mouse.move(cx + i * 26, cy, { steps: 1 });
  await page.mouse.up();
  await settle(page, 3000);
  const s = await state(page);
  ok('A7-1 划走 canvas：页签 −1', s.tabCount === n0 - 1, `n=${s.tabCount} (base ${n0})`);
  ok('A7-2 邻位接管（alpha→bravo，同 closeTab 右邻优先）', s.activeId.endsWith('bravo.md'), `active=${s.activeId}`);
  ok('A7-3 deck 保持', s.open === true && s.M === s.tabCount);
  ok('A7-4 新 canvas 顶上（前景位）', s.frontIdx === 0 && s.frontName === 'bravo.md', `front=${s.frontName}`);
  ok('R0 主文档无 JS 运行时错误（A7）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A8 划走前景环卡（v5 A8 移植：就地收敛保相位）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const n0 = (await state(page)).tabCount;
  await wheelScrub(page, 240);        // bravo 前景
  await settle(page);
  const c = await cardRect(page, 1);
  const cx = c.x + c.width / 2, cy = c.y + c.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) await page.mouse.move(cx - i * 26, cy, { steps: 1 });
  await page.mouse.up();
  await settle(page, 3000);
  const s = await state(page);
  ok('A8-1 划走前景卡：该文件被关（−1）', s.tabCount === n0 - 1, `n=${s.tabCount} (base ${n0})`);
  ok('A8-2 被关的是 bravo（active 不变）', s.activeId.endsWith('alpha.md') && !s.tabs.includes('bravo.md'), `tabs=${s.tabs.join(',')}`);
  ok('A8-3 环流就地收敛：相位保持（pos 未重置）', approx(s.pos, 1, 0.01), `pos=${s.pos.toFixed(3)}`);
  ok('A8-4 后继卡顶上（charlie 前景）', s.frontIdx === 1 && s.frontName === 'charlie.md', `front=${s.frontName}`);
  ok('R0 主文档无 JS 运行时错误（A8）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A9 右键关深处卡（v5 A9 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const n0 = (await state(page)).tabCount;
  await wheelScrub(page, 240);
  await settle(page);
  // 深处卡只露出顶缘带（④②透视补偿后 = 设计阶梯 9px 档），右键必须落在露出的名称条上缘
  const c3 = await cardRect(page, 3);
  await page.click('.deck-card[data-r="3"]', { button: 'right', position: { x: Math.round(c3.width / 2), y: 3 } });
  await sleep(400);
  const menu = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    if (!m) return null;
    return [...m.querySelectorAll('button')].map((b) => b.textContent.trim());
  });
  ok('A9-1 右键菜单三项（引用/关闭/关闭其他）',
    !!menu && menu.length === 3 && menu[0].includes('引用') && menu[1] === '关闭' && menu[2] === '关闭其他',
    menu ? menu.join(' | ') : 'no-menu');
  await page.evaluate(() => {
    const btns = [...document.querySelectorAll('.canvas-tab-context-menu button')];
    btns.find((b) => b.textContent.trim() === '关闭')?.click();
  });
  await settle(page, 3000);
  const s = await state(page);
  ok('A9-2 深处卡被关（−1）', s.tabCount === n0 - 1 && !s.tabs.includes('delta.md'), `n=${s.tabCount}`);
  ok('A9-3 相位保持（pos 未重置）', approx(s.pos, 1, 0.01), `pos=${s.pos.toFixed(3)}`);
  ok('R0 主文档无 JS 运行时错误（A9）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A10 uifix ③d/③e：竖排提示退场 + 卡面 × 关闭钮（作者最新口径）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);                  // pos=0：canvas 前景
  const s0 = await state(page);
  /* ③d：竖排「滑动关闭」提示文字整体退场（面板面 + 卡面零命中） */
  const noHint = await page.evaluate(() => ({
    panel: document.querySelectorAll('.deck-swipe-hint').length,
    card: document.querySelectorAll('.card-swipe-hint').length,
    anyText: [...document.querySelectorAll('#deck-layer *')].some((el) => el.textContent === '滑动关闭'),
  }));
  ok('A10-0 竖排滑动提示零命中（面板 + 卡面 + 文案扫描）',
    noHint.panel === 0 && noHint.card === 0 && !noHint.anyText, JSON.stringify(noHint));
  const x0 = await page.evaluate(() => {
    const xs = [...document.querySelectorAll('.deck-card .deck-card-x')];
    const first = xs[0];
    const st = first ? getComputedStyle(first) : null;
    return { count: xs.length, cards: document.querySelectorAll('.deck-card').length,
             visible: st ? st.display !== 'none' && parseFloat(st.opacity) > 0.2 : false,
             cvClose: document.querySelectorAll('.cv-close').length };
  });
  ok('A10-1 卡面 × 钮 = 卡数（每卡一枚；.cv-close 仍零命中）',
    x0.count === x0.cards && x0.visible && x0.cvClose === 0, JSON.stringify(x0));
  /* ③e 通路：× 点击 = 关闭该卡（前景卡 = 面板收拢淡出，deck 收回 active 换邻位） */
  const nBase = s0.tabCount;
  await page.evaluate(() => document.querySelector('.deck-card[data-r="1"] .deck-card-x').click());
  await settle(page, 3200);
  const s1 = await state(page);
  ok('A10-2 × 点击深处卡 = 关闭该卡（−1 · deck 保持 · 相位收敛）',
    s1.tabCount === nBase - 1 && s1.open === true && !s1.tabs.includes('bravo.md'),
    `n=${s1.tabCount}/${nBase} open=${s1.open}`);
  ok('A10-3 × 点击不换签（active 仍 alpha.md · closeTab 邻位语义留给后续前景轮换）',
    s1.activeId.endsWith('alpha.md'), `active=${s1.activeId}`);
  const xAfter = await state(page);
  ok('A10-4 × 钮随环重建补齐（卡数 −1 后仍每卡一枚）', xAfter.cardXButtons === xAfter.M,
    `x=${xAfter.cardXButtons} M=${xAfter.M}`);
  /* 前景卡 = canvas 本尊（active 页签）×：面板收拢淡出 → 邻位顶前景、deck 保持
     （v4⑥ shrink 通路既有契约；「收回」= ESC / 点 canvas 专用，A11/A12 已钉）。
     A10-2 关闭深处 bravo 后环重绑，pos 仍 0 = alpha 自卡前景（r0）——直接点自卡 ×，
     不再 scrub（回滚会把环尾绕回卡转到前景，走深卡关闭通路，非本断言对象）。 */
  const n2 = (await state(page)).tabCount;
  await page.evaluate(() => {
    const self = document.querySelector('.deck-card[data-r="0"] .deck-card-x');
    self.click();
  });
  await settle(page, 3200);
  const s2 = await state(page);
  ok('A10-5 前景卡 × = 当前页签关闭（−1 · 面板收拢 · 邻位 charlie 顶前景 · deck 保持）',
    s2.tabCount === n2 - 1 && s2.open === true && s2.frontName === 'charlie.md',
    `n=${s2.tabCount}/${n2} open=${s2.open} front=${s2.frontName}`);
  ok('R0 主文档无 JS 运行时错误（A10）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A11 轮换态收回（v5 A11 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 480);        // 前景到 charlie
  await settle(page);
  await page.keyboard.press('Escape');
  await settle(page);
  const s = await state(page);
  ok('A11-1 ESC：deck 关', s.open === false && s.M === 0);
  ok('A11-2 activeId 不变（alpha.md）', s.activeId.endsWith('alpha.md'), `active=${s.activeId}`);
  ok('A11-3 根回满幅', !/\bstacked\b/.test(s.panelClass) && s.panelAlpha === 1);
  ok('R0 主文档无 JS 运行时错误（A11）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A12 点 canvas 前景 = 收回（v5 A12 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const nBase = await page.evaluate(() => window.__canvasDeck.state().tabCount);
  await page.click('#deck-fab');
  await settle(page);
  const pr = await panelRect(page);
  await page.mouse.click(pr.x + pr.width / 2, pr.y + pr.height / 2);
  await settle(page);
  const s = await state(page);
  ok('A12-1 点 canvas（前景位）= 收回卡组', s.open === false, `open=${s.open}`);
  ok('A12-2 内容不变', s.activeId.endsWith('alpha.md') && s.tabCount === nBase, `n=${s.tabCount} active=${s.activeId}`);
  ok('R0 主文档无 JS 运行时错误（A12）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A13 窄档（v5 A13 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '340px'));
  await sleep(700);
  await page.mouse.move(200, 500);   // 移开鼠标：fab hover scale 会让 40px 测量读成 42
  await page.click('#deck-fab');
  await settle(page);
  const pr = await panelRect(page);
  const stackedFrontTop = pr.y + (pr.height - pr.height * 0.85) / 2 + 8;
  const c1 = await cardRect(page, 1);
  ok('A13-1 窄档 340：后卡 = 85% 基 × 近档缩小（±4%）', approx(pr.width, 340, 3) &&
    approx(c1.width, 340 * 0.85 * (1 - 0.055) * (1150 / (1150 + 26)), 340 * 0.85 * 0.04),
    `area=${pr.width.toFixed(0)} card=${c1.width.toFixed(0)}`);
  /* UI 批 ④②：窄档同享透视补偿 —— 投影顶 = 设计顶，露边 27px 档真实可测。 */
  ok('A13-2 露边 27px 仍在（v2 档；④②透视补偿后投影顶=设计顶，±4px）',
    approx(stackedFrontTop - c1.y, 27, 4),
    `peek=${(stackedFrontTop - c1.y).toFixed(1)}`);
  await page.mouse.move(200, 500);   // page.click 后光标停在 fab 上：hover scale 1.05 → 视觉 rect 42×42
  const fab = await page.evaluate(() => {
    const el = document.getElementById('deck-fab');
    return { w: el.offsetWidth, h: el.offsetHeight, x: el.offsetLeft, y: el.offsetTop };  // 布局盒对 transform 免疫
  });
  ok('A13-3 浮钮仍固定 40px 原位（不缩放不移位；布局盒口径；移位批 2026-10-22 钉区右下 bottom16）',
    fab.w === 40 && fab.h === 40 && approx(pr.bottom - (fab.y + fab.h), 16, 1.5) && approx(pr.right - (fab.x + fab.w), 12, 1.5),
    `${fab.w}x${fab.h} db=${(pr.bottom - fab.y - fab.h).toFixed(1)} dr=${(pr.right - fab.x - fab.w).toFixed(1)}`);
  /* UI 批 ①：窄档不再有提示行可藏——deck-foot 整体退役（几何与宽档同规）。 */
  const footGoneNarrow = await page.evaluate(() => !document.getElementById('deck-foot'));
  ok('A13-4 窄档帮助条退役（deck-foot 零命中）', footGoneNarrow === true);
  await page.screenshot({ path: join(SHOTS_DIR, 'light-narrow-340.png'), clip: { x: pr.x - 8, y: pr.y - 8, width: pr.width + 16, height: pr.height + 16 } });
  ok('R0 主文档无 JS 运行时错误（A13）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A14 主题/降级（v5 A14 移植 + 精修②dark token）════════ */
{
  const { ctx, page, errors } = await bootPage(browser, { colorScheme: 'dark' });
  const shadowDark = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--card-shadow').trim());
  ok('A14-1 暗档 --card-shadow token 镜像（精修② 0.20/0.24/0.26）',
    /rgba\(0,\s*0,\s*0,\s*0\.20?\)/.test(shadowDark) && /0\.24/.test(shadowDark) && /0\.26/.test(shadowDark), shadowDark);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const pr = await panelRect(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'dark-deck-open.png'), clip: { x: pr.x - 8, y: pr.y - 8, width: pr.width + 16, height: pr.height + 16 } });
  await wheelScrub(page, 240);
  await sleep(140);
  await page.screenshot({ path: join(SHOTS_DIR, 'dark-scrub-mid.png'), clip: { x: pr.x - 8, y: pr.y - 8, width: pr.width + 16, height: pr.height + 16 } });
  await settle(page);
  const s = await state(page);
  ok('A14-2 暗档轮换正常（pos→1）', approx(s.pos, 1, 0.01), `pos=${s.pos.toFixed(3)}`);
  await ctx.close();
  // prefers-reduced-motion
  const ctx2 = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 1600, height: 1000 } });
  await ctx2.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page2 = await ctx2.newPage();
  await page2.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page2);
  await page2.goto(BASE + '/index.html');
  await page2.waitForSelector('#activity-bar', { timeout: 15000 });
  const dur = await page2.evaluate(() => getComputedStyle(document.getElementById('canvas-panel')).transitionDuration);
  ok('A14-3 prefers-reduced-motion 面板过渡 0.01s', /\b0\.01s\b/.test(dur), dur);
  await ctx2.close();
}

/* ════════ A15 环绕回归（v5 A15 移植）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const M = (await state(page)).M;
  await wheelScrub(page, 240 * M);    // 连滚整圈（M 格）
  await settle(page, 4000);
  const s = await state(page);
  ok('A15-1 连滚整圈回到 canvas 前景（pos=M、frontIdx=0）', approx(s.pos, M, 0.01) && s.frontIdx === 0 && s.frontName === 'alpha.md',
    `pos=${s.pos.toFixed(3)} M=${M} front=${s.frontName}`);
  ok('A15-2 面板回前景位（alpha 1、over 落下、inline 清空）', s.panelAlpha === 1 && s.deckOver === false && s.d0 === 0);
  ok('A15-3 首尾无缝（d₀=0）', s.d0 === 0 && s.selfCardHidden === true);
  ok('R0 主文档无 JS 运行时错误（A15）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 精修① 动画再顺滑一档（常量单一来源断言）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const s = await state(page);
  const c = s.consts;
  ok('F1-1 rAF 趋近系数 10/7 → 12/9', c.scrubApproach === 12 && c.spreadApproach === 9,
    `scrub=${c.scrubApproach} spread=${c.spreadApproach}`);
  ok('F1-2 翻落曲线收幅 30/40/6/30 → 26/32/5/24',
    c.fallDrop === 26 && c.fallArc === 32 && c.fallTilt === 5 && c.fallTiltArc === 24,
    `drop=${c.fallDrop} arc=${c.fallArc} tilt=${c.fallTilt} tiltArc=${c.fallTiltArc}`);
  ok('F1-3 FX/morph 时长统一 0.28s 档（fly 0.30 / ghost 0.28 / spring 0.22 / fling 0.24）',
    c.flyDur === 0.3 && c.ghostDur === 0.28 && c.springDur === 0.22 && c.flingDur === 0.24,
    `fly=${c.flyDur} ghost=${c.ghostDur} spring=${c.springDur} fling=${c.flingDur}`);
  const dur = await page.evaluate(() => getComputedStyle(document.getElementById('canvas-panel')).transitionDuration);
  ok('F1-4 面板 morph 过渡 0.28s（CSS 单一来源 --deck-morph-duration）', /\b0\.28s\b/.test(dur), dur);
  ok('R0 主文档无 JS 运行时错误（F1）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 精修② 背景阴影浅一档（token 值断言）════════ */
{
  const { ctx, page, errors } = await bootPage(browser, { colorScheme: 'light' });
  await openAllTabs(page);
  const token = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--card-shadow').trim());
  const alphas = [...token.matchAll(/rgba\([^)]*[\d.]\)/g)].map((m) => parseFloat(m[0].replace(/^rgba\([^,]+,[^,]+,[^,]+,\s*/, '').replace(')', '')));
  // 样张 light：0.05 / 0.10 / 0.12 —— 精修②每档严格更浅
  ok('F2-1 light --card-shadow 三档（0.04/0.07/0.08）且每档 < 样张值',
    alphas.length === 3 && alphas[0] === 0.04 && alphas[1] === 0.07 && alphas[2] === 0.08 &&
    alphas[0] < 0.05 && alphas[1] < 0.10 && alphas[2] < 0.12, token);
  const stackedShadow = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    p.classList.add('no-trans', 'stacked');
    const sh = getComputedStyle(p).boxShadow;
    p.classList.remove('no-trans', 'stacked');
    return sh;
  });
  const sAlphas = [...stackedShadow.matchAll(/rgba\([^)]+,\s*([\d.]+)\)/g)].map((m) => parseFloat(m[1]));
  /* UI 批 ⑤：.stacked 投影 = 羽化前景档 token（v2：环境晕 0.02 + 0.05/0.10/0.12，
     模糊半径放大、不透明度降档——边缘无硬边）；末三层锚前景档本体。 */
  ok('F2-2 .stacked 投影锚 UI 批⑤ 羽化前景档（v2 环境晕 0.02 + 0.05/0.10/0.12）',
    sAlphas.slice(-4).join(',') === '0.02,0.05,0.1,0.12', sAlphas.join(','));
  /* UI 批 ⑤：卡描边退役 + 投影羽化——需真实卡组在挂（deck 开启后 data-dim 档生效）。
     box-shadow 计算值 = color-first 序列化（rgba(...) x y blur spread …），逐层取第
     3 个长度（blur）求最大，锚羽化档最大模糊 110px。 */
  await page.click('#deck-fab');
  await settle(page);
  const cardEdge = await page.evaluate(() => {
    const c = document.querySelector('.deck-card[data-dim="0"]') || document.querySelector('.deck-card');
    if (!c) return null;
    const st = getComputedStyle(c);
    const shadow = st.boxShadow;
    const blurs = [...shadow.matchAll(/rgba\([^)]+\)\s*((?:-?\d+(?:\.\d+)?px\s*)+)/g)].map((m) => {
      const nums = m[1].trim().split(/\s+/).map((s) => parseFloat(s));
      return nums[2] || 0;   // x y blur spread → blur
    });
    return { borderColor: st.borderColor, maxBlur: Math.max(...blurs), shadeBg: getComputedStyle(c.querySelector('.card-shade')).backgroundImage };
  });
  ok('F2-3 卡描边退役 + 投影羽化（边框透明、最大模糊 ≥90px、shade 为纵向渐变）',
    !!cardEdge && cardEdge.maxBlur >= 90 && cardEdge.shadeBg.includes('linear-gradient'),
    JSON.stringify(cardEdge));
  // 三版 look 联动：setLook 热切换 → .stacked 前景档 token 随 body 类变化（作者挑版通道）
  const looks = {};
  for (const v of ['v1', 'v2', 'v3']) {
    await page.evaluate((vv) => window.__canvasDeck.setLook(vv), v);
    await sleep(60);
    looks[v] = await page.evaluate(() => {
      const p = document.getElementById('canvas-panel');
      p.classList.add('no-trans', 'stacked');
      const sh = getComputedStyle(p).boxShadow;
      p.classList.remove('no-trans', 'stacked');
      return [...sh.matchAll(/rgba\([^)]+,\s*([\d.]+)\)/g)].map((m) => parseFloat(m[1])).join(',');
    });
  }
  await page.evaluate(() => window.__canvasDeck.setLook('v2'));
  ok('F2-4 三版 look 前景档阴影互异（作者挑版通道 · setLook 热切换）',
    new Set(Object.values(looks)).size === 3, JSON.stringify(looks));
  ok('R0 主文档无 JS 运行时错误（F2）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 换面后常态（换入后截图 + 头行标题）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 240);
  await settle(page);
  await page.evaluate(() => document.querySelector('.deck-card[data-r="1"]').click());
  await settle(page, 3200);
  const pr = await panelRect(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'light-after-swap.png'), clip: { x: pr.x - 8, y: pr.y - 8, width: pr.width + 16, height: pr.height + 16 } });
  const title = await page.evaluate(() => document.getElementById('canvas-title-name').textContent);
  ok('A16-1 换面后常态头居中标题=bravo.md', title === 'bravo.md', `title=${title}`);
  ok('R0 主文档无 JS 运行时错误（A16）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A17 竖拖换卡（面板面 · Pointer Events 统一通路 · 作者补充令）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const s0 = await state(page);
  const c = s0.consts;
  ok('A17-0 竖拖常量单一来源（gain 1/160 · 惯性 90 冲程/±80px 钳 · 轴锁 8px）',
    c.dragGain === 1 / 160 && c.dragInertia === 90 && c.dragInertiaMax === 80 && c.axisLockPx === 8,
    JSON.stringify(c));
  const hintGone = await page.evaluate(() => ({
    hint: document.querySelector('.deck-hint')?.textContent || '',
    foot: !!document.getElementById('deck-foot'),
  }));
  ok('A17-0b 提示行退役（UI 批 ①：竖拖换卡交互自学，不再有文案行）',
    hintGone.hint === '' && !hintGone.foot,
    `hint="${hintGone.hint}" foot=${hintGone.foot}`);
  await page.click('#deck-fab');
  await settle(page);
  const pr = await panelRect(page);
  const cx = Math.round(pr.x + pr.width / 2), cy = Math.round(pr.y + pr.height / 2);
  await pointerDrag(page, cx, cy, 0, 260, { hold: true });   // 竖向下拖 260px，按住不松
  const mid = await state(page);
  ok('A17-1 拖动中主导轴锁 y（面板面手势上报）', !!mid.gesture && mid.gesture.face === 'panel' && mid.gesture.axis === 'y',
    JSON.stringify(mid.gesture));
  ok('A17-2 跟手：target 随 dy 增量增长（>0.5 卡 · 未松手未换签）', mid.target > 0.5 && mid.activeId.endsWith('alpha.md'),
    `target=${mid.target.toFixed(3)} active=${mid.activeId}`);
  await page.mouse.up();                                     // 松手：少量惯性 + 贴靠
  await settle(page);
  const s1 = await state(page);
  ok('A17-3 竖拖换卡：pos 贴靠 2、charlie 到前景（惯性钳半卡内，落点确定）',
    approx(s1.pos, 2, 0.01) && s1.frontIdx === 2 && s1.frontName === 'charlie.md',
    `pos=${s1.pos.toFixed(3)} front=${s1.frontName}`);
  ok('A17-4 是换卡不是关闭（页签数不变 · deck 保持 · 无面板 FX）',
    s1.tabCount === s0.tabCount && s1.open === true && s1.faceFx === null,
    `n=${s1.tabCount}/${s0.tabCount} open=${s1.open} faceFx=${s1.faceFx}`);
  ok('R0 主文档无 JS 运行时错误（A17 鼠标）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();

  /* 触屏腿：CDP touch → pointerType:'touch' 的 Pointer Events —— 同一条拖划公共腿。
     触点在 .stacked 面板（touch-action:none）上，浏览器不接管纵向手势。 */
  const t = await bootPage(browser);
  await openAllTabs(t.page);
  await t.page.click('#deck-fab');
  await settle(t.page);
  const t0 = await state(t.page);
  const tpr = await panelRect(t.page);
  const tx = Math.round(tpr.x + tpr.width / 2), ty = Math.round(tpr.y + tpr.height / 2);
  const cdp = await t.ctx.newCDPSession(t.page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: tx, y: ty, id: 1 }] });
  for (let i = 1; i <= 10; i++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: tx, y: ty + i * 26, id: 1 }] });
    await sleep(40);
  }
  const tmid = await state(t.page);
  ok('A17-5 触屏竖拖：touch 指针走同一手势通路（轴锁 y · 跟手）',
    !!tmid.gesture && tmid.gesture.face === 'panel' && tmid.gesture.axis === 'y' && tmid.target > 0.5,
    JSON.stringify({ g: tmid.gesture, target: +tmid.target.toFixed(3) }));
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
  await settle(t.page);
  const t1 = await state(t.page);
  ok('A17-6 触屏竖拖换卡（pos 贴靠 2 · charlie 前景 · 不关闭）',
    approx(t1.pos, 2, 0.01) && t1.frontName === 'charlie.md' && t1.tabCount === t0.tabCount && t1.open === true,
    `pos=${t1.pos.toFixed(3)} front=${t1.frontName} n=${t1.tabCount}`);
  ok('R0 主文档无 JS 运行时错误（A17 触屏）', t.errors.length === 0, t.errors.slice(0, 2).join(' | '));
  await t.ctx.close();
}

/* ════════ A18 环卡面分工：竖拖换卡 / 横滑关闭（作者补充令 ②）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const n0 = (await state(page)).tabCount;
  await wheelScrub(page, 240);            // bravo 到前景（环卡面）
  await settle(page);
  const c1 = await cardRect(page, 1);
  const cx = Math.round(c1.x + c1.width / 2), cy = Math.round(c1.y + c1.height / 2);
  await pointerDrag(page, cx, cy, 0, 260, { hold: true });   // 纵向下拖前景环卡
  const mid = await state(page);
  ok('A18-1 环卡面纵拖：手势上报 face=card · 轴锁 y', !!mid.gesture && mid.gesture.face === 'card' && mid.gesture.axis === 'y',
    JSON.stringify(mid.gesture));
  await page.mouse.up();
  await settle(page);
  const s1 = await state(page);
  ok('A18-2 环卡竖拖换卡：pos 贴靠 3、delta 到前景', approx(s1.pos, 3, 0.01) && s1.frontIdx === 3 && s1.frontName === 'delta.md',
    `pos=${s1.pos.toFixed(3)} front=${s1.frontName}`);
  ok('A18-3 纵向主导不触发关闭（页签数不变 · delta 仍在 · 无面板 FX）',
    s1.tabCount === n0 && s1.tabs.includes('delta.md') && s1.faceFx === null,
    `n=${s1.tabCount}/${n0} tabs=${s1.tabs.join(',')}`);
  const c3 = await cardRect(page, 3);     // delta 已在前景位
  const dx = Math.round(c3.x + c3.width / 2), dy = Math.round(c3.y + c3.height / 2);
  await pointerDrag(page, dx, dy, -312, 0, { steps: 12, gap: 0 });  // 横向主导左滑（原 A8 通路）
  await settle(page, 3000);
  const s2 = await state(page);
  ok('A18-4 横向主导仍是划走关闭：delta 被关（−1）', s2.tabCount === n0 - 1 && !s2.tabs.includes('delta.md'),
    `n=${s2.tabCount} (base ${n0})`);
  ok('A18-5 环流就地收敛保相位：echo 顶上（pos=3）、active 不变',
    approx(s2.pos, 3, 0.01) && s2.frontName === 'echo.md' && s2.activeId.endsWith('alpha.md'),
    `pos=${s2.pos.toFixed(3)} front=${s2.frontName} active=${s2.activeId}`);
  ok('R0 主文档无 JS 运行时错误（A18）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A19 主导轴判定（作者补充令 ②③：横纵分工 + 死区 + 让路面不变）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);
  const n0 = (await state(page)).tabCount;
  const pr = await panelRect(page);
  const cx = Math.round(pr.x + pr.width / 2), cy = Math.round(pr.y + pr.height / 2);
  /* (a) dy 主导对角（dx=120 恒在参与）→ 锁 y → 换卡不关闭 */
  await pointerDrag(page, cx, cy, 120, 260, { hold: true });
  const midY = await state(page);
  ok('A19-1 对角 dy 主导：锁 y（|dx|>|dy| 才横）', !!midY.gesture && midY.gesture.axis === 'y',
    JSON.stringify(midY.gesture));
  await page.mouse.up();
  await settle(page);
  const sa = await state(page);
  ok('A19-2 dy 主导对角 = 换卡不关闭（charlie 前景 · 页签数不变）',
    approx(sa.pos, 2, 0.01) && sa.frontName === 'charlie.md' && sa.tabCount === n0,
    `pos=${sa.pos.toFixed(3)} front=${sa.frontName} n=${sa.tabCount}`);
  /* (b) dx 主导对角 → 锁 x → 划走关闭（原逻辑） */
  await wheelScrub(page, -480);           // 回滚 2 格：alpha 回前景（面板面复现）
  await settle(page);
  await pointerDrag(page, cx, cy, 312, 96, { steps: 12, hold: true });
  const midX = await state(page);
  ok('A19-3 对角 dx 主导：锁 x', !!midX.gesture && midX.gesture.axis === 'x', JSON.stringify(midX.gesture));
  await page.mouse.up();
  await settle(page, 3000);
  const sb = await state(page);
  ok('A19-4 dx 主导对角 = 划走关闭（alpha 被关 · 邻位 bravo 顶上）',
    sb.tabCount === n0 - 1 && !sb.tabs.includes('alpha.md') && sb.activeId.endsWith('bravo.md'),
    `n=${sb.tabCount} (base ${n0}) active=${sb.activeId}`);
  ok('A19-5 关闭后 deck 保持、新 canvas 前景位（环重置 pos=0）',
    sb.open === true && sb.frontIdx === 0 && sb.frontName === 'bravo.md',
    `open=${sb.open} front=${sb.frontName}`);
  /* (c) ≤8px 死区：不锁轴、不驱动；松手 = 点击语义（点 canvas = 收回卡组，原界不变） */
  await pointerDrag(page, cx, cy, 4, 4, { steps: 1, gap: 0, hold: true });
  const midD = await state(page);
  ok('A19-6 死区内不锁轴不驱动（axis null · target 未动）',
    !!midD.gesture && midD.gesture.axis === null && midD.target === 0,
    JSON.stringify({ g: midD.gesture, target: midD.target }));
  await page.mouse.up();
  await settle(page);
  const sc = await state(page);
  ok('A19-7 死区松手 = 点击语义（deck 收回），换卡/关闭均未发生',
    sc.open === false && sc.tabCount === n0 - 1 && sc.activeId.endsWith('bravo.md'),
    `open=${sc.open} n=${sc.tabCount}`);
  ok('R0 主文档无 JS 运行时错误（A19）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A20 堆叠按钮连点稳定（UI 批 ②：spec 钉，含快速连点场景）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  /* (a) 快速三连点：开 → 收（finalizing 窗口） → 排队——收场后补发，终态=开。
     旧实现第 2/3 点被 finalizing 守卫静默丢弃（终态=关且第 3 点丢失）。 */
  await page.click('#deck-fab');
  await page.click('#deck-fab');       // deck 刚开即再点（收场 300ms 窗口内）
  await page.click('#deck-fab');       // 第 3 点：finalizing 中 → 应登记排队
  await settle(page);
  const sA = await state(page);
  ok('A20-1 快速三连点每次计数（终态=开：开→收→排队补发）',
    sA.open === true && sA.M === sA.tabCount, `open=${sA.open} M=${sA.M} queued=${sA.fabQueued}`);
  ok('A20-2 排队已清空（补发后 fabQueued=false）', sA.fabQueued === false);
  // 第 4 点照常翻转（连点后不粘死）
  await page.click('#deck-fab');
  await settle(page);
  const sB = await state(page);
  ok('A20-3 连点后第 4 点照常翻转（终态=关，不粘）', sB.open === false && sB.tabCount === sA.tabCount);
  /* (b) 重入锁泄漏回归：竖拖进行中按 ESC（closeDeck 收场）→ 收场必须清
     deck.cardDrag——旧实现残留 flag 让 fab 永久吞点击（真 bug 成因 a）。 */
  await page.click('#deck-fab');
  await settle(page);
  await wheelScrub(page, 240);         // bravo 到前景（环卡面可拖）
  await settle(page);
  const c1 = await cardRect(page, 1);
  const gx = Math.round(c1.x + c1.width / 2), gy = Math.round(c1.y + c1.height / 2);
  await pointerDrag(page, gx, gy, 0, 120, { steps: 4, hold: true });   // 按住纵拖（轴已锁）
  await page.keyboard.press('Escape'); // 拖划进行中收场（旧实现：cardDrag 残留）
  await page.mouse.up();
  await settle(page);
  const sC = await state(page);
  ok('A20-4 收场清重入锁（gesture=null · deck 关）', sC.gesture === null && sC.open === false,
    `gesture=${JSON.stringify(sC.gesture)} open=${sC.open}`);
  await page.click('#deck-fab');       // 旧实现此处 fab 永久无响应
  await settle(page);
  const sD = await state(page);
  ok('A20-5 泄漏修复后 fab 照常响应（deck 重开）', sD.open === true && sD.M === sD.tabCount,
    `open=${sD.open} M=${sD.M}`);
  ok('R0 主文档无 JS 运行时错误（A20）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A21 动效层（UI 批 ④：缩放坡道常量单一来源 + 轮换中间帧位姿）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const c0 = (await state(page)).consts;
  ok('A21-0 动效层常量单一来源（近档 0.055 / 深档 0.018 / 视差 z 26 / 翻落缩 0.10 / 透视 1150·origin 0.10）',
    c0.depthScaleNear === 0.055 && c0.depthScaleFar === 0.018 && c0.depthZ === 26 && c0.fallShrink === 0.1 &&
    c0.perspective === 1150 && c0.originYK === 0.1,
    JSON.stringify(c0));
  await page.click('#deck-fab');
  await settle(page);
  const pr = await panelRect(page);
  const f1 = await cardRect(page, 1);   // d=1（前一位）
  const f2 = await cardRect(page, 2);   // d=2
  const base = pr.width * 0.85;
  ok('A21-1 前景位基姿不变（d=0 卡与缩放态 canvas 同位同宽——交接窗换手无跳变）',
    approx(f1.width / base, (1 - 0.055) * (1150 / (1150 + 26)), 0.03),
    `d1/base=${(f1.width / base).toFixed(3)}`);
  ok('A21-2 近大远小连续（d2 < d1 < 基卡，单调坡道）',
    f2.width < f1.width && f1.width < base, `d1=${f1.width.toFixed(0)} d2=${f2.width.toFixed(0)} base=${base.toFixed(0)}`);
  ok('A21-3 透明度渐变仍在（深处卡 op 随深衰减）', f2.op < 1 && f1.op > f2.op,
    `op1=${f1.op} op2=${f2.op}`);
  /* 翻落支：canvas 本尊（d=-1）缩小 + 显著衰减 */
  await wheelScrub(page, 240);
  await settle(page);
  const fallen = await cardRect(page, 0);
  ok('A21-4 翻落支缩小（d=-1 卡宽 < 基卡 × 0.94）', fallen !== null && fallen.width < base * 0.94,
    `fallen=${fallen && fallen.width.toFixed(0)} base=${base.toFixed(0)}`);
  ok('R0 主文档无 JS 运行时错误（A21）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ A22 快照活性窗（UI 批 ③：真容升级 + 可视卡 ±2 精渲染）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page, 3200);
  const snap0 = await page.evaluate(() => ({
    hasSnap: document.querySelectorAll('.card-thumb.has-snap').length,
    cards: document.querySelectorAll('.deck-card').length,
    pending: document.querySelectorAll('.card-thumb[data-snap="pending"]').length,
    mock: document.querySelectorAll('.card-thumb[data-snap="mock"]').length,
  }));
  /* 真容补齐：可克隆族（md）全挂真容快照（pending 零），面板族页签回落类型 mock */
  ok('A22-1 全环真容补齐（md 族全克隆零 pending；面板族 mock 回落）',
    snap0.pending === 0 && snap0.hasSnap === snap0.cards - snap0.mock && snap0.hasSnap >= 1,
    JSON.stringify(snap0));
  /* 活性窗：可视卡 ±2 挂同步（环 ≥9 卡 → 窗内 5），深处卡静态快照 */
  const sWin = await state(page);
  ok('A22-2 活性窗 = 可视卡 ±2（环 ≥9 → 窗内 5）', sWin.snapshots.liveWindow === 5,
    JSON.stringify(sWin.snapshots));
  /* 源滚动 → 缩略镜像（滚动中同步更新）：先灌长文使 scroller 可滚 */
  const mirrored = await page.evaluate(async () => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const viewer = pane.querySelector('.canvas-md-viewer');
    viewer.insertAdjacentHTML('beforeend', Array.from({ length: 40 }, (_, i) => '<p>填充段落' + i + '</p>').join(''));
    await new Promise((r) => setTimeout(r, 900));   // 等变更重截落定
    const scroller = pane.querySelector('.canvas-md-scroll') || pane;
    scroller.scrollTop = 220;
    scroller.dispatchEvent(new Event('scroll', { bubbles: false }));
    await new Promise((r) => setTimeout(r, 200));
    const inner = document.querySelector('.deck-card[data-r="0"] .card-snap-inner');
    return inner ? inner.style.transform : '';
  });
  ok('A22-3 源滚动镜像（缩略 translateY 随 scrollTop 同步）', /translateY\(-/.test(mirrored), mirrored);
  /* 源 DOM 变更 → 防抖重截（实时映射） */
  const reCloned = await page.evaluate(async () => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const viewer = pane.querySelector('.canvas-md-viewer');
    viewer.insertAdjacentHTML('afterbegin', '<p id="ui5-live-marker">活性窗重截标记段</p>');
    await new Promise((r) => setTimeout(r, 900));
    const inner = document.querySelector('.deck-card[data-r="0"] .card-snap-inner');
    return inner ? inner.textContent.includes('活性窗重截标记段') : false;
  });
  ok('A22-4 源变更防抖重截（近卡实时映射）', reCloned === true);
  ok('R0 主文档无 JS 运行时错误（A22）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();

console.log(results.join('\n'));
console.log(`\ncanvas-stack: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
