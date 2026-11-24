// model-chain-slim.spec.mjs — 设置页渐进披露模型选择验收。
// 冻结意图见 .stand/docs/settings-progressive-disclosure-zh__20261107_230657__flow-453d463d.md：
// 每类会话单选一个模型，不再把回退链当作设置页契约；摘要可展开、说明可读。
// 五类独立角色在设置页逐行呈现，保存沿用既有单角色 PUT 通道；其余冻结验收另行列项。
// 双引擎（Chromium + WebKit）× 深/浅主题 × 桌面 + Chromium 手机；功能腿验证单模型替换即存。
//
// 运行：node tests/model-chain-slim.spec.mjs                    # 双引擎
//      ENGINE=webkit node tests/model-chain-slim.spec.mjs       # 仅 WebKit
// 可选 env：PORT=8334、SHOTS_DIR=<dir>、HEADLESS=0

import { chromium, webkit } from 'playwright-core';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(process.env.WEB_DIR || resolve(ROOT, 'src/main/resources/web'));
const PORT = Number(process.env.PORT || 8334);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(ROOT, 'reports-evidence', '20261111_model-granularity');
const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');
const SID = 'mcs-root-session';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(SHOTS_DIR, { recursive: true });

let failures = 0;
const ok = (tag, name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  [${tag}] ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

/* ─────────────── 夹具：三行链 + 一个链外模型（添加模型下拉非空） ─────────────── */
const PROVIDERS = {
  zhipu: { baseUrl: 'https://api.zhipu.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'glm-5.3-flash' }, { id: 'glm-4.6' }] },
  kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: '***', protocol: 'anthropic', models: [{ id: 'kimi-for-coding' }] },
  cmdcode: { baseUrl: 'https://api.cmd.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'deepseek/deepseek-v4.1-flash' }] },
};
const CHAIN = {
  preferred: 'zhipu/glm-5.3-flash',
  fallbacks: [],
};
const RETIRED_KEYS = [
  'model.refreshList', 'model.refreshFailed', 'model.manualInput',
  'model.manualPlaceholder', 'model.clearChain', 'model.clearChainConfirm',
  // switch 批 2026-11-21：整句模式注记退役（类别名从句子中间挪到胶囊行，句子由
  // model.divergedNoteLead / model.followsNebula 作引导语，当前态走 model.modeState*）。
  'settings.modelModeUnifiedNote',
];
/** switch 批新增的四个键（两侧必须对称在册）。 */
const MODE_KEYS = ['model.modeSeparateHint', 'model.modeStateUnified', 'model.modeStateSeparate', 'model.divergedNoteLead'];
const TITLES = { 'zh-CN': '模型（按会话类别）', en: 'Model (per session class)' };

/* ─────────────── 静态服务（真 web/ 树） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const p0 = decodeURIComponent(new URL(req.url, BASE).pathname);
  const p = p0 === '/' ? '/index.html' : p0;
  // /api/* 兜底：WebKit 的 fetch 偶发绕过 page.route 直打服务端 —— 服务端同
  // 形态应答 ⇒ 零 404 噪声（零 console error 的前提）。
  if (p.startsWith('/api/')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 引导（mock API + mock WS + 真 web 壳） ─────────────── */
async function boot(browser, { viewport, colorScheme, locale = 'zh-CN', touch = false }) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch: touch });
  const sim = { mode: 'follow', source: '', chains: Object.fromEntries(['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent'].map((r) => [r, r === 'Nebula' ? CHAIN : null])), puts: [] };
  await ctx.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
    // SW 关闸（sw.js 温缓存会绕开 page.route，静态服务下无正收益）。
    localStorage.setItem('stand_sw', '0');
  }, locale);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`); });
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/**', (r) => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    if (p === '/api/model-rules/mode') {
      sim.modePuts = (sim.modePuts || 0) + 1;
      if (sim.failMode) return r.fulfill({ status: 500, json: { error: 'save failed' } });
      sim.mode = r.request().postDataJSON().mode;
      return r.fulfill({ json: { mode: sim.mode } });
    }
    if (p === '/api/model-rules') {
      if (r.request().method() === 'PUT') sim.source = r.request().postDataJSON().source;
      return r.fulfill({ json: { mode: sim.mode, source: sim.source, diagram: { nodes: [] } } });
    }
    if (p === '/api/sessions') {
      return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return r.fulfill({ json: { messages: [], total: 0, sessionId: SID } });
    }
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      const role = decodeURIComponent(p.split('/')[3]);
      if (r.request().method() === 'PUT') {
        const body = r.request().postDataJSON();
        sim.puts.push({ role, ...body });
        if (sim.failRole === role) return r.fulfill({ status: 500, json: { error: 'save failed' } });
        sim.chains[role] = body && body.model ? body.model : null;
      }
      const own = sim.chains[role];
      const effective = own || sim.chains.Nebula || { preferred: 'seed/seed-model', fallbacks: [] };
      return r.fulfill({
        json: {
          mode: own ? 'explicit' : 'follow',
          chain: own,
          effectiveChain: effective,
          resolvedFrom: sim.chain ? 'own-chain' : 'seed',
          current: effective.preferred,
          settable: true,
        },
      });
    }
    return r.fulfill({ json: {} });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ llm: { providers: PROVIDERS } }),
    }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') { sendConfig(); return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
  await page.goto(BASE + '/index.html');
  // 手机层下 #activity-bar 是隐藏在场（attached 判据；desktop 两态皆满足）。
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await sleep(600);
  return { ctx, page, errors, sim };
}

/* ─────────────── 导航到设置页（桌面 / 手机两路真 UI 入口） ─────────────── */
async function openSettings(page, { mobile }) {
  if (mobile) {
    await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
    await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="workbench"]')?.classList.contains('live'));
    await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('settings'));
    await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'));
  } else {
    await page.click('#settings-btn');
  }
  const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disclosure.getAttribute('aria-expanded') !== 'true') await disclosure.click();
  await page.waitForSelector('#model-chain-section-body [data-role="Nebula"] .mp-settings-role-model', { state: 'visible', timeout: 15000 });
  await page.evaluate(() => document.getElementById('model-chain-section')?.scrollIntoView({ block: 'start' }));
  await sleep(350);
  if (mobile) {
    const clearance = await page.evaluate(() => {
      const cardTitle = document.querySelector('#model-chain-section > .set-card-title');
      const disclosure = document.querySelector('#model-chain-disclosure');
      if (!cardTitle || !disclosure) return null;
      const cardRect = cardTitle.getBoundingClientRect();
      return { cardTitleTop: cardRect.top, disclosureTop: disclosure.getBoundingClientRect().top };
    });
    ok('mobile/clearance', '模型卡标题在视口内可见',
      !!clearance && clearance.cardTitleTop >= 0, JSON.stringify(clearance));
  }
}

/* ─────────────── 档位：unified（默认）/ separate ─────────────── */
/**
 * 切到「分开配置」档（模型设置页 switch 批 2026-11-21：两段式按钮改成一个开关，
 * 关 = 全部跟随主会话、开 = 分开配置；默认档 unified）。本 spec 的逐类可写断言
 * 属于 separate 档，先真点开关再断言 —— 顺带把开关的乐观翻转 + setMode 一起验了。
 */
async function ensureSeparate(page) {
  const sw = page.locator('#model-chain-modes [data-rule-mode="slots"]');
  if (await sw.getAttribute('aria-checked') !== 'true') {
    await sw.click();
    await page.waitForFunction(
      () => document.querySelector('#model-chain-modes [data-rule-mode="slots"]')?.getAttribute('aria-checked') === 'true',
      null, { timeout: 5000 });
  }
  await page.waitForSelector('#small-llm-settings-row select', { state: 'visible', timeout: 15000 });
}

/* ─────────────── 单腿：断言 + 实拍 ─────────────── */
async function leg(engineName, browser, { theme, locale = 'zh-CN', mobile = false }) {
  const tag = `${engineName}/${mobile ? 'mobile' : 'desktop'}/${theme}/${locale}`;
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 };
  const { ctx, page, errors, sim } = await boot(browser, { viewport, colorScheme: theme, locale, touch: mobile });
  await openSettings(page, { mobile });
  if (engineName === 'chromium' && theme === 'light' && locale === 'zh-CN') {
    const modes = page.locator('#model-chain-modes');
    const section = page.locator('#model-chain-section');
    await section.screenshot({ path: join(SHOTS_DIR, `tri-follow-${mobile ? 'mobile' : 'desktop'}.png`) });
    ok(tag, '跟随态不渲染编辑器', await page.locator('.mr-draft').count() === 0);
    await modes.locator('[data-rule-mode="slots"]').click();
    await page.waitForFunction(() => document.querySelector('[data-rule-mode="slots"]')?.getAttribute('aria-checked') === 'true');
    await section.screenshot({ path: join(SHOTS_DIR, `tri-slots-${mobile ? 'mobile' : 'desktop'}.png`) });
    ok(tag, '分槽态不渲染编辑器', await page.locator('.mr-draft').count() === 0);
    await modes.locator('[data-rule-mode="rules"]').click();
    await page.locator('.mr-draft').waitFor({ state: 'visible' });
    await section.screenshot({ path: join(SHOTS_DIR, `tri-rules-${mobile ? 'mobile' : 'desktop'}.png`) });
    ok(tag, '规则摘要和编辑器在场', await page.locator('#model-rules-section-body .set-row-hint').innerText() === '暂无规则。');
    await page.locator('.mr-draft').fill('rule example');
    await page.locator('[data-act="apply"]').click();
    await page.waitForFunction(() => document.querySelector('#model-rules-section-body .set-row-hint')?.textContent === '源码');
    ok(tag, '规则保存后摘要更新', sim.source === 'rule example');
    await modes.locator('[data-rule-mode="follow"]').click();
    await page.waitForFunction(() => document.querySelector('[data-rule-mode="follow"]')?.getAttribute('aria-checked') === 'true');
    await page.waitForFunction(() => document.querySelector('#model-chain-config .mp-config-inner')?.children.length === 0);
  }

  // ① 模式开关（switch 批）：全站唯一开关组件，一个 role=switch 的按钮 + 一行当前态。
  const modeSwitch = page.locator('#model-chain-modes [data-rule-mode="follow"]');
  ok(tag, '模式控件 = 三态单选组',
    await page.locator('#model-chain-modes[role="radiogroup"]').count() === 1 &&
    await page.locator('#model-chain-modes button[role="radio"]').count() === 3);
  ok(tag, '默认档 = 全部跟随',
    await modeSwitch.getAttribute('aria-checked') === 'true' &&
    (await page.locator('#model-chain-mode-state').textContent() || '')
      .includes(locale === 'zh-CN' ? '全部跟随' : 'Follow'),
    await page.locator('#model-chain-mode-state').textContent());
  // unified 档（渐进披露批 2026-11-21 作者令「关档不要置灰展示那堆卡，只留开关；开档才
  // 展开四类配置卡与对应说明」）：只渲染主会话卡（唯一可写主链）；披露体收起且**零子件**
  // （follower 卡 / 注记 / 小模型槽不渲染，不是置灰）。
  const uni = await page.evaluate(() => {
    const body = document.getElementById('model-chain-section-body');
    const inner = document.querySelector('#model-chain-config .mp-config-inner');
    return {
      roles: [...body.querySelectorAll('[data-role]')].map((r) => r.dataset.role),
      selects: body.querySelectorAll('[data-role] select').length, // 生图分组不计（非会话类别）
      foldOpen: document.querySelector('#model-chain-config')?.classList.contains('open') === true,
      foldChildren: inner ? inner.children.length : -1,
      notes: document.querySelectorAll('#model-chain-diverged-note, #model-chain-follow-note').length,
      greyed: document.querySelectorAll('.mp-role-follow, .mp-follow-readout').length,
    };
  });
  ok(tag, 'unified：只渲染主会话卡（唯一可写）、披露体收起且零子件、零注记、零置灰残留',
    uni.roles.join(',') === 'Nebula' && uni.selects === 1 && !uni.foldOpen &&
    uni.foldChildren === 0 && uni.notes === 0 && uni.greyed === 0, JSON.stringify(uni));
  ok(tag, 'unified：小模型槽不渲染（「全部跟随」不得有可写例外槽）',
    await page.locator('#small-llm-settings-row').count() === 0);
  // ② 真点击开关 → 分开配置（乐观翻转须先于重绘：见 renderSessionModeControl 注释）
  await ensureSeparate(page);
  const sep = await page.evaluate(() => ({
    roles: [...document.querySelectorAll('#model-chain-section-body .mp-settings-role-panel[data-role]')]
      .map((r) => `${r.dataset.role}:${r.querySelector('select') ? 'SELECT' : 'NONE'}`),
    small: document.querySelectorAll('#small-llm-settings-row select').length,
  }));
  ok(tag, '开关打开 = 分开配置（五类逐个可写 + 小模型槽）',
    sep.roles.join(',') === 'Nebula:SELECT,kernel:SELECT,project-dispatcher:SELECT,node:SELECT,subagent:SELECT' &&
    sep.small === 1, JSON.stringify(sep));
  ok(tag, '当前态文字随档位改写',
    (await page.locator('#model-chain-mode-state').textContent() || '')
      .includes(locale === 'zh-CN' ? '分开配置' : 'Per-slot'),
    await page.locator('#model-chain-mode-state').textContent());
  ok(tag, '开档：披露体展开（.open）且 follower 卡在披露体内', await page.evaluate(() =>
    document.getElementById('model-chain-config')?.classList.contains('open') === true &&
    !!document.querySelector('#model-chain-config .mp-config-inner [data-role="subagent"]')));
  // 切回关档 ⇒ 披露体收起后清空（follower 卡离场、不残留壳），主会话卡仍在
  await page.locator('#model-chain-modes [data-rule-mode="follow"]').click();
  await page.waitForFunction(() => document.querySelector('#model-chain-config .mp-config-inner')?.children.length === 0,
    null, { timeout: 5000 });
  const back = await page.evaluate(() => ({
    roles: [...document.querySelectorAll('#model-chain-section-body [data-role]')].map((r) => r.dataset.role),
    open: document.getElementById('model-chain-config')?.classList.contains('open') === true,
  }));
  ok(tag, '关档回切：披露体清空，仅剩主会话卡', back.roles.join(',') === 'Nebula' && !back.open, JSON.stringify(back));
  await ensureSeparate(page);

  const small = page.locator('#small-llm-settings-row');
  // 修前这里钉的是 `#small-llm-settings-row .set-row` ——两模式批（2026-11-18）之后
  // 小模型行由 settingsModelCard 造（与五类角色行同一个卡组件 + 同一个
  // `.mp-add-select mp-settings-role-model` 选择器），本行不再是 set-row 行令牌，
  // 该断言自那批起就红了（main tip f69c4b26f 上同样红）。意图保持不变：这一行
  // 必须复用共享控件，不得自带一套样式。改钉共享卡 + 共享选择器类。
  ok(tag, '通用小 LLM 设置行复用共享卡与共享选择器（零自带控件）',
    await small.evaluate((el) => el.classList.contains('mp-settings-role-panel') &&
      el.querySelectorAll('.mp-role-name').length === 1 &&
      el.querySelectorAll('select.mp-add-select.mp-settings-role-model').length === 1));
  const smallSelect = small.locator('select');
  ok(tag, '小模型未配置提示回退主模型', (await smallSelect.locator('option').first().textContent()).includes(locale === 'zh-CN' ? '未配置（回退主模型）' : 'Not configured'));
  await smallSelect.selectOption('kimi/kimi-for-coding');
  await page.waitForFunction(() => document.querySelector('#small-llm-settings-row select')?.value === 'kimi/kimi-for-coding');
  ok(tag, '小模型选择写入独立槽', sim.puts.some(p => p.role === 'small-llm' && p.model?.preferred === 'kimi/kimi-for-coding'));
  await page.locator('#small-llm-settings-row select').selectOption('');
  await page.waitForFunction(() => document.querySelector('#small-llm-settings-row select')?.value === '');
  ok(tag, '清除小模型配置', sim.puts.some(p => p.role === 'small-llm' && p.model === null));
  sim.puts.length = 0;
  // ③ 标题
  const title = (await page.locator('#model-chain-section > .set-card-title').textContent() || '').trim();
  const disclosureTitle = (await page.locator('#model-chain-section [data-settings-disclosure="models"]').textContent() || '').trim();
  const summary = await page.locator('#model-chain-section [data-settings-disclosure="models"]').textContent();
  ok(tag, '③ 标题 = 模型（按会话类别） / Model (per session class)', title === TITLES[locale] && disclosureTitle.startsWith(locale === 'zh-CN' ? '5 类会话模型' : '5 session classes'), `title="${title}" disclosure="${disclosureTitle}"`);
  ok(tag, '④ 摘要显示可配置会话类别数量', (summary || '').includes('5'), `summary="${summary}"`);

  // ④ 说明一行内：高度 = 单行行高，且横向不溢出（无 ellipsis 截断）
  const hint = await page.evaluate(() => {
    const el = document.querySelector('#model-chain-disclosure > .set-row-hint');
    if (!el) return null;
    const cs = getComputedStyle(el);
    const range = document.createRange();
    range.selectNodeContents(el);
    const natural = range.getBoundingClientRect().width;
    const r = el.getBoundingClientRect();
    return {
      text: el.textContent.trim(), h: Math.round(r.height),
      lh: parseFloat(cs.lineHeight), box: el.clientWidth,
      natural: Math.round(natural), scroll: el.scrollWidth,
    };
  });
  ok(tag, '④ 说明文字在位', !!hint && hint.text.length > 0, JSON.stringify(hint));
  if (hint) {
    // Frozen §4 permits wrapping labels at narrow widths; verify bounded, visible text,
    // not the superseded one-line requirement from the earlier chain UI.
    ok(tag, '④ 说明在布局内可见（允许按冻结规格换行）', hint.h > 0 && hint.box > 0 && hint.scroll <= hint.box + 1,
      `h=${hint.h} box=${hint.box} scroll=${hint.scroll}`);
  }

  // 五类角色各有独立有效值与选择器，不显示后备链编辑器。
  const roles = await page.evaluate(() => [...document.querySelectorAll('#model-chain-section-body [data-role]')].map((row) => ({
    role: row.dataset.role,
    label: row.querySelector('label')?.textContent.trim(),
    select: row.querySelector('select')?.tagName,
    options: row.querySelector('select')?.options.length || 0,
    hidden: row.hidden,
    height: row.querySelector('select')?.getBoundingClientRect().height || 0,
    overflow: row.scrollWidth > row.clientWidth,
  })));
  ok(tag, '五类角色独立行齐全', roles.length === 5 && roles.map((r) => r.role).join(',') === 'Nebula,kernel,project-dispatcher,node,subagent', JSON.stringify(roles));
  ok(tag, '五类角色均有单模型选择器、继承状态和44px触控高度', roles.every((r) => r.select === 'SELECT' && r.options >= 2 && r.height >= 44 && !r.overflow), JSON.stringify(roles));

  // i18n 双语键：新文案在册、旧键连根拔除、两字典同长
  const dict = await page.evaluate(async ({ retired, modeKeys }) => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    return {
      zhTitle: zh['settings.modelChainTitle'], enTitle: en['settings.modelChainTitle'],
      zhHint: zh['settings.modelChainSectionHint'], enHint: en['settings.modelChainSectionHint'],
      zhSummary: zh['settings.modelsSummary'], enSummary: en['settings.modelsSummary'],
      zhLeak: retired.filter((k) => k in zh), enLeak: retired.filter((k) => k in en),
      zhMode: modeKeys.filter((k) => !zh[k]), enMode: modeKeys.filter((k) => !en[k]),
      zhCount: Object.keys(zh).length, enCount: Object.keys(en).length,
    };
  }, { retired: RETIRED_KEYS, modeKeys: MODE_KEYS });
  ok(tag, 'i18n 双语标题齐', dict.zhTitle === TITLES['zh-CN'] && dict.enTitle === TITLES.en,
    `zh="${dict.zhTitle}" en="${dict.enTitle}"`);
  ok(tag, '渐进披露摘要双语键齐', dict.zhSummary === '{n} 类会话模型' && dict.enSummary === '{n} session classes',
    `zh="${dict.zhSummary}" en="${dict.enSummary}"`);
  ok(tag, 'i18n 双语说明齐', !!dict.zhHint && !!dict.enHint, `zh="${dict.zhHint}" en="${dict.enHint}"`);
  ok(tag, 'i18n 开关档四键双语对称在册', dict.zhMode.length === 0 && dict.enMode.length === 0,
    JSON.stringify({ zh: dict.zhMode, en: dict.enMode }));
  ok(tag, 'i18n 退役键零残留（zh/en 双向）', dict.zhLeak.length === 0 && dict.enLeak.length === 0,
    JSON.stringify({ zhLeak: dict.zhLeak, enLeak: dict.enLeak }));
  // 当前基线的字典总数差为 6；退役键双侧检查在上方独立断言。
  ok(tag, 'i18n 字典差值 = 当前基线 6', dict.zhCount - dict.enCount === 6,
    `zh=${dict.zhCount} en=${dict.enCount} Δ=${dict.zhCount - dict.enCount}`);

  // 实拍：五类角色各自独立在位，截图确认同屏可见。
  const roleKeys = ['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent'];
  for (const role of roleKeys) {
    const row = page.locator(`#model-chain-section-body [data-role="${role}"]`);
    await row.scrollIntoViewIfNeeded();
    const roleShot = join(SHOTS_DIR, `role-${role}-${engineName}-${mobile ? 'mobile' : 'desktop'}-${theme}${locale === 'en' ? '-en' : ''}.png`);
    await row.screenshot({ path: roleShot });
  }
  const shot = join(SHOTS_DIR, `chain-${engineName}-${mobile ? 'mobile' : 'desktop'}-${theme}${locale === 'en' ? '-en' : ''}.png`);
  await page.locator('#model-chain-section').screenshot({ path: shot });
  ok(tag, '五类别视图截图落盘', true, `${SHOTS_DIR}/role-<role>-${engineName}-${mobile ? 'mobile' : 'desktop'}-${theme}.png`);

  // 零 console error
  const NOISE = /favicon|net::ERR|WebSocket|404|ResizeObserver loop/i;
  const fresh = errors.filter((e) => !NOISE.test(e));
  ok(tag, '零 console error / 零 pageerror', fresh.length === 0, fresh.slice(0, 4).join(' | '));

  await ctx.close();
  return { sim };
}

