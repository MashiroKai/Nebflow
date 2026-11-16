// settings-e2e-isolated.mjs — settings-panel-jev-imagegen 批真浏览器验证
// （隔离引擎 :8687，seal=ON 构建；在产引擎 pid 70481/网关 6138 零接触）。
//
// 会话引导：POST /api/auth/console-ticket（Bearer file token，BearerFileOnly 轨）
// → GET /?ticket=（303 + HttpOnly stand_session cookie）→ WS/REST 全轨可用。
//
// 覆盖：imagegen 缺 key→录入→已配置（②）、调试徽标（③）、
// cmdcode 测试钮真打 API（④⑤端到端）、STT toggle 关→stt-disabled（⑥D 件）。
// （① JeV key 明文腿已随 JeV 全量退役删除——jev-retire 批 2026-11-16。）
// 截图落 .nebflow/evidence/20261028_settings-panel-jev-imagegen/isolated-ui/。
import { chromium } from '@playwright/test';
import fs from 'node:fs';

const BASE = 'http://localhost:8687';
const SHOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-b9c42478/.nebflow/evidence/20261028_settings-panel-jev-imagegen/isolated-ui';
fs.mkdirSync(SHOT, { recursive: true });

const TOKEN = JSON.parse(fs.readFileSync('/tmp/nb-settings-batch/home/auth.json', 'utf8'));
const authHeaders = () => ({ Authorization: 'Bearer ' + TOKEN });

const mint = await fetch(BASE + '/api/auth/console-ticket', { method: 'POST', headers: authHeaders() });
const ticket = (await mint.json()).ticket;
if (!ticket) { console.error('FAIL ticket mint: HTTP ' + mint.status); process.exit(1); }

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } });
await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`); // 303 → / + session cookie
await page.waitForSelector('#settings-btn', { timeout: 20000 });
await page.click('#settings-btn');
// provider 卡来自 state.parsedConfig（WS serverConfig 送达后 renderSettings
// 才有内容；面板先开，再等数据到位——renderSettings 会在 echo 后重绘）
await page.waitForFunction(() => document.querySelectorAll('#provider-list .cfg-card').length > 0, null, { timeout: 20000 });
// provider-add-tabs 批（2026-11-06 作者令「添加服务商统一成标签切换类型 +
// 单一服务商面板」）：生图 = 统一服务商面板的类型标签——点 Image generation
// 标签后列表/新增/表单都在面板内（不再有层级下钻浮层；JeV 标签已随 JeV 退役
// 删除——jev-retire 批 2026-11-16）。
await page.waitForSelector('#provider-panel-host .nf-provider-panel', { timeout: 15000 });
await page.waitForTimeout(600); // 投影（provider-types/imagegen）落地重绘
await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="imagegen"]')?.click());
await page.waitForSelector('.nf-provider-panel[data-type="imagegen"]', { timeout: 15000 });
await page.waitForTimeout(800);

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`);
  if (!ok) process.exitCode = 1;
};

// ③ 调试徽标（功能开放批 2026-10-30：正式开放，徽标退役——必须零渲染）
const badge = await page.locator('.imagegen-badge').count();
check('③ 调试徽标退役（零渲染）', badge === 0, `count=${badge}`);

// ② 缺 key 形态（warn 徽标 待录入）→ 录入 → 已配置（ok）
const badgeDump = await page.evaluate(() =>
  Array.from(document.querySelectorAll('#provider-panel-host .st-badge')).map((b) => ({
    tone: b.dataset.tone, text: (b.textContent || '').trim(),
  })));
console.log('  [diag badges]', JSON.stringify(badgeDump));
const warnText = (await page.locator('#provider-panel-host .cfg-card .st-badge[data-tone="warn"]').first().textContent().catch(() => '')) || '';
check('② 缺 key=未配置(warn 徽标在场)', warnText.length > 0, `text=${warnText}`);
const wrap = page.locator('#provider-panel-host');
await wrap.screenshot({ path: `${SHOT}/S2-imagegen-missing-key.png` });

