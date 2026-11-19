// external-open-shots.mjs — 「打开外部文件」入口的界面证据（external-open 批 2026-11-19）。
//
// 产出（默认 evidence/20261119_external_open/）：
//   d01-dialog-light.png   桌面 1440×900 亮：对话框（标题/提示/输入/粘贴/取消/打开）
//   d02-dialog-dark.png    桌面 1440×900 暗：同上
//   d03-deny-credential.png 桌面：**凭据路径被拒**的可见文案（负控 ①）
//   d04-deny-private.png   桌面：**私网 URL 被拒**的可见文案（负控 ②）
//   d05-open-url-image.png 桌面：公网 URL 拉取成功后 image viewer 打开（正控 ④）
//   d06-open-local-pdf.png 桌面：项目外本机 PDF 打开（pdf viewer）
//   m01-dialog-light.png   手机 375×812 亮
//   m02-dialog-dark.png    手机 375×812 暗
//   m03-open-url-image.png 手机：拉取后 canvas 打开
//   probes.json            探针读数（对话框在场 / 拒因文案 / 打开的 tab / 请求体）
//   flow.gif               交互 GIF（1512×900 → 960 宽，12fps）
//
// 载体说明：本脚本跑的是**真前端模块**（js/externalOpen.js + canvas.js +
// viewer 注册表），服务端以 mock 替代（/api/nf-external/open 的四种回答 +
// 既有 /api/nf-ticket、/api/nf-file、WS pop.readFile）。真实后端的闸与拉取读数
// 见 evidence 里的 curl 记录（scripts/verify-external-open.sh）——两条证据面
// 分工明确：本脚本证明**界面与交互**，curl 证明**服务端判据**。
//
// 运行：node tests/external-open-shots.mjs

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { extname, join, normalize, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const OUT = process.env.SHOTS_DIR || resolve(HERE, '../evidence/20261119_external_open');
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 素材：一份真 PDF、一张真 PNG、一个项目外文本 ─────────────────────────
const WORK = mkdtempSync(join(tmpdir(), 'nf-ext-shots-'));
const OUTSIDE = join(WORK, 'outside-project');
mkdirSync(OUTSIDE, { recursive: true });

const PDF_BYTES = Buffer.from(
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
  '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
  '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n' +
  '4 0 obj<</Length 62>>stream\nBT /F1 18 Tf 24 120 Td (External PDF — opened from outside the project) Tj ET\nendstream endobj\n' +
  '5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n' +
  'trailer<</Root 1 0 R>>\n%%EOF\n', 'latin1');
/** URL 腿的图片素材：用 ffmpeg 生成的中性测试图（testsrc），不掺个人照片；
 *  ffmpeg 不可用才回落内置小 PNG。 */
const GEN_PNG = join(WORK, 'fixture-testsrc.png');
function fixtureImageBytes() {
  try {
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi',
      '-i', 'testsrc=size=640x400:rate=1', '-frames:v', '1', GEN_PNG]);
    return readFileSync(GEN_PNG);
  } catch { return null; }
}
const PNG_BYTES = fixtureImageBytes() || Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAGQAAABkCAYAAABw4pVUAAAAXklEQVR4nO3BAQ0AAADCoPdPbQ8HFAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAvBsYAAAB3l0sAAAAASUVORK5CYII=',
  'base64');

const LOCAL_PDF = join(OUTSIDE, 'report.pdf');
writeFileSync(LOCAL_PDF, PDF_BYTES);
const URL_ABS = join(WORK, 'external-fetch', 'a1b2c3d4e5f60718', 'pic.png');
mkdirSync(dirname(URL_ABS), { recursive: true });
writeFileSync(URL_ABS, PNG_BYTES);

/** 每次 /api/nf-external/open 的请求体（探针面）。 */
const openCalls = [];

// ── 静态服务 + API mock ────────────────────────────────────────────────
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.json': 'application/json', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ico': 'image/x-icon',
};

