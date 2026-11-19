// pdf-canvas-note-textlayer.spec.mjs — PDF「可选中 + 页锚 note/ask 单源」验收 spec
// （文本层批 2026-11-19；作者裁定「走 B」：保留页锚内容模型、入口归 canvas）。
//
// 被测 = **真实模块链**：index.html 全量 app（路由夹具）+ 真 canvas 标签 + 真
// viewers/pdf.js + vendored pdf.js 3.11.174 + 真夹具 PDF 字节（零 mock 进被测链）。
// 入口腿走 app 自己的真实打开件（`popArtifacts.openPopArtifact` = Pop 文件卡 / 搜索结果
// 共用腿；乐观打开 ⇒ 票据腿取字节 ⇒ 真 canvas 标签 + 真 viewer；md 对照腿走既有
// `workspace-open-item`（itemType markdown）——与 canvas-note-panel.spec 同一条路）。
//
// 判据：
//   T1 文本层在位：`.pdf-page-wrap .textLayer` 与 span 随位图共建；`--scale-factor`
//      恒等于当前 viewport.scale（pdf.js v3.11 renderTextLayer 硬前提）。
//   T2 选中 → 页锚 note：真 DOM 选区（mouse drag）⇒ `.note-fab`（同一颗钮）开
//      `.note-dock`（同一扇壳）；读腿 = GET /api/pdf/note?sha256&page；写腿 =
//      POST /api/pdf/note，载荷含 page 页锚 + snippet=选中文字 + expected_version。
//   T3 同一颗钮 → ask：`.ask-fab` 开 `.ask-dock`，提问正文 = 同一组装件
//      （selectionAsk.buildQuestion）产物，含选中文字。
//   T4 **零新路由**：全程 `/api/pdf/<x>` 取值集合 ⊆ {open, note}。
//   T5 单套外壳：PDF 态与 md 态的 `.note-dock` 是**同一节点**（跨态同一引用），
//      入口钮仍是 DOM 里唯一那两颗 `.ask-fab` / `.note-fab`。
//   T6 数据落点差异（作者允许的唯一差异）：md 态下方 note **零** /api/pdf 调用。
//   M1..M4 手机端（375×812 真全屏 + 录像）：真全屏形态下 T1/T2/T3 逐条复验。
//   S1 侧边栏删净**负控**：本树零命中（绿），基线提交 5b99db0b9 同扫描有命中（红）。
//
// 运行：node tests/pdf-canvas-note-textlayer.spec.mjs
// 证据：PDFTEXT_EVIDENCE_DIR=<目录>（默认 /Users/kaiyu/stand-evidence/pdf-canvas-note-20261119）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync, readdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB = resolve(REPO, 'src/main/resources/web');
const OUT = process.env.PDFTEXT_EVIDENCE_DIR
  || '/Users/kaiyu/stand-evidence/pdf-canvas-note-20261119';
const VIDEO_DIR = join(OUT, '_video');
mkdirSync(OUT, { recursive: true });
rmSync(VIDEO_DIR, { recursive: true, force: true });
mkdirSync(VIDEO_DIR, { recursive: true });
// 证据批次清单（scripts/check-evidence-manifests.mjs A 面契约）：stand-evidence
// 命名空间下的批次必须带 manifest.json，否则引擎 TTL 清扫 fail-closed ⇒ 永不回收。
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({
  createdAt: new Date().toISOString(), project: 'stand', batch: 'pdf-canvas-note-textlayer-' + Date.now(),
}) + '\n');

