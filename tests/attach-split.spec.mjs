// attach-split.spec.mjs — 修复批「网页端带附件消息整体丢失」（2026-10-24 作者现场
// 报障：22 图 + 文字一条 WS 帧 > 服务端旧 10MB 上限 ⇒ 整帧（含文字）被静默丢弃，
// UI 已渲乐观气泡 = 假成功；随后纯文本正常 ⇒ 「纯文本通、带附件整体吞」）验收。
//
// 被测面 = **真发送链**（真实 web 树 + 真模块图 + 真用户动作 + WS 层 routeWebSocket
// 桩服务端，同 f1-send-queue.spec.mjs 骨架）：
//
//   ① 文字必达：22 图大帧 ⇒ **首帧只载正文**（attachments 空）+ 主 clientMessageId，
//     且在**任何附件帧之前**出线（WS 单连接有序）。
//   ② 附件分批：22 片附件拆成 ≥2 帧、每帧线长 < 拆分阈值（8MB）、附件总数守恒。
//   ③ 独立幂等键：附件帧 id = 主 id + '/a' + n —— 躲 AgentActor recentMessageIds 判重
//     （同 id 会被判重丢弃 ⇒ 附件永远到不了）。
//   ④ 单帧零回归：小消息照旧恰一帧、附件随帧。
//   ⑤ 超限有声：服务端 messageTooLarge 回执 ⇒ 用户可见错误行 + 幻影 busy 收掉。
//   ⑥ 源面静态钉：拆分阈值/派发台四腿共用、服务端 64MB 上限 + 有声拒绝回执、
//     typeless 准入谓词（附件帧 content 空可达的承载面）逐字在场。
//
// 红线：断言面 = 线上帧字节/键 + 气泡行 + busy 读数，不触碰样式值。
//
// Run: node tests/attach-split.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-attach-split';
const SPLIT_BYTES = 8 * 1024 * 1024;

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端 ──────────────────────────────────────────────────────────
const outbound = [];           // 客户端 → 桩 的全部帧（按出线序）
let lastUserTextFrame = null;  // 最近一条正文帧（messageTooLarge 回执要引用它的 id）
let currentWs = null;          // 当前连接句柄（⑤ 桩侧推回执用）

