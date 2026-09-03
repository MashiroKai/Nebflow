// askuser-refresh-survive.spec.mjs — 刷新存活验收（2026-09-03，#43 同任务域补充 case）。
//
// 作者指定验收：pending AskUser 在浏览器刷新 / WS 重连后恢复可答。
//   T1 发起 → 刷新（page.reload）→ WS 重连 → hub 重发 → 卡片恢复可见可答
//      → 点选项 → askUserAnswer(带 requestId) 送达 → 工具返回（MOCK_DONE 气泡）
//      → 亮暗双主题截图落盘；
//   T2 输入框直通路径刷新后同样验证 → askUserAnswered{via:'chat-input'} 锁卡
//      → 工具返回携带直通文本。
//
// 真实全环：隔离 Nebflow 实例（BASE_URL/TOKEN env 注入，绝非 8080 宿主）+
// OpenAI 兼容 mock LLM（tests/fixtures/askuser-refresh/mock-llm.mjs，状态机：
// 'ask me now' → AskUserQuestion 工具调用；role=tool 收尾 → MOCK_DONE；其余 → ok）。
// 事故形态垫厚：ask 前经 REST turn 端点（真实 handleUserText 路径）垫 30 轮 ok，
// ask pending 期间经 callbacks/inject 注入 55 条 ExternalEvent（#43 事故形态：
// 注入气泡排在 pending ask 之后）→ 刷新后 askUser 条目落在首屏 50 条之外，
// 历史还原链不重建卡片——服务端重发是唯一恢复路径（验红基线即此形态）。
//
// Run: node node_modules/@playwright/test/cli.js test <repo>/tests/askuser-refresh-survive.spec.mjs

import { test, expect } from '@playwright/test';
import { homedir } from 'node:os';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:18923';
const TOKEN = process.env.TOKEN ?? '';
const SHOT_DIR = join(homedir(), '.nebflow', 'docs', 'Nebflow');
const PAD_TURNS = 30;
const INJECTS = 55;

async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.json();
}

/** REST turn 端点 = 生产 handleUserText 全路径（source=rest-turn，永不碰 pending 卡）。 */
async function restTurn(sid, content) {
  return api(`/sessions/${sid}/turn`, 'POST', { content, timeoutSec: 120 });
}

/** 页面级 WebSocket 捕获：包装 window.WebSocket 暴露应用连接 + 收发帧记录。 */
function attachWsCapture(page) {
  page.addInitScript(() => {
    const Orig = window.WebSocket;
    window.__nfFrames = { in: [], out: [] };
    function Wrapped(...args) {
      const ws = new Orig(...args);
      window.__nf_ws = ws;
      window.__nf_ws_ready = new Promise((res) => ws.addEventListener('open', () => res(), { once: true }));
      ws.addEventListener('message', (e) => {
        try { window.__nfFrames.in.push(JSON.parse(e.data)); } catch { /* binary */ }
      });
      const origSend = ws.send.bind(ws);
      ws.send = (data) => {
        try { window.__nfFrames.out.push(JSON.parse(data)); } catch { /* not json */ }
        origSend(data);
      };
      return ws;
    }
    Wrapped.prototype = Orig.prototype;
    Wrapped.CONNECTING = 0; Wrapped.OPEN = 1; Wrapped.CLOSING = 2; Wrapped.CLOSED = 3;
    window.WebSocket = Wrapped;
  });
}

/** 等待某方向出现匹配帧（match 为 {字段: 值} 平面匹配）。谓词在页面上下文执行，
 *  故用可序列化的 match 对象而非闭包。 */
async function waitForFrame(page, dir, match, timeoutMs = 15000) {
  await page.waitForFunction(
    ([d, m]) =>
      (window.__nfFrames?.[d] ?? []).some((f) => Object.entries(m).every(([k, v]) => f[k] === v)),
    [dir, match],
    { timeout: timeoutMs },
  );
}

