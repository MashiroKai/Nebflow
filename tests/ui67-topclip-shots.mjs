// ui67-topclip-shots.mjs — UI 批⑥⑦ 前后对照截图（作者令 2026-10-21）
//
// ⑥ 毛玻璃渐隐只保留手机端最顶部 / ⑦ 桌面消息最顶部不得超过 header：
//   · 桌面 dark/light：40 对历史消息灌满 #chat → 滚到中段 → 顶部窄条截图
//     （含 header 全高 + 裁剪线下方一线）——裁剪线以上零消息文字（⑦ 实证）、
//     header 玻璃下无穿顶渐隐（⑥ 实证；修复前 = 作者截图
//     uploads/d7c3cb54…/500692431478166_截屏2026-10-21 10.24.00.jpg 的穿顶可见）；
//   · 手机 375：同场景滚动态顶条 —— 内容穿顶 + body.mobile-layer #header::after
//     渐隐裙边仍在（⑥「手机端维持现状」实证）。
//
// 运行：SHOTS_DIR=evidence/<ts>_canvas-stack-ui5/shots node tests/ui67-topclip-shots.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = normalize(resolve(HERE, '../src/main/resources/web'));
const PORT = Number(process.env.PORT || 8163);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/ui67-topclip-shots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const SID = 'cas-root-session';
const T0 = Date.now() - 60 * 60 * 1000;
const HISTORY = [];
for (let i = 0; i < 40; i++) {
  HISTORY.push({ type: 'user', text: `用户消息 ${i + 1}：请继续处理第 ${i + 1} 项，滚动穿顶对照样本行。`, ts: T0 + i * 8000 });
  HISTORY.push({ type: 'ai', text: `回复 ${i + 1}：好的，这一项已经处理完毕，下面是结果摘要与下一步计划说明，滚动穿顶对照样本行。`, ts: T0 + i * 8000 + 4000 });
}

async function bootViewport({ width, height, mobile, scheme }) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: scheme, isMobile: mobile, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  await page.route('**/api/**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat', { timeout: 15000 });
  await sleep(900);
  return { ctx, page };
}

/** 滚到中段（内容两侧都有富余），量裁剪线几何，截顶部窄条。 */
async function topStripShot(page, path, stripH) {
  const geo = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    chat.scrollTop = Math.max(0, (chat.scrollHeight - chat.clientHeight) / 2);
    return new Promise((r) => setTimeout(() => {
      const cr = chat.getBoundingClientRect();
      const hr = document.getElementById('header').getBoundingClientRect();
      r({ chatTop: +cr.top.toFixed(1), headerBottom: +hr.bottom.toFixed(1), headerTop: +hr.top.toFixed(1), scrollTop: chat.scrollTop });
    }, 160));
  });
  const W = page.viewportSize().width;
  await page.screenshot({ path, clip: { x: 0, y: 0, width: W, height: stripH } });
  return geo;
}

const browser = await chromium.launch();

/* 桌面 dark / light：顶部窄条 = header 全高 + 裁剪线下余量 */
const STRIP_H = 120;
for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await bootViewport({ width: 1440, height: 900, mobile: false, scheme });
  const geo = await topStripShot(page, join(SHOTS_DIR, `ui67-desktop-${scheme}-scrolled-top.png`), STRIP_H);
  console.log(`[desktop-${scheme}] ${JSON.stringify(geo)}`);
  await ctx.close();
}

/* 手机 375：现状渐隐穿顶（顶条 = 顶栏 + 裙边带） */
{
  const { ctx, page } = await bootViewport({ width: 375, height: 667, mobile: true, scheme: 'light' });
  const geo = await topStripShot(page, join(SHOTS_DIR, 'ui67-mobile-375-scrolled-top.png'), STRIP_H);
  console.log(`[mobile-375] ${JSON.stringify(geo)}`);
  await ctx.close();
}

await browser.close();
server.close();
console.log('ui67-topclip-shots done → ' + SHOTS_DIR);
