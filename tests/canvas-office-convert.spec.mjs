// canvas-office-convert.spec.mjs — pptx viewer 转换优先腿（canvas-pptx-convert 批
// 2026-10-24 作者令「服务端转换优先」）的门禁档。
//
// 被测阶梯（viewers/pptx.js）：
//   ① /api/nf-office 200 PDF  → gate convert-pdf、复用 pdf 面板（.canvas-pdf-viewer）、
//     零路线 A renderer 资产请求；diag.converter/convertCache 照实回读响应头。
//   ② /api/nf-office 200 PNG（qlmanage 首页腿）→ gate convert-firstpage、首页预览卡、
//     「first page only」注明、Download full file 键。
//   ③ 501 converter-absent → 静默回落路线 A（renderer 资产被请求、gate=render）。
//   ④ 502 conversion-failed + 路线 A 也不可渲（CFB .ppt 魔数）→ 终态降级卡
//     「此环境无预览能力」＋下载键（不许裸失败）。
//   ⑤ 缓存命中头（X-Nf-Office-Cache: hit）→ diag.convertCache=hit 透传。
//
// 运行：node tests/canvas-office-convert.spec.mjs
// 夹具：pptx/ppt 用 20261006_pptx-mvp 批的 fidelity 样例（NF_PPTX_FIXTURE /
//       NF_PPT_FIXTURE 可覆盖）；PDF/PNG 由本档运行时手铸（零三方依赖）。
// 服务端真转换腿（soffice/qlmanage 真二进制、真缓存）由 Scala NfOfficeRoutesSpec
// （路由语义）＋批报告的 LIVE 实例取证覆盖——本档全部 mock 档。

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8144);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/20261024_pptx-convert');
const PPTX_FIXTURE = process.env.NF_PPTX_FIXTURE ||
  '/Users/kaiyu/Claude code/Stand/.nebflow/evidence/20261006_pptx-mvp/poc/decks/fidelity-sample.pptx';
const PPT_FIXTURE = process.env.NF_PPT_FIXTURE ||
  '/Users/kaiyu/Claude code/Stand/.nebflow/evidence/20261006_pptx-mvp/poc/decks/legacy-header.ppt';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具：运行时手铸最小合法 PDF（xref 偏移程序化计算） ───────────── */
function minimalPdf(text) {
  const enc = (s) => Buffer.from(s, 'latin1');
  const content = `BT /F1 24 Tf 72 720 Td (${text}) Tj ET`;
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${enc(content).length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let head = enc('%PDF-1.4\n');
  const offsets = [];
  objs.map((o, i) => {
    offsets.push(head.length);
    const chunk = enc(`${i + 1} 0 obj\n${o}\nendobj\n`);
    head = Buffer.concat([head, chunk]);
    return chunk;
  });
  const xrefAt = head.length;
  let xref = enc(`xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`);
  for (const off of offsets) xref = Buffer.concat([xref, enc(String(off).padStart(10, '0') + ' 00000 n \n')]);
  const trailer = enc(`trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefAt}\n%%EOF\n`);
  // head 已累积 %PDF 头＋全部 obj 字节（offsets 在 append 前记录，天然自洽）。
  return Buffer.concat([head, xref, trailer]);
}

/** 1×1 红 PNG（base64 常量，零依赖）。 */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

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

/* ───────────── 应用引导（office-viewers 同族：真 web 树＋票闸替身＋WS 替身） ───────────── */
const EXT_ITEM = { pptx: 'pptx', ppt: 'pptx' };

async function boot(browser, officeResponder) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  const errors = [];
  const officeReqs = [];
  const rendererReqs = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('request', (r) => {
    if (r.url().includes('/api/nf-office')) officeReqs.push(r.url());
    if (r.url().includes('/vendor/pptx-renderer/')) rendererReqs.push(r.url());
  });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  const mint = await installTicketMock(page, {});
  await page.route(/\/api\/nf-office\?/, async (route) => {
    const url = new URL(route.request().url());
    if (!url.searchParams.get('ticket')) {
      return route.fulfill({ status: 401, contentType: 'text/plain', body: 'Missing ticket (guard)' });
    }
    return officeResponder(route, url);
  });
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    try {
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'application/octet-stream' }, body: readFileSync(p) });
    } catch { return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' }); }
  });
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
        const ext = (rawPath.split('.').pop() || '').toLowerCase();
        const msg = { type: 'fileContent', path: rawPath, absPath: rawPath, itemType: EXT_ITEM[ext] || 'code', fileName: rawPath.split('/').pop(), size: buf.length };
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
  return { ctx, page, errors, officeReqs, rendererReqs, mint };
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

