#!/usr/bin/env node
// check-glass-runtime.mjs — 「毛玻璃在真渲染里真的生效了吗」运行时闸
// （glass-runtime 批 2026-11-18；作者 17:25 逐字：
//   「为什么现在我都是要求使用毛玻璃效果 有些就是正常的不透字的毛玻璃模糊效果
//     而有些就是像这样透明的呢？这个是canvas手机端的标题 并且边框还没完全包裹住」）
//
// ── 为什么另有一道闸（既有 check-glass-unify.mjs 的盲区）──────────────────
// `check-glass-unify.mjs` 判的是**源码里的配方**：配方块是否只在 css/glass.css
// 单一源、α 是否落区间、`--nf-glass-input-filter` 是否逐值等于基准件。它**读不到
// 运行时**：一个面的 backdrop-filter 写对了、算出来的 computed 值也逐字正确，
// 但浏览器**根本没在采样** —— 它照样绿。本轮 P0 就是这种形态：
//   · 主胶囊 `.mf-strip-chip.on::after` 的 `backdrop-filter: blur(24px) saturate(1.15)`
//     是准确的（computed 逐字正确），但它的**宿主** `.mf-strip-chip` 带 `filter`
//     （值恒非 none）与 `will-change: transform, opacity` ⇒ 宿主成为 **backdrop
//     root** ⇒ 那只 ::after 只能采「宿主自己绘制的内容」（宿主零绘制）⇒ 模糊整条
//     失效，台上只剩 α .16 洗色底、原文穿出来（作者实拍）。
//   · 无 backdrop 引擎的**降级档**把圆钮族 `.g2-fab` 回落到 α .16 的 `--glass-bg`
//     （`--glass-bg` 是**给模糊配**的底），而 `.nf-glass` 面回落不透明
//     `--color-surface` ⇒ 同一屏「有的正常不透字、有的像这样透明」。
// 两条都不是「配方写错了」，都是**配方与渲染脱节** —— 静态闸结构上抓不到。
//
// ── backdrop root 触发点（本机 chromium 实测，2026-11-18）─────────────────
// 用「透明宿主 + 子件 backdrop-filter 悬在宿主外的背板上」这一**正确结构**做微测
// （宿主自带内容时子件还能采到宿主内容，测不出切断；见 GLASS_RUNTIME 批证据）：
//   filter:blur(0px) / filter:saturate(1)（值非 none，哪怕视觉恒等） ⇒ **切断**
//   will-change:opacity（列表含 opacity）                          ⇒ **切断**
//   opacity < 1                                                    ⇒ **切断**
//   backdrop-filter（值非 none）                                    ⇒ **切断**
//   will-change:transform / perspective / transform:translateZ(0) /
//   contain:paint / isolation:isolate                              ⇒ **不切断**（已排除）
// 故本闸的祖先扫描**只认实测会切断的那几个**（多认 = 假阳性，漏认 = 假阴性）。
//
// ── 三条判据（全部只看真渲染 + 真像素）──────────────────────────────────
//  R1 **采样式生效判定**：对每个玻璃面拍两枪 —— 本相（原样）与「全站摘掉
//     backdrop-filter」相（含 ::before/::after）。看该面自身盒内的逐像素差：
//       差 ≥ EFFECT_MIN  ⇒ 这个 backdrop-filter 真的在改像素（生效）
//       差 <  INERT_MAX  ⇒ 声明了玻璃但**什么都没做**（= 玻璃纸）⇒ 红
//     **平色底豁免**：把该面自己藏起来（visibility:hidden）再拍一枪，量它**背后的
//     背板**自身的边缘能量 `backdropEdge`。背板是平色时，模糊一个常量色仍得常量色
//     —— 那不是缺陷，记 n/a 不判红。（旧档拿「面自己的边缘能量」当平色判据是错的：
//     面自带图标/边框时它恒高，平色底也被误判成「玻璃纸」。）
//  R2 **backdrop root 切断**：玻璃面的祖先链上不许出现实测会新建 backdrop root 的
//     属性（上表）。命中即红并指名那一层 —— 这是 R1 红时的**根因行**，也是新面的
//     预防网。
//  R3 **降级档一致性**：给页面加 `html.no-backdrop`（产品自己在无 backdrop 引擎里
//     加的那个类，js/scrollUnderGlass.js），此时每一个「本相是半透明玻璃底」的面，
//     其 `background` 必须**不透明**（α ≥ 0.9）。α 低的底在无模糊引擎里 = 玻璃纸
//     （作者报障的「有背景色、没模糊、字透出来」）⇒ 红。纯渐隐面（本相 α≈0）与
//     本相已不透明（α≥0.9）的面不在此列。
//
// ── 具名例外 ──────────────────────────────────────────────────────────────
// 少数**真缺陷**面，其修复需要重排另一片 UI 的材质层（超出本批范围）。它们以
// 具名例外登记在下方 NAMED_EXCEPTIONS，**带理由与证据**，并计入「例外命中」行
// （自证例外仍活着；例外不命中 = 陈旧例外，会告警）。**禁止静默放宽**：任何未登记
// 的 R1/R2/R3 红都会让闸 FAIL。
//
// 用法：
//   node scripts/check-glass-runtime.mjs                # 门禁（红/绿）
//   node scripts/check-glass-runtime.mjs --json         # 机器读
//   node scripts/check-glass-runtime.mjs --inventory    # 全站玻璃面清点表
//   node scripts/check-glass-runtime.mjs --root=<web树> # 换一棵静态根（改前/改后对照）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { join, extname, normalize, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
// 判据与阈值**单源**（复测批 2026-11-18 收口）：严格腿
// （tests/mobile-overlay-glass.spec.mjs 的 NEB_OVERLAY_STRICT_GLASS）与本闸的 R1
// 共用这一份实现；阈值来自设计规范 §9 的机器可读块。本文件不再自带任何数字拷贝。
import { CRITERIA, glassSampleVerdict, alphaOf as criteriaAlphaOf, SPEC_PATH } from './glass-runtime-criteria.mjs';

