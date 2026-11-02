#!/usr/bin/env node
// canvas-mobile-overlay.spec.mjs — 手机端 Canvas 阅读面「浮层控件布局」返修批的机械断言
// （作者报障 2026-11-02 19:18，三项诉求：底部钮同一行 / 正文从控件底下穿过 /
//  顶部初始态不遮挡首行、滚动后允许穿过）。
//
// 现场（作者 iPhone 实拍，link.mystand.space 手机端 canvas 页 + REPORT.md）：
//   ① 底部 `<>`（源码）圆钮与搜索放大镜圆钮比右侧对话气泡圆钮明显偏高 —— 三颗钮
//      不在同一水平线；
//   ② 这三颗钮压在正文文字上，同时正文看起来又为它们让了位（容器被收缩）；
//   ③ 首屏正文第一行被顶部按钮遮挡。
//
// 断言（改前必红 / 改后必绿，读数逐条打印；全部 = 真机视口 390×844 DPR3 的
// boundingBox / elementFromPoint 实测，不 mock 布局）：
//   A1  底部 `<>` / 放大镜 / 气泡三圆钮中心 y 极差 ≤1px（同一行）
//   A2  底部钮 vs 顶部胶囊组（标题环中签）的水平中心对齐关系（读数，不设阈值）
//   A3  控件纯覆盖层：① 正文容器宽 = 视口内容宽；② 文档宿主底缘 = 页面底缘
//       （底部指示器等控件**零流内占位**）；③ 控件 position ∈ {fixed, absolute}
//   A4  scrollTop = 0 时正文首个内容块 top ≥ 顶部控件组 bottom（无遮挡，像素读数）
//   A5  scrollTop > 0 时正文允许出现在顶部控件之下（穿过）
//   A6  滚动到底：正文最后一行 bottom ≤ 底部控件 top（可完整滚出浮层）
//   A7  所有可点控件热区 ≥44×44（elementFromPoint 步行实测，不读伪元素声明值）；
//       图标视觉尺寸随读数（改前/改后对照）
//   A8  排版硬标准：正文 ≥17px、辅助字 ≥13px（Apple HIG 正文档/footnote 档）
//
// 运行（必须对隔离 LIVE 实例跑，勿对宿主 6138）：
//   BASE_URL=http://127.0.0.1:8317 NEBFLOW_HOME=/tmp/nf-f1cfeb61-home \
//     node tests/canvas-mobile-overlay.spec.mjs [outDir]
//
// 退出码：0 = 全绿；1 = 有 FAIL（读数照旧全部打印，便于改前取证）。
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.BASE_URL || 'http://127.0.0.1:8317').replace(/\/$/, '');
const HOME = process.env.NEBFLOW_HOME || '/tmp/nf-f1cfeb61-home';
const BUNDLE = resolve(HERE, '..', 'evidence/20261102_canvas-mobile-overlay');
// 夹具（两卡）固定落 bundle 的 report-src：改前/改后两轮必须读同一份正文，
// 否则 scrollHeight 不同、末尾余量读数不可比。
const SRC_DIR = join(BUNDLE, 'report-src');
const MD_PATH = join(SRC_DIR, 'REPORT.md');
const APPENDIX_PATH = join(SRC_DIR, 'APPENDIX.md');
const OUT = process.argv[2] || join(BUNDLE, 'after');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

// ── 真机视口（iPhone 14/15 级）：390 × 844 CSS px，DPR 3 ──────────────────
const VIEWPORT = { width: 390, height: 844 };
const DPR = 3;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const round = (n) => Math.round(n * 100) / 100;

function token() {
  try { return JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8')); } catch { return ''; }
}

// ── 结果收集 ──────────────────────────────────────────────────────────────
const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}

