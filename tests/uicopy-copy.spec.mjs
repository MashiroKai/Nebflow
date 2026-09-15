// uicopy-copy.spec.mjs — UI 文案微批（作者 2026-09-15 令）验红钉 + 回归门。
//
//   件 1 · 插件面板：两处可见小字**整体删除**
//     a) 默认态状态药丸的文字「已启用」/「Enabled」（`plugins.stateOn`）——
//        「在位即信任」下它恒显，是冗余说明；开关态由 toggle 本体自明。
//     b) 派发开关旁的可见 label「任务分发器可见性」/「Dispatcher visibility」
//        （`.plugins-dispatch-label`）——toggle 本体自明。
//     c) 普通关态的 note「派发已关闭——只影响未来派发；已在跑的节点保持其插件许可」
//        （`plugins.dispatchOffNote`）。
//   🔴 后续裁定（作者 2026-09-15，「**删掉空框**」）——本 spec 已随令迁移：
//     • 空文案（默认态）⇒ **pill 元素根本不生成**，判据 = `.plugins-state-pill`
//       count = 0（🔴 **不是**「元素在、用 CSS 藏起来」——禁遮盖式假修）；
//       旧口径「药丸本体 + on 类保留」已随该令作废。
//   🔴 保留面（本 spec 逐条钉住，防「删过头」）：
//     • toggle 本体（`[data-plugin-dispatch]`）在位、role=switch、**可点**、
//       点击后状态样式翻转（`.on` 类）+ 卡片右上几何不变（状态区只剩 toggle）；
//     • **有文字态**照常渲染并保留状态样式：blocked ⇒ 元素在 + `blocked` 类 +
//       圆角 + 非透明状态色 + 文字「已封禁」（可行动信号）+ 可行动 note（指向 API / CLI）；
//     • **可访问性面**：toggle 的 aria-label 仍 = locale `plugins.dispatchLabel`
//       （非空 —— 只删可见文本，不删 aria）。
//   （本批同步件 = capsulefix：渲染点条件化，`js/plugins.js` 三处。）
//
//   件 2 · 设置面板 · 模型预设（Preset）描述语义：label + 帮助文案表达
//     「描述 = 该方案的能力描述，agent 可据此自动选择模型方案」。
//
// Self-contained: static server on 127.0.0.1:8185 (8100+ band — never the host
// 8080/8091/8092; 8179 = sidebar-plugins, 8181/8183/8187/8188 taken too);
// WS/API mocked in-page; server closed at end. Zero external network.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8185;
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

/** 被删的两处可见小字（zh / en）。🔴 只出现在本文件的**反断言**里。 */
const REMOVED_PILL_TEXT = { 'zh-CN': '已启用', en: 'Enabled' };
const REMOVED_LABEL_TEXT = { 'zh-CN': '任务分发器可见性', en: 'Dispatcher visibility' };
const REMOVED_NOTE_TEXT = {
  'zh-CN': '派发已关闭——只影响未来派发；已在跑的节点保持其插件许可',
  en: 'Dispatch is off — affects FUTURE dispatches only; nodes already running keep their plugin grant',
};
/** 件 2 期望文案（改后逐字）。 */
const PRESET_LABEL = { 'zh-CN': '能力描述', en: 'Capability description' };
const PRESET_HINT = {
  'zh-CN': '描述该方案的能力（擅长什么任务）；agent 会据此自动选择模型方案',
  en: 'Describe what this preset is good at — agents use it to pick a preset automatically',
};

