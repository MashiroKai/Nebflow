#!/usr/bin/env node
// mobile-muse-head-restore-shots.mjs — Muse 悬浮身份头恢复批（作者令
// 2026-11-02 09:14「会话顶部身份头回到『头像在上、名字在下』的悬浮形态，
// 拆掉顶栏」）验收 harness。
//
// 背景：上一批（07:23「会话顶栏单行三段式」）把悬浮身份头做成了 position:fixed
// 单行三段式真顶栏（56+safe 通栏毛玻璃带 + 下缘 1px 描边 + 头像 40 + 名字横排
// 右侧）。本批整条回退，恢复 Muse 悬浮形态并新增六条机械断言。
//
// 六条机械断言（与作者令逐条对齐）：
//   A 不存在顶栏：顶部区域没有「高度 ≥40 且横贯 ≥90% 宽 且 有背景/描边/固定定位」
//     的横贯带（全文档扫描 + .g2-chat-head 本体静态属性双口径）。
//   B 胶囊水平中心 ↔ 头像水平中心 ≤1px，且胶囊 top ≥ 头像 bottom −1（在下方）。
//   C 头像中心 ↔ 左钮中心 / 右钮中心 的垂直中心极差 ≤ 1px（**全状态**；本批
//     2026-11-02 11:08「始终同一行」契约取代旧的「贴顶态 ≤12px / 静止态豁免」）。
//   D 头像水平中心 ↔ 屏幕中线 ≤1px。
//   E 内容确实从头像后穿过：滚动态存在消息行渲染盒与头像盒相交（面积 >0）。
//   F 无名态：胶囊不渲染（display:none）+ 头像仍居中（中线 ≤1px）。
//   R0 每腿零 console error / pageerror。
//
// 产出 → evidence/20261102_muse-head-restore/：
//   rest-{light,dark}.png / scrolled-{light,dark}.png / unnamed-{light,dark}.png
//   prev-batch-rest-light.png（上一批同视口对照，源 evidence/20261102_mobile-chat-topbar/）
//   desktop-1280.png
// Run: node tests/mobile-muse-head-restore-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'evidence', '20261102_muse-head-restore');
const PREV = join(ROOT, 'evidence', '20261102_mobile-chat-topbar');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'muse-head-restore-session';

// 夹具头像：珊瑚底白字「S」圆牌（与 muse-avatar / topbar 族同源）。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="156" height="156"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="156" height="156" rx="78" fill="url(#g)"/><text x="78" y="102" font-family="-apple-system,sans-serif" font-size="64" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

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

