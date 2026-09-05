// plugins-panel-autosync.spec.mjs — 2026-09-05 插件面板体验批验收 spec
//（移除手动刷新 + 列表实时自动同步 + 启停原地状态切换 + nb-toggle 公共组件）。
//
// Shell 级：隔离静态服务器（127.0.0.1:8181，8100+ 纪律，绝非宿主 8080）+
// 页内 mock，真实 UI 代码全量执行。断言链：
//   ① 手动刷新按钮不复存在（DOM 零残留）
//   ② mock 注入新插件 → 轮询周期内列表自动插入新卡（带 plugins-card-entering
//      插入动画，MutationObserver 同帧捕获），全程无手动刷新、无 page reload
//   ③ 开关启停：乐观原地翻转 → 注册表收敛，卡片/列表/兄弟卡 DOM 节点身份
//      不变（零全列表重绘）、无 page reload；POST 失败回滚乐观态 + toast
//   ④ nb-toggle 组件契约：role=switch / aria-checked / 键盘 Space+Enter /
//      setToggleState / attrs 钩子 / 插件面板开关同 class / CSS 全 token 零手造色
//
// Self-contained: static server on 127.0.0.1:8181; WS/API mocked in-page;
// server closed at end.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8181; // fixed, 8100+ — never the host 8080/8091/8092 (8179 = sidebar-plugins)
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

// ── Mock backend (shapes mirror RestApiRoutes.scala / PluginRegistry.scala) ──
const MOCK_AGENTS = {
  agents: [
    { name: 'Nebula', displayName: 'Nebula', description: 'orchestrator', category: 'standalone', layer: 'global' },
  ],
};
const MOCK_MODEL = { preferred: 'gpt/test-model', current: 'gpt/test-model', preset: 'preset-one' };

const TRUSTED = { status: 'trusted', approvedAt: 1757000000, digest: 'a'.repeat(72) };
const UNTRUSTED = { status: 'untrusted', reason: 'never approved (default-deny)' };

/** Mutable registry — tests mutate it to simulate backend-side plugin
 *  appearances (live-sync path). Reset per test in beforeEach. */
const registry = { plugins: [], rejected: [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }] };
/** POST /approve fault injection (rollback path). */
let failApprove = false;

