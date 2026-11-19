// toolline-detail-stream.spec.mjs — 工具执行行增强批核心回归（作者 2026-10-06
// 16:51 令②③）：运行中工具行 = spinner + 工具名 + **类型化细节行**（映射表
// 可滚：delegate=任务 brief 首行 / bash=正在执行的具体命令 / 读=路径 / 写=路径 /
// 搜索=查询词 / web=URL / 其余=默认探测字段序）；细节流式只做 textContent
// 替换（span 跨帧复用，零节点重建）；完成勾选时该行翻转为单行结果摘要；
// 完成行点击展开（header toggle → tucked 卡回位 + 卡本体 body）照旧零回归。
//
// 数据面（回执证据链的运行时半边）：toolArgDelta 在 toolCallDetected→toolStart
// 之间流毕、toolStart 不复位缓冲（main.js 只在下一个 toolCallDetected 复位）
// ⇒ 本 spec 按真实 WS 事件形态喂 renderToolPending/appendToolStreamDelta/
// renderTool，全程零引擎改动（🔴 禁动引擎面守约）。
//
// Run: npx playwright test tests/toolline-detail-stream.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/toolline-detail/harness.html';
const SHOT_DIR = process.env.NB_KEEP_TEXT_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_toolline-detail-x1', 'detail-spec');

let port;
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

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
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
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 一条真实形态的工具运行流：toolCallDetected → toolArgDelta×n → toolStart
 *  → toolEnd。返回运行中（toolStart 后）与完成后（toolEnd 后）的 census。 */
async function runTool(page, { name, label, chunks, summary, content, input, isError }) {
  await page.evaluate(n => window.__tdUser('跑一个工具'), name);
  await page.evaluate(n => window.__tdCallStart(n), name);
  for (const chunk of chunks) {
    await page.evaluate(([n, c]) => window.__tdArg(n, c), [name, chunk]);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
  }
  const running = await page.evaluate(() => window.__tdCensus());
  await page.evaluate(l => window.__tdExecStart(l), label);
  const started = await page.evaluate(() => window.__tdCensus());
  await page.evaluate(([l, s, c, e, i]) => window.__tdDone(l, s, c, e, i), [label, summary, content, isError, input]);
  const done = await page.evaluate(() => window.__tdCensus());
  return { running, started, done };
}