const gateDecision = (page) => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?._pptxDiag?.gateDecision || '');
const diagField = (page, f) => page.evaluate((k) => document.querySelector('.canvas-tab-pane.active')?._pptxDiag?.[k] || '', f);
const paneHas = (page, sel) => page.evaluate((s) => !!document.querySelector('.canvas-tab-pane.active')?.querySelector(s), sel);
const paneText = (page) => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.textContent || '');

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
if (!readFileSync(PPTX_FIXTURE).slice(0, 2).equals(Buffer.from('PK'))) {
  console.error(`pptx fixture missing: ${PPTX_FIXTURE} (set NF_PPTX_FIXTURE)`); process.exit(1);
}
const pdfBytes = minimalPdf('NF Convert Test Page');
console.log(`[fixtures] pptx=${PPTX_FIXTURE} ppt=${PPT_FIXTURE} pdf=${pdfBytes.length}B png=${PNG_1PX.length}B`);

const browser = await chromium.launch();

console.log('\n== ① 转换 PDF 腿：/api/nf-office 200(pdf) → 复用 pdf 面板，零路线 A 资产 ==');
{
  const h = await boot(browser, (route) => route.fulfill({
    status: 200,
    headers: {
      'Content-Type': 'application/pdf',
      'X-Nf-Office-Converter': 'soffice',
      'X-Nf-Office-Cache': 'miss',
    },
    body: pdfBytes,
  }));
  await openPath(h.page, PPTX_FIXTURE);
  await h.page.waitForFunction(() => {
    const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
    return d && d.gateDecision !== 'pending';
  }, null, { timeout: 30000 });
  ok('gateDecision=convert-pdf', (await gateDecision(h.page)) === 'convert-pdf', await gateDecision(h.page));
  ok('diag.converter=soffice（响应头回读）', (await diagField(h.page, 'converter')) === 'soffice', await diagField(h.page, 'converter'));
  ok('diag.convertCache=miss', (await diagField(h.page, 'convertCache')) === 'miss');
  ok('pdf 面板在位（复用既有 .canvas-pdf-viewer）', await paneHas(h.page, '.canvas-pdf-viewer'));
  // pdf.js 异步：doc load + 首页 canvas 晚于 gate 置位——真渲染以 canvas 落位为准。
  const pdfCanvasSeen = await h.page.waitForFunction(
    () => !!document.querySelector('.canvas-tab-pane.active canvas.pdf-page'),
    null, { timeout: 15000 }
  ).then(() => true).catch(() => false);
  ok('pdf 页 canvas 在位（pdf.js 真渲染）', pdfCanvasSeen);
  ok('零路线 A renderer 资产请求', h.rendererReqs.length === 0, h.rendererReqs.join(','));
  ok('office 请求带票（ticket= 在 URL）', h.officeReqs.length === 1 && h.officeReqs[0].includes('ticket='), h.officeReqs.join(','));
  await shot(h.page, 'convert-pdf-panel.png');
  await h.ctx.close();
}

console.log('\n== ② qlmanage 首页腿：200(png) → 首页预览卡 ＋ first-page 注明 ＋下载键 ==');
{
  const h = await boot(browser, (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'image/png', 'X-Nf-Office-Converter': 'qlmanage', 'X-Nf-Office-Cache': 'miss' },
    body: PNG_1PX,
  }));
  await openPath(h.page, PPTX_FIXTURE);
  await h.page.waitForFunction(() => {
    const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
    return d && d.gateDecision !== 'pending';
  }, null, { timeout: 30000 });
  ok('gateDecision=convert-firstpage', (await gateDecision(h.page)) === 'convert-firstpage');
  ok('首页图在位', await paneHas(h.page, '.canvas-md-viewer img'));
  ok('first-page 注明', (await paneText(h.page)).includes('first page only'));
  ok('Download full file 键在位', await paneHas(h.page, 'a.nf-pptx-download'));
  await shot(h.page, 'convert-firstpage-card.png');
  await h.ctx.close();
}

