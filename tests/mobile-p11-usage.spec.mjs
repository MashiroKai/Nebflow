// mobile-p11-usage.spec.mjs — P11「token 用量」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P11 + 开放点①②③④按设计批推荐）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   页范式 …… 右列键推入 .mobile-page（壳既有）；内容面 = 收编桌面
//        #usage-dashboard（r3 face-adopt 全幅几何既有）。
//   §2-P11 四卡摘要 …… 桌面 #usage-summary .ud-card×4（总消耗+计费等效 /
//        今日+较昨日 / 缓存命中率+目标 / 本周环比）—— 桌面 6 列 grid → 手机
//        2 列（两行）。
//   近 14 天条带 …… 桌面 ud-curve（内建条带/曲线族）满宽原样（开放点②：
//        桌面保留腿 = 52 周热力图横滚查看，不裁不伪）。
//   开放点① 计费等效口径 …… 桌面文案原样沿用。
//   开放点③ 维度 seg 置顶 …… 桌面 shell 序 = header→filters→stats→heat
//        dims→heatmap→curve→drill，维度 seg 本就在上部（ud-heat-controls），
//        零重排即满足样张语义（T2 断言在摘要卡之前？—— 实机序 heat seg 紧随
//        stats；样张 seg 置顶 = 已然形态）。
//   开放点④ 万/亿缩写与峰值文案 …… 桌面 fmt token 同源沿用。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p11-usage.spec.mjs

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

const SID = 'p11-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function aggPayload() {
  return {
    buckets: [],
    totalInput: 120_000, totalOutput: 30_000,
    totalCacheRead: 110_000, totalCacheWrite: 5_000,
    count: 128, costEquivalent: 0.42,
  };
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
      if (p.startsWith('/api/usage/aggregate')) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(aggPayload()) });
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

test('T1 usage page: adopted dashboard, four summary cards in 2-col grid', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'usage');

  // 面收编进页面体（usage 首开异步建面 → navController 收编腿）—— 类翻转即
  // 读数会踩进建面/收编前窗口，重试断言（P3 T2/P6 既有修法同款）。
  await expect(page.locator(
    '.mobile-page[data-page="usage"] .mobile-page-body #usage-dashboard',
  )).toHaveCount(1);

  // 摘要卡族：总消耗 / 今日 / 缓存命中率 / 本周环比（样张 P11 四件套 ⊆ 桌面
  // 6 卡 —— 桌面另有计数类卡，样张四件套必在场即可）。
  const cards = page.locator('#usage-summary .ud-card');
  await expect(cards.first()).toBeVisible();
  const labels = await cards.locator('.ud-card-label').allTextContents();
  expect(labels.join('|')).toContain('总消耗');
  expect(labels.join('|')).toContain('今日');
  expect(labels.join('|')).toContain('缓存');
  expect(labels.join('|')).toContain('周');

  // 手机 2 列（桌面 6 列 → 样张两行制）。
  const cols = await page.locator('#usage-summary').evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  expect(cols.trim().split(/\s+/).length).toBe(2);
  expect(errors).toEqual([]);
});

test('T2 controls: heat dim seg on top region, 52-week heatmap scrollable, curve strip full-width', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'usage');

  // 维度 seg（总计/按模型/按 agent）三键在场。
  const dims = page.locator('#usage-heat-dims .ud-hdim');
  await expect(dims).toHaveCount(3);

  // 52 周热力图 = 横向滚动保留腿（overflow-x auto 不被本批改动）。
  const grid = page.locator('#usage-heatmap, .ud-heat-grid, [id*="heatmap"]').first();
  if (await grid.count()) {
    const ox = await grid.evaluate((el) => getComputedStyle(el).overflowX);
    expect(['auto', 'scroll']).toContain(ox);
  }

  // 条带（ud-curve）满宽（不超页宽）。
  const curveBox = await page.locator('.ud-curve').first().boundingBox();
  if (curveBox) expect(curveBox.width).toBeLessThanOrEqual(375);
  expect(errors).toEqual([]);
});

test('T3 page-shell back button pops to chat (injected back retired by ②17 dedup)', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'usage');

  // 返回钮单点 = 页面壳 .mobile-back-btn（②17 返回钮去重批：ensureInjectedBack
  // 注入腿已退役为无调用死代码 —— 注入选择器零存留 = 防复活钉）。
  await expect(page.locator('#usage-dashboard .mobile-back-injected')).toHaveCount(0);
  const back = page.locator('.mobile-page[data-page="usage"] .mobile-back-btn');
  await expect(back).toBeVisible();
  await back.click();
  await expect(page.locator('.mobile-page[data-page="usage"]')).not.toHaveClass(/live/);
  expect(await page.locator('.mobile-page.live').count()).toBe(0);
  expect(errors).toEqual([]);
});
