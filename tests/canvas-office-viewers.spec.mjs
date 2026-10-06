// canvas-office-viewers.spec.mjs — Canvas 办公三 viewer（canvas-office-render-fix 批 2026-10-06）。
//
// 作者 16:43 令（双截图为证）三症状：
//   ① pptx：viewer 已新版但 renderer asset fetch 404 —— vendored
//     web/vendor/pptx-renderer/*（commit 0adfef219 新增 7 件）无路由可达
//     （WebSocketRoutes 旧 vendor 白名单只覆盖单段文件＋monaco/pdfjs/fonts 目录）；
//     修 = 静态路由收录 vendor 子树（StaticRoutes.vendorSubtree）。
//   ② xlsx：`Failed to render XLSX: HTTP 401: Missing 'ticket' parameter` ——
//     取件 fetch 一次性、无 image.js/video.js 的 reMint 自愈；mint 轮空时
//     URL 无票 ⇒ 401 定局。修 = 镜像 reMint 形态（401 → 重取票 → 重试一次）。
//   ③ docx：同一存量形态（同腿同缺自愈）；修同②。
//
// 被测真实链路（前端零替身，mock 档）：workspace-open-item（itemType:'' + absPath）
// → canvas.js openWorkspaceItem → WS pop.readFile（替身按真语义：binary 只回元数据）
// → explorer fileContent 重派发 → renderFile → viewers/{pptx,xlsx,docx}.js
// → nfTicket 铸票 → /api/nf-file（替身带票闸）→ 出片/出表/出文。
// 服务端路由面真读数由 VendorSubtreeRoutesSpec（Scala）＋ LIVE 档（真实例 curl 级
// 读数由批回执记录）双重取证。
//
// 运行：
//   node tests/canvas-office-viewers.spec.mjs                    # 门禁档（改后必绿；改前 ①④ 腿会红）
//   NEBFLOW_LIVE_BASE=http://127.0.0.1:8199 NEBFLOW_LIVE_HOME=/tmp/nf-office-home-after \
//     node tests/canvas-office-viewers.spec.mjs                  # 追加真实例档（真铸票＋真 vendor 路由）
//   （NEBFLOW_LIVE_* 为仓内 LIVE 档约定名，与 canvas-video-viewer.spec.mjs 同形；
//    NF_OFFICE_LIVE_BASE / NF_OFFICE_LIVE_HOME 为同义别名——沙箱执行面会剥离
//    NEBFLOW_* 环境变量时的备用通道。两对同时给出时约定名优先。）
// 可选 env：PORT=8142、SHOTS_DIR=<dir>（缺省 .nebflow/evidence/20261006_canvasoffice/）
//
// 夹具：xlsx/docx 运行时手写最小 OOXML zip（stdlib，零三方依赖）；pptx 用
// 20261006_pptx-mvp 批的 fidelity-sample.pptx（6 页保真样例，可 NF_PPTX_FIXTURE 覆盖）。

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8142);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = process.env.NF_OFFICE_FIXDIR || join(tmpdir(), 'nf-office-viewer-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/20261006_canvasoffice');
const LIVE_BASE = process.env.NEFLOW_LIVE_BASE || process.env.NF_OFFICE_LIVE_BASE || '';
const LIVE_HOME = process.env.NEFLOW_LIVE_HOME || process.env.NF_OFFICE_LIVE_HOME || '';
const PPTX_FIXTURE = process.env.NF_PPTX_FIXTURE ||
  resolve(ROOT, '.nebflow/evidence/20261006_pptx-mvp/poc/decks/fidelity-sample.pptx');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具（运行时手写最小 OOXML zip，stdlib 零依赖） ───────────── */

