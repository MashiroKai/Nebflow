#!/usr/bin/env node
// tmp-dock-center-evidence.mjs — 中键改版批（2026-10-29 23:36）evidence 截图脚本。
// 产出四张（亮暗 × 移动两态）→ evidence/dock-center/：
//   dock-chat-<scheme>.png    聊天根（中键 = sapphire 大圆 + agent 头像，高出基线）
//   messages-<scheme>.png     消息页（跟联系人的对话消息列表 + 最左气泡图标）
// 走 mobile-dock.spec 同款静态 harness（无后端；fm_api_mock 种子好友会话）。
// Run: node tests/tmp-dock-center-evidence.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', 'dock-center');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'dock-root-session';

// 夹具头像：珊瑚底白字圆牌（与 sapphire 环色可辨）。静态 harness 品牌回退 =
// stand（brand.js fallback）—— 双拼写写入，键名不构成断言面。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="96" height="96" rx="48" fill="url(#g)"/><text x="48" y="63" font-family="-apple-system,sans-serif" font-size="40" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
  await page.addInitScript(({ avatar }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    for (const k of ['stand_agent_avatar_Nebula', 'nebflow_agent_avatar_Nebula']) {
      localStorage.setItem(k, JSON.stringify({ dataUrl: avatar, sha: 'evidence-seed', at: Date.now() }));
    }
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
      users: [], friends: [
        { userId: 'u-1', neblinkId: 'lin@example.com', name: '林一', avatarUrl: '', since: Date.now() - 86400e3 },
        { userId: 'u-2', neblinkId: 'chen@example.com', name: '陈二', avatarUrl: '', since: Date.now() - 172800e3 },
      ],
      incoming: [], outgoing: [],
      conversations: [
        { conversationId: 'c-1', friend: { userId: 'u-1', neblinkId: 'lin@example.com', name: '林一', avatarUrl: '' }, lastMessage: { id: 11, senderId: 'u-1', kind: 'text', body: '中间键截图效果如何？', createdAt: Date.now() - 120e3 }, unreadCount: 2 },
        { conversationId: 'c-2', friend: { userId: 'u-2', neblinkId: 'chen@example.com', name: '陈二', avatarUrl: '' }, lastMessage: { id: 12, senderId: 'me', kind: 'text', body: '晚上对一下 spec', createdAt: Date.now() - 3600e3 }, unreadCount: 0 },
      ],
      messages: {
        'c-1': [{ id: 11, senderId: 'u-1', kind: 'text', body: '中间键截图效果如何？', createdAt: Date.now() - 120e3 }],
        'c-2': [{ id: 12, senderId: 'me', kind: 'text', body: '晚上对一下 spec', createdAt: Date.now() - 3600e3 }],
      },
    }));
  }, { avatar: AV });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed; try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(500);

  // ① 聊天根：中键（sapphire 大圆 + agent 头像，高出基线）。
  await page.screenshot({ path: join(OUT, `dock-chat-${scheme}.png`) });

  // ② 消息页：跟联系人的对话消息列表（最左键 = 消息气泡图标，active 绿）。
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="messages"]');
    if (!el || !el.classList.contains('live')) return false;
    const tf = getComputedStyle(el).transform;
    return tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)';
  }, { timeout: 5000 });
  await page.waitForTimeout(600); // 会话列表行渲染
  await page.screenshot({ path: join(OUT, `messages-${scheme}.png`) });
  await page.close();
  console.log(`evidence: ${scheme} done`);
}
await browser.close();
console.log('ALL DONE →', OUT);
