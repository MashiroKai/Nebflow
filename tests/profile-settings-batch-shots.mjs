#!/usr/bin/env node
// profile-settings-batch-shots.mjs — profile-settings 批（作者令 2026-11-02，件一～件五）
// 证据实拍脚本：**同一支脚本跑两棵树**（判红纪律「改前/改后」对照）。
//
//   · SHOTS_WEB_ROOT 未设 ⇒ 跑本仓 `src/main/resources/web`（改后树）→ 全场景可拍。
//   · SHOTS_WEB_ROOT=<改前树 web/>（`git archive 23029d8fe | tar -x -C /tmp/baseline-prebatch`）
//     ⇒ 拍「改前」帧：Profile 面/身份卡尚不存在、会话页头是旧形态、联系人设备行仍带
//     更新钮 —— 缺件不抛错（逐场景 try/catch + manifest 记 `absent`），这正是改前证据。
//
// 场景（手机 375×812 亮/暗 + 桌面 1280 亮）：
//   S1 好友 Profile（双描述两字段可见可编辑）→ 编辑后帧
//   S2 群 Profile（群介绍 + 我的群备注）
//   S3 三会话页头并排素材：主对话 / 好友 / 群 / 设备（同机位、同裁剪 = 顶部 0-140px）
//   S4 滚动后身份头渐隐帧（好友会话）
//   S5 设置页身份卡
//   S6 点胶囊进自己的 profile 面（只读真实读数 + GitHub/Google 未知态）
//   S7 联系人设备区（件五：更新入口已无）
//   S8 桌面：好友 Profile / 设置身份卡 / 自己的 profile 面
//
// 全程 console error 采集：任何一条进 manifest 并让退出码 = 1（零错误是硬判据）。
//
// Run:
//   node tests/profile-settings-batch-shots.mjs
//   SHOTS_WEB_ROOT=/tmp/baseline-prebatch/src/main/resources/web \
//   SHOTS_OUT=<abs>/evidence/20261102_profile-and-settings/before \
//   node tests/profile-settings-batch-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.SHOTS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SHOTS_OUT || join(ROOT, 'evidence', '20261102_profile-and-settings', 'after');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'profile-settings-batch-shots';

const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '',
  displayName: '测试用户', email: 'me@example.com', avatarUrl: '',
};
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }];

const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: '同事 · 后端组', bio: '写 Scala，也爱养多肉。', since: 1762000000000,
};
const G_MAIN = {
  groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2,
  lastMessage: { id: 101, senderId: 'u-f1', kind: 'text', body: '今天开会', createdAt: 1762000000000 },
  unreadCount: 0, lastMessageId: 101, createdAt: 1762000000000,
  description: 'stand 本体开发群，只聊落地。', remark: '我的群备注：只看发布',
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
    // 🔴 `username` 必填：mock 的 `searchUser(email)` 自证腿（relation_status='self'）返回
    //    `username ?? neblinkId`，缺 username 时会把邮箱当用户名显示 —— 那是夹具假象，
    //    不是产品行为（真机 = Logto username claim）。
    self: { userId: 'me', username: 'Mashiro', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [{ ...F_MAIN }],
    incoming: [],
    outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
      {
        conversationId: 'g-1', kind: 'group', title: G_MAIN.title, memberCount: 2, myRole: 'owner',
        lastMessage: G_MAIN.lastMessage, unreadCount: 0,
      },
    ],
    messages: { 'c-f1': msgs, 'g-1': [] },
    groups: [{ ...G_MAIN }],
    groupInvites: [],
    groupMembers: {
      'g-1': [
        { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
        { userId: 'u-f1', username: null, display_name: '老友', avatar: null, role: 'member', joinedAt: 0 },
      ],
    },
    _now: NOW,
  };
}

