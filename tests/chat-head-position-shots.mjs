#!/usr/bin/env node
// chat-head-position-shots.mjs — 会话身份组位置批（作者报障 2026-11-01 23:0x
// 「中间 agent 头像和 agent name 的位置这么奇怪」，实拍
// evidence/20261101_dock-avatar-edge-unify/edge-sameframe-dark-avatar.png）验收
// harness：手机 375 × 双主题 × 三态实拍 + 机械探针；桌面 1280 回归自查。
//
// 病灶两条（本 harness 的断言面，实证见批报告）：
//   ① 位置下沉：`.g2-chat-head` 由 chatChrome.js 插在 `#chat` 内容流首 ⇒ 静止态
//      组顶 = `#chat` 的 padding-top（顶让位常态 96px = 渐隐带 88 + 基线 8）⇒
//      96px 纯空带把「会话身份头」推到视觉中段（「头像上方一大块空白、像浮在
//      页面中央」）。
//   ② 名字缺位：未命名 root 下 `#session-name` 被写空串但 `display:''` 保留 ⇒
//      空 span 仍吃胶囊 padding，画出 28×6px 幽灵玻璃条（既非名字也非间距）。
//
// ⚠ 契约改写（会话顶栏单行三段式批 2026-11-02 07:23 作者令）：本 harness 原验
// 的「64px 内容流锚 + 名字下置 + sticky 变形」三件已被新契约整条取代 ——
// .g2-chat-head 现在是 position:fixed 的真顶栏（高 56+safe，头像 40px 与名字
// 横排同行，与左右双钮同轴），--muse-head-top 变量链退役。断言随之改写：
//
// 产出 → evidence/20261101_chat-head-position/：
//   rest-<scheme>.png        静止态（fixed 顶栏单行：头像+名字居中，双钮同轴）
//   scrollmid-<scheme>.png   滚动中（顶栏不动，消息从其下缘穿过）
//   scrolled-<scheme>.png    滚动后（顶栏 rect 逐值不变，渐隐带 88px 在场）
//   unnamed-<scheme>.png     未命名 root 档（②-c 幽灵胶囊收口实证：无名字条）
//   desktop-1280.png         桌面回归（#header 头像行原样、零身份头）
// 探针（新契约）：
//   P① 静止态落位：顶栏贴窗口顶 headTop == 0 ±1、高 56+safe ±0.6（64px 锚退役）
//   P② 单行成组：头像垂直居中于顶栏（avTop − headTop == 8 ±1.5）；名字横排挂
//      头像右侧且垂直落在头像区间内；头像水平中心 = 屏幕中线 ±1
//   P③ 静止态零叠压：消息行矩形与顶栏矩形相交计数 == 0（内容从顶栏下方起）
//   P④ 名字读数：已命名 root ⇒ #session-name 文本 = agent.json displayName
//   P⑤ 滚动态 steadfast：顶栏 headTop/headBottom 逐值不变；消息行穿过其下缘
//      （rowsBehind > 0）；渐隐带 88px 在场；--muse-head-top 变量已退役（空串）
//   P⑥ 幽灵胶囊收口：未命名 root ⇒ #session-name 计算 display none 且盒高 0，
//      且头像仍居中（名字不参与居中）
//   P⑦ 桌面零触及：1280 下 #chat 内零 .g2-chat-head、#header 头像行可绘
//   R0 每腿零 console error / pageerror
// 走 mobile-muse-avatar-shots 同款静态 harness（无后端：routeWebSocket 供
// configData/sessionList/agentList/historyPage，fm_api_mock 供登录态，catch-all
// API 200 防杂错混入零错误断言）。
// Run: node tests/chat-head-position-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_chat-head-position');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'position-root-session';

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
    const avatarBtn = document.getElementById('agent-avatar-btn');
    const nameEl = document.getElementById('session-name');
    const fade = document.getElementById('g2-edge-fade');
    const headCs = head ? getComputedStyle(head) : null;
    const chatBox = r(chat);
    const headBox = head ? r(head) : null;
    const intersect = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
    const rowsBehind = headBox
      ? [...chat.querySelectorAll('.row')].filter((row) => intersect(r(row), headBox) > 4).length
      : -1;
    const av = r(avatarBtn);
    const nr = nameEl ? r(nameEl) : null;
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headerHidden: getComputedStyle(document.getElementById('header')).display === 'none',
      headTop: headBox ? headBox.top : null,
      headBottom: headBox ? headBox.bottom : null,
      chatTop: chatBox.top,
      headHeight: headBox ? headBox.height : null,
      headPos: headCs?.position || 'absent',
      avTop: av.top, avBottom: av.bottom, avH: av.height,
      avCx: (av.left + av.right) / 2, avRight: av.right,
      chatCx: (chatBox.left + chatBox.right) / 2,
      vw: window.innerWidth,
      nameText: nameEl ? nameEl.textContent : null,
      nameDisplay: nameEl ? getComputedStyle(nameEl).display : 'absent',
      nameH: nr ? nr.height : -1,
      nameTop: nr ? nr.top : -1,
      nameBottom: nr ? nr.bottom : -1,
      nameLeft: nr ? nr.left : -1,
      nameCx: nr ? (nr.left + nr.right) / 2 : -1,
      clipTop: getComputedStyle(chat).getPropertyValue('--chat-top-clip').trim(),
      museHeadTop: getComputedStyle(chat).getPropertyValue('--muse-head-top').trim(),
      scrollTop: chat.scrollTop,
      fadeH: r(fade).height,
      rowsBehind,
      aiRows: chat.querySelectorAll('.row.ai').length,
    };
  });
}

