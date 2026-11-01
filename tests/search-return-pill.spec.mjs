// search-return-pill.spec.mjs — 搜索跳转「回到跳转前位置」胶囊验收
//（作者令 2026-11-01：搜索→跳转→胶囊出现→点击回位全链路；样式逐字复用
// 「↓ N 条新消息」胶囊现役组件 `.scroll-new-pill`，零新 CSS）。
//
// 判据面（断言与文案/i18n 解耦，data-* 契约；文案只在一处字面断言）：
//   P1  桌面 zh 亮色全链路：跳转落地出胶囊（state=anchor）→ 点击平滑回位 →
//       锚行视口相对位置复原（±3px）；胶囊落位 = #input-area 上缘 +12px、
//       水平居中、浮层三连（rect 有面积 / 视口内 / elementFromPoint 命中）。
//       真锚断言：胶囊墙上的锚身份（data-msg-id/data-ts）在 #chat 解析得到行。
//   P2  降级换挡：锚行被流重建逐出 ⇒ state=latest；点击 ⇒ 回到最新（贴底）。
//   P3  消失时机：用户滚离（wheel）⇒ 胶囊即隐。
//   P4  再次新跳转 = 换锚（单一胶囊节点；回位落点 = 第二次跳转前位置）。
//   P5  跨会话跳转不设胶囊（锚住在另一条会话流里无法保真 ⇒ 诚实缺席）。
//   P6  双主题 × 双引擎 × 手机视口（390×844 独立交互层同一条腿）+ 全链路实拍。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/search-return-pill.spec.mjs   （ENGINE=webkit 可单跑一引擎）

import { chromium, webkit } from 'playwright';
import http from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS = join(HERE, '..', 'evidence', 'search-return-pill');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');

