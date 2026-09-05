// project-panel-archive.spec.mjs — Project 面板归档按钮（迁移方案 v2 §6.1）前端回归。
//
// 锁定：
//  - 每个 project 卡片有归档按钮（.project-archive-btn，header 摘要右侧）
//  - 归档可逆 → 中性玻璃样式：按钮与确认弹层计算色值均非 danger 红
//    （按钮走 sapphire.css Pattern A；弹层确认钮走 #delete-box.confirm-neutral）
//  - 点击归档 → 确认弹层（window.__showConfirm → #delete-box），文案含「归档后不在
//    任务面板显示 / workspace 文件保留」语义
//  - 确认 → POST /api/projects/<name>/archive（载荷正确：无 body、URL 带 name）
//  - 归档后该卡片从列表消失，无需刷新页面（同页重渲；window 标记证明零 reload）
//  - 取消 → 无请求发出，卡片保留
//  - 亮暗双主题截图落盘（默认 ~/.nebflow/docs/Nebflow/，ARCHIVE_SHOT_DIR 可覆盖）
//
// Run: node "/Users/dev/Claude code/Nebflow/node_modules/@playwright/test/cli.js" test tests/project-panel-archive.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = process.env.ARCHIVE_SHOT_DIR || '/Users/dev/.nebflow/docs/Nebflow';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/** 可变项目状态：POST archive 后模拟后端 list 过滤（归档项从 GET /api/projects 消失）。 */
let projectsState;
let archivePosts = [];

async function bootApp(page) {
  projectsState = [
    { name: 'demo-keep', workspace: '/tmp/ws-keep', agentFile: '/tmp/ws-keep/AGENTS.md', description: 'stays visible', createdAt: 1 },
    { name: 'demo-gone', workspace: '/tmp/ws-gone', agentFile: '/tmp/ws-gone/AGENTS.md', description: 'will be archived', createdAt: 2 },
  ];
  archivePosts = [];

  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/projects' && route.request().method() === 'GET') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ projects: projectsState }),
      });
    }
    const archMatch = p.match(/^\/api\/projects\/([^/]+)\/archive$/);
    if (archMatch && route.request().method() === 'POST') {
      archivePosts.push({ name: decodeURIComponent(archMatch[1]), body: route.request().postData() });
      projectsState = projectsState.filter((x) => x.name !== decodeURIComponent(archMatch[1]));
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ archived: true, archivedAt: Date.now() }),
      });
    }
    if (p.match(/^\/api\/projects\/[^/]+\/flow-map$/)) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'x', updatedAt: Date.now() } }),
      });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
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
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: 'root',
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 });
}

async function openProjectsPanel(page) {
  await page.evaluate(() => import('/js/projectTab.js').then((m) => m.openProjectsTab()));
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible({ timeout: 10000 });
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible();
}

/** danger 红判定：r 通道显著高于 g/b（如 #e5484d、rgba(220,60,60,*)、#e55）。全透明视为中性。 */
function isDangerRed(cssColor) {
  const m = String(cssColor).match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
  if (!m) return false;
  if (m[4] !== undefined && parseFloat(m[4]) === 0) return false;
  const r = +m[1], g = +m[2], b = +m[3];
  return r > 150 && r - g > 60 && r - b > 60;
}

/** 按钮关键计算样式（color/border/background/backdrop-filter）。 */
function archiveBtnStyles(locator) {
  return locator.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { color: cs.color, border: cs.borderTopColor, bg: cs.backgroundColor, bf: cs.backdropFilter || '' };
  });
}

