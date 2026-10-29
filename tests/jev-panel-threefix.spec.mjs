// jev-panel-threefix.spec.mjs — Jev（插件分配）设置面板三改验收 spec。
//
// 用户 2026-10-09 23:11 截图反馈三点（原话）：
//   ①「是应该是点击展开才显示细节，否则就显示一个toggle就行了。」
//   ②「是如果是Jev版，这个端点应该帮我写好，不需要我填，因为这个是固定的，
//      我填密钥就行了。同时Jev如果不可用，应该自动fallback到llm模式」
//   ③「分配通道状态行如实反映（如「Jev 不可用 → 已回退 llm」）；两腿状态分开
//      如实显示，回退后 llm 腿再挂仍是异常。」
//
// ── 存量红清欠批（2026-10-29）锚换代 ─────────────────────────────
// 本 spec 断言的旧卡结构锚（#jev-card / #jev-head / #jev-detail-body /
// .cfg-card-head / .cfg-card-head-btn / .cfg-row 横排行 / .jev-health-row /
// .cfg-head-chev）已被 2026-10-28 settings-panel 批 C 件重排整体删除
// （作者令：「JeV 插件分配那部分的风格，与『STT 高级设置』区的风格保持一致」；
// 旧锚在现树源码零命中，main 上本 spec 即全红——spec 停在过去，非产品回归）。
// 新结构（sidebar.js:917-971，与 STT 高级设置逐项同源）：
//   · 头行   #jev-advance-toggle（.settings-collapse-toggle，标题+
//            .settings-collapse-chevron）+ 右侧状态位 #jev-status-hint
//            （内含 #settings-jev-health 分配通道徽标——自旧折叠体内
//            .jev-health-row 迁入头行，收起态也如实可见）；
//   · 折叠体 #jev-advance-body（hidden 属性开合，session-memory 态
//            jevAdvanceExpanded，默认收起）；
//   · 开关行 #toggle-jev 随重排迁入折叠体首行（旧卡头行内开关退役；
//            收起态开关不可达 = 设计态，非漂移）；
//   · 字段组 .cfg-form-group 纵排（#cfg-jev-provider / #cfg-jev-endpoint /
//            #cfg-jev-keyref / #cfg-jev-key-value 明文直显行 + #jev-key-eye）；
//   · 保存行 #btn-save-jev + #btn-test-jev + #jev-save-state。
// 语义映射（三修意图逐条保留，锚换新）：
//   R1 一行收起 → 头行 + hidden 折叠体 + aria-expanded/aria-controls + 徽标头行在场；
//   R1b 行体开合 + 键盘 → #jev-advance-toggle（真 <button>，Enter 原生可开合）；
//   R2 开关解耦 → 折叠体展开态下翻档不改变折叠体可见性 + 收展不清值；
//   R3/R3b 端点钉死 / 保存携带 → 锚 id 原样存活，断言零改动；
//   R4 分配通道 legs 如实显示 → #settings-jev-health 锚存活，位迁头行。
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

/** Expand/collapse the JeV detail body through the head toggle (the C-batch
 *  restructure's single entry point — STT-advance-toggle 同款). */
async function setJevExpanded(page, wantOpen) {
  const expanded = await page.evaluate(() => document.getElementById('jev-advance-toggle')?.getAttribute('aria-expanded') === 'true');
  if (expanded !== wantOpen) await page.click('#jev-advance-toggle');
  await page.waitForFunction((want) => {
    const body = document.getElementById('jev-advance-body');
    return body && body.hidden === !want;
  }, wantOpen, { timeout: 5000 });
}

/** The JeV settings section (the C-batch shell has no id — locate by its
 *  head toggle, the section's stable resident). */
function jevSection(page) {
  return page.locator('.settings-section').filter({ has: page.locator('#jev-advance-toggle') });
}

// ══ R1 · 收起态一行 ══════════════════════════════════════════
test('R1 collapsed by default (even enabled): one head row only — title + status slot, body hidden', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' }); // jev.enabled=true in the mock
  await openSettings(page);
  const dump = await page.evaluate(() => {
    const head = document.getElementById('jev-advance-toggle');
    const body = document.getElementById('jev-advance-body');
    const section = head?.closest('.settings-section');
    const statusHint = document.getElementById('jev-status-hint');
    const badge = document.getElementById('settings-jev-health');
    return {
      title: head?.querySelector('.settings-label')?.textContent.trim() || '',
      bodyHidden: !!body && body.hidden === true,
      ariaExpanded: head?.getAttribute('aria-expanded'),
      controls: head?.getAttribute('aria-controls'),
      chev: head ? getComputedStyle(head.querySelector('.settings-collapse-chevron')).transform : null,
      toggleInBody: !!document.querySelector('#jev-advance-body #toggle-jev'), // C-batch: gate moved INTO the body
      statusInHead: !!statusHint && statusHint.closest('.stt-advance-toggle-row') === head?.closest('.stt-advance-toggle-row'),
      badgeVisible: !!badge && !!badge.offsetParent,
      sectionH: section?.getBoundingClientRect().height || 0,
    };
  });
  expect(dump.title, 'head row carries the Jev title').toBe('Jev（插件分配）');
  expect(dump.bodyHidden, '🔴 detail body collapsed by default even when enabled (hidden 属性)').toBe(true);
  expect(dump.ariaExpanded, 'head toggle declares collapsed').toBe('false');
  expect(dump.controls, 'head toggle controls the detail body').toBe('jev-advance-body');
  expect(dump.chev, 'chevron unrotated while collapsed').toBe('none');
  expect(dump.toggleInBody, 'the usage gate rides the collapsed body first row (C-batch 迁入位)').toBe(true);
  expect(dump.statusInHead, 'allocation-channel status slot lives in the HEAD row').toBe(true);
  expect(dump.badgeVisible, '🔴 health badge visible even collapsed — 状态行如实反映不再藏进细节').toBe(true);
  expect(dump.sectionH, `collapsed section is ONE row (${dump.sectionH}px < 60)`).toBeLessThan(60);
});

