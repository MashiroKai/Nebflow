// plugins-panel-two-actions.spec.mjs — 2026-09-13 无审批批（装了就是信任）验收 spec；
// **2026-09-14 插件面板收敛批（作者三裁之批一）再重写**（上一版 = 2026-09-12 令 1
// 的「两控件并存」版本，再上一版 = 内容审批开关版）。真后端（隔离实例）。
// 本批砍掉**封禁/解封 UI 面**（在飞止损只走 API / CLI）⇒ 卡片上只剩**一个**控件：
//   • 派发开关（`data-plugin-dispatch`）：**卡片右上、与状态药丸同排**，
//     乐观翻转，只写 `plugins.dispatch`（令 1 派发面）
//
// **2026-09-15 UI 文案微批（作者令）叠加**：该开关旁的**可见 label 小字**
// 「任务分发器可见性」整体删除（toggle 本体自明；`plugins.dispatchLabel` 只留作
// aria-label），默认态药丸也不再出「已启用」文字（药丸本体 + 状态样式保留；
// blocked / contentChanged 仍出字）。
//
// 断言链：
//   ① 退场件页面级零残留：`[data-plugin-switch]`（2026-09-13 退场）+
//      `[data-plugin-more]` / `[data-plugin-menu]` / `[data-plugin-block]`（本批退场）
//   ② 唯一控件存在且位置正确：role=switch、aria-label = locale 真值
//      `plugins.dispatchLabel`（不硬编码文案）、**无**可见 label 小字、
//      落在卡片头行内（`.plugins-card-head` ≥ `.plugins-card-state`）、与药丸**同排**、
//      在药丸**右侧**、行盒右对齐（「右上」）、
//      行盒非零、**无**旧底部行的 hairline 分隔
//   ③ 状态药丸绑 blocked / contentChanged（🔴 不绑 trusted）：默认态**无可见文字** + on 类
//   ④ 前端判据面（🔴 **不是**引擎语义的正面断言）：fixture 插件在实例上没有 trust
//      记录 ⇒ 卡片落默认态（药丸无可见文字）+ 派发开关可用 + 旧审批开关零残留。**引擎侧**「无记录包
//      首扫即受信」的正面断言在 Scala 侧 `PluginRegistrySpec`（「在位即信任（正面
//      断言）」+「无记录包进目录」）——本 spec 不含该语义断言：前端按设计不消费
//      `trusted`，对「引擎是否在位即信任」无区分度（old default-deny 引擎上同样绿）；
//      本用例的区分度在「药丸/派发面被重新绑回审批面」这一**前端回归**上——本实例
//      载荷带旧 default-deny 判词（`trust.status='untrusted'`，`trusted` 布尔字段在
//      未合入引擎轨的实例上**不存在**）⇒ 绑回去即转红（返工轮变异实测）。
//   ⑤ 封禁（**引擎侧** API / CLI 发起——面板已无该入口）⇒ 面板必须把状态收敛出来
//      （药丸「已封禁」+ 派发开关锁死 + 可行动注记指向 API / CLI + REST 真值
//       blocked=true）；**整轮 UI 交互零 revoke 调用**（UI 入口退场 = wire 可验）
//   ⑥ 解封（同样引擎侧）⇒ 回默认态（药丸无可见文字）+ 开关解锁；**面板 wire 零 unblock**；
//      派发开关仍只打 /disable（不掺 /revoke），且落盘 dispatch.authorEnabled=false
//
// 端点与 C6 字段由**两轨共用冻结契约 v1** 固定（🔴 本批零改动）：
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
      const dw = card?.querySelector('[data-plugin-dispatch]');
      const pill = card?.querySelector('.plugins-state-pill');
      const head = card?.querySelector('.plugins-card-head');
      const state = card?.querySelector('.plugins-card-state');
      const row = card?.querySelector('.plugins-card-dispatch');
      const note = card?.querySelector('.plugins-dispatch-note');
      const comp = row ? getComputedStyle(row) : null;
      const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height, right: b.right, bottom: b.bottom }; };
      const rb = box(row), db = box(dw), hb = box(head);
      return {
        cardCount: document.querySelectorAll('#plugins-content .plugins-card').length,
        // ① 退场件（页面级零残留）
        contentSwitchCount: document.querySelectorAll('#plugins-content [data-plugin-switch]').length,
        moreCount: document.querySelectorAll('#plugins-content [data-plugin-more]').length,
        menuCount: document.querySelectorAll('#plugins-content [data-plugin-menu]').length,
        blockCount: document.querySelectorAll('#plugins-content [data-plugin-block]').length,
        // ② 唯一控件
        dispatchCount: document.querySelectorAll('#plugins-content [data-plugin-dispatch]').length,
        hasDispatch: !!dw,
        dispatchRole: dw?.getAttribute('role') ?? null,
        dispatchLabel: dw?.getAttribute('aria-label') ?? null,
        rowLabelText: row?.querySelector('.plugins-dispatch-label')?.textContent.trim() ?? null,
        rowBorderTop: comp ? comp.borderTopWidth : null,
        rowBox: rb,
        inHead: !!(head && dw && head.contains(dw)),
        inState: !!(state && dw && state.contains(dw)),
        // 2026-09-15「删掉空框」：空文案 ⇒ 药丸元素缺席 ⇒ 状态区只剩承载开关的包裹
        pillAbsent: !pill,
        stateOnlyChild: !!(state && state.children.length === 1
          && state.firstElementChild?.querySelector('[data-plugin-dispatch]')),
        rightAlignedInHead: !!(hb && db) && (hb.right - db.right) <= 4,
        dispatchDisabled: dw ? dw.disabled : null,
        dispatchBlocked: dw?.getAttribute('data-dispatch-blocked') ?? null,
        dispatchOn: dw?.classList.contains('on') ?? null,
        noteHidden: note ? note.hidden : null,
        noteText: note?.textContent.trim() ?? null,
        // ③ 药丸：空文案 ⇒ **元素不生成**（2026-09-15「删掉空框」）⇒ count 是主判据
        pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
        pillClass: card?.querySelector('.plugins-state-pill')?.className ?? null,
        pillElCount: card ? card.querySelectorAll('.plugins-state-pill').length : null,
        cardClass: card?.className ?? null,
      };
    }, name);
  }

  test('①+②+③ 退场件零残留；唯一控件 = 卡片右上派发开关（与药丸同排）；药丸绑新字段', async ({ page }) => {
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    const st = await cardState(page, PLUGIN);

    const dispatchLabel = await locale(page, 'plugins.dispatchLabel');

    // ① 退场件页面级零残留（内容审批开关 + 封禁/「更多」三钩子）
    expect(st.contentSwitchCount, '内容审批开关页面级零残留（C7 主控件退场）').toBe(0);
    expect(st.moreCount, '「更多」入口退场 = 页面级零残留').toBe(0);
    expect(st.menuCount, '次级动作行退场 = 页面级零残留').toBe(0);
    expect(st.blockCount, '封禁/解封入口退场 = 页面级零残留').toBe(0);

    // ② 唯一控件：存在 + 钩子/标签 + 位置（右上、与药丸同排）
    expect(st.dispatchCount, '页面唯一控件 = 派发开关（计数 1）').toBe(1);
    expect(st.hasDispatch, '派发控件存在（既有钩子 data-plugin-dispatch）').toBe(true);
    expect(st.dispatchRole, '派发控件 role=switch').toBe('switch');
    expect(st.dispatchLabel, '派发控件 aria-label = locale plugins.dispatchLabel（可访问性面保留）').toBe(dispatchLabel);
    expect(st.rowLabelText, '开关旁无可见 label 小字（2026-09-15 令：「任务分发器可见性」已整体删除，toggle 本体自明）').toBe(null);
    expect(st.inHead, '控件在卡片头行内（不再是底部独立行）').toBe(true);
    expect(st.inState, '控件落在状态区（卡片右上）').toBe(true);
    expect(st.pillAbsent, '状态区内无药丸元素（空框不占位）').toBe(true);
    expect(st.stateOnlyChild, '状态区只剩承载开关的包裹元素（零占位残留）').toBe(true);
    expect(st.rightAlignedInHead, '控件行盒右对齐（「右上」）').toBe(true);
    expect(st.rowBox.w > 0 && st.rowBox.h > 0, '控件真实渲染（有非零盒）').toBe(true);
    expect(parseFloat(st.rowBorderTop), '旧底部行的 hairline 分隔已随该形态退场（border-top = 0）').toBe(0);

    // ③ 药丸绑 blocked/contentChanged（未封禁 ⇒ 默认态：空文案 ⇒ **元素不生成**）
    expect(st.pillElCount, '状态药丸默认态零元素（「删掉空框」：空文案 ⇒ 不渲染 pill 元素）').toBe(0);
    expect(st.pill, '默认态药丸无元素 ⇒ 文本读数 null').toBe(null);
    expect(st.dispatchDisabled, '未封禁 ⇒ 派发开关可用').toBe(false);
  });

  test('④ 前端判据面：无 trust 记录的包 ⇒ 默认态（药丸无可见文字）+ 派发可用 + 旧审批开关零残留（引擎语义断言见 Scala PluginRegistrySpec）', async ({ page }) => {
    // 本用例断言的是**前端契约**：无论引擎处于哪个世界（在位即信任 / 回退
    // default-deny），只要载荷没有 blocked/contentChanged，面板就必须落默认态
    // （药丸无可见文字 + on 类）+ 派发可用 + 无「先审批内容」前提（前端按设计不
    // 消费 `trusted`——引擎侧的语义正面断言在 `PluginRegistrySpec`，不在本 spec）。
    // 区分度：药丸/派发面若被重新绑回审批面（`pluginStatus` 读 `trusted`/`trust`、
    // `dispatchState` 缺省 false）⇒ 本实例上该包（未合入引擎轨时载荷带旧
    // default-deny 判词 `trust.status='untrusted'`、无 `trusted` 布尔字段）即转红
    // （返工轮变异实测：变异实例上本用例红、pristine 绿）。下面把实例载荷的信任面
    // 读数写进断言消息，便于判读本用例当前落在哪个世界。
    await unblockQuietly(PLUGIN);
    const { body } = await rest('GET', '/api/plugins');
    const man = (body.plugins || []).find((p) => p.name === PLUGIN);
    expect(!!man, 'fixture 插件已被扫描装载（8 步内出现在注册表）').toBe(true);
    const needWorld = `实例载荷 trust.status=${String(man?.trust?.status)} / trusted=${String(man?.trusted)}`;

    await loadPluginsPage(page);
    const st = await cardState(page, PLUGIN);
    expect(st.pillElCount, `无 trust 记录的包在面板上落默认态（药丸零元素）（${needWorld}；前端不消费该字段）`).toBe(0);
    expect(st.pill, '默认态药丸无元素 ⇒ 文本读数 null').toBe(null);
    expect(st.cardClass, '卡片无 blocked/changed 痕').not.toContain('blocked');
    await waitDispatchLive(page, PLUGIN);
    const again = await cardState(page, PLUGIN);
    expect(again.dispatchDisabled, '派发开关可用（不再要求先审批内容）').toBe(false);
    expect(again.dispatchBlocked, '无 data-dispatch-blocked（「未受信」阻断前提消失）').toBe(null);
    expect(again.noteHidden, '无「先审批内容」注记').toBe(true);
    expect(again.contentSwitchCount, '内容审批开关零残留').toBe(0);
  });

  test('⑤ 封禁（引擎侧 API / CLI）⇒ 面板收敛「已封禁」+ 开关锁死 + 注记可行动；UI 零 revoke wire', async ({ page }) => {
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/api/plugins/')) posts.push(new URL(req.url()).pathname);
    });
    // 面板上唯一的用户动作（派发开关）——wire 只可能是 enable/disable。
    await page.click(`.plugins-card[data-plugin="${PLUGIN}"] [data-plugin-dispatch]`);
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-dispatch]`)?.getAttribute('aria-checked') === 'false',
      PLUGIN, { timeout: 10000 });

    // 封禁走**引擎侧**（作者选定的在飞止损路径；面板已无该入口）。
    await rest('POST', `/api/plugins/${PLUGIN}/revoke`);
    await page.reload();
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.classList.contains('blocked'),
      PLUGIN, { timeout: 20000 });
    const st = await cardState(page, PLUGIN);
    expect(st.pillClass, 'pill 带 blocked class').toContain('blocked');
    // 例外态仍出文字（可行动信号；删的只是默认态冗余小字）。
    expect(st.pill, 'blocked pill 仍出文字 = 已封禁').toBe('已封禁');
    expect(st.dispatchDisabled, '封禁 ⇒ 派发开关 disabled（写下去也不会生效）').toBe(true);
    expect(st.dispatchBlocked, 'blocked 原因随元素暴露').toBe('blocked');
    expect(st.noteHidden, '可行动注记可见').toBe(false);
    expect(st.noteText.includes('API / CLI'), '注记指向 API / CLI（面板已无入口）').toBe(true);
    expect(st.moreCount + st.menuCount + st.blockCount, '封禁态下也不得长回入口').toBe(0);
    const { body } = await rest('GET', '/api/plugins');
    const man = (body.plugins || []).find((p) => p.name === PLUGIN);
    expect(man?.blocked, 'REST 真值：blocked=true（落盘事实源）').toBe(true);
    // 🔴 UI 入口退场 = wire 可验：整轮交互不得出现 revoke / unblock。
    expect(posts.filter((p) => /\/revoke$|\/unblock$/.test(p)), `UI 不得打 revoke/unblock，got ${posts}`).toEqual([]);
    expect(posts.some((p) => p.endsWith('/approve')), 'UI 主线绝不打 /approve').toBe(false);
    // 清场：解封（下一用例与后续批次都要求未封禁起点）。
    await rest('POST', `/api/plugins/${PLUGIN}/unblock`);
  });

  test('⑥ 解封（引擎侧）⇒ 回默认态（药丸无可见文字）+ 开关解锁；面板 wire 零 unblock；派发开关仍只写 /disable', async ({ page }) => {
    test.skip(!hasC6, 'engine track C6 (`blocked`) not live on this instance — /unblock cannot move the state yet; rerun after the plugin-nogate engine track merges');
    await unblockQuietly(PLUGIN);
    await loadPluginsPage(page);
    // 先封禁（引擎侧真后端），再解封 —— 面板全程不参与这两条 wire。
    await rest('POST', `/api/plugins/${PLUGIN}/revoke`);
    await page.reload();
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-state-pill`)?.classList.contains('blocked'),
      PLUGIN, { timeout: 20000 });

    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST') posts.push(new URL(req.url()).pathname);
    });
    await rest('POST', `/api/plugins/${PLUGIN}/unblock`);
    await page.reload();
    // 等重渲完成（卡片 class 回 on）：旧口径「pill 回 on 类」已随空框退场。
    await page.waitForFunction((n) =>
      document.querySelector(`.plugins-card[data-plugin="${n}"]`)?.classList.contains('on'),
      PLUGIN, { timeout: 20000 });
    const st = await cardState(page, PLUGIN);
    expect(st.pillElCount, '解封后回默认态（药丸零元素，非「无文字的 on 药丸」）').toBe(0);
    expect(st.pill, '解封后默认态药丸无元素 ⇒ 读数 null').toBe(null);
    expect(st.dispatchDisabled, '派发开关解锁').toBe(false);
    expect(st.dispatchBlocked, 'blocked 属性清除').toBe(null);
    expect(posts.filter((p) => /\/revoke$|\/unblock$/.test(p)), `面板不得打 revoke/unblock，got ${posts}`).toEqual([]);

    // 派发动作仍打自己的端点（且不掺 revoke/unblock）。
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
    expect(posts2.some((p) => /\/revoke$|\/unblock$/.test(p)), '派发动作不得打 revoke/unblock').toBe(false);
    const after = await diskPlugins();
    expect(after.dispatch?.[PLUGIN]?.authorEnabled, '落盘：dispatch.authorEnabled=false').toBe(false);
  });
});