const PORT = Number(process.env.PORT || 8181);
const BASE = `http://127.0.0.1:${PORT}`;
const ABS = '/tmp/pdf-canvas-note-fixture/readme.pdf';
const FILE = 'readme.pdf';
const SHA = 'b'.repeat(64);
const BASELINE = '5b99db0b9';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rows = {};
let failCount = 0;
const t = (name, cond, extra = '') => {
  rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`;
  if (!cond) failCount++;
};

/* ══ 夹具 PDF（真文本内容流：Helvetica 标准 14 字体，选所需真文本）══════════ */
const enc = new TextEncoder();
function buildTextPdf(lines) {
  const parts = []; let len = 0; const offsets = new Map();
  const w = (s) => { const b = typeof s === 'string' ? enc.encode(s) : s; parts.push(b); len += b.length; };
  const obj = (n, body) => { offsets.set(n, len); w(`${n} 0 obj\n`); body(); w('\nendobj\n'); };
  w('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');
  const esc = (s) => s.replace(/[\\()]/g, (c) => '\\' + c);
  const stream = ['BT', '/F1 22 Tf', '30 TL', '54 700 Td', ...lines.map((l) => `(${esc(l)}) Tj T*`), 'ET'].join('\n');
  obj(1, () => w('<< /Type /Catalog /Pages 2 0 R >>'));
  obj(2, () => w('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'));
  obj(3, () => w('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'));
  obj(4, () => w(`<< /Length ${enc.encode(stream).length} >>\nstream\n${stream}\nendstream`));
  obj(5, () => w('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'));
  const xrefPos = len;
  let x = 'xref\n0 6\n0000000000 65535 f \n';
  for (let i = 1; i <= 5; i++) x += `${String(offsets.get(i)).padStart(10, '0')} 00000 n \n`;
  w(x);
  w(`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF`);
  return Buffer.concat(parts);
}
const PDF_LINES = [
  'Stand canvas note and ask now read PDFs.',
  'Select this sentence and press the note button.',
  'The page anchor travels with the captured excerpt.',
];
const PDF_BYTES = buildTextPdf(PDF_LINES);
const PDF_TEXT_WORDS = ['Select', 'sentence', 'note', 'anchor'];

/* ══ 本机 HTTP 服务：静态 web 树 + /api 夹具（记账请求腿）═══════════════════ */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.wasm': 'application/wasm',
  '.pdf': 'application/pdf', '.bcmap': 'application/octet-stream',
};
/** 请求腿台账（用于 T4 零新路由 + T2 载荷断言）。 */
const apiLog = [];
const noteStore = new Map(); // page → { text, snippet, version }

function readBody(req) {
  return new Promise((res) => { let b = ''; req.on('data', (c) => { b += c; }); req.on('end', () => res(b)); });
}
const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };

const server = createServer(async (req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';

  if (p.startsWith('/api/')) {
    const raw = await readBody(req);
    let body = {}; try { body = JSON.parse(raw || '{}'); } catch { body = {}; }
    apiLog.push({ method: req.method, path: p, query: u.search, body });

    if (p === '/api/sessions') return json(res, 200, { sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' });
    if (p === '/api/nf-ticket') {
      const paths = Array.isArray(body.paths) ? body.paths : [];
      const tickets = {};
      for (const q of paths) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
      return json(res, 200, { tickets, rejected: [] });
    }
    if (p === '/api/nf-file') {
      res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': PDF_BYTES.length, 'Accept-Ranges': 'bytes' });
      return res.end(PDF_BYTES);
    }
    if (p === '/api/pdf/open') {
      return json(res, 200, { sha256: SHA, parsed: false, manifest: { page_count: 1 }, note_pages: [...noteStore.keys()] });
    }
    if (p === '/api/pdf/note' && req.method === 'GET') {
      const page = Number(u.searchParams.get('page') || 0);
      const n = noteStore.get(page);
      return json(res, 200, { note: n ? { text: n.text, version: n.version } : null, page_verified: false, out_of_range: false });
    }
    if (p === '/api/pdf/note' && req.method === 'POST') {
      const page = Number(body.page || 0);
      const prev = noteStore.get(page);
      const version = (prev ? prev.version : 0) + 1;
      noteStore.set(page, { text: String(body.text || ''), snippet: String(body.snippet || ''), version });
      return json(res, 200, { version, note_pages: [...noteStore.keys()] });
    }
    return json(res, 200, {});
  }

  const file = resolve(join(WEB, p));
  if (!file.startsWith(WEB)) { res.writeHead(403); return res.end('forbidden'); }
  let buf; try { buf = readFileSync(file); } catch { res.writeHead(404); return res.end('nf'); }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(buf);
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

/* ══ WS 夹具（configData / sessionList / getHistory / askOpen / ping）════════ */
async function routeWs(page) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1',
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'askOpen') ws.send(JSON.stringify({
        type: 'askState', sessionId: 'ask-' + SHA.slice(0, 8), sessionKey: 'ask:xxxx',
        file: ABS, fileName: FILE, messages: [],
      }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
}

async function bootPage(browser, opts = {}) {
  const ctx = await browser.newContext({
    viewport: opts.viewport || { width: 1440, height: 900 },
    deviceScaleFactor: opts.deviceScaleFactor || 1,
    isMobile: !!opts.isMobile, hasTouch: !!opts.hasTouch,
    ...(opts.recordVideo ? { recordVideo: { dir: VIDEO_DIR, size: opts.viewport } } : {}),
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 240)));
  await routeWs(page);
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  return { ctx, page, errors };
}

/** 走 app 自己的真实打开入口（`popArtifacts.openPopArtifact` = Pop 文件卡 /
 *  搜索结果共用腿，乐观打开 ⇒ 票据腿取字节 ⇒ 真 canvas 标签 + 真 viewer），
 *  等 PDF 页真渲出。 */
async function openPdf(page) {
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (mobile) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); });
    await sleep(700);
  }
  await page.evaluate(async ({ absPath, title }) => {
    const { openPopArtifact } = await import('/js/popArtifacts.js');
    openPopArtifact(absPath, title);
  }, { absPath: ABS, title: FILE });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"] canvas.pdf-page[width]`, { timeout: 30000 });
  await page.waitForFunction(() => {
    const s = document.querySelector('.canvas-tab-pane .textLayer span');
    return !!(s && (s.textContent || '').trim());
  }, null, { timeout: 30000 });
  await page.waitForFunction(() => {
    const f = document.querySelector('.note-fab');
    return f && !f.classList.contains('gate-off');
  }, null, { timeout: 15000 });
  await sleep(300);
}

