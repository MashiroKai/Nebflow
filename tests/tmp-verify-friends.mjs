// tmp-verify-friends.mjs — A2A friends UI 自测 harness（friends-messaging-spec v1.2 §9）
// A1-A18 + A24，全 mock（routeWebSocket + fm_api_mock seed）。
// Run: node tests/tmp-verify-friends.mjs（需静态服务器 :8976 serve src/main/resources/web）
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1:8976';
const SID = 'sess-1';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const now = Date.now();
const iso = ms => new Date(ms).toISOString();

// SEED1: 主场景（zh 登录态）——两会话各 2 未读 + 非好友会话 + 待处理请求
const SEED1 = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [
    { userId: 'u-lin', neblinkId: 'lin_custom_id', name: '林小满', avatarUrl: '' }, // 自定义 NL 号（非邮箱）→ A4 隐私
    { userId: 'u-new', neblinkId: 'newbie42', name: '新同学', avatarUrl: '' },
  ],
  friends: [
    { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '', since: iso(now - 86400e3) },
    { userId: 'u-b', neblinkId: 'bob02', name: 'Bob', avatarUrl: '', since: iso(now - 86400e3) },
  ],
  incoming: [{ requestId: 'rq-1', from: { userId: 'u-new', neblinkId: 'newbie42', name: '新同学', avatarUrl: '' }, note: '你好，加我', status: 'pending' }],
  outgoing: [],
  conversations: [
    { conversationId: 'c-a', friend: { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
      lastMessage: { id: 'm-a2', senderId: 'u-a', kind: 'text', body: 'Alice 的最新消息', createdAt: iso(now - 60e3) }, unreadCount: 2 },
    { conversationId: 'c-b', friend: { userId: 'u-b', neblinkId: 'bob02', name: 'Bob', avatarUrl: '' },
      lastMessage: { id: 'm-b1', senderId: 'u-b', kind: 'text', body: 'Bob 说的事', createdAt: iso(now - 120e3) }, unreadCount: 2 },
    { conversationId: 'c-x', friend: { userId: 'u-x', neblinkId: 'exfriend', name: '旧友', avatarUrl: '' },
      lastMessage: { id: 'm-x1', senderId: 'u-x', kind: 'text', body: '解除前的最后一条', createdAt: iso(now - 180e3) }, unreadCount: 0 },
  ],
  messages: {
    'c-a': [
      { id: 'm-a0', senderId: 'u-a', kind: 'text', body: 'Alice 的第一条', createdAt: iso(now - 300e3) },
      { id: 'm-a1', senderId: 'me', kind: 'text', body: '我之前发的', createdAt: iso(now - 240e3) },
      { id: 'm-a2', senderId: 'u-a', kind: 'text', body: 'Alice 的最新消息', createdAt: iso(now - 60e3) },
    ],
    'c-b': [{ id: 'm-b1', senderId: 'u-b', kind: 'text', body: 'Bob 说的事', createdAt: iso(now - 120e3) }],
    'c-x': [{ id: 'm-x1', senderId: 'u-x', kind: 'text', body: '解除前的最后一条', createdAt: iso(now - 180e3) }],
  },
};
// SEED2: en + 99+ 边界
const SEED2 = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  friends: [{ userId: 'u-big', neblinkId: 'big99', name: 'BigTalker', avatarUrl: '', since: iso(now - 86400e3) }],
  conversations: [{ conversationId: 'c-big', friend: { userId: 'u-big', neblinkId: 'big99', name: 'BigTalker', avatarUrl: '' },
    lastMessage: { id: 'm-g1', senderId: 'u-big', kind: 'text', body: 'latest from big', createdAt: iso(now - 60e3) }, unreadCount: 100 }],
  messages: { 'c-big': [{ id: 'm-g1', senderId: 'u-big', kind: 'text', body: 'latest from big', createdAt: iso(now - 60e3) }] },
};

const browser = await chromium.launch();

