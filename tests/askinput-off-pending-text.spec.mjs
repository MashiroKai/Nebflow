#!/usr/bin/env node
// askinput-off-pending-text.spec.mjs — AskUser「输入框直通」关闭的**行为级**判据
// （本批新增，2026-09-15 · 台账 #211 缺口面 A）。
//
// 为什么需要本件：`src/test/scala/nebflow/gateway/UserTextGateSpec.scala` 头部
// 自陈「本门只证明**符号面/调用点面**已摘除……『关闭后输入框文本确实走普通消息
// 通道、且不丢消息』的**行为读数**归 #515 联合轮」。本件补的就是那份行为读数：
// 驱动**真实隔离 gateway 实例**（非 jsdom / 非静态 mock）走产品自身协议面与自身
// 输入框，断言作者目标态（09-14 08:38 裁定）：
//   「AskUserQuestion 卡片 pending 期间，聊天输入框文本一律按普通用户消息处理
//     （正常渲染入流、禁静默丢弃/禁吞消息）；卡片只认点选回答；点选路径行为不变。」
//
// 判据形态 = **双向钉**：本件只断言**目标态**，同一份 spec 跑两侧——
//   · 现树（e59ed251d 等价面）⇒ PASS（绿）
//   · 未改动树（前树，直通腿在载）⇒ 命名判据 FAIL + 打出旧行为读数（红）
//
// 🔴 **前端队列面的坑（本件第二轮修正，实测所得）**：卡片 pending 期间在输入框
// 打字回车，前端**不会立即发帧**——会话 busy ⇒ 文本进本地「排队中」条
// （`chatQueue.js`），要点「立即发送」（`.queue-item-btn.immediate`）才发。
// ⇒ 只断言「打字后」会**两侧都绿**（后端压根没收到文本 ⇒ 无从判别）。
// 故 B2 必须以「立即发送」把文本真正推出去，判据才有判别力（red 侧由此锁定）。
//
// ── 断言面 ──
//   A0 造卡成功（requestId 现取）
//   A1 文本不被当卡片答案：观察窗内零 `askUserAnswered` 帧
//   A2 卡片未被文本消费：文本之后点选仍命中卡片 → 工具结果 = 点选项
//   A3 工具结果不含输入框文本标记（文本没成为 answers）
//   B0 真 Chromium 卡片渲染到达（requestId 现取）
//   B1 打字 ⇒ 文本不丢（队列条/气泡可见）∧ 卡片仍 pending 未锁定   [theme × viewport]
//   B2 立即发送 ⇒ 文本以**普通用户消息**入流（.row.user 含标记）∧ 卡片仍 pending [同]
//   B3 刷新 ⇒ 卡片重放（同一 requestId，仍 pending）
//   B4 点选确认 ⇒ 卡片锁定（点选路径行为不变）
//   B5 页面零错误（既有 e2e 白名单靶点外）
//
// ── Run（实例由外部 runner 拉起；本件只驱动，不 spawn）──
//   BASE_URL=http://127.0.0.1:8094 TOKEN=<auth.json> LABEL=green \
//   SHOTS=/path/shots node tests/askinput-off-pending-text.spec.mjs
//
// 先例：tests/askuser-refresh-survive.spec.mjs（同族，隔离实例 + mock LLM；本件
//       openApp / WS 嗅探 / onboarding 序列沿用其形态）、
//       scripts/verify-askuser-passthrough.cjs（同族，浏览器面）。

import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8094';
const TOKEN = process.env.TOKEN || '';
const LABEL = process.env.LABEL || 'green';
const SHOTS = process.env.SHOTS || '/tmp/qa-ask211-shots';
const MARKER = process.env.MARKER || `ASKTEXT-${LABEL}-${Date.now()}`;
const ASK_PROMPT = process.env.ASK_PROMPT || 'ask me now';
const OBS_MS = Number(process.env.OBS_MS || 15000);
const ASK_WAIT_MS = Number(process.env.ASK_WAIT_MS || 90000);
const CLICK_OPTION = 'alpha';
const WS_URL = `${BASE.replace(/^http/, 'ws')}/ws?token=${encodeURIComponent(TOKEN)}`;

