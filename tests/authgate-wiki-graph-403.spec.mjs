// authgate-wiki-graph-403.spec.mjs — 图谱按钮/维基链接 403 族收口验收（#762 批
// 2026-10-22/23）。
//
// 作者实报症状（2026-10-22 21:49）：长命标签页会话过期后，文档里点图谱按钮只落
// 「文档图谱不可用」死 toast（REST 直调被裸 403，无重认证面）。修 = 同日
// scan-bind-403 批同一条路：401/403 ⇒ sessionGate noteAuthRejected（可见横幅 +
// 1.5s 自愈 reload，单飞 + 60s 冷却防环）。同族盘点一并上伞：
//   · viewers/shared.js addGraphToggle   —— 401/403 交伞（本件主诉面）；
//   · viewers/markdown.js wikiGraph()    —— err.status 分流：渲染腿静默降级
//     （authgate 批「渲染即重登」禁令），点击腿交伞；
//   · viewers/markdown.js 远端文档腿     —— 401/403 交伞，不再裸 remoteFetchFailed；
//   · viewers/epub.js                    —— office 族最后一个裸取件（无 reMint
//     自愈）⇒ 镜像 xlsx.js T3 形态（恰一次重取票＋重试）。
//
// 契约：
//   ① /api/wiki/graph 403 ⇒ 图谱按钮：零 toast + 横幅（data-source=wiki-graph-toggle）
//      + 自愈 reload 腿被拉动；
//   ② 401 ⇒ 同一收口（两状态同语义）；
//   ③ 503 ⇒ 既有 graphUnavailable toast 面保持，零横幅零 reload（真传输错静默降级不变）；
//   ④ 渲染腿（viewMarkdown 打开含 [[wikilink]] 的文档，graph 403）⇒ 零横幅零
//      reload 零 toast，链接照常渲染为锚（authgate 纪律：背景面绝不重登）；
//   ⑤ 点击该维基链接（graph 403）⇒ 交伞（data-source=wiki-link），零「无法解析」toast；
//   ⑥ 远端文档腿（graph 200 命中远端节点 → /api/wiki/remote-doc 403）⇒ 交伞
//      （data-source=wiki-remote-doc），零 remoteFetchFailed toast；
//   ⑦ epub 裸取件（首轮铸票失败 → /api/nf-file 401）⇒ 恰好一次 reMint 重取票 +
//      重试 → 出阅读器（T3 自愈，xlsx 同形）。
//
// 运行：node tests/authgate-wiki-graph-403.spec.mjs   （PORT 可换）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8148);
const BASE = `http://127.0.0.1:${PORT}`;

/* ── 最小合法 EPUB（stored zip，stdlib）────────────────────────────────── */

