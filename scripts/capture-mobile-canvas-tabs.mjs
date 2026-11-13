#!/usr/bin/env node
// capture-mobile-canvas-tabs.mjs — 手机 canvas 顶部环带（方案 C）真渲染读数与取证
// （mobile-canvas-tabs-c 批 2026-11-06；作者 08:33 逐字：
//  「采用方案c 主胶囊要采用毛玻璃底 然后背景的文字不使用胶囊 但是还有电脑端
//    hover 上下选项的那种模糊效果 文字两边不要渐隐」）
//
// 为什么另有一支脚本：`scripts/check-mobile-canvas-strip.mjs` 判的是**静态声明**
// （谁声明了什么）。它读不出「这块玻璃压在真实正文上长什么样」「文字的左右边缘
// 到底有没有被淡化」「下缘那条 28px 是不是真的在化开」。本脚本补的就是只能在
// **真渲染**里拿到的四类读数：
//   · 几何：带高 / 玻璃带高 / 渐隐带高与起点 / 正文初态让位；
//   · 材质：`.mf-strip::before` 与主胶囊 `.on::after` 的 backdrop-filter 实际值，
//     两侧签的实际 `filter`（环邻位模糊，与桌面 hover 上下选项同两枚令牌）；
//   · 渐隐：strip / chip / label / ::after 的 computed `mask-image`（水平渐隐证据 =
//     全为 none）+ **像素腿**（label 左右边缘斜坡带宽度，0 渐隐 ⇒ ≤5 设备像素）；
//   · 可读性：主胶囊与两侧签的**实测对比度**（WCAG 2.1，像素法：背景取区域主色、
//     前景取深浅两极的中位）。
//
// 装置：零端口、零进程、真 Chromium（与 tests/ 系 spec 同一套自包含夹具配方）——
//   page.route 从**磁盘**喂静态资源（`--root` 可把改前检出树整棵喂进来）、
//   routeWebSocket 打桩、localStorage 打桩身份域。不碰宿主实例 6138、不写库。
//
// 用法（改前 / 改后同一份脚本、同一组脸）：
//   node scripts/capture-mobile-canvas-tabs.mjs \
//        --root /tmp/mct-before/src/main/resources/web --tag before \
//        --out <dir>   (default = EvidenceTemp batch)
//   node scripts/capture-mobile-canvas-tabs.mjs \
//        --root src/main/resources/web --tag after --out <同一个目录>
//
// 产出：
//   <tag>-375-<theme>.png        整屏（375×812）
//   <tag>-375-<theme>-strip.png  带区窄条（0..132，含正文穿行段）
//   <tag>-1440-<theme>.png       桌面 canvas 面板（环胶囊 hover 态）
//   <tag>-readings.json          全部读数（几何 / 材质 / 渐隐 / 对比度 / 像素剖面）
//
// 退出码：0 = 采集完成（**不做红绿判定** —— 判定在 check-mobile-canvas-strip.mjs
// 与 tests/ 系 spec；本脚本只出读数，读数原样落 JSON 供报告引用）；1 = 装配错误。

import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, extname, normalize, isAbsolute, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evidenceBatch } from './lib/evidence-batch.mjs';
import { createRequire } from 'node:module';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };

const REPO = fileURLToPath(new URL('..', import.meta.url));
const ROOT_ARG = valOf('--root', 'src/main/resources/web');
const WEB = normalize(isAbsolute(ROOT_ARG) ? ROOT_ARG : join(REPO, ROOT_ARG));
const TAG = valOf('--tag', 'after');
// Default output = an EvidenceTemp batch (manifest + non-active ⇒ TTL-sweepable).
// The repo-tree `reports-evidence/` dir is a retired legacy evidence location and
// must not receive new default writes; an explicit --out keeps its meaning.
const OUT_ARG = valOf('--out', null);
const OUT = OUT_ARG ? normalize(isAbsolute(OUT_ARG) ? OUT_ARG : join(REPO, OUT_ARG)) : evidenceBatch('mobile-canvas-tabs', 'capture-c');
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);

