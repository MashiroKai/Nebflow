// friend-chain-ui.spec.mjs — 0904 好友功能链补全 + 界面优化 验收 spec。
// 覆盖：设置面板 NL 号入口零残留（10:54 裁定移除）· 聊天历史「加载更早消息」（keyset id-window）
// · 搜索/申请界面打磨（loading/未找到提示/验证消息 Enter+取消/申请时间/红点
// 未见语义）· 转发链补强（无活跃会话引导 toast；引用块入框→发送→chip）。
// 全 mock（fm_api_mock seed + route 拦截），隔离静态服务器 :8976。
// Run: node tests/friend-chain-ui.spec.mjs
import { chromium } from 'playwright';

const BASE = process.env.FM_BASE || 'http://127.0.0.1:8976';
const SID = 'sess-1';
const SHOTS = '/tmp';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const now = Date.now();
const iso = ms => new Date(ms).toISOString();
const epochSec = ms => Math.floor(ms / 1000);

// 数值 id 消息 220 条（分页：窗口 200 → 初始 21..220，点一次补 1..20）
const PAG_MSGS = [];
for (let i = 1; i <= 220; i++) {
  PAG_MSGS.push({ id: i, senderId: i % 2 ? 'u-p' : 'me', kind: 'text', body: `历史消息 #${i}`, createdAt: epochSec(now - (221 - i) * 60e3) });
}

const SEED = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [
    { userId: 'u-t', neblinkId: 'takenid', name: '被占用号', avatarUrl: '' },
    { userId: 'u-new', neblinkId: 'newbie42', name: '新同学', avatarUrl: '' },
  ],
  friends: [
    { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: iso(now - 86400e3) },
    { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '', since: iso(now - 86400e3) },
  ],
  incoming: [
    { requestId: 'rq-1', from: { userId: 'u-n1', neblinkId: 'newbie42', name: '新同学', avatarUrl: '' }, note: '你好，加我', status: 'pending', createdAt: epochSec(now - 3600e3) },
    { requestId: 'rq-2', from: { userId: 'u-n2', neblinkId: 'wanderer7', name: '路人甲', avatarUrl: '' }, note: '', status: 'pending', createdAt: epochSec(now - 86400e3) },
  ],
  outgoing: [],
  conversations: [
    { conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
      lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: epochSec(now - 60e3) }, unreadCount: 0 },
    { conversationId: 'c-few', friend: { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
      lastMessage: { id: 'f3', senderId: 'u-a', kind: 'text', body: 'few-3', createdAt: iso(now - 90e3) }, unreadCount: 0 },
    { conversationId: 'c-a', friend: { userId: 'u-a2', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
      lastMessage: { id: 'm-a2', senderId: 'u-a2', kind: 'text', body: 'Alice 的最新消息', createdAt: iso(now - 60e3) }, unreadCount: 1 },
  ],
  messages: {
    'c-p': PAG_MSGS,
    'c-few': [
      { id: 'f1', senderId: 'u-a', kind: 'text', body: 'few-1', createdAt: iso(now - 300e3) },
      { id: 'f2', senderId: 'me', kind: 'text', body: 'few-2', createdAt: iso(now - 180e3) },
      { id: 'f3', senderId: 'u-a', kind: 'text', body: 'few-3', createdAt: iso(now - 90e3) },
    ],
    'c-a': [
      { id: 'm-a0', senderId: 'u-a2', kind: 'text', body: 'Alice 的第一条', createdAt: iso(now - 300e3) },
      { id: 'm-a1', senderId: 'me', kind: 'text', body: '我之前发的', createdAt: iso(now - 240e3) },
      { id: 'm-a2', senderId: 'u-a2', kind: 'text', body: 'Alice 的最新消息', createdAt: iso(now - 60e3) },
    ],
  },
};

const browser = await chromium.launch();