/** 真 DOM 选区：mouse drag 从左到右划过前两条文本行（真实选法）。 */
async function dragSelect(page) {
  // 先把文本行滚进真视口（手机档页面高于视口，行可能在折线以下 ⇒ 坐标出视口
  // 就选不中）。
  await page.evaluate(() => {
    const s = document.querySelector('.canvas-tab-pane .textLayer span');
    if (s && s.scrollIntoView) s.scrollIntoView({ block: 'center' });
  });
  await sleep(250);
  const box = await page.evaluate(() => {
    const spans = [...document.querySelectorAll('.canvas-tab-pane .textLayer span')].filter((s) => (s.textContent || '').trim());
    if (spans.length < 2) return null;
    const vp = { w: window.innerWidth, h: window.innerHeight };
    const a = spans[0].getBoundingClientRect(); const b = spans[1].getBoundingClientRect();
    const clamp = (v, hi) => Math.max(2, Math.min(hi - 2, v));
    return {
      x1: clamp(a.left + 2, vp.w), y1: clamp(a.top + a.height / 2, vp.h),
      x2: clamp(b.left + b.width - 2, vp.w), y2: clamp(b.top + b.height / 2, vp.h),
      inView: a.top > 0 && b.bottom < vp.h,
    };
  });
  if (!box) return null;
  await page.mouse.move(box.x1, box.y1);
  await page.mouse.down();
  await page.mouse.move(box.x1 + (box.x2 - box.x1) / 2, box.y1 + 6, { steps: 6 });
  await page.mouse.move(box.x2, box.y2, { steps: 6 });
  await page.mouse.up();
  await sleep(150);
  const paneSel = await page.evaluate((absPath) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`);
    const fn = pane && pane._nfPdfSelection;
    return typeof fn === 'function' ? fn() : null;
  }, ABS);
  const domSel = await page.evaluate(() => window.getSelection().toString());
  return { paneSel, domSel, inView: box.inView };
}

const pdfRouteSegs = () => [...new Set(apiLog
  .filter((r) => r.path.startsWith('/api/pdf/'))
  .map((r) => r.path.slice('/api/pdf/'.length)))].sort();

/* ══ 站 S1（桌面 1440×900）：文本层 / 页锚 note / 同钮 ask / 零新路由 / 单壳 ══ */
{
  const browser = await chromium.launch();
  try {
    apiLog.length = 0; noteStore.clear();
    const { ctx, page, errors } = await bootPage(browser);
    try {
      await openPdf(page);

      // T1 文本层在位 + 与位图同盒 + --scale-factor 恒等
      const tl = await page.evaluate((absPath) => {
        const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`);
        const c = pane.querySelector('canvas.pdf-page[width]');
        const wrap = c.closest('.pdf-page-wrap');
        const layer = wrap.querySelector('.textLayer');
        const spans = [...layer.querySelectorAll('span')].filter((s) => (s.textContent || '').trim());
        const cs = getComputedStyle(layer);
        const cBox = c.getBoundingClientRect(); const lBox = layer.getBoundingClientRect();
        // 位图 CSS 盒宽 = viewport.scale × baseWidth；文本层 --scale-factor 必须同值
        const sf = Number(getComputedStyle(layer).getPropertyValue('--scale-factor'));
        return {
          hasLayer: !!layer, spanCount: spans.length, position: cs.position,
          text: spans.map((s) => s.textContent).join(' '),
          sameBox: Math.abs(cBox.width - lBox.width) <= 1 && Math.abs(cBox.height - lBox.height) <= 1,
          sf, bmpRatio: c.clientWidth ? cBox.width / c.clientWidth : 0,
          dataScale: layer.dataset.textLayerScale,
        };
      }, ABS);
      t('T1a `.pdf-page-wrap .textLayer` 在位 + span 非空（PDF 可选中）',
        tl.hasLayer && tl.spanCount >= PDF_LINES.length && PDF_TEXT_WORDS.every((x) => tl.text.includes(x)),
        `spans=${tl.spanCount} pos=${tl.position} text="${tl.text.slice(0, 80)}"`);
      t('T1b 文本层与位图同盒（inset:0 逐像素叠合）', tl.position === 'absolute' && tl.sameBox,
        `pos=${tl.position} sameBox=${tl.sameBox}`);
      t('T1c `--scale-factor` = 当前渲染 scale（data-text-layer-scale 同步落标）',
        tl.sf > 0 && tl.dataScale === String(tl.sf), `sf=${tl.sf} data=${tl.dataScale}`);

      // T2 选中 → 页锚 note（同一颗 .note-fab）
      const selRes = await dragSelect(page);
      t('T2a 真 DOM 选区落在文本层内（_nfPdfSelection 返回页锚 + 摘录）',
        !!selRes && !!selRes.paneSel && selRes.paneSel.page === 1 && selRes.paneSel.text.trim().length > 0,
        JSON.stringify(selRes && selRes.paneSel));
      const beforePdfCalls = apiLog.filter((r) => r.path.startsWith('/api/pdf/')).length;
      await page.click('.note-fab');
      await page.waitForFunction(() => {
        const d = document.querySelector('.note-dock');
        return d && !d.classList.contains('closed');
      }, null, { timeout: 10000 });
      await sleep(500);
      const opened = await page.evaluate(() => {
        const d = document.querySelector('.note-dock');
        const st = d.querySelector('[data-note-status]');
        return {
          open: !d.classList.contains('closed'),
          status: st ? st.textContent : '',
          noteFabCount: document.querySelectorAll('.note-fab').length,
          askFabOnlyCount: document.querySelectorAll('.ask-fab:not(.note-fab)').length,
        };
      });
      const getCalls = apiLog.filter((r) => r.path === '/api/pdf/note' && r.method === 'GET');
      const openCalls = apiLog.filter((r) => r.path === '/api/pdf/open');
      t('T2b 同一颗钮 `.note-fab`（DOM 内唯一；ask 钮 = `.ask-fab:not(.note-fab)` 唯一）开同一扇 `.note-dock`',
        opened.open && opened.noteFabCount === 1 && opened.askFabOnlyCount === 1, JSON.stringify(opened));
      t('T2c 开窗读腿 = 既有 GET /api/pdf/note?sha256&page（页锚=选中页）',
        openCalls.length >= 1 && getCalls.length >= 1
          && getCalls[0].query.includes('sha256=' + SHA) && getCalls[0].query.includes('page=1'),
        JSON.stringify({ openCalls: openCalls.length, get: getCalls.map((c) => c.query) }));

      // 打字 → 防抖落盘（POST 载荷：page 页锚 + snippet=选中摘录）
      const TYPED = 'T2 页锚备注正文（自动验收）';
      await page.fill('.note-dock .note-body textarea', TYPED);
      await sleep(900);
      const post = apiLog.filter((r) => r.path === '/api/pdf/note' && r.method === 'POST').at(-1);
      const savedStatus = await page.evaluate(() => document.querySelector('.note-dock [data-note-status]')?.textContent || '');
      t('T2d 写腿 POST /api/pdf/note 载荷含 page 页锚 + snippet=选中摘录 + expected_version',
        !!post && post.body.sha256 === SHA && post.body.page === 1 && post.body.text === TYPED
          && typeof post.body.snippet === 'string' && post.body.snippet.trim().length > 0
          && PDF_TEXT_WORDS.some((x) => post.body.snippet.includes(x))
          && post.body.expected_version === 0,
        JSON.stringify(post && post.body));
      t('T2e 状态行落「第 1 页 · 已带选中摘录」事实（选中页 = 页锚）',
        savedStatus.includes('1') && /已带选中摘录|selection captured/.test(savedStatus), savedStatus);
      await page.screenshot({ path: join(OUT, 'pdf-note-desktop.png') });

      // T5 单套外壳：标记当前 .note-dock 节点
      await page.evaluate(() => { const d = document.querySelector('.note-dock'); d.__nfShellProbe = 'pdf-shell'; });

      // T3 关 note → 重新选文 → 同一颗 .ask-fab 开 ask → 提问正文预填（同组装件）
      await page.click('.note-fab');
      await sleep(400);
      const sel2 = await dragSelect(page);
      const askValueProbe = await page.evaluate((absPath) => {
        const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`);
        const fn = pane && pane._nfPdfSelection;
        return typeof fn === 'function' ? fn() : null;
      }, ABS);
      await page.click('.ask-fab');
      await page.waitForFunction(() => {
        const d = document.querySelector('.ask-dock');
        return d && !d.classList.contains('closed');
      }, null, { timeout: 10000 });
      await sleep(600);
      const ask = await page.evaluate(() => ({
        open: !document.querySelector('.ask-dock').classList.contains('closed'),
        value: document.querySelector('.ask-dock .ask-input textarea')?.value || '',
        noteClosed: document.querySelector('.note-dock').classList.contains('closed'),
      }));
      t('T3a `.ask-fab`（同一颗钮）开同一扇 `.ask-dock`，note 窗排他收起',
        ask.open && ask.noteClosed);
      t('T3b 提问正文 = 同一组装件产物（含当拍选中文字，非空）',
        !!askValueProbe && askValueProbe.text.trim().length > 0
          && ask.value.trim().length > 0
          && ask.value.includes(askValueProbe.text.trim().slice(0, 12)),
        JSON.stringify({ sel: askValueProbe && askValueProbe.text.slice(0, 40), value: ask.value.slice(0, 120) }));

      // T4 零新路由
      const segs = pdfRouteSegs();
      t('T4 全程 `/api/pdf/<x>` 取值集合 ⊆ {open, note}（零新路由）',
        segs.every((s) => s === 'open' || s === 'note') && segs.length > 0,
        JSON.stringify(segs));

      // T5（续）+ T6：md 态同壳、且零 /api/pdf 调用（数据落点差异）
      const mdAbs = '/tmp/pdf-canvas-note-fixture/note.md';
      const pdfCallsBeforeMd = apiLog.filter((r) => r.path.startsWith('/api/pdf/')).length;
      await page.evaluate(({ absPath, content }) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'note.md', content, absPath, pinned: true },
        }));
      }, { absPath: mdAbs, content: '# md\n\nfixture body\n' });
      await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${mdAbs}"]`, { timeout: 15000 });
      await sleep(500);
      const mdProbe = await page.evaluate(() => {
        const d = document.querySelector('.note-dock');
        return { sameNode: d && d.__nfShellProbe === 'pdf-shell', fabCount: document.querySelectorAll('.note-fab').length };
      });
      await page.click('.note-fab');
      await sleep(300);
      await page.fill('.note-dock .note-body textarea', 'md 纯文本草稿（验收）');
      await sleep(900);
      const pdfCallsAfterMd = apiLog.filter((r) => r.path.startsWith('/api/pdf/')).length;
      const mdNoteStatus = await page.evaluate(() => document.querySelector('.note-dock [data-note-status]')?.textContent || '');
      t('T5 PDF 态与 md 态的 `.note-dock` = 同一节点（单套外壳，非第二个面板）',
        mdProbe.sameNode && mdProbe.fabCount === 1, JSON.stringify(mdProbe));
      t('T6 md 态下 note **零** /api/pdf 调用（落点差异：md=纯文本草稿 / pdf=页锚备注）',
        pdfCallsAfterMd === pdfCallsBeforeMd,
        `pdfCalls ${pdfCallsBeforeMd} → ${pdfCallsAfterMd} (md status="${mdNoteStatus}")`);
      t('T7 桌面站零致命 JS 错误',
        errors.filter((e) => !/favicon|net::|WebSocket|ws closed/i.test(e)).length === 0,
        errors.join(' | ').slice(0, 200));

      await page.screenshot({ path: join(OUT, 'pdf-note-desktop-md-shell.png') });
    } catch (e) {
      t('S1 桌面站异常（站点中断）', false, String((e && e.message) || e).slice(0, 220));
    } finally { await ctx.close(); }
  } finally { await browser.close(); }
}

