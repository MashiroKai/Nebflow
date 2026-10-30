// reader-p1.spec.mjs — 阅读器 P1 统一内核批验收（设计稿 20261030_181655 §7.2 P1 断言）。
//
// 覆盖：A5（底栏重排：桌面 [‹ ›]滑杆页码 Aa ☰ ⋯ / 移动收 ‹ 且含 ⋯ 刷新、
// 刷新钮不在底栏直显位）/ A6（进度条拖 >50% 跨章跳章 + epub-page-change
// 单调）/ A10（reduced-motion 翻页 ≤0.01s）/ TOC 抽屉（OPF2 NCX 嵌套 +
// OPF3 nav + 章内锚定位 + 移动底弹）/ 240ms 双页夹翻页动效（双页层/时长/
// 缓动/页缘投影）/ touch 横滑跟手（慢速小位移回弹、快速率判定完成）/
// 统一 chrome 套 txt（\f 分章 + 主题）与 md（TOC 抽屉 + 滑杆 + fab gate-off）。
//
// 被测真实链路（前端零替身，mock 档）：同 reader-p0.spec.mjs。
// 夹具：运行时手写 stored zip（EPUB2 NCX / EPUB3 nav）+ txt/md 文本件。
//
// 运行：node tests/reader-p1.spec.mjs
// 可选 env：PORT=8147、SHOTS_DIR=<dir>（缺省 .nebflow/evidence/<ts>_reader-p1/）

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
const PORT = Number(process.env.PORT || 8147);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'nf-reader-p1-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/' + new Date().toISOString().replace(/[-:]/g, '').slice(0, 14).replace(/(\d{8})(\d{6})/, '$1_$2') + '_reader-p1');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具：手写 stored zip（同 P0 批 CRC32 自算） ───────────── */

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

function writeStoredZip(out, entries) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = CRC(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6);
    lh.writeUInt16LE(0, 8); lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(nameBuf.length, 26); lh.writeUInt16LE(0, 28);
    chunks.push(lh, nameBuf, data);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0, 8); ch.writeUInt16LE(0, 10); ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24);
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

const NS_OPF = 'http://www.idpf.org/2007/opf';
const XHTML = 'http://www.w3.org/1999/xhtml';

const filler = (tag, n) => Array.from({ length: n }, (_, i) => `<p>${tag} 填充段落 ${i + 1}，用于制造翻页空间。白日依山尽，黄河入海流。欲穷千里目，更上一层楼。</p>`).join('\n');

function chapter(title, extra, n) {
  return `<?xml version="1.0"?>
<html xmlns="${XHTML}"><head><title>${title}</title></head><body>
<h1>${title}</h1>
${extra || ''}
${filler(title, n)}
</body></html>`;
}

/** EPUB2 + toc.ncx：三章 + 一章内二级锚点。 */
function makeBookNcx(out) {
  const container = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;
  const opf = `<?xml version="1.0"?>
<package xmlns="${NS_OPF}" version="2.0" unique-identifier="id">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/><itemref idref="ch3"/></spine>
</package>`;
  const ncx = `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head></head><docTitle><text>BOOK-NCX</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>第一章</text></navLabel><content src="text/ch1.xhtml"/>
      <navPoint id="n1-1" playOrder="2"><navLabel><text>第一章第二节</text></navLabel><content src="text/ch1.xhtml#sec1-2"/></navPoint>
    </navPoint>
    <navPoint id="n2" playOrder="3"><navLabel><text>第二章</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
    <navPoint id="n3" playOrder="4"><navLabel><text>第三章</text></navLabel><content src="text/ch3.xhtml"/></navPoint>
  </navMap>
</ncx>`;
  writeStoredZip(out, [
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/toc.ncx', ncx],
    ['OEBPS/text/ch1.xhtml', chapter('第一章 开端', '<h2 id="sec1-2">第一章第二节</h2>', 60)],
    ['OEBPS/text/ch2.xhtml', chapter('第二章 深入', '', 50)],
    ['OEBPS/text/ch3.xhtml', chapter('第三章 收束', '', 50)],
  ]);
}

/** EPUB3 + nav.xhtml（epub:type="toc"）：两章。 */
function makeBookNav(out) {
  const container = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;
  const opf = `<?xml version="1.0"?>
<package xmlns="${NS_OPF}" version="3.0" unique-identifier="id">
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>`;
  const nav = `<?xml version="1.0"?>
<html xmlns="${XHTML}" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body>
<nav epub:type="toc"><h1>目录</h1><ol>
  <li><a href="text/ch1.xhtml">启程</a></li>
  <li><a href="text/ch2.xhtml">归宿</a></li>
</ol></nav>
</body></html>`;
  writeStoredZip(out, [
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/nav.xhtml', nav],
    ['OEBPS/text/ch1.xhtml', chapter('启程', '', 40)],
    ['OEBPS/text/ch2.xhtml', chapter('归宿', '', 40)],
  ]);
}