if (!existsSync(join(WEB, 'index.html'))) {
  console.error(`装配错误：${WEB}/index.html 不存在（--root 指向 web 树）`);
  process.exit(1);
}
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.map': 'application/json', '.wasm': 'application/wasm',
};

const SID = 'canvas-tabs-c-session';

const require = createRequire(import.meta.url);
function loadPlaywright() {
  const tried = [];
  const cands = [];
  if (process.env.NEBFLOW_PW_PATH) cands.push(process.env.NEBFLOW_PW_PATH);
  cands.push('playwright', 'playwright-core', '@playwright/test');
  for (const c of cands) {
    try { return require(c); } catch (e) { tried.push(`${c} (${String(e.message).split('\n')[0]})`); }
  }
  throw new Error('playwright 不可解析：' + tried.join(', '));
}

/** 三个页签：一枚超长（同屏可见的「正文穿行」证据）、两枚常规（环位姿判据）。 */
const DOCS = [
  ['d1', 'meeting-notes-2026-11-05.md', 'notes'],
  ['d2', 'design-spec-value-converge.md', 'spec'],
  ['d3', 'release-checklist.md', 'rel'],
];

/** 正文（真 markdown 渲染器渲进活动 pane）：长到足以滚过整个带区。 */
const MD_BODY = [
  '# 会议纪要 · 2026-11-05',
  '',
  '十一月的第一批改动已经合并，接下来要把顶部标签带的视觉口径收敛到一套。',
  '这一段文字的存在意义只有一个：**让它从顶栏底下滚过去**。',
  '',
  '## 待办',
  '',
  '- 主胶囊采用毛玻璃底（唯一配方，不另造一套）',
  '- 背景的文字不使用胶囊',
  '- 保留电脑端 hover 上下选项的那种模糊效果',
  '- 文字两边不要渐隐',
  '- 下边缘渐隐厚度 = 28px',
  '',
  '## 背景',
  '',
  '方案 C 的要害是「没有下边缘」：64px 玻璃把正文洗成色块，接着 28px 渐隐带把剩下的',
  '字形慢慢化开。滚走的行不是被一条线切掉，而是被化掉的。',
  '',
  '这一段要继续长一点，好让滚动后的首行正好落在 64..92 的渐隐带里。',
  '再多写两行，避免首屏出现空白导致剖面读不到墨迹。',
  '',
  '## 结论',
  '',
  '一套实现两处生效：桌面 hover 环与手机横向环带共用同一对模糊令牌。',
].join('\n');

// ── 静态服务 + WS 打桩（与 tests/ 系 spec 同一配方）──────────────────────────
async function wireContext(context) {
  await context.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    try { Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true }); } catch { }
  });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await context.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootPage(browser, viewport, theme) {
  const mobile = viewport === 'mobile';
  const context = await browser.newContext({
    viewport: mobile ? { width: 375, height: 812 } : { width: 1440, height: 900 },
    hasTouch: mobile,
    colorScheme: theme,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
    deviceScaleFactor: 2,
  });
  await wireContext(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { page, context, errors };
}

/** 开文档卡（canvas.openTab）。多卡用独立 type 避免预览组互相顶掉。 */
async function openDocs(page) {
  await page.evaluate((list) => {
    import('/js/canvas.js').then((canvas) => {
      for (const [id, title, type] of list) canvas.openTab(id, title, { type: type || 'generic' });
    });
  }, DOCS);
  await page.waitForTimeout(320);
}

/** 手机档：dock 进 canvas 页。 */
async function dockToCanvas(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="canvas"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(320);
}

/** 用**产品真渲染器**把正文渲进活动 pane（不复制任何解析逻辑）——
 *  只有真 markdown 面才有 `.canvas-md-scroll.canvas-viewer-safe-top` 这层滚动宿主。 */
async function renderBody(page, content) {
  const ok = await page.evaluate(async (md) => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active')
      || document.querySelector('.mf-cv-host > .canvas-tab-pane');
    if (!pane) return false;
    const mod = await import('/js/viewers/markdown.js');
    await mod.default.render(pane, { content: md, absPath: '/tmp/mct-notes.md', fileName: 'meeting-notes-2026-11-05.md', anchor: null, readOnly: true });
    return true;
  }, content);
  await page.waitForTimeout(320);
  return ok;
}

