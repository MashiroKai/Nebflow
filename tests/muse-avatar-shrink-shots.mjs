#!/usr/bin/env node
// ⛔ 已退役（会话页头同构批 件二+件三，作者令 2026-11-02）：本脚本拍的形态
//    （Muse 头像 78→56 收小档（sticky 锚 + 流内首件））已被**整条取代** —— 现役形态 = 悬浮身份头
//    `.mh-idhead`（mobileLayer.css 文件尾「会话页头同构批」+ js/mobile/sessionHead.js），
//    机械验收改由 `tests/conversation-heads-unify.spec.mjs` 承担。
//    保留本文件仅作历史取证回放；其断言（fixed 顶栏/sticky 锚/负 margin）**不再成立**，
//    重跑必红 = 预期，勿据此判回归。
// muse-avatar-shrink-shots.mjs — Muse 会话头头像收小批（作者令 2026-11-02 06:58，
// 实拍 /Users/kaiyu/.stand/uploads/.../722923094985583_IMG_1674.jpg「手机端会话
// 顶部居中的 Muse 头像调小一点」）验收 harness：375×812 双主题 × 静止态/滚动中
// 两态实拍 + 机械探针；桌面 1280 回归自查（28px 零触碰）。
//
// ⚠ 契约改写（会话顶栏单行三段式批 2026-11-02 07:23）：sticky 锚贴 + 56px 大头像
// + 名字下置的旧形态已被整条取代，本 harness 判据随之改写为下表的单行档
// （同批新增的单行三段式验收见 tests/mobile-chat-topbar-single-row-shots.mjs）。
//
// 判据（新契约）：
//   ① 头像 40px（单行内 36-40 档取上沿；56 撑不开 56px 单行，取值理由见批文档）
//   ② 名字横排挂头像右侧：名字盒垂直落在头像区间内，水平间距 8px ±1.5
//   ③ 顶栏 fixed 贴窗口顶：headTop == 0 ±1（不再有 64px 内容流锚）
//   ④ 居中：头像中心 == 屏幕中线 ±1（名字绝对定位不参与居中）
//   ⑤ 滚动中顶栏不动（headTop/headBottom 逐值不变）+ 名字仍横排
//   ⑥ 名字字号：14px（与 40px 头像字身比 2.9，克制档）
//   ⑦ 桌面零触碰：1280 下 #chat 无 .g2-chat-head、#header 头像钮 28px
//   R0 每腿零 console error / pageerror
//
// 产出 → evidence/20261102_muse-avatar-shrink/：
//   rest-<scheme>.png       修后静止态（375 全屏，含消息区比例观感）
//   scrolled-<scheme>.png   修后滚动态（fixed 顶栏不动）
//   desktop-1280.png        桌面回归
// 走 chat-head-position-shots 同款静态 harness（无后端：routeWebSocket 供
// configData/sessionList/historyPage，fm_api_mock 供登录态，catch-all API 200）。
// Run: node tests/muse-avatar-shrink-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261102_muse-avatar-shrink');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'shrink-root-session';

// 夹具头像：珊瑚底白字「S」圆牌（与 muse-avatar / dock-shape harness 同族）。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="156" height="156"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="156" height="156" rx="78" fill="url(#g)"/><text x="78" y="102" font-family="-apple-system,sans-serif" font-size="64" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

// 历史夹具：12 条长文（375×812 必出滚动 ⇒ 滚动态可锚定）。
const LONG = '这次真重启完成了（新引擎 pid=30450，13:23 就绪）：核查干净零残留会话，所有批都在重启前落了地；新代码已生效，待办圆框、主 agent stand logo、长按退役、文件图片 footer、手机空白根治全在里面；手机和电脑各刷新一次就能看到，消息列表应该恢复正常了。';
const HISTORY = [];
for (let i = 0; i < 12; i++) {
  HISTORY.push({ type: i % 2 === 0 ? 'user' : 'ai', text: i % 2 === 0 ? `第 ${Math.floor(i / 2) + 1} 轮：${LONG.slice(0, 40)}` : `第 ${Math.floor(i / 2) + 1} 轮回复：${LONG}`, timestamp: Date.now() - (600 - i * 30) * 1e3 });
}

