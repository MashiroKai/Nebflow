// plugins-panel-redesign.spec.mjs — 2026-09-13 无审批批（装了就是信任）验收 spec；
// **2026-09-14 插件面板收敛批（作者三裁之批一）再重写**。
// 真后端（隔离实例，NEBFLOW_HOME fixture + 端口 ≥8285，绝非宿主 8080）+ fixture
// 插件目录。重写自 2026-09-04 的「开关状态机」版本（内容审批开关退场）；本批再
// 砍掉封禁 UI 面，新链：
//   A 卡片形态（任意引擎）：无内容审批开关、无「更多」/封禁入口（卡片级零残留）；
//     药丸 = 已启用；**唯一控件** = 右上「任务分发器可见性」开关（在头行内、
//     与药丸同排、在药丸右侧、行盒右对齐）
//   B 目录可见性 + 内容变更**非拦截**（需引擎轨 C6 字段）：
//     无记录包 ⇒ 进分发器目录（在位即信任的可见结果）；
//     改 fixture 文件 ⇒ digest 变 ⇒ **仍进目录**、仍有派发许可，只在面板上标
//     「内容已变更」（可见性，不提供任何拦截）——这是与旧语义的分水岭
//   C 封禁（**引擎侧** API / CLI 发起——面板已无该入口）⇒ 出目录 + 面板「已封禁」
//     + 派发开关锁死 + 封禁态下也不得长回入口；解封 ⇒ 回目录（需 C6 字段）
//   D 双主题截图（形态留档）
//
// 未设 NEBFLOW_HOME_DIR 时显式 skip（shell 级 mock 覆盖由
// sidebar-plugins.spec.mjs 承担——两层互补，不静默降级）。
// 引擎轨 C6 字段（blocked / contentChanged）未合入时，B/C 显式 skip 并注明原因。

