// canvas-auto-refresh.spec.mjs — canvas 自动检查变更刷新批验收（作者令 2026-11-17
// 逐字：「我们现在 canvas 有个刷新按钮 去掉 改为带迟滞的自动检查变更刷新 时间窗60s」）
//
// 六个验收项（红→绿两态证据见 /private/tmp/car-evid/）：
//   a 无变更持续 60s+：不刷新（DOM 节点身份/滚动位/文本选型原样，零提示零 spinner）；
//   b 外部改文件 → 静默窗实测 60s 到点才刷（时间戳差证明不是立刻）；
//   c 连续多次改写：一路推迟，只在最后一次变更静默满 60s 时刷一次（计数=1）；
//   d 脏态：不自动刷 + 「有更新」提示；点提示立即刷新（refreshDirty 语义：脏点击
//     = toast 拒绝，撤销编辑后点击 = 立即重读）；
//   e 按钮消失：DOM 断言 + 截图；
//   f 刷新后滚动位置保持（md 查看器改前 prevScrollTop 保位）。
//
// 60s 是真实等待（常数不缩水）。精确边界（t=59 不刷 / t=60 刷、两常数 = 60000）由
// U1 经 window.__canvasAutoRefreshDebug（只读测试面）做单元级断言——报告口径：
// 「真实等待 + 单元级边界断言」两种做法并用。
//
// 运行（/tmp worktree 无 node_modules，借主仓）：
//   node "../../../Users/kaiyu/Claude code/Stand/node_modules/@playwright/test/cli.js" \
//     test tests/canvas-auto-refresh.spec.mjs
// 自足 mock：静态服务 web/ 真源 + WS mock + nf-ticket mock（照抄 canvas-file-refresh
// .spec.mjs 跑法；外部改盘 = 换 mock 应答 + 推 nf-cache-invalidate 帧——与引擎写路径
// 广播同一条线上真径）。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;

const MD_V1 = `# note\n\n${Array.from({ length: 80 }, (_, i) => `Paragraph ${i + 1}: mock body line for scroll.`).join('\n\n')}\n`;
const MD_V2 = MD_V1 + '\n## Freshly edited on disk\n\nNEW-CONTENT-MARKER v2\n';

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

/** Shared boot（照抄 canvas-file-refresh 模板；差异：readFile 应答带 mtimeMs、
 *  帧账本带时间戳、暴露 pushFrame 供测试侧主动推引擎帧）。 */
async function bootPage(browser, { files = {}, colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, colorScheme });
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
  const reads = [];   // { t, path } — pop.readFile 客户端→服务端帧（= 刷新动作本体）
  let pushWs = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    pushWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      // 心跳必须应答：ws.js 是应用层 ping/pong（3 miss 自我逐出重连）。mock 不回
      // pong ⇒ 重连风暴 ⇒ explorer onReconnect 每轮都派发 explorer-focus-refresh
      // ⇒ canvas 迟滞引擎每轮重布防 60s 定时器 ⇒ 永不到点（生产 ws 真回 pong）。
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'pop.readFile' && files[m.path] !== undefined) {
        reads.push({ t: Date.now(), path: m.path });
        const f = files[m.path];
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          ...(f.content !== undefined ? { content: f.content } : {}),
          itemType: f.itemType || 'code', fileName: m.path.split('/').pop(),
          size: f.size ?? (f.content ? f.content.length : 0),
          mtimeMs: f.mtimeMs ?? 1000,
        }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  /** 测试侧推一条引擎→前端帧（如 nf-cache-invalidate = 引擎写路径广播真形）。 */
  const pushFrame = (m) => pushWs && pushWs.send(JSON.stringify(m));
  return { ctx, page, errors, reads, pushFrame };
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

/** 把 mock 上的文件改成新内容并广播引擎写帧（nf-cache-invalidate → main.js →
 *  nf-file-external-change → canvas 迟滞引擎；生产线上同一条真径）。 */
function externalWrite(files, pushFrame, absPath, content, mtimeMs) {
  files[absPath] = { ...files[absPath], content, mtimeMs };
  pushFrame({ type: 'nf-cache-invalidate', path: absPath });
}

