// queuebar-informative.spec.mjs — 排队呈现回退批（2026-10-22 作者令「怎么还出现了
// 发送了的消息还在队列里的问题。我觉得这个可能需要回退，已收到的标记也是多余的。」）
// 验收：今日 #714 乐观气泡批 + 排队信息量批的**呈现层回退**新契约。
//
// 作者令拆解（新契约）：
//   · 「发送了的消息还在队列里」⇒ 去重复：busy 排队消息已作为乐观气泡上屏，队列条
//     不再重复渲染其内容行——队列条只保留头行「等待当前回复完成 · N 条排队」
//     （活秒表保留），行级明细（文本/位次标/已收到 chip/按钮/拖拽）全退役；
//   · 「已收到的标记也是多余的」⇒ 气泡 footer 的 .acked 绿✓确认标 + 队列行 ack
//     chip 同撤——消息上屏本身即收到，不用标；
//   · 能力不丢、入口收敛：立即发送/撤回/移除移入乐观气泡右键菜单
//     （msgQuote.js 挂点 → input.js runQueuedAction 执行面）；
//   · 保留：乐观气泡本身、离线腿「待送达」时钟标（真离线未出线，语义不同）、
//     drain 翻面机制（flipOfflineMark 按 .msg-pending-mark 寻址不动）、
//     qattachvault 禁静默面（行级失败注记退役 ⇒ 头行红色失败计数后缀）。
//   · 数据层不动：state.messageQueue / queueMessage 钉死寻址 / persistQueue 全原样。
//
// Pinned here (binary)：
//   Q1 busy 插话 ⇒ #queue-bar visible，头行 = 「等待当前回复完成 · 2 条排队」。
//   Q2 头行活秒表在场（.queue-bar-elapsed[data-start]，已进行 \d+s）且走字（1s ticker）。
//   Q3 去重复：#queue-bar 内零 .queue-item 行（无文本/位次/按钮再渲染）。
//   Q4 去已收到标：全文零 .msg-pending-mark.acked；busy 气泡零 .msg-pending-mark
//      （裸上屏；离线腿时钟标语义互斥，不在本场景）。
//   Q5 乐观气泡在场 ×2（上屏可见性保留），文本各自完整。
//   Q6 右键菜单：排队气泡 ⇒ 菜单含 立即发送/撤回/移除（+复制/引用）；点「立即发送」
//      ⇒ 恰一帧立即出线、队列计数 2→1、气泡数不变（零新增呈现）。
//   Q7 drain 后：队列条隐藏、气泡数不变、全文零 pending 标。
//   Q8 静态源钉：busy 腿无 pending 标 / 离线腿 pending:true 保留 / chatQueue.js
//      行族退役 / locales 无 queuedAck / 右键菜单 runQueuedAction 通道。
//   Q9 亮暗双版截图（全窗 + 输入区特写）。
//
// Run: node tests/queuebar-informative.spec.mjs
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
const SUB = process.argv[2] || '20261022_queue-rollback/after';
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
      // 真实时序补齐：turnStartTimes 锚由流帧族写入（main.js onMessage('thinking')
      // 守卫写入；sessionBusy 帧本身不写锚）——真实引擎开 turn 后立刻流 thinking。
      ws.send(JSON.stringify({ type: 'thinking', sessionId: SID }));
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
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();

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

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  // 会话进 busy（sessionBusy busy:true ⇒ isBusy + state.turnStartTimes 锚写入），
  // 然后两连发（busy 排队腿）。
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_arm_busy' });
  });
  await sleep(300);
  const sendText = async (text) => {
    const input = page.locator('#input');
    await input.click();
    await input.fill(text);
    await input.press('Enter');
    await sleep(350);
  };
  await sendText('排队回退验证第一条（队列条不得重复渲染我）');
  await sendText('排队回退验证第二条');

  const bar = await page.evaluate(() => {
    const bar = document.getElementById('queue-bar');
    const title = bar?.querySelector('.queue-bar-title')?.textContent || '';
    const elapsed = bar?.querySelector('.queue-bar-elapsed');
    return {
      visible: bar?.classList.contains('visible') || false,
      title,
      elapsedText: elapsed?.textContent || '',
      elapsedStart: elapsed?.dataset.start || '',
      rowCount: bar?.querySelectorAll('.queue-item').length ?? -1,
      anyRowClass: bar?.querySelectorAll('[class*="queue-item"]').length ?? -1,
    };
  });
  const tag = scheme;
  ok(`Q1[${tag}] 排队条可见 + 头行 = 等待当前回复完成 · 2 条排队`,
    bar.visible && bar.title.includes('等待当前回复完成') && bar.title.includes('2 条排队'),
    `title=${bar.title}`);
  ok(`Q2[${tag}] 头行活秒表在场（已进行 Ns，锚 turnStartTimes）`,
    !!bar.elapsedStart && /^ · 已进行 \d+s$/.test(bar.elapsedText),
    `elapsed=${bar.elapsedText} start=${bar.elapsedStart}`);
  ok(`Q3[${tag}] 去重复：队列条零行级明细（.queue-item* 全退役）`,
    bar.rowCount === 0 && bar.anyRowClass === 0,
    `rows=${bar.rowCount} anyRowClass=${bar.anyRowClass}`);

  // 气泡侧：裸上屏（无已收到标、无 pending 标）+ 双泡在场
  const bubbles = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return rows.map(r => ({
      text: (r.querySelector('.bubble.user')?.textContent || ''),
      pendingMarks: r.querySelectorAll('.msg-pending-mark').length,
      ackedMarks: r.querySelectorAll('.msg-pending-mark.acked').length,
    }));
  });
  ok(`Q4[${tag}] 去已收到标：busy 气泡零 .acked 且零 pending 标（裸上屏）`,
    bubbles.length === 2 && bubbles.every(b => b.ackedMarks === 0 && b.pendingMarks === 0),
    JSON.stringify(bubbles.map(b => ({ acked: b.ackedMarks, pending: b.pendingMarks }))));
  ok(`Q5[${tag}] 乐观气泡在场 ×2（上屏可见性保留）且文本各自完整`,
    bubbles.length === 2
    && bubbles[0].text.includes('第一条') && !bubbles[0].text.includes('第二条')
    && bubbles[1].text.includes('第二条') && !bubbles[1].text.includes('第一条'),
    JSON.stringify(bubbles.map(b => b.text.slice(0, 14))));

  // 秒表走字实证（≥1.2s 后读数必须变化）
  const t1 = bar.elapsedText;
  await sleep(1400);
  const t2 = await page.evaluate(() => document.querySelector('#queue-bar .queue-bar-elapsed')?.textContent || '');
  ok(`Q2b[${tag}] 秒表走字（1s ticker 实证）`, t1 !== t2 && /\d+s$/.test(t2), `${t1} → ${t2}`);

  // Q6 右键菜单：能力收敛入口（立即发送/撤回/移除）
  const menuLabels = await page.evaluate(() => {
    const row = [...document.querySelectorAll('#chat .row.user')]
      .find(r => (r.querySelector('.bubble.user')?.textContent || '').includes('第一条'));
    if (!row) return null;
    row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 400, clientY: 300 }));
    return [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')].map(b => b.textContent || '');
  });
  ok(`Q6[${tag}] 排队气泡右键菜单含 立即发送/撤回/移除`,
    Array.isArray(menuLabels)
    && menuLabels.includes('立即发送') && menuLabels.includes('撤回') && menuLabels.includes('移除'),
    JSON.stringify(menuLabels));

  // 点「立即发送」⇒ 恰一帧立即出线（accepted 记录），队列 2→1，气泡数不变
  const framesBefore = accepted.length;
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')]
      .find(b => b.textContent === '立即发送');
    btn?.click();
  });
  await sleep(700);
  const afterImmediate = await page.evaluate(() => ({
    title: document.getElementById('queue-bar')?.querySelector('.queue-bar-title')?.textContent || '',
    userRows: document.querySelectorAll('#chat .row.user').length,
  }));
  ok(`Q6b[${tag}] 立即发送：恰一帧出线 + 队列计数 2→1 + 气泡数不变`,
    accepted.length === framesBefore + 1
    && afterImmediate.title.includes('1 条排队')
    && afterImmediate.userRows === 2,
    `frames=${accepted.length - framesBefore} title=${afterImmediate.title} rows=${afterImmediate.userRows}`);
  await page.evaluate(() => document.querySelector('.fm-context-menu')?.remove());

  // 截图（排队条 + 输入区特写 + 全窗）
  await page.screenshot({ path: join(OUT, `D-queuebar-rollback-${tag}.png`) });
  const inputBox = await page.evaluate(() => {
    const b = document.getElementById('queue-bar').getBoundingClientRect();
    return { y: Math.max(0, b.top - 140), h: Math.min(860, window.innerHeight - Math.max(0, b.top - 140)) };
  });
  await page.screenshot({
    path: join(OUT, `D-queuebar-rollback-${tag}-closeup.png`),
    clip: { x: 0, y: inputBox.y, width: 1280, height: inputBox.h },
  });

  // drain：放行 → 剩余一项出线，队列条隐藏、无 pending 标
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(2000);
  const after = await page.evaluate(() => ({
    visible: document.getElementById('queue-bar').classList.contains('visible'),
    pending: document.querySelectorAll('#chat .row.user .msg-pending-mark').length,
    acked: document.querySelectorAll('.msg-pending-mark.acked').length,
    rows: document.querySelectorAll('#chat .row.user').length,
  }));
  ok(`Q7[${tag}] drain 后队列条隐藏 + 零 pending/acked 标 + 气泡数不变`,
    !after.visible && after.pending === 0 && after.acked === 0 && after.rows === 2,
    JSON.stringify(after));
  ok(`Q9[${tag}] 零页面错误`, pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
await new Promise(r => server.close(r));

// ── Q8 静态源钉：回退批新契约（同树同读数） ──────────────────────────
{
  const input = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const busyLeg = input.match(/if \(isBusy && !isResend && !state\.frozenSessions\.has\(v\.sessionId\)\) \{([\s\S]*?)\n  \}/);
  ok('Q8a busy 腿乐观气泡裸上屏（rendered:true 在场、无 queued 确认标）',
    !!busyLeg && busyLeg[1].includes('rendered: true')
    && busyLeg[1].includes('renderUserBubble(text, v.pendingAttachments, undefined, { msgId: item.clientMessageId })')
    && !busyLeg[1].includes("'queued'"));
  const offlineLeg = input.match(/function enqueueOfflineMessage[\s\S]{0,2000}/) || '';
  ok('Q8b 离线腿保持 pending:true（待送达时钟标，语义互斥保留）',
    String(offlineLeg).includes('pending: true'));
  ok('Q8c 队列动作执行面 runQueuedAction 导出在场',
    /export function runQueuedAction\(sessionId, clientMessageId, action\)/.test(input));

  const cq = readFileSync(join(WEB, 'js', 'chatQueue.js'), 'utf8');
  ok('Q8d 队列条头行-only（行族渲染全退役：无 queue-item 构建）',
    cq.includes('queue-bar-header') && !cq.includes('queue-item-pos') && !cq.includes('queue-item-ack')
    && !cq.includes('queue-item-btn'));
  ok('Q8e 失败计数禁静默面在场（chatQueue.failedCount）',
    cq.includes('chatQueue.failedCount') && cq.includes('queue-bar-failed'));

  const chat = readFileSync(join(WEB, 'js', 'chat.js'), 'utf8');
  ok('Q8f 气泡侧 .acked 变体退役（createPendingSendMark 单形态）',
    !chat.includes(".msg-pending-mark' + (kind === 'queued'") && chat.includes('function createPendingSendMark()'));

  const css = readFileSync(join(WEB, 'css', 'chat.css'), 'utf8');
  ok('Q8g chat.css 无 .acked 规则块', !css.includes('.msg-pending-mark.acked'));

  const mq = readFileSync(join(WEB, 'js', 'msgQuote.js'), 'utf8');
  ok('Q8h 右键菜单队列动作通道（runQueuedAction 动态 import + 三个动作项）',
    mq.includes("import('./input.js')") && mq.includes("run('immediate')")
    && mq.includes("run('recall')") && mq.includes("run('remove')"));

  const zh = readFileSync(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8');
  ok('Q8i 退役键清零（无 chat.queuedAck/chatQueue.position），撤回键在场',
    !zh.includes("'chat.queuedAck'") && !zh.includes("'chatQueue.position'") && zh.includes("'chat.queueRecall'"));
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
