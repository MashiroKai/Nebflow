#!/usr/bin/env node
// mobile-muse-avatar-shots.mjs — Muse 式悬浮头像批（作者令 2026-11-01 13:33，附
// Muse/stand 两张参考实拍）验收 harness：手机视口（390×844）Chromium + WebKit
// 双引擎 × 深浅双主题实拍 + 机械探针；桌面 1280 回归自查。
// 产出 → evidence/20261101_muse-avatar-mobile/：
//   <engine>-<scheme>-rest.png       静止态（内容首让位渐隐带之下，锚在流内原位）
//   <engine>-<scheme>-pinned.png     滚动态（Muse 形态：锚贴窗口顶、压消息流上缘）
//   <engine>-<scheme>-fallback.png   无自定义头像回落 stand logo 样张
//   desktop-1280.png                 桌面回归（#header 头像行原样，零锚零身份头）
// 探针（与令逐条对齐）：
//   ① 消息流零身份头：#chat 内 .g2-msg-meta / .g2-msg-avatar-slot / .g2-msg-name
//      计数 == 0（消息分组/时间戳/footer 不在本批面 —— 以 ai 行数>0 佐证在演戏台）
//   ② 悬浮锚形态：.g2-chat-head position:sticky / top:0 / z-index:26（> 渐隐带
//      25，头像本体不被 veil 蒙住）；#chat mask 已在手机层释放（堆叠上下文解锁）
//   ③ Muse 几何：大号圆头像 78px、水平居中（与 #chat 可视盒同心）、名字玻璃
//      胶囊在头像正下方居中、名字 = 当前会话对方 agent 身份（Spider 腿实文）
//   ④ 压流锚定（滚动态）：锚顶 ≈ #chat 可视顶（±1.5px）且消息行矩形与锚矩形
//      相交（行从锚上缘穿过）；渐隐带仍在场（88px，未被本批破坏）
//   ⑤ 头像货源：自定义头像腿 = .agent-avatar-img 可绘（现役身份链）；无头像腿
//      = .agent-avatar-stand-logo 可绘（2963aeac0 同货源回落，零默认人形）
//   R0 双引擎双主题零 console error + 桌面腿零 console error
// 走 desktop-avatarfix-shots / mobile-dock-shape-shots 同款静态 harness（无后端：
// routeWebSocket 供 configData/sessionList/agentList/historyPage，fm_api_mock 供
// 登录态，catch-all API 200 防杂错混入零错误断言）。
// Run: node tests/mobile-muse-avatar-shots.mjs

import { chromium, webkit } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_muse-avatar-mobile');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'muse-spider-session';

// 夹具头像：珊瑚底白字「S」圆牌（Spider 身份；与 dock-shape harness 同族）。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="156" height="156"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="156" height="156" rx="78" fill="url(#g)"/><text x="78" y="102" font-family="-apple-system,sans-serif" font-size="64" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

// 历史夹具：Spider agent 会话，12 条长文（390×844 视口必出滚动 ⇒ 滚动态可锚定）。
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

