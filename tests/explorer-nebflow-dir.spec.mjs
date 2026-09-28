// explorer-nebflow-dir.spec.mjs — 文件浏览器 .nebflow 目录验收（2026-09-02）。
//
// 背景：项目 .nebflow/ 内的悬空符号链接（flowmap-anim → worktrees/flowmap-anim，
// 目标不存在）曾让后端 listDir 整体失败（dirListing{error}），浏览器里目录
// 「打不开」。修复后：后端逐条目故障隔离，悬空链接降级为 size-0/broken 文件条目。
//
// 本 harness 用真实前端资源（src/main/resources/web 下真实 explorer.js/ws.js/
// canvas.js）+ routeWebSocket mock 驱动完整分发链（ws.js → explorer.js →
// canvas.js）。mock dirListing 帧形态与修复后 WebSocketRoutes.listDirEntries
// 输出严格一致：目录在前、文件按名（大小写不敏感）排序，条目含
// {name,type,size,broken}，悬空条目 flowmap-anim 为 type=file/size=0/broken=true。
//
// 验收语义（对应任务断言①-⑤）：
//   ① 根 listing 含 .nebflow（目录行）
//   ② 展开 .nebflow 成功且子条目齐全（Agent.md/flow-map.json/flow-map-archive.json/
//      flowmap-anim/worktrees），树内无 explorer-error
//   ③ flowmap-anim 呈现为文件行（不炸列表）
//   ④ 点 flow-map.json → readFile → fileContent → Canvas 预览出现
//   ⑤ 点 Agent.md（有效符号链接）→ 内容可读
// 另：亮暗双主题各跑一遍并截图（prefers-color-scheme，CSS 变量全真）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/explorer-nebflow-dir.spec.mjs

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

const SID = 'exp-root-session';
const SHOT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '.nebflow', 'docs', 'Nebflow');

// ── mock 数据：与修复后 listDirEntries 输出形态一致 ──────────────────────

// 项目根：.nebflow 以目录条目出现（前端 HIDDEN_DIRS 不含它——dot 目录可见是既有设计）
const ROOT_ENTRIES = [
  { name: '.nebflow', type: 'dir', size: 0, broken: false },
  { name: 'AGENTS.md', type: 'file', size: 8252, broken: false },
  { name: 'src', type: 'dir', size: 0, broken: false },
  { name: 'package.json', type: 'file', size: 512, broken: false },
];

// .nebflow 展开：目录在前（worktrees），文件按名排序（大小写不敏感），
// flowmap-anim 为悬空链接降级条目（type=file, size=0, broken=true）。
const NEBFLOW_ENTRIES = [
  { name: 'worktrees', type: 'dir', size: 0, broken: false },
  { name: 'Agent.md', type: 'file', size: 8252, broken: false },
  { name: 'flow-map-archive.json', type: 'file', size: 219900, broken: false },
  { name: 'flow-map.json', type: 'file', size: 45436, broken: false },
  { name: 'flowmap-anim', type: 'file', size: 0, broken: true },
];

const FLOW_MAP_JSON = JSON.stringify({ flow: 'map-stub', nodes: [], meta: { project: 'demo' } });
const AGENTS_MD = '# AGENTS stub\n';