mkdirSync(OUT, { recursive: true });
if (!existsSync(join(WEB, 'index.html'))) { console.error('web root missing: ' + WEB); process.exit(2); }

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

/** 页内状态探针。 */
async function probeState(page) {
  return page.evaluate(() => {
    const r = (el) => el.getBoundingClientRect();
    const chat = document.getElementById('chat');
    const head = document.querySelector('#chat > .g2-chat-head');
    const av = r(document.getElementById('agent-avatar-btn'));
    const nameEl = document.getElementById('session-name');
    const nr = nameEl ? r(nameEl) : null;
    const chatBox = r(chat);
    const headBox = head ? r(head) : null;
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headTop: headBox ? headBox.top : null,
      headBottom: headBox ? headBox.bottom : null,
      chatTop: chatBox.top,
      avW: av.width, avH: av.height,
      avTop: av.top, avBottom: av.bottom,
      avCx: (av.left + av.right) / 2,
      chatCx: (chatBox.left + chatBox.right) / 2,
      nameTop: nr ? nr.top : -1,
      nameBottom: nr ? nr.bottom : -1,
      nameLeft: nr ? nr.left : -1,
      nameCx: nr ? (nr.left + nr.right) / 2 : -1,
      nameFont: nameEl ? getComputedStyle(nameEl).fontSize : '',
      nameText: nameEl ? nameEl.textContent : null,
      headPos: head ? getComputedStyle(head).position : 'absent',
      scrollTop: chat.scrollTop,
    };
  });
}

