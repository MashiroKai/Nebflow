// canvas-file-refresh.spec.mjs — canvas 文件刷新批验收（作者令 2026-10-25 两件）
//
// ① 刷新按钮：canvas 文件页签（md/html/图片等全类型）右下角「重新读盘」钮——
//    点击走既有 readFile 面（pop.readFile → fileContent → workspace-open-item）
//    重渲染当前文件，不整页刷新；内容未变时点了无害（不重挂、不丢滚动位）；
//    脏标签（未保存修改）拒绝覆盖并给可见反馈；源码态整钮隐藏。
// ② 「查看源码」提速：归因 = Monaco AMD 链（loader.js → editor.main.js ~3.6MB
//    → css + basic-languages）被首击独扛（preloadMonaco 无人调用）；修复 = main.js
//    空闲预载。本 spec 断言预载完成后源码打开时延落在性能预算内（1500ms）。
//
// 运行：npx playwright test tests/canvas-file-refresh.spec.mjs
// （自足 mock：静态服务 web/ 真源 + WS mock + nf-ticket/nf-file mock）

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8229);
const BASE = `http://127.0.0.1:${PORT}`;

const MD_V1 = `# note\n\n${Array.from({ length: 80 }, (_, i) => `Paragraph ${i + 1}: mock body line for scroll.`).join('\n\n')}\n`;
const MD_V2 = MD_V1 + '\n## Freshly edited on disk\n\nNEW-CONTENT-MARKER v2\n';
// 1×1 PNG (transparent)
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

// ── 静态服务（serve 真实 web/ 前端源码树）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    if (!res.headersSent) { res.writeHead(404); res.end('not found'); }
  }
});
test.beforeAll(async () => { await new Promise((r) => server.listen(PORT, '127.0.0.1', r)); });
test.afterAll(async () => { await new Promise((r) => server.close(r)); });

/** Shared boot: static web tree + WS mock (answers pop.readFile from FILES) +
 *  api catch-all + ticket mock. Returns handles for per-test wiring. */
async function bootPage(browser, { files = {}, onFrame = null } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  const wsFrames = [];
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      wsFrames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'pop.readFile' && files[m.path] !== undefined) {
        const f = files[m.path];
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          ...(f.content !== undefined ? { content: f.content } : {}),
          itemType: f.itemType || 'code', fileName: m.path.split('/').pop(),
          size: f.size ?? (f.content ? f.content.length : 0),
        }));
      }
      if (onFrame) onFrame(m, ws);
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  return { ctx, page, errors, wsFrames };
}

async function openMdTab(page, absPath, content, title = 'note.md') {
  await page.evaluate(({ absPath, content, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content, absPath, pinned: true },
    }));
  }, { absPath, content, title });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"] .canvas-md-viewer`, { timeout: 15000 });
}

const paneSel = (absPath) => `.canvas-tab-pane[data-tab-id="file:${absPath}"]`;

