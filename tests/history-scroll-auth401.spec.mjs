// history-scroll-auth401.spec.mjs — 上滑看历史 × 铸票 401：会话绝不登出回归钉
// （控制台三症状批 · ① 登出级 bug，作者令 2026-10-22）。
//
// 修前链路（诊断结论，本 spec 逐环复现）：
//   聊天向上滚动（main.js _primScrollGate，scrollTop<100）
//     → WS 帧 getHistory（**不经 link.mystand.space**，gateway 本地会话页；
//        WS 握手一次性带 cookie，帧本身不带也不需要 ticket/cookie）
//     → historyPage 补页渲染（chat/historyReplay.js → persistence.js
//       restoreFromBackendHistory）
//     → 页内 Card 工具行（___CARD_HTML___ 载荷含 /api/nf-file?path=…）
//       经 cardRegistry 渲染 ⇒ nfTicket.mintTickets ⇒ **POST /api/nf-ticket**
//       （credentials:'same-origin' 先走 cookie；401/403 才补 Bearer 重试——
//       凭据链本来就对，本批未改）
//     → relay 源 15min 会话 cookie 过期 / 本地源 host 会话失效 ⇒ 401/403
//     → 修前 nfTicket.mintRound 一发 401 就 noteAuthRejected('nf-ticket')
//       ⇒ sessionGate 横幅「登录状态已过期」+ 1.5s location.reload() ⇒ 被迫重登。
//
// 修复钉（本 spec 断言面）：
//   H1 历史正常补齐   — 上滑逐轮补进更旧的一页（3→6→9），历史到头收口不转圈；
//   H2 铸票确实被拒   — /api/nf-ticket 全程答 401，且铸票请求 ≥3 次（401 路
//                       被真实踩到，不是没触发；cookie+Bearer 双发 = 每轮 2 hit）；
//   H3 静默重试带退避 — 退避窗（0.5s 指数）过后下一轮铸票照发（≥3 hit 即证）；
//   H4 会话不登出     — 全程零 `#stand-session-gate` 横幅、零 reload（boot 后
//                       打的 window 标记跨全程存活）。
//
// 运行：node tests/history-scroll-auth401.spec.mjs
// 可选 env：PORT（静态口，默认 8161）、SHOTS_DIR（现场截图目录）、
//   NB_UX_ROOT（被测树根，红腿复现：git archive HEAD 出修前树后指到这里）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
/** 被测树根（repo root；NB_UX_ROOT 红腿腿 = git archive HEAD 出的修前树根）。 */
const SERVE_ROOT = process.env.NB_UX_ROOT || resolve(HERE, '..');
const WEB_DIR = resolve(SERVE_ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8161);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || '/tmp/stand-1022/auth401';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 分页脚本：3 页更旧历史，每页一枚 Card 工具行（渲染即铸票）─────────────
const cardRow = (n) => ({
  type: 'tool',
  label: 'Card',
  summary: '',
  isError: false,
  content: '___CARD_HTML___' + JSON.stringify({
    title: `历史卡片 P${n}`,
    html: `<h3>第 ${n} 页的卡片</h3><img src="/api/nf-file?path=/pics/p${n}.png" alt="p${n}">`,
  }),
  timestamp: 1750000000000 + n * 1000,
});
const userRow = (t) => ({ type: 'user', text: t, timestamp: 1750000000000 + t.length });
/** 分页脚本：初载（无 beforeIndex）给 offset=100/hasMore=true 的最旧在视页；
 *  每轮上滑带 beforeIndex=100/50 → 更旧一页（offset −50），到 0 收口。 */
const pageFor = (m) => {
  const before = m.beforeIndex === undefined ? null : Number(m.beforeIndex);
  const n = before === null ? 2 : Math.round(before / 50);   // 页号：初载=2，上滑 100→1、50→0
  const offset = before === null ? 100 : Math.max(0, before - 50);
  return {
    type: 'historyPage',
    sessionId: 'sess-1',
    messages: [userRow(`第 ${n} 页的历史消息，装一点正文让行有高度。`.repeat(3)), cardRow(n)],
    offset,
    total: 200,
    hasMore: offset > 0,
  };
};

let ticketHits = 0;

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
const errors = [];
let navigated = 0;
page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
page.on('framenavigated', (f) => { if (f === page.mainFrame()) navigated += 1; });

// 顺序即优先级：后注册者先命中 —— nf-authcheck 204、nf-ticket 401、nf-file 图、其余 /api/** 空 JSON。
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route('**/api/nf-file?*', (r) => r.fulfill({
  status: 200, contentType: 'image/png',
  body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64'),
}));
await page.route('**/api/nf-ticket', (r) => { ticketHits += 1; r.fulfill({ status: 401, json: { error: 'unauthorized' } }); });
await page.route('**/api/nf-authcheck', (r) => r.fulfill({ status: 204, headers: { 'x-nebflow-credential': 'session' } }));
await page.routeWebSocket(/\/ws/, (ws) => {
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify(pageFor(m)));
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});

