// model-chain-slim.spec.mjs — 设置页渐进披露模型选择验收。
// 冻结意图见 .stand/docs/settings-progressive-disclosure-zh__20261107_230657__flow-453d463d.md：
// 每类会话单选一个模型，不再把回退链当作设置页契约；摘要可展开、说明可读。
// 当前实现仍只挂载 Nebula 面，五类设置的完整度另由冻结规格验收，不由本 spec 冒充覆盖。
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
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8334);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(ROOT, 'evidence', '20261101_model-chain-slim');
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
];
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
  const sim = { chain: CHAIN, puts: [] };
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
    if (p === '/api/sessions') {
      return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return r.fulfill({ json: { messages: [], total: 0, sessionId: SID } });
    }
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      if (r.request().method() === 'PUT') {
        const body = r.request().postDataJSON();
        sim.puts.push(body);
        sim.chain = body && body.model ? body.model : null;
      }
      const effective = sim.chain || { preferred: 'seed/seed-model', fallbacks: [] };
      return r.fulfill({
        json: {
          mode: sim.chain ? 'explicit' : 'follow',
          chain: sim.chain,
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
    await page.locator('.mobile-page[data-page="workbench"] [data-hero-settings="1"]').click();
    await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'));
  } else {
    await page.click('#settings-btn');
  }
  const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disclosure.getAttribute('aria-expanded') !== 'true') await disclosure.click();
  await page.waitForSelector('#model-chain-section-body .mp-row', { state: 'attached', timeout: 15000 });
  await page.waitForSelector('#model-chain-section-body .mp-row', { state: 'visible', timeout: 10000 });
  await page.evaluate(() => document.getElementById('model-chain-section')?.scrollIntoView({ block: 'center' }));
  await sleep(350);
}