// ════ ① 刷新按钮 ════
test.describe('refresh button', () => {
  test('A1 exists on file tabs, seats bottom-right without overlapping the corner cluster', async ({ browser }) => {
    const { ctx, page, errors } = await bootPage(browser, {
      files: { '/tmp/nfr/note.md': { content: MD_V1, itemType: 'markdown' } },
    });
    await openMdTab(page, '/tmp/nfr/note.md', MD_V1);
    const pane = page.locator(paneSel('/tmp/nfr/note.md'));
    // 量几何前等面板开合过渡（flex-basis 0 → target, 0.32s）落定，否则 pane 宽读 0
    await expect.poll(async () => pane.evaluate((p) => p.getBoundingClientRect().width), { timeout: 10000 }).toBeGreaterThan(320);
    // 存在 + 可见 + 圆钮族样式
    const btn = pane.locator('.canvas-refresh-toggle');
    await expect(btn).toHaveCount(1);
    await expect(btn).toBeVisible();
    const geo = await page.evaluate((sel) => {
      const pane = document.querySelector(sel);
      const paneRect = pane.getBoundingClientRect();
      const rect = pane.querySelector('.canvas-refresh-toggle').getBoundingClientRect();
      const others = [...pane.querySelectorAll('.canvas-source-toggle, .canvas-find-toggle, .canvas-graph-toggle, .canvas-zoom-bar')]
        .map((el) => el.getBoundingClientRect());
      const overlap = others.some((o) => !(rect.right <= o.left + 1 || rect.left >= o.right - 1 || rect.bottom <= o.top + 1 || rect.top >= o.bottom - 1));
      return {
        fromRight: paneRect.right - rect.right, fromBottom: paneRect.bottom - rect.bottom,
        overlap, paneW: paneRect.width, rightCss: pane.querySelector('.canvas-refresh-toggle').style.right,
      };
    }, paneSel('/tmp/nfr/note.md'));
    // 右下角：贴角簇同一行（bottom ≤ 24px），完整落在 pane 内，不与既有圆钮/缩放条重叠
    expect(geo.fromBottom, 'refresh button sits on the bottom row').toBeLessThanOrEqual(24);
    expect(geo.fromRight, 'button fully inside the pane horizontally').toBeLessThanOrEqual(geo.paneW - 56);
    expect(geo.overlap, 'no overlap with source/find/graph/zoom chrome').toBe(false);
    // code 页签同样有钮
    await page.evaluate(() => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:/tmp/nfr/app.scala', itemType: 'code', title: 'app.scala', content: 'object App {}\n', absPath: '/tmp/nfr/app.scala', pinned: true },
      }));
    });
    await page.waitForSelector(`${paneSel('/tmp/nfr/app.scala')} .canvas-refresh-toggle`, { timeout: 10000 });
    // 面板/URL 页签（无 absPath）没有钮（URL 页签 id 自带 scheme，不能走 paneSel 的 file: 前缀）
    await page.evaluate(() => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'url:https://example.com', itemType: 'url', title: 'example', url: 'https://example.com' },
      }));
    });
    await page.waitForSelector('.canvas-tab-pane[data-tab-id="url:https://example.com"]', { timeout: 10000, state: 'attached' });
    expect(await page.locator('.canvas-tab-pane[data-tab-id="url:https://example.com"] .canvas-refresh-toggle').count()).toBe(0);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });

  test('A2 md tab: click re-reads from disk and re-renders changed content', async ({ browser }) => {
    const files = { '/tmp/nfr/live.md': { content: MD_V1, itemType: 'markdown' } };
    const { ctx, page, errors } = await bootPage(browser, { files });
    await openMdTab(page, '/tmp/nfr/live.md', MD_V1);
    const pane = page.locator(paneSel('/tmp/nfr/live.md'));
    await expect(pane.locator('.canvas-md-viewer')).toContainText('Paragraph 80');
    // 磁盘上的文件被改动（mock：readFile 应答换成 v2）
    files['/tmp/nfr/live.md'] = { content: MD_V2, itemType: 'markdown' };
    await pane.locator('.canvas-refresh-toggle').click();
    await expect(pane.locator('.canvas-md-viewer'), 'changed content shows after refresh').toContainText('NEW-CONTENT-MARKER v2', { timeout: 10000 });
    await expect(pane.locator('.canvas-refresh-toggle'), 'busy state cleared after settle').not.toHaveClass(/busy/);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });

  test('A3 unchanged content: click is harmless — no remount, scroll kept, busy cleared', async ({ browser }) => {
    const { ctx, page, errors } = await bootPage(browser, {
      files: { '/tmp/nfr/same.md': { content: MD_V1, itemType: 'markdown' } },
    });
    await openMdTab(page, '/tmp/nfr/same.md', MD_V1);
    const sel = paneSel('/tmp/nfr/same.md');
    // 滚到中部（渲染管线完全落定后 setter 定位 + 读回基线——内容高度在字体/
    // 布局收敛前后会变，早设会被 clamp 出假漂移），并标记当前渲染节点
    await page.waitForTimeout(500);
    await page.evaluate((sel) => {
      document.querySelector(`${sel} .canvas-md-scroll`).scrollTop = 600;
    }, sel);
    await page.evaluate((sel) => {
      document.querySelector(`${sel} .canvas-md-viewer`).__nfrProbe = 'v1-node';
    }, sel);
    const before = await page.evaluate((sel) => document.querySelector(`${sel} .canvas-md-scroll`).scrollTop, sel);
    expect(before, 'scrolled the reader to mid-document').toBeGreaterThan(100);
    await page.locator(`${sel} .canvas-refresh-toggle`).click();
    await page.waitForTimeout(900);  // readFile round trip + settle
    const after = await page.evaluate((sel) => {
      const pane = document.querySelector(sel);
      return {
        scroll: pane.querySelector('.canvas-md-scroll').scrollTop,
        sameNode: pane.querySelector('.canvas-md-viewer').__nfrProbe === 'v1-node',
        busy: !!pane.querySelector('.canvas-refresh-toggle.busy'),
      };
    }, sel);
    expect(after.sameNode, 'unchanged refresh must NOT remount the viewer').toBe(true);
    expect(after.scroll, 'reading position preserved').toBe(before);
    expect(after.busy, 'busy cleared').toBe(false);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });

  test('A4 image tab: refresh invalidates the blob ledger and re-fetches bytes', async ({ browser }) => {
    test.setTimeout(90000);
    let nfFetches = 0;
    const { ctx, page, errors } = await bootPage(browser, {
      files: { '/tmp/nfr/pic.png': { itemType: 'image', size: 95 } },
    });
    console.log('[A4] booted');
    await page.route(/\/api\/nf-file\?/, async (route) => {
      if (await ticketGuard(route)) return;
      nfFetches++;
      console.log('[A4] nf-file fetch #', nfFetches);
      return route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from(PNG_B64, 'base64') });
    });
    await page.evaluate(() => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:/tmp/nfr/pic.png', itemType: 'image', title: 'pic.png', content: '', absPath: '/tmp/nfr/pic.png', pinned: true },
      }));
    });
    await page.waitForSelector(`${paneSel('/tmp/nfr/pic.png')} .canvas-image-viewer img`, { timeout: 20000 });
    await page.waitForTimeout(500);  // let the ledger put land
    const first = nfFetches;
    console.log('[A4] first render fetches =', first);
    expect(first, 'initial render fetched bytes via the ticket chain').toBeGreaterThan(0);
    await page.locator(`${paneSel('/tmp/nfr/pic.png')} .canvas-refresh-toggle`).click({ timeout: 15000 });
    console.log('[A4] refresh clicked');
    await expect.poll(() => nfFetches, { timeout: 20000 }, 'refresh re-fetches (ledger invalidated, cache not served)').toBeGreaterThan(first);
    console.log('[A4] re-fetch observed, count =', nfFetches);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
    console.log('[A4] done');
  });

  test('A5 dirty tab: refresh refuses to clobber and says so; no readFile is sent', async ({ browser }) => {
    const { ctx, page, errors, wsFrames } = await bootPage(browser, {
      files: { '/tmp/nfr/dirty.md': { content: MD_V1, itemType: 'markdown' } },
    });
    await openMdTab(page, '/tmp/nfr/dirty.md', MD_V1, 'dirty.md');
    const sel = paneSel('/tmp/nfr/dirty.md');
    // 进源码态 → 经 Monaco model 改内容（触发 onDidChangeContent → 脏）→ 回渲染态
    await page.locator(`${sel} .canvas-source-toggle`).click();
    await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 30000 });
    await page.waitForTimeout(300);
    await page.evaluate((sel) => {
      const h = document.querySelector(sel)._editorHandle;
      h.model.setValue(h.model.getValue() + '\nDIRTY-EDIT');
    }, sel);
    // 脏点挂在页签按钮的 label 上（tabEl 与 paneEl 是兄弟节点，不能按 pane 作用域查）
    await expect(page.locator('.canvas-tab', { hasText: 'dirty.md' }).locator('.canvas-tab-label.dirty')).toHaveCount(1);
    await page.locator(`${sel} .canvas-source-toggle`).click();
    await page.waitForSelector(`${sel} .canvas-md-viewer`, { timeout: 15000 });
    // 捕获 toast + 记录 pop.readFile 计数
    await page.evaluate(() => {
      /** @type {any} */ (window).__nfrToasts = [];
      const orig = /** @type {any} */ (window).__showToast;
      /** @type {any} */ (window).__showToast = (msg, kind) => { /** @type {any} */ (window).__nfrToasts.push(String(msg)); if (orig) orig(msg, kind); };
    });
    const sentBefore = wsFrames.filter((f) => f.type === 'pop.readFile').length;
    await page.locator(`${sel} .canvas-refresh-toggle`).click();
    await page.waitForTimeout(600);
    const toasts = await page.evaluate(() => /** @type {any} */ (window).__nfrToasts);
    expect(toasts.some((s) => s.includes('未保存')), 'visible dirty boundary toast').toBe(true);
    expect(wsFrames.filter((f) => f.type === 'pop.readFile').length, 'no readFile dispatched for a dirty tab').toBe(sentBefore);
    // 已知噪声过滤：Monaco 在 dispose 带未完成 tokenize 任务的源码编辑器时，
    // 其内部 cancellation token 会以未处理 rejection 冒泡（栈全在 editor.main.js
    // 的 cancel/dispose 链）——源码态编辑器 dispose 是 addSourceToggle 既有路径
    // （本批未触碰；任何「编辑后切回渲染」都会触发），非本批新增错误。
    const realErrors = errors.filter((m) => !/^Canceled\b/.test(m));
    expect(realErrors, 'zero new pageerrors').toEqual([]);
    await ctx.close();
  });

  test('A6 refresh button hides in source mode and returns in rendered mode', async ({ browser }) => {
    const { ctx, page, errors } = await bootPage(browser, {
      files: { '/tmp/nfr/src.md': { content: MD_V1, itemType: 'markdown' } },
    });
    await openMdTab(page, '/tmp/nfr/src.md', MD_V1);
    const sel = paneSel('/tmp/nfr/src.md');
    await page.locator(`${sel} .canvas-source-toggle`).click();
    await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 30000 });
    const hidden = await page.evaluate((sel) => {
      const btn = document.querySelector(`${sel} .canvas-refresh-toggle`);
      return btn ? getComputedStyle(btn).display === 'none' : true;
    }, sel);
    expect(hidden, 'refresh button hidden while the source editor is mounted').toBe(true);
    await page.locator(`${sel} .canvas-source-toggle`).click();
    await page.waitForSelector(`${sel} .canvas-md-viewer`, { timeout: 15000 });
    await expect(page.locator(`${sel} .canvas-refresh-toggle`)).toBeVisible();
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });
});

// ════ ② 「查看源码」提速 ════
test.describe('view-source latency', () => {
  test('A7 preloaded source open mounts within the performance budget (1500ms)', async ({ browser }) => {
    test.setTimeout(120000);
    const { ctx, page, errors } = await bootPage(browser, {
      files: { '/tmp/nfr/latency.md': { content: MD_V1, itemType: 'markdown' } },
    });
    await openMdTab(page, '/tmp/nfr/latency.md', MD_V1);
    // 等空闲预载落位（main.js idle preload → window.monaco）
    await page.waitForFunction(() => !!window.monaco, null, { timeout: 45000 });
    await page.waitForTimeout(150);
    const sel = paneSel('/tmp/nfr/latency.md');
    const t0 = Date.now();
    await page.locator(`${sel} .canvas-source-toggle`).click();
    await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 1500, state: 'attached' });
    const ms = Date.now() - t0;
    console.log(`[canvas-file-refresh] preloaded source-open latency: ${ms}ms (budget 1500ms)`);
    expect(ms, 'source open latency within budget').toBeLessThan(1500);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });
});
