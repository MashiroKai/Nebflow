// friend-contract-align.spec.mjs — 好友 UI 契约对齐验收（friend-search-contract
// v1.0+§8，批次 friend-contract-align；作者指令 2026-09-05）。
//
// 覆盖（断言 = 契约条目 → 实现位 逐项钉死，对照
// .nebflow/Spec/friend-ui-contract-alignment.md）：
//   M1~M10 mock 模式（fm_api_mock seed，契约同形实现）：
//     M1  addable → 「添加」按钮（含 @ 邮箱双键命中，§4.1）
//     M2  already_friends → 「发消息」按钮
//     M3  outgoing_pending → 「等待对方处理」文案
//     M4  incoming_pending → 「回应请求」+ accept/decline（同意后好友落地）
//     M5  blocked_by_me → 已拉黑标记 + 「取消拉黑」入口（添加不可用）
//     M6  self → 「这是你自己」（username 命中）
//     M7  email 双键命中（不含 username 的邮箱串）
//     M8  miss → 未找到卡（found:false）
//     M9  旧形态 seed（neblinkId/name）→ 回退读仍可搜可加
//     M10 addable 加好友全流程：验证消息 Enter 直发 → 等待对方处理；
//         wire query=username（outgoing.to 内部形态）
//   D1~D7 直连模式（Playwright 路由拦截模拟已部署网关+服务端，wire=契约原形）：
//     D1  客户端调用 /api/users/search（端点切换钉死：调旧 lookup 则拦截
//         不命中 → catch-all {} → 断言必红）+ snake_case 消费（addable）
//     D2  relation_status=already_friends（契约原形 wire）→ 「发消息」
//     D3  miss 恒 {found:false} → 未找到卡
//     D4  422 invalid_query → 搜索失败卡（失败分态，09-06 作者令；曾兜底为
//         未找到卡——失败与空结果混态已拆分）
//     D5  outgoing_pending（直连契约态）→ 等待对方处理
//     D6  旧端点零残留：全程无 /api/users/lookup、/api/users/me/neblink-id 请求
//     D7  设置面板 NL 号修改入口零残留（10:54 裁定），设备区仍渲染
//   I1  i18n parity（zh=en 键数相等）+ 新契约键双语齐全 + contacts.*/messages.*
//       键值 NL 号字样零残留
//
// mock/直连双模式全绿；静态服务器随机隔离端口，finally 必关（进程清理纪律）。
// Run: node tests/friend-contract-align.spec.mjs
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 内置静态服务器（随机隔离端口；finally 关闭）──────────────
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.woff2': 'font/woff2' };
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// mock seed：目录用户覆盖 relation 六态 + 一条旧形态用户（friend-chain-ui 旧
// seed 形态，accessor 回退读兼容面）。
const SEED = {
  self: { userId: 'me', username: 'selfme', email: 'self@example.com', displayName: '我自己', avatar: '' },
  users: [
    { userId: 'u-x', username: 'xiaoming', email: 'xm@example.com', displayName: '小明', avatar: '' },     // addable
    { userId: 'u-f', username: 'buddy7', email: 'b7@example.com', displayName: '老友', avatar: '' },        // already_friends
    { userId: 'u-o', username: 'outgoing1', email: 'o1@example.com', displayName: '在途君', avatar: '' },   // outgoing_pending
    { userId: 'u-i', username: 'incoming1', email: 'i1@example.com', displayName: '来求君', avatar: '' },   // incoming_pending
    { userId: 'u-b', username: 'blocked1', email: 'b1@example.com', displayName: '拉黑君', avatar: '' },    // blocked_by_me
    { userId: 'u-old', neblinkId: 'oldbie77', name: '旧形态', avatarUrl: '' },                              // addable（旧形态 seed）
  ],
  friends: [
    { userId: 'u-f', neblinkId: 'buddy7', name: '老友', avatarUrl: '', since: new Date().toISOString() },
    { userId: 'u-b', neblinkId: 'blocked1', name: '拉黑君', avatarUrl: '', since: new Date().toISOString(), blocked: true },
  ],
  incoming: [{ requestId: 'rq-i1', from: { userId: 'u-i', neblinkId: 'incoming1', name: '来求君', avatarUrl: '' }, note: '加我', status: 'pending' }],
  outgoing: [{ requestId: 'rq-o1', to: { userId: 'u-o', neblinkId: 'outgoing1', name: '在途君', avatarUrl: '' }, note: '', status: 'pending' }],
  conversations: [], messages: {},
};

