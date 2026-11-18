#!/usr/bin/env node
// tests/todo-mobile-reply-bottom.spec.mjs — 手机待办**详情页回复框**贴底 + 自增高闸
// （作者令 2026-11-18 追加批：「详情页正文可以默认展开 但是这个对话框应该挨着底部
//   而且应该跟主会话这个一样 可以根据内容长度变化」）。
//
// 判据（逐条对应追加任务书）：
//   B1 贴底锚定：`.ta-reply` 的底缘与工具胶囊带 `.ta-tool` 顶缘只差一条呼吸缝
//      （--ds-space-2 = 8px，逐值量；不是「差不多在下面」）。
//   B2 两条带都在底部 dock 之上（dock 上缘 ≥ 工具带下缘），且回复框底缘不被 dock 截断。
//   B3 高度读数四态：空 / 单行 / 多行 / 超上限，逐态量 textarea 高度（px）。
//   B4 超上限 = **卡内滚**：高度停在封顶值（= 主会话同一口径 200），
//      scrollHeight > clientHeight（内容在件内滚），件不再长高。
//   B5 超上限不顶走正文：**正文区 `.ta-edit` 是本页的滚动面**（scrollHeight >
//      clientHeight），页体本身不再被长正文顶长（`.mobile-page-body` 不滚）；
//      正文区滚到底时正文末行的底缘仍在回复框顶缘之上（回复条是它的兄弟页脚，
//      不在正文下面）。
//   B6 四态下回复框都仍贴底、都仍不被 dock 截断（自增高不会把自己顶出底部）。
//   B7 单源：件自身 computed max-height = none（CSS 不再压第三个数）、
//      次级输入框封顶常量 = 200 = 主会话口径、实测封顶 = 200。
//   B8 真提交：填字 → 点发送 → 用户帧逐字进主会话（`#<待办名>·<id> <正文>`），件清空。
//   B9 形态仍是次级输入框模板（`.ta-reply-input` = nf-glass 外壳 + 裸件 + ds-turn-btn）。
//   B10 贴底机制 = **流内页脚**（与主会话「滚动面 + 常驻底条」同构）：`.ta-reply`
//      是 `.ta-root` 的最后一个 flex 孩子、`flex: 0 0 auto`、底缘落在 `.ta-root`
//      的让位线（--ta-band-stack）之上；**零定位声明**（computed position =
//      static）—— 不是 sticky/fixed 压住正文，也不给浮动发现闸（floating-discovery
//      ratchet）添新定位选择器。
//
// 运行：
//   node tests/todo-mobile-reply-bottom.spec.mjs
//   TMR_WEB_ROOT=<另一棵 web 树> node tests/todo-mobile-reply-bottom.spec.mjs   # 改前腿/破坏腿
//   TMR_OUT=<证据目录> node tests/todo-mobile-reply-bottom.spec.mjs
//   退出码：0 = 全绿；1 = 有红。
//
// 数据面 = routeWebSocket 帧桩 + todoStore.__seed 夹具模式；静态面 = page.route 从
// 磁盘注真 web 树。零后端、零 sbt、零宿主实例（不碰 :6138 / :8080 / :8097）。
// 🔴 诚实申报：headless 里没有系统软键盘与 safe-area，`--ta-kb` 腿只验通道存在
//   （mobile-todo-apple-shots.mjs 既有腿）+ 本轮验 dock 腿；真机键盘弹起手感另列
//   「待真机亲验」。

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TMR_WEB_ROOT
  ? normalize(resolve(process.env.TMR_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.TMR_OUT || '/Users/kaiyu/stand-evidence/todo-decisioncard-20261118';
const SHOTS = join(OUT, 'detail-shots');
const FRAMES = join(OUT, 'frames-detail');
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(SHOTS, { recursive: true });
mkdirSync(FRAMES, { recursive: true });
// EvidenceTemp batch contract (scripts/check-evidence-manifests.mjs): a batch
// that writes into the stand-evidence namespace MUST carry a manifest.json, or
// the fail-closed TTL sweep skips it and retention becomes unbounded. The task
// pins this batch's dir (TMR_OUT), so the manifest is written here instead of
// relocating through the shared helper.
writeFileSync(
  join(OUT, 'manifest.json'),
  JSON.stringify({ createdAt: new Date().toISOString(), project: 'stand', batch: 'todo-decisioncard-20261118' }) + '\n',
);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'todo-mobile-reply-bottom-session';
const VW = 375, VH = 812;
const ITEM = 'reply-bottom-1';
const CAP = 200;                    // = 主会话输入框封顶（single source 口径）
const LONG_BODY = Array.from({ length: 14 }, (_, i) => `正文第 ${i + 1} 行：详情页正文要能完整显示，长正文不应被容器高度截断。`).join('\n');

let pass = 0, fail = 0;
const lines = [];
const ok = (name, cond, detail = '') => {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const probe = {};
const sentFrames = [];
const pageErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: VW, height: VH }, deviceScaleFactor: 2,
  hasTouch: true, isMobile: true, colorScheme: 'light', locale: 'zh-CN',
});
const page = await ctx.newPage();
page.on('pageerror', (e) => { if (!NOISE.test(e.message)) pageErrors.push(`pageerror: ${e.message}`); });
page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) pageErrors.push(`console.error: ${m.text()}`); });

