// mobile-p8-projects.spec.mjs — P8「项目」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P8 + 开放点①②③④按设计批推荐）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   页范式 …… 右列键推入 .mobile-page（壳 r2/v3 已落地）：悬浮页头[圆形毛玻璃
//        「<」+ 居中标题] + 全宽滚动页体；内容面 = 收编桌面画板 pane
//        （navController adoptPane，桌面 projectTab 取数/渲染零改动）。
//   §2-P8 项目卡三段 …… 桌面 team-card 范式与样张同构：名+远端徽标 →
//        摘要行[点色+brief/骨架] → 字段行[工作区/AGENTS.md/运行中 agent/说明]；
//        手机层 CSS 形态 = 全宽单列堆叠 + 16px 卡圆角 + 字阶收紧 + 超长
//        ellipsis（样张 §6 口径）。
//   开放点② 远端徽标+离线琥珀点 …… 桌面 .dev-badge / .off-tag 原样（面C）。
//   开放点③ flow-map 摘要进卡 …… 桌面 applyCardSummary 渐进填充原样
//        （样张摘要行 = 桌面 .team-card-summary，无需新 DOM）。
//   开放点①④ 密度/归档折叠 …… 归档 = 桌面源清单过滤（卸载即不出现在
//        名单），无折叠 DOM 可言 —— 记报告；密度 = 本批 CSS 字阶（T2 断言）。
//
// 数据面 = GET /api/projects 夹具 + flow-map 404（未挂载分支）+ neblink 空册，
// 零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p8-projects.spec.mjs

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

const SID = 'p8-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function projectsFixture() {
  return { projects: [
    { name: 'nebflow 主仓', workspace: '/Users/kaiyu/Claude code/Nebflow', description: '网关与编排主仓', agentFile: true, createdAt: Date.now() - 86400_000 },
    { name: '远端巡检', workspace: '/srv/inspect', description: '', agentFile: true, createdAt: Date.now() - 3600_000,
      origin: { device: 'KAI', path: 'C:/inspect' } },
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
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(projectsFixture()) });
      }
      if (p.startsWith('/api/projects/') && p.endsWith('/flow-map')) {
        // 空图谱快照（空闲摘要腿；404 notMounted 分支虽被 app 妥善处理，
        // 但浏览器仍会对非 2xx 记 console error —— 夹具用 200 空节点）。
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'x', updatedAt: Date.now() } }) });
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

/** 开右列 → 点页面键（layoutController 页面键 → navigateTo）。 */
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

test('T1 page shell: full-width push page, glass round back button, adopted pane in body', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'projects');

  // 页面全宽（v3 走廊退役 → 恒 375）+ 互斥 .live。推入 transform 260ms ——
  // 类翻转即采样会踩进位移中段，poll 到终态。
  const pageBox = () => page.locator('.mobile-page[data-page="projects"]').evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { x: r.x, w: Math.round(r.width) };
  });
  await expect.poll(pageBox, { timeout: 5000 }).toEqual({ x: 0, w: 375 });
  expect(await page.locator('.mobile-page.live').count()).toBe(1);

  // 页头返回钮 = 圆形毛玻璃「<」（border-radius 50% ← 15px on 30px 盒）。
  const back = page.locator('.mobile-page[data-page="projects"] .mobile-back-btn');
  await expect(back).toBeVisible();
  const radius = await back.evaluate((el) => getComputedStyle(el).borderRadius);
  expect(radius).toBe('50%');
  const title = await page.locator('.mobile-page[data-page="projects"] .mobile-page-title').textContent();
  expect(title).toBe('项目');

  // 内容面 = 收编桌面 pane（adoptPane）：pane 在页面体内，卡片在其中渲染
  // —— 收编是异步腿（动态 import + openTab 完成才 adoptPane），重试断言
  // （P3 T2/P6 既有修法同款）。
  await expect(page.locator(
    '.mobile-page[data-page="projects"] .mobile-page-body .canvas-tab-pane[data-tab-id="projects"]',
  )).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('T2 project cards: three-part form, remote badge, ellipsis values, full-width stack', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'projects');

  const scroll = page.locator('.mobile-page[data-page="projects"] .team-scroll');
  const cards = scroll.locator('.team-card.project-card');
  await expect(cards).toHaveCount(2);

  // 卡 1（本机）：名 + 三段字段（工作区 / AGENTS.md / 运行中 agent）+ 说明行。
  const c1 = cards.nth(0);
  await expect(c1.locator('.project-card-name')).toHaveText('nebflow 主仓');
  await expect(c1.locator('.project-field-label').first()).toHaveText('工作区');
  const wsVal = c1.locator('.project-field-value.mono');
  await expect(wsVal).toHaveText('/Users/kaiyu/Claude code/Nebflow');
  // 超长 ellipsis（样张 §6：workspace ellipsis + tabular-nums）。
  expect(await wsVal.evaluate((el) => getComputedStyle(el).whiteSpace)).toBe('nowrap');
  expect(await wsVal.evaluate((el) => getComputedStyle(el).textOverflow)).toBe('ellipsis');
  await expect(c1.locator('.project-field', { hasText: 'AGENTS.md' })).toHaveCount(1);
  await expect(c1.locator('.project-field', { hasText: '运行中 agent' })).toHaveCount(1);
  await expect(c1.locator('.project-field-value.desc')).toHaveText('网关与编排主仓');
  // 文件浏览器间接入口（.project-open-btn）手机层退役（既有规则仍在岗）。
  expect(await c1.locator('.project-open-btn').evaluate((el) => getComputedStyle(el).display)).toBe('none');

  // 卡 2（远端）：dev-badge 在场（开放点②）。
  const c2 = cards.nth(1);
  await expect(c2.locator('.dev-badge')).toHaveCount(1);

  // 形态：全宽单列堆叠（T2 密度口径）——卡宽 = 页体宽，纵向排列。
  const b1 = await c1.boundingBox();
  const b2 = await c2.boundingBox();
  expect(b1.width).toBeGreaterThanOrEqual(340);
  expect(b2.y).toBeGreaterThan(b1.y + b1.height - 1);
  // 卡圆角 16（样张页卡口径）。
  const radius = await c1.evaluate((el) => getComputedStyle(el).borderRadius);
  expect(radius).toBe('16px');
  expect(errors).toEqual([]);
});

test('T3 back button pops to chat root; pressed key mirrors the stack', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'projects');
  await page.locator('.mobile-page[data-page="projects"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).not.toHaveClass(/live/);
  expect(await page.locator('.mobile-page.live').count()).toBe(0);
  // 右列键按下态复位（syncPressed 镜像栈顶）。
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  const pressed = await page.locator('.mh-dd[data-dd="right"] [data-face="projects"]').getAttribute('aria-pressed');
  expect(pressed).toBe('false');
  expect(errors).toEqual([]);
});
