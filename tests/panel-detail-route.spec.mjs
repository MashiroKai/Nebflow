// panel-detail-route.spec.mjs — 分层展示批核心回归（作者 2026-10-06 17:55 令 +
// dispatcher 追加令）：同源工具活动数据在两条视图分支下双形态渲染——
//
//   · 主聊天流（primary）：398dd72ac 单行流式工具行原样——turn 行 spinner+
//     类型化细节行、完成翻转、终态 badge、过程卡 tucked（本批主分支零改动，
//     仅路由守卫）；
//   · 面板视图（bgagent/flow 面板，.nf-panel 标记）：展开工具块——命令/输出/
//     结果详单全量展开（参照 b3c0851c8^ 展开块样式），**不吃细节行**；终态
//     badge 照落但过程行保持展开，header toggle 依旧真实有效。
//
// 路由收口点 = chatView.js ChatView 构造器（panelFace opt-in → `.nf-panel`
// 标记一次落定）；消费面 = chat.js（renderTool 完成块落位 + setWorklineToolDetail
// 守卫 + applyPanelDetailFace）、turnGroup.js（两处 tuck 守卫）、persistence.js
// （历史重放尾部归位）。本 spec 按真实 WS 事件形态喂同一条数据流进双视图，
// 锁「面板展开态与主行形态互斥」断言。
//
// 数据面事实（引擎 !isSubagent 门）：子代理腿无 toolArgDelta——面板分支的
// 展开态数据全部来自 toolEnd 帧 + 持久化历史（本 spec 直喂参数流属于超集
// 验证：即便有直喂，面板也不出细节行）。
//
// Run: npx playwright test tests/panel-detail-route.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/panel-detail/harness.html';
const SHOT_DIR = path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_panel-detail-route-x1', 'spec');

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

const ARGS = ['{"command":"git sta', 'tus && npm tes', 't","timeout":30}'];

/** 一条同源工具流喂进 face（'main'|'panel'，夹具已 __pdUse 切换 activeView）：
 *  user → toolCallDetected+args → toolStart → toolEnd → done 终态。返回运行中/
 *  完成/终态三拍 census。 */
async function runToolFlow(page, face) {
  await page.evaluate(() => window.__pdUser('跑一个工具'));
  await page.evaluate(() => window.__pdCallStart('Bash'));
  for (const chunk of ARGS) {
    await page.evaluate(([n, c]) => window.__pdArg(n, c), ['Bash', chunk]);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
  }
  const running = await page.evaluate(f => window.__pdCensus(f), face);
  await page.evaluate(l => window.__pdExecStart(l), 'Bash\n  (git status && npm test)');
  await page.evaluate(() => window.__pdDone('Bash\n  (git status && npm test)', 'No output', 'ok', false, '{"command":"git status && npm test"}'));
  const done = await page.evaluate(f => window.__pdCensus(f), face);
  await page.evaluate(() => window.__pdFinishTurn(1200, 'test-model'));
  const settled = await page.evaluate(f => window.__pdCensus(f), face);
  return { running, done, settled };
}

