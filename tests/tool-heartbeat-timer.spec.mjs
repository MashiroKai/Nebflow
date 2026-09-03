// tool-heartbeat-timer.spec.mjs — 审计 20260903 子项①前端面：
// 工具执行期 toolHeartbeat WS 事件喂活 busy timer。
//
// 根因（审计 §2.5，作者症状直接根因）：前端 busy timer = streamTimeoutMs+30s
// 纯静默（只看 WS 事件流）。前台长工具执行期间 toolStart→toolEnd 之间零事件
// → 630s 到点显示「响应超时」卡 + sendWs({type:'interrupt'}) 杀掉后端正在干活
// 的 turn（实测 56/359 turn 超 630s）。修复：后端工具执行期每 30s 发
// toolHeartbeat；前端注册 onMessage → resetStreamTimeout。
//
// 测试时序（严禁真实等待 630s）：init 脚本把 ≥35s 的 setTimeout 压到 400ms
// （busy timer = streamTimeoutMs(10s)+30s ≥ 35s 唯一命中域；重连退避 ≤30s
// 不受影响）——心跳每 150ms 重置一次 400ms 计时器即可永不到点。
//
// Self-contained: route interception serves src/main/resources/web + mocks the
// WS boot handshake — no backend, no static server, no sbt.
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/tool-heartbeat-timer.spec.mjs
//
// 验红变异：删去 main.js 的 onMessage('toolHeartbeat'/'agentToolHeartbeat')
// 注册块 → T1 红（心跳喂不进 timer → 400ms 压缩窗内必发 interrupt + 超时卡）；
// 恢复后绿。T2 是真停摆回归面（无心跳时 timer 照常触发）。

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

const ROOT_SID = 'e2e-root-session';

async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  // 时间压缩：busy timer 延迟恒为 streamTimeoutMs+30000（本 harness 10s+30s
  // = 40s）→ 压到 400ms；重连退避 ≤30s、UI 短 timer 不受影响。
  await page.addInitScript(() => {
    window.__timerStats = { capped: 0, clearTimeouts: 0 };
    const origSet = window.setTimeout.bind(window);
    window.setTimeout = (fn, delay, ...rest) => {
      if (typeof delay === 'number' && delay >= 35000) {
        window.__timerStats.capped++;
        return origSet(fn, 400, ...rest);
      }
      return origSet(fn, delay, ...rest);
    };
    const origClear = window.clearTimeout.bind(window);
    window.clearTimeout = (id, ...rest) => {
      window.__timerStats.clearTimeouts++;
      return origClear(id, ...rest);
    };
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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'serverConfig', streamTimeoutMs: 10000, version: 'e2e', thinking: null, workSchedule: null,
    }));
    const sendConfigLegacy = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendConfigLegacy();
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
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1 && s.streamTimeoutMs === 10000;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** Drive an inbound WS event through the real handler chain. */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function snapshot(page, sid) {
  return page.evaluate(async (key) => {
    const s = (await import('/js/state.js')).default;
    return {
      busy: s.busySessionIds.has(key),
      timerArmed: s.sessionBusyTimeouts[key] != null,
      stats: window.__timerStats,
      sentInterrupt: null,
    };
  }, sid);
}

async function sentMessages(page) {
  return page.evaluate(() => window.__sentWs || []);
}

/** Record client→server WS frames by wrapping state.ws.send. */
async function armSentRecorder(page) {
  await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    window.__sentWs = [];
    const orig = s.ws.send.bind(s.ws);
    s.ws.send = (data) => {
      try { window.__sentWs.push(typeof data === 'string' ? JSON.parse(data) : data); } catch { /* noop */ }
      return orig(data);
    };
  });
}

/** 生产时序：turn 开始 setBusy 先于活动事件 → 活动事件武装计时器。 */
async function armTimer(page) {
  await inject(page, { type: 'thinkingDelta', sessionId: ROOT_SID, delta: 'working…' });
  await inject(page, { type: 'thinkingDelta', sessionId: ROOT_SID, delta: 'still working…' });
}

