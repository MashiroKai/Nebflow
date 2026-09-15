// plugins-panel-autosync.spec.mjs — 2026-09-05 插件面板体验批验收 spec
//（移除手动刷新 + 列表实时自动同步 + 原地状态切换 + nb-toggle 公共组件）。
// **2026-09-13 无审批批（装了就是信任）重写**；**2026-09-14 插件面板收敛批
// （作者三裁之批一）再重写**：封禁/解封 UI 全量退场 ⇒ 卡片只剩**一个**控件 =
// 派发开关（卡片右上、与状态药丸同排，乐观原地翻转）。
// **2026-09-15 UI 文案微批（作者令）**：该开关旁的**可见 label 小字**
// 「任务分发器可见性」整体删除（toggle 本体自明，只留 aria-label）；默认态药丸
// 也不再出「已启用」文字（药丸本体 + 状态样式保留；blocked/changed 仍出字）。
//
// Shell 级：隔离静态服务器（127.0.0.1:8181，8100+ 纪律，绝非宿主 8080）+
// 页内 mock，真实 UI 代码全量执行。断言链：
//   ① 手动刷新按钮不复存在（DOM 零残留）
//   ② mock 注入新插件 → 轮询周期内列表自动插入新卡（带 plugins-card-entering
//      插入动画，MutationObserver 同帧捕获），全程无手动刷新、无 page reload；
//      新卡携带**唯一控件（派发开关）**（🔴 不带内容审批开关，🔴 不带已退场的
//      封禁/「更多」入口）
//   ③ 派发开关启停：乐观原地翻转 → 注册表收敛，卡片/列表/兄弟卡 DOM 节点身份
//      不变（零全列表重绘）、无 page reload；POST 失败回滚乐观态 + toast
//   ④ nb-toggle 组件契约：role=switch / aria-checked / 键盘 Space+Enter /
//      setToggleState / attrs 钩子 / 插件面板开关同 class / CSS 全 token 零手造色
//   ⑤ 封禁态由**引擎侧**产生（API / CLI；本 spec 以夹具态翻转 + 重同步模拟其结果）
//      ⇒ 面板必须收敛出「已封禁」+ 派发开关同帧锁死 + 可行动提示；且面板**零**
//      revoke/unblock wire（UI 入口已退场，端点本身零改动）
//   ⑥ （原「零静默：后端 ok:true 但注册表未反映封禁态 ⇒ error toast」用例**随该动作
//      退场而删除**——它的唯一承载就是被删掉的封禁动作 + `plugins.actionNoEffect`。
//      在飞的 F1 承载 = ③b：派发开关 POST 失败 ⇒ 回滚乐观态 + error toast。
//      ⇒ 若作者希望把「ok:true 但注册表未反映」的核对搬到派发面，那是**新增行为**，
//      属后续批，不在本批范围。）
//   ⑦ 前端判据面（🔴 **不是**引擎语义的正面断言）：载荷缺 blocked/contentChanged
//      ⇒ 卡片落默认态（药丸**无可见文字**、状态类 on）+ 派发开关可用 + 内容审批
//      开关零残留。夹具**故意**取旧
//      default-deny 形态（`trusted:false` + `reason:'never approved (default-deny)'`）
//      ⇒ 可对「药丸被重新绑回审批面」这一前端回归转红；对「引擎是否在位即信任」
//      **无区分度**（前端按设计不消费 `trusted`）。引擎侧「无记录包首扫即受信」的
//      正面断言在 Scala 侧 `PluginRegistrySpec`（「在位即信任（正面断言）」+
//      「无记录包进目录」两例），不在本 spec。
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
 *  `blocked` / `contentChanged`. 🔴 fixture 缺省 = **新引擎世界**（`trusted:true`、
 *  无 `trust` 块）；**旧 default-deny 形态**（`trusted:false` + 下面这个 trust 块
 *  = 改造前引擎对无记录包的载荷，即复核判词 M1 的变异输入）**只在 ⑦ 显式注入**
 *  ——那里它是「药丸不得再绑审批面」这一断言的**区分度**来源（夹具不带
 *  `trusted:false` 时该用例双向恒绿，正是判词 R1 判红的原因）。 */
