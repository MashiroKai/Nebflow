// askuser-badge-conditional.spec.mjs — AskUser badge 分组条件化回归钉（作者
// 2026-10-07 18:36 三令之②·新口径）。
//
// 语义升级：AskUser 作答行与外部注入触发的 turn 分组从两把静态刀
//   · 398dd72ac「注入腿统一重开」（workline turnAnchor 任何 user 行重锚）
//   · 0584bf0bb「作答行豁免」（带 data-nf-ask-answer 的行永不作轮界）
// 升为**条件判定**（badge 终态判据单点 = workline.priorTurnBadgeSettled，
// 终态判据读现面 badge 状态字段 .turn-header[data-turn-state^=done]）：
//   上一轮 badge 未到终态 → 并入同组（T-合并）
//   上一轮 badge 已终态   → 下方新开 badge 单行流式，与用户输入同形（T-新开）
//
// 四钉（两分支 × 两行型）：
//   T1 T-合并·作答腿  busyTail 重放（交换中途、ack badge 在作答行上方）→
//                     live 续跑：行线锚轮顶（不劈轮）；终态整交换一枚 header
//                     ——askuser-expandfix B3 同形不回归（09:25 令面）。
//   T2 T-合并·注入腿  busyTail 重放开尾含注入泡 → live 续跑：行线锚轮顶
//                     （旧静态刀会骑到注入泡之后）；终态一枚 header 罩全组。
//   T3 T-新开·作答腿  作答行上方 exchange 已闭（其卡在已闭组内、其后另有
//                     一整轮已终态）→ busyTail 重放：作答行劈为新组 opener；
//                     live 续跑行线**直接落在作答行之后**（与用户输入同形）；
//                     终态自建 badge，前组 header 不被吞并。
//   T4 T-新开·注入腿  前轮 E5 文本轮无 header 闭组（无 badge 可读）→ 空闲注
//                     入：行线仍落注入泡之后（398dd72ac 保脸），终态新
//                     header 贴注入泡——旧静态刀核心面不因条件化回退。
//
// Drives the REAL render modules through tests/fixtures/askuser-expand/
// harness.html（重放+live 双腿）与 tests/fixtures/toolline-detail/harness.html
// （live 注入腿）over throwaway static servers（🔴 绝非 8080 宿主）。
//
// Run: npx playwright test tests/askuser-badge-conditional.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const AX_HARNESS = '/tests/fixtures/askuser-expand/harness.html';
const TD_HARNESS = '/tests/fixtures/toolline-detail/harness.html';
// 取证截图目录（默认进仓内 .nebflow 证据位；NB_BADGE_COND_SHOT_DIR 可改道）。
const SHOT_DIR = process.env.NB_BADGE_COND_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261007_askuser-badge-conditional', 'badge-spec');

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
  await waitUntilUp(`http://127.0.0.1:${port}${AX_HARNESS}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser, harness) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${harness}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const ASK_ITEMS = [{ question: '用哪个方向继续?', options: [{ label: '方向 A' }, { label: '方向 B' }] }];
const T0 = 1728200000000;
const TOOL = (label, summary, content, input, ts) => ({
  type: 'tool', label, summary, content, isError: false, input, timestamp: ts,
});

test.describe('AskUser badge 分组条件化 (2026-10-07 18:36 三令之②)', () => {
  // ── T-合并：上一轮 badge 未到终态 → 并入同组 ─────────────────────────────
  test('T1 作答腿：交换中途 busyTail 重放（ack badge 在作答行上方）→ live 续跑锚轮顶、整交换一枚 header', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, AX_HARNESS);
    // 真实两轮历史形：ack 轮已终态（badge 在场）但作答续跑未完成 ⇒ 交换未闭。
    const msgs = [
      { type: 'user', text: '帮我看下仓库状态，然后问我一个方向问题', timestamp: T0 },
      TOOL('Bash\n  (git status)', '成功', 'clean', { command: 'git status' }, T0 + 1000),
      { type: 'askUser', items: ASK_ITEMS, requestId: 'req-cond-merge', agentName: 'Nebula', timestamp: T0 + 2000 },
      { type: 'ai', text: '卡片已发出，等你选方向。', durationMs: 4200, model: 'test-model', timestamp: T0 + 3000 },
      { type: 'user', text: '方向 A', answerOf: 'req-cond-merge', timestamp: T0 + 5000 },
      TOOL('Bash\n  (npm test)', '成功', 'ok', { command: 'npm test' }, T0 + 6000),
    ];
    await page.evaluate((m) => window.__axReplay(m, { busyTail: true }), msgs);
    const r1 = await page.evaluate(() => window.__axCensus());
    expect(r1.headers).toHaveLength(0); // 开尾（badge 未到交换终态）不落组
    // live 续跑：行线锚**轮顶**（作答行上方虽有 ack badge，但它不是交换终态
    // ⇒ 作答行并入同组，不重锚、不劈轮——badge 终态判据单点读卡面）。
    await page.evaluate(() => window.__axCallStart('Bash'));
    const up = await page.evaluate(() => window.__axCensus());
    expect(up.childSeq[0]).toBe('user');
    expect(up.childSeq[1]).toBe('workline'); // 轮顶，非作答行后
    await page.evaluate(() => window.__axDone('Bash\n  (npm run e2e)', '成功', 'done', false, '{"command":"npm run e2e"}'));
    await page.evaluate(() => window.__axFinishTurn(900, 'test-model', '已确认方向 A，全部完成。'));
    const fin = await page.evaluate(() => window.__axCensus());
    expect(fin.headers).toHaveLength(1); // 整交换一枚 badge（B3 面不回归）
    expect(fin.headers[0].text).toContain('工具 3 次');
    expect(fin.tools.every(x => x.tucked)).toBe(true);
    expect(fin.childSeq[1]).toBe('header');
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 't1-merge-answer-same-group.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    await context.close();
  });

  test('T2 注入腿：busyTail 开尾含注入泡 → live 续跑行线锚轮顶（并入同组），终态一枚 header 罩全组', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, AX_HARNESS);
    const msgs = [
      { type: 'user', text: '跑一个长任务', timestamp: T0 },
      TOOL('Bash\n  (sleep 30)', '成功', 'ok', { command: 'sleep 30' }, T0 + 1000),
      { type: 'user', injected: true, text: '【节点消息】swift-dev 汇报：子任务已启动', source: 'nodeMessage', timestamp: T0 + 2000 },
      TOOL('Bash\n  (npm run build)', '成功', 'built', { command: 'npm run build' }, T0 + 3000),
    ];
    await page.evaluate((m) => window.__axReplay(m, { busyTail: true }), msgs);
    const r1 = await page.evaluate(() => window.__axCensus());
    expect(r1.headers).toHaveLength(0); // 开尾无 badge
    // live 续跑：注入泡上方 group 未到终态 ⇒ 并入同组——行线锚**轮顶**，
    // 不重锚到注入泡之后（旧 398dd72ac 静态刀会落 childSeq[3]）。
    await page.evaluate(() => window.__axCallStart('Read'));
    const up = await page.evaluate(() => window.__axCensus());
    expect(up.childSeq[0]).toBe('user');
    expect(up.childSeq[1]).toBe('workline'); // 轮顶 = 并入同组
    expect(up.childSeq[3]).toBe('user');     // 注入泡仍在行线之下原位
    await page.evaluate(() => window.__axDone('Read\n  ("/tmp/a.md")', '12 lines', 'body', false, '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__axFinishTurn(700, 'test-model', '续跑完成。'));
    const fin = await page.evaluate(() => window.__axCensus());
    expect(fin.headers).toHaveLength(1); // 一枚 header 罩全组（含注入泡所在交换）
    expect(fin.headers[0].text).toContain('工具 3 次');
    expect(fin.childSeq[1]).toBe('header');
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 't2-merge-injected-same-group.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    await context.close();
  });

  // ── T-新开：上一轮 badge 已终态 → 下方新开 badge 单行流式 ────────────────
  test('T3 作答腿：作答行上方交换已闭（卡在已闭组、其后另有已终态轮）→ 作答行开新组、行线贴作答行（与用户输入同形）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, AX_HARNESS);
    // 组1（含卡 req-cond-open，ack 轮带 badge 闭组）→ 组2（普通轮，badge 闭组）
    // → 迟到作答行（answerOf 指组1 的卡）→ 续跑工具（开尾）。
    const msgs = [
      { type: 'user', text: '第一问：看下仓库状态', timestamp: T0 },
      TOOL('Bash\n  (git status)', '成功', 'clean', { command: 'git status' }, T0 + 1000),
      { type: 'askUser', items: ASK_ITEMS, requestId: 'req-cond-open', agentName: 'Nebula', timestamp: T0 + 2000 },
      { type: 'ai', text: '卡片已发出。', durationMs: 4200, model: 'test-model', timestamp: T0 + 3000 },
      { type: 'user', text: '第二问：跑下测试', timestamp: T0 + 4000 },
      TOOL('Bash\n  (npm test)', '成功', 'ok', { command: 'npm test' }, T0 + 5000),
      { type: 'ai', text: '第二问完成。', durationMs: 900, model: 'test-model', timestamp: T0 + 6000 },
      { type: 'user', text: '方向 A', answerOf: 'req-cond-open', timestamp: T0 + 7000 },
      TOOL('Bash\n  (npm run build)', '成功', 'built', { command: 'npm run build' }, T0 + 8000),
    ];
    await page.evaluate((m) => window.__axReplay(m, { busyTail: true }), msgs);
    const r1 = await page.evaluate(() => window.__axCensus());
    // 作答行上方两枚 badge 均已终态 ⇒ 作答行劈为新组 opener：组1、组2 各一枚
    // header（组1 罩卡；组2 不被迟到作答吞并）；开尾平铺。
    expect(r1.headers).toHaveLength(2);
    expect(r1.headers[0].text).toContain('工具 1 次');
    expect(r1.headers[1].text).toContain('工具 1 次');
    expect(r1.childSeq[9]).toBe('user');      // 作答行 = 新组 opener 行
    expect(r1.childSeq.filter(s => s === 'header')).toHaveLength(2);
    // live 续跑：上一轮 badge 已终态 ⇒ 行线**直接落在作答行之后**（与用户输入
    // 同形断言：fresh user 行后同样贴行线），下方新开 badge 单行流式。
    await page.evaluate(() => window.__axCallStart('Read'));
    const up = await page.evaluate(() => window.__axCensus());
    expect(up.childSeq[9]).toBe('user');
    expect(up.childSeq[10]).toBe('workline'); // 贴作答行 = 新组行线
    expect(up.childSeq[11]).toBe('tool(FLAT)');
    await page.evaluate(() => window.__axDone('Read\n  ("/tmp/b.md")', '4 lines', 'body', false, '{"file_path":"/tmp/b.md"}'));
    await page.evaluate(() => window.__axFinishTurn(600, 'test-model', '补跑完成。'));
    const fin = await page.evaluate(() => window.__axCensus());
    expect(fin.headers).toHaveLength(3); // 新组自己的 badge（下方新开）
    // 新组 scope = 作答行之后的续跑工具（重放 t3 + live Read）⇒ 工具 2 次。
    expect(fin.headers[2].text).toContain('工具 2 次');
    // 前组 header 不被吞并/重写（fence 完好）。
    expect(fin.headers[0].text).toBe(r1.headers[0].text);
    expect(fin.headers[1].text).toBe(r1.headers[1].text);
    expect(fin.childSeq[10]).toBe('header'); // 行线原地翻转为新组 header
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 't3-new-open-answer-own-group.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    await context.close();
  });

  test('T4 注入腿：前轮 E5 文本轮无 header 闭组 → 空闲注入行线仍贴注入泡（398dd72ac 保脸），终态新 header 贴注入泡', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, TD_HARNESS);
    // 前轮 = 纯文本问答（E5：无 tuckable ⇒ 无 header 闭组——无 badge 可读）。
    await page.evaluate(() => window.__tdUser('纯文本问答，无工具'));
    await page.evaluate(() => window.__tdFinishTurn(800, 'test-model', '纯文本回复。'));
    const c0 = await page.evaluate(() => window.__tdCensus());
    expect(c0.headerCount).toBe(0); // E5 无 header（条件化的「无 badge」态）
    // 空闲注入：注入泡是最后一个 child ⇒ 下方新开（行线贴注入泡之后）——
    // 行线不得落进已闭轮内部（Q/A 之间）。
    await page.evaluate(() => window.__tdInject('【定时】每日站会提醒', 'schedule'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    const up = await page.evaluate(() => window.__tdCensus());
    expect(up.linePresent).toBe(true);
    expect(up.linePrevIsInjected).toBe(true); // 行线贴注入泡 = 新组开轮面保脸
    await page.evaluate(() => window.__tdDone('Bash\n  (git status)', 'No output', 'ok', false, '{"command":"git status"}'));
    await page.evaluate(() => window.__tdFinishTurn(1200, 'test-model', '注入轮完成。'));
    const fin = await page.evaluate(() => window.__tdCensus());
    expect(fin.headerCount).toBe(1);
    expect(fin.headerMeta[0].prevIsInjected).toBe(true); // 新 header 贴注入泡
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 't4-new-open-injected-own-group.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    await context.close();
  });
});