const results = [];
function check(name, ok, extra = '') {
  results.push({ name, pass: !!ok, extra });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  return !!ok;
}

// ── 裸 WS 客户端（node ≥22 原生 WebSocket；捕帧 + 谓词等待）──
class WsClient {
  constructor(label) { this.label = label; this.frames = []; this.ws = null; }
  async connect(timeoutMs = 20000) {
    this.ws = new WebSocket(WS_URL);
    this.ws.onmessage = (e) => { try { this.frames.push(JSON.parse(e.data)); } catch { /* binary */ } };
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error(`${this.label}: ws open timeout`)), timeoutMs);
      this.ws.onopen = () => { clearTimeout(t); res(); };
      this.ws.onerror = () => { clearTimeout(t); rej(new Error(`${this.label}: ws error`)); };
    });
    return this;
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  close() { try { this.ws.close(); } catch { /* already closed */ } }
  blob() { return JSON.stringify(this.frames); }
  async waitFor(pred, ms, label) {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const hit = this.frames.find(pred);
      if (hit) return hit;
      await sleep(250);
    }
    throw new Error(`${this.label}: waitFor(${label}) timeout ${ms}ms`);
  }
  async waitForOrNull(pred, ms) { try { return await this.waitFor(pred, ms, 'soft'); } catch { return null; } }
}

async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

/** REST turn：生产 `handleUserText(source="rest-turn")` 全路径，用于**造卡**。
 *  前树 `probesPassthrough("rest-turn")===false` ⇒ REST 永不直通，两侧造卡同形。
 *  卡片挂起时该请求一直挂着 ⇒ fire-and-forget，不 await。 */
function fireTurn(sid, content) {
  const p = fetch(`${BASE}/api/sessions/${sid}/turn`, {
    method: 'POST',
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: JSON.stringify({ content, timeoutSec: 300 }),
  });
  p.then(() => {}).catch(() => {});
  return p;
}

