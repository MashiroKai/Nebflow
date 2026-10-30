// pdf-lazy-render.spec.mjs — 阅读器 P2 大文件批（设计件 §5）验收 spec。
//
// 锚：docs/design/20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN.md
// §五 PDF 大文件（闸拆分 §5.1 / 懒渲染 §5.2 / 护栏 §5.3）+ 验收表 A7/A8。
//
// 分站（被测 = **真实模块链**：viewers/pdf.js 全文 + vendored pdf.js + 真 PDF 字节，
// 零 mock 替身进被测链）：
//   站1 A7 懒渲染静态：100 页灰底 PDF（blob 腿）⇒ 全部占位 style 预占（防跳变）；
//       渲染静止后 带 width 属性（= 有位图）canvas ∈ [1,6]（护栏①）；远端页无位图；
//       视口页中心像素 = 灰底 235（非空白渲）。
//   站2 滚动回收：滚到底 ⇒ 底部页渲染、第 1 页释放（width 属性撤除 = A7 离屏判据）、
//       静止后位图数仍 ≤6。
//   站3 缩放只重渲可见页（§5.2-4）：ctrl+wheel 放大 ⇒ 静止后可见页 renderedScale =
//       新档位，位图数 ≤6，离屏页（末页）仍无位图。
//   站4 护栏源码钉（§5.3 代码审面）：pdf.js 三常量（±2 屏/并发≤2/常驻≤6）+
//       IntersectionObserver/releasePage 撤 width + page.cleanup；attachmentPreview.js
//       闸镜像拆分（MAX_TEXT_BYTES 100MB 不动 / MAX_VIEWER_BYTES = 1,073,741,824 双
//       BLOB 腿消费 / MAX_INLINE_IMAGE_BYTES 刻意解耦不动）；Scala 侧
//       TextStreamSearch.MaxViewerFileBytes = AttachContract.MaxFileBytes（引用非字面量）
//       + popReadGateLimit + WsFileOpsHandlers 闸前二值判定。
//   站5 大文件实跑（A8/护栏③④）：~115MB 稀疏 50 页 PDF（每页 2.3MB Flate 图，Range
//       静态服务）⇒ 首屏静止 ≤3s（设计 §5.3-③ 的 QA 预算）；全程每静止点位图 ≤6；
//       滚完全卷 JS 堆增长 ≤ 取数缓存地板（fileBytes + 80MB）；**二遍滚**堆增量
//       ≤32MB（取数缓存已暖 ⇒ 再涨 = 渲染侧泄漏，「内存不涨」的实证判据）。
//
// harness 纪律（frontend-verification skill §1/§13）：
//   · /sw.js 静态 404（「引擎未重启」形态，防 SW 活体化绕过 mock——nffile-three-tier
//     批注同因）；
//   · 断言与文案/i18n 解耦：全部走 data-*/width 属性/content 属性这类 DOM 契约；
//   · 收尾 browser.close() 进 finally，server.close() 显式调用，进程零残留。
//
// 运行：node tests/pdf-lazy-render.spec.mjs

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { deflateSync } from 'node:zlib';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const SCALA_DIR = resolve(HERE, '../src/main/scala/nebflow/gateway');
const PORT = Number(process.env.PORT || 8179);
const BASE = `http://127.0.0.1:${PORT}`;
const FIXTURE_DIR = '/tmp/pdf-lazy-render-p2';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 真实 PDF 夹具生成（零依赖，手写 xref，偏移精确）─────────────────────────
//
// buildPdf(pages, img) — img = { w, h, raw(Buffer) } 或 null（灰底页）。
// 每 img 页：content 流画 /Im0 + Image XObject（FlateDecode DeviceRGB）。
const enc = new TextEncoder();
function buildPdf(pageCount, img) {
  const parts = [];
  let len = 0;
  const offsets = new Map();
  const w = (s) => { const b = typeof s === 'string' ? enc.encode(s) : s; parts.push(b); len += b.length; };
  const obj = (n, body) => { offsets.set(n, len); w(`${n} 0 obj\n`); body(); w(`\nendobj\n`); };

  // %PDF 魔数头（缺它 = pdf.js「Invalid Root reference」——本 spec 首跑实证）+
  // 二进制注释行（含 >0x7F 字节，强制按二进制传输语义）。
  w('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');

  const useImg = !!img;
  const plan = [];
  let next = 3;
  for (let i = 0; i < pageCount; i++) {
    plan.push({ content: next, imgObj: useImg ? next + 1 : 0, page: next + (useImg ? 2 : 1) });
    next += useImg ? 3 : 2;
  }
  const count = next - 1;

  obj(1, () => w('<< /Type /Catalog /Pages 2 0 R >>'));
  obj(2, () => w(`<< /Type /Pages /Kids [${plan.map(p => `${p.page} 0 R`).join(' ')}] /Count ${pageCount} >>`));

  let comp = null;
  if (useImg) comp = deflateSync(img.raw);

  for (const o of plan) {
    obj(o.content, () => {
      const stream = useImg ? `q ${img.w} 0 0 ${img.h} 0 0 cm /Im0 Do Q` : '0.92 0.92 0.92 rg 0 0 612 792 re f';
      w(`<< /Length ${enc.encode(stream).length} >>\nstream\n${stream}\nendstream`);
    });
    if (useImg) {
      obj(o.imgObj, () => {
        w(`<< /Type /XObject /Subtype /Image /Width ${img.w} /Height ${img.h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${comp.length} >>\nstream\n`);
        w(comp);
        w('\nendstream');
      });
    }
    obj(o.page, () => w(useImg
      ? `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${img.w} ${img.h}] /Resources << /XObject << /Im0 ${o.imgObj} 0 R >> >> /Contents ${o.content} 0 R >>`
      : `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents ${o.content} 0 R >>`));
  }

  const xrefPos = len;
  let x = `xref\n0 ${count + 1}\n0000000000 65535 f \n`;
  for (let i = 1; i <= count; i++) x += `${String(offsets.get(i)).padStart(10, '0')} 00000 n \n`;
  w(x);
  w(`trailer\n<< /Size ${count + 1} /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF`);
  return Buffer.concat(parts);
}

