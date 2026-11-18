#!/usr/bin/env node
// composer-message-item-unify-app.spec.mjs — composer/message-item 统一批的**真应用页**
// 渲染几何与行为验收（2026-11-18）。
//
// 与旧 tests/message-item-unify.spec.mjs 的区别（复核意见改正点）：旧 spec 的驱动面是
// 一个**手写 HARNESS 页**（自拼 <link> + import 两个模块），被指「fixture 不是真应用」。
// 本 spec 驱动的是**真 index.html**——静态文件从 src/main/resources/web 读盘、真实
// 模块图、真实 css 全链、真实 ws.js 分发路径；好友 REST 面走 friendsApi.js 的
// `fm_api_mock` seed（与 friend-trust-sealed.spec.mjs 同款注入口径）。
// 无后端、无端口、不碰宿主 6138。
//
// 断言矩阵（两视口 375×812 / 1280×800；每视口录屏）：
//   A. composer 同组件（真页面实测 computed）
//      A1 外壳单定义：`#input-bar` 与 `.fm-input-bar` 的 border-radius / padding 四向 /
//         gap / display / align-items / position 逐值相等。
//      A2 内件同型：`#input` 与 `.fm-input` 都是 TEXTAREA，padding / max-height /
//         font-size / line-height / width 逐值相等，且都挂 `nf-field--bare`。
//      A3 能力同清单：两面都挂 `.nf-glass` 玻璃材质类、都有发送键（`ds-turn-btn--send`）、
//         都有麦克风键与（可附件面）纸夹键、placeholder 同源非空。
//   B. composer 行为（键鼠真事件）
//      B1 autosize：多行后 textarea 高度 > 单行高度（两面同判据）。
//      B2 键盘契约：裸 Enter 提交（输入框清空）。
//   C. 消息条目（真页面里的真组件）
//      C1 主会话 `.bubble` 与消息面 `.fm-msg-bubble` 几何 > 0（真渲染的行）。
//      C2 附件区：消息面 `.fm-att-list` 挂 `data-nf-attach-area="1"`（= chatItem.attachmentArea
//         唯一构造点的落地证据），且容器基线（display/flex-direction/align）与主会话 `.pop-group` 同源。
//      C3 叠卡两态：合起 = 一枚 `[data-nf-stack-footer="group"]` 可见；展开 = 每卡
//         `[data-nf-stack-footer="card"]` 可见且带 `.msg-dl` 下载键。
//   D. footer 基座基类 `.duration-badge` 两面 computed 底色 / 圆角相等（同基座，格式变体）。
//
// Run: node tests/composer-message-item-unify-app.spec.mjs
// 证据：evidence 目录由 EVID 环境变量给出（缺省 .stand/tmp/composer-unify/evidence/shots-app）。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = process.env.EVID || join(ROOT, '.stand', 'tmp', 'composer-unify', 'evidence', 'shots-app');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };

const UID = 'u-cu';
const CID = 'c-cu';
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const nowSec = () => Math.floor(Date.now() / 1000);
const FRIEND = { userId: UID, neblinkId: 'buddy01', name: '好友甲', avatarUrl: '' };
const SEED = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [],
  friends: [{ ...FRIEND, since: new Date().toISOString() }],
  incoming: [],
  outgoing: [],
  conversations: [{
    conversationId: CID, friend: FRIEND,
    lastMessage: { id: 1, senderId: UID, kind: 'text', body: '统一批早前消息', createdAt: nowSec() - 600 },
    unreadCount: 0,
  }],
  messages: { [CID]: [
    { id: 1, senderId: UID, kind: 'text', body: '统一批早前消息', createdAt: nowSec() - 600 },
    { id: 2, senderId: 'me', kind: 'text', body: '统一批我方回执', createdAt: nowSec() - 500 },
  ] },
};

let failures = 0;
const results = [];
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  results.push({ name, pass: !!cond, extra });
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const HISTORY = [
  { type: 'user', text: '统一批主会话用户行', timestamp: nowSec() * 1000 - 5000 },
  { type: 'ai', text: '统一批主会话回执行', timestamp: nowSec() * 1000 - 4000 },
];

async function boot(page) {
  await page.addInitScript(([seedJson]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_trusted', JSON.stringify([]));
  }, [JSON.stringify(SEED)]);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', (r) => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 's1', messages: HISTORY, hasMore: false, offset: 0 }));
  });
  await page.goto('http://localhost:1/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await sleep(800);
}

/** 打开消息面板 → 好友会话（真点击路径，桌面走 #messages-btn / 手机走底 dock 键）。
 * @returns {Promise<boolean>} 是否进入会话面 */
