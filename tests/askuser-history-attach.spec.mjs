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
import http from 'node:http';
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

// 基线 ref = 本批开工时的分支尖 9389ff749（= 本批改动的父提交；含 opt-other 等
// 先行批、不含本批改动）。历史注记：原默认值 66f7ec46a 是 2026-10-02「历史卡无
// 附件渲染面」缺口的验红基线——该缺口早已被后续批关闭，且 2026-10-06 杂交批的
// `opt-other` 钩子类使 N1 双树 outerHTML 对照在 main 上先行失绿；askuser-
// attach-replyrender 批（2026-10-30）顺势把基线前移到本批父提交，B1 由「缺口验红」
// 改签为「旧 Canvas 开法行为锚」（B1 三读数 = 本批刻意改变的旧语义回归钉）。
const BASELINE_REF = process.env.ASKUSER_HISTATT_BASELINE_REF ?? '9389ff749';

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
  // KAI Windows 环境兼容（perf 台账 #922 批顺手修，行为面不变）：原腿
  // `python3 -m http.server` 在无 python 的 Windows 检出直接 ENOENT ⇒ 换 node
  // 静态伺服（history-replay-cards 同款；html/js/json/css/svg/png + 穿越防护）。
  const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json',
    '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  };
  const srv = http.createServer((req, res) => {
    let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (p.endsWith('/')) p += 'index.html';
    const file = path.join(dir, p);
    if (!file.startsWith(dir)) { res.writeHead(403); res.end(); return; }
    try {
      const b = fs.readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.')).toLowerCase()] || 'application/octet-stream' });
      res.end(b);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  srv.listen(port, '127.0.0.1');
  return { kill: () => srv.close() };
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
  // KAI Windows 环境兼容（perf 台账 #922 批顺手修，行为不变）：反斜杠 -C 路径
  // 被 bsdtar 当转义吃掉 ⇒ 管道解包必败；正斜杠两侧皆可（history-replay-cards 同款）。
  execSync(`git archive ${BASELINE_REF} src/main/resources/web | tar -x -C "${baselineRoot.split(path.sep).join('/')}"`, { cwd: REPO_ROOT });
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
// ① 基线树（baseline）— 旧 Canvas 开法行为锚（askuser-attach-replyrender 批改签）
// ============================================================
test.describe('基线树（baseline）— 旧行为锚', () => {
  test('B1 基线锚：附件行在位，但 = 旧语义（WS 探针 / 行 disabled / 入口跳 Canvas）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    const r = await page.evaluate(() => window.__read());
    expect(pageErrors).toEqual([]);
    expect(r.hasBox, '卡照常渲染').toBe(true);
    // 历史注记：基线已含 2026-10-02 附件行本身（缺口早已关闭）——本腿锚的是
    // 「行在，但开法/锁语义是旧的那套」，三读数全是本批刻意改变的旧语义。
    expect(r.hasWrap, '基线（本批开工尖）已有附件渲染面').toBe(true);
    expect(r.rows).toBe(2);
    expect(r.rowTags).toEqual(['BUTTON', 'BUTTON']);
    expect(r.sentFrames.filter(f => f === 'pop.readFile').length, '旧探针 = pop.readFile WS 帧').toBe(2);
    expect(r.rowDisabled, '旧 lockOptionBox 不豁免附件行 ⇒ 终态卡行 disabled').toEqual([true, true]);
    const viaEntry = await page.evaluate(() => window.__callRowEntry(0));
    expect(viaEntry.dispatched, '旧行入口 = Canvas 打开腿（readFile 帧已发）').toBe(1);
    expect(viaEntry.detailIds).toEqual(['/tmp/nb-histatt-fixture/notes.md']);
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

  test('F2 点击面两读数：行级入口 = 下载腿；已决卡附件行可点（lockOptionBox 豁免实证）+ 零 Canvas', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    const r = await page.evaluate(() => window.__read());
    expect(pageErrors).toEqual([]);
    expect(r.clickHandlerAttached, '每行挂 onclick（单点入口 = 下载腿）').toEqual([true, true]);
    // 历史卡仍走 lockOptionBox（终态语义不变），但 askuser-attach-replyrender 批
    // （2026-10-30 作者令「不需要在 Canvas 打开」）给 .ask-attachments-wrap 只读查看面
    // 开了豁免：「查看附件」≠「重新作答」⇒ 附件行不 disabled、点击哨兵放行。
    expect(r.boxResolved, '历史恢复卡 = 已决卡（lockOptionBox 语义不变）').toBe(true);
    expect(r.rowDisabled, '附件行豁免终态锁 ⇒ 仍可用').toEqual([false, false]);
    // 探针已从 WS pop.readFile 腿迁到票据轨（旧探针的 fileContent 应答被 explorer.js
    // 全局处理器重派 workspace-open-item 自动跳 Canvas = 作者报障机制根因）⇒ 零帧。
    expect(r.sentFrames.filter(f => f === 'pop.readFile').length, '探针迁离 WS 轨 ⇒ 零 pop.readFile 帧').toBe(0);
    // 探针在新轨有真实读数：两件附件 ⇒ 至少一次 nf-file 首字节探测（铸票合帧，≤1 次 mint）。
    // 探针链是异步的（模块 import + 铸票 + Range 取字节）⇒ 轮询等读数落账。
    await expect.poll(async () =>
      (await page.evaluate(() => window.__railCounts)).nfFile,
      { timeout: 5000 }).toBeGreaterThanOrEqual(2);

    // ① 卡级真点击（终态卡内）：哨兵豁免附件行 ⇒ 点击到达行 onclick；行入口 =
    //    下载腿 ⇒ 零 workspace-open-item（Canvas 面整批退役）。
    const viaClick = await page.evaluate(() => window.__clickRow(0));
    expect(viaClick.clicked).toBe(true);
    expect(viaClick.tag).toBe('BUTTON');
    expect(viaClick.dispatched, '下载腿 ⇒ 零 workspace-open-item').toBe(0);
    await expect.poll(async () =>
      (await page.evaluate(() => window.__railCounts)).nfFile,
      { timeout: 5000 }).toBeGreaterThanOrEqual(3); // 点击触发整件取回（探针 2 + 下载 1+）
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

  // askuser-attach-replyrender 批（2026-10-30）：附件行（.ask-attachments-wrap =
  // 只读查看面）已获 lockOptionBox 豁免 ⇒ 「已决卡附件行不可点」的开放项随本批关闭。
  // 豁免只放通行，不放开作答控件（option-btn/confirm/cancel 仍锁）。
  test('F5 对照：live 卡确认后附件行仍可点（豁免语义），作答控件保持终态锁', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate(() => window.__liveThenConfirm());
    expect(pageErrors).toEqual([]);
    expect(r.before.rows, 'live pending 卡：附件行在位').toBe(1);
    expect(r.before.disabled, 'live pending 卡：附件行可点（未锁）').toEqual([false]);
    expect(r.before.boxResolved).toBe(false);
    expect(r.confirmEnabled, '选完首个问题后确认键可用').toBe(true);
    expect(r.after.boxResolved, '确认后卡进入终态（resolved）').toBe(true);
    expect(r.after.disabled, '确认后同一附件行**仍可用**——豁免语义（只读查看面 ≠ 重新作答）').toEqual([false]);
    await context.close();
  });

  // 【原开放项取证批 · 语义已由 askuser-attach-replyrender 批关闭】已决卡附件行
  //  现默认可点（lockOptionBox 豁免）⇒ 「复位 disabled 再点击」变成纯冗余动作：
  //  点击走下载腿 ⇒ 零 workspace-open-item、行不被降级/移除。本腿保留为回归钉。
  test('F6 豁免回归钉：已决卡附件行复位后再点击 ⇒ 下载腿、零 Canvas 开面', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate((msgs) => window.__replay(msgs), WITH_ATT);
    const r = await page.evaluate(() => window.__forceEnableAndClick(0));
    expect(pageErrors).toEqual([]);
    expect(r.clicked).toBe(true);
    expect(r.disabledAfterReset, '复位生效（disabled=false）').toBe(false);
    expect(r.rowStillInDom, '行未被降级/移除（下载腿可达）').toBe(true);
    expect(r.dispatched, '下载腿 ⇒ 零 workspace-open-item').toBe(0);
    await context.close();
  });

  // ✅ 「在位且可点」的实证腿 = main.js:1977 的 pending 重挂（历史行附件 ⇒ live
  // `renderAskUser` ⇒ 交互卡）。askuser-attach-replyrender 批后行入口 = **下载腿**：
  // 可观测面 = 票据轨 nf-file 取回（+1）+ 零 workspace-open-item（Canvas 面退役）。
  test('F7 pending 腿（main.js:1977 同形）：历史行附件还原到 live 交互卡 ⇒ 在位、可点、走下载腿', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const r = await page.evaluate((msgs) => window.__pendingRestore(msgs), PENDING_WITH_ATT);
    expect(pageErrors).toEqual([]);
    expect(r.read.boxes, '历史孪生卡已被回收，恰一张交互卡').toBe(1);
    expect(r.read.rows, '附件行在位').toBe(1);
    expect(r.read.boxResolved, '交互卡未进终态锁（pending）').toBe(false);
    expect(r.read.disabled, '附件行未禁用 ⇒ 可点').toEqual([false]);
    expect(r.click.dispatched, '真点击 ⇒ 下载腿 nf-file 取回已发生（可点性实证）').toBeGreaterThanOrEqual(1);
    expect(r.click.events, 'Canvas 面退役 ⇒ 零 workspace-open-item').toBe(0);
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
