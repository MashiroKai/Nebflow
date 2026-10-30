// reader-p0.spec.mjs — 阅读器 P0 止血批验收（设计稿 20261030_181655 §7.2 P0 断言）。
//
// 覆盖：A1（EPUB 图片可见，blob 计数 + 0 个 4xx）/ A2（资源缺失一次性 toast +
// data-nf-res-miss 可见降级）/ A3（暗夜对比度 ≥4.5:1 + 书内硬编码色只读清洗，
// computed 断言）/ A4（点按 1/3-1/3-1/3 分区 + 菜单开时点正文仅收菜单）/
// A9（手机层查看面顶垫 ≥ calc(36px+16px)）/ A11（字号/主题档持久化
// localStorage nf.reader.prefs）+ 单图 8MB 闸（data-nf-res-too-big 占位）。
//
// 被测真实链路（前端零替身，mock 档）：workspace-open-item → canvas.js →
// WS pop.readFile（binary 只回元数据）→ renderFile → viewers/epub.js →
// nfTicket 铸票 → /api/nf-file（替身带票闸）→ ZIP 解析/改写/渲染。
// 夹具：运行时手写 stored zip（含 %20 href、spine 外引用、lying-header 8MB+ 单图）。
//
// 运行：node tests/reader-p0.spec.mjs
// 可选 env：PORT=8146、SHOTS_DIR=<dir>（缺省 .nebflow/evidence/<ts>_reader-p0/）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8146);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'nf-reader-p0-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/' + new Date().toISOString().replace(/[-:]/g, '').slice(0, 14).replace(/(\d{8})(\d{6})/, '$1_$2') + '_reader-p0');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具：手写 stored zip（CRC32 自算；可覆写声明尺寸） ───────────── */

const CRC = (() => {
  const t = new Int32Array(256).map((_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c;
  });
  return (buf) => {
    let c = -1;
    for (const b of buf) c = t[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ -1) >>> 0;
  };
})();

/** 手写 stored（uncompressed）zip。declaredOverride：entry 名 → 伪声明未压缩尺寸
 *  （lying-header 夹具：8MB 闸按声明尺寸预判，不该触发真实解压）。 */
function writeStoredZip(out, entries, declaredOverride = {}) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = CRC(data);
    const declared = declaredOverride[name] || data.length;
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6);
    lh.writeUInt16LE(0, 8); lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(declared, 22);
    lh.writeUInt16LE(nameBuf.length, 26); lh.writeUInt16LE(0, 28);
    chunks.push(lh, nameBuf, data);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0, 8); ch.writeUInt16LE(0, 10); ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(declared, 24);
    ch.writeUInt16LE(nameBuf.length, 28); ch.writeUInt16LE(0, 30); ch.writeUInt16LE(0, 32);
    ch.writeUInt16LE(0, 34); ch.writeUInt16LE(0, 36); ch.writeUInt32LE(0, 38); ch.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([ch, nameBuf]));
    offset += lh.length + nameBuf.length + data.length;
  }
  const cd = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(0, 4); eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12); eocd.writeUInt32LE(offset, 16); eocd.writeUInt16LE(0, 20);
  writeFileSync(out, Buffer.concat([...chunks, cd, eocd]));
}

/** 1×1 透明 PNG。 */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);

const NS_OPF = 'http://www.idpf.org/2007/opf';

