#!/usr/bin/env node
// verify-preset-empty-state.cjs — MODEL PRESETS 空态处理批（2026-09-16 作者令）验收
//
// 作者原令（逐字，2026-09-16 07:20）：
//   「小优化，设置面板里的MODEL PRESETS栏，在没有配置Provider之前，不要出现，
//     而且提示文字要写清楚。要先添加Provider，然后再可以根据agent来调整模型方案」
//
// 打桩路线**完全复用** scripts/e2e-ctxthresh.cjs 头部注释逐字所述配方：
//   `page.route` 从磁盘服务真实前端 + `MockWebSocket` 注入真实 `ws.js` 分发路径，
//   **零端口零进程、不碰 8080**、不碰宿主实例。
//
// 用法：
//   node scripts/verify-preset-empty-state.cjs                    # after——当前磁盘树
//   node scripts/verify-preset-empty-state.cjs --baseline         # before——CHANGED 件取批基版
//   --baseline-ref <ref>   批基引用，默认 main（= 本批建位基线 44a8897bd）
//   --out <file>           把读数 JSON 落盘（红绿两腿各一份，供逐字比对）
//   --shots <dir>          亮暗双主题截图落盘目录（省略则不截图）
//
// 断言集（改前红 / 改后绿；with-provider 腿两态**必须完全一致**）：
//   A 无 Provider 态（config A：llm.providers = {}）
//     A1 #preset-list 计数 = 0        A2 #btn-add-preset 计数 = 0
//     A3 #preset-migrate-banner 计数 = 0
//     A4 标题文案（t('settings.presets')）在 #settings-content 内出现次数 = 0
//     A5 提示行在场：恰好 1 条，文案与 i18n 值**逐字相等**（zh 读数）
//     A6 同一读数（en 读数，切 setLocale('en') 后重渲染）
//     A7 零 /api/presets* 请求
//     A8 零 console error / pageerror
//     A9 零 `Loading…` 残留
//     A10 提示行**未被隐藏包壳**（display≠none ∧ opacity>0 ∧ 有可见盒）
//     A11 无「空态内联跳转」：点击提示行后结构件计数仍为 0（原位零跳转）
//     A12 #settings-content 内 [id^="preset-"] 元素集 = 恰 {preset-empty-hint}
//   B 有 Provider 态（config B：一家 provider + 两个模型）
//     B1 结构件计数 ≥ 1（#preset-list / #btn-add-preset / #preset-migrate-banner）
//     B2 标题文案在场      B3 提示行计数 = 0
//     B4 /api/presets 恰被请求 1 次（loadPresetsSection 全链未被短路）
//     B5 preset 卡渲染 = stub 的 2 张，默认徽标落在 defaultPreset 指定卡上
//     B6 presets 区块 outerHTML（before/after 两腿逐字相等 = 行为零变化）
//   C 亮暗双主题真渲染截图（--shots）

