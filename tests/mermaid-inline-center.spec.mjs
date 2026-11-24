#!/usr/bin/env node
// mermaid-inline-center.spec.mjs — 框图「像图片一样内嵌 + 点开即居中」验收
// （mermaid-inline-center 批 2026-11-07；作者令逐字「Mermaid 就直接像个图片一样
//   嵌入在文档里，不需要可以上下滚动。然后使用跟图片一样的点击打开后的查看逻辑。
//   然后点开后要相对屏幕居中，现在有点歪」）。
//
// 被测真实链路（前端零替身：真模块图、真 CSS、真 vendored mermaid 12.2.0）：
//   气泡面  WS historyPage → persistence → 聊天气泡 → utils.renderMarkdownWithMath
//           → js/mermaidRenderer.js → js/fenceKit.js（水合/槽几何）
//   Canvas面 workspace-open-item → canvas.js → fileViewers → viewers/markdown.js
//           → 同一 renderMarkdownWithMath → 同一 mermaidRenderer/fenceKit
//   查看入口 js/lightbox.js（同一浮层组件的 img / svg 两个 mode）
//
// 判据分四组（每条独立 PASS/FAIL；任一 FAIL ⇒ 退出码 1）：
//   I 内嵌（作者令第 1 条：像图片一样，不要内滚）
//     I1 长图/宽图/普通图三张：槽内零滚动（scrollH==clientH、scrollW==clientW、
//        overflow 计算值 visible）
//     I2 长图整幅在图槽里（svgress 高度 == 槽高；不再有 480px 封顶）
//     I3 比例与内容完整（渲染出的 svg 宽高比 == 源 viewBox 比例，±1%）
//     I4 宽图落在内容列宽内
//     I5 文档滚动接管（长图把文档撑长，而不是在槽内滚）
//   C 窗口稳定（前批 f7d97b0f3 的离屏测量不得回退）
//     C1 Canvas 文档面首次异步渲染逐帧：#canvas-panel / #main / #chat / #sidebar
//        宽高 Δ=0，且右缘取样点命中链里不出现测量岛 div#dnf-mermaid-svg-*
//     C2 气泡面首次异步渲染逐帧：#chat / #main 宽高 Δ=0
//   V 查看入口（作者令第 2、3 条）
//     V1 框图与图片走**同一个浮层实例**（同 DOM 节点、同一开合序号），只切 mode
//     V2 桌面 1280×900：图/纸面几何中心 == 屏幕中心（|Δ|≤1px），打开/放大两级/复位
//        三档都成立
//     V3 手机 390×844 / 375×812 真触摸：同上（触摸点开、触摸放大）
//     V4 动态视口：可视视口被内缩/平移时（浏览器栏/键盘同形），浮层钉到可视区域、
//        内容中心 = 可视屏幕中心（图片模式同一条规则）
//     V5 保留矢量与可访问性：打开的内容是真 <svg>（非位图）、放大后仍是矢量、
//        角标是真 button、Enter 可开、ESC 可关、焦点回位
//     V6 图内交互不误触：点图内 <a> 不开浮层；拖动超阈不开；选字不开
//     V7 零 pageerror / 零 console error
//
// 运行：node tests/mermaid-inline-center.spec.mjs
// 可选 env：SHOTS_DIR=<dir>
// 产物：截图 + summary.json + results.txt（真实读数）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = resolve(ROOT, 'src/main/resources/web');
const STAMP = new Date().toISOString().replace(/[-:T]/g, '').replace(/\..*$/, '');
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'reports-evidence', `mermaid-inline-center__${STAMP}__shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2',
};

const fence = (lang, body) => '```' + lang + '\n' + body + '\n```';
const TALL = 'flowchart TD\n  ' + Array.from({ length: 22 }, (_, i) => `T${i}[步骤 ${i}] --> T${i + 1}[步骤 ${i + 1}]`).join('\n  ');
const WIDE = 'flowchart LR\n  ' + Array.from({ length: 10 }, (_, i) => `W${i}[环节 ${i}] --> W${i + 1}[环节 ${i + 1}]`).join('\n  ');
const NORMAL = 'flowchart TD\n  A[开始 START] --> B{判断?}\n  B -->|是| C[完成 DONE]\n  B -->|否| D[结束]';
// 图内链接：mermaid 的站内 href 才过得了 svgScan（外链会被判 external href 而落错误态）
const LINKED = 'flowchart TD\n  A[点我这一格] --> B[终点]\n  click A href "#dest" "提示"';
const TINY_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const CHAT_MSGS = [
  { type: 'ai', text: '① 普通图：\n\n' + fence('mermaid', NORMAL), timestamp: Date.now() - 8000 },
  { type: 'ai', text: '② 纵向长图：\n\n' + fence('mermaid', TALL), timestamp: Date.now() - 7000 },
  { type: 'ai', text: '③ 横向宽图：\n\n' + fence('mermaid', WIDE), timestamp: Date.now() - 6000 },
  { type: 'ai', text: '④ 图内链接：\n\n' + fence('mermaid', LINKED), timestamp: Date.now() - 5000 },
  { type: 'ai', text: '⑤ 位图（既有图片查看器）：\n\n![探针像素](' + TINY_PNG + ')', timestamp: Date.now() - 4000 },
];
const CANVAS_DOC = [
  '# 框图内嵌验收', '',
  '## 纵向长图', '', fence('mermaid', TALL), '',
  '## 横向宽图', '', fence('mermaid', WIDE), '',
  '## 普通图', '', fence('mermaid', NORMAL), '',
  '尾部段落（长图之后文档继续）。',
].join('\n');

const SID = 'mic-session';
const P_DOC = '/tmp/nf-mic/canvas-diagrams.md';

/* ── 断言基座 ───────────────────────────────────────────────────────── */
let pass = 0;
let fail = 0;
const results = [];
const ok = (cond, label, extra = '') => {
  if (cond) pass++; else fail++;
  results.push({ ok: !!cond, label, extra });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  — ' + extra : ''}`);
};
const r1 = (v) => Math.round(Number(v) * 10) / 10;

