#!/usr/bin/env node
// e2e-fm-scroll-follow.cjs — 好友/群会话窗「新消息自动跟随 + ↓N 回到底部胶囊」
// GUI 黑盒验收（follow 批 2026-10-08，主人反馈）。
//
// 病灶（改前）：`appendMessage`（WS 到达腿）**无条件** `flow.scrollTop = scrollHeight`
// —— 用户上翻读历史时一帧到达就把视口整个拽到底部；且本窗没有「回到底部」入口
// （主窗 agent 面自 A-branch 2026-09-11 / B4 2026-10-04 起已有跟随闩 + ↓N 胶囊，
// 本窗两样都缺）。改后：贴底（msgScrollAnchor.isAtBottom，AT_BOTTOM_PX=40）才跟随；
// 已离开 ⇒ 保持原位 + 胶囊报数（`.scroll-new-pill` 复用 agent 面既有件），点击回底。
//
// 本 harness 自包含打桩（零端口、不碰 8080，与 e2e-friendmsg-dup.cjs 同路线）：
// `page.route` 从磁盘服务**真实 web 树** + `page.routeWebSocket` 收发真实帧形状，
// 驱动**真实** UI 路径（点会话行开窗 → 滚轮上翻 → WS 帧到达 → 点胶囊）。
//
// 测试点（每点 DOM 结构读数 + 已检视截图双通道）：
//   T1 贴底到达跟随    —— 开窗钉底后 WS 帧到达 ⇒ 视口仍在底部，胶囊不出现
//   T2 上翻不强拉      —— 滚轮上翻后帧到达 ⇒ scrollTop 分毫不动，胶囊出现「↓ 1 条新消息」
//   T3 计数累加        —— 再到两条 ⇒ 「↓ 3 条新消息」
//   T4 胶囊点击回底    —— 点胶囊 ⇒ 回到贴底区，胶囊隐藏
//   T5 手动回底清零    —— 上翻→到达→滚回底部 ⇒ 胶囊自隐（计数被滚动判定清零）
//   T6 本机发送恒回底  —— 上翻后从输入框发送 ⇒ 乐观气泡钉底（主窗 own-message 裁定同向）
//
// Run（仓库根）：node scripts/e2e-fm-scroll-follow.cjs
// 截图证据：.nebflow/evidence/20261008_fm-follow/e2e-shots/
const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, resolve } = require('node:path');