function startServer() {
  return new Promise((done) => {
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
    server.listen(PORT, '127.0.0.1', () => done(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;

const MOCK_AGENTS = { agents: [{ name: 'Coder', displayName: 'Coder', description: 'coder', category: 'standalone', layer: 'global' }] };
const MOCK_MODEL = { preferred: 'prov/m1', current: 'prov/m1', preset: 'preset-one' };
const pluginState = { blocked: false, contentChanged: false, dispatchEnabled: true };

function fixtureManifest(st) {
  return {
    name: 'e2e-hello',
    version: '1.0.0',
    description: 'fixture plugin',
    author: 'spec',
    digest: 'deadbeefcafe'.repeat(6),
    fileCount: 3,
    trusted: true,
    blocked: st.blocked,
    contentChanged: st.contentChanged,
    dispatch: { authorEnabled: st.dispatchEnabled, transitionActive: false },
    trust: { status: 'untrusted', reason: 'legacy gate record' },
    skills: [{ id: 'e2e-hello/hello', description: 'Say hello', preview: '' }],
    mcpServers: [],
    toolsExtension: [],
    warnings: [],
  };
}

const MOCK_PRESETS = {
  defaultPreset: '',
  presets: [{ name: 'p1', preferred: 'prov/m1', fallbacks: [], description: 'vision + long context' }],
  agents: {},
};

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  pluginState.blocked = false;
  pluginState.contentChanged = false;
  pluginState.dispatchEnabled = true;
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Playwright route matching is LIFO — catch-all FIRST.
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/presets', r => r.fulfill({ json: MOCK_PRESETS }));
  await page.route('**/api/plugins/e2e-hello/disable', r => {
    if (r.request().method() === 'POST') { pluginState.dispatchEnabled = false; r.fulfill({ json: { ok: true, message: 'disabled' } }); }
    else r.fulfill({ json: {} });
  });
  await page.route('**/api/plugins/e2e-hello/enable', r => {
    if (r.request().method() === 'POST') { pluginState.dispatchEnabled = true; r.fulfill({ json: { ok: true, message: 'enabled' } }); }
    else r.fulfill({ json: {} });
  });
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: [fixtureManifest(pluginState)], rejected: [] } }));
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
});