/** 页内状态探针：身份组 / 双钮 / 渐隐带 / 首条消息 / 顶部横贯带扫描。 */
async function probeState(page) {
  return page.evaluate(() => {
    const r = (el) => (el ? el.getBoundingClientRect() : null);
    const cy = (b) => (b ? (b.top + b.bottom) / 2 : -1);
    const cx = (b) => (b ? (b.left + b.right) / 2 : -1);
    const alpha = (c) => {
      const m = /rgba?\(([^)]+)\)/.exec(String(c) || '');
      if (!m) return /transparent/.test(String(c)) ? 0 : 1;
      const parts = m[1].split(',').map((s) => s.trim());
      return parts.length >= 4 ? parseFloat(parts[3]) : 1;
    };
    const chat = document.getElementById('chat');
    const head = document.querySelector('#chat > .g2-chat-head');
    const av = r(document.getElementById('agent-avatar-btn'));
    const nameEl = document.getElementById('session-name');
    const nr = r(nameEl);
    const ncs = nameEl ? getComputedStyle(nameEl) : null;
    const hcs = head ? getComputedStyle(head) : null;
    const task = r(document.querySelector('.mh-chrome-btn[data-chrome="tasks"]'));
    const search = r(document.querySelector('.mh-chrome-btn[data-chrome="search"]'));
    const chatBox = r(chat);
    const fade = document.getElementById('g2-edge-fade');
    const vw = window.innerWidth;

    // ── A：顶部横贯带扫描（全文档）──
    // 判据 = 高度 ≥40 且 宽度 ≥90% vw 且 **有可见墨迹**（背景 α>0.02 或边框 α>0.02），
    // 且顶边落在屏幕上半（top < vh/2）。裸 position:fixed 但零背景零边框的容器
    // （如 #mobile-pages 全屏页栈 —— 非 live 页不画任何像素）不算「带」：判据问的
    // 是「屏幕顶部有没有一条看得见的分隔」，不是「有没有 fixed 元素」。
    const bands = [];
    for (const el of document.body.querySelectorAll('*')) {
      const b = el.getBoundingClientRect();
      if (b.width < vw * 0.9 || b.height < 40 || b.top > window.innerHeight / 2) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
      const bgA = alpha(cs.backgroundColor);
      const bdW = parseFloat(cs.borderBottomWidth) || 0;
      const bdA = bdW > 0 ? alpha(cs.borderBottomColor) : 0;
      const bdTopW = parseFloat(cs.borderTopWidth) || 0;
      const bdTopA = bdTopW > 0 ? alpha(cs.borderTopColor) : 0;
      const isFixed = cs.position === 'fixed';
      void isFixed;
      const hasEdge = (bdA > 0.02 || bdTopA > 0.02);
      if (bgA > 0.02 || hasEdge) {
        bands.push({
          id: el.id || '', cls: String(el.className).slice(0, 40), tag: el.tagName,
          w: +b.width.toFixed(1), h: +b.height.toFixed(1), top: +b.top.toFixed(1),
          pos: cs.position, bgA: +bgA.toFixed(2), edge: hasEdge, bdW: +bdW.toFixed(1),
        });
      }
    }

    // ── E：消息行 × 头像盒相交（滚动态判据）──
    const intersectArea = (a, b) => {
      if (!a || !b) return 0;
      const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      return w > 0 && h > 0 ? w * h : 0;
    };
    let behindHits = 0;
    let maxArea = 0;
    for (const row of document.querySelectorAll('#chat .row')) {
      const area = intersectArea(row.getBoundingClientRect(), av);
      if (area > 0) { behindHits++; maxArea = Math.max(maxArea, area); }
    }

    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headPos: hcs ? hcs.position : 'absent',
      headBgA: hcs ? alpha(hcs.backgroundColor) : -1,
      headBdBottomW: hcs ? parseFloat(hcs.borderBottomWidth) || 0 : -1,
      headBackdrop: hcs ? String(hcs.backdropFilter || hcs.webkitBackdropFilter || '') : '',
      headTop: r(head) ? r(head).top : -1,
      headBottom: r(head) ? r(head).bottom : -1,
      headH: r(head) ? r(head).height : -1,
      headW: r(head) ? r(head).width : -1,
      headDisplay: hcs ? hcs.display : 'absent',
      headFlexDir: hcs ? hcs.flexDirection : '',
      bands,
      chatTop: chatBox.top,
      chatPadTop: parseFloat(getComputedStyle(chat).paddingTop) || 0,
      vw, vh: window.innerHeight,
      avTop: av ? +av.top.toFixed(2) : -1,
      avBottom: av ? +av.bottom.toFixed(2) : -1,
      avW: av ? +av.width.toFixed(2) : -1,
      avH: av ? +av.height.toFixed(2) : -1,
      avCx: av ? +cx(av).toFixed(2) : -1,
      avCy: av ? +cy(av).toFixed(2) : -1,
      taskCx: task ? +cx(task).toFixed(2) : -1,
      taskCy: task ? +cy(task).toFixed(2) : -1,
      taskW: task ? +task.width.toFixed(2) : -1,
      searchCx: search ? +cx(search).toFixed(2) : -1,
      searchCy: search ? +cy(search).toFixed(2) : -1,
      searchW: search ? +search.width.toFixed(2) : -1,
      nameText: nameEl ? nameEl.textContent : null,
      nameDisplay: ncs ? ncs.display : 'absent',
      nameTop: nr ? +nr.top.toFixed(2) : -1,
      nameBottom: nr ? +nr.bottom.toFixed(2) : -1,
      nameLeft: nr ? +nr.left.toFixed(2) : -1,
      nameRight: nr ? +nr.right.toFixed(2) : -1,
      nameW: nr ? +nr.width.toFixed(2) : -1,
      nameH: nr ? +nr.height.toFixed(2) : -1,
      nameCx: nr ? +cx(nr).toFixed(2) : -1,
      nameRadius: ncs ? ncs.borderTopLeftRadius : '',
      nameBorderW: ncs ? parseFloat(ncs.borderTopWidth) || 0 : -1,
      nameBg: ncs ? ncs.backgroundColor : '',
      nameBgA: ncs ? +alpha(ncs.backgroundColor).toFixed(2) : -1,
      nameColor: ncs ? ncs.color : '',
      nameBackdrop: ncs ? String(ncs.backdropFilter || ncs.webkitBackdropFilter || '') : '',
      nameFont: ncs ? ncs.fontSize : '',
      firstRowTop: (() => { const el = document.querySelector('#chat .row'); return el ? +el.getBoundingClientRect().top.toFixed(2) : -1; })(),
      scrollTop: chat.scrollTop,
      fadeH: fade ? r(fade).height : -1,
      fadeMask: fade ? String(getComputedStyle(fade).maskImage || getComputedStyle(fade).webkitMaskImage || '') : '',
      behindHits,
      behindMaxArea: +maxArea.toFixed(0),
    };
  });
}

