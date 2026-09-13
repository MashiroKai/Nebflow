// plugins-panel-autosync.spec.mjs — 2026-09-05 插件面板体验批验收 spec
//（移除手动刷新 + 列表实时自动同步 + 原地状态切换 + nb-toggle 公共组件）。
// **2026-09-13 无审批批（装了就是信任）重写**：内容审批开关退场后，卡片上
// 剩下的可操作件是「派发开关」（乐观原地翻转）与「封禁/解封」（次级动作，
// 不做乐观翻转、落盘后核对注册表）。
//
// Shell 级：隔离静态服务器（127.0.0.1:8181，8100+ 纪律，绝非宿主 8080）+
// 页内 mock，真实 UI 代码全量执行。断言链：
//   ① 手动刷新按钮不复存在（DOM 零残留）
//   ② mock 注入新插件 → 轮询周期内列表自动插入新卡（带 plugins-card-entering
//      插入动画，MutationObserver 同帧捕获），全程无手动刷新、无 page reload；
//      新卡携带**次级动作入口 + 派发开关**（🔴 不带内容审批开关）
//   ③ 派发开关启停：乐观原地翻转 → 注册表收敛，卡片/列表/兄弟卡 DOM 节点身份
//      不变（零全列表重绘）、无 page reload；POST 失败回滚乐观态 + toast
//   ④ nb-toggle 组件契约：role=switch / aria-checked / 键盘 Space+Enter /
//      setToggleState / attrs 钩子 / 插件面板开关同 class / CSS 全 token 零手造色
//   ⑤ 封禁/解封（C7 次级动作）：封禁 ⇒ POST /revoke ⇒ 卡片转「已封禁」+ 派发
//      开关同帧锁死；解封 ⇒ POST /unblock ⇒ 回「已启用」+ 派发开关解锁
//   ⑥ 零静默（F1）：后端 ok:true 但注册表**未**反映封禁态 ⇒ 必须出 error toast，
//      绝不静默无效（禁 `ok:true` + 零效果）
//   ⑦ 正面断言：无记录包（C6 trusted/blocked=false/contentChanged=false）在面板上
//      表现为「已启用」且派发开关可用（在位即信任的可见结果）
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

/** C6 (2026-09-13 无审批批): the payload no longer carries a gate verdict the
 *  UI consults — `trusted` stays for compatibility, the card binds to
 *  `blocked` / `contentChanged`. The legacy `trust` block is deliberately kept
 *  in the fixture (and deliberately NOT read) so a regression that re-binds
 *  the pill to trust shows up here. */
const LEGACY_TRUST = { status: 'untrusted', reason: 'legacy gate record — no longer decides loading' };

/** Mutable registry — tests mutate it to simulate backend-side plugin
 *  appearances (live-sync path). Reset per test in beforeEach. */
const registry = { plugins: [], rejected: [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }] };
/** Fault injection (rollback path): POST /disable → 500. */
let failDispatch = false;
/** Fault injection (零静默 path): POST /revoke answers ok:true but the registry
 *  does NOT flip `blocked` — i.e. the action has no effect. */
let revokeNoEffect = false;

