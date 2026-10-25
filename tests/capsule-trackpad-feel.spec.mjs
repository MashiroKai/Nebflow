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
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8191);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = resolve(HERE, '../reports/20261025_131500_capsule-trackpad/shots');
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
    window.__mo = new MutationObserver(() => {
      const c = ring.querySelector('.cr-capsule.is-center');
      const id = c ? c.dataset.tabId : null;
      const arr = window.__steps;
      if (!arr.length || arr[arr.length - 1].id !== id) arr.push({ id, t: performance.now() });
    });
    window.__mo.observe(ring, { subtree: true, attributes: true, attributeFilter: ['class'] });
  });
}
const stepCount = (page) => page.evaluate(() => window.__steps.length);
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

/* ════════ 主场景：4 文件页签（环序 alpha→bravo→charlie→delta，游标初值 delta）════════ */
{
  const { ctx, page, errors } = await boot();
  for (const f of FILES) await openFile(page, `/tmp/ct-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  ok('TC0 前置：四文件页签开（环序 alpha/bravo/charlie/delta，delta 活动）',
    s0.tabCount === 4 && s0.activeId === 'file:/tmp/ct-fixture/delta.md', `n=${s0.tabCount} active=${s0.activeId}`);

  await hoverCapsule(page);
  let r = await ringSnap(page);
  ok('TC0b hover 开环（steady）+ 游标 = 激活页签', r.open && r.n === 4 && r.steady && r.center === 'file:/tmp/ct-fixture/delta.md',
    `open=${r.open} n=${r.n} steady=${r.steady} center=${r.center}`);
  await installProbe(page);
  const { cap, far } = await points(page);

  /* ── TC1 累积器在岗：单个小 delta 不切（阈下微漂不移游标；负向残账留给
   *  TC2 顺带钉「反向归零」）── */
  await fireWheel(page, [-12], 0, cap);
  await sleep(260);
  ok('TC1 单个小 delta（12px < 48 累积阈）不换选：轻扫要「挥」不是「抖」',
    (await stepCount(page)) === 0 && (await ringSnap(page)).center === 'file:/tmp/ct-fixture/delta.md',
    `steps=${await stepCount(page)}`);

  /* ── TC2 轻扫序列恰切 1 次（8 × 12px @14ms ≈ 一挥轻扫 + 微惯性尾）── */
  await fireWheel(page, [12, 12, 12, 12, 12, 12, 12, 12], 14, cap);
  await sleep(420);
  r = await ringSnap(page);
  ok('TC2 轻扫序列（8 小 delta）恰切 1 次：delta → alpha（+1 环绕），惯性尾被冷却吞掉',
    (await stepCount(page)) === 1 && r.center === 'file:/tmp/ct-fixture/alpha.md' && r.open,
    `steps=${await stepCount(page)} center=${r.center}`);
  await page.screenshot({ path: join(SHOTS, '1-ring-after-swipe-dark.png'), clip: await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top - 56), width: p.width, height: 300 };
  }) });

  /* ── TC3 冷却期内事件不叠加切换：同一次页内求值里「切一格 → 立即连发」──
   *  （120 单格切 + 20ms 起每 20ms 一发 60px ×6 = 360px；无冷却会再切 ≥2 次，
   *  全部落 200ms 冷却窗内 ⇒ 只许 TC2+本格共 2 次） */
  const s3base = await stepCount(page);
  await page.evaluate(({ pt }) => {
    const el = document.elementFromPoint(pt.x, pt.y) || document.body;
    const fire = (dy) => el.dispatchEvent(new WheelEvent('wheel', {
      deltaY: dy, clientX: pt.x, clientY: pt.y, bubbles: true, cancelable: true, view: window,
    }));
    fire(120);                                      // 单格切换（冷却武装）
    (async () => {
      for (let i = 0; i < 6; i++) {
        await new Promise((res) => setTimeout(res, 20));
        fire(60);
      }
    })();
  }, { pt: cap });
  await sleep(400);
  ok('TC3 冷却期内连发（360px）零叠加：恰 +1（单格那发）',
    (await stepCount(page)) - s3base === 1 && (await ringSnap(page)).center === 'file:/tmp/ct-fixture/bravo.md',
    `本序列 steps=${(await stepCount(page)) - s3base} center=${(await ringSnap(page)).center}`);

  /* ── TC4 惯性序列 30 连发 ≤2 次不超调（30 × 6px @10ms = 180px 惯性末段）── */
  await sleep(500);                                 // 冷却彻底过期，纯惯性序列
  const s4base = await stepCount(page);
  await fireWheel(page, Array.from({ length: 30 }, () => 6), 10, cap);
  await sleep(420);
  const s4 = (await stepCount(page)) - s4base;
  ok('TC4 惯性序列（30 小 delta 连发）≤2 次不超调（实证 1：首阈一切、末段全入冷却）',
    s4 >= 1 && s4 <= 2, `本序列 steps=${s4}`);
  r = await ringSnap(page);
  ok('TC4b 惯性末态游标 = charlie（恰好一格，不连跳）',
    r.center === 'file:/tmp/ct-fixture/charlie.md' && r.open, `center=${r.center}`);

  /* ── TC5 鼠标单格 delta 切 1 次（离散大步进 120px 单事件）── */
  await sleep(500);
  const s5base = await stepCount(page);
  await fireWheel(page, [120], 0, cap);
  await sleep(360);
  ok('TC5 鼠标单格 delta（120px 单事件）恰切 1 次：charlie → delta',
    (await stepCount(page)) - s5base === 1 && (await ringSnap(page)).center === 'file:/tmp/ct-fixture/delta.md',
    `本序列 steps=${(await stepCount(page)) - s5base} center=${(await ringSnap(page)).center}`);

  /* ── TC6 ④hover 区外零响应：不消费（defaultPrevented=false）+ 游标不动 ── */
  const b6base = await page.evaluate(() => window.__bubble.length);
  await fireWheel(page, [200, 200], 30, far);
  await sleep(300);
  const tail = await bubbleTail(page, await page.evaluate((n) => window.__bubble.length - n, b6base));
  ok('TC6 光标在胶囊外（面板内容区）滚轮零响应：游标不动 + 零 preventDefault（页面滚动不被抢）',
    (await stepCount(page)) === s5base + 1 && tail.length === 2 && tail.every((x) => !x.prevented),
    `steps=${await stepCount(page)} prevented=${tail.map((x) => x.prevented).join('/')}`);
  // 区内对照腿：同量事件打在胶囊上 = 照常消费换选（环滚动链仍活）
  await fireWheel(page, [120], 0, cap);
  await sleep(360);
  const inTail = await bubbleTail(page, 1);
  ok('TC6b 区内对照：同量滚轮打在胶囊上照常消费（prevented=true）+ 游标 +1 环绕（delta → alpha）',
    (await stepCount(page)) === s5base + 2 && inTail.length === 1 && inTail[0].prevented
      && (await ringSnap(page)).center === 'file:/tmp/ct-fixture/alpha.md',
    `steps=${await stepCount(page)} prevented=${inTail.map((x) => x.prevented).join('/')}`);

  /* ── TC7 ③过渡动画 CSS 断言：steady 态游标步进 0.18s 快跟手档 ── */
  const css = await page.evaluate(() => {
    const el = document.querySelector('#canvas-capsule-ring .cr-capsule.is-edge');
    const c = getComputedStyle(el);
    return { dur: c.transitionDuration, prop: c.transitionProperty };
  });
  ok('TC7 步进过渡 150-200ms 档：steady 态胶囊 transition-duration 首值 0.18s + 含 transform/opacity',
    css.dur.startsWith('0.18s') && /(^|,\s*)transform(\s*,|$)/.test(css.prop) && /(^|,\s*)opacity(\s*,|$)/.test(css.prop),
    `dur=${css.dur} prop=${css.prop}`);

  /* ── TC8 游标步进真滑（0.18s 过渡在途 = transform 非终值，丝滑可观测）── */
  await sleep(500);
  await fireWheel(page, [120], 0, cap);
  await sleep(55);                                  // 0.18s 过渡中段采样
  const midT = await page.evaluate(() => {
    const el = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return el ? el.getBoundingClientRect().top : null;
  });
  await sleep(400);                                 // 过渡落定
  const endT = await page.evaluate(() => {
    const el = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return el ? el.getBoundingClientRect().top : null;
  });
  ok('TC8 步进过渡真滑（在途中段 ≠ 落定位；0.18s 平滑迁移非瞬跳）',
    midT !== null && endT !== null && Math.abs(midT - endT) > 2,
    `mid=${midT?.toFixed(1)} end=${endT?.toFixed(1)} Δ=${(midT - endT).toFixed(1)}px`);

  ok('TC-R 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ TC9 prefers-reduced-motion 直切（0.01s 钉 + 功能不失效）════════ */
{
  const { ctx, page, errors } = await boot({ reducedMotion: 'reduce' });
  await openFile(page, '/tmp/ct-fixture/alpha.md');
  await openFile(page, '/tmp/ct-fixture/bravo.md');
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  await hoverCapsule(page);
  const r9 = await ringSnap(page);
  const css = await page.evaluate(() => {
    const el = document.querySelector('#canvas-capsule-ring .cr-capsule.is-edge');
    return el ? getComputedStyle(el).transitionDuration : null;
  });
  ok('TC9 reduce-motion：过渡 0.01s 直切档（!important 钉压过 0.18s 步进档）',
    r9.open && r9.steady && css !== null && css.startsWith('0.01s'), `steady=${r9.steady} dur=${css}`);
  await installProbe(page);
  const { cap } = await points(page);
  await fireWheel(page, [100], 0, cap);
  await sleep(300);
  ok('TC9b reduce-motion 功能不失效：滚轮换选照常（恰 1 次，bravo +1 环绕 → alpha）',
    (await stepCount(page)) === 1 && (await ringSnap(page)).center === 'file:/tmp/ct-fixture/alpha.md',
    `steps=${await stepCount(page)} center=${(await ringSnap(page)).center}`);
  ok('TC9c reduce 上下文零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncapsule-trackpad-feel: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
