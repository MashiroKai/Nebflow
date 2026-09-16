#!/usr/bin/env node
// fwdguard-refonly-gate.spec.mjs — 「转发空转」前端闸的行为级判据（fwdguard-impl 批新增）
//
// 断点（诊断真源 `.nebflow/reports/20260917_forwarddiag.md` §③）：转发腿的载荷只走
// `refs`，而 `input.js:672-673` 又把 ref 剔出 `attachments` ⇒ 网关准入谓词
// `WebSocketRoutes.scala:4055`（`content.nonEmpty || attachments.nonEmpty`）恒假
// ⇒ `:4246 else IO.unit` 静默丢弃（零日志零 turn），而前端发帧后已 `setBusy`
// （`input.js:696-701`）⇒ 会话永久「转圈」= 空转。
//
// 本件判据 = **作者取向 Q1 的落地读数**：不做 `|| refs.nonEmpty`、不改 `:4055`，改为
// 前端在「空文本 + 带 ref」时禁用发送键 + 显示提示，两个入口（Enter 直发 / 点击发送）
// 都不产帧；并**必做防误伤**（空文本 + 真附件仍可发送、有附言 + ref 正常发送）。
//
// ── 判红纪律（同族先例 tests/content-i18n.spec.mjs）──
//   同一支探针跑两棵树：
//     · `FWDGUARD_WEB_ROOT` 未设 ⇒ 跑本仓 `src/main/resources/web`（**改后树**）
//       ⇒ 闸类判据全绿；同时**红线**：空文本 + ref 必须**零帧**（0 帧 = 闸生效）。
//     · `FWDGUARD_WEB_ROOT=<改前树 web/>` ⇒ 闸类判据逐条必须红（`FWDGUARD_EXPECT=red`
//       自动按树推断）：提示件不存在、发送键不禁用、Enter 直接产出**断点帧**
//       （`content:""` + `attachments:[]` + `refs:[…]`）—— 那正是被 4055 判空丢弃的帧，
//       本件把它逐字打出来（= 断点帧证据）。
//
// ── 断言面 ──
//   A0 应用真启动（静态 serve 本仓 web/ + MockWebSocket 捕获出站帧）
//   A1 转发腿两调用落进输入框（makeReference + appendRefToActiveView，与 messages.js:2202-2221 同源）
//   A2 [闸] 空文本 + ref ⇒ 发送键 disabled + 提示可见（真渲染：在视口内 + elementFromPoint 命中）
//   A3 [闸] 提示文案 = 服务树 zh 字典的 input.refOnlyHint（作者逐字「请附一句话后发送」）
//   A4 [闸] Enter 直发 ⇒ 零帧（入口①）
//   A5 [闸] 真鼠标点击发送键 ⇒ 零帧（入口②）
//   A6 [闸] 判据本身：isRefOnlyFrame 对纯引用帧为 true
//   B1 有附言 + ref ⇒ 正常发送（1 帧，content 非空 + refs 长度 1）
//   B2 空文本 + 真附件 ⇒ 仍可发送（1 帧，attachments 长度 1，content 为空）—— 防误伤必做
//   B3 空文本 + 无任何载荷 ⇒ 不触发本闸（既有 578-581 空守卫语义不变）
//   B4 ref 芯片移除（chat.js 侧）⇒ 闸随动解除（MutationObserver 路径）
//   C1 出站帧总账（逐条打印；改前树应含且仅含断点帧）
//
// 自包含：静态服务器（随机隔离端口）+ route 拦截 + MockWebSocket 注入；finally 必关
// （进程清理纪律）。**不 spawn gateway 实例、不碰 8080、零外网**。
//
// Run:
//   node tests/fwdguard-refonly-gate.spec.mjs
//   FWDGUARD_WEB_ROOT=/tmp/fwdguard-before/web node tests/fwdguard-refonly-gate.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const WEB = process.env.FWDGUARD_WEB_ROOT || REPO_WEB;
const MODE = process.env.FWDGUARD_WEB_ROOT ? 'BEFORE' : 'AFTER';
const EXPECT_RED = (process.env.FWDGUARD_EXPECT || (MODE === 'BEFORE' ? 'red' : 'green')) === 'red';
const OUT = process.env.FWDGUARD_OUT || join('/tmp', 'nb-fwdguard', MODE);
// 期望值取**本仓改后树**的 zh 字典（固定参照，与被服务的树解耦 —— 否则改前树会拿
// 自己缺失的键当期望而假绿）。
const ZH_REF = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'zh-CN.js')).href)).default;
const HINT_EXPECT = ZH_REF['input.refOnlyHint'];

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff2': 'font/woff2', '.map': 'application/json',
};