/** 最小合法 xlsx：两个 sheet（Sheet1 带表头＋两行数据；Second 冒烟）。 */
function makeXlsx(out) {
  const ct = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>`;
  const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;
  const wb = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/><sheet name="Second" sheetId="2" r:id="rId2"/></sheets>
</workbook>`;
  const wbRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
</Relationships>`;
  const sheetXml = (rows) => `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows}</sheetData></worksheet>`;
  const rowXml = (cells, rowIdx) =>
    `<row r="${rowIdx}">${cells.map((v, i) => `<c r="${String.fromCharCode(65 + i)}${rowIdx}" t="inlineStr"><is><t>${v}</t></is></c>`).join('')}</row>`;
  // 手写 stored zip（无压缩，CRC32 自算）——OOXML 允许 stored 条目。
  const entries = [
    ['[Content_Types].xml', ct],
    ['_rels/.rels', rels],
    ['xl/workbook.xml', wb],
    ['xl/_rels/workbook.xml.rels', wbRels],
    ['xl/worksheets/sheet1.xml', sheetXml(rowXml(['Name', 'Qty', 'Price'], 1) + rowXml(['Widget', '4', '9.99'], 2) + rowXml(['Gadget', '2', '24.5'], 3))],
    ['xl/worksheets/sheet2.xml', sheetXml(rowXml(['A', 'B'], 1) + rowXml(['x', 'y'], 2))],
  ];
  writeStoredZip(out, entries);
}

/** 最小合法 docx：word/document.xml 一段标题＋一段正文（mammoth 直读）。 */
function makeDocx(out) {
  const ct = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`;
  const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`;
  const doc = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>NF Office Fix Sample</w:t></w:r></w:p>
<w:p><w:r><w:t>This paragraph proves the docx viewer renders real text.</w:t></w:r></w:p>
</w:body></w:document>`;
  writeStoredZip(out, [
    ['[Content_Types].xml', ct],
    ['_rels/.rels', rels],
    ['word/document.xml', doc],
  ]);
}