// ── 探针：一帧内全体几何（避免跨帧错位）────────────────────────────────
const PROBE = `(() => {
  const R = (n) => Math.round(n * 100) / 100;
  const box = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return null;
    return { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height),
             right: R(r.right), bottom: R(r.bottom),
             cx: R(r.x + r.width / 2), cy: R(r.y + r.height / 2) };
  };
  const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  const cs = (el) => el ? getComputedStyle(el) : null;

  const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane')
            || document.querySelector('.canvas-tab-pane.active');
  const scroller = pane ? pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll, .epub-reader-viewport') : null;
  const mdViewer = scroller ? scroller.querySelector('.canvas-md-viewer') : null;
  // 控件查询必须**限定在活动 pane 内**：非活动 pane（在 .mobile-page-body 里）也
  // 挂着同名工具钮，document 级 querySelector 会取到那枚隐藏的（读数全 0）。
  const inPane = (sel) => (pane ? pane.querySelector(sel) : null);

  const firstBlock = mdViewer ? [...mdViewer.children].find((c) => vis(c)) : null;
  const lastBlock = mdViewer ? [...mdViewer.children].reverse().find((c) => vis(c)) : null;

  const src = inPane('.canvas-source-toggle');
  const find = inPane('.canvas-find-toggle');
  const ref = inPane('.canvas-ref-select');
  const askFab = document.querySelector('.ask-fab');
  const zoomBar = inPane('.canvas-zoom-bar');
  const chipOn = document.querySelector('.mf-strip-chip.on');
  const chip = document.querySelector('.mf-strip');
  const closeBtn = document.querySelector('.mf-cv-close');
  const stackBtn = document.querySelector('.mf-cv-stack');

  const controlsInFlow = [];
  for (const [n, el] of [['source', src], ['find', find], ['ref', ref], ['askFab', askFab]]) {
    if (!el) continue;
    const c = cs(el);
    controlsInFlow.push({ n, position: c.position, zIndex: c.zIndex,
      parent: el.parentElement ? (el.parentElement.className || el.parentElement.id || el.parentElement.tagName) : null });
  }

  const hot = (el, label) => {
    if (!vis(el)) return null;
    const b = box(el);
    if (!b) return null;
    const icon = el.querySelector('svg, i svg');
    const ib = icon ? box(icon) : null;
    return { label, w: b.w, h: b.h, cx: b.cx, cy: b.cy, top: b.y, bottom: b.bottom,
             iconW: ib ? ib.w : null, iconH: ib ? ib.h : null };
  };

  // 排版读数（A8）：正文段落 / 标题三档 / 辅助字最小者。
  const fs = (el) => (el ? R(parseFloat(cs(el).fontSize) || 0) : null);
  const para = mdViewer ? [...mdViewer.querySelectorAll('p')].find((p) => vis(p)) : null;
  const auxCand = [];
  for (const [sel, el] of [
    ['code', mdViewer ? mdViewer.querySelector('code') : null],
    ['table', mdViewer ? mdViewer.querySelector('table') : null],
    ['.mf-strip-chip.on', chipOn],
    ['.canvas-zoom-pct', inPane('.canvas-zoom-pct')],
    ['.mf-cv-empty-sub', document.querySelector('.mf-cv-empty-sub')],
  ]) if (el && vis(el)) auxCand.push({ sel, fs: fs(el) });
  const minAux = auxCand.length ? auxCand.reduce((a, b) => (b.fs < a.fs ? b : a)) : { sel: 'n/a', fs: null };
  const type = {
    paraTag: para ? para.tagName : 'n/a', paraFs: fs(para),
    paraLh: para ? cs(para).lineHeight : null,
    h1Fs: fs(mdViewer ? mdViewer.querySelector('h1') : null),
    h2Fs: fs(mdViewer ? mdViewer.querySelector('h2') : null),
    h3Fs: fs(mdViewer ? mdViewer.querySelector('h3') : null),
    minFs: minAux.fs, minSel: minAux.sel,
    chipFs: fs(chipOn), dotsFs: fs(document.querySelector('.mf-dots')),
  };

  return {
    mobile: document.body.classList.contains('mobile-layer'),
    vw: window.innerWidth, vh: window.innerHeight, dpr: window.devicePixelRatio,
    viewportContentW: document.documentElement.clientWidth,
    pane: box(pane), scroller: box(scroller),
    scrollerScrollTop: scroller ? R(scroller.scrollTop) : null,
    scrollerScrollH: scroller ? R(scroller.scrollHeight) : null,
    scrollerClientH: scroller ? R(scroller.clientHeight) : null,
    mdViewer: box(mdViewer),
    mdViewerCS: mdViewer ? { w: R(mdViewer.getBoundingClientRect().width),
                             maxW: cs(mdViewer).maxWidth,
                             padL: cs(mdViewer).paddingLeft, padR: cs(mdViewer).paddingRight,
                             padB: cs(mdViewer).paddingBottom } : null,
    firstBlock: firstBlock ? { tag: firstBlock.tagName, ...box(firstBlock) } : null,
    lastBlock: lastBlock ? { tag: lastBlock.tagName, ...box(lastBlock) } : null,
    controls: {
      src: hot(src, 'source(<>'), find: hot(find, '放大镜'), ref: hot(ref, 'ref'), askFab: hot(askFab, '气泡'),
      close: hot(closeBtn, 'close'), stack: hot(stackBtn, 'stack'), chipOn: hot(chipOn, 'chip:on'),
      zoom: zoomBar ? [...zoomBar.querySelectorAll('.canvas-zoom-btn')].map((b) => hot(b, 'zoom-btn')).filter(Boolean) : [],
    },
    chip: box(chip), chipOn: box(chipOn),
    type,
    dots: (() => { const d = document.querySelector('.mf-dots'); return d && !d.hidden && vis(d) ? box(d) : null; })(),
    dotsCS: (() => { const d = document.querySelector('.mf-dots'); return d ? { position: cs(d).position } : null; })(),
    controlsInFlow,
    askFabPosition: askFab ? cs(askFab).position : null,
    scrollPadTop: scroller ? R(parseFloat(cs(scroller).paddingTop) || 0) : null,
    scrollPadBottom: scroller ? R(parseFloat(cs(scroller).paddingBottom) || 0) : null,
    docBottomPad: mdViewer ? R(parseFloat(cs(mdViewer).paddingBottom) || 0) : null,
    dockTop: (() => { const d = document.getElementById('mobile-dock'); return d ? box(d) : null; })(),
    page: (() => { const p = document.querySelector('.mobile-page[data-page="canvas"]'); return p ? box(p) : null; })(),
    host: (() => { const h = document.querySelector('.mf-cv-host'); return h ? box(h) : null; })(),
  };
})()`;

