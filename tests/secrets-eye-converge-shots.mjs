// secrets-eye-converge-shots.mjs — 小扫尾批 2026-10-22 ②：secretsPanel 眼图标
// 单源收敛的渲染对照验证。
//
// 改动：secretsPanel.js 私有 EYE_SVG/EYE_OFF_SVG（lucide v0.454 眼形）退役 →
// providersUi.js 单源 eyeSvg/eyeOffSvg（与 chat.js AskUser 密钥卡、设置页
// provider 密码域同一枚眼）。本脚本验证三件事：
//   A. 单源字节等式：面板行眼 / 草稿眼 / AskUser 卡眼 的 innerHTML 三方相等，
//      且逐字节等于 providersUi 模块导出的 eyeSvg/eyeOffSvg（同一 SVG 源串
//      ⇒ 同一光栅化像素——像素级一致的机器可断言面）。
//   B. 功能语义零变化：揭示=一次 GET 后 field 显值+eyeOff 面，再点=烧回占位
//      重掩码；草稿眼=纯表现层切换（不 fetch）；aria-pressed 随态翻转。
//   C. 前后对照截图：同一脚本 TAG=before（HEAD 私有副本）/TAG=after（单源）
//      各跑一遍，亮暗双主题 → SHOTS_DIR/。AskUser 卡（chat.js，本批未触碰）
//      前后两张必须逐字节相同（零附带损伤的回归证据）；密钥面板眼两张按预期
//      不同（lucide→feather 眼形统一，本批唯一预期视觉变化）。
//
// 运行：TAG=before SHOTS_DIR=… node tests/secrets-eye-converge-shots.mjs
// （静态服务 + WS/REST mock 模式照搬 tests/design-language-shots.mjs 先例）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8178);
const BASE = `http://127.0.0.1:${PORT}`;
const TAG = process.env.TAG || 'after';
const SHOTS_DIR = join(process.env.SHOTS_DIR || resolve(HERE, '../evidence/20261022_secrets-eye-converge'), TAG);
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（真实 web/ 树，同 design-language-shots.mjs）──────────────
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB, p);
  if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── Mock（metadata-only 密钥契约，同 design-language-shots.mjs）────────
const SECRETS = [
  { name: 'GITHUB_TOKEN', size: 40, mtime: Date.now() - 86400e3, mode: '0600', refs: ['nebflow-website'] },
  { name: 'OPENAI_API_KEY', size: 51, mtime: Date.now() - 3600e3, mode: '0644', refs: [] },
  { name: 'FEISHU_APP_SECRET', size: 32, mtime: Date.now() - 7 * 86400e3, mode: '0600', refs: ['social-feishu'] },
];
const CONFIG = {
  llm: { providers: { 'openai': { apiKeyRef: 'OPENAI_API_KEY', models: ['gpt-5.2'], default: true } } },
  safety: { defaultMode: 'confirm-edits' },
};

