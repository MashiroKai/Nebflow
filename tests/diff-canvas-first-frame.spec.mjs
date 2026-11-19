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

/** T1（作者令③ spec①）：点击 → 首帧必有响应面。
 *  迁移批 2026-11-19 判据留痕：旧判据 = 「一拍后装载态仍在场」（旧自研分块渲染器
 *  流 12 万行要很多帧，装载态长期在场）。新底座（共享组件 CodeMirror）装载极快，
 *  按拍采样会错过装载态 ⇒ 改为**点击前布观察器**、断言「装载态曾出现过」+ 文件头
 *  在场 —— 语义不变（首帧必有可见响应面），判据更强（不依赖装载时长）。 */
test('T1: 行点击 → 文件头 + 装载态首帧落地（修前 = 整份 parse 冻结出白屏）', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(n)), BIG_LINES);
  // 预热动态 import（首次 import 的模块编译不属首帧断言面）。
  await page.evaluate((i) => window.__clickRow(i), 1);
  await page.waitForFunction('window.__readerState().hasTab === true');
  await page.waitForFunction('window.__readerState().loading === false'); // 小文件装载完

  // 大文件行点击：装载态必须曾出现过（首帧响应面），文件头同帧在场。
  await page.evaluate(() => window.__armLoadingWatch());
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__readerState().hasTab === true');
  const st = await page.evaluate(() => ({ ...window.__readerState(), sawLoading: window.__sawLoadingNow() }));
  expect(st.hasHead).toBe(true);       // 文件头 = 首帧定位面（路径/±计数）
  expect(st.sawLoading).toBe(true);    // 装载态 = 「点了有反应」的最小响应面
});

/** T2（作者令③ spec②）：大 diff 不阻塞主线程 + 全量必达。
 *  迁移批 2026-11-19 判据留痕：旧判据 = 「DOM 行数 = 总行数」（旧自研渲染器把每行
 *  都挂进 DOM）。新底座按视口**虚拟化**（D9 的正解）⇒ DOM 行数恒远小于总行数，
 *  旧读数已无意义（非放水）。新判据 = 「**文档**行数精确 = 总行数」（无硬截断，
 *  作者 2026-10-10 裁定承接）+「DOM 行数远小于文档行数」（虚拟化实证）+ 帧间隔有界。 */
test('T2: 12 万行 diff 文档全量必达 + 视口虚拟化 + 帧间隔有界', async ({ page }) => {
  await page.evaluate((n) => window.__openBatch(window.__bigPayload(n)), BIG_LINES);
  const t0 = Date.now();
  await page.evaluate((i) => window.__clickRow(i), 0);
  await page.waitForFunction('window.__readerState().hasTab === true');

  // 全量必达（无硬截断）：文档行数 = 统一差异转换后的现文精确行数。
  await page.waitForFunction('window.__readerState().loading === false', null, { timeout: 60000 });
  const mountMs = Date.now() - t0;
  const expected = await page.evaluate((n) => window.__expectedDocLines(n), BIG_LINES);
  const done = await page.evaluate(() => window.__readerState());
  expect(expected).toBeGreaterThan(1000);
  expect(done.docLines).toBe(expected);
  expect(done.mountedLines).toBeLessThan(done.docLines); // 视口虚拟化（D9）

  // 挂载后（稳态）主线程按帧出票（禁冻结）。迁移批 2026-11-19 判据留痕：旧判据取
  // 「流式中途」的帧间隔（旧自研渲染器分帧挂行）；新底座一次性构建文档再虚拟化，
  // 中途无「流」可言 ⇒ 取稳态读数。实测 12 万行合成载荷：挂载 1.27s、稳态帧间隔
  // 0.12s（tests/_bench-mobile-diff.mjs 读数，见报告 D9 行）——真实载荷（651/1380
  // 行）挂载 11/16ms。挂载总时长另有上界（禁无界冻结）。
  const gap = await page.evaluate(() => window.__frameGapMs());
  expect(gap).toBeLessThan(500);
  expect(mountMs).toBeLessThan(9000);
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
  expect(meta.text).toContain('看板');  // 失败文案（zh-CN 键文案）
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
  expect(meta2.text).toContain('看板');

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
