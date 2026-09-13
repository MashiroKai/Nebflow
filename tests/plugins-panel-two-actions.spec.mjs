// plugins-panel-two-actions.spec.mjs — 2026-09-13 无审批批（装了就是信任）验收 spec
// 真后端（隔离实例）——重写自 2026-09-12 令 1（R2/F1）的「两控件并存」版本：
// 内容审批开关**退场**后，卡片上仍是**两个视觉可分、各打各端点**的动作：
//   • 派发许可（`data-plugin-dispatch`，卡片底部独立一行，乐观翻转）
//   • 封禁/解封（`data-plugin-block`，右上「更多」菜单内的**次级动作**，不做乐观翻转）
//
// 断言链：
//   ① 主控件退场：`[data-plugin-switch]` 页面级零残留（C7）
//   ② 两个动作同时存在且**视觉可分**：钩子分家（data-plugin-block vs
//      data-plugin-dispatch）、标签分家（locale 真值 plugins.blockAction /
//      plugins.dispatchLabel，不硬编码文案）、几何可分（派发行在「更多」按钮
//      与其菜单行**下方**、上缘有 hairline 分隔、行盒非零）
//   ③ 状态药丸绑 blocked / contentChanged（🔴 不绑 trusted）：未封禁 ⇒ 已启用
//   ④ **无记录包首扫即受信并在面板表现为已启用**（正面断言，本批的核心口径）：
//      fixture 插件在实例上没有 trust 记录 ⇒ 卡片「已启用」+ 派发开关可用
//   ⑤ 零静默：封禁动作必打 POST /revoke，且**必须落到注册表**——注册表回
//      blocked 或（未落地时）出 error toast，二者必居其一，绝不 ok:true 静默
//   ⑥ 解封打 POST /unblock；两条动作的 wire 互不污染
//
// 端点与 C6 字段由**两轨共用冻结契约 v1** 固定：
//   POST /api/plugins/:name/revoke  = 封禁（路径名保留，语义翻转）
//   POST /api/plugins/:name/unblock = 解封（新增）
//   GET  /api/plugins 每项含 trusted / blocked / contentChanged
// 引擎轨（plugin-nogate-impl-engine）未合入时，`blocked` / `contentChanged`
// 不在载荷里 ⇒ 依赖这两个字段的用例**显式 skip**（不是静默降级，与
// 「未设 NEBFLOW_HOME_DIR 时显式 skip」同一纪律）。
//
// 未设 NEBFLOW_HOME_DIR 时显式 skip（shell 级 mock 覆盖由
// sidebar-plugins.spec.mjs / plugins-panel-autosync.spec.mjs 承担——三层互补）。
// 实例：NEBFLOW_URL（默认 127.0.0.1:8285，隔离端口，绝非宿主 8080）+ 该实例的
// NEBFLOW_HOME_DIR（fixture 写在 <HOME>/plugins/ 下）。