const WEB = process.env.N6_WEB
  ? resolve(process.env.N6_WEB)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const SHOTS = join(__dirname, '..', '.nebflow', 'evidence', '20261008_fm-follow', 'e2e-shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const FRIEND = { userId: 'u-1', neblinkId: 'alice', name: 'Alice', avatarUrl: '' };
const CONV_ID = 'c-1';
// 40 条历史 ⇒ flow 内容远超视口（可上翻）；id 1..40 升序。
const HISTORY = Array.from({ length: 40 }, (_, i) => ({
  id: i + 1, senderId: i % 2 ? 'me' : 'u-1', kind: 'text',
  body: `历史消息 ${i + 1}`, createdAt: Math.floor(Date.now() / 1000) - (40 - i) * 60,
}));

(async () => {
  mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const waitFor = async (fn, ms = 4000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { if (fn()) return true; await sleep(25); }
    return false;
  };
  const pageErrors = [];
  const consoleErrors = [];

  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    colorScheme: 'dark',
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });

  /** 服务端侧真源（桩）。 */
  const st = { msgs: HISTORY.slice(), seq: 40, ws: null };
  const inFrame = (id, body) => JSON.stringify({
    type: 'friend_event', event: 'message_new', messageId: id,
    conversationId: CONV_ID, senderId: FRIEND.userId, kind: 'text', body,
    createdAt: Math.floor(Date.now() / 1000),
  });
  const deliver = (body) => st.ws && st.ws.send(body);

  await page.route('**/*', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const path = u.pathname;
    if (path.startsWith('/api/')) {
      if (path === '/api/neblink/status') {
        return route.fulfill({ json: {
          loggedIn: true,
          device: { id: 'd1', name: '本机', platform: 'macos', email: 'me@example.com', userDescription: '', avatarUrl: '' },
          peers: [], relay: { available: true },
        } });
      }
      if (path === '/api/friends') return route.fulfill({ json: { friends: [FRIEND], incoming: [], outgoing: [] } });
      if (path === '/api/conversations') {
        const last = st.msgs[st.msgs.length - 1];
        return route.fulfill({ json: [{ conversationId: CONV_ID, friend: FRIEND, lastMessage: last, unreadCount: 0 }] });
      }
      if (path === `/api/conversations/${CONV_ID}/messages`) {
        const after = Number(u.searchParams.get('after') || 0);
        const limit = Math.max(1, Math.min(200, Number(u.searchParams.get('limit') || 50)));
        return route.fulfill({ json: st.msgs.filter((m) => Number(m.id) > after).slice(0, limit) });
      }
      if (path === `/api/conversations/${CONV_ID}/read`) return route.fulfill({ json: {} });
      if (path === `/api/friends/${FRIEND.userId}/messages`) {
        const body = JSON.parse(req.postData() || '{}').body || '';
        const m = { id: ++st.seq, senderId: 'me', kind: 'text', body, createdAt: Math.floor(Date.now() / 1000) };
        st.msgs.push(m);
        return route.fulfill({ json: { messageId: m.id, conversationId: CONV_ID, createdAt: m.createdAt } });
      }
      return route.fulfill({ json: {} });
    }
    if (path === '/' || path === '/index.html') {
      return route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
    }
    try {
      const file = join(WEB, decodeURIComponent(path));
      return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
    } catch {
      // 已知噪声：favicon 在 web 树中不存在，404 会让「无 console error」读数失真。
      if (path === '/favicon.ico') return route.fulfill({ status: 204, body: '' });
      // agent.json = 后端既有面（生产由后端出）；打桩给最小合法体，避免 404 噪声。
      if (/^\/agents\/[^/]+\/agent\.json$/.test(path)) {
        return route.fulfill({ json: { name: 'Nebula', description: '', prompts: [] } });
      }
      console.error(`[route-404] ${path}`);
      return route.fulfill({ status: 404, body: '' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    st.ws = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });

  await page.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 20000 });
  await sleep(300);
  await page.click('#messages-btn');
  await page.waitForSelector(`.fm-conv-row[data-conversation-id="${CONV_ID}"]`, { timeout: 20000 });
  await page.click(`.fm-conv-row[data-conversation-id="${CONV_ID}"]`);
  await page.waitForSelector('.fm-flow', { timeout: 20000 });
  await sleep(400);

  /** 结构读数（只读）：贴底距离 / scrollTop / 胶囊态。 */
  const read = () => page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const pill = document.querySelector('.fm-modal > .scroll-new-pill');
    const r = pill ? pill.getBoundingClientRect() : null;
    return {
      dist: flow.scrollHeight - flow.scrollTop - flow.clientHeight,
      scrollTop: flow.scrollTop,
      scrollHeight: flow.scrollHeight,
      clientHeight: flow.clientHeight,
      pillHidden: !pill || pill.classList.contains('hidden'),
      pillText: pill ? pill.textContent : '',
      pillVisibleBox: !!r && r.width > 0 && r.height > 0,
      msgCount: flow.querySelectorAll('.fm-msg').length,
    };
  });
  const shot = (name) => page.screenshot({ path: join(SHOTS, name) });
  const flowCenter = async () => {
    const box = await (await page.$('.fm-flow')).boundingBox();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  };

  // ── T1 贴底到达跟随 ──────────────────────────────────────────────────────
  let r = await read();
  ok('T1.pre 开窗钉底（40 条历史，贴底距离 ≤40）', r.dist <= 40, `dist=${r.dist} msgs=${r.msgCount}`);
  deliver(inFrame(101, '贴底到达的实时消息'));
  await waitFor(async () => (await read()).msgCount === r.msgCount + 1);
  await sleep(150); // 等跟随写入 + 滚动事件同拍
  r = await read();
  ok('T1 贴底到达 ⇒ 自动跟随（仍在底部）', r.dist <= 40, `dist=${r.dist}`);
  ok('T1 贴底到达 ⇒ 胶囊不出现', r.pillHidden === true, `pillHidden=${r.pillHidden}`);
  await shot('t1_follow-at-bottom.png');

  // ── T2 上翻不强拉 + 胶囊出现 ─────────────────────────────────────────────
  const c = await flowCenter();
  await page.mouse.move(c.x, c.y);
  await page.mouse.wheel(0, -400); // 一次真实滚轮上翻
  await sleep(200);
  r = await read();
  const t2 = { scrollTop: r.scrollTop, dist: r.dist };
  ok('T2.pre 已离开贴底区', r.dist > 40, `dist=${r.dist}`);
  deliver(inFrame(102, '上翻时到达的消息一'));
  await waitFor(async () => (await read()).msgCount === r.msgCount + 1);
  await sleep(150);
  r = await read();
  ok('T2 上翻时到达 ⇒ 视口不动（不强拉）', Math.abs(r.scrollTop - t2.scrollTop) <= 2,
    `before=${t2.scrollTop} after=${r.scrollTop}`);
  ok('T2 胶囊出现且文案=↓ 1 条新消息', r.pillHidden === false && r.pillText.includes('↓ 1 条新消息'),
    `text="${r.pillText}" hidden=${r.pillHidden}`);
  ok('T2 胶囊有可见渲染盒（CSS 生效）', r.pillVisibleBox === true);
  await shot('t2_scrolled-up-pill.png');

  // ── T3 计数累加 ──────────────────────────────────────────────────────────
  deliver(inFrame(103, '上翻时到达的消息二'));
  await waitFor(async () => (await read()).msgCount === r.msgCount + 1);
  deliver(inFrame(104, '上翻时到达的消息三'));
  await waitFor(async () => (await read()).msgCount === r.msgCount + 2);
  await sleep(150);
  r = await read();
  ok('T3 再到两条 ⇒ 胶囊=↓ 3 条新消息', r.pillText.includes('↓ 3 条新消息'), `text="${r.pillText}"`);
  ok('T3 视口仍不动', Math.abs(r.scrollTop - t2.scrollTop) <= 2, `scrollTop=${r.scrollTop}`);
  await shot('t3_count-3.png');

  // ── T4 胶囊点击回底 ──────────────────────────────────────────────────────
  await page.click('.fm-modal > .scroll-new-pill');
  await waitFor(async () => (await read()).dist <= 40);
  await sleep(150);
  r = await read();
  ok('T4 点胶囊 ⇒ 回到贴底区', r.dist <= 40, `dist=${r.dist}`);
  ok('T4 回底 ⇒ 胶囊隐藏', r.pillHidden === true, `pillHidden=${r.pillHidden}`);
  await shot('t4_after-pill-click.png');

  // ── T5 手动滚回底部 ⇒ 清零自隐 ───────────────────────────────────────────
  // 注意：T4 的胶囊点击把光标留在了胶囊位（输入条上方）。胶囊再现时会盖在光标下
  // ⇒ 滚轮会落在 button 上而非 flow ⇒ 不产生滚动。先移回 flow 中部再滚（真实用户
  // 也不会拿光标压着胶囊滚）。
  const c5 = await flowCenter();
  await page.mouse.move(c5.x, c5.y);
  await page.mouse.wheel(0, -400);
  await sleep(200);
  deliver(inFrame(105, '再次上翻后的到达'));
  await waitFor(async () => (await read()).msgCount === r.msgCount + 1);
  await sleep(150);
  r = await read();
  ok('T5.pre 上翻+到达 ⇒ 胶囊再现', r.pillHidden === false && r.pillText.includes('↓ 1 条新消息'),
    `text="${r.pillText}"`);
  const c5b = await flowCenter();
  await page.mouse.move(c5b.x, c5b.y); // 光标离开胶囊 ⇒ 滚轮落回 flow
  await page.mouse.wheel(0, 2000); // 一滚到底
  await sleep(250);
  r = await read();
  ok('T5 手动回底 ⇒ 胶囊自隐（计数清零）', r.pillHidden === true && r.dist <= 40,
    `dist=${r.dist} pillHidden=${r.pillHidden}`);
  await shot('t5_manual-return.png');

  // ── T6 本机发送恒回底 ────────────────────────────────────────────────────
  await page.mouse.wheel(0, -400);
  await sleep(200);
  r = await read();
  ok('T6.pre 已上翻', r.dist > 40, `dist=${r.dist}`);
  await page.fill('.fm-input', '本机在历史位置发送的一条');
  await page.click('.fm-send-btn');
  await waitFor(async () => (await read()).dist <= 40);
  await sleep(200);
  r = await read();
  ok('T6 本机发送 ⇒ 视口回底（乐观气泡可见）', r.dist <= 40, `dist=${r.dist}`);
  await shot('t6_own-send-pins.png');

  // ── 收尾读数 ─────────────────────────────────────────────────────────────
  ok('无未捕获页面异常', pageErrors.length === 0, pageErrors.join(' | ') || 'clean');
  ok('无 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | ') || 'clean');

  await browser.close();
  const failed = results.filter(([, v]) => !v).length;
  console.log(`\n== fm-scroll-follow e2e: ${results.length - failed}/${results.length} PASS ==`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('HARNESS ERROR:', e); process.exit(2); });