async function bootPage({ locale = 'zh-CN', sessions = [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme });
  await ctx.addInitScript(([seedJson, loc]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', loc); localStorage.setItem('neblink_locale', loc);
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '350'); // 搜索 loading 态可观察
  }, [JSON.stringify(SEED), locale]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));           // catch-all first
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
  // （[U3] NL 号代理 mock 已删——旧 neblink-id 端点退役 + 设置入口移除，
  //  2026-09-05 10:54 裁定 / friend-search-contract §4.7。）
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
    ws.send(JSON.stringify({ type: 'sessionList', sessions, activeId: sessions[0]?.id || null, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
  await sleep(900);
  return { ctx, page, clientFrames, send: obj => serverWs.send(JSON.stringify(obj)) };
}

// ══ Page 1: zh 主场景 ═════════════════════════════════════
{
  const { page, clientFrames, send } = await bootPage();

  // ── T1 红点未见语义 + 申请行时间 ────────────────────────
  // 面板激活触发 refresh（boot 时 status 尚未回来，badge 为 0）——先开面板再断言。
  await page.click('#contacts-btn');
  await sleep(700);
  const badge0 = await page.$eval('#contacts-badge', e => e.textContent).catch(() => null);
  ok('T1a 两个未见申请 → contacts 钮 badge=2', badge0 === '2', `badge=${badge0}`);
  const nfBadge = await page.$eval('.fm-nf-entry .fm-row-badge', e => e.textContent).catch(() => null);
  ok('T1b 「新的朋友」entry badge=2（未见口径）', nfBadge === '2', `nf=${nfBadge}`);
  await page.click('.fm-nf-entry');
  await sleep(400);
  const reqRows = await page.$$eval('.fm-req-row', rows => rows.map(r => ({
    time: !!r.querySelector('.fm-req-time'),
    sub: r.querySelector('.fm-row-sub')?.textContent || '',
  })));
  ok('T1c 展开后 2 条申请行均带时间', reqRows.length === 2 && reqRows.every(r => r.time), JSON.stringify(reqRows));
  ok('T1d 验证消息优先展示（rq-1 note）', reqRows[0]?.sub === '你好，加我', reqRows[0]?.sub);
  const badgeAfterSeen = await page.$eval('#contacts-badge', e => e.textContent).catch(() => null);
  await page.click('.fm-nf-entry'); // 收起
  await sleep(400);
  const badge1 = await page.$eval('#contacts-badge', e => e.textContent).catch(() => null);
  ok('T1e 查看后红点清零（seen 持久化）', badge1 === null || badge1 === '' || badge1 === '0', `after=${badgeAfterSeen} now=${badge1}`);
  // REST 是事实源 —— 先注入 mock 请求行，再走 friend_event 触发 refresh
  await page.evaluate(async () => {
    const api = await import('/js/friendsApi.js');
    api.mockInjectIncomingRequest({ requestId: 'rq-3', from: { userId: 'u-n3', neblinkId: 'newcomer9', name: '新新', avatarUrl: '' }, note: '又来一个', status: 'pending', createdAt: Math.floor(Date.now() / 1000) });
  });
  send({ type: 'friend_event', event: 'friend_request', requestId: 'rq-3', from: { userId: 'u-n3', neblinkId: 'newcomer9', name: '新新', avatarUrl: '' }, note: '又来一个' });
  await sleep(1000);
  const badge2 = await page.$eval('#contacts-badge', e => e.textContent).catch(() => null);
  ok('T1f 新 friend_event → 红点重亮=1', badge2 === '1', `badge=${badge2}`);

  // ── T2 搜索打磨：未找到提示 / loading / 验证消息 Enter+取消 ──
  await page.fill('.fm-search-input', 'ghost404');
  await page.click('.fm-search-btn');
  await sleep(500);
  let cardTxt = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('T2a 未找到态：主文案+常识提示', cardTxt.includes('未找到该用户') && cardTxt.includes('尚未设置'), cardTxt.slice(0, 50));
  await sleep(800); // 1s 最小提交间隔
  await page.fill('.fm-search-input', 'slowpoke1');
  await page.click('.fm-search-btn');
  await sleep(120); // mock delay=350ms → 窗口期内按钮应处于 loading 态
  const loadingBtn = await page.$eval('.fm-search-btn', e => ({ txt: e.textContent, disabled: e.disabled })).catch(() => null);
  ok('T2b 搜索中按钮 loading 态禁用', !!loadingBtn && loadingBtn.disabled && loadingBtn.txt.includes('搜索中'), JSON.stringify(loadingBtn));
  await sleep(600);
  await sleep(800); // 间隔恢复
  await page.fill('.fm-search-input', 'newbie42');
  await page.click('.fm-search-btn');
  await sleep(500);
  await page.click('.fm-add-btn');
  await sleep(300);
  const verifyBox = await page.$eval('.fm-verify-box', e => ({
    input: !!e.querySelector('.fm-verify-input'),
    cancel: !!e.querySelector('.fm-verify-cancel'),
  })).catch(() => null);
  ok('T2c 验证消息输入框 + 取消按钮', !!verifyBox && verifyBox.input && verifyBox.cancel, JSON.stringify(verifyBox));
  await page.click('.fm-verify-cancel');
  await sleep(300);
  const backToAdd = await page.$('.fm-add-btn');
  ok('T2d 取消回退到加好友按钮', !!backToAdd);
  await page.click('.fm-add-btn');
  await sleep(300);
  await page.fill('.fm-verify-input', '求通过');
  await page.keyboard.press('Enter');
  await sleep(500);
  cardTxt = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('T2e Enter 发送验证消息 → 等待对方处理', cardTxt.includes('等待对方处理'), cardTxt.slice(0, 40));

  // ── T3 加载更早消息（c-p: 220 条，初始窗口 21..220）─────
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-p"]');
  await sleep(800);
  let state = await page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const ids = [...flow.querySelectorAll('.fm-msg')].map(w => w.dataset.messageId);
    return {
      count: ids.length,
      first: ids[0],
      last: ids[ids.length - 1],
      dup: new Set(ids).size !== ids.length,
      btn: !!flow.querySelector('.fm-load-more'),
      atBottom: flow.scrollTop >= flow.scrollHeight - flow.clientHeight - 2,
    };
  });
  ok('T3a 初始窗口 200 条（21..220）+ 加载按钮', state.count === 200 && state.first === '21' && state.last === '220' && state.btn, JSON.stringify({ ...state, btn: state.btn }));
  ok('T3b 无重复 id', !state.dup);
  // 滚到顶部再点击（视口稳定断言：补偿后不停留在底部）
  await page.$eval('.fm-flow', f => { f.scrollTop = 0; });
  await sleep(150);
  await page.click('.fm-load-more');
  await sleep(900);
  state = await page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const ids = [...flow.querySelectorAll('.fm-msg')].map(w => w.dataset.messageId);
    return {
      count: ids.length, first: ids[0], last: ids[ids.length - 1],
      dup: new Set(ids).size !== ids.length,
      btn: !!flow.querySelector('.fm-load-more'),
      atBottom: flow.scrollTop >= flow.scrollHeight - flow.clientHeight - 2,
    };
  });
  ok('T3c 补载后 220 条（1..220）按钮消失', state.count === 220 && state.first === '1' && state.last === '220' && !state.btn, JSON.stringify(state));
  ok('T3d 无重复 + 视口未跳底', !state.dup && !state.atBottom, `atBottom=${state.atBottom}`);
  // 短会话无按钮
  await page.click('.fm-modal-close');
  await sleep(400);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-few"]');
  await sleep(600);
  const fewBtn = await page.$eval('.fm-flow .fm-load-more', () => true).catch(() => false);
  ok('T3e 短会话（3 条）无加载按钮', !fewBtn);

  // ── T4 转发链：引用块入框 → 发送 → chip ────────────────
  await page.click('.fm-modal-close');
  await sleep(400);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
  await sleep(700);
  const mA0 = page.locator('.fm-msg[data-message-id="m-a0"]');
  await mA0.hover();
  await mA0.locator('.fm-msg-act[title="转发给 agent"]').click();
  await sleep(400);
  const fwd1 = await page.evaluate(() => ({
    refBlock: !!document.querySelector('#input-bar .att-ref-fm, .fa-input-bar .att-ref-fm'),
    toast: !document.querySelector('.fm-modal-toast')?.hidden && document.querySelector('.fm-modal-toast')?.textContent.includes('已加入输入框'),
    chip: !!document.querySelector('.fm-msg[data-message-id="m-a0"] .fm-msg-forwarded-badge'),
  }));
  ok('T4a 转发 → 主输入框引用块 + toast，未自动发、未打 chip', fwd1.refBlock && fwd1.toast && !fwd1.chip, JSON.stringify(fwd1));
  // 主输入框附言后发送 → wire 帧 refs + chip
  await page.fill('#input', '看这条');
  await page.keyboard.press('Enter');
  await sleep(700);
  const sentFrame = clientFrames.find(f => f.type === 'ask' || (f.refs !== undefined && f.content !== undefined));
  const refFrame = clientFrames.find(f => Array.isArray(f.refs) && f.refs.some(r => r.refType === 'friend-message'));
  ok('T4b 发送帧携带 friend-message ref', !!refFrame, JSON.stringify(refFrame?.refs?.[0]?.refType || null));
  const chip1 = await page.$eval('.fm-msg[data-message-id="m-a0"] .fm-msg-forwarded-badge', e => e.textContent).catch(() => null);
  ok('T4c 发送后「已转发」chip 出现', !!chip1, `chip=${chip1}`);

  // ── T5 NL 号设置区已移除（2026-09-05 10:54 裁定：NL 号 = 官网 Username，
  // 客户端不提供修改入口；friend-search-contract §4.7 契约切换非回归）────
  await page.click('.fm-modal-close');
  await sleep(400);
  await page.click('#settings-btn');
  await sleep(700);
  const nl = await page.evaluate(() => {
    const root = document.getElementById('settings-content');
    return {
      label: [...root.querySelectorAll('.neblink-section-label')].some(e => e.textContent === 'NL 号' || e.textContent === 'NebLink ID'),
      value: !!root.querySelector('.neblink-nlid-value'),
      editBtn: !!root.querySelector('#neblink-nlid-edit'),
      input: !!root.querySelector('#neblink-nlid-input'),
      cached: !!localStorage.getItem('neblink_id_custom'),
      devicesLabel: [...root.querySelectorAll('.neblink-section-label')].some(e => e.textContent.includes('设备')),
      logout: !!root.querySelector('#neblink-logout-btn'),
    };
  });
  ok('T5 NL 号修改入口零残留 + 设备区完好', !nl.label && !nl.value && !nl.editBtn && !nl.input && !nl.cached && nl.devicesLabel && nl.logout, JSON.stringify(nl));
  await page.close();
}

