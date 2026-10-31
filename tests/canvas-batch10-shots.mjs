// canvas-batch10-shots.mjs — Canvas 改造批（十条，作者令 2026-10-31 09:25）证据截图 + 探针。
//
// 产出（SHOTS_DIR，默认 evidence/20261031_canvas-batch/）：
//   桌面（1440×900 暗）：
//     d02-header-v2.png        ②顶栏换代：胶囊左 ✕ + 右上 #deck-fab（g2 液体玻璃）
//     d05-deck-count.png       ⑤堆叠态顶计数「N/总数」（.t-count）+ 卡组环
//     d06-dedupe.png           ⑥文件名不重复显示（alpha.md 首标题 # alpha 被去重）
//     d07-txt-plain.png        ⑦txt 朴素滚动面（无 epub 阅读器底栏）
//     d07-epub-reader.png      ⑦epub 阅读器视角 + ⑩ask-fab 上阅读器页
//     d08-corner-cluster.png   ⑧右下角控件整齐收纳（源码钮贴右缘 16px 首槽）
//   移动（390×844 触屏暗）：
//     m02-canvas-toolbar.png   ②手机工具条：左 ✕ / 计数 / 右堆叠视角钮（g2-fab）
//     m03-stackview.png        ⑤堆叠视角浮层「N 张文档」
//     m03-swipe-ring.png       ③横拖切卡中帧（相邻页圆环跟手）
//     m09-askfab.png           ⑨canvas 页对话钮在场（body 层 fixed，dock 上方）
//   probes.json                ①中文化 title 面 / ④graph 钮零命中 / ⑤pop 钮零命中 /
//                              ⑥去重计算面 / ⑦epub-reader 载体判据 / ⑩fab 可见
// 运行：node tests/canvas-batch10-shots.mjs

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8173);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/20261031_canvas-batch');
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── 夹具：手写 stored zip（reader-p1 同款 CRC32 自算）── */
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
  const chunks = []; const central = []; let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = CRC(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(nameBuf.length, 26); lh.writeUInt16LE(0, 28);
    chunks.push(lh, nameBuf, data);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameBuf.length, 28); ch.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([ch, nameBuf]));
    offset += lh.length + nameBuf.length + data.length;
  }
  const cd = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12); eocd.writeUInt32LE(offset, 16);
  writeFileSync(out, Buffer.concat([...chunks, cd, eocd]));
}

const XHTML = 'http://www.w3.org/1999/xhtml';
const EPUB_PATH = '/tmp/cv-batch10/book.epub';
mkdirSync(dirname(EPUB_PATH), { recursive: true });
writeStoredZip(EPUB_PATH, [
  ['mimetype', 'application/epub+zip'],
  ['META-INF/container.xml', `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`],
  ['OEBPS/content.opf', `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>`],
  ['OEBPS/toc.ncx', `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head></head><docTitle><text>批⑩样书</text></docTitle>
  <navMap><navPoint id="n1" playOrder="1"><navLabel><text>第一章</text></navLabel><content src="text/ch1.xhtml"/></navPoint></navMap>
</ncx>`],
  ['OEBPS/text/ch1.xhtml', `<?xml version="1.0"?>
<html xmlns="${XHTML}"><head><title>第一章</title></head><body>
<h1>第一章</h1>
${Array.from({ length: 80 }, (_, i) => `<p>批⑩样书段落 ${i + 1}：证据链填充文本，白日依山尽，黄河入海流。</p>`).join('\n')}
</body></html>`],
]);

const TALL = '# bravo\n\n' + Array.from({ length: 140 }, (_, i) => `bravo 行 ${i + 1}：右下角控件簇整齐收纳判据填充行。`).join('\n') + '\n';
const FILE_CONTENTS = new Map([
  ['/tmp/cv-batch10/alpha.md', { content: '# alpha\n\nalpha 正文——文件名与首标题同名，去重后标题只出现一次。\n\n- 列表 A\n- 列表 B\n' }],
  ['/tmp/cv-batch10/bravo.md', { content: TALL }],
  ['/tmp/cv-batch10/note.txt', { content: Array.from({ length: 200 }, (_, i) => `TXT-LINE-${String(i + 1).padStart(3, '0')} 朴素滚动面证据行。`).join('\n') + '\n' }],
  ['/tmp/cv-batch10/book.epub', { epub: true }],
]);

/* ── 静态服务 ── */
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.epub': 'application/epub+zip',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB, p);
  if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();
const probes = {};

