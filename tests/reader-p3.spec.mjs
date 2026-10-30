// reader-p3.spec.mjs — 阅读器 P3 生态批验收（设计稿 20261030_181655 §7.2 A12 +
// §3.2 mobi/azw3 解析路线 + §4.2 引擎通用标注面）。
//
// 覆盖：
//   A12  未加密 .mobi 打开出正文与目录（h2 伪章节 → TOC； PalmDB+PalmDOC 解析）
//        .azw3 打开出正文与目录（KF8 抽内嵌 EPUB → 现有 EPUB 管线零改动复用，
//        图片 blob 改写面同链生效）
//        加密 .mobi 落「仅下载」可见降级（下载钮真出下载事件）
//   §3.2 PalmDOC LZ77 纯函数钉扎（字面 run + 回溯码逐字节对账）
//   §4.2 标注面：划选浮动条（四色 + 写笔记）→ 高亮回渲 → WS reader.anno 落库
//        → 整页重开后高亮回渲（quote 主键重定位）→ 笔记抽屉按章分组 + 点击
//        回跳定位 → 原文改动后 quote 失配 → offset 模糊找回灰显（不丢数据）。
//
// 被测真实链路（前端零替身，mock 档）：同 reader-p0/p1.spec.mjs。
// 夹具：手写 stored zip + 手写 PalmDB/MOBI 记录容器 + 手写 LZ77 编码器。
//
// 运行：node tests/reader-p3.spec.mjs
// 可选 env：PORT=8149、SHOTS_DIR=<dir>

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
const PORT = Number(process.env.PORT || 8149);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'nf-reader-p3-fixtures');
const ANNO_FILE = join(FIX_DIR, 'anno-store.json'); // WS mock 的标注库落点（真文件往返）
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/' + new Date().toISOString().replace(/[-:]/g, '').slice(0, 14).replace(/(\d{8})(\d{6})/, '$1_$2') + '_reader-p3');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具：手写 stored zip（同 P0/P1 批 CRC32 自算） ───────────── */

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

function storedZipBytes(entries) {
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
  return Buffer.concat([...chunks, cd, eocd]);
}

const writeStoredZip = (out, entries) => writeFileSync(out, storedZipBytes(entries));

/* ───────────── 夹具：EPUB（标注面用，两章可滚） ───────────── */

const NS_OPF = 'http://www.idpf.org/2007/opf';
const XHTML = 'http://www.w3.org/1999/xhtml';
const ANNO_POEM = '白日依山尽，黄河入海流。欲穷千里目，更上一层楼。';

/** ch1 段落（可选变异体：改 quote 第 9 字「千」→ quote 失配、
 *  8 字前缀仍配 = offset 模糊找回灰显场景）。 */
function annoChapter(title, variant, n) {
  const paras = [];
  for (let i = 1; i <= n; i++) {
    const poem = variant ? '白日依山尽，黄河入海流。欲穷改里目，更上一层楼。' : ANNO_POEM;
    paras.push(`<p>${title} 第${i}段 ${poem} 填充文字。</p>`);
  }
  return `<?xml version="1.0"?>
<html xmlns="${XHTML}"><head><title>${title}</title></head><body>
<h1>${title}</h1>
${paras.join('\n')}
</body></html>`;
}