/** 一台手机跑（engine × scheme × 货源腿）：探针 + 实拍。 */
async function runMobile(engineKind, scheme, withAvatar) {
  const tag = `${engineKind}-${scheme}${withAvatar ? '' : '-fallback'}`;
  const errors = [];
  const launcher = engineKind === 'chromium' ? chromium : webkit;
  const browser = await launcher.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(({ avatar }) => {
      // 双拼写写入（brand 命名空间不构成断言面，dock-shape harness 同款纪律）。
      for (const ns of ['stand', 'nebflow']) {
        localStorage.setItem(`${ns}_token`, 'e2e-token');
        localStorage.setItem(`${ns}_locale`, 'zh-CN');
        if (avatar) localStorage.setItem(`${ns}_agent_avatar_Spider`, JSON.stringify({ dataUrl: avatar, sha: 'evidence-seed', at: Date.now() }));
      }
      // stub 掉 SW 注册（静态 harness 无 /sw.js，register 404 会记 console error）。
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
      }));
      // 🔴 fallback 腿：不种 agent_avatar_Spider —— 悬浮锚应自绘 stand logo。
    }, { avatar: withAvatar ? AV : null });

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/') || p.startsWith('/agents/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'spider-proc', agentName: 'Spider' }], activeId: SID }) });
        if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
        // 其余 API 一律 200 '{}'（404 会记 console error 混进零错误断言）。
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
      catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });

    // mock WS：sessionList（Spider 会话，activeId 直落）+ agentList（displayName
    // 读链）+ getHistory 回 12 条长文（滚动可锚定）。
    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
      const sessions = [{ id: SID, name: 'spider-proc', agentName: 'Spider' }];
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions, folders: [], activeId: SID }));
      const sendAgents = () => ws.send(JSON.stringify({ type: 'agentList', agents: [{ name: 'Spider', displayName: 'Spider' }] }));
      sendConfig(); sendSessions(); sendAgents();
      ws.onMessage((raw) => {
        let parsed; try { parsed = JSON.parse(raw); } catch { return; }
        if (parsed.type === 'getConfig') sendConfig();
        else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') { sendSessions(); sendAgents(); }
        else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
      });
    });

    // WebKit 拒绝 blocked port（1 在封口清单）：全引擎走高位闲置端口，
    // route 拦截层短路应答，零真实监听。
    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1 && document.body.classList.contains('mobile-layer');
    }, SID, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 6, { timeout: 20000 });
    // 头像货源落定（自定义腿等 img 可绘；fallback 腿等 logo picture 可绘）。
    await page.waitForFunction((withAv) => {
      const slot = document.getElementById('agent-avatar-btn');
      if (!slot) return false;
      if (withAv) {
        const img = slot.querySelector('.agent-avatar-img');
        return !!img && img.complete && img.naturalWidth > 0;
      }
      const logo = slot.querySelector('.agent-avatar-stand-logo img');
      return !!logo && logo.complete && logo.naturalWidth > 0;
    }, withAvatar, { timeout: 20000 });
    await page.waitForTimeout(700); // 重放 rAF markdown 批 + 顶让位观察器落定

    // ── 静止态探针 + 实拍（先回滚顶：boot 历史重放自动落底，静止态 = scrollTop 0）──
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(250);
    const rest = await probeState(page);
    assertCommon(tag, rest, withAvatar);
    ok(`${tag} 静止态：锚在流内原位（顶让位之下，未锚定）`,
      !rest.pinnedNow && rest.head.top >= rest.chat.top + 88,
      `headTop-chatTop=${(rest.head.top - rest.chat.top).toFixed(1)}`);
    await page.screenshot({ path: join(OUT, `${tag}-rest.png`) });

    // ── 滚动态（Muse 形态）探针 + 实拍 ────────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight; });
    await page.waitForFunction(() => {
      const head = document.querySelector('#chat > .g2-chat-head');
      const chat = document.getElementById('chat');
      return head && chat && head.getBoundingClientRect().top - chat.getBoundingClientRect().top <= 1.5;
    }, { timeout: 10000 });
    await page.waitForTimeout(250);
    const pinned = await probeState(page);
    assertCommon(tag, pinned, withAvatar);
    ok(`${tag} 滚动态：锚贴窗口顶（headTop ≈ chatTop ±1.5px）`, pinned.pinnedNow,
      `headTop-chatTop=${(pinned.head.top - pinned.chat.top).toFixed(1)}`);
    ok(`${tag} 滚动态：压在消息流上缘（消息行矩形与锚矩形相交）`, pinned.rowsBehind > 0,
      `rowsBehind=${pinned.rowsBehind}`);
    ok(`${tag} 滚动态：渐隐带仍在场（88px，未被本批破坏）`,
      Math.abs(pinned.fadeH - 88) <= 1.5, `fadeH=${pinned.fadeH}`);
    await page.screenshot({ path: join(OUT, `${tag}-pinned.png`) });
    ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 页内状态探针（静止/滚动两态共用）。 */
