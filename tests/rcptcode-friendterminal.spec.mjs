// rcptcode-friendterminal.spec.mjs — 好友腿**终态码分态**（rcptcode 批 2026-09-20）的
//   **自包含真渲染**验收：真 Chromium + 真模块图 + 真 CSS + 真 DOM 事件。
//
// 被测面（客户端半边）：
//   · `friendsApi.js`：`req()` 的登录链豁免（好友域终态码 ⇒ 不派 `fm-auth-required`）、
//     `errKind()` 的 `'terminal'` 分态；
//   · `messages.js`：好友发送腿 catch 分支 —— 白名单码 ⇒ **终态**（撤气泡 + 分态原因 +
//     **不给重试键**）；未知码 ⇒ 回退可重试 + 码**原样**展示（`data-send-error-code`）；
//     被踢会话 403（无语义码）⇒ 登录引导（既有 auth 链，行为变化 ② 的客户端读数）。
//
// 运行（双向红绿钉 —— 同一 harness，两份被测树 × 两种 wire 形态）：
//   RC_MODE=after  node tests/rcptcode-friendterminal.spec.mjs                    # 改后
//   RC_MODE=before RC_WEB_ROOT=<纯 main 的 web 树> node tests/rcptcode-friendterminal.spec.mjs
//     ⇒ 改前基线：wire = **折叠形态**（502 + `{"error":"HTTP 403: {...}"}` 文本），
//       客户端 = **旧树**。红组断言 = 症状必须复现（恒出重试键、无原因、无登录引导）。
// 环境变量：
//   RC_WEB_ROOT  被测 web 树（缺省 = 本仓 src/main/resources/web）
//   RC_MODE      `after`（缺省）｜`before`（改前基线）
//   RC_OUT       全部读数落盘为 JSON（证据件）
//   RC_SHOTS     截图落盘目录（缺省不落图）
//
// 🔴 诚实申报（与 `msgmenu.spec.mjs` / `imgmsg-send.spec.mjs` 同款纪律）：本 spec 的
//   「网关」= 页内 `page.route` 的**契约镜像**（按 rcptcode 批的**真 wire 形态**写死：
//   逐字状态码 + `{"error":"<code>"}`，改前分支 = 折叠形态）。**不是**生产网关/上游。
//   本 spec 覆盖**前端消费链**；「网关逐码透传」由 `FriendApiRoutesSpec`（Scala）与
//   隔离实例黑盒探针各自的读数承接（本 spec 不冒充那两条腿的证据）。
// 🔴 零实例依赖：不起 gateway 实例（无 sbt、零端口占用、不触碰宿主 :8080）；静态资源由
//   本 spec 内置 http server（随机端口）供给，收尾必关（进程清理纪律）。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.RC_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.env.RC_MODE === 'before' ? 'before' : 'after';
const AFTER = MODE === 'after';
const RC_OUT = process.env.RC_OUT || '';
const RC_SHOTS = process.env.RC_SHOTS || '';

let failures = 0;
const R = { mode: MODE, web: WEB, readings: {} };
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const put = (k, v) => { R.readings[k] = v; console.log(`READ  ${k} = ${JSON.stringify(v)}`); };
let shotSeq = 0;
async function shot(page, name) {
  if (!RC_SHOTS) return;
  await mkdir(RC_SHOTS, { recursive: true });
  shotSeq += 1;
  await page.screenshot({ path: join(RC_SHOTS, `${String(shotSeq).padStart(2, '0')}-${name}.png`) });
}

// ── REST/WS 契约镜像 ────────────────────────────────────────────────────
const EPOCH = Math.floor(Date.now() / 1000);
const iso = s => new Date(s * 1000).toISOString();
const SRV = { calls: [], arm: null };
const CONVS = [{
  conversationId: 'cA',
  friend: { userId: 'u-p', neblinkId: 'peer@example.com', name: '对端', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) },
  lastMessage: { id: 101, senderId: 'u-p', kind: 'text', body: '早上好', createdAt: iso(EPOCH - 300) },
  unreadCount: 0,
}];
const MSGS = { cA: [{ id: 101, senderId: 'u-p', kind: 'text', body: '早上好', createdAt: iso(EPOCH - 300) }] };

