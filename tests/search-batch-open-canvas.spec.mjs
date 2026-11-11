// search-batch-open-canvas.spec.mjs — 搜索多选文件 ⇒ 一键批量在 Canvas 打开
// （chatsearch-batch-open 批验收。wave-1 = 82b0c42bc：选中面收集
// selectedFilesForCanvas + 动作钮 #search-open-selected-files + 逐件走
// openPopArtifact 单点；wave-2 补 locale 键面 + 本 spec。）
//
// 判据面（断言与文案解耦处用 data-* 契约；文案字面只在 locale 腿断言一处）：
//   B1  多选态可视面 + 动作钮跟随：Cmd/Ctrl 点选流行（data-artifact="1"）⇒
//       .multi-sel 虚线环 + 钮出现且计数 = 可 Canvas 打开的选中件数；
//       非 Pop 行（普通消息 / 非 Pop 工具行）入选不增计数。
//   B2  同路径去重：重复 filePath 入选 ⇒ 计数不涨。
//   B3  批量打开契约：点钮 ⇒ 每个唯一路径一枚 workspace-open-item 事件
//       （id=file:<path>、absPath=路径、content=''、itemType=''、pinned=true）
//       + Canvas 面板打开（body.canvas-open）+ 搜索模态关闭。
//   B4  Files 栏目腿：typed 条目卡（type='file'）同样入选可计数；Cmd/Ctrl+A
//       全选 3 卡（含同路径复件）⇒ 计数仍 2（去重在收集面，不在选择集）。
//   B5  重渲染复位：重开模态（面重建）⇒ 选择态清零、钮回隐。
//   B6  locale：zh 字面「在 Canvas 打开 (N)」/ en 字面 'Open in Canvas (N)'；
//       模态开着切 locale ⇒ 钮文案随换（renderStaticLabels 腿）。
//   B7  keyword 检索腿诚实缺席：命中全是不可开行（服务端剥 tool 片的同构夹具）
//       ⇒ 全选后计数 0 ⇒ 钮保持隐藏。
//   B8  双主题 × 双引擎实拍 + 每腿零 console error。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/search-batch-open-canvas.spec.mjs   （ENGINE=webkit 可单跑一引擎）

import { chromium, webkit } from 'playwright';
import http from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS = join(HERE, '..', 'reports-evidence', '20261111_chatsearch-batch-open');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');

