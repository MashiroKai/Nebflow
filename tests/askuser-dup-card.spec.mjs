// askuser-dup-card.spec.mjs — AskUser「同 id 双卡」（首卡恒死）前端修复的双向钉。
//
// 缺陷（`.nebflow/reports/20260921_181620_askuserdup-arch__chain-askuserdup.md` §1.5）：
//   历史恢复路径给**仍 pending** 的卡也补一行 `.option-answer`（改前 `chat.js
//   renderAskUserHistory` 无条件 `if (!cancelled)` 追加），而重放腿的去重判据是
//   「卡上有没有作答行」⇒ 判为已答 ⇒ 不删既有卡 ⇒ 重放卡挂成第二张。历史卡先入
//   DOM 且已 `lockOptionBox`（置灰 + 全 disabled + capture 点击哨兵）、又无
//   `data-request-id`（`UiMessage.AskUser` 只落 `{type, items}`）⇒ 首卡恒死、引擎
//   也关不掉它。
//
// 本件 = 案 A/B（前端最小闭环 + 数据面根治）的**静态最小红验**（全链红验见
// tests/askuser-refresh-survive.spec.mjs T3）。双树对照：
//   - baseline: `git archive <pinned pre-fix ref> src/main/resources/web`（改前代码）
//   - fixed:    本支工作树（改后代码）
// 同一份 harness（tests/fixtures/history-replay/harness.html）驱动两棵树 ⇒ 行为差
// = 被测代码差。静态服务只起在 127.0.0.1 的临时端口（🔴 绝非 8080 宿主），afterAll
// 全清。
//
// Run: node node_modules/@playwright/test/cli.js test tests/askuser-dup-card.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn, execSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { guardWrites } from '../scripts/lib/writeguard.mjs';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_DIR = path.join(REPO_ROOT, 'tests', 'fixtures', 'history-replay');
const HARNESS_PATH = '/tests/fixtures/history-replay/harness.html';
const SHOT_DIR = process.env.ASKUSER_DUP_SHOTS
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20260921_askuserdup');
// W1 写根断言（模块加载期 fail-fast，先于任何 browser/启动动作）
guardWrites([SHOT_DIR], { repoRoot: REPO_ROOT, label: 'askuser-dup-card' });

// 基线 ref = 本批开工时的 main 尖（= 改前代码；本支提交后 HEAD 前移，故钉死此 sha）。
const BASELINE_REF = process.env.ASKUSER_DUP_BASELINE_REF ?? '65cbea5b2';
const FIXTURES = ['fixture.json', 'pending-fixture.json', 'pending-legacy-fixture.json', 'answered-marker-fixture.json'];

let baselineRoot;
let baselinePort;
let fixedPort;
const servers = [];

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

function startStaticServer(port, dir) {
  return spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  baselineRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nb-askuserdup-baseline-'));
  execSync(`git archive ${BASELINE_REF} src/main/resources/web | tar -x -C "${baselineRoot}"`, { cwd: REPO_ROOT });
  // Harness + 全部 fixture 必须在**两棵树**下同源可达（module import + fetch 同源）
  const baseHarnessDir = path.join(baselineRoot, 'tests', 'fixtures', 'history-replay');
  fs.mkdirSync(baseHarnessDir, { recursive: true });
  fs.copyFileSync(path.join(HARNESS_DIR, 'harness.html'), path.join(baseHarnessDir, 'harness.html'));
  for (const f of FIXTURES) fs.copyFileSync(path.join(HARNESS_DIR, f), path.join(baseHarnessDir, f));

  baselinePort = await freePort();
  fixedPort = await freePort();
  servers.push(startStaticServer(baselinePort, baselineRoot));
  servers.push(startStaticServer(fixedPort, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${baselinePort}${HARNESS_PATH}`);
  await waitUntilUp(`http://127.0.0.1:${fixedPort}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
  if (baselineRoot) fs.rmSync(baselineRoot, { recursive: true, force: true });
});

async function newPage(browser, port) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const RID = 'ask-pending-fixture-1';
const ITEMS = [{
  question: '双开复现夹具：非阻塞提问（pending 期间 agent 继续产出 ai 行）',
  options: [{ label: 'alpha', description: '选项 A' }, { label: 'beta', description: '选项 B' }],
}];

// ============================================================
// ① 验红基线（改前代码）：pending 卡被画成「已作答」+ 重放腿挂出同 id 第二张卡
// ============================================================
test.describe('改前树（baseline）— 双开与假作答行如实复现', () => {
  test('R1 历史恢复：仍 pending 的卡被补一行假作答行（.option-answer）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    const counts = await page.evaluate((f) => window.__replayNamed(f), 'pending-fixture');
    expect(pageErrors).toEqual([]);
    expect(counts.boxes).toBe(1);
    // 改前：`cancelled=false` ⇒ 无条件补 `.option-answer` ⇒ 「无作答记录」被画成「已作答」
    expect(counts.answered, '改前：pending 卡带假作答行').toBe(1);
    expect(counts.pendingNote, '改前：无显式待定标注').toBe(0);
    // 改前：历史卡不带 requestId（UiMessage.AskUser 只落 {type, items}）
    expect(counts.rids, '改前：历史卡无 data-request-id').toEqual(['']);
    await page.screenshot({ path: path.join(SHOT_DIR, '20260921_askuserdup-1-baseline-fake-answer.png'), fullPage: true });
    await context.close();
  });

  test('R2 重放腿：同 id 两张卡（首卡锁定且够不到）+ 现场 DOM 读数', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    await page.evaluate((f) => window.__replayNamed(f), 'pending-fixture');
    const counts = await page.evaluate(([rid, items]) => window.__replayLeg(rid, items), [RID, ITEMS]);
    expect(pageErrors).toEqual([]);
    expect(counts.boxes, '改前：同 id 两张卡').toBe(2);
    expect(counts.answered, '改前：首卡带假作答行').toBe(1);
    expect(counts.resolved, '改前：首卡已被 lockOptionBox 锁死').toBe(1);
    expect(counts.rids.filter(Boolean), '改前：只有重放卡可 id 寻址').toEqual([RID]);
    expect(await page.evaluate(() => window.__reclaimUnit('probe-rid'))).toEqual({ supported: false });
    await page.screenshot({ path: path.join(SHOT_DIR, '20260921_askuserdup-2-baseline-two-cards.png'), fullPage: true });
    await context.close();
  });
});