/* ───────────── 静态服务 + 替身（同 P0 批语义） ───────────── */
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

async function nfFileRoute(route) {
  if (await ticketGuard(route)) return;
  const url = new URL(route.request().url());
  const p = url.searchParams.get('path') || '';
  try {
    const body = readFileSync(p);
    const CT = { '.epub': 'application/epub+zip', '.md': 'text/markdown', '.txt': 'text/plain', '.png': 'image/png' };
    return route.fulfill({ status: 200, headers: { 'Content-Type': CT[extname(p).toLowerCase()] || 'application/octet-stream' }, body });
  } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
}

const EXT_ITEM = {
  md: 'markdown', js: 'code', json: 'code', png: 'image', pdf: 'pdf', epub: 'epub', txt: 'code',
};
const BINARY = new Set(['png', 'pdf', 'epub']);
const MAX_READ = 100 * 1024 * 1024;

async function boot(browser, { colorScheme = 'light', mobile = false, reducedMotion = false } = {}) {
  const ctx = await browser.newContext({
    colorScheme,
    hasTouch: mobile,
    reducedMotion: reducedMotion ? 'reduce' : 'no-preference',
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
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
  }
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  return { ctx, page, errors, badResponses };
}

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

/** 视口分区点按（合成 click）：画布 pane 的真输入管线在多套件同进程下有拦截怪癖
 *  （单进程单跑真鼠可达）——同一 click 监听合成直触等价覆盖 chrome 逻辑。 */
async function tapViewport(page, fx, fy, scrollerSel = '.epub-reader-viewport') {
  await page.evaluate(({ fx: x, fy: y, sel }) => {
    const v = document.querySelector('.canvas-tab-pane.active ' + sel);
    const r = v.getBoundingClientRect();
    v.dispatchEvent(new MouseEvent('click', {
      bubbles: true, cancelable: true,
      clientX: r.left + r.width * x, clientY: r.top + r.height * y,
    }));
  }, { fx, fy, sel: scrollerSel });
}


/** 画布打开链路会二次挂载（readFile 应答重渲，canvas.js 既有行为）——交互
 *  序列（点按/手势/菜单）不得跨重建：等元素身份稳定 300ms 再继续。 */
async function stabilizeReader(page) {
  await page.evaluate(() => {
    window.__p1stable = { el: null, at: 0 };
    window.__p1stabilityProbe = setInterval(() => {
      const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
      const s = window.__p1stable;
      if (v !== s.el) { s.el = v; s.at = Date.now(); }
    }, 50);
  });
  await page.waitForFunction(() => {
    const s = window.__p1stable;
    return s.el && document.querySelector('.canvas-tab-pane.active .epub-reader-viewport') === s.el && Date.now() - s.at > 300;
  }, { timeout: 10000, polling: 100 });
  await page.evaluate(() => clearInterval(window.__p1stabilityProbe));
}


/** 合成 touch 事件序列（Chromium Touch/TouchEvent 构造器可编程时间戳 → 可控速率）。 */
function touchScript(elGetter, points) {
  return (elSel, pts) => {
    const el = document.querySelector(elSel);
    if (!el) return 'no-el';
    const rect = el.getBoundingClientRect();
    const mk = (p) => new Touch({
      identifier: 1, target: el,
      clientX: rect.left + p.x, clientY: rect.top + p.y,
    });
    const fire = (type, list, t) => {
      const ev = new TouchEvent(type, {
        touches: type === 'touchend' ? [] : list.map(mk),
        targetTouches: type === 'touchend' ? [] : list.map(mk),
        changedTouches: list.map(mk),
        bubbles: true, cancelable: true,
      });
      el.dispatchEvent(ev);
      void t;
    };
    fire('touchstart', [pts[0]], pts[0].t);
    for (let i = 1; i < pts.length; i++) fire('touchmove', [pts[i]], pts[i].t);
    fire('touchend', [pts[pts.length - 1]], pts[pts.length - 1].t);
    return 'ok';
  };
}

/* ═══════════════════════════ 夹具 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const bookNcx = join(FIX_DIR, 'book-ncx.epub');
const bookNav = join(FIX_DIR, 'book-nav.epub');
const sampleTxt = join(FIX_DIR, 'sample.txt');
const partsTxt = join(FIX_DIR, 'parts.txt');
const docMd = join(FIX_DIR, 'doc.md');
makeBookNcx(bookNcx);
makeBookNav(bookNav);
writeFileSync(sampleTxt, Array.from({ length: 260 }, (_, i) => `TXT-LINE-${String(i + 1).padStart(3, '0')} 床前明月光，疑是地上霜。举头望明月，低头思故乡。`).join('\n') + '\n');
writeFileSync(partsTxt, Array.from({ length: 120 }, (_, i) => `PART1-LINE-${i + 1} 第一段内容。`).join('\n') + '\f' + Array.from({ length: 120 }, (_, i) => `PART2-LINE-${i + 1} 第二段内容。`).join('\n') + '\n');
writeFileSync(docMd, '# 文档主标题\n\n' +
  Array.from({ length: 30 }, (_, i) => `开篇段落 ${i + 1}。春眠不觉晓，处处闻啼鸟。`).join('\n\n') +
  '\n\n## 第二节\n\n' + Array.from({ length: 30 }, (_, i) => `第二节段落 ${i + 1}。夜来风雨声，花落知多少。`).join('\n\n') +
  '\n\n### 小节甲\n\n' + Array.from({ length: 30 }, (_, i) => `小节甲段落 ${i + 1}。`).join('\n\n') +
  '\n\n## 末节\n\n' + Array.from({ length: 30 }, (_, i) => `末节段落 ${i + 1}。`).join('\n\n') + '\n');
console.log(`[fixtures] ${bookNcx} ${bookNav} ${sampleTxt} ${partsTxt} ${docMd}`);

const browser = await chromium.launch();

/* ───────── A5 桌面底栏 + 角浮件让位 + ⋯ 菜单（无刷新项） ───────── */
console.log('\n== A5 桌面：底栏重排（[‹ ›] 滑杆 页码 Aa ☰ ⋯；刷新不在底栏） ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookNcx);
  await waitReader(h.page);
  await h.page.waitForSelector('#canvas-content > .canvas-refresh-toggle', { timeout: 10000 });

  const bar = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const nav = pane.querySelector('.epub-reader-nav');
    const vis = (el) => el && getComputedStyle(el).display !== 'none';
    const KEY = ['epub-prev', 'epub-next', 'epub-progress', 'epub-nav-info', 'epub-aa', 'epub-toc-btn', 'epub-more-btn'];
    const items = [...nav.children].filter((c) => c !== nav.querySelector('.epub-bar-menu') && vis(c)).map((c) => KEY.find((k) => c.classList.contains(k)) || c.tagName.toLowerCase());
    const floatBtn = document.querySelector('#canvas-content > .canvas-refresh-toggle');
    return {
      items,
      prev: vis(nav.querySelector('.epub-prev')),
      next: vis(nav.querySelector('.epub-next')),
      slider: !!nav.querySelector('.epub-progress-range'),
      info: (nav.querySelector('.epub-nav-info').textContent || '').trim(),
      refreshInBar: !!nav.querySelector('.canvas-refresh-toggle'),
      floatBtnBottom: floatBtn ? getComputedStyle(floatBtn).bottom : null,
      tickCount: nav.querySelectorAll('.epub-progress-tick').length,
      tipText: nav.querySelector('.epub-progress-tip').textContent,
    };
  });
  ok('A5 桌面底栏序：prev/next/slider/info/aa/tocBtn/moreBtn', JSON.stringify(bar.items) === JSON.stringify(['epub-prev', 'epub-next', 'epub-progress', 'epub-nav-info', 'epub-aa', 'epub-toc-btn', 'epub-more-btn']), JSON.stringify(bar.items));
  ok('A5 桌面 ‹ › 对直显', bar.prev && bar.next);
  ok('A5 滑杆在栏（input[type=range]）', bar.slider);
  ok('A5 页码文本 = Pg a/b · Ch i/n', /^Pg \d+\/\d+ · Ch \d+\/\d+$/.test(bar.info), bar.info);
  ok('A5 刷新钮不在底栏直显位', bar.refreshInBar === false);
  ok('A5 刷新角浮件抬到底栏上方（bottom 64px）', bar.floatBtnBottom === '64px', String(bar.floatBtnBottom));
  ok('A5 章节刻度来自 TOC（NCX 三章 = 3 刻度）', bar.tickCount === 3, `ticks=${bar.tickCount}`);
  ok('A5 常态位置 tooltip「章 · %」', /· \d+%$/.test(bar.tipText), bar.tipText);
  await shot(h.page, 'a5-desktop-bar.png');

  // ⋯ 菜单：桌面无刷新项、有翻页模式切换
  await h.page.click('.canvas-tab-pane.active .epub-more-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-bar-menu:not([hidden])');
  const more = await h.page.evaluate(() => ({
    refresh: !!document.querySelector('.canvas-tab-pane.active .epub-bar-menu [data-reader-refresh]'),
    modes: document.querySelectorAll('.canvas-tab-pane.active .epub-bar-menu [data-reader-mode]').length,
  }));
  ok('A5 桌面 ⋯ 菜单无刷新项（刷新维持面板角浮件）', more.refresh === false, JSON.stringify(more));
  ok('A5 ⋯ 菜单含翻页模式双档', more.modes === 2, `modes=${more.modes}`);
  await shot(h.page, 'a5-desktop-more-menu.png');
  ok('A5 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── A5 移动底栏：收 ‹、⋯ 含刷新、壳层刷新浮件隐藏 ───────── */
console.log('\n== A5 移动：底栏收 ‹（下一页交点右区/横滑）、⋯ 含刷新、角浮件隐藏 ==');
{
  const h = await boot(browser, { mobile: true });
  await openPath(h.page, bookNcx);
  await waitReader(h.page);
  const bar = await h.page.evaluate(() => {
    const nav = document.querySelector('.canvas-tab-pane.active .epub-reader-nav');
    const vis = (el) => el && getComputedStyle(el).display !== 'none';
    const KEY = ['epub-prev', 'epub-next', 'epub-progress', 'epub-nav-info', 'epub-aa', 'epub-toc-btn', 'epub-more-btn'];
    const items = [...nav.children].filter((c) => c !== nav.querySelector('.epub-bar-menu') && vis(c)).map((c) => KEY.find((k) => c.classList.contains(k)) || c.tagName.toLowerCase());
    const floatBtn = document.querySelector('#canvas-content > .canvas-refresh-toggle');
    return {
      items,
      nextDisplay: getComputedStyle(nav.querySelector('.epub-next')).display,
      info: nav.querySelector('.epub-nav-info').textContent.trim(),
      // 移动层画布是独立移动面：壳层刷新浮件宿于桌面 split 面（隐藏），
      // 生效可见性 = 盒面积（rect 宽 0 即不可见）。
      floatVisible: floatBtn ? (floatBtn.getBoundingClientRect().width > 0 && getComputedStyle(floatBtn).display !== 'none') : false,
      navPadBottom: getComputedStyle(nav).paddingBottom,
    };
  });
  ok('A5 移动底栏序：prev/slider/info/aa/tocBtn/moreBtn（六项，next 退场）', JSON.stringify(bar.items) === JSON.stringify(['epub-prev', 'epub-progress', 'epub-nav-info', 'epub-aa', 'epub-toc-btn', 'epub-more-btn']), JSON.stringify(bar.items));
  ok('A5 移动 › 直隐（display:none）', bar.nextDisplay === 'none');
  ok('A5 移动页码文本 = Ch i/n', /^Ch \d+\/\d+$/.test(bar.info), bar.info);
  ok('A5 移动壳层刷新浮件不可见（桌面 split 面隐藏；刷新退入 ⋯）', bar.floatVisible === false, String(bar.floatVisible));
  ok('A5 移动底栏让位 safe-area（padding-bottom ≥ 4px 含 env 项）', parseFloat(bar.navPadBottom) >= 4, bar.navPadBottom);

  // 移动阅读菜单含刷新行 + TOC 底弹形态
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  // 移动层真鼠标/触屏管线均被移动手势层吞（不派生 click——P0 移动断言亦
  // 只走 dock）；此处合成 click 直触 chrome 点按逻辑（输入管线另属移动层）。
  await h.page.evaluate(({ left, top, width, height }) => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    v.dispatchEvent(new MouseEvent('click', {
      bubbles: true, cancelable: true,
      clientX: left + width * 0.5, clientY: top + height * 0.55,
    }));
  }, rect);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  const menuMobile = await h.page.evaluate(() => ({
    refresh: !!document.querySelector('.canvas-tab-pane.active .epub-reader-menu [data-reader-refresh]'),
    toc: !!document.querySelector('.canvas-tab-pane.active .epub-reader-menu [data-reader-toc]'),
  }));
  ok('A5 移动阅读菜单含刷新行（设计稿 §2.3）', menuMobile.refresh === true, JSON.stringify(menuMobile));
  ok('A5 移动阅读菜单含目录入口', menuMobile.toc === true);
  await shot(h.page, 'a5-mobile-menu.png');
  await h.ctx.close();
}

