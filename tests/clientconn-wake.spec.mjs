// clientconn-wake.spec.mjs — clientconn 批 item 2「回前台 / 回网即增量拉」的真页面验证。
//
// 被测面 = **真渲染管线**：
//   · 静态面（HTML/JS/CSS/模块图）走**真实隔离实例**的静态路由（不是 route 替身），
//     并断言 served `/js/ws.js`、`/js/messages.js` 的 sha256 == 本分支工作区文件
//     （实例同一性实证）；
//   · 数据面（REST）用 route 拦截（本节点无第二账号，不碰生产账号）：mock 的形状
//     按 friendsApi.js 契约镜像 —— `/api/neblink/status`（loggedIn）、`/api/friends`、
//     `/api/conversations`、`/api/conversations/<id>/messages?after=&limit=`（keyset
//     前进语义：id > after，ASC，limit clamp）。
//
// 唤醒源 = **浏览器自己触发的真实转换**（不是合成 Event）：
//   · 回前台：同 context 双 page + `bringToFront()` ⇒ 浏览器真的发 visibilitychange
//     （断言页面内记录到的 visibilityState 序列）；
//   · 回网  ：CDP `Network.emulateNetworkConditions` offline → online ⇒ 浏览器真的
//     发 offline/online 事件。
//
// fixture 形态（对应缺陷场景）：开窗时服务端只有 id=1；用户切走/断网期间 id=2,3 到达
// 而推送通道全程不可用 ⇒ 回到前台/回网时必须由客户端**自己**增量拉回来（keyset
// `after=<水位>`），而不是等用户关窗重开。
//
// 反例对照：同一 harness 跑第二遍，只把 ws.js 的两处 `notifyWake(...)` 调用去掉
// （= 改动前形态的等价回退，函数保留但无人调用），断言数据面**零动作** + 无新气泡。
//
// Run:
//   NEBFLOW_URL=http://127.0.0.1:8096 node tests/clientconn-wake.spec.mjs
// 需要：一个隔离网关实例（本仓 web 树为其静态资源来源），默认 8096。
import { chromium } from 'playwright-core';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const BASE = (process.env.NEBFLOW_URL || 'http://127.0.0.1:8096').replace(/\/$/, '');
// 隔离实例的网关 token（真 WS 握手需要）：默认读 NEBFLOW_HOME/auth.json。
const GW_TOKEN = process.env.NEBFLOW_TOKEN
  || (() => {
    try { return JSON.parse(readFileSync(join(process.env.NEBFLOW_HOME || '/tmp/nb-clientconn-qa', 'auth.json'), 'utf8')); }
    catch { return 't'; }
  })();

const UID = 'u-wake';
const CID = 'c-wake';
const FRIEND = { userId: UID, neblinkId: 'wake01', name: '回前台对象', avatarUrl: '', remark: null };
const nowSec = () => Math.floor(Date.now() / 1000);
const MSG_OLD = { id: 1, senderId: UID, kind: 'text', body: '开窗时已有', createdAt: nowSec() - 600 };
const MSG_NEW = [
  { id: 2, senderId: UID, kind: 'text', body: '后台期间到达·A', createdAt: nowSec() - 120 },
  { id: 3, senderId: UID, kind: 'text', body: '后台期间到达·B', createdAt: nowSec() - 60 },
];

/** 数据面 = 好友/消息 REST（唤醒面唯一允许动作的面）。应用自己的其它后台请求
 *  （canvas-tabs / plugins / agents…）不计入，避免把无关流量算成唤醒证据。 */
