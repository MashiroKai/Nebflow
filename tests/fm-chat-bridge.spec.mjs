// fm-chat-bridge.spec.mjs — fm 会话桥验收（P12 电脑端聊天面板大一统 · 阶段 1 铺垫件）
//
// 被测件：js/messages/fmChatBridge.js（conv ↔ ChatView 注册 + friend_event fan-out）
//          + ws.js 的 fm 桥分支（非独占：数据腿照跑）+ messages.js 的两个 sync 对账点。
//
// 判据面（阶段 1 = 行为不变的纯增件，两条腿都要断）：
//   B0  conv↔view 注册：好友/群/设备三会话各注册一枚 `fm-<kind>-<id>` 视图进
//       chatViews（bgAgentPopup 先例模式）；view.sessionId=null（findViewBySessionId
//       零交集）；容器落隐藏宿主（display:none 祖先内、零可见面）。
//   B1  好友帧进视图派发：friend_event(message_new) 按 conversationId 派发进桥视图
//       （journal 打点 + onFmFrame 订阅者收到）；同帧产品数据腿零回归 —— 开着的
//       好友窗 .fm-flow 出现该消息气泡（onFriendEvent appendMessage 照常）；帧前后
//       activeView 恒 'primary'（桥通道零视图扰动）。
//   B2  设备帧同理：设备会话桥视图收到帧（journal）；窗关着 ⇒ 未读腿照常
//       （.fm-row-badge '1' + #messages-btn 活动角标上涨）。
//   B3  message_new_self（本账号他处所发）同样进桥通道（journal+订阅者），但不计
//       未读（数据腿既有语义不变，行徽标不涨）。
//   B4  幽灵会话帧（未注册 conversationId）⇒ sink 静默 no-op：不建视图、订阅者
//       不响、零 pageerror。
//   B5  agent 会话既有路由零回归：textDelta(sessionId='sess-1') 照常落主会话
//       #chat；桥视图 journal 不被 agent 帧触碰；activeView 恒 'primary'。
//   B6  退役对账：会话列表撤行 ⇒ 对应桥视图连注册表键带容器一并退役；退役后
//       该会话帧 = no-op；其余视图保留。
//   每腿零 console error / 零 pageerror。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// 范式参照 tests/search-batch-open-canvas.spec.mjs；fm REST 夹具形状参照
// tests/fm-md-social-shots.mjs（neblink status / conversations / friends / groups /
// messages 增量 after=）。
// Run: node tests/fm-chat-bridge.spec.mjs   （ENGINE=chromium+webkit 可多引擎）

import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS = join(HERE, '..', 'evidence', '20261111_p12-chat-bridge', 'shots');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const ENGINES = (process.env.ENGINE || 'chromium').split('+');