async function probeState(page) {
  return page.evaluate(() => {
    const r = (el) => el.getBoundingClientRect();
    const chat = document.getElementById('chat');
    const head = document.querySelector('#chat > .g2-chat-head');
    const avatarBtn = document.getElementById('agent-avatar-btn');
    const nameEl = document.getElementById('session-name');
    const fade = document.getElementById('g2-edge-fade');
    const headCs = head ? getComputedStyle(head) : null;
    const chatCs = getComputedStyle(chat);
    const pad = parseFloat(chatCs.paddingTop) || 0;
    const av = r(avatarBtn);
    const nr = r(nameEl);
    const chatBox = r(chat);
    const headBox = r(head);
    const intersect = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
    const rowsBehind = [...chat.querySelectorAll('.row.ai')]
      .filter((row) => intersect(r(row), headBox) > 4).length;
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headerHidden: getComputedStyle(document.getElementById('header')).display === 'none',
      metas: chat.querySelectorAll('.g2-msg-meta, .g2-msg-avatar-slot, .g2-msg-name').length,
      aiRows: chat.querySelectorAll('.row.ai').length,
      position: headCs?.position,
      stickyTop: headCs?.top,
      padTop: pad,
      zIndex: headCs?.zIndex,
      maskImage: chatCs.maskImage === 'none' ? 'none' : (chatCs.webkitMaskImage || chatCs.maskImage),
      avW: av.width, avH: av.height,
      avCx: (av.left + av.right) / 2,
      chatCx: (chatBox.left + chatBox.right) / 2,
      nameBelow: nr.top >= av.bottom - 2,
      nameCx: (nr.left + nr.right) / 2,
      nameText: nameEl.textContent,
      nameVisible: nr.height > 0 && getComputedStyle(nameEl).visibility !== 'hidden',
      customAvatar: (() => { const img = avatarBtn?.querySelector('.agent-avatar-img'); return !!img && img.complete && img.naturalWidth > 0; })(),
      logoFallback: (() => { const img = avatarBtn?.querySelector('.agent-avatar-stand-logo img'); return !!img && img.complete && img.naturalWidth > 0; })(),
      glyphFallback: !!avatarBtn?.querySelector('svg.lucide-user, i[data-lucide="user"], .agent-avatar-initial'),
      head: headBox, chat: chatBox,
      pinnedNow: Math.abs(headBox.top - chatBox.top) <= 1.5,
      rowsBehind,
      fadeH: r(fade).height,
    };
  });
}

/** 两态共用断言（①②③⑤）。 */
function assertCommon(tag, p, withAvatar) {
  ok(`${tag} ① 消息流零身份头（meta/slot/name 全零）`, p.aiRows > 0 && p.metas === 0,
    `aiRows=${p.aiRows} metas=${p.metas}`);
  ok(`${tag} ② sticky 顶锚（position/top 补偿/z=26）`,
    p.position === 'sticky' && Math.abs(parseFloat(p.stickyTop || '1') + p.padTop) <= 1 && p.zIndex === '26',
    `position=${p.position} top=${p.stickyTop} pad=${p.padTop} z=${p.zIndex}`);
  ok(`${tag} ② #chat mask 已释放（手机层堆叠上下文解锁）`, p.maskImage === 'none', p.maskImage);
  ok(`${tag} ② 手机层在场 + #header 退场`, p.mobileLayer && p.headerHidden, '');
  ok(`${tag} ③ 大号圆头像 78px`,
    Math.abs(p.avW - 78) <= 0.8 && Math.abs(p.avH - 78) <= 0.8, `${p.avW.toFixed(1)}×${p.avH.toFixed(1)}`);
  ok(`${tag} ③ 头像水平居中（与 #chat 同心 ±2px）`, Math.abs(p.avCx - p.chatCx) <= 2,
    `avCx=${p.avCx.toFixed(1)} chatCx=${p.chatCx.toFixed(1)}`);
  ok(`${tag} ③ 名字在头像正下方且居中`, p.nameBelow && Math.abs(p.nameCx - p.avCx) <= 2,
    `below=${p.nameBelow} nameCx=${p.nameCx.toFixed(1)}`);
  if (withAvatar) {
    ok(`${tag} ③ 名字 = 当前会话对方 agent（Spider）`, p.nameVisible && p.nameText === 'Spider',
      `text="${p.nameText}"`);
    ok(`${tag} ⑤ 头像货源 = 自定义头像（现役身份链）`, p.customAvatar && !p.logoFallback, '');
  } else {
    ok(`${tag} ⑤ 头像货源 = stand logo 回落（零默认人形）`, p.logoFallback && !p.customAvatar && !p.glyphFallback, '');
  }
}