// ── 热区实测：elementFromPoint 步行（不读 ::before 的声明值 —— getComputedStyle
//    对 max(100%, 44px) 这类表达式给的是未完全解析的读数，量测面不可信）。──
const HIT_TEST = (list) => `(() => {
  const R = (n) => Math.round(n * 100) / 100;
  const out = [];
  const els = ${JSON.stringify(list)}.map((s) => document.querySelector(s));
  for (let i = 0; i < els.length; i++) {
    const el = els[i];
    if (!el || !el.getClientRects().length) { out.push(null); continue; }
    const r = el.getBoundingClientRect();
    const cx = r.x + r.width / 2, cy = r.y + r.height / 2;
    const hits = (x, y) => {
      if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) return false;
      const t = document.elementFromPoint(x, y);
      return !!t && (t === el || el.contains(t) || t.contains(el));
    };
    const walk = (dx, dy, cap) => {
      let d = 0;
      while (d < cap) { const nd = d + 0.5; if (!hits(cx + dx * nd, cy + dy * nd)) break; d = nd; }
      return d;
    };
    const up = walk(0, -1, 60), down = walk(0, 1, 60), left = walk(-1, 0, 200), right = walk(1, 0, 200);
    out.push({ sel: ${JSON.stringify(list)}[i], cx: R(cx), cy: R(cy),
               hitW: R(left + right), hitH: R(up + down),
               boxW: R(r.width), boxH: R(r.height) });
  }
  return out;
})()`;

const HOT_SELECTORS = [
  '.mf-cv-host > .canvas-tab-pane .canvas-source-toggle',
  '.mf-cv-host > .canvas-tab-pane .canvas-find-toggle',
  '.ask-fab', '.mf-cv-close', '.mf-cv-stack', '.mf-strip-chip.on',
];

// ── 滚动：写值后立即回读校验（重渲染会换掉 scroller 节点 ⇒ 写值可能落在旧节点）──
async function scrollTo(target) {
  const apply = () => page.evaluate((t) => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const sc = pane && pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll, .epub-reader-viewport');
    if (!sc) return { ok: false, reason: 'no scroller' };
    const want = t === 'bottom' ? sc.scrollHeight : t;
    sc.scrollTop = want;
    return { ok: true, want, got: Math.round(sc.scrollTop * 100) / 100,
             max: Math.round((sc.scrollHeight - sc.clientHeight) * 100) / 100,
             sh: sc.scrollHeight, ch: sc.clientHeight };
  }, target);
  const read = () => page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const sc = pane && pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll, .epub-reader-viewport');
    return sc ? Math.round(sc.scrollTop * 100) / 100 : null;
  });
  let r = await apply();
  await sleep(350);
  let got = await read();
  const expect = r.ok ? Math.min(r.want, r.max) : null;
  if (r.ok && expect !== null && Math.abs(got - expect) > 2) {
    r = await apply();
    await sleep(450);
    got = await read();
  }
  console.log(`[scroll] 目标=${target} 实测=${got}（写值回读 ${r.got ?? '-'}；上限 ${r.max ?? '-'}；sh=${r.sh ?? '-'} ch=${r.ch ?? '-'}）`);
  return got;
}

