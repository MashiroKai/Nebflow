// settings-fourfix-live.mjs — settings-panel-fourfix 批 LIVE 真浏览器验证
// （隔离引擎 :8097，seal=ON 构建；在产实例零接触——独立 home /tmp/nb-fourfix-home）。
//
// 会话引导：POST /api/auth/console-ticket（Bearer file token，BearerFileOnly 轨）
// → GET /?ticket=（303 + HttpOnly 会话 cookie）→ WS/REST 全轨可用。
//
// 覆盖（fourfix 批①②③④验收）：
//   ① 模型行零思考控件（收归全局后卡面证据截图）
//   ② provider 编辑弹窗内连接性测试 LIVE：zhipu / kimi×3 / cmdcode（真实 API，
//      结果块截图——绿✓+耗时 / cmdcode 全量 401 文本不截断）
//   ③ 生图面板：新用户全空面截图 → 真实 UI 配置流（填 key、保存）→ 已配置面
//   ④ 生图端到端 LIVE：POST /api/imagegen（cogview 走后端）→ 图落盘 + nf-file
//      票据链可服务（前端取图链路同源）
//
// Run: node tests/settings-fourfix-live.mjs   （引擎须已在 :8097 带 STAND_IMAGEGEN=1 起）
import { chromium } from '@playwright/test';
import fs from 'node:fs';

const BASE = 'http://127.0.0.1:8097';
const HOME = '/tmp/nb-fourfix-home';
const SHOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-73f5fdd8/evidence/20261029_121500_settings-panel-fourfix__delegate-nebflow-73f5fdd8/live-ui';
fs.mkdirSync(SHOT, { recursive: true });

const TOKEN = JSON.parse(fs.readFileSync(HOME + '/auth.json', 'utf8'));
const authHeaders = () => ({ Authorization: 'Bearer ' + TOKEN });

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`);
  if (!ok) process.exitCode = 1;
};

// ── 浏览器会话 ──────────────────────────────────────────────────────────────
const mint2 = await fetch(BASE + '/api/auth/console-ticket', { method: 'POST', headers: authHeaders() });
const ticket = (await mint2.json()).ticket;
if (!ticket) { console.error('FAIL ticket mint: HTTP ' + mint2.status); process.exit(1); }

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } });
await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`);
await page.waitForSelector('#settings-btn', { timeout: 20000 });
await page.click('#settings-btn');
await page.waitForFunction(() => document.querySelectorAll('#provider-list .cfg-card').length > 0, null, { timeout: 30000 });
await page.waitForSelector('#imagegen-section-wrap .imagegen-face', { timeout: 15000 });
await page.waitForTimeout(1200);

// ① 模型行面（收归全局后）：零思考下拉/徽标/测试钮。
const rowProbe = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('#provider-list .cfg-model-row'));
  return {
    rows: rows.length,
    selects: rows.filter((r) => r.querySelector('select')).length,
    thinking: rows.filter((r) => r.querySelector('.cfg-model-thinking, .cfg-thinking-forced-badge')).length,
    tests: rows.filter((r) => r.querySelector('.cfg-model-test')).length,
  };
});
check('① 模型行零思考控件/零测试钮（归位后卡面）', rowProbe.rows > 0 && rowProbe.selects === 0 && rowProbe.thinking === 0 && rowProbe.tests === 0, JSON.stringify(rowProbe));
const globalSel = await page.locator('#cfg-thinking-effort').count();
check('① 全局思考下拉唯一配置点在场', globalSel === 1);
await page.locator('#provider-list').screenshot({ path: `${SHOT}/F1-provider-cards-after.png` });