// ── 手机腿：双引擎 × 双主题（自定义头像腿 4 跑）+ 回落腿（chromium-light /
//    webkit-dark 2 跑）───────────────────────────────────────────────────
for (const engineKind of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await runMobile(engineKind, scheme, true);
  }
}
await runMobile('chromium', 'light', false);
await runMobile('webkit', 'dark', false);

// ── 桌面回归腿（1280×800 chromium）：#header 头像行原样、零锚、零身份头 ──
{
  const errors = [];
  const browser = await chromium.launch();
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
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'muse-root-session', name: 'root', agentName: 'Nebula' }], activeId: 'muse-root-session' }) });
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
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'muse-root-session', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'muse-root-session' }));
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
  }, 'muse-root-session', { timeout: 20000 });
  await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 2, { timeout: 20000 });
  await page.waitForFunction(() => {
    const slot = document.getElementById('agent-avatar-btn');
    const logo = slot?.querySelector('.agent-avatar-stand-logo img');
    return !!slot && !!logo && logo.complete && logo.naturalWidth > 0;
  }, { timeout: 20000 });
  await page.waitForTimeout(600);

  const d = await page.evaluate(() => {
    const header = document.getElementById('header');
    const center = header?.querySelector('.header-center');
    const slot = document.getElementById('agent-avatar-btn');
    const slotRect = slot?.getBoundingClientRect();
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      headerShown: !!header && getComputedStyle(header).display !== 'none',
      slotInHeader: !!slot && !!center && center.contains(slot),
      slotVisible: !!slotRect && slotRect.width > 20 && slotRect.height > 20,
      slotLogoPainted: (() => { const img = slot?.querySelector('.agent-avatar-stand-logo img'); return !!img && img.complete && img.naturalWidth > 0; })(),
      noChatHead: !document.querySelector('#chat > .g2-chat-head'),
      stickyAny: (() => { const h = document.querySelector('.g2-chat-head'); return h ? getComputedStyle(h).position : 'absent'; })(),
      metas: document.querySelectorAll('#chat .g2-msg-meta').length,
      aiRows: document.querySelectorAll('#chat .row.ai').length,
      chatMaskActive: getComputedStyle(document.getElementById('chat')).maskImage !== 'none',
    };
  });
  ok('desktop ① #header 头像行原样（header-center 含头像钮，可见可绘）',
    !d.mobileLayer && d.headerShown && d.slotInHeader && d.slotVisible && d.slotLogoPainted,
    `inHeader=${d.slotInHeader} painted=${d.slotLogoPainted}`);
  ok('desktop ② 零悬浮锚 + 零身份头（手机层装饰桌面零感知）',
    d.noChatHead && d.stickyAny === 'absent' && d.metas === 0 && d.aiRows > 0,
    `head=${d.stickyAny} metas=${d.metas} aiRows=${d.aiRows}`);
  ok('desktop ③ #chat 顶部消失点 mask 桌面现役（未被手机层释放波及）', d.chatMaskActive, '');
  await page.screenshot({ path: join(OUT, 'desktop-1280.png') });
  ok('desktop R0 零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
  await browser.close();
}

console.log(`═══ mobile-muse-avatar-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
