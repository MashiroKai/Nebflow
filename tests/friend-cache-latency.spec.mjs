// friend-cache-latency.spec.mjs — ⑨ 缓存与增量 + ①opt-A3 降级兜底 验收 spec
// （方案件 §2.3/§5.2 M1–M6、§2.1/§5.1 P2/P4；作者预授权令 #297 逐条口径）。
//
// 与既有 friend-*.spec.mjs 的关键差别：**不用 `fm_api_mock`**。M1/M4 的判据是
// 「往返次数 × 字节数」，而 fm_api_mock 是在 `friendsApi.req()` 之前就把请求
// 短路掉的（零 HTTP），量不到任何东西。本 spec 因此把 REST 换成
// `page.route('**/api/**')` 的**进程内真桩**：按同一契约（keyset 前进游标
// `id > after ORDER BY id ASC`、`limit` 1..200 clamp）应答，并逐条登记
// `{path, method, query, bytes}` —— 请求数与字节数都是真读数。
//
// WS 面用 `page.routeWebSocket` 打桩（既有基建），可注入任意服务端帧。
//
// 计量口径（写死在这里，判据不可事后重解释）：
//  · R_MESSAGES = 某会话的窗口拉取（`GET /api/conversations/{cid}/messages`）
//    —— M1③/M1④/M3/M4①/M6 的分母。
//  · 「尾窗全量」= 形如 `limit=200`（= HISTORY_WINDOW）的窗口拉取。M1③ 红线。
//  · 不计入：`GET /api/neblink/status`（既有 10s 状态 beacon，本批零改动）、
//    `POST /api/conversations/{id}/read`（既有已读回执；不是取数请求）。
//  · 字节 = JSON 响应体的 `.length`（UTF-16 code unit；与
//    `persistence.js` 的 localStorage 计量口径同源）。
//
// Run: node tests/friend-cache-latency.spec.mjs
//   FM_WEB_ROOT=<path>  覆盖被测前端树（默认本仓 src/main/resources/web；
//                       用于 M6「冷缓存不倒退」的跨树基线对照）
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.FM_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 内置静态服务器（随机隔离端口；finally 必关 —— 进程清理纪律）──────
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.woff2': 'font/woff2' };
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// ── REST 真桩（服务端契约镜像）─────────────────────────────
const EPOCH = Math.floor(Date.now() / 1000);
const iso = s => new Date(s * 1000).toISOString();
const msg = (id, senderId, body, atSec) => ({ id, senderId, kind: 'text', body, createdAt: iso(atSec) });

/** cA = ids 1..40（会话首条 id = 1 ⇒ ②-7 确定态，探针不发）。
 *  cB = ids 101..300（200 条；会话首条 id ≠ 1 ⇒ 探针会发，按增量页计）。 */
function freshState() {
  const cA = [];
  for (let i = 1; i <= 40; i++) cA.push(msg(i, i % 2 ? 'u-p' : 'me', `A-${i}`, EPOCH - (41 - i) * 60));
  const cB = [];
  for (let i = 101; i <= 300; i++) cB.push(msg(i, i % 2 ? 'u-p' : 'me', `B-${i}`, EPOCH - (301 - i) * 60));
  const friends = [{ userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }];
  // 大好友列表（M4② 的意义所在：面板切换不再重取它）
  for (let i = 0; i < 120; i++) friends.push({ userId: `u-x${i}`, neblinkId: `user${i}@example.com`, name: `好友${i}`, avatarUrl: '', remark: null, since: iso(EPOCH - 86400) });
  const convs = [
    { conversationId: 'cA', friend: friends[0], lastMessage: cA[cA.length - 1], unreadCount: 0 },
    { conversationId: 'cB', friend: friends[1], lastMessage: cB[cB.length - 1], unreadCount: 0 },
    { conversationId: 'cC', friend: friends[2], lastMessage: null, unreadCount: 0 },
  ];
  return { convs, msgs: { cA, cB, cC: [] }, friends, relayAvailable: true };
}