// ── 读数 ──────────────────────────────────────────────────────────────────
/** 手机档：环带几何 / 材质 / 渐隐 / 令牌。 */
async function probeStrip(page) {
  return page.evaluate(() => {
    const strip = document.querySelector('.mf-strip');
    if (!strip) return { missing: true };
    const root = getComputedStyle(document.documentElement);
    const before = getComputedStyle(strip, '::before');
    const after = getComputedStyle(strip, '::after');
    const chips = [...strip.querySelectorAll('.mf-strip-chip')];
    const on = chips.find((c) => c.classList.contains('on')) || null;
    const side = chips.find((c) => !c.classList.contains('on')) || null;
    const rs = strip.getBoundingClientRect();
    const rectOf = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    const px = (v) => parseFloat(v) || 0;
    const label = (el) => {
      if (!el) return null;
      const l = el.querySelector('.mf-strip-label');
      const la = getComputedStyle(el, '::after');
      const cs = getComputedStyle(el);
      return {
        id: el.dataset.tabId,
        cls: el.className,
        label: l ? l.textContent : null,
        filter: cs.filter,
        opacity: cs.opacity,
        // 时长曲线同源证据（作者口径 ④「同一时长曲线」）：transition 的
        // property / duration / timing 三段原样落盘，供报告与桌面缘位逐项对照。
        transitionProperty: cs.transitionProperty,
        transitionDuration: cs.transitionDuration,
        transitionTiming: cs.transitionTimingFunction,
        labelMask: l ? (getComputedStyle(l).maskImage || 'none') : null,
        labelRect: rectOf(l),
        afterMask: la.maskImage || 'none',
        afterBg: la.backgroundImage === 'none' ? la.backgroundColor : `${la.backgroundImage} | ${la.backgroundColor}`,
        afterBorderWidth: la.borderTopWidth,
        afterShadow: la.boxShadow,
        backdrop: la.backdropFilter || la.webkitBackdropFilter || 'none',
      };
    };
    return {
      stripRect: { x: +rs.x.toFixed(1), y: +rs.y.toFixed(1), w: +rs.width.toFixed(1), h: +rs.height.toFixed(1) },
      stripBg: getComputedStyle(strip).backgroundColor,
      stripMask: getComputedStyle(strip).maskImage || 'none',
      glass: {
        height: before.height,
        top: before.top,
        bg: before.backgroundColor,
        bgImage: before.backgroundImage.slice(0, 120),
        backdrop: before.backdropFilter || before.webkitBackdropFilter || 'none',
        borderTop: before.borderTopWidth,
        borderBottom: before.borderBottomWidth,
      },
      fade: {
        height: after.height,
        heightPx: px(after.height),
        top: after.top,
        topPx: px(after.top),
        bgImage: after.backgroundImage.slice(0, 200),
        backdrop: after.backdropFilter || after.webkitBackdropFilter || 'none',
        mask: after.maskImage || 'none',
      },
      contentOffset: (() => {
        const sc = document.querySelector('.mf-cv-host .canvas-md-scroll')
          || document.querySelector('.canvas-md-scroll')
          || document.querySelector('.canvas-viewer-safe-top');
        if (!sc) return null;
        const cs = getComputedStyle(sc);
        const r = sc.getBoundingClientRect();
        const firstText = sc.querySelector('h1, h2, p, li');
        const ftr = firstText ? firstText.getBoundingClientRect() : null;
        return {
          cls: sc.className,
          paddingTop: cs.paddingTop,
          bg: cs.backgroundColor,
          bgImage: cs.backgroundImage,
          radius: cs.borderRadius,
          rectTop: +r.top.toFixed(1),
          firstTextTop: ftr ? +ftr.top.toFixed(1) : null,
          scrollTop: sc.scrollTop,
          scrollable: sc.scrollHeight > sc.clientHeight,
        };
      })(),
      tokens: {
        ringBlur: root.getPropertyValue('--ds-ring-blur').trim(),
        ringSaturate: root.getPropertyValue('--ds-ring-saturate').trim(),
        ringDur: root.getPropertyValue('--ds-ring-dur').trim(),
        fadeVeil: root.getPropertyValue('--ds-fade-veil').trim().slice(0, 90),
        glassFilter: root.getPropertyValue('--nf-glass-filter').trim(),
        stripH: root.getPropertyValue('--mf-strip-h').trim(),
        stripGlassH: root.getPropertyValue('--mf-strip-glass-h').trim(),
        stripFadeH: root.getPropertyValue('--mf-strip-fade-h').trim(),
      },
      on: label(on),
      side: label(side),
      chipCount: chips.length,
    };
  });
}

