// friend-trust-sealed.spec.mjs — ⑥ 信任好友封存护栏（方案 §4.2 定稿 / 作者裁定
// 2026-09-12：新增独立 flag `TRUST_SEALED`，默认 true = 封存生效）。
//
// 断言面（§4.2「必须新增的护栏测试」）：
//   F1  好友行右键菜单**无**「信任此好友 / 取消信任」项（另有对照：删除/拉黑项仍在，
//       防「菜单根本没打开」的假绿）
//   F2  会话窗头**无** `fm-trust-slot`（另有对照：窗头本体与好友名在位）
//   F3  预置 `localStorage.fm_trusted=[<uid>]` 后新到 INCOMING **不**产生草案：
//       F3a 预置确实生效（`isFriendTrusted()` 读回 true）；F3b 消息事件链活着
//       （新消息渲染进会话）；F3c 输入框未变 + 主输入栏无 `.att-ref-fm` 引用块
//   F4  回退后 F1–F3 全反转（**不做生产代码改动**的自动化形态）：把 web 树复制到临时
//       目录、只在该副本里把 `TRUST_SEALED` 改成 `false`（= 回退步骤 1 的等价模拟，
//       回退步骤 2 的 build 对 source 树行为无影响、步骤 3「宿主 8080 需重启生效」属
//       部署面），断言：菜单项回来（「取消信任」）/ 窗头槽回来 / 新到消息**产生**草案
//       （主输入栏出现 `.att-ref-fm`）。副本用完即删（临时夹具，生产代码零改动）。
//
// 隔离：本 spec 自包含——route 拦截静态文件（直接从 web 树读盘）+ routeWebSocket mock，
// 无后端、无真实端口、不碰宿主 8080。好友 REST 面走 friendsApi.js 的 `fm_api_mock` seed
// （与 friend-chain-ui.spec.mjs 同款注入口径）。
// Run: node tests/friend-trust-sealed.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, cpSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const UID = 'u-trust';          // 好友 userId（= fm_trusted / friend_event senderId 值域）
const CID = 'c-trust';          // 会话 id
const PROBE = 'F3-PROBE-新到消息';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

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
    lastMessage: { id: 1, senderId: UID, kind: 'text', body: '早前消息', createdAt: nowSec() - 600 },
    unreadCount: 0,
  }],
  messages: { [CID]: [{ id: 1, senderId: UID, kind: 'text', body: '早前消息', createdAt: nowSec() - 600 }] },
};

const browser = await chromium.launch();

/**
 * bootPage — 自包含页面（静态文件从 webRoot 读盘；WS = mock）。
 * @param {{webRoot?: string, trustedSeed?: boolean}} opts
 */
async function bootPage({ webRoot = WEB, trustedSeed = true } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(([seedJson, trustedJson]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    // ⑥-D：存量标记（封存不清数据）——F3 预置就是「用户早已信任过」的形态
    localStorage.setItem('fm_trusted', trustedJson);
  }, [JSON.stringify(SEED), JSON.stringify(trustedSeed ? [UID] : [])]);
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  // catch-all first — 后注册的 route 优先（Playwright 逆序匹配）
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(webRoot, p));
    if (!file.startsWith(normalize(webRoot))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  // 好友面板/会话链的前置：neblink 已登录（loggedIn() 才放行 refresh）
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  let serverWs = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    serverWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 15000 });
  await sleep(700);
  return { ctx, page, pageErrors, send: obj => serverWs.send(JSON.stringify(obj)) };
}

/** 打开联系人面板 → 右键好友行 → 返回菜单项文案数组（+ 对照标记）。 */
async function openFriendMenu(page) {
  if (await page.locator('.fm-context-menu').count()) await page.keyboard.press('Escape');
  if (!(await page.locator('#panel-contacts.active').count())) {
    await page.click('#contacts-btn');
    await sleep(500);
  }
  await page.waitForSelector('.fm-friend-row', { timeout: 10000 });
  await page.locator('.fm-friend-row').first().click({ button: 'right' });
  await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
  const labels = await page.$$eval('.fm-context-menu button', bs => bs.map(b => b.textContent.trim()));
  const rowOk = await page.locator('.fm-friend-row').first().isVisible();
  return { labels, rowOk };
}

/** 打开好友会话弹窗 → 返回窗头槽计数 / 窗头在位 / 输入框当前值。 */
async function openChat(page) {
  if (await page.locator('.fm-context-menu').count()) await page.keyboard.press('Escape');
  if (!(await page.locator('.fm-modal-header').count())) {
    if (!(await page.locator('#panel-messages.active').count())) {
      await page.click('#messages-btn');
      await sleep(600);
    }
    await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${CID}"]`);
    await page.waitForSelector('.fm-modal-header', { timeout: 10000 });
    await sleep(500);
  }
  return {
    slots: await page.locator('.fm-modal-header .fm-trust-slot').count(),
    headerOk: await page.locator('.fm-modal-header .fm-modal-name').count() === 1,
    input: await page.inputValue('.fm-input').catch(() => null),
  };
}