// 小夹具：100 页灰底（站1-3）。大夹具：50 页 × 2.3MB 图 ≈ 115MB（站5）。
mkdirSync(FIXTURE_DIR, { recursive: true });
const SMALL_PDF = join(FIXTURE_DIR, 'small-100p.pdf');
const BIG_PDF = join(FIXTURE_DIR, 'big-50p.pdf');

if (!existsSync(SMALL_PDF)) writeFileSync(SMALL_PDF, buildPdf(100, null));

// xorshift32：LCG 低字节周期只有 256（本 spec 实证：raw 退化成 256B 重复
// 图案 ⇒ deflate 压到 ~0 ⇒ 夹具只有 484KB）——取高字节保真随机。
function prng(seed) { let s = seed >>> 0 || 1; return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s; }; }
const BIG_IMG = { w: 900, h: 850 };
const BIG_IMG_RAW_LEN = BIG_IMG.w * BIG_IMG.h * 3;
if (!existsSync(BIG_PDF) || statSync(BIG_PDF).size < 100 * 1024 * 1024) {
  const rand = prng(42);
  const raw = Buffer.allocUnsafe(BIG_IMG_RAW_LEN);
  for (let i = 0; i < raw.length; i++) raw[i] = (rand() >>> 24) & 0xff;
  writeFileSync(BIG_PDF, buildPdf(50, { ...BIG_IMG, raw }));
}
const BIG_BYTES = statSync(BIG_PDF).size;

// ── 静态服务（真实 web 目录 + /fixture Range 支持）──────────────────────────
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
  '.pdf': 'application/pdf', '.wasm': 'application/wasm', '.svg': 'image/svg+xml',
};
function serveFixture(p, req, res) {
  const name = p.slice('/fixture/'.length);
  const file = join(FIXTURE_DIR, name);
  if (!file.startsWith(FIXTURE_DIR)) { res.writeHead(403); res.end(); return; }
  let buf;
  try { buf = readFileSync(file); } catch { res.writeHead(404); res.end('nf'); return; }
  const total = buf.length;
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  const type = MIME[name.slice(name.lastIndexOf('.'))] || 'application/octet-stream';
  if (range) {
    let start = range[1] ? Number(range[1]) : 0;
    let end = range[2] ? Number(range[2]) : total - 1;
    if (range[1] === '' && range[2]) { start = Math.max(0, total - Number(range[2])); end = total - 1; }
    if (start > end || start >= total) {
      res.writeHead(416, { 'Content-Range': `bytes */${total}` }); res.end(); return;
    }
    end = Math.min(end, total - 1);
    res.writeHead(206, {
      'Content-Type': type, 'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${total}`, 'Accept-Ranges': 'bytes',
    });
    res.end(buf.subarray(start, end + 1));
    return;
  }
  res.writeHead(200, { 'Content-Type': type, 'Content-Length': total, 'Accept-Ranges': 'bytes' });
  res.end(buf);
}

