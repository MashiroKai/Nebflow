#!/usr/bin/env node
// kernel-badge-shots.mjs — 后台智能体面板「agentflow 徽标来源」批（作者 2026-11-06
// 令「怎么 kernel 被标记 agentflow？agentflow 是用来标记 dispatcher 派生出来的节点
// 的」）视觉证据样张。
//
// 样张 = 同一份夹具，只换被拍的树（改前 / 改后各拍一次，同名文件）：
//   ① kernel 批（mode=kernel，orchestrationMode=subagent-star，真实上限）
//   ② dispatcher 且登记过链（mode=dispatcher，orchestrationMode=agentflow）
//      —— 唯一该带 agentflow 词的整行
//   ③ dispatcher 但从未登记链（orchestrationMode=single）—— 无派生事实
//   ④ 成员行（分发器 / 节点 / Subagent / Delegate）
//
// 用法：node tests/kernel-badge-shots.mjs [outDir]
// 默认产出 = 项目 vault 的 docs/assets/（图随文档落地，见 wiki-docs R6）。
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/Users/kaiyu/.stand/projects/stand/.stand/docs/assets';
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'kbs-root-session';
const K = 'delegate-kbs-kernel-01';
const D = 'delegate-kbs-disp-02';
const D0 = 'delegate-kbs-disp-nocchain-03';
const T0 = Date.now();

const row = (sid, title, mode, orchestrationMode, taskNo) => ({
  taskNo, delegateId: sid, title, name: title.slice(0, 6), executor: 'nebflow',
  status: 'running', external: false, project: '', createdAt: T0 - 12 * 60_000,
  orchestrationMode, mode, archivedAt: null, mergeQueue: null, mergeQueuePos: null,
});
const head = (sid, name, taskDescription) => ({
  type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
  agentId: sid, nodeSessionId: sid, name, taskDescription,
});
const chain = (sid, name, status, over = {}) => ({
  type: 'agentStart', sessionId: D, rootSessionId: D,
  agentId: sid, nodeSessionId: sid, name, taskDescription: '', project: 'Nebflow',
  status, ...over,
});

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/') || p.startsWith('/agents/')) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch {
    return route.fulfill({ status: 404, body: 'nf' });
  }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
    folders: [], activeId: ROOT_SID,
  }));
  sendConfig(); sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});
await page.goto('http://localhost:1/');
await page.waitForFunction(async (rootSid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
}, ROOT_SID, { timeout: 15000 });
await page.waitForTimeout(300);

await page.evaluate(async (fx) => {
  const stub = await import('/js/delegateChainStub.js');
  stub.__setFixture(fx);
}, {
  rows: [
    row(K, '内核批次（kernel 实例）', 'kernel', 'subagent-star', '95'),
    row(D, '分发器批次（登记过链）', 'dispatcher', 'agentflow', '96'),
    row(D0, '分发器空转批次（没登记链）', 'dispatcher', 'single', '97'),
  ],
  children: {
    [D]: [
      { taskId: 'store-subagent-kbs', source: 'subagent', status: 'completed', agentName: 'stand', description: '只读侦察' },
      { taskId: 'store-delegate-kbs', source: 'delegate', status: 'completed', agentName: 'stand', description: '嵌套委托' },
    ],
  },
});

const inject = (o) => page.evaluate(async (frame) => {
  const s = (await import('/js/state.js')).default;
  s.ws.onmessage({ data: JSON.stringify(frame) });
}, o);

await inject(head(K, 'KernelBatch', '内核批次（kernel 实例）'));
await inject(head(D, 'DispBatch', '分发器批次（登记过链）'));
await inject(head(D0, 'DispIdleBatch', '分发器空转批次（没登记链）'));
await inject(chain('dispatcher-kbsaaaa', 'dispatcher/Nebflow', 'Processing'));
await inject(chain('node-kbsbbbb', 'stand', 'Processing'));

await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
await page.click('#bgagent-indicator');
await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
await page.waitForTimeout(400);

// 徽标 dump（与截图同帧，避免「图好看但数据不对」）。
const dump = await page.evaluate(() => {
  const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
  return [...(list ? list.children : [])]
    .filter((el) => el.classList.contains('bg-task-row'))
    .map((el) => ({
      sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
      level: el.getAttribute('data-bg-row-level') || '',
      entity: el.getAttribute('data-bg-entity') || '',
      kinds: [...el.querySelectorAll('.bg-task-kind')].map((x) => x.textContent),
      entities: [...el.querySelectorAll('.bg-task-entity')].map((x) => x.textContent),
      name: (el.querySelector('.bg-task-name') || {}).textContent || '',
    }));
});
console.log('[dump] ' + JSON.stringify(dump, null, 1));

await page.locator('#bg-dropdown').screenshot({ path: join(OUT, 'kernel-badge-panel.png') });
console.log('[shot] ' + join(OUT, 'kernel-badge-panel.png'));

await browser.close();
