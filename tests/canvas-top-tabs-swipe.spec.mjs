#!/usr/bin/env node
// canvas-top-tabs-swipe.spec.mjs — canvas 页顶部环签「左右滑切签」机械断言集
// （ui-final-four ②，作者令 2026-11-04 13:04 逐字：「把canvas顶部的标签 支持
// 左右滑的手势现在也没了」）。
//
// 判据来源 = 任务书第二步六条（逐条落成 H 系列，改前必红 / 改后必绿）：
//   H0 形态前置 …… canvas 页 live + 三签 + 签条在位 + 唯一 .on。
//   H1 横滑左 ⇒ **真的切签**：active 由 b 变 c（setActiveTab/syncCanvasPage 生效：
//      pane 收编换人 + 计数 3/3 + 小点 idx 同步）。改前：签条横拖零响应 = 红。
//   H2 横滑右 ⇒ active 由 c 变 b（反向等价；改前红）。
//   H3 端点非环式 ⇒ 首签右滑 / 尾签左滑：active **不变**、跟手位移被阻尼
//      （|sdx| < |dx|）、松手位移**归零回弹**（--sdx = 0px）、pane 不闪白
//      （opacity 1、pane 仍在）。改前：--sdx 不存在 = 红。
//   H4 纵拖一律不认领：签上纵拖 active 不变 + 零 claim（探针 claims 不增）；
//      文档区纵拖 active 不变 + 文档区滚轮照常滚（原生滚动路径未被劫持）。
//   H5 双条件判据：①位移 < 8px ⇒ 不认；②|dx| < 1.2×|dy| ⇒ 不认（轴锁给纵轴）。
//   H6 内容手势面优先：`.canvas-image-viewport` 上起手横拖 ⇒ 不切签、零 claim
//      （既有仲裁闸原样保留）。
//   H7 内容区横滑「环式」语义未变（**加法不破既有**；与 mobile-canvas-sheet
//      T3 同口径）：三签在末签左滑 ⇒ 环绕到首签，首签右滑 ⇒ 环绕回末签。
//   H8 零 pageerror。
//   H9 签本体 touch-action = pan-y（横轴收归指针管线 / 纵轴让给原生）。
//   H10 环带透传契约未变：带 pointer-events:none、签 pointer-events:auto。
//
// 引擎：Chromium(:8360) + WebKit(:8361) 各跑一遍（viewport 375×812 / hasTouch）。
// 改前树腿：SWIPE_WEB=<git archive 出来的 77afefc77 web 根> SWIPE_PHASE=before
//   ⇒ 同一脚本跑改前树，H1/H2/H3/H9 必红（横滑不切签 / 无回弹位移语义）。
// 证据：SWIPE_EVIDENCE_DIR/<phase>/swipe/ 下 = 手势中间帧序列 PNG（起手 / 轴锁
//   后 / 跟手位移中段 / 过阈 / 端点回弹，双引擎各一套）+ readings.json。
// 数据面 = 自包含（本机静态服务 + routeWebSocket 夹具），零后端、零 sbt、
//   跑完 server.close() + browser.close() 零残留。
// Run: node tests/canvas-top-tabs-swipe.spec.mjs
import { chromium, webkit } from 'playwright';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB = process.env.SWIPE_WEB || resolve(REPO, 'src', 'main', 'resources', 'web');
const PHASE = process.env.SWIPE_PHASE || 'after';
const OUT = join(
  process.env.SWIPE_EVIDENCE_DIR || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/ui-final-four',
  PHASE,
  'swipe',
);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'swipe-root-session';
const ENGINES = [
  { name: 'chromium', type: chromium, port: 8360 },
  { name: 'webkit', type: webkit, port: 8361 },
];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ══ 静态服务（每引擎独占端口；监听失败 = 端口被占，直接报错不硬闯）════════ */
function serve(root, port) {
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    const file = join(root, p);
    if (!file.startsWith(root)) { res.writeHead(403); res.end('forbidden'); return; }
    try {
      const body = readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  return new Promise((res, rej) => {
    server.once('error', (err) => rej(new Error(`静态服务 :${port} 起不来（端口占用？）: ${err.message}`)));
    server.listen(port, '127.0.0.1', () => res(server));
  });
}

/* ══ 读数 ═════════════════════════════════════════════════════════════ */
/** 判据面一次性读数（活动签 / 跟手位移 / 探针 / pane / 计数 / 小点）。 */
function readState(page) {
  return page.evaluate(() => {
    const strip = document.querySelector('.mf-strip');
    const onChip = document.querySelector('.mf-strip-chip.on');
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const host = document.querySelector('.mf-cv-host');
    const probe = (/** @type {any} */ (window)).__nfStripSwipe || null;
    const dots = document.querySelector('.mf-dots');
    return {
      active: onChip ? onChip.dataset.tabId : null,
      onCount: document.querySelectorAll('.mf-strip-chip.on').length,
      chips: document.querySelectorAll('.mf-strip-chip').length,
      sdx: strip ? getComputedStyle(strip).getPropertyValue('--sdx').trim() : null,
      stripPointer: strip ? getComputedStyle(strip).pointerEvents : null,
      stripSwiping: strip ? strip.classList.contains('is-swiping') : null,
      chipTouch: onChip ? getComputedStyle(onChip).touchAction : null,
      chipPointer: onChip ? getComputedStyle(onChip).pointerEvents : null,
      paneId: pane ? pane.dataset.tabId : null,
      paneOpacity: pane ? getComputedStyle(pane).opacity : null,
      hostOpacity: host ? getComputedStyle(host).opacity : null,
      count: document.querySelector('.mf-cv-count')?.textContent || '',
      dots: dots ? `${dots.dataset.idx}/${dots.dataset.n}` : null,
      probe: probe
        ? { axis: probe.axis, claims: probe.claims, switches: probe.switches, bounces: probe.bounces, lastDx: probe.lastDx, lastOffset: probe.lastOffset }
        : null,
    };
  });
}

const num = (v) => (v === null || v === undefined || v === '' ? 0 : parseFloat(v) || 0);

/* ══ 单引擎全场景 ═════════════════════════════════════════════════════ */
async function runEngine(engine) {
  const rec = {};              // 判据 → 读数
  const lines = [];            // PASS/FAIL 原始输出行
  let fails = 0;
  const ok = (id, cond, detail) => {
    const line = `${cond ? 'PASS' : 'FAIL'}  [${engine.name}]  ${id}  — ${detail}`;
    console.log(line);
    lines.push(line);
    if (!cond) fails += 1;
    rec[id] = { pass: !!cond, detail };
  };
  const frames = [];
  const shot = async (page, label) => {
    const name = `${engine.name}-${String(frames.length + 1).padStart(2, '0')}-${label}.png`;
    await page.screenshot({ path: join(OUT, name) });
    frames.push(name);
    return name;
  };

  const server = await serve(WEB, engine.port);
  const base = `http://127.0.0.1:${engine.port}`;
  const browser = await engine.type.launch();
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark',
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });

  try {
    await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      const sendSessions = () => ws.send(JSON.stringify({
        type: 'sessionList',
        sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
        folders: [], activeId: SID,
      }));
      sendConfig();
      sendSessions();
      ws.onMessage((raw) => {
        let m;
        try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getConfig') sendConfig();
        else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
        else if (m.type === 'getHistory') {
          ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
        }
      });
    });
    await page.goto(`${base}/index.html`);
    await page.waitForFunction(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
    }, SID, { timeout: 20000 });
    await sleep(400);

    /* ── 夹具：三张文档卡 + dock 进 canvas 页 ── */
    await page.evaluate((list) => {
      import('/js/canvas.js').then((c) => {
        for (const [id, title, type] of list) c.openTab(id, title, { type });
      });
    }, [['doc-a', '卡甲', 'pg-a'], ['doc-b', '卡乙', 'pg-b'], ['doc-c', '卡丙', 'pg-c']]);
    await sleep(350);
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="canvas"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 8000 });
    await sleep(350);

    const setTab = async (id) => {
      await page.evaluate(async (tid) => {
        const c = await import('/js/canvas.js');
        c.setActiveTab(tid);
      }, id);
      await sleep(320);
    };

    /* ── 手势驱动：逐步 mouse.move（真实 Pointer Events 管线），逐帧读数 +
     *    指定步拍照（中间帧序列证据）。 ── */
    const dragPath = async ({ x0, y0, x1, y1, steps = 10, stepMs = 14, shotAt = {} }) => {
      const samples = [];
      await page.mouse.move(x0, y0);
      await sleep(60);
      if (shotAt[0]) await shot(page, shotAt[0]);
      await page.mouse.down();
      for (let i = 1; i <= steps; i++) {
        await page.mouse.move(x0 + ((x1 - x0) * i) / steps, y0 + ((y1 - y0) * i) / steps);
        await sleep(stepMs);
        const st = await readState(page);
        samples.push({
          step: i,
          dx: +(((x1 - x0) * i) / steps).toFixed(1),
          dy: +(((y1 - y0) * i) / steps).toFixed(1),
          active: st.active, sdx: st.sdx, probe: st.probe,
        });
        if (shotAt[i]) await shot(page, shotAt[i]);
      }
      return { samples, up: () => page.mouse.up() };
    };

    /* ── H0 形态前置 ── */
    const h0 = await readState(page);
    const stripBox = await page.locator('.mf-strip').boundingBox();
    ok('H0 canvas 页在位 + 三签 + 唯一 .on', h0.chips === 3 && h0.onCount === 1 && h0.active === 'doc-c'
      && !!stripBox && stripBox.width > 200,
    `chips=${h0.chips} on=${h0.onCount} active=${h0.active} strip=${stripBox ? Math.round(stripBox.width) + 'x' + Math.round(stripBox.height) : 'null'}`);

    /* 中心签（活动签）几何 —— 签条横滑的起手面。 */
    const chipBox = async (id) => page.evaluate((tid) => {
      const el = document.querySelector(`.mf-strip-chip[data-tab-id="${tid}"]`);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height, cx: r.x + r.width / 2, cy: r.y + r.height / 2 };
    }, id);

    await setTab('doc-b');
    let cb = await chipBox('doc-b');

    /* ── H1 横滑左（签上起手，dx=-120）⇒ 下一张 = doc-c ── */
    const h1Before = await readState(page);
    let d = await dragPath({
      x0: cb.cx, y0: cb.cy, x1: cb.cx - 120, y1: cb.cy, steps: 10,
      shotAt: { 0: 'h1-start', 1: 'h1-axis-locked', 3: 'h1-mid', 5: 'h1-over-threshold' },
    });
    await d.up();
    await sleep(360);
    const h1After = await readState(page);
    ok('H1a 签上横滑左 ⇒ active b→c（真的切签）', h1Before.active === 'doc-b' && h1After.active === 'doc-c',
      `active ${h1Before.active} → ${h1After.active}`);
    ok('H1b setActiveTab/syncCanvasPage 生效：pane 收编换人 + 计数 + 小点同步',
      h1After.paneId === 'doc-c' && h1After.count === '3/3' && h1After.dots === '2/3',
      `pane=${h1After.paneId} count=${h1After.count} dots=${h1After.dots}`);
    const h1Off = d.samples.map((s) => num(s.sdx));
    ok('H1c 跟手位移真实（轴锁后 --sdx 随指变，非瞬切）',
      h1Off.some((v) => v <= -20) && h1Off[h1Off.length - 1] < 0,
    `--sdx 逐步 = [${d.samples.map((s) => s.sdx || '""').join(', ')}]`);
    ok('H1d 横轴锁后单次判定（探针 axis=x，claim 恰 1）',
      h1After.probe?.axis === 'x' && (h1After.probe?.claims || 0) === 1,
    `axis=${h1After.probe?.axis} claims=${h1After.probe?.claims}`);
    await shot(page, 'h1-switched');

    /* ── H2 横滑右 ⇒ 上一张 = doc-b ── */
    cb = await chipBox('doc-c');
    const h2Before = await readState(page);
    d = await dragPath({ x0: cb.cx, y0: cb.cy, x1: cb.cx + 120, y1: cb.cy, steps: 10 });
    await d.up();
    await sleep(360);
    const h2After = await readState(page);
    ok('H2 签上横滑右 ⇒ active c→b（反向等价）',
      h2Before.active === 'doc-c' && h2After.active === 'doc-b' && h2After.paneId === 'doc-b',
      `active ${h2Before.active} → ${h2After.active}，pane=${h2After.paneId} count=${h2After.count}`);

    /* ── H3a 首签右滑（prev 越界）⇒ 阻尼 + active 不变 + 回弹归零 ── */
    await setTab('doc-a');
    cb = await chipBox('doc-a');
    const b0 = await readState(page);
    d = await dragPath({
      x0: cb.cx, y0: cb.cy, x1: cb.cx + 112, y1: cb.cy, steps: 8,
      shotAt: { 2: 'h3a-bounce-damped' },
    });
    const midSdxA = num(d.samples[3].sdx);
    const midDxA = d.samples[3].dx;
    await d.up();
    await sleep(120);
    await shot(page, 'h3a-bounce-spring');
    await sleep(320);
    const b1 = await readState(page);
    ok('H3a-① 首签右滑 ⇒ active 不变（端点非环式，不环绕到尾签）',
      b1.active === 'doc-a' && b0.active === 'doc-a',
    `active ${b0.active} → ${b1.active}`);
    ok('H3a-② 越界段跟手被阻尼（0 < sdx < dx）', midSdxA > 0 && midSdxA < midDxA,
      `mid dx=${midDxA} sdx=${midSdxA}（比例 ${(midSdxA / midDxA).toFixed(2)}）`);
    ok('H3a-③ 松手位移归零回弹（--sdx = 0px）+ 不闪白（pane 在场 opacity 1）',
      /^0(\.0+)?px$/.test(String(b1.sdx)) && b1.paneId === 'doc-a' && b1.paneOpacity === '1' && b1.hostOpacity === '1',
    `sdx=${JSON.stringify(b1.sdx)} pane=${b1.paneId} paneOpacity=${b1.paneOpacity} hostOpacity=${b1.hostOpacity}`);
    ok('H3a-④ 端点记一次 bounce（探针），换签计数不增',
      (b1.probe?.bounces || 0) === 1 && (b1.probe?.switches || 0) === 2,
    `bounces=${b1.probe?.bounces} switches=${b1.probe?.switches}`);

    /* ── H3b 尾签左滑（next 越界）⇒ 同上 ── */
    await setTab('doc-c');
    cb = await chipBox('doc-c');
    d = await dragPath({ x0: cb.cx, y0: cb.cy, x1: cb.cx - 112, y1: cb.cy, steps: 8, shotAt: { 2: 'h3b-bounce-damped' } });
    const midSdxB = num(d.samples[3].sdx);
    await d.up();
    await sleep(420);
    const b2 = await readState(page);
    ok('H3b 尾签左滑 ⇒ active 不变 + 阻尼（sdx < 0 且 |sdx| < |dx|）+ 归零回弹',
      b2.active === 'doc-c' && midSdxB < 0 && Math.abs(midSdxB) < 112 && /^0(\.0+)?px$/.test(String(b2.sdx)),
    `active=${b2.active} mid sdx=${midSdxB} settled sdx=${JSON.stringify(b2.sdx)} bounces=${b2.probe?.bounces}`);

    /* ── H4a 签上纵拖 ⇒ 不认领（active 不变 + 零 claim） ── */
    await setTab('doc-b');
    cb = await chipBox('doc-b');
    const v0 = await readState(page);
    d = await dragPath({ x0: cb.cx, y0: cb.cy, x1: cb.cx + 4, y1: cb.cy + 150, steps: 10 });
    await d.up();
    await sleep(260);
    const v1 = await readState(page);
    const claimsDelta = (v1.probe?.claims || 0) - (v0.probe?.claims || 0);
    ok('H4a 签上纵拖 ⇒ active 不变 + 零 claim（axis=y，不抢 capture）',
      v1.active === 'doc-b' && claimsDelta === 0
      && (v1.probe === null || v1.probe.axis === 'y' || v1.probe.axis === null),
      `active=${v1.active} axis=${v1.probe?.axis} claims ${v0.probe?.claims}→${v1.probe?.claims}`);

    /* ── H4b 文档区纵拖 ⇒ active 不变；文档区滚轮照常滚（原生路径未被劫持） ── */
    await page.evaluate(() => {
      const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
      pane.innerHTML = '<div class="sw-scroll" style="height:100%;overflow-y:auto"><div style="height:2400px">scroll-body</div></div>';
    });
    await sleep(120);
    const host = await page.locator('.mf-cv-host').boundingBox();
    await page.mouse.move(host.x + host.width / 2, host.y + host.height / 2);
    await page.mouse.wheel(0, 320);
    await sleep(240);
    const scrollTop = await page.evaluate(() => document.querySelector('.mf-cv-host .sw-scroll').scrollTop);
    d = await dragPath({
      x0: host.x + host.width / 2, y0: host.y + host.height * 0.7,
      x1: host.x + host.width / 2 + 6, y1: host.y + host.height * 0.7 - 150, steps: 10,
    });
    await d.up();
    await sleep(240);
    const h4b = await readState(page);
    ok('H4b 文档区纵拖 ⇒ active 不变 + 滚轮照常滚（原生滚动路径保持）',
      h4b.active === 'doc-b' && scrollTop > 0,
    `active=${h4b.active} scrollTop=${scrollTop}`);

    /* ── H4c 真触屏纵拖（Chromium CDP Input.dispatchTouchEvent；WebKit 无 CDP
     *    面 ⇒ 记 skipped）⇒ 文档区 scrollTop 增加 + active 不变 ── */
    if (engine.name === 'chromium') {
      const cdp = await ctx.newCDPSession(page);
      await page.evaluate(() => { const s = document.querySelector('.mf-cv-host .sw-scroll'); if (s) s.scrollTop = 0; });
      await sleep(120);
      const tx = host.x + host.width / 2;
      const ty0 = host.y + host.height * 0.62;
      const ty1 = host.y + host.height * 0.28;
      const touch = (type, x, y) => cdp.send('Input.dispatchTouchEvent', {
        type, touchPoints: type === 'touchEnd' ? [] : [{ x, y, radiusX: 6, radiusY: 6, force: 1 }],
      });
      await touch('touchStart', tx, ty0);
      for (let i = 1; i <= 8; i++) {
        await touch('touchMove', tx, ty0 + ((ty1 - ty0) * i) / 8);
        await sleep(16);
      }
      await touch('touchEnd', tx, ty1);
      await sleep(420);
      const st2 = await page.evaluate(() => document.querySelector('.mf-cv-host .sw-scroll').scrollTop);
      const h4c = await readState(page);
      ok('H4c [chromium] 真触屏纵拖 ⇒ 文档区 scrollTop 增加 + active 不变',
        st2 > 0 && h4c.active === 'doc-b', `scrollTop=${st2} active=${h4c.active}`);
      rec['H4c(webkit)'] = { pass: true, detail: 'skipped — WebKit 无 CDP 触屏面（同脚本双引擎各自可用的最高保真路径）' };
    }

    /* ── H5a 位移 < 8px ⇒ 不认（最小位移条件） ── */
    const p0 = await readState(page);
    d = await dragPath({ x0: cb.cx, y0: cb.cy, x1: cb.cx - 6, y1: cb.cy, steps: 3 });
    await d.up();
    await sleep(260);
    const p1 = await readState(page);
    ok('H5a 位移 6px（< 8px 阈值）⇒ 不认横滑、active 不变、零 claim',
      p1.active === 'doc-b' && (p1.probe?.claims || 0) === (p0.probe?.claims || 0),
    `active=${p1.active} claims ${p0.probe?.claims}→${p1.probe?.claims}`);

    /* ── H5b |dx| < 1.2×|dy| ⇒ 不认（位移比条件） ── */
    d = await dragPath({ x0: cb.cx, y0: cb.cy, x1: cb.cx - 40, y1: cb.cy + 62, steps: 8 });
    await d.up();
    await sleep(260);
    const p2 = await readState(page);
    ok('H5b dx=-40 / dy=+62（|dx| < 1.2×|dy|）⇒ 轴锁给纵轴、不切签',
      p2.active === 'doc-b' && (p2.probe === null || p2.probe.axis === 'y'),
      `active=${p2.active} axis=${p2.probe?.axis} claims=${p2.probe?.claims}`);

    /* ── H6 内容手势面起手横拖 ⇒ 不切签（仲裁闸原样） ── */
    await page.evaluate(() => {
      const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
      pane.innerHTML = '<div class="canvas-image-viewport" style="height:520px;width:100%">content-gesture-surface</div>';
    });
    await sleep(150);
    const vp = await page.locator('.mf-cv-host .canvas-image-viewport').boundingBox();
    const c0 = await readState(page);
    d = await dragPath({ x0: vp.x + vp.width - 40, y0: vp.y + vp.height / 2, x1: vp.x + 40, y1: vp.y + vp.height / 2, steps: 10 });
    await d.up();
    await sleep(300);
    const c1 = await readState(page);
    ok('H6 .canvas-image-viewport 上起手横拖 ⇒ 不切签 + 零 claim',
      c1.active === 'doc-b' && (c1.probe?.claims || 0) === (c0.probe?.claims || 0),
    `active=${c1.active} claims ${c0.probe?.claims}→${c1.probe?.claims}`);

    /* ── H7 内容区横滑「环式」语义未变（加法不破既有） ── */
    await setTab('doc-c');
    const r0 = await readState(page);
    d = await dragPath({
      x0: host.x + host.width - 40, y0: host.y + host.height / 2,
      x1: host.x + host.width - 160, y1: host.y + host.height / 2, steps: 10,
    });
    await d.up();
    await sleep(360);
    const r1 = await readState(page);
    d = await dragPath({
      x0: host.x + 40, y0: host.y + host.height / 2,
      x1: host.x + 160, y1: host.y + host.height / 2, steps: 10,
    });
    await d.up();
    await sleep(360);
    const r2 = await readState(page);
    ok('H7 内容区横滑仍为环式（末签左滑 → 首签；首签右滑 → 末签）',
      r0.active === 'doc-c' && r1.active === 'doc-a' && r2.active === 'doc-c',
    `环序 ${r0.active} —左滑→ ${r1.active} —右滑→ ${r2.active}`);

    /* ── H9 / H10 契约面 ── */
    const fin = await readState(page);
    ok('H9 签本体 touch-action=pan-y（横轴收指针管线 / 纵轴让原生）', fin.chipTouch === 'pan-y', `touch-action=${fin.chipTouch}`);
    ok('H10 环带透传契约未变（带 pointer-events:none / 签 auto）',
      fin.stripPointer === 'none' && fin.chipPointer === 'auto',
    `strip=${fin.stripPointer} chip=${fin.chipPointer}`);

    /* ── H8 零 pageerror ── */
    ok('H8 全程零 pageerror', pageErrors.length === 0, pageErrors.length ? pageErrors.join(' | ') : '0 error');
    rec.consoleErrors = { pass: consoleErrors.length === 0, detail: consoleErrors.slice(0, 5).join(' | ') || '0' };
  } catch (err) {
    fails += 1;
    lines.push(`FAIL  [${engine.name}]  EXCEPTION  — ${err && err.stack ? String(err.stack).split('\n')[0] : String(err)}`);
    console.log(lines[lines.length - 1]);
    rec.EXCEPTION = { pass: false, detail: String(err && err.message ? err.message : err) };
  } finally {
    await ctx.close().catch(() => {});
    await browser.close().catch(() => {});
    await new Promise((r) => server.close(r));
  }
  return { engine: engine.name, fails, lines, readings: rec, frames };
}