// ── 主页流程 ──────────────────────────────────────────────────────────────
// 服务端页签存档是权威源（restoreTabs 优先拉它）：**每次实测前清空**，
// 让本轮现场 = 脚本自己开的两张卡（否则上一轮残留的第三张会污染「2 签 + 小点」）。
try { rmSync(join(HOME, 'canvas_tabs.json'), { force: true }); } catch { /* 无存档 */ }

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: VIEWPORT, deviceScaleFactor: DPR,
  isMobile: true, hasTouch: true, locale: 'zh-CN', colorScheme: 'dark',
});
const page = await ctx.newPage();
await ctx.addInitScript(() => {
  for (const k of ['stand_canvas_tabs', 'nebflow_canvas_tabs', 'neblink_canvas_tabs']) {
    try { localStorage.removeItem(k); } catch { /* private mode */ }
  }
});
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
page.on('console', (m) => { if (m.type() === 'error') pageErrors.push('console: ' + m.text().slice(0, 240)); });

const tok = token();
if (!tok) throw new Error(`no auth token at ${join(HOME, 'auth.json')}`);
{
  const r = await fetch(`${BASE}/api/auth/console-ticket`, { method: 'POST', headers: { Authorization: `Bearer ${tok}` } });
  if (!r.ok) throw new Error(`console-ticket HTTP ${r.status}`);
  const { ticket } = await r.json();
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'load' });
}
await page.waitForFunction(async () => {
  const s = (await import('/js/state.js')).default;
  return !!s.activeSessionId && s.ws && s.ws.readyState === 1;
}, null, { timeout: 30000 });
await page.waitForSelector('body.mobile-layer', { timeout: 20000 });
await sleep(800);
await page.evaluate(() => {
  document.querySelector('.onboarding-overlay #ob-skip, .onboarding-overlay #ob-no')?.click();
});
await sleep(400);

await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
await sleep(700);

// 两张卡（作者实拍里有底部小点指示器 ⇒ 至少 2 签）。**先开附录、后开 REPORT.md**，
// 让 REPORT.md 成为活动签。小点条在场 = 「底部钮错行」的触发条件，必须复现。
await page.evaluate(([absPath, absPath2]) => {
  for (const [p, title] of [[absPath2, 'APPENDIX.md'], [absPath, 'REPORT.md']]) {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: '', title, content: '', absPath: p, pinned: true },
    }));
  }
}, [MD_PATH, APPENDIX_PATH]);
await page.waitForSelector('.mf-cv-host > .canvas-tab-pane .canvas-md-scroll .canvas-md-viewer', { timeout: 30000, state: 'attached' });
await sleep(1800);
{
  const ok = await page.evaluate(() => {
    const chips = [...document.querySelectorAll('.mf-strip-chip')];
    const on = chips.find((c) => c.classList.contains('on'));
    const dots = document.querySelector('.mf-dots');
    return { chips: chips.length, on: on ? on.getAttribute('title') : null, dots: !!dots && !dots.hidden };
  });
  console.log(`[scene] chips=${ok.chips} active=${ok.on} dots=${ok.dots}`);
}
await page.evaluate(() => document.fonts?.ready);
await sleep(700);

// ── 取数：静止帧（scrollTop = 0）────────────────────────────────────────
await scrollTo(0);
const S0 = await page.evaluate(PROBE);
writeFileSync(join(OUT, 'probe-scrolltop0.json'), JSON.stringify(S0, null, 2));
await page.screenshot({ path: join(SHOTS, 'mobile-canvas-scrolltop0.png') });
const HIT0 = await page.evaluate(HIT_TEST(HOT_SELECTORS));
writeFileSync(join(OUT, 'probe-hittest.json'), JSON.stringify(HIT0, null, 2));

// ── 取数：滚动态（scrollTop = 200）──────────────────────────────────────
await scrollTo(200);
const S200 = await page.evaluate(PROBE);
writeFileSync(join(OUT, 'probe-scrolltop200.json'), JSON.stringify(S200, null, 2));
await page.screenshot({ path: join(SHOTS, 'mobile-canvas-scrolltop200.png') });

