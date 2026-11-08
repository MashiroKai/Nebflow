// image-stack-expand.spec.mjs — 作者报障回归（2026-11-07）：
// 「为什么现在图片一点展开，就消失了？」
//
// ── 报障复现面与判据来源 ────────────────────────────────────────────────
// 报障截图里只剩一枚「收起」胶囊、图片整组不见 —— 这是叠卡媒体组（Pop 交付卡 /
// 用户多图消息同源组件 `buildMediaStack`）的**展开态**。本 spec 在**真实浏览器 +
// 真实模块图 + 真实 CSS + 真实应用外壳（index.html）**上复现并钉死三条行为：
//
//   C1 桌面亮档 · 三图叠卡：展开 N ⇒ 三行**全部可见且已出图**（每行几何 > 0、
//      img.complete ∧ naturalWidth > 0、行不透明度 1、容器高度覆盖三行）；
//      收起 ⇒ 复原扇形卡脸；**再展开**（展开-收起-展开）⇒ 与首次同结果。
//      （报障形态 = 展开后 expand 列 display:none（被 design-system.css 的
//       .ds-media-stack__expand 压掉）+ 折叠面 inline display:none ⇒ 两面俱隐，
//       容器只剩 94×38 = 胶囊本身。）
//   C2 暗档同判据（亮暗两档都要看得见 —— 报障未限定主题）。
//   C3 手机层（375×812 视口 ⇒ body.mobile-layer 真实激活）：同一条展开/收起链，
//      并断言展开行不横向溢出视口。**触屏语义不在此测**（本 spec 只用程序化
//      click；真触屏腿归手机章矩阵），此处只钉「手机档几何下展开后看得见」。
//   C4 单张图（平铺面）：无「展开」钮；图可见；整面点击派发 Canvas 打开单点
//      —— 单张腿不得被多张腿的修法拖坏（Canvas 真渲染腿见 C5）。
//   C5 Canvas 内图片不回归：`workspace-open-item` 真开 Canvas 图片页签，图可见
//      （.canvas-image-viewport img 有盒、有 naturalWidth）—— 本次修复改的是
//      design-system.css 的**特异性**（其全部规则此前被误挂进未闭合的 `:root`
//      嵌套块），该文件同时供 Canvas 面消费，故必须同批守 Canvas 腿。
//
// 静态度量（红/绿对照）：
//   node node_modules/@playwright/test/cli.js test tests/image-stack-expand.spec.mjs
//   改前树对照（验红实证）：CHATFACE_WEB_ROOT=<改前 web 树> 同上命令。
// 截图落盘：IMG_STACK_SHOT_DIR=<绝对目录>（缺省不落盘，CI 不留垃圾）。

import { test, expect } from '@playwright/test';
import zlib from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { bootChat, WEB } from './_chat-face-harness.mjs';