// ── boot：静态真实资源 + WS mock（同 flowmap-realtime 模式） ──────────────

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // Monaco web worker 的 AMD loader 会把模块 id（vs/language/...）拼到
    // baseUrl（.../vendor/monaco/vs/）上 → vs/vs/ 双前缀 404。生产环境 monaco
    // 对 worker 失败优雅降级（回退主线程），编辑器照常工作；harness 里重写
    // 掉双前缀让 worker 真正可用，保住零 pageerror 断言的强度。
    const monacoDoubled = p.match(/^\/vendor\/monaco\/vs\/vs\/(.+)$/);
    if (monacoDoubled) p = `/vendor/monaco/vs/${monacoDoubled[1]}`;
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      console.log(`[harness] 404: ${p}`);
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
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      } else if (msg.type === 'listDir') {
        const entries = msg.path === '.nebflow' ? NEBFLOW_ENTRIES : ROOT_ENTRIES;
        ws.send(JSON.stringify({
          type: 'dirListing', path: msg.path, resolvedPath: `/w/demo${msg.path ? '/' + msg.path : ''}`,
          entries,
        }));
      } else if (msg.type === 'readFile') {
        if (msg.path === '.nebflow/flow-map.json') {
          ws.send(JSON.stringify({
            type: 'fileContent', path: msg.path, absPath: `/w/demo/${msg.path}`,
            content: FLOW_MAP_JSON, itemType: 'code', fileName: 'flow-map.json', size: FLOW_MAP_JSON.length,
          }));
        } else if (msg.path === '.nebflow/Agent.md') {
          ws.send(JSON.stringify({
            type: 'fileContent', path: msg.path, absPath: `/w/demo/${msg.path}`,
            content: AGENTS_MD, itemType: 'code', fileName: 'Agent.md', size: AGENTS_MD.length,
          }));
        }
        // 其它路径（如悬空链接）不应被点开——不发帧，用例也不点它
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

// ── 主体用例：亮暗双主题各一遍 ───────────────────────────────────────────

for (const theme of ['light', 'dark']) {
  test.describe(`explorer .nebflow dir — ${theme}`, () => {
    test.use({ colorScheme: theme, viewport: { width: 1440, height: 900 } });

    test(`①②③④⑤ ${theme}: .nebflow 可见可展开、悬空链接不炸列表、flow-map.json 可预览`, async ({ page }) => {
      const pageErrors = [];
      page.on('pageerror', (e) => pageErrors.push(`${e.message}\n--- stack ---\n${e.stack || '(no stack)'}`));

      await bootApp(page);

      // ① 根 listing 含 .nebflow 目录行，树内无错误卡
      const nebflowRow = page.locator('#explorer-tree .explorer-item.explorer-folder[data-path=".nebflow"]');
      await expect(nebflowRow).toBeVisible({ timeout: 10000 });
      await expect(page.locator('#explorer-tree .explorer-error')).toHaveCount(0);

      // ② 展开 .nebflow → 子条目齐全（真实 loadDir → listDir → dirListing 管线）
      await nebflowRow.click();
      await expect(page.locator('#explorer-tree .explorer-file[data-path=".nebflow/flow-map.json"]')).toBeVisible({ timeout: 8000 });
      await expect(page.locator('#explorer-tree .explorer-file[data-path=".nebflow/Agent.md"]')).toBeVisible();
      await expect(page.locator('#explorer-tree .explorer-file[data-path=".nebflow/flow-map-archive.json"]')).toBeVisible();
      await expect(page.locator('#explorer-tree .explorer-folder[data-path=".nebflow/worktrees"]')).toBeVisible();
      await expect(page.locator('#explorer-tree .explorer-error')).toHaveCount(0);

      // ③ 悬空链接 flowmap-anim 呈现为文件行（非目录），列表未炸
      const dangling = page.locator('#explorer-tree .explorer-item.explorer-file[data-path=".nebflow/flowmap-anim"]');
      await expect(dangling).toBeVisible();
      await expect(page.locator('#explorer-tree .explorer-item.explorer-folder[data-path=".nebflow/flowmap-anim"]')).toHaveCount(0);

      // ④ 点 flow-map.json → readFile → fileContent → Canvas 预览标签 + 内容
      await page.locator('#explorer-tree .explorer-item.explorer-file[data-path=".nebflow/flow-map.json"]').click();
      const flowTab = page.locator('.canvas-tab', { hasText: 'flow-map.json' });
      await expect(flowTab).toBeVisible({ timeout: 8000 });
      await expect(page.locator('#canvas-panel')).toContainText('map-stub', { timeout: 8000 });

      // ⑤ 点 Agent.md（有效符号链接）→ 内容可读
      await page.locator('#explorer-tree .explorer-item.explorer-file[data-path=".nebflow/Agent.md"]').click();
      const agentTab = page.locator('.canvas-tab', { hasText: 'Agent.md' });
      await expect(agentTab).toBeVisible({ timeout: 8000 });
      await expect(page.locator('#canvas-panel')).toContainText('AGENTS stub', { timeout: 8000 });

      // 无前端异常
      expect(pageErrors, `page errors: ${pageErrors.join(' | ')}`).toEqual([]);

      // 主题截图（.nebflow 展开态全页）
      const shot = join(SHOT_DIR, theme === 'dark'
        ? '20260902_explorer-nebflow-dir-dark.png'
        : '20260902_explorer-nebflow-dir-light.png');
      await page.screenshot({ path: shot, fullPage: false });
    });
  });
}
