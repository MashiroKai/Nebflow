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
async function wheelScrub(page, dy) {
  await page.evaluate(() => {}); // flush
  await page.mouse.move(200, 500);
  await page.mouse.wheel(0, dy);
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
  ok('A1-5 浮钮钉区右上 top10/right12/40px',
    approx(fab.y - pr.y, 10, 1.5) && approx(pr.right - (fab.x + fab.w), 12, 1.5) && fab.w === 40 && fab.h === 40,
    `dy=${(fab.y - pr.y).toFixed(1)} dr=${(pr.right - fab.x - fab.w).toFixed(1)} ${fab.w}x${fab.h}`);
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
  ok('A2-5 d=1 露边 22px', approx(stackedFrontTop - c1.y, 22, 3.5), `peek=${(stackedFrontTop - c1.y).toFixed(1)}px`);
  ok('A2-6 后卡 85%（与缩放态 canvas 同尺寸；透视投影微放宽容差）',
    approx(c1.width, pr.width * 0.85, 8) && approx(c1.height, pr.height * 0.85, 8),
    `${c1.width.toFixed(0)}x${c1.height.toFixed(0)} vs ${(pr.width * 0.85).toFixed(0)}x${(pr.height * 0.85).toFixed(0)}`);
  const c3 = await cardRect(page, 3);
  ok('A2-7 d1 vs d3 宽差 <2.5%（无逐级缩放通道；微后仰投影偏差随区高）', Math.abs(c1.width - c3.width) / c1.width < 0.025,
    `dw=${Math.abs(c1.width - c3.width).toFixed(1)}px`);
  ok('A2-8 canvas 前景时 .over 未抬', s.deckOver === false);
  const count = await page.evaluate(() => document.querySelector('#deck-foot .deck-count').textContent);
  const alphaStrip = s.tabs.indexOf('alpha.md') + 1;
  ok('A2-9 计数行「前景 <strip>/N · alpha.md · 下一张 bravo.md」（自校准）',
    count.includes(`前景 ${alphaStrip}/${s.tabCount}`) && count.includes('alpha.md') && count.includes('下一张 bravo.md'), count);
  const footOn = await page.evaluate(() => {
    const f = document.getElementById('deck-foot');
    return f.classList.contains('on') && parseFloat(getComputedStyle(f).opacity) > 0.9;
  });
  ok('A2-10 foot 浮现（on + opacity→1）', footOn === true);
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
  const count = await page.evaluate(() => document.querySelector('#deck-foot .deck-count').textContent);
  const bravoStrip = s1.tabs.indexOf('bravo.md') + 1;
  ok('A3-11 计数行「前景 <bravo strip>/N」（自校准）', count.includes(`前景 ${bravoStrip}/`) && count.includes('bravo.md'), count);
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
  // 深处卡只露出顶缘 6px 带（peek 阶梯），右键必须落在露出的名称条上缘
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

/* ════════ A10 精修③：× 退役 → 竖排浅色滑动提示 ════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await settle(page);                  // pos=0：canvas 前景 —— 面板面提示须在此测量
  const panelHint = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    const el = p.querySelector('.deck-swipe-hint');
    if (!el) return null;
    const st = getComputedStyle(el);
    return { wm: st.writingMode, op: st.opacity, stacked: p.classList.contains('stacked'), front: p.classList.contains('is-front') };
  });
  ok('A10-5 面板面（canvas 前景）同款竖排提示（stacked+is-front 浮现）',
    !!panelHint && panelHint.stacked && panelHint.front && panelHint.wm === 'vertical-rl' && parseFloat(panelHint.op) > 0.3,
    panelHint ? `wm=${panelHint.wm} op=${panelHint.op} stacked=${panelHint.stacked} front=${panelHint.front}` : 'missing');
  await wheelScrub(page, 240);        // bravo 前景（环卡面）
  await settle(page);
  const noClose = await page.evaluate(() => ({
    cvClose: document.querySelectorAll('.cv-close').length,
    cardClose: document.querySelectorAll('.deck-card .canvas-tab-close, .deck-card button').length,
  }));
  ok('A10-1 叉号零命中（.cv-close / 卡内关闭钮均不存在）', noClose.cvClose === 0 && noClose.cardClose === 0,
    `cv=${noClose.cvClose} card=${noClose.cardClose}`);
  const hint = await page.evaluate(() => {
    const el = document.querySelector('.deck-card.front .card-swipe-hint');
    if (!el) return null;
    const st = getComputedStyle(el);
    return { text: el.textContent, wm: st.writingMode, op: st.opacity, color: st.color, fs: st.fontSize };
  });
  ok('A10-2 前景卡竖排滑动提示存在（writing-mode: vertical-rl）', !!hint && hint.wm === 'vertical-rl',
    hint ? `wm=${hint.wm}` : 'missing');
  ok('A10-3 提示文案「滑动关闭」、浅色小字（text-dim + 11px + 0.55 浮现）',
    !!hint && hint.text === '滑动关闭' && parseFloat(hint.op) > 0.3 && parseFloat(hint.op) < 0.8 && hint.fs === '11px',
    hint ? `op=${hint.op} fs=${hint.fs} color=${hint.color}` : 'missing');
  const dim = await page.evaluate(() => {
    const probe = document.createElement('div');
    probe.style.color = 'var(--color-text-dim)';
    document.body.appendChild(probe);
    const c = getComputedStyle(probe).color;
    probe.remove();
    return c;
  });
  ok('A10-4 提示色 = --color-text-dim（浅色 token，非强调色）', !!hint && hint.color.replace(/\s/g, '') === dim.replace(/\s/g, ''),
    `hint=${hint && hint.color} dim=${dim}`);
  // 精修③下关闭仍走滑动（A8 已证甩出）——这里补「右键关闭」通路即可（A9 已证），断言划走后提示随前景易主
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
  ok('A13-1 窄档 340：后卡 289×…（=85%）', approx(pr.width, 340, 3) && approx(c1.width, 340 * 0.85, 3),
    `area=${pr.width.toFixed(0)} card=${c1.width.toFixed(0)}`);
  ok('A13-2 露边 22px 仍在', approx(stackedFrontTop - c1.y, 22, 3.5), `peek=${(stackedFrontTop - c1.y).toFixed(1)}`);
  await page.mouse.move(200, 500);   // page.click 后光标停在 fab 上：hover scale 1.05 → 视觉 rect 42×42
  const fab = await page.evaluate(() => {
    const el = document.getElementById('deck-fab');
    return { w: el.offsetWidth, h: el.offsetHeight, x: el.offsetLeft, y: el.offsetTop };  // 布局盒对 transform 免疫
  });
  ok('A13-3 浮钮仍固定 40px 原位（不缩放不移位；布局盒口径）',
    fab.w === 40 && fab.h === 40 && approx(fab.y - pr.y, 10, 1.5) && approx(pr.right - (fab.x + fab.w), 12, 1.5),
    `${fab.w}x${fab.h} dy=${(fab.y - pr.y).toFixed(1)} dr=${(pr.right - fab.x - fab.w).toFixed(1)}`);
  const hintHidden = await page.evaluate(() => {
    const h = document.querySelector('#deck-foot .deck-hint');
    return h && getComputedStyle(h).display === 'none';
  });
  ok('A13-4 窄档提示行隐藏', hintHidden === true);
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
  const sAlphas = [...stackedShadow.matchAll(/rgba\(0,\s*0,\s*0,\s*([\d.]+)\)/g)].map((m) => parseFloat(m[1]));
  // 样张 .stacked 投影：0.06 / 0.13 / 0.12 —— 精修② 0.04 / 0.09 / 0.08
  ok('F2-2 .stacked 面板投影密度降一档（0.04/0.09/0.08 < 样张 0.06/0.13/0.12）',
    sAlphas.slice(-3).join(',') === '0.04,0.09,0.08', sAlphas.join(','));
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
  const hintTxt = await page.evaluate(() => document.querySelector('#deck-foot .deck-hint')?.textContent || '');
  ok('A17-0b 提示行纳入竖拖换卡（「上下拖动」）', hintTxt.includes('上下拖动') && hintTxt.includes('横向划走'), hintTxt);
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

await browser.close();
server.close();

console.log(results.join('\n'));
console.log(`\ncanvas-stack: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