/* ══ 站 S2（手机 375×812 真全屏 + 录像）：同一流程实测 ═════════════════════ */
{
  const browser = await chromium.launch();
  let videoPath = null;
  try {
    apiLog.length = 0; noteStore.clear();
    const { ctx, page, errors } = await bootPage(browser, {
      viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, recordVideo: true,
    });
    try {
      await openPdf(page);
      const full = await page.evaluate(() => ({
        mobileLayer: document.body.classList.contains('mobile-layer'),
        innerW: window.innerWidth, innerH: window.innerHeight,
      }));
      t('M1 手机真全屏形态（mobile-layer + 视口 375×812）',
        full.mobileLayer && full.innerW === 375, JSON.stringify(full));

      const tl = await page.evaluate(() => {
        const layer = document.querySelector('.canvas-tab-pane .textLayer');
        const spans = layer ? [...layer.querySelectorAll('span')].filter((s) => (s.textContent || '').trim()) : [];
        const sf = layer ? Number(getComputedStyle(layer).getPropertyValue('--scale-factor')) : 0;
        return { has: !!layer, spans: spans.length, sf, text: spans.map((s) => s.textContent).join(' ') };
      });
      t('M2 手机端文本层在位且非空（span 有真文本）',
        tl.has && tl.spans >= PDF_LINES.length && tl.sf > 0 && PDF_TEXT_WORDS.every((x) => tl.text.includes(x)),
        JSON.stringify({ spans: tl.spans, sf: tl.sf }));

      const sel = await dragSelect(page);
      t('M3 手机端选中实测：真 DOM 选区 ⇒ _nfPdfSelection = 页锚 + 摘录',
        !!sel && !!sel.paneSel && sel.paneSel.page === 1 && sel.paneSel.text.trim().length > 0,
        JSON.stringify(sel && sel.paneSel));

      await page.tap('.note-fab');
      await page.waitForFunction(() => {
        const d = document.querySelector('.note-dock');
        return d && !d.classList.contains('closed');
      }, null, { timeout: 10000 });
      await sleep(600);
      const TYPED = '手机端页锚备注（验收）';
      await page.fill('.note-dock .note-body textarea', TYPED);
      await sleep(1000);
      const post = apiLog.filter((r) => r.path === '/api/pdf/note' && r.method === 'POST').at(-1);
      t('M4 手机端页锚 note 落盘（POST 载荷 page=1 + snippet=选中摘录）',
        !!post && post.body.page === 1 && post.body.text === TYPED
          && typeof post.body.snippet === 'string' && post.body.snippet.trim().length > 0,
        JSON.stringify(post && post.body));

      await page.tap('.note-fab');
      await sleep(400);
      const sel2 = await dragSelect(page);
      await page.tap('.ask-fab');
      await page.waitForFunction(() => {
        const d = document.querySelector('.ask-dock');
        return d && !d.classList.contains('closed');
      }, null, { timeout: 10000 });
      await sleep(700);
      const askVal = await page.evaluate(() => document.querySelector('.ask-dock .ask-input textarea')?.value || '');
      t('M5 手机端同一颗 ask 钮预填当拍选中正文（同组装件）',
        !!sel2 && !!sel2.paneSel && sel2.paneSel.text.trim().length > 0
          && askVal.trim().length > 0 && askVal.includes(sel2.paneSel.text.trim().slice(0, 12)),
        JSON.stringify({ sel: sel2 && sel2.paneSel && sel2.paneSel.text.slice(0, 40), value: askVal.slice(0, 120) }));
      await page.screenshot({ path: join(OUT, 'pdf-note-mobile.png') });
      t('M6 手机站零致命 JS 错误',
        errors.filter((e) => !/favicon|net::|WebSocket|ws closed/i.test(e)).length === 0,
        errors.join(' | ').slice(0, 200));
    } catch (e) {
      t('S2 手机站异常（站点中断）', false, String((e && e.message) || e).slice(0, 220));
    } finally {
      const v = page.video();
      await ctx.close();
      if (v) { try { videoPath = await v.path(); } catch { videoPath = null; } }
    }
  } finally { await browser.close(); }
  if (videoPath) {
    const gif = join(OUT, 'pdf-note-mobile.gif');
    const r = spawnSync('ffmpeg', ['-y', '-i', videoPath, '-vf', 'fps=10,scale=375:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse', '-loop', '0', gif], { encoding: 'utf8' });
    t('M7 手机动效 GIF 产出（ffmpeg 转码 exit 0）', r.status === 0, (r.stderr || '').split('\n').slice(-3).join(' '));
  } else {
    t('M7 手机动效 GIF 产出（ffmpeg 转码 exit 0）', false, 'no video path');
  }
}

