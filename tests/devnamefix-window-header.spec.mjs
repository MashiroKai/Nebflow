// devnamefix-window-header.spec.mjs — U3（root 2026-09-15 · #600）
//   「**U3（无名设备窗头名回落 device id）**：判据 = 无名称无描述的设备，窗头显示
//    占位文案（如「未命名设备」）而非 id；与作者 ⑤『不显示设备码』同族精神。」
//
// 真渲染验收探针：**同一支探针跑两棵树**（判红纪律：「改前不可满足」）。
//   · `DEVNAMEFIX_WEB_ROOT` 未设 ⇒ 跑本仓 `src/main/resources/web`（**改后树** AFTER）
//   · `DEVNAMEFIX_WEB_ROOT=/tmp/nb-devnamefix/before/src/main/resources/web`
//     （`git archive 3be2b67cc… src/main/resources/web` 导出的**改前树**）⇒
//     红锚 `U3a` **必须红** ⇒ 那就是「缺陷在真渲染里可复现」的原文证据。
//
// 覆盖：
//   U3a（红锚）无名（无 deviceName 且无 userDescription）设备 ⇒ 窗头 = **占位文案**
//               （zh「未命名设备」/ en「Unnamed device」），且窗头全文 / aria-label
//               **不含 device id 字面**。
//   U3b（双向） 有名设备 ⇒ 窗头**照旧**显示其名称（禁只测无名态）。
//   U3c（双向） 有描述设备 ⇒ 描述优先（既有优先级零回归）。
//   U3d（护栏） 对话框 `role=dialog` 的 aria-label 与窗头文本**同源**（aria = name）。
//   U3e（读数） 消息面板设备行名（面板口径**不在本令指涉面**：仍回落 id ⇒ 只打印
//               读数 + 断言行在场，不做「必须等于 id」的绿灯断言）。
//
// 四轮 = 亮/暗双主题 × zh-CN/en（全站主题机制 = `prefers-color-scheme` 媒体查询）。
// 自包含：静态服务器随机隔离端口 + route 拦截；finally 必关（进程清理纪律）。
// Run:
//   node tests/devnamefix-window-header.spec.mjs
//   DEVNAMEFIX_WEB_ROOT=/tmp/nb-devnamefix/before/src/main/resources/web node tests/devnamefix-window-header.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.DEVNAMEFIX_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.DEVNAMEFIX_WEB_ROOT ? 'BEFORE' : 'AFTER';
const SHOTS = process.env.DEVNAMEFIX_SHOTS || join('/tmp', 'nb-devnamefix', MODE);

// 无名设备（判据主体）、有名设备（双向读数的另一半）、有描述设备（优先级护栏）。
const UNNAMED = 'dev-unnamed-9c41';
const NAMED = 'dev-named-7b22';
const NAMED_NAME = 'Phone';
const DESCRIBED = 'dev-desc-5a10';
const DESCRIBED_DESC = '书房工作站';
// 🔴 红锚：改前树必须红的那条断言 id。
const RED_ANCHORS = new Set(['U3a']);
const PLACEHOLDER = { 'zh-CN': '未命名设备', en: 'Unnamed device' };

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SELF = 'dev-self';

const results = [];
const readings = [];
function ok(id, theme, locale, name, cond, extra = '') {
  results.push({ id, theme, locale, pass: !!cond, name, extra });
  readings.push(`  ${cond ? 'PASS' : 'FAIL'}  [${id}/${theme}/${locale}] ${name}${extra ? '  — ' + extra : ''}`);
  console.log(readings[readings.length - 1]);
}
function note(line) { readings.push(line); console.log(line); }

const sleep = ms => new Promise(r => setTimeout(r, ms));

const conversations = () => [UNNAMED, NAMED, DESCRIBED].map(id => ({
  conversationId: 'dev:' + id, kind: 'device', deviceId: id, unreadCount: 0, lastMessage: null,
}));

