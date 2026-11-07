#!/usr/bin/env node
// mermaid-viewer-stability.spec.mjs — 框图渲染稳定性 + 点击看大图 验收
// （mermaid-viewer-stability 批 2026-11-06；作者报障逐字「然后还有的问题就是我们
//   canvas渲染的框图 好像是什么mairid 它渲染的时候有可能会导致canvas窗口异常 突然
//   变大变小 然后就是你气泡里渲染的 要点击能变成大图查看 canvas里的也要能支持」）。
//
// 被测真实链路（前端零替身：真模块图、真 CSS、真 vendored mermaid 12.2.0）：
//   气泡面   WS historyPage → persistence.js → 聊天气泡 → utils.renderMarkdownWithMath
//            → js/mermaidRenderer.js → js/fenceKit.js（水合/槽几何）
//   Canvas面 workspace-open-item → canvas.js → fileViewers → viewers/markdown.js
//            → 同一 renderMarkdownWithMath → 同一 mermaidRenderer/fenceKit
//   查看入口 js/lightbox.js（同一浮层组件的 svg 模式）
//
// 判据分两组（每条独立 PASS/FAIL；任一 FAIL ⇒ 退出码 1）：
//   S 稳定性（作者令第 1 条）
//     S1 首次异步渲染全过程逐帧采样：外层 #canvas-panel / #canvas-slide /
//        #canvas-content / .canvas-tab-pane / #main / #chat **零变化**（Δ=0），
//        并打印 before/after bounds 读数
//     S2 图槽＝图片位（mermaid-inline-center 批 2026-11-07 改制）：长图整幅落在
//        槽内、槽内零滚动；外壳高度仍然零变化（S1/S5）
//     S3 长图未被裁掉且槽内零滚动（scrollH == clientH）
//     S4 宽图落在内容列宽内
//     S5 连渲（一次 6 张）+ 主题切换后：外层零变化；图块高度零变化
//     S6 主题切换确实重画了（旧主题产物不得残留），且重画期间槽高不塌
//     S7 字体加载（document.fonts.ready）前后槽高不变
//     S8 零 ResizeObserver 回环告警、零 pageerror、零 console error
//   V 查看入口（作者令第 2 条）
//     V1 气泡内图点击 → 打开查看器，内容是**矢量 SVG**（不是位图）
//     V2 Canvas 文档内图点击 → 同一个浮层实例（一个组件，不是两套）
//     V3 缩放：控件放大/缩小/复位读数与 transform 同步
//     V4 平移：拖动改变 translate
//     V5 滚轮缩放到光标锚点
//     V6 关闭：ESC / 关闭钮 / 空白点击
//     V7 键盘可访问：真 button 可聚焦，Enter 打开，ESC 关闭
//     V8 误触闸：拖动超阈 / 文本选区 不打开
//     V9 手机 375 亮暗：可点开、可关闭、控件可点
//     V10 重复恢复（消息重渲染）后一次点击只开一次（不重复绑定）
//     V11 打开查看器零新增网络请求（不重复传输）
//
// 运行：node tests/mermaid-viewer-stability.spec.mjs
// 可选 env：SHOTS_DIR=<dir>
// 产物：截图 + 一段短视频（ctx recordVideo）+ summary.json（真实计数与读数）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = resolve(ROOT, 'src/main/resources/web');
const STAMP = new Date().toISOString().replace(/[-:T]/g, '').replace(/\..*$/, '');
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'reports-evidence', `mermaid-viewer-stability__${STAMP}__shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

const fence = (lang, body) => '```' + lang + '\n' + body + '\n```';

/* ── 夹具：长/宽两类图 + 一张正常图 + 一张坏图 ─────────────────────────
   「长」= 纵向 22 节点（内禀高度上千 px，最能把文档/卡片撑大）；
   「宽」= 横向 10 节点（内禀宽度上千 px，最容易被压成一条线）。 */
const TALL = 'flowchart TD\n  ' + Array.from({ length: 22 }, (_, i) => `T${i}[步骤 ${i}] --> T${i + 1}[步骤 ${i + 1}]`).join('\n  ');
const WIDE = 'flowchart LR\n  ' + Array.from({ length: 10 }, (_, i) => `W${i}[环节 ${i}] --> W${i + 1}[环节 ${i + 1}]`).join('\n  ');
const NORMAL = 'flowchart TD\n  A[开始 START] --> B{判断?}\n  B -->|是| C[完成 DONE]\n  B -->|否| D[结束]';
const SIX = Array.from({ length: 6 }, (_, i) => `flowchart LR\n  S${i}[连渲 ${i}] --> E${i}[终点 ${i}]`).join('#SPLIT#');
const BROKEN = 'flowchart TD\n  A[Unclosed ---> B[End]';

const TINY_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const CHAT_MSGS = [
  { type: 'ai', text: '① 正常图：\n\n' + fence('mermaid', NORMAL), timestamp: Date.now() - 9000 },
  { type: 'ai', text: '② 纵向长图：\n\n' + fence('mermaid', TALL), timestamp: Date.now() - 8000 },
  { type: 'ai', text: '③ 横向宽图：\n\n' + fence('mermaid', WIDE), timestamp: Date.now() - 7000 },
  { type: 'ai', text: '④ 坏图：\n\n' + fence('mermaid', BROKEN), timestamp: Date.now() - 6000 },
  { type: 'ai', text: '⑤ 位图（既有图片查看器回归）：\n\n![探针像素](' + TINY_PNG + ')', timestamp: Date.now() - 5000 },
];

const CANVAS_DOC = [
  '# Canvas 框图稳定性验收', '',
  '## 纵向长图', '', fence('mermaid', TALL), '',
  '## 横向宽图', '', fence('mermaid', WIDE), '',
  '## 正常图', '', fence('mermaid', NORMAL), '',
].join('\n');
const CANVAS_SIX = ['# 连渲验收', ''].concat(SIX.split('#SPLIT#').map((src, i) => `## 连渲 ${i}\n\n${fence('mermaid', src)}\n`)).join('\n');

const SID = 'mvs-session';
const P_CANVAS = '/tmp/nf-mvs/canvas-diagrams.md';
const P_SIX = '/tmp/nf-mvs/canvas-six.md';

/* ── 断言基座 ───────────────────────────────────────────────────────── */
let pass = 0;
let fail = 0;
const results = [];
const ok = (cond, label, extra = '') => {
  if (cond) pass++; else fail++;
  results.push({ ok: !!cond, label, extra });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  — ' + extra : ''}`);
};
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? Math.round(v * 10) / 10 : null);
const rectOf = (r) => (r ? [num(r[0]), num(r[1]), num(r[2]), num(r[3])] : null);