// 落点源 = 真实本地端口（WebKit 对 1 号不安全端口网络层拒连——search-return-pill
// 同款技法：本 server 只是「可连的源」，全部请求仍走 page.route，自身零流量）。
const originServer = http.createServer((_req, res) => { res.writeHead(200); res.end('fixture-origin'); });
const ORIGIN = await new Promise((resolve) => {
  originServer.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${originServer.address().port}`));
});

// ── 夹具：6 条消息，3 枚 Pop 卡（其中一枚与首枚同路径=去重腿）、对照行 ×3 ──
const SID = 'sboc-main-session';
const T0 = Date.now();
const P_ALPHA = `/reports/nf-batch-alpha-${SID}.md`;
const P_BETA = `/reports/nf-batch-beta-${SID}.md`;
const popMsg = (label, filePath, title, ts) => ({
  type: 'tool', label,
  input: JSON.stringify({ filePath, title }),
  summary: `Pop 产出 ${title}`,
  timestamp: ts,
});
const SEED = [
  { type: 'user', text: '普通行甲 BATCHOPEN-MARKER（不可 Canvas 打开的对照行）', timestamp: T0 - 600_000 },
  popMsg('Pop(Alpha 报告)', P_ALPHA, 'Alpha 报告', T0 - 500_000),
  { type: 'user', text: '普通行乙 BATCHOPEN-MARKER（对照行）', timestamp: T0 - 400_000 },
  popMsg('Pop(Beta 报告)', P_BETA, 'Beta 报告', T0 - 300_000),
  popMsg('Pop(Alpha 复件)', P_ALPHA, 'Alpha 报告', T0 - 200_000),   // 同路径复件 ⇒ 去重腿
  { type: 'tool', label: 'Bash(ls -la)', input: JSON.stringify({ command: 'ls -la' }), summary: '非 Pop 工具行 BATCHOPEN-MARKER', timestamp: T0 - 100_000 },
];
const SESSIONS = [{ id: SID, name: '主会话', agentName: 'Nebula' }];

function historyPageFrame(msg) {
  const before = typeof msg.beforeIndex === 'number' ? msg.beforeIndex : SEED.length;
  const offset = Math.max(0, before - (msg.limit || 50));
  return {
    type: 'historyPage', sessionId: msg.sessionId,
    messages: SEED.slice(offset, before),
    offset, hasMore: offset > 0, total: SEED.length,
  };
}

/** 索引快路（chatSearch.js C3）：GET /api/search/chat → {hits,total}。
 *  与真服务端同构：tool 片不入命中（作者令①）——只投影 user/ai 文本。 */
function indexHits(url) {
  const q = url.searchParams.get('q') || '';
  const hits = [];
  SEED.forEach((m, ord) => {
    if (m.type !== 'user' && m.type !== 'ai') return;
    if (!m.text || !m.text.includes(q)) return;
    hits.push({ sid: SID, ord, kind: 'message', snippet: m.text, ts: m.timestamp, extra: { role: m.type } });
  });
  return { hits, total: hits.length };
}

/** 每腿一页：隔离 mock（search-return-pill 同法）。theme = 'light'|'dark'。 */
async function newPage(browser, { viewport, locale = 'zh-CN', theme = 'light' }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`console: ${m.text()}`); });
  page.on('response', (r) => { if (r.status() >= 400) consoleErrors.push(`http${r.status()}: ${r.url()}`); });
  await page.emulateMedia({ colorScheme: theme });
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', loc);
    localStorage.setItem('stand_sw', '0');   // swReg 官方逃生门：SW 管线非被测面
  }, locale);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: SESSIONS, activeId: SID }) });
    }
    if (/^\/agents\/[^/]+\/agent\.json$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    const hm = p.match(/^\/api\/sessions\/([^/]+)\/history/);
    if (hm) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: SEED, total: SEED.length, sessionId: hm[1] }) });
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
      // pop.readFile（Canvas 开件取读腿）静默不应答：本 spec 断言到
      // workspace-open-item 事件契约 + 面板打开为止，取读后渲染是 canvas.js
      // 既有面（零应答只让取读 promise 挂起，不产 console error）。
    });
  });

  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 15000 });
  await page.waitForTimeout(600); // 初载滚底/渲染驻停
  // 夹具收面：boot 恢复腿空存储下开 Canvas —— 先收掉，B3 的「面板打开」断言
  // 才是本批动作的净读数（search-return-pill 同法）。
  if (await page.evaluate(() => document.body.classList.contains('canvas-open'))) {
    await page.click('#canvas-toggle-btn');
    await page.waitForFunction(() => !document.body.classList.contains('canvas-open'), undefined, { timeout: 5000 });
    await page.waitForTimeout(300);
  }
  return { page, context, consoleErrors };
}

// ── 读数腿 ──────────────────────────────────────────────────────────────
const BTN = '#search-open-selected-files';
const ART_ROWS = '#search-results .search-result[data-artifact="1"]';

async function openSearch(page) {
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.waitForSelector(ART_ROWS, { timeout: 10000 });   // browse 流渲染驻停
  await page.waitForTimeout(300);
}

/** 钮面读数：在场性 + 字面 + .multi-sel 环计数。 */
const btnState = (page) => page.evaluate((btn) => {
  const el = document.querySelector(btn);
  const rings = document.querySelectorAll('#search-results .multi-sel').length;
  return { exists: !!el, hidden: el ? el.hidden : null, text: el ? el.textContent : '', rings };
}, BTN);

async function cmdClick(page, sel, nth = 0) {
  await page.locator(sel).nth(nth).click({ modifiers: ['ControlOrMeta'] });
}

async function armEventCapture(page) {
  await page.evaluate(() => {
    window.__openEvents = [];
    window.addEventListener('workspace-open-item', (e) => window.__openEvents.push({ ...e.detail }));
  });
}
const readEvents = (page) => page.evaluate(() => window.__openEvents || []);

// ── 共用 browse 腿（B1-B3 + 截图；zh 双主题复用）────────────────────────
async function browseLeg(browser, ENGINE, { theme, shots = false, tag }) {
  const checks = [];
  const check = (name, ok, extra = '') => { checks.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}/${tag}] ${name}${extra ? '  — ' + extra : ''}`); };
  const shot = (n) => join(SHOTS, `${ENGINE}-${tag}-${n}.png`);
  const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 1440, height: 900 }, theme });

  await openSearch(page);
  let st = await btnState(page);
  check('B0 初始态：钮隐藏、零选择环', st.exists && st.hidden === true && st.rings === 0,
    JSON.stringify(st));

  // B1 第一枚 Pop 行入选 ⇒ 钮在场、计数 1、虚线环 1
  await cmdClick(page, ART_ROWS, 0);
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B1 Pop 行入选 ⇒ 钮在场计数 1 + .multi-sel 环', st.exists && st.hidden === false && st.rings === 1 && /1/.test(st.text),
    JSON.stringify(st));
  if (shots) await page.screenshot({ path: shot('1-browse-one-selected') });

  // B1b 非 Pop 对照行（user 行）入选 ⇒ 环涨、计数不涨（收集面只收可开件）
  await page.locator('#search-results .search-result[data-kind="user"]').first()
    .click({ modifiers: ['ControlOrMeta'] });
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B1b user 对照行入选 ⇒ 环 2、计数仍 1', st.rings === 2 && st.hidden === false && /1/.test(st.text),
    JSON.stringify(st));

  // B1c 非 Pop 工具行（Bash）同理不可开 —— 取消选第一枚 Pop 再验证计数归 0 隐钮
  await cmdClick(page, ART_ROWS, 0);   // 再点 = 翻出选择集
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B1c 翻出唯一可开件 ⇒ 计数 0 钮回隐（环仍 1=user 对照）', st.hidden === true && st.rings === 1,
    JSON.stringify(st));
  await page.locator('#search-results .search-result[data-kind="user"]').first()
    .click({ modifiers: ['ControlOrMeta'] });   // 摘掉对照行，回到净空
  await page.waitForTimeout(150);

  // B2 两枚不同路径 Pop 入选 ⇒ 计数 2；同路径复件入选 ⇒ 计数仍 2
  await cmdClick(page, ART_ROWS, 0);
  await cmdClick(page, ART_ROWS, 1);
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B2 α+β 入选 ⇒ 计数 2', st.hidden === false && st.rings === 2 && /2/.test(st.text), JSON.stringify(st));
  await cmdClick(page, ART_ROWS, 2);   // α 复件（同路径）
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B2 同路径复件入选 ⇒ 计数仍 2（环 3）', st.rings === 3 && /2/.test(st.text), JSON.stringify(st));
  if (shots) await page.screenshot({ path: shot('2-batch-ready') });

  // B3 批量打开契约：恰 2 枚事件、载荷契约面、面板开、模态关
  await armEventCapture(page);
  await page.click(BTN);
  await page.waitForTimeout(400);
  const events = await readEvents(page);
  const paths = events.map((e) => e.absPath).sort();
  check('B3 每唯一路径一枚事件（=2）', events.length === 2 && paths[0] === P_ALPHA && paths[1] === P_BETA,
    JSON.stringify(events));
  check('B3 载荷契约（id=file:<path> / content="" / itemType="" / pinned=true）',
    events.every((e) => e.id === 'file:' + e.absPath && e.content === '' && e.itemType === '' && e.pinned === true),
    JSON.stringify(events));
  const faceState = await page.evaluate(() => ({
    canvasOpen: document.body.classList.contains('canvas-open'),
    overlayOn: document.getElementById('search-overlay')?.classList.contains('on') || false,
  }));
  check('B3 Canvas 面板打开 + 搜索模态关闭', faceState.canvasOpen === true && faceState.overlayOn === false,
    JSON.stringify(faceState));

  // B5 重开模态 ⇒ 面重建 ⇒ 选择态清零、钮回隐
  await openSearch(page);
  st = await btnState(page);
  check('B5 重开模态 ⇒ 选择态清零、钮回隐', st.hidden === true && st.rings === 0, JSON.stringify(st));

  check('browse 腿零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
  await context.close();
  return { ok: checks.every(Boolean), passed: checks.filter(Boolean).length, total: checks.length };
}