/* ══ 主流程 ═══════════════════════════════════════════════════════════ */
const report = {
  phase: PHASE,
  web: WEB,
  startedAt: new Date().toISOString(),
  engines: {},
  totals: { pass: 0, fail: 0, engines: 0 },
};
let hardFail = false;
for (const engine of ENGINES) {
  console.log(`\n═══ ${engine.name}（:${engine.port}，web=${WEB}）═══`);
  try {
    const r = await runEngine(engine);
    report.engines[engine.name] = r;
    report.totals.engines += 1;
    report.totals.fail += r.fails;
    report.totals.pass += Object.values(r.readings).filter((x) => x.pass).length;
    if (r.fails) hardFail = true;
  } catch (err) {
    hardFail = true;
    report.engines[engine.name] = { engine: engine.name, error: String(err && err.message ? err.message : err) };
    console.log(`FAIL  [${engine.name}]  BOOT  — ${err && err.message ? err.message : err}`);
  }
}
report.finishedAt = new Date().toISOString();
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(report, null, 2) + '\n');

console.log(`\n═══ 汇总（phase=${PHASE}）═══`);
for (const [name, r] of Object.entries(report.engines)) {
  console.log(`  ${name}: ${r.fails === 0 ? 'ALL PASS' : r.fails + ' FAIL'}（帧 ${r.frames ? r.frames.length : 0} 张）`);
}
console.log(`  证据目录 = ${OUT}`);
console.log(`  readings.json = ${join(OUT, 'readings.json')}`);
console.log(hardFail ? '\nRESULT: RED' : '\nRESULT: GREEN');
process.exit(hardFail ? 1 : 0);
