#!/usr/bin/env node
// mobile-muse-head-same-row-shots.mjs — 会话身份头「始终同一行」批（作者令
// 2026-11-02 11:08「为什么会出现有的情况，这个头像会和两侧按钮不在同一行」）
// 验收 harness。
//
// 背景（本批病灶与上一批的判据漏洞）：
//   sticky 的两个落点由**两个不同表达式**给定 —— 实测（逐 scrollTop 探针）：
//     静止落点 = #chat padding-top + margin-top   （96 + (-32) = 64）
//     贴顶落点 = #chat padding-top + top          （96 + (-96) =  0）
//   二者恒差 64 ⇒ 身份组只在**滚动之后**才与双钮（cy 40）对齐；静止态头像
//   cy = 100 vs 双钮 cy = 40 ⇒ 偏差 60px（作者实拍所见）。
//   上一批（a99ae5ee2）把静止态**排除在同行断言之外**（口径字面：「静止态
//   spread 60 属 Muse 形态不判」），于是这 60px 被当成形态本来的样子放过。
//   本批取消该豁免：**任何状态都必须同一行**，容差 ≤1px。
//
// 七条机械断言（四态各测：静止 scrollTop=0 / 滚动中 ≈400 / 贴顶 ≈892 / 无名字）：
//   ① 头像垂直中心 ↔ 左右双钮垂直中心，偏差 ≤1px   （静止态不再豁免）
//   ② 头像水平中心 ↔ 屏幕中线，偏差 ≤1px
//   ③ 名字胶囊中心 ↔ 头像中心 ≤1px 且 胶囊 top ≥ 头像 bottom −1
//      （无名字态：胶囊不渲染 = `:empty { display:none }` 收口成立）
//   ④ 不存在顶栏带（沿用上一批断言 A 口径：≥40 高 × ≥90% 宽 × 有可见墨迹）
//   ⑤ 内容仍从头像后穿过（沿用断言 E 口径：滚动态消息行盒 × 头像盒相交）
//   ⑥ 滚动全程头像无可见跳动（逐帧采样 avatar rect.top，极差 ≤2px；
//      新几何设计为「全程恒等」⇒ 期望极差 = 0）
//   ⑦ 安全区模拟（CDP Emulation.setSafeAreaInsetsOverride top=44）下
//      头像不与刘海区重叠、不顶内容上缘、且 ① 仍成立
//   另有 R0 每腿零 console error / pageerror。
//
// 产出 → evidence/20261102_muse-head-same-row/：
//   rest-{light,dark}.png         静止态（重点：头像与双钮在同一条线上）
//   cropped-{light,dark}.png      静止态顶部裁剪放大（同一条水平线的直观证据）
//   scrollmid-{light,dark}.png    滚动中（scrollTop≈400）
//   pinned-{light,dark}.png       贴顶（scrollTop≈892）
//   unnamed-{light,dark}.png      无名字态
//   safe-area-{light,dark}.png    安全区模拟（刘海 44）
//   before-rest-{light,dark}.png  改前同视口（源 evidence/20261102_muse-head-restore/）
//   desktop-1280.png              桌面回归（未变）
// Run: node tests/mobile-muse-head-same-row-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'evidence', '20261102_muse-head-same-row');
const BEFORE = join(ROOT, 'evidence', '20261102_muse-head-restore');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'muse-head-same-row-session';