/** 新到 INCOMING（trusted 好友）——返回 {rendered, input, refBlocks, trustedReadback}。 */
async function probeIncoming(page, send) {
  const before = await page.inputValue('.fm-input').catch(() => null);
  const trustedReadback = await page.evaluate(async (uid) => {
    const m = await import('/js/messages.js');
    return m.isFriendTrusted(uid);
  }, UID);
  send({
    type: 'friend_event', event: 'message_new', conversationId: CID, messageId: 9001,
    senderId: UID, kind: 'text', body: PROBE, createdAt: nowSec(),
  });
  await sleep(900);
  return {
    before,
    trustedReadback,
    rendered: await page.locator('.fm-msg', { hasText: PROBE }).count(),
    input: await page.inputValue('.fm-input').catch(() => null),
    refBlocks: await page.locator('#input-bar .att-ref-fm, .fa-input-bar .att-ref-fm').count(),
  };
}

// ══ 封存态（生产默认：TRUST_SEALED = true）══════════════════════
const sealed = await bootPage();
{
  const { page, pageErrors, send } = sealed;
  const menu = await openFriendMenu(page);
  ok('F1 好友行右键菜单无信任项（封存）', menu.rowOk && !menu.labels.some(l => l === '信任此好友' || l === '取消信任'),
    JSON.stringify(menu.labels));
  ok('F1 对照：菜单本体仍产出 删除/拉黑 项', menu.labels.includes('删除好友') && menu.labels.includes('加入黑名单'),
    JSON.stringify(menu.labels));

  const chat = await openChat(page);
  ok('F2 会话窗头无 fm-trust-slot（封存）', chat.headerOk && chat.slots === 0, JSON.stringify(chat));

  const p = await probeIncoming(page, send);
  ok('F3a 预置 localStorage.fm_trusted 生效（isFriendTrusted=true）', p.trustedReadback === true, `readback=${p.trustedReadback}`);
  ok('F3b 对照：新到消息事件链活着（消息渲染进会话）', p.rendered === 1, `rendered=${p.rendered}`);
  ok('F3c 封存：新到 INCOMING 不产生草案（输入框未变 + 无引用块）',
    p.input === p.before && p.input === '' && p.refBlocks === 0,
    `before=${JSON.stringify(p.before)} after=${JSON.stringify(p.input)} refs=${p.refBlocks}`);
  ok('F1-F3 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await sealed.ctx.close();
}

// ══ 回退态（F4：临时副本树把常量改回 false，生产代码零改动）═══════
{
  const tmp = mkdtempSync(join(tmpdir(), 'fm-trust-sealed-'));
  const patchTarget = join(tmp, 'web', 'js', 'featureFlags.js');
  const patched = 'F4 夹具：把副本 featureFlags.js 的 TRUST_SEALED 改成 false（模拟回退步骤 1）';
  // 行锚定：注释里也会出现该常量名，裸字符串 replace 会命中注释而非声明（首版踩坑）。
  const DECL_TRUE = /^export const TRUST_SEALED = true;$/m;
  const DECL_FALSE = /^export const TRUST_SEALED = false;$/m;
  try {
    cpSync(WEB, join(tmp, 'web'), { recursive: true });
    const src = readFileSync(patchTarget, 'utf8');
    if (!DECL_TRUE.test(src)) {
      ok(patched, false, '副本里找不到 TRUST_SEALED 的 true 声明行 — 常量形态变了，夹具需同步');
    } else {
      writeFileSync(patchTarget, src.replace(DECL_TRUE, 'export const TRUST_SEALED = false;'));
      const after = readFileSync(patchTarget, 'utf8');
      ok(patched, DECL_FALSE.test(after) && !DECL_TRUE.test(after));
    }
    const rollback = await bootPage({ webRoot: join(tmp, 'web') });
    const { page, pageErrors, send } = rollback;
    const menu = await openFriendMenu(page);
    // 预置 trusted ⇒ 回退态菜单项应为「取消信任」
    ok('F4a 回退后菜单信任项回来（取消信任）', menu.rowOk && menu.labels.includes('取消信任'), JSON.stringify(menu.labels));

    const chat = await openChat(page);
    ok('F4b 回退后窗头 fm-trust-slot 回来', chat.headerOk && chat.slots === 1, JSON.stringify(chat));

    const p = await probeIncoming(page, send);
    ok('F4c 回退后新到 INCOMING 产生草案（主输入栏引用块）', p.refBlocks === 1,
      `refs=${p.refBlocks} rendered=${p.rendered} input=${JSON.stringify(p.input)}`);
    ok('F4 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await rollback.ctx.close();
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);