function wireUp(ws) {
  currentWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    outbound.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    // 正文帧（content 键在，哪怕空串）⇒ 回 busy/done 让 turn 收尾；纯附件帧
    // （content 键缺席）不回 —— 它已是附加 turn，无需桩再喂终态。
    if (Object.prototype.hasOwnProperty.call(m, 'content')) {
      lastUserTextFrame = m;
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      // ⑤ 的探针帧**不收尾**（不送 done）——busy 保持到 messageTooLarge 回执来收，
      // 否则 60ms 后 done 先清了 busy，「幻影 busy 收口」断言失去前提。
      if (typeof m.content === 'string' && m.content.includes('probe before tooLarge')) return;
      setTimeout(() => {
        try {
          ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
          ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
        } catch { /* 连接已被测试关闭 */ }
      }, 60);
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'attach-split E2E' }], activeId: SID, folders: [] }));
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
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

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  const busyHas = (sid) => page.evaluate(async (s) => {
    const sMod = await import('/js/state.js');
    return sMod.default.busySessionIds.has(s);
  }, sid);
  /** 直接注入 22 片「已压缩」图附件（绕过 canvas 压缩，等价于压缩完成态）。 */
  const injectImages = (n, b64len) => page.evaluate(async ({ count, len }) => {
    const { activeView } = await import('/js/chatView.js');
    for (let i = 0; i < count; i++) {
      activeView.pendingAttachments.push({
        type: 'image', mimeType: 'image/jpeg',
        data: 'A'.repeat(len),
        name: 'chat-' + i + '.jpg', preview: 'data:image/jpeg;base64,AA=='
      });
    }
    return activeView.pendingAttachments.length;
  }, { count: n, len: b64len });

  // ══ ①②③ 22 图大帧：文字必达 + 附件分批 + 独立幂等键 ═══════════════
  const IMG_N = 22, IMG_B64 = 600 * 1024;   // 22 × 600KB ≈ 13.2MB > 8MB 阈值
  ok('setup 注入 22 片附件', (await injectImages(IMG_N, IMG_B64)) === IMG_N);
  await page.fill('#input', '这是我跟投资人的聊天记录 帮我分析一下');
  await page.keyboard.press('Enter');
  await sleep(900);

  const textFrames = outbound.filter(m => typeof m.content === 'string' && m.content.includes('投资人'));
  const attFrames = outbound.filter(m => Array.isArray(m.attachments) && m.attachments.length > 0);
  const wireBytes = (m) => JSON.stringify(m).length;

  ok('①a 正文恰一帧且文字必达（attachments 显式空数组）',
    textFrames.length === 1 && Array.isArray(textFrames[0].attachments) && textFrames[0].attachments.length === 0,
    `textFrames=${textFrames.length}, atts=${JSON.stringify(textFrames[0]?.attachments)}`);
  const mainId = textFrames[0] && textFrames[0].clientMessageId;
  ok('①b 正文帧携带主 clientMessageId', typeof mainId === 'string' && mainId.length > 0, `id=${mainId}`);
  ok('①c 正文帧零附件线长 < 阈值', textFrames.length === 1 && wireBytes(textFrames[0]) < SPLIT_BYTES,
    `bytes=${textFrames.length === 1 ? wireBytes(textFrames[0]) : 'n/a'}`);

  const firstTextIdx = outbound.indexOf(textFrames[0]);
  const firstAttIdx = outbound.findIndex(m => Array.isArray(m.attachments) && m.attachments.length > 0);
  ok('①d 文字帧在**任何附件帧之前**出线（文字必达先行）',
    firstTextIdx >= 0 && firstAttIdx > firstTextIdx, `textIdx=${firstTextIdx}, attIdx=${firstAttIdx}`);

  const totalAtts = attFrames.reduce((s, m) => s + m.attachments.length, 0);
  ok('②a 附件 ≥2 帧分批', attFrames.length >= 2, `frames=${attFrames.length}`);
  ok('②b 22 片附件总数守恒', totalAtts === IMG_N, `total=${totalAtts}`);
  ok('②c 每片附件帧线长 < 8MB 阈值', attFrames.every(m => wireBytes(m) < SPLIT_BYTES),
    `max=${Math.max(...attFrames.map(wireBytes))}`);
  ok('②d 附件帧无 content 键（正文只走首帧）',
    attFrames.every(m => !Object.prototype.hasOwnProperty.call(m, 'content')));
  ok('②e 附件帧无 chatWidth（避免二次覆盖）',
    attFrames.every(m => !Object.prototype.hasOwnProperty.call(m, 'chatWidth')));

  const attIds = attFrames.map(m => m.clientMessageId);
  ok('③a 附件帧幂等键 = 主 id + /a + n',
    attIds.every((id, i) => id === mainId + '/a' + (i + 1)), JSON.stringify(attIds));
  ok('③b 全部帧幂等键互不相同（躲 recentMessageIds 判重）',
    new Set([mainId, ...attIds]).size === 1 + attIds.length);

  // ══ ④ 小消息单帧零回归 ══════════════════════════════════════════════
  outbound.length = 0;
  await page.fill('#input', 'small follow-up');
  await page.keyboard.press('Enter');
  await sleep(700);
  const smallText = outbound.filter(m => typeof m.content === 'string' && m.content === 'small follow-up');
  ok('④a 小消息恰一帧（不拆）', smallText.length === 1, `frames=${smallText.length}`);
  ok('④b 单帧携带主 id', smallText.length === 1 && typeof smallText[0].clientMessageId === 'string' && smallText[0].clientMessageId.length > 0);

  // ══ ⑤ messageTooLarge 有声面：可见错误 + 幻影 busy 收口 ═══════════════
  outbound.length = 0;
  await page.fill('#input', 'probe before tooLarge');
  await page.keyboard.press('Enter');
  await sleep(400);
  ok('⑤setup 正文帧已受理（busy 已置）', (await busyHas(SID)) === true);
  // 桩侧推 messageTooLarge 回执（引用刚发出的正文帧 id = 服务端拒收语义）
  currentWs.send(JSON.stringify({
    type: 'messageTooLarge', sessionId: SID,
    clientMessageId: lastUserTextFrame ? lastUserTextFrame.clientMessageId : '',
    sizeBytes: 70 * 1024 * 1024, limitBytes: 64 * 1024 * 1024
  }));
  await sleep(600);
  const errRow = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.row.error')];
    return rows.map(r => (r.textContent || '')).join('\n');
  });
  ok('⑤a 用户可见错误行已渲染（超限有声，非假成功）',
    /超过投递上限|exceeds the .* delivery limit/i.test(errRow), JSON.stringify(errRow.slice(0, 120)));
  ok('⑤b 幻影 busy 已收（clearBusy）', (await busyHas(SID)) === false);

  ok('⑤c 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));

  // ══ ⑥ 源面静态钉 ═════════════════════════════════════════════════════
  const inputSrc = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  ok('⑥a 拆分阈值 8MB 在场', /WS_SPLIT_MAX_BYTES = 8 \* 1024 \* 1024/.test(inputSrc));
  ok('⑥b 派发台四腿共用（send/drain/immediate/offline ≥4 调用点）',
    (inputSrc.match(/dispatchUserMessageFrames\(\{/g) || []).length >= 4,
    `callSites=${(inputSrc.match(/dispatchUserMessageFrames\(\{/g) || []).length}`);
  ok('⑥c 附件帧独立幂等键 /a 派生在场', /clientMessageId \+ '\/a' \+ idx/.test(inputSrc));
  const routesSrc = await readFile(join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'scala', 'nebflow', 'gateway', 'WebSocketRoutes.scala'), 'utf8');
  ok('⑥d 服务端上限 64MB（22 图级单帧可达）', /MaxMessageSize = 64 \* 1024 \* 1024/.test(routesSrc));
  ok('⑥e 超限有声拒绝回执（messageTooLarge 帧）',
    /"messageTooLarge"/.test(routesSrc) && /Dropping oversized WebSocket message/.test(routesSrc));
  ok('⑥f typeless 准入谓词在场（附件帧 content 空可达的承载面）',
    /content\.nonEmpty \|\| attachments\.nonEmpty/.test(routesSrc));
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  ok('⑥g 客户端 messageTooLarge 处理器在场（含 busy 收口）',
    /onMessage\('messageTooLarge'/.test(mainSrc) && /clearBusy\(sid\)/.test(mainSrc));

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);
