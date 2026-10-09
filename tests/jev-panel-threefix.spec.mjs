// jev-panel-threefix.spec.mjs — Jev（插件分配）设置面板三改验收 spec。
//
// 用户 2026-10-09 23:11 截图反馈三点（原话）：
//   ①「是应该是点击展开才显示细节，否则就显示一个toggle就行了。」
//   ②「是如果是Jev版，这个端点应该帮我写好，不需要我填，因为这个是固定的，
//      我填密钥就行了。同时Jev如果不可用，应该自动fallback到llm模式」
//   ③「分配通道状态行如实反映（如「Jev 不可用 → 已回退 llm」）；两腿状态分开
//      如实显示，回退后 llm 腿再挂仍是异常。」
//
// R1 收起态一行 — 卡片收起 = 标题「Jev（插件分配）」+ 开关（+chevron）一行；
//    默认收起（enabled=true 也不展开）；点击行体（标题区，非开关）原位展开
//    细节（提供方/端点/密钥/保存/分配通道状态），再点收起；键盘可开合。
// R2 开关解耦 — toggle 只是用法闸（usage gate），点击不改变细节展开态。
// R3 端点钉死 — provider=typesafe-jev：端点预填固定值
//    https://api.typesafe.ai/v1/systemone（DecisionProvider.DefaultTypesafeEndpoint
//    的前端单点）、disabled 禁用态、不可编辑；切到 laya 恢复可编辑（清掉钉死
//    值/空值），切回重新钉死；保存 PUT 携带钉死端点。
// R4 健康回退如实显示 — legs 载荷：jev 腿 down + 通道 up（llm 接管）→ warn 徽标
//    「Jev 不可用 → 已回退 llm」+ jev 腿 reason 尾巴（title 全文）；两腿全挂 →
//    err「异常」+ 两腿 reason；无 legs 的旧载荷 → 既有三态显示不回归。
//
// 截图（亮/暗双主题，零新色值核验）→ /tmp/nb-jev-threefix/shots/
// SELF-CONTAINED（secrets-ui-redesign.spec.mjs 同款 harness）：静态服务器
// 127.0.0.1:8191 + route mock API + routeWebSocket mock WS，零真实后端。
// Run: npx playwright test tests/jev-panel-threefix.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8191; // fixed, 8100+ per task discipline — never the host 8080
const SHOTS = '/tmp/nb-jev-threefix/shots';
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

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', jev?: object|null,
 *           health?: object|null, healthFail?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', colorScheme = 'light',
    jev = { enabled: true, provider: 'typesafe-jev', endpoint: PINNED, keyRef: 'typesafe-api-key' },
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
      config: JSON.stringify({ jev }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return { frames, send: obj => wsSend(obj) };
}

/** Open the settings modal and wait for render. */
async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}

// ══ R1 · 收起态一行 ══════════════════════════════════════════
test('R1 collapsed by default (even enabled): one head row only — title + toggle + chevron', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' }); // jev.enabled=true in the mock
  await openSettings(page);
  const dump = await page.evaluate(() => {
    const card = document.getElementById('jev-card');
    const head = card?.querySelector('.cfg-card-head');
    const body = document.getElementById('jev-detail-body');
    const headBtn = head?.querySelector('.cfg-card-head-btn');
    return {
      cardH: card?.getBoundingClientRect().height || 0,
      headH: head?.getBoundingClientRect().height || 0,
      bodyVisible: !!body && body.style.display !== 'none',
      title: headBtn?.querySelector('.cfg-card-title')?.textContent.trim() || '',
      toggleInHead: !!head?.querySelector('#toggle-jev'),
      ariaExpanded: headBtn?.getAttribute('aria-expanded'),
      controls: headBtn?.getAttribute('aria-controls'),
      healthHidden: (() => { const r = card?.querySelector('.jev-health-row'); if (!r) return false; const el = r.closest('#jev-detail-body') || r; return el.style.display === 'none' || !r.offsetParent; })(),
    };
  });
  expect(dump.title, 'head row carries the Jev title').toBe('Jev（插件分配）');
  expect(dump.toggleInHead, 'the toggle rides the head row').toBe(true);
  expect(dump.bodyVisible, '🔴 detail body collapsed by default even when enabled').toBe(false);
  expect(dump.ariaExpanded, 'head button declares collapsed').toBe('false');
  expect(dump.controls, 'head button controls the detail body').toBe('jev-detail-body');
  expect(dump.healthHidden, 'the health row lives inside the collapsed detail').toBe(true);
  expect(dump.cardH, `collapsed card is ONE row (card ${dump.cardH}px ≈ head ${dump.headH}px)`).toBeLessThan(60);
});