// 打开 zhipu 卡的统一表单录入 key（provider-add-tabs 批：保存腿 = 表单骨架）
await page.evaluate(() => {
  const card = [...document.querySelectorAll('#provider-list .cfg-card')]
    .find((c) => c.querySelector('.cfg-card-title')?.textContent.trim() === 'zhipu');
  card?.querySelector('.cfg-card-title')?.click();
});
await page.waitForSelector('#cfg-modal [data-field="apiKey"]', { timeout: 15000 });
await page.fill('#cfg-modal [data-field="apiKey"]', 'sk-e2e-imagegen-0000-verify');
await page.click('#cfg-modal-save');
let proj = null;
for (let i = 0; i < 30; i++) {
  proj = await page.evaluate(async (t) => {
    const r = await fetch('/api/config/imagegen', { headers: { Authorization: 'Bearer ' + t } });
    return r.json();
  }, TOKEN);
  if (proj?.providers?.zhipu?.keyConfigured === true) break;
  await page.waitForTimeout(500);
}
check('② 录入后 keyConfigured=true', proj?.providers?.zhipu?.keyConfigured === true, JSON.stringify(proj?.providers?.zhipu?.keyConfigured));
const okText = (await page.locator('#provider-panel-host .cfg-card .st-badge[data-tone="ok"]').first().textContent().catch(() => '')) || '';
check('② 已配置(ok 徽标)', okText.length > 0, `text=${okText}`);
await wrap.screenshot({ path: `${SHOT}/S3-imagegen-key-configured.png` });

// ④⑤ cmdcode 测试钮真打（真实 API，~39 tokens；有界 40s）。结果位=被点按钮
// 的 nextElementSibling（DOM 序 first() 会读错行）。
const deepseekBtn = '.cfg-model-test[data-test-provider="cmdcode"][data-test-model*="deepseek"]';
await page.locator(deepseekBtn).first().click();
const settled = await page.waitForFunction((sel) => {
  const btn = document.querySelector(sel);
  const slot = btn && btn.nextElementSibling;
  const ok = slot && slot.querySelector('.st-ok');
  const err = slot && slot.querySelector('.st-err');
  return ok ? 'ok' : err ? 'err' : null;
}, deepseekBtn, { timeout: 40000, polling: 500 }).then((h) => h.jsonValue()).catch(() => 'timeout');
const slotInfo = await page.evaluate((sel) => {
  const slot = document.querySelector(sel)?.nextElementSibling;
  return { cls: slot?.querySelector('svg')?.getAttribute('class') || '', txt: (slot?.textContent || '').trim(), title: slot?.title || '' };
}, deepseekBtn);
check('④ cmdcode 测试钮绿 ✓', settled === 'ok', `${settled} ${slotInfo.txt} ${slotInfo.title.slice(0, 80)}`);
await page.locator(`.cfg-card:has(${deepseekBtn})`).first()
  .screenshot({ path: `${SHOT}/S4-cmdcode-model-test-ok.png` })
  .catch(async () => { await page.screenshot({ path: `${SHOT}/S4-cmdcode-model-test-ok.png` }); });

// ⑥D STT toggle 关 → 保存 → body.stt-disabled + mic orb 隐藏
try {
  await page.click('#stt-advance-toggle', { timeout: 8000 });
} catch (e) {
  const cover = await page.evaluate(() => {
    const el = document.getElementById('stt-advance-toggle');
    if (!el) return 'no-element';
    const r = el.getBoundingClientRect();
    const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return top ? `${top.tagName}#${top.id}.${top.className} @${Math.round(r.x)},${Math.round(r.y)}` : 'no-hit';
  });
  console.log('  [diag stt-toggle cover]', cover);
  const modals = await page.evaluate(() => Array.from(document.querySelectorAll('.cfg-modal-overlay, .modal-overlay, .overlay')).map((m) => `${m.id}.${m.className}:display=${getComputedStyle(m).display}`));
  console.log('  [diag overlays]', JSON.stringify(modals));
  throw e;
}
await page.click('#toggle-stt-enabled');
await page.click('#btn-save-stt');
await page.waitForSelector('body.stt-disabled', { timeout: 15000 });
const orbHidden = await page.evaluate(() => {
  const el = document.getElementById('mic-orb-wrap');
  return el ? getComputedStyle(el).display === 'none' : true;
});
check('⑥ stt-disabled: mic orb 隐藏', orbHidden);
const sttFile = JSON.parse(fs.readFileSync('/tmp/nb-settings-batch/home/stt-config.json', 'utf8'));
check('⑥ 盘上 {enabled:false} 保留', sttFile.enabled === false, JSON.stringify(Object.keys(sttFile)));
await page.screenshot({ path: `${SHOT}/S5-stt-disabled.png` });

// 全景
await page.locator('#settings-content').first().screenshot({ path: `${SHOT}/S0-panel-full.png` }).catch(async () => {
  await page.screenshot({ path: `${SHOT}/S0-panel-full.png` });
});
await browser.close();
console.log('DONE exit=' + (process.exitCode || 0));