async function rootSessionId() {
  const r = await api('/sessions');
  const list = r.json?.sessions || r.json || [];
  const neb = list.filter((s) => s && s.agentName === 'Nebula');
  return { status: r.status, sid: neb[0]?.id || list[0]?.id || null, count: list.length, neb: neb.length };
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase A — 协议面双向钉（裸 WS，真实 gateway 栈）
// ═══════════════════════════════════════════════════════════════════════════
async function phaseA(sid) {
  console.log(`\n── Phase A · 协议面双向钉（LABEL=${LABEL}）──`);
  const ws = await new WsClient('A').connect();
  await sleep(1200);

  fireTurn(sid, ASK_PROMPT);
  let ask;
  try {
    ask = await ws.waitFor((f) => f.type === 'askUser' && !!f.requestId, ASK_WAIT_MS, 'askUser');
  } catch (e) {
    check('A0 造卡：pending askUser 帧到达（requestId 现取）', false, String(e.message));
    ws.close();
    return null;
  }
  const rid = ask.requestId;
  check('A0 造卡：pending askUser 帧到达（requestId 现取）', true, `requestId=${rid}`);

  // 输入框文本（**逐字用产品自身的 immediateInput 帧形**，input.js:867）
  ws.send({ type: 'immediateInput', content: MARKER, sessionId: sid });
  console.log(`[A] 输入框文本已投：type=immediateInput content=${MARKER} sessionId=${sid}`);

  // A1：观察窗内是否出现 askUserAnswered（前树：文本即答案 ⇒ 广播该帧并消费卡片）
  await sleep(OBS_MS);
  const answeredFrame = ws.frames.find((f) => f.type === 'askUserAnswered');
  check(
    'A1 文本不被当卡片答案：观察窗内零 askUserAnswered 帧',
    !answeredFrame,
    `actual_answered=${!!answeredFrame}${answeredFrame ? ` via=${answeredFrame.via} requestId=${answeredFrame.requestId}` : ''}`,
  );

  // A2+A3：文本之后点选仍命中卡片（= 卡片未被文本消费）∧ 工具结果 = 点选项
  ws.send({ type: 'askUserAnswer', sessionId: sid, requestId: rid, answers: [CLICK_OPTION] });
  const doneFrame = await ws.waitForOrNull((f) => JSON.stringify(f).includes('MOCK_DONE'), 60000);
  const doneBlob = doneFrame ? JSON.stringify(doneFrame) : '';
  check(
    'A2 卡片未被文本消费：文本之后点选仍命中 → 工具结果 = 点选项',
    !!doneFrame && doneBlob.includes(CLICK_OPTION),
    `tool_result=${doneBlob.slice(0, 220)}`,
  );
  check(
    'A3 工具结果不含输入框文本标记（文本没成为 answers）',
    !ws.blob().includes(`MOCK_DONE(${MARKER}`),
    `text_became_answer=${ws.blob().includes(`MOCK_DONE(${MARKER}`)}`,
  );

  ws.close();
  return { rid, framesA: ws.frames.length };
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase B — 真渲染自检（真 Chromium · 亮/暗双主题 × 三端视口）
// ═══════════════════════════════════════════════════════════════════════════
const VIEWPORTS = [
  { tag: 'wide', width: 1440, height: 900 },
  { tag: 'mid', width: 1024, height: 768 },
  { tag: 'narrow', width: 390, height: 844 },
];
const THEMES = ['light', 'dark'];

async function loadPlaywright() {
  for (const spec of ['playwright', '/opt/homebrew/lib/node_modules/playwright/index.mjs']) {
    try { return await import(spec); } catch { /* try next */ }
  }
  throw new Error('playwright 不可解析（试过裸名与 /opt/homebrew 绝对路径）');
}

/** 开应用 + 等就绪（序列沿用 tests/askuser-refresh-survive / e2e-askuser-source-label
  * 既有形态：localStorage token → goto('/') → #search-btn → onboarding 跳过）。 */
async function openApp(browser, theme, viewport) {
  const ctx = await browser.newContext({ baseURL: BASE, colorScheme: theme, viewport });
  await ctx.addInitScript((tok) => {
    try { localStorage.setItem('nebflow_token', tok); } catch { /* quota */ }
    window.__ask211 = [];
    const OrigWS = window.WebSocket;
    window.WebSocket = class extends OrigWS {
      constructor(...args) {
        super(...args);
        this.addEventListener('message', (ev) => {
          try {
            const m = JSON.parse(ev.data);
            if (m && ['askUser', 'askUserAnswered', 'askUserClosed'].includes(m.type)) window.__ask211.push(m);
          } catch { /* non-JSON */ }
        });
      }
    };
  }, TOKEN);
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errs.push(`console.error: ${m.text()} ${(m.location()?.url || '')}`); });
  page.on('requestfailed', (r) => errs.push(`requestfailed: ${r.url()} ${r.failure()?.errorText || ''}`));
  page.on('response', (r) => { if (r.status() >= 400) errs.push(`http ${r.status()}: ${r.url()}`); });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30000 });
  const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 4000 }).then(() => true).catch(() => false);
  if (ob) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no');
    await page.waitForSelector('.onboarding-overlay', { state: 'detached', timeout: 5000 }).catch(() => {});
  }
  await page.waitForTimeout(600);
  return { ctx, page, errs };
}

// 既有 e2e 家族的白名单靶点（fresh fixture home 下 by design 触发）——其余判 FAIL
const NOISE = [/\/api\/nf-authcheck/, /console\.error: .*\/api\/canvas-tabs/, /http 404: .*\/api\/canvas-tabs/];
const realErrors = (errs) => errs.filter((e) => !NOISE.some((re) => re.test(e)));

