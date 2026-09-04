// plugins-panel-redesign.spec.mjs — 2026-09-04 插件面板重设计验收 spec
// （统一插件系统 + 每插件一开关）。
//
// 开关状态机（真后端）：隔离实例（NEBFLOW_HOME fixture + 端口 ≥8285，绝非
// 宿主 8080）+ fixture 插件目录。断言链：
//   停（untrusted）→ 卡片 off + GET /api/plugins/catalog 不含该插件
//   启（点击开关 = POST approve）→ 卡片 on + catalog 含该插件（目录可见性
//   ≡ 开关态，分发器目录同源）
//   内容变更（改 fixture 文件 → digest 不匹配）→ 卡片 off + 「内容已变更，
//   重新启用将按新内容审批」标注 → 重新启用 = 按新内容审批 → on
//   停（点击开关 = POST revoke）→ 卡片 off + catalog 不含
//
// 未设 NEBFLOW_HOME_DIR 时显式 skip（shell 级 mock 覆盖由
// sidebar-plugins.spec.mjs 承担——两层互补，不静默降级）。

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
  return JSON.stringify({
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} for the plugins-panel-redesign spec`,
    author: 'plugins-panel-redesign spec',
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

test.describe.configure({ mode: 'serial' });

test.describe('plugins panel redesign — switch state machine (real backend)', () => {
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
  });

  /** Node-side REST helper (Bearer auth). */
  async function rest(method, path) {
    const resp = await fetch(BASE + path, { method, headers: { Authorization: `Bearer ${token}` } });
    let body = null;
    try { body = await resp.json(); } catch { body = await resp.text(); }
    return { status: resp.status, body };
  }

  /** catalog 是分发器目录段同源（trusted only）——断言插件名是否在内。 */
  async function catalogContains(name) {
    const { body } = await rest('GET', '/api/plugins/catalog');
    const text = typeof body?.catalog === 'string' ? body.catalog : JSON.stringify(body);
    return text.includes(`- ${name}:`);
  }

  async function revokeQuietly(name) {
    await rest('POST', `/api/plugins/${name}/revoke`); // 404/400 when never approved — fine
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
      const sw = card?.querySelector('.plugins-switch');
      return {
        exists: !!card,
        on: sw?.classList.contains('on') ?? null,
        ariaChecked: sw?.getAttribute('aria-checked') ?? null,
        pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
        changedHint: card?.querySelector('.plugins-card-hint:not(.dim)')?.textContent.trim() ?? null,
        changedClass: card?.classList.contains('changed') ?? false,
      };
    }, name);
  }

  test('off → on: approve puts the plugin into the catalog; card reflects trusted state', async ({ page }) => {
    await revokeQuietly(ALPHA);
    await loadPluginsPage(page);

    let st = await cardState(page, ALPHA);
    expect(st.on, 'initial (never approved) renders the switch off').toBe(false);
    expect(st.pill, 'state pill 未启用').toBe('未启用');
    expect(await catalogContains(ALPHA), 'untrusted plugin must NOT be in the catalog').toBe(false);

    // The page's only control: click → POST approve → registry resync → on.
    await page.click(`.plugins-card[data-plugin="${ALPHA}"] .plugins-switch`);
    await page.waitForFunction(
      (plugin) => document.querySelector(`.plugins-card[data-plugin="${plugin}"] .plugins-switch`)?.classList.contains('on'),
      ALPHA, { timeout: 10000 });

    st = await cardState(page, ALPHA);
    expect(st.ariaChecked, 'aria-checked=true when trusted').toBe('true');
    expect(st.pill, 'state pill 已启用').toBe('已启用');
    expect(await catalogContains(ALPHA), 'trusted plugin must appear in the catalog (switch ≡ visibility)').toBe(true);

    await revokeQuietly(ALPHA); // cleanup for the next tests
  });

  test('digest change: edited fixture content → off + 内容已变更 hint; re-enable approves the new content', async ({ page }) => {
    // Precondition: approved at digest D1.
    await revokeQuietly(ALPHA);
    await (async () => { const r = await rest('POST', `/api/plugins/${ALPHA}/approve`); if (r.status !== 200) throw new Error(`approve failed: ${JSON.stringify(r.body)}`); })();

    // Mutate the plugin directory → digest D2 ≠ D1 (listWithRejected is
    // uncached; the scan mtime cache keys on file mtimes — both pick this up).
    const skillPath = join(pluginsDir, ALPHA, 'skills', 'greet', 'SKILL.md');
    await writeFile(skillPath, skillMd({ name: 'greet', description: 'Greets from the alpha fixture (v2)', line: 'Hello from alpha — EDITED.' }));

    await loadPluginsPage(page);
    const st = await cardState(page, ALPHA);
    expect(st.on, 'digest mismatch renders the switch off').toBe(false);
    expect(st.changedClass, 'card carries the changed state class').toBe(true);
    expect(st.changedHint, 'card shows the 内容已变更 re-approval hint')
      .toBe('内容已变更，重新启用将按新内容审批');
    expect(await catalogContains(ALPHA), 'changed plugin must NOT be in the catalog').toBe(false);

    // Re-enable = approve the NEW content → trusted again.
    await page.click(`.plugins-card[data-plugin="${ALPHA}"] .plugins-switch`);
    await page.waitForFunction(
      (plugin) => document.querySelector(`.plugins-card[data-plugin="${plugin}"] .plugins-switch`)?.classList.contains('on'),
      ALPHA, { timeout: 10000 });
    expect(await catalogContains(ALPHA), 're-approved (new digest) plugin back in the catalog').toBe(true);

    await revokeQuietly(ALPHA);
  });

  test('on → off: revoke removes the plugin from the catalog; card reflects untrusted state', async ({ page }) => {
    // Precondition: on.
    const pre = await rest('POST', `/api/plugins/${BETA}/approve`);
    expect(pre.status, 'fixture beta approves cleanly').toBe(200);

    await loadPluginsPage(page);
    const betaPre = await cardState(page, BETA);
    expect(betaPre.on, 'precondition: beta switch on').toBe(true);
    expect(await catalogContains(BETA), 'precondition: beta in the catalog').toBe(true);

    // Click → POST revoke → registry resync → off + out of the catalog.
    await page.click(`.plugins-card[data-plugin="${BETA}"] .plugins-switch`);
    await page.waitForFunction(
      (plugin) => !document.querySelector(`.plugins-card[data-plugin="${plugin}"] .plugins-switch`)?.classList.contains('on'),
      BETA, { timeout: 10000 });

    const st = await cardState(page, BETA);
    expect(st.ariaChecked, 'aria-checked=false after revoke').toBe('false');
    expect(st.pill, 'state pill back to 未启用').toBe('未启用');
    expect(await catalogContains(BETA), 'revoked plugin must leave the catalog').toBe(false);
  });

  test('dual-theme screenshots of the redesigned plugins page', async ({ page }) => {
    await revokeQuietly(ALPHA);
    // Give the page something to show: alpha on (with expandable skills), beta off.
    await rest('POST', `/api/plugins/${ALPHA}/approve`);

    const shots = [];
    for (const scheme of ['dark', 'light']) {
      await loadPluginsPage(page, scheme);
      // Expand the skill previews so the shot shows the full annotation story.
      await page.click(`.plugins-card[data-plugin="${ALPHA}"] .plugins-comp-toggle`).catch(() => {});
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