async function boot({ viewport = { width: 1440, height: 900 }, mobile = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme: 'dark', hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  // epub 字节面：/api/nf-file?path=… 直读磁盘（reader-p1 同口径）。
  await ctx.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const u = new URL(route.request().url());
    const fp = u.searchParams.get('path') || '';
    try {
      const body = readFileSync(fp);
      const CT = { '.epub': 'application/epub+zip', '.md': 'text/markdown', '.txt': 'text/plain' };
      const ext = fp.slice(fp.lastIndexOf('.')).toLowerCase();
      return route.fulfill({ status: 200, headers: { 'Content-Type': CT[ext] || 'application/octet-stream' }, body });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  });
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        if (rec && rec.epub) {
          // epub = 二进制面：frame 不携 content（reader-p1 mock 同口径），
          // 前端按扩展名走 arrayBuffer/fetch 通路。
          ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, itemType: 'epub', fileName: m.path.split('/').pop(), size: 1 }));
        } else {
          ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: 'markdown', fileName: m.path.split('/').pop(), size: 1 }));
        }
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html');
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

// itemType 按扩展名（reader-p1 EXT_ITEM 同口径）+ pinned（防预览组替换——
// 同组二次开不 pinned 会顶掉首张，移动「N 张文档」计数退 1）。
const openFile = (page, p) => page.evaluate((pp) => {
  const ext = (pp.split('.').pop() || '').toLowerCase();
  const ITEM_OF = { md: 'markdown', txt: 'code', epub: 'epub' };
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + pp, itemType: ITEM_OF[ext] || 'code', content: '', absPath: pp, size: 0, title: pp.split('/').pop(), pinned: true },
  }));
}, p);

/* ════════ 桌面 ════════ */
{
  const { ctx, page } = await boot();
  await openFile(page, '/tmp/cv-batch10/alpha.md');
  await openFile(page, '/tmp/cv-batch10/bravo.md');   // bravo 活动
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(700);

  // ②顶栏换代 + ⑥去重 + ⑧簇位（常态 md 角浮件回落首槽 16px）
  await page.screenshot({ path: join(SHOTS_DIR, 'd02-header-v2.png') });
  await page.screenshot({ path: join(SHOTS_DIR, 'd06-dedupe.png') });
  await page.screenshot({ path: join(SHOTS_DIR, 'd08-corner-cluster.png') });

  probes.dedupe = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const h1 = pane.querySelector('h1');
    return { firstHeadingDisplay: h1 ? getComputedStyle(h1).display : null, tabTitle: document.getElementById('canvas-title-name').textContent };
  });
  probes.zhDesktop = await page.evaluate(() => ({
    sourceToggleTitle: document.querySelector('.canvas-source-toggle')?.getAttribute('title') || '',
    zoomBarTitles: [...document.querySelectorAll('.canvas-zoom-bar button')].map((b) => b.getAttribute('title') || ''),
    closeTabTitle: document.querySelector('.canvas-tab-close')?.getAttribute('title') || '',
    deckFabTitle: document.getElementById('deck-fab')?.getAttribute('title') || '',
    headCloseTitle: document.getElementById('canvas-head-close')?.getAttribute('title') || '',
  }));
  probes.noGraphToggle = await page.evaluate(() => !document.querySelector('.canvas-tab-pane.active .canvas-graph-toggle') && !document.querySelector('.canvas-graph-toggle'));
  probes.noPopChip = await page.evaluate(() => !document.getElementById('canvas-pop-btn'));

  // ⑤堆叠态顶计数
  await page.click('#deck-fab');
  await sleep(900);
  probes.deckCount = await page.evaluate(() => (document.getElementById('canvas-title-count') || {}).textContent || '');
  await page.screenshot({ path: join(SHOTS_DIR, 'd05-deck-count.png') });
  await page.keyboard.press('Escape');
  await sleep(400);

  // ⑦txt 朴素滚动面
  await openFile(page, '/tmp/cv-batch10/note.txt');
  await sleep(700);
  probes.txtPlain = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return { txtScroll: !!pane.querySelector('.canvas-txt-scroll'), epubZero: !pane.querySelector('.epub-reader') };
  });
  await page.screenshot({ path: join(SHOTS_DIR, 'd07-txt-plain.png') });

  // ⑦epub 阅读器视角 + ⑩ask-fab 上阅读器页
  await openFile(page, '/tmp/cv-batch10/book.epub');
  await page.waitForSelector('.canvas-tab-pane.active .epub-reader-nav', { timeout: 20000 });
  await sleep(800);
  probes.epubReader = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const fab = document.querySelector('.ask-fab');
    const nav = pane.querySelector('.epub-reader-nav');
    const fr = fab.getBoundingClientRect(), nr = nav.getBoundingClientRect();
    return {
      readerMounted: !!pane.querySelector('.epub-reader'),
      fabVisible: fab.offsetParent !== null && !fab.classList.contains('gate-off'),
      fabAboveNav: fr.bottom <= nr.top + 1,
    };
  });
  await page.screenshot({ path: join(SHOTS_DIR, 'd07-epub-reader.png') });
  await ctx.close();
}