const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  if (p === '/sw.js') { res.writeHead(404); res.end('nf'); return; }
  if (p.startsWith('/fixture/')) { serveFixture(p, req, res); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const report = {};
let fail = 0;

// ── 页面脚手架（nffile-three-tier 同款 boot 形态）───────────────────────────
async function bootPage() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: JSON.stringify({ features: {} }) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  return { browser, page, errors };
}

/** 在真实页面里挂 PDF viewer（走真实 fileViewers 注册表，objectUrl 直透 pdf.js）。 */
async function mountPdf(page, objectUrl) {
  return page.evaluate(async (url) => {
    const { renderFile } = await import('/js/fileViewers.js');
    document.getElementById('pdf-test-pane')?.remove();
    const pane = document.createElement('div');
    pane.id = 'pdf-test-pane';
    pane.style.cssText = 'position:absolute;left:0;top:0;width:840px;height:1000px;z-index:99999;background:#222;';
    document.body.appendChild(pane);
    const t0 = performance.now();
    await renderFile(pane, { itemType: 'pdf', content: '', absPath: null, objectUrl: url, fileName: 't.pdf', size: 0 });
    return performance.now() - t0; // mount 返回 ≠ 渲染静止，静止由 settle 谓词判
  }, objectUrl);
}

/** 静止谓词：存在位图 && 所有带位图的 canvas 都已完成（renderedScale 落标）。 */
const SETTLE_JS = `(() => {
  const cs = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')];
  if (cs.length === 0) return false;
  const withW = cs.filter(c => c.hasAttribute('width'));
  return withW.length > 0 && withW.every(c => c.dataset.renderedScale !== undefined);
})()`;