// ════ U1 单元级边界（假等待零秒：常数与判定函数）════
test('U1 hysteresis constants = 60s and the deadline predicate flips exactly at t=60s', async ({ browser }) => {
  const { ctx, page } = await bootPage(browser, {});
  const u = await page.evaluate(() => {
    const d = /** @type {any} */ (window).__canvasAutoRefreshDebug;
    if (!d) return null;
    const changeAt = 1_000_000;
    return {
      QUIET_MS: d.QUIET_MS, MIN_INTERVAL_MS: d.MIN_INTERVAL_MS,
      at59: d.deadline({ _autoChangeAt: changeAt, _lastAutoRefreshAt: 0 }) - (changeAt + 59_000),
      at60: d.deadline({ _autoChangeAt: changeAt, _lastAutoRefreshAt: 0 }) - (changeAt + 60_000),
      // 最小间隔：末次变更 T=2_000_000，上次自动刷新 T=2_030_000（刷新晚于变更信号——迟到检测兜底）
      // ⇒ 静默窗到点 2_060_000 被最小间隔顶到 2_030_000+60s = 2_090_000。
      minGate: d.deadline({ _autoChangeAt: 2_000_000, _lastAutoRefreshAt: 2_030_000 }),
    };
  });
  expect(u, 'debug handle present (engine installed)').toBeTruthy();
  expect(u.QUIET_MS, 'quiet window constant = 60s').toBe(60_000);
  expect(u.MIN_INTERVAL_MS, 'min interval constant = 60s').toBe(60_000);
  expect(u.at59, 't=59s: still 1s short — not due').toBe(1_000);
  expect(u.at60, 't=60s: exactly due').toBe(0);
  expect(u.minGate, 'min interval pushes the deadline out').toBe(2_030_000 + 60_000);
  await ctx.close();
});

// ════ e 按钮消失（先跑便宜的）════
test('E5 refresh button is gone from the DOM and the corner area is clean', async ({ browser }) => {
  const { ctx, page, errors } = await bootPage(browser, {
    files: { '/tmp/car/gone.md': { content: MD_V1, itemType: 'markdown' } },
  });
  await openMdTab(page, '/tmp/car/gone.md', MD_V1);
  const pane = page.locator(paneSel('/tmp/car/gone.md'));
  await expect.poll(() => pane.evaluate((p) => p.getBoundingClientRect().width), { timeout: 10000 }).toBeGreaterThan(320);
  // DOM 断言：壳层单例位、pane 内、整文档——三处全零
  expect(await page.locator('#canvas-content > .canvas-refresh-toggle').count(), 'shell singleton slot empty').toBe(0);
  expect(await page.locator('.canvas-refresh-toggle').count(), 'no refresh toggle anywhere').toBe(0);
  // 角簇其余成员健在（去钮不殃及邻居）
  expect(await page.locator(`${paneSel('/tmp/car/gone.md')} .canvas-source-toggle`).count(), 'source toggle unaffected').toBe(1);
  // 截图证据（右下角簇区域）
  await page.screenshot({ path: '/private/tmp/car-evid/e5-button-gone.png', clip: await page.evaluate(() => {
    const r = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, r.right - 420), y: Math.max(0, r.bottom - 200), width: Math.min(420, r.width), height: Math.min(200, r.height) };
  }) });
  expect(errors, 'zero pageerrors').toEqual([]);
  await ctx.close();
});