function makeAnnoBook(out, { variant = false } = {}) {
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
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>`;
  const ncx = `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head></head><docTitle><text>ANNO-BOOK</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>第一章</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
    <navPoint id="n2" playOrder="2"><navLabel><text>第二章</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
  </navMap>
</ncx>`;
  writeStoredZip(out, [
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/toc.ncx', ncx],
    ['OEBPS/text/ch1.xhtml', annoChapter('第一章', variant, 30)],
    ['OEBPS/text/ch2.xhtml', annoChapter('第二章', false, 30)],
  ]);
}

/* ───────────── 夹具：PalmDB/MOBI 容器 ───────────── */

const FOURCC_LE = (s) => (s.charCodeAt(0) | (s.charCodeAt(1) << 8) | (s.charCodeAt(2) << 16) | (s.charCodeAt(3) << 24)) >>> 0;

/** 手写 LZ77 编码器（字面 run + 短窗回溯，解码器逐字节对账用）。 */
function lz77Compress(u8) {
  const out = [];
  let i = 0;
  const n = u8.length;
  while (i < n) {
    let bestLen = 0;
    let bestDist = 0;
    const maxDist = Math.min(256, i);
    for (let d = 1; d <= maxDist; d++) {
      let l = 0;
      while (l < 66 && i + l < n && u8[i + l] === u8[i - d + l]) l++;
      if (l >= 4 && l > bestLen) { bestLen = l; bestDist = d; }
    }
    if (bestLen >= 4) {
      out.push(0x80 | (bestLen - 3), bestDist - 1);
      i += bestLen;
      continue;
    }
    let run = Math.min(64, n - i);
    out.push(0x40 + (run - 1));
    for (let k = 0; k < run; k++) out.push(u8[i + k]);
    i += run;
  }
  return Buffer.from(out);
}

/** 手写 MOBI：PalmDB 头 + record0（PalmDOC 16B + MOBI 0xE8 + EXTH 作者 + 书名）
 *  + 文本记录（compression 1=stored / 2=LZ77；encryption≠0 = 加密夹具）。 */
function makeMobiBytes({ html, title = 'MOBI-书', author = '', encryption = 0, compression = 1 }) {
  const htmlBuf = Buffer.from(html, 'utf8');
  // record 0
  const palm = Buffer.alloc(16);
  palm.writeUInt16LE(compression, 0);
  palm.writeUInt32LE(htmlBuf.length, 4); // textLength
  palm.writeUInt16LE(1, 8); // textRecordCount（stored/LZ77 都单记录，截断按 textLength）
  palm.writeUInt16LE(4096, 10); // recordSize
  palm.writeUInt16LE(encryption, 12);
  const mobi = Buffer.alloc(0xE8);
  mobi.writeUInt32LE(FOURCC_LE('MOBI'), 0);
  mobi.writeUInt32LE(0xE8, 4); // headerLen
  mobi.writeUInt32LE(2, 8); // type
  mobi.writeUInt32LE(65001, 12); // encoding utf-8
  mobi.writeUInt32LE(0x40, 0x80); // exthFlags: EXTH present
  const authorBuf = Buffer.from(author, 'utf8');
  const exth = Buffer.alloc(12 + 8 + authorBuf.length);
  exth.writeUInt32LE(FOURCC_LE('EXTH'), 0);
  exth.writeUInt32LE(exth.length, 4);
  exth.writeUInt32LE(1, 8); // count
  exth.writeUInt32LE(100, 12); // author
  exth.writeUInt32LE(8 + authorBuf.length, 16);
  authorBuf.copy(exth, 20);
  const nameBuf = Buffer.from(title, 'utf8');
  const rec0 = Buffer.concat([palm, mobi, exth, nameBuf]);
  // full name 偏移 = PalmDOC(16) + MOBI(0xE8) + EXTH —— 相对 record0 起点
  rec0.writeUInt32LE(16 + 0xE8 + exth.length, 16 + 0x54);
  rec0.writeUInt32LE(nameBuf.length, 16 + 0x58);

  const textRec = compression === 2 ? lz77Compress(htmlBuf) : htmlBuf;
  const records = [rec0, textRec];

  // PalmDB 头（78B）+ 记录表
  const head = Buffer.alloc(78);
  Buffer.from('p3-fixture', 'utf8').copy(head, 0);
  head.writeUInt32LE(FOURCC_LE('BOOK'), 60);
  head.writeUInt32LE(FOURCC_LE('MOBI'), 64);
  head.writeUInt16LE(records.length, 76);
  const table = Buffer.alloc(records.length * 8);
  let off = 78 + table.length;
  records.forEach((rec, i) => {
    table.writeUInt32LE(off, i * 8);
    off += rec.length;
  });
  return Buffer.concat([head, table, ...records]);
}

const MOBI_HTML = `<html><body>
<h2>第一章 开端</h2>
${Array.from({ length: 12 }, (_, i) => `<p>mobi 第一段 ${i + 1} ${ANNO_POEM}</p>`).join('\n')}
<h2>第二章 深入</h2>
${Array.from({ length: 12 }, (_, i) => `<p>mobi 第二段 ${i + 1} ${ANNO_POEM}</p>`).join('\n')}
<h2>第三章 收束</h2>
${Array.from({ length: 12 }, (_, i) => `<p>mobi 第三段 ${i + 1} ${ANNO_POEM}</p>`).join('\n')}
</body></html>`;

/* ───────────── 夹具：azw3 = MOBI 前缀 + BOUNDARY + 内嵌 EPUB zip ───────────── */

const TINY_PNG = Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000001f15c48900000000d49444154789c626001000000ffff03000006000557bfabd40000000049454e44ae426082', 'hex');

function makeAzw3Bytes() {
  const container = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;
  const opf = `<?xml version="1.0"?>
<package xmlns="${NS_OPF}" version="2.0" unique-identifier="id">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="pic" href="images/pic.png" media-type="image/png"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>`;
  const ncx = `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head></head><docTitle><text>AZW3-书</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>KF8 第一章</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
    <navPoint id="n2" playOrder="2"><navLabel><text>KF8 第二章</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
  </navMap>
</ncx>`;
  const ch = (t) => `<?xml version="1.0"?>
<html xmlns="${XHTML}"><head><title>${t}</title></head><body>
<h1>${t}</h1>
<p><img src="../images/pic.png" alt="p"/></p>
${Array.from({ length: 10 }, (_, i) => `<p>${t} 段 ${i + 1} ${ANNO_POEM}</p>`).join('\n')}
</body></html>`;
  const zip = storedZipBytes([
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/toc.ncx', ncx],
    ['OEBPS/images/pic.png', TINY_PNG],
    ['OEBPS/text/ch1.xhtml', ch('KF8 第一章')],
    ['OEBPS/text/ch2.xhtml', ch('KF8 第二章')],
  ]);
  const prefix = Buffer.from('BOOKMOBI-prefix-garbage-BOUNDARY!!!\0'.repeat(4), 'utf8');
  return Buffer.concat([prefix, zip]);
}

/* ───────────── 静态服务 + 替身（同 P0/P1 批语义） ───────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
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
    return route.fulfill({ status: 200, headers: { 'Content-Type': 'application/octet-stream' }, body });
  } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
}

const EXT_ITEM = {
  md: 'markdown', js: 'code', json: 'code', png: 'image', pdf: 'pdf', epub: 'epub',
  txt: 'code', mobi: 'mobi', azw3: 'mobi',
};
const BINARY = new Set(['png', 'pdf', 'epub', 'mobi', 'azw3']);
const MAX_READ = 100 * 1024 * 1024;

async function boot(browser, opts = {}) {
  const ctx = await browser.newContext({
    colorScheme: 'light',
    acceptDownloads: true,
    viewport: { width: 1600, height: 1000 },
    ...opts,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
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
      if (m.type === 'reader.anno') {
        // 标注库 mock：真文件往返（重开整页后 persistence 断言的数据源）。
        if (m.action === 'load') {
          let content = null;
          try { content = readFileSync(ANNO_FILE, 'utf8'); } catch { /* 缺件 = 空库 */ }
          return ws.send(JSON.stringify({ type: 'readerAnno', reqId: m.reqId, ok: true, content }));
        }
        if (m.action === 'save') {
          writeFileSync(ANNO_FILE, String(m.content || ''));
          return ws.send(JSON.stringify({ type: 'readerAnno', reqId: m.reqId, ok: true }));
        }
        return;
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
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  return { ctx, page, errors };
}

async function openPath(page, absPath, refresh = false) {
  await page.evaluate(({ abs, refresh }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + abs, itemType: '', content: '', absPath: abs, size: 0, title: abs.split('/').pop(), refresh },
    }));
  }, { abs: absPath, refresh });
}

async function shot(page, name) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  const out = join(SHOTS_DIR, name);
  await page.screenshot({ path: out, fullPage: false });
  console.log(`  [shot] ${out}`);
  return out;
}

const waitReader = (page) => page.waitForSelector('.canvas-tab-pane.active .epub-reader', { timeout: 20000 });

async function tapViewport(page, fx, fy) {
  await page.evaluate(({ fx: x, fy: y }) => {
    const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
    const r = v.getBoundingClientRect();
    v.dispatchEvent(new MouseEvent('click', {
      bubbles: true, cancelable: true,
      clientX: r.left + r.width * x, clientY: r.top + r.height * y,
    }));
  }, { fx, fy });
}

/** 画布打开链路二次挂载稳定探针（同 P1 批纪律）。 */
async function stabilizeReader(page) {
  await page.evaluate(() => {
    window.__p3stable = { el: null, at: 0 };
    window.__p3stabilityProbe = setInterval(() => {
      const v = document.querySelector('.canvas-tab-pane.active .epub-reader-viewport');
      const s = window.__p3stable;
      if (v !== s.el) { s.el = v; s.at = Date.now(); }
    }, 50);
  });
  await page.waitForFunction(() => {
    const s = window.__p3stable;
    return s.el && document.querySelector('.canvas-tab-pane.active .epub-reader-viewport') === s.el && Date.now() - s.at > 300;
  }, { timeout: 10000, polling: 100 });
  await page.evaluate(() => clearInterval(window.__p3stabilityProbe));
}

/** 章内选定指定串（TreeWalker 找文本节点 → Range → mouseup 唤条）。 */
async function selectText(page, needle, len = 5) {
  return page.evaluate(({ needle, len }) => {
    const content = document.querySelector('.canvas-tab-pane.active .epub-reader-content');
    if (!content) return 'no-content';
    const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = walker.nextNode())) {
      const idx = n.nodeValue.indexOf(needle);
      if (idx < 0) continue;
      const range = document.createRange();
      range.setStart(n, idx);
      range.setEnd(n, idx + len);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      const scroller = content.closest('.epub-reader-viewport');
      scroller.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
      return 'ok';
    }
    return 'not-found';
  }, { needle, len });
}

/** 等标注库文件含某串（flush 防抖 + WS 往返的落盘点对账）。 */
async function waitStoreContains(needle, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const text = readFileSync(ANNO_FILE, 'utf8');
      if (text.includes(needle)) return true;
    } catch { /* 未落盘 */ }
    await sleep(150);
  }
  return false;
}

/* ═══════════════════════════ 夹具 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const bookAnno = join(FIX_DIR, 'anno-book.epub');
const mobiPlain = join(FIX_DIR, 'book.mobi');
const mobiLz = join(FIX_DIR, 'book-lz.mobi');
const mobiEnc = join(FIX_DIR, 'book-enc.mobi');
const azw3 = join(FIX_DIR, 'book.azw3');
makeAnnoBook(bookAnno, { variant: false });
writeFileSync(mobiPlain, makeMobiBytes({ html: MOBI_HTML, title: 'MOBI-书', author: 'P3作者' }));
writeFileSync(mobiLz, makeMobiBytes({ html: MOBI_HTML, compression: 2 }));
writeFileSync(mobiEnc, makeMobiBytes({ html: MOBI_HTML, encryption: 2 }));
writeFileSync(azw3, makeAzw3Bytes());
console.log(`[fixtures] ${bookAnno} ${mobiPlain} ${mobiLz} ${mobiEnc} ${azw3}`);

const browser = await chromium.launch();

/* ───────── §3.2 纯函数钉扎：LZ77 解码 + PalmDB + KF8 边界扫描 ───────── */
console.log('\n== §3.2 解析纯函数：LZ77 逐字节对账 / PalmDB 记录表 / 加密识别 ==');
{
  const h = await boot(browser);
  const unit = await h.page.evaluate(async () => {
    const m = await import('/js/viewers/mobi.js');
    const enc = new TextEncoder();
    // 字面 + 回溯混合源文（重复段触发 0x80 回溯码）。
    const src = ('ABCD'.repeat(80)) + ' end';
    const srcU8 = enc.encode(src);
    // 用夹具同款编码器压一遍（页内复制实现，保持 spec 自包含）。
    const lz = (u8) => {
      const out = []; let i = 0; const n = u8.length;
      while (i < n) {
        let bl = 0, bd = 0;
        for (let d = 1; d <= Math.min(256, i); d++) {
          let l = 0;
          while (l < 66 && i + l < n && u8[i + l] === u8[i - d + l]) l++;
          if (l >= 4 && l > bl) { bl = l; bd = d; }
        }
        if (bl >= 4) { out.push(0x80 | (bl - 3), bd - 1); i += bl; continue; }
        const run = Math.min(64, n - i);
        out.push(0x40 + (run - 1));
        for (let k = 0; k < run; k++) out.push(u8[i + k]);
        i += run;
      }
      return Uint8Array.from(out);
    };
    const compressed = lz(srcU8);
    const decoded = new TextDecoder().decode(m.palmDocLz77Decode(compressed));
    return {
      roundtrip: decoded === src,
      compressedSmaller: compressed.length < srcU8.length,
      hasFns: typeof m.palmDocLz77Decode === 'function' && typeof m.parsePalmDb === 'function' && typeof m.findEmbeddedZip === 'function' && typeof m.parseMobiBook === 'function',
    };
  });
  ok('LZ77 解码逐字节对账（压后 < 原文且回环一致）', unit.roundtrip && unit.compressedSmaller, JSON.stringify(unit));
  ok('解析面四纯函数全部导出', unit.hasFns === true);

  const encCheck = await h.page.evaluate(async (b64) => {
    const m = await import('/js/viewers/mobi.js');
    const u8 = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    return m.parseMobiBook(u8).ok === false;
  }, readFileSync(mobiEnc).toString('base64'));
  ok('加密 mobi：parseMobiBook 判 encrypted（不进解析）', encCheck === true);
  await h.ctx.close();
}

/* ───────── A12a：未加密 .mobi 打开出正文与目录 ───────── */
console.log('\n== A12a：.mobi（stored PalmDOC）正文 + h2 伪章节 TOC ==');
{
  const h = await boot(browser);
  await openPath(h.page, mobiPlain);
  await waitReader(h.page);
  await stabilizeReader(h.page);
  const info = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return {
      format: pane.querySelector('.epub-reader-content').dataset.format,
      nav: pane.querySelector('.epub-nav-info').textContent,
      tocBtnHidden: pane.querySelector('.epub-toc-btn').hidden,
      aaVisible: getComputedStyle(pane.querySelector('.epub-aa')).display !== 'none',
      bodyText: (pane.querySelector('.epub-reader-content').textContent || '').slice(0, 40),
    };
  });
  ok('mobi 进统一 chrome（data-format=mobi）', info.format === 'mobi', info.format);
  ok('mobi 三章（Ch 1/3）', /Ch 1\/3/.test(info.nav), info.nav);
  ok('mobi 目录钮在场（TOC 由伪章节题供给）', info.tocBtnHidden === false);
  ok('mobi 主题/Aa 面在场（themeable）', info.aaVisible === true);
  ok('mobi 正文渲染（PalmDOC stored → HTML）', info.bodyText.includes('mobi 第一段'), info.bodyText);
  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  const toc = await h.page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane.active .epub-toc-item')].map((b) => b.textContent.trim()));
  ok('mobi TOC = 三章（第一章 开端/第二章 深入/第三章 收束）', toc.length === 3 && toc[0].includes('第一章') && toc[2].includes('第三章'), JSON.stringify(toc));
  await shot(h.page, 'mobi-reader-toc.png');
  ok('A12a 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── §3.2：LZ77 压缩 .mobi 端到端 ───────── */
console.log('\n== §3.2：LZ77 压缩 .mobi 端到端（PalmDOC compression=2） ==');
{
  const h = await boot(browser);
  await openPath(h.page, mobiLz);
  await waitReader(h.page);
  const nav = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-nav-info').textContent);
  const text = await h.page.evaluate(() => (document.querySelector('.canvas-tab-pane.active .epub-reader-content').textContent || '').slice(0, 30));
  ok('LZ77 mobi 三章在位', /Ch 1\/3/.test(nav), nav);
  ok('LZ77 mobi 正文正确解压', text.includes('mobi 第一段'), text);
  ok('LZ77 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── A12b：.azw3 打开出正文与目录（EPUB 管线复用） ───────── */
console.log('\n== A12b：.azw3（KF8 内嵌 EPUB）→ 现有 EPUB 渲染管线 ==');
{
  const h = await boot(browser);
  await openPath(h.page, azw3);
  await waitReader(h.page);
  await stabilizeReader(h.page);
  const state = await h.page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return {
      nav: pane.querySelector('.epub-nav-info').textContent,
      chapter: pane.querySelector('.epub-chapter-title').textContent,
      imgBlob: !!pane.querySelector('.epub-reader-content img[src^="blob:"]'),
      tocBtnHidden: pane.querySelector('.epub-toc-btn').hidden,
    };
  });
  ok('azw3 进 EPUB 管线（两章 Pg/Ch + 章题）', /Ch 1\/2/.test(state.nav) && state.chapter.includes('KF8 第一章'), JSON.stringify(state));
  ok('azw3 图片走 blob 改写面（P0 根治面同链生效）', state.imgBlob === true);
  ok('azw3 目录钮在场', state.tocBtnHidden === false);
  await shot(h.page, 'azw3-reader.png');
  ok('A12b 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── A12c：加密 .mobi 落「仅下载」 ───────── */
console.log('\n== A12c：加密 .mobi → 仅下载降级面 ==');
{
  const h = await boot(browser);
  await openPath(h.page, mobiEnc);
  await h.page.waitForSelector('.canvas-tab-pane.active [data-nf-download]', { timeout: 20000 });
  const degrade = await h.page.evaluate(() => ({
    msg: document.querySelector('.canvas-tab-pane.active .canvas-error div').textContent,
    reader: !!document.querySelector('.canvas-tab-pane.active .epub-reader'),
  }));
  ok('加密降级：不给解析（无 .epub-reader）', degrade.reader === false);
  ok('加密降级：人话文案（i18n 键）', degrade.msg.includes('加密'), degrade.msg);
  const dl = h.page.waitForEvent('download', { timeout: 10000 });
  await h.page.click('.canvas-tab-pane.active [data-nf-download]');
  const download = await dl;
  ok('下载钮真出下载事件（文件名 = 原书）', (download.suggestedFilename() || '') === 'book-enc.mobi', download.suggestedFilename());
  ok('A12c 腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();
}

/* ───────── §4.2：标注面（划选 → 四色 → 落库 → 重开回渲 → 抽屉回跳 → 灰显） ───────── */
console.log('\n== §4.2 标注面：划选浮动条 / 高亮落库 / 重开回渲 / 抽屉回跳 / 灰显 ==');
{
  const h = await boot(browser);
  await openPath(h.page, bookAnno);
  await waitReader(h.page);
  await stabilizeReader(h.page);

  // 阅读菜单含笔记行
  await tapViewport(h.page, 0.5, 0.55);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  ok('阅读菜单含笔记行（data-reader-anno）', await h.page.evaluate(() => !!document.querySelector('.canvas-tab-pane.active [data-reader-anno]')));
  await h.page.keyboard.press('Escape');

  // 划选 → 浮动条（四色 + 写笔记）。选 9 字跨「欲穷千」——灰显阶段原文在
  // 第 9 字后插字，正好制造 quote 失配 + offset 模糊找回场景。
  ok('选区直中目标句', (await selectText(h.page, '黄河入海流', 9)) === 'ok');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-anno-bar:not([hidden])');
  const bar = await h.page.evaluate(() => ({
    swatches: document.querySelectorAll('.canvas-tab-pane.active .epub-anno-bar [data-anno-color]').length,
    note: !!document.querySelector('.canvas-tab-pane.active .epub-anno-bar [data-anno-note]'),
  }));
  ok('浮动条四色 + 写笔记', bar.swatches === 4 && bar.note === true, JSON.stringify(bar));
  await shot(h.page, 'anno-bar.png');

  // 黄色落笔 → mark 回渲
  await h.page.click('.canvas-tab-pane.active .epub-anno-bar [data-anno-color="yellow"]');
  await h.page.waitForSelector('.canvas-tab-pane.active mark.nf-anno[data-anno-color="yellow"]');
  const markId = await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active mark.nf-anno').dataset.annoId);
  ok('黄色高亮回渲（mark.nf-anno[data-anno-color=yellow]）', !!markId);

  // 落库对账（WS reader.anno 真文件往返）
  ok('标注落库（anno-store.json 含标注 id）', await waitStoreContains(markId));

  // 第二章再标一条（抽屉分组 + 回跳用）
  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  await h.page.click('.canvas-tab-pane.active .epub-toc-item:nth-child(2)');
  await h.page.waitForFunction(() => document.querySelector('.canvas-tab-pane.active .epub-chapter-title').textContent.includes('第二章'), { timeout: 5000 });
  await stabilizeReader(h.page);
  ok('选区直中第二章目标句', (await selectText(h.page, '黄河入海流', 5)) === 'ok');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-anno-bar:not([hidden])');
  await h.page.click('.canvas-tab-pane.active .epub-anno-bar [data-anno-color="blue"]');
  await h.page.waitForSelector('.canvas-tab-pane.active mark.nf-anno[data-anno-color="blue"]');

  // 笔记：第一章黄标加批注（编辑 → 保存）
  await h.page.click('.canvas-tab-pane.active .epub-toc-btn');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-toc:not([hidden])');
  await h.page.click('.canvas-tab-pane.active .epub-toc-item:nth-child(1)');
  await h.page.waitForFunction(() => document.querySelector('.canvas-tab-pane.active .epub-chapter-title').textContent.includes('第一章'), { timeout: 5000 });
  await stabilizeReader(h.page);
  await h.page.evaluate(() => {
    const mark = document.querySelector('.canvas-tab-pane.active mark.nf-anno');
    mark.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-anno-bar:not([hidden])');
  await h.page.click('.canvas-tab-pane.active .epub-anno-bar [data-anno-note]');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-anno-note:not([hidden])');
  await h.page.fill('.canvas-tab-pane.active .epub-anno-note textarea', 'P3 批注一');
  await h.page.click('.canvas-tab-pane.active .epub-anno-note [data-anno-save]');
  ok('笔记编辑保存（编辑器收起）', await h.page.evaluate(() => document.querySelector('.canvas-tab-pane.active .epub-anno-note').hidden));
  ok('批注随标注落库', await waitStoreContains('P3 批注一'));

  // 抽屉：按章分组 + 笔记预览
  await tapViewport(h.page, 0.5, 0.55);
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  await h.page.click('.canvas-tab-pane.active [data-reader-anno]');
  await h.page.waitForSelector('.canvas-tab-pane.active .epub-anno-list:not([hidden])');
  const drawer = await h.page.evaluate(() => ({
    groups: [...document.querySelectorAll('.canvas-tab-pane.active .epub-anno-group-label')].map((g) => g.textContent.trim()),
    items: document.querySelectorAll('.canvas-tab-pane.active .epub-anno-item').length,
    note: (document.querySelector('.canvas-tab-pane.active .epub-anno-item-note') || { textContent: '' }).textContent,
  }));
  ok('抽屉按章分组（第一章/第二章）', drawer.groups.length === 2 && drawer.groups[0].includes('第一章') && drawer.groups[1].includes('第二章'), JSON.stringify(drawer));
  ok('抽屉两条 + 批注预览', drawer.items === 2 && drawer.note.includes('P3 批注一'), JSON.stringify(drawer));
  await shot(h.page, 'anno-drawer.png');

  // 回跳：点第二章蓝标 → 跨章跳 + 高亮落位
  await h.page.evaluate(() => {
    const items = [...document.querySelectorAll('.canvas-tab-pane.active .epub-anno-item')];
    items[items.length - 1].click(); // 末条 = 第二章蓝标
  });
  await h.page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return pane.querySelector('.epub-chapter-title').textContent.includes('第二章')
      && !!pane.querySelector('mark.nf-anno[data-anno-color="blue"]');
  }, { timeout: 5000 });
  ok('抽屉点击 → 跨章回跳 + 高亮落位', true);
  ok('标注面腿零 pageerror', h.errors.length === 0, h.errors.join(' | '));
  await h.ctx.close();

  // ── 重开整页：quote 主键重定位（高亮原样回渲） ──
  console.log('  -- 整页重开：落库标注回渲 --');
  const h2 = await boot(browser);
  await openPath(h2.page, bookAnno);
  await waitReader(h2.page);
  await stabilizeReader(h2.page);
  await h2.page.waitForSelector('.canvas-tab-pane.active mark.nf-anno[data-anno-color="yellow"]', { timeout: 10000 });
  const restored = await h2.page.evaluate(() => ({
    yellow: !!document.querySelector('.canvas-tab-pane.active mark.nf-anno[data-anno-color="yellow"]'),
    blueOnCh2: !!document.querySelector('.canvas-tab-pane.active mark.nf-anno[data-anno-color="blue"]'),
    staleCount: document.querySelectorAll('.canvas-tab-pane.active mark.nf-anno[data-anno-stale]').length,
  }));
  ok('重开后第一章黄标回渲（quote 主键命中）', restored.yellow === true, JSON.stringify(restored));
  ok('重开后无灰显（原文未动）', restored.staleCount === 0);
  ok('重开腿零 pageerror', h2.errors.length === 0, h2.errors.join(' | '));
  await h2.ctx.close();

  // ── 原文改动：quote 失配 → offset 模糊找回灰显（数据不丢） ──
  // 新 h3 页签零开页——openPath 直读磁盘新字节，无需 refresh 旗。
  console.log('  -- 原文改动：灰显降级 --');
  makeAnnoBook(bookAnno, { variant: true }); // 「欲穷改里目」——quote（9 字）失配，8 字前缀仍配
  const h3 = await boot(browser);
  await openPath(h3.page, bookAnno);
  await waitReader(h3.page);
  await stabilizeReader(h3.page);
  await h3.page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return pane.querySelector('.epub-chapter-title').textContent.includes('第一章');
  }, { timeout: 5000 });
  const stale = await h3.page.evaluate(() => ({
    staleMarks: document.querySelectorAll('.canvas-tab-pane.active mark.nf-anno[data-anno-stale]').length,
    totalMarks: document.querySelectorAll('.canvas-tab-pane.active mark.nf-anno').length,
  }));
  ok('quote 失配 → offset 模糊找回灰显（data-anno-stale）', stale.staleMarks >= 1, JSON.stringify(stale));
  // 抽屉徽记 + 数据不丢
  await tapViewport(h3.page, 0.5, 0.55);
  await h3.page.waitForSelector('.canvas-tab-pane.active .epub-reader-menu:not([hidden])');
  await h3.page.click('.canvas-tab-pane.active [data-reader-anno]');
  await h3.page.waitForSelector('.canvas-tab-pane.active .epub-anno-list:not([hidden])');
  const staleDrawer = await h3.page.evaluate(() => ({
    items: document.querySelectorAll('.canvas-tab-pane.active .epub-anno-item').length,
    badges: document.querySelectorAll('.canvas-tab-pane.active .epub-anno-stale').length,
    note: (document.querySelector('.canvas-tab-pane.active .epub-anno-item-note') || { textContent: '' }).textContent,
  }));
  ok('灰显徽记在抽屉（原文已变动）+ 标注数不丢', staleDrawer.items === 2 && staleDrawer.badges >= 1, JSON.stringify(staleDrawer));
  ok('批注数据不丢（P3 批注一仍在）', staleDrawer.note.includes('P3 批注一'));
  await shot(h3.page, 'anno-stale-drawer.png');
  ok('灰显腿零 pageerror', h3.errors.length === 0, h3.errors.join(' | '));
  await h3.ctx.close();
}

await browser.close();
webServer.close();
const failed = results.filter((r) => !r.pass);
console.log(`\n== ${results.length - failed.length}/${results.length} 项通过 ==`);
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => `  ✗ ${f.name} — ${f.detail}`).join('\n'));
  process.exit(1);
}