test('T1: toolHeartbeat 持续喂活 busy timer——1.5s 心跳段零 interrupt、零超时卡、busy 保持', async ({ page }) => {
  await bootApp(page);
  await armSentRecorder(page);
  await armTimer(page);
  const armed = await snapshot(page, ROOT_SID);
  expect(armed.busy).toBe(true);
  expect(armed.timerArmed).toBe(true);
  const clearsBefore = armed.stats.clearTimeouts;

  // 工具执行期：后端每 30s 发心跳（生产）；此处 150ms 一拍，共 10 拍 =
  // 1.5s ≫ 400ms 压缩窗——每拍都必须重置计时器，否则必发 interrupt。
  for (let i = 0; i < 10; i++) {
    await inject(page, { type: 'toolHeartbeat', sessionId: ROOT_SID, label: 'Bash' });
    await page.waitForTimeout(150);
  }

  const after = await snapshot(page, ROOT_SID);
  expect(after.busy).toBe(true);
  expect(after.busy).toBe(true); // busy 保持（turn 仍在干活）
  expect(after.timerArmed).toBe(true);
  // 心跳 → resetStreamTimeout → clearTimeout 旧计时器：至少 9 次重置
  expect(after.stats.clearTimeouts - clearsBefore).toBeGreaterThanOrEqual(9);
  // 零 interrupt（对照：T2 无心跳 400ms 内即发）
  const sent = await sentMessages(page);
  expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
  // 零「响应超时」卡
  expect(await page.locator('.row.error .error-card').count()).toBe(0);
});

test('T1b: agentToolHeartbeat（子代理原事件）经 rootSessionId 重置 root 会话计时器', async ({ page }) => {
  await bootApp(page);
  await armSentRecorder(page);
  await armTimer(page);
  const clearsBefore = (await snapshot(page, ROOT_SID)).stats.clearTimeouts;

  for (let i = 0; i < 8; i++) {
    await inject(page, {
      type: 'agentToolHeartbeat', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
      agentId: 'delegate-x1', label: 'sbt test',
    });
    await page.waitForTimeout(150);
  }
  const after = await snapshot(page, ROOT_SID);
  expect(after.busy).toBe(true);
  expect(after.stats.clearTimeouts - clearsBefore).toBeGreaterThanOrEqual(7);
  const sent = await sentMessages(page);
  expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
});

test('T2: 真停摆回归（活动路径）——无心跳时 timer 照常到点弹「响应超时」卡 + 清 busy（原保护不回归）', async ({ page }) => {
  await bootApp(page);
  await armSentRecorder(page);
  await armTimer(page);
  const armed = await snapshot(page, ROOT_SID);
  expect(armed.timerArmed).toBe(true);

  // 不发任何心跳：400ms 压缩窗到点 → 超时卡 + busy 清除（活动事件重武装的
  // 是 main.js resetStreamTimeout 变体——渲染超时卡 + clearBusy）。
  await page.waitForTimeout(900);

  const after = await snapshot(page, ROOT_SID);
  expect(after.busy).toBe(false);
  // 注：不断言 timerArmed——既有代码 fire 后残留已触发的 timer id（fire 后
  // 不 delete 键），键存在≠还能再触发；本批不改动该清理语义。
  expect(await page.locator('.row.error .error-card').count()).toBeGreaterThanOrEqual(1);
});

test('T2b: 真停摆回归（send 路径）——发送后零活动，input.js timer 到点发 interrupt 杀 turn（作者症状路径不回归）', async ({ page }) => {
  await bootApp(page);
  await armSentRecorder(page);

  // 真实 UI 发送：input.js send() 会 setBusy + 武装 send 路径 timer
  // （到点 sendWs({type:'interrupt'})——审计 §2.5「前端主动杀 turn」的确切路径）。
  await page.fill('#input', 'long tool call turn');
  await page.click('#send-btn');
  await page.waitForTimeout(150);

  // 立即武装对比：先证明 send 后 timer 已挂（busy=true）
  const armed = await snapshot(page, ROOT_SID);
  expect(armed.busy).toBe(true);

  // 模拟长工具执行且【无】toolHeartbeat（变异面对照：T1 有心跳则永不触发）
  await page.waitForTimeout(900);

  const sent = await sentMessages(page);
  const interrupts = sent.filter((m) => m.type === 'interrupt' && m.sessionId === ROOT_SID);
  expect(interrupts.length).toBeGreaterThanOrEqual(1);
  const after = await snapshot(page, ROOT_SID);
  expect(after.busy).toBe(false);
  expect(await page.locator('.row.error .error-card').count()).toBeGreaterThanOrEqual(1);
});