/* ───────── TOC 抽屉：NCX 嵌套 + 章内锚 + nav.xhtml + 移动底弹 ───────── */
console.log('\n== TOC 抽屉：EPUB2 NCX 嵌套目录/章内锚定位/EPUB3 nav/移动底弹 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookNcx);
  await waitReader(h.page);
  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const toc = await h.page.evaluate(() => {
    const el = document.querySelector('.canvas-tab-pane.active .epub-toc');
    const items = [...el.querySelectorAll('.epub-toc-item')].map((b) => ({
      label: b.textContent.trim(), depth: b.dataset.depth, on: b.classList.contains('on'),
    }));
    return { count: items.length, items, side: getComputedStyle(el).borderRadius };
  });
  ok('TOC NCX 条目 = 4（三章 + 一章内二级）', toc.count === 4, JSON.stringify(toc.items));
  ok('TOC 嵌套深度落 data-depth（0/1）', toc.items[1].depth === '1', JSON.stringify(toc.items));
  ok('TOC 当前章激活态（末个 ≤ 当前章条目 .on = 第一章第二节）', toc.items[1].on === true && toc.items[2].on === false, JSON.stringify(toc.items.map((i) => i.on)));
  await shot(h.page, 'toc-desktop-drawer.png');

  // 点击「第二章」→ 跳章
  await h.page.click('.canvas-tab-pane.active .epub-toc-item:nth-child(3)');
  await h.page.waitForFunction(() => {
    const t = document.querySelector('.canvas-tab-pane.active .epub-chapter-title').textContent;
    return t.includes('第二章');
  }, { timeout: 5000 });
  ok('TOC 点击条目 → 跨章跳转生效', true);

  // 点击「第一章第二节」（章内锚）→ 回第一章且滚动到锚
  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  await h.page.click('.canvas-tab-pane.active .epub-toc-item:nth-child(2)');
  await h.page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const t = pane.querySelector('.epub-chapter-title').textContent;
    const v = pane.querySelector('.epub-reader-viewport');
    return t.includes('第一章') && v.scrollTop > 10;
  }, { timeout: 5000 });
  const anchorPos = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const v = pane.querySelector('.epub-reader-viewport');
    const anchor = pane.querySelector('.epub-reader-content [id="sec1-2"]');
    if (!anchor) return { scrollTop: v.scrollTop, rel: null };
    return { scrollTop: v.scrollTop, rel: Math.abs(anchor.getBoundingClientRect().top - v.getBoundingClientRect().top) };
  });
  ok('TOC 章内锚定位：回第一章 + 滚动至锚（锚贴视口顶 ±80px）', anchorPos.rel !== null && anchorPos.rel < 80, JSON.stringify(anchorPos));

  // EPUB3 nav.xhtml
  const h2 = await boot(browser);
  await openPath(h2.page, bookNav);
  await waitReader(h2.page);
  await h2.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h2.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const navToc = await h2.page.evaluate(() =>
    [...document.querySelectorAll('.canvas-tab-pane.active .epub-toc-item')].map((b) => b.textContent.trim()));
  ok('TOC EPUB3 nav.xhtml 条目 = 2（启程/归宿）', navToc.length === 2 && navToc[0] === '启程' && navToc[1] === '归宿', JSON.stringify(navToc));
  await shot(h2.page, 'toc-epub3-nav.png');
  await h2.ctx.close();

  // 移动底弹形态
  const hm = await boot(browser, { mobile: true });
  await openPath(hm.page, bookNcx);
  await waitReader(hm.page);
  await hm.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await hm.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const tocMobile = await hm.page.evaluate(() => {
    const el = document.querySelector('.canvas-tab-pane.active .epub-toc');
    const cs = getComputedStyle(el);
    return { radius: cs.borderRadius, bottom: cs.bottom, maxH: cs.maxHeight };
  });
  ok('TOC 移动底弹形态（上圆角 + bottom 0 + max-height 60%）', tocMobile.radius.startsWith('12px') && tocMobile.bottom === '0px' && tocMobile.maxH === '60%', JSON.stringify(tocMobile));
  await shot(hm.page, 'toc-mobile-sheet.png');
  ok('TOC 腿零 pageerror', h.errors.length === 0 && hm.errors.length === 0, [...h.errors, ...hm.errors].join(' | '));
  await hm.ctx.close();
  await h.ctx.close();
}

