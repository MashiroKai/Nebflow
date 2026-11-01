#!/usr/bin/env node
// shots.mjs — 单行排布批（2026-11-01 作者令 16:18）实拍取证脚本。
// 验收面：before/after 布局实拍（含 ask 展开态 = 对话框在按钮行上方），
// 深浅双主题 × 双引擎（Chromium + WebKit）× 桌面/手机。
// 用法：node shots.mjs --web <web树根> --out <输出目录> [--tag before|after] [--engines chromium,webkit]
// 端口默认 8920（≥8899 避 Safari bad-port 封 localhost:1 —— 前批 WebKit 跑法注记同源）。

import { chromium, webkit } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from '../../tests/nf-ticket-mock.mjs';

const arg = (k, d = '') => {
  const i = process.argv.indexOf('--' + k);
  return i >= 0 ? process.argv[i + 1] : d;
};
const WEB_DIR = resolve(arg('web'));
const OUT = resolve(arg('out'));
const TAG = arg('tag', 'after');
const ENGINES = (arg('engines', 'chromium,webkit')).split(',');
const PORT = Number(process.env.PORT || 8920);
const BASE = `http://127.0.0.1:${PORT}`;

mkdirSync(OUT, { recursive: true });

const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/woff',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  // WebKit 的 service worker fetch 绕过 page.route（/api/canvas-tabs 等实证）——
  // 服务器侧兜底应用数据面（web 树本体不受影响）。
  if (p.startsWith('/api/') || p.startsWith('/agents/')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(p === '/api/canvas-tabs' ? JSON.stringify({ v: 2, tabs: [] }) : '{}');
    return;
  }
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end('nf'); } }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function boot(browser, engine, { mobile = false, colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({
    viewport: mobile ? { width: 390, height: 844 } : { width: 1600, height: 1000 },
    colorScheme, hasTouch: mobile,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e.message || e).slice(0, 200)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 200)); });
  page.on('response', (r) => { if (r.status() === 404) errors.push('404: ' + r.url().replace(BASE, '')); });
  // WebKit 对 '**/api/**' glob 偶发不命中（/api/canvas-tabs 等 404 实证）——
  // 谓词路由双引擎一致。
  await page.route((u) => (u.pathname || '').startsWith('/api/'), (r) => r.fulfill({ json: {} }));
  // 静态 agent 档（/agents/** 不在 /api 域，mock 空档防 404 console 噪声——
  // 应用逻辑对缺席容忍，取证只关心 web 树本体的零错）。
  await page.route('**/agents/**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const name = (m.file && m.file.name) || 'file';
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        setTimeout(() => ws.send(JSON.stringify({
          type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '', fileName: name, messages: [],
        })), 60);
      }
      if (m.type === 'askQuestion') {
        const sid = m.sessionId;
        setTimeout(() => ws.send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: '这是 mock 回答：该文档讨论了记忆检索的调优参数与 A/B 回归证据。' })), 120);
        setTimeout(() => ws.send(JSON.stringify({ type: 'done', sessionId: sid, finalText: '这是 mock 回答：该文档讨论了记忆检索的调优参数与 A/B 回归证据。' })), 260);
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(800);
  return { ctx, page, errors };
}

async function openMd(page, mobile) {
  const absPath = '/tmp/canvas-ask-shots/记忆检索调优笔记.md';
  await page.evaluate(({ absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: '记忆检索调优笔记.md',
        content: '# 记忆检索调优笔记\n\nτ = 30 天，A/B 回归证据见 §3。\n\n## 结论\n\n- 保留近似缓存\n- 丢弃长尾键\n\n```js\nconst tau = 30;\n```\n',
        absPath, pinned: true,
      },
    }));
  }, { absPath });
  if (mobile) {
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'attached', timeout: 10000 });
    // 进 canvas 根页（dock 键）——手机层 canvas 页可见性闸。
    await page.click('#mobile-dock .dock-key[data-dock="canvas"]').catch(() => {});
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 8000 }).catch(() => {});
  } else {
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"] .canvas-md-viewer`, { timeout: 15000 });
  }
  await sleep(600);
  return absPath;
}

const rows = [];
const engineBoot = { chromium, webkit };
for (const engine of ENGINES) {
  const mod = engineBoot[engine];
  if (!mod) { rows.push(`${engine}: SKIP（未知引擎）`); continue; }
  let browser;
  try { browser = await mod.launch(); } catch (e) { rows.push(`${engine}: SKIP（launch 失败 ${String(e).slice(0, 80)}）`); continue; }
  try {
    for (const theme of ['dark', 'light']) {
      for (const mobile of [false, true]) {
        const face = mobile ? 'mobile' : 'desktop';
        const { ctx, page, errors } = await boot(browser, engine, { mobile, colorScheme: theme });
        await openMd(page, mobile);
        const slug = `${TAG}-${engine}-${theme}-${face}`;
        await page.screenshot({ path: join(OUT, `${slug}-collapsed.png`) }).catch(() => {});
        // 展开：ask 对话框（含一轮 mock 问答，验证气泡列升腾形态）
        const fabVisible = await page.locator('.ask-fab').isVisible().catch(() => false);
        if (fabVisible) {
          await page.locator('.ask-fab').click();
          await sleep(500);
          await page.fill('.ask-dock input', '这份文档的结论是什么？');
          await page.press('.ask-dock input', 'Enter');
          await sleep(900);
        }
        await page.screenshot({ path: join(OUT, `${slug}-expanded.png`) }).catch(() => {});
        rows.push(`${slug}: ${errors.length === 0 ? 'zero-error' : 'ERRORS → ' + errors.slice(0, 3).join(' | ')}`);
        await ctx.close();
      }
    }
  } finally { await browser.close(); }
}
server.close();
console.log(rows.join('\n'));