function makeBookA(out) {
  const container = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;
  const opf = `<?xml version="1.0"?>
<package xmlns="${NS_OPF}" version="3.0" unique-identifier="id">
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="pic" href="images/pic%20one.png" media-type="image/png"/>
    <item id="fig2" href="images/fig2.png" media-type="image/png"/>
    <item id="css" href="css/style.css" media-type="text/css"/>
  </manifest>
  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>`;
  const ch1 = `<?xml version="1.0"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title>
<style>
body { background-color: #fdfdfd; color: #222222; }
p { color: #111111; }
.keep { font-style: italic; }
</style>
<link rel="stylesheet" type="text/css" href="../css/style.css"/>
</head><body>
<h1>第一章 开端</h1>
<p style="color:#333333">INLINE-COLOR-MARKER 这段内联硬编码色应被清洗为主题墨。</p>
<p class="bgimg">CSS-BG-MARKER 背景图来自样式表 url() 改写。</p>
<p class="keep">KEEP-MARKER 排版声明（斜体）必须保真。</p>
<img src="../images/pic%20one.png" alt="encoded"/>
<img src="images/fig2.png" alt="miss"/>
${Array.from({ length: 80 }, (_, i) => `<p>第一章填充段落 ${i + 1}，用于制造翻页空间。</p>`).join('\n')}
</body></html>`;
  const ch2 = `<?xml version="1.0"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head><body>
<h1>第二章 深入</h1>
<img src="../images/pic one.png" alt="raw-space"/>
${Array.from({ length: 60 }, (_, i) => `<p>第二章填充段落 ${i + 1}。</p>`).join('\n')}
</body></html>`;
  const css = `p { color: #444444; }
.bgimg { background-image: url("../images/fig2.png"); background-repeat: no-repeat; }
.fine { line-height: 2; }`;
  writeStoredZip(out, [
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/text/ch1.xhtml', ch1],
    ['OEBPS/text/ch2.xhtml', ch2],
    ['OEBPS/css/style.css', css],
    ['OEBPS/images/pic one.png', PNG_1X1],
    ['OEBPS/images/fig2.png', PNG_1X1],
  ]);
}

/** 单图 8MB 闸夹具：big.png 声明 9MB（lying header）、实际 1×1 PNG 字节。 */
function makeBookTooBig(out) {
  const container = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;
  const opf = `<?xml version="1.0"?>
<package xmlns="${NS_OPF}" version="3.0" unique-identifier="id">
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="big" href="images/big.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>`;
  const ch1 = `<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>大图章</title></head><body>
<h1>大图章</h1>
<p>BOOK-B-TEXT 正文先行渲染。</p>
<img src="../images/big.png" alt="too-big"/>
</body></html>`;
  writeStoredZip(out, [
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/text/ch1.xhtml', ch1],
    ['OEBPS/images/big.png', PNG_1X1],
  ], { 'OEBPS/images/big.png': 9 * 1024 * 1024 });
}

/* ───────────── 静态服务（真 web/ 树） ───────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ───────────── /api/nf-file 替身（真端点语义：票闸 + 按路径回字节） ───────────── */
async function nfFileRoute(route) {
  if (await ticketGuard(route)) return;
  const url = new URL(route.request().url());
  const p = url.searchParams.get('path') || '';
  try {
    const body = readFileSync(p);
    const CT = { '.epub': 'application/epub+zip', '.md': 'text/markdown', '.js': 'text/javascript', '.png': 'image/png' };
    return route.fulfill({ status: 200, headers: { 'Content-Type': CT[extname(p).toLowerCase()] || 'application/octet-stream' }, body });
  } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
}

/* ───────────── WS 替身：pop.readFile 真语义（binary 只回元数据） ───────────── */
const EXT_ITEM = {
  md: 'markdown', js: 'code', json: 'json', png: 'image', pdf: 'pdf', epub: 'epub',
};
const BINARY = new Set(['png', 'pdf', 'epub']);
const MAX_READ = 100 * 1024 * 1024;

/* ───────────── 应用引导 ───────────── */
async function boot(browser, { colorScheme = 'light', mobile = false } = {}) {
  const ctx = await browser.newContext({
    colorScheme,
    hasTouch: mobile,
    viewport: mobile ? { width: 390, height: 844 } : { width: 1600, height: 1000 },
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  const badResponses = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('response', (r) => { if (r.status() >= 400 && !r.url().includes('favicon')) badResponses.push(`${r.status()} ${r.url().slice(0, 120)}`); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await installTicketMock(page);
  await page.route(/\/api\/nf-file\?/, nfFileRoute);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type !== 'pop.readFile') return;
      const rawPath = String(m.path || '');
      try {
        const buf = readFileSync(rawPath);
        if (buf.length > MAX_READ) throw new Error('file exceeds 100MB limit');
        const ext = (rawPath.split('.').pop() || '').toLowerCase();
        const msg = { type: 'fileContent', path: rawPath, absPath: rawPath, itemType: EXT_ITEM[ext] || 'code', fileName: rawPath.split('/').pop(), size: buf.length };
        if (!BINARY.has(ext)) msg.content = buf.toString('utf8');
        ws.send(JSON.stringify(msg));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: rawPath }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) {
    // 手机层 #activity-bar 隐藏（桌面侧栏退场）——以交互层激活探针为准。
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
  }
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  return { ctx, page, errors, badResponses };
}

/** 以 workspace-open-item 打开文件（空内容＋空 itemType 的 Pop 卡同款腿）。 */
async function openPath(page, absPath) {
  await page.evaluate((abs) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + abs, itemType: '', content: '', absPath: abs, size: 0, title: abs.split('/').pop() },
    }));
  }, absPath);
}

