// project-desc-edit.spec.mjs — Project 面板侧描述写穿 + 设备上下文补全（台账 #920，2026-10-27）。
//
// 锁定（前端面；引擎面 ProjectEventSpec 钉）：
//  ① 描述行**恒渲染**：有描述 = 文本；无描述 = 占位文案 + is-empty；每卡一枚编辑钮
//     （.project-desc-edit-btn，lucide pencil）；
//  ② 就地编辑：点 ✎ → textarea 预填现值 + 保存/取消；取消 = 编辑器收起、零请求；
//  ③ 保存 → POST /api/projects/<name>/description（body {description}）→ 卡片描述槽
//     定点写回（零 reload，window 标记跨全程存活）；
//  ④ WS 回声：projectDescriptionUpdated 帧按名定点写卡（其他端改的实时面），
//     is-empty 态随值翻转，data-desc-raw 同步；
//  ⑤ 编辑中的卡不吃帧（不覆盖用户输入；取消后仍是旧值）；
//  ⑥ 设备上下文（#913 device 存储值的面板消费）：origin 缺席而 device= 对端 deviceId
//     ⇒ .dev-badge 兜底（data-device-id）；device=self/缺席 = 本机 ⇒ 零徽标；
//  ⑦ projectCreated 帧带 device 键 ⇒ 定点插卡 + 徽标在位（normalizeProjectRow 透传）。
//
// Run: npx playwright test tests/project-desc-edit.spec.mjs（workers=1 见 playwright.config.mjs）

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

/** 可变项目状态（POST description 后同步改写，模拟引擎存储面）。 */
let projectsState;
let descPosts = [];
let sendToPage = () => {};   // 桩 → 页面的主动帧通道（WS 回声 / projectCreated）

async function bootApp(page) {
  projectsState = [
    { name: 'demo-a', workspace: '/tmp/ws-a', agentFile: '/tmp/ws-a/AGENTS.md', description: '既有描述', createdAt: 1 },
    { name: 'demo-b', workspace: '/tmp/ws-b', agentFile: '/tmp/ws-b/AGENTS.md', description: '', createdAt: 2 },
    // #920 ⑥：origin 缺席 + device 存储值（#913 回填形）= 徽标兜底面
    { name: 'demo-dev', workspace: '/tmp/ws-dev', agentFile: '/tmp/ws-dev/AGENTS.md', description: '远端项目', createdAt: 3, device: 'dev-xyz-9' },
    { name: 'demo-self', workspace: '/tmp/ws-self', agentFile: '/tmp/ws-self/AGENTS.md', description: '本机项目', createdAt: 4, device: 'self' },
  ];
  descPosts = [];

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });

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
    const descMatch = p.match(/^\/api\/projects\/([^/]+)\/description$/);
    if (descMatch && route.request().method() === 'POST') {
      const name = decodeURIComponent(descMatch[1]);
      let body = {};
      try { body = JSON.parse(route.request().postData() || '{}'); } catch { /* 空 body = 清空 */ }
      const desc = typeof body.description === 'string' ? body.description : '';
      descPosts.push({ name, description: desc });
      const row = projectsState.find((x) => x.name === name);
      if (row) row.description = desc;
      // 模拟引擎成功腿的 WS 回声（帧单点 = ProjectActor.projectDescriptionUpdatedFrame）
      sendToPage(JSON.stringify({ type: 'projectDescriptionUpdated', project: name, description: desc, updatedAt: Date.now() }));
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ saved: true }),
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
    sendToPage = (raw) => ws.send(raw);
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
  await expect(page.locator('.project-card[data-project="demo-a"]')).toBeVisible({ timeout: 10000 });
  for (const n of ['demo-b', 'demo-dev', 'demo-self']) {
    await expect(page.locator(`.project-card[data-project="${n}"]`)).toBeVisible();
  }
}

const descValue = (page, name) => page.locator(`.project-card[data-project="${name}"] [data-desc-value]`);

// ── ① 恒渲染 + 编辑钮 + 设备徽标兜底 ─────────────────────────────────────────
test('desc row always rendered: text / empty-placeholder / edit button / device-badge fallback', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjectsPanel(page);

  // 四卡都有描述行 + 编辑钮（lucide svg 已替换 <i>）
  await expect(page.locator('.project-desc-row')).toHaveCount(4);
  await expect(page.locator('.project-desc-edit-btn')).toHaveCount(4);
  await expect(page.locator('.project-card[data-project="demo-a"] .project-desc-edit-btn svg')).toBeVisible();

  // 有描述 = 文本原样；无描述 = is-empty + 占位文案
  await expect(descValue(page, 'demo-a')).toHaveText('既有描述');
  const emptyCls = await descValue(page, 'demo-b').getAttribute('class');
  expect(emptyCls).toContain('is-empty');
  expect((await descValue(page, 'demo-b').textContent()) || '').not.toBe('');

  // 设备上下文：device=对端 id 且无 origin ⇒ 徽标兜底；device=self ⇒ 零徽标
  await expect(page.locator('.project-card[data-project="demo-dev"] .dev-badge')).toHaveAttribute('data-device-id', 'dev-xyz-9');
  expect(await page.locator('.project-card[data-project="demo-self"] .dev-badge').count()).toBe(0);
  expect(await page.locator('.project-card[data-project="demo-a"] .dev-badge').count()).toBe(0);

  expect(pageErrors).toEqual([]);
});

