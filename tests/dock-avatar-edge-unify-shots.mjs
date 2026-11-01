#!/usr/bin/env node
// dock-avatar-edge-unify-shots.mjs — dock 头像边缘统一批（作者令 2026-11-01 22:3x
// 「dock 的 agent 头像边缘和顶部 agent 头像的边缘保持一致」）双主题 × 双头像态
// 截图取证 + 机械探针。产出 → evidence/20261101_dock-avatar-edge-unify/：
//   edge-sameframe-<scheme>-<state>.png  同帧实拍（聊天根 375×812：顶部 Muse
//                                        悬浮大头像 56px + dock 中键 44px 同框）
//   zoom-dock-<scheme>-<state>.png       并排帧·dock 中键特写（元素级截图）
//   zoom-top-<scheme>-<state>.png        并排帧·顶部头像特写（元素级截图）
// 探针（与令逐条对齐）：
//   ① 边缘一致性（核心断言）：.dock-center 与 #agent-avatar-btn 计算样式逐字
//      等值 —— border-top-width==1px / border-top-style==solid / border-color
//      / box-shadow（inset 高光 + 0 2px 8px 浮影）；两件消费同一组
//      glass-control token，同主题下计算值必须全等。
//   ② 边缘非空：shadow != none、borderW == 1px（收形批④ zero-shadow 退役实证）。
//   ③ 主题映射：同一断言在 dark / light 两档都成立，且两档 computed 值互异
//      （证明走 token 主题映射而非写死值）。
//   ④ 两态成立：自定义头像态（img 铺满 content 盒 ≈42px，旧 2.5px 蓝环退役）
//      / 默认态（stand logo 回落）各跑一轮。
//   ⑤ dock 其余形态零触碰：中键外径恒 44×44（border-box 描边收进足印）、
//      圆不探出胶囊顶缘、呼吸缝 == --dock-gap 10px、顶部头像 56px 形态不动。
//   R0 零 console error 门。
// 走 mobile-dock-shape-shots / mobile-dock.spec 同款静态 harness（无后端）。
// Run: node tests/dock-avatar-edge-unify-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_dock-avatar-edge-unify');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'dock-root-session';

// 夹具头像：珊瑚底白字圆牌（与 sapphire 底/芯片底可辨）。静态 harness 品牌回退 =
// stand（brand.js fallback）—— 双拼写写入，键名不构成断言面。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="96" height="96" rx="48" fill="url(#g)"/><text x="48" y="63" font-family="-apple-system,sans-serif" font-size="40" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

const HISTORY = [
  { type: 'user', text: 'dock 中键头像的边缘和顶部头像保持一致', timestamp: Date.now() - 300e3 },
  { type: 'ai', text: '收到。边缘换同一份语言：1px glass-control 描边 + inset 高光 + 0 2px 8px 浮影，尺寸各自不动。', timestamp: Date.now() - 240e3 },
  { type: 'user', text: '自定义头像和默认 logo 两个形态都在吗？', timestamp: Date.now() - 180e3 },
  { type: 'ai', text: '两态都成立：头像图铺满圆面（描边环内缘），无头像回落 stand logo，边缘同一套。', timestamp: Date.now() - 120e3 },
];

mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

