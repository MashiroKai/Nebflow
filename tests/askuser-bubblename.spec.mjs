// askuser-bubblename.spec.mjs — askuser-bubblename 批（作者 2026-10-06 19:43 令
// B-1/B-2/B-3）验收钉。
//
// 病根（B-1 实录定案，.nebflow/evidence/20261006_askuser-bubblename-x1/）：
//   agentList 载荷的 displayName 是**回退毒化**的 —— 网关
//   WsSessionChatHandlers.handleListAgents 发 `a.displayName.getOrElse(a.name)`，
//   未起名 home 也恒带 `displayName:"Nebula"` ⇒ rootName.js isRootNamed() 的
//   agentsData 腿在**每次真实启动都读真** ⇒ 56a44587e 的 N1/N2/N4 全部门禁失效
//   （作者截图二：题泡上「NEBULA」标签；实录 f1 帧同面）。
//
// 修法（本 spec 逐腿钉死）：
//   ① isRootNamed() 撤掉毒化腿，改读 agent.json 探针（rootName.js
//      initRootNameProbe：GET agents/Nebula/agent.json — 服务端 displayNameForRoot
//      的同一权威文件；404/缺键 = 未起名，非空键 = 已起名；判**键的存在**，
//      从不比值 —— 用户真起名「Nebula」不被误隐，N4 顺修语义保住）。
//   ② B-2：onboarding 气泡一律不挂发送者名（simBubble 删挂载点，命名前后一律）。
//   ③ B-3：题泡题文（.ob-ghost-seat .ob-body）取得与对话流同材质的 AI 泡面，
//      .ask-card 浮层卡不回退。
//
// 驱动真实模块（main.js / sidebar.js / onboarding.js / rootName.js / state.js
// 全真实 boot），静态 route + routeWebSocket 供给帧序 —— 零真实实例，永不触 :8080。
//
// Run: npx playwright test tests/askuser-bubblename.spec.mjs

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

const ROOT_SID = 'ob-bubblename-root-session';
const CHOSEN_NAME = '星尘试号';

/** Boot the REAL app against static routes + a scripted WS. `agentJson`:
 *  null → agents/Nebula/agent.json 404s (fresh home); a string → 200 with
 *  {"displayName": <agentJson>} (the probe's named leg). `agentsDisplayName`
 *  is what the agentList frame carries — 'Nebula' reproduces the gateway's
 *  getOrElse fallback verbatim (the poison). */
async function bootApp(page, { agentJson = null, agentsDisplayName = 'Nebula', onboarding = 'pending' } = {}) {
  await page.emulateMedia({ reducedMotion: 'reduce' }); // typewriter fills instantly — no skip clicks
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // The probe URL: 404 (fresh home) or the named fixture — handled HERE
    // (a separately registered route would lose to this catch-all: playwright
    // matches in reverse registration order).
    if (p === '/agents/Nebula/agent.json') {
      if (agentJson === null) return route.fulfill({ status: 404, body: 'not found' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: agentJson }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    // 帧序纪律（onboarding-name-header 同款）：sessionList 先行，configData
    // 押后到 getHistory 应答完 —— 引导气泡只落「已落定」的 chat DOM。
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    const sendAgents = () => ws.send(JSON.stringify({
      type: 'agentList',
      agents: [{ name: 'Nebula', description: '', displayName: agentsDisplayName, avatar: null, tools: ['*'] }],
    }));
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding,
    }));
    let historySettled = false;
    let configPending = false;
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
        historySettled = true;
        if (configPending) { configPending = false; sendConfig(); }
      } else if (msg.type === 'getConfig') {
        if (historySettled) sendConfig(); else configPending = true;
      } else if (msg.type === 'listAgents') {
        sendAgents();
      } else if (msg.type === 'setOnboardingAnswers') {
        ws.send(JSON.stringify({ type: 'onboardingAnswersSaved', count: 1 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  // 探针落定（initRootNameProbe 是 fire-and-forget —— 判据读数前必须等它）
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.rootAgentFileNamed !== null && s.rootAgentFileNamed !== undefined;
  }, null, { timeout: 8000 });
}

const readJudgement = (page) => page.evaluate(async () => {
  const s = (await import('/js/state.js')).default;
  const { isRootNamed, rootDisplayName } = await import('/js/rootName.js');
  return { file: s.rootAgentFileNamed, named: isRootNamed(), display: rootDisplayName() };
});

// ── A · B-1 主钉：回退毒化的 agentsData + 无 agent.json ⇒ 未起名 ──
// 改前树此腿读真（病根本体）；起名前全流零名字面：Header 空、气泡零标签。
test('A · B-1：毒化 displayName + 404 探针 ⇒ 未起名；起名前全流零名字面', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, { agentJson: null, agentsDisplayName: 'Nebula', onboarding: 'pending' });
  await page.waitForSelector('.ask-card[data-q="name"]', { timeout: 15000 });

  const j = await readJudgement(page);
  expect(j.file).toBe(false);
  expect(j.named).toBe(false);

  // 起名前实录帧同面（before/f1 的三处 NEBULA 字样 → 零）：
  expect(await page.locator('#session-name').textContent()).toBe('');
  expect(await page.locator('#chat .ask-user-source').count()).toBe(0);
  const flowNebula = await page.evaluate(() => {
    let n = 0;
    const w = document.createTreeWalker(document.getElementById('main'), NodeFilter.SHOW_TEXT);
    while (w.nextNode()) if (/nebula/i.test(w.currentNode.textContent || '')) n++;
    return n;
  });
  expect(flowNebula).toBe(0);
  expect(pageErrors).toEqual([]);
});

