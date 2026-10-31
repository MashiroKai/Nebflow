// desktop-margin-revert-shots.mjs — 桌面边距还原批（作者令 2026-10-31 17:15
// 「电脑端边距不需要调整，还原成之前靠边的形态」）前后对照证据 + 几何断言。
//
// 考古定位：#chat 水平内距 16px 由 5f8657cfc（2026-10-30 冷启动批⑦「消息列表
// 左右留白」）引入，旧值 = 8px 0 36px（左右 0 = 靠边）。本批回退桌面腿；
// 手机端（≤768px 视口）现行 16px 不动 —— 回退由 mobile.css 媒体查询承接保形。
//
// 本脚本：真 web 树 + 真 WS 桩（形制同 tests/composer-slim-shots.mjs），
// 桌面视口 1440×900 × 亮/暗双主题输出整视口截图 + 几何探针；并断言：
//   A. TAG=before → #chat 水平内距 = 16px；TAG=after → = 0px（靠边）；
//   B. TAG=after → 首个消息行左缘 == #chat 内容缘（行不内缩）；
//   C. 手机视口（390×844，双 TAG）→ #chat 水平内距恒 = 16px（不动断言）。
// 用法：TAG=before SHOTS_DIR=… node tests/desktop-margin-revert-shots.mjs
//       TAG=after  SHOTS_DIR=… node tests/desktop-margin-revert-shots.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TAG = process.env.TAG || 'before';
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(HERE, '..', 'evidence', '20261031_desktop-margin-revert', TAG);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-margin-revert';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// 历史夹具：AI/用户交替 12 条，把消息列撑出可滚形态（边距对照面）。
const HISTORY = Array.from({ length: 12 }, (_, i) => i % 2 === 0
  ? { type: 'ai', text: `桌面边距还原对照消息 ${i + 1}：电脑端消息列应贴边（左右内距 0），手机端维持 16px。`, timestamp: NOW - 600_000 + i * 10_000 }
  : { type: 'user', text: `收到，第 ${i + 1} 项继续。`, timestamp: NOW - 600_000 + i * 10_000 });

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

async function boot(colorScheme, viewport) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${TAG}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${TAG}] pageerror: ${e.message}`));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await sleep(1200);
  return { ctx, page };
}

/** 几何探针：#chat 水平内距 + 行内缩量。 */
async function probe(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    const cs = getComputedStyle(chat);
    const cr = chat.getBoundingClientRect();
    const first = document.querySelector('#chat .row');
    const fr = first ? first.getBoundingClientRect() : null;
    return {
      padL: cs.paddingLeft, padR: cs.paddingRight,
      chatX: cr.left,
      rowInsetL: fr ? +(fr.left - cr.left - parseFloat(cs.paddingLeft)).toFixed(2) : null,
    };
  });
}

const EXPECT = TAG === 'before' ? 16 : 0;

// ── 桌面视口 1440×900 × 亮/暗 ──────────────────────────────────────
for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await boot(scheme, { width: 1440, height: 900 });
  const p = await probe(page);
  console.log(`[${TAG}] desktop/${scheme} probe: ${JSON.stringify(p)}`);
  ok(`A·${scheme} 桌面 #chat 水平内距 = ${EXPECT}px`,
    parseFloat(p.padL) === EXPECT && parseFloat(p.padR) === EXPECT, JSON.stringify(p));
  if (TAG === 'after') {
    ok(`B·${scheme} 桌面消息行贴边（行内缩量 = 0，不再内缩）`, p.rowInsetL === 0, `rowInsetL=${p.rowInsetL}`);
  }
  await page.screenshot({ path: join(SHOTS_DIR, `desktop-${scheme}.png`), fullPage: false });
  await ctx.close();
}

// ── 手机视口 390×844（暗）——「不动」断言：双 TAG 均须 16px ──────────
{
  const { ctx, page } = await boot('dark', { width: 390, height: 844 });
  const p = await probe(page);
  console.log(`[${TAG}] mobile/390 probe: ${JSON.stringify(p)}`);
  ok('C·手机视口 #chat 水平内距恒 = 16px（本批不动）',
    parseFloat(p.padL) === 16 && parseFloat(p.padR) === 16, JSON.stringify(p));
  await page.screenshot({ path: join(SHOTS_DIR, 'mobile-390.png'), fullPage: false });
  await ctx.close();
}

ok('console 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

await browser.close();
server.close();
console.log(failures === 0 ? `ALL PASS (${TAG})` : `${failures} FAIL (${TAG})`);
process.exit(failures === 0 ? 0 : 1);
