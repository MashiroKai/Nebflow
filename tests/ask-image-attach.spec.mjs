#!/usr/bin/env node
// ask-image-attach.spec.mjs — canvas ask 附图批（2026-11-22 作者令「让 canvas 的 ask
// 支持附件图片输入」）前端验收。自包含：静态服务本机起、WS = routeWebSocket mock、
// 不碰宿主 6138/8080（无 Scala 后端参与——入站帧形状与回放投影由 AskAttachmentSpec 钉）。
//
// 断言面：
//   A1 输入条有附件钮（可见、可聚焦）；隐藏 file input 接受 image/*
//   A2 选图 → 缩略托盘出 1 件（chip + 移除钮），发送前不出 askQuestion
//   A3 图 + 文字 发送 → askQuestion 帧带 attachments[0]（mimeType image/*、data 非空），
//      气泡内出图（.ask-msg-img）且正文在图下
//   A4 「图+无正文」发送 → 帧 content=""、attachments 仍在；气泡仍成行（不被当空行隐藏）
//   A5 回放：askState 的 user 行带 attachments[{path}] → 气泡图 src = /uploads/<sid>/<file>
//   A6 无图提问帧不带 attachments 键（纯文本逐字现状）
//   E  全程零 pageerror
//   S  截图：desktop 1280×900 + mobile 390×844，dark/light → evidence/ask-image-attach/
// Run: node tests/ask-image-attach.spec.mjs
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS_ROOT = process.env.EVIDENCE_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/ask-image-attach/evidence/ask-image-attach';
const PORT = Number(process.env.PORT || 8247);
const BASE = `http://127.0.0.1:${PORT}`;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
// 1×1 PNG（与 AskAttachmentSpec 同源 fixture 的合法字节）。
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC', 'base64');

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  let body;
  try { body = readFileSync(file); } catch { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(body);
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0, fail = 0;
const failures = [];
function ok(tag, name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  [${tag}]  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  if (cond) pass++; else { fail++; failures.push(line); }
}

async function boot(browser, { viewport, colorScheme }) {
  const mobile = viewport.width < 500;
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  const inbound = [];
  let ws = null;
  // 回放面：askOpen 时由测试侧决定下发的历史（可注入带附件的 user 行）。
  const replay = { messages: [] };
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (sock) => {
    ws = sock;
    sock.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') sock.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const abs = (m.file && m.file.absPath) || '';
        const sid = sidOf(abs);
        sock.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: abs, fileName: (m.file && m.file.name) || 'file', messages: replay.messages }));
      }
    });
    sock.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    sock.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Stand', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await sleep(mobile ? 800 : 700);
  return { ctx, page, errors, inbound, replay, wsSend: (obj) => ws && ws.send(JSON.stringify(obj)) };
}

