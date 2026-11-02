#!/usr/bin/env node
// mobile-canvas-ring-center-nofade-shots.mjs — 中签两端零虚化渐隐批证据抓取
// （作者令 2026-11-02 11:23「前置显示的那个标签，两端不要有虚化渐隐」）
//
// 覆盖（375 × 812，暗/亮双主题，deviceScaleFactor 2）：
//   b1-ring-2tabs     2 签（中签 + 一枚邻签）
//   b2-ring-3tabs     3 签
//   b3-ring-5tabs     5 签（|k|≥2 归零 = 绕到圈外）
//   b4-long-title     3 签 + 中签超长标题（≥10 汉字，验整字收束：不溢出/不硬裁/无省略号）
//   b5-center-zoom    中签放大特写（同视口 4× 裁切，看两端是否干净）
//   b6-expanded       点活签展开全名（超长名在展开档能放下更多整字）
//   b7-neighbor-fade  邻签渐隐仍在（中签短标题 + 邻签超长标题 ⇒ 邻签 mask 化开）
//   d1-desktop        桌面 canvas（证明桌面零变化）
// 每帧附 JSON 探针：中签 label 的 mask-image 计算值 / 文字左右端点 alpha 读数 /
// 邻签 mask 仍在 / cy 极差 / text-overflow / 整字收束读数 / 热区。
// 收尾钉零 console error。
//
// 改前对照：从上一批 evidence/20261102_canvas-ring-tabbar-align/ 取同视口帧
// （a1/a2/a3/a6 四帧同 seed，见批文档 §5），另生成本批的「前后并排」对照图。
//
// 用法：node tests/mobile-canvas-ring-center-nofade-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync, existsSync, copyFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { labelEdgeRamp } from './_label-edge-pixels.mjs';
import { decodePng, encodePng } from '../scripts/lib/png-diff.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const ROOT = join(HERE, '..');
const OUT = process.argv[2] || join(ROOT, 'evidence', '20261102_center-tab-no-fade');
const BEFORE = join(ROOT, 'evidence', '20261102_canvas-ring-tabbar-align');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'center-nofade-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage(colorScheme, viewport = { width: 375, height: 812 }) {
  const ctx = await browser.newContext({
    viewport, colorScheme, deviceScaleFactor: 2, hasTouch: true,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${colorScheme}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${colorScheme}] pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(900);
  return { ctx, page };
}

async function dockCanvas(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(700);
}

/** 清空页签 + 灌入 N 张文档卡（每卡独立 type 避开预览组替换）。 */
async function seed(page, docs) {
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    const n = await import('/js/mobile/navController.js');
    for (const d of n.docTabs()) c.closeTab(d.id);
  });
  await sleep(220);
  await page.evaluate((list) => import('/js/canvas.js').then((canvas) => {
    for (const [id, title, type] of list) canvas.openTab(id, title, { type });
  }), docs);
  await sleep(820);
}

const DOC = (i, title) => [`cn-${i}`, title, `cn-type-${i}`];

/** 最近邻整数倍放大（零依赖：解 PNG → 重采样 → 重新编码）。特写帧用。 */
function scalePng(buf, k) {
  const { width, height, data } = decodePng(buf);
  const W = width * k;
  const H = height * k;
  const out = new Uint8Array(W * H * 4);
  for (let y = 0; y < H; y++) {
    const sy = Math.floor(y / k);
    for (let x = 0; x < W; x++) {
      const sx = Math.floor(x / k);
      const si = (sy * width + sx) * 4;
      const di = (y * W + x) * 4;
      out[di] = data[si];
      out[di + 1] = data[si + 1];
      out[di + 2] = data[si + 2];
      out[di + 3] = data[si + 3];
    }
  }
  return encodePng(W, H, out);
}

/** 像素面读数：截当前帧 PNG，对 label 区域做「边缘斜坡带宽度」判读
 *  （判据见 tests/_label-edge-pixels.mjs 头注）。两条腿互为佐证：
 *   · 计算样式腿：中签 label 的 `mask-image` 必须为 none（断言①）；
 *   · 像素腿：无 mask ⇒ 端点 = 实色硬边，斜坡带 ≤3 设备像素；
 *     有 mask（邻签）⇒ 斜坡带 14–18 设备像素。两档不重叠，判据稳定。
 *  邻签 label 同样量一遍，作为「渐隐仍在」的像素反证。 */
