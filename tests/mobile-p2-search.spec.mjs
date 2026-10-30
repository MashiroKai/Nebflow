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
  // 关闭契约：面板关闭 ⇒ overlay 摘 .on（§8-5 面族映射）+ 栈弹回聊天根。
  await page.locator('#search-modal-close').click();
  await expect(page.locator('#search-overlay')).not.toHaveClass(/on/);
  await expect(page.locator('.mobile-page[data-page="search"]')).not.toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('chat');
  expect(errors).toEqual([]);
});

test('T2 main-session face: head icon, scope retired, kind row = second filter row, foot copy (v3.6②)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  // 面板头图标 = 来源键 i-search（§8-2）。
  await expect(page.locator('.mh-search-head-ic svg')).toHaveCount(1);

  // 范围/代理/工具 select + 搜索钮整行退场（裁定②：恒主会话）。
  const filtersDisplay = await page.locator('#search-modal .search-filters').evaluate((el) => getComputedStyle(el).display);
  expect(filtersDisplay).toBe('none');
  await expect(page.locator('#search-scope')).toBeHidden();
  // 夹具服务端不驱动 scope —— 兜底断言：pin 腿已把 select 值钉在 current。
  expect(await page.locator('#search-scope').inputValue()).toBe('current');

  // 筛选两行：第一行 = 类型·日期 tabs；第二行 = 角色行（首档「全部来源」；
  // 「工具」档随 2026-10-30 作者令①退役——tool 行不入搜索结果）。
  await expect(page.locator('#search-modal .search-tabs .search-tab')).toHaveCount(5);
  const kindOptions = page.locator('#mh-search-kind option');
  await expect(kindOptions).toHaveCount(4);
  expect(await kindOptions.nth(0).textContent()).toBe('全部来源');
  expect(await kindOptions.nth(1).textContent()).toBe('用户');
  expect(await kindOptions.nth(3).textContent()).toBe('AI');

  // 脚注：主会话说明 + 空态两腿（现网同款键）。
  const foot = await page.locator('#mh-search-foot').textContent();
  expect(foot).toContain('仅检索当前主会话');
  expect(foot).toContain('无匹配结果');
  expect(foot).toContain('试试缩短关键词或清除日期/类型筛选');
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

  // 角色过滤：选「用户」→ 仅 user 行；回「全部来源」→ 复原 2 行。
  await page.locator('#mh-search-kind').selectOption('user');
  await expect(rows).toHaveCount(1, { timeout: 8000 });
  await expect(rows.first()).toHaveAttribute('data-kind', 'user');
  await page.locator('#mh-search-kind').selectOption('');
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
