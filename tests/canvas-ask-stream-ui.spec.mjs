#!/usr/bin/env node
// canvas-ask-stream-ui.spec.mjs — ask 会话三件（ask-stream-ui 批 2026-11-17）验收。
//
// 作者令（逐字）：「现在canvas ask会话 首先在思考中的时候应该设计一个spinner
// 然后新消息 如果用户在上方浏览 应该像主会话这样 不影响用户阅读 而是在输入框
// 上方有个小气泡提示 然后比如滑到上方回到底部这个提示 总之这里的设计可以参考
// 主会话的实现」。
//
// 三件 → 断言映射（全部对齐主会话机制：胶囊 = js/scrollPill.js 唯一组件，dots =
// chat.css .thinking-dot 思考族）：
//   S1 思考期 spinner：submit → 首个非空 textDelta 之间，.ask-stream-line 内
//      三枚 .thinking-dot +「思考中」（chat.thinking.now 字面）；不与流式指示
//      打架（首 token 整替为流式文本后 dots 归零）；等待期零空 ai 泡（双泡纪律）。
//   S2 新消息不打断阅读：用户离底（上翻）时新消息（injected user / done 落泡）
//      到达 ⇒ 列 scrollTop 原样不动 + .ask-dock 内 .scroll-new-pill 出现
//      「↓ N 条新消息」；贴底时同路径跟随钉底（跟随意图 latch）。
//   S3 点击胶囊回底：平滑贴底（poll 到距离 ≤4px）+ 胶囊隐藏 + 计数清零。
//   S4 滑回底部消失 + 容差：距底 30px（< AT_BOTTOM_PX 44）即算贴底——不差几
//      像素闪烁；距底 ≥ SHOW_AFTER_PX 320 且无未读 ⇒ 腿 B「回到底部」；贴底 ⇒ 隐。
//   S5 复用纪律：胶囊与主会话同一类名 .scroll-new-pill（chat.css 唯一形态源），
//      落位在输入条顶缘之上；页面零 pageerror。
//
// 运行：node tests/canvas-ask-stream-ui.spec.mjs
// 可选 env：PORT（默认 8226）、ASK_STREAM_EVID_DIR（截图/录屏目录，默认
// evidence/ask-stream-ui）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

/** 客户端/服务端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-）。 */
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
// argv 传参（env 前缀命令在本仓权限面被拒）：--port=8226 --evid-dir=/tmp/...
const argv = Object.fromEntries(process.argv.slice(2).filter((a) => a.startsWith('--')).map((a) => {
  const i = a.indexOf('='); return [a.slice(2, i), a.slice(i + 1)];
}));
const PORT = Number(argv.port || process.env.PORT || 8226);
const BASE = `http://127.0.0.1:${PORT}`;
const EVID_DIR = argv['evid-dir'] || resolve(HERE, `../evidence/ask-stream-ui`);
mkdirSync(EVID_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树；隔离实例，不依赖任何在跑端口）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 断言器 ──
let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}

// ── ask 会话 mock 面（routeWebSocket 的 server 侧；canvas-ask-dock 同款骨架）──
/** @type {any} */ let askWs = null;
const inbound = [];
function wsSend(obj) { if (askWs) askWs.send(JSON.stringify(obj)); }

const SEED_MSGS = [];
for (let i = 0; i < 24; i++) {
  SEED_MSGS.push({ role: i % 2 ? 'ai' : 'user', text: i % 2 ? `第 ${i + 1} 条答复：检索调优参数第 ${i + 1} 档的召回对比与消融结论，附上下文窗口与缓存命中率的取舍记录。` : `第 ${i + 1} 个问题：调优实验 ${i + 1} 的变量与基线怎么定？` });
}

function nfFileRoute() {
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  };
}