const browser = await chromium.launch();
const edgeByScheme = {};   // ③ 主题映射互异断言的证据池
for (const scheme of ['dark', 'light']) {
  for (const withAvatar of [true, false]) {
    const state = withAvatar ? 'avatar' : 'logo';
    const tag = `${scheme}-${state}`;
    const errors = [];
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(({ avatar }) => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      // harness：stub 掉 SW 注册（静态 harness 无 /sw.js，register 404 会记
      // console error 混进零错误断言）。
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
    await page.waitForTimeout(600);

    // 同帧前置：#chat 滚到顶 → .g2-chat-head sticky 锚定窗口顶（顶部头像可见），
    // dock 固定底缘（中键可见）—— 375×812 单帧同时容纳两件头像。
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    await page.waitForTimeout(300);

    // ── 探针 ──────────────────────────────────────────────────────
    const probe = await page.evaluate(() => {
      const center = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
      const dock = document.getElementById('mobile-dock');
      const bar = document.getElementById('input-bar');
      const top = document.getElementById('agent-avatar-btn');
      const edgeOf = (el) => {
        if (!el) return null;
        const c = getComputedStyle(el);
        return { borderW: c.borderTopWidth, borderStyle: c.borderTopStyle, borderColor: c.borderColor, shadow: c.boxShadow };
      };
      const cr = center.getBoundingClientRect();
      const tr = top.getBoundingClientRect();
      const dockR = dock.getBoundingClientRect();
      const barR = bar.getBoundingClientRect();
      const img = center.querySelector('img.dock-center-img');
      const logo = center.querySelector('img.dock-center-logo');
      const topImg = top.querySelector('.agent-avatar-img');
      const topLogo = top.querySelector('.agent-avatar-stand-logo');
      return {
        centerEdge: edgeOf(center),
        topEdge: edgeOf(top),
        centerW: cr.width, centerH: cr.height,
        centerTop: cr.top, dockTop: dockR.top, dockBottom: dockR.bottom, centerBottom: cr.bottom,
        gap: dockR.top - barR.bottom,
        topW: tr.width, topH: tr.height, topInViewport: tr.top >= 0 && tr.bottom <= 812,
        centerInViewport: cr.top >= 0 && cr.bottom <= 812,
        imgW: img ? img.getBoundingClientRect().width : null,
        imgLoaded: !!img && img.complete && img.naturalWidth > 0,
        hasLogo: !!logo,
        topHasImg: !!topImg && topImg.complete && topImg.naturalWidth > 0,
        topHasLogo: !!topLogo,
      };
    });

    const ce = probe.centerEdge, te = probe.topEdge;
    // ① 边缘一致性：两件头像计算样式逐字等值（同组 token 的同主题计算值必等）
    const edgeSame = !!ce && !!te
      && ce.borderW === te.borderW && ce.borderStyle === te.borderStyle
      && ce.borderColor === te.borderColor && ce.shadow === te.shadow;
    ok(`${tag} ① dock 中键边缘 == 顶部头像边缘（宽/样式/描边色/光环逐字等值）`,
      edgeSame, `center=${JSON.stringify(ce)} top=${JSON.stringify(te)}`);
    // ② 边缘非空（收形批④ zero-shadow 退役实证）
    ok(`${tag} ② 边缘规格 = 1px 描边 + 非零光环`,
      ce && ce.borderW === '1px' && ce.borderStyle === 'solid' && ce.shadow !== 'none',
      `borderW=${ce && ce.borderW} shadow=${ce && ce.shadow}`);
    edgeByScheme[scheme] = ce;
    // ③ 主题映射（dark/light 全档成立 + 档间互异 → token 映射非写死）在循环外断言。

    // ④ 两态成立
    if (withAvatar) {
      ok(`${tag} ④ 自定义头像态：img 铺满 content 盒（≈42px，旧 2.5px 蓝环退役）+ 双面头像图`,
        probe.imgLoaded && probe.topHasImg && probe.imgW !== null && Math.abs(probe.imgW - 42) <= 0.8,
        `imgW=${probe.imgW} imgLoaded=${probe.imgLoaded} topImg=${probe.topHasImg}`);
    } else {
      ok(`${tag} ④ 默认态：双面 stand logo 回落`,
        probe.hasLogo && probe.topHasLogo && !probe.imgLoaded && !probe.topHasImg,
        `logo=${probe.hasLogo} topLogo=${probe.topHasLogo}`);
    }

    // ⑤ dock 其余形态零触碰 + 顶部形态不动
    ok(`${tag} ⑤ 中键外径恒 44×44（border-box 描边收进足印）+ 收进胶囊边界`,
      Math.abs(probe.centerW - 44) <= 0.6 && Math.abs(probe.centerH - 44) <= 0.6
        && probe.centerTop >= probe.dockTop - 0.5 && probe.centerBottom <= probe.dockBottom + 0.5,
      `${probe.centerW}×${probe.centerH} top=${probe.centerTop.toFixed(1)} dockTop=${probe.dockTop.toFixed(1)}`);
    ok(`${tag} ⑤ 呼吸缝 == --dock-gap 10px（胶囊形态零触碰）`, Math.abs(probe.gap - 10) <= 1.5, `gap=${probe.gap.toFixed(2)}`);
    ok(`${tag} ⑤ 顶部 Muse 大头像 56px 形态零触碰 + 同帧可见`,
      Math.abs(probe.topW - 56) <= 0.6 && Math.abs(probe.topH - 56) <= 0.6
        && probe.topInViewport && probe.centerInViewport,
      `top=${probe.topW}×${probe.topH} inFrame=${probe.topInViewport && probe.centerInViewport}`);

    // ── 截图（同帧 + 两件特写并排帧）─────────────────────────────
    await page.screenshot({ path: join(OUT, `edge-sameframe-${tag}.png`) });
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').screenshot({ path: join(OUT, `zoom-dock-${tag}.png`) });
    await page.locator('#agent-avatar-btn').screenshot({ path: join(OUT, `zoom-top-${tag}.png`) });

    ok(`${tag} R0 零 JS/console 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await page.close();
  }
}
await browser.close();

// ③ 主题映射互异断言（dark/light 两档 computed 值互异 ⇒ 走 token 映射非写死）
const dk = edgeByScheme.dark, lt = edgeByScheme.light;
ok(`③ 边缘双主题成立且档间互异（token 主题映射）`,
  !!dk && !!lt && dk.borderW === lt.borderW
    && (dk.borderColor !== lt.borderColor || dk.shadow !== lt.shadow),
  `dark=${JSON.stringify(dk)} light=${JSON.stringify(lt)}`);

console.log(`═══ dock-avatar-edge-unify-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