/** @type {{scene: string, file: string, ok: boolean, absent?: string, readings?: any}[]} */
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
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  }, s);

  const tag = `${page.viewportSize()?.width || 0}`;
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`${tag}: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`${tag}: pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
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
      // 🔴 唯一真实身份读路（settingsIdentity 的自证探针；friend contract v1.0 §4.1 形状，
      //    `relation_status:'self'` 是自证判据）—— 不 stub 它，身份卡 username 会退回邮箱。
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

async function isMobileShell(page) {
  return page.evaluate(() => document.body.classList.contains('mobile-layer'));
}

async function dockTo(page, id) {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForFunction((pid) => {
    const el = document.querySelector(`.mobile-page[data-page="${pid}"]`);
    return !!el && el.classList.contains('live');
  }, id, { timeout: 8000 });
  await page.waitForTimeout(250);
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

/** 改前兜底帧：目标面在改前树**不存在**（Profile 面 / 身份卡 / 三点菜单）时，
 *  拍当下的整屏 = 改前形态本身（证据意义不减：缺的正是本批新增的面）。
 *  @param {string} reason */
async function shotFallback(page, scene, file, reason, extra = {}) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, file);
  try {
    await page.screenshot({ path });
    manifest.push({ scene, file: path, ok: true, readings: { fallback: reason }, ...extra });
  } catch (e) {
    manifest.push({ scene, file: path, ok: false, absent: `${reason} / ${String((e && e.message) || e)}` });
  }
}

/** 好友 Profile 面（手机 = 会话三点菜单；桌面 = 联系人行右键菜单）。 */
async function openFriendProfile(page, mobile) {
  if (mobile) {
    await dockTo(page, 'messages');
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-f1"]').click();
    await page.waitForSelector('.mobile-page[data-page="conversation"] .fm-modal', { timeout: 8000 });
    await page.locator('.mobile-page[data-page="conversation"] .mh-sess-more').click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    await page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first().click();
  } else {
    await page.locator('#contacts-btn').click();
    await page.waitForSelector('#fm-contacts-body .fm-friend-row', { timeout: 15000 });
    await page.locator('.fm-friend-row').first().click({ button: 'right' });
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    await page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first().click();
  }
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
  await page.waitForTimeout(300);
}

async function closeProfile(page) {
  const btn = page.locator('#profile-face-close');
  if (await btn.count()) await btn.click();
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
}

/** 会话身份头几何读数（与 spec 同一判据口径，供 manifest 佐证「并排同构」）。 */
async function headReading(page, sel) {
  return page.evaluate((s) => {
    const head = document.querySelector(s);
    if (!head) return null;
    const avatar = head.querySelector('#agent-avatar-btn, .mh-idhead-avatar > .fm-modal-avatar');
    const name = head.querySelector('.mh-idhead-name');
    const r = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {
        x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1),
        cx: +(b.x + b.width / 2).toFixed(1), cy: +(b.y + b.height / 2).toFixed(1),
      };
    };
    return { cls: head.className, avatar: r(avatar), name: r(name), text: (name?.textContent || '').trim() };
  }, sel);
}

async function readFields(page) {
  return page.evaluate(() => [...document.querySelectorAll('#profile-face-overlay [data-pf-input]')]
    .map((e) => ({ id: /** @type {HTMLInputElement} */ (e).dataset.pfInput, value: /** @type {HTMLInputElement} */ (e).value })));
}

