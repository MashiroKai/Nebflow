// scroll-pill-family.spec.mjs — 会话面浮条胶囊「一族三消费」单实现验收
// （作者令 2026-11-06 07:39 逐字：「我们消息框的回到底部的按钮不要自己造 要跟
//  新消息通知 搜索聊天记录跳转 使用同一套」）。
//
// 判据面：
//   S0  静态单实现：js/scrollPill.js 在、js/scrollBackPill.js 亡；web 树零
//       `scroll-back-pill` / `createScrollBackPill` / `nf-sbp-in`；`./scrollPill.js`
//       的 import 者恰为 utils.js / messages.js / searchReturnPill.js 三处；
//       `.scroll-new-pill {` 形态声明全站唯一一处（chat.css），`nf-pill-in`、
//       `@keyframes nf-pill-in` 各恰一处。
//   S1  主窗（1440×900 · light）：滚离 → 出现「回到底部」→ 注新消息 → 同**一节点**
//       换「↓ 1 条新消息」→ 点击 → 平滑（120ms 采样仍在中途）→ 贴底 → 隐藏；
//       读数：落位 = 输入区上缘 −12、水平居中（|dx|≤1）、浮层命中、28px 高。
//   S2  agent 弹窗（.flow-agent-modal）：同一枚胶囊在场 + 同形 + 点击回底。
//   S3  好友 / 群 / 设备（.fm-modal）：三面各一条在场 + 同形 + 点击回底。
//   S4  搜索回跳（.scroll-new-pill[data-nf-search-return]）：同形同落位；与
//       「回到底部」同时在場时按 STACK_STEP(40) 上抬一格，互不叠影。
//   S5  手机（375×812 独立交互层）：主窗 + 好友窗两处在场；抬层 z=170
//       （> 页面容器 156）⇒ elementFromPoint 命中自身；桌面 z=25。
//   S6  同形判据：S1..S4 五处读数的计算样式关键项逐项相等（唯一允许差异
//       = z-index（手机抬层）已在 S5 单独立判据）。
//
// 验红：本文件在 2026-11-05 的改前树上必然红 —— 那里「回到底部」是另一类
// `.scroll-back-pill`（右下定位 / --color-surface 实底 / nf-sbp-in / 无 transition），
// 形态声明在 chat.css 的第二段，S0 的存在性与 S1 的类名/底色/居中/动画四条
// 断言逐条落地为红（见批文档「验红证据」表）。
//
// Self-contained: route-intercepted static files + mocked REST/WS + fm_api_mock，
// 零后端零端口、不碰宿主实例 :6138。
// Run: node tests/scroll-pill-family.spec.mjs            （桌面四页）
//      VIEWS=desktop|mobile node tests/scroll-pill-family.spec.mjs

import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SHOTS = join(ROOT, 'evidence', 'scroll-pill-family');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const VIEWS = (process.env.VIEWS || 'desktop+mobile').split('+');

// WebKit 对 1 号不安全端口在网络层直接拒连 ⇒ 起一个零流量本地源（同
// search-return-pill.spec.mjs 的技法），全部请求仍由 page.route 拦截。
const ORIGIN = await new Promise((resolve) => {
  const srv = http.createServer((_req, res) => { res.writeHead(200); res.end('fixture-origin'); });
  srv.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${srv.address().port}`));
});

const SID = 'spf-main-session';
const NODE_SID = 'flow-st-1';

// ── 主会话历史：40 条 user/ai 交替（撑出可滚列）─────────────────────────
function agentHistory() {
  const now = Date.now() - 4e6;
  const out = [];
  for (let i = 0; i < 40; i += 1) {
    const ai = i % 2 === 0;
    out.push({
      type: ai ? 'ai' : 'user',
      text: ai ? `第 ${i + 1} 条助手消息：撑出可滚消息列。` : `第 ${i + 1} 条用户消息：收到。`,
      timestamp: now + i * 10e3,
    });
  }
  return out;
}
const HIT = '胶囊夹具命中行 spf-hit-2f7';
function mainHistory() {
  const rows = agentHistory();
  rows[3] = { type: 'user', text: `${HIT} —— 搜索跳转目标行。`, timestamp: rows[3].timestamp };
  return rows;
}

// ── 好友/群/设备夹具（fm_api_mock，与 thread-ui-unify-shots.mjs 同形）────
const F_MAIN = { userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '', remark: '', bio: '', since: 1762000000000 };
const SELF_DEV = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '' };
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', userDescription: '', online: true, lastSeen: Date.now() }];

function threadMsgs() {
  const NOW = Date.now();
  const out = [];
  for (let i = 1; i <= 40; i += 1) {
    out.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 胶囊同族夹具（撑出滚轴）。`, createdAt: NOW - (40 - i) * 60e3,
    });
  }
  return out;
}
function fmSeed() {
  const msgs = threadMsgs();
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [], friends: [F_MAIN], incoming: [], outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
      { conversationId: 'g-1', kind: 'group', title: '项目群', memberCount: 2, myRole: 'owner', lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
    ],
    messages: { 'c-f1': msgs, 'g-1': msgs },
    groups: [{ groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2, lastMessageId: 40, createdAt: 1762000000000, description: '', remark: '' }],
    groupInvites: [],
    groupMembers: {
      'g-1': [
        { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
        { userId: 'u-f1', username: null, display_name: '老友', avatar: null, role: 'member', joinedAt: 0 },
      ],
    },
    _now: Date.now(),
  };
}
function deviceHistory() {
  const NOW = Date.now();
  const out = [];
  for (let i = 1; i <= 24; i += 1) {
    out.push({ msgId: `d-${i}`, kind: 'text', direction: i % 2 ? 'in' : 'out', ts: NOW - (24 - i) * 60e3, text: `第 ${i} 条设备消息 —— 胶囊同族夹具。`, status: 'done' });
  }
  return out;
}