/* ════════ 移动 ════════ */
{
  const { ctx, page } = await boot({ viewport: { width: 390, height: 844 }, mobile: true });
  await openFile(page, '/tmp/cv-batch10/alpha.md');
  await openFile(page, '/tmp/cv-batch10/bravo.md');
  await sleep(600);
  await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
  await sleep(900);

  // ②工具条 + ⑨ask-fab
  probes.mobileToolbar = await page.evaluate(() => ({
    close: !!document.querySelector('.mf-cv-close'),
    stack: !!document.querySelector('.mf-cv-stack'),
    count: (document.querySelector('.mf-cv-count') || {}).textContent || '',
    popGone: !document.querySelector('.mf-cv-pop'),
    backGone: document.querySelectorAll('.mobile-page[data-page="canvas"] .mobile-back-btn').length === 0,
    g2fab: document.querySelector('.mf-cv-close')?.classList.contains('g2-fab') && document.querySelector('.mf-cv-stack')?.classList.contains('g2-fab'),
    zhStackTitle: document.querySelector('.mf-cv-stack')?.getAttribute('title') || '',
    // offsetParent 对 position:fixed 恒 null（Chrome 口径）——可见判据读布局盒。
    fabVisible: (() => { const f = document.querySelector('.ask-fab'); return !!f && f.getBoundingClientRect().width > 0 && !f.classList.contains('gate-off'); })(),
    fabDebug: (() => {
      const f = document.querySelector('.ask-fab');
      return {
        top: (window.__nfMobileNav || {}).top,
        gateOff: f ? f.classList.contains('gate-off') : null,
        display: f ? getComputedStyle(f).display : null,
        mobileLayer: document.body.classList.contains('mobile-layer'),
      };
    })(),
  }));
  await page.screenshot({ path: join(SHOTS_DIR, 'm02-canvas-toolbar.png') });
  await page.screenshot({ path: join(SHOTS_DIR, 'm09-askfab.png') });

  // ⑤堆叠视角「N 张文档」
  await page.click('.mf-cv-stack');
  await sleep(600);
  probes.stackView = await page.evaluate(() => ({
    count: (document.querySelector('.mf-stack-count') || {}).textContent || '',
    cards: document.querySelectorAll('.mf-stack-card').length,
  }));
  await page.screenshot({ path: join(SHOTS_DIR, 'm03-stackview.png') });
  await page.click('.mf-stack-view, .mf-stack-count, .mf-cv-stack').catch(() => {});
  await page.evaluate(() => document.querySelector('.mf-stack-view')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))).catch(() => {});
  await sleep(300);

  // ③横拖中帧（圆环跟手）：在内容 pane 上横拖 60px 不松手截图
  const paneBox = await page.evaluate(() => {
    const pane = document.querySelector('.mobile-canvas-body .canvas-tab-pane.active, .mf-canvas-page .canvas-tab-pane.active, .canvas-tab-pane.active');
    const r = pane.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(paneBox.x, paneBox.y);
  await page.mouse.down();
  for (let i = 1; i <= 6; i++) { await page.mouse.move(paneBox.x + i * 10, paneBox.y); await sleep(30); }
  probes.swipeRing = await page.evaluate(() => ({
    ringPresent: !!document.querySelector('.mf-swipe-ring'),
    paneTransform: (document.querySelector('.canvas-tab-pane.active') || document.querySelector('.mf-canvas-pane'))?.style.transform || '',
  }));
  await page.screenshot({ path: join(SHOTS_DIR, 'm03-swipe-ring.png') });
  await page.mouse.up();
  await sleep(400);
  await ctx.close();
}

writeFileSync(join(SHOTS_DIR, 'probes.json'), JSON.stringify(probes, null, 2));
console.log('probes:', JSON.stringify(probes, null, 2));
console.log('shots ->', SHOTS_DIR);

await browser.close();
server.close();