// ════ a 无变更 = 零视觉噪音 ════
test('E1 no change for 60s+: no refresh, no hint, node identity/scroll/selection intact', async ({ browser }) => {
  test.setTimeout(120_000);
  const { ctx, page, errors, reads } = await bootPage(browser, {
    files: { '/tmp/car/quiet.md': { content: MD_V1, itemType: 'markdown' } },
  });
  await openMdTab(page, '/tmp/car/quiet.md', MD_V1);
  const sel = paneSel('/tmp/car/quiet.md');
  await page.waitForTimeout(500);  // 渲染收敛后再定位
  await page.evaluate((sel) => {
    const scroller = document.querySelector(`${sel} .canvas-md-scroll`);
    scroller.scrollTop = 600;
    // 文本选择：正文第 40 段上拉一个 range
    const ps = document.querySelectorAll(`${sel} .canvas-md-viewer p`);
    const p = ps[40];
    const r = document.createRange();
    r.selectNodeContents(p);
    const s = window.getSelection();
    s.removeAllRanges();
    s.addRange(r);
    document.querySelector(`${sel} .canvas-md-viewer`).__nfrProbe = 'v1-node';
  }, sel);
  const before = await page.evaluate((sel) => ({
    scroll: document.querySelector(`${sel} .canvas-md-scroll`).scrollTop,
    node: document.querySelector(`${sel} .canvas-md-viewer`).__nfrProbe,
    selText: String(window.getSelection()),
  }), sel);
  expect(before.scroll, 'scrolled mid-document').toBeGreaterThan(100);
  expect(before.selText.length, 'a text selection is live').toBeGreaterThan(10);
  const readsBefore = reads.length;
  // 真实静默 65s（无任何变更信号 ⇒ 引擎零布防；聚焦腿即便布防，未变内容也不重挂）
  await page.waitForTimeout(65_000);
  const after = await page.evaluate((sel) => ({
    scroll: document.querySelector(`${sel} .canvas-md-scroll`).scrollTop,
    node: document.querySelector(`${sel} .canvas-md-viewer`).__nfrProbe,
    selText: String(window.getSelection()),
  }), sel);
  expect(after.node, 'viewer NOT remounted (node identity kept)').toBe('v1-node');
  expect(after.scroll, 'scroll position kept').toBe(before.scroll);
  expect(after.selText, 'text selection kept').toBe(before.selText);
  expect(await page.locator('.canvas-update-hint').count(), 'no update hint').toBe(0);
  expect(await page.locator('.nf-file-conflict').count(), 'no conflict bar').toBe(0);
  const reRendered = await page.evaluate((sel) => document.querySelector(`${sel} .canvas-md-viewer`).__nfrProbe !== 'v1-node', sel);
  expect(reRendered, 'no DOM rebuild happened').toBe(false);
  await page.screenshot({ path: '/private/tmp/car-evid/e1-no-change-quiet.png' });
  expect(errors, 'zero pageerrors').toEqual([]);
  await ctx.close();
});

// ════ b 外部改文件 → 实测 60s 静默窗到点刷 ════
test('E2 external change auto-refreshes after a measured 60s quiet window (not immediately)', async ({ browser }) => {
  test.setTimeout(150_000);
  const files = { '/tmp/car/live.md': { content: MD_V1, itemType: 'markdown', mtimeMs: 1000 } };
  const { ctx, page, errors, reads, pushFrame } = await bootPage(browser, { files });
  await openMdTab(page, '/tmp/car/live.md', MD_V1);
  const sel = paneSel('/tmp/car/live.md');
  await expect(page.locator(`${sel} .canvas-md-viewer`)).toContainText('Paragraph 80');
  await page.waitForTimeout(800);  // boot 稳定（避开开面板期的杂散聚焦腿干扰记数窗）
  const readsBaseline = reads.length;
  const T0 = Date.now();
  externalWrite(files, pushFrame, '/tmp/car/live.md', MD_V2, 2000);  // 外部改盘
  // 前 50s：不许有任何刷新动作（readFile 计数为零——立刻刷 = 迟滞未装）
  await page.waitForTimeout(50_000);
  expect(reads.length, 'no re-read within the first 50s (hysteresis armed, not firing)').toBe(readsBaseline);
  // 60s 静默窗到点：等刷新落地（容差至 75s）
  await expect.poll(() => reads.length, { timeout: 30_000 }, 'auto refresh fires once the quiet window is satisfied').toBe(readsBaseline + 1);
  const delta = reads[reads.length - 1].t - T0;
  // 时间戳差证明：≥ 60s（到点刷）且 ≤ 75s（没丢事件没瞎拖）
  expect(delta, `refresh landed ${delta}ms after the change — must be ≥ 60s`).toBeGreaterThanOrEqual(60_000);
  expect(delta, 'refresh landed within a sane margin of the deadline').toBeLessThanOrEqual(75_000);
  // 内容真换了（自动刷新生效）
  await expect(page.locator(`${sel} .canvas-md-viewer`), 'changed content rendered by the auto refresh').toContainText('NEW-CONTENT-MARKER v2', { timeout: 15_000 });
  console.log(`[E2] change@${T0} → refresh@${reads[reads.length - 1].t} (Δ=${delta}ms)`);
  expect(errors, 'zero pageerrors').toEqual([]);
  await ctx.close();
});