// ── Files 栏目腿（B4 全选 + 去重 + 批开）─────────────────────────────────
async function filesTabLeg(browser, ENGINE, { theme = 'light', tag = 'files-tab' }) {
  const checks = [];
  const check = (name, ok, extra = '') => { checks.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}/${tag}] ${name}${extra ? '  — ' + extra : ''}`); };
  const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 1440, height: 900 }, theme });

  await openSearch(page);
  await page.click('#search-tab-files');
  await page.waitForSelector('#search-results .search-media-card', { timeout: 8000 });
  await page.waitForTimeout(300);
  const cards = await page.locator('#search-results .search-media-card').count();
  check('B4 Files 栏目 3 张卡（α、β、α 复件）', cards === 3, `cards=${cards}`);

  // 点选 2 张不同路径卡 ⇒ 计数 2；Cmd+A 全选 3 张 ⇒ 计数仍 2（去重）
  await page.locator('#search-results .search-media-card').nth(0).click({ modifiers: ['ControlOrMeta'] });
  await page.locator('#search-results .search-media-card').nth(1).click({ modifiers: ['ControlOrMeta'] });
  await page.waitForTimeout(150);
  let st = await btnState(page);
  check('B4 点选 2 卡 ⇒ 计数 2', st.hidden === false && /2/.test(st.text), JSON.stringify(st));
  await page.locator('#search-results .search-media-card').nth(2).click({ modifiers: ['ControlOrMeta'] });
  await page.keyboard.press('ControlOrMeta+a');   // 全选本组（非输入焦点位）
  await page.waitForTimeout(200);
  st = await btnState(page);
  check('B4 Cmd/Ctrl+A 全选 3 卡 ⇒ 计数仍 2（环 3 = 同路径复件各自入环）',
    st.rings === 3 && /2/.test(st.text), JSON.stringify(st));

  // 批开：仍恰 2 枚事件（复件路径被收集面去重）
  await armEventCapture(page);
  await page.click(BTN);
  await page.waitForTimeout(400);
  const events = await readEvents(page);
  const paths = events.map((e) => e.absPath).sort();
  check('B4 批开 = 2 枚事件（α+β，复件去重）', events.length === 2 && paths[0] === P_ALPHA && paths[1] === P_BETA,
    JSON.stringify(events));

  check('files 腿零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
  await context.close();
  return { ok: checks.every(Boolean), passed: checks.filter(Boolean).length, total: checks.length };
}

// ── locale 腿（B6 双字面 + 模态开着切 locale）────────────────────────────
async function localeLeg(browser, ENGINE, { tag = 'locale-en' }) {
  const checks = [];
  const check = (name, ok, extra = '') => { checks.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}/${tag}] ${name}${extra ? '  — ' + extra : ''}`); };
  const shot = join(SHOTS, `${ENGINE}-${tag}-1-en-label.png`);
  const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light', locale: 'en' });

  await openSearch(page);
  await cmdClick(page, ART_ROWS, 0);
  await cmdClick(page, ART_ROWS, 1);
  await page.waitForTimeout(150);
  let st = await btnState(page);
  check('B6 en 字面 = Open in Canvas (2)', st.text === 'Open in Canvas (2)', JSON.stringify(st));
  await page.screenshot({ path: shot });

  // 模态开着切 zh ⇒ locale-changed ⇒ renderStaticLabels 腿换文案。
  // 注意仓内既有语义（C5）：locale 切换整窗重绘（badges/times 本地化），
  // 「面重建 ⇒ 选择态清零」随之发生 ⇒ 此处断言文案换到 zh 键面（计数归 0
  // 是既有语义的必然读数），再重选一枚验证 zh 字面插值。
  await page.evaluate(async () => { (await import('/js/i18n.js')).setLocale('zh-CN'); });
  await page.waitForTimeout(300);
  st = await btnState(page);
  check('B6 模态开着切 locale ⇒ 钮文案随换 zh 键面（重绘清选择态 = C5 既有语义）',
    st.text.startsWith('在 Canvas 打开 ('), JSON.stringify(st));
  await cmdClick(page, ART_ROWS, 0);
  await page.waitForTimeout(150);
  st = await btnState(page);
  check('B6 换肤后重选 ⇒ zh 字面插值「在 Canvas 打开 (1)」', st.text === '在 Canvas 打开 (1)',
    JSON.stringify(st));

  check('locale 腿零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
  await context.close();
  return { ok: checks.every(Boolean), passed: checks.filter(Boolean).length, total: checks.length };
}