// ══ R1b · 点行体展开/收起（非开关）+ 键盘 ═════════════════════
test('R1b clicking the head row expands/collapses in place; keyboard operable', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  const open = await page.evaluate(() => ({
    aria: document.getElementById('jev-head').getAttribute('aria-expanded'),
    chev: getComputedStyle(document.querySelector('#jev-head .cfg-head-chev svg')).transform,
    rows: [...document.querySelectorAll('#jev-detail-body .cfg-row')].map(r => r.querySelector('.cfg-row-label')?.textContent.trim()),
  }));
  expect(open.aria, 'expanded → aria-expanded=true').toBe('true');
  expect(open.chev, 'chevron rotates when expanded').not.toBe('none');
  expect(open.rows, 'expanded detail = 提供方 + 端点 + 密钥引用 + 分配通道')
    .toEqual(['提供方', '端点', 'API 密钥引用', '分配通道']);

  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'none');
  expect(await page.evaluate(() => document.getElementById('jev-head').getAttribute('aria-expanded')), 're-click collapses').toBe('false');

  // Keyboard: focus + Enter toggles (real <button> semantics).
  await page.focus('#jev-head');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'none');
});

// ══ R2 · 开关解耦（toggle ≠ 展开）════════════════════════════
test('R2 the toggle is only a usage gate — flipping it never changes the detail body', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  // collapsed → toggle flip keeps collapsed
  await page.click('#toggle-jev');
  expect(await page.evaluate(() => document.getElementById('jev-detail-body').style.display), 'flip ON while collapsed stays collapsed').toBe('none');
  // expand → toggle flip keeps expanded
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  await page.click('#toggle-jev');
  await page.waitForTimeout(80);
  expect(await page.evaluate(() => document.getElementById('jev-detail-body').style.display), 'flip OFF while expanded stays expanded').toBe('block');
  // collapse ≠ clear: the configured values survive expand/collapse
  await page.fill('#cfg-jev-keyref', 'my-key-name');
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'none');
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  expect(await page.inputValue('#cfg-jev-keyref'), 'collapse/expand keeps the input value').toBe('my-key-name');
});

// ══ R3 · 端点钉死（typesafe-jev）═════════════════════════════
test('R3 typesafe-jev pins the endpoint (prefilled + disabled); laya stays editable; switching re-pins', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');

  const pinned = await page.evaluate(() => {
    const input = document.getElementById('cfg-jev-endpoint');
    return { value: input.value, disabled: input.disabled, title: input.title, cursor: getComputedStyle(input).cursor };
  });
  expect(pinned.value, '🔴 endpoint prefilled with the fixed Jev value').toBe(PINNED);
  expect(pinned.disabled, '🔴 endpoint is not editable (disabled 禁用态)').toBe(true);
  expect(pinned.title, 'disabled input explains why').toContain('端点固定');
  expect(pinned.cursor, 'disabled cursor language').toBe('not-allowed');

  // laya → editable again; the pinned value never leaks into the laya face
  await page.selectOption('#cfg-jev-provider', 'laya');
  const laya = await page.evaluate(() => {
    const input = document.getElementById('cfg-jev-endpoint');
    return { value: input.value, disabled: input.disabled, ph: input.placeholder };
  });
  expect(laya.disabled, 'laya keeps a custom endpoint editable').toBe(false);
  expect(laya.value, 'pinned value does not leak into laya').toBe('');
  expect(laya.ph, 'laya placeholder names the self-host default').toContain('127.0.0.1:8080');

  // back to typesafe-jev → re-pinned
  await page.selectOption('#cfg-jev-provider', 'typesafe-jev');
  const repin = await page.evaluate(() => {
    const input = document.getElementById('cfg-jev-endpoint');
    return { value: input.value, disabled: input.disabled };
  });
  expect(repin.value, 'switching back re-pins the fixed value').toBe(PINNED);
  expect(repin.disabled, 'and re-disables the field').toBe(true);
});