// ── ② 取消：零请求、值保持 ───────────────────────────────────────────────────
test('edit → cancel: editor closes, no POST, value untouched', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  await page.locator('.project-card[data-project="demo-a"] .project-desc-edit-btn').click();
  const ta = page.locator('.project-card[data-project="demo-a"] .project-desc-input');
  await expect(ta).toBeVisible();
  await expect(ta).toHaveValue('既有描述');
  await page.locator('.project-card[data-project="demo-a"] .project-desc-cancel').click();
  await expect(ta).toHaveCount(0);
  expect(descPosts.length).toBe(0);
  await expect(descValue(page, 'demo-a')).toHaveText('既有描述');
  await expect(descValue(page, 'demo-a')).toBeVisible();
});

// ── ③ 保存：POST 载荷 + 定点写回 + 零 reload ─────────────────────────────────
test('edit → save: POST /description payload + in-place update + zero reload', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  await page.evaluate(() => { window.__descNoReload = true; });

  await page.locator('.project-card[data-project="demo-a"] .project-desc-edit-btn').click();
  const ta = page.locator('.project-card[data-project="demo-a"] .project-desc-input');
  await expect(ta).toBeVisible();
  await ta.fill('改后的描述');
  await page.locator('.project-card[data-project="demo-a"] .project-desc-save').click();

  await expect.poll(() => descPosts.length, { timeout: 5000 }).toBe(1);
  expect(descPosts[0]).toEqual({ name: 'demo-a', description: '改后的描述' });
  // 定点写回（编辑器收起 + 值槽新文本）；零 reload（标记存活）
  await expect(ta).toHaveCount(0);
  await expect(descValue(page, 'demo-a')).toHaveText('改后的描述');
  expect(await page.evaluate(() => window.__descNoReload)).toBe(true);

  // 清空腿：保存空串 → POST description:""（引擎删键语义）
  await page.locator('.project-card[data-project="demo-a"] .project-desc-edit-btn').click();
  const ta2 = page.locator('.project-card[data-project="demo-a"] .project-desc-input');
  await ta2.fill('');
  await page.locator('.project-card[data-project="demo-a"] .project-desc-save').click();
  await expect.poll(() => descPosts.length, { timeout: 5000 }).toBe(2);
  expect(descPosts[1]).toEqual({ name: 'demo-a', description: '' });
  const emptyCls = await descValue(page, 'demo-a').getAttribute('class');
  expect(emptyCls).toContain('is-empty');
});

// ── ④ WS 帧按名定点写 ────────────────────────────────────────────────────────
test('projectDescriptionUpdated frame updates the card in place (other-session edit)', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  sendToPage(JSON.stringify({ type: 'projectDescriptionUpdated', project: 'demo-b', description: '来自帧的描述', updatedAt: Date.now() }));
  await expect(descValue(page, 'demo-b')).toHaveText('来自帧的描述', { timeout: 5000 });
  expect((await descValue(page, 'demo-b').getAttribute('class')) || '').not.toContain('is-empty');
  expect(await descValue(page, 'demo-b').getAttribute('data-desc-raw')).toBe('来自帧的描述');
  // 帧不产生请求（回声 ≠ 重拉）
  expect(descPosts.length).toBe(0);
});

// ── ⑤ 编辑中不吃帧 ───────────────────────────────────────────────────────────
test('frame while editing does not clobber the input; cancel keeps the pre-frame value', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  await page.locator('.project-card[data-project="demo-a"] .project-desc-edit-btn').click();
  const ta = page.locator('.project-card[data-project="demo-a"] .project-desc-input');
  await expect(ta).toHaveValue('既有描述');
  sendToPage(JSON.stringify({ type: 'projectDescriptionUpdated', project: 'demo-a', description: '帧值', updatedAt: Date.now() }));
  await page.waitForTimeout(400); // 帧若被误消费也会立即写 DOM——留窗口
  await expect(ta).toHaveValue('既有描述'); // 输入不被覆盖
  await page.locator('.project-card[data-project="demo-a"] .project-desc-cancel').click();
  await expect(descValue(page, 'demo-a')).toHaveText('既有描述'); // 编辑中跳过 ⇒ 旧值保留
});

// ── ⑥ projectCreated 帧 + device 键 ⇒ 插卡带徽标 ─────────────────────────────
test('projectCreated frame with device key inserts a card carrying the device badge', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  sendToPage(JSON.stringify({
    type: 'projectCreated', project: 'demo-new',
    row: { name: 'demo-new', workspace: '/tmp/ws-new', agentFile: '/tmp/ws-new/AGENTS.md', description: '', createdAt: 9, origin: null, device: 'dev-new-7' },
    mounted: true,
  }));
  await expect(page.locator('.project-card[data-project="demo-new"]')).toBeVisible({ timeout: 5000 });
  await expect(page.locator('.project-card[data-project="demo-new"] .dev-badge')).toHaveAttribute('data-device-id', 'dev-new-7');
  // 新卡描述行恒渲染（空态）
  const cls = await page.locator('.project-card[data-project="demo-new"] [data-desc-value]').getAttribute('class');
  expect(cls).toContain('is-empty');
});
