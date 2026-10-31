// composer-slim-shots.mjs — 消息输入框垂直瘦身批（2026-10-31 作者令「消息输入框的
// 上下留白尽量小 让输入框的占比小一点 让主要信息凸显」）前后对照证据 + 回归断言。
//
// 改前面（三件）：
//   · #input-area 底缘外留白（桌面 max(10px, safe-area) / ≤768px 12px+safe-area）
//   · #input-bar 垂直内 padding（8px 12px）
//   · #input textarea 垂直内 padding（8px 14px）
// 手机层（body.mobile-layer）与桌面共用 #input-bar ⇒ 本批只动 input.css 单点 +
// mobile.css 的 ≤768px 底缘档，即为「主对话+移动档通用腿」。
//
// 本脚本：真 web 树 + 真 WS 桩（形制同 tests/chatinput-two-defects-shots.mjs），
// 亮/暗双主题 × 桌面/手机两视口；每档输出整视口截图（消息列+输入区同框，直观
// 对照留白压缩量）+ 几何探针（stdout）；并断言三条不变量：
//   A. 多行增长：textarea 6 行后 offsetHeight 显著增长（增长逻辑未动）；
//   B. 附件行零遮挡：附件 chip 条带底缘 ≤ textarea 顶缘，发送钮完整可见；
//   C. 发送钮不遮挡：发送钮包围盒全程完整落在视口内。
//
// 用法：TAG=before SHOTS_DIR=… node tests/composer-slim-shots.mjs
//       TAG=after  SHOTS_DIR=… node tests/composer-slim-shots.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TAG = process.env.TAG || 'before';
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(HERE, '..', 'evidence', '20261031_composer-slim', TAG);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-composerslim';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ── 历史夹具：AI/用户交替 10 条，把消息列撑出可滚形态（「主要信息凸显」对照面）──
const HISTORY = Array.from({ length: 10 }, (_, i) => i % 2 === 0
  ? { type: 'ai', text: `填充消息 ${i + 1}：输入区瘦身后，消息列表应获得更多的可视高度。`, timestamp: NOW - 300_000 + i * 10_000 }
  : { type: 'user', text: `收到，继续第 ${i + 1} 项。`, timestamp: NOW - 300_000 + i * 10_000 });

// ── 静态服务器 ──────────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// ── WS 桩 ───────────────────────────────────────────────────────────
function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

