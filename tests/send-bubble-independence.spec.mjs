// send-bubble-independence.spec.mjs — 反并泡契约（输入消息排队回退批 2026-10-23
// 换代重钉 BASE 语义；原作者令「聊天输入两缺陷批」症状一的反并泡本体保留）。
//
// 换代说明：10-22 并泡诊断批的修法 = busy 排队腿补乐观气泡（入队即上屏）——该
// 呈现面随排队回退批（2026-10-23 作者令「输入消息排队功能采用大概两天以前的
// 设计」）退役。BASE 形态下反并泡的成立方式：busy 两连发 = 队列条两行（互不
// 并文）；当前回复完成后 drain **逐条补渲**气泡（一行一气泡，msgId = 入队钉死
// 的 clientMessageId）——服务端/传输/渲染三层禁合并语义不因呈现回退而变。
//
// Pinned here (binary):
//   S1 busy 两连发 ⇒ 零即时用户气泡行（呈现面 = 排队条两行）。
//   S2 队列条两行文本互不并文（逻辑消息独立成行）。
//   S3 drain 后 ⇒ 恰两独立 .row.user，各含一条文本，任一气泡不并文（反并泡本体）。
//   S4 恰两帧出线、clientMessageId 互异、帧序 = 发送序。
//   S5 气泡行 msgId 在场互异；补渲气泡零 pending 标（待送达标 = 离线腿专属；
//      BASE drain 腿渲染行不带帧 id——行身份与帧身份两轨分离）。
//   S6 wire/源面静态钉：busy 腿直入队零气泡；队列条行 = 行级明细；时间戳真值钉
//      （drain 补渲 = 入队时刻 item.ts，非 drain 时刻）。
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

  // S1/S2 busy 中：零气泡（排队条行承接）
  const busyState = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    const bar = document.getElementById('queue-bar');
    const rowTexts = [...(bar?.querySelectorAll('.queue-item-text') || [])].map(el => el.textContent || '');
    return { userRows: rows.length, barVisible: bar?.classList.contains('visible') || false, rowTexts };
  });
  ok('S1 busy 两连发 = 零即时用户气泡（呈现面 = 排队条）',
    busyState.userRows === 0 && busyState.barVisible && busyState.rowTexts.length === 2,
    `userRows=${busyState.userRows} rows=${JSON.stringify(busyState.rowTexts.map(t => t.slice(0, 14)))}`);
  ok('S2 排队条两行互不并文（逻辑消息独立成行）',
    busyState.rowTexts.length === 2
    && busyState.rowTexts[0].includes('AAA') && !busyState.rowTexts[0].includes('第二发')
    && busyState.rowTexts[1].includes('第二发') && !busyState.rowTexts[1].includes('AAA'));

  // ── drain：放行 → 两项逐条补渲 ─────────────────────────────────────
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(2500);

  const rowsAfter = await readRows();
  ok('S3 drain 后恰两独立气泡，互不并文（反并泡本体）',
    rowsAfter.length === 2
    && rowsAfter[0].text.includes('AAA') && !rowsAfter[0].text.includes('第二发')
    && rowsAfter[1].text.includes('第二发') && !rowsAfter[1].text.includes('AAA'),
    `rows=${rowsAfter.length}`);
  ok('S4 恰两帧出线', accepted.length === 2, `accepted=${accepted.length}`);
  ok('S4b 两帧 clientMessageId 互异', new Set(accepted.map(m => m.clientMessageId)).size === 2);
  ok('S4c 帧序 = 发送序（A 先 B 后）', accepted[0]?.content.includes('AAA') && accepted[1]?.content.includes('第二发'));
  ok('S5 气泡行 msgId 在场互异 + 零 pending 标（待送达标 = 离线腿专属；BASE drain 腿渲染行不带帧 id——行身份与帧身份两轨分离）',
    rowsAfter[0].msgId && rowsAfter[1].msgId && rowsAfter[0].msgId !== rowsAfter[1].msgId
    && rowsAfter.every(r => r.pending === 0),
    JSON.stringify(rowsAfter.map(r => ({ msgId: r.msgId, pending: r.pending }))));

  ok('零页面错误', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
} finally {
  await browser.close().catch(() => {});
  await new Promise(r => server.close(r));
}

// ── S6 静态源钉：BASE 腿形态（同树同读数） ──────────────────────────
{
  const src = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const busyLeg = src.match(/if \(isBusy && !isResend && !state\.frozenSessions\.has\(v\.sessionId\)\) \{([\s\S]*?)\n  \}/);
  ok('S6 busy 腿直入队零气泡（无 renderUserBubble 无 rendered:true——排队呈现面 = 排队条行）',
    !!busyLeg && busyLeg[1].includes('queueMessage(v, text, v.pendingAttachments)')
    && !busyLeg[1].includes('renderUserBubble') && !busyLeg[1].includes('rendered: true'));
  ok('S6b queueMessage 钉入队时刻（ts）', src.includes('ts: Date.now()'));
  ok('S6c persistQueue 落盘 ts（跨刷新真值源）', src.includes('ts: it.ts || null'));
  ok('S6d drain 补渲用 item.ts（非 Date.now()）',
    src.includes('renderUserBubble(item.text, item.attachments, item.ts)'));
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
