// busy-queue-ack.spec.mjs — 对话层活性批②的**回退换代**（排队呈现回退批
// 2026-10-22 作者令「怎么还出现了发送了的消息还在队列里的问题。我觉得这个可能
// 需要回退，已收到的标记也是多余的。」）。
//
// 原契约（插话即时确认「已收到 · 排队第 N 位」+ 队列条行按钮）已整面退役：
//   · 乐观气泡保留（上屏可见性是好的），但**裸上屏**——零「已收到」标；
//   · 队列条只保留头行「等待当前回复完成 · N 条排队」（活秒表）——消息文本/
//     位次/按钮不再双呈现；
//   · 撤回/移除能力收敛进**气泡右键菜单**（立即发送/撤回/移除 +
//     既有 复制/引用），执行面 = input.js runQueuedAction。
//
// 本 spec 钉（新契约）：
//   A busy 中插话 ⇒ 乐观气泡上屏（零标）+ 头行计数 1 + 零行级明细 + 输入清空；
//   B 第二条 ⇒ 头行计数 2，气泡 ×2 各自独立；
//   C drain（队首派发）⇒ 头泡无标可翻仍恰一份、尾项留队计数 1、busy 重新武装；
//   D 右键「移除」⇒ 尾泡随项收掉、队列条隐藏。
//
// harness = 原文件同一套（静态路由 + routeWebSocket + 真实 ws.js 分发路径）。
// 零后端、零 sbt、零构建。
//
// Run: node tests/busy-queue-ack.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8189);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'cas-root-session';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
  await page.goto(`http://127.0.0.1:${PORT}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(600);
}

async function chatGeo(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    const rows = [...chat.querySelectorAll('.row.user[data-msg-id]')].map((r) => ({
      msgId: r.dataset.msgId,
      text: r.querySelector('.bubble')?.textContent || '',
      pendingMarks: r.querySelectorAll('.msg-pending-mark').length,
      ackedMarks: r.querySelectorAll('.msg-pending-mark.acked').length,
    }));
    const bar = document.getElementById('queue-bar');
    return {
      rows,
      queueVisible: bar.classList.contains('visible'),
      headerText: bar.querySelector('.queue-bar-title')?.textContent || '',
      queueRowCount: bar.querySelectorAll('.queue-item').length,
    };
  });
}

/** 排队呈现回退批：右键菜单驱动（队列条行按钮退役后的唯一入口）。 */
async function clickQueueMenu(page, rowIndex, label) {
  await page.evaluate(({ rowIndex, label }) => {
    const row = [...document.querySelectorAll('#chat .row.user[data-msg-id]')][rowIndex];
    if (!row) throw new Error('no row at ' + rowIndex);
    row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 400, clientY: 300 }));
    const btn = [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')]
      .find((b) => b.textContent === label);
    if (!btn) throw new Error('menu item not found: ' + label);
    btn.click();
  }, { rowIndex, label });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('console', (m) => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 300)); });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 400)));

try {
  await bootApp(page);

  // 置 busy（模拟长回合进行中）。
  await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    s.busySessionIds.add(sid);
  }, SID);

  // 腿 1 — busy 中插话 ⇒ 乐观气泡（零标）+ 头行计数。
  await page.fill('#input', '插话：先看看 ws 事件面');
  await page.evaluate(async () => { (await import('/js/input.js')).send(); });
  await sleep(200);
  let g = await chatGeo(page);
  ok('A1 optimistic bubble rendered', g.rows.length === 1 && g.rows[0].text.includes('插话：先看看 ws 事件面'), g.rows);
  ok('A2 裸上屏：零 pending 标零 .acked（已收到标退役）',
    g.rows[0].pendingMarks === 0 && g.rows[0].ackedMarks === 0,
    { pending: g.rows[0].pendingMarks, acked: g.rows[0].ackedMarks });
  ok('A3 队列条 = 头行计数（1 条排队）且零行级明细',
    g.queueVisible === true && g.headerText.includes('等待当前回复完成') && g.headerText.includes('1 条排队') && g.queueRowCount === 0,
    { header: g.headerText, rows: g.queueRowCount });
  ok('A4 input cleared', (await page.inputValue('#input')) === '');

  // 腿 2 — 第二条插话 ⇒ 头行计数 2。
  await page.fill('#input', '插话二：还有一条');
  await page.evaluate(async () => { (await import('/js/input.js')).send(); });
  await sleep(200);
  g = await chatGeo(page);
  ok('B1 头行计数 2 且零行级明细', g.headerText.includes('2 条排队') && g.queueRowCount === 0, { header: g.headerText });
  ok('B2 两个独立气泡各自完整',
    g.rows.length === 2 && g.rows.every((r) => r.pendingMarks === 0 && r.ackedMarks === 0),
    g.rows.map((r) => r.text.slice(0, 12)));

  // 腿 3 — drain（队首派发）：气泡恰一份零标、尾项留队、busy 重新武装。
  await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    s.busySessionIds.delete(sid);
    const { drainMessageQueue } = await import('/js/input.js');
    drainMessageQueue(sid);
  }, SID);
  await sleep(400);
  g = await chatGeo(page);
  ok('C1 head bubble present exactly once, zero marks',
    g.rows.length === 2 && g.rows[0].pendingMarks === 0 && g.rows[0].ackedMarks === 0, g.rows);
  ok('C2 tail stays queued (one message per turn)',
    g.queueVisible === true && g.headerText.includes('1 条排队') && g.queueRowCount === 0,
    { header: g.headerText });
  const queueState = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return { q: (s.messageQueue[sid] || []).length, expecting: !!s.turnExpecting[sid], busy: s.busySessionIds.has(sid) };
  }, SID);
  ok('C3 drain re-armed the turn (busy + expecting)', queueState.busy === true && queueState.expecting === true, queueState);
  ok('C4 queue holds the tail item', queueState.q === 1, queueState);

  // 腿 4 — 右键「移除」：尾泡随项收掉、队列条隐藏（行按钮退役后的菜单入口）。
  await clickQueueMenu(page, 1, '移除');
  await sleep(250);
  g = await chatGeo(page);
  ok('D1 remove via bubble menu removes the queued bubble', g.rows.length === 1, { n: g.rows.length });
  ok('D2 bar hidden when queue drained by removal', g.queueVisible === false, { header: g.headerText });

  // 腿 5 — 静态钉：右键菜单通道在场（runQueuedAction + 三动作）。
  const src = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  ok('D3 runQueuedAction 执行面导出在场', /export function runQueuedAction\(sessionId, clientMessageId, action\)/.test(src));
  const mq = readFileSync(join(WEB, 'js', 'msgQuote.js'), 'utf8');
  ok('D4 菜单三动作项在场', mq.includes("run('immediate')") && mq.includes("run('recall')") && mq.includes("run('remove')"));

  console.log('\nbusy-queue-ack.spec done');
} finally {
  await browser.close();
}