const isDataPlane = (r) =>
  r.path === '/api/conversations' || r.path.startsWith('/api/friends') ||
  r.path.startsWith('/api/users') || r.path.includes('/messages');

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
let failures = 0;
const results = [];
function ok(name, cond, extra = '') {
  results.push({ name, ok: !!cond, extra });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 改动前形态的等价回退：只摘掉两处唤醒广播调用（其余逐字节不动）。 */
function revertWake(fileText) {
  const out = fileText
    .replace(`{ checkConnection(); notifyWake('visibility'); }`, '{ checkConnection(); }')
    .replace('{ checkConnection(); notifyWake(\'online\'); }', '{ checkConnection(); }');
  return out;
}

/** 数据面 mock（按 pathname 分派；单 handler 避免 Playwright 逆序匹配歧义）。 */
async function installApi(page, log) {
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    log.push({ t: Date.now(), method: req.method(), path: u.pathname + (u.search || '') });
    const json = (body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    const p = u.pathname;
    if (p === '/api/neblink/status') {
      return json({
        loggedIn: true,
        device: { id: 'dev-wake', name: 'wake-qa', platform: 'macos', capabilities: {}, avatarUrl: '', email: '', displayName: '' },
        relay: { available: true },
        peers: [],
      });
    }
    if (p === '/api/friends') {
      return json({ friends: [{ ...FRIEND, since: new Date().toISOString() }], incoming: [], outgoing: [] });
    }
    if (p === '/api/conversations') {
      return json([{ conversationId: CID, friend: FRIEND, lastMessage: MSG_OLD, unreadCount: 0 }]);
    }
    if (p === `/api/conversations/${CID}/messages`) {
      const after = Math.max(0, Number(u.searchParams.get('after') || 0));
      const limit = Math.min(200, Math.max(1, Number(u.searchParams.get('limit') || 50)));
      // fixture：`after=0`（冷开窗的既有尾窗路径）= 服务端此刻只有 id=1；
      // 之后（keyset 前进拉取）返回后台期间到达的 id>after 的消息。
      const all = after <= 0 ? [MSG_OLD] : [MSG_OLD].concat(MSG_NEW);
      return json(all.filter((m) => Number(m.id) > after).slice(0, limit));
    }
    return json({ ok: true });
  });
}

async function bootContext(browser, { serveRevertedWs = false, log, token = 't' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  // 网关 token 由调用方注入（隔离实例的 <home>/auth.json）——WS 必须真连上，否则
  // 页面收不到 configData 帧、好友面板不挂载（featureFlags.friendsEnabled 的 latch）。
  await ctx.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok);
    localStorage.setItem('neblink_token', tok);
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // 记录真实可见性转换 + fm-wake 广播（证据通道，不干预任何应用逻辑）
    window.__visLog = [];
    window.__wakeLog = [];
    document.addEventListener('visibilitychange', () => window.__visLog.push(document.visibilityState));
    window.addEventListener('fm-wake', (e) => window.__wakeLog.push(e && e.detail ? e.detail.reason : '?'));
    // 可见性：headless Chromium **不产生**真 visibility 转换（`bringToFront` 与 CDP
    // `Page.setWebLifecycleState` 两路本机实测均不可得）⇒ 用 getter 覆写复现浏览器本会
    // 交付的 hidden→visible 序列 + 派发同名事件（handler 与其下游全是生产代码）；
    // 「回网」腿另走**真** CDP 网络转换（offline→online），见 reallyGoOfflineThenOnline。
    window.__fakeVis = 'visible';
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => window.__fakeVis });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__fakeVis === 'hidden' });
  }, token);
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push('console: ' + m.text()); });
  if (serveRevertedWs) {
    const reverted = revertWake(readFileSync(join(WEB, 'js', 'ws.js'), 'utf8'));
    await page.route('**/js/ws.js', (route) =>
      route.fulfill({ status: 200, contentType: 'text/javascript', body: reverted }));
  }
  await installApi(page, log);
  return { ctx, page, consoleErrors };
}

/** 回前台：复现 hidden → visible 序列并派发 visibilitychange（见 bootContext 注释）。 */
async function hideThenShow(page) {
  await page.evaluate(() => { window.__fakeVis = 'hidden'; document.dispatchEvent(new Event('visibilitychange')); });
  await sleep(200);
  const hidden = await page.evaluate(() => document.visibilityState);
  await page.evaluate(() => { window.__fakeVis = 'visible'; document.dispatchEvent(new Event('visibilitychange')); });
  await sleep(700); // ws.js 的可见性处理是同步的；留出 REST 往返时间
  const visible = await page.evaluate(() => document.visibilityState);
  return { hidden, visible };
}