await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  if (navigator.serviceWorker) {
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  config(); sessions();
  ws.onMessage((raw) => {
    sentFrames.push(String(raw));
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') config();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    // 回合终态桩：零后端夹具没有引擎回合，补 done 让 busy 闸收口（同 todo-row-reply）。
    else if (typeof m.content === 'string' && m.sessionId) ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId }));
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws?.readyState === 1;
}, SID, { timeout: 20000 });

// 进入详情页（真入口链：手机任务钮 → 列表标签 → 行 → 详情页编辑态）
const taskButton = page.locator("[data-chrome='tasks']");
await taskButton.waitFor({ state: 'visible' });
await taskButton.click();
await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'), null, { timeout: 15000 });
await page.evaluate((body) => import('/js/todoStore.js').then(({ todoStore }) => todoStore.__seed({
  tagList: [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }],
  itemList: [{ id: 'reply-bottom-1', tagId: 'tag-work', title: '详情页回复框', notes: body, due: null, priority: 1, createdAt: Date.now() }],
})), LONG_BODY);
await page.waitForTimeout(260);
const taskPage = page.locator(".mobile-page[data-page='tasks']");
await taskPage.locator('.ta-row.ta-row-tag', { hasText: '工作' }).click();
await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'), null, { timeout: 15000 });
await page.locator(`.mobile-page[data-page='todo-apple-list'] .ta-item[data-item-id='${ITEM}'] .ta-item-body`).click();
await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-item']")?.classList.contains('live'), null, { timeout: 15000 });
await page.waitForTimeout(420);

/* ── 读数 ── */
const READ = `(() => {
  const f = (v) => +v.toFixed(2);
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { top: f(b.top), bottom: f(b.bottom), h: f(b.height), w: f(b.width) }; };
  const pageEl = document.querySelector(".mobile-page[data-page='todo-apple-item']");
  const reply = pageEl.querySelector('.ta-reply');
  const tool = pageEl.querySelector('.ta-tool');
  const ta = pageEl.querySelector('.ta-reply-input textarea');
  const shell = pageEl.querySelector('.ta-reply-input');
  const dock = document.querySelector('#mobile-dock');
  const bodyScroll = pageEl.querySelector('.mobile-page-body');
  const edit = pageEl.querySelector('.ta-edit');
  const root = pageEl.querySelector('.ta-root');
  const cs = reply ? getComputedStyle(reply) : null;
  const tcs = ta ? getComputedStyle(ta) : null;
  const rb = box(reply); const tb = box(tool);
  return {
    reply: rb, tool: tb, dock: box(dock), shell: box(shell),
    ta: ta ? { ...box(ta), scrollH: ta.scrollHeight, clientH: ta.clientHeight, value: ta.value,
      maxH: tcs.maxHeight, overflowY: tcs.overflowY, tag: ta.tagName.toLowerCase() } : null,
    replyPosition: cs ? cs.position : null,
    replyZ: cs ? cs.zIndex : null,
    replyBg: cs ? cs.backgroundColor : null,
    replyFlexGrow: cs ? cs.flexGrow : null,
    replyFlexShrink: cs ? cs.flexShrink : null,
    replyIsLastChild: !!(reply && root && reply.parentElement === root && root.lastElementChild === reply),
    editOverflowY: edit ? getComputedStyle(edit).overflowY : null,
    editScroll: edit ? { scrollTop: f(edit.scrollTop), scrollH: edit.scrollHeight, clientH: edit.clientHeight } : null,
    shellCls: shell ? shell.className : null,
    scrollBtnCls: shell ? (shell.querySelector('button') || {}).className || null : null,
    page: bodyScroll ? { scrollTop: f(bodyScroll.scrollTop), scrollH: bodyScroll.scrollHeight, clientH: bodyScroll.clientHeight } : null,
    viewport: { w: innerWidth, h: innerHeight },
    varBandBottom: cs ? cs.getPropertyValue('--ta-band-bottom').trim() : null,
    varBandStack: cs ? cs.getPropertyValue('--ta-band-stack').trim() : null,
    gapPx: rb && tb ? f(tb.top - rb.bottom) : null,
    padBottom: cs ? cs.paddingBottom : null,
  };
})()`;

