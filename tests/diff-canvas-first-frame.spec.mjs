// diff-canvas-first-frame.spec.mjs — diff 打开链「点击→首帧 + 大 diff 不阻塞主线程
// + 失败可见反馈」回归网（二次加载清剿批 2026-10-24 · 作者现场报障 15:42 逐字：
// 「我点diff，然后再侧边栏选diff文件在canvas打开的体验还是很慢，甚至点了没反应。」）
//
// 链路面（diffPanel.js → diffCanvas.js → canvas.openTab；载荷 = WS 帧内存载荷，
// **零网络取数**——T4 用请求计数钉死）：
//   T1  点击 → 首帧必有可见响应面（文件头 + 装载态 .nf-diff-loading 同帧落地；
//       修前 = parseUnifiedDiff 先整份 split ⇒ 秒级白屏 = 「点了没反应」）；
//   T2  大 diff（12 万行）：流式渲染中途主线程帧间隔有界（两连 rAF ≪ 冻结阈值）
//       且 全量行数必达（无硬截断，作者 2026-10-10 裁定承接）；
//   T3  失败路径可见反馈：行腿渲染抛错 ⇒ 面板 meta 槽错误文案 + 行失败态；
//       开批腿装配抛错 ⇒ meta 错误面（修前 `.catch(() => {})` 静默吞掉 =
//       「点了没反应」的另一半）；重试成功 ⇒ 失败面清除；
//   T4  打开链零网络：整条链 /api 请求计数 = 0（载荷内存面，取数不在本链）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/diff-canvas-first-frame.spec.mjs

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/diff-canvas-first-frame/harness.html';
const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript' };

let port;
let server;
let tmpRoot;

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startOrigin(p) {
  server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1');
    const rel = decodeURIComponent(u.pathname).replace(/^\/+/, '');
    if (rel.includes('..')) { res.writeHead(404); res.end(); return; }
    const file = path.join(tmpRoot, rel);
    if (!file.startsWith(tmpRoot) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end(); return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(p, '127.0.0.1', resolve));
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nf-diff-frame-'));
  fs.symlinkSync(path.join(REPO_ROOT, 'src'), path.join(tmpRoot, 'src'), 'dir');
  fs.symlinkSync(path.join(REPO_ROOT, 'tests'), path.join(tmpRoot, 'tests'), 'dir');
  port = await freePort();
  await startOrigin(port);
});

test.afterAll(async () => {
  if (server) await new Promise((r) => server.close(r));
  if (tmpRoot) fs.rmSync(tmpRoot, { recursive: true, force: true });
});

test.beforeEach(async ({ page }) => {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
});

const BIG_LINES = 120000;

/** T1（作者令③ spec①）：点击 → 首帧必有响应面。 */
test('T1: 行点击 → 文件头 + 装载态首帧落地（修前 = 整份 parse 冻结出白屏）', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(n)), BIG_LINES);
  // 预热动态 import（首次 import 的模块编译不属首帧断言面）。
  await page.evaluate((i) => window.__clickRow(i), 1);
  await page.waitForFunction('window.__readerState().hasTab === true');
  await page.waitForFunction('window.__readerState().loading === false'); // 小文件流完

  // 大文件行点击：一个宏任务拍后（import 已热）首帧面必须在场。
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.evaluate(() => new Promise((r) => setTimeout(r, 0)));
  const st = await page.evaluate(() => window.__readerState());
  expect(st.hasHead).toBe(true);    // 文件头 = 首帧定位面（路径/±计数）
  expect(st.loading).toBe(true);    // 装载态 = 「点了有反应」的最小响应面
});

