// uidefect-askcard-focus.spec.mjs — 钉②（件②：新 AskUser 卡到达 ⇒ 夺走卡片输入框
// 焦点）双向红绿读数。
//
// 症状权威 = 作者原话逐字（2026-09-15 现场报）：「2.是不清楚什么原因，应该是来了新的
// AskUser卡片，反正会打断用户在AskUser卡片的输入，导致光标从卡片的输入框，到了消息
// 的输入框。」
//
// 真凶（取证见 .nebflow/evidence/20260915_uidefect/forensics.md，focusin 栈探针实测）：
//   chat.js:275   clearBusy() 里 `if (input) input.focus()` 无条件把焦点盖到主输入框；
//   调用链：clearBusyFor(main.js:350) ← onMessage('done')(main.js:1073)
//           以及 onMessage('sessionBusy'){busy:false}(main.js:2738)。
//   引擎侧：AgentActor.scala:3028-3059 finishTurn 成对发 Done + sessionBusy{false}，
//   而 InteractionHub.scala:145-152 在发卡片前先发 roundComplete ⇒ 卡片与 turn 终态帧
//   落在同一时间窗 ⇒ 用户看到「新卡到达 ⇒ 光标跳走」。
//
// 本 spec 自包含：route 拦截直接读真源码 web 树（`UIDEFECT_WEB_ROOT` 可指向改前树做
// 「转红」复现），/ws 用 routeWebSocket 走真 handler 链（真 askUser 帧 → 真卡片渲染 →
// 真键盘输入）。**不起网关、不占端口、不碰 8080、不依赖 sbt**。亮/暗双主题各跑一遍。
//
// Run（改后绿，默认）:
//   node tests/uidefect-askcard-focus.spec.mjs
// Run（改前红，同一 spec、同一读数口径）:
//   UIDEFECT_WEB_ROOT=/tmp/nb-uidefect-base/src/main/resources/web PIN_EXPECT=red \
//     node tests/uidefect-askcard-focus.spec.mjs
//
// 读数口径：`activeElement` 精确 selector + 卡片输入框值 + 主输入框值（键盘输入的实际
// 落点）＋ 夺焦时的 focusin 调用栈（file:line）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.UIDEFECT_WEB_ROOT
  ? normalize(process.env.UIDEFECT_WEB_ROOT)
  : join(REPO, 'src', 'main', 'resources', 'web');
const EXPECT = process.env.PIN_EXPECT ?? 'green';
const SHOTS = process.env.UIDEFECT_SHOTS ?? join(tmpdir(), 'uidefect-shots', `pin2-${EXPECT}`);
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'pin2-root-session';
const ITEMS = [{ question: '选哪个方案？', options: ['方案 A', '方案 B'], allowOther: true }];

let failures = 0;
const readings = {};
function ok(id, cond, detail) {
  console.log(`  ${cond ? 'PASS' : 'FAIL'}  [${id}] ${detail}`);
  if (!cond) failures++;
}