const read = () => page.evaluate(READ);
const userContentFrames = () => sentFrames
  .map((r) => { try { return JSON.parse(r); } catch { return null; } })
  .filter((m) => m && typeof m.content === 'string' && m.sessionId === SID)
  .map((m) => m.content);
const setValue = (text) => page.evaluate((v) => {
  const ta = document.querySelector(".mobile-page[data-page='todo-apple-item'] .ta-reply-input textarea");
  ta.value = v;
  ta.dispatchEvent(new Event('input', { bubbles: true }));
}, text);

const base = await read();
probe.base = base;
const replySel = ".mobile-page[data-page='todo-apple-item'] .ta-reply";
await page.screenshot({ path: join(SHOTS, '01-detail-reply-bottom.png') });

ok('B1 回复框贴底锚定：底缘与工具胶囊带顶缘只差一条呼吸缝（--ds-space-2 = 8px）',
  !!base.reply && !!base.tool && Math.abs(base.gapPx - 8) <= 1.5,
  JSON.stringify({ reply: base.reply, tool: base.tool, gapPx: base.gapPx, bandStack: base.varBandStack }));
ok('B2 两条带都在底部 dock 之上（回复框底缘 ≤ 工具带底缘 ≤ dock 上缘）',
  !!base.dock && base.reply.bottom <= base.tool.bottom + 0.5 && base.tool.bottom <= base.dock.top + 0.5,
  JSON.stringify({ reply: base.reply, tool: base.tool, dock: base.dock }));
ok('B10 贴底机制 = 流内页脚且零定位：.ta-reply 是 .ta-root 最后一个 flex 孩子（flex 0 0 auto）、'
  + 'position = static；正文区 .ta-edit 是本页滚动面（overflow-y: auto）',
  base.replyPosition === 'static' && base.replyIsLastChild === true
  && base.replyFlexGrow === '0' && base.replyFlexShrink === '0' && base.editOverflowY === 'auto',
  JSON.stringify({ position: base.replyPosition, lastChild: base.replyIsLastChild, flexGrow: base.replyFlexGrow,
    flexShrink: base.replyFlexShrink, editOverflowY: base.editOverflowY, zIndex: base.replyZ, bg: base.replyBg }));

/* ── B3 四种状态的高度读数 ── */
const H = {};
await setValue('');
await page.waitForTimeout(200);
H.empty = (await read()).ta;
await page.screenshot({ path: join(SHOTS, '02-reply-empty.png') });
await setValue('单行草稿');
await page.waitForTimeout(200);
H.one = (await read()).ta;
await page.screenshot({ path: join(SHOTS, '03-reply-one-line.png') });
await setValue(['第二行', '第三行', '第四行', '第五行'].join('\n'));
await page.waitForTimeout(220);
H.multi = (await read()).ta;
await page.screenshot({ path: join(SHOTS, '04-reply-multi-line.png') });
await setValue(Array.from({ length: 40 }, (_, i) => `超限第 ${i + 1} 行`).join('\n'));
await page.waitForTimeout(320);
const overRaw = await read();
H.over = overRaw.ta;
await page.screenshot({ path: join(SHOTS, '05-reply-over-cap.png') });
probe.heights = H;