// 直连模式契约原形 DB（snake_case wire，模拟已部署 neblink-server + 网关出参）
const WIRE_DB = {
  alice: { userId: 'u-a', username: 'alice', display_name: '爱丽丝', avatar: '' },
  bob: { userId: 'u-bob', username: 'bob', display_name: '鲍勃', avatar: '' },
  waiting: { userId: 'u-w', username: 'waiting', display_name: '等回君', avatar: '' },
};

const browser = await chromium.launch();

async function bootMockPage() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(([seedJson]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '30');
  }, [JSON.stringify(SEED)]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
  await sleep(900);
  return { ctx, page };
}

async function search(page, q) {
  // submit 式 + 1s 最小提交间隔：填词 → 点按钮 → 等结果卡
  await page.fill('.fm-search-input', q);
  await page.click('.fm-search-btn');
  await sleep(400);
}

async function cardText(page) {
  return page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
}

try {
  // ══ Mock 模式：六态 → 按钮映射（契约 §4.1 逐态）════════════
  {
    const { ctx, page } = await bootMockPage();
    await page.click('#contacts-btn');
    await sleep(700);

    await search(page, 'xiaoming');
    let card = await cardText(page);
    let addVisible = await page.$('.fm-result-card .fm-add-btn');
    ok('M1 addable → 「添加」按钮', !!addVisible, card.slice(0, 50));

    await sleep(700);
    await search(page, 'buddy7');
    card = await cardText(page);
    const msgBtn = await page.$('.fm-result-card .fm-msg-btn');
    ok('M2 already_friends → 「发消息」按钮', !!msgBtn && card.includes('发消息'), card.slice(0, 50));

    await sleep(700);
    await search(page, 'outgoing1');
    card = await cardText(page);
    ok('M3 outgoing_pending → 等待对方处理', card.includes('等待对方处理'), card.slice(0, 50));

    await sleep(700);
    await search(page, 'incoming1');
    card = await cardText(page);
    const acc = await page.$('.fm-result-card .fm-req-accept');
    const dec = await page.$('.fm-result-card .fm-req-decline');
    ok('M4a incoming_pending → 回应请求 + accept/decline', card.includes('回应请求') && !!acc && !!dec, card.slice(0, 60));
    if (acc) {
      await acc.click();
      await sleep(600);
      const friendsNow = await page.evaluate(async () => {
        const api = await import('/js/friendsApi.js');
        return (await api.getFriends()).friends.map(f => f.userId);
      });
      ok('M4b 同意后好友落地（u-i 入列）', friendsNow.includes('u-i'), JSON.stringify(friendsNow));
    } else {
      ok('M4b 同意后好友落地（u-i 入列）', false, 'accept button missing');
    }

    await sleep(700);
    await search(page, 'blocked1');
    card = await cardText(page);
    const ub = await page.$('.fm-result-card .fm-unblock-btn');
    const addOnBlocked = await page.$('.fm-result-card .fm-add-btn');
    ok('M5 blocked_by_me → 已拉黑标记 + 取消拉黑（无添加钮）', card.includes('已拉黑') && card.includes('取消拉黑') && !!ub && !addOnBlocked, card.slice(0, 60));

    await sleep(700);
    await search(page, 'selfme');
    card = await cardText(page);
    ok('M6 self → 这是你自己', card.includes('这是你自己'), card.slice(0, 50));

    await sleep(700);
    await search(page, 'b7@example.com');
    card = await cardText(page);
    const msgBtn2 = await page.$('.fm-result-card .fm-msg-btn');
    ok('M7 email 双键命中（b7@example.com → 老友 + already_friends 态）', card.includes('老友') && !!msgBtn2, card.slice(0, 50));

    await sleep(700);
    await search(page, 'ghost404');
    card = await cardText(page);
    ok('M8 miss → 未找到卡', card.includes('未找到该用户'), card.slice(0, 50));

    await sleep(700);
    await search(page, 'oldbie77');
    card = await cardText(page);
    const addOld = await page.$('.fm-result-card .fm-add-btn');
    ok('M9 旧形态 seed 仍可搜可加（回退读）', card.includes('旧形态') && !!addOld, card.slice(0, 50));

    // M10 addable 全流程：验证消息 Enter 直发 → 等待对方处理；wire query=username
    await sleep(700);
    await search(page, 'xiaoming');
    await page.click('.fm-result-card .fm-add-btn');
    await sleep(300);
    await page.fill('.fm-verify-input', '求通过');
    await page.keyboard.press('Enter');
    await sleep(500);
    card = await cardText(page);
    ok('M10a 验证消息 Enter 直发 → 等待对方处理', card.includes('等待对方处理'), card.slice(0, 50));
    const wire = await page.evaluate(async () => {
      const api = await import('/js/friendsApi.js');
      return ((await api.getFriends()).outgoing || []).map(o => o.to?.neblinkId);
    });
    ok('M10b wire query=username（outgoing.to 含 xiaoming）', wire.includes('xiaoming'), JSON.stringify(wire));
    await ctx.close();
  }

  // ══ 直连模式：端点切换 + 契约原形 wire 消费（路由拦截=已部署形态）═══
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
      // 不设 fm_api_mock → 直连；fetch 被下面路由拦截为契约响应
    });
    const page = await ctx.newPage();
    page.on('pageerror', e => console.log('[pageerror]', e.message));
    const apiCalls = [];
    page.on('request', req => { if (req.url().includes('/api/')) apiCalls.push(req.url()); });  // 全量登记（路由拦截之外的观测面）
    await page.route('**/api/**', r => r.fulfill({ json: {} }));   // catch-all first
    await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
    // 契约端点拦截：/api/users/search 按 q 返回 contract 原形（snake_case wire）
    await page.route('**/api/users/search*', r => {
      const q = (new URL(r.request().url()).searchParams.get('q') || '').trim().toLowerCase();
      if (q === 'alice') r.fulfill({ json: { found: true, user: WIRE_DB.alice, relation_status: 'addable' } });
      else if (q === 'bob') r.fulfill({ json: { found: true, user: WIRE_DB.bob, relation_status: 'already_friends' } });
      else if (q === 'waiting') r.fulfill({ json: { found: true, user: WIRE_DB.waiting, relation_status: 'outgoing_pending' } });
      else if (q === 'toolongquery') r.fulfill({ status: 422, json: { error: 'invalid_query' } });
      else r.fulfill({ json: { found: false } });
    });
    await page.routeWebSocket(/\/ws/, ws => {
      ws.onMessage(() => {});
      ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });
    await page.goto(BASE + '/index.html');
    await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
    await sleep(900);
    await page.click('#contacts-btn');
    await sleep(700);

    await search(page, 'alice');
    let card = await cardText(page);
    const addD = await page.$('.fm-result-card .fm-add-btn');
    const searchHit = apiCalls.some(u => u.includes('/api/users/search'));
    ok('D1 端点切换 /api/users/search + snake_case 消费（addable→添加钮）', !!addD && searchHit && card.includes('爱丽丝'), `searchHit=${searchHit} ${card.slice(0, 40)}`);

    await sleep(700);
    await search(page, 'bob');
    card = await cardText(page);
    const msgD = await page.$('.fm-result-card .fm-msg-btn');
    ok('D2 契约原形 already_friends → 发消息', !!msgD && card.includes('发消息'), card.slice(0, 40));

    await sleep(700);
    await search(page, 'waiting');
    card = await cardText(page);
    ok('D5 直连 outgoing_pending → 等待对方处理', card.includes('等待对方处理'), card.slice(0, 40));

    await sleep(700);
    await search(page, 'ghost');
    card = await cardText(page);
    ok('D3 miss 恒 found:false → 未找到卡', card.includes('未找到该用户'), card.slice(0, 40));

    await sleep(700);
    await search(page, 'toolongquery');
    card = await cardText(page);
    ok('D4 422 invalid_query → 搜索失败卡（失败分态，非「未找到」）', card.includes('搜索失败'), card.slice(0, 40));

    // D6 旧端点零残留：全程请求扫描（lookup / neblink-id 设置链）
    const legacy = apiCalls.filter(u => u.includes('/api/users/lookup') || u.includes('neblink-id'));
    ok('D6 旧端点零残留（lookup / neblink-id 全程零调用）', legacy.length === 0, JSON.stringify(legacy));

    // D7 设置面板 NL 号修改入口零残留 + 设备区仍渲染
    await page.click('#settings-btn');
    await sleep(700);
    const nl = await page.evaluate(() => {
      const root = document.getElementById('settings-content');
      return {
        nlidValue: !!root.querySelector('.neblink-nlid-value'),
        editBtn: !!root.querySelector('#neblink-nlid-edit'),
        input: !!root.querySelector('#neblink-nlid-input'),
        devicesLabel: [...root.querySelectorAll('.neblink-section-label')].some(e => e.textContent.includes('设备')),
        logout: !!root.querySelector('#neblink-logout-btn'),
      };
    });
    ok('D7 设置面板：NL 号修改入口零残留 + 设备区完好', !nl.nlidValue && !nl.editBtn && !nl.input && nl.devicesLabel && nl.logout, JSON.stringify(nl));
    await ctx.close();
  }

  // ══ I1 i18n parity + 契约键齐全 + 好友域 NL 号字样零残留 ══
  {
    // 复用全路由 boot（浏览器页面内动态 import 两个 locale 模块——node 不支持 http import）
    const { ctx: i18nCtx, page: i18nPage } = await bootMockPage();
    const i18n = await i18nPage.evaluate(async () => {
      const zh = (await import('/js/locales/zh-CN.js')).default;
      const en = (await import('/js/locales/en.js')).default;
      const bad = [];
      for (const [loc, table] of [['zh', zh], ['en', en]]) {
        for (const [k, v] of Object.entries(table)) {
          if ((k.startsWith('contacts.') || k.startsWith('messages.')) && /NL 号|NL号|NebLink ID|nebflow 号|nebflow ID/i.test(String(v))) bad.push(`${loc}:${k}=${v}`);
        }
      }
      return { zh, en, zhN: Object.keys(zh).length, enN: Object.keys(en).length, bad };
    });
    ok('I1a i18n parity zh=en', i18n.zhN === i18n.enN, `zh=${i18n.zhN} en=${i18n.enN}`);
    const needKeys = ['contacts.sendMessage', 'contacts.outgoingPending', 'contacts.respondRequest', 'contacts.unblock'];
    const haveZh = needKeys.every(k => i18n.zh[k]); const haveEn = needKeys.every(k => i18n.en[k]);
    ok('I1b 新契约键双语齐全（sendMessage/outgoingPending/respondRequest/unblock）', haveZh && haveEn, JSON.stringify({ haveZh, haveEn }));
    ok('I1c contacts.*/messages.* 键值 NL 号字样零残留', i18n.bad.length === 0, JSON.stringify(i18n.bad));
    ok('I1d 死键零残留（pendingVerification / neblink.nlId*）',
      !('contacts.pendingVerification' in i18n.zh) && !Object.keys(i18n.zh).some(k => k.startsWith('neblink.nlId'))
      && !('contacts.pendingVerification' in i18n.en) && !Object.keys(i18n.en).some(k => k.startsWith('neblink.nlId')));
    await i18nCtx.close();
  }
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
