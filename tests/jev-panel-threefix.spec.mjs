// jev-panel-threefix.spec.mjs — Jev 配置面三改守卫（provider-add-tabs 批 2026-11-06
// 判据换代：Jev 面已并入**统一服务商面板**——作者令逐字「我们添加的模型都会集中
// 显示在同一个面板里（即现在的 LLM 服务商 / LLM Provider 面板）」。旧判据 R1/R1b
// 钉的「一行下钻入口 + 浮层收编」随旧节整件退役；本文件改钉新面上的同批语义：
//
//   R1' 面板统一  → 服务商面板 = 类型标签（LLM / Image generation / Jev）+ 一张
//       列表 + 一个新增钮；Jev 行带类型徽标「Jev」；旧 jev-advance-* 结构零复活
//       （静态哨兵 scripts/check-provider-panel.mjs P1 同钉，这里是渲染面）。
//   R2' 开关解耦  → #toggle-provider-enabled 只作用法闸：翻档不改列表可见性、
//       关不清值；PUT /api/config/jev 携带 enabled。
//   R3/R3b 端点钉死 → typesafe-jev 预填 + 禁用 + title 说明；laya 可编辑、钉死值
//       不泄漏；保存 PUT 携带钉死端点（用户从未手填）。
//   R4 分配通道 legs 如实显示 → #settings-jev-health 锚存活（现居面板类型附加行，
//       Jev 标签选中时可见），三态 + 回退措辞 + legs title 零改动。
//
// SELF-CONTAINED：静态服务器 127.0.0.1:8191 + route mock API + routeWebSocket
// mock WS，零真实后端。
// Run: npx playwright test tests/jev-panel-threefix.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8191; // fixed, 8100+ per task discipline — never the host 8080
const PINNED = 'https://api.typesafe.ai/v1/systemone';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

const JEV_PROVIDERS = {
  'typesafe-jev': { type: 'typesafe-jev', endpoint: PINNED, model: 'jev-latest', keyRef: 'typesafe-jev-key', keyConfigured: true },
};

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', jev?: object|null,
 *           health?: object|null, healthFail?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', colorScheme = 'light',
    jev = { enabled: true, provider: 'typesafe-jev', providers: JEV_PROVIDERS },
    health = { status: 'unconfigured' }, healthFail = false,
  } = opts;
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.route('**/api/**', r => r.fulfill({ json: {} })); // catch-all first (lowest priority)
  await page.route('**/api/config/provider-types', r => r.fulfill({
    json: {
      domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }, { id: 'jev', enabledSwitch: true }],
      adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview'], jev: ['typesafe-jev', 'laya'] },
    },
  }));
  await page.route('**/api/config/jev', r => r.fulfill({ json: { ...jev, compiledIn: true } }));
  await page.route('**/api/config/imagegen', r => r.fulfill({ json: { compiledIn: true, enabled: true, providers: {} } }));
  await page.route('**/api/config/provider-keys', r => r.fulfill({ json: { providers: {} } }));
  await page.route('**/api/secrets', r => r.fulfill({ json: { secrets: [] } }));
  await page.route('**/api/decision/health', r => {
    if (healthFail) return r.fulfill({ status: 500, body: 'boom' });
    return r.fulfill({ json: { decision: health } });
  });

  const frames = [];
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, ws => {
    wsSend = obj => ws.send(JSON.stringify(obj));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ llm: { providers: {} } }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 15000 });
  return { frames, send: obj => wsSend(obj) };
}

/** Open the settings modal and wait for the provider panel to paint. */
async function openSettings(page) {
  await page.evaluate(async () => {
    (await import('/js/sidebar.js')).openSettingsPanel();
    document.getElementById('settings-overlay')?.classList.add('on');
  });
  await page.waitForSelector('#provider-panel-host .nf-provider-panel', { timeout: 10000 });
}

/** Select the Jev type tab and wait for the panel to repaint. */
async function selectJevTab(page) {
  await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
  await page.waitForSelector('.nf-provider-panel[data-type="jev"]', { timeout: 5000 });
}

/** Open the unified provider form for a given provider ('' = new). */
async function openJevForm(page, name = '') {
  await selectJevTab(page);
  if (name) {
    await page.evaluate((n) => {
      const card = [...document.querySelectorAll('#provider-list .cfg-card')].find(c => c.dataset.name === n || c.querySelector(`[data-provider="${n}"]`));
      card?.querySelector('.cfg-card-title')?.click();
    }, name);
  } else {
    await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
  }
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 5000 });
}