async function loadShell(page, locale) {
  await page.addInitScript((l) => {
    localStorage.clear();
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
}

/** 卡片形态读数（件 1）：可见小字 / toggle 本体 / 药丸状态样式 / aria 面。 */
async function cardDump(page) {
  return page.evaluate(() => {
    const q = (s) => document.querySelector(s);
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    const pill = card?.querySelector('.plugins-state-pill');
    const dsw = card?.querySelector('[data-plugin-dispatch]');
    const lab = card?.querySelector('.plugins-dispatch-label');
    const noteEl = card?.querySelector('.plugins-dispatch-note');
    const cs = pill ? getComputedStyle(pill) : null;
    return {
      // 被删的可见小字 —— 三条都必须是「不存在 / 空」
      pillText: pill?.textContent.trim() ?? null,
      labelPresent: !!lab,
      labelText: lab?.textContent.trim() ?? null,
      notePresent: !!noteEl,
      noteHidden: noteEl ? noteEl.hidden : null,
      noteText: noteEl && !noteEl.hidden ? noteEl.textContent.trim() : null,
      // 保留面：toggle 本体
      hasToggle: !!dsw,
      toggleRole: dsw?.getAttribute('role') ?? null,
      toggleTag: dsw?.tagName ?? null,
      toggleAria: dsw?.getAttribute('aria-label') ?? null,
      toggleAriaChecked: dsw?.getAttribute('aria-checked') ?? null,
      toggleDisabled: dsw ? dsw.disabled : null,
      toggleOn: dsw ? dsw.classList.contains('on') : null,
      // 状态面：默认态零元素（2026-09-15「删掉空框」）；有文字态（blocked/changed）元素在
      pillPresent: !!pill,
      pillElCount: card ? card.querySelectorAll('.plugins-state-pill').length : null,
      pillClass: pill?.className ?? null,
      pillVisual: cs ? {
        borderRadius: cs.borderRadius,
        backgroundFill: cs.backgroundColor,
        borderColor: cs.borderTopColor,
      } : null,
      // 几何（卡片右上）：开关是状态区**唯一子元素**（空框不占位）
      geom: (() => {
        const head = card?.querySelector('.plugins-card-head');
        const state = card?.querySelector('.plugins-card-state');
        if (!card || !head || !state || !dsw) return null;
        const r = (el) => el.getBoundingClientRect();
        const dr = r(dsw), hr = r(head);
        return {
          inHead: head.contains(dsw),
          inState: state.contains(dsw),
          pillAbsent: !pill,
          // 状态区唯一子元素 = 承载 toggle 的 `.plugins-card-dispatch` 包裹（无占位元素）
          stateOnlyChild: state.children.length === 1 && !!state.firstElementChild?.querySelector('[data-plugin-dispatch]'),
          rightAlignedInHead: (hr.right - dr.right) <= 4,
        };
      })(),
    };
  });
}

// ══════════════════ 件 1 ══════════════════
test('件1: 插件面板两处可见小字整体删除；toggle 本体/状态样式/aria 面保留（zh-CN + en）', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });

    const s = await cardDump(page);

    // ── 红钉（改前红）：三条被删的可见小字「不存在」+ 空态**零元素** ──
    expect(s.pillElCount, `[${locale}] 默认态零元素（「删掉空框」：空文案 ⇒ 不渲染 pill 元素，判据 = count 0）`).toBe(0);
    expect(s.pillText, `[${locale}] 默认态药丸无元素 ⇒ textContent 读数 null（「${REMOVED_PILL_TEXT[locale]}」小字已删）`).toBe(null);
    expect(s.labelPresent, `[${locale}] 可见 label「${REMOVED_LABEL_TEXT[locale]}」元素不存在`).toBe(false);
    expect(s.labelText, `[${locale}] 可见 label 文本为 null`).toBe(null);
    expect(s.noteText, `[${locale}] 普通态无可见 note（「${REMOVED_NOTE_TEXT[locale]}」已删）`).toBe(null);
    expect(s.notePresent && s.noteHidden, `[${locale}] note 容器保留但 hidden（blocked/transition 态仍要用它）`).toBe(true);

    // ── 整页可见文本里那两串小字也彻底不存在 ──
    const pageText = await page.evaluate(() => {
      const root = document.querySelector('#plugins-content');
      return root ? root.innerText : '';
    });
    expect(pageText.includes(REMOVED_PILL_TEXT[locale]), `[${locale}] 面板可见文本不含「${REMOVED_PILL_TEXT[locale]}」`).toBe(false);
    expect(pageText.includes(REMOVED_LABEL_TEXT[locale]), `[${locale}] 面板可见文本不含「${REMOVED_LABEL_TEXT[locale]}」`).toBe(false);
    expect(pageText.includes(REMOVED_NOTE_TEXT[locale]), `[${locale}] 面板可见文本不含关态说明小字`).toBe(false);

    // ── 保留面：toggle 本体在位、可点、右上几何不变 ──
    expect(s.hasToggle, `[${locale}] toggle 本体保留`).toBe(true);
    expect(s.toggleTag, `[${locale}] toggle 仍是 button`).toBe('BUTTON');
    expect(s.toggleRole, `[${locale}] toggle role=switch`).toBe('switch');
    expect(s.toggleDisabled, `[${locale}] 未封禁 ⇒ toggle 可点（非 disabled）`).toBe(false);
    expect(s.toggleOn, `[${locale}] 默认态 toggle = on`).toBe(true);
    expect(s.geom?.inState, `[${locale}] toggle 仍在卡片状态区（右上）`).toBe(true);
    expect(s.geom?.inHead, `[${locale}] toggle 仍在卡片头行`).toBe(true);
    expect(s.geom?.pillAbsent, `[${locale}] 状态区内无药丸元素（空框不占位）`).toBe(true);
    expect(s.geom?.stateOnlyChild, `[${locale}] 状态区只剩 toggle 一个子元素（零占位残留）`).toBe(true);
    expect(s.geom?.rightAlignedInHead, `[${locale}] toggle 行盒右对齐`).toBe(true);

    // ── 保留面：可访问性（aria-label 仍 = locale plugins.dispatchLabel，非空）──
    expect(s.toggleAria, `[${locale}] toggle aria-label 保留 = ${REMOVED_LABEL_TEXT[locale]}`).toBe(REMOVED_LABEL_TEXT[locale]);

    // ── 空态零元素（作者 2026-09-15「删掉空框」）——🔴 不是「元素在、CSS 藏起来」──
    expect(s.pillPresent, `[${locale}] 默认态药丸元素不生成（旧「药丸本体保留」口径已被作者裁定取代）`).toBe(false);
    expect(s.pillClass, `[${locale}] 空态无类可挂`).toBe(null);
    // 状态**样式**的载体随之只在有文字态出现 ⇒ 视觉读数在下面的封禁态里逐条钉（非本处）。
    expect(s.pillVisual, `[${locale}] 空态无药丸 ⇒ 无计算样式可读`).toBe(null);

    // ── 可点性 + 状态样式翻转（写 dispatch.authorEnabled，wire 只出现 disable/enable）──
    const posts = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && new URL(req.url()).pathname.startsWith('/api/plugins/')) posts.push(new URL(req.url()).pathname);
    });
    await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
    await page.waitForFunction(() =>
      !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.classList.contains('on'),
      { timeout: 8000 });
    const off = await cardDump(page);
    expect(off.toggleOn, `[${locale}] 点击后 toggle 状态样式翻转为 off`).toBe(false);
    expect(off.toggleAriaChecked, `[${locale}] aria-checked 跟随翻转为 false`).toBe('false');
    // 🔴 关态也不出说明小字（作者令点名的就是「派发已关闭——…」这条）
    expect(off.noteText, `[${locale}] 关态仍无可见说明小字`).toBe(null);
    await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]');
    await page.waitForFunction(() =>
      document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.classList.contains('on'),
      { timeout: 8000 });
    const on = await cardDump(page);
    expect(on.toggleOn, `[${locale}] 再点回 on`).toBe(true);
    expect(posts, `[${locale}] wire：仍只有 disable/enable 两笔`).toEqual([
      '/api/plugins/e2e-hello/disable', '/api/plugins/e2e-hello/enable',
    ]);
  }
});

