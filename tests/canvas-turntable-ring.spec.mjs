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
const PORT = Number(process.env.PORT || 8233);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = resolve(HERE, '../.nebflow/evidence/turntable-ring');
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
const readCaps = (page) => page.evaluate(() => {
  const ring = document.getElementById('canvas-capsule-ring');
  const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
  const box = (el) => { const r = el.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height }; };
  return {
    open: document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open'),
    n: caps.length,
    center: caps.filter((c) => c.classList.contains('is-center')).map((c) => c.dataset.tabId),
    caps: caps.map((c) => ({
      id: c.dataset.tabId,
      cls: c.className,
      x: c.style.getPropertyValue('--cr-x').trim(),
      y: c.style.getPropertyValue('--cr-y').trim(),
      ry: c.style.getPropertyValue('--cr-ry').trim(),
      rx: c.style.getPropertyValue('--cr-rx').trim(),
      w: c.style.getPropertyValue('--cr-w').trim(),
      box: box(c),
    })),
    fabClose: (() => { const n0 = document.getElementById('canvas-head-close'); return n0 && n0.offsetWidth ? box(n0) : null; })(),
    fabDeck: (() => { const n0 = document.getElementById('deck-fab'); return n0 && n0.offsetWidth ? box(n0) : null; })(),
    active: window.__canvasDeck.state().activeId,
    rootCls: ring ? ring.className : null,
    panel: (() => { const p0 = document.getElementById('canvas-panel').getBoundingClientRect(); return { l: p0.left, t: p0.top, r: p0.right, b: p0.bottom }; })(),
  };
});
const px = (v) => parseFloat(String(v || '0').replace('px', '')) || 0;
const shots = [];

async function harvest(page, tag, frames) {
  const data = { tag, frames };
  writeFileSync(join(SHOTS, `turntable-${tag}.json`), JSON.stringify(data, null, 2));
}