/* ───────── A6 进度条跳章：>50% 跨章 + 事件单调 ───────── */
console.log('\n== A6 进度条：拖至 >50% → 当前章变化，epub-page-change progress 单调 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookNcx);
  await waitReader(h.page);
  await h.page.evaluate(() => {
    window.__p1progress = [];
    const pane = document.querySelector('.canvas-tab-pane.active');
    pane.addEventListener('epub-page-change', (e) => window.__p1progress.push(e.detail.progress));
  });
  await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-progress-range');
    r.value = '700'; // >50% → floor(0.7×3) = 第三章
    r.dispatchEvent(new Event('input', { bubbles: true }));
    r.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await h.page.waitForFunction(() => {
    const t = document.querySelector('.canvas-tab-pane.active .epub-chapter-title').textContent;
    return t.includes('第三章');
  }, { timeout: 5000 });
  const seq = await h.page.evaluate(() => window.__p1progress);
  const monotone = seq.every((v, i) => i === 0 || v >= seq[i - 1] - 1e-9);
  ok('A6 拖至 70% → 落第三章', true);
  ok('A6 progress 单调不减且落点 ≈0.7', monotone && seq.length >= 1 && seq[seq.length - 1] > 0.6 && seq[seq.length - 1] < 0.85, JSON.stringify(seq));
  ok('A6 章起刻度落位（拖后 tooltip 更新）', /第三章|收束/.test(await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-progress-tip').textContent)));
  ok('A6 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── §4.1 双页夹翻页动效（txt 内核上断言：时长/缓动/双页/投影） ───────── */
console.log('\n== 翻页动效：240ms 双页夹（transition/双页层/页缘投影/滚动模式默认不扰） ==');
{
  const h = await boot(browser);
  await openPath(h.page, sampleTxt);
  await waitReader(h.page);
  await h.page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return v.scrollHeight > v.clientHeight * 2;
  }, { timeout: 10000 });
  await stabilizeReader(h.page);

  // 滚动模式（默认）：右区点按 = 平滑滚动翻页（P0 既定行为不扰）。
  // 真鼠点按（同 P0 A4）：合成 click 触发的 smooth scrollTo 在 headless
  // 不启动动画，真输入管线才驱动合成器滚动。
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  await h.page.mouse.click(rect.left + rect.width * 0.85, rect.top + rect.height * 0.5);
  let scrolled = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').scrollTop);
  if (scrolled <= 10) { await h.page.mouse.click(rect.left + rect.width * 0.85, rect.top + rect.height * 0.5); await sleep(400); scrolled = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').scrollTop); }
  const noStage = await h.page.evaluate(() => !document.querySelector('.canvas-tab-pane.active .epub-flip-stage'));
  ok('滚动模式默认：右区点按平滑滚动（无翻页舞台）', scrolled > 10 && noStage, `scrollTop=${scrolled}`);
  await h.page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    v.scrollTop = 0;
  });

  // 切翻页模式（⋯ 菜单），右区点按 → 双页夹舞台。
  await h.page.click('.canvas-tab-pane.active .epub-more-btn');
  await h.page.click('.canvas-tab-pane.active .epub-bar-menu [data-reader-mode="flip"]');
  const prefsMode = await h.page.evaluate(() => JSON.parse(localStorage.getItem('nf.reader.prefs') || '{}').mode);
  ok('翻页模式档持久化（nf.reader.prefs.mode=flip）', prefsMode === 'flip', String(prefsMode));

  await tapViewport(h.page, 0.85, 0.5);
  const stage = await h.page.evaluate(() => {
    const st = document.querySelector('.canvas-tab-pane.active .epub-flip-stage');
    if (!st) return null;
    const a = st.querySelector('.epub-flip-layer.a');
    const b = st.querySelector('.epub-flip-layer.b');
    const cs = getComputedStyle(b);
    return {
      layers: st.querySelectorAll('.epub-flip-layer').length,
      duration: cs.transitionDuration,
      timing: cs.transitionTimingFunction,
      shadow: !!b.querySelector('.epub-flip-shadow'),
    };
  });
  ok('翻页舞台 = 双页层（现页 + 邻页）', !!stage && stage.layers === 2, JSON.stringify(stage));
  ok('翻页时长 240ms', !!stage && stage.duration === '0.24s', stage && stage.duration);
  ok('翻页缓动 cubic-bezier(0.22, 0.61, 0.36, 1)', !!stage && /cubic-bezier\(0\.22, 0\.61, 0\.36, 1\)/.test(stage.timing), stage && stage.timing);
  ok('页缘 8px 投影元件在场', !!stage && stage.shadow === true);
  if (stage) await shot(h.page, 'flip-mid-animation.png');
  await h.page.waitForFunction(() => !document.querySelector('.canvas-tab-pane.active .epub-flip-stage'), { timeout: 5000 });
  const after = await h.page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return { scrollTop: v.scrollTop, clientHeight: v.clientHeight };
  });
  ok('翻页收束：scrollTop 整页推进（≈视口高 ±30%）', Math.abs(after.scrollTop - after.clientHeight) < after.clientHeight * 0.3, JSON.stringify(after));
  ok('翻页腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── A10 reduced-motion：0.01s 直切 ───────── */
console.log('\n== A10 reduced-motion：翻页动画时长 ≤0.01s（媒体查询降级） ==');
{
  const h = await boot(browser, { reducedMotion: true });
  await openPath(h.page, sampleTxt);
  await waitReader(h.page);
  await stabilizeReader(h.page);
  await h.page.click('.canvas-tab-pane.active .epub-more-btn');
  await h.page.click('.canvas-tab-pane.active .epub-bar-menu [data-reader-mode="flip"]');
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  await tapViewport(h.page, 0.85, 0.5);
  const dur = await h.page.evaluate(() => {
    const layer = document.querySelector('.canvas-tab-pane.active .epub-flip-layer');
    return layer ? parseFloat(getComputedStyle(layer).transitionDuration) : null;
  });
  ok('A10 reduced-motion 下翻页层时长 ≤0.01s', dur !== null && dur <= 0.01, String(dur));
  await h.page.waitForFunction(() => !document.querySelector('.canvas-tab-pane.active .epub-flip-stage'), { timeout: 5000 });
  const advanced = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').scrollTop);
  ok('A10 直切后页面前进（功能不丢）', advanced > 10, `scrollTop=${advanced}`);
  await h.ctx.close();
}

/* ───────── touch 横滑：慢速小位移回弹 / 快速率判定完成 ───────── */
console.log('\n== touch 横滑跟手：速率判定（慢→回弹 / 快→完成） ==');
{
  const h = await boot(browser, { mobile: true });
  await openPath(h.page, sampleTxt);
  await waitReader(h.page);
  await stabilizeReader(h.page);
  await h.page.click('.canvas-tab-pane.active .epub-more-btn');
  await h.page.click('.canvas-tab-pane.active .epub-bar-menu [data-reader-mode="flip"]');
  await h.page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return v.scrollHeight > v.clientHeight * 2;
  }, { timeout: 10000 });
  // 真实时间间隔分派：chrome 速率采样用 Date.now()，事件须按真实节奏走。
  const vp = '.canvas-tab-pane.active .epub-reader-viewport';
  const fireTouch = (type, pt) => h.page.evaluate(({ sel, type: t, pt: p }) => {
    const el = document.querySelector(sel);
    const rect = el.getBoundingClientRect();
    const touch = new Touch({ identifier: 1, target: el, clientX: rect.left + p.x, clientY: rect.top + p.y });
    el.dispatchEvent(new TouchEvent(t, {
      touches: t === 'touchend' ? [] : [touch],
      targetTouches: t === 'touchend' ? [] : [touch],
      changedTouches: [touch],
      bubbles: true, cancelable: true,
    }));
  }, { sel: vp, type, pt });

  // 慢速小位移（dx=48 < 1/4 宽 97.5，尾窗速率 10px/200ms=0.05px/ms）→ 回弹。
  const w = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').clientWidth);
  await fireTouch('touchstart', { x: 300, y: 300 });
  for (const p of [{ x: 280, y: 300 }, { x: 262, y: 301 }, { x: 252, y: 301 }]) { await sleep(200); await fireTouch('touchmove', p); }
  await sleep(200);
  await fireTouch('touchend', { x: 252, y: 301 });
  await h.page.waitForFunction(() => !document.querySelector('.canvas-tab-pane.active .epub-flip-stage'), { timeout: 5000 });
  const rebounded = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader-viewport').scrollTop);
  ok('触滑慢速小位移 → 回弹不翻页（scrollTop 仍 ~0）', rebounded < 10, `scrollTop=${rebounded} w=${w}`);

  // 快速率（尾窗 40ms 内 28px = 0.7px/ms > 0.3；位移 40 < 1/4 宽——单速率触发）→ 完成翻页。
  // 整序列页内分派：chrome 速率采样用页内 Date.now()，Node 侧 sleep +
  // evaluate 往返会放大时间戳间隔把速率窗口稀释（>0.3 判定滑落到 false）。
  await h.page.evaluate(async (sel) => {
    const el = document.querySelector(sel);
    const rect = el.getBoundingClientRect();
    const mk = (x, y) => new Touch({ identifier: 1, target: el, clientX: rect.left + x, clientY: rect.top + y });
    const fire = (type, pts) => el.dispatchEvent(new TouchEvent(type, {
      touches: type === 'touchend' ? [] : pts,
      targetTouches: type === 'touchend' ? [] : pts,
      changedTouches: pts,
      bubbles: true, cancelable: true,
    }));
    const wait = (ms) => new Promise((r) => setTimeout(r, ms));
    fire('touchstart', [mk(300, 300)]);
    await wait(300); fire('touchmove', [mk(296, 300)]);
    await wait(30); fire('touchmove', [mk(284, 300)]);
    await wait(40); fire('touchmove', [mk(260, 300)]);
    await wait(30);
    fire('touchend', [mk(260, 300)]);
  }, vp);
  await h.page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    return !document.querySelector('.canvas-tab-pane.active .epub-flip-stage') && v.scrollTop > 10;
  }, { timeout: 5000 });
  ok('触滑快速率判定 → 完成翻页（scrollTop 前进）', true);
  ok('触滑腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── 统一 chrome 套 txt：主题/Aa/\f 分章 ───────── */
console.log('\n== 统一内核套 txt：转换渲染（\f 分章 + 主题档 + 段落白空间留） ==');
{
  const h = await boot(browser);
  await openPath(h.page, sampleTxt);
  await waitReader(h.page);
  const txt = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const c = pane.querySelector('.epub-reader-content');
    return {
      format: c.dataset.format,
      paras: c.querySelectorAll('p').length,
      firstLine: c.querySelector('p').textContent.slice(0, 12),
      theme: pane.querySelector('.epub-reader').dataset.epubTheme,
      aaVisible: getComputedStyle(pane.querySelector('.epub-aa')).display !== 'none',
      tocBtnHidden: pane.querySelector('.epub-toc-btn').hidden,
    };
  });
  ok('txt 进统一 chrome（data-format=text）', txt.format === 'text', JSON.stringify(txt));
  ok('txt 行 → 段落（260 行 = 260 段，textContent 防 XSS）', txt.paras === 260 && txt.firstLine.startsWith('TXT-LINE-001'), JSON.stringify(txt));
  ok('txt 默认纸质主题（亮面板 auto → paper）', txt.theme === 'paper', txt.theme);
  ok('txt Aa 直显（themeable）/ 单章 ☰ 隐藏', txt.aaVisible === true && txt.tocBtnHidden === true, JSON.stringify(txt));

  // 切暗夜档 → 对比度口径沿用 P0（epub 腿同款主题表）
  await tapViewport(h.page, 0.5, 0.55);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  await h.page.click('.canvas-tab-pane.active [data-epub-theme-pick="dark"]');
  const dark = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-reader').dataset.epubTheme);
  ok('txt 主题三档可切（暗夜）', dark === 'dark', dark);

  // \f 分章：parts.txt 两节 → ☰ 显 + TOC 两项 + 拖杆跨节
  const h2 = await boot(browser);
  await openPath(h2.page, partsTxt);
  await waitReader(h2.page);
  const parts = await h2.page.evaluate(() => ({
    info: document.querySelector('.canvas-tab-pane.active .epub-nav-info').textContent,
    tocHidden: document.querySelector('.canvas-tab-pane.active .epub-toc-btn').hidden,
  }));
  ok('txt \\f 分章：Ch 1/2，☰ 显', /Ch 1\/2/.test(parts.info) && parts.tocHidden === false, JSON.stringify(parts));
  await h2.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h2.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const partItems = await h2.page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .epub-toc-item')].map((b) => b.textContent.trim()));
  ok('txt TOC = 两节（第 1 节/第 2 节）', partItems.length === 2 && partItems[1].includes('2'), JSON.stringify(partItems));
  await shot(h2.page, 'txt-toc-parts.png');
  await h2.ctx.close();
  await h.ctx.close();
}