ok('B3 四态高度读数齐备且单调（空 ≤ 单行 ≤ 多行 ≤ 超限）',
  !!H.empty && !!H.one && !!H.multi && !!H.over
  && H.empty.h > 0 && H.one.h >= H.empty.h - 0.5 && H.multi.h > H.one.h + 8 && H.over.h > H.multi.h,
  JSON.stringify({ empty: H.empty && H.empty.h, one: H.one && H.one.h, multi: H.multi && H.multi.h, over: H.over && H.over.h }));
ok('B4 超上限 = 高度停在封顶值（不再长高），内容改为件内滚（scrollHeight > clientHeight）',
  !!H.over && Math.abs(H.over.h - CAP) <= 1.5 && H.over.scrollH > H.over.clientH + 10 && H.over.overflowY === 'auto',
  JSON.stringify({ h: H.over.h, cap: CAP, scrollH: H.over.scrollH, clientH: H.over.clientH, overflowY: H.over.overflowY }));
ok('B7 单源：件自身 computed max-height = none（CSS 不再压第三个数）',
  H.over.maxH === 'none' || H.over.maxH === '0px' || H.over.maxH === '', String(H.over.maxH));
{
  const mod = await page.evaluate(async () => {
    const m = await import('/js/input/secondaryComposer.js');
    return { main: m.SECONDARY_COMPOSER_MAX_H, compact: m.SECONDARY_COMPOSER_MAX_H_COMPACT };
  });
  probe.caps = mod;
  ok('B7 次级输入框封顶常量 = 200 = 主会话输入框口径（单一声明点，实测件也封在 200）',
    mod.main === CAP && Math.abs(H.over.h - mod.main) <= 1.5,
    JSON.stringify({ constant: mod.main, measured: H.over.h, compactForAsk: mod.compact }));
}
ok('B9 形态 = 次级输入框模板（.ta-reply-input = nf-glass 外壳 + 裸件 textarea + ds-turn-btn 发送钮）',
  !!base.shellCls && /nf-glass/.test(base.shellCls) && base.ta.tag === 'textarea'
  && /ds-turn-btn/.test(String(base.scrollBtnCls)),
  JSON.stringify({ shell: base.shellCls, input: base.ta.tag, send: base.scrollBtnCls }));

/* ── B5 超上限不顶走正文：回复条到封顶时（现在页面处于 B4 的超限态）正文区被挤矮但
   **不挤扁**（内容超高 ⇒ 正文区自己滚），页体不被顶长，滚到底末行底缘仍在回复框顶缘之上 ── */
const bottomProof = await page.evaluate(`(() => {
  const pageEl = document.querySelector(".mobile-page[data-page='todo-apple-item']");
  const body = pageEl.querySelector('.mobile-page-body');
  const edit = pageEl.querySelector('.ta-edit');
  const reply = pageEl.querySelector('.ta-reply');
  const notes = pageEl.querySelector('.ta-notes');
  const title = pageEl.querySelector('.ta-title-input');
  body.scrollTop = body.scrollHeight;
  const beforeScroll = +edit.scrollTop.toFixed(1);
  edit.scrollTop = edit.scrollHeight;
  const rb = reply.getBoundingClientRect();
  const eb = edit.getBoundingClientRect();
  const nb = notes.getBoundingClientRect();
  const tb = title.getBoundingClientRect();
  return { replyH: +rb.height.toFixed(2), editScrollTopBefore: beforeScroll,
    editScrollTop: +edit.scrollTop.toFixed(1), editScrollH: edit.scrollHeight, editClientH: edit.clientHeight,
    editH: +eb.height.toFixed(2), editBottom: +eb.bottom.toFixed(2),
    bodyScrollH: body.scrollHeight, bodyClientH: body.clientHeight,
    replyTop: +rb.top.toFixed(2), replyBottom: +rb.bottom.toFixed(2),
    titleH: +tb.height.toFixed(2), notesH: +nb.height.toFixed(2),
    notesBottom: +nb.bottom.toFixed(2),
    notesBottomAboveReply: nb.bottom <= rb.top + 1,
    contentRegionAboveReply: eb.bottom <= rb.top + 1 };
})()`);
probe.bottomProof = bottomProof;
ok('B5 超上限不顶走正文：回复条在封顶态时正文区被挤矮但内容不被挤扁（正文区 scrollH > clientH 可滚）、'
  + '页体不被顶长（body 不滚），滚到底时正文末行底缘 ≤ 回复框顶缘',
  bottomProof.replyH >= 199
  && bottomProof.editScrollH > bottomProof.editClientH + 20
  && bottomProof.titleH > 0 && bottomProof.notesH > 0
  && bottomProof.bodyScrollH <= bottomProof.bodyClientH + 1
  && bottomProof.contentRegionAboveReply === true
  && bottomProof.notesBottomAboveReply === true,
  JSON.stringify(bottomProof));