console.log('\n== ③ converter-absent 501 → 静默回落路线 A（renderer 资产、gate=render） ==');
{
  const h = await boot(browser, (route) => route.fulfill({
    status: 501, headers: { 'X-Nf-Reason': 'converter-absent' }, contentType: 'text/plain', body: 'no office converter on this machine',
  }));
  await openPath(h.page, PPTX_FIXTURE);
  await h.page.waitForFunction(() => {
    const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
    return d && d.gateDecision !== 'pending';
  }, null, { timeout: 45000 });
  ok('gateDecision=render（路线 A 接管）', (await gateDecision(h.page)) === 'render', await gateDecision(h.page));
  ok('renderer 资产被请求（路线 A 真跑）', h.rendererReqs.length > 0);
  ok('convertSkipped 记因 501', String(await diagField(h.page, 'convertSkipped')).includes('501'), await diagField(h.page, 'convertSkipped'));
  const prodFrame = await h.page.evaluate(() => {
    const f = document.querySelector('.canvas-tab-pane.active iframe[sandbox=""]');
    return f ? { sandbox: f.getAttribute('sandbox'), slides: (f.srcdoc.match(/class="slidebox"/g) || []).length } : null;
  });
  ok('产物帧在位且零脚本帧形态', !!prodFrame && prodFrame.sandbox === '', JSON.stringify(prodFrame));
  await shot(h.page, 'fallback-routeA-render.png');
  await h.ctx.close();
}

console.log('\n== ④ 502 全败 + 路线 A 也不可渲（CFB .ppt）→ 终态降级卡「此环境无预览能力」 ==');
{
  const h = await boot(browser, (route) => route.fulfill({
    status: 502, headers: { 'X-Nf-Reason': 'conversion-failed' }, contentType: 'text/plain', body: 'all converter legs failed',
  }));
  await openPath(h.page, PPT_FIXTURE);
  await h.page.waitForFunction(() => {
    const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
    return d && d.gateDecision !== 'pending';
  }, null, { timeout: 30000 });
  ok('gateDecision=download-leg:cfb（魔数嗅探终态）', (await gateDecision(h.page)) === 'download-leg:cfb', await gateDecision(h.page));
  const txt = await paneText(h.page);
  ok('「此环境无预览能力」显式文案', txt.includes('此环境无预览能力'), txt.slice(0, 120));
  ok('下载键在位（不裸失败）', await paneHas(h.page, 'a.nf-pptx-download'));
  await shot(h.page, 'degraded-no-preview-card.png');
  await h.ctx.close();
}

console.log('\n== ⑤ 缓存命中头透传：X-Nf-Office-Cache: hit → diag.convertCache=hit ==');
{
  const h = await boot(browser, (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'application/pdf', 'X-Nf-Office-Converter': 'soffice', 'X-Nf-Office-Cache': 'hit' },
    body: pdfBytes,
  }));
  await openPath(h.page, PPTX_FIXTURE);
  await h.page.waitForFunction(() => {
    const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
    return d && d.gateDecision !== 'pending';
  }, null, { timeout: 30000 });
  ok('diag.convertCache=hit（二次点击零重转的前端读数）', (await diagField(h.page, 'convertCache')) === 'hit');
  ok('pdf 面板在位', await paneHas(h.page, '.canvas-pdf-viewer'));
  await h.ctx.close();
}

await browser.close();
const failed = results.filter((r) => !r.pass);
console.log(`\n[canvas-office-convert] ${results.length - failed.length}/${results.length} PASS${failed.length ? ' — FAIL: ' + failed.map((f) => f.name).join('; ') : ''}`);
process.exit(failed.length ? 1 : 0);