const results = [];
function record(name, pass, okAfter, extra) {
  results.push({ name, pass, okAfter, extra, mode: MODE });
  console.log(`${pass ? 'PASS' : 'FAIL'} [${MODE}] ${name}${extra ? '  — ' + extra : ''}`);
  return pass;
}
/** 两树都必须成立的判据（应用能启动、不受本闸影响的既有行为）。 */
function check(name, ok, extra = '') { return record(name, !!ok, !!ok, extra); }
/** 闸类判据：写成「改后树应为真」，改前树自动反相（判红：改前必须不满足）。 */
function checkGate(name, okAfter, extra = '') {
  return record(name, EXPECT_RED ? !okAfter : !!okAfter, !!okAfter, extra);
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    let p = decodeURIComponent(url.pathname);
    if (p === '/' || p === '') p = '/index.html';
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) { res.writeHead(403).end(); return; }
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const MOCK_WS = () => {
  window.__frames = [];
  class MockWS {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url) {
      this.url = url; this.readyState = 1; this._h = {};
      this.CONNECTING = 0; this.OPEN = 1; this.CLOSING = 2; this.CLOSED = 3;
      window.__ws = this;
      setTimeout(() => { try { this.onopen && this.onopen({}); } catch (e) { console.error(e); } }, 0);
    }
    send(data) {
      try { window.__frames.push(typeof data === 'string' ? JSON.parse(data) : data); }
      catch { window.__frames.push({ __unparsed: String(data) }); }
    }
    close() { this.readyState = 3; try { this.onclose && this.onclose({}); } catch {} }
    addEventListener(t, f) { (this._h[t] = this._h[t] || []).push(f); }
    removeEventListener() {}
  }
  window.WebSocket = MockWS;
};

