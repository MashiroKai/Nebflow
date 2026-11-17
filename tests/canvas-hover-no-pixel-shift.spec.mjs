// canvas-hover-no-pixel-shift.spec.mjs — 「hover 胶囊不许带着图片一起动」spec 钉
// （作者报障 2026-11-03 21:35 逐字：「我在电脑端hover canvas 胶囊的时候，会带着
//   图片一起动，这个应该不要影响」/ UI 待办 #1475 第七条）
//
// 根因（修前实测）：桌面 hover 标题胶囊 → canvas.js openRing() 给 #canvas-content
//   挂 .capsule-ring-open → split.css 尾块
//   `#canvas-content.capsule-ring-open .canvas-viewer-safe-top { padding-top: 52px }`
//   把 .canvas-image-viewer 的顶垫从基类 16px 抬到 52px（另配 .canvas-viewer-safe-top
//   的 `transition: padding-top 240ms`）⇒ 定高 flex 列里的 .canvas-image-viewport
//   高度 −36px ⇒ 图片 ResizeObserver 重 fit（s/tx/ty 全变）⇒ 图片视觉中心下移并被
//   缩放。**用布局属性（padding）做 hover 反馈**正是作者判定要点①明令禁止的形态。
//
// 设计语言裁定：胶囊环是**真浮层**（#canvas-capsule-ring 挂 body、z-246、真实越出
//   面板顶缘）—— 浮 chrome 不占位、不透传布局（与本批 mobile-canvas-topbar
//   「零底带透传悬浮」同语义）。内容顶垫只允许由**静态档位**决定（基类值 / 手机层
//   --mchrome-clear / md 面常驻垫），绝不允许由 hover 态类驱动。
//
// 断言（全部 boundingBox / computed style 实测，不 mock 布局）：
//   H1 前置：环真开（ring-open + ≥2 环胶囊 + #canvas-content.capsule-ring-open）
//      —— 防「假绿：环根本没开」
//   H2 图片零位移：<img> / .canvas-image-viewport / .canvas-image-viewer 三项
//      x,y,w,h 逐值 ≤0.5px（作者报障本体）
//   H3 查看面顶垫零变化：.canvas-image-viewer padding-top hover 前后相等
//   H4 无布局过渡：.canvas-viewer-safe-top 的 computed transition 不含 padding
//      （duration 0s，或属性白名单里无 padding*）
//   H5 静态钉：split.css 零 `transition: all`（拆分.css face 内全部改显式白名单）
//      + 零「.capsule-ring-open … padding」联动规则
//   H6 md 面回归：常驻顶垫 52px（hover 前后不变）—— 环开不再二次抬高
//   H7 手机层腿：查看面顶垫 = --mchrome-clear（≥52，A9 口径）且 hover 标题不挂
//      .capsule-ring-open、图片零位移
//
// 验红：stash 本批 CSS 改动后 H2/H3/H4/H5 即败（图片 rect 位移 + 240ms padding
//   过渡 + `transition: all` 5 处）。
//
// 运行：node tests/canvas-hover-no-pixel-shift.spec.mjs
// 可选 env：PORT（默认 8261）、SHIFT_SHOTS_DIR（截图/读数目录）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8261);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHIFT_SHOTS_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/hover-layout';
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const readings = {};
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

// 实图（960×600 SVG —— fit 依赖 naturalWidth/Height；1×1 会让 fit 恒 s=1 掩盖位移）
const IMG_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="600">'
  + '<rect width="960" height="600" fill="#5b8def"/><circle cx="300" cy="200" r="90" fill="#ffd66b"/>'
  + '<rect x="80" y="420" width="800" height="120" fill="#1b2436"/></svg>';
const IMG_DISK = '/tmp/nf-shift-fixture/shot.svg';
mkdirSync('/tmp/nf-shift-fixture', { recursive: true });
writeFileSync(IMG_DISK, IMG_SVG);
const MD_DISK = '/tmp/nf-shift-fixture/notes.md';
writeFileSync(MD_DISK, '# SHIFT-MD\n\n' + '正文段落，用于撑出滚动面。\n\n'.repeat(30));

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
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const FILE_CONTENTS = new Map([
  [MD_DISK, { content: readFileSync(MD_DISK, 'utf8'), itemType: 'markdown' }],
]);