// ② provider 编辑弹窗内连接性测试 LIVE（逐 provider、逐模型串行——引擎
// 单飞闸同一时刻只放行一个诊断调用，并发第二击会 409）。
async function testProvider(name, expectModels) {
  await page.click(`.cfg-card[data-provider="${name}"]`);
  await page.waitForSelector('#cfg-modal', { timeout: 8000 });
  const btns = page.locator('#cfg-modal .cfg-model-test');
  const count = await btns.count();
  check(`② ${name} 弹窗测试钮数`, count === expectModels, `count=${count} expect=${expectModels}`);
  for (let i = 0; i < count; i++) {
    await btns.nth(i).click();
    // 等本行落定（spinner 消失）再点下一枚——单飞闸串行面。
    await page.waitForFunction(() =>
      document.querySelectorAll('#cfg-modal .cfg-test-result-line.testing').length === 0,
    null, { timeout: 60000, polling: 300 });
  }
  const verdicts = await page.evaluate(() =>
    Array.from(document.querySelectorAll('#cfg-modal .cfg-test-result-line')).map((l) => ({
      model: l.dataset.model, cls: l.className,
      text: (l.querySelector('.cfg-test-line-text')?.textContent || '').trim(),
    })));
  console.log('  [verdicts]', name, JSON.stringify(verdicts));
  await page.locator('#cfg-modal .cfg-modal').screenshot({ path: `${SHOT}/F2-provider-test-${name}.png` });
  await page.click('#cfg-modal-cancel');
  await page.waitForSelector('#cfg-modal', { state: 'detached', timeout: 5000 }).catch(() => {});
  return verdicts;
}

const zhipuV = await testProvider('zhipu', 1);
check('② zhipu LIVE 绿', zhipuV[0]?.cls.includes('ok'), `${zhipuV[0]?.text}`);
const kimiV = await testProvider('kimi', 3);
check('② kimi×3 LIVE', kimiV.filter((v) => v.cls.includes('ok')).length, JSON.stringify(kimiV.map((v) => `${v.model}:${v.cls.includes('ok') ? v.text : 'FAIL'}`)));
const cmdV = await testProvider('cmdcode', 1);
check('② cmdcode 401 全量文本可见（不截断）', cmdV[0]?.cls.includes('err') && cmdV[0]?.text.includes("Invalid 'Authorization' header or token."), cmdV[0]?.text);

// ③ 生图面板：新用户全空面（隔离 home 初始无 imagegen 节）。
const wrap = page.locator('#imagegen-section-wrap');
await wrap.screenshot({ path: `${SHOT}/F3-imagegen-new-user-blank.png` });
const blankProbe = await page.evaluate(() => {
  const sel = document.getElementById('cfg-imagegen-provider');
  return {
    addSelected: sel?.value === '__add__',
    nameVisible: !document.getElementById('cfg-imagegen-newprovider')?.hidden,
    nameVal: document.getElementById('cfg-imagegen-newprovider')?.value ?? null,
    typeVal: document.getElementById('cfg-imagegen-type')?.value ?? null,
    modelVal: document.getElementById('cfg-imagegen-model')?.value ?? null,
    keyVal: document.getElementById('cfg-imagegen-key')?.value ?? null,
    noKeyRefWording: !document.getElementById('imagegen-section-wrap').innerText.includes('keyRef'),
    singleSave: document.querySelectorAll('#btn-save-imagegen').length === 1 && !document.getElementById('btn-save-imagegen-key'),
  };
});
check('③ 新用户全空面 + 零 keyRef 字眼 + 单一保存钮', blankProbe.addSelected && blankProbe.nameVisible && blankProbe.typeVal === '' && blankProbe.modelVal === '' && blankProbe.keyVal === '' && blankProbe.noKeyRefWording && blankProbe.singleSave, JSON.stringify(blankProbe));