/* ── 引导（零后端、零登录：route 从磁盘注入 + WS 夹具）────────────────── */
async function boot(browser, { width, height, dark, recordVideo }) {
  const ctx = await browser.newContext({
    viewport: { width, height },
    colorScheme: dark ? 'dark' : 'light',
    ...(recordVideo ? { recordVideo: { dir: join(SHOTS_DIR, 'video'), size: { width: 900, height: 640 } } } : {}),
  });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow_token', 'neblink_token']) localStorage.setItem(k, 'e2e-token');
    for (const k of ['nebflow_locale', 'neblink_locale']) localStorage.setItem(k, 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
    // vendor mermaid 的 render 计数探针（「到底重画了几次」是库自己的调用次数）。
    let mm = null;
    Object.defineProperty(window, 'mermaid', {
      configurable: true,
      get() { return mm; },
      set(v) {
        mm = v;
        if (v && typeof v.render === 'function' && !v.__nfWrapped) {
          v.__nfWrapped = true;
          const orig = v.render.bind(v);
          v.render = (...a) => { window.__mmRenderCalls = (window.__mmRenderCalls || 0) + 1; return orig(...a); };
        }
      },
    });
    window.__mmRenderCalls = 0;
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(45000);
  const pageErrors = [];
  const consoleErrors = [];
  const reqs = [];
  page.on('pageerror', (e) => pageErrors.push(String((e && e.message) || e).slice(0, 240)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 240)); });
  page.on('request', (r) => reqs.push(r.url()));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // agent.json / api 面：夹具里没有，回空对象（不是 404 —— 探针实测这两条 404
    // 会被记成 console error，污染「零 console error」断言）。
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = join(WEB, p);
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  let pendingHist = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); sess();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') return ws.send(JSON.stringify({ type: 'pong' }));
      if (m.type === 'getConfig') return cfg();
      if (m.type === 'getSessions' || m.type === 'listSessions') return sess();
      if (m.type === 'getHistory') {
        // 历史**延后**到采样器就位再投：这样「首次异步渲染」是真的首次。
        if (pendingHist) return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: CHAT_MSGS, hasMore: false, offset: 0, total: CHAT_MSGS.length }));
        pendingHist = { ws, sessionId: m.sessionId };
        return;
      }
      if (m.type === 'pop.readFile') {
        const set = m.path === P_SIX ? { content: CANVAS_SIX, name: 'canvas-six.md' } : { content: CANVAS_DOC, name: 'canvas-diagrams.md' };
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, itemType: 'markdown', fileName: set.name, size: set.content.length, content: set.content }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForSelector('#chat', { timeout: 30000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await page.waitForTimeout(400);

  /** 投历史（点名为「首次渲染」的开始时刻）。 */
  const sendHistory = () => {
    const ref = pendingHist;
    if (!ref) return false;
    pendingHist = { ws: ref.ws, sessionId: ref.sessionId };
    ref.ws.send(JSON.stringify({ type: 'historyPage', sessionId: ref.sessionId, messages: CHAT_MSGS, hasMore: false, offset: 0, total: CHAT_MSGS.length }));
    return true;
  };
  return { ctx, page, pageErrors, consoleErrors, reqs, sendHistory };
}

/* ── 逐帧采样器 ─────────────────────────────────────────────────────── */
const INSTALL_SAMPLER = () => {
  const r = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return [b.x, b.y, b.width, b.height];
  };
  // 右缘取样点：面板横向漂移（实测 300px）时点名的那个元素链 —— 让「谁占了
  // 那块宽度」在读数里可见，不用猜。
  const chainAt = (x, y) => {
    let e = document.elementFromPoint(x, y);
    const out = [];
    while (e && e !== document.documentElement && out.length < 6) {
      out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\s+/).join('.') : ''));
      e = e.parentElement;
    }
    return out.join(' < ');
  };
  window.__s = { on: true, frames: [] };
  const tick = () => {
    if (!window.__s.on) return;
    const blocks = [...document.querySelectorAll('.nf-mermaid')];
    window.__s.frames.push({
      right: chainAt(window.innerWidth - 12, Math.round(window.innerHeight * 0.35)),
      sidebar: r('#sidebar'),
      act: r('#activity-bar'),
      t: performance.now(),
      panel: r('#canvas-panel'),
      slide: r('#canvas-slide'),
      content: r('#canvas-content'),
      pane: r('.canvas-tab-pane.active'),
      main: r('#main'),
      chat: r('#chat'),
      blocks: blocks.map((b) => {
        const br = b.getBoundingClientRect();
        const body = b.querySelector('.nf-mermaid-body');
        const bb = body ? body.getBoundingClientRect() : null;
        return [br.width, br.height, bb ? bb.width : 0, bb ? bb.height : 0];
      }),
      // 同位图块的身份键（源码前缀）：逐块跟踪塌陷必须按块对齐，不能把 A 块的
      // 最小值和 B 块的最大值混在一堆里（实测踩过：S6d 拿 72px 骨架和 480px
      // 长图相除，报了个假 FAIL）。
      keys: blocks.map((b) => (b.getAttribute('data-mermaid-src') || '').replace(/\s+/g, ' ').slice(0, 18)),
      mm: window.__mmRenderCalls || 0,
    });
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
};
const STOP_SAMPLER = () => { window.__s.on = false; return window.__s.frames; };

/** 逐帧读数汇总：某 selector 的 rect 在所有帧里是否恒等。 */
function stabilityOf(frames, key) {
  const vals = frames.map((f) => (f[key] ? f[key].map((v) => Math.round(v * 10) / 10) : null));
  const uniq = [...new Set(vals.map((v) => JSON.stringify(v)))];
  let maxDelta = 0;
  const first = vals.find((v) => v);
  for (const v of vals) {
    if (!v || !first) continue;
    for (let i = 0; i < 4; i++) maxDelta = Math.max(maxDelta, Math.abs(v[i] - first[i]));
  }
  return { stable: uniq.length <= 1, distinct: uniq.length, values: uniq, maxDelta: Math.round(maxDelta * 10) / 10, first, last: vals.filter(Boolean).slice(-1)[0] || null };
}

/** 面板漂移点名：首帧 vs 首次出现差异的那一帧，以及右缘元素链。
 *  作者令要求「区分图的内部尺寸变化与整个 Canvas/独立窗口尺寸改变」——这份
 *  读数就是为了让「谁动了外层」有名字，而不是靠猜。 */
