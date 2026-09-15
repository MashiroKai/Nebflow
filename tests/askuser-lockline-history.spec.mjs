#!/usr/bin/env node
// askuser-lockline-history.spec.mjs — AskUser 卡片「`->` 锁定行」历史还原判据
// （uiclean 批新增，2026-09-15 · 前端清理微批 D 项）。
//
// 缺口头：`persistence.js askUserAnswerText(msgs, i)` 把「askUser 条目之后的第一条
// 非 injected 用户消息」当作卡片答案。输入框直通退役（`e59ed251d`）之前，卡片 pending
// 期间输入框文本虽也落盘，但**它本身就是被消费的答案**（两侧一致，看不出错）；退役之后
// 输入框文本一律按普通消息落盘（`WebSocketRoutes.dispatchUserText`），于是**答案槽被
// 文本顶掉** ⇒ 刷新后历史还原的锁定行显示的是**末条用户文本**，与工具实收答案不一致。
//
// 本件断言目标态（= 锁定行必须等于工具实收答案）：
//   D0 造卡成功（requestId 现取）
//   D1 pending 期间输入框文本**以普通用户消息入流**、卡片保持 pending（回归面）
//   D2 点选 alpha ⇒ 活卡锁定行 = 实收答案（点选路径行为不变 · 回归面）
//   D3 工具实收 = 点选项（MOCK_DONE(alpha)）
//   D4 **刷新后**历史还原卡片的锁定行 = `-> alpha` 且**不含**输入框文本标记 ← 本批核心判别点
//   D5 页面零错误（既有 e2e 白名单靶点外）
//
// 判据形态 = 双向钉：同一份 spec 跑两侧 —— 未改动树（red）D4 FAIL、改后树（green）全绿。
// 实例由外部 runner 拉起（`.nebflow/tools/20260915_uiclean-e2e.mjs`）；本件只驱动，不 spawn。
//
// Run: BASE_URL=http://127.0.0.1:8096 TOKEN=<auth.json> LABEL=green SHOTS=<dir> \
//      node tests/askuser-lockline-history.spec.mjs

import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8096';
const TOKEN = process.env.TOKEN || '';
const LABEL = process.env.LABEL || 'green';
const SIDE = process.env.SIDE || LABEL;
const SHOTS = process.env.SHOTS || '/tmp/qa-uiclean-shots';
const MARK = process.env.MARKER || `UICLEAN-${SIDE}-${Date.now()}`;
const ASK_WAIT_MS = Number(process.env.ASK_WAIT_MS || 120000);
const PICK = 'alpha';

const results = [];
function check(name, ok, extra = '') {
  results.push({ name, pass: !!ok, extra });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  return !!ok;
}