/* ── 夹具字节：零依赖生成纯色 PNG（不联网、不落盘、每张比例可指定） ──────── */

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** Solid-colour PNG bytes: w×h, one flat RGB fill (real pixels ⇒ naturalWidth>0). */
export function png(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;      // bit depth
  ihdr[9] = 2;      // colour type: truecolour
  const row = Buffer.alloc(1 + w * 3);
  for (let x = 0; x < w; x++) {
    row[1 + x * 3] = r;
    row[2 + x * 3] = g;
    row[3 + x * 3] = b;
  }
  const raw = Buffer.concat(Array.from({ length: h }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const dataUri = (w, h, rgb) => 'data:image/png;base64,' + png(w, h, rgb).toString('base64');

/** 三张异比例图（4:1 / 1:4 / 4:3）—— 与 pop-stack-fan 同一组比例，且刻意大于
 *  展开档的 220 宽上限（fitLayer 不放大：真链全长图缩小到 220，行高各不相同）。 */
function threeImages() {
  return [
    { kind: 'image', name: 'wide.png', path: '/tmp/nf-expand-wide.png', src: dataUri(800, 200, [60, 170, 120]) },
    { kind: 'image', name: 'tall.png', path: '/tmp/nf-expand-tall.png', src: dataUri(200, 800, [40, 110, 190]) },
    { kind: 'image', name: 'mid.png', path: '/tmp/nf-expand-mid.png', src: dataUri(400, 300, [190, 120, 50]) },
  ];
}

/** 真实载荷形态（AgentCore → frontendContent 的逐字串，live/回放两腿共用）。 */
function popPayload(items) {
  return '___POP_JSON___' + JSON.stringify({
    items,
    fileRefs: { ok: items.length, failed: 0 },
    warnings: [],
  });
}

/** 历史帧：一条助手文本 + 一条工具结果（Pop 载荷）—— 走 persistence 的**回放腿**
 *  （真历史恢复路径），不是直接调组件。 */
function historyWithPop(items) {
  const now = Date.now();
  return [
    { type: 'ai', text: '图已经给你了，点「展开」看全部。', timestamp: now - 9000 },
    { type: 'tool', label: 'Pop', summary: `交付 ${items.length} 个文件`, content: popPayload(items), timestamp: now - 8000 },
  ];
}

/* ── 探针 ───────────────────────────────────────────────────────────────── */

/** 叠卡/展开两态的几何与像素级可见性读数（全部来自真实布局，不读 class 猜）。 */
function probeStack(page, scope = '#chat') {
  return page.evaluate((scope) => {
    const root = document.querySelector(scope);
    const area = root && root.querySelector('.pop-stack-area');
    if (!area) return null;
    const box = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return {
        w: +b.width.toFixed(1), h: +b.height.toFixed(1),
        left: +b.left.toFixed(1), top: +b.top.toFixed(1),
        display: s.display, opacity: s.opacity, visibility: s.visibility,
      };
    };
    const ex = area.querySelector('.pop-expand-view');
    const rows = ex ? [...ex.querySelectorAll('.media-layer')] : [];
    return {
      pill: area.querySelector('.pill').textContent,
      area: box(area),
      collapsed: box(area.querySelector('.pop-stack-view')),
      expandClass: ex ? ex.className : '',
      expand: box(ex),
      rows: rows.map((r) => ({
        box: box(r),
        ratio: r.style.aspectRatio,
        img: (() => {
          const im = r.querySelector('img');
          if (!im) return null;
          return { complete: im.complete, nw: im.naturalWidth, nh: im.naturalHeight, box: box(im) };
        })(),
      })),
    };
  }, scope);
}

/** 展开态必须「看得见」的硬判据（报障态在每条上都是反的）。 */
function expectExpandedVisible(st, label) {
  expect(st, `${label}: stack area present`).toBeTruthy();
  expect(st.expand.display, `${label}: 展开列 display`).toBe('flex');
  expect(st.rows.length, `${label}: 每张一行`).toBe(3);
  expect(st.area.h, `${label}: 容器高度须容下三行（报障态 = 40/38）`).toBeGreaterThan(400);
  for (const [i, r] of st.rows.entries()) {
    expect(r.box.w, `${label}·行${i}: 行宽`).toBeGreaterThan(100);
    expect(r.box.h, `${label}·行${i}: 行高`).toBeGreaterThan(40);
    expect(r.box.display, `${label}·行${i}: display`).not.toBe('none');
    expect(Number(r.box.opacity), `${label}·行${i}: 不透明度（fadeIn 已落定）`).toBeGreaterThan(0.95);
    expect(r.img && r.img.complete, `${label}·行${i}: 字节已到达`).toBe(true);
    expect(r.img && r.img.nw, `${label}·行${i}: naturalWidth`).toBeGreaterThan(0);
    expect(r.img && r.img.box.w, `${label}·行${i}: 图有可见盒`).toBeGreaterThan(100);
    expect(r.img && r.img.box.h, `${label}·行${i}: 图有可见盒`).toBeGreaterThan(20);
  }
}

async function shot(page, name) {
  const dir = process.env.IMG_STACK_SHOT_DIR;
  if (!dir) return;
  await page.screenshot({ path: `${dir}/${name}.png`, fullPage: false });
}

/** 读数落盘（证据自描述）：IMG_STACK_SHOT_DIR 在场时每次读数即刻写一份
 *  `<label>.json`（红腿也会写 —— 红腿的读数正是「消失」的定量证据）。
 *  逐条落盘的唯一理由：Playwright 在测试失败后会重启 worker，模块态（聚合数组）
 *  会丢，只有磁盘上的读数活得过失败。 */
function record(label, state) {
  const dir = process.env.IMG_STACK_SHOT_DIR;
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  writeFileSync(`${dir}/reading-${label}.json`, JSON.stringify(state, null, 1));
}

/** 展开钮的点击（真实鼠标路径：命中胶囊的可点区中央）。 */
async function clickPill(page) {
  const pill = page.locator('#chat .pop-stack-area .pill').first();
  await pill.click();
}

/* ── C1 桌面亮档：展开 / 收起 / 再展开 ──────────────────────────────────── */

test.describe('C1 桌面亮档 · 三图叠卡展开后图片必须可见（报障主腿）', () => {
  test('展开 → 三行出图；收起 → 复原；再展开 → 同结果', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootChat(page, { history: historyWithPop(threeImages()) });
    await page.waitForSelector('#chat .pop-stack-area .pill', { timeout: 20000 });
    await expect(page.locator('#chat .pop-stack-area .pill')).toHaveText(/展开\s*3/);
    await page.waitForTimeout(400); // 卡脸字节与取景腿落地

    const collapsed = await probeStack(page);
    record('c1-collapsed', collapsed);
    expect(collapsed.collapsed.h, '折叠卡脸 = 220×280 固定脸').toBeGreaterThan(270);
    expect(collapsed.expand.display, '折叠态展开列不占位').toBe('none');
    await shot(page, 'c1-1-collapsed');

    await clickPill(page);
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(700); // fadeIn 0.25s + 高度过渡 460ms 落定
    await shot(page, 'c1-2-expanded');   // 先取证再断言：红腿也要留下现场
    const expanded = await probeStack(page);
    record('c1-expanded', expanded);
    expectExpandedVisible(expanded, '首次展开');
    expect(expanded.pill).toContain('收起');
    await shot(page, 'c1-2-expanded');

    await clickPill(page); // 收起
    await page.waitForFunction(() => !document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(700);
    const restacked = await probeStack(page);
    expect(restacked.pill).toContain('展开');
    expect(restacked.collapsed.h, '收起后扇形卡脸复原').toBeGreaterThan(270);
    expect(restacked.expand.display, '收起后展开列重新占位 0').toBe('none');
    await shot(page, 'c1-3-collapsed-again');

    await clickPill(page); // 再展开
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(700);
    const expandedAgain = await probeStack(page);
    record('c1-expanded-again', expandedAgain);
    expectExpandedVisible(expandedAgain, '二次展开');
    await shot(page, 'c1-4-expanded-again');

    await ctx.close();
  });
});

/* ── C2 暗档 ────────────────────────────────────────────────────────────── */

test.describe('C2 暗档 · 同判据', () => {
  test('暗档展开后三行同样可见', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
    const page = await ctx.newPage();
    await bootChat(page, { history: historyWithPop(threeImages()) });
    await page.waitForSelector('#chat .pop-stack-area .pill', { timeout: 20000 });
    await page.waitForTimeout(400);
    await clickPill(page);
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(700);
    const darkState = await probeStack(page);
    record('c2-dark-expanded', darkState);
    await shot(page, 'c2-1-dark-expanded');
    expectExpandedVisible(darkState, '暗档展开');
    await ctx.close();
  });
});

/* ── C3 手机层 ──────────────────────────────────────────────────────────── */

test.describe('C3 手机层 375×812 · 同一条展开链', () => {
  test('手机档展开后图片可见且不横向溢出', async ({ browser }) => {
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 },
      isMobile: true,
      hasTouch: true,
      deviceScaleFactor: 2,
      colorScheme: 'light',
    });
    const page = await ctx.newPage();
    await bootChat(page, { history: historyWithPop(threeImages()) });
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.waitForSelector('#chat .pop-stack-area .pill', { timeout: 20000 });
    await page.waitForTimeout(600);

    await clickPill(page);
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(700);
    const st = await probeStack(page);
    record('c3-mobile-expanded', st);
    await shot(page, 'c3-1-mobile-expanded');
    expectExpandedVisible(st, '手机层展开');
    const vw = page.viewportSize().width;
    for (const [i, r] of st.rows.entries()) {
      expect(r.box.left, `手机层·行${i}: 左缘不越界`).toBeGreaterThanOrEqual(0);
      expect(r.box.left + r.box.w, `手机层·行${i}: 右缘不越视口`).toBeLessThanOrEqual(vw + 1);
    }
    await ctx.close();
  });
});

