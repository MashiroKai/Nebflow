// secrets-query-chips.spec.mjs — 可查询批（作者令 2026-11-18 21:31）面板腿的闸。
//
// 「secret 要加上说明和关键词 tag，方便被查询使用，同时据此对 secret 面板布局和
// 显示进行优化」——本件把这句话变成可执行的判据：
//
//   P1 哨兵零泄漏（面板腿）：mock 的 `/api/secrets` 响应里**故意**塞一个
//      `value: '<哨兵>'` 键（值文件内容），面板渲染后整个 DOM 的 textContent
//      与全部可见属性里都不得出现该哨兵。这条断言与 CLI/REST 两腿（
//      SecretsQueryGateSpec / SecretsQueryRoutesGateSpec）同源同哨兵。
//   P2 行面重排：名称 + 最后更新 + 说明 + 标签 chips + 路径 + 消费方齐全，
//      行内无值面（收起态无 [data-edit]）。
//   P3 chips 可点击筛选：点一个 chip ⇒ 卡片集收窄为带该标签的行，计数副值随之
//      变化；再点一次复原。
//   P4 搜索框：按说明/标签子串筛选；无命中 ⇒ 明示「没有匹配」而不是空表。
//   P5 展开区可编辑说明与标签（只写元数据面）：字段预填；改后回车 ⇒ 一条 PATCH
//      全量替换体（description + tags 同时在场），**没有**任何 PUT 值请求。
//   P6 新建草稿带说明+标签 ⇒ POST 体同时携带 value / description / tags。
//   P7 手机全屏形态（375×812）：body.mobile-layer 下 secrets 面板铺满屏（不是
//      弹窗/内嵌面板），chips 仍可点，哨兵仍零泄漏。
//
// 截图（本批视觉证据）→ /tmp/nb-secrets-query/shots/：亮/暗/手机 + 动效 GIF 的
// 逐帧源（见 tools/gif 步骤，报告里出 ffprobe 实测读数）。
//
// SELF-CONTAINED（secrets-ui-redesign.spec.mjs 同款 harness）：隔离静态服务器
// 127.0.0.1:8188 + route mock API + routeWebSocket mock WS，零真实后端、零端口冲突。
//
// Run: npx playwright test tests/secrets-query-chips.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8188;
const SHOTS = '/tmp/nb-secrets-query/shots';
const MIME = {
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.html': 'text/html',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
};

/** 🔴 The value-file content. It exists ONLY in the mock payload's `value`
 *  key — nothing on screen may ever contain it. */
const SENTINEL = 'SENTINEL-DO-NOT-LEAK-8f3a41';
const VALUE = `${SENTINEL}-panel`;

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
test.beforeAll(async () => { server = await startServer(); await mkdir(SHOTS, { recursive: true }); });
test.afterAll(async () => { server.close(); });

const ROWS = [
  {
    name: 'aliyun-oss', size: 42, mtime: 1763000000000, mode: '0600',
    refs: ['social:df-msg'], variableName: 'ALIYUN_OSS',
    description: 'OSS access key for the docs bucket', tags: ['cloud', 'deploy', 'oss'],
    path: '~/.stand/secrets/aliyun-oss', value: VALUE,
  },
  {
    name: 'vercel-token', size: 18, mtime: 1763100000000, mode: '0600',
    refs: [], variableName: 'VERCEL_TOKEN',
    description: 'Vercel deploy token', tags: ['deploy'],
    path: '~/.stand/secrets/vercel-token', value: VALUE,
  },
  {
    name: 'plain-unlabelled', size: 7, mtime: 1762900000000, mode: '0600',
    refs: [], variableName: 'PLAIN_UNLABELLED',
    description: '', tags: [],
    path: '~/.stand/secrets/plain-unlabelled', value: VALUE,
  },
];

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', rows?: object[], record?: object[]}} opts
 */
