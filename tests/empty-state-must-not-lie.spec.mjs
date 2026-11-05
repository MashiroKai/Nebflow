// empty-state-must-not-lie.spec.mjs — 「失败不许伪装成空态」前端验收（2026-11-05 /
// empty-state-must-not-lie 批 · 件一）。
//
// 事故（改前现场）：`GET /api/conversations` 的 route case 被 `6c1feea6d` 误删 ⇒ 网关
// 回 404 ⇒ `friendsApi.getConversations()` reject ⇒ `messages.js` 的
// `catch { /* keep last known */ }` 吞掉 ⇒ 列表落到 `conversations = []` ⇒ 渲染
// `messages.empty`「暂无会话」。**故障被画成正常空列表**，一天无人发现。
//
// 本 spec 判据（逐条二值，无「大致」「看起来」）：
//   L1 取数 500  ⇒ 面板**不得**出现空态文案（`messages.empty`），**必须**出现错误态
//                    （`.fm-list-error` + 重试键）；
//   L2 错误态可重试 ⇒ 点重试、后端恢复 ⇒ 会话行出现、错误态消失（一条都不许残留）；
//   L3 真·空态 ⇒ 服务端**明确答复** `200 []` 时才出现空态文案，且错误态不在场；
//   L4 网络失败（fetch reject，无 status）⇒ 同 L1（不得落到空态）；
//   L5 404（事故现场的**原始状态码**）⇒ 同 L1 —— 这条是本次事故的复现位；
//   L6 错误态之后**成功一次** ⇒ 错误态清零（不得粘滞）。
//
// 运行：
//   node tests/empty-state-must-not-lie.spec.mjs                     # 改后树（应全绿）
//   EMPTY_STATE_WEB_ROOT=/tmp/before-web node tests/…spec.mjs       # 改前树（L1/L2/L4/L5 应红）
//
// 离线自足：随机端口静态服务器（只读磁盘上的 web 树）+ `page.route` 真桩；
// 不碰宿主实例（6138）任何一面，零网络、零凭据。PROBE 读数与判据同拍打印。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
/** 被测前端树（改前/改后对照腿用同一个 spec）。 */
const WEB = process.env.EMPTY_STATE_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
/** 文案针的来源 = **本仓** locale 表（不取被测树）：改前树上没有新键，取被测树会让
 *  BEFORE 腿因为「针本身不存在」而红，那是假红；取本仓才检验「改前树在失败时
 *  渲染的是空态文案」这个真判据。 */
const REPO_WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const ZH = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'zh-CN.js')).href)).default;

const EMPTY_COPY = ZH['messages.empty']; // 「暂无会话」
const FAIL_COPY = ZH['messages.listLoadFailed'] || ''; // 「会话列表加载失败」
const RETRY_COPY = ZH['messages.retry'] || ''; // 「重试」

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务器（随机隔离端口；finally 必关）──────────────────────────
const MIME = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
};
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end('not found');
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// ── REST 真桩：会话列表腿四态可切 ─────────────────────────────────────
/** 'ok' | 'empty' | 'http500' | 'http404' | 'abort' */
let MODE = 'ok';
const ROW = {
  conversationId: 'c-ok',
  friend: { userId: 'u-1', username: 'zhangsan', display_name: '张三', avatar: '', relation_status: 'friend' },
  lastMessage: { id: 1, senderId: 'u-1', kind: 'text', body: '在吗', createdAt: new Date().toISOString() },
  unreadCount: 0,
};
const CALLS = [];

function statusBody() {
  return {
    loggedIn: true,
    relay: { available: false, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
    device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
    peers: [],
  };
}

/** 会话列表腿的状态码读数（错误态判据的直接证据）。 */
const listResponses = [];

async function routeApi(route) {
  const u = new URL(route.request().url());
  const p = u.pathname;
  CALLS.push({ p, method: route.request().method() });
  if (p === '/api/neblink/status') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statusBody()) });
  }
  if (p === '/api/conversations') {
    if (MODE === 'abort') {
      listResponses.push('abort');
      return route.abort('failed');
    }
    if (MODE === 'http500') {
      listResponses.push(500);
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'boom' }) });
    }
    if (MODE === 'http404') {
      // 事故现场的状态码（路由消失 ⇒ 网关 404）
      listResponses.push(404);
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'Not Found' }) });
    }
    if (MODE === 'empty') {
      listResponses.push(200);
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    listResponses.push(200);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([ROW]) });
  }
  if (p === '/api/friends') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [ROW.friend], incoming: [], outgoing: [] }) });
  }
  if (p === '/api/groups' || p === '/api/groups/invites') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  }
  if (/^\/api\/conversations\/[^/]+\/messages$/.test(p)) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
}

const browser = await chromium.launch();

async function bootPage() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.route('**/api/**', routeApi);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(700);
  return { ctx, page, pageErrors };
}

/** 打开消息面板并等列表腿落地。 */
async function openPanel(page) {
  await page.click('#messages-btn');
  await sleep(800);
}

/** 面板读数：错误态 / 空态 / 行数 / 全文。 */
async function panelState(page) {
  return page.evaluate(() => {
    const box = document.getElementById('fm-conversations');
    if (!box) return { present: false };
    return {
      present: true,
      text: box.innerText || '',
      rows: box.querySelectorAll('.fm-conv-row').length,
      errorBoxes: box.querySelectorAll('.fm-list-error').length,
      retry: box.querySelectorAll('.fm-list-retry').length,
      emptyBoxes: box.querySelectorAll('.fm-empty').length,
      authBoxes: box.querySelectorAll('.fm-login-empty').length,
    };
  });
}

function sig(s) {
  return JSON.stringify({ rows: s.rows, err: s.errorBoxes, retry: s.retry, empty: s.emptyBoxes, auth: s.authBoxes });
}

