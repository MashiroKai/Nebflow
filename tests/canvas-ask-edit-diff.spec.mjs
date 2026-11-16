#!/usr/bin/env node
// canvas-ask-edit-diff.spec.mjs — canvas-ask-restricted-edit ③ web diff 卡验收
// （openspec changes/canvas-ask-restricted-edit，CAE-REQ-001 前端腿）。
//
// ask_edit/ask_write 提案 → askDock diff 卡（cursor 风格 add/del 行），逐卡
// Accept（askEditAccept → askEditResolved accepted → 画布按路径重载）/ Reject
// （丢弃零写盘）；会话内多轮提案多卡并存。协议面用 routeWebSocket mock 驱动
// （canvas-ask-dock.spec.mjs 孪生骨架），**结构断言红绿**（不卡像素）：
//   E0  页面零 pageerror（载入健康面）
//   E1  askEditProposal 帧 → .ask-edit-card 挂卡：data-proposal-id、文件标签
//       （notes.md +N −M 全量计数）、del/add 行数与行文本 = LCS 行级 diff、
//       接受/拒绝钮在场可点
//   E2  Accept 腿：点击 → askEditAccept 帧外发（sessionId + proposalId）、卡
//       data-outcome=waiting + 双钮 disabled（防双发）；askEditResolved
//       (accepted) → data-outcome/data-state=accepted；画布重载腿触发
//       （该路径 pop.readFile 重取 +1，scheduleFileRefresh 同守卫）
//   E3  Reject 腿：askEditReject 帧外发 → resolved(rejected) → data-outcome
//       =rejected；期间该路径 pop.readFile 零重发（盘上未动）
//   E4  多轮并存：已审卡与新提案卡同列并存（会话内多轮）；同 proposalId 帧
//       幂等（upsert 不重复挂卡）
//   E5  askState 对账腿：服务端在列 edits 播种挂卡（重连回放面）；本地 pending
//       不在服务端在列 → rejected（resolved elsewhere，保守面不假写盘成功）
//   E6  文本红线：diff 行一律 textContent——行内 HTML/script 字面量不构成
//       可执行/可成像节点（oldText/newText 是文件原文，绝不进 innerHTML）
//
// 运行：node tests/canvas-ask-edit-diff.spec.mjs
// 可选 env：PORT（默认 8218，避开 dock spec 的 8216）、ASK_SHOTS_DIR（截图目录）。

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
const PORT = Number(process.env.PORT || 8218);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.ASK_SHOTS_DIR || resolve(HERE, `../evidence/canvas-ask-edit/shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树，canvas-stack 同款）──
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

// ── ask 会话 mock 面（routeWebSocket 的 server 侧；askState 回放携带 edits）──
/** @type {any} */ let askWs = null;
const inbound = [];                                 // page → server 帧留档（协议断言面）
/** @type {Record<string, any[]>} */ const serverEdits = {}; // 服务端在列待审提案（askState 回放面）
function wsSend(obj) { if (askWs) askWs.send(JSON.stringify(obj)); }

let nfFileHits = 0;                                 // （按路径 HTTP 重取计数；E2/E3 重载腿判据在 WS pop.readFile 面）

async function bootPage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
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
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    nfFileHits += 1;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      // pop.readFile 应答（WsFileOpsHandlers 同式）：fileContent → explorer 落
      // workspace-open-item（background）→ openWorkspaceItem 既有页签重渲 +
      // settleFileRefresh（_refreshing 解锁——真后端必应答，mock 不应答会把刷新
      // 面卡在 10s 安全窗里，E2d 的重载腿就永远等不到第二次调度）。
      if (m.type === 'pop.readFile' && m.path) {
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          fileName: m.path.split('/').pop(), itemType: 'markdown',
          content: '# notes (refreshed)\n\nmock body\n', size: 28,
        }));
      }
      if (m.type === 'askOpen') {
        // askOpen 帧不携 sessionId（服务端按 file.absPath 派生）——mock 同式推导。
        const sid = sidOf((m.file && m.file.absPath) || '');
        ws.send(JSON.stringify({
          type: 'askState',
          sessionId: sid,
          sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '',
          fileName: (m.file && m.file.name) || 'file',
          messages: [],
          edits: serverEdits[sid] || [],
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
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name,
        content: `# ${name}\n\nmock body\n`, absPath, pinned: true,
      },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
  await sleep(300);
  return absPath;
}