const bitmapCount = (page) => page.evaluate(
  () => document.querySelectorAll('#pdf-test-pane canvas.pdf-page[width]').length
);
const renderedScales = (page) => page.evaluate(
  () => [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')]
    .filter(c => c.hasAttribute('width'))
    .map(c => ({ page: c.dataset.page, scale: c.dataset.renderedScale }))
);
const scrollPages = (page, top) => page.evaluate((t) => {
  const el = document.querySelector('#pdf-test-pane .pdf-pages');
  el.scrollTop = t === 'bottom' ? el.scrollHeight : t;
  return el.scrollHeight;
}, top);
const heapBytes = (page) => page.evaluate(() => performance.memory.usedJSHeapSize);

async function waitSettled(page, timeout = 30000) {
  try {
    await page.waitForFunction(SETTLE_JS, undefined, { timeout });
  } catch (e) {
    const diag = await page.evaluate(() => {
      const pane = document.getElementById('pdf-test-pane');
      if (!pane) return { pane: null };
      const cs = [...pane.querySelectorAll('canvas.pdf-page')];
      return {
        paneHtml: pane.innerHTML.slice(0, 300),
        total: cs.length,
        withWidth: cs.filter(c => c.hasAttribute('width')).length,
        sample: cs.slice(0, 8).map(c => ({ p: c.dataset.page, w: c.getAttribute('width'), rs: c.dataset.renderedScale, sh: c.style.height })),
        loading: !!pane.querySelector('.pdf-loading'),
        error: pane.querySelector('.canvas-error')?.textContent?.slice(0, 200) || null,
        pdfjsLib: typeof window.pdfjsLib,
        workerSrc: window.pdfjsLib?.GlobalWorkerOptions?.workerSrc || null,
      };
    }).catch(() => 'eval-failed');
    console.error('SETTLE-TIMEOUT DIAG:', JSON.stringify(diag, null, 2));
    throw e;
  }
}

/** 变更感知等待（防「静止谓词已真 ⇒ 立即返回」的状态盲等）：等指定页真的渲出位图。 */
async function waitForPageRendered(page, pageNum, timeout = 30000) {
  await page.waitForFunction(`(() => {
    const c = document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="${pageNum}"]');
    return c && c.hasAttribute('width') && c.dataset.renderedScale !== undefined;
  })()`, undefined, { timeout });
}

/** 变更感知等待：所有带位图的页都按「非旧 scale」重渲完成（缩放站用）。 */
async function waitForRescale(page, oldScale, timeout = 30000) {
  await page.waitForFunction((os) => {
    const withW = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')]
      .filter(c => c.hasAttribute('width'));
    return withW.length > 0 && withW.every(c => c.dataset.renderedScale !== os);
  }, oldScale, { timeout });
}

// ── 站1+站2+站3：懒渲染 / 滚动回收 / 缩放（小夹具 blob 腿）─────────────────
{
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  const { browser, page, errors } = await bootPage();
  try {
    // blob 腿：在页面里 fetch fixture 造 blob URL（与 attachmentPreview objectUrl 腿同形）。
    const blobUrl = await page.evaluate(async () => {
      const res = await fetch('/fixture/small-100p.pdf');
      const b = await res.blob();
      return URL.createObjectURL(b);
    });
    await mountPdf(page, blobUrl);

    // 站1：占位预占 + 护栏① + 远端无位图 + 真渲染。
    await waitSettled(page);
    const total = await page.evaluate(() => document.querySelectorAll('#pdf-test-pane canvas.pdf-page').length);
    const styled = await page.evaluate(() =>
      [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')]
        .filter(c => c.style.height && c.style.width).length);
    t('1a 占位页数 = 100（页尺寸预占：全部 canvas style 尺寸在位）', total === 100 && styled === 100, `total=${total} styled=${styled}`);
    const bc1 = await bitmapCount(page);
    t('1b 静止后位图数 ∈ [1,6]（护栏①/A7）', bc1 >= 1 && bc1 <= 6, `count=${bc1}`);
    const far1 = await page.evaluate(() => !document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="100"]').hasAttribute('width'));
    t('1c 远端页（第 100 页）无位图（A7 离屏判据）', far1);
    const page1Rendered = await page.evaluate(() => {
      const c = document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="1"]');
      return c && c.hasAttribute('width') && c.dataset.renderedScale !== undefined;
    });
    t('1d 视口首屏已渲（第 1 页有位图）', page1Rendered);
    const px = await page.evaluate(() => {
      const c = document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="1"]');
      if (!c || !c.hasAttribute('width')) return null;
      const d = c.getContext('2d').getImageData(Math.floor(c.width / 2), Math.floor(c.height / 2), 1, 1).data;
      return [d[0], d[1], d[2]];
    });
    t('1e 首屏像素 = 灰底 235（真渲非空白）', !!px && Math.abs(px[0] - 235) <= 3 && Math.abs(px[1] - 235) <= 3, JSON.stringify(px));

    // 站2：滚到底 ⇒ 底部渲染 + 第 1 页释放 + 位图仍 ≤6。
    // （先等「末页真的渲出」这一**新状态**，再等静止——静止谓词对已真状态
    //   会立即返回，直接等 = 状态盲等。）
    await scrollPages(page, 'bottom');
    await waitForPageRendered(page, 100);
    await waitSettled(page);
    const bc2 = await bitmapCount(page);
    t('2a 滚动后位图数 ≤6（护栏①滚动态）', bc2 >= 1 && bc2 <= 6, `count=${bc2}`);
    const bottomRendered = await page.evaluate(() => {
      const cs = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')];
      const last = cs[cs.length - 1];
      return last.hasAttribute('width') && last.dataset.renderedScale !== undefined;
    });
    t('2b 视口所在末页已渲', bottomRendered);
    const firstReleased = await page.evaluate(() => !document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="1"]').hasAttribute('width'));
    t('2c 离屏第 1 页已释放（width 属性撤除）', firstReleased);

    // 站3：ctrl+wheel 放大 ⇒ 只重渲可见页（§5.2-4）。
    const beforeScales = await renderedScales(page);
    const oldScale = beforeScales.length ? beforeScales[0].scale : null;
    await page.evaluate(() => {
      const pages = document.querySelector('#pdf-test-pane .pdf-pages');
      const r = pages.getBoundingClientRect();
      pages.dispatchEvent(new WheelEvent('wheel', {
        bubbles: true, cancelable: true, ctrlKey: true, deltaY: -240,
        clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
      }));
    });
    await waitForRescale(page, oldScale);
    await waitSettled(page);
    const pct = await page.evaluate(() => Number(document.querySelector('#pdf-test-pane .canvas-zoom-pct').textContent.replace('%', '')));
    // 3b 用谓词等待而非点读：缩放 pivot 补偿滚屏 ⇒ IO 再准入，渲染会瞬时释放
    // 重渲（churn）；「存在 ≥1 页完成重渲且全部完成页都按新 scale」一旦出现即成立。
    const rescaleOk = await page.waitForFunction((p) => {
      const done = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')]
        .filter(c => c.hasAttribute('width') && c.dataset.renderedScale !== undefined);
      return done.length >= 1 && done.every(c => Math.abs(Number(c.dataset.renderedScale) * 100 - p) <= 2);
    }, pct, { timeout: 15000 }).then(() => true).catch(() => false);
    // 单原子读（count + scales 同帧取，防两 evaluate 之间新渲染进站造成 TOCTOU）。
    const afterState = await page.evaluate(() => {
      const withW = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')]
        .filter(c => c.hasAttribute('width'));
      return {
        count: withW.length,
        done: withW.filter(c => c.dataset.renderedScale !== undefined)
          .map(c => ({ page: c.dataset.page, scale: c.dataset.renderedScale })),
      };
    });
    const bc3 = afterState.count;
    t('3a 缩放档位已变（zoom bar >100%）', pct > 100, `pct=${pct}`);
    t('3b 已完成重渲的页全部按新 scale（renderedScale ×100 ≈ 档位）', rescaleOk,
      JSON.stringify(afterState.done.slice(0, 3)));
    t('3c 重渲后位图数仍 ≤6', bc3 >= 1 && bc3 <= 6, `count=${bc3}`);
    t('3d 缩放前后位图集不相等（确实发生了重渲）', JSON.stringify(beforeScales) !== JSON.stringify(afterState.done));
    const farStillBare = await page.evaluate(() => !document.querySelector('#pdf-test-pane canvas.pdf-page[data-page="1"]').hasAttribute('width'));
    t('3e 离屏页缩放后仍无位图（不重渲离屏）', farStillBare);

    const fatal = errors.filter(e => !/favicon|net::|WebSocket|ws closed/i.test(e));
    t('1z 页面零致命 JS 错误', fatal.length === 0, fatal.join(' | ').slice(0, 160));
  } finally {
    await browser.close().catch(() => {});
  }
  report['A7 懒渲染/滚动回收/缩放（真实 pdf.js + 100p 夹具）'] = rows;
}