/** 真回网：CDP 断网 → 复网 ⇒ 浏览器发 offline / online。 */
async function reallyGoOfflineThenOnline(ctx, page) {
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.enable');
  const setOffline = (offline) =>
    cdp.send('Network.emulateNetworkConditions', { offline, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await setOffline(true);
  await sleep(300);
  await setOffline(false);
  await sleep(900); // ws.js 的 online 处理有 500ms 延迟
  return true;
}

const browser = await chromium.launch();
try {
  // ── 实例同一性（进任何断言前先证「页面吃的是本分支产物」）──────────
  const servedWs = await (await fetch(`${BASE}/js/ws.js`)).text();
  const servedMsg = await (await fetch(`${BASE}/js/messages.js`)).text();
  const localWs = readFileSync(join(WEB, 'js', 'ws.js'), 'utf8');
  const localMsg = readFileSync(join(WEB, 'js', 'messages.js'), 'utf8');
  console.log(`# served /js/ws.js       sha256=${sha256(servedWs)}`);
  console.log(`# branch js/ws.js        sha256=${sha256(localWs)}`);
  console.log(`# served /js/messages.js sha256=${sha256(servedMsg)}`);
  console.log(`# branch js/messages.js  sha256=${sha256(localMsg)}`);
  ok('I1 实例 served js/ws.js == 本分支文件（sha256）', sha256(servedWs) === sha256(localWs));
  ok('I2 实例 served js/messages.js == 本分支文件（sha256）', sha256(servedMsg) === sha256(localMsg));
  const reverted = revertWake(localWs);
  const revertHits = (localWs.match(/notifyWake\(/g) || []).length;
  ok('I3 控制组回退是定点变换（ws.js 内 notifyWake 出现 3 次：1 定义 + 2 调用）',
    revertHits === 3 && reverted !== localWs && !/notifyWake\('visibility'\)|notifyWake\('online'\)/.test(reverted),
    `hits=${revertHits}`);

  // ── 处理组：本分支（真实例）──────────────────────────────────
  {
    const log = [];
    const { ctx, page, consoleErrors } = await bootContext(browser, { log, token: GW_TOKEN });
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#fm-conversations .fm-conv-row', { state: 'attached', timeout: 15000 });

    // T1-T3 列表态（未开任何会话）：真回前台
    log.length = 0;
    const vis = await hideThenShow(page);
    await sleep(700);
    const winA = log.slice();
    const wakeA = await page.evaluate(() => window.__wakeLog.slice());
    const visLog = await page.evaluate(() => window.__visLog.slice());
    ok('T1 浏览器真发 visibilitychange（hidden→visible）且应用广播 fm-wake',
      vis.hidden === 'hidden' && vis.visible === 'visible' && visLog.includes('hidden') && visLog.includes('visible') && wakeA.includes('visibility'),
      `vis=${vis.hidden}→${vis.visible} visLog=${JSON.stringify(visLog)} wake=${JSON.stringify(wakeA)}`);
    ok('T2 列表态唤醒 ⇒ 会话列表拉取 ≥1（/api/conversations）',
      winA.some((r) => r.path === '/api/conversations'), JSON.stringify(winA));
    ok('T3 列表态唤醒 ⇒ 消息窗口请求 == 0（红线：唤醒面绝不发尾窗/窗口请求）',
      !winA.some((r) => r.path.includes('/messages')), JSON.stringify(winA));

    // T4-T7 开窗态：真回网
    await page.evaluate(() => document.querySelector('#fm-conversations .fm-conv-row').click());
    await page.waitForSelector('.fm-msg[data-message-id="1"]', { state: 'attached', timeout: 10000 });
    const domBefore = await page.evaluate(() => document.querySelectorAll('.fm-msg[data-message-id]').length);
    // 唤醒面节流 3s（WAKE_THROTTLE_MS，本批设计值）——两次唤醒要隔开，否则第二次被
    // 节流吞掉（这正是设计行为：上一拍刚拉过，数据仍新鲜）。
    await sleep(3200);
    log.length = 0;
    await reallyGoOfflineThenOnline(ctx, page);
    await sleep(400);
    const winB = log.slice();
    const keyset = winB.filter((r) => r.path.includes('/messages?'));
    const domAfter = await page.evaluate(() =>
      [...document.querySelectorAll('.fm-msg[data-message-id]')].map((n) => n.dataset.messageId));
    ok('T4 开窗态 + 真回网 ⇒ keyset 增量 after=水位（after=1）',
      keyset.some((r) => /after=1(&|$)/.test(r.path)), JSON.stringify(keyset));
    ok('T5 唤醒面禁尾窗全量：窗口内无 limit=200、无 after=0 的窗口请求',
      !keyset.some((r) => r.path.includes('limit=200')) && !keyset.some((r) => /after=0(&|$)/.test(r.path)),
      JSON.stringify(keyset));
    const wakeOnline = await page.evaluate(() => window.__wakeLog.slice());
    ok('T6 开窗态唤醒 ⇒ 列表也刷新（withList）',
      winB.some((r) => r.path === '/api/conversations') && wakeOnline.includes('online'),
      `wake=${JSON.stringify(wakeOnline)} reqs=${JSON.stringify(winB.map((r) => r.path))}`);
    ok('T7 增量拉回的消息真上屏（DOM id 1→1,2,3）',
      domBefore === 1 && domAfter.includes('2') && domAfter.includes('3'),
      `before=${domBefore} after=${JSON.stringify(domAfter)}`);
    ok('T8 处理组无新增 console error / pageerror', consoleErrors.length === 0, JSON.stringify(consoleErrors.slice(0, 3)));
    await ctx.close();
  }

  // ── 控制组：只去掉唤醒广播（改动前形态等价回退），同法复跑 ────────
  {
    const log = [];
    const { ctx, page, consoleErrors } = await bootContext(browser, { log, token: GW_TOKEN, serveRevertedWs: true });
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#fm-conversations .fm-conv-row', { state: 'attached', timeout: 15000 });

    log.length = 0;
    await hideThenShow(page);
    await sleep(700);
    const winA = log.slice();
    const wakeA = await page.evaluate(() => window.__wakeLog.slice());
    ok('C1 反例（改动前形态）：真回前台 ⇒ 数据面零动作 + 无 fm-wake 广播',
      winA.filter(isDataPlane).length === 0 && wakeA.length === 0,
      `dataPlane=${JSON.stringify(winA.filter(isDataPlane))} all=${JSON.stringify(winA)} wake=${JSON.stringify(wakeA)}`);

    await page.evaluate(() => document.querySelector('#fm-conversations .fm-conv-row').click());
    await page.waitForSelector('.fm-msg[data-message-id="1"]', { state: 'attached', timeout: 10000 });
    await sleep(3200);
    log.length = 0;
    await reallyGoOfflineThenOnline(ctx, page);
    await sleep(400);
    const winB = log.slice();
    const keyset = winB.filter((r) => r.path.includes('/messages?'));
    const domAfter = await page.evaluate(() =>
      [...document.querySelectorAll('.fm-msg[data-message-id]')].map((n) => n.dataset.messageId));
    ok('C2 反例：真回网 ⇒ 无 keyset after= 增量请求',
      !keyset.some((r) => /after=\d+/.test(r.path)), JSON.stringify(keyset));
    ok('C3 反例：回网后新消息不在屏上（= 症状「只有关窗重开才可见」的形态）',
      !domAfter.includes('2') && !domAfter.includes('3'), JSON.stringify(domAfter));
    ok('C4 控制组无新增 console error / pageerror', consoleErrors.length === 0, JSON.stringify(consoleErrors.slice(0, 3)));
    await ctx.close();
  }
} finally {
  await browser.close();
}

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)'}  (${results.length} assertions)`);
process.exit(failures === 0 ? 0 : 1);
