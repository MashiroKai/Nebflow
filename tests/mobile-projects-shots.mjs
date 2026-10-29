#!/usr/bin/env node
// mobile-projects-shots.mjs — 手机端「项目」页单列批 前后对比截图生成器
//（作者令 2026-10-29 13:25：手机端项目界面改一列往下翻，不要可右拉的排布）。
//
// 用法：
//   node tests/mobile-projects-shots.mjs <outDir> [--web-root <webDir>] [--pan <px>] [--tag <name>]
//     <outDir>     截图输出目录（自动创建）
//     --web-root   静态资源根（默认本仓 src/main/resources/web；「修前」截图
//                  指向 HEAD 临时工作树的同名目录即可产出改动前形态）
//     --pan <px>   截图前把 .team-scroll 横向滚过 px（复现作者「右拉」中段态）
//     --tag <name> 产物名前缀（默认 projects；前/后对拍各取 before/after）
//
// 产物：projects-390.png（390×844 @2x 手机推送页）。
// 断言钉在 tests/mobile-projects-single-column.spec.mjs（M1-M4/D1），本脚本只出图。

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const outDir = resolve(args[0] || 'evidence/tmp-shots');
mkdirSync(outDir, { recursive: true });
const webRootIdx = args.indexOf('--web-root');
const WEB = webRootIdx >= 0 ? resolve(args[webRootIdx + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const panIdx = args.indexOf('--pan');
const PAN = panIdx >= 0 ? Number(args[panIdx + 1]) || 0 : 0;
const tagIdx = args.indexOf('--tag');
const TAG = tagIdx >= 0 ? String(args[tagIdx + 1]) : 'projects';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'shots-root-session';

// 五项目夹具（与 single-column spec 同源量级：≥4 卡即复现修复前竖向换列）。
function projectsFixture() {
  return { projects: [
    { name: 'CdZnTe 探测器研究', workspace: '/Users/kaiyu/.stand/projects/CdZnTe-detector-research-lab-notebook', description: 'CdZnTe（碲锌镉）探测器研究——探测器制备、能谱性能表征与读出电子学联调', agentFile: true, createdAt: Date.now() - 86400_000 },
    { name: 'SlideBlocks Studio', workspace: '/Users/kaiyu/Claude code/SlideBlocksStudio', description: 'SlideBlocks 演示引擎与课件编辑器', agentFile: true, createdAt: Date.now() - 7200_000 },
    { name: 'Slideblocks Skill Development', workspace: '/Users/kaiyu/Claude code/Slideblocks-Skill-Development-Long-Name-Path', description: '技能包开发与评测流水线', agentFile: true, createdAt: Date.now() - 3600_000 },
    { name: 'D0STEL', workspace: '/Users/kaiyu/Claude code/D0STEL', description: 'D0STEL——SiPM/D0STEL 探测器读出固件', agentFile: true, createdAt: Date.now() - 1800_000 },
    { name: 'chisel-fpga', workspace: '/Users/kaiyu/.stand/projects/RendererIsland-macOS-liuhai-display-driver-fpga', description: '', agentFile: true, createdAt: Date.now() - 600_000 },
  ] };
}

const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true,
  deviceScaleFactor: 2, colorScheme: 'light',
});
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    if (p === '/api/projects') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(projectsFixture()) });
    if (p.startsWith('/api/projects/') && p.endsWith('/flow-map')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'x', updatedAt: Date.now() } }) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
  catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
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
await page.waitForTimeout(300);

// 开右列 → projects 推送页
await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
await page.locator('.mh-dd[data-dd="right"] [data-face="projects"]').click();
await page.waitForSelector('.mobile-page[data-page="projects"].live', { timeout: 5000 });
await page.waitForSelector('.mobile-page[data-page="projects"] .team-card.project-card', { timeout: 8000 });
// 推入动画（260ms）终态
await page.waitForFunction(() => Math.round(document.querySelector('.mobile-page[data-page="projects"]').getBoundingClientRect().x) === 0, { timeout: 5000 });
await page.waitForTimeout(200);

const metrics = await page.evaluate(() => {
  const s = document.querySelector('.mobile-page[data-page="projects"] .team-scroll');
  const cards = [...document.querySelectorAll('.mobile-page[data-page="projects"] .team-card.project-card')]
    .map((el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width) }; });
  return { scrollW: s.scrollWidth, clientW: s.clientWidth, cards: cards.length, xs: [...new Set(cards.map((c) => c.x))] };
});

if (PAN > 0) {
  await page.evaluate((px) => { document.querySelector('.mobile-page[data-page="projects"] .team-scroll').scrollLeft = px; }, PAN);
  await page.waitForTimeout(150);
}

const tag = PAN > 0 ? `${TAG}-390-pan${PAN}` : `${TAG}-390`;
await page.screenshot({ path: join(outDir, `${tag}.png`) });
console.log(`[shots] ${tag}.png  metrics=${JSON.stringify(metrics)}`);
await browser.close();