await page.screenshot({ path: join(SHOTS, '06-content-bottom-not-covered.png') });

/* ── B6 四态下仍贴底 + 不被 dock 截断 ── */
const sticks = {};
for (const [name, text] of Object.entries({ empty: '', one: '单行', multi: '一\n二\n三\n四', over: Array.from({ length: 40 }, (_, i) => `超限 ${i}`).join('\n') })) {
  await page.evaluate(() => { const b = document.querySelector(".mobile-page[data-page='todo-apple-item'] .ta-edit"); b.scrollTop = 0; });
  await setValue(text);
  await page.waitForTimeout(240);
  const r = await read();
  sticks[name] = { gapPx: r.gapPx, replyBottom: r.reply.bottom, dockTop: r.dock.top, h: r.ta.h };
}
probe.sticks = sticks;
ok('B6 四态下回复框都仍贴底（缝 = 8px）且都仍不被 dock 截断',
  Object.values(sticks).every((s) => Math.abs(s.gapPx - 8) <= 1.5 && s.replyBottom <= s.dockTop + 0.5),
  JSON.stringify(sticks));

/* ── B8 真提交 ── */
await setValue('');
await page.waitForTimeout(160);
await setValue('回复：这条详情页回执');
await page.waitForTimeout(200);
const framesBefore = userContentFrames().length;
await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-reply-input button').click();
await page.waitForTimeout(520);
const userFrames = userContentFrames();
const afterSend = await read();
probe.submit = { before: framesBefore, frames: userFrames, value: afterSend.ta.value };
ok('B8 真提交：回复逐字进既有回复链路（用户帧 = #待办名·id + 正文）+ 件清空',
  userFrames.length === framesBefore + 1
  && userFrames[userFrames.length - 1] === `#详情页回复框·${ITEM} 回复：这条详情页回执`
  && afterSend.ta.value === '',
  JSON.stringify({ frames: userFrames, value: afterSend.ta.value }));

ok('Z 零页面错误（pageerror/console.error 去噪后）', pageErrors.length === 0, pageErrors.slice(0, 4).join(' | '));