async function bootPage(page, opts = {}) {
  const { locale = 'zh-CN', colorScheme = 'light', rows = ROWS, record = [] } = opts;
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/secrets', (r) => r.fulfill({ json: { secrets: rows } }));
  // `**/api/secrets/**` (not `*`) — the single-secret arms are two segments
  // deep (`/api/secrets/<name>/value`), and a one-level glob would let the
  // value readback fall through to the catch-all (which answers `{}`, i.e. an
  // empty value — the exact way a "no leak" test can pass for the wrong
  // reason). Later-registered routes win in Playwright.
  await page.route('**/api/secrets/**', (r) => {
    const req = r.request();
    const method = req.method();
    let body = null;
    try { body = JSON.parse(req.postData() || 'null'); } catch { body = null; }
    record.push({ method, url: req.url(), body });
    if (method === 'GET') return r.fulfill({ json: { name: 'x', value: VALUE } });
    return r.fulfill({ json: { ok: true, name: 'x' } });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ llm: { providers: {} } }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
  await page.goto(base + '/index.html');
  // Ready when EITHER shell has settled: the desktop chrome (activity bar
  // visible) or the mobile layer (the app switches on a width threshold this
  // spec does not re-declare). Anchoring on only one of them makes a test
  // depend on the viewport in a way that reads as a product failure.
  await page.waitForFunction(
    () =>
      document.body.classList.contains('mobile-layer') ||
      (document.getElementById('activity-bar') !== null &&
        document.getElementById('activity-bar').offsetParent !== null),
    null,
    { timeout: 15000 }
  );
}

/** Open the secrets panel through its REAL module entry point (the same
 *  function main.js wires to #btn-open-secrets). */
async function openPanel(page) {
  await page.evaluate(async () => {
    const m = await import('/js/secretsPanel.js');
    m.openSecretsPanel();
  });
  await page.waitForSelector('#secrets-overlay.on .secrets-row', { timeout: 8000 });
}

/** Every string the DOM can publish: text, plus the attributes a user can
 *  see or that a screen reader reads out. */
async function domSurface(page) {
  return page.evaluate(() => {
    const attrs = ['title', 'placeholder', 'aria-label', 'value', 'alt'];
    let out = document.body.textContent || '';
    for (const el of document.querySelectorAll('*')) {
      for (const a of attrs) {
        const v = el.getAttribute(a);
        if (v) out += `\u0000${v}`;
      }
    }
    return out;
  });
}

// ══ P1 · 哨兵零泄漏（面板腿）═══════════════════════════════════════════
test('P1 a wire `value` key never reaches any render (sentinel absent from the whole DOM)', async ({ page }) => {
  await bootPage(page);
  await openPanel(page);
  // Expand every row (the value face only exists inside the detail).
  for (const name of ['aliyun-oss', 'vercel-token']) {
    await page.click(`[data-row-head="${name}"]`);
    await page.waitForSelector(`.secrets-row[data-name="${name}"] .secrets-row-detail`, { timeout: 5000 });
    await page.click(`[data-row-head="${name}"]`); // collapse again
  }
  // Also reveal: the eye is the ONE readback call; the mock answers with the
  // sentinel, and the value's single legitimate appearance is that field.
  await page.click('[data-row-head="aliyun-oss"]');
  await page.waitForSelector('.secrets-row[data-name="aliyun-oss"] [data-edit]', { timeout: 5000 });
  const surfaceBeforeReveal = await domSurface(page);
  await page.click('.secrets-row[data-name="aliyun-oss"] [data-eye]');
  await page.waitForTimeout(300);
  const fieldValue = await page.evaluate(() => {
    const el = document.querySelector('.secrets-row[data-name="aliyun-oss"] [data-edit]');
    return el instanceof HTMLTextAreaElement ? el.value : null;
  });
  // The revealed field holds the mock's value — that is the ONE intended face.
  expect(fieldValue, 'the eye reveal is the single, explicit value face').toBe(VALUE);
  // Everything else — list, chips, metadata, path, consumers, the collapsed
  // rows — is sentinel-free before the reveal happened.
  expect(surfaceBeforeReveal.includes(SENTINEL), '🔴 no panel surface may carry a stored value').toBe(false);
  // Re-mask burns it back to the placeholder; the surface is sentinel-free again.
  await page.click('.secrets-row[data-name="aliyun-oss"] [data-eye]');
  await page.click('[data-row-head="aliyun-oss"]');
  const after = await domSurface(page);
  expect(after.includes(SENTINEL), 'after re-mask nothing retains the value').toBe(false);
  // Shots (light + dark) for the batch's visual evidence.
  await shoot(page, 'desktop-light');
});

// ══ P2 · 行面重排 ══════════════════════════════════════════════════════
test('P2 resting rows carry name / updated / description / tag chips / path / consumers — and no value face', async ({ page }) => {
  await bootPage(page);
  await openPanel(page);
  const dump = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="aliyun-oss"]');
    return {
      name: row?.querySelector('.secrets-row-name')?.textContent.trim(),
      meta: row?.querySelector('.secrets-row-head .secrets-row-meta')?.textContent.trim() || '',
      desc: row?.querySelector('.secrets-row-desc')?.textContent.trim(),
      chips: [...row.querySelectorAll('.secrets-row-sub .secrets-chip')].map((c) => c.textContent.trim()),
      facts: row?.querySelector('.secrets-row-facts')?.textContent.trim() || '',
      noFieldAtRest: !row?.querySelector('[data-edit]'),
      noActionsAtRest: !row?.querySelector('.secrets-row-actions'),
      count: document.getElementById('secrets-count')?.textContent.trim(),
    };
  });
  expect(dump.name).toBe('aliyun-oss');
  expect(dump.meta, 'row keeps the size + the updated stamp').toMatch(/B/);
  expect(dump.desc, '说明 rides the row').toBe('OSS access key for the docs bucket');
  expect(dump.chips, '标签 chips, one per tag').toEqual(['#cloud', '#deploy', '#oss']);
  expect(dump.facts, '路径 + 消费方 ride the sub lane').toContain('~/.stand/secrets/aliyun-oss');
  expect(dump.facts).toContain('social:df-msg');
  expect(dump.noFieldAtRest, '🔴 收起行无字段').toBe(true);
  expect(dump.noActionsAtRest, '🔴 收起行无按钮').toBe(true);
  expect(dump.count).toBe('共 3 个密钥');
  // A row with no metadata renders neither lane (no empty shells).
  const bare = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="plain-unlabelled"]');
    return {
      descHidden: row.querySelector('.secrets-row-desc')?.hasAttribute('hidden') ?? null,
      chips: row.querySelectorAll('.secrets-row-sub .secrets-chip').length,
    };
  });
  expect(bare.descHidden, 'an empty description leaves no placeholder line').toBe(true);
  expect(bare.chips, 'an unlabelled secret renders no chips').toBe(0);
});