// ══ Page 2: 无活跃会话 → 转发引导 toast ═══════════════════
{
  const { page } = await bootPage({ sessions: [] });
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
  await sleep(700);
  const mA0 = page.locator('.fm-msg[data-message-id="m-a0"]');
  await mA0.hover();
  await mA0.locator('.fm-msg-act[title="转发给 agent"]').click();
  await sleep(400);
  const noSess = await page.evaluate(() => ({
    toast: !document.querySelector('.fm-modal-toast')?.hidden && document.querySelector('.fm-modal-toast')?.textContent.includes('请先在主界面打开一个会话'),
    refBlock: !!document.querySelector('.att-ref-fm'),
  }));
  ok('T6 无活跃会话转发 → 引导 toast + 不入框', noSess.toast && !noSess.refBlock, JSON.stringify(noSess));

  // en 抽查（同页换 en 上下文成本高——直接断言 en 资源在独立页面覆盖）
  await page.close();
}

// ══ Page 3: en 抽查 ══════════════════════════════════════
{
  const { page } = await bootPage({ locale: 'en' });
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-p"]');
  await sleep(800);
  const enBtn = await page.$eval('.fm-load-more', e => e.textContent).catch(() => '');
  ok('T7a en 加载按钮文案', enBtn === 'Load earlier messages', enBtn);
  await page.click('.fm-modal-close');
  await sleep(400);
  await page.click('#settings-btn');
  await sleep(700);
  const enNl = await page.evaluate(() => {
    const root = document.getElementById('settings-content');
    return {
      label: [...root.querySelectorAll('.neblink-section-label')].some(e => e.textContent === 'NebLink ID'),
      value: !!root.querySelector('.neblink-nlid-value'),
      devicesLabel: [...root.querySelectorAll('.neblink-section-label')].some(e => e.textContent.includes('Devices')),
    };
  });
  ok('T7b en 设置面板 NL 号区零残留 + 设备区完好', !enNl.label && !enNl.value && enNl.devicesLabel, JSON.stringify(enNl));
  await page.close();
}

// ══ Page 4/5: 双主题截图（目检 + 报告路径清单）════════════
async function shots(colorScheme, tag) {
  const { page } = await bootPage({ colorScheme });
  // 聊天弹窗 + 加载按钮 + 引用块
  await page.click('#messages-btn');
  await sleep(700);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-p"]');
  await sleep(800);
  await page.screenshot({ path: `${SHOTS}/friend-chain-chat-${tag}.png` });
  await page.click('.fm-modal-close');
  await sleep(400);
  // 联系人：申请展开
  await page.click('#contacts-btn');
  await sleep(500);
  await page.click('.fm-nf-entry');
  await sleep(400);
  await page.screenshot({ path: `${SHOTS}/friend-chain-contacts-${tag}.png` });
  // 设置面板（NL 号区已移除——截图留存设备区现状）
  await page.click('#settings-btn');
  await sleep(700);
  await page.screenshot({ path: `${SHOTS}/friend-chain-settings-${tag}.png` });
  await page.close();
}
await shots('light', 'light');
await shots('dark', 'dark');

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