/** 页面内取卡片/气泡状态。 */
const READ_DOM = (m) => {
  const users = [...document.querySelectorAll('.row.user')].map((r) => r.innerText || '');
  const box = document.querySelector('.option-box[data-request-id]');
  const btns = box ? [...box.querySelectorAll('.option-btn')] : [];
  const qitems = [...document.querySelectorAll('.queue-item')].map((r) => r.innerText || '');
  return {
    userBubbles: users,
    userBubbleHasMarker: users.some((t) => t.includes(m)),
    userBubbleCount: users.length,
    queueHasMarker: qitems.some((t) => t.includes(m)),
    queueLen: qitems.length,
    cardPresent: !!box,
    cardRid: box?.dataset.requestId || null,
    cardDisabled: btns.length ? btns.every((b) => b.disabled) : null,
    answerDivs: box ? box.querySelectorAll('.option-answer').length : -1,
    askFrames: (window.__ask211 || []).map((f) => f.type + (f.via ? ':' + f.via : '')),
  };
};

async function phaseB(sid) {
  console.log(`\n── Phase B · 真渲染自检（Chromium · 双主题 × 三端视口）──`);
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch();
  const version = browser.version();
  console.log(`[B] Chromium 版本 = ${version}`);
  mkdirSync(SHOTS, { recursive: true });

  const perTheme = {};
  for (const theme of THEMES) {
    let app;
    try {
      app = await openApp(browser, theme, VIEWPORTS[0]);
    } catch (e) {
      check(`B0[${theme}] 应用就绪（真 Chromium）`, false, String(e.message).slice(0, 180));
      continue;
    }
    const { ctx, page, errs } = app;

    fireTurn(sid, ASK_PROMPT);
    let rid = null;
    try {
      await page.waitForSelector('.option-box[data-request-id]', { state: 'visible', timeout: ASK_WAIT_MS });
      rid = await page.evaluate(() => document.querySelector('.option-box[data-request-id]')?.dataset.requestId || null);
    } catch (e) {
      const diag = await page.evaluate(READ_DOM, 'zzz-none').catch(() => ({}));
      check(`B0[${theme}] 卡片渲染到达（真 Chromium）`, false, `${String(e.message).slice(0, 110)} diag=${JSON.stringify(diag)}`);
      await ctx.close();
      continue;
    }
    check(`B0[${theme}] 卡片渲染到达（真 Chromium）· requestId 现取`, !!rid, `requestId=${rid}`);
    const themeState = { rid, viewports: {} };

    for (const vp of VIEWPORTS) {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.emulateMedia({ colorScheme: theme });
      await page.waitForTimeout(400);

      const marker = `${MARKER}-DOM-${theme}-${vp.tag}`;
      await page.fill('#input', marker);
      await page.press('#input', 'Enter');
      await page.waitForTimeout(1200);

      // B1：打字后文本**不丢**（队列条或气泡可见）∧ 卡片仍 pending 未锁定
      const d1 = await page.evaluate(READ_DOM, marker);
      const b1 = (d1.queueHasMarker || d1.userBubbleHasMarker) && d1.cardPresent && d1.cardDisabled === false && d1.answerDivs === 0;
      check(
        `B1[${theme}/${vp.tag} ${vp.width}x${vp.height}] 打字 ⇒ 文本不丢 ∧ 卡片仍 pending 未锁定`,
        b1,
        `queueMarker=${d1.queueHasMarker} bubble=${d1.userBubbleHasMarker} card=${d1.cardPresent} disabled=${d1.cardDisabled} answerDivs=${d1.answerDivs}`,
      );
      await page.screenshot({ path: join(SHOTS, `typed-${theme}-${vp.tag}.png`) });

      // B2：**立即发送**把文本真正推出去（前端 busy ⇒ 打字只进本地队列；不发帧则两侧
      // 都「绿」而毫无判别力 —— 见文件头「前端队列面的坑」）
      const hadBtn = await page.evaluate(() => {
        const row = [...document.querySelectorAll('.queue-item')].find((r) => (r.innerText || '').includes('ASKTEXT'));
        const btn = row?.querySelector('.queue-item-btn.immediate');
        if (btn) { btn.click(); return true; }
        return false;
      });
      await page.waitForTimeout(3000);
      const d2 = await page.evaluate(READ_DOM, marker);
      const b2 = d2.userBubbleHasMarker && d2.cardPresent && d2.cardDisabled === false && d2.answerDivs === 0;
      check(
        `B2[${theme}/${vp.tag}] 立即发送 ⇒ 文本以普通用户消息入流 ∧ 卡片仍 pending`,
        b2,
        `sentBtn=${hadBtn} bubble=${d2.userBubbleHasMarker} userBubbles=${d2.userBubbleCount} card=${d2.cardPresent} disabled=${d2.cardDisabled} answerDivs=${d2.answerDivs} askFrames=${d2.askFrames.join(',')}`,
      );
      themeState.viewports[vp.tag] = { b1, b2, rid: d2.cardRid };
    }

    // B3：刷新 ⇒ 卡片重放（同一 requestId，仍 pending）
    try {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.option-box[data-request-id]', { state: 'visible', timeout: 45000 });
      const ridAfter = await page.evaluate(() => document.querySelector('.option-box[data-request-id]')?.dataset.requestId || null);
      check(`B3[${theme}] 刷新 ⇒ 卡片重放且 requestId 不变（仍 pending）`, ridAfter === rid, `before=${rid} after=${ridAfter}`);
    } catch (e) {
      check(`B3[${theme}] 刷新 ⇒ 卡片重放且 requestId 不变（仍 pending）`, false, String(e.message).slice(0, 140));
    }

    // B4：点选确认 ⇒ 卡片锁定（点选路径行为不变）
    try {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.evaluate(() => document.querySelector('.option-box[data-request-id]').querySelector('.option-btn').click());
      await page.waitForTimeout(400);
      await page.evaluate(() => document.querySelector('.option-box[data-request-id]').querySelector('.option-confirm').click());
      await page.waitForTimeout(2000);
      const locked = await page.evaluate(() => document.querySelector('.option-box[data-request-id]')?.querySelectorAll('.option-answer').length ?? -1);
      check(`B4[${theme}] 点选确认 ⇒ 卡片锁定（.option-answer 出现）`, locked >= 1, `answerDivs=${locked}`);
      await page.screenshot({ path: join(SHOTS, `card-answered-${theme}.png`) });
    } catch (e) {
      check(`B4[${theme}] 点选确认 ⇒ 卡片锁定`, false, String(e.message).slice(0, 140));
    }

    const real = realErrors(errs);
    check(`B5[${theme}] 页面零错误（白名单靶点外）`, real.length === 0, real.slice(0, 2).join(' | '));
    perTheme[theme] = { ...themeState, errsRaw: errs.length, errsReal: real.length };
    await ctx.close();
  }
  await browser.close();
  return { version, perTheme };
}