// ════ c 连续多次改写：一路推迟，只刷一次 ════
test('E3 continuous writes keep deferring — exactly one refresh after the LAST change settles', async ({ browser }) => {
  test.setTimeout(240_000);
  const files = { '/tmp/car/burst.md': { content: MD_V1, itemType: 'markdown', mtimeMs: 1000 } };
  const { ctx, page, errors, reads, pushFrame } = await bootPage(browser, { files });
  await openMdTab(page, '/tmp/car/burst.md', MD_V1);
  const sel = paneSel('/tmp/car/burst.md');
  await page.waitForTimeout(800);
  const readsBaseline = reads.length;
  // 连续 4 次外部改写（每 10s 一次——节奏上限见下方「窗口数学」）——每次信号都
  // 把静默窗重置
  const T0 = Date.now();
  let lastWriteAt;
  for (let i = 1; i <= 4; i++) {
    if (i > 1) await page.waitForTimeout(10_000);
    lastWriteAt = Date.now();
    externalWrite(files, pushFrame, '/tmp/car/burst.md',
      MD_V1 + `\n\nBURST-EDIT-${i}\n`, 1000 + i);
  }
  // 末次写入约 T0+30s，到点约 T0+90s，避开初始化后 120s 对账。
  // 第 4 次改写后 50s（≈ T0+80s，若按首次变更（T0+60s 到点）算早已超窗）仍不许
  // 刷：静默窗从**最后一次**变更起算
  await page.waitForTimeout(50_000);
  expect(reads.length, 'still deferred — quiet window counts from the LAST change').toBe(readsBaseline);
  // 最后一次变更 + 60s 到点：刷，且只刷一次
  await expect.poll(() => reads.length, { timeout: 25_000 }, 'fires once after the last change settles 60s').toBe(readsBaseline + 1);
  const delta = reads[reads.length - 1].t - lastWriteAt;
  expect(delta, `Δ from LAST write = ${delta}ms ≥ 60s`).toBeGreaterThanOrEqual(60_000);
  expect(delta, 'within sane margin').toBeLessThanOrEqual(75_000);
  // 内容是最后一次改写的结果（不是中途快照）
  await expect(page.locator(`${sel} .canvas-md-viewer`), 'final content = last write (BURST-EDIT-4), not a mid-burst snapshot').toContainText('BURST-EDIT-4', { timeout: 15_000 });
  await page.waitForTimeout(3_000);
  expect(reads.length, 'exactly ONE auto refresh for the whole burst').toBe(readsBaseline + 1);
  console.log(`[E3] 4 writes over 30s → 1 refresh, Δ(last write → refresh)=${delta}ms`);
  expect(errors, 'zero pageerrors').toEqual([]);
  await ctx.close();
});

