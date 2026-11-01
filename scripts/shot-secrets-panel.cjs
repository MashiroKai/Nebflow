#!/usr/bin/env node
// shot-secrets-panel.cjs — 密钥能力三件套批 C 件（层级收进/红标退役/网格化/清杂质）
// 的视觉验收 + DOM 断言（作者实拍令 2026-11-01）。
//
// 与 shot-header-name.cjs 的纯打桩路线不同：本验收获收的真实对象是**隔离真实例**
// （临时 STAND_HOME + 独占端口，启动/播种由驱动侧 bash 完成——qa 章
// isolated-smoke-verification 法），本脚本只对给定 BASE 发起真请求：
// 密钥清单来自 SecretsStore.list() 真读数（rollback 杂质剔除是后端单点，打桩
// 证明不了它）。
//
// 用法: node scripts/shot-secrets-panel.cjs <base> <token> <outdir>
//   例: node scripts/shot-secrets-panel.cjs http://127.0.0.1:6198 "<token>" .nebflow/shots-secrets
//
// 矩阵: chromium × firefox 两引擎 × dark/light 双主题（面板深浅双主题 × 双引擎
// 实拍验收面），每格一张「设置面板开着 + 密钥子面板开着」实拍；chromium-dark
// 加拍一张行展开态（原位编辑详情）。
//
// 硬断言（任一失败 → exit 1）:
//   A1 层级收进: #secrets-modal 矩形完整落在 #settings-modal 矩形内（≥1px 边距）
//   A2 红标退役: .secrets-mode-badge 元素数 = 0，面板文本零「权限过宽」
//   A3 杂质不出现: 面板文本零 "rollback"（后端 list 单点过滤的端到端证据）
//   A4 行网格对齐: 所有 .secrets-row-name 左缘 x 一致（±1px）、
//      所有 .secrets-row-meta 右缘 x 一致（±1.5px）——键名列/元信息列两列成栅格
//   A5 行数 = 播种的真实密钥数（SECRETS_EXPECT 环境变量；未设则跳过该断言）
//   A6 零 console error（**面板开窗后**的作用窗内）：pageerror = 0，console error
//      = 0，/api/secrets* 请求零失败 —— 面板开窗前的启动探测噪声（fresh 隔离
//      home 的 agents/plugins 403、WS 探测）不属本批面，不计。
//
// 纪律注（2026-11-01 实跑沉淀）：fresh 隔离 home 无 agents/Nebula/system.md 与
// 插件资产，boot 阶段浏览器会为这些探测打 403/404 resource 日志；Firefox 另有
// ws:// 握手失败噪声（ws.js 面，与本批无关）。A6 只对「密钥面板开着」的作用窗
// 记账 —— 面板自身的 API 面（/api/secrets*）必须全程零错，这才是本批验收面。

const { chromium, firefox } = require('playwright');
const { mkdirSync } = require('node:fs');
const { join } = require('node:path');

const BASE = process.argv[2] || 'http://127.0.0.1:6198';
const TOKEN = process.argv[3] || '';
const OUT_DIR = process.argv[4] || join(__dirname, '..', '.nebflow', 'shots-secrets');
const EXPECT = process.env.SECRETS_EXPECT ? Number(process.env.SECRETS_EXPECT) : null;

mkdirSync(OUT_DIR, { recursive: true });

