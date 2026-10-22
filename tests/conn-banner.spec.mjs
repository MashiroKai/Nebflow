// conn-banner.spec.mjs — 对话层活性批③：传输层可见化（断连横幅 + 滞留可见 + 恢复冲刷）
//
// 症状：ws 断连此前唯一可见面 = 发送按钮变灰；长回合期间断网 ⇒ 界面静止 +
// 用户消息滞留，传输问题伪装成卡死。本 spec 钉三段：
//   D  断开  ⇒ 顶栏横幅 #conn-banner 显现「连接断开 · 正在重连」；
//   Q  断开期间入队 ⇒ 横幅追加「1 条消息待送达」（滞留状态可见）+ 乐观气泡待送达标；
//   R  重连  ⇒ 横幅收起，离线队列自动冲刷（帧出线、气泡翻已送达）。
//
// 断开/重连走真实链路：routeWebSocket 服务端腿收到探针帧后 ws.close() ⇒ 客户端
// 真实 onclose ⇒ 真实指数退避重连（假后端重新受理引导帧族）⇒ flushOfflineQueue。
//
// Run: node tests/conn-banner.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8190);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'cas-root-session';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 每条连接独立的假后端：D 腿由探针帧触发 close()。 */
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
      if (parsed.type === '__test_drop') { ws.close(); return; }
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

function bannerGeo(page) {
  return page.evaluate(() => {
    const el = document.getElementById('conn-banner');
    if (!el) return null;
    return {
      exists: true,
      hidden: el.classList.contains('hidden'),
      shown: el.classList.contains('show'),
      text: el.querySelector('.conn-banner-text')?.textContent || '',
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

  // R0 — 在线 ⇒ 横幅隐藏。
  let g = await bannerGeo(page);
  ok('R0 banner exists and hidden while connected', g && g.exists && g.hidden === true && g.shown === false, g);

  // 腿 D — 服务端真断开 ⇒ 真实 onclose ⇒ 横幅「连接断开 · 正在重连」。
  await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    s.ws.send(JSON.stringify({ type: '__test_drop' }));
  });
  await page.waitForFunction(() => document.getElementById('conn-banner')?.classList.contains('show'), null, { timeout: 8000 });
  g = await bannerGeo(page);
  ok('D1 banner shown on disconnect', g.shown === true && g.hidden === false, g);
  ok('D2 banner text reads 连接断开 · 正在重连', g.text.includes('连接断开') && g.text.includes('重连'), { text: g.text });
  const connState = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return s.connected;
  });
  ok('D3 state.connected flipped false', connState === false);

  // 腿 Q — 断开期间插话 ⇒ 离线入队：乐观气泡待送达标 + 横幅追加「1 条待送达」。
  await page.fill('#input', '断线期间的插话');
  await page.evaluate(async () => { (await import('/js/input.js')).send(); });
  await sleep(300);
  g = await bannerGeo(page);
  ok('Q1 banner counts the stuck message', g.text.includes('1 条消息待送达'), { text: g.text });
  const bubble = await page.evaluate(() => {
    const row = document.querySelector('.row.user[data-msg-id]');
    return {
      present: !!row,
      text: row?.querySelector('.bubble')?.textContent || '',
      pending: !!row?.querySelector('.msg-pending-mark'),
    };
  });
  ok('Q2 offline bubble rendered with pending mark', bubble.present && bubble.pending && bubble.text.includes('断线期间的插话'), bubble);

  // 腿 R — 真实重连（指数退避 ≈2-3s 内落地）⇒ 横幅收起 + 队列自动冲刷。
  await page.waitForFunction(() => !document.getElementById('conn-banner')?.classList.contains('show'), null, { timeout: 15000 });
  g = await bannerGeo(page);
  ok('R1 banner hidden after reconnect', g.shown === false, g);
  // 冲刷：flushOfflineQueue（onReconnect 腿）派发离线项 ⇒ 小标摘除 + 队列清空。
  await sleep(800);
  const flushed = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    const row = document.querySelector('.row.user[data-msg-id]');
    return {
      queueLeft: (s.messageQueue[sid] || []).filter((it) => it.offline).length,
      markGone: !row?.querySelector('.msg-pending-mark'),
      bubbleStill: !!row,
    };
  }, SID);
  ok('R2 offline queue flushed after reconnect', flushed.queueLeft === 0, flushed);
  ok('R3 bubble flipped to delivered (mark removed, row stays)', flushed.markGone && flushed.bubbleStill, flushed);

  console.log('\nconn-banner.spec done');
} finally {
  await browser.close();
}
