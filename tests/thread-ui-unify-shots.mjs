#!/usr/bin/env node
// thread-ui-unify-shots.mjs — 四种会话面（主 agent / 好友 / 群 / 设备）统一批实拍 + 几何读数。
//
// ── 零后端零端口 ─────────────────────────────────────────────────────────
//   `page.route` 从磁盘注入本仓 web 树（`WEB`）+ `routeWebSocket` 帧桩
//   （configData / sessionList / historyPage / dropbox-history）+
//   `fm_api_mock` 好友/群假 API ⇒ 不开服务、不碰宿主实例 :6138。
//
// ── 拍什么 ──────────────────────────────────────────────────────────────
//   四族 × 视口（375×812 手机层 / 1440×900 桌面）× 主题（dark / light）
//   每族一帧；文件名 `<states>-<族>-<w>x<h>-<theme>.png`。
//
// ── 量什么 ──────────────────────────────────────────────────────────────
//   `measure(page)` 按族返回逐元素 `getBoundingClientRect()` + 计算样式读数：
//     · 身份头头像（外径/圆角/字体）
//     · 左右两颗钮（宽高/圆角/落位）
//     · 输入区三键（发送 / 附件 / 话筒）
//     · 气泡（内距/圆角/字号/行高/底色/描边/阴影/最大宽）
//     · footer 元信息 pill（内距/圆角/阴影/时间字号）
//     · 文件卡 / 媒体卡（类名 + 卡内距/圆角）
//
// ── 回到底部胶囊（四种会话）──────────────────────────────────────────────
//   `pillCase(page, 族)`：滚离底部 ≥ 阈值 ⇒ 胶囊在场（含读数）；点击 ⇒ 贴底 +
//   胶囊消失；与输入框/既有悬浮钮相交面积为 0。
//
// Run:  node tests/thread-ui-unify-shots.mjs
//       SHOTS_WEB_ROOT=/tmp/before/web SHOTS_STATE=before node tests/thread-ui-unify-shots.mjs
//       SHOTS_ONLY=measure node tests/thread-ui-unify-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = process.env.SHOTS_WEB_ROOT
  ? normalize(process.env.SHOTS_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const STATE = process.env.SHOTS_STATE || 'after';
const ONLY = process.env.SHOTS_ONLY || '';
const OUT = process.env.SHOTS_OUT
  ? normalize(process.env.SHOTS_OUT)
  : join(ROOT, 'reports-evidence', `thread-ui-unify__${STATE}`);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SID = 'thread-ui-unify-session';
/** 当前页的 WS 桩句柄（设备面历史帧由本脚本主动补发，见 openFace）。 */
let lastWs = null;
const SELF_DEV = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '' };
const PEERS = [{
  deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', userDescription: '',
  online: true, lastSeen: Date.now(),
}];
const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: '', bio: '', since: 1762000000000,
};
const G_MAIN = {
  groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2,
  lastMessage: { id: 101, senderId: 'u-f1', kind: 'text', body: '今天开会', createdAt: 1762000000000 },
  unreadCount: 0, lastMessageId: 101, createdAt: 1762000000000, description: '', remark: '',
};

/** 主会话历史：24 轮 user/ai 交替（撑出可滚列）。 */
function agentHistory() {
  const now = Date.now();
  const out = [];
  for (let i = 0; i < 40; i += 1) {
    const ai = i % 2 === 0;
    out.push({
      type: ai ? 'ai' : 'user',
      text: ai
        ? `第 ${i + 1} 条助手消息：撑出可滚消息列，用于四种会话面同框比对。`
        : `第 ${i + 1} 条用户消息：收到。`,
      timestamp: now - 600e3 + i * 10e3,
    });
  }
  return out;
}