/** 像素读数：区域主色 / 平均色 / 前景-背景对比度（WCAG 2.1 相对亮度）。 */
async function regionStats(page, buf) {
  const b64 = buf.toString('base64');
  return page.evaluate(async (data) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + data;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const d = ctx.getImageData(0, 0, c.width, c.height).data;
    const lum = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
    const buckets = new Map();
    let r = 0, g = 0, b = 0, n = 0;
    const lums = [];
    for (let i = 0; i < d.length; i += 4) {
      const R = d[i], G = d[i + 1], B = d[i + 2];
      r += R; g += G; b += B; n++;
      lums.push(lum(R, G, B));
      const k = ((R >> 3) << 10) | ((G >> 3) << 5) | (B >> 3);
      const cur = buckets.get(k) || { n: 0, r: 0, g: 0, b: 0 };
      cur.n++; cur.r += R; cur.g += G; cur.b += B;
      buckets.set(k, cur);
    }
    let best = null;
    for (const v of buckets.values()) if (!best || v.n > best.n) best = v;
    const mode = { r: best.r / best.n, g: best.g / best.n, b: best.b / best.n, share: +(best.n / n).toFixed(3) };
    // 前景 = 与主色亮度差最大的那一极（取最亮/最暗 8% 的中位）—— 玻璃上的字。
    lums.sort((a, b2) => a - b2);
    const lowQ = lums[Math.floor(lums.length * 0.04)];
    const highQ = lums[Math.floor(lums.length * 0.96)];
    const modeLum = lum(mode.r, mode.g, mode.b);
    const fgLum = Math.abs(lowQ - modeLum) > Math.abs(highQ - modeLum) ? lowQ : highQ;
    const rel = (l) => { const s = l / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
    const ratio = (a, b2) => { const l1 = rel(a), l2 = rel(b2); const hi = Math.max(l1, l2), lo = Math.min(l1, l2); return +((hi + 0.05) / (lo + 0.05)).toFixed(2); };
    return {
      px: n,
      mean: { r: +(r / n).toFixed(1), g: +(g / n).toFixed(1), b: +(b / n).toFixed(1) },
      mode: { r: +mode.r.toFixed(1), g: +mode.g.toFixed(1), b: +mode.b.toFixed(1), share: mode.share },
      fgLum: +fgLum.toFixed(1),
      bgLum: +modeLum.toFixed(1),
      contrast: ratio(fgLum, modeLum),
    };
  }, b64);
}