/* ══ 站 S3：侧边栏删净 —— 本树绿 / 基线红 的负控 ═════════════════════════ */
{
  const NEEDLES = ['pdfPanel', 'initPdfPanel', 'pdf-btn', 'panel-pdf', 'pdf-panel-body', '.pdfp-'];
  const walk = (dir, acc = []) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) { if (name === 'vendor' || name === 'node_modules') continue; walk(p, acc); }
      else if (['.js', '.mjs', '.css', '.html'].includes(extname(p))) acc.push(p);
    }
    return acc;
  };
  const hitsIn = (files, read) => {
    const hits = [];
    for (const f of files) {
      const text = read(f); if (text == null) continue;
      text.split(/\r?\n/).forEach((line, i) => {
        for (const n of NEEDLES) if (line.includes(n)) hits.push(`${f.split('/web/')[1] || f}:${i + 1}:${n}`);
      });
    }
    return hits;
  };
  const missing = ['js/pdfPanel.js', 'css/pdfPanel.css'].filter((rel) => existsSync(join(WEB, rel)));
  const curHits = hitsIn(walk(WEB), (f) => { try { return readFileSync(f, 'utf8'); } catch { return null; } });
  t('S1a 本树：侧边栏件不在盘上（js/pdfPanel.js · css/pdfPanel.css）', missing.length === 0, missing.join(','));

  // 负控（红）：同扫描在基线提交 5b99db0b9 上必须命中
  const baselineFiles = ['src/main/resources/web/js/pdfPanel.js', 'src/main/resources/web/css/pdfPanel.css',
    'src/main/resources/web/index.html', 'src/main/resources/web/js/main.js'];
  const baseHits = [];
  for (const rel of baselineFiles) {
    const r = spawnSync('git', ['show', `${BASELINE}:${rel}`], { cwd: REPO, encoding: 'utf8' });
    if (r.status !== 0) continue;
    r.stdout.split(/\r?\n/).forEach((line, i) => {
      for (const n of NEEDLES) if (line.includes(n)) baseHits.push(`${rel}:${i + 1}:${n}`);
    });
  }
  t('S1b 本树：侧边栏身份串零命中（含孤儿 CSS 选择器）', curHits.length === 0, curHits.slice(0, 6).join(' | '));
  t('S1c 负控有效：同一扫描在基线 5b99db0b9 上命中（删掉的入口再被引用会红）',
    baseHits.length > 0, `baseline hits=${baseHits.length} e.g. ${baseHits.slice(0, 3).join(' | ')}`);
}

/* ══ 收口 ══════════════════════════════════════════════════════════════════ */
server.close();
console.log('\n== pdf-canvas-note-textlayer ==');
for (const [name, verdict] of Object.entries(rows)) {
  console.log(`  ${verdict.startsWith('PASS') ? 'PASS' : 'FAIL'}  ${name}${verdict.startsWith('PASS') ? '' : '  ' + verdict.slice(5)}`);
}
console.log(`\n${Object.keys(rows).length} checks, ${failCount} failed`);
console.log(`evidence: ${OUT}`);
process.exit(failCount ? 1 : 0);
