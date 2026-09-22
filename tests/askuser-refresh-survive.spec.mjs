// askuser-refresh-survive.spec.mjs — 刷新存活验收（2026-09-03，#43 同任务域补充 case）。
//
// 作者指定验收：pending AskUser 在浏览器刷新 / WS 重连后恢复可答。
//   T1 发起 → 刷新（page.reload）→ WS 重连 → hub 重发 → 卡片恢复可见可答
//      → 点选项 → askUserAnswer(带 requestId) 送达 → 工具返回（MOCK_DONE 气泡）
//      → 亮暗双主题截图落盘；
//   T2 输入框文本（直通退役后）：刷新后经输入框的文本按普通用户消息入流（不吞），
//      卡片保持 pending、仍须点选作答（uiclean 批 2026-09-15 对齐；退役前的
//      askUserAnswered{via:'chat-input'} 锁卡断言已删——新行为覆盖见
//      tests/askinput-off-pending-text.spec.mjs，两者互补：本件保留「刷新之后」维度）。
//
// 真实全环：隔离 Nebflow 实例（BASE_URL/TOKEN env 注入，绝非 8080 宿主）+
// OpenAI 兼容 mock LLM（tests/fixtures/askuser-refresh/mock-llm.mjs，状态机：
// 'ask me now' → AskUserQuestion 工具调用；role=tool 收尾 → MOCK_DONE；其余 → ok）。
// 事故形态垫厚：ask 前经 REST turn 端点（真实 handleUserText 路径）垫 30 轮 ok，
// ask pending 期间经 callbacks/inject 注入 55 条 ExternalEvent（#43 事故形态：
// 注入气泡排在 pending ask 之后）→ 刷新后 askUser 条目落在首屏 50 条之外，
// 历史还原链不重建卡片——服务端重发是唯一恢复路径（验红基线即此形态）。
//
// Run（home 由夹具 provisioner 装配，2026-09-15 e2efix 批补齐）：
//   1) 装配隔离 home：NEBFLOW_HOME_DIR=<home> MOCK_PORT=<mp> node tests/fixtures/askuser-refresh/fixture-home.mjs --provision
//      （🔴 只写 <home> 之下；连跑两次 manifest sha 一致；装/拆后零残留）
//   2) 起该 home 的隔离实例（端口 ≠ 8080；装配面 = 宿主实载工件 + 本支 web 树前置，见 `.nebflow/tools/20260915_hostcp-*`）
//   3) BASE_URL=http://127.0.0.1:<port> TOKEN=$(cat <home>/auth.json) QA_SHOTS=<截图目录> \
//        node node_modules/@playwright/test/cli.js test <repo>/tests/askuser-refresh-survive.spec.mjs
//   🔴 `QA_SHOTS` 默认 = <repo>/.nebflow/evidence/20260915_e2eask（写根白名单内；禁落宿主 ~/.nebflow/docs/**）

import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { guardWrites } from '../scripts/lib/writeguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:18923';
const TOKEN = process.env.TOKEN ?? '';
// 截图输出目录 = **受控参数**（`QA_SHOTS`）；默认 = 本 checkout 的 `.nebflow/evidence/20260915_e2eask/`。
// 🔴 2026-09-15 e2efix 批：原值 `join(homedir(), '.nebflow', 'docs', 'Nebflow')` 是**硬编码宿主绝对路径**
//    （正是件 1 同族缺陷的宿主 `docs/**` 写入面）⇒ 已删，改走白名单根。
const SHOT_DIR = process.env.QA_SHOTS || join(REPO, '.nebflow', 'evidence', '20260915_e2eask');
// W1 写根断言（模块加载期即 fail-fast，先于任何 browser/网络动作）：越界 → 非零退出
guardWrites([SHOT_DIR], { repoRoot: REPO, label: 'askuser-refresh-survive' });
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

  test('T2 输入框文本（直通退役后）：发起 → 刷新 → 重发恢复 → 文本按普通消息入流不吞 ∧ 卡片仍可答', async ({ page }) => {
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
    expect(await box.getAttribute('data-request-id'), '重发恢复同样靠重放帧拿回 requestId 绑定').toBe(liveRid);

    // ── 直通退役（2026-09-14 作者令 / 落地 e59ed251d；uiclean 批 2026-09-15 对齐）──
    // 旧断言（已删行为）：文本被 hub 消费为答案 → askUserAnswered{via:'chat-input'}
    //   → 精确锁卡 → 工具返回携带该文本。
    // 新断言（目标态）：pending 期间回车**不发帧**（会话 busy ⇒ 文本进本地「排队中」条，
    //   不吞不丢）→ 点「立即发送」后以**普通用户消息**入流 → 卡片仍 pending（未锁）→
    //   引擎**零** askUserAnswered 帧 → 仍须在卡片上作答，工具返回携带**点选项**。
    // 新行为的逐视口覆盖见 tests/askinput-off-pending-text.spec.mjs（0cb416b0）；
    // 本 case 保留其唯一维度：**刷新之后**再走输入框。
    const MARK_T2 = '直通退役后普通文本-xyz';
    await sendUserText(page, MARK_T2);
    await page.waitForTimeout(1200);
    // ① 不吞：文本落在本地「排队中」条（会话 busy ⇒ 尚未发帧）
    const queued = await page.locator('.queue-item', { hasText: MARK_T2 }).count();
    expect(queued, 'pending 期间回车 ⇒ 文本进本地队列，不得静默丢弃').toBeGreaterThan(0);
    // ② 「立即发送」把它真的推出去 ⇒ 以普通用户消息入流
    const pushed = await page.evaluate((mk) => {
      const row = [...document.querySelectorAll('.queue-item')].find((r) => (r.innerText || '').includes(mk));
      const btn = row?.querySelector('.queue-item-btn.immediate');
      if (!btn) return false;
      btn.click();
      return true;
    }, MARK_T2);
    expect(pushed, '「立即发送」控件可点（把排队文本推出）').toBe(true);
    await page.waitForFunction(
      (mk) => [...document.querySelectorAll('.row.user')].some((r) => (r.innerText || '').includes(mk)),
      MARK_T2,
      { timeout: 15000 },
    );
    // ③ 卡片仍 pending（文本没成为答案）∧ 零 askUserAnswered 帧
    await page.waitForTimeout(3000);
    expect(await page.locator('.option-box[data-request-id] .option-answer').count(), '文本不得消费卡片').toBe(0);
    const answeredFrames = await page.evaluate(() => (window.__nfFrames?.in ?? []).filter((f) => f.type === 'askUserAnswered'));
    expect(answeredFrames, '退役后引擎不得再广播 askUserAnswered').toHaveLength(0);

    // ④ 点选作答仍正常：出站带 requestId，工具返回携带点选项
    await clickOption(page, 'alpha');
    await clickOption(page, 'yes');
    await page.locator('.option-box .option-confirm').first().click();
    await waitForFrame(page, 'out', { type: 'askUserAnswer', requestId: liveRid });
    await waitDoneBubble(page, 'MOCK_DONE');
    await waitDoneBubble(page, 'alpha');
  });
});