test.describe('② typed running detail line (mapping table)', () => {
  test('Bash: the executing command streams into the detail line, node reused across frames', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 手工编排（不用 runTool）：节点同一性探针必须在**运行中**做，完成翻转
    // 会按设计换项。
    await page.evaluate(() => window.__tdUser('跑一个工具'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    for (const chunk of ['{"command":"git sta', 'tus && npm tes', 't","timeout":30}']) {
      await page.evaluate(c => window.__tdArg('Bash', c), chunk);
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    }
    // 运行中：细节行 = 正在执行的具体命令（参数增量实时成长）。
    const running = await page.evaluate(() => window.__tdCensus());
    expect(running.detailPresent).toBe(true);
    expect(running.detailText).toBe('git status && npm test');
    expect(running.itemKind).toBe('running');

    // 跨帧复用：打点后继续喂增量，同一节点（零重建）。
    await page.evaluate(() => window.__tdProbeDetail());
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"git status && npm test"}'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    const c = await page.evaluate(() => window.__tdCensus());
    expect(c.detailNodeTag).toBe('same-node');
    expect(c.itemCount).toBe(1); // 项未增长（无第二节点）

    // toolStart（终值重放）仍在；完成后翻转、细节 span 卸下。
    await page.evaluate(() => window.__tdExecStart('Bash\n  (git status && npm test)'));
    const started = await page.evaluate(() => window.__tdCensus());
    expect(started.detailText).toBe('git status && npm test');
    await page.evaluate(() => window.__tdDone('Bash\n  (git status && npm test)', 'No output', 'ok', false, '{"command":"git status && npm test"}'));
    const done = await page.evaluate(() => window.__tdCensus());
    expect(done.itemKind).toBe('done');
    expect(done.detailPresent).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('Delegate: the task brief FIRST LINE streams (stale `prompt` mapping fixed to schema field `task`)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const r = await runTool(page, {
      name: 'Delegate',
      label: 'Delegate(修复登录页的时区显示问题)',
      chunks: ['{"task":"修复登录页的时区', '显示问题\\n详细步骤：先读', ' login.js 再改"}'],
      summary: 'ok', content: 'done', input: '{"task":"修复登录页的时区显示问题"}',
    });
    expect(r.running.detailPresent).toBe(true);
    expect(r.running.detailText).toBe('修复登录页的时区显示问题'); // 首行，非整段
    expect(r.started.detailText).toBe('修复登录页的时区显示问题');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('Read: file path; Write: file path (content chunks do not hijack the line)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const rd = await runTool(page, {
      name: 'Read',
      label: 'Read\n  ("/tmp/very/long/path/report.md")',
      chunks: ['{"file_path":"/tmp/very/lon', 'g/path/report.md","offset":0}'],
      summary: '12 lines', content: 'body', input: '{"file_path":"/tmp/very/long/path/report.md"}',
    });
    expect(rd.running.detailText).toBe('/tmp/very/long/path/report.md');

    await page.evaluate(() => window.__tdReset());
    const wr = await runTool(page, {
      name: 'Write',
      label: 'Write\n  ("out.md")',
      chunks: ['{"file_path":"out.md","content":"', '# 标题\\n第一段\\n第二', '段\\n第三段"}'],
      summary: 'File created', content: 'ok', input: '{"file_path":"out.md","content":"# 标题"}',
    });
    // 细节 = 路径（content 体走既有参数流式大面板，不进细节行）。
    expect(wr.running.detailText).toBe('out.md');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('WebFetch: URL; Grep: query pattern; unknown tool falls back to the probe field order', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const wf = await runTool(page, {
      name: 'WebFetch', label: 'WebFetch(https://example.com/a)',
      chunks: ['{"url":"https://example', '.com/a","prompt":"sum"}'],
      summary: 'ok', content: 'html', input: '{"url":"https://example.com/a"}',
    });
    expect(wf.running.detailText).toBe('https://example.com/a');

    await page.evaluate(() => window.__tdReset());
    const gp = await runTool(page, {
      name: 'Grep', label: 'Grep("turnAnchor")',
      chunks: ['{"pattern":"turnAnch', 'or","path":"src"}'],
      summary: '3 matches', content: 'hits', input: '{"pattern":"turnAnchor"}',
    });
    expect(gp.running.detailText).toBe('turnAnchor');

    await page.evaluate(() => window.__tdReset());
    // 默认分支：映射表外工具按 DEFAULT 字段序取首个出现字段（TaskBoard → id）。
    const tb = await runTool(page, {
      name: 'TaskBoard', label: 'TaskBoard(list)',
      chunks: ['{"action":"list","id":', '"42"}'],
      summary: 'ok', content: 'rows', input: '{"action":"list","id":"42"}',
    });
    expect(tb.running.detailText).toBe('42');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('missing-field tools get NO detail line (no empty span, no layout residue)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const r = await runTool(page, {
      name: 'TaskBoard', label: 'TaskBoard(list)',
      chunks: ['{"action":"list"}'],
      summary: 'ok', content: 'rows', input: '{"action":"list"}',
    });
    expect(r.running.detailPresent).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('visual evidence: bash + delegate detail lines while running (screenshot)', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('看两类运行细节'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"cargo build --release"}'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (cargo build --release)'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    const shot1 = path.join(SHOT_DIR, 'after-running-bash-detail.png');
    await page.screenshot({ path: shot1, fullPage: true });

    await page.evaluate(() => window.__tdDone('Bash\n  (cargo build --release)', 'No output', 'ok', false, '{"command":"cargo build --release"}'));
    await page.evaluate(() => window.__tdFinishTurn(1000, 'test-model'));
    await page.evaluate(() => window.__tdInject('【deviceMail】远程指令：检查构建产物', 'deviceMail'));
    await page.evaluate(() => window.__tdCallStart('Delegate'));
    await page.evaluate(() => window.__tdArg('Delegate', '{"task":"检查 target/release 下的构建产物清单\\n输出前 20 行"}'));
    await page.evaluate(() => window.__tdExecStart('Delegate(检查 target/release 下的构建产物清单)'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    const shot2 = path.join(SHOT_DIR, 'after-running-delegate-detail.png');
    await page.screenshot({ path: shot2, fullPage: true });
    expect(fs.existsSync(shot1)).toBe(true);
    expect(fs.existsSync(shot2)).toBe(true);
    await context.close();
  });
});

test.describe('③ completion flip + expansion regression', () => {
  test('tool completion flips the line to a one-line result summary; detail span is gone; expand face intact', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('读一下文件'));
    await page.evaluate(() => window.__tdCallStart('Read'));
    await page.evaluate(() => window.__tdArg('Read', '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__tdExecStart('Read\n  ("a.md")'));
    const running = await page.evaluate(() => window.__tdCensus());
    expect(running.detailPresent).toBe(true);

    await page.evaluate(() => window.__tdDone('Read\n  ("a.md")', '12 lines', 'body', false, '{"file_path":"/tmp/a.md"}'));
    const done = await page.evaluate(() => window.__tdCensus());
    // 完成翻转：✓ + 单行结果摘要（工具名 — 本地化摘要），细节 span 卸下。
    expect(done.itemKind).toBe('done');
    expect(done.detailPresent).toBe(false);
    expect(done.itemText).toContain('读取');           // localizeToolLabel(Read) → zh 键
    expect(done.itemText).toContain('12 行');          // tool.result.lines → 「12 行」
    expect(done.itemText).not.toContain('/tmp/a.md'); // 运行细节不再滞留

    // 失败腿：cross + 摘要同样翻转。
    await page.evaluate(() => window.__tdFinishTurn(800, 'test-model'));
    await page.evaluate(() => window.__tdUser('再来一个失败的'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"exit 1"}'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (exit 1)'));
    await page.evaluate(() => window.__tdDone('Bash\n  (exit 1)', 'No output', 'boom', true, '{"command":"exit 1"}'));
    const failed = await page.evaluate(() => window.__tdCensus());
    expect(failed.itemKind).toBe('error');
    expect(failed.itemText).toContain('命令');          // localizeToolLabel(Bash) → zh 键
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('completed row click-expand is intact: header toggle untucks the card, card body still opens (零回归)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('展开面回归'));
    await page.evaluate(() => window.__tdCallStart('Read'));
    await page.evaluate(() => window.__tdArg('Read', '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__tdExecStart('Read\n  ("a.md")'));
    await page.evaluate(() => window.__tdDone('Read\n  ("a.md")', '12 lines', 'file body content', false, '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__tdFinishTurn(900, 'test-model'));

    // 完成行点击展开 = header toggle：tucked 工具卡回位。
    const beforeToggle = await page.evaluate(() => ({
      tucked: document.querySelectorAll('#chat .nf-tucked').length,
    }));
    expect(beforeToggle.tucked).toBeGreaterThan(0);
    await page.locator('.turn-header').first().click();
    const afterToggle = await page.evaluate(() => ({
      tucked: document.querySelectorAll('#chat .nf-tucked').length,
      toolVisible: Array.from(document.querySelectorAll('#chat .row.tool'))
        .every(r => r.offsetHeight > 0),
    }));
    expect(afterToggle.tucked).toBe(0);
    expect(afterToggle.toolVisible).toBe(true);

    // 卡本体展开面照旧：body 默认收起，点击卡 → 展开（attachToolClick 语义
    // 未触碰——完成翻转只动了 turn 行的项，不碰卡展开面）。
    const bodyDefault = await page.evaluate(() => {
      const card = document.querySelector('#chat .row.tool .tool-card');
      const body = card ? card.querySelector('.body') : null;
      return { hasCard: !!card, hasBody: !!body, bodyVisible: body ? body.offsetHeight > 0 : false };
    });
    expect(bodyDefault.hasCard).toBe(true);
    expect(bodyDefault.hasBody).toBe(true);
    expect(bodyDefault.bodyVisible).toBe(false); // 默认收起（既有语义）
    await page.locator('#chat .row.tool .tool-card').first().click();
    const bodyAfterClick = await page.evaluate(() => {
      const body = document.querySelector('#chat .row.tool .tool-card .body');
      return { bodyVisible: body ? body.offsetHeight > 0 : false };
    });
    expect(bodyAfterClick.bodyVisible).toBe(true); // 点击展开照旧
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 'after-expand-regression.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    await context.close();
  });
});