const REPO = fileURLToPath(new URL('..', import.meta.url));
const argOf = (f, d = null) => { const i = process.argv.indexOf(f); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : d; };
const ROOT_ARG = process.argv.slice(2).find((a) => a.startsWith('--root='));
const WEB = ROOT_ARG
  ? normalize(isAbsolute(ROOT_ARG.slice(7)) ? ROOT_ARG.slice(7) : join(REPO, ROOT_ARG.slice(7)))
  : join(REPO, 'src', 'main', 'resources', 'web');
const JSON_OUT = process.argv.includes('--json');
const INVENTORY = process.argv.includes('--inventory');
const THEMES = (argOf('--themes', 'dark,light') || '').split(',').filter(Boolean);
// 阈值全部来自设计规范（见文件头 import）：本闸**零数字**。
const { effectMin: EFFECT_MIN, inertMax: INERT_MAX, flatEdgeMax: FLAT_EDGE_MAX, occluderMinAlpha: OCCLUDER_MIN_ALPHA, degradeMinAlpha: DEGRADE_MIN_ALPHA } = CRITERIA;

if (!existsSync(join(WEB, 'index.html'))) {
  console.error(`装配错误：${WEB}/index.html 不存在（--root 指向 web 树）`);
  process.exit(2);
}

// ── 具名例外（每条必须带理由 + 证据；未登记者一律判红）─────────────────────
// glass-desktop-header 批（2026-11-18）：唯一的旧例外
// `desktop-canvas-header-under-panel-backdrop-root` 已**撤除** —— 真缺陷已修，
// 不再需要豁免。修法 = 把壳的模糊从 `#canvas-panel` 挪到 `#canvas-panel::before`
// （伪元素不是表头的祖先 ⇒ 不切断；见 css/split.css「材质层重排」注 +
// glass-spec.md §9）。该条的反证（坏源码直跑必须报红）见批证据目录
// `~/<evidence-root>/glass-desktop-header-20261118/`：改前树直跑 exit 1
//（R2 × 6 条 violation 指 `#canvas-panel`），改后树 exit 0。
// 注意：本闸的 desktop-canvas 腿自本批起渲染正文并把首行滚到表头之下 —— 空面板的
// 背板近乎平色，量的只是边缘噪声（改前实测 Δpx 1.202，抓不到玻璃纸）。
const NAMED_EXCEPTIONS = [];

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf', '.map': 'application/json', '.wasm': 'application/wasm', '.mp4': 'video/mp4', '.pdf': 'application/pdf' };
const SID = 'glass-runtime-session';

function loadPlaywright() {
  const tried = [];
  for (const c of [process.env.NEBFLOW_PW_PATH, 'playwright', 'playwright-core', '@playwright/test'].filter(Boolean)) {
    try { return createRequire(import.meta.url)(c); } catch (e) { tried.push(`${c} (${String(e.message).split('\n')[0]})`); }
  }
  throw new Error('playwright 不可解析：' + tried.join(', '));
}