/* ── C4 单张图：平铺面（多张腿的修法不得拖坏单张腿） ─────────────────────── */

test.describe('C4 单张图 · 平铺面', () => {
  test('无展开钮；单张图可见；整面点击 = Canvas 打开单点（事件契约）', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    const single = [threeImages()[2]];
    await bootChat(page, { history: historyWithPop(single) });
    await page.waitForSelector('#chat .pop-media-flat img', { timeout: 20000 });
    await expect(page.locator('#chat .pop-stack-area .pill')).toHaveCount(0);

    const flat = await page.evaluate(() => {
      const im = document.querySelector('#chat .pop-media-flat img');
      const b = im.getBoundingClientRect();
      const s = getComputedStyle(im);
      return { w: +b.width.toFixed(1), h: +b.height.toFixed(1), display: s.display, nw: im.naturalWidth, complete: im.complete };
    });
    record('c4-single-flat', flat);
    expect(flat.complete && flat.nw > 0, '单张图字节已到达').toBe(true);
    expect(flat.w, '单张图有可见盒').toBeGreaterThan(50);
    expect(flat.h, '单张图有可见盒').toBeGreaterThan(20);
    await shot(page, 'c4-1-single-flat');

    // 整面点击 = Canvas 打开单点（三修 ② 语义，`openPopArtifact` 派发面）。
    // 只钉事件契约不钉 Canvas 渲染：本夹具不回 `pop.readFile`，空 itemType 的
    // 二进制腿要靠那条 WS 往返才能落页签（真渲染腿由 C5 用显式 itemType 覆盖）。
    await page.evaluate(() => {
      window.__openEvents = [];
      window.addEventListener('workspace-open-item', (e) => window.__openEvents.push(e.detail));
    });
    await page.click('#chat .pop-media-flat img');
    await page.waitForFunction(() => (window.__openEvents || []).length > 0, null, { timeout: 10000 });
    const opened = await page.evaluate(() => window.__openEvents[0]);
    expect(opened.absPath, '打开的是该张的绝对路径').toBe('/tmp/nf-expand-mid.png');
    expect(opened.title, '标题 = 文件名').toBe('mid.png');
    record('c4-open-event', opened);
    await ctx.close();
  });
});