const consoleErrors = [];
const failures = [];
function check(name, ok, detail = '') {
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${ok || !detail ? '' : ' — ' + detail}`);
  if (!ok) failures.push(`${name}: ${detail}`);
}
// console 静噪（仅此一条规则，其余错误照收）：mock 宇宙不带后端，页面启动期的
// /api/ 状态探针 404（/api/delegates /api/projects /api/agents/*/avatar
// /api/canvas-tabs /api/nf-authcheck /api/chains /api/decision/health
// /api/models/capabilities）是夹具噪声，非本批面。静态资源 404 仍算错误。
function isApiNoise(text) {
  return /Failed to load resource.*404/.test(text);
}

// 面内断言一次取齐：行眼/草稿眼/卡眼的 innerHTML + providersUi 模块单源串。
// 比对口径：innerHTML 读回会做 DOM 归一化（<path/> → <path></path>，HTML 序列
// 化器对非 void 元素不自闭合）——单源串先过同一归一化再比，等式即「同一 SVG
// 源 ⇒ 同一 DOM ⇒ 同一光栅化像素」；另断三方 innerHTML 逐字节互等。
async function eyeProbe(page) {
  return page.evaluate(async () => {
    const m = await import('/js/sidebar/providersUi.js');
    const norm = (s) => { const d = document.createElement('div'); d.innerHTML = s; return d.innerHTML; };
    const rowEye = document.querySelector('.secrets-row-detail [data-eye]');
    const draftEye = document.querySelector('[data-draft-eye]');
    const cardEye = document.querySelector('.option-box[data-request-id="req-shot-1"] .option-secret-eye');
    const rowField = document.querySelector('.secrets-row-detail [data-edit]');
    return {
      srcEyeNorm: norm(m.eyeSvg), srcEyeOffNorm: norm(m.eyeOffSvg),
      rowEyeHtml: rowEye ? rowEye.innerHTML : null,
      draftEyeHtml: draftEye ? draftEye.innerHTML : null,
      cardEyeHtml: cardEye ? cardEye.innerHTML : null,
      rowMasked: rowField ? rowField.classList.contains('masked') : null,
      rowValue: rowField ? rowField.value : null,
      rowPressed: rowEye ? rowEye.getAttribute('aria-pressed') : null,
    };
  });
}

const browser = await chromium.launch();
for (const scheme of ['light', 'dark']) {
  console.log(`theme=${scheme}`);
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route('**/api/secrets', (r) => r.fulfill({ status: 200, json: { secrets: SECRETS } }));
  await ctx.route('**/api/secrets/*/value', (r) => r.fulfill({ status: 200, json: { name: 'x', value: 'sk-sample-value-0123456789abcdef' } }));
  await ctx.route('**/api/neblink/status', (r) => r.fulfill({ status: 200, json: { loggedIn: false, username: '', devices: [] } }));
  await ctx.route('**/api/plugins', (r) => r.fulfill({ status: 200, json: { plugins: [] } }));
  await ctx.route('**/api/auth/status', (r) => r.fulfill({ status: 200, json: { authenticated: true } }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify(CONFIG) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: '眼图标收敛批', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 'sess-1', messages: [], hasMore: false, offset: 0 }));
    ws.send(JSON.stringify({
      type: 'askUser', requestId: 'req-shot-1', sessionId: 'sess-1', agentName: 'Nebula',
      items: [{ question: '请提供 API Key', secret: true }],
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify(CONFIG) }));
      else if (m.type === 'getToolResultTtl') ws.send(JSON.stringify({ type: 'toolResultTtl', ttlHours: 24 }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${TAG}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${TAG}] pageerror: ${e.message}`));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(900);

  // ⓪ 打开设置页一次再关：#btn-open-secrets 由 renderSettings 挂载
  // （design-language-shots.mjs 同款序列；直达点击会被 ?. 静默吞掉）
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-overlay.on #settings-modal', { timeout: 8000 });
  await sleep(500);
  await page.click('#settings-modal-close');
  await page.waitForSelector('#settings-overlay.on', { state: 'detached', timeout: 8000 }).catch(() => {});
  await sleep(300);

  // ① 密钥面板：静息列表（按钮在已关设置弹窗 DOM 内=hidden，JS click 仍达）
  await page.waitForSelector('#btn-open-secrets', { timeout: 8000, state: 'attached' });
  await page.evaluate(() => { document.getElementById('btn-open-secrets')?.click(); });
  await page.waitForSelector('#secrets-overlay.on #secrets-modal', { timeout: 8000 });
  await sleep(900);
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-1-secrets-rest.png`) });

  // ② 第一行原位展开 → 掩码面（行眼 = providersUi eyeSvg，逐字节）
  await page.locator('.secrets-row-head').first().click();
  await sleep(600);
  let p = await eyeProbe(page);
  check(`${scheme} 行眼(掩码)=单源 eyeSvg（DOM归一化逐字节）`, p.rowEyeHtml === p.srcEyeNorm, `row=${p.rowEyeHtml?.slice(0, 80)}…`);
  check(`${scheme} 掩码面 aria-pressed=false`, p.rowPressed === 'false', String(p.rowPressed));
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-2-secrets-open-masked.png`) });
  await page.locator('[data-eye="GITHUB_TOKEN"]').screenshot({ path: join(SHOTS_DIR, `${scheme}-eye-masked.png`) });

  // ③ 点眼 → 揭示（一次 GET）：field 显 mock 值，眼 = eyeOffSvg，pressed=true
  await page.locator('[data-eye="GITHUB_TOKEN"]').click();
  await sleep(600);
  p = await eyeProbe(page);
  check(`${scheme} 揭示后 field 显值`, p.rowValue === 'sk-sample-value-0123456789abcdef', String(p.rowValue));
  check(`${scheme} 揭示后眼=单源 eyeOffSvg（DOM归一化逐字节）`, p.rowEyeHtml === p.srcEyeOffNorm, `row=${p.rowEyeHtml?.slice(0, 80)}…`);
  check(`${scheme} 揭示后 aria-pressed=true`, p.rowPressed === 'true', String(p.rowPressed));
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-3-secrets-open-revealed.png`) });
  await page.locator('[data-eye="GITHUB_TOKEN"]').screenshot({ path: join(SHOTS_DIR, `${scheme}-eye-revealed.png`) });

  // ④ 再点 → 重掩码：fetched 值烧回占位（value=''），眼回到 eyeSvg
  await page.locator('[data-eye="GITHUB_TOKEN"]').click();
  await sleep(400);
  p = await eyeProbe(page);
  check(`${scheme} 重掩码烧回占位`, p.rowMasked === true && p.rowValue === '', `masked=${p.rowMasked} value="${p.rowValue}"`);
  check(`${scheme} 重掩码眼回到 eyeSvg（DOM归一化逐字节）`, p.rowEyeHtml === p.srcEyeNorm);

  // ④b AskUser 卡眼（chat.js 面，本批未触碰）：行眼仍在位 → 三方互等 + 卡面截图
  const card = page.locator('.option-box[data-request-id="req-shot-1"]').last();
  await card.scrollIntoViewIfNeeded();
  await sleep(300);
  await card.screenshot({ path: join(SHOTS_DIR, `${scheme}-4-ask-card.png`) });
  check(`${scheme} 卡眼=行眼=单源（三方 innerHTML 逐字节互等）`, p.cardEyeHtml === p.rowEyeHtml && p.rowEyeHtml === p.srcEyeNorm, `card===row: ${p.cardEyeHtml === p.rowEyeHtml}, row===src: ${p.rowEyeHtml === p.srcEyeNorm}`);
  await page.locator('[data-eye="GITHUB_TOKEN"]').screenshot({ path: join(SHOTS_DIR, `${scheme}-eye-remasked.png`) });

  // ⑤ 新建草稿眼：表现层切换（先键入再切换 → 不 fetch，值保留）
  await page.locator('[data-add]').click();
  await sleep(400);
  p = await eyeProbe(page);
  check(`${scheme} 草稿眼(初始)=单源 eyeSvg（两方 DOM归一化逐字节）`, p.draftEyeHtml === p.srcEyeNorm, `draft=${p.draftEyeHtml?.slice(0, 80)}…`);
  await page.locator('[data-draft-value]').fill('draft-typed-value');
  await page.locator('[data-draft-eye]').click();
  await sleep(300);
  p = await eyeProbe(page);
  const draftVal = await page.locator('[data-draft-value]').inputValue();
  check(`${scheme} 草稿眼切换保留键入值`, draftVal === 'draft-typed-value', `draft="${draftVal}"`);
  check(`${scheme} 草稿眼切换后=eyeOffSvg（DOM归一化逐字节）`, p.draftEyeHtml === p.srcEyeOffNorm);

  await ctx.close();
}
await browser.close();
server.close();
const errs = consoleErrors.filter((t) => !t.includes('favicon') && !isApiNoise(t));
console.log(`done TAG=${TAG} → ${SHOTS_DIR}`);
console.log(errs.length ? `console errors (${errs.length}):\n` + errs.slice(0, 10).join('\n') : 'console: no errors');
check(`${TAG} console 零错误`, errs.length === 0, errs.slice(0, 3).join(' | '));
console.log(failures.length ? `RESULT: ${failures.length} FAILURE(S)` : 'RESULT: ALL PASS');
process.exitCode = failures.length ? 1 : 0;