// ═══════════════════════════════════════════════════════════════════════════
(async () => {
  console.log(`[spec] LABEL=${LABEL} BASE=${BASE} MARKER=${MARKER}`);
  const rs = await rootSessionId();
  if (!rs.sid) {
    check('会话面可用（root Nebula 会话存在）', false, `status=${rs.status} count=${rs.count}`);
    process.exit(1);
  }
  check('会话面可用（root Nebula 会话存在）', true, `sid=${rs.sid} sessions=${rs.count} nebula=${rs.neb}`);

  const a = await phaseA(rs.sid);
  const b = await phaseB(rs.sid);

  const fails = results.filter((r) => !r.pass);
  console.log(`\n[spec] LABEL=${LABEL} total=${results.length} pass=${results.length - fails.length} fail=${fails.length}`);
  if (fails.length) console.log('[spec] FAILED: ' + fails.map((f) => f.name).join(' ; '));
  console.log(`[spec] JSON ${JSON.stringify({ label: LABEL, marker: MARKER, chromium: b.version, phaseA: a, perTheme: b.perTheme, fails: fails.map((f) => f.name) })}`);
  process.exit(fails.length ? 1 : 0);
})().catch((e) => {
  console.error('[spec] FATAL ' + (e && e.stack ? e.stack : String(e)));
  process.exit(3);
});