test('archive button visible; confirm dialog; card removed without reload; POST payload correct', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjectsPanel(page);

  // ① 归档按钮可见：每张卡片 header 各一枚，lucide archive 图标
  const btnKeep = page.locator('.project-card[data-project="demo-keep"] .project-archive-btn');
  const btnGone = page.locator('.project-card[data-project="demo-gone"] .project-archive-btn');
  await expect(btnKeep).toBeVisible();
  await expect(btnGone).toBeVisible();
  await expect(btnKeep.locator('svg')).toBeVisible(); // createIconsIn 已把 <i> 换成 svg

  // 零 reload 证明标记
  await page.evaluate(() => { window.__archiveNoReload = true; });

  // ② 取消路径：点归档 → 确认弹层出现 → 取消 → 无请求、卡片保留
  await btnGone.click();
  const deleteBox = page.locator('#delete-box');
  await expect(deleteBox).toBeVisible();
  // 归档可逆 → 弹层走中性 tone：#delete-box 带 confirm-neutral，确认钮计算色非 danger 红
  expect(await deleteBox.getAttribute('class')).toContain('confirm-neutral');
  const confirmBtn = page.locator('#delete-confirm');
  expect(isDangerRed(await confirmBtn.evaluate((el) => getComputedStyle(el).color))).toBe(false);
  expect(isDangerRed(await confirmBtn.evaluate((el) => getComputedStyle(el).borderTopColor))).toBe(false);
  const confirmTitle = await page.locator('#delete-title').textContent();
  expect(confirmTitle).toContain('demo-gone');
  const confirmMsg = await page.locator('#delete-msg').textContent();
  expect(confirmMsg).toContain('demo-gone');
  expect(confirmMsg.length).toBeGreaterThan(10); // 有实际确认文案
  await page.locator('#delete-cancel').click();
  await expect(deleteBox).not.toBeVisible();
  expect(archivePosts.length).toBe(0); // 取消 → 零请求
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible();

  // ③ 确认路径：点归档 → 确认 → POST 发出 → 卡片消失（同页重渲，零 reload）
  await btnGone.click();
  await expect(deleteBox).toBeVisible();
  await page.locator('#delete-confirm').click();
  await expect(deleteBox).not.toBeVisible();

  // 载荷断言：URL 带项目名、POST 无 body
  expect(archivePosts.length).toBe(1);
  expect(archivePosts[0].name).toBe('demo-gone');
  expect(archivePosts[0].body).toBeNull();

  // 列表实时移除（rerenderProjectsTab 200ms 防抖 + refetch 已过滤）
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toHaveCount(0, { timeout: 5000 });
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible();

  // 零 reload：点击前设的 window 标记仍在（页面没刷新过）
  expect(await page.evaluate(() => window.__archiveNoReload)).toBe(true);
  expect(pageErrors).toEqual([]);
});

test('dark & light theme screenshots of the Projects panel with archive buttons', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);

  // ── 静态 token 断言：归档按钮/确认钮配色只许引用既有 token，无 danger 红字面量 ──
  const panelCss = readFileSync(join(WEB, 'css', 'projectPanel.css'), 'utf8');
  const sapphireCss = readFileSync(join(WEB, 'css', 'sapphire.css'), 'utf8');
  const modalCss = readFileSync(join(WEB, 'css', 'modal.css'), 'utf8');
  expect(panelCss).not.toMatch(/project-archive-btn:hover\s*{[^}]*--color-error/);
  expect(panelCss).not.toContain('rgba(229, 72, 77');
  expect(panelCss).not.toContain('#e5484d');
  // sapphire.css Pattern A（glass-control 标准）注册：ghost 基态 + 中性玻璃 hover
  expect(sapphireCss).toContain('.project-archive-btn {');
  expect(sapphireCss).toContain('.project-archive-btn:hover {');
  // 确认弹层中性 variant：#delete-box.confirm-neutral → glass-control 材质、无红字面量
  expect(modalCss).toContain('#delete-box.confirm-neutral #delete-confirm');
  const neutralBlock = modalCss.match(/#delete-box\.confirm-neutral #delete-confirm\s*{([^}]*)}/);
  expect(neutralBlock).toBeTruthy();
  expect(neutralBlock[1]).toContain('var(--glass-control-bg)');
  expect(neutralBlock[1]).not.toContain('rgba(220, 60, 60');

  const card = page.locator('.project-card[data-project="demo-keep"]');
  const keepBtn = page.locator('.project-card[data-project="demo-keep"] .project-archive-btn');

  for (const scheme of ['dark', 'light']) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.waitForTimeout(400);

    // 整面板截图（沿用既有命名）
    await page.screenshot({ path: join(SHOT_DIR, `20260903_project-archive-btn-${scheme}.png`) });

    // 常态（ghost：透明底/透明边， muted 图标色——全部非红）
    await page.mouse.move(0, 0);
    await page.waitForTimeout(200);
    await card.screenshot({ path: join(SHOT_DIR, `archive-btn-${scheme}-rest.png`) });
    const rest = await archiveBtnStyles(keepBtn);
    expect(isDangerRed(rest.color)).toBe(false);
    expect(isDangerRed(rest.border)).toBe(false);
    expect(isDangerRed(rest.bg)).toBe(false);
    expect(rest.bg).toBe('rgba(0, 0, 0, 0)'); // Pattern A 基态：透明

    // hover（中性玻璃：bg-hover 白系 alpha + backdrop blur，非红高亮）
    await keepBtn.hover();
    await page.waitForTimeout(250);
    await card.screenshot({ path: join(SHOT_DIR, `archive-btn-${scheme}-hover.png`) });
    const hov = await archiveBtnStyles(keepBtn);
    expect(isDangerRed(hov.color)).toBe(false);
    expect(isDangerRed(hov.border)).toBe(false);
    expect(isDangerRed(hov.bg)).toBe(false);
    expect(hov.bg).toMatch(/rgba\(255, 255, 255, 0\.(65|10?)\)/); // --glass-control-bg-hover（浅 0.65 / 深 0.10）
    expect(hov.bf).toContain('blur'); // 玻璃材质生效
  }

  await expect(keepBtn).toBeVisible();
});
