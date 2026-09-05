// friends-username-unify.spec.mjs — 好友 UI 批：NL 号=Username 统一 + 搜索双识别
// + 契约 mock 验收（作者 2026-09-05 10:54 裁定，微信一一对应）。
//
// 覆盖：
//   U1  zh 搜索框 placeholder =「用户名 / 邮箱」（双渠道语义）
//   U2  按 Username 搜索（不含 @）→ 结果卡渲染 displayName + username
//   U3  发起好友请求（验证消息 Enter 直发）→ 等待对方处理；wire query=username
//   U4  按邮箱搜索（含 @）→ email 精确匹配命中
//   U5  旧形态 seed（neblinkId/name）兼容——username/displayName 回退读
//   U6  self 命中：任一自身标识（username / email）→「这是你自己」
//   U7  未找到态：主文案 + 提示（含「用户名」「尚未设置」字样）
//   U8  真实链路网络失败态：search 请求 abort → 搜索失败卡 + 错误 toast
//       （失败分态 ≠「未找到」，09-06 作者令三态；曾与空结果混态已拆分）
//   U9  en 文案抽查：placeholder / notFoundHint = Username 语义
//   U10 i18n parity + contacts.*/messages.* 键值 NL 号/nebflow 号 字样零残留
//
// 全 mock（fm_api_mock seed），静态服务器内置（随机隔离端口，finally 关闭）。
// Run: node tests/friends-username-unify.spec.mjs
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

// ── 内置静态服务器（隔离端口，进程清理纪律：finally 必关）─────────
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

// 新契约形态 seed（username/email/displayName）+ 一条旧形态用户（兼容回退）
const SEED = {
  self: { userId: 'me', username: 'selfme', email: 'self@example.com', displayName: '我自己', avatar: '' },
  users: [
    { userId: 'u-x', username: 'xiaoming', email: 'xm@example.com', displayName: '小明', avatar: '' },
    { userId: 'u-m', username: 'mary', email: 'mary@example.com', displayName: 'Mary', avatar: '' },
    { userId: 'u-o', neblinkId: 'oldbie77', name: '旧形态用户', avatarUrl: '' },
  ],
  friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
};

const browser = await chromium.launch();

