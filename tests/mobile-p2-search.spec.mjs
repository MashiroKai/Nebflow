// mobile-p2-search.spec.mjs — P2「搜索聊天记录」手机面验收
//（mobile-secondary-screens 实施批；样张 v3.5 P2 §8 + v3.6 作者裁定②守卫）。
//
// dock 批换代（2026-10-29 作者令「各节目都是独立界面」）：入口腿 = 聊天根顶栏
// 「搜索」chrome 键；§8-1 悬浮盒模型（308×560 居中）随浮层形态退役 —— 判据
// 换钉收编页面几何（页面体全幅 / radius 0 / 关闭对账弹栈）。其余内容面断言不变。
//
// 样张→实机 DOM 映射（换代注记）：
//   §8-1 【换代】页面化盒模型 …… #search-modal 收编进 .mobile-page-body：
//        全幅（w=375 / x=0）/ radius 0。
//   §8-2 面板头图标 …… .search-modal-header 首子 = i-search 图标（searchFace.js）。
//   §8-3 mark 高亮底 …… rgba sapphire 族 30%（产品 sapphire 字面 rgb(91,127,191)；
//        样张 127,156,245 为样张侧调色，同族同 alpha —— 映射已记录）。
//   v3.6-3 裁定②守卫 …… 范围 seg 退场（.search-filters display:none）、计数行
//        以「主会话」开头、筛选两行 = tabs + 角色行（第二行含「全部来源」）、
//        脚注含「无匹配结果」、结果行会话名前缀退场。
//   §8-8 【换代】reduced-motion …… 收编面 animation:none（morph 族已删）。
//   返回契约（§8-5 面族映射）…… 面板关闭 ⇒ #search-overlay 摘 .on + 栈弹回
//        聊天根（CLOSE_WATCH 对账）。
//
// 数据面 = HTTP history 夹具（fetchHistory 走 /api/sessions/:id/history），
// 零后端、零 sbt。Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p2-search.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'p2-root-session';
const T0 = Date.now();

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 会话历史夹具：user/ai/tool 三 kind 各一条含「量子纠缠」+ 一条不含（角色过滤断言；
 *  tool 行随作者令① 2026-10-30 不入关键词结果——种它在场只为守卫「排除」）。 */
function historyFixture() {
  return { messages: [
    { type: 'user', text: '帮我查一下量子纠缠的最新进展', timestamp: T0 - 40_000 },
    { type: 'ai', text: '量子纠缠是量子力学中的核心现象，下面展开说明。', timestamp: T0 - 30_000 },
    { type: 'tool', label: 'WebSearch', input: '{"query":"量子纠缠 最新研究"}', summary: '已检索量子纠缠相关文献', content: '' },
    { type: 'user', text: '今天天气不错', timestamp: T0 - 20_000 },
  ] };
}

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === `/api/sessions/${SID}/history`) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(historyFixture()) });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 开面板：聊天根顶栏「搜索」chrome 键 → push 搜索页（面收编进页面体）。 */
async function openPanel(page) {
  await page.locator('[data-chrome="search"]').click();
  await expect(page.locator('.mobile-page[data-page="search"]')).toHaveClass(/live/);
  await expect(page.locator('#search-overlay')).toHaveClass(/on/);
  await expect(page.locator('#search-modal')).toBeVisible();
  // 页面滑入终态（dock 批：morph 退役 —— 位移 transform 归位即终态）。
  await settlePage(page, 'search');
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 page geometry: adopted full-bleed in search page, radius 0 (dock 批换代判据)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  // 收编页面几何 poll（滑入 transform 归位后采样 —— openPanel 已 settle，
  // 双保险防抖）。
  const geo = () => page.evaluate(() => {
    const m = document.getElementById('search-modal');
    const r = m.getBoundingClientRect();
    return { x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height), radius: getComputedStyle(m).borderRadius };
  });
  await expect.poll(geo, { timeout: 5000 }).toEqual({
    x: 0, w: 375, h: expect.any(Number), radius: '0px',
  });
  expect((await geo()).h).toBeGreaterThan(400); // 页面体吃满（非内容零星高）
  // 关闭契约：页面壳「<」弹栈 ⇒ overlay 摘 .on（§8-5 面族映射）+ 栈弹回聊天根。
  // （桌面 #search-modal-close 已随重复返回钮清查批在页面壳内退场——出页唯一
  // 键 = 页面壳「<」。）
  await expect(page.locator('#search-modal-close')).toBeHidden();
  await page.locator('.mobile-page[data-page="search"] .mobile-back-btn').click();
  await expect(page.locator('#search-overlay')).not.toHaveClass(/on/);
  await expect(page.locator('.mobile-page[data-page="search"]')).not.toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('chat');
  expect(errors).toEqual([]);
});