// ════ d 脏态：不刷 + 「有更新」提示 + 点击立即刷新（refreshDirty 语义）════
test('E4 dirty tab: no auto refresh, hint appears, click refreshes per refreshDirty semantics', async ({ browser }) => {
  test.setTimeout(180_000);
  const files = { '/tmp/car/dirty.md': { content: MD_V1, itemType: 'markdown', mtimeMs: 1000 } };
  const { ctx, page, errors, reads, pushFrame } = await bootPage(browser, { files });
  await openMdTab(page, '/tmp/car/dirty.md', MD_V1, 'dirty.md');
  const sel = paneSel('/tmp/car/dirty.md');
  await page.waitForTimeout(800);
  const readsBaseline = reads.length;
  // 场景：信号时页签还是干净的 → 布防；窗口内用户开始编辑（源码态改 Monaco → 回
  // 渲染态，脏点在）→ 到点时脏 ⇒ 不刷、挂「有更新」提示
  externalWrite(files, pushFrame, '/tmp/car/dirty.md', MD_V2, 2000);
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 30_000 });
  await page.waitForTimeout(300);
  await page.evaluate((sel) => {
    const h = document.querySelector(sel)._editorHandle;
    h.model.setValue(h.model.getValue() + '\nDIRTY-EDIT');
  }, sel);
  await expect(page.locator('.canvas-tab', { hasText: 'dirty.md' }).locator('.canvas-tab-label.dirty')).toHaveCount(1);
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .canvas-md-viewer`, { timeout: 15_000 });
  // 静默窗到点（真实等待）：不刷 + 提示在场
  await page.waitForTimeout(62_000);
  expect(reads.length, 'dirty tab: NO auto refresh dispatched').toBe(readsBaseline);
  await expect(page.locator(`${sel} .canvas-update-hint`), 'lightweight update hint visible').toHaveCount(1);
  await expect(page.locator(`${sel} .canvas-update-hint`)).toContainText('磁盘');
  await page.screenshot({ path: '/private/tmp/car-evid/e4-dirty-hint.png', clip: await page.evaluate(() => {
    const r = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: r.x, y: r.y, width: Math.min(560, r.width), height: 220 };
  }) });
  // 脏点击 = refreshDirty toast（不覆盖本地编辑）
  await page.evaluate(() => {
    /** @type {any} */ (window).__nfrToasts = [];
    const orig = /** @type {any} */ (window).__showToast;
    /** @type {any} */ (window).__showToast = (msg, kind) => { /** @type {any} */ (window).__nfrToasts.push(String(msg)); if (orig) orig(msg, kind); };
  });
  await page.locator(`${sel} .canvas-update-hint-refresh`).click();
  await page.waitForTimeout(600);
  const toasts = await page.evaluate(() => /** @type {any} */ (window).__nfrToasts);
  expect(toasts.some((s) => s.includes('未保存')), 'dirty click → refreshDirty toast (no clobber)').toBe(true);
  expect(reads.length, 'dirty click dispatched no re-read').toBe(readsBaseline);
  // 撤销编辑（setValue 回原文 ⇒ 脏点清）→ 点「立即刷新」→ 真刷
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 30_000 });
  await page.evaluate((sel) => {
    const h = document.querySelector(sel)._editorHandle;
    h.model.setValue(h.model.getValue().replace('\nDIRTY-EDIT', ''));
  }, sel);
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .canvas-md-viewer`, { timeout: 15_000 });
  await expect(page.locator('.canvas-tab', { hasText: 'dirty.md' }).locator('.canvas-tab-label.dirty')).toHaveCount(0);
  await page.locator(`${sel} .canvas-update-hint-refresh`).click();
  await expect.poll(() => reads.length, { timeout: 15_000 }, 'clean click → immediate re-read').toBe(readsBaseline + 1);
  await expect(page.locator(`${sel} .canvas-md-viewer`), 'refreshed to disk content').toContainText('NEW-CONTENT-MARKER v2', { timeout: 15_000 });
  await expect(page.locator(`${sel} .canvas-update-hint`), 'hint cleared once a refresh landed').toHaveCount(0);
  // 已知噪声过滤（同 canvas-file-refresh A5：Monaco dispose 的 Canceled rejection）
  const realErrors = errors.filter((m) => !/^Canceled\b/.test(m));
  expect(realErrors, 'zero new pageerrors').toEqual([]);
  await ctx.close();
});