/* ── C5 Canvas 内图片不回归 ─────────────────────────────────────────────── */

test.describe('C5 Canvas 图片面不回归', () => {
  test('Canvas 打开图片页签后图可见', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootChat(page);

    // 字节腿：本 spec 的 route 后注册 ⇒ 优先生效（覆盖 harness 的通用 /api/ 兜底）。
    const canvasPng = png(320, 240, [90, 140, 200]);
    await page.route('**/api/nf-ticket', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ tickets: {} }),
    }));
    await page.route('**/api/nf-file**', (route) => route.fulfill({
      status: 200, contentType: 'image/png', body: canvasPng,
    }));

    await page.evaluate(() => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'nf-expand-canvas.png',
          itemType: 'image',
          title: 'nf-expand-canvas.png',
          absPath: '/tmp/nf-expand-canvas.png',
          path: '/tmp/nf-expand-canvas.png',
          size: 4096,
        },
      }));
    });
    await page.waitForSelector('.canvas-image-viewport img', { timeout: 30000 });
    await page.waitForFunction(() => {
      const im = document.querySelector('.canvas-image-viewport img');
      return im && im.complete && im.naturalWidth > 0;
    }, null, { timeout: 30000 });

    const cv = await page.evaluate(() => {
      const im = document.querySelector('.canvas-image-viewport img');
      const b = im.getBoundingClientRect();
      const vp = document.querySelector('.canvas-image-viewport');
      const vb = vp.getBoundingClientRect();
      return {
        img: { w: +b.width.toFixed(1), h: +b.height.toFixed(1), nw: im.naturalWidth },
        viewport: { w: +vb.width.toFixed(1), h: +vb.height.toFixed(1) },
        paneDisplay: getComputedStyle(document.querySelector('.canvas-tab-pane') || document.body).display,
      };
    });
    expect(cv.img.nw, 'Canvas 图字节到达').toBe(320);
    expect(cv.img.w, 'Canvas 图有可见盒').toBeGreaterThan(50);
    expect(cv.img.h, 'Canvas 图有可见盒').toBeGreaterThan(30);
    expect(cv.viewport.w, 'Canvas 图片视口在场').toBeGreaterThan(200);
    await shot(page, 'c5-1-canvas-image');
    await ctx.close();
  });
});

/* ── 自检：本 spec 的被测树可打印（红/绿对照靠 CHATFACE_WEB_ROOT） ────────── */

test('harness self-check: 被测 web 树存在', async () => {
  expect(WEB).toContain('web');
});