// ── 取数：滚动到底 ──────────────────────────────────────────────────────
await scrollTo('bottom');
const SBot = await page.evaluate(PROBE);
writeFileSync(join(OUT, 'probe-scrollbottom.json'), JSON.stringify(SBot, null, 2));
await page.screenshot({ path: join(SHOTS, 'mobile-canvas-scrollbottom.png') });

// ── 断言 ────────────────────────────────────────────────────────────────
const C = S0.controls;
const bsrc = C.src, bfind = C.find, bask = C.askFab;

// A1 底部三钮同一行
if (bsrc && bfind && bask) {
  const ys = [bsrc.cy, bfind.cy, bask.cy];
  const spread = round(Math.max(...ys) - Math.min(...ys));
  record('A1', '底部 <> / 放大镜 / 气泡 三圆钮中心 y 极差 ≤1px',
    spread <= 1,
    `cy: <> = ${bsrc.cy}, 放大镜 = ${bfind.cy}, 气泡 = ${bask.cy} → 极差 ${spread}px（阈值 ≤1）`);
} else {
  record('A1', '底部三圆钮同一行', false,
    `缺件：<> ${bsrc ? 'ok' : 'MISSING'} / 放大镜 ${bfind ? 'ok' : 'MISSING'} / 气泡 ${bask ? 'ok' : 'MISSING'}`);
}

// A2 底部钮 vs 顶部胶囊组水平中心（读数，不设阈值）
{
  const vals = [bsrc, bfind, bask].filter(Boolean).map((b) => `${b.label}.cx=${b.cx}`);
  record('A2', '底部钮与顶部胶囊组水平中心线关系（读数）', true,
    `顶部中签 cx=${S0.chipOn ? S0.chipOn.cx : null}；底部 ${vals.join(' / ')}；视口中线 = ${round(S0.vw / 2)}`);
}

// A3 控件纯覆盖层（宽度不缩 + 零流内占位 + position）
{
  const mdW = S0.mdViewerCS ? S0.mdViewerCS.w : 0;
  const vcw = S0.viewportContentW;
  const posOK = S0.controlsInFlow.length > 0 &&
    S0.controlsInFlow.every((c) => c.position === 'fixed' || c.position === 'absolute');
  // 文档宿主底缘 = 页面底缘 ⇒ 底部控件带（小点指示器等）零流内占位，正文区没被压短。
  const hostGap = S0.host && S0.page ? round(S0.page.bottom - S0.host.bottom) : null;
  const layoutOK = hostGap !== null && Math.abs(hostGap) <= 1;
  record('A3', '控件为纯覆盖层：正文宽 = 视口宽 ∧ 宿主底缘 = 页面底缘 ∧ 控件 fixed/absolute',
    Math.abs(mdW - vcw) <= 1 && posOK && layoutOK,
    `正文容器宽 = ${mdW}px / 视口内容宽 = ${vcw}px（差 ${round(mdW - vcw)}px）；` +
    `宿主底缘 ${S0.host ? S0.host.bottom : 'n/a'} vs 页面底缘 ${S0.page ? S0.page.bottom : 'n/a'}` +
    `（被流内占位 ${hostGap}px，阈值 |·|≤1）；` +
    `控件 position: ${S0.controlsInFlow.map((c) => `${c.n}=${c.position}`).join(', ')}；` +
    `小点条 position=${S0.dotsCS ? S0.dotsCS.position : 'none'}（盒 ${S0.dots ? `${S0.dots.w}×${S0.dots.h}@y${S0.dots.y}` : 'hidden'}）`);
}

// A4 scrollTop=0：正文首块 top ≥ 顶部控件组 bottom
{
  const fb = S0.firstBlock;
  const tops = [C.close, C.stack, C.chipOn].filter(Boolean).map((b) => b.bottom);
  const topBottom = tops.length ? round(Math.max(...tops)) : null;
  record('A4', 'scrollTop=0 正文首块 top ≥ 顶部控件组 bottom（不遮挡）',
    !!fb && topBottom !== null && fb.y >= topBottom,
    `正文首块 <${fb ? fb.tag : '?'}> top = ${fb ? fb.y : 'n/a'}px；顶部控件组 bottom = ${topBottom}px` +
    `（✕ ${C.close ? C.close.bottom : '-'} / 堆叠 ${C.stack ? C.stack.bottom : '-'} / 中签 ${C.chipOn ? C.chipOn.bottom : '-'}）；` +
    `首屏余量 = ${fb && topBottom !== null ? round(fb.y - topBottom) : 'n/a'}px；scroller padding-top = ${S0.scrollPadTop}px`);
}

