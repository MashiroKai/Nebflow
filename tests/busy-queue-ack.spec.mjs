// busy-queue-ack.spec.mjs — 对话层活性批②：插话即时确认「已收到 · 排队第 N 位」
//
// 症状：长回合期间用户插话，消息只收进底部队列条（长对话里滚离视口），对话面
// 零反馈——「界面无任何活动迹象」的另一半体感。本 spec 钉：busy 中发送 ⇒ 乐观
// 气泡立即上屏 + footer「已收到 · 排队第 N 位」确认标 + 队列条同步计数；done 后
// drain ⇒ 确认标原位摘除（翻已派发态）、气泡恰一份（零重复）；撤回 ⇒ 气泡随项
// 收掉。
//
// harness = chat-autoscroll-follow.spec.mjs 同一套（静态路由 + routeWebSocket +
// 真实 ws.js 分发路径）。零后端、零 sbt、零构建。
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
      hasQueueMark: !!r.querySelector('.msg-queued-mark'),
      queueMarkText: r.querySelector('.msg-queued-mark')?.textContent || '',
    }));
    const bar = document.getElementById('queue-bar');
    return {
      rows,
      queueVisible: bar.classList.contains('visible'),
      queueItems: [...bar.querySelectorAll('.queue-item')].map((q) => q.querySelector('.queue-item-text')?.textContent || ''),
    };
  });
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

  // 腿 1 — busy 中插话 ⇒ 乐观气泡 + 「已收到 · 排队第 1 位」确认标 + 队列条。
  await page.fill('#input', '插话：先看看 ws 事件面');
  await page.evaluate(async () => { (await import('/js/input.js')).send(); });
  await sleep(200);
  let g = await chatGeo(page);
  ok('A1 optimistic bubble rendered', g.rows.length === 1 && g.rows[0].text.includes('插话：先看看 ws 事件面'), g.rows);
  ok('A2 ack mark present', g.rows[0].hasQueueMark === true);
  ok('A3 ack text reads 已收到 · 排队第 1 位', g.rows[0].queueMarkText.includes('已收到') && g.rows[0].queueMarkText.includes('第 1 位'), { mark: g.rows[0].queueMarkText });
  ok('A4 queue bar shows the item', g.queueVisible === true && g.queueItems.length === 1, g.queueItems);
  ok('A5 input cleared', (await page.inputValue('#input')) === '');

  // 腿 2 — 第二条插话 ⇒ 排队第 2 位。
  await page.fill('#input', '插话二：还有一条');
  await page.evaluate(async () => { (await import('/js/input.js')).send(); });
  await sleep(200);
  g = await chatGeo(page);
  ok('B1 second ack reads 排队第 2 位', g.rows[1] && g.rows[1].queueMarkText.includes('第 2 位'), { rows: g.rows.map(r => r.queueMarkText) });
  ok('B2 queue bar counts 2', g.queueItems.length === 2, g.queueItems);

  // 腿 3 — 回合终态后 drain：队首确认标摘除、气泡恰一份、其余留队（drain 每次
  // 派发一条 = 一个新回合；其余项等下一轮 done）。
  await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    s.busySessionIds.delete(sid);
    const { drainMessageQueue } = await import('/js/input.js');
    drainMessageQueue(sid);
  }, SID);
  await sleep(400);
  g = await chatGeo(page);
  ok('C1 head mark flipped off after drain', g.rows[0] && g.rows[0].hasQueueMark === false, g.rows);
  ok('C2 both bubbles still present exactly once', g.rows.length === 2, { n: g.rows.length });
  ok('C3 tail stays queued (one message per turn)', g.queueVisible === true && g.queueItems.length === 1, g.queueItems);
  const queueState = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return { q: (s.messageQueue[sid] || []).length, expecting: !!s.turnExpecting[sid], busy: s.busySessionIds.has(sid) };
  }, SID);
  ok('C4 drain re-armed the turn (busy + expecting)', queueState.busy === true && queueState.expecting === true, queueState);
  ok('C5 queue holds the tail item', queueState.q === 1, queueState);

  // 腿 4 — 撤回：第二条还在队里（drain 只发第一条）⇒ 移除 ⇒ 气泡随项收掉。
  await page.click('#queue-bar .queue-item .queue-item-btn.remove');
  await sleep(200);
  g = await chatGeo(page);
  ok('D1 recall removes the queued bubble', g.rows.length === 1, { n: g.rows.length });

  console.log('\nbusy-queue-ack.spec done');
} finally {
  await browser.close();
}