test.describe('route convergence (判定一处收口)', () => {
  test('marker: panel view stamps .nf-panel once at construction; main face and non-opted views do not', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const probe = await page.evaluate(() => ({
      panel: window.__pdCensus('panel'),
      main: window.__pdCensus('main'),
      plain: window.__pdNewPlainView(),
    }));
    // 面板视图：.nf-panel 落定（UI-G 的 .nf-static 同样在——面板同时是静窗）。
    expect(probe.panel.marker).toBe(true);
    expect(probe.panel.staticCls).toBe(true);
    // 主视图：无面板标记。
    expect(probe.main.marker).toBe(false);
    // 非面板非主视图（search-float 形态）：非 primary ≠ 面板——判定是显式
    // opt-in，不是「非主即面板」。
    expect(probe.plain.marker).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('panel face: expanded tool blocks (面板恢复)', () => {
  test('live flow: completed block untucked + body open (cmd/output readable); NO detail line even when args are fed', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    const r = await runToolFlow(page, 'panel');

    // 运行中：turn 行承载（spinner+label），但**不吃细节行**——参数即便直喂
    // 也不落 .nf-wl-detail（全量展开替代之）。
    expect(r.running.linePresent).toBe(true);
    expect(r.running.itemKind).toBe('running');
    expect(r.running.detailPresent).toBe(false);

    // 完成块：脱 tucked、可见、body open、命令/输出全量可读。
    expect(r.done.tools.length).toBe(1);
    expect(r.done.tools[0].tucked).toBe(false);
    expect(r.done.tools[0].visible).toBe(true);
    expect(r.done.tools[0].hasBody).toBe(true);
    expect(r.done.tools[0].bodyOpenCls).toBe(true);
    expect(r.done.tools[0].bodyVisible).toBe(true);
    expect(r.done.tools[0].cmdText).toBe('cmd: git status && npm test');
    expect(r.done.tools[0].preText).toBe('ok');

    // 终态：badge 落定，过程行**保持展开**（面板不收起）。
    expect(r.settled.headers.length).toBe(1);
    expect(r.settled.headers[0].state).toBe('done');
    expect(r.settled.tools[0].tucked).toBe(false);
    expect(r.settled.tools[0].visible).toBe(true);
    expect(r.settled.tools[0].bodyOpenCls).toBe(true);

    const shot = path.join(SHOT_DIR, 'after-panel-expanded.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel history restore: untucked rows + open bodies + header built (面板打开 = 展开工具块可见)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    await page.evaluate(msgs => window.__pdHistory(msgs), [
      { type: 'user', text: '面板历史一', timestamp: Date.now() },
      { type: 'tool', label: 'Bash\n  (cargo build --release)', summary: 'No output', content: 'compiled', isError: false, input: '{"command":"cargo build --release"}', timestamp: Date.now() },
      { type: 'ai', text: '历史回合最终回复。', durationMs: 3600, model: 'test-model', timestamp: Date.now() },
    ]);
    const c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.headers.length).toBe(1);
    expect(c.headers[0].state).toBe('done');
    expect(c.tools.length).toBe(1);
    expect(c.tools[0].tucked).toBe(false);
    expect(c.tools[0].visible).toBe(true);
    expect(c.tools[0].bodyOpenCls).toBe(true);
    expect(c.tools[0].bodyVisible).toBe(true);
    expect(c.tools[0].cmdText).toBe('cmd: cargo build --release');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel terminal toggle stays honest: click header tucks, click again untucks (默认展开，交互不废)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    await runToolFlow(page, 'panel');

    await page.evaluate(() => window.__pdClickHeader('panel')); // done → done-expanded（已展开，无视觉变化）
    let c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.headers[0].state).toBe('done-expanded');
    expect(c.tools[0].tucked).toBe(false);

    await page.evaluate(() => window.__pdClickHeader('panel')); // done-expanded → done（收起）
    c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.headers[0].state).toBe('done');
    expect(c.tools[0].tucked).toBe(true);
    expect(c.tools[0].visible).toBe(false);

    await page.evaluate(() => window.__pdClickHeader('panel')); // 再展开
    c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.tools[0].tucked).toBe(false);
    expect(c.tools[0].visible).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('main face: single-line form unchanged (主行不回归) + 互斥断言', () => {
  test('same feed into primary: detail line streams, completed card tucked, body closed, terminal tucks — the exact 398dd72ac form', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('main'));
    const r = await runToolFlow(page, 'main');

    // 运行中：类型化细节行在（主分支形态原样）。
    expect(r.running.linePresent).toBe(true);
    expect(r.running.itemKind).toBe('running');
    expect(r.running.detailPresent).toBe(true);
    expect(r.running.detailText).toBe('git status && npm test');

    // 完成卡：tucked（展开面）、body 默认收起。
    expect(r.done.tools[0].tucked).toBe(true);
    expect(r.done.tools[0].bodyOpenCls).toBe(false);

    // 终态：badge 落定 + 过程行收起（主分支 tuck 照旧）。
    expect(r.settled.headers.length).toBe(1);
    expect(r.settled.headers[0].state).toBe('done');
    expect(r.settled.tools[0].tucked).toBe(true);

    const shot = path.join(SHOT_DIR, 'after-main-singleline.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('mutual exclusion: identical data, two faces — every face field is the XOR of the other', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 同一条数据流先后喂两个面，再并排比对 census（同源数据、异构形态）。
    await page.evaluate(() => window.__pdUse('panel'));
    const panel = await runToolFlow(page, 'panel');
    await page.evaluate(() => window.__pdReset('panel'));

    await page.evaluate(() => window.__pdUse('main'));
    const main = await runToolFlow(page, 'main');

    // 数据同源：完成块的命令/输出内容逐字一致（同一构建器、同一数据）。
    expect(main.done.tools[0].cmdText).toBe(panel.done.tools[0].cmdText);
    expect(main.done.tools[0].preText).toBe(panel.done.tools[0].preText);

    // 形态互斥（四条 XOR）：
    expect(main.done.tools[0].tucked).toBe(true);
    expect(panel.done.tools[0].tucked).toBe(false);
    expect(main.running.detailPresent).toBe(true);
    expect(panel.running.detailPresent).toBe(false);
    expect(main.done.tools[0].bodyOpenCls).toBe(false);
    expect(panel.done.tools[0].bodyOpenCls).toBe(true);
    expect(main.settled.tools[0].tucked).toBe(true);
    expect(panel.settled.tools[0].tucked).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('boundary: while the panel is open the main flow keeps its own face (覆盖层机制——路由按视图固定，无全局切换)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 面板开着（panel 面完成一轮），主聊天流同时来一轮——两个容器各自形态，
    // 互不污染：开/关面板只搬面板容器（overlay ↔ hidden root），主视图路由
    // 从不切换（ws.js 按 sessionId → findViewBySessionId 定视图；主 face 由
    // 构造器一次性落定）。
    await page.evaluate(() => window.__pdUse('panel'));
    await runToolFlow(page, 'panel');

    await page.evaluate(() => window.__pdUse('main'));
    await runToolFlow(page, 'main');

    const both = await page.evaluate(() => ({
      panel: window.__pdCensus('panel'),
      main: window.__pdCensus('main'),
      distinctContainers: document.getElementById('panel-chat') !== document.getElementById('main-chat'),
    }));
    expect(both.distinctContainers).toBe(true);
    expect(both.panel.marker).toBe(true);
    expect(both.panel.tools[0].tucked).toBe(false);
    expect(both.main.marker).toBe(false);
    expect(both.main.tools[0].tucked).toBe(true);
    // 主面终态 = 单行 turn 线已同元素落成 badge（settleWorklineAsHeader 的
    // morph 契约：行消失 = 它已是 header）。面板 badge 独立共存。
    expect(both.main.linePresent).toBe(false);
    expect(both.main.headers.length).toBe(1);
    expect(both.main.headers[0].state).toBe('done');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('AskUserQuestion 追加令 (映射表补行)', () => {
  test('question streams into the main-face detail line; the card streaming face gets the same field (同源一致)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('main'));
    await page.evaluate(() => window.__pdUser('问个问题'));
    await page.evaluate(() => window.__pdCallStart('AskUserQuestion'));
    for (const chunk of ['{"question":"要继续部署', '到预发环境吗？"}']) {
      await page.evaluate(([n, c]) => window.__pdArg(n, c), ['AskUserQuestion', chunk]);
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    }
    const running = await page.evaluate(() => window.__pdCensus('main'));
    // 细节行：问句随 LLM 生成逐字上屏（作者 10-06 裁决点名例）。
    expect(running.detailPresent).toBe(true);
    expect(running.detailText).toBe('要继续部署到预发环境吗？');

    // 卡体流式面（TOOL_PRIMARY_FIELDS 同源行）：question 落 .tool-stream-body。
    const bodyFace = await page.evaluate(() => {
      const el = document.querySelector('#main-chat .tool-stream-body pre');
      return el ? el.textContent : '';
    });
    expect(bodyFace).toBe('要继续部署到预发环境吗？');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel face: the same AskUserQuestion feed produces NO detail line (面板不吃细节行)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    await page.evaluate(() => window.__pdUser('问个问题'));
    await page.evaluate(() => window.__pdCallStart('AskUserQuestion'));
    for (const chunk of ['{"question":"要继续部署', '到预发环境吗？"}']) {
      await page.evaluate(([n, c]) => window.__pdArg(n, c), ['AskUserQuestion', chunk]);
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    }
    const running = await page.evaluate(() => window.__pdCensus('panel'));
    expect(running.detailPresent).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
