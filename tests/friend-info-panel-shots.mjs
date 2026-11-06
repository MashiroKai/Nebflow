#!/usr/bin/env node
// friend-info-panel-shots.mjs — 「三点按钮直达好友信息页 + 搜索面板顶部收敛」批
// （作者令 2026-11-05 17:59）**证据实拍脚本**：同一支脚本跑两棵树（改前/改后）。
//
//   · SHOTS_WEB_ROOT 未设 ⇒ 跑本仓 `src/main/resources/web`（改后树）。
//   · SHOTS_WEB_ROOT=/tmp/friend-info-before/src/main/resources/web
//     ⇒ 拍「改前」帧（base commit b78366f3e 的 `git archive` 树）：三点仍是
//     「菜单 → 再点一次查看资料」两跳、好友信息页仍是居中英雄区 + 56 档头像 +
//     五条分隔线、搜索页顶部两处标题 + 底部说明行、无免打扰/顶置两行。
//     缺件不抛错（逐场景 try/catch + manifest 记 absent），缺件本身即改前证据。
//
// 判据面（与批报告 §判据 一一对应，读数全部进 manifest.json）：
//   C1 点击路径：点三点后「菜单是否弹出」+「好友信息面板是否已开」+ 实际点击数
//   C2 好友信息页：头像直径实测 / 面板内嵌套层数 / 分隔线（border）条数 / 行清单
//   C3 搜索入口复用：从好友信息页点「搜索聊天记录」打开的面板，与主对话搜索钮
//      打开的是**同一个** `#search-modal` 节点（类名/模块路径/节点身份三重读数）
//   C4 两个 toggle：点击前后 aria-checked 实值 + 关面重进后仍保持 + 写入落到
//      mock 好友行（= 后端 PUT 的等价面）
//   C5 搜索面板顶部：标题节点可见性 / 胶囊文案 / 底部说明行是否存在 / 结果区高度
//
// Run:
//   node tests/friend-info-panel-shots.mjs
//   SHOTS_WEB_ROOT=/tmp/friend-info-before/src/main/resources/web \
//   SHOTS_OUT=<abs>/assets/<ts>_friend-info/before node tests/friend-info-panel-shots.mjs
//
// 与 profile-settings-batch-shots.mjs 同一套夹具纪律：静态站点 + `fm_api_mock`
// 好友域 mock（本域 UI 的既有取证方式），不起真实例、不碰宿主 6138。

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.SHOTS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SHOTS_OUT || join(ROOT, 'evidence', 'friend-info-panel');
const SID = 'friend-info-panel-shots';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '',
  displayName: '测试用户', email: 'me@example.com', avatarUrl: '',
};
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }];

const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: '同事 · 后端组', bio: '写 Scala，也爱养多肉。', since: 1762000000000,
};