/** 逐列墨迹对比度 → 左右边缘斜坡带宽度（设备像素；0 渐隐 ⇒ ≤5）。 */
async function labelRamp(page, buf) {
  const b64 = buf.toString('base64');
  return page.evaluate(async (data) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + data;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const { width, height } = c;
    const d = ctx.getImageData(0, 0, width, height).data;
    const LUMA = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
    const cols = [];
    for (let x = 0; x < width; x++) {
      let lo = 255, hi = 0;
      for (let y = 0; y < height; y++) {
        const i = (y * width + x) * 4;
        const l = LUMA(d[i], d[i + 1], d[i + 2]);
        if (l < lo) lo = l;
        if (l > hi) hi = l;
      }
      cols.push(hi - lo);
    }
    const ink = 8;
    const peak = Math.max(...cols);
    let from = null, to = null;
    for (let x = 0; x < width; x++) if (cols[x] >= ink) { from = x; break; }
    for (let x = width - 1; x >= 0; x--) if (cols[x] >= ink) { to = x; break; }
    if (from === null || to === null) return { width, height, peak, rampLeft: 0, rampRight: 0, inkFrom: null, inkTo: null };
    const span = to - from + 1;
    const mid = cols.slice(from + Math.floor(span * 0.2), to - Math.floor(span * 0.2) + 1);
    const sorted = [...mid].sort((a, b2) => a - b2);
    const interiorMedian = sorted.length ? sorted[Math.floor(sorted.length / 2)] : peak;
    const lim = interiorMedian * 0.85;
    let rampLeft = 0;
    for (let x = from; x <= to && cols[x] < lim; x++) rampLeft++;
    let rampRight = 0;
    for (let x = to; x >= from && cols[x] < lim; x--) rampRight++;
    return { width, height, peak, interiorMedian, inkFrom: from, inkTo: to, rampLeft, rampRight };
  }, b64);
}

/** 纵向剖面：带区一列在 0..132 上逐行亮度（下缘「化开」证据）。 */
async function verticalProfile(page, buf) {
  const b64 = buf.toString('base64');
  return page.evaluate(async (data) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + data;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const { width, height } = c;
    const d = ctx.getImageData(0, 0, width, height).data;
    const LUMA = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
    const rows = [];
    for (let y = 0; y < height; y++) {
      let sum = 0, n = 0, lo = 255, hi = 0;
      for (let x = 0; x < width; x++) {
        const i = (y * width + x) * 4;
        const l = LUMA(d[i], d[i + 1], d[i + 2]);
        sum += l; n++;
        if (l < lo) lo = l;
        if (l > hi) hi = l;
      }
      rows.push({ y, mean: +(sum / n).toFixed(1), range: +(hi - lo).toFixed(1) });
    }
    return { height, width, rows };
  }, b64);
}

// ── 主流程 ────────────────────────────────────────────────────────────────
const { chromium } = loadPlaywright();
const browser = await chromium.launch();
const readings = { tag: TAG, root: WEB, themes: {}, desktop: {}, errors: [] };