// 落点源 = 真实本地端口（WebKit 对 1 号不安全端口在网络层直接拒连，路由拦截
// 根本收不到 —— pr41 的 localhost:1 技法是 Chromium 专属）。page.route 仍拦
// 全部请求，本 server 只是 WebKit 眼里「可连的源」，自身零流量。
const ORIGIN = await new Promise((resolve) => {
  const srv = http.createServer((_req, res) => { res.writeHead(200); res.end('fixture-origin'); });
  srv.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${srv.address().port}`));
});

// ── 夹具：56 条历史（初载 50 ⇒ hasMore），跳转目标在需翻页的最老段 ─────
const SID = 'srp-main-session';
const SID2 = 'srp-other-session';
const T0 = Date.now();
const MARKER_A = '深水锚点甲0703';   // 目标 A = idx 2（最老段，需 WS 翻页）
const MARKER_B = '深水锚点乙0912';   // 目标 B = idx 4（A 落地后已在 DOM）
const SEED = [];
for (let i = 0; i < 56; i++) {
  if (i === 2) {
    SEED.push({ type: 'user', text: `请定位这句 ${MARKER_A}（老历史第一跳目标）`, timestamp: T0 - 56_000_000 + i * 60_000 });
  } else if (i === 4) {
    SEED.push({ type: 'user', text: `第二跳目标行 ${MARKER_B}（回位换锚判据）`, timestamp: T0 - 56_000_000 + i * 60_000 });
  } else if (i % 2 === 0) {
    SEED.push({ type: 'user', text: `桌面读位填充行 u-${i} —— 用于锚定视口读数`, timestamp: T0 - 56_000_000 + i * 60_000 });
  } else {
    SEED.push({ type: 'ai', text: `回复填充行 a-${i}：滚动跟随与胶囊判据夹具行。`, timestamp: T0 - 56_000_000 + i * 60_000 });
  }
}
const SEED2 = [
  { type: 'user', text: '跨会话命中行 CROSS-SESSION-HIT-4F2', timestamp: T0 - 90_000_000 },
  { type: 'ai', text: '另一条会话的普通回复。', timestamp: T0 - 89_900_000 },
];
const SESSIONS = [
  { id: SID, name: '主会话', agentName: 'Nebula' },
  { id: SID2, name: '另一会话', agentName: 'Nebula' },
];

function historyOf(sid) { return sid === SID2 ? SEED2 : SEED; }

function historyPageFrame(msg) {
  const total = historyOf(msg.sessionId).length;
  const before = typeof msg.beforeIndex === 'number' ? msg.beforeIndex : total;
  const offset = Math.max(0, before - (msg.limit || 50));
  return {
    type: 'historyPage', sessionId: msg.sessionId,
    messages: historyOf(msg.sessionId).slice(offset, before),
    offset, hasMore: offset > 0, total,
  };
}

/** 索引快路（chatSearch.js C3）：GET /api/search/chat → {hits,total}。 */
function indexHits(url) {
  const q = url.searchParams.get('q') || '';
  const sid = url.searchParams.get('sid') || '';
  const hits = [];
  for (const s of SESSIONS) {
    if (sid && s.id !== sid) continue;
    historyOf(s.id).forEach((m, ord) => {
      if (!m.text || !m.text.includes(q)) return;
      hits.push({ sid: s.id, ord, kind: 'message', snippet: m.text, ts: m.timestamp, extra: { role: m.type === 'user' ? 'user' : 'ai' } });
    });
  }
  return { hits, total: hits.length };
}

/** 每腿一页：隔离 mock（pr41 同法）。theme = 'light'|'dark'。 */
async function newPage(browser, { viewport, locale = 'zh-CN', theme = 'light', touch = false }) {
  const context = await browser.newContext({ viewport, hasTouch: touch });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`console: ${m.text()}`); });
  page.on('response', (r) => { if (r.status() >= 400) consoleErrors.push(`http${r.status()}: ${r.url()}`); });
  await page.emulateMedia({ colorScheme: theme });
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', loc);
    // swReg.js 自带的逃生门（<lower>_sw='0' = 跳过注册）：路由拦截源上 SW 脚本
    // 管线不走 page.route，注册必败且只产生「fetch the script」console 噪声——
    // 温访问缓存非本批被测面，夹具按官方逃生门关掉（无域名豁免）。
    localStorage.setItem('stand_sw', '0');
  }, locale);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: SESSIONS, activeId: SID }) });
    }
    if (/^\/agents\/[^/]+\/agent\.json$/.test(p)) {
      // rootName.js 读数探针（displayName）；缺席即 404 噪声，夹具给真形。
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    const hm = p.match(/^\/api\/sessions\/([^/]+)\/history/);
    if (hm) {
      const msgs = historyOf(hm[1]);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: msgs, total: msgs.length, sessionId: hm[1] }) });
    }
    if (p === '/api/search/chat') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(indexHits(url)) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = (o) => ws.send(JSON.stringify(o));
    const sendConfig = () => send({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    const sendSessions = () => send({ type: 'sessionList', sessions: SESSIONS, folders: [], activeId: SID });
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') send(historyPageFrame(m));
    });
  });

  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 15000 });
  await page.waitForTimeout(600); // 初载滚底/渲染驻停
  // 夹具收面：boot 恢复腿空存储下开 Canvas（右列「插件」pane）——非本批被测面，
  // 点顶栏 Canvas 开关收掉，证据帧回到纯消息流读位。（手机层无此开关 —— 该键
  // display:none，跳过。）
  if (await page.evaluate(() => !document.body.classList.contains('mobile-layer') &&
      document.body.classList.contains('canvas-open'))) {
    await page.click('#canvas-toggle-btn');
    await page.waitForFunction(() => !document.body.classList.contains('canvas-open'), undefined, { timeout: 5000 });
    await page.waitForTimeout(300);
  }
  return { page, context, consoleErrors };
}

// ── 读数腿（独立实现，与 searchReturnPill.js 同契约不同源）──────────────
/** 视口顶缘第一条可寻址行（msgId 或 duration-badge ts）+ 视口相对偏移。 */
const READ_ANCHOR = () => {
  const chat = document.getElementById('chat');
  const chatTop = chat.getBoundingClientRect().top;
  for (const node of chat.children) {
    const row = node;
    if (!(row.classList && row.classList.contains('row') || row.classList && row.classList.contains('tool-card'))) continue;
    const r = row.getBoundingClientRect();
    if (r.bottom <= chatTop + 1) continue;
    const msgId = row.dataset.msgId || '';
    const badge = row.querySelector('[data-ts]');
    const ts = badge ? badge.getAttribute('data-ts') : '';
    if (!msgId && !(ts && ts !== '0')) continue;
    return { msgId, ts, offset: r.top - chatTop };
  }
  return null;
};

/** 按身份回解析行视口偏移（点击回位后的断言读数）。 */
const READ_BY_ID = (pre) => {
  const chat = document.getElementById('chat');
  const chatTop = chat.getBoundingClientRect().top;
  let row = null;
  if (pre.msgId) row = chat.querySelector(`[data-msg-id="${CSS.escape(pre.msgId)}"]`);
  if (!row && pre.ts) {
    const badge = chat.querySelector(`[data-ts="${CSS.escape(pre.ts)}"]`);
    row = badge ? badge.closest('.row, .tool-card') : null;
  }
  if (!row) return null;
  return row.getBoundingClientRect().top - chatTop;
};

const pillSel = '#main [data-nf-search-return="1"]';

const pillState = (page) => page.evaluate((sel) => {
  const el = document.querySelector(sel);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  const chat = document.getElementById('chat');
  const inputArea = document.getElementById('input-area');
  const at = r.width > 0 && r.height > 0
    ? document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) : null;
  // 真锚断言：胶囊墙上的锚身份在 #chat 里解析得到一行
  const anchorId = el.dataset.msgId || el.dataset.ts || '';
  let anchorRow = null;
  if (el.dataset.msgId) anchorRow = chat.querySelector(`[data-msg-id="${CSS.escape(el.dataset.msgId)}"]`);
  if (!anchorRow && el.dataset.ts) {
    const badge = chat.querySelector(`[data-ts="${CSS.escape(el.dataset.ts)}"]`);
    anchorRow = badge ? badge.closest('.row, .tool-card') : null;
  }
  return {
    exists: true, hidden: el.classList.contains('hidden'), state: el.dataset.state,
    text: el.textContent,
    anchorId, anchorResolves: !!anchorRow,
    inViewport: r.top >= 0 && r.left >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
    hitSelf: at === el || !!(at && el.contains(at)),
    bottomGapToInput: (inputArea ? inputArea.getBoundingClientRect().top : innerHeight) - r.bottom,
    horizontallyCentered: Math.abs((r.left + r.right) / 2 - (chat.getBoundingClientRect().left + chat.getBoundingClientRect().right) / 2),
    rect: { w: Math.round(r.width), h: Math.round(r.height) },
  };
}, pillSel);

/** 平滑滚动驻停（连续两样本 scrollTop 稳定）。 */
async function settleScroll(page, timeout = 4000) {
  const probe = () => page.evaluate(() => document.getElementById('chat').scrollTop);
  let last = await probe();
  const t0 = Date.now();
  for (;;) {
    await page.waitForTimeout(220);
    const cur = await probe();
    if (Math.abs(cur - last) <= 1) return cur;
    last = cur;
    if (Date.now() - t0 > timeout) return cur;
  }
}

async function openSearch(page) {
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
}
async function searchAndClick(page, kw) {
  await page.fill('#search-keyword', kw);
  await page.waitForSelector('#search-results .search-result', { timeout: 8000 });
  await page.waitForTimeout(400); // 渲染驻停
  await page.locator('#search-results .search-result').first().click();
}
async function waitPill(page) {
  await page.waitForFunction((sel) => {
    const el = document.querySelector(sel);
    return !!el && !el.classList.contains('hidden');
  }, pillSel, { timeout: 15000 });
}

// ── 共用全链路腿（P1/P6 桌面双主题 + 手机腿复用）────────────────────────
async function fullChainLeg(browser, ENGINE, { viewport, theme, touch = false, shots = null, tag = 'leg' }) {
  const checks = [];
  const check = (name, ok, extra = '') => { checks.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}/${tag}] ${name}${extra ? '  — ' + extra : ''}`); };
  const { page, context, consoleErrors } = await newPage(browser, { viewport, theme, touch });

  const pre = await page.evaluate(READ_ANCHOR);
  check('跳转前锚可捕获（可寻址行在场）', !!pre, JSON.stringify(pre));
  if (shots) await page.screenshot({ path: join(SHOTS, `${ENGINE}-${tag}-0-prejump.png`) });

  await openSearch(page);
  if (shots) await page.screenshot({ path: join(SHOTS, `${ENGINE}-${tag}-1-search.png`) });
  await searchAndClick(page, MARKER_A);

  await page.waitForFunction((kw) => {
    const el = document.querySelector('#chat .search-hit-flash');
    return !!el && el.textContent.includes(kw);
  }, MARKER_A, { timeout: 15000 });
  await waitPill(page);
  await settleScroll(page); // 落地滚动驻停后再拍：帧内 = 落点行 + 闪击 + 胶囊
  if (shots) await page.screenshot({ path: join(SHOTS, `${ENGINE}-${tag}-2-jump-pill.png`) });

  const st = await pillState(page);
  check('胶囊在场（state=anchor）', !!st && !st.hidden && st.state === 'anchor',
    JSON.stringify(st && { state: st.state, hidden: st.hidden }));
  check('文案 = 回到跳转前位置（作者令字面）', !!st && st.text === '回到跳转前位置', st && st.text);
  check('真锚：胶囊锚身份在 #chat 解析得到行', !!st && st.anchorId !== '' && st.anchorResolves === true,
    st && JSON.stringify({ id: st.anchorId, resolves: st.anchorResolves }));
  check('浮层三连（有面积/视口内/elementFromPoint 命中）',
    !!st && st.rect.w > 0 && st.rect.h > 0 && st.inViewport && st.hitSelf,
    JSON.stringify(st && { inViewport: st.inViewport, hitSelf: st.hitSelf }));
  check('落位 = #input-area 上缘 +12px（新消息胶囊同式）',
    !!st && Math.abs(st.bottomGapToInput - 12) <= 2, st && `gap=${Math.round(st.bottomGapToInput * 10) / 10}`);
  check('水平居中（流下沿同位）', !!st && st.horizontallyCentered <= 2, st && `dx=${Math.round(st.horizontallyCentered * 10) / 10}`);

  await page.click(pillSel);
  await settleScroll(page);
  if (shots) await page.screenshot({ path: join(SHOTS, `${ENGINE}-${tag}-3-after-return.png`) });
  const postOffset = await page.evaluate(READ_BY_ID, pre);
  check('回位 = 锚行视口偏移复原（|Δ|≤3px）',
    postOffset !== null && pre !== null && Math.abs(postOffset - pre.offset) <= 3,
    `pre=${pre && pre.offset} post=${postOffset}`);
  const pillGone = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    return !el || el.classList.contains('hidden');
  }, pillSel);
  check('点击后胶囊消失（消失时机①）', pillGone);
  check('零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));

  await context.close();
  return { ok: checks.every(Boolean), passed: checks.filter(Boolean).length, total: checks.length };
}