async function openAskable(page, name) {
  const absPath = `/tmp/ask-image-fixture/${name}`;
  // 移动端：画布经底部 dock 的 canvas 键进入（与 canvas-ask-legibility 同律）。
  const isMobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (isMobile) {
    await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
    await sleep(800);
  }
  await page.evaluate(({ name: n, absPath: ap }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + ap, itemType: 'markdown', title: n, content: `# ${n}\n\nmock body\n`, absPath: ap, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
  await sleep(300);
  await page.click('.ask-fab');
  await sleep(350);
  return absPath;
}

const dockState = (page) => page.evaluate(() => ({
  attach: !!document.querySelector('.ask-dock .ask-attach'),
  attachVisible: (() => { const b = document.querySelector('.ask-dock .ask-attach'); return !!b && getComputedStyle(b).display !== 'none'; })(),
  fileInputAccept: (document.querySelector('.ask-dock input[type=file]') || {}).accept || null,
  chips: document.querySelectorAll('.ask-dock .ask-media-chip').length,
  userImgs: document.querySelectorAll('.ask-dock .ask-bub.user .ask-msg-img').length,
  userBubs: document.querySelectorAll('.ask-dock .ask-bub.user').length,
  lastUserText: (() => { const n = document.querySelectorAll('.ask-dock .ask-bub.user'); return n.length ? n[n.length - 1].textContent : null; })(),
  lastUserSrc: (() => { const n = document.querySelectorAll('.ask-dock .ask-bub.user .ask-msg-img'); return n.length ? n[n.length - 1].getAttribute('src') : null; })(),
}));

async function runCombo(browser, tag, viewport, colorScheme) {
  const shotsDir = join(SHOTS_ROOT, tag);
  mkdirSync(shotsDir, { recursive: true });
  const { ctx, page, errors, inbound, replay, wsSend } = await boot(browser, { viewport, colorScheme });
  try {
    const abs = await openAskable(page, '附图验收.md');
    const sid = sidOf(abs);
    const askFrames = () => inbound.filter((m) => m.type === 'askQuestion');

    // A1
    const s0 = await dockState(page);
    ok(tag, 'A1 附件钮可见 + 隐藏 file input 接受 image/*', s0.attach && s0.attachVisible && /image/.test(s0.fileInputAccept || ''), JSON.stringify(s0));
    await page.screenshot({ path: join(shotsDir, '01-attach-entry.png') });

    // A2 选图 → 托盘 1 件；发送前不出帧
    await page.setInputFiles('.ask-dock input[type=file]', { name: 'shot.png', mimeType: 'image/png', buffer: PNG });
    await sleep(900);
    const s1 = await dockState(page);
    ok(tag, 'A2 选图后托盘 1 件、未发送', s1.chips === 1 && askFrames().length === 0, JSON.stringify({ chips: s1.chips, frames: askFrames().length }));
    await page.screenshot({ path: join(shotsDir, '02-chip-tray.png') });

    // A3 图 + 文字
    const mark3 = inbound.length;
    await page.fill('.ask-dock textarea', '这张图里是什么？');
    await page.click('.ask-dock .ask-send');
    await sleep(1200);
    const f3 = inbound.slice(mark3).find((m) => m.type === 'askQuestion');
    const att3 = f3 && f3.attachments && f3.attachments[0];
    ok(tag, 'A3 图+文字 → 帧带 attachments[0]（image/*、data 非空）且正文保留', !!att3 && /^image\//.test(att3.mimeType) && att3.data.length > 0 && f3.content === '这张图里是什么？', JSON.stringify({ content: f3 && f3.content, mime: att3 && att3.mimeType }));
    const s3 = await dockState(page);
    ok(tag, 'A3 气泡内出图 + 正文在图下 + 托盘清空', s3.userImgs >= 1 && /这张图里是什么/.test(s3.lastUserText || '') && s3.chips === 0, JSON.stringify(s3));
    await page.screenshot({ path: join(shotsDir, '03-sent-image-text.png') });

    // 发送后让 turn 收口（回答 + done），再做下一轮
    wsSend({ type: 'textDelta', sessionId: sid, delta: '一张红色方块。' });
    await sleep(120);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(300);

    // A4 图 + 无正文
    await page.setInputFiles('.ask-dock input[type=file]', { name: 'only.png', mimeType: 'image/png', buffer: PNG });
    await sleep(900);
    const mark4 = inbound.length;
    const bubsBefore = (await dockState(page)).userBubs;
    await page.click('.ask-dock .ask-send');
    await sleep(1000);
    const f4 = inbound.slice(mark4).find((m) => m.type === 'askQuestion');
    const s4 = await dockState(page);
    ok(tag, 'A4 图+无正文 → 帧 content 空、attachments 在；气泡成行（不被隐藏）',
      !!f4 && f4.content === '' && Array.isArray(f4.attachments) && f4.attachments.length === 1 && s4.userBubs === bubsBefore + 1 && s4.userImgs >= 2,
      JSON.stringify({ frame: !!f4, content: f4 && f4.content, bubs: [bubsBefore, s4.userBubs], imgs: s4.userImgs }));
    await page.screenshot({ path: join(shotsDir, '04-sent-image-only.png') });
    wsSend({ type: 'textDelta', sessionId: sid, delta: '收到。' });
    await sleep(120);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(300);

    // A6 纯文本提问不带 attachments 键
    const mark6 = inbound.length;
    await page.fill('.ask-dock textarea', '纯文本');
    await page.click('.ask-dock .ask-send');
    await sleep(600);
    const f6 = inbound.slice(mark6).find((m) => m.type === 'askQuestion');
    ok(tag, 'A6 纯文本提问帧无 attachments 键（逐字现状）', !!f6 && !('attachments' in f6), JSON.stringify(f6 && Object.keys(f6)));
    wsSend({ type: 'textDelta', sessionId: sid, delta: 'ok' });
    await sleep(120);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(300);

    const errs = errors.length;
    ok(tag, 'E 全程零 pageerror（主流程）', errs === 0, errs ? errors.join(' | ') : '');
  } finally {
    await ctx.close();
  }

  // A5 回放（独立一次 boot：回放行在 askOpen 时由服务端下发，须先于开面板设定）。
  const r5 = await boot(browser, { viewport, colorScheme });
  try {
    r5.replay.messages = [
      { role: 'user', text: '', attachments: [{ name: 'replay.png', type: 'image', path: '/data/uploads/ask-PLACEHOLDER/1-replay.png' }] },
      { role: 'ai', text: '回放答复' },
    ];
    const abs5 = await openAskable(r5.page, '附图验收.md');
    const sid5 = sidOf(abs5);
    // 把占位 sid 换成本次会话 sid（路由形状 /uploads/<sid>/<file>）。
    r5.replay.messages[0].attachments[0].path = `/data/uploads/${sid5}/1-replay.png`;
    // 重开一次 askOpen 回放（收起再开）以让 askState 带上新行。
    await r5.page.click('.ask-fab'); await sleep(250);
    await r5.page.click('.ask-fab'); await sleep(700);
    const s5 = await dockState(r5.page);
    ok(tag, 'A5 回放 user 行带图 → 气泡图 src = /uploads/<sid>/<file>',
      s5.lastUserSrc !== null && s5.lastUserSrc.startsWith(`/uploads/${sid5}/`),
      JSON.stringify({ src: s5.lastUserSrc, sid: sid5 }));
    await r5.page.screenshot({ path: join(shotsDir, '05-replay-image.png') });
    ok(tag, 'E 全程零 pageerror（回放）', r5.errors.length === 0, r5.errors.join(' | '));
  } finally {
    await r5.ctx.close();
  }
}

const browser = await chromium.launch();
try {
  await runCombo(browser, 'desktop-1280x900-dark', { width: 1280, height: 900 }, 'dark');
  await runCombo(browser, 'desktop-1280x900-light', { width: 1280, height: 900 }, 'light');
  await runCombo(browser, 'mobile-390x844-dark', { width: 390, height: 844 }, 'dark');
  await runCombo(browser, 'mobile-390x844-light', { width: 390, height: 844 }, 'light');
} finally {
  await browser.close();
  server.close();
}
console.log(`\nask-image-attach: ${pass} passed, ${fail} failed`);
if (fail) { console.log(failures.join('\n')); process.exit(1); }
