// LLM 失败卡「点按消失」真 Chromium 手机视口验收（2026-11-22 作者 23:24 bug）。
// 驱动真实 js/llmFailureCard.js + css（经本地静态服务挂载，不依赖后端）。
// 断言：① 出卡；② 控件区（等待）点击不消失；③ 正文点按消失；④ 关闭钮消失；
//      ⑤ 换模型成功 → 脚注「已切换」→ 自动淡出清除；⑥ 换模型失败 → 卡保留（不假消失）。
// 三态截图落到 EVIDENCE_DIR（默认 <项目>/.stand/tmp/llm-fail-card-dismiss/evidence）。
// 运行：node tests/llm-failure-card-dismiss.spec.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, '..', 'src', 'main', 'resources', 'web');
const PRIMARY = process.env.PRIMARY_CHECKOUT || '/Users/kaiyu/Claude code/Stand';
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || path.join(PRIMARY, '.stand', 'tmp', 'llm-fail-card-dismiss', 'evidence');
const require = createRequire(path.join(PRIMARY, 'package.json'));
const { chromium } = require('playwright');

const CSS = ['base.css', 'stand-tokens.css', 'design-system.css', 'chat.css', 'llmFailureCard.css'];
const PAGE = `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
${CSS.map((c) => `<link rel="stylesheet" href="/css/${c}">`).join('\n')}
</head><body style="background:#f4f5f7;margin:0;padding:12px">
<div id="chat" style="width:358px;display:flex;flex-direction:column;gap:12px"></div>
<script type="module">
import * as F from '/js/llmFailureCard.js';
const { renderLlmFailureCard, llmFailureKey } = F;
// 负对照（修复前树）无 dismiss store 导出：退化为空实现，使用例失败落在行为面而非模块加载面。
window.__store = F.createLlmDismissStore ? F.createLlmDismissStore() : { has: () => false, add() {}, clear() {}, size: () => 0 };
window.__saveOk = true;
window.__deps = {
  saveModel: async () => { if (!window.__saveOk) throw new Error('boom'); },
  modelRefs: () => ['Irical/DeepSeek-V4.1-Flash-1017', 'IricalClaude/claude-haiku-5-5'],
  onDismiss: (k) => window.__store.add(k),
};
window.mount = (msg) => {
  const c = renderLlmFailureCard(msg, window.__deps);
  document.getElementById('chat').appendChild(c);
  return llmFailureKey(msg.layer, msg.modelRef);
};
window.__ready = true;
</script></body></html>`;

const MSG = {
  layer: 'root',
  modelRef: 'IricalClaude/claude-haiku-5-5',
  agents: ['main'],
  summary: 'L1/子任务/EmptyStream：All providers failed',
  failureClass: 'llm-transient',
};

const MIME = { '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end(PAGE); }
  const file = path.join(WEB, path.normalize(url.pathname).replace(/^([/\\])+/, ''));
  if (!file.startsWith(WEB) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

const fails = [];
const ok = (cond, msg) => { if (cond) console.log('  ✔ ' + msg); else { console.log('  ✘ ' + msg); fails.push(msg); } };

await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await page.goto(base + '/');
  await page.waitForFunction(() => window.__ready === true);
  const card = (key) => page.locator(`.llm-failure-card[data-card-key="${key}"]`);

  console.log('[1] 出卡');
  const key = await page.evaluate((m) => window.mount(m), MSG);
  ok(await card(key).isVisible(), '失败卡出现');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state1-card-shown.png') });

  console.log('[2] 控件区点击不消失');
  await card(key).locator('.option-cancel').tap();
  ok(await card(key).isVisible(), '点「等待」后卡仍在（控件区不触发消失）');

  console.log('[3] 正文点按消失');
  await card(key).locator('.llm-failure-summary').tap();
  await page.waitForTimeout(400);
  ok((await card(key).count()) === 0, '正文点按后卡移除');
  ok(await page.evaluate((k) => window.__store.has(k), key), '消失组记入 dismiss store（同组不复出）');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state2-dismissed.png') });

  console.log('[4] 关闭钮消失');
  const key2 = await page.evaluate((m) => window.mount({ ...m, layer: 'team' }), MSG);
  await card(key2).locator('.llm-failure-close').tap();
  await page.waitForTimeout(100);
  ok((await card(key2).count()) === 0, '关闭钮点按后卡移除');

  console.log('[5] 换模型成功 → 脚注 → 自动淡出');
  const key3 = await page.evaluate((m) => window.mount({ ...m, layer: 'subtask' }), MSG);
  await card(key3).locator('.llm-failure-pick').selectOption('Irical/DeepSeek-V4.1-Flash-1017');
  await card(key3).locator('.option-confirm').tap();
  await page.waitForFunction((k) => document.querySelector(`.llm-failure-card[data-card-key="${k}"] .llm-failure-status`)?.textContent.includes('已切换'), key3);
  ok(await card(key3).isVisible(), '换模型成功后脚注出现、卡仍在（等待淡出）');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state3a-switched-footnote.png') });
  await page.waitForTimeout(4000 + 300 + 400);
  ok((await card(key3).count()) === 0, '换模型成功后自动淡出清除');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state3b-switched-cleared.png') });

  console.log('[6] 换模型失败 → 卡保留');
  const key4 = await page.evaluate((m) => window.mount({ ...m, layer: 'team' }), MSG);
  await page.evaluate(() => { window.__saveOk = false; });
  await card(key4).locator('.option-confirm').tap();
  await page.waitForFunction((k) => document.querySelector(`.llm-failure-card[data-card-key="${k}"] .llm-failure-status`)?.textContent.includes('切换失败'), key4);
  await page.waitForTimeout(4800);
  ok(await card(key4).isVisible(), '切换失败时卡保留，不假消失');
} catch (e) {
  fails.push(String(e && e.stack || e));
  console.log('  ✘ 异常：' + (e && e.message));
} finally {
  await browser.close();
  server.close();
}
if (fails.length) { console.log(`FAIL ${fails.length}`); process.exit(1); }
console.log('PASS llm-failure-card-dismiss');
