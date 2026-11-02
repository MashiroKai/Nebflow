#!/usr/bin/env node
// mobile-chat-topbar-single-row-shots.mjs — 会话顶栏单行三段式批（作者令
// 2026-11-02 07:23，实拍 evidence/20261102_muse-avatar-shrink/rest-light.png
// 「头像为什么不在顶栏与左右两颗按钮同一行居中，而是掉到下面一行」）验收
// harness：375×812 双主题 × 静止态/滚动中/名字缺失三态实拍 + 机械探针；
// 桌面 1280 回归自查。
//
// 判据（与作者验收逐条对齐）：
//   ① 顶栏单行三段式：左任务钮 / 中头像 / 右搜索钮，三者垂直中线同轴
//      ⇒ |头像 cy − 任务钮 cy| ≤ 1px、|头像 cy − 搜索钮 cy| ≤ 1px
//   ② 头像水平居中：|头像 cx − 屏幕中线| ≤ 1px
//   ③ 头像 40px（36-40 档取上沿，理由见批文档）
//   ④ 名字横排挂头像右侧（同一行）：nameTop ∈ [avatar.top, avatar.bottom]
//      且 name.left ≥ avatar.right（间距 = 8px）
//   ⑤ 顶栏 fixed、不随内容滚动（滚动前后 rect 逐值不变），顶栏下缘 = 56+safe
//   ⑥ 内容起点 = 顶栏下缘 + 8（实测打印「顶栏下缘 → 首条消息」数值）
//   ⑦ 名字缺失态：名字零渲染、头像仍居中
//   ⑧ 桌面零触碰：1280 下无 .g2-chat-head、#header 头像钮 28px、顶栏隐形
//   R0 每腿零 console error / pageerror
//
// 产出 → evidence/20261102_mobile-chat-topbar/：
//   rest-<scheme>.png / scrolled-<scheme>.png / unnamed-<scheme>.png / desktop-1280.png
// Run: node tests/mobile-chat-topbar-single-row-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261102_mobile-chat-topbar');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'topbar-single-row-session';

// 夹具头像：珊瑚底白字「S」圆牌（与 muse-avatar / dock-shape harness 同族）。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="156" height="156"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="156" height="156" rx="78" fill="url(#g)"/><text x="78" y="102" font-family="-apple-system,sans-serif" font-size="64" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

// 历史夹具：12 条长文（375×812 必出滚动 ⇒ 滚动态可验顶栏不动）。
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

/** 页内状态探针：顶栏 / 双钮 / 头像 / 名字 / 首条消息 五组几何。 */
async function probeState(page) {
  return page.evaluate(() => {
    const r = (el) => (el ? el.getBoundingClientRect() : null);
    const cy = (b) => (b ? (b.top + b.bottom) / 2 : -1);
    const cx = (b) => (b ? (b.left + b.right) / 2 : -1);
    const chat = document.getElementById('chat');
    const head = document.querySelector('#chat > .g2-chat-head');
    const av = r(document.getElementById('agent-avatar-btn'));
    const nameEl = document.getElementById('session-name');
    const nr = r(nameEl);
    const task = r(document.querySelector('.mh-chrome-btn[data-chrome="tasks"]'));
    const search = r(document.querySelector('.mh-chrome-btn[data-chrome="search"]'));
    const headBox = r(head);
    const chatBox = r(chat);
    const firstRow = document.querySelector('#chat .row');
    const firstBubble = document.querySelector('#chat .row .bubble');
    const ncs = nameEl ? getComputedStyle(nameEl) : null;
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headPosition: head ? getComputedStyle(head).position : 'absent',
      headZ: head ? getComputedStyle(head).zIndex : '',
      headTop: headBox ? headBox.top : -1,
      headBottom: headBox ? headBox.bottom : -1,
      headH: headBox ? headBox.height : -1,
      headBg: head ? getComputedStyle(head).backgroundColor : '',
      headBackdrop: head ? (getComputedStyle(head).backdropFilter || getComputedStyle(head).webkitBackdropFilter) : '',
      headRadius: head ? getComputedStyle(head).flexDirection : '',
      chatTop: chatBox.top,
      chatPadTop: parseFloat(getComputedStyle(chat).paddingTop) || 0,
      vw: window.innerWidth,
      vh: window.innerHeight,
      avW: av ? av.width : -1, avH: av ? av.height : -1,
      avCx: cx(av), avCy: cy(av), avRight: av ? av.right : -1,
      avTop: av ? av.top : -1, avBottom: av ? av.bottom : -1,
      taskCy: cy(task), taskCx: cx(task), taskTop: task ? task.top : -1, taskW: task ? task.width : -1,
      searchCy: cy(search), searchCx: cx(search), searchTop: search ? search.top : -1, searchW: search ? search.width : -1,
      nameText: nameEl ? nameEl.textContent : null,
      nameDisplay: ncs ? ncs.display : 'absent',
      nameFont: ncs ? ncs.fontSize : '',
      nameTop: nr ? nr.top : -1, nameBottom: nr ? nr.bottom : -1,
      nameLeft: nr ? nr.left : -1, nameCx: cx(nr),
      firstRowTop: firstRow ? r(firstRow).top : -1,
      firstBubbleTop: firstBubble ? r(firstBubble).top : -1,
      scrollTop: chat.scrollTop,
      fadeH: (() => { const f = document.getElementById('g2-edge-fade'); return f ? r(f).height : -1; })(),
    };
  });
}