async function api(path) {
  const res = await fetch(`${BASE}/api${path}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return { status: res.status, json: await res.json().catch(() => null) };
}

async function loadPlaywright() {
  for (const spec of ['playwright', '/opt/homebrew/lib/node_modules/playwright/index.mjs']) {
    try { return await import(spec); } catch { /* try next */ }
  }
  throw new Error('playwright 不可解析（试过裸名与 /opt/homebrew 绝对路径）');
}

const VIEWPORTS = [
  { tag: 'wide', width: 1440, height: 900 },
  { tag: 'mid', width: 1024, height: 768 },
  { tag: 'narrow', width: 390, height: 844 },
];
const THEMES = ['light', 'dark'];

/** 产品自身入口：GET / → #search-btn → onboarding 跳过。 */
async function openApp(browser, theme, viewport) {
  const ctx = await browser.newContext({ baseURL: BASE, colorScheme: theme, viewport });
  await ctx.addInitScript((tok) => {
    try { localStorage.setItem('nebflow_token', tok); } catch { /* quota */ }
  }, TOKEN);
  const page = await ctx.newPage();
  const errs = [];
  const logs = [];
  page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    logs.push(`${m.type()}: ${m.text()}`);
    if (logs.length > 60) logs.shift();
    if (m.type() === 'error') errs.push(`console.error: ${m.text()} ${(m.location()?.url || '')}`);
  });
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
  return { ctx, page, errs, logs };
}

const NOISE = [/\/api\/nf-authcheck/, /console\.error: .*\/api\/canvas-tabs/, /http 404: .*\/api\/canvas-tabs/];
const realErrors = (errs) => errs.filter((e) => !NOISE.some((re) => re.test(e)));

/** DOM 读数（纯取值，判定在调用侧）。多卡时取**最后一张**（= 本轮提问的那张：
  * 历史还原卡在前、本轮卡在后；刷新后同样以最后一张为本轮）。 */
const READ = (m) => {
  const cards = [...document.querySelectorAll('.option-box')];
  const c = cards[cards.length - 1] || null;
  const users = [...document.querySelectorAll('.row.user')].map((r) => r.innerText || '');
  const ai = [...document.querySelectorAll('.row.ai')].map((r) => r.innerText || '');
  return {
    cardCount: cards.length,
    rid: c?.dataset.requestId || null,
    lockLine: c?.querySelector('.option-answer')?.textContent || null,
    lockCount: c ? c.querySelectorAll('.option-answer').length : -1,
    cardDisabled: c ? [...c.querySelectorAll('.option-btn')].every((b) => b.disabled) : null,
    userHasMark: users.some((t) => t.includes(m)),
    queueHasMark: [...document.querySelectorAll('.queue-item')].some((r) => (r.innerText || '').includes(m)),
    aiText: ai.join('\n').slice(-600),
  };
};

async function sendImmediate(page, text) {
  let clicked = false;
  let diag = '';
  for (let attempt = 1; attempt <= 3 && !clicked; attempt++) {
    await page.fill('#input', text);
    await page.press('#input', 'Enter');
    await page.waitForTimeout(1500);
    // 会话 busy ⇒ 只进本地队列；点「立即发送」把文本真正推出去（否则判据无判别力）
    const st = await page.evaluate((mk) => {
      const row = [...document.querySelectorAll('.queue-item')].find((r) => (r.innerText || '').includes(mk));
      const btn = row?.querySelector('.queue-item-btn.immediate');
      if (btn) { btn.click(); return { clicked: true, queued: true }; }
      const bubbled = [...document.querySelectorAll('.row.user')].some((r) => (r.innerText || '').includes(mk));
      return { clicked: false, queued: false, bubbled, inputValue: document.querySelector('#input')?.value || '' };
    }, text);
    clicked = st.clicked;
    diag = `attempt${attempt}=${JSON.stringify(st)}`;
    if (!clicked && !st.bubbled) await page.waitForTimeout(1500);
  }
  await page.waitForTimeout(3000);
  return { clicked, diag };
}

async function runTheme(browser, theme, sid) {
  console.log(`\n── D[${theme}] 锁定行历史还原判据（真 Chromium）──`);
  const { ctx, page, errs, logs } = await openApp(browser, theme, VIEWPORTS[0]);
  const out = { theme };

  // D0：造卡（产品自身入口 = 输入框发 'ask me now'）
  await page.fill('#input', 'ask me now');
  await page.press('#input', 'Enter');
  let rid = null;
  try {
    await page.waitForSelector('.option-box[data-request-id]', { state: 'visible', timeout: ASK_WAIT_MS });
    rid = await page.evaluate(() => document.querySelector('.option-box[data-request-id]')?.dataset.requestId || null);
  } catch (e) {
    check(`D0[${theme}] 卡片渲染到达（真 Chromium）`, false, String(e.message).slice(0, 120));
    await page.screenshot({ path: join(SHOTS, `d0-fail-${theme}.png`) });
    await ctx.close();
    return out;
  }
  out.rid = rid;
  check(`D0[${theme}] 卡片渲染到达（真 Chromium）· requestId 现取`, !!rid, `requestId=${rid}`);

  // D1：pending 期间打字 + 立即发送 ⇒ 文本以普通用户消息入流 ∧ 卡片仍 pending（回归面）
  const mk = `${MARK}-${theme}`;
  const sent = await sendImmediate(page, mk);
  const d1 = await page.evaluate(READ, mk);
  if (!check(`D1[${theme}] pending 期间输入框文本以普通用户消息入流 ∧ 卡片仍 pending（回归面）`,
    (d1.userHasMark || d1.queueHasMark) && d1.cardDisabled === false && d1.lockCount === 0,
    `sentBtn=${sent.clicked} bubble=${d1.userHasMark} queue=${d1.queueHasMark} disabled=${d1.cardDisabled} lockCount=${d1.lockCount} | ${sent.diag}`)) {
    console.log(`[diag:${theme}] console tail = ${JSON.stringify(logs.slice(-8))}`);
  }
  await page.screenshot({ path: join(SHOTS, `d1-pending-${theme}.png`) });

  // D2：点选 alpha + 确认 ⇒ 活卡锁定行 = 实收答案（回归面）
  await page.evaluate(() => {
    const boxes = [...document.querySelectorAll('.option-box[data-request-id]')];
    const box = boxes[boxes.length - 1];
    [...box.querySelectorAll('.option-btn')].find((b) => b.dataset.label === 'alpha')?.click();
    box.querySelector('.option-confirm')?.click();
  });
  await page.waitForTimeout(2500);
  const d2 = await page.evaluate(READ, mk);
  check(
    `D2[${theme}] 点选确认 ⇒ 活卡锁定行 = 实收答案（回归面）`,
    d2.lockLine === `-> ${PICK}`,
    `lockLine=${JSON.stringify(d2.lockLine)}`,
  );
  await page.screenshot({ path: join(SHOTS, `d2-live-locked-${theme}.png`) });

  // D3：工具实收 = 点选项
  let ai = '';
  const t0 = Date.now();
  while (Date.now() - t0 < 60000) {
    const d = await page.evaluate(READ, mk);
    ai = d.aiText;
    if (ai.includes('MOCK_DONE')) break;
    await sleep(1000);
  }
  out.aiText = ai.slice(-200);
  check(
    `D3[${theme}] 工具实收 = 点选项（MOCK_DONE(${PICK})）`,
    ai.includes(`MOCK_DONE(${PICK})`),
    `ai=${JSON.stringify(ai.slice(-160))}`,
  );

  // D4（核心判别点）：刷新 ⇒ 历史还原卡片的锁定行 = 实收答案 ∧ 不含输入框标记
  try {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30000 });
    await page.waitForSelector('.option-box .option-answer', { timeout: 45000 });
    await page.waitForTimeout(1200);
    const d4 = await page.evaluate(READ, mk);
    out.lockLine = d4.lockLine;
    out.cardCount = d4.cardCount;
    check(
      `D4[${theme}] 刷新后历史还原锁定行 = 实收答案（不含输入框文本）`,
      d4.lockLine === `-> ${PICK}`,
      `lockLine=${JSON.stringify(d4.lockLine)} markLeaked=${String(d4.lockLine || '').includes(mk)}`,
    );
  } catch (e) {
    check(`D4[${theme}] 刷新后历史还原锁定行 = 实收答案（不含输入框文本）`, false, String(e.message).slice(0, 140));
  }
  await page.screenshot({ path: join(SHOTS, `d4-after-reload-${theme}.png`) });

  // 余下两视口：只做形态截图 + 卡片未损（不改判据）
  for (const vp of VIEWPORTS.slice(1)) {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await page.waitForTimeout(500);
    await page.screenshot({ path: join(SHOTS, `d5-reload-${theme}-${vp.tag}.png`) });
  }

  const real = realErrors(errs);
  check(`D6[${theme}] 页面零错误（白名单靶点外）`, real.length === 0, real.slice(0, 2).join(' | '));
  out.errsRaw = errs.length; out.errsReal = real.length;
  out.logsTail = logs.slice(-12);
  await ctx.close();
  return out;
}

// ═══════════════════════════════════════════════════════════════════════════
(async () => {
  console.log(`[spec] LABEL=${LABEL} SIDE=${SIDE} BASE=${BASE} MARK=${MARK}`);
  const rs = await api('/sessions');
  const list = rs.json?.sessions || rs.json || [];
  const neb = list.filter((s) => s && s.agentName === 'Nebula');
  const sid = neb[0]?.id || list[0]?.id || null;
  if (!sid) {
    check('会话面可用（root Nebula 会话存在）', false, `status=${rs.status} count=${list.length}`);
    process.exit(1);
  }
  check('会话面可用（root Nebula 会话存在）', true, `sid=${sid} sessions=${list.length} nebula=${neb.length}`);

  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch();
  const version = browser.version();
  console.log(`[spec] Chromium 版本 = ${version}`);
  mkdirSync(SHOTS, { recursive: true });

  const perTheme = {};
  for (const theme of THEMES) {
    perTheme[theme] = await runTheme(browser, theme, sid);
  }
  await browser.close();

  const fails = results.filter((r) => !r.pass);
  console.log(`\n[spec] LABEL=${LABEL} total=${results.length} pass=${results.length - fails.length} fail=${fails.length}`);
  if (fails.length) console.log('[spec] FAILED: ' + fails.map((f) => f.name).join(' ; '));
  console.log(`[spec] JSON ${JSON.stringify({ label: LABEL, mark: MARK, chromium: version, perTheme, fails: fails.map((f) => f.name) })}`);
  process.exit(fails.length ? 1 : 0);
})().catch((e) => {
  console.error('[spec] FATAL ' + (e && e.stack ? e.stack : String(e)));
  process.exit(3);
});