function makeServerWsMocks(ctx) {
  return ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
}

async function boot({ mobile = false } = {}) {
  const ctx = await browser.newContext({
    viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 },
    colorScheme: 'dark', hasTouch: mobile, locale: 'zh-CN',
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  makeServerWsMocks(ctx);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  const nfFile = async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: readFileSync(IMG_DISK) });
  };
  await page.route(/\/api\/nf-file\?/, nfFile);
  await page.goto(BASE + '/index.html');
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 20000 });
  await sleep(800);
  return { ctx, page, errors };
}

async function openItem(page, { absPath, itemType, title }) {
  await page.evaluate(({ absPath, itemType, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, absPath, pinned: true },
    }));
  }, { absPath, itemType, title });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 15000, state: 'attached' });
  await sleep(500);
}

async function activateTab(page, id) {
  await page.evaluate((tid) => {
    const tab = [...document.querySelectorAll('.canvas-tab')].find((t) => t.dataset.tabId === tid);
    if (tab) tab.click();
  }, id);
  await sleep(900);
}

/** 同帧几何快照（活动页签的查看面三项 + 顶垫 + 过渡档 + 环态）。 */
const SNAP = `(() => {
  const round = (n) => Math.round(n * 100) / 100;
  const box = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: round(b.x), y: round(b.y), w: round(b.width), h: round(b.height) };
  };
  const pane = document.querySelector('.canvas-tab-pane.active');
  const viewer = pane ? pane.querySelector('.canvas-image-viewer, .canvas-md-scroll') : null;
  const vp = pane ? pane.querySelector('.canvas-image-viewport') : null;
  const img = pane ? pane.querySelector('.canvas-image-viewport img') : null;
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const content = document.getElementById('canvas-content');
  const safe = document.querySelector('.canvas-viewer-safe-top');
  const sc = safe ? getComputedStyle(safe) : null;
  return {
    viewer: box(viewer),
    vp: box(vp),
    img: box(img),
    padTop: viewer ? round(parseFloat(getComputedStyle(viewer).paddingTop)) : null,
    padMd: safe && safe.classList.contains('canvas-md-scroll') ? round(parseFloat(sc.paddingTop)) : null,
    safeTag: safe ? safe.className : null,
    safeTransitionProp: sc ? sc.transitionProperty : null,
    safeTransitionDur: sc ? sc.transitionDuration : null,
    ringOpen: !!hdr && hdr.classList.contains('ring-open'),
    contentClass: content ? String(content.className) : null,
    caps: document.querySelectorAll('#canvas-capsule-ring .cr-capsule').length,
    imgTransform: img ? img.style.transform : null,
  };
})()`;

const snap = (page) => page.evaluate(SNAP);

const boxDelta = (a, b) => {
  if (!a || !b) return 999;
  return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y), Math.abs(a.w - b.w), Math.abs(a.h - b.h));
};
const fmtBox = (b) => (b ? `x${b.x} y${b.y} ${b.w}×${b.h}` : 'null');

async function capsulePoint(page) {
  return page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    if (!hdr) return null;
    const r = hdr.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
}
async function hoverCapsule(page) {
  const p = await capsulePoint(page);
  if (!p) return false;
  await page.mouse.move(p.x, p.y);
  await sleep(620);            // 环展开 0.36s + （修前的）240ms padding 过渡走完
  return true;
}
async function unhover(page) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, Math.min(880, p.y + 380));
  await sleep(500);
}

/** 该查看面是否挂着「布局属性」过渡（作者要点②③：只许合成属性）。
 *  transition-property 含 padding/尺寸/定位 且首个 duration > 0 ⇒ 红。 */
const hasLayoutTransition = (s) => {
  const prop = String(s.safeTransitionProp || '');
  const dur = String(s.safeTransitionDur || '0s').split(',')[0].trim();
  return /padding|width|height|margin|top|left|right|bottom/.test(prop) && !/^0(\.0+)?m?s$/.test(dur);
};

