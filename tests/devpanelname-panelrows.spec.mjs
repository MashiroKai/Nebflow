// devpanelname-panelrows.spec.mjs — U3 扩展小批（作者 2026-09-15 22:27 卡答
//   「那兜底就用未命名设备呗」）：
//   「**面板行（消息面板行 / 联系人面板行 / 引用载荷 `friendName`）的无名
//    （无名称且无描述）设备显示改用「既有占位键」**（与窗头同键，**零新键**），与窗头
//    一致 —— **界面上任何位置不再裸显设备码**。」
//
// 真渲染验收探针：**同一支探针跑两棵树**（判红纪律：「改前不可满足」）。
//   · `DEVNAMEPANEL_WEB_ROOT` 未设 ⇒ 跑本仓 `src/main/resources/web`（**改后树** AFTER）
//   · `DEVNAMEPANEL_WEB_ROOT=/tmp/nb-devpanelname/before/src/main/resources/web`
//     （`git archive 8ef0557f0… src/main/resources/web` 导出的**改前树**）⇒
//     三条红锚 `U3Pa/U3Pb/U3Pc`（+ 头像锚 `U3Pd`）**必须红** ⇒ 那就是「缺陷在真渲染里
//     可复现」的原文证据。
//
// 覆盖（三处 = 本批判据指涉面）：
//   U3Pa（红锚） 消息面板设备行名（无名设备）⇒ 占位文案，行**文本层**无 device id 字面
//   U3Pb（红锚） 联系人面板设备行名（无名设备）⇒ 占位文案，行文本层无 device id 字面
//   U3Pc（红锚） 引用载荷 `friendName` ⇒ 主输入框引用块标签「来自 <占位文案>」，
//                块 text/title/aria 三层均无 device id 字面
//   U3Pd（红锚） 消息面板设备行**头像首字**派生自占位文案（非 id 首字 ⇒ 不再裸显设备码）
//   U3Qa（护栏） 无名设备**窗头** = 占位文案（U3 已落行为，**两树逐字相同**）
//   U3Qb（护栏） 有名设备（`deviceName='Phone'`）三处 + 窗头 = 「Phone」逐字不变
//   U3Qc（护栏） 有描述设备（`userDescription='书房工作站'`）三处 + 窗头 = 「书房工作站」
//                （描述 > 设备名优先级零回归）
//
// 🔴 判据口径：**文本层**（`textContent` + `title` + `aria-label`）无 device id 字面
//    —— **禁 CSS 遮盖式「看不见」**（本探针不读计算样式做可见性判定）。
//
// 四轮 = 亮/暗双主题 × zh-CN/en（全站主题机制 = `prefers-color-scheme` 媒体查询）。
// 自包含：静态服务器随机隔离端口 + route 拦截 + `routeWebSocket` 桩；finally 必关。
// Run:
//   node tests/devpanelname-panelrows.spec.mjs
//   DEVNAMEPANEL_WEB_ROOT=/tmp/nb-devpanelname/before/src/main/resources/web node tests/devpanelname-panelrows.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.DEVNAMEPANEL_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.DEVNAMEPANEL_WEB_ROOT ? 'BEFORE' : 'AFTER';
const SHOTS = process.env.DEVNAMEPANEL_SHOTS || join('/tmp', 'nb-devpanelname', MODE);

// 无名设备（判据主体）、有名设备、有描述设备（双向读数的另一半）。
const UNNAMED = 'dev-unnamed-9c41';
const NAMED = 'dev-named-7b22';
const NAMED_NAME = 'Phone';
const DESCRIBED = 'dev-desc-5a10';
const DESCRIBED_DESC = '书房工作站';
// 🔴 红锚：改前树必须红的那几条断言 id（护栏不得漏进这个集合）。
const RED_ANCHORS = new Set(['U3Pa', 'U3Pb', 'U3Pc', 'U3Pd']);
const PLACEHOLDER = { 'zh-CN': '未命名设备', en: 'Unnamed device' };
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SELF = 'dev-self';
const SESSION_ID = 'sess-1';