let ST = freshState();
let CALLS = [];
const resetCalls = () => { CALLS = []; };
const callsTo = (p) => CALLS.filter(c => c.p === p && c.method === 'GET');
const msgCalls = (cid) => callsTo(`/api/conversations/${cid}/messages`);
const tailCalls = (cid) => msgCalls(cid).filter(c => c.get('limit') === '200');
const bytesOf = (list) => list.reduce((s, c) => s + c.bytes, 0);

function apiBody(p, q, method) {
  if (p === '/api/neblink/status') {
    return { loggedIn: true, relay: { available: ST.relayAvailable, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' }, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' }, peers: [] };
  }
  if (p === '/api/conversations') return ST.convs;
  if (p === '/api/friends') return { friends: ST.friends, incoming: [], outgoing: [] };
  const m = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
  if (m) {
    const cid = decodeURIComponent(m[1]);
    const after = Math.max(0, Math.floor(Number(q.get('after')) || 0));
    const limit = Math.min(200, Math.max(1, Math.floor(Number(q.get('limit')) || 50)));
    const all = (ST.msgs[cid] || []).filter(x => Number(x.id) > after);
    return all.slice(0, limit);
  }
  if (/^\/api\/conversations\/[^/]+\/read$/.test(p)) return {};
  return {};
}

const browser = await chromium.launch();

async function bootPage({ viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', (route) => {
    const u = new URL(route.request().url());
    const method = route.request().method();
    const body = JSON.stringify(apiBody(u.pathname, u.searchParams, method));
    CALLS.push({ p: u.pathname, method, get: (k) => u.searchParams.get(k), bytes: body.length, query: u.search });
    return route.fulfill({ status: 200, contentType: 'application/json', body });
  });
  let serverWs = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    serverWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
  await sleep(900);
  return { ctx, page, pageErrors, send: obj => serverWs && serverWs.send(JSON.stringify(obj)) };
}