async function chatReady(page, expectSid) {
  await page.waitForFunction(() => !!window.__nf_ws_ready, null, { timeout: 20000 });
  // 活动会话已选定：sessionList(activeId) → resetChat → 该会话的 historyPage 到达
  await page.waitForFunction(
    (s) => (window.__nfFrames?.in ?? []).some((f) => f.type === 'historyPage' && f.sessionId === s),
    expectSid,
    { timeout: 20000 },
  );
}

async function sendUserText(page, text) {
  await page.fill('#input', text);
  await page.keyboard.press('Enter');
}

async function clickOption(page, label) {
  await page.locator(`.option-box .option-btn[data-label="${label}"]`).first().click();
}

async function waitDoneBubble(page, needle, timeoutMs = 45000) {
  await page.waitForFunction(
    (n) => {
      const bubbles = [...document.querySelectorAll('#chat .bubble.ai')];
      return bubbles.some((b) => (b.textContent || '').includes(n));
    },
    needle,
    { timeout: timeoutMs },
  );
}

/** 发起 AskUser：经 REST turn 端点 fire-and-forget（同 handleUserText 生产分发路径）。
 *  不走浏览器输入框发起的原因：浏览器真实用户回合会注入 time-reminder，与
 *  tool-call 执行存在既有 agent-core 竞态（偶发丢弃工具调用，见 gateway 日志
 *  18:09:24 run：无 InteractionRequest、turn 以 "ok" 收尾）——与本任务域无关，
 *  REST 路径（rest-turn 源，不注入 time reminder）无此竞态且不受 30s 内同步
 *  turn gate 影响（T1 答复完成 → done → gate 释放，T2 复用）。 */
function fireAsk() {
  api(`/sessions/${sid}/turn`, 'POST', { content: 'ask me now', timeoutSec: 600 }).catch(() => {
    /* turn 挂起直至回答完成——有意不等待 */
  });
}

async function waitLiveCard(page, timeoutMs = 30000) {
  await page.waitForSelector('.option-box[data-request-id]', { timeout: timeoutMs });
  return page.locator('.option-box[data-request-id]').first().getAttribute('data-request-id');
}

let sid;

test.beforeAll(async () => {
  test.setTimeout(300000); // 30 轮 padding 串行 REST turn
  const meta = await api('/sessions', 'POST', { name: `e2e-refresh-${Date.now()}` });
  sid = meta.id;
  // 30 轮 padding（每轮 user+ai 两条），把后续 askUser 条目推进历史深处
  for (let i = 0; i < PAD_TURNS; i++) {
    const r = await restTurn(sid, `pad-${i}`);
    if (r.status !== 'completed') throw new Error(`pad turn ${i} failed: ${JSON.stringify(r).slice(0, 200)}`);
  }
});