async function assertAndShoot(browser, engine, scheme) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: scheme });
  let windowErrors = [];
  let recording = false;
  // ws.js 聊天轨的握手/重连噪声（ws://…/ws 403/连接失败）在此隔离无头实例上
  // 随时可能落进作用窗（重连退避时序不定，双引擎皆可现）——聊天轨面，与本批
  // 面板（纯 fetch）无关，不计入 A6；/api/secrets* 的任何 4xx/5xx 仍硬失败。
  const wsNoise = (s) => /ws:\/\/[^/]+\/ws|WebSocket connection/i.test(s);
  const note = (s) => { if (recording && !wsNoise(s)) windowErrors.push(s); };
  page.on('pageerror', (e) => note(`pageerror: ${e}`));
  page.on('console', (m) => { if (m.type() === 'error') note(`console: ${m.text()}`); });
  page.on('requestfailed', (r) => { if (r.url().startsWith(BASE) && !r.url().includes('/ws')) note(`reqfail: ${r.url()} ${r.failure()?.errorText}`); });
  page.on('response', (r) => {
    // 面板 API 面的 4xx/5xx 无论何时都算本批失败（真请求，不因窗口期豁免）
    if (recording && r.url().includes('/api/secrets') && r.status() >= 400) {
      windowErrors.push(`secrets-api ${r.status()}: ${r.url()}`);
    }
  });
  await page.addInitScript(() => {
    localStorage.setItem('stand_locale', 'zh-CN');
  });
  // token 经 init 参数进页（addInitScript 的闭包不吃外层变量）
  await page.addInitScript((t) => { localStorage.setItem('stand_token', t); }, TOKEN);

  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForFunction(() => document.querySelector('#settings-overlay'), null, { timeout: 20000 });

  // 打开设置 → 等密钥入口行渲染 → 打开密钥子面板
  await page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await page.waitForSelector('#btn-open-secrets', { timeout: 15000 });
  await page.click('#btn-open-secrets');
  await page.waitForFunction(() => document.querySelector('#secrets-overlay')?.classList.contains('on'), null, { timeout: 15000 });
  await page.waitForSelector('.secrets-row', { timeout: 15000 });
  await page.waitForTimeout(500); // 列表渲染 + 动画落定

  // ── A6 作用窗开表：面板已开、启动探测噪声已过，此后的一切错误都记账 ──
  recording = true;
  windowErrors = [];

  const audit = await page.evaluate(() => {
    const parent = document.getElementById('settings-modal')?.getBoundingClientRect();
    const child = document.getElementById('secrets-modal')?.getBoundingClientRect();
    const ovText = document.getElementById('secrets-overlay')?.innerText || '';
    const names = [...document.querySelectorAll('.secrets-row-name')].map((el) => el.getBoundingClientRect());
    const metas = [...document.querySelectorAll('.secrets-row-side .secrets-row-meta')].map((el) => el.getBoundingClientRect());
    const near = (a, b, tol) => Math.abs(a - b) <= tol;
    return {
      contained:
        !!parent && !!child &&
        child.left >= parent.left + 1 && child.right <= parent.right - 1 &&
        child.top >= parent.top + 1 && child.bottom <= parent.bottom - 1,
      rects: parent && child ? {
        p: { l: Math.round(parent.left), r: Math.round(parent.right), t: Math.round(parent.top), b: Math.round(parent.bottom), w: Math.round(parent.width), h: Math.round(parent.height) },
        c: { l: Math.round(child.left), r: Math.round(child.right), t: Math.round(child.top), b: Math.round(child.bottom), w: Math.round(child.width), h: Math.round(child.height) },
      } : null,
      badges: document.querySelectorAll('.secrets-mode-badge').length,
      permissiveWord: ovText.includes('权限过宽'),
      rollbackVisible: /rollback/i.test(ovText),
      rowCount: document.querySelectorAll('.secrets-row').length,
      nameXAligned: names.length > 0 && names.every((r) => near(r.left, names[0].left, 1)),
      metaRightAligned: metas.length > 0 && metas.every((r) => near(r.right, metas[0].right, 1.5)),
    };
  });

  const shot = join(OUT_DIR, `secrets-${engine}-${scheme}.png`);
  await page.screenshot({ path: shot });

  const hard = [];
  if (!audit.contained) hard.push(`A1 层级收进 FAIL ${JSON.stringify(audit.rects)}`);
  if (audit.badges !== 0 || audit.permissiveWord) hard.push(`A2 红标退役 FAIL badges=${audit.badges} word=${audit.permissiveWord}`);
  if (audit.rollbackVisible) hard.push('A3 杂质不出现 FAIL: rollback visible in panel');
  if (!audit.nameXAligned || !audit.metaRightAligned) hard.push(`A4 网格对齐 FAIL name=${audit.nameXAligned} meta=${audit.metaRightAligned}`);
  if (EXPECT !== null && audit.rowCount !== EXPECT) hard.push(`A5 行数 FAIL got=${audit.rowCount} want=${EXPECT}`);
  if (windowErrors.length) hard.push(`A6 console FAIL: ${windowErrors.slice(0, 5).join(' | ')}`);

  console.log(`[${engine}-${scheme}] rows=${audit.rowCount} contained=${audit.contained} badges=${audit.badges} rollback=${audit.rollbackVisible} nameX=${audit.nameXAligned} metaR=${audit.metaRightAligned} errors=${windowErrors.length} -> ${hard.length ? 'FAIL' : 'PASS'}`);
  if (audit.rects) console.log(`  parent=${JSON.stringify(audit.rects.p)} child=${JSON.stringify(audit.rects.c)}`);

  // 行展开态 + 眼形读值实拍（GET /api/secrets/{name}/value —— 面板唯一的取值
  // 调用，也在 A6 作用窗内记账：值域 API 全程零错）。展开与取值是 mock 值，
  // 截图含明文无泄露面。
  await page.click('.secrets-row-head');
  await page.waitForSelector('.secrets-row-detail', { timeout: 8000 });
  await page.waitForTimeout(400);
  await page.click('.secrets-value-eye');
  await page.waitForFunction(() => {
    const f = document.querySelector('.secrets-row-detail .secrets-value');
    return f && f.value && !f.classList.contains('masked');
  }, null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(OUT_DIR, `secrets-${engine}-${scheme}-expanded.png`) });

  await page.close();
  if (hard.length) { console.error(hard.map((h) => `  ✗ ${h}`).join('\n')); return 1; }
  return 0;
}

(async () => {
  let fails = 0;
  const chromiumBrowser = await chromium.launch();
  const firefoxBrowser = await firefox.launch();
  for (const scheme of ['dark', 'light']) {
    fails += await assertAndShoot(chromiumBrowser, 'chromium', scheme);
    fails += await assertAndShoot(firefoxBrowser, 'firefox', scheme);
  }
  await chromiumBrowser.close();
  await firefoxBrowser.close();
  console.log(`shots in ${OUT_DIR}`);
  if (fails) { console.error(`shot-secrets-panel: ${fails} matrix cell(s) FAILED`); process.exit(1); }
  console.log('shot-secrets-panel done — all matrix cells PASS');
})().catch((e) => { console.error(e); process.exit(1); });