test('T2 main-session face: one pill title, scope retired, kind row = second filter row, no foot copy', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  // 🔴 三点入口批（2026-11-05 作者令「把搜索和搜索聊天记录结合……下面的文字不要，
  // 然后底下的说明文字不要」）：旧断言（面板头图标 `.mh-search-head-ic` 在场 +
  // 底部说明脚注文案）**整组换代**——
  //   · 顶部唯一标题 = 页面名胶囊（PAGES.search）——大标题退役（ui-unify-panels
  //     B 批 2026-11-17）后该节点亦不再建，页面零标题节点；
  //   · 面板头 `.search-modal-header`（重复标题 + 头图标所在层）整块隐藏；
  //   · 底部说明行 `#mh-search-foot` 连 DOM 一起删除（不是 display:none）。
  // 🔴 会话内独立搜索批（2026-11-06 作者令「不要有省略 那就用搜索两个字吧」）：
  //   页名胶囊随 search.title 同批改「搜索」二字（「搜索聊天记录」整族退役）。
  await expect(page.locator('.mobile-page[data-page="search"] .mobile-page-title')).toHaveCount(0);
  await expect(page.locator('#search-modal .search-modal-header')).toBeHidden();
  await expect(page.locator('#mh-search-foot')).toHaveCount(0);

  // 范围/代理/工具 select + 搜索钮整行退场（裁定②：恒主会话）。
  const filtersDisplay = await page.locator('#search-modal .search-filters').evaluate((el) => getComputedStyle(el).display);
  expect(filtersDisplay).toBe('none');
  await expect(page.locator('#search-scope')).toBeHidden();
  // 夹具服务端不驱动 scope —— 兜底断言：pin 腿已把档值钉在 current。
  // 🔴 本条原写 `inputValue()`，在 main tip（f69c4b26f）上**已经红**：三枚筛选件
  // 早由 `<select>` 迁成 `<button class="cfg-select">`（chatUi.js:203-205，值走
  // JS `.value` 属性 + 自绘动作表菜单，见 chatSearch.js setFilterOptions/openToolMenu）
  // ⇒ 它们不是表单控件，`inputValue()` 必抛。改为读 `.value` 属性（真契约）。
  expect(await page.locator('#search-scope').evaluate((el) => el.value)).toBe('current');

  // 筛选：第一行 = 类型·日期 tabs。
  await expect(page.locator('#search-modal .search-tabs .search-tab')).toHaveCount(5);
  // 🔴 search-page-density 批（2026-11-21 作者令①「删掉『全部来源』大下拉框，
  // 来源筛选只用 chips 一行」）：旧断言点名的角色行 select `#mh-search-kind`
  // **整件退役**（CSS/DOM 双侧零节点），来源筛选只剩 chips 三档
  // （全部来源/用户/助手）——本条改为断言 chips 行在场 + 三档标签。
  await expect(page.locator('#mh-search-kind')).toHaveCount(0);
  await expect(page.locator('#mh-search-kind-row')).toHaveCount(0);
  const chips = page.locator('#search-filter-row #search-source-chips .search-source-chip');
  await expect(chips).toHaveCount(3);
  expect(await chips.allTextContents()).toEqual(['全部来源', '用户', '助手']);

  // 说明行已退役（同批作者令）⇒ 顶部只剩一个胶囊：可见标题节点数 = 0（面板内）。
  const visibleTitles = await page.evaluate(() => [...document.querySelectorAll('#search-modal-title')]
    .filter((el) => {
      const b = el.getBoundingClientRect();
      return getComputedStyle(el).display !== 'none' && b.width > 0 && b.height > 0;
    }).length);
  expect(visibleTitles).toBe(0);
  expect(errors).toEqual([]);
});

test('T3 status prefix「主会话」+ mark highlight + session prefix hidden + kind filter', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  await page.locator('#search-keyword').fill('量子纠缠');
  await expect(page.locator('#search-status')).toHaveText(/^主会话 · 2 条结果/, { timeout: 8000 });

  // 结果行：2 行（user/ai——tool 种植行被排除，作者令①「搜索结果只搜对话」），
  // 会话名前缀退场（裁定②）。
  const rows = page.locator('#search-results .search-result');
  await expect(rows).toHaveCount(2);
  const sessDisplay = await rows.first().locator('.search-result-session').evaluate((el) => getComputedStyle(el).display);
  expect(sessDisplay).toBe('none');

  // mark 命中高亮 = sapphire 30%（§8-3 映射字面值）。
  const markBg = await rows.first().locator('.search-result-preview mark').first().evaluate((el) => getComputedStyle(el).backgroundColor);
  expect(markBg).toBe('rgba(91, 127, 191, 0.3)');

  // 来源过滤：选「用户」→ 仅 user 行；回「全部来源」→ 复原 2 行。
  // （search-page-density 批：角色 select `#mh-search-kind` 退役，改点 chips。）
  const chips = page.locator('#search-filter-row .search-source-chip');
  await chips.filter({ hasText: '用户' }).click();
  await expect(rows).toHaveCount(1, { timeout: 8000 });
  await expect(rows.first()).toHaveAttribute('data-kind', 'user');
  await chips.filter({ hasText: '全部来源' }).click();
  await expect(rows).toHaveCount(2, { timeout: 8000 });
  expect(errors).toEqual([]);
});

test.describe('reduced motion', () => {
  test.use({ reducedMotion: 'reduce' });

  test('T4 reduced-motion: no morph animation class on the panel (§8-8 mapping)', async ({ page }) => {
    const errors = watchErrors(page);
    await openPanel(page);
    const cls = await page.locator('#search-modal').getAttribute('class');
    expect(cls || '').not.toMatch(/mh-morph-anim|mh-morph-close/);
    const anim = await page.locator('#search-modal').evaluate((el) => getComputedStyle(el).animationName);
    expect(anim === 'none' || anim === '').toBe(true);
    expect(errors).toEqual([]);
  });
});