function seed() {
  const NOW = Date.now();
  /** @type {any[]} */
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    msgs.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 用来把消息流撑出滚轴`, createdAt: NOW - (40 - i) * 60e3,
    });
  }
  return {
    self: { userId: 'me', username: 'Mashiro', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [{ ...F_MAIN }],
    incoming: [],
    outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 3 },
    ],
    messages: { 'c-f1': msgs },
    groups: [],
    groupInvites: [],
    groupMembers: {},
    _now: NOW,
  };
}

/** @type {any[]} */
const manifest = [];
/** @type {string[]} */
const consoleErrors = [];

async function boot(page) {
  const s = seed();
  await page.addInitScript((seedJson) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    window.open = () => null;
  }, s);

  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      // 搜索面取数腿 = REST `/api/sessions/{id}/history`（chatSearch.fetchHistory）：
      // 必须给**契约形状**的行（`uiMessage`：type/text/timestamp），否则 search 面
      // 落进「加载失败」错误态，结果区高度读数就没意义了。
      if (/^\/api\/sessions\/[^/]+\/history$/.test(p)) {
        const rows = [];
        for (let i = 1; i <= 12; i += 1) {
          rows.push(i % 2
            ? { id: `m${i}`, type: 'user', text: `第 ${i} 条：搜索命中的用户消息（关键词：消息记录）`, timestamp: 1762000000000 + i * 60000 }
            : { id: `m${i}`, type: 'ai', text: `第 ${i} 条：助手回复，含「搜索聊天记录」关键词`, timestamp: 1762000000000 + i * 60000 });
        }
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ messages: rows, hasMore: false }),
        });
      }
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: PEERS }),
        });
      }
      if (p === '/api/users/search') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            found: true,
            user: { userId: 'u-self', username: 'Mashiro', display_name: '测试用户', avatar: null },
            relation_status: 'self',
          }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const st = (await import('/js/state.js')).default;
    return st.activeSessionId === sid && st.ws && st.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

async function shot(page, scene, file, extra = {}, clip = null) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, file);
  try {
    await page.screenshot({ path, ...(clip ? { clip } : {}) });
    manifest.push({ scene, file: path, ok: true, ...extra });
  } catch (e) {
    manifest.push({ scene, file: path, ok: false, absent: String((e && e.message) || e) });
  }
}

async function openFriendConversation(page) {
  const CONV = '.mobile-page[data-page="conversation"]';
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="messages"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(300);
  await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-f1"]').first().click();
  await page.waitForSelector(`${CONV} .fm-modal`, { timeout: 10000 });
  await page.waitForTimeout(600);
  return CONV;
}

/** C1：点三点后的实际路径（菜单有没有弹、面板有没有开、点了几次）。 */
async function clickPathReading(page, CONV) {
  const more = page.locator(`${CONV} .mh-sess-more`);
  await more.click();
  // 500ms 观察窗：够菜单渲染，也不至于把两跳误判成「面板已开」。
  await page.waitForTimeout(500);
  const menuOpen = await page.evaluate(() => !!document.querySelector('.fm-context-menu'));
  const panelOpen = await page.evaluate(() => {
    const ov = document.querySelector('#profile-face-overlay');
    return !!ov && !ov.hidden;
  });
  return { clicks: panelOpen ? 1 : (menuOpen ? 1 : 0), menuOpen, panelOpen, note: panelOpen ? '一点直达' : '需第二跳' };
}

/** C2：好友信息页几何读数（头像直径 / 嵌套层数 / 分隔线条数 / 行清单）。 */
async function panelReading(page) {
  return page.evaluate(() => {
    const ov = document.querySelector('#profile-face-overlay');
    if (!ov || ov.hidden) return null;
    const modal = ov.querySelector('.pf-modal');
    const avatar = ov.querySelector('.pf-hero .fm-avatar');
    const rect = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
    };
    // 嵌套层数：从面板根到「最深一行」的祖先链长度（一条主轴度量扁平化程度）。
    const rows = [...ov.querySelectorAll('.pf-fields > *')];
    const deepest = rows.reduce((acc, r) => {
      let d = 0; let n = r;
      while (n && n !== modal) { d += 1; n = n.parentElement; }
      return Math.max(acc, d);
    }, 0);
    // 分隔线（**只数行级容器**：改前 = 每个字段行一条 border-top，正是作者要去掉的
    // 「一个个卡片盒子 / 分隔线」；表单控件自身的描边（输入框/保存键/开关本体）不算
    // ——它们是控件材质，不是行分隔）。
    let fieldSeparators = 0;
    let rowBoxes = 0;
    const fields = ov.querySelector('.pf-fields');
    if (fields) {
      const rowLevel = [...fields.children, ...[...fields.children].flatMap((r) => [...r.children])]
        .filter((el) => !['INPUT', 'BUTTON', 'SELECT'].includes(el.tagName));
      for (const el of rowLevel) {
        const cs = getComputedStyle(el);
        const bt = parseFloat(cs.borderTopWidth) || 0;
        const bb = parseFloat(cs.borderBottomWidth) || 0;
        if ((bt > 0 && cs.borderTopStyle !== 'none') || (bb > 0 && cs.borderBottomStyle !== 'none')) fieldSeparators += 1;
      }
      for (const row of [...fields.children]) {
        const cs = getComputedStyle(row);
        const bg = cs.backgroundColor;
        const hasBg = bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent';
        const hasBorder = (parseFloat(cs.borderTopWidth) || 0) > 0
          || (parseFloat(cs.borderBottomWidth) || 0) > 0
          || (parseFloat(cs.borderLeftWidth) || 0) > 0;
        const hasShadow = cs.boxShadow && cs.boxShadow !== 'none';
        if (hasBg || hasBorder || hasShadow) rowBoxes += 1;
      }
    }
    return {
      avatar: rect(avatar),
      avatarClass: avatar ? avatar.className : null,
      heroRow: (getComputedStyle(ov.querySelector('.pf-hero') || ov).flexDirection || ''),
      heroRect: rect(ov.querySelector('.pf-hero')),
      nestDepth: deepest,
      fieldSeparators,
      rowBoxes,
      rowCount: rows.length,
      rowGap: fields ? getComputedStyle(fields).rowGap || getComputedStyle(fields).gap : null,
      rows: rows.map((r) => ({
        cls: r.className,
        field: r.dataset ? (r.dataset.pfField || r.dataset.pfNav || r.dataset.pfSwitch || '') : '',
        text: (r.textContent || '').trim().slice(0, 40),
      })),
      panelRect: rect(modal),
    };
  });
}

/** C4：两个 toggle 的实态（aria-checked）+ 写入是否落到好友行。 */
async function toggleReading(page) {
  return page.evaluate(async () => {
    const out = {};
    for (const k of ['muted', 'pinned']) {
      const btn = document.querySelector(`#profile-face-overlay [data-pf-toggle="${k}"]`);
      out[k] = btn ? {
        checked: btn.getAttribute('aria-checked'),
        on: btn.classList.contains('on'),
        cls: btn.className,
      } : null;
    }
    const api = await import('/js/friendsApi.js');
    const list = await api.getFriends();
    const row = (list.friends || []).find((f) => String(f.userId) === 'u-f1');
    out.friendRow = row ? { muted: row.muted ?? null, pinned: row.pinned ?? null } : null;
    return out;
  });
}