// ============================================================
// ② 修复后（本支）：无作答记录不再伪装已作答 + id 优先去重 ⇒ 恰一张活卡
// ============================================================
test.describe('改后树（fixed）— 案 A + 案 B 的目标态', () => {
  test('F1 历史恢复（案 B 行）：显式待定标注、无假作答行、卡可 id 寻址', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const counts = await page.evaluate((f) => window.__replayNamed(f), 'pending-fixture');
    expect(pageErrors).toEqual([]);
    expect(counts.boxes).toBe(1);
    expect(counts.answered, '案 A①：无作答记录不得补 .option-answer').toBe(0);
    expect(counts.pendingNote, '案 A①：改挂显式待定标注').toBe(1);
    expect(counts.pendingNoteText).toBe('历史无作答记录（待定）');
    expect(counts.rids, '案 B：提问行随行落盘的 requestId 透传到历史卡').toEqual([RID]);
    // 死卡显式标注（不倒向「一律可点」）：卡保持终态 + 全控件 disabled
    expect(counts.resolved).toBe(1);
    expect(counts.disabledBtns).toBeGreaterThan(0);
    await page.screenshot({ path: path.join(SHOT_DIR, '20260921_askuserdup-3-fixed-pending-card.png'), fullPage: true });
    await context.close();
  });

  test('F2 重放腿：id 优先无条件回收 ⇒ 恰一张活卡（首卡不再死）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate((f) => window.__replayNamed(f), 'pending-fixture');
    const counts = await page.evaluate(([rid, items]) => window.__replayLeg(rid, items), [RID, ITEMS]);
    expect(pageErrors).toEqual([]);
    expect(counts.boxes, '恰一张卡').toBe(1);
    expect(counts.answered).toBe(0);
    expect(counts.pendingNote, '历史孪生卡已被回收').toBe(0);
    expect(counts.resolved, '幸存的那张 = 重放活卡（未锁）').toBe(0);
    expect(counts.rids).toEqual([RID]);
    expect(counts.disabledBtns, '活卡选项可点').toBe(0);
    await page.screenshot({ path: path.join(SHOT_DIR, '20260921_askuserdup-4-fixed-single-card.png'), fullPage: true });
    await context.close();
  });

  test('F3 旧行（无 requestId 无标记）⇒ 回落「未作答」＋无 id 形态兜底仍生效', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const counts = await page.evaluate((f) => window.__replayNamed(f), 'pending-legacy-fixture');
    expect(pageErrors).toEqual([]);
    expect(counts.answered, '旧行不得被读成已作答').toBe(0);
    expect(counts.pendingNote).toBe(1);
    expect(counts.rids, '旧行本就没有 requestId（不伪造）').toEqual(['']);
    // 无 id 的历史未作答卡仍被重放腿回收（旧数据形态的兜底腿）
    const after = await page.evaluate(([rid, items]) => window.__replayLeg(rid, items), [RID, ITEMS]);
    expect(after.boxes, '兜底回收后恰一张活卡').toBe(1);
    expect(after.resolved).toBe(0);
    await context.close();
  });

  test('F4 案 B 取值表：answerOf 精确寻址（run 被 ai 行截断也取到真答案）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const counts = await page.evaluate((f) => window.__replayNamed(f), 'answered-marker-fixture');
    expect(pageErrors).toEqual([]);
    // 旧读法（遇第一个非 user 行即 break）在这份 shape 上取 null ⇒ 改前会画成
    // 「已作答但空答案」；案 B 按 answerOf === 本行 requestId 精确寻址 ⇒ 取到 'alpha'
    expect(counts.answered, '已作答卡照常带答案行').toBe(1);
    expect(counts.pendingNote).toBe(0);
    const answerText = await page.locator('.option-answer').first().textContent();
    expect(answerText).toBe('-> alpha');
    expect(counts.rids).toEqual(['ask-answered-fixture-1']);
    await context.close();
  });

  test('F5 单一判据单元：id 命中**无条件**（既有卡带作答行也照样回收）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate(() => window.__reclaimUnit('probe-rid'));
    expect(pageErrors).toEqual([]);
    expect(r.supported).toBe(true);
    expect(r.before.boxes, '探针卡挂出（且带一行「作答」行 = 改前不删的那张）').toBe(1);
    expect(r.before.answered).toBe(1);
    expect(r.removed, '案 A②：id 命中即无条件移除（不再看作答行）').toBe(1);
    expect(r.after.boxes).toBe(0);
    await context.close();
  });

  test('F6 回归：既有已作答 fixture 的历史还原逐字不变', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate(() => window.__replay());
    await page.waitForTimeout(300);
    expect(pageErrors).toEqual([]);
    await expect(page.locator('.option-box').first().locator('.option-answer')).toHaveText('-> 都做。');
    await expect(page.locator('.option-box').first().locator('.option-btn').first()).toBeDisabled();
    await expect(page.locator('.option-answer-pending')).toHaveCount(0);
    await context.close();
  });
});