// ── 站4：护栏源码钉（§5.3 代码审面 + 闸镜像拆分钉）─────────────────────────
{
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  const pdfJs = readFileSync(join(WEB_DIR, 'js/viewers/pdf.js'), 'utf8');
  const ap = readFileSync(join(WEB_DIR, 'js/attachmentPreview.js'), 'utf8');
  const tss = readFileSync(join(SCALA_DIR, 'TextStreamSearch.scala'), 'utf8');
  const wsf = readFileSync(join(SCALA_DIR, 'WsFileOpsHandlers.scala'), 'utf8');

  t('4a 护栏边距 ±2 屏（RENDER_MARGIN_SCREENS = 2）', /const RENDER_MARGIN_SCREENS = 2;/.test(pdfJs));
  t('4b 护栏② 单页并发 ≤2（MAX_RENDER_CONCURRENCY = 2）', /const MAX_RENDER_CONCURRENCY = 2;/.test(pdfJs));
  t('4c 护栏① 常驻位图 ≤6（MAX_RESIDENT_BITMAPS = 6）', /const MAX_RESIDENT_BITMAPS = 6;/.test(pdfJs));
  t('4d 视口驱动 = IntersectionObserver，root = 滚动容器，rootMargin 用护栏常量',
    /new IntersectionObserver/.test(pdfJs) && /root:\s*pages/.test(pdfJs) && /rootMargin:\s*`\$\{RENDER_MARGIN_SCREENS \* 100\}% 0px`/.test(pdfJs));
  t('4e 离屏释放撤 width/height 属性（A7 DOM 判据）+ page.cleanup（pdf.js viewer 同范式）',
    /c\.removeAttribute\('width'\)/.test(pdfJs) && /c\.removeAttribute\('height'\)/.test(pdfJs) && /\.cleanup\(\)/.test(pdfJs));
  t('4f 页尺寸预占（>2000 页抽样 50 均高估算）在场',
    /FULL_SIZE_SCAN_MAX_PAGES = 2000/.test(pdfJs) && /SIZE_SAMPLE_COUNT = 50/.test(pdfJs));
  t('4g 渲染失败「重试一次 + 占位可见」纪律保留', /renderFailed/.test(pdfJs) && (pdfJs.match(/await page\.render/g) || []).length >= 2);
  t('4h 渲染竞态纪律保留（renderToken 失效模式）', (pdfJs.match(/renderToken/g) || []).length >= 4);

  t('4i 文本闸镜像不动：MAX_TEXT_BYTES = 100MB', /const MAX_TEXT_BYTES = 100 \* 1024 \* 1024;/.test(ap));
  t('4j 二进制闸镜像拆分：MAX_VIEWER_BYTES = 1,073,741,824（= AttachContract.MaxFileBytes）',
    /const MAX_VIEWER_BYTES = 1073741824;/.test(ap));
  t('4k MAX_VIEWER_BYTES 在两个 BLOB 腿消费（previewBlob + runProgressAttempt）',
    (ap.match(/blob\.size > MAX_VIEWER_BYTES/g) || []).length === 2);
  t('4l 内联直显预算刻意解耦不动（MAX_INLINE_IMAGE_BYTES = 10MB）',
    /export const MAX_INLINE_IMAGE_BYTES = 10 \* 1024 \* 1024;/.test(ap));

  t('4m Scala 二进制闸 = 附件总闸**引用**（MaxViewerFileBytes = AttachContract.MaxFileBytes，禁字面量第二把尺）',
    /val MaxViewerFileBytes: Long = nebflow\.shared\.AttachContract\.MaxFileBytes/.test(tss));
  t('4n Scala 文本闸 100MB 字面量不动', /val MaxPopReadFileBytes: Long = 100L \* 1024 \* 1024/.test(tss));
  t('4o Scala 二值闸判据 popReadGateLimit 在场', /def popReadGateLimit\(isBinary: Boolean\): Long/.test(tss));
  t('4p pop.readFile 闸前先做 binary 判定（两门各归各位的接线）',
    /popIsBinary = nebflow\.core\.workspace\.FileTypeRegistry\.detect\(popExt\)\.binary[\s\S]*?popGateBytes = TextStreamSearch\.popReadGateLimit\(popIsBinary\)[\s\S]*?fileSize > popGateBytes/.test(wsf));
  t('4q 二进制腿零整件堆读口径保留（Range 流式注释在场）', /fs2\.io\.file\.Files\.readRange/.test(wsf));
  report['static(护栏四条件 + 闸拆分源码钉)'] = rows;
}