// ════ d′ 脏态（信号时已脏）：冲突条腿不打断 ════
test("E4b already-dirty at signal: conflict bar (B7-2) shows, no auto refresh, reload click refreshes", async ({ browser }) => {
  test.setTimeout(120_000);
  const files = { '/tmp/car/conflict.md': { content: MD_V1, itemType: 'markdown', mtimeMs: 1000 } };
  const { ctx, page, errors, reads, pushFrame } = await bootPage(browser, { files });
  await openMdTab(page, '/tmp/car/conflict.md', MD_V1, 'conflict.md');
  const sel = paneSel('/tmp/car/conflict.md');
  // 先弄脏
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .monaco-editor`, { timeout: 30_000 });
  await page.waitForTimeout(300);
  await page.evaluate((sel) => {
    const h = document.querySelector(sel)._editorHandle;
    h.model.setValue(h.model.getValue() + '\nDIRTY-EDIT');
  }, sel);
  await page.locator(`${sel} .canvas-source-toggle`).click();
  await page.waitForSelector(`${sel} .canvas-md-viewer`, { timeout: 15_000 });
  await page.waitForTimeout(800);
  const readsBaseline = reads.length;
  externalWrite(files, pushFrame, '/tmp/car/conflict.md', MD_V2, 2000);
  // 信号时刻已脏 ⇒ B7-2 冲突条立即（既有语义），迟滞到点也不自动刷
  await expect(page.locator(`${sel} .nf-file-conflict`), 'conflict bar shown at signal time').toHaveCount(1);
  await page.waitForTimeout(62_000);
  expect(reads.length, 'no auto refresh on a dirty tab (deadline passed)').toBe(readsBaseline);
  // 「重载磁盘版本」一键 = 用户自主时机的立即刷新
  await page.locator(`${sel} .nf-file-conflict-reload`).click();
  await expect.poll(() => reads.length, { timeout: 15_000 }, 'reload click → immediate re-read').toBe(readsBaseline + 1);
  await expect(page.locator(`${sel} .canvas-md-viewer`), 'reloaded to disk content').toContainText('NEW-CONTENT-MARKER v2', { timeout: 15_000 });
  const realErrors = errors.filter((m) => !/^Canceled\b/.test(m));
  expect(realErrors, 'zero new pageerrors').toEqual([]);
  await ctx.close();
});

// ════ f 刷新后滚动位置保持 ════
test('E6 scroll position survives the auto refresh (changed content, md viewer)', async ({ browser }) => {
  test.setTimeout(150_000);
  const files = { '/tmp/car/scroll.md': { content: MD_V1, itemType: 'markdown', mtimeMs: 1000 } };
  const { ctx, page, errors, reads, pushFrame } = await bootPage(browser, { files });
  await openMdTab(page, '/tmp/car/scroll.md', MD_V1);
  const sel = paneSel('/tmp/car/scroll.md');
  await page.waitForTimeout(500);
  await page.evaluate((sel) => { document.querySelector(`${sel} .canvas-md-scroll`).scrollTop = 600; }, sel);
  const before = await page.evaluate((sel) => document.querySelector(`${sel} .canvas-md-scroll`).scrollTop, sel);
  expect(before, 'scrolled mid-document').toBeGreaterThan(100);
  await page.waitForTimeout(300);
  const readsBaseline = reads.length;
  externalWrite(files, pushFrame, '/tmp/car/scroll.md', MD_V2, 2000);  // 追加段（不删旧段：锚位仍有效）
  // 静默窗未满不刷，到点（60s）才刷——先熬过 50s 记数窗，再给 30s 容差等落地
  await page.waitForTimeout(50_000);
  expect(reads.length, 'no re-read before the quiet window is satisfied').toBe(readsBaseline);
  await expect.poll(() => reads.length, { timeout: 30_000 }, 'auto refresh fired after the quiet window').toBe(readsBaseline + 1);
  await expect(page.locator(`${sel} .canvas-md-viewer`), 'new content rendered').toContainText('NEW-CONTENT-MARKER v2', { timeout: 15_000 });
  await page.waitForTimeout(400);  // 重渲后 apply 沉淀
  const after = await page.evaluate((sel) => document.querySelector(`${sel} .canvas-md-scroll`).scrollTop, sel);
  expect(after, 'reading position preserved across the auto refresh').toBe(before);
  console.log(`[E6] scroll ${before} → ${after} across a changed-content auto refresh`);
  expect(errors, 'zero pageerrors').toEqual([]);
  await ctx.close();
});