function installRoutes(page) {
  return page.route('**/api/**', async r => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    const m = r.request().method();
    if (p === '/api/neblink/status') {
      return r.fulfill({
        json: {
          loggedIn: true,
          device: { id: SELF, name: 'Mac', platform: 'macos', userDescription: '' },
          peers: [
            // 🔴 U3 主体：既无 deviceName 也无 userDescription。
            { deviceId: UNNAMED, platform: 'windows', online: true, directOnline: true },
            { deviceId: NAMED, deviceName: NAMED_NAME, platform: 'macos', online: true, directOnline: true },
            { deviceId: DESCRIBED, deviceName: 'Laptop', userDescription: DESCRIBED_DESC, platform: 'linux', online: true, directOnline: true },
          ],
        },
      });
    }
    if (p === '/api/conversations' && m === 'GET') return r.fulfill({ json: conversations() });
    if (p === '/api/users/search') return r.fulfill({ json: { found: false } });
    if (p === '/api/friends') return r.fulfill({ json: { friends: [], incoming: [], outgoing: [] } });
    if (p === '/api/groups') return r.fulfill({ json: [] });
    return r.fulfill({ json: {} });
  });
}

function installWs(page) {
  return page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

async function shot(page, theme, locale, name) {
  try {
    await mkdir(join(SHOTS, `${theme}-${locale}`), { recursive: true });
    await page.screenshot({ path: join(SHOTS, `${theme}-${locale}`, name + '.png') });
  } catch { /* 证据截图不是判据 */ }
}

/** 开一台设备的会话窗 ⇒ 读窗头（文本 + 全文 + aria-label）⇒ **窗开着**截图 ⇒ Escape 关窗。 */
async function openDeviceWindowAndRead(page, deviceId, shotsFor) {
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="dev:${deviceId}"]`);
  await page.waitForSelector('.fm-modal', { timeout: 10000 });
  await sleep(450);
  const read = await page.evaluate(() => {
    const modal = document.querySelector('.fm-modal'); // 会话窗模态（role=dialog + aria-label 都在它身上）
    return {
      name: (modal.querySelector('.fm-modal-name') || {}).textContent || null,
      headerText: (modal.querySelector('.fm-modal-header') || {}).textContent || null,
      sub: (modal.querySelector('.fm-modal-id') || {}).textContent || null,
      // ⚠ 必须取会话窗自己的那一个：文首其它模态（ctxthresh 等）也带 role=dialog。
      aria: modal.getAttribute('aria-label'),
    };
  });
  // 🔴 截图必须在**窗仍开着**时拍（先 read → 再 shot → 最后关窗），否则拍到的是关窗后的面板态。
  if (shotsFor) await shot(page, shotsFor.theme, shotsFor.locale, shotsFor.name);
  await page.keyboard.press('Escape');
  await page.waitForSelector('.fm-modal', { state: 'detached', timeout: 10000 }).catch(() => {});
  await sleep(250);
  return read;
}

const browser = await chromium.launch();
let exitCode = 0;
try {
  note(`# MODE=${MODE}  WEB=${WEB}`);
  for (const theme of ['light', 'dark']) {
    for (const locale of ['zh-CN', 'en']) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
      let page = null;
      try {
        await ctx.addInitScript(l => {
          localStorage.setItem('nebflow_token', 't');
          localStorage.setItem('neblink_token', 't');
          localStorage.setItem('nebflow_locale', l);
          localStorage.setItem('neblink_locale', l);
        }, locale);
        page = await ctx.newPage();
        const pageErrors = [];
        page.on('pageerror', e => pageErrors.push(e.message));
        await installRoutes(page);
        await installWs(page);

        await page.goto(BASE + '/index.html');
        await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
        await sleep(1000);
        // 设备档案（peers）= 设备窗名字的唯一数据源，必须先落 state。
        await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
        await sleep(300);
        // 语言真源 = i18n.js（`nebflow_locale` 为存储键）；强制并断言生效值。
        const effLocale = await page.evaluate(async l => {
          const i = await import('/js/i18n.js');
          i.setLocale(l);
          return i.getLocale();
        }, locale);
        note(`  [theme=${theme}/${locale}] effectiveLocale=${effLocale}`);
        if (effLocale !== locale) { ok('GUARD-locale', theme, locale, '探针语言生效', false, `got=${effLocale}`); continue; }

        // 主题读数（证明两轮确实是两个主题：同元素计算样式不同）。
        const themeProbe = await page.evaluate(() => ({
          scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
          bg: getComputedStyle(document.body).backgroundColor,
        }));
        note(`  [theme=${theme}/${locale}] prefers-color-scheme=${themeProbe.scheme} body.bg=${themeProbe.bg}`);

        // 消息面板上场（设备窗的入口）。
        const active = await page.evaluate(() => !!document.getElementById('panel-messages')?.classList.contains('active'));
        if (!active) await page.click('#messages-btn');
        await sleep(600);
        const rowCount = await page.evaluate(() => document.querySelectorAll('#fm-conversations .fm-conv-row[data-device="1"]').length);
        ok('GUARD-device-rows', theme, locale, '消息面板三台设备行在场（数据面就绪）', rowCount === 3, `rows=${rowCount}`);
        if (rowCount !== 3) continue;

        // ── U3a（红锚）：无名设备窗头 ───────────────────────────────
        const un = await openDeviceWindowAndRead(page, UNNAMED, { theme, locale, name: '01-unnamed-window' });
        const ph = PLACEHOLDER[locale];
        const nameOk = un.name === ph;
        const noIdInHeader = !(un.headerText || '').includes(UNNAMED);
        const noIdInAria = !(un.aria || '').includes(UNNAMED);
        note(`  [${theme}/${locale}] 无名窗头读数 name=${JSON.stringify(un.name)} placeholder=${JSON.stringify(ph)} headerText=${JSON.stringify(un.headerText)} aria=${JSON.stringify(un.aria)}`);
        ok('U3a', theme, locale, `无名设备窗头 = 占位文案「${ph}」且窗头无 device id`,
          nameOk && noIdInHeader && noIdInAria,
          `name=${JSON.stringify(un.name)} headerHasId=${!noIdInHeader} ariaHasId=${!noIdInAria}`);
        // U3d（护栏）：aria-label 与窗头文本同源。
        ok('U3d', theme, locale, '对话框 aria-label 与窗头文本同源（同一单点）',
          un.aria === un.name, `aria=${JSON.stringify(un.aria)} name=${JSON.stringify(un.name)}`);

        // ── U3b（双向）：有名设备窗头照旧 = 其名称 ──────────────────
        const nm = await openDeviceWindowAndRead(page, NAMED, { theme, locale, name: '02-named-window' });
        note(`  [${theme}/${locale}] 有名窗头读数 name=${JSON.stringify(nm.name)}`);
        ok('U3b', theme, locale, `有名设备窗头照旧显示其名称「${NAMED_NAME}」（双向读数）`,
          nm.name === NAMED_NAME, `name=${JSON.stringify(nm.name)}`);

        // ── U3c（双向）：描述优先 ──────────────────────────────────
        const ds = await openDeviceWindowAndRead(page, DESCRIBED, { theme, locale, name: '03-described-window' });
        note(`  [${theme}/${locale}] 有描述窗头读数 name=${JSON.stringify(ds.name)}`);
        ok('U3c', theme, locale, `有描述设备窗头 = 描述「${DESCRIBED_DESC}」（描述 > 设备名）`,
          ds.name === DESCRIBED_DESC, `name=${JSON.stringify(ds.name)}`);

        // ── U3e（读数）：消息面板设备行名 = 面板口径（不在本令指涉面）────
        const panelRow = await page.evaluate(id => {
          const row = document.querySelector(`#fm-conversations .fm-conv-row[data-conversation-id="dev:${id}"]`);
          return row ? (row.querySelector('.fm-row-name') || {}).textContent : null;
        }, UNNAMED);
        note(`  [${theme}/${locale}] 读数（面板行，不在本令指涉面）无名设备行名=${JSON.stringify(panelRow)}`);
        ok('U3e', theme, locale, '消息面板设备行在场且有名（面板口径只读数、不断言取值）',
          !!panelRow && panelRow.length > 0, `rowName=${JSON.stringify(panelRow)}`);

        // ── U3f（回归面）：联系人面板设备行 = 同源单点**默认形态**，未误伤 ────
        // 判据：行名 === 页内现取 `deviceLabel(peer)`（**不带** forWindowTitle）——
        // 即「面板口径仍走默认形态」；两树读数逐字相同（改前/改后日志对照）。
        const cActive = await page.evaluate(() => !!document.getElementById('panel-contacts')?.classList.contains('active'));
        if (!cActive) await page.click('#contacts-btn');
        await sleep(600);
        if (await page.locator('[data-device-section="1"]').count() === 0
          && await page.locator('[data-devices-entry="1"]').count() === 1) {
          await page.click('[data-devices-entry="1"]');
          await sleep(500);
        }
        const contactRead = await page.evaluate(async id => {
          const row = document.querySelector(`[data-device-section="1"] .fm-device-row[data-device-id="${id}"]`);
          const shown = row ? (row.querySelector('.neblink-peer-name') || {}).textContent : null;
          const m = await import('/js/messages.js');
          const n = await import('/js/neblink.js');
          const peer = (n.getNeblinkState().peers || []).find(p => String(p.deviceId) === id);
          return { shown, defaultForm: peer ? m.deviceLabel(peer) : null };
        }, UNNAMED);
        await shot(page, theme, locale, '04-contacts-device-row');
        note(`  [${theme}/${locale}] 读数（联系人面板设备行，同源默认形态）rowName=${JSON.stringify(contactRead.shown)} deviceLabel(peer)=${JSON.stringify(contactRead.defaultForm)}`);
        ok('U3f', theme, locale, '联系人面板设备行 = 同源单点默认形态（面板面零行为差）',
          !!contactRead.shown && contactRead.shown === contactRead.defaultForm,
          `rowName=${JSON.stringify(contactRead.shown)} default=${JSON.stringify(contactRead.defaultForm)}`);

        // ── 护栏：零新增 pageerror ────────────────────────────────
        ok('GUARD-pageerror', theme, locale, '零 pageerror', pageErrors.length === 0, `errors=${pageErrors.slice(0, 2).join(' | ')}`);
      } finally {
        if (page) await page.close().catch(() => {});
        await ctx.close().catch(() => {});
      }
    }
  }
} finally {
  await browser.close().catch(() => {});
  server.close();
}