/** 好友 / 群消息：40 条（撑出滚轴），含一条文件附件消息。 */
function threadMsgs() {
  const NOW = Date.now();
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    const atts = i === 38
      ? [{ id: 'a-1', kind: 'file', name: '批次报告.pdf', size: 234567, state: 'ready' }]
      : undefined;
    msgs.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 四种会话面统一批实拍夹具，用于比对气泡与 footer。`,
      createdAt: NOW - (40 - i) * 60e3,
      ...(atts ? { attachments: atts } : {}),
    });
  }
  return msgs;
}

function seed() {
  const msgs = threadMsgs();
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [], friends: [F_MAIN], incoming: [], outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
      { conversationId: 'g-1', kind: 'group', title: G_MAIN.title, memberCount: 2, myRole: 'owner', lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
    ],
    messages: { 'c-f1': msgs, 'g-1': msgs },
    groups: [{ ...G_MAIN }],
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

/** 设备面历史（`dropbox-history` 帧的 messages）。 */
function deviceHistory() {
  const NOW = Date.now();
  const out = [];
  for (let i = 1; i <= 24; i += 1) {
    out.push({
      msgId: `d-${i}`, kind: 'text', direction: i % 2 ? 'in' : 'out',
      ts: NOW - (24 - i) * 60e3,
      text: `第 ${i} 条设备消息 —— 统一批实拍夹具。`,
      status: 'done',
    });
  }
  return out;
}

async function bootApp(page) {
  await page.addInitScript((seedJson) => {
    for (const ns of ['nebflow', 'neblink', 'stand']) {
      localStorage.setItem(`${ns}_token`, 'e2e-token');
      localStorage.setItem(`${ns}_locale`, 'zh-CN');
    }
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
  }, seed());

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (url.hostname !== 'localhost' && url.hostname !== '127.0.0.1') return route.continue();
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            loggedIn: true,
            device: { ...SELF_DEV, avatarUrl: '', email: 'me@example.com', displayName: '我自己' },
            peers: PEERS,
          }),
        });
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
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: agentHistory(),
          hasMore: false, offset: 0, total: 40,
        }));
      } else if (parsed.type === 'dropbox-get-history') {
        ws.send(JSON.stringify({
          type: 'dropbox-history', deviceId: parsed.deviceId, messages: deviceHistory(),
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForSelector('#chat', { timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(500);
}

/** 打开一族会话并落底（手机层先点 dock 的「消息」键进面板）。 */
async function openFace(page, kind) {
  if (kind === 'agent') {
    const chatKey = page.locator('#mobile-dock .dock-key[data-dock="chat"]');
    if (await chatKey.count()) { await chatKey.click(); await page.waitForTimeout(400); }
    // 拆进场落底武装窗（settleBottom 进驻窗每帧回底；真实用户意图事件解除）。
    await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
    await page.waitForTimeout(150);
    await page.evaluate(() => {
      const chat = document.querySelector('#chat');
      if (chat) { chat.scrollTop = chat.scrollHeight; chat.dispatchEvent(new Event('scroll')); }
    });
    await page.waitForTimeout(200);
    return;
  }
  const dock = page.locator('#mobile-dock .dock-key[data-dock="messages"]');
  if (await dock.count()) { await dock.click(); await page.waitForTimeout(200); }
  await page.evaluate(async (k) => {
    const m = await import('/js/messages.js');
    if (k === 'friend') await m.openConversation('c-f1', null);
    else if (k === 'group') await m.openConversation('g-1', null);
    else if (k === 'device') m.openDeviceChat({ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true });
  }, kind);
  await page.waitForTimeout(700);
  if (kind === 'device') {
    // 设备面历史 = WS `dropbox-history` 帧；开窗请求腿未必到达（桩环境），
    // 这里主动补发同一帧（与生产回包同形），保证实拍夹具可复现。
    await page.waitForTimeout(200);
    lastWs?.send(JSON.stringify({ type: 'dropbox-history', deviceId: 'dev-kai', messages: deviceHistory() }));
    await page.waitForTimeout(600);
    if (process.env.SHOTS_DEBUG) {
      const dbg = await page.evaluate(async () => {
        const dx = await import('/js/dropbox.js');
        return {
          inDropbox: (dx.deviceMessagesOf('dev-kai') || []).length,
          flowKids: document.querySelectorAll('.fm-flow > *').length,
        };
      });
      console.log('[dbg-device]', JSON.stringify(dbg));
    }
  }
  // 拆进场落底武装窗（真实用户意图事件）后钉底。
  await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
  await page.waitForTimeout(150);
  await page.evaluate(() => {
    const modal = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const flow = modal && modal.querySelector('.fm-flow');
    if (flow) { flow.scrollTop = flow.scrollHeight; flow.dispatchEvent(new Event('scroll')); }
    const chat = document.querySelector('#chat');
    if (chat) { chat.scrollTop = chat.scrollHeight; chat.dispatchEvent(new Event('scroll')); }
  });
  await page.waitForTimeout(300);
}

const READ = `(sel) => {
  const el = document.querySelector(sel);
  if (!el) return null;
  const b = el.getBoundingClientRect();
  const s = getComputedStyle(el);
  return {
    w: +b.width.toFixed(2), h: +b.height.toFixed(2),
    x: +b.left.toFixed(2), y: +b.top.toFixed(2),
    radius: s.borderRadius, font: s.fontSize, lh: s.lineHeight,
    pad: s.padding, bg: s.backgroundColor, border: s.borderTopWidth + ' ' + s.borderTopColor,
    shadow: s.boxShadow, maxW: s.maxWidth, gap: s.gap,
    cls: el.className,
  };
}`;

/** 四族逐元素读数（会话窗在手机层被收编进 `.mobile-page[data-page=conversation]`，
 *  桌面在 `#fm-chat-overlay` ⇒ 两处都找）。 */
async function measure(page, kind) {
  return page.evaluate(({ kind, readSrc }) => {
    // eslint-disable-next-line no-eval
    const read = eval(readSrc);
    const fmModal = () => document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const q = (sel) => (kind === 'agent' ? document.querySelector(sel) : (fmModal() && fmModal().querySelector(sel)));
    const out = {
      kind,
      mobileLayer: document.body.classList.contains('mobile-layer'),
      avatar: read0(q(kind === 'agent'
        ? '#agent-avatar-btn'
        : '.mh-idhead .mh-idhead-avatar > .fm-modal-avatar, .fm-modal-header .fm-modal-avatar')),
      avatarInner: read0(q(kind === 'agent' ? '#agent-avatar-btn' : '.fm-avatar')),
      namePill: read0(kind === 'agent'
        ? (document.querySelector('#session-name') || document.querySelector('.g2-chat-head .g2-title, #session-name'))
        : q('.fm-modal-name')),
      head: read0(kind === 'agent' ? document.querySelector('.g2-chat-head') : q('.mh-idhead')),
      leftBtn: read0(kind === 'agent' ? document.querySelector('.mh-chrome-btn[data-chrome="tasks"]') : document.querySelector('.mobile-back-btn')),
      rightBtn: read0(kind === 'agent' ? document.querySelector('.mh-chrome-btn[data-chrome="search"]') : q('.mh-sess-more')),
      sendBtn: read0(q(kind === 'agent' ? '#send-btn' : '.fm-send-btn')),
      attachBtn: read0(q(kind === 'agent' ? '#attach-btn' : '.fm-attach-btn')),
      micBtn: read0(q(kind === 'agent' ? '#voice-btn' : '.fm-mic-btn')),
      outBubble: read0(q(kind === 'agent' ? '#chat .row.user .bubble' : '.fm-msg.out .fm-msg-bubble')),
      inBubble: read0(q(kind === 'agent' ? '#chat .row:not(.user) .bubble.ai' : '.fm-msg.in .fm-msg-bubble')),
      metaPill: read0(q(kind === 'agent' ? '#chat .duration-badge' : '.fm-msg-meta')),
      metaTime: read0(q(kind === 'agent' ? '#chat .duration-badge-time' : '.fm-msg-time')),
      metaCopy: read0(q(kind === 'agent' ? '#chat .msg-copy' : '.fm-msg-act')),
      flow: read0(q(kind === 'agent' ? '#chat' : '.fm-flow')),
      inputBar: read0(q(kind === 'agent' ? '#input-bar' : '.fm-input-bar')),
      cardFile: read0(q(kind === 'agent' ? '#chat .file-card' : '.file-card')),
      cardMedia: read0(q(kind === 'agent' ? '#chat .pop-media-flat' : '.pop-media-flat')),
      pill: read0(q(kind === 'agent' ? '.scroll-back-pill' : '.scroll-back-pill')),
      // 状态气泡（液态光球）存在性 —— 四种会话面各自的「本面作用域」内计数：
      // 主会话 = #main；会话窗 = 其 .fm-modal（不含 #main）。话筒键同理。
      orb: kind === 'agent'
        ? document.querySelectorAll('#main .mic-orb-wrap, #main .orb-canvas, #main .css-orb').length
        : (fmModal() ? fmModal().querySelectorAll('.mic-orb-wrap, .orb-canvas, .css-orb').length : -1),
      mic: kind === 'agent'
        ? document.querySelectorAll('#main .fm-mic-btn').length
        : (fmModal() ? fmModal().querySelectorAll('.fm-mic-btn').length : -1),
    };
    function read0(el) {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return {
        w: +b.width.toFixed(2), h: +b.height.toFixed(2),
        x: +b.left.toFixed(2), y: +b.top.toFixed(2),
        radius: s.borderRadius, font: s.fontSize, lh: s.lineHeight,
        pad: s.padding, bg: s.backgroundColor,
        border: s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor,
        shadow: s.boxShadow, maxW: s.maxWidth, gap: s.gap,
        cls: typeof el.className === 'string' ? el.className : '',
      };
    }
    return out;
  }, { kind, readSrc: READ });
}

/** 回到底部胶囊（四会话同一判据，分四拍取读数）：
 *  ① 滚离 420px → 在场？几何/文案/重叠；② 未读注入 → 文案是否带计数；
 *  ③ 点击 → 平滑（120ms 采样仍在中途）+ 贴底；④ 贴底后消失？ */
const FM_SCROLL = `(k) => {
  const fm = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
    || document.querySelector('#fm-chat-overlay .fm-modal');
  return k === 'agent' ? document.querySelector('#chat') : (fm ? fm.querySelector('.fm-flow') : null);
}`;

async function shot(browser, { w, h, kind, theme, tag }) {
  const page = await browser.newPage({
    viewport: { width: w, height: h },
    hasTouch: w < 600,
    colorScheme: theme,
    locale: 'zh-CN',
  });
  try {
    await bootApp(page);
    await openFace(page, kind);
    const m = await measure(page, kind);
    const file = join(OUT, `${tag}-${kind}-${w}x${h}-${theme}.png`);
    await page.screenshot({ path: file });
    console.log(`[shot] ${kind} ${w}x${h} ${theme} → ${file}`);
    return { kind, w, h, theme, file, measure: m };
  } finally {
    await page.close();
  }
}

async function pillRead(page, kind) {
  return page.evaluate(async (k) => {
    const fm = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const scrollEl = k === 'agent' ? document.querySelector('#chat') : (fm ? fm.querySelector('.fm-flow') : null);
    if (!scrollEl) return { error: 'no scroll element' };
    const host = k === 'agent' ? document.querySelector('#main') : fm;
    const pill = host ? host.querySelector('.scroll-back-pill') : null;
    const read = () => {
      if (!pill) return null;
      const b = pill.getBoundingClientRect();
      const s = getComputedStyle(pill);
      return {
        text: (pill.textContent || '').trim(),
        unread: pill.dataset.unread,
        hidden: pill.classList.contains('hidden'),
        w: +b.width.toFixed(2), h: +b.height.toFixed(2),
        x: +b.left.toFixed(2), y: +b.top.toFixed(2),
        right: +(375 - b.right).toFixed(2),
        radius: s.borderRadius, bg: s.backgroundColor, backdrop: s.backdropFilter,
        shadow: s.boxShadow,
      };
    };
    return {
      pill: read(),
      distance: Math.round(scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight),
      scrollTop: Math.round(scrollEl.scrollTop),
      max: Math.round(scrollEl.scrollHeight - scrollEl.clientHeight),
    };
  }, kind);
}

async function pillMetrics(page, kind) {
  return page.evaluate(async (k) => {
    const fm = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const host = k === 'agent' ? document.querySelector('#main') : fm;
    const pill = host ? host.querySelector('.scroll-back-pill') : null;
    const overlaps = (a, b) => {
      if (!a || !b) return null;
      const x = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
      const y = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
      return +(x * y).toFixed(2);
    };
    const inputSel = k === 'agent' ? '#input-area' : '.fm-input-bar';
    const floats = k === 'agent'
      ? ['#send-btn', '#attach-btn', '#voice-btn', '#mic-orb-wrap']
      : ['.fm-send-btn', '.fm-attach-btn', '.fm-mic-btn'];
    const ib = k === 'agent' ? document.querySelector(inputSel) : fm.querySelector(inputSel);
    const out = { inputBar: null, floats: {} };
    if (!pill) return out;
    const pr = pill.getBoundingClientRect();
    out.inputBar = overlaps(pr, ib ? ib.getBoundingClientRect() : null);
    for (const s of floats) {
      const e = k === 'agent' ? document.querySelector(s) : fm.querySelector(s);
      if (e) out.floats[s] = overlaps(pr, e.getBoundingClientRect());
    }
    return out;
  }, kind);
}

async function pillClick(page, kind) {
  return page.evaluate(async (k) => {
    const fm = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const scrollEl = k === 'agent' ? document.querySelector('#chat') : (fm ? fm.querySelector('.fm-flow') : null);
    const host = k === 'agent' ? document.querySelector('#main') : fm;
    const pill = host ? host.querySelector('.scroll-back-pill') : null;
    if (!pill) return { error: 'no pill' };
    const max = scrollEl.scrollHeight - scrollEl.clientHeight;
    pill.click();
    await new Promise((r) => setTimeout(r, 120));
    const mid = Math.round(scrollEl.scrollTop);
    await new Promise((r) => setTimeout(r, 1200));
    return {
      max: Math.round(max),
      scrollTop120ms: mid,
      smooth: mid < Math.round(max) - 2,
      scrollTopEnd: Math.round(scrollEl.scrollTop),
      distanceEnd: Math.round(scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight),
      hiddenEnd: pill.classList.contains('hidden'),
    };
  }, kind);
}

async function pillShot(browser, { w, h, kind, theme, tag }) {
  const page = await browser.newPage({
    viewport: { width: w, height: h },
    hasTouch: w < 600,
    colorScheme: theme,
    locale: 'zh-CN',
  });
  try {
    await bootApp(page);
    await openFace(page, kind);
    // ① 滚离底部 420px（> SHOW_AFTER_PX 320）⇒ 无未读也要在场
    await page.evaluate(async (k) => {
      const fm = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
        || document.querySelector('#fm-chat-overlay .fm-modal');
      const el = k === 'agent' ? document.querySelector('#chat') : (fm ? fm.querySelector('.fm-flow') : null);
      if (!el) return;
      document.dispatchEvent(new Event('touchstart', { bubbles: true }));
      el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight - 420);
      el.dispatchEvent(new Event('scroll'));
    }, kind);
    await page.waitForTimeout(280);
    const idle = await pillRead(page, kind);
    const metrics = await pillMetrics(page, kind);
    const fileIdle = join(OUT, `${tag}-pill-${kind}-${w}x${h}-${theme}.png`);
    await page.screenshot({ path: fileIdle });

    // ② 未读注入：离底期间来了新消息 ⇒ 同一胶囊切「↓ N 条新消息」
    if (kind === 'agent') {
      await page.evaluate(() => {
        const chat = document.querySelector('#chat');
        const row = document.createElement('div');
        row.className = 'row user';
        row.innerHTML = '<div class="bubble user">离底期间到达的新消息</div>';
        chat.appendChild(row);
      });
    } else {
      lastWs?.send(JSON.stringify({
        type: 'friend_event', event: 'message_new',
        conversationId: kind === 'group' ? 'g-1' : 'c-f1',
        messageId: String(Date.now()), senderId: 'u-f1', kind: 'text',
        body: '离底期间到达的新消息', createdAt: Date.now(),
      }));
    }
    await page.waitForTimeout(420);
    const unread = await pillRead(page, kind);
    const fileUnread = join(OUT, `${tag}-pill-unread-${kind}-${w}x${h}-${theme}.png`);
    await page.screenshot({ path: fileUnread });

    // ③ 点击 → 平滑贴底（120ms 采样仍在中途）＋ ④ 贴底后消失
    const clicked = await pillClick(page, kind);
    const fileAfter = join(OUT, `${tag}-pill-after-${kind}-${w}x${h}-${theme}.png`);
    await page.screenshot({ path: fileAfter });
    console.log(`[pill] ${kind} ${w}x${h} ${theme} → ${JSON.stringify({ idle, metrics, unread, clicked })}`);
    return { kind, w, h, theme, file: fileIdle, idle, metrics, unread, clicked };
  } finally {
    await page.close();
  }
}

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
const rows = [];
const pills = [];
const KINDS = ['agent', 'friend', 'group', 'device'];
const VIEWS = [{ w: 375, h: 812 }, { w: 1440, h: 900 }];
try {
  for (const v of VIEWS) {
    for (const theme of ['dark', 'light']) {
      // 全族对拍照：手机层 dark 全拍，其余组合只拍 friend 以控时长
      const kinds = ONLY === 'measure' ? [] : KINDS;
      for (const kind of kinds) {
        rows.push(await shot(browser, { ...v, kind, theme, tag: STATE }));
      }
    }
  }
  for (const v of VIEWS) {
    for (const kind of KINDS) {
      pills.push(await pillShot(browser, { ...v, kind, theme: 'dark', tag: STATE }));
    }
  }
} finally {
  await browser.close();
}

console.log(`\n═══ thread-ui-unify-shots (state=${STATE}, web=${WEB}) ═══`);
console.log('\n── 几何读数（JSON）──');
console.log(JSON.stringify(rows.map((r) => ({ kind: r.kind, view: `${r.w}x${r.h}`, theme: r.theme, measure: r.measure })), null, 1));
console.log('\n── 回到底部胶囊 ──');
for (const p of pills) console.log(`${p.kind} ${p.w}x${p.h}: ${JSON.stringify(p.pill)}`);