const results = [];
const readings = [];
function ok(id, theme, locale, name, cond, extra = '') {
  results.push({ id, theme, locale, pass: !!cond, name, extra });
  readings.push(`  ${cond ? 'PASS' : 'FAIL'}  [${id}/${theme}/${locale}] ${name}${extra ? '  — ' + extra : ''}`);
  console.log(readings[readings.length - 1]);
}
function note(line) { readings.push(line); console.log(line); }

const sleep = ms => new Promise(r => setTimeout(r, ms));

/** 会话行（三台设备都成窗）+ 一条**不含 device id 字面**的消息体
 *  （体字面若带 id 会污染「行文本层无 id」判据 ⇒ 夹具必须干净）。 */
const conversations = () => [UNNAMED, NAMED, DESCRIBED].map((id, i) => ({
  conversationId: 'dev:' + id, kind: 'device', deviceId: id, unreadCount: 0,
  lastMessage: { id: 100 + i, senderId: 'peer', kind: 'text', body: 'hello bubble', createdAt: 1757900000, senderDeviceId: id },
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
            // 🔴 判据主体：既无 deviceName 也无 userDescription。
            { deviceId: UNNAMED, platform: 'windows', online: true, directOnline: true },
            { deviceId: NAMED, deviceName: NAMED_NAME, platform: 'macos', online: true, directOnline: true },
            { deviceId: DESCRIBED, deviceName: 'Laptop', userDescription: DESCRIBED_DESC, platform: 'linux', online: true, directOnline: true },
          ],
        },
      });
    }
    if (p === '/api/conversations' && m === 'GET') return r.fulfill({ json: conversations() });
    if (p.endsWith('/messages') && m === 'GET') {
      const convId = p.slice('/api/conversations/'.length, -'/messages'.length);
      const dev = convId.startsWith('dev:') ? convId.slice(4) : '';
      return r.fulfill({ json: [{ id: 'msg-1', senderId: 'peer', kind: 'text', body: 'hello bubble', createdAt: 1757900000, senderDeviceId: dev }] });
    }
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
    // 🔴 活跃会话视图必须在场：引用载荷的落点是**主输入框**（`appendRefToActiveView`
    //   找不到 activeView 会退化成引导 toast ⇒ 引用块永远不入框、判据不可达）。
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SESSION_ID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }],
      activeId: SESSION_ID, folders: [],
    }));
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

/** 消息面板设备行读数（行名元素文本 + 头像首字 + 行文本层）。 */
function readMsgPanelRow(page, id) {
  return page.evaluate((devId) => {
    const row = document.querySelector(`#fm-conversations .fm-conv-row[data-conversation-id="dev:${devId}"]`);
    if (!row) return null;
    const nameEl = row.querySelector('.fm-row-name');
    const av = row.querySelector('.fm-avatar');
    return {
      name: nameEl ? nameEl.textContent : null,
      avatar: av ? av.textContent : null,
      text: row.textContent || '',
    };
  }, id);
}

/** 联系人面板设备行读数。 */
function readContactsRow(page, id) {
  return page.evaluate((devId) => {
    const row = document.querySelector(`[data-device-section="1"] .fm-device-row[data-device-id="${devId}"]`);
    if (!row) return null;
    const nameEl = row.querySelector('.neblink-peer-name');
    return {
      name: nameEl ? nameEl.textContent : null,
      text: row.textContent || '',
      title: row.getAttribute('title') || '',
      aria: row.getAttribute('aria-label') || '',
    };
  }, id);
}

/** 开设备窗 → 读窗头（文本 + 全文 + aria-label）→ **窗开着**截图 → 转发一条 → 读引用块 → 关窗。
 *  引用块读数取**最后一个** `.att-ref-fm`（累计入框，最新一件即本次转发产物）。 */