function driftReport(frames, key) {
  const base = frames.find((f) => f[key]);
  if (!base) return null;
  const sig = (f) => JSON.stringify((f[key] || []).map((v) => Math.round(v * 10) / 10));
  const at = frames.findIndex((f) => sig(f) !== sig(base));
  const f = at >= 0 ? frames[at] : null;
  return {
    changedAtFrame: at, ofFrames: frames.length,
    from: rectOf(base[key]), to: f ? rectOf(f[key]) : null,
    rightBefore: base.right, rightAfter: f ? f.right : null,
    sidebar: base.sidebar ? rectOf(base.sidebar) : null, act: base.act ? rectOf(base.act) : null,
  };
}

/** 逐块塌陷读数（按源码前缀对齐）：每块窗口内最小/最大槽高。
 *  判据必须是「同一块自己不许回落到骨架」——跨块比大小是无意义的（见 keys 注）。 */
function collapseReport(frames) {
  const per = new Map();
  for (const f of frames) {
    const keys = f.keys || [];
    (f.blocks || []).forEach((b, i) => {
      const k = keys[i] || '#' + i;
      const h = b[3] || b[1] || 0;
      if (!h) return;
      const rec = per.get(k) || { min: Infinity, max: 0, samples: 0 };
      rec.min = Math.min(rec.min, h); rec.max = Math.max(rec.max, h); rec.samples++;
      per.set(k, rec);
    });
  }
  const rows = [...per.entries()].map(([k, r]) => ({ key: k, min: Math.round(r.min), max: Math.round(r.max), samples: r.samples }))
    .filter((r) => r.samples > 5);
  const worst = rows.slice().sort((a, b) => a.min / a.max - b.min / b.max)[0] || null;
  return { rows, worst };
}

/** 逐屏扫过（围栏是视口懒渲染：不滚过去永远停在 pending）。 */
async function sweep(page, sel) {
  await page.evaluate(async (s) => {
    const box = document.querySelector(s);
    if (!box) return;
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    box.scrollTop = 0; await sleep(180);
    const step = Math.max(120, Math.round(box.clientHeight * 0.5));
    for (let y = 0; y <= box.scrollHeight + step; y += step) { box.scrollTop = y; await sleep(140); }
    box.scrollTop = 0; await sleep(180);
  }, sel);
  await page.waitForTimeout(260);
}

async function settle(page, sel) {
  await page.waitForFunction((s) => {
    const live = [...document.querySelectorAll(s + ' .nf-mermaid')]
      .filter((e) => e.getAttribute('data-mermaid-state') === 'loading');
    return live.length === 0;
  }, sel, { timeout: 60000 });
  await page.waitForTimeout(350);
}

/** 等外壳布局落定（侧栏/分栏/画板开合是启动期自己的异步面）。
 *  **必须在装采样器之前**等它稳：否则采样器会把「启动期侧栏展开把 #main 推移
 *  300px」误算成「图渲染导致窗口跳变」（实测踩过：S1 报 maxDelta=300px）。 */
async function settleLayout(page, timeout = 8000) {
  const read = () => page.evaluate(() => {
    const r = (sel) => { const el = document.querySelector(sel); if (!el) return null; const b = el.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)].join(','); };
    return [r('#main'), r('#sidebar'), r('#chat'), r('#canvas-panel'), r('#input-area'), r('#activity-bar')].join('|');
  });
  const t0 = Date.now();
  let last = await read();
  let same = 0;
  while (Date.now() - t0 < timeout) {
    await page.waitForTimeout(150);
    const cur = await read();
    if (cur === last) same += 1; else { same = 0; last = cur; }
    if (same >= 3) return true;
  }
  return false;
}

async function openCanvasDoc(page, absPath, content, title) {
  await page.evaluate(({ absPath, content, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content, absPath, pinned: true },
    }));
  }, { absPath, content, title });
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll', { timeout: 25000 });
  await page.waitForTimeout(350);
}

/** 图槽读数（块高 / 槽高 / 上界 token / 是否可以滚到整图）。 */
const SLOT_READ = (needle) => {
  const blocks = [...document.querySelectorAll('.nf-mermaid')];
  const el = blocks.find((b) => (b.getAttribute('data-mermaid-src') || '').includes(needle));
  if (!el) return null;
  const body = el.querySelector('.nf-mermaid-body');
  const svg = body ? body.querySelector('svg') : null;
  const br = el.getBoundingClientRect();
  const bb = body ? body.getBoundingClientRect() : null;
  const sr = svg ? svg.getBoundingClientRect() : null;
  return {
    state: el.getAttribute('data-mermaid-state'),
    blockH: Math.round(br.height),
    slotH: bb ? Math.round(bb.height) : 0,
    slotW: bb ? Math.round(bb.width) : 0,
    slotScrollH: body ? body.scrollHeight : 0,
    slotClientH: body ? body.clientHeight : 0,
    svgW: sr ? Math.round(sr.width) : 0,
    svgH: sr ? Math.round(sr.height) : 0,
    svgAspect: sr && sr.height ? Math.round((sr.width / sr.height) * 100) / 100 : 0,
    openBtn: !!el.querySelector('.nf-figure-open'),
    hasSvg: !!svg,
  };
};

