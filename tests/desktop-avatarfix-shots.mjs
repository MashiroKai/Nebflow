#!/usr/bin/env node
// desktop-avatarfix-shots.mjs — 圆框统一批 + agent 头像 stand logo 回落桌面腿
// （作者实拍指认两件 · 2026-11-01）桌面端截图取证 + 机械探针。
//   ① 待办事项入口行圆框 = `.fm-avatar` 家族（与设备行/好友行同规格：40px 圆 +
//      glass-etched 底描边）——探针对齐设备行圆框的几何/底色/描边。
//   ② 会话头（#agent-avatar-btn）无头像 = stand logo（paintSlot 自绘
//      .agent-avatar-stand-logo；主 agent Spider 有名无头像 = 作者实指不生效点）。
// 走 mobile-dock-shape-shots 同款静态 harness（无后端：routeWebSocket 供
// configData/sessionList/historyPage，fm_api_mock 供好友/设备会话行，
// /api/neblink/status 拦截供登录态 + Windows 对端设备档案）。
// Run: node tests/desktop-avatarfix-shots.mjs
// 产出 → evidence/20261101_avatarfix-desktop/

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_avatarfix-desktop');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'avatarfix-root-session';

// 好友头像夹具（绿色调小像，同作者实拍的相片圆框观感）。
const FRIEND_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7aa06e"/><stop offset="1" stop-color="#4c7a4f"/></linearGradient></defs><rect width="96" height="96" rx="48" fill="url(#g)"/><circle cx="48" cy="38" r="14" fill="#f2e8d5"/><ellipse cx="48" cy="74" rx="22" ry="16" fill="#f2e8d5"/></svg>';
const FRIEND_AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(FRIEND_SVG);

const NOW = Date.now();
const SEED = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [], friends: [], incoming: [], outgoing: [],
  conversations: [
    // 设备行（服务端 kind:'device' 行——deviceConvs 服务端腿，免本地流量证据）。
    {
      conversationId: 'device:dev-win-1', kind: 'device', deviceId: 'dev-win-1',
      unreadCount: 0, lastMessage: { id: 1, senderId: 'dev-win-1', kind: 'text', body: '00', createdAt: NOW - 600e3 },
    },
    // 好友行（相片头像圆框）——排序键 lastMessage.createdAt。
    {
      conversationId: 'c-pu',
      friend: { userId: 'u-pu', neblinkId: 'pu@example.com', name: 'puerhile', avatarUrl: FRIEND_AV, remark: null },
      lastMessage: { id: 2, senderId: 'u-pu', kind: 'text', body: '能看到我信息不', createdAt: NOW - 3600e3 },
      unreadCount: 0,
    },
  ],
  messages: {},
};
const HISTORY = [
  { type: 'user', text: '圆框统一批 + stand logo 回落看一下效果', timestamp: Date.now() - 300e3 },
  { type: 'ai', text: '两件都落在桌面：待办入口行圆框与设备行同规格；会话头无头像回退 stand logo。', timestamp: Date.now() - 240e3 },
];

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

mkdirSync(OUT, { recursive: true });
if (!existsSync(join(WEB, 'index.html'))) { console.error('web root missing: ' + WEB); process.exit(2); }