// ── 判定：BEFORE 树 ⇒ 红锚必须红、其余必须绿；AFTER 树 ⇒ 全绿 ────────
const redRes = results.filter(r => RED_ANCHORS.has(r.id));
const guardRes = results.filter(r => !RED_ANCHORS.has(r.id));
const redAllRed = redRes.length > 0 && redRes.every(r => !r.pass);
const guardsAllGreen = guardRes.every(r => r.pass);
const allGreen = results.every(r => r.pass);

note('');
note(`# 汇总 MODE=${MODE}  断言 ${results.length} 条：PASS=${results.filter(r => r.pass).length} FAIL=${results.filter(r => !r.pass).length}`);
note(`# 红锚 ${redRes.length} 条（U3a × 4 轮）：${redAllRed ? '全部红' : '存在绿'}；护栏 ${guardRes.length} 条：${guardsAllGreen ? '全绿' : '存在红'}`);
if (MODE === 'BEFORE') {
  exitCode = (redAllRed && guardsAllGreen) ? 0 : 1;
  note(`# BEFORE 判红：${exitCode === 0 ? 'OK（红锚逐轮复现缺陷 + 护栏未误伤）' : 'NG'}`);
} else {
  exitCode = allGreen ? 0 : 1;
  note(`# AFTER 判绿：${exitCode === 0 ? 'OK（全绿）' : 'NG（存在红）'}`);
}
try {
  await mkdir(SHOTS, { recursive: true });
  await writeFile(join(SHOTS, `probe-log-${MODE}.txt`), readings.join('\n') + '\n');
  console.log(`\n# 日志落盘：${join(SHOTS, `probe-log-${MODE}.txt`)}`);
} catch { /* 证据落盘不是判据 */ }
process.exit(exitCode);