test('件1: 例外态文字仍在（封禁 ⇒ 药丸出「已封禁」+ 可行动 note）——防「删过头」', async ({ page }) => {
  await loadShell(page, 'zh-CN');
  await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });

  // 引擎侧封禁（面板以 GET /api/plugins 重同步收敛）——本 spec 以夹具态翻转模拟其结果。
  pluginState.blocked = true;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')
      ?.classList.contains('blocked'), { timeout: 8000 });

  const blocked = await cardDump(page);
  expect(blocked.pillText, '封禁态药丸仍出文字（可行动信号，非冗余说明）').toBe('已封禁');
  expect(blocked.pillClass, '封禁态药丸状态类').toContain('blocked');
  // 状态样式（圆角 + 非透明状态色）随有文字态照常存在——空框退场后视觉面在此钉住。
  expect(blocked.pillVisual?.borderRadius, '封禁态药丸状态样式（圆角药丸）在').not.toBe('0px');
  expect(blocked.pillVisual?.backgroundFill, '封禁态药丸背景非透明（状态色在）').not.toMatch(/^rgba?\(0, 0, 0, 0\)$|^transparent$/);
  expect(blocked.noteHidden, '封禁态 note 可见').toBe(false);
  expect(blocked.noteText, '封禁态 note 指向 API / CLI').toContain('API / CLI');
  expect(blocked.toggleDisabled, '封禁态 toggle disabled').toBe(true);
  expect(blocked.toggleAria, '封禁态 aria-label 仍非空（可访问性面不因定态丢失）').toBe('任务分发器可见性');

  // 解封 ⇒ 回默认态：**元素整个消失**（不是「回到无文字的 on 药丸」），开关样式回来。
  pluginState.blocked = false;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  // 等重渲完成（卡片 class 回 on + loading 占位消失）——避免与 innerHTML 重写竞态。
  await page.waitForFunction(() => {
    const c = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    return !!c && c.classList.contains('on') && !document.querySelector('#plugins-content .plugins-loading');
  }, undefined, { timeout: 8000 });
  const after = await cardDump(page);
  expect(after.pillElCount, '解封后默认态药丸元素 count = 0（「删掉空框」）').toBe(0);
  expect(after.pillText, '解封后默认态药丸无元素 ⇒ 读数 null').toBe(null);
  expect(after.noteText, '解封后 note 重新隐藏').toBe(null);
  expect(after.toggleDisabled, '解封后 toggle 重新可点').toBe(false);
});

