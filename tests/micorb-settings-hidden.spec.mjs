// micorb-settings-hidden.spec.mjs — settings-page gating spec for the micOrb
// appearance section (author ruling 2026-09-03: the orb is preset-driven, the
// settings UI entry is hidden; code and config-reading logic stay intact).
//
// Verifies (read-only w.r.t. every other spec):
//   H1 DOM 断言 — the REAL /js/sidebar.js renderSettings() (full shell +
//      mocked WS) contains no settings.appearance section card and no
//      orb-* config element of any kind, in zh-CN AND en. Adjacent sections
//      (runtime/providers) still render — the whole card is gated off, no
//      empty "外观" shell left behind.
//   H2 代码保留证据 — importing the REAL /js/orbSettingsUI.js and calling
//      renderAppearanceSection() still returns the full markup (same source
//      the micorb-presets T4 harness mounts): hiding ≠ deleting.
//   H3 自定义配置仍生效 — without touching any UI, orbPresets.saveSaved()
//      with a custom selection drives the shell's LIVE orb through
//      CHANGE_EVENT → loadSaved → renderer boards (hidden entry ≠ dropped
//      config; existing user selections keep working).
//
// voicefix-testsync（chain-voicefix，2026-09-21）· **纯测试同步、行为零改**：
//   voicefix 批落地（commit `aaaaa7765`）按作者 2026-09-21 14:0x ③「把最近的有光球的
//   回退回来」复原了主输入区 orb 挂载（micOrb.js 字节级回退 + index.html 四件 +
//   input.css 光球段）⇒ 输入区光球 = **现行终态**，visup-b 修正④ 的「退役面」判据整体
//   翻转。本文件 H1/H2（设置页外观区维持关闭 / 模块保留）**原样不动**；只有 H3 的
//   退役面读数改为**新终态对位**（零删除零跳过；判据逐条对位、强度不减）。
//
// Self-contained: static server on 127.0.0.1:8177 (never 8080) serving
// src/main/resources/web only; WS/API mocked in-page; server closed at end.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8177; // fixed, 8100+ per task discipline — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

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

test.beforeEach(async ({ page }) => {
  // Dark scheme (factory boards are dark-first) + token/locale init.
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Mock backend: API returns {} and the app WS gets the minimal handshake —
  // same shape as tests/tmp-usage-display-verify.mjs (no real server touched).
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
});

/** Load the real shell, then render settings via the REAL sidebar.js module. */
async function renderRealSettings(page, locale) {
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.evaluate(async () => {
    const sb = await import('/js/sidebar.js'); // production module, real path
    sb.renderSettings();
  });
}

// Every orb-config element class/id the appearance section can produce
// (from orbSettingsUI.js: base select, 9-state map rows, custom board editor,
// live preview, reset button).
const ORB_SELECTORS = [
  '#orb-base-select',
  '#orb-appearance-body',
  '#orb-appearance-reset',
  '#orb-custom-toggle',
  '#orb-custom-body',
  '#orb-custom-source',
  '#orb-custom-slots',
  '#orb-preview-slot',
  '.orb-state-select',
  '.orb-state-row',
  '.orb-state-map',
  '.orb-state-map-title',
  '.orb-slot-row',
  '.orb-slot-label',
  '.orb-slot-color',
  '.orb-theme-btn',
  '.orb-theme-seg',
  '.orb-swatches',
];

const APPEARANCE_TITLE = { 'zh-CN': '外观', en: 'Appearance' };

test('H1: settings DOM has no orb config entry (zh-CN + en), no empty shell card', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await renderRealSettings(page, locale);
    const dump = await page.evaluate((sels) => {
      const content = document.getElementById('settings-content');
      return {
        leaked: sels.filter((s) => content.querySelector(s)),
        titles: [...content.querySelectorAll('.settings-section-title')].map((e) => e.textContent.trim()),
        sections: content.querySelectorAll('.settings-section').length,
        htmlLen: content.innerHTML.length,
      };
    }, ORB_SELECTORS);

    expect(dump.leaked, `[${locale}] orb config elements leaked into settings DOM`).toEqual([]);
    expect(dump.titles, `[${locale}] appearance section card must be gone entirely`).not.toContain(APPEARANCE_TITLE[locale]);
    // Gating, not amputation: the neighboring sections still render.
    expect(dump.sections, `[${locale}] settings page must keep its other sections`).toBeGreaterThan(3);
    for (const tt of dump.titles) expect(tt.length, `[${locale}] section titles must be non-empty`).toBeGreaterThan(0);
  }
});

