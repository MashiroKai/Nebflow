// config-drill-unify.spec.mjs — 配置面风格统一批（作者令 2026-10-31 21:40，两件）
// 桌面端守卫 spec。
//
// ① JeV/生图配置面层级下钻（与 LLM 配置同款族：桌面浮层玻璃面板 +
//    手机层级子页——手机 legs 在 mobile-popup-retire.spec T4 末尾）：
//    U1  生图入口行 → 浮层收编 #imagegen-advance-body（内容/绑定随节点走），
//        ×/点空白/Esc 归还隐藏；JeV 同链路由 jev-panel-threefix R1b 守。
// ② 设置页「模型回退链」节（/model 回退链调整页原样再放一份）：
//    U2  显式链：行渲染 = 链内容（拖拽排序行族 .mp-row 同源）、加模型 ⇒
//        PUT /api/agents/Nebula/model {model:{preferred,fallbacks}}。
//    U3  双向往返同步：面板改 ⇒ 设置节即时重渲；设置节改 ⇒ 面板即时重渲
//        （同一 roleState/保存通道，emitChainChange 单点）。
//    U4  seed 态：mode=follow ⇒ seed 注记 + 生效链预览 + 「独立配置」fork ⇒
//        PUT 以生效链起显式链（与 /model 面板 Nebula 块同款语义）。
//    U5  读数失败 ⇒ 节级显式降级面（#937 同款语义，不静默半死）+ 重试恢复。
//
// SELF-CONTAINED（jev-panel-threefix.spec.mjs 同款 harness）：静态服务器
// 127.0.0.1:8263 + route mock API + routeWebSocket mock WS，零真实后端。
// Run: npx playwright test tests/config-drill-unify.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8263; // fixed, 8100+ per task discipline — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

const PINNED = 'https://api.typesafe.ai/v1/systemone';

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
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
 * Boot the real shell against mocks. The model-chain state is a mutable
 * server-side simulation (GET reads it; PUT folds the write in like the
 * engine does, then returns the fresh projection — putChain re-reads it).
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark',
 *           chain?: {preferred: string, fallbacks: string[]}|null,
 *           modelFail?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', colorScheme = 'light',
    chain = { preferred: 'kimi/kimi-k2', fallbacks: ['anon/anon-1'] },
    modelFail = false,
  } = opts;
  // 服务端模拟态：Nebula 显式链（null = seed/follow 态）。
  const sim = { chain };
  const putBodies = [];
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  // 智能体档案读数（mobile-popup-retire 同款 mock——否则静态服务器 404 进 console）。
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/**', (r) => {
    const p = new URL(r.request().url()).pathname;
    // 模型链读数（Nebula 与 follower 角色同形状 mock；nebflow 恒 follow）。
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      if (r.request().method() === 'PUT') {
        const body = r.request().postDataJSON();
        putBodies.push(body);
        sim.chain = body && body.model ? body.model : null;
      }
      if (modelFail) return r.fulfill({ status: 401, body: 'unauthorized' });
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
    // 生图状态投影（seal=ON，一个既有 provider）。
    if (p === '/api/config/imagegen') {
      return r.fulfill({
        json: {
          compiledIn: true, enabled: true,
          providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true } },
        },
      });
    }
    return r.fulfill({ json: {} }); // catch-all
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({
        llm: {
          providers: {
            kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: '***', protocol: 'anthropic', models: [{ id: 'kimi-k2', contextWindow: 8192 }] },
            anon: { baseUrl: 'https://anon.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'anon-1', contextWindow: 4096 }] },
          },
        },
        jev: { enabled: true, provider: 'typesafe-jev', endpoint: PINNED, keyRef: 'typesafe-api-key' },
      }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return { sim, putBodies };
}

async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    // 资源 401 = 本 spec 自己的降级场景 mock（U5）——语义断言在 DOM 面，
    // console 面只滤这一种已知场景音。
    if (/Failed to load resource.*401 \(Unauthorized\)/.test(m.text())) return;
    errors.push(`console: ${m.text()}`);
  });
  return errors;
}

/** 设置页回退链节的行 refs（按渲染序）。 */
function sectionRowRefs(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('#model-chain-section-body .mp-row')].map(r => r.textContent.trim()));
}