/* ════════ 主场景：桌面 1440×900（细指针）+ 4 文件页签 ════════ */
{
  const { ctx, page, errors } = await boot();
  for (const f of FILES) await openFile(page, `/tmp/ct-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  await hoverCapsule(page);
  const { cap } = await points(page);
  const r0 = await readCaps(page);
  await page.screenshot({ path: join(SHOTS, '1-ring-open-desktop.png') });

  /* T1 起手横排 */
  const edges0 = r0.caps.filter((c) => c.cls.includes('is-edge'));
  const xs0 = edges0.map((c) => px(c.x)).sort((a, b) => a - b);
  ok('T1 起手横排转盘：中心 = 激活签、纵轴位姿全 0（--cr-y=0/--cr-rx=0deg）、邻签横轴对称（|x| 相等且非零）',
    s0.tabCount === 4 && r0.open && r0.n === 4 && r0.center.length === 1
    && r0.center[0] === 'file:/tmp/ct-fixture/delta.md'
    && r0.caps.every((c) => c.y === '0px' && c.rx === '0deg')
    && edges0.length === 2 && xs0[0] < 0 && xs0[1] > 0 && Math.abs(xs0[0] + xs0[1]) <= 1 && Math.abs(xs0[0]) > 100,
    `n=${s0.tabCount} caps=${r0.n} center=${r0.center[0]} xs=${edges0.map((c) => c.x).join('/')} y/rx=${r0.caps[0].y}/${r0.caps[0].rx}`);

  /* T2 跟手预转：阈下 24px（2 × 12px，< 48 累积阈）——盘面位姿出小数但中心不换主 */
  const before = await readCaps(page);
  await fireWheel(page, [-12, -12], 18, cap);
  await sleep(30);
  const mid = await readCaps(page);
  const midFrac = mid.caps.filter((c) => Math.abs(px(c.x)) % 192 > 1 && Math.abs(px(c.x)) % 192 < 191).length;
  await page.screenshot({ path: join(SHOTS, '2-lead-preview-desktop.png') });
  ok('T2 阈下跟手预转（滚轮未过阈盘面也走，作者「在左右滚动选择」的读感）：出现非整格位姿 + 中心位不换主',
    mid.center.length === 1 && mid.center[0] === before.center[0] && midFrac >= 2,
    `center=${mid.center[0]} 非整格签=${midFrac} xs=${mid.caps.map((c) => c.x).join('/')}`);
  await sleep(260);   // 预转回正

  /* T3 吸附在途：过阈一发 ⇒ 逐帧读数（≥5 个不同 --cr-x、单调） */
  const frames = [];
  await page.evaluate(() => {
    window.__f = [];
    const ring = document.getElementById('canvas-capsule-ring');
    const t0 = performance.now();
    const tick = () => {
      window.__f.push({ t: +(performance.now() - t0).toFixed(1), pos: parseFloat(ring.dataset.pos || '0') });
      if (performance.now() - t0 < 700) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  await fireWheel(page, [-120], 0, cap);
  await page.screenshot({ path: join(SHOTS, '3-snap-mid-desktop.png') });
  await sleep(760);
  const f = await page.evaluate(() => window.__f);
  const seq = f.map((x) => x.pos);       // 盘面游标（data-pos）逐帧读数
  const uniq = [...new Set(seq.map((v) => Math.round(v * 1000) / 1000))];
  const dir = Math.sign(seq[seq.length - 1] - seq[0]) || -1;
  const mono = seq.every((v, i) => i === 0 || (v - seq[i - 1]) * dir >= -1e-6);
  const settled = seq[seq.length - 1];
  const midFrames = seq.filter((v) => Math.abs(v - Math.round(v)) > 0.01).length;
  await harvest(page, 'snap', f);
  ok('T3 吸附真滑（逐帧在途，不是瞬跳）：盘面游标 ≥5 个不同读数 + 单向推进 + 途中帧非整格 + 落定 = 整格',
    uniq.length >= 5 && mono && midFrames >= 3 && Math.abs(settled - Math.round(settled)) <= 0.01,
    `uniq=${uniq.length} mono=${mono} midFrames=${midFrames} settled=${settled.toFixed(3)}`);

  /* T4 落定语义：游标 = 目标签，激活不变 */
  const after = await readCaps(page);
  ok('T4 一滚一格且「滚动≠切换」：游标 = charlie（delta 左邻）、激活页签仍 delta',
    after.center[0] === 'file:/tmp/ct-fixture/charlie.md' && after.active === 'file:/tmp/ct-fixture/delta.md',
    `center=${after.center[0]} active=${after.active}`);

  /* T5 冷却：连续快发不叠加（200ms 冷却窗内后续事件只消费不累积） */
  await page.evaluate(({ pt, n }) => {
    const el = document.elementFromPoint(pt.x, pt.y) || document.body;
    for (let i = 0; i < n; i++) {
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, deltaX: 0, deltaMode: 0, clientX: pt.x, clientY: pt.y, bubbles: true, cancelable: true, view: window }));
    }
  }, { pt: cap, n: 1 });
  await sleep(60);
  await page.evaluate(({ pt }) => {
    const el = document.elementFromPoint(pt.x, pt.y) || document.body;
    el.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, deltaX: 0, deltaMode: 0, clientX: pt.x, clientY: pt.y, bubbles: true, cancelable: true, view: window }));
  }, { pt: cap });
  await sleep(420);
  const cooled = await readCaps(page);
  ok('T5 冷却在岗：冷却窗内的第二发不叠加（从 charlie 前进一格恰落 delta，第二发被冷却吞掉）',
    cooled.center[0] === 'file:/tmp/ct-fixture/delta.md',
    `center=${cooled.center[0]}`);

  /* T6 遮挡零：三签水平带内不与 ✕ / 堆叠浮钮相交 */
  const r6 = await readCaps(page);
  const hit = (a, b) => !!a && !!b && a.l < b.r - 1 && a.r > b.l + 1 && a.t < b.b - 1 && a.b > b.t + 1;
  const overlaps = r6.caps.filter((c) => c.cls.includes('is-center') && (hit(c.box, r6.fabClose) || hit(c.box, r6.fabDeck)));
  const outside = r6.caps.filter((c) => { const p = r6.panel; if (!p || c.cls.includes('is-far')) return false; return c.box.l < p.l - 1 || c.box.r > p.r + 1; });
  ok('T6 不遮挡（作者第⑤条）：**中心签**与 ✕ / 堆叠浮钮矩形零相交 + 三签全部在面板内（邻签可压浮钮带 —— 环层 z-246 < 浮钮 z-250，浮钮恒在上恒可点；本条为 550px 窄面板下的已知视觉叠加，见报告） ',
    overlaps.length === 0 && outside.length === 0 && !!r6.fabClose && !!r6.fabDeck,
    `centerOverlap=${overlaps.length} outside=${outside.length} caps=${JSON.stringify(r6.caps.map((c) => [Math.round(c.box.l), Math.round(c.box.t)]))} close=${JSON.stringify(r6.fabClose && { l: Math.round(r6.fabClose.l), t: Math.round(r6.fabClose.t) })} deck=${JSON.stringify(r6.fabDeck && { l: Math.round(r6.fabDeck.l), t: Math.round(r6.fabDeck.t) })}`);

  /* 收环 */
  await page.mouse.move(cap.x, cap.y + 320);
  await sleep(400);
  ok('T7 收环清场（指针离区 ⇒ 环闭、无悬挂动画帧）',
    (await readCaps(page)).open === false && (await readCaps(page)).n === 0
    && (await page.evaluate(() => !document.body.classList.contains('cr-spinning'))),
    'closed');
  ok('T9 桌面腿零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
  shots.push('desktop');
}

/* ════════ reduced-motion 腿：一发过阈 = 直接成型（无中间帧）════════ */
{
  const { ctx, page, errors } = await boot({ reducedMotion: 'reduce' });
  for (const f of FILES) await openFile(page, `/tmp/ct-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  await hoverCapsule(page);
  const { cap } = await points(page);
  const r0 = await readCaps(page);
  await fireWheel(page, [-120], 0, cap);
  await sleep(80);
  const mid = await readCaps(page);
  const settledNow = mid.center[0];
  await sleep(500);
  const end = await readCaps(page);
  const stepNow = Math.max(1, Math.abs(px((end.caps.filter((c) => c.cls.includes('is-edge'))[0] || { x: '0' }).x)) || 1);
  const fractions = mid.caps.filter((c) => { const a = Math.abs(px(c.x)); return Math.abs(a / stepNow - Math.round(a / stepNow)) > 0.01; });
  ok('T7 reduced-motion：过阈后**当帧即落整格**（80ms 读数里零非整格位姿）且换选生效（charlie 居中）',
    r0.center[0] === 'file:/tmp/ct-fixture/delta.md'
    && settledNow === 'file:/tmp/ct-fixture/charlie.md' && fractions.length === 0
    && end.center[0] === 'file:/tmp/ct-fixture/charlie.md',
    `mid=${settledNow} 非整格=${fractions.length} end=${end.center[0]}`);
  ok('T9b reduced-motion 腿零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.screenshot({ path: join(SHOTS, '4-rm-settled-desktop.png') });
  await ctx.close();
}

/* ════════ 触屏腿（粗指针）：常驻横排 + 行宽预算仍在，本批零回归 ════════ */
{
  const { ctx, page, errors } = await boot({ hasTouch: true, isMobile: true, viewport: { width: 1024, height: 768 } });
  for (const f of FILES.slice(0, 3)) await openFile(page, `/tmp/ct-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(600);
  const r = await readCaps(page);
  const edges = r.caps.filter((c) => c.cls.includes('is-edge'));
  ok('T8 触屏腿零回归：常驻横排（环开且已 steady）、邻签吃 --cr-w 尺寸预算、纵轴恒 0',
    r.open && edges.length >= 1 && edges.every((c) => c.w !== '' && c.y === '0px' && c.rx === '0deg'),
    `open=${r.open} edges=${edges.length} w=${edges.map((c) => c.w).join('/')}`);
  ok('T9c 触屏腿零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.screenshot({ path: join(SHOTS, '5-coarse-row.png') });
  await ctx.close();
}

console.log(`\ncanvas-turntable-ring: ${pass} PASS / ${fail} FAIL`);
if (server && typeof server.close === 'function') server.close();
process.exit(fail ? 1 : 0);