test('H2: orbSettingsUI module intact — renderAppearanceSection still emits full markup', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const markup = await page.evaluate(async () => {
    const ui = await import('/js/orbSettingsUI.js'); // same source the T4 harness mounts
    return ui.renderAppearanceSection();
  });
  expect(markup.length, 'module must still render non-empty markup').toBeGreaterThan(500);
  for (const token of ['id="orb-base-select"', 'orb-state-select', 'id="orb-appearance-reset"', 'id="orb-preview-slot"']) {
    expect(markup, `markup must still contain ${token}`).toContain(token);
  }
});

/* ---- H3 helpers: read the live orb's renderer targets ------------------- */

const hexToRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
async function targetBoard(page) {
  const pal = await page.evaluate(async () => {
    const micOrb = await import('/js/micOrb.js');
    const r = micOrb.getMicOrb().renderer;
    return { a: r.target.pal.a.slice(), b: r.target.pal.b.slice(), c: r.target.pal.c.slice() };
  });
  for (const slot of ['a', 'b', 'c']) {
    pal[slot] = pal[slot].map((v) => Math.round(v * 255));
  }
  return pal;
}
function expectSlot(slot, rgb, hex, ctx) {
  const want = hexToRgb(hex).map((v) => Math.round(v * 255));
  expect(rgb, `${ctx} slot ${slot} → ${hex}`).toEqual(want);
}

/** 状态就绪闸（voicefix-testsync 补 · 纯测试侧、期望值零动）：
 *  本文件以下的槽位断言对位对象 = **基调板**（`idle` 解析到基调），而 `offline` 是
 *  一条**合法**的更高优先态（`micOrb.js` `resolveState`：offline 优先于 idle；板 =
 *  `ash`）。offline 由 `micOrb.js` 的 1s 轮询 `pollDerived()` 推出：
 *  `offline = state.connected === false`，而 `state.connected` 默认 false
 *  （`state.js:60`）、只在 `ws.js` 的 `onopen` 置 true ⇒ mock 环境的 WS 开面**晚于首个
 *  轮询 tick** 时光球会合法落 offline 板，断言读到 `#B9C2D2`(=ash a) 而非基调板。
 *  实测（同树连跑）：H3 1 过 2 红（本闸前）。本 helper 只等**被测状态到位**，
 *  🔴 不改任何期望值、不删不跳任何断言（强度零减）；真·offline 不恢复时本闸超时
 *  报「等 idle 超时」而非伪装成颜色不符。 */
async function waitForOrbIdle(page) {
  // 🔴 谓词必须**同步**：`page.waitForFunction` 不 await 异步谓词的 Promise
  // （返回值序列化后恒为真值 ⇒ 立刻通过 = 空闸；实测 6ms 返回而 state 仍 `offline`）。
  // 同步谓词读承载类 = 与产品同一读数面（`apply()` 逐态移除后加当前态类）。
  await page.waitForFunction(() => {
    const wrap = document.getElementById('mic-orb-wrap');
    return !!wrap && wrap.classList.contains('s-idle');
  }, null, { timeout: 15000 });
}

/** voicefix 批（作者 2026-09-21 14:0x ③「把最近的有光球的回退回来」）已把 visup-b
 *  修正④ 的挂载退役**回退**：主输入区 orb 复活 ⇒ 产品面自身就是 live orb，H3 直接
 *  驱动**产品面单例**。
 *  🔴 原「探测宿主」（`mountOrbProbe`）随退役面翻转而**失去必要且有害**：再挂一个同 id
 *  `#mic-canvas` 会与产品面真实挂载点重复（`getMicOrb` 挂载判据按 id 取画布）⇒ 本批
 *  移除该 helper，改为**正向对位**断言产品面单挂载点（宿主/画布/css-orb 各恰一件 +
 *  单例绑定该画布）。`webglOk`/槽位三值/状态映射等原判据**逐条保留**（强度不减）。 */

