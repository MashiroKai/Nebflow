// queuebar-informative.spec.mjs — 排队信息量批（2026-10-22 作者追加令，症状③）验收：
// 聊天输入区「排队中 (N)」死状态退役 —— agent 回合进行中插话的等待面必须有信息量。
//
// 作者令原文拆解：
//   · 「显示正在处理的内容摘要或回合已进行时长」⇒ 头行 = 「等待当前回复完成 ·
//     N 条排队 · 已进行 Xs」（活秒表，锚 state.turnStartTimes[sessionId]，与
//     workline 转轮 hint 同源同格式 formatLive；内容摘要不做——会话流里正在处理的
//     内容以气泡/转轮在场，队列条不重复摘要面，实现在报告申报）。
//   · 「排队项显示第 N 位·等待当前回复完成」⇒ 每行位次标逐字该形态（.queue-item-pos）。
//   · 「排队消息要有明确的已收到视觉（而不是消失在数字里）」⇒ 双呈现：队列行
//     .queue-item-ack 确认 chip（check + 「已收到」）+ 气泡侧 .msg-pending-mark.acked
//     （离线腿「待送达」时钟不变——语义互斥：busy 腿 = 应用已收下排队、帧未出线）。
//
// Pinned here (binary):
//   Q1 busy 插话 ⇒ #queue-bar visible，头行含「等待当前回复完成」+「2 条排队」。
//   Q2 头行活秒表在场（.queue-bar-elapsed[data-start]，文本 = 已进行 \d+s）且走字
//      （≥1s 后读数增长——1s ticker 实证）。
//   Q3 每个排队项位次标逐字 = 「第 N 位 · 等待当前回复完成」，N 升序。
//   Q4 每个排队项带「已收到」确认 chip（svg + 可读文本）。
//   Q5 乐观气泡侧各带一枚 .msg-pending-mark.acked（文本「已收到」）。
//   Q6 drain 后：队列条隐藏、小标全摘、气泡数不变（零新增呈现红线）。
//   Q7 静态源钉：busy 腿 pending:'queued' / 离线腿 pending:true（语义互斥不混用）。
//   Q8 亮暗双版截图（evidence/20261022_chatinput-two-defects/after/D-queuebar-*.png）。
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
const SUB = process.argv[2] || '20261022_chatinput-two-defects/after';
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
      // 真实时序补齐：turnStartTimes 锚由流帧族写入（main.js onMessage('thinking')
      // 守卫写入；sessionBusy 帧本身不写锚）——真实引擎开 turn 后立刻流 thinking。
      // 此处补一帧使头行秒表锚在场（= 作者真实插话场景；无锚窗口 = 降级无时长形态，
      // Q2 降级腿见 spec 头注）。
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
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '排队信息量E2E' }], activeId: SID, folders: [] }));
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
  await sendText('排队信息量验证第一条（作者令：不许消失在数字里）');
  await sendText('排队信息量验证第二条');

  const bar = await page.evaluate(() => {
    const bar = document.getElementById('queue-bar');
    const title = bar?.querySelector('.queue-bar-title')?.textContent || '';
    const elapsed = bar?.querySelector('.queue-bar-elapsed');
    const rows = [...(bar?.querySelectorAll('.queue-item') || [])];
    return {
      visible: bar?.classList.contains('visible') || false,
      title,
      elapsedText: elapsed?.textContent || '',
      elapsedStart: elapsed?.dataset.start || '',
      pos: rows.map(r => r.querySelector('.queue-item-pos')?.textContent || ''),
      ack: rows.map(r => ({
        text: (r.querySelector('.queue-item-ack')?.textContent || '').trim(),
        svg: !!r.querySelector('.queue-item-ack svg'),
      })),
    };
  });
  const tag = scheme;
  ok(`Q1[${tag}] 排队条可见 + 头行有信息量（等待当前回复完成 · 2 条排队）`,
    bar.visible && bar.title.includes('等待当前回复完成') && bar.title.includes('2 条排队'),
    `title=${bar.title}`);
  ok(`Q2[${tag}] 头行活秒表在场（已进行 Ns，锚 turnStartTimes）`,
    !!bar.elapsedStart && /^ · 已进行 \d+s$/.test(bar.elapsedText),
    `elapsed=${bar.elapsedText} start=${bar.elapsedStart}`);
  ok(`Q3[${tag}] 位次标逐字 = 「第 N 位 · 等待当前回复完成」且 N 升序`,
    bar.pos.length === 2 && bar.pos[0].startsWith('第 1 位 · 等待当前回复完成') && bar.pos[1].startsWith('第 2 位 · 等待当前回复完成'),
    JSON.stringify(bar.pos));
  ok(`Q4[${tag}] 每个排队项带「已收到」确认 chip（svg + 可读文本）`,
    bar.ack.length === 2 && bar.ack.every(a => a.svg && a.text.includes('已收到')),
    JSON.stringify(bar.ack));

  // 气泡侧确认标
  const bubbles = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return rows.map(r => ({
      acked: r.querySelectorAll('.msg-pending-mark.acked').length,
      text: (r.querySelector('.msg-pending-mark.acked')?.textContent || '').trim(),
    }));
  });
  ok(`Q5[${tag}] 乐观气泡侧各带一枚「已收到」确认标（.acked + 文本）`,
    bubbles.length === 2 && bubbles.every(b => b.acked === 1 && b.text.includes('已收到')),
    JSON.stringify(bubbles));

  // 秒表走字实证（≥1.2s 后读数必须变化）
  const t1 = bar.elapsedText;
  await sleep(1400);
  const t2 = await page.evaluate(() => document.querySelector('#queue-bar .queue-bar-elapsed')?.textContent || '');
  ok(`Q2b[${tag}] 秒表走字（1s ticker 实证）`, t1 !== t2 && /\d+s$/.test(t2), `${t1} → ${t2}`);

  // 截图（排队条 + 输入区特写 + 全窗）
  await page.screenshot({ path: join(OUT, `D-queuebar-informative-${tag}.png`) });
  const inputBox = await page.evaluate(() => {
    const b = document.getElementById('queue-bar').getBoundingClientRect();
    return { y: Math.max(0, b.top - 140), h: Math.min(860, window.innerHeight - Math.max(0, b.top - 140)) };
  });
  await page.screenshot({
    path: join(OUT, `D-queuebar-informative-${tag}-closeup.png`),
    clip: { x: 0, y: inputBox.y, width: 1280, height: inputBox.h },
  });

  // drain：放行 → 两项出线，队列条隐藏、小标全摘
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(2000);
  const after = await page.evaluate(() => ({
    visible: document.getElementById('queue-bar').classList.contains('visible'),
    pending: document.querySelectorAll('#chat .row.user .msg-pending-mark').length,
    rows: document.querySelectorAll('#chat .row.user').length,
  }));
  ok(`Q6[${tag}] drain 后队列条隐藏 + 小标全摘 + 气泡数不变`,
    !after.visible && after.pending === 0 && after.rows === 2,
    JSON.stringify(after));
  ok(`Q8[${tag}] 零页面错误`, pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
await new Promise(r => server.close(r));

// ── Q7 静态源钉：语义互斥（busy 腿 'queued' / 离线腿 true） ──────────
{
  const src = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const busyLeg = src.match(/if \(isBusy && !isResend && !state\.frozenSessions\.has\(v\.sessionId\)\) \{([\s\S]*?)\n  \}/);
  const offlineLeg = src.match(/function enqueueOfflineMessage[\s\S]{0,2000}/) || '';
  ok(`Q7 busy 腿 pending:'queued'（已收到语义）`, !!busyLeg && busyLeg[1].includes("pending: 'queued'"));
  ok(`Q7b 离线腿保持 pending:true（待送达语义，不混用）`, !!offlineLeg && String(offlineLeg).includes('pending: true'));
  const cq = readFileSync(join(WEB, 'js', 'chatQueue.js'), 'utf8');
  ok(`Q7c 头行秒表锚 = state.turnStartTimes（与 workline 同源）`, cq.includes('state.turnStartTimes[sessionId]'));
  ok(`Q7d 位次标逐字钉（作者原文形态）`, cq.includes("t('chatQueue.position'"));
  const zh = readFileSync(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8');
  ok(`Q7e 文案钉：第 {n} 位 · 等待当前回复完成`, zh.includes("'chatQueue.position': '第 {n} 位 · 等待当前回复完成'"));
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