/** 手写 stored（uncompressed）zip：本地文件头＋central directory＋EOCD，CRC32 自算。 */
function writeStoredZip(out, entries) {
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
  const chunks = [];
  const central = [];
  let offset = 0;
  const enc = (s) => Buffer.from(s, 'utf8');
  for (const [name, content] of entries) {
    const nameBuf = enc(name);
    const data = enc(content);
    const crc = crc32(data);
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

/* ───────────── 静态服务（真 web/ 树） ───────────── */
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

/* ───────────── /api/nf-file 替身（真端点语义：票闸＋按路径回字节） ───────────── */
async function nfFileRoute(route) {
  if (await ticketGuard(route)) return;
  const url = new URL(route.request().url());
  const p = url.searchParams.get('path') || '';
  try {
    const body = readFileSync(p);
    const CT = { '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', '.md': 'text/markdown' };
    return route.fulfill({ status: 200, headers: { 'Content-Type': CT[extname(p).toLowerCase()] || 'application/octet-stream' }, body });
  } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
}

/* ───────────── WS 替身：pop.readFile 真语义（binary 只回元数据） ───────────── */
const EXT_ITEM = {
  md: 'markdown', json: 'json', png: 'image', pdf: 'pdf',
  pptx: 'pptx', docx: 'docx', xlsx: 'xlsx', xlsm: 'xlsx',
};
const BINARY = new Set(['pptx', 'docx', 'xlsx', 'xlsm', 'png', 'pdf']);
const MAX_READ = 100 * 1024 * 1024;

/* ───────────── 应用引导 ───────────── */
async function boot(browser, { mintAlwaysFails = false, failFirstMint = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  const nfFileReqs = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('request', (r) => { if (r.url().includes('/api/nf-file')) nfFileReqs.push(decodeURIComponent(r.url())); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  const mint = await installTicketMock(page, mintAlwaysFails ? { status: 403 } : {});
  let mintFailCount = 0;
  if (failFirstMint) {
    // 首轮铸票整轮失败（⇒ ticketUrl 出无票 URL ⇒ 401 Missing 'ticket'）。
    // 注意 mintRound 在 403 时会用 Bearer 立即重试一次（nfTicket.js:199-204），
    // 所以「整轮失败」= 连续吞掉同一轮的两次 POST（cookie 腿＋Bearer 腿）。
    await page.route('**/api/nf-ticket*', async (route) => {
      if (mintFailCount < 2) { mintFailCount++; return route.fulfill({ status: 403, contentType: 'text/plain', body: 'mock mint failure' }); }
      return route.fallback();
    });
  }
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
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  return { ctx, page, errors, nfFileReqs, mint, get mintFailCount() { return mintFailCount; } };
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

const paneHas = (page, sel) => page.evaluate((s) => !!document.querySelector('.canvas-tab-pane.active')?.querySelector(s), sel);
const paneText = (page) => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.textContent || '');
const gateDecision = (page) => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?._pptxDiag?.gateDecision || '');

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const sampleXlsx = join(FIX_DIR, 'sample.xlsx');
const sampleDocx = join(FIX_DIR, 'sample.docx');
makeXlsx(sampleXlsx);
makeDocx(sampleDocx);
if (!readFileSync(PPTX_FIXTURE).slice(0, 2).equals(Buffer.from('PK'))) {
  console.error(`pptx fixture missing or not a zip: ${PPTX_FIXTURE} (set NF_PPTX_FIXTURE)`); process.exit(1);
}
console.log(`[fixtures] xlsx=${sampleXlsx} docx=${sampleDocx} pptx=${PPTX_FIXTURE} (${readFileSync(sampleXlsx).length}B/${readFileSync(sampleDocx).length}B/${readFileSync(PPTX_FIXTURE).length}B)`);

const browser = await chromium.launch();

console.log('\n== ① pptx：renderer 资产腿＋渲染出片（mock 档：vendor 由 web/ 树伺服） ==');
{
  const h = await boot(browser);
  const libReq = [];
  h.page.on('request', (r) => { if (r.url().includes('/vendor/pptx-renderer/')) libReq.push(r.url()); });
  await openPath(h.page, PPTX_FIXTURE);
  await h.page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return pane && pane._pptxDiag && pane._pptxDiag.gateDecision !== 'pending';
  }, { timeout: 30000 });
  const gate = await gateDecision(h.page);
  ok('gateDecision=render（渲染腿走通）', gate === 'render', `gate=${gate}`);
  ok('renderer 资产请求发生过（/vendor/pptx-renderer/…）', libReq.length > 0, libReq.join(','));
  const prodFrame = await h.page.evaluate(() => {
    const f = document.querySelector('.canvas-tab-pane.active iframe[sandbox=""]');
    return f ? { sandbox: f.getAttribute('sandbox'), slides: (f.srcdoc.match(/class="slidebox"/g) || []).length, scripts: (f.srcdoc.match(/<script/g) || []).length } : null;
  });
  ok('产物帧在位（sandbox="" 全沙箱）', !!prodFrame && prodFrame.sandbox === '', JSON.stringify(prodFrame));
  ok('出片 6 页（fidelity 样例）', !!prodFrame && prodFrame.slides === 6, `slides=${prodFrame && prodFrame.slides}`);
  ok('产物帧零脚本', !!prodFrame && prodFrame.scripts === 0, `scripts=${prodFrame && prodFrame.scripts}`);
  await shot(h.page, 'mock-pptx-rendered.png');
  if (h.errors.length) console.log(`  [warn] pageerror: ${h.errors.join(' | ').slice(0, 200)}`);
  await h.ctx.close();
}

console.log('\n== ② xlsx：自愈档（首轮铸票失败 → 401 → reMint → 重试 → 出表） ==');
{
  const h = await boot(browser, { failFirstMint: true });
  await openPath(h.page, sampleXlsx);
  let rendered = true;
  try { await h.page.waitForSelector('.canvas-tab-pane.active .canvas-xlsx-viewer', { timeout: 10000 }); } catch { rendered = false; }
  const text = await paneText(h.page);
  ok('自愈后出表（.canvas-xlsx-viewer）', rendered && text.includes('Widget'), text.slice(0, 80));
  // 铸票轮数：第 1 轮整轮失败（2 次 POST 被 注入器 吞掉）＋ reMint 第 2 轮成功（cookie 腿 1 次）。
  ok('首轮铸票失败仍自愈（重取票确发生：注入 2 败＋mock 收 1 成）', h.mint.count() >= 1 && h.mintFailCount === 2, `mints=${h.mint.count()} failInj=${h.mintFailCount}`);
  ok('nf-file 取件恰好两次（败＋重试）', h.nfFileReqs.length === 2, `reqs=${h.nfFileReqs.length}`);
  const noTicket = h.nfFileReqs.filter((u) => !/ticket=/.test(u)).length;
  ok('第一跳确为无票 401 形态（URL 无 ticket 参数）', noTicket === 1, `ticketless=${noTicket}`);
  await shot(h.page, 'mock-xlsx-healed.png');
  if (h.errors.length) console.log(`  [warn] pageerror: ${h.errors.join(' | ').slice(0, 200)}`);
  await h.ctx.close();
}

console.log('\n== ②-T4 xlsx：定局档（铸票恒败 → 恰好一次重试 → 终局错误面板，无死循环） ==');
{
  const h = await boot(browser, { mintAlwaysFails: true });
  await openPath(h.page, sampleXlsx);
  let terminal = true;
  try {
    await h.page.waitForFunction(() => {
      const t = document.querySelector('.canvas-tab-pane.active')?.textContent || '';
      return t.includes('Failed to render XLSX');
    }, { timeout: 10000 });
  } catch { terminal = false; }
  const text = await paneText(h.page);
  ok('终局面板在位（Failed to render XLSX）', terminal && text.includes('Failed to render XLSX'), text.slice(0, 80));
  ok('401 Missing ticket 形态如实呈现', text.includes('401'), text.slice(0, 120));
  ok('恰好两跳定局（无第三跳）', h.nfFileReqs.length === 2, `reqs=${h.nfFileReqs.length}`);
  // 每轮铸票 = cookie 腿＋Bearer 腿两次 POST（localStorage 有 token 时，nfTicket.js 403 重试）；
  // 两轮（首轮＋reMint，reMint 先清 breaker）= 4 次 POST。
  ok('铸票两轮（首轮＋reMint；403 时每轮双投）', h.mint.count() === 4, `mints=${h.mint.count()}`);
  if (h.errors.length) console.log(`  [warn] pageerror: ${h.errors.join(' | ').slice(0, 200)}`);
  await h.ctx.close();
}

console.log('\n== ③ docx：自愈档＋正常档（mammoth 出文） ==');
{
  // 正常档
  const h = await boot(browser);
  await openPath(h.page, sampleDocx);
  let rendered = true;
  try { await h.page.waitForFunction(() => {
    const t = document.querySelector('.canvas-tab-pane.active')?.textContent || '';
    return t.includes('NF Office Fix Sample');
  }, { timeout: 10000 }); } catch { rendered = false; }
  const text = await paneText(h.page);
  ok('docx 出文（标题＋正文真渲染）', rendered && text.includes('proves the docx viewer renders real text'), text.slice(0, 100));
  await shot(h.page, 'mock-docx-rendered.png');
  if (h.errors.length) console.log(`  [warn] pageerror: ${h.errors.join(' | ').slice(0, 200)}`);
  await h.ctx.close();

  // 自愈档（同一 reMint 形态）。等待内容文本而非 .canvas-md-viewer——
  // Loading 占位也挂这个类，选择器等待会立即假绿。
  const h2 = await boot(browser, { failFirstMint: true });
  await openPath(h2.page, sampleDocx);
  let healed = true;
  try { await h2.page.waitForFunction(() => {
    const t = document.querySelector('.canvas-tab-pane.active')?.textContent || '';
    return t.includes('NF Office Fix Sample');
  }, { timeout: 10000 }); } catch { healed = false; }
  const text2 = await paneText(h2.page);
  ok('docx 首轮铸票败仍自愈出文', healed && text2.includes('NF Office Fix Sample'), text2.slice(0, 80));
  ok('docx 自愈两跳（败＋重试）', h2.nfFileReqs.length === 2, `reqs=${h2.nfFileReqs.length}`);
  if (h2.errors.length) console.log(`  [warn] pageerror: ${h2.errors.join(' | ').slice(0, 200)}`);
  await h2.ctx.close();
}

console.log('\n== 回归抽查：md 打开不受影响 ==');
{
  const sampleMd = join(FIX_DIR, 'doc.md');
  writeFileSync(sampleMd, '# MD-REGRESSION-MARKER\n\n正文保持可渲染。\n');
  const h = await boot(browser);
  await openPath(h.page, sampleMd);
  await sleep(1200);
  const text = await paneText(h.page);
  ok('回归抽查：md 打开仍渲染（MARKER 可见）', text.includes('MD-REGRESSION-MARKER'), text.slice(0, 80));
  await h.ctx.close();
}

/* ── LIVE 档（可选）：真实例真铸票真 vendor 路由 ── */
if (LIVE_BASE && LIVE_HOME) {
  console.log('\n== LIVE 档（真实例：真铸票＋真 /vendor/pptx-renderer 路由） ==');
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  let token = 't';
  try {
    const raw = readFileSync(join(LIVE_HOME, 'auth.json'), 'utf8').trim();
    let val = raw;
    try { const j = JSON.parse(raw); val = (typeof j === 'string' ? j : (j.token || j.gatewayToken || raw)); } catch { /* 裸串 */ }
    if (val) token = val;
  } catch { /* 无 auth.json */ }
  await ctx.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  }, token);
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  const libReq = [];
  const nfFileReqStatus = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('request', (r) => { if (r.url().includes('/vendor/pptx-renderer/')) libReq.push(r.url()); });
  page.on('response', (r) => { if (r.url().includes('/api/nf-file')) nfFileReqStatus.push(r.status()); });
  await page.goto(LIVE_BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(500);

  // pptx：vendor 资产真路由（本批修复主面）
  await openPath(page, PPTX_FIXTURE);
  await page.waitForFunction(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return pane && pane._pptxDiag && pane._pptxDiag.gateDecision !== 'pending';
  }, { timeout: 45000 });
  const gate = await gateDecision(page);
  ok('[LIVE] pptx gateDecision=render（真路由伺服 renderer 资产）', gate === 'render', `gate=${gate}`);
  ok('[LIVE] /vendor/pptx-renderer 资产请求发生', libReq.length > 0, libReq.join(','));
  await shot(page, 'live-pptx-rendered.png');

  // xlsx：真铸票真取件
  await openPath(page, sampleXlsx);
  let xlsxOk = true;
  try { await page.waitForSelector('.canvas-tab-pane.active .canvas-xlsx-viewer', { timeout: 10000 }); } catch { xlsxOk = false; }
  ok('[LIVE] xlsx 出表（真票据腿）', xlsxOk && (await paneText(page)).includes('Widget'), (await paneText(page)).slice(0, 80));
  await shot(page, 'live-xlsx-rendered.png');

  // docx：真铸票真取件
  await openPath(page, sampleDocx);
  let docxOk = true;
  try { await page.waitForFunction(() => {
    const t = document.querySelector('.canvas-tab-pane.active')?.textContent || '';
    return t.includes('NF Office Fix Sample');
  }, { timeout: 10000 }); } catch { docxOk = false; }
  ok('[LIVE] docx 出文（真票据腿）', docxOk, (await paneText(page)).slice(0, 80));
  await shot(page, 'live-docx-rendered.png');

  ok('[LIVE] nf-file 全部 200（真票真取件）', nfFileReqStatus.length > 0 && nfFileReqStatus.every((s) => s === 200), JSON.stringify(nfFileReqStatus));
  if (errors.length) console.log(`  [warn] pageerror: ${errors.join(' | ').slice(0, 200)}`);
  await ctx.close();
} else {
  console.log('\n[LIVE 档跳过] 设 NEBFLOW_LIVE_BASE + NEBFLOW_LIVE_HOME 追加真实例档');
}

await browser.close();
webServer.close();
const failed = results.filter((r) => !r.pass);
console.log(`\n== ${results.length - failed.length}/${results.length} 项通过 ==`);
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => `  ✗ ${f.name} — ${f.detail}`).join('\n'));
  process.exit(1);
}