async function runMobile(browser, scheme) {
  const tag = scheme; // light | dark
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme, deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  await boot(page);
  const CONV = '.mobile-page[data-page="conversation"]';

  // ── S3 素材：主对话身份头 ──
  try {
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
    await page.waitForSelector('#chat > .g2-chat-head', { timeout: 10000 });
    await page.waitForTimeout(500);
    await shot(page, 'heads-main', `m-${tag}-s3-heads-main.png`,
      { readings: await headReading(page, '#chat > .g2-chat-head') },
      { x: 0, y: 0, width: 375, height: 140 });
  } catch (e) { manifest.push({ scene: 'heads-main', file: '', ok: false, absent: String((e && e.message) || e) }); }

  // ── 好友会话：页头 + 会话全屏 + Profile + 滚动渐隐 ──
  try {
    await dockTo(page, 'messages');
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-f1"]').click();
    await page.waitForSelector(`${CONV} .fm-modal`, { timeout: 10000 });
    await page.waitForTimeout(600);
    await shot(page, 'heads-friend', `m-${tag}-s3-heads-friend.png`,
      { readings: await headReading(page, `${CONV} .fm-modal .mh-idhead`) },
      { x: 0, y: 0, width: 375, height: 140 });
    await shot(page, 'conversation-friend-full', `m-${tag}-s3-conversation-friend-full.png`);

    // S1 好友 Profile（双描述）
    await page.locator(`${CONV} .mh-sess-more`).click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    await page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first().click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
    await page.waitForTimeout(400);
    await shot(page, 'friend-profile', `m-${tag}-s1-friend-profile.png`, { readings: { fields: await readFields(page) } });
    const bio = page.locator('#profile-face-overlay [data-pf-input="bio"]');
    if (await bio.count()) {
      await bio.fill('写 Scala，也爱养多肉。｜本批证据改写串');
      await page.locator('#profile-face-overlay [data-pf-save="bio"]').click();
      await page.waitForTimeout(600);
      await shot(page, 'friend-profile-edited', `m-${tag}-s1-friend-profile-edited.png`, { readings: { fields: await readFields(page) } });
    }
    await closeProfile(page);

    // S4 滚动渐隐
    await page.evaluate((sel) => {
      const flow = document.querySelector(`${sel} .fm-flow`);
      if (flow) { flow.scrollTop = 200; flow.dispatchEvent(new Event('scroll')); }
    }, CONV);
    await page.waitForTimeout(500);
    const faded = await page.evaluate((sel) => {
      const h = document.querySelector(`${sel} .fm-modal .mh-idhead`);
      if (!h) return null;
      const cs = getComputedStyle(h);
      return { opacity: cs.opacity, visibility: cs.visibility, p: cs.getPropertyValue('--idhead-p').trim() };
    }, CONV);
    await shot(page, 'heads-friend-scrolled', `m-${tag}-s4-heads-friend-scrolled.png`, { readings: faded },
      { x: 0, y: 0, width: 375, height: 140 });
    await page.locator(`${CONV} .mobile-back-btn`).click();
    await page.waitForTimeout(500);
  } catch (e) {
    // 改前树：三点菜单 / Profile 面不存在 ⇒ 拍当下整屏（改前形态本身）作对照帧。
    await shotFallback(page, 'heads-friend', `m-${tag}-s3-conversation-friend-before-full.png`,
      `前四件未落地：${String((e && e.message) || e).split('\n')[0].slice(0, 80)}`);
  }

  // ── 群会话页头 + S2 群 Profile ──
  try {
    // 群行在手机层有两个镜像副本（桌面面板 + 手机页）⇒ 只点可见的那一份。
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="g-1"]:visible').first().click();
    await page.waitForSelector(`${CONV} .fm-modal`, { timeout: 10000 });
    await page.waitForTimeout(600);
    await shot(page, 'heads-group', `m-${tag}-s3-heads-group.png`,
      { readings: await headReading(page, `${CONV} .fm-modal .mh-idhead`) },
      { x: 0, y: 0, width: 375, height: 140 });
    // 群设置抽屉入口：手机层走右上三点菜单（窗头行在会话页内已收口 ⇒ `.fm-gs-open`
    // 在手机层不可见；三点菜单 = 该面唯一入口，见 conversation-heads-unify.spec）。
    await page.locator(`${CONV} .mh-sess-more`).click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    await page.locator('.fm-context-menu button').filter({ hasText: '群设置' }).first().click();
    await page.waitForSelector(`${CONV} .fm-group-settings`, { timeout: 6000 });
    await page.locator(`${CONV} .fm-group-settings .fm-gs-profile`).click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
    await page.waitForTimeout(400);
    await shot(page, 'group-profile', `m-${tag}-s2-group-profile.png`, { readings: { fields: await readFields(page) } });
    await closeProfile(page);
    await page.locator(`${CONV} .mobile-back-btn`).click();
    await page.waitForTimeout(500);
  } catch (e) {
    await shotFallback(page, 'heads-group', `m-${tag}-s3-messages-before.png`,
      `改前树：无群会话行/无 .mh-sess-more —— ${String((e && e.message) || e).split('\n')[0].slice(0, 70)}`);
  }

  // ── 设备会话页头 ──
  try {
    await page.evaluate(async (peer) => {
      const m = await import('/js/messages.js');
      m.openDeviceChat(peer);
    }, PEERS[0]);
    await page.waitForSelector(`${CONV} .fm-modal`, { timeout: 10000 });
    await page.waitForTimeout(600);
    await shot(page, 'heads-device', `m-${tag}-s3-heads-device.png`,
      { readings: await headReading(page, `${CONV} .fm-modal .mh-idhead`) },
      { x: 0, y: 0, width: 375, height: 140 });
    await page.locator(`${CONV} .mobile-back-btn`).click();
    await page.waitForTimeout(500);
  } catch (e) { manifest.push({ scene: 'heads-device', file: '', ok: false, absent: String((e && e.message) || e) }); }

  // ── S7 联系人设备区（件五）──
  try {
    await dockTo(page, 'contacts');
    const entry = page.locator('[data-devices-entry="1"]');
    if (await entry.count()) await entry.click();
    await page.waitForTimeout(500);
    const devices = await page.evaluate(() => {
      const sec = document.querySelector('[data-device-section="1"]');
      return {
        rows: sec ? sec.querySelectorAll('.fm-device-row').length : 0,
        kidCounts: sec ? [...sec.querySelectorAll('.fm-device-row')].map((r) => r.children.length) : [],
        updateBtns: document.querySelectorAll('[data-device-update-btn], .neblink-peer-update-btn').length,
        updateStates: document.querySelectorAll('[data-device-update-state]').length,
        updateTextBtns: sec ? [...sec.querySelectorAll('button')].filter((b) => /更新|Update/i.test(b.textContent || '')).length : -1,
        anchors: sec ? [...sec.querySelectorAll('a[href]')].map((a) => a.getAttribute('href')) : [],
      };
    });
    await shot(page, 'contacts-devices', `m-${tag}-s7-contacts-devices.png`, { readings: devices });
  } catch (e) { manifest.push({ scene: 'contacts-devices', file: '', ok: false, absent: String((e && e.message) || e) }); }

  // ── S5 设置页身份卡 + S6 自己的 profile 面 ──
  try {
    await dockTo(page, 'workbench');
    await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
    await page.waitForSelector('#settings-identity-card', { timeout: 10000 });
    await page.waitForTimeout(700);
    const cardRead = await page.evaluate(() => {
      const c = document.getElementById('settings-identity-card');
      const cap = document.getElementById('settings-identity-capsule');
      const r = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { w: +b.width.toFixed(1), h: +b.height.toFixed(1), x: +b.x.toFixed(1), y: +b.y.toFixed(1) };
      };
      return {
        hasCard: !!c,
        avatar: r(document.getElementById('settings-avatar-entry')),
        capsule: r(cap),
        username: c?.querySelector('.settings-identity-username')?.textContent?.trim() || '',
        email: c?.querySelector('.settings-identity-email')?.textContent?.trim() || '',
        capsuleKids: cap ? cap.children.length : 0,
        anchors: c ? c.querySelectorAll('a').length : -1,
      };
    });
    await shot(page, 'settings-identity-card', `m-${tag}-s5-settings-identity-card.png`, { readings: cardRead });

    const cap = page.locator('#settings-identity-capsule');
    if (await cap.count()) {
      await cap.click();
      await page.waitForSelector('#settings-profile-overlay:not([hidden])', { timeout: 8000 });
      await page.waitForTimeout(600);
      const face = await page.evaluate(() => {
        const ov = document.getElementById('settings-profile-overlay');
        const field = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
        return {
          username: field('username'), nickname: field('nickname'), email: field('email'),
          github: field('github'), google: field('google'),
          editable: ov ? ov.querySelectorAll('input, select, textarea, [contenteditable], [role="switch"]').length : -1,
          anchors: ov ? ov.querySelectorAll('a').length : -1,
          warn: ov?.querySelector('.sp-note-warn')?.textContent?.trim() || '',
          openCalls: (/** @type {any} */ (window)).__openCalls.length,
        };
      });
      await shot(page, 'own-profile-face', `m-${tag}-s6-own-profile-face.png`, { readings: face });
    } else {
      await shotFallback(page, 'own-profile-face', `m-${tag}-s6-settings-before.png`, '改前树：无 #settings-identity-capsule（身份卡随件四新增）');
    }
  } catch (e) {
    await shotFallback(page, 'settings-identity-card', `m-${tag}-s5-settings-before.png`,
      `改前树：无 #settings-identity-card —— ${String((e && e.message) || e).split('\n')[0].slice(0, 70)}`);
  }

  await ctx.close();
}