async function pixelProbe(page, name, scheme) {
  const boxes = await page.evaluate(() => {
    const grab = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: +r.left.toFixed(2), y: +r.top.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) };
    };
    const chips = [...document.querySelectorAll('.mf-strip-chip')];
    const on = chips.find((c) => c.classList.contains('on')) || null;
    const shown = chips.filter((c) => parseFloat(getComputedStyle(c).opacity) > 0.01 && c !== on);
    const nb = shown.sort((a, b) => b.getBoundingClientRect().width - a.getBoundingClientRect().width)[0] || null;
    return {
      on: grab(on ? on.querySelector('.mf-strip-label') : null),
      onChip: grab(on),
      neighbor: grab(nb ? nb.querySelector('.mf-strip-label') : null),
    };
  });

  const shoot = async (b, pad = 2) => {
    if (!b || b.w < 4 || b.h < 4) return null;
    return page.screenshot({
      clip: { x: Math.max(0, b.x - pad), y: Math.max(0, b.y - pad), width: b.w + pad * 2, height: b.h + pad * 2 },
    });
  };

  const out = { on: null, neighbor: null };
  const onBuf = await shoot(boxes.on);
  if (onBuf) {
    writeFileSync(join(OUT, `${name}-labelcrop-${scheme}.png`), onBuf);
    out.on = { box: boxes.on, bytes: onBuf.length, ...labelEdgeRamp(onBuf) };
  }
  // 中签**整体特写**（签身 + 两端各留 6px，看圆角/底色/两端硬边是否干净）
  const chipBuf = await shoot(boxes.onChip, 6);
  if (chipBuf) writeFileSync(join(OUT, `${name}-chipcrop-${scheme}.png`), chipBuf);
  const nbBuf = await shoot(boxes.neighbor);
  if (nbBuf) {
    writeFileSync(join(OUT, `${name}-neighborcrop-${scheme}.png`), nbBuf);
    out.neighbor = { box: boxes.neighbor, bytes: nbBuf.length, ...labelEdgeRamp(nbBuf) };
  }
  return out;
}

async function probe(page) {
  return page.evaluate(() => {
    const q = (s) => document.querySelector(s);
    const box = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: +r.left.toFixed(1), y: +r.top.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1), cx: +(r.left + r.width / 2).toFixed(2), cy: +(r.top + r.height / 2).toFixed(2) };
    };
    const strip = q('.mf-strip');
    const closeB = q('.mf-cv-close')?.getBoundingClientRect() || null;
    const stackB = q('.mf-cv-stack')?.getBoundingClientRect() || null;
    const chips = [...document.querySelectorAll('.mf-strip-chip')].map((c) => {
      const cs = getComputedStyle(c);
      const label = c.querySelector('.mf-strip-label');
      const lcs = getComputedStyle(label);
      const before = getComputedStyle(c, '::before');
      return {
        id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
        expanded: c.classList.contains('is-expanded'),
        ...box(c),
        op: +cs.opacity, blur: cs.filter,
        rdx: c.style.getPropertyValue('--rdx').trim(),
        rsc: c.style.getPropertyValue('--rsc').trim(),
        rbl: c.style.getPropertyValue('--rbl').trim(),
        radius: cs.borderRadius, bg: cs.backgroundImage, bgColor: cs.backgroundColor,
        borderTop: cs.borderTopColor, borderBottom: cs.borderBottomColor, borderWidth: cs.borderTopWidth,
        shadow: cs.boxShadow, weight: cs.fontWeight, size: cs.fontSize, family: cs.fontFamily,
        color: cs.color, glass: cs.backdropFilter || cs.webkitBackdropFilter,
        textOverflow: lcs.textOverflow,
        labelMask: lcs.maskImage || lcs.webkitMaskImage,
        labelStyleMaxW: label.style.maxWidth || '',
        labelBox: box(label),
        labelW: +label.getBoundingClientRect().width.toFixed(1),
        labelSW: label.scrollWidth,
        hitW: +parseFloat(before.width).toFixed(1), hitH: +parseFloat(before.height).toFixed(1),
        text: label.textContent, title: c.title,
        ellipsisInText: label.textContent.includes('\u2026'),
      };
    });
    const shown = chips.filter((c) => c.op > 0.01);
    const on = chips.find((c) => c.on);
    const side = shown.filter((c) => !c.on).sort((a, b) => b.w - a.w)[0] || null;
    return {
      strip: strip
        ? { ...box(strip), fade: strip.classList.contains('fade'), mask: getComputedStyle(strip).maskImage }
        : null,
      fabCy: { close: closeB ? +(closeB.top + closeB.height / 2).toFixed(2) : null, stack: stackB ? +(stackB.top + stackB.height / 2).toFixed(2) : null },
      fabEdge: { closeRight: closeB ? +closeB.right.toFixed(1) : null, stackLeft: stackB ? +stackB.left.toFixed(1) : null },
      chips,
      cySpread: shown.length ? +(Math.max(...shown.map((c) => c.cy)) - Math.min(...shown.map((c) => c.cy))).toFixed(3) : null,
      sizeRatio: on && side ? { onW: on.w, sideW: side.w, wRatio: +(on.w / side.w).toFixed(3), hRatio: +(on.h / side.h).toFixed(3) } : null,
      centerMask: on ? on.labelMask : null,
      neighborMasks: shown.filter((c) => !c.on).map((c) => c.labelMask),
      allClip: chips.every((c) => c.textOverflow === 'clip'),
      anyEllipsis: chips.some((c) => c.ellipsisInText),
      minHit: chips.length ? Math.min(...chips.map((c) => Math.min(c.hitW, c.hitH))) : null,
    };
  });
}