const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const tag = scheme;
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.addInitScript(({ seed }) => {
    // 双拼写写入（brand 命名空间不构成断言面，mobile-dock-shape-shots 同款纪律）。
    for (const ns of ['stand', 'nebflow']) {
      localStorage.setItem(`${ns}_token`, 'e2e-token');
      localStorage.setItem(`${ns}_locale`, 'zh-CN');
    }
    // stub 掉 SW 注册（静态 harness 无 /sw.js）。
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seed));
    // 🔴 无头像腿：不种 agent_avatar_Nebula —— paintSlot 应自绘 stand logo。
  }, { seed: SEED });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      if (p === '/api/neblink/status') return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          loggedIn: true,
          device: { id: 'self-mac-1', name: 'MacBook Pro', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' },
          peers: [{ deviceId: 'dev-win-1', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', online: true, directOnline: true }],
        }),
      });
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      // 其余 API 一律 200 '{}'（404 会记 console error 混进零错误断言——mobile harness 同款）。
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  // mock WS（mobile-dock-shape-shots 同款）：configData + sessionList 随连即推，
  // getHistory 回一页小历史（聊天区有实感内容）。
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed; try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  // 待办入口行 + 设备行 + 好友行齐 → 会话头 logo img 可绘。
  await page.waitForFunction(() => {
    const todo = document.querySelector('.fm-todo-entry .fm-todo-icon');
    const dev = document.querySelector('.fm-conv-row[data-device="1"]');
    const fr = document.querySelector('.fm-conv-row:not([data-device="1"])');
    const logo = document.querySelector('#agent-avatar-btn .agent-avatar-stand-logo img');
    return !!todo && !!dev && !!fr && !!logo && logo.complete && logo.naturalWidth > 0;
  }, { timeout: 20000 });
  await page.waitForTimeout(600);

  // 打开消息面板（桌面 boot 落在 canvas 视图；列表特写需面板可见）。
  await page.click('#messages-btn');
  await page.waitForFunction(() => {
    const el = document.getElementById('fm-conversations');
    return !!el && el.offsetParent !== null && el.getBoundingClientRect().width > 40;
  }, { timeout: 15000 });
  await page.waitForTimeout(300);

  const probe = await page.evaluate(() => {
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const todoIcon = document.querySelector('.fm-todo-entry .fm-todo-icon');
    const devAv = document.querySelector('.fm-conv-row[data-device="1"] .fm-avatar');
    const frAv = document.querySelector('.fm-conv-row:not([data-device="1"]) .fm-avatar');
    const t = cs(todoIcon); const d = cs(devAv); const f = cs(frAv);
    const logoImg = document.querySelector('#agent-avatar-btn .agent-avatar-stand-logo img');
    const slot = document.getElementById('agent-avatar-btn');
    return {
      todoCls: todoIcon?.className || '',
      todoRadius: t?.borderRadius, todoBg: t?.backgroundColor, todoBorder: t?.borderTopColor, todoW: t?.width,
      devRadius: d?.borderRadius, devBg: d?.backgroundColor, devBorder: d?.borderTopColor, devW: d?.width,
      frBg: f?.backgroundColor, frBorder: f?.borderTopColor,
      devSvg: !!devAv?.querySelector('svg'),
      slotLogo: !!logoImg && logoImg.complete && logoImg.naturalWidth > 0,
      slotGlyph: !!slot?.querySelector('svg.lucide-user, i[data-lucide="user"]'),
      slotInitial: !!slot?.querySelector('.agent-avatar-initial'),
    };
  });

  // ① 待办圆框 = 设备行圆框同规格（家族类 + 几何/底/描边一致）
  ok(`${tag} ① 待办图标挂 .fm-avatar 家族类`, /fm-avatar/.test(probe.todoCls) && /fm-avatar-40/.test(probe.todoCls) && /fm-todo-icon/.test(probe.todoCls), probe.todoCls);
  ok(`${tag} ① 圆框几何一致（40px + r50%）`,
    probe.todoRadius === '50%' && probe.devRadius === '50%'
    && Math.abs(parseFloat(probe.todoW) - parseFloat(probe.devW)) < 0.5,
    `todo=${probe.todoW}/${probe.todoRadius} dev=${probe.devW}/${probe.devRadius}`);
  ok(`${tag} ① 圆框底/描边与设备行一致`,
    probe.todoBg === probe.devBg && probe.todoBorder === probe.devBorder && probe.todoBg === probe.frBg,
    `bg todo=${probe.todoBg} dev=${probe.devBg} border todo=${probe.todoBorder} dev=${probe.devBorder}`);
  ok(`${tag} ① 底/描边非透明（玻璃 etched 在场）`,
    probe.todoBg !== 'rgba(0, 0, 0, 0)' && probe.todoBorder !== 'rgba(0, 0, 0, 0)',
    `bg=${probe.todoBg} border=${probe.todoBorder}`);
  ok(`${tag} ① 设备行平台字形在场`, probe.devSvg, '');
  // ② 会话头无头像 = stand logo（零人形/零首字母牌）
  ok(`${tag} ② 会话头 = stand logo 可绘`, probe.slotLogo, '');
  ok(`${tag} ② 零默认人形/零首字母牌`, !probe.slotGlyph && !probe.slotInitial, '');

  await page.screenshot({ path: join(OUT, `desktop-${tag}.png`) });
  // 消息列表侧栏特写（与作者实拍同框位：待办 + 设备 + 好友三行）。
  const list = await page.$('#fm-conversations');
  if (list) await list.screenshot({ path: join(OUT, `convo-list-${tag}.png`) });
  const slotEl = await page.$('#agent-avatar-btn');
  if (slotEl) {
    const box = await slotEl.boundingBox();
    if (box) {
      await page.screenshot({
        path: join(OUT, `header-avatar-${tag}.png`),
        clip: { x: Math.max(0, box.x - 90), y: Math.max(0, box.y - 12), width: 220, height: 52 },
      });
    }
  }

  ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}
await browser.close();
console.log(`═══ desktop-avatarfix-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