test.describe('AskUser pending 刷新存活（作者验收）', () => {
  test.use({ colorScheme: 'dark' });

  test('T1 发起 → 刷新 → hub 重发恢复卡片 → 点选项 → 工具返回 + 双主题截图', async ({ page }) => {
    test.setTimeout(240000);
    attachWsCapture(page);
    await page.goto(`${BASE}/?token=${TOKEN}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => !!window.__nf_ws_ready, null, { timeout: 20000 });

    // —— 激活测试会话（服务端 switchSession → 重载后 sessionList.activeId 生效）——
    await page.evaluate(async (s) => {
      await window.__nf_ws_ready;
      window.__nf_ws.send(JSON.stringify({ type: 'switchSession', sessionId: s }));
    }, sid);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await chatReady(page, sid);

    // —— 发起 AskUser（mock 状态机返回两问工具调用）——
    fireAsk();
    const liveRid = await waitLiveCard(page);
    expect(liveRid, '活卡应带 requestId').toBeTruthy();

    // pending 期间注入 55 条 ExternalEvent（#43 事故形态：注入排在 ask 之后）
    for (let i = 0; i < INJECTS; i++) {
      await api('/callbacks/inject', 'POST', {
        agent: 'Nebula', session: sid, message: `inject-${i}`, source: 'e2e-refresh-test',
      });
    }

    // —— 刷新：WS 重连 → historyPage（ask 条目在页外）→ hub 重发恢复 ——
    await page.reload({ waitUntil: 'domcontentloaded' });
    await chatReady(page, sid);
    await page.waitForSelector('.option-box[data-request-id]', { timeout: 20000 });

    // 重发帧证据：inbound askUser{replayed:true, requestId, items, sessionId}
    await waitForFrame(page, 'in', { type: 'askUser', replayed: true });
    const replayFrame = await page.evaluate(() => {
      const withRid = window.__nfFrames.in.filter((f) => f.type === 'askUser' && f.replayed === true);
      return withRid[withRid.length - 1];
    });
    expect(replayFrame.requestId, '重发载荷带 requestId（答案链重绑定）').toBe(liveRid);
    expect(Array.isArray(replayFrame.items) && replayFrame.items.length === 2, '两问内容完整重发').toBe(true);
    expect(replayFrame.items[0].question).toContain('E2E 刷新存活验证');

    // —— 亮暗双主题截图：恢复后的 pending 卡（按钮可用、未作答）——
    const btnEnabled = await page.locator('.option-box .option-btn').first().isEnabled();
    expect(btnEnabled, '恢复卡选项可点（可答）').toBe(true);
    mkdirSync(SHOT_DIR, { recursive: true });
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForTimeout(600);
    await page.screenshot({ path: join(SHOT_DIR, '20260903_askuser-refresh-survive-dark.png'), fullPage: false });
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForTimeout(600);
    await page.screenshot({ path: join(SHOT_DIR, '20260903_askuser-refresh-survive-light.png'), fullPage: false });

    // 恢复后的卡片可交互：选项可点
    await clickOption(page, 'alpha');
    await clickOption(page, 'yes');
    await page.locator('.option-box .option-confirm').first().click();

    // 回答送达：outbound askUserAnswer 带 requestId；卡片锁定
    await waitForFrame(page, 'out', { type: 'askUserAnswer', requestId: liveRid });
    await page.waitForSelector('.option-box .option-answer', { timeout: 10000 });

    // 工具正常返回：pending 解除 → LLM 下一轮 → MOCK_DONE 气泡含所选值
    await waitDoneBubble(page, 'MOCK_DONE');
    await waitDoneBubble(page, 'alpha');
    // 等 55 条注入排空（#43 排队语义）且 turn 彻底结束——释放 /turn 的同步
    // gate，T2 的 fireAsk 才能受理（否则 409 Conflict）。
    await expect(page.locator('#send-btn')).toBeVisible({ timeout: 90000 });
  });

  test('T2 直通路径：发起 → 刷新 → 重发恢复 → 输入框回答 → 锁卡 + 工具返回', async ({ page }) => {
    test.setTimeout(180000);
    attachWsCapture(page);
    await page.goto(`${BASE}/?token=${TOKEN}`, { waitUntil: 'domcontentloaded' });
    await chatReady(page, sid);

    fireAsk();
    const liveRid = await waitLiveCard(page);
    expect(liveRid).toBeTruthy();

    await page.reload({ waitUntil: 'domcontentloaded' });
    await chatReady(page, sid);
    const box = page.locator('.option-box[data-request-id]');
    await box.waitFor({ timeout: 20000 });
    expect(await box.getAttribute('data-request-id'), '直通路径同样靠重发拿回 requestId 绑定').toBe(liveRid);

    // 输入框直通：文本被后端消费为答案 → askUserAnswered{via:'chat-input'} → 精确锁卡
    await sendUserText(page, '直通答案文本-xyz');
    await waitForFrame(page, 'in', { type: 'askUserAnswered', via: 'chat-input', requestId: liveRid });
    await page.waitForSelector('.option-box[data-request-id] .option-answer', { timeout: 10000 });

    // 工具返回携带直通文本
    await waitDoneBubble(page, '直通答案文本-xyz', 45000);
  });
});