/** 手机腿（scheme × 命名档）：三态实拍 + 探针。 */
async function runMobile(scheme, { named = true, tagSuffix = '' } = {}) {
  const tag = `${scheme}${tagSuffix}`;
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
        // 命名腿：生产同款 agent.json（displayName 键存在 ⇒ isRootNamed true）。
        // 未命名腿：404（从未起名）⇒ 空名档，验 ②-c 幽灵胶囊收口。
        if (p === '/agents/Nebula/agent.json') {
          return named
            ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) })
            : route.fulfill({ status: 404, body: 'not found' });
        }
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
      const logo = slot?.querySelector('.agent-avatar-stand-logo img');
      return (!!img && img.complete && img.naturalWidth > 0) || (!!logo && logo.complete && logo.naturalWidth > 0);
    }, { timeout: 20000 });
    await page.waitForTimeout(700); // 顶让位观察器 + rAF markdown 批落定

    // ── 静止态：回滚到顶 ────────────────────────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(250);
    const rest = await probeState(page);
    ok(`${tag} P① 静止态落位 = 贴窗口顶的真顶栏（headTop 0 ±1、高 56 ±0.6；64px 内容流锚退役）`,
      rest.headPos === 'fixed' && Math.abs(rest.headTop - 0) <= 1 && Math.abs(rest.headHeight - 56) <= 0.6,
      `pos=${rest.headPos} headTop=${rest.headTop.toFixed(1)} h=${rest.headHeight} muse-head-top="${rest.museHeadTop}" clip=${rest.clipTop}`);
    ok(`${tag} P② 单行成组：头像垂直居中于顶栏、名字横排挂其右侧、头像水平居中`,
      Math.abs((rest.avTop - rest.headTop) - 8) <= 1.5
        && rest.nameTop >= rest.avTop - 0.5 && rest.nameBottom <= rest.avBottom + 0.5
        && rest.nameLeft >= rest.avRight - 0.5
        && Math.abs(rest.avCx - rest.vw / 2) <= 1,
      `avTop-headTop=${(rest.avTop - rest.headTop).toFixed(1)} onLine=${rest.nameTop >= rest.avTop - 0.5 && rest.nameBottom <= rest.avBottom + 0.5} avCx=${rest.avCx.toFixed(1)} mid=${(rest.vw / 2).toFixed(1)}`);
    ok(`${tag} P③ 静止态零叠压（无消息行与顶栏矩形相交）`, rest.rowsBehind === 0, `rowsBehind=${rest.rowsBehind}`);
    ok(`${tag} P④ 名字读数 = agent.json displayName`, rest.nameText === 'Spider' && rest.nameH > 0,
      `text="${rest.nameText}" h=${rest.nameH} display=${rest.nameDisplay}`);
    await page.screenshot({ path: join(OUT, `rest-${tag}.png`) });

    // ── 滚动中（中段位置：顶栏 fixed，内容从其下缘流过）──────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = Math.round((c.scrollHeight - c.clientHeight) * 0.55); });
    await page.waitForTimeout(350);
    const mid = await probeState(page);
    ok(`${tag} P⑤ 滚动中：顶栏纹丝不动（headTop/headBottom 逐值不变）`,
      mid.scrollTop > 0 && mid.headTop === rest.headTop && mid.headBottom === rest.headBottom,
      `headTop=${mid.headTop}/${rest.headTop} headBottom=${mid.headBottom}/${rest.headBottom} scrollTop=${mid.scrollTop}`);
    ok(`${tag} P⑤ 滚动中：消息行从顶栏下缘穿过`, mid.rowsBehind > 0, `rowsBehind=${mid.rowsBehind}`);
    await page.screenshot({ path: join(OUT, `scrollmid-${tag}.png`) });

    // ── 滚动后（落底停住）：顶栏仍不动 + 渐隐带在场 ──────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight; });
    await page.waitForFunction(() => {
      const chat = document.getElementById('chat');
      return chat.scrollTop + chat.clientHeight >= chat.scrollHeight - 2;
    }, { timeout: 10000 });
    await page.waitForTimeout(300);
    const after = await probeState(page);
    ok(`${tag} P⑤ 滚动后：停住顶栏仍不动（rect 不变）+ 渐隐带 88px 在场 + --muse-head-top 已退役`,
      after.headTop === rest.headTop && after.headBottom === rest.headBottom
        && Math.abs(after.fadeH - 88) <= 1.5 && after.museHeadTop === '',
      `headTop=${after.headTop} headBottom=${after.headBottom} fadeH=${after.fadeH} muse-head-top="${after.museHeadTop}"`);
    await page.screenshot({ path: join(OUT, `scrolled-${tag}.png`) });

    ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
    return { rest, mid, after };
  } finally {
    await browser.close();
  }
}