/* ── 证据腿：录「贴底 → 单行 → 多行 → 超限卡内滚 → 滚到底 → 提交」── */
let media = null;
try {
  await page.evaluate(() => { const b = document.querySelector(".mobile-page[data-page='todo-apple-item'] .ta-edit"); b.scrollTop = 0; });
  await setValue('');
  await sleep(500);
  const cdp = await ctx.newCDPSession(page);
  const frames = [];
  cdp.on('Page.screencastFrame', (fr) => {
    frames.push(fr.data);
    cdp.send('Page.screencastFrameAck', { sessionId: fr.sessionId }).catch(() => {});
  });
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
  await sleep(500);
  await setValue('第一行');
  await sleep(650);
  await setValue('第一行\n第二行\n第三行\n第四行');
  await sleep(750);
  await setValue(Array.from({ length: 30 }, (_, i) => `超限第 ${i + 1} 行`).join('\n'));
  await sleep(900);
  // 超限后件内滚（真触摸拖动件内）
  const taBox = await page.evaluate(`(() => { const r = document.querySelector(".mobile-page[data-page='todo-apple-item'] .ta-reply-input textarea").getBoundingClientRect();
    return { cx: Math.round(r.x + r.width / 2), cy: Math.round(r.y + r.height / 2) }; })()`);
  {
    const cdp2 = await ctx.newCDPSession(page);
    await cdp2.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: taBox.cx, y: taBox.cy, id: 1 }] });
    for (let i = 1; i <= 8; i++) {
      await cdp2.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: taBox.cx, y: Math.round(taBox.cy - (i * 90) / 8), id: 1 }] });
      await sleep(20);
    }
    await cdp2.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await cdp2.detach();
  }
  await sleep(700);
  // 页体滚到底（证明正文不被回复框盖住）
  await page.evaluate(`(() => { const b = document.querySelector(".mobile-page[data-page='todo-apple-item'] .ta-edit"); b.scrollTop = b.scrollHeight; })()`);
  await sleep(800);
  await setValue('回复：这条详情页回执');
  await sleep(650);
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-reply-input button').click();
  await sleep(800);
  await cdp.send('Page.stopScreencast');
  await cdp.detach();

  frames.forEach((b64, i) => writeFileSync(join(FRAMES, `${String(i).padStart(4, '0')}.jpg`), Buffer.from(b64, 'base64')));
  const ff = (args) => execFileSync(args[0], args.slice(1), { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const gif = join(OUT, 'detail-reply-bottom.gif');
  const mp4 = join(OUT, 'detail-reply-bottom.mp4');
  ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
    '-vf', 'fps=10,scale=280:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3', gif]);
  ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
    '-vf', 'fps=30,scale=750:-2:flags=lanczos', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '24', mp4]);
  const fprobe = (p) => ff(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,avg_frame_rate', '-show_entries', 'format=duration,size,format_name', '-of', 'json', p]);
  media = { frames: frames.length, gif: { path: gif, probe: JSON.parse(fprobe(gif)) }, mp4: { path: mp4, probe: JSON.parse(fprobe(mp4)) } };
  probe.media = media;
  const CAPB = 10 * 1024 * 1024;
  const gifBytes = readFileSync(gif).length;
  const mp4Bytes = readFileSync(mp4).length;
  probe.mediaSizes = { gifBytes, mp4Bytes, capBytes: CAPB };
  ok('Z 交付媒体 ≤10MB（对真值文件量尺，GIF 与 MP4）', gifBytes <= CAPB && mp4Bytes <= CAPB,
    JSON.stringify({ gifBytes, mp4Bytes, capBytes: CAPB }));
  console.log(`      [证据] ${frames.length} 帧 → GIF ${JSON.stringify(media.gif.probe.streams && media.gif.probe.streams[0])} / MP4 ${JSON.stringify(media.mp4.probe.streams && media.mp4.probe.streams[0])}`);
} catch (e) {
  probe.mediaError = String((e && e.message) || e);
  console.log('      [证据] 录制腿失败：' + probe.mediaError);
}

probe.web = WEB;
writeFileSync(join(OUT, 'detail-probe.json'), JSON.stringify(probe, null, 2) + '\n');
writeFileSync(join(OUT, 'detail-result.txt'),
  `# 详情页回复框 贴底 + 自增高 gate（作者令 2026-11-18 追加）\nWEB=${WEB}\nOUT=${OUT}\n\n`
  + lines.join('\n') + `\n\nPASS ${pass} / FAIL ${fail}\n`
  + `\n高度读数（px）：空 ${H.empty && H.empty.h} / 单行 ${H.one && H.one.h} / 多行 ${H.multi && H.multi.h} / 超限 ${H.over && H.over.h}（封顶 ${CAP}）\n`
  + (media ? `\nGIF ${media.gif.path}\n  ${JSON.stringify(media.gif.probe)}\nMP4 ${media.mp4.path}\n  ${JSON.stringify(media.mp4.probe)}\n` : ''));
console.log(`\n=== ${pass} passed, ${fail} failed ===`);
console.log(`evidence: ${OUT}`);
await ctx.close();
await browser.close();
process.exit(fail ? 1 : 0);