const LEGACY_TRUST = { status: 'untrusted', reason: 'never approved (default-deny)' };

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
    // 缺省 = 新引擎世界（在位即信任：包受信、无 trust 记录块）。
    // 旧 default-deny 形态（`trusted:false` + `trust` 块）只在 ⑦ 显式注入。
    trusted: true,
    blocked: false,
    contentChanged: false,
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
      pill: card.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
      pillClass: card.querySelector('.plugins-state-pill')?.className ?? null,
      pillElCount: card.querySelectorAll('.plugins-state-pill').length,
      cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
    };
  });
  expect(dump.reloadMarker, 'no page reload during live insert').toBe(42);
  expect(dump.inserted, 'exactly the new plugin card was recorded as inserted, with the entering animation').toEqual([
    { name: 'z-late', entering: true },
  ]);
  expect(dump.prevName, 'inserted at sorted position (after m-mid)').toBe('m-mid');
  expect(dump.nextIsRejected, 'trusted block stays before the rejected card').toBe(true);
  expect(dump.moreThere, 'new card 零残留：退场的「更多」入口不得出现').toBe(false);
  expect(dump.blockThere, 'new card 零残留：退场的封禁入口不得出现').toBe(false);
  expect(dump.dispatchThere, 'new card carries the dispatch switch (唯一控件)').toBe(true);
  expect(dump.contentSwitchThere, 'new card does NOT carry the retired content switch').toBe(false);
  // 2026-09-15 作者令（先删文字「已启用」、后裁「删掉空框」）：默认态药丸**不出文字**，
  // 且文字为空 ⇒ **元素不生成** —— 判据 = DOM 里 `.plugins-state-pill` count = 0。
  expect(dump.pillElCount, 'new card 空态零元素：pill 元素不生成（「删掉空框」，非「元素在但不可见」）').toBe(0);
  expect(dump.pill, 'new card pill textContent 读数 = null（无元素可读）').toBe(null);
  expect(dump.pillClass, 'new card 空态无类可挂（旧「on 类保留」口径已随空框一并退场）').toBe(null);
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
      pillClass: card.querySelector('.plugins-state-pill')?.className,
      pillElCount: card.querySelectorAll('.plugins-state-pill').length,
    };
  });
  expect(optimistic.aria, 'optimistic aria-checked=false immediately after click').toBe('false');
  expect(optimistic.on, 'optimistic switch class off immediately after click').toBe(false);
  expect(optimistic.pillElCount, 'dispatch write never moves the status pill（空态零元素 ⇒ 仍零元素）').toBe(0);
  expect(optimistic.pill, 'dispatch write never moves the status pill（无元素 ⇒ 读数 undefined）').toBe(undefined);
  expect(optimistic.pillClass, 'dispatch write never moves the status pill state class（无元素 ⇒ 无类）').toBe(undefined);

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

// ══ ⑤ 封禁/解封（C7 次级动作）＋ ⑥ 零静默 ＋ ⑦ 前端判据面（缺 blocked/contentChanged）══

/** 卡片形态读数（⑤/⑥/⑦ 共用）。 */
async function cardState(page, name) {
  return page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    const btn = card?.querySelector('[data-plugin-block]');
    const dw = card?.querySelector('[data-plugin-dispatch]');
    return {
      pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
      pillClass: card?.querySelector('.plugins-state-pill')?.className ?? null,
      pillElCount: card ? card.querySelectorAll('.plugins-state-pill').length : null,
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

test('⑤ 封禁态来自引擎侧（API/CLI）⇒ 收敛「已封禁」+ 派发锁死；面板零 revoke/unblock wire', async ({ page }) => {
  await loadShell(page);
  const posts = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/api/plugins/')) posts.push(new URL(r.url()).pathname);
  });

  // 前置：未封禁 ⇒ 默认态药丸**零元素**（「删掉空框」）+ 派发开关可用 + 封禁 UI 零残留。
  const before = await cardState(page, 'e2e-hello');
  expect(before.pillElCount, 'precondition: 空态零元素 —— pill 元素不生成（「删掉空框」，非「元素在但不可见」）').toBe(0);
  expect(before.pill, 'precondition: 空态 pill 读数 = null').toBe(null);
  expect(before.pillClass, 'precondition: 空态无类可挂（旧「on 类保留」口径已退场）').toBe(null);
  expect(before.dispatchDisabled, 'precondition: dispatch usable').toBe(false);
  expect(before.blockLabel, 'precondition: 封禁入口已退场（DOM 零残留）').toBe(null);

  // 面板上唯一的用户动作（派发开关）——wire 只可能是 enable/disable。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.getAttribute('aria-checked') === 'false',
    { timeout: 8000 });

  // 引擎侧封禁（作者选定的在飞止损路径 = API / CLI；夹具态翻转 + 重同步模拟其结果）
  registry.plugins.find(p => p.name === 'e2e-hello').blocked = true;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.classList.contains('blocked'),
    { timeout: 8000 });
  const blocked = await cardState(page, 'e2e-hello');
  expect(blocked.pillClass, 'blocked pill class').toContain('blocked');
  // 例外态仍出文字（可行动信号；删的只是默认态冗余小字）。
  expect(blocked.pill, 'blocked pill 仍出文字 = 已封禁').toBe('已封禁');
  expect(blocked.dispatchDisabled, '封禁 ⇒ 派发开关同帧锁死（写下去也不会生效）').toBe(true);
  expect(blocked.dispatchBlocked, 'blocked 原因随元素暴露').toBe('blocked');
  expect(blocked.blockedHint, '一行可行动提示').toBeTruthy();
  expect(blocked.blockedHint, '封禁提示指向 API / CLI（面板已无入口）').toContain('API / CLI');
  expect(blocked.blockLabel, '封禁态下也不得长回封禁入口').toBe(null);

  // 解封（同样引擎侧）⇒ 回默认态（药丸**零元素**）+ 派发开关解锁。
  registry.plugins.find(p => p.name === 'e2e-hello').blocked = false;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  // 等重渲**完成**（卡片 class 回 on + loading 占位消失）——旧口径等的「pill 回 on 类」
  // 已随空框退场，改等卡片级完成信号，避免与 innerHTML 重写竞态。
  await page.waitForFunction(() => {
    const c = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    return !!c && c.classList.contains('on') && !document.querySelector('#plugins-content .plugins-loading');
  }, undefined, { timeout: 8000 });
  const after = await cardState(page, 'e2e-hello');
  expect(after.pillElCount, 'pill 回默认态 = 零元素（不是「回到无文字的 on 药丸」）').toBe(0);
  expect(after.pill, 'pill 回默认态：无元素 ⇒ 读数 null').toBe(null);
  expect(after.dispatchDisabled, '派发开关解锁').toBe(false);
  expect(after.dispatchBlocked, 'blocked 属性清除').toBe(null);
  expect(after.blockedHint, '封禁提示移除').toBe(null);
  // 🔴 整轮交互的 wire：零 revoke / 零 unblock（端点面零改动，但面板不再提供入口）
  expect(posts.filter(p => /\/revoke$|\/unblock$/.test(p)), '面板不得打 revoke/unblock').toEqual([]);
  expect(legacyApproveCalls, 'UI 主线绝不调用 /approve').toEqual([]);
});