// ══ P3 · chips 可点击筛选 ═══════════════════════════════════════════════
test('P3 a tag chip filters the list (AND) and the count follows', async ({ page }) => {
  await bootPage(page);
  await openPanel(page);
  const names = () => page.evaluate(() => [...document.querySelectorAll('#secrets-list .secrets-row')].map((r) => r.dataset.name));
  expect((await names()).length).toBe(3);
  await page.click('.secrets-row[data-name="aliyun-oss"] .secrets-chip[data-tag="cloud"]');
  expect(await names()).toEqual(['aliyun-oss']);
  expect(await page.evaluate(() => document.getElementById('secrets-count')?.textContent.trim())).toBe('筛出 1 / 3 个密钥');
  // Adding a second tag narrows further (intersection, not union).
  await page.click('#secrets-tagbar .secrets-chip[data-tag="deploy"]');
  expect(await names()).toEqual(['aliyun-oss']);
  // Clicking the selected chip again removes it.
  await page.click('#secrets-tagbar .secrets-chip[data-tag="cloud"]');
  expect(await names()).toEqual(['aliyun-oss', 'vercel-token']);
  // The clear chip restores everything.
  await page.click('#secrets-tagbar .secrets-chip-clear');
  expect((await names()).length).toBe(3);
  expect(await page.evaluate(() => document.getElementById('secrets-count')?.textContent.trim())).toBe('共 3 个密钥');
  await shoot(page, 'desktop-filtered');
});