function startServer() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const p = decodeURIComponent(url.pathname);
    const json = (status, obj, extraHeaders = {}) => {
      const body = JSON.stringify(obj);
      res.writeHead(status, Object.assign({ 'Content-Type': 'application/json' }, extraHeaders));
      res.end(body);
    };
    const readBody = () => new Promise((ok) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => ok(Buffer.concat(chunks).toString('utf8')));
    });
    if (p === '/api/nf-external/open' && req.method === 'POST') {
      return readBody().then((raw) => {
        let body = {};
        try { body = JSON.parse(raw || '{}'); } catch { body = {}; }
        openCalls.push(body);
        const target = String(body.path || body.url || '');
        // ① 凭据路径：真实服务端由 NfFilePolicy 身份层拒（403 credential-path）
        if (/\/\.ssh\/|auth\.json$|\/secrets\//.test(target)) {
          res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8', 'X-Nf-Reason': 'credential-path' });
          return res.end('credential-path: this path is a known credential location');
        }
        // ② 私网 URL：真实服务端由地址判据拒（403 url-private-address）
        if (/^https?:\/\/(127\.|localhost|10\.|192\.168\.|169\.254\.)/i.test(target)) {
          res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8', 'X-Nf-Reason': 'url-private-address' });
          return res.end("url-private-address: 'localhost' resolves to 127.0.0.1 — private, loopback, link-local and metadata addresses are refused");
        }
        // ④ 两条正路
        if (/^https?:\/\//i.test(target)) {
          writeFileSync(URL_ABS, PNG_BYTES);
          return json(200, {
            ok: true, kind: 'url', absPath: URL_ABS, fileName: 'pic.png',
            itemType: 'image', binary: true, size: PNG_BYTES.length,
            sha256: 'a1b2c3d4e5f60718' + '0'.repeat(48),
            finalUrl: target, active: false,
          });
        }
        return json(200, {
          ok: true, kind: 'local', absPath: LOCAL_PDF, fileName: 'report.pdf',
          itemType: 'pdf', binary: true, size: PDF_BYTES.length,
        });
      });
    }
    if (p === '/api/nf-ticket' && req.method === 'POST') {
      return readBody().then((raw) => {
        let paths = [];
        try { paths = JSON.parse(raw || '{}').paths || []; } catch { paths = []; }
        const tickets = {};
        for (const x of paths) tickets[x] = { t: 'ticket-' + Buffer.from(x).toString('hex').slice(0, 8), exp: Date.now() + 3600_000, uses: -1 };
        return json(200, { tickets, rejected: [] });
      });
    }
    if (p.startsWith('/api/nf-file')) {
      const which = url.searchParams.get('path') || '';
      const bytes = which.endsWith('.pdf') ? PDF_BYTES : PNG_BYTES;
      res.writeHead(200, {
        'Content-Type': which.endsWith('.pdf') ? 'application/pdf' : 'image/png',
        'Content-Length': String(bytes.length),
        'Cache-Control': 'no-store',
      });
      return res.end(bytes);
    }
    if (p === '/api/canvas-writer') return json(200, { role: 'writer', epoch: 1 });
    if (p === '/api/canvas-tabs' && req.method === 'GET') return json(200, { v: 2, tabs: [], activeTabId: null });
    if (p === '/api/canvas-tabs') return readBody().then(() => json(200, { ok: true }));
    if (p.startsWith('/api/')) return json(200, {});
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    if (!file.startsWith(WEB)) { res.writeHead(403); return res.end('forbidden'); }
    try {
      const body = readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      return res.end(body);
    } catch {
      res.writeHead(404);
      return res.end('nf');
    }
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok({ server, port: server.address().port })));
}

const ITEM_TYPE = { pdf: 'pdf', png: 'image', jpg: 'image', md: 'markdown', txt: 'code' };