// ⑥ 原「零静默（F1）：后端 ok:true 但注册表未反映封禁态 ⇒ error toast」——**已随
// 封禁动作退场删除**（承载者 `setPluginBlocked` + `plugins.actionNoEffect` 与本批
// 一并删除）。在飞的 F1 承载 = ③b（派发开关 POST 失败 ⇒ 回滚 + error toast）。
// 若要把该核对搬到派发面，属新增行为 ⇒ 后续批，不写在这里冒充通过。

test('⑦ 前端判据面：载荷缺 blocked/contentChanged（旧 default-deny 形态）⇒ 已启用 + 派发可用 + 内容开关零残留', async ({ page }) => {
  // 🔴 本用例**不**宣称引擎语义（「无记录包首扫即受信」的正面断言在 Scala 侧
  // `PluginRegistrySpec`，见文件头）。它断言前端自己的契约面：夹具取「无记录包 +
  // 引擎**未**在位即信任」这一**最不利**世界 —— 旧 default-deny 形态
  // （`trusted:false` + `reason:'never approved (default-deny)'`，即复核判词 M1 的
  // 变异输入）。前端只读 blocked/contentChanged ⇒ 该世界下仍必须是「已启用」+
  // 派发可用 + 旧审批开关零残留。
  // 区分度（返工轮实测）：把 `pluginStatus` 的药丸重新绑回 `trusted`（复核判词 M2
  // 变异）⇒ 本用例**转红**；旧夹具（缺省 `trusted:true`）在 M1/M2 双向恒绿。
  // （e2e-hello 保留在注册表里是 loadShell 的锚点插件。）
  registry.plugins = [
    manifest('e2e-hello'),
    manifest('no-record-pkg', { trusted: false, trust: { ...LEGACY_TRUST } }),
    manifest('m-mid'),
  ];
  // 夹具自检：注入的确实是旧 default-deny 载荷（否则本用例退化为恒绿）。
  const injected = registry.plugins.find(p => p.name === 'no-record-pkg');
  expect(injected.trusted, '夹具携带旧 default-deny 判词 trusted=false').toBe(false);
  expect(injected.trust?.reason, '夹具携带旧 default-deny 原因串').toContain('default-deny');

  await loadShell(page);
  await page.waitForSelector('.plugins-card[data-plugin="no-record-pkg"]', { timeout: 10000 });
  const st = await cardState(page, 'no-record-pkg');
  expect(st.pillElCount, '载荷 trusted=false（旧 default-deny 世界）下默认态仍零元素（text===\'\' ⇒ 元素不生成）').toBe(0);
  expect(st.pill, '载荷 trusted=false 下默认态 pill 读数 = null').toBe(null);
  expect(st.pillClass, '空态无类可挂').toBe(null);
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