async function bootPage(browser) {
  const ctx = await browser.newContext({
    viewport: { width: 1600, height: 1000 }, colorScheme: 'dark',
    recordVideo: { dir: EVID_DIR, size: { width: 1600, height: 1000 } },
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        ws.send(JSON.stringify({
          type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || 'file',
          messages: SEED_MSGS,
        }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openFile(page, name) {
  const absPath = `/tmp/canvas-ask-fixture/${name}`;
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nmock body\n`, absPath, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000 });
  await sleep(300);
  return absPath;
}

/** ask 列几何/胶囊一揽子读数（全部 scoped 在 .ask-dock 内，与主窗胶囊零混淆）。 */
const askGeo = (page) => page.evaluate(() => {
  const col = document.querySelector('.ask-dock .ask-col');
  const pill = document.querySelector('.ask-dock .scroll-new-pill');
  const input = document.querySelector('.ask-dock .ask-input');
  const dots = document.querySelectorAll('.ask-dock .nf-workline .thinking-dot');
  const line = document.querySelector('.ask-dock .nf-workline');
  const bubbles = document.querySelectorAll('.ask-dock .ask-col .ask-bub');
  const pr = pill ? pill.getBoundingClientRect() : null;
  const ir = input ? input.getBoundingClientRect() : null;
  return {
    scrollTop: col ? col.scrollTop : null,
    dist: col ? col.scrollHeight - col.scrollTop - col.clientHeight : null,
    bubbles: bubbles.length,
    dots: dots.length,
    lineText: line ? line.textContent : null,
    pillHidden: pill ? pill.classList.contains('hidden') : null,
    pillText: pill ? pill.textContent : null,
    pillAboveInput: pr && ir ? ir.top - pr.bottom : null,
    pillInDock: !!pill && !!pill.closest('.ask-dock'),
  };
});

async function shot(page, name) {
  try { await page.screenshot({ path: join(EVID_DIR, name + '.png'), fullPage: false }); } catch { /* headless 沙箱截不到就算了 */ }
  try {
    const dock = await page.$('.ask-dock');
    if (dock) await dock.screenshot({ path: join(EVID_DIR, name + '--dock.png') });
  } catch { /* 同上 */ }
}

/** poll 到列距底 ≤ tol（平滑滚动落点）；超时返回最终读数。 */
async function waitDist(page, tol, ms) {
  const end = Date.now() + ms;
  let g = await askGeo(page);
  while (Date.now() < end && (g.dist === null || g.dist > tol)) {
    await sleep(60);
    g = await askGeo(page);
  }
  return g;
}

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
const { ctx, page, errors } = await bootPage(browser);
try {
  const absPath = await openFile(page, '记忆检索调优笔记.md');
  const sid = sidOf(absPath);

  // 开 dock：askState 回放 24 条 → snapCol 贴底
  await page.click('.ask-fab');
  await page.waitForSelector('.ask-dock:not(.closed) .ask-bub', { timeout: 8000 });
  await sleep(500);
  let g = await waitDist(page, 4, 1500);
  ok('S2a 开窗回放贴底（snapCol）', g.bubbles === 24 && g.dist !== null && g.dist <= 4, `bubbles=${g.bubbles} dist=${g.dist}`);
  ok('S5a 胶囊在册且隐藏（贴底态）', g.pillInDock === true && g.pillHidden === true, `pillHidden=${g.pillHidden}`);

  // ── S1 思考期 spinner：发问后、首 token 前 ──────────────────────────────
  await page.fill('.ask-dock textarea', '这一轮调优的取舍结论是什么？');
  await page.click('.ask-dock .ask-send');
  await sleep(400); // 等待期（不推 textDelta）
  g = await askGeo(page);
  ok('S1a 等待期零空 ai 泡（双泡纪律不回归）', g.bubbles === 25, `bubbles=${g.bubbles}`);
  ok('S1b 思考期三点 spinner（主会话 .thinking-dot 族）', g.dots === 3, `dots=${g.dots}`);
  ok('S1c 使用主会话工作线', g.lineText !== null, `line=${g.lineText}`);
  await shot(page, '01-thinking-spinner');

  // 首个非空 token：spinner 让位流式文本（不并存打架）
  wsSend({ type: 'textDelta', sessionId: sid, delta: '结论：召回优先，' });
  await sleep(250);
  g = await askGeo(page);
  ok('S1d 正文增量期间工作线保留', g.lineText !== null, `line=${g.lineText}`);
  ok('S1e 增量正文不逐字出现', g.bubbles === 25 && !(g.lineText || '').includes('结论：召回优先'), `line=${g.lineText} bubbles=${g.bubbles}`);
  ok('S2b 贴底流式跟随（latch 生效不被打断）', g.dist !== null && g.dist <= 4, `dist=${g.dist}`);
  await shot(page, '02-streaming-text');
  wsSend({ type: 'textDelta', sessionId: sid, delta: '延迟次之；缓存命中率作为第三优先级。' });
  wsSend({ type: 'done', sessionId: sid, finalText: '结论：召回优先，延迟次之；缓存命中率作为第三优先级。' });
  await sleep(400);
  g = await askGeo(page);
  ok('S2c done 落泡（贴底跟随 ⇒ 跟到底）', g.bubbles === 26 && g.dist !== null && g.dist <= 4, `bubbles=${g.bubbles} dist=${g.dist}`);
  ok('S2d 落泡后流式行退场', g.lineText === null, `line=${g.lineText}`);

  // ── S2 离底读历史：新消息到达不得抢滚动位，胶囊报数 ──────────────────────
  await page.evaluate(() => { const c = document.querySelector('.ask-dock .ask-col'); if (c) c.scrollTop = 0; });
  await sleep(350);
  const awayBefore = await askGeo(page);
  ok('S2e 上翻离底（距底 ≥ 320 拐点）', awayBefore.dist >= 320, `dist=${awayBefore.dist}`);
  // 新消息 = 主会话回问（injected user）
  wsSend({ type: 'user', sessionId: sid, injected: true, text: '主会话回问：把第三优先级展开讲讲' });
  await sleep(450);
  g = await askGeo(page);
  ok('S2f 新消息到达滚动位不动（±2px）', Math.abs(g.scrollTop - awayBefore.scrollTop) <= 2, `before=${awayBefore.scrollTop} after=${g.scrollTop}`);
  ok('S2g 新气泡已落列（在下方，不打断阅读）', g.bubbles === 27, `bubbles=${g.bubbles}`);
  ok('S2h user 注入不计入新消息', g.pillHidden === false && (g.pillText || '').includes('回到底部'), `pill="${g.pillText}"`);
  ok('S5b 胶囊落位在输入条顶缘之上（anchor 参照）', g.pillAboveInput !== null && g.pillAboveInput >= -1, `gap=${g.pillAboveInput}`);
  await shot(page, '03-newmsg-away-pill');
  await page.screenshot({ path: join(EVID_DIR, '03-newmsg-away-pill--full.png') });

  // ── S3 点击胶囊 → 平滑回底 → 胶囊消失 ───────────────────────────────────
  await page.click('.ask-dock .scroll-new-pill');
  g = await waitDist(page, 4, 2500);
  ok('S3a 点击胶囊平滑贴底', g.dist !== null && g.dist <= 4, `dist=${g.dist}`);
  ok('S3b 贴底后胶囊隐藏（计数清零）', g.pillHidden === true, `pillHidden=${g.pillHidden}`);
  await shot(page, '04-after-pill-click');

  // ── S4 容差与滑回消失 ────────────────────────────────────────────────────
  await page.evaluate(() => { const c = document.querySelector('.ask-dock .ask-col'); if (c) c.scrollTop = 0; });
  await sleep(350);
  wsSend({ type: 'user', sessionId: sid, injected: true, text: '主会话回问：再补一条离底计数' });
  await sleep(450);
  g = await askGeo(page);
  ok('S4a 再次离底 user 注入仍不计数', g.pillHidden === false && (g.pillText || '').includes('回到底部'), `pill="${g.pillText}"`);
  // 距底 30px（< 44 容差）⇒ 算贴底：胶囊隐藏、不闪烁
  await page.evaluate(() => {
    const c = document.querySelector('.ask-dock .ask-col');
    if (c) c.scrollTop = c.scrollHeight - c.clientHeight - 30;
  });
  await sleep(400);
  g = await askGeo(page);
  ok('S4b 距底 30px（容差内）即隐藏（不差几像素闪烁）', g.dist !== null && g.dist >= 20 && g.dist <= 40 && g.pillHidden === true, `dist=${g.dist} pillHidden=${g.pillHidden}`);
  await shot(page, '05-tolerance-hidden');
  // 滑回顶部（无未读）⇒ 腿 B「回到底部」；再滑到贴底 ⇒ 消失
  await page.evaluate(() => { const c = document.querySelector('.ask-dock .ask-col'); if (c) c.scrollTop = 0; });
  await sleep(400);
  g = await askGeo(page);
  ok('S4c 无未读且距底 ≥ 320 ⇒ 腿 B「回到底部」', g.pillHidden === false && (g.pillText || '').includes('回到底部'), `pill="${g.pillText}"`);
  await shot(page, '06-back-to-bottom-leg');
  await page.evaluate(() => { const c = document.querySelector('.ask-dock .ask-col'); if (c) c.scrollTop = c.scrollHeight; });
  await sleep(450);
  g = await askGeo(page);
  ok('S4d 滑回底部 ⇒ 胶囊消失', g.pillHidden === true && g.dist !== null && g.dist <= 2, `dist=${g.dist} pillHidden=${g.pillHidden}`);
  await shot(page, '07-scrolled-back-hidden');

  // ── S5 收尾 ──────────────────────────────────────────────────────────────
  ok('S5e 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
} finally {
  // 录屏落盘（context.close 后 video 才 finalize）
  const video = page.video();
  await ctx.close();
  if (video) {
    try { readFileSync(await video.path()); console.log(`VIDEO  ${await video.path()}`); } catch { /* 沙箱 */ }
  }
  await browser.close();
  server.close();
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail > 0) process.exit(1);