async function shot(page, name) {
  try { await page.screenshot({ path: join(SHOTS_DIR, name + '.png'), fullPage: false }); } catch { /* headless 沙箱截不到就算了 */ }
}

/** dock 内卡面快照（结构断言面：数量/状态/按钮可用性/行文本）。 */
const cardsOf = (page) => page.evaluate(() => {
  const cards = [...document.querySelectorAll('.ask-dock .ask-edit-card')];
  return cards.map((c) => {
    const dels = [...c.querySelectorAll('.ask-edit-line.del')].map((l) => l.textContent || '');
    const adds = [...c.querySelectorAll('.ask-edit-line.add')].map((l) => l.textContent || '');
    return {
      proposalId: c.getAttribute('data-proposal-id') || '',
      outcome: c.getAttribute('data-outcome') || '',
      state: (c.querySelector('.ask-edit-status') || {}).dataset ? c.querySelector('.ask-edit-status').dataset.state : '',
      file: (c.querySelector('.ask-edit-file') || {}).textContent || '',
      dels, adds,
      acceptDisabled: (c.querySelector('.ask-edit-accept') || {}).disabled === true,
      rejectDisabled: (c.querySelector('.ask-edit-reject') || {}).disabled === true,
    };
  });
});

const byId = (cards, id) => cards.find((c) => c.proposalId === id);

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
try {
  const { page, errors } = await bootPage(browser);
  const abs = await openFile(page, 'notes.md');
  const sid = sidOf(abs);
  // 画布刷新冷却（FILE_REFRESH_COOLDOWN=2000；开卡渲染即 _lastRefreshAt 时戳）：
  // 冷却窗外驱动后续 Accept，E2d 重载腿判定才确定性（scheduleFileRefresh 在
  // 冷却窗内的调用是静默 return、不重试——这是 canvas.js 的既定守卫语义）。
  await sleep(2300);
  await page.click('.ask-fab');                       // 开 dock（askOpen → askState 回放）
  await sleep(500);

  // E0 载入健康
  ok('E0 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));

  // ── E1 askEditProposal → 卡结构 ─────────────────────────────────────────
  const oldText = 'alpha\nbeta\ngamma\n';
  const newText = 'alpha\nBETA\ngamma\ndelta\n';
  wsSend({ type: 'askEditProposal', sessionId: sid, proposalId: 'e1', path: abs, fileName: 'notes.md', oldText, newText, createdAt: Date.now() });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e1"]', { timeout: 5000 });
  let cards = await cardsOf(page);
  const e1 = byId(cards, 'e1');
  ok('E1a 卡挂载：data-proposal-id=e1，文件标签 = notes.md +2 −1（全量计数）',
    !!e1 && e1.file.includes('notes.md') && e1.file.includes('+2') && e1.file.includes('−1'), JSON.stringify(e1 && e1.file));
  ok('E1b LCS 行级 diff：del=1（− beta）、add=2（+ BETA / + delta）',
    !!e1 && e1.dels.length === 1 && e1.adds.length === 2
      && e1.dels[0].includes('beta') && e1.adds.some((t) => t.includes('BETA')) && e1.adds.some((t) => t.includes('delta')),
    JSON.stringify({ dels: e1 && e1.dels, adds: e1 && e1.adds }));
  ok('E1c 状态 = pending（data-state/data-outcome），接受/拒绝钮在场可点',
    !!e1 && e1.state === 'pending' && e1.outcome === 'pending' && !e1.acceptDisabled && !e1.rejectDisabled,
    JSON.stringify(e1 && { state: e1.state, outcome: e1.outcome }));

  // E4（前半）同 proposalId 帧幂等
  wsSend({ type: 'askEditProposal', sessionId: sid, proposalId: 'e1', path: abs, fileName: 'notes.md', oldText, newText, createdAt: Date.now() });
  await sleep(300);
  cards = await cardsOf(page);
  ok('E4a 同 proposalId 帧幂等（upsert 不重复挂卡）', cards.filter((c) => c.proposalId === 'e1').length === 1, JSON.stringify(cards.map((c) => c.proposalId)));

  // ── E2 Accept 腿 ───────────────────────────────────────────────────────
  // 画布重载腿判据 = WS pop.readFile 帧按路径重取（scheduleFileRefresh 走
  // pop.readFile → fileContent → workspace-open-item，非 HTTP nf-file 直取）。
  const readsOf = () => inbound.filter((m) => m.type === 'pop.readFile' && m.path === abs).length;
  const readsBeforeAccept = readsOf();
  await page.click('.ask-edit-card[data-proposal-id="e1"] .ask-edit-accept');
  await sleep(300);
  const acceptFrame = inbound.find((m) => m.type === 'askEditAccept');
  ok('E2a Accept 点击 → askEditAccept 帧外发（sessionId + proposalId）',
    !!acceptFrame && acceptFrame.sessionId === sid && acceptFrame.proposalId === 'e1',
    JSON.stringify(acceptFrame));
  cards = await cardsOf(page);
  const e1w = byId(cards, 'e1');
  ok('E2b 防双发：waiting 态 + 双钮 disabled（等 askEditResolved 终态）',
    !!e1w && e1w.outcome === 'waiting' && e1w.acceptDisabled && e1w.rejectDisabled,
    JSON.stringify(e1w && { outcome: e1w.outcome, acceptDisabled: e1w.acceptDisabled }));
  wsSend({ type: 'askEditResolved', sessionId: sid, proposalId: 'e1', outcome: 'accepted', path: abs });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e1"][data-outcome="accepted"]', { timeout: 5000 });
  // 画布重载腿：refreshFileTabByPath → scheduleFileRefresh（300ms 防抖）→ pop.readFile。
  let refetched = false;
  for (let i = 0; i < 40 && !refetched; i++) { await sleep(100); refetched = readsOf() > readsBeforeAccept; }
  cards = await cardsOf(page);
  const e1a = byId(cards, 'e1');
  ok('E2c resolved(accepted) → data-outcome/data-state=accepted', !!e1a && e1a.outcome === 'accepted' && e1a.state === 'accepted',
    JSON.stringify(e1a && { outcome: e1a.outcome, state: e1a.state }));
  ok('E2d 画布重载腿触发（accepted → 该路径 pop.readFile 重取 +1）', refetched, `pop.readFile ${readsBeforeAccept} → ${readsOf()}`);
  await shot(page, 'e2-accepted');

  // ── E4（后半）多轮并存 + E3 Reject 腿 ──────────────────────────────────
  wsSend({ type: 'askEditProposal', sessionId: sid, proposalId: 'e2', path: abs, fileName: 'notes.md',
    oldText: 'alpha\n', newText: 'ALPHA\n', createdAt: Date.now() });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e2"]', { timeout: 5000 });
  cards = await cardsOf(page);
  ok('E4b 多轮并存：已审卡（e1 accepted）与新提案卡（e2 pending）同列',
    cards.length >= 2 && !!byId(cards, 'e1') && byId(cards, 'e1').outcome === 'accepted'
      && !!byId(cards, 'e2') && byId(cards, 'e2').outcome === 'pending',
    JSON.stringify(cards.map((c) => ({ id: c.proposalId, o: c.outcome }))));
  const readsBeforeReject = readsOf();
  await page.click('.ask-edit-card[data-proposal-id="e2"] .ask-edit-reject');
  await sleep(300);
  const rejectFrame = inbound.find((m) => m.type === 'askEditReject');
  ok('E3a Reject 点击 → askEditReject 帧外发（sessionId + proposalId）',
    !!rejectFrame && rejectFrame.sessionId === sid && rejectFrame.proposalId === 'e2',
    JSON.stringify(rejectFrame));
  wsSend({ type: 'askEditResolved', sessionId: sid, proposalId: 'e2', outcome: 'rejected' });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e2"][data-outcome="rejected"]', { timeout: 5000 });
  await sleep(300);
  ok('E3b resolved(rejected) → data-outcome=rejected；期间该路径 pop.readFile 零重发（丢弃零写盘）',
    readsOf() === readsBeforeReject, `pop.readFile ${readsBeforeReject} → ${readsOf()}`);
  await shot(page, 'e3-rejected');

  // ── E5 askState 对账腿 ─────────────────────────────────────────────────
  // 播种：服务端在列 e4（重连回放面——本地没有的补挂）。
  serverEdits[sid] = [{ proposalId: 'e4', path: abs, fileName: 'notes.md',
    oldText: 'one\n', newText: 'two\n', createdAt: Date.now() }];
  wsSend({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: abs,
    fileName: 'notes.md', messages: [], edits: serverEdits[sid] });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e4"]', { timeout: 5000 });
  cards = await cardsOf(page);
  ok('E5a askState 播种：服务端在列 e4 补挂成卡（重连回放面）',
    !!byId(cards, 'e4') && byId(cards, 'e4').outcome === 'pending', JSON.stringify(cards.map((c) => c.proposalId)));
  // 对账：本地 pending（e5）不在服务端在列 → 保守面 rejected（resolved elsewhere）。
  wsSend({ type: 'askEditProposal', sessionId: sid, proposalId: 'e5', path: abs, fileName: 'notes.md',
    oldText: 'x\n', newText: 'y\n', createdAt: Date.now() });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e5"]', { timeout: 5000 });
  wsSend({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: abs,
    fileName: 'notes.md', messages: [], edits: serverEdits[sid] });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e5"][data-outcome="rejected"]', { timeout: 5000 });
  cards = await cardsOf(page);
  const e5 = byId(cards, 'e5');
  ok('E5b 本地 pending 不在服务端在列 → rejected（他机已处理，保守面不假写盘）',
    !!e5 && e5.outcome === 'rejected', JSON.stringify(e5 && e5.outcome));
  await shot(page, 'e5-reconciled');

  // ── E6 文本红线（结构间查） ────────────────────────────────────────────
  const hostile = '<img src=x onerror=window.__askEditXss=1><script>window.__askEditXss=1<\/script>';
  wsSend({ type: 'askEditProposal', sessionId: sid, proposalId: 'e6', path: abs, fileName: 'notes.md',
    oldText: 'safe\n', newText: hostile + '\n', createdAt: Date.now() });
  await page.waitForSelector('.ask-dock .ask-edit-card[data-proposal-id="e6"]', { timeout: 5000 });
  const safety = await page.evaluate(() => {
    const card = document.querySelector('.ask-dock .ask-edit-card[data-proposal-id="e6"]');
    if (!card) return { found: false };
    return {
      found: true,
      imgs: card.querySelectorAll('img').length,
      scripts: card.querySelectorAll('script').length,
      literal: (card.textContent || '').includes('<img src=x'),
      fired: window.__askEditXss === 1,
    };
  });
  ok('E6 diff 行文本红线：HTML/script 字面量不构成节点、不执行（textContent 面）',
    safety.found && safety.imgs === 0 && safety.scripts === 0 && safety.literal && !safety.fired,
    JSON.stringify(safety));

  await sleep(200);
  ok('E0b 收尾零 pageerror（全程含 6 帧驱动 + 3 次点击）', errors.length === 0, errors.slice(0, 2).join(' | '));
} catch (e) {
  // 红面驱动中断（基线 web/ 无 diff 卡实现 ⇒ waitForSelector 超时等）：记一条
  // 决定性 FAIL 而非裸栈崩溃——红绿两态都以汇总行 + 退出码收口。
  ok('SPEC 驱动中断（红面：提案帧未成卡/关键元素缺失）', false, String((e && e.message) || e).slice(0, 240));
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${fail === 0 ? '✅' : '❌'} canvas-ask-edit-diff: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