/** C5：搜索面板顶部读数（标题节点 / 胶囊文案 / 底部说明 / 结果区高度）。 */
async function searchTopReading(page) {
  return page.evaluate(() => {
    const modal = document.getElementById('search-modal');
    if (!modal) return null;
    const header = modal.querySelector('.search-modal-header');
    const titleEl = document.getElementById('search-modal-title');
    const pageTitle = document.querySelector('.mobile-page[data-page="search"] .mobile-page-title');
    const foot = document.getElementById('mh-search-foot');
    const results = document.getElementById('search-results');
    const status = document.getElementById('search-status');
    const r = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
    };
    const vis = (el) => {
      if (!el) return false;
      const cs = getComputedStyle(el);
      const b = el.getBoundingClientRect();
      return cs.display !== 'none' && cs.visibility !== 'hidden' && b.width > 0 && b.height > 0;
    };
    return {
      pillText: pageTitle ? (pageTitle.textContent || '').trim() : null,
      pillRect: r(pageTitle),
      headerVisible: vis(header),
      headerRect: r(header),
      titleText: titleEl ? (titleEl.textContent || '').trim() : null,
      titleVisible: vis(titleEl),
      footPresent: !!foot,
      resultsRect: r(results),
      statusText: status ? (status.textContent || '').trim().slice(0, 40) : '',
      statusRect: r(status),
      modalRect: r(modal),
      standaloneTitleCount: [...document.querySelectorAll('#search-modal-title')].filter(vis).length,
      searchModalCount: document.querySelectorAll('#search-modal').length,
    };
  });
}

