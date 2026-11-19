// mobile-keybadge-shots.mjs — 手机小批「subagent/后台任务键图标常驻 + 角标计数」
// 前后对照截图（375/430 两视口 × 空态/任务态 + 桌面对照脚）。
// 用法：node tests/mobile-keybadge-shots.mjs <before|after>
//   before = 改动前 HEAD 树，after = 本批工作树。
// 产物 = evidence/20261021_mobile-keybadge/shots/<phase>/keybadge-*.png
// 数据面 = 真实 WS 快照帧（activeBgTasks / activeAgents —— 与引擎同一数据源，
// 零伪造 state），harness 形制同 tests/mobile-p6-bgstatus.spec.mjs。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const PHASE = process.argv[2] || 'after';
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261021_mobile-keybadge', 'shots', PHASE);
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'keybadge-root-session';
const T0 = Date.now();

const browser = await chromium.launch();

async function boot(width, height) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: 'dark', deviceScaleFactor: 2, hasTouch: true,
  })).newPage();
  let liveSend = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
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
    liveSend = (frame) => ws.send(JSON.stringify(frame));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return { send: (f) => liveSend?.(f), page };
}

// 任务态夹具：2 条运行中后台任务 + 2 个运行中子智能体（真 WS 帧同形）。
const busyTasks = () => ({
  [SID]: [
    { taskId: 'job-1', description: '长跑构建任务', status: 'running', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
    { taskId: 'job-2', description: '远端巡检任务', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'remote', origin: 'dispatcher', originLabel: 'dispatcher' },
  ],
});
const busyAgents = () => ([
  { agentId: 'subtask-agent-1', rootSessionId: SID, sessionId: 'subtask-agent-1', agentName: '调研子agent', task: '扫库盘点', status: 'running', startedAt: T0 - 60_000, kind: 'Delegate' },
  { agentId: 'subtask-agent-2', rootSessionId: SID, sessionId: 'subtask-agent-2', agentName: '巡检子agent', task: '日志巡检', status: 'running', startedAt: T0 - 20_000, kind: 'Subagent' },
]);

/** 打开左列并等 spring 终态（0.55s 高度 + stagger，1.2s 全落定 —— 与 T1 同判据）。 */
async function openLeftColumn(page) {
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.waitForTimeout(1250);
}

async function shootMobile(width, height, tag, seeded) {
  const { send, page } = await boot(width, height);
  if (seeded) {
    send({ type: 'activeBgTasks', tasks: busyTasks() });
    send({ type: 'activeAgents', agents: busyAgents() });
    await page.waitForTimeout(450);
  }
  await openLeftColumn(page);
  await page.screenshot({ path: join(OUT, `keybadge-${tag}-${width}.png`) });
  const col = await page.locator('.mh-dd[data-dd="left"]').boundingBox();
  if (col) {
    await page.screenshot({
      path: join(OUT, `keybadge-${tag}-${width}-col.png`),
      clip: { x: Math.max(0, col.x - 10), y: Math.max(0, col.y - 10), width: col.width + 20, height: Math.min(height, col.height + 20) },
    });
  }
  await page.context().close();
}

// ── 手机两视口 × 空态/任务态 ─────────────────────────────────────────────
for (const [w, h] of [[375, 812], [430, 932]]) {
  await shootMobile(w, h, 'empty', false);
  await shootMobile(w, h, 'busy', true);
}

// ── 桌面对照脚（零触碰证据）：1280 视口 busy 态，header 右簇胶囊原样 ──────
{
  const page = await (await browser.newContext({
    viewport: { width: 1280, height: 800 }, colorScheme: 'dark', deviceScaleFactor: 2,
  })).newPage();
  let liveSend = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    liveSend = (f) => ws.send(JSON.stringify(f));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  liveSend({ type: 'activeBgTasks', tasks: busyTasks() });
  liveSend({ type: 'activeAgents', agents: busyAgents() });
  await page.waitForTimeout(600);
  const header = await page.locator('#header').boundingBox();
  await page.screenshot({
    path: join(OUT, 'keybadge-desktop-busy-header.png'),
    clip: { x: 0, y: 0, width: 1280, height: Math.ceil((header?.height || 50)) + 140 },
  });
  await page.context().close();
}

await browser.close();
console.log(`[mobile-keybadge-shots] phase=${PHASE} → ${OUT}`);