// A5 scrollTop>0：正文允许进到顶部控件之下（穿过）
{
  const fb = S200.firstBlock;
  const topBottom = [C.close, C.stack].filter(Boolean).map((b) => b.bottom).reduce((a, b) => Math.max(a, b), 0);
  record('A5', 'scrollTop>0 正文允许出现在顶部控件之下（穿过）',
    S200.scrollerScrollTop > 0 && !!fb && fb.y < topBottom,
    `实测 scrollTop = ${S200.scrollerScrollTop}px；正文首块 top = ${fb ? fb.y : 'n/a'}px` +
    `（顶部控件组 bottom = ${topBottom}px）→ ${fb && fb.y < topBottom ? '已穿到控件之下（允许）' : '未穿入'}`);
}

// A6 滚动到底：最后一行 bottom ≤ 底部控件 top
{
  const lb = SBot.lastBlock;
  const bottoms = [bsrc, bfind, bask].filter(Boolean).map((b) => b.top);
  const bottomTop = bottoms.length ? round(Math.min(...bottoms)) : null;
  record('A6', '滚动到底正文最后一行 bottom ≤ 底部控件 top（可完整滚出浮层）',
    !!lb && bottomTop !== null && lb.bottom <= bottomTop,
    `末块 <${lb ? lb.tag : '?'}> bottom = ${lb ? lb.bottom : 'n/a'}px；底部控件 top = ${bottomTop}px` +
    `（<> ${bsrc ? bsrc.top : '-'} / 放大镜 ${bfind ? bfind.top : '-'} / 气泡 ${bask ? bask.top : '-'}）；` +
    `余量 = ${lb && bottomTop !== null ? round(bottomTop - lb.bottom) : 'n/a'}px；` +
    `scrollTop=${SBot.scrollerScrollTop} sh=${SBot.scrollerScrollH} ch=${SBot.scrollerClientH}；` +
    `scroller padBottom = ${SBot.scrollPadBottom}px，正文 padBottom = ${SBot.docBottomPad}px`);
}

// A7 热区 ≥44×44（elementFromPoint 步行实测）
{
  const items = HIT0.filter(Boolean);
  const bad = items.filter((b) => b.hitW < 44 - 0.5 || b.hitH < 44 - 0.5);
  record('A7', '所有可点控件热区 ≥44×44（真命中实测）',
    items.length > 0 && bad.length === 0,
    `${items.length} 个控件：` + items.map((b) =>
      `${b.sel.split(' ').pop()}=命中${b.hitW}×${b.hitH}（盒${b.boxW}×${b.boxH}）`).join('，') +
    (bad.length ? ` → 不足件：${bad.map((b) => b.sel.split(' ').pop()).join(', ')}` : ' → 全部达标') +
    `；图标视觉尺寸：` +
    ['src', 'find', 'askFab', 'close', 'stack'].filter((k) => C[k]).map((k) => `${C[k].label}=${C[k].iconW}×${C[k].iconH}`).join('，'));
}

// A8 排版硬标准：正文 ≥17px / 辅助字 ≥13px
{
  const T = S0.type;
  const bodyOk = T.paraFs >= 17;
  const auxOk = T.minFs >= 13;
  record('A8', '排版硬标准：正文 ≥17px / 辅助字（代码·表格·读数·标签）≥13px',
    bodyOk && auxOk,
    `正文 <${T.paraTag}> = ${T.paraFs}px（阈值 ≥17，行高 ${T.paraLh}）；` +
    `标题 h1=${T.h1Fs} / h2=${T.h2Fs} / h3=${T.h3Fs}px（h3 须 ≥ 正文，否则层级倒挂）；` +
    `辅助档最小字 = ${T.minFs}px（阈值 ≥13；来源 ${T.minSel}）；` +
    `中签 ${T.chipFs}px / 小点条 ${T.dotsFs}px`);
}

// ── 汇总落盘 ────────────────────────────────────────────────────────────
const summary = {
  when: new Date().toISOString(),
  base: BASE, viewport: VIEWPORT, dpr: DPR, out: OUT,
  pass, fail, rows, hitTest: HIT0, pageErrors,
};
writeFileSync(join(OUT, 'assertions.json'), JSON.stringify(summary, null, 2));

console.log(`\n=== ${pass} PASS / ${fail} FAIL ===`);
if (pageErrors.length) console.log('page errors:\n  ' + pageErrors.slice(0, 10).join('\n  '));
console.log(`shots: ${SHOTS}`);

await browser.close();
process.exit(fail === 0 ? 0 : 1);