/* ─────────────── 功能腿：排序拖拽 / 删除 / 加模型真点真存 ─────────────── */
async function functionalLeg(browser) {
  const tag = 'chromium/desktop/dark/functional';
  const { ctx, page, errors, sim } = await boot(browser, {
    viewport: { width: 1440, height: 900 }, colorScheme: 'dark',
  });
  await openSettings(page, { mobile: false });
  await ensureSeparate(page); // 小模型槽 + 逐类选择器只在分开配置档铺出

  // Failed writes must restore the server value in the live select control.
  for (const role of ['Nebula', 'small-llm']) {
    const selector = role === 'small-llm' ? '#small-llm-settings-row select' : '#model-role-select-Nebula';
    sim.failRole = role;
    const write = page.waitForResponse((response) => response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith(`/agents/${role}/model`));
    await page.locator(selector).selectOption('kimi/kimi-for-coding');
    await write;
    const expected = role === 'Nebula' ? 'zhipu/glm-5.3-flash' : '';
    const reverted = await page.waitForFunction(({ selector, expected }) => document.querySelector(selector)?.value === expected,
      { selector, expected }, { timeout: 5000 }).then(() => true, () => false);
    ok(tag, `${role} failed save reverts live selection`, reverted);
    sim.failRole = null;
  }
  sim.puts.length = 0;
  errors.length = 0; // The two injected HTTP 500 responses are intentional.
  // Each category writes through its existing role endpoint and reads back independently.
  for (const role of ['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent']) {
    const select = page.locator(`#model-role-select-${role}`);
    const saved = page.waitForResponse((response) => response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith(`/agents/${encodeURIComponent(role)}/model`));
    await select.selectOption('kimi/kimi-for-coding');
    await saved;
    await page.waitForResponse((response) => response.request().method() === 'GET' &&
      new URL(response.url()).pathname.endsWith(`/agents/${encodeURIComponent(role)}/model`));
  }
  ok(tag, '五类逐一选择、PUT并读回对应槽链', sim.puts.length === 5 &&
    sim.puts.every((put, i) => put.role === ['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent'][i] &&
      JSON.stringify(put.model) === JSON.stringify({ preferred: 'kimi/kimi-for-coding', fallbacks: [] })) &&
    Object.values(sim.chains).every((chain) => chain?.preferred === 'kimi/kimi-for-coding'), JSON.stringify(sim.puts));

  // /model 面板同源面同样零退役控件（同一 buildNebulaChainFace）
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  await page.waitForFunction(() => document.querySelectorAll('#model-panel-overlay .mp-roleblock-nebula .mp-row').length === 1, null, { timeout: 5000 });
  const panel = await page.evaluate(() => {
    const blk = document.querySelector('#model-panel-overlay .mp-roleblock-nebula');
    return {
      retired: ['mp-refresh', 'mp-manual-toggle', 'mp-manual', 'mp-clear'].map((c) => blk.querySelectorAll('.' + c).length),
      rows: blk.querySelectorAll('.mp-row').length,
      drags: blk.querySelectorAll('.mp-drag').length,
    };
  });
  ok(tag, '/model 面板同源面（逐字节同款）同样零退役控件',
    panel.retired.every((n) => n === 0) && panel.rows === 1 && panel.drags === 0, JSON.stringify(panel));

  const NOISE = /favicon|net::ERR|WebSocket|404|ResizeObserver loop/i;
  const fresh = errors.filter((e) => !NOISE.test(e));
  ok(tag, '零 console error / 零 pageerror', fresh.length === 0, fresh.slice(0, 4).join(' | '));
  await ctx.close();
}