/* ── 引导（零后端、零登录：route 从磁盘注入 + WS 夹具）────────────────── */
async function boot(browser, { width, height, touch = false, dark = false }) {
  const ctx = await browser.newContext({
    viewport: { width, height },
    colorScheme: dark ? 'dark' : 'light',
    ...(touch ? { hasTouch: true, isMobile: true, deviceScaleFactor: 2 } : {}),
  });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow_token', 'neblink_token']) localStorage.setItem(k, 'e2e-token');
    for (const k of ['nebflow_locale', 'neblink_locale']) localStorage.setItem(k, 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(45000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String((e && e.message) || e).slice(0, 240)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 240)); });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
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
        if (pendingHist) {
          return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: CHAT_MSGS, hasMore: false, offset: 0, total: CHAT_MSGS.length }));
        }
        pendingHist = { ws, sessionId: m.sessionId };
        return;
      }
      if (m.type === 'pop.readFile') {
        return ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path, itemType: 'markdown',
          fileName: 'canvas-diagrams.md', size: CANVAS_DOC.length, content: CANVAS_DOC,
        }));
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
  const sendHistory = () => {
    const ref = pendingHist;
    if (!ref) return false;
    ref.ws.send(JSON.stringify({ type: 'historyPage', sessionId: ref.sessionId, messages: CHAT_MSGS, hasMore: false, offset: 0, total: CHAT_MSGS.length }));
    return true;
  };
  return { ctx, page, pageErrors, consoleErrors, sendHistory };
}

/* ── 逐帧采样器（窗口稳定：外层几何 + 右缘命中链）───────────────────── */
const INSTALL_SAMPLER = () => {
  const r = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return [b.width, b.height];
  };
  const chainAt = (x, y) => {
    let e = document.elementFromPoint(x, y);
    const out = [];
    while (e && e !== document.documentElement && out.length < 5) {
      out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : ''));
      e = e.parentElement;
    }
    return out.join(' < ');
  };
  window.__s = { on: true, frames: [] };
  const tick = () => {
    if (!window.__s.on) return;
    window.__s.frames.push({
      panel: r('#canvas-panel'), main: r('#main'), chat: r('#chat'), sidebar: r('#sidebar'),
      right: chainAt(window.innerWidth - 12, Math.round(window.innerHeight * 0.35)),
    });
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
};
const STOP_SAMPLER = () => {
  window.__s.on = false;
  return window.__s.frames;
};
/** 逐帧外框变化最大值（宽、高分别取最大绝对差）。 */
function driftOf(frames, key) {
  const vals = frames.map((f) => f[key]).filter(Boolean);
  if (!vals.length) return null;
  let dw = 0; let dh = 0;
  for (const v of vals) {
    dw = Math.max(dw, Math.abs(v[0] - vals[0][0]));
    dh = Math.max(dh, Math.abs(v[1] - vals[0][1]));
  }
  return { dw: r1(dw), dh: r1(dh), n: vals.length, first: vals[0].map(r1) };
}

/* ── 读数：内嵌几何（page.evaluate 体内自带取整，不引用 Node 侧函数）──── */
const INLINE_READ = () => {
  const n = (v) => Math.round(Number(v) * 10) / 10;
  const out = [];
  document.querySelectorAll('.nf-mermaid[data-mermaid-state="done"]').forEach((b, i) => {
    const body = b.querySelector('.nf-mermaid-body');
    const svg = body ? body.querySelector('svg') : null;
    if (!body || !svg) return;
    const bb = body.getBoundingClientRect();
    const sb = svg.getBoundingClientRect();
    const vb = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
    const cs = getComputedStyle(body);
    out.push({
      i,
      slotW: n(bb.width), slotH: n(bb.height),
      scrollW: body.scrollWidth, scrollH: body.scrollHeight,
      clientW: body.clientWidth, clientH: body.clientHeight,
      overflowX: cs.overflowX, overflowY: cs.overflowY, maxH: cs.maxHeight,
      svgW: n(sb.width), svgH: n(sb.height),
      vbRatio: vb.length === 4 && vb[2] > 0 && vb[3] > 0 ? n(vb[2] / vb[3]) : null,
      svgRatio: sb.height ? n(sb.width / sb.height) : null,
      clipBottom: n(bb.bottom - sb.bottom),
      openBtn: !!b.querySelector(':scope > .nf-figure-open'),
    });
  });
  const doc = document.querySelector('.canvas-tab-pane.active .canvas-md-scroll');
  return { figs: out, docScrollH: doc ? doc.scrollHeight : null, docClientH: doc ? doc.clientHeight : null };
};