async function bootPage({ locale = 'zh-CN', blockLookup = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(([seedJson, loc]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', loc); localStorage.setItem('neblink_locale', loc);
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '60');
  }, [JSON.stringify(SEED), locale]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));           // catch-all first
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
  if (blockLookup) await page.route('**/api/users/search*', r => r.abort());  // 端点切换：search（契约唯一搜索入口）
  let serverWs = null;
  await page.routeWebSocket(/\/ws/, ws => {
    serverWs = ws;
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
  return { ctx, page, send: obj => serverWs.send(JSON.stringify(obj)) };
}

async function search(page, q) {
  // 搜索为 submit 式 + 1s 最小提交间隔：填词 → 点按钮 → 等结果卡
  await page.fill('.fm-search-input', q);
  await page.click('.fm-search-btn');
  await sleep(500);
}

try {
  // ══ Page 1: zh 主场景 ═══════════════════════════════════════
  const { page, send } = await bootPage();
  await page.click('#contacts-btn');
  await sleep(700);

  const ph = await page.$eval('.fm-search-input', e => e.placeholder).catch(() => '');
  ok('U1 zh placeholder=用户名 / 邮箱（双渠道）', ph === '用户名 / 邮箱', ph);

  // U2 Username 路径（不含 @）
  await search(page, 'xiaoming');
  let card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U2a Username 搜索命中：displayName 主行', card.includes('小明'), card.slice(0, 60));
  ok('U2b username 副行', card.includes('xiaoming'), card.slice(0, 60));

  // U3 加好友 → 验证消息 → 等待对方处理（契约 §4.1 outgoing_pending 文案，
  // friend-contract-align 批次统一切换）；wire query = username
  await page.click('.fm-add-btn');
  await sleep(300);
  await page.fill('.fm-verify-input', '求通过');
  await page.keyboard.press('Enter');
  await sleep(600);
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U3a 验证消息 Enter 直发 → 等待对方处理', card.includes('等待对方处理'), card.slice(0, 60));
  const wire = await page.evaluate(async () => {
    const api = await import('/js/friendsApi.js');
    const d = await api.getFriends();
    return (d.outgoing || [])[0]?.to || null;
  });
  ok('U3b wire query=username + outgoing 归一 wire 形态', !!wire && wire.neblinkId === 'xiaoming' && wire.name === '小明', JSON.stringify(wire));

  // U4 邮箱路径（含 @ → email 精确匹配）
  await sleep(700); // 1s 最小提交间隔余量
  await search(page, 'mary@example.com');
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U4 邮箱搜索命中（含 @ 判邮箱）', card.includes('Mary') && card.includes('mary'), card.slice(0, 60));

  // U5 旧形态 seed：neblinkId 回退读 username
  await sleep(700);
  await search(page, 'oldbie77');
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U5 旧形态用户（neblinkId seed）仍可搜到', card.includes('旧形态用户') && card.includes('oldbie77'), card.slice(0, 60));

  // U6 self 命中：username / email 双标识
  await sleep(700);
  await search(page, 'selfme');
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U6a self username 命中 → 这是你自己', card.includes('这是你自己'), card.slice(0, 60));
  await sleep(700);
  await search(page, 'self@example.com');
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U6b self email 命中 → 这是你自己', card.includes('这是你自己'), card.slice(0, 60));

  // U7 未找到态（文案含「用户名」「尚未设置」）
  await sleep(700);
  await search(page, 'ghost404');
  card = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
  ok('U7 未找到：主文案+用户名常识提示', card.includes('未找到该用户') && card.includes('尚未设置') && card.includes('用户名'), card.slice(0, 80));

  // U10 parity + 好友域键值零残留（页面内实时断言）
  const i18n = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const bad = [];
    for (const [loc, table] of [['zh', zh], ['en', en]]) {
      for (const [k, v] of Object.entries(table)) {
        if ((k.startsWith('contacts.') || k.startsWith('messages.')) && /NL 号|NL号|NebLink ID|nebflow 号|nebflow ID/i.test(v)) bad.push(`${loc}:${k}=${v}`);
      }
    }
    return { zhN: Object.keys(zh).length, enN: Object.keys(en).length, bad };
  });
  ok('U10a i18n parity zh=en', i18n.zhN === i18n.enN, `zh=${i18n.zhN} en=${i18n.enN}`);
  ok('U10b contacts.*/messages.* 键值 NL 号字样零残留', i18n.bad.length === 0, JSON.stringify(i18n.bad));
  await page.close();

  // ══ Page 2: 真实链路网络失败态（mock 关闭 + lookup abort）══════
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      await ctx.addInitScript(() => {
        localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
        localStorage.setItem('nebflow_locale', 'zh-CN');
        // 不设 fm_api_mock → 真实链路；lookup 被 abort → 网络失败态
      });
      const p = await ctx.newPage();
      p.on('pageerror', e => console.log('[pageerror]', e.message));
      await p.route('**/api/**', r => r.fulfill({ json: {} }));
      await p.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
      await p.route('**/api/users/search*', r => r.abort());  // 端点切换：search
      await p.routeWebSocket(/\/ws/, ws => {
        ws.onMessage(() => {});
        ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
      });
      await p.goto(BASE + '/index.html');
      await p.waitForSelector('#messages-btn', { state: 'attached', timeout: 10000 });
      await sleep(900);
      await p.click('#contacts-btn');
      await sleep(700);
      await p.fill('.fm-search-input', 'anyone');
      await p.click('.fm-search-btn');
      await sleep(800);
      const failCard = await p.$eval('.fm-result-card', e => e.textContent).catch(() => '');
      ok('U8 网络失败 → 搜索失败卡（失败分态，非「未找到」）', failCard.includes('搜索失败'), failCard.slice(0, 60));
      await ctx.close();
  }

  // ══ Page 3: en 抽查 ════════════════════════════════════════
  {
    const { ctx, page: p3 } = await bootPage({ locale: 'en' });
    await p3.click('#contacts-btn');
    await sleep(700);
    const enPh = await p3.$eval('.fm-search-input', e => e.placeholder).catch(() => '');
    ok('U9a en placeholder=Username / Email', enPh === 'Username / Email', enPh);
    await search(p3, 'ghost404');
    const enCard = await p3.$eval('.fm-result-card', e => e.textContent).catch(() => '');
    ok('U9b en 未找到提示 = Username 语义', enCard.includes('User not found') && enCard.includes('Username'), enCard.slice(0, 90));
    await ctx.close();
  }
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