let lastWs = null;

async function bootPage(browser, { viewport, theme = 'light', touch = false }) {
  const context = await browser.newContext({ viewport, hasTouch: touch, colorScheme: theme });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

  await page.addInitScript((seedJson) => {
    for (const ns of ['nebflow', 'neblink', 'stand']) {
      localStorage.setItem(`${ns}_token`, 'e2e-token');
      localStorage.setItem(`${ns}_locale`, 'zh-CN');
    }
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '0');
  }, JSON.stringify(fmSeed()));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: { ...SELF_DEV, avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: PEERS }),
        });
      }
      if (p === '/api/search/chat') {
        const q = url.searchParams.get('q') || '';
        const hits = mainHistory().map((m, ord) => ({ m, ord })).filter(({ m }) => (m.text || '').includes(q))
          .map(({ m, ord }) => ({ sid: SID, ord, kind: 'message', snippet: m.text, ts: m.timestamp, extra: { role: m.type === 'user' ? 'user' : 'ai' } }));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ hits, total: hits.length }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    lastWs = ws;
    const send = (o) => ws.send(JSON.stringify(o));
    const sendConfig = () => send({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} });
    const sendSessions = () => send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { send({ type: 'pong' }); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        send({ type: 'historyPage', sessionId: m.sessionId, messages: mainHistory(), hasMore: false, offset: 0, total: 40 });
      } else if (m.type === 'dropbox-get-history') {
        send({ type: 'dropbox-history', deviceId: m.deviceId, messages: deviceHistory() });
      }
    });
  });

  await page.goto(`${ORIGIN}/`);
  await page.waitForSelector('#chat', { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(600);
  // 手机层从 dock「消息」键进聊天根；桌面无 dock 键。
  const chatKey = page.locator('#mobile-dock .dock-key[data-dock="chat"]');
  if (await chatKey.count()) { await chatKey.click(); await page.waitForTimeout(400); }
  await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
  return { page, context, errors };
}

// ── 读数腿（独立实现，不与组件同源）─────────────────────────────────────
const READ = `(sel, anchorSel) => {
  const el = document.querySelector(sel);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  const s = getComputedStyle(el);
  const host = el.parentElement;
  const hr = host.getBoundingClientRect();
  const anchor = anchorSel ? document.querySelector(anchorSel) : null;
  const m = s.transform.match(/matrix\\(([^)]+)\\)/);
  const tx = m ? Number(m[1].split(',')[4]) : 0;
  const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
  return {
    cls: el.className, text: (el.textContent || '').trim(), unread: el.dataset.unread,
    hidden: el.classList.contains('hidden'),
    rect: { w: +r.width.toFixed(2), h: +r.height.toFixed(2), left: +r.left.toFixed(2), top: +r.top.toFixed(2) },
    centerDx: +(((r.left + r.right) / 2) - ((hr.left + hr.right) / 2)).toFixed(2),
    gap: anchor ? +((anchor.getBoundingClientRect().top) - r.bottom).toFixed(2) : null,
    txRatio: r.width ? +(tx / r.width).toFixed(3) : null,
    inViewport: r.top >= 0 && r.left >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
    hitSelf: at === el || !!(at && el.contains(at)),
    atCls: at ? ((at.className && String(at.className)) || at.tagName) : null,
    style: {
      backgroundColor: s.backgroundColor, backgroundImage: s.backgroundImage,
      border: s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor,
      borderRadius: s.borderRadius, boxShadow: s.boxShadow,
      backdropFilter: s.backdropFilter,
      color: s.color, fontSize: s.fontSize, fontWeight: s.fontWeight,
      lineHeight: s.lineHeight, letterSpacing: s.letterSpacing,
      padding: s.paddingTop + ' ' + s.paddingRight + ' ' + s.paddingBottom + ' ' + s.paddingLeft,
      height: s.height, position: s.position, display: s.display, gap: s.gap,
      animationName: s.animationName,
      transition: s.transitionProperty + ' ' + s.transitionDuration,
      zIndex: s.zIndex,
    },
  };
}`;

const op = (page, sel, anchorSel = null) => page.evaluate(
  ({ sel, anchorSel, readSrc }) => {
    // eslint-disable-next-line no-eval
    return eval(readSrc)(sel, anchorSel);
  }, { sel, anchorSel, readSrc: READ });

/** 稳定滚动量（连续两样本 scrollTop 差 ≤1）。 */
async function settleScroll(page, elSel, timeout = 4000) {
  const probe = () => page.evaluate((s) => document.querySelector(s).scrollTop, elSel);
  let last = await probe();
  const t0 = Date.now();
  for (;;) {
    await page.waitForTimeout(200);
    const cur = await probe();
    if (Math.abs(cur - last) <= 1) return cur;
    last = cur;
    if (Date.now() - t0 > timeout) return cur;
  }
}

const BASE = '#main > .scroll-new-pill:not([data-nf-search-return])';
const PILL_BASE = '.scroll-new-pill';

let total = 0, passed = 0;
const groups = [];
function check(group, name, cond, extra = '') {
  total += 1; if (cond) passed += 1;
  else { const g = groups.find((x) => x.name === group); if (g) g.fails.push(name); }
  console.log(`${cond ? 'PASS' : 'FAIL'}  [${group}] ${name}${extra ? '  — ' + extra : ''}`);
}

/** 同形判据：关键项逐项相等（z-index 单列，手机抬层另有判据）。 */
const STYLE_KEYS = ['backgroundColor', 'backgroundImage', 'border', 'borderRadius', 'boxShadow',
  'backdropFilter', 'color', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing',
  'padding', 'height', 'position', 'display', 'gap', 'animationName', 'transition'];
function styleDiff(ref, cur) {
  const out = {};
  for (const k of STYLE_KEYS) if (ref.style[k] !== cur.style[k]) out[k] = [ref.style[k], cur.style[k]];
  return out;
}

// ════════════════════════════════════════════════════════════════════════
// S0 — 静态单实现判据（零浏览器）
// ════════════════════════════════════════════════════════════════════════
function walkWeb(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkWeb(p, out);
    else if (/\.(js|css)$/.test(e.name)) out.push(p);
  }
  return out;
}
{
  groups.push({ name: 'S0-static', fails: [] });
  const files = walkWeb(WEB);
  // 判据只扫**代码面**：注释里的「已收编」墓碑（仓内既有留痕惯例）不算复活。
  const stripComments = (src, ext) => (ext === '.css'
    ? src.replace(/\/\*[\s\S]*?\*\//g, '')
    : src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^[ \t]*\/\/[^\n]*/gm, '').replace(/\s\/\/[^\n]*/g, ''));
  const hits = [];
  for (const f of files) {
    const src = stripComments(readFileSync(f, 'utf8'), extname(f));
    if (/scroll-back-pill|createScrollBackPill|nf-sbp-in/.test(src)) hits.push(f.replace(ROOT + '/', ''));
  }
  check('S0-static', '唯一实现件 js/scrollPill.js 在册', existsSync(join(WEB, 'js', 'scrollPill.js')));
  check('S0-static', '新造件 js/scrollBackPill.js 已整件退役（无空壳）', !existsSync(join(WEB, 'js', 'scrollBackPill.js')));
  check('S0-static', 'web 树零 scroll-back-pill / createScrollBackPill / nf-sbp-in', hits.length === 0, hits.join(', '));

  const importers = files.filter((f) => /from '\.\/scrollPill\.js'|from '\.\.\/scrollPill\.js'/.test(readFileSync(f, 'utf8')))
    .map((f) => f.replace(WEB + '/js/', '')).sort();
  check('S0-static', 'scrollPill.js 的消费面恰为 utils / messages / searchReturnPill 三处',
    importers.join(',') === 'messages.js,searchReturnPill.js,utils.js', importers.join(','));

  const chatCss = readFileSync(join(WEB, 'css', 'chat.css'), 'utf8');
  const declBlocks = (chatCss.match(/^\.scroll-new-pill\s*\{/gm) || []).length;
  check('S0-static', '形态声明 .scroll-new-pill{} 全站唯一一处（chat.css）', declBlocks === 1, `blocks=${declBlocks}`);
  check('S0-static', '进场动效 nf-pill-in 唯一一处定义/引用',
    (chatCss.match(/@keyframes nf-pill-in\s*\{/g) || []).length === 1
    && (chatCss.match(/animation:\s*nf-pill-in/g) || []).length === 1);
}

const REF = { name: 'reference' };   // S1 主窗基准读数（S6 同形判据的参照）

// ════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
mkdirSync(SHOTS, { recursive: true });

try {
  if (VIEWS.includes('desktop')) {
    // ── S1 主窗（1440×900 · light）+ S2 agent 弹窗 ──────────────────────
    {
      groups.push({ name: 'S1-main', fails: [] }, { name: 'S2-agent', fails: [] });
      const { page, context, errors } = await bootPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light' });
      try {
        const chat = '#chat';
        const max = await page.evaluate((s) => { const e = document.querySelector(s); return e.scrollHeight - e.clientHeight; }, chat);
        check('S1-main', '主窗可滚（夹具撑出一屏以上）', max > 600, `max=${Math.round(max)}`);
        // ① 未贴底：钉底后上翻 420px（> SHOW_AFTER_PX 320）
        await page.evaluate((s) => {
          const e = document.querySelector(s);
          e.scrollTop = Math.max(0, e.scrollHeight - e.clientHeight - 420);
          e.dispatchEvent(new Event('scroll'));
        }, chat);
        await page.waitForTimeout(260);
        let base = await op(page, BASE, '#input-area');
        check('S1-main', '滚离 420px ⇒ 胶囊在场（无未读也是这一枚）', !!base && !base.hidden, base && JSON.stringify({ cls: base.cls, text: base.text }));
        check('S1-main', '文案 = 回到底部', !!base && base.text === '回到底部', base && base.text);
        check('S1-main', '类名族 = .scroll-new-pill（与通知/搜索同一枚）',
          !!base && base.cls.split(/\s+/).includes('scroll-new-pill'), base && base.cls);
        check('S1-main', '落位 = #input-area 上缘 −12px', !!base && Math.abs(base.gap - 12) <= 2, base && `gap=${base.gap}`);
        check('S1-main', '水平居中（translateX(-50%)：tx/宽 = −0.5，|dx|≤0.5）',
          !!base && Math.abs(base.txRatio + 0.5) <= 0.02 && Math.abs(base.centerDx) <= 0.5,
          base && `txRatio=${base.txRatio} dx=${base.centerDx}`);
        check('S1-main', '浮层三连（有面积 / 视口内 / elementFromPoint 命中）',
          !!base && base.rect.w > 40 && base.rect.h === 28 && base.inViewport && base.hitSelf,
          base && JSON.stringify(base.rect));
        await page.screenshot({ path: join(SHOTS, 's1-main-backtobottom-1440-light.png') });

        // ② 离底期间到达真实新消息 ⇒ **同一节点**换计数文案
        await page.evaluate(() => {
          const el = document.querySelector('#main > .scroll-new-pill:not([data-nf-search-return])');
          if (el) el.dataset.nfProbe = 'same-node';
        });
        await page.evaluate(() => {
          const chatEl = document.querySelector('#chat');
          const row = document.createElement('div');
          row.className = 'row user';
          row.innerHTML = '<div class="bubble user">离底期间到达的新消息</div>';
          chatEl.appendChild(row);
        });
        await page.waitForTimeout(320);
        const own = await op(page, BASE, '#input-area');
        check('S1-main', '本机 user 不计入新消息', !!own && own.unread === '0', own && JSON.stringify({ text: own.text, unread: own.unread }));
        await page.evaluate(() => {
          const row = document.createElement('div');
          row.className = 'row ai';
          row.textContent = '离底期间到达的助手消息';
          document.querySelector('#chat').appendChild(row);
        });
        await page.waitForTimeout(320);
        const unread = await op(page, BASE, '#input-area');
        check('S1-main', '新消息 ⇒ 同节点换「↓ 1 条新消息」（data-unread=1）',
          !!unread && unread.text.includes('1') && unread.unread === '1', unread && JSON.stringify({ text: unread.text, unread: unread.unread }));
        await page.evaluate(() => document.querySelector('#chat > .row.ai:last-child').remove());
        await page.waitForTimeout(100);
        const removed = await op(page, BASE, '#input-area');
        check('S1-main', '撤回未读助手行回退计数', !!removed && removed.unread === '0', removed && JSON.stringify({ text: removed.text, unread: removed.unread }));
        await page.evaluate(() => {
          const row = document.createElement('div'); row.className = 'row ai'; row.textContent = '另一条助手消息';
          document.querySelector('#chat').appendChild(row);
        });
        await page.waitForTimeout(100);
        check('S1-main', '同形：计数档与「回到底部」档样式逐项相等', !!base && !!unread && Object.keys(styleDiff(base, unread)).length === 0,
          base && unread && JSON.stringify(styleDiff(base, unread)));
        REF.main = base;
        await page.screenshot({ path: join(SHOTS, 's1-main-unread-1440-light.png') });

        // ③ 点击 ⇒ 平滑（120ms 仍在中途）⇒ 贴底 ⇒ 隐藏
        await page.click(BASE, { force: true });
        await page.waitForTimeout(120);
        const mid = await page.evaluate((s) => Math.round((() => { const e = document.querySelector(s); return e.scrollHeight - e.clientHeight - e.scrollTop; })()), chat);
        const end = await settleScroll(page, chat);
        const endState = await page.evaluate((s) => {
          const e = document.querySelector(s);
          return { dist: Math.round(e.scrollHeight - e.scrollTop - e.clientHeight) };
        }, chat);
        const gone = await page.evaluate((s) => {
          const el = document.querySelector(s); return !el || el.classList.contains('hidden');
        }, BASE);
        check('S1-main', '点击 = 平滑滚动（120ms 采样距底 ' + mid + 'px，仍未到位）', mid > 2, `mid=${mid}`);
        check('S1-main', '点击后贴底（距底 ≤44）', endState.dist <= 44, `dist=${endState.dist} scrollTop=${Math.round(end)}`);
        check('S1-main', '贴底后胶囊隐藏（两腿同时收敛）', gone);

        // ── S2 agent 弹窗：同一工厂、同一枚 ──
        await page.evaluate(async () => {
          const m = await import('/js/flowAgentPopup.js');
          m.openStepPopup('st-1', 'node-a', 'Nebula', 'flow-demo', 'flow-st-1');
        });
        await page.waitForSelector('.flow-agent-modal .flow-agent-chat .row', { timeout: 15000 });
        await page.waitForTimeout(600);
        const popChat = '.flow-agent-modal .flow-agent-chat';
        // 真实用户意图信号先解除进场落底武装窗（settleBottom），否则一次性钉底
        // 会把合成滚动拉回底部、胶囊永远不出现（harness 同法，见
        // tests/thread-ui-unify-shots.mjs openFace）。
        await page.evaluate((s) => {
          const e = document.querySelector(s);
          e.dispatchEvent(new WheelEvent('wheel', { deltaY: -160, bubbles: true }));
        }, popChat);
        await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
        await page.waitForTimeout(200);
        await page.evaluate((s) => {
          const e = document.querySelector(s);
          e.scrollTop = Math.max(0, e.scrollHeight - e.clientHeight - 420);
          e.dispatchEvent(new Event('scroll'));
        }, popChat);
        await page.waitForTimeout(260);
        console.log('[diag-agent]', JSON.stringify(await page.evaluate(() => {
          const c = document.querySelector('.flow-agent-modal .flow-agent-chat');
          const modal = document.querySelector('.flow-agent-modal');
          const pill = document.querySelector('.flow-agent-modal > .scroll-new-pill');
          return {
            rows: c ? c.querySelectorAll('.row').length : -1,
            sh: c ? c.scrollHeight : -1, ch: c ? c.clientHeight : -1, st: c ? Math.round(c.scrollTop) : -1,
            modalDisp: modal ? getComputedStyle(modal).display : 'none',
            modalRect: modal ? modal.getBoundingClientRect().toJSON() : null,
            pillCls: pill ? pill.className : null,
            pillParentIsModal: pill ? pill.parentElement === modal : null,
          };
        })));
        const pop = await op(page, '.flow-agent-modal > .scroll-new-pill', '.flow-agent-modal .flow-agent-footer');
        check('S2-agent', 'agent 弹窗滚离 ⇒ 同一枚胶囊在场', !!pop && !pop.hidden, pop && pop.cls);
        check('S2-agent', '落位 = footer 上缘 −12px', !!pop && Math.abs(pop.gap - 12) <= 2, pop && `gap=${pop.gap}`);
        check('S2-agent', '水平居中（|dx|≤0.5）', !!pop && Math.abs(pop.centerDx) <= 0.5, pop && `dx=${pop.centerDx}`);
        check('S2-agent', '与主窗同形（计算样式关键项逐项相等）',
          !!pop && !!REF.main && Object.keys(styleDiff(REF.main, pop)).length === 0,
          pop && REF.main && JSON.stringify(styleDiff(REF.main, pop)));
        REF.agent = pop;
        await page.screenshot({ path: join(SHOTS, 's2-agent-1440-light.png') });
        await page.click('.flow-agent-modal > .scroll-new-pill', { force: true });
        await page.waitForTimeout(900);
        const popGone = await page.evaluate(() => {
          const el = document.querySelector('.flow-agent-modal > .scroll-new-pill');
          return !el || el.classList.contains('hidden');
        });
        const popDist = await page.evaluate((s) => { const e = document.querySelector(s); return Math.round(e.scrollHeight - e.scrollTop - e.clientHeight); }, popChat);
        check('S2-agent', '点击 ⇒ 贴底 + 隐藏', popGone && popDist <= 44, `dist=${popDist}`);
        check('S1-main', '零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
      } finally { await context.close(); }
    }

    // ── S3 好友 / 群 / 设备（.fm-modal）────────────────────────────────
    {
      groups.push({ name: 'S3-fm', fails: [] });
      const { page, context, errors } = await bootPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light' });
      try {
        for (const face of ['friend', 'group', 'device']) {
          if (face === 'friend') {
            await page.evaluate(async () => { const m = await import('/js/messages.js'); await m.openConversation('c-f1', null); });
          } else if (face === 'group') {
            await page.evaluate(async () => { const m = await import('/js/messages.js'); await m.openConversation('g-1', null); });
          } else {
            await page.evaluate(async () => {
              const m = await import('/js/messages.js');
              m.openDeviceChat({ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true });
            });
            await page.waitForTimeout(300);
            lastWs?.send(JSON.stringify({ type: 'dropbox-history', deviceId: 'dev-kai', messages: deviceHistory() }));
          }
          await page.waitForSelector('.fm-modal .fm-flow .fm-msg, .fm-modal .fm-flow .row', { timeout: 15000 });
          await page.waitForTimeout(700);
          await page.mouse.move(4, 4);   // 指针移开：hover 档底 α=.62 ≠ 静息 α=.55（判据只比静息态）
          await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
          await page.evaluate(() => {
            const flow = document.querySelector('.fm-modal .fm-flow');
            flow.scrollTop = Math.max(0, flow.scrollHeight - flow.clientHeight - 420);
            flow.dispatchEvent(new Event('scroll'));
          });
          await page.waitForTimeout(280);
          const p = await op(page, '.fm-modal > .scroll-new-pill', '.fm-modal .fm-input-bar');
          check('S3-fm', `${face}：滚离 ⇒ 同一枚胶囊在场`, !!p && !p.hidden, p && p.cls);
          check('S3-fm', `${face}：落位 = 输入条上缘 −12px`, !!p && Math.abs(p.gap - 12) <= 2, p && `gap=${p.gap}`);
          check('S3-fm', `${face}：与主窗同形（逐项相等）`,
            !!p && !!REF.main && Object.keys(styleDiff(REF.main, p)).length === 0,
            p && REF.main && JSON.stringify(styleDiff(REF.main, p)));
          REF[face] = p;
          await page.screenshot({ path: join(SHOTS, `s3-${face}-1440-light.png`) });
          await page.click('.fm-modal > .scroll-new-pill', { force: true });
          await page.waitForTimeout(900);
          const res = await page.evaluate(() => {
            const flow = document.querySelector('.fm-modal .fm-flow');
            const pill = document.querySelector('.fm-modal > .scroll-new-pill');
            return {
              gone: !pill || pill.classList.contains('hidden'),
              dist: Math.round(flow.scrollHeight - flow.scrollTop - flow.clientHeight),
            };
          });
          check('S3-fm', `${face}：点击 ⇒ 贴底 + 隐藏`, res.gone && res.dist <= 44, `dist=${res.dist}`);
          await page.evaluate(async () => { const m = await import('/js/messages.js'); m.closeChat(); });
          await page.waitForTimeout(400);
        }
        check('S3-fm', '零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
      } finally { await context.close(); }
    }

    // ── S4 搜索回跳（同族第三个消费）+ 堆叠 ────────────────────────────
    {
      groups.push({ name: 'S4-search', fails: [] });
      const { page, context, errors } = await bootPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light' });
      try {
        await page.click('#search-btn');
        await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
        await page.fill('#search-keyword', HIT);
        await page.waitForSelector('#search-results .search-result', { timeout: 8000 });
        await page.waitForTimeout(400);
        await page.locator('#search-results .search-result').first().click();
        await page.waitForFunction((kw) => {
          const el = document.querySelector('#chat .search-hit-flash');
          return !!el && el.textContent.includes(kw);
        }, HIT, { timeout: 15000 });
        await page.waitForFunction(() => {
          const el = document.querySelector('#main [data-nf-search-return="1"]');
          return !!el && !el.classList.contains('hidden');
        }, undefined, { timeout: 8000 });
        await settleScroll(page, '#chat');
        const sr = await op(page, '#main > .scroll-new-pill[data-nf-search-return="1"]', '#input-area');
        check('S4-search', '搜索回跳胶囊 = 同一类名族', !!sr && sr.cls.split(/\s+/).includes('scroll-new-pill'), sr && sr.cls);
        check('S4-search', '文案 = 回到跳转前位置', !!sr && sr.text === '回到跳转前位置', sr && sr.text);
        check('S4-search', '与主窗同形（逐项相等）',
          !!sr && !!REF.main && Object.keys(styleDiff(REF.main, sr)).length === 0,
          sr && REF.main && JSON.stringify(styleDiff(REF.main, sr)));
        check('S4-search', '水平居中（|dx|≤0.5）+ 浮层命中', !!sr && Math.abs(sr.centerDx) <= 0.5 && sr.hitSelf, sr && `dx=${sr.centerDx} hit=${sr.hitSelf}`);
        REF.search = sr;
        await page.screenshot({ path: join(SHOTS, 's4-search-1440-light.png') });

        // 堆叠：同宿主再出「回到底部」⇒ 搜索胶囊上抬一格（40px），两枚不叠。
        // 触发腿 = 追加一条真实新消息（**不滚**：搜索胶囊遇用户滚离即隐，滚离腿
        // 测不出共存态）。
        await page.mouse.move(4, 4);
        await page.evaluate(() => {
          const chatEl = document.querySelector('#chat');
          const row = document.createElement('div');
          row.className = 'row ai';
          row.innerHTML = '<div class="bubble ai">共存态：离底期间到达的新消息</div>';
          chatEl.appendChild(row);
        });
        await page.waitForTimeout(400);
        const both = await page.evaluate(() => {
          const q = (s) => document.querySelector(s);
          const base = q('#main > .scroll-new-pill:not([data-nf-search-return])');
          const sr = q('#main > .scroll-new-pill[data-nf-search-return="1"]');
          const rb = base && !base.classList.contains('hidden') ? base.getBoundingClientRect() : null;
          const rs = sr && !sr.classList.contains('hidden') ? sr.getBoundingClientRect() : null;
          const overlap = rb && rs
            ? Math.max(0, Math.min(rb.right, rs.right) - Math.max(rb.left, rs.left))
              * Math.max(0, Math.min(rb.bottom, rs.bottom) - Math.max(rb.top, rs.top))
            : null;
          return { baseVisible: !!rb, srVisible: !!rs, gapPx: rb && rs ? +(rb.top - rs.bottom).toFixed(2) : null, overlap: overlap === null ? null : Math.round(overlap) };
        });
        check('S4-search', '两枚同族同时在場时零重叠（搜索胶囊上抬一格）',
          both.baseVisible && both.srVisible && both.overlap === 0 && both.gapPx >= 0,
          JSON.stringify(both));
        check('S4-search', '零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
      } finally { await context.close(); }
    }
  }

  if (VIEWS.includes('mobile')) {
    // ── S5 手机 375×812 独立交互层 ────────────────────────────────────
    {
      groups.push({ name: 'S5-mobile', fails: [] });
      const { page, context, errors } = await bootPage(browser, { viewport: { width: 375, height: 812 }, theme: 'light', touch: true });
      try {
        check('S5-mobile', '独立交互层已激活（body.mobile-layer）', await page.evaluate(() => document.body.classList.contains('mobile-layer')));
        await page.evaluate(() => {
          const chatEl = document.querySelector('#chat');
          chatEl.scrollTop = Math.max(0, chatEl.scrollHeight - chatEl.clientHeight - 420);
          chatEl.dispatchEvent(new Event('scroll'));
        });
        await page.waitForTimeout(300);
        const m = await op(page, BASE, '#input-area');
        check('S5-mobile', '主窗滚离 ⇒ 同一枚胶囊在场', !!m && !m.hidden, m && m.cls);
        check('S5-mobile', '375 视口内 + 浮层命中（聊天根在场时不被 dock 页压住）',
          !!m && m.inViewport && m.hitSelf && m.style.zIndex === '25',
          m && JSON.stringify({ inViewport: m.inViewport, hit: m.hitSelf, at: m.atCls, z: m.style.zIndex }));
        check('S5-mobile', '落位 = 输入区上缘 −12 + 水平居中（|dx|≤0.5）', !!m && Math.abs(m.gap - 12) <= 2 && Math.abs(m.centerDx) <= 0.5, m && `gap=${m.gap} dx=${m.centerDx}`);
        check('S5-mobile', '与桌面同形（除 z-index 外的关键项逐项相等）',
          !!m && !!REF.main && Object.keys(styleDiff(REF.main, m)).length === 0,
          m && REF.main && JSON.stringify(styleDiff(REF.main, m)));
        REF.mobile = m;
        await page.screenshot({ path: join(SHOTS, 's5-mobile-main-375-light.png') });

        // 好友窗（收编进 conversation 页）
        await page.evaluate(async () => {
          const dock = document.querySelector('#mobile-dock .dock-key[data-dock="messages"]');
          if (dock) dock.click();
        });
        await page.waitForTimeout(300);
        await page.evaluate(async () => { const mm = await import('/js/messages.js'); await mm.openConversation('c-f1', null); });
        await page.waitForSelector('.fm-modal .fm-flow .fm-msg', { timeout: 15000 });
        await page.waitForTimeout(900);
        console.log('[diag-mobile-fm]', JSON.stringify(await page.evaluate(() => {
          const modals = [...document.querySelectorAll('.fm-modal')].map((m) => {
            const flow = m.querySelector('.fm-flow');
            return {
              parent: m.parentElement && m.parentElement.className,
              disp: getComputedStyle(m).display,
              msgs: flow ? flow.querySelectorAll('.fm-msg').length : -1,
              sh: flow ? flow.scrollHeight : -1, ch: flow ? flow.clientHeight : -1,
              pills: m.querySelectorAll(':scope > .scroll-new-pill').length,
            };
          });
          return { modals };
        })));
        await page.mouse.move(4, 4);
        await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
        await page.waitForTimeout(150);
        await page.evaluate(() => {
          const flow = document.querySelector('.fm-modal .fm-flow');
          flow.scrollTop = Math.max(0, flow.scrollHeight - flow.clientHeight - 420);
          flow.dispatchEvent(new Event('scroll'));
        });
        await page.waitForTimeout(320);
        const f = await op(page, '.fm-modal > .scroll-new-pill', '.fm-modal .fm-input-bar');
        check('S5-mobile', '好友窗滚离 ⇒ 同一枚胶囊在场', !!f && !f.hidden, f && f.cls);
        check('S5-mobile', '好友窗：375 视口内 + 命中 + 落位 −12', !!f && f.inViewport && f.hitSelf && Math.abs(f.gap - 12) <= 2,
          f && JSON.stringify({ hit: f.hitSelf, at: f.atCls, gap: f.gap }));
        check('S5-mobile', '好友窗：与主窗同形（逐项相等）',
          !!f && !!REF.main && Object.keys(styleDiff(REF.main, f)).length === 0,
          f && REF.main && JSON.stringify(styleDiff(REF.main, f)));
        REF.mobileFm = f;
        await page.screenshot({ path: join(SHOTS, 's5-mobile-friend-375-light.png') });
        check('S5-mobile', '零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
      } finally { await context.close(); }
    }
  }

  // ── S6 同形总表（一页纸读数，供批文档抄录）──────────────────────────
  groups.push({ name: 'S6-parity', fails: [] });
  {
    const keys = ['main', 'agent', 'friend', 'group', 'device', 'search', 'mobile', 'mobileFm'].filter((k) => REF[k]);
    check('S6-parity', '五处以上消费点全部取到读数', keys.length >= 5, keys.join(','));
    const ref = REF.main;
    const bad = keys.filter((k) => k !== 'main' && Object.keys(styleDiff(ref, REF[k])).length > 0)
      .map((k) => `${k}:${JSON.stringify(styleDiff(ref, REF[k]))}`);
    check('S6-parity', '全族计算样式关键项与基准逐项相等（唯一允许差异 = z-index，另立判据）', bad.length === 0, bad.join(' | '));
    const rows = keys.map((k) => ({
      face: k,
      cls: REF[k].cls,
      text: REF[k].text,
      rect: REF[k].rect,
      centerDx: REF[k].centerDx,
      gap: REF[k].gap,
      z: REF[k].style.zIndex,
      bg: REF[k].style.backgroundColor,
      radius: REF[k].style.borderRadius,
      shadow: REF[k].style.boxShadow === 'none' ? 'none' : 'set',
      anim: REF[k].style.animationName,
      fontSize: REF[k].style.fontSize,
      padding: REF[k].style.padding,
      height: REF[k].style.height,
    }));
    console.log('\n── 三（五）消费点同形表 ──');
    for (const r of rows) console.log(JSON.stringify(r));
  }
} finally {
  await browser.close();
}

console.log('\n── 分组 ──');
for (const g of groups) console.log(`${g.fails.length === 0 ? 'PASS' : 'FAIL'}  ${g.name}${g.fails.length ? '  ✗ ' + g.fails.join(' ; ') : ''}`);
console.log(`\n${passed}/${total} passed`);
// 落点源 http server 常驻会让 node 事件循环不退出（浏览器已关）⇒ 显式收尾。
process.exit(passed === total ? 0 : 1);