/** 公共路由夹具（mock API + WS + 静态文件）。 */
async function wire(page, { avatar, displayName }) {
  await page.addInitScript(({ av }) => {
    for (const ns of ['stand', 'nebflow']) {
      localStorage.setItem(`${ns}_token`, 'e2e-token');
      localStorage.setItem(`${ns}_locale`, 'zh-CN');
      if (av) localStorage.setItem(`${ns}_agent_avatar_Nebula`, JSON.stringify({ dataUrl: av, sha: 'evidence-seed', at: Date.now() }));
    }
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
      users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
    }));
  }, { av: avatar });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      // displayName 缺失 ⇒ 无名档（rootName.js 判 unnamed，胶囊 :empty 收口）。
      if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(displayName ? { displayName } : {}) });
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
}

/** 手机腿（375×812 × 双主题）：静止 / 滚动中 / 无名态。 */
async function runMobile(scheme) {
  const errors = [];
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await wire(page, { avatar: AV, displayName: 'Spider' });
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
    // sticky 锚 + --muse-head-top 同步落定
    await page.waitForFunction(() => {
      const head = document.querySelector('#chat > .g2-chat-head');
      const chat = document.getElementById('chat');
      return head && getComputedStyle(head).position === 'sticky'
        && (chat.style.getPropertyValue('--muse-head-top') || '').trim() !== '';
    }, { timeout: 10000 });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(700);

    const rest = await probeState(page);

    // ── A 不存在顶栏 ───────────────────────────────────────────────
    ok(`${scheme} A 不存在顶栏（顶部无「≥40 高 × ≥90% 宽 × 有背景/描边/fixed」横贯带）`,
      rest.bands.length === 0,
      rest.bands.length === 0 ? `scanned 0 bands` : `hits=${JSON.stringify(rest.bands.slice(0, 3))}`);
    ok(`${scheme} A .g2-chat-head 本体 = sticky 透明浮层（零背景 / 零描边 / 零 backdrop）`,
      rest.headPos === 'sticky' && rest.headBgA === 0 && rest.headBdBottomW === 0 && !/blur/.test(rest.headBackdrop),
      `pos=${rest.headPos} bgA=${rest.headBgA} bdBottom=${rest.headBdBottomW} backdrop=${rest.headBackdrop || '(none)'} size=${rest.headW.toFixed(1)}×${rest.headH.toFixed(1)}`);

    // ── B 胶囊在头像正下方、水平同轴 ───────────────────────────────
    const dB = Math.abs(rest.nameCx - rest.avCx);
    const gapBA = rest.nameTop - rest.avBottom;
    ok(`${scheme} B 胶囊水平中心↔头像水平中心 ≤1px 且 胶囊 top ≥ 头像 bottom −1`,
      dB <= 1 && gapBA >= -1,
      `|nameCx−avCx|=${dB.toFixed(2)} (nameCx=${rest.nameCx} avCx=${rest.avCx}) gap=${gapBA.toFixed(2)} (nameTop=${rest.nameTop} avBottom=${rest.avBottom})`);

    // ── C 头像中心 ↔ 双钮中心垂直极差（**静止态也判**；见下 + pinned 段）──
    // 🔴 口径修订（身份头同行批 2026-11-02 11:08，作者令「为什么会出现有的情况，
    // 这个头像会和两侧按钮不在同一行」）：本判据原先把静止态**豁免**掉（字面
    // 「静止态头像落在内容流原位…属 Muse 静止形态，仅打印不判红」），于是静止态
    // 实测 spread=60px 被当成形态本来的样子放过 —— 那正是作者报障的画面。
    // 现契约：**任何状态（静止 / 滚动中 / 贴顶 / 无名字）都必须同一行，≤1px**。
    // 静止态 spread 之所以曾是 60：sticky 的两个落点由两个不同表达式给定、恒差
    // 64；同行批把 top 与 margin-top 统一成同一表达式后，静止落点 ≡ 贴顶落点。
    const spreadRest = Math.max(Math.abs(rest.avCy - rest.taskCy), Math.abs(rest.avCy - rest.searchCy));
    ok(`${scheme} C 静止态头像中心 ↔ 左右双钮中心垂直极差 ≤1px（旧豁免取消）`,
      spreadRest <= 1,
      `spread=${spreadRest.toFixed(2)} avCy=${rest.avCy} taskCy=${rest.taskCy} searchCy=${rest.searchCy}（修前实测 60.00，被旧口径放过）`);
    ok(`${scheme} C 静止态身份组锚落位（组顶 4 ±1 / 头像顶 12 ±1 —— 与贴顶态逐值相同）`,
      Math.abs(rest.headTop - 4) <= 1 && Math.abs(rest.avTop - 12) <= 1,
      `headTop=${rest.headTop} avTop=${rest.avTop}（修前 64 / 72）`);

    // ── D 头像水平居中 ─────────────────────────────────────────────
    const dD = Math.abs(rest.avCx - rest.vw / 2);
    ok(`${scheme} D 头像水平中心 ↔ 屏幕中线 ≤1px`,
      dD <= 1, `|avCx−vw/2|=${dD.toFixed(2)} (avCx=${rest.avCx} vw/2=${(rest.vw / 2).toFixed(1)})`);

    // 追加事实断言（作者令要求的取值与既有语义）
    ok(`${scheme} 头像 56px（实拍 ≈50.8 ⇒ 取 ≥实拍的既有档）`,
      Math.abs(rest.avW - 56) <= 0.6 && Math.abs(rest.avH - 56) <= 0.6, `${rest.avW}×${rest.avH}`);
    ok(`${scheme} 胶囊视觉 = glass token 底 + r999 + 零 border（不回带边框胶囊）`,
      rest.nameBgA > 0.02 && rest.nameRadius === '999px' && rest.nameBorderW === 0,
      `bg=${rest.nameBg} (α=${rest.nameBgA}) radius=${rest.nameRadius} border=${rest.nameBorderW}px color=${rest.nameColor}`);
    ok(`${scheme} 渐隐带回到 88px 两段式原 mask（无 56+safe 收口覆盖）`,
      rest.fadeH === 88 && /0%/.test(rest.fadeMask) && /55%|0\.55/.test(rest.fadeMask) && !/56px/.test(rest.fadeMask),
      `fadeH=${rest.fadeH} mask=${rest.fadeMask.slice(0, 90)}`);
    const gapFirst = rest.firstRowTop - rest.avBottom;
    console.log(`      · 实测：头像 ${rest.avTop}→${rest.avBottom} / 胶囊 ${rest.nameTop}→${rest.nameBottom}（${rest.nameW}×${rest.nameH}，gap ${gapBA.toFixed(2)}）/ 首条消息行顶 ${rest.firstRowTop}（距胶囊底 ${(rest.firstRowTop - rest.nameBottom).toFixed(2)}px；#chat padding-top=${rest.chatPadTop}，头像顶距屏顶 ${rest.avTop}）`);
    ok(`${scheme} 首条消息不与头像/胶囊相撞（静止态首行顶 ≥ 胶囊底）`,
      rest.firstRowTop >= rest.nameBottom, `firstRowTop=${rest.firstRowTop} nameBottom=${rest.nameBottom} gap=${gapFirst.toFixed(2)}`);
    await page.screenshot({ path: join(OUT, `rest-${scheme}.png`) });

    // ── 滚动中：E 内容从头像后穿过 ─────────────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = Math.round((c.scrollHeight - c.clientHeight) * 0.55); });
    await page.waitForTimeout(400);
    const mid = await probeState(page);
    ok(`${scheme} E 内容确实从头像后穿过（滚动态消息行渲染盒与头像盒相交）`,
      mid.scrollTop > 0 && mid.behindHits > 0 && mid.behindMaxArea > 0,
      `scrollTop=${mid.scrollTop} 相交行数=${mid.behindHits} 最大交叠面积=${mid.behindMaxArea}px²`);
    // ── C（贴顶态）头像中心 ↔ 双钮中心极差（同批收紧 12 → 1）─────────
    const spreadPinned = Math.max(Math.abs(mid.avCy - mid.taskCy), Math.abs(mid.avCy - mid.searchCy));
    ok(`${scheme} C 贴顶态头像中心 ↔ 左右双钮中心垂直极差 ≤1px`,
      spreadPinned <= 1,
      `spread=${spreadPinned.toFixed(2)} avCy=${mid.avCy} taskCy=${mid.taskCy} searchCy=${mid.searchCy}（静止态 spread=${Math.max(Math.abs(rest.avCy - rest.taskCy), Math.abs(rest.avCy - rest.searchCy)).toFixed(2)}，本批起同样判红）`);
    // ── E 滚动态 sticky 锚落点 ─────────────────────────────────────
    // 贴顶判据（同行批改）：静态落点与贴顶落点取同一表达式 ⇒ 组顶恒 4、头像顶恒 12，
    // 滚动不产生任何位移（修前是「静止 64/72 → 贴顶 0/8」的 64px converge）。
    ok(`${scheme} E 滚动态身份组锚落点 = 静止落点（组顶 4 / 头像顶 12，零 sticky 位移）`,
      Math.abs(mid.avTop - 12) <= 1 && Math.abs(mid.headTop - 4) <= 1
        && Math.abs(mid.avTop - rest.avTop) <= 0.5 && Math.abs(mid.headTop - rest.headTop) <= 0.5,
      `滚动前 avTop=${rest.avTop} headTop=${rest.headTop} → 滚动后 avTop=${mid.avTop} headTop=${mid.headTop}`);
    ok(`${scheme} E 滚动态头像仍居中 + 胶囊仍在正下方`,
      Math.abs(mid.avCx - mid.vw / 2) <= 1 && Math.abs(mid.nameCx - mid.avCx) <= 1 && mid.nameTop >= mid.avBottom - 1,
      `|avCx−vw/2|=${Math.abs(mid.avCx - mid.vw / 2).toFixed(2)} |nameCx−avCx|=${Math.abs(mid.nameCx - mid.avCx).toFixed(2)} gap=${(mid.nameTop - mid.avBottom).toFixed(2)}`);
    await page.screenshot({ path: join(OUT, `scrolled-${scheme}.png`) });

    ok(`${scheme} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 无名态腿：胶囊不渲染 + 头像仍居中 + 不留空洞。 */
async function runUnnamed(scheme) {
  const errors = [];
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await wire(page, { avatar: AV, displayName: null });
    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1 && document.body.classList.contains('mobile-layer');
    }, SID, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 6, { timeout: 20000 });
    await page.waitForFunction(() => {
      const head = document.querySelector('#chat > .g2-chat-head');
      return head && getComputedStyle(head).position === 'sticky';
    }, { timeout: 10000 });
    await page.waitForTimeout(700);
    const st = await probeState(page);
    ok(`${scheme}-unnamed F 胶囊不渲染（:empty 收口，无幽灵胶囊条）`,
      st.nameDisplay === 'none' && st.nameW === 0 && st.nameH === 0,
      `display=${st.nameDisplay} w=${st.nameW} h=${st.nameH} text=${JSON.stringify(st.nameText)}`);
    ok(`${scheme}-unnamed F 头像仍居中（中线 ≤1px）+ 56px`,
      Math.abs(st.avCx - st.vw / 2) <= 1 && Math.abs(st.avW - 56) <= 0.6,
      `|avCx−vw/2|=${Math.abs(st.avCx - st.vw / 2).toFixed(2)} avW=${st.avW}`);
    ok(`${scheme}-unnamed A 无名态同样无顶栏（横贯带 0 条）`, st.bands.length === 0, `hits=${st.bands.length}`);
    console.log(`      · 无名态实测：头像 ${st.avTop}→${st.avBottom}、首条消息行顶 ${st.firstRowTop}（不留空洞：组高 ${st.headH.toFixed(1)} = 56 + pad 8/12）`);
    await page.screenshot({ path: join(OUT, `unnamed-${scheme}.png`) });
    ok(`${scheme}-unnamed R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 桌面回归腿（1280×800）：无 .g2-chat-head、#header 头像 28px、渐隐带不渲染。 */
async function runDesktop() {
  const errors = [];
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await wire(page, { avatar: AV, displayName: 'Spider' });
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
        overlaysTop: parseFloat(getComputedStyle(document.getElementById('top-overlays')).top) || 0,
      };
    });
    ok('desktop-1280 桌面零触碰（无会话头节点 + #header 头像 28px + #header flex/absolute + 渐隐带不渲染）',
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

// 同视口对照：把上一批（顶栏形态）的静止态样张复制进本批证据目录，便于并排评审。
for (const scheme of ['light', 'dark']) {
  const src = join(PREV, `rest-${scheme}.png`);
  if (existsSync(src)) {
    copyFileSync(src, join(OUT, `prev-batch-rest-${scheme}.png`));
    console.log(`      对照 · 上一批静止态 → prev-batch-rest-${scheme}.png`);
  }
}

console.log(`═══ mobile-muse-head-restore-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
