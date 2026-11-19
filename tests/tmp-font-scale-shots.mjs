// tmp-font-scale-shots.mjs — 联系人/消息列表条目字号放大批 · 截图取证脚本（tmp 件）。
//
// 用法：node tests/tmp-font-scale-shots.mjs <before|after>
//   直连模式（route 拦截 + 契约 wire），自起 chromium，跑完即关。
//   每个 label 在 375×812 与 1440×900 两视口下截取消息列表 / 联系人列表两面板，
//   并把关键条目的 computed font-size 打印成证据行。
// 产物：reports/<ts>-contact-msg-font-scale/<label>-<viewport>-<panel>.png
import { chromium } from 'playwright-core';
import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const LABEL = process.argv[2] || 'before';
const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'reports', '20261031_contact-msg-font-scale');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const WIRE_FRIEND_A = { userId: 'u-a', username: 'kaiyu@nebula', display_name: '凯宇', avatar: '', remark: null, since: new Date().toISOString() };
const WIRE_FRIEND_B = { userId: 'u-b', username: 'mashiro@nebula', display_name: '真白', avatar: '', remark: '白酱', since: new Date().toISOString() };
const NOW = Date.now();

const sleep = ms => new Promise(r => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();

async function boot(viewport) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.removeItem('fm_api_mock');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  await page.route('**/api/friends', r => r.fulfill({
    json: { friends: [WIRE_FRIEND_A, WIRE_FRIEND_B], incoming: [], outgoing: [] },
  }));
  await page.route('**/api/conversations', r => r.fulfill({
    json: [
      { conversationId: 'c-a', friend: WIRE_FRIEND_A, unreadCount: 2, lastMessage: { body: '晚上一起看下那个字体改动的效果截图', createdAt: NOW - 5 * 60000, sender: 'u-a' } },
      { conversationId: 'c-b', friend: WIRE_FRIEND_B, unreadCount: 0, lastMessage: { body: '好的，明天发你一版对比', createdAt: NOW - 26 * 3600000, sender: 'u-b' } },
    ],
  }));
  await page.route('**/api/conversations/*/messages*', r => r.fulfill({ json: [] }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors };
}

async function shotPanel(page, face, file) {
  const mobile = await page.locator('#mobile-dock').isVisible().catch(() => false);
  if (mobile) {
    // 手机层（375）：底 dock 切根页 → 截整屏。
    await page.locator(`#mobile-dock .dock-key[data-dock="${face}"]`).click();
    await page.waitForFunction((f) => {
      const el = document.querySelector(`.mobile-page[data-page="${f}"]`);
      return el && el.classList.contains('live');
    }, face, { timeout: 5000 });
  } else {
    const btn = face === 'messages' ? '#messages-btn' : '#contacts-btn';
    const panelSel = face === 'messages' ? '#panel-messages' : '#panel-contacts';
    if (!(await page.locator(`${panelSel}.active`).count())) {
      await page.click(btn);
      await sleep(700);
    }
  }
  await sleep(400);
  await page.screenshot({ path: join(OUT, file) });
}

async function readStyles(page) {
  return page.evaluate(() => {
    const cs = (sel, prop, root) => {
      const e = root ? root.querySelector(sel) : document.querySelector(sel);
      return e ? getComputedStyle(e)[prop] : null;
    };
    const conv = document.querySelector('#fm-conversations .fm-conv-row');
    const friend = document.querySelector('#fm-contacts-body .fm-friend-row');
    return {
      conv: {
        name: cs('.fm-row-name', 'fontSize', conv), nameLH: cs('.fm-row-name', 'lineHeight', conv),
        summary: cs('.fm-conv-summary', 'fontSize', conv),
        time: cs('.fm-conv-time', 'fontSize', conv),
        rowH: conv ? Math.round(conv.getBoundingClientRect().height) : null,
      },
      friend: {
        name: cs('.fm-row-name', 'fontSize', friend), nameLH: cs('.fm-row-name', 'lineHeight', friend),
        sub: cs('.fm-row-sub', 'fontSize', friend), subLH: cs('.fm-row-sub', 'lineHeight', friend),
        rowH: friend ? Math.round(friend.getBoundingClientRect().height) : null,
      },
    };
  });
}

const evidence = {};
for (const [vwName, viewport] of [['375', { width: 375, height: 812 }], ['1440', { width: 1440, height: 900 }]]) {
  const { ctx, page, pageErrors } = await boot(viewport);

  await shotPanel(page, 'messages', `${LABEL}-${vwName}-messages.png`);
  evidence[`${vwName}-messages`] = await readStyles(page);

  await shotPanel(page, 'contacts', `${LABEL}-${vwName}-contacts.png`);
  evidence[`${vwName}-contacts`] = await readStyles(page);

  evidence[`${vwName}-pageErrors`] = pageErrors;
  await ctx.close();
}
await browser.close();

writeFileSync(join(OUT, `${LABEL}-styles.json`), JSON.stringify(evidence, null, 2));
console.log(JSON.stringify(evidence, null, 2));
console.log(`shots written to ${OUT}`);