const MD_BODY = [
  '# 会议纪要 · 2026-11-05',
  '',
  '十一月的第一批改动已经合并，接下来要把顶部标签带的视觉口径收敛到一套。',
  '这一段文字的存在意义只有一个：**让它从顶栏底下滚过去**。',
  '',
  ...Array.from({ length: 24 }, () => '情况的反演时间和尚缺的能谱、角度响应（气体中子计）数据，逐条列在这里，凑足长度让正文可以滚动，并且让文字正好从顶部两枚圆钮与标题胶囊底下穿过。'),
].join('\n\n');

// ── 页面装配（零端口、零进程：page.route 从磁盘喂静态根 + routeWebSocket 打桩）──
async function wireContext(context) {
  await context.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    try { Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true }); } catch { /* ignore */ }
  });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await context.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); sess();
    ws.onMessage((raw) => {
      let q; try { q = JSON.parse(raw); } catch { return; }
      if (q.type === 'getConfig') cfg();
      else if (q.type === 'getSessions' || q.type === 'listSessions') sess();
      else if (q.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: q.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootPage(browser, mobile, theme) {
  const context = await browser.newContext({
    viewport: mobile ? { width: 375, height: 812 } : { width: 1280, height: 860 },
    hasTouch: mobile, isMobile: mobile, colorScheme: theme,
    locale: 'zh-CN', timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce', deviceScaleFactor: 2,
  });
  await wireContext(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message)));
  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 30000 });
  await page.waitForTimeout(400);
  return { page, context, errors };
}

// ── 场景 ──────────────────────────────────────────────────────────────────
const SCENES = {
  'mobile-canvas': {
    mobile: true,
    async setup(page) {
      await page.evaluate(() => import('/js/canvas.js').then((c) => { c.openTab('d1', 'meeting-notes-2026-11-05.md', { type: 'notes' }); c.openTab('d2', 'design-spec-value-converge.md', { type: 'spec' }); }));
      await page.waitForTimeout(320);
      await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
      await page.waitForFunction(() => { const el = document.querySelector('.mobile-page[data-page="canvas"]'); return !!el && el.classList.contains('live'); }, null, { timeout: 8000 });
      await page.waitForTimeout(300);
      await page.evaluate(async (md) => {
        const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active') || document.querySelector('.mf-cv-host > .canvas-tab-pane');
        const mod = await import('/js/viewers/markdown.js');
        await mod.default.render(pane, { content: md, absPath: '/tmp/gr.md', fileName: 'meeting-notes-2026-11-05.md', anchor: null, readOnly: true });
      }, MD_BODY);
      await page.waitForTimeout(300);
      await page.evaluate(() => {
        const sc = document.querySelector('.mf-cv-host .canvas-md-scroll') || document.querySelector('.canvas-md-scroll');
        if (!sc) return;
        const el = sc.querySelector('p');
        if (el) sc.scrollTop += el.getBoundingClientRect().top + 6;
      });
      await page.waitForTimeout(280);
    },
  },
  'mobile-chat': { mobile: true, async setup(page) { await page.waitForTimeout(300); } },
  'desktop-canvas': {
    mobile: false,
    async setup(page) {
      await page.evaluate(() => import('/js/canvas.js').then((c) => { c.openTab('d1', 'design-spec-value-converge.md', { type: 'spec' }); if (c.openPanel) c.openPanel(); }));
      await page.waitForTimeout(500);
      // glass-desktop-header 批（2026-11-18）：与 mobile-canvas 腿同规 —— 表头玻璃
      // 的判据是「面板**正文**从胶囊底下穿过时有没有真被糊」。空面板的背板近乎平色，
      // 量出来的是边缘噪声，抓不到「玻璃纸」（改前实测 Δpx 1.202 = 打不中）。
      // 故本腿渲染正文并把首行滚到表头之下（几何与 mobile 腿同一手法）。
      await page.evaluate(async (md) => {
        const pane = document.querySelector('#canvas-content .canvas-tab-pane.active') || document.querySelector('#canvas-content .canvas-tab-pane');
        if (!pane) return;
        const mod = await import('/js/viewers/markdown.js');
        await mod.default.render(pane, { content: md, absPath: '/tmp/gr.md', fileName: 'design-spec-value-converge.md', anchor: null, readOnly: true });
      }, MD_BODY);
      await page.waitForTimeout(300);
      await page.evaluate(() => {
        const sc = document.querySelector('#canvas-content .canvas-md-scroll') || document.querySelector('.canvas-md-scroll');
        if (!sc) return;
        const el = sc.querySelector('h1, p');
        if (el) sc.scrollTop += Math.max(0, el.getBoundingClientRect().top - 26);
      });
      await page.waitForTimeout(280);
      const h = page.locator('.canvas-header').first();
      if (await h.count()) await h.hover({ timeout: 3000 }).catch(() => { });
      await page.waitForTimeout(300);
    },
  },
};