test('H3: hidden entry ≠ dropped config — saveSaved custom selection drives the live orb', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  // ── ① 新终态（voicefix 回退）：产品面**单一**光球挂载点在位（旧反向判据逐条正向对位，
  //      判据数只增不减） ──────────────────────────────────────────────────────
  const mounted = await page.evaluate(async () => {
    const micOrb = await import('/js/micOrb.js');
    const o = micOrb.getMicOrb();
    const btn = document.getElementById('voice-btn');
    const canvas = document.getElementById('mic-canvas');
    return {
      composerWrap: !!document.querySelector('#input-bar .mic-orb-wrap'),
      composerCanvas: !!document.querySelector('#input-bar #mic-canvas'),
      voiceBtnOrbChild: !!document.querySelector('#voice-btn .orb-canvas'),
      orbWrapNodes: document.querySelectorAll('.mic-orb-wrap').length,
      orbBubbleNodes: document.querySelectorAll('.micbubble').length,
      orbCanvasNodes: document.querySelectorAll('.orb-canvas').length,
      orbCssNodes: document.querySelectorAll('.css-orb').length,
      canvasIdNodes: document.querySelectorAll('#mic-canvas').length,
      singleton: !!o,
      singletonBound: !!o && !!btn && !!canvas && o.btn === btn && o.canvas === canvas,
      voiceBtnCls: btn ? btn.className : null,
    };
  });
  expect(mounted.composerWrap, 'composer orb wrap mounted (voicefix revert)').toBe(true);
  expect(mounted.composerCanvas, 'composer orb canvas mounted (voicefix revert)').toBe(true);
  expect(mounted.voiceBtnOrbChild, 'voice button carries the orb canvas').toBe(true);
  // 「单一挂载点」= 旧「零节点」判据的正向对位：不得有游离/重复件（挂载判据按 id 取
  // 画布，重复 `#mic-canvas` 会让单例绑错目标）。
  expect(mounted.orbWrapNodes, 'exactly one orb wrap').toBe(1);
  expect(mounted.orbBubbleNodes, 'exactly one micbubble').toBe(1);
  expect(mounted.orbCanvasNodes, 'exactly one orb canvas').toBe(1);
  expect(mounted.orbCssNodes, 'exactly one css-orb').toBe(1);
  expect(mounted.canvasIdNodes, 'exactly one #mic-canvas').toBe(1);
  expect(mounted.singleton, 'MicOrb singleton is live (mount gate satisfied)').toBe(true);
  expect(mounted.singletonBound, 'MicOrb singleton bound to the PRODUCT button + canvas').toBe(true);
  expect(mounted.voiceBtnCls, 'mic control = the orb button (.micbubble)').toContain('micbubble');
  // ── ② 产品面**同一生产** MicOrb 单例（WebGL must be up, same requirement
  //       as micorb-presets T1 in this environment） ─────────────────────────
  const webglOk = await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().webglOk);
  expect(webglOk, 'headless Chromium must provide WebGL (SwiftShader)').toBe(true);

  // Preset a custom selection WITHOUT any UI (what a returning user's
  // localStorage would hold): emerald base, custom dark slot a, listening→sunset.
  await page.evaluate(async () => {
    const presets = await import('/js/orbPresets.js');
    presets.saveSaved({
      base: 'emerald',
      map: { listening: 'sunset' },
      custom: { base: 'emerald', dark: { a: '#40E0FF' }, light: {} },
    });
  });
  // CHANGE_EVENT → loadSaved → board re-resolve (target is set synchronously).
  // 状态就绪闸：本组断言的对位对象是基调板 ⇒ 先等光球落 `idle`（见 helper 注释；
  // 期望值逐条不动）。
  await waitForOrbIdle(page);
  let board = await targetBoard(page);
  expectSlot('a', board.a, '#40E0FF', 'idle w/ custom dark override');
  expectSlot('b', board.b, '#B8E05A', 'idle w/ emerald base');
  expectSlot('c', board.c, '#0A5230', 'idle w/ emerald base');

  // Mapped states keep the preset language (custom overrides the base only).
  await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().apply('listening'));
  board = await targetBoard(page);
  expectSlot('a', board.a, '#FFA26B', 'listening → sunset map');
  expectSlot('b', board.b, '#F060A0', 'listening → sunset map');
  expectSlot('c', board.c, '#7A2050', 'listening → sunset map');
});
