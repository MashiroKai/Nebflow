// mobile-p10-plugins.spec.mjs — P10「插件」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P10 + 开放点①②③④按设计批推荐）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   页范式 …… 右列键推入 .mobile-page（壳既有）；内容面 = 收编桌面 plugins
//        pane（GET /api/plugins 冻结契约 C6，桌面零改动）。
//   §2-P10 插件卡 …… 桌面条目式形态同构：名+版本·作者 meta → 状态药丸 →
//        行尾分发开关（nb-toggle）+ chevron 展开详情（描述/组成 chips/
//        Plugin.md/actions）。
//   已封禁整卡降透明 …… 桌面红边保留 + 手机层 opacity .55 叠加（纯漆面）。
//   开放点① 三态 pill/「任务分发中」灰 pill …… 桌面态机原样（本夹具只布
//        blocked/on 两态；transition 态桌面既有）。
//   开放点② 分发开关行内常驻 …… 桌面收起态行尾开关既有（data-plugin-dispatch-row）。
//   开放点③④ 拒载列表/无安装入口 …… 桌面既有（rejected 渲染 / 无安装钮），
//        夹具 rejected 空册。
//   样张「组成 chips 在卡面」…… 桌面 chips 在展开详情（accordion 既有信息
//        层级），不挪 DOM —— 记偏差（报告）。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p10-plugins.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'p10-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function pluginsFixture() {
  return { plugins: [
    { name: 'web-search', version: '1.4.0', author: 'nebflow', description: '联网检索与网页摘要', skills: ['web-search'], mcpServers: [], toolsExtension: null, blocked: false, contentChanged: false },
    { name: 'legacy-scraper', version: '0.9.2', author: '3rd-party', description: '旧版抓取（已封禁示例）', skills: ['scrape'], mcpServers: ['scraper-mcp'], toolsExtension: null, blocked: true, contentChanged: false },
  ], rejected: [] };
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
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/plugins') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pluginsFixture()) });
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

async function pushPage(page, face) {
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await page.locator(`.mh-dd[data-dd="right"] [data-face="${face}"]`).click();
  await expect(page.locator(`.mobile-page[data-page="${face}"]`)).toHaveClass(/live/);
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

test('T1 plugins page: adopted pane, two cards stacked full-width', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'plugins');

  // pane 收编 + 推入终态 —— 收编是异步腿（动态 import + openTab 完成才
  // adoptPane），重试断言（P3 T2/P6 既有修法同款）。
  await expect(page.locator(
    '.mobile-page[data-page="plugins"] .mobile-page-body .canvas-tab-pane[data-tab-id="plugins"]',
  )).toHaveCount(1);
  const pageBox = () => page.locator('.mobile-page[data-page="plugins"]').evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { x: r.x, w: Math.round(r.width) };
  });
  await expect.poll(pageBox, { timeout: 5000 }).toEqual({ x: 0, w: 375 });

  const cards = page.locator('.plugins-card');
  await expect(cards).toHaveCount(2);
  const b1 = await cards.nth(0).boundingBox();
  const b2 = await cards.nth(1).boundingBox();
  expect(b1.width).toBeGreaterThanOrEqual(340);
  expect(b2.y).toBeGreaterThan(b1.y + b1.height - 1);
  expect(errors).toEqual([]);
});

test('T2 card form: name + meta, dispatch toggle row-end, expand shows composition chips', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'plugins');

  const c1 = page.locator('.plugins-card[data-plugin="web-search"]');
  await expect(c1.locator('.plugins-card-name')).toHaveText('web-search');
  // meta = 版本 · 作者（样张 chips 族的版本·作者两粒在收起行）。
  await expect(c1.locator('.plugins-card-meta')).toContainText('v1.4.0');
  await expect(c1.locator('.plugins-card-meta')).toContainText('nebflow');
  // 行尾分发开关（开放点②：收起态行内常驻，data-plugin-dispatch-row）。
  const toggle = c1.locator('[data-plugin-dispatch-row="web-search"]');
  await expect(toggle).toBeVisible();
  expect(await toggle.getAttribute('aria-checked')).toBe('true');
  expect(await toggle.evaluate((el) => el.classList.contains('nb-toggle'))).toBe(true);

  // 展开详情：组成 chips（技能/MCP 计数）—— 桌面 accordion 既有层级。
  await c1.locator('.plugins-card-head').click();
  const detail = c1.locator('.plugins-item-detail');
  await expect(detail).toBeVisible();
  await expect(detail.locator('.plugins-card-composition')).toContainText('技能');
  expect(errors).toEqual([]);
});

test('T3 blocked card: dimmed, banned pill, dispatch disabled; back contract', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'plugins');

  const c2 = page.locator('.plugins-card[data-plugin="legacy-scraper"]');
  await expect(c2.locator('.plugins-card-name')).toHaveText('legacy-scraper');
  // 整卡降透明（样张 P10；桌面红边保留 + 手机层 opacity 叠加）。
  const opacity = await c2.evaluate((el) => getComputedStyle(el).opacity);
  expect(parseFloat(opacity)).toBeLessThan(1);
  // 已封禁药丸 + 分发开关禁用。
  await expect(c2.locator('.plugins-state-pill')).toContainText('已封禁');
  expect(await c2.locator('[data-plugin-dispatch-row]').getAttribute('disabled')).not.toBeNull();

  // 返回契约。
  await page.locator('.mobile-page[data-page="plugins"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="plugins"]')).not.toHaveClass(/live/);
  expect(await page.locator('.mobile-page.live').count()).toBe(0);
  expect(errors).toEqual([]);
});
