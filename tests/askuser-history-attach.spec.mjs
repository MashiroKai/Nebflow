// askuser-history-attach.spec.mjs — AskUser 历史恢复卡的附件行（在位）双向钉。
//
// askUser 历史恢复批（2026-10-02，chain-askuser-histattach）：后端落盘行带
// `attachments`（`UiMessage.AskUser`）⇒ 历史恢复路径（`persistence.js` 两条腿的
// `renderAskUserHistory`）读回并在卡上还原附件行。
//
// 本件 = 同一份 harness（tests/fixtures/askuser-history-attach/harness.html）双树对照
// （baseline = 钉死的改前 ref；fixed = 本支工作树），驱动
// `restoreFromBackendHistory`（后端历史恢复腿）与 `restoreFromStorage`（本地缓存腿）。
//
// 🔴 读数只锚类名 / 属性 / DOM 计数 / 单点入口事件（禁锚本地化文本）。
// 🔴 静态服务只起在 127.0.0.1 临时端口（绝非 8080 宿主）；afterAll 全清。
//
// Run: node node_modules/@playwright/test/cli.js test tests/askuser-history-attach.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn, execSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { guardWrites } from '../scripts/lib/writeguard.mjs';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_REL = 'tests/fixtures/askuser-history-attach/harness.html';
const HARNESS_PATH = '/' + HARNESS_REL;
const SHOT_DIR = process.env.ASKUSER_HISTATT_SHOTS
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261002_askuser_histattach');
// W1 写根断言（模块加载期 fail-fast，先于任何 browser/启动动作）
guardWrites([SHOT_DIR], { repoRoot: REPO_ROOT, label: 'askuser-history-attach' });

// 基线 ref = 本批开工时的 main 尖（= 改前代码；本支提交后 HEAD 前移，故钉死此 sha）。
const BASELINE_REF = process.env.ASKUSER_HISTATT_BASELINE_REF ?? '66f7ec46a4dcb9903a699f93ac435b5cd2f6c21c';

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
  baselineRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nb-askhistatt-baseline-'));
  execSync(`git archive ${BASELINE_REF} src/main/resources/web | tar -x -C "${baselineRoot}"`, { cwd: REPO_ROOT });
  const dst = path.join(baselineRoot, path.dirname(HARNESS_REL));
  fs.mkdirSync(dst, { recursive: true });
  fs.copyFileSync(path.join(REPO_ROOT, HARNESS_REL), path.join(dst, 'harness.html'));

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

// 后端历史恢复腿的形状 = `.ui.json` 行（与 `Encoder[UiMessage]` 同构）。
const WITH_ATT = [{
  type: 'askUser',
  items: [{ question: 'harness: attach rows', options: [{ label: 'alpha' }, { label: 'beta' }] }],
  requestId: 'ask-hist-attach-1',
  attachments: ['/tmp/nb-histatt-fixture/notes.md', '/tmp/nb-histatt-fixture/plan.pdf'],
}];
const WITH_ATT_ANSWERED = [
  WITH_ATT[0],
  { type: 'user', text: 'alpha', answerOf: 'ask-hist-attach-1', timestamp: 1759400000000 },
];
const WITHOUT_ATT = [{
  type: 'askUser',
  items: [{ question: 'harness: no attach rows', options: [{ label: 'alpha' }, { label: 'beta' }] }],
  requestId: 'ask-hist-noattach-1',
}];
// pending 腿夹具：最后一条 = **未作答** askUser（带附件）⇒ main.js:1977 的
// `isAskUserPending` 分支成立（其后无 ai/user 行）。
const PENDING_WITH_ATT = [{
  type: 'askUser',
  items: [{ question: 'harness: pending attach card', options: [{ label: 'alpha' }, { label: 'beta' }] }],
  requestId: 'ask-hist-pending-att-1',
  attachments: ['/tmp/nb-histatt-fixture/notes.md'],
}];

// ============================================================
// ① 验红基线（改前代码）：历史卡没有附件渲染面
// ============================================================
test.describe('改前树（baseline）— 缺口如实复现', () => {
  test('B1 带附件的历史行：零附件块（缺口成立，非渲染失败）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    const r = await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.hasBox, '卡照常渲染').toBe(true);
    expect(r.hasWrap, '改前：历史卡没有附件渲染面').toBe(false);
    expect(r.rows).toBe(0);
    await context.close();
  });
});