await page.goto(BASE + '/index.html');
// 红腿感知启动：修前树在 boot 阶段就会被早发的 mint 401 拉起 sessionGate
// （横幅 + 1.5s reload 循环）——等 #activity-bar 或横幅，横幅先到即红腿定罪。
try {
  await page.waitForSelector('#activity-bar, #stand-session-gate', { timeout: 20000 });
} catch (e) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, 'red-leg-boot-timeout.png') }).catch(() => {});
  console.log('FAIL  RED-LEG: boot 20s 内既无 #activity-bar 也无横幅（现场截图 red-leg-boot-timeout.png）—', String(e.message || e).slice(0, 120));
  console.log('\n═══ history-scroll-auth401: 0 PASS / 1 FAIL（红腿异常）═══');
  await browser.close(); server.close(); process.exit(1);
}
const bootBanner = await page.evaluate(() => !!document.getElementById('stand-session-gate'));
if (bootBanner) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, 'red-leg-boot-banner.png') });
  console.log('FAIL  RED-LEG（修前树）: boot 阶段 mint 401 → sessionGate 横幅在场（#stand-session-gate），页面进入自愈 reload 循环 —— 登出级 bug 在场');
  console.log('\n═══ history-scroll-auth401: 0 PASS / 1 FAIL（红腿：缺陷在场）═══');
  await browser.close();
  server.close();
  process.exit(1);
}
// 初载 historyPage（offset=100, hasMore）落位：行数 > 0 即初载完成。
await page.waitForFunction(() => document.querySelectorAll('#chat .row').length > 0, { timeout: 15000 });
await sleep(900); // 卡片 iframe 铸票窗
await page.evaluate(() => { window.__noReloadMarker = { at: Date.now() }; });
const navAtBoot = navigated;            // 初载 goto 自身的 framenavigated，不算 reload

const results = [];
const ok = (name, cond, detail = '') => results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);

const rowCount = () => page.evaluate(() => document.querySelectorAll('#chat .row').length);
const bannerUp = () => page.evaluate(() => !!document.getElementById('stand-session-gate'));

// ── H1/H3/H4：上滑 ×3，轮间等过退避窗（0.5s→1s→2s 指数）──────────────────
const counts = [await rowCount()];
for (let i = 0; i < 3; i++) {
  await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll')); });
  await sleep(1600); // 覆盖铸票退避窗（0.5s/1s/2s 前两档）+ 渲染
  counts.push(await rowCount());
}
const mintsAtEnd = ticketHits;

ok('H1-a 初载有行', counts[0] > 0, `rows=${counts[0]}`);
// 上滑×3：perf 台账 #922（2026-10-26）起停顶续拉语义 —— 停在顶部即连续补页
// 直至历史收口（首轮 3→9 = 两页并进），到头后行数不增、会话照常。
// 收口本身也是钉（旧缺陷家族里「到头仍打转」出过回归）。
ok('H1-b 上滑补页（停在顶部连续补进，直至历史收口不再增）',
  counts[3] === 9 && counts[1] >= counts[0] && counts[3] === counts[2],
  `rows=${counts.join('→')}`);
ok('H2 铸票被拒路径真实踩到（nf-ticket 401 ×≥3）', mintsAtEnd >= 3, `hits=${mintsAtEnd}`);
ok('H3 401 后静默重试仍在发生（≥2 次即证退避后重试）', mintsAtEnd >= 2, `hits=${mintsAtEnd}`);
const bannerEver = await bannerUp();
ok('H4-a 零 sessionGate 横幅', !bannerEver, bannerEver ? 'banner in DOM' : 'none');
ok('H4-b 零 reload（boot 后零导航 + 页面标记跨全程存活）',
  await page.evaluate(() => !!window.__noReloadMarker) && navigated === navAtBoot,
  `marker=${await page.evaluate(() => String(!!window.__noReloadMarker))} navigated=${navigated - navAtBoot}次(boot后)`);
ok('R0 主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));

mkdirSync(SHOTS_DIR, { recursive: true });
await page.screenshot({ path: join(SHOTS_DIR, 'final-state.png'), fullPage: false });

console.log(results.join('\n'));
const fail = results.filter((r) => r.startsWith('FAIL')).length;
console.log(`\n═══ history-scroll-auth401: ${results.length - fail} PASS / ${fail} FAIL ═══`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);
