// explorer-listdir-timeout.spec.mjs — listDir 有界等待验收（project-slow-folders 批
// 2026-10-28）。
//
// 现场（作者报障 2026-10-28 15:5x，DOSTEL 项目卡文件区「加载中…」久久不出）：
// dirListing 帧经拥塞链路（portal 中继 uplink inbox-full / 出站队列溢出拆线 /
// pendingSends 溢出丢最旧）丢失或无限迟到后，旧实现 pendingLoads / loadingDirs
// 永不清理 ⇒ 加载行永挂，且 fsChanged / focus-refresh / 慢对账三条自愈腿全被
// `!loadingDirs.has()` 闸挡住——没有任何路径能把树救回来。
//
// 修复语义（本 spec 钉死）：
//   ① 应答丢失一次 ⇒ 超时**静默重发**（不闪错误行）；重发应答到达 ⇒ 树正常渲染；
//   ② 持续丢帧 ⇒ 既有错误行 + [重试] 钮（远端腿 B3 同款 UI），加载行摘除
//      （「加载中…」有终态，不再永挂）；
//   ③ 终态后 loadingDirs / pendingLoads 已释放 ⇒ focus 自愈腿能重拉根目录
//      （死锁回归钉：旧实现此腿被 loadingDirs 闸永久挡住，树永远救不回来）；
//   ④ 正常应答路径零行为变化（explorer-nebflow-dir spec 同舱回归）。
//
// 运行：npx playwright test tests/explorer-listdir-timeout.spec.mjs

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

const SID = 'exp-timeout-session';
// e2e seam（生产恒 10000ms）：350ms 让「丢帧 → 静默重发 → 终态」三段在秒级跑完。
const LOAD_TIMEOUT_MS = 350;

const ROOT_ENTRIES = [
  { name: 'src', type: 'dir', size: 0, broken: false },
  { name: 'AGENTS.md', type: 'file', size: 8252, broken: false },
  { name: 'package.json', type: 'file', size: 512, broken: false },
];

// 丢帧控制（Node 侧可变对象，由 WS mock 闭包捕获）：当前累计丢帧数 drops 与
// 上限 maxDrops——`drops < maxDrops` 的根 listDir 不应答（模拟中继丢帧），
// 其余照答。用例体内随时改 maxDrops 模拟「网络恢复」，与启动期渲染竞态无关
// （启动期可能有多条 renderTree 腿，逐条计数不可靠，闸控计数才确定）。
function makeDropCtl(maxDrops) {
  return { drops: 0, maxDrops };
}

async function bootApp(page, dropCtl) {
  await page.addInitScript((ms) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // e2e seam：listDir 有界等待时限（见 explorer.js LOAD_TIMEOUT_MS 注记）。
    window.__nfExplorerLoadTimeoutMs = ms;
  }, LOAD_TIMEOUT_MS);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
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
        if (!msg.path && dropCtl.drops++ < dropCtl.maxDrops) return; // 丢帧腿：不回 dirListing
        ws.send(JSON.stringify({
          type: 'dirListing', path: msg.path, resolvedPath: `/w/demo${msg.path ? '/' + msg.path : ''}`,
          entries: ROOT_ENTRIES,
        }));
      }
      // watchSubscribe / readFile 等：本 spec 不涉，静默忽略。
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
}

test.describe('explorer listDir 有界等待', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('① 丢帧一次 ⇒ 静默重发自愈，不闪错误行', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    const dropCtl = makeDropCtl(1); // 丢一次 ⇒ 超时静默重发，此后照答
    await bootApp(page, dropCtl);

    // 重发应答到达 ⇒ 树渲染成功。
    const srcRow = page.locator('#explorer-tree .explorer-item.explorer-folder[data-path="src"]');
    await expect(srcRow).toBeVisible({ timeout: 8000 });
    // 自愈路径零错误行残留。
    await expect(page.locator('#explorer-tree .explorer-error')).toHaveCount(0);
    // 加载行已摘（不残留）。
    await expect(page.locator('#explorer-tree .explorer-loading')).toHaveCount(0);
    expect(pageErrors, `pageerror: ${pageErrors.join(' | ')}`).toEqual([]);
  });

  test('② 持续丢帧 ⇒ 错误行 + [重试] 终态（不再永挂「加载中…」）', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    const dropCtl = makeDropCtl(Number.MAX_SAFE_INTEGER); // 全丢 ⇒ 每轮尝试都无应答
    await bootApp(page, dropCtl);

    // 两轮超时（2 × 350ms + 重发间隔）后落错误终态。
    const errRow = page.locator('#explorer-tree .explorer-error');
    await expect(errRow).toBeVisible({ timeout: 8000 });
    const retry = errRow.locator('button.retry');
    await expect(retry).toBeVisible();
    await expect(retry).toHaveText('重试'); // zh-CN locale 键 explorer.retry
    // 加载行已摘：超时文案落位后不再有「加载中...」行。
    await expect(page.locator('#explorer-tree .explorer-loading')).toHaveCount(0);
    expect(pageErrors, `pageerror: ${pageErrors.join(' | ')}`).toEqual([]);
  });

  test('③ 终态后闸已释放 ⇒ focus 自愈腿能重拉根目录（死锁回归钉）', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    const dropCtl = makeDropCtl(Number.MAX_SAFE_INTEGER); // 先全丢：错误终态必达且稳定
    await bootApp(page, dropCtl);

    // 先落到错误终态（② 同款）。全丢期间任何启动期补拉腿也只会再次终态（幂等）。
    await expect(page.locator('#explorer-tree .explorer-error')).toBeVisible({ timeout: 8000 });

    // 「网络恢复」：此后所有 listDir 照答。
    dropCtl.maxDrops = 0;
    // 不点重试 —— 走 focus 自愈腿（refreshExpandedDirs）：旧实现此处 loadingDirs
    // 仍含 '' ⇒ 该腿被闸永久挡住（死锁，树救不回来）；修复后终态即释放，重拉必达。
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    const srcRow = page.locator('#explorer-tree .explorer-item.explorer-folder[data-path="src"]');
    await expect(srcRow).toBeVisible({ timeout: 8000 });
    await expect(page.locator('#explorer-tree .explorer-error')).toHaveCount(0);
    expect(pageErrors, `pageerror: ${pageErrors.join(' | ')}`).toEqual([]);
  });
});