async function run(browser, scheme) {
  const tag = scheme;
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme, deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  await boot(page);

  let CONV = '.mobile-page[data-page="conversation"]';

  // ── S1 会话页 → 三点 ──
  try {
    CONV = await openFriendConversation(page);
    await shot(page, 'conversation', `m-${tag}-s1-conversation.png`);
    const path = await clickPathReading(page, CONV);
    await page.waitForTimeout(400);
    await shot(page, 'click-path', `m-${tag}-s2-after-more-click.png`, { readings: path });
    // 改前树：菜单弹着（两跳）；为拿到相同的面板帧，补按「查看资料」。
    if (!path.panelOpen) {
      const item = page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first();
      if (await item.count()) {
        await item.click();
        await page.waitForTimeout(600);
        manifest.push({ scene: 'click-path-2nd', ok: true, readings: { clicks: 2, note: '第二跳：菜单项「查看资料」' } });
      }
    }
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
    await page.waitForTimeout(400);
    await shot(page, 'friend-info', `m-${tag}-s3-friend-info.png`, { readings: await panelReading(page) });
    await shot(page, 'friend-info-hero', `m-${tag}-s3b-friend-info-hero.png`,
      {}, { x: 0, y: 0, width: 375, height: 260 });
  } catch (e) {
    manifest.push({ scene: 'friend-info', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }

  // ── S4 两个 toggle（改前树没有 ⇒ absent） ──
  try {
    const before = await toggleReading(page);
    const btns = await page.locator('#profile-face-overlay [data-pf-toggle]').count();
    if (btns < 2) throw new Error('改前树无 toggle 行（data-pf-toggle 计数 = ' + btns + '）');
    await page.locator('#profile-face-overlay [data-pf-toggle="muted"]').click();
    await page.waitForTimeout(400);
    await page.locator('#profile-face-overlay [data-pf-toggle="pinned"]').click();
    await page.waitForTimeout(500);
    const after = await toggleReading(page);
    await shot(page, 'toggles-on', `m-${tag}-s4-toggles-on.png`, { readings: { before, after } });
    // 关面重进：状态必须仍在（真值来自好友行 = 后端 PUT 的等价面）。
    await page.locator('#profile-face-close').click();
    await page.waitForTimeout(400);
    await page.locator(`${CONV} .mh-sess-more`).click();
    await page.waitForTimeout(700);
    if (!(await page.locator('#profile-face-overlay:not([hidden])').count())) {
      const item = page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first();
      if (await item.count()) { await item.click(); await page.waitForTimeout(500); }
    }
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
    await page.waitForTimeout(400);
    const reopened = await toggleReading(page);
    await shot(page, 'toggles-reopened', `m-${tag}-s5-toggles-reopened.png`, { readings: { reopened } });
  } catch (e) {
    manifest.push({ scene: 'toggles', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }

  // ── S6 从好友信息页点「搜索聊天记录」→ 同一张搜索面板 ──
  try {
    const nav = page.locator('#profile-face-overlay [data-pf-nav]').first();
    if (!(await nav.count())) throw new Error('改前树无「搜索聊天记录」入口行');
    await nav.click();
    await page.waitForSelector('#search-overlay.on', { timeout: 8000 });
    await page.waitForTimeout(700);
    const fromProfile = await searchTopReading(page);
    // 复用证明（节点腿）：给此刻的面板节点打标 —— 换入口再开时若标记仍在，
    // 证明确实是**同一个 DOM 节点**（不是第二个实例）。
    await page.evaluate(() => {
      const m = document.getElementById('search-modal');
      if (m) m.dataset.fipMark = 'from-profile';
    });
    const moduleId = await page.evaluate(async () => {
      // 复用证明（模块腿）：面板本体只可能来自 `js/chatSearch.js` 的 openSearchModal。
      const m = await import('/js/chatSearch.js');
      return { hasOpenSearchModal: typeof m.openSearchModal === 'function' };
    });
    await shot(page, 'search-from-profile', `m-${tag}-s6-search-from-profile.png`,
      { readings: { fromProfile, moduleId } });
  } catch (e) {
    manifest.push({ scene: 'search-from-profile', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }

  // ── S7 从主对话搜索钮打开同一面板（真实入口：聊天根右上搜索圆钮）──
  try {
    // 先把可能还开着的面关掉（改前树 S4/S5 在缺件处中止，好友信息面仍开着会挡住 dock）。
    const pfClose = page.locator('#profile-face-close');
    if (await pfClose.count()) { await pfClose.first().click({ force: true }).catch(() => {}); await page.waitForTimeout(300); }
    await page.evaluate(async () => {
      const m = await import('/js/chatSearch.js');
      m.closeSearchModal();
    });
    await page.waitForTimeout(300);
    // 回聊天根：详情页在场时先按返回（改前/改后两树同一条路，不依赖页面栈状态）。
    for (let i = 0; i < 3; i += 1) {
      const back = page.locator('.mobile-page.live .mobile-back-btn');
      if (await back.count()) { await back.first().click(); await page.waitForTimeout(400); } else break;
    }
    const chatDock = page.locator('#mobile-dock .dock-key[data-dock="chat"]');
    if (await chatDock.count()) { await chatDock.first().click({ force: true }).catch(() => {}); }
    await page.waitForTimeout(600);
    const chromeBtn = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="search"]');
    if (!(await chromeBtn.count())) throw new Error('聊天根无搜索圆钮（#mobile-fab-layer [data-chrome=search]）');
    await chromeBtn.first().click();
    await page.waitForSelector('#search-overlay.on', { timeout: 8000 });
    await page.waitForTimeout(800);
    const fromMain = await searchTopReading(page);
    const sameNode = await page.evaluate(() => {
      const m = document.getElementById('search-modal');
      return m ? m.dataset.fipMark === 'from-profile' : false;
    });
    await shot(page, 'search-from-main', `m-${tag}-s7-search-from-main.png`, { readings: { sameNode, fromMain } });
  } catch (e) {
    manifest.push({ scene: 'search-from-main', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }

  // ── S8 搜索页（手机层级页）：顶部收敛 + 结果区高度 ──
  try {
    const top = await searchTopReading(page);
    await page.locator('#search-keyword').fill('消息');
    await page.waitForTimeout(700);
    await shot(page, 'search-top', `m-${tag}-s8-search-top.png`, { readings: { top, afterType: await searchTopReading(page) } });
  } catch (e) {
    manifest.push({ scene: 'search-top', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }

  await ctx.close();
}

async function runDesktop(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await boot(page);
  try {
    // 桌面：搜索面板（本批只动手机层顶部，桌面必须逐字不变）。
    await page.evaluate(async () => {
      const m = await import('/js/chatSearch.js');
      m.openSearchModal();
    });
    await page.waitForSelector('#search-overlay.on', { timeout: 8000 });
    await page.waitForTimeout(600);
    await shot(page, 'desktop-search', 'd-light-s9-desktop-search.png', { readings: await searchTopReading(page) });
  } catch (e) {
    manifest.push({ scene: 'desktop-search', ok: false, absent: String((e && e.message) || e).split('\n')[0].slice(0, 120) });
  }
  await ctx.close();
}

const browser = await chromium.launch();
try {
  await run(browser, 'light');
  await run(browser, 'dark');
  await runDesktop(browser);
} finally {
  await browser.close();
}

mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ web: WEB, out: OUT, manifest, consoleErrors }, null, 2));
if (consoleErrors.length) {
  console.error(`console errors: ${consoleErrors.length}`);
  for (const e of consoleErrors.slice(0, 10)) console.error('  ' + e);
}
console.log(`shots → ${OUT} (${manifest.length} scenes, ${consoleErrors.length} console errors)`);
process.exit(consoleErrors.length ? 1 : 0);