let browser = null;
try {
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  await page.addInitScript(MOCK_WS);
  await page.route('**/api/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));

  await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('send-btn') && document.getElementById('input'), null, { timeout: 15000 });
  await page.waitForFunction(() => !!window.__ws && window.__ws.readyState === 1, null, { timeout: 15000 });
  await page.waitForFunction(() => !!(window.Nebflow && window.Nebflow.state), null, { timeout: 15000 });
  // 确保有 active view（boot 后 primary 才是 active；探针只做显式钉定，不改产品语义）
  await page.evaluate(async () => {
    const cv = await import('/js/chatView.js');
    if (cv.chatViews && cv.chatViews.primary) cv.setActiveView(cv.chatViews.primary);
  });
  await page.waitForTimeout(150);

  // ── 探针辅助（只读/复位态，不改产品语义）──────────────────────────────
  /** 清 busy（改前树在「发送成功」后会置 busy，发送键被 setBusy 隐藏 ⇒ 入口② 读
    * 不到；两棵树必须同一起跑线）。 */
  const resetBusy = async () => {
    await page.evaluate(async () => {
      const state = (await import('/js/state.js')).default;
      const { clearBusy } = await import('/js/chat.js');
      state.busySessionIds.clear();
      clearBusy(window.Nebflow.activeSessionId ?? null);
    });
    await page.waitForTimeout(60);
  };
  /** 走转发腿的两个真实调用（messages.js:2202-2221 同源）。 */
  const appendForwardRef = async (messageId, body) => page.evaluate(async ({ messageId, body }) => {
    const { makeReference } = await import('/js/reference.js');
    const { appendRefToActiveView } = await import('/js/input.js');
    const ref = makeReference({
      refType: 'friend-message',
      source: {
        conversationId: 'conv-1', messageId, friendName: 'KAI',
        friendNeblinkId: 'nl-1', direction: 'in', date: '2026-09-16 14:40',
      },
      content: { fullText: body },
    });
    const ok = appendRefToActiveView(ref);
    return {
      ok, refId: ref && ref.id, refType: ref && ref.refType,
      inputValue: document.getElementById('input').value,
      chipRendered: !!document.querySelector('.att-ref'),
      removeBtn: !!document.querySelector('.att-ref-remove'),
    };
  }, { messageId, body });
  /** 出站用户帧（clientMessageId 为用户发送帧的唯一标志）；改前树会把断点帧留档。 */
  const userFrames = async (capture) => page.evaluate((capture) => {
    const f = window.__frames.filter((x) => x && x.clientMessageId);
    if (capture && f.length) window.__breakpointFrame = f[0];
    return { n: f.length, all: window.__frames.length, frame: f[0] || null };
  }, !!capture);

  const boot = await page.evaluate(() => ({
    connected: !!window.Nebflow.state.connected,
    wsOpen: window.__ws.readyState,
    bootFrames: window.__frames.length,
    hasInputWrap: !!document.querySelector('#input-wrap'),
  }));
  check('A0 应用真启动（input-bar 渲染 + MockWebSocket OPEN + connected）',
    boot.connected && boot.wsOpen === 1 && boot.hasInputWrap === true, JSON.stringify(boot));

  await resetBusy();
  // ── A1 转发腿的两个真实调用（messages.js:2202-2221 同源）────────────────
  const afterRef = await appendForwardRef('9001', 'NebLink 远端 Mac 实测完成，这是被转发的正文（≤4000）。');
  check('A1 转发腿落进输入框（ref 芯片已渲染，输入框仍为空）',
    afterRef.ok === true && afterRef.refType === 'friend-message' && afterRef.inputValue === '' && afterRef.chipRendered,
    JSON.stringify(afterRef));

  // ── A2/A3 闸的可见态 ─────────────────────────────────────────────────
  const gateView = await page.evaluate(() => {
    const btn = document.getElementById('send-btn');
    const hint = document.querySelector('#input-bar .ref-gate-hint');
    let hintVis = null;
    if (hint) {
      const r = hint.getBoundingClientRect();
      const cs = getComputedStyle(hint);
      const cx = Math.round(r.left + r.width / 2), cy = Math.round(r.top + r.height / 2);
      const hit = (cx > 0 && cy > 0) ? document.elementFromPoint(cx, cy) : null;
      hintVis = {
        hidden: hint.hidden, display: cs.display, w: Math.round(r.width), h: Math.round(r.height),
        inViewport: r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
        hitSelf: !!(hit && (hit === hint || hint.contains(hit) || hit.contains(hint))),
        text: hint.textContent,
      };
    }
    return { disabled: btn.disabled, aria: btn.getAttribute('aria-disabled'), hintVis };
  });
  checkGate('A2 空文本 + ref ⇒ 发送键 disabled', gateView.disabled === true, `disabled=${gateView.disabled} aria=${gateView.aria}`);
  checkGate('A2b 提示件存在（#input-bar .ref-gate-hint）', !!gateView.hintVis);
  checkGate('A2c 提示**真渲染可见**（display≠none ∧ 视口内 ∧ elementFromPoint 命中自身）',
    !!(gateView.hintVis && gateView.hintVis.display !== 'none' && gateView.hintVis.inViewport && gateView.hintVis.hitSelf),
    JSON.stringify(gateView.hintVis));
  checkGate('A3 提示文案 = 服务树 zh 字典 input.refOnlyHint（作者逐字「请附一句话后发送」）',
    !!(gateView.hintVis && gateView.hintVis.text === HINT_EXPECT),
    `expect=${JSON.stringify(HINT_EXPECT)} got=${JSON.stringify(gateView.hintVis && gateView.hintVis.text)}`);

  // ── A4 Enter 直发 ⇒ 零帧 ─────────────────────────────────────────────
  await page.evaluate(() => { window.__frames.length = 0; });
  await page.click('#input', { force: true });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(250);
  const afterEnter = await userFrames(true);
  checkGate('A4 入口①（Enter 直发）⇒ 零用户帧', afterEnter.n === 0, JSON.stringify({ n: afterEnter.n, all: afterEnter.all }));

  // ── A5 真鼠标点击发送键 ⇒ 零帧 ───────────────────────────────────────
  // 两棵树必须同一起跑线：改前树在 A4 已把 ref 发出去（pendingAttachments 被清空）
  // ⇒ 那之后点击命中既有空守卫（578-581），读不到闸/无闸的差别 ⇒ 先补一个在待 ref。
  await resetBusy();
  if (await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length) === 0) {
    await appendForwardRef('9002', '第四条被转发的正文（A5 场景）。');
  }
  await page.evaluate(() => { window.__frames.length = 0; });
  const btnBox = await page.locator('#send-btn').boundingBox();
  check('A5pre 发送键可见可点（真实鼠标坐标可命中）', !!btnBox, JSON.stringify(btnBox));
  if (btnBox) await page.mouse.click(Math.round(btnBox.x + btnBox.width / 2), Math.round(btnBox.y + btnBox.height / 2));
  await page.waitForTimeout(250);
  const afterClick = await userFrames(true);
  checkGate('A5 入口②（真鼠标点击发送键）⇒ 零用户帧', afterClick.n === 0, JSON.stringify({ n: afterClick.n, all: afterClick.all }));

  // ── A6 判据本身 + 断点帧形态读数 ────────────────────────────────────
  const pred = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const cv = await import('/js/chatView.js');
    const v = cv.activeView;
    const out = { hasFn: typeof m.isRefOnlyFrame === 'function' };
    if (out.hasFn) {
      out.refOnly = m.isRefOnlyFrame(v);
      out.wireAtts = m.wireAttachmentsOf(v).length;
      out.pending = (v.pendingAttachments || []).length;
    }
    return out;
  });
  checkGate('A6 判据 isRefOnlyFrame(纯引用帧) === true', pred.hasFn && pred.refOnly === true, JSON.stringify(pred));

  // ── B1 有附言 + ref ⇒ 正常发送（两棵树都必须成立：不是「禁用一切」）────
  await resetBusy();
  const refB1 = await appendForwardRef('9101', '第三条被转发的正文（B1 场景）。');
  await page.evaluate(() => { window.__frames.length = 0; });
  await page.click('#input', { force: true });
  await page.keyboard.type('这是附言。');
  await page.waitForTimeout(120);
  const enabledAfterText = await page.evaluate(() => ({
    disabled: document.getElementById('send-btn').disabled,
    hintHidden: document.querySelector('#input-bar .ref-gate-hint')?.hidden ?? null,
  }));
  await page.keyboard.press('Enter');
  await page.waitForTimeout(250);
  const b1 = await userFrames(false);
  check('B1a 有附言 ⇒ 发送键可用（改后树：闸解除 + 提示隐藏）',
    enabledAfterText.disabled === false
      && (!EXPECT_RED ? enabledAfterText.hintHidden === true : true), JSON.stringify(enabledAfterText));
  check('B1b 有附言 + ref ⇒ 正常发送（1 帧，content 非空 ∧ refs 携带本条转发引用）',
    b1.n === 1 && !!b1.frame && b1.frame.content === '这是附言。'
      && (b1.frame.refs || []).length >= 1
      && (b1.frame.refs || []).some((r) => r.id === refB1.refId)
      && (b1.frame.attachments || []).length === 0,
    JSON.stringify({ refId: refB1.refId, n: b1.n, content: b1.frame && b1.frame.content, refs: (b1.frame && b1.frame.refs || []).map((r) => r.id) }));

  // ── B2 空文本 + 真附件 ⇒ 仍可发送（防误伤，必做）─────────────────────
  await resetBusy();
  await page.evaluate(() => { window.__frames.length = 0; });
  await page.evaluate(async () => {
    const { addFileAttachment } = await import('/js/input.js');
    const f = new File(['fwdguard-attachment-probe\n'], 'fwdguard-probe.txt', { type: 'text/plain' });
    await addFileAttachment(f);
  });
  await page.waitForTimeout(400);
  const b2pre = await page.evaluate(() => ({
    disabled: document.getElementById('send-btn').disabled,
    attChips: document.querySelectorAll('#attachment-preview .att-file').length,
  }));
  await page.click('#input', { force: true });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(250);
  const b2 = await userFrames(false);
  check('B2a 空文本 + 真附件 ⇒ 发送键**仍可用**（防误伤）', b2pre.disabled === false, JSON.stringify(b2pre));
  check('B2b 空文本 + 真附件 ⇒ 正常发送（1 帧，attachments 长度 1，content 为空）',
    b2.n === 1 && b2.frame && (b2.frame.attachments || []).length === 1 && (b2.frame.content || '') === '',
    JSON.stringify(b2.frame));

  // ── B3 空文本 + 无任何载荷 ⇒ 本闸不介入（既有空守卫语义不变）──────────
  await resetBusy();
  const b3 = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const cv = await import('/js/chatView.js');
    const v = cv.activeView;
    const out = { hasFn: typeof m.isRefOnlyFrame === 'function', pending: (v.pendingAttachments || []).length };
    if (out.hasFn) out.refOnly = m.isRefOnlyFrame(v);   // 改前树无该函数 ⇒ 不调用
    return out;
  });
  check('B3 空文本 + 无任何载荷 ⇒ 判据为 false（不介入既有空守卫）',
    !EXPECT_RED ? (b3.hasFn && b3.refOnly === false) : (b3.hasFn === false), JSON.stringify(b3));

  // ── B4 ref 芯片移除（chat.js 侧路径）⇒ 闸随动解除 ────────────────────
  const b4 = await page.evaluate(async () => {
    const { makeReference } = await import('/js/reference.js');
    const { appendRefToActiveView } = await import('/js/input.js');
    const ref = makeReference({
      refType: 'friend-message',
      source: { conversationId: 'conv-2', messageId: '9002', friendName: 'KAI', friendNeblinkId: 'nl-2', direction: 'in', date: '2026-09-16 14:41' },
      content: { fullText: '第二条被转发的正文。' },
    });
    appendRefToActiveView(ref);
    await new Promise((r) => setTimeout(r, 60));
    const before = {
      disabled: document.getElementById('send-btn').disabled,
      hasRemove: !!document.querySelector('.att-ref-remove'),
    };
    const rm = document.querySelector('.att-ref-remove');
    if (rm) rm.click();
    await new Promise((r) => setTimeout(r, 80));
    const after = {
      disabled: document.getElementById('send-btn').disabled,
      chips: document.querySelectorAll('.att-ref').length,
    };
    return { before, after };
  });
  checkGate('B4 ref 芯片移除 ⇒ 闸随动解除（发送键恢复可用）',
    b4.before.disabled === true && b4.after.chips === 0 && b4.after.disabled === false, JSON.stringify(b4));

  // ── C1 出站帧总账 ────────────────────────────────────────────────────
  const ledger = await page.evaluate(() => ({
    total: window.__frames.length,
    userish: window.__frames.filter((f) => f && f.clientMessageId).map((f) => ({
      content: f.content, attachments: (f.attachments || []).length,
      refs: (f.refs || []).length, keys: Object.keys(f),
    })),
  }));
  // 断点帧形态（= 被 4055 判空丢弃的帧）：content 空 ∧ attachments 空 ∧ refs 非空
  const bp = await page.evaluate(() => window.__breakpointFrame || null);
  console.log(`[${MODE}] 出站用户帧总账: ${JSON.stringify(ledger)}`);
  if (bp) console.log(`[${MODE}] 断点帧逐字: ${JSON.stringify(bp)}`);

  await mkdir(OUT, { recursive: true });
  const summary = {
    mode: MODE, tree: WEB, expectRed: EXPECT_RED, base: BASE,
    hintExpect: HINT_EXPECT, gateView, afterEnter, afterClick, pred, b1: b1.frame, b2: b2.frame, b4,
    ledger, breakpointFrame: bp, consoleErrors: consoleErrors.slice(0, 10),
    results, allPass: results.every((r) => r.pass),
  };
  await writeFile(join(OUT, 'p1p2-result.json'), JSON.stringify(summary, null, 2));
  console.log(`[${MODE}] 结果落盘: ${join(OUT, 'p1p2-result.json')}`);
  console.log(`[${MODE}] PASS ${results.filter((r) => r.pass).length}/${results.length}`);
  if (consoleErrors.length) console.log(`[${MODE}] console errors (${consoleErrors.length}): ` + consoleErrors.slice(0, 5).join(' | '));
  process.exitCode = results.every((r) => r.pass) ? 0 : 1;
} catch (e) {
  console.error('HARNESS ERROR:', e && e.stack || e);
  process.exitCode = 2;
} finally {
  if (browser) await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
  console.log('cleanup: browser closed + static server closed');
}
