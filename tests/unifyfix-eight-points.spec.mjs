// unifyfix-eight-points.spec.mjs — 设备/好友统一返工（作者 2026-09-15 八点指正）
// 真渲染验收探针：**同一支探针跑两棵树**（判红纪律：「改前不可满足」）。
//
//   · `UNIFYFIX_WEB_ROOT` 未设 ⇒ 跑本仓 `src/main/resources/web`（**改后树**）
//     ⇒ 八点全部绿（含「改前红锚」逐条翻绿）。
//   · `UNIFYFIX_WEB_ROOT=<改前树 web/>`（`git archive HEAD` 出来的基线）⇒
//     红锚集（RED_ANCHORS）**逐条必须红** ⇒ 那就是「改前行为在真渲染里可复现」
//     的原文证据（每条红锚的 id + 读数逐行打印）。
//
// 亮暗双主题：`prefers-color-scheme: light|dark` 各跑一轮（全站主题机制 = 媒体查询，
// 无 class/localStorage 开关）；两轮各留截图 + 计算样式读数（证明两轮确实是两个主题）。
//
// 覆盖（作者八点，逐点对应）：
//   ① setting → contacts 入口搬家：设置账号段无设备列表/无旧设备窗入口；两键保留
//   ② 设备入口 = 「新的朋友」同族**点进展开**（收起态无展开体 / 点开有）
//   ③ 设备行样式沿用设置里的设备行（`.neblink-peer` 家族 + 平台 SVG）
//   ④ 设置页只留 切换账号 + 退出登录
//   ⑤(a) 对话框不显示设备码 (b) 零留存期文案 (c) 有设备描述编辑处（真写路径往返）
//   ⑥ 设备「通信过」才进消息面板（无证据不出现 / 有证据出现 —— 双向读数）
//   ⑦ 搜索未命中卡可收起（点外部 / Escape；点块内不收起；无超时自撤）
//   ⑧ 设备会话零好友关系文案（好友面照常在）
//
// 自包含：静态服务器随机隔离端口 + route 拦截；finally 必关（进程清理纪律）。
// Run:
//   node tests/unifyfix-eight-points.spec.mjs
//   UNIFYFIX_WEB_ROOT=/tmp/nb-unifyfix/before/src/main/resources/web node tests/unifyfix-eight-points.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.UNIFYFIX_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.UNIFYFIX_WEB_ROOT ? 'BEFORE' : 'AFTER';
const SHOTS = process.env.UNIFYFIX_SHOTS || join('/tmp', 'nb-unifyfix', MODE);

// 🔴 红锚集 = 「改前行为」必须在 BEFORE 树**真渲染里复现**的那批断言 id。
// 其余 id 是**护栏**（两棵树都必须绿：不许误伤好友面 / 不许引入超时 / 不许新增 pageerror）。
const RED_ANCHORS = new Set([
  'R1a', 'R1b', 'R1c', 'R1d',     // ① 设置页设备面（列表 / 行 / 旧窗入口 / 更新键组）还在
  'R2a', 'R2b', 'R2c',            // ② 设备段常开平铺（无点进展开）
  'R3a', 'R3b',                   // ③ 设备行走的是好友行样式
  'R5a', 'R5b', 'R5c', 'R5c2',    // ⑤a 设备码可见 / ⑤b 留存文案在场 / ⑤c 无描述编辑面
  'R6a', 'R6b',                   // ⑥ 未通信设备直接出现在消息面板
  'R7a', 'R7b',                   // ⑦ 未命中卡不可收起
  'R8a',                          // ⑧ 设备会话显示好友关系文案
]);

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SELF = 'dev-self';
const PEER = 'dev-peer';
const PEER_NAME = 'Phone';
const ZH_NOW_FRIENDS = '你们已成为好友';
const ZH_NO_MESSAGES = '暂无消息';
const RETENTION_EN = 'Cloud keeps messages for 7 days; this device keeps them permanently.';
const RETENTION_ZH = '云端保留 7 天，本机永久保存。';