// 夹具头像：珊瑚底白字「S」圆牌（与 muse-head / avatar 族同源）。
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

    // ── ④ 顶部横贯带扫描（沿用上一批断言 A 口径：只认「有墨迹」的带）──
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
      const hasEdge = (bdA > 0.02 || bdTopA > 0.02);
      if (bgA > 0.02 || hasEdge) {
        bands.push({
          id: el.id || '', cls: String(el.className).slice(0, 40), tag: el.tagName,
          w: +b.width.toFixed(1), h: +b.height.toFixed(1), top: +b.top.toFixed(1),
          pos: cs.position, bgA: +bgA.toFixed(2), edge: hasEdge,
        });
      }
    }

    // ── ⑤ 消息行 × 头像盒相交（滚动态判据）──
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
      headTop: r(head) ? +r(head).top.toFixed(2) : -1,
      headBottom: r(head) ? +r(head).bottom.toFixed(2) : -1,
      headH: r(head) ? +r(head).height.toFixed(2) : -1,
      headW: r(head) ? +r(head).width.toFixed(2) : -1,
      bands,
      chatTop: +chatBox.top.toFixed(2),
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
      taskTop: task ? +task.top.toFixed(2) : -1,
      taskW: task ? +task.width.toFixed(2) : -1,
      searchCx: search ? +cx(search).toFixed(2) : -1,
      searchCy: search ? +cy(search).toFixed(2) : -1,
      searchTop: search ? +search.top.toFixed(2) : -1,
      searchW: search ? +search.width.toFixed(2) : -1,
      nameText: nameEl ? nameEl.textContent : null,
      nameDisplay: ncs ? ncs.display : 'absent',
      nameTop: nr ? +nr.top.toFixed(2) : -1,
      nameBottom: nr ? +nr.bottom.toFixed(2) : -1,
      nameW: nr ? +nr.width.toFixed(2) : -1,
      nameH: nr ? +nr.height.toFixed(2) : -1,
      nameCx: nr ? +cx(nr).toFixed(2) : -1,
      nameBgA: ncs ? +alpha(ncs.backgroundColor).toFixed(2) : -1,
      firstRowTop: (() => { const el = document.querySelector('#chat .row'); return el ? +el.getBoundingClientRect().top.toFixed(2) : -1; })(),
      scrollTop: Math.round(chat.scrollTop),
      fadeH: fade ? +r(fade).height.toFixed(2) : -1,
      behindHits,
      behindMaxArea: +maxArea.toFixed(0),
    };
  });
}