// ══ R1b · 点头行展开/收起 + 键盘 ══════════════════════════════
test('R1b clicking the head toggle expands/collapses in place; keyboard operable', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.click('#jev-advance-toggle');
  await page.waitForFunction(() => document.getElementById('jev-advance-body')?.hidden === false);
  const open = await page.evaluate(() => ({
    aria: document.getElementById('jev-advance-toggle').getAttribute('aria-expanded'),
    chev: getComputedStyle(document.querySelector('#jev-advance-toggle .settings-collapse-chevron')).transform,
    toggleRow: document.querySelector('#jev-advance-body .settings-row .settings-label')?.textContent.trim(),
    labels: [...document.querySelectorAll('#jev-advance-body .cfg-form-group .cfg-label')].map(l => l.textContent.trim()),
  }));
  expect(open.aria, 'expanded → aria-expanded=true').toBe('true');
  expect(open.chev, 'chevron rotates when expanded (rotate 180°)').toContain('matrix');
  expect(open.toggleRow, 'first body row = the usage-gate toggle row').toBe('用 Jev 分配插件');
  expect(open.labels, 'expanded detail = 提供方 + 端点 + 密钥引用 + key 值（分配通道升入头行状态位）')
    .toEqual(['提供方', '端点', 'API 密钥引用', 'key 值']);

  await page.click('#jev-advance-toggle');
  await page.waitForFunction(() => document.getElementById('jev-advance-body')?.hidden === true);
  expect(await page.evaluate(() => document.getElementById('jev-advance-toggle').getAttribute('aria-expanded')), 're-click collapses').toBe('false');

  // Keyboard: focus + Enter toggles (real <button> semantics).
  await page.focus('#jev-advance-toggle');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.getElementById('jev-advance-body')?.hidden === false);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.getElementById('jev-advance-body')?.hidden === true);
});

// ══ R2 · 开关解耦（toggle ≠ 展开）════════════════════════════
// C-batch 后 gate 住在折叠体内——收起态不可达是设计态；解耦语义改在展开态钉：
// 翻档（on/off 两个方向）都不改变折叠体可见性。
test('R2 the toggle is only a usage gate — flipping it never changes the detail body', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await setJevExpanded(page, true);
  // flip OFF then ON — body stays expanded throughout
  await page.click('#toggle-jev');
  await page.waitForTimeout(80);
  expect(await page.evaluate(() => document.getElementById('jev-advance-body').hidden), 'flip OFF while expanded stays expanded').toBe(false);
  await page.click('#toggle-jev');
  await page.waitForTimeout(80);
  expect(await page.evaluate(() => document.getElementById('jev-advance-body').hidden), 'flip ON again — still expanded').toBe(false);
  // collapse ≠ clear: the configured values survive expand/collapse
  await page.fill('#cfg-jev-keyref', 'my-key-name');
  await setJevExpanded(page, false);
  await setJevExpanded(page, true);
  expect(await page.inputValue('#cfg-jev-keyref'), 'collapse/expand keeps the input value').toBe('my-key-name');
});

// ══ R3 · 端点钉死（typesafe-jev）═════════════════════════════
test('R3 typesafe-jev pins the endpoint (prefilled + disabled); laya stays editable; switching re-pins', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await setJevExpanded(page, true);

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
  await setJevExpanded(page, true); // the save action lives in the expanded detail
  await page.click('#btn-save-jev');
  await page.waitForFunction(() => (document.getElementById('jev-save-state')?.textContent || '').length > 0);
  expect(putBody, 'PUT body captured').not.toBeNull();
  expect(putBody.enabled, 'usage gate value travels').toBe(true);
  expect(putBody.provider, 'provider travels').toBe('typesafe-jev');
  expect(putBody.endpoint, '🔴 the pinned endpoint travels — the user never had to type it').toBe(PINNED);
});

// ══ R4 · 健康回退如实显示（legs 载荷；徽标现居头行状态位）══════
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
      inHeadRow: !!b.closest('.stt-advance-toggle-row'), // C-batch: head status slot
    };
  });
  expect(dump.inHeadRow, 'badge lives in the head status slot (visible collapsed)').toBe(true);
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
  test(`shots[${tag}] collapsed one-row section / expanded detail`, async ({ page }) => {
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
    await jevSection(page).screenshot({ path: `${SHOTS}/jev-card-collapsed-${tag}.png` });
    await setJevExpanded(page, true);
    await jevSection(page).screenshot({ path: `${SHOTS}/jev-card-expanded-${tag}.png` });
  });
}
await shots('light', 'light');
await shots('dark', 'dark');
