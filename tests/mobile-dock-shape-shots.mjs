#!/usr/bin/env node
// mobile-dock-shape-shots.mjs — dock 收形批（作者令 10-31 22:50 六件）双主题截图取证
// + 六项机械探针。产出 → evidence/20261031_mobile-dock-shape/：
//   dock-<scheme>.png         聊天根 dock 全景（中键 = agent 头像图、无文字、收进 dock 内）
//   msgflow-<scheme>.png      消息流带头像样张（每条 agent 消息 = 圆头像 + agent 名）
//   logo-fallback-<scheme>.png 无头像回落样张（dock 中键 + 消息流身份头 = stand logo）
// 六项探针（与令逐条对齐）：
//   ① 每条 .row.ai（非代发行）带 .g2-msg-meta：名非空 + 头像 img 可绘
//   ② 中键零文字标签（.dock-label 不存在）+ aria-label 承接
//   ③ 无头像回落 = stand logo（.dock-center-logo / .g2-msg-logo，双面）
//   ④ 中键圆 box-shadow == none（边缘收简：纯色零浮影零光环）
//   ⑤ 中键圆不探出 dock 顶缘（inside）+ 输入卡底缘与 dock 顶缘缝 ≤1.5px
//   ⑥ #send-btn = 36×36 r50% 纯圆（绿保留，颜色探针跟查 --ctl-send-bg）
// 走 mobile-dock.spec / tmp-dock-center-evidence 同款静态 harness（无后端）。
// Run: node tests/mobile-dock-shape-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261031_mobile-dock-shape');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'dock-root-session';

// 夹具头像：珊瑚底白字圆牌（与 sapphire 底可辨）。静态 harness 品牌回退 =
// stand（brand.js fallback）—— 双拼写写入，键名不构成断言面。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="96" height="96" rx="48" fill="url(#g)"/><text x="48" y="63" font-family="-apple-system,sans-serif" font-size="40" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

// 历史夹具：两轮 user/ai 对话（ai 行 = 身份头装饰面）。
const HISTORY = [
  { type: 'user', text: '收形批六件看一下效果', timestamp: Date.now() - 300e3 },
  { type: 'ai', text: '收到。六件都落在手机层：消息流头像+名、中键去文字、logo 回落、边缘收简、中键收进 dock、发送钮回圆。', timestamp: Date.now() - 240e3 },
  { type: 'user', text: '截图样张什么样？', timestamp: Date.now() - 180e3 },
  { type: 'ai', text: '这条消息上方就是头像 + agent 名的样张形态。', timestamp: Date.now() - 120e3 },
];

mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  for (const withAvatar of [true, false]) {
    const tag = `${scheme}${withAvatar ? '' : '-nologo-seed'}`; // nologo 腿 = 无头像回落腿
    const errors = [];
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(({ avatar }) => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      // harness：stub 掉 SW 注册（mobile-dock.spec 同款 —— 静态 harness 无
      // /sw.js，register 404 会记 console error 混进零错误断言）。
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      if (avatar) {
        for (const k of ['stand_agent_avatar_Nebula', 'nebflow_agent_avatar_Nebula']) {
          localStorage.setItem(k, JSON.stringify({ dataUrl: avatar, sha: 'evidence-seed', at: Date.now() }));
        }
      }
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
      }));
    }, { avatar: withAvatar ? AV : null });
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/') || p.startsWith('/agents/')) {
        // /agents/Nebula/agent.json：rootName 探针腿（404 记 console error 混零错误断言）。
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
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
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
    }, SID, { timeout: 15000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 2, { timeout: 10000 });
    await page.waitForTimeout(600); // 重放 rAF markdown 批 + 守望装饰落定

    // ── 探针（六项）───────────────────────────────────────────────
    const probe = await page.evaluate(() => {
      const r = (el) => el.getBoundingClientRect();
      const center = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
      const centerKey = document.querySelector('#mobile-dock .dock-key[data-dock="chat"]');
      const dock = document.getElementById('mobile-dock');
      const bar = document.getElementById('input-bar');
      const send = document.getElementById('send-btn');
      const sendCs = getComputedStyle(send);
      const metas = [...document.querySelectorAll('#chat .row.ai')]
        .filter((row) => !row.querySelector(':scope > .agent-badge'))
        .map((row) => {
          const meta = row.querySelector(':scope > .g2-msg-meta');
          const img = meta?.querySelector('img');
          return {
            hasMeta: !!meta,
            name: meta?.querySelector('.g2-msg-name')?.textContent || '',
            imgPainted: !!img && img.complete && img.naturalWidth > 0,
            isLogo: !!meta?.querySelector('img.g2-msg-logo'),
          };
        });
      const centerImg = center?.querySelector('img');
      const avatarSlot = document.getElementById('agent-avatar-btn');
      return {
        centerLabel: !!centerKey.querySelector('.dock-label'),
        centerAria: centerKey.getAttribute('aria-label'),
        centerHasImg: !!centerImg && centerImg.complete && centerImg.naturalWidth > 0,
        centerIsLogo: !!center?.querySelector('img.dock-center-logo'),
        centerShadow: getComputedStyle(center).boxShadow,
        circle: r(center), dockRect: r(dock), barRect: r(bar),
        sendRadius: sendCs.borderRadius, sendW: r(send).width, sendH: r(send).height,
        sendBg: sendCs.backgroundColor,
        slotImg: !!avatarSlot?.querySelector('.agent-avatar-img'),
        slotLogo: !!avatarSlot?.querySelector('.agent-avatar-stand-logo'),
        slotGlyph: !!avatarSlot?.querySelector('svg.lucide-user, i[data-lucide="user"]'),
        metas,
        aiRows: document.querySelectorAll('#chat .row.ai').length,
        metaRows: document.querySelectorAll('#chat .row.ai .g2-msg-meta').length,
        agentBadgeRows: document.querySelectorAll('#chat .row.ai:scope, #chat .row.ai > .agent-badge').length,
      };
    });

    // ① 消息流头像 + agent 名
    ok(`${tag} ① ai 行全带身份头（含可绘头像+非空名）`,
      probe.aiRows > 0 && probe.metas.length > 0 && probe.metas.every((m) => m.hasMeta && m.name && m.imgPainted),
      `aiRows=${probe.aiRows} metas=${JSON.stringify(probe.metas)}`);
    // ② 中键去文字
    ok(`${tag} ② 中键零文字标签 + aria-label=对话`,
      !probe.centerLabel && probe.centerAria === '对话',
      `label=${probe.centerLabel} aria=${probe.centerAria}`);
    // ③ 无头像回落 = stand logo（dock 中键 + 消息流身份头 + 会话头空态）
    if (withAvatar) {
      ok(`${tag} ③ 有头像：中键/会话头 = 头像图（非 logo）`,
        probe.centerHasImg && !probe.centerIsLogo && probe.slotImg && !probe.slotLogo,
        `isLogo=${probe.centerIsLogo} slotImg=${probe.slotImg}`);
    } else {
      ok(`${tag} ③ 无头像：中键+消息流身份头+会话头 = stand logo（零默认人形）`,
        probe.centerIsLogo && probe.metas.every((m) => m.isLogo) && probe.slotLogo && !probe.slotGlyph,
        `centerLogo=${probe.centerIsLogo} slotLogo=${probe.slotLogo} glyph=${probe.slotGlyph}`);
    }
    // ④ 边缘收简
    ok(`${tag} ④ 中键 box-shadow == none`, probe.centerShadow === 'none', probe.centerShadow);
    // ⑤ 中键不探出 dock + 输入卡间隙收窄
    const inside = probe.circle.top >= probe.dockRect.top - 0.5 && probe.circle.bottom <= probe.dockRect.bottom + 0.5;
    const gap = probe.dockRect.top - probe.barRect.bottom;
    ok(`${tag} ⑤ 中键收进 dock 边界内`, inside, `circleTop=${probe.circle.top.toFixed(1)} dockTop=${probe.dockRect.top.toFixed(1)}`);
    ok(`${tag} ⑤ 输入卡-dock 缝 ≤1.5px`, Math.abs(gap) <= 1.5, `gap=${gap.toFixed(2)}`);
    // ⑥ 发送钮纯圆
    ok(`${tag} ⑥ 发送钮 36×36 r50% 纯圆 + 绿保留`,
      probe.sendRadius === '50%' && Math.abs(probe.sendW - 36) <= 0.6 && Math.abs(probe.sendH - 36) <= 0.6 && probe.sendBg === 'rgb(7, 193, 96)',
      `r=${probe.sendRadius} ${probe.sendW}×${probe.sendH} bg=${probe.sendBg}`);

    // ── 截图 ──────────────────────────────────────────────────────
    await page.screenshot({ path: join(OUT, `dock-${tag}.png`) });
    if (withAvatar) {
      // 消息流样张：滚到底（最后一条 ai 行的身份头入画）。
      await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight; });
      await page.waitForTimeout(300);
      await page.screenshot({ path: join(OUT, `msgflow-${tag}.png`) });
    } else {
      await page.screenshot({ path: join(OUT, `logo-fallback-${tag}.png`) });
    }

    ok(`${tag} R0 零 JS 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await page.close();
  }
}
await browser.close();
console.log(`═══ mobile-dock-shape-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