/** 手机腿：375×812 × 双主题。 */
async function runMobile(scheme) {
  const errors = [];
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(({ avatar }) => {
      for (const ns of ['stand', 'nebflow']) {
        localStorage.setItem(`${ns}_token`, 'e2e-token');
        localStorage.setItem(`${ns}_locale`, 'zh-CN');
        if (avatar) localStorage.setItem(`${ns}_agent_avatar_Nebula`, JSON.stringify({ dataUrl: avatar, sha: 'evidence-seed', at: Date.now() }));
      }
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
      }));
    }, { avatar: AV });

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/') || p.startsWith('/agents/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
        if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) });
        if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
      catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });

    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
      const sessions = [{ id: SID, name: 'root', agentName: 'Nebula' }];
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions, folders: [], activeId: SID }));
      sendConfig(); sendSessions();
      ws.onMessage((raw) => {
        let parsed; try { parsed = JSON.parse(raw); } catch { return; }
        if (parsed.type === 'getConfig') sendConfig();
        else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
        else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
      });
    });

    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1 && document.body.classList.contains('mobile-layer');
    }, SID, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 6, { timeout: 20000 });
    await page.waitForFunction(() => {
      const slot = document.getElementById('agent-avatar-btn');
      const img = slot?.querySelector('.agent-avatar-img');
      return !!img && img.complete && img.naturalWidth > 0;
    }, { timeout: 20000 });
    await page.waitForTimeout(700);

    // ── 静止态：回滚到顶 ────────────────────────────────────────────
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(250);
    const rest = await probeState(page);
    ok(`${scheme} ① 头像 40px（单行档取上沿）`,
      Math.abs(rest.avW - 40) <= 0.8 && Math.abs(rest.avH - 40) <= 0.8, `${rest.avW.toFixed(1)}×${rest.avH.toFixed(1)}`);
    ok(`${scheme} ② 名字横排挂头像右侧（间距 8px ±1.5）`,
      rest.nameTop >= rest.avTop - 0.5 && rest.nameBottom <= rest.avBottom + 0.5
        && Math.abs((rest.nameLeft - (rest.avCx + rest.avW / 2)) - 8) <= 1.5,
      `nameTop=${rest.nameTop.toFixed(1)} nameBottom=${rest.nameBottom.toFixed(1)} avTop=${rest.avTop.toFixed(1)} avBottom=${rest.avBottom.toFixed(1)}`);
    ok(`${scheme} ③ 顶栏 fixed 贴窗口顶（headTop == 0 ±1；64px 内容流锚退役）`,
      rest.headPos === 'fixed' && Math.abs(rest.headTop - 0) <= 1,
      `pos=${rest.headPos} headTop=${rest.headTop.toFixed(1)} headBottom=${rest.headBottom.toFixed(1)}`);
    ok(`${scheme} ④ 头像中心 == 屏幕中线 ±1px`,
      Math.abs(rest.avCx - 187.5) <= 1, `avCx=${rest.avCx.toFixed(1)} mid=187.5`);
    ok(`${scheme} ⑤ 名字字号 14px + 顶栏 flex 单行`,
      rest.nameFont === '14px', `font=${rest.nameFont}`);
    await page.screenshot({ path: join(OUT, `rest-${scheme}.png`) });

    // ── 滚动态：中段位置（顶栏 fixed，必须纹丝不动）─────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = Math.round((c.scrollHeight - c.clientHeight) * 0.55); });
    await page.waitForTimeout(350);
    const mid = await probeState(page);
    ok(`${scheme} ⑤ 滚动中顶栏不动（headTop/headBottom 逐值不变）`,
      mid.scrollTop > 0 && mid.headTop === rest.headTop && mid.headBottom === rest.headBottom,
      `headTop=${mid.headTop}/${rest.headTop} headBottom=${mid.headBottom}/${rest.headBottom} scrollTop=${mid.scrollTop}`);
    ok(`${scheme} ⑤ 滚动中头像仍 40px + 名字仍横排`,
      Math.abs(mid.avW - 40) <= 0.8 && mid.nameTop >= mid.avTop - 0.5 && mid.nameBottom <= mid.avBottom + 0.5,
      `av=${mid.avW.toFixed(1)} nameTop=${mid.nameTop.toFixed(1)} avTop=${mid.avTop.toFixed(1)}`);
    await page.screenshot({ path: join(OUT, `scrolled-${scheme}.png`) });

    ok(`${scheme} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 桌面回归腿（1280×800）：#chat 零身份头、#header 头像钮 28px 零触碰。 */
async function runDesktop() {
  const errors = [];
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(() => {
      for (const ns of ['stand', 'nebflow']) {
        localStorage.setItem(`${ns}_token`, 'e2e-token');
        localStorage.setItem(`${ns}_locale`, 'zh-CN');
      }
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
      }));
    });
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/') || p.startsWith('/agents/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
        if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) });
        if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
      catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
      const sessions = [{ id: SID, name: 'root', agentName: 'Nebula' }];
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions, folders: [], activeId: SID }));
      sendConfig(); sendSessions();
      ws.onMessage((raw) => {
        let parsed; try { parsed = JSON.parse(raw); } catch { return; }
        if (parsed.type === 'getConfig') sendConfig();
        else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
        else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY.slice(0, 4), hasMore: false, offset: 0, total: 4 }));
      });
    });
    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
    }, SID, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 2, { timeout: 20000 });
    await page.waitForTimeout(700);
    const d = await page.evaluate(() => {
      const slot = document.getElementById('agent-avatar-btn');
      const box = slot?.getBoundingClientRect();
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        noChatHead: !document.querySelector('#chat > .g2-chat-head'),
        slotW: box ? box.width : -1,
        slotH: box ? box.height : -1,
      };
    });
    ok('desktop-1280 ⑦ 桌面零触碰（#chat 无身份头 + #header 头像钮 28px）',
      !d.mobileLayer && d.noChatHead && Math.abs(d.slotW - 28) <= 0.6 && Math.abs(d.slotH - 28) <= 0.6,
      `head=${d.noChatHead} av=${d.slotW}×${d.slotH}`);
    await page.screenshot({ path: join(OUT, 'desktop-1280.png') });
    ok('desktop-1280 R0 零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

for (const scheme of ['light', 'dark']) await runMobile(scheme);
await runDesktop();

console.log(`═══ muse-avatar-shrink-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