async function shot(page, name, scheme) {
  const p = await probe(page);
  p.pixels = await pixelProbe(page, name, scheme);
  writeFileSync(join(OUT, `${name}-${scheme}.json`), JSON.stringify(p, null, 2));
  await page.screenshot({ path: join(OUT, `${name}-${scheme}.png`) });
  // 中签放大特写（b5-center-zoom）：把签身裁切图最近邻放大 4× —— 两端是否残留
  // alpha 渐隐肉眼一眼可判（这是「放大特写」这一帧的实体）。
  if (name === 'b5-center-zoom') {
    const chipCrop = join(OUT, `${name}-chipcrop-${scheme}.png`);
    if (existsSync(chipCrop)) {
      writeFileSync(join(OUT, `${name}-x4-${scheme}.png`), scalePng(readFileSync(chipCrop), 4));
    }
  }
  const on = p.chips.find((c) => c.on);
  console.log(`shot ${name}-${scheme}: chips=${p.chips.length} on=${on ? on.id : '-'} cySpread=${p.cySpread} ratio=${p.sizeRatio ? p.sizeRatio.wRatio : '-'} clip=${p.allClip} ellipsis=${p.anyEllipsis} minHit=${p.minHit} centerMask=${p.centerMask} ramp=${p.pixels.on ? `${p.pixels.on.rampLeft}/${p.pixels.on.rampRight}` : '-'} nbRamp=${p.pixels.neighbor ? `${p.pixels.neighbor.rampLeft}/${p.pixels.neighbor.rampRight}` : '-'}`);
}

const LONG10 = '7451 lugi 命名规约汇编（超长标题档·验零省略号）';

/** 显式激活某一枚签（seed 之后活动卡 = 末张，长标题档必须点名摆到环心）。 */
async function activate(page, id) {
  await page.locator(`.mf-strip-chip[data-tab-id="${id}"]`).click();
  await sleep(560);   // 环位姿过渡 320ms + 收束重算
}

for (const scheme of ['dark', 'light']) {
  // ── 手机 375×812 ────────────────────────────────────────────────
  const { ctx, page } = await makePage(scheme);
  await dockCanvas(page);

  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计')]);
  await shot(page, 'b1-ring-2tabs', scheme);

  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计'), DOC(3, LONG10)]);
  await shot(page, 'b2-ring-3tabs', scheme);

  await seed(page, [
    DOC(1, '环一'), DOC(2, '环二'), DOC(3, '环三'), DOC(4, '环四'), DOC(5, LONG10),
  ]);
  await shot(page, 'b3-ring-5tabs', scheme);

  // 超长标题档（≥10 汉字）：**把超长那张点成中签**，验两端干净 + 整字收束 +
  // 不溢出 / 不硬裁 / 无省略号。
  await seed(page, [
    DOC(1, '7451 lugi 命名规约汇编与落盘纪律（超长标题档 A·≥10 汉字）'),
    DOC(2, '背景任务设计'),
    DOC(3, '单文件画像的现状形态'),
  ]);
  await activate(page, 'cn-1');
  await shot(page, 'b4-long-title', scheme);

  // 中签两端干净的自证：中签短标题（完整显示 ⇒ 一望即知两端是实色硬边）
  await seed(page, [DOC(1, '命名规约'), DOC(2, '背景任务设计'), DOC(3, '单文件画像')]);
  await activate(page, 'cn-1');
  await shot(page, 'b5-center-zoom', scheme);

  // 展开腿（超长名）：点中签 → 展开档能放下更多整字
  await seed(page, [
    DOC(1, '7451 lugi 命名规约汇编与落盘纪律（超长标题档 A·≥10 汉字）'),
    DOC(2, '背景任务设计'),
    DOC(3, '单文件画像的现状形态'),
  ]);
  await activate(page, 'cn-1');
  await page.locator('.mf-strip-chip.on').click();
  await sleep(460);
  await shot(page, 'b6-expanded', scheme);
  await sleep(2600);

  // 邻签渐隐仍在（中签短、邻签超长 ⇒ 邻签右缘 mask 化开；中签两端仍干净）
  await seed(page, [
    DOC(1, '背景任务设计 · 队列与重试（邻签超长标题档·验渐隐仍在）'),
    DOC(2, '命名规约'),
    DOC(3, '单文件画像的现状形态与技术债盘点（另一侧邻签超长）'),
  ]);
  await activate(page, 'cn-2');
  await shot(page, 'b7-neighbor-fade', scheme);

  await ctx.close();
}