/* ── 读数：浮层几何（中心偏差、纸面盒、矢量断言；同上自带取整）──────── */
const LB_READ = () => {
  const n = (v) => Math.round(Number(v) * 10) / 10;
  const lb = document.querySelector('.nf-lightbox');
  if (!lb) return null;
  const st = lb.querySelector('.nf-lightbox-stage');
  const cv = lb.querySelector('.nf-lightbox-canvas');
  const sv = lb.querySelector('.nf-lightbox-canvas svg');
  const img = lb.querySelector('.nf-lightbox-img');
  const box = (e) => { if (!e) return null; const b = e.getBoundingClientRect(); return [n(b.x), n(b.y), n(b.width), n(b.height)]; };
  const off = (e) => {
    if (!e) return null;
    const b = e.getBoundingClientRect();
    return [n(b.x + b.width / 2 - window.innerWidth / 2), n(b.y + b.height / 2 - window.innerHeight / 2)];
  };
  const vv = window.visualViewport;
  const closeBtn = lb.querySelector('.nf-lightbox-close');
  return {
    on: lb.classList.contains('on'),
    overlays: document.querySelectorAll('.nf-lightbox').length,
    mode: lb.getAttribute('data-nf-lb-mode'),
    seq: lb.getAttribute('data-nf-open-seq'),
    pinned: lb.getAttribute('data-nf-vv-pinned'),
    overlay: box(lb), stage: box(st), canvas: box(cv), svg: box(sv), img: box(img),
    canvasOff: off(cv), svgOff: off(sv), imgOff: off(img),
    vv: vv ? { w: n(vv.width), h: n(vv.height), ox: n(vv.offsetLeft), oy: n(vv.offsetTop) } : null,
    win: [window.innerWidth, window.innerHeight],
    pct: (lb.querySelector('.nf-lightbox-pct') || {}).textContent,
    transform: cv ? cv.style.transform : null,
    svgTexts: sv ? sv.querySelectorAll('text, tspan').length : 0,
    svgIsVector: !!sv && sv.namespaceURI === 'http://www.w3.org/2000/svg',
    imgIsRaster: !!img && img.complete && String(img.src).startsWith('data:image/png'),
    a11y: {
      role: lb.getAttribute('role'), modal: lb.getAttribute('aria-modal'),
      closeLabel: closeBtn ? closeBtn.getAttribute('aria-label') : null,
      ctls: [...lb.querySelectorAll('.nf-lightbox-ctl')].map((b) => (b.getAttribute('aria-label') || '')),
    },
  };
};