async function mockWs(page) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((msg) => {
      let frame = {};
      try { frame = JSON.parse(typeof msg === 'string' ? msg : msg.toString()); } catch { return; }
      if (frame.type === 'pop.readFile') {
        const abs = String(frame.path || '');
        const ext = (abs.split('.').pop() || '').toLowerCase();
        const binary = ext === 'pdf' || ext === 'png' || ext === 'jpg';
        const name = abs.split('/').pop();
        const payload = {
          type: 'fileContent',
          path: abs,
          absPath: abs,
          fileName: name,
          itemType: ITEM_TYPE[ext] || 'code',
          size: binary ? (ext === 'pdf' ? PDF_BYTES.length : PNG_BYTES.length) : 12,
        };
        if (!binary) payload.content = '# 外部文本\n';
        ws.send(JSON.stringify(payload));
      }
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ llm: { providers: {} }, features: {} }),
    }));
    ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: 'extopen', agentName: 'Nebula', title: 'external-open' }],
      activeId: 'extopen', folders: [],
    }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 'extopen', messages: [], hasMore: false, offset: 0 }));
  });
}

async function boot(browser, base, { width, height, mobile = false, theme = 'light', video = false }) {
  const context = await browser.newContext({
    viewport: { width, height },
    isMobile: mobile, hasTouch: mobile, deviceScaleFactor: 1, locale: 'zh-CN',
    colorScheme: theme,
    ...(video ? { recordVideo: { dir: join(WORK, 'video'), size: { width, height } } } : {}),
  });
  await context.addInitScript((t) => {
    try { localStorage.setItem('stand_locale', 'zh-CN'); } catch { /* ignore */ }
    document.addEventListener('DOMContentLoaded', () => {
      if (t === 'dark') document.documentElement.setAttribute('data-theme', 'dark');
    });
  }, theme);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message)));
  await mockWs(page);
  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await page.waitForTimeout(500);
  return { context, page, errors };
}

async function openExplorer(page) {
  // 侧栏默认就展开；这里只**等布局稳定**并记录入口按钮的可见读数（截图另存
  // d00-entry-button.png）。绝不盲点 #files-btn：再点 active 面板 = 折叠
  // （VSCode 语义），第一次跑这个脚本就踩了。
  await page.waitForTimeout(1200);
  const btn = await page.$('#explorer-open-external-btn');
  probes.entryInDom = !!btn;
  probes.entryVisible = btn ? await btn.isVisible() : false;
  return btn;
}

async function openDialog(page) {
  // 走**真实模块的导出入口**（按钮的 click 处理器调的就是它）；手机层侧栏是
  // 滑出层，按钮不在视口里，这条调用腿保证两端同一条路径。
  await page.evaluate(() => import('/js/externalOpen.js').then((m) => m.openExternalDialog()));
  await page.waitForSelector('.xopen-panel', { state: 'visible', timeout: 5000 });
  await page.waitForTimeout(250); // 入场动效
}

async function submit(page, value) {
  await page.fill('.xopen-input', value);
  await page.click('.xopen-open-btn');
  await page.waitForTimeout(600);
}

const probes = {
  entry: 'explorer header #explorer-open-external-btn',
  entryInDom: false,
  entryVisible: false,
  dialogVisible: false,
  denyCredential: '',
  denyPrivate: '',
  localPdfOpened: false,
  urlImageOpened: false,
  canvasTabs: 0,
  openRequests: openCalls,
  pageErrors: [],
};

const { server, port } = await startServer();
const BASE = `http://127.0.0.1:${port}/`;
const browser = await chromium.launch();