// ── ① 生图层级下钻浮层（JeV 同链路由 jev-panel-threefix R1b 守）───────────
test('U1 imagegen entry row drills into a glass overlay (adopts the body); close restores it hidden', async ({ page }) => {
  const errors = watchErrors(page);
  await bootPage(page);
  await openSettings(page);

  // 入口行在场：标题 + 右转 chevron；内容面隐藏未收编。
  const entry = await page.evaluate(() => {
    const head = document.getElementById('imagegen-advance-toggle');
    const body = document.getElementById('imagegen-advance-body');
    return {
      label: head?.querySelector('.settings-label')?.textContent.trim() || '',
      chev: head ? getComputedStyle(head.querySelector('.settings-collapse-chevron')).transform : null,
      bodyHidden: !!body && body.hidden === true,
      adopted: !!body?.closest('.config-drill-overlay'),
    };
  });
  expect(entry.label, 'entry row carries the section name').toBe('生图');
  expect(entry.chev, 'chevron points right (层级导航 affordance)').toContain('matrix');
  expect(entry.bodyHidden, 'content face hidden in the settings page').toBe(true);
  expect(entry.adopted, 'not adopted before drill-in').toBe(false);

  // 点入口行 → 浮层收编整节点（imagegenSection 渲染面随节点走、即时可用）。
  await page.click('#imagegen-advance-toggle');
  await page.waitForFunction(() => {
    const body = document.getElementById('imagegen-advance-body');
    return body && !body.hidden && !!body.closest('.config-drill-overlay');
  });
  const overlay = page.locator('.config-drill-overlay');
  await expect(overlay).toBeVisible();
  await expect(page.locator('.config-drill-overlay .config-drill-title')).toHaveText('生图');
  await expect(page.locator('.config-drill-overlay #imagegen-section-wrap .cfg-form-group')).toBeVisible();
  await expect(page.locator('.config-drill-overlay #cfg-imagegen-model')).toHaveValue('cogview-3-flash');

  // 点空白处关闭 → 节点归还设置页、恢复 hidden；设置页内无残留浮层。
  await page.mouse.click(10, 400);
  await page.waitForFunction(() => {
    const body = document.getElementById('imagegen-advance-body');
    return body?.hidden === true && !body.closest('.config-drill-overlay')
      && !document.querySelector('.config-drill-overlay');
  });
  expect(errors).toEqual([]);
});

// ── ②-U2 回退链节：显式链渲染 + 加模型保存形状 ────────────────────────────
test('U2 chain section renders the explicit chain (same .mp-row face as /model) and add-model saves via PUT', async ({ page }) => {
  const errors = watchErrors(page);
  const h = await bootPage(page);
  await openSettings(page);
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 2, null, { timeout: 5000 });

  const rows = await sectionRowRefs(page);
  expect(rows.length, 'chain rows = preferred + fallback').toBe(2);
  expect(rows[0], 'first row = the preferred model').toContain('kimi-k2');
  expect(rows[0], 'first row carries the 首选 badge').toContain('首选');
  expect(rows[1], 'second row = the fallback model').toContain('anon-1');

  // 加模型：手动输入腿（下拉按 provider 分组、与 /model 编辑器同款选择面；
  // 手动输入回车 = 第三个模型入链尾部）。
  await page.click('#model-chain-section-body .mp-manual-toggle');
  await page.fill('#model-chain-section-body .mp-manual', 'glm/glm-4');
  await page.press('#model-chain-section-body .mp-manual', 'Enter');

  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 3, null, { timeout: 5000 });
  expect(h.putBodies.length, 'exactly one PUT (per-action save)').toBe(1);
  expect(h.putBodies[0].model, 'PUT body = the full new chain').toEqual({
    preferred: 'kimi/kimi-k2', fallbacks: ['anon/anon-1', 'glm/glm-4'],
  });
  expect(errors).toEqual([]);
});