/** T2（作者令③ spec②）：大 diff 不阻塞主线程 + 全量必达。 */
test('T2: 12 万行 diff 流式渲染中途帧间隔有界，全量行数必达', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(n)), BIG_LINES);
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__readerState().hasTab === true');

  // 流式中途（装载态在场 = 行未流完）：两连 rAF 墙钟差必须有界。
  // 修前此处在整份 split（≈十万级字符串物化）上冻结——两帧间隔秒级。
  await page.waitForFunction('window.__readerState().loading === true');
  const mid = await page.evaluate(() => window.__readerState());
  expect(mid.loading).toBe(true);
  expect(mid.rows).toBeLessThan(BIG_LINES + 1); // 仍在流（预算机制在工作）
  const gapMs = await page.evaluate(() => window.__frameGapMs());
  expect(gapMs).toBeLessThan(500); // 冻结形态下此值 > 数秒；流式形态 ≪ 500ms

  // 全量必达（无硬截断）：装载态退场 + 行数 = 精确行数（1 hunk + BIG_LINES 行）。
  await page.waitForFunction('window.__readerState().loading === false', null, { timeout: 60000 });
  const done = await page.evaluate(() => window.__readerState());
  expect(done.rows).toBe(BIG_LINES + 1);
});

/** T3（作者令③ spec③）：失败路径可见反馈（禁静默无响应）。
 *  失败腿 = 真实抛错注入（载荷字段抛错 getter——修前两处 `.catch(() => {})`
 *  静默吞掉的正是这类装配/渲染抛错；摘 canvas 容器不构成失败腿，openTab 自建
 *  DOM 自愈）。两条腿 + 两条恢复弧：
 *    行腿  renderReader 抛 ⇒ 行 open 的 .catch ⇒ meta 错误文案 + 行失败态；
 *    开批腿 renderBatch 抛 ⇒ openDiffBatch 可见失败面（修前 = 静默无响应）；
 *    恢复  重试成功 ⇒ 失败面清除（错误态不粘死）。 */
test('T3: 行腿渲染抛错 ⇒ meta 错误+行失败态；开批腿抛错 ⇒ meta 错误；重试成功 ⇒ 清除', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(20)), 20);
  await page.waitForFunction('window.__readerState().hasTab === true');

  // ── 行腿：文件 0 的 diffText 变抛错 getter ⇒ 行点击 renderReader 抛 ──
  await page.evaluate(() => window.__sabotageFile(0, 'diffText'));
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__metaState().err === true');
  const meta = await page.evaluate(() => window.__metaState());
  expect(meta.text).toContain('Canvas');  // 失败文案（zh-CN 键文案）
  expect(meta.failRows).toBe(1);          // 行失败标记在场

  // 恢复 ⇒ 同行重试成功 ⇒ 失败面清除（错误态不粘死）。
  await page.evaluate(() => window.__restoreFile(0, 'diffText'));
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__metaState().err === false');
  expect((await page.evaluate(() => window.__metaState())).failRows).toBe(0);

  // ── 开批腿：renderBatch 装配抛（added getter）⇒ openDiffBatch 可见失败面 ──
  await page.evaluate((ctx) => window.__openBatchSabotaged(ctx.n, ctx.i, ctx.f), { n: 20, i: 1, f: 'added' });
  await page.waitForFunction('window.__metaState().err === true');
  const meta2 = await page.evaluate(() => window.__metaState());
  expect(meta2.text).toContain('Canvas');

  // 恢复 ⇒ 正常开批成功 ⇒ 失败面清除 + tab 在场。
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(n)), 20);
  await page.waitForFunction('window.__metaState().err === false');
  expect((await page.evaluate(() => window.__readerState())).hasTab).toBe(true);
});

/** T4：diff 打开链零网络（载荷 = WS 帧内存面；取数不在本链）。 */
test('T4: 打开链 /api 请求计数 = 0（载荷内存面，无隐藏取数腿）', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(200)), 200);
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__readerState().hasTab === true');
  await page.evaluate((i) => window.__clickRow(i), 1);
  await page.waitForFunction('window.__readerState().loading === false');
  expect(await page.evaluate(() => window.__apiRequests)).toBe(0);
});