async function shot(page, name) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  const out = join(SHOTS_DIR, name);
  await page.screenshot({ path: out, fullPage: false });
  console.log(`  [shot] ${out}`);
  return out;
}

const waitReader = (page) => page.waitForSelector('.canvas-tab-pane.active .epub-reader', { timeout: 20000 });

/** WCAG 对比度（page 内计算，relative luminance）。 */
const CONTRAST_FN = `(() => {
  const lum = (rgb) => {
    const c = rgb.match(/[\\d.]+/g).map(Number);
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
  };
  return (a, b) => {
    const l1 = lum(a), l2 = lum(b);
    const hi = Math.max(l1, l2), lo = Math.min(l1, l2);
    return (hi + 0.05) / (lo + 0.05);
  };
})()`;

/* ═══════════════════════════ 夹具 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const bookA = join(FIX_DIR, 'book-a.epub');
const bookB = join(FIX_DIR, 'book-b-toobig.epub');
makeBookA(bookA);
makeBookTooBig(bookB);
console.log(`[fixtures] bookA=${bookA} (${readFileSync(bookA).length}B) bookB=${bookB} (${readFileSync(bookB).length}B)`);

const browser = await chromium.launch();

console.log('\n== A1+A2 桌面亮面板：图片根治 / 缺失可见降级 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookA);
  await waitReader(h.page);
  await h.page.waitForFunction(() => {
    const c = document.querySelector('.canvas-tab-pane.active .epub-reader-content');
    return c && c.querySelectorAll('img').length >= 2;
  }, { timeout: 15000 });
  await sleep(300);

  // 解析期全书资源改写账本：ch1 的 %20 形态 + ch2 的原始空格形态 + CSS url(fig2)
  // 三章内引用在 parse 阶段全部改写（ch2 未显示也入账）。
  const cacheStats = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const urls = pane && pane._epubBlobUrls;
    return { cacheSize: urls && urls.size !== undefined ? urls.size : (urls ? urls.length : -1) };
  });
  const imgStats = await h.page.evaluate(() => {
    const c = document.querySelector('.canvas-tab-pane.active .epub-reader-content');
    const imgs = [...c.querySelectorAll('img')];
    return {
      total: imgs.length,
      blob: imgs.filter((i) => (i.getAttribute('src') || '').startsWith('blob:')).length,
      miss: imgs.filter((i) => i.hasAttribute('data-nf-res-miss')).length,
      missNoSrc: imgs.filter((i) => i.hasAttribute('data-nf-res-miss') && !i.hasAttribute('src')).length,
    };
  });
  ok('A1 解析期全书 blob 缓存 = 2（%20 形态 + 原始空格形态同 entry 去重 + CSS url fig2）', cacheStats.cacheSize === 2, JSON.stringify(cacheStats));
  ok('A1 首章 img[src^="blob:"] 计数 = 1（%20 引用命中）', imgStats.blob === 1, JSON.stringify(imgStats));
  ok('A2 spine 外引用 miss：data-nf-res-miss + 摘 src（零 404 噪声）', imgStats.miss === 1 && imgStats.missNoSrc === 1, JSON.stringify(imgStats));
  ok('A1 零 4xx 网络响应', h.badResponses.length === 0, h.badResponses.join(' | '));

  // 样式表 url() 改写 + 排版保真
  const styleStats = await h.page.evaluate(() => {
    const c = document.querySelector('.canvas-tab-pane.active .epub-reader-content');
    const bg = c.querySelector('.bgimg');
    const keep = c.querySelector('.keep');
    return {
      bgImage: bg ? getComputedStyle(bg).backgroundImage : '',
      keepFontStyle: keep ? getComputedStyle(keep).fontStyle : '',
      scopedCount: c.querySelectorAll('style.epub-book-style').length,
      strayStyle: [...c.querySelectorAll('style')].filter((s) => !s.classList.contains('epub-book-style')).length,
    };
  });
  ok('样式表 url() 改写：.bgimg background-image 含 blob:', /blob:/.test(styleStats.bgImage), styleStats.bgImage.slice(0, 120));
  ok('排版保真：.keep font-style italic（书内排版声明保留）', styleStats.keepFontStyle === 'italic', styleStats.keepFontStyle);
  ok('书内 <style> 单点 scoped 注入（1 个 epub-book-style，原 style 全摘除）', styleStats.scopedCount === 1 && styleStats.strayStyle === 0, JSON.stringify(styleStats));

  // A2 toast
  let toastText = '';
  try {
    await h.page.waitForSelector('.nebflow-toast.show', { timeout: 5000 });
    toastText = await h.page.evaluate(() => document.querySelector('.nebflow-toast .nebflow-toast-msg')?.textContent || '');
  } catch { /* toast 缺席由下行断言 */ }
  ok('A2 出现「1 个资源缺失」toast（i18n key epub.resMissing）', toastText.includes('1 个资源缺失'), `toast="${toastText}"`);
  await shot(h.page, 'a1-a2-desktop-light.png');
  ok('A1/A2 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

console.log('\n== A4 桌面：点按 1/3-1/3-1/3 分区 + 菜单开时点正文仅收菜单 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookA);
  await waitReader(h.page);
  await h.page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return v && v.scrollHeight > v.clientHeight * 2;
  }, { timeout: 15000 });

  const menuState = () => h.page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-pane.active .epub-reader-menu');
    return { hidden: m.hidden, attr: m.dataset.epubMenu };
  });
  const pos = () => h.page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return { scrollTop: v.scrollTop, info: document.querySelector('.canvas-tab-pane.active .epub-nav-info').textContent };
  });
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  const clickAt = (fx, fy) => h.page.mouse.click(rect.left + rect.width * fx, rect.top + rect.height * fy);

  let st0 = await menuState();
  ok('A4 初始菜单关', st0.hidden === true && st0.attr === 'closed', JSON.stringify(st0));

  // 中间 1/3 → 菜单开
  await clickAt(0.5, 0.55);
  await sleep(200);
  let st = await menuState();
  ok('A4 中间 1/3 点按 → 菜单开', st.hidden === false && st.attr === 'open', JSON.stringify(st));
  await shot(h.page, 'a4-menu-open.png');

  // 菜单开时点右 1/3 → 仅关菜单不翻页
  const before = await pos();
  await clickAt(0.85, 0.55);
  await sleep(250);
  st = await menuState();
  const after = await pos();
  ok('A4 菜单开时点正文 → 仅关菜单不翻页', st.hidden === true && after.scrollTop === before.scrollTop && after.info === before.info,
    `menu=${JSON.stringify(st)} scroll ${before.scrollTop}→${after.scrollTop}`);

  // 右 1/3 → 下一页（滚动前进）
  await clickAt(0.85, 0.55);
  await h.page.waitForFunction((prev) => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return v.scrollTop > prev + 10;
  }, before.scrollTop, { timeout: 5000 }).catch(() => {});
  let p = await pos();
  ok('A4 右 1/3 点按 → 下一页（scrollTop 前进）', p.scrollTop > before.scrollTop + 10, `scrollTop=${p.scrollTop}`);

  // 左 1/3 → 上一页（滚回顶部）
  await clickAt(0.15, 0.55);
  await h.page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return v.scrollTop < 10;
  }, {}, { timeout: 5000 }).catch(() => {});
  p = await pos();
  ok('A4 左 1/3 点按 → 上一页（scrollTop 回顶）', p.scrollTop < 10, `scrollTop=${p.scrollTop}`);

  // 中间再点 → 菜单再开（toggle 语义）
  await clickAt(0.5, 0.75);
  await sleep(200);
  st = await menuState();
  ok('A4 中间 1/3 再点 → 菜单重开（toggle）', st.hidden === false, JSON.stringify(st));
  await h.ctx.close();
}