for (const theme of THEMES) {
  const { page, context, errors } = await bootPage(browser, 'mobile', theme);
  await openDocs(page);
  await dockToCanvas(page);
  // 真 markdown 正文：只有它有 `.canvas-md-scroll.canvas-viewer-safe-top` 滚动宿主，
  // 初态让位（padding-top）与「正文穿行」两件事都挂在这层上。
  const rendered = await renderBody(page, MD_BODY);
  readings.themes[theme] = { renderedBody: rendered };
  // 让正文滚起来：滚走的行进入 64..92 的渐隐带 = 「化开」证据。
  await page.evaluate(() => {
    const sc = document.querySelector('.mf-cv-host .canvas-md-scroll') || document.querySelector('.canvas-md-scroll');
    if (sc) sc.scrollTop = 56;
  });
  await page.waitForTimeout(260);

  const strip = await probeStrip(page);
  readings.themes[theme] = { ...readings.themes[theme], strip, errors: [...errors] };

  // 整屏 + 带区窄条
  const fullName = `${TAG}-375-${theme}.png`;
  const stripName = `${TAG}-375-${theme}-strip.png`;
  const fullBuf = await page.screenshot({ path: join(OUT, fullName) });
  const stripBuf = await page.screenshot({ path: join(OUT, stripName), clip: { x: 0, y: 0, width: 375, height: 132 } });

  // 带区读数：玻璃带主色（不含字）/ 渐隐带主色 / 正文区主色
  readings.themes[theme].glassBandStats = await regionStats(page, await page.screenshot({ clip: { x: 0, y: 0, width: 375, height: 56 } }));
  readings.themes[theme].fadeBandStats = await regionStats(page, await page.screenshot({ clip: { x: 0, y: 64, width: 375, height: 28 } }));
  readings.themes[theme].contentStats = await regionStats(page, await page.screenshot({ clip: { x: 0, y: 96, width: 375, height: 120 } }));
  readings.themes[theme].verticalProfile = await verticalProfile(page, stripBuf);

  // 主胶囊 / 两侧签：区域像素对比度 + 文字左右边缘斜坡带
  for (const which of ['on', 'side']) {
    const info = strip[which];
    if (!info || !info.labelRect) continue;
    const r = info.labelRect;
    const clip = { x: Math.max(0, r.x - 2), y: Math.max(0, r.y - 1), width: Math.min(375 - Math.max(0, r.x - 2), r.w + 4), height: r.h + 2 };
    if (clip.width < 4 || clip.height < 4) continue;
    const buf = await page.screenshot({ clip });
    const key = which === 'on' ? 'onLabel' : 'sideLabel';
    readings.themes[theme][key] = {
      clip,
      stats: await regionStats(page, buf),
      ramp: await labelRamp(page, buf),
      fileName: `${TAG}-375-${theme}-${which}.png`,
    };
    writeFileSync(join(OUT, readings.themes[theme][key].fileName), buf);
  }
  void fullBuf;

  await context.close();
}

// ── 桌面 1440：环胶囊 hover 态（同两枚令牌的另一处消费面，证明「一处实现两处生效」）──
for (const theme of THEMES) {
  const { page, context, errors } = await bootPage(browser, 'desktop', theme);
  await openDocs(page);
  await page.waitForTimeout(300);
  // 打开 canvas 面板（桌面腿）
  await page.evaluate(() => { import('/js/canvas.js').then((c) => { if (c.openPanel) c.openPanel(); }); });
  await page.waitForTimeout(400);
  const header = page.locator('.canvas-header').first();
  if (await header.count()) { await header.hover({ timeout: 4000 }).catch(() => {}); }
  await page.waitForTimeout(500);
  const desk = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    const edges = [...document.querySelectorAll('.cr-capsule.is-edge')];
    const center = document.querySelector('.cr-capsule.is-center');
    const cs = (el) => (el ? { filter: getComputedStyle(el).filter, opacity: getComputedStyle(el).opacity } : null);
    return {
      ringOpen: !!document.querySelector('#canvas-capsule-ring.open'),
      edgeCount: edges.length,
      edge: cs(edges[0]),
      center: cs(center),
      tokens: {
        ringBlur: root.getPropertyValue('--ds-ring-blur').trim(),
        ringSaturate: root.getPropertyValue('--ds-ring-saturate').trim(),
        ringDur: root.getPropertyValue('--ds-ring-dur').trim(),
      },
    };
  });
  const shot = `${TAG}-1440-${theme}.png`;
  await page.screenshot({ path: join(OUT, shot) });
  readings.desktop[theme] = { ...desk, fileName: shot, errors: [...errors] };
  await context.close();
}

await browser.close();

const jsonName = `${TAG}-readings.json`;
writeFileSync(join(OUT, jsonName), JSON.stringify(readings, null, 2));