/** 打开消息面板并等 refresh 落地。 */
async function openMessagesPanel(page) {
  await page.click('#messages-btn');
  await sleep(600);
}
async function openConv(page, cid) {
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`);
  await sleep(700);
}
async function closeConv(page) {
  await page.click('.fm-modal-close');
  await sleep(400);
}
async function domIds(page) {
  return page.$$eval('.fm-flow .fm-msg', ws => ws.map(w => w.dataset.messageId));
}
async function serverIds(cid) {
  return (ST.msgs[cid] || []).map(m => String(m.id));
}

// ══ Page 1: M1 / M3 / M4 / M5 / P2 / P4 ══════════════════
{
  const { page, pageErrors, send } = await bootPage();
  await openMessagesPanel(page);

  // ── M1② 再次开面板（无变更）= 1 往返；好友列表命中 L1 ⇒ 0 ──
  {
    await page.click('#contacts-btn');
    await sleep(600);
    resetCalls();
    await openMessagesPanel(page);
    const listCalls = callsTo('/api/conversations');
    const frCalls = callsTo('/api/friends');
    ok('M1② 再次开面板 = 1 次 /api/conversations 且 0 次 /api/friends',
      listCalls.length === 1 && frCalls.length === 0,
      JSON.stringify({ conversations: listCalls.length, friends: frCalls.length }));
  }

  // ── M1③ 开会话（无新消息）≤1 且非尾窗 ──
  {
    await openConv(page, 'cA');          // 冷缓存首开：= 今天基线（1 次尾窗）
    const cold = msgCalls('cA');
    const coldBytes = bytesOf(cold);
    ok('M6-baseline 冷缓存首开 = 1 次尾窗拉取（今天形态）',
      cold.length === 1 && tailCalls('cA').length === 1,
      JSON.stringify({ calls: cold.map(c => c.query), bytes: coldBytes }));
    await closeConv(page);

    resetCalls();
    await openConv(page, 'cA');          // 热缓存再开
    const warm = msgCalls('cA');
    const warmBytes = bytesOf(warm);
    ok('M1③ 热缓存开会话（无新消息）= 1 次请求且非尾窗',
      warm.length === 1 && warm[0].get('limit') === '50' && warm[0].get('after') === '40' && tailCalls('cA').length === 0,
      JSON.stringify({ calls: warm.map(c => c.query), bytes: warmBytes }));
    const ids = await domIds(page);
    ok('M1③ 首屏 = 同步读缓存（40 条，零等待）',
      ids.length === 40 && ids[0] === '1' && ids[39] === '40',
      JSON.stringify({ count: ids.length, first: ids[0], last: ids[ids.length - 1] }));
  }

  // ── M1④ 有 1 条新消息 = 1 次增量 ──
  {
    await closeConv(page);
    ST.msgs.cA.push(msg(41, 'u-p', 'A-41', EPOCH));
    ST.convs[0].lastMessage = ST.msgs.cA[ST.msgs.cA.length - 1];
    resetCalls();
    await openConv(page, 'cA');
    const c = msgCalls('cA');
    const ids = await domIds(page);
    ok('M1④ 有 1 条新消息 = 1 次增量请求（after=水位，非尾窗）',
      c.length === 1 && c[0].get('after') === '40' && c[0].get('limit') === '50' && tailCalls('cA').length === 0,
      JSON.stringify({ calls: c.map(x => x.query) }));
    ok('M1④ 增量补齐到窗口（41 条，末条 = 41，无重复）',
      ids.length === 41 && ids[ids.length - 1] === '41' && new Set(ids).size === ids.length,
      JSON.stringify({ count: ids.length, last: ids[ids.length - 1] }));
  }

  // ── M3 端到端新消息（WS 帧）≤1 帧渲染 & 零取数请求 ──
  {
    resetCalls();
    const t0 = Date.now();
    const frame42 = [42, 'u-p', 'A-42', EPOCH];
    ST.msgs.cA.push(msg(...frame42));          // 服务端确实存下了（M5 分母）
    ST.convs[0].lastMessage = ST.msgs.cA[ST.msgs.cA.length - 1];
    send({
      type: 'friend_event', event: 'message_new',
      messageId: 42, conversationId: 'cA', senderId: 'u-p', sender: { userId: 'u-p', displayName: '分页君' },
      kind: 'text', body: 'A-42', origin: 'user', createdAt: iso(EPOCH), createdAtMs: EPOCH * 1000,
    });
    let ids = [];
    for (let i = 0; i < 30; i++) { ids = await domIds(page); if (ids.includes('42')) break; await sleep(50); }
    const dt = Date.now() - t0;
    const fetches = msgCalls('cA');
    ok('M3 WS 帧 ≤1 帧渲染 & 取数请求 = 0', ids.includes('42') && fetches.length === 0 && dt <= 300,
      JSON.stringify({ rendered: ids.includes('42'), ms: dt, fetchCalls: fetches.length, domCount: ids.length }));
  }

  // ── P2 契约对齐：真形状帧（网关 post-①opt-A2 扁平帧）──────────
  {
    resetCalls();
    ST.msgs.cA.push(msg(43, 'u-p', 'A-43', EPOCH));
    ST.convs[0].lastMessage = ST.msgs.cA[ST.msgs.cA.length - 1];
    send({ type: 'friend_event', event: 'message_new', messageId: 43, conversationId: 'cA', senderId: 'u-p', kind: 'text', body: 'A-43', createdAt: iso(EPOCH) });
    let ids = [];
    for (let i = 0; i < 30; i++) { ids = await domIds(page); if (ids.includes('43')) break; await sleep(50); }
    ok('P2a 真形状扁平帧 → 打开的会话窗 append（未走 refreshConversations 早退）',
      ids.includes('43') && callsTo('/api/conversations').length === 0,
      JSON.stringify({ appended: ids.includes('43'), listCalls: callsTo('/api/conversations').length }));
    // 防御面：未展平的嵌套帧（老网关/直连形态）不得炸 —— 走「未知会话 → 刷列表」容错分支
    const before = pageErrors.length;
    resetCalls();
    send({ type: 'friend_event', eventId: 'e-nested', event: { type: 'message_new', payload: { messageId: 99, conversationId: 'cA', sender: { userId: 'u-p' }, kind: 'text', body: 'A-99', createdAt: iso(EPOCH) } } });
    await sleep(600);
    ok('P2b 嵌套帧（非网关形态）零 page error（容错分支不炸）',
      pageErrors.length === before, JSON.stringify({ pageErrors: pageErrors.slice(before) }));
  }

  // ── M5 一致性门：渲染 id 序列 vs 服务端 id 序列（红线：任何不等）──
  {
    const ids = await domIds(page);
    const sids = await serverIds('cA');
    const start = sids.indexOf(ids[0]);
    const contiguous = start >= 0 && ids.every((x, i) => sids[start + i] === x);
    ok('M5a 渲染 id 序列 = 服务端序列的连续窗口（无缺口/无重复/严格升序）',
      contiguous && new Set(ids).size === ids.length && ids[ids.length - 1] === sids[sids.length - 1],
      JSON.stringify({ rendered: ids.length, server: sids.length, contiguous, tail: ids[ids.length - 1] }));
    await closeConv(page);
  }

  // ── M4① 重复打开 200 条会话 ≤1 KB ──
  {
    resetCalls();
    await openConv(page, 'cB');   // 冷：今天基线（尾窗 200 条）
    const coldBytes = bytesOf(msgCalls('cB'));
    await closeConv(page);
    resetCalls();
    await openConv(page, 'cB');   // 热
    const warm = msgCalls('cB');
    const warmBytes = bytesOf(warm);
    ok('M4① 重复打开 200 条会话：热开窗口取数 ≤1 KB（且零尾窗）',
      warmBytes <= 1024 && tailCalls('cB').length === 0,
      JSON.stringify({ coldBytes, warmBytes, warmCalls: warm.map(c => c.query) }));
    // M5b：热开窗的渲染序列同样是服务端序列的连续窗口（尾部 50 条）
    const ids = await domIds(page);
    const sids = await serverIds('cB');
    const start = sids.indexOf(ids[0]);
    const contiguous = start >= 0 && ids.every((x, i) => sids[start + i] === x);
    ok('M5b cB 热开窗渲染序列连续且末条 = 服务端最新',
      contiguous && ids[ids.length - 1] === sids[sids.length - 1] && new Set(ids).size === ids.length,
      JSON.stringify({ rendered: ids.length, serverMax: sids[sids.length - 1], tail: ids[ids.length - 1], contiguous }));
    await closeConv(page);
  }

  // ── M4② 面板切换 ≤3 KB（好友列表 120 条不再重取）──
  {
    await page.click('#contacts-btn');
    await sleep(600);
    resetCalls();
    await openMessagesPanel(page);
    const bytes = bytesOf(callsTo('/api/conversations'));
    ok('M4② 面板切换总取数 ≤3 KB（/api/friends 0 次）',
      bytes <= 3072 && callsTo('/api/friends').length === 0,
      JSON.stringify({ bytes, listCalls: callsTo('/api/conversations').length, friendCalls: callsTo('/api/friends').length }));
  }

  // ── ①opt-A3 健康路径：relay 可用 ⇒ 零额外取数 ──
  {
    await openConv(page, 'cA');
    ST.relayAvailable = true;
    ST.msgs.cA.push(msg(44, 'u-p', 'A-44', EPOCH));
    resetCalls();
    await sleep(12000);   // ≥1 拍 beacon
    const fetches = msgCalls('cA');
    const ids = await domIds(page);
    ok('①opt-A3 健康路径（relay.available=true）零额外取数（12s 观察窗）',
      fetches.length === 0 && !ids.includes('44'),
      JSON.stringify({ fetchCalls: fetches.length, appended: ids.includes('44'), statusBeacon: callsTo('/api/neblink/status').length }));
  }

  // ── P4 降级兜底：relay 不可用 ⇒ ≤30s 凭 REST 增量显示 ──
  {
    ST.relayAvailable = false;              // 人为打死隧道（status 报不可用）
    ST.msgs.cA.push(msg(45, 'u-p', 'A-45', EPOCH));
    ST.convs[0].lastMessage = ST.msgs.cA[ST.msgs.cA.length - 1];
    resetCalls();
    const t0 = Date.now();
    let ids = [];
    for (let i = 0; i < 60; i++) { ids = await domIds(page); if (ids.includes('45') && ids.includes('44')) break; await sleep(500); }
    const dt = Date.now() - t0;
    const c = msgCalls('cA');
    ok('P4 降级兜底：relay 不可用 ⇒ 打开窗 ≤30s 自动补到服务端水位',
      ids.includes('44') && ids.includes('45') && dt <= 30000 && c.length > 0 && tailCalls('cA').length === 0 && c[0].get('after') === '43',
      JSON.stringify({ ms: dt, appended: [ids.includes('44'), ids.includes('45')], calls: c.map(x => x.query) }));
    ST.relayAvailable = true;
    await closeConv(page);
  }

  ok('零 page error（整页）', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
  await page.close();
}

// ══ Page 2: M6 冷缓存不倒退（新上下文 ⇒ 空缓存 ⇒ 走今天基线路径）═══
{
  const { page } = await bootPage();
  await openMessagesPanel(page);
  resetCalls();
  await openConv(page, 'cB');
  const c = msgCalls('cB');
  // 冷缓存的**窗口拉取**必须与今天同形（1 次 `after=<anchor-200>&limit=200`）；
  // ②-7 存在性探针是既有机制（不新增、不可去），本批只把它从「整窗 200 条」
  // 压到 `limit=1`（判据等价，见 messages.js PROBE_LIMIT 注释）⇒ 总字节只降不升。
  const wins = c.filter(x => x.get('limit') === '200');
  ok('M6 冷缓存窗口拉取 = 1 次尾窗（今天形态，未新增窗口取数）',
    wins.length === 1 && wins[0].get('after') === '100' && tailCalls('cB').length === 1,
    JSON.stringify({ calls: c.map(x => x.query), bytes: bytesOf(c) }));
  ok('M6b 既有存在性探针降为 limit=1（占位 ≤128 B）',
    c.filter(x => x.get('limit') === '1').length === 1,
    JSON.stringify({ probes: c.filter(x => x.get('limit') === '1').map(x => x.bytes) }));
  console.log(`M6-READOUT tree=${WEB} coldBytes=${bytesOf(c)} coldCalls=${c.length} coldQueries=${JSON.stringify(c.map(x => x.query))}`);
  // ⑨-6 键迁移：裸键存量值启动即迁入 key() 命名空间
  const keys = await page.evaluate(() => ({
    legacy: Object.keys(localStorage).filter(k => k === 'fm_trusted' || k === 'fm_seen_requests' || k === 'fm_blocked'),
    namespaced: Object.keys(localStorage).filter(k => k === 'nebflow_fm_trusted' || k === 'nebflow_fm_seen_requests' || k === 'nebflow_fm_blocked'),
  }));
  ok('⑨-6 裸键零残留（迁移后命名空间键存在）', keys.legacy.length === 0, JSON.stringify(keys));
  await page.close();
}

// ══ Page 3: ⑨-6 存量迁移（预置裸键 → 启动即迁，值不丢）═══════════
{
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 800 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_trusted', JSON.stringify(['u-p']));
    localStorage.setItem('fm_seen_requests', JSON.stringify(['rq-1']));
    localStorage.setItem('fm_blocked', JSON.stringify(['u-z']));
  });
  const page = await ctx.newPage();
  await page.route('**/api/**', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiBody(new URL(r.request().url()).pathname, new URL(r.request().url()).searchParams, r.request().method())) }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
  await sleep(600);
  const migrated = await page.evaluate(() => ({
    legacy: ['fm_trusted', 'fm_seen_requests', 'fm_blocked'].filter(k => localStorage.getItem(k) !== null),
    trusted: localStorage.getItem('nebflow_fm_trusted'),
    seen: localStorage.getItem('nebflow_fm_seen_requests'),
    blocked: localStorage.getItem('nebflow_fm_blocked'),
  }));
  ok('⑨-6 裸键 → key() 命名空间迁移（三键全迁、存量值不丢）',
    migrated.legacy.length === 0 && migrated.trusted === '["u-p"]' && migrated.seen === '["rq-1"]' && migrated.blocked === '["u-z"]',
    JSON.stringify(migrated));
  await page.close();
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