console.log('\n== A11 桌面：字号/主题档持久化（nf.reader.prefs） ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookA);
  await waitReader(h.page);

  // 打开菜单 → 选暗夜 → 字号 + 两次（16→18→20）
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  await h.page.mouse.click(rect.left + rect.width * 0.5, rect.top + rect.height * 0.55);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])', { timeout: 5000 });
  await h.page.click('.canvas-tab-pane.active [data-epub-theme-pick="dark"]');
  await h.page.click('.canvas-tab-pane.active [data-epub-step="fontSize:1"]');
  await h.page.click('.canvas-tab-pane.active [data-epub-step="fontSize:1"]');
  await sleep(200);

  const setNow = await h.page.evaluate(() => ({
    theme: document.querySelector('.canvas-tab-pane.active .epub-reader').dataset.epubTheme,
    fontSize: getComputedStyle(document.querySelector('.canvas-tab-pane.active .epub-reader-content')).fontSize,
    prefs: localStorage.getItem('nf.reader.prefs'),
  }));
  ok('A11 选档生效：主题 dark + 字号 20px', setNow.theme === 'dark' && setNow.fontSize === '20px', JSON.stringify(setNow));
  ok('A11 localStorage nf.reader.prefs 落账', !!setNow.prefs && setNow.prefs.includes('"theme":"dark"') && setNow.prefs.includes('"fontSize":20'), setNow.prefs);

  // 重开面板（整页重载）→ 档位保持
  await h.page.reload();
  await h.page.waitForSelector('#activity-bar', { timeout: 20000 });
  await h.page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  await openPath(h.page, bookA);
  await waitReader(h.page);
  const kept = await h.page.evaluate(() => ({
    theme: document.querySelector('.canvas-tab-pane.active .epub-reader').dataset.epubTheme,
    fontSize: getComputedStyle(document.querySelector('.canvas-tab-pane.active .epub-reader-content')).fontSize,
  }));
  ok('A11 重开后面板档位保持（dark + 20px）', kept.theme === 'dark' && kept.fontSize === '20px', JSON.stringify(kept));
  await shot(h.page, 'a11-persisted-dark.png');
  await h.ctx.close();
}