// ══════════════════ 件 2 ══════════════════
test('件2: 预设「能力描述」label + 帮助文案表达 agent 可据此自动选择模型的语义（zh-CN + en）', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    await page.click('#settings-btn');
    await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
    await page.waitForSelector('.preset-card', { timeout: 10000 });

    // 打开既有预设的编辑弹窗（产品自身入口：卡片 → 编辑）
    await page.click('.preset-card [data-action="edit"]');
    await page.waitForSelector('#cfg-modal', { timeout: 5000 });

    const form = await page.evaluate(() => {
      const modal = document.getElementById('cfg-modal');
      const group = modal?.querySelector('[data-field="description"]')?.closest('.cfg-form-group');
      const label = group?.querySelector('.cfg-label');
      const hint = group?.querySelector('.cfg-hint');
      const input = group?.querySelector('[data-field="description"]');
      const hintCs = hint ? getComputedStyle(hint) : null;
      return {
        labelText: label?.textContent.trim() ?? null,
        hintText: hint?.textContent.trim() ?? null,
        hintVisible: hint ? (hint.getClientRects().length > 0) : false,
        hintFontSize: hintCs?.fontSize ?? null,
        inputPresent: !!input,
        inputValue: input?.value ?? null,
        // 全弹窗可见文本（反断言用）
        modalText: modal?.innerText ?? '',
      };
    });

    expect(form.labelText, `[${locale}] label = 「能力描述」语义（改后逐字）`).toBe(PRESET_LABEL[locale]);
    expect(form.hintText, `[${locale}] 帮助文案（改后逐字）`).toBe(PRESET_HINT[locale]);
    expect(form.hintVisible, `[${locale}] 帮助文案可见（不是 hidden/display:none）`).toBe(true);
    expect(form.hintFontSize, `[${locale}] 帮助文案走既有 cfg-hint 样式（小字）`).toBe('10px');
    expect(form.inputPresent, `[${locale}] 描述输入框仍在（只改文案，零功能改动）`).toBe(true);
    expect(form.inputValue, `[${locale}] 既有描述值原样回填（🔴 零数据改动）`).toBe('vision + long context');
    // 语义两要素（locale 感知）：①「能力 / 擅长」②「agent 自动选择方案」
    const CAPABILITY_WORD = { 'zh-CN': /能力|擅长/, en: /capability|good at/i };
    const AUTO_PICK = { 'zh-CN': /自动选择/, en: /pick a preset automatically/ };
    const copy = `${form.labelText} ${form.hintText}`;
    expect(
      CAPABILITY_WORD[locale].test(copy),
      `[${locale}] 文案表达「描述 = 方案的能力描述」`,
    ).toBe(true);
    expect(
      /agent/i.test(copy) && AUTO_PICK[locale].test(copy),
      `[${locale}] 文案表达「agent 可据此自动选择模型方案」`,
    ).toBe(true);
    // 🔴 旧的裸文案（无任何语义）不得残留为 label
    expect(form.labelText, `[${locale}] 旧裸 label「描述」/「Description」不再是 label`).not.toBe(locale === 'zh-CN' ? '描述' : 'Description');

    await page.evaluate(() => document.getElementById('cfg-modal')?.remove());
  }
});