function manifest(name) {
  return {
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} (autosync spec)`,
    author: 'autosync-spec',
    digest: 'a'.repeat(72),
    fileCount: 1,
    trust: { ...UNTRUSTED },
    skills: [{ id: `${name}/s`, description: 'Skill', preview: 'body' }],
    mcpServers: [],
    toolsExtension: [],
    warnings: [],
  };
}

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(() => {
  registry.plugins = [manifest('e2e-hello'), manifest('m-mid')]; // both off (untrusted)
  registry.rejected = [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }];
  failApprove = false;
});

/** Shell load with the full API/WS mock set. */
async function loadShell(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Route matching is LIFO — catch-all FIRST, specifics after (they win).
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/plugins/*/approve', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    if (failApprove) return r.fulfill({ status: 500, json: { error: 'boom — injected failure' } });
    const name = r.request().url().match(/plugins\/([^/]+)\/approve/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, trust: { ...TRUSTED } } : p);
    r.fulfill({ json: { ok: true, message: 'approved' } });
  });
  await page.route('**/api/plugins/*/revoke', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    const name = r.request().url().match(/plugins\/([^/]+)\/revoke/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, trust: { ...UNTRUSTED } } : p);
    r.fulfill({ json: { ok: true, message: 'revoked' } });
  });
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: registry.plugins, rejected: registry.rejected } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: MOCK_MODEL }));
  await page.route('**/api/agents', r => r.fulfill({ json: MOCK_AGENTS }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  // Boot fallback auto-opens the plugins page.
  await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });
}

// ══ ① 手动刷新按钮移除 ═══════════════════════════════════════════════════

test('① no manual refresh button anywhere in the rendered panel', async ({ page }) => {
  await loadShell(page);
  const dump = await page.evaluate(() => ({
    refreshById: !!document.getElementById('plugins-refresh'),
    refreshByClass: document.querySelectorAll('.plugins-refresh-btn').length,
    titlePresent: !!document.querySelector('#plugins-content .plugins-title'),
    cards: document.querySelectorAll('#plugins-content .plugins-card').length,
  }));
  expect(dump.refreshById, '#plugins-refresh must not exist').toBe(false);
  expect(dump.refreshByClass, '.plugins-refresh-btn must not exist').toBe(0);
  expect(dump.titlePresent, 'panel title still renders').toBe(true);
  expect(dump.cards, 'plugin cards still render (2 trusted-list + 1 rejected)').toBe(3);
});

// ══ ② 列表实时自动同步（轮询注入新插件）══════════════════════════════════

test('② new plugin appears via polling with enter animation — no manual refresh, no reload', async ({ page }) => {
  await loadShell(page);

  // Observe list mutations; capture whether the entering class is present at
  // insertion time (same synchronous block → observer fires before the 0.25s
  // animation ends). Also plant a reload detector on the window.
  await page.evaluate(() => {
    window.__reloadMarker = 42;
    window.__insertedCards = [];
    const list = document.querySelector('#plugins-section-plugins .plugins-card-list');
    new MutationObserver(muts => {
      for (const m of muts) for (const n of m.addedNodes) {
        if (n.nodeType === 1 && n.classList?.contains('plugins-card')) {
          window.__insertedCards.push({ name: n.dataset.plugin, entering: n.classList.contains('plugins-card-entering') });
        }
      }
    }).observe(list, { childList: true });
  });

  // Backend-side: a new plugin ships (mock mutated — nothing touched in-page,
  // no navigation, no manual refresh; only the poller can discover it).
  registry.plugins.push(manifest('z-late')); // sorts after e2e-hello / m-mid

  // The poller (6s interval) must insert the card within ~2 intervals.
  await page.waitForFunction(() =>
    !!document.querySelector('.plugins-card[data-plugin="z-late"]'), { timeout: 15000 });

  const dump = await page.evaluate(() => {
    const card = document.querySelector('.plugins-card[data-plugin="z-late"]');
    return {
      reloadMarker: window.__reloadMarker,
      inserted: window.__insertedCards,
      prevName: card.previousElementSibling?.dataset?.plugin || null,
      nextIsRejected: card.nextElementSibling?.classList.contains('rejected') || false,
      switchThere: !!card.querySelector('[data-plugin-switch]'),
      cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
    };
  });
  expect(dump.reloadMarker, 'no page reload during live insert').toBe(42);
  expect(dump.inserted, 'exactly the new plugin card was recorded as inserted, with the entering animation').toEqual([
    { name: 'z-late', entering: true },
  ]);
  expect(dump.prevName, 'inserted at sorted position (after m-mid)').toBe('m-mid');
  expect(dump.nextIsRejected, 'trusted block stays before the rejected card').toBe(true);
  expect(dump.switchThere, 'new card carries the enable switch').toBe(true);
  expect(dump.cardCount, 'card count grew 3 → 4').toBe(4);
});

// ══ ③ 启停原地状态切换（乐观翻转 + 收敛 + 回滚）══════════════════════════

test('③ toggle: optimistic in-place flip → registry convergence; zero list redraw, zero reload', async ({ page }) => {
  await loadShell(page);

  // Expand the skill preview first — it must survive the whole toggle path.
  await page.click('.plugins-card[data-plugin="e2e-hello"] .plugins-comp-toggle');
  await page.waitForFunction(() => !document.querySelector('[data-expand-for="skills-e2e-hello"]')?.hidden);

  // Identity markers: card node, list container node, sibling card node.
  await page.evaluate(() => {
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    card.__identityTag = 'card-identity';
    card.closest('.plugins-card-list').__identityTag = 'list-identity';
    document.querySelector('.plugins-card[data-plugin="m-mid"]').__identityTag = 'sibling-identity';
    window.__reloadMarker = 7;
  });

  // Click → the optimistic flip is synchronous: right after the click the
  // switch reads on WITHOUT any network wait.
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  const optimistic = await page.evaluate(() => {
    const sw = document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    return {
      aria: sw.getAttribute('aria-checked'),
      on: sw.classList.contains('on'),
      pill: card.querySelector('.plugins-state-pill')?.textContent.trim(),
    };
  });
  expect(optimistic.aria, 'optimistic aria-checked=true immediately after click').toBe('true');
  expect(optimistic.on, 'optimistic switch class on immediately after click').toBe(true);
  expect(optimistic.pill, 'optimistic state pill flip').toBe('已启用');

  // Convergence: registry re-fetch applied in place; switch clickable again.
  await page.waitForFunction(() =>
    !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.disabled,
    { timeout: 8000 });

  const converged = await page.evaluate(() => {
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    const list = card.closest('.plugins-card-list');
    return {
      reloadMarker: window.__reloadMarker,
      cardIdentity: card.__identityTag === 'card-identity',
      listIdentity: list.__identityTag === 'list-identity',
      siblingIdentity: document.querySelector('.plugins-card[data-plugin="m-mid"]')?.__identityTag === 'sibling-identity',
      childCount: list.childElementCount,
      aria: card.querySelector('[data-plugin-switch]')?.getAttribute('aria-checked'),
      expandStillVisible: !document.querySelector('[data-expand-for="skills-e2e-hello"]')?.hidden,
      cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
    };
  });
  expect(converged.reloadMarker, 'no page reload across the toggle').toBe(7);
  expect(converged.cardIdentity, 'card DOM node identity preserved (no card re-render)').toBe(true);
  expect(converged.listIdentity, 'list container identity preserved (no list redraw)').toBe(true);
  expect(converged.siblingIdentity, 'sibling cards untouched').toBe(true);
  expect(converged.childCount, 'no nodes added/removed by the toggle').toBe(3);
  expect(converged.cardCount, 'no full-page re-render (same card set)').toBe(3);
  expect(converged.aria, 'converged state ≡ backend (on)').toBe('true');
  expect(converged.expandStillVisible, 'expanded skill block survived in place').toBe(true);

  // on → off via revoke (same in-place path).
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.getAttribute('aria-checked') === 'false'
    && !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.disabled,
    { timeout: 8000 });
  const off = await page.evaluate(() => ({
    identity: document.querySelector('.plugins-card[data-plugin="e2e-hello"]')?.__identityTag === 'card-identity',
    pill: document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim(),
  }));
  expect(off.identity, 'revoke path also in place').toBe(true);
  expect(off.pill, 'pill back to 未启用').toBe('未启用');
});

test('③b toggle rollback: failed POST reverts the optimistic flip + error toast, switch stays operable', async ({ page }) => {
  await loadShell(page);
  failApprove = true;

  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  // The toast is the definitive rollback signal (raised in the same block as
  // the revert + switch release).
  await page.waitForSelector('.nebflow-toast-error', { timeout: 8000 });
  const dump = await page.evaluate(() => ({
    aria: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.getAttribute('aria-checked'),
    enabled: !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.disabled,
    toasts: document.querySelectorAll('.nebflow-toast-error').length,
    identity: document.querySelector('.plugins-card[data-plugin="e2e-hello"]')?.dataset.plugin === 'e2e-hello',
  }));
  expect(dump.aria, 'optimistic flip rolled back to off').toBe('false');
  expect(dump.enabled, 'switch released after rollback (still operable)').toBe(true);
  expect(dump.toasts, 'error toast shown for the failed approve').toBeGreaterThan(0);
  expect(dump.identity, 'card identity preserved through rollback').toBe(true);

  // Recovery: clear the fault → the same switch works again.
  failApprove = false;
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.getAttribute('aria-checked') === 'true',
    { timeout: 8000 });
});

// ══ ④ nb-toggle 公共组件契约 ═════════════════════════════════════════════

test('④ toggle.js contract: role=switch, aria-checked, Space/Enter keyboard, setToggleState, attrs hook', async ({ page }) => {
  await loadShell(page);

  const unit = await page.evaluate(async () => {
    const m = await import('/js/toggle.js');
    const html = m.toggleHTML({ on: true, label: 'LBL', title: 'TIT', attrs: 'data-x="1"' });
    const root = document.createElement('div');
    root.innerHTML = html;
    document.body.appendChild(root);
    const sw = root.querySelector('.nb-toggle');
    const calls = [];
    const bound = m.bindToggle(root, (el, on) => calls.push(on));
    const before = {
      tag: sw.tagName,
      type: sw.getAttribute('type'),
      role: sw.getAttribute('role'),
      ariaChecked: sw.getAttribute('aria-checked'),
      on: sw.classList.contains('on'),
      label: sw.getAttribute('aria-label'),
      title: sw.getAttribute('title'),
      attrs: sw.getAttribute('data-x'),
      knobChildren: sw.childElementCount,
      boundCount: bound.length,
    };
    sw.click(); // optimistic flip + callback
    const afterClick = {
      ariaChecked: sw.getAttribute('aria-checked'),
      on: sw.classList.contains('on'),
      calls: calls.slice(),
    };
    m.setToggleState(sw, true); // programmatic set (rollback helper)
    const afterSet = { ariaChecked: sw.getAttribute('aria-checked'), on: sw.classList.contains('on') };
    // Re-bind is idempotent (no double toggles).
    m.bindToggle(root, (el, on) => calls.push('again:' + on));
    sw.click();
    const afterRebindClick = { ariaChecked: sw.getAttribute('aria-checked'), calls: calls.slice() };
    root.remove();
    return { before, afterClick, afterSet, afterRebindClick };
  });
  expect(unit.before.tag, 'real <button> — keyboard operable by construction').toBe('BUTTON');
  expect(unit.before.type, 'type=button (no form submit semantics)').toBe('button');
  expect(unit.before.role, 'role=switch').toBe('switch');
  expect(unit.before.ariaChecked, 'aria-checked mirrors initial on').toBe('true');
  expect(unit.before.on, 'on class mirrors initial state').toBe(true);
  expect(unit.before.label, 'aria-label carried').toBe('LBL');
  expect(unit.before.title, 'title carried').toBe('TIT');
  expect(unit.before.attrs, 'attrs hook lands on the element').toBe('1');
  expect(unit.before.knobChildren, 'knob is CSS-drawn — zero child elements').toBe(0);
  expect(unit.before.boundCount, 'bindToggle returns the bound switches').toBe(1);
  expect(unit.afterClick.ariaChecked, 'click flips to false (optimistic)').toBe('false');
  expect(unit.afterClick.calls, 'onChange receives (el, newState)').toEqual([false]);
  expect(unit.afterSet.ariaChecked, 'setToggleState programmatic true').toBe('true');
  expect(unit.afterRebindClick.ariaChecked, 're-bind is idempotent — the click flips exactly once (true→false)').toBe('false');
  expect(unit.afterRebindClick.calls, 'first handler stays bound, re-bind adds no duplicate (the "again:" callback is dropped)').toEqual([false, false]);

  // Keyboard: real input pipeline on the PANEL switch (m-mid — starts off).
  const swSel = '.plugins-card[data-plugin="m-mid"] [data-plugin-switch]';
  await page.focus(swSel);
  const ariaBeforeKeys = await page.getAttribute(swSel, 'aria-checked');
  await page.keyboard.press('Space');
  await page.waitForFunction((sel) =>
    document.querySelector(sel)?.getAttribute('aria-checked') === 'true', swSel, { timeout: 8000 });
  // The switch latches disabled during the POST — wait for release before
  // the next keystroke, or Enter would hit a disabled control.
  await page.waitForFunction((sel) => !document.querySelector(sel)?.disabled, swSel, { timeout: 8000 });
  const afterSpace = await page.getAttribute(swSel, 'aria-checked');
  // The in-flight latch disabled the switch, which blurs it (browser default)
  // — re-focus before the second key, as a real keyboard user would.
  await page.focus(swSel);
  await page.keyboard.press('Enter');
  await page.waitForFunction((sel) =>
    document.querySelector(sel)?.getAttribute('aria-checked') === 'false', swSel, { timeout: 8000 });
  const afterEnter = await page.getAttribute(swSel, 'aria-checked');
  expect(ariaBeforeKeys, 'precondition: switch off before keys').toBe('false');
  expect(afterSpace, 'Space toggles the switch').toBe('true');
  expect(afterEnter, 'Enter toggles it back').toBe('false');

  // Panel integration: the plugins panel switch IS the shared component.
  const integration = await page.evaluate((sel) => {
    const sw = document.querySelector(sel);
    return { nbToggle: sw.classList.contains('nb-toggle'), role: sw.getAttribute('role') };
  }, swSel);
  expect(integration.nbToggle, 'plugins panel switch uses the nb-toggle class (settings page will share it)').toBe(true);
  expect(integration.role, 'panel switch role=switch').toBe('switch');

  // CSS token discipline: the nb-toggle section in sidebar.css uses tokens
  // only — no hand-made hex colors, no literal rgba() colors.
  const css = readFileSync(join(WEB, 'css', 'sidebar.css'), 'utf8');
  const sectionStart = css.indexOf('«nb-toggle');
  const sectionEnd = css.indexOf('.plugins-card.plugins-card-entering');
  expect(sectionStart, 'nb-toggle section exists in sidebar.css').toBeGreaterThan(0);
  expect(sectionEnd, 'section is terminated by the card-enter block').toBeGreaterThan(sectionStart);
  const section = css.slice(sectionStart, sectionEnd);
  expect(/#[0-9a-fA-F]{3,8}\b/.test(section), 'no hand-made hex colors in the component section').toBe(false);
  expect(/rgba\(/.test(section), 'no literal rgba colors in the component section').toBe(false);
  expect(section.includes('rgb(var(--sapphire)'), 'accent from the sapphire token family').toBe(true);
  expect(section.includes('var(--glass-control-bg)'), 'track from the glass-control material tokens').toBe(true);
});