// ── B · B-2：起名后气泡也不挂名；Header 显名 ──
test('B · B-2：起名 ⇒ Header 显名、气泡一律零发送者名', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, { agentJson: null, agentsDisplayName: 'Nebula', onboarding: 'pending' });
  await page.waitForSelector('.ask-card[data-q="name"]', { timeout: 15000 });

  const input = page.locator('.ask-card[data-q="name"] .free-row input.option-input');
  await input.fill(CHOSEN_NAME);
  await page.locator('.ask-card[data-q="name"] .free-row button').first().click();
  await page.waitForFunction((n) => document.getElementById('session-name')?.textContent === n,
    CHOSEN_NAME, { timeout: 5000 });

  // 气泡一律不挂名（B-2 语义升级：命名前后一律）——后续 follow-up 泡也无标签
  await page.waitForTimeout(600);
  expect(await page.locator('#chat .ask-user-source').count()).toBe(0);
  const j = await readJudgement(page);
  expect(j.named).toBe(true);          // in-session override
  expect(j.display).toBe(CHOSEN_NAME);
  expect(pageErrors).toEqual([]);
});

// ── C · N4 顺修保真：用户真起名「Nebula」⇒ 仍判已起名（判键不比值）──
test('C · 字面名 Nebula 已落盘 ⇒ 探针判已起名（不误隐）', async ({ page }) => {
  await bootApp(page, { agentJson: 'Nebula', agentsDisplayName: 'Nebula', onboarding: 'done' });
  const j = await readJudgement(page);
  expect(j.file).toBe(true);
  expect(j.named).toBe(true);
});

// ── D · B-1 回归钉：毒化行 + 无文件 ⇒ 未起名（改前树此腿读真 = 病根）──
test('D · 回退毒化行 + 404 探针 ⇒ 未起名（改前读真的病根钉）', async ({ page }) => {
  await bootApp(page, { agentJson: null, agentsDisplayName: 'Nebula', onboarding: 'done' });
  const j = await readJudgement(page);
  expect(j.file).toBe(false);
  expect(j.named).toBe(false);
});

// ── E · 探针命名腿端到端：agent.json 有名 ⇒ 判已起名、Header 显名 ──
// agentsData 行与文件一致（真实命名 home 的形态：displayNameForRoot 读文件，
// agentList 帧如实携带）——判据与显示面各走各的读点，双双落名。
test('E · agent.json displayName ⇒ 探针判已起名、Header 显示文件名', async ({ page }) => {
  await bootApp(page, { agentJson: CHOSEN_NAME, agentsDisplayName: CHOSEN_NAME, onboarding: 'done' });
  await page.waitForFunction((n) => document.getElementById('session-name')?.textContent === n,
    CHOSEN_NAME, { timeout: 5000 });
  const j = await readJudgement(page);
  expect(j.file).toBe(true);
  expect(j.named).toBe(true);
  expect(j.display).toBe(CHOSEN_NAME);
});

// ── F · B-3 材质钉：题文 = 对话流 AI 泡材质；.ask-card 浮层卡不回退 ──
test('F · B-3：题文取得 AI 泡材质，浮层卡原样', async ({ page }) => {
  await bootApp(page, { agentJson: null, agentsDisplayName: 'Nebula', onboarding: 'pending' });
  await page.waitForSelector('.ask-card[data-q="name"]', { timeout: 15000 });
  const m = await page.evaluate(() => {
    const body = document.querySelector('.bubble.ai.ob-ghost-seat .ob-body');
    if (!body) return null;
    const cs = getComputedStyle(body);
    const card = document.querySelector('.ask-card');
    const ccs = card ? getComputedStyle(card) : null;
    const seat = document.querySelector('.bubble.ai.ob-ghost-seat');
    const scs = seat ? getComputedStyle(seat) : null;
    return {
      bodyBg: cs.backgroundColor,
      bodyBorder: cs.borderTopWidth,
      bodyRadius: cs.borderTopLeftRadius,
      bodyShadow: cs.boxShadow,
      seatBg: scs ? scs.backgroundColor : null,
      seatPadding: scs ? scs.padding : null,
      cardShadow: ccs ? ccs.boxShadow : null,
      cardRadius: ccs ? ccs.borderTopLeftRadius : null,
    };
  });
  expect(m).not.toBeNull();
  // 题文泡面 = .bubble.ai 原值（rgba(255,255,255,…)/白底按主题；断言「非透明 + 有边 + 圆角 + 有影」）
  expect(m.bodyBg).not.toBe('rgba(0, 0, 0, 0)');
  expect(m.bodyBorder).toBe('1px');
  expect(m.bodyRadius).toBe('16px');
  expect(m.bodyShadow).not.toBe('none');
  // 外泡保持 ghost（零面零内距）——卡照旧直接浮在纸上
  expect(m.seatBg).toBe('rgba(0, 0, 0, 0)');
  expect(m.seatPadding).toBe('0px');
  // 浮层卡维持 56a44587e 裁定（渐变面 + 柔影 + 16px 圆角）
  expect(m.cardShadow).not.toBe('none');
  expect(m.cardRadius).toBe('16px');
});
