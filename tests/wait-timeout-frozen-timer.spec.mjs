// wait-timeout-frozen-timer.spec.mjs — R4-a 冻结漏网堵截（wait-timeout-fix，
// 审计 20260903 Q2-A）。
//
// 根因：子代理冻结（agentFrozen）只标记 bgAgent 条目 + applyLocalFreeze（输入
// 栏 CSS），不清 root 会话的 sessionBusyTimeouts。root turn 挂在
// outstandingSubagentResults 屏障上：busy 保持、零活动事件 → 10.5min
// （streamTimeoutMs 600s + 30s）计时器到点 → sendWs({type:'interrupt'}) 杀掉
// root turn + 弹「响应超时」卡——冻结跨小时（如 09:00-12:00 段）100% 复现。
//
// 修复（对齐 'frozen' 处理器的 F8/F4 契约）：agentFrozen 清除
// msg.rootSessionId || msg.sessionId 对应会话的 sessionBusyTimeouts；恢复后由
// 活动事件重武装（resumed → activity re-arms，既有契约）。
//
// Self-contained: serves src/main/resources/web via route interception and
// mocks the WS boot handshake — no backend, no sbt.
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/wait-timeout-frozen-timer.spec.mjs
//
// 验红变异：还原 main.js agentFrozen 处理器中的计时器清除块 → T1 红（计时器
// 仍挂 → 到点必发 interrupt + 超时卡）；恢复后绿。

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
const SUB_AGENT_ID = 'delegate-abc123';

/** Minimal boot: configData + sessionList with an active root session. */
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

/** Drive an inbound WS event through the real handler chain. */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

function timerOf(page, sid) {
  return page.evaluate(async (key) => {
    const s = (await import('/js/state.js')).default;
    return s.sessionBusyTimeouts[key] != null;
  }, sid);
}

/** 生产时序：turn 开始 setBusy 先于活动事件 → 活动事件武装计时器。首个
 * thinkingDelta 只置 busy（resetStreamTimeout 对非 busy 会话早退），第二个
 * 才真正武装——与生产 turn 中活动事件流一致。 */
async function armTimer(page) {
  await inject(page, { type: 'thinkingDelta', sessionId: ROOT_SID, delta: 'working…' });
  await inject(page, { type: 'thinkingDelta', sessionId: ROOT_SID, delta: 'still working…' });
}

test('R4-a T1: 子代理 agentFrozen 清除 root 会话的杀 turn 计时器（冻结跨小时不被误杀）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 1. root 会话进入 busy：活动事件武装 stream timeout（生产链路＝LLM 流/
  //    工具事件 → resetStreamTimeout）。
  await armTimer(page);
  expect(await timerOf(page, ROOT_SID)).toBe(true);

  // 2. 子代理冻结（root 忙等屏障）：root 的计时器必须被清除——否则
  //    streamTimeoutMs+30s 到点发 interrupt 杀 root turn（Q2-A 误杀链）。
  await inject(page, {
    type: 'agentFrozen',
    sessionId: SUB_AGENT_ID, rootSessionId: ROOT_SID,
    agentId: SUB_AGENT_ID, resumeAt: null, reason: 'schedule',
  });
  expect(await timerOf(page, ROOT_SID)).toBe(false);

  expect(pageErrors).toEqual([]);
});

test('R4-a T2: 冻结结束后活动事件重武装计时器（F8/F4 既有契约保持：resumed → activity re-arms）', async ({ page }) => {
  await bootApp(page);

  await armTimer(page);
  expect(await timerOf(page, ROOT_SID)).toBe(true);

  await inject(page, {
    type: 'agentFrozen',
    sessionId: SUB_AGENT_ID, rootSessionId: ROOT_SID,
    agentId: SUB_AGENT_ID, resumeAt: null, reason: 'schedule',
  });
  expect(await timerOf(page, ROOT_SID)).toBe(false);

  // 出冻结段（agentResumed）：root 无需特殊处理——下一个活动事件照常重武装
  // （与 'frozen'/'resumed' 处理器的既有契约一致）。
  await inject(page, {
    type: 'agentResumed',
    sessionId: SUB_AGENT_ID, rootSessionId: ROOT_SID,
    agentId: SUB_AGENT_ID,
  });
  await armTimer(page);
  expect(await timerOf(page, ROOT_SID)).toBe(true);
});

test('R4-a T3: 未知会话的 agentFrozen 不产生幻影键、不误清他人计时器', async ({ page }) => {
  await bootApp(page);

  await armTimer(page);
  expect(await timerOf(page, ROOT_SID)).toBe(true);

  await inject(page, {
    type: 'agentFrozen',
    sessionId: 'ghost-agent', rootSessionId: 'ghost-root',
    agentId: 'ghost-agent', resumeAt: null, reason: 'schedule',
  });
  // root 的计时器不受无关冻结影响；ghost 键不被创建。
  expect(await timerOf(page, ROOT_SID)).toBe(true);
  expect(await timerOf(page, 'ghost-root')).toBe(false);
});
