// send-bubble-independence.spec.mjs — 并泡诊断批（2026-10-22 作者令「聊天输入两缺
// 陷批」症状一）验收：**两次独立 send = 两个独立气泡**。
//
// 取证结论（本批诊断，reports 同名档）：服务端（WebSocketRoutes 一帧一 UserInput /
// AgentProcessing 排队逐件 / TurnBoundaryDrains.drainHead 一次一件，2026-09-15
// 「禁合并语义」裁定）、传输（ws.js pendingSends 整帧 FIFO）、渲染（三条恢复腿
// 一行一气泡）均无合并机制；报障的「并泡」= compose 时两段文本已同在输入框、
// **一帧出线**（ui.json 744 行 + 引擎日志 10:49:27.840 恰一帧实证）。促成缺陷 =
// busy 排队腿零会话流呈现：发送后气泡不出现（只落在排队条），作者无从分辨
// 「这条已独立在场」⇒ 观感即并泡。本批修法 = busy 排队腿补乐观气泡（离线腿同款
// 形态：data-msg-id = 入队钉死的 clientMessageId + 「待发送」时钟小标），drain 腿
// rendered 分支只摘小标 ⇒ 全链每条逻辑消息恰一气泡。
//
// Pinned here (binary):
//   S1 两连发（busy 中）⇒ 立即两个独立 .row.user，各含一条文本，任一气泡不并文。
//   S2 每气泡带一枚 .msg-pending-mark（待发送态）+ 互异 data-msg-id。
//   S3 drain 后气泡数不变（排队项零新增呈现 = #570 家族「零新增呈现」红线）、
//      小标摘除（翻已送达态）。
//   S4 恰两帧出线、clientMessageId 互异（两条逻辑消息两条幂等键）。
//   S5 入队时刻真值：排队项 drain 补渲的气泡 footer 时间 = 入队时刻（item.ts），
//      非 drain 时刻（静态钉 input.js 的 item.ts 传递链 + persistQueue 落盘键）。
//   S6 wire/源面静态钉：busy 腿 renderUserBubble({pending:true}) 在场。
//
// Run: node tests/send-bubble-independence.spec.mjs
// 形制同 tests/f1-send-queue.spec.mjs（真 web 树 + 真 WS 桩 + routeWebSocket）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SID = 's-bubble-indep';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端 ────────────────────────────────────────────────────────
let releaseBusy = false;
const accepted = [];
const isUserContentFrame = (m) => !m.type && typeof m.content === 'string';

function wireUp(ws) {
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (m.type === '__e2e_arm_busy') {
      releaseBusy = false;
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: true }));
      return;
    }
    if (m.type === '__e2e_release') {
      releaseBusy = true;
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: false }));
      ws.send(JSON.stringify({ type: 'done', sessionId: SID, model: 'mock-llm' }));
      return;
    }
    if (isUserContentFrame(m)) {
      accepted.push(m);
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      if (releaseBusy) {
        setTimeout(() => {
          try {
            ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
            ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
          } catch { /* 连接已被测试关闭 */ }
        }, 150);
      }
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '并泡诊断E2E' }], activeId: SID, folders: [] }));
}

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
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
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  const sendText = async (text) => {
    const input = page.locator('#input');
    await input.click();
    await input.fill(text);
    await input.press('Enter');
    await sleep(350);
  };
  const readRows = () => page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return rows.map(r => ({
      msgId: r.dataset.msgId || '',
      text: (r.querySelector('.bubble.user')?.textContent || ''),
      pending: r.querySelectorAll('.msg-pending-mark').length,
    }));
  });

  // ── 场景：会话 busy，两连发 ─────────────────────────────────────────
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_arm_busy' });
  });
  await sleep(300);
  const TXT_A = '终端粘贴文本 AAA（模拟第一发）';
  const TXT_B = '为什么刚刚stand被强制退出了（第二发）';
  await sendText(TXT_A);
  await sendText(TXT_B);

  const rowsNow = await readRows();
  ok('S1 两连发 = 立即两个独立用户气泡行', rowsNow.length === 2, JSON.stringify(rowsNow.map(r => r.text.slice(0, 20))));
  ok('S1b 任一气泡不含另一条文本（不并泡）',
    rowsNow.length === 2 &&
    rowsNow[0].text.includes('AAA') && !rowsNow[0].text.includes('第二发') &&
    rowsNow[1].text.includes('第二发') && !rowsNow[1].text.includes('AAA'));
  ok('S2 各带一枚待发送小标', rowsNow.length === 2 && rowsNow.every(r => r.pending === 1));
  ok('S2b msgId 互异且在场', rowsNow.length === 2 && rowsNow[0].msgId && rowsNow[1].msgId && rowsNow[0].msgId !== rowsNow[1].msgId);

  // ── drain：放行 → 两项依次出线 ─────────────────────────────────────
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(2000);

  const rowsAfter = await readRows();
  ok('S3 drain 后仍两气泡（排队项零新增呈现）', rowsAfter.length === 2, `rows=${rowsAfter.length}`);
  ok('S3b 小标翻面（已送达态）', rowsAfter.every(r => r.pending === 0));
  ok('S3c 气泡文本不变（无重渲换文）', rowsAfter[0]?.text.includes('AAA') && rowsAfter[1]?.text.includes('第二发'));
  ok('S4 恰两帧出线', accepted.length === 2, `accepted=${accepted.length}`);
  ok('S4b 两帧 clientMessageId 互异', new Set(accepted.map(m => m.clientMessageId)).size === 2);
  ok('S4c 帧序 = 发送序（A 先 B 后）', accepted[0]?.content.includes('AAA') && accepted[1]?.content.includes('第二发'));
  ok('S4d 帧带各自 msgId 与气泡行对齐',
    rowsAfter[0].msgId === accepted[0].clientMessageId && rowsAfter[1].msgId === accepted[1].clientMessageId);

  ok('零页面错误', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
} finally {
  await browser.close().catch(() => {});
  await new Promise(r => server.close(r));
}

// ── S5/S6 静态源钉 ──────────────────────────────────────────────────
{
  const src = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const busyLeg = src.match(/if \(isBusy && !isResend && !state\.frozenSessions\.has\(v\.sessionId\)\) \{([\s\S]*?)\n  \}/);
  ok('S6 busy 腿乐观气泡（rendered:true + pending 小标）在场',
    !!busyLeg && busyLeg[1].includes("rendered: true") && busyLeg[1].includes("pending: true"));
  ok('S5 queueMessage 钉入队时刻（ts）', src.includes('ts: Date.now()'));
  ok('S5b persistQueue 落盘 ts（跨刷新真值源）', src.includes('ts: it.ts || null'));
  ok('S5c drain 补渲用 item.ts（非 Date.now()）',
    src.includes('renderUserBubble(item.text, item.attachments, item.ts)'));
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