let currentTheme = 'light';
let failures = 0;
const results = [];

function ok(id, name, cond, extra = '') {
  results.push({ id, theme: currentTheme, pass: !!cond, name, extra });
  console.log(`  ${cond ? 'PASS' : 'FAIL'}  [${id}/${currentTheme}] ${name}${extra ? '  — ' + extra : ''}`);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 桩（服务端真值形状；一个 handler 内按 path 分派，避免 route 顺序歧义）──
const conversationsOf = opts => {
  const rows = [];
  if (opts.friendRow) {
    rows.push({ conversationId: 'c-lin', friend: { userId: 'u1', username: 'lin', display_name: 'Lin', avatar: null }, lastMessage: null, unreadCount: 0 });
  }
  if (opts.deviceRow) {
    rows.push({ conversationId: 'dev:' + PEER, kind: 'device', deviceId: PEER, unreadCount: 0, lastMessage: null });
  }
  return rows;
};

function installRoutes(page, cap, opts = {}) {
  return page.route('**/api/**', async r => {
    const url = new URL(r.request().url());
    const p = decodeURIComponent(url.pathname);
    const m = r.request().method();
    if (p === '/api/neblink/status') {
      return r.fulfill({
        json: {
          loggedIn: true,
          device: { id: SELF, name: 'Mac', platform: 'macos', userDescription: '' },
          peers: [{ deviceId: PEER, deviceName: PEER_NAME, platform: 'windows', online: true, directOnline: true }],
        },
      });
    }
    if (p === '/api/neblink/peer-description' && m === 'PUT') {
      cap.descPuts.push({ body: r.request().postData() });
      return r.fulfill({ json: { ok: true } });
    }
    if (p === '/api/conversations' && m === 'GET') return r.fulfill({ json: conversationsOf(opts) });
    if (p === '/api/users/search') return r.fulfill({ json: { found: false } });
    if (p === '/api/friends') {
      return r.fulfill({ json: { friends: [{ userId: 'u1', username: 'lin', displayName: 'Lin', name: 'Lin', remark: null }], incoming: [], outgoing: [] } });
    }
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

async function ensurePanel(page, btnId, panelId) {
  const active = await page.evaluate(id => !!document.getElementById(id)?.classList.contains('active'), panelId);
  if (!active) await page.click(btnId);
  await sleep(350);
}

async function shot(page, theme, name) {
  try {
    await mkdir(join(SHOTS, theme), { recursive: true });
    await page.screenshot({ path: join(SHOTS, theme, name + '.png') });
  } catch { /* 证据截图不是判据 */ }
}

const browser = await chromium.launch();
try {
  for (const theme of ['light', 'dark']) {
    currentTheme = theme;
    const cap = { descPuts: [] };
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
    let page = null;
    try {
      await ctx.addInitScript(() => {
        localStorage.setItem('nebflow_token', 't');
        localStorage.setItem('neblink_token', 't');
        localStorage.setItem('nebflow_locale', 'zh-CN');
        localStorage.setItem('neblink_locale', 'zh-CN');
      });
      page = await ctx.newPage();
      const pageErrors = [];
      page.on('pageerror', e => pageErrors.push(e.message));
      await installRoutes(page, cap, {});
      await installWs(page);

      await page.goto(BASE + '/index.html');
      await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
      await sleep(1200);
      // 设备档案（peers）必须先落 state（设备段的唯一数据源）。
      await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
      await sleep(400);

      // 双主题读数（证明两轮确实是两个主题：同一元素的计算样式不同）
      const themeProbe = await page.evaluate(() => ({
        scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
        bg: getComputedStyle(document.body).backgroundColor,
      }));
      console.log(`  [theme=${theme}] prefers-color-scheme=${themeProbe.scheme} body.bg=${themeProbe.bg}`);

      // ══ ① ④：设置页设备面摘除 + 两键保留 ═══════════════════════════
      await page.click('#settings-btn');
      await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
      await sleep(500);
      await shot(page, theme, '01-settings');
      const settings = await page.evaluate(() => {
        const content = document.getElementById('settings-content');
        return {
          peersList: content.querySelectorAll('.neblink-peers-list').length,
          peerRows: content.querySelectorAll('.neblink-peer').length,
          clickable: content.querySelectorAll('.dropbox-clickable').length,
          switchBtn: !!content.querySelector('#neblink-switch-btn'),
          logoutBtn: !!content.querySelector('#neblink-logout-btn'),
          accountBlock: !!content.querySelector('.neblink-logged-in'),
          peerUpdateBtns: content.querySelectorAll('.neblink-peer-update-btn, .neblink-ch-btn, .neblink-ch-cancel').length,
        };
      });
      ok('R1a', '设置账号段无设备列表容器（.neblink-peers-list 0 命中）', settings.peersList === 0, `count=${settings.peersList}`);
      ok('R1b', '设置账号段无设备行（.neblink-peer 0 命中）', settings.peerRows === 0, `count=${settings.peerRows}`);
      ok('R1c', '设置页无旧设备窗入口（.dropbox-clickable 0 命中）', settings.clickable === 0, `count=${settings.clickable}`);
      ok('R1d', '设置页无「更新/频道」设备键组残留', settings.peerUpdateBtns === 0, `count=${settings.peerUpdateBtns}`);
      ok('G1', '设置账号段保留 切换账号 + 退出登录 两键', settings.switchBtn && settings.logoutBtn,
        `switch=${settings.switchBtn} logout=${settings.logoutBtn}`);
      ok('G1b', '设置账号块仍渲染（未把整块删掉）', settings.accountBlock === true);
      await page.keyboard.press('Escape');
      await sleep(300);

      // ══ ② ③：联系人面板设备入口（点进展开）+ 行样式沿用设置 ═════════
      await ensurePanel(page, '#contacts-btn', 'panel-contacts');
      await shot(page, theme, '02-contacts-collapsed');
      const collapsed = await page.evaluate(() => {
        const entry = document.querySelector('[data-devices-entry="1"]');
        return {
          entryExists: !!entry,
          entryIsNfFamily: !!entry && entry.classList.contains('fm-nf-entry'),
          entryClasses: entry ? entry.className : null,
          entryChevron: !!entry && !!entry.querySelector('.fm-nf-chevron svg, .fm-nf-chevron i'),
          entryHasLabel: !!entry && !!entry.querySelector('.fm-nf-label'),
          entryAria: entry ? entry.getAttribute('aria-expanded') : null,
          bodyBeforeClick: document.querySelectorAll('[data-device-section="1"]').length,
          nfEntry: document.querySelectorAll('#fm-contacts-body .fm-nf-entry').length,
        };
      });
      ok('R2a', '设备入口存在且与「新的朋友」同族（.fm-nf-entry + chevron + label）',
        collapsed.entryExists && collapsed.entryIsNfFamily && collapsed.entryChevron && collapsed.entryHasLabel,
        `classes="${collapsed.entryClasses}" chevron=${collapsed.entryChevron}`);
      ok('G2', '「新的朋友」入口未被误伤（仍渲染）', collapsed.nfEntry >= 1, `fm-nf-entry count=${collapsed.nfEntry}`);
      ok('R2b', '收起态：展开体不在 DOM（未点击 ⇒ 无 [data-device-section]）',
        collapsed.bodyBeforeClick === 0, `count=${collapsed.bodyBeforeClick}`);

      // 改前树没有该入口（R2a 红）⇒ 不点，让 R2c 照红（探针不得因缺件崩掉）。
      if (collapsed.entryExists) {
        await page.click('[data-devices-entry="1"]');
        await sleep(500);
      }
      await shot(page, theme, '03-contacts-expanded');
      const expanded = await page.evaluate(() => {
        const body = document.querySelector('[data-device-section="1"]');
        const row = body && body.querySelector('.neblink-peer.fm-device-row');
        const entry = document.querySelector('[data-devices-entry="1"]');
        return {
          bodyCount: document.querySelectorAll('[data-device-section="1"]').length,
          deviceCount: body ? body.dataset.deviceCount : null,
          rowCount: body ? body.querySelectorAll('.fm-device-row').length : 0,
          rowClasses: row ? row.className : null,
          rowIconSvg: !!(row && row.querySelector('.neblink-peer-icon svg')),
          rowName: row ? (row.querySelector('.neblink-peer-name') || {}).textContent : null,
          rowStatus: row ? (row.querySelector('.neblink-peer-status') || {}).textContent : null,
          rowPresence: row ? (row.querySelector('.neblink-presence') || {}).textContent : null,
          rowAvatar: row ? row.querySelectorAll('.fm-avatar').length : -1,
          rowHasDeviceIdText: row ? row.textContent.includes('dev-peer') : null,
          aria: entry ? entry.getAttribute('aria-expanded') : null,
        };
      });
      ok('R2c', '点开（aria-expanded=true）⇒ 展开体入 DOM 且含 1 台设备行（deviceCount=1）',
        expanded.bodyCount === 1 && expanded.deviceCount === '1' && expanded.rowCount === 1 && expanded.aria === 'true',
        `body=${expanded.bodyCount} deviceCount=${expanded.deviceCount} rows=${expanded.rowCount} aria=${expanded.aria}`);
      ok('R3a', '设备行类名 = 设置设备行家族（.neblink-peer，非 fm-friend-row）',
        !!expanded.rowClasses && expanded.rowClasses.includes('neblink-peer') && !expanded.rowClasses.includes('fm-friend-row'),
        `class="${expanded.rowClasses}"`);
      ok('R3b', '设备行 = 平台 SVG 图标（.neblink-peer-icon svg）+ 设置口径状态文本，且无好友圆头像(.fm-avatar)',
        expanded.rowIconSvg === true && expanded.rowAvatar === 0 && expanded.rowStatus === 'Windows' && expanded.rowPresence === '在线',
        `icon=${expanded.rowIconSvg} avatar=${expanded.rowAvatar} status=${JSON.stringify(expanded.rowStatus)} presence=${JSON.stringify(expanded.rowPresence)}`);

      // ══ ⑥(a)：未通信设备**不得**出现在消息面板 ══════════════════════
      await ensurePanel(page, '#messages-btn', 'panel-messages');
      const msgPanelBefore = await page.evaluate(() => document.querySelectorAll('#fm-conversations .fm-conv-row[data-device="1"]').length);
      await shot(page, theme, '04-messages-no-traffic');
      ok('R6a', '无通信记录 ⇒ 消息面板零设备行（peers 在档案里但不成窗）',
        msgPanelBefore === 0, `deviceRows=${msgPanelBefore}`);

      // ══ ⑥(b)+⑤：从联系人面板点设备 ⇒ 开窗但**不**新增消息面板行 ════
      // ⚠ 对话框开着时 overlay 会挡住一切点击 ⇒ 面板行计数**直接读 DOM**
      //   （`#fm-conversations` 是静态容器，面板非激活态照常渲染）。
      await ensurePanel(page, '#contacts-btn', 'panel-contacts');
      // 收起态（②之后入口默认收起）⇒ 需要时再展开，保证行可点（改前树常开，无需展开）。
      if (await page.locator('[data-device-section="1"]').count() === 0
        && await page.locator('[data-devices-entry="1"]').count() === 1) {
        await page.click('[data-devices-entry="1"]');
        await sleep(400);
      }
      await page.click('.fm-device-row');
      await page.waitForSelector('.fm-modal', { timeout: 10000 });
      await sleep(700);
      const dialogOpen = await page.locator('.fm-modal').count() === 1;
      const msgPanelAfter = await page.evaluate(() => document.querySelectorAll('#fm-conversations .fm-conv-row[data-device="1"]').length);
      ok('R6b', '点开设备会话后消息面板仍零设备行（可开窗 ≠ 直接出现在面板）',
        msgPanelAfter === 0 && dialogOpen, `deviceRows=${msgPanelAfter} dialogOpen=${dialogOpen}`);

      // ══ ⑤：设备对话框（设备码 / 留存文案 / 描述编辑）══ 复用已开的这一窗 ══
      await shot(page, theme, '05-device-dialog');
      const dlg = await page.evaluate(() => {
        const modal = document.querySelector('.fm-modal');
        const idEl = modal.querySelector('.fm-modal-id');
        return {
          modalId: idEl ? idEl.textContent : null,
          headerText: modal.querySelector('.fm-modal-header').textContent,
          noteCount: modal.querySelectorAll('.fm-device-note').length,
          descRow: modal.querySelectorAll('.fm-device-desc').length,
          descText: (modal.querySelector('.fm-device-desc-text') || {}).textContent,
          docHasRetention: document.body.innerText.includes('云端保留') || document.body.innerText.includes('Cloud keeps messages'),
          title: (modal.querySelector('.fm-modal-name') || {}).textContent,
        };
      });
      ok('R5a', '对话框不显示设备码（窗头副行 = 平台文本，header 无 deviceId）',
        dlg.modalId === 'Windows' && !dlg.headerText.includes(PEER),
        `modalId=${JSON.stringify(dlg.modalId)} headerHasId=${dlg.headerText.includes(PEER)}`);
      ok('R5b', '对话框零留存期文案（.fm-device-note 0 命中 + 全文无该句）',
        dlg.noteCount === 0 && dlg.docHasRetention === false,
        `notes=${dlg.noteCount} docHasRetention=${dlg.docHasRetention}`);
      ok('R5c', '对话框有设备描述编辑处（.fm-device-desc 在场）',
        dlg.descRow === 1, `count=${dlg.descRow} text=${JSON.stringify(dlg.descText)}`);

      // ⑤c 真往返：点描述行 → 输入 → Enter ⇒ PUT /api/neblink/peer-description
      let descRoundTrip = null;
      if (dlg.descRow === 1) {
        await page.click('.fm-device-desc-text');
        await sleep(250);
        const hasInput = await page.locator('.fm-device-desc-input').count() === 1;
        if (hasInput) {
          await page.fill('.fm-device-desc-input', '桌面机 · 编译用');
          await page.keyboard.press('Enter');
          await sleep(600);
          descRoundTrip = await page.evaluate(() => {
            const modal = document.querySelector('.fm-modal');
            return {
              descText: (modal.querySelector('.fm-device-desc-text') || {}).textContent,
              title: (modal.querySelector('.fm-modal-name') || {}).textContent,
            };
          });
        }
        ok('R5c2', '描述编辑往返：PUT 打对端点 + 描述就地生效（名称链 userDescription 生效）',
          cap.descPuts.length === 1
          && JSON.parse(cap.descPuts[0].body || '{}').deviceId === PEER
          && JSON.parse(cap.descPuts[0].body || '{}').userDescription === '桌面机 · 编译用'
          && !!descRoundTrip && descRoundTrip.descText === '桌面机 · 编译用' && descRoundTrip.title === '桌面机 · 编译用',
          `puts=${JSON.stringify(cap.descPuts.map(x => x.body))} after=${JSON.stringify(descRoundTrip)}`);
      } else {
        ok('R5c2', '描述编辑往返：PUT 打对端点 + 描述就地生效（名称链 userDescription 生效）', false,
          'no .fm-device-desc (改前树) ⇒ 无法开编辑器');
      }
      await page.keyboard.press('Escape');
      await sleep(400);
      // 描述落库是桩（不写服务端）⇒ 重新拉状态把名称链复位，免污染后续读数。
      await page.evaluate(async () => {
        const d = (await import('/js/messages.js'));
        void d; const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus();
      });
      await sleep(300);

      // ══ ⑦：搜索未命中卡可收起 ═══════════════════════════════════════
      await ensurePanel(page, '#contacts-btn', 'panel-contacts');
      await page.fill('.fm-search-input', 'nobody-xyz');
      await page.click('.fm-search-btn');
      await page.waitForSelector('.fm-result-card', { timeout: 8000 });
      await sleep(400);
      await shot(page, theme, '06-search-notfound');
      const cardText = await page.locator('.fm-result-card').textContent().catch(() => '');
      ok('G7', '未命中卡出现且含作者引用文案（前置读数）', /未找到该用户/.test(cardText || ''), `text=${JSON.stringify((cardText || '').slice(0, 60))}`);
      // 点块内（搜索输入框）⇒ 不得收起（禁「点搜索框即收起」）
      await page.click('.fm-search-input');
      await sleep(350);
      const afterInside = await page.locator('.fm-result-card').count();
      ok('G7b', '点搜索块内（输入框）不收起', afterInside === 1, `count=${afterInside}`);
      // 点面板内、搜索块外 ⇒ 收起
      await page.click('#panel-contacts .panel-title');
      await sleep(400);
      const afterOutside = await page.locator('.fm-result-card').count();
      ok('R7a', '点面板外任意处 ⇒ 未命中卡收起', afterOutside === 0, `count=${afterOutside}`);
      // Escape 收起（与 ctxthresh 同族成对口径）
      await sleep(1200); // 搜索键有 1s 最小间隔闸（contacts.js `lastSearchAt`）
      await page.fill('.fm-search-input', 'nobody-xyz');
      await page.click('.fm-search-btn');
      await page.waitForSelector('.fm-result-card', { timeout: 8000 });
      await sleep(400);
      await page.keyboard.press('Escape');
      await sleep(400);
      const afterEsc = await page.locator('.fm-result-card').count();
      ok('R7b', 'Escape ⇒ 未命中卡收起（与 ctxthresh 先例同族）', afterEsc === 0, `count=${afterEsc}`);
      // 无超时自撤（禁双标：全站面板族无超时惯例）
      await sleep(1200); // 同 1s 最小间隔闸
      await page.fill('.fm-search-input', 'nobody-xyz');
      await page.click('.fm-search-btn');
      await page.waitForSelector('.fm-result-card', { timeout: 8000 });
      await sleep(4000);
      const afterWait = await page.locator('.fm-result-card').count();
      ok('G7c', '无超时自撤（等 4s 仍在 = 未引入定时器双标）', afterWait === 1, `count=${afterWait}`);

      // ══ ⑥(c) 通信后出现（本地缓存证据 ⇒ 成窗）═══════════════════════
      const seeded = await page.evaluate(async () => {
        const c = await import('/js/fmDropboxCache.js');
        return { n: c.saveDeviceMessages('dev-peer', [{ msgId: 'seed-1', kind: 'text', direction: 'out', ts: Date.now(), text: '与设备通信过' }]) };
      });
      await page.reload();
      await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
      await sleep(1200);
      await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
      await sleep(400);
      await ensurePanel(page, '#messages-btn', 'panel-messages');
      await page.evaluate(async () => { const m = await import('/js/messages.js'); void m; });
      await sleep(600);
      const trafficRow = await page.evaluate(() => {
        const row = document.querySelector('#fm-conversations .fm-conv-row[data-device="1"]');
        return {
          count: document.querySelectorAll('#fm-conversations .fm-conv-row[data-device="1"]').length,
          name: row ? (row.querySelector('.fm-row-name') || {}).textContent : null,
          summary: row ? (row.querySelector('.fm-conv-summary') || {}).textContent : null,
        };
      });
      await shot(page, theme, '07-messages-with-traffic');
      ok('G6c', '双向读数·反向：有通信证据（本地缓存非空）⇒ 设备行出现在消息面板（名字仍走 peers 单点）',
        seeded.n === 1 && trafficRow.count === 1 && trafficRow.name === PEER_NAME,
        `seeded=${seeded.n} rows=${trafficRow.count} name=${JSON.stringify(trafficRow.name)} summary=${JSON.stringify(trafficRow.summary)}`);

      // ══ ⑧：设备会话零好友文案（好友面照常在）════════════════════════
      // 换桩：会话行里出现「设备行（末条为空）+ 好友行（末条为空）」⇒ 同一支探针
      // 一次读两侧（设备侧必须不是好友文案；好友侧必须仍是好友文案）。
      await page.unroute('**/api/**');
      await installRoutes(page, cap, { deviceRow: true, friendRow: true });
      await page.reload();
      await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
      await sleep(1200);
      await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
      await sleep(400);
      await ensurePanel(page, '#messages-btn', 'panel-messages');
      // 到达判据用**等待条件**（换桩 + reload 后 `refreshConversations` 的落地拍不定）；
      // 固定 sleep 会读成"两行皆无"的假红。
      await page.waitForFunction(
        () => document.querySelectorAll('#fm-conversations .fm-conv-row').length >= 2,
        { timeout: 15000 }).catch(() => {});
      await sleep(700);
      await shot(page, theme, '08-messages-rows');
      const rows = await page.evaluate(() => {
        const dev = document.querySelector('#fm-conversations .fm-conv-row[data-device="1"]');
        const fr = document.querySelector('#fm-conversations .fm-conv-row:not([data-device="1"]):not([data-group="1"])');
        return {
          devSummary: dev ? (dev.querySelector('.fm-conv-summary') || {}).textContent : null,
          devName: dev ? (dev.querySelector('.fm-row-name') || {}).textContent : null,
          devTag: dev ? (dev.querySelector('.fm-device-tag') || {}).textContent : null,
          friendSummary: fr ? (fr.querySelector('.fm-conv-summary') || {}).textContent : null,
          friendName: fr ? (fr.querySelector('.fm-row-name') || {}).textContent : null,
        };
      });
      ok('R8a', '设备会话行零好友关系文案（空末条 ⇒ 中性空态，非「你们已成为好友」）',
        rows.devSummary !== null && rows.devSummary !== ZH_NOW_FRIENDS && rows.devSummary === ZH_NO_MESSAGES,
        `dev="${rows.devName} / ${rows.devTag} / ${rows.devSummary}"`);
      ok('G8', '好友会话行该文案照常在（未误伤好友面）',
        rows.friendSummary === ZH_NOW_FRIENDS, `friend="${rows.friendName} / ${rows.friendSummary}"`);
      await shot(page, theme, '09-messages-rows-shot');

      ok('G9', '全程零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
    } finally {
      if (page) await page.close().catch(() => {});
      await ctx.close().catch(() => {});
    }
  }
} finally {
  await browser.close().catch(() => {});
  await new Promise(r => server.close(r));
}

// ── 期望判定：AFTER ⇒ 全绿；BEFORE ⇒ 红锚集**逐条红**（护栏仍须绿）──
const mismatched = [];
for (const r of results) {
  const wantPass = MODE === 'AFTER' ? true : !RED_ANCHORS.has(r.id);
  if (r.pass !== wantPass) mismatched.push(`${r.id}/${r.theme}: pass=${r.pass} want=${wantPass}`);
}
failures = mismatched.length;

console.log(`\n[${MODE}] web root = ${WEB}`);
console.log(`[${MODE}] probes = ${results.length}  mismatched-expectations = ${failures}`);
if (MODE === 'BEFORE') {
  const red = [...new Set(results.filter(r => !r.pass).map(r => r.id))].sort();
  console.log(`[BEFORE] 红锚复现（应为红锚集的子集）= ${JSON.stringify(red)}`);
  const missed = [...RED_ANCHORS].filter(id => !red.includes(id));
  if (missed.length) console.log(`[BEFORE] 🔴 未能复现的红锚 = ${JSON.stringify(missed)}`);
}
if (mismatched.length) console.log(`[${MODE}] mismatch: ${mismatched.slice(0, 20).join(' ; ')}`);
console.log(`[${MODE}] shots = ${SHOTS}`);
process.exit(failures === 0 ? 0 : 1);
