// queuebar-informative.spec.mjs — 输入消息排队回退批（2026-10-23 作者令「我们的
// 输入消息排队功能采用我们的之前的。大概两天以前的设计」）验收：排队面恢复
// 2026-10-21（BASE 383f4e197）形态，本 spec 换代钉 **BASE 排队条契约**。
//
// 作者令拆解（BASE 契约）：
//   · busy 插话 = 消息入队，呈现面 = 输入框上方**排队条行级明细**（文本/
//     附件缩略/立即发送/撤回/移除/拖拽排序/点击展开/折叠钮）——10-22 并泡诊断
//     批的「入队即上屏乐观气泡」、排队信息量批的「头行活秒表/条数」、排队呈现
//     回退批的「气泡右键队列动作」随本批全数退役；
//   · 单呈现面：busy 中排队消息**不在会话流**（零乐观气泡零标），当前回复完成
//     （drain）后逐条补渲气泡（一行一气泡，时间戳 = 入队时刻 item.ts 真值钉）；
//   · 队列动作唯一入口 = 排队条行按钮（立即发送/撤回/移除）；
//   · 保留：数据层（state.messageQueue / queueMessage 钉死寻址 / drain 翻面 /
//     persistQueue）、离线腿「待送达」乐观气泡与时钟标（真离线未出线，语义不同）、
//     qattachvault 失败项行级注记（载荷丢失禁静默）。
//
// Pinned here (binary)：
//   Q1 busy 插话 ⇒ #queue-bar visible + .queue-item 行 ×2 + 头行「排队中 (2)」。
//   Q2 行文本互不并文（逻辑消息独立成行）。
//   Q3 单呈现面：busy 中会话流零 .row.user、零 .msg-pending-mark。
//   Q4 折叠钮在场：collapsed ⇒ 行隐藏，展开 ⇒ 行回场。
//   Q5 行动作：移除 ⇒ 行 2→1；撤回 ⇒ 行消失 + 文本回输入框。
//   Q6 行「立即发送」⇒ immediateInput 帧立即出线 + 行 2→1 + 气泡在场（立即
//      补渲，时间戳 = 入队时刻）+ busy 不破。
//   Q7 drain ⇒ 队列条隐藏 + 剩余项补渲气泡（data-msg-id = 帧 clientMessageId）。
//   Q8 亮暗双版截图（全窗 + 输入区特写）。
//   Q9 零页面错误。
//   S1-S8 静态源钉：busy 腿直入队零气泡 / refreshQueue 三 handler / 右键菜单
//      队列动作退役 / chatQueue 行族回归零秒表 / locales 键集双向钉。
//
// Run: node tests/queuebar-informative.spec.mjs [evidenceSubdir]
// 形制同 tests/send-bubble-independence.spec.mjs（真 web 树 + 真 WS 桩 + routeWebSocket）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SID = 's-queue-info';
const SUB = process.argv[2] || '20261023_queue-restore/after';
const OUT = join(HERE, '..', 'evidence', SUB);
mkdirSync(OUT, { recursive: true });

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
// 立即发送腿走 immediateInput 命令帧（sendImmediate 单点）——与默认用户消息帧
// 同等计为「出线帧」（真实后端两条都按用户输入受理）。
const isUserContentFrame = (m) => (!m.type || m.type === 'immediateInput') && typeof m.content === 'string';

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
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '排队回退E2E' }], activeId: SID, folders: [] }));
}

// ── 静态服务器 ────────────────────────────────────────────────────────
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
const BASEURL = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();

const TXT_A = '排队回退验证第一条（BASE 形态：消息只在排队条行）';
const TXT_B = '排队回退验证第二条';

const sendText = async (page, text) => {
  const input = page.locator('#input');
  await input.click();
  await input.fill(text);
  await input.press('Enter');
  await sleep(350);
};

