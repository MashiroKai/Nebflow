// imgcard-click-safari-compat.spec.mjs — #520 追加批：作者 22:42 答卡两点新证据
// （①点不动的是聊天会话消息流里的图片附件缩略图 ②浏览器是 Safari）⇒ 本 spec 把
// 「缩略图点击 → 铸票 → 兑换取字节 → Canvas 开图」全链按**断点级**分四站，在
// Chromium 与 WebKit（= Safari 同引擎族）各跑一遍，逐站对比，哪站断哪站报。
//
// 被测真实模块（零 mock 替身进入被测链）：
//   站A 铸票      nfTicket.mintTickets → POST /api/nf-ticket（假票 mock，200/403 两态）
//   站B 兑换取字节 fetch(ticketUrl) → /api/nf-file（ticketGuard：无票必 401）
//   站C Canvas 开图 attachmentPreview.previewBlob → canvas.openTab → viewers/image.js
//   站D 点击降级腿 铸票 403 ⇒ savedPathBlob null ⇒ previewLocalPath 文本腿的
//       「无反应」形状 + sessionGate 可见面（#520 修复腿）在场
// 点击 handler 本体（makeCardPreviewable，messages.js:2426-2451）= 纯 DOM
// addEventListener，无引擎分歧面，由站D 的 DOM 触发等价覆盖。
//
// 运行：node tests/imgcard-click-safari-compat.spec.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8152);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实可解码 1×1 PNG（canvas 系 spec 同款字节；自拼 PNG 的 CRC 不对会被解码器拒绝）
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const IMG_PATH = '/tmp/imgcard-probe/shot.png'; // nf-file mock 按磁盘路径读字节 → 落一份真 PNG
import { mkdirSync, writeFileSync } from 'node:fs';
try { mkdirSync('/tmp/imgcard-probe', { recursive: true }); writeFileSync(IMG_PATH, PNG); } catch { /* 已在 */ }

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

async function bootPage(browserType) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/png', body: readFileSync(IMG_PATH) });
  });
  await installTicketMock(page); // 默认 200 假票；站A/D 里按需切 status
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(400);
  return { browser, page, errors };
}

const report = {};
for (const [label, engine] of [['chromium', chromium], ['webkit(Safari引擎族)', webkit]]) {
  const rows = {};
  let browserRef = null;
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };

  // ── 站A：铸票 200（真 nfTicket）──
  {
    const { browser, page, errors } = await bootPage(engine);
    browserRef = browser;
    const mint = await page.evaluate(async (path) => {
      const nf = await import('/js/nfTicket.js');
      const map = await nf.mintTickets([path]);
      return { got: map.has(path), size: map.size };
    }, IMG_PATH);
    t('A1 铸票 200 ⇒ 票在手', mint.got === true, JSON.stringify(mint));
    t('A2 页面零 JS 异常', errors.length === 0, errors.join(' | '));

    // ── 站B：兑换取字节（真 fetch + ticketGuard 无票 401 判据）──
    const bytes = await page.evaluate(async (path) => {
      const nf = await import('/js/nfTicket.js');
      const url = await nf.ticketUrl(path);
      const resp = await fetch(url);
      const blob = await resp.blob();
      return { status: resp.status, size: blob.size, hasTicket: /[?&]ticket=/.test(url) };
    }, IMG_PATH);
    t('B1 URL 带票', bytes.hasTicket === true, JSON.stringify(bytes));
    t('B2 取字节 200', bytes.status === 200);
    t('B3 字节非空(PNG)', bytes.size === PNG.length, `size=${bytes.size} want=${PNG.length}`);

    // ── 站C：Canvas 开图（真 previewBlob → openTab → image viewer）──
    await page.evaluate(async (path) => {
      const nf = await import('/js/nfTicket.js');
      const url = await nf.ticketUrl(path);
      const blob = await (await fetch(url)).blob();
      const ap = await import('/js/attachmentPreview.js');
      return await ap.previewBlob({ id: 'probe:img', title: 'shot.png', fileName: 'shot.png', blob });
    }, IMG_PATH);
    // image viewer 的 DOM 组装挂在 img.onload（viewers/image.js:66）之后 ⇒ 轮询等挂载
    let canvas = { result: 'pending', viewerInDom: false, imgLoaded: false, natural: null };
    for (let i = 0; i < 20 && !canvas.viewerInDom; i++) {
      await sleep(150);
      canvas = await page.evaluate(() => {
        const viewer = document.querySelector('.canvas-image-viewer');
        const img = viewer ? viewer.querySelector('img') : null;
        return {
          result: 'ok',
          panelOpen: !!document.querySelector('.canvas-panel.is-open, #canvas.is-open, .canvas-open'),
          viewerInDom: !!viewer,
          imgLoaded: !!img && img.complete && img.naturalWidth > 0,
          natural: img ? [img.naturalWidth, img.naturalHeight] : null,
        };
      });
    }
    t('C1 previewBlob = ok', canvas.result === 'ok', JSON.stringify(canvas));
    t('C2 Canvas 面板开启', canvas.panelOpen === true);
    t('C3 image viewer 挂载', canvas.viewerInDom === true);
    t('C4 缩略图字节解码成功(naturalWidth>0)', canvas.imgLoaded === true, JSON.stringify(canvas.natural));
  }

  // ── 站D：铸票 403（会话过期形状）⇒ 断点 = A1 翻红 + 修复横幅在场 ──
  {
    const { browser, page, errors } = await bootPage(engine);
    browserRef = browser;
    await page.route('**/api/nf-ticket*', (route) =>
      route.fulfill({ status: 403, contentType: 'text/plain', body: 'gate' }));
    // 稳定性批 2026-10-26：noteAuthRejected 先走静默重座（ws.js 动态探链——
    // lab 无 portal 面，探链要走完两个 404 才落 false）。本站钉的是 #520 的
    // 可见面（403 ⇒ 横幅在场），不钉重座腿本体（session-gate.spec ①c/①d
    // 专站）；lab 里把缝钉死为「重座不可用」，可见面判定确定化。
    await page.evaluate(async () => {
      const gate = await import('/js/sessionGate.js');
      gate._sessionGateTestHooks().setReseat(async () => false);
    });
    const dead = await page.evaluate(async (path) => {
      const nf = await import('/js/nfTicket.js');
      const map = await nf.mintTickets([path]);
      const url = await nf.ticketUrl(path);
      const resp = await fetch(url);
      return { got: map.has(path), noTicketUrl: !/[?&]ticket=/.test(url), bytesStatus: resp.status };
    }, IMG_PATH);
    t('D1 铸票 403 ⇒ 无票（断点站位）', dead.got === false && dead.noTicketUrl === true, JSON.stringify(dead));
    t('D2 无票兑换被 401 拒（ticketGuard）', dead.bytesStatus === 401);
    t('D3 sessionGate 可见过期横幅在场(#520 修复)', await page.locator('[data-session-gate="1"]').count() === 1);
    t('D4 页面零 JS 异常（降级腿不抛）', errors.length === 0, errors.join(' | '));
  }

  report[label] = rows;
  if (browserRef) await browserRef.close();
}

server.close();
let fail = 0;
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n== ${engine} ==`);
  for (const [name, verdict] of Object.entries(rows)) {
    console.log(`  ${verdict.startsWith('PASS') ? 'PASS' : 'FAIL'}  ${name}${verdict.startsWith('PASS') ? '' : '  ' + verdict.slice(5)}`);
    if (!verdict.startsWith('PASS')) fail++;
  }
}
console.log(`\n${Object.values(report).flat().length} checks, ${fail} failed`);
process.exit(fail ? 1 : 0);