async function modeFailureLeg(browser) {
  const { ctx, page, errors, sim } = await boot(browser, { viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  await openSettings(page, { mobile: false });
  sim.failMode = true;
  await page.locator('[data-rule-mode="slots"]').click();
  await page.waitForFunction(() => document.querySelector('#model-chain-mode-state')?.textContent.includes('保存失败'));
  ok('mode-failure', '失败后回滚跟随模式', await page.locator('[data-rule-mode="follow"]').getAttribute('aria-checked') === 'true');
  ok('mode-failure', '仅预期 500 错误', errors.every(e => /500/.test(e)));
  await ctx.close();
}

/* ─────────────── 主流程：双引擎 × 双主题 ─────────────── */
for (const engineName of ENGINES) {
  const browser = await (engineName === 'webkit' ? webkit : chromium).launch({ headless: process.env.HEADLESS !== '0' });
  try {
    for (const theme of ['light', 'dark']) {
      await leg(engineName, browser, { theme });
      await leg(engineName, browser, { theme, mobile: true });
    }
    // 英文腿（i18n 双语实拍；最窄 390 视口那一条才是「不折行」的真判据）
    await leg(engineName, browser, { theme: 'dark', locale: 'en' });
    await leg(engineName, browser, { theme: 'dark', locale: 'en', mobile: true });
    if (engineName === 'chromium') { await functionalLeg(browser); await modeFailureLeg(browser); }
  } finally {
    await browser.close();
  }
}

webServer.close();
console.log(`\nshots → ${SHOTS_DIR}`);
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures ? 1 : 0);