// ── 站5：大文件实跑（~115MB 稀疏 50 页，Range 流式）────────────────────────
{
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  const { browser, page, errors } = await bootPage();
  try {
    const m0 = await heapBytes(page);
    const t0 = Date.now();
    await mountPdf(page, `${BASE}/fixture/big-50p.pdf`);
    await waitSettled(page, 60000);
    const firstScreenMs = Date.now() - t0;
    const m1 = await heapBytes(page);
    const bc1 = await bitmapCount(page);
    t('5a 首屏静止 ≤3s（设计 §5.3-③ 的 1GiB 级 QA 预算，本夹具 115MB）', firstScreenMs <= 3000, `${firstScreenMs}ms`);
    t('5b 首屏位图数 ≤6（护栏①）', bc1 >= 1 && bc1 <= 6, `count=${bc1}`);
    t('5c 首屏堆增量 ≤64MB（只取了 xref/首页数据，未整件入堆）', m1 - m0 <= 64 * 1024 * 1024, `+${Math.round((m1 - m0) / 1048576)}MB`);

    // 滚到底（等末页真的渲出 = 新状态，再等静止），量堆。
    const height = await scrollPages(page, 'bottom');
    await waitForPageRendered(page, 50, 60000);
    await waitSettled(page, 60000);
    const m2 = await heapBytes(page);
    const bc2 = await bitmapCount(page);
    t('5d 滚到底后位图数 ≤6（全程渲染面不膨胀）', bc2 >= 1 && bc2 <= 6, `count=${bc2}`);
    t('5e 全卷堆增长 ≤ 取数缓存地板（fileBytes + 80MB：pdf.js 取数缓存 ≈ 文件体积，渲染位图恒定）',
      m2 - m1 <= BIG_BYTES + 80 * 1024 * 1024,
      `+${Math.round((m2 - m1) / 1048576)}MB vs file=${Math.round(BIG_BYTES / 1048576)}MB`);

    // 二遍：回顶（等首页真的渲出）再滚到底——取数缓存已暖，任何再增长 = 渲染侧泄漏。
    await scrollPages(page, 0);
    await waitForPageRendered(page, 1, 60000);
    await waitSettled(page, 60000);
    await scrollPages(page, 'bottom');
    await waitForPageRendered(page, 50, 60000);
    await waitSettled(page, 60000);
    const m3 = await heapBytes(page);
    const bc3 = await bitmapCount(page);
    t('5f 二遍通读堆增量 ≤32MB（「内存不涨」实证：一遍已暖缓存）', m3 - m2 <= 32 * 1024 * 1024, `+${Math.round((m3 - m2) / 1048576)}MB`);
    t('5g 二遍后位图数仍 ≤6', bc3 >= 1 && bc3 <= 6, `count=${bc3}`);
    await waitSettled(page, 60000);
    // 真渲判据 = 像素**有变化**（随机图夹具：空白/失败占位是均匀色，真渲多采样必发散）。
    const bottomPx = await page.evaluate(() => {
      const cs = [...document.querySelectorAll('#pdf-test-pane canvas.pdf-page')];
      const last = cs[cs.length - 1];
      if (!last || !last.hasAttribute('width')) return null;
      const g = last.getContext('2d');
      const pts = [[0.5, 0.5], [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.8, 0.8]];
      const vals = pts.map(([fx, fy]) => {
        const d = g.getImageData(Math.floor(last.width * fx), Math.floor(last.height * fy), 1, 1).data;
        return `${d[0]},${d[1]},${d[2]}`;
      });
      return { distinct: new Set(vals).size, vals };
    });
    t('5h 末页真渲（随机图夹具：5 采样点像素发散 >2 种，非均匀空白/占位）',
      !!bottomPx && bottomPx.distinct > 2, JSON.stringify(bottomPx));

    const fatal = errors.filter(e => !/favicon|net::|WebSocket|ws closed/i.test(e));
    t('5z 页面零致命 JS 错误', fatal.length === 0, fatal.join(' | ').slice(0, 160));
    rows['5i 读数'] = `首屏 ${firstScreenMs}ms；堆 ${Math.round(m0 / 1048576)}→${Math.round(m1 / 1048576)}→${Math.round(m2 / 1048576)}→${Math.round(m3 / 1048576)}MB（夹具 ${Math.round(BIG_BYTES / 1048576)}MB）；scrollHeight=${height}`;
  } finally {
    await browser.close().catch(() => {});
  }
  report[`大文件实跑（${Math.round(BIG_BYTES / 1048576)}MB 稀疏 50 页）`] = rows;
}

server.close();
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n== ${engine} ==`);
  for (const [name, verdict] of Object.entries(rows)) {
    if (name.endsWith('读数')) { console.log(`  INFO  ${verdict}`); continue; }
    console.log(`  ${verdict.startsWith('PASS') ? 'PASS' : 'FAIL'}  ${name}${verdict.startsWith('PASS') ? '' : '  ' + verdict.slice(5)}`);
    if (!verdict.startsWith('PASS')) fail++;
  }
}
console.log(`\n${Object.values(report).reduce((n, r) => n + Object.keys(r).length, 0)} checks, ${fail} failed`);
process.exit(fail ? 1 : 0);