function manifest(name, over = {}) {
  return {
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} (autosync spec)`,
    author: 'autosync-spec',
    digest: 'a'.repeat(72),
    fileCount: 1,
    trusted: true,
    blocked: false,
    contentChanged: false,
    trust: { ...LEGACY_TRUST },
    skills: [{ id: `${name}/s`, description: 'Skill', preview: 'body' }],
    mcpServers: [],
    toolsExtension: [],
    warnings: [],
    ...over,
  };
}

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(() => {
  registry.plugins = [manifest('e2e-hello'), manifest('m-mid')];
  registry.rejected = [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }];
  failDispatch = false;
  revokeNoEffect = false;
});

/** /approve 误调记录（UI 主线不得再调用它）。 */
const legacyApproveCalls = [];

/** Shell load with the full API/WS mock set. */
async function loadShell(page) {
  legacyApproveCalls.length = 0;
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Route matching is LIFO — catch-all FIRST, specifics after (they win).
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  // 🔴 /approve 已退出 UI 主线（审批语义退场）。路由保留只为记录误调。
  await page.route('**/api/plugins/*/approve', r => {
    legacyApproveCalls.push(r.request().method());
    r.fulfill({ json: { ok: true, message: 'legacy approve (UI must not call this)' } });
  });
  await page.route('**/api/plugins/*/enable', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    const name = r.request().url().match(/plugins\/([^/]+)\/enable/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, dispatch: { ...(p.dispatch || {}), authorEnabled: true } } : p);
    r.fulfill({ json: { ok: true, message: 'enabled' } });
  });
  await page.route('**/api/plugins/*/disable', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    if (failDispatch) return r.fulfill({ status: 500, json: { error: 'boom — injected failure' } });
    const name = r.request().url().match(/plugins\/([^/]+)\/disable/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, dispatch: { ...(p.dispatch || {}), authorEnabled: false } } : p);
    r.fulfill({ json: { ok: true, message: 'disabled' } });
  });
  // 封禁（保留路径名，语义翻转）：注册表回 blocked:true（除非注入「无效果」）。
  await page.route('**/api/plugins/*/revoke', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    if (!revokeNoEffect) {
      const name = r.request().url().match(/plugins\/([^/]+)\/revoke/)?.[1];
      registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, blocked: true } : p);
    }
    r.fulfill({ json: { ok: true, message: 'blocked' } });
  });
  await page.route('**/api/plugins/*/unblock', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    const name = r.request().url().match(/plugins\/([^/]+)\/unblock/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, blocked: false } : p);
    r.fulfill({ json: { ok: true, message: 'unblocked' } });
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
  expect(dump.cards, 'plugin cards still render (2 plugin-list + 1 rejected)').toBe(3);
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
      moreThere: !!card.querySelector('[data-plugin-more]'),
      blockThere: !!card.querySelector('[data-plugin-block]'),
      dispatchThere: !!card.querySelector('[data-plugin-dispatch]'),
      contentSwitchThere: !!card.querySelector('[data-plugin-switch]'),
      pill: card.querySelector('.plugins-state-pill')?.textContent.trim() || null,
      cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
    };
  });
  expect(dump.reloadMarker, 'no page reload during live insert').toBe(42);
  expect(dump.inserted, 'exactly the new plugin card was recorded as inserted, with the entering animation').toEqual([
    { name: 'z-late', entering: true },
  ]);
  expect(dump.prevName, 'inserted at sorted position (after m-mid)').toBe('m-mid');
  expect(dump.nextIsRejected, 'trusted block stays before the rejected card').toBe(true);
  expect(dump.moreThere, 'new card carries the 更多 secondary-action entry').toBe(true);
  expect(dump.blockThere, 'new card carries the block/unblock action').toBe(true);
  expect(dump.dispatchThere, 'new card carries the dispatch switch').toBe(true);
  expect(dump.contentSwitchThere, 'new card does NOT carry the retired content switch').toBe(false);
  expect(dump.pill, 'new card pill = 已启用 (presence-trust)').toBe('已启用');
  expect(dump.cardCount, 'card count grew 3 → 4').toBe(4);
});

// ══ ③ 原地状态切换（派发开关乐观翻转 + 收敛 + 回滚）═══════════════════════

test('③ dispatch toggle: optimistic in-place flip → registry convergence; zero list redraw, zero reload', async ({ page }) => {
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
  // dispatch switch reads off WITHOUT any network wait. (The status pill is
  // bound to blocked/contentChanged — a dispatch write must NOT move it.)
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
  const optimistic = await page.evaluate(() => {
    const dw = document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    return {
      aria: dw.getAttribute('aria-checked'),
      on: dw.classList.contains('on'),
      pill: card.querySelector('.plugins-state-pill')?.textContent.trim(),
    };
  });
  expect(optimistic.aria, 'optimistic aria-checked=false immediately after click').toBe('false');
  expect(optimistic.on, 'optimistic switch class off immediately after click').toBe(false);
  expect(optimistic.pill, 'dispatch write never moves the status pill (绑 blocked/contentChanged)').toBe('已启用');

  // Convergence: registry re-fetch applied in place; switch clickable again.
  await page.waitForFunction(() =>
    !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.disabled,
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
      aria: card.querySelector('[data-plugin-dispatch]')?.getAttribute('aria-checked'),
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
  expect(converged.aria, 'converged state ≡ backend (off)').toBe('false');
  expect(converged.expandStillVisible, 'expanded skill block survived in place').toBe(true);

  // off → on via enable (same in-place path).
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.getAttribute('aria-checked') === 'true'
    && !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.disabled,
    { timeout: 8000 });
  const backOn = await page.evaluate(() => ({
    identity: document.querySelector('.plugins-card[data-plugin="e2e-hello"]')?.__identityTag === 'card-identity',
    on: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.classList.contains('on'),
  }));
  expect(backOn.identity, 'enable path also in place').toBe(true);
  expect(backOn.on, 'dispatch switch back on').toBe(true);
});

test('③b dispatch rollback: failed POST reverts the optimistic flip + error toast, switch stays operable', async ({ page }) => {
  await loadShell(page);
  failDispatch = true;

  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
  // The toast is the definitive rollback signal (raised in the same block as
  // the revert + switch release).
  await page.waitForSelector('.nebflow-toast-error', { timeout: 8000 });
  const dump = await page.evaluate(() => ({
    aria: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.getAttribute('aria-checked'),
    enabled: !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.disabled,
    toasts: document.querySelectorAll('.nebflow-toast-error').length,
    identity: document.querySelector('.plugins-card[data-plugin="e2e-hello"]')?.dataset.plugin === 'e2e-hello',
  }));
  expect(dump.aria, 'optimistic flip rolled back to on').toBe('true');
  expect(dump.enabled, 'switch released after rollback (still operable)').toBe(true);
  expect(dump.toasts, 'error toast shown for the failed dispatch write').toBeGreaterThan(0);
  expect(dump.identity, 'card identity preserved through rollback').toBe(true);

  // Recovery: clear the fault → the same switch works again.
  failDispatch = false;
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.getAttribute('aria-checked') === 'false',
    { timeout: 8000 });
});

// ══ ⑤ 封禁/解封（C7 次级动作）＋ ⑥ 零静默 ＋ ⑦ 无记录包正面断言 ═══════════

/** 卡片形态读数（⑤/⑥/⑦ 共用）。 */
async function cardState(page, name) {
  return page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    const btn = card?.querySelector('[data-plugin-block]');
    const dw = card?.querySelector('[data-plugin-dispatch]');
    return {
      pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
      pillClass: card?.querySelector('.plugins-state-pill')?.className ?? null,
      cardClass: card?.className ?? null,
      blockLabel: btn?.textContent.trim() ?? null,
      blockState: btn?.dataset.blocked ?? null,
      dispatchDisabled: dw ? dw.disabled : null,
      dispatchBlocked: dw?.getAttribute('data-dispatch-blocked') ?? null,
      blockedHint: card?.querySelector('.plugins-card-hint.blocked')?.textContent.trim() ?? null,
      noteText: card?.querySelector('.plugins-dispatch-note')?.hidden === false
        ? card.querySelector('.plugins-dispatch-note')?.textContent.trim() : null,
    };
  }, name);
}

test('⑤ block/unblock: 封禁 ⇒ POST /revoke ⇒ 已封禁 + 派发锁死; 解封 ⇒ POST /unblock ⇒ 回已启用', async ({ page }) => {
  await loadShell(page);
  const posts = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/api/plugins/')) posts.push(new URL(r.url()).pathname);
  });

  // 前置：未封禁 ⇒ 已启用 + 派发开关可用。
  const before = await cardState(page, 'e2e-hello');
  expect(before.pill, 'precondition: 已启用').toBe('已启用');
  expect(before.dispatchDisabled, 'precondition: dispatch usable').toBe(false);

  // 封禁（次级动作在「更多」菜单里）。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]');
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim() === '已封禁',
    { timeout: 8000 });
  const blocked = await cardState(page, 'e2e-hello');
  expect(blocked.pillClass, 'blocked pill class').toContain('blocked');
  expect(blocked.blockLabel, 'block entry flips to 解封').toBe('解封该插件');
  expect(blocked.blockState, 'data-blocked=1').toBe('1');
  expect(blocked.dispatchDisabled, '封禁 ⇒ 派发开关同帧锁死（零静默：写下去也不会生效）').toBe(true);
  expect(blocked.dispatchBlocked, 'blocked 原因随元素暴露').toBe('blocked');
  expect(blocked.blockedHint, '一行可行动提示').toBeTruthy();
  expect(posts, 'wire: 只打 /revoke').toEqual(['/api/plugins/e2e-hello/revoke']);

  // 解封。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim() === '已启用',
    { timeout: 8000 });
  const after = await cardState(page, 'e2e-hello');
  expect(after.pillClass, 'pill back to on').toContain('on');
  expect(after.dispatchDisabled, '派发开关解锁').toBe(false);
  expect(after.dispatchBlocked, 'blocked 属性清除').toBe(null);
  expect(after.blockedHint, '封禁提示移除').toBe(null);
  expect(posts, 'wire: revoke → unblock，别无他写').toEqual([
    '/api/plugins/e2e-hello/revoke', '/api/plugins/e2e-hello/unblock',
  ]);
  expect(legacyApproveCalls, 'UI 主线绝不调用 /approve').toEqual([]);
});

test('⑥ 零静默：后端 ok:true 但注册表未反映封禁 ⇒ 必须出 error toast（禁 ok:true 静默无效）', async ({ page }) => {
  await loadShell(page);
  revokeNoEffect = true; // 注入：POST /revoke 回 ok:true，但 blocked 不变
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]');
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]');
  await page.waitForSelector('.nebflow-toast-error', { timeout: 8000 });
  const dump = await page.evaluate(() => ({
    toasts: [...document.querySelectorAll('.nebflow-toast-error')].map(e => e.textContent.trim()),
    pill: document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim(),
    btnDisabled: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]')?.disabled,
  }));
  expect(dump.toasts.length, '零静默：动作未生效必须显式报错').toBeGreaterThan(0);
  expect(dump.toasts.join(' '), 'toast 文案点名该插件（可行动）').toContain('e2e-hello');
  expect(dump.pill, 'UI 不擅自画成已封禁（以注册表为单一事实源）').toBe('已启用');
  expect(dump.btnDisabled, '在途闩已释放（可重试）').toBe(false);
});

test('⑦ 无记录包：面板表现为「已启用」且派发开关可用（在位即信任的可见结果）', async ({ page }) => {
  // 无记录包 = 载荷里没有 trust 记录、blocked/contentChanged 均为 false。
  // （e2e-hello 保留在注册表里是 loadShell 的锚点插件。）
  registry.plugins = [manifest('e2e-hello'), manifest('no-record-pkg', { trust: undefined }), manifest('m-mid')];
  await loadShell(page);
  await page.waitForSelector('.plugins-card[data-plugin="no-record-pkg"]', { timeout: 10000 });
  const st = await cardState(page, 'no-record-pkg');
  expect(st.pill, '无记录包在面板上表现为已启用').toBe('已启用');
  expect(st.pillClass, 'pill on class').toContain('on');
  expect(st.dispatchDisabled, '派发开关可用（不再要求先审批内容）').toBe(false);
  expect(st.noteText, '无「先审批内容」注记（该前提已消失）').toBe(null);
  const page_dump = await page.evaluate(() => ({
    contentSwitchCount: document.querySelectorAll('#plugins-content [data-plugin-switch]').length,
    cardClass: document.querySelector('.plugins-card[data-plugin="no-record-pkg"]')?.className,
  }));
  expect(page_dump.contentSwitchCount, '内容审批开关零残留').toBe(0);
  expect(page_dump.cardClass, 'card carries the on class').toContain('on');
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

  // Keyboard: real input pipeline on the PANEL switch (m-mid's dispatch switch,
  // which starts on because the fixture's dispatch.authorEnabled defaults true).
  const swSel = '.plugins-card[data-plugin="m-mid"] [data-plugin-dispatch]';
  await page.focus(swSel);
  const ariaBeforeKeys = await page.getAttribute(swSel, 'aria-checked');
  await page.keyboard.press('Space');
  await page.waitForFunction((sel) =>
    document.querySelector(sel)?.getAttribute('aria-checked') === 'false', swSel, { timeout: 8000 });
  // The switch latches disabled during the POST — wait for release before
  // the next keystroke, or Enter would hit a disabled control.
  await page.waitForFunction((sel) => !document.querySelector(sel)?.disabled, swSel, { timeout: 8000 });
  const afterSpace = await page.getAttribute(swSel, 'aria-checked');
  // The in-flight latch disabled the switch, which blurs it (browser default)
  // — re-focus before the second key, as a real keyboard user would.
  await page.focus(swSel);
  await page.keyboard.press('Enter');
  await page.waitForFunction((sel) =>
    document.querySelector(sel)?.getAttribute('aria-checked') === 'true', swSel, { timeout: 8000 });
  const afterEnter = await page.getAttribute(swSel, 'aria-checked');
  expect(ariaBeforeKeys, 'precondition: switch on before keys').toBe('true');
  expect(afterSpace, 'Space toggles the switch').toBe('false');
  expect(afterEnter, 'Enter toggles it back').toBe('true');

  // Panel integration: the plugins panel switch IS the shared component.
  const integration = await page.evaluate((sel) => {
    const sw = document.querySelector(sel);
    return { nbToggle: sw.classList.contains('nb-toggle'), role: sw.getAttribute('role') };
  }, swSel);
  expect(integration.nbToggle, 'plugins panel switch uses the nb-toggle class (settings page will share it)').toBe(true);
  expect(integration.role, 'panel switch role=switch').toBe('switch');

  // CSS token discipline: the nb-toggle section in sidebar.css uses tokens
  // only — no hand-made hex colors, no literal rgba() colors.
  // 🔴 锚点修正（2026-09-13，本批附带）：旧锚 `indexOf('«nb-toggle')` 命中的是
  // 文件中部一条**提及**该节的注释（sidebar.css:748），切片因此横跨数千行
  // 历史手造色值 ⇒ 断言在基线上就是红的（本批基线 ablation 实测：BASELINE
  // hex?=True rgba?=True）。改为锚定**节头注释本身** + 节尾（卡片入场块）。
  // 同批修正第二处陈旧期望：开关强调色自 2026-09-06 作者裁定起已由 sapphire
  // 改为 brand-green（见 sidebar.css 节头注释与 visual-style skill），
  // 断言从 `rgb(var(--sapphire)` 改为 `rgb(var(--brand-green)`。
  const css = readFileSync(join(WEB, 'css', 'sidebar.css'), 'utf8');
  const sectionStart = css.indexOf('/* ── nb-toggle — shared switch component');
  const sectionEnd = css.indexOf('.plugins-card.plugins-card-entering');
  expect(sectionStart, 'nb-toggle section exists in sidebar.css').toBeGreaterThan(0);
  expect(sectionEnd, 'section is terminated by the card-enter block').toBeGreaterThan(sectionStart);
  const section = css.slice(sectionStart, sectionEnd);
  expect(/#[0-9a-fA-F]{3,8}\b/.test(section), 'no hand-made hex colors in the component section').toBe(false);
  expect(/rgba\(/.test(section), 'no literal rgba colors in the component section').toBe(false);
  expect(section.includes('rgb(var(--brand-green)'), 'accent from the brand-green token family').toBe(true);
  expect(section.includes('var(--glass-control-bg)'), 'track from the glass-control material tokens').toBe(true);
});