/* ───────── 统一 chrome 套 md：TOC 抽屉/滑杆/fab gate-off ───────── */
console.log('\n== 统一内核套 md：TOC 抽屉（标题锚）+ 滑杆 + 阅读菜单开 fab gate-off ==');
{
  const h = await boot(browser);
  await openPath(h.page, docMd);
  await waitReader(h.page);
  const md = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return {
      aaHidden: pane.querySelector('.epub-aa').hidden,
      slider: !!pane.querySelector('.epub-progress-range'),
      tocVisible: !pane.querySelector('.epub-toc-btn').hidden,
      mdScrollIntact: !!pane.querySelector('.canvas-md-scroll.canvas-viewer-safe-top .canvas-md-viewer'),
      info: pane.querySelector('.epub-nav-info').textContent,
    };
  });
  ok('md 包统一 chrome：滑杆 + ☰ 显', md.slider === true && md.tocVisible === true, JSON.stringify(md));
  ok('md 排版二期：Aa 隐藏（主题/字号档不接管）', md.aaHidden === true);
  ok('md 正文渲染不动（canvas-md-scroll 原链保留）', md.mdScrollIntact === true);
  ok('md 页码 = Pg a/b（单章）', /^Pg \d+\/\d+ · Ch 1\/1$/.test(md.info), md.info);

  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const mdToc = await h.page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .epub-toc-item')].map((b) => b.textContent.trim()));
  ok('md TOC = h1-h3 标题四项', mdToc.length === 4 && mdToc[0] === '文档主标题' && mdToc[3] === '末节', JSON.stringify(mdToc));
  await h.page.click('.canvas-tab-pane.active .epub-toc-item:nth-child(3)');
  await h.page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = pane.querySelector('.canvas-md-scroll');
    const flash = pane.querySelector('.nf-jump-flash');
    return sc.scrollTop > 50 && !!flash;
  }, { timeout: 5000 });
  ok('md TOC 点击 → 标题锚滚动 + 落点高亮', true);
  await shot(h.page, 'md-toc-jump.png');

  // 进度条拖 → md 滚动（单章 = 滚动位置空间）
  await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .epub-progress-range');
    r.value = '0';
    r.dispatchEvent(new Event('input', { bubbles: true }));
    r.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await h.page.waitForFunction(() => document.querySelector('.canvas-tab-pane.active .canvas-md-scroll').scrollTop < 10, { timeout: 5000 });
  ok('md 滑杆拖回 0% → 滚回首部', true);

  // 阅读菜单开 ⇒ ask-fab gate-off（设计稿 §2.2 联动）
  const fabState = await h.page.evaluate(() => {
    const fab = document.querySelector('.ask-fab');
    return { exists: !!fab, gated: fab ? fab.classList.contains('gate-off') : null };
  });
  ok('md 卡 ask-fab 在场且未 gate-off（菜单关）', fabState.exists && fabState.gated === false, JSON.stringify(fabState));
  const rect = await h.page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active .canvas-md-scroll').getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  await tapViewport(h.page, 0.5, 0.55, '.canvas-md-scroll');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  const gated = await h.page.evaluate(() => document.querySelector('.ask-fab').classList.contains('gate-off'));
  ok('阅读菜单开 ⇒ ask-fab gate-off', gated === true);
  await shot(h.page, 'md-menu-fab-gated.png');
  await h.page.keyboard.press('Escape');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu[hidden]', { state: 'attached' });
  const ungated = await h.page.evaluate(() => document.querySelector('.ask-fab').classList.contains('gate-off'));
  ok('菜单关 ⇒ gate-off 复位', ungated === false);
  ok('md 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
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