try {
  // ── 桌面亮：对话框 + 两种拒绝 ─────────────────────────────────────────
  let { context, page, errors } = await boot(browser, BASE, { width: 1440, height: 900, theme: 'light' });
  await openExplorer(page);
  if (probes.entryVisible) {
    const box = await page.evaluate(() => {
      const el = document.getElementById('explorer-section');
      const r = el.getBoundingClientRect();
      return { x: Math.max(0, Math.round(r.x)), y: Math.max(0, Math.round(r.y)), width: Math.round(r.width), height: 80 };
    });
    await page.screenshot({ path: join(OUT, 'd00-entry-button.png'), clip: box });
  }
  await openDialog(page);
  probes.dialogVisible = await page.isVisible('.xopen-panel');
  await page.screenshot({ path: join(OUT, 'd01-dialog-light.png') });

  await submit(page, '/Users/kaiyu/.ssh/id_rsa');
  probes.denyCredential = (await page.textContent('.xopen-err')) || '';
  await page.screenshot({ path: join(OUT, 'd03-deny-credential.png') });

  await submit(page, 'http://127.0.0.1:6138/api/nf-file?path=/etc/passwd');
  probes.denyPrivate = (await page.textContent('.xopen-err')) || '';
  await page.screenshot({ path: join(OUT, 'd04-deny-private.png') });

  // 正路①：项目外本机 PDF
  await submit(page, LOCAL_PDF);
  await page.waitForTimeout(1200);
  probes.localPdfOpened = (await page.$('.canvas-tab-pane iframe, #canvas-content canvas, #canvas-content .pdf-')) !== null;
  await page.screenshot({ path: join(OUT, 'd06-open-local-pdf.png') });
  probes.canvasTabs = await page.evaluate(() => document.querySelectorAll('.canvas-tab-pane').length);
  probes.pageErrors = errors;
  await context.close();

  // ── 桌面暗：对话框 + 公网 URL 正路 ────────────────────────────────────
  ({ context, page, errors } = await boot(browser, BASE, { width: 1440, height: 900, theme: 'dark' }));
  await openExplorer(page);
  await openDialog(page);
  await page.screenshot({ path: join(OUT, 'd02-dialog-dark.png') });
  await submit(page, 'https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/640px-PNG_transparency_demonstration_1.png');
  await page.waitForTimeout(1400);
  probes.urlImageOpened = (await page.$('#canvas-content img')) !== null;
  await page.screenshot({ path: join(OUT, 'd05-open-url-image.png') });
  probes.pageErrors = probes.pageErrors.concat(errors);
  await context.close();

  // ── 手机 375 × 亮/暗 ──────────────────────────────────────────────────
  for (const theme of ['light', 'dark']) {
    const r = await boot(browser, BASE, { width: 375, height: 812, mobile: true, theme });
    await openExplorer(r.page);
    await openDialog(r.page);
    await r.page.screenshot({ path: join(OUT, `m${theme === 'light' ? '01' : '02'}-dialog-${theme}.png`) });
    if (theme === 'dark') {
      await submit(r.page, 'https://example.com/assets/pic.png');
      await r.page.waitForTimeout(1200);
      await r.page.screenshot({ path: join(OUT, 'm03-open-url-image.png') });
    }
    probes.pageErrors = probes.pageErrors.concat(r.errors);
    await r.context.close();
  }

  // ── GIF：交互全过程（桌面亮）──────────────────────────────────────────
  const g = await boot(browser, BASE, { width: 1440, height: 900, theme: 'light', video: true });
  await openExplorer(g.page);
  await g.page.waitForTimeout(400);
  await openDialog(g.page);
  await g.page.waitForTimeout(600);
  await submit(g.page, '/Users/kaiyu/.ssh/id_rsa');
  await g.page.waitForTimeout(900);
  await submit(g.page, 'http://10.0.0.5/secret.png');
  await g.page.waitForTimeout(900);
  await submit(g.page, LOCAL_PDF);
  await g.page.waitForTimeout(1800);
  const video = g.page.video();
  const videoPath = video ? await video.path() : '';
  await g.page.close();
  await g.context.close();
  try {
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', videoPath,
      '-vf', 'fps=10,scale=960:-1:flags=lanczos', '-loop', '0', join(OUT, 'flow.gif')]);
    probes.gif = 'flow.gif';
  } catch (e) {
    probes.gif = 'ffmpeg failed: ' + String(e && e.message);
  }
} finally {
  await browser.close();
  server.close();
  writeFileSync(join(OUT, 'probes.json'), JSON.stringify(probes, null, 2));
}

console.log(JSON.stringify(probes, null, 2));
console.log('shots ->', OUT);