// ══ P4 · 搜索框 ═══════════════════════════════════════════════════════
test('P4 the search box filters by description/tag/consumer and shows a no-match state', async ({ page }) => {
  await bootPage(page);
  await openPanel(page);
  const names = () => page.evaluate(() => [...document.querySelectorAll('#secrets-list .secrets-row')].map((r) => r.dataset.name));
  await page.fill('#secrets-search-input', 'docs bucket');
  expect(await names()).toEqual(['aliyun-oss']);
  await page.fill('#secrets-search-input', 'df-msg');
  expect(await names()).toEqual(['aliyun-oss']);
  await page.fill('#secrets-search-input', 'zzz-no-hit');
  expect(await names()).toEqual([]);
  expect(await page.evaluate(() => document.querySelector('#secrets-list .cfg-empty')?.textContent.trim()))
    .toContain('没有匹配的密钥');
  await page.fill('#secrets-search-input', '');
  expect((await names()).length).toBe(3);
});

// ══ P5 · 说明 / 标签可编辑（只写元数据面）═════════════════════════════
test('P5 editing description+tags commits ONE whole-replace PATCH and never a value PUT', async ({ page }) => {
  const record = [];
  await bootPage(page, { record });
  await openPanel(page);
  await page.click('[data-row-head="vercel-token"]');
  await page.waitForSelector('.secrets-row[data-name="vercel-token"] [data-edit-desc]', { timeout: 5000 });
  const prefilled = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="vercel-token"]');
    return {
      desc: row.querySelector('[data-edit-desc]')?.value,
      tags: row.querySelector('[data-edit-tags]')?.value,
    };
  });
  expect(prefilled.desc, 'the description field opens pre-filled').toBe('Vercel deploy token');
  expect(prefilled.tags, 'the tag field opens pre-filled').toBe('deploy');
  await page.fill('.secrets-row[data-name="vercel-token"] [data-edit-desc]', 'Vercel deploy token (docs)');
  await page.fill('.secrets-row[data-name="vercel-token"] [data-edit-tags]', 'Deploy, docs, ci');
  await page.press('.secrets-row[data-name="vercel-token"] [data-edit-tags]', 'Enter');
  await page.waitForTimeout(400);
  const writes = record.filter((r) => r.method !== 'GET');
  expect(writes.length, 'exactly one write request').toBe(1);
  expect(writes[0].method, 'the metadata face is PATCH (never a value PUT)').toBe('PATCH');
  expect(writes[0].url).toContain('/api/secrets/vercel-token');
  expect(writes[0].body, 'whole-replace body carries BOTH catalogue fields').toEqual({
    description: 'Vercel deploy token (docs)',
    tags: ['deploy', 'docs', 'ci'],
  });
  expect(JSON.stringify(writes[0].body).includes(SENTINEL), 'the metadata body never carries a value').toBe(false);
});

// ══ P6 · 新建时可带说明与标签 ══════════════════════════════════════════
test('P6 the create draft posts value+description+tags in ONE request', async ({ page }) => {
  const record = [];
  await bootPage(page, { record });
  await openPanel(page);
  await page.click('[data-add]');
  await page.waitForSelector('[data-draft-name]', { timeout: 5000 });
  await page.fill('[data-draft-name]', 'fresh-key');
  await page.fill('[data-draft-value]', 'v1');
  await page.fill('[data-draft-desc]', 'Created from the panel');
  await page.fill('[data-draft-tags]', 'New, Cloud');
  await page.click('[data-draft-save]');
  await page.waitForTimeout(400);
  const posts = record.filter((r) => r.method === 'POST');
  expect(posts.length, 'one POST').toBe(1);
  expect(posts[0].body).toEqual({ value: 'v1', description: 'Created from the panel', tags: ['new', 'cloud'] });
});