/* ════════ 桌面腿：图片面（作者报障本体）════════ */
{
  const { ctx, page, errors } = await boot();
  await openItem(page, { absPath: MD_DISK, itemType: 'markdown', title: 'notes.md' });
  await openItem(page, { absPath: IMG_DISK, itemType: 'image', title: 'shot.svg' });
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  await activateTab(page, 'file:' + IMG_DISK);
  await page.waitForSelector('.canvas-tab-pane.active .canvas-image-viewport img', { timeout: 15000 });
  await sleep(1200);           // ResizeObserver 收敛 + 图片几何稳定

  const rest = await snap(page);
  ok('H0 前置：图片面就位（img + viewport + viewer 齐备，桌面非手机层）',
    !!rest.img && !!rest.vp && !!rest.viewer && rest.caps === 0,
    `img=${fmtBox(rest.img)} vp=${fmtBox(rest.vp)} caps=${rest.caps}`);
  await page.screenshot({ path: join(SHOTS_DIR, `${process.env.SHIFT_PHASE || 'phase'}-before-hover.png`),
    clip: { x: 0, y: 0, width: 1440, height: 420 } }).catch(() => {});

  const hovered = await hoverCapsule(page);
  const hov = await snap(page);
  ok('H1 前置：hover 真开环（ring-open + ≥2 环胶囊）—— 防假绿',
    hovered && hov.ringOpen && hov.caps >= 2,
    `ringOpen=${hov.ringOpen} caps=${hov.caps} cls=${hov.contentClass}`);
  ok('H1b 祛耦合：环开不再给 #canvas-content 挂 .capsule-ring-open（浮层不占位）',
    !/capsule-ring-open/.test(hov.contentClass || ''),
    `cls="${hov.contentClass}"`);

  const dImg = boxDelta(rest.img, hov.img);
  const dVp = boxDelta(rest.vp, hov.vp);
  const dViewer = boxDelta(rest.viewer, hov.viewer);
  ok('H2 图片零位移（作者报障本体）：<img> 四边 ≤0.5px（hover 前后同帧实测）',
    dImg <= 0.5,
    `Δ=${dImg}px  rest[${fmtBox(rest.img)}] → hover[${fmtBox(hov.img)}] transform=${hov.imgTransform}`);
  ok('H2b 图片容器零位移：.canvas-image-viewport 四边 ≤0.5px',
    dVp <= 0.5, `Δ=${dVp}px  rest[${fmtBox(rest.vp)}] → hover[${fmtBox(hov.vp)}]`);
  ok('H2c 查看面外壳零位移：.canvas-image-viewer 四边 ≤0.5px',
    dViewer <= 0.5, `Δ=${dViewer}px  rest[${fmtBox(rest.viewer)}] → hover[${fmtBox(hov.viewer)}]`);
  ok('H3 查看面顶垫零变化（hover 不许改 padding —— 布局属性归静态档位）',
    rest.padTop === hov.padTop,
    `padTop ${rest.padTop}px → ${hov.padTop}px（基类 16px 档）`);
  ok('H4 .canvas-viewer-safe-top 无布局过渡（transition 属性白名单不含 padding/margin/尺寸）',
    !hasLayoutTransition(rest) && !hasLayoutTransition(hov),
    `prop=${rest.safeTransitionProp} dur=${rest.safeTransitionDur} (${rest.safeTag})`);

  await page.screenshot({ path: join(SHOTS_DIR, `${process.env.SHIFT_PHASE || 'phase'}-ring-open.png`),
    clip: { x: 0, y: 0, width: 1440, height: 420 } }).catch(() => {});

  await unhover(page);
  const back = await snap(page);
  ok('H2d 收环后回归原位（图片 rect 回到 hover 前值 ≤0.5px）',
    boxDelta(rest.img, back.img) <= 0.5 && !back.ringOpen,
    `Δ=${boxDelta(rest.img, back.img)}px ringOpen=${back.ringOpen}`);

  /* ── 静态钉（CSS 文本面）──注释先剥（留痕注释允许提及 `transition: all`，
     判据只看真实声明） */
  const cssRaw = await page.evaluate(async () => (await fetch('/css/split.css')).text());
  const cssText = cssRaw.replace(/\/\*[\s\S]*?\*\//g, '');
  const allTrans = (cssText.match(/transition:\s*all\b/g) || []).length;
  ok('H5 split.css 零 `transition: all`（作者判定要点②：显式属性白名单）',
    allTrans === 0, `remaining=${allTrans}`);
  const ringPad = /\.capsule-ring-open[^{]*\{[^}]*padding/i.test(cssText);
  ok('H5b 零「.capsule-ring-open ⇒ padding」联动规则（环开 = 纯浮层，不占位）',
    !ringPad, ringPad ? 'found padding coupling' : 'clean');
  const safeTransitionBlock = /\.canvas-viewer-safe-top\s*\{[^}]*transition/i.test(cssText);
  ok('H5c .canvas-viewer-safe-top 基类块内零 transition 声明（顶垫恒静态）',
    !safeTransitionBlock, safeTransitionBlock ? 'padding-top transition still declared' : 'clean');

  /* ── md 面回归：常驻垫 = 统一安全区 token，hover 前后不变 ──
     canvas-overlap-fix 批 2026-11-05：原先钉死字面量 52（= --capsule-h 36 + 16），
     与统一安全区 --cv-safe-top（= --cv-head-clear 56）差一档 —— 正文首行贴胶囊
     下沿的档差来源。本批 md 面改消费 token，断言随之改成「等于消费点解析值」，
     比钉死数字更强：token 换尺（手机/窄档）时本断言不会假绿。 */
  await activateTab(page, 'file:' + MD_DISK);
  await sleep(600);
  const mdRest = await snap(page);
  await hoverCapsule(page);
  const mdHov = await snap(page);
  await unhover(page);
  const safeTopPx = await page.evaluate(() => {
    // token 的 computed 值保留 calc() 链 ⇒ 在真实消费点（md 滚动面）取解析值。
    const el = document.createElement('div');
    el.style.paddingTop = 'var(--cv-safe-top)';
    document.body.appendChild(el);
    const v = parseFloat(getComputedStyle(el).paddingTop);
    el.remove();
    return Number.isFinite(v) ? v : null;
  });
  ok('H6 md 面回归：常驻顶垫 = --cv-safe-top 解析值（截图 md 首行让胶囊带语义保留）且 hover 前后相等',
    safeTopPx !== null && mdRest.padMd === safeTopPx && mdHov.padMd === safeTopPx,
    `token=${safeTopPx} rest=${mdRest.padMd} hover=${mdHov.padMd}`);

  ok('H9 桌面腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  readings.desktop = { rest, hover: hov, back };
  await ctx.close();
}

/* ════════ 手机层腿（作者判定要点④：手机端胶囊/标题 hover 同核）════════ */
{
  const { ctx, page, errors } = await boot({ mobile: true });
  await openItem(page, { absPath: IMG_DISK, itemType: 'image', title: 'shot.svg' });
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="canvas"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await sleep(1200);
  const rest = await snap(page);
  ok('H7 手机层前置：图片查看面在场 + 顶垫 = --mchrome-clear（≥52，A9 口径）',
    !!rest.img && rest.padTop !== null && rest.padTop >= 52,
    `padTop=${rest.padTop} cls=${rest.safeTag}`);

  // 大标题退役（ui-unify-panels B 批 2026-11-17）：hover 靶从页名胶囊换到
  // 页壳返回钮（顶铬区唯一常驻件），位移/顶垫不变判据照旧。
  const chromePt = await page.evaluate(() => {
    const el = document.querySelector('.mobile-page[data-page="canvas"] .mobile-back-btn');
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  if (chromePt) { await page.mouse.move(chromePt.x, chromePt.y); await sleep(600); }
  const hov = await snap(page);
  ok('H7b 手机端 hover 标题：不挂 .capsule-ring-open + 图片零位移 + 顶垫不变',
    rest.padTop === hov.padTop && boxDelta(rest.img, hov.img) <= 0.5
    && !/capsule-ring-open/.test(hov.contentClass || ''),
    `padTop ${rest.padTop}→${hov.padTop} Δ=${boxDelta(rest.img, hov.img)}px cls=${hov.contentClass}`);
  ok('H8 手机层腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  readings.mobile = { rest, hover: hov };
  await ctx.close();
}

writeFileSync(join(SHOTS_DIR, `${process.env.SHIFT_PHASE || 'phase'}-readings.json`), JSON.stringify(readings, null, 2));
await browser.close();
server.close();
console.log(`\ncanvas-hover-no-pixel-shift: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