// ============================================================
// 双开缺陷批（2026-09-21 chain-askuserdup）· T3：**非阻塞提问**刷新后的同 id 双卡
//
// 定谳（.nebflow/reports/20260921_181620_askuserdup-arch__chain-askuserdup.md §1.5）：
//   历史恢复腿给**仍 pending** 的卡补一行假 `.option-answer`（`chat.js
//   renderAskUserHistory` 改前无条件追加）⇒ 重放腿的去重判据（「卡上有没有作答行」）
//   被击穿 ⇒ 不删既有卡 ⇒ 重放卡挂成第二张。首卡 = 历史卡（`lockOptionBox` 置灰 +
//   全 disabled + 无 `data-request-id`）⇒ 「总是第一张失效」+ 引擎关不掉它。
//   触发前置 = `isAskUserPending` 为假，而它要求「askUser 是首屏历史里最后一条真人
//   行」；**非阻塞提问**之后 agent 继续本 turn 产出 ai 行（`AskUserQuestionTool:400-417`
//   发起即返回）⇒ 该条件在其真实用法下恒为假 ⇒ 每次刷新/切会话/重连都复现。
//
// 本 case 用 mock LLM 的 'ask nb now' 触发腿**真实制造**该形态：
//   非阻塞提问（卡仍在 hub pending）→ 同 turn 续跑落 `ai` 行 → 刷新 → historyPage
//   （askUser 条目在首屏内，历史腿真的画卡）→ 重放帧 ⇒ 现场 DOM 读数。
// 双向钉（同一份断言跑两侧，`ASKUSER_DUP_ARM` 切换期望值；两侧实例由外部 runner
// 拉起，前端 web 树不同：red = 改前基线树，green = 本支）：
//   red  （改前代码）：`.option-box` == 2、`.resolved` == 1、`.option-answer` == 1、
//                     只有 1 张卡可 id 寻址、首卡（.resolved）控件全 disabled；
//   green（改后代码）：`.option-box` == 1、`.resolved` == 0、`.option-answer` == 0、
//                     `.option-answer-pending` == 0（历史孪生卡已被 id 优先回收）、
//                     卡可答（出站 askUserAnswer 带 requestId）+ 待办镜像仍 1。
// ============================================================
const ARM = process.env.ASKUSER_DUP_ARM ?? 'green';

async function readDupCards(page) {
  return page.evaluate(() => {
    const boxes = [...document.querySelectorAll('#chat .option-box')];
    return {
      total: boxes.length,
      withRid: boxes.filter((b) => b.dataset.requestId).length,
      rids: boxes.map((b) => b.dataset.requestId || ''),
      resolved: boxes.filter((b) => b.classList.contains('resolved')).length,
      answered: boxes.filter((b) => b.querySelector('.option-answer')).length,
      pendingNote: boxes.filter((b) => b.querySelector('.option-answer-pending')).length,
      firstCardDisabled: boxes.length > 0
        ? [...boxes[0].querySelectorAll('.option-btn')].every((b) => b.disabled)
        : null,
      lastPendingSnapshot: (() => {
        const snaps = (window.__nfFrames?.in ?? []).filter((f) => f.type === 'pendingAsksSnapshot');
        const last = snaps[snaps.length - 1];
        return last ? (last.asks || []).length : null;
      })(),
    };
  });
}