// ══ P7 · 手机全屏形态（375×812）════════════════════════════════════════
test('P7 mobile 375×812: the panel is a FULL-SCREEN sheet (not a popup), chips still filter, sentinel absent', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await bootPage(page, { colorScheme: 'dark' });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
  await openPanel(page);
  const geom = await page.evaluate(() => {
    const m = document.getElementById('secrets-modal');
    const r = m.getBoundingClientRect();
    return {
      w: Math.round(r.width), h: Math.round(r.height),
      vw: window.innerWidth, vh: window.innerHeight,
      mobile: document.body.classList.contains('mobile-layer'),
    };
  });
  expect(geom.mobile, 'the mobile layer is active at 375px').toBe(true);
  expect(geom.w, 'full-screen width').toBe(geom.vw);
  expect(geom.h, 'full-screen height').toBe(geom.vh);
  // The chips are usable at this width.
  await page.click('.secrets-row[data-name="aliyun-oss"] .secrets-chip[data-tag="cloud"]');
  const names = await page.evaluate(() => [...document.querySelectorAll('#secrets-list .secrets-row')].map((r) => r.dataset.name));
  expect(names).toEqual(['aliyun-oss']);
  const surface = await domSurface(page);
  expect(surface.includes(SENTINEL), '🔴 mobile face is sentinel-free too').toBe(false);
  await shoot(page, 'mobile-dark');
});

// ══ P8 · 视觉证据：暗/亮两档 + 手机亮档 ═══════════════════════════════
test('P8 shots: light/dark desktop + light mobile', async ({ page }) => {
  await bootPage(page, { colorScheme: 'light' });
  await openPanel(page);
  await shoot(page, 'desktop-light');
  await page.close();

  const dark = await page.context().newPage();
  await bootPage(dark, { colorScheme: 'dark' });
  await openPanel(dark);
  await shoot(dark, 'desktop-dark');
  await dark.click('.secrets-row[data-name="aliyun-oss"] [data-row-head]');
  await dark.waitForSelector('.secrets-row[data-name="aliyun-oss"] .secrets-row-detail', { timeout: 5000 });
  await shoot(dark, 'desktop-dark-expanded');
  await dark.close();

  const lightMobile = await page.context().newPage();
  await lightMobile.setViewportSize({ width: 375, height: 812 });
  await bootPage(lightMobile, { colorScheme: 'light' });
  await openPanel(lightMobile);
  await shoot(lightMobile, 'mobile-light');
  await lightMobile.close();
});

// ══ P9 · 动效帧序列（合成 GIF 的源帧）═════════════════════════════════
test('P9 motion frames: filtering is the animation (chip toggle + live search)', async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 620 });
  await bootPage(page, { colorScheme: 'light' });
  await openPanel(page);
  const dir = join(SHOTS, '..', 'gif-frames');
  await mkdir(dir, { recursive: true });
  let n = 0;
  const frame = async () => {
    await page.screenshot({ path: join(dir, `${String(++n).padStart(3, '0')}.png`) });
  };
  await frame();
  await page.click('.secrets-row[data-name="aliyun-oss"] .secrets-chip[data-tag="cloud"]');
  await frame();
  await page.click('#secrets-tagbar .secrets-chip[data-tag="deploy"]');
  await frame();
  await page.click('#secrets-tagbar .secrets-chip-clear');
  await frame();
  for (const ch of 'docs') {
    await page.type('#secrets-search-input', ch, { delay: 60 });
    await frame();
  }
  await page.click('#secrets-tagbar .secrets-chip-clear');
  await frame();
  expect(n).toBe(9);
});

// ── shot helper ────────────────────────────────────────────────────────
async function shoot(page, tag) {
  await mkdir(SHOTS, { recursive: true });
  const v = page.viewportSize();
  const path = join(SHOTS, `${tag}.png`);
  await page.screenshot({ path, fullPage: false });
  await writeFile(join(SHOTS, `${tag}.viewport.txt`), `${v.width}x${v.height}\n`);
}
