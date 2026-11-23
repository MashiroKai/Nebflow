// bg-panel-history-fold.spec.mjs — 后台面板历史卡堆叠折叠闸（作者令 2026-11-23「很多卡在那」）。
//
// 断言面：
//   H1 渲染行数上限：20 条终态历史顶层行 ⇒ 面板内可见 .bg-task-row ≤ BG_HISTORY_VISIBLE（6）。
//   H2 折叠结构：恰一个 [data-bg-history-fold]，钮文案含剩余条数（20-6=14）。
//   H3 展开：点折叠钮 ⇒ 全部 20 行出镜，钮切为「收起历史」。
//   H4 清除历史视图：点清除 ⇒ 终态历史行本地隐藏（可见行 0），折叠结构仍在（可再展示）。
//   H5 运行中行永不折叠：1 条 running + 20 条历史 ⇒ running 行恒在。
//   H6 负对照：折叠上限归零（不折叠）时可见行 = 20 ⇒ 上限闸确实生效（非空转）。
//
// Run: npx playwright test tests/bg-panel-history-fold.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'hist-root-session';
const LIST_ROWS = '#bg-dropdown .bg-section-agents .bg-dropdown-list .bg-task-row';
const HIST_N = 20;
const VISIBLE = 6;

function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId, name: sessionId, taskDescription: '', ...over,
  };
}

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

/** 灌 N 条终态历史（hist-* 前缀）+ 可选 1 条运行中行；终态以 done=true 装置（同 struct-parent ⑤）。 */
async function seedHistory(page, { n = HIST_N, running = false } = {}) {
  for (let i = 0; i < n; i++) await inject(page, rootStart(`hist-${String(i).padStart(2, '0')}`, { status: 'Processing', name: `历史${i}` }));
  if (running) await inject(page, rootStart('live-running-01', { status: 'Processing', name: '在跑甲' }));
  await page.evaluate(async (n) => {
    const s = (await import('/js/state.js')).default;
    for (const bucket of Object.values(s.sessionBgAgents)) {
      for (const k of Object.keys(bucket)) {
        if (k.startsWith('hist-')) { bucket[k].done = true; bucket[k].status = 'Completed'; }
      }
    }
  }, n);
}

const rowCount = (page) => page.locator(LIST_ROWS).count();

test('H1+H2 渲染行数上限 + 折叠结构：20 条历史 ⇒ 可见 ≤ 6，钮文案 14', async ({ page }) => {
  await bootApp(page);
  await seedHistory(page);
  await openPanel(page);
  const visible = await rowCount(page);
  expect(visible).toBeLessThanOrEqual(VISIBLE);
  expect(visible).toBeGreaterThan(0);
  const fold = page.locator('[data-bg-history-fold]');
  await expect(fold).toHaveCount(1);
  await expect(fold.locator('[data-bg-history-toggle]')).toContainText(String(HIST_N - VISIBLE));
});

test('H3 展开：点折叠钮 ⇒ 全部历史出镜，钮切为收起', async ({ page }) => {
  await bootApp(page);
  await seedHistory(page);
  await openPanel(page);
  await page.locator('[data-bg-history-toggle]').click();
  await page.waitForTimeout(150);
  expect(await rowCount(page)).toBe(HIST_N);
  await expect(page.locator('[data-bg-history-toggle]')).toContainText('收起');
  // 收起回去。
  await page.locator('[data-bg-history-toggle]').click();
  await page.waitForTimeout(150);
  expect(await rowCount(page)).toBeLessThanOrEqual(VISIBLE);
});

test('H4 清除历史视图：终态历史本地隐藏，折叠结构保留', async ({ page }) => {
  await bootApp(page);
  await seedHistory(page);
  await openPanel(page);
  await page.locator('[data-bg-history-clear]').click();
  await page.waitForTimeout(150);
  expect(await rowCount(page)).toBe(0);
  await expect(page.locator('[data-bg-history-clear]')).toHaveCount(0); // 无候选 ⇒ 结构随之消失
});

test('H5 运行中行永不折叠：1 条 running + 20 条历史 ⇒ running 行恒在', async ({ page }) => {
  await bootApp(page);
  await seedHistory(page, { running: true });
  await openPanel(page);
  const names = await page.locator(LIST_ROWS).evaluateAll((els) => els.map((e) => e.querySelector('.bg-task-name')?.textContent || ''));
  expect(names.some((n) => n.includes('在跑甲'))).toBe(true);
  expect(names.length).toBeLessThanOrEqual(VISIBLE + 1);
});

test('H6 负对照：历史 ≤ 上限时不出折叠钮（上限闸不误伤小规模）', async ({ page }) => {
  await bootApp(page);
  await seedHistory(page, { n: VISIBLE });
  await openPanel(page);
  expect(await rowCount(page)).toBe(VISIBLE);
  await expect(page.locator('[data-bg-history-fold]')).toHaveCount(1); // 结构仍在（清除入口）
  await expect(page.locator('[data-bg-history-toggle]')).toHaveCount(0); // 无可折叠条数 ⇒ 不给折叠钮
});