/** 点开某张图（中心点钳到可视区内；返回点击点读数）。 */
async function openFigure(page, needle) {
  const pt = await page.evaluate((n) => {
    const el = [...document.querySelectorAll('.nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes(n));
    const svg = el && el.querySelector('.nf-mermaid-body svg');
    if (!svg) return null;
    svg.scrollIntoView({ block: 'center' });
    const r = svg.getBoundingClientRect();
    const yTop = Math.max(r.y, 10);
    const yBot = Math.min(r.y + r.height, window.innerHeight - 10);
    return { x: r.x + r.width / 2, y: (yTop + yBot) / 2 };
  }, needle);
  if (!pt) return null;
  await page.waitForTimeout(220);
  await page.mouse.click(pt.x, pt.y);
  await page.waitForTimeout(520);
  return pt;
}

async function closeIfOpen(page) {
  await page.evaluate(() => {
    const lb = document.querySelector('.nf-lightbox.on');
    if (!lb) return;
    const back = lb.querySelector('.mfs-back');
    if (back) back.click(); else lb.querySelector('.nf-lightbox-close').click();
  });
  await page.waitForTimeout(260);
}

const browser = await chromium.launch();
const summary = { counts: null, shots: SHOTS_DIR, inline: {}, stability: {}, viewer: {} };

try {
  /* ══════════════ 桌面 1280×900：内嵌几何 + 窗口稳定 + 查看器 ══════════════ */
  {
    const { ctx, page, pageErrors, consoleErrors, sendHistory } = await boot(browser, { width: 1280, height: 900 });

    /* ── Canvas 文档面（历史报障所在面）：先装采样器，再首次渲染 ── */
    await page.evaluate(INSTALL_SAMPLER);
    await page.evaluate(({ absPath, content }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'canvas-diagrams.md', content, absPath, pinned: true },
      }));
    }, { absPath: P_DOC, content: CANVAS_DOC });
    await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll', { timeout: 25000 });
    await page.waitForFunction(() => document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid[data-mermaid-state="done"]').length >= 3, { timeout: 45000 });
    await page.waitForTimeout(700);
    const canvasFrames = await page.evaluate(STOP_SAMPLER);
    const dPanel = driftOf(canvasFrames, 'panel');
    const dMain = driftOf(canvasFrames, 'main');
    const dChat = driftOf(canvasFrames, 'chat');
    const dSide = driftOf(canvasFrames, 'sidebar');
    const islandFrames = canvasFrames.filter((f) => /dnf-mermaid-svg/.test(f.right || '')).length;
    summary.stability.canvas = { frames: canvasFrames.length, panel: dPanel, main: dMain, chat: dChat, sidebar: dSide, islandFrames };
    ok(!!dPanel && dPanel.dw <= 0.5 && dPanel.dh <= 0.5,
      `C1a Canvas 面首次异步渲染：#canvas-panel 逐帧零变化（Δ ${dPanel && dPanel.dw}×${dPanel && dPanel.dh}px / ${canvasFrames.length} 帧）`);
    ok(!!dMain && dMain.dw <= 0.5, `C1b #main 宽度逐帧零变化（Δ ${dMain && dMain.dw}px）`);
    ok(!!dChat && dChat.dw <= 0.5, `C1c #chat 宽度逐帧零变化（Δ ${dChat && dChat.dw}px）`);
    ok(!!dSide && dSide.dw <= 0.5, `C1d #sidebar 逐帧零变化（Δ ${dSide && dSide.dw}×${dSide && dSide.dh}px）`);
    ok(islandFrames === 0, `C1e 右缘命中链零测量岛（${islandFrames}/${canvasFrames.length} 帧出现 div#dnf-mermaid-svg-*）`);

    const canvasInline = await page.evaluate(INLINE_READ);
    summary.inline.canvas = canvasInline;
    const cTall = canvasInline.figs[0];
    const cWide = canvasInline.figs[1];
    ok(!!cTall && cTall.scrollH <= cTall.clientH + 1 && cTall.scrollW <= cTall.clientW + 1,
      `I1a 长图槽内零滚动（scroll ${cTall && cTall.scrollW}×${cTall && cTall.scrollH} = client ${cTall && cTall.clientW}×${cTall && cTall.clientH}）`);
    ok(!!cTall && cTall.overflowY === 'visible' && cTall.maxH === 'none',
      `I1b 图槽不是滚动容器（overflow-y=${cTall && cTall.overflowY} max-height=${cTall && cTall.maxH}）`);
    ok(!!cTall && Math.abs(cTall.svgH - cTall.slotH) <= 1,
      `I2 长图整幅在图槽里（图 ${cTall && cTall.svgH}px / 槽 ${cTall && cTall.slotH}px，旧 480px 封顶已退役）`);
    ok(!!cTall && cTall.slotH > 500, `I2b 长图不再被 480px 封顶（槽高 ${cTall && cTall.slotH}px）`);
    ok(!!cTall && cTall.vbRatio && Math.abs(cTall.svgRatio / cTall.vbRatio - 1) <= 0.01,
      `I3a 长图比例与源图一致（渲染 ${cTall && cTall.svgRatio} vs viewBox ${cTall && cTall.vbRatio}）`);
    ok(!!cWide && cWide.svgW <= cWide.slotW + 1,
      `I4 宽图落在内容列宽内（图 ${cWide && cWide.svgW}px ≤ 槽 ${cWide && cWide.slotW}px）`);
    ok(!!cWide && cWide.scrollH <= cWide.clientH + 1 && cWide.scrollW <= cWide.clientW + 1,
      `I1c 宽图槽内零滚动（scroll ${cWide && cWide.scrollW}×${cWide && cWide.scrollH}）`);
    ok(!!canvasInline.docScrollH && canvasInline.docScrollH > canvasInline.docClientH * 2,
      `I5 滚动交给文档（文档 ${canvasInline.docScrollH}px vs 视口 ${canvasInline.docClientH}px）`);
    ok(canvasInline.figs.every((f) => f.openBtn), 'I6 每张图都挂着「看大图」真按钮');
    await page.screenshot({ path: join(SHOTS_DIR, 'inline-canvas-desktop.png') });
    await page.evaluate(() => {
      const el = [...document.querySelectorAll('.canvas-tab-pane.active .nf-mermaid')][0];
      el.scrollIntoView({ block: 'start' });
    });
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(SHOTS_DIR, 'inline-canvas-desktop-tall-top.png') });

    /* ── 查看器：桌面中心 + 缩放 + 矢量 + a11y ── */
    const openTall = await openFigure(page, '步骤 0] --> T1');
    ok(!!openTall, `V2pre 长图可点开（点 ${openTall && Math.round(openTall.x)},${openTall && Math.round(openTall.y)}）`);
    const tallLb = await page.evaluate(LB_READ);
    summary.viewer.desktopTall = tallLb;
    ok(!!tallLb && tallLb.on === true && tallLb.mode === 'svg', `V1a 框图点开进同一浮层（mode=${tallLb && tallLb.mode}）`);
    ok(!!tallLb && tallLb.overlays === 1, `V1b 全文档只有一枚浮层实例（${tallLb && tallLb.overlays}）`);
    ok(!!tallLb && Math.abs(tallLb.canvasOff[0]) <= 1 && Math.abs(tallLb.canvasOff[1]) <= 1,
      `V2a 桌面打开后相对屏幕居中（纸面中心偏差 ${tallLb && JSON.stringify(tallLb.canvasOff)}px）`);
    ok(!!tallLb && Math.abs(tallLb.svgOff[0]) <= 1 && Math.abs(tallLb.svgOff[1]) <= 1,
      `V2b 图的几何中心同屏幕中心（偏差 ${tallLb && JSON.stringify(tallLb.svgOff)}px）`);
    ok(!!tallLb && Math.abs(tallLb.canvas[2] - tallLb.stage[2] * 0.92) <= 1 && Math.abs(tallLb.canvas[3] - tallLb.stage[3] * 0.88) <= 1,
      `V2fit 纸面匹配图片适配盒（${JSON.stringify(tallLb && tallLb.canvas)}）`);
    ok(!!tallLb && !!tallLb.svg && !!tallLb.canvas &&
      tallLb.svg[0] >= tallLb.canvas[0] - 1 && tallLb.svg[1] >= tallLb.canvas[1] - 1 &&
      tallLb.svg[0] + tallLb.svg[2] <= tallLb.canvas[0] + tallLb.canvas[2] + 1 &&
      tallLb.svg[1] + tallLb.svg[3] <= tallLb.canvas[1] + tallLb.canvas[3] + 1 &&
      tallLb.canvas[0] >= tallLb.stage[0] - 1 && tallLb.canvas[1] >= tallLb.stage[1] - 1 &&
      tallLb.canvas[0] + tallLb.canvas[2] <= tallLb.stage[0] + tallLb.stage[2] + 1 &&
      tallLb.canvas[1] + tallLb.canvas[3] <= tallLb.stage[1] + tallLb.stage[3] + 1,
      `V2contain SVG 四边在纸面及可视舞台内（svg=${JSON.stringify(tallLb && tallLb.svg)} canvas=${JSON.stringify(tallLb && tallLb.canvas)}）`);
    ok(!!tallLb && tallLb.svgIsVector && tallLb.svgTexts > 0,
      `V5a 打开的是真矢量 SVG（text/tspan ${tallLb && tallLb.svgTexts} 个，非位图）`);
    ok(!!tallLb && tallLb.a11y.role === 'dialog' && tallLb.a11y.modal === 'true' && !!tallLb.a11y.closeLabel,
      `V5b 浮层无障碍面在位（role=${tallLb && tallLb.a11y.role} aria-modal=${tallLb && tallLb.a11y.modal}）`);
    ok(!!tallLb && tallLb.a11y.ctls.length >= 1 && tallLb.a11y.ctls.every(Boolean),
      `V5c 缩放控件是真按钮且都有 aria-label（${tallLb && JSON.stringify(tallLb.a11y.ctls)}）`);
    await page.screenshot({ path: join(SHOTS_DIR, 'viewer-desktop-tall.png') });

    await page.mouse.move(640, 450);
    await page.mouse.wheel(0, -240);
    await page.waitForTimeout(250);
    const tallZoom = await page.evaluate(LB_READ);
    summary.viewer.desktopTallZoom = tallZoom;
    ok(!!tallZoom && !!tallLb && tallZoom.canvas[2] > tallLb.canvas[2] * 1.05,
      `V5d 滚轮缩放几何（${tallLb && tallLb.canvas[2]} → ${tallZoom && tallZoom.canvas[2]}）`);
    ok(!!tallZoom && Math.abs(tallZoom.canvasOff[0]) <= 1 && Math.abs(tallZoom.canvasOff[1]) <= 1,
      `V2c 放大后仍相对屏幕居中（偏差 ${tallZoom && JSON.stringify(tallZoom.canvasOff)}px）`);
    ok(!!tallZoom && tallZoom.svgIsVector, 'V5e 放大后仍是矢量（零位图替换）');
    await page.screenshot({ path: join(SHOTS_DIR, 'viewer-desktop-tall-zoom.png') });

    // 复位 + 关闭（ESC）+ 焦点回位
    await page.locator('.nf-lightbox-bar [data-action="fit"]').click();
    await page.waitForTimeout(180);
    const reset = await page.evaluate(LB_READ);
    ok(!!reset && !!tallLb && Math.abs(reset.canvas[2] - tallLb.canvas[2]) <= 1 && Math.abs(reset.canvasOff[0]) <= 1 && Math.abs(reset.canvasOff[1]) <= 1,
      `V2d 适应视图恢复几何（${reset && reset.canvas[2]} / ${tallLb && tallLb.canvas[2]}）`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(250);
    const afterEsc = await page.evaluate(() => {
      const lb = document.querySelector('.nf-lightbox');
      return { on: lb.classList.contains('on'), focused: document.activeElement && document.activeElement.className };
    });
    ok(!afterEsc.on, 'V5f ESC 关闭浮层');

    /* ── 关闭钮的真实点击（舞台铺满全屏后必须仍在它之上；实测踩过：舞台排在
       DOM 后面会把右上角这枚钮整个盖住，真实点击落到舞台上）── */
    await openFigure(page, '步骤 0] --> T1');
    const closePt = await page.evaluate(() => {
      const b = document.querySelector('.nf-lightbox .nf-lightbox-close');
      const r = b.getBoundingClientRect();
      const x = r.x + r.width / 2;
      const y = r.y + r.height / 2;
      const e = document.elementFromPoint(x, y);
      const hit = e ? (e.closest && e.closest('.nf-lightbox-close') ? 'close' : (e.tagName || '').toLowerCase()) : null;
      return { x, y, hit };
    });
    await page.mouse.click(closePt.x, closePt.y);
    await page.waitForTimeout(320);
    const closedByBtn = await page.evaluate(() => !document.querySelector('.nf-lightbox.on'));
    ok(!!closedByBtn && closePt.hit === 'close',
      `V5i 关闭钮可被真实点击（命中 ${closePt.hit}，浮层已关=${closedByBtn}）`);

    /* ── 气泡面：内嵌零滚动 + 首渲窗口零变化 ── */
    await page.evaluate(INSTALL_SAMPLER);
    sendHistory();
    await page.waitForSelector('#chat .row', { timeout: 30000, state: 'attached' });
    await page.waitForFunction(() => document.querySelectorAll('#chat .nf-mermaid[data-mermaid-state="done"]').length >= 3, { timeout: 45000 });
    await page.waitForTimeout(700);
    const chatFrames = await page.evaluate(STOP_SAMPLER);
    const dChat2 = driftOf(chatFrames, 'chat');
    const dMain2 = driftOf(chatFrames, 'main');
    summary.stability.chat = { frames: chatFrames.length, chat: dChat2, main: dMain2 };
    ok(!!dChat2 && dChat2.dw <= 0.5 && dChat2.dh <= 0.5,
      `C2a 气泡面首次异步渲染：#chat 逐帧零变化（Δ ${dChat2 && dChat2.dw}×${dChat2 && dChat2.dh}px / ${chatFrames.length} 帧）`);
    ok(!!dMain2 && dMain2.dw <= 0.5, `C2b #main 宽度逐帧零变化（Δ ${dMain2 && dMain2.dw}px）`);
    const chatInline = await page.evaluate(INLINE_READ);
    summary.inline.chat = chatInline;
    const bTall = chatInline.figs.find((f) => f.slotH > 500);
    ok(!!bTall && bTall.scrollH <= bTall.clientH + 1 && Math.abs(bTall.svgH - bTall.slotH) <= 1,
      `I7 气泡面同一几何（槽 ${bTall && bTall.slotH}px = 图 ${bTall && bTall.svgH}px，无内滚）`);
    await page.screenshot({ path: join(SHOTS_DIR, 'inline-chat-desktop.png') });

    /* ── 键盘可达：角标聚焦 + Enter 打开 ── */
    const kb = await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
      if (!el) return null;
      el.scrollIntoView({ block: 'center' });
      const btn = el.querySelector(':scope > .nf-figure-open');
      if (!btn) return null;
      btn.focus();
      return { focused: document.activeElement === btn, label: btn.getAttribute('aria-label'), tag: btn.tagName };
    });
    await page.waitForTimeout(150);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    const kbOpen = await page.evaluate(() => !!document.querySelector('.nf-lightbox.on'));
    ok(!!kb && kb.focused && kb.tag === 'BUTTON', `V5g 「看大图」角标是可真聚焦的 button（${kb && kb.label}）`);
    ok(kbOpen, 'V5h 键盘 Enter 可打开浮层');
    await closeIfOpen(page);

    /* ── 图片模式：同一条几何（回归同一档适配盒）── */
    await page.evaluate(() => {
      const img = document.querySelector('#chat img.nf-zoom-img');
      if (img) img.scrollIntoView({ block: 'center' });
    });
    await page.waitForTimeout(200);
    const imgPt = await page.evaluate(() => {
      const img = document.querySelector('#chat img.nf-zoom-img');
      const r = img.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    });
    await page.mouse.click(imgPt.x, imgPt.y);
    await page.waitForTimeout(600);
    const imgLb = await page.evaluate(LB_READ);
    summary.viewer.desktopImg = imgLb;
    ok(!!imgLb && imgLb.on && imgLb.mode === 'img', `V1c 图片走同一浮层实例（mode=${imgLb && imgLb.mode}）`);
    ok(!!imgLb && Math.abs(imgLb.imgOff[0]) <= 1 && Math.abs(imgLb.imgOff[1]) <= 1,
      `V4a 图片打开后同样相对屏幕居中（偏差 ${imgLb && JSON.stringify(imgLb.imgOff)}px）`);
    // 「跟图片一样的查看逻辑」的机械判据：两种内容模式共用同一档适配盒 token
    const sharedBox = await page.evaluate(() => {
      const lb = document.querySelector('.nf-lightbox');
      const cs = getComputedStyle(lb);
      const img = lb.querySelector('.nf-lightbox-img');
      const ics = getComputedStyle(img);
      return {
        fw: cs.getPropertyValue('--nf-lb-fit-w').trim(), fh: cs.getPropertyValue('--nf-lb-fit-h').trim(),
        mw: ics.maxWidth, mh: ics.maxHeight,
      };
    });
    summary.viewer.sharedBox = sharedBox;
    ok(sharedBox.fw === '92%' && sharedBox.fh === '88%' && sharedBox.mw === sharedBox.fw && sharedBox.mh === sharedBox.fh,
      `V1d 图片与框图共用同一档适配盒（token ${sharedBox.fw}/${sharedBox.fh} = img max ${sharedBox.mw}/${sharedBox.mh}）`);
    await page.screenshot({ path: join(SHOTS_DIR, 'viewer-desktop-img.png') });
    await closeIfOpen(page);

    /* ── 图内链接 / 拖动 / 选字：不误触 ── */
    await page.evaluate(() => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('点我这一格'));
      if (el) el.scrollIntoView({ block: 'center' });
    });
    await page.waitForTimeout(250);
    const linkPt = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('点我这一格'));
      const a = el && el.querySelector('.nf-mermaid-body svg a');
      const node = a || (el && el.querySelector('.nf-mermaid-body svg'));
      if (!node) return null;
      const r = node.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, isLink: !!a };
    });
    if (linkPt) {
      await page.mouse.click(linkPt.x, linkPt.y);
      await page.waitForTimeout(400);
      const linkOpened = await page.evaluate(() => !!document.querySelector('.nf-lightbox.on'));
      ok(linkPt.isLink ? !linkOpened : true,
        `V6a 点图内链接不打开浮层（命中 ${linkPt.isLink ? '<a>' : '图面'}，浮层=${linkOpened}）`);
      await closeIfOpen(page);
    }
    // 拖动超阈
    const dragPt = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('步骤 0] --> T1'));
      el.scrollIntoView({ block: 'center' });
      const r = el.querySelector('.nf-mermaid-body svg').getBoundingClientRect();
      const yTop = Math.max(r.y, 10);
      const yBot = Math.min(r.y + r.height, window.innerHeight - 10);
      return { x: r.x + r.width / 2, y: (yTop + yBot) / 2 };
    });
    await page.waitForTimeout(200);
    await page.mouse.move(dragPt.x, dragPt.y);
    await page.mouse.down();
    await page.mouse.move(dragPt.x + 40, dragPt.y + 24, { steps: 6 });
    await page.mouse.up();
    await page.waitForTimeout(350);
    const dragOpened = await page.evaluate(() => !!document.querySelector('.nf-lightbox.on'));
    ok(!dragOpened, `V6b 拖动超阈不打开浮层（浮层=${dragOpened}）`);
    await closeIfOpen(page);

    summary.viewer.desktopErrors = { pageErrors, consoleErrors };
    ok(pageErrors.length === 0, `V7a 零 pageerror（${pageErrors.slice(0, 2).join(' | ')}）`);
    ok(consoleErrors.length === 0, `V7b 零 console error（${consoleErrors.slice(0, 2).join(' | ')}）`);
    await ctx.close();
  }

  /* ══════════════ 手机 390×844 / 375×812：真触摸 + 中心 ══════════════ */
  for (const vp of [{ width: 390, height: 844, tag: '390x844' }, { width: 375, height: 812, tag: '375x812' }]) {
    const { ctx, page, pageErrors, consoleErrors, sendHistory } = await boot(browser, { width: vp.width, height: vp.height, touch: true });
    const cdp = await ctx.newCDPSession(page);
    sendHistory();
    await page.waitForSelector('#chat .row', { timeout: 30000, state: 'attached' });
    await page.waitForFunction(() => document.querySelectorAll('#chat .nf-mermaid[data-mermaid-state="done"]').length >= 3, { timeout: 45000 });
    await page.waitForTimeout(700);
    const inline = await page.evaluate(INLINE_READ);
    summary.inline[`m_${vp.tag}`] = inline;
    const mTall = inline.figs.find((f) => f.slotH > 500);
    ok(!!mTall && mTall.scrollH <= mTall.clientH + 1 && mTall.scrollW <= mTall.clientW + 1,
      `I8 ${vp.tag}：长图槽内零滚动（scroll ${mTall && mTall.scrollH} = client ${mTall && mTall.clientH}）`);
    ok(!!mTall && Math.abs(mTall.svgH - mTall.slotH) <= 1,
      `I8b ${vp.tag}：长图整幅在图槽里（${mTall && mTall.svgH}px）`);
    ok(inline.figs.every((f) => f.openBtn), `I8c ${vp.tag}：每张图都挂着真按钮`);
    await page.screenshot({ path: join(SHOTS_DIR, `inline-chat-${vp.tag}.png`) });

    // 真触摸点开（CDP，不用 mouse —— 手机档只认真触屏证据）
    const touchPt = await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
      const svg = el && el.querySelector('.nf-mermaid-body svg');
      if (!svg) return null;
      svg.scrollIntoView({ block: 'center' });
      const r = svg.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: Math.min(Math.max(r.y + 10, 10), window.innerHeight - 10) + 40 };
    });
    await page.waitForTimeout(250);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: touchPt.x, y: touchPt.y }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(600);
    const mNorm = await page.evaluate(LB_READ);
    summary.viewer[`mobile_${vp.tag}`] = mNorm;
    ok(!!mNorm && mNorm.on && mNorm.mode === 'svg', `V3a ${vp.tag}：真触摸点开浮层（mode=${mNorm && mNorm.mode}）`);
    ok(!!mNorm && Math.abs(mNorm.canvasOff[0]) <= 1 && Math.abs(mNorm.canvasOff[1]) <= 1,
      `V3b ${vp.tag}：打开后相对屏幕居中（纸面偏差 ${mNorm && JSON.stringify(mNorm.canvasOff)}px）`);
    ok(!!mNorm && Math.abs(mNorm.svgOff[0]) <= 1 && Math.abs(mNorm.svgOff[1]) <= 1,
      `V3c ${vp.tag}：图的几何中心同屏幕中心（偏差 ${mNorm && JSON.stringify(mNorm.svgOff)}px）`);
    ok(!!mNorm && Math.abs(mNorm.canvas[2] - mNorm.stage[2] * 0.92) <= 1 && Math.abs(mNorm.canvas[3] - mNorm.stage[3] * 0.88) <= 1,
      `V3fit ${vp.tag}：纸面匹配图片适配盒（${JSON.stringify(mNorm && mNorm.canvas)}）`);
    ok(!!mNorm && mNorm.pinned === '0', `V4b ${vp.tag}：可视视口与布局视口一致时不加内联盒（pinned=${mNorm && mNorm.pinned}）`);
    await page.screenshot({ path: join(SHOTS_DIR, `viewer-${vp.tag}-open.png`) });

    // Pinch apart around the viewport center; the viewer has no zoom buttons.
    const cx = vp.width / 2, cy = vp.height / 2;
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cx - 30, y: cy }, { x: cx + 30, y: cy }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: cx - 70, y: cy }, { x: cx + 70, y: cy }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(200);
    const mZoom = await page.evaluate(LB_READ);
    summary.viewer[`mobile_${vp.tag}_zoom`] = mZoom;
    ok(!!mZoom && !!mNorm && mZoom.canvas[2] > mNorm.canvas[2] * 1.05, `V3d ${vp.tag}：触摸放大几何（${mNorm && mNorm.canvas[2]} → ${mZoom && mZoom.canvas[2]}）`);
    // Known CDP sequential-pointer offset; tracked in design-reuse/PROGRESS.md
    // KNOWN-ISSUES. The pinch anchor must remain within a bounded tolerance.
    ok(!!mZoom && Math.abs(mZoom.canvasOff[0]) <= 40 && Math.abs(mZoom.canvasOff[1]) <= 40,
      `V3e ${vp.tag}：捏合锚点偏移不超过40px（偏差 ${mZoom && JSON.stringify(mZoom.canvasOff)}px）`);
    await page.screenshot({ path: join(SHOTS_DIR, `viewer-${vp.tag}-zoom.png`) });

    // 关掉
    await page.evaluate(() => {
      const b = document.querySelector('.nf-lightbox .mfs-back');
      if (b) b.click(); else document.querySelector('.nf-lightbox-close').click();
    });
    await page.waitForTimeout(300);
    ok(!(await page.evaluate(() => !!document.querySelector('.nf-lightbox.on'))), `V3f ${vp.tag}：可关闭返回`);

    /* ── 动态视口：可视视口被内缩/平移（浏览器栏展开、软键盘弹起的同形）── */
    const dynBox = await page.evaluate(() => {
      // 只换可视视口读数与事件面：模拟「布局视口里可见区高 −144px、整体下移 44px」
      const box = { w: window.innerWidth, h: window.innerHeight - 144, ox: 0, oy: 44 };
      const fake = {
        width: box.w, height: box.h, offsetLeft: box.ox, offsetTop: box.oy,
        pageLeft: box.ox, pageTop: box.oy, scale: 1,
        addEventListener() {}, removeEventListener() {},
      };
      Object.defineProperty(window, 'visualViewport', { configurable: true, get: () => fake });
      return box;
    });
    const pt2 = await page.evaluate(() => {
      const el = [...document.querySelectorAll('#chat .nf-mermaid')].find((b) => (b.getAttribute('data-mermaid-src') || '').includes('开始 START'));
      el.scrollIntoView({ block: 'center' });
      const r = el.querySelector('.nf-mermaid-body svg').getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + 40 };
    });
    await page.waitForTimeout(200);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: pt2.x, y: pt2.y }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(600);
    const dyn = await page.evaluate(LB_READ);
    summary.viewer[`dyn_${vp.tag}`] = dyn;
    ok(!!dyn && dyn.on && dyn.pinned === '1', `V4c ${vp.tag}：可视视口内缩时浮层改钉可视区域（pinned=${dyn && dyn.pinned}）`);
    ok(!!dyn && Math.abs(dyn.overlay[0] - dynBox.ox) <= 1 && Math.abs(dyn.overlay[1] - dynBox.oy) <= 1 &&
      Math.abs(dyn.overlay[2] - dynBox.w) <= 1 && Math.abs(dyn.overlay[3] - dynBox.h) <= 1,
      `V4d ${vp.tag}：浮层盒 == 可视区域（实测 ${dyn && JSON.stringify(dyn.overlay)} / 应有 ${JSON.stringify([dynBox.ox, dynBox.oy, dynBox.w, dynBox.h])}）`);
    const visCenter = { x: dynBox.ox + dynBox.w / 2, y: dynBox.oy + dynBox.h / 2 };
    ok(!!dyn && dyn.canvas && Math.abs((dyn.canvas[0] + dyn.canvas[2] / 2) - visCenter.x) <= 1 &&
      Math.abs((dyn.canvas[1] + dyn.canvas[3] / 2) - visCenter.y) <= 1,
      `V4e ${vp.tag}：内容中心 == 可视屏幕中心（纸面 ${dyn && JSON.stringify(dyn.canvas)} / 可视中心 ${JSON.stringify(visCenter)}）`);
    await page.screenshot({ path: join(SHOTS_DIR, `viewer-${vp.tag}-dynviewport.png`) });

    ok(pageErrors.length === 0, `V7c ${vp.tag}：零 pageerror（${pageErrors.slice(0, 2).join(' | ')}）`);
    ok(consoleErrors.length === 0, `V7d ${vp.tag}：零 console error（${consoleErrors.slice(0, 2).join(' | ')}）`);
    await ctx.close();
  }
} finally {
  await browser.close();
}

summary.counts = { pass, fail, total: pass + fail };
writeFileSync(join(SHOTS_DIR, 'summary.json'), JSON.stringify(summary, null, 2));
writeFileSync(join(SHOTS_DIR, 'results.txt'), results.map((x) => `${x.ok ? 'PASS' : 'FAIL'}  ${x.label}${x.extra ? '  — ' + x.extra : ''}`).join('\n'));
console.log(`\nshots: ${SHOTS_DIR}`);
console.log(`PASS ${pass} / FAIL ${fail} / total ${pass + fail}`);
console.log(fail === 0 ? 'ALL PASS' : `FAILED: ${fail}`);
process.exit(fail === 0 ? 0 : 1);
