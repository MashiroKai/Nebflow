// 服务商受限通知「卡片化收编」真 Chromium 手机视口验收
// （provider-notice-card-unify 批 2026-11-23；作者 08:08 问「这些通知不都应该用 llm 卡吗」）。
// 驱动真实 js/llmFailureCard.js + js/providerFailureStatus.js + css（经本地静态服务挂载，不依赖后端）。
// 断言：① 窗口受限卡出卡（标题带 provider+窗口）；② 无等待/换模型按钮、有「去设置」；
//      ③ 点「去设置」触发注入的 openSettings；④ 失败组（providerGroups）走卡族面；
//      ⑤ 普通失败卡不变（等待/换模型仍在）。
// 增量（作者 09:48「卡片上应该要可以直接设置」）：⑥ 卡内显示当前窗口值 + 快捷动作；
//      ⑦ 快捷动作先出确认条（取消 = 零写入），确认后经注入的 saveWindows 写入，
//      成功后当前值刷新 + 状态提示（收卡仍由 llmFailureRecovered 完成，零定时器）。
// 截图落到 EVIDENCE_DIR（默认 <项目>/.stand/tmp/provider-notice-unify/evidence）。
// 运行：node tests/provider-notice-card-unify.spec.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, '..', 'src', 'main', 'resources', 'web');
const PRIMARY = process.env.PRIMARY_CHECKOUT || '/Users/kaiyu/Claude code/Stand';
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || path.join(PRIMARY, '.stand', 'tmp', 'provider-notice-unify', 'evidence');
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
import { renderProviderFailureStatus } from '/js/providerFailureStatus.js';
window.__F = F;
window.__renderStatus = renderProviderFailureStatus;
window.__settingsOpened = 0;
// 卡内直接设置（作者 09:48 增量令）：live 窗口值可改 + 写入留痕（模拟 saveProviderWithAck 链路出口）
window.__currentWindows = [{ from: '06:00', to: '01:00' }];
window.__saved = [];
window.__saveOk = true;
window.__deps = {
  saveModel: async () => {},
  modelRefs: () => ['UTSC/glm-5.3-flash', 'Irical/DeepSeek-V4.1-Flash-1017'],
  onDismiss: () => {},
  openSettings: () => { window.__settingsOpened += 1; },
  getWindows: () => window.__currentWindows,
  saveWindows: async (provider, windows) => {
    window.__saved.push({ provider, windows });
    if (!window.__saveOk) return false;
    window.__currentWindows = windows;
    return true;
  },
};
window.mountFailure = (msg) => {
  const c = F.renderLlmFailureCard(msg, window.__deps);
  document.getElementById('chat').appendChild(c);
  return F.llmFailureKey(msg.layer, msg.modelRef);
};
window.__ready = true;
</script></body></html>`;

const WINDOW_MSG = {
  layer: 'root',
  modelRef: 'UTSC/glm-5.3-flash',
  agents: ['delegate-1'],
  summary: '当前时段（06:00-01:00）全部候选模型受限：UTSC/glm-5.3-flash — 所有候选都在其不可用时段内，已按规则跳过（不静默降级）',
  failureClass: 'llm-schedule-window',
  windowDesc: '06:00-01:00',
};

const NORMAL_MSG = {
  layer: 'root', modelRef: 'Irical/DeepSeek-V4.1-Flash-1017', agents: ['main'],
  summary: 'HTTP 503 overloaded', failureClass: 'llm-overloaded',
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

  console.log('[1] 窗口受限卡出卡（标题带 provider+窗口）');
  const key = await page.evaluate((m) => window.mountFailure(m), WINDOW_MSG);
  const card = page.locator(`.llm-failure-card[data-card-key="${key}"]`);
  ok(await card.isVisible(), '窗口受限卡出现');
  const title = await card.locator('.option-q').textContent();
  ok(title.includes('UTSC/glm-5.3-flash') && title.includes('06:00-01:00'), '标题带 provider 与窗口时段');
  ok((await card.locator('[data-lucide="clock"]').count()) === 1, '标题图标为 clock（窗口语义）');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state1-window-card.png') });

  console.log('[2] 无等待/换模型按钮，有「去设置」');
  ok((await card.locator('.option-cancel', { hasText: '等待' }).count()) === 0, '无等待按钮');
  ok((await card.locator('.llm-failure-pick').count()) === 0, '无换模型下拉');
  const bSettings = card.locator('button', { hasText: '去设置' });
  ok(await bSettings.isVisible(), '「去设置」按钮在场');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state2-window-actions.png') });

  console.log('[2b] 卡内直接设置：当前窗口值 + 快捷动作 + 确认一步');
  const winBox = card.locator('.llm-failure-window');
  ok(await winBox.isVisible(), '卡内设置区在场');
  ok((await winBox.locator('.llm-failure-window-value').textContent()).includes('当前：06:00-01:00（每天）'), '显示当前 unavailableWindows（provider 取自 modelRef）');
  const bClear = winBox.locator('[data-preset="clear"]');
  const bNight = winBox.locator('[data-preset="night"]');
  ok(await bClear.isVisible(), '快捷动作「清除窗口」在场');
  ok((await bNight.textContent()).includes('仅深夜 01:00-06:00'), '快捷动作「仅深夜 01:00-06:00」在场');
  const confirmBar = winBox.locator('.llm-failure-window-confirm');
  ok(!(await confirmBar.isVisible()), '确认条初始隐藏');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state5-window-inline-controls.png') });

  await bClear.tap();
  ok(await confirmBar.isVisible(), '点「清除窗口」先出确认条（防误触）');
  ok((await confirmBar.textContent()).includes('把 UTSC 的不可用窗口改为「无（随时可用）」，确认？'), '确认条写明目标 provider 与目标值');
  ok((await page.evaluate(() => window.__saved.length)) === 0, '未确认前零写入');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state6-window-inline-confirm.png') });
  await confirmBar.locator('.option-cancel').tap();
  ok(!(await confirmBar.isVisible()), '取消后确认条收起');
  ok((await page.evaluate(() => window.__saved.length)) === 0, '取消 = 零写入');

  await bClear.tap();
  await confirmBar.locator('.llm-failure-window-yes').tap();
  await page.waitForFunction(() => window.__saved.length === 1);
  const saved1 = await page.evaluate(() => window.__saved[0]);
  ok(saved1.provider === 'UTSC' && Array.isArray(saved1.windows) && saved1.windows.length === 0, '确认后写入 = provider UTSC / 空段列表');
  await page.waitForFunction(() => document.querySelector('.llm-failure-window-value')?.textContent.includes('未配置窗口'));
  ok(true, '写入成功后卡内当前值刷新（未配置窗口）');
  ok((await card.locator('.llm-failure-status').textContent()).includes('已更新窗口配置'), '状态行提示已更新（收卡仍由 recovered 帧完成）');
  ok((await card.count()) === 1, '写入不手工收卡（等 llmFailureRecovered 自动清卡）');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state7-window-inline-saved.png') });

  await bNight.tap();
  ok((await confirmBar.textContent()).includes('01:00-06:00'), '深夜预设确认条带目标时段');
  await confirmBar.locator('.llm-failure-window-yes').tap();
  await page.waitForFunction(() => window.__saved.length === 2);
  const saved2 = await page.evaluate(() => window.__saved[1]);
  ok(saved2.provider === 'UTSC' && saved2.windows.length === 1 && saved2.windows[0].from === '01:00' && saved2.windows[0].to === '06:00', '深夜预设写入 01:00-06:00');
  await page.waitForFunction(() => document.querySelector('.llm-failure-window-value')?.textContent.includes('01:00-06:00'));

  // 失败腿：写入 ack=false ⇒ 状态报错、本地值不变。
  await page.evaluate(() => { window.__saveOk = false; });
  await bClear.tap();
  await confirmBar.locator('.llm-failure-window-yes').tap();
  await page.waitForFunction(() => window.__saved.length === 3);
  ok((await card.locator('.llm-failure-status').textContent()).includes('保存失败'), 'ack 失败时状态行报错');
  ok((await winBox.locator('.llm-failure-window-value').textContent()).includes('01:00-06:00'), '失败不篡改显示值');
  await page.evaluate(() => { window.__saveOk = true; });

  console.log('[3] 点「去设置」触发 openSettings');
  await bSettings.tap();
  const opened = await page.evaluate(() => window.__settingsOpened);
  ok(opened === 1, 'openSettings 被调用一次');

  console.log('[4] 失败组（providerGroups）走卡族面');
  const groupText = await page.evaluate(() => {
    const root = document.createElement('section');
    document.body.appendChild(root);
    const view = { dom: { chat: root }, sessionId: 'parent' };
    const digest = {
      items: [{ sessionId: 'a', parentSessionId: 'parent', title: '迁移任务' }],
      providerGroups: [{ provider: 'UTSC', family: 'unknown', presentation: 'availability', reason: 'all candidates in their unavailable windows', sessionIds: ['a'] }],
    };
    window.__renderStatus(view, digest, () => {});
    return {
      cardCount: root.querySelectorAll('.llm-failure-card').length,
      detailsCount: root.querySelectorAll('details').length,
      text: root.textContent,
    };
  });
  ok(groupText.cardCount === 1, '失败组渲染为 llm-failure-card 卡面');
  ok(groupText.detailsCount === 0, '不再产出 details/summary 裸横幅');
  ok(groupText.text.includes('UTSC'), '卡面带真实 provider（非 unknown）');
  ok(groupText.text.includes('迁移任务'), '任务跳转按钮保留');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state3-group-card.png') });

  console.log('[5] 普通失败卡不变（等待/换模型仍在）');
  const keyN = await page.evaluate((m) => window.mountFailure(m), NORMAL_MSG);
  const cardN = page.locator(`.llm-failure-card[data-card-key="${keyN}"]`);
  ok(await cardN.isVisible(), '普通失败卡出现');
  ok((await cardN.locator('.option-cancel').count()) === 1, '等待按钮仍在');
  ok((await cardN.locator('.llm-failure-pick').count()) === 1, '换模型下拉仍在');
  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'state4-normal-card.png') });
} catch (e) {
  fails.push(String(e && e.stack || e));
  console.log('  ✘ 异常：' + (e && e.message));
} finally {
  await browser.close();
  server.close();
}
if (fails.length) { console.log(`FAIL ${fails.length}`); process.exit(1); }
console.log('PASS provider-notice-card-unify');