async function openPage(browser, colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'spec-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'serverConfig', streamTimeoutMs: 600000, version: 'spec', thinking: null, workSchedule: null }));
    const sendConfigLegacy = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'pin2', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendConfigLegacy(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(250);
  // focusin 栈探针（夺焦时给出 file:line）
  await page.evaluate(() => {
    window.__focusLog = [];
    const sel = (el) => {
      if (!el || !el.tagName) return String(el);
      if (el.id) return '#' + el.id;
      if (el.classList && el.classList.length) return el.tagName.toLowerCase() + '.' + [...el.classList].join('.');
      return el.tagName.toLowerCase();
    };
    document.addEventListener('focusin', (e) => {
      window.__focusLog.push({ to: sel(e.target), stack: (new Error().stack || '').split('\n').slice(1, 5).join(' | ') });
    }, true);
  });
  return { ctx, page, pageErrors };
}

const inj = (page, o) => page.evaluate(async (obj) => {
  const s = (await import('/js/state.js')).default;
  s.ws.onmessage({ data: JSON.stringify(obj) });
}, o);

const activeSel = (page) => page.evaluate(() => {
  const ae = document.activeElement;
  if (!ae) return 'null';
  if (ae.id) return '#' + ae.id;
  if (ae.classList && ae.classList.length) return ae.tagName.toLowerCase() + '.' + [...ae.classList].join('.');
  return ae.tagName.toLowerCase();
});

const card = (requestId) => ({ type: 'askUser', sessionId: SID, items: ITEMS, agentName: 'Nebula', requestId });

/** 真渲染一张卡并聚焦它的「其他」文本域（= 用户在卡片输入框打字的生产路径）。 */
async function renderCardAndFocus(page, requestId) {
  await inj(page, card(requestId));
  const box = page.locator('.option-box[data-request-id="' + requestId + '"]');
  await box.locator('.option-btn').last().click();          // 「其他」→ 露出文本域
  const ta = box.locator('.option-custom-input');
  await ta.click();
  return ta;
}

async function scenario(browser, { id, colorScheme, setup, drive, probeSel, focusTarget }) {
  const { ctx, page, pageErrors } = await openPage(browser, colorScheme);
  await inj(page, { type: 'sessionBusy', sessionId: SID, busy: true });
  const handle = await setup(page);
  if (focusTarget) await page.locator(focusTarget).click();
  await page.evaluate(() => { window.__focusLog = []; });
  const before = await activeSel(page);
  await drive(page);
  await page.waitForTimeout(300);
  const after = await activeSel(page);
  await page.keyboard.type('XYZ');
  // 卡片输入框用 requestId 限定的稳定 selector 复读（懒 locator 会指向新卡）
  const cardVal = probeSel ? await page.locator(probeSel).inputValue().catch(() => 'ERR') : null;
  const mainVal = await page.locator('#input').inputValue().catch(() => 'ERR');
  const focusLog = await page.evaluate(() => window.__focusLog);
  const r = { id, theme: colorScheme, before, after, cardValue: cardVal, mainValue: mainVal, focusLog, pageErrors };
  readings[`${id}@${colorScheme}`] = { ...r, handle: handle ? 'card-textarea' : 'none' };
  await page.screenshot({ path: join(SHOTS, `${id}-${colorScheme}.png`) });
  await ctx.close();
  return r;
}

const browser = await chromium.launch();
try {
  for (const theme of ['light', 'dark']) {
    // ── S1 主钉：卡片输入框打字中 ⇒ 新卡到达（生产窗：卡片 + turn 终态帧）──
    {
      console.log(`\n── S1-card-typing-then-new-card @ ${theme} | PIN_EXPECT=${EXPECT} ──`);
      const r = await scenario(browser, {
        id: 'S1', colorScheme: theme,
        setup: async (page) => { const ta = await renderCardAndFocus(page, 'req-s1-a'); await page.keyboard.type('abc'); return ta; },
        drive: async (page) => { await inj(page, card('req-s1-b')); await inj(page, { type: 'done', sessionId: SID }); },
        probeSel: '.option-box[data-request-id="req-s1-a"] .option-custom-input',
      });
      console.log(`  activeElement: before=${r.before} after=${r.after}  cardValue=${JSON.stringify(r.cardValue)} mainValue=${JSON.stringify(r.mainValue)}`);
      ok('S1-pageerrors', r.pageErrors.length === 0, `pageerrors=${r.pageErrors.length}`);
      if (EXPECT === 'red') {
        ok('S1-R-steal', r.after === '#input', `after=${r.after} (期望 #input)`);
        ok('S1-R-typing-moved', r.mainValue === 'XYZ' && r.cardValue === 'abc',
          `cardValue=${JSON.stringify(r.cardValue)} mainValue=${JSON.stringify(r.mainValue)} (续打落到主输入框、卡片值停在 abc)`);
      } else {
        ok('S1-G-focus-held', r.after === 'textarea.option-custom-input', `after=${r.after}`);
        ok('S1-G-typing-uninterrupted', r.cardValue === 'abcXYZ' && r.mainValue === '',
          `cardValue=${JSON.stringify(r.cardValue)} mainValue=${JSON.stringify(r.mainValue)} (值=两次拼接，主输入框零输入)`);
      }
      console.log(`  focusLog=${JSON.stringify(r.focusLog)}`);
    }

    // ── S2 连打两卡 + 打字连续性（两次生产窗注入，值必须逐次续接）──
    {
      console.log(`\n── S2-two-cards-in-a-row @ ${theme} | PIN_EXPECT=${EXPECT} ──`);
      const { ctx, page, pageErrors } = await openPage(browser, theme);
      await inj(page, { type: 'sessionBusy', sessionId: SID, busy: true });
      const ta = await renderCardAndFocus(page, 'req-s2-a');
      await page.keyboard.type('abc');
      const afterType1 = await activeSel(page);
      await inj(page, card('req-s2-b'));
      await inj(page, { type: 'sessionBusy', sessionId: SID, busy: false });
      await page.waitForTimeout(120);
      const afterCard2 = await activeSel(page);
      await page.keyboard.type('DEF');
      const val1 = await ta.inputValue();
      // 第三张卡（真 done）
      await inj(page, card('req-s2-c'));
      await inj(page, { type: 'done', sessionId: SID });
      await page.waitForTimeout(120);
      const afterCard3 = await activeSel(page);
      await page.keyboard.type('GHI');
      const val2 = await ta.inputValue();
      const mainVal = await page.locator('#input').inputValue();
      const focusLog = await page.evaluate(() => window.__focusLog);
      console.log(`  afterType1=${afterType1} afterCard2=${afterCard2} afterCard3=${afterCard3} ` +
        `cardValue=${JSON.stringify(val2)} mainValue=${JSON.stringify(mainVal)}`);
      console.log(`  focusLog=${JSON.stringify(focusLog)}`);
      ok('S2-pageerrors', pageErrors.length === 0, `pageerrors=${pageErrors.length}`);
      readings[`S2@${theme}`] = { id: 'S2', theme, afterType1, afterCard2, afterCard3, cardValueAfter2: val1, cardValueFinal: val2, mainValue: mainVal, focusLog, pageErrors };
      if (EXPECT === 'red') {
        ok('S2-R-steal-at-card2', afterCard2 === '#input', `afterCard2=${afterCard2}`);
        ok('S2-R-typing-interrupted', val2 === 'abc' && mainVal === 'DEFGHI', `cardValue=${JSON.stringify(val2)} mainValue=${JSON.stringify(mainVal)}`);
      } else {
        ok('S2-G-focus-held', afterCard2 === 'textarea.option-custom-input' && afterCard3 === 'textarea.option-custom-input',
          `afterCard2=${afterCard2} afterCard3=${afterCard3}`);
        ok('S2-G-typing-uninterrupted', val2 === 'abcDEFGHI' && mainVal === '',
          `cardValue=${JSON.stringify(val2)} mainValue=${JSON.stringify(mainVal)}`);
      }
      await page.screenshot({ path: join(SHOTS, `S2-two-cards-${theme}.png`) });
      await ctx.close();
    }

    // ── S3 回归：无进行中输入（焦点在 body）⇒ 新卡到达须**保持**既有聚焦主输入框行为 ──
    {
      console.log(`\n── S3-regression-no-input-in-progress @ ${theme} ──`);
      const r = await scenario(browser, {
        id: 'S3', colorScheme: theme,
        setup: async (page) => {
          await renderCardAndFocus(page, 'req-s3-a');
          // 「无进行中的输入」= 真把焦点从可写元素上摘掉（body.focus() 不会 blur）
          await page.evaluate(() => { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); document.body.focus(); });
          return null;
        },
        drive: async (page) => { await inj(page, card('req-s3-b')); await inj(page, { type: 'done', sessionId: SID }); },
      });
      console.log(`  activeElement: before=${r.before} after=${r.after}`);
      ok('S3-pageerrors', r.pageErrors.length === 0, `pageerrors=${r.pageErrors.length}`);
      ok('S3-precondition-no-writable-focus', r.before.startsWith('body'), `before=${r.before} (前置：焦点已离开可写元素)`);
      ok('S3-focus-follows-card', r.after === '#input', `after=${r.after} (无进行中输入 ⇒ 既有行为: 聚焦主输入框)`);
    }

    // ── S4 边界：焦点本就在主输入框（guard 不得破坏该路径）──
    {
      console.log(`\n── S4-boundary-focus-already-composer @ ${theme} ──`);
      const r = await scenario(browser, {
        id: 'S4', colorScheme: theme,
        setup: async (page) => { await renderCardAndFocus(page, 'req-s4-a'); return null; },
        drive: async (page) => { await inj(page, card('req-s4-b')); await inj(page, { type: 'done', sessionId: SID }); },
        focusTarget: '#input',
      });
      console.log(`  activeElement: before=${r.before} after=${r.after} mainValue=${JSON.stringify(r.mainValue)}`);
      ok('S4-pageerrors', r.pageErrors.length === 0, `pageerrors=${r.pageErrors.length}`);
      ok('S4-composer-kept', r.after === '#input' && r.mainValue === 'XYZ', `after=${r.after} mainValue=${JSON.stringify(r.mainValue)}`);
    }

    // ── S5 真凶归属旁证：裸新卡（无 turn 终态帧）两向都不夺焦 ⇒ 卡片渲染路径本身无罪 ──
    {
      console.log(`\n── S5-evidence-bare-card-does-not-steal @ ${theme} ──`);
      const r = await scenario(browser, {
        id: 'S5', colorScheme: theme,
        setup: async (page) => { await renderCardAndFocus(page, 'req-s5-a'); await page.keyboard.type('abc'); return null; },
        drive: async (page) => { await inj(page, card('req-s5-b')); },
      });
      console.log(`  activeElement: before=${r.before} after=${r.after} mainValue=${JSON.stringify(r.mainValue)}`);
      ok('S5-pageerrors', r.pageErrors.length === 0, `pageerrors=${r.pageErrors.length}`);
      ok('S5-bare-card-innocent', r.after === 'textarea.option-custom-input' && r.mainValue === '',
        `after=${r.after} mainValue=${JSON.stringify(r.mainValue)} (两向不变量)`);
    }
  }
} finally {
  await browser.close();
}

writeFileSync(join(SHOTS, `readings-pin2-${EXPECT}.json`), JSON.stringify(readings, null, 2));
console.log(`\nPIN2_READINGS ${JSON.stringify(readings)}`);
console.log(`\nRESULT: ${failures ? `FAIL (${failures} failures)` : 'PASS'}  [PIN_EXPECT=${EXPECT} web=${WEB}]`);
console.log(`screenshots/readings → ${SHOTS}`);
process.exit(failures ? 1 : 0);