// ============================================================
// ② 修复后（本支）：附件行在位 + 既有形态 + 单点入口
// ============================================================
test.describe('改后树（fixed）— 附件行在位', () => {
  test('F1 后端历史腿：附件块在位、复用既有类名、插位在 .option-btn-row 之前', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.hasBox).toBe(true);
    expect(r.hasWrap, '附件块必须在位').toBe(true);
    expect(r.rows, '两件附件 ⇒ 两行').toBe(2);
    expect(r.wrapIsQWrapper, '承载块复用既有 .option-q-wrapper').toBe(true);
    expect(r.beforeBtnRow, '插位在 .option-btn-row 之前').toBe(true);
    expect(r.rowTags, '有 viewer 认领 ⇒ 真 <button>（非假按钮）').toEqual(['BUTTON', 'BUTTON']);
    expect(r.rowTitles.every(t => t.startsWith('/tmp/nb-histatt-fixture/')), '全路径只进 title').toBe(true);
    expect(r.boxHistoryReplay, '历史恢复形态锚在场').toBe(true);
    await page.screenshot({ path: path.join(SHOT_DIR, '20261002_askhistatt-1-fixed-attach-rows.png'), fullPage: true });
    await context.close();
  });

  test('F2 点击面两读数：行级单点入口已接线；卡级终态锁（既有语义）为独立读数', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    const r = await page.evaluate(() => window.__read());
    expect(pageErrors).toEqual([]);
    expect(r.clickHandlerAttached, '每行挂 onclick（单点入口 previewLocalPath）').toEqual([true, true]);
    // 历史卡走 lockOptionBox（既有终态语义：历史恢复卡 = 已决卡）：置灰 + disabled。
    // 本批不越界改 lockOptionBox / renderAskAttachments 本体 ⇒ 如实取读数。
    expect(r.boxResolved, '历史恢复卡 = 已决卡（既有 lockOptionBox 语义）').toBe(true);
    expect(r.rowDisabled, '终态卡内可交互件 disabled（既有语义）').toEqual([true, true]);
    expect(r.sentFrames.filter(f => f === 'pop.readFile').length, '每行一次可达性探针').toBe(2);

    // ① 卡级点击读数（终态锁的既有语义）：capture 阶段哨兵拦下 ⇒ 零派发。
    const viaClick = await page.evaluate(() => window.__clickRow(0));
    expect(viaClick.clicked).toBe(true);
    expect(viaClick.tag).toBe('BUTTON');
    expect(viaClick.dispatched, '终态卡：capture 哨兵拦下整卡点击（既有 lockOptionBox 语义）').toBe(0);

    // ② 行级接线读数：入口函数本身走单点派发，id 逐字 `file:<absPath>`（与 live 腿同源）。
    const viaEntry = await page.evaluate(() => window.__callRowEntry(0));
    expect(viaEntry.dispatched, '行级单点入口本身可用（接线正确）').toBe(1);
    expect(viaEntry.detailIds, '派发 id 逐字 = file:<absPath>').toEqual(['file:/tmp/nb-histatt-fixture/notes.md']);
    await context.close();
  });

  test('F3 不扰动 markAnsweredPick：已作答附件卡仍按问题下标还原答案、问题仍在第 0 位', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT_ANSWERED);
    expect(pageErrors).toEqual([]);
    expect(r.hasWrap).toBe(true);
    expect(r.questionAtIndexZero, '附件块 append 在所有问题 wrapper 之后，不顶掉第 0 位').toBe(true);
    expect(r.qWrapperCount, '1 个问题 wrapper + 1 个附件承载块').toBe(2);
    const answer = await page.locator('#chat .option-box .option-answer').first().textContent();
    expect(answer.startsWith('-> '), '按 answerOf 精确寻址取到作答行').toBe(true);
    await context.close();
  });

  test('F4 localStorage 兜底腿同款：本地缓存行也还原附件行', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__storage(msgs), WITH_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.hasWrap, '两条历史腿同构（都经 renderAskUserHistory）').toBe(true);
    expect(r.rows).toBe(2);
    await context.close();
  });

  // 🔴 开放项（如实申报，不擅自扩面）：历史恢复卡是**已决卡**（既有 `lockOptionBox`
  // 语义）⇒ 卡内附件行与 live 卡**确认后**同形（disabled + capture 哨兵）。
  // 本件钉的是「该形态 = 既有语义、非本批引入」：同一份 lockOptionBox 在 live 腿
  // 确认后对同一附件行取到**同一读数**。
  test('F5 对照：同一 lockOptionBox 语义在 live 卡确认后对附件行取到同一 disabled 读数', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate(() => window.__liveThenConfirm());
    expect(pageErrors).toEqual([]);
    expect(r.before.rows, 'live pending 卡：附件行在位').toBe(1);
    expect(r.before.disabled, 'live pending 卡：附件行可点（未锁）').toEqual([false]);
    expect(r.before.boxResolved).toBe(false);
    expect(r.confirmEnabled, '选完首个问题后确认键可用').toBe(true);
    expect(r.after.boxResolved, '确认后卡进入既有终态（resolved）').toBe(true);
    expect(r.after.disabled, '确认后同一附件行 disabled —— 与本批历史卡读数一致（既有语义）').toEqual([true]);
    await context.close();
  });

  // 🔴 开放项取证（结构性）：即便把 disabled 强行复位，卡级 capture 点击哨兵仍拦下 ⇒
  // 「历史卡附件行可点」须改 `lockOptionBox` 本体（任务书明令禁触面）⇒ 本席不停手、
  // 不擅自扩面，按字面交付并如实申报（见 result 开放项）。
  test('F6 开放项取证：复位 disabled 后仍零派发 ⇒ 可点性须改 lockOptionBox 本体（禁触面）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    const r = await page.evaluate(() => window.__forceEnableAndClick(0));
    expect(pageErrors).toEqual([]);
    expect(r.clicked).toBe(true);
    expect(r.disabledAfterReset, '复位生效（disabled=false）').toBe(false);
    expect(r.rowStillInDom, '行未被降级/移除（探针无应答窗口内取数）').toBe(true);
    expect(r.dispatched, 'capture 哨兵单独即拦下 ⇒ 不改 lockOptionBox 则不可点').toBe(0);
    await context.close();
  });

  // ✅ 「在位且可点」的实证腿 = main.js:1977 的 pending 重挂（历史行附件 ⇒ live
  // `renderAskUser` ⇒ 交互卡）。这是「刷新后附件行可点」的真实到达面：未作答的
  // askUser 行在恢复后由 live 卡接管，而该卡**未**进终态锁。
  test('F7 pending 腿（main.js:1977 同形）：历史行附件还原到 live 交互卡 ⇒ 在位且可点', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__pendingRestore(msgs), PENDING_WITH_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.read.boxes, '历史孪生卡已被回收，恰一张交互卡').toBe(1);
    expect(r.read.rows, '附件行在位').toBe(1);
    expect(r.read.boxResolved, '交互卡未进终态锁（pending）').toBe(false);
    expect(r.read.disabled, '附件行未禁用 ⇒ 可点').toEqual([false]);
    expect(r.click.dispatched, '真点击 ⇒ 单点入口派发（可点性实证）').toBe(1);
    expect(r.click.detailIds).toEqual(['file:/tmp/nb-histatt-fixture/notes.md']);
    await context.close();
  });
});

// ============================================================
// ③ 负对照：无附件 ⇒ 改前 / 改后逐字节同形（非 askUser 行零影响）
// ============================================================
test.describe('负对照 — 缺席即零影响', () => {
  test('N1 无附件的历史行：两树 outerHTML 逐字相等', async ({ browser }) => {
    async function outerHtml(port) {
      const { context, page, pageErrors } = await newPage(browser, port);
      const html = await page.evaluate(async (msgs) => {
        await window.__replay(msgs);
        return document.querySelector('#chat .option-box').outerHTML;
      }, WITHOUT_ATT);
      expect(pageErrors).toEqual([]);
      await context.close();
      return html;
    }
    const base = await outerHtml(baselinePort);
    const fixed = await outerHtml(fixedPort);
    expect(base.length).toBeGreaterThan(0);
    expect(fixed, '无附件 ⇒ 改后树与改前树逐字相等').toEqual(base);
  });

  test('N2 无附件 ⇒ 零附件 DOM + 零探针帧', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__replay(msgs), WITHOUT_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.hasWrap).toBe(false);
    expect(r.rows).toBe(0);
    expect(r.sentFrames.filter(f => f === 'pop.readFile')).toEqual([]);
    await context.close();
  });
});
