// plugins-panel-two-actions.spec.mjs — 2026-09-12 令 1 收口（R2/F1）验收 spec
// 真后端（隔离实例）——面板上「内容审批（approve/revoke）」与「派发许可
// （enable/disable）」是**两个视觉可分、各打各端点**的动作，且未受信插件上
// **不存在静默空操作**（那正是 R1 复核 F1 的返工点）。
//
// 断言链：
//   ① 两控件并存 + 钩子/标签分家（role=switch ×2；aria-label 与行标签取自
//      locale 真值 plugins.switchLabel / plugins.dispatchLabel，不硬编码文案）
//   ② 视觉可分（几何 + 分隔线）：派发行在内容开关行**下方**（rowTop ≥
//      switchBottom）、行盒可见、上缘有 hairline 分隔（border-top ≠ 0）
//   ③ 未受信 ⇒ 派发控件 disabled + data-dispatch-blocked=untrusted + 一行
//      可行动注记（文案 = locale plugins.dispatchBlockedUntrusted）；内容开关
//      可点（enabled）
//   ④ 零静默：对 disabled 的派发控件 force-click ⇒ **零** POST 发出（R1 复核
//      的「ok:true + 静默无效果」形态在面板上不可达）
//   ⑤ 可行动结果：点内容开关 ⇒ approve（走真后端）⇒ 卡片转受信（pill/开关/
//      派发行全部可见变化：blocked 清除、disabled 解除、注记消失）
//   ⑥ 两动作各打各的端点（wire 断言）+ 落盘分离：派发开关 ⇒ POST /disable
//      且**不**发 /revoke，落盘只写 plugins.dispatch 而 plugins.trust 摘要未动；
//      内容开关 ⇒ POST /revoke（不是 /disable），落盘 trust 条目消失而
//      plugins.dispatch 记录保持
//
// 未设 NEBFLOW_HOME_DIR 时显式 skip（shell 级 mock 覆盖由
// sidebar-plugins.spec.mjs 承担——两层互补，不静默降级）。
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

test.describe.configure({ mode: 'serial' });

