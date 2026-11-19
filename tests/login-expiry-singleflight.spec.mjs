// login-expiry-singleflight.spec.mjs — 登录失效弹窗循环 + 旧域跳转修复批
// （作者 2026-10-31 07:33/07:36 手机实测报障 + 07:37 根因令）的自包含回归钉。
//
// 被测面（客户端半边，全部真 Chromium + 真模块图 + 真 DOM 事件）：
//   ① 自动登录链零 window.open —— fm-auth-required 自动开的是**页面内登录卡**
//     （deferPopup），微信内置浏览器「attempting to open a pop-up」弹窗循环根断；
//      卡内只留真手势的「重新打开登录页面」恢复键，无「弹窗被拦截」toast。
//   ② 登录失效单例判定 —— friendsApi.notifyAuthRequired() 闩：一个失效回合
//      全站只派一次 fm-auth-required（401 轮询齐炸不重演）；fm-auth-resolved
//      （登录态翻转）复位后下一段失效照常首派。
//   ③ 联系人失效块单例 —— 同一 listErrorKind='auth' 驱动的好友列表区 +
//      「新的朋友」请求区同屏时，失效文案两处可见但「重新登录」动作键全面板
//      只挂一枚（修前同屏两块各自为政）。
//   ④ 登录页打开链 wiring —— 真手势键 window.open 的目标是网关下发的
//      authorizeUrl（本镜像回 auth.mystand.space —— 现役托管登录域；Scala 侧
//      内嵌默认域由 NeblinkModelSpec 钉）。
//
// 场景形态：NebLink 本机凭据活着（/api/neblink/status loggedIn:true）但好友域
//  会话已死（/api/friends 恒 403）——正是作者报障时的真实形态：boot 即触发的
//  好友取数 403 ⇒ 首派 fm-auth-required ⇒ 页面内登录卡自动单飞（无手势、无弹窗）。
//
// 🔴 诚实申报（与 rcptcode spec 同款纪律）：本 spec 的「网关」= 页内 route 的
//   契约镜像；覆盖**前端消费链**。零实例依赖：不起 gateway（无 sbt、零端口占用）。
// 运行：node tests/login-expiry-singleflight.spec.mjs

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttt',
};

const SRV = { authStartCalls: 0 };

const browser = await chromium.launch();
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const body = await readFile(join(WEB, path === '/' ? 'index.html' : path));
    res.writeHead(200, { 'Content-Type': MIME[extname(path)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'dead-token');
  localStorage.setItem('neblink_token', 'dead-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('neblink_locale', 'zh-CN');
  window.__authRequired = 0;
  window.addEventListener('fm-auth-required', () => { window.__authRequired += 1; });
});
let popupCount = 0;

const page = await ctx.newPage();
// 只数主窗派生的 popup（ctx.on('page') 会把 newPage() 本体也算进来，不可用作弹窗计数）。
page.on('popup', () => { popupCount += 1; });
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(String(e.message || e).slice(0, 200)));

// 契约镜像：NebLink 凭据活着、好友域会话已死（恒 403 无语义码 = 被踢/失效形态）。
await page.route('**/api/**', async (route) => {
  const p = new URL(route.request().url()).pathname;
  const json = (status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  if (p === '/api/neblink/status') {
    return json(200, { loggedIn: true, relay: null, device: { id: 'd1', name: '本机', platform: 'macos', capabilities: {}, userDescription: '', avatarUrl: '', email: 'qa@example.com', displayName: 'QA' }, peers: [] });
  }
  if (p === '/api/friends') return json(403, { error: 'Missing or invalid token' });
  if (p === '/api/neblink/auth/start') {
    SRV.authStartCalls += 1;
    return json(200, { authorizeUrl: 'https://auth.mystand.space/oidc/auth?client_id=pkce-app&redirect_uri=http%3A%2F%2F127.0.0.1%3A6138%2Fauth%2Fcallback&response_type=code' });
  }
  if (p === '/api/conversations') return json(200, { conversations: [] });
  return json(200, {});
});
// WS 最小活会话镜像（cardwin/rcptcode 同形）：configData 到达才渲染活动栏按钮。
await ctx.routeWebSocket(/\/ws/, (ws) => {
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});

await page.goto(BASE + '/index.html');
await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 15000 });
// boot 期好友取数 403 ⇒ 首派 fm-auth-required ⇒ 页面内登录卡自动单飞。
await page.waitForSelector('#nebflow-login-modal', { timeout: 15000 });
await page.waitForSelector('#login-open-auth', { timeout: 15000 }); // deferPopup 恢复键 = 零 popup 的可见证明