function makeEpubBytes() {
  const crcTable = new Int32Array(256).map((_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c;
  });
  const crc32 = (buf) => {
    let c = -1;
    for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ -1) >>> 0;
  };
  const container = `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`;
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>`;
  const ch1 = `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><h1>NF Epub Selfheal</h1><p>chapter body</p></body></html>`;
  const entries = [
    ['mimetype', 'application/epub+zip'],
    ['META-INF/container.xml', container],
    ['OEBPS/content.opf', opf],
    ['OEBPS/ch1.xhtml', ch1],
  ];
  const enc = (s) => Buffer.from(s, 'utf8');
  const chunks = [], central = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = enc(name), data = enc(content), crc = crc32(data);
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
const EPUB_BYTES = makeEpubBytes();

/* ── 静态服务（真 web/ 树）───────────────────────────────────────────────── */

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html' };
const webServer = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; failures.push(name); console.log(`FAIL  ${name}${extra ? '  — ' + extra : ''}`); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── LAB 页 + 路由 ─────────────────────────────────────────────────────── */

const LAB_HTML = '<!doctype html><html><head><meta charset="utf-8"><title>lab</title></head><body>lab</body></html>';
const GRAPH_MERGED_200 = JSON.stringify({
  projects: [{ name: 'p', vault: '/v' }],
  nodes: [{ rel: 'Target.md', project: 'p', device: 'peer1', stub: false }],
  edges: [],
});

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.route('**/lab.html', (route) => route.fulfill({ contentType: 'text/html', body: LAB_HTML }));

  let graphStatus = 403;
  let graphBody = '{"error":"denied"}';
  await page.route('**/api/wiki/graph*', (route) =>
    route.fulfill({ status: graphStatus, contentType: 'application/json', body: graphBody }));

  let remoteDocStatus = 403;
  await page.route('**/api/wiki/remote-doc*', (route) =>
    route.fulfill({ status: remoteDocStatus, contentType: 'application/json', body: '{"error":"denied"}' }));

  let nfFileCount = 0;
  await page.route('**/api/nf-file*', async (route) => {
    nfFileCount++;
    // 真端点语义：无票 401（ticketGuard 同形）。
    const url = new URL(route.request().url());
    if (!url.searchParams.has('ticket')) {
      return route.fulfill({ status: 401, contentType: 'text/plain', body: 'Missing ticket' });
    }
    return route.fulfill({ status: 200, contentType: 'application/epub+zip', body: EPUB_BYTES });
  });

  // 铸票替身（确定性两轮）：第 1 次 POST ⇒ 500（首轮铸票失败）；第 2 次起 ⇒ 正常出票。
  let mintCount = 0;
  await page.route('**/api/nf-ticket*', async (route) => {
    mintCount++;
    if (mintCount === 1) {
      return route.fulfill({ status: 500, contentType: 'text/plain', body: 'mock mint failure' });
    }
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    const tickets = {};
    for (const p of Array.isArray(paths) ? paths : []) {
      tickets[p] = { t: 'ticket-' + mintCount + '-' + p.length, exp: Date.now() + 3600000, uses: -1 };
    }
    return route.fulfill({ json: { tickets, rejected: [] } });
  });

  await page.goto(`${BASE}/lab.html`);
  await page.evaluate(async () => {
    window.__toasts = [];
    window.__showToast = (msg, kind) => window.__toasts.push({ msg: String(msg), kind: String(kind) });
    const m = await import('/js/sessionGate.js');
    window.__gate = m;
    m._sessionGateTestHooks().setReload(() => { window.__reloads = (window.__reloads || 0) + 1; });
    window.__shared = await import('/js/viewers/shared.js');
    window.__md = (await import('/js/viewers/markdown.js')).default;
    window.__epub = (await import('/js/viewers/epub.js')).default;
  });
  const gateState = () => page.evaluate(() => ({
    banner: document.querySelectorAll('[data-session-gate="1"]').length,
    source: (document.querySelector('[data-session-gate="1"]') || { dataset: {} }).dataset.source || '',
    reloads: window.__reloads || 0,
    toasts: window.__toasts,
  }));
  const resetGate = () => page.evaluate(() => {
    window.__gate._sessionGateTestHooks().reset();
    document.getElementById('stand-session-gate')?.remove();
    window.__toasts.length = 0;
  });

  // ── ① 图谱按钮 403 ⇒ 零 toast + 横幅 + reload 腿 ──
  await page.evaluate(() => {
    const pane = document.createElement('div');
    pane.id = 'pane-toggle';
    document.body.appendChild(pane);
    window.__shared.addGraphToggle(pane, '/v/doc.md', 160);
  });
  await page.click('#pane-toggle .canvas-graph-toggle');
  await sleep(300);
  let st = await gateState();
  ok('① 403 ⇒ 零 toast（不再死「文档图谱不可用」）', st.toasts.length === 0, JSON.stringify(st.toasts));
  ok('① 403 ⇒ 可见横幅出现', st.banner === 1);
  ok('① 横幅带触发面读数 wiki-graph-toggle', st.source === 'wiki-graph-toggle', `source=${st.source}`);
  await sleep(1500); // > RELOAD_DELAY_MS
  ok('① 自愈 reload 腿被拉动', (await gateState()).reloads === 1);

  // ── ② 401 ⇒ 同一收口 ──
  await resetGate();
  graphStatus = 401;
  await page.click('#pane-toggle .canvas-graph-toggle');
  await sleep(300);
  st = await gateState();
  ok('② 401 ⇒ 同一收口（横幅在、零 toast）', st.banner === 1 && st.toasts.length === 0,
    `banner=${st.banner} toasts=${JSON.stringify(st.toasts)}`);
  await sleep(1500);
  ok('② 401 ⇒ reload 腿被拉动', (await gateState()).reloads === 2);

  // ── ③ 503 ⇒ 既有 toast 面保持，零横幅零 reload ──
  await resetGate();
  graphStatus = 503;
  await page.click('#pane-toggle .canvas-graph-toggle');
  await sleep(300);
  st = await gateState();
  ok('③ 503 ⇒ graphUnavailable toast 照旧（真传输错的面）',
    st.toasts.length === 1 && st.toasts[0].kind === 'error', JSON.stringify(st.toasts));
  ok('③ 503 ⇒ 零新增横幅零 reload', st.banner === 0 && st.reloads === 2, `banner=${st.banner} reloads=${st.reloads}`);

  // ── ④ 渲染腿（打开含 [[wikilink]] 文档，graph 403）⇒ 完全静默 ──
  // 注：renderWikilinks 是 viewMarkdown 返回后的 fire-and-forget 过山——锚等它落定。
  await resetGate();
  graphStatus = 403;
  await page.evaluate(async () => {
    const pane = document.createElement('div');
    pane.id = 'pane-md';
    document.body.appendChild(pane);
    await window.__md.render(pane, { content: 'see [[Target Doc]] here', absPath: '/v/a.md', fileName: 'a.md' });
  });
  let mdLinks = 0;
  try {
    await page.waitForSelector('#pane-md a.nf-wikilink', { timeout: 5000 });
    mdLinks = await page.evaluate(() => document.querySelectorAll('#pane-md a.nf-wikilink').length);
  } catch { mdLinks = 0; }
  ok('④ 渲染腿 ⇒ wikilink 照常渲染为锚', mdLinks === 1, `links=${mdLinks}`);
  await sleep(300);
  st = await gateState();
  ok('④ 渲染腿 403 ⇒ 零横幅零 reload 零 toast（背景面绝不重登）',
    st.banner === 0 && st.reloads === 2 && st.toasts.length === 0,
    `banner=${st.banner} reloads=${st.reloads} toasts=${JSON.stringify(st.toasts)}`);

  // ── ⑤ 点击该维基链接 ⇒ 交伞（wiki-link），零「无法解析」toast ──
  await page.click('#pane-md a.nf-wikilink');
  await sleep(300);
  st = await gateState();
  ok('⑤ 点击腿 403 ⇒ 可见横幅', st.banner === 1);
  ok('⑤ 横幅带触发面读数 wiki-link', st.source === 'wiki-link', `source=${st.source}`);
  ok('⑤ 点击腿 ⇒ 零「无法解析」toast', st.toasts.length === 0, JSON.stringify(st.toasts));
  await sleep(1500);
  ok('⑤ 自愈 reload 腿被拉动', (await gateState()).reloads === 3);

  // ── ⑥ 远端文档腿（graph 200 → remote-doc 403）⇒ 交伞 ──
  // 注：链接文本小写——resolveWikiTarget 的大小写口径是「左侧比较值小写、
  // withMd 保留原文」，大写链接在该既有口径下不命中（与本批无关的存量语义，
  // 不在本批改动面）；夹具按可命中形态给。
  await resetGate();
  graphStatus = 200;
  graphBody = GRAPH_MERGED_200;
  remoteDocStatus = 403;
  await page.evaluate(async () => {
    const pane = document.createElement('div');
    pane.id = 'pane-md2';
    document.body.appendChild(pane);
    await window.__md.render(pane, { content: 'see [[target]] here', absPath: '/v/b.md', fileName: 'b.md' });
  });
  await page.waitForSelector('#pane-md2 a.nf-wikilink', { timeout: 5000 });
  await sleep(150); // 渲染腿解析（graph 200 入 30s 缓存，dataset.wikiResolved 落定）
  await page.click('#pane-md2 a.nf-wikilink');
  await sleep(300);
  st = await gateState();
  ok('⑥ 远端文档腿 403 ⇒ 可见横幅 + 读数 wiki-remote-doc',
    st.banner === 1 && st.source === 'wiki-remote-doc', `banner=${st.banner} source=${st.source}`);
  ok('⑥ 远端文档腿 ⇒ 零 remoteFetchFailed toast', st.toasts.length === 0, JSON.stringify(st.toasts));
  await sleep(1500);
  ok('⑥ 自愈 reload 腿被拉动', (await gateState()).reloads === 4);

  // ── ⑦ epub 裸取件 ⇒ T3 自愈（首轮铸票败 → 401 → reMint → 重试 → 出阅读器）──
  const mintsBefore = mintCount;
  const nfBefore = nfFileCount;
  const epubRes = await page.evaluate(async () => {
    const pane = document.createElement('div');
    pane.id = 'pane-epub';
    document.body.appendChild(pane);
    try {
      await window.__epub.render(pane, { absPath: '/x/book.epub', fileName: 'book.epub' });
    } catch (e) {
      return { reader: false, error: true, threw: String((e && e.message) || e) };
    }
    return {
      reader: !!pane.querySelector('.epub-reader'),
      error: !!pane.querySelector('.canvas-error'),
      threw: '',
    };
  });
  ok('⑦ epub 自愈 ⇒ 阅读器出片（非错误面板、不外抛）',
    epubRes.reader && !epubRes.error && !epubRes.threw, JSON.stringify(epubRes));
  ok('⑦ epub 自愈 ⇒ 恰好两轮铸票（首轮败 + reMint 轮）', mintCount - mintsBefore === 2, `mints=${mintCount - mintsBefore}`);
  ok('⑦ epub 自愈 ⇒ 恰好两次取件（无票 401 → 票据 200）', nfFileCount - nfBefore === 2, `nf=${nfFileCount - nfBefore}`);
} finally {
  await browser.close();
  webServer.close();
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES: ' + failures.join(' | ')); process.exit(1); }