/** ① 同行断言（四态共用，≤1px；静止态不再豁免）。 */
function assertSameRow(tag, st) {
  const spread = Math.max(Math.abs(st.avCy - st.taskCy), Math.abs(st.avCy - st.searchCy));
  ok(`${tag} ① 头像 cy ↔ 双钮 cy 偏差 ≤1px`,
    spread <= 1,
    `spread=${spread.toFixed(2)} avCy=${st.avCy} 双钮 cy=${st.taskCy}/${st.searchCy}`);
  return spread;
}
/** ② 水平居中（四态共用）。 */
function assertCentered(tag, st) {
  const d = Math.abs(st.avCx - st.vw / 2);
  ok(`${tag} ② 头像水平中心 ↔ 屏幕中线 ≤1px`, d <= 1,
    `|avCx−vw/2|=${d.toFixed(2)} (avCx=${st.avCx} vw/2=${(st.vw / 2).toFixed(1)})`);
  return d;
}
/** ③ 名字胶囊对齐（有名字态）。 */
function assertName(tag, st) {
  const d = Math.abs(st.nameCx - st.avCx);
  const gap = st.nameTop - st.avBottom;
  ok(`${tag} ③ 名字胶囊中心↔头像中心 ≤1px 且 胶囊 top ≥ 头像 bottom −1`,
    d <= 1 && gap >= -1,
    `|nameCx−avCx|=${d.toFixed(2)} gap=${gap.toFixed(2)} (nameTop=${st.nameTop} avBottom=${st.avBottom})`);
}
/** ④ 无顶栏（四态共用）。 */
function assertNoBar(tag, st) {
  ok(`${tag} ④ 不存在顶栏带（≥40 高 × ≥90% 宽 × 有可见墨迹）`, st.bands.length === 0,
    st.bands.length === 0 ? 'scanned 0 bands' : `hits=${JSON.stringify(st.bands.slice(0, 3))}`);
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
      // displayName 缺失 ⇒ 无名字档（rootName.js 判 unnamed，胶囊 :empty 收口）。
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

/** 起页并等身份头几何落定。 */
async function bootMobile(ctx, { avatar, displayName, safeTop = 0 }) {
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  if (safeTop > 0) {
    const cdp = await ctx.newCDPSession(page);
    await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: safeTop, bottom: 0, left: 0, right: 0 } });
  }
  await wire(page, { avatar, displayName });
  await page.goto('http://localhost:59999/', { timeout: 90000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1 && document.body.classList.contains('mobile-layer');
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
  await page.waitForTimeout(500);
  return { page, errors };
}

/** 手机腿（375×812 × 双主题）：静止 / 滚动中 / 贴顶 / 无名字 四态。 */
async function runMobile(scheme) {
  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    const { page, errors } = await bootMobile(ctx, { avatar: AV, displayName: 'Spider' });

    const scrollTo = async (v) => {
      await page.evaluate((y) => { document.getElementById('chat').scrollTop = y; }, v);
      await page.waitForTimeout(280);
    };

    // ── 静态一：静止态（scrollTop = 0）—— 本批重点，不再豁免 ──────────
    await scrollTo(0);
    const rest = await probeState(page);
    assertSameRow(`${scheme} 静止`, rest);
    assertCentered(`${scheme} 静止`, rest);
    assertName(`${scheme} 静止`, rest);
    assertNoBar(`${scheme} 静止`, rest);
    ok(`${scheme} 静止 身份组几何 = 锚落位（组顶 = 锚 4 ±1；头像顶 = 锚+组内上距 12 ±1）`,
      Math.abs(rest.headTop - 4) <= 1 && Math.abs(rest.avTop - 12) <= 1,
      `headTop=${rest.headTop} avTop=${rest.avTop} padTop=${rest.chatPadTop}`);
    ok(`${scheme} 静止 首条消息不与胶囊相撞（首行顶 ≥ 胶囊底）`,
      rest.firstRowTop >= rest.nameBottom,
      `firstRowTop=${rest.firstRowTop} nameBottom=${rest.nameBottom} gap=${(rest.firstRowTop - rest.nameBottom).toFixed(2)}`);
    console.log(`      · 静止实测：头像 ${rest.avTop}→${rest.avBottom}（cy ${rest.avCy}）/ 双钮 cy ${rest.taskCy}、${rest.searchCy} / 胶囊 ${rest.nameTop}→${rest.nameBottom} / 首条消息行顶 ${rest.firstRowTop} / #chat padding-top=${rest.chatPadTop}`);
    await page.screenshot({ path: join(OUT, `rest-${scheme}.png`) });
    // 顶部裁剪放大：静止态「头像与双钮同一条线」的直观证据
    await page.screenshot({ path: join(OUT, `cropped-${scheme}.png`), clip: { x: 0, y: 0, width: 375, height: 170 } });

    // ── 静态二：滚动中（scrollTop ≈ 400）────────────────────────────
    await scrollTo(400);
    const mid = await probeState(page);
    assertSameRow(`${scheme} 滚动中`, mid);
    assertCentered(`${scheme} 滚动中`, mid);
    assertName(`${scheme} 滚动中`, mid);
    assertNoBar(`${scheme} 滚动中`, mid);
    ok(`${scheme} 滚动中 ⑤ 内容确实从头像后穿过（消息行盒与头像盒相交）`,
      mid.scrollTop > 0 && mid.behindHits > 0 && mid.behindMaxArea > 0,
      `scrollTop=${mid.scrollTop} 相交行数=${mid.behindHits} 最大交叠面积=${mid.behindMaxArea}px²`);
    await page.screenshot({ path: join(OUT, `scrollmid-${scheme}.png`) });

    // ── 静态三：贴顶（scrollTop ≈ 892）──────────────────────────────
    await scrollTo(892);
    const pinned = await probeState(page);
    assertSameRow(`${scheme} 贴顶`, pinned);
    assertCentered(`${scheme} 贴顶`, pinned);
    assertName(`${scheme} 贴顶`, pinned);
    assertNoBar(`${scheme} 贴顶`, pinned);
    // 贴顶与静止**同位**：不存在「滚动才对齐」的窗口
    ok(`${scheme} 贴顶 与静止同位（无 sticky 位移：组顶/头像顶与静止态逐值相同）`,
      Math.abs(pinned.headTop - rest.headTop) <= 0.5 && Math.abs(pinned.avTop - rest.avTop) <= 0.5,
      `headTop ${rest.headTop}→${pinned.headTop} avTop ${rest.avTop}→${pinned.avTop}`);
    ok(`${scheme} 贴顶 ⑤ 内容仍从头像后穿过`, pinned.behindHits > 0,
      `scrollTop=${pinned.scrollTop} 相交行数=${pinned.behindHits} 最大交叠面积=${pinned.behindMaxArea}px²`);
    await page.screenshot({ path: join(OUT, `pinned-${scheme}.png`) });

    // ── ⑥ 滚动全程逐帧采样：头像不得出现可见跳动 ─────────────────────
    const jitter = await page.evaluate(async () => {
      const chat = document.getElementById('chat');
      const av = document.getElementById('agent-avatar-btn');
      const btn = document.querySelector('.mh-chrome-btn[data-chrome="tasks"]');
      chat.scrollTop = 0;
      await new Promise((res) => requestAnimationFrame(res));
      const max = chat.scrollHeight - chat.clientHeight;
      const avTops = [];
      const spreads = [];
      let pos = 0;
      await new Promise((resolve) => {
        const step = () => {
          const a = av.getBoundingClientRect();
          const b = btn.getBoundingClientRect();
          avTops.push(+a.top.toFixed(2));
          spreads.push(+Math.abs((a.top + a.bottom) / 2 - (b.top + b.bottom) / 2).toFixed(2));
          pos = Math.min(max, pos + 24);
          chat.scrollTop = pos;
          if (pos >= max) {
            const a2 = av.getBoundingClientRect();
            const b2 = btn.getBoundingClientRect();
            avTops.push(+a2.top.toFixed(2));
            spreads.push(+Math.abs((a2.top + a2.bottom) / 2 - (b2.top + b2.bottom) / 2).toFixed(2));
            resolve();
          } else requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      });
      return {
        samples: avTops.length, maxScroll: max,
        minTop: Math.min(...avTops), maxTop: Math.max(...avTops),
        range: +(Math.max(...avTops) - Math.min(...avTops)).toFixed(2),
        maxSpread: +Math.max(...spreads).toFixed(2),
      };
    });
    ok(`${scheme} ⑥ 滚动全程头像无可见跳动（逐帧 avatar rect.top 极差 ≤2px）`,
      jitter.samples >= 10 && jitter.range <= 2,
      `帧数=${jitter.samples} 极差=${jitter.range}px（min ${jitter.minTop} / max ${jitter.maxTop}，滚程 0→${jitter.maxScroll}）`);
    ok(`${scheme} ⑥ 滚动全程与双钮始终同行（逐帧 spread 最大值 ≤1px）`,
      jitter.maxSpread <= 1, `逐帧最大 spread=${jitter.maxSpread}px`);
    console.log(`      · 逐帧：${jitter.samples} 帧，头像 top 恒 ${jitter.minTop}（极差 ${jitter.range}），最大 spread ${jitter.maxSpread}`);

    ok(`${scheme} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await ctx.close();
  } finally {
    await browser.close();
  }
}

/** 无名字态腿（第四态）：头像仍与双钮同行 + 胶囊不渲染。 */
async function runUnnamed(scheme) {
  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    const { page, errors } = await bootMobile(ctx, { avatar: AV, displayName: null });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForTimeout(280);
    const st = await probeState(page);
    assertSameRow(`${scheme} 无名字`, st);
    assertCentered(`${scheme} 无名字`, st);
    assertNoBar(`${scheme} 无名字`, st);
    ok(`${scheme} 无名字 ③ 胶囊不渲染（:empty 收口：display none / 盒 0）`,
      st.nameDisplay === 'none' && st.nameW === 0 && st.nameH === 0,
      `display=${st.nameDisplay} w=${st.nameW} h=${st.nameH} text=${JSON.stringify(st.nameText)}`);
    ok(`${scheme} 无名字 组高收敛（56 + 8 + 12 = 76，不留空洞）`,
      Math.abs(st.headH - 76) <= 1, `headH=${st.headH}`);
    // 无名字态滚动后仍同行
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 892; });
    await page.waitForTimeout(280);
    const stScrolled = await probeState(page);
    assertSameRow(`${scheme} 无名字·滚动`, stScrolled);
    console.log(`      · 无名字实测：头像 ${st.avTop}→${st.avBottom}（cy ${st.avCy}）/ 双钮 cy ${st.taskCy}、${st.searchCy}`);
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, `unnamed-${scheme}.png`) });
    ok(`${scheme} 无名字 R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await ctx.close();
  } finally {
    await browser.close();
  }
}

/** 安全区模拟腿（⑦）：刘海 44 下不重叠刘海、不顶内容上缘、且仍同行。 */
async function runSafeArea(scheme) {
  const SAFE = 44;
  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    const { page, errors } = await bootMobile(ctx, { avatar: AV, displayName: 'Spider', safeTop: SAFE });
    const envTop = await page.evaluate(() => {
      const d = document.createElement('div');
      d.style.cssText = 'position:absolute;padding-top:env(safe-area-inset-top,0px)';
      document.body.appendChild(d);
      const v = getComputedStyle(d).paddingTop;
      d.remove();
      return v;
    });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForTimeout(280);
    const st = await probeState(page);
    assertSameRow(`${scheme} 安全区 ${SAFE}`, st);
    assertCentered(`${scheme} 安全区 ${SAFE}`, st);
    assertName(`${scheme} 安全区 ${SAFE}`, st);
    ok(`${scheme} 安全区 ${SAFE} ⑦ 头像不与刘海区重叠（头像顶 ≥ safe-top）且组顶在安全区之下`,
      st.avTop >= SAFE && st.headTop >= SAFE,
      `env(safe-area-inset-top)=${envTop} avTop=${st.avTop} headTop=${st.headTop}`);
    ok(`${scheme} 安全区 ${SAFE} ⑦ 不顶内容上缘（首条消息行顶 ≥ 胶囊底）`,
      st.firstRowTop >= st.nameBottom,
      `firstRowTop=${st.firstRowTop} nameBottom=${st.nameBottom}`);
    ok(`${scheme} 安全区 ${SAFE} ⑦ 锚随安全区整体下移（组顶 = 锚 4 + safe 44 = 48 ±1）`,
      Math.abs(st.headTop - (4 + SAFE)) <= 1,
      `headTop=${st.headTop} 期望 48；头像顶=${st.avTop}（期望 56）、头像 cy=${st.avCy}、双钮 cy=${st.taskCy}`);
    console.log(`      · 安全区实测：组顶 ${st.headTop} / 头像 ${st.avTop}→${st.avBottom}（cy ${st.avCy}）/ 双钮 cy ${st.taskCy} / 胶囊底 ${st.nameBottom} / 首行 ${st.firstRowTop}`);
    await page.screenshot({ path: join(OUT, `safe-area-${scheme}.png`) });
    ok(`${scheme} 安全区 ${SAFE} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await ctx.close();
  } finally {
    await browser.close();
  }
}

/** 桌面回归腿（1280×800）：无 .g2-chat-head、#header 头像 28px、渐隐带不渲染。 */
async function runDesktop() {
  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await wire(page, { avatar: AV, displayName: 'Spider' });
    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
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
    ok('desktop-1280 桌面零触碰（无会话头节点 + #header 头像 28px + #header flex/absolute + 渐隐带不渲染）',
      !d.mobileLayer && d.noChatHead && Math.abs(d.slotW - 28) <= 0.6 && Math.abs(d.slotH - 28) <= 0.6
        && d.headerDisplay === 'flex' && d.headerPos === 'absolute' && d.fadeDisplay === 'none',
      `head=${d.noChatHead} av=${d.slotW}×${d.slotH} header=${d.headerDisplay}/${d.headerPos} fade=${d.fadeDisplay} pad=${d.chatPadTop}`);
    await page.screenshot({ path: join(OUT, 'desktop-1280.png') });
    ok('desktop-1280 R0 零 console error', errors.length === 0, errors.slice(0, 3).join(' | '));
    await ctx.close();
  } finally {
    await browser.close();
  }
}

for (const scheme of ['light', 'dark']) await runMobile(scheme);
for (const scheme of ['light', 'dark']) await runUnnamed(scheme);
for (const scheme of ['light', 'dark']) await runSafeArea(scheme);
await runDesktop();

// 改前对照：把上一批（错位 60px 的静止态）样张复制进本批证据目录，同视口并排评审。
for (const scheme of ['light', 'dark']) {
  const src = join(BEFORE, `rest-${scheme}.png`);
  if (existsSync(src)) {
    copyFileSync(src, join(OUT, `before-rest-${scheme}.png`));
    console.log(`      对照 · 改前静止态 → before-rest-${scheme}.png`);
  } else {
    console.log(`      ⚠ 改前样张缺失：${src}`);
  }
}

console.log(`═══ mobile-muse-head-same-row-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