const t1 = await page.evaluate(() => ({
  auth: window.__authRequired,
  blockedToast: !!document.querySelector('.nebflow-toast'),
}));
ok('② 失效首派恰好一次（boot 期 401 轮询不叠派）', t1.auth === 1, `auth=${t1.auth}`);
ok('① 自动登录链零 window.open（无 popup 事件）', popupCount === 0, `popups=${popupCount}`);
ok('① 页面内登录卡在挂 + 恢复键在挂（单飞形态）', true);
ok('① 无「弹窗被拦截」toast（deferPopup 不 nag）', t1.blockedToast === false);

// ── ② 闩的硬钉：同回合再炸 N 次 401 ⇒ 派发数不增；fm-auth-resolved 复位 ⇒ 再首派。
const t2 = await page.evaluate(async () => {
  const api = await import('/js/friendsApi.js');
  const burst = async () => { try { await api.getFriends(); } catch (_) { /* 403 预期 */ } };
  await burst(); await burst(); await burst();
  const latched = window.__authRequired;
  window.dispatchEvent(new CustomEvent('fm-auth-resolved'));
  await burst();
  return { latched, afterResolve: window.__authRequired };
});
ok('② 闩内 401 连爆不重演（3 连击派发零增量）', t2.latched === 1, `auth=${t2.latched}`);
ok('② fm-auth-resolved 复位后下一段失效照常首派', t2.afterResolve === 2, `auth=${t2.afterResolve}`);

// ── ③ 联系人失效块单例：关卡 → 开面板 → 展开「新的朋友」⇒ 文案两处 + 动作键一枚 ──
await page.keyboard.press('Escape'); // 关掉登录卡（boot 自动卡已完成①②断言）
await sleep(400);
await page.click('#contacts-btn');
await page.waitForSelector('[data-fm-list-error="auth"]', { timeout: 10000 });
await page.click('.fm-nf-entry');
await page.waitForSelector('[data-fm-req-error="auth"]', { timeout: 10000 });
const t3 = await page.evaluate(() => ({
  textBlocks: document.querySelectorAll('#fm-contacts-body [data-fm-list-error="auth"], #fm-contacts-body [data-fm-req-error="auth"]').length,
  loginBtns: document.querySelectorAll('#fm-contacts-body .fm-login-btn').length,
  retryBtns: [...document.querySelectorAll('#fm-contacts-body button')].filter(b => b.textContent.trim() === '重试').length,
}));
ok('③ 失效文案两区仍可见（好友列表区 + 请求区）', t3.textBlocks === 2, `blocks=${t3.textBlocks}`);
ok('③ 「重新登录」动作键全面板单例（修前同屏两块）', t3.loginBtns === 1, `btns=${t3.loginBtns}`);
ok('③ auth 态不再给「重试」（重拉对已死会话无意义）', t3.retryBtns === 0, `retry=${t3.retryBtns}`);

// ── ④ 点开链 wiring：面板「重新登录」（真手势）→ 卡内主键 → popup = authorizeUrl ──
await page.click('#fm-contacts-body .fm-login-btn');
await page.waitForSelector('#login-unified', { timeout: 10000 });
const popupP = page.waitForEvent('popup', { timeout: 8000 }).catch(() => null);
await page.click('#login-unified');
const popup = await popupP;
ok('④ 真手势点开托管登录页（单飞一次）', !!popup && popupCount === 1, `popups=${popupCount}`);
if (popup) {
  // 预约窗先落 about:blank 再被导航 —— 等导航落地再读 host。
  await popup.waitForURL((u) => new URL(u).host === 'auth.mystand.space', { timeout: 8000 }).catch(() => {});
  ok('④ 登录页 = 网关下发 authorizeUrl（现役域 auth.mystand.space）',
    new URL(popup.url()).host === 'auth.mystand.space', popup.url().slice(0, 72));
}

ok('零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 2)));
console.log(`\n==== login-expiry-singleflight: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ====`);
await ctx.close();
await browser.close();
await new Promise(r => server.close(r));
process.exit(failures === 0 ? 0 : 1);