// ── 面探针（在页面里跑；含 ::before/::after 伪元素）────────────────────────
const PROBE = () => {
  // 只认实测会切断子件采样的触发点（见文件头；perspective/contain/isolation 实测不切断）
  const ROOT_TRIGGERS = [
    ['filter', (v) => v && v !== 'none'],
    ['backdropFilter', (v) => v && v !== 'none'],
    ['webkitBackdropFilter', (v) => v && v !== 'none'],
    ['opacity', (v) => v != null && parseFloat(v) < 1],
    ['willChange', (v) => !!v && /(opacity|filter|backdrop-filter)/.test(v)],
  ];
  const key = (el) => el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.') : '');
  const alphaOf = (v) => {
    const m = String(v).match(/^rgba?\(([^)]*)\)$/i);
    if (!m) { const h = String(v).match(/^#([0-9a-f]{8})$/i); return h ? parseInt(h[1].slice(6, 8), 16) / 255 : 1; }
    const parts = m[1].split(/[,/]/).map((s) => s.trim()).filter(Boolean);
    return parts.length >= 4 ? parseFloat(parts[3]) : 1;
  };
  const rootCauseOf = (el) => {
    const out = [];
    let n = el.parentElement;
    while (n && n !== document.documentElement) {
      const cs = getComputedStyle(n);
      const hits = [];
      for (const [p, pred] of ROOT_TRIGGERS) { if (pred(cs[p])) hits.push(`${p}=${cs[p]}`); }
      if (hits.length) {
        const r = n.getBoundingClientRect();
        out.push({ sel: key(n), flags: hits, rect: [+r.x.toFixed(1), +r.y.toFixed(1), +r.width.toFixed(1), +r.height.toFixed(1)] });
      }
      n = n.parentElement;
    }
    return out;
  };
  document.querySelectorAll('[data-gr-idx]').forEach((e) => e.removeAttribute('data-gr-idx'));
  const surfaces = [];
  let gidN = 0;
  for (const el of document.querySelectorAll('*')) {
    // 有效可见度（祖先 opacity 连乘）：整支不可见的面不测（例外：tfl-panel opacity:0 里的 FAB）
    let effOp = 1; let m = el;
    while (m && m !== document.documentElement) { effOp *= parseFloat(getComputedStyle(m).opacity || '1'); m = m.parentElement; }
    if (effOp < 0.02) continue;
    let elGid = null;
    for (const pe of [null, '::before', '::after']) {
      const cs = getComputedStyle(el, pe);
      const bf = cs.backdropFilter && cs.backdropFilter !== 'none' ? cs.backdropFilter
        : (cs.webkitBackdropFilter && cs.webkitBackdropFilter !== 'none' ? cs.webkitBackdropFilter : null);
      if (!bf) continue;
      const r = el.getBoundingClientRect();
      if (r.width < 24 || r.height < 16) continue;
      if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
      if (elGid === null) { elGid = String(gidN++); el.setAttribute('data-gr-idx', elGid); }
      surfaces.push({
        idx: surfaces.length, gid: elGid, pe,
        key: key(el) + (pe || ''),
        path: (() => { const p = []; let n = el; while (n && n !== document.body && p.length < 4) { p.unshift(key(n)); n = n.parentElement; } return p.join(' > '); })(),
        rect: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
        backdropFilter: bf,
        background: cs.backgroundColor,
        alpha: alphaOf(cs.backgroundColor),
        rootCauses: rootCauseOf(el),
      });
    }
  }
  return surfaces;
};

// ── 像素读数 ──────────────────────────────────────────────────────────────
async function regionStats(page, base64, box) {
  return page.evaluate(async ({ data, box }) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + data;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const x0 = Math.max(0, Math.round(box.x)), y0 = Math.max(0, Math.round(box.y));
    const w = Math.min(c.width - x0, Math.round(box.w)), h = Math.min(c.height - y0, Math.round(box.h));
    if (w < 2 || h < 2) return null;
    const d = ctx.getImageData(x0, y0, w, h).data;
    const LUMA = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
    let sum = 0, n = 0; const lums = [];
    for (let i = 0; i < d.length; i += 4) { const l = LUMA(d[i], d[i + 1], d[i + 2]); sum += l; lums.push(l); n++; }
    const mean = sum / n;
    const sd = Math.sqrt(lums.reduce((s, v) => s + (v - mean) ** 2, 0) / n);
    let edge = 0, en = 0;
    for (let y = 0; y < h; y++) {
      for (let x = 1; x < w; x++) {
        const i = (y * w + x) * 4, j = (y * w + x - 1) * 4;
        edge += Math.abs(LUMA(d[i], d[i + 1], d[i + 2]) - LUMA(d[j], d[j + 1], d[j + 2])); en++;
      }
    }
    return { mean: +mean.toFixed(2), sd: +sd.toFixed(2), edge: +(edge / Math.max(1, en)).toFixed(2), px: n, w, h };
  }, { data: base64, box });
}