import { test, expect } from '@playwright/test';
import { mkdir, rm, writeFile, readFile, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.NEBFLOW_URL || 'http://127.0.0.1:8285';
const HOME = process.env.NEBFLOW_HOME_DIR || '';
const SHOTS = process.env.PLUGINS_SPEC_SHOTS || '/tmp';

const ALPHA = 'spec-plugin-alpha';
const BETA = 'spec-plugin-beta';

function pluginJson(name) {
  // §5.2/§5.3（PluginRegistry.CanonicalSchema）: manifest 缺 $schema 会被**拒载**
  // （`rejected[]`，卡片无控件）⇒ fixture 必须带 canonical $schema，否则本 spec
  // 的控件断言没有承载物。2026-09-12 R2 记账：此缺省为 spec 侧 fixture 缺陷
  // （main jar 上同红，见 impl-r2/11_r2-main-ablation.log），非产品行为。
  return JSON.stringify({
    $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json',
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} for the plugins-panel-redesign spec`,
    author: { name: 'plugins-panel-redesign spec' },
  }, null, 2);
}

function skillMd(body) {
  return `---
name: ${body.name}
description: ${body.description}
---
${body.line}
`;
}

/** Write a minimal skill-only plugin dir (plugin.json + skills/<id>/SKILL.md). */
async function writePlugin(pluginsDir, name, skillBody) {
  const dir = join(pluginsDir, name);
  await rm(dir, { recursive: true, force: true });
  await mkdir(join(dir, 'skills', skillBody.name), { recursive: true });
  await writeFile(join(dir, 'plugin.json'), pluginJson(name));
  await writeFile(join(dir, 'skills', skillBody.name, 'SKILL.md'), skillMd(skillBody));
  return dir;
}

/** 引擎轨 C6 字段是否已在载荷里（不在 ⇒ 依赖它们的用例显式 skip）。 */
let hasC6 = false;

test.describe.configure({ mode: 'serial' });

test.describe('plugins panel redesign — card form + non-blocking content change (real backend)', () => {
  test.skip(!HOME, 'NEBFLOW_HOME_DIR not set — isolated-instance E2E skipped (shell-level spec covers the mocked path)');

  let token = '';
  let pluginsDir = '';

  test.beforeAll(async () => {
    // Instance auth (auth.json = bare JSON string token; object shape tolerated).
    try {
      const auth = JSON.parse(await readFile(join(HOME, 'auth.json'), 'utf8'));
      token = typeof auth === 'string' ? auth : (auth.token || auth.authToken || '');
    } catch { token = ''; }
    if (!token) throw new Error(`no token in ${join(HOME, 'auth.json')} — is the isolated instance up at ${BASE}?`);

    pluginsDir = join(HOME, 'plugins');
    await writePlugin(pluginsDir, ALPHA, { name: 'greet', description: 'Greets from the alpha fixture', line: 'Hello from alpha.' });
    await writePlugin(pluginsDir, BETA, { name: 'echo', description: 'Echoes from the beta fixture', line: 'Echo from beta.' });

    const probe = await fetch(`${BASE}/api/plugins`, { headers: { Authorization: `Bearer ${token}` } });
    const body = await probe.json().catch(() => ({}));
    const item = (body.plugins || []).find((p) => p.name === ALPHA);
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

  /** catalog 是分发器目录段同源——断言插件名是否在内。 */
  async function catalogContains(name) {
    const { body } = await rest('GET', '/api/plugins/catalog');
    const text = typeof body?.catalog === 'string' ? body.catalog : JSON.stringify(body);
    return text.includes(`- ${name}:`);
  }

  /** 幂等解封（老实例无 /unblock ⇒ 404，忽略）。 */
  async function unblockQuietly(name) {
    await rest('POST', `/api/plugins/${name}/unblock`).catch(() => {});
  }

  /** Load the real shell against the isolated instance; the boot fallback
   *  opens the plugins page. Returns after the plugin card is visible. */
  async function loadPluginsPage(page, colorScheme = 'dark') {
    await page.emulateMedia({ colorScheme });
    await page.addInitScript((tok) => {
      localStorage.clear();
      localStorage.setItem('nebflow_token', tok);
      localStorage.setItem('neblink_token', tok);
    }, token);
    await page.goto(BASE + '/');
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
    await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${ALPHA}"]`, { timeout: 20000 });
  }

  async function cardState(page, name) {
    return page.evaluate((plugin) => {
      const card = document.querySelector(`.plugins-card[data-plugin="${plugin}"]`);
      const dw = card?.querySelector('[data-plugin-dispatch]');
      const pill = card?.querySelector('.plugins-state-pill');
      const head = card?.querySelector('.plugins-card-head');
      const geom = (() => {
        if (!card || !pill || !dw || !head) return null;
        const r = (el) => el.getBoundingClientRect();
        const pr = r(pill), dr = r(dw), hr = r(head);
        return {
          inHead: head.contains(dw),
          sameRowAsPill: Math.abs((pr.y + pr.height / 2) - (dr.y + dr.height / 2)) <= 2,
          rightOfPill: dr.x >= pr.right - 1,
          rightAlignedInHead: (hr.right - dr.right) <= 4,
        };
      })();
      return {
        exists: !!card,
        classes: card?.className ?? null,
        contentSwitchCount: card ? card.querySelectorAll('[data-plugin-switch]').length : null,
        // 2026-09-14 面板收敛批：退场件（更多/菜单/封禁）卡片级零残留
        moreCount: card ? card.querySelectorAll('[data-plugin-more]').length : null,
        menuCount: card ? card.querySelectorAll('[data-plugin-menu]').length : null,
        blockCount: card ? card.querySelectorAll('[data-plugin-block]').length : null,
        dispatchCount: card ? card.querySelectorAll('[data-plugin-dispatch]').length : null,
        dispatchLabel: card?.querySelector('.plugins-dispatch-label')?.textContent.trim() ?? null,
        geom,
        pill: pill?.textContent.trim() ?? null,
        pillClass: pill?.className ?? null,
        changedHint: card?.querySelector('.plugins-card-hint.changed')?.textContent.trim() ?? null,
        blockedHint: card?.querySelector('.plugins-card-hint.blocked')?.textContent.trim() ?? null,
        dispatchOn: dw?.classList.contains('on') ?? null,
        dispatchDisabled: dw ? dw.disabled : null,
      };
    }, name);
  }

  test('A card form: content switch + block UI retired; pill 已启用; 唯一控件 = 右上「任务分发器可见性」开关', async ({ page }) => {
    await unblockQuietly(ALPHA);
    await loadPluginsPage(page);
    const st = await cardState(page, ALPHA);
    expect(st.exists, 'fixture card rendered').toBe(true);
    expect(st.contentSwitchCount, '内容审批开关退场（卡片级零残留）').toBe(0);
    expect(st.moreCount, '「更多」入口退场（卡片级零残留）').toBe(0);
    expect(st.menuCount, '次级动作行退场（卡片级零残留）').toBe(0);
    expect(st.blockCount, '封禁/解封入口退场（卡片级零残留）').toBe(0);
    expect(st.dispatchCount, '卡片唯一控件 = 派发开关').toBe(1);
    expect(st.dispatchLabel, '开关文案 = 「任务分发器可见性」').toBe('任务分发器可见性');
    expect(st.geom?.inHead, '开关在卡片头行内（不是底部独立一行）').toBe(true);
    expect(st.geom?.sameRowAsPill, '开关与状态药丸同排').toBe(true);
    expect(st.geom?.rightOfPill, '开关在药丸右侧').toBe(true);
    expect(st.geom?.rightAlignedInHead, '开关行盒右对齐（「右上」）').toBe(true);
    expect(st.pill, '药丸 = 已启用（绑 blocked/contentChanged）').toBe('已启用');
    expect(st.pillClass, 'pill 带 on class').toContain('on');
    expect(st.classes, '卡片无 blocked/changed 痕').not.toContain('blocked');
    expect(st.dispatchOn, '派发开关 on（兼容默认 authorEnabled=true）').toBe(true);
    expect(st.dispatchDisabled, '派发开关可用（封禁是唯一的禁用前提）').toBe(false);
  });

  test('B 内容变更**非拦截**：改 fixture 内容 ⇒ 仍进目录 + 仍可派发，面板只多一条可见性提示', async ({ page }) => {
    test.skip(!hasC6, 'engine track C6 (`contentChanged`) not live on this instance — content change still blocks loading here; rerun after the plugin-nogate engine track merges');
    // Precondition: 已有 trust 记录（旧语义的「已审批」基准），随后改内容。
    await rest('POST', `/api/plugins/${ALPHA}/approve`);
    const skillPath = join(pluginsDir, ALPHA, 'skills', 'greet', 'SKILL.md');
    await writeFile(skillPath, skillMd({ name: 'greet', description: 'Greets from the alpha fixture (v2)', line: 'Hello from alpha — EDITED.' }));

    await loadPluginsPage(page);
    const st = await cardState(page, ALPHA);
    expect(st.pill, '内容已变更 ⇒ 药丸 = 内容已变更').toBe('内容已变更');
    expect(st.pillClass, 'pill 带 changed class').toContain('changed');
    expect(st.changedHint, '可见性提示（非拦截）').toBeTruthy();
    expect(st.dispatchDisabled, '内容变更不锁派发开关（非拦截）').toBe(false);
    expect(await catalogContains(ALPHA), '内容已变更的包**仍进目录**（不拦装载）').toBe(true);
    expect(st.classes, '卡片带 changed 态但**不**带 blocked 态').not.toContain('blocked');
  });

  test('C 封禁（引擎侧 API/CLI）⇒ 出目录 + 派发锁死 + 面板零封禁入口；解封 ⇒ 回目录', async ({ page }) => {
    test.skip(!hasC6, 'engine track C6 (`blocked`) not live on this instance — block cannot take effect here; rerun after the plugin-nogate engine track merges');
    await unblockQuietly(BETA);
    await rest('POST', `/api/plugins/${BETA}/approve`);
    await loadPluginsPage(page);
    const pre = await cardState(page, BETA);
    expect(pre.dispatchDisabled, 'precondition: 未封禁 ⇒ 派发可用').toBe(false);
    expect(pre.blockCount, 'precondition: 面板无封禁入口').toBe(0);
    expect(await catalogContains(BETA), 'precondition: beta 在目录里').toBe(true);

    // 封禁走**引擎侧**（API / CLI）——面板已无该入口（作者三裁之批一）；
    // 面板必须把引擎侧状态收敛出来（轮询/重渲），而不是自己发起该动作。
    await rest('POST', `/api/plugins/${BETA}/revoke`);
    await page.reload();
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.textContent.trim() === '已封禁',
      BETA, { timeout: 20000 });
    const st = await cardState(page, BETA);
    expect(st.pillClass, 'pill 带 blocked class').toContain('blocked');
    expect(st.blockCount, '封禁态下也不得长回封禁入口').toBe(0);
    expect(st.blockedHint, '一行可行动提示（指向 API / CLI）').toBeTruthy();
    expect(st.dispatchDisabled, '封禁 ⇒ 派发开关锁死').toBe(true);
    expect(await catalogContains(BETA), '封禁 ⇒ 出目录').toBe(false);

    // 解封回位（同样引擎侧）。
    await rest('POST', `/api/plugins/${BETA}/unblock`);
    await page.reload();
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.textContent.trim() !== '已封禁',
      BETA, { timeout: 20000 });
    const after = await cardState(page, BETA);
    expect(after.pill, '解封后回已启用').toBe('已启用');
    expect(after.dispatchDisabled, '派发开关解锁').toBe(false);
    expect(await catalogContains(BETA), '解封 ⇒ 回目录').toBe(true);
  });

  test('D dual-theme screenshots of the redesigned plugins page', async ({ page }) => {
    await unblockQuietly(ALPHA);
    const shots = [];
    for (const scheme of ['dark', 'light']) {
      await loadPluginsPage(page, scheme);
      // 2026-09-14 面板收敛批：卡片唯一控件（右上派发开关）已随初始渲染在位，
      // 无「更多」菜单需要展开（该入口已退场）。
      await page.waitForTimeout(400);
      const path = join(SHOTS, `plugins-redesign-${scheme}.png`);
      const content = page.locator('#plugins-content');
      await content.screenshot({ path });
      shots.push(path);
    }
    for (const path of shots) {
      const info = await stat(path).catch(() => null);
      expect(info?.size ?? 0, `screenshot written with content: ${path}`).toBeGreaterThan(0);
    }
  });
});