const { chromium } = require('playwright');
const { readFileSync, writeFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
const _refIdx = process.argv.indexOf('--baseline-ref');
const BASELINE_REF = _refIdx >= 0 && process.argv[_refIdx + 1] ? process.argv[_refIdx + 1] : 'main';
const _outIdx = process.argv.indexOf('--out');
const OUT = _outIdx >= 0 && process.argv[_outIdx + 1] ? process.argv[_outIdx + 1] : null;
const _shotsIdx = process.argv.indexOf('--shots');
const SHOTS = _shotsIdx >= 0 && process.argv[_shotsIdx + 1] ? process.argv[_shotsIdx + 1] : null;
// --compare <另一腿读数 JSON>：机械核「有 Provider 态行为零变化」（B6 逐字相等）
const _cmpIdx = process.argv.indexOf('--compare');
const COMPARE = _cmpIdx >= 0 && process.argv[_cmpIdx + 1] ? process.argv[_cmpIdx + 1] : null;

// 本批改动面（--baseline 时取批基版；两腿之外的文件一律取当前磁盘树）
const CHANGED = ['js/sidebar.js', 'js/locales/zh-CN.js', 'js/locales/en.js'];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const CFG_NO_PROVIDER = { llm: { providers: {} }, features: { friends: false } };
const CFG_ONE_PROVIDER = {
  llm: { providers: { zhipu: { protocol: 'openai', models: [{ id: 'glm-4.6' }, { id: 'glm-4.5-air' }] } } },
  features: { friends: false },
};
// /api/presets 的 stub 应答（字段名 = presets.js:49 契约）
const PRESETS_STUB = {
  defaultPreset: 'general',
  presets: [
    { name: 'general', description: '通用', preferred: 'zhipu/glm-4.6', fallbacks: [] },
    { name: 'cheap', description: '省钱', preferred: 'zhipu/glm-4.5-air', fallbacks: [] },
  ],
  agents: {},
};

const baselineBlob = {};
if (MODE === 'before') {
  for (const rel of CHANGED) {
    baselineBlob['/' + rel] = execFileSync(
      'git', ['show', `${BASELINE_REF}:src/main/resources/web/${rel}`],
      { cwd: ROOT, maxBuffer: 30 * 1024 * 1024 });
  }
}

(async () => {
  const browser = await chromium.launch();
  const results = [];
  const failures = [];
  const check = (id, ok, detail) => {
    results.push({ id, ok: !!ok, detail: detail === undefined ? null : detail });
    if (!ok) failures.push(`${id}: ${detail}`);
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${id}${detail !== undefined && detail !== null ? ' — ' + JSON.stringify(detail) : ''}`);
  };

  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
  const consoleErrors = [];
  const pageErrors = [];
  const requests = [];
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const url = (m.location() && m.location().url) || '';
    // 打桩自身产生的噪声：本脚本对未打桩的 /api/* 一律 404（= e2e-ctxthresh 配方），
    // 浏览器据此报 "Failed to load resource"。按 URL 归因，与两条腿逐字可比。
    const harnessNoise = /^https?:\/\/[^/]+\/api\//.test(url);
    consoleErrors.push({ text: m.text(), url, harnessNoise });
  });
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message ? e.message : e)));
  page.on('request', (r) => requests.push(r.url()));

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'verify-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) {
        this.url = u; this.readyState = MockWS.CONNECTING;
        window.__wsMock = this;
        window.__wsSent = [];
      }
      send(data) { try { window.__wsSent.push(JSON.parse(data)); } catch { /* ignore */ } }
      close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // /api/presets* 的 stub（计数在 page.on('request') 里）
    if (p === '/api/presets') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PRESETS_STUB) });
    }
    if (p === '/api/agents') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"agents":[]}' });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    if (MODE === 'before' && baselineBlob[p]) {
      return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: baselineBlob[p] });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(400);

  /** 注入一条服务端帧（走真实 ws.js 分发路径）。 */
  const frame = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);

  /** 推送配置快照（configData = state.parsedConfig 的唯一到达路径，main.js:2581）。 */
  const pushConfig = async (cfg) => {
    await frame({ type: 'configData', config: JSON.stringify(cfg) });
    await page.waitForTimeout(250);
  };

  // ── i18n 期望值：从真实 locale 模块现取（禁照抄字面量） ──────────────
  const i18n = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    return {
      zhHint: zh['settings.presetsEmptyHint'], enHint: en['settings.presetsEmptyHint'],
      zhTitle: zh['settings.presets'], enTitle: en['settings.presets'],
      zhKeys: Object.keys(zh).length, enKeys: Object.keys(en).length,
    };
  });

  const readDom = () => page.evaluate(() => {
    const q = (s) => document.querySelectorAll(s).length;
    const content = document.getElementById('settings-content');
    const hint = document.getElementById('preset-empty-hint');
    let hintVisible = null;
    if (hint) {
      const cs = getComputedStyle(hint);
      const box = hint.getBoundingClientRect();
      hintVisible = { display: cs.display, opacity: cs.opacity, visibility: cs.visibility, h: Math.round(box.height), w: Math.round(box.width) };
    }
    return {
      presetList: q('#preset-list'), addPreset: q('#btn-add-preset'),
      migrateBanner: q('#preset-migrate-banner'),
      hint: q('#preset-empty-hint'),
      hintText: hint ? hint.textContent : null,
      hintVisible,
      idPrefixed: [...(content ? content.querySelectorAll('[id^="preset-"]') : [])].map(e => e.id),
      // 标题**结构件**读数（禁按裸子串计数——提示文案本身含「模型方案」字样）
      sectionTitles: content ? [...content.querySelectorAll('.settings-section-title')].map(e => e.textContent.trim()) : [],
      html: content ? content.innerHTML : '',
      loadingResidue: content ? (content.innerHTML.match(/Loading…/g) || []).length : -1,
      presetCards: q('.preset-card'),
      defaultBadges: [...document.querySelectorAll('#preset-list .preset-card')].map(c => ({
        name: c.dataset.preset,
        hasDefaultBadge: !!c.querySelector('.preset-default-badge'),
      })),
      presetSectionHtml: (() => {
        const t = document.getElementById('btn-add-preset') || document.getElementById('preset-empty-hint');
        return t ? (t.closest('.settings-section') ? t.closest('.settings-section').outerHTML : t.outerHTML) : null;
      })(),
    };
  });

  const setLocale = (code) => page.evaluate(async (c) => {
    const m = await import('/js/i18n.js');
    m.setLocale(c);
  }, code);

  const apiPresetReqs = () => requests.filter(u => u.includes('/api/presets')).length;

  console.log(`[preset-empty-state] mode=${MODE} ref=${BASELINE_REF} web=${WEB}`);

  // ══ A 腿：无 Provider 态 ═══════════════════════════════════════════
  console.log('\n── A 无 Provider 态 ──');
  await pushConfig(CFG_NO_PROVIDER);
  // 真实入口：Activity Bar 齿轮 → activityBar.js:302 bindSettingsButton →
  // openSettingsPanel() → sidebar.js:75 renderSettings()
  await page.click('#settings-btn');
  await page.waitForTimeout(600);

  const beforeReqs = apiPresetReqs();
  let A = await readDom();

  // A0：真实入口被走到（openSettingsPanel 发 getConfig）⇒ 本腿不是「手搓 DOM」
  const sentGetConfig = await page.evaluate(() => (window.__wsSent || []).some(m => m && m.type === 'getConfig'));
  check('A0 真实入口 openSettingsPanel 已执行（出行帧 getConfig 在案）', sentGetConfig === true, { sentGetConfig });

  check('A1 #preset-list 计数 = 0', A.presetList === 0, { got: A.presetList });
  check('A2 #btn-add-preset 计数 = 0', A.addPreset === 0, { got: A.addPreset });
  check('A3 #preset-migrate-banner 计数 = 0', A.migrateBanner === 0, { got: A.migrateBanner });
  const titleHits = A.sectionTitles.filter(x => x === i18n.zhTitle).length;
  check('A4 presets 标题**结构件**计数 = 0（.settings-section-title 等值计数）', titleHits === 0,
    { text: i18n.zhTitle, hits: titleHits, titles: A.sectionTitles });
  check('A5 提示行在场且文案逐字相符（zh）', A.hint === 1 && A.hintText === i18n.zhHint,
    { count: A.hint, got: A.hintText, want: i18n.zhHint });
  check('A9 零 Loading… 残留', A.loadingResidue === 0, { got: A.loadingResidue });
  check('A10 提示行未被隐藏包壳（可见盒）',
    !!A.hintVisible && A.hintVisible.display !== 'none' && Number(A.hintVisible.opacity) > 0
      && A.hintVisible.visibility !== 'hidden' && A.hintVisible.h > 0 && A.hintVisible.w > 0,
    A.hintVisible);
  check('A12 [id^="preset-"] 元素集 = 恰 {preset-empty-hint}',
    JSON.stringify(A.idPrefixed) === JSON.stringify(['preset-empty-hint']), { got: A.idPrefixed });

  // A11：点击提示行 ⇒ 原位零跳转（结构件计数仍为 0，无新增区块）
  await page.evaluate(() => {
    const el = document.getElementById('preset-empty-hint');
    if (el) el.click();
    document.body.click();
  });
  await page.waitForTimeout(200);
  const AafterClick = await readDom();
  check('A11 点击提示行后零跳转（结构件仍全 0）',
    AafterClick.presetList === 0 && AafterClick.addPreset === 0 && AafterClick.hint === 1,
    { after: { presetList: AafterClick.presetList, addPreset: AafterClick.addPreset, hint: AafterClick.hint } });

  check('A7 零 /api/presets* 请求', apiPresetReqs() === beforeReqs, { got: apiPresetReqs(), base: beforeReqs });

  // A6：en 读数（同一断言集）
  await setLocale('en');
  await pushConfig(CFG_NO_PROVIDER);
  await page.waitForTimeout(300);
  const Aen = await readDom();
  const enTitleHits = Aen.sectionTitles.filter(x => x === i18n.enTitle).length;
  check('A6 提示行在场且文案逐字相符（en）', Aen.hint === 1 && Aen.hintText === i18n.enHint,
    { count: Aen.hint, got: Aen.hintText, want: i18n.enHint });
  check('A6b en presets 标题结构件计数 = 0', enTitleHits === 0, { text: i18n.enTitle, hits: enTitleHits });
  check('A6c en 态结构件全 0', Aen.presetList === 0 && Aen.addPreset === 0 && Aen.migrateBanner === 0,
    { presetList: Aen.presetList, addPreset: Aen.addPreset, migrateBanner: Aen.migrateBanner });

  const realErrors = consoleErrors.filter(e => !e.harnessNoise);
  const presetErrors = consoleErrors.filter(e => /preset/i.test(e.text) || /\/api\/presets/.test(e.url));
  check('A8 零「非打桩噪声」console error', realErrors.length === 0, { got: realErrors.slice(0, 5) });
  check('A8a 零 preset 相关 console error', presetErrors.length === 0, { got: presetErrors.slice(0, 5) });
  check('A8b 零 pageerror', pageErrors.length === 0, { got: pageErrors.slice(0, 5) });

  // ══ 截图（亮暗双主题，无 Provider 态 = 本批改动面）═══════════════
  if (SHOTS) {
    mkdirSync(SHOTS, { recursive: true });
    await setLocale('zh-CN');
    await page.setViewportSize({ width: 1280, height: 1750 });   // 让整块设置模态入画
    for (const scheme of ['dark', 'light']) {
      await page.emulateMedia({ colorScheme: scheme });
      await pushConfig(CFG_NO_PROVIDER);
      await page.waitForTimeout(350);
      const el = await page.$('#settings-modal');
      if (el) await el.screenshot({ path: join(SHOTS, `preset-noprovider-${scheme}.png`) });
      await page.screenshot({ path: join(SHOTS, `preset-noprovider-${scheme}-full.png`) });
      console.log(`  [shot] preset-noprovider-${scheme}.png`);
    }
  }

  // ══ B 腿：有 Provider 态 ═══════════════════════════════════════════
  console.log('\n── B 有 Provider 态 ──');
  const reqsBeforeB = apiPresetReqs();
  await setLocale('zh-CN');                  // A6 腿把 locale 切到 en，B 腿读数须回 zh
  await pushConfig(CFG_ONE_PROVIDER);
  await page.waitForTimeout(900);
  const B = await readDom();
  const titleHitsB = B.sectionTitles.filter(x => x === i18n.zhTitle).length;

  check('B1 结构件计数 ≥ 1', B.presetList >= 1 && B.addPreset >= 1 && B.migrateBanner >= 1,
    { presetList: B.presetList, addPreset: B.addPreset, migrateBanner: B.migrateBanner });
  check('B2 presets 标题结构件在场', titleHitsB >= 1, { text: i18n.zhTitle, hits: titleHitsB, titles: B.sectionTitles });
  check('B3 提示行计数 = 0', B.hint === 0, { got: B.hint });
  check('B4 /api/presets 被请求（全链未被短路）', apiPresetReqs() > reqsBeforeB,
    { before: reqsBeforeB, after: apiPresetReqs() });
  check('B5 preset 卡渲染 = 2 且默认徽标落在 defaultPreset 卡',
    B.presetCards === 2
      && B.defaultBadges.filter(c => c.hasDefaultBadge).length === 1
      && (B.defaultBadges.find(c => c.hasDefaultBadge) || {}).name === PRESETS_STUB.defaultPreset,
    { cards: B.presetCards, badges: B.defaultBadges });

  if (SHOTS) {
    for (const scheme of ['dark', 'light']) {
      await page.emulateMedia({ colorScheme: scheme });
      await pushConfig(CFG_ONE_PROVIDER);
      await page.waitForTimeout(500);
      const el = await page.$('#settings-modal');
      if (el) await el.screenshot({ path: join(SHOTS, `preset-withprovider-${scheme}.png`) });
      console.log(`  [shot] preset-withprovider-${scheme}.png`);
    }
  }

  // ══ 读数落盘 ════════════════════════════════════════════════════════
  const readings = {
    mode: MODE, ref: BASELINE_REF, changed: CHANGED,
    i18n: { zhKeys: i18n.zhKeys, enKeys: i18n.enKeys, zhHint: i18n.zhHint, enHint: i18n.enHint },
    A_no_provider: {
      presetList: A.presetList, addPreset: A.addPreset, migrateBanner: A.migrateBanner,
      hint: A.hint, hintText: A.hintText, hintVisible: A.hintVisible,
      titleHits, idPrefixed: A.idPrefixed, loadingResidue: A.loadingResidue,
      enHintText: Aen.hintText, enTitleHits,
    },
    B_with_provider: {
      presetList: B.presetList, addPreset: B.addPreset, migrateBanner: B.migrateBanner,
      hint: B.hint, titleHits: titleHitsB, presetCards: B.presetCards,
      defaultBadges: B.defaultBadges, presetSectionHtml: B.presetSectionHtml,
      presetReqsTotal: apiPresetReqs(),
    },
    errors: {
      consoleErrorsTotal: consoleErrors.length,
      consoleErrorsHarnessNoise: consoleErrors.filter(e => e.harnessNoise).length,
      consoleErrorsReal: consoleErrors.filter(e => !e.harnessNoise),
      presetErrors: consoleErrors.filter(e => /preset/i.test(e.text) || /\/api\/presets/.test(e.url)),
      pageErrors,
    },
    results,
  };
  if (OUT) { writeFileSync(OUT, JSON.stringify(readings, null, 2)); console.log(`\n[readings] ${OUT}`); }

  // ══ B6：与另一腿读数机械核「行为零变化」 ══════════════════════════════
  if (COMPARE) {
    console.log('\n── B6 两腿行为零变化（有 Provider 态逐字相等）──');
    const other = JSON.parse(readFileSync(COMPARE, 'utf8'));
    const o = other.B_with_provider, m = readings.B_with_provider;
    check('B6 presets 区块 outerHTML 两腿逐字相等（行为零变化）',
      m.presetSectionHtml === o.presetSectionHtml,
      { selfLen: m.presetSectionHtml.length, otherLen: o.presetSectionHtml.length, otherMode: other.mode });
    check('B6a 结构件计数两腿相等',
      m.presetList === o.presetList && m.addPreset === o.addPreset && m.migrateBanner === o.migrateBanner,
      { self: [m.presetList, m.addPreset, m.migrateBanner], other: [o.presetList, o.addPreset, o.migrateBanner] });
    check('B6b 卡与默认徽标两腿相等',
      JSON.stringify(m.defaultBadges) === JSON.stringify(o.defaultBadges),
      { self: m.defaultBadges, other: o.defaultBadges });
    check('B6c 零 Provider 态结构件读数两腿**必须不同**（红绿对照成立）',
      JSON.stringify([readings.A_no_provider.presetList, readings.A_no_provider.addPreset,
        readings.A_no_provider.migrateBanner, readings.A_no_provider.hint]) !==
      JSON.stringify([other.A_no_provider.presetList, other.A_no_provider.addPreset,
        other.A_no_provider.migrateBanner, other.A_no_provider.hint]),
      { self: [readings.A_no_provider.presetList, readings.A_no_provider.addPreset,
        readings.A_no_provider.migrateBanner, readings.A_no_provider.hint],
        other: [other.A_no_provider.presetList, other.A_no_provider.addPreset,
          other.A_no_provider.migrateBanner, other.A_no_provider.hint] });
  }

  await browser.close();
  console.log(`\n[preset-empty-state] mode=${MODE}  PASS=${results.filter(r => r.ok).length}/${results.length}`);
  if (failures.length) { console.log('FAILURES:'); failures.forEach(f => console.log('  - ' + f)); process.exit(1); }
  console.log('ALL GREEN');
})();