async function runDesktop(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await boot(page);

  try {
    await openFriendProfile(page, false);
    await shot(page, 'desktop-friend-profile', 'd-light-s1-friend-profile.png', { readings: { fields: await readFields(page) } });
    await closeProfile(page);
  } catch (e) {
    await shotFallback(page, 'desktop-friend-profile', 'd-light-s1-contacts-before.png',
      `改前树：无「查看资料」菜单项 —— ${String((e && e.message) || e).split('\n')[0].slice(0, 70)}`);
  }

  try {
    await page.locator('#settings-btn').click();
    await page.waitForSelector('#settings-identity-card', { timeout: 10000 });
    await page.waitForTimeout(700);
    await shot(page, 'desktop-settings-identity', 'd-light-s5-settings-identity-card.png');
    const cap = page.locator('#settings-identity-capsule');
    if (await cap.count()) {
      await cap.click();
      await page.waitForSelector('#settings-profile-overlay:not([hidden])', { timeout: 8000 });
      await page.waitForTimeout(600);
      const face = await page.evaluate(() => {
        const ov = document.getElementById('settings-profile-overlay');
        const field = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
        return {
          username: field('username'), nickname: field('nickname'), email: field('email'),
          github: field('github'), google: field('google'),
          editable: ov ? ov.querySelectorAll('input, select, textarea, [contenteditable], [role="switch"]').length : -1,
          openCalls: (/** @type {any} */ (window)).__openCalls.length,
        };
      });
      await shot(page, 'desktop-own-profile-face', 'd-light-s6-own-profile-face.png', { readings: face });
    }
  } catch (e) {
    await shotFallback(page, 'desktop-settings-identity', 'd-light-s5-settings-before.png',
      `改前树：无 #settings-identity-card —— ${String((e && e.message) || e).split('\n')[0].slice(0, 70)}`);
  }

  await ctx.close();
}

const browser = await chromium.launch();
try {
  for (const scheme of ['light', 'dark']) await runMobile(browser, scheme);
  await runDesktop(browser);
} finally {
  await browser.close();
}

mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ web: WEB, out: OUT, manifest, consoleErrors }, null, 2));
const okCount = manifest.filter((m) => m.ok).length;
console.log(`shots → ${OUT}`);
console.log(`scenes ok=${okCount} absent=${manifest.length - okCount} consoleErrors=${consoleErrors.length}`);
for (const m of manifest) console.log(`${m.ok ? 'OK  ' : 'MISS'} ${m.scene} ${m.file}${m.absent ? '  [' + m.absent.split('\n')[0] + ']' : ''}`);
if (consoleErrors.length) { console.log('CONSOLE ERRORS:'); for (const e of consoleErrors) console.log('  ' + e); }
process.exitCode = consoleErrors.length ? 1 : 0;