console.log(`# served tree: ${WEB}`);
console.log(`# needles: empty="${EMPTY_COPY}" fail="${FAIL_COPY}" retry="${RETRY_COPY}"`);

// ══ L1 / L2（同一页：失败 → 重试恢复）════════════════════════════════
{
  MODE = 'http500';
  const before = listResponses.length;
  const { ctx, page, pageErrors } = await bootPage();
  await openPanel(page);
  const s = await panelState(page);

  ok('L1-a 会话列表确实被请求了（失败不是「没发请求」）', listResponses.length > before, `listResponses=${listResponses.slice(before).join(',')}`);
  ok('L1-b 500 ⇒ **不渲染**空态文案「暂无会话」', !s.text.includes(EMPTY_COPY) && s.emptyBoxes === 0, sig(s));
  ok('L1-c 500 ⇒ 渲染错误态（.fm-list-error + 重试键）', s.errorBoxes === 1 && s.retry === 1, sig(s));
  ok('L1-d 错误态文案 = 状态事实（`messages.listLoadFailed`）', !!FAIL_COPY && s.text.includes(FAIL_COPY), sig(s));
  ok('L1-e 错误态先于登录态（NebLink 已登录时不落到登录引导）', s.authBoxes === 0 && s.errorBoxes === 1, sig(s));

  // L2：后端恢复 ⇒ 重试即回到列表
  MODE = 'ok';
  const beforeRetry = listResponses.length;
  let retried = true;
  try {
    await page.click('.fm-list-retry', { timeout: 5000 });
  } catch {
    retried = false; // 改前树没有重试键 ⇒ 本腿红（不是崩，读数要落下来）
  }
  await sleep(900);
  const r = await panelState(page);
  ok('L2-a 错误态带可点的重试键', retried, sig(s));
  ok('L2-b 重试确实重新取了会话列表', listResponses.length > beforeRetry && listResponses[listResponses.length - 1] === 200, `listResponses=${listResponses.slice(beforeRetry).join(',')}`);
  ok('L2-c 重试成功 ⇒ 会话行上屏', r.rows >= 1, sig(r));
  ok('L2-d 重试成功 ⇒ 错误态整块消失（不残留）', r.errorBoxes === 0 && r.retry === 0, sig(r));
  ok('L2-e 全程零 page error', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

  await ctx.close();
}

// ══ L3（真·空态：服务端明确答复没有会话）═══════════════════════════
{
  MODE = 'empty';
  const before = listResponses.length;
  const { ctx, page } = await bootPage();
  await openPanel(page);
  const s = await panelState(page);
  ok('L3-a 200 + [] ⇒ 空态文案出现（这是唯一允许它出现的条件）', s.text.includes(EMPTY_COPY) && s.emptyBoxes === 1, sig(s));
  ok('L3-b 200 + [] ⇒ 错误态**不**在场（空态与错误态互斥）', s.errorBoxes === 0 && s.retry === 0, sig(s));
  ok('L3-c 空态那次请求的确是 200', listResponses[listResponses.length - 1] === 200, `listResponses=${listResponses.slice(before).join(',')}`);
  await ctx.close();
}

// ══ L4（网络失败：fetch reject，无 status）══════════════════════════
{
  MODE = 'abort';
  const { ctx, page } = await bootPage();
  await openPanel(page);
  const s = await panelState(page);
  ok('L4-a 网络失败 ⇒ **不渲染**空态文案', !s.text.includes(EMPTY_COPY) && s.emptyBoxes === 0, sig(s));
  ok('L4-b 网络失败 ⇒ 渲染错误态', s.errorBoxes === 1 && s.retry === 1, sig(s));
  await ctx.close();
}

// ══ L5（事故现场原始状态码 404）═════════════════════════════════════
{
  MODE = 'http404';
  const before = listResponses.length;
  const { ctx, page } = await bootPage();
  await openPanel(page);
  const s = await panelState(page);
  ok('L5-a 404（路由消失）⇒ **不渲染**空态文案 —— 本次事故的复现位', !s.text.includes(EMPTY_COPY) && s.emptyBoxes === 0, sig(s));
  ok('L5-b 404 ⇒ 渲染错误态（可见、可重试）', s.errorBoxes === 1 && s.retry === 1, sig(s));
  ok('L5-c 404 那次请求确实是 404', listResponses[listResponses.length - 1] === 404, `listResponses=${listResponses.slice(before).join(',')}`);
  await ctx.close();
}

// ══ L6（错误态不粘滞：失败 → 成功一次 ⇒ 清零）══════════════════════
{
  MODE = 'http500';
  const { ctx, page } = await bootPage();
  await openPanel(page);
  const failed = await panelState(page);
  MODE = 'ok';
  try {
    await page.click('.fm-list-retry', { timeout: 5000 });
  } catch {
    /* 改前树无重试键：下面的读数自然会红，不崩 */
  }
  await sleep(900);
  const recovered = await panelState(page);
  // 再切回失败：错误态必须能再次出现（不只是「一次性的消失」）
  MODE = 'http500';
  await page.click('#contacts-btn');
  await sleep(500);
  await page.click('#messages-btn');
  await sleep(900);
  const failedAgain = await panelState(page);
  ok(
    'L6 错误态不粘滞且可重复进入（失败→成功→再失败）',
    failed.errorBoxes === 1 && recovered.errorBoxes === 0 && recovered.rows >= 1 && failedAgain.errorBoxes === 1,
    `failed=${JSON.stringify(sig(failed))} recovered=${JSON.stringify(sig(recovered))} again=${JSON.stringify(sig(failedAgain))}`
  );
  await ctx.close();
}

await browser.close();
server.close();

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
