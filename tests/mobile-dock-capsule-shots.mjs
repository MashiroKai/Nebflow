#!/usr/bin/env node
// mobile-dock-capsule-shots.mjs — dock 悬浮胶囊批（作者令 2026-11-01 21:07 两件）
// 双主题×双页面截图取证 + 机械探针。产出 → evidence/20261101_dock-capsule/：
//   msg-<scheme>.png        聊天根（消息页）胶囊全景 —— 输入卡与胶囊上缘呼吸缝可见
//   workbench-<scheme>.png  非消息根页（工作台）—— 胶囊悬浮在承载面带上
// 探针（与令逐条对齐）：
//   ① 胶囊化：r999 全圆角 + 左右下三边屏幕边距（inset-x 12 / inset-b 8，
//     测试视口 safe=0 ⇒ bottom == 8；真机 home 避让 = safe 并入公式）
//   ② 本体高 = --dock-h 54（safe 垫外置）
//   ③ 毛玻璃材质 = 面级浮件档（--glass-bg + backdrop blur，#input-bar 同配方）
//   ④ 呼吸缝：输入卡底缘与胶囊上缘缝 == --dock-gap 10px（收形批零缝退役）
//   ⑤ 承载面带区**零垫层**（dock 悬浮批 2026-11-21，作者裁定 10:41「手机端 dock 在
//      其他页面都是悬浮效果，但主会话却是截断的——都得是悬浮的」）：`#mobile-pages`
//      容器与它的 ::before 一律透明（垫层整族退役），带区里画的是宿主真实内容；
//      改前本项判的是「背板底色 == 页面底色」，那正是被裁定退役的形态。
//      根页的不透明承底由页面壳自己给（.mobile-page top:0/bottom:0 + --color-bg）。
//   ⑥ 中键收形语义零回归：44×44 收进胶囊边界内、box-shadow none
//   R0 零 JS/console 错误（每帧）
// 走 mobile-dock-shape-shots 同款静态 harness（无后端）。
// Run: node tests/mobile-dock-capsule-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_dock-capsule');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'dock-capsule-session';

mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // harness：stub 掉 SW 注册（mobile-dock.spec 同款——静态 harness 无 /sw.js）。
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
      if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForFunction(() => !!document.getElementById('mobile-dock'), null, { timeout: 10000 });
  await page.waitForTimeout(600);

  const probe = () => page.evaluate(() => {
    const r = (el) => el.getBoundingClientRect();
    const dock = document.getElementById('mobile-dock');
    const dr = r(dock);
    const cs = getComputedStyle(dock);
    const bar = document.getElementById('input-bar');
    const br = r(bar);
    const probeVar = (v) => {
      const s = document.createElement('span');
      s.style.marginTop = `var(${v}, 0px)`;
      document.body.appendChild(s);
      const val = parseFloat(getComputedStyle(s).marginTop) || 0;
      s.remove();
      return val;
    };
    const pages = document.getElementById('mobile-pages');
    const bandCs = getComputedStyle(pages, '::before');
    const containerCs = getComputedStyle(pages);
    // 根页自己的不透明承底（页面壳；垫层退役后「带区底色」全部由它提供）
    const livePage = pages.querySelector('.mobile-page.live');
    const pageOwnBg = livePage ? getComputedStyle(livePage).backgroundColor : 'n/a';
    const bgProbe = document.createElement('span');
    bgProbe.style.background = 'var(--color-bg)';
    document.body.appendChild(bgProbe);
    const pageBg = getComputedStyle(bgProbe).backgroundColor;
    bgProbe.remove();
    const center = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
    const cr = r(center);
    return {
      vw: innerWidth, vh: innerHeight,
      radius: cs.borderRadius,
      backdrop: cs.backdropFilter || '',
      dockLeft: dr.left, dockRight: dr.right, dockFromBottom: innerHeight - dr.bottom, dockTop: dr.top, dockH: dr.height,
      barBottom: br.bottom,
      insetX: probeVar('--dock-inset-x'), insetB: probeVar('--dock-inset-b'), gap: probeVar('--dock-gap'),
      bandBg: bandCs.backgroundColor, bandH: bandCs.height, pageBg,
      containerBg: containerCs.backgroundColor, pageBgOwn: pageOwnBg,
      centerShadow: getComputedStyle(center).boxShadow,
      centerW: cr.width, centerH: cr.height, centerInside: cr.top >= dr.top - 0.5 && cr.bottom <= dr.bottom + 0.5,
    };
  });

  // ── 消息页（聊天根）──────────────────────────────────────────────
  const msg = await probe();
  ok(`${scheme} ① 胶囊全圆角 r999`, msg.radius === '999px', msg.radius);
  ok(`${scheme} ① 左右边距 == inset-x(12)`,
    Math.abs(msg.dockLeft - msg.insetX) <= 0.5 && Math.abs((msg.vw - msg.dockRight) - msg.insetX) <= 0.5,
    `left=${msg.dockLeft.toFixed(1)} right=${(msg.vw - msg.dockRight).toFixed(1)} inset-x=${msg.insetX}`);
  ok(`${scheme} ① 底边距 == inset-b(8)（safe=0 视口；真机 = safe+8 home 避让）`,
    Math.abs(msg.dockFromBottom - msg.insetB) <= 0.5, `bottom=${msg.dockFromBottom.toFixed(1)} inset-b=${msg.insetB}`);
  ok(`${scheme} ② 本体高 == 54（safe 垫外置）`, Math.abs(msg.dockH - 54) <= 0.5, `h=${msg.dockH.toFixed(1)}`);
  ok(`${scheme} ③ 毛玻璃 blur 在档`, msg.backdrop.includes('blur'), msg.backdrop);
  ok(`${scheme} ④ 呼吸缝 == 10px（--dock-gap）`,
    Math.abs((msg.dockTop - msg.barBottom) - msg.gap) <= 1.5 && msg.gap > 0,
    `gap=${(msg.dockTop - msg.barBottom).toFixed(2)} token=${msg.gap}`);
  ok(`${scheme} ⑥ 中键几何零回归（44×44 收进胶囊；边缘随边缘统一批 = 顶部头像同款非零光环）`,
    Math.abs(msg.centerW - 44) <= 0.5 && Math.abs(msg.centerH - 44) <= 0.5 && msg.centerInside && msg.centerShadow !== 'none',
    `w=${msg.centerW} h=${msg.centerH} inside=${msg.centerInside} shadow=${msg.centerShadow}`);
  await page.screenshot({ path: join(OUT, `msg-${scheme}.png`) });

  // ── 非消息根页（工作台）─────────────────────────────────────────
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="workbench"]')?.classList.contains('live'), null, { timeout: 5000 });
  await page.waitForTimeout(400);
  const wb = await probe();
  ok(`${scheme} ⑤ 承载面带区零垫层（#mobile-pages 容器与 ::before 一律透明 —— 带区画宿主真实内容）`,
    wb.bandBg === 'rgba(0, 0, 0, 0)' && wb.containerBg === 'rgba(0, 0, 0, 0)',
    `band=${wb.bandBg} container=${wb.containerBg}`);
  ok(`${scheme} ⑤ 根页不透明承底由页面壳自己给（.mobile-page 底色 == --color-bg）`,
    wb.pageBgOwn === wb.pageBg, `page-own=${wb.pageBgOwn} token=${wb.pageBg}`);
  ok(`${scheme} ① 根页胶囊边距同档（悬浮形态跨页恒定）`,
    Math.abs(wb.dockLeft - wb.insetX) <= 0.5 && Math.abs(wb.dockFromBottom - wb.insetB) <= 0.5 && wb.radius === '999px',
    `left=${wb.dockLeft.toFixed(1)} bottom=${wb.dockFromBottom.toFixed(1)} r=${wb.radius}`);
  await page.screenshot({ path: join(OUT, `workbench-${scheme}.png`) });

  ok(`${scheme} R0 零 JS/console 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.close();
}
await browser.close();
console.log(`═══ mobile-dock-capsule-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);