async function bootPage({ seed, locale = 'zh-CN', loggedIn = true, reducedMotion = false, viewport = { width: 1440, height: 900 } }) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, ...(reducedMotion ? { reducedMotion: 'reduce' } : {}) });
  await ctx.addInitScript(([seedJson, loc]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', loc); localStorage.setItem('neblink_locale', loc);
    localStorage.setItem('fm_api_mock', '1');
    if (seedJson) localStorage.setItem('fm_api_mock_seed', seedJson);
    // A24 instrumentation: count Notification/Audio constructions
    window.__fmSideFx = { notif: 0, audio: 0 };
    try { window.Notification = function () { window.__fmSideFx.notif++; }; } catch {}
    // play() must return a promise (chat.js voice-unlock does s.play().then)
    try { window.Audio = function () { window.__fmSideFx.audio++; return { play() { return Promise.resolve(); }, pause() {} }; }; } catch {}
  }, [seed ? JSON.stringify(seed) : null, locale]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));           // catch-all first
  if (loggedIn) {
    await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: null, peers: [] } }));
  }
  const clientFrames = [];
  let serverWs = null;
  await page.routeWebSocket(/\/ws/, ws => {
    serverWs = ws;
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      clientFrames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
  await sleep(800); // boot + neblink status fetch
  return { ctx, page, clientFrames, send: obj => serverWs.send(JSON.stringify(obj)) };
}

// ══ Page 1: zh 主场景 ═════════════════════════════════════
{
  const { page, clientFrames, send } = await bootPage({ seed: SEED1 });

  // ── A1: Activity Bar 图标序（裁定 R1）────────────────────
  const order = await page.$$eval('#activity-bar > *', els => els.slice(0, 5).map(e => e.id || e.className));
  ok('A1 图标序 avatar→messages→contacts→files→spacer',
    order[0] === 'activity-avatar' && order[1] === 'messages-btn' && order[2] === 'contacts-btn' && order[3] === 'files-btn' && order[4].includes('activity-spacer'),
    JSON.stringify(order));

  // ── A2: 面板切换/互斥/收起 ───────────────────────────────
  await page.click('#messages-btn');
  await sleep(400);
  const a2on = await page.evaluate(() => ({
    msg: document.getElementById('panel-messages').classList.contains('active'),
    btn: document.getElementById('messages-btn').classList.contains('active'),
    others: [...document.querySelectorAll('.panel.active')].length,
  }));
  ok('A2a 点消息钮 → 恰 1 激活面板 + 按钮 active', a2on.msg && a2on.btn && a2on.others === 1, JSON.stringify(a2on));
  // A14 在此测（按钮处于 active）
  const a14 = await page.$eval('#messages-btn', e => {
    const cs = getComputedStyle(e);
    return { blw: cs.borderLeftWidth, bs: cs.boxShadow };
  });
  ok('A14 active 无竖线仅阴影', a14.blw === '0px' && a14.bs !== 'none', JSON.stringify(a14));
  await page.click('#messages-btn');
  await sleep(300);
  const a2off = await page.evaluate(() => ({
    collapsed: document.body.classList.contains('sidebar-collapsed'),
    active: document.getElementById('panel-messages').classList.contains('active'),
  }));
  ok('A2b 再点 → 侧栏收起 + 面板失活', a2off.collapsed && !a2off.active, JSON.stringify(a2off));
  await page.click('#messages-btn'); // 重新展开
  await sleep(600); // MutationObserver → refreshConversations

  // ── A11a: 未读双级联动初值 ──────────────────────────────
  const badge0 = await page.$eval('#messages-badge', e => e.textContent);
  const rowBadges = await page.$$eval('#fm-conversations .fm-conv-row', rows =>
    rows.map(r => ({ id: r.dataset.conversationId, badge: r.querySelector('.fm-row-badge')?.textContent || null })));
  ok('A11a 消息钮 badge=4', badge0 === '4', badge0);
  ok('A11b 行 badge c-a=2 c-b=2', rowBadges.find(r => r.id === 'c-a')?.badge === '2' && rowBadges.find(r => r.id === 'c-b')?.badge === '2', JSON.stringify(rowBadges));
  ok('A12a 未读 0 的会话行无 badge', rowBadges.find(r => r.id === 'c-x')?.badge === null, JSON.stringify(rowBadges));

  // ── A6: 会话行五元素 + 摘要单行 ─────────────────────────
  const a6 = await page.$eval('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]', r => {
    const s = r.querySelector('.fm-conv-summary');
    return {
      avatar: !!r.querySelector('.fm-avatar'), name: !!r.querySelector('.fm-row-name'),
      summary: !!s, time: !!r.querySelector('.fm-conv-time'), badge: !!r.querySelector('.fm-row-badge'),
      oneLine: s.scrollHeight <= s.clientHeight + 1,
    };
  });
  ok('A6 行结构五元素 + 摘要单行', a6.avatar && a6.name && a6.summary && a6.time && a6.badge && a6.oneLine, JSON.stringify(a6));

  // ── A4: 搜索加好友（隐私：自定义号不含邮箱）─────────────
  await page.click('#contacts-btn');
  await sleep(500);
  await page.fill('.fm-search-input', 'lin_custom_id');
  await page.click('.fm-search-btn');
  await sleep(500);
  const card1 = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('A4a 命中结果卡含昵称+自定义号', card1.includes('林小满') && card1.includes('lin_custom_id'), card1.slice(0, 60));
  ok('A4b 结果卡不含邮箱文本（R3 隐私）', !card1.includes('@'), card1.slice(0, 60));
  await sleep(1100); // 提交式查询 1s 最小间隔
  await page.fill('.fm-search-input', 'me@example.com');
  await page.click('.fm-search-btn');
  await sleep(500);
  const card2 = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('A4c 自己搜自己 → 「这是你自己」无加好友钮', card2.includes('这是你自己'), card2.slice(0, 60));
  await sleep(1100);
  await page.fill('.fm-search-input', 'nobody_xyz');
  await page.click('.fm-search-btn');
  await sleep(500);
  const card3 = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('A4d 未命中 → 「未找到该用户」', card3.includes('未找到该用户'), card3.slice(0, 60));

  // ── A5: 好友请求闭环（同意三联）─────────────────────────
  await page.click('.fm-nf-entry');
  await sleep(300);
  await page.click('.fm-req-accept');
  await sleep(800);
  const a5c = await page.evaluate(() => ({
    friendInList: [...document.querySelectorAll('#fm-contacts-body .fm-friend-row .fm-row-name')].some(e => e.textContent === '新同学'),
    reqRow: [...document.querySelectorAll('.fm-req-row')].map(r => ({
      status: r.querySelector('.fm-status-text')?.textContent || null,
      hasBtns: !!r.querySelector('.fm-req-accept'),
    })),
  }));
  ok('A5a 同意后好友列表出现该用户', a5c.friendInList, JSON.stringify(a5c));
  ok('A5b 请求条目变「已添加」且按钮消失', a5c.reqRow.some(r => r.status === '已添加' && !r.hasBtns), JSON.stringify(a5c.reqRow));
  await page.click('#messages-btn');
  await sleep(700); // 切面板触发 messages refresh
  const a5m = await page.$$eval('#fm-conversations .fm-conv-row .fm-row-name', els => els.map(e => e.textContent));
  ok('A5c 消息面板出现新会话（新同学）', a5m.includes('新同学'), JSON.stringify(a5m));

  // ── 打开 c-a → A11c 已读清除联动 ────────────────────────
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
  await sleep(700);
  const badge1 = await page.$eval('#messages-badge', e => e.textContent);
  const cABadge = await page.$eval('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]', r => r.querySelector('.fm-row-badge')?.textContent || null);
  ok('A11c 打开 c-a → 行 badge 消失 + 消息钮 badge=2', badge1 === '2' && cABadge === null, `btn=${badge1} row=${cABadge}`);

  // ── A7: 弹窗铁律双联 + 560px ────────────────────────────
  const a7 = await page.evaluate(() => {
    const ov = document.getElementById('fm-chat-overlay');
    const md = ov?.querySelector('.fm-modal');
    if (!ov || !md) return null;
    const ocs = getComputedStyle(ov), mcs = getComputedStyle(md);
    return { ovBg: ocs.backgroundColor, bf: mcs.backdropFilter || mcs.webkitBackdropFilter, w: mcs.width };
  });
  ok('A7 overlay transparent + 面板 backdrop-filter 含 blur + 宽 560px',
    !!a7 && (a7.ovBg === 'rgba(0, 0, 0, 0)' || a7.ovBg === 'transparent') && (a7.bf || '').includes('blur') && a7.w === '560px',
    JSON.stringify(a7));
  await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/friends-chat-modal-light.png' });

  // ── A8: hover 消息转发该条 ──────────────────────────────
  const mA0 = page.locator('.fm-msg[data-message-id="m-a0"]');
  await mA0.hover();
  await mA0.locator('.fm-msg-act[title="转发给 agent"]').click();
  await sleep(300);
  const a8chip = await page.$eval('.fm-msg[data-message-id="m-a0"]', e => !!e.querySelector('.fm-msg-forwarded-badge'));
  const a8frame = clientFrames.find(f => f.type === 'ask' && typeof f.question === 'string' && f.question.includes('Alice 的第一条'));
  const a8toast = await page.$eval('.fm-modal-toast', e => !e.hidden && e.textContent.includes('已发送到当前会话'));
  ok('A8 转发该条 → chip + ask 帧 [来自 前缀 + toast',
    a8chip && !!a8frame && a8frame.question.startsWith('[来自 Alice] ') && a8frame.sessionId === SID && a8toast,
    JSON.stringify(a8frame || null));

  // ── A9: header 钮转发最近一条 in ────────────────────────
  await page.click('.fm-forward-btn');
  await sleep(300);
  const a9 = await page.evaluate(() => ({
    latestIn: !!document.querySelector('.fm-msg[data-message-id="m-a2"] .fm-msg-forwarded-badge'),
  }));
  const a9frame = clientFrames.filter(f => f.type === 'ask').pop();
  ok('A9 header 钮转发最近一条对方消息（m-a2 非 m-a0 之外的）', a9.latestIn && !!a9frame && a9frame.question === '[来自 Alice] Alice 的最新消息', a9frame?.question);

  // ── A18: Esc 关闭 + 焦点归还触发会话行 ──────────────────
  await page.keyboard.press('Escape');
  await sleep(400);
  const a18 = await page.evaluate(() => ({
    closed: !document.getElementById('fm-chat-overlay'),
    focusBack: document.activeElement?.dataset?.conversationId === 'c-a',
  }));
  ok('A18 Esc 关闭弹窗 + 焦点归还触发行', a18.closed && a18.focusBack, JSON.stringify(a18));

  // ── A13: 非好友拦截 ─────────────────────────────────────
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-x"]');
  await sleep(700);
  const a13 = await page.evaluate(() => {
    const bar = document.querySelector('.fm-blocked-bar');
    const input = document.querySelector('.fm-input');
    return {
      barExists: !!bar,
      barIsBubble: bar ? !!bar.querySelector('.fm-msg-bubble') || bar.classList.contains('fm-msg-bubble') : null,
      barText: bar?.textContent || '',
      inputDisabled: input?.disabled,
    };
  });
  ok('A13 非好友 → 输入禁用 + 系统提示条（非气泡）',
    a13.barExists && a13.barIsBubble === false && a13.inputDisabled === true && a13.barText.includes('对方已不是你的好友'),
    JSON.stringify(a13));
  await page.keyboard.press('Escape');
  await sleep(300);

  // ── A10: agent 代发四联 ─────────────────────────────────
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-b"]');
  await sleep(700);
  send({ type: 'friend_event', event: 'message_new', conversationId: 'c-b', messageId: 'm-b2', senderId: 'me', kind: 'agent', body: 'agent 代发内容', createdAt: iso(Date.now()) });
  await sleep(500);
  const a10 = await page.evaluate(() => {
    const wrap = document.querySelector('.fm-msg[data-message-id="m-b2"]');
    const rowB = document.querySelector('#fm-conversations .fm-conv-row[data-conversation-id="c-b"]');
    return {
      inFlow: !!wrap, isOut: wrap?.classList.contains('out'), chip: !!wrap?.querySelector('.fm-msg-agent-badge'),
      chipText: wrap?.querySelector('.fm-msg-agent-badge')?.textContent || '',
      summary: rowB?.querySelector('.fm-conv-summary')?.textContent || '',
      rowBadge: rowB?.querySelector('.fm-row-badge')?.textContent || null,
      btnBadge: document.getElementById('messages-badge')?.textContent || '',
      btnBadgeHidden: document.getElementById('messages-badge')?.hidden,
    };
  });
  ok('A10 代发 → out 气泡 + Agent chip + 摘要 [Agent] 前缀 + 未读不增',
    a10.inFlow && a10.isOut && a10.chip && a10.chipText === 'Agent 代发' && a10.summary.startsWith('[Agent] ') && a10.rowBadge === null,
    JSON.stringify(a10));
  ok('A12b 全部已读后消息钮 badge 隐藏（0 隐藏）', a10.btnBadgeHidden === true, `hidden=${a10.btnBadgeHidden}`);

  // ── A24: 提醒边界负断言（非聚焦会话新消息）──────────────
  // 口径：消息到达时刻不得新增提醒副作用。chat.js 的 voice-unlock 会在首次
  // 用户手势时构造一次 Audio（存量 TTS 机制，与消息事件无关）——所以断言
  // 注入前后计数**不变**，而非全程为 0。
  const titleBefore = await page.title();
  const fxBefore = await page.evaluate(() => ({ ...window.__fmSideFx }));
  send({ type: 'friend_event', event: 'message_new', conversationId: 'c-a', messageId: 'm-a9', senderId: 'u-a', kind: 'text', body: '聚焦外的问候', createdAt: iso(Date.now()) });
  await sleep(500);
  const a24 = await page.evaluate(() => ({
    fx: window.__fmSideFx, title: document.title,
    badgeNow: document.getElementById('messages-badge')?.textContent,
  }));
  ok('A24 非聚焦新消息 → badge+1 且注入前后无新增 Notification/Audio、title 无前缀',
    a24.fx.notif === fxBefore.notif && a24.fx.audio === fxBefore.audio && a24.title === titleBefore && !a24.title.match(/^\(?\d/) && a24.badgeNow === '1',
    `before=${JSON.stringify(fxBefore)} after=${JSON.stringify(a24)}`);

  // ── A15: 375px 增量口径 ─────────────────────────────────
  await page.keyboard.press('Escape');
  await sleep(300);
  await page.setViewportSize({ width: 375, height: 812 });
  await sleep(400);
  const baseSw = await page.evaluate(() => document.documentElement.scrollWidth);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-b"]');
  await sleep(600);
  const a15 = await page.evaluate(() => ({
    docSw: document.documentElement.scrollWidth,
    modalSw: document.querySelector('.fm-modal')?.scrollWidth,
  }));
  ok('A15 375px：打开弹窗不新增文档溢出 + 弹窗子树 ≤560', a15.docSw === baseSw && a15.modalSw <= 560, `base=${baseSw} ${JSON.stringify(a15)}`);
  await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/friends-375.png' });
  await page.keyboard.press('Escape');
  await page.close();
}

// ══ Page 2: en + A12 99+ ══════════════════════════════════
{
  const { page, send } = await bootPage({ seed: SEED2, locale: 'en' });
  await page.click('#messages-btn');
  await sleep(700);
  const a12 = await page.evaluate(() => ({
    btn: document.getElementById('messages-badge')?.textContent,
    row: document.querySelector('#fm-conversations .fm-row-badge')?.textContent,
    title: document.querySelector('#panel-messages .panel-title')?.textContent,
  }));
  ok('A12c 未读 100 → 行/钮 badge 均 99+', a12.btn === '99+' && a12.row === '99+', JSON.stringify(a12));
  ok('A17a en 面板标题 Messages', a12.title === 'Messages', a12.title);
  await page.click('#contacts-btn');
  await sleep(500);
  const a17b = await page.evaluate(() => ({
    title: document.querySelector('#panel-contacts .panel-title')?.textContent,
    ph: document.querySelector('.fm-search-input')?.placeholder,
    nf: document.querySelector('.fm-nf-label')?.textContent,
  }));
  ok('A17b en 联系人标题/搜索占位/新的朋友', a17b.title === 'Contacts' && a17b.ph === 'NebLink ID / Email' && a17b.nf === 'New Friends', JSON.stringify(a17b));
  // en chip：开 c-big 注入 agent 代发
  await page.click('#messages-btn');
  await sleep(500);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-big"]');
  await sleep(700);
  send({ type: 'friend_event', event: 'message_new', conversationId: 'c-big', messageId: 'm-g2', senderId: 'me', kind: 'agent', body: 'agent reply', createdAt: iso(Date.now()) });
  await sleep(500);
  const a17c = await page.evaluate(() => ({
    chip: document.querySelector('.fm-msg[data-message-id="m-g2"] .fm-msg-agent-badge')?.textContent,
    sendBtn: document.querySelector('.fm-send-btn')?.textContent,
  }));
  ok('A17c en chip「Sent by agent」+ 发送钮 Send', a17c.chip === 'Sent by agent' && a17c.sendBtn === 'Send', JSON.stringify(a17c));
  await page.close();
}

// ══ Page 3: 未登录 A3 ═════════════════════════════════════
{
  const { page } = await bootPage({ seed: null, loggedIn: false });
  await sleep(500);
  const a3 = await page.evaluate(() => ({
    msgEmpty: !!document.querySelector('#fm-conversations .fm-login-empty'),
    ctEmpty: !!document.querySelector('#fm-contacts-body .fm-login-empty'),
    loginText: document.querySelector('#fm-conversations .fm-login-text')?.textContent || '',
    msgBadgeHidden: document.getElementById('messages-badge')?.hidden,
    ctBadgeHidden: document.getElementById('contacts-badge')?.hidden,
  }));
  ok('A3 未登录 → 两面板登录引导空态 + badge 全隐藏',
    a3.msgEmpty && a3.ctEmpty && a3.loginText === '登录 NebLink 后使用消息与联系人' && a3.msgBadgeHidden && a3.ctBadgeHidden,
    JSON.stringify(a3));
  await page.close();
}

// ══ Page 4: reduced-motion A16 ════════════════════════════
{
  const { page } = await bootPage({ seed: SEED1, reducedMotion: true });
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
  await sleep(700);
  const a16 = await page.evaluate(() => {
    const ov = document.getElementById('fm-chat-overlay');
    const badge = document.getElementById('messages-badge');
    const dur = e => { const cs = getComputedStyle(e); return [cs.animationDuration, cs.transitionDuration]; };
    return { overlay: ov ? dur(ov) : null, badge: badge ? dur(badge) : null };
  });
  const le = arr => arr && arr.every(v => parseFloat(v) <= 0.01);
  ok('A16 reduced-motion：弹窗 overlay + badge 动效 ≤0.01s', le(a16.overlay) && le(a16.badge), JSON.stringify(a16));
  await page.close();
}

// ══ Page 5: 暗色主题截图（目检）════════════════════════════
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: 'dark' });
  await ctx.addInitScript(([seedJson]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    if (seedJson) localStorage.setItem('fm_api_mock_seed', seedJson);
  }, [JSON.stringify(SEED1)]);
  const page = await ctx.newPage();
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await sleep(800);
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
  await sleep(700);
  await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/friends-chat-modal-dark.png' });
  await page.close();
}

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