// ══ R3b · 保存携带钉死端点 ═══════════════════════════════════
test('R3b save PUT carries provider + enabled + the pinned endpoint', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  let putBody = null;
  await page.route('**/api/config/jev', r => {
    if (r.request().method() !== 'PUT') return r.fulfill({ json: {} });
    putBody = r.request().postDataJSON();
    return r.fulfill({ json: { updated: true } });
  });
  await openSettings(page);
  await page.click('#jev-head'); // the save action lives in the expanded detail
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  await page.click('#btn-save-jev');
  await page.waitForFunction(() => (document.getElementById('jev-save-state')?.textContent || '').length > 0);
  expect(putBody, 'PUT body captured').not.toBeNull();
  expect(putBody.enabled, 'usage gate value travels').toBe(true);
  expect(putBody.provider, 'provider travels').toBe('typesafe-jev');
  expect(putBody.endpoint, '🔴 the pinned endpoint travels — the user never had to type it').toBe(PINNED);
});

// ══ R4 · 健康回退如实显示（legs 载荷）════════════════════════
test('R4 jev leg down + channel up (llm took over) → warn badge「Jev 不可用 → 已回退 llm」+ jev reason', async ({ page }) => {
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
  await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return {
      tone: b.dataset.tone,
      text: b.textContent.trim(),
      reason: b.querySelector('.badge-reason')?.textContent || '',
      title: b.title,
      warnIcon: !!b.querySelector('svg.st-warn'),
    };
  });
  expect(dump.tone, '🔴 fallback state = warn tone').toBe('warn');
  expect(dump.warnIcon, 'warn icon rides st-warn').toBe(true);
  expect(dump.text, '🔴 the fallback wording is the truthful story').toContain('Jev 不可用 → 已回退 llm');
  expect(dump.reason, 'jev leg reason rides the truncated tail').toContain('jev leg: decision API timeout: boom');
  expect(dump.title, 'title carries the full reason').toBe('jev leg: decision API timeout: boom');
});

test('R4b fallback then llm leg fails too → err「异常」with both legs in the title', async ({ page }) => {
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

test('R4c legacy payload without legs → the tri-state display does not regress', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', health: { status: 'down', reason: 'decision API server error: 502' } });
  await openSettings(page);
  await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return { tone: b.dataset.tone, text: b.textContent.trim(), reason: b.querySelector('.badge-reason')?.textContent || '' };
  });
  expect(dump.tone, 'legacy down → err').toBe('err');
  expect(dump.text, 'legacy word = 异常').toContain('异常');
  expect(dump.reason, 'legacy reason tail intact').toContain('decision API server error: 502');
});

// ══ 双主题截图（收起一行 / 展开细节，暗亮两态零新色值）════════
async function shots(colorScheme, tag) {
  test(`shots[${tag}] collapsed one-row card / expanded detail`, async ({ page }) => {
    await bootPage(page, {
      locale: 'zh-CN', colorScheme,
      health: {
        status: 'up',
        legs: {
          jev: { status: 'down', reason: 'jev leg: decision API timeout: boom', since: 1728470400000 },
          llm: { status: 'up' },
        },
      },
    });
    await openSettings(page);
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').length > 0);
    await page.locator('#jev-card').screenshot({ path: `${SHOTS}/jev-card-collapsed-${tag}.png` });
    await page.click('#jev-head');
    await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
    await page.locator('#jev-card').screenshot({ path: `${SHOTS}/jev-card-expanded-${tag}.png` });
  });
}
await shots('light', 'light');
await shots('dark', 'dark');