// ── ②-U2b 拖拽排序：指针版拖拽（手柄）+ 触屏滚动守卫 ─────────────────────
// 手机端 /model 面滚动/拖动修（2026-10-31 作者报障①）：行不再 draggable=true
// （HTML5 DnD 触屏不派发 + iOS WebKit 上 draggable 行吞原生滚动——整面推不动
// 的根因）；排序改手柄指针拖拽（.mp-drag，touch-action:none），行体其余部分
// 保留原生滚动。
test('U2b grip pointer-drag reorders the chain (PUT carries the new order); rows carry no draggable attr', async ({ page }) => {
  const errors = watchErrors(page);
  const h = await bootPage(page);
  await openSettings(page);
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 2, null, { timeout: 5000 });

  // 触屏守卫：行无 draggable 属性（原生滚动不被吞）+ 手柄 touch-action:none
  // （手柄面起拖不被浏览器手势接管）。
  const guard = await page.evaluate(() => {
    const row = document.querySelector('#model-chain-section-body .mp-row');
    const grip = row?.querySelector('.mp-drag');
    return {
      draggable: row?.hasAttribute('draggable'),
      gripTouchAction: grip ? getComputedStyle(grip).touchAction : null,
    };
  });
  expect(guard.draggable, 'rows must not be draggable=true (mobile native scroll)').toBe(false);
  expect(guard.gripTouchAction, 'grip handle opts out of browser gestures').toBe('none');

  // 指针拖拽：fallback 行（idx1）的手柄拖到首选行（idx0）上方 ⇒ 新链头。
  // 原始 mouse 调用不自动滚屏——先把行滚进视口（视口外 pointer 事件落空）。
  const grip = page.locator('#model-chain-section-body .mp-row[data-idx="1"] .mp-drag');
  const target = page.locator('#model-chain-section-body .mp-row[data-idx="0"]');
  await target.scrollIntoViewIfNeeded();
  const g = await grip.boundingBox();
  const t = await target.boundingBox();
  expect(g && t, 'rows laid out').toBeTruthy();
  await page.mouse.move(g.x + g.width / 2, g.y + g.height / 2);
  await page.mouse.down();
  await page.mouse.move(g.x + g.width / 2, t.y + 4, { steps: 8 });
  await page.mouse.up();

  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 2, null, { timeout: 5000 });
  await expect.poll(() => h.putBodies.length, { timeout: 5000 }).toBeGreaterThanOrEqual(1);
  expect(h.putBodies[0].model, 'PUT body = reordered chain (dragged row becomes the head)').toEqual({
    preferred: 'anon/anon-1', fallbacks: ['kimi/kimi-k2'],
  });
  // 重渲腿：putChain 成功 → emit → 节重渲（行文立刻读会撞 PUT 记录与重渲之间的窗口）。
  await page.waitForFunction(() => document.querySelector('#model-chain-section-body .mp-row[data-idx="0"] .mp-model')?.textContent?.includes('anon-1'), null, { timeout: 5000 });
  const rows = await sectionRowRefs(page);
  expect(rows[0], 'new head row = the dragged model').toContain('anon-1');
  expect(rows[0], '首选 badge follows the new head').toContain('首选');
  expect(errors).toEqual([]);
});

// ── ②-U3 双向往返即时同步（同一 roleState/保存通道）────────────────────────
test('U3 edits sync both ways: panel change re-renders the settings section and vice versa', async ({ page }) => {
  const errors = watchErrors(page);
  const h = await bootPage(page);
  await openSettings(page);
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 2, null, { timeout: 5000 });

  // 打开 /model 面板（原入口保留不动——module seam 同 UI 命令一路）。
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  await expect(page.locator('#model-panel-overlay')).toBeVisible();
  await page.waitForFunction(() => document.querySelectorAll('#model-panel-overlay .mp-roleblock-nebula .mp-row').length === 2, null, { timeout: 5000 });

  // 面板侧移除 fallback ⇒ PUT + 设置节即时重渲成 1 行。
  await page.click('#model-panel-overlay .mp-roleblock-nebula .mp-remove[data-idx="1"]');
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 1, null, { timeout: 5000 });
  const rowsAfterPanelEdit = await sectionRowRefs(page);
  expect(rowsAfterPanelEdit.length, 'settings section down to the preferred row only').toBe(1);
  expect(rowsAfterPanelEdit[0], 'the surviving row is the preferred model').toContain('kimi-k2');

  // 设置节侧加回 fallback ⇒ 面板即时重渲回 2 行。
  // 面板浮层（z10000）盖着设置页——设置节在浮层底下，物理点击被承接层截胡；
  // 同步腿断言用合成事件直驱（模块监听挂在元素本体的 keydown，冒泡即达）。
  await page.evaluate(() => {
    document.querySelector('#model-chain-section-body .mp-manual-toggle')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const input = document.querySelector('#model-chain-section-body .mp-manual');
    if (input) {
      input.value = 'anon/anon-1';
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    }
  });
  await page.waitForFunction(() => document.querySelectorAll('#model-panel-overlay .mp-roleblock-nebula .mp-row').length === 2, null, { timeout: 5000 });
  expect(h.putBodies.length, 'two saves, one channel').toBe(2);
  expect(errors).toEqual([]);
});

