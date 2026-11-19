// model-panel-degraded.spec.mjs — 台账 #937（/model 面板点击全死）降级面 spec 钉
//
// 作者报障（2026-10-26 14:19）：「/model 能打开但上面都点不了」。复验批取证的
// 成因链 = 面板打开后 GET /api/agents/:name/model 全部 401（未登录 / 引擎
// 不可达态，顶部横幅「需要登录/连接断开」同屏实拍在案）⇒ roleState 全 null ⇒
// 修前面板 = 静默僵面：Nebula 块只剩 seed 注记 + 永久禁用的「独立配置」、
// follower 展开只有「加载失败」小字，无一处告诉用户为什么、无恢复入口。
//
// 契约（#937 修复面）：
//   P1 全角色读数失败 ⇒ 面板级降级面（.mp-load-error：标题 + 可读原因 + 重试钮），
//      per-role blocks 不再以僵死形态出场；
//   P2 降级态头部模式钮（统一配置/分开配置）仍可点、零 JS 异常 —— 面板可交互，
//      不假活（点了仍回一致降级面）；
//   P3 恢复腿：重试钮在 API 变健康后一把拉起完整面板（role blocks 渲染、
//      「独立配置」可用 —— refs 由 configData.providers 供给）；
//   P4 partial 失败（一角 401 其余 200）不走面板级降级 —— 可用角色照常渲染，
//      失败角自己带「加载失败」。
//
// 运行：npx playwright test tests/model-panel-degraded.spec.mjs
// （harness 同 device-provenance.spec.mjs：page.route 全拦 + 假 WS，零后端）

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'e2e-root-session';
// 带一个 provider 的 config（refs 供给面 —— P3 断言「独立配置」可用需要非空 refs）。
const CONFIG = {
  llm: { providers: { deepseek: { baseUrl: 'https://api.example', models: [{ id: 'm-1' }] } } },
};
const HEALTHY = (name) => ({
  name, mode: 'follow', chain: null,
  effectiveChain: { preferred: 'deepseek/m-1', fallbacks: [] },
  resolvedFrom: 'seed', current: 'deepseek/m-1', settable: true,
});

/** 最小 boot：全路由拦截 + 假 WS（configData 带 provider）。mode = 全部角色
 *  模型读数的 HTTP 状态（401 = 全失败态；'partial' = 仅 nebflow 401）。 */
async function bootApp(page, mode) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/agents/') && p.endsWith('/model')) {
      const role = decodeURIComponent(p.split('/')[3]);
      const status = mode === 'partial' && role === 'nebflow' ? 401 : (mode === 401 ? 401 : 200);
      if (status !== 200) return route.fulfill({ status, contentType: 'application/json', body: '{"error":"unauthorized"}' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(HEALTHY(role)) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: JSON.stringify(CONFIG), configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(200);
}

/** 打开 /model 面板（与 slash 命令同一模块入口）。 */
async function openPanel(page) {
  await page.evaluate(async () => {
    const mod = await import('/js/modelPanel.js');
    await mod.openModelPanel();
  });
  await page.waitForTimeout(300);
}

test('P1+P2 全角色 401 ⇒ 面板级降级面（原因可读 + 重试钮），头部模式钮仍可点、零 JS 异常', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, 401);
  await openPanel(page);

  await expect(page.locator('#model-panel-overlay')).toBeVisible();
  const face = page.locator('.mp-load-error');
  await expect(face, '降级面出场').toBeVisible();
  await expect(face.locator('.mp-load-error-title')).toHaveText(/加载失败|Failed to load/);
  await expect(face.locator('.mp-load-error-hint')).toContainText(/未登录|Not signed in/);
  await expect(face.locator('.mp-load-error-retry')).toBeVisible();
  // 僵死形态退役：per-role blocks（含永久禁用的「独立配置」）不得同时在场。
  await expect(page.locator('.mp-roleblock')).toHaveCount(0);
  await expect(page.locator('.mp-add-select, .mp-addrow')).toHaveCount(0);

  // P2：头部模式钮可点（点击后仍回一致降级面 —— 状态一致不假活）。
  await page.click('.mp-mode-btn[data-mode="separate"]');
  await expect(page.locator('.mp-mode-btn[data-mode="separate"].on')).toBeVisible();
  await expect(page.locator('.mp-load-error')).toBeVisible();
  await page.click('.mp-mode-btn[data-mode="unified"]');
  await expect(page.locator('.mp-mode-btn[data-mode="unified"].on')).toBeVisible();

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('P3 重试恢复腿：API 转健康后一把拉起完整面板（blocks 渲染 + 独立配置可用）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, 401);
  await openPanel(page);
  await expect(page.locator('.mp-load-error')).toBeVisible();

  // 引擎侧恢复（登录/断连解除）⇒ 模型读数 200。
  await page.route('**/api/agents/*/model', (route) => {
    const url = new URL(route.request().url());
    const role = decodeURIComponent(url.pathname.split('/')[3]);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(HEALTHY(role)) });
  });
  await page.click('.mp-load-error-retry');
  await expect(page.locator('.mp-load-error'), '降级面退场').toHaveCount(0);
  await expect(page.locator('.mp-roleblock')).toHaveCount(2);
  // refs 由 configData providers 供给 ⇒ 「独立配置」fork 钮可用（不再是永久禁用）。
  const forkBtn = page.locator('.mp-roleblock-nebula button');
  await expect(forkBtn).toBeEnabled();

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('P4 partial 失败（一角 401）不走面板级降级：可用角色照常渲染', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, 'partial');
  await openPanel(page);

  await expect(page.locator('.mp-load-error')).toHaveCount(0);
  await expect(page.locator('.mp-roleblock')).toHaveCount(2);
  // 失败角（nebflow follower）自己带「加载失败」小字（展开后可见）。
  // follower 组的可见性归 panelVisibility 管：unified 态整组隐藏（show iff
  // separate），先切「分开配置」再展开失败角。
  await page.click('.mp-mode-btn[data-mode="separate"]');
  await page.locator('.mp-role-head-toggle').first().click();
  // 只钉失败角本体（.mp-role-body 有两处：Nebula 健康块不含失败文案）。
  await expect(page.locator('.mp-roleblock-follower[data-role="nebflow"] .mp-role-body')).toContainText(/加载失败|Failed to load/);

  expect(pageErrors, '零新增页面错误').toEqual([]);
});