/** 被测面：好友发送腿的**上游形态**（一位一码；`terminal` = 期望落终态）。 */
const ARMS = [
  { id: 'A1', label: '403 not_friends', status: 403, body: { error: 'not_friends' },
    terminal: true, reasonText: '你们已不是好友，消息未发送' },
  { id: 'A2', label: '403 not_blocker', status: 403, body: { error: 'not_blocker' },
    terminal: true, reasonText: '你并未拉黑对方，该操作不可用' },
  { id: 'A3', label: '400 REPLY_TARGET_INVALID', status: 400, body: { error: 'REPLY_TARGET_INVALID' },
    terminal: true, reasonText: '被引用的消息已不可用，消息未发送' },
  { id: 'A4', label: '500 未知码', status: 500, body: { error: 'boom_code_unknown' },
    terminal: false, code: 'boom_code_unknown' },
  { id: 'A5', label: '403 被踢会话（无语义码）', status: 403, body: { error: 'Missing or invalid token' },
    terminal: false, kicked: true },
];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

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

async function bootPage() {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // 登录引导链读数面：`req()` 的 403 豁免判据只看这一处是否被派发。
    window.__authRequired = 0;
    window.addEventListener('fm-auth-required', () => { window.__authRequired += 1; });
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    SRV.calls.push({ p, method: req.method() });
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' }, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' }, peers: [] }) });
    }
    if (p === '/api/conversations') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
    }
    if (p === '/api/friends') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: CONVS.map(c => c.friend), incoming: [], outgoing: [] }) });
    }
    if (/^\/api\/conversations\/[^/]+\/messages$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MSGS.cA) });
    }
    if (/^\/api\/conversations\/[^/]+\/read$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  // 好友发送腿：后注册 ⇒ 优先（Playwright 反向匹配）。
  await page.route('**/api/friends/*/messages', async (route) => {
    const arm = SRV.arm;
    await sleep(120);
    if (!arm) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messageId: 900, conversationId: 'cA', createdAt: iso(EPOCH) }) });
    }
    if (!AFTER) {
      // 改前 wire = **折叠形态**：网关把任何上游非 2xx 答成 502 + 文本（状态码降级）。
      const folded = `HTTP ${arm.status}: ${JSON.stringify(arm.body)}`;
      return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: folded }) });
    }
    return route.fulfill({ status: arm.status, contentType: 'application/json', body: JSON.stringify(arm.body) });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors };
}

const openPanel = async (page) => { await page.click('#messages-btn'); await sleep(600); };
const openConv = async (page) => { await page.click('#fm-conversations .fm-conv-row[data-conversation-id="cA"]'); await sleep(700); };

/** 发送一条并读全量面（气泡 / 重试键 / toast / 码 / 登录链 / 输入框）。 */
async function sendAndRead(page, text) {
  await page.fill('.fm-input', text);
  await page.click('.fm-send-btn');
  await sleep(1100);
  return page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const msgs = flow ? [...flow.querySelectorAll('.fm-msg')] : [];
    const toast = document.querySelector('.fm-modal-toast');
    return {
      msgCount: msgs.length,
      failedCount: msgs.filter(m => m.classList.contains('fm-failed')).length,
      retryCount: flow ? flow.querySelectorAll('.fm-retry').length : 0,
      toastVisible: !!toast && !toast.hidden,
      toastText: toast ? toast.textContent : '',
      errCodes: msgs.map(m => m.dataset.sendErrorCode).filter(Boolean),
      authRequired: window.__authRequired,
      inputValue: document.querySelector('.fm-input')?.value ?? null,
    };
  });
}