// ③b 真实 UI 配置流：填 key、保存（值打掩码输入框，截图不可见）。
await page.fill('#cfg-imagegen-newprovider', 'zhipu');
await page.selectOption('#cfg-imagegen-type', 'cogview');
await page.fill('#cfg-imagegen-model', 'cogview-3-flash');
const zhipuKey = JSON.parse(fs.readFileSync(HOME + '/nebflow.json', 'utf8')).llm.providers.zhipu.apiKey;
await page.fill('#cfg-imagegen-key', zhipuKey);
await page.click('#toggle-imagegen'); // 启用生图
await page.click('#btn-save-imagegen');
await page.waitForFunction(() => document.querySelector('#imagegen-section-wrap .st-badge[data-tone="ok"]'), null, { timeout: 15000 });
const cfgProbe = await page.evaluate(() => {
  const sel = document.getElementById('cfg-imagegen-provider');
  return { provider: sel?.value, configured: !!document.querySelector('#imagegen-section-wrap .st-badge[data-tone="ok"]'), keyCleared: document.getElementById('cfg-imagegen-key')?.value === '' };
});
check('③ UI 配置保存 → 已配置态 + 输入清空', cfgProbe.provider === 'zhipu' && cfgProbe.configured && cfgProbe.keyCleared, JSON.stringify(cfgProbe));
await wrap.screenshot({ path: `${SHOT}/F3-imagegen-configured.png` });
// 引擎盘上双读：secret 在位 + 配置节写入（含派生/沿用 keyRef——前端不可见）。
const secretStat = fs.statSync(HOME + '/secrets/zhipu-api-key');
const cfgOnDisk = JSON.parse(fs.readFileSync(HOME + '/nebflow.json', 'utf8'));
check('③ 盘上 secret + 配置节', secretStat.size > 0 && cfgOnDisk.imagegen?.providers?.zhipu?.model === 'cogview-3-flash', `secretBytes=${secretStat.size} mode=0${(secretStat.mode & 0o777).toString(8)} imagegen=${JSON.stringify(cfgOnDisk.imagegen)}`);

// ── ④ 生图端到端 LIVE（cogview 走后端——UI 配置完成后触发）─────────────────
const t0 = Date.now();
const genResp = await fetch(BASE + '/api/imagegen', {
  method: 'POST',
  headers: { ...authHeaders(), 'Content-Type': 'application/json' },
  body: JSON.stringify({ prompt: 'a small glass house on a foggy hillside at dawn, soft watercolor', size: '1024x1024' }),
});
const genMs = Date.now() - t0;
const gen = await genResp.json().catch(() => null);
check('④ imagegen LIVE HTTP 200', genResp.ok, `http=${genResp.status} body=${JSON.stringify(gen).slice(0, 160)}`);
if (gen?.path) {
  const bytes = fs.readFileSync(gen.path);
  const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8;
  const png = bytes[0] === 0x89 && bytes[1] === 0x50;
  check('④ 图落盘且为真实图片字节', bytes.length > 10000 && (jpeg || png), `path=${gen.path} bytes=${bytes.length} magic=${jpeg ? 'jpeg' : png ? 'png' : 'unknown'} dur=${genMs}ms`);
  // nf-file 票据链（前端 <img> 取图同一条路）：POST /api/nf-ticket {paths:[…]}
  // → GET /api/nf-file?path=…&ticket=…
  const mint = await fetch(BASE + '/api/nf-ticket', { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ paths: [gen.path] }) });
  const mintBody = await mint.json().catch(() => null);
  const ticket = mintBody?.tickets?.[gen.path]?.t;
  if (ticket) {
    const imgResp = await fetch(`${BASE}/api/nf-file?path=${encodeURIComponent(gen.path)}&ticket=${encodeURIComponent(ticket)}`);
    check('④ nf-file 票据链可服务', imgResp.ok, `http=${imgResp.status} type=${imgResp.headers.get('content-type')} len=${imgResp.headers.get('content-length')}`);
  } else {
    check('④ nf-ticket 铸票', false, JSON.stringify(mintBody).slice(0, 120));
  }
}

// 全景
await page.locator('#settings-content').first().screenshot({ path: `${SHOT}/F0-panel-full.png` }).catch(async () => {
  await page.screenshot({ path: `${SHOT}/F0-panel-full.png` });
});
await browser.close();
console.log('DONE exit=' + (process.exitCode || 0));