// ════════════════════════════════════════════════════════════════════════
let failedGroups = 0;
for (const ENGINE of ENGINES) {
  const browser = await (ENGINE === 'webkit' ? webkit : chromium).launch({ headless: process.env.HEADLESS !== '0' });
  mkdirSync(SHOTS, { recursive: true });
  let groupPassed = 0, groupTotal = 0;
  const tally = (r) => { groupPassed += r.passed; groupTotal += r.total; if (!r.ok) failedGroups++; };

  // P1 桌面亮色全链路 + 实拍
  tally(await fullChainLeg(browser, ENGINE, { viewport: { width: 1440, height: 900 }, theme: 'light', shots: true, tag: 'desktop-light-zh' }));
  // P6 桌面暗色全链路 + 实拍（双主题）
  tally(await fullChainLeg(browser, ENGINE, { viewport: { width: 1440, height: 900 }, theme: 'dark', shots: true, tag: 'desktop-dark-zh' }));

  // P6 手机视口全链路 + 实拍（390×844 独立交互层；入口 = 顶缘搜索圆钮）×双主题
  for (const mTheme of ['light', 'dark']) {
    let ok = 0, total = 0;
    const check = (name, cond, extra = '') => { total++; if (cond) ok++; console.log(`${cond ? 'PASS' : 'FAIL'}  [${ENGINE}/mobile-${mTheme}] ${name}${extra ? '  — ' + extra : ''}`); };
    const shot = (n) => join(SHOTS, `${ENGINE}-mobile-${mTheme}-${n}.png`);
    const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 390, height: 844 }, theme: mTheme, touch: true });

    check('独立交互层已激活（body.mobile-layer）', await page.evaluate(() => document.body.classList.contains('mobile-layer')));
    const pre = await page.evaluate(READ_ANCHOR);
    check('跳转前锚可捕获', !!pre, JSON.stringify(pre));

    await page.locator('[data-chrome="search"]').click();
    await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="search"]');
      if (!el) return true; // 页面收编缺席 = overlay 直开形态
      const tf = getComputedStyle(el).transform;
      return el.classList.contains('live') && (tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)');
    }, undefined, { timeout: 6000 }).catch(() => {});
    await page.screenshot({ path: shot('1-search') });

    await page.fill('#search-keyword', MARKER_A);
    await page.waitForSelector('#search-results .search-result', { timeout: 8000 });
    await page.waitForTimeout(400);
    await page.screenshot({ path: shot('2-results') });
    await page.locator('#search-results .search-result').first().click();

    await page.waitForFunction((kw) => {
      const el = document.querySelector('#chat .search-hit-flash');
      return !!el && el.textContent.includes(kw);
    }, MARKER_A, { timeout: 15000 });
    await waitPill(page);
    await settleScroll(page); // 落地滚动驻停后再拍
    await page.screenshot({ path: shot('3-jump-pill') });

    const st = await pillState(page);
    check('胶囊在场（state=anchor）+ 浮层三连',
      !!st && !st.hidden && st.state === 'anchor' && st.inViewport && st.hitSelf && st.anchorResolves,
      JSON.stringify(st && { state: st.state, inViewport: st.inViewport, hitSelf: st.hitSelf, resolves: st.anchorResolves }));
    check('文案 = 回到跳转前位置', !!st && st.text === '回到跳转前位置', st && st.text);

    await page.click(pillSel);
    await settleScroll(page);
    await page.screenshot({ path: shot('4-after-return') });
    const postOffset = await page.evaluate(READ_BY_ID, pre);
    check('回位 = 锚行视口偏移复原（|Δ|≤3px）',
      postOffset !== null && pre !== null && Math.abs(postOffset - pre.offset) <= 3,
      `pre=${pre && pre.offset} post=${postOffset}`);
    check('零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
    await context.close();
    groupPassed += ok; groupTotal += total; if (ok !== total) failedGroups++;
  }

  // P2/P3/P4/P5 契约腿（桌面亮色一页串行）
  {
    let ok = 0, total = 0;
    const check = (name, cond, extra = '') => { total++; if (cond) ok++; console.log(`${cond ? 'PASS' : 'FAIL'}  [${ENGINE}/contract] ${name}${extra ? '  — ' + extra : ''}`); };
    const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light' });

    await openSearch(page);
    await searchAndClick(page, MARKER_A);
    await waitPill(page);

    // P2：锚行被逐出（模拟流重建丢弃）⇒ 降级换挡 state=latest
    await page.evaluate((sel) => {
      const pill = document.querySelector(sel);
      const chat = document.getElementById('chat');
      const id = pill.dataset.msgId || '';
      const row = id
        ? chat.querySelector(`[data-msg-id="${CSS.escape(id)}"]`)
        : chat.querySelector(`[data-ts="${CSS.escape(pill.dataset.ts || '')}"]`)?.closest('.row, .tool-card');
      if (row) row.remove();
    }, pillSel);
    await page.waitForFunction((sel) => document.querySelector(sel)?.dataset.state === 'latest', pillSel, { timeout: 3000 });
    check('P2: 锚行逐出 ⇒ state=latest（诚实降级换挡）', true);
    await page.click(pillSel);
    await settleScroll(page);
    const atBottom = await page.evaluate(() => {
      const c = document.getElementById('chat');
      return c.scrollHeight - c.scrollTop - c.clientHeight;
    });
    check('P2: 降级点击 = 回到最新（贴底 ≤3px）', atBottom <= 3, `dist=${Math.round(atBottom)}`);

    // P3：用户滚离 ⇒ 胶囊即隐（先重新跳转造一颗）
    await openSearch(page);
    await searchAndClick(page, MARKER_B);
    await waitPill(page);
    await page.mouse.move(720, 450);
    await page.mouse.wheel(0, -240);
    await page.waitForTimeout(300);
    const hiddenAfterWheel = await page.evaluate((sel) => document.querySelector(sel)?.classList.contains('hidden'), pillSel);
    check('P3: 用户滚离（wheel）⇒ 胶囊即隐', hiddenAfterWheel === true, `hidden=${hiddenAfterWheel}`);

    // P4：换锚 —— 连续两次跳转，回位落点 = 第二次跳转前位置
    await openSearch(page);
    await searchAndClick(page, MARKER_A);   // 第一跳（造胶囊，锚=当时读位）
    await waitPill(page);
    await settleScroll(page);
    const pre2 = await page.evaluate(READ_ANCHOR);
    await openSearch(page);
    await searchAndClick(page, MARKER_B);   // 第二跳
    await waitPill(page);
    const pillCount = await page.evaluate((sel) => document.querySelectorAll(sel).length, pillSel);
    check('P4: 单一胶囊节点（再次跳转=换锚不另起）', pillCount === 1, `count=${pillCount}`);
    await page.click(pillSel);
    await settleScroll(page);
    const post2 = await page.evaluate(READ_BY_ID, pre2);
    check('P4: 回位落点 = 第二次跳转前位置（|Δ|≤3px）',
      post2 !== null && pre2 !== null && Math.abs(post2 - pre2.offset) <= 3,
      `pre2=${pre2 && pre2.offset} post2=${post2}`);

    // P5：跨会话跳转不设胶囊（锚住在另一条会话流里）
    await openSearch(page);
    await page.selectOption('#search-scope', 'all');
    await page.fill('#search-keyword', 'CROSS-SESSION-HIT-4F2');
    await page.waitForSelector('#search-results .search-result', { timeout: 8000 });
    await page.waitForTimeout(400);
    await page.locator('#search-results .search-result').first().click();
    await page.waitForTimeout(2500); // 切会话 + 潜在落地窗
    const crossPill = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      return !!el && !el.classList.contains('hidden');
    }, pillSel);
    check('P5: 跨会话跳转不出胶囊（诚实缺席）', crossPill === false, `visible=${crossPill}`);
    const crossFlash = await page.evaluate(() => !!document.querySelector('#chat .search-hit-flash'));
    const activeNow = await page.evaluate(async () => (await import('/js/state.js')).default.activeSessionId);
    console.log(`OBSERVE [${ENGINE}] 跨会话命中点击后：activeSession=${activeNow}（既有跳转腿行为，仅记录不判） flash=${crossFlash}`);

    check('contract: 零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
    await context.close();
    groupPassed += ok; groupTotal += total; if (ok !== total) failedGroups++;
  }

  await browser.close();
  console.log(`\n[${ENGINE}] group: ${groupPassed}/${groupTotal} passed`);
}

console.log(failedGroups === 0 ? '\nALL GREEN' : `\n${failedGroups} failing group(s)`);
process.exit(failedGroups === 0 ? 0 : 1);
