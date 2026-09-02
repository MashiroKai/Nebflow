// project-agents-md.spec.mjs — AGENTS.md 迁移 AC-6：前端 Agent.md 查看/编辑入口回归。
//
// 锁定（2026-09-02 AGENTS.md 迁移，文案/契约层）：
//  - Project 卡片字段标签显示 AGENTS.md（不再是 Agent.md）
//  - 点击「查看/编辑」→ overlay 标题 `demo · AGENTS.md`，正文 h3 = AGENTS.md，
//    提示文案含 AGENTS.md 且不再出现旧路径 .nebflow/Agent.md
//  - 编辑内容 → 保存 → PUT /api/projects/demo/agent.md body 带新内容
//    （磁盘落点 = 工作区根 AGENTS.md 由后端 curl / ProjectAgentFileRoutesSpec 验证）
//
// Run: node node_modules/@playwright/test/cli.js test tests/project-agents-md.spec.mjs

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

const DEMO_CONTENT = '# demo template (root AGENTS.md)\n';
let putBodies = [];

async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/projects') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          projects: [{
            name: 'demo', workspace: '/tmp/ws-demo',
            agentFile: '/tmp/ws-demo/AGENTS.md', description: 'demo project', createdAt: 0,
          }],
        }),
      });
    }
    if (p === '/api/projects/demo/flow-map') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'demo', updatedAt: Date.now() } }),
      });
    }
    if (p === '/api/projects/demo/agent.md' && route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ content: DEMO_CONTENT }) });
    }
    if (p === '/api/projects/demo/agent.md' && route.request().method() === 'PUT') {
      putBodies.push(route.request().postDataJSON());
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ saved: true }) });
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

test('project card shows AGENTS.md label; viewer title/hint updated; save PUTs to agent.md', async ({ page }) => {
  putBodies = [];
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 打开 Project 标签页
  await page.evaluate(() => import('/js/projectTab.js').then((m) => m.openProjectsTab()));
  const card = page.locator('.project-card[data-project="demo"]');
  await expect(card).toBeVisible({ timeout: 10000 });

  // ① 卡片字段标签 = AGENTS.md（第二行 .project-field-label），且不再出现裸 Agent.md 标签
  const labels = card.locator('.project-field-label');
  await expect(labels.nth(1)).toHaveText('AGENTS.md');

  // ② 点击查看/编辑 → overlay 标题 demo · AGENTS.md；h3 = AGENTS.md；提示文案含 AGENTS.md
  await card.locator('.project-link-btn[data-open-agent="demo"]').click();
  const overlay = page.locator('.flow-viewer-overlay');
  await expect(overlay).toBeVisible();
  await expect(overlay.locator('.flow-viewer-title')).toHaveText('demo · AGENTS.md');
  await expect(overlay.locator('h3')).toHaveText('AGENTS.md');
  const hint = await overlay.locator('p').first().textContent();
  expect(hint).toContain('AGENTS.md');
  expect(hint).not.toContain('.nebflow/Agent.md');
  expect(hint).not.toContain('Agent.md（'); // 旧中文文案形态不残留

  // ③ 内容来自 GET（磁盘根文件）；编辑 → 保存 → PUT body 带新内容
  const textarea = overlay.locator('#flow-agentfile-textarea');
  await expect(textarea).toHaveValue(DEMO_CONTENT);
  await textarea.fill('# edited by e2e\n');
  await overlay.locator('#flow-agentfile-save').click();
  await expect(overlay.locator('#flow-agentfile-status')).toHaveText(/saved|已保存/i, { timeout: 5000 });
  expect(putBodies.length).toBe(1);
  expect(putBodies[0]).toEqual({ content: '# edited by e2e\n' });

  expect(pageErrors).toEqual([]);
});