import { test, expect } from '@playwright/test';
import { mkdir, rm, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const BASE = process.env.NEBFLOW_URL || 'http://127.0.0.1:8285';
const HOME = process.env.NEBFLOW_HOME_DIR || '';
const LOCALE = process.env.PLUGINS_SPEC_LOCALE || 'zh-CN';
const PLUGIN = 'spec-two-actions';

const SCHEMA = 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json';

function pluginJson(name) {
  return JSON.stringify({
    $schema: SCHEMA,
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} for the plugins-panel-two-actions spec`,
    author: { name: 'plugins-panel-two-actions spec' },
  }, null, 2);
}

function skillMd(name) {
  return `---
name: ${name}
description: Fixture skill ${name} for the two-action panel spec
---
Fixture body.
`;
}

/** Write a minimal skill-only plugin dir (plugin.json + skills/<id>/SKILL.md). */
async function writePlugin(pluginsDir, name, skill) {
  const dir = join(pluginsDir, name);
  await rm(dir, { recursive: true, force: true });
  await mkdir(join(dir, 'skills', skill), { recursive: true });
  await writeFile(join(dir, 'plugin.json'), pluginJson(name));
  await writeFile(join(dir, 'skills', skill, 'SKILL.md'), skillMd(skill));
  return dir;
}

/** nebflow.json 的 plugins 面快照（落盘事实源）。 */
async function diskPlugins() {
  const raw = await readFile(join(HOME, 'nebflow.json'), 'utf8');
  return JSON.parse(raw).plugins || {};
}

/** 引擎轨 C6 字段是否已在载荷里（不在 ⇒ 相关用例显式 skip）。 */
let hasC6 = false;

test.describe.configure({ mode: 'serial' });

test.describe('plugins panel — dispatch permission vs block (real backend)', () => {
  test.skip(!HOME, 'NEBFLOW_HOME_DIR not set — isolated-instance E2E skipped (shell-level specs cover the mocked path)');

  let token = '';
  let pluginsDir = '';

  test.beforeAll(async () => {
    try {
      const auth = JSON.parse(await readFile(join(HOME, 'auth.json'), 'utf8'));
      token = typeof auth === 'string' ? auth : (auth.token || auth.authToken || '');
    } catch { token = ''; }
    if (!token) throw new Error(`no token in ${join(HOME, 'auth.json')} — is the isolated instance up at ${BASE}?`);
    pluginsDir = join(HOME, 'plugins');
    await writePlugin(pluginsDir, PLUGIN, 'two-actions-fixture');
    const probe = await fetch(`${BASE}/api/plugins`, { headers: { Authorization: `Bearer ${token}` } });
    const body = await probe.json().catch(() => ({}));
    const item = (body.plugins || []).find((p) => p.name === PLUGIN);
    hasC6 = !!item && Object.prototype.hasOwnProperty.call(item, 'blocked')
      && Object.prototype.hasOwnProperty.call(item, 'contentChanged');
  });

  /** Node-side REST helper (Bearer auth). */
  async function rest(method, path) {
    const resp = await fetch(BASE + path, { method, headers: { Authorization: `Bearer ${token}` } });
    let body = null;
    try { body = await resp.json(); } catch { body = await resp.text(); }
    return { status: resp.status, body };
  }

  /** 起点必须是「未封禁」——幂等解封兜底（老实例无 /unblock ⇒ 404，忽略）。 */
  async function unblockQuietly(name) {
    await rest('POST', `/api/plugins/${name}/unblock`).catch(() => {});
  }

  async function waitDispatchLive(page, name) {
    await page.waitForFunction((plugin) => {
      const dw = document.querySelector(`.plugins-card[data-plugin="${plugin}"] [data-plugin-dispatch]`);
      return !!dw && !dw.disabled;
    }, name, { timeout: 10000 });
  }

  async function loadPluginsPage(page) {
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.addInitScript((tok) => {
      localStorage.clear();
      localStorage.setItem('nebflow_token', tok);
      localStorage.setItem('neblink_token', tok);
    }, token);
    await page.goto(BASE + '/');
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
    await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${PLUGIN}"]`, { timeout: 20000 });
  }

  /** locale 真值（页内动态 import 真实模块，避免把文案硬编码进断言）。 */
  async function locale(page, key) {
    return page.evaluate(async ([loc, k]) => {
      const mod = await import(`/js/locales/${loc}.js`);
      return mod.default[k];
    }, [LOCALE, key]);
  }

  async function cardState(page, name) {
    return page.evaluate((plugin) => {
      const card = document.querySelector(`.plugins-card[data-plugin="${plugin}"]`);
      const more = card?.querySelector('[data-plugin-more]');
      const menu = card?.querySelector('[data-plugin-menu]');
      const btn = card?.querySelector('[data-plugin-block]');
      const dw = card?.querySelector('[data-plugin-dispatch]');
      const row = card?.querySelector('.plugins-card-dispatch');
      const note = card?.querySelector('.plugins-dispatch-note');
      const comp = row ? getComputedStyle(row) : null;
      const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height, bottom: b.bottom }; };
      return {
        cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
        // ① 主控件退场
        contentSwitchCount: document.querySelectorAll('#plugins-content [data-plugin-switch]').length,
        // ② 两个动作
        hasMore: !!more,
        hasBlock: !!btn,
        hasDispatch: !!dw,
        blockLabel: btn?.textContent.trim() ?? null,
        blockState: btn?.dataset.blocked ?? null,
        blockBox: box(btn),
        moreBox: box(more),
        menuHidden: menu ? menu.hidden : null,
        menuBox: box(menu),
        dispatchRole: dw?.getAttribute('role') ?? null,
        dispatchLabel: dw?.getAttribute('aria-label') ?? null,
        rowLabelText: row?.querySelector('.plugins-dispatch-label')?.textContent.trim() ?? null,
        rowBorderTop: comp ? comp.borderTopWidth : null,
        rowBox: box(row),
        dispatchDisabled: dw ? dw.disabled : null,
        dispatchBlocked: dw?.getAttribute('data-dispatch-blocked') ?? null,
        dispatchOn: dw?.classList.contains('on') ?? null,
        noteHidden: note ? note.hidden : null,
        noteText: note?.textContent.trim() ?? null,
        // ③ 药丸
        pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
        pillClass: card?.querySelector('.plugins-state-pill')?.className ?? null,
        cardClass: card?.className ?? null,
      };
    }, name);
  }

  test('①+②+③ 主控件退场；封禁（次级）与派发（开关）并存且视觉可分；药丸绑新字段', async ({ page }) => {
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    let st = await cardState(page, PLUGIN);
    // ② 需要「更多」菜单展开才能量到封禁入口的几何（入口本身先断言存在）。
    expect(st.hasMore, '次级动作入口「更多」存在').toBe(true);
    expect(st.hasBlock, '封禁/解封动作存在').toBe(true);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-more]`);
    await page.waitForFunction((n) =>
      !document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-menu]`)?.hidden, PLUGIN, { timeout: 5000 });
    st = await cardState(page, PLUGIN);

    const [blockLabel, dispatchLabel, pillOn] = await Promise.all([
      locale(page, 'plugins.blockAction'),
      locale(page, 'plugins.dispatchLabel'),
      locale(page, 'plugins.stateOn'),
    ]);

    // ① 主控件退场
    expect(st.contentSwitchCount, '内容审批开关页面级零残留（C7 主控件退场）').toBe(0);

    // ② 两动作存在 + 钩子/标签/形态分家
    expect(st.hasDispatch, '派发控件存在（既有钩子 data-plugin-dispatch）').toBe(true);
    expect(st.dispatchRole, '派发控件 role=switch').toBe('switch');
    expect(st.dispatchLabel, '派发控件 aria-label = locale plugins.dispatchLabel').toBe(dispatchLabel);
    expect(st.rowLabelText, '派发行自有文字标签 = locale plugins.dispatchLabel').toBe(dispatchLabel);
    expect(st.blockLabel, '封禁入口文案 = locale plugins.blockAction').toBe(blockLabel);
    expect(st.blockLabel === st.dispatchLabel, '两动作文案必须不同（可辨）').toBe(false);
    expect(st.menuBox.w > 0 && st.menuBox.h > 0, '次级动作行真实渲染（有非零盒）').toBe(true);
    expect(st.blockBox.w > 0 && st.blockBox.h > 0, '封禁按钮真实渲染（有非零盒）').toBe(true);
    expect(st.rowBox.w > 0 && st.rowBox.h > 0, '派发行真实渲染（有非零盒）').toBe(true);
    expect(parseFloat(st.rowBorderTop), '派发行上缘 hairline 分隔（视觉可分）').toBeGreaterThan(0);
    expect(st.rowBox.y >= st.moreBox.bottom - 1, '派发行在「更多」按钮下方（位置分离）').toBe(true);
    expect(st.rowBox.y >= st.blockBox.bottom + 1, '派发行在封禁按钮行下方（不挨着）').toBe(true);

    // ③ 药丸绑 blocked/contentChanged（未封禁 ⇒ 已启用）
    expect(st.pill, '状态药丸 = 已启用（绑 blocked/contentChanged，不绑 trusted）').toBe(pillOn);
    expect(st.pillClass, 'pill 带 on class').toContain('on');
    expect(st.blockState, 'data-blocked=0（未封禁）').toBe('0');
    expect(st.dispatchDisabled, '未封禁 ⇒ 派发开关可用').toBe(false);
  });

  test('④ 无记录包首扫即受信：面板表现为「已启用」且派发开关可用（正面断言）', async ({ page }) => {
    // fixture 插件在本实例上**没有 trust 记录**（原「never approved (default-deny)」
    // 场景）——无审批批后它必须在面板上表现为「已启用」，且派发开关不再要求
    // 「先审批内容」（那个前提已消失）。
    await unblockQuietly(PLUGIN);
    const { body } = await rest('GET', '/api/plugins');
    const man = (body.plugins || []).find((p) => p.name === PLUGIN);
    expect(!!man, 'fixture 插件已被扫描装载（8 步内出现在注册表）').toBe(true);

    await loadPluginsPage(page);
    const st = await cardState(page, PLUGIN);
    const pillOn = await locale(page, 'plugins.stateOn');
    expect(st.pill, '无记录包在面板上表现为已启用').toBe(pillOn);
    expect(st.pillClass, 'pill 带 on class').toContain('on');
    expect(st.cardClass, '卡片无 blocked/changed 痕').not.toContain('blocked');
    await waitDispatchLive(page, PLUGIN);
    const again = await cardState(page, PLUGIN);
    expect(again.dispatchDisabled, '派发开关可用（不再要求先审批内容）').toBe(false);
    expect(again.dispatchBlocked, '无 data-dispatch-blocked（「未受信」阻断前提消失）').toBe(null);
    expect(again.noteHidden, '无「先审批内容」注记').toBe(true);
    expect(again.contentSwitchCount, '内容审批开关零残留').toBe(0);
  });

  test('⑤ 零静默：封禁 ⇒ POST /revoke，且必须落到注册表（否则 error toast，绝不静默）', async ({ page }) => {
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes(`/api/plugins/${PLUGIN}/`)) posts.push(new URL(req.url()).pathname);
    });
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-more]`);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-block]`);
    // 终态：要么（C6 在线）卡片转「已封禁」，要么（C6 未上线）出一条 error toast
    // ——两者必居其一 = 「动作必须产生可行动结果」。
    const outcome = await page.waitForFunction((n) => {
      const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
      const pill = card?.querySelector('.plugins-state-pill')?.textContent.trim();
      const toast = document.querySelector('.nebflow-toast-error');
      if (toast) return { kind: 'toast', text: toast.textContent.trim() };
      if (pill === '已封禁') return { kind: 'blocked', text: pill };
      return null;
    }, PLUGIN, { timeout: 10000 }).then((h) => h.jsonValue());

    expect(posts.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/revoke`)).length,
      `封禁必须（且只）打 /revoke，got ${posts}`).toBe(1);
    expect(posts.some((p) => p.endsWith('/approve')), 'UI 主线绝不打 /approve').toBe(false);
    expect(['blocked', 'toast'], '零静默：终态必须是已封禁或显式报错').toContain(outcome.kind);

    if (outcome.kind === 'blocked') {
      // C6 在线：封禁态必须与注册表一致 + 派发开关同帧锁死。
      const st = await cardState(page, PLUGIN);
      expect(st.blockState, 'data-blocked=1').toBe('1');
      expect(st.pillClass, 'pill 带 blocked class').toContain('blocked');
      expect(st.dispatchDisabled, '封禁 ⇒ 派发开关 disabled').toBe(true);
      expect(st.dispatchBlocked, 'blocked 原因随元素暴露').toBe('blocked');
      expect(st.noteHidden, '可行动注记可见').toBe(false);
      expect(st.noteText.length, '注记非空（可行动指引）').toBeGreaterThan(0);
      const { body } = await rest('GET', '/api/plugins');
      const man = (body.plugins || []).find((p) => p.name === PLUGIN);
      expect(man?.blocked, 'REST 真值：blocked=true（落盘事实源）').toBe(true);
      // 清场：解封（下一用例与后续批次都要求未封禁起点）。
      await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-block]`);
      await page.waitForFunction((n) =>
        document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.textContent.trim() !== '已封禁',
        PLUGIN, { timeout: 10000 });
    }
  });

  test('⑥ 解封 ⇒ POST /unblock；与派发动作的 wire 互不污染', async ({ page }) => {
    test.skip(!hasC6, 'engine track C6 (`blocked`) not live on this instance — /unblock cannot move the state yet; rerun after the plugin-nogate engine track merges');
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    // 先封禁（走真后端），再解封 —— 两条 wire 各打各的端点。
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-more]`);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-block]`);
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.textContent.trim() === '已封禁',
      PLUGIN, { timeout: 10000 });

    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST') posts.push(new URL(req.url()).pathname);
    });
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-block]`);
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.textContent.trim() !== '已封禁',
      PLUGIN, { timeout: 10000 });
    const st = await cardState(page, PLUGIN);
    const pillOn = await locale(page, 'plugins.stateOn');
    expect(posts.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/unblock`)).length,
      `解封必须（且只）打 /unblock，got ${posts}`).toBe(1);
    expect(posts.some((p) => p.endsWith('/enable') || p.endsWith('/disable')),
      '解封动作不得写派发面').toBe(false);
    expect(st.pill, '解封后回「已启用」').toBe(pillOn);
    expect(st.dispatchDisabled, '派发开关解锁').toBe(false);

    // 派发动作仍打自己的端点（且不掺 /revoke）。
    const posts2 = [];
    page.on('request', (req) => {
      if (req.method() === 'POST') posts2.push(new URL(req.url()).pathname);
    });
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-dispatch]`);
    await page.waitForFunction((n) =>
      !document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-dispatch]`)?.disabled,
      PLUGIN, { timeout: 10000 });
    expect(posts2.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/disable`)).length,
      `派发开关必须（且只）打 /disable，got ${posts2}`).toBe(1);
    expect(posts2.some((p) => p.endsWith('/revoke')), '派发动作不得打 /revoke').toBe(false);
    const after = await diskPlugins();
    expect(after.dispatch?.[PLUGIN]?.authorEnabled, '落盘：dispatch.authorEnabled=false').toBe(false);
  });
});