test.describe('plugins panel — content approval vs dispatch permission (real backend)', () => {
  test.skip(!HOME, 'NEBFLOW_HOME_DIR not set — isolated-instance E2E skipped (shell-level spec covers the mocked path)');

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
  });

  /** Node-side REST helper (Bearer auth). */
  async function rest(method, path) {
    const resp = await fetch(BASE + path, { method, headers: { Authorization: `Bearer ${token}` } });
    let body = null;
    try { body = await resp.json(); } catch { body = await resp.text(); }
    return { status: resp.status, body };
  }

  /** 起点必须是「内容未受信」——never-approved fixture（幂等 revoke 兜底）。 */
  async function revokeQuietly(name) {
    await rest('POST', `/api/plugins/${name}/revoke`);
  }

  /** 开关在途闩：click 后乐观翻转 + disabled，收敛/回滚时释放 ⇒ 等闩释放 =
   *  等后端事务落地（与插件面板既有契约一致）。 */
  async function waitSettled(page, name, hook) {
    await page.waitForFunction(([plugin, sel]) => {
      const el = document.querySelector(`.plugins-card[data-plugin="${plugin}"] ${sel}`);
      return !!el && !el.disabled;
    }, [name, hook], { timeout: 10000 });
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

  async function twoControlState(page, name) {
    return page.evaluate((plugin) => {
      const card = document.querySelector(`.plugins-card[data-plugin="${plugin}"]`);
      const sw = card?.querySelector('[data-plugin-switch]');
      const dw = card?.querySelector('[data-plugin-dispatch]');
      const row = card?.querySelector('.plugins-card-dispatch');
      const note = card?.querySelector('.plugins-dispatch-note');
      const comp = row ? getComputedStyle(row) : null;
      return {
        hasSwitch: !!sw,
        hasDispatch: !!dw,
        switchRole: sw?.getAttribute('role') ?? null,
        dispatchRole: dw?.getAttribute('role') ?? null,
        switchLabel: sw?.getAttribute('aria-label') ?? null,
        dispatchLabel: dw?.getAttribute('aria-label') ?? null,
        rowLabelText: row?.querySelector('.plugins-dispatch-label')?.textContent.trim() ?? null,
        rowBorderTop: comp ? comp.borderTopWidth : null,
        rowBox: row ? [row.getBoundingClientRect().width, row.getBoundingClientRect().height] : null,
        rowTop: row?.getBoundingClientRect().top ?? null,
        switchBottom: sw?.getBoundingClientRect().bottom ?? null,
        dispatchDisabled: dw ? dw.disabled : null,
        dispatchBlocked: dw?.getAttribute('data-dispatch-blocked') ?? null,
        dispatchOn: dw?.classList.contains('on') ?? null,
        switchOn: sw?.classList.contains('on') ?? null,
        switchDisabled: sw ? sw.disabled : null,
        noteHidden: note ? note.hidden : null,
        noteText: note?.textContent.trim() ?? null,
        pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
      };
    }, name);
  }

  test('①+②+③ 两面可分：两控件并存、标签分家、派发行独立成行；未受信 ⇒ 派发控件 disabled + 可行动注记', async ({ page }) => {
    await revokeQuietly(PLUGIN);
    await loadPluginsPage(page);
    const st = await twoControlState(page, PLUGIN);
    const [switchLabel, dispatchLabel, blockedNote] = await Promise.all([
      locale(page, 'plugins.switchLabel'),
      locale(page, 'plugins.dispatchLabel'),
      locale(page, 'plugins.dispatchBlockedUntrusted'),
    ]);
    expect(st.hasSwitch, '内容开关存在（既有契约钩子 data-plugin-switch）').toBe(true);
    expect(st.hasDispatch, '派发控件存在（新钩子 data-plugin-dispatch）').toBe(true);
    expect(st.switchRole, '内容开关 role=switch').toBe('switch');
    expect(st.dispatchRole, '派发控件 role=switch').toBe('switch');
    expect(st.switchLabel, '内容开关 aria-label = locale plugins.switchLabel').toBe(switchLabel);
    expect(st.dispatchLabel, '派发控件 aria-label = locale plugins.dispatchLabel').toBe(dispatchLabel);
    expect(st.switchLabel === st.dispatchLabel, '两控件标签必须不同（可辨）').toBe(false);
    expect(st.rowLabelText, '派发行自有文字标签 = locale plugins.dispatchLabel').toBe(dispatchLabel);
    expect(st.rowBox[0] > 0 && st.rowBox[1] > 0, '派发行真实渲染（有非零盒）').toBe(true);
    expect(parseFloat(st.rowBorderTop), '派发行上缘 hairline 分隔（视觉可分）').toBeGreaterThan(0);
    expect(st.rowTop >= st.switchBottom - 1, '派发行在内容开关行下方（独立成行）').toBe(true);
    // ③ 未受信插件：派发控件 blocked（不可点）+ 可行动注记；内容开关可点
    expect(st.dispatchDisabled, '未受信 ⇒ 派发控件 disabled').toBe(true);
    expect(st.dispatchBlocked, 'blocked 原因随元素暴露').toBe('untrusted');
    expect(st.noteHidden, '可行动注记可见').toBe(false);
    expect(st.noteText, '注记文案 = locale plugins.dispatchBlockedUntrusted').toBe(blockedNote);
    expect(st.noteText.length, '注记非空（可行动指引）').toBeGreaterThan(0);
    expect(st.switchDisabled, '内容开关保持可点（approve 是本状态下的可行动作）').toBe(false);
  });

  test('④ 零静默：force-click disabled 的派发控件 ⇒ 零 POST 发出', async ({ page }) => {
    await revokeQuietly(PLUGIN);
    await loadPluginsPage(page);
    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes(`/api/plugins/${PLUGIN}/`)) posts.push(new URL(req.url()).pathname);
    });
    const st = await twoControlState(page, PLUGIN);
    expect(st.dispatchDisabled, 'precondition: 派发控件 disabled').toBe(true);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-dispatch]`, { force: true }).catch(() => {});
    await page.waitForTimeout(1200);
    expect(posts, '未受信插件上对派发控件的点击不得发出任何写请求（无 ok:true+静默路径）').toEqual([]);
    const again = await twoControlState(page, PLUGIN);
    expect(again.dispatchOn, '控件状态未被静默改写').toBe(st.dispatchOn);
    expect(again.dispatchBlocked, 'blocked 状态保持').toBe('untrusted');
  });

  test('⑤ 可行动结果：点内容开关 = approve ⇒ 卡片转受信（派发控件同帧解锁）', async ({ page }) => {
    await revokeQuietly(PLUGIN);
    await loadPluginsPage(page);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-switch]`);
    await waitSettled(page, PLUGIN, '[data-plugin-switch]');
    await waitDispatchLive(page, PLUGIN);
    const st = await twoControlState(page, PLUGIN);
    const pillOn = await locale(page, 'plugins.stateOn');
    expect(st.switchOn, '内容开关 on').toBe(true);
    expect(st.pill, '状态 pill → 已启用').toBe(pillOn);
    expect(st.switchDisabled, '内容开关在途闩已释放').toBe(false);
    expect(st.dispatchDisabled, '派发控件解除 disabled（可操作）').toBe(false);
    expect(st.dispatchBlocked, 'blocked 属性被清除').toBe(null);
    expect(st.noteHidden, '可行动注记隐藏').toBe(true);
    expect(st.dispatchOn, '派发开关态 = 作者意图层（默认跟随内容受信）').toBe(true);
    const { body } = await rest('GET', '/api/plugins');
    const man = (body.plugins || []).find((p) => p.name === PLUGIN);
    expect(man?.trust?.status, 'REST 真值：内容面 trusted').toBe('trusted');
    expect(man?.dispatch?.enabled, 'REST 真值：派发面 effective=true').toBe(true);
  });

  test('⑥ 两动作各打各的端点 + 落盘分离（wire + nebflow.json 双证据）', async ({ page }) => {
    await revokeQuietly(PLUGIN);
    await loadPluginsPage(page);
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-switch]`); // approve
    await waitSettled(page, PLUGIN, '[data-plugin-switch]');
    await waitDispatchLive(page, PLUGIN);
    const trustShaBefore = (await diskPlugins()).trust?.[PLUGIN]?.sha256;

    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST') posts.push(new URL(req.url()).pathname);
    });
    // (a) 派发开关 off ⇒ /disable（且绝不 /revoke）
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-dispatch]`);
    await waitSettled(page, PLUGIN, '[data-plugin-dispatch]');
    const afterDisable = await diskPlugins();
    expect(posts.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/disable`)).length, `派发开关必须（且只）打 /disable，got ${posts}`).toBe(1);
    expect(posts.some((p) => p.endsWith(`/api/plugins/${PLUGIN}/revoke`)), '派发开关不得打 /revoke').toBe(false);
    expect(afterDisable.dispatch?.[PLUGIN]?.authorEnabled, '落盘：dispatch.authorEnabled=false').toBe(false);
    expect(afterDisable.trust?.[PLUGIN]?.sha256, '落盘：内容信任摘要（sha256）未动').toBe(trustShaBefore);

    // (b) 内容开关 off ⇒ /revoke（且不再追加 /disable）
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-switch]`);
    await waitSettled(page, PLUGIN, '[data-plugin-switch]');
    const afterRevoke = await diskPlugins();
    expect(posts.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/revoke`)).length, `内容开关必须（且只）打 /revoke，got ${posts}`).toBe(1);
    expect(posts.filter((p) => p.endsWith(`/api/plugins/${PLUGIN}/disable`)).length, '内容开关不得追加 /disable 写').toBe(1);
    expect(afterRevoke.trust?.[PLUGIN], '落盘：trust 条目消失（回落 untrusted）').toBe(undefined);
    expect(afterRevoke.dispatch?.[PLUGIN]?.authorEnabled, '落盘：dispatch 作者意图层保持（未被内容动作改写）').toBe(false);
    // revoke 后回到「未受信 ⇒ 派发控件 blocked」——零静默闭环
    const st = await twoControlState(page, PLUGIN);
    expect(st.dispatchDisabled, 'revoke 后派发控件回到 blocked（disabled）').toBe(true);
    expect(st.noteHidden, '可行动注记重新出现').toBe(false);
  });
});
