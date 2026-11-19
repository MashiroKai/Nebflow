// turn-liveness-shots.mjs — 对话层活性批存续面：断连横幅证据截图。
//
// 历史：本脚本原为对话层活性批（2026-10-22）产出前后对照四张（活动条
// before/after/expanded + 断连横幅）。回合活动流条（对话框上方活动面板）已随
// 2026-10-23 作者令整链退役（逐字「这个活动面板我不是让删掉吗，就是对话框上面
// 这个」；防复活钉 tests/turn-activity-stream.spec.mjs），活动条三腿随之撤销，
// 仅保留断连横幅一腿。
//
// 一张（/tmp/nf-liveness-04-disconnect-banner.png，亮色主题）：
//   04-disconnect-banner.png  断连横幅（顶栏「连接断开 · 正在重连 · N 条待送达」）。
//
// Run: node tests/turn-liveness-shots.mjs   （产出固定 /tmp 路径，供报告引用）

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8191);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json',
};

const SID = 'cas-root-session';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 300)));

await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
      const T0 = Date.now() - 30 * 60 * 1000;
      const msgs = [
        { type: 'user', text: '断连横幅场景过一遍。', ts: T0 },
        { type: 'ai', text: '收到。传输层可见化：断连 ⇒ 顶栏横幅 + 待送达计数，重连自动冲刷。', ts: T0 + 5000 },
      ];
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: msgs, hasMore: false, offset: 0, total: msgs.length }));
    }
  });
});
await page.goto(`http://127.0.0.1:${PORT}/`);
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await page.waitForTimeout(800);

// 04 — 断连横幅：服务端真断开（客户端真发探针帧 → 假后端 close）+ 一条断线插话。
await page.evaluate(async () => {
  const s = (await import('/js/state.js')).default;
  s.ws.send(JSON.stringify({ type: '__test_drop' }));
});
await page.waitForFunction(() => document.getElementById('conn-banner')?.classList.contains('show'), null, { timeout: 8000 });
await page.fill('#input', '断线期间的插话');
await page.evaluate(async () => { (await import('/js/input.js')).send(); });
await sleep(400);
await page.screenshot({ path: '/tmp/nf-liveness-04-disconnect-banner.png', fullPage: false });

console.log('shots saved: /tmp/nf-liveness-04-disconnect-banner.png');
await browser.close();