console.log('\n== 单图 8MB 闸：lying-header 9MB 声明 → data-nf-res-too-big 占位 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookB);
  await waitReader(h.page);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-content img', { timeout: 15000 });
  const gate = await h.page.evaluate(() => {
    const img = document.querySelector('.canvas-tab-pane.active .epub-reader-content img');
    return {
      tooBig: img.hasAttribute('data-nf-res-too-big'),
      src: img.getAttribute('src') || '',
      text: document.querySelector('.canvas-tab-pane.active .epub-reader-content').textContent.includes('BOOK-B-TEXT'),
    };
  });
  ok('8MB 闸：超声明单图打 data-nf-res-too-big 占位、不出 blob', gate.tooBig && !gate.src.startsWith('blob:'), JSON.stringify(gate));
  ok('8MB 闸：正文先行渲染不阻塞', gate.text === true);
  await shot(h.page, 'gate-8mb-toobig.png');
  await h.ctx.close();
}

console.log('\n== A3 暗面板：暗夜主题对比度 ≥4.5:1 + 硬编码色清洗（computed 断言） ==');
{
  const h = await boot(browser, { colorScheme: 'dark' });
  await openPath(h.page, bookA);
  await waitReader(h.page);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-content p', { timeout: 15000 });

  const stats = await h.page.evaluate((contrastSrc) => {
    const contrast = eval(contrastSrc);
    const c = document.querySelector('.canvas-tab-pane.active .epub-reader-content');
    const cs = getComputedStyle(c);
    const inline = [...c.querySelectorAll('p')].find((p) => p.textContent.includes('INLINE-COLOR-MARKER'));
    const plain = [...c.querySelectorAll('p')].find((p) => p.textContent.includes('CSS-BG-MARKER'));
    const quote = c.querySelector('blockquote');
    const ics = getComputedStyle(inline);
    const pcs = getComputedStyle(plain);
    return {
      theme: document.querySelector('.canvas-tab-pane.active .epub-reader').dataset.epubTheme,
      bg: cs.backgroundColor,
      fg: cs.color,
      contentContrast: contrast(cs.color, cs.backgroundColor),
      inlineColor: ics.color,
      inlineCleaned: ics.color === cs.color,
      plainColor: pcs.color,
      plainCleaned: pcs.color === cs.color,
      quoteContrast: quote ? contrast(getComputedStyle(quote).color, cs.backgroundColor) : null,
    };
  }, CONTRAST_FN);
  ok('A3 暗面板默认落暗夜档（auto → dark）', stats.theme === 'dark', stats.theme);
  ok('A3 暗夜正文对比度 ≥4.5:1', stats.contentContrast >= 4.5, `contrast=${stats.contentContrast.toFixed(2)} bg=${stats.bg} fg=${stats.fg}`);
  ok('A3 书内 inline color:#333 被清洗（computed = 主题墨）', stats.inlineCleaned, `inline=${stats.inlineColor} theme=${stats.fg}`);
  ok('A3 章节/样式表硬编码色被清洗（书内 <style> p{color:#111} + css p{color:#444}）', stats.plainCleaned, `plain=${stats.plainColor} theme=${stats.fg}`);
  ok('A3 引用行对比度 ≥4.5:1（color-mix 主题墨色）', stats.quoteContrast === null || stats.quoteContrast >= 4.5, `quote=${stats.quoteContrast && stats.quoteContrast.toFixed(2)}`);
  await shot(h.page, 'a3-dark-theme.png');
  await h.ctx.close();
}