// ── 桌面（证明未变）──────────────────────────────────────────────────
for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await makePage(scheme, { width: 1440, height: 900 });
  await sleep(600);
  await seed(page, [DOC(1, '桌面甲'), DOC(2, '桌面乙'), DOC(3, '桌面丙')]);
  const p = await probe(page);
  writeFileSync(join(OUT, `d1-desktop-${scheme}.json`), JSON.stringify(p, null, 2));
  await page.screenshot({ path: join(OUT, `d1-desktop-${scheme}.png`) });
  console.log(`shot d1-desktop-${scheme}: mobile strip present=${p.chips.length > 0}`);
  await ctx.close();
}

// ── 改前同时口对照帧（从上一批 evidence 拷入，便于并排看）─────────────
const BEFORE_MAP = {
  'a1-ring-2tabs': 'b1-ring-2tabs',
  'a2-ring-3tabs': 'b2-ring-3tabs',
  'a3-ring-5tabs': 'b3-ring-5tabs',
  'a6-center-zoom': 'b5-center-zoom',
};
for (const [src, dst] of Object.entries(BEFORE_MAP)) {
  for (const scheme of ['dark', 'light']) {
    const f = join(BEFORE, `${src}-${scheme}.png`);
    if (existsSync(f)) copyFileSync(f, join(OUT, `z0-before-${dst}-${scheme}.png`));
  }
}

// ── 改前/改后**同视口并排**对照图（上 = 改前，下 = 改后，只取顶部 150 CSS px
//    的环带区；中间 6px 白线分隔）。改前帧来自上一批同一 seed 的实拍。
function cropRows(pngBuf, maxH) {
  const { width, height, data } = decodePng(pngBuf);
  const h = Math.min(height, maxH);
  return { width, height: h, data: data.subarray(0, width * h * 4) };
}
function stackPng(topBuf, botBuf, gap = 6) {
  const a = cropRows(topBuf, 300);        // 150 CSS px × dsf 2 = 300 设备像素
  const b = cropRows(botBuf, 300);
  const width = Math.max(a.width, b.width);
  const height = a.height + gap + b.height;
  const out = new Uint8Array(width * height * 4);
  for (let i = 0; i < out.length; i += 4) { out[i] = 255; out[i + 1] = 255; out[i + 2] = 255; out[i + 3] = 255; }
  for (let y = 0; y < a.height; y++) {
    out.set(a.data.subarray(y * a.width * 4, (y + 1) * a.width * 4), y * width * 4);
  }
  for (let y = 0; y < b.height; y++) {
    out.set(b.data.subarray(y * b.width * 4, (y + 1) * b.width * 4), (a.height + gap + y) * width * 4);
  }
  return encodePng(width, height, out);
}
for (const [src, dst] of Object.entries(BEFORE_MAP)) {
  for (const scheme of ['dark', 'light']) {
    const bf = join(OUT, `z0-before-${dst}-${scheme}.png`);
    const af = join(OUT, `${dst}-${scheme}.png`);
    if (!existsSync(bf) || !existsSync(af)) continue;
    writeFileSync(join(OUT, `z1-compare-${dst}-${scheme}.png`),
      stackPng(readFileSync(bf), readFileSync(af)));
  }
}

await browser.close();
console.log(`\nconsole errors: ${consoleErrors.length}`);
if (consoleErrors.length) console.log(consoleErrors.slice(0, 8).join('\n'));
console.log(`DONE → ${OUT}`);
process.exit(consoleErrors.length ? 1 : 0);