// ── keyword 诚实缺席腿（B7）──────────────────────────────────────────────
async function keywordAbsenceLeg(browser, ENGINE, { tag = 'keyword-absence' }) {
  const checks = [];
  const check = (name, ok, extra = '') => { checks.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  [${ENGINE}/${tag}] ${name}${extra ? '  — ' + extra : ''}`); };
  const { page, context, consoleErrors } = await newPage(browser, { viewport: { width: 1440, height: 900 }, theme: 'light' });

  await openSearch(page);
  await page.fill('#search-keyword', 'BATCHOPEN-MARKER');
  await page.waitForSelector('#search-results .search-result', { timeout: 8000 });
  await page.waitForTimeout(400);
  const hits = await page.locator('#search-results .search-result').count();
  const artHits = await page.locator(ART_ROWS).count();
  check('B7 命中面零可开行（tool 片不入索引的夹具同构：两 user 命中）', hits === 2 && artHits === 0, `hits=${hits} artifacts=${artHits}`);
  await page.locator('#search-results .search-result').first().click({ modifiers: ['ControlOrMeta'] });
  await page.keyboard.press('ControlOrMeta+a');
  await page.waitForTimeout(200);
  const st = await btnState(page);
  check('B7 全选不可开行 ⇒ 计数 0 钮保持隐藏（诚实缺席）', st.hidden === true && st.rings === hits,
    JSON.stringify(st));

  check('keyword 腿零 console error', consoleErrors.length === 0, consoleErrors.join(' | '));
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

  tally(await browseLeg(browser, ENGINE, { theme: 'light', shots: ENGINE === 'chromium', tag: 'desktop-light-zh' }));
  tally(await browseLeg(browser, ENGINE, { theme: 'dark', shots: ENGINE === 'chromium', tag: 'desktop-dark-zh' }));
  tally(await filesTabLeg(browser, ENGINE, {}));
  tally(await localeLeg(browser, ENGINE, {}));
  tally(await keywordAbsenceLeg(browser, ENGINE, {}));

  await browser.close();
  console.log(`\n[${ENGINE}] group: ${groupPassed}/${groupTotal} passed`);
}

const srvClosed = new Promise((resolve) => originServer.close(() => resolve()));
console.log(failedGroups === 0 ? '\nALL GREEN' : `\n${failedGroups} failing group(s)`);
await srvClosed;
process.exit(failedGroups === 0 ? 0 : 1);