async function boot(colorScheme, viewport, hasTouch) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${TAG}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${TAG}] pageerror: ${e.message}`));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  return { ctx, page };
}

/** 几何探针：输入区链的垂直留白 + 遮挡不变量。 */
async function probe(page) {
  return page.evaluate(() => {
    const cs = (el) => el ? getComputedStyle(el) : null;
    const area = document.getElementById('input-area');
    const bar = document.getElementById('input-bar');
    const input = document.getElementById('input');
    const send = document.getElementById('send-btn');
    const strip = document.getElementById('attachment-preview');
    const chat = document.getElementById('chat');
    const a = cs(area), b = cs(bar), i = cs(input);
    const r = (el) => { const x = el.getBoundingClientRect(); return { top: x.top, bottom: x.bottom, left: x.left, right: x.right, h: x.height }; };
    const vw = window.innerWidth, vh = window.innerHeight;
    const sendRect = send ? r(send) : null;
    const sendVisible = sendRect
      && sendRect.bottom <= vh + 0.5 && sendRect.right <= vw + 0.5 && sendRect.top >= 0 && sendRect.left >= 0;
    // 遮挡：附件条（in-flow 行）底缘必须 ≤ textarea 顶缘（B12 红线语义）。
    let stripOccludes = false;
    if (strip && strip.children.length > 0 && input) {
      const sr = strip.getBoundingClientRect(), ir = input.getBoundingClientRect();
      stripOccludes = sr.bottom > ir.top + 0.5;
    }
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      areaPadBottom: a ? a.paddingBottom : null,
      barPadTop: b ? b.paddingTop : null,
      barPadBottom: b ? b.paddingBottom : null,
      inputPadTop: i ? i.paddingTop : null,
      inputPadBottom: i ? i.paddingBottom : null,
      barH: bar ? bar.offsetHeight : null,
      areaH: area ? area.offsetHeight : null,
      chatPadBottom: chat ? cs(chat).paddingBottom : null,
      inputH: input ? input.offsetHeight : null,
      gapBelowBar: area && bar ? (area.getBoundingClientRect().bottom - bar.getBoundingClientRect().bottom) : null,
      sendVisible,
      stripOccludes,
      vh,
    };
  });
}

const DESKTOP = { width: 1280, height: 860 };
const MOBILE = { width: 390, height: 844 };

for (const scheme of ['light', 'dark']) {
  // ── 桌面档：整视口截图（消息列 + 输入区同框）+ 几何探针 ──────────────
  {
    const { ctx, page } = await boot(scheme, DESKTOP, false);
    const p0 = await probe(page);
    ok(`desktop-${scheme} 发送钮完整可见（静息态）`, p0.sendVisible === true, JSON.stringify({ vh: p0.vh }));
    ok(`desktop-${scheme} 附件条零遮挡（静息态空条带）`, p0.stripOccludes === false);
    console.log(`GEOM desktop-${scheme} TAG=${TAG} ` + JSON.stringify(p0));
    await page.screenshot({ path: join(SHOTS_DIR, `desktop-${scheme}-rest.png`) });

    // A. 多行增长（增长逻辑不动）
    const input = page.locator('#input');
    await input.click();
    const h1 = p0.inputH;
    await input.fill(Array.from({ length: 6 }, (_, i) => `第 ${i + 1} 行：多行增长回归。`).join('\n'));
    await input.dispatchEvent('input');
    await sleep(400);
    const p1 = await probe(page);
    ok(`desktop-${scheme} A 多行增长（6 行后输入域变高）`, p1.inputH > h1 + 20, `h ${h1} → ${p1.inputH}`);
    ok(`desktop-${scheme} A 多行态发送钮仍完整可见`, p1.sendVisible === true);
    await page.screenshot({ path: join(SHOTS_DIR, `desktop-${scheme}-multiline.png`) });
    await input.fill('');
    await input.dispatchEvent('input');
    await sleep(200);

    // B. 附件行零遮挡 + 发送钮不遮挡
    await page.evaluate(async () => {
      const chatMod = await import('/js/chat.js');
      const { activeView } = await import('/js/chatView.js');
      activeView.pendingAttachments.push(
        { type: 'file', name: '输入框瘦身对照说明.md', mimeType: 'text/markdown' },
        { type: 'file', name: 'screenshot-evidence.png', mimeType: 'image/png' },
      );
      chatMod.renderAttachmentPreview();
    });
    await sleep(300);
    const p2 = await probe(page);
    ok(`desktop-${scheme} B 附件条零遮挡（chip 底 ≤ textarea 顶）`, p2.stripOccludes === false);
    ok(`desktop-${scheme} B 附件态发送钮完整可见`, p2.sendVisible === true);
    await page.screenshot({ path: join(SHOTS_DIR, `desktop-${scheme}-attach.png`) });
    await ctx.close();
  }

  // ── 手机档（mobile-layer ≤768px 自激活，含 dock）：截图 + 探针 ─────────
  {
    const { ctx, page } = await boot(scheme, MOBILE, true);
    await page.waitForFunction(() => window.__nfMobileLayer && window.__nfMobileLayer.active === true, null, { timeout: 15000 });
    await sleep(600);
    const p3 = await probe(page);
    ok(`mobile-${scheme} mobile-layer 已激活`, p3.mobileLayer === true);
    ok(`mobile-${scheme} 发送钮完整可见（dock 带内）`, p3.sendVisible === true, JSON.stringify({ vh: p3.vh, areaH: p3.areaH }));
    console.log(`GEOM mobile-${scheme} TAG=${TAG} ` + JSON.stringify(p3));
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}-rest.png`) });

    // 手机档多行增长 + 附件不遮挡（同一不变量）
    const input = page.locator('#input');
    await input.click();
    await input.fill(Array.from({ length: 5 }, (_, i) => `手机档第 ${i + 1} 行。`).join('\n'));
    await input.dispatchEvent('input');
    await sleep(400);
    const p4 = await probe(page);
    ok(`mobile-${scheme} A 多行增长`, p4.inputH > p3.inputH + 20, `h ${p3.inputH} → ${p4.inputH}`);
    ok(`mobile-${scheme} A 多行态发送钮仍完整可见`, p4.sendVisible === true);
    await page.evaluate(async () => {
      const chatMod = await import('/js/chat.js');
      const { activeView } = await import('/js/chatView.js');
      activeView.pendingAttachments.push({ type: 'file', name: 'mobile-evidence.png', mimeType: 'image/png' });
      chatMod.renderAttachmentPreview();
    });
    await sleep(300);
    const p5 = await probe(page);
    ok(`mobile-${scheme} B 附件条零遮挡`, p5.stripOccludes === false);
    ok(`mobile-${scheme} B 附件态发送钮完整可见`, p5.sendVisible === true);
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}-attach.png`) });
    await ctx.close();
  }
}

await browser.close();
await new Promise(r => server.close(r));
const errs = consoleErrors.filter((t) => !t.includes('favicon') && !/Failed to load resource.*404/.test(t));
console.log(`done TAG=${TAG} → ${SHOTS_DIR}`);
console.log(errs.length ? `console errors (${errs.length}):\n` + errs.slice(0, 10).join('\n') : 'console: no errors');
ok(`${TAG} console 零新增错误`, errs.length === 0, errs.slice(0, 3).join(' | '));
console.log(failures === 0 ? 'RESULT: ALL PASS' : `RESULT: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