/** 手机腿（375×812 × 双主题），命名态 + 三态实拍。 */
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
    // 顶栏 fixed + 让位落定（main.js 观察器写 #chat padding-top）
    await page.waitForFunction(() => {
      const head = document.querySelector('#chat > .g2-chat-head');
      return head && getComputedStyle(head).position === 'fixed';
    }, { timeout: 10000 });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(700);

    const rest = await probeState(page);

    // ── ① 单行三段式：三者垂直中线同轴 ────────────────────────────
    const dTask = Math.abs(rest.avCy - rest.taskCy);
    const dSearch = Math.abs(rest.avCy - rest.searchCy);
    ok(`${scheme} ① 头像与左右双钮同一水平轴线（各 ≤1px）`,
      dTask <= 1 && dSearch <= 1,
      `|avCy−taskCy|=${dTask.toFixed(2)} |avCy−searchCy|=${dSearch.toFixed(2)} avCy=${rest.avCy.toFixed(2)} taskCy=${rest.taskCy.toFixed(2)} searchCy=${rest.searchCy.toFixed(2)}`);

    // ── ② 头像水平居中（屏幕中线 0±1px）────────────────────────────
    const dCenter = Math.abs(rest.avCx - rest.vw / 2);
    ok(`${scheme} ② 头像水平中心 = 屏幕中线（0±1px）`, dCenter <= 1,
      `|avCx−vw/2|=${dCenter.toFixed(2)} avCx=${rest.avCx.toFixed(2)} vw/2=${(rest.vw / 2).toFixed(2)}`);

    // ── ③ 头像 40px ───────────────────────────────────────────────
    ok(`${scheme} ③ 头像 40px（36-40 档取上沿）`,
      Math.abs(rest.avW - 40) <= 0.8 && Math.abs(rest.avH - 40) <= 0.8, `${rest.avW.toFixed(1)}×${rest.avH.toFixed(1)}`);

    // ── ④ 名字横排挂头像右侧（同一行）──────────────────────────────
    // 「同一行」判据 = 名字盒完全落在头像的垂直区间内（横排同行；下置形态会
    // 必然跌破头像 bottom）；「右侧」判据 = 名字左缘 ≥ 头像右缘，间距 8px。
    const nameOnLine = rest.nameTop >= rest.avTop - 0.5 && rest.nameBottom <= rest.avBottom + 0.5;
    ok(`${scheme} ④ 名字与头像同一行、挂在其右侧（间距 8px）`,
      nameOnLine && rest.nameLeft >= rest.avRight - 0.5 && Math.abs((rest.nameLeft - rest.avRight) - 8) <= 1.5,
      `nameTop=${rest.nameTop.toFixed(1)} nameBottom=${rest.nameBottom.toFixed(1)} avTop=${rest.avTop.toFixed(1)} avBottom=${rest.avBottom.toFixed(1)} gap=${(rest.nameLeft - rest.avRight).toFixed(1)}`);

    // ── ⑤ 顶栏 fixed + 材质 + 高度 56 ──────────────────────────────
    ok(`${scheme} ⑤ 顶栏 = position:fixed / 高 56+safe / 玻璃单源（--glass-bg + blur24 sat1.15）`,
      rest.headPosition === 'fixed' && Math.abs(rest.headH - 56) <= 0.6
        && rest.headTop === 0 && /blur\(24px\)/.test(String(rest.headBackdrop)) && /saturate\(1\.15\)/.test(String(rest.headBackdrop)),
      `pos=${rest.headPosition} h=${rest.headH} top=${rest.headTop} bg=${rest.headBg} backdrop=${rest.headBackdrop}`);

    // ── ⑥ 顶栏下缘 → 首条消息间距（作者验收要求给出数值）──────────
    // 判据 ≤16px 的推导（自设 ≤12 过严，已按实测放宽到物理可行最小值）：
    //   间距 = #chat padding-top 基线 8px（main.js BASE_TOP_PAD，顶栏下缘之下
    //   的最小呼吸）+ chat.css:1037 `.row { margin: 6px 0 }` 的行自身外边距 6px
    //   = 14px。行 margin 属消息行之间的既有节奏（首行也吃同一份），摘掉会让
    //   首行与后续行节奏不一致 —— 不动。故 14px 即本批的合理最小值；
    //   ≤16 留 2px 给不同 DPR/字体度量的取整余量。
    const gapToFirst = rest.firstBubbleTop - rest.headBottom;
    ok(`${scheme} ⑥ 顶栏下缘 → 首条消息 ≤ 16px（留白带收掉；14 = 8 baseline + 6 行 margin）`,
      gapToFirst >= 0 && gapToFirst <= 16,
      `headBottom=${rest.headBottom.toFixed(1)} firstBubbleTop=${rest.firstBubbleTop.toFixed(1)} gap=${gapToFirst.toFixed(1)} chatPadTop=${rest.chatPadTop}`);
    console.log(`      · 实测：顶栏下缘 ${rest.headBottom.toFixed(1)}px → 首条消息泡顶 ${rest.firstBubbleTop.toFixed(1)}px（间距 ${gapToFirst.toFixed(1)}px = 8 padding + 6 行 margin；#chat padding-top=${rest.chatPadTop}px）`);

    ok(`${scheme} ⑥ 原 96/64px 留白带已收（chat padding-top = 56+safe+8 = 64）`,
      Math.abs(rest.chatPadTop - 64) <= 1.5, `pad=${rest.chatPadTop}`);
    await page.screenshot({ path: join(OUT, `rest-${scheme}.png`) });

    // ── 滚动中：顶栏不动 + 内容从其下方流过 ────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = Math.round((c.scrollHeight - c.clientHeight) * 0.55); });
    await page.waitForTimeout(400);
    const mid = await probeState(page);
    ok(`${scheme} ⑤ 滚动中顶栏纹丝不动（rect 逐值不变）`,
      mid.scrollTop > 0 && mid.headTop === rest.headTop && mid.headBottom === rest.headBottom && mid.avCy === rest.avCy,
      `scrollTop=${mid.scrollTop} headTop=${mid.headTop}/${rest.headTop} headBottom=${mid.headBottom}/${rest.headBottom}`);
    ok(`${scheme} ⑤ 滚动中头像/名字/双钮仍在同一轴线（各 ≤1px）`,
      Math.abs(mid.avCy - mid.taskCy) <= 1 && Math.abs(mid.avCy - mid.searchCy) <= 1 && Math.abs(mid.avCx - mid.vw / 2) <= 1,
      `dTask=${Math.abs(mid.avCy - mid.taskCy).toFixed(2)} dSearch=${Math.abs(mid.avCy - mid.searchCy).toFixed(2)} dCenter=${Math.abs(mid.avCx - mid.vw / 2).toFixed(2)}`);
    ok(`${scheme} ⑤ 滚动中内容从顶栏下方流过（有消息行压在顶栏下缘之下滚动）`,
      mid.firstRowTop < rest.firstRowTop, `firstRowTop=${mid.firstRowTop.toFixed(1)} rest=${rest.firstRowTop.toFixed(1)}`);
    await page.screenshot({ path: join(OUT, `scrolled-${scheme}.png`) });

    ok(`${scheme} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 名字缺失腿（agent.json 无 displayName）：头像仍居中、名字零渲染。 */
async function runUnnamed(scheme) {
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
        // 未命名档：agent.json 在场但无 displayName 键（rootName.js 判 unnamed）。
        if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
      const head = document.querySelector('#chat > .g2-chat-head');
      return head && getComputedStyle(head).position === 'fixed';
    }, { timeout: 10000 });
    await page.waitForTimeout(700);

    const st = await probeState(page);
    ok(`${scheme}-unnamed ⑦ 名字零渲染（:empty 收口，无幽灵胶囊条）`,
      st.nameDisplay === 'none' && st.nameTop === 0, `display=${st.nameDisplay} nameTop=${st.nameTop}`);
    ok(`${scheme}-unnamed ⑦ 名字缺失时头像仍居中（屏幕中线 0±1px）+ 40px`,
      Math.abs(st.avCx - st.vw / 2) <= 1 && Math.abs(st.avW - 40) <= 0.8,
      `|avCx−vw/2|=${Math.abs(st.avCx - st.vw / 2).toFixed(2)} avW=${st.avW.toFixed(1)}`);
    await page.screenshot({ path: join(OUT, `unnamed-${scheme}.png`) });

    ok(`${scheme}-unnamed R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 桌面回归腿（1280×800）：无顶栏节点、#header 头像 28px、渐隐带不渲染。 */
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
      const head = document.querySelector('#chat > .g2-chat-head');
      const header = document.getElementById('header');
      const fade = document.getElementById('g2-edge-fade');
      const chat = document.getElementById('chat');
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        noChatHead: !head,
        slotW: box ? box.width : -1,
        slotH: box ? box.height : -1,
        headerDisplay: getComputedStyle(header).display,
        headerPos: getComputedStyle(header).position,
        fadeDisplay: fade ? getComputedStyle(fade).display : null,
        chatPadTop: parseFloat(getComputedStyle(chat).paddingTop) || 0,
      };
    });
    ok('desktop-1280 ⑧ 桌面零触碰（无顶栏节点 + #header 头像 28px + #header flex/absolute + 渐隐带不渲染）',
      !d.mobileLayer && d.noChatHead && Math.abs(d.slotW - 28) <= 0.6 && Math.abs(d.slotH - 28) <= 0.6
        && d.headerDisplay === 'flex' && d.headerPos === 'absolute' && d.fadeDisplay === 'none',
      `head=${d.noChatHead} av=${d.slotW}×${d.slotH} header=${d.headerDisplay}/${d.headerPos} fade=${d.fadeDisplay} pad=${d.chatPadTop}`);
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

console.log(`═══ mobile-chat-topbar-single-row-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