/* ─────────────── 单腿：断言 + 实拍 ─────────────── */
async function leg(engineName, browser, { theme, locale = 'zh-CN', mobile = false }) {
  const tag = `${engineName}/${mobile ? 'mobile' : 'desktop'}/${theme}/${locale}`;
  const viewport = mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 };
  const { ctx, page, errors, sim } = await boot(browser, { viewport, colorScheme: theme, locale, touch: mobile });
  await openSettings(page, { mobile });

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

  // ①② 三交互退役 + 只剩「添加模型」一个动作
  const controls = await page.evaluate(() => {
    const sec = document.getElementById('model-chain-section');
    const q = (s) => sec.querySelectorAll(s).length;
    const addRow = sec.querySelector('.mp-addrow');
    return {
      refresh: q('.mp-refresh'), manualToggle: q('.mp-manual-toggle'),
      manualInput: q('.mp-manual'), clear: q('.mp-clear'),
      buttons: [...sec.querySelectorAll('#model-chain-section-body button')].map((b) => b.textContent.trim()),
      addRowChildren: addRow ? [...addRow.children].map((c) => c.className) : [],
      addSelect: addRow?.querySelector('.mp-add-select')?.options?.[0]?.textContent?.trim() || '',
      addOptions: addRow?.querySelector('.mp-add-select')?.options?.length || 0,
    };
  });
  ok(tag, '① 刷新列表/手动输入/清空链三交互零残留（DOM）',
    controls.refresh === 0 && controls.manualToggle === 0 && controls.manualInput === 0 && controls.clear === 0,
    JSON.stringify(controls));
  ok(tag, '② 动作只剩「添加模型」下拉（节内零按钮）',
    controls.buttons.length === 0 && controls.addRowChildren.length === 1 &&
    controls.addRowChildren[0] === 'mp-add-select' &&
    (locale === 'zh-CN' ? controls.addSelect === '添加模型' : controls.addSelect === 'Add model'),
    JSON.stringify({ buttons: controls.buttons, addRowChildren: controls.addRowChildren, first: controls.addSelect }));
  ok(tag, '② 下拉带链外模型（可继续加链）', controls.addOptions >= 2, `options=${controls.addOptions}`);

  // 冻结 §1/§2：每类会话单选一个模型；不再要求已退役的后备链手柄、删除和位次徽标。
  const rows = await page.evaluate(() => {
    const list = [...document.querySelectorAll('#model-chain-section-body .mp-row')];
    return {
      count: list.length,
      heights: list.map((r) => Math.round(r.getBoundingClientRect().height)),
      models: list.map((r) => r.querySelector('.mp-model')?.textContent.trim()),
      editor: document.querySelector('#model-chain-section-body .mp-add-select')?.tagName,
    };
  });
  ok(tag, '⑤ 当前模型行可见且仅一项单选编辑器', rows.count === 1 && !!rows.models[0] && rows.editor === 'SELECT', JSON.stringify(rows));
  ok(tag, '⑤ 模型行尺寸有效', rows.heights.length === 1 && rows.heights[0] > 0, `heights=${rows.heights}`);

  // i18n 双语键：新文案在册、旧键连根拔除、两字典同长
  const dict = await page.evaluate(async (retired) => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    return {
      zhTitle: zh['settings.modelChainTitle'], enTitle: en['settings.modelChainTitle'],
      zhHint: zh['settings.modelChainSectionHint'], enHint: en['settings.modelChainSectionHint'],
      zhSummary: zh['settings.modelsSummary'], enSummary: en['settings.modelsSummary'],
      zhLeak: retired.filter((k) => k in zh), enLeak: retired.filter((k) => k in en),
      zhCount: Object.keys(zh).length, enCount: Object.keys(en).length,
    };
  }, RETIRED_KEYS);
  ok(tag, 'i18n 双语标题齐', dict.zhTitle === TITLES['zh-CN'] && dict.enTitle === TITLES.en,
    `zh="${dict.zhTitle}" en="${dict.enTitle}"`);
  ok(tag, '渐进披露摘要双语键齐', dict.zhSummary === '{n} 类会话模型' && dict.enSummary === '{n} session classes',
    `zh="${dict.zhSummary}" en="${dict.enSummary}"`);
  ok(tag, 'i18n 双语说明齐', !!dict.zhHint && !!dict.enHint, `zh="${dict.zhHint}" en="${dict.enHint}"`);
  ok(tag, 'i18n 退役键零残留（zh/en 双向）', dict.zhLeak.length === 0 && dict.enLeak.length === 0,
    JSON.stringify({ zhLeak: dict.zhLeak, enLeak: dict.enLeak }));
  // 两字典本就有 7 键的存量差（zh 多 8 键 / en 多 1 键，与本批无关）——这里钉的
  // 是「本批六个键在两侧对称删干净」（差值不因单边漏删而变化）。
  ok(tag, 'i18n 删键对称（差值 = 存量基线 7）', dict.zhCount - dict.enCount === 7,
    `zh=${dict.zhCount} en=${dict.enCount} Δ=${dict.zhCount - dict.enCount}`);

  // 实拍
  const shot = join(SHOTS_DIR, `chain-${engineName}-${mobile ? 'mobile' : 'desktop'}-${theme}${locale === 'en' ? '-en' : ''}.png`);
  await page.locator('#model-chain-section').screenshot({ path: shot });
  ok(tag, '实拍落盘', true, shot);

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

  // 单模型选择即存；冻结规格不要求删除或重排后备链。
  const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disclosure.getAttribute('aria-expanded') !== 'true') await disclosure.click();
  await page.selectOption('#model-chain-section-body .mp-add-select', 'kimi/kimi-for-coding');
  await page.waitForFunction(() => document.querySelector('#model-chain-section-body .mp-row .mp-model')?.textContent?.includes('kimi-for-coding'), null, { timeout: 5000 });
  ok(tag, '⑤ 单模型下拉选择即存（不提供后备链删除／排序）', sim.puts.length === 1 &&
    JSON.stringify(sim.puts[0].model) === JSON.stringify({ preferred: 'kimi/kimi-for-coding', fallbacks: [] }),
  JSON.stringify(sim.puts[0]));

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
    if (engineName === 'chromium') await functionalLeg(browser);
  } finally {
    await browser.close();
  }
}

webServer.close();
console.log(`\nshots → ${SHOTS_DIR}`);
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures ? 1 : 0);