/** 未命名档（②-c 幽灵胶囊收口）：P⑥ 断言 + 实拍。 */
async function runUnnamed(scheme) {
  const tag = `${scheme}-unnamed`;
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
        // 未命名档：agent.json 存在但无 displayName 键（rootName.js
        // initRootNameProbe 判 unnamed）—— 走 200+{} 而非 404，让零 console
        // error 门只盯真错（404 资源报错会混进断言面）。
        if (/^\/agents\/Nebula\/agent\.json$/.test(p)) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    await page.waitForTimeout(800);
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; });
    await page.waitForTimeout(300);
    const p = await probeState(page);
    ok(`${tag} P⑥ 幽灵胶囊收口：空名 #session-name 退出渲染（display none，盒高 0）`,
      p.nameText === '' && p.nameDisplay === 'none' && p.nameH === 0,
      `text="${p.nameText}" display=${p.nameDisplay} h=${p.nameH}`);
    ok(`${tag} P①′ 未命名档顶栏形态不变（fixed 贴顶、高 56、头像仍居中）`,
      p.headPos === 'fixed' && Math.abs(p.headTop - 0) <= 1 && Math.abs(p.headHeight - 56) <= 0.6
        && Math.abs(p.avCx - p.vw / 2) <= 1,
      `pos=${p.headPos} headTop=${p.headTop} h=${p.headHeight} avCx=${p.avCx} mid=${(p.vw / 2)}`);
    await page.screenshot({ path: join(OUT, `unnamed-${scheme}.png`) });
    ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 桌面回归腿（1280×800）：#header 头像行原样、零身份头。 */
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
        if (/^\/agents\/Nebula\/agent\.json$/.test(p)) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) });
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
      const header = document.getElementById('header');
      const center = header?.querySelector('.header-center');
      const slot = document.getElementById('agent-avatar-btn');
      const slotRect = slot?.getBoundingClientRect();
      const nameEl = document.getElementById('session-name');
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        headerShown: !!header && getComputedStyle(header).display !== 'none',
        slotInHeader: !!slot && !!center && center.contains(slot),
        slotVisible: !!slotRect && slotRect.width > 20 && slotRect.height > 20,
        noChatHead: !document.querySelector('#chat > .g2-chat-head'),
        nameText: nameEl ? nameEl.textContent : null,
        metas: document.querySelectorAll('#chat .g2-msg-meta').length,
        aiRows: document.querySelectorAll('#chat .row.ai').length,
        chatMaskActive: getComputedStyle(document.getElementById('chat')).maskImage !== 'none',
      };
    });
    ok('desktop-1280 P⑦ #header 头像行原样（header-center 含头像钮，可见可绘）',
      !d.mobileLayer && d.headerShown && d.slotInHeader && d.slotVisible, `inHeader=${d.slotInHeader}`);
    ok('desktop-1280 P⑦ 零身份头（本批零桌面触及）', d.noChatHead && d.metas === 0 && d.aiRows > 0 && d.chatMaskActive,
      `head=${d.noChatHead} metas=${d.metas} aiRows=${d.aiRows} mask=${d.chatMaskActive}`);
    await page.screenshot({ path: join(OUT, 'desktop-1280.png') });
    ok('desktop-1280 R0 零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

for (const scheme of ['light', 'dark']) await runMobile(scheme);
for (const scheme of ['light', 'dark']) await runUnnamed(scheme);
await runDesktop();

console.log(`═══ chat-head-position-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