async function meanAbsDiff(page, a, b, box) {
  return page.evaluate(async ({ da, db, box }) => {
    const load = async (s) => { const i = new Image(); i.src = 'data:image/png;base64,' + s; await i.decode(); const c = document.createElement('canvas'); c.width = i.width; c.height = i.height; c.getContext('2d', { willReadFrequently: true }).drawImage(i, 0, 0); return c; };
    const A = await load(da), B = await load(db);
    const x0 = Math.max(0, Math.round(box.x)), y0 = Math.max(0, Math.round(box.y));
    const w = Math.min(A.width - x0, Math.round(box.w)), h = Math.min(A.height - y0, Math.round(box.h));
    if (w < 2 || h < 2) return null;
    const ca = A.getContext('2d', { willReadFrequently: true }), cb = B.getContext('2d', { willReadFrequently: true });
    const da2 = ca.getImageData(x0, y0, w, h).data;
    const db2 = cb.getImageData(x0, y0, w, h).data;
    let s = 0, n = 0;
    for (let i = 0; i < da2.length; i += 4) { s += Math.abs(da2[i] - db2[i]) + Math.abs(da2[i + 1] - db2[i + 1]) + Math.abs(da2[i + 2] - db2[i + 2]); n += 3; }
    return +(s / Math.max(1, n)).toFixed(3);
  }, { da: a.toString('base64'), db: b.toString('base64'), box });
}

// ── 主流程 ────────────────────────────────────────────────────────────────
let pw;
try { pw = loadPlaywright(); } catch (e) { console.error('装配错误：' + e.message); process.exit(2); }

const { chromium } = pw;
let browser = null;
let engine = '';
try { browser = await chromium.launch({ channel: 'chromium' }); engine = 'chromium(full) ' + browser.version(); }
catch { try { browser = await chromium.launch(); engine = 'chromium(headless-shell) ' + browser.version(); } catch (e) { console.error('装配错误：无可用浏览器 ' + e.message); process.exit(2); } }

const report = { ok: true, web: WEB, engine, criteria: CRITERIA, criteriaSource: SPEC_PATH, scenes: {}, violations: [], exceptions: [], exemptions: [] };
const allExemptions = [];
const addViolation = (rule, v) => { report.ok = false; report.violations.push({ rule, ...v }); };
const exceptFor = (v) => NAMED_EXCEPTIONS.find((x) => x.rules.includes(v.rule) && x.match(v));
const exceptionHits = new Map();
const routeViolation = (rule, v) => {
  const ex = exceptFor({ rule, ...v });
  if (ex) { exceptionHits.set(ex.id, (exceptionHits.get(ex.id) || 0) + 1); return; }
  addViolation(rule, v);
};

