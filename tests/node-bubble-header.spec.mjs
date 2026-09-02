// node-bubble-header.spec.mjs — Node 完成通知蓝气泡 header 格式验收。
//
// 需求: Node 完成通知（注入消息，蓝色气泡）header 统一为
//   NODE · <项目名> · <节点名> · <状态>（状态全大写: COMPLETED / FAILED / CANCELED）。
// 前端契约 (chat.js injectedSourceLabel node 分支):
//   WS user 事件 { type:'user', injected:true, source:'node', eventType,
//                  sender:'<project>/<node>', sessionId } →
//   .ask-label.injected-source-label 文本 = 'NODE · <project> · <node> · <STATUS>'
// 覆盖: completed / failed / cancelled 状态 + 旧历史行 (sender='node' 无 '/') 优雅降级
//       + 非 Node 类注入消息格式不受影响 (mail 对照)。
//
// Run: node node_modules/@playwright/test/cli.js test tests/node-bubble-header.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'bubble-root-session';

async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 读取当前所有注入气泡 header 文本（按出现顺序）。 */
function headers(page) {
  return page.locator('#chat .row.user .bubble.injected .ask-label.injected-source-label').allTextContents();
}

/** 注入一条 Node 完成通知 user 事件。 */
function injectNodeBubble(page, { eventType, sender, text }) {
  return inject(page, {
    type: 'user',
    text: text || `[Node '调研' ${eventType}]\nresult...`,
    injected: true,
    source: 'node',
    eventType,
    sender,
    sessionId: ROOT_SID,
  });
}

test('node bubble: NODE · project · node · COMPLETED (completed)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await injectNodeBubble(page, {
    eventType: 'completed',
    sender: 'czt-project/仿真跑批',
    text: "[Node '仿真跑批' completed]\nR=3.2%",
  });

  const h = await headers(page);
  expect(h).toEqual(['NODE · czt-project · 仿真跑批 · COMPLETED']);
  expect(pageErrors).toEqual([]);
});

test('node bubble: NODE · project · node · FAILED (failed)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await injectNodeBubble(page, {
    eventType: 'failed',
    sender: 'sipm-paper/图表核对',
    text: "[Node '图表核对' failed]\nagent not found",
  });

  const h = await headers(page);
  expect(h).toEqual(['NODE · sipm-paper · 图表核对 · FAILED']);
  expect(pageErrors).toEqual([]);
});

test('node bubble: cancelled status renders CANCELED', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await injectNodeBubble(page, {
    eventType: 'cancelled',
    sender: 'demo/长任务',
    text: "[Node '长任务' cancelled]\nby NodeCancel",
  });

  const h = await headers(page);
  expect(h).toEqual(['NODE · demo · 长任务 · CANCELED']);
  expect(pageErrors).toEqual([]);
});

test('node bubble: legacy rows (sender="node", no "/") degrade to NODE · STATUS', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 旧历史行：sender="node"（无 '/' 分隔）→ 不崩、不误切，只显 NODE · COMPLETED
  await injectNodeBubble(page, {
    eventType: 'completed',
    sender: 'node',
    text: "[Node '旧行' completed]\nlegacy",
  });

  const h = await headers(page);
  expect(h).toEqual(['NODE · COMPLETED']);
  expect(pageErrors).toEqual([]);
});

test('non-node injected messages keep their format (mail control)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // Mail 注入：格式不受 node 分支影响（SOURCE · SENDER · EVENT_TYPE）
  await inject(page, {
    type: 'user',
    text: 'do the thing',
    injected: true,
    source: 'mail',
    eventType: 'result',
    sender: 'Manager',
    sessionId: ROOT_SID,
  });

  const h = await headers(page);
  expect(h).toEqual(['Mail · Manager · Result']);
  expect(pageErrors).toEqual([]);
});