// ══ R1' · 面板统一（类型标签 + 一张列表 + 类型徽标）═══════════════════════
test("R1' unified panel: type tabs + one list; Jev rows carry the Jev badge; old drill entries are gone", async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  const dump = await page.evaluate(() => ({
    tabs: [...document.querySelectorAll('.nf-ptab')].map(b => b.textContent.trim()),
    lists: document.querySelectorAll('#provider-list').length,
    addBtns: document.querySelectorAll('#btn-add-provider').length,
    oldToggle: !!document.getElementById('jev-advance-toggle'),
    oldBody: !!document.getElementById('jev-advance-body'),
    overlays: document.querySelectorAll('.config-drill-overlay').length,
  }));
  expect(dump.tabs, 'type tabs = the three domains (后端投影词面)').toEqual(['LLM', 'Image generation', 'Jev']);
  expect(dump.lists, 'exactly ONE provider list').toBe(1);
  expect(dump.addBtns, 'exactly ONE add button').toBe(1);
  expect(dump.oldToggle, 'old jev drill entry row is gone').toBe(false);
  expect(dump.oldBody, 'old jev drill body is gone').toBe(false);
  expect(dump.overlays, 'no drill overlay machinery in the DOM').toBe(0);

  // Jev 标签 → 列表行带类型徽标 + 协议徽标。
  await selectJevTab(page);
  const jevRows = await page.evaluate(() => ({
    cards: [...document.querySelectorAll('#provider-list .cfg-card')].map(c => ({
      name: c.querySelector('.cfg-card-title')?.textContent.trim(),
      type: c.querySelector('.cfg-card-type')?.textContent.trim(),
      protocol: c.querySelector('.cfg-card-badge')?.textContent.trim(),
      key: c.querySelector('.st-badge')?.textContent.trim(),
    })),
    healthRow: !!document.getElementById('settings-jev-health'),
    toggle: !!document.getElementById('toggle-provider-enabled'),
  }));
  expect(jevRows.cards, 'Jev list = one card with Jev type badge + adapter badge + key state').toEqual([
    // 键位徽标词面 = jev-imagegen-align-llm 批定版（zh『已保存』/en『Configured』）。
    { name: 'typesafe-jev', type: 'Jev', protocol: 'typesafe-jev', key: '已保存' },
  ]);
  expect(jevRows.healthRow, 'allocation-channel health row lives in the panel (Jev tab)').toBe(true);
  expect(jevRows.toggle, 'usage-gate toggle lives in the panel (Jev tab)').toBe(true);
});

// ══ R2' · 开关解耦（只作用法闸）════════════════════════════════
test("R2' the panel toggle is only a usage gate — flipping never clears the list; PUT carries enabled", async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  const puts = [];
  await page.route('**/api/config/jev', r => {
    if (r.request().method() === 'PUT') { puts.push(r.request().postDataJSON()); return r.fulfill({ json: { updated: true } }); }
    return r.fulfill({ json: { enabled: true, provider: 'typesafe-jev', providers: JEV_PROVIDERS, compiledIn: true } });
  });
  await openSettings(page);
  await selectJevTab(page);
  await page.evaluate(() => document.getElementById('toggle-provider-enabled')?.click());
  await page.waitForTimeout(300);
  const afterOff = await page.evaluate(() => ({
    cards: document.querySelectorAll('#provider-list .cfg-card').length,
    toggleOn: document.getElementById('toggle-provider-enabled')?.classList.contains('on'),
  }));
  expect(afterOff.cards, 'list stays visible while the gate flips (no hiding)').toBeGreaterThan(0);
  expect(puts.some(b => b && b.enabled === false), 'PUT carries enabled=false').toBe(true);
  // 失败回弹：PUT 500 ⇒ 开关回位（不报假态）。
  await page.route('**/api/config/jev', r => r.fulfill({ status: 500, body: 'boom' }));
  await page.evaluate(() => document.getElementById('toggle-provider-enabled')?.click());
  await page.waitForTimeout(300);
  const rebound = await page.evaluate(() => document.getElementById('toggle-provider-enabled')?.classList.contains('on'));
  expect(rebound, 'failed PUT ⇒ toggle snaps back (no fake state)').toBe(true);
});

// ══ R3 · 端点钉死（typesafe-jev）═════════════════════════════
test("R3 typesafe-jev pins the endpoint (prefilled + disabled); laya stays editable; switching re-pins", async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await openJevForm(page, 'typesafe-jev');
  const pinned = await page.evaluate(() => {
    const input = document.querySelector('#cfg-modal [data-field="endpoint"]');
    return { value: input.value, disabled: input.disabled, title: input.title, cursor: getComputedStyle(input).cursor };
  });
  expect(pinned.value, '🔴 endpoint prefilled with the fixed Jev value').toBe(PINNED);
  expect(pinned.disabled, '🔴 endpoint is not editable (disabled 禁用态)').toBe(true);
  expect(pinned.title, 'disabled input explains why').toContain('端点固定');
  expect(pinned.cursor, 'disabled cursor language').toBe('not-allowed');

  // 切 laya → editable again; the pinned value never leaks into the laya face
  await page.selectOption('#cfg-modal [data-field="type"]', 'laya');
  const laya = await page.evaluate(() => {
    const input = document.querySelector('#cfg-modal [data-field="endpoint"]');
    return { value: input.value, disabled: input.disabled, ph: input.placeholder };
  });
  expect(laya.disabled, 'laya keeps a custom endpoint editable').toBe(false);
  expect(laya.value, 'pinned value does not leak into laya').toBe('');
  expect(laya.ph, 'laya placeholder names the self-host default').toContain('127.0.0.1:8080');

  // back to typesafe-jev → re-pinned
  await page.selectOption('#cfg-modal [data-field="type"]', 'typesafe-jev');
  const repin = await page.evaluate(() => {
    const input = document.querySelector('#cfg-modal [data-field="endpoint"]');
    return { value: input.value, disabled: input.disabled };
  });
  expect(repin.value, 'switching back re-pins the fixed value').toBe(PINNED);
  expect(repin.disabled, 'and re-disables the field').toBe(true);
});