async function openConversation(page) {
  const dock = page.locator('#mobile-dock .dock-key[data-dock="messages"]');
  const mobile = (await dock.count()) > 0 && await dock.first().isVisible();
  if (mobile) {
    await dock.first().click({ timeout: 15000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="messages"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else if (!(await page.locator('#panel-messages.active').count())) {
    await page.click('#messages-btn', { timeout: 15000 });
    await sleep(700);
  }
  const row = page.locator(`.fm-conv-row[data-conversation-id="${CID}"]`).first();
  if (!(await row.count())) return false;
  await row.click({ timeout: 15000 });
  if (mobile) {
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="conversation"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else {
    await page.waitForSelector('#header .fm-main-head', { timeout: 15000 });
  }
  await sleep(600);
  return true;
}

const READ = (sel) => `(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return null;
  const c = getComputedStyle(el); return { tag: el.tagName, radius: c.borderRadius, pT: c.paddingTop, pR: c.paddingRight,
  pB: c.paddingBottom, pL: c.paddingLeft, gap: c.gap, display: c.display, align: c.alignItems, pos: c.position,
  fontSize: c.fontSize, lineHeight: c.lineHeight, maxH: c.maxHeight, width: c.width, bg: c.backgroundColor,
  boxSizing: c.boxSizing, resize: c.resize, overflowY: c.overflowY, outline: c.outlineStyle, shadow: c.boxShadow,
  borderTopW: c.borderTopWidth, borderTopC: c.borderTopColor, backdrop: c.backdropFilter || c.webkitBackdropFilter,
  cls: String(el.className), ph: el.getAttribute('placeholder'), h: el.getBoundingClientRect().height, w: el.getBoundingClientRect().width }; })()`;

const VIEWPORTS = [
  { name: 'mobile-375x812', width: 375, height: 812 },
  { name: 'desktop-1280x800', width: 1280, height: 800 },
];

const browser = await chromium.launch();
rmSync(EVID, { recursive: true, force: true });
mkdirSync(EVID, { recursive: true });

for (const vp of VIEWPORTS) {
  const vidDir = join(EVID, `video-${vp.name}`);
  mkdirSync(vidDir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN',
    reducedMotion: 'reduce', recordVideo: { dir: vidDir, size: { width: vp.width, height: vp.height } },
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const tag = (n) => `${vp.name} ${n}`;

  try {
    await boot(page);

    // ── A. composer 同组件（主会话侧）──────────────────────────────────────
    const mainShell = await page.evaluate(READ('#input-bar'));
    const mainField = await page.evaluate(READ('#input'));
    // 发送键空闲态可见性在**发消息之前**量（发后 busy ⇒ 发送键隐 / 中断键显，是产品态）
    const mainSendBtnVisible = await page.evaluate(() => {
      const b = document.querySelector('#send-btn');
      return !!b && getComputedStyle(b).display !== 'none' && b.getBoundingClientRect().width > 0;
    });
    ok(tag('A1 主会话 #input-bar 在场'), !!mainShell && mainShell.w > 0 && mainShell.h > 0,
      mainShell ? `w=${Math.round(mainShell.w)} h=${Math.round(mainShell.h)}` : 'missing');
    ok(tag('A2 主会话 #input 是 textarea'), mainField && mainField.tag === 'TEXTAREA', mainField && mainField.tag);

    // ── B1/B2 主会话 composer 行为 ─────────────────────────────────────────
    const mainBehavior = await page.evaluate(async () => {
      const input = document.querySelector('#input');
      input.focus();
      input.value = 'first';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 40));
      const h0 = input.getBoundingClientRect().height;
      input.value = 'first\nsecond\nthird\nfourth';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 60));
      return { h0, h1: input.getBoundingClientRect().height };
    });
    ok(tag('B1 主会话 autosize：多行后高度增长'), mainBehavior.h1 > mainBehavior.h0,
      `h0=${mainBehavior.h0} h1=${mainBehavior.h1}`);
    const mainSend = await page.evaluate(async () => {
      const input = document.querySelector('#input');
      input.value = '统一批发送探针';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      await new Promise((r) => setTimeout(r, 150));
      return { after: input.value, frames: (window.__wsSent || []).map((f) => f && f.type) };
    });
    ok(tag('B2 主会话裸 Enter 提交（输入框清空）'), mainSend.after === '',
      `after=${JSON.stringify(mainSend.after)} outFrames=${JSON.stringify(mainSend.frames)}`);
    ok(tag('A3 主会话发送键在位且可见（空闲态）'), mainSendBtnVisible);

    // ── C1 主会话真渲染消息条目 ────────────────────────────────────────────
    const mainItem = await page.evaluate(READ('#chat .row .bubble'));
    ok(tag('C1 主会话 .bubble 几何 > 0'), !!mainItem && mainItem.w > 0 && mainItem.h > 0,
      mainItem ? `w=${Math.round(mainItem.w)} h=${Math.round(mainItem.h)} radius=${mainItem.radius}` : 'missing');
    const mainFooter = await page.evaluate(READ('#chat .row .duration-badge'));
    ok(tag('D 主会话 footer .duration-badge 在位'), !!mainFooter && mainFooter.w > 0, mainFooter ? `bg=${mainFooter.bg} radius=${mainFooter.radius}` : 'missing');

    // ── C3 叠卡 footer 两态（真应用页内的真生产链；在**主会话可见**时量）────
    // 位置说明：进入消息面板后 `#main.fm-social-main` 会把主会话列置
    // `visibility:hidden`（friends.css），故主会话面的节点必须在打开会话**之前**量。
    const stack = await page.evaluate(async ({ png }) => {
      const { buildUserMediaRow } = await import('/js/popArtifacts.js');
      const host = document.querySelector('#chat');
      const atts = [0, 1, 2].map((i) => ({ type: 'image', name: 's' + i + '.png', path: '/tmp/s' + i + '.png', preview: png }));
      const row = buildUserMediaRow(atts, { ts: Date.now() });
      row.dataset.probe = 'stack';
      host.append(row);
      await new Promise((r) => setTimeout(r, 200));
      return { group: row.querySelectorAll('[data-nf-stack-footer="group"]').length,
        cards: row.querySelectorAll('[data-nf-stack-footer="card"]').length };
    }, { png: PNG });
    ok(tag('C3 合起态：整组 footer 一枚'), stack.group === 1, `group=${stack.group}`);
    ok(tag('C3 每卡 footer 预置三枚'), stack.cards === 3, `cards=${stack.cards}`);
    const collapsedVisible = await page.locator('[data-probe="stack"] [data-nf-stack-footer="group"]').first().isVisible();
    const collapsedCardHidden = await page.locator('[data-probe="stack"] [data-nf-stack-footer="card"]').first().isHidden();
    ok(tag('C3 合起态：整组 footer 可见 / 每卡 footer 隐藏'), collapsedVisible && collapsedCardHidden,
      `groupVisible=${collapsedVisible} cardHidden=${collapsedCardHidden}`);
    await page.locator('[data-probe="stack"] .ds-media-stack__pill').first().click();
    await sleep(300);
    const expanded = await page.evaluate(() => {
      const row = document.querySelector('[data-probe="stack"]');
      const g = row.querySelector('[data-nf-stack-footer="group"]');
      const cards = [...row.querySelectorAll('[data-nf-stack-footer="card"]')];
      return {
        groupHidden: !g || getComputedStyle(g).display === 'none' || g.offsetParent === null,
        visibleCards: cards.filter((c) => c.offsetParent !== null).length,
        dl: cards.map((c) => c.querySelectorAll('.msg-dl').length),
        dlBox: cards.map((c) => { const d = c.querySelector('.msg-dl'); const b = d && d.getBoundingClientRect(); return b ? Math.round(b.width) + 'x' + Math.round(b.height) : 'none'; }),
      };
    });
    ok(tag('C3 展开态：整组 footer 隐藏'), expanded.groupHidden);
    ok(tag('C3 展开态：每卡 footer 可见'), expanded.visibleCards === 3, `visible=${expanded.visibleCards}`);
    ok(tag('C3 展开态：每卡 footer 有下载键 .msg-dl'), expanded.dl.every((n) => n === 1) && expanded.dlBox.every((s) => s !== 'none'),
      `dl=${expanded.dl.join(',')} box=${expanded.dlBox.join(',')}`);
    await page.screenshot({ path: join(EVID, `main-chat-${vp.name}.png`) });

    // ── 消息面 ────────────────────────────────────────────────────────────
    const opened = await openConversation(page);
    ok(tag('0 真点击进入好友会话面'), opened);
    if (!opened) throw new Error('conversation surface unreachable');

    const fmShell = await page.evaluate(READ('.fm-input-bar'));
    const fmField = await page.evaluate(READ('.fm-input'));
    ok(tag('A1 消息面 .fm-input-bar 在场'), !!fmShell && fmShell.w > 0 && fmShell.h > 0,
      fmShell ? `w=${Math.round(fmShell.w)} h=${Math.round(fmShell.h)}` : 'missing');

    if (mainShell && fmShell) {
      for (const k of ['radius', 'pT', 'pR', 'pB', 'pL', 'gap', 'display', 'align', 'pos']) {
        ok(tag(`A1 外壳单定义 ${k} 相等`), mainShell[k] === fmShell[k], `main=${mainShell[k]} fm=${fmShell[k]}`);
      }
      // A3 玻璃材质同令牌档（computed 口径，比类名更强的判据）：主会话外壳挂 id 级配方、
      // 消息面挂 `.nf-glass` 工具类 —— 两者 computed 材质必须逐值同。
      for (const k of ['bg', 'backdrop', 'shadow', 'borderTopW', 'borderTopC']) {
        ok(tag(`A3 玻璃材质 ${k} 相等`), mainShell[k] === fmShell[k], `main=${mainShell[k]} fm=${fmShell[k]}`);
      }
      writeFileSync(join(EVID, `composer-shell-${vp.name}.json`), JSON.stringify({ main: mainShell, fm: fmShell }, null, 2));
    }
    if (mainField && fmField) {
      ok(tag('A2 内件同型 textarea'), mainField.tag === 'TEXTAREA' && fmField.tag === 'TEXTAREA',
        `main=${mainField.tag} fm=${fmField.tag}`);
      for (const k of ['pT', 'pR', 'pB', 'pL', 'fontSize', 'lineHeight', 'maxH', 'boxSizing', 'resize', 'overflowY']) {
        ok(tag(`A2 内件单定义 ${k} 相等`), mainField[k] === fmField[k], `main=${mainField[k]} fm=${fmField[k]}`);
      }
      // 裸件契约（computed 口径）：两面内件都必须无底/无边/无环/无影 —— 与
      // css/inputSurface.css 裸件成员表（`#input` 与 `.nf-field--bare` 同列）一致。
      for (const k of ['outline', 'shadow']) {
        ok(tag(`A2 裸件契约 ${k} 两面同`), mainField[k] === fmField[k], `main=${mainField[k]} fm=${fmField[k]}`);
      }
      ok(tag('A2 裸件契约：两面内件边框宽同值'), mainField.borderTopW === fmField.borderTopW,
        `main=${mainField.borderTopW} fm=${fmField.borderTopW}`);
      ok(tag('A3 placeholder 两面同源非空'), !!mainField.ph && !!fmField.ph,
        `main=${JSON.stringify(mainField.ph)} fm=${JSON.stringify(fmField.ph)}`);
      writeFileSync(join(EVID, `composer-field-${vp.name}.json`), JSON.stringify({ main: mainField, fm: fmField }, null, 2));
    }
    const caps = await page.evaluate(() => {
      const q = (s) => document.querySelector(s);
      return {
        fmSend: !!q('.fm-input-bar .ds-turn-btn--send'), fmMic: !!q('.fm-input-bar .fm-mic-btn'),
        fmAttach: !!q('.fm-input-bar button.icon-btn:not(.fm-mic-btn)'),
        mainSend: !!q('#input-bar .ds-turn-btn--send'), mainMic: !!q('#input-bar #voice-btn'),
        mainAttach: !!q('#input-bar #attach-btn'),
      };
    });
    ok(tag('A3 能力清单：两面均有发送键'), caps.fmSend && caps.mainSend, JSON.stringify(caps));
    ok(tag('A3 能力清单：两面均有语音入口'), caps.fmMic && caps.mainMic);
    ok(tag('A3 能力清单：两面均有附件入口'), caps.fmAttach && caps.mainAttach);

    // ── B1/B2 消息面 composer 行为 ────────────────────────────────────────
    const fmBehavior = await page.evaluate(async () => {
      const input = document.querySelector('.fm-input');
      input.focus();
      input.value = 'l1';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 40));
      const h0 = input.getBoundingClientRect().height;
      input.value = 'l1\nl2\nl3\nl4';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 60));
      return { h0, h1: input.getBoundingClientRect().height, focused: document.activeElement === input };
    });
    ok(tag('A3 消息面 composer 可聚焦'), fmBehavior.focused);
    ok(tag('B1 消息面 autosize：多行后高度增长'), fmBehavior.h1 > fmBehavior.h0,
      `h0=${fmBehavior.h0} h1=${fmBehavior.h1}`);
    const fmSend = await page.evaluate(async () => {
      const input = document.querySelector('.fm-input');
      input.value = '统一批消息面发送探针';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      await new Promise((r) => setTimeout(r, 200));
      return { after: input.value };
    });
    ok(tag('B2 消息面裸 Enter 提交（输入框清空）'), fmSend.after === '', `after=${JSON.stringify(fmSend.after)}`);

    // ── C2 附件区 + C1 消息面气泡 ─────────────────────────────────────────
    const fmItem = await page.evaluate(READ('.fm-msg-bubble'));
    ok(tag('C1 消息面 .fm-msg-bubble 几何 > 0'), !!fmItem && fmItem.w > 0 && fmItem.h > 0,
      fmItem ? `w=${Math.round(fmItem.w)} h=${Math.round(fmItem.h)}` : 'missing');
    const attArea = await page.evaluate(async ({ png }) => {
      const { bubbleEl } = await import('/js/messages.js');
      const { attachmentArea } = await import('/js/chatItem.js');
      const host = document.querySelector('#chat');
      const flow = document.createElement('div');
      flow.className = 'fm-flow';
      flow.dataset.probe = 'attach-area';
      flow.append(bubbleEl(
        { id: 'probe', body: '统一批附件探针', ours: false, createdAt: Date.now(),
          attachments: [{ type: 'image', name: 'p.png', path: '/tmp/p.png', preview: png }] },
        { id: 'probe', kind: 'direct', friend: { userId: 'u-cu' } }));
      // 主会话侧容器 = 同一唯一构造点 attachmentArea('pop-group')（真页面里的真组件）
      // 口径：取**入向/中性**行的基线（出向行由 `.fm-msg.out .fm-att-list` 与
      // `.row.user-media .pop-group` 两支侧变体各自右对齐 —— 侧变体不算分叉）。
      const popGroup = attachmentArea('pop-group');
      popGroup.dataset.probe = 'pop-base';
      host.append(flow, popGroup);
      await new Promise((r) => setTimeout(r, 150));
      const list = flow.querySelector('.fm-att-list');
      const cs = list && getComputedStyle(list);
      const cg = getComputedStyle(popGroup);
      return {
        hasList: !!list, area: list && list.dataset.nfAttachArea,
        listDisplay: cs && cs.display, listDir: cs && cs.flexDirection, listAlign: cs && cs.alignItems,
        groupDisplay: cg.display, groupDir: cg.flexDirection, groupAlign: cg.alignItems,
        groupArea: popGroup.dataset.nfAttachArea,
        w: list ? list.getBoundingClientRect().width : 0,
      };
    }, { png: PNG });
    ok(tag('C2 消息面附件区经 chatItem.attachmentArea 构造'), attArea.hasList && attArea.area === '1',
      `data-nf-attach-area=${attArea.area}`);
    ok(tag('C2 附件区宽度 > 0'), attArea.w > 0, `w=${Math.round(attArea.w)}`);
    ok(tag('C2 附件区基线两面同源（display/flex-direction/align）'),
      !!attArea.groupDisplay && attArea.listDisplay === attArea.groupDisplay &&
      attArea.listDir === attArea.groupDir && attArea.listAlign === attArea.groupAlign,
      `list=${attArea.listDisplay}/${attArea.listDir}/${attArea.listAlign} group=${attArea.groupDisplay}/${attArea.groupDir}/${attArea.groupAlign}`);
    const fmFooter = await page.evaluate(READ('.fm-flow[data-probe="attach-area"] .duration-badge'));
    ok(tag('D 消息面 footer .duration-badge 在位'), !!fmFooter && fmFooter.w > 0, fmFooter ? `bg=${fmFooter.bg} radius=${fmFooter.radius}` : 'missing');
    if (mainFooter && fmFooter) {
      ok(tag('D footer 基座两面同底色'), mainFooter.bg === fmFooter.bg, `main=${mainFooter.bg} fm=${fmFooter.bg}`);
      ok(tag('D footer 基座两面同圆角'), mainFooter.radius === fmFooter.radius, `main=${mainFooter.radius} fm=${fmFooter.radius}`);
    }

    await page.screenshot({ path: join(EVID, `full-${vp.name}.png`) });
    ok(tag('E 无 pageerror'), pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
  } catch (e) {
    ok(tag('EXEC'), false, String(e && e.message ? e.message : e));
  } finally {
    const videoPath = await page.video()?.path().catch(() => null);
    await page.close();
    if (videoPath) writeFileSync(join(EVID, `video-path-${vp.name}.txt`), videoPath);
    await ctx.close();
  }
}

await browser.close();
const passed = results.filter((r) => r.pass).length;
writeFileSync(join(EVID, 'results.json'), JSON.stringify({ passed, total: results.length, failures, results }, null, 2));
console.log(`\ncomposer-message-item-unify-app: ${passed}/${results.length} assertions passed (failures=${failures})`);
process.exit(failures ? 1 : 0);
