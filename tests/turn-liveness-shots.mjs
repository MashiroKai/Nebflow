// turn-liveness-shots.mjs — 对话层活性批：前后对照截图（报告证据面）。
//
// 三张（/tmp/nf-liveness-*.png，亮色主题）：
//   01-before-silent.png   修前对照：同一条长回合（委派批在跑），活动条隐藏
//                          （= 修前产品面）——对话面除 workline 点线外零活动。
//   02-after-strip.png     修后：同一时刻，活动条在场（默认收起 = 一行滚动摘要）。
//   03-after-expanded.png  修后展开：最近 N 条活动行（批派发/工具/待合并/后台任务）。
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
        { type: 'user', text: '把对话层活性批落了：长回合活动流、插话即时确认、断连横幅三个场景。', ts: T0 },
        { type: 'ai', text: '收到。诊断结论：ws 事件面不缺（委派/后台事件已全部广播），缺的是对话层消费——活动只在 Sub-Agents 下拉与弹窗里有面。现在动手。', ts: T0 + 5000 },
        { type: 'user', text: '好，报告要带前后对照。', ts: T0 + 9000 },
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

/** 走真实 ws.js 分发路径注入一帧。 */
const inject = (obj) => page.evaluate(async (o) => {
  const s = (await import('/js/state.js')).default;
  s.ws.onmessage({ data: JSON.stringify(o) });
}, obj);

// 长回合场景：用户消息落地（开轮）→ 思考 → 委派批派发 → 批内工具连转 → 后台任务。
await inject({ type: 'user', sessionId: SID, text: '把三个 delegate 批都派出去，先跑实现批。', ts: Date.now() });
await inject({ type: 'thinking', sessionId: SID });
await sleep(400);
await inject({
  type: 'agentStart', sessionId: SID, rootSessionId: SID,
  agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
  name: '实现批', agentType: 'delegate', taskTitle: '对话层活性批',
});
await sleep(250);
await inject({ type: 'agentToolStart', sessionId: SID, rootSessionId: SID, agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x', label: 'Read src/main/resources/web/js/main.js' });
await inject({ type: 'agentToolStart', sessionId: SID, rootSessionId: SID, agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x', label: 'Grep updateConnBanner' });
await inject({
  type: 'backgroundTaskUpdate', sessionId: SID, rootSessionId: SID,
  taskId: 'bt-17', description: 'sbt Test/compile', status: 'running',
});
await inject({ type: 'agentToolStart', sessionId: SID, rootSessionId: SID, agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x', label: 'Edit turnActivity.js' });
await sleep(500);

// 01 — 修前对照：同一时刻，隐藏活动条（= 修前产品面）。
await page.addStyleTag({ content: '#activity-strip{display:none !important}' });
await page.screenshot({ path: '/tmp/nf-liveness-01-before-silent.png', fullPage: false });
await page.addStyleTag({ content: '#activity-strip{display:flex !important}' });

// 02 — 修后：活动条默认收起（一行滚动摘要）。
await page.screenshot({ path: '/tmp/nf-liveness-02-after-strip.png', fullPage: false });

// 03 — 修后展开。
await page.click('#activity-strip .activity-strip-head');
await sleep(300);
await page.screenshot({ path: '/tmp/nf-liveness-03-after-expanded.png', fullPage: false });

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

console.log('shots saved: /tmp/nf-liveness-01-before-silent.png 02-after-strip.png 03-after-expanded.png 04-disconnect-banner.png');
await browser.close();