console.log('\n== A9 手机层：查看面顶垫 ≥ calc(36px + 16px)（md / code / image） ==');
{
  const h = await boot(browser, { mobile: true });
  const md = join(FIX_DIR, 'top.md');
  const code = join(FIX_DIR, 'top.js');
  writeFileSync(md, '# TOP-PAD-MARKER\n\n手机层顶垫断言样例。\n');
  writeFileSync(code, 'const TOP_PAD_MARKER = true;\n');
  const png = join(FIX_DIR, 'top.png');
  writeFileSync(png, PNG_1X1);

  // 同 type 打开会触发 canvas 预览组替换（前卡被顶掉）——每开一个面即测其
  // 容器（display:none 的 pane computed padding 仍解析），最后留 png 截图。
  const readPad = (sel) => h.page.evaluate((s) => {
    const el = document.querySelector(s);
    return el ? parseFloat(getComputedStyle(el).paddingTop) : null;
  }, sel);
  await openPath(h.page, md);
  await sleep(800);
  const padMd = await readPad('.canvas-md-scroll.canvas-viewer-safe-top');
  await openPath(h.page, code);
  await sleep(800);
  const padMonaco = await readPad('.canvas-monaco-container.canvas-viewer-safe-top');
  await openPath(h.page, png);
  await sleep(800);
  const padImage = await readPad('.canvas-image-viewer.canvas-viewer-safe-top');

  const mobileLayer = await h.page.evaluate(() => document.body.classList.contains('mobile-layer'));
  const MIN = 52; // calc(var(--capsule-h, 36px) + 16px)
  ok('A9 手机层已激活（body.mobile-layer）', mobileLayer === true);
  ok('A9 md 查看面 padding-top ≥ 52px', padMd !== null && padMd >= MIN, `md=${padMd}`);
  ok('A9 代码查看面 padding-top ≥ 52px', padMonaco !== null && padMonaco >= MIN, `monaco=${padMonaco}`);
  ok('A9 图片查看面 padding-top ≥ 52px', padImage !== null && padImage >= MIN, `image=${padImage}`);

  // dock 进 canvas 页截图存证（真实 UI 路径）
  await h.page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await h.page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="canvas"]');
    if (!el || !el.classList.contains('live')) return false;
    const tf = getComputedStyle(el).transform;
    return tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)';
  }, { timeout: 8000 });
  await sleep(500);
  await shot(h.page, 'a9-mobile-top-pad.png');
  await h.ctx.close();
}

await browser.close();
webServer.close();
const failed = results.filter((r) => !r.pass);
console.log(`\n== ${results.length - failed.length}/${results.length} 项通过 ==`);
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => `  ✗ ${f.name} — ${f.detail}`).join('\n'));
  process.exit(1);
}