for (const theme of THEMES) {
  for (const [sceneName, scene] of Object.entries(SCENES)) {
    const { page, context, errors } = await bootPage(browser, scene.mobile, theme);
    try {
      await scene.setup(page);
      const baseline = await page.screenshot({ animations: 'disabled' });
      const surfaces = await page.evaluate(PROBE);
      await page.addStyleTag({ content: '*,*::before,*::after{backdrop-filter:none !important;-webkit-backdrop-filter:none !important}' });
      await page.waitForTimeout(200);
      const off = await page.screenshot({ animations: 'disabled' });
      await page.evaluate(() => document.querySelectorAll('style').forEach((s) => { if (s.textContent.includes('backdrop-filter:none !important') && s.textContent.includes('*::before')) s.remove(); }));
      await page.waitForTimeout(150);

      const rows = [];
      const exemptions = [];
      for (const s of surfaces) {
        const box = { x: s.rect.x * 2 + 2, y: s.rect.y * 2 + 2, w: s.rect.w * 2 - 4, h: s.rect.h * 2 - 4 };
        const diff = await meanAbsDiff(page, baseline, off, box);
        // 遮挡实测：该面盒内取样点是否被压在其上、不透明的元素盖住（身份 + 几何）
        const coverProbe = await page.evaluate(({ gid, occluderMinAlpha }) => {
          const el = document.querySelector(`[data-gr-idx="${gid}"]`);
          if (!el) return { covered: false, samples: 0, occluders: [] };
          const r = el.getBoundingClientRect();
          const key = (n) => n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (typeof n.className === 'string' && n.className.trim() ? '.' + n.className.trim().split(/\s+/).slice(0, 2).join('.') : '');
          const alpha = (v) => {
            const m = String(v).match(/^rgba?\(([^)]*)\)$/i);
            if (!m) { const h = String(v).match(/^#([0-9a-f]{8})$/i); return h ? parseInt(h[1].slice(6, 8), 16) / 255 : 1; }
            const parts = m[1].split(/[,/]/).map((t) => t.trim()).filter(Boolean);
            return parts.length >= 4 ? parseFloat(parts[3]) : 1;
          };
          const pts = [];
          for (const fx of [0.15, 0.5, 0.85]) for (const fy of [0.2, 0.5, 0.8]) pts.push([r.x + r.width * fx, r.y + r.height * fy]);
          const occl = new Map();
          let coveredPts = 0;
          for (const [x, y] of pts) {
            const stack = document.elementsFromPoint(x, y);
            const at = stack.indexOf(el);
            const above = at >= 0 ? stack.slice(0, at) : stack;
            let hit = null;
            for (const a of above) {
              if (a === el || el.contains(a)) continue;      // 该面自己的子件不算遮挡
              const cs = getComputedStyle(a);
              const al = alpha(cs.backgroundColor);
              if (al >= occluderMinAlpha || (cs.backgroundImage && cs.backgroundImage !== 'none')) {
                const q = a.getBoundingClientRect();
                hit = { sel: key(a), alpha: +al.toFixed(3), bg: cs.backgroundColor, rect: [+q.x.toFixed(1), +q.y.toFixed(1), +q.width.toFixed(1), +q.height.toFixed(1)] };
                break;
              }
            }
            if (hit) { coveredPts++; if (!occl.has(hit.sel)) occl.set(hit.sel, hit); }
          }
          return { covered: pts.length > 0 && coveredPts === pts.length, samples: pts.length, coveredPts, occluders: [...occl.values()] };
        }, { gid: s.gid, occluderMinAlpha: OCCLUDER_MIN_ALPHA });
        // 藏起该面自己：量背板平整度（证据），并保留该面可见度读数
        await page.evaluate((gid) => { const e = document.querySelector(`[data-gr-idx="${gid}"]`); if (e) e.style.visibility = 'hidden'; }, s.gid);
        await page.waitForTimeout(40);
        const bgShot = await page.screenshot({ animations: 'disabled' });
        const bgStats = await regionStats(page, bgShot.toString('base64'), box);
        await page.evaluate((gid) => { const e = document.querySelector(`[data-gr-idx="${gid}"]`); if (e) e.style.visibility = ''; }, s.gid);
        const backdropEdge = bgStats ? bgStats.edge : null;
        // 该面**可见度**实测：藏起该面后盒内逐像素差（≈0 ⇒ 它的贡献到不了最终画面 ⇒ 被不透明层盖住）
        const visibleDelta = await meanAbsDiff(page, baseline, bgShot, box);
        const cover = {
          visibleDelta, occluders: coverProbe.occluders,
          covered: coverProbe.covered, samples: coverProbe.samples, coveredPts: coverProbe.coveredPts,
        };
        const v = glassSampleVerdict({ deltaPx: diff, backdropEdge, cover });
        const row = {
          scene: sceneName, theme, key: s.key, path: s.path, pe: s.pe, gid: s.gid,
          rect: s.rect, backdropFilter: s.backdropFilter, background: s.background, alpha: s.alpha,
          diff, backdropEdge, visibleDelta, cover, verdict: v.status, flat: !v.judgeable, judgeable: v.judgeable, reason: v.reason,
          rootCauses: s.rootCauses,
        };
        rows.push(row);
        if (v.judgeable && !v.ok) {
          routeViolation('R1', {
            scene: sceneName, theme, sel: s.key, path: s.path, detail: v.reason,
            root: s.rootCauses,
            evidence: { deltaPx: diff, backdropEdge, visibleDelta, cover },
          });
        }
        if (!v.judgeable) {
          // 不可判样本**逐条具名记录**（元素 + Δpx 原值 + 背板平整度 / 遮挡元素身份与几何）——
          // 豁免只对这里列出的样本成立，不是阈值放宽。
          exemptions.push({ scene: sceneName, theme, sel: s.key, pe: s.pe || null, status: v.status, deltaPx: diff, backdropEdge, visibleDelta, cover, reason: v.reason, rect: s.rect });
        }
        if (s.rootCauses.length) {
          routeViolation('R2', { scene: sceneName, theme, sel: s.key, detail: '祖先链上有 backdrop root 触发点', root: s.rootCauses });
        }
      }

      // R3 降级档一致性：给页面加 html.no-backdrop，逐面读 computed 底
      await page.evaluate(() => document.documentElement.classList.add('no-backdrop'));
      await page.waitForTimeout(200);
      const alphaOf = criteriaAlphaOf;
      for (const r of rows) {
        if (r.alpha < 0.05 || r.alpha >= DEGRADE_MIN_ALPHA) continue; // 纯渐隐面 / 本相已不透明 ⇒ 不在此列
        const got = await page.evaluate(({ gid, pe }) => {
          const el = document.querySelector(`[data-gr-idx="${gid}"]`); if (!el) return null;
          const cs = getComputedStyle(el, pe || null);
          return { background: cs.backgroundColor, bf: cs.backdropFilter };
        }, { gid: r.gid, pe: r.pe });
        if (!got) continue;
        if (alphaOf(got.background) < DEGRADE_MIN_ALPHA) {
          routeViolation('R3', { scene: sceneName, theme, sel: r.key, detail: `降级（html.no-backdrop）下底仍是 α=${alphaOf(got.background).toFixed(3)}（${got.background}）= 无模糊引擎里的玻璃纸`, root: [] });
        }
      }
      report.scenes[`${theme}/${sceneName}`] = { surfaces: rows.length, rows, exemptions, errors };
      allExemptions.push(...exemptions);
    } finally {
      await context.close();
    }
  }
}

await browser.close();

const total = Object.values(report.scenes).reduce((s, v) => s + v.surfaces, 0);
report.exceptions = NAMED_EXCEPTIONS.map((x) => ({ id: x.id, hits: exceptionHits.get(x.id) || 0, reason: x.reason }));
report.exemptions = allExemptions;
const staleExceptions = report.exceptions.filter((x) => x.hits === 0);

// ── 输出 ──────────────────────────────────────────────────────────────────
if (JSON_OUT) {
  console.log(JSON.stringify(report, null, 2));
  process.exit(report.ok ? 0 : 1);
}

if (INVENTORY) {
  console.log(`# 全站玻璃面清点（静态根 ${WEB} · ${engine}）`);
  console.log(`# 判据单源：${SPEC_PATH}`);
  console.log(`# 判定：Δpx = **该面自己**开/关自身 backdrop-filter 的盒内逐像素差；背板edge = 藏起该面后盒内背板边缘能量（<${FLAT_EDGE_MAX} ⇒ 平色背板）；可见度 = 藏起该面后盒内逐像素差（<${INERT_MAX} ⇒ 该面被不透明层完全盖住）`);
  for (const [k, v] of Object.entries(report.scenes)) {
    console.log(`\n== ${k} ==  面 ${v.surfaces} 个`);
    console.log('| 选择器 | 伪元素 | 盒(x,y,w,h) | backdrop-filter | source bg | α | Δpx(同面) | 背板edge | 可见度 | 运行时判定 | 祖先 backdrop-root |');
    console.log('|---|---|---|---|---|---|---|---|---|---|---|');
    for (const r of v.rows) {
      console.log(`| \`${r.key}\` | ${r.pe || '—'} | ${r.rect.x},${r.rect.y},${r.rect.w},${r.rect.h} | ${r.backdropFilter} | ${r.background} | ${r.alpha.toFixed(2)} | ${r.diff} | ${r.backdropEdge} | ${r.visibleDelta} | ${r.judgeable ? r.verdict : `豁免(${r.verdict})`} | ${r.rootCauses.length ? r.rootCauses.map((x) => `${x.sel}: ${x.flags.join(',')}`).join(' · ') : '—'} |`);
    }
  }
  console.log(`\n违规 ${report.violations.length} · 具名例外命中 ${report.exceptions.filter((x) => x.hits > 0).map((x) => `${x.id}×${x.hits}`).join(', ') || '无'} · 不可判样本（豁免，逐条具名）${report.exemptions.length}`);
  for (const v of report.violations) console.log(`  [${v.rule}] ${v.scene}/${v.theme} ${v.sel} — ${v.detail}`);
  for (const x of report.exceptions) console.log(`  例外 ${x.id} 命中 ${x.hits}`);
  for (const e of report.exemptions) console.log(`  豁免 ${e.scene}/${e.theme} ${e.sel}${e.pe || ''} [${e.status}] Δpx=${e.deltaPx} 背板edge=${e.backdropEdge} 可见度=${e.visibleDelta} — ${e.reason}`);
  process.exit(0);
}

if (!report.ok) {
  console.error('✗ glass-runtime gate FAILED');
  console.error(`  场景 ${Object.keys(report.scenes).length} · 玻璃面 ${total} · 违规 ${report.violations.length} · 例外命中 ${report.exceptions.reduce((s, x) => s + x.hits, 0)} · 不可判样本 ${report.exemptions.length}`);
  for (const v of report.violations) {
    console.error(`  [${v.rule}] ${v.scene}/${v.theme}  ${v.sel}`);
    console.error(`      ${v.detail}`);
    for (const r of v.root ?? []) console.error(`      祖先 ${r.sel}  ${r.flags.join(' | ')}`);
  }
  console.error('\n处置：R1 = 让这一族的 backdrop-filter 真的采样到背板（把挡在它前面的 backdrop root（祖先的 filter / will-change:opacity / opacity<1 / backdrop-filter）撤掉，或把材质层挪到 root 之外）；只有在「该面可见 + 背后背板非平色」时 R1 才成立，否则该样本按不可判豁免并逐条具名。');
  console.error('      R2 = 玻璃面的祖先链上不许新建 backdrop root（这是 R1 红时的根因行）；');
  console.error('      R3 = 降级档（html.no-backdrop / @supports not）必须回落到不透明实底 —— α .16 底只在模糊能采样时才是玻璃。');
  console.error('      配方本体与单源纪律见 css/glass.css + scripts/check-glass-unify.mjs；判据与阈值单源见 scripts/glass-runtime-criteria.mjs。');
  process.exit(1);
}
if (staleExceptions.length) {
  console.warn(`⚠ glass-runtime gate passed，但具名例外未命中（陈旧？复核后删除）：${staleExceptions.map((x) => x.id).join(', ')}`);
}
const judged = total - report.exemptions.length;
console.log(`✓ glass-runtime gate passed — 场景 ${Object.keys(report.scenes).length} · 玻璃面 ${total}（可判 ${judged} 全部真采样；不可判样本 ${report.exemptions.length} 逐条具名豁免）· 祖先链无 backdrop root · 降级档全不透明 · 具名例外命中 ${report.exceptions.reduce((s, x) => s + x.hits, 0)}`);
// 豁免**逐条自证**：即使门禁绿，也把每一个不可判样本的元素 + Δpx 原值 + 背板读数 / 遮挡身份打出来，
// 免得「绿」掩盖「豁免吞掉了真凶」。豁免不是阈值放宽，是实测不可判。
for (const e of report.exemptions) {
  const why = e.status === 'covered'
    ? `可见度=${e.visibleDelta} < ${INERT_MAX}（藏起它不改像素）⇒ 被不透明层盖住` + ((e.cover && e.cover.occluders && e.cover.occluders.length) ? `；遮挡 ${e.cover.occluders.map((o) => `${o.sel} (α=${o.alpha}, box ${o.rect.join(',')})`).join(' · ')}` : '')
    : e.status === 'flat-backdrop'
      ? `背板edge=${e.backdropEdge} < ${FLAT_EDGE_MAX}（平色背板）`
      : e.reason;
  console.log(`  · 豁免 ${e.scene}/${e.theme} ${e.sel}${e.pe || ''} [${e.status}] Δpx=${e.deltaPx} — ${why}`);
}
process.exit(0);