// 落点源 = 真实本地端口（WebKit 对不安全端口拒连的同款技法：本 server 只是
// 「可连的源」，全部请求仍走 page.route，自身零流量）。
const originServer = http.createServer((_req, res) => { res.writeHead(200); res.end('fixture-origin'); });
const ORIGIN = await new Promise((resolve) => {
  originServer.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${originServer.address().port}`));
});

// ── 夹具：三会话（好友无 kind 字段 / 群经 normalizeGroupRow 归一 / 设备显式 kind）──
const T0 = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(T0 - 86400000) };

const FRIEND_MSGS = [
  { id: 1, senderId: 'u-p', kind: 'text', body: '历史消息（桥接夹具）', createdAt: iso(T0 - 600000), attachments: [] },
];
const DEVICE_MSGS = [
  { id: 1, senderDeviceId: 'peer-1', kind: 'text', body: '设备历史（桥接夹具）', createdAtMs: T0 - 600000, attachments: [] },
];
const GROUP_MSGS = [
  { id: 1, senderId: 'u-p', kind: 'text', body: '群历史（桥接夹具）', createdAt: iso(T0 - 600000), attachments: [] },
];

/** Node 侧可变会话名册（B6 退役腿：撤群行动态生效）。 */
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: FRIEND_MSGS[0], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: DEVICE_MSGS[0], unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '桥接群', unreadCount: 0, memberCount: 2, role: 'owner', memberAvatars: [], lastMessage: GROUP_MSGS[0] }];
const SESSIONS = [{ id: 'sess-1', name: '主会话', agentName: 'Nebula' }];
const MSGS = { cF: FRIEND_MSGS, 'dev:peer-1': DEVICE_MSGS, 'g-1': GROUP_MSGS };

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`console: ${m.text()}`); });
  page.on('response', (r) => { if (r.status() >= 400) consoleErrors.push(`http${r.status()}: ${r.url()}`); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('neblink_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        loggedIn: true,
        relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
        device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
        peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
      }) });
    }
    if (p === '/api/conversations') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
    }
    if (p === '/api/friends') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
    }
    if (p === '/api/groups') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
    }
    if (p === '/api/groups/invites') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
    }
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) {
      const cid = decodeURIComponent(mm[1]);
      const after = Math.max(0, Math.floor(Number(url.searchParams.get('after')) || 0));
      const rows = (MSGS[cid] || []).filter((x) => Number(x.id) > after);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p === '/agents/sess-1/agent.json' || /^\/agents\/[^/]+\/agent\.json$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  // WS mock：boot 帧 + 请求应答；push() = 测试侧向页面注入入站帧的唯一通道。
  let push = () => {};
  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = (o) => ws.send(JSON.stringify(o));
    push = send;
    const sendConfig = () => send({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} });
    const sendSessions = () => send({ type: 'sessionList', sessions: SESSIONS, folders: [], activeId: 'sess-1' });
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        // 一条 seed 用户行：零历史的会话不产 .row，boot 等待面（#chat .row）需要它。
        send({ type: 'historyPage', sessionId: m.sessionId, hasMore: false, offset: 0, total: 1,
          messages: [{ type: 'user', text: 'boot seed（桥接夹具）', timestamp: T0 - 300000 }] });
      }
    });
  });

  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === 'sess-1' && s.ws && s.ws.readyState === 1;
  }, undefined, { timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 15000 });
  await page.waitForTimeout(400);
  // 测试侧向页面注入入站帧的唯一通道（转发到当前 WS 连接的 send）。
  const pushFrame = (o) => push(o);
  return { page, context, consoleErrors, push: pushFrame };
}

// ── 页侧读数 ────────────────────────────────────────────────────────────────
const readRegistry = (page) => page.evaluate(async () => {
  const cv = await import('/js/chatView.js');
  const bridge = await import('/js/messages/fmChatBridge.js');
  const pick = (id) => {
    const v = cv.chatViews[id];
    if (!v) return null;
    return {
      id: v.id, sessionId: v.sessionId, mounted: v.mounted, visible: v.visible,
      journal: v.fmBridge ? { frameCount: v.fmBridge.frameCount, lastFrameId: v.fmBridge.lastFrame ? String(v.fmBridge.lastFrame.messageId) : null } : null,
      hidden: !v.dom.chat || v.dom.chat.offsetParent === null,
      inDoc: !!(v.dom.chat && v.dom.chat.isConnected),
    };
  };
  return {
    friend: pick('fm-friend-cF'),
    group: pick('fm-group-g-1'),
    device: pick('fm-device-dev:peer-1'),
    ghost: !!bridge.fmViewFor('conv-ghost'),
    fmViewCf: !!bridge.fmViewFor('cF'),
    activeViewId: cv.activeView ? cv.activeView.id : null,
  };
});

const readBadges = (page) => page.evaluate(() => {
  const btn = document.getElementById('messages-btn');
  const badge = btn ? btn.querySelector('.activity-badge') : null;
  const devRow = document.querySelector('.fm-conv-row[data-conversation-id="dev:peer-1"]');
  const devRowBadge = devRow ? devRow.querySelector('.fm-row-badge') : null;
  return { activity: badge && !badge.hidden ? badge.textContent : '', devRow: devRowBadge ? devRowBadge.textContent : '' };
});

// ════════════════════════════════════════════════════════════════════════
let failedGroups = 0;
for (const ENGINE of ENGINES) {
  const browser = await (ENGINE === 'webkit' ? (await import('playwright')).webkit : chromium).launch({ headless: process.env.HEADLESS !== '0' });
  mkdirSync(SHOTS, { recursive: true });
  let groupPassed = 0, groupTotal = 0;
  const check = (name, ok, extra = '') => { groupTotal++; groupPassed += ok ? 1 : 0; console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}] ${name}${extra ? '  — ' + extra : ''}`); };

  const { page, context, consoleErrors, push } = await newPage(browser);

  // ── B0 打开消息面板 ⇒ refreshConversations ⇒ 桥对账注册 ──
  await page.click('#messages-btn');
  await page.waitForSelector('#fm-conversations .fm-conv-row', { timeout: 10000 });
  await page.waitForFunction(async () => {
    const b = await import('/js/messages/fmChatBridge.js');
    return !!b.fmViewFor('cF') && !!b.fmViewFor('g-1') && !!b.fmViewFor('dev:peer-1');
  }, undefined, { timeout: 10000 });
  let reg = await readRegistry(page);
  check('B0 三会话视图注册（fm-friend-cF / fm-group-g-1 / fm-device-dev:peer-1）',
    !!reg.friend && !!reg.group && !!reg.device, JSON.stringify(reg));
  check('B0 视图契约：sessionId=null / mounted / hidden 容器（findViewBySessionId 零交集 + 零可见面）',
    reg.friend.sessionId === null && reg.friend.mounted === true && reg.friend.visible === false
    && reg.friend.hidden === true && reg.friend.inDoc === true
    && reg.group.sessionId === null && reg.device.sessionId === null, JSON.stringify(reg));

  // ── B1 好友帧：桥通道派发 + 数据腿照跑（开窗气泡）+ activeView 零扰动 ──
  await page.evaluate(async () => {
    const b = await import('/js/messages/fmChatBridge.js');
    window.__cfFrames = [];
    b.onFmFrame('cF', (msg) => window.__cfFrames.push(String(msg.messageId)));
  });
  await page.click('.fm-conv-row[data-conversation-id="cF"]');
  await page.waitForSelector('.fm-modal .fm-flow', { timeout: 8000 });
  await page.waitForTimeout(300);
  push({ type: 'friend_event', event: 'message_new', conversationId: 'cF', messageId: 'm-1',
    senderId: 'u-p', kind: 'text', body: 'BRIDGE-FRIEND-MARKER', createdAt: iso(T0), attachments: [] });
  await page.waitForFunction(() => (window.__cfFrames || []).includes('m-1'), undefined, { timeout: 8000 });
  reg = await readRegistry(page);
  check('B1 好友帧进桥视图（journal frameCount=1 + lastFrame=m-1 + 订阅者收到）',
    reg.friend.journal && reg.friend.journal.frameCount === 1 && reg.friend.journal.lastFrameId === 'm-1',
    JSON.stringify(reg.friend));
  const bubble = await page.evaluate(() => {
    const flow = document.querySelector('.fm-modal .fm-flow');
    return { hasMarker: !!flow && flow.textContent.includes('BRIDGE-FRIEND-MARKER') };
  });
  check('B1 数据腿零回归：开着的 fm 窗照常出气泡（onFriendEvent appendMessage）', bubble.hasMarker, JSON.stringify(bubble));
  check('B1 activeView 恒 primary（桥通道零视图扰动）', reg.activeViewId === 'primary', `activeView=${reg.activeViewId}`);

  // ── B2 设备帧：桥通道 + 未读腿照常（窗关着）──
  await page.evaluate(async () => {
    const b = await import('/js/messages/fmChatBridge.js');
    window.__devFrames = [];
    b.onFmFrame('dev:peer-1', (msg) => window.__devFrames.push(String(msg.messageId)));
  });
  const before2 = await readBadges(page);
  push({ type: 'friend_event', event: 'message_new', conversationId: 'dev:peer-1', messageId: 'd-1',
    senderId: 'me-account', senderDeviceId: 'peer-1', kind: 'text', body: 'BRIDGE-DEVICE-MARKER', createdAt: iso(T0 + 1), attachments: [] });
  await page.waitForFunction(() => (window.__devFrames || []).includes('d-1'), undefined, { timeout: 8000 });
  await page.waitForTimeout(300);
  reg = await readRegistry(page);
  const after2 = await readBadges(page);
  check('B2 设备帧进桥视图（journal frameCount=1 + lastFrame=d-1）',
    reg.device.journal && reg.device.journal.frameCount === 1 && reg.device.journal.lastFrameId === 'd-1',
    JSON.stringify(reg.device));
  check('B2 未读腿零回归：设备行徽标 0→1 + 活动角标上涨',
    before2.devRow === '' && after2.devRow === '1' && after2.activity !== '' && Number(after2.activity) >= 1,
    `before=${JSON.stringify(before2)} after=${JSON.stringify(after2)}`);

  // ── B3 message_new_self：进桥通道、不计未读 ──
  const snap3 = reg.friend.journal.frameCount;
  const devBefore3 = after2.devRow;
  push({ type: 'friend_event', event: 'message_new_self', conversationId: 'cF', messageId: 'm-2',
    senderId: 'me', kind: 'text', body: 'BRIDGE-SELF-ECHO', createdAt: iso(T0 + 2), attachments: [] });
  await page.waitForFunction(() => (window.__cfFrames || []).includes('m-2'), undefined, { timeout: 8000 });
  reg = await readRegistry(page);
  const badges3 = await readBadges(page);
  check('B3 self 回声帧同样进桥视图（frameCount +1）',
    reg.friend.journal.frameCount === snap3 + 1 && reg.friend.journal.lastFrameId === 'm-2',
    JSON.stringify(reg.friend));
  check('B3 数据腿既有语义：self 帧不计未读（设备行徽标不动）',
    badges3.devRow === devBefore3, JSON.stringify(badges3));

  // ── B4 幽灵会话帧 = 静默 no-op ──
  const cf4 = reg.friend.journal.frameCount;
  push({ type: 'friend_event', event: 'message_new', conversationId: 'conv-ghost', messageId: 'g-9',
    senderId: 'u-x', kind: 'text', body: 'GHOST', createdAt: iso(T0 + 3), attachments: [] });
  await page.waitForTimeout(500);
  reg = await readRegistry(page);
  check('B4 幽灵帧：不建视图（fmViewFor(conv-ghost)=null）', reg.ghost === false, `ghost=${reg.ghost}`);
  check('B4 既有视图 journal 不受幽灵帧影响', reg.friend.journal.frameCount === cf4, `cf=${reg.friend.journal.frameCount}`);

  // ── B5 agent 会话既有路由零回归 ──
  // stream-ux（2026-10-03）后 delta 只累积不上屏：textDone（finishAi→saveMsg）
  // 才把整段渲染成 ai 气泡 ⇒ 断言面 = 双帧后的 #chat。
  const cf5 = reg.friend.journal.frameCount;
  const dev5 = reg.device.journal.frameCount;
  push({ type: 'textDelta', sessionId: 'sess-1', delta: 'AGENT-BRIDGE-MARKER' });
  push({ type: 'textDone', sessionId: 'sess-1' });
  await page.waitForFunction(() => {
    const chat = document.getElementById('chat');
    return !!chat && chat.textContent.includes('AGENT-BRIDGE-MARKER');
  }, undefined, { timeout: 8000 });
  await page.waitForTimeout(200);
  const agentLeg = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    return { hasMarker: !!chat && chat.textContent.includes('AGENT-BRIDGE-MARKER') };
  });
  reg = await readRegistry(page);
  check('B5 agent textDelta 照常落主会话 #chat', agentLeg.hasMarker, JSON.stringify(agentLeg));
  check('B5 桥视图 journal 不被 agent 帧触碰', reg.friend.journal.frameCount === cf5 && reg.device.journal.frameCount === dev5,
    `cf=${reg.friend.journal.frameCount} dev=${reg.device.journal.frameCount}`);
  check('B5 activeView 恒 primary', reg.activeViewId === 'primary', `activeView=${reg.activeViewId}`);
  await page.screenshot({ path: join(SHOTS, `${ENGINE}-bridge-state.png`) });

  // ── B6 退役对账：撤群行 ⇒ 视图退役；退役后帧 = no-op ──
  const cf6 = reg.friend.journal.frameCount;
  GROUPS.length = 0;   // Node 侧动态撤群（route 读同一数组）
  await page.evaluate(() => { window.dispatchEvent(new Event('fm-friends-changed')); });
  await page.waitForFunction(async () => {
    const cv = await import('/js/chatView.js');
    return !cv.chatViews['fm-group-g-1'];
  }, undefined, { timeout: 10000 });
  await page.waitForTimeout(300);
  reg = await readRegistry(page);
  const containerGone = await page.evaluate(() => !document.querySelector('[data-fm-conv="g-1"]'));
  check('B6 撤行 ⇒ 群视图退役（注册表键删 + 隐藏容器摘除）',
    !reg.group && containerGone && !!reg.friend && !!reg.device, `group=${JSON.stringify(reg.group)} containerGone=${containerGone}`);
  push({ type: 'friend_event', event: 'message_new', conversationId: 'g-1', messageId: 'gp-1',
    senderId: 'u-p', kind: 'text', body: 'POST-PRUNE', createdAt: iso(T0 + 4), attachments: [] });
  await page.waitForTimeout(400);
  reg = await readRegistry(page);
  check('B6 退役后群帧 = no-op（其余视图 journal 不动）',
    !reg.group && reg.friend.journal.frameCount === cf6, `cf=${reg.friend.journal.frameCount}`);

  check(`每腿零 console error / 零 pageerror`, consoleErrors.length === 0, consoleErrors.join(' | '));

  await context.close();
  await browser.close();
  console.log(`\n[${ENGINE}] group: ${groupPassed}/${groupTotal} passed`);
}

const srvClosed = new Promise((resolve) => originServer.close(() => resolve()));
console.log(failedGroups === 0 ? '\nALL GREEN' : `\n${failedGroups} failing group(s)`);
await srvClosed;
process.exit(failedGroups === 0 ? 0 : 1);
