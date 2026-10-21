// design-language-shots.mjs — 三面板设计语言统一批 前后对照截图（作者令 2026-10-21）
//
// 面板：① 设置页（#settings-modal）② 密钥面板（#secrets-modal）③ AskUser 密钥请求卡片
// （.option-box secret 题）。真实 DOM + 真实 CSS（静态服务 web/ 树 + WS/REST mock），
// 同一脚本跑两遍：TAG=before（整改前）与 TAG=after（整改后），亮/暗双主题。
//
// 产出（SHOTS_DIR/<TAG>/）：
//   light-1-settings.png / dark-1-settings.png           设置页
//   light-2-secrets.png / dark-2-secrets.png             密钥面板（静息列表）
//   light-3-secrets-open.png / dark-3-secrets-open.png   密钥面板（行内展开 + 新建尾行）
//   light-4-ask-card.png / dark-4-ask-card.png           AskUser 密钥请求卡片
// console 错误全程收集，结尾汇报（视觉验证纪律：无新增错误）。
//
// 运行：TAG=before SHOTS_DIR=… node tests/design-language-shots.mjs

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8177);
const BASE = `http://127.0.0.1:${PORT}`;
const TAG = process.env.TAG || 'before';
const SHOTS_DIR = join(process.env.SHOTS_DIR || resolve(HERE, '../evidence/design-language-shots'), TAG);
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（真实 web/ 树）─────────────────────────────────────────────
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

// ── Mock 数据 ────────────────────────────────────────────────────────────
// 密钥列表（metadata only 契约）：一条 0600 正常 + 一条 permissive（警示徽标）+ 一条带 refs
const SECRETS = [
  { name: 'GITHUB_TOKEN', size: 40, mtime: Date.now() - 86400e3, mode: '0600', refs: ['nebflow-website'] },
  { name: 'OPENAI_API_KEY', size: 51, mtime: Date.now() - 3600e3, mode: '0644', refs: [] },
  { name: 'FEISHU_APP_SECRET', size: 32, mtime: Date.now() - 7 * 86400e3, mode: '0600', refs: ['social-feishu'] },
];
const CONFIG = {
  llm: { providers: { 'openai': { apiKeyRef: 'OPENAI_API_KEY', models: ['gpt-5.2'], default: true }, 'anthropic': { apiKeyRef: 'ANTHROPIC_KEY', models: ['claude-4.6'] } } },
  safety: { defaultMode: 'confirm-edits' },
  jev: { enabled: true, provider: 'typesafe-jev', endpoint: 'https://jev.typesafe.dev/predict', keyRef: 'typesafe-api-key' },
};

const consoleErrors = [];