const browser = await chromium.launch();
const summary = { counts: { pass: 0, fail: 0 }, stability: {}, shots: SHOTS_DIR };
try {
  /* ══════════════ 桌面 1440：气泡面 + Canvas 面 ══════════════ */
  const { ctx, page, pageErrors, consoleErrors, reqs, sendHistory } = await boot(browser, { width: 1440, height: 900, dark: false, recordVideo: true });
  await settleLayout(page);
  await page.evaluate(INSTALL_SAMPLER);

  /* ── S1 气泡面首次异步渲染：外层逐帧零变化 ───────────────────────── */
  sendHistory();
  await page.waitForSelector('#chat .row', { timeout: 30000, state: 'attached' });
  await sweep(page, '#chat');
  await settle(page, '#chat');
  await page.waitForTimeout(500);
  const chatFrames = await page.evaluate(STOP_SAMPLER);
  const chatPanel = stabilityOf(chatFrames, 'panel');
  const chatMain = stabilityOf(chatFrames, 'main');
  const chatCol = stabilityOf(chatFrames, 'chat');
  summary.stability.chatFrames = chatFrames.length;
  summary.stability.chatPanel = { stable: chatPanel.stable, maxDelta: chatPanel.maxDelta, distinct: chatPanel.distinct, first: rectOf(chatPanel.first), last: rectOf(chatPanel.last) };
  summary.stability.chatMain = { stable: chatMain.stable, maxDelta: chatMain.maxDelta };
  const chatDrift = driftReport(chatFrames, 'panel');
  summary.stability.chatDrift = chatDrift;
  ok(chatPanel.stable && chatFrames.length > 60,
    `S1 气泡面首次异步渲染全过程（${chatFrames.length} 帧）外层 canvas 面板零变化`,
    `bounds ${JSON.stringify(rectOf(chatPanel.first))} 全程 maxDelta=${chatPanel.maxDelta}px ${chatPanel.stable ? '' : 'distinct=' + JSON.stringify(chatPanel.values) + ' drift=' + JSON.stringify(chatDrift)}`);
  ok(chatMain.stable && chatCol.stable,
    'S1b 同一过程 #main / #chat 列盒零变化',
    `main maxDelta=${chatMain.maxDelta}px / chat maxDelta=${chatCol.maxDelta}px`);

  /* ── S2/S3/S4 气泡面：长图/宽图槽几何 ──────────────────────────────
     mermaid-inline-center 批 2026-11-07：图槽＝图片位（作者令「直接像个图片一样
     嵌入在文档里，不需要可以上下滚动」）⇒ 上一批的 `--nf-figure-max-h` 上界与
     `overflow:auto` 已退役。同一条稳定判据改成「整幅在槽内、槽内零滚动」：
     裁图与内滚都不许出现，高度随图展开（外壳仍由 S1/S5 钉零变化）。 */
  const tallSlot = await page.evaluate(SLOT_READ, '步骤 0] --> T1');
  const wideSlot = await page.evaluate(SLOT_READ, '环节 0] --> W1');
  ok(!!tallSlot && tallSlot.state === 'done' && tallSlot.hasSvg, `S2a 纵向长图渲染成功（state=${tallSlot && tallSlot.state}）`);
  ok(!!tallSlot && tallSlot.slotH > 0 && Math.abs(tallSlot.slotH - tallSlot.svgH) <= 1,
    `S2b 纵向长图整幅落在槽内（槽 ${tallSlot && tallSlot.slotH}px = 图 ${tallSlot && tallSlot.svgH}px）`);
  ok(!!tallSlot && Math.abs(tallSlot.slotScrollH - tallSlot.slotClientH) <= 1,
    `S3 长图未被裁掉且槽内零滚动（槽内容 ${tallSlot && tallSlot.slotScrollH}px = 槽视口 ${tallSlot && tallSlot.slotClientH}px；svg ${tallSlot && tallSlot.svgW}x${tallSlot && tallSlot.svgH}）`);
  ok(!!wideSlot && wideSlot.svgW > 0 && wideSlot.slotW > 0 && wideSlot.svgW <= wideSlot.slotW + 1,
    `S4 横向宽图落在内容列宽内（图 ${wideSlot && wideSlot.svgW}px ≤ 槽 ${wideSlot && wideSlot.slotW}px）`);
  ok(!!tallSlot && tallSlot.blockH >= tallSlot.slotH, `S2c 图块含整幅图（块 ${tallSlot && tallSlot.blockH}px ≥ 槽 ${tallSlot && tallSlot.slotH}px）`);

  /* ── 取证：修前/修后对照（同一页、同一张图、只切「槽内滚动」这一条）──────
     不做两套环境：临时把内滚盒扣回槽上（max-height + overflow:auto = 上一批的
     几何），同一渲染链路上拍第二张，差别只可能来自这条改动。 */
  const chatClip = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
    if (!el) return null;
    el.scrollIntoView({ block: 'start' });
    const c = document.querySelector('#chat').getBoundingClientRect();
    return { x: Math.round(c.x), y: Math.round(c.y), width: Math.round(c.width), height: Math.min(860, Math.round(c.height)) };
  });
  await page.waitForTimeout(300);
  if (chatClip) {
    await page.screenshot({ path: join(SHOTS_DIR, 'after-chat-inline.png'), clip: chatClip });
    const beforeH = await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
      const b = el.querySelector('.nf-mermaid-body');
      return Math.round(b.getBoundingClientRect().height);
    });
    await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
      const b = el.querySelector('.nf-mermaid-body');
      b.style.maxHeight = '480px'; b.style.overflow = 'auto';
    });
    await page.waitForTimeout(400);
    const cappedH = await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
      const b = el.querySelector('.nf-mermaid-body');
      return Math.round(b.getBoundingClientRect().height);
    });
    await page.screenshot({ path: join(SHOTS_DIR, 'before-chat-scrollbox.png'), clip: chatClip });
    await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
      const b = el.querySelector('.nf-mermaid-body');
      b.style.maxHeight = ''; b.style.overflow = '';
    });
    await page.waitForTimeout(300);
    summary.stability.gallery = { inlineSlotH: beforeH, scrollBoxSlotH: cappedH, field: 'max-height/overflow' };
    ok(beforeH > cappedH + 100,
      `S2e 图槽确实是图片位而不是内滚盒（同一页：内滚盒 ${cappedH}px → 整幅 ${beforeH}px）`);
  }
  const badgePt = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + Math.min(120, r.height / 2) };
  });
  await page.waitForTimeout(250);
  if (badgePt) {
    await page.mouse.move(badgePt.x, badgePt.y);
    await page.waitForTimeout(350);
    await page.screenshot({ path: join(SHOTS_DIR, 'after-chat-badge-hover.png') });
  }

  /* ── 存量坏图：错误态仍可见、不空白 ─────────────────────────────── */
  const badState = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('Unclosed'));
    if (!el) return null;
    const pre = el.querySelector('.nf-fence-src');
    return { state: el.getAttribute('data-mermaid-state'), err: el.classList.contains('nf-fence-error'), src: pre ? !pre.hidden : false, btn: !!el.querySelector('.nf-figure-open') };
  });
  ok(!!badState && badState.state === 'error' && badState.err && badState.src === true,
    `S 坏图仍是可见错误态 + 回退原码（state=${badState && badState.state}）`);
  ok(!!badState && badState.btn === false, 'S 错误态不挂「看大图」角标');

  /* ── V1 气泡内点击 → 矢量大图查看器 ─────────────────────────────── */
  const chatSvgPoint = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    const svg = el ? el.querySelector('.nf-mermaid-body svg') : null;
    if (!svg) return null;
    svg.scrollIntoView({ block: 'center' });
    const r = svg.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height };
  });
  await page.waitForTimeout(250);
  await page.mouse.click(chatSvgPoint.x, chatSvgPoint.y);
  await page.waitForTimeout(500);
  const lb1 = await page.evaluate(() => {
    const o = document.querySelector('.nf-lightbox');
    if (!o) return null;
    const stage = o.querySelector('.nf-lightbox-stage');
    const cv = o.querySelector('.nf-lightbox-canvas');
    const svg = o.querySelector('.nf-lightbox-stage svg');
    const img = o.querySelector('.nf-lightbox-img');
    const sr = stage ? stage.getBoundingClientRect() : null;
    const cr = cv ? cv.getBoundingClientRect() : null;
    return {
      on: o.classList.contains('on'),
      mode: o.getAttribute('data-nf-lb-mode'),
      seq: Number(o.getAttribute('data-nf-open-seq') || 0),
      instances: document.querySelectorAll('.nf-lightbox').length,
      svgInStage: document.querySelectorAll('.nf-lightbox-stage svg').length,
      imgVisible: !!img && getComputedStyle(img).display !== 'none',
      aria: o.getAttribute('aria-label') || '',
      bar: !!o.querySelector('.nf-lightbox-bar'),
      pct: (o.querySelector('.nf-lightbox-pct') || {}).textContent || '',
      stageRect: sr ? [Math.round(sr.width), Math.round(sr.height)] : null,
      canvasRect: cr ? [Math.round(cr.width), Math.round(cr.height)] : null,
      vector: !!svg && svg.namespaceURI === 'http://www.w3.org/2000/svg',
    };
  });
  ok(!!lb1 && lb1.on && lb1.mode === 'svg' && lb1.svgInStage === 1 && lb1.vector,
    `V1 气泡内图点击 ⇒ 查看器打开且内容是矢量 SVG（mode=${lb1 && lb1.mode} svg=${lb1 && lb1.svgInStage} 矢量=${lb1 && lb1.vector}）`);
  ok(!!lb1 && lb1.instances === 1 && lb1.imgVisible === false,
    `V1b 浮层是单例且图片面无残影（实例 ${lb1 && lb1.instances}）`);
  ok(!!lb1 && lb1.bar === true && lb1.pct === '100%',
    `V1c 起始档 = 整幅可见 100%（读数 ${lb1 && lb1.pct}）`);
  ok(!!lb1 && lb1.canvasRect && lb1.stageRect && lb1.canvasRect[0] <= lb1.stageRect[0] && lb1.canvasRect[1] <= lb1.stageRect[1],
    `V1d 起始档图形落在舞台内（图 ${lb1 && JSON.stringify(lb1.canvasRect)} / 舞台 ${lb1 && JSON.stringify(lb1.stageRect)}）`);

  /* ── V3 缩放（控件）/ V4 平移 / V5 滚轮锚点 ─────────────────────── */
  const tf = async () => page.evaluate(() => {
    const c = document.querySelector('.nf-lightbox-canvas');
    const pct = (document.querySelector('.nf-lightbox-pct') || {}).textContent || '';
    const tr = c ? getComputedStyle(c).transform : 'none';
    const m = c ? new DOMMatrixReadOnly(tr === 'none' ? '' : tr) : null;
    return { scale: m ? Math.round(m.a * 1000) / 1000 : 0, tx: m ? Math.round(m.e) : 0, ty: m ? Math.round(m.f) : 0, pct };
  });
  const t0 = await tf();
  await page.locator('.nf-lightbox-ctl:has([data-lucide="zoom-in"])').click();
  await page.waitForTimeout(220);
  const t1 = await tf();
  ok(t1.scale > t0.scale + 0.05 && t1.pct !== t0.pct, `V3a 控件放大生效（${t0.pct}→${t1.pct}，scale ${t0.scale}→${t1.scale}）`);
  await page.locator('.nf-lightbox-ctl:has([data-lucide="zoom-out"])').click();
  await page.waitForTimeout(220);
  const t2 = await tf();
  ok(t2.scale < t1.scale, `V3b 控件缩小生效（${t1.pct}→${t2.pct}）`);
  const stageBox = await page.locator('.nf-lightbox-stage').boundingBox();
  await page.mouse.move(stageBox.x + stageBox.width / 2, stageBox.y + stageBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(stageBox.x + stageBox.width / 2 + 90, stageBox.y + stageBox.height / 2 + 40, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(200);
  const t3 = await tf();
  ok(Math.abs(t3.tx - t2.tx) > 40 || Math.abs(t3.ty - t2.ty) > 20,
    `V4 拖动平移生效（translate ${t2.tx},${t2.ty} → ${t3.tx},${t3.ty}）`);
  await page.mouse.move(stageBox.x + stageBox.width / 2, stageBox.y + stageBox.height / 2);
  await page.mouse.wheel(0, -240);
  await page.waitForTimeout(240);
  const t4 = await tf();
  ok(t4.scale > t3.scale + 0.02, `V5 滚轮缩放生效（${t3.pct}→${t4.pct}）`);
  await page.locator('.nf-lightbox-ctl:has([data-lucide="maximize-2"])').click();
  await page.waitForTimeout(240);
  const t5 = await tf();
  ok(Math.abs(t5.scale - 1) < 0.02 && t5.tx === 0 && t5.ty === 0, `V3c 复位回整幅档（scale=${t5.scale} tx=${t5.tx} ty=${t5.ty}）`);
  await page.screenshot({ path: join(SHOTS_DIR, 'viewer-desktop-light.png') });

  /* ── V11 打开查看器零新增请求 ───────────────────────────────────── */
  const reqsBefore = reqs.length;
  await page.keyboard.press('Escape');
  await page.waitForTimeout(250);
  await page.mouse.click(chatSvgPoint.x, chatSvgPoint.y);
  await page.waitForTimeout(400);
  const newReqs = reqs.slice(reqsBefore).filter((u) => !u.startsWith('data:'));
  ok(newReqs.length === 0, `V11 打开查看器零新增网络请求（${newReqs.length}）`, newReqs[0] || '');

  /* ── V6 关闭 + 计次 ─────────────────────────────────────────────── */
  const seqBeforeClose = await page.evaluate(() => Number((document.querySelector('.nf-lightbox') || {}).getAttribute?.('data-nf-open-seq') || 0));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  const closed = await page.evaluate(() => {
    const o = document.querySelector('.nf-lightbox');
    return { on: o ? o.classList.contains('on') : false, body: document.body.classList.contains('nf-lightbox-open') };
  });
  ok(closed.on === false && closed.body === false, `V6a ESC 关闭生效（on=${closed.on}）`);
  ok(seqBeforeClose >= 2, `V6b 每次打开独立计次（seq=${seqBeforeClose}）`);

  await page.mouse.click(chatSvgPoint.x, chatSvgPoint.y);
  await page.waitForTimeout(350);
  await page.locator('.nf-lightbox-close').click();
  await page.waitForTimeout(250);
  const closed2 = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(closed2 === false, 'V6c 关闭钮生效');
  await page.mouse.click(chatSvgPoint.x, chatSvgPoint.y);
  await page.waitForTimeout(350);
  await page.mouse.click(20, 20);
  await page.waitForTimeout(250);
  const closed3 = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(closed3 === false, 'V6d 点击浮层空白处关闭');

  /* ── V7 键盘可访问 ──────────────────────────────────────────────── */
  const btnInfo = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    const btn = el ? el.querySelector('.nf-figure-open') : null;
    if (!btn) return null;
    btn.focus();
    return { tag: btn.tagName, type: btn.getAttribute('type'), label: btn.getAttribute('aria-label') || '', focusable: document.activeElement === btn };
  });
  ok(!!btnInfo && btnInfo.tag === 'BUTTON' && btnInfo.type === 'button' && btnInfo.label.length > 0 && btnInfo.focusable === true,
    `V7a 图角标是真按钮且可聚焦（aria-label="${btnInfo && btnInfo.label}"）`);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(350);
  const kbOpen = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(kbOpen === true, 'V7b Enter 可打开查看器');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(250);
  const kbClosed = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(kbClosed === false, 'V7c ESC 可关闭查看器');

  /* ── V8 误触闸 ─────────────────────────────────────────────────── */
  await page.mouse.move(chatSvgPoint.x, chatSvgPoint.y);
  await page.mouse.down();
  await page.mouse.move(chatSvgPoint.x + 60, chatSvgPoint.y + 8, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  const afterDrag = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(afterDrag !== true, 'V8a 图上拖动（>8px）不打开查看器');
  await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    const t = el ? el.querySelector('svg text') : null;
    if (!t) return;
    const rng = document.createRange();
    rng.selectNodeContents(t);
    const sel = window.getSelection();
    sel.removeAllRanges(); sel.addRange(rng);
  });
  await page.mouse.click(chatSvgPoint.x, chatSvgPoint.y);
  await page.waitForTimeout(300);
  const afterSel = await page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
  ok(afterSel !== true, 'V8b 文本选区状态下点击不打开查看器');
  await page.evaluate(() => window.getSelection().removeAllRanges());

  /* ── V10 重复恢复：重渲染后一次点击只开一次 ─────────────────────── */
  // ① 恢复 = 用同一条真实管线按原始消息文本重灌这条气泡（存量消息恢复 / 重渲染
  //    的真实路径），并把全局委托重复 init 两次（叠监听是「重复绑定」的另一张脸）。
  const FIXTURE_TEXT = '① 正常图：\n\n' + fence('mermaid', NORMAL);
  await page.evaluate(async (txt) => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const bubble = [...document.querySelectorAll('#chat .bubble.ai')]
      .find((b) => (b.textContent || '').includes('判断?'));
    if (bubble) bubble.innerHTML = renderMarkdownWithMath(txt, true, { cache: false });
    const { initLightbox } = await import('/js/lightbox.js');
    initLightbox();   // 再 arm 两次：幂等闸必须吃掉
    initLightbox();
  }, FIXTURE_TEXT);
  await page.waitForTimeout(1600);
  await sweep(page, '#chat');
  await settle(page, '#chat');
  const reHydrated = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    return el ? { state: el.getAttribute('data-mermaid-state'), svg: !!el.querySelector('svg'), btn: !!el.querySelector('.nf-figure-open') } : null;
  });
  ok(!!reHydrated && reHydrated.svg === true && reHydrated.btn === true,
    `V10a 重渲染后图自动恢复且角标重建（state=${reHydrated && reHydrated.state} btn=${reHydrated && reHydrated.btn}）`);
  const seqPre = await page.evaluate(() => Number((document.querySelector('.nf-lightbox') || {}).getAttribute?.('data-nf-open-seq') || 0));
  const rePoint = await page.evaluate(() => {
    const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    const svg = el ? el.querySelector('.nf-mermaid-body svg') : null;
    if (!svg) return null;
    svg.scrollIntoView({ block: 'center' });
    const r = svg.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  ok(!!rePoint, 'V10b 重渲染后图仍有可点面');
  if (rePoint) {
    await page.waitForTimeout(250);
    await page.mouse.click(rePoint.x, rePoint.y);
    await page.waitForTimeout(400);
    const seqPost = await page.evaluate(() => Number((document.querySelector('.nf-lightbox') || {}).getAttribute?.('data-nf-open-seq') || 0));
    ok(seqPost - seqPre === 1, `V10b 一次点击恰好开一次（seq ${seqPre}→${seqPost}，重复绑定会跳 2）`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
  }

  /* ── 既有图片查看器回归（同一个组件的 img 模式没被破坏）───────────── */
  const imgPt = await page.evaluate(() => {
    const img = document.querySelector('#chat img.nf-zoom-img');
    if (!img) return null;
    img.scrollIntoView({ block: 'center' });
    const r = img.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width };
  });
  ok(!!imgPt && imgPt.w > 0, 'R1 既有图片查看器入口（img.nf-zoom-img）仍在');
  if (imgPt && imgPt.w > 0) {
    await page.waitForTimeout(200);
    await page.mouse.click(imgPt.x, imgPt.y);
    await page.waitForTimeout(600);
    const imgMode = await page.evaluate(() => {
      const o = document.querySelector('.nf-lightbox');
      if (!o) return null;
      const i = o.querySelector('.nf-lightbox-img');
      return { on: o.classList.contains('on'), mode: o.getAttribute('data-nf-lb-mode'), img: !!i && getComputedStyle(i).display !== 'none' };
    });
    ok(!!imgMode && imgMode.on === true && imgMode.mode === 'img' && imgMode.img === true,
      `R1b 图片仍走 img 模式（mode=${imgMode && imgMode.mode}）`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
  }

  /* ── V2 Canvas 文档内点击 → 同一浮层组件 ────────────────────────── */
  await openCanvasDoc(page, P_CANVAS, CANVAS_DOC, 'canvas-diagrams.md');
  await settleLayout(page);            // 画板开合/宽度过渡落定后再采（跳变不是图造成的）
  await page.evaluate(INSTALL_SAMPLER);
  await sweep(page, '.canvas-tab-pane.active .canvas-md-scroll');
  await settle(page, '.canvas-tab-pane.active .canvas-md-scroll');
  await page.waitForTimeout(400);
  const cvFrames = await page.evaluate(STOP_SAMPLER);
  const cvPanel = stabilityOf(cvFrames, 'panel');
  const cvPane = stabilityOf(cvFrames, 'pane');
  const cvContent = stabilityOf(cvFrames, 'content');
  summary.stability.canvasFrames = cvFrames.length;
  summary.stability.canvasPanel = { stable: cvPanel.stable, maxDelta: cvPanel.maxDelta, distinct: cvPanel.distinct, first: rectOf(cvPanel.first), last: rectOf(cvPanel.last) };
  ok(cvPanel.stable && cvPane.stable && cvContent.stable && cvFrames.length > 40,
    `S1c Canvas 文档面渲染全过程（${cvFrames.length} 帧）外层面板/页签/内容区零变化`,
    `panel bounds ${JSON.stringify(rectOf(cvPanel.first))} maxDelta=${cvPanel.maxDelta}px`);

  const cvTall = await page.evaluate(SLOT_READ, '步骤 0] --> T1');
  // mermaid-inline-center 批：Canvas 面同一张图槽＝图片位（整幅在槽内、槽内零滚动），
  // 不再是上一批的「槽高受限 + 槽内滚动」。
  ok(!!cvTall && cvTall.state === 'done' && cvTall.slotH > 0 && Math.abs(cvTall.slotH - cvTall.svgH) <= 1
    && Math.abs(cvTall.slotScrollH - cvTall.slotClientH) <= 1,
    `S2d Canvas 文档面长图整幅落在槽内且零内滚（槽 ${cvTall && cvTall.slotH}px = 图 ${cvTall && cvTall.svgH}px）`);

  const cvPoint = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    const svg = el ? el.querySelector('.nf-mermaid-body svg') : null;
    if (!svg) return null;
    svg.scrollIntoView({ block: 'center' });
    const r = svg.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  await page.waitForTimeout(250);
  await page.mouse.click(cvPoint.x, cvPoint.y);
  await page.waitForTimeout(500);
  const lb2 = await page.evaluate(() => {
    const o = document.querySelector('.nf-lightbox');
    return { on: o ? o.classList.contains('on') : false, instances: document.querySelectorAll('.nf-lightbox').length, mode: o ? o.getAttribute('data-nf-lb-mode') : '', svg: document.querySelectorAll('.nf-lightbox-stage svg').length };
  });
  ok(lb2.on === true && lb2.instances === 1 && lb2.mode === 'svg' && lb2.svg === 1,
    `V2 Canvas 文档内图点击 ⇒ 同一个浮层组件打开（实例 ${lb2.instances} / svg ${lb2.svg}）`);
  await page.screenshot({ path: join(SHOTS_DIR, 'viewer-canvas-desktop-light.png') });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(250);

  /* ── S5/S6 连渲 + 主题切换 ─────────────────────────────────────── */
  await openCanvasDoc(page, P_SIX, CANVAS_SIX, 'canvas-six.md');
  await settleLayout(page);
  await page.evaluate(INSTALL_SAMPLER);
  await sweep(page, '.canvas-tab-pane.active .canvas-md-scroll');
  await settle(page, '.canvas-tab-pane.active .canvas-md-scroll');
  await page.waitForTimeout(600);
  const sixFrames = await page.evaluate(STOP_SAMPLER);
  const sixPanel = stabilityOf(sixFrames, 'panel');
  const sixCount = await page.evaluate(() => document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid[data-mermaid-state="done"]').length);
  ok(sixCount >= 6, `S5a 连渲 6 张全部落定（${sixCount}）`);
  ok(sixPanel.stable && sixFrames.length > 30, `S5b 连渲全过程外层面板零变化（${sixFrames.length} 帧 maxDelta=${sixPanel.maxDelta}px）`);

  const callsBeforeTheme = await page.evaluate(() => window.__mmRenderCalls || 0);
  const blocksBeforeTheme = await page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')].map((b) => Math.round(b.getBoundingClientRect().height)));
  await page.evaluate(INSTALL_SAMPLER);
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(2600);
  await settle(page, '.canvas-tab-pane.active .canvas-md-scroll');
  await page.waitForTimeout(400);
  const themeFrames = await page.evaluate(STOP_SAMPLER);
  const callsAfterTheme = await page.evaluate(() => window.__mmRenderCalls || 0);
  const blocksAfterTheme = await page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')].map((b) => Math.round(b.getBoundingClientRect().height)));
  const themePanel = stabilityOf(themeFrames, 'panel');
  const collapse = collapseReport(themeFrames);
  const themeDrift = driftReport(themeFrames, 'panel');
  summary.stability.theme = { callsBefore: callsBeforeTheme, callsAfter: callsAfterTheme, blocksBefore: blocksBeforeTheme, blocksAfter: blocksAfterTheme, collapse: collapse.rows, panelStable: themePanel.stable, panelMaxDelta: themePanel.maxDelta, drift: themeDrift, frames: themeFrames.length };
  ok(callsAfterTheme - callsBeforeTheme >= 6,
    `S6a 主题翻转确实重画了（mermaid render 调用 ${callsBeforeTheme}→${callsAfterTheme}）`);
  const wiring = await page.evaluate(async () => {
    const k = await import('/js/fenceKit.js');
    return { kinds: k.fenceKindNames(), requeues: k.themeRequeueCount() };
  });
  summary.stability.wiring = wiring;
  ok(wiring.kinds.includes('mermaid') && wiring.kinds.includes('svg') && wiring.requeues >= 1,
    `S6a2 水合/主题换图腿确实 armed（族=${wiring.kinds.join(',')} / 换图触发 ${wiring.requeues} 次）`);
  ok(themePanel.stable, `S6b 主题切换重画全过程外层面板零变化（maxDelta=${themePanel.maxDelta}px / ${themeFrames.length} 帧）${themePanel.stable ? '' : ' drift=' + JSON.stringify(themeDrift)}`);
  const heightDelta = blocksBeforeTheme.length === blocksAfterTheme.length
    ? Math.max(...blocksBeforeTheme.map((v, i) => Math.abs(v - blocksAfterTheme[i])))
    : 999;
  ok(heightDelta <= 1, `S6c 重画后图块高度零变化（最大 Δ=${heightDelta}px / before=${JSON.stringify(blocksBeforeTheme)} after=${JSON.stringify(blocksAfterTheme)}）`);
  ok(!!collapse.worst && collapse.worst.min >= collapse.worst.max * 0.85,
    `S6d 重画期间每块槽高不塌（最差块 ${collapse.worst ? collapse.worst.key : '-'} 最小 ${collapse.worst ? collapse.worst.min : 0}px / 自身稳定 ${collapse.worst ? collapse.worst.max : 0}px，${collapse.rows.length} 块）`);
  await page.screenshot({ path: join(SHOTS_DIR, 'canvas-diagrams-dark.png') });

  /* ── S7 字体加载前后槽高 ───────────────────────────────────────── */
  const fontBefore = await page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')].map((b) => Math.round(b.getBoundingClientRect().height)));
  await page.evaluate(async () => { if (document.fonts && document.fonts.ready) await document.fonts.ready; });
  await page.waitForTimeout(600);
  const fontAfter = await page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')].map((b) => Math.round(b.getBoundingClientRect().height)));
  const fontDelta = fontBefore.length === fontAfter.length ? Math.max(...fontBefore.map((v, i) => Math.abs(v - fontAfter[i]))) : 999;
  ok(fontDelta === 0, `S7 字体加载完成后图块高度零变化（最大 Δ=${fontDelta}px）`);

  /* ── S8 零回环 / 零异常 ────────────────────────────────────────── */
  const roLoops = consoleErrors.filter((m) => /ResizeObserver loop/i.test(m));
  ok(roLoops.length === 0, `S8a 零 ResizeObserver 回环告警（${roLoops.length}）`, roLoops[0] || '');
  ok(pageErrors.length === 0, `S8b 零未捕获异常（${pageErrors.length}）`, pageErrors[0] || '');
  const realConsole = consoleErrors.filter((m) => !/ResizeObserver loop/i.test(m) && !/favicon/i.test(m));
  ok(realConsole.length === 0, `S8c 零 console error（${realConsole.length}）`, realConsole.slice(0, 2).join(' | '));
  summary.consoleErrors = consoleErrors.slice(0, 10);

  const video = page.video();
  await ctx.close();
  if (video) {
    try { summary.video = await video.path(); console.log(`\nvideo: ${summary.video}`); } catch (e) { /* 无录制 */ }
  }

  /* ══════════════ 手机 375 亮/暗：点开 + 缩放 + 关闭 ══════════════ */
  for (const dark of [false, true]) {
    const m = await boot(browser, { width: 375, height: 812, dark });
    const cdp = await m.ctx.newCDPSession(m.page);
    m.sendHistory();
    await m.page.waitForSelector('#chat .row', { timeout: 30000, state: 'attached' });
    await sweep(m.page, '#chat');
    await settle(m.page, '#chat');
    const mobileSlot = await m.page.evaluate(SLOT_READ, '步骤 0] --> T1');
    ok(!!mobileSlot && mobileSlot.slotH > 0 && Math.abs(mobileSlot.slotH - mobileSlot.svgH) <= 1
      && Math.abs(mobileSlot.slotScrollH - mobileSlot.slotClientH) <= 1,
      `S9 375 档${dark ? '暗' : '亮'}：长图整幅落在槽内且零内滚（槽 ${mobileSlot && mobileSlot.slotH}px = 图 ${mobileSlot && mobileSlot.svgH}px）`);
    const mPt = await m.page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
      const svg = el ? el.querySelector('.nf-mermaid-body svg') : null;
      if (!svg) return null;
      svg.scrollIntoView({ block: 'center' });
      const r = svg.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    });
    await m.page.waitForTimeout(250);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: mPt.x, y: mPt.y }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await m.page.waitForTimeout(600);
    const mV = await m.page.evaluate(() => {
      const o = document.querySelector('.nf-lightbox');
      if (!o) return null;
      const ctl = o.querySelector('.nf-lightbox-ctl');
      const cr = ctl ? ctl.getBoundingClientRect() : null;
      return {
        on: o.classList.contains('on'), mode: o.getAttribute('data-nf-lb-mode'),
        svg: document.querySelectorAll('.nf-lightbox-stage svg').length,
        bar: !!o.querySelector('.nf-lightbox-bar'),
        ctl: cr ? [Math.round(cr.width), Math.round(cr.height), Math.round(cr.x), Math.round(cr.y)] : null,
        back: !!o.querySelector('.mfs-back'),
        mobileLayer: document.body.classList.contains('mobile-layer'),
      };
    });
    ok(!!mV && mV.on === true && mV.svg === 1 && mV.bar === true,
      `V9a 375 档${dark ? '暗' : '亮'}：真实触摸点开查看器（mode=${mV && mV.mode} svg=${mV && mV.svg}）`);
    ok(!!mV && mV.ctl && mV.ctl[0] >= 28 && mV.ctl[1] >= 28,
      `V9b 375 档${dark ? '暗' : '亮'}：缩放控件可点（${mV && JSON.stringify(mV.ctl)}）`);
    if (mV && mV.ctl) {
      const cx = mV.ctl[2] + mV.ctl[0] / 2;
      const cy = mV.ctl[3] + mV.ctl[1] / 2;
      const pctBefore = await m.page.evaluate(() => (document.querySelector('.nf-lightbox-pct') || {}).textContent);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cx, y: cy }] });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await m.page.waitForTimeout(300);
      const pctAfter = await m.page.evaluate(() => (document.querySelector('.nf-lightbox-pct') || {}).textContent);
      ok(pctAfter !== pctBefore, `V9c 375 档${dark ? '暗' : '亮'}：触摸缩放生效（${pctBefore}→${pctAfter}）`);
    }
    await m.page.screenshot({ path: join(SHOTS_DIR, `viewer-mobile-375-${dark ? 'dark' : 'light'}.png`) });
    await m.page.evaluate(() => { const b = document.querySelector('.nf-lightbox .mfs-back'); if (b) b.click(); else document.querySelector('.nf-lightbox-close').click(); });
    await m.page.waitForTimeout(350);
    const mClosed = await m.page.evaluate(() => (document.querySelector('.nf-lightbox') || {}).classList?.contains('on'));
    ok(mClosed === false, `V9d 375 档${dark ? '暗' : '亮'}：可关闭返回`);
    await m.page.screenshot({ path: join(SHOTS_DIR, `chat-375-${dark ? 'dark' : 'light'}.png`) });
    await m.ctx.close();
  }
} finally {
  await browser.close();
}

summary.counts = { pass, fail, total: pass + fail };
writeFileSync(join(SHOTS_DIR, 'summary.json'), JSON.stringify(summary, null, 2));
writeFileSync(join(SHOTS_DIR, 'results.txt'), results.map((r) => `${r.ok ? 'PASS' : 'FAIL'}  ${r.label}${r.extra ? '  — ' + r.extra : ''}`).join('\n'));
console.log(`\nshots: ${SHOTS_DIR}`);
console.log(`PASS ${pass} / FAIL ${fail} / total ${pass + fail}`);
console.log(fail === 0 ? 'ALL PASS' : `FAILED: ${fail}`);
process.exit(fail === 0 ? 0 : 1);