// ── ②-U4 seed 态：seed 注记 + 预览 + 独立配置 fork ─────────────────────────
test('U4 seed state shows the seed note + effective preview; 独立配置 forks an explicit chain', async ({ page }) => {
  const errors = watchErrors(page);
  const h = await bootPage(page, { chain: null });
  await openSettings(page);
  await page.waitForFunction(() => !!document.querySelector('#model-chain-section-body .mp-note-seed'), null, { timeout: 5000 });

  const seed = await page.evaluate(() => ({
    note: document.querySelector('#model-chain-section-body .mp-note-seed')?.textContent.trim() || '',
    preview: document.querySelector('#model-chain-section-body .mp-preview .mchain') !== null,
    editorAbsent: document.querySelectorAll('#model-chain-section-body .mp-row').length === 0,
    forkLabel: document.querySelector('#model-chain-section-body .chain-section-face button')?.textContent.trim() || '',
  }));
  expect(seed.note.length, 'seed note present').toBeGreaterThan(0);
  expect(seed.preview, 'effective (seed) chain preview chips').toBe(true);
  expect(seed.editorAbsent, 'no chain editor in seed state').toBe(true);
  expect(seed.forkLabel, 'the fork action names 独立配置').toBe('独立配置');

  await page.click('#model-chain-section-body .chain-section-face button');
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 1, null, { timeout: 5000 });
  expect(h.putBodies.length, 'fork saves immediately').toBe(1);
  expect(h.putBodies[0].model, 'fork seeds from the effective chain').toEqual({ preferred: 'seed/seed-model', fallbacks: [] });
  expect(errors).toEqual([]);
});

// ── ②-U5 读数失败 ⇒ 节级显式降级面 + 重试恢复 ──────────────────────────────
test('U5 unreadable model data degrades to an explicit error face with retry (never a silent dead section)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootPage(page, { modelFail: true });
  await openSettings(page);
  await page.waitForFunction(() => !!document.querySelector('#model-chain-section-body .chain-section-error'), null, { timeout: 5000 });

  const degraded = await page.evaluate(() => ({
    alert: document.querySelector('#model-chain-section-body .chain-section-error')?.getAttribute('role'),
    title: document.querySelector('#model-chain-section-body .chain-section-error .mp-load-error-title')?.textContent.trim() || '',
    hint: document.querySelector('#model-chain-section-body .chain-section-error .mp-load-error-hint')?.textContent.trim() || '',
    retry: !!document.querySelector('#model-chain-section-body .chain-section-error .mp-load-error-retry'),
    rows: document.querySelectorAll('#model-chain-section-body .mp-row').length,
  }));
  expect(degraded.alert, 'role=alert').toBe('alert');
  expect(degraded.title, 'failure is named').toContain('加载失败');
  expect(degraded.hint.length, 'reason hint present').toBeGreaterThan(0);
  expect(degraded.retry, 'recovery entry present').toBe(true);
  expect(degraded.rows, 'no half-dead editor behind the error face').toBe(0);

  // 重试：服务恢复 ⇒ 降级面拆除、编辑器出现。
  await page.evaluate(() => { window.__cduFail = false; });
  // 翻转 mock：直接重注册一条更具体的路由（后注册优先）。
  await page.route('**/api/agents/*/model', (r) => r.fulfill({
    json: {
      mode: 'explicit', chain: { preferred: 'kimi/kimi-k2', fallbacks: [] },
      effectiveChain: { preferred: 'kimi/kimi-k2', fallbacks: [] },
      resolvedFrom: 'own-chain', current: 'kimi/kimi-k2', settable: true,
    },
  }));
  await page.click('#model-chain-section-body .mp-load-error-retry');
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 1, null, { timeout: 5000 });
  expect(errors).toEqual([]);
});