// ── 控制台摘要（人读；判定不在这里）────────────────────────────────────────
const line = (k, v) => console.log(`  ${k.padEnd(28)} ${v}`);
for (const theme of THEMES) {
  const t = readings.themes[theme];
  if (!t) continue;
  console.log(`\n== 375 · ${theme} ==`);
  line('strip 高 / 宽', `${t.strip.stripRect?.h} × ${t.strip.stripRect?.w}`);
  line('玻璃带 ::before', `h=${t.strip.glass?.height} top=${t.strip.glass?.top} backdrop=${t.strip.glass?.backdrop}`);
  line('渐隐带 ::after', `h=${t.strip.fade?.height} (${t.strip.fade?.heightPx}px) top=${t.strip.fade?.top} (${t.strip.fade?.topPx}px) backdrop=${t.strip.fade?.backdrop}`);
  line('正文宿主', `${t.strip.contentOffset?.cls || '(缺)'} pt=${t.strip.contentOffset?.paddingTop} bg=${t.strip.contentOffset?.bg} r=${t.strip.contentOffset?.radius} 首字 top=${t.strip.contentOffset?.firstTextTop}`);
  line('令牌 --ds-ring-blur/-sat', `${t.strip.tokens?.ringBlur} / ${t.strip.tokens?.ringSaturate}`);
  line('mask（strip/label/after）', `${t.strip.stripMask} | ${t.strip.on?.labelMask} | ${t.strip.side?.labelMask} | ${t.strip.side?.afterMask}`);
  line('主胶囊 filter / side filter', `${t.strip.on?.filter} | ${t.strip.side?.filter}`);
  line('主胶囊 transition', `${t.strip.on?.transitionProperty} · ${t.strip.on?.transitionDuration} · ${t.strip.on?.transitionTiming}`);
  line('侧签 transition', `${t.strip.side?.transitionProperty} · ${t.strip.side?.transitionDuration} · ${t.strip.side?.transitionTiming}`);
  line('主胶囊 ::after backdrop', String(t.strip.on?.backdrop));
  line('侧签 ::after 底/边/影', `${t.strip.side?.afterBg} | ${t.strip.side?.afterBorderWidth} | ${t.strip.side?.afterShadow}`);
  if (t.onLabel) line('主胶囊对比度 / 斜坡带', `${t.onLabel.stats.contrast}:1 · L${t.onLabel.ramp.rampLeft} R${t.onLabel.ramp.rampRight} px`);
  if (t.sideLabel) line('侧签对比度 / 斜坡带', `${t.sideLabel.stats.contrast}:1 · L${t.sideLabel.ramp.rampLeft} R${t.sideLabel.ramp.rampRight} px`);
  line('玻璃带主色', `${JSON.stringify(t.glassBandStats?.mode)}`);
  line('渐隐带主色 / 正文主色', `${JSON.stringify(t.fadeBandStats?.mode)} | ${JSON.stringify(t.contentStats?.mode)}`);
  if (t.verticalProfile?.rows?.length) {
    const band = (y0, y1) => {
      const rows = t.verticalProfile.rows.filter((r) => r.y >= y0 && r.y < y1);
      const avg = (k) => (rows.reduce((s, r) => s + r[k], 0) / Math.max(1, rows.length)).toFixed(1);
      return `mean=${avg('mean')} 墨迹极差=${avg('range')}`;
    };
    // 设备像素（dsf=2）：64 CSS = 128、92 CSS = 184
    line('纵向剖面 0..128（玻璃带）', band(0, 128));
    line('纵向剖面 128..184（渐隐）', band(128, 184));
    line('纵向剖面 184..264（正文）', band(184, 264));
  }
}
for (const theme of THEMES) {
  const d = readings.desktop[theme];
  if (!d) continue;
  console.log(`\n== 1440 · ${theme} ==`);
  line('环胶囊 open / edge 数', `${d.ringOpen} / ${d.edgeCount}`);
  line('缘位 filter（桌面 hover 档）', String(d.edge?.filter));
  line('令牌', `${d.tokens?.ringBlur} / ${d.tokens?.ringSaturate} / ${d.tokens?.ringDur}`);
}
console.log(`\n读数：${join(OUT, jsonName)}`);
