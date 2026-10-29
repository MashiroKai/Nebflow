#!/usr/bin/env node
// wiki-graph-seal-shots.mjs — wiki-graph 边栏入口封存批（作者手写想法①
// 2026-10-29「词典边栏的图谱入口暂时隐藏」）的**前后对比截图**取证脚本。
//
// 自包含（route 拦截静态文件 + mock WS，无后端、无真实端口，harness 同
// friends-dev-default-on.spec.mjs）：按 --config 注入 nebflow.json 形态，
// 渲染真实源码树的控制台（vendor 全本地，lucide 图标实渲染），存两图：
//   evidence/20261029_wiki-graph-seal/<tag>.png      （整窗 1440×900）
//   evidence/20261029_wiki-graph-seal/<tag>.bar.png  （左侧 chrome 放大条）
//
// 用法：
//   node tests/wiki-graph-seal-shots.mjs --tag before   --config '{"features":{"wikiGraph":true}}'
//   node tests/wiki-graph-seal-shots.mjs --tag after-sealed  --config '{}'
//   node tests/wiki-graph-seal-shots.mjs --tag after-unsealed --config '{"features":{"wikiGraph":true}}'
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261029_wiki-graph-seal');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
};
const tag = arg('tag', 'shot');
const configJson = arg('config', '{}');

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(e.message));
await page.addInitScript(() => localStorage.setItem('nebflow_token', 't'));
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch {
    return route.fulfill({ status: 404, body: 'not found' });
  }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: configJson, configured: true, onboarding: 'done', models: [], defaults: {} }));
  sendConfig();
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});
await page.goto('http://localhost:1/');
await page.waitForFunction(async () => {
  const s = (await import('/js/state.js')).default;
  return s.ws && s.ws.readyState === 1;
}, undefined, { timeout: 15000 });
await new Promise(r => setTimeout(r, 1200)); // configData latch + 图标/字体渲染窗口

await page.screenshot({ path: join(OUT, `${tag}.png`) });
// 左侧 chrome 放大条：activity bar（玻璃卡）+ sidebar 左缘，x∈[0,120] 全高。
await page.screenshot({ path: join(OUT, `${tag}.bar.png`), clip: { x: 0, y: 0, width: 120, height: 900 } });

console.log(`wiki-btn in DOM: ${await page.locator('#wiki-btn').count()}  (${tag}, config=${configJson})`);
console.log(`page errors: ${pageErrors.length}${pageErrors.length ? ' — ' + pageErrors.join('; ') : ''}`);
console.log(`saved: ${join(OUT, tag + '.png')} (+ .bar.png)`);
await browser.close();