test.describe('T3 AskUser 双卡（非阻塞提问 → 刷新）· chain-askuserdup 全链红验', () => {
  test.use({ colorScheme: 'dark' });

  test('T3 非阻塞提问 + 后置 ai 行 → 刷新 ⇒ 现场 DOM 读数（green: 恰 1 张活卡）', async ({ page }) => {
    test.setTimeout(240000);
    attachWsCapture(page);
    // 独立会话（无 padding ⇒ askUser 条目落在首屏历史内 —— 历史恢复腿才会真的画卡）
    const meta = await api('/sessions', 'POST', { name: `e2e-dupcards-${ARM}-${Date.now()}`, agentName: 'Nebula' });
    const nsid = meta.id;
    await page.goto(`${BASE}/?token=${TOKEN}`, { waitUntil: 'domcontentloaded' });
    await page.evaluate(async (s) => {
      await window.__nf_ws_ready;
      window.__nf_ws.send(JSON.stringify({ type: 'switchSession', sessionId: s }));
    }, nsid);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await chatReady(page, nsid);

    // —— 非阻塞提问（mock 'ask nb now' ⇒ AskUserQuestion(mode=non-blocking)）——
    api(`/sessions/${nsid}/turn`, 'POST', { content: 'ask nb now', timeoutSec: 600 }).catch(() => {
      /* turn 正常收尾；有意外时由下方断言暴露 */
    });
    const nbRid = await waitLiveCard(page);
    expect(nbRid, '非阻塞卡也应带 requestId').toBeTruthy();
    // 非阻塞 = 发起即返回 ⇒ 同 turn 续跑一条 ai 行（双开的前置形态）
    await waitDoneBubble(page, 'MOCK_DONE');
    const preRefresh = await readDupCards(page);
    expect(preRefresh.total, '刷新前：恰一张 live 卡').toBe(1);
    expect(preRefresh.answered, '刷新前：无假作答行').toBe(0);

    // —— 刷新：historyPage（含 askUser 条目）+ 重放帧 ——
    await page.reload({ waitUntil: 'domcontentloaded' });
    await chatReady(page, nsid);
    await page.waitForSelector('.option-box[data-request-id]', { timeout: 20000 });
    await waitForFrame(page, 'in', { type: 'askUser', replayed: true });
    await page.waitForTimeout(400);

    const cards = await readDupCards(page);
    // 现场 DOM 读数（补定谳件 §6.1 缺的那张快照）：每卡的 .resolved / .option-answer /
    // data-request-id 计数 + 首卡控件 disabled 读数
    console.log(`[T3:${ARM}]`, JSON.stringify(cards));

    if (ARM === 'red') {
      // 改前代码：同 id 两张卡 —— 首卡（历史恢复）锁定 + 假作答行 + 无 id；次卡（重放）活
      expect(cards.total, '改前：同 id 两张卡').toBe(2);
      expect(cards.resolved, '改前：首卡被 lockOptionBox 锁死').toBe(1);
      expect(cards.answered, '改前：首卡带一行假作答行').toBe(1);
      expect(cards.pendingNote, '改前：无显式待定标注').toBe(0);
      expect(cards.withRid, '改前：只有重放卡可 id 寻址（历史卡无 requestId）').toBe(1);
      expect(cards.firstCardDisabled, '改前：「总是第一个失效」= 首卡控件全 disabled').toBe(true);
      await page.screenshot({ path: join(SHOT_DIR, `20260921_askuserdup-T3-red-${nsid}.png`), fullPage: false });
    } else {
      // 改后代码：id 优先无条件回收 ⇒ 恰一张卡，且是重放活卡
      expect(cards.total, '改后：恰一张卡').toBe(1);
      expect(cards.resolved, '改后：幸存卡是活卡（未锁）').toBe(0);
      expect(cards.answered, '改后：无假作答行').toBe(0);
      expect(cards.pendingNote, '改后：历史孪生卡已被回收（不留待定卡）').toBe(0);
      expect(cards.rids, '改后：唯一卡可 id 寻址').toEqual([nbRid]);
      expect(cards.lastPendingSnapshot, '待办条镜像仍为 1（未回归到镜像面）').toBe(1);

      // 复绿正向：卡可答 → askUserAnswer{requestId} 送达 → 卡片锁定
      await page.screenshot({ path: join(SHOT_DIR, `20260921_askuserdup-T3-green-${nsid}.png`), fullPage: false });
      await clickOption(page, 'alpha');
      await clickOption(page, 'yes');
      await page.locator('.option-box .option-confirm').first().click();
      await waitForFrame(page, 'out', { type: 'askUserAnswer', requestId: nbRid });
      await page.waitForSelector('.option-box .option-answer', { timeout: 10000 });
      const afterAnswer = await readDupCards(page);
      expect(afterAnswer.total).toBe(1);
      expect(afterAnswer.answered, '作答后该卡带答案行').toBe(1);
      expect(afterAnswer.resolved).toBe(1);
    }
  });
});
