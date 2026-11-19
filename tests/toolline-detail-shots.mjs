// toolline-detail-shots.mjs — 工具执行行增强批（toolline-detail-x1）before/after
// 取证脚本（非断言 spec；判据在 badge-injected-turn.spec.mjs 与
// toolline-detail-stream.spec.mjs）。同一驱动脚本跑两棵树：
//   BEFORE = git worktree @ HEAD（05fa54c01，本批改动之前）
//   AFTER  = 当前工作树（本批改动之后）
// 四类工具实录（delegate / bash / 读 / 写）+ 注入腿 badge 分组 + 完成翻转。
// 产物：.nebflow/evidence/20261006_toolline-detail-x1/{before,after}/*.png
//
// Run: node tests/toolline-detail-shots.mjs

import { chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const BEFORE_DIR = process.env.NB_BEFORE_DIR || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_toolline-detail-x1', 'before');
const AFTER_DIR = process.env.NB_AFTER_DIR || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_toolline-detail-x1', 'after');
const HARNESS = '/tests/fixtures/toolline-detail/harness.html';

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

async function withServer(dir, fn) {
  const port = await freePort();
  const srv = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(`http://127.0.0.1:${port}${HARNESS}`); if (r.ok) break; } catch { /* retry */ }
    await new Promise(r => setTimeout(r, 100));
  }
  try { await fn(port); } finally { srv.kill('SIGTERM'); }
}

async function drive(port, shotDir, tag) {
  fs.mkdirSync(shotDir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  const shot = (name) => page.screenshot({ path: path.join(shotDir, `${tag}-${name}.png`), fullPage: true });
  const raf = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

  // ── 场景 1：bash 运行中（spinner + 细节行）──────────────────────────
  await page.evaluate(() => window.__tdUser('帮我看下仓库状态'));
  await page.evaluate(() => window.__tdCallStart('Bash'));
  for (const c of ['{"command":"git sta', 'tus && npm ', 'test --silent"}']) {
    await page.evaluate(c => window.__tdArg('Bash', c), c);
    await raf();
  }
  await page.evaluate(() => window.__tdExecStart('Bash\n  (git status && npm test --silent)'));
  await raf();
  await shot('1-bash-running');

  // ── 场景 2：读运行中（路径细节）────────────────────────────────────
  await page.evaluate(() => window.__tdDone('Bash\n  (git status && npm test --silent)', 'No output', 'ok', false, '{"command":"git status"}'));
  await page.evaluate(() => window.__tdCallStart('Read'));
  for (const c of ['{"file_path":"/tmp/very/long/', 'path/nebflow-report.md"}']) {
    await page.evaluate(c => window.__tdArg('Read', c), c);
    await raf();
  }
  await page.evaluate(() => window.__tdExecStart('Read\n  ("nebflow-report.md")'));
  await raf();
  await shot('2-read-running');

  // ── 场景 3：写完成翻转（✓ + 单行结果摘要）──────────────────────────
  await page.evaluate(() => window.__tdDone('Read\n  ("nebflow-report.md")', '12 lines', 'file body', false, '{"file_path":"/tmp/very/long/path/nebflow-report.md"}'));
  await page.evaluate(() => window.__tdCallStart('Write'));
  for (const c of ['{"file_path":"out.', 'md","content":"# 标题\\n第一段"}']) {
    await page.evaluate(c => window.__tdArg('Write', c), c);
    await raf();
  }
  await page.evaluate(() => window.__tdExecStart('Write\n  ("out.md")'));
  await raf();
  await shot('3-write-running');
  await page.evaluate(() => window.__tdDone('Write\n  ("out.md")', 'File created', 'ok', false, '{"file_path":"out.md"}'));
  await raf();
  await shot('3b-write-done-flip');

  // ── 场景 4：注入腿开轮 + delegate 运行中（badge 分组 + brief 首行）──
  await page.evaluate(() => window.__tdFinishTurn(3000, 'test-model'));
  await page.evaluate(() => window.__tdInject('【deviceMail】远程指令：检查构建产物清单并回报', 'deviceMail'));
  await page.evaluate(() => window.__tdCallStart('Delegate'));
  for (const c of ['{"task":"检查 target/release 下的', '构建产物清单\\n输出前 20 行并给', '出体积读数"}']) {
    await page.evaluate(c => window.__tdArg('Delegate', c), c);
    await raf();
  }
  await page.evaluate(() => window.__tdExecStart('Delegate(检查 target/release 下的构建产物清单)'));
  await raf();
  await shot('4-injected-delegate-running');

  // ── 场景 5：注入轮闭合（新 badge 重建 vs 叠旧）─────────────────────
  await page.evaluate(() => window.__tdDone('Delegate(检查 target/release 下的构建产物清单)', 'ok', 'done', false, '{"task":"检查产物"}'));
  await page.evaluate(() => window.__tdFinishTurn(1500, 'test-model', '注入轮的最终回复。', '✻ 注入轮 2 秒'));
  await raf();
  await shot('5-injected-turn-settled');

  // 读数落盘（badge 位置/细节行文本）。
  const census = await page.evaluate(() => window.__tdCensus());
  fs.writeFileSync(path.join(shotDir, `${tag}-census.json`), JSON.stringify(census, null, 2));
  await browser.close();
}

const serveBefore = process.env.NB_SERVE_BEFORE; // 基线树根（含拷入的 harness）
const serveAfter = process.env.NB_SERVE_AFTER || REPO_ROOT;

await withServer(serveBefore, (p) => drive(p, BEFORE_DIR, 'before'));
console.log('before shots →', BEFORE_DIR);
await withServer(serveAfter, (p) => drive(p, AFTER_DIR, 'after'));
console.log('after shots →', AFTER_DIR);