async function openForwardAndRead(page, deviceId, shotsFor) {
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="dev:${deviceId}"]`);
  await page.waitForSelector('.fm-modal', { timeout: 10000 });
  await sleep(500);
  const head = await page.evaluate(() => {
    const modal = document.querySelector('.fm-modal');
    return {
      name: (modal.querySelector('.fm-modal-name') || {}).textContent || null,
      headerText: (modal.querySelector('.fm-modal-header') || {}).textContent || null,
      aria: modal.getAttribute('aria-label'),
    };
  });
  if (shotsFor) await shot(page, shotsFor.theme, shotsFor.locale, shotsFor.name);

  // 转发腿：hover 气泡 → 第 2 颗 `.fm-msg-act`（「转发给 agent」，与右键菜单同一 handler）。
  const bubble = page.locator('.fm-modal .fm-msg').first();
  await bubble.hover();
  await sleep(200);
  const acts = bubble.locator('.fm-msg-act');
  let forwarded = false;
  if (await acts.count() >= 2) { await acts.nth(1).click({ timeout: 8000 }); forwarded = true; }
  await sleep(500);
  const chip = await page.evaluate(() => {
    const all = [...document.querySelectorAll('#attachment-preview .att-ref-fm')];
    const last = all[all.length - 1];
    if (!last) return null;
    return {
      count: all.length,
      label: (last.querySelector('.att-ref-fm-name') || {}).textContent || null,
      text: last.textContent || '',
      title: last.getAttribute('title') || '',
      aria: last.getAttribute('aria-label') || '',
    };
  });

  await page.keyboard.press('Escape');
  await page.waitForSelector('.fm-modal', { state: 'detached', timeout: 10000 }).catch(() => {});
  await sleep(250);
  return { head, chip, forwarded };
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
        // 设备档案（peers）= 设备名唯一数据源，必须先落 state。
        await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
        await sleep(300);
        const effLocale = await page.evaluate(async l => {
          const i = await import('/js/i18n.js');
          i.setLocale(l);
          return i.getLocale();
        }, locale);
        note(`  [theme=${theme}/${locale}] effectiveLocale=${effLocale}`);
        if (effLocale !== locale) { ok('GUARD-locale', theme, locale, '探针语言生效', false, `got=${effLocale}`); continue; }

        const themeProbe = await page.evaluate(() => ({
          scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
          bg: getComputedStyle(document.body).backgroundColor,
        }));
        note(`  [theme=${theme}/${locale}] prefers-color-scheme=${themeProbe.scheme} body.bg=${themeProbe.bg}`);
        if (themeProbe.scheme !== theme) { ok('GUARD-theme', theme, locale, '探针主题生效', false, `got=${themeProbe.scheme}`); continue; }

        // 消息面板上场。
        const active = await page.evaluate(() => !!document.getElementById('panel-messages')?.classList.contains('active'));
        if (!active) await page.click('#messages-btn');
        await sleep(700);
        const rows = await page.evaluate(() => document.querySelectorAll('#fm-conversations .fm-conv-row[data-device="1"]').length);
        ok('GUARD-device-rows', theme, locale, '消息面板三台设备行在场（数据面就绪）', rows === 3, `rows=${rows}`);
        if (rows !== 3) continue;

        const ph = PLACEHOLDER[locale];

        // ══ U3Pa（红锚）：消息面板设备行名（无名设备）═══════════════
        const mpU = await readMsgPanelRow(page, UNNAMED);
        const mpUNameOk = !!mpU && mpU.name === ph;
        const mpUNoId = !!mpU && !mpU.text.includes(UNNAMED);
        // U3Pd（红锚）：头像首字必须派生自占位文案（id 首字「D」= 裸显设备码的同族）。
        const avatarOk = !!mpU && !!mpU.avatar && ph.includes(mpU.avatar);
        await shot(page, theme, locale, '01-unnamed-msgpanel-row');
        note(`  [${theme}/${locale}] 消息面板行（无名）读数 name=${JSON.stringify(mpU && mpU.name)} avatar=${JSON.stringify(mpU && mpU.avatar)} rowText=${JSON.stringify(mpU && mpU.text)}`);
        ok('U3Pa', theme, locale, `消息面板设备行名 = 占位文案「${ph}」且行文本层无 device id`,
          mpUNameOk && mpUNoId, `name=${JSON.stringify(mpU && mpU.name)} hasId=${!!mpU && mpU.text.includes(UNNAMED)}`);
        ok('U3Pd', theme, locale, `消息面板设备行头像首字派生自占位文案（非 id 首字「D」）`,
          avatarOk, `avatar=${JSON.stringify(mpU && mpU.avatar)} inPlaceholder=${avatarOk}`);

        // ══ 三台设备逐台开窗（窗头读数 + 引用块读数）═══════════════
        const win = {};
        const chipOf = {};
        for (const [key, devId, shotName] of [
          ['unnamed', UNNAMED, '02-unnamed-window'],
          ['named', NAMED, '03-named-window'],
          ['described', DESCRIBED, '04-described-window'],
        ]) {
          const r = await openForwardAndRead(page, devId, { theme, locale, name: shotName });
          win[key] = r.head;
          chipOf[key] = { chip: r.chip, forwarded: r.forwarded };
          note(`  [${theme}/${locale}] ${key} 窗头 name=${JSON.stringify(r.head.name)} headerHasId=${(r.head.headerText || '').includes(devId)} ariaHasId=${(r.head.aria || '').includes(devId)}`
            + ` | 引用块 forwarded=${r.forwarded} label=${JSON.stringify(r.chip && r.chip.label)}`)
          ;
        }

        // ══ U3Pc（红锚）：引用载荷 friendName（无名设备）══════════
        const chipU = chipOf.unnamed.chip;
        const chipULabelOk = !!chipU && chipU.label === `${locale === 'en' ? 'From' : '来自'} ${ph}`;
        const chipUNoId = !!chipU && ![chipU.text, chipU.title, chipU.aria].some(s => (s || '').includes(UNNAMED));
        // 引用块入框后**窗已关**，补拍输入框引用块（截图在关窗后 ⇒ 面板行不入镜）。
        await shot(page, theme, locale, '05-unnamed-refchip');
        ok('U3Pc', theme, locale, `引用载荷 friendName ⇒ 引用块标签「${locale === 'en' ? 'From' : '来自'} ${ph}」，text/title/aria 三层无 device id`,
          chipULabelOk && chipUNoId,
          `forwarded=${chipOf.unnamed.forwarded} label=${JSON.stringify(chipU && chipU.label)} hasId=${!chipUNoId} chips=${chipU && chipU.count}`);

        // ══ U3Qa（护栏）：无名设备窗头逐字不变（U3 已落行为）═══════
        ok('U3Qa', theme, locale, `无名设备窗头 = 占位文案「${ph}」且 header/aria 无 device id（两树逐字相同）`,
          win.unnamed.name === ph
          && !(win.unnamed.headerText || '').includes(UNNAMED)
          && !(win.unnamed.aria || '').includes(UNNAMED),
          `name=${JSON.stringify(win.unnamed.name)} header=${JSON.stringify(win.unnamed.headerText)} aria=${JSON.stringify(win.unnamed.aria)}`);

        // ══ U3Qb / U3Qc（护栏）：有名 / 有描述设备三处 + 窗头逐字不变 ══
        const expectNamed = `From ${NAMED_NAME}`;
        const expectDescr = `From ${DESCRIBED_DESC}`;
        const fromPrefix = locale === 'en' ? 'From' : '来自';
        const namedOk = win.named.name === NAMED_NAME
          && chipOf.named.chip && chipOf.named.chip.label === `${fromPrefix} ${NAMED_NAME}`;
        const descrOk = win.described.name === DESCRIBED_DESC
          && chipOf.described.chip && chipOf.described.chip.label === `${fromPrefix} ${DESCRIBED_DESC}`;
        ok('U3Qb', theme, locale, `有名设备窗头 + 引用块 = 「${NAMED_NAME}」逐字不变（双向读数）`,
          !!namedOk, `winTitle=${JSON.stringify(win.named.name)} chip=${JSON.stringify(chipOf.named.chip && chipOf.named.chip.label)} expectChip=${JSON.stringify(`${fromPrefix} ${NAMED_NAME}`)} (${expectNamed})`);
        ok('U3Qc', theme, locale, `有描述设备窗头 + 引用块 = 「${DESCRIBED_DESC}」（描述 > 设备名）逐字不变`,
          !!descrOk, `winTitle=${JSON.stringify(win.described.name)} chip=${JSON.stringify(chipOf.described.chip && chipOf.described.chip.label)} expectChip=${JSON.stringify(`${fromPrefix} ${DESCRIBED_DESC}`)} (${expectDescr})`);

        // ══ U3Pb（红锚）：联系人面板设备行（无名设备）═══════════════
        // 此刻三窗全关 ⇒ 可切面板（overlay 不再挡点击）。
        const cActive = await page.evaluate(() => !!document.getElementById('panel-contacts')?.classList.contains('active'));
        if (!cActive) await page.click('#contacts-btn');
        await sleep(700);
        if (await page.locator('[data-device-section="1"]').count() === 0
          && await page.locator('[data-devices-entry="1"]').count() === 1) {
          await page.click('[data-devices-entry="1"]');
          await sleep(600);
        }
        const ctU = await readContactsRow(page, UNNAMED);
        await shot(page, theme, locale, '06-unnamed-contacts-row');
        note(`  [${theme}/${locale}] 联系人面板行（无名）读数 name=${JSON.stringify(ctU && ctU.name)} rowText=${JSON.stringify(ctU && ctU.text)}`);
        ok('U3Pb', theme, locale, `联系人面板设备行名 = 占位文案「${ph}」且行文本层无 device id`,
          !!ctU && ctU.name === ph && !ctU.text.includes(UNNAMED) && !ctU.title.includes(UNNAMED) && !ctU.aria.includes(UNNAMED),
          `name=${JSON.stringify(ctU && ctU.name)} hasId=${!!ctU && ctU.text.includes(UNNAMED)}`);

        // 双向钉（联系人行）：有名 / 有描述行名逐字不变。
        const ctN = await readContactsRow(page, NAMED);
        const ctD = await readContactsRow(page, DESCRIBED);
        note(`  [${theme}/${locale}] 联系人面板行（有名/有描述）读数 name=${JSON.stringify(ctN && ctN.name)} / ${JSON.stringify(ctD && ctD.name)}`);
        ok('U3Qd', theme, locale, '联系人面板行：有名 / 有描述设备行名逐字不变',
          !!ctN && ctN.name === NAMED_NAME && !!ctD && ctD.name === DESCRIBED_DESC,
          `named=${JSON.stringify(ctN && ctN.name)} described=${JSON.stringify(ctD && ctD.name)}`);

        // 双向钉（消息面板行）：有名 / 有描述行名逐字不变。
        const mpN = await readMsgPanelRow(page, NAMED);
        const mpD = await readMsgPanelRow(page, DESCRIBED);
        note(`  [${theme}/${locale}] 消息面板行（有名/有描述）读数 name=${JSON.stringify(mpN && mpN.name)} / ${JSON.stringify(mpD && mpD.name)}`);
        ok('U3Qe', theme, locale, '消息面板行：有名 / 有描述设备行名逐字不变',
          !!mpN && mpN.name === NAMED_NAME && !!mpD && mpD.name === DESCRIBED_DESC,
          `named=${JSON.stringify(mpN && mpN.name)} described=${JSON.stringify(mpD && mpD.name)}`);

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

// ── 判定：BEFORE 树 ⇒ 红锚必须红、护栏必须绿；AFTER 树 ⇒ 全绿 ────────
const redRes = results.filter(r => RED_ANCHORS.has(r.id));
const guardRes = results.filter(r => !RED_ANCHORS.has(r.id));
const redAllRed = redRes.length > 0 && redRes.every(r => !r.pass);
const guardsAllGreen = guardRes.every(r => r.pass);
const allGreen = results.every(r => r.pass);

note('');
note(`# 汇总 MODE=${MODE}  断言 ${results.length} 条：PASS=${results.filter(r => r.pass).length} FAIL=${results.filter(r => !r.pass).length}`);
note(`# 红锚 ${redRes.length} 条（U3Pa/Pb/Pc/Pd × 4 轮）：${redAllRed ? '全部红' : '存在绿'}；护栏 ${guardRes.length} 条：${guardsAllGreen ? '全绿' : '存在红'}`);
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