for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, colorScheme: scheme, deviceScaleFactor: 2 });
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

  await page.goto(BASEURL + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  // 会话进 busy（sessionBusy busy:true ⇒ isBusy），然后两连发（busy 排队腿）。
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_arm_busy' });
  });
  await sleep(300);
  await sendText(page, TXT_A);
  await sendText(page, TXT_B);

  const bar = await page.evaluate(() => {
    const bar = document.getElementById('queue-bar');
    const rows = [...(bar?.querySelectorAll('.queue-item') || [])];
    return {
      visible: bar?.classList.contains('visible') || false,
      title: bar?.querySelector('.queue-bar-title')?.textContent || '',
      collapse: !!bar?.querySelector('.queue-bar-collapse'),
      rowTexts: rows.map(r => (r.querySelector('.queue-item-text')?.textContent || '')),
      rowIds: rows.map(r => r.dataset.queueId || ''),
    };
  });
  const tag = scheme;
  ok(`Q1[${tag}] 排队条可见 + 头行「排队中 (2)」+ 折叠钮在场`,
    bar.visible && bar.title.includes('排队中') && bar.title.includes('(2)') && bar.collapse,
    `title=${bar.title} collapse=${bar.collapse}`);
  ok(`Q2[${tag}] 行级明细 ×2 且文本互不并文（逻辑消息独立成行）`,
    bar.rowTexts.length === 2
    && bar.rowTexts[0].includes('第一条') && !bar.rowTexts[0].includes('第二条')
    && bar.rowTexts[1].includes('第二条') && !bar.rowTexts[1].includes('第一条'),
    JSON.stringify(bar.rowTexts.map(t => t.slice(0, 16))));

  // Q3 单呈现面：busy 中会话流零用户气泡、零 pending 标
  const flow = await page.evaluate(() => ({
    userRows: document.querySelectorAll('#chat .row.user').length,
    pendingMarks: document.querySelectorAll('#chat .msg-pending-mark').length,
  }));
  ok(`Q3[${tag}] 单呈现面：busy 中会话流零气泡零标（排队条行 = 唯一呈现）`,
    flow.userRows === 0 && flow.pendingMarks === 0, JSON.stringify(flow));

  // Q4 折叠钮：collapsed ⇒ 行隐藏；展开 ⇒ 行回场
  await page.evaluate(() => document.querySelector('.queue-bar-collapse')?.click());
  await sleep(200);
  const collapsed = await page.evaluate(() => {
    const bar = document.getElementById('queue-bar');
    const row = bar.querySelector('.queue-item');
    return { on: bar.classList.contains('collapsed'), hidden: row ? getComputedStyle(row).display === 'none' : false };
  });
  await page.evaluate(() => document.querySelector('.queue-bar-collapse')?.click());
  await sleep(200);
  const expanded = await page.evaluate(() => {
    const bar = document.getElementById('queue-bar');
    const row = bar.querySelector('.queue-item');
    return { off: !bar.classList.contains('collapsed'), shown: row && getComputedStyle(row).display !== 'none' };
  });
  ok(`Q4[${tag}] 折叠收行 / 展开回行`, collapsed.on && collapsed.hidden && expanded.off && expanded.shown,
    `collapsed=${JSON.stringify(collapsed)} expanded=${JSON.stringify(expanded)}`);

  // Q5 行动作：移除第二行 ⇒ 2→1；撤回第一行 ⇒ 0 + 文本回输入框
  await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#queue-bar .queue-item')];
    rows[1]?.querySelector('.queue-item-btn.remove')?.click();
  });
  await sleep(250);
  const afterRemove = await page.evaluate(() => ({
    rows: document.querySelectorAll('#queue-bar .queue-item').length,
    title: document.querySelector('#queue-bar .queue-bar-title')?.textContent || '',
  }));
  ok(`Q5a[${tag}] 行「移除」：行 2→1 + 头行计数同步`, afterRemove.rows === 1 && afterRemove.title.includes('(1)'),
    `rows=${afterRemove.rows} title=${afterRemove.title}`);
  await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#queue-bar .queue-item')];
    rows[0]?.querySelector('.queue-item-btn.recall')?.click();
  });
  await sleep(250);
  const afterRecall = await page.evaluate(() => ({
    visible: document.getElementById('queue-bar')?.classList.contains('visible') || false,
    inputVal: (document.getElementById('input')?.value || ''),
  }));
  ok(`Q5b[${tag}] 行「撤回」：行清空队列条隐藏 + 文本回输入框`,
    !afterRecall.visible && afterRecall.inputVal.includes('第一条'),
    `visible=${afterRecall.visible} input=${afterRecall.inputVal.slice(0, 20)}`);

  // 重建队列 ×2（供 Q6 立即发送 / Q7 drain 断言）
  await sendText(page, TXT_A);
  await sendText(page, TXT_B);
  await sleep(300);

  // Q6 行「立即发送」：immediateInput 帧立即出线 + 行 2→1 + 气泡在场
  const framesBefore = accepted.length;
  await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#queue-bar .queue-item')];
    rows[0]?.querySelector('.queue-item-btn.immediate')?.click();
  });
  await sleep(700);
  const afterImmediate = await page.evaluate(() => ({
    rows: document.querySelectorAll('#queue-bar .queue-item').length,
    userRows: document.querySelectorAll('#chat .row.user').length,
    rowIds: [...document.querySelectorAll('#queue-bar .queue-item')].map(r => r.dataset.queueId || ''),
  }));
  ok(`Q6[${tag}] 行「立即发送」：帧立即出线 + 行 2→1 + 气泡在场（busy 不破队）`,
    accepted.length === framesBefore + 1 && afterImmediate.rows === 1 && afterImmediate.userRows === 1,
    `frames=+${accepted.length - framesBefore} rows=${afterImmediate.rows} userRows=${afterImmediate.userRows}`);

  // 截图（排队条行级明细：全窗 + 输入区特写）
  await page.screenshot({ path: join(OUT, `Q-queuebar-rows-${tag}.png`) });
  const inputBox = await page.evaluate(() => {
    const b = document.getElementById('queue-bar').getBoundingClientRect();
    return { y: Math.max(0, b.top - 140), h: Math.min(860, window.innerHeight - Math.max(0, b.top - 140)) };
  });
  await page.screenshot({
    path: join(OUT, `Q-queuebar-rows-${tag}-closeup.png`),
    clip: { x: 0, y: inputBox.y, width: 1280, height: inputBox.h },
  });

  // Q7 drain：放行 → 剩余一项出线（气泡补渲，msgId = 帧 clientMessageId），队列条隐藏
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(2200);
  const after = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return {
      visible: document.getElementById('queue-bar')?.classList.contains('visible') || false,
      rows: rows.map(r => ({ msgId: r.dataset.msgId || '', text: (r.querySelector('.bubble.user')?.textContent || '') })),
      pending: document.querySelectorAll('#chat .msg-pending-mark').length,
    };
  });
  ok(`Q7[${tag}] drain 后队列条隐藏 + 剩余项补渲气泡`,
    !after.visible && after.rows.length === 2 && after.rows[1].text.includes('第二条'),
    JSON.stringify(after.rows.map(r => r.text.slice(0, 14))));
  ok(`Q7b[${tag}] 补渲气泡行身份在场且互异（BASE drain 腿渲染行不带队列帧 id——
    行身份 = 生成 id，帧身份 = clientMessageId 走服务端去重，两轨分离）`,
    after.rows.length === 2 && after.rows[0].msgId && after.rows[1].msgId
    && after.rows[0].msgId !== after.rows[1].msgId,
    JSON.stringify(after.rows.map(r => r.msgId)));
  ok(`Q7c[${tag}] busy 排队腿补渲气泡零 pending 标（待送达标 = 离线腿专属）`,
    after.pending === 0);

  ok(`Q9[${tag}] 零页面错误`, pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
await new Promise(r => server.close(r));

// ── S 静态源钉：BASE 排队面契约（同树同读数） ──────────────────────────
{
  const input = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const busyLeg = input.match(/if \(isBusy && !isResend && !state\.frozenSessions\.has\(v\.sessionId\)\) \{([\s\S]*?)\n  \}/);
  ok('S1 busy 腿直入队（零乐观气泡：无 renderUserBubble 无 rendered:true）',
    !!busyLeg && busyLeg[1].includes('queueMessage(v, text, v.pendingAttachments)')
    && !busyLeg[1].includes('renderUserBubble') && !busyLeg[1].includes('rendered: true'));
  ok('S2 refreshQueue 三行按钮 handler 回归（立即发送/撤回/移除）',
    input.includes('onImmediate: (item) => sendImmediate(sessionId, item)')
    && input.includes('onRecall: (item) => recallQueuedItem(sessionId, item)')
    && input.includes('onRemove: (item) => removeQueuedItem(sessionId, item)'));
  ok('S3 右键菜单执行面退役（runQueuedAction/queuedItemByClientId 零残留）',
    !input.includes('runQueuedAction') && !input.includes('queuedItemByClientId'));
  ok('S4 数据层真值钉保留（queueMessage ts + persistQueue 落盘 + drain 补渲 item.ts）',
    input.includes('ts: Date.now()') && input.includes('ts: it.ts || null')
    // queuedirect 批（2026-10-28）：drain 补渲腿抽为 dispatchQueuedItemNow（与直通腿
    // 共用），补渲调用带第四参 opts.msgId（直通腿寻址面）；item.ts 时间戳真值钉不变。
    && input.includes('renderUserBubble(item.text, item.attachments, item.ts, opts.msgId ? { msgId: opts.msgId } : undefined)'));
  const offlineLeg = input.match(/function enqueueOfflineMessage[\s\S]{0,2000}/) || '';
  ok('S5 离线腿乐观气泡 + pending:true 保留（待送达标，语义互斥）',
    String(offlineLeg).includes('rendered: true') && String(offlineLeg).includes('pending: true'));

  const cq = readFileSync(join(WEB, 'js', 'chatQueue.js'), 'utf8');
  ok('S6 chatQueue 行族回归（行文本/按钮/折叠/拖拽/失败注记）+ 零秒表',
    cq.includes('queue-item-text') && cq.includes('queue-item-btn') && cq.includes('queue-item-failed-note')
    && cq.includes("queue-bar-collapse") && cq.includes('dragReorder')
    && !cq.includes('queue-bar-elapsed') && !cq.includes('workline'));

  const mq = readFileSync(join(WEB, 'js', 'msgQuote.js'), 'utf8');
  ok('S7 msgQuote 右键菜单零队列动作（复制/引用单形态）',
    !mq.includes('runQueuedAction') && !mq.includes('messageQueue'));

  const zh = readFileSync(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8');
  const en = readFileSync(join(WEB, 'js', 'locales', 'en.js'), 'utf8');
  ok('S8 locales 键集双向钉：行族键回归 + 10-22 批键退役（zh/en 同）',
    zh.includes("'chatQueue.dragReorder'") && zh.includes("'chatQueue.clickExpand'") && zh.includes("'chatQueue.clickCollapse'")
    && !zh.includes('waitingTurn') && !zh.includes('queuedCount') && !zh.includes('turnElapsed')
    && !zh.includes("'chatQueue.failedCount'") && !zh.includes("'chat.queueRecall'")
    && en.includes("'chatQueue.dragReorder'") && !en.includes('waitingTurn') && !en.includes("'chat.queueRecall'"));
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