// ══ R3b · 保存携带钉死端点 ═══════════════════════════════════
test("R3b save PUT carries provider + enabled + the pinned endpoint", async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  let putBody = null;
  await page.route('**/api/config/jev', r => {
    if (r.request().method() !== 'PUT') return r.fulfill({ json: { enabled: true, provider: 'typesafe-jev', providers: JEV_PROVIDERS, compiledIn: true } });
    putBody = r.request().postDataJSON();
    return r.fulfill({ json: { updated: true } });
  });
  await openSettings(page);
  await openJevForm(page, 'typesafe-jev');
  await page.evaluate(() => document.querySelector('#cfg-modal .cfg-btn-save')?.click());
  await page.waitForTimeout(400);
  expect(putBody, 'PUT body captured').not.toBeNull();
  expect(putBody.enabled, 'usage gate value travels').toBe(true);
  expect(putBody.provider, 'provider travels').toBe('typesafe-jev');
  const row = putBody.providers && putBody.providers['typesafe-jev'];
  expect(row, 'provider row travels').toBeTruthy();
  expect(row.endpoint, '🔴 the pinned endpoint travels — the user never had to type it').toBe(PINNED);
  expect(row.keyRef, 'existing keyRef is preserved (never regenerated)').toBe('typesafe-jev-key');
});

// ══ R4 · 健康回退如实显示（legs 载荷；徽标现居面板类型附加行）══════
test("R4 jev leg down + channel up (llm took over) → warn badge「Jev 不可用 → 已回退 llm」+ jev reason", async ({ page }) => {
  await bootPage(page, {
    locale: 'zh-CN',
    health: {
      status: 'up',
      legs: {
        jev: { status: 'down', reason: 'jev leg: decision API timeout: boom', since: 1728470400000 },
        llm: { status: 'up' },
      },
    },
  });
  await openSettings(page);
  await selectJevTab(page);
  await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return {
      tone: b.dataset.tone,
      text: b.textContent.trim(),
      reason: b.querySelector('.badge-reason')?.textContent || '',
      title: b.title,
      warnIcon: !!b.querySelector('svg.st-warn'),
      inPanel: !!b.closest('.nf-provider-panel'), // 统一面板：类型附加行
    };
  });
  expect(dump.inPanel, 'badge lives in the unified provider panel (Jev tab)').toBe(true);
  expect(dump.tone, '🔴 fallback state = warn tone').toBe('warn');
  expect(dump.warnIcon, 'warn icon rides st-warn').toBe(true);
  expect(dump.text, '🔴 the fallback wording is the truthful story').toContain('Jev 不可用 → 已回退 llm');
  expect(dump.reason, 'jev leg reason rides the truncated tail').toContain('jev leg: decision API timeout: boom');
  expect(dump.title, 'title carries the full reason').toBe('jev leg: decision API timeout: boom');
});

test("R4b fallback then llm leg fails too → err「异常」with both legs in the title", async ({ page }) => {
  await bootPage(page, {
    locale: 'zh-CN',
    health: {
      status: 'down',
      reason: 'llm leg: decision API timeout: llm-slow',
      legs: {
        jev: { status: 'down', reason: 'jev leg: decision API auth failed: 401', since: 1728470400000 },
        llm: { status: 'down', reason: 'llm leg: decision API timeout: llm-slow', since: 1728470400001 },
      },
    },
  });
  await openSettings(page);
  await selectJevTab(page);
  await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return { tone: b.dataset.tone, text: b.textContent.trim(), title: b.title };
  });
  expect(dump.tone, 'both legs down = err').toBe('err');
  expect(dump.text, 'word = 异常').toContain('异常');
  expect(dump.title, 'title names BOTH legs').toContain('jev leg: decision API auth failed: 401');
  expect(dump.title).toContain('llm leg: decision API timeout: llm-slow');
});

test("R4c legacy payload without legs → the tri-state display does not regress", async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', health: { status: 'down', reason: 'decision API server error: 502' } });
  await openSettings(page);
  await selectJevTab(page);
  await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return { tone: b.dataset.tone, text: b.textContent.trim(), reason: b.querySelector('.badge-reason')?.textContent || '' };
  });
  expect(dump.tone, 'legacy down → err').toBe('err');
  expect(dump.text, 'legacy word = 异常').toContain('异常');
  expect(dump.reason, 'legacy reason tail intact').toContain('decision API server error: 502');
});