async function boot({ viewport = { width: 1440, height: 900 }, colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  // 先注册 catch-all，再注册具体路由（Playwright 后注册者优先——nf-ticket-mock 同纪律）
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route('**/api/secrets', (r) => r.fulfill({ status: 200, json: { secrets: SECRETS } }));
  await ctx.route('**/api/secrets/*/value', (r) => r.fulfill({ status: 200, json: { name: 'x', value: 'sk-sample-value-0123456789abcdef' } }));
  // 账号区状态行 / 插件清单：mock 成功态，避免 404 红字噪声进对照图
  await ctx.route('**/api/neblink/status', (r) => r.fulfill({ status: 200, json: { loggedIn: false, username: '', devices: [] } }));
  await ctx.route('**/api/plugins', (r) => r.fulfill({ status: 200, json: { plugins: [] } }));
  await ctx.route('**/api/auth/status', (r) => r.fulfill({ status: 200, json: { authenticated: true } }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify(CONFIG) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: '设计语言批', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 'sess-1', messages: [], hasMore: false, offset: 0 }));
    // AskUser 密钥请求卡（secret 题 + 一条普通题并存，验证卡片内的 secret 行）
    ws.send(JSON.stringify({
      type: 'askUser', requestId: 'req-shot-1', sessionId: 'sess-1', agentName: 'Nebula',
      items: [
        { question: '请提供 Jina Reader 的 API Key（将写入密钥库，0600）', secret: true },
        { question: '同时把仓库地址告诉我', options: ['mystand.space', 'github.com/nebflow'] },
      ],
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
  return { ctx, page };
}

const browser = await chromium.launch();

for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await boot({ colorScheme: scheme });

  // ① 设置页（拍完即关——后续面板与卡片截图不得被弹层遮挡）
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-overlay.on #settings-modal', { timeout: 8000 });
  await sleep(700);
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-1-settings.png`) });
  await page.click('#settings-modal-close');
  await page.waitForSelector('#settings-overlay.on', { state: 'detached', timeout: 8000 }).catch(() => {});
  await sleep(400);

  // ② 密钥面板：静息列表
  await page.evaluate(() => { document.getElementById('btn-open-secrets')?.click(); });
  await page.waitForSelector('#secrets-overlay.on #secrets-modal', { timeout: 8000 });
  await sleep(900);
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-2-secrets.png`) });

  // ②b 密钥面板：第一行原位展开（眼 + meta + 保存/删除）＋ 新建尾行可见
  const rowHead = page.locator('.secrets-row-head').first();
  if (await rowHead.count()) {
    await rowHead.click();
    await sleep(600);
  }
  await page.screenshot({ path: join(SHOTS_DIR, `${scheme}-3-secrets-open.png`) });
  await page.click('#secrets-modal-close');
  await page.waitForSelector('#secrets-overlay.on', { state: 'detached', timeout: 8000 }).catch(() => {});
  await sleep(400);

  // ③ AskUser 密钥请求卡片（聊天流内，元素级截图；弹层已全部关闭）
  const card = page.locator('.option-box[data-request-id="req-shot-1"]').last();
  try {
    await card.waitFor({ state: 'visible', timeout: 8000 });
    await card.scrollIntoViewIfNeeded();
    await sleep(500);
    await card.screenshot({ path: join(SHOTS_DIR, `${scheme}-4-ask-card.png`) });

    // 功能冒烟（交互逻辑零变化的证据）：眼切换 type/aria-pressed、作答闸解锁确认钮
    const eyeOk = await page.evaluate(() => {
      const eye = document.querySelector('.option-box[data-request-id="req-shot-1"] .option-secret-eye');
      const pw = document.querySelector('.option-box[data-request-id="req-shot-1"] .option-secret-input');
      if (!eye || !pw) return 'missing-dom';
      const before = pw.type;
      eye.click();
      const afterShow = pw.type;
      const pressed = eye.getAttribute('aria-pressed');
      eye.click();
      const afterHide = pw.type;
      return (before === 'password' && afterShow === 'text' && pressed === 'true' && afterHide === 'password') ? 'ok' : `bad:${before}->${afterShow}->${afterHide}`;
    });
    console.log(`[smoke:${scheme}] eye-toggle = ${eyeOk}`);
    const gateOk = await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-request-id="req-shot-1"]');
      const pw = box.querySelector('.option-secret-input');
      const confirmBtn = [...box.querySelectorAll('button')].find((b) => b.className.includes('option-confirm') || (b.textContent || '').includes('确认'));
      if (!pw || !confirmBtn) return 'missing-dom';
      const disabledBefore = confirmBtn.disabled;
      pw.value = 'sk-smoke-value';
      pw.dispatchEvent(new Event('input', { bubbles: true }));
      // 单选题点一枚，满足全部作答
      const opt = box.querySelector('.option-btn:not(.opt-ghost)');
      if (opt) opt.click();
      const disabledAfter = confirmBtn.disabled;
      return (!disabledBefore || disabledAfter) ? 'note' : (disabledBefore === true && disabledAfter === false) ? 'ok' : `bad:${disabledBefore}->${disabledAfter}`;
    });
    console.log(`[smoke:${scheme}] answer-gate = ${gateOk}`);
  } catch (e) {
    consoleErrors.push(`[${TAG}] ask card not rendered: ${e.message}`);
  }
  await ctx.close();
}

await browser.close();
server.close();
const errs = consoleErrors.filter((t) => !t.includes('favicon'));
console.log(`done TAG=${TAG} → ${SHOTS_DIR}`);
console.log(errs.length ? `console errors (${errs.length}):\n` + errs.slice(0, 10).join('\n') : 'console: no errors');