/** 单臂：新页 → 开面板/会话 → 发送 → 读数 + 双向断言。 */
async function runArm(arm) {
  SRV.arm = arm;
  const { ctx, page, pageErrors } = await bootPage();
  try {
    await openPanel(page);
    await openConv(page);
    const before = await page.evaluate(() => document.querySelectorAll('.fm-flow .fm-msg').length);
    const st = await sendAndRead(page, `rcptcode ${arm.id}`);
    await shot(page, `${MODE}-${arm.id}`);
    const reading = { arm: arm.id, label: arm.label, before, ...st };
    put(`arm.${arm.id}.${MODE}`, reading);

    if (arm.terminal) {
      // 终态面：**无重试键**、气泡撤走、给原因、正文不丢。
      ok(`${arm.id} 终态：无重试键`, st.retryCount === 0, `retry=${st.retryCount}`);
      ok(`${arm.id} 终态：气泡已撤`, st.msgCount === before, `msg=${st.msgCount}/${before}`);
      ok(`${arm.id} 终态：分态原因可见`, st.toastVisible && st.toastText === arm.reasonText,
        `toast=${JSON.stringify(st.toastText)}`);
      ok(`${arm.id} 终态：正文退回输入框（不丢）`, st.inputValue === `rcptcode ${arm.id}`, JSON.stringify(st.inputValue));
      ok(`${arm.id} 终态：不派登录链`, st.authRequired === 0, `auth=${st.authRequired}`);
    } else if (arm.code) {
      // 未知码回退面：可重试 + 码**原样**展示（fail-visible）。
      ok(`${arm.id} 未知码：保留重试键`, st.retryCount === 1, `retry=${st.retryCount}`);
      ok(`${arm.id} 未知码：码原样展示`, st.errCodes.includes(arm.code), JSON.stringify(st.errCodes));
      ok(`${arm.id} 未知码：不派登录链`, st.authRequired === 0, `auth=${st.authRequired}`);
    } else if (arm.kicked) {
      // 被踢会话（无语义码的 403）⇒ 既有 auth 链（登录引导），行为变化 ② 的客户端面。
      ok(`${arm.id} 被踢 403：派登录链`, st.authRequired === 1, `auth=${st.authRequired}`);
    }
    ok(`${arm.id} 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    return reading;
  } finally {
    await ctx.close();
  }
}

/** 改前基线（红组）：折叠 502 × 旧树 —— 症状必须复现。 */
async function runBaselineSymptoms() {
  SRV.arm = ARMS[0];
  const { ctx, page, pageErrors } = await bootPage();
  try {
    await openPanel(page);
    await openConv(page);
    const st = await sendAndRead(page, 'rcptcode baseline');
    put('baseline.folded403', st);
    ok('改前：403 not_friends 被折 502 ⇒ 恒出重试键（症状①）', st.retryCount === 1, `retry=${st.retryCount}`);
    ok('改前：无分态原因（症状②）', !(st.toastVisible && st.toastText === ARMS[0].reasonText), JSON.stringify(st.toastText));
    ok('改前：被折 502 ⇒ 不派登录链', st.authRequired === 0, `auth=${st.authRequired}`);
    ok('改前：零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
  // 被踢会话的改前形态：上游 403 也被折成 502 ⇒ **没有**登录引导（行为变化 ②）。
  SRV.arm = ARMS[4];
  const kicked = await bootPage();
  try {
    await openPanel(kicked.page);
    await openConv(kicked.page);
    const st = await sendAndRead(kicked.page, 'rcptcode baseline kicked');
    put('baseline.kicked403', st);
    ok('改前：被踢 403 被折 502 ⇒ 无登录引导（行为变化 ② 的红组）', st.authRequired === 0, `auth=${st.authRequired}`);
  } finally {
    await kicked.ctx.close();
  }
}

for (const arm of ARMS) await runArm(arm);
if (!AFTER) await runBaselineSymptoms();

console.log(`\n==== rcptcode-friendterminal ${MODE} : ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ====`);
if (RC_OUT) {
  R.failures = failures;
  writeFileSync(RC_OUT, JSON.stringify(R, null, 2));
  console.log(`READ  written ${RC_OUT}`);
}
await browser.close();
await new Promise(r => server.close(r));
process.exit(failures === 0 ? 0 : 1);
